# SystemUI 崩溃侦测与分级自救 v3 设计

> 机型：Xiaomi 2509FPN0BC / Android 17 (API 37) / HyperOS 3
> SystemUI：`com.android.systemui`（uid=10229 / u0_a229，`/system_ext/priv-app/MiuiSystemUI`，versionCode=202602260）
> 依据：第六章 AOSP 旁观机制（E7–E11）、第七章 MIUI 定制机制（M1–M11）、v2 代码审计

---

## 0. 一句话

把现在的「**BOOT 有 / OK 无 → 无差别加码自救**」改成「**多源归因先定性 → 按原因对症分级 → 反复失效就熔断停手**」。

---

## 1. 现状审计（代码级）

### 1.1 四个组件

| 组件 | 位置 | 进程 / uid | 职责 |
|---|---|---|---|
| `SysUiRescue.java` | `src/com/hyper/Camellia/` | SystemUI / 10229 | 4 锚点 + BOOT/OK/CRASH/NOANCHOR + 三层崩溃钩子 |
| `SysUiWatch.java` | 同上 | system_server / 1000 | P1 hook `AppErrors` + P2 扫 dropbox + P3 pid 巡检 + 记账 |
| `sysui_rescue_guard.sh` | `tools/` | root | 读 logcat 信号 → `Tier1/2/3` 动作 |
| `SysUiRescuePage` | `SettingsActivity.kt:2925` | 设置页 | 展示 `camellia_sysui_*` 计数与堆栈 |

### 1.2 现有锚点（保持不动）

| # | 锚点 | 作用 |
|---|---|---|
| 1 | `SystemUIApplicationImpl#attachBaseContext(Context)` | 进程内最早可拿 Context，发 BOOT、装兜底 handler |
| 2 | `SystemUIService#onCreate()` | 标记「已到 Service 层」，定位崩在哪个阶段 |
| 3 | `SystemUIApplicationImpl#startServicesIfNeeded(Map,String,String)` | 唯一无盲区锚点：正常返回 + 活过 3s 才发 OK |
| 4 | `UncaughtExceptionPreHandlerManager#registerHandler` | 在 SystemUI 注册完自己的 pre-handler 后夺回外层，向下转发 |

`OK_DELAY_MS = 3000`；`persist.camellia.sysui.hookoff=1` 是 kill switch（install 最开头读，命中则完全不装锚点）。

### 1.3 九个结构性缺陷

| ID | 缺陷 | 具体表现 | 后果 |
|---|---|---|---|
| **D1** | **单判据、无归因** | guard 只知道「BOOT 有 OK 无」，不知道**为什么** | 内存压力型重启（实测 `pss=544MB/rss=795MB`）被当成模块 bug 直送 Tier2 摘 scope；v2 已证明这类**不该摘** |
| **D2** | **Tier 单调递增** | `do_rescue()` 里 `t=$((t+1))`，只在收到 OK 时 `reset_healthy` 清零 | 同根因反复失败会一路升到 Tier3「回滚 SystemUI」，可能把系统搞坏 |
| **D3** | **假 OK** | `startServicesIfNeeded` 返回即计时 3s 发 OK；3s 正好落在 scout `WARNING(2.5s)` 与 `HANG(5s)` 之间 | 依赖死锁 / 冻屏型故障「看起来健康」，**全程漏报** |
| **D4** | **动作过重** | Tier2 用 `pm clear com.android.systemui` | 清掉用户 SystemUI 数据（布局、开关），且不可回滚 |
| **D5** | **键名冲突** | `SysUiRescue.KEY_BOOT` 与 `SysUiWatch.KEY_BOOT` 同为 `"camellia_sysui_boot"`，分别存「启动 seq」与「上次开机时刻」 | 互相覆盖 → 跨开机清零与 seq 校验都不可靠 |
| **D6** | **NOANCHOR 后裸奔** | 4 锚点全失效时 `pending_seq` 永远为空，只剩 pid 抖动兜底 | 模块对当前版本完全失效，用户却看不到 |
| **D7** | **归因污染** | Camellia 自身在 scout 里有大量 HANG（Compose 设置页阻塞约 10.7s） | 与 SystemUI 异常窗口重叠，可能张冠李戴 |
| **D8** | **去重不可靠** | `guard.sh` 声明了 `last_line` 但循环里从未使用 | `logcat -T` 秒级边界会重复处理，BOOT 被重复计数 |
| **D9** | **危险动作无入口** | Tier3 默认关，设置页没有手动触发/回滚入口 | 用户出问题时只能自己敲 shell |

