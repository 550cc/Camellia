# Camellia SystemUI 急救 v5：收敛决议与实物级手术

> 本文不是第四套平行方案。现存 v2/v3/v4 三份文档 + 两套实现，v5 的职责是**定唯一执行者、定唯一根因模型、给出可直接落地的增量**。

---

## 0. 三线现状与冲突

| 线 | 载体 | 归因方式 | 处置 | 致命短板 |
|---|---|---|---|---|
| **v2.19 Java 阶梯** | `SysRescueGuard.java`（1976 行，跑在 system_server 内） | 崩溃次数 + 崩溃类型 | L1 卸更新 → L2 装本包 → L3 装对端包 → L4 清数据 | ① **无 CAP_SYS_ADMIN，做不了挂载手术**；② `none` 被误判失败而升阶 |
| **v3 守护** | `tools/sysui_rescue_guard.sh`（PR1 已实现） | 等 Java 下发 `VERDICT` | S0–S4 | **至今没有任何人给它下 VERDICT**，只会 BOOT 超时/PID_FLAP 时以 S1 兜底 |
| **v4 文档** | `docs/sysui_rescue_v4_design.md` | 来源快照 diff | A 卸更新 / B `disable` 模块 + **重启** / C LSPosed 安全模式 | ① B 类必须重启；② 无基线快照时不下手 |

**冲突点只有三条**：

1. 谁做最终决策 —— Java（v2.19）还是 root 侧（v3/v4）
2. 模块挂载型故障是否必须重启 —— v4 说必须，实测说有更轻的路（§1.3）
3. Java 侧该留多少 —— 三版答案各不相同

---

## 1. 实测事实（本轮重新逆向，v5 的物理基础）

### 1.1 能力事实：system_server 根本做不了挂载手术

```
system_server
  CapEff = 0000001a06887c20   →  bit21 CAP_SYS_ADMIN = 0
  CapBnd = 0000000000000000   →  bounding set 为空，连提权都无门
```

对比本机 `su`/shell 的 `CapEff=000001ffffffffff`（全能力）。

**结论（硬约束）**：`umount` / `mount` / `nsenter` 一律不能在 system_server 内做，必须走 root 桥（camd，uid=0，init ns）。Java 侧只能做三件事：取证、决策、下单。

### 1.2 命名空间事实：四个进程四个 ns

| 进程 | mount ns | `/system_ext` 传播属性 |
|---|---|---|
| init (1) | `mnt:[4026532909]` | **`shared:10`**（源） |
| zygote64 | `mnt:[4026536276]` | master:10 |
| system_server | `mnt:[4026536279]` | master:10 |
| com.android.systemui | `mnt:[4026536553]` | master:10 |

- 四者互不相同：在任一处 `umount` 不会自动作用于其他 ns（除非靠传播）。
- `/system_ext` 是单层 erofs（`253:4 /dev/block/dm-4`），模块叠加层都挂在这个 shared 挂载之下。

### 1.3 传播事实（v4 未利用的关键通道）

`/system_ext` 在 init ns 是 `shared:10`，三个子 ns 全是 `master:10` —— 同一 peer group 的从属。

Linux 语义：在 peer group 的 shared 副本上 `umount`，事件会传播给所有 master 从属。模块在 `/system_ext/priv-app/...` 下 `bind mount` 时，父挂载是 shared，新挂载会继承 shared 属性并加入同一 peer group。

**推论**：在 init ns 执行一次 `umount -l <target>`，会立即传播到运行中的 SystemUI 的 ns，不必逐 ns `nsenter`、不必重启。
→ 这就是 v4「B 类必须重启」可以被降级的原因。**但必须实测确认**（§7 V1）。

### 1.4 出厂基线指纹（探测锚点）

```
/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk
  size = 52,019,850 B
  md5  = 3ff26776…（出厂原版）
  stat 指纹 = dev:inode:size（毫秒级，用于高频巡检）
```

`lib/arm64/` 下 5 个 so 全是指向 `/system_ext/lib64/` 的符号链接（共享库池）。

**救援意义**：搬动/替换 apk 会让 so 依赖断链（软链指向共享池）；M0 撤挂载只撤叠加层，不破坏软链结构，比「disable 模块 + 重启」更安全。

### 1.5 桥事实

`tools/camellia_bridge.c` 白名单（`ALLOWED[]`）**已含**：
`pm cmd am cp mkdir chmod chown rm mv ln ls cat id sh getprop setprop dumpsys sync kill pkill df mount umount magisk settings echo sleep test stat head tail grep sed tar unzip dd log wm svc appops content input screencap true find sha256sum md5sum cksum blockdev restorecon`

**缺**：`nsenter`、`killall`、`restart`。

→ **shared 传播路线（M0a）零桥改造**；逐 ns 路线（M0b）需扩白名单。

---

