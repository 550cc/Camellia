# Camellia

面向 HyperOS / MIUI 的 **LSPosed 增强模块**（libxposed API 102）。一个模块整合多目标增强：小米互传、小米画报、手机管家、系统安全组件，外加小窗/桌面增强、系统界面与组件急救（camd）、定时快照备份，以及模块设置页的主题系统。

- **包名**：`com.hyper.Camellia`
- **模块名**：`Camellia`
- **作者**：yuyu
- **框架**：LSPosed / libxposed，`minApiVersion=102 / targetApiVersion=102`
- **版本**：`1.0.0`（构建时自动生成，见下「版本命名」）

> 设计基调：**版本自适应**——DexKit/内置 dex 解析在运行期按“结构特征”定位被混淆的类与方法，不硬编码混淆名，系统升级一般无需重新打包。

---

## 功能

| 目标 | 说明 |
|---|---|
| **小米互传** | 「接收文件后通知栏『打开』」的落点改为 **MT 管理器**（文档 / 音频 / 未知类型）；图片 / 视频 / 联系人保持原样。4.0.5 ~ 5.4.2 自适应 |
| **小米画报** | 自选壁纸目录同步（目录可配置，默认 `Pictures/小米画报/`），自动入库、清理失效项 |
| **手机管家** | 省电与电池、前置摄像助手、隐私与安全、游戏/视频工具箱 等解锁与拦截（按 HyperCeiler 逐项复刻） |
| **系统安全组件** | 拦截云端下发指令、设备状态上报、应用清单上传（逐条开关可配） |
| **快捷方式屏蔽** | 全局关键词 + 应用专属规则，屏蔽桌面 / 全局搜索里的快捷方式入口 |
| **小窗增强** | 解除小窗限制、黑名单、通知下拉快速小窗 |
| **系统桌面** | 长按菜单增强、桌面增强 |
| **系统界面 / 组件急救（camd）** | root 守护进程 camd：环境自检与修复、崩溃急救、监控；含 eBPF 辅助。救砖思路参考 HyperMount |
| **定时快照备份** | 主题 / 字体 / 桌面布局 / 壁纸 定时快照，可手动/自动，支持恢复 |
| **主题系统（设置页）** | 界面风格（Miuix / Material3 配色）、色彩标准（2021 / 2025）、色彩风格、强调色、Monet 取色、顶/底栏样式、预测性返回、模糊、界面缩放、说明文字开关 |

> 说明：本仓库为**纯模块源码**；不含 `assets/camd.bin` 与 `jni/**/*.so` 等预编译二进制（见「构建」）。

---

## 作用域（scope）

| 包名 | 应用 |
|---|---|
| `system` | 系统框架（system_server） |
| `com.miui.mishare.connectivity` | 小米互传 |
| `com.mfashiongallery.emag` | 小米画报 |
| `com.miui.securitycenter` | 手机管家 |
| `com.android.systemui` | 系统界面（SystemUI） |
| `com.miui.home` | 系统桌面 |
| `com.miui.guardprovider` | 系统安全组件（安全守护、云端指令） |

---

## 技术要点

- **版本自适应**：运行期在 `classes*.dex` 中按特征（如“返回 `Intent` 且参数含 `FileInfo[]` 的静态方法”）定位混淆方法；不硬编码类名/方法名。
- **跨进程配置**：模块侧写入 remote preferences / `Settings.System` 公共键，目标进程免权限读取；未授权时静默回退。
- **root 守护 camd**：静态链接的守护进程，配合 eBPF 做系统界面/组件急救、崩溃拉起、快照。
- **多包多入口**：`java_init.list` 指定入口 `com.hyper.Camellia.Main`，`staticScope=true`。

---

## 项目结构

```
├── AndroidManifest.xml          # 模块清单（包名/权限/queries）
├── build.gradle / settings.gradle / gradle.properties
├── src/com/hyper/Camellia/      # 模块主体（Kotlin/Java：Main、Unlock、SecurityDiffFeatures、Sidebar*、CamdRescue、SnapScheduler、SettingsActivity…）
├── res/                         # 资源（图标、字符串、主题设置页 UI）
├── jni/homeprobe/               # homeprobe C 源 + build_so.sh（生成 libhomeprobe.so）
├── libs/                        # 本地依赖（api.jar / service.jar / dexkit.jar / flatbuffers / kotlin-stdlib）
├── assets/camd_service.sh       # camd 启动脚本（camd.bin 由 tools/build_camd.sh 生成）
├── tools/                       # camd/桥接 C 源（camellia_bridge/rescue/sock/dsnap、sqlite3、bpf_blob.h）+ 构建脚本
├── xposed-meta/META-INF/xposed/ # module.prop / scope.list / java_init.list
├── docs/  notice/
```

---

## 构建

环境：**JDK 17 + Gradle 9.8 + Android SDK**（compileSdk `android-37.2` / build-tools 37）。

```bash
gradle assembleDebug
# 产物：build/outputs/apk/debug/Camellia-debug.apk
```

本机 proot 环境的注意项（普通 PC 可删）：
- `gradle.properties` 的 `android.aapt2FromMavenOverride` 指向 QEMU 包装过的 aapt2；
- 单 worker / 单编译线程（`ActiveProcessorCount`、`-XX:+UseSerialGC`、in-process Kotlin）。

**预编译二进制（不在本仓库内，克隆后需先生成）**
- `assets/camd.bin`：`tools/build_camd.sh` 静态编译生成
- `jni/**/*.so`（libhomeprobe.so / libdexkit.so）：`jni/homeprobe/build_so.sh` 生成

签名：release 使用 `550cc.jks`，口令经 `keystore.properties` 或环境变量 `CAMELLIA_RELEASE_STORE_PASS` / `CAMELLIA_RELEASE_KEY_PASS` 注入（不入库）。

---

## 版本命名（自动）

`build.gradle` 依 git 生成：
- `versionCode = 164 + git 提交数`
- `versionName = 1.0.0 + 渠道后缀`
  - debug → `-MMddHHmm-r<versionCode>`
  - release → `-yyyyMMdd`

例：`1.0.0-11272147-r165`（debug）。

---

## 依赖与许可

- 依赖：MIUIX `top.yukonga.miuix.kmp` 0.9.5-rc01、Jetpack Compose 1.13、`material-color-utilities` 6.0.0、Kyant `backdrop` 2.0.1 / `capsule` 2.1.3；本地 `libs/`（libxposed、DexKit、FlatBuffers）。
- 完整开源库与许可见 App「关于 → 开源库引用」及 `notice/`、APK 内 `META-INF/NOTICE-DEPS.txt`。
- 本项目采用 **Apache License 2.0**，见 [LICENSE](LICENSE)。

## 鸣谢

WOMMO（YifePlayte）、HyperMount（XKspeed）、HyperCeiler、HyperLight、酷安 @堇森、@PY小和尚、@桃酥打水、@MariOus、锁屏飞升组织（全体群友）。详见 App「关于与鸣谢」。
