package com.hyper.Camellia

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import io.github.libxposed.service.HookedTarget
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import com.materialkolor.hct.Hct
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模块设置页。
 *
 * 结构沿用 HyperCeiler 的组织方式（两级菜单 + 卡片式条目 + 独立二级页），
 * 视觉上做了三件事：
 *   1) 品牌化：CamelliaTheme 注入山茶花玫红作为强调色，开关 / 单选 / 主按钮随之变色；
 *   2) 结构化：状态不再是一段多行文字，而是「带色结论 chip + 左右两栏的键值行」，
 *      长值只在右侧列内折行，不会把左侧标签挤歪；
 *   3) 节奏统一：区块之间一律 12dp，说明文字与 SmallTitle 对齐到同一 28dp 基线。
 *
 * 调试信息默认不显示：状态卡片只给结论，打开「调试模式」才展开命中方法与耗时明细。
 */

/** 「重启应用」/ 结束进程链路的日志 tag：adb logcat -s MiShareMT2/Restart */
private const val TAG_RESTART = "MiShareMT2/Restart"

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 覆盖安装后图标状态可能被系统重置，这里同步一次（内部已走后台队列，不阻塞首帧）
        Prefs.applyIconVisibility(this, Prefs.isHideIcon(this))
        // 打开设置页时给目标进程发一次「配置变了」的刷新信号。
        // 配置内容不再落任何副本（侧车文件 / Settings.System / 系统键都取消了）：
        // 目标进程要配置时直接查 CfgProvider，读到的是模块 prefs 的实时真源。
        val appCtx = applicationContext
        // 尽早挂上 prefs 变更监听：本进程写设置后要能触发 CfgProvider 的 notifyChange，
        // 手机管家 / SystemUI 里的 Unlock、LauncherShortcut 才会当轮丢掉本地快照。
        CfgProvider.ensureWatching(appCtx)
        Prefs.bg(Runnable {
            CfgBridge.push(appCtx, Prefs.FG_PKG)
        })

        setContent {
            // 主题模式提升到这里：在「设置」页切换后整页立刻重建配色，不需要退出重进
            var themeMode by remember { mutableStateOf(Prefs.getThemeMode(this)) }
            var monet by remember { mutableStateOf(Prefs.getThemeMonet(this)) }
            var pureBlack by remember { mutableStateOf(Prefs.getPureBlack(this)) }
            var blurLevel by remember { mutableIntStateOf(Prefs.getGlassBlur(this)) }
            var uiScale by remember { mutableIntStateOf(Prefs.getUiScale(this)) }
            var showHints by remember { mutableStateOf(Prefs.getShowHints(this)) }
            var themeStyle by remember { mutableStateOf(Prefs.getThemeStyle(this)) }
            var colorStandard by remember { mutableStateOf(Prefs.getColorStandard(this)) }
            var accentColor by remember { mutableIntStateOf(Prefs.getAccentColor(this)) }
            var uiStyle by remember { mutableStateOf(Prefs.getUiStyle(this)) }
            // 首次启动引导：未完成时先走引导（用户协议 → 授权检查），完成后才进设置页
            var guideDone by remember { mutableStateOf(Prefs.isGuideDone(this)) }
            CamelliaTheme(themeMode, monet, pureBlack, blurLevel, uiScale, showHints = showHints, style = themeStyle, colorStandard = colorStandard, accentColor = accentColor, uiStyle = uiStyle) {
                if (!guideDone) {
                    GuideScreen(
                        onFinish = {
                            Prefs.setGuideDone(this, true)
                            guideDone = true
                        },
                    )
                } else {
                    SettingsScreen(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            Prefs.setThemeMode(this, mode)
                        },
                        monet = monet, onMonetChange = { v -> monet = v; Prefs.setThemeMonet(this, v) },
                        pureBlack = pureBlack, onPureBlackChange = { v -> pureBlack = v; Prefs.setPureBlack(this, v) },
                        blurLevel = blurLevel, onBlurChange = { v -> blurLevel = v; Prefs.setGlassBlur(this, v) },
                        uiScale = uiScale, onUiScaleChange = { v -> uiScale = v; Prefs.setUiScale(this, v) },
                        showHints = showHints, onShowHintsChange = { v -> showHints = v; Prefs.setShowHints(this, v) },
                        themeStyle = themeStyle, onThemeStyleChange = { v -> themeStyle = v; Prefs.setThemeStyle(this, v) },
                        colorStandard = colorStandard, onColorStandardChange = { v -> colorStandard = v; Prefs.setColorStandard(this, v) },
                        accentColor = accentColor, onAccentColorChange = { v -> accentColor = v; Prefs.setAccentColor(this, v) },
                        uiStyle = uiStyle, onUiStyleChange = { v -> uiStyle = v; Prefs.setUiStyle(this, v) },
                        onForceStop = { forceStopMiShare() },
                        onForceStopPkg = { pkg, label -> forceStopPkg(pkg, label) },
                        onKillApps = { pkgs -> killApps(pkgs) },
                        onRestartSelf = { recreate() },
                        onToast = { msg -> toast(msg) },
                        onShowGuide = { guideDone = false },
                    )
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------- 强制结束小米互传 ----------------

    /**
     * 结束小米互传，让新设置下次启动时生效。
     *
     * 普通应用无权 force-stop 别的应用，因此：
     *   1) 设备有 root（/system/bin/su）时用 su 执行 am force-stop，最彻底；
     *   2) 没有 root 时退化为 killBackgroundProcesses（可结束其后端进程）。
     */
    private fun forceStopMiShare() = forceStopPkg(Prefs.TARGET_PKG, "小米互传")

    /** 强制结束任意包名（root 优先，无 root 退化为 killBackgroundProcesses） */
    private fun forceStopPkg(pkg: String, label: String) {
        toast("正在结束" + label + "…")
        Thread({
            val msg = doForceStop(pkg, label)
            runOnUiThread { toast(msg) }
        }, "force-stop").start()
    }

    private fun doForceStop(pkg: String, label: String): String {
        // 1) root 方式：真实探测 + 结尾标记，不再依赖文件存在性与退出码
        val r = runSu("am force-stop $pkg; sleep 0.2; echo CF_DONE", 15000)
        val rootOk = r.ok && r.out.contains("CF_DONE")
        android.util.Log.i(TAG_RESTART, "root-force-stop $pkg label=$label rootOk=$rootOk out=" + r.out.take(200))
        if (rootOk) {
            return "已强制结束" + label + "（root），下次打开即用当前设置"
        }
        // 2) 无 root 回退
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
            if (am != null) {
                am.killBackgroundProcesses(pkg)
                return "已结束" + label + "的后台进程（未拿到 root），下次打开即用当前设置"
            }
        } catch (ignored: Throwable) {
        }
        return "结束失败：请在系统设置 -> 应用 -> " + label + " 中手动强行停止"
    }

    // ---------------- 重启目标应用（kill 进程，仿 HyperCeiler 的 AppsTool.killApps） ----------------

    /** 结束若干应用的进程：系统会按 oom_adj 把它们重新拉起，等于「重启了一下」 */
    private fun killApps(pkgs: List<String>) {
        if (pkgs.isEmpty()) return
        toast("正在重启 " + pkgs.size + " 个应用…")
        Thread({
            val msg = doKillApps(pkgs)
            runOnUiThread { toast(msg) }
        }, "restart-apps").start()
    }

    /** su 执行结果：ok 表示 su 进程跑完并被读回，out 是合并后的 stdout+stderr */
    private class SuResult(val ok: Boolean, val out: String)

    /**
     * 用 root 执行一段 shell，并读回全部输出。
     *
     * 不用 `waitFor() == 0` 判定成功：脚本里 `[ cond ] && kill` 这类写法在条件为假时会把整段的
     * 退出码变成 1，于是「root 明明已经执行过」也会被判成失败，静默退化成 killBackgroundProcesses
     * 并提示「无 root」—— 这就是「重启应用不走 root」的根因。
     * 现在改成：先 id 确认 uid=0，再用结尾标记确认脚本跑完；输出一律读走，避免管道写满阻塞。
     */
    private fun runSu(cmd: String, timeoutMs: Long = 15000): SuResult {
        val p = try {
            ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return SuResult(false, t.message ?: "su 不可用")
        }
        val sb = StringBuilder()
        val sink = Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') }
            } catch (ignored: Throwable) {
            }
        }, "su-read")
        sink.start()
        val finished = try {
            p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            false
        }
        if (!finished) {
            try {
                p.destroyForcibly()
            } catch (ignored: Throwable) {
            }
            return SuResult(false, "su 超时（可能未授权）")
        }
        sink.join(600)
        return SuResult(true, sb.toString())
    }

    /**
     * 生成「取得目标包所有 PID」的 shell 片段，结果写进变量 P。
     *
     * 这里踩过坑：`pgrep -f <包名>` 会命中 **su 自己**——因为 su 的 argv 就是
     * `su -c "...<包名>..."`，命令行里含包名 → 脚本把自己（以及父链路）杀掉，
     * 表现为 kill 只执行一半、退出码 143、结尾标记打不出来。
     * 所以先用 pidof（按进程名精确匹配），再用锚定正则 `^pkg(:子进程)?$` 兜底。
     */
    private fun pidExpr(pkg: String): String {
        val pat = pkg.map { if (it == '.') "[.]" else it.toString() }.joinToString("")
        return "P=\"\$(pidof $pkg 2>/dev/null)\"; " +
            "[ -z \"\$P\" ] && P=\"\$(pgrep -f '^$pat(:[A-Za-z0-9_.]+)?\$' 2>/dev/null)\"; "
    }

    /**
     * 用 SIGTERM → SIGKILL 结束应用进程，刻意不用 am force-stop：
     * force-stop 会把应用标记成 stopped，系统不会自动把它拉起 —— 系统界面 / 桌面被这样停掉后
     * 通知栏与桌面可能长时间回不来，只能用户手动点开。kill 则由系统自动重启进程，
     * 行为与 HyperCeiler 的 AppsTool.killApps(pkg, 15) 一致。
     *
     * 取 PID 用 pidof 优先、pgrep -f 兜底；两句都不匹配说明该应用当前没在跑，直接跳过。
     */
    private fun doKillApps(pkgs: List<String>): String {
        val safe = pkgs.filter { it.matches(Regex("[A-Za-z0-9_.]+")) }
        if (safe.isEmpty()) return "包名不合法，已取消"

        // 1) 真实探测 root：不看 /system/bin/su 是否存在，直接问 su 要 uid
        val probe = runSu("id", 10000)
        val rootOk = probe.ok && probe.out.contains("uid=0")
        if (!rootOk) {
            android.util.Log.w(TAG_RESTART, "no root: " + probe.out.take(200))
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
                if (am != null) {
                    safe.forEach { am.killBackgroundProcesses(it) }
                    return "未获得 root 授权，只清理了后台进程；前台应用需手动结束"
                }
            } catch (ignored: Throwable) {
            }
            return "重启失败：拿不到 root（" + probe.out.trim().take(60).ifBlank { "su 无输出" } + "）"
        }

        // 2) 一次 su 会话内 TERM → KILL，避免每个包各 fork 一个 root shell
        val script = buildString {
            appendLine("id") // 让输出带上 uid=0，作为 root 确实生效的证据
            safe.forEach { pkg ->
                appendLine(pidExpr(pkg) + "[ -n \"\$P\" ] && kill -15 \$P 2>/dev/null; true")
            }
            appendLine("sleep 0.5")
            safe.forEach { pkg ->
                appendLine(pidExpr(pkg) + "[ -n \"\$P\" ] && kill -9 \$P 2>/dev/null; true")
            }
            appendLine("echo CF_RESTART_DONE")
        }
        val r = runSu(script, 20000)
        val done = r.out.contains("CF_RESTART_DONE")
        android.util.Log.i(TAG_RESTART, "root-kill pkgs=$safe done=$done out=" + r.out.take(300))
        if (!done) {
            return "root 执行中断：" + r.out.trim().take(80).ifBlank { "无输出" }
        }
        return "已用 root 重启 " + safe.size + " 个应用，重新打开对应界面即生效"
    }
}

// ---------------- 页面结构 ----------------

/** 两级页面：主页是入口列表，其余是各目标应用 / 设置 / 关于的二级页 */
private enum class Screen(val title: String, val subtitle: String) {
    Home("Camellia", "模块设置"),
    MiShare("小米互传", "收到文件的打开落点"),
    Gallery("小米画报", "自选图片目录同步"),
    Security("手机管家", "解锁 / 拦截"),
    ShortcutBlock("快捷方式屏蔽", "全局关键词 · 应用专属"),
    GpPushCmds("指令拦截明细", "云端下发指令 · 逐条开关"),
    Freeform("小窗增强", "解除限制 · 黑名单 · 通知下拉"),
    Desktop("系统桌面修改", "长按菜单 · 桌面增强"),
    CamdPanel("系统界面/组件急救-camd", "环境 · 行为 · 监控 · 快照"),
    Dsnap("定时快照备份", "主题 · 字体 · 桌面布局 · 壁纸"),
    Settings("设置", "模块自身外观"),
    About("关于与鸣谢", "作者 · 鸣谢 · 开源引用"),
}

/** 底栏三栏的后两栏：切到它们时不再算「主页」高亮 */
private val HOME_TABS = setOf(Screen.Home, Screen.MiShare, Screen.Gallery, Screen.Security, Screen.Freeform)

@Composable
private fun SettingsScreen(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onForceStop: () -> Unit,
    onForceStopPkg: (String, String) -> Unit,
    onKillApps: (List<String>) -> Unit,
    onRestartSelf: () -> Unit,
    onToast: (String) -> Unit,
    onShowGuide: () -> Unit,
    monet: Boolean, onMonetChange: (Boolean) -> Unit,
    pureBlack: Boolean, onPureBlackChange: (Boolean) -> Unit,
    blurLevel: Int, onBlurChange: (Int) -> Unit,
    uiScale: Int, onUiScaleChange: (Int) -> Unit,
    showHints: Boolean, onShowHintsChange: (Boolean) -> Unit,
    themeStyle: String, onThemeStyleChange: (String) -> Unit,
    colorStandard: String, onColorStandardChange: (String) -> Unit,
    accentColor: Int, onAccentColorChange: (Int) -> Unit,
    uiStyle: String, onUiStyleChange: (String) -> Unit,
) {
    val ctx = LocalContext.current
    // AndroidLiquidGlass(backdrop)：把「滚动内容层」录制成 layer backdrop，
    // 顶栏用 drawBackdrop 消费它，得到 blur + 折射 + 高光的液体玻璃
    val backdrop = rememberLayerBackdrop()
    // 玻璃本体着色基准：沿用 MIUIX 的 surface，深浅色随主题自动切换
    val glassColor = if (LocalCamelliaPureBlack.current && LocalCamelliaDark.current) Color.Black else MiuixTheme.colorScheme.surface
    val glassBlur = LocalGlassBlur.current
    // 底栏实测高度（含系统导航条内距）：滚动内容按它留出底部安全区，
    // 内容中途会从玻璃底栏下方滚过，最后一项却不会被挡住
    var navBarHeightPx by remember { mutableIntStateOf(0) }
    val navBarHeight = with(LocalDensity.current) { navBarHeightPx.toDp() }
    // 「关于与鸣谢」页的流动渐变背景：配色随深浅色切换，phase 无限循环驱动光斑漂移
    val aboutPalette = remember(glassColor) { aboutGradientPalette(glassColor) }
    val aboutPhase by rememberInfiniteTransition(label = "aboutBg").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 26_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "aboutPhase",
    )
    // 「关于」页渐变背景的透明度：切页时淡入淡出，而不是整块背景瞬间出现 / 消失
    // （这是之前切页「闪一下」的主因之一：渐变随页面内容一起被硬切）

    var screen by remember { mutableStateOf(Screen.Home) }
    // 手机管家第三级页：当前展开的功能大类（ = 大类列表）
    var secGroup by remember { mutableStateOf("") }
    // 逐级返回：大类详情 → 大类列表；快捷方式屏蔽 → 手机管家；其余 → 主页
    val goBack: () -> Unit = {
        when {
            screen == Screen.Security && secGroup.isNotEmpty() -> secGroup = ""
            screen == Screen.ShortcutBlock -> screen = Screen.Security
            screen == Screen.GpPushCmds -> screen = Screen.Security
            else -> screen = Screen.Home
        }
    }
    var barStyle by remember { mutableStateOf(Prefs.getBottomBarStyle(ctx)) }
    var topBarStyle by remember { mutableStateOf(Prefs.getTopBarStyle(ctx)) }
    var mtTarget by remember { mutableStateOf(Prefs.getMtTarget(ctx)) }
    var hideIcon by remember { mutableStateOf(Prefs.isHideIcon(ctx)) }
    var fgEnabled by remember { mutableStateOf(Prefs.isFgEnabled(ctx)) }
    var fgDir by remember { mutableStateOf(Prefs.getFgDir(ctx)) }
    var fgCap by remember { mutableStateOf(Prefs.getFgCap(ctx).toString()) }
    var fgOrder by remember { mutableStateOf(Prefs.getFgOrder(ctx)) }
    var fgBlockAd by remember { mutableStateOf(Prefs.isFgBlockAd(ctx)) }
    var fgLocalPhoto by remember { mutableStateOf(Prefs.isFgLocalPhoto(ctx)) }
    var fgCurated by remember { mutableStateOf(Prefs.getFgCurated(ctx)) }
    var fgAutoHeal by remember { mutableStateOf(Prefs.isFgAutoHeal(ctx)) }
    var debug by remember { mutableStateOf(Prefs.isFgDebug(ctx)) }
    var tick by remember { mutableIntStateOf(0) }

    // 「关于」页渐变背景的透明度：切页时淡入淡出，而不是整块背景瞬间出现 / 消失
    // （这是之前切页「闪一下」的主因之一：渐变随页面内容一起被硬切）
    val aboutAlpha by animateFloatAsState(
        targetValue = if (screen == Screen.About) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "aboutBgAlpha",
    )

    // 进页面时 pull 一次运行期状态；目标进程回包有延迟（小米 Greezer 会冷冻模块 app），
    // 所以用「状态到达即刷新」监听，而不是靠固定延时。
    DisposableEffect(Unit) {
        StatusReceiver.request(ctx, Prefs.TARGET_PKG)
        StatusReceiver.request(ctx, Prefs.FG_PKG)
        val receiver: BroadcastReceiver = StatusReceiver.registerUiListener(ctx) { tick++ }
        onDispose {
            try {
                ctx.unregisterReceiver(receiver)
            } catch (ignored: Throwable) {
            }
        }
    }
    // 兜底：2 秒后再刷一次（有些回包来得更晚）
    LaunchedEffect(Unit) {
        delay(2000)
        tick++
    }

    // 状态卡片的数据源包含 PackageManager 查询与 /sdcard 上的状态文件读取，
    // 全部放到 IO 线程取好后再回到组合线程：首帧与「回包刷新(tick++)」都不会再卡 UI
    var version by remember { mutableStateOf("") }
    var mtView by remember { mutableStateOf(StatusView("读取中", Tone.Idle, emptyList(), emptyList())) }
    var fgView by remember { mutableStateOf(StatusView("读取中", Tone.Idle, emptyList(), emptyList())) }
    var ulView by remember { mutableStateOf(StatusView("读取中", Tone.Idle, emptyList(), emptyList())) }
    LaunchedEffect(tick, debug) {
        val loaded = withContext(Dispatchers.IO) {
            arrayOf<Any>(
                moduleVersion(ctx),
                mtStatusView(ctx, debug),
                fgStatusView(ctx, debug),
                ulStatusView(Prefs.readUlStatus(ctx), debug),
            )
        }
        version = loaded[0] as String
        mtView = loaded[1] as StatusView
        fgView = loaded[2] as StatusView
        ulView = loaded[3] as StatusView
    }

    // 右上角「重启应用」对话框（仿 HyperCeiler 的标题栏重启入口）
    var showRestart by remember { mutableStateOf(false) }

    // 返回键：先退出手机管家的第三级页，再回主页
    BackHandler(enabled = screen != Screen.Home) {
        goBack()
    }

    Scaffold(
        topBar = {
            val roundTop = topBarStyle == Prefs.TOP_ROUND
            val glassyTop = topBarStyle == Prefs.TOP_GLASS
            val topShapeFor = if (roundTop) RoundedCornerShape(22.dp) else RoundedCornerShape(0.dp)
            TopAppBar(
                title = screen.title,
                subtitle = screen.subtitle,
                // MIUIX 顶栏默认画一层不透明 surface（modifier.background(color) 盖在玻璃层之上），
                // 会把玻璃整个挡住。改成透明后玻璃才透得出来，观感交给 backdrop 的着色与高光负责。
                color = Color.Transparent,
                modifier = Modifier.drawBackdrop(
                    backdrop = backdrop,
                    // 顶栏贴屏幕顶端，上沿与左右都要平直：HyperOS 的顶栏本身就是通栏直角。
                    // 这里必须用 CornerBasedShape（RoundedCornerShape(0.dp) 四角半径 0，视觉等价于矩形）：
                    // backdrop 的 lens 折射要从 shape 反推圆角，传 RectangleShape 会直接抛
                    // UnsupportedOperationException: Only RoundedRectangularShape or CornerBasedShape is supported
                    shape = { topShapeFor },
                    effects = {
                        // vibrancy：先提饱和，避免模糊后整块发灰
                        vibrancy()
                        blur(((if (glassyTop) 30.dp else 22.dp) * glassBlur).toPx())
                        // lens 折射：模拟玻璃厚度，边缘处内容被轻微放大扭曲（液体玻璃的关键特征）
                        lens((if (glassyTop) 34.dp else 24.dp).toPx(), (if (glassyTop) 34.dp else 24.dp).toPx(), depthEffect = true)
                    },
                    // 不要常开 Highlight.Default：它的 shader 对矩形内部任意点都返回 0.707 的常量强度，
                    // 再以 BlendMode.Plus 叠加会把整条顶栏刷成一块纯白矩形（底栏同理），
                    // 所以静止时保持无高光，只靠「模糊 + 折射 + 半透明底面」表达玻璃。
                    // 玻璃本体着色（半透明），保证顶栏标题/副标题可读
                    onDrawSurface = { drawRect(glassColor.copy(alpha = if (glassyTop) 0.30f else 0.45f)) },
                ),
                navigationIcon = {
                    if (screen != Screen.Home) {
                        IconButton(onClick = goBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
                // 右上角「重启应用」：与 HyperCeiler 一样放在标题栏右侧，
                // 任何页面都能一键结束目标应用进程，让新配置立刻生效
                actions = {
                    IconButton(onClick = { showRestart = true }) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "重启应用",
                        )
                    }
                },
                defaultWindowInsetsPadding = false,
            )
        },
    ) { inner ->
        // 顶栏玻璃 / 内容层 / 底栏玻璃同处一个 Box：
        // 内容层（AnimatedContent）把「页面背景 + 滚动内容」录制成 backdrop，顶栏与底栏各自 drawBackdrop 取用
        Box(modifier = Modifier.fillMaxSize()) {
            // ---- 页面层（backdrop 来源层）----
            // 底板 + 渐变背景 + 页面内容一起录制在这一层里，顶栏与玻璃底栏按坐标取用。
            // 关键 1：layerBackdrop 只挂这一处 —— 原先挂在 AnimatedContent 的每个页面上，
            //         切页时新旧两页同时存在、互相抢录制，是「切页闪屏」的主因；
            // 关键 2：底板 background 恒定为不透明页面色，任何过渡中间态都不会露出窗口底色；
            // 关键 3：渐变背景挪到这一层（不再跟着页面内容被硬切），只做 alpha 淡入淡出。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(glassColor)
                    .drawBehind { drawAboutGradient(glassColor, aboutPalette, aboutPhase, aboutAlpha) }
                    .layerBackdrop(backdrop),
            ) {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        // 进二级页：新页自右滑入、旧页左移淡出；回主页时方向相反。
                        val forward = targetState.ordinal > initialState.ordinal
                        val slide = tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)
                        val fade = tween<Float>(durationMillis = 220)
                        (slideInHorizontally(slide) { if (forward) it / 6 else -it / 6 } + fadeIn(fade))
                            .togetherWith(
                                slideOutHorizontally(slide) { if (forward) -it / 6 else it / 6 } + fadeOut(fade),
                            )
                    },
                    label = "page",
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            // 顶栏高度作为滚动内容的一部分：这里不 .padding(inner)，
                            // 滚动视口与顶栏重叠，内容才能滚到顶栏下面、从玻璃里透出来。
                            // 背景录制由外层「页面层」统一负责，这里只管滚动与留白
                            .verticalScroll(rememberScrollState())
                            .padding(
                                top = inner.calculateTopPadding(),
                                // 底部按底栏实测高度留白：内容能滚到玻璃底栏之下，
                                // 最后一项又能完整停在底栏上方（28dp 是收尾呼吸感）
                                bottom = navBarHeight + 28.dp,
                            ),
                    ) {
                        when (page) {
                            Screen.Home -> HomePage(
                                version = version,
                                mtChip = mtView.chip,
                                mtTone = mtView.tone,
                                fgChip = fgView.chip,
                                fgTone = fgView.tone,
                                ulChip = ulView.chip,
                                ulTone = ulView.tone,
                                debug = debug,
                                onDebugChange = {
                                    debug = it
                                    Prefs.setFgDebug(ctx, it)
                                    onToast(if (it) "调试模式已开启，目标进程稍后生效" else "调试模式已关闭，日志恢复静默")
                                },
                                onOpen = {
                                    // 从主页进入任一二级页时，手机管家的大类详情要复位到大类列表
                                    secGroup = ""
                                    screen = it
                                },
                                onOpenTo = { target, group ->
                                    secGroup = group
                                    screen = target
                                },
                            )

                            Screen.MiShare -> MiSharePage(
                                mtTarget = mtTarget,
                                mtView = mtView,
                                debug = debug,
                                onMtTargetChange = { pkg ->
                                    mtTarget = pkg
                                    Prefs.setMtTarget(ctx, pkg)
                                    if (pkg == Prefs.MT_CANARY &&
                                        Prefs.isInstalled(ctx, Prefs.MT_CANARY) &&
                                        !Prefs.supportsFolderJump(ctx, Prefs.MT_CANARY)
                                    ) {
                                        onToast("该版本没有跳转入口，将自动回退到 MT 管理器正式版")
                                    } else {
                                        onToast("已切换为 $pkg，点下方按钮结束互传后生效")
                                    }
                                },
                                onForceStop = onForceStop,
                                onRefresh = {
                                    StatusReceiver.request(ctx, Prefs.TARGET_PKG)
                                    tick++
                                    onToast("已刷新")
                                },
                                onToast = onToast,
                            )

                            Screen.Gallery -> GalleryPage(
                                fgEnabled = fgEnabled,
                                fgDir = fgDir,
                                fgCap = fgCap,
                                fgOrder = fgOrder,
                                fgBlockAd = fgBlockAd,
                                fgLocalPhoto = fgLocalPhoto,
                                fgCurated = fgCurated,
                                fgAutoHeal = fgAutoHeal,
                                fgView = fgView,
                                debug = debug,
                                onFgEnabledChange = {
                                    fgEnabled = it
                                    Prefs.setFgEnabled(ctx, it)
                                    CfgBridge.push(ctx, Prefs.FG_PKG)
                                    onToast(if (it) "已启用，画报正在按新配置同步" else "已停用")
                                },
                                onFgDirChange = { fgDir = it },
                                onSaveDir = {
                                    Prefs.setFgDir(ctx, fgDir)
                                    CfgBridge.push(ctx, Prefs.FG_PKG)
                                    fgDir = Prefs.getFgDir(ctx)
                                    onToast("目录已保存，画报正在同步")
                                },
                                onResetDir = {
                                    Prefs.setFgDir(ctx, Prefs.FG_DEFAULT_DIR)
                                    CfgBridge.push(ctx, Prefs.FG_PKG)
                                    fgDir = Prefs.getFgDir(ctx)
                                    onToast("已恢复默认目录")
                                },
                                onFgCapChange = { fgCap = it.filter { ch -> ch.isDigit() }.take(4) },
                                onSaveCap = {
                                    Prefs.setFgCap(ctx, fgCap.toIntOrNull() ?: Prefs.FG_DEFAULT_CAP)
                                    CfgBridge.push(ctx, Prefs.FG_PKG)
                                    fgCap = Prefs.getFgCap(ctx).toString()
                                    onToast("上限已保存，画报正在同步")
                                },
                                onFgOrderChange = { order ->
                                    fgOrder = order
                                    // 换了顺序就要从头播：清掉右滑进度，否则老图 show_t 非 0 会被跳过
                                    Prefs.bumpFgResetSeq(ctx)
                                    Prefs.setFgOrder(ctx, order)
                                    CfgBridge.push(ctx, Prefs.FG_PKG)
                                    onToast("已改为${orderName(order)}，画报正在重排")
                                },
                                onFgBlockAdChange = {
                                    fgBlockAd = it
                                    Prefs.setFgBlockAd(ctx, it)
                                    CfgBridge.push(ctx, Prefs.FG_PKG)
                                    onToast(if (it) "已屏蔽推广" else "已恢复推广")
                                },
                                onFgLocalPhotoChange = {
                                    fgLocalPhoto = it
                                    Prefs.setFgLocalPhoto(ctx, it)
                                    CfgBridge.push(ctx, Prefs.FG_PKG)
                                    onToast(if (it) "已启用右滑轮播图片接管" else "已停用右滑轮播图片接管")
                                },
                                onFgCuratedChange = {
                                    fgCurated = it
                                    Prefs.setFgCurated(ctx, it)
                                    CfgBridge.push(ctx, Prefs.FG_PKG)
                                    onToast(if (it.isEmpty()) "已取消精简，画报精选壁纸全部保留" else "已精简精选壁纸")
                                },
                                onFgAutoHealChange = {
                                    fgAutoHeal = it
                                    Prefs.setFgAutoHeal(ctx, it)
                                    CfgBridge.push(ctx, Prefs.FG_PKG)
                                    onToast(if (it) "已开启自动修复" else "已关闭自动修复")
                                },
                                onReseed = {
                                    // 重新洗牌 = 新一轮：种子换掉，同时把右滑进度清零
                                    Prefs.bumpFgResetSeq(ctx)
                                    Prefs.setFgSeed(ctx, System.currentTimeMillis())
                                    CfgBridge.push(ctx, Prefs.FG_PKG)
                                    onToast("已重新洗牌")
                                },
                                onRefresh = {
                                    StatusReceiver.request(ctx, Prefs.FG_PKG)
                                    tick++
                                    onToast("已刷新")
                                },
                                onToast = onToast,
                            )

                            Screen.Security -> SecurityPage(
                                debug = debug,
                                onRefresh = {
                                    tick++
                                    onToast("已刷新")
                                },
                                onOpenShortcutBlock = { screen = Screen.ShortcutBlock },
                                onOpenGpPushCmds = { screen = Screen.GpPushCmds },
                                group = secGroup,
                                onGroup = { secGroup = it },
                                onToast = onToast,
                            )

                            Screen.ShortcutBlock -> ShortcutBlockPage(onToast = onToast)

                            Screen.GpPushCmds -> GpPushCmdsPage(onToast = onToast)

                            Screen.CamdPanel -> CamdPanelPage(version = version, onToast = onToast)

                            Screen.Dsnap -> DsnapPage(version = version, onToast = onToast)

                            Screen.Freeform -> FreeformPage(
                                onToast = onToast,
                            )

                            Screen.Desktop -> DesktopPage(
                                onToast = onToast,
                            )

                            Screen.Settings -> SettingsPage(
                                themeMode = themeMode,
                                onThemeModeChange = onThemeModeChange,
                                monet = monet, onMonetChange = onMonetChange,
                                pureBlack = pureBlack, onPureBlackChange = onPureBlackChange,
                                blurLevel = blurLevel, onBlurChange = onBlurChange,
                                uiScale = uiScale, onUiScaleChange = onUiScaleChange,
                                showHints = showHints, onShowHintsChange = onShowHintsChange,
                                themeStyle = themeStyle, onThemeStyleChange = onThemeStyleChange,
                                colorStandard = colorStandard, onColorStandardChange = onColorStandardChange,
                                accentColor = accentColor, onAccentColorChange = onAccentColorChange,
                                uiStyle = uiStyle, onUiStyleChange = onUiStyleChange,
                                hideIcon = hideIcon,
                                onHideIconChange = {
                                    hideIcon = it
                                    Prefs.setHideIcon(ctx, it)
                                    onToast(if (it) "桌面图标已隐藏，可从 LSPosed 管理器进入本页" else "桌面图标已恢复")
                                },
                                barStyle = barStyle,
                                onBarStyleChange = {
                                    barStyle = it
                                    Prefs.setBottomBarStyle(ctx, it)
                                    onToast("底栏已切换为「" + barStyleName(it) + "」")
                                },
                                topBarStyle = topBarStyle,
                                onTopBarStyleChange = {
                                    topBarStyle = it
                                    Prefs.setTopBarStyle(ctx, it)
                                    onToast("顶栏已切换为「" + topBarStyleName(it) + "」")
                                },
                                onShowGuide = onShowGuide,
                                onForceStop = onForceStop,
                                onForceStopPkg = onForceStopPkg,
                                onRestartSelf = onRestartSelf,
                            )

                            Screen.About -> AboutPage(debug = debug, version = version)

                        }
                    }
                }
            }

            // 底部导航：主页 / 设置 / 关于。三种样式共用同一个 backdrop 来源层
            // （也就是上面的「页面层」），所以滚动内容从它下面经过时，
            // 都是同一套「模糊 + 折射」的液体玻璃质感；
            // 样式只决定形状、贴底 / 悬浮、模糊与折射强度。
            CamelliaBottomBar(
                style = barStyle,
                backdrop = backdrop,
                // 从主页卡片进入的二三级页（互传、相册、安全…）仍然高亮「主页」
                selectedIndex = when (screen) {
                    Screen.Settings -> 1
                    Screen.About -> 2
                    else -> 0
                },
                onSelect = { index ->
                    secGroup = ""
                    screen = when (index) {
                        1 -> Screen.Settings
                        2 -> Screen.About
                        else -> Screen.Home
                    }
                },
                onHeightChange = { navBarHeightPx = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // 弹窗必须挂在 Miuix Scaffold 的 content 内部：Miuix 的 OverlayDialog 不是独立 Window，
        // 而是把内容投进 Scaffold 通过 LocalDialogStates 提供的宿主编排层去渲染。
        // 写在 Scaffold 外面读到的是默认空列表，点按钮界面上不会有任何反应。
        RestartAppDialog(
            show = showRestart,
            onDismiss = { showRestart = false },
            onConfirm = { targets ->
                showRestart = false
                // 「重新载入本页」不需要 root；其余目标一次性丢给同一个 su 会话去 kill，
                // 避免每个应用各 fork 一个 root shell（HyperCeiler 是一个包一次 killApps，这里合并）
                if (targets.any { it.self }) onRestartSelf()
                onKillApps(targets.filterNot { it.self }.map { it.pkg })
            },
        )
    }
}

