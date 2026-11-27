# SystemUI 自查/自救方案 v2 设计（基于 APK 逆向与真机实证）

> 证据来源：MiuiSystemUI.apk（com.android.systemui 17.03.260226.r / vc 202602260 / targetSdk 37 / 43618 类）反编译 + 本机 `am crash` 实测日志与 DropBox 数据。
> 目标文件：`src/com/hyper/Camellia/SysUiRescue.java`（SystemUI 进程内）、`SysUiWatch.java`（system_server 侧观测）。

---

## 一、逆向实证结论

### E1 启动时序（smali 确认）

```
SystemUIService.onCreate()          // Dagger Component 完成注入后才被调用
 ├─ ① startSystemUserServicesIfNeeded()        ← 崩溃高发区，最先执行
 │    · 已启动        → return
 │    · 模式非法      → Log.e("Invalid mode for startSystemUserServicesIfNeeded") + return
 │                      ★ 静默降级：不抛异常，进程活着但半残
 │    · CAS 成功      → mServicesStarted.set(true)
 │                      → startServicesIfNeeded(mComponents, mBootTheme, TAG)
 │                         · 失败 → mServicesStarted.set(false) + mContext = null
 │                                  + throw RuntimeException("Failed to start system ui services")
 ├─ ② Thread.setUncaughtExceptionPreHandler(LogBufferEulogizer::record)
 │      注册时机**很晚**（在 ① 之后），① 内崩溃抓不到
 └─ ③ 读取资源 overlay（解析冲突会抛 Resources$NotFoundException）
```

### E2 LogBufferEulogizer：设计意图好，本机实际不落盘

- `path = new File(context.getFilesDir(), "log_buffers.txt")`
- `record()`：emergency dump 全部 log buffer + 全部 `Dumpable`（比 dropbox 信息量大得多）
- 内含 `minWriteGap = 300000ms` 节流（`getMillisSinceLastWrite() < minWriteGap` 直接 bail）

**实测（本机）**：
```
16:38:56.487 30258 I BufferEulogizer: Performing emergency dump of log buffers
16:38:56.487 30258 W BufferEulogizer: Cannot dump logs, last write was only 31787 ms ago
```
- `record()` **确实被调用**（说明 pre-handler 链路正常）
- 但 `find /data` + `search_files /data` 全盘搜索 **0 个 `log_buffers*` 文件**
- 结论：**该文件在本机从未成功落盘**，且被 300s 节流拦下
- ⚠️ 节流日志中的 31787ms 来自「上次写入时间」读取成功，与「文件不存在」互相矛盾 —— 说明本机 SystemUI 对该路径的处理与原生 AOSP 有差异（疑似 MIUI 定制或 CE/DE 存储切换），**不可作为判据依赖**

### E3 DropBox 崩溃条目自带黄金字段（实测样例）

```
SystemUptimeMs: 3118297            ← 崩溃时的开机时长 → 可反推 system_server 是否也重启过
Process: com.android.systemui
PID: 29709
UID: 10229
Flags: 0x30c8be0d
Package: com.android.systemui v102602260 (17.03.260226.r)
Process-Runtime: 2670596           ← ★ 进程已存活毫秒数（本次 44.5 分钟）
Timestamp: 2026-10-23 16:38:25.041+0800
DefaultScreen-State: ON            ← 崩溃时屏幕亮着（用户在场）
```

**`Process-Runtime` 是现成的「崩溃时机」判据**，秒级精度，无需我们自己算存活时长 —— 这是分级的核心数据来源。

### E4 SystemUI 自身毫无崩溃恢复能力

- 组件全部 `exported=false`，唯一入口是 `AMS.startSystemUi()`（`PERSISTENT` 进程）
- 无 watchdog、无自重启、**无安全模式**（完整 AOSP 有，MIUI 阉割了安全模式服务）
- 崩溃后只能靠 AMS persistent 重启 → 再崩 → 无限循环
- `SystemUIAuxiliaryDumpService` 与主 Service **同进程** → **不能当旁观者**（自救逻辑若放这里会一起死）

### E5 UncaughtExceptionPreHandler 只做 log eulogize，不阻止崩溃

任何人调用 `Thread.setUncaughtExceptionPreHandler` 都会**直接覆盖** SystemUI 的（该字段是全局静态的，且链式 setter 对返回类型有歧义）→ 必须 hook `UncaughtExceptionPreHandlerManager.registerHandler` 并在其注册完成后**重新装回最外层并向下转发**，否则会弄坏 SystemUI 自带的 eulogizer。

### E6 `isDumpCritical()` 默认 `true`

内部调用 `Debug.isDebugerLocked()` / `Watchdog.getInstance().isCrash()`，对 SystemUI 自身崩溃永远返回默认值 → **无分级信息，不能用于分级**。

---

## 二、现有实现的审计结果（SysUiRescue.java）

**已经做对的部分**（保留）：
- kill switch：`persist.camellia.sysui.hookoff=1` → 全锚点跳过并在 logcat 留痕（比改 LSPosed DB 安全、可即时生效）
- 4 个锚点与真实代码完全吻合：`attachBaseContext` / `SystemUIService.onCreate` / `startServicesIfNeeded` / `UncaughtExceptionPreHandlerManager.registerHandler`
- BOOT / OK / NOANCHOR 信号 + `OK_DELAY_MS=3000`（覆盖「启动完就崩」）
- 向下转发 downstream handler，不破坏 LogBufferEulogizer

