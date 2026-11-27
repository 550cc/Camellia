package com.hyper.Camellia;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * camd（root 守护进程）的控制面板通道。
 *
 * 定位：camd 的救援逻辑（DE 阶段保命、坏包自愈、系统自杀屏蔽）全部跑在 root 侧，
 * 与本类的分工是「它动手，我配置 + 读回结果」。本类不含任何救援决策代码 ——
 * 曾经这里并列着一套 Java 侧分级救援（SysRescueGuard + SysUiWatch + SnapshotStore），
 * 与 camd 抢同一件事：谁先发现崩溃谁动手，结论互相覆盖，日志互相淹没，
 * 还会出现「Java 侧判正常、camd 判崩溃」的对峙。已整体删除，只留面板。
 *
 * 【三层通道：socket 优先，文件兜底】
 *   ① socket    /data/data/<pkg>/files/camd.sock（见 CamdSocket + camellia_sock.c）
 *               长连接双向：命令直写、状态主动推，两端都不轮询、不 fork su。
 *               首选，但依赖 SELinux 放行 untrusted_app -> camd 域的 connectto。
 *   ② ext4 快通道  /data/adb/camellia/cmd|caps
 *               有 root 时用 su 写，camd 的 inotify 立刻唤醒（毫秒级、零轮询）。
 *               socket 不可用时的第一兜底。
 *   ③ FUSE 慢通道  /sdcard/Android/data/.../files
 *               无 root 时的最终兜底：camd 每秒轮询取指令，App 读文件取状态。
 *
 * 【为什么文件通道仍然保留】（降级路径，勿删除）
 * 本类运行在 App 进程（uid=u0_aXXX），既没有 root 也没有 system uid：
 *   - /data/adb/camellia            打不开（test -x 必然失败）
 *   - /data/system/camellia_action  打不开（isDirectory() 直接 false）
 * 所以 App 与 camd 之间用外部私有目录做文件 IPC：
 *
 *   /sdcard/Android/data/com.hyper.Camellia/files/camd_status.txt
 *       camd 周期写（内容变化 + 5s 节流，tmp+rename 原子提交），App 只读；
 *   .../camd_cmd
 *       App 写 key=value，camd 每秒轮询、应用后立即 unlink（只执行一次）；
 *   .../camd_snap.txt
 *       快照索引，camd 代读 /data/adb/camellia/bak 后落盘（App 读不了 bak）；
 *   监控包固定在 C 侧（FIXED_TARGET = SystemUI + 插件包），不再有候选包文件：
 *   多包配置的 CSV 上限与逐包 pm path 校验都曾静默失效并阻塞主循环。
 *
 * 该目录在 Android 11+ 受 scoped storage 保护，第三方应用无权写入；
 * 处置动作只删 /data/app 覆盖层、系统底包原样保留，所以最坏结果是回滚到出厂
 * 版本，不会把系统应用删没——这也是监控包写死成 SystemUI + 插件包的前提。
 */
final class CamdRescue {

    private CamdRescue() {}

    // -------------------------------------------------- root 执行器 / 能力握手

