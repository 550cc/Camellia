package com.hyper.Camellia

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 主题 / 字体 / 桌面布局的定时快照。
 *
 * 一句话：把「系统被重置后要手动重配的半天天」压缩成「一条命令 + 重启桌面」。
 *
 * 背景：MIUI 的 RescuePartyPlus（com.android.server.RescuePartyPlusHelper）在崩溃计数达阈值后
 * 执行一串不可逆删除：resetTheme() 清 /data/system/theme，resetFont() 清 fonts，
 * L4 对 com.miui.home 做 pm clear，L5 CONFIG_RESET 删 settings 后自动重启。
 * 本类不干预系统行为，只负责「先把能救的东西存下来」。是否禁用急救由用户在别处决定。
 *
 * ---- 实测结论（决定了下面每一处写法）----
 * 1) tar 是 Toybox 0.8.13，支持 -z / --selinux，但**不认 --numeric-owner**（会静默忽略）。
 *    所以命令里不再写它；归档内的属主由 -p 与 --selinux 保证，解包后实测
 *    uid/gid(6101:6101)、SELinux 标签 theme_data_file、符号链接全部正确。
 * 2) 主题 52 MB、gzip 后 39 MB（字体 ttf 已压缩，压不动）；耗时约 4.4 s。
 * 3) 桌面真正的内容在 /data/user_de/0/com.miui.home/**databases**/，主要是
 *    launcher4xN.db（库名随桌面网格变，本机 OS4 是 4x6）+ -wal + -shm。
 *    .db 本身只有几 KB，几 MB 的布局数据全在未 checkpoint 的 -wal 里，
 *    所以三者必须一起打包。注意是 databases 复数。
 *    同目录还有 onetrack_cache / universal_ot_monitor 等埋点缓存（数 MB），刻意排除。
 * 4) 壁纸 101 MB 里 97 MB 是动态壁纸视频（video/ video_gallery/ linkage_video/
 *    video_depth_editor/）。这类东西既不该进快照也不该占快照额度，所以壁纸档改成
 *    **白名单只取静态子目录**（gallery image default dark depth_blur data
 *    super_save_power super_wallpaper maml sensor），合计约 8 MB。
 * 5) 存放位置 /data/adb/camellia/dsnap：root 私有域，不在急救清理清单内，
 *    也不受「清除数据」影响。App 侧一律靠 su 访问。
 *
 * ---- 可靠性设计 ----
 * · 三类内容各自独立打包，**单类失败不影响其它类**，失败原因写进 meta 的 fail= 字段，
 *   全部失败才整体判失败。旧实现是任一 tar 失败就 exit，壁纸一大就全军覆没。
 * · 最多保留 [KEEP] 份，淘汰优先级 auto → pre-restore → manual，
 *   手动快照不会被定时任务挤掉。
 * · 恢复前脚本先做 sha256 + 归档可读性预检，预检不过直接退出、一个字节都不删。
 * · 恢复是破坏性的，调用方必须先 [create] 一份 src=pre-restore 的快照。
 */
internal object SnapshotMgr {

    private const val TAG = "MiShareMT2/Snap"

    /** 快照根目录，刻意放在 /data/adb 下 */
    const val ROOT = "/data/adb/camellia/dsnap"

    /** 最多保留几份 */
    const val KEEP = 5

    /**
     * 可备份 / 恢复的数据类别，key 与归档名一致。
     *
     * 壁纸拆成三档的原因（实测教训）：动态壁纸的「状态」写在
     * theme_magic/users/0/wallpaper/data 下 xml 的 type/path* 里，
     * 「素材」是 XML 引用的 video 目录 mp4 与 image 目录 jpg。两者必须成对，
     * 只备份 XML 不备份素材，恢复后 type="video" 却找不到 mp4 → 黑屏。
     * 所以拆成「状态+静态素材」与「动态视频素材」两档，且后者依赖前者。
     */
    enum class Kind(
        val key: String,
        val label: String,
        val detail: String,
        val approxMb: Int,
        val onByDefault: Boolean,
        /** 勾了本档要自动带上的前置档（key） */
        val needs: Set<String> = emptySet(),
    ) {
        LAUNCHER(
            "launcher", "桌面布局",
            "图标位置 / 文件夹 / 抽屉 / 小组件 / 应用分类（db + wal + shm + 偏好）", 6, true,
        ),
        THEME(
            "theme", "主题与字体",
            "/data/system/theme 全量 + /data/system/fonts + 字体与主题设置项", 16, true,
        ),
        WALLPAPER(
            "wallpaper", "壁纸状态与静态素材",
            "壁纸 XML（type/path 决定用哪张图）+ 静态素材 + 系统壁纸记录", 5, true,
        ),
        WP_VIDEO(
            "wp_video", "壁纸动态视频",
            "按 XML 引用自动抓取 video/ 下的 mp4 与缩略图（不压缩，约 96 MB）", 96, true,
            needs = setOf("wallpaper"),
        ),
        WP_AOD(
            "wp_aod", "AOD 景深壁纸",
            "com.miui.aod 模板当前套（videoWallpaper 等，约 100 MB，内容与动态视频重复）", 100, false,
            needs = setOf("wallpaper"),
        );

        companion object {
            fun of(key: String): Kind? = entries.firstOrNull { it.key == key }
            val defaults: Set<Kind> get() = entries.filter { it.onByDefault }.toSet()

            /** 补齐依赖：勾了 wp_video 就自动带上 wallpaper，避免半完成态 */
            fun expand(kinds: Set<Kind>): Set<Kind> {
                val out = kinds.toMutableSet()
                var changed = true
                while (changed) {
                    changed = false
                    for (k in out.toList()) {
                        for (n in k.needs) {
                            val d = of(n) ?: continue
                            if (out.add(d)) changed = true
                        }
                    }
                }
                return out
            }
        }
    }

    /** 快照来源，决定空间不足时谁先被淘汰 */
    enum class Src(val key: String, val label: String) {
        MANUAL("manual", "手动"),
        AUTO("auto", "定时"),
        PRE("pre-restore", "恢复前");

        companion object {
            fun of(key: String): Src =
                entries.firstOrNull { it.key == key } ?: MANUAL
        }
    }

    /** 一份快照。id 同时是目录名，形如 20261107_153012 */
    data class Snap(
        val id: String,
        val ts: Long,
        val note: String,
        val ver: String,
        val src: Src,
        val parts: Map<String, Long>,
        /** 打包失败或缺失的档位，形如 ["wallpaper"] */
        val fails: List<String>,
    ) {
        val size: Long get() = parts.values.sum()
        val kinds: List<Kind> get() = Kind.entries.filter { parts.containsKey(it.key) }
        val sizeText: String get() = fmtSize(size)
        /** meta 里的 ts 优先，缺失时用目录名兜底 */
        val at: Long get() = if (ts > 0) ts else idToTs(id)
        val partial: Boolean get() = fails.isNotEmpty()
    }

    data class SuOut(val code: Int, val out: String) {
        val ok: Boolean get() = code == 0
        /** 脚本内用 ERR_ 前缀报告失败原因 */
        val errToken: String? get() = Regex("ERR_[A-Z_]+").find(out)?.value
    }

