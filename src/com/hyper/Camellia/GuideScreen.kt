package com.hyper.Camellia

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首次启动引导（仿 HyperCeiler 的 provision 流程，改写成 Compose 单页 4 步）。
 *
 * 顺序：欢迎 → 用户协议 → 必要授权 / 环境检查 → 完成。
 * 只有「用户协议」是硬门槛（不同意直接退出），其余检查项允许跳过 ——
 * Root 与目标应用的状态随时会变，卡在这里没有意义，如实展示即可。
 */
private enum class GuideStep(val index: Int) {
    Welcome(0), Terms(1), Permissions(2), Done(3),
}

/** 一条环境检查结果 */
private data class GuideCheck(
    val key: String,
    val title: String,
    val ok: Boolean,
    val detail: String,
    /** 可在页内一键处理时给出的按钮文案； 表示只能手动处理 */
    val action: String? = null,
)

private val GUIDE_STEPS = listOf(GuideStep.Welcome, GuideStep.Terms, GuideStep.Permissions, GuideStep.Done)

@Composable
internal fun GuideScreen(
    onFinish: () -> Unit,
    onAgree: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf(GuideStep.Welcome) }
    var agreed by remember { mutableStateOf(false) }
    var checks by remember { mutableStateOf<List<GuideCheck>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var scanTick by remember { mutableIntStateOf(0) }

    // 进入「授权检查」这一步时才真正去探测：Root 检测要 fork su，没必要在欢迎页就跑
    LaunchedEffect(step, scanTick) {
        if (step != GuideStep.Permissions) {
            return@LaunchedEffect
        }
        scanning = true
        // scanTick > 0 说明用户点了「重新检测」（刚去管理器授过权），此时强制重探 root；
        // 首次进入这一步沿用 RootState 的缓存，避免每次切步骤都 fork su 弹框。
        val forceRoot = scanTick > 0
        val result = withContext(Dispatchers.IO) { runGuideChecks(ctx, forceRoot) }
        checks = result
        scanning = false
    }

    // ---- 入场动画：仿 HyperCeiler provision 的 enter_home_anim ----
    // 整页 scale 1.3 → 1.0 用 spring(damping 0.65 / response 0.55 ≈ 中低刚度)，
    // alpha 0 → 1 用 sine-in-out 230ms 且延迟 60ms。两者错开，才有「页面上浮后落定」的层次感。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 先让首帧以初始值真实绘制一次再翻转状态，否则翻转发生在首帧之前，动画会被整段跳过
        withFrameNanos { }
        entered = true
    }
    val enterScale by animateFloatAsState(
        targetValue = if (entered) 1f else 1.3f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "guide-enter-scale",
    )
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = 230,
            delayMillis = 60,
            easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f),
        ),
        label = "guide-enter-alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .graphicsLayer {
                scaleX = enterScale
                scaleY = enterScale
                alpha = enterAlpha
            },
    ) {
        GuideGlowBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            GuideHeader(step)
            Spacer(Modifier.height(8.dp))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    // 350ms 整页水平对推（对应 provision_slide_in_left/right + slide_out_*）：
                    // 前进时新页自右滑入、旧页向左滑出，回退时方向相反。
                    // 原实现用的 accelerate-decelerate 在这里用 FastOutSlowIn 近似。
                    val forward = targetState.index > initialState.index
                    val slide = tween<IntOffset>(durationMillis = 350, easing = FastOutSlowInEasing)
                    val fade = tween<Float>(durationMillis = 200, easing = LinearEasing)
                    (slideInHorizontally(slide) { if (forward) it else -it } + fadeIn(fade))
                        .togetherWith(
                            slideOutHorizontally(slide) { if (forward) -it else it } + fadeOut(fade),
                        )
                },
                label = "guide-step",
            ) { current ->
                Column {
                    when (current) {
                        GuideStep.Welcome -> WelcomeStep(onNext = { step = GuideStep.Terms })
                        GuideStep.Terms -> TermsStep(
                            agreed = agreed,
                            onAgreeChange = { agreed = it },
                            onNext = {
                                if (agreed) {
                                    onAgree()
                                    step = GuideStep.Permissions
                                }
                            },
                            onReject = { (ctx as? Activity)?.finish() },
                        )
                        GuideStep.Permissions -> PermissionsStep(
                            checks = checks,
                            scanning = scanning,
                            onRescan = { scanTick++ },
                            onAction = { c -> handleGuideAction(ctx, c) },
                            onNext = { step = GuideStep.Done },
                        )
                        GuideStep.Done -> DoneStep(onFinish = onFinish)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}


// ---------------- 背景光效（仿 HyperCeiler 的 GlowPainter）----------------

// GlowPainter 里 AGSL 使用的三个调色：#F52828 / #9AA8F5 / #4D4AD7
private val GUIDE_GLOW_RED = Color(0xFFF52828)
private val GUIDE_GLOW_BLUE = Color(0xFF9AA8F5)
private val GUIDE_GLOW_PURPLE = Color(0xFF4D4AD7)

/**
 * 引导页背景：三团大半径彩雾缓慢漂移，叠一条扫掠渐变描边的圆环做呼吸。
 *
 * HyperCeiler 的原实现是 AGSL RuntimeShader 挂在 RenderEffect 上（GlowController 以 60fps
 * 推送 uTime，时间轴在 2~120s 之间 ping-pong）。Compose 里走 RuntimeShader 要自己管
 * RenderEffect 生命周期，而且每帧都要额外合成一整屏，和本模块页面里的玻璃 backdrop 叠加
 * 代价太高；这里用 Canvas 的径向 / 扫掠渐变把同样的观感画出来 —— 单帧只有几次 drawCircle，
 * 渐变全在 GPU 上做，几乎不占开销。
 */
@Composable
private fun GuideGlowBackground() {
    // 浅色主题下彩雾要压暗，否则白底上的红光会糊成一片脏粉
    val glowAlpha = if (MiuixTheme.colorScheme.surface.luminance() < 0.5f) 1f else 0.5f
    val transition = rememberInfiniteTransition(label = "guide-glow")
    // HyperCeiler 的 GlowController 把 uTime 在 0~120s 之间 ping-pong，但它的 AGSL 里同时跑着
    // fbm 噪声、0.9 倍速的环形脉动和色相偏移，所以观感上并不静止。这里没有噪声函数，
    // 若照搬 120s 单程，光斑每秒只挪动千分之几屏宽，肉眼等同静止 —— 所以把基础时间轴压到
    // 18s 一个单程（36s 一个完整往返），并把「色相流动」和「光带旋转」拆成各自更快的周期。
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(durationMillis = 18_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "guide-glow-t",
    )
    // 环形光带另一路时间轴：旋转 + 呼吸，7s 一周期，比光雾快，画面才有「活着」的感觉
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(durationMillis = 7_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "guide-glow-spin",
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val full = (Math.PI * 2).toFloat()
        val radius = min(w, h) * 0.95f
        val a = t * full

        // ① 色相流动：三种调色随时间互相替换，形成「渐变一直在慢慢变色」
        val phase = 0.5f - 0.5f * cos(a)
        val cRed = lerp(GUIDE_GLOW_RED, GUIDE_GLOW_PURPLE, phase)
        val cBlue = lerp(GUIDE_GLOW_BLUE, GUIDE_GLOW_RED, phase)
        val cPurple = lerp(GUIDE_GLOW_PURPLE, GUIDE_GLOW_BLUE, phase)

        // ② 四团大半径光斑各自沿椭圆漂移（相位错开 90°），重叠出流动的彩雾
        val blobs = listOf(
            Triple(
                Offset(
                    w * 0.18f + cos(a) * w * 0.16f,
                    h * 0.16f + sin(a) * h * 0.10f,
                ),
                cRed,
                0.34f,
            ),
            Triple(
                Offset(
                    w * 0.82f + cos(a + 1.6f) * w * 0.14f,
                    h * 0.28f + sin(a + 1.6f) * h * 0.12f,
                ),
                cBlue,
                0.30f,
            ),
            Triple(
                Offset(
                    w * 0.46f + cos(a + 3.1f) * w * 0.18f,
                    h * 0.62f + sin(a + 3.1f) * h * 0.10f,
                ),
                cPurple,
                0.32f,
            ),
            Triple(
                Offset(
                    w * 0.30f + cos(a + 4.7f) * w * 0.15f,
                    h * 0.88f + sin(a + 4.7f) * h * 0.07f,
                ),
                lerp(cRed, cPurple, 0.5f),
                0.22f,
            ),
        )
        blobs.forEach { (center, color, alpha) ->
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha * glowAlpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // ③ 环形光带：圆心落在标题下方，一边自转一边呼吸（HyperCeiler 的 uCircleSpeed 0.9）
        val ring = Offset(w * 0.5f, h * 0.30f)
        val pulse = 0.5f + 0.5f * sin(spin * full)
        val ringRadius = min(w, h) * (0.32f + 0.10f * pulse)
        rotate(degrees = spin * 360f, pivot = ring) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        GUIDE_GLOW_RED.copy(alpha = 0f),
                        GUIDE_GLOW_RED.copy(alpha = 0.50f * glowAlpha),
                        GUIDE_GLOW_BLUE.copy(alpha = 0.60f * glowAlpha),
                        GUIDE_GLOW_PURPLE.copy(alpha = 0.50f * glowAlpha),
                        GUIDE_GLOW_RED.copy(alpha = 0f),
                    ),
                    center = ring,
                ),
                radius = ringRadius,
                center = ring,
                style = Stroke(width = ringRadius * 0.38f, cap = StrokeCap.Round),
                blendMode = BlendMode.Plus,
            )
        }
    }
}