---

## 2. 取证通道权限实测（v3 的物理基础）

本次 `root_exec` 直测（**修正了此前 jetrace 路径记录**）：

| 通道 | 路径 | 权限 | 属主 | 大小 | **谁能读** |
|---|---|---|---|---|---|
| scout 索引 | `/data/miuilog/stability/scout/app/**/*-info` | `0774` (`o+r`) | system:system | 335 B | **任何进程（免 root）** |
| scout 全线程栈 | `.../-self-trace` | `0600` | system:system | **4.2 MB** | **仅 system_server / root** |
| jetrace | `/data/miuilog/stability/jetrace/*.trace` | `0600` | **u0_a229 = SystemUI 自己** | ~90 KB | **仅 SystemUI 进程内** |
| ANR | `/data/anr/` | root only | system | — | root |
| dropbox | `/data/system/dropbox/` | system only | system | — | system_server |
| reboot 包 | `/data/miuilog/stability/reboot/*.zip` | `0777` | root:system | — | 任何进程 |
| hanglog | `/data/miuilog/stability/hanglog/*/` | `0774` | system:system | 945 KB/zip | 任何进程 |

> ⚠️ **`/data/miuilog/jetrace` 不存在**。真实路径是 `/data/miuilog/stability/jetrace`。此前记录有误。

### 2.1 关键发现：scout 与 jetrace 是「同事件配对」

同一次 SystemUI 卡顿的实测样本：

```
scout/app/2026-10-23-16-38-55-com.android.systemui-30258-APP_SCOUT_WARNING/
    ...-info          ← 335 B，16:38:55
    ...-self-trace    ← 4.2 MB（0600 system）
jetrace/2026-10-23-16-38-56-635_com.android.systemui_30258.trace   ← 90 KB，16:38:56（0600 u0_a229）
```

`-info` 全文：

```
AppBug
BoardSensorTemp: 35.115
Exception: APP_SCOUT_WARNING
Timestamp: 2026-10-23-16-38-55
packageName: com.android.systemui
processName: com.android.systemui
Pid: 30258
Uid: 10229
activityName: empty
Current Blocking Msg: (Current message: duration=2501ms seq=8155 h=android.app.ActivityThread$H w=134)
Binder transaction info: empty
```

**三条硬结论**：

1. `Current Blocking Msg` 直接给出**卡在哪个 Handler、哪条消息、卡了多久** —— 比「崩没崩」高一个维度的信息。
2. `duration=2501ms` 精确印证 scout 阈值 **WARNING = 2.5s**（HANG = 5s）。
3. scout 记卡顿、**1 秒后 MIUI 抓 jetrace**；而 jetrace 属主是 SystemUI 自己，**只有 SystemUI 进程内能读**。这正好补上 Java hook 拿不到「卡死前调用栈」的空缺。

于是三方分工有了物理依据：**小文件给 system_server 轮询，私有/大文件给对应属主按需读。**

---

## 3. 判据全景与取舍（P1–P13）

取舍原则：① 只保留**能回答不同问题**的；② 被覆盖的降级或删除；③ 成本（轮询频率 × 文件大小 × 权限摩擦）必须匹配收益。

