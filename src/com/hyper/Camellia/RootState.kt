package com.hyper.Camellia

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 权限的探测结论。
 *
 * 四个状态而不是布尔值，因为「没有 su」「有 su 但没授权」「授权了」在 UI 上
 * 给用户的指引完全不同：NO_SU 要换设备/换方案，DENIED 要去管理器点允许。
 */
internal enum class RootStatus(val code: Int) {
    UNKNOWN(0),
    GRANTED(1),
    DENIED(2),
    NO_SU(3),
}

/**
 * Root 状态：探测 + 缓存 + 持久化 + 联动 camd 通道。
 *
 * 为什么要独立成一层：引导页（GuideScreen）和 camd 面板（设置页）都需要知道
 * 「本 App 有没有 root」，但探测要 fork su（首次还会弹授权框），不能每个调用点
 * 各探一次 —— 那会重复弹框、重复等 3s。这里做成「进程内缓存 + SharedPreferences
 * 落盘」，一次探测全局复用；探测结论变化时顺带同步给 camd。
 *
 * 探测策略（方案 A：超时算未授权）：
 *   - 3s 超时。超时意味着用户没在授权框上做选择（切后台 / 锁屏 / 犹豫），
 *     一律按未授权处理，不阻断引导流程 —— 环境检查本身就是「如实展示」，
 *     卡在这里没有意义。
 *   - 只有用户主动点「重新检测」才 force 重探，避免反复弹框。
 */
internal object RootState {

    private const val PROBE_TIMEOUT_MS = 3000L

    private const val KEY_STATUS = "root_status"
    private const val KEY_TS = "root_ts"

    /** 进程内缓存：UNKNOWN 表示本进程还没探过（≠ 上次落盘的结论）。 */
    @Volatile
    private var cached: RootStatus = RootStatus.UNKNOWN

    @Volatile
    private var cachedAt: Long = 0L

    /** su 常见路径：只用于区分「没有 su」和「有 su 但被拒」，不用于执行。 */
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
    )

    /** 不阻塞地读当前认知（进程内缓存 → SharedPreferences 兜底）。 */
    fun cached(ctx: Context): RootStatus {
        if (cached != RootStatus.UNKNOWN) return cached
        val sp = Prefs.get(ctx)
        val code = sp.getInt(KEY_STATUS, RootStatus.UNKNOWN.code)
        val s = RootStatus.entries.firstOrNull { it.code == code } ?: RootStatus.UNKNOWN
        cached = s
        cachedAt = sp.getLong(KEY_TS, 0L)
        return s
    }

    /** 上次探测时间（epoch ms），0 表示从未探过。 */
    fun cachedAt(ctx: Context): Long {
        if (cachedAt > 0L) return cachedAt
        cachedAt = Prefs.get(ctx).getLong(KEY_TS, 0L)
        return cachedAt
    }

    /** su 是否存在于常见路径（不执行，1 次 stat）。 */
    fun hasSuBinary(): Boolean = SU_PATHS.any { File(it).exists() }

    /**
     * 实测 root。**必须在 IO 线程调用**（fork su + 最多等 3s）。
     *
     * @param force false 时优先沿用缓存，只有 UNKNOWN 才真正探测；
     *              true 用于用户主动点「重新检测」。
     */
    fun probe(ctx: Context, force: Boolean = false): RootStatus {
        if (!force) {
            val c = cached(ctx)
            if (c != RootStatus.UNKNOWN) return c
        }
        val s = doProbe()
        cached = s
        cachedAt = System.currentTimeMillis()
        Prefs.get(ctx).edit()
            .putInt(KEY_STATUS, s.code)
            .putLong(KEY_TS, cachedAt)
            .apply()
        // 结论变化同步给 camd：有 root 走快通道（inotify），没有就继续慢轮询
        if (s == RootStatus.GRANTED) {
            CamdRescue.syncCap(ctx)
        } else {
            CamdRescue.clearCap(ctx)
        }
        return s
    }

    private fun doProbe(): RootStatus {
        val out = runSu("id", PROBE_TIMEOUT_MS)
        return when {
            out != null && out.contains("uid=0") -> RootStatus.GRANTED
            hasSuBinary() -> RootStatus.DENIED
            else -> RootStatus.NO_SU
        }
    }

    /**
     * 执行 `su -c <cmd>`，带超时。
     *
     * 关键：waitFor 必须有超时。原实现是 `p.waitFor()` + `readText()` 无超时，
     * 用户不在 KernelSU 授权框上做选择时 su 会一直挂着，调用线程永久阻塞 ——
     * 引导页表现为永远停在「正在检测环境…」，还进不了下一步（真实踩过）。
     *
     * 先 waitFor 再读 stdout：su 的输出（id / 写 caps 的报错）都很短，
     * 远小于管道缓冲 64KB，不会因缓冲区满而产生死锁。
     *
     * @return 输出文本（trim 后）；超时 / 无 su / 异常一律 null
     */
    fun runSu(cmd: String, timeoutMs: Long = PROBE_TIMEOUT_MS): String? {
        var p: Process? = null
        return try {
            p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                // 超时：用户没点授权框，或 su 卡在启动阶段。按未授权处理。
                null
            } else {
                p.inputStream.bufferedReader().use { it.readText() }.trim()
            }
        } catch (ignored: Throwable) {
            null
        } finally {
            try {
                p?.destroy()
            } catch (ignored: Throwable) {
            }
        }
    }
}
