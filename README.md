# 小米互传 → MT 管理器（Xposed / LSPosed 模块）

把「小米互传接收文件后，通知栏点『打开』」的落点改成 **MT 管理器**。

| 文件类型 | 走向 |
|---|---|
| 文档 / 音频 / 未知类型 | **MT 管理器**（互传目录） |
| 图片 / 视频 | 相册 / 播放器（保持原样） |
| 联系人（vcf） | 通讯录（保持原样） |

- 包名：`com.yuyu.misharemt2`
- 作用域：`com.miui.mishare.connectivity`（小米互传）
- 兼容：LSPosed API 100+（`minApiVersion=100 / targetApiVersion=102`）

---

## 为什么能适配未来版本（核心设计）

互传的**逻辑类是混淆的**，类名/方法名每个版本都会变（如分流方法 5.1.12 是 `e.j`、5.3.4 是 `e.l`、5.4.2 是 `e.q`）。所以要靠**稳定的东西**定位：

1. **框架类型永不混淆**：`android.content.Context`、`android.content.Intent`
2. **数据模型类字段稳定**：`com.miui.mishare.file.FileInfo` 的
   `mimeType / directory / path / name`

模块运行时的定位规则：

> 在 APK 的 `classes*.dex` 中查找【返回 `android.content.Intent`】
> 且【参数里含 `FileInfo[]`】的静态方法，取**参数最多**的那个
> —— 它就是「文件分流中枢」（通知构建与点击回调都走它）。

本模块自带一个**极简 dex 解析器**（只读 string/type/proto/method 四张表），
不依赖任何被隐藏的 API，因此**互传升级后无需重新打包模块**。

Hook 到中枢方法后，按文件类型决定：
命中 image/video/vcard → 放行原逻辑；否则 → 返回 MT 管理器 Intent
（`bin.mt.plus.ACTION_SHORTCUT` + `bin.mt.plus.ShortcutActivity`，
`path` = 互传目录，`operation=goto`，`flags=0x34000000`）。

---

## 构建

不使用 Gradle，直接用 `javac + d8 + aapt2 + apksigner`：

```bash
bash build.sh
# 产物: build/com.yuyu.misharemt2-v1.0.apk  （并复制到项目根目录）
```

## 项目结构

```
├── AndroidManifest.xml          # 模块清单
├── build.sh                     # 构建脚本
├── libs/api.jar                 # libxposed API 100
├── res/values/                  # strings.xml / arrays.xml(作用域)
├── src/com/yuyu/misharemt2/
│   ├── Main.java                # 模块入口 + dex 自适应定位 + Hook
│   └── SettingsActivity.java    # 说明页
├── xposed/
│   ├── module.prop              # LSPosed 元数据
│   ├── scope.list               # 作用域= com.miui.mishare.connectivity
│   └── java_init.list           # 入口类
└── _scan_check.py               # 定位算法离线验证脚本（开发用）
```

---

## 安装与使用

1. 安装 `com.yuyu.misharemt2-v1.0.apk`
2. 打开 **LSPosed 管理器** → 模块 → 启用「Camellia」
3. **作用域**勾选【小米互传】（`com.miui.mishare.connectivity`）
4. 强制停止小米互传，然后重新打开（或重启手机）

## 验证

```bash
# 查看模块日志（模块会打印定位到的真实方法）
adb logcat -s MiShareMT
# 期望看到类似：
#   hooked dispatcher => com.miui.mishare.view.e#q(Context, FileInfo[], ...)
```

真机验收：
- 接收 `.txt / .sh / .mp3` → 点通知「打开」→ 停在 MT 管理器互传目录
- 接收 `.jpg / .mp4 / .vcf` → 点通知 → 仍走相册 / 播放器 / 通讯录

## 已知坑：路径语义（v1.3 修复）

### 坑 1：MT 的 goto 只接受**目录**

`bin.mt.plus.ShortcutActivity` 的 `operation=goto` 把传入的 `path` 当**目录**：
传文件路径时，MT 会去找一个名为「文件名/」的目录，界面显示成 `…/file.json/`（实测三次都是如此）。
因此下发给 MT 的必须是**文件所在目录**。模块最后统一走 `toDirTarget()`：
是目录就用目录本身，是文件就换成其父目录。

### 坑 2：互传的 FileInfo 字段格式不统一

互传的 `FileInfo` 在不同文件类型下给出的东西**不一样**，实测踩到两个坑：

