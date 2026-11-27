# camd 崩溃判定口径（实现版）

> 对应代码：`tools/camellia_rescue.c`
> 验证命令：`camd --self-test` / `camd --diagnose`
> 本文件记录的是**代码实际行为**，与 `sysui_rescue_v5_design.md`（设计决议）互补。

---

## 1. 为什么必须改判定口径

旧实现：只要 `/proc/<pid>` 里的 pid 和上次记录值不同，就认定「崩了」并进入修复。

真机踩坑：SystemUI 是常驻 UI 进程，正常场景也会换 pid ——

| 场景 | pid 是否变 | 是否算崩溃 |
|---|---|---|
| 主题/深色模式切换导致 SystemUI 重建 | 变 | **否** |
| 系统内存回收后 persistent 进程被拉起 | 变 | **否** |
| 模块自身 OOM / dex 加载失败 | 变 | **是** |
| 覆盖层资源错，渲染时 ANR 被 kill | 变 | **是** |

pid 变化只证明「进程重启过」，**不构成崩溃证据**。旧逻辑因此存在误判风险：
连续两次正常重建就可能触发删包 + 软重启。

---

## 2. 判定模型：三条信号 + 时间窗口

判定在 `rescue_tick()` 内进行，每轮 tick 计算一次：

```
window   = cfg.crash_window_sec   （默认 60 秒）
thr      = cfg.crash_threshold    （默认 3 次）
```

### 信号一：dropbox 崩溃条数（一手证据，分两级）

扫描 `/data/system/dropbox/`，先按文件名匹配崩溃类型
（`system_app_anr` / `system_app_crash` / `system_server_crash` /
`system_server_watchdog` 等）并筛掉窗口外的条目。未压缩的 `.txt` 直接读；
`.gz` 通过 toybox `gzip -dc` 解压后读正文（旧代码直接 `fopen` 读 `.gz`，
拿到的是二进制垃圾，归因必然失败）。

**同一条目要过两道口径，分别计数**：

| 级别 | 判据 | 函数 | 回答的问题 |
|---|---|---|---|
| 一级 | 正文有 `Process: <界面进程>` | `process_owner()` | 崩了没有（不问是谁） |
| 二级 | 堆栈/资源里有组件或本体特征 | `attribute_text()` | 崩的是谁（供归因决策） |

为什么一级不可省：崩溃循环里堆栈往往全是框架类（`ZygoteInit` /
`ActivityThread` / `Looper` / `Handler`），二级归因返回 `ATTR_NONE`。
旧实现此时直接 `continue` 丢掉条目 —— 「崩了 5 次」这个事实被抹成 0 次，
只能靠 pid 变化兜底，而 pid 采样有间隔、又分不清崩溃与正常重启。
真机现场（T8c / T9d）：12 条 crash 里二级只命中 7 条。

一级的取值约定：

- `Process: com.android.systemui` → `ATTR_BOTH`（两包共用这个进程名，
  一级层面分不开，只表示「确实有界面进程在崩」）
- `Process: miui.systemui.plugin` → `ATTR_PLUGIN`（ROM 若给它独立进程）
- `Process: system_server` → `ATTR_NONE`（它是软重启元凶，不算界面崩溃）

### 信号二：pid 变化次数（辅助证据，只记账）

每次 tick 发现 pid 与 `state/spid_<pkg>` 记录值不同时：

- `crash_hist_add()` 往 `state/crash_hist` 追加一条当前 uptime（秒）
- 更新 `state/spid_<pkg>`
- **本身不触发修复**

`state/crash_hist` 是**滚动窗口**：写入时会把「窗口外的」和
「时间戳在未来的」条目一并丢弃（后者防硬重启 —— uptime 归零后旧记录的
绝对时间会落在未来，不丢弃会造成连续误判）。最多保留 16 条。

### 判定

```
crash = db_attr_cnt >= thr      /* 二级：归因命中，能分清是谁 */
     || db_proc_cnt >= thr      /* 一级：进程命中但堆栈无特征 */
     || pid_cnt >= thr          /* 间接：进程反复重启 */
```

三条**任一达标**即判崩溃循环，不做叠加，避免同一次崩溃被重复计数。
`db_proc_cnt` 与 `db_attr_cnt` 是同一次扫描的两个视角
（`db_proc_cnt >= db_attr_cnt` 恒成立）；任一条触发都记 `CRASH_WIN`，
日志里同时打印 `db=`（二级）、`proc=`（一级）、`pid=`（pid 变化）三个数，
事后可判断是哪条判据在起作用。

---

## 3. 归因：四档 + 判不出就双修

`culprit_attr(min_ts_ms, why, ...)` 返回：

| 值 | 含义 | 处理 |
|---|---|---|
| `ATTR_PLUGIN` | 证据指向组件 `miui.systemui.plugin` | 只修组件 |
| `ATTR_SYSUI` | 证据指向宿主 `com.android.systemui` | 只修宿主 |
| `ATTR_BOTH` | 判不出来（见下） | **B 段：两个都修** |
| `ATTR_NONE` | 保留值，实际不再返回（一律降级为 BOTH） | — |

归因顺序（短路求值）：

1. **dropbox 正文**（一手证据）— 解析崩溃堆栈里的包名/类名
2. **插件加载期标记** — `state/` 下的 marker，限 `PLUGIN_LOADING_WINDOW_MS` /
   `PLUGIN_ERROR_WINDOW_MS` 时间窗