## 2. 根因模型：按「故障实物位置」分类（唯一模型）

取代 v3 的 Verdict 七分法与 v4 的三类 —— 不是它们错，是它们没有和「能做什么手术」对齐。

| ID | 类别 | 判定证据（探测优先，栈兜底） | 手术 | 可逆 | 需重启 |
|---|---|---|---|---|---|
| **F0** | `PKG_UPDATE` | `pm path` 落在 `/data/app` 且系统分区有原件 | L1 卸更新 | ✅ 保备份包 | ❌ |
| **F1** | `PKG_ZOMBIE` | `/data/app` 完整安装包（非更新层） | 卸更新/清僵尸 | ✅ 可再装 | ❌ |
| **F2** | `PKG_THIRD` | 系统分区无原件（纯三方） | 卸包 | ✅ 可再装 | ❌ |
| **F3** | `MOUNT_OVERLAY` | mountinfo 多出非原生层，**或** stat/md5 指纹 ≠ 基线 | **M0a 运行时 umount** | ✅ 完全可逆 | 视情况 |
| **F4** | `MOUNT_BIND_PRIVATE` | 同 F3 但传播不通 | M0b 逐 ns → 仍不行则 disable+重启 | ✅ | ✅ |
| **F5** | `HOOK_SELF` | 栈含 `com.hyper.Camellia` 帧 / 安全模式已生效 | H0 bypass 或不动手 | ✅ | ❌ |
| **F6** | `NATIVE_LIB` | `UnsatisfiedLinkError` / `dlopen` 失败 | 先 M0（若是挂载型）→ 否则 L1 | 视手术 | 视手术 |
| **F7** | `DATA_BAD` | 崩在 datastore/prefs/XML 解析 | L4 清数据（须用户开启） | ❌ 用户数据 | ❌ |
| **F8** | `MEMORY` | exit-info `REASON_LOW_MEMORY` / `OOM_KILL` | 只 kill，**绝不动手术** | ✅ | ❌ |
| **F9** | `UNKNOWN` | 无证据 | 只 kill 一次 → 熔断 | ✅ | ❌ |

### 2.1 F3 是否需要重启？

- 撤挂载后，后续 `open()` 立刻看到原厂文件（对 StartService/插件加载型有效）。
- 但 SystemUI 主进程启动时已 `mmap` 了 apk/dex，已映射的页不会变 → 若故障是「坏 apk 已加载」，必须 kill 让 persistent 重新拉起（新进程从 zygote 的干净 ns fork）。
- **判定**：M0 后先做指纹复核 + 健康探测（`pidof` + `dumpsys window` 里 StatusBar/NotificationShade 窗口存在），仅健康未恢复时才 kill。

### 2.2 判定顺序（短路求值）

```
故障事件（崩溃 / 失活 / PID_FLAP）
 │
 ├(1) 时间否决窗？开机 <5min / 模块升级后首次 / 自伤 self_action 未过期
 │      → F9 只记录
 ├(2) 栈含 Camellia 帧 或 安全模式已生效
 │      → F5 H0（不因「栈里出现模块名」就禁用整个模块）
 ├(3) exit-info 指向内存
 │      → F8 只 kill
 ├(4) 目标路径存在叠加层 或 指纹≠基线
 │      → F3 M0a（传播复核）→ 不通转 F4 M0b
 ├(5) pmPath 在 /data/app
 │      → 系统分区有原件 ? F0 L1 : F2 卸包
 ├(6) 崩在 native / dlopen
 │      → F6（先试 M0，再 L1）
 ├(7) 崩在 datastore/prefs 解析
 │      → F7 L4（需开关）
 └(8) 其他
        → F9 只 kill，一次，然后熔断
```

关键改动：**探测 (4) 优先于包形态 (5)**。因为挂载替换时 `pm path` / `versionCode` / `flags` 全都不会变（本机已实测），只靠包管理器永远认不出 F3。

---

## 3. 处置级别重排

动作顺序由「侵入度」决定，不由「次数」决定。

| 级别 | 动作 | 侵入度 | 进预算 | 备注 |
|---|---|---|---|---|
| **M0a** | init ns `umount -l <target>` + 复核 | 极低（完全可逆） | ❌ | F3 首选；同目标同开机最多 2 次 |
| **M0b** | 逐 ns `nsenter -m -t <pid> umount -l` | 低 | ❌ | 需扩白名单 `nsenter` |
| **H0** | 自身 bypass（Settings 标志 + 杀进程），**不改 LSPosed 配置** | 低 | ❌ | F5；用户可手动解除 |
| **L1** | 卸更新 / 回滚系统分区版 / 卸纯三方包 | 中（有备份） | ✅ | F0/F1/F2 |
| **L2** | 装本目标包 | 中 | ✅ | 缺包顺延 |
| **L3** | 装对端包（系统界面 ↔ 组件互兜底） | 中 | ✅ | 缺包顺延 |
| **L4** | 清数据重建 | 高（用户数据） | ✅ | **默认关闭** |
| **HALT** | 熔断：停止一切动作 + 通知 + 留现场 | — | — | 需人工解除 |

