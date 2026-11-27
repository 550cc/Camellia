# camd 优化设计（对标 HyperMount 全量源码分析）

> 依据：完整阅读 HyperMount(`module/src/{watchd,actiond,ctl}.c` + `module/main/*.sh`) 与
> 本项目 camd(`camellia_bridge.c` 848 / `camellia_sock.c` 260 / `camellia_rescue.c` 6277 / `camellia_dsnap.c` 821)。

## 1. HyperMount 架构（要点，带源码位置）
- **watchd(常驻, 655行)**：`poll` 用 **pidfd_open** 盯 SystemUI pid（进程退出内核即通知，零轮询核心）；老内核回退每秒扫 `/proc`；**即使有 pidfd 也 ~4s 扫一次对账**兜底。**`--guard` 子进程互保**（同代码页、`flock` 单实例、`waitpid(WNOHANG)` 收僵尸、从 `watch.state` 续 `since/stamps` 使运行时长不跳变）。判定：WINDOW 秒内重启 COUNT 次。
- **`config/.self_restart` 标记**：模块自己换 SystemUI 时 `mark_self_restart()` 写时间戳；watcher `skip_self_restart(ts)` 命中(≤5min)即消费跳过，不计崩溃。
- **actiond(按需, 1626行)**：崩溃才起。判据：`is_fatal_line`（Fatal signal/Process has died/WATCHDOG/ANR + systemui）；**卡死检测**：crash 缓冲含 `StackOverflowError`/`at ` 行>5000 且占比>80% 且文件>1MB（`STACK_OVERFLOW`）；**日志持续增长**（`LOG_FLOOD`，按 `flood` 配置，连续两次）；`STACK_MAX_MB` 60MB 封顶轮转。**音量键救砖**（`getevent` 识别音量设备 `parse_input_devs`+首次 `verify_key` 实测校验，`ge_poll`）。替换前 `mark_self_restart`。
- **ctl(控制面, 1768行, 全C)**：把 webctl/check 原每 1.5s 20+ fork 合并成**单进程**子命令：`ls/prepare/test-run/status/output/stop/rescue/watch/boot/boot-start/log-run/check(env,keys,scan,up,down,save,verify)`。`ctl boot`：等 `sys.boot_completed`(≤10min)→**再 `sleep 15`**（"开机后 SystemUI 正常重启一两次，太早起会被算进崩溃窗口"）→起 watch/log。
- **shield**：`dc_put/dc_get`(device_config `activity_manager`) + `ams_apply_limit`(settings global `activity_manager_constants`) 双写，含 `min_crash_interval`/`crash_count_reset_interval`；`read_limit_dumpsys` 校验；增强模式强制开。
- **进程模型分层**：watchd(轻常驻) + actiond(按需) + ctl(控制面)，`service.sh/action.sh` 只是 `exec` 转交 C 的薄壳。
- **偏好**：事件(pidfd)优先、C 化少 fork、控制面单进程、按需起重活、状态原子写(tmp+rename)、自保(guard)。

## 2. camd 架构（要点）
- **单线程 `poll` 主循环**(`camellia_bridge.c:610 main`)：`inotify`(命令目录 BRIDGE_DIR + UI 目录) + socket 监听 + ≤4 客户端；`to=(ncli>0||!fast)?5000:30000` ms。每轮 `rescue_write_status()`(ui_dump 45s 节流) + `rescue_tick()`(tick_sec 30s 节流) + inotify 排空 + 兜底 `sweep()`。
- **命令同步执行**：`rescue_poll_cmd→ui_apply→{rescue 动作, dsnap_*}`；dsnap 重活(VACUUM/tar/restorecon)在**主线程内**跑，会卡循环。
- **rescue(6277行, 175 顶层函数)**：A段(DE)`a_pid_track` 采样 pid 变化；B段(CE)`dropbox_window`+`pid_change` 计数(`crash_hist`)；`VERIFY` 观察窗；软重启收口 `do_soft_reboot`:`crash_hist_reset`+`reset_pid_baselines`+`sr_drop_max`+`sr_rebase` 水位；`halt` 熔断；`sr_count/sr_total` 预算；插件归因；`watch_install_tick`(apk_stamp)。
- **shield(`shield_apply`)**：`settings put global activity_manager_constants '...process_crash_count_limit=N...'`（**整串替换**）+ `SHIELD_PROP_ON` + 回读校验；**仅 count_limit，无 device_config、无 min/reset interval**。
- **dsnap**：按单元增量备份/恢复（布局 digest 去 prefs 噪声；主题/壁纸/视频 元数据指纹 + 硬链接复用 + 全未变跳过；restore 就地覆盖+restorecon+settings+重启引擎+`rescue_after_own_restart`）。`setns(/proc/1/ns/mnt)` 读 `/data/user_de` 私有库（HyperMount 没有的能力）。
- **fork 现状**：rescue 中 `sh_run/popen/fork/system` 约 **31 处**（settings/tar/restorecon/pidof/reboot 等）；**shell watchdog** 由 `camellia_action.sh` 等 `while; sleep 60` 拉起；App 侧诊断每条 `su -c`。