| 文件 | 字段表现 |
|---|---|
| `boot(1).img` | `directory = /storage/emulated/0/Download/MiShare/boot(1).img/`（**带尾斜杠，像目录**） |
| `HMA-OSS_config_...json` | `path = content://media/external_primary/file/149268`（**MediaStore URI，不是路径**） |

直接把这两种值交给 MT，就会分别跳进「不存在的空目录」和「content:// 目录」。

v1.2 起的解法：把所有来源统一解析成**文件系统路径**，再用「路径是否真实存在」定优先级。

```
FileInfo.path / FileInfo.directory
        |
        v
   realPath()  ->  真实 fs 路径  ->  new File(p).exists()? 采用
```

`realPath()` 的处理：

- `/...` 开头 -> 直接使用（去掉尾部 `/`）
- `file://...` -> 取 path
- `content://...` -> 依次尝试 `_data` 列 -> `RELATIVE_PATH + DISPLAY_NAME` -> `DocumentsContract.getDocumentId()`（形如 `primary:Download/MiShare/xxx`）

若以上都拿不到存在的路径，再退化为「已知互传目录 + `FileInfo.name`」拼接，仍失败才返回互传目录本身。

日志（`adb logcat -s MiShareMT`）会打印判定过程：

```
file[0] name=HMA-OSS_config_2026-03-24_00.45.34.json path=content://media/external_primary/file/149268 directory=...
uri _data -> /storage/emulated/0/Download/MiShare/HMA-OSS_config_2026-03-24_00.45.34.json
target <- exists: /storage/emulated/0/Download/MiShare/HMA-OSS_config_2026-03-24_00.45.34.json
-> MT Manager, path=/storage/emulated/0/Download/MiShare/HMA-OSS_config_2026-03-24_00.45.34.json
```

## 排查

| 现象 | 处理 |
|---|---|
| 日志出现 `dispatcher not found` | 定位失败，请把 `MiShareMT` 日志反馈 |
| 定位到了但行为没变 | 确认作用域已勾选且互传已重启 |
| 想保留某扩展名走原逻辑 | 修改 `Main.KEEP_EXTS` |

## 与旧方案的区别

`_analyze/` 里的 skill 是 **smali 直接改包 + 不签名** 的方案（每版都要重改重打包）；
本模块是 **运行时 Hook** 方案：免重打包、免签名问题、随版本自适应，
但需要设备已 Root / 已装 LSPosed。

---

## v1.9：修「4.x 上 hook 失效」

根因不是定位不到，而是**覆盖面**：

互传的中枢在「不是恰好一个文件」时（多文件接收、点「打开文件夹」）**不会自己拼 Intent**，
而是调用一个私有静态**目录构造点**返回「打开目录」的 Intent；
而模块的 `shouldRedirect()` 在 `FileInfo[]` 为 null / 长度 0 时保持原行为，
于是这条路径完全绕开了模块 —— 日志有命中、点通知还是系统文件管理器，4.x 上尤其容易撞到。

四版本调用关系（MT MCP `mt_apk_dex_xref` dispatch 精确确认，ix = 指令序号）：

| 版本 | 中枢（第一套 hook） | 目录构造点（第二套 hook） | 调用点 |
|---|---|---|---|
| 4.0.5 | `view.d#d` / `view.d#i` | `view.d#f(Context)` | ix 98 / 223 |
| 4.10.1 | `view.d#d` / `view.d#i` | `view.d#f(Context)` | 真机 status 实测 |
| 4.13.8 | `view.d#j` / `view.d#e` | `view.d#g(Context,Z,TapRecvData,Z)` | ix 377 / 120 |
| 5.3.4 | `view.e#l` / `view.e#f` | `view.e#h(...)` | 离线验证器 |
| 5.4.2 | `view.e#q` / `e#r` / `e#i` / `e#g` | `view.e#k(...)` | ix 383 / 128 |

### v1.9 改动

1. **新增第二套 hook**：`findDirAnchors()` ——
   DexKit：`returnType(Intent)` + `usingStrings("com.android.fileexplorer.tapShare.START_TRANSFER" | "get explorer intent!")`；
   兜底：解析 dex 的形状扫描（private static + 返回 Intent + 形状判据）。
   因 Xposed 对**嵌套调用**同样生效，内层 hook 会先返回 MT Intent，
   从外侧「绕过」中枢的 keep 策略 —— 这是「补挂一层就修好 4.x」的原理。