| 编号 | 判据 | 数据源 | 读取方 | 能回答什么 | 失效场景 | **决定** |
|---|---|---|---|---|---|---|
| **P1** | `AppErrors.crashApplication*` hook | AMS 内部 | system_server | 实时 Java 崩溃 + 完整栈 | AMS 方法改名；抓不到 native 死 | **保留（主判据 A）** |
| **P2a** | exit-info | `/data/system/procexitstore` 或内部 API | system_server | **reason + subreason + timestamp** → 死因分类 | subreason 本机恒 0/UNKNOWN | **升为主判据 B（v3 核心）** |
| **P2b** | dropbox 扫描 | `/data/system/dropbox` | system_server | 结果导向：真崩了文件一定在 | Java 栈信息少 | **降为取证备份** |
| **P3** | pid 变化 | `/proc/*/cmdline` | 任何 | 进程换号了 | **无法区分崩/被杀/正常重启** | **降级为「活性探测」，不再算崩溃判据** |
| P4 | tombstone | `/data/tombstones` | root | native 崩溃栈 | 权限高、SystemUI 少见 native 崩 | 弃（P2b 覆盖） |
| P5 | `ProcessErrorStateRecord` | AMS | system_server | ANR 对话框状态 | P1/P9 覆盖 | 弃 |
| P6 | `IncidentManager` | — | — | 全量现场 | 太重、耗时长 | 弃 |
| P7 | SystemUI 自读上次退出原因 | `ApplicationExitInfo` | SystemUI | 上次为何死 | P2a 在观察者侧已能拿到且更早 | 弃（重复） |
| **P8** | `logcat -b crash` tail | logcat | root 守护 | native/Java 崩溃原始行 | 解析成本 | **保留（低成本兜底）** |
| **P9** | ANR 全线程 dump | `/data/anr/` | root 守护 | ANR 时全线程栈 | 只在确认异常后读 | **保留（动作前取证）** |
| **P10a** | scout `-info` 轮询 | `0774` 小文件 | **system_server** | 卡在哪个 Handler / 多久 / WARNING\|HANG | 493 个子目录需按 mtime 增量 | **新增·必选（P13 输入）** |
| **P10b** | scout `-self-trace` | `0600` 4.2 MB | system_server | 卡顿瞬间全线程栈 | 文件大，只读头部 64 KB | **新增·按需（确认后才读）** |
| **P11** | jetrace | `0600 u0_a229` | **SystemUI 进程内** | **卡死前 Java 调用栈**（唯一来源） | 只能在下次启动时读上次的 | **新增·必选** |
| **P12** | `rescue_boot_count` / reboot zip | `0777` | 守护 | 是否在反复重启失败 | 粒度粗 | **新增·低成本** |
| **P13** | scout WARNING 作为 OK 否决项 | P10a | system_server | 「启动走完了但立刻卡住」 | 依赖 P10a | **新增·必选（治 D3）** |

**结论**：保留 **P1 / P2a / P2b / P3(降级) / P8 / P9 / P10a / P10b / P11 / P12 / P13**；弃 **P4 / P5 / P6 / P7**。

---

## 4. v3 架构：三侧分工

```
┌───────────────────────────────────────────────────────────────┐
│ SystemUI 进程 (uid 10229)          「观测 + 自证」            │
│  4 锚点（不变）                                                │
│  + 主线程哨兵  → 检测自己卡住，恢复后补发 STALL                │
│  + jetrace 读取 → 上次卡死前 Java 栈（属主就是自己）           │
│  + OK 自证     → 读 scout -info 确认自己没被 WARNING           │
│  产出：BOOT / OK / CRASH / STALL / TRACE / NOANCHOR / OFF      │
└───────────────────────────┬───────────────────────────────────┘
                            │ logcat  CamelliaRescue:W
┌───────────────────────────▼───────────────────────────────────┐
│ system_server 观察者 (uid 1000)    「归因引擎」★ 判断全在这    │
│  P1  AppErrors hook                                           │
│  P2a exit-info 解析（reason / subreason / timestamp）          │
│  P2b dropbox 扫描                                             │
│  P10a scout -info 轮询（0774 免 root）                        │
│  P10b self-trace 按需读头部（0600 system）                     │
│  P13 裁决 OK 是否成立                                         │
│  产出：VERDICT|verdict=..|action=..|evidence=..               │
└───────────────────────────┬───────────────────────────────────┘
                            │ logcat  CamelliaVerdict:W
┌───────────────────────────▼───────────────────────────────────┐
│ root 守护 (guard.sh)              「只执行，不判断」           │
│  P8  logcat -b crash 兜底                                      │
│  P9  ANR dump 取证（动手之前）                                 │
│  P12 rescue_boot_count 交叉校验                                │
│  执行 S0–S4 + 熔断 + self_action 记账                          │
└───────────────────────────────────────────────────────────────┘
```