## 3. 差距表（camd vs HyperMount）
| 维度 | HyperMount | camd | 结论 |
|---|---|---|---|
| 进程死亡检测 | pidfd 事件(即时) + 4s 对账 | tick 采样(≤30s) | 抄 pidfd |
| 卡死(进程没死) | 日志疯长/StackOverflow 判据 | **无(漏判)** | 抄卡死检测 |
| 自己重启不误计 | `.self_restart` 标记 | 刚加 reset_pid_baselines | 加标记更通用 |
| 守护互保 | C --guard(flock+僵尸回收+续跑) | shell 脚本 loop | 换 C guard |
| 控制面/fork | 单 ctl 合并 20+fork | socket 推送好，但诊断多次 exec | 加 ctl 查询 |
| shield | device_config+settings，min/reset | 仅 settings count_limit | 补全 |
| 开机防误判 | boot 后再等 15s | A段 up<90；B段无 | 加延迟 arm |
| 音量键救砖 | 有 | 无 | 可选加 |
| 数据备份(setns/增量) | 无 | **有** | camd 护城河，保留 |
| App 状态通道 | 1.5s 拉 ctl | socket 推送 | camd 更好，保留 |

## 4. camd 优化蓝图（目标架构）
**总原则**：保持单 C binary（不引入 App 侧常驻守护）；**事件驱动优先、去轮询/去 shell 子进程**；保留 dsnap/setns/socket 推送优势；**最小增量、分阶段、每步真机验证**。

主循环目标形态：`poll(inotify_cmd, socket_listen, clients, **pidfd(sysui)**, **pidfd(plugin)**)`；
- 事件：命令、socket、**SystemUI/plugin 死亡(pidfd)**；
- 周期(仅心跳)：`ui_dump`(45s)、`rescue_tick`(非事件检查：shield 1h、install watch)、`dsnap_heartbeat`(30s 时门)；
- 重活(tar/restorecon)可选移到工作线程/按需子进程，避免卡循环；
- C `--guard` 子进程自保；`ctl` 一次性查询子命令 + socket 推送给 App。

### P0（高收益低风险，先做）
1. **pidfd 化崩溃检测**：新增 `open_pidfd(sysui/plugin)`，加入主 poll；退出即触发“疑似崩溃”采样（仍走 dropbox/pid 归因判真伪），替代 30s tick 空转。老内核回退扫 `/proc`。
2. **卡死检测**：arm 时按需读 `logcat -b crash`（不常驻），加 `is_fatal_line` + 日志疯长/`StackOverflow` 行占比判据（照 HyperMount 阈值）。补“进程没死但黑屏卡死”漏判。
3. **`.self_restart` 标记**：`dsnap_restore`/换主题/软重启/任何主动重启 SystemUI 前写 marker；rescue 崩溃判定命中即跳过消费。（与 `reset_pid_baselines` 并用，双保险）
4. **C 自守护 `--guard`**：替掉 shell `while; sleep 60`；`flock` 单实例 + `waitpid` 收僵尸 + 从 state 续 `since`。

### P1（中收益）
5. **减少 fork**：合并 shield 的多条 settings 写为一次；读回校验走一次；可静态链 **libarchive** 做 tar、**libselinux** 做 setfilecon 递归 → dsnap 主题/壁纸备份恢复**彻底无子进程**（契合“不要子进程”）。
6. **控制面合并**：加 `camd ctl status`（一次读 config/state/snap/diag 吐 JSON）；App 走**持久 root shell**(一次授权常驻) 跑 pidof/查询，替代每条 `su -c`。
7. **shield 补全**：device_config + settings global 双写，补 `min_crash_interval`/`crash_count_reset_interval`；增强模式强制开。

### P2（锦上添花）
8. **音量键救砖**（getevent 识别+首次实测校验）。
9. **watch/act 分层**：常驻只 pidfd 看护，出事故才起重活，进一步降稳态。
10. **开机后再等 ~15s 才 arm B 段崩溃窗口**（避免开机正常重启被计）。

## 5. 各步验证方法
- pidfd：`kill -9` SystemUI，崩溃判据触发时延从 ≤30s→毫秒；`strace -c`/`dumpsys cpuinfo` 对比稳态系统调用/CPU。
- 卡死：灌 logcat crash 洪泛/伪 StackOverflow，确认判“卡死”。
- self_restart：`dsnap_restore` 后确认 `state/crash_hist` 不增、不触发（auto_fix=1 下）。
- C guard：`kill -9` camd 主进程，guard 秒级拉起且统计续跑、无僵尸（`ps -o stat`）。
- 无子进程：改 libarchive/libselinux 后 `ps` 确认备份/恢复期间无 sh/tar 子进程。
- 消耗：改前后 `dumpsys cpuinfo` camd 占比、`cat /proc/<pid>/stat`(utime/stime)、电池统计对比。

## 6. 取舍/风险
- pidfd：Android11+(本机17)OK；老内核回退扫。风险低。
- 卡死 logcat：**务必按需短窗口**，常驻 logcat 很耗电；与 shield 协同。
- libarchive/libselinux：增二进制体积、SELinux 依赖；**可选**，先做 P0/P1(5 合并 settings)。
- C guard：双实例用 flock 防；僵尸必须 waitpid 收。
- 保持工程纪律：**已实测可工作的只做最小增量**，分阶段落地，每步真机回归（dsnap 备份/恢复、急救 A/B 段、shield）。
