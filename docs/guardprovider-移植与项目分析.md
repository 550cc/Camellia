# Camellia 项目分析与 guardprovider 三 Hook 移植记录

> 记录日期：2026-11-16。以下结论均以工作区实际文件、上游源码及本地构建产物核验为准。
> 上游参考：WOMMO `33626ea70dfc336c509e1a2405d8727a6070d64f`（2026-11-12）；
> 上游源码本机路径：`/sdcard/codeforge/projects/WOMMO`（保留 `.git`，已从临时目录迁出）；
> 框架规范：libxposed **Modern Xposed API 102**（`io.github.libxposed:api:102.0.0`）。

---

## 1. 项目概览

| 项 | 值 |
|---|---|
| 模块 id / 包名 | `com.hyper.Camellia` |
| 版本 | `v2.37.10` / `versionCode=163` |
| 功能域 | 小米互传、小米画报、手机管家（省电/隐私/工具箱等）、系统安全组件 |
| 目标进程 | `system`、`com.miui.mishare.connectivity`、`com.mfashiongallery.emag`、`com.miui.securitycenter`、`com.android.systemui`、`com.miui.home`、`com.miui.guardprovider` |
| 构建 | AGP 9.4.1、KGP 2.4.20、Gradle **9.8.0**、JDK 17、compileSdk 37 / minSdk 26 / targetSdk 35 |
| 框架依赖 | `libs/api.jar`（compileOnly，API 102 由框架提供）、`libs/service.jar`（implementation，随 APK 打包）、`libs/dexkit.jar`（DexKit 2.3.0）、`flatbuffers-java:23.5.26` |

`Main.java` 导入 `io.github.libxposed.api.XposedModule`、`XposedInterface.Chain` / `Hooker`、
`ModuleLoadedParam` / `PackageLoadedParam` / `PackageReadyParam` / `SystemServerStartingParam`，
即 API 102 的现代回调式接口（非旧版 `IXposedHookLoadPackage`）。

## 2. 目录布局（非 Gradle 标准结构，易踩坑）

```
misharemt2/
├── AndroidManifest.xml          # 根目录，由 manifest.srcFile 显式指定
├── src/com/hyper/Camellia/      # Java + Kotlin 混排在同一棵树
├── res/                         # 资源
├── xposed-meta/META-INF/xposed/ # 真正打进 APK 的模块元数据（resources.srcDirs）
├── xposed/META-INF/xposed/      # 历史遗留副本，构建不引用（本次已同步）
├── jni/  assets/  libs/  tools/  docs/
├── build.gradle  settings.gradle  gradle.properties
└── build/outputs/apk/debug/Camellia-debug.apk
```

- AGP 9 内置 Kotlin 支持默认只认 `src/main/kotlin`，本项目用
  `android.sourceSets.main.kotlin.srcDirs = ['src']` 把整棵 `src/` 交给 Kotlin 插件，
  否则 `SettingsActivity.kt` 不会参与编译。
- `resources.srcDirs += 'xposed-meta'`，因此 `META-INF/xposed/*` 来自 `xposed-meta/`。
  直接改 `xposed/` 目录不会影响产物 —— 本次两处 scope.list 已同步为一致。
- `syncXposedModuleProp` 任务在构建期把 `version`/`versionCode` 写回 module.prop。

### 源码规模

| 文件 | 行数 | 职责 |
|---|---:|---|
| `Unlock.java` | 7298 | 规则表、DexKit 锚点定位、全部 hook 实现、缓存与状态 |
| `SettingsActivity.kt` | 5618 | Jetpack Compose 设置页 |
| `Main.java` | 3547 | 模块入口、生命周期分发、进程/包匹配 |
| `Prefs.java` | 2123 | 跨进程偏好读写 |
| `CfgProvider.java` | 220 | 配置 ContentProvider |
| `App.java` | 57 | Application |

## 3. 入口与生命周期

- `META-INF/xposed/java_init.list` → `com.hyper.Camellia.Main`。
- `Main extends XposedModule`，实现：
  - `onModuleLoaded(ModuleLoadedParam)`
  - `onPackageLoaded(PackageLoadedParam)`
  - `onPackageReady(PackageReadyParam)`
  - `onSystemServerStarting(SystemServerStartingParam)`
- `onPackageLoaded` 与 `onPackageReady` **双回调幂等**：部分目标（如桌面 Flutter/Rust APK）
  的 classloader 到位时机不同，需两处兜底。
- 与 WOMMO 的差异：WOMMO 另实现 `onHotReloading` / `onHotReloaded` 并在 reload 后重装 hook，
  `module.prop` 带 `autoHotReload=true`；Camellia **未实现**这两个回调，故本次不启用
  `autoHotReload`，设置页走「force-stop 目标进程重新注入」路径。

