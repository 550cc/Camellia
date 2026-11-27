package com.hyper.Camellia

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Camellia 的品牌配色与语义状态色。
 *
 * 为什么要自己做一层：
 *   MIUIX 默认方案是蓝色系（primary = #3482FF），在浅色下它与「链接蓝」「信息蓝」没有区分度，
 *   且默认没有语义状态色，状态只能靠文字表达。这里做两件事：
 *     1) 把品牌强调色换成「山茶花玫红」，用在开关 / 单选 / 主按钮 / 选中态上，
 *        与 MIUIX 默认蓝、错误红（#E94634）在色相上都拉开，不会误读；
 *     2) 补一组 ok / warn / bad / idle 语义色（前景 + 容器底色成对给出），
 *        让「已适配 / 待运行 / 未找到中枢」这类结论可以用色点 + 底色直接表达，
 *        不必再堆一大段说明文字。
 *
 * 浅色与深色各自一套：深色下用低饱和容器底 + 高明度前景，避免在纯黑背景上刺眼。
 */

/** 一对前景/底色。前景保证在该底色上可读，底色在同一主题下互相区分。 */
data class ToneColors(val fg: Color, val bg: Color)

/** 语义状态。Brand 用品牌色，其余按「好 -> 坏 -> 未知」排列。 */
enum class Tone { Brand, Ok, Warn, Bad, Idle, Info }

data class CamelliaColors(
    val brand: Color,
    val brandSoft: Color,
    val onBrandSoft: Color,
    val ok: ToneColors,
    val warn: ToneColors,
    val bad: ToneColors,
    val idle: ToneColors,
    val info: ToneColors,
) {
    fun of(tone: Tone): ToneColors = when (tone) {
        Tone.Brand -> ToneColors(onBrandSoft, brandSoft)
        Tone.Ok -> ok
        Tone.Warn -> warn
        Tone.Bad -> bad
        Tone.Idle -> idle
        Tone.Info -> info
    }
}

// 山茶花玫红：#C2185B 色相约 336°，而 MIUIX 错误色 #E94634 色相约 8°，
// 相隔近 30°，小尺寸下也能区分「品牌强调」与「出错」。
private val LightCamellia = CamelliaColors(
    brand = Color(0xFFC2185B),
    brandSoft = Color(0xFFFDECF3),
    onBrandSoft = Color(0xFF9B1247),
    ok = ToneColors(Color(0xFF0E7A50), Color(0xFFE3F5EC)),
    warn = ToneColors(Color(0xFF96650A), Color(0xFFFFF3DF)),
    bad = ToneColors(Color(0xFFC0392B), Color(0xFFFCEBE9)),
    idle = ToneColors(Color(0xFF5D6675), Color(0xFFF0F1F4)),
    info = ToneColors(Color(0xFF5B4BA8), Color(0xFFEFECFA)),
)

private val DarkCamellia = CamelliaColors(
    brand = Color(0xFFFF7AAE),
    brandSoft = Color(0xFF3A1426),
    onBrandSoft = Color(0xFFFFB0CC),
    ok = ToneColors(Color(0xFF4BD599), Color(0xFF10301F)),
    warn = ToneColors(Color(0xFFF3BE55), Color(0xFF33270B)),
    bad = ToneColors(Color(0xFFFF8478), Color(0xFF351312)),
    idle = ToneColors(Color(0xFF9AA2B2), Color(0xFF23262C)),
    info = ToneColors(Color(0xFFB7A9FF), Color(0xFF221D38)),
)

// Material 3 品牌色（紫调）：让“界面风格 Material3”对全站控件都可见（开关/按钮/选中态用的是 brand）
private val MaterialLightCamellia = CamelliaColors(
    brand = Color(0xFF6750A4),
    brandSoft = Color(0xFFEADDFF),
    onBrandSoft = Color(0xFF4F378B),
    ok = ToneColors(Color(0xFF0E7A50), Color(0xFFE3F5EC)),
    warn = ToneColors(Color(0xFF96650A), Color(0xFFFFF3DF)),
    bad = ToneColors(Color(0xFFC0392B), Color(0xFFFCEBE9)),
    idle = ToneColors(Color(0xFF5D6675), Color(0xFFF0F1F4)),
    info = ToneColors(Color(0xFF5B4BA8), Color(0xFFEFECFA)),
)
private val MaterialDarkCamellia = CamelliaColors(
    brand = Color(0xFFD0BCFF),
    brandSoft = Color(0xFF4F378B),
    onBrandSoft = Color(0xFFEADDFF),
    ok = ToneColors(Color(0xFF4BD599), Color(0xFF10301F)),
    warn = ToneColors(Color(0xFFF3BE55), Color(0xFF33270B)),
    bad = ToneColors(Color(0xFFFF8478), Color(0xFF351312)),
    idle = ToneColors(Color(0xFF9AA2B2), Color(0xFF23262C)),
    info = ToneColors(Color(0xFFB7A9FF), Color(0xFF221D38)),
)