    fun fmtSize(b: Long): String = when {
        b >= 1073741824L -> String.format("%.2f GB", b / 1073741824.0)
        b >= 1048576L -> String.format("%.1f MB", b / 1048576.0)
        b >= 1024L -> String.format("%.0f KB", b / 1024.0)
        else -> "" + b + " B"
    }

    private val ID_RE = Regex("^\\d{8}_\\d{6}$")

    fun isValidId(id: String): Boolean = ID_RE.matches(id)

    private fun idToTs(id: String): Long = try {
        val m = Regex("^(\\d{8})_(\\d{6})$").find(id)
        if (m == null) 0L else {
            java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
                .parse(m.groupValues[1] + m.groupValues[2])?.time ?: 0L
        }
    } catch (ignored: Throwable) {
        0L
    }

    fun fmtTime(ts: Long): String {
        if (ts <= 0) return "—"
        // 必须带秒：定时/手动连着打几份时，只到分钟会显示成同一时间，
        // 看起来像「时间不对」而且列表里几条一模一样分不清。
        val f = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        return f.format(java.util.Date(ts))
    }

    /**
     * 跑一段脚本。刻意不逐条 `su -c`：备份要连续做打包 / 统计 / 写 meta，
     * 每条一次 su 会反复弹授权，也定位不到失败点。
     */
    /**
     * 是否支持 `su -M`（强制全局 mount namespace）。
     *
     * 必须用 -M。KernelSU/Magisk 下裸 `su -c` 默认沿用**调用方 app 的 mount
     * namespace**，而 app 的 ns 里看不到别的 app 的 /data/user_de/0/<pkg>：
     * 实测已拿到 uid=0 仍报 `ls: /data/user_de/0/com.miui.home: No such file
     * or directory`。不加 -M 会直接导致两个假故障：
     *   1) 只勾「桌面布局」时永远 launcher_empty → 唯一档失败 → 整体判失败；
     *   2) AOD 档永远 wp_aod_nodir → 勾不勾备份体积完全一样（从没成功过）。
     * 探测一次并缓存，避免在长备份脚本上做重试。
     */
    private val mountMaster: Boolean by lazy {
        try {
            val p = ProcessBuilder("su", "-M", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(8, TimeUnit.SECONDS)
            out.contains("uid=0")
        } catch (ignored: Throwable) {
            false
        }
    }

    fun runSu(script: String, timeoutMs: Long = 180_000L): SuOut {
        var proc: Process? = null
        return try {
            val argv =
                if (mountMaster) arrayOf("su", "-M", "-c", script)
                else arrayOf("su", "-c", script)
            val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
            proc = p
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = r.read(buf)
                            if (n <= 0) break
                            sb.append(buf, 0, n)
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                try {
                    p.destroyForcibly()
                } catch (ignored: Throwable) {
                }
                SuOut(-9, sb.toString() + "\nERR_TIMEOUT")
            } else {
                SuOut(p.exitValue(), sb.toString())
            }
        } catch (e: Throwable) {
            SuOut(-1, (e.message ?: e.toString()) + "\nERR_EXEC")
        } finally {
            try {
                proc?.destroy()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 真实探测 root：不看 su 文件在不在，直接要 uid */
    fun hasRoot(): Boolean = runSu("id", 12_000L).out.contains("uid=0")

    /**
     * 快照目录的**真实**磁盘占用，单位 KB。
     *
     * 为什么要单独算：内容相同的归档在创建时被硬链接到了上一份
     * （见 create 里的 LINK_ 逻辑），各份快照的 [Snap.size] 之和会把
     * 同一份数据重复计入 —— 三份内容一致的快照能报出 700MB，而磁盘
     * 上其实只有 230MB。列表里显示的是「这份快照包含多少数据」，
     * 这里显示的是「这些快照实际占了多少盘」，两者用途不同。
     *
     * du 对硬链接天然只计一次，所以直接问它最准；拿不到就返回 0 让调用方隐藏。
     */
    fun usageKb(): Long =
        runSu("du -sk " + ROOT + " 2>/dev/null | awk '{print \$1}'", 20_000L)
            .out.trim().toLongOrNull() ?: 0L

    // ---------------- 定时备份配置 ----------------

    private const val SP = "dsnap_auto"
    private const val K_EN = "enabled"
    private const val K_DAYS = "days"
    private const val K_HOUR = "hour"
    private const val K_TS = "last_ts"
    private const val K_OK = "last_ok"
    private const val K_MSG = "last_msg"
    private const val K_FP = "last_fp"
    private const val K_KINDS = "kinds"

    /** 定时备份配置。days / hour 都是用户显式选的，不做「智能默认」 */
    data class AutoCfg(
        val enabled: Boolean = false,
        val days: Int = 3,
        val hour: Int = 3,
        val keep: Int = 5,
        /**
         * 定时备份要备份的内容档（Kind.key 集合）。
         *
         * 以前 autoRun 写死用 Kind.defaults：用户在页面上勾/取消的档只对
         * 「立即备份」生效，定时任务永远只备默认那 4 档 —— 勾了 AOD 景深壁纸
         * 与不勾产出的快照体积一模一样，就是这么来的。勾选现在落盘到这里，
         * 定时按用户的来。
         */
        val kinds: Set<String> = Kind.defaults.map { it.key }.toSet(),
        /** 上次执行时间（无论成功失败） */
        val lastTs: Long = 0L,
        val lastOk: Boolean = true,
        val lastMsg: String = "",
        /** 上次成功备份时的内容指纹，用于跳过无变化备份 */
        val lastFp: String = "",
    ) {
        val intervalText: String get() = when (days) {
            1 -> "每天"
            7 -> "每周"
            else -> "每 $days 天"
        }

        /**
         * kinds 落成真正的枚举集合。只补前置依赖（expand），不擅自加档。
         * 空集合 = 用户主动全取消 → 定时备份跳过，而不是偷偷退回默认档
         * （那样会出现「界面一个都没勾，却备了一堆内容」）。
         * 老版本没存过 K_KINDS 的情况在 autoCfg() 里已兜底成默认档：
         * getStringSet 对「键不存在」返回 null，对「空集合」返回空集，两者可分，
         * 所以这里拿到的空集一定是用户主动取消的。
         */
        val kindSet: Set<Kind>
            get() = Kind.expand(kinds.mapNotNull { Kind.of(it) }.toSet())
    }

    fun autoCfg(ctx: Context): AutoCfg {
        val m = CamdRescue.dsnapStatus()
        val days = (m["dsnap_days"]?.toIntOrNull() ?: 3).coerceIn(1, 30)
        val hour = (m["dsnap_hour"]?.toIntOrNull() ?: 3).coerceIn(0, 23)
        val ks = (m["dsnap_kinds"] ?: "launcher theme wallpaper").split(" ", "\t").map { it.trim() }.filter { it.isNotEmpty() }.toSet().ifEmpty { setOf("launcher") }
        return AutoCfg(
            enabled = m["dsnap_enabled"] == "1",
            days = days,
            hour = hour,
            keep = (m["dsnap_keep"]?.toIntOrNull() ?: 5).coerceIn(1, 10),
            kinds = ks,
            lastTs = (m["dsnap_last_ok"]?.toLongOrNull() ?: 0L) * 1000L,
            lastOk = true,
            lastMsg = m["dsnap_last_msg"] ?: "",
            lastFp = "",
        )
    }

    fun saveAutoCfg(ctx: Context, cfg: AutoCfg) {
        CamdRescue.dsnapCfg(cfg.enabled, cfg.days, cfg.hour, cfg.keep, cfg.kinds.joinToString("+"))
    }

    private fun alignToHour(ms: Long, hour: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        c.set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * 最近一个「已经到点」的计划时刻（<= now）。
     *
     * 判断「是否该补跑」必须用这个函数。曾经的写法是 `now < nextRunAt(...)`，
     * 而 nextRunAt 的取值范围是「严格大于 now」，于是该条件恒为真、
     * runIfDue 永远提前 return —— 定时备份从未真正执行过一次。
     */
    fun lastDueAt(cfg: AutoCfg, now: Long = System.currentTimeMillis()): Long {
        val daysMs = cfg.days.coerceIn(1, 30) * 86_400_000L
        var t = alignToHour(now, cfg.hour)
        // 今天这个整点还没到，上一个计划点就是 N 天前
        if (t > now) t -= daysMs
        return t
    }

    /**
     * 下一次该跑的时间点（严格大于 now），仅用于界面展示。
     *
     * 不要拿它做「是否到期」判断（见 lastDueAt 的说明）。
     * 若存在未补跑的积压，界面应优先显示「待补跑」而不是这里的时间。
     */
    fun nextRunAt(cfg: AutoCfg, now: Long = System.currentTimeMillis()): Long {
        val daysMs = cfg.days.coerceIn(1, 30) * 86_400_000L
        var t = alignToHour(now, cfg.hour)
        if (t <= now) t += daysMs
        return t
    }

    /** 是否存在已过期但还没补跑的计划点 */
    fun overdue(cfg: AutoCfg, now: Long = System.currentTimeMillis()): Boolean =
        cfg.enabled && cfg.lastTs < lastDueAt(cfg, now)

    /** 内容指纹：主题与桌面库的 大小+文件数+目录 mtime。变了才值得再存一份 */
    fun fingerprint(): String {
        val r = runSu(
            "f(){ if [ -d \"\$1\" ]; then " +
                "printf '%s:%s:%s;' \"\$(du -sb \"\$1\" 2>/dev/null | cut -f1)\"" +
                " \"\$(find \"\$1\" -type f 2>/dev/null | wc -l)\"" +
                " \"\$(stat -c %Y \"\$1\" 2>/dev/null)\"; else printf 'x;'; fi; }\n" +
                "f /data/system/theme; f /data/user_de/0/com.miui.home/databases\n",
            45_000L,
        )
        return if (r.ok) r.out.trim() else ""
    }

    // ---------------- 新建快照 ----------------

    /**
     * 建一份快照。
     *
     * 三类内容各自独立打包，某一类失败只让该档缺失（记进 meta 的 fail=），
     * 全部失败才整体判失败。这样壁纸这种大档出问题不会把主题/桌面一起拖下水。
     *
     * @return (是否成功, 给用户看的一句话)
     */
    fun create(
        kinds: Set<Kind>,
        note: String,
        src: Src = Src.MANUAL,
        ver: String = "",
    ): Pair<Boolean, String> {
        val ok = CamdRescue.dsnapRun()
        return ok to (if (ok) "已提交备份（桌面布局）" else "发送失败：camd 未连接")
    }

    private fun hasSnap(id: String): Boolean =
        runSu("[ -e \"$ROOT/$id\" ] && echo YES", 15_000L).out.contains("YES")

    private fun newId(): String {
        val s = runSu("date +%Y%m%d_%H%M%S", 15_000L).out.trim().lines().firstOrNull()?.trim() ?: ""
        if (isValidId(s)) return s
        return java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
    }

    private fun scriptBackup(
        id: String,
        kinds: Set<Kind>,
        note: String,
        ver: String,
        src: Src,
    ): String {
        val safeNote = note.replace("'", "").replace("\n", " ").take(60)
        val safeVer = ver.replace("'", "").take(24)
        val b = StringBuilder()
        b.append("R=").append(ROOT).append('\n')
        // 清掉硬链接去重时 toybox ln -f 覆盖已存在目标留下的 <名>_XXXXXX 残留。
        // 实测：它与正式归档是同一 inode（link count 3），不占额外空间，
        // 只是把 dsnap 目录搞乱；放在打包开头做一次廉价清扫。
        b.append("find \"\$R\" -maxdepth 2 -name '*.tar.gz_*' -type f -exec rm -f {} + 2>/dev/null\n")
        b.append("D=\$R/").append(id).append('\n')
        b.append("mkdir -p \"\$D\" || { echo ERR_MKDIR; exit 3; }\n")
        b.append("OK=\"\"\nFAIL=\"\"\n")

        if (Kind.THEME in kinds) {
            // 主题与字体一起打：真实字体在 theme/fonts 下；resetFont 单独清的
            // /data/system/fonts 只有 7 KB，没有独立备份价值。
            // 一律不带 --selinux：设备 Toybox 0.8.13 的 tar 在该开关下会间歇性
            // 段错误（同一命令实测一次成功一次 exit=139，半包还会写进快照）。
            // 标签统一交给恢复时的 restorecon -R 还原。
            b.append("if tar -czf \"\$D/theme.tar.gz\" -C / data/system/theme")
            b.append(" 2>\"\$D/theme.err\"; then OK=\"\$OK theme\"; else FAIL=\"\$FAIL theme\"; fi\n")
            // 字体/主题设置项：光恢复文件不改这些 key，系统重启后仍按旧设置
            // 把字体换回去（文件在但没被选中）。
            b.append("{\n")
            b.append("  settings list secure 2>/dev/null")
            b.append(" | grep -E '^(theme_customization_overlay_packages|previous_font)='")
            b.append(" | sed 's/^/secure:/'\n")
            b.append("  settings list system 2>/dev/null")
            b.append(" | grep -E '^(font_scale|device_font_scale|is_theme_fod_animation)='")
            b.append(" | sed 's/^/system:/'\n")
            b.append("} > \"\$D/theme_settings.txt\" 2>/dev/null\n")
        }
        if (Kind.LAUNCHER in kinds) {
            // 注意：这里不能加 --selinux —— 设备自带 Toybox 0.8.13 的 tar 在
            // 「--selinux + 多条目」组合下会段错误（exit=139，只写出半包），
            // 实测单条目才安全。标签改为恢复后 restorecon -R 还原。
            b.append("LC=/data/user_de/0/com.miui.home\n")
            // 诊断先落盘：launcher 档失败时能直接看到 su 的真实身份、SELinux 上下文
            // 和 databases 目录可见性，不必再靠猜。
            // 背景：/data/system/theme_magic 是 777，任何身份都读得动；而
            // /data/user_de/0/com.miui.home 是 700（仅 u0_a146），只有真 root 或
            // app 自身 uid 才进得去。所以「theme 成功但 launcher 空」只可能是
            // 身份/权限问题，而不是通配写法问题。
            b.append("{ echo \"id=\$(id)\"; echo \"selinux=\$(cat /proc/self/attr/current 2>&1)\";")
            b.append(" ls -ld \"\$LC\" 2>&1;")
            b.append(" ls -la \"\$LC/databases\" 2>&1 | head -40;")
            b.append(" } > \"\$D/launcher.dbg\" 2>&1\n")
            b.append("LF=\"\"\n")
            // 收集改用 find（与 wallpaper 档同口径，已验证可行）：
            // 不再依赖 shell glob 的展开时机，也不受 CWD 影响。
            b.append("if [ -d \"\$LC\" ]; then\n")
            b.append("  LF=\$( { find \"\$LC/databases\" -maxdepth 1")
            b.append(" \\( -name 'launcher4x*.db' -o -name 'launcher4x*.db-wal'")
            b.append(" -o -name 'launcher4x*.db-shm' -o -name 'launcher4x*.db-journal'")
            b.append(" -o -name 'drawer' -o -name 'elderlyMan' -o -name 'miui_widget.db*'")
            b.append(" -o -name 'app_category_new.db*' -o -name 'app_status.db' \\) -print 2>/dev/null;")
            b.append(" [ -d \"\$LC/shared_prefs\" ] && echo \"\$LC/shared_prefs\";")
            b.append(" } | sed \"s|^\$LC/||\" | tr '\\n' ' ' )\n")
            b.append("fi\n")
            b.append("if [ -n \"\$LF\" ]; then\n")
            b.append("  if tar -czf \"\$D/launcher.tar.gz\" -C \"\$LC\" \$LF")
            b.append(" 2>\"\$D/launcher.err\"; then OK=\"\$OK launcher\";")
            b.append(" else FAIL=\"\$FAIL launcher\"; fi\n")
            // 外层 if 为假 = 一个文件都没找到。必须记进 FAIL，
            // 否则 meta 里 fail 为空，用户会误以为这档备份成功。
            b.append("else FAIL=\"\$FAIL launcher_empty\"; fi\n")
        }
        if (Kind.WALLPAPER in kinds) {
            // 壁纸「状态 + 静态素材」。
            // 用 tar -C / + -T 清单，包内保留 data/... 完整前缀，
            // 恢复时 tar -C / 解包就能精确落回原位，不会串目录。
            b.append("WR=/data/system/theme_magic/users/0/wallpaper\n")
            b.append("{\n")
            b.append("  for x in gallery image default dark depth_blur data")
            b.append(" super_save_power super_wallpaper maml sensor; do\n")
            b.append("    [ -e \"\$WR/\$x\" ] && find \"\$WR/\$x\" 2>/dev/null\n")
            b.append("  done\n")
            b.append("  for f in /data/system/users/0/wallpaper_info.xml")
            b.append(" /data/system/users/0/wallpaper")
            b.append(" /data/system/users/0/wallpaper_orig; do\n")
            b.append("    [ -e \"\$f\" ] && echo \"\$f\"\n")
            b.append("  done\n")
            b.append("} | sed 's|^/||' | sort -u > \"\$D/wp.lst\"\n")
            b.append("if [ -s \"\$D/wp.lst\" ]; then\n")
            b.append("  if tar -czpf \"\$D/wallpaper.tar.gz\"")
            b.append(" -C / -T \"\$D/wp.lst\" 2>\"\$D/wallpaper.err\"; then")
            b.append(" OK=\"\$OK wallpaper\";")
            b.append(" else FAIL=\"\$FAIL wallpaper\"; fi\n")
            b.append("else FAIL=\"\$FAIL wallpaper_empty\"; fi\n")
            // 壁纸类型与效果的设置项。flag_lock_wallpaper_type 直接决定锁屏走
            // 视频还是静态，必须跟 XML 的 type 字段一致，否则两边打架。
            b.append("{\n")
            b.append("  settings list secure 2>/dev/null")
            b.append(" | grep -E '^(flag_lock_wallpaper_type|wallpaper_changed_[0-9]+")
            b.append("|wallpaper_effect_type_[0-9]+|wallpaper_matting_support_[0-9]+")
            b.append("|home_wallpaper_scale_base|miui_wallpaper_content_type")
            b.append("|super_wallpaper_linkage_state|aod_using_super_wallpaper")
            b.append("|miui_15_default_lockscreen_info")
            b.append("|pref_key_wallpaper_screen_scrolled_span)='")
            b.append(" | sed 's/^/secure:/'\n")
            b.append("} > \"\$D/wp_settings.txt\" 2>/dev/null\n")
        }
        if (Kind.WP_VIDEO in kinds) {
            // 壁纸「动态视频素材」。两层抓取，缺一不可：
            // 1) 解析 home.xml / lock.xml 里所有 path* 属性按引用抓 —— 壁纸引擎
            //    只认 XML 里的路径，素材没跟上就是「type=video 但 mp4 不存在」黑屏；
            // 2) video* 目录兜底全带 —— XML 有时只引用一张缩略图，实际播放的
            //    mp4 由引擎按约定文件名拼出来，光看 XML 抓不全。
            b.append("WR=/data/system/theme_magic/users/0/wallpaper\n")
            b.append(": > \"\$D/wpv.files\"\n")
            b.append("for f in \"\$WR/data/home.xml\" \"\$WR/data/lock.xml\"; do\n")
            b.append("  [ -f \"\$f\" ] || continue\n")
            b.append("  sed 's/></>\\n</g' \"\$f\"")
            b.append(" | grep -oE 'path[0-9]*=\"[^\"]+\"'")
            b.append(" | sed 's/^[^\"]*\"//; s/\"\$//'\n")
            b.append("done > \"\$D/wp.paths\"\n")
            b.append("while IFS= read -r p; do\n")
            b.append("  [ -n \"\$p\" ] || continue\n")
            b.append("  case \"\$p\" in \"\$WR\"/*) r=\"\${p#\$WR/}\" ;; *) continue ;; esac\n")
            b.append("  [ -e \"\$WR/\$r\" ] && find \"\$WR/\$r\" 2>/dev/null\n")
            b.append("done < \"\$D/wp.paths\" >> \"\$D/wpv.files\"\n")
            b.append("for x in video video_gallery linkage_video video_depth_editor; do\n")
            b.append("  [ -e \"\$WR/\$x\" ] && find \"\$WR/\$x\" 2>/dev/null\n")
            b.append("done >> \"\$D/wpv.files\"\n")
            b.append("sed 's|^/||' \"\$D/wpv.files\" | sort -u > \"\$D/wpv.lst\"\n")
            b.append("if [ -s \"\$D/wpv.lst\" ]; then\n")
            b.append("  if GZIP=-1 tar -czpf \"\$D/wp_video.tar.gz\"")
            b.append(" -C / -T \"\$D/wpv.lst\" 2>\"\$D/wp_video.err\"; then")
            b.append(" OK=\"\$OK wp_video\";")
            b.append(" else FAIL=\"\$FAIL wp_video\"; fi\n")
            b.append("else FAIL=\"\$FAIL wp_video_empty\"; fi\n")
        }
        if (Kind.WP_AOD in kinds) {
            // AOD 景深壁纸模板当前套。内容与 wp_video 高度重复（同一个 mp4
            // 又存了一份），但 AOD 服务只认自己这份，不能省。
            b.append("AR=/data/user_de/0/com.miui.aod/files/templates/current\n")
            b.append("if [ -d \"\$AR\" ]; then\n")
            b.append("  find \"\$AR\" 2>/dev/null | sed 's|^/||' | sort -u > \"\$D/aod.lst\"\n")
            b.append("  if GZIP=-1 tar -czpf \"\$D/wp_aod.tar.gz\"")
            b.append(" -C / -T \"\$D/aod.lst\" 2>\"\$D/wp_aod.err\"; then")
            b.append(" OK=\"\$OK wp_aod\";")
            b.append(" else FAIL=\"\$FAIL wp_aod\"; fi\n")
            b.append("else FAIL=\"\$FAIL wp_aod_nodir\"; fi\n")
            // AOD 模板状态：constant_template_editor_info 里存着深度视频路径、
            // 时钟样式、壁纸矩阵参数（几 KB 的 JSON）。不恢复它 AOD 退回默认模板。
            b.append("settings list secure 2>/dev/null")
            b.append(" | grep -E '^constant_template_editor_info='")
            b.append(" | sed 's/^/secure:/'")
            b.append(" > \"\$D/aod_settings.txt\" 2>/dev/null\n")
        }

        // 全挂就没有留存意义，清掉半成品
        b.append("[ -n \"\$OK\" ] || { echo \"ERR_ALL_FAILED \$FAIL\"; rm -rf \"\$D\"; exit 4; }\n")

        // ---- 与上一份内容完全相同的归档直接硬链接，省掉重复的几百 MB ----
        // 实测：连续三份快照的 theme / wallpaper / wp_video 内容一字不差，
        // 各存一遍白白多占 250MB+（视频类素材动辄 200MB）。
        // 硬链接后每份快照照旧独立可读，meta 里的 sha 也照旧校验，
        // 只是磁盘上只保留一份真实数据；rm 掉某一份不影响其他份。
        b.append("PREV=\$(ls -1 \"\$R\" 2>/dev/null | grep -vx '").append(id).append("' | sort | tail -1)\n")
        b.append("if [ -n \"\$PREV\" ] && [ -d \"\$R/\$PREV\" ]; then\n")
        b.append("  for f in \"\$D\"/*.tar.gz; do\n")
        b.append("    [ -f \"\$f\" ] || continue\n")
        b.append("    n=\$(basename \"\$f\")\n")
        b.append("    o=\"\$R/\$PREV/\$n\"\n")
        b.append("    [ -f \"\$o\" ] || continue\n")
        b.append("    if cmp -s \"\$f\" \"\$o\"; then\n")
        b.append("      ln -f \"\$o\" \"\$f\" 2>/dev/null && echo \"LINK_\$n\"\n")
        b.append("    fi\n")
        b.append("  done\n")
        b.append("fi\n")

        b.append(": > \"\$D/meta\"\n")
        b.append("{\n")
        b.append("  printf 'ts=%s\\n' \"\$(date +%s)\"\n")
        b.append("  printf 'src=%s\\n' '").append(src.key).append("'\n")
        b.append("  printf 'note=%s\\n' '").append(safeNote).append("'\n")
        b.append("  printf 'ver=%s\\n' '").append(safeVer).append("'\n")
        b.append("  printf 'miui=%s\\n' \"\$(getprop ro.miui.ui.version.name)\"\n")
        b.append("  printf 'android=%s\\n' \"\$(getprop ro.build.version.release)\"\n")
        b.append("  printf 'fail=%s\\n' \"\$(echo \$FAIL)\"\n")
        b.append("  printf 'err=%s\\n' \"\$(cat \"\$D\"/*.err 2>/dev/null")
        b.append(" | tr '\\n' ' ' | cut -c1-160)\"\n")
        b.append("  for f in \"\$D\"/*.tar.gz; do\n")
        b.append("    [ -f \"\$f\" ] || continue\n")
        b.append("    n=\$(basename \"\$f\" .tar.gz)\n")
        b.append("    printf '%s_size=%s\\n' \"\$n\" \"\$(stat -c %s \"\$f\")\"\n")
        b.append("    printf '%s_files=%s\\n' \"\$n\" \"\$(tar -tzf \"\$f\" | wc -l)\"\n")
        b.append("    printf '%s_sha=%s\\n' \"\$n\" \"\$(sha256sum \"\$f\" | cut -d' ' -f1)\"\n")
        b.append("  done\n")
        b.append("} >> \"\$D/meta\"\n")
        b.append("rm -f \"\$D\"/*.err\n")
        b.append("echo \"SNAP_OK \$OK\"\n")
        return b.toString()
    }

    // ---------------- 列表 / 删除 / 轮转 ----------------

    /** 读全部快照，按时间倒序。meta 是 key=value，解析不依赖 JSON 转义 */
    fun list(): List<Snap> {
        fun mk(f: List<String>): Snap? {
            if (f.size < 4) return null
            val id = f[0]; if (!isValidId(id)) return null
            fun n(i: Int) = f.getOrNull(i)?.toLongOrNull() ?: 0L
            val parts = LinkedHashMap<String, Long>()
            if (n(2) > 0) parts[Kind.LAUNCHER.key] = n(2)
            if (f.size >= 7) {
                if (n(4) > 0) parts[Kind.THEME.key] = n(4)
                if (n(5) > 0) parts[Kind.WALLPAPER.key] = n(5)
                if (n(6) > 0) parts[Kind.WP_VIDEO.key] = n(6)
            }
            if (parts.isEmpty()) return null
            val ts = n(3) * 1000L
            val src = when (f[1]) { "auto" -> Src.AUTO; "pre-restore" -> Src.PRE; else -> Src.MANUAL }
            return Snap(id, ts, "", "", src, parts, emptyList())
        }
        val out = ArrayList<Snap>()
        // 1) 以真实快照目录为准（App 自己 su 备份 / camd 备份都落在 ROOT；磁盘即真相）。
        //    旧逻辑只读 dsnap_list.txt（仅 camd 收到 dsnap_list 才重建），导致 App 自己
        //    “立即备份”出来的快照不进列表 → 看起来备份失效。这里直接扫 ROOT/*/meta。
        run {
            val D = "${'$'}"
            val sh = "for x in " + ROOT + "/*/; do " +
                "[ -f \"" + D + "x/meta\" ] || continue; " +
                "id=" + D + "(basename \"" + D + "x\"); " +
                "src=" + D + "(sed -n 's/^src=//p' \"" + D + "x/meta\"|head -1); " +
                "ts=" + D + "(sed -n 's/^ts=//p' \"" + D + "x/meta\"|head -1); " +
                "th=" + D + "(sed -n 's/^theme_size=//p' \"" + D + "x/meta\"|head -1); " +
                "wp=" + D + "(sed -n 's/^wp_size=//p' \"" + D + "x/meta\"|head -1); " +
                "wpv=" + D + "(sed -n 's/^wpv_size=//p' \"" + D + "x/meta\"|head -1); " +
                "lsz=" + D + "(stat -c %s \"" + D + "x/launcher.db\" 2>/dev/null); " +
                "echo \"" + D + "id|" + D + "src|" + D + "{lsz:-0}|" + D + "ts|" + D + "{th:-0}|" + D + "{wp:-0}|" + D + "{wpv:-0}\"; " +
                "done"
            val txt = try { runSu(sh, 25_000L).out } catch (t: Throwable) { "" }
            for (line in txt.split("\n")) {
                val t = line.trim(); if (t.isEmpty() || !t.contains('|')) continue
                mk(t.split('|'))?.let { out.add(it) }
            }
        }
        if (out.isNotEmpty()) return out.sortedByDescending { it.at }
        // 2) 回退：status 的 dsnap_list / files/dsnap_list.txt
        val st = CamdRescue.dsnapStatus()["dsnap_list"] ?: ""
        for (e in st.split(";")) { if (e.isEmpty()) continue; mk(e.split(","))?.let { out.add(it) } }
        if (out.isEmpty()) {
            for (line in CamdRescue.dsnapListRaw().split("\n")) { val t = line.trim(); if (t.isEmpty()) continue; mk(t.split('|'))?.let { out.add(it) } }
        }
        return out.sortedByDescending { it.at }
    }

    fun delete(id: String): Boolean = CamdRescue.dsnapDel(id)

    /**
     * 只留 [limit] 份。淘汰顺序 auto → pre-restore → manual，同类里先淘汰最旧的。
     * 也就是定时任务永远挤不掉用户手动存的那份。
     * @return 实际删掉几份
     */
    fun prune(limit: Int = KEEP): Int {
        val all = list()
        if (all.size <= limit) return 0
        var need = all.size - limit
        val victims = mutableListOf<String>()
        for (order in listOf(Src.AUTO, Src.PRE, Src.MANUAL)) {
            if (need <= 0) break
            for (s in all.filter { it.src == order && it.id !in victims }.sortedBy { it.at }) {
                if (need <= 0) break
                victims += s.id
                need--
            }
        }
        if (victims.isEmpty()) return 0
        val script = buildString {
            append("R=").append(ROOT).append('\n')
            for (v in victims) append("rm -rf \"\$R/").append(v).append("\"\n")
            append("echo PRUNE_OK\n")
        }
        runSu(script, 60_000L)
        Log.i(TAG, "prune removed=" + victims.joinToString(","))
        return victims.size
    }

    // ---------------- 恢复 ----------------

    /**
     * 恢复到指定快照。
     *
     * 调用方注意：恢复是「先删目标目录、再解包」，中途失败会留下半套数据。
     * 所以入口 UI 会先自动打一份 src=pre-restore 的快照再走这里。
     * 脚本自身也会对每个要恢复的档做 sha256 + 归档可读性预检，
     * 预检不过直接退出，一个字节都不删。
     *
     * 恢复后的进程处置：
     *   launcher  -> kill -9 桌面（不能用 am force-stop：force-stop 会把包标记成
     *                stopped，系统不会自动拉起，桌面就再也回不来）
     *   theme/壁纸 -> kill systemui（SIGTERM，让 system_server 重启它并重新加载主题资源）
     */
    fun restore(id: String, kinds: Set<Kind>): Pair<Boolean, String> {
        val ok = CamdRescue.dsnapRestore(id)
        return ok to (if (ok) "已提交恢复，桌面将自动重启" else "发送失败：camd 未连接")
    }

    private fun scriptRestore(id: String, kinds: Set<Kind>): String {
        val b = StringBuilder()
        b.append("R=").append(ROOT).append('\n')
        b.append("D=\$R/").append(id).append('\n')
        b.append("[ -d \"\$D\" ] || { echo ERR_NO_SNAP; exit 10; }\n")
        b.append("vfy(){\n")
        b.append("  f=\"\$D/\$1.tar.gz\"\n")
        b.append("  [ -f \"\$f\" ] || return 1\n")
        b.append("  exp=\$(grep \"^\$1_sha=\" \"\$D/meta\" 2>/dev/null | cut -d= -f2)\n")
        b.append("  got=\$(sha256sum \"\$f\" 2>/dev/null | cut -d' ' -f1)\n")
        b.append("  [ -z \"\$exp\" ] || [ \"\$exp\" = \"\$got\" ] || return 1\n")
        b.append("  tar -tzf \"\$f\" >/dev/null 2>&1 || return 1\n")
        b.append("  return 0\n")
        b.append("}\n")

        // settings 回写：备份时按「ns:key=value」一行一条存，这里逐行 put 回去。
        // 值里可能含 = : 空格 { }，所以用 %% 和 # 做最小切分，不能用 cut。
        fun applySettings(file: String, indent: String = "  ") {
            b.append(indent).append("if [ -f \"\$D/").append(file).append("\" ]; then\n")
            b.append(indent).append("  while IFS= read -r ln; do\n")
            b.append(indent).append("    ns=\${ln%%:*}; kv=\${ln#*:}\n")
            b.append(indent).append("    k=\${kv%%=*}; v=\${kv#*=}\n")
            b.append(indent).append("    [ -n \"\$k\" ] && [ -n \"\$v\" ]")
            b.append(" && settings put \"\$ns\" \"\$k\" \"\$v\" 2>/dev/null\n")
            b.append(indent).append("  done < \"\$D/").append(file).append("\"\n")
            b.append(indent).append("fi\n")
        }

        // ---- 预检：只看不动 ----
        if (Kind.THEME in kinds) {
            b.append("[ -f \"\$D/theme.tar.gz\" ] && { vfy theme")
            b.append(" || { echo ERR_VERIFY_THEME; exit 11; }; }\n")
        }
        if (Kind.LAUNCHER in kinds) {
            b.append("[ -f \"\$D/launcher.tar.gz\" ] && { vfy launcher")
            b.append(" || { echo ERR_VERIFY_LAUNCHER; exit 12; }; }\n")
        }
        if (Kind.WALLPAPER in kinds) {
            b.append("[ -f \"\$D/wallpaper.tar.gz\" ] && { vfy wallpaper")
            b.append(" || { echo ERR_VERIFY_WALLPAPER; exit 13; }; }\n")
        }
        if (Kind.WP_VIDEO in kinds) {
            b.append("[ -f \"\$D/wp_video.tar.gz\" ] && { vfy wp_video")
            b.append(" || { echo ERR_VERIFY_WP_VIDEO; exit 14; }; }\n")
        }
        if (Kind.WP_AOD in kinds) {
            b.append("[ -f \"\$D/wp_aod.tar.gz\" ] && { vfy wp_aod")
            b.append(" || { echo ERR_VERIFY_WP_AOD; exit 15; }; }\n")
        }

        // ---- 动手 ----
        if (Kind.THEME in kinds) {
            b.append("if [ -f \"\$D/theme.tar.gz\" ]; then\n")
            b.append("  rm -rf /data/system/theme\n")
            b.append("  tar -xzpf \"\$D/theme.tar.gz\" -C / || { echo ERR_UNPACK_THEME; exit 21; }\n")
            b.append("  restorecon -R /data/system/theme >/dev/null 2>&1\n")
            applySettings("theme_settings.txt")
            b.append("fi\n")
        }
        if (Kind.LAUNCHER in kinds) {
            b.append("if [ -f \"\$D/launcher.tar.gz\" ]; then\n")
            b.append("  L=/data/user_de/0/com.miui.home\n")
            // 不能整体 rm -rf databases：目录里还有 onetrack_cache.db、
            // download_install_info.txt 等不在白名单里的文件，删了会一起丢。
            //
            // 顺序极其关键。早期版本是「按清单 rm -> 解包覆盖 -> 再 kill」，
            // 结果恢复后布局一点没变：桌面进程当时还活着，手里握着旧库的 fd
            // 和 mmap，我们删文件/覆盖文件它都感觉不到，反而在它下一次落盘时
            // 把内存里的旧布局写回新库上，最后 kill 掉重读读到的还是旧的。
            // 所以改成：解到 staging -> 杀掉并确认死透 -> rename 原子替换 -> 再补杀。
            b.append("  ST=\"\$L/.camellia_stage\"\n")
            b.append("  rm -rf \"\$ST\"; mkdir -p \"\$ST\"\n")
            b.append("  tar -xzpf \"\$D/launcher.tar.gz\" -C \"\$ST\"")
            b.append(" || { rm -rf \"\$ST\"; echo ERR_UNPACK_LAUNCHER; exit 22; }\n")
            b.append("  tar -tzf \"\$D/launcher.tar.gz\" > \"\$D/l.lst\" 2>/dev/null\n")
            // 停桌面并等 pid 真的消失。桌面有守护会自动重启，这里只保证
            // 「替换那一刻没有活着的桌面持有旧库」。
            b.append("  kh(){ p=\$(pidof com.miui.home 2>/dev/null);")
            b.append(" [ -z \"\$p\" ] && return 0; kill -9 \$p 2>/dev/null; i=0;")
            b.append(" while [ -n \"\$(pidof com.miui.home 2>/dev/null)\" ] && [ \$i -lt 60 ];")
            b.append(" do sleep 0.05; i=\$((i+1)); done; return 0; }\n")
            b.append("  kh\n")
            b.append("  while IFS= read -r f; do\n")
            b.append("    [ -n \"\$f\" ] || continue\n")
            b.append("    case \"\$f\" in */) continue;; esac\n")
            // -shm 是进程共享内存的索引，带旧 salt 会让 SQLite 误判，交给它自建
            b.append("    case \"\$f\" in *-shm) continue;; esac\n")
            b.append("    [ -e \"\$ST/\$f\" ] || continue\n")
            b.append("    mkdir -p \"\$(dirname \"\$L/\$f\")\"\n")
            // 同分区 mv 就是 rename(2)：原子替换，不会出现「删了还没解」的空窗
            b.append("    if ! mv -f \"\$ST/\$f\" \"\$L/\$f\" 2>/dev/null; then\n")
            b.append("      rm -rf \"\$L/\$f\"; cp -f \"\$ST/\$f\" \"\$L/\$f\"\n")
            b.append("    fi\n")
            b.append("  done < \"\$D/l.lst\"\n")
            b.append("  rm -rf \"\$ST\"\n")
            // 替换期间系统可能已把桌面拉起并加载了旧库，再杀一次逼它重读
            b.append("  kh\n")
            b.append("  restorecon -R \"\$L/databases\" >/dev/null 2>&1\n")
            b.append("fi\n")
        }
        if (Kind.WALLPAPER in kinds) {
            b.append("if [ -f \"\$D/wallpaper.tar.gz\" ]; then\n")
            b.append("  W=/data/system/theme_magic/users/0/wallpaper\n")
            b.append("  mkdir -p \"\$W\"\n")
            // 包内是 data/system/... 全路径，所以按清单逐条删（不能 rm -rf 整个
            // W，否则 video/ 里不属于本档的素材会被连坐删掉）
            b.append("  tar -tzf \"\$D/wallpaper.tar.gz\" > \"\$D/w.lst\" 2>/dev/null\n")
            b.append("  while IFS= read -r f; do [ -n \"\$f\" ] && rm -rf \"/\$f\"; done < \"\$D/w.lst\"\n")
            b.append("  tar -xzpf \"\$D/wallpaper.tar.gz\" -C /")
            b.append(" || { echo ERR_UNPACK_WALLPAPER; exit 23; }\n")
            b.append("  restorecon -R \"\$W\" >/dev/null 2>&1\n")
            b.append("  restorecon /data/system/users/0/wallpaper_info.xml >/dev/null 2>&1\n")
            applySettings("wp_settings.txt")
            b.append("fi\n")
        }
        if (Kind.WP_VIDEO in kinds) {
            b.append("if [ -f \"\$D/wp_video.tar.gz\" ]; then\n")
            b.append("  W=/data/system/theme_magic/users/0/wallpaper\n")
            b.append("  mkdir -p \"\$W/video\"\n")
            b.append("  tar -tzf \"\$D/wp_video.tar.gz\" > \"\$D/wv.lst\" 2>/dev/null\n")
            b.append("  while IFS= read -r f; do [ -n \"\$f\" ] && rm -rf \"/\$f\"; done < \"\$D/wv.lst\"\n")
            b.append("  tar -xzpf \"\$D/wp_video.tar.gz\" -C /")
            b.append(" || { echo ERR_UNPACK_WP_VIDEO; exit 24; }\n")
            b.append("  restorecon -R \"\$W/video\" >/dev/null 2>&1\n")
            b.append("fi\n")
        }
        if (Kind.WP_AOD in kinds) {
            b.append("if [ -f \"\$D/wp_aod.tar.gz\" ]; then\n")
            b.append("  A=/data/user_de/0/com.miui.aod/files/templates/current\n")
            b.append("  mkdir -p \"\$A\"\n")
            b.append("  tar -tzf \"\$D/wp_aod.tar.gz\" > \"\$D/wa.lst\" 2>/dev/null\n")
            b.append("  while IFS= read -r f; do [ -n \"\$f\" ] && rm -rf \"/\$f\"; done < \"\$D/wa.lst\"\n")
            b.append("  tar -xzpf \"\$D/wp_aod.tar.gz\" -C /")
            b.append(" || { echo ERR_UNPACK_WP_AOD; exit 25; }\n")
            b.append("  restorecon -R \"\$A\" >/dev/null 2>&1\n")
            applySettings("aod_settings.txt")
            b.append("fi\n")
        }

        // ---- 壁纸自愈：XML 指向的素材必须真实存在 ----
        // 这是「恢复后黑屏」的唯一根因：XML 写了 type="video"、path0 指向某个
        // mp4，但那个 mp4 没进快照（或用户只恢复了 wallpaper 没恢复 wp_video）。
        // 不能把烂摊子留给用户，就地修：有静态图就降级成静态，有视频就升回
        // 视频，两边都没有才报 ERR_WP_ORPHAN。
        if (Kind.WALLPAPER in kinds || Kind.WP_VIDEO in kinds) {
            b.append("W=/data/system/theme_magic/users/0/wallpaper\n")
            b.append("for n in home lock; do\n")
            b.append("  X=\"\$W/data/\$n.xml\"\n")
            b.append("  [ -f \"\$X\" ] || continue\n")
            b.append("  p0=\$(sed 's/></>\\n</g' \"\$X\"")
            b.append(" | grep -oE 'path0=\"[^\"]+\"' | head -1")
            b.append(" | sed 's/.*=\"//; s/\"\$//')\n")
            b.append("  [ -n \"\$p0\" ] || continue\n")
            b.append("  [ -f \"\$p0\" ] && continue\n")
            b.append("  alt=\"\"\n")
            b.append("  for c in \"\$W/image/\${n}_wallpaper.jpg\"")
            b.append(" \"\$W/image/\${n}_wallpaper.png\"")
            b.append(" \"\$W/gallery/\${n}_wallpaper.jpg\"")
            b.append(" \"\$W/default/\${n}_wallpaper.jpg\"; do\n")
            b.append("    [ -f \"\$c\" ] && { alt=\"\$c\"; break; }\n")
            b.append("  done\n")
            b.append("  if [ -n \"\$alt\" ]; then\n")
            b.append("    sed -i 's/type=\"video\"/type=\"image\"/g' \"\$X\"\n")
            b.append("    sed -i 's/last_type=\"video\"/last_type=\"image\"/g' \"\$X\"\n")
            b.append("    sed -i \"s|path0=\\\"[^\\\"]*\\\"|path0=\\\"\$alt\\\"|\" \"\$X\"\n")
            b.append("    echo \"WARN_WP_DOWNGRADE_\$n\"\n")
            b.append("  else\n")
            b.append("    v=\"\"\n")
            b.append("    for c in \"\$W/video/\${n}_wallpaper_video.mp4\"")
            b.append(" \"\$W/video/home_wallpaper_video.mp4\"; do\n")
            b.append("      [ -f \"\$c\" ] && { v=\"\$c\"; break; }\n")
            b.append("    done\n")
            b.append("    if [ -n \"\$v\" ]; then\n")
            b.append("      sed -i 's/type=\"image\"/type=\"video\"/g' \"\$X\"\n")
            b.append("      sed -i 's/last_type=\"image\"/last_type=\"video\"/g' \"\$X\"\n")
            b.append("      sed -i \"s|path0=\\\"[^\\\"]*\\\"|path0=\\\"\$v\\\"|\" \"\$X\"\n")
            b.append("      echo \"WARN_WP_UPGRADE_\$n\"\n")
            b.append("    else\n")
            b.append("      echo \"ERR_WP_ORPHAN_\$n\"\n")
            b.append("    fi\n")
            b.append("  fi\n")
            // 锁屏壁纸类型必须跟最终 XML 一致：XML 已降级成 image、settings
            // 还写着 video 的话，锁屏会去找不存在的视频 —— 又是一次黑屏。
            // 所以放在自愈之后，以 XML 的实际值为准覆盖 settings。
            b.append("t=\$(sed 's/></>\\n</g' \"\$W/data/lock.xml\" 2>/dev/null")
            b.append(" | grep -oE 'type=\"[^\"]+\"' | head -1")
            b.append(" | sed 's/.*=\"//; s/\"\$//')\n")
            b.append("[ -n \"\$t\" ]")
            b.append(" && settings put secure flag_lock_wallpaper_type \"\$t\"\n")
            b.append("done\n")
        }

        // ---- 让改动生效 ----
        if (Kind.LAUNCHER in kinds) {
            b.append("if [ -f \"\$D/launcher.tar.gz\" ]; then\n")
            b.append("  p=\$(pidof com.miui.home)\n")
            b.append("  [ -n \"\$p\" ] && kill -9 \$p\n")
            b.append("fi\n")
        }
        if (Kind.WALLPAPER in kinds || Kind.WP_VIDEO in kinds || Kind.WP_AOD in kinds) {
            // miwallpaper 是壁纸引擎，不重启它永远读缓存里的旧 XML —— 这正是
            // 「手改 XML 没反应」的原因。必须 -9，普通 kill 会被它自己忽略。
            b.append("if [ -f \"\$D/wallpaper.tar.gz\" ]")
            b.append(" || [ -f \"\$D/wp_video.tar.gz\" ]")
            b.append(" || [ -f \"\$D/wp_aod.tar.gz\" ]; then\n")
            b.append("  for s in com.miui.miwallpaper com.miui.aod; do\n")
            b.append("    p=\$(pidof \$s); [ -n \"\$p\" ] && kill -9 \$p\n")
            b.append("  done\n")
            b.append("  p=\$(pidof com.android.systemui); [ -n \"\$p\" ] && kill \$p\n")
            b.append("fi\n")
        }
        if (Kind.THEME in kinds) {
            b.append("if [ -f \"\$D/theme.tar.gz\" ]; then\n")
            b.append("  p=\$(pidof com.android.systemui); [ -n \"\$p\" ] && kill \$p\n")
            b.append("fi\n")
        }

        b.append("echo RESTORE_OK\n")
        return b.toString()
    }

    // ---------------- 定时执行 ----------------

    /**
     * 跑一次定时备份。内容指纹与上次成功备份一致时直接跳过，不浪费快照额度。
     * 无论成败都回写 lastTs / lastOk / lastMsg，UI 直接读来展示。
     */
    fun autoRun(ctx: Context, reason: String = "定时备份"): Pair<Boolean, String> {
        val cfg = autoCfg(ctx)
        val now = System.currentTimeMillis()
        val kinds = cfg.kindSet
        if (kinds.isEmpty()) {
            saveAutoCfg(ctx, cfg.copy(lastTs = now, lastOk = false, lastMsg = "未选择备份内容，已跳过"))
            Log.i(TAG, "autoRun skipped: no kind selected")
            return false to "未选择备份内容，已跳过"
        }
        val rawFp = fingerprint()
        // 指纹里必须带上内容档：用户改了勾选就等于「内容变了」，
        // 否则会被下面的「无变化跳过」挡住，勾上 AOD 也永远不备。
        val sig = rawFp + "|" + kinds.map { it.key }.sorted().joinToString(",")
        if (cfg.lastOk && cfg.lastTs > 0L && rawFp.isNotEmpty() && sig == cfg.lastFp) {
            saveAutoCfg(ctx, cfg.copy(lastTs = now, lastOk = true, lastMsg = "内容无变化，已跳过"))
            Log.i(TAG, "autoRun skipped: unchanged")
            return true to "内容无变化，已跳过"
        }
        val (ok, msg) = create(kinds, reason, Src.AUTO)
        saveAutoCfg(
            ctx,
            cfg.copy(
                lastTs = now,
                lastOk = ok,
                lastMsg = msg,
                lastFp = if (ok) sig else cfg.lastFp,
            ),
        )
        Log.i(TAG, "autoRun ok=$ok msg=$msg kinds=${kinds.map { it.key }}")
        return ok to msg
    }

    /** 打开设置页 / 开机 / App 启动时补跑：设备关机或进程没起来时错过的计划点 */
    fun runIfDue(ctx: Context): Pair<Boolean, String>? {
        val cfg = autoCfg(ctx)
        if (!cfg.enabled) return null
        val now = System.currentTimeMillis()
        // 上一次执行已经落在最近一个计划点之后 → 这一轮已经做过，不必再跑
        if (cfg.lastTs >= lastDueAt(cfg, now)) return null
        return autoRun(ctx, "定时备份（补跑）")
    }
}