// ---------------- 主页：入口列表 ----------------

@Composable
private fun HomePage(
    version: String,
    mtChip: String,
    mtTone: Tone,
    fgChip: String,
    fgTone: Tone,
    ulChip: String,
    ulTone: Tone,
    debug: Boolean,
    onDebugChange: (Boolean) -> Unit,
    onOpen: (Screen) -> Unit,
    onOpenTo: (Screen, String) -> Unit,
) {
    val c = CamelliaTheme.colors
    val ctx = LocalContext.current

    // ── 搜索栏直接摆在主页顶部（仿 HyperCeiler）：输入即过滤，结果就地列出，
    //    不再单独开一个「搜索功能」二级页 ──
    var q by remember { mutableStateOf("") }
    val allHits = remember { searchIndex() }
    val key = q.trim()
    val hits = remember(key) {
        if (key.isEmpty()) {
            emptyList()
        } else {
            allHits.filter {
                it.title.contains(key, ignoreCase = true) ||
                    it.desc.contains(key, ignoreCase = true) ||
                    it.kw.contains(key, ignoreCase = true)
            }
        }
    }

    SectionCard {
        TextField(
            value = q,
            onValueChange = { q = it },
            label = "搜索功能 · 关键词，如：倒计时、侧边栏、模糊",
        )
    }

    // 搜索态：结果直接替代主页其余内容
    if (key.isNotEmpty()) {
        SmallTitle(text = "结果 ${hits.size} 项")
        if (hits.isEmpty()) {
            Note("没有找到与「$key」相关的功能，换个词试试。")
        } else {
            SectionCard {
                var first = true
                hits.forEach { e ->
                    if (!first) HorizontalDivider()
                    first = false
                    BasicComponent(
                        title = e.title,
                        summary = e.desc,
                        endActions = { Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null) },
                        onClick = { onOpenTo(e.screen, e.group) },
                    )
                }
            }
        }
        return
    }

    // 顶部品牌块：应用名 + 版本 + 两个目标的实时结论，整体状态一眼看完
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.brandSoft)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Camellia",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.onBrandSoft,
            )
            Spacer(Modifier.width(8.dp))
            Badge("v$version", Tone.Brand)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "小米互传 · 小米画报 · 手机管家的版本自适应模块",
            fontSize = 13.sp,
            color = c.onBrandSoft.copy(alpha = 0.72f),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("互传 $mtChip", mtTone)
            StatusChip("画报 $fgChip", fgTone)
        }
        Spacer(Modifier.height(8.dp))
        StatusChip("手机管家 $ulChip", ulTone)
    }

    SmallTitle(text = "目标应用")
    SectionCard {
        BasicComponent(
            title = "小米互传",
            summary = "收到文件后点通知「打开」的落点",
            startAction = { AppIcon(Prefs.TARGET_PKG, "互") },
            endActions = {
                StatusChip(mtChip, mtTone)
                Spacer(Modifier.width(8.dp))
                Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null)
            },
            onClick = { onOpen(Screen.MiShare) },
        )
        HorizontalDivider()
        BasicComponent(
            title = "小米画报",
            summary = "把指定目录同步进「自选图片」",
            startAction = { AppIcon(Prefs.FG_PKG, "报") },
            endActions = {
                StatusChip(fgChip, fgTone)
                Spacer(Modifier.width(8.dp))
                Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null)
            },
            onClick = { onOpen(Screen.Gallery) },
        )
        HorizontalDivider()
        BasicComponent(
            title = "手机管家",
            summary = "省电与电池 · 摄像助手 · 隐私安全 · 游戏/视频工具箱",
            startAction = { AppIcon(Prefs.SECURITY_PKG, "管") },
            endActions = {
                StatusChip(ulChip, ulTone)
                Spacer(Modifier.width(8.dp))
                Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null)
            },
            onClick = { onOpen(Screen.Security) },
        )
        HorizontalDivider()
        BasicComponent(
            title = "小窗增强",
            summary = "多小窗数量限制 · 应用小窗黑名单 · 通知下拉",
            startAction = { AppIcon("android", "窗") },
            endActions = {
                StatusChip("已集成", Tone.Ok)
                Spacer(Modifier.width(8.dp))
                Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null)
            },
            onClick = { onOpen(Screen.Freeform) },
        )
    }

    SmallTitle(text = "系统级")
    SectionCard {
        BasicComponent(
            title = "系统桌面修改",
            summary = "长按图标菜单「小窗」· 桌面增强（A + B 配套）",
            startAction = { AppIcon("com.miui.home", "桌") },
            endActions = {
                Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null)
            },
            onClick = { onOpen(Screen.Desktop) },
        )
        HorizontalDivider()
        // camd 的存活状态只能来自它自己写的快照：pid>0 且文件新鲜才算「在跑」
        val camdSt = remember { CamdRescue.status() }
        // 用户主动卸载过（停用标记在）：面板里要和「从没装过」区分开
        val camdCtx = LocalContext.current.applicationContext
        val camdOff = remember { Prefs.isCamdDisabled(camdCtx) }
        /* 暂停和卸载都会落 camdOff，区别只在状态文件：卸载会把它一起清掉，暂停不会。
         * 而且 camd 一停就不再更新那份快照，pid 会一直留在最后一帧 —— 所以 camdOff
         * 期间绝不能按 pid 判活，否则暂停后主页还挂着「运行中」。 */
        val camdPaused = camdOff && camdSt.isNotEmpty()
        val camdAlive = !camdOff && camdSt.isNotEmpty() && (camdSt["pid"]?.toLongOrNull() ?: 0L) > 0L
        BasicComponent(
            title = "系统界面/组件急救-camd",
            summary = "环境判定 · 行为 · 监控包 · 快照 · 卸载",
            startAction = { AppIcon("com.android.systemui", "c") },
            endActions = {
                StatusChip(
                    when {
                        camdAlive -> "运行中"
                        camdPaused -> "已暂停"
                        camdOff -> "已卸载"
                        else -> "未部署"
                    },
                    when {
                        camdAlive -> Tone.Ok
                        camdPaused -> Tone.Warn
                        camdOff -> Tone.Warn
                        else -> Tone.Bad
                    },
                )
                Spacer(Modifier.width(8.dp))
                Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null)
            },
            onClick = { onOpen(Screen.CamdPanel) },
        )
        HorizontalDivider()
        // 不在这张卡的 endActions 里放状态 chip：读快照要经过 su，
        // 首页同步跑一次 su 会让列表卡住一拍，状态留给二级页。
        BasicComponent(
            title = "定时快照备份",
            summary = "主题 / 字体 / 桌面布局 / 壁纸：定时自动存，最多 10 份",
            startAction = { AppIcon("android", "备") },
            endActions = {
                Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null)
            },
            onClick = { onOpen(Screen.Dsnap) },
        )
    }

    SmallTitle(text = "其他")
    SectionCard {
        SwitchPreference(
            title = "调试模式",
            summary = "关闭时目标进程不写调试日志，本页也只显示结论（默认）",
            checked = debug,
            onCheckedChange = onDebugChange,
        )
        HorizontalDivider()
        BasicComponent(
            title = "设置",
            summary = "主题模式 · 底栏样式 · 桌面图标",
            startAction = {
                Icon(
                    MiuixIcons.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            endActions = { Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null) },
            onClick = { onOpen(Screen.Settings) },
        )
    }

    Note(
        "小米互传：把「接收文件后点通知打开」的落点改成 MT 管理器（文档 / 音频 / 未知类型）。\n" +
            "小米画报：把指定目录里的图片同步进「自选图片」。\n" +
            "模块完全在本地运行，不联网、不收集任何数据。",
    )
}

// ---------------- 二级页：小米互传 ----------------

@Composable
private fun MiSharePage(
    mtTarget: String,
    mtView: StatusView,
    debug: Boolean,
    onMtTargetChange: (String) -> Unit,
    onForceStop: () -> Unit,
    onRefresh: () -> Unit,
    onToast: (String) -> Unit,
) {
    val ctx = LocalContext.current

    SmallTitle(text = "运行详情（每 3 秒刷新）")
    StatusCard(view = mtView, action = { Button(onClick = onRefresh) { Text("刷新") } })
    if (debug) {
        Note("调试模式已开启：上面的状态卡已展开命中方法与耗时明细，可直接截图反馈。")
    }

    SmallTitle(text = "打开方式")
    SectionCard {
        RadioButtonPreference(
            title = "MT 管理器",
            summary = Prefs.MT_DEFAULT + Prefs.statusTag(ctx, Prefs.MT_DEFAULT),
            selected = mtTarget == Prefs.MT_DEFAULT,
            onClick = { onMtTargetChange(Prefs.MT_DEFAULT) },
        )
        RadioButtonPreference(
            title = "MT 管理器 Canary",
            summary = Prefs.MT_CANARY + Prefs.statusTag(ctx, Prefs.MT_CANARY),
            selected = mtTarget == Prefs.MT_CANARY,
            onClick = { onMtTargetChange(Prefs.MT_CANARY) },
        )
    }
    Note(
        "走 MT 管理器：文档 / 音频 / 未知类型。\n" +
            "保持原样：图片 / 视频 / 联系人。\n" +
            "若所选版本是缺少跳转入口的分身或克隆版，会自动改用 MT 管理器正式版。",
    )

    SmallTitle(text = "使设置生效")
    SectionCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "小米互传只在启动时读取一次模块配置。改完设置后点下面的按钮，" +
                    "下次打开互传即按新设置工作。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onForceStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("强制结束小米互传")
            }
        }
    }

    SmallTitle(text = "使用步骤")
    Note(
        "1. 在 LSPosed 中启用本模块，作用域勾选【小米互传】（画报同步另需勾选【小米画报】）\n" +
            "2. 选好打开方式，点「强制结束小米互传」\n" +
            "3. 重新打开互传，接收文件，点通知里的「打开」",
    )

    SmallTitle(text = "功能解锁")
    val mishareVer = remember { Prefs.miShareVersionCode(ctx) }
    val turboOk = Prefs.isMiShareTurboSupported(ctx)
    val airOk = Prefs.isMiShareAirDropSupported(ctx)
    var turboOn by remember { mutableStateOf(Prefs.isMiShareUnlockOn(ctx, Prefs.ID_UL_MISHARE_TURBO)) }
    var airOn by remember { mutableStateOf(Prefs.isMiShareUnlockOn(ctx, Prefs.ID_UL_MISHARE_AIRDROP)) }
    // 内核能力：本机没有 wonder 模块时，App 侧解锁只能造出「点了必失败」的假入口，
    // 所以这种情况直接把开关置灰，而不是让用户以为能用
    val airKernelOk = remember { Prefs.isKernelWonderSupported() }
    SectionCard {
        SwitchPreference(
            title = "极速传输（160M）",
            summary = when {
                mishareVer < 0 -> "未安装小米互传，无法解锁"
                !turboOk -> "当前版本（$mishareVer）没有该功能，需 5.0 以上"
                turboOn -> "已解锁：与同样支持的对端互传时自动启用高速通道"
                else -> "已关闭：交回小米官方策略判定"
            },
            checked = turboOn && turboOk,
            enabled = turboOk,
            onCheckedChange = {
                turboOn = it
                Prefs.setMiShareUnlockOn(ctx, Prefs.ID_UL_MISHARE_TURBO, it)
                onToast("已保存；强制结束互传后重新打开即生效")
            },
        )
        SwitchPreference(
            title = "苹果设备互联（AirDrop）",
            summary = when {
                mishareVer < 0 -> "未安装小米互传，无法解锁"
                !airOk -> "当前版本（$mishareVer）没有该功能，需 5.4 以上"
                !airKernelOk -> "本机内核不支持：缺少 wonder 模块，解锁也无实际作用"
                airOn -> "已解锁：首页显示「从苹果设备接收」入口"
                else -> "已关闭：不显示苹果设备入口"
            },
            checked = airOn && airOk && airKernelOk,
            enabled = airOk && airKernelOk,
            onCheckedChange = {
                airOn = it
                Prefs.setMiShareUnlockOn(ctx, Prefs.ID_UL_MISHARE_AIRDROP, it)
                onToast("已保存；强制结束互传后重新打开即生效")
            },
        )
    }
    if (mishareVer > 0) {
        Note(
            "当前互传版本：$mishareVer。解锁在运行时完成，不改 APK、不改系统属性；" +
                "关掉开关后强制结束互传再打开，即恢复原版行为。"
        )
    } else {
        Note("未检测到小米互传（${Prefs.MISHARE_PKG}），装上并勾选作用域后本区开关才会生效。")
    }
}

// ---------------- 二级页：小米画报 ----------------

@Composable
private fun GalleryPage(
    fgEnabled: Boolean,
    fgDir: String,
    fgCap: String,
    fgOrder: String,
    fgBlockAd: Boolean,
    fgLocalPhoto: Boolean,
    fgCurated: String,
    fgAutoHeal: Boolean,
    fgView: StatusView,
    debug: Boolean,
    onFgEnabledChange: (Boolean) -> Unit,
    onFgDirChange: (String) -> Unit,
    onSaveDir: () -> Unit,
    onResetDir: () -> Unit,
    onFgCapChange: (String) -> Unit,
    onSaveCap: () -> Unit,
    onFgOrderChange: (String) -> Unit,
    onFgBlockAdChange: (Boolean) -> Unit,
    onFgLocalPhotoChange: (Boolean) -> Unit,
    onFgCuratedChange: (String) -> Unit,
    onFgAutoHealChange: (Boolean) -> Unit,
    onReseed: () -> Unit,
    onRefresh: () -> Unit,
    onToast: (String) -> Unit,
) {
    val ctx = LocalContext.current

    SmallTitle(text = "同步状态")
    StatusCard(view = fgView, action = { Button(onClick = onRefresh) { Text("刷新") } })
    if (debug) {
        Note("调试模式已开启：上面的状态卡已展开同步目录、注入点与耗时明细。")
    }

    SmallTitle(text = "环境自检与修复")
    FgEnvHealCard(
        fgDir = fgDir,
        fgView = fgView,
        autoHeal = fgAutoHeal,
        onAutoHealChange = onFgAutoHealChange,
        onRefresh = onRefresh,
        onToast = onToast,
    )

    SmallTitle(text = "目录同步")
    SectionCard {
        SwitchPreference(
            title = "启用目录同步",
            summary = "把下面目录里的图片同步进画报「自选图片」",
            checked = fgEnabled,
            onCheckedChange = onFgEnabledChange,
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            TextField(
                value = fgDir,
                onValueChange = onFgDirChange,
                label = "同步目录",
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveDir) { Text("保存目录") }
                TextButton(text = "恢复默认", onClick = onResetDir)
            }
            Spacer(Modifier.height(18.dp))
            TextField(
                value = fgCap,
                onValueChange = onFgCapChange,
                label = "最多同步图片数",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onSaveCap) { Text("保存上限") }
        }
    }

    SmallTitle(text = "推广与右滑轮播")
    SectionCard {
        SwitchPreference(
            title = "禁用推广",
            summary = "锁屏画报不再请求、不再展示推广图；推广壁纸不进入轮播",
            checked = fgBlockAd,
            onCheckedChange = onFgBlockAdChange,
        )
        SwitchPreference(
            title = "右滑轮播图片接管",
            summary = "画报右滑轮播（left_lks）改用上面目录里的本地图片；锁屏壁纸轮播保持画报原生，不受影响",
            checked = fgLocalPhoto,
            onCheckedChange = onFgLocalPhotoChange,
        )
    }
    Note(
        "「右滑轮播图片接管」只替换画报右滑轮播（left_lks）用的图片池，不会改动锁屏壁纸轮播，" +
            "也不会把画报切成自选壁纸模式；目录需先开启上面的目录同步并有可用图片（jpg/jpeg/png/webp），" +
            "取不到可用图片时自动退回画报原图。"
    )

    // ---- 精简精选壁纸：按 scrb_items1 订阅分类勾选，只保留勾选的分类 ----
    // cats 行由 Fg 侧从画报 scrb_items1 表读出后写进 status，格式 id:名称|id:名称
    val catList = remember(fgView) {
        (statusValue(Prefs.readFgStatus(ctx), "cats") ?: "")
            .split('|')
            .mapNotNull { seg ->
                val i = seg.indexOf(':')
                if (i <= 0) null else seg.substring(0, i).trim() to seg.substring(i + 1).trim()
            }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
    }
    val curatedSet = fgCurated.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    // curated 为空 = 不过滤（全部保留）；非空 = 只保留集合内的分类
    val noFilter = curatedSet.isEmpty()
    // 画报订阅的分类可能有几十个，全平铺会把设置页淹掉。默认只露 8 项（已保留的优先），
    // 需要逐项调整时用搜索或「展开全部」。
    var catQuery by remember { mutableStateOf("") }
    var catAllExpanded by remember { mutableStateOf(false) }
    fun isCatKept(id: String): Boolean = noFilter || curatedSet.contains(id)
    val keptCatCount = if (noFilter) catList.size else catList.count { isCatKept(it.first) }
    if (catList.isNotEmpty()) {
        SmallTitle(text = "精简精选壁纸")
        SectionCard {
            Text(
                "只保留勾选的分类，未勾选的分类会从画报「精选壁纸」里移除。" +
                    "全部不勾选等于不过滤。共 ${catList.size} 个分类，已保留 $keptCatCount 个。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                TextField(
                    value = catQuery,
                    onValueChange = { catQuery = it },
                    label = "搜索分类",
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = if (catAllExpanded) "收起" else "展开全部 ${catList.size} 项",
                        onClick = {
                            catAllExpanded = !catAllExpanded
                            if (!catAllExpanded) catQuery = ""
                        },
                    )
                    if (catQuery.isNotEmpty() || catAllExpanded) {
                        TextButton(text = "恢复默认", onClick = { catQuery = "" })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            val cq = catQuery.trim()
            val matched = if (cq.isEmpty()) catList
            else catList.filter { (id, name) ->
                name.contains(cq, ignoreCase = true) || id.contains(cq, ignoreCase = true)
            }
            // 未展开时：已保留的排前面，最多露 8 项，避免长列表把页面撑爆
            val visibleCats = when {
                catAllExpanded || cq.isNotEmpty() -> matched
                else -> (matched.filter { isCatKept(it.first) } + matched.filterNot { isCatKept(it.first) })
                    .take(8)
            }
            visibleCats.forEach { (id, name) ->
                val kept = isCatKept(id)
                SwitchPreference(
                    title = name,
                    summary = if (kept) "保留" else "将移除",
                    checked = kept,
                    onCheckedChange = { on ->
                        // 从「全保留」切到「按分类保留」时，先把所有分类都加进来，
                        // 再按这次点击去掉/保留，避免第一次点一下就把其余全删光
                        val next = if (noFilter) catList.map { it.first }.toMutableSet()
                        else curatedSet.toMutableSet()
                        if (on) next.add(id) else next.remove(id)
                        val joined = if (next.size >= catList.size) "" else next.joinToString(",")
                        onFgCuratedChange(joined)
                    },
                )
            }
            if (visibleCats.isEmpty()) {
                Text(
                    "没有匹配的分类",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                )
            } else if (!catAllExpanded && cq.isEmpty() && matched.size > visibleCats.size) {
                Text(
                    "已折叠 ${matched.size - visibleCats.size} 项，点上方「展开全部」可查看调整",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                )
            }
        }
    }

    SmallTitle(text = "加载顺序")
    SectionCard {
        RadioButtonPreference(
            title = "顺序",
            summary = "最新加入的排在前面（画报默认）",
            selected = fgOrder == Prefs.ORDER_SEQ,
            onClick = { onFgOrderChange(Prefs.ORDER_SEQ) },
        )
        RadioButtonPreference(
            title = "倒序",
            summary = "最早加入的排在前面",
            selected = fgOrder == Prefs.ORDER_DESC,
            onClick = { onFgOrderChange(Prefs.ORDER_DESC) },
        )
        RadioButtonPreference(
            title = "随机",
            summary = "打乱顺序轮播，可随时重新洗牌",
            selected = fgOrder == Prefs.ORDER_RANDOM,
            onClick = { onFgOrderChange(Prefs.ORDER_RANDOM) },
        )
        if (fgOrder == Prefs.ORDER_RANDOM) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                TextButton(text = "重新随机", onClick = onReseed)
            }
        }
        Text(
            "顺序只影响画报「自选图片」列表的先后，不影响同步进哪些图片。",
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }

    SmallTitle(text = "打开小米画报")
    SectionCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Button(
                onClick = {
                    try {
                        val i = Intent()
                        i.setClassName(Prefs.FG_PKG, Prefs.FG_ACTIVITY)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    } catch (t: Throwable) {
                        onToast("打不开小米画报：${t.message}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("打开「自选图片」页")
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "首次使用先点一次，让画报进程加载模块并写入同步状态。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }

    Note(
        "目录变更后画报进程会被自动唤醒同步（新增入库、删除联动）。\n" +
            "若同步状态一直是「尚未获取」，先点上面的按钮进一次自选图片页。",
    )
}

// ---------------- 画报环境自检与一键修复 ----------------

/** 画报环境自检结果：以 root 实测为准，不依赖画报进程自报 */
private data class FgEnvDiag(
    val busy: Boolean = true,
    /** 画报进程上报的 readMediaImages， = 尚未上报 */
    val permOk: Boolean? = null,
    /** 同步目录里真实图片数（root find 数出来的），-1 = 读不到 */
    val realCount: Int = -1,
    /** MediaStore 里位于该目录的图片数，-1 = 查询失败 */
    val indexedCount: Int = -1,
    /** 画报进程本次同步看到的候选数 */
    val shownCount: Int = -1,
    val note: String = "",
)

/** 单引号包裹 shell 参数，路径含中文/空格也安全 */
private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

private fun fgNum(v: Int): String = if (v < 0) "—" else v.toString()

/** 由自检结果给出结论（文案 + 色调） */
private fun fgEnvVerdict(d: FgEnvDiag): Pair<String, Tone> {
    if (d.busy) {
        return "正在检测…" to Tone.Idle
    }
    if (d.note.isNotEmpty()) {
        return d.note to Tone.Warn
    }
    if (d.permOk == false) {
        return "画报缺少「照片和视频」读取权限" to Tone.Bad
    }
    if (d.realCount == 0) {
        return "同步目录里没有图片" to Tone.Warn
    }
    if (d.indexedCount >= 0 && d.realCount > 0 && d.indexedCount < d.realCount) {
        return "有 ${d.realCount - d.indexedCount} 张图未被系统媒体库索引" to Tone.Bad
    }
    if (d.shownCount == 0) {
        return "画报还没同步到图片" to Tone.Warn
    }
    return "环境正常" to Tone.Ok
}

/**
 * root 侧实测：目录真实图片数 + MediaStore 索引数。
 *
 * 画报进程自己看不到「未被索引的文件」（Android 13+ 分区存储下 dir.list()
 * 只返回已入库条目），所以这两个数必须由 App 用 root 去数，
 * 才能区分「目录真的没图」和「有图但没被索引」。
 */
private suspend fun probeFgEnv(ctx: android.content.Context, dir: String): FgEnvDiag =
    withContext(Dispatchers.IO) {
        val raw = Prefs.readFgStatus(ctx)
        // 权限用 root 直查 PackageManager 状态：权威，且不依赖画报进程是否上报过 perm。
        // （画报没重启加载新版模块时 perm 行是初始值 "-"，靠它判断会误报「缺少权限」）
        val permDump = suOutput(
            "dumpsys package " + Prefs.FG_PKG +
                " 2>/dev/null | grep -m1 'READ_MEDIA_IMAGES: granted='"
        )
        val perm: Boolean? = when {
            permDump == null -> null
            permDump.contains("granted=true") -> true
            permDump.contains("granted=false") -> false
            else -> null
        }
        val shown = statusValue(raw ?: "", "cand")?.toIntOrNull() ?: -1
        val d = dir.trim().trimEnd('/')
        if (d.isEmpty()) {
            return@withContext FgEnvDiag(
                busy = false,
                permOk = perm,
                shownCount = shown,
                note = "同步目录未配置",
            )
        }
        val real = suOutput(
            "find " + shq(d) + " -maxdepth 1 -type f \\( -iname '*.jpg' -o -iname '*.jpeg'" +
                " -o -iname '*.png' -o -iname '*.webp' \\) 2>/dev/null | wc -l"
        )?.trim()?.toIntOrNull() ?: -1
        val indexed = suOutput(
            "content query --uri content://media/external/images/media --projection _data" +
                " 2>/dev/null | grep -cF " + shq(d + "/")
        )?.trim()?.toIntOrNull() ?: -1
        FgEnvDiag(
            busy = false,
            permOk = perm,
            realCount = real,
            indexedCount = indexed,
            shownCount = shown,
            note = if (real < 0) "没有 root 权限，无法读取目录" else "",
        )
    }

/**
 * 一键修复：补授权限 + 对目录内全部图片触发一次媒体扫描。
 *
 * 「图片在目录里但画报看不到」几乎都是「未被 MediaStore 索引」导致的，
 * 逐个扫一遍就能让画报的 dir.list() 重新看到它们。
 */
private suspend fun healFgEnv(dir: String): String = withContext(Dispatchers.IO) {
    val d = dir.trim().trimEnd('/')
    if (d.isEmpty()) {
        return@withContext "同步目录未配置，无法修复"
    }
    val cmd = "pm grant " + Prefs.FG_PKG + " android.permission.READ_MEDIA_IMAGES; " +
        "pm grant " + Prefs.FG_PKG + " android.permission.READ_MEDIA_VISUAL_USER_SELECTED; " +
        "find " + shq(d) + " -maxdepth 1 -type f \\( -iname '*.jpg' -o -iname '*.jpeg'" +
        " -o -iname '*.png' -o -iname '*.webp' \\) -print0 2>/dev/null | " +
        "xargs -0 -P 6 -I@ am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE" +
        " -d \"file://@\" >/dev/null 2>&1; echo done"
    val out = suOutput(cmd)
    if (out == null) "没有 root 权限，无法自动修复" else "已授权并触发媒体扫描，重开画报即可看到图片"
}

@Composable
private fun FgEnvHealCard(
    fgDir: String,
    fgView: StatusView,
    autoHeal: Boolean,
    onAutoHealChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onToast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var diag by remember { mutableStateOf(FgEnvDiag()) }
    var fixing by remember { mutableStateOf(false) }
    var probeTick by remember { mutableIntStateOf(0) }
    // 同一轮异常只自动修一次，避免每次重组都把整个目录重扫一遍
    var autoHealedKey by remember { mutableStateOf("") }

    LaunchedEffect(fgDir, fgView, probeTick) {
        diag = FgEnvDiag(busy = true)
        diag = probeFgEnv(ctx, fgDir)
    }

    val verdict = fgEnvVerdict(diag)
    val abnormal = !diag.busy && verdict.second != Tone.Ok && diag.note.isEmpty()

    LaunchedEffect(abnormal, autoHeal, verdict.first, fgDir) {
        if (!autoHeal || !abnormal) {
            return@LaunchedEffect
        }
        val key = verdict.first + "|" + fgDir
        if (key == autoHealedKey) {
            return@LaunchedEffect
        }
        autoHealedKey = key
        fixing = true
        val r = healFgEnv(fgDir)
        diag = probeFgEnv(ctx, fgDir)
        fixing = false
        onToast(r)
        onRefresh()
    }

    SectionCard {
        Column(modifier = Modifier.padding(16.dp)) {
            StatusChip(text = verdict.first, tone = verdict.second)
            Spacer(Modifier.height(12.dp))
            Text(
                "目录图片 " + fgNum(diag.realCount) +
                    "   已索引 " + fgNum(diag.indexedCount) +
                    "   画报可见 " + fgNum(diag.shownCount),
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!fixing && !diag.busy) {
                            fixing = true
                            scope.launch {
                                val r = healFgEnv(fgDir)
                                diag = probeFgEnv(ctx, fgDir)
                                fixing = false
                                onToast(r)
                                onRefresh()
                            }
                        }
                    },
                    enabled = !fixing && !diag.busy,
                ) {
                    Text(if (fixing) "修复中…" else "一键修复")
                }
                TextButton(text = "重新检测", onClick = { probeTick++ })
            }
        }
        SwitchPreference(
            title = "自动修复",
            summary = "进入本页时若检出权限缺失或图片未被索引，自动授权并触发媒体扫描",
            checked = autoHeal,
            onCheckedChange = onAutoHealChange,
        )
    }
    Note(
        "「一键修复」会补授画报的照片读取权限，并对同步目录里的图片逐个触发系统媒体扫描。\n" +
            "画报只能看到被系统媒体库索引过的文件，所以目录里有图、画报里却没有时，先点一次修复。",
    )
}

