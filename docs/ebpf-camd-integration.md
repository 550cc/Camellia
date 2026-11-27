# eBPF → camd 适配设计 + 实测结论

## 一、传感器实测结论（已在真机跑通）
- `raw_tp/sched_process_exit` + `bpf_ringbuf`：事件驱动、过滤 comm 含 systemui，抓到 `EXIT pid=.. comm=ndroid.systemui`（4 条），**tracing_on 前后=0（不写全局 trace、不常驻 logcat）**，attach=1。→ 生产形态可用。
- **关键教训：`signal_generate` 的 SIGSEGV 不可靠** —— 一次测试收到 **342 条 SIGSEGV 但 systemui 活着、0 dropbox 崩溃**（Android 运行时大量用 SIGSEGV 做非致命用途）。**不能用"收到 SIGSEGV"判崩**，否则误报爆炸。故传感器收敛为 **只用 EXIT 事件**。

## 二、诚实评估：eBPF 相对现状的增益
| 能力 | 现状(camd) | eBPF(EXIT) | 结论 |
|---|---|---|---|
| 目标进程死亡检测 | pidfd（盯 systemui/plugin 主进程，即时）| sched_process_exit（任意目标子进程、带 comm）| 略强：覆盖所有 target 进程；但 pidfd 已够主进程 |
| 崩溃 vs 正常/kill 区分 | dropbox 归因 + self_restart | 纯 EXIT 无法区分；signal 太吵 | **eBPF 无增益**（除非做 exit_code CO-RE） |
| IO/常驻 | 无 logcat；dropbox 目录 stat | 无 logcat、无 dropbox、纯事件 | 略省 |
- 结论：**eBPF 主要能把"目标死亡检测"做得更全（所有 target 子进程、事件驱动），但"崩溃判定"这块没有比 pidfd+dropbox 明显更强**，且要引入 libbpf/BPF 复杂度。ROI 中等。

## 三、三种集成方式（供选择）
### A. 嵌入 camd（camd 直接跑 BPF，最优但最重）
- camd 静态链接 libbpf(+elf+z+zstd)，把 `sysui_mon.bpf.o` 编成字节数组内嵌；启动时 `bpf_object__load`+attach；主循环每轮 `ring_buffer__poll(rb,0)` 消费 EXIT → 命中目标则 `rescue_kick()`（提前触发一次现有 tick 判定，不改记账）。
- 优点：单进程、零额外进程、事件驱动。缺点：camd 体积+依赖增大、需设备 BTF、失败要 fallback（无 eBPF → pidfd/dropbox）；改 camd 主循环，风险中等。

### B. 旁挂传感器 sidecar（camd 不动 BPF，折中）
- 独立 `camd-ebpf` 守护（就是现在的 loader 改）：attach 后把"target EXIT"写到 `state/ebpf_exits`（append 行 `ts pid comm`）；camd 用 inotify 盯该文件，读到新行→`rescue_kick()`。
- 优点：BPF 复杂度与安全关键的 camd 隔离；camd 改动极小（加一个 inotify watch）。缺点：多一个常驻进程（要纳入 watchdog+自动拉起）；用户曾偏好"少子进程"。

### C. 不集成，作为诊断/实验工具保留
- 保留 `/data/local/tmp` 的传感器，仅在排障时手动跑；camd 维持 pidfd+dropbox。优点：零风险。缺点：没"适配"进产品。

## 四、建议
- 若"适配"目标是**把死亡检测做成事件驱动、更全**：选 **A（嵌入 camd）**——但只当作 pidfd 的增强/替代，`rescue_kick` 复用现有判定，不改记账；带 fallback。
- 若要**零风险隔离**：选 **B（sidecar）**。
- 若发现 ROI 不够、pidfd 已够用：选 **C**。
- 无论哪种：崩溃"是不是真崩"的最终判定仍用 **dropbox 归因 + self_restart（+可选 exit_code CO-RE）**，不靠裸 SIGSEGV。

## 五、实现清单（若选 A）
1. 构建脚本：`-lbpf -lelf -lzstd -lz` 静态链进 camd；`xxd -i sysui_mon.bpf.o > bpf_blob.h` 内嵌。
2. camd 启动：写临时 .o→`bpf_object__open_mem`→load→attach→存 `rb`；poll 循环加 `ring_buffer__poll(rb,0)`；回调里对 target EXIT 置 `g_force_tick`（同 pidfd）。
3. fallback：加载失败则日志 + 继续用 pidfd/dropbox。
4. 验证：kill systemui → camd 日志出现一次提前判定；稳态无输出/无 tracing_on。