2. `anyContext()`：`onPackageLoaded` 阶段 `currentApplication()` 常为 null →
   退回 `ActivityThread.getSystemContext()`（修 `version=unknown` 与 MT 探测空转）。
3. 4.0.5/4.10.1 的 `FileInfo` **无 `directory` 字段** → `resolveDir()` 增加方法级兜底
   `getUri()` / `getContentUri(Context)`。
4. MT 入口枚举失败时用 `getActivityInfo(<pkg>.ShortcutActivity)` 显式确认。
5. status 新增 `dirAnchors=` / `mt=` 字段；`module.prop` 升到 v1.9 / versionCode 10。

### 验证结果

离线（`_scan_check.py` 中枢 + `_scan_explorer.py` 目录锚点，四版本全绿）：

| 版本 | 中枢候选（floor=90） | 目录锚点候选（形状过滤后） |
|---|---|---|
| 4.0.5 | `d#d`(117)、`d#i`(109) → 挂 2 | `d#f(Context)`（`d#h(Context,String)` 被排除） |
| 4.13.8 | `d#j`(125)、`d#e`(117) → 挂 2 | `d#g(...)`（`d#i(Context,String)` 被排除） |
| 5.3.4 | `e#l`(125)、`e#f`(117) → 挂 2 | `e#h(...)`（`e#j(Context,String)` 被排除） |
| 5.4.2 | `e#q`(133)、`e#r`(125)、`e#i`(117)、`e#g`(94) → 挂 4 | `e#k(...)`（`e#n(Context,String)`、`e#p(Context,MiShareTask)` 被排除） |

真机（`/sdcard/Android/media/com.yuyu.misharemt2/misharemt2.status`，与离线结果逐字一致）：

```
# 设备实装 4.10.1 时
version=4.10.1(41001)  via=dexkit
hooks=view.d#d(Context, FileInfo[], TapRecvData, boolean), view.d#i(Context, FileInfo[], TapRecvData)
dirAnchors=view.d#f(Context)          ← 4.x legacy 目录锚点已挂上
mt=bin.mt.plus.canary/bin.mt.plus.ShortcutActivity

# 设备实装 5.4.2 时（本次复测）
version=5.4.2_20260923_1315(50402)  via=dexkit
hooks=view.e#q(...MiShareTask, Z, Z), view.e#r(...Z, Z), view.e#i(...Z), view.e#g(...Z)
dirAnchors=view.e#k(Context, boolean, TapRecvData, boolean)
mt=bin.mt.plus.canary/bin.mt.plus.ShortcutActivity
```

**已知副作用**：多文件批次（含一次多张图片）现在也进 MT 目录视图；
单张图片/视频仍走中枢、仍进相册/播放器。不接受就让 `findDirAnchors()` 返回空。

---

## 小米画报「自选图片」目录同步（v2.0）

同一个 APK 里还带一套**小米画报**（`com.mfashiongallery.emag`）的 hook：把设置的目录
镜像进画报「自选图片」，并控制轮播顺序。两套 hook 目标应用不同，互不影响。

### 它做什么

| 设置项 | 说明 |
|---|---|
| 启用目录同步 | 关掉即完全不干预画报 |
| 同步目录 | 默认 `/storage/emulated/0/Pictures/小米画报/` |
| 最多同步图片数 | 只保留最新的 N 张（超出的自动从画报里删） |
| **加载顺序** | `顺序`（默认，最新在前）/ `倒序`（最早在前）/ `随机`（可「重新随机」） |
| 画报同步状态 | 结果、命中注入点、FileProvider、权限、增删计数、耗时 |

### 加载顺序是怎么生效的

画报查「自选图片」的 SQL **没有 ORDER BY** → 返回顺序就是 `_id`（rowid）升序，
也就是**我们插行的顺序**。所以：

- `顺序`：按「修改时间降序」插入（＝画报原本的表现，保持兼容）
- `倒序`：反过来插
- `随机`：先按路径归一，再用「文件集合派生值 ^ 种子」洗牌（同一批文件顺序稳定，
  点「重新随机」换种子 → 换顺序）
- 库里顺序与目标不一致时，把本模块管的行**删掉按目标顺序重插**（`_id` 自增，
  重插后 `_id` 升序＝目标顺序）；一致时**不写库**，所以不会反复抖