// ---------------- 二级页：关于与鸣谢 ----------------

// ---------------- 二级页：手机管家 ----------------

/**
 * 手机管家（安全服务）规则页。
 *
 * 规则表由目标进程侧的 Unlock 定义（按 HyperCeiler 逐项复刻），
 * 本页只做三件事：列规则、写开关、读回目标进程写出的定位结果。
 * 全部开关默认关闭 —— 不主动改变用户手机管家的既有行为。
 */
@Composable
private fun SecurityPage(
    debug: Boolean,
    onRefresh: () -> Unit,
    onOpenShortcutBlock: () -> Unit,
    onOpenGpPushCmds: () -> Unit,
    group: String,
    onGroup: (String) -> Unit,
    onToast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    val raw = remember(tick, debug) { Prefs.readUlStatus(ctx) }
    val view = remember(raw, debug) { ulStatusView(raw, debug) }
    // sidebar_bg 已在上方拥有 1:1 复刻的专属区块（开关 + 模糊程度 + 调色盘），规则列表里不再重复出现
    val rows = remember { Unlock.ruleRows().filter { it[0] != "sidebar_bg" && it[0] != "sidebar_limit" } }
    val onMap = remember(tick) {
        mutableStateMapOf<String, Boolean>().apply {
            // 带默认值：gp_* 三条从未配置时应显示为「开」，与 hook 侧判定一致
            rows.forEach { r -> put(r[0], Prefs.isUlOn(ctx, r[0], Unlock.ruleDefOn(r[0]))) }
        }
    }
    // 识曲 App 选择（字符串配置，和布尔开关分开存）
    var bgmApp by remember { mutableStateOf(Prefs.getUlApp(ctx)) }
    val enabledCount = onMap.count { it.value }
    // 原生已支持：模块在手机管家进程内影子采样到的结果，经状态文件回传
    val natSame = remember(raw) { ulNativeSame(raw) }

    StatusCard(
        view = view,
        action = { TextButton(text = "刷新", onClick = onRefresh) },
    )

    // 「大类列表 ↔ 大类详情」两次切换沿用全局的横向滑入 + 淡入淡出，
    // 否则点大类时内容会瞬间换掉，和进二级页的观感不一致。
    AnimatedContent(
        targetState = group,
        transitionSpec = {
            // 空 → 某大类算「前进」；返回大类列表算「后退」，方向相反
            val forward = targetState.isNotEmpty()
            val slide = tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)
            val fade = tween<Float>(durationMillis = 220)
            (slideInHorizontally(slide) { if (forward) it / 6 else -it / 6 } + fadeIn(fade))
                .togetherWith(
                    slideOutHorizontally(slide) { if (forward) -it / 6 else it / 6 } + fadeOut(fade),
                )
        },
        label = "secgroup",
    ) { g ->
        Column(modifier = Modifier.fillMaxWidth()) {
            if (g.isEmpty()) {
                // ① 第一级：功能大类列表
                SmallTitle(
                    if (natSame.isEmpty()) {
                        "功能大类（已启用 $enabledCount / ${rows.size} 项规则）"
                    } else {
                        "功能大类（已启用 $enabledCount / ${rows.size} 项规则 · 原生已支持 ${natSame.size}）"
                    },
                )
                SectionCard {
                    var first = true
                    Unlock.groupKeys().forEach { key ->
                        val items = rows.filter { it[1] == key }
                        if (items.isEmpty()) {
                            return@forEach
                        }
                        if (!first) {
                            HorizontalDivider()
                        }
                        first = false
                        val on = items.count { onMap[it[0]] == true }
                        BasicComponent(
                            title = Unlock.groupLabel(key),
                            summary = "${Unlock.groupDesc(key)} · 已启用 $on/${items.size}",
                            endActions = { Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null) },
                            onClick = { onGroup(key) },
                        )
                    }
                }
                Note(
                    "先在上面选功能大类，再进入对应的开关列表。\n\n" +
                        "「系统安全组件」三条防护（应用清单上传 / 设备状态上报 / 云端指令拦截）默认开启，" +
                        "其余规则默认关闭。\n" +
                        "改动后：安全组件三条需重启 com.miui.guardprovider，其余规则需重启手机管家；" +
                        "也可以直接用下方的「重启应用」面板一次性处理。",
                )
                SmallTitle(text = "操作")
                SectionCard {
                    BasicComponent(
                        title = "结束手机管家",
                        summary = "改动在下次启动手机管家时生效",
                        onClick = { forceStopSecurity(ctx) },
                    )
                }
            } else {
                // ② 第二级：某个大类下的具体开关
                // 「全局侧边栏背景自定义」的详细设置跟随分组顺序摆放：落在「全局侧边栏」大类最前面
                if (g == Unlock.G_SIDEBAR) {
                    SidebarBackgroundSection()
                }
                val allItems = rows.filter { it[1] == g }
                // 「全局侧边栏」大类里 sidebar_limit / sidebar_bg 由上面的 SidebarBackgroundSection
                // 承载（带数量滑块与调色盘），列表里不再重复列一遍
                val items = if (g == Unlock.G_SIDEBAR) {
                    allItems.filter { it[0] != "sidebar_limit" && it[0] != "sidebar_bg" }
                } else {
                    allItems
                }
                SmallTitle(
                    "${Unlock.groupLabel(g)}（已启用 " +
                        "${allItems.count { onMap[it[0]] == true }} / ${allItems.size}）",
                )
                SectionCard {
                    items.forEachIndexed { i, r ->
                        val id = r[0]
                        val nat = natSame[id]
                        val natively = nat != null
                        if (i > 0) {
                            HorizontalDivider()
                        }
                        SwitchPreference(
                            title = r[2],
                            summary = when {
                                natively -> "原机型已支持（依据 $nat，开启不会改变行为）"
                                debug -> ulItemSummary(r)
                                else -> null
                            },
                            checked = onMap[id] == true,
                            enabled = !natively,
                            onCheckedChange = { v ->
                                onMap[id] = v
                                Prefs.setUlOn(ctx, id, v)
                                Toast.makeText(
                                    ctx,
                                    when {
                                        // 系统安全组件（gp_*）挂在 guardprovider 进程，重启对象不同
                                        r[0].startsWith("gp_") && v -> "已开启「${r[2]}」，重启系统安全组件后生效"
                                        r[0].startsWith("gp_") -> "已关闭「${r[2]}」，重启系统安全组件后生效"
                                        v -> "已开启「${r[2]}」，重启手机管家后生效"
                                        else -> "已关闭「${r[2]}」"
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                        // 「侧边栏快捷方式面板」打开后，紧接着给出关键词屏蔽管理的入口（独立页面）
                        if (id == "sidebar_shortcut" && onMap[id] == true) {
                            HorizontalDivider()
                            val gN = splitScKws(Prefs.getSidebarScBlock(ctx)).size
                            val aN = parseScAppRules(Prefs.getSidebarScBlockApp(ctx))
                                .count { it.kws.isNotEmpty() }
                            BasicComponent(
                                title = "屏蔽关键词规则",
                                summary = if (gN == 0 && aN == 0) {
                                    "未设置 · 点此添加全局 / 应用专属屏蔽"
                                } else {
                                    "全局 $gN 个关键词 · $aN 个应用专属"
                                },
                                endActions = { Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null) },
                                onClick = onOpenShortcutBlock,
                            )
                        }
                        // 「拦截云端下发指令」打开后，紧接着给出 10 条指令的逐条开关
                        if (id == "gp_push" && onMap[id] == true) {
                            HorizontalDivider()
                            // 直读本地 prefs（与列表其余开关一致），不走 Unlock.gpPushSubOn：
                            // 后者带 1.5s TTL 缓存且优先跨进程配置源，刚改完子开关时 summary 会滞后。
                            val gpcOn = Unlock.GP_PUSH_SUB_CMDS.count {
                                Prefs.isUlOn(ctx, Unlock.gpPushSubId(it), true)
                            }
                            BasicComponent(
                                title = "指令拦截明细",
                                summary = if (gpcOn == 0) {
                                    "当前全部放行 · 点此恢复拦截"
                                } else {
                                    "拦截 $gpcOn/${Unlock.GP_PUSH_SUB_CMDS.size} 条 · 点此逐条开关"
                                },
                                endActions = { Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null) },
                                onClick = onOpenGpPushCmds,
                            )
                        }
                        // 「识别 BGM 改用第三方识曲」打开后，紧接着给出识曲 App 选择器
                        if (id == "video_bgm_app" && onMap[id] == true) {
                            HorizontalDivider()
                            BgmAppChooser(
                                current = bgmApp,
                                onPick = { v ->
                                    bgmApp = v
                                    Prefs.setUlApp(ctx, v)
                                    Toast.makeText(
                                        ctx,
                                        "识曲 App 已切换为「${bgmAppLabel(v)}」",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    }
                }
                Note(Unlock.groupDesc(g))

                if (natSame.isNotEmpty()) {
                    Note(
                        "灰色项 = 本机型 / 本版本原生就支持该功能，开不开都一样，因此置灰停用。判定方式有两种：\n" +
                            "· 返回值对比：在手机管家进程内影子调用一次原方法，原生返回值与解锁目标值一致即判定已支持；\n" +
                            "· 设备能力判据：跳过执行 / 界面注入型规则（如杜比全景声）本身没有可比对的返回值，" +
                            "改按机型能力判定。\n" +
                            "两种判定都只做一次，不会重复执行原逻辑；判不出来的保持可切换，不会误置灰。",
                    )
                }

                SmallTitle(text = "操作")
                SectionCard {
                    BasicComponent(
                        title = "结束手机管家",
                        summary = "改动在下次启动手机管家时生效",
                        onClick = { forceStopSecurity(ctx) },
                    )
                }
            }
        }
    }

}

// ---------------- 视频工具箱「识别 BGM」识曲 App 选择 ----------------

/** 识曲 App 取值 → 展示名 */
private fun bgmAppLabel(v: String?): String = when (v) {
    Unlock.BGM_APP_NETEASE -> "网易云音乐"
    Unlock.BGM_APP_KUGOU -> "酷狗音乐"
    Unlock.BGM_APP_KUGOU_LITE -> "酷狗音乐极速版"
    Unlock.BGM_APP_QQ -> "QQ 音乐"
    else -> "小米音乐"
}

/** 识曲 App 取值 → 包名（只用于「是否已安装」提示，不作为跳转依据） */
private fun bgmAppPkg(v: String?): String? = when (v) {
    Unlock.BGM_APP_MIUI -> "com.miui.player"
    Unlock.BGM_APP_NETEASE -> "com.netease.cloudmusic"
    Unlock.BGM_APP_KUGOU -> "com.kugou.android"
    Unlock.BGM_APP_KUGOU_LITE -> "com.kugou.android.lite"
    Unlock.BGM_APP_QQ -> "com.tencent.qqmusic"
    else -> null
}

private fun bgmAppSummary(v: String?, installed: Boolean): String {
    val base = when (v) {
        Unlock.BGM_APP_MIUI -> "保持原生行为：点「识别 BGM」仍打开小米音乐的识曲页"
        Unlock.BGM_APP_NETEASE -> "小窗打开网易云音乐听歌识曲"
        Unlock.BGM_APP_KUGOU -> "小窗打开酷狗音乐的识曲页"
        Unlock.BGM_APP_KUGOU_LITE -> "小窗打开酷狗音乐极速版的识曲页"
        Unlock.BGM_APP_QQ -> "小窗打开 QQ 音乐的识曲页"
        else -> ""
    }
    return if (installed) base else "$base（未安装：选中后会被跳过并回退到小米音乐）"
}

/**
 * 识曲 App 选择器。
 *
 * 取值只作用于「识别 BGM」的跳转目标；未安装的选项不会让点击没反应 ——
 * 目标进程会依次试候选入口，全部失败时回退小米音乐原流程。
 */
@Composable
private fun BgmAppChooser(current: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        Text("识曲 App", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "决定点「识别 BGM」时打开哪个 App。改完即时生效，不用重启手机管家。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
    // 顺序即推荐顺序，小米音乐放最后表示「不接管」
    listOf(
        Unlock.BGM_APP_NETEASE,
        Unlock.BGM_APP_KUGOU,
        Unlock.BGM_APP_KUGOU_LITE,
        Unlock.BGM_APP_QQ,
        Unlock.BGM_APP_MIUI,
    ).forEach { v ->
        val pkg = bgmAppPkg(v)
        val installed = pkg == null || Prefs.isInstalled(ctx, pkg)
        RadioButtonPreference(
            title = bgmAppLabel(v),
            summary = bgmAppSummary(v, installed),
            selected = current == v,
            onClick = { if (current != v) onPick(v) },
        )
    }
}

// ---------------- 快捷方式屏蔽：关键词规则管理（独立页） ----------------

/**
 * 一条系统快捷方式：包名 + id + 标题。
 *
 * <p>标题取 ShortcutService 里保存的 shortLabel（取不到回退 id），也就是侧边栏面板 /
 * 桌面长按菜单里真正显示的那行字。拦截判据与它一致，所以「界面上显示已屏蔽」就等于
 * 「面板里看不到这条」。</p>
 */
private class ScItem(val pkg: String, val id: String, val label: String)

/** 一个有快捷方式的应用 */
private class ScApp(val pkg: String, val label: String, val items: List<ScItem>)

/** 应用专属规则行：包名或应用名 = 关键词1,关键词2 */
private class ScAppRule(val key: String, val kws: MutableList<String>)

/** 关键词串 → 关键词表（逗号 / 顿号 / 分号 / 空白分隔，保留原始大小写，去重忽略大小写）。 */
private fun splitScKws(raw: String?): MutableList<String> {
    val out = mutableListOf<String>()
    if (raw.isNullOrBlank()) return out
    for (s in raw.split(Regex("[,，、;；\\s]+"))) {
        val k = s.trim()
        if (k.isEmpty()) continue
        if (out.none { it.equals(k, ignoreCase = true) }) out.add(k)
    }
    return out
}

/** 应用专属规则串 → 规则行表（换行或分号分隔多条）。 */
private fun parseScAppRules(raw: String?): MutableList<ScAppRule> {
    val out = mutableListOf<ScAppRule>()
    if (raw.isNullOrBlank()) return out
    for (line in raw.split(Regex("[\\r\\n;；]+"))) {
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim()
        if (key.isEmpty()) continue
        out.add(ScAppRule(key, splitScKws(line.substring(eq + 1))))
    }
    return out
}

/** 规则行表 → 写回配置的字符串（没有关键词的规则自动丢弃，避免留下空行）。 */
private fun dumpScAppRules(rules: List<ScAppRule>): String =
    rules.filter { it.kws.isNotEmpty() }
        .joinToString("\n") { it.key + " = " + it.kws.joinToString(",") }

/** 该应用对应的规则行：标识命中包名或应用名任一即算（与 hook 侧 appKeywords 一致）。 */
private fun scRuleOf(rules: List<ScAppRule>, app: ScApp): ScAppRule? =
    rules.firstOrNull {
        it.key.equals(app.pkg, ignoreCase = true) || it.key.equals(app.label, ignoreCase = true)
    }

/** 命中判据：与 hook 侧 ShortcutBlock 一致 —— 标题 + id 拼接后做忽略大小写的子串匹配。 */
private fun scHit(label: String, id: String, kws: List<String>): String? {
    if (kws.isEmpty()) return null
    val hay = (label + "\u0000" + id).lowercase()
    for (k in kws) {
        val kk = k.trim().lowercase()
        if (kk.isNotEmpty() && hay.contains(kk)) return k
    }
    return null
}

/** root 执行命令读 stdout；没有 su 或未授权时返回 null。 */
private fun suOutput(cmd: String): String? = try {
    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
    val txt = p.inputStream.bufferedReader().use { it.readText() }
    p.waitFor()
    txt
} catch (t: Throwable) {
    null
}

/**
 * 执行 su 命令，返回「退出码 + 合流输出」。
 *
 * <p>与 [suOutput] 的差别是把 stderr 一起收回来、并把退出码暴露出来。手动执行
 * 按钮必须走这个：su 未授权、SELinux 拦截、命令不存在这几种失败全都只往 stderr
 * 写一行，只读 stdout 就只剩空字符串，界面于是弹出「已执行（无输出）」，看上去
 * 像一秒成功，实际系统一点没动。</p>
 */
private fun suExec(cmd: String): Pair<Int, String> = try {
    val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
    val txt = p.inputStream.bufferedReader().use { it.readText() }
    val code = p.waitFor()
    code to txt
} catch (t: Throwable) {
    -1 to ("无法执行 su：" + (t.message ?: t.toString()))
}

/**
 * 读出「有快捷方式的应用 + 它们的快捷方式」；读不到（无 root）返回 null。
 *
 * <p>设置页是普通 App，ShortcutManager.getShortcuts(pkg) 只肯给自家应用的快捷方式，
 * 想看别家的只能走系统服务的可读文本视图：su -c dumpsys shortcut。输出按
 * 「Package: <pkg>」分段，段内每条快捷方式是「ShortcutInfo {id=<id>, ...}}」后跟一行
 * 「shortLabel=<标题>, resId=...」，按这个结构逐行扫即可。</p>
 *
 * <p>注意：这里列的是 ShortcutService 管的快捷方式（应用运行期注册的动态 / 固定快捷方式），
 * manifest 里静态声明的不会出现在这份输出里 —— 那些仍然可以用关键词匹配挡掉。</p>
 */
private fun loadScApps(ctx: Context): List<ScApp>? {
    val txt = suOutput("dumpsys shortcut")
    if (txt.isNullOrBlank()) return null
    val map = LinkedHashMap<String, MutableList<ScItem>>()
    var pkg: String? = null
    var id: String? = null
    for (raw in txt.lineSequence()) {
        val t = raw.trim()
        if (t.startsWith("Package: ")) {
            pkg = t.removePrefix("Package: ").substringBefore(' ').trim()
            id = null
            continue
        }
        if (t.startsWith("ShortcutInfo {id=")) {
            id = t.removePrefix("ShortcutInfo {id=").substringBefore(',').trim()
            continue
        }
        if (t.startsWith("shortLabel=")) {
            val pid = id
            val pp = pkg
            if (pid.isNullOrEmpty() || pp.isNullOrEmpty()) continue
            var lab = t.removePrefix("shortLabel=").substringBeforeLast(", resId=").trim()
            if (lab.isEmpty()) lab = pid
            map.getOrPut(pp) { mutableListOf() }.add(ScItem(pp, pid, lab))
            id = null
        }
    }
    return map.entries.map { e ->
        ScApp(e.key, ShortcutBlock.appLabelOf(ctx, e.key) ?: e.key, e.value)
    }.sortedBy { it.label }
}

/**
 * 「gp_push 指令拦截明细」独立管理页：10 条云端下发指令的逐条拦截开关。
 *
 * <p>本体总开关是 G_GUARD 组里的 gp_push（拦不拦下发通道）；本页只决定「哪几条指令仍然拦」，
 * 对齐上游 BlockPushCommand 的 block_push_command_&lt;cmd&gt; 逐条开关。</p>
 *
 * <p>这些 gp_push_&lt;cmd&gt; 刻意不在 Unlock 的 RULES/SPECIALS 里（规则表单位是「锚点 + hook 安装」，
 * 子开关只是同一条 hook 内的运行时判定），因此：</p>
 * <ul>
 *   <li>配置由 {@code Prefs.ulSidecarBody} 单独补写进侧车——目标进程读不到模块 prefs；</li>
 *   <li>默认值为 true（与 gp_push 同口径），无配置即拦截；</li>
 *   <li>侧车每 5s 轮询 + cfgGen 代际探测，切换后最长 5s 在 guardprovider 进程生效。</li>
 * </ul>
 */
@Composable
private fun GpPushCmdsPage(onToast: (String) -> Unit) {
    val ctx = LocalContext.current

    // 子开关不进规则表，没有「其他写入方」的可能，进页面读一次快照，
    // 之后只由本页的点击维护，避免 remember 里反复读磁盘。
    // 用三参 isUlOn 直读本地 prefs 并显式给默认 true —— 与 gp_push 本体「未配置视为开启」
    // 口径一致；若用单参 isUlOn（默认 false），没动过的子项会显示成全部关闭。
    var pairs by remember {
        mutableStateOf(
            Unlock.GP_PUSH_SUB_CMDS.map {
                it to Prefs.isUlOn(ctx, Unlock.gpPushSubId(it), true)
            },
        )
    }

    val onCount = pairs.count { it.second }
    val allOn = pairs.isNotEmpty() && onCount == pairs.size

    val apply: (List<String>, Boolean) -> Unit = { cmds, on ->
        for (cmd in cmds) {
            val id = Unlock.gpPushSubId(cmd)
            if (id != null) Prefs.setUlOn(ctx, id, on)
        }
        pairs = pairs.map { if (cmds.contains(it.first)) it.first to on else it }
    }

    SmallTitle(text = "批量")

    SectionCard {
        BasicComponent(
            title = if (allOn) "全部放行" else "全部拦截",
            summary = if (allOn) {
                "10 条指令当前全部拦截，点此一次性放行"
            } else {
                "已放行 ${pairs.size - onCount} 条，点此恢复全部拦截"
            },
            onClick = {
                val next = !allOn
                apply(Unlock.GP_PUSH_SUB_CMDS.toList(), next)
                onToast(if (next) "已全部拦截" else "已全部放行（等同于放行下发通道）")
            },
        )
    }

    SmallTitle(text = "逐条指令")

    SectionCard {
        Unlock.GP_PUSH_SUB_CMDS.forEachIndexed { i, cmd ->
            if (i > 0) HorizontalDivider()
            val on = pairs.firstOrNull { it.first == cmd }?.second ?: true
            SwitchPreference(
                title = Unlock.gpPushSubLabel(cmd),
                summary = if (on) "拦截 · $cmd" else "放行 · $cmd",
                checked = on,
                onCheckedChange = { checked ->
                    apply(listOf(cmd), checked)
                },
            )
        }
    }

    Note(
        "关闭某条后，手机管家收到该指令时按原生逻辑继续执行，其余指令仍被拦截。" +
            "改动写入侧车配置，目标进程最长 5 秒后同步；若总开关「拦截云端下发指令」未开，本页设置不生效。"
    )
}

/**
 * 「快捷方式屏蔽」独立管理页：全局关键词 + 应用专属（选应用 → 勾该应用真实的快捷方式）。
 *
 * <p>两段配置落在 ul_sidebar_sc_block / ul_sidebar_sc_block_app 两个字符串键上，由
 * Prefs.setSidebarScBlock 一次写全（prefs → 侧车 → 下发）；hook 侧手机管家面板与桌面长按
 * 菜单共用同一套语义（ShortcutBlock），所以这里改一次，两处同时生效。</p>
 */
@Composable
private fun ShortcutBlockPage(onToast: (String) -> Unit) {
    val ctx = LocalContext.current

    var globalKws by remember { mutableStateOf(splitScKws(Prefs.getSidebarScBlock(ctx))) }
    var appRules by remember { mutableStateOf(parseScAppRules(Prefs.getSidebarScBlockApp(ctx))) }
    var gInput by remember { mutableStateOf("") }
    var aInput by remember { mutableStateOf("") }
    // null = 还没读到（正在读 / 没有 root）；空表 = 读到了但没有动态快捷方式
    var apps by remember { mutableStateOf(null as List<ScApp>?) }
    var scanned by remember { mutableStateOf(false) }
    var curPkg by remember { mutableStateOf(null as String?) }
    var scanTick by remember { mutableIntStateOf(0) }

    // 两条字符串配置永远一起写：分开写会出现「全局已生效、应用专属还是旧值」的中间态
    val persist = { g: List<String>, rules: List<ScAppRule> ->
        Prefs.setSidebarScBlock(ctx, g.joinToString(","), dumpScAppRules(rules))
        Unit
    }

    LaunchedEffect(scanTick) {
        scanned = false
        apps = withContext(Dispatchers.IO) { loadScApps(ctx) }
        scanned = true
    }

    val cur = apps?.firstOrNull { it.pkg == curPkg }

    if (cur != null) {
        // ---------- 某个应用：逐条快捷方式开关 ----------
        val rule = scRuleOf(appRules, cur)
        SmallTitle(text = cur.label)
        SectionCard {
            BasicComponent(
                title = "返回应用列表",
                summary = cur.pkg,
                onClick = { curPkg = null },
            )
        }

        SmallTitle(text = "快捷方式（开 = 屏蔽）")
        SectionCard {
            cur.items.forEachIndexed { i, item ->
                if (i > 0) HorizontalDivider()
                val localHit = scHit(item.label, item.id, rule?.kws ?: emptyList())
                val globalHit = scHit(item.label, item.id, globalKws)
                SwitchPreference(
                    title = item.label,
                    summary = when {
                        globalHit != null -> "已被全局关键词「" + globalHit + "」屏蔽"
                        localHit != null -> "已屏蔽（匹配「" + localHit + "」）· " + item.id
                        else -> "显示中 · " + item.id
                    },
                    checked = globalHit != null || localHit != null,
                    // 被全局关键词挡掉的条目不给在这里关：要关得去上面的全局关键词里删
                    enabled = globalHit == null,
                    onCheckedChange = { on ->
                        val rules = appRules.toMutableList()
                        val idx = rules.indexOfFirst {
                            it.key.equals(cur.pkg, ignoreCase = true) ||
                                it.key.equals(cur.label, ignoreCase = true)
                        }
                        val r = if (idx >= 0) {
                            rules[idx]
                        } else {
                            ScAppRule(cur.pkg, mutableListOf<String>()).also { rules.add(it) }
                        }
                        if (on) {
                            // 用快捷方式自己的 id 当关键词：只挡这一条，不误伤标题相近的其它条目
                            if (r.kws.none { it.equals(item.id, ignoreCase = true) }) r.kws.add(item.id)
                        } else {
                            r.kws.removeAll { kw -> scHit(item.label, item.id, listOf(kw)) != null }
                        }
                        appRules = rules
                        persist(globalKws, rules)
                        onToast(if (on) "已屏蔽" else "已恢复")
                    },
                )
            }
        }

        if (rule != null && rule.kws.isNotEmpty()) {
            SmallTitle(text = "「" + cur.label + "」的关键词")
            SectionCard {
                rule.kws.forEachIndexed { i, k ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = k,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 14.dp),
                        )
                        TextButton(
                            text = "删除",
                            onClick = {
                                val rules = appRules.toMutableList()
                                rules.firstOrNull {
                                    it.key.equals(cur.pkg, ignoreCase = true) ||
                                        it.key.equals(cur.label, ignoreCase = true)
                                }?.kws?.removeAll { it == k }
                                appRules = rules
                                persist(globalKws, rules)
                                onToast("已删除规则")
                            },
                        )
                    }
                }
            }
        }

        SmallTitle(text = "手动添加「" + cur.label + "」的关键词")
        SectionCard {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            ) {
                TextField(
                    value = aInput,
                    onValueChange = { aInput = it },
                    label = "关键词，如：签到、小说",
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val add = splitScKws(aInput)
                        if (add.isEmpty()) {
                            onToast("请输入关键词")
                        } else {
                            val rules = appRules.toMutableList()
                            val idx = rules.indexOfFirst {
                                it.key.equals(cur.pkg, ignoreCase = true) ||
                                    it.key.equals(cur.label, ignoreCase = true)
                            }
                            val r = if (idx >= 0) {
                                rules[idx]
                            } else {
                                ScAppRule(cur.pkg, mutableListOf<String>()).also { rules.add(it) }
                            }
                            add.forEach { k ->
                                if (r.kws.none { it.equals(k, ignoreCase = true) }) r.kws.add(k)
                            }
                            appRules = rules
                            aInput = ""
                            persist(globalKws, rules)
                            onToast("已添加 " + add.size + " 个关键词")
                        }
                    },
                ) {
                    Text("添加")
                }
            }
        }
        Note("开关直接改写「" + cur.pkg + " = 关键词…」这条规则：打开时把该条快捷方式的 id 记进去，只挡这一条；关闭时把命中它的关键词全部摘掉。侧边栏面板与桌面长按菜单同时生效，改动立刻写入配置并下发给 hook。")
        return
    }

    // ---------- 总览：全局关键词 + 应用列表 ----------
    SmallTitle(text = "全局关键词（所有应用）")
    SectionCard {
        if (globalKws.isEmpty()) {
            BasicComponent(
                title = "还没有全局关键词",
                summary = "在下面输入后点「添加」，如：签到、领券、小说",
            )
        } else {
            globalKws.forEachIndexed { i, k ->
                if (i > 0) HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = k,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 14.dp),
                    )
                    val hitN = apps?.sumOf { a ->
                        a.items.count { scHit(it.label, it.id, listOf(k)) != null }
                    } ?: 0
                    if (hitN > 0) {
                        Text(text = hitN.toString() + " 条", fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(
                        text = "删除",
                        onClick = {
                            val next = globalKws.filter { !it.equals(k, ignoreCase = true) }.toMutableList()
                            globalKws = next
                            persist(next, appRules)
                            onToast("已删除关键词")
                        },
                    )
                }
            }
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            TextField(
                value = gInput,
                onValueChange = { gInput = it },
                label = "关键词，可用逗号一次加多个：签到, 领券",
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val add = splitScKws(gInput)
                    if (add.isEmpty()) {
                        onToast("请输入关键词")
                    } else {
                        val next = globalKws.toMutableList()
                        add.forEach { k ->
                            if (next.none { it.equals(k, ignoreCase = true) }) next.add(k)
                        }
                        globalKws = next
                        gInput = ""
                        persist(next, appRules)
                        onToast("已添加 " + add.size + " 个关键词")
                    }
                },
            ) {
                Text("添加")
            }
        }
    }
    Note("标题或 id 里包含任一关键词的快捷方式会被过滤掉（忽略大小写），侧边栏面板与桌面长按菜单都生效。某个应用的所有条目全被过滤时，面板不再弹出。")

    SmallTitle(text = "应用专属（只屏蔽某个应用）")
    SectionCard {
        BasicComponent(
            title = if (scanned) "重新读取应用与快捷方式" else "正在读取应用与快捷方式…",
            summary = when {
                !scanned -> "su -c dumpsys shortcut"
                apps == null -> "读取失败：需要 root 权限"
                else -> "共 " + (apps?.size ?: 0) + " 个应用有快捷方式，点此刷新"
            },
            onClick = { if (scanned) scanTick++ },
        )
    }

    val list = apps
    if (!scanned) {
        Note("正在通过 root 读取系统 ShortcutService 里的快捷方式清单…")
    } else if (list == null) {
        Note("没有读到快捷方式：读取系统快捷方式需要 root（su -c dumpsys shortcut）。\n仍然可以在下面的「手动输入」里按应用名或包名加关键词，效果一样。")
    } else if (list.isEmpty()) {
        Note("系统里目前没有登记任何动态快捷方式。manifest 静态声明的快捷方式不在这份清单里，可以直接用上面的全局关键词按标题匹配过滤。")
    } else {
        SectionCard {
            list.forEachIndexed { i, app ->
                if (i > 0) HorizontalDivider()
                val r = scRuleOf(appRules, app)
                val n = app.items.count { item ->
                    scHit(item.label, item.id, r?.kws ?: emptyList()) != null ||
                        scHit(item.label, item.id, globalKws) != null
                }
                BasicComponent(
                    title = app.label,
                    summary = (if (n > 0) "已屏蔽 " + n + " / " + app.items.size + " 条" else app.items.size.toString() + " 条快捷方式") +
                        " · " + app.pkg,
                    endActions = { Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null) },
                    onClick = {
                        curPkg = app.pkg
                        aInput = ""
                    },
                )
            }
        }
    }

    SmallTitle(text = "手动输入应用专属规则")
    SectionCard {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            TextField(
                value = aInput,
                onValueChange = { aInput = it },
                label = "应用名或包名 = 关键词，如：番茄畅听 = 小说",
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val eq = aInput.indexOf('=')
                    if (eq <= 0) {
                        onToast("格式：应用名或包名 = 关键词")
                    } else {
                        val key = aInput.substring(0, eq).trim()
                        val kws = splitScKws(aInput.substring(eq + 1))
                        if (key.isEmpty() || kws.isEmpty()) {
                            onToast("格式：应用名或包名 = 关键词")
                        } else {
                            val rules = appRules.toMutableList()
                            val idx = rules.indexOfFirst { it.key.equals(key, ignoreCase = true) }
                            val r = if (idx >= 0) {
                                rules[idx]
                            } else {
                                ScAppRule(key, mutableListOf<String>()).also { rules.add(it) }
                            }
                            kws.forEach { k ->
                                if (r.kws.none { it.equals(k, ignoreCase = true) }) r.kws.add(k)
                            }
                            appRules = rules
                            aInput = ""
                            persist(globalKws, rules)
                            onToast("已添加规则")
                        }
                    }
                },
            ) {
                Text("添加")
            }
        }
    }

    if (appRules.any { it.kws.isNotEmpty() }) {
        SmallTitle(text = "已有的应用专属规则")
        SectionCard {
            appRules.filter { it.kws.isNotEmpty() }.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider()
                BasicComponent(
                    title = r.key,
                    summary = r.kws.joinToString("、"),
                    endActions = {
                        TextButton(
                            text = "删除",
                            onClick = {
                                val rules = appRules.filter { it !== r }.toMutableList()
                                appRules = rules
                                persist(globalKws, rules)
                                onToast("已删除应用规则")
                            },
                        )
                    },
                )
            }
        }
    }

    Note("应用专属规则标识可以写包名（如 com.xs.fm）或应用名（如 番茄畅听），忽略大小写；关键词按「标题或 id 包含」匹配，忽略大小写。规则只影响显示，不会改系统里的快捷方式。")
}

