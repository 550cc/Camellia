# camd × HyperMount 完整代码级对比评估 + camd 优化（修订版）

> 完整阅读：HyperMount `watchd.c(655)/actiond.c(1626)/ctl.c(1768)` + `main/*.sh`；
> 本仓 camd `bridge(848)/sock(260)/rescue(6277)/dsnap(821)`。非"去子进程"视角，聚焦**质量/正确性/健壮性/体验**。

## 一、能力矩阵（谁更优 + 源码依据）
| 能力 | HyperMount | camd | 结论 |
|---|---|---|---|
| SystemUI 死亡检测 | **pidfd 事件即时**（watchd:open_pidfd） | tick 采样 pid(≤30s) | HM 优 |
| 卡死/ANR/日志疯长/StackOverflow | **有**（actiond:is_fatal_line/LOG_FLOOD/STACK_OVERFLOW） | **无**（只认重启循环） | HM 优(漏判) |
| 自身重启不误计 | **`.self_restart` 标记 5min 内跳过**（watchd:skip_self_restart） | `reset_pid_baselines`(仅 pid 基线) | HM 更通用 |
| 崩溃归因精度 | 仅按 target pid 重启 | **dropbox 正文归因 SYSUI/PLUGIN/BOTH** | **camd 优** |
| 防过度修复 | 简单阈值 | **sr_count/sr_total 预算 + nofx + halt 熔断 + VERIFY 观察窗 + 每boot一次** | **camd 优** |
| 装包事件感知 | 无 | **watch_install(apk_stamp)** | camd 优 |
| shield | device_config+settings global，含 min/reset interval，dumpsys 校验(ctl:ams_apply_limit/dc_put) | 仅 settings global count_limit + 回读 | HM 更全 |
| 自保(进程) | **C --guard**:flock+收僵尸+续 since+放弃阈值 | shell `while sleep 60` | HM 优 |
| 开机防误判 | boot_completed+**sleep15** 再 arm | A段 up<90；B段无延迟 | HM 更全 |
| 音量键救砖 | **有**(getevent 识别+首次校验) | 无 | HM 优 |
| 控制面/诊断 | 单 ctl 多子命令一次吐(webctl/check 合并) | socket 推送状态；诊断多次 exec | 各有所长(camd 推送优/诊断 HM 优) |
| 数据备份/恢复 | 无 | **有**(布局/主题/壁纸/锁屏 增量+VACUUM+setns) | **camd 独有** |
| 读 app 私有库 | 无(uid 不够) | **setns 全局 ns 读 u0_a146** | **camd 独有** |
| 恢复策略 | bind-overlay 换 SystemUI apk | **卸更新/pclear/软重启 系统级回退** | 场景不同 |

## 二、我们的 camd 能"做得更好"的点（现状→改法→收益/风险）
### A. 检测与判定（最该补）
1. **加 pidfd 检测 SystemUI/plugin 死亡**（现 tick 采样）。
   改：`open_pidfd` 加入 bridge 主 poll；死即触发一次归因采样（仍走 dropbox/pid 判真伪）。老内核回退扫 /proc。
   收益：检测 ≤30s→毫秒；稳态不再空转。风险：低（Android11+ 本机17）。
2. **补"卡死/ANR/日志疯长/StackOverflow"判据**（现完全没有，黑屏卡死漏判）。
   改：arm 时按需读 `logcat -b crash`，加 is_fatal_line + 行数占比判据（HM 阈值：`at `>5000 且占比>80% 且文件>1MB）。
   收益：覆盖"进程没死但卡死"。风险：logcat 要按需/短窗，别常驻耗电。
3. **统一 `.self_restart` 标记**（现仅 reset_pid_baselines，只覆盖 pid 基线；dropbox 侧/其它动作未覆盖）。
   改：恢复/换主题/软重启前写带时间戳 marker；rescue 命中(≤5min)跳过并消费（与 reset_pid_baselines 并用）。
   收益：任何"我们自己的重启"都不被计崩溃，通用、可复用。风险：低（TTL 防残留）。
4. **B 段加"开机后再等 ~15s 才 arm"**（现 A 段 up<90，B 段无）。
   收益：避开开机 SystemUI 正常重启误判。风险：低。