**职责边界**：判断集中在**数据最全**的 system_server；root 只做它独占权限的事（kill / setprop / 读 ANR / 回滚）。任一侧失效，另两侧仍能降级工作。

---

## 5. 信号协议 v3

一行一条、`|` 分隔、自描述。`v=3` 用于版本识别，老守护读到新信号会忽略而非误判。

### 5.1 SystemUI → 观察者（`CamelliaRescue:W`）

```
BOOT|v=3|seq=<ms>|pid=<pid>|stage=<anchor>|ts=<ms>
OK|v=3|seq=<ms>|pid=<pid>|ms=<elapsed>|scout=<clean|warn=N>|ts=<ms>
CRASH|v=3|seq=<ms>|pid=<pid>|ex=<ExClass:msg>|mod=<0|1>|ts=<ms>
STALL|v=3|seq=<ms>|pid=<pid>|where=<tag>|lag=<ms>|ts=<ms>
TRACE|v=3|seq=<ms>|pid=<pid>|file=<jetrace名>|top=<首帧>|ts=<ms>
NOANCHOR|v=3|seq=0|pid=<pid>|stage=install|none_of_4_hooks_matched|ts=<ms>
OFF|v=3|seq=<ms>|pid=<pid>|rescue disabled by user|ts=<ms>
HOOKOFF|v=3|pid=<pid>|persist.camellia.sysui.hookoff=1|skip all anchors|ts=<ms>
```

**三处关键升级**：

1. **`OK` 必须带 `scout=` 自证**。`scout=clean` 才算真 OK；`scout=warn=N` 表示「启动走完了，但观测窗口内自己有 N 次 scout WARNING」→ 观察者按**半 OK** 处理（治 D3）。
2. **`CRASH` 带 `mod=`**：崩溃栈含 `com.hyper.Camellia` 帧则 `mod=1`，这是最硬的模块归因证据（治 D1）。
3. **新增 `STALL` / `TRACE`**：把「卡住但没崩」这一整类从盲区搬到明面。

### 5.2 观察者 → root 守护（`CamelliaVerdict:W`）

```
VERDICT|v=3|seq=<ms>|verdict=<V1..V6>|conf=<0.0-1.0>|action=<S0..S4>|evidence=<短串>|ts=<ms>
```

root 收到 `VERDICT` 就照 `action` 执行，**不再自行判断**。

---

## 6. 归因决策树

### 6.1 Verdict 分类

| ID | 归因 | 判定证据（任一命中） |
|---|---|---|
| **V1** | `MODULE` —— 本模块是根因 | ① `CRASH` 带 `mod=1`；② 崩溃栈含 `com.hyper.Camellia`；③ exit-info 显示崩在锚点 1/4 执行期；④ scout `Current Blocking Msg` 落在我们 hook 的方法内 |
| **V2** | `MEMORY` —— 内存压力被杀 | ① exit-info `REASON_LOW_MEMORY`；② `REASON_SIGNALED` + `SUBREASON_OOM_KILL` / `MEMORY_PRESSURE`；③ 崩溃前 rss/pss 接近设备阈值（实测 `pss=544MB/rss=795MB`） |
| **V3** | `RESOURCE` —— 资源/配置损坏 | ① exit-info `REASON_INITIALIZATION_FAILURE`；② dropbox 含 `Resources$NotFoundException` / `AssetManager` / overlay 冲突 |
| **V4** | `SYSTEMUI_SELF` —— SystemUI 自身 bug | 栈在 systemui 自身且**无**我们的帧，且 V1/V2/V3 均不成立 |
| **V5** | `EXTERNAL` —— 外部原因 | ① 用户 force-stop / 手动重启；② 系统升级或装机后首次启动；③ 守护自己的动作（`self_action`，R8）；④ 关机/重启流程中 |
| **V6** | `UNKNOWN` | 无任何证据，只有「BOOT 有 OK 无」 |

### 6.2 判定顺序（短路求值，先命中先返回）