3. **改动面排除法** — 看两侧覆盖层是否存在：
   - 只有组件有覆盖层 → PLUGIN
   - 只有宿主有覆盖层 → SYSUI
   - 两侧覆盖层状态相同（都是 ROM 或都有） → **判不出 → BOTH**

### 为什么判不出要双修

单方面「猜一个删掉」有两种失败模式：

- 猜错 → 真正的问题包还在，下轮再崩，用户多经历一次软重启
- 猜对 → 只删一半，另一半的坏 overlay 仍在，可能继续崩

既然已经**确证崩了 N 次**（这是双修的前提），那么「两个都撤掉」的代价
（一次性多撤一个 overlay，随时可用 `--fix` 装回）远小于反复软重启。

### 归因必须限定在同一时间窗口内 ⚠️

`culprit_attr()` 的第一个参数是 `min_ts_ms`。**调用方必须传窗口起点**，
不能传 `0`。

真机复现过的问题：诊断输出显示「窗口内 0 条命中」，但归因却是
`sysui`，依据是 `system_app_anr@...txt.gz` —— 那条崩溃发生在 **2.9 天前**。
不限时间取「最近一条」会把几天前的崩溃归因套到这次崩溃上，方向直接错，
于是该修组件却去删宿主。

现在：

- `rescue_tick()` → `culprit_attr(since, ...)`，`since = now - window*1000`
- `cmd_diagnose()` → 同样传 `since`，另附一行「不限时间」的参考值

---

## 3.5 DE / CE 阶段判定：只认 `sys.user.0.ce_available`

A 段只在 DE（用户还没解锁、锁屏画不出来）时该出手，所以 `phase_de()` 的准确性
直接决定 A 段会不会被执行。这个判定踩过三代坑，最终只保留一条权威判据。

| 代 | 判据 | 为什么错 |
|---|---|---|
| 1 | `access("/data/user/0", F_OK)` | F_OK 只查「路径能否解析」，CE 存储挂载点在 DE 阶段就已存在 → **恒判 CE**，A 段永不执行 |
| 2 | `readdir("/data/user/0")` 有子项 | FBE 只加密**文件内容**，目录项（包名）在 DE 阶段完全可见 → 照样能清点出全部子项 |
| 3 ✅ | `getprop sys.user.0.ce_available` | 与系统同一来源。DE 阶段属性为**空串**，解锁后为字符串 `"true"` |

注意它是 boolean 属性，输出是 `true` / `false`（不是 `1` / `0`），解析必须两种都认。

真机日志（`2509FPN0BC` / Android 17，一次开机完整走完两个阶段）：

```
[up=42]  PHASE_DET ce_avail=''      up=42  ce=0 de=1   ← DE（属性尚未生成）
[up=102] A_MOD_ALREADY_DISABLED id=systemui_mount
[up=102] A_ALIVE_SKIP sysui alive circ=0/3 win=180s    ← A 段真的执行了
[up=117] PHASE_DET ce_avail='true'  up=117 ce=1 de=0   ← 解锁瞬间切 CE
```

`PHASE_DET` 只在判定值变化时写一条，用来事后核对。排查时**不要**用「从最后一条
`BOOT_NEW` 开始切片」的方式看日志 —— `PHASE_DET` 与 `BOOT_NEW` 同秒且写得更早，
会被切掉，直接 `grep PHASE_DET` 才可靠。

---

## 4. A 段与 B 段对 `ATTR_BOTH` 的处理**不同**

这是本次改造最容易搞错的地方。

| 阶段 | 时机 | 有「窗口内崩了 N 次」这个前提吗 | `ATTR_BOTH` 怎么办 |
|---|---|---|---|
| A 段 | 开机早期（DE 阶段，`up > 90s`） | **没有** | **只诊断，不动手** |
| B 段 | 运行中 tick 检测到崩溃 | **有** | 双修（两个 overlay 都撤） |

原因：A 段每次开机都会跑。如果 A 段在归因不明时也双修，等价于
「只要 `auto_fix` 开着、uptime 过了 90 秒，就把宿主和组件的覆盖层各删一遍」——
绝大多数用户每次开机都会被无差别手术。所以 A 段必须保守：

```
attr == ATTR_BOTH  →  write_pending("unknown", "diagnose_only")  →  return 0
```

A 段的归因仍用 `culprit_attr(0, ...)`（不限时间）是**有意的**：A 段要判断的
正是「上一次开机之后用户是否崩过」，那个证据必然早于本次开机。

### A 段的触发判据：`pidof` 有值 ≠ 没崩 ⚠️

旧实现的第一道门是这样写的：

```c
if (pid_of_pkg("com.android.systemui") > 0) return 0;   /* ← 致命漏判 */
```

`pidof` 有值就认为「进程活着，别乱动」。这在**崩溃循环**下几乎必然漏判：
SystemUI 是常驻服务，被 Zygote 3 秒一轮反复拉起，`pidof` 采样大概率落在
「进程在、还没崩」的窗口里。于是 DE 黑屏循环时 camd 每次都 `return 0`，
一直不救 —— 这正是一线「界面死了但 camd 说没事」的根因。

新判据改成两条**互补**，任一成立才继续归因：

| # | 判据 | 含义 |
|---|---|---|
| ① | `pidof == 0` | 进程真的没了 → 起不来 |
| ② | 窗口内 pid 变化次数 `>= a_circ_thr` | 进程在但反复换 pid → 崩溃循环 |

两个都不成立（进程在 **且** 窗口内没反复换 pid）才 `return 0`。此时黑屏更可能
是渲染卡死 / SurfaceFlinger 问题，删包只会让用户更难恢复。