private fun camelliaFromSeed(seed: Int, dark: Boolean): CamelliaColors {
    val h = Hct.fromInt(seed)
    val hue = h.hue
    val ch = Math.min(h.chroma, 48.0)
    fun c(t: Double) = Color(Hct.from(hue, ch, t).toInt())
    return CamelliaColors(
        brand = c(if (dark) 80.0 else 40.0),
        brandSoft = c(if (dark) 30.0 else 90.0),
        onBrandSoft = c(if (dark) 90.0 else 10.0),
        ok = if (dark) ToneColors(Color(0xFF4BD599), Color(0xFF10301F)) else ToneColors(Color(0xFF0E7A50), Color(0xFFE3F5EC)),
        warn = if (dark) ToneColors(Color(0xFFF3BE55), Color(0xFF33270B)) else ToneColors(Color(0xFF96650A), Color(0xFFFFF3DF)),
        bad = if (dark) ToneColors(Color(0xFFFF8478), Color(0xFF351312)) else ToneColors(Color(0xFFC0392B), Color(0xFFFCEBE9)),
        idle = if (dark) ToneColors(Color(0xFF9AA2B2), Color(0xFF23262C)) else ToneColors(Color(0xFF5D6675), Color(0xFFF0F1F4)),
        info = if (dark) ToneColors(Color(0xFFB7A9FF), Color(0xFF221D38)) else ToneColors(Color(0xFF5B4BA8), Color(0xFFEFECFA)),
    )
}

internal val LocalCamelliaColors = staticCompositionLocalOf { LightCamellia }

/** 当前实际使用的深浅色（已把「跟随系统 / 强制浅色 / 强制深色」解析成结果），供页面自行判断。 */
internal val LocalCamelliaDark = staticCompositionLocalOf { false }

object CamelliaTheme {
    val colors: CamelliaColors
        @Composable get() = LocalCamelliaColors.current
}

/**
 * 把品牌色接进 MIUIX 方案。
 *
 * 只覆盖与品牌相关的字段，其余（surface / dividerLine / 文字层级…）保持 MIUIX 默认，
 * 这样跟随 HyperOS 的观感不会被破坏。
 */
private fun miuixLight(accent: Color?): Colors = if (accent == null) lightColorScheme(
    primary = Color(0xFFC2185B),
    primaryVariant = Color(0xFFA01349),
    onPrimaryVariant = Color(0xFFFFD6E5),
    primaryContainer = Color(0xFFE0508A),
    onPrimaryContainer = Color(0xFFFFFFFF),
    disabledPrimary = Color(0xFFEFC3D4),
    disabledOnPrimary = Color(0xFFFDF2F7),
    disabledPrimaryButton = Color(0xFFEFC3D4),
    disabledPrimarySlider = Color(0xFFDFA9BF),
    tertiaryContainer = Color(0xFFFDECF3),
    onTertiaryContainer = Color(0xFFC2185B),
    tertiaryContainerVariant = Color(0xFFFDECF3),
) else lightColorScheme(
    primary = accent,
    primaryVariant = lerp(accent, Color.Black, 0.18f),
    onPrimaryVariant = lerp(accent, Color.White, 0.80f),
    primaryContainer = lerp(accent, Color.White, 0.28f),
    onPrimaryContainer = Color.White,
    disabledPrimary = lerp(accent, Color.White, 0.72f),
    disabledOnPrimary = lerp(accent, Color.White, 0.90f),
    disabledPrimaryButton = lerp(accent, Color.White, 0.72f),
    disabledPrimarySlider = lerp(accent, Color.White, 0.55f),
    tertiaryContainer = lerp(accent, Color.White, 0.90f),
    onTertiaryContainer = accent,
    tertiaryContainerVariant = lerp(accent, Color.White, 0.90f),
)