### B. 屏蔽(shield)完整度
5. **补 `min_crash_interval` / `crash_count_reset_interval` + device_config 双写 + dumpsys 校验**（现只 settings global 的 count_limit + 回读）。
   收益：防止系统按"重置间隔"清零导致阈值口径失效；device_config 与 settings 双保险（HM 做法）。风险：低。

### C. 自保与体验
6. **C `--guard` 自守护**（替 shell `while sleep60`）：flock 单实例 + waitpid 收僵尸 + 从 state 续 since + 连续 N 次起不来就放弃（HM guard_main）。
   收益：更稳、无 shell、统计不跳变。风险：中（flock/僵尸必须处理对）。
7. **音量键救砖**（getevent 识别+首次实测校验）+ 首次运行自检 UX（HM ctl check/detect_keys）。收益：UI 点不动时的救命通道。风险：中（设备差异，需实测校验）。
8. **观测/诊断增强**：崩溃/调试日志分级、把 DE/CE、当前 pid、窗口计数、上次修复原因、sr 预算 暴露进状态（camd 已有多数字段，补齐）；App 面板展示。收益：排障。风险：低。

### D. 备份引擎打磨（camd 独有，别丢优势）
9. **恢复异步化/分段**：现恢复大 tar(VACUUM+tar+restorecon) 在主线程同步阻塞循环(几十秒~分钟)；已加 busy 兜底，但体验一般。
   改：恢复放工作线程/按需子进程，主循环继续服务状态；或分单元恢复+进度回显。收益：忙时不卡 UI/命令。风险：中（SQLite THREADSAFE=0，需串行保证同一时刻只有一个 dsnap 任务）。
10. **指纹抗盲区**：主题/壁纸"元数据(size+mtime)"对"同大小同 mtime 原地改"会漏；对关键小文件(lock.xml/home.xml/wallpaper_info.xml)加内容 md5（wallpaper_info 已 md5）。收益：更准。风险：低。
11. **恢复前隐藏自保**：可选在恢复前抓一份"pre-restore"但**不计入 keep/不参与 prune**（另存 hidden 目录），出错可回滚；避免之前"自保快照把目标顶掉"的问题。收益：安全。风险：低。
12. **恢复顺序/失败回滚**：主题 `rm -rf` 后 `tar -x` 失败的窗口风险 → 先解到临时目录校验再切换；db 已 quick_check。收益：稳健。

### E. 架构（可选，谨慎）
13. **watch/action 分层**（轻常驻 pidfd 看护 + 出事故才起重活）——HM 的做法；但我们"不去子进程"，可折中为**单进程内**：pidfd 事件驱动 + 重活用工作线程，保持单 binary。

## 三、优先级建议（不涉及"去子进程"）
- **P0（正确性/漏判，先做）**：1 pidfd、2 卡死判据、3 self_restart、5 shield 补全。
- **P1（健壮/体验）**：4 boot 延迟 arm、6 C guard、8 观测、9 恢复异步化。
- **P2（打磨/可选）**：7 音量键、10 指纹 md5、11 隐藏自保、12 回滚、13 分层。

## 四、必须保持的 camd 优势（别在优化中丢失）
- **dropbox 正文归因**(区分 systemui/plugin) + **sr 预算/halt/VERIFY/install watch**（比 HM 更完善的"防乱修"体系）。
- **setns 读私有库 + dsnap 增量备份恢复**（HM 完全没有的护城河）。
- **socket 推送状态**（优于 HM 的 1.5s 拉取）+ **单 binary 整合**。

## 五、结论
- **谁更强**：检测手段/自保/屏蔽细节/音量键 **HM 强**；归因精度/防过度修复/数据备份/私有库/socket 推送 **camd 强**。
- **camd 优化正道**：吸收 HM 的 **pidfd + 卡死判据 + self_restart + shield 补全 + C guard + boot延迟 + 音量键**，并**打磨备份引擎**(异步恢复/指纹 md5/隐藏自保)，同时**保住 camd 已有的归因与预算熔断体系**。
- 这些多数是 rescue/bridge 内部增量，风险可控、可逐个真机验证。