**A 段账本与 B 段账本必须物理隔离**，各用各的文件与窗口：

| | 文件 | 默认窗口 | 阈值 | 语义 |
|---|---|---|---|---|
| A 段 | `state/a_crash_hist` | 180s | 3 次 | 开机后界面起不来 |
| B 段 | `state/crash_hist` | 60s | 3 次 | 解锁后装包把界面搞崩 |

共用一份账会出两个方向的错：A 段 180s 长窗口会把 B 段刚清零的计数算回来；
B 段 `watch_install` 的重置会误清 A 段在开机期攒的崩账。

账本的价值在**跨软重启**：软重启（`killall system_server`）不重置 uptime，
所以「软重启后又崩了」能被同一份账本累计 —— 这正是判「修了没用」需要的。
硬重启换 boot_id、uptime 归零，旧时间戳会变成「未来」，`a_hist_count()`
按 `v > now` 自动丢弃，不需要额外清理。

修复收尾（`reboot_after_fix()` 与 `a_phase()` 出手后）必须做两件事：

```c
a_hist_reset();              /* 账本作废：剩余 tick 不该拿旧账再判一次 */
st_set("apid_sysui", 0);     /* 清 pid 基线：软重启后 pid 必变，否则记一笔假崩 */
```

若不清 pid 基线，软重启后第一次采样就会把「pid 变了」记成一笔崩账，
把 180s 窗口弄脏，下一轮判定被污染。

**真机验证**（2026-11-02，`2509FPN0BC` / Android 17）：SystemUI 正常活着
（`pid=7487`）时伪造 3 条窗口内 pid 变化记录，`--a-phase --dry` 立刻从
「alive 放过」转为触发归因：

```
phase=CE ce_ok=1 auto_fix=0 up=10551 dry=1
mod_systemui_active=0
mod_systemui_disabled=1 systemui_mount
attr=sysui why=dropbox:system_app_anr@1793372209941.txt.gz(cnt=1)
systemui_pid=7487 alive=1
a_win=180s a_thr=3 a_hist=3
DRY pending: pkg=com.android.systemui action=diagnose_only ovl= why=rom-builtin
```

旧实现在同一场景会直接 `return 0`，什么都不打印。

---

## 4.5 卸更新后的自愈观察窗（VERIFY）

卸更新（`pm uninstall-system-updates` / 删除覆盖层包）本身就是一次有效修复：
坏包消失后 SystemUI 重启通常就能起来。既然如此，卸完**立即**软重启就是多余动作 ——
用户白等一次 30 秒黑屏，还白白消耗一次 `sr_max` 预算。

所以 B 段卸更新成功后不再直接软重启，而是开一个观察窗由 `tick` 裁决：

| 项 | 值 | 说明 |
|---|---|---|
| 窗口长度 | `VERIFY_SEC = 30` s | 覆盖 Zygote 至少一轮重启 + SystemUI 首帧 |
| 自愈条件 | 进程活着 **且** 无新崩溃 | `pid_of_pkg("com.android.systemui") > 0 && !new_cr` |
| 未自愈 | 软重启兜底 | `a_hist_reset()` + `do_soft_reboot()` |
| 作废 | `age > VERIFY_SEC * 20`（600 s） | 中途被关过 `auto_fix`，窗口已无意义，直接清零不动作 |
| 位置 | A 段之前（`if (!c.auto_fix) return 0;` 之后） | 上次修复的结论必须先落定，否则 A 段会拿旧账重判 |

### 「有没有新崩溃」必须用全量条目数 ⚠️

这里**不能**复用 `dropbox_window()` 的命中数。那个函数只统计**归因命中**的条目
（`attribute_text()` 返回 `NONE` 的直接跳过）。而崩溃循环期间的堆栈常常全是框架类
（`ZygoteInit` / `ActivityThread` / `Looper` / `Handler`），归因结果是 `NONE` ——
被过滤成 0 条，于是「还在崩」被读成「已自愈」，不发软重启，用户继续黑屏。

正确的口径是 `drop_max()`：目录内**所有** crash 条目的最大时间戳，只比大小、
不做归因。判据要回答的是「还崩不崩」，与「崩的是谁」无关。

实测证据就是 T8c：同一次扫描里 dropbox 有 **12** 条 crash，归因只能命中 **7** 条
（差 5 条）。用命中数等于放掉 5 次崩溃信号。

### 基线必须锚在「卸更新那一刻」

```c
long vwall = now_ms_rt() - (now_up() - vts) * 1000L;  /* 开窗时刻的 wall clock */
long base  = st_get("sr_drop_max");                   /* 卸更新时记的最新崩溃 */
if (base < vwall) base = vwall;                       /* 不早于卸更新时刻 */
new_cr = (drop_max() > base);
```

不能用 `now_ms_rt() - VERIFY_SEC * 1000` 反推：那会把窗口右端划到「现在」，
恰好漏掉「刚卸完就崩」的那几秒 —— 而那几秒正是判据最该看的地方。

### 自愈成立要回退 `sr_count` 预算

卸更新时 `sr_count` 已 +1（那是一次**真实修复尝试**，先记账防止无限循环）。
但观察窗裁决自愈时**并没有真的软重启**，所以这里必须回退这一笔：

```c
long sc = st_get("sr_count");
if (sc > 0) st_set("sr_count", sc - 1);
```

否则连续装两次坏包、两次都自愈，`sr_max` 预算会被白白耗光直接熔断 ——
第三次真需要软重启时反而被拦下。