// ---------------- 顶部：标题 + 进度 ----------------

@Composable
private fun GuideHeader(step: GuideStep) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
        Text(
            "Camellia",
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when (step) {
                GuideStep.Welcome -> "使用前须知"
                GuideStep.Terms -> "用户协议与隐私说明"
                GuideStep.Permissions -> "必要授权与环境检查"
                GuideStep.Done -> "准备完成"
            },
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GUIDE_STEPS.forEach { s ->
                Box(
                    modifier = Modifier
                        .size(if (s == step) 22.dp else 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (s.index <= step.index) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                            },
                        ),
                )
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

// ---------------- ① 欢迎 ----------------

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    SmallTitle(text = "这是什么")
    Card(modifier = Modifier.padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Camellia 是一个基于 LSPosed 的模块，用来补齐小米互传、小米画报、手机管家与桌面上的若干细节行为：",
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(10.dp))
            listOf(
                "小米互传：收到的文件直接落到指定目录，可交给 MT 管理器打开",
                "小米画报：自选图片目录作为图源，支持顺序 / 随机与张数上限",
                "手机管家：解锁侧边栏应用数量、侧边栏背景自定义、各类确认拦截",
                "小窗与快捷方式：解除数量限制、侧边栏长按直接小窗打开应用",
            ).forEach {
                Text(
                    "· $it",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "所有功能都需要重启对应的目标应用（或重启手机管家进程）才会生效；" +
                    "本模块不会修改任何应用的安装包，随时关闭开关即可恢复原状。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Button(onClick = onNext) { Text("开始") }
}

// ---------------- ② 用户协议 ----------------

@Composable
private fun TermsStep(
    agreed: Boolean,
    onAgreeChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onReject: () -> Unit,
) {
    SmallTitle(text = "用户协议与隐私说明")
    Card(modifier = Modifier.padding(bottom = 12.dp)) {
        Column(
            modifier = Modifier
                .height(280.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            TermsSection(
                "一、使用风险",
                "本模块通过系统级 hook 修改第三方应用的运行行为，可能与该应用的后续版本不兼容，" +
                    "也可能引起界面异常、功能失效等问题。请自行评估风险并对自己的设备负责；" +
                    "在重要数据场景下，请先做好备份。",
            )
            TermsSection(
                "二、数据与隐私",
                "本模块完全离线运行，不含任何联网上传逻辑，不收集、不统计、不分享任何个人信息。" +
                    "所有配置（开关、关键词、路径、颜色等）只写入本机：应用私有目录下的 SharedPreferences，" +
                    "以及为跨进程共享而写在应用私有外部目录 /sdcard/Android/data/" +
                    "com.hyper.Camellia/files/ 下的侧车文本文件。卸载本模块即全部清除。",
            )
            TermsSection(
                "三、权限说明",
                "· Root：仅用于执行 su 命令（结束目标应用、读取状态、触发系统服务），" +
                    "未授予 Root 时相关功能会自动降级或跳过，不会影响其它功能。\n" +
                    "· LSPosed：模块必须被激活并勾选相应作用域（小米互传 / 小米画报 / 手机管家 / " +
                    "system_server）才会生效。\n" +
                    "· 查询已安装应用：仅用于判断目标应用是否存在及其版本，不做上传。",
            )
            TermsSection(
                "四、免责声明",
                "本模块按「现状」提供，不对因使用本模块造成的任何直接或间接损失负责。" +
                    "请在理解上述内容后自行决定是否继续使用。",
            )
            TermsSection(
                "五、开源与致谢",
                "本模块的诸多实现思路来自 HyperCeiler（AGPL-3.0）与 LSPosed 社区，" +
                    "相关引用的具体出处已列在「关于与鸣谢」页。",
            )
        }
    }
    Card(modifier = Modifier.padding(bottom = 12.dp)) {
        SwitchPreference(
            title = "我已阅读并同意上述条款",
            summary = "不同意将无法继续使用本模块",
            checked = agreed,
            onCheckedChange = onAgreeChange,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onNext, enabled = agreed) { Text("同意并继续") }
        Spacer(Modifier.width(12.dp))
        TextButton(text = "不同意并退出", onClick = onReject)
    }
}

@Composable
private fun TermsSection(title: String, body: String) {
    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        body,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    Spacer(Modifier.height(14.dp))
}

// ---------------- ③ 必要授权 / 环境检查 ----------------

@Composable
private fun PermissionsStep(
    checks: List<GuideCheck>,
    scanning: Boolean,
    onRescan: () -> Unit,
    onAction: (GuideCheck) -> Unit,
    onNext: () -> Unit,
) {
    SmallTitle(
        if (scanning) "正在检测环境…" else "环境检查（${checks.count { it.ok }} / ${checks.size} 项就绪）",
    )
    Card(modifier = Modifier.padding(bottom = 12.dp)) {
        if (checks.isEmpty() && scanning) {
            BasicComponent(
                title = "正在检查…",
                summary = "Root / LSPosed / 目标应用",
            )
        }
        checks.forEachIndexed { i, c ->
            if (i > 0) {
                HorizontalDivider()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (c.ok) {
                                Color(0xFF43A047)
                            } else {
                                Color(0xFFE6A23C)
                            },
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(c.title, fontSize = 15.sp)
                    if (c.detail.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            c.detail,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                // 能一键处理的项（如 root 未授权 → 跳管理器）给个按钮，其余项 c.action 为 null
                c.action?.let { label ->
                    Spacer(Modifier.width(8.dp))
                    TextButton(text = label, onClick = { onAction(c) })
                }
            }
        }
    }
    TextButton(text = "重新检测", onClick = onRescan)
    Spacer(Modifier.height(8.dp))
    BasicComponent(
        title = "这些检查不影响进入设置页",
        summary = "未就绪的项可以稍后处理；开关保存后需要重启目标应用才会生效",
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Button(onClick = onNext) { Text("下一步") }
    }
}

// ---------------- ④ 完成 ----------------

@Composable
private fun DoneStep(onFinish: () -> Unit) {
    SmallTitle(text = "开始使用")
    Card(modifier = Modifier.padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("启用的下一步", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            listOf(
                "在 LSPosed 里勾选作用域：小米互传、小米画报、手机管家（部分功能还需 system_server）",
                "回到这里按需打开各项开关，改动会写入配置文件",
                "重启对应的目标应用（设置页提供一键结束入口）后生效",
            ).forEach {
                Text(
                    "· $it",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "本页随时可以从「设置 → 重新查看引导」再次打开。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Button(onClick = onFinish) { Text("开始使用") }
    }
}

// ---------------- 环境探测 ----------------

/**
 * 探测 Root / LSPosed / 目标应用。
 *
 * 全部是只读判断，不会修改设备状态；Root 检测走 su（首次会弹出授权框，
 * 这也是引导页希望达成的目的之一：让用户在需要时就地授权，而不是等某个功能突然失效）。
 */
/**
 * 等框架把 XposedService binder 投递过来（最多 1.5s）。
 *
 * 投递链路是：App 启动 → framework 调 `<pkg>.XposedService` provider → binder 落到
 * {@code CfgRemote} 的监听里。它和 Activity 首帧是两条并行路径，引导页可能在 binder 之前
 * 就开始探测，所以这里做一次有上限的轮询（本函数只在 IO 线程被调用）。
 */
private fun awaitFrameworkService(timeoutMs: Long = 1500): Boolean {
    if (CfgRemote.available()) {
        return true
    }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            Thread.sleep(60)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            break
        }
        if (CfgRemote.available()) {
            return true
        }
    }
    return CfgRemote.available()
}

private fun runGuideChecks(ctx: Context, forceRoot: Boolean = false): List<GuideCheck> {
    // root 探测统一走 RootState：3s 超时（超时算未授权，不再永久卡死引导页）、
    // 进程内缓存 + 落盘复用，避免每次重扫都 fork su 弹框；
    // 探测到 GRANTED 时会顺带把 caps 同步给 camd（决定 camd 用快通道还是慢轮询）。
    val rootStatus = RootState.probe(ctx, forceRoot)
    val rootOk = rootStatus == RootStatus.GRANTED
    val suPath = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/adb/ksu/bin/su")
        .firstOrNull { File(it).exists() }

    // LSPosed：直接用框架下发的 XposedService（App.onCreate 里 CfgRemote.init 已注册监听）读真实状态。
    // binder 只在「框架在跑 + 本模块已被框架认可」时经 <pkg>.XposedService provider 投递，
    // 拿到它就等于环境可用；getScope() 直接给出用户勾选的作用域，getRunningTargets() 给出已注入进程。
    // 投递相对 Activity 启动是异步的，这里最多等 1.5s，避免引导页比 binder 先到而误报「未检测到」。
    val lspBound = awaitFrameworkService()
    val lspScope = if (lspBound) CfgRemote.scope().orEmpty() else emptyList()
    val lspRunning = if (lspBound) CfgRemote.runningTargets().orEmpty() else emptyList()
    val lspTag = if (lspBound) {
        listOfNotNull(CfgRemote.frameworkName(), CfgRemote.frameworkVersion()?.takeIf { it.isNotBlank() })
            .joinToString(" ").ifBlank { "Xposed" }
    } else {
        "Xposed"
    }
    // 管理器包名只在拿不到 binder 时作为"装了但没生效"的线索
    val lspManager = arrayOf("org.lsposed.manager", "de.robv.android.xposed.installer")
        .firstOrNull { Prefs.isInstalled(ctx, it) }
    val lspOk = lspBound
    val lspDetail = when {
        lspBound && lspScope.isNotEmpty() && Prefs.TARGET_PKG in lspScope ->
            "$lspTag · 作用域 ${lspScope.size} 项（含小米互传）" +
                if (lspRunning.isEmpty()) " · 目标应用尚未启动" else " · 已注入 ${lspRunning.size} 个进程"
        lspBound && lspScope.isNotEmpty() -> "$lspTag · 作用域 ${lspScope.size} 项，但未勾选小米互传"
        lspBound -> "$lspTag · 框架已连上，但作用域为空：请在 LSPosed 里勾选小米互传"
        lspManager != null -> "已安装 $lspManager，但框架未下发服务：请确认模块已启用并重启手机"
        else -> "未检测到 LSPosed：本模块的全部功能都依赖它"
    }

    val mtOk = Prefs.isInstalled(ctx, Prefs.TARGET_PKG)
    val fgOk = Prefs.isInstalled(ctx, Prefs.FG_PKG)
    val scOk = Prefs.isInstalled(ctx, Prefs.SECURITY_PKG)

    return listOf(
        GuideCheck(
            key = "root",
            title = "Root 权限",
            ok = rootOk,
            detail = when (rootStatus) {
                RootStatus.GRANTED -> "已授予 Root（su 可用）· 指令通道：快（inotify）"
                RootStatus.DENIED ->
                    "检测到 ${suPath ?: "su"}，但 su 未返回 uid=0（可能没点授权框）：点右侧按钮去授权"
                RootStatus.NO_SU -> "未检测到 su：可跳过，部分功能（结束进程 / 读取系统状态）会降级"
                RootStatus.UNKNOWN -> "未检测到 su：可跳过，部分功能会降级"
            },
            action = if (rootStatus == RootStatus.DENIED) "去授权" else null,
        ),
        GuideCheck(
            key = "lsp",
            title = "LSPosed 环境",
            ok = lspOk,
            detail = lspDetail,
        ),
        GuideCheck(
            key = "mt",
            title = "小米互传",
            ok = mtOk,
            detail = if (mtOk) Prefs.TARGET_PKG else "未安装（${Prefs.TARGET_PKG}）：相关功能会被隐藏",
        ),
        GuideCheck(
            key = "fg",
            title = "小米画报",
            ok = fgOk,
            detail = if (fgOk) Prefs.FG_PKG else "未安装（${Prefs.FG_PKG}）：画报图源同步不可用",
        ),
        GuideCheck(
            key = "sc",
            title = "手机管家",
            ok = scOk,
            detail = if (scOk) {
                Prefs.SECURITY_PKG
            } else {
                "未安装（${Prefs.SECURITY_PKG}）：侧边栏 / 拦截类功能不可用"
            },
        ),
    )
}

/** 执行 `su -c <cmd>` 并返回输出；失败或无 su 时返回 null */
/** 引导页「一键处理」：只做当前环境确实能做的跳转，其余留给用户手动。 */
private fun handleGuideAction(ctx: Context, c: GuideCheck) {
    when (c.key) {
        "root" -> openRootManager(ctx)
        else -> Unit
    }
}

/** 按优先级尝试打开 root 管理器（KernelSU / Magisk / APatch），找不到就提示手动处理。 */
private fun openRootManager(ctx: Context) {
    val pkgs = listOf(
        "com.derubao.zzz",      // KernelSU
        "me.weishu.kernelsu",   // KernelSU 旧包名
        "com.topjohnwu.magisk", // Magisk
        "me.bmax.apatch",       // APatch
    )
    for (p in pkgs) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(p) ?: continue
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (ignored: Throwable) {
        }
    }
    Toast.makeText(ctx, "未找到 Root 管理器，请手动打开后授权本模块", Toast.LENGTH_LONG).show()
}