**M0/H0 不进预算**：它们是「降低侵入」而非「改变系统」，计入预算会让真正的救援动作被提前熔断。

### 3.1 三态结果（修 P0 洞）

当前 bug（确切位置）：

- `SysRescueGuard.java:981` → `return "uninstall_update none(已在系统分区 …)"`
- 注释（978 行）：「必须如实报 none 让阶梯不升」
- `ok()`（1454 行）：只匹配失败词与 `success|system-updates|exit=0`，**没有 none 分支**
- → 该串判为 `false` → `Math.min(lvl+1, LADDER_MAX)` 升阶

注释与实现直接矛盾。修法：

```java
enum R { GOOD, NOOP, FAIL }

static R classify(String out) {
    if (out == null) return R.FAIL;
    String s = out.toLowerCase();
    if (s.contains("none(") || s.contains(" noop")) return R.NOOP;   // 先于失败词判断
    if (s.contains("error") || s.contains("exception") || s.contains("denied")
        || s.contains("not found") || s.contains("fail")
        || s.contains("couldn't") || s.contains("could not")
        || s.contains("cannot") || s.contains("not allowed")
        || s.contains("not installed") || s.contains("unknown package")) return R.FAIL;
    if (s.contains("success") || s.contains("system-updates") || s.contains("exit=0")) return R.GOOD;
    return R.FAIL;
}
```

三态语义：

| 结果 | 含义 | 阶梯 | 预算 | 状态机 |
|---|---|---|---|---|
| `GOOD` | 本级生效 | 停在 lvl，进观察窗 | 消耗 1 | `ST_ACTED` |
| `NOOP` | **本级对此故障不适用** | **不升阶**，记 `clearedLvl=lvl`，下轮从 lvl+1 起 | **不消耗** | `ST_WATCH` |
| `FAIL` | 本级执行失败 | 升到 `min(lvl+1, MAX)` | 消耗 1 | `ST_ACTED` |

`NOOP` 不升阶但必须「跳过」：否则每轮都在 L1 空转。用 `clearedLvl` 记录，`execLevel(from = max(from, clearedLvl+1))`。

---

## 4. M0 撤挂载：具体命令

### 4.1 M0a（靠 shared 传播，零桥改造）

camd 跑在 init ns，它执行 `umount` 就作用在 `shared:10` 源上。

```sh
BASE=/system_ext/priv-app/MiuiSystemUI
# ① 判定是否真有叠加层：与基线指纹比对
stat -c '%d:%i:%s' $BASE/MiuiSystemUI.apk      # 期望 64772:<inode>:52019850
# ② 撤层（先文件级，再目录级；-l 懒卸载避免 busy）
umount -l $BASE/MiuiSystemUI.apk 2>/dev/null
umount -l $BASE 2>/dev/null
# ③ 指纹复核：是否回出厂
sha256sum $BASE/MiuiSystemUI.apk
# ④ 仅在健康未恢复时 kill（persistent 会自动拉起）
kill -9 $(pidof com.android.systemui)
```

第一版只做 `②+④`：`umount` 与 `sha256sum` 都已在白名单，**零桥改造可上线**。
`③` 若要从 SystemUI 自身视角复核，需 `nsenter -t <pid> -m --`，当前白名单缺。

### 4.2 M0b（传播不通时，逐 ns）

```sh
TARGET=/system_ext/priv-app/MiuiSystemUI
for p in $(pidof zygote64) $(pidof system_server) $(pidof com.android.systemui); do
  nsenter -t $p -m -- umount -l $TARGET 2>/dev/null
  nsenter -t $p -m -- umount -l $TARGET/MiuiSystemUI.apk 2>/dev/null
done
```

需扩白名单 `nsenter`、`killall`，并为 `nsenter` 增加**参数形态校验**（只允许 `-t <pid> -m -- umount …`），避免白名单成为任意命令跳板。

### 4.3 可逆性

`umount` 完全可逆：重新 `mount` 或让模块重新生效即可。相比 v4 的「写 `disable` 文件 + 重启」，M0 不改任何磁盘状态、不重启、失败可原样恢复 —— 这是它该排在 L1 之前的原因。

---

## 5. 决策权归属（收敛结论）