日志/状态里的 `order=` 与 `reorder=` 就是这套机制的可观测点，判读见
skill 文档 `WALLPAPER-SYNC.md`。

### 生效时机

改完设置会广播给画报进程并**立即同步一次**（画报没在运行就等它下次启动自己来拉）。
但画报页面里的列表是内存缓存，**重进一次「自选图片」页**才能看到新顺序。

### 已实测（真机）

```
sync(cfg-push) … order=desc  -> ok   +  reorder rows=10 order=desc (was 10 want 10)
sync(cfg-push) … order=random -> ok  +  reorder=0      ← 同 seed 重复同步不重排（幂等）
```

## 手机管家「安全服务」环境检测 Hook（v2.3，多进程状态修正 v2.4）

同一个 APK 里还有第三条业务线：**手机管家**（`com.miui.securitycenter`）里的安全服务
（XSOF，`com.xiaomi.security.xsof`）。安全服务给第三方 App 提供「设备可信 / 风险应用 /
模拟触控 / 反诈」四类环境检测，本模块在**安全服务进程**里挂 hook，可按组选择
「只观测」或「返回良性结果」。目标包不同、进程不同，与互传/画报两条线互不影响。

### 它做什么

| 设置项 | 说明 |
|---|---|
| 接管安全服务环境检测（总开关） | 关闭时手机管家的行为与本模块完全无关（**默认关闭**） |
| 设备可信校验 / 风险应用扫描 / 模拟触控检测 / 反诈·实人认证 | 每组独立：`原始行为`(默认) / `只观测` / `阻断并返回良性结果` |
| 字段名启发式拦截 | 兜底：键名含 `simulat` / `fake` / `mock` 的取数请求直接回良性值 |
| 检测状态 | 已接管 / 定位未完成 + 版本、执行器、进程、能力组入口、类型映射、模式、命中统计、注入点、定位备注 |

安全服务跑在 `com.miui.securitycenter:remote`，**改完设置不用重启手机也不用重启手机管家**：
下一次调用检测接口就按新配置执行（配置有 1.5s 缓存）。

### 怎么定位（不硬编码混淆名）

定位顺序：**定位缓存 → DexKit → 失败隔离（不改行为，只是没 hook 上）**。

| 锚点 | 13.6.3-260924.0.1 实测 |
|---|---|
| 服务实现 | `com.xiaomi.security.xsof.MiSafetyDetectService$MiSafetyDetectImpl`（`IMiSafetyDetectServer$Stub` 的实现） |
| 任务对象 | `co.a`（多版本稳定，未混淆） |
| 执行器 | `j` / `b` / `b`（一版一名，靠结构收敛） |
| 注入点 | 7 个 `xsof_*` 能力组入口 + `taskCtor` + `finish` + `exec:j/b/b` + `dispatch` + `secMgr` |

进程在 `PackageLoaded` 时实现类常常还没加载，会打一行 `定位失败：…`，随后
`Application#attach` 兜底重试并在 2 秒内 `install ok via=cache|dexkit`。**看最后一行即可。**

### 多进程：状态为什么会有三份（v2.4 修复）

`com.miui.securitycenter` 是多进程应用（`:ui` / `:remote` / `.cache` / `:bootaware` …），
LSPosed 会把模块注入它的**每一个**子进程。其中 `.cache` 之类的预加载子进程
classloader 是空的（实测 `DexPathList[[directory "."]]`），加载不到宿主自己的类，
定位必然失败——**这不代表 hook 不可用，它压根不是 XSOF 的承载进程**。

v2.4 起，模块在定位前先用两个公开 API 探针判断「本进程是否承载宿主代码」
（`ApplicationInfo#className` 能否被当前 classloader 解析 + classloader 能否找到
APK 根部的 `AndroidManifest.xml`），据此分成三份状态：

| 结论 | 落盘文件 | `skip=` |
|---|---|---|
| 承载宿主代码且定位成功 | `misharemt2.sc.status` | 0 |
| 承载宿主代码但定位失败 | `misharemt2.sc.err.status` | 0 |
| **不承载宿主代码**（与 hook 场景无关） | `misharemt2.sc.skip.status` | 1 |

设置页只在「非 skip」的两份里按 `time=` 取最新（旧的成功结论不会掩盖新的失败），
`skip` 只在没有任何非 skip 记录时兜底显示为「待运行」。写成功/跳过时会顺手删掉
过期的 `misharemt2.sc.err.status`。