---

## 4.6 判定起点何时归零

窗口判据（「60 秒内崩 ≥3 次」）有个隐含前提：起点必须是**上一次动手的时刻**。
以下三件事会让起点归零，否则修复动作自身造成的重启会被当成「又崩了」：

| 事件 | 代码位置 | 动作 |
|---|---|---|
| 覆盖层变更（`INSTALL_EVENT`，判据 = `apk_stamp` 变化） | `watch_install()` 内 | `crash_hist_reset()`（+ A 段账本一并清） |
| 修复动作落地（卸更新 / 删覆盖层 / 软重启） | `ui_rescue_chain()` / `a_rescue()` 尾部 | 记 `sr_ts`；A 段 `A_HIST_RESET` |
| 连续 `stable_sec`（默认 600s）无崩溃 | tick 末尾 | 归零，并清 `nofx_count` |

三者缺一：卸更新后 plugin 重启、软重启后系统进程批量重启，都会在 60 秒窗口里
堆出 ≥3 次 pid 变化，立刻二次触发 —— 这就是「修完又修、越修越坏」的来源。
真机日志里的 `INSTALL_EVENT ... -> 重置判定` + `CRASH_HIST_RESET` 就是这条链在生效。

注意 B 段的 VERIFY **不参与**归零：它比的是「卸更新那一刻」的基线时间戳，
与账本无关，所以 reset 账本不会把「还在崩」误读成「已自愈」。

---

## 5. 验证方法

### 只读诊断

```sh
camd --diagnose
```

输出与 `rescue_tick()` 同一套口径，避免「诊断说崩了但 tick 不动」：

```
up=4148s  win=60s  thr=3
最新 dropbox 条目 age=248476s（仅参考，单条不触发）
  com.android.systemui    pid=7487  prev=7782  → pid 变过（只记账，不算崩）
      覆盖层: 无（系统分区版本，不会去删）
  miui.systemui.plugin    pid=0     prev=0     → 正常

[判定依据]
  dropbox 窗口内命中 0 条
  pid 变化窗口内 0 次
  阈值 3 次 → 不会触发
  归因(窗口内): both（判不出 → B段双修） (unknown->both(sysui=rom,plugin=rom))
  归因(不限时间,仅供参考): sysui (dropbox:system_app_anr@...txt.gz(cnt=1))
```

### 逻辑自测（不触发任何修复）

```sh
camd --self-test
```

会临时改写 `state/crash_hist` / `state/a_crash_hist`（测完恢复原内容），断言 9 组：

| 用例 | 断言 |
|---|---|
| T1 3 条窗口内 + 2 条过期 | `count == 3` |
| T2 追加一条 | `count == 4`（过期条目被挤出文件） |
| T3 含未来时间戳（模拟硬重启） | `count == 1` |
| T4a/T4b dropbox 窗口 / 不限时间 | 能数出条目并写出最新文件名（证明 `.gz` 可读） |
| T5a/T5b 归因两档 | 都必须返回非 `NONE` |
| T6 A/B 账本隔离 | 写 A 段账本后，B 段 `hist_count()` 必须仍为 0（证明两份账不互相污染） |
| T7 A 段参数生效 | `a_crash_window_sec` / `a_crash_threshold` 读到非 0 且与配置一致 |
| T8 卸更新后自愈判据 | 基线 = 最新时间戳 → `new_crash == 0`；基线早 1 ms → `new_crash == 1`（证明判据只比时间戳、不受归因过滤影响） |
| T9 一级计数（`Process:` 行） | `system_server`→`NONE`；两包共用进程名→`BOTH`；组件独立进程→`PLUGIN`；别的应用/无 `Process` 行→`NONE`；且一级计数 ≥ 二级计数（T9d） |

真机输出（2026-11-03 08:37，小米 `2509FPN0BC`，Android 17；camd 二进制 673424 B）：

```
win=60s thr=3 up=4624
  T1 3条窗口内+2条过期 → count=3（应3）PASS
  T2 add 后 → count=4（应4，说明过期已挤掉）PASS
  T3 含未来时间戳(模拟重启) → count=1（应1）PASS
  T4a dropbox 60s 窗口内 0 条
  T4b 不限时间 7 条 最新=system_app_crash@1793662351917.txt
  T5a 归因(窗口内)=both (unknown->both(sysui=rom,plugin=rom))
  T5b 归因(不限时间)=plugin (dropbox:system_app_crash@1793662351917.txt(cnt=7))
  T6 只写B段后 A段计数 0→0（应不变）PASS
  T7 A段 win=180s thr=3 PASS
  T8a 基线=dmax(1793662351917) → new_crash=0（应0）PASS
  T8b 基线=dmax-1 → new_crash=1（应1）PASS
  T8c 全部crash=12 归因命中=7（采样上限24）→ 口径差 5
       ↑ 有 5 条未归因，若 VERIFY 用命中数会漏判「还在崩」
  T9.1 system_server 排除 → none（应 none）PASS
  T9.2 界面进程(两包共用名) → both（应 both）PASS
  T9.3 组件独立进程 → plugin（应 plugin）PASS
  T9.4 别的应用 → none（应 none）PASS
  T9.5 无 Process 行 → none（应 none）PASS
  T9d 一级(Process命中)=7 二级(堆栈归因)=7 → 一级>=二级 PASS
  已恢复原始 crash_hist（5 字节）
SELF-TEST PASS
```