```
收到「BOOT 有 + OK 无」或 STALL 告警
  │
  ├─(1) 落在 V5 白名单窗口？
  │     · 开机后 < 5 min
  │     · 模块版本升级后首次启动
  │     · 用户手动重启 SystemUI 后 < 10 min
  │     · 守护自身刚动过手（self_action 未过期）
  │        → V5 / S0（只记录，不动手）
  │
  ├─(2) mod=1 或栈含 Camellia 帧？
  │        → V1 / S2（摘 hook）
  │
  ├─(3) exit-info 指向内存？
  │        → V2 / S1（只软重启，绝不摘 scope、绝不清 data）
  │
  ├─(4) exit-info = INITIALIZATION_FAILURE 或资源类异常？
  │        → V3 / S2（摘 hook）
  │
  ├─(5) scout WARNING/HANG 且 blocking msg 在我们 hook 内？
  │        → V1 / S2
  │
  ├─(6) scout HANG 但 blocking 在 SystemUI 自身（如 NotificationShade dispatch MotionEvent）？
  │        → V4 / S1，累计 3 次 → S4
  │
  ├─(7) 栈在 systemui 自身、无我们的帧？
  │        → V4 / S1，累计 3 次 → S4
  │
  └─(8) 其他
           → V6 / S1，累计 2 次 → S2
```

> 关键改变：**只有 V1 和 V3 才会摘 hook**。V2（内存）走 V2/S1，**永不摘 scope** —— 这是 v2 结论的落实，也是 D1 的修复。

---

## 7. 分级自救 S0–S4

**动作按 verdict 选，不再单调加码**（治 D2）。

| 级别 | 动作 | 适用 | 副作用 | 可逆 |
|---|---|---|---|---|
| **S0** | 纯观察：只写记录，不做任何动作 | V5、校准期 | 无 | — |
| **S1** | 软重启：`kill -9 <systemui_pid>`（persistent 应用由 system_server 自动拉起），**不清理任何数据** | V2 / V4 / V6 | 极低（仅进程重启） | 自动 |
| **S2** | 摘 hook：`setprop persist.camellia.sysui.hookoff 1` + 重启 SystemUI | **仅** V1 / V3 | 模块对 SystemUI 失效（功能降级） | `setprop ... 0` |
| **S3** | 清 cache：仅删 `code_cache/*` 与 `cache/*`，**永不清 `data`、永不用 `pm clear`** | S2 无效且轨迹指向 cache | 低（自动重建） | 自动 |
| **S4** | **熔断**：停止一切自动动作 + 通知用户 + 保留现场 | 反复失效 | 无（需人工介入） | 用户手动解除 |

**Tier3「回滚 SystemUI」彻底移出自动流程**，改为设置页里的**手动按钮**（治 D4、D9）。

### 7.1 熔断规则（治 D2）

| 条件 | 动作 |
|---|---|
| 同一 verdict 30 分钟内触发 S≥1 达 3 次 | 进入 S4 |
| 24 小时内总救援达 5 次 | 进入 S4 |
| 熔断后用户手动解除 | 计数清零，回到 S0 观察 10 分钟 |

熔断期间**只记录不动手**，并在设置页顶部显示醒目状态条。

### 7.2 `self_action` 记账（R8，防自伤误判）

守护每次动手前先写：

```
self_action|<ts>|<pid>|<action>|<seq>
```

否则它自己的 `kill -9` 会被 AMS 记成 `reason=2 SIGNALED / status=9`，下次归因时被误判成 V4 甚至 V1。判定树第 (1) 步会读这条记录。

---

## 8. 防误杀与归因隔离

### 8.1 三重否决（任一生效即降级为 S0）

1. **时间否决**：开机后 < 5 分钟 / 模块升级后首次启动 / 用户手动重启 SystemUI 后 < 10 分钟。
2. **自伤否决**：`self_action` 未过期 → 是我们自己干的。
3. **升级否决**：SystemUI APK 的 mtime / versionCode 发生变化 → 正在升级，重启是正常现象。

### 8.2 归因隔离（治 D7）

Camellia 自身在 scout 里会产生大量 HANG（Compose 设置页阻塞约 10.7s），必须隔离：