private fun miuixDark(accent: Color?): Colors = if (accent == null) darkColorScheme(
    primary = Color(0xFFFF7AAE),
    onPrimary = Color(0xFF4A0022),
    primaryVariant = Color(0xFFFF9CC4),
    onPrimaryVariant = Color(0xFF5C0B33),
    disabledPrimary = Color(0xFF5A2B41),
    disabledOnPrimary = Color(0xFF9C7C8B),
    disabledPrimaryButton = Color(0xFF5A2B41),
    disabledOnPrimaryButton = Color(0xFF9C7C8B),
    disabledPrimarySlider = Color(0xFF6E3B53),
    tertiaryContainer = Color(0xFF3A1426),
    onTertiaryContainer = Color(0xFFFF9CC4),
    tertiaryContainerVariant = Color(0xFF3A1426),
) else darkColorScheme(
    primary = lerp(accent, Color.White, 0.55f),
    onPrimary = lerp(accent, Color.Black, 0.55f),
    primaryVariant = lerp(accent, Color.White, 0.70f),
    onPrimaryVariant = lerp(accent, Color.Black, 0.40f),
    disabledPrimary = lerp(accent, Color.Black, 0.60f),
    disabledOnPrimary = lerp(accent, Color.Black, 0.30f),
    disabledPrimaryButton = lerp(accent, Color.Black, 0.60f),
    disabledOnPrimaryButton = lerp(accent, Color.Black, 0.30f),
    disabledPrimarySlider = lerp(accent, Color.Black, 0.50f),
    tertiaryContainer = lerp(accent, Color.Black, 0.72f),
    onTertiaryContainer = lerp(accent, Color.White, 0.55f),
    tertiaryContainerVariant = lerp(accent, Color.Black, 0.72f),
)

/**
 * 主题入口。
 *
 * [mode] 取 Prefs.THEME_SYSTEM / THEME_LIGHT / THEME_DARK：
 * 跟随系统时用 isSystemInDarkTheme()，另外两种强制单一配色。
 * 切换时整个设置页会立即重建配色（状态提升在 Activity 的 setContent 里）。
 */
private fun materialLight(accent: Color?): Colors {
    val p = accent ?: Color(0xFF6750A4)
    return lightColorScheme(
        primary = p,
        primaryVariant = lerp(p, Color.Black, 0.15f),
        onPrimaryVariant = lerp(p, Color.White, 0.85f),
        primaryContainer = lerp(p, Color.White, 0.55f),
        onPrimaryContainer = lerp(p, Color.Black, 0.55f),
        disabledPrimary = lerp(p, Color.White, 0.70f),
        disabledOnPrimary = lerp(p, Color.White, 0.90f),
        disabledPrimaryButton = lerp(p, Color.White, 0.70f),
        disabledPrimarySlider = lerp(p, Color.White, 0.50f),
        tertiaryContainer = lerp(p, Color.White, 0.85f),
        onTertiaryContainer = p,
        tertiaryContainerVariant = lerp(p, Color.White, 0.85f),
    )
}

private fun materialDark(accent: Color?): Colors {
    val p = accent ?: Color(0xFFD0BCFF)
    return darkColorScheme(
        primary = p,
        onPrimary = lerp(p, Color.Black, 0.60f),
        primaryVariant = lerp(p, Color.White, 0.70f),
        onPrimaryVariant = lerp(p, Color.Black, 0.40f),
        disabledPrimary = lerp(p, Color.Black, 0.60f),
        disabledOnPrimary = lerp(p, Color.Black, 0.30f),
        disabledPrimaryButton = lerp(p, Color.Black, 0.60f),
        disabledOnPrimaryButton = lerp(p, Color.Black, 0.30f),
        disabledPrimarySlider = lerp(p, Color.Black, 0.50f),
        tertiaryContainer = lerp(p, Color.Black, 0.72f),
        onTertiaryContainer = lerp(p, Color.White, 0.55f),
        tertiaryContainerVariant = lerp(p, Color.Black, 0.72f),
    )
}

private fun monetColors(seedArgb: Int, dark: Boolean, std: String): Colors {
    val seed = Hct.fromInt(seedArgb)
    val hue = seed.hue
    val chroma = when (std) {
        Prefs.STD_MONO -> 0.0
        Prefs.STD_VIBRANT -> 56.0
        Prefs.STD_FIDELITY -> Math.max(seed.chroma, 48.0)
        Prefs.STD_CONTENT -> seed.chroma
        else -> 36.0
    }
    val tp = if (dark) 80.0 else 40.0
    val cp = if (dark) 30.0 else 90.0
    val onCp = if (dark) 90.0 else 10.0
    val pv = if (dark) 70.0 else 30.0
    val onPv = if (dark) 20.0 else 90.0
    val dis = if (dark) 30.0 else 80.0
    fun c(h: Double, ch: Double, t: Double) = Color(Hct.from(h, ch, t).toInt())
    val disCh = Math.min(chroma, 16.0)
    return if (dark) darkColorScheme(
        primary = c(hue, chroma, tp),
        onPrimary = c(hue, chroma, 20.0),
        primaryVariant = c(hue, chroma, pv),
        onPrimaryVariant = c(hue, chroma, onPv),
        disabledPrimary = c(hue, disCh, dis),
        disabledOnPrimary = c(hue, disCh, 80.0),
        disabledPrimaryButton = c(hue, disCh, dis),
        disabledOnPrimaryButton = c(hue, disCh, 80.0),
        disabledPrimarySlider = c(hue, disCh, dis),
        tertiaryContainer = c(hue, chroma, cp),
        onTertiaryContainer = c(hue, chroma, onCp),
        tertiaryContainerVariant = c(hue, chroma, cp),
    ) else lightColorScheme(
        primary = c(hue, chroma, tp),
        primaryVariant = c(hue, chroma, pv),
        onPrimaryVariant = c(hue, chroma, onPv),
        primaryContainer = c(hue, chroma, cp),
        onPrimaryContainer = c(hue, chroma, onCp),
        disabledPrimary = c(hue, disCh, 90.0),
        disabledOnPrimary = c(hue, disCh, 40.0),
        disabledPrimaryButton = c(hue, disCh, 90.0),
        disabledPrimarySlider = c(hue, disCh, 80.0),
        tertiaryContainer = c(hue, chroma, cp),
        onTertiaryContainer = c(hue, chroma, onCp),
        tertiaryContainerVariant = c(hue, chroma, cp),
    )
}