/** 结束手机管家进程（root 优先，退化到 killBackgroundProcesses） */
private fun forceStopSecurity(ctx: Context) {
    Toast.makeText(ctx, "正在结束手机管家…", Toast.LENGTH_SHORT).show()
    Thread({
        var ok = false
        if (File("/system/bin/su").exists() || File("/system/xbin/su").exists()) {
            try {
                val p = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "am force-stop " + Prefs.SECURITY_PKG),
                )
                ok = p.waitFor() == 0
            } catch (ignored: Throwable) {
            }
        }
        if (!ok) {
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
                am?.killBackgroundProcesses(Prefs.SECURITY_PKG)
            } catch (ignored: Throwable) {
            }
        }
    }, "force-stop-sc").start()
}

/**
 * 解析状态文件里的「原生已支持」采样结果。
 *
 * 规则行格式：`r=<id>|<group>|<label>|<state>|<hits>|<where>|n=<0..4>|nv=<原生值>`
 * 其中 n=1 表示「原生返回值与解锁目标值一致」，即本机型 / 本版本本来就支持。
 * 用 substringAfter 取值，避免 label / where 里万一出现 '|' 时错位。
 *
 * @return 规则 id -> 原生返回值文本（仅含 n=1 的规则）
 */
private fun ulNativeSame(raw: String): Map<String, String> {
    if (raw.isBlank()) {
        return emptyMap()
    }
    val out = LinkedHashMap<String, String>()
    raw.lineSequence().forEach { line ->
        if (!line.startsWith("r=")) {
            return@forEach
        }
        val id = line.substringAfter("r=").substringBefore('|')
        if (id.isEmpty()) {
            return@forEach
        }
        val code = line.substringAfter("|n=", "").substringBefore('|')
        if (code == "1") {
            out[id] = line.substringAfter("|nv=", "").ifEmpty { "原生同值" }
        }
    }
    return out
}

/** 调试模式下每条规则的定位细节 */
private fun ulItemSummary(r: Array<String>): String {
    // guard 组（gp_applist / gp_devstate / gp_push）只挂在 guardprovider 进程，
    // 在手机管家、安全中心这类进程里本来就找不到锚点。裸写「未定位」会让人误以为
    // 功能没生效，所以这里按组给出准确说法。
    val isGuard = r.size > 1 && r[1] == "guard"
    val state = when (r[3]) {
        "1" -> "已 hook"
        "2" -> "未命中"
        "3" -> "失败"
        else -> if (isGuard) "仅系统安全组件内生效" else "未定位"
    }
    val hits = r[4].toIntOrNull() ?: 0
    val where = r[5]
    return buildString {
        append("状态：").append(state)
        if (hits > 0) {
            append(" · 命中 ").append(hits).append(" 次")
        }
        // guard 规则在本进程未定位时不再追加「未定位」，避免和上面的文案重复
        if (where.isNotEmpty() && !(isGuard && r[3] == "0")) {
            append(" · ").append(where)
        }
    }
}

/** 解析目标进程写出的手机管家状态文件 */
private fun ulStatusView(raw: String, debug: Boolean): StatusView {
    if (raw.isBlank()) {
        return StatusView(
            chip = "未上报",
            tone = Tone.Idle,
            rows = listOf(
                "手机管家" to "尚未在作用域内启动，或模块未在该进程生效",
                "提示" to "打开一次手机管家即可生成定位结果",
            ),
            diag = emptyList(),
        )
    }
    val proc = statusValue(raw, "proc") ?: "-"
    val via = statusValue(raw, "via") ?: "-"
    val on = statusValue(raw, "on") ?: "-"
    val cost = statusValue(raw, "cost") ?: "-"
    val hooks = statusValue(raw, "stat") ?: "-"
    val hooked = hooks.substringAfter("hooked=", "0")
        .takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    val native = statusValue(raw, "native")
    val natSame = native?.substringAfter("same=", "0")
        ?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
    val natPend = native?.substringAfter("pend=", "0")
        ?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    val rows = mutableListOf<Pair<String, String>>()
    rows += "规则" to on
    rows += "定位方式" to viaName(via)
    rows += "耗时" to cost
    if (proc != "-") {
        rows += "进程" to proc
    }
    if (natSame > 0) {
        rows += "原生已支持" to "$natSame 项（已置灰）"
    } else if (native != null && natPend > 0) {
        rows += "原生已支持" to "尚未采到（用一次相关功能后刷新）"
    }

    val diag = mutableListOf<Pair<String, String>>()
    diag += "hook 结果" to hooks
    native?.takeIf { it.isNotEmpty() }?.let { diag += "原生采样" to it }
    statusValue(raw, "cfg")?.takeIf { it.isNotEmpty() }?.let { diag += "配置来源" to it }

    return StatusView(
        chip = if (hooked > 0) "已生效" else "待适配",
        tone = if (hooked > 0) Tone.Ok else Tone.Idle,
        rows = rows,
        diag = if (debug) diag else emptyList(),
    )
}

@Composable
private fun AboutPage(debug: Boolean, version: String) {
    val uriHandler = LocalUriHandler.current
    val cs = MiuixTheme.colorScheme
    val c = CamelliaTheme.colors

    // 头图：大图标 + 模块名 + 版本。与 HyperCeiler 的关于页同一个结构——
    // 先用一块「品牌区」把身份说清楚（图标、名字、版本、一句话定位），
    // 往下才是各项技术说明；背景是上一层绘制的流动渐变。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 图标外加一层品牌色柔光底，深色下也能把图标从渐变背景上「托」起来
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(c.brandSoft)
                .padding(10.dp),
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = "Camellia",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("Camellia", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Badge("v$version", Tone.Brand)
        Spacer(Modifier.height(10.dp))
        Text(
            "小米互传 · 小米画报 · 手机管家的版本自适应模块",
            fontSize = 13.sp,
            color = cs.onSurfaceVariantSummary,
        )
    }

    SmallTitle(text = "关于本模块")
    TechCard(
        title = "它做什么",
        badge = null,
        desc = "把互传收到文件的「打开」落点改到 MT 管理器（文档 / 音频 / 未知类型，图片 / 视频 / 联系人保持原样）；" +
            "并把指定目录的图片同步进小米画报的「自选图片」。完全在本地运行，不联网、不收集任何数据。",
    )
    SectionCard {
        Column(modifier = Modifier.padding(16.dp)) {
            AvatarRow(
                avatarRes = R.drawable.avatar_author,
                name = "酷安 @咸鱼鱼o",
                desc = "Camellia 作者",
                url = "https://www.coolapk.com/u/2487707",
            )
        }
    }

    SmallTitle(text = "运行原理")
    TechCard(
        title = "libxposed API",
        badge = "100 → 102",
        desc = "模块运行在 LSPosed 等 libxposed 兼容框架上，只声明接口；Hook 的代际交接由框架负责，互传升级无需重装模块。",
    )
    TechCard(
        title = "DexKit",
        badge = "2.3.0",
        desc = "运行时按「方法返回 Intent」+「参数含文件集合 / TapRecvData / Context」等多信号打分定位混淆方法，并缓存命中结果。" +
            "不硬编码任何混淆类名 / 方法名，所以 4.0.5 ~ 5.4.2 共用同一套规则。",
    )

    SmallTitle(text = "界面与平台")
    TechCard(
        title = "Kotlin · Compose",
        badge = "Kotlin 2.4.21 · Compose 1.13",
        desc = "设置页由 Kotlin 与 Jetpack Compose（androidx.compose 1.13.0-rc01）构建；二级页切换有滑动与淡入淡出过渡，各页滚动位置互相独立。",
    )
    TechCard(
        title = "MIUIX for Compose",
        badge = "0.9.5-rc01",
        desc = "界面组件沿用 MIUIX（top.yukonga.miuix.kmp），跟随 HyperOS 的视觉与交互习惯；品牌强调色在此基础上覆盖。",
    )
    TechCard(
        title = "AndroidLiquidGlass",
        badge = "2.0.1",
        desc = "顶栏与底栏的液体玻璃（io.github.kyant0:backdrop）：把滚动内容录制成来源层，顶栏 / 底栏按坐标取用并做模糊、边缘折射与高光，随主题自适应深浅色；" +
            "底栏另有 HyperOS 贴底 / HyperOS 悬浮 / 液态玻璃三种样式可选。",
    )
    TechCard(
        title = "Material Color Utilities",
        badge = "6.0.0",
        desc = "Monet 动态取色与 Material 3 配色（com.materialkolor:material-color-utilities），" +
            "驱动「色彩标准 / 色彩风格 / 强调色」的 HCT 色板推导。",
    )
    SectionCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("应用与运行环境", fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            InfoGrid(
                listOf(
                    "最低 / 目标 API" to "26 / 35",
                    "ABI" to "arm64-v8a",
                    "模块版本" to "v$version",
                    "调试模式" to (if (debug) "已开启" else "已关闭"),
                ),
            )
        }
    }

    SmallTitle(text = "鸣谢")
    SectionCard {
        CreditLink(R.drawable.avatar_credit, "酷安 @堇森", "「把互传收到文件的打开落点改到 MT 管理器」的思路来源", "https://www.coolapk.com/u/37937816")
        HorizontalDivider()
        CreditLink(R.drawable.avatar_py, "酷安 @PY小和尚", "感谢提供手机管家的修改思路和方案", "https://www.coolapk.com/u/1993450")
        HorizontalDivider()
        CreditLink(R.drawable.avatar_rechronorain, "HyperCeiler", "ReChronoRain/HyperCeiler", "https://github.com/ReChronoRain/HyperCeiler")
        HorizontalDivider()
        CreditLink(R.drawable.avatar_kiminonawaresa, "HyperLight", "KiminonawaResa/HyperLight", "https://github.com/KiminonawaResa/HyperLight")
        HorizontalDivider()
        CreditLink(R.drawable.avatar_wommo, "WOMMO · 作者 YifePlayte", "移植系统安全组件拦截功能与 Rust 桌面 Hook 思路", "https://github.com/YifePlayte/WOMMO")
        HorizontalDivider()
        CreditLink(R.drawable.avatar_xkspeed, "HyperMount · 作者 XKspeed", "camd 组件「救砖」功能的思路参考", "https://github.com/XKspeed/HyperMount")
        HorizontalDivider()
        CreditLink(R.drawable.avatar_taosu, "酷安 @桃酥打水", "桌面布局备份", "https://www.coolapk.com/u/4111144")
        HorizontalDivider()
        CreditLink(R.drawable.avatar_marious, "酷安 @MariOus", "感谢支持与帮助", "https://www.coolapk.com/u/578241")
        HorizontalDivider()
        CreditLink(R.drawable.avatar_group, "锁屏飞升组织 · 全体群友", "感谢各位群友的支持", "")
    }

    SmallTitle(text = "开源库引用")
    SectionCard {
        LibRow("DexKit 2.3.0", "org.luckypray:dexkit · 运行时解析 dex 定位混淆方法 · Apache-2.0", "https://github.com/LuckyPray/DexKit")
        HorizontalDivider()
        LibRow("Kotlin stdlib 2.4.21", "Kotlin 运行期依赖 · Apache-2.0")
        HorizontalDivider()
        LibRow("kotlinx-coroutines", "协程调度（Dispatchers / launch）· Apache-2.0")
        HorizontalDivider()
        LibRow("FlatBuffers Java 23.5.26", "DexKit 的 Java ↔ native 协议 · Apache-2.0")
        HorizontalDivider()
        LibRow("Jetpack Compose 1.13.0-rc01", "androidx.compose:ui / foundation / animation · 设置页声明式 UI · Apache-2.0")
        HorizontalDivider()
        LibRow("AndroidX Activity Compose 1.13.0", "setContent 与返回手势宿主 · Apache-2.0")
        HorizontalDivider()
        LibRow("MIUIX for Compose 0.9.5-rc01", "top.yukonga.miuix.kmp:core / ui / preference / icons · 设置页 UI 组件 · Apache-2.0")
        HorizontalDivider()
        LibRow("Material Color Utilities 6.0.0", "com.materialkolor:material-color-utilities · Monet 取色与 Material 3 色板（HCT）· Apache-2.0")
        HorizontalDivider()
        LibRow("AndroidLiquidGlass 2.0.1", "io.github.kyant0:backdrop · 顶栏 / 底栏液体玻璃 · Apache-2.0")
        HorizontalDivider()
        LibRow("capsule 2.1.3", "io.github.kyant0:capsule · 玻璃胶囊形状与描边 · Apache-2.0")
        HorizontalDivider()
        LibRow("libxposed API", "io.github.libxposed · 模块与框架的接口定义", "https://github.com/LibXposed")
    }
    Note(
        "以上开源库的版权与许可声明同时收录在本模块 APK 内的 META-INF/NOTICE-DEPS.txt。",
    )

    SmallTitle(text = "版本自适应原理")
    Note(
        "运行时不依赖任何混淆类名 / 方法名：先用 DexKit 按「返回 Intent」取全集，" +
            "再按「参数含文件集合、TapRecvData、Context、参数个数」等多信号打分，" +
            "取最高分附近的前几个候选一起挂；文件类型判定也只认 FileInfo 的字段形状。" +
            "所以 4.x 与 5.x 共用同一套规则，互传升级后无需重新打包模块。",
    )

    SmallTitle(text = "调试")
    if (debug) {
        Note(
            "调试模式已开启。日志过滤：adb logcat -s Camellia\n" +
                "关闭调试模式后，目标进程不会再输出 INFO / DEBUG 日志（WARN / ERROR 保留）。",
        )
    } else {
        Note(
            "调试信息默认关闭：状态卡片只显示结论。\n" +
                "如果适配状态异常或想反馈问题，请在主页打开「调试模式」，" +
                "回来即可看到可复制的诊断明细（命中方法、目录锚点、MT 探测、耗时等）。",
        )
    }
}

// ---------------- 通用容器 ----------------

/**
 * 统一的分区块卡片。
 *
 * 外层左右 12dp + 卡内 16dp，把内容推到 28dp，正好和 SmallTitle 的 28dp 文字基线、
 * Note 的 28dp 左边线对齐 —— 整页只有这一条视觉左线。
 * 底部统一 12dp：旧版这里有的写 10dp、有的写 12dp，滚动时节奏会一顿一顿的。
 */
@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .then(modifier),
        content = content,
    )
}

/**
 * 「自动跟随应用取色」的取值方案。顺序必须与 Unlock.java 的 sidebarMode() 一致：
 * 0=图标面积主色 1=壁纸主色 2=图标最饱和 3=图标平均色 4=图标最亮 5=图标提亮版 6=跟随屏幕。
 */
private val SB_MODE_TITLES = listOf(
    "跟随应用图标",
    "跟随壁纸",
    "图标 · 最饱和",
    "图标 · 平均色",
    "图标 · 最亮",
    "图标 · 提亮版",
    "跟随屏幕",
)

/** 与 [SB_MODE_TITLES] 一一对应的优缺点说明 */
private val SB_MODE_SUMMARIES = listOf(
    "优点：最稳，就是 App 的品牌色。缺点：同一个 App 的所有页面颜色一样",
    "优点：全局协调，色彩最不违和。缺点：不跟着 App 变，只在换壁纸时才变",
    "优点：最醒目，灰白底图标也能出彩。缺点：可能偏艳，个别图标会有点跳",
    "优点：最柔和、不刺眼。缺点：对比度偏低，深色图标可能糊成一团",
    "优点：深色图标也看得清。缺点：整体偏亮，可能接近白色",
    "优点：暗色 App 也清晰可辨。缺点：会偏离 App 原本的品牌色",
    "优点：真正按屏幕上的画面取色，画面一换它就跟着换。缺点：需调用 screencap 抓屏，耗电与开销都更高；颜色有「下一次呼出才生效」的延迟，抓屏失败时回退到壁纸色",
)

/** 小字说明段落：同一 28dp 左边线，用次级灰色与正文拉开层次 */
@Composable
private fun Note(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
    )
}

/** 头像 + 昵称 + 说明 + 主页入口：用于「作者」与「鸣谢」 */
@Composable
private fun LibRow(title: String, summary: String, url: String = "") {
    val uriHandler = LocalUriHandler.current
    BasicComponent(
        title = title,
        summary = summary,
        endActions = { if (url.isNotBlank()) Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null) },
        onClick = { if (url.isNotBlank()) uriHandler.openUri(url) },
    )
}

@Composable
private fun CreditLink(avatarRes: Int, title: String, summary: String, url: String) {
    val uriHandler = LocalUriHandler.current
    BasicComponent(
        title = title,
        summary = summary,
        startAction = {
            Image(
                painter = painterResource(avatarRes),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(CircleShape),
            )
        },
        endActions = { if (url.isNotBlank()) Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null) },
        onClick = { if (url.isNotBlank()) uriHandler.openUri(url) },
    )
}

