package com.hyper.Camellia

import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 「全局侧边栏背景」调色盘。
 *
 * <p>结构与 HyperCeiler 的 ColorPickerPreference 逐项对齐（反编译其 2.10.166 的
 * res/R9.xml + res/lc.xml 得到）：</p>
 * <pre>
 *   ┌ 圆形色块预览（棋盘打底，便于看透明度）          十六进制输入框（右对齐、自动大写）
 *   ├ [ 网格 ] [ 滑块 ] [ 莫奈 ]                      三页分页
 *   │   网格：HSV 色板栅格
 *   │   滑块：色相 / 饱和度 / 明度（轨道为对应分量的真实渐变）
 *   │   莫奈：系统动态色 + 常用预设色
 *   └ 透明度 + 百分比 ──────────────── Alpha 滑块（透明 → 当前色）
 * </pre>
 *
 * <p><b>状态模型（关键 1）</b>：色相 / 饱和度 / 明度 / 透明度是四个<b>互相独立</b>的
 * 状态，颜色由它们派生（{@link hsvToArgb}）。滑块回调只写入对应的那一个分量，
 * <b>绝不</b>把 Argb 反算回 HSV —— 否则会出现经典「拖不动」：例如当前色是纯黑
 * （v=0）时，无论怎么改色相 / 饱和度，算出的颜色都还是黑，反算回来必然是 h=0 /
 * s=0，滑块于是被弹回原位。只有「外部输入颜色」（初始值、网格取色、莫奈预设、
 * HEX 输入）时才允许反算一次。</p>
 *
 * <p><b>轨道配色（关键 2）</b>：不用 Miuix 的 Slider —— 它的轨道与拇指固定使用主题
 * 强调色，看不出正在调的是哪个分量。这里自绘 {@link GradientSlider}：轨道用该分量的
 * 真实渐变（色相=彩虹、饱和度=灰→彩、明度=黑→亮、透明度=透明→当前色），拇指为白色
 * 圆点，与 iOS / MIUI 原生取色器一致。</p>
 *
 * @param show 是否显示
 * @param initial 打开时的初始颜色（ARGB）
 * @param onDismiss 取消 / 点击外部关闭
 * @param onConfirm 点击「确定」，带回选定颜色（ARGB）
 */