    /**
     * 执行 `su -c <cmd>`，返回退出码（超时 / 无 su / 异常一律 -1）。
     *
     * waitFor 必须带超时：用户不去点 KernelSU 授权框时 su 会永久挂起，
     * 调用线程跟着死锁。这是本项目真实踩过的坑。
     */
    static int runSuExit(String cmd, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return -1;
            }
            // 排空输出：本类写进去的命令输出都很短，waitFor 之后读不会阻塞
            try {
                p.getInputStream().read();
            } catch (Throwable ignored) {
            }
            return p.exitValue();
        } catch (Throwable t) {
            return -1;
        } finally {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 向 camd 声明「本 App 有 root，请降频轮询」。
     *
     * 写 /data/adb/camellia/caps（camd 侧 600 权限目录，只有 root 能写），
     * 内容 app_root=1。写成功后本进程改用快通道下发指令。
     * **必须在 IO 线程调用**（fork su）。
     */
    static boolean syncCap(Context ctx) {
        int vc = 0;
        try {
            vc = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable ignored) {
        }
        String body = "app_root=1\napp_ver=" + vc + "\nts=" + (System.currentTimeMillis() / 1000L) + "\n";
        boolean ok = writeDirFile(CAPS, body, 8000);
        fastChan = ok;
        return ok;
    }

    /** 撤销能力声明（root 掉了 / 用户关掉快通道）：camd 读到 app_root=0 会回到 1s 轮询。 */
    static void clearCap(Context ctx) {
        fastChan = false;
        writeDirFile(CAPS, "app_root=0\nts=" + (System.currentTimeMillis() / 1000L) + "\n", 8000);
    }

    /** 用 root 原子写 camd 目录下的某个文件（b64 传输，避开 shell 转义问题）。 */
    private static boolean writeDirFile(String path, String body, long timeoutMs) {
        try {
            String b64 = Base64.encodeToString(body.getBytes("UTF-8"), Base64.NO_WRAP);
            String cmd = "mkdir -p " + DIR
                    + " && echo '" + b64 + "' | base64 -d > " + path + ".tmp"
                    + " && mv -f " + path + ".tmp " + path
                    + " && chmod 600 " + path;
            return runSuExit(cmd, timeoutMs) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** camd 侧目录（App 不可读，仅用于文案/诊断提示）。 */
    static final String DIR = "/data/adb/camellia";
    static final String BIN = DIR + "/camd";
    static final String CFG = DIR + "/config";

    /** App ↔ camd 文件通道：App 自己的外部私有目录，无需任何权限。 */
    static final String UI_DIR    = "/sdcard/Android/data/com.hyper.Camellia/files";
    static final String UI_STATUS = UI_DIR + "/camd_status.txt";
    static final String UI_CMD    = UI_DIR + "/camd_cmd";
    static final String UI_SNAP   = UI_DIR + "/camd_snap.txt";
    static final String UI_BUSY   = UI_DIR + "/camd_busy";

    /*
     * 快通道（只有 App 确实拿到 root 时才启用）。
     *
     * 背景：走 UI_DIR 的 FUSE 通道时，camd 必须每秒 fopen(camd_cmd) 才能做到
     * 「指令延迟 ≤1s」，代价是 86400 次/天的跨进程往返；而 99.99% 的轮询只是在
     * 确认「没有新指令」。FUSE 上 inotify 不可靠，这个开销没法从通道侧消掉。
     *
     * 有 root 后换成 ext4 上的 /data/adb/camellia：
     *   caps  App 声明「我有 root 且支持快通道」（camd 读它决定是否降频）
     *   cmd   App 直接写，camd 的 inotify 立刻唤醒 —— 零轮询、延迟更低
     * camd 侧仍保留 60s 一次的 FUSE 兜底，覆盖「用户中途撤销 root」的降级场景。
     */
    static final String CAPS     = DIR + "/caps";
    static final String CMD_FAST = DIR + "/cmd";

    /** 快通道可用标记：RootState 探测到 root 并成功写下 caps 后置 true。 */
    private static volatile boolean fastChan = false;

    static boolean isFastChan() {
        return fastChan;
    }

    /** 状态快照新鲜度上限：camd 5s 节流写，超过这个时长就认为它没在跑。 */
    /**
     * camd_status.txt 的 ts 新鲜度阈值。
     *
     * <p>camd 侧只在业务内容变化时立即重写状态文件，其余情况按
     * UI_BEAT_SEC=45s 写一次心跳（性能优化：避免每 5s 无条件写 FUSE）。
     * 本阈值必须显著大于该心跳间隔，否则正常运行的 camd 会被误判成
     * 「进程未运行」—— 取 4 倍余量。</p>
     */
    private static final long FRESH_SEC = 180L;

    /** 与 C 侧 cfg_defaults() 严格对齐。键缺失时 UI 显示这里的值，
     *  两边一旦不一致，就会出现「UI 显示 180s，camd 实际按 90s 跑」的错位。 */
    static final String DEFAULT_CFG =
            "auto_fix = 0\n"
            + "tick_sec = 30\n"
            + "target = " + Prefs.PKG_SYSUI + ",miui.systemui.plugin\n"
            + "bak_keep = 2\n"
            + "sr_max = 2\n"
            + "nofx_max = 2\n"
            + "stable_sec = 600\n"
            + "shield = 1\n"
            + "watch_install = 1\n"
            + "ebpf = 1\n"
            + "a_window_sec = 180\n"
            + "a_circ_thr = 3\n"
            + "bak_dir = " + DIR + "/bak\n"
            + "reboot_cmd = killall system_server\n";

    // -------------------------------------------------------- 状态快照（读）

    private static String readText(String path) {
        try {
            File f = new File(path);
            if (!f.isFile()) return "";
            long len = f.length();
            if (len <= 0 || len > 65536) return "";
            byte[] b = new byte[(int) len];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) {
                    off += n;
                }
            } finally {
                in.close();
            }
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 解析为有序键值（保留 camd 侧新增的未知键，便于将来扩展）。 */
    private static Map<String, String> parse(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        if (text == null) return m;
        for (String line : text.split("\n")) {
            String tr = line.trim();
            if (tr.isEmpty() || tr.startsWith("#")) continue;
            int eq = tr.indexOf('=');
            if (eq <= 0) continue;
            m.put(tr.substring(0, eq).trim(), tr.substring(eq + 1).trim());
        }
        return m;
    }

    private static long num(String v) {
        try {
            return Long.parseLong(v == null ? "" : v.trim());
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** camd 的状态快照原文（每行 key=value）；读不到返回空串。
     *  socket 在线时直接用推送来的最新快照（不碰 FUSE），否则读文件兜底。 */
    static String statusText() {
        String live = CamdSocket.latest();
        if (live != null) return live;
        return readText(UI_STATUS);
    }

    /** 上一次“有效”的状态（含 ver/pid）；用于扛住 FUSE 读空 / 坏帧导致的整页闪空。 */
    private static volatile String sLastStatus = null;

    static Map<String, String> status() {
        String txt = statusText();
        Map<String, String> m = parse(txt);
        // 读空或坏帧（缺 ver）时沿用上一次有效状态，避免面板一拍变“未部署/开关全关”
        if (m.isEmpty() || !m.containsKey("ver")) {
            String last = sLastStatus;
            if (last != null) { Map<String, String> l = parse(last); if (!l.isEmpty()) return l; }
            return m;
        }
        sLastStatus = txt;
        return m;
    }

    /**
     * camd 进程已运行秒数 —— 纯 App 侧观测，camd 一行代码都不改。
     *
     * <p>camd_status.txt 里的 {@code uptime} 是<b>系统开机时长</b>，不是 camd 自己的
     * 运行时长。直接拿它显示「运行时长」，会让「重新部署后 camd 已重启」看起来毫无
     * 变化（pid 换了、时长还显示 2 天），用户会以为重新部署没生效。</p>
     *
     * <p>这里改为一律由 App 记录「第一次看到这个 pid 的时刻」，pid 变化（camd 重启）
     * 即重新计时。不读 /proc、不需要 root、不增加 camd 的任何常驻开销 —— 只在面板
     * 渲染时读写一次内存缓存的 SharedPreferences。</p>
     *
     * <p>面板只显示到「分」，所以这里按分钟缓存结果：同一 pid、同一分钟内直接返回
     * 上次的值，连那一次 SP 读都省掉。</p>
     *
     * @return 运行秒数；无法确定（首次观测到该 pid）返回 -1，由 UI 显示 "—"
     */
    static long procAgeSec(Context ctx, String pid) {
        if (ctx == null || pid == null) return -1L;
        String p = pid.trim();
        if (p.isEmpty() || "0".equals(p)) return -1L;

        long now = System.currentTimeMillis();
        long minute = now / 60000L;
        if (p.equals(sAgePid) && minute == sAgeMinute) return sAgeSec;

        long sec = -1L;
        try {
            SharedPreferences sp = ctx.getSharedPreferences("camellia_obs", Context.MODE_PRIVATE);
            String v = sp.getString("camd_pid_birth", "");
            if (v != null && v.length() > p.length() + 1 && v.startsWith(p + ":")) {
                long t0 = Long.parseLong(v.substring(p.length() + 1));
                if (t0 > 0 && now >= t0) sec = (now - t0) / 1000L;
            } else {
                sp.edit().putString("camd_pid_birth", p + ":" + now).apply();
            }
        } catch (Throwable t) {
            return -1L;
        }
        sAgePid = p;
        sAgeMinute = minute;
        sAgeSec = sec;
        return sec;
    }

    /** procAgeSec 的分钟级缓存（pid + 分钟数 + 结果），避免面板 2 秒一次重绘都去读 SP。 */
    private static String sAgePid = "";
    private static long sAgeMinute = Long.MIN_VALUE;
    private static long sAgeSec = -1L;

    /**
     * 是否已部署且在跑：快照存在、pid>0、且快照足够新鲜。
     * 三条同时满足才算健康 —— 只看文件存在会把「camd 已死但残留快照」误判成正常。
     */
    static boolean deployed() {
        if (CamdSocket.connected() || hasBusy()) return true;
        Map<String, String> m = status();
        if (m.isEmpty()) return false;
        long ts = num(m.get("ts"));
        if (ts <= 0) return false;
        long age = System.currentTimeMillis() / 1000L - ts;
        return age >= -60L && age <= FRESH_SEC;
    }

    static boolean isAlive() {
        return num(status().get("pid")) > 0;
    }

    static String pidText() {
        long p = num(status().get("pid"));
        return p > 0 ? String.valueOf(p) : "-";
    }

    /** camd 自报版本（编译期由 build_camd.sh 从 build.gradle 注入）。 */
    static String ver() {
        String v = status().get("ver");
        return v == null || v.isEmpty() ? "-" : v;
    }

    /** config 文件是否存在且非空：区分「首次运行自动生成」与「用户改过」。 */
    static boolean cfgExists() {
        return "1".equals(status().get("cfg_ok"));
    }

    /** 只读诊断：直接把快照暴露给 UI（不再需要 fork --diagnose）。 */
    static String diagnoseText() {
        return statusText();
    }

    // -------------------------------------------------------- 配置读（快照）

    private static String configDefaults(String key) {
        String v = parse(DEFAULT_CFG).get(key);
        return v == null ? "" : v;
    }

    static String configValue(String key) {
        String v = status().get(key);
        return v == null ? configDefaults(key) : v;
    }

    static boolean flag(String key) {
        return "1".equals(configValue(key));
    }

    static int intValue(String key, int def) {
        long v = num(configValue(key));
        return v == -1 ? def : (int) v;
    }

    static boolean autoFix() {
        return "1".equals(configValue("auto_fix"));
    }

    static boolean shield() {
        return "1".equals(configValue("shield"));
    }

    static boolean watchInstall() {
        return "1".equals(configValue("watch_install"));
    }

    static int tickSec()    { return intValue("tick_sec", 30); }
    static int srMax()      { return intValue("sr_max", 2); }
    static int nofxMax()    { return intValue("nofx_max", 2); }
    static int bakKeep()    { return intValue("bak_keep", 2); }
    static long stableSec() { return num(configValue("stable_sec")); }
    static int aWindowSec() { return intValue("a_window_sec", 180); }
    static int aCircThr()   { return intValue("a_circ_thr", 3); }

    /** target 原文（逗号分隔）。 */
    static String target() {
        return configValue("target");
    }

    /** target 拆成列表，UI 逐行渲染用。 */
    static List<String> targetList() {
        List<String> out = new ArrayList<>();
        for (String s : target().split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static long srCount() {
        return num(status().get("sr_count"));
    }

    static long nofxCount() {
        return num(status().get("nofx_count"));
    }

    static boolean halted() {
        return "1".equals(status().get("halt"));
    }

    static String haltReason() {
        String r = status().get("halt_reason");
        return r == null ? "" : r;
    }

    static String bootId() {
        String b = status().get("boot_id");
        return b == null || b.isEmpty() ? "-" : b;
    }

    // ------------------------------------------------- A 段（DE 期急救）状态
    //
    // camd 在「用户还没解锁」的 DE 阶段自主判断并处置：模块挂载 → 禁模块，
    // 安装的坏包 → 删 /data/app 覆盖目录，然后软/硬重启。这些动作发生在
    // 开机过程中，用户往往不知道，所以必须把「上次干了什么」广播出来。
    // 注意 a_last_* 是 camd 从 state 读出来统一下发的 ——
    // /data/system/camellia_action 下的 pending 原文 App 没有权限读。

    /** 当前是否处于 DE（用户尚未解锁）阶段。 */
    static boolean phaseDe() {
        return "DE".equals(status().get("phase"));
    }

    /** 挂载了 SystemUI/界面组件的活跃模块数量。 */
    static long aModCount() {
        return num(status().get("a_mod"));
    }

    /** 最近一次 A 段动作摘要，如 "delete_apk com.android.systemui"。 */
    static String aLastSummary() {
        String act = status().get("a_last_action");
        if (act == null || act.isEmpty() || "-".equals(act)) return "";
        String pkg = status().get("a_last_pkg");
        if (pkg == null || "-".equals(pkg)) pkg = "";
        return pkg.isEmpty() ? act : act + " " + pkg;
    }

    // --------------------------------------------------------------- 快照

    /** 一条坏包快照。name 是 bak_dir 下的目录名（<pkg>@<uptime>）。 */
    static final class Snap {
        final String name;
        final String pkg;
        final long mt;
        final long bytes;
        final boolean hasApk;

        Snap(String name, String pkg, long mt, long bytes, boolean hasApk) {
            this.name = name;
            this.pkg = pkg;
            this.mt = mt;
            this.bytes = bytes;
            this.hasApk = hasApk;
        }
    }

    /** 快照列表，camd 已按 mtime 新→旧排好，直接顺序渲染。 */
    private static volatile List<Snap> sLastSnaps = null;
    /** 诊断：一次性输出快照/状态读取实况，供面板打日志定位“快照不显示”。 */
    static String dbgSnap() {
        int fl = readText(UI_SNAP).length();
        int sl = readText(UI_STATUS).length();
        String su;
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder(); String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            br.close(); p.waitFor();
            su = sb.toString().contains("uid=0") ? "root" : ("no:" + sb.toString().trim());
        } catch (Throwable t) { su = "err:" + t.getClass().getSimpleName(); }
        return "snapFileLen=" + fl + " statusFileLen=" + sl + " su=" + su
                + " sock=" + CamdSocket.connected() + " snaps=" + snaps().size();
    }
    static List<Snap> snaps() {
        List<Snap> out = new ArrayList<>();
        // 0) 优先：socket 状态帧里内嵌的快照（App 读 /sdcard 文件为 0 字节，只有 socket 通）
        try {
            String enc = status().get("snaps");
            if (enc != null && !enc.isEmpty()) {
                for (String e : enc.split(";")) {
                    if (e.isEmpty()) continue;
                    String[] q = e.split(",");
                    if (q.length < 5) continue;
                    out.add(new Snap(q[0], q[1], num(q[2]), num(q[3]), "1".equals(q[4].trim())));
                }
            }
        } catch (Throwable t) { }
        for (String line : readText(UI_SNAP).split("\n")) {
            String tr = line.trim();
            if (tr.isEmpty()) continue;
            String[] p = tr.split("\\|");
            if (p.length < 5) continue;
            out.add(new Snap(p[0], p[1], num(p[2]), num(p[3]), "1".equals(p[4])));
        }
        if (out.isEmpty()) {
            // 兜底：直接扫 bak 目录（camd_snap.txt 读不到时仍能显示真实备份）
            final String D = "${'$'}";
            String sh = "for d in " + DIR + "/bak/*/; do [ -f \"" + D + "d/meta\" ] || continue; " +
                "n=" + D + "(basename \"" + D + "d\"); " +
                "p=" + D + "(sed -n 's/^pkg=//p' \"" + D + "d/meta\"|head -1); " +
                "b=" + D + "(stat -c %s \"" + D + "d/base.apk\" 2>/dev/null); " +
                "echo \"" + D + "n|" + D + "p|0|" + D + "{b:-0}|1\"; done";
            try {
                Process pr = new ProcessBuilder("su", "-c", sh).redirectErrorStream(true).start();
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()));
                String ln; StringBuilder sb = new StringBuilder();
                while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
                br.close(); pr.waitFor();
                for (String line : sb.toString().split("\n")) {
                    String tr = line.trim(); if (tr.isEmpty()) continue;
                    String[] q = tr.split("\\|"); if (q.length < 5) continue;
                    out.add(new Snap(q[0], q[1], num(q[2]), num(q[3]), "1".equals(q[4])));
                }
            } catch (Throwable t) { }
        }
        // 读空（FUSE 闪空 / 坏帧）时沿用上一次有效快照，避免列表一拍消失
        if (out.isEmpty() && sLastSnaps != null && !sLastSnaps.isEmpty()) return sLastSnaps;
        if (!out.isEmpty()) sLastSnaps = out;
        return out;
    }

    /** 快照数量（camd 已算好，避免 App 自己数）。 */
    static int snapCount() {
        long n = num(status().get("bak_n"));
        return n < 0 ? 0 : (int) n;
    }

    // ---- 定时快照（桌面布局；camd 侧 camellia_dsnap.c 实现）----
    static final String DSNAP_LIST = UI_DIR + "/dsnap_list.txt";
    static boolean dsnapRun() { return sendCmd(one("dsnap_run", "1")); }
    static boolean dsnapDel(String id) { return id != null && !id.isEmpty() && sendCmd(one("dsnap_del", id)); }
    static boolean dsnapRestore(String id) { return id != null && !id.isEmpty() && sendCmd(one("dsnap_restore", id)); }
    static boolean dsnapCfg(boolean enabled, int days, int hour, int keep, String kinds) {
        return sendCmd(one("dsnap_cfg", "enabled=" + (enabled ? 1 : 0) + ",days=" + days + ",hour=" + hour + ",keep=" + keep + ",kinds=" + kinds));
    }
    static java.util.Map<String, String> dsnapStatus() { return status(); }
    /** camd 是否在跑（以状态里有无 ver 为准）。 */
    static boolean hasBusy() {
        String t = readText(UI_BUSY);
        if (t == null || t.isEmpty()) return false;
        long ts = 0;
        for (String line : t.split("\n")) { line = line.trim(); if (line.startsWith("ts=")) { try { ts = Long.parseLong(line.substring(3).trim()); } catch (Throwable x) {} break; } }
        if (ts <= 0) return false;
        long age = System.currentTimeMillis() / 1000L - ts;
        return age >= -60L && age <= 1800L;
    }
    /** 用 su -c pidof camd 取进程真值：>0=在跑, 0=确实没进程, -1=探测失败(未知)。*/
    static long camdPidNow() {
        try {
            Process p = new ProcessBuilder("su", "-c", "pidof camd 2>/dev/null || echo __NONE__").redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder(); String ln;
            while ((ln = br.readLine()) != null) sb.append(ln).append(' ');
            br.close(); p.waitFor();
            String t = sb.toString().trim();
            if (t.contains("__NONE__")) return 0L;   // pidof 明确“无此进程”
            for (String x : t.split("[^0-9]+")) if (!x.isEmpty()) return Long.parseLong(x);
            return -1L;   // su 无输出 = 未知；不能当 0，否则 chip 误判“未部署”反复横跳
        } catch (Throwable e) { return -1L; }
    }
    static boolean camdAlive() { return CamdSocket.connected() || hasBusy() || isFastChan() || status().get("ver") != null; }
    static boolean dsnapExportAll() { return sendCmd(one("dsnap_export_all", "1")); }
    /** 触发列表刷新并读取 camd 写到 sdcard 的列表（id|src|size|ts）。 */
    static String dsnapListRaw() {
        sendCmd(one("dsnap_list", "1"));
        try { Thread.sleep(250); } catch (InterruptedException ignored) { }
        return readText(DSNAP_LIST);
    }

    // -------------------------------------------------------- 指令上行（写）

    /**
     * 原子下发指令给 camd：写入 camd_cmd（tmp + rename）。
     * camd 每秒轮询，读到后立即 unlink 并应用，延迟 ≤1s。
     */
    private static boolean sendCmd(Map<String, String> kv) {
        if (kv == null || kv.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        // socket 通道优先：一条长连接同时承载命令与状态，App 与 camd
        // 都不再为「收/发」而轮询 FUSE，也不需要 fork su。连不上就往下落。
        if (CamdSocket.send(sb.toString())) return true;
        // 快通道：有 root 时写 ext4 上的 /data/adb/camellia/cmd，
        // camd 的 inotify 立刻唤醒，不再靠 FUSE 每秒轮询。
        if (fastChan && writeFastCmd(sb.toString())) return true;
        // 慢通道兜底：FUSE 写 camd_cmd，camd 每秒轮询（≤1s 生效）
        try {
            File dir = new File(UI_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File tmp = new File(dir, "camd_cmd.tmp");
            File dst = new File(dir, "camd_cmd");
            FileOutputStream out = new FileOutputStream(tmp, false);
            try {
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            if (dst.exists() && !dst.delete()) {
                tmp.delete();
                return false;
            }
            return tmp.renameTo(dst);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 快通道写指令：su 写 /data/adb/camellia/cmd.tmp 再 mv，camd 收到 inotify 事件。 */
    private static boolean writeFastCmd(String body) {
        try {
            String b64 = Base64.encodeToString(body.getBytes("UTF-8"), Base64.NO_WRAP);
            String cmd = "mkdir -p " + DIR
                    + " && echo '" + b64 + "' | base64 -d > " + CMD_FAST + ".tmp"
                    + " && mv -f " + CMD_FAST + ".tmp " + CMD_FAST;
            return runSuExit(cmd, 8000) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Map<String, String> one(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    /** 坏包自动修复开关：0=只诊断（默认），1=允许动手。 */
    static boolean setAutoFix(boolean on) {
        return sendCmd(one("auto_fix", on ? "1" : "0"));
    }

    /** 系统自杀屏蔽开关（与 auto_fix 无关：这是「别让系统误判」，不是修复动作）。 */
    static boolean setShield(boolean on) {
        return sendCmd(one("shield", on ? "1" : "0"));
    }

    /** 安装事件监听：装了新包就重置崩溃计数、重新判定。 */
    static boolean setWatchInstall(boolean on) {
        return sendCmd(one("watch_install", on ? "1" : "0"));
    }

    /** eBPF 加速检测：1 自动(可用即启用)/0 关闭(回退 pidfd+dropbox)。 */
    static boolean setEbpf(boolean on) {
        return sendCmd(one("ebpf", on ? "1" : "0"));
    }

    static boolean setSrMax(int v) {
        if (v < 1 || v > 3) return false;
        return sendCmd(one("sr_max", String.valueOf(v)));
    }

    static boolean setNofxMax(int v) {
        if (v < 1 || v > 10) return false;
        return sendCmd(one("nofx_max", String.valueOf(v)));
    }

    static boolean setTickSec(int v) {
        if (v < 5 || v > 3600) return false;
        return sendCmd(one("tick_sec", String.valueOf(v)));
    }

    /** 快照保留份数：0 = 不留（急救时不落备份，省 52MB 写盘），最多 5 份。
     *  0 也意味着「删覆盖层后没有回滚材料」，所以默认值仍是 3，且面板必须
     *  把这条副作用写在说明里，不能只显示一个数字。 */
    static boolean setBakKeep(int v) {
        if (v < 0 || v > 5) return false;
        return sendCmd(one("bak_keep", String.valueOf(v)));
    }

    static boolean setStableSec(long v) {
        if (v < 60 || v > 86400) return false;
        return sendCmd(one("stable_sec", String.valueOf(v)));
    }

    /** 清除熔断并归零计数（等同 camd --reset-halt）。 */
    static boolean resetHalt() {
        return sendCmd(one("reset_halt", "1"));
    }

    /** 让 camd 立即重扫快照目录。 */
    static boolean refreshSnaps() {
        return sendCmd(one("snap_list", "1"));
    }

    /** 删除一条快照（camd 侧 rm -rf，目录名带穿越校验）。 */
    static boolean snapDelete(String name) {
        return name != null && !name.isEmpty() && sendCmd(one("snap_del", name));
    }

    /**
     * 用快照里的 base.apk 重装回去（pm install -r -d）。
     * -d 允许降版本：快照往往来自更早的 versionCode，不带它必然失败。
     */
    static boolean snapRestore(String name) {
        return name != null && !name.isEmpty() && sendCmd(one("snap_restore", name));
    }

    /**
     * 首次使用时确保通道目录存在。注意：绝不覆盖 camd 侧已有 config
     * （生产环境可能已放好 target / auto_fix，覆盖会改变安全边界）。
     */
    static void ensureConfig() {
        try {
            File dir = new File(UI_DIR);
            if (!dir.isDirectory()) dir.mkdirs();
        } catch (Throwable t) {
            // 通道目录建不出来也不影响主流程，UI 会显示「未部署」
        }
    }

    /** 单行状态摘要，给设置页/通知用。 */
    static String summary() {
        if (!deployed()) return "未部署";
        if (!isAlive()) return "已部署 · 进程未运行";
        String auto = autoFix() ? "自动修复开" : "仅诊断";
        String st = halted() ? " · 熔断" : " · 正常";
        String phase = phaseDe() ? " · DE未解锁" : "";
        // 通道状态直接影响「改开关多久生效」，用户调参时要能看出来：
        // socket = 长连接直连 camd，命令与状态都毫秒级，且两端零轮询；
        // 快 = 有 root 但 socket 不可用（SELinux 未放行时的兜底），su 写 ext4 + inotify；
        // 慢 = 无 root，只能靠 FUSE 每秒轮询，最坏 1s。
        String chan = CamdSocket.connected() ? " · 通道:socket"
                : (fastChan ? " · 通道:快" : " · 通道:慢");
        String last = aLastSummary();
        return "运行中 · " + auto + st + phase + chan + " · target=" + target()
                + (last.isEmpty() ? "" : " · 上次急救: " + last);
    }
}