> **踩过的坑**：`.cache` 进程的定位失败记录时间戳往往比 `:remote` 的成功记录更新，
> 早先「成功/失败按时间取最新」的规则会被它盖掉，界面于是误报
> 「定位未完成 / 待适配」——而其实 `:remote` 里 hook 早已接管、阻断也在生效。
> 判定「哪些进程该参与比较」比「谁更新」更重要。

### 已实测（真机）

```
install ok via=dexkit  hooks=[xsof_device_trust … dispatch secMgr] cost=238ms   ← 删掉缓存后的回退路径
install ok via=cache   hooks=[… 同上 …]                          cost=6ms     ← 后续进程走缓存

已阻断 XSOF 组=trust (xsof_device_trust) code=1005
已阻断 XSOF 组=risk  (xsof_risk_app)     code=0
已阻断 XSOF 组=touch (xsof_simulated_touch) code=0

# 状态文件（/sdcard/Android/media/com.hyper.Camellia/misharemt2.sc.status）
ready=1 via=cache cfg=sidecar skip=0
mode=trust=2,risk=2,touch=2,fraud=2 enabled=1
map=10=device_trust,11=risk_app,12=simulated_touch
stat=trust:entry=5,block=1,code=1005,cost=2ms risk:entry=5,block=1,code=0,cost=0ms …
rtBy=com.miui.securitycenter.remote
host=app-class:ok
```

界面改一组模式 → 侧车 `misharemt2.ini` 立即变 → 重新触发一次检测，该组立刻
从「阻断」变成「只观测」（实测 trust 的 `block` 不再增长），无需重启任何进程。

细节、锚点推导、跨进程状态合并规则与验收 SOP 见 skill 文档
`/sdcard/yuyu/mcp/skills/mishare-mt-xposed-module/SECURITY-CENTER-XSOF.md`。

---

## 手机管家「能力解锁」Hook（v2.5 起，v2.9 起严格对齐 HyperCeiler）

### 它做什么

手机管家里**功能开关的显隐**取决于一批「设备能力查询」：读系统属性
（`ro.vendor.*` / `persist.vendor.*`）、读厂商配置、按机型白名单判定。非对应机型上这些
查询返回 `false`，界面上的开关会**整条消失**（不是灰掉）。

本模块把这些查询的返回值改成「支持」，让被隐藏的开关重新出现。与上面的 XSOF 是
**两条独立业务线**：XSOF 改的是「安全检测」返回值（面向第三方 App），本线改的是
「设备能力」返回值（面向系统 UI）；各有开关与状态文件，互不影响。

`12 条规则 / 3 个页 / 4 个子组`，**默认全部关闭**（解锁改的是设备能力判定，由用户按需开启）。

### 菜单结构（严格对齐 HyperCeiler）

层级、命名、顺序逐条对齐 HyperCeiler 的 `libhook/rules/securitycenter/*`。HyperCeiler 的
手机管家主页是「应用管理 / 省电与电池 / 隐私保护 / 全局侧边栏 / 其他」5 页；本模块只保留
**在 13.6.3 上确实定位到 hook 点**的项，因此呈现 3 页（应用管理、隐私保护在本机没有可用
锚点，不显示空页），HyperCeiler 有而本机无对应项的条目一律不列——**不留自创项**。

| 页 | 子组 | 条数 | 条目（与 HyperCeiler 同名） |
|---|---|---|---|
| 省电与电池 | — | 3 | 补全充电与电池功能 / 解锁极速无线充电模式 / 解锁低温延长续航 |
| 全局侧边栏 | 视频工具箱 | 1 | 解锁动态画面补偿 |
| 全局侧边栏 | 游戏工具箱 | 4 | 解锁游戏加速狂暴模式 / 解锁准星辅助 / 移除自动连招黑名单 / 移除游戏模式 Toast 弹窗 |
| 其他 | — | 4 | 禁用体验优化 / 跳过倒计时限制 / 禁用对应用的自动检查 / 禁用 Root 检测 |

设置页层级为 **首页 → 手机管家 →（页）→（组）→ 条目**：页入口带 `开启数/总数` 计数，
页内按子组渲染并带「开启整组」开关；返回键与顶栏返回按钮逐级回退（页 → 手机管家 → 首页）。