**缺陷清单**：

| ID | 严重度 | 问题 | 根因 |
|---|---|---|---|
| D1 | **严重** | `scheduleOkReport()` 在 `startServicesIfNeeded` 正常返回后**无条件**调度 | E1 的「模式非法」分支是静默降级，proceed 正常返回但 `mServicesStarted=false`，于是发出**假 OK** → 守护误判启动成功，实际半残 |
| D2 | **严重** | `KEY_BOOT` 键名冲突 | `SysUiRescue` 用 `camellia_sysui_boot` 存「启动序号」，`SysUiWatch` 同键存「开机时刻」，互相覆盖 → UI 的 `bootMark` 判据失真 |
| D3 | 中 | 崩溃时机未入判据 | 未使用 E3 的 `Process-Runtime`，「启动期崩溃」与「稳态崩溃」被同等累计，无法实现差异化处置 |
| D4 | 中 | 无归因能力 | 本机有 21 个模块 hook SystemUI，崩溃后无法判断是否本模块所致 → 误摘 scope 风险高 |
| D5 | 低 | `sysui_watch=hook=on,db=on` 单值串 | 维度增多后不可扩展 |

---

## 三、方案设计 v2

### 3.1 判据（全部在 system_server 侧，不受 SystemUI 死活影响）

| 编号 | 判据 | 状态 | 作用 |
|---|---|---|---|
| P1 | hook `AppErrors.crashApplication*`（多签名） | 已有·实测命中 | 崩溃发生的第一时间 |
| P2 | 扫 `/data/system/dropbox` 的 `system_app_crash` / `system_app_anr`（支持 `.txt`/`.txt.gz`/`.dat.gz`，按条目自身时间戳去重） | 已有·实测命中 `system_app_crash@1792744705042` | **结果导向**：真崩了文件必然存在，P1 失效它仍有效 |
| P3 | 扫 `/proc/*/cmdline` 找 systemui pid | 已有·实测命中 `29709→30258` | 覆盖 LMK 杀 / force-stop / native 死亡 |
| **P4** | **OK 缺失**（BOOT 有、OK 无） | 新增 | 进程没活到稳态 |
| **P5** | **DEGRADED 信号** | 新增 | 启动走了静默降级分支（修 D1） |
| **P6** | **归因**：读 LSPosed modules log 末尾 N 行，确认本模块 SystemUI scope 在崩溃窗口内是否启用 | 新增 | 修 D4 |

P2 新增解析（来自 E3）：`Process-Runtime`、`SystemUptimeMs`、`DefaultScreen-State`。

### 3.2 分级模型（三维：时机 × 频次 × 归因）

**时机维度**（用 `Process-Runtime`）
- `EARLY`：`process_runtime < 60000ms`，或字段缺失（死在 attach 前后）
- `STEADY`：`process_runtime ≥ 60000ms`

**频次**：本开机窗口内累计次数 `n`（按 `SystemUptimeMs` 归窗）

**归因**：`ATTRIBUTED`（modules log 显示本模块该 scope 活跃）/ `UNKNOWN`

| 级 | 条件 | 动作 |
|---|---|---|
| **S0** | OK 在、无异常 | 无动作 |
| **S1** | EARLY × n=1~2 × UNKNOWN | 只记录 + 上报，不动手 |
| **S2** | EARLY × n≥3 | 摘 SystemUI scope（`hookoff=1`）+ 通知 + 记录 |
| **S3** | STEADY × n≥5（60s 内） | **只重启，不摘** —— 稳态崩溃说明大概率是模块以外的问题 |
| **S4** | 任意 × `ATTRIBUTED` | 立即摘 scope（归因可信时优先） |
| **SAFE 熔断** | 开机 300s 内 ≥3 次重启 / hookoff 已置 | 只发用户可见通知，**禁止任何自动动作** |
| **长稳** | 连续 30min OK | 清空计数、回落到 S0 |

### 3.3 信号通道

| 通道 | 用途 | 特性 |
|---|---|---|
| **logcat** | SystemUI → 守护的实时信号（BOOT/OK/DEGRADED/NOANCHOR/CRASH） | 立即送达，不丢 |
| **崩溃堆栈** | hook 的 pre-handler 里直接 `Log.getStackTraceString(t)` 截断 800 字写入信号 | **不依赖 log_buffers**（E2 证明不落盘）★ |
| `Settings.Global` | 事后取证镜像（`camellia_sysui_*`） | 约 2s 批量落盘窗口，崩溃时可能丢失 → **只作镜像，不作判据** |
| `persist.camellia.sysui.hookoff` | 摘 scope 的开关 | root 可 `setprop`，重启 SystemUI 即生效，比改 LSPosed DB 安全 |

### 3.4 取证

root 守护维护 `/data/system/camellia_rescue/`：
- `crash_history.log`（已有，`ts|来源|进程|堆栈首行`）
- `crash_events.log`（新增，结构化）：`ts|src|pid|proc_runtime_ms|uptime_ms|screen_on|attr|stack_first`
- `dropbox/`（新增）：dropbox 条目原文副本，避免 dropbox 滚动丢失
- 崩溃堆栈首段（新增）：从 hook 信号里取