@Composable
private fun AvatarRow(avatarRes: Int, name: String, desc: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (url.isNotBlank()) Modifier.clickable { uriHandler.openUri(url) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(avatarRes),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                desc,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

// ---------------- 小部件 ----------------

/** 带色点的结论标签：状态直接用颜色表达，不必再写一整句话 */
@Composable
private fun StatusChip(text: String, tone: Tone) {
    val tc = CamelliaTheme.colors.of(tone)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tc.bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tc.fg),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = tc.fg)
    }
}

// ---------------- 状态总览（SystemUI 自救页顶部） ----------------

/**
 * 版本 / 标签徽标。
 *
 * 显式给纵向 3dp + 显式行高：旧版只给横向 padding，高度完全由字体度量决定，
 * 无障碍上报出来的高度只有 9px，与旁边 32px 的标题不在同一视觉重量上。
 */
@Composable
private fun Badge(text: String, tone: Tone = Tone.Idle) {
    val tc = CamelliaTheme.colors.of(tone)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(tc.bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 11.sp, lineHeight = 15.sp, color = tc.fg)
    }
}

/**
 * 左标签 + 右值。
 *
 * 标签固定 88dp，值占满剩余宽度并「在自身列内」折行。
 * 旧版把状态拼成一整句（如「5.4.2 · 已适配（5.4.2_20260923_1315（50402））」）再用一个
 * Text 渲染，长版本号整行折成两行后视觉重心右移，与左侧标签垂直错位（实测 h=112 vs 67）；
 * 分栏后标签永远对齐首行，长值只在自己那一列换行。
 */
