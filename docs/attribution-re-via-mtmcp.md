# 归因库完善 —— SystemUI / SystemUI 组件 APK 逆向（mtmcp/MT MCP）

> 目标：用 MT MCP 逆向两个包的真实包/类分布，校准 `attribute_text()` / `has_plugin_class()` / `process_owner()` 的崩溃归因规则。
> APK：`com.android.systemui`（/system_ext/priv-app/MiuiSystemUI）、`miui.systemui.plugin`（/product/app/MIUISystemUIPlugin，**已混淆**，含 A.a 等短名）。

## 一、权威包分布（dex javaName 前缀统计）
| 类名前缀 | SystemUI | Plugin | 用途 |
|---|---|---|---|
| `com.android.systemui` | 25193 | **627** | SystemUI 主；**插件也嵌 627 个 → 单凭它不能判 SYS** |
| `com.android.systemui.plugins` | 322 | 0 | SYS 专属 |
| `com.miui.systemui.` | **1272** | 0 | **SYS 专属（强）** |
| `miui.systemui.` | 2（仅 utils） | **4361** | **PLG 专属主命名空间（强）** |
| `miui.systemui.plugin.` | 0 | 114 | PLG 专属 |
| `com.android.systemui.MiPlay*` | **0** | **61** | **PLG 专属（MiPlay 规则成立）** |
| `com.miui.systemui.plugin.` | 4 | 0 | 属 SYS！ |
| `miui.systemui.utils` | 2 | 0 | SYS（共享类已不共享） |

## 二、由数据得出的归因规则（当前代码已基本吻合，本表为准）
强信号（优先级从高到低，越靠前越"硬"）：
1. **进程级 `process_owner`**：`Process: miui.systemui.plugin` → PLUGIN；`Process: com.android.systemui` → BOTH（插件常驻宿主进程，无法再细分）；`system_server` → 忽略。
2. **覆盖层目录名**（最硬）：`/miui.systemui.plugin-` → PLUGIN；`/com.android.systemui-` → SYSUI。
3. **native 库路径**：库在 `.../miui.systemui.plugin-XXXX==/lib/...` → PLUGIN（不能只看库名，两边同名库都有）。
4. **包/类名前缀**：
   - `miui.systemui.*`（≈4361，SYS≈0）→ **PLUGIN**；排除 `com.miui.systemui.*` 与 `miui.systemui.utils.*`。
   - `miui.systemui.plugin.*`（114/0）→ PLUGIN；排除 `com.miui.systemui.plugin.*`（4/0，属 SYS）。
   - `com.android.systemui.MiPlay*`（0/61）→ PLUGIN。
   - `com.miui.systemui.*`（1272/0）→ **SYSUI**。
   - `com.android.systemui`（25193/627）→ 仅"兜底 SYSUI**且**无更强 plugin 证据时"；有 plugin 信号(2/3/4)时不得据此判 SYS。
5. 资源：`qs_customize_button` → PLUGIN。

## 三、本次代码改动（已完成，rescue.c）
- **修 `attribute_text` step1**：`miui.systemui.plugin.` 子串匹配会误吸 SYS 的 `com.miui.systemui.plugin.*`(4个)。加"前面不能是 `com.`"守卫（与 `has_plugin_class` 同口径）。→ 避免把 SystemUI 这 4 个类误判成插件。
- 其余规则经数据核对**无需改**（已有 `com.miui.systemui.`→SYSUI、`has_plugin_class` 认 `miui.systemui.*`、MiPlay、覆盖层目录、native 库路径）。

## 四、已知盲区（记录，暂不改）
- **插件被混淆**（`A.a` 等短名）：纯包名/类名对"混淆帧"无效；但插件主逻辑 `miui.systemui.*`(4361) 未被混淆 → 主崩溃栈仍可判 PLUGIN；混淆帧靠 process_owner/覆盖层目录兜底。
- `miui.systemui.utils.*`（2/0）目前无归属（step2 排除、step6 不含），可考虑归 SYSUI；影响极小（2 类）。