> v2.9 相对 v2.6 的规则表：41 → 12 条。删掉的是 HyperCeiler 里**没有对应条目**的自创
> 解锁项（视频智能刷新率、游戏宏、HDR、陀螺仪、双核独显、旁路充电通知抑制……），
> 以及一条「全局侧边栏已选应用数量上限」的独立业务线（`Sidebar.java` 已整体移除）。
> 保留项**逐条改名对齐 HyperCeiler 文案**。

### 怎么定位（不硬编码混淆名）

三层，全部版本自适应：

```
① 字符串锚点        DexKit 批量反查「哪个方法引用了这个属性名 / pref 键」
   例: support_wild_boost / pref_gb_unsupport_macro_apps /
       persist.vendor.tx.speed.control / key_check_item_root
② dex 层形状判定    返回类型 + 参数描述符必须与规则声明一致
③ 未混淆回退        仅在无锚点可用时走「未混淆类名 + 方法名」，同样要过形状校验
```

> 注意：本线**不能**用「参数最多的方法」那套启发式——能力查询几乎都是零参
> `isSupportXxx()`，形状（返回 `Z`/`I`/`V`）才是有区分度的过滤器。定位结果按
> 版本 + 类名做了进程内缓存，二次进入手机管家不再重复扫描（真机 179 ms 全部完成）。

### 开关与状态

- 开关：`sc_ul_<id>=true`（如 `sc_ul_wild_boost`），写在模块设置偏好；手机管家进程读不到时
  回退侧车 `misharemt2.ini` 同名键，**1.5 s 缓存**，改完即时生效、无需重启任何进程。
- 状态：`/sdcard/Android/media/com.hyper.Camellia/misharemt2.ul.status`

```
ver=1
proc=com.miui.securitycenter
rules=12 hooked=12 miss=0 fail=0 off=0      ← off = 按配置关闭的条数；定位与挂 hook 照做
via=dexkit
cost=179
detail=wild_boost=1/1,gunsight=1/3,…,root_item=1/1   ← id=命中数/候选数
```

### 已实测（真机 13.6.3-260924.0.1，v2.9 / versionCode 20）

```
rules=12 hooked=12 miss=0 fail=0 off=0 via=dexkit cost=179ms
```

- 12/12 全命中，无 miss / fail；
- 手机管家页显示 3 个页入口且计数正确（`省电与电池（0/3）`、`全局侧边栏（5/5）`、`其他（4/4）`）；
- 页内子分组渲染正确（`视频工具箱` 1 项、`游戏工具箱（4/4）`、「开启整组」开关正常）；
- 三级导航（页 ↔ 手机管家 ↔ 首页）与顶栏标题/副标题联动正常。

细节、规则全表、动作语义与「版本核验」结论见 skill 文档
`/sdcard/yuyu/mcp/skills/mishare-mt-xposed-module/SECURITY-CENTER-UNLOCK.md`。

---

## 构建

本模块为 LSPosed 模块（libxposed API 102），Gradle + Kotlin/Compose。

环境：JDK 17、Gradle 9.8、Android SDK（compileSdk 37.2 / build-tools 37）。本机 proot 环境额外需要：
- `gradle.properties` 的 `android.aapt2FromMavenOverride` 指向 QEMU 包装过的 aapt2（普通 PC 可删除该项）
- 单 worker / 单编译线程设置（普通 PC 可放宽）

命令：
```
gradle assembleDebug
```

### 预编译二进制（不在本仓库内）
- `assets/camd.bin`（camd root 守护进程）：`tools/build_camd.sh` 静态编译生成
- `jni/**/*.so`（libhomeprobe.so / libdexkit.so）：`jni/homeprobe/build_so.sh` 生成
克隆后需先生成上述二进制再构建。

### 依赖来源
- `libs/`：libxposed `service.jar` / `api.jar`、DexKit `dexkit.jar`、FlatBuffers 等本地依赖
- Maven：MIUIX（top.yukonga.miuix.kmp 0.9.5-rc01）、Jetpack Compose 1.13、material-color-utilities 6.0.0、Kyant backdrop/capsule
- 详见 App 内「关于 → 开源库引用」，完整许可见 `notice/` 与 APK 内 `META-INF/NOTICE-DEPS.txt`

## 许可
Apache License 2.0，见 [LICENSE](LICENSE)。

## 鸣谢
WOMMO(YifePlayte)、HyperMount(XKspeed)、HyperCeiler、HyperLight、@堇森、@PY小和尚、@桃酥打水、@MariOus、锁屏飞升组织 —— 详见 App「关于与鸣谢」。