### 3.5 代码改动清单

| 文件 | 位置 | 改动 |
|---|---|---|
| `SysUiRescue.java` | `hookStartServicesIfNeeded` 的 intercept | proceed 后读 `mServicesStarted`（`AtomicBoolean`）字段；`false` → `signal("DEGRADED", ...)` 并且**不** `scheduleOkReport()`（修 D1） |
| `SysUiRescue.java` | 常量 | `KEY_BOOT` → `KEY_ATTEMPT_SEQ`（值语义：启动序号）；新增 `KEY_BOOT_AT`（开机时刻，归 SysUiWatch 所有）（修 D2） |
| `SysUiRescue.java` | `mirror(KEY_BOOT, ...)` 调用点（约 :223） | 同步改名 |
| `SysUiRescue.java` | pre-handler 拦截 | 抓 `Log.getStackTraceString(t)` 截 800 字发信号，不再只依赖 downstream eulogizer |
| `SysUiWatch.java` | dropbox 解析 | 新增 `Process-Runtime` / `SystemUptimeMs` / `DefaultScreen-State`（修 D3） |
| `SysUiWatch.java` | 分级 | 落地 S0~S4 + SAFE 熔断 + 30min 长稳回落 |
| `SysUiWatch.java` | 归因 | 读 LSPosed modules log 尾行，判断本模块 SystemUI scope（修 D4） |
| `SysUiWatch.java` | `sysui_watch` 值 | 改结构化 `state=...,p1=...,p2=...,p3=...,deg=...`（修 D5） |
| `SysUiWatch.java` | 记事件 | 写 `crash_events.log` + dropbox 原文副本 |
| `SettingsActivity.kt` | `SysUiRescuePage` | 按 S 级着色/给建议；新增「崩溃时机」列（`Process-Runtime`）；`bootMark` 改读 `KEY_BOOT_AT`（同步 D2） |

---

## 四、明确不做 / 已知风险

1. **不依赖 `log_buffers.txt`** —— E2 实证本机不落盘 + 300s 节流。
2. **不依赖 `isDumpCritical()`** —— E6 恒为默认 `true`。
3. **救援动作不放 SystemUI 进程** —— E4：同进程自杀不可恢复；且本机 SystemUI 为 `platform_app`（有 `WRITE_SECURE_SETTINGS`，无 `INSTALL_PACKAGES` / `READ_LOGS`）。
4. **摘 scope 只走 `persist.camellia.sysui.hookoff=1`** —— 已有通道，`setprop` 即时生效，不碰 LSPosed DB。
5. **缓爆型无法自动区分**（启动 30s 后才崩且必崩）→ 靠归因维度；默认落到 S3 只重启。
6. **P3 假阳性**：force-stop / LMK 也会被计为「重启」→ P3 **不单独触发任何动作**，必须与 P1/P2 或 OK 缺失交叉。
7. **冷启动前必读**：hook 必须挂在 `startSystemUserServicesIfNeeded` **之前**（E1 的 ①），现有 `attachBaseContext` 锚点满足。

---

## 五、本次实测记录

| 项 | 数据 |
|---|---|
| 第一次 `am crash com.android.systemui` | pid `29709` → `30258`；dropbox `system_app_crash@1792744705042.txt`，`Process-Runtime: 2670596ms`(≈44.5min)，`DefaultScreen-State: ON` |
| 第二次 `am crash` | pid `30258` → `31363` |
| logcat | `Performing emergency dump of log buffers` ×5 + `Cannot dump logs, last write was only 31787 ms ago` ×5 |
| `log_buffers.txt` | `find /data` 与 `search_files /data` 均为 **0 结果** → 从未落盘 |
| systemui uid | `10229`（`u0_a229`），`dataDir=/data/user_de/0/com.android.systemui` |
| 结论 | **dropbox 判据实证有效**；**log_buffers 判据实证无效，弃用** |

---

## 六、系统自带「旁观者」机制（services.jar 实证）

> 本节数据来源：`/system/framework/services.jar`（39.8 MB，classes.dex~4）dex 字符串提取 + 本机 `dumpsys` / `logcat` / 文件系统实测。
> 标注「AOSP 另有」的字段未在本机 dump 中亲眼确认，不得直接依赖。

### E7 机制总览