@Composable
private fun KVRow(label: String, value: String, small: Boolean = false) {
    val cs = MiuixTheme.colorScheme
    val fs = if (small) 12.sp else 13.sp
    val lh = if (small) 17.sp else 19.sp
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            fontSize = fs,
            lineHeight = lh,
            color = cs.onSurfaceVariantSummary,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            fontSize = fs,
            lineHeight = lh,
            color = cs.onSurfaceSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 状态卡：结论 chip + 键值行 (+ 调试明细) (+ 操作按钮) */
@Composable
private fun StatusCard(view: StatusView, action: (@Composable () -> Unit)? = null) {
    SectionCard {
        Column(modifier = Modifier.padding(16.dp)) {
            StatusChip(view.chip, view.tone)
            Spacer(Modifier.height(14.dp))
            view.rows.forEach { (k, v) -> KVRow(k, v) }
            if (view.diag.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    "调试明细",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                view.diag.forEach { (k, v) -> KVRow(k, v, small = true) }
            }
            if (action != null) {
                Spacer(Modifier.height(14.dp))
                action()
            }
        }
    }
}

/** 双列 key-value 网格 */
@Composable
private fun InfoGrid(pairs: List<Pair<String, String>>) {
    val cs = MiuixTheme.colorScheme
    Column {
        pairs.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                row.forEach { (k, v) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(k, fontSize = 12.sp, color = cs.onSurfaceVariantSummary)
                        Spacer(Modifier.height(4.dp))
                        Text(v, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 条目卡：标题 + 版本徽标 + 描述 */
@Composable
private fun TechCard(title: String, badge: String?, desc: String) {
    val cs = MiuixTheme.colorScheme
    SectionCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge, Tone.Brand)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(desc, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceSecondary)
        }
    }
}

/** 目标应用的图标；取不到时用首字做占位 */
@Composable
private fun AppIcon(pkg: String, fallback: String) {
    val ctx = LocalContext.current
    val drawable = remember(pkg) {
        try {
            ctx.packageManager.getApplicationIcon(pkg)
        } catch (t: Throwable) {
            null
        }
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (drawable != null) {
            AndroidView(
                factory = { c ->
                    ImageView(c).apply {
                        setImageDrawable(drawable)
                        scaleType = ImageView.ScaleType.FIT_XY
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(fallback)
        }
    }
}

private fun orderName(order: String): String = when (order) {
    Prefs.ORDER_DESC -> "倒序"
    Prefs.ORDER_RANDOM -> "随机"
    else -> "顺序"
}

private fun moduleVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
} catch (t: Throwable) {
    "?"
}

private fun viaName(via: String): String = when (via) {
    "dexkit" -> "DexKit 结构化查询"
    "dex-scan" -> "内置 dex 扫描"
    "cache" -> "本地指纹缓存"
    "cache+async" -> "缓存 + 后台补扫"
    "async" -> "后台定位"
    "fallback-name" -> "类名兜底"
    else -> via
}

// ---------------- 状态：解析为结构化视图 ----------------

/**
 * 一个状态视图。
 *
 * [rows] 是给人看的结论，[diag] 是只在调试模式下展开的实现细节。
 * 拆成结构化数据而不是一段字符串，是为了让界面能用「标签 / 值」两栏排版，
 * 而不是把多行文本塞进一个 Text 里 —— 后者就是这一版布局问题的主因。
 */
private data class StatusView(
    val chip: String,
    val tone: Tone,
    val rows: List<Pair<String, String>>,
    val diag: List<Pair<String, String>>,
)

/** 从 key=value 形式的状态文本里取指定键；raw 允许为 null（画报尚未推送状态时） */
private fun statusValue(raw: String?, key: String): String? {
    if (raw.isNullOrEmpty()) return null
    for (line in raw.split("\n")) {
        val eq = line.indexOf('=')
        if (eq <= 0) {
            continue
        }
        if (line.substring(0, eq).trim() == key) {
            return line.substring(eq + 1).trim()
        }
    }
    return null
}

/** 把 "5.4.2_20260923_1315（50402）" 这类长版本号压成 "5.4.2" */
private fun shortVersion(v: String?): String {
    if (v.isNullOrBlank()) return "未安装"
    return v.substringBefore('_').substringBefore('（').trim().ifEmpty { v }
}

private fun mtDiagRows(raw: String, debug: Boolean): List<Pair<String, String>> {
    if (!debug) return emptyList()
    val out = mutableListOf<Pair<String, String>>()
    statusValue(raw, "hooks")?.takeIf { it.isNotEmpty() }?.let { out += "命中中枢" to it }
    statusValue(raw, "dirAnchors")?.let { out += "目录锚点" to (if (it.isEmpty()) "未命中" else it) }
    statusValue(raw, "mt")?.takeIf { it.isNotEmpty() }?.let {
        out += "MT 跳转能力" to (if (it == "none") "未探测到跳转入口" else it)
    }
    statusValue(raw, "dexkit")?.takeIf { it.isNotEmpty() }?.let { out += "DexKit" to it }
    statusValue(raw, "intercept")?.takeIf { it.isNotEmpty() }?.let { out += "单次拦截耗时" to it }
    statusValue(raw, "time")?.takeIf { it.isNotEmpty() }?.let { out += "更新时间" to it }
    return out
}

private fun mtStatusView(ctx: Context, debug: Boolean): StatusView {
    val installed = Prefs.installedVersion(ctx, Prefs.TARGET_PKG)
    val ver = shortVersion(installed)
    if (installed == null) {
        return StatusView(
            chip = "未安装",
            tone = Tone.Idle,
            rows = listOf("小米互传" to "未检测到（未安装，或被桌面隐藏）"),
            diag = emptyList(),
        )
    }

    val raw = Prefs.readRuntimeStatus(ctx)
    if (raw.isNullOrEmpty()) {
        return StatusView(
            chip = "待运行",
            tone = Tone.Idle,
            rows = listOf(
                "小米互传" to ver,
                "适配状态" to "尚未获取",
                "如何激活" to "在 LSPosed 中启用本模块，然后打开一次小米互传",
            ),
            diag = emptyList(),
        )
    }

    val result = statusValue(raw, "result")
    if (result == "no-dispatcher") {
        return StatusView(
            chip = "待适配",
            tone = Tone.Warn,
            rows = listOf(
                "小米互传" to ver,
                "适配状态" to "未找到分流中枢",
                "怎么办" to "打开调试模式后把本页截图反馈给作者",
            ),
            diag = mtDiagRows(raw, debug),
        )
    }

    val via = statusValue(raw, "via")
    val runVersion = statusValue(raw, "version")
    val rows = mutableListOf("小米互传" to ver, "适配状态" to "已适配")
    // 只有运行期版本与已安装版本不一致时才单列一行，避免同一串版本号出现两次
    val runShort = shortVersion(runVersion)
    if (runShort != ver && runShort != "未安装") {
        rows += "运行期版本" to runShort
    }
    if (via == "dexkit" || via == "dex-scan") {
        rows += "定位方式" to viaName(via)
    }
    return StatusView(
        chip = "已适配",
        tone = Tone.Ok,
        rows = rows,
        diag = mtDiagRows(raw, debug),
    )
}

private fun fgStatusView(ctx: Context, debug: Boolean): StatusView {
    val installed = Prefs.installedVersion(ctx, Prefs.FG_PKG)
    val ver = shortVersion(installed)
    if (installed == null) {
        return StatusView(
            chip = "未安装",
            tone = Tone.Idle,
            rows = listOf("小米画报" to "未检测到（未安装）"),
            diag = emptyList(),
        )
    }

    val raw = Prefs.readFgStatus(ctx)
    if (raw.isNullOrEmpty()) {
        return StatusView(
            chip = "待同步",
            tone = Tone.Idle,
            rows = listOf(
                "小米画报" to ver,
                "同步状态" to "尚未获取",
                "如何激活" to "点下方按钮打开一次「自选图片」页",
            ),
            diag = emptyList(),
        )
    }

    val result = statusValue(raw, "result")
    val bad = result != null && (result.contains("失败") || result.contains("错误"))
    val warn = !bad && result != null && (result.contains("跳过") || result.contains("未"))
    val chip = when {
        result == null -> "已就绪"
        bad -> "同步失败"
        warn -> "部分就绪"
        else -> "已就绪"
    }
    val rows = mutableListOf("小米画报" to ver)
    if (!result.isNullOrEmpty()) {
        rows += "同步状态" to result
    }
    // 推广拦截的生效反馈：ad 行形如「ad=on adhooks=4 adhits=12 adlast=1792...」
    // 这里只取「开关 + 命中次数」——用户需要的是「到底有没有真的拦住」，
    // 注入点数量属于实现细节，放进调试明细。
    val adLine = statusValue(raw, "ad") ?: ""
    val adOn = adLine.startsWith("on")
    val adHooks = Regex("adhooks=(\\d+)").find(adLine)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val adHits = Regex("adhits=(\\d+)").find(adLine)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    rows += "禁用推广" to when {
        !adOn -> "已关闭"
        adHooks == 0 -> "已开启（尚未命中注入点）"
        adHits > 0 -> "已开启 · 已拦截 $adHits 次"
        else -> "已开启 · 等待命中"
    }

    val diag = mutableListOf<Pair<String, String>>()
    if (debug) {
        statusValue(raw, "dir")?.takeIf { it.isNotEmpty() }?.let { diag += "同步目录" to it }
        statusValue(raw, "hooks")?.takeIf { it.isNotEmpty() }?.let { diag += "命中注入点" to it }
        statusValue(raw, "ad")?.takeIf { it.isNotEmpty() }?.let { diag += "推广拦截" to it }
        statusValue(raw, "fileprovider")?.takeIf { it.isNotEmpty() }?.let { diag += "FileProvider" to it }
        statusValue(raw, "perm")?.takeIf { it.isNotEmpty() }?.let { diag += "存储权限" to it }
        statusValue(raw, "added")?.takeIf { it.isNotEmpty() }?.let { diag += "本次入库" to it }
        statusValue(raw, "cost")?.takeIf { it.isNotEmpty() }?.let { diag += "本次耗时" to it }
        statusValue(raw, "time")?.takeIf { it.isNotEmpty() }?.let { diag += "更新时间" to it }
    }

    return StatusView(
        chip = chip,
        tone = if (bad) Tone.Bad else if (warn) Tone.Warn else Tone.Ok,
        rows = rows,
        diag = diag,
    )
}

/**
 * 「全局侧边栏背景自定义」区块（1:1 对齐 HyperCeiler 的组织方式）。
 *
 * 独立成 composable 是为了让它能随规则分组顺序摆放：当前挂在「其他」分组之后、
 * 「游戏工具箱」分组之前。开启主开关后才会出现「模糊程度」滑块与「叠加层颜色」调色盘。
 */
@Composable
private fun SidebarBackgroundSection() {
    val ctx = LocalContext.current
    // 侧边栏背景：1:1 对齐 HyperCeiler「全局侧边栏背景自定义」——
    // 主开关打开后才出现「模糊程度」滑块与「叠加层颜色」调色盘（依赖联动）
    var limitOn by remember { mutableStateOf(Prefs.isUlOn(ctx, "sidebar_limit")) }
    var appLimit by remember { mutableIntStateOf(Prefs.getSidebarLimit(ctx)) }
    // 与下面的「侧边栏背景」保持同一套版式：SmallTitle 分组标题 + SectionCard（左右 12dp）
    SmallTitle(text = "侧边栏应用数量上限")
    SectionCard {
        SwitchPreference(
            title = "解锁侧边栏应用数量限制",
            summary = "同时调整应用编辑页和 Dock 添加页，默认 50，最多 200",
            checked = limitOn,
            onCheckedChange = {
                limitOn = it
                Prefs.setSidebarLimit(ctx, appLimit)
                Prefs.setUlOn(ctx, "sidebar_limit", it)
                Toast.makeText(ctx, "已保存，请重启手机管家后重新打开侧边栏", Toast.LENGTH_SHORT).show()
            },
        )
        if (limitOn) {
            HorizontalDivider()
            SliderPreference(
                title = "应用数量上限",
                value = appLimit.toFloat(),
                onValueChange = { appLimit = it.roundToInt() },
                valueRange = 16f..200f,
                steps = 183,
                valueText = "$appLimit 个",
                onValueChangeFinished = {
                    Prefs.setSidebarLimit(ctx, appLimit)
                    Toast.makeText(ctx, "已保存，请重新打开编辑页；调低上限不会删除已选应用", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
    var sbOn by remember { mutableStateOf(Prefs.isUlOn(ctx, "sidebar_bg")) }
    var sbBlur by remember { mutableIntStateOf(Prefs.getSidebarBlur(ctx)) }
    var sbColor by remember { mutableIntStateOf(Prefs.getSidebarColor(ctx)) }
    var sbPicker by remember { mutableStateOf(false) }
    var sbMonet by remember { mutableStateOf(Prefs.isSidebarMonet(ctx)) }
    var sbMode by remember { mutableIntStateOf(Prefs.getSidebarMode(ctx)) }
    // Miuix 滑杆首次组合会把归一化初值 0 回调出来（实测把模糊程度打到了下限 30），那不是用户操作：
    // 进入页面后的保护期内忽略滑杆回调，界面上只显示已存储的值。
    var sbBlurLocked by remember { mutableStateOf(true) }
    // 功能说明默认折叠：这一页的说明很长，展开时会顶掉一屏开关，改成点按展开
    var showNote by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(800)
        sbBlurLocked = false
    }

    SmallTitle(text = "侧边栏背景")
    SectionCard {
        SwitchPreference(
            title = "全局侧边栏背景自定义",
            summary = "接管侧边栏本体：叠加自定义颜色并启用 MIUI 原生背景模糊",
            checked = sbOn,
            onCheckedChange = { v ->
                sbOn = v
                Prefs.setUlOn(ctx, "sidebar_bg", v)
                Toast.makeText(
                    ctx,
                    if (v) "已开启「全局侧边栏背景自定义」，下次呼出侧边栏即生效" else "已关闭「全局侧边栏背景自定义」",
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
        if (sbOn) {
            HorizontalDivider()
            SwitchPreference(
                title = "自动跟随应用取色",
                summary = if (sbMonet)
                    "已接管背景色：每次呼出按下面选中的方案取色（下面的颜色设定被忽略）"
                else
                    "已关闭：使用下面设定的颜色",
                checked = sbMonet,
                onCheckedChange = { v ->
                    sbMonet = v
                    Prefs.setSidebarMonet(ctx, v)
                    if (v) {
                        // 开启即由动态取色接管：把颜色配置还原成默认值，免得设置里留着一个
                        // 再也不会生效的旧颜色，让人以为"设了没效果"。
                        sbColor = Prefs.SB_COLOR_DEF
                        Prefs.setSidebarColor(ctx, Prefs.SB_COLOR_DEF)
                    }
                    Toast.makeText(
                        ctx,
                        if (v) "已开启「自动跟随应用取色」：展开下方列表选择取色方案" else "已关闭「自动跟随应用取色」：改回使用下面设定的颜色",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            if (sbMonet) {
                // 取色方案单选（只能选一个）。每项都标注了优缺点，方便按自己的观感挑。
                val pick = { m: Int ->
                    sbMode = m
                    Prefs.setSidebarMode(ctx, m)
                    Toast.makeText(
                        ctx,
                        "取色方案已切换为「${SB_MODE_TITLES[m]}」，下次呼出侧边栏即生效",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                HorizontalDivider()
                SB_MODE_TITLES.indices.forEach { m ->
                    RadioButtonPreference(
                        title = SB_MODE_TITLES[m],
                        summary = SB_MODE_SUMMARIES[m],
                        selected = sbMode == m,
                        onClick = { pick(m) },
                    )
                }
            }
            HorizontalDivider()
            SliderPreference(
                title = "全局侧边栏背景模糊程度",
                value = sbBlur.toFloat(),
                onValueChange = { if (!sbBlurLocked) sbBlur = it.roundToInt() },
                valueRange = Prefs.SB_BLUR_MIN.toFloat()..Prefs.SB_BLUR_MAX.toFloat(),
                steps = Prefs.SB_BLUR_MAX - Prefs.SB_BLUR_MIN - 1,
                valueText = "$sbBlur %",
                onValueChangeFinished = {
                    if (!sbBlurLocked) {
                        Prefs.setSidebarBlur(ctx, sbBlur)
                        Toast.makeText(ctx, "模糊程度 $sbBlur %，下次呼出侧边栏即生效", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            HorizontalDivider()
            BasicComponent(
                title = "全局侧边栏背景叠加层颜色",
                summary = if (sbMonet)
                    "已被「自动跟随应用取色」接管，颜色跟着当前应用走"
                else
                    "点击打开调色盘（取色 / 透明度 / 十六进制）",
                enabled = !sbMonet,
                onClick = { sbPicker = true },
                endActions = {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(sbColor)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        String.format("#%08X", sbColor),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            )
        }
    }
    SectionCard {
        BasicComponent(
            title = if (showNote) "收起功能说明" else "功能说明",
            summary = if (showNote) "点按收起" else "点按查看每一项的含义、跟色范围与生效时机",
            onClick = { showNote = !showNote },
            endActions = {
                Icon(
                    MiuixIcons.Basic.ArrowRight,
                    contentDescription = null,
                    modifier = Modifier.rotate(if (showNote) 90f else 0f),
                )
            },
        )
    }
    if (showNote) {
        Note(
            "对齐 HyperCeiler 的组织方式：先开「全局侧边栏背景自定义」，其下才会出现「自动跟随应用取色」、" +
                "「全局侧边栏背景模糊程度」（10 - 99 %，默认 60）与「全局侧边栏背景叠加层颜色」三项。" +
                "跟色范围：侧边栏本体、最下方按钮展开的 dock 栏、右侧「全部小窗应用」抽屉页，以及视频工具箱。" +
                "打开「自动跟随应用取色」后会在下方展开取色方案列表，每项都标了优缺点，只能选一个：" +
                "前两项决定颜色的来源（应用图标 / 壁纸），后四项是同一张图标的不同取法 —— " +
                "按面积取最像品牌色，按饱和度取最醒目，按平均取最柔和，按明度取最适合深色图标；" +
                "「提亮版」则在品牌色基础上抬明度，暗色 App 也看得清。" +
                "开启后每次呼出侧边栏都会重新取色，所以颜色会随你正在用的 App 变化；" +
                "取不到图标色的界面（桌面、设置等）自动用壁纸主色。" +
                "图标按包名缓存，同一个 App 只解码一次，切换方案时也无需重新解码，" +
                "命中缓存时无额外开销。开启时上面的颜色设定会被忽略并还原为默认值。" +
                "颜色 / 方案 / 模糊改完无需重启手机管家，下次呼出侧边栏即生效；模糊 API 缺失时自动降级为纯色背景。",
        )
    }
    if (sbPicker) {
        SidebarColorDialog(
            show = sbPicker,
            initial = sbColor,
            onDismiss = { sbPicker = false },
            onConfirm = { c ->
                sbColor = c
                Prefs.setSidebarColor(ctx, c)
                sbPicker = false
                Toast.makeText(ctx, "叠加层颜色已保存，下次呼出侧边栏即生效", Toast.LENGTH_SHORT).show()
            },
        )
    }

}

// ---------------- 关于页：流动渐变背景 ----------------

/**
 * 「关于与鸣谢」页流动渐变背景的配色。
 *
 * 取自 HyperCeiler 关于页的背景特效（BgEffectPainter + res/raw/bg_frag.glsl：
 * gradientColors1/2/3 三组色值 + uPoints 光斑轨迹 + uAlphaOffset / uAlphaMulti 透明度），
 * 这里是同一套色值在 Compose 里的复刻：粉调打底、紫蓝点缀，深浅色各一组。
 */
private fun aboutGradientPalette(surface: Color): List<Color> {
    val dark = surface.luminance() < 0.5f

    return if (dark) {
        // 深色：HyperCeiler mPhoneDarkData 的 RGBA（第 4 位就是 alpha）
        listOf(
            Color(0.20f, 0.06f, 0.88f, 0.42f),
            Color(0.30f, 0.14f, 0.55f, 0.50f),
            Color(0.00f, 0.64f, 0.96f, 0.45f),
            Color(0.58f, 0.30f, 0.74f, 0.42f),
            Color(0.06f, 0.25f, 0.84f, 0.46f),
            Color(0.66f, 0.26f, 0.62f, 0.42f),
        )
    } else {
        // 浅色：HyperCeiler mPhoneLightData 的 RGB，alpha 按「背景」身份收一档，不抢卡片内容
        listOf(
            Color(1.00f, 0.90f, 0.94f, 0.85f),
            Color(1.00f, 0.84f, 0.89f, 0.80f),
            Color(0.97f, 0.73f, 0.82f, 0.70f),
            Color(0.64f, 0.65f, 0.98f, 0.55f),
            Color(0.58f, 0.74f, 1.00f, 0.55f),
            Color(0.56f, 0.69f, 1.00f, 0.50f),
        )
    }
}

/**
 * 画「关于」页的渐变背景：页面底色打底，再叠若干个低饱和光斑。
 *
 * 每个光斑沿一条李萨如（Lissajous）轨迹缓慢漂移——横向与纵向速度不同、相位逐层错开，
 * 所以看不出明显的循环节拍。[phase] 取 0..1，由 rememberInfiniteTransition 驱动。
 */
private fun DrawScope.drawAboutGradient(
    base: Color,
    palette: List<Color>,
    phase: Float,
    alpha: Float = 1f,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    // alpha 由「关于」页的切页动画驱动：进出这一页时整片渐变淡入淡出，
    // 而不是随页面内容被硬切（原先的「切页闪一下」）
    if (alpha <= 0.001f) return

    // 先铺一层页面底色，避免渐变边缘露出 Scaffold 底色造成色差
    drawRect(color = base, alpha = alpha)

    palette.forEachIndexed { index, color ->
        val t = (phase + index * 0.16f) * 2f * PI.toFloat()
        val cx = w * (0.5f + 0.40f * sin(t * (0.55f + 0.07f * index)))
        val cy = h * (0.42f + 0.34f * cos(t * (0.43f + 0.05f * index)))
        val radius = max(w, h) * (0.46f + 0.07f * index)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = color.alpha * alpha), Color.Transparent),
                center = Offset(cx, cy),
                radius = radius,
            ),
        )
    }
}

// ---------------- 二级页：小窗增强 ----------------

// ---------------- 二级页：camd 控制面板 ----------------

/** 通道目录（仅用于失败提示文案；真实路径由 CamdRescue 维护） */
private const val UI_HINT = "/sdcard/Android/data/com.hyper.Camellia/files"

/** camd 固定监控的两个包，与 C 侧 FIXED_TARGET 严格一致；面板只展示，不可增删 */
private val CAMD_FIXED_PKGS = listOf(Prefs.PKG_SYSUI, "miui.systemui.plugin")

/** 面板一次读取的两份数据：状态快照 + 快照索引 */
private class CamdUi(
    val st: Map<String, String>,
    val snaps: List<CamdRescue.Snap>,
)

/**
 * camd 控制面板。
 *
 * 这一页替掉了原来的「SystemUI 自救」：崩溃探测、APK 备份、卸载更新这些动作
 * 已经全部搬进 /data/adb/camellia/camd 这个独立守护进程，模块只剩两件事——
 *
 *  · 看：直接读 camd 每 5 秒原子写出的 camd_status / camd_snap，
 *    不再 fork 子进程、也不用 root 去查 pid；
 *  · 指挥：所有写操作落进 camd_cmd，camd 每秒轮询取走执行（≤1 秒生效）。
 *
 * 所以页面永远不等系统动作做完：点一下 = 写一行文字，结果由下一次轮询显示。
 * 面板自己按 2 秒节拍重读快照——camd 只在内容变化时重写文件，
 * 不主动读就会永远停在「刚打开那一刻」的数字。
 */
@Composable
private fun CamdPanelPage(version: String, onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf(CamdUi(emptyMap(), emptyList())) }
    // 2 秒心跳；bump 是动作后的立即刷新信号（不用等下一个节拍）
    var beat by remember { mutableIntStateOf(0) }
    var bump by remember { mutableIntStateOf(0) }
    var pendingRestore by remember { mutableStateOf("") }
    // 危险区状态：待确认卸载 / 动作进行中 / 用户已主动卸载（停用标记）
    var pendingUninstall by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var camdOff by remember { mutableStateOf(Prefs.isCamdDisabled(ctx)) }
    /* 动作后的「本地权威状态」。
     * camd 的状态快照是节流写盘的（正常也要几秒到几十秒才落一次），而暂停/启动是 App
     * 用 su 亲自执行的 —— pidof 的结果就是真值，一定比文件新。
     * 只认文件的话，按钮点完界面还是旧样子，得杀 App 重进、等文件追上来才对。
     * 所以动作成功时把真值记下，等状态文件的 ts 超过操作时刻（说明 camd 已写出新快照）
     * 就自动失效交还给文件，不需要手工清除。opPid = -1 表示「确认没有进程」。 */
    var opAt by remember { mutableStateOf(0L) }
    var opPid by remember { mutableStateOf(-1L) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            beat++
        }
    }
    LaunchedEffect(beat, bump) {
        // 抖动健壮性：一次读取异常不要把面板清空/翻“未部署”，保留上一次成功的快照
        val nu = withContext(Dispatchers.IO) {
            try { CamdUi(CamdRescue.status(), CamdRescue.snaps()) } catch (t: Throwable) { null }
        }
        if (nu != null) ui = nu
        try { android.util.Log.i("CamdPanel", CamdRescue.dbgSnap()) } catch (t: Throwable) {}
        camdOff = Prefs.isCamdDisabled(ctx)
    }

    // 进程真值（su pidof camd）：>0=在跑, 0=确实没有, -1=未知。慢频探测 + 二次确认(迟滞)。
    var procPid by remember { mutableStateOf(-1L) }
    var downStreak by remember { mutableIntStateOf(0) }
    LaunchedEffect(bump, Unit) {
        val pid = withContext(Dispatchers.IO) { try { CamdRescue.camdPidNow() } catch (t: Throwable) { -1L } }
        if (pid > 0L) { procPid = pid; downStreak = 0 }
        else if (pid == 0L) { downStreak += 1; if (downStreak >= 2) procPid = 0L }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15000)
            val pid = withContext(Dispatchers.IO) { try { CamdRescue.camdPidNow() } catch (t: Throwable) { -1L } }
            if (pid > 0L) { procPid = pid; downStreak = 0 }
            else if (pid == 0L) { downStreak += 1; if (downStreak >= 2) procPid = 0L }
        }
    }

    /** 下发结果统一提示：失败几乎只有一种原因——通道目录不可写 */
    fun fire(ok: Boolean, msg: String) {
        onToast(if (ok) msg else "指令没发出去：$UI_HINT 不可写")
        bump++
    }

    val st = ui.st
    val ts = st["ts"]?.toLongOrNull() ?: 0L
    val age = if (ts > 0L) System.currentTimeMillis() / 1000L - ts else Long.MAX_VALUE
    // 与 CamdRescue.deployed() 同口径：pid>0 且快照不超过 90 秒
    val fresh = ts > 0L && age in -60L..90L
    val oracle = opAt > 0L && ts <= opAt
    val effPid = if (oracle) opPid else (st["pid"]?.toLongOrNull() ?: 0L)
    /* camdOff 只由「用户主动暂停 / 卸载」写入，而写它的那一刻进程确实已经被杀掉了。
     * 所以只要这个标记还在，就不能认状态文件里的 pid ——camd 停了之后不再更新状态文件，
     * 那份快照会一直停在最后一帧（pid 还写着、看着还挺新），照它显示就会出现
     * 「结论说已暂停、按钮却还是暂停 camd、进程还显示 pid」这种自相矛盾的界面。 */
    val alive = effPid > 0L && !camdOff
    val halt = st["halt"] == "1"
    val de = st["phase"] == "DE"
    val camdVer = st["ver"].orEmpty().ifEmpty { "-" }
    // camd 的版本号编译期从 build.gradle 的 versionName 注入，两边应当同源
    val verSame = camdVer != "-" && camdVer.trim() == version.trim()

    val busyNow = try { CamdRescue.hasBusy() } catch (t: Throwable) { false }
    val (chip, tone, headline) = when {
        // 刚做完动作：先按 App 亲自用 su 确认的结果显示
        oracle && effPid <= 0L ->
            Triple("已暂停", Tone.Warn,
                "camd 已按你的操作停止，二进制、配置和历史快照都还在；点下方「启动 camd」即可恢复。")
        oracle ->
            Triple("正常", Tone.Ok, "camd 已启动（pid " + effPid + "），正在等它写出第一份快照。")
        camdOff && st.isNotEmpty() ->
            Triple("已暂停", Tone.Warn,
                "camd 已停止（你手动停的），二进制、配置和历史快照都还在；点下方「启动 camd」即可恢复。")
        st.isEmpty() && camdOff ->
            Triple("已卸载", Tone.Warn, "camd 组件已被你卸载：崩溃急救不可用。要恢复请点本页最下方「重新部署 camd 组件」。")
        // 进程真值优先：pidof camd —— 忙≠死、状态文件新旧都不影响部署判定
        procPid == 0L ->
            Triple("未部署", Tone.Bad, "camd 进程不存在（pidof 无输出）：没起来，或已退出。")
        busyNow ->
            Triple("运行中", Tone.Ok, "camd 正在后台执行备份/恢复（属正常，不影响已部署），完成后自动刷新。")
        procPid > 0L -> when {
            halt -> Triple("已熔断", Tone.Warn, "连续抢救无效，camd 主动停手：" + st["halt_reason"].orEmpty().ifEmpty { "原因未记录" })
            de -> Triple("DE 阶段", Tone.Warn, "用户还没解锁，急救动作由 camd 在开机早期自主执行。")
            else -> Triple("正常", Tone.Ok, "camd 在跑（pid " + procPid + "），配置已加载，固定监控 " + CAMD_FIXED_PKGS.size + " 个包。")
        }
        // 还没探测到（-1，首帧）：退回旧快照启发
        else -> when {
            st.isEmpty() -> Triple("检测中…", Tone.Warn, "正在探测 camd 进程状态…")
            !fresh -> Triple("失联", Tone.Bad, "状态快照已经 " + fmtAgo(age) + "没更新，camd 大概率已经退出。")
            !alive -> Triple("未运行", Tone.Bad, "状态文件是旧的：camd 进程已经不在了。")
            halt -> Triple("已熔断", Tone.Warn, "连续抢救无效，camd 主动停手：" + st["halt_reason"].orEmpty().ifEmpty { "原因未记录" })
            de -> Triple("DE 阶段", Tone.Warn, "用户还没解锁，急救动作由 camd 在开机早期自主执行。")
            else -> Triple("正常", Tone.Ok, "camd 在跑，配置已加载，固定监控 " + CAMD_FIXED_PKGS.size + " 个包。")
        }
    }

    // ---------------- 环境判定 ----------------

    SmallTitle(text = "环境判定")
    SectionCard {
        Column(modifier = Modifier.padding(16.dp)) {
            StatusChip(chip, tone)
            Spacer(Modifier.height(12.dp))
            Text(
                headline,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            Spacer(Modifier.height(14.dp))
            // 暂停 / 重新启动：省电或排障时最常用的两个动作，紧跟结论放在这里，
            // 省得为了停一下 camd 滚到页面最底下找。
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (alive) {
                    TextButton(
                        text = if (busy) "执行中…" else "暂停 camd",
                        onClick = {
                            if (busy) {
                                onToast("上一个动作还没完成，稍等")
                            } else {
                                busy = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { DaemonInstaller.stop(ctx) }
                                    busy = false
                                    // 真停了就把真值钉住，别让 UI 继续显示节流快照里的旧 pid；
                                    // PARTIAL 说明还有残留，交给 bump 后的文件显示（用户可以再点一次）
                                    if (r == "OK") {
                                        opAt = System.currentTimeMillis() / 1000L
                                        opPid = -1L
                                    }
                                    onToast(
                                        when (r) {
                                            "OK" -> "camd 已暂停，配置与快照都保留着"
                                            "NOSU" -> "暂停失败：拿不到 root 权限"
                                            "PARTIAL" -> "只停掉一部分，仍有残留，可再点一次"
                                            else -> "暂停失败：" + r
                                        },
                                    )
                                    bump++
                                }
                            }
                        },
                    )
                } else {
                    TextButton(
                        text = if (busy) "执行中…" else "重新启动 camd",
                        onClick = {
                            if (busy) {
                                onToast("上一个动作还没完成，稍等")
                            } else {
                                busy = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { DaemonInstaller.restart(ctx) }
                                    busy = false
                                    camdOff = Prefs.isCamdDisabled(ctx)
                                    // 启动结果里带着 camd 的真实 pid：钉住它，界面立刻就是「在跑」，
                                    // 不用等 camd 几十秒后写出第一份快照
                                    val newPid = pidFromDeployResult(r)
                                    if (newPid > 0L) {
                                        opAt = System.currentTimeMillis() / 1000L
                                        opPid = newPid
                                    }
                                    onToast("重新启动：" + r)
                                    bump++
                                }
                            }
                        },
                    )
                }
                Text(
                    if (alive) "停保活、杀进程，配置与快照保留"
                    else "按当前配置重新拉起（二进制没变就不重写）",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            KVRow("camd 版本", if (verSame) camdVer else camdVer + "　≠　模块 " + version.ifEmpty { "?" })
            // uptime 是【系统开机时长】，不是 camd 运行时长；改用 App 侧 pid 观测，
            // 否则重新部署重启后这一行毫无变化，会被误认为「重新部署失效」。
            KVRow(
                "运行时长",
                if (alive) fmtDurCoarse(CamdRescue.procAgeSec(ctx, effPid.toString())) else "—",
            )
            KVRow(
                "进程",
                when {
                    !alive -> "未运行"
                    // 本地权威：快照年龄在这一刻没有意义，直接说明为什么和上次看到的不一样
                    oracle -> "pid " + effPid + "　刚操作，等 camd 写新快照"
                    else -> "pid " + effPid + "　快照 " + fmtAgo(age)
                },
            )
            // 通道决定「改开关多久生效」：socket 直连 > ext4 快通道 > FUSE 轮询。
            // 只有这里能一眼看出优化是否真的生效，否则通道降级是静默的。
            KVRow(
                "通道",
                when {
                    CamdSocket.connected() -> "socket 长连接 · 双向直连，两端零轮询"
                    CamdRescue.isFastChan() -> "ext4 快通道 · inotify 唤醒，毫秒级"
                    else -> "FUSE 轮询 · 最坏 1 秒生效"
                },
            )
            KVRow("配置", if (st["cfg_ok"] == "1") "已加载（用你的配置）" else "默认值（首次自动生成）")
            KVRow("阶段", if (de) "DE · 未解锁" else "CE · 已解锁")
            KVRow(
                "重启计数",
                "软重启 " + (st["sr_count"] ?: "0") + " / " + (st["sr_max"] ?: "?") +
                    "　无效 " + (st["nofx_count"] ?: "0") + " / " + (st["nofx_max"] ?: "?"),
            )
            KVRow("上次急救", camdLastHeal(st))
            KVRow("快照", (st["bak_n"] ?: "0") + " 份　保留 " + (st["bak_keep"] ?: "?"))
        }
    }

    // ---------------- 危险区：卸载 / 重新部署 ----------------

    SmallTitle(text = "camd 组件")
    SectionCard {
        BasicComponent(
            title = if (alive) "一键卸载 camd 组件" else "重新部署 camd 组件",
            summary = if (alive)
                "停保活 · 杀进程 · 删二进制与运行时数据；卸载后不再自动装回"
            else
                "重新释放二进制并拉起守护进程，急救能力恢复",
            startAction = { AppIcon("com.android.systemui", "c") },
            endActions = {
                if (busy) {
                    StatusChip("执行中", Tone.Warn)
                } else {
                    Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null)
                }
            },
            onClick = {
                if (busy) {
                    onToast("上一个动作还没完成，稍等")
                } else if (alive) {
                    pendingUninstall = true
                } else {
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            DaemonInstaller.clearDisabled(ctx)
                            DaemonInstaller.ensureNow(ctx)
                        }
                        busy = false
                        camdOff = Prefs.isCamdDisabled(ctx)
                        onToast("重新部署：" + r)
                        bump++
                    }
                }
            },
        )
    }
    Note(
        "卸载会停掉 service.d 保活脚本、杀掉 camd，并删除 /data/adb/camellia（二进制 / 日志 / 配置 / 快照）、" +
            "运行时目录与面板状态文件。卸载后 SystemUI 与插件组件的崩溃急救全部失效，模块其他功能不受影响；" +
            "App 也不会再自动装回。",
    )

    // ---------------- 行为 ----------------

    SmallTitle(text = "行为")
    SectionCard {
        SwitchPreference(
            title = "自动修复",
            summary = "命中规则时真的动手；关掉就只有诊断，只记录不处置",
            checked = st["auto_fix"] == "1",
            onCheckedChange = { on ->
                fire(CamdRescue.setAutoFix(on), if (on) "已开启自动修复" else "已切换为仅诊断")
            },
        )
        HorizontalDivider()
        SwitchPreference(
            title = "屏蔽系统自救",
            summary = "拦住 SystemUI / 系统框架自己的救援重启循环，避免与 camd 抢着处置",
            checked = st["shield"] == "1",
            onCheckedChange = { on ->
                fire(CamdRescue.setShield(on), if (on) "已开启自救屏蔽" else "已关闭自救屏蔽")
            },
        )
        HorizontalDivider()
        SwitchPreference(
            title = "安装事件监听",
            summary = "盯着监控包的新装包：一装上就核验，坏包立刻收回",
            checked = st["watch_install"] == "1",
            onCheckedChange = { on ->
                fire(CamdRescue.setWatchInstall(on), if (on) "已开启安装监听" else "已关闭安装监听")
            },
        )
        HorizontalDivider()
        SwitchPreference(
            title = "eBPF 加速检测",
            summary = "用内核 eBPF 事件更早更省地发现 SystemUI/组件退出；可用自动开启，关闭或不支持则回退 pidfd+dropbox",
            checked = st["ebpf"] == "1",
            onCheckedChange = { on ->
                fire(CamdRescue.setEbpf(on), if (on) "已开启 eBPF 加速检测" else "已关闭 eBPF（回退 pidfd+dropbox）")
            },
        )
    }

    // ---------------- 阈值 ----------------

    SmallTitle(text = "阈值")
    SectionCard {
        val tick = st["tick_sec"]?.toIntOrNull() ?: 30
        val tickOpts = listOf(15, 30, 60, 120)
        OverlayDropdownPreference(
            title = "自检周期",
            summary = "camd 多久巡检一次现场",
            items = tickOpts.map { "$it 秒" },
            selectedIndex = tickOpts.indexOf(tick).let { if (it < 0) 1 else it },
            onSelectedIndexChange = { i -> fire(CamdRescue.setTickSec(tickOpts[i]), "已下发 " + tickOpts[i] + " 秒") },
        )
        HorizontalDivider()
        val stable = st["stable_sec"]?.toLongOrNull() ?: 900L
        val stableOpts = listOf(60L, 180L, 300L, 600L, 1800L)
        OverlayDropdownPreference(
            title = "稳定观察期",
            summary = "连续稳定这么久才算救回来",
            items = stableOpts.map { (it / 60).toString() + " 分钟" },
            selectedIndex = stableOpts.indexOf(stable).let { if (it < 0) 3 else it },
            onSelectedIndexChange = { i -> fire(CamdRescue.setStableSec(stableOpts[i]), "已下发 " + (stableOpts[i] / 60) + " 分钟") },
        )
        HorizontalDivider()
        val srMax = st["sr_max"]?.toIntOrNull() ?: 2
        val srOpts = listOf(1, 2, 3)
        OverlayDropdownPreference(
            title = "软重启上限",
            summary = "同一轮故障里最多软重启几次",
            items = srOpts.map { "$it 次" },
            selectedIndex = srOpts.indexOf(srMax).let { if (it < 0) 1 else it },
            onSelectedIndexChange = { i -> fire(CamdRescue.setSrMax(srOpts[i]), "已下发 " + srOpts[i] + " 次") },
        )
        HorizontalDivider()
        val nofx = st["nofx_max"]?.toIntOrNull() ?: 2
        val nofxOpts = listOf(1, 2, 3, 5)
        OverlayDropdownPreference(
            title = "无效处置上限",
            summary = "连续几次没效果就停手",
            items = nofxOpts.map { "$it 次" },
            selectedIndex = nofxOpts.indexOf(nofx).let { if (it < 0) 1 else it },
            onSelectedIndexChange = { i -> fire(CamdRescue.setNofxMax(nofxOpts[i]), "已下发 " + nofxOpts[i] + " 次") },
        )
        HorizontalDivider()
        val keep = st["bak_keep"]?.toIntOrNull() ?: 3
        val keepOpts = listOf(0, 1, 2, 3, 5)
        OverlayDropdownPreference(
            title = "快照保留",
            summary = "每个包最多留几份备份，超出淘汰最旧",
            items = keepOpts.map { if (it == 0) "不留" else "$it 份" },
            selectedIndex = keepOpts.indexOf(keep).let { if (it < 0) 3 else it },
            onSelectedIndexChange = { i -> fire(CamdRescue.setBakKeep(keepOpts[i]), "已下发 " + keepOpts[i] + " 份") },
        )
    }
    SectionCard {
        BasicComponent(
            title = "清除熔断",
            summary = if (halt) "当前已熔断：" + st["halt_reason"].orEmpty().ifEmpty { "原因未记录" }
            else "把重启计数与熔断标记归零，让 camd 重新开始计数",
            onClick = { fire(CamdRescue.resetHalt(), "已请求清除熔断") },
        )
    }

    // 监控包固定（camd 侧写死），面板不再展示


    // ---------------- 快照 ----------------

    SmallTitle(text = "快照")
    SectionCard {
        BasicComponent(
            title = "重新扫描快照",
            summary = "重读备份目录，立刻反映最新一份快照",
            onClick = { fire(CamdRescue.refreshSnaps(), "已请求重扫快照") },
        )
    }
    if (ui.snaps.isEmpty()) {
        Note("还没有快照。camd 在第一次处置某个包之前会先把它的 APK 备份到 bak_dir，方便随时回滚。")
    } else {
        SectionCard {
            ui.snaps.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                SnapRow(
                    snap = s,
                    onRestore = { pendingRestore = s.name },
                    onDelete = { fire(CamdRescue.snapDelete(s.name), "已删除快照 " + s.name) },
                )
            }
        }
        Note("「重装」用快照里的 base.apk 覆盖安装（带 -d 允许降版本）；「删除」直接删掉这份快照，不可恢复。")
    }

    if (pendingUninstall) {
        OverlayDialog(
            show = true,
            title = "卸载 camd 组件",
            onDismissRequest = { if (!busy) pendingUninstall = false },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Note(
                    "将依次执行：停掉保活脚本 → 杀掉 camd → 删自启脚本 → 删除二进制与全部运行时数据" +
                        "（日志 / 配置 / 快照 / 状态）。\n\n" +
                        "卸载后崩溃急救不再生效，日志与快照一并清空、不可恢复；模块其他功能不受影响。" +
                        "要恢复请在面板点「重新部署 camd 组件」。",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { if (!busy) pendingUninstall = false },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { DaemonInstaller.uninstall(ctx) }
                                    busy = false
                                    pendingUninstall = false
                                    camdOff = Prefs.isCamdDisabled(ctx)
                                    onToast(
                                        when (r) {
                                            "OK" -> "已卸载 camd 组件"
                                            "PARTIAL" -> "已卸载，但有残留，建议重启设备确认"
                                            "NOSU" -> "卸载失败：拿不到 root 授权"
                                            else -> "卸载失败：" + r
                                        },
                                    )
                                    bump++
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Text(if (busy) "卸载中…" else "确认卸载")
                    }
                }
            }
        }
    }

    val rs = ui.snaps.firstOrNull { it.name == pendingRestore }
    if (rs != null) {
        OverlayDialog(
            show = true,
            title = "重装 " + rs.pkg,
            onDismissRequest = { pendingRestore = "" },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Note(
                    "会用快照里的 base.apk 覆盖安装当前版本（带 -d，允许降版本）。\n" +
                        "装完如果系统仍判它是坏包，camd 可能再次处置——确认这是你想要的结果。",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { pendingRestore = "" },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            fire(CamdRescue.snapRestore(rs.name), "已请求重装 " + rs.pkg)
                            pendingRestore = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("重装")
                    }
                }
            }
        }
    }
}

/** 数值行：左标题右「− 值 +」。到边界时按钮直接不渲染，避免点了没反应。 */
@Composable
private fun NumRow(
    title: String,
    summary: String,
    value: String,
    onDec: (() -> Unit)?,
    onInc: (() -> Unit)?,
) {
    val cs = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Spacer(Modifier.height(3.dp))
            Text(summary, fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariantSummary)
        }
        Spacer(Modifier.width(8.dp))
        if (onDec != null) {
            TextButton(text = "−", onClick = onDec, modifier = Modifier.width(44.dp))
        } else {
            Spacer(Modifier.width(44.dp))
        }
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(72.dp),
        )
        if (onInc != null) {
            TextButton(text = "+", onClick = onInc, modifier = Modifier.width(44.dp))
        } else {
            Spacer(Modifier.width(44.dp))
        }
    }
}

/** 一条快照：包名 + 体积/时间，右侧「重装 / 删除」。没有 APK 的残片不给重装。 */
@Composable
private fun SnapRow(snap: CamdRescue.Snap, onRestore: () -> Unit, onDelete: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    BasicComponent(
        title = snap.pkg,
        summary = fmtBytes(snap.bytes) + " · " + fmtMarkSec(snap.mt) + if (snap.hasApk) "" else " · 无 APK",
        startAction = { AppIcon(snap.pkg, snap.pkg) },
        endActions = {
            if (snap.hasApk) TextButton(text = "重装", onClick = onRestore)
            else Text("残缺", fontSize = 12.sp, color = cs.onSurfaceVariantSummary)
            TextButton(text = "删除", onClick = onDelete)
        },
        onClick = { if (snap.hasApk) onRestore() },
    )
}

// ---------------- 面板用的格式化 ----------------

/** 秒数 → 「3 分 20 秒」这类口语时长；超过一天就只到小时 */
private fun fmtDur(sec: Long): String = when {
    sec <= 0L -> "—"
    sec < 60L -> sec.toString() + " 秒"
    sec < 3600L -> (sec / 60).toString() + " 分 " + (sec % 60) + " 秒"
    sec < 86400L -> (sec / 3600).toString() + " 小时 " + (sec % 3600 / 60) + " 分"
    else -> (sec / 86400).toString() + " 天 " + (sec % 86400 / 3600) + " 小时"
}

/**
 * 从部署结果里抠出 camd 的真实 pid。
 *
 * `camd_service.sh` 启动成功会打印 `started pid=1234 nice=-20 ...`，ensure() 原样带回。
 * 面板拿到这个 pid 就能立刻显示「在跑」和正确的运行时长，不用等 camd 自己把状态文件
 * 落盘（那份快照是节流的，正常也要几十秒）。
 *
 * @return pid；抠不到返回 -1，调用方据此不做本地权威覆盖
 */
private fun pidFromDeployResult(r: String): Long {
    val m = Regex("pid=(\\d+)").find(r) ?: return -1L
    return m.groupValues[1].toLongOrNull() ?: -1L
}

/**
 * 粗粒度时长，只到「分」—— 专给 camd 运行时长用。
 *
 * 面板每 2 秒重绘一次，秒级跳动既没人看，也意味着每 2 秒就有一次文本变化。
 * 粗到分钟既够判断「是不是刚刚重启过」，又让绝大多数重绘的文案完全不变。
 */
private fun fmtDurCoarse(sec: Long): String = when {
    sec < 0L -> "—"
    sec < 60L -> "刚刚启动"
    sec < 3600L -> (sec / 60).toString() + " 分钟"
    sec < 86400L -> (sec / 3600).toString() + " 小时 " + (sec % 3600 / 60) + " 分"
    else -> (sec / 86400).toString() + " 天 " + (sec % 86400 / 3600) + " 小时"
}

/** 「多久以前」：看快照年龄，超过 1 分钟就不必精确到秒 */
private fun fmtAgo(sec: Long): String = when {
    sec == Long.MAX_VALUE || sec < 0L -> "—"
    sec < 60L -> sec.toString() + " 秒前"
    sec < 3600L -> (sec / 60).toString() + " 分钟前"
    sec < 86400L -> (sec / 3600).toString() + " 小时前"
    else -> (sec / 86400).toString() + " 天前"
}

/** 秒级时间戳 → 「10-25 12:14」 */
private fun fmtMarkSec(sec: Long): String =
    if (sec <= 0L) "—" else android.text.format.DateFormat.format("MM-dd HH:mm", sec * 1000L).toString()

/** 快照体积：只有 APK 一种文件，保留 1 位小数就够 */
private fun fmtBytes(n: Long): String = when {
    n <= 0L -> "0 B"
    n < 1024L -> n.toString() + " B"
    n < 1048576L -> (n / 1024L).toString() + " KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
}

/** 「上次急救」：动作 + 包名 + 原因 + 时间；从没动过手就明确说「无」 */
private fun camdLastHeal(st: Map<String, String>): String {
    val act = st["a_last_action"].orEmpty()
    if (act.isEmpty() || act == "-") return "无"
    val pkg = st["a_last_pkg"].orEmpty().let { if (it == "-") "" else it }
    // why 是失败原因/结果摘要（手动快照重装也走这条通道），比动作名有用得多，
    // 没有它用户只能看到「重装过」却不知道成没成。
    val why = st["a_last_why"].orEmpty().let { if (it == "-") "" else it }
    val sec = st["a_last_ts"]?.toLongOrNull() ?: 0L
    val when0 = if (sec > 0L) fmtMarkSec(sec) else "时间未知"
    return act + (if (pkg.isEmpty()) "" else " " + pkg) +
        (if (why.isEmpty()) "" else " · " + why) + " · " + when0
}

/** 恢复用 APK 的固定落地文件。设备加密存储（DE）：
 *  /data/user_de/0/com.hyper.Camellia/files/apk/{sysui,plugin}.apk
 *
 *  必须用 DE 而不是默认的 CE 目录——data 未解密（锁屏前、开机直起）时
 *  /data/user/0/... 整片挂载点还不存在，救援时读到的就是「文件不存在」。 */
/** 「恢复用包」的来源记录 sidecar：与包体同目录、同名加 .origin。
 *
 *  APK 本身完全不携带「从哪台设备提取」的信息——包名、版本号、签名证书都不含设备标识，
 *  所以只有在自己提取时记一份 sidecar 才知道来历。手动导入的包无从追溯，
 *  一律按「外部导入 · 设备未知」处理并给出风险提示。
 *
 *  记录里存 size + lastModified：包体一旦被换掉（重新导入 / 手动替换文件），
 *  旧来源立刻失效，避免拿旧标签贴在新文件上——比不显示更误导。
 */
private fun apkOriginFile(ctx: Context, slug: String): java.io.File {
    val f = apkFixedFile(ctx, slug)
    return java.io.File(f.parentFile, f.name + ".origin")
}

/** 本机提取成功后落一份来源标记 */
private fun writeApkOrigin(ctx: Context, slug: String, src: String) {
    try {
        val f = apkFixedFile(ctx, slug)
        val o = org.json.JSONObject()
            .put("model", android.os.Build.MODEL ?: "")
            .put("brand", android.os.Build.BRAND ?: "")
            .put("release", android.os.Build.VERSION.RELEASE ?: "")
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("fp", android.os.Build.FINGERPRINT ?: "")
            .put("size", f.length())
            .put("mtime", f.lastModified())
            .put("ts", System.currentTimeMillis())
            .put("src", src)
        apkOriginFile(ctx, slug).writeText(o.toString())
    } catch (t: Throwable) {
    }
}

/** 包体被替换后来源记录立刻作废 */
private fun clearApkOrigin(ctx: Context, slug: String) {
    try {
        apkOriginFile(ctx, slug).delete()
    } catch (t: Throwable) {
    }
}

/** 来源说明。first=展示文本（空串表示还没有包），second=是否可信的本机包。 */
private fun apkOriginOf(ctx: Context, slug: String): Pair<String, Boolean> {
    val f = apkFixedFile(ctx, slug)
    if (!f.exists() || f.length() < 4096L) return "" to false
    val of = apkOriginFile(ctx, slug)
    if (!of.exists() || of.length() == 0L) {
        return "来源：外部导入 · 设备未知（包内不含设备信息，无法核实是否同代包）" to false
    }
    val o = try {
        org.json.JSONObject(of.readText())
    } catch (t: Throwable) {
        return "来源：外部导入 · 设备未知" to false
    }
    val model = o.optString("model", "?")
    val brand = o.optString("brand", "")
    val rel = o.optString("release", "?")
    val fp = o.optString("fp", "")
    val size = o.optLong("size", -1L)
    val mtime = o.optLong("mtime", -1L)
    val ts = o.optLong("ts", 0L)
    val whenText = if (ts > 0L) fmtMark(ts.toString()) else "?"
    if (size != f.length() || mtime != f.lastModified()) {
        return "来源：本机提取过，但包体已被替换（$model · Android $rel · $whenText）" to false
    }
    // 换机 / 恢复备份时 sidecar 会跟着数据一起迁移，但它描述的是旧机上的包
    val sameDev = fp.isEmpty() || fp == (android.os.Build.FINGERPRINT ?: "")
    val who = (if (brand.isEmpty()) "" else brand + " ") + model + " · Android " + rel
    return if (sameDev) {
        "来源：本机提取 · $who · $whenText" to true
    } else {
        "来源：另一台设备提取 · $who（与当前设备代次不同，覆盖安装多半失败）" to false
    }
}

private fun apkFixedFile(ctx: Context, slug: String): java.io.File {
    val de = ctx.createDeviceProtectedStorageContext()
    val dir = java.io.File(de.filesDir, "apk")
    if (!dir.exists()) dir.mkdirs()
    val name = if (Prefs.TGT_PLUGIN == slug) "plugin.apk" else "sysui.apk"
    return java.io.File(dir, name)
}

/** 该位置要求的包名 */
private fun apkTargetPkg(slug: String): String =
    if (Prefs.TGT_PLUGIN == slug) "miui.systemui.plugin" else "com.android.systemui"

/** 不安装也要读出 APK 的包名/版本。
 *
 *  注意 flags 用 0：带 GET_ACTIVITIES 去解析 systemui 这种 50MB+ 的大包
 *  会解析失败返回 null，进而让下面 apkStorePath 把好端端的路径写成空串。 */
private fun apkInfoOf(ctx: Context, f: java.io.File): android.content.pm.PackageInfo? = try {
    ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
} catch (t: Throwable) {
    null
}

/** 「所选包 vs 本机已装」的差异说明。空串表示无法比对或完全一致。 */
private fun apkCompatNote(ctx: Context, slug: String): String {
    val f = apkFixedFile(ctx, slug)
    if (!f.exists() || f.length() < 4096L) return ""
    val pkg = apkTargetPkg(slug)
    val mine = try {
        ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
    } catch (t: Throwable) {
        null
    } ?: return ""
    val inst = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
    } catch (t: Throwable) {
        null
    }
    val sb = StringBuilder()
    if (inst != null) {
        @Suppress("DEPRECATION")
        val vcMine = mine.versionCode
        @Suppress("DEPRECATION")
        val vcInst = inst.versionCode
        @Suppress("DEPRECATION")
        val uidMine = mine.sharedUserId ?: ""
        @Suppress("DEPRECATION")
        val uidInst = inst.sharedUserId ?: ""
        // sharedUserId 是覆盖安装最硬的一道墙：它在各代 ROM 之间从
        // android.uid.system 变成 android.uid.systemui，一旦与已装不一致，
        // pm 无条件抛 INSTALL_FAILED_UID_CHANGED，签名验证绕过都救不了。
        // 两边都读到了才比：getPackageArchiveInfo 对大包的字段填充并不总完整，
        // 空值当差异报出去就是纯误报，比不报更糟。
        if (uidMine.isNotEmpty() && uidInst.isNotEmpty() && uidMine != uidInst) {
            sb.append("⚠️ sharedUserId 不同：本机已装 ")
                .append(if (uidInst.isEmpty()) "（无）" else uidInst)
                .append("，这个包声明 ")
                .append(if (uidMine.isEmpty()) "（无）" else uidMine)
                .append("。覆盖安装必然报 INSTALL_FAILED_UID_CHANGED，核心破解也绕不过——")
                .append("这个包不是本机这一代的，建议改从本机提取。\n")
        }
        if (vcMine == vcInst) {
            sb.append("版本与本机已装一致（").append(vcInst).append("）。")
        } else if (vcMine > vcInst) {
            sb.append("版本比本机已装高（").append(vcMine).append(" > ").append(vcInst)
                .append("），可正常升级安装。")
        } else {
            sb.append("版本比本机已装低（").append(vcMine).append(" < ").append(vcInst)
                .append("），降级安装要 -d，部分 ROM 仍会拒。")
        }
    }
    // 签名单独比一次。取不到（大包解析失败等）就跳过，不因此挡住提示。
    val sigMine = sigDigestOf(ctx, f, false)
    val sigInst = if (inst != null) sigDigestOf(ctx, f, true) else null
    if (sigMine != null && sigInst != null) {
        sb.append(if (sigMine == sigInst) "\n签名与本机已装一致。"
        else "\n⚠️ 签名与本机已装不一致，装包需要核心破解关掉签名验证。")
    }
    return sb.toString().trim()
}

/** 取签名证书 SHA-256 摘要。installed=false 读 APK 文件，true 读本机已装。 */
private fun sigDigestOf(ctx: Context, f: java.io.File, installed: Boolean): String? = try {
    val flag = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
    val pi = if (installed) {
        val pkg = ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0)?.packageName
        if (pkg == null) null else ctx.packageManager.getPackageInfo(pkg, flag)
    } else {
        ctx.packageManager.getPackageArchiveInfo(f.absolutePath, flag)
    }
    val cert = if (pi == null) null else {
        val si = if (android.os.Build.VERSION.SDK_INT >= 28) pi.signingInfo else null
        if (si != null) {
            if (si.hasMultipleSigners()) si.apkContentsSigners.firstOrNull()
            else si.signingCertificateHistory?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pi.signatures?.firstOrNull()
        }
    }
    if (cert == null) null else {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.digest(cert.toByteArray()).joinToString("") { b ->
            String.format(java.util.Locale.US, "%02x", b)
        }
    }
} catch (t: Throwable) {
    null
}