T8c 的两组数字是这份判据的现场证据：同一次扫描里 dropbox 有 12 条 crash，
但只有 7 条能被归因到 sysui/plugin。若 VERIFY（卸更新后判断是否自愈）用
归因命中数，剩下 5 条会被跳过 —— 「还在崩」被读成「已自愈」。
故 VERIFY 一律用全量条目数（`drop_entry_count`），只比时间戳。

---

## 6. 相关配置项

写在 `/data/adb/camellia/config`，由 `cfg_load()` 读取：

| 键 | 默认 | 说明 |
|---|---|---|
| `crash_window_sec` | 60 | 崩溃信号统计窗口（秒） |
| `crash_threshold` | 3 | 窗口内达到多少次判定为「崩了」 |
| `a_crash_window_sec` | 180 | **A 段**独立崩溃窗口（秒），与 B 段互不影响 |
| `a_crash_threshold` | 3 | **A 段**独立阈值（窗口内 pid 变化次数） |

调参建议：

- 想让救援更敏感（更快动手）→ 降到 `3`/`45`，但误判风险上升
- 想更保守（宁可多崩几次也不误删）→ 升到 `5`/`120`

---

## 7. 已知边界

1. **dropbox 目录见不到**（SELinux 拒或目录为空）时，只剩 pid 信号。
   pid 信号本身不构成崩溃证据，所以实际判定会偏保守 —— 这是有意的降级方向。
2. **`.gz` 依赖 toybox `gzip`**。若设备无 `gzip`，解压失败会退化为读文件名
   匹配（能识别崩溃类型，但拿不到堆栈正文，归因精度下降）。
3. **归因的排除法只比较「覆盖层是否存在」**，不比较覆盖层内容差异。
   两侧都有 overlay 时必然落入 `BOTH`（这也是双修设计要覆盖的常见情形）。
4. `crash_hist` 最多 16 条，窗口内次数超过 16 时按满即挤最旧处理 —— 阈值
   远小于 16，实际不受影响。

---

## 8. 部署步骤（必读：漏一步就等于没部署）

camd 不是「替换二进制就生效」。真正的运行体是**隐藏工作副本**，
由 `/data/adb/service.d/camellia_daemon.sh` 从 seed 复制出来：

```
seed   /data/adb/camellia/camd          ← 替换这里
sig    /data/adb/camellia/camd.sig      ← 必须同步更新，否则不刷新
work   /dev/<随机名>/.x/camd            ← 实际运行的是这个
work 戳 /dev/<随机名>/.x/.sig
```

守护脚本的刷新判据（关键，v2.29.2 起改为内容哈希）：

```sh
seed_sig() { md5sum "$SEED" 2>/dev/null | cut -c1-16; }   # 算内容，不读 sig 文件
SIGV=$(seed_sig)
if [ ! -s "$WORK/camd" ] || [ "$SIGV" != "$(cat "$WORK/.sig" 2>/dev/null)" ]; then
    pkill -x camd; cp -f "$SEED" "$WORK/camd"; echo "$SIGV" > "$WORK/.sig"
fi
```

> ⚠️ **2026-11-03 事故（本判据的由来）**：旧判据读 `camd.sig`（`大小-时间戳` 格式），
> 而那次重编前后 camd 都是 **673424 B**、mtime 也未变 → sig 一字不差 → 判据认为
> 「已是最新」→ 隐藏副本永远是旧版（md5 `c10efa81`，实际应为 `0adf00a3`）。
> 结果是**只部署了 CE 母本，线上跑的一直是旧代码，全程没有任何报错**。
> 教训：判据必须锚在**内容哈希**上；任何「大小 / 时间戳 / 版本号」型判据都会在
> 「同尺寸重编」时静默失效。

现在判据不再依赖 `camd.sig`，但仍建议顺手把内容哈希写进 `camd.sig`，便于人工核对。

正确步骤：

```sh
# 1. 备份 + 替换 seed
cd /data/adb/camellia
cp -f camd camd.bak.$(date +%s)
cp -f <新编译的 camd> camd && chmod 700 camd

# 2. 把内容哈希写进 sig（仅信息用途，判据不读它）
md5sum camd | cut -c1-16 > camd.sig

# 3. 让守护脚本立即刷新一次（--once 内部 pkill + cp + 拉起）
sh /data/adb/service.d/camellia_daemon.sh --once

# 4. 校验：运行进程的 md5 必须等于 seed
for p in $(pidof camd); do md5sum /proc/$p/exe | cut -c1-16; done

# 5. 校验：跑工作副本自测（旧版没有 --self-test，会打印用法）
/dev/*/.x/camd --self-test | tail -3
```

> ⚠️ 别用 `$(stat -c %Y f) * 1000` 造 sig：busybox/mksh 的算术是 32 位，会溢出成
> 负数（`118--1675452728`）。现在 sig 是内容哈希，不碰时间戳，但旧脚本里可能还留着这行。

> ⚠️ camd 运行中不会周期性比对自身文件，`binary refreshed` 只在**启动时**打。
> 「替换文件后等一会儿它会自己升级」是错的，必须 kill 或 `--once` 触发重启。

### 8.1 APK 侧：`assets/camd.bin` 必须与编译产物同步 ⚠️

`DaemonInstaller` 从 `assets/camd.bin`（gzip）释放 camd，并把它当作 **DE 母本的权威源**。
三处判据现在全部是内容哈希：

| 环节 | 判据 | 代码 |
|---|---|---|
| APK → CE 释放 | `versionCode-lastUpdateTime-md5(assets)` | `DaemonInstaller.appVersion()` |
| CE → DE 同步 | `md5(DE 母本) == md5(assets)` | `DaemonInstaller.syncToDe()` |
| DE seed → work | `md5(seed) == work/.sig` | `camellia_daemon.sh:seed_sig()` |