| # | 机制 | 实现类（均在 services.jar） | 覆盖范围 | 数据质量 | 持久化 | 读取方式 | 代价 |
|---|---|---|---|---|---|---|---|
| M1 | **ApplicationExitInfo** | `AppExitInfoTracker` (classes.dex) | Java crash / native crash / ANR / SIGKILL / LMK / force-stop / user-stop / 初始化失败 / 资源滥用 | ★★★★★ 结构化 | ✅ `/data/system/procexitstore/procexitinfo`（实测 363392 B，16:51 更新） | `dumpsys activity exit-info [pkg]`；`ActivityManager.getHistoricalProcessExitReasons()` | 零（无需 root） |
| M2 | **crash log buffer** | `android.util.Log`(LOG_ID_CRASH) + libart `AndroidRuntime` | Java 未捕获异常 | ★★★★★ 全帧堆栈（含 ZygoteInit 链路） | ⚠️ 2 MiB 环形，实测仅 3 KiB 可读 | `logcat -b crash -d` / 持续 tail | 零 |
| M3 | **ANR traces** | `AnrHelper` / `AppErrors` | ANR / watchdog | ★★★★★ 全线程 dump | ✅ `/data/anr/anr_*.txt` + `/data/system/procexitstore/anr_*.gz`（实测 5 份） | 读文件 | 零 |
| M4 | **tombstones** | debuggerd / tombstoned (native) | native crash | ★★★★★ 寄存器+栈 | ✅ `/data/tombstones/`（实测 tombstone_24 444 KB） | 读文件 | 零 |
| M5 | **DropBox** | `DropBoxManagerService` | 全类型摘要 | ★★★ 文本 | ✅ `/data/system/dropbox/` | 读文件 | 零 |
| M6 | **ProcessErrorStateRecord** | AMS 内部 | 实时 `crashing`/`notResponding` 标志 | ★★★ | ❌ 内存态 | `dumpsys activity processes` | 零 |
| M7 | IncidentManager / BugreportManagerService | ✅ 存在 | 全量诊断报告 | ★★★★★ | ✅ | 需 DUMP 且耗时数十秒 | 高，不适合自动 |
| M8 | LogBufferEulogizer | SystemUI 内（非 jar） | Java 崩溃 | ★★★★ 全 buffer | ❌ 本机不落盘（见 E11） | — | 弃用 |

`procexitstore/` 目录同时承载 exit-info 与 ANR 的 gz 备份，是 M1+M3 的落盘根。

### E8 实测样例（本机 com.android.systemui，uid 10229）

```
Last Timestamp of Persistence Into Persistent Storage: 2026-10-23 16:51:18.693
package: com.android.systemui
  Historical Process Exit for uid=10229
    #0 timestamp=16:38:56.648 pid=30258 reason=4 (APP CRASH(EXCEPTION)) pss=544MB rss=795MB
    #1 timestamp=16:38:25.044 pid=29709 reason=4 (APP CRASH(EXCEPTION)) pss=544MB rss=795MB
    #2 timestamp=15:53:54.369 pid=28747 reason=4 (APP CRASH(EXCEPTION))
    #3 timestamp=15:53:22.173 pid=24464 reason=2 (SIGNALED) status=9
    #4 timestamp=15:48:44.482 pid=7298  reason=2 (SIGNALED) status=9
    #5 timestamp=13:35:42.407 pid=3971  reason=4 (APP CRASH(EXCEPTION))
```

三个关键结论：

1. **"Last Timestamp of Persistence Into Persistent Storage"** 明示存在跨重启持久化 → 即使 SystemUI 崩溃 + 设备重启，历史仍在。
2. **`pss=544MB / rss=795MB`** —— SystemUI 反复崩溃时内存占用极高，高度疑似内存压力（对应 `REASON_LOW_MEMORY` / `SUBREASON_OOM_KILL` 语义），即**可能不是模块 bug**。这直接支撑 S3「稳态崩溃只重启不摘 scope」。
3. **`am crash` 产生的是 reason=4**，而 `kill -9` 产生 reason=2 + status=9 → **两种死法可区分**。

### E9 本机支持的原因枚举（dex 提取，17 + 44 全在）

**REASON（17 个，编号与 AOSP 一致）**

`UNKNOWN`0 `EXIT_SELF`1 `SIGNALED`2 `LOW_MEMORY`3 `CRASH`4 `CRASH_NATIVE`5 `ANR`6 `INITIALIZATION_FAILURE`7 `PERMISSION_CHANGE`8 `EXCESSIVE_RESOURCE_USAGE`9 `USER_REQUESTED`10 `USER_STOPPED`11 `DEPENDENCY_DIED`12 `OTHER`13 `FREEZER`14 `PACKAGE_STATE_CHANGE`15 `PACKAGE_UPDATED`16

**SUBREASON（44 个，按用途分组）**

- **LMK/内存**：`OOM_KILL` `MEMORY_PRESSURE` `LARGE_CACHED` `TOO_MANY_CACHED` `TOO_MANY_EMPTY` `REMOVE_LRU` `TRIM_EMPTY` `IMPERCEPTIBLE`
- **资源滥用**：`EXCESSIVE_CPU` `EXCESSIVE_BINDER_OBJECTS` `EXCESSIVE_ENQUEUED_BROADCASTS_COUNT` `EXCESSIVE_OUTGOING_BROADCASTS_WHILE_CACHED`
- **ANR 归因**：`ANR_TYPE_INPUT_DISPATCHING_TIMEOUT` `ANR_TYPE_INPUT_DISPATCHING_TIMEOUT_NO_FOCUSED_WINDOW` `ANR_TYPE_BROADCAST_OF_INTENT` `ANR_TYPE_EXECUTING_SERVICE` `ANR_TYPE_CONTENT_PROVIDER_NOT_RESPONDING` `ANR_TYPE_START_FOREGROUND_SERVICE` `ANR_TYPE_JOB_SERVICE_START` `ANR_TYPE_BIND_APPLICATION` `ANR_TYPE_APP_TRIGGERED` `ANR_TYPE_FOREGROUND_SHORT_SERVICE_TIMEOUT`
- **主动/被动终止**：`KILL_PID` `KILL_UID` `KILL_BACKGROUND` `KILL_ALL_FG` `KILL_ALL_BG_EXCEPT` `FORCE_STOP` `STOP_APP` `WAIT_FOR_DEBUGGER` `PACKAGE_UPDATE` `CACHED_IDLE_FORCED_APP_STANDBY` `SDK_SANDBOX_DIED`
- **启动异常**：`INVALID_START` `INVALID_STATE`