- **进程过滤**：scout 条目 `processName` 必须是 `com.android.systemui`，否则丢弃。
- **栈归属**：崩溃栈含 `com.hyper.Camellia` → V1；**不含**则绝不归因到模块。
- **自身噪声白名单**：`com.hyper.Camellia` 的 scout 记录只做审计展示，**永不参与** SystemUI 判定。

### 8.3 「假 OK」二次确认（治 D3）

```
SystemUI: startServicesIfNeeded 返回 → 3s 后
    ├─ 读 scout -info，检查最近 10s 内是否有自己的 WARNING/HANG
    ├─ 无 → 发 OK|scout=clean
    └─ 有 → 发 OK|scout=warn=N（半 OK）
             观察者：仍算「启动完成」，
             但若 60s 内出现 HANG → 归 V4/V1 并升级动作
```

---

## 9. 兼容与迁移

### 9.1 旧 Tier1/2/3 → 新 S0–S4

| 旧 | 新 | 说明 |
|---|---|---|
| Tier1（清 cache + kill） | **S1**（只 kill）+ **S3**（确需时才清 cache） | 拆开：多数情况只 kill 就够 |
| Tier2（`pm clear` + 摘 scope） | **S2**（只摘 hook）+ **S3**（可选清 cache） | **移除 `pm clear`**，不再毁用户数据 |
| Tier3（回滚 SystemUI） | **手动按钮** | 移出自动流程，只在设置页手动触发 |

### 9.2 必须同步修的旧缺陷

| 缺陷 | 修法 |
|---|---|
| **D5** 键名冲突 | `SysUiRescue.KEY_BOOT` → `"camellia_sysui_rescue_seq"`；`SysUiWatch.KEY_BOOT` → `"camellia_sysui_boot_elapsed"`；同步改 `SettingsActivity.kt:2928 / 2943 / 2988` |
| **D8** 去重失效 | 实为**两层**问题：① `last_line` 声明后从未使用，`-T` 秒边界的重复行会被重复处理（BOOT 重复计数、超时计时被重置）；② 信号处理写在 `echo "$lines" — while` 的管道里，**整个分支跑在子 shell 中**，`last_line`、`flap` 等状态无法跨批次传递。修法：改为 `logcat > "$LCF"` 后用 `while IFS= read -r line; do ... done < "$LCF"`（留在当前 shell），并补 `last_line` 去重判断 |
| **D6** NOANCHOR 裸奔 | `NOANCHOR` 在设置页顶部显示红色横幅，并提供「打开救援日志」入口 |
| **D9** 无手动入口 | 新增手动区：摘 hook / 恢复 hook / 重启 SystemUI / 回滚 SystemUI / 导出取证包（均需 root） |

---

## 10. 落地顺序（四个可独立验证的批次）

| 批次 | 内容 | 涉及文件 | 可独立验证 |
|---|---|---|---|
| **PR1 修缺陷** | D5 键名拆分、D8 去重、D6 NOANCHOR 横幅、D9 手动入口 | `SysUiRescue.java`、`SysUiWatch.java`、`SettingsActivity.kt`、`sysui_rescue_guard.sh` | ✅ 不改判据，风险最低 |
| **PR2 加判据** | P2a exit-info 解析、P10a/P10b scout 轮询、P11 jetrace 读取、`STALL`/`TRACE` 信号 | `SysUiWatch.java`、`SysUiRescue.java` | ✅ 只**记录**不动作，先观察准确率 |
| **PR3 换动作** | `VERDICT` 通道、S0–S4 替换 Tier1–3、熔断、`self_action` | `SysUiWatch.java`、`sysui_rescue_guard.sh` | ⚠️ 改变行为，需 PR2 数据支撑 |
| **PR4 记录页** | 归因分布、动作历史、熔断状态、取证导出 | `SettingsActivity.kt` | ✅ 纯 UI |

> 顺序原则：**先让系统会看，再让系统会动。** PR2 上线后建议先跑 2–3 天纯观察，确认 verdict 分布合理再开 PR3。

---

## 11. 需要用户确认的三项取舍