只要 `assets/camd.bin` 是旧的，装一次 APK 就会把设备上更新的 camd **覆盖回旧版**，
而且判据本身是「APK 内内容」，脚本侧再新也救不回来。

因此 `build.gradle` 加了闸门 `verifyCamdAsset`（挂在 `preBuild`）：
比较 `gzip -dc assets/camd.bin` 与 `.camd-tmp/camd` 的 MD5，不一致直接 `BUILD FAILED`，
并提示重跑 `tools/build_camd.sh`。

> 已实测：放入错误二进制的 gzip → 构建失败（报出两侧 md5）；恢复后正常。
> 注意：只在文件**尾部追加**垃圾字节的情况 GZIPInputStream 会忽略、拦不住；
> 但真实风险是「整份 camd 是旧版」，这种能拦住。

---

## 9. 安装事件监听（`watch_install`）

### 为什么需要
用户「装了个坏包 → 界面起不来」是最高频场景。没有监听时，旧崩账（崩溃历史）
会一直积累，新装的包还没崩过就被旧账带进急救，属于误伤。监听的作用就一件事：
**包一变，旧账清零，只判新包**。

### 判据：轮询 `apk_stamp`，不用 inotify
`apk_stamp` = `apk 文件 mtime ^ (size << 1)`，定位 apk 走 `codePath`。
- `/data/app` 的安装是 PMS 事务（多目录改名的中间态），inotify 会看到一串半成品事件；
- stamp 是稳定指纹，一次安装/替换恰好变一次 —— 天然去抖，也不占常驻 fd。

`codePath` 有两种形态，都要兼容：
| 形态 | 例子 | 定位方式 |
|---|---|---|
| 目录 | `/system_ext/priv-app/MiuiSystemUI` | 扫目录里**第一个 `.apk`** |
| 直接文件 | `/data/app/~~x==/pkg-y==/base.apk` | 直接用 |

> ⚠️ 系统应用目录下的 apk **不叫 `base.apk`**（真机是 `MiuiSystemUI.apk`、
> `MIUISystemUIPlugin.apk`）。写死 `%s/base.apk` 会让 stamp 恒为 0，
> 监听看起来"武装了"但永远看不到事件。

### 武装条件：只看 CE 解锁
CE（Credential Encrypted）已解锁即武装，**不要求目标包在跑**。
- `spid_*` 存的是"当前 PID"，而界面组件不被调用时不进进程（=0）；
- 拿 `spid > 0` 当前提会导致监听永不武装。

DE 阶段直接跳过 —— 那时界面本来起不来，比对没有意义。

### 位置：`auto_fix` 门禁之前
监听块只**记录事件 + 重置计数**，不做任何修复动作，所以放在 `auto_fix` 门禁之前：
诊断模式（`auto_fix=0`）下同样维护基线，否则开关一打开基线是空的，
第一次真实安装会被当成"没变"。

### 事件到达后做什么
```
INSTALL_EVENT pkg=... prev=... cur=... -> 重置判定
  → crash_hist_reset()   旧崩账清零
  → sr_ts = 0            打断旧观察期
  → 写入新 stamp 基线
```

### 验证
```bash
# 1. 基线是否建立（首次武装时打一次）
grep WATCH_SEED /data/adb/camellia/state/rescue.log
cat /data/adb/camellia/state/wstamp_com.android.systemui

# 2. 伪造旧基线，下一个 tick 应检测到"变更"
echo 111 > /data/adb/camellia/state/wstamp_com.android.systemui
sleep 40
grep INSTALL_EVENT /data/adb/camellia/state/rescue.log
```

### 踩过的三个坑（按发现顺序）
1. **`auto_fix=0` 早退** —— 监听在门禁之后，永远不跑。已前移。
2. **`pkg_query` 返回值写反** —— 它返回 1=找到，写成 `!= 0` 就短路 return 0。
3. **系统应用 apk 名不是 `base.apk`** —— 见上表。

---

## 10. 坏包快照（自动落盘 + 手动查看）

### 自动快照
修复链每动一次手之前都会先备份，`ui_rescue_chain` 里有两处调用点：
- `before_uninstall_update` —— 卸系统更新前
- `before_install_apk` —— 装用户指定 APK 前

备份内容：坏包的 `base.apk` + `meta`（首行 `pkg=...`）。
无覆盖层时打 `SNAP_SKIP`（系统分区版本无从备份，也没什么可备份的）。
每包保留份数由 `bak_keep` 控制（默认 2）。

### 手动查看
```bash
/dev/*/.x/camd --snap              # 列出全部快照
/dev/*/.x/camd --snap com.android.systemui   # 只看某个包
```

输出形如：
```
com.test.rescue@3595    pkg=com.test.rescue why=...
共 2 份快照，目录 /data/adb/camellia/bak
```

---

## 11. 16 KB 页对齐：谁真正在对齐

### 结论
**apksigner（build-tools ≥ 35）签名时就会重新对齐**，`--lib-page-alignment 16384`
（36 起为默认值）会把所有**未压缩** `.so` 的 data offset 摆到 16 KB 边界上。
所以「只签名、不跑 zipalign」的产物也是对齐的 —— 实测：

| 输入 | 处理 | `.so` data offset | %16384 |
|---|---|---|---|
| `unsigned.apk` | 仅 apksigner `--lib-page-alignment 16384` | 1540096 | **0 ✅** |