⚠️ **本机实测 `subreason=0 (UNKNOWN)`** —— MIUI 未回填 subreason，因此**只能用 reason 判定**；字段结构在，OTA 后可能启用。

### E10 判据修订（取代原 P2）

| 编号 | 判据 | 变化 |
|---|---|---|
| **P2a** | 解析 `dumpsys activity exit-info com.android.systemui`：`reason` / `timestamp` / `status` / `subreason` / `pss` / `rss` | **取代 dropbox 成为主判据**：结构化、跨重启、覆盖 LMK/force-stop/ANR/资源滥用 |
| P2b | dropbox `system_app_crash*` | 降为备份，仍提供 `Process-Runtime` / `DefaultScreen-State` / 堆栈首段 |
| **P7** | **SystemUI 启动时自读** `getHistoricalProcessExitReasons(null, 0, 1)` → 发 `PREVEXIT\|reason\|ts\|desc` 信号 | 查自己同包免检（待代码实测）；崩了就死，但**下次启动能读到「上一次」** → 天然覆盖启动期崩溃 |
| **P8** | 守护持续 tail `logcat -b crash` | 2 MiB 环形仅剩 3 KiB 可读 → **必须实时抓，禁止崩溃后 dump**；可得完整 FATAL EXCEPTION 全帧（远强于 hook 里 800 字截断） |
| **P9** | `/data/system/procexitstore/anr_*.gz` + `/data/anr/` | ANR 取全线程 dump |

**分级模型增补**

- 时机维度改用 exit-info `timestamp` 与「本次开机时刻」之差（比 `Process-Runtime` 直接），`Process-Runtime` 作交叉校验
- `REASON_INITIALIZATION_FAILURE` → **直接 S2**（初始化失败=结构性不可用，重启无用，应摘 scope）
- `REASON_LOW_MEMORY` / `SUBREASON_OOM_KILL|MEMORY_PRESSURE` → **不摘 scope**，走内存缓解（非模块问题）
- `REASON_EXCESSIVE_RESOURCE_USAGE` → 记事件，倾向 S3 只重启 + 通知用户资源异常
- `REASON_ANR` + `SUBREASON_ANR_TYPE_INPUT_DISPATCHING_TIMEOUT_NO_FOCUSED_WINDOW` → 命中「无焦点窗口」半残形态

### R8 新增风险：守护自杀循环

守护救援时会 `kill -9 systemui` → AMS 记 `REASON_SIGNALED` + `status=9`（本机 subreason=UNKNOWN，拿不到 `KILL_PID`）→ 若守护用 exit-info 作判据，会把**自己刚杀的那次**当成新崩溃 → 死循环。

**缓解**：守护每次主动 kill 前先写 `self_action.intent`（`pid|ts|action`）；读 exit-info 时若条目 `timestamp ≤ self_action.ts + 5s` 且 `pid` 匹配 → **丢弃该条**。
⚠️ 因本机 subreason 不可用，**必须用自记录方式排除自身动作，不能依赖 `SUBREASON_KILL_PID`**。

### E11 修正 E2 的机制解释

`log_buffers.txt` 不落盘的真正原因：`LogBufferEulogizer` 用 `Files.newBufferedWriter(path, CREATE, TRUNCATE_EXISTING)`，**`CREATE` 不创建父目录**，而 `/data/user_de/0/com.android.systemui/files/` 在本机**不存在** → `NoSuchFileException` 被 catch 吞掉（仅打 "bailing"）。与 300s 节流不矛盾：节流读的是内存变量 `mLastWriteElapsed`，不是文件 mtime。
→ 若要用它：root 预建该目录（`mkdir -p` + `chown 10229:10229` + `chmod 771`），待实测。

---

## 七、MIUI/HyperOS 定制「异常分析机制」（本轮实测）

> 结论先行：**MIUI 确实有一套远超 AOSP 的定制稳定性体系，且其中 Scout 机制是「渐进式、带阻塞消息、带全线程栈」的高价值数据源**。它比我们 hook 能拿到的信息更早、更结构化。但它**不能替代** P1 hook + BOOT/OK 信号（见 7.7）。

### M1 稳定性数据总根：`/data/miuilog/stability/`

由 `/system_ext/etc/init/init.miui.ext.rc:201-208` 在 `post-fs-data` 阶段创建：