@Composable
internal fun SidebarColorDialog(
    show: Boolean,
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    // ── 四个独立分量状态：颜色从它们派生，而不是反向同步 ──
    var hue by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(0f) }
    var value by remember { mutableFloatStateOf(0f) }
    var alpha by remember { mutableFloatStateOf(1f) }
    var hexText by remember { mutableStateOf(hexOf(initial)) }
    // 默认停在「滑块」页：它同时给出色相 / 饱和度 / 明度，最接近「调色盘」的直觉
    var tab by remember { mutableIntStateOf(1) }

    /** 当前颜色：完全由四个分量派生，所以滑块一动颜色必然跟着动 */
    fun currentArgb(): Int = hsvToArgb(hue, saturation, value, alpha)

    /** 把 HEX 输入框同步为当前颜色的文本 */
    fun syncHex() {
        hexText = hexOf(currentArgb())
    }

    /**
     * 外部输入颜色时的唯一反算入口（初始值 / 网格 / 莫奈 / HEX）。
     *
     * @param syncHex 是否同时刷新 HEX 输入框；来自 HEX 输入框本身的调用应传 false，
     *                否则用户正在输入的文本会被规范化结果覆盖。
     */
    fun setFromColor(c: Int, syncHex: Boolean) {
        val hsv = FloatArray(3)
        AColor.colorToHSV(c, hsv)
        hue = hsv[0]
        saturation = hsv[1] / 100f
        value = hsv[2] / 100f
        alpha = AColor.alpha(c) / 255f
        if (syncHex) {
            hexText = hexOf(c)
        }
    }

    // 每次打开都用外部最新值重置（关闭期间用户可能在别处改过）
    LaunchedEffect(show) {
        if (show) {
            setFromColor(initial, syncHex = true)
        }
    }

    /**
     * 色相 / 饱和度滑块的写入：拖到纯黑（明度 0）时颜色不会变化，手感上像是失灵。
     * 这里在用户主动改色相 / 饱和度而明度为 0 时把明度抬到满，让他立刻看到颜色，
     * 与主流 HSV 取色器的行为一致。
     */
    fun setHue(h: Float) {
        hue = h
        if (value == 0f) value = 1f
        syncHex()
    }

    fun setSaturation(s: Float) {
        saturation = s
        if (value == 0f) value = 1f
        syncHex()
    }

    fun setValue(v: Float) {
        value = v
        syncHex()
    }

    fun setAlpha(a: Float) {
        alpha = a
        syncHex()
    }

    OverlayDialog(
        show = show,
        title = "全局侧边栏背景",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── ① 预览 + 十六进制输入（对应 HyperCeiler：左圆形色块 + 右 150dip 输入框）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .checkerboard(6.dp)
                        .background(Color(currentArgb())),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    TextField(
                        value = hexText,
                        onValueChange = { raw ->
                            val cleaned = raw.filter { it.isLetterOrDigit() }.take(8).uppercase()
                            hexText = cleaned
                            if (cleaned.length == 8) {
                                val parsed = cleaned.toLongOrNull(16)
                                if (parsed != null) {
                                    setFromColor((parsed and 0xFFFFFFFFL).toInt(), syncHex = false)
                                }
                            }
                        },
                        label = "Hex（#AARRGGBB）",
                        modifier = Modifier.width(150.dp),
                    )
                }
            }

            // ── ② 分页：网格 / 滑块 / 莫奈
            TabRow(
                tabs = listOf("网格", "滑块", "莫奈"),
                selectedTabIndex = tab,
                onTabSelected = { tab = it },
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                when (tab) {
                    0 -> ColorPalette(
                        color = Color(currentArgb()),
                        onColorChanged = { setFromColor(it.toArgb(), syncHex = true) },
                        modifier = Modifier.fillMaxWidth(),
                        showPreview = false,
                    )

                    1 -> Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GradientSlider(
                            label = "色相",
                            text = "${hue.roundToInt()}°",
                            value = hue,
                            range = 0f..360f,
                            trackColors = HUE_TRACK,
                            onValueChange = { setHue(it) },
                        )
                        GradientSlider(
                            label = "饱和度",
                            text = "${(saturation * 100f).roundToInt()} %",
                            value = saturation,
                            range = 0f..1f,
                            trackColors = saturationTrack(hue, value),
                            onValueChange = { setSaturation(it) },
                        )
                        GradientSlider(
                            label = "明度",
                            text = "${(value * 100f).roundToInt()} %",
                            value = value,
                            range = 0f..1f,
                            trackColors = valueTrack(hue, saturation),
                            onValueChange = { setValue(it) },
                        )
                    }

                    else -> MonetColors(
                        onPick = { setFromColor(it, syncHex = true) },
                    )
                }
            }

            // ── ③ 透明度（对应 HyperCeiler：左标题 + 右数值 + Alpha 滑块）
            GradientSlider(
                label = "透明度",
                text = "${(alpha * 100f).roundToInt()} %",
                value = alpha,
                range = 0f..1f,
                trackColors = alphaTrack(hue, saturation, value),
                onValueChange = { setAlpha(it) },
            )

            // ── ④ 底部按钮：取消 / 确定
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "恢复默认",
                    onClick = { setFromColor(DEFAULT_SIDEBAR_COLOR, syncHex = true) },
                )
                Spacer(Modifier.weight(1f))
                TextButton(text = "取消", onClick = onDismiss)
                Button(onClick = { onConfirm(currentArgb()) }) { Text("确定") }
            }
        }
    }
}

/**
 * 自绘取色滑杆：轨道是该分量的真实渐变，拇指为白色圆点。
 *
 * <p>之所以不用 Miuix 的 Slider：它的轨道与拇指固定用主题强调色，无法体现「正在调
 * 色相 / 饱和度 / 明度」。而 Miuix 的 Hsv*Slider 又不接受 Modifier、宽度与手势由库
 * 内部控制（实测在本页只有一条可拖）。</p>
 *
 * <p>坐标换算把拇指宽度算进去，因此两端能精确落到 range.start / endInclusive。</p>
 */
@Composable
private fun GradientSlider(
    label: String,
    text: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    trackColors: List<Color>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.weight(1f))
            Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        var trackW by remember { mutableFloatStateOf(0f) }
        var thumbW by remember { mutableFloatStateOf(0f) }
        val span = (range.endInclusive - range.start).takeIf { it != 0f } ?: 1f

        /** 触点 x（像素）换算成取值：可用行程 = 轨道宽 - 拇指宽，触点取拇指中心 */
        fun valueAt(x: Float): Float {
            val usable = (trackW - thumbW).coerceAtLeast(1f)
            val f = ((x - thumbW / 2f) / usable).coerceIn(0f, 1f)
            return range.start + f * span
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .onSizeChanged { trackW = it.width.toFloat() }
                .pointerInput(range) {
                    detectTapGestures { off -> onValueChange(valueAt(off.x)) }
                }
                .pointerInput(range) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onValueChange(valueAt(change.position.x))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // 轨道：该分量的真实渐变
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(trackColors)),
            )
            // 拇指：白色圆点 + 细描边（浅色轨道上也看得见）
            Box(
                modifier = Modifier
                    .onSizeChanged { thumbW = it.width.toFloat() }
                    .size(24.dp)
                    .graphicsLayer {
                        val usable = (trackW - thumbW).coerceAtLeast(0f)
                        val f = ((value - range.start) / span).coerceIn(0f, 1f)
                        translationX = usable * f
                    }
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color(0x40000000), CircleShape),
            )
        }
    }
}