zipalign 那一步仍然保留，作用变成「签名前先自检」，不是必需项。

### 校验必须读 **local header**
`zipfile` 的 `ZipInfo.extra` 是 **central directory** 的 extra，
它的长度和 local header 的 extra 不一定相同 —— 用它算 data offset 会得出错误结论
（本项目就因此误报过一次 "FAIL"，实际早已对齐）。

正确算法（读文件本身 30 字节 local header）：
```python
f.seek(i.header_offset); h = f.read(30)
_,_,_,meth,_,_,_,_,_,fn,ex = struct.unpack('<IHHHHHIIIHH', h)
data_off = i.header_offset + 30 + fn + ex      # 这才是真实 data 起点
assert meth == 0                                # 必须是 STORED，压缩过就不谈对齐
```

### 工具链注意
- build-tools **34 的 zipalign 是 aarch64 原生**，可直接执行；
- build-tools **36 的 zipalign 是 x86-64**，必须经 `qemu-x86_64 -L /opt/x86_64-sysroot` 包装，
  直接执行会报 `No such file or directory`（缺 x86-64 动态链接器），不是文件不存在。

---

## 12. 归因包不可行动时必须降级（2026-11-03 实弹暴露）

完整记录见 `docs/实弹记录-20261103-系统界面坏包.md`，日志 `docs/logs/rescue-20261103.log`。

### 现象

SystemUI 覆盖层坏包（ROM 原版 APK 装进 `/data/app`）崩溃循环，一级计数 `pid=3 thr=3`
在 `up=249` 就已达标，但实际动手在 `up=400` —— **211 秒 / 8 次崩溃**。

中间 5 个 tick（249/279/309/339/369）全部是同一形态：

```
TICK_ATTR plugin why=dropbox:system_app_crash@…(cnt=2)
TICK_NO_OVL attr=plugin (目标包无 /data/app 覆盖层，无可删物)
```

### 原因

MIUI SystemUI 插件化 → 崩溃堆栈含 `miui.systemui.plugin` 帧 → `culprit_attr()` 归到 plugin；
而 plugin 无覆盖层（坏的是 SystemUI 的覆盖层）。定性分支遇到「归因包无可删物」只是
`continue` 后整轮放弃，**不 fallback 到有覆盖层的候选**。

最终救回靠 `only-sysui-overlay(lu=0)` 退化判据（旧 dropbox 滑出 60 s 窗口 → 归因落空 →
按「只有 sysui 有覆盖层」认定）。**这是巧合，配置里多一个有覆盖层的包就会失效。**

### 规则（待实现）

> **归因给出包 A，但 A 没有可删物 ⇒ 归因不可行动，视为无效归因，降级处理。**
> - 全候选中有覆盖层者恰 1 个 → 直接修它，日志 `TICK_ATTR_INVALID attr=<x> -> fallback pkg=<y>`
> - ≥2 个 → 走 `ATTR_BOTH` 双修（处理完统一软重启一次）
> - 0 个 → 保持现状 `TICK_NO_OVL`，本轮不动（确实无可删物）

原则不变：能定性的仍只动被认定的那个包，仅在「不可行动」时降级。

### 已验证正确的部分（不要动）

`BAK_SAVE → FIX_DONE → SOFT_REBOOT(killall system_server) → INSTALL_EVENT 回落` 全链路
在 SystemUI 本体上首次实弹通过：`sr_count=1` 未触熔断，`pm path` 回落
`/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk`。**问题只在时机，不在动作。**

---

## 14. 实现与实证（v2.29.3 / vc120）

### 14.1 实现

`culprit_attr()` 四级瀑布：

1. `dropbox_window(since)` 文本归因（窗口内，硬证据规则）
2. `attr_plugin_state()` 插件加载期标记
3. 覆盖层存在性（`only-sysui-overlay` / `only-plugin-overlay` / `both-*`）
4. `ATTR_BOTH`

并引入 `attr_actionable(attr)` 门禁：归因指向的包在配置里存在、但当前没有
`/data/app` 覆盖层 ⇒ 视为**不可行动**，不再让调用方整轮放弃。

### 14.2 同时修掉的真凶：库名归因

`libMiMainColor.so` / `libmglnative.so` 这两个库 **SystemUI 也从 `/system_ext/lib64`
加载**，按库名判 plugin 必然误判。旧规则 4 因此把这批 SystemUI 崩溃全判成 plugin，
而 plugin 当时没有覆盖层 → `TICK_NO_OVL` 空转。

现规则 4 改为「库出现在组件覆盖层目录里才算组件的」：

- `/miui.systemui.plugin-` 或 `miui.systemui.plugin/lib/` → `ATTR_PLUGIN`
- `/com.android.systemui-`（覆盖层目录名，包名+随机后缀，最硬的归属证据）→ `ATTR_SYSUI`

比「正文出现 `com.android.systemui` 字样」更强：后者在 plugin 自己的代码里也大量存在。

### 14.3 逐 tick 实证（`实弹记录-20261103-系统界面坏包`）

`rescue.log` 原文（该次事故 up=249~400）：

| up | 日志 | 结果 |
|---|---|---|
| 249 | `TICK_ATTR plugin why=dropbox:...@1793668268862.txt(cnt=2)` | `TICK_NO_OVL` 空转 1 |
| 279 | 同上 | 空转 2 |
| 309 | 同上 | 空转 3 |
| 339 | 同上 | 空转 4 |
| 369 | 同上 | 空转 5 |
| 399 | `TICK_ATTR sysui why=only-sysui-overlay` | **TICK_ACT → FIX_DONE** |
| 400 | `SOFT_REBOOT sr_count=1` | 回落 ROM |