```
/data/miuilog/stability/            0775 system system
├── scout/                          0774 system system   ← 核心（other 不可读）
│   └── app/                        0774，实测 493 个子目录
├── jetrace/                        0777                ← SystemUI 可直读
├── hanglog/                        0777                ← 含 pre_shutdown_log_*.zip
├── memleak/{gpu,heapdump,procs,sentinel}
├── resleak/  fdtrack/  hprof/      0775 / 0777（本机为空）
├── nativecrash/tombstones/         实测 15 个 tombstone
├── reboot/                         3 个 `-3_killer_*.zip`
├── unhandledevent/                 0700（需 root，本机为空）
└── miuibpfframework/               0700
```

另有 `init.stability_setup` 服务（`/system_ext/bin/init.stabilitysetup.sh`，oneshot，`on post-fs-data` 启动，`ro.boottime.stability_setup=32274755143` ≈ 开机 32s）负责按内存档位校准开关：

- 本机 `MemTotal = 15445004 kB ≈ 14.7 GB` → **`litescout` 未启用**（lite 模式仅 ≤6GB 且非 MTBF/UAT），即 **Scout 全功能：抓 backtrace + 允许主动动作**
- 脚本还会设 `persist.sys.stability.scout.enable=true`、`scout.check_frozen=true`

### M2 崩溃恢复模块（`crashrecovery.rescue_boot_count` 的来源）

本机 `getprop` 有 `[crashrecovery.rescue_boot_count]: [1]`，源码在 services.jar：

```
classes.dex    Lcom/android/server/crashrecovery/CrashRecoveryHelper;
classes.dex    com.android.server.crashrecovery.CrashRecoveryModule$Lifecycle
classes.dex    StartCrashRecoveryModule / StartCrashRecoveryModuleXM   ← XM = Xiaomi 定制
classes2.dex   CrashRecoveryHelper.java / CrashRecoveryStatsLog.java
```

即 **MIUI 在 system_server 内自带「连续崩溃启动救援」模块**（决定是否降级/清理/进安全模式），并用 prop 记录连续救援次数。

→ 对我们的意义：**与 SysUiRescue 的 `restart_cnt` 概念重叠**。可读取它作**独立第三方计数**交叉校验我们的自救次数（我们是模块自记，它是系统记）。

### M3 ★ Scout：本轮最大发现

**开关与实现**：`persist.sys.stability.scout.enable=true`、`scout.check_frozen=true`；类 `com.android.server.stability.MiuiStabilityDisplayScoutStub` + `MiDisplayScoutParam`（services.jar）。

**四级渐进模型（实测全部命中）**：

| 级别 | 实测触发条件 | 实例 |
|---|---|---|
| `APP_SCOUT_WARNING` | 主线程单条消息阻塞 **≈2500ms** | 16:38:24 SystemUI `ActivityThread$H w=134 duration=2502ms` |
| `APP_SCOUT_HANG` | 阻塞 **≈5069ms**（对应 `Timeout: 5000`） | 15:47:42 SystemUI `flux.decor.hook.f5 duration=5069ms late=543ms` |
| `APP_SCOUT_HANG_INPUT` | 输入派发超时（`Current Blocking Msg: null`） | 12:00:58 SystemUI |
| `APP_ANR` | AMS ANR 正式成立 | 12:00:53 SystemUI `NotificationShade ... Waited 5000ms` |

**落盘格式**：`scout/app/{ts}-{pkg}-{pid}-{TYPE}/` 目录内两个文件

```
{pkg}-{pid}-{TYPE}-{ts}-info        262 B   结构化元数据
{pkg}-{pid}-{TYPE}-{ts}-self-trace  160 KB  全线程栈
```

`-info` 实测样例（SystemUI HANG）：

```
AppBug
BoardSensorTemp: 39.885
Exception: APP_SCOUT_HANG
Timestamp: 2026-10-23-15-47-42
packageName: com.android.systemui
processName: com.android.systemui
Pid: 7298
Uid: 10229
activityName: empty
Current Blocking Msg: (Current message: duration=5069ms seq=7376 late=543ms h=android.os.Handler c=flux.decor.hook.f5)
Binder transaction info: empty
```

**为什么这是宝藏**：`Current Blocking Msg` 直接给出**阻塞在哪个 Handler、哪条 callback、阻塞多久**。这是 hook `uncaughtException` 永远拿不到的信息（崩溃前状态），也是 exit-info 拿不到的（只有事后结论）。

**AppBug 判据配套**：`scout/app/` 下还有 `anr_*_{pkg}-{pid}-lastAnrState.txt`（AMS `dumpsys activity lastanr` + `dumpsys window lastanr` 全文）与 `anr_io_latency.txt`。

### M4 MIUI 定制的未捕获异常处理链

`jetrace/` 中 SystemUI 崩溃现场（`am crash` 实证）的 main 线程栈：

```
"main" state=TIMED_WAITING
  at java.util.concurrent.FutureTask.awaitDone
  at miui.mqsas.sdk.MQSEventManagerDelegate.runtimeWithTimeout(MQSEventManagerDelegate.java:806)
  at com.android.internal.os.RuntimeInitImpl.onJE(RuntimeInitImpl.java:133)      ← MIUI 定制
  at com.android.internal.os.RuntimeInit$KillApplicationHandler.ensureLogging(RuntimeInit.java:232)
  at com.android.internal.os.RuntimeInit$KillApplicationHandler.uncaughtException(RuntimeInit.java:171)
  at com.hyper.Camellia.SysUiRescue$RescueHandler.uncaughtException(SysUiRescue.java:408)  ← 我们的 hook
  at com.xiaomi.onetrack.api.aq.uncaughtException(...)                            ← OneTrack 埋点
  at java.lang.ThreadGroup.uncaughtException
```