/** 色相轨道：彩虹（13 段足够平滑） */
private val HUE_TRACK: List<Color> = List(13) { i ->
    Color(AColor.HSVToColor(floatArrayOf(360f * i / 12f, 1f, 1f)))
}

/** 饱和度轨道：灰 → 当前色相下的满饱和 */
private fun saturationTrack(hue: Float, v: Float): List<Color> = listOf(
    Color(AColor.HSVToColor(floatArrayOf(hue, 0f, v))),
    Color(AColor.HSVToColor(floatArrayOf(hue, 1f, v))),
)

/** 明度轨道：黑 → 当前色相 / 饱和度下的满明度 */
private fun valueTrack(hue: Float, s: Float): List<Color> = listOf(
    Color(AColor.HSVToColor(floatArrayOf(hue, s, 0f))),
    Color(AColor.HSVToColor(floatArrayOf(hue, s, 1f))),
)

/** 透明度轨道：全透明 → 当前颜色（预览块的棋盘底会透出来） */
private fun alphaTrack(hue: Float, s: Float, v: Float): List<Color> = listOf(
    Color(AColor.HSVToColor(0, floatArrayOf(hue, s, v))),
    Color(AColor.HSVToColor(255, floatArrayOf(hue, s, v))),
)

/** 「莫奈」页：系统动态色 + 常用预设色，点击即取色 */
@Composable
private fun MonetColors(onPick: (Int) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val dynamic = listOf(
        scheme.primary, scheme.secondary, scheme.primaryVariant, scheme.secondaryVariant,
        scheme.primaryContainer, scheme.secondaryContainer,
    ).map { it.toArgb() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("系统动态色", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        SwatchRow(dynamic, onPick)
        Text("常用色", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        SwatchRow(PRESET_COLORS, onPick)
    }
}

@Composable
private fun SwatchRow(colors: List<Int>, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        colors.forEach { c ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .checkerboard(5.dp)
                    .background(Color(c))
                    .clickablePick { onPick(c) },
            )
        }
    }
}

/** 常用预设色（含 HyperCeiler 默认的半透明黑） */
private val PRESET_COLORS = listOf(
    0xCC000000.toInt(), 0xFF000000.toInt(), 0xFF1F1F1F.toInt(), 0xFF3F3F3F.toInt(),
    0xFFFFFFFF.toInt(), 0xFFEFEFEF.toInt(), 0xFF9E9E9E.toInt(), 0x669E9E9E.toInt(),
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(),
)

/** HyperCeiler 里侧边栏背景色的默认值（-1 即不改色，本项目默认半透明黑） */
internal const val DEFAULT_SIDEBAR_COLOR = 0xCC000000.toInt()

/** 8 位 ARGB 十六进制文本，带 #，与侧车存储格式（%08X）一致 */
private fun hexOf(argb: Int): String = String.format("#%08X", argb)

/** 由 HSV + 透明度分量直接算 ARGB —— 颜色由分量派生，不做任何反向反算 */
private fun hsvToArgb(hue: Float, s: Float, v: Float, a: Float): Int =
    AColor.HSVToColor((a * 255f).roundToInt(), floatArrayOf(hue, s * 100f, v * 100f))

/** 透明底棋盘：让预览色块和色板能看出透明度 */
private fun Modifier.checkerboard(cell: Dp): Modifier = drawBehind {
    val c = cell.toPx()
    if (c <= 0f) return@drawBehind
    val cols = ceil(size.width / c).toInt()
    val rows = ceil(size.height / c).toInt()
    for (r in 0 until rows) {
        for (col in 0 until cols) {
            drawRect(
                color = if ((r + col) % 2 == 0) Color(0xFFFFFFFF) else Color(0xFFCCCCCC),
                topLeft = Offset(col * c, r * c),
                size = Size(c, c),
            )
        }
    }
}

/** 让色块可点击（用最朴素的 clickable，避免引入额外依赖） */
private fun Modifier.clickablePick(onPick: () -> Unit): Modifier = this.clickable { onPick() }