/** 让 system uid 能「穿过并读到」这个包。
 *
 *  DE 根目录和 files/ 默认是 drwx------（属主是 App 自己），system_server
 *  即便能看见 apk/ 里的文件也穿不过中间两级——实测 su 1000 直接
 *  Permission denied。所以这里从文件往上给每一级加 o+x（只给「可穿透」，
 *  不给「可列目录」），文件本身再给 others 读。 */
private fun relaxForSystem(f: java.io.File) {
    try {
        f.setReadable(true, false)
    } catch (t: Throwable) {
    }
    var d: java.io.File? = f.parentFile
    var guard = 0
    while (d != null && guard < 4) {
        try {
            d.setExecutable(true, false)
        } catch (t: Throwable) {
        }
        if (d.name == "com.hyper.Camellia") break
        d = d.parentFile
        guard++
    }
}

/** 再同步一份到 /data/system/camellia_bak/apk/ —— system_server 自己的地盘，
 *  既没有 app 私有目录的 DAC 问题，也不受 data 是否解密影响，是救援时最稳的
 *  取包位置。走 su 异步做，失败就算了（DE + 权限放宽那一路还能用）。 */
private fun pushApkToSystemBackup(slug: String, src: java.io.File) {
    val name = if (Prefs.TGT_PLUGIN == slug) "plugin.apk" else "sysui.apk"
    Thread {
        try {
            val d = src.absolutePath
            val cmd = "mkdir -p /data/system/camellia_bak/apk && cp -f '" + d +
                "' /data/system/camellia_bak/apk/" + name +
                " && chmod 644 /data/system/camellia_bak/apk/" + name
            val p = ProcessBuilder("/system/bin/su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        } catch (t: Throwable) {
        }
    }.start()
}

private fun fmtMark(v: String): String {
    if (v.isEmpty()) return "—"
    return try {
        val ms = v.toLong()
        if (ms <= 0L) "—" else android.text.format.DateFormat.format("MM-dd HH:mm:ss", ms).toString()
    } catch (e: Throwable) {
        v
    }
}

@Composable
private fun DesktopPage(
    onToast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var launcherFf by remember { mutableStateOf(Prefs.isUlOn(ctx, "launcher_freeform")) }
    SmallTitle(text = "桌面小窗放行（A）")
    SectionCard {
        SwitchPreference(
            title = "桌面内自由小窗",
            summary = "由 native 补丁放行桌面自身的 freeform 快捷方式过滤。它只负责「允许」，本身不产生任何菜单项",
            checked = launcherFf,
            onCheckedChange = {
                launcherFf = it
                Prefs.setUlOn(ctx, "launcher_freeform", it)
                onToast(
                    if (it) "已开启：补丁在桌面进程里加载，重启桌面后生效"
                    else "已关闭：已加载的补丁不会自动撤销，重启桌面后生效"
                )
            },
        )
    }
    Note(
        "这里只负责放行，不产生菜单项。长按菜单里的「小窗」这一项由「小窗增强」页的「长按图标菜单增加「小窗」」开关提供数据，" +
            "两者分属不同功能，各自独立开关。"
    )

}

@Composable
private fun FreeformPage(
    onToast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var countLimit by remember { mutableStateOf(Prefs.isFfCountLimit(ctx)) }
    var shortcutFf by remember { mutableStateOf(Prefs.isUlOn(ctx, "app_shortcut_freeform")) }
    var blacklist by remember { mutableStateOf(Prefs.isFfBlacklist(ctx)) }
    var pinFg by remember { mutableStateOf(Prefs.isFfPinForeground(ctx)) }
    var canSlide by remember { mutableStateOf(Prefs.isFfCanSlide(ctx)) }
    var capTop by remember { mutableStateOf(Prefs.isFfCaptionTop(ctx)) }
    var capBottom by remember { mutableStateOf(Prefs.isFfCaptionBottom(ctx)) }
    var homeBar by remember { mutableStateOf(Prefs.isFfHomeBar(ctx)) }

    SmallTitle(text = "系统框架（system_server）")
    SectionCard {
        SwitchPreference(
            title = "解除小窗数量限制",
            summary = "允许多个小窗同时运行，解除系统默认的数量配额上限",
            checked = countLimit,
            onCheckedChange = {
                countLimit = it
                Prefs.setFfCountLimit(ctx, it)
                onToast("设置已保存，重启系统或相关应用后生效")
            },
        )
        HorizontalDivider()
        SwitchPreference(
            title = "解除小窗黑名单拦截",
            summary = "强制允许所有应用开启小窗模式，并抑制「当前应用不支持小窗」弹窗提示",
            checked = blacklist,
            onCheckedChange = {
                blacklist = it
                Prefs.setFfBlacklist(ctx, it)
                onToast("设置已保存，重启系统或相关应用后生效")
            },
        )
        HorizontalDivider()
        SwitchPreference(
            title = "前台小窗防冻结",
            summary = "小窗处于前台时维持进程活跃状态，防止系统后台冻结或杀死",
            checked = pinFg,
            onCheckedChange = {
                pinFg = it
                Prefs.setFfPinForeground(ctx, it)
                onToast("设置已保存，重启系统或相关应用后生效")
            },
        )
    }
    Note(
        "作用域：系统框架（android）。若修改后未立即生效，请确保在 LSPosed 管理器中已勾选「系统框架」作用域并重启手机。"
    )

    SmallTitle(text = "桌面快捷方式")
    SectionCard {
        SwitchPreference(
            title = "长按图标菜单增加「小窗」",
            summary = "在桌面长按应用图标弹出的快捷菜单里增加一项「小窗」，点击后直接以小窗方式启动该应用",
            checked = shortcutFf,
            onCheckedChange = {
                shortcutFf = it
                Prefs.setUlOn(ctx, "app_shortcut_freeform", it)
                onToast("设置已保存；无需重启，回桌面长按图标即可看到")
            },
        )
    }
    Note(
        "实现方式：系统侧（system_server）合成一条快捷方式并接管它的图标与点击，不改动桌面 APK 本身。" +
            "开关关闭后新菜单不再出现该项（已显示过的菜单需重新长按）。"
    )

    SmallTitle(text = "系统界面（SystemUI）")
    SectionCard {
        SwitchPreference(
            title = "通知下拉全小窗支持",
            summary = "解除下拉横幅通知打开小窗的白名单限制，允许所有通知直接下拉为小窗",
            checked = canSlide,
            onCheckedChange = {
                canSlide = it
                Prefs.setFfCanSlide(ctx, it)
                onToast("设置已保存，重启系统界面后生效")
            },
        )
        HorizontalDivider()
        SwitchPreference(
            title = "隐藏小窗顶部标题栏",
            summary = "隐藏小窗顶部的窗口标题栏与指示点（含拖拽把手），观感更干净",
            checked = capTop,
            onCheckedChange = {
                capTop = it
                Prefs.setFfCaptionTop(ctx, it)
                onToast("设置已保存，重启系统界面后生效")
            },
        )
        HorizontalDivider()
        SwitchPreference(
            title = "隐藏小窗底部标题栏",
            summary = "隐藏小窗底部的操作标题栏，仅保留窗口本体",
            checked = capBottom,
            onCheckedChange = {
                capBottom = it
                Prefs.setFfCaptionBottom(ctx, it)
                onToast("设置已保存，重启系统界面后生效")
            },
        )
        HorizontalDivider()
        SwitchPreference(
            title = "隐藏底部手势小白条",
            summary = "隐藏小窗底部的系统手势提示条，避免遮挡内容",
            checked = homeBar,
            onCheckedChange = {
                homeBar = it
                Prefs.setFfHomeBar(ctx, it)
                onToast("设置已保存，重启系统界面后生效")
            },
        )
    }
    Note(
        "作用域：系统界面（com.android.systemui）。"
    )
}

// ---------------- 二级页：设置（模块自身外观） ----------------

/**
 * 「设置」页：只放模块自己的外观开关，与三个目标应用无关。
 *
 * 为什么要单独一页：目标应用页里的每一项都会影响目标进程的行为，
 * 而主题模式 / 底栏样式只影响本设置页的渲染，混在一起会让人误以为
 * 「改主题也要重启互传」。这里单独收拢，并在标题上说明「模块自身外观」。
 */
@Composable
private fun SettingsPage(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    hideIcon: Boolean,
    onHideIconChange: (Boolean) -> Unit,
    barStyle: String,
    onBarStyleChange: (String) -> Unit,
    topBarStyle: String, onTopBarStyleChange: (String) -> Unit,
    monet: Boolean, onMonetChange: (Boolean) -> Unit,
    pureBlack: Boolean, onPureBlackChange: (Boolean) -> Unit,
    blurLevel: Int, onBlurChange: (Int) -> Unit,
    uiScale: Int, onUiScaleChange: (Int) -> Unit,
    showHints: Boolean, onShowHintsChange: (Boolean) -> Unit,
    themeStyle: String, onThemeStyleChange: (String) -> Unit,
    colorStandard: String, onColorStandardChange: (String) -> Unit,
    accentColor: Int, onAccentColorChange: (Int) -> Unit,
    uiStyle: String, onUiStyleChange: (String) -> Unit,
    onShowGuide: () -> Unit,
    onForceStop: () -> Unit,
    onForceStopPkg: (String, String) -> Unit,
    onRestartSelf: () -> Unit,
) {
    SmallTitle(text = "主题设置")
    SectionCard {
        OverlayDropdownPreference(
            title = "界面风格",
            summary = "Miuix（山茶花）/ Material 3（紫调基线）",
            items = listOf("Miuix", "Material 3"),
            selectedIndex = if (uiStyle == Prefs.UI_MATERIAL) 1 else 0,
            onSelectedIndexChange = { i -> onUiStyleChange(if (i == 1) Prefs.UI_MATERIAL else Prefs.UI_MIUIX) },
        )
        HorizontalDivider()
        OverlayDropdownPreference(
            title = "色彩标准",
            summary = "Material 3 色彩规范 2021 / 2025",
            items = listOf("2021", "2025"),
            selectedIndex = if (themeStyle == "2025") 1 else 0,
            onSelectedIndexChange = { i -> onThemeStyleChange(if (i == 1) "2025" else "2021") },
        )
        HorizontalDivider()
        SwitchPreference(
            title = "使用 Monet 取色",
            summary = "从壁纸提取强调色，跟随系统 Material You",
            checked = monet,
            onCheckedChange = { onMonetChange(it) },
        )
        HorizontalDivider()
        ColorChoiceRow(
            title = "色彩风格",
            summary = "Monet 调色风格",
            selectedIndex = listOf("TonalSpot","Neutral","Vibrant","Expressive","Rainbow","FruitSalad","Monochrome","Fidelity","Content").indexOf(colorStandard).let { if (it < 0) 0 else it },
            options = listOf("TonalSpot","Neutral","Vibrant","Expressive","Rainbow","FruitSalad","Monochrome","Fidelity","Content").map { it to schemeTriple(if (accentColor != 0) accentColor else 0xFFC2185B.toInt(), it, LocalCamelliaDark.current) },
            onSelect = { i -> onColorStandardChange(listOf("TonalSpot","Neutral","Vibrant","Expressive","Rainbow","FruitSalad","Monochrome","Fidelity","Content")[i]) },
        )
        HorizontalDivider()
        ColorChoiceRow(
            title = "强调色",
            summary = "主色（作为 Monet keyColor）",
            selectedIndex = listOf(0,0xFFC2185B.toInt(),0xFF6750A4.toInt(),0xFF1976D2.toInt(),0xFF00897B.toInt(),0xFF43A047.toInt(),0xFFF57C00.toInt(),0xFFE91E63.toInt()).indexOf(accentColor).let { if (it < 0) 0 else it },
            options = listOf(0 to "默认",0xFFC2185B.toInt() to "玫红",0xFF6750A4.toInt() to "紫",0xFF1976D2.toInt() to "蓝",0xFF00897B.toInt() to "青",0xFF43A047.toInt() to "绿",0xFFF57C00.toInt() to "橙",0xFFE91E63.toInt() to "粉").map { (argb, name) -> name to schemeTriple(if (argb == 0) 0xFFC2185B.toInt() else argb, "TonalSpot", LocalCamelliaDark.current) },
            onSelect = { i -> onAccentColorChange(listOf(0,0xFFC2185B.toInt(),0xFF6750A4.toInt(),0xFF1976D2.toInt(),0xFF00897B.toInt(),0xFF43A047.toInt(),0xFFF57C00.toInt(),0xFFE91E63.toInt())[i]) },
        )
        HorizontalDivider()
        SwitchPreference(
            title = "深色使用纯黑背景",
            summary = "深色模式下用纯黑底，更省电更纯粹",
            checked = pureBlack,
            onCheckedChange = { onPureBlackChange(it) },
        )
        HorizontalDivider()
        SwitchPreference(
            title = "模糊效果",
            summary = "顶栏/底栏液体玻璃的模糊；关闭更省、更通透",
            checked = blurLevel > 0,
            onCheckedChange = { on -> onBlurChange(if (on) 2 else 0) },
        )
        HorizontalDivider()
        SliderPreference(
            title = "界面缩放",
            summary = "整体 UI 大小（85%~115%）",
            value = uiScale.toFloat(),
            onValueChange = { v -> onUiScaleChange(((v.toInt() / 5) * 5).coerceIn(85, 115)) },
            valueRange = 85f..115f,
            keyPoints = listOf(85f, 90f, 95f, 100f, 105f, 110f, 115f),
            showKeyPoints = true,
        )
        HorizontalDivider()
        SwitchPreference(
            title = "显示说明文字",
            summary = "显示/隐藏各设置项下面的说明",
            checked = showHints,
            onCheckedChange = { onShowHintsChange(it) },
        )
    }

    SmallTitle(text = "主题模式")
    SectionCard {
        OverlayDropdownPreference(
            title = "主题模式",
            summary = "跟随系统 / 浅色 / 深色",
            items = listOf("跟随系统", "浅色", "深色"),
            selectedIndex = when (themeMode) { Prefs.THEME_LIGHT -> 1; Prefs.THEME_DARK -> 2; else -> 0 },
            onSelectedIndexChange = { i ->
                onThemeModeChange(when (i) { 1 -> Prefs.THEME_LIGHT; 2 -> Prefs.THEME_DARK; else -> Prefs.THEME_SYSTEM })
            },
        )
    }
    Note(
        "只改本设置页的配色（背景 / 文字 / 玻璃底色），不会影响三个目标应用的任何行为。\n" +
            "切换后本页立即重建配色，不用退出重进。",
    )

    SmallTitle(text = "顶栏样式")
    SectionCard {
        OverlayDropdownPreference(
            title = "顶栏样式",
            summary = "经典 / 圆角 / 液态玻璃",
            items = listOf("经典（直角）", "圆角", "液态玻璃"),
            selectedIndex = when (topBarStyle) { Prefs.TOP_ROUND -> 1; Prefs.TOP_GLASS -> 2; else -> 0 },
            onSelectedIndexChange = { i -> onTopBarStyleChange(when (i) { 1 -> Prefs.TOP_ROUND; 2 -> Prefs.TOP_GLASS; else -> Prefs.TOP_CLASSIC }) },
        )
    }

    SmallTitle(text = "底栏样式")
    SectionCard {
        OverlayDropdownPreference(
            title = "底栏样式",
            summary = "HyperOS / 悬浮 / 液态玻璃",
            items = listOf("HyperOS 底栏", "HyperOS 悬浮底栏", "液态玻璃底栏"),
            selectedIndex = when (barStyle) { Prefs.BAR_FLOATING -> 1; Prefs.BAR_GLASS -> 2; else -> 0 },
            onSelectedIndexChange = { i ->
                onBarStyleChange(when (i) { 1 -> Prefs.BAR_FLOATING; 2 -> Prefs.BAR_GLASS; else -> Prefs.BAR_HYPEROS })
            },
        )
    }
    Note(
        "三种样式共用同一份玻璃来源层（页面层），只是形状与玻璃参数不同；\n" +
            "切换后立即生效，不需要重启任何应用。",
    )

    SmallTitle(text = "桌面图标")
    SectionCard {
        SwitchPreference(
            title = "隐藏桌面图标",
            summary = "隐藏后仍可从 LSPosed 管理器 -> 模块 -> 打开设置进入本页",
            checked = hideIcon,
            onCheckedChange = onHideIconChange,
        )
    }
    Note("从「小米互传」页迁到这里：它属于模块自身设置，不影响目标进程。")

    SmallTitle(text = "引导")
    SectionCard {
        BasicComponent(
            title = "重新查看引导",
            summary = "用户协议、必要授权与环境检查",
            onClick = onShowGuide,
        )
    }

    SmallTitle(text = "快捷操作")
    SectionCard {
        BasicComponent(
            title = "重启小米互传",
            summary = "强制结束后台进程，下次打开即用当前设置",
            onClick = onForceStop,
        )
        HorizontalDivider()
        BasicComponent(
            title = "重启小米画报",
            summary = "同步目录 / 上限改动需要它重新启动才生效",
            onClick = { onForceStopPkg(Prefs.FG_PKG, "小米画报") },
        )
        HorizontalDivider()
        BasicComponent(
            title = "重启手机管家",
            summary = "结束整套进程（含 :ui），模块改动会立即重新注入",
            onClick = { onForceStopPkg(Prefs.SECURITY_PKG, "手机管家") },
        )
        HorizontalDivider()
        BasicComponent(
            title = "重启系统安全组件",
            summary = "gp_* 三条防护的开关改动要靠它重新注入",
            onClick = { onForceStopPkg(Unlock.PKG_GP, "系统安全组件") },
        )
        HorizontalDivider()
        BasicComponent(
            title = "重载本设置页",
            summary = "重新读取配置文件并重建界面（不用退出重进）",
            onClick = onRestartSelf,
        )
    }
    Note(
        "「重启」用 root 的 am force-stop 结束进程，最彻底；没有 root 时只能结束后台进程，\n" +
            "系统应用（手机管家）在无 root 情况下可能打不动，需要手动在系统设置里强行停止。",
    )
}

/** 底栏样式的中文名（用于提示语） */
private fun schemeTriple(seedArgb: Int, style: String, dark: Boolean): List<Color> {
    val h = Hct.fromInt(seedArgb)
    val hue = h.hue
    val ch = when (style) {
        "Monochrome" -> 0.0; "Neutral" -> 8.0; "Vibrant" -> 56.0
        "Fidelity" -> Math.max(h.chroma, 48.0); "Content" -> h.chroma
        "Expressive" -> 40.0; "Rainbow" -> 48.0; "FruitSalad" -> 42.0; else -> 36.0
    }
    val tone = if (dark) 80.0 else 40.0
    val h2 = when (style) { "Expressive" -> hue + 240; "Rainbow" -> hue + 120; "FruitSalad" -> hue + 180; "Vibrant" -> hue + 30; "Neutral" -> hue + 8; else -> hue + 15 }
    val h3 = when (style) { "Expressive" -> hue + 60; "Rainbow" -> hue + 240; "FruitSalad" -> hue + 270; "Vibrant" -> hue - 30; else -> hue + 60 }
    fun n(x: Double) = ((x % 360) + 360) % 360
    fun c(hh: Double, cc: Double) = Color(Hct.from(n(hh), cc, tone).toInt())
    return listOf(c(hue, ch), c(h2, ch * 0.6), c(h3, ch * 0.8))
}

@Composable
private fun ColorChoiceRow(
    title: String,
    summary: String,
    selectedIndex: Int,
    options: List<Pair<String, List<Color>>>,
    onSelect: (Int) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    BasicComponent(
        title = title,
        summary = summary,
        endActions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                options.getOrNull(selectedIndex)?.let { TriDots(it.second) }
                Spacer(Modifier.width(8.dp))
                Text(
                    options.getOrNull(selectedIndex)?.first ?: "",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        },
        onClick = { show = true },
    )
    if (show) {
        OverlayDialog(show = true, title = title, onDismissRequest = { show = false }) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEachIndexed { i, opt ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(i); show = false }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriDots(opt.second)
                        Spacer(Modifier.width(12.dp))
                        Text(opt.first, modifier = Modifier.weight(1f), fontSize = 15.sp)
                        if (i == selectedIndex) Text("✓", color = CamelliaTheme.colors.brand)
                    }
                }
            }
        }
    }
}

@Composable
private fun TriDots(cs: List<Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        cs.forEach { Box(Modifier.size(14.dp).clip(CircleShape).background(it)) }
    }
}

@Composable
private fun PaletteStylePicker(selected: String, seed: Int, dark: Boolean, onPick: (String) -> Unit) {
    val styles = listOf("TonalSpot", "Neutral", "Vibrant", "Expressive", "Rainbow", "FruitSalad", "Monochrome", "Fidelity", "Content")
    Column {
        styles.forEach { v ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onPick(v) }.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TriDots(schemeTriple(seed, v, dark))
                Spacer(Modifier.width(12.dp))
                Text(v, modifier = Modifier.weight(1f), fontSize = 15.sp)
                if (selected == v) Text("✓", color = CamelliaTheme.colors.brand)
            }
        }
    }
}

@Composable
private fun AccentSwatchRow(selected: Int, dark: Boolean, onPick: (Int) -> Unit) {
    val presets = listOf(
        0 to "默认", 0xFFC2185B.toInt() to "玫红", 0xFF6750A4.toInt() to "紫",
        0xFF1976D2.toInt() to "蓝", 0xFF00897B.toInt() to "青", 0xFF43A047.toInt() to "绿",
        0xFFF57C00.toInt() to "橙", 0xFFE91E63.toInt() to "粉",
    )
    Column {
        presets.forEach { (argb, name) ->
            val sel = selected == argb
            val seed = if (argb == 0) 0xFFC2185B.toInt() else argb
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onPick(argb) }.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TriDots(schemeTriple(seed, "TonalSpot", dark))
                Spacer(Modifier.width(12.dp))
                Text(name, modifier = Modifier.weight(1f), fontSize = 15.sp)
                if (sel) Text("✓", color = CamelliaTheme.colors.brand)
            }
        }
    }
}

private fun topBarStyleName(style: String): String = when (style) {
    Prefs.TOP_ROUND -> "圆角"
    Prefs.TOP_GLASS -> "液态玻璃"
    else -> "经典（直角）"
}

private fun barStyleName(style: String): String = when (style) {
    Prefs.BAR_HYPEROS -> "HyperOS 底栏"
    Prefs.BAR_FLOATING -> "HyperOS 悬浮底栏"
    else -> "液态玻璃底栏"
}

// ---------------- 底栏：三样式共用一套实现 ----------------

/**
 * 底栏观感的全部参数。
 *
 * 三种样式的差别收敛到这几个数值上，绘制与交互只有一份实现，
 * 新增样式只是再加一组参数，不必复制一遍 NavigationBar。
 *
 * 数值参考 AndroidLiquidGlass 官方示例（catalog/components/LiquidBottomTabs）：
 * 液体玻璃 = 小模糊 + 强折射 + 低不透明底面 + 静止时没有高光。
 * 之前那套「大模糊 + 高不透明度底面 + 恒定高光」只会得到一块白色毛玻璃砖。
 */
private class BottomBarLook(
    /** 玻璃形状：同时用于裁剪、投影与折射取圆角，必须是圆角系（胶囊用 50% 圆角表达） */
    val shape: Shape,
    /** 左右外边距：0dp = 整宽贴边 */
    val sideMargin: Dp,
    /** 悬浮样式在系统导航条之上再抬起的距离 */
    val bottomLift: Dp,
    /** 是否悬浮：false = 贴着屏幕底端，由内容自己吃系统导航条内距 */
    val floating: Boolean,
    val blur: Dp,
    /** 折射区厚度：越大边缘扭曲越明显 */
    val lensHeight: Dp,
    /** 折射位移量 */
    val lensAmount: Dp,
    /** 折射是否模拟玻璃厚度分层（更「液体」）；直角形状上用不到 */
    val deepLens: Boolean,
    /** 玻璃底色不透明度：越低越透 */
    val scrim: Float,
)

/**
 * 底栏：主页 / 设置 / 关于 三个 Tab。
 *
 * 玻璃来源层与顶栏共用同一个「页面层」，所以滚动内容从它下面经过时
 * 同样是「模糊 + 折射」的液体玻璃；样式只决定形状、位置与玻璃强度。
 */