三条结论：

1. **MIUI 有 `RuntimeInitImpl.onJE`（on Java Exception）** —— 比 `AppErrors.crashApplication` 更上游的定制入口，每次未捕获异常都走 `miui.mqsas.sdk.MQSEventManagerDelegate.runtimeWithTimeout`（MQS 质量事件上报，带 `FutureTask.awaitDone` 阻塞式超时）→ **MQS 上报慢会拖长崩溃流程**，这也是 main 线程出现 `TIMED_WAITING` 的原因。
2. **调用顺序是 onetrack → 我们 → KillApplicationHandler**：我们的 handler 处在链中间，`sDownstream` 转发逻辑正确（后续 handler 仍会执行）。
3. `jetrace/*.trace`（0777）头部含 `Subject:` / `ExceptionMessage:` / `pid` / `Cmd line`，是**明码可解析的崩溃摘要**。

### M5 jetrace 归属澄清（避免误判为 MIUI 独有）

`jetrace/{ts}_{pkg}_{pid}.trace` 的格式 `Subject: Dump JavaProcess Traces` + PSI 三件套 + `RssHwmKb` 是 **AOSP 14+ `ActivityManagerService.dumpJavaProcessTraces()` 的原生输出**（崩溃/ANR 前 dump），MIUI 只是把落点从 `/data/anr/` 改到了 `/data/miuilog/stability/jetrace/`（权限同时放宽到 0777）。

→ 归档 trace 实测含：
```
Subject: Input dispatching timed out (cd18ec1 NotificationShade is not responding. Waited 5000ms for MotionEvent(action=OUTSIDE)).
/proc/pressure/cpu: some avg10=43.78 avg60=19.59   ← CPU 压力高
RssHwmKb: 812976  RssKb: 677588                    ← SystemUI 峰值 794MB
Timeout: 5000
```

### M6 事故现场自动打包：`-3_killer_*.zip`

`reboot/` 下的 zip（本机 3 个），内容实测：

```
anr/temp_anr_2026-10-23_12-00-53-471_*.txt          0 B（空壳）
anr/anr_2026-10-23_12-00-53-508                     2.4 MB  ← 完整 ANR 全线程 dump
hyperBugreport-tmp.zip                              16 KB
-3_killer_{md5}_unknown_stable_popsicle_OS4.0.0.23.XPBCNXM_{ts1}_{ts2}   74 MB
```

文件名编码：`{reason_code}_killer_{md5}_{unknown}_stable_{board}_popsicle_{OS版本}_{ts1}_{ts2}`。

→ **MIUI 在 ANR/异常重启时会自动把完整现场打包归档**，时间戳 `12:00:53` 与 SystemUI `NotificationShade` ANR 完全对应。这是比我们自己抓更完整的取证物。

### M7 资源泄漏监控族（部分与 SystemUI 直接相关）

| prop（本机实测） | 机制 | 与 SystemUI 的关系 |
|---|---|---|
| `enable_rss_monitor` / `pss_highest_line=6291456` | RSS/PSS 超 6 GB 报警 | 低（SystemUI pss≈544MB） |
| `enable_fd_monitor` / `enable_fd_leak_abort` | FD 泄漏 → **abort** | **中**：SystemUI FD 泄漏会直接被 abort（表现为 `REASON_CRASH_NATIVE` 而非 Java 崩溃） |
| `enable_thread_monitor` / `thread_leak_abort` | 线程泄漏 → abort | 中 |
| `enable_dmabuf_monitor` / `dmabuf_highest_line=3145728` | dmabuf 超 3 GB | 中（图形相关，SystemUI 重灾区） |
| `enable_res_leak_abort` / `enable_sentinel_resource_monitor` | 资源泄漏 abort | 中 |
| **`window_monitor.enabled=true` / `kill_threshold=500` / `kill_when_leak=true`** | **窗口数 >500 且判定泄漏 → 直接 kill** | **高**：SystemUI 窗口泄漏是经典问题，会被 MIUI 直接杀 |
| `enable_process_exit_monitor=true` | 进程退出监控 | **高**：与我们 P2a 同源但更早 |
| `monitor_third_party_app=true` / `get_tpa_hprof=false` | 监控第三方应用 | **高：Camellia 自己就是第三方应用** |
| `nativehangII.enable=true` / `nativehang.enable=false` | native hang 检测/恢复 | 中 |
| `fault_report=true` / `micrash` | 故障上报 | 低（不可读） |

### M8 可用性评估（决定我们能用哪些）