**211 秒 / 5 轮空转**。按 v2.29.3 规则，up=249 首轮即命中：规则 1–3 未命中 →
规则 4 无覆盖层目录路径 → 规则 6 `com.android.systemui` → `ATTR_SYSUI` →
`attr_actionable(SYSUI)=1` → `TICK_ACT`。**预计节省约 150 秒（5 轮 × 30 s）。**

### 14.4 `lastUpdateTime` 在 Android 13+ 不可用

`/data/system/packages.xml` 已改为 ABX 二进制，且**不再写入 `lastUpdateTime`**
（实测 `grep -c lastUpdateTime` 命中 0）。`pkginfo.last_update` 恒 0，令 both-overlay
时「谁更新」的判断失效（两个都当 0 → `>=` 恒选 sysui，属碰巧可接受但非本意）。

改用覆盖层版本目录的 `st_mtime`（`ovl_stamp()`）替代：安装/更新都会重写该目录时间，
语义接近且永远可取。实测 `only-plugin-overlay(lu=1793670245)` 已非 0。

### 14.5 自检基线

`T10.1`–`T10.5` 全 PASS：

- T10.1 sysui 覆盖层路径（硬证据）→ sysui
- T10.2 plugin 覆盖层路径（硬证据）→ plugin
- T10.3 只凭库名 → **不得**判 plugin → sysui
- T10.4 plugin 类名特征（保留）→ plugin
- T10.5 sysui 兜底 → sysui

### 14.6 现场注意

`miui.systemui.plugin` 在 up=2231 被**系统自动更新**（54 MB，`INSTALL_EVENT` 记录
versionCode 变化），不是模块行为。若新包本身有问题，归因链会正常指向 plugin
（堆栈含 plugin 类 → `ATTR_PLUGIN` → 有覆盖层 → 删）。无需人工干预。

---

## 15. dropbox ground truth 回归（v2.29.3 上线前）

样本：`/sdcard/camellia-diag-20261103-092839.tar.gz` 内 13 条 `system_app_crash@*`。
入口：`camd --attr-file <crash.txt>`（只读、无副作用，纯文本归因）。

### 结果

| # | 文件 | Package versionName | 堆栈特征 | 判定 |
|---|---|---|---|---|
| 1–7 | `@1793661*` / `@1793662*` | **102602260** | `miui.systemui.controlcenter.*`，`Resources$NotFoundException: qs_customize_button.xml` | **PLUGIN** |
| 8–13 | `@1793668*` | **202602260** | `miuix.mipalette.MiPalette.<clinit>` + `com.android.systemui.statusbar.*`，`UnsatisfiedLinkError: libMiMainColor.so` | **SYSUI** |

**13/13 与原文事实一致，零误判。**

### 关键发现：`Package:` 行的 versionName 是硬判据

两批同为 `Process: com.android.systemui`，但 `Package:` 行不同：

- 覆盖层注入后 versionName = `102602260`（`1026` = 覆盖层 versionCode，拼进 versionName）
- ROM 版 = `202602260`

这条证据比堆栈类名更硬：它直接说明「当前跑的 SystemUI 是不是被覆盖层替换过的那个」，
且不受代码混淆、retrace 行号影响。**建议后续作为 `ATTR_*` 的一级硬证据之一**（当前未用，
仅靠堆栈 dex 归属也已全对）。

### 为什么早期批次判 PLUGIN 而非 SYSUI

崩溃堆栈是 `miui.systemui.controlcenter.*`——**不是** `miui.systemui.plugin.*`。
静态前缀表里 `miui.systemui.` 是两包共用的，旧静态规则会判 SYSUI（错）。
本轮靠 Route B 的 dex class_defs 扫描，确认这些类只在 plugin 的 dex 里，
才正确判 PLUGIN。**这验证了 Route B 的必要性：静态前缀无法区分共用命名空间。**

### 补充：T8c「归因命中 13/18」的真相（非判据缺陷）

`crash_hist` 记录 18 条历史 pid/时间，但其中 5 条对应的
`system_app_crash@*.txt` 已被系统轮转清理（当前 `/data/system/dropbox` 内
`system_app_crash` 计数为 **0**，只剩 `SYSTEM_AUDIT`）。文本不存在，自然无法归因。

VERIFY 判「还在崩」用的是 `crash_hist` 计数（T8b 基线比较），**不依赖归因命中数**，
所以这 5 条缺口不影响决策正确性。自检里那句「若 VERIFY 用命中数会漏判」是防御性提示，
当前实现未走该路径。

**约束**：若将来把 VERIFY 改成依赖归因命中，必须同时处理 dropbox 轮转
（以 `crash_hist` 为计数真源）。否则会在旧文件被清理后误判为「没在崩」。

### 部署校验（v2.29.3 / vc120 最终态）

| 项 | 值 |
|---|---|
| APK 内 `assets/camd.bin` | `7f5f1ba053edb19c1e842774443e1afa` |
| DE `/data/adb/camellia/camd` | `db36dce731a06dc22bef767e1aa2f50e`（673424 B） |
| CE `/data/data/com.hyper.Camellia/files/camd` | 同上（一致） |
| 源码产物 `.camd-tmp/camd` | 同上（一致） |
| `--self-test` | **SELF-TEST PASS**（T10.1–T10.5、T11 全通过） |