## 4. 模块元数据（lspapi102 校正）

```
id=com.hyper.Camellia
name=Camellia
version=v2.37.10
versionCode=163
minApiVersion=102      ← 本次由 101 校正为 102
targetApiVersion=102
staticScope=true
```

校正理由：Camellia 已使用 API 102 才有的 `Chain` / `Hooker` / `XposedModule` 回调接口，
声明 `minApiVersion=101` 会让框架误以为可加载在 101 上。上游 WOMMO 同为 `102/102`。

`scope.list` 现为 7 项（新增 `com.miui.guardprovider`）：

```
system
com.miui.mishare.connectivity
com.mfashiongallery.emag
com.miui.securitycenter
com.android.systemui
com.miui.home
com.miui.guardprovider
```

## 5. Hook 架构

### 5.1 表驱动规则

- `Unlock.RULES`：通用规则表，每条 `Rule(id, group, label, defaultAction, anchor)`。
- `Unlock.GROUPS`：8 个分组（电池/摄像/隐私/侧边栏/其他/游戏/视频/**系统安全组件**）；
  `groupLabel()` / `groupDesc()` 提供中文标题与说明，`G_GUARD` 已纳入。
- `A_SP` 为默认动作（默认开启）；`installSpecial()` 按规则 id 分派到具体实现。
- 设置页 `SecurityPage` 由 `Unlock.ruleRows()` + `groupKeys()` **自动生成**，新增规则无需手写开关。

### 5.2 锚点定位（Sp）

- `Sp` 用 DexKit 的 `usingStrings` 定位混淆后的方法/类，再经 `shapeMatch` 形状过滤，
  结果缓存进 `resolvedM` / `resolvedC`；`q()` 走 `query()`，`ms()` 按形状过滤，`cls()` 做类级缓存。
- 定位查询在回调线程之外执行，避免阻塞目标进程主线程。
- 签名缓存 `ulCacheFile()` = `/data/user/<u>/<当前包名>/cache/hooksig.ul.cache`，
  `ulCacheKey` 含 `sourceDir + mtime + size`，版本升级自动失效。
- `cacheCovers()` 判断缓存是否覆盖所需规则；不完整时退回 DexKit 重查。

### 5.3 进程隔离（guardprovider 专用）

- `Unlock.PKG_GP = "com.miui.guardprovider"`，`matchGuardPkg()` 同时接受
  `.` / `:` 形式的子进程名。
- `Main.java:604`（`onPackageReady`）与 `Main.java:671`（`onPackageLoaded`）两处，
  在 `matchPkg` 分支**之前**先判 `matchGuardPkg` → `Unlock.installGuardProvider(...)`。
- 走独立入口的原因：手机管家与系统安全组件是两套规则集，若共用一份签名缓存，
  `cacheCovers()` 会判为「不完整」并反复退回 DexKit，污染设置页命中状态。
- `countOnRules()` 与 `onTable` 增加 `sIsGuard` 门控：guardprovider 进程不得统计/挂载
  手机管家那批 `RULES`，否则默认开启的管家规则会让 guard 进程误判「有规则启用」并空跑 DexKit。
- 缓存按包名构造，两进程天然隔离，不会互相覆盖。

## 6. 三条 guardprovider Hook：上游对照与本次校正

上游目录：`app/src/main/java/com/yifeplayte/wommo/hook/hooks/singlepackage/guardprovider/`

| 功能 | 规则 id | 上游实现 | Camellia 锚点 | 拦截行为 | 本次改动 |
|---|---|---|---|---|---|
| 拦截应用清单上传 | `gp_applist` | `BlockAppListUpload.kt`：`usingStrings=["https://flash.sec.miui.com/detect/app"]` 定位 POST 发包方法，`before` 返回 null | `GP_APP_LIST_ACTION`：`usingStrings("https://flash.sec.miui.com/detect/app")` + POST 方法 | `before` 置空返回值，令上传失败 | **无需改动**，锚点与语义已一致 |
| 拦截设备状态上报 | `gp_devstate` | `BlockDeviceStateReport.kt`：定位 `name="run"`、`paramCount=0`、`usingStrings=["Reported device_state anomaly"]`；反射取 `int` 型 taskId 字段，`taskId == 6` 时 `param.result = null` | `GP_DEV_STATE_ACTION`：同一字符串锚点 + `run()` | **只跳过任务 6 分支**，不整体拦 `run()`（同一 run 里还有十几个无关任务）；拦截点必须在任务入口，统计 SDK 会吞异常、在上游抛异常无效 | 由「写哨兵值继续执行」改为 **`==6` 直接 `param.result=null`**；字段选择优先 `task` / `id` / `type` |
| 拦截云端下发指令 | `gp_push` | `BlockPushCommand.kt`：双字符串锚点（`unknown command: ` / `failed to parse params: `），`callerMethods` 反查唯一的无参 `String` 指令名 getter，10 条指令白名单，命中则 `newInstance(command, 0, "blocked")` 假成功 | `GP_PUSH_ACTION`：同样双字符串锚点 + `before` 命令分发点 | 命中白名单则替换调用结果为「成功」假值；构造不出返回值一律 `proceed` 放行，绝不抛异常引发重试 | `GP_COMMANDS` 由占位值换成上游 10 条真实指令；`gpFakeResult` 的 msg 由 `ok` 改为 **`blocked`**；`gpActionName` 用反射扫描 + 白名单校验 |

上游真实指令白名单（10 条）：

```
collect_package, disable_package, enable_package, disable_component,
enable_component, clear_a11y, scan_and_process, set_prop,
set_settings, update_virus_db
```

`gpFakeResult(Class<?> rt, String action)`：优先 `(String, int, String)` 构造器，
构造 `newInstance(action, 0, "blocked")`，与上游一致；`code=0` 表示成功。
每条反射调用均有独立 `try/catch`，只有返回值命中白名单才生效。

### 与上游的实现差异（有意保留）

上游用 DexKit `callerMethods` 唯一确定指令名 getter；Camellia 用
「运行时扫描指令对象所有无参、非静态、返回 `String` 的实例方法 + 白名单校验」。
功能等价且对混淆变体更宽容，代价是会顺带调用少量无关 getter（均有异常保护，
且只有返回值恰好是 10 条指令之一才会生效）。`toString()` 一类方法由 `k != Object.class`
边界与 try/catch 兜住。

## 7. 构建验证

```bash
cd /sdcard/projects/misharemt2
/opt/gradle-9.8.0/bin/gradle assembleDebug --no-daemon \
  -Dorg.gradle.vfs.watch=false \
  -Djava.io.tmpdir=/sdcard/projects/misharemt2/.gradle-tmp --stacktrace
```

结果：`EXIT=0`（此前项目状态为 FAILED，本次已解除）。产物
`build/outputs/apk/debug/Camellia-debug.apk`（15 MB）。

APK 内校验：

- `META-INF/xposed/scope.list` = 7 项，含 `com.miui.guardprovider` ✅
- `META-INF/xposed/module.prop` = `minApiVersion=102` / `targetApiVersion=102` ✅
- `META-INF/xposed/java_init.list` = `com.hyper.Camellia.Main` ✅
- dex 内含锚点字符串 `https://flash.sec.miui.com/detect/app`、
  `unknown command: ` / `failed to parse params: `、`Reported device_state anomaly`
  及 `gp_applist` / `gp_devstate` / `gp_push` / `G_GUARD` ✅
- `Main.java` 两处 `installGuardProvider` 调用点（604 / 671）✅

## 8. 部署（必须由用户在 LSPosed 侧完成）

以 root 只读方式核对设备侧
`/data/adb/lspd/config/modules_config.db` 的 `scope` 表（该变体表结构为
`scope(module_pkg_name, app_pkg_name, user_id)`，无 `mid` 列；设备无 `sqlite3`，
故临时复制到 `/sdcard/codeforge/tmp/` 用 Python sqlite3 只读查询，查完即删）：

```
Camellia 当前 scope = 6 项：
  com.android.systemui / com.mfashiongallery.emag / com.miui.home /
  com.miui.mishare.connectivity / com.miui.securitycenter / system
guardprovider present: False
```

即使 `staticScope=true` 且新 APK 的 `scope.list` 已含该进程，管理器里的**实际勾选**仍是注入依据。
因此：

1. 安装新构建的 `Camellia-debug.apk`；
2. 在 LSPosed 管理器 → Camellia → 作用域，勾选 **`com.miui.guardprovider`**；
3. `force-stop com.miui.guardprovider`（或重启设备）令其重新注入；
4. 设置页「系统安全组件」分组下三条规则默认开启，无需额外操作。

**未直接改写 scope 数据库**：该 LSPosed 变体无 `lspd` CLI / `/data/adb/lspd/bin`，
配置只在框架启动时读入内存，直接改库需重启且可能影响同机其它模块，风险高于收益。

## 9. 已知取舍与回退

- `autoHotReload` 未启用（缺 `onHotReloading` / `onHotReloaded` 实现），热更新一律靠
  force-stop 目标进程。
- `getSettingsConfig` 类不在拦截范围（上游同样不拦），云端配置读取保持原样。
- 三条规则默认开启；若安全组件出现重试/异常，可在设置页逐条关闭，无需重打包。
- 上游 WOMMO 为 minSdk 33 / 仅 arm64-v8a 的独立模块，Camellia 为 minSdk 26 的多功能
  聚合模块，故采用「进程门控 + 独立入口」而非直接搬 `MainHook` 的 registry 结构。