| # | 议题 | 建议 | 备选 |
|---|---|---|---|
| **Q1** | 是否**彻底移除** `pm clear com.android.systemui` | **移除**（改用 S3 只清 cache） | 保留但仅在 S3 之后仍失败时手动确认 |
| **Q2** | Tier3「回滚 SystemUI」是否只保留为**纯手动** | **纯手动** | 保留自动但要求用户预授权 |
| **Q3** | 校准期长度 | **开机后 5 分钟 + 模块升级后首次启动** | 缩短到 2 分钟 / 延长到 10 分钟 |

---

## 12. 验证方法（每批可直接执行）

```bash
# PR1：确认键名已拆分，不再互相覆盖
settings list global | grep -E "camellia_sysui_(rescue_seq|boot_elapsed)"

# PR2：确认 scout 判据真的能读到
ls -lt /data/miuilog/stability/scout/app | head -3
cat /data/miuilog/stability/scout/app/<最新目录>/*-info

# PR2：确认 jetrace 可被 SystemUI 读（在 SystemUI 进程内）
# 期望：进程内 logcat 出现 TRACE|v=3|...|file=2026-.._com.android.systemui_<pid>.trace

# PR3：验证归因（构造内存压力型重启）
# 期望：VERDICT|verdict=V2|action=S1（而不是旧逻辑的摘 scope）

# PR3：验证熔断
# 期望：30 分钟内第 3 次触发后出现 VERDICT|action=S4，且不再有 kill 动作
```

**判据准确性观察指标**（PR2 期间记录）：

| 指标 | 期望 |
|---|---|
| verdict 分布 | V5（外部）应占多数，否则说明判据过敏 |
| V1（模块）检出率 | 人为注入模块异常时 **100% 命中** |
| V2（内存）检出率 | 内存压力重启 **不应**被判 V1 |
| 假 OK 残留 | `scout=warn=N` 应能捕获 D3 场景 |
| 误杀率 | 非真实故障的 S≥1 动作次数应为 **0** |

---

## 13. 实现进度（2026-10-23）

### 13.1 已完成：`tools/sysui_rescue_guard.sh` v3

守护脚本（root 侧）已按本设计**整体重写**，一次性覆盖 PR1 的守护部分与 PR3 的动作层。

| 维度 | v2 | v3 |
|---|---|---|
| 判据来源 | 自己判「BOOT 有 / OK 无」 | **不判**，只执行 `VERDICT` |
| 动作 | Tier1 → 2 → 3 单调加码 | S0–S4 **按 verdict 对症** |
| `pm clear` | Tier2 使用 | **已删除**（治 D4） |
| 自动回滚 | Tier3 自动 | **已删除**，改手动子命令（治 D9） |
| 重复行去重 | 无（D8） | 文件重定向 + `last_line`（治 D8） |
| 失控熔断 | 无 | 同 verdict 3 次/30min；总量 5 次/24h |
| 自伤记账 | 无 | `self_action.log`（治 R8） |
| 校准期 | 无 | 开机 300s、手动重启后 600s（治误杀） |
| `hookoff` 自维持防护 | 无 | `hookoff=1` 时忽略一切动作请求 |
| 手动入口 | 无 | 8 个子命令 |

**部署位置**：`/data/adb/service.d/50-camellia-rescue.sh`（已部署，755）

**子命令一览**（供设置页按钮直接调用）：

```sh
sh 50-camellia-rescue.sh status        # 打印状态、state、最近动作
sh 50-camellia-rescue.sh selftest      # 11 项自检（DRYRUN，不动真实系统）
sh 50-camellia-rescue.sh restart       # 软重启 SystemUI（S1）
sh 50-camellia-rescue.sh hook-off      # 摘 hook 并重启（S2）
sh 50-camellia-rescue.sh hook-on       # 恢复 hook 并重启
sh 50-camellia-rescue.sh clear-cache   # 仅清 cache/code_cache 并重启（S3）
sh 50-camellia-rescue.sh rollback      # 回滚 SystemUI（纯手动，替代旧 Tier3）
sh 50-camellia-rescue.sh fuse-reset    # 解除熔断、计数清零
sh 50-camellia-rescue.sh export        # 导出取证包到 /sdcard/Download/
```