internal val LocalGlassBlur = staticCompositionLocalOf { 1f }
internal val LocalShowHints = staticCompositionLocalOf { false }

/** 说明文字统一入口：全局开关关闭时一律返回空串，等于隐藏。 */
@Composable
internal fun hint(s: String?): String = if (LocalShowHints.current) (s ?: "") else ""
internal val LocalCamelliaPureBlack = staticCompositionLocalOf { false }

private fun wallpaperAccent(ctx: android.content.Context): Color? = try {
    if (android.os.Build.VERSION.SDK_INT < 27) null else {
        val wc = android.app.WallpaperManager.getInstance(ctx)
            .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
        val pc = wc?.primaryColor
        if (pc == null) null else Color(pc.toArgb())
    }
} catch (t: Throwable) { null }

@Composable
fun CamelliaTheme(
    mode: String = Prefs.THEME_SYSTEM,
    monet: Boolean = false,
    pureBlack: Boolean = false,
    blurLevel: Int = 2,
    uiScale: Int = 100,
    showHints: Boolean = false,
    style: String = Prefs.STYLE_MIUIX,
    colorStandard: String = Prefs.STD_TONAL,
    accentColor: Int = 0,
    uiStyle: String = Prefs.UI_MIUIX,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val dark = when (mode) {
        Prefs.THEME_LIGHT -> false
        Prefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val wpAccent = if (monet) remember(monet) { wallpaperAccent(ctx) } else null
    val dynamic = monet || accentColor != 0
    val keyColor = when { accentColor != 0 -> Color(accentColor); wpAccent != null -> wpAccent; else -> Color(0xFFC2185B) }
    val blurScale = if (blurLevel > 0) 1f else 0f
    val base = LocalDensity.current
    val scaled = if (uiScale == 100) base else Density(base.density * uiScale / 100f, base.fontScale)
    val cMode = when {
        dynamic && dark -> ColorSchemeMode.MonetDark
        dynamic -> ColorSchemeMode.MonetLight
        dark -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.Light
    }
    val specEnum = if (style == "2025") ThemeColorSpec.Spec2025 else ThemeColorSpec.Spec2021
    val paletteEnum = when (colorStandard) {
        "Neutral" -> ThemePaletteStyle.Neutral
        "Vibrant" -> ThemePaletteStyle.Vibrant
        "Expressive" -> ThemePaletteStyle.Expressive
        "Rainbow" -> ThemePaletteStyle.Rainbow
        "FruitSalad" -> ThemePaletteStyle.FruitSalad
        "Monochrome" -> ThemePaletteStyle.Monochrome
        "Fidelity" -> ThemePaletteStyle.Fidelity
        "Content" -> ThemePaletteStyle.Content
        else -> ThemePaletteStyle.TonalSpot
    }
    val materialUi = uiStyle == Prefs.UI_MATERIAL
    val baseLight = if (materialUi) materialLight(null) else miuixLight(null)
    val baseDark = if (materialUi) materialDark(null) else miuixDark(null)
    val controller = remember(cMode, keyColor, specEnum, paletteEnum, dark, materialUi) {
        ThemeController(
            colorSchemeMode = cMode,
            lightColors = baseLight,
            darkColors = baseDark,
            keyColor = keyColor,
            colorSpec = specEnum,
            paletteStyle = paletteEnum,
            isDark = dark,
        )
    }
    CompositionLocalProvider(LocalDensity provides scaled) {
        MiuixTheme(controller = controller) {
            CompositionLocalProvider(
                LocalCamelliaColors provides (if (dynamic) camelliaFromSeed(keyColor.toArgb(), dark) else if (materialUi) (if (dark) MaterialDarkCamellia else MaterialLightCamellia) else if (dark) DarkCamellia else LightCamellia),
                LocalCamelliaDark provides dark,
                LocalCamelliaPureBlack provides pureBlack,
                LocalGlassBlur provides blurScale,
                LocalShowHints provides showHints,
                content = content,
            )
        }
    }
}