@Composable
private fun CamelliaBottomBar(
    style: String,
    backdrop: Backdrop,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onHeightChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalCamelliaDark.current
    // 玻璃底面着色：浅色主题用白、深色主题用黑，且都压得很低，
    // 让背景真正透上来 —— 这是「液体玻璃」与「白色长条」的分界线
    val tint = if (dark) Color.Black else Color.White
    val glassBlur = LocalGlassBlur.current
    val look = remember(style) {
        when (style) {
            // ① HyperOS 底栏：贴底、整宽、直角。原生底栏本身是较实的磨砂条，
            //    这里保持较高底面 + 中等模糊；直角形状上折射只出现在两端，实现收益低故关闭
            Prefs.BAR_HYPEROS -> BottomBarLook(
                shape = RoundedCornerShape(0.dp),
                sideMargin = 0.dp,
                bottomLift = 0.dp,
                floating = false,
                blur = 30.dp,
                lensHeight = 0.dp,
                lensAmount = 0.dp,
                deepLens = false,
                scrim = 0.66f,
            )
            // ② HyperOS 悬浮底栏：胶囊 + 离底悬浮，模糊与折射都适中
            Prefs.BAR_FLOATING -> BottomBarLook(
                shape = RoundedCornerShape(50),
                sideMargin = 16.dp,
                bottomLift = 12.dp,
                floating = true,
                blur = 22.dp,
                lensHeight = 24.dp,
                lensAmount = 34.dp,
                deepLens = true,
                scrim = 0.46f,
            )
            // ③ 液态玻璃底栏：官方配方 —— 胶囊 + 小模糊(8dp) + 强折射(24dp) + 低底面(0.34)
            else -> BottomBarLook(
                shape = RoundedCornerShape(50),
                sideMargin = 14.dp,
                bottomLift = 14.dp,
                floating = true,
                blur = 8.dp,
                lensHeight = 24.dp,
                lensAmount = 24.dp,
                deepLens = true,
                scrim = 0.34f,
            )
        }
    }

    val navInsets = WindowInsets.navigationBars
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = look.sideMargin, end = look.sideMargin)
            .then(
                // 悬浮样式：玻璃整体抬到系统导航条之上，四周露出后面的内容
                if (look.floating) {
                    Modifier
                        .padding(bottom = look.bottomLift)
                        .windowInsetsPadding(navInsets)
                } else {
                    Modifier
                },
            )
            // 实测总高度（含系统导航条内距）交给内容层留底部安全区
            .onSizeChanged { onHeightChange(it.height) }
            .graphicsLayer {
                shape = look.shape
                clip = true
                // 悬浮样式用投影把玻璃「托」起来；贴底样式必须清掉阴影，
                // 否则玻璃边缘会渗出一圈灰边
                shadowElevation = if (look.floating) 18f else 0f
                ambientShadowColor = Color.Black.copy(alpha = 0.30f)
                spotShadowColor = Color.Black.copy(alpha = 0.30f)
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { look.shape },
                effects = {
                    // vibrancy：先提饱和，避免模糊后整块发灰
                    vibrancy()
                    blur((look.blur * glassBlur).toPx())
                    // 折射：液体玻璃的核心特征。厚度 / 位移为 0 时库内部会自动跳过
                    lens(
                        look.lensHeight.toPx(),
                        look.lensAmount.toPx(),
                        depthEffect = look.deepLens,
                        chromaticAberration = look.deepLens,
                    )
                },
                // 这里绝对不能常开 Highlight.Default：
                // 它的 shader 对矩形内部任意点都算出 |dot(grad, normal)| = 0.707 的常量，
                // 再以 BlendMode.Plus 叠加 → 整条底栏会被刷成一块纯白长矩形。
                // 官方示例里高光只在按压 / 拖动时随进度淡入，静止时为 0。
                onDrawSurface = { drawRect(tint.copy(alpha = look.scrim)) },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    // 贴底样式：系统导航条内距由内容自己吃，玻璃一直铺到屏幕最底
                    if (look.floating) Modifier else Modifier.windowInsetsPadding(navInsets),
                )
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomBarTab(
                selected = selectedIndex == 0,
                onClick = { onSelect(0) },
                icon = MiuixIcons.Home,
                label = "主页",
                modifier = Modifier.weight(1f),
            )
            BottomBarTab(
                selected = selectedIndex == 1,
                onClick = { onSelect(1) },
                icon = MiuixIcons.Settings,
                label = "设置",
                modifier = Modifier.weight(1f),
            )
            BottomBarTab(
                selected = selectedIndex == 2,
                onClick = { onSelect(2) },
                icon = MiuixIcons.Info,
                label = "关于",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 底栏单个 Tab：图标 + 文字，选中态用品牌玫红，未选中降到次级文字色 */
@Composable
private fun BottomBarTab(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) {
        CamelliaTheme.colors.brand
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = tint)
    }
}

// ───────────────────────────── 功能搜索 ─────────────────────────────

/** 一条可搜索的功能项：标题 + 说明 + 关键词 + 目标页面（手机管家规则额外带大类） */
private data class SearchHit(
    val title: String,
    val desc: String,
    val kw: String,
    val screen: Screen,
    val group: String,
)

/**
 * 搜索索引：固定入口 + 手机管家逐条规则。
 *
 * 手机管家规则来自 Unlock.ruleRows()（id / group / label），
 * 这样新增规则时搜索页自动跟着更新，不需要手工维护第二份清单。
 */
private fun searchIndex(): List<SearchHit> {
    val out = ArrayList<SearchHit>()
    out += SearchHit(
        "小米互传 · 打开落点",
        "通知「打开」交给哪个应用 / 目录",
        "互传 落点 目录 文件夹 管理器 打开方式 文件",
        Screen.MiShare, "",
    )
    out += SearchHit(
        "小米画报 · 目录同步",
        "把目录里的图片同步进「自选图片」",
        "画报 壁纸 目录 同步 上限 加载顺序 洗牌 自选图片",
        Screen.Gallery, "",
    )
    out += SearchHit(
        "手机管家 · 功能大类",
        "按大类查看 / 切换全部解锁与拦截",
        "管家 解锁 拦截 省电 电池 相机 隐私 侧边栏 游戏 视频 其他",
        Screen.Security, "",
    )
    out += SearchHit(
        "快捷方式屏蔽",
        "全局与应用专属的屏蔽关键词管理",
        "快捷方式 屏蔽 关键词 长按 菜单 桌面",
        Screen.ShortcutBlock, "",
    )
    out += SearchHit(
        "指令拦截明细",
        "云端下发 10 条指令的逐条拦截开关",
        "下发 指令 拦截 推送 push 卸载 停用 组件 无障碍 云控 guardprovider",
        Screen.GpPushCmds, "",
    )
    out += SearchHit(
        "小窗增强",
        "解除小窗限制 / 黑名单 / 通知下拉小窗",
        "小窗 自由窗口 黑名单 通知 下拉 悬浮",
        Screen.Freeform, "",
    )
    out += SearchHit(
        "camd 面板",
        "环境判定 / 行为开关 / 监控包 / 快照回滚",
        "camd 自救 崩溃 救砖 黑屏 重启 救援 rescue bootloop 开机 快照 回滚 监控",
        Screen.CamdPanel, "",
    )
    out += SearchHit(
        "设置 · 主题与底栏",
        "主题模式、底栏样式、桌面图标、重启应用",
        "主题 深色 浅色 底栏 玻璃 图标 重启 引导",
        Screen.Settings, "",
    )
    out += SearchHit(
        "关于与鸣谢",
        "版本号、作者、开源引用",
        "关于 鸣谢 版本 开源 作者 引用",
        Screen.About, "",
    )
    for (r in Unlock.ruleRows()) {
        if (r.size < 3) continue
        val label = r[2].replace("$", "")
        out += SearchHit(
            label,
            "手机管家 · " + Unlock.groupLabel(r[1]),
            r[0] + " " + r[1] + " " + label,
            Screen.Security, r[1],
        )
    }
    return out
}

/** 功能搜索页：边输入边过滤，点结果直接跳到页面（手机管家会落到具体大类） */
// ---------------- 右上角：重启应用 ----------------

/**
 * 「重启应用」对话框，对应 HyperCeiler 标题栏菜单里的 quick_restart。
 *
 * 只做一件普通应用也能做的事：勾选要重启的目标，逐个 force-stop（root 优先，
 * 无 root 退化为 killBackgroundProcesses），让新钩子 / 新配置在下次启动时生效。
 */
@Composable
private fun RestartAppDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<RestartTarget>) -> Unit,
) {
    val targets = remember { restartTargets() }
    // 每次打开都清空勾选（对应 HyperCeiler 的 deselectAll），避免上次的勾选残留导致误杀
    val selected = remember(show) { mutableStateListOf<String>() }

    OverlayDialog(
        show = show,
        title = "重启应用",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Note("勾选要重启的应用：进程会被结束，系统随即把它重新拉起，新的钩子与配置立即生效。")
            // 列表区限高可滚动：OverlayDialog 走 DialogContentLayout，给对话框限了约 2/3 屏高的
            // 裁剪区，但内容本身不能滚动。目标清单较长时（6~8 个应用）会把下方「取消 / 立即重启」
            // 按钮顶出裁剪区，既看不见也点不到。这里让列表占用剩余高度并可滚动，按钮固定在底部。
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionCard {
                    targets.forEachIndexed { index, target ->
                        if (index > 0) HorizontalDivider()
                        RestartTargetRow(
                            target = target,
                            checked = selected.contains(target.key),
                            onToggle = {
                                if (!selected.remove(target.key)) selected.add(target.key)
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val picked = targets.filter { selected.contains(it.key) }
                        if (picked.isEmpty()) onDismiss() else onConfirm(picked)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (selected.isEmpty()) "立即重启" else "重启 " + selected.size + " 项")
                }
            }
        }
    }
}

/** 重启面板的一行：应用名 + 包名 + 作用说明 */
private data class RestartTarget(
    val label: String,
    val pkg: String,
    val desc: String,
    /** true 表示这一项是「重新载入本设置页」，不需要 kill 任何进程 */
    val self: Boolean = false,
) {
    val key: String get() = label
}

/**
 * 重启面板的候选应用。前三项是本模块真正要 hook 的目标，与设置页里的快捷按钮一致；
 * 系统界面 / 桌面 / 系统设置为 HyperCeiler 同款常用项，改动影响到它们时才需要点。
 */
private fun restartTargets(): List<RestartTarget> = listOf(
    RestartTarget("小米互传", Prefs.TARGET_PKG, "接收文件落点、打开方式"),
    RestartTarget("小米画报", Prefs.FG_PKG, "图源目录、顺序与张数上限"),
    RestartTarget("手机管家", Prefs.SECURITY_PKG, "侧边栏、拦截确认等全部管家功能"),
    RestartTarget("系统安全组件", Unlock.PKG_GP, "应用清单上传 / 设备状态上报 / 云端指令拦截"),
    RestartTarget("系统界面", "com.android.systemui", "状态栏与控制中心相关 hook"),
    RestartTarget("桌面", "com.miui.home", "桌面快捷方式屏蔽等改动"),
    RestartTarget("系统设置", "com.android.settings", "设置类 hook"),
    RestartTarget("Camellia 设置页", "", "只重新载入本界面，不结束任何应用", self = true),
)

@Composable
private fun RestartTargetRow(target: RestartTarget, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(target.label, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                if (target.pkg.isEmpty()) target.desc else target.pkg + " · " + target.desc,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.width(12.dp))
        // 勾选圈：未选中为空心描边，选中填充主色 + 对勾
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) MiuixTheme.colorScheme.primary else Color.Transparent)
                .border(
                    width = if (checked) 0.dp else 1.5.dp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", fontSize = 13.sp, color = MiuixTheme.colorScheme.surface)
            }
        }
    }
}

// ============================================================================
// 问题包备份：磁盘目录才是唯一真相
// ============================================================================
// 这里以前只信 Settings.Global 里的索引 `sysui_bak_list`，但有两个致命前提都不成立：
//   1. 本应用只申请了 WRITE_SETTINGS（写 Settings.System），没有
//      WRITE_SECURE_SETTINGS，写 Settings.Global 会抛 SecurityException，而 Prefs.putGlobal
//      为了「设置页只展示、不影响判定」把它吞了——于是删列表项这一步从来就没成功过。
//   2. 索引里的 path 是早期命名规则 `<BAK_DIR>/<pkg>_<md5前8>.apk`，后来真实备份改存
//      `<BAK_DIR>/apk/<固定名>.apk`，老记录全部指向不存在的文件。
// 两条叠加的效果就是：点「删除」→ rm 空跑返回 0 → 界面刷新后列表和文件都还在，
// 用户看到的是「删除完全不起作用」。
//
// 所以改成：扫描目录拿真实文件列表，删除按真实路径执行并回读验证，
// 索引只在「清理失效记录」时用 su 重写一次（shell uid 才有写 Global 的权限）。
// ============================================================================

// ============================================================================
// 定时快照备份（主题 / 字体 / 桌面布局 / 壁纸）
// ============================================================================
// 为什么需要这个页面：
//
// MIUI 的「系统急救」（RescuePartyPlusHelper，/system/framework/services.jar）
// 判定「开机反复崩溃」后会按白名单逐条删用户数据来救命，里面除了我们自己的模块，
// 还有主题、字体、桌面布局这些「删掉也能开机」的内容。本机已确认该机制被全面
// 禁用（persist.sys.rescuepartyplus.disable 等），但刷机回滚、第三方精简、误清
// 数据同样会丢这几类内容，快照仍是必要保底。
//
// 快照存在 /data/adb/camellia/dsnap（root 私有域）：
//   · 不落 /sdcard —— 那里会被「清除数据」、急救自己的清理脚本、各种清理软件扫到；
//   · 不落 App 私有目录 —— 卸载重装就没了。
// 代价是读写都要 su，所以未授权 root 时本页只展示说明，不给操作入口。
//
// 定时语义（见 SnapScheduler）：
// 不用 AlarmManager，也不依赖 camd 常驻。模块进程在「开机 / App 启动 / 打开本页」
// 时比对上次成功时间与周期，到期就补一份。设备长期不重启又从不打开 App 时确实
// 不会备份，下面的文案如实写了这一点——这比装一堆唤醒锁更老实，也更省电。
// ============================================================================

/** 设置分组里的小节标签：SectionCard 内部用，左边距与 SmallTitle 对齐 */
@Composable
private fun PrefLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 2.dp),
    )
}

/**
 * 定时快照页。
 *
 * 所有磁盘 / su 动作都在 IO 线程跑，UI 只留 busy 状态；
 * 页面不自动轮询——列表只在进入、创建完、删除完、恢复完、手动刷新时重读。
 */
@Composable
private fun DsnapPage(version: String, onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }
    var snaps by remember { mutableStateOf<List<SnapshotMgr.Snap>>(emptyList()) }
    /** 快照目录真实占盘（KB）。0 = 未知/没 root，界面就不显示这一行 */
    var usedKb by remember { mutableStateOf(0L) }

    /** null = 还没探测完；首帧不要把「未授权」甩到用户脸上 */
    var rootOk by remember { mutableStateOf<Boolean?>(value = null) }
    var cfg by remember { mutableStateOf(SnapshotMgr.AutoCfg()) }
    var pendingRestore by remember { mutableStateOf<SnapshotMgr.Snap?>(value = null) }
    var pendingDelete by remember { mutableStateOf<SnapshotMgr.Snap?>(value = null) }

    // 默认勾选按本机实测体积定：主题 39MB / 桌面布局 3MB / 壁纸 8MB（不含 97MB 视频）
    // 真实来源是 cfg.kinds（定时备份读的就是它），reload 完成后由下面的
    // LaunchedEffect(cfg.kinds) 对齐；首帧只有默认值，用户感知不到差别。
    val picked = remember {
        mutableStateMapOf<String, Boolean>().apply {
            SnapshotMgr.Kind.entries.forEach { put(it.key, it.onByDefault) }
        }
    }

    // 统一的「跑后台 + 刷新」入口。
    // block 跑在主线程（rememberCoroutineScope 默认 Main）：Compose 状态写入与
    // onToast 都必须留在主线程——onToast 最终是 Toast.show()，在无 Looper 的 IO
    // 线程调用会直接抛 "Can't create handler inside thread..."。
    // 磁盘 / su 动作由 block 自己用 withContext(Dispatchers.IO) 包住。
    fun run(text: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        busyText = text
        scope.launch {
            try {
                block()
            } finally {
                busy = false
                busyText = ""
            }
        }
    }

    fun reload() {
        run("") {
            val usable = withContext(Dispatchers.IO) {
                try { CamdRescue.camdAlive() } catch (t: Throwable) { false }
            }
            if (!usable) {
                // 瞬断/后台备份中：保留上次列表，不把状态翻成"未部署"（避免横跳/清空）
                if (snaps.isEmpty()) rootOk = false
                return@run
            }
            rootOk = true
            snaps = withContext(Dispatchers.IO) {
                try { SnapshotMgr.list() } catch (t: Throwable) { emptyList() }
            }
            cfg = withContext(Dispatchers.IO) {
                try { SnapshotMgr.autoCfg(ctx) } catch (t: Throwable) { SnapshotMgr.AutoCfg() }
            }
            usedKb = snaps.sumOf { it.size } / 1024L
        }
    }

    // 定时相关偏好的写盘：只改内存 + 异步落盘，界面立刻响应
    fun setCfg(mut: (SnapshotMgr.AutoCfg) -> SnapshotMgr.AutoCfg) {
        val next = mut(cfg)
        cfg = next
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    SnapshotMgr.saveAutoCfg(ctx, next)
                } catch (t: Throwable) {
                }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    // cfg 异步加载完成后，把界面勾选对齐到磁盘上真实生效的内容档。
    // 先判等再写：勾选变化会同步写 cfg.kinds，两边一致时这里什么都不做，
    // 不会把用户刚点的那一下刷回去。
    LaunchedEffect(cfg.kinds) {
        val stored = cfg.kinds.toSet()
        val cur = SnapshotMgr.Kind.entries
            .filter { picked[it.key] == true }
            .map { it.key }
            .toSet()
        if (cur != stored) {
            SnapshotMgr.Kind.entries.forEach { picked[it.key] = it.key in stored }
        }
    }

    // ---------------- 状态 ----------------
    SmallTitle(text = "快照")
    SectionCard {
        Column(modifier = Modifier.padding(16.dp)) {
            KVRow("存储位置", SnapshotMgr.ROOT, small = true)
            KVRow(
                "已有快照",
                when (rootOk) {
                    null -> "读取中…"
                    false -> "需要 root"
                    else -> snaps.size.toString() + " / " + cfg.keep + " 份"
                },
            )
            // 各份的「大小」之和会把硬链接共享的归档重复计入，
            // 这里给的是磁盘真实占用（du 只计一次），两者会明显不同。
            if (usedKb > 0L) {
                KVRow(
                    "占用空间",
                    SnapshotMgr.fmtSize(usedKb * 1024L) + " · 相同内容只存一份",
                    small = true,
                )
            }
            if (cfg.lastTs > 0L) {
                KVRow(
                    "上次执行",
                    SnapshotMgr.fmtTime(cfg.lastTs) + " · " + (if (cfg.lastOk) "成功" else "失败"),
                )
            }
            if (cfg.lastMsg.isNotEmpty()) {
                KVRow("结果", cfg.lastMsg, small = true)
            }
            if (cfg.enabled) {
                // 到期未补跑时要说清楚，不能只显示一个“看起来还在未来”的时间点，
                // 否则定时任务失灵时界面上完全看不出来。
                val nowMs = System.currentTimeMillis()
                if (SnapshotMgr.overdue(cfg, nowMs)) {
                    KVRow("下次检查", "已到期 · 下次开机或打开本应用时补跑")
                } else {
                    KVRow("下次检查", SnapshotMgr.fmtTime(SnapshotMgr.nextRunAt(cfg, nowMs)))
                }
            }
        }
    }
    Note(
        hint("最多保留 " + cfg.keep + " 份。要腾地方时按「定时 → 恢复前 → 手动」" +
            "的顺序淘汰最旧的一份，所以手动备份最不容易被顶掉。"),
    )

    // ---------------- 定时备份 ----------------
    SmallTitle(text = "定时备份")
    SectionCard {
        SwitchPreference(
            title = "自动备份",
            summary = "到期后，在下次开机或打开本应用时补一份；不常驻、无唤醒锁",
            checked = cfg.enabled,
            onCheckedChange = { on ->
                if (!busy) {
                    setCfg { it.copy(enabled = on) }
                    onToast(if (on) "已开启自动备份" else "已关闭自动备份")
                }
            },
        )
        if (cfg.enabled) {
            HorizontalDivider()
            OverlayDropdownPreference(
                title = "备份周期",
                summary = "多久自动备一份",
                items = listOf("每天", "每 3 天", "每周"),
                selectedIndex = when (cfg.days) { 1 -> 0; 3 -> 1; 7 -> 2; else -> 1 },
                onSelectedIndexChange = { i -> if (!busy) setCfg { it.copy(days = when (i) { 0 -> 1; 1 -> 3; else -> 7 }) } },
            )
            HorizontalDivider()
            OverlayDropdownPreference(
                title = "备份时刻",
                summary = "到期当天的补备份时刻",
                items = listOf("凌晨 3:00", "中午 12:00"),
                selectedIndex = if (cfg.hour == 3) 0 else 1,
                onSelectedIndexChange = { i -> if (!busy) setCfg { it.copy(hour = if (i == 0) 3 else 12) } },
            )
            HorizontalDivider()
            OverlayDropdownPreference(
                title = "最多保留份数",
                summary = "超出时自动顶掉最旧的",
                items = listOf("3 份", "5 份", "8 份", "10 份"),
                selectedIndex = listOf(3, 5, 8, 10).indexOf(cfg.keep).let { if (it < 0) 1 else it },
                onSelectedIndexChange = { i -> if (!busy) setCfg { it.copy(keep = listOf(3, 5, 8, 10)[i.coerceIn(0, 3)]) } },
            )
        }
    }
    Note(
        "「到期」以「上次成功时间 + 周期」算，不是固定时刻：关机久了开机就补，打开 App 时也会补。" +
            "如果长期不重启又从不打开本应用，这份定时备份不会执行。",
    )

    // ---------------- 备份内容 + 手动备份 ----------------
    SmallTitle(text = "备份内容")
    SectionCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("桌面布局（始终备份：图标 / 文件夹 / 抽屉 / 网格）", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            SwitchPreference(
                title = "主题与字体",
                summary = "备份 /data/system/theme（含字体），约 16MB",
                checked = cfg.kinds.contains("theme"),
                onCheckedChange = { on ->
                    if (!busy) setCfg { c0 ->
                        val s0 = c0.kinds.toMutableSet(); s0.add("launcher")
                        if (on) s0.add("theme") else s0.remove("theme")
                        c0.copy(kinds = s0)
                    }
                },
            )
            SwitchPreference(
                title = "静态壁纸",
                summary = "壁纸状态与静态素材，约 8MB",
                checked = cfg.kinds.contains("wallpaper"),
                onCheckedChange = { on ->
                    if (!busy) setCfg { c0 ->
                        val s0 = c0.kinds.toMutableSet(); s0.add("launcher")
                        if (on) s0.add("wallpaper") else s0.remove("wallpaper")
                        c0.copy(kinds = s0)
                    }
                },
            )
            SwitchPreference(
                title = "动态壁纸视频（锁屏/桌面）",
                summary = "约 140MB，较大，默认关",
                checked = cfg.kinds.contains("wp_video"),
                onCheckedChange = { on ->
                    if (!busy) setCfg { c0 ->
                        val s0 = c0.kinds.toMutableSet(); s0.add("launcher")
                        if (on) s0.add("wp_video") else s0.remove("wp_video")
                        c0.copy(kinds = s0)
                    }
                },
            )
        }
        HorizontalDivider()
        Column(modifier = Modifier.padding(16.dp)) {
            Button(
                onClick = {
                    if (!busy) {
                        run("正在备份…") {
                            val (ok, msg) = withContext(Dispatchers.IO) {
                                SnapshotMgr.create(setOf(SnapshotMgr.Kind.LAUNCHER), "手动备份", SnapshotMgr.Src.MANUAL, version)
                            }
                            withContext(Dispatchers.IO) { try { Thread.sleep(1500) } catch (t: Throwable) {} }
                            snaps = withContext(Dispatchers.IO) {
                                try { SnapshotMgr.list() } catch (t: Throwable) { emptyList() }
                            }
                            onToast(if (ok) "已提交备份（桌面布局，约 73KB）" else "备份失败：" + msg)
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("立即备份") }
        }
    }
    Note(
        hint("壁纸素材按 home/lock 的 XML 引用自动抓取：勾「壁纸动态视频」会连带勾上「壁纸状态与静态素材」，" +
            "避免恢复时 XML 指向不存在的文件而黑屏。AOD 景深壁纸与动态视频内容重复，默认不勾。"),
    )

    // ---------------- 快照列表 ----------------
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "快照列表",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(text = if (busy) "处理中" else "刷新", onClick = { reload() })
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(text = "导出备份", onClick = {
            if (!busy) { CamdRescue.dsnapExportAll(); onToast("已导出到 /storage/emulated/0/Download/Camellia") }
        })
        TextButton(text = "打开备份目录", onClick = {
            val path = "/data/adb/camellia/dsnap"
            val uri = android.net.Uri.fromFile(java.io.File(path))
            var launched = false
            val old = android.os.StrictMode.getVmPolicy()
            try { android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build()) } catch (t: Throwable) { }
            try {
                for (pkg in listOf("bin.mt.plus", "bin.mt.plus.canary")) {
                    try {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setData(uri)
                            setClassName(pkg, "bin.mt.plus.OpenFileActivity")
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }); launched = true; break
                    } catch (t: Throwable) { }
                }
            } finally { try { android.os.StrictMode.setVmPolicy(old) } catch (t: Throwable) { } }
            if (!launched) onToast("备份目录：/data/adb/camellia/dsnap")
        })
    }
    SectionCard {
        if (snaps.isEmpty()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    when (rootOk) {
                        false -> "未授权 root，无法读取快照"
                        null -> "读取中…"
                        else -> "还没有快照。点上面的「立即备份」先存一份。"
                    },
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            snaps.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                DsnapRow(
                    snap = s,
                    enabled = !busy,
                    onRestore = { pendingRestore = s },
                    onDelete = { pendingDelete = s },
                )
            }
        }
    }
    Note(
        hint("恢复会校验快照 → 就地覆盖回目标内容 → 自动重启桌面；" +
            "校验不过直接中止，不动数据。主题与桌面布局恢复后需要重启对应界面才完全生效。"),
    )

    // ---------------- 恢复确认 ----------------
    val pr = pendingRestore
    if (pr != null) {
        OverlayDialog(
            show = true,
            title = "恢复快照",
            onDismissRequest = { if (!busy) pendingRestore = null },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            ) {
                Note(
                    "快照：" + SnapshotMgr.fmtTime(pr.at) + "（" + pr.src.label + "）\n" +
                        "内容：" + pr.kinds.joinToString("、") { it.label } + "\n\n" +
                        "将执行：校验 → 就地覆盖回该快照内容 → 自动重启桌面。",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { if (!busy) pendingRestore = null },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val target = pr
                            pendingRestore = null
                            run("正在恢复…") {
                                val kinds = target.kinds.toSet()
                                val (ok, msg) = withContext(Dispatchers.IO) {
                                    SnapshotMgr.restore(target.id, kinds)
                                }
                                withContext(Dispatchers.IO) { try { Thread.sleep(2500) } catch (t: Throwable) {} }
                                snaps = withContext(Dispatchers.IO) {
                                    try {
                                        SnapshotMgr.list()
                                    } catch (t: Throwable) {
                                        emptyList()
                                    }
                                }
                                onToast(if (ok) "已恢复，桌面自动重启中…" else msg)
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(2f),
                    ) { Text("确认恢复") }
                }
            }
        }
    }

    // ---------------- 删除确认 ----------------
    val pd = pendingDelete
    if (pd != null) {
        OverlayDialog(
            show = true,
            title = "删除快照",
            onDismissRequest = { if (!busy) pendingDelete = null },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            ) {
                Note(
                    SnapshotMgr.fmtTime(pd.at) + " · " + pd.sizeText + "\n" +
                        "删除后不可恢复，设备上现有的主题 / 桌面布局不受影响。",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { if (!busy) pendingDelete = null },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val target = pd
                            pendingDelete = null
                            run("正在删除…") {
                                val ok = withContext(Dispatchers.IO) {
                                    try { SnapshotMgr.delete(target.id) } catch (t: Throwable) { false }
                                }
                                withContext(Dispatchers.IO) { try { Thread.sleep(600) } catch (t: Throwable) {} }
                                snaps = withContext(Dispatchers.IO) {
                                    try { SnapshotMgr.list() } catch (t: Throwable) { emptyList() }
                                }
                                onToast(if (ok) "已删除快照" else "删除失败（camd 未运行？）")
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(2f),
                    ) { Text("确认删除") }
                }
            }
        }
    }

    // ---------------- 处理中 ----------------
    if (busy && busyText.isNotEmpty()) {
        Note(busyText + "打包 50 MB 左右通常需要 1～2 秒，请留在本页。")
    }
}

/** 快照列表里的一行：时间 + 来源 + 内容 + 大小，右侧恢复 / 删除 */
@Composable
private fun DsnapRow(
    snap: SnapshotMgr.Snap,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    BasicComponent(
        title = SnapshotMgr.fmtTime(snap.at),
        summary = snap.src.label + "　" + snap.kinds.joinToString(" · ") { it.label } + "　" + snap.sizeText +
            (if (snap.fails.isEmpty()) "" else "　缺 " + snap.fails.joinToString("/")),
        endActions = {
            TextButton(text = "恢复", onClick = { if (enabled) onRestore() })
            TextButton(text = "删除", onClick = { if (enabled) onDelete() })
        },
        onClick = { if (enabled) onRestore() },
    )
}