| 数据源 | 权限 | SystemUI 进程内可读？ | root 守护可读？ | 价值 | 建议 |
|---|---|---|---|---|---|
| `jetrace/*.trace` | 0777 | ✅ **可以** | ✅ | 崩溃摘要 + PSI + RSS + 全线程 | **采纳为 P11** |
| `hanglog/pre_shutdown_log_*.zip` | 0777 | ✅ | ✅ | 关机前现场 | 低优先（关机场景） |
| `scout/app/*-info` | 0774 system:system | ❌（uid 10229 无 other 权限） | ✅ | **最高**（阻塞消息 + 分级 + 温度） | **采纳为 P10，root 守护读** |
| `scout/app/*-self-trace` | 0774 | ❌ | ✅ | 全线程栈 | P10 附件 |
| `scout/app/anr_*_lastAnrState.txt` | 0774 | ❌ | ✅ | AMS/WMS lastanr 全文 | P10 附件 |
| `reboot/-3_killer_*.zip` | 0770 root | ❌ | ✅ | 完整现场归档 | 事后取证（低频） |
| `crashrecovery.rescue_boot_count` | prop | ✅ 可读 | ✅ | 系统侧自救计数 | **交叉校验 restart_cnt** |

> ⚠️ `scout/` 是 `drwxrwxr-- system system`，**SystemUI（u0_a229）读不到**。想让 SystemUI 进程内读，只能靠 root 守护读取后再经信号通道下发（与现有架构一致，无需改动分工）。

### M9 风险与副作用（必须记录）

- **R9 我们的模块本身就在被 Scout 记录**：实测 `scout/app/` 中 `com.hyper.Camellia` 有约 **80 条** `APP_SCOUT_HANG` / `APP_ANR`。最近一条（10-12 23:02）：
  ```
  ANR Reason: Input dispatching timed out (com.hyper.Camellia/.LauncherAlias is not responding. Waited XXms for MotionEvent(action=DOWN))
  summary: long running Message duration=10752ms h=android.view.Choreographer$FrameHandler
  pendingMessages: Delayed 1 { when=-10s753ms target=androidx.compose.ui.platform.AndroidUiDispatcher$dispatchCallback$1 }
  longSameTargetMessages: count=233 totalwalltime=1683ms
  clues=High Cpu : some avg10=38.38 avg60=57.37 avg300=32.22
  ```
  → **Camellia 自己的 Compose 设置页会阻塞主线程 10.7s 并触发系统 ANR**。这是独立于本方案的性能缺陷，但会**污染 Scout 数据、干扰 SystemUI 归因**（时间窗重叠时难以区分是 Camellia 还是 SystemUI）。应单列待修项。
- **R10 MIUI 会主动杀 SystemUI**：`window_monitor.kill_when_leak=true` + `kill_threshold=500`，以及 fd/thread/res/dmabuf 各类 `*_leak_abort`。这类死亡在 exit-info 记为 `REASON_CRASH_NATIVE` 或 `REASON_EXCESSIVE_RESOURCE_USAGE`，**不是我们的 hook 能捕获的**（Java 层 abort 不走 uncaughtException）→ 直接印证 7.7 的结论：**单靠 hook 会漏报**。
- **R11 MQS 上报可能拖长崩溃流程**（`MQSEventManagerDelegate.runtimeWithTimeout` 阻塞在 main 线程），意味着「崩溃 → 进程真正退出」之间有时间窗，守护判定需容忍该延迟。

### M10 判据增补（在原 P1–P9 之上）

| 编号 | 判据 | 执行者 | 说明 |
|---|---|---|---|
| **P10** | 守护轮询 `/data/miuilog/stability/scout/app/` 中**新增的 `*systemui*-APP_SCOUT_{WARNING,HANG,HANG_INPUT}` 与 `*systemui*-APP_ANR`** 目录，解析 `-info` 的 `Exception` / `Current Blocking Msg` / `BoardSensorTemp` | root 守护 | **最强判据**：早于崩溃 2.5s 即预警，给出阻塞 Handler 名 |
| **P11** | 守护 / SystemUI 自读 `jetrace/{ts}_com.android.systemui_{pid}.trace`，取 `Subject:` 与 `RssHwmKb` | 两者皆可（0777） | 崩溃摘要 + 内存峰值，免 root |
| **P12** | 读 `crashrecovery.rescue_boot_count`，与自记 `restart_cnt` 比对 | 守护 | 若系统侧计数增长而自记未增长 → 存在「我们没发现的崩溃」 |
| **P13** | Scout WARNING 用作 **D1 假 OK 的终结判据**：发 OK 前（`OK_DELAY_MS=3000`）检查窗口内是否出现 `APP_SCOUT_WARNING`，若有则**不发 OK** | SystemUI 进程内不可读 → 改为守护在收到 OK 后 5s 内反查 Scout | 阈值 2.5s < 3s < 5s，**3s 的 OK 时点恰好落在 WARNING 与 HANG 之间，是天然判读窗口** |

### M11 待验证项

1. `scout` 判定是否会因 `litescout` 降级而失效 —— 本机 14.7GB 不降级，**其他机型需注意**（≤6GB 会关 Sentinel、开 lite）。
2. `APP_SCOUT_WARNING` 的 2502ms 是否为固定阈值，还是随 `input_anr_timeout` 缩放。
3. `unhandledevent/`（0700，本机空）内容未知，需构造一次 native 崩溃再看。
4. `-3_killer` 的 `reason_code = -3` 具体映射（怀疑 3 = 异常重启，负号为 killer 类），需对照 `CrashRecoveryStatsLog` 枚举。
5. `miuibpfframework/`（0700）是否为 eBPF 采集，未展开。