### 13.2 自检结果（真机 root 实测，11/11 通过）

| # | 用例 | 结果 |
|---|---|---|
| 1 | `state_set/get` 往返 | OK |
| 2 | `state_set` 清空 | OK |
| 3 | `state_int` 非数字归零 | OK |
| 4 | `state_int` 正常解析 | OK |
| 5 | `record_self_action` 追加 | OK |
| 6 | `in_quiet_period`（已过校准期应非静默） | OK |
| 7 | `fuse_hit` 同 verdict ×3 触发 | OK |
| 8 | `fuse_hit` 不误伤其他 verdict | OK |
| 9 | `fuse_hit` 总量上限触发 | OK |
| 10 | 动作链路 S0–S4 全部执行 | OK |
| 11 | S4 置 `fused=1` | OK |

### 13.3 实测踩到的坑：POSIX sh 没有 `local`

首版运行后 `hist` 记录了 `verdict=1792747024`（时间戳）而不是 `V6`。根因：

```sh
state_set() { sc_k=$1; sc_v=$2; ... }   # 原本写作 _k/_v
run_action() { _v=$1; ...; state_set last_action_ts "$now"  # ← 这里把 _v 覆盖成时间戳
               hist "verdict=$_v" }                          # ← 于是打印出时间戳
```

`sh`/`mksh` 的函数没有局部作用域，内层赋值直接改外层同名变量。**修法**：所有函数内变量改用专属前缀——`state_set` → `sc_*`、`state_int` → `si_*`、`fuse_hit` → `fh_*`、`run_action` → `ra_*`、`restart_sysui` → `rs_*`。新增函数时必须继续遵守。

### 13.4 剩余工作

| 批次 | 内容 | 状态 |
|---|---|---|
| PR1 守护侧 | D8 去重、D4 移除 `pm clear`、D9 手动入口 | ✅ 完成 |
| PR1 Java 侧 | D5 `KEY_BOOT` 键名拆分（`SysUiRescue.java:223`、`SysUiWatch`、`SettingsActivity.kt:2928/2943/2988`） | ⬜ 待做 |
| PR1 UI 侧 | D6 NOANCHOR 红色横幅 + 救援日志入口 | ⬜ 待做 |
| PR2 | P2a exit-info、P10a/P10b scout、P11 jetrace、`STALL`/`TRACE` 信号 | ⬜ 待做 |
| PR3 观察者侧 | 归因决策树 + `VERDICT` 下发 + 假 OK 二次确认 | ⬜ 待做 |
| PR4 | 设置页归因分布 / 动作历史 / 熔断状态 / 取证导出 | ⬜ 待做 |

> 守护侧已完成「会执行」，但**目前还没有人给它下 `VERDICT`**。在 Java 侧 PR3 完成前，守护只会在 `BOOT` 超时或 `PID_FLAP` 时以最保守的 `S1`（只 kill）兜底，不会摘 hook、不会清数据——这正是 v3 相对 v2 的核心安全改进。

---

## 附录：本章新增证据索引

| 证据 | 出处 | 用途 |
|---|---|---|
| scout `-info` 0774 可读 | 本次 `ls -ld` 实测 | P10a 可用性 |
| scout `-self-trace` 0600 system | 本次实测（4.2 MB） | P10b 归 system_server |
| jetrace 0600 **u0_a229** | 本次实测（90 KB） | P11 **必须**在 SystemUI 内 |
| jetrace 真实路径 `stability/jetrace` | 本次 `find` 实测 | 修正旧记录 |
| `duration=2501ms` 印证 WARNING 2.5s | 本次 `-info` 采样 | 阈值依据 |
| scout↔jetrace 同事件配对（16:38:55 / 16:38:56） | 本次采样 | P11 价值论证 |
| exit-info 17 REASON / 44 SUBREASON | v2 实测 | P2a 分类表 |
| `pss=544MB/rss=795MB` | v2 实测 | V2 判定阈值 |
| NotificationShade dispatch 超时 5000ms | v2 lastAnrState | V4 判定样例 |
| Camellia 自身 scout HANG ~10.7s | v2 实测 | R9 / D7 归因隔离 |