| 职责 | 归属 | 理由 |
|---|---|---|
| 崩溃取证 | **Java**（`SysUiWatch` 保留） | 只有 system_server 能读 dropbox / AppErrors / exit-info |
| F 类探测 | **Java 下单，root 读** | Java 无 CAP_SYS_ADMIN，拿不到挂载与指纹真相；`stat`/`sha256sum` 走桥 |
| 手术执行 | **root 侧（camd + 守护）** | §1.1 能力 + system_server 会崩、LSPosed 可能停 Java |
| 熔断/预算 | **root 侧权威**，Java 侧只镜像给 UI | root 侧不会被 Java 崩掉影响 |
| 物理旁路（音量键） | **root 侧** | 黑屏时设置页不可达 |

**Java 侧边界（v5 明确）**：

- 保留：崩溃取证、F 类探测下单、UI 镜像、手动入口转发
- **移除**：Java 自己调 `pm` 卸更新/装包 —— 改为下单给守护执行
- 保留 gating：桥不可用 → 只 kill + 记录（当前行为正确，不要退化成「无桥硬干」）

**为什么不能全交给 Java**：execLevel 里 `runViaRootBridge` 已是唯一出口（1480 行注释写明 system_server 的 SELinux 域不允许 fork 执行 cmd/pm/su，实测 error=13）。说明 v2.19 早就发现了能力边界，只是没把决策权一起搬走。

---

## 6. 熔断与预算（沿用 v4，去重）

| 条件 | 动作 |
|---|---|
| 同一 F 类 30 min 内 L 级动作 ≥ 3 次 | 进 HALT |
| 24 h 内 L 级动作总数 ≥ 5 次 | 进 HALT |
| 同一目标同一开机 M0 次数 ≥ 2 | 该目标 M0 静默，转 L1 |
| HALT 期间 | 只记录 + 通知 + 留现场，不动手 |
| 解除 | 仅用户手动（设置页 `fuse-reset`） |

自伤否决优先于一切：`self_action` 未过期 / 开机 < 5 min / 模块升级后首次 → 只记录。

---

## 7. 落地顺序（4 批，每批独立可验证）

| 批次 | 内容 | 风险 | 验证方式 |
|---|---|---|---|
| **B1** | 三态 `classify()` 替换 `ok()` | 零（纯判定） | 构造「已在系统分区」场景，日志应出现 `L1 noop` 且 `inc.level` 不增 |
| **B2** | F 类探测器（只读） | 零 | 设置页显示「当前形态：系统分区/更新层/被挂载/指纹是否=基线」 |
| **B3** | M0a 上线（`umount` + `sha256sum` 复核） | 中（需实机） | 用测试模块挂假文件到 SystemUI 目录 → 触发 → 验证 umount 后 sha256 回出厂、SystemUI 起来 |
| **B4** | HALT 预算 + 设置页手动按钮 + UI 镜像 | 低 | 连续触发观察 HALT 是否生效 |

B1 可以立刻做，与 B2-B4 无依赖。

---

## 8. 必须实测的 4 个点（不验证不许上自动流程）

| 编号 | 待验证 | 方法 | 若不成立的后果 |
|---|---|---|---|
| **V1** | shared:10 传播是否真能把 init ns 的 umount 传到 SystemUI ns | 用一个无害 `bind mount` 到 `/system_ext/priv-app/MiuiSystemUI/` 下放测试文件，init ns umount，读 SystemUI ns 是否消失 | 退化为 M0b 逐 ns，需扩白名单 |
| **V2** | camd 能否 `nsenter`（当前白名单缺） | 扩白名单后 `nsenter -t <pid> -m -- true` | M0b 不可用，只能靠传播 |
| **V3** | umount 后 SystemUI 已 mmap 的 apk 是否必须重启 | **先用「对端组件」做实验体**（/data/app 的三方 SystemUI 类应用），不拿真 SystemUI 试 | 若不需重启，可省一次进程重启 |
| **V4** | M0 之后能否 `mount` 回去（可逆性） | 测试挂载 → umount → 重新 mount，比对指纹 | 若不可逆，M0 降级为「需用户确认」 |

V3 的实验体选择很重要：**不要拿真 SystemUI 做首次实验**，黑屏代价太高。用对端组件包（`PKG_PLUGIN`，装在 `/data/app`）当小白鼠。

---

## 9. 与现有文档的关系（谁作废、谁保留）

| 文档 | 处置 |
|---|---|
| `sysui_rescue_v2_design.md` | 归档，不再执行 |
| `sysui_rescue_v3_design.md` | **归因树（§6）与自伤记账（§7.2）被本文 §2.2 吸收**；守护脚本 S0–S4 骨架保留，动作表按 §3 重排 |
| `sysui_rescue_v4_design.md` | A 类（卸更新）、C 类（LSPosed 安全模式）、物理旁路、变更台账 **全部保留**；B 类升级为「M0 优先，disable+重启 fallback」 |
| v2.19 Java 阶梯 | L1–L4 保留为 L 级；新增 M0/H0 前置；`ok()` 改三态 |
| `系统急救机制分析.md` | 证据库，继续追加 |
