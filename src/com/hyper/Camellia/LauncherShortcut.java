package com.hyper.Camellia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

/**
 * 桌面「长按图标 → 快捷方式菜单」注入「小窗」项（system_server 侧实现）。
 *
 * <h3>为什么装在 system_server</h3>
 * HyperOS 桌面（com.miui.home）是纯 Flutter：菜单项来自
 * {@code LauncherApps.getShortcuts()} 返回的 ShortcutInfo 列表
 * （libapp.so 内可见 "LauncherApps callback:" / "processItems shortcutInfo added"），
 * 桌面自己并不持有任何 Java 菜单定义。所以最干净的注入点是在系统侧合成一条
 * ShortcutInfo 并接管它的图标与点击分发，无需改桌面 APK / Dart。
 *
 * <h3>挂的钩子</h3>
 * <pre>
 * com.android.server.pm.ShortcutService$LocalService     ← 权威数据源
 *   List getShortcuts(int,String,long,String,List,List,ComponentName,int,int,int,int)
 *   Intent[] createShortcutIntents(int,String,String,String,int,int,int)
 *   ParcelFileDescriptor getShortcutIconFd(...) / int getShortcutIconResId(...)
 *   String getShortcutIconUri(...) / getShortcutStartingThemeResName(...)
 * com.android.server.pm.LauncherAppsService$LauncherAppsImpl   ← 兜底 + 探针
 *   ParceledListSlice getShortcuts(String,ShortcutQueryWrapper,UserHandle)
 *   boolean startShortcutInner(IBinder,...,int)
 * </pre>
 *
 * <h3>点击后如何变「小窗」</h3>
 * 合成项的 Intent 指向模块自己的 {@link ShortcutFreeformActivity}（透明中转页），
 * 由它用 {@link SecurityDiffFeatures#smallWindowOptions} 以小窗参数启动目标应用
 * —— 和侧边栏「小窗」快捷项完全同一条启动链。
 */
public final class LauncherShortcut {

    private static final String TAG = "Camellia-Launcher";

    /** 合成项的 shortcut id */
    public static final String ID = "camellia_freeform";
    /** 菜单里显示的名字 */
    private static final String LABEL = "小窗";

    /** 模块自己的中转 Activity（见 ShortcutFreeformActivity） */
    private static final String HOST_PKG = "com.hyper.Camellia";
    private static final String HOST_ACT = "com.hyper.Camellia.ShortcutFreeformActivity";
    static final String EXTRA_PKG = "camellia_target_pkg";
    static final String EXTRA_USER = "camellia_target_user";
    /** 目标应用的启动 ComponentName（显式组件，绕过包可见性限制） */
    static final String EXTRA_COMPONENT = "camellia_target_component";

    /** 合成项图标（运行时渲染，system_server 可写 /data/system） */
    private static final String ICON_FILE = "/data/system/camellia_shortcut_icon.png";

    /** 开关：与设置页规则 id 对应（Prefs 前缀 ul_） */
    private static final String PREF_KEY = Prefs.KEY_UL_PREFIX + "app_shortcut_freeform";

    /**
     * 开关：「桌面内自由小窗」（A 方案）。
     *
     * <p>它<b>不控制本类</b>，而是本类的互斥条件：A 开着时 B（本类的快捷方式注入）
     * 自动失效。用户明确要求「做成两个功能，桌面里的功能开了就让快捷方式小窗失效」。</p>
     */

    /**
     * 排序权重：越小越靠前。桌面快捷菜单有条数上限（条目多的 app 会截断尾部），
     * 注意：{@code ShortcutInfo.Builder.setRank()} 内部有
     * {@code checkArgument(rank >= 0)}，传负数会抛 IllegalArgumentException
     * 导致注入失败；正常路径由 {@code inject()} 传入「当前列表最大 rank + 1」
     * （桌面按 rank 降序显示，rank 越大越靠前），这里只是兜底。</p>
     */
    private static final int RANK = 0;

    /**
     * 桌面快捷菜单一次最多渲染 4 个快捷项，第 5 个起被截断。
     *
     * <p>实测（见 {@code /data/system/camellia_launcher.log}）：notes n=3、settings n=4
     * 都能看到「小窗」，qqmusic n=5 看不到，正好卡在 4 这个阈值上。</p>
     */
    private static final int MAX_ITEMS = 4;

    /** 覆盖 {@link #MAX_ITEMS} 的兜底配置键（写在 misharemt2-cfg.txt 里，重启生效） */
    private static final String KEY_MAX_ITEMS = "ul_app_shortcut_max";

    /**
     * 快捷方式屏蔽关键词（全局），与侧边栏面板共用同一份规则。
     *
     * <p>为什么在 system_server 出口处过滤、而不是去 hook Launcher：桌面长按菜单、
     * 侧边栏快捷方式面板、以及任何第三方启动器最终都从 {@link #L_SVC} /
     * {@link #L_IMPL} 这两个方法取数据，在这里裁掉一次，所有消费方自动同步。</p>
     */
    private static final String KEY_BLOCK_ALL = "ul_sidebar_sc_block";

    /** 应用专属屏蔽规则，形如 {@code 包名或应用名 = 关键词1,关键词2}（分号或换行分隔多条） */
    private static final String KEY_BLOCK_APP = "ul_sidebar_sc_block_app";

    private static final String L_SVC = "com.android.server.pm.ShortcutService$LocalService";
    private static final String L_IMPL = "com.android.server.pm.LauncherAppsService$LauncherAppsImpl";

    private static final long TTL_MS = 1500L;
    /** 兜底文件通道的缓存窗口（读 /sdcard 是真 IO，别每次长按都读盘） */
    private static final long FILE_TTL_MS = 3000L;

    /**
     * 兜底配置候选路径。
     *
     * <p>system_server 侧读设置有一条正常通道：{@code XposedModule#getRemotePreferences()}。
     * 但框架 service 不可用 / remote prefs 里恰好没有这个键时（历史 bug：模块从未
     * 往框架侧写过这个键），这里还能从设置页落地的配置文件里捞回来。</p>
     */
    private static final String[] FALLBACK_FILES = {
            "/storage/emulated/0/Download/misharemt2/misharemt2-cfg.txt",
            "/storage/emulated/0/Android/media/com.hyper.Camellia/misharemt2.ini",
            "/storage/emulated/0/Android/data/com.hyper.Camellia/files/misharemt2.ini",
            "/sdcard/Download/misharemt2/misharemt2-cfg.txt",
    };

    private static FreeformHooks.PrefProvider sPP;
    /** 模块实例引用：system_server 侧读 remote prefs 需要它（install 时赋值） */
    private static volatile XposedModule sModule;
    private static long sAt;
    private static boolean sOn;
    private static long sFileAt;
    private static boolean sFileOn;
    /** 跨进程通道是否拿到了明确值（区分「明确关闭」与「暂无数据」） */
    private static boolean sFileKnown;
    /** 屏蔽规则快照（remote prefs 优先，兜底配置文件补充），按 {@link #FILE_TTL_MS} 刷新 */
    private static String sBlockAll;
    private static String sBlockApp;
    private static long sBlockAt = -FILE_TTL_MS;
    /** 各快照对应的配置失效代际：代际一变立刻重建，不用等 TTL（{@link Prefs#cfgGen()}） */
    private static volatile long sGen = -1L;
    private static volatile long sFileGen = -1L;
    private static volatile long sBlockGen = -1L;
    private static Context sSysCtx;
    private static int sProbe;
    private static volatile boolean sInstalled;

    private LauncherShortcut() {
    }

    // ───────────────────────────── 日志 ─────────────────────────────

    /**
     * 落盘日志文件。system_server 日志量极大，logcat 缓冲区几秒就被冲掉，
     * 事后无法回看；关键诊断信息必须同时写文件。
     */
    static final String LOG_FILE = "/data/system/camellia_launcher.log";
    /**
     * 备用日志路径。桌面进程跑在非 system_server 的 SELinux 域，写 /data/system
     * 会被拒绝且是静默失败 —— 表现为「模块在桌面侧明明跑了却一条日志都没有」，
     * 排查时极易误判成「代码没执行」。主路径失败时落到这里：同进程私有目录。
     */
    static final String LOG_FILE_ALT = "/sdcard/Android/media/com.miui.home/camellia_launcher.log";

    private static void log(String s) {
        Log.i(TAG, s);
        logFile("I " + s);
    }

    private static void logE(String s, Throwable t) {
        Log.e(TAG, s, t);
        logFile("E " + s + (t == null ? "" : "\n" + Log.getStackTraceString(t)));
    }

    /** 追加一行日志；主路径失败自动落 {@link #LOG_FILE_ALT} */
    private static void logFile(String s) {
        String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                java.util.Locale.US).format(new java.util.Date());
        String line = ts + " " + s + "\n";
        if (!appendTo(LOG_FILE, line)) {
            appendTo(LOG_FILE_ALT, line);
        }
    }

    /** @return 写入是否成功；超过 512KB 就重开，避免无限增长 */
    private static boolean appendTo(String path, String line) {
        try {
            File f = new File(path);
            if (f.length() > 512 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(line.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return true;
        } catch (Throwable ignored) {
            return false;   // 写日志失败不能影响主流程
        }
    }

    /** 探针日志：只打前 80 条，避免刷爆 logcat */
    private static void probe(String s) {
        // v157: 上限从 80 提到 600。system_server 是长驻进程，旧上限在开机几小时后
        // 就把日志彻底静默，导致「开关关不掉」这类问题完全无法从日志判断
        //（连 enabled=... 和 system cfg -> ... 都不再打印，误以为链路没跑到）。
        if (sProbe >= 600) return;
        sProbe++;
        Log.i(TAG, "[probe] " + s);
    }

    // ───────────────────────────── 开关 ─────────────────────────────

    private static boolean enabled() {
        // 权威通道优先：CfgProvider 下发的跨进程文本由模块 app 进程读自身 prefs 生成，
        // 一定是当前设置。反过来，system_server 拿到的 getRemotePreferences() 是框架
        // 缓存的同一个对象，实测反射 reload() 不生效，sOn 会永远停在旧值
        // （真机症状：设置里关掉开关，长按菜单里的小窗依然在）。
        Boolean cross = crossProcFlag();
        if (cross != null) {
            return cross.booleanValue();
        }
        // 兜底：remote prefs 内存快照（仅 provider 暂不可用时走到）
        long now = SystemClock.uptimeMillis();
        synchronized (LauncherShortcut.class) {
            if (now - sAt >= TTL_MS || sGen != Prefs.cfgGen()) {
                sAt = now;
                sGen = Prefs.cfgGen();
                try {
                    SharedPreferences sp = sPP == null ? null : sPP.prefs();
                    if (sp != null) {
                        try {
                            Method m = sp.getClass().getMethod("reload");
                            m.setAccessible(true);
                            m.invoke(sp);
                        } catch (Throwable ignored) {
                        }
                        sOn = sp.getBoolean(PREF_KEY, false);
                    }
                } catch (Throwable ignored) {
                    // 保留旧快照
                }
            }
            // v158 加固：跨进程通道（provider + 兜底文件）都拿不到明确值时，
            // 绝不能无条件信任 sOn —— 它来自框架缓存的 SharedPreferences 对象，
            // 实测反射 reload() 不生效，一旦某次读成 true 就永久 true，
            // 真机表现正是「设置里关掉开关，长按仍然注入小窗」（08:09:53 复现）。
            // 安全默认「关闭」：宁可功能暂时不生效（provider 恢复后下一次 TTL
            // 到期即自动正确），也不能让用户关不掉开关。
            if (sOn) {
                probe("enabled(): 通道无数据，忽略 remote sOn=true，安全默认 false");
            }
            return false;
        }
    }

    /**
     * 跨进程三态读取 {@link #PREF_KEY}：{@code TRUE}/{@code FALSE} = 拿到明确值，
     * {@code null} = 通道当前没有数据（provider 不可用，或文本里没有这个键）。
     *
     * <p>结果按 {@link #FILE_TTL_MS} 缓存，避免每次长按都跨进程 query。</p>
     */
    private static Boolean crossProcFlag() {
        long now = SystemClock.uptimeMillis();
        synchronized (LauncherShortcut.class) {
            if (now - sFileAt < FILE_TTL_MS && sFileGen == Prefs.cfgGen()) {
                return sFileKnown ? Boolean.valueOf(sFileOn) : null;
            }
            sFileAt = now;
            sFileGen = Prefs.cfgGen();
        }
        String v = readKeyFromFiles(PREF_KEY);
        Boolean out = null;
        if (v != null) {
            out = Boolean.valueOf("1".equals(v) || "true".equalsIgnoreCase(v));
        }
        synchronized (LauncherShortcut.class) {
            sFileKnown = out != null;
            sFileOn = out != null && out.booleanValue();
        }
        if (out != null) {
            probe("cross-proc " + PREF_KEY + "=" + (out.booleanValue() ? "1" : "0"));
        }
        return out;
    }

    /**
     * 兜底通道：从 {@link #FALLBACK_FILES} 里读 {@code ul_app_shortcut_freeform}。
     *
     * <p>设置页每次改开关都会重写这些文件（见 {@code Prefs.ulSidecarBody} /
     * {@code Prefs.writePublicSidecar}），所以只要用户开过开关，这里一定读得到 1。</p>
     */
    private static boolean readFlagFromFiles() {
        String v = readKeyFromFiles(PREF_KEY);
        return v != null && ("1".equals(v) || "true".equalsIgnoreCase(v));
    }

    private static boolean enabledFromFiles() {
        long now = SystemClock.uptimeMillis();
        synchronized (LauncherShortcut.class) {
            if (now - sFileAt < FILE_TTL_MS && sFileGen == Prefs.cfgGen()) {
                return sFileOn;
            }
            sFileAt = now;
            sFileGen = Prefs.cfgGen();
        }
        boolean on = readFlagFromFiles();
        synchronized (LauncherShortcut.class) {
            sFileOn = on;
        }
        if (on) {
            probe("开关来自兜底配置文件（remote prefs 未生效）");
        }
        return on;
    }

    /**
     * 读任意键（返回命中的原始值，读不到返回 null）。
     *
     * <p>首选 Settings.System 公共键（设备级、任何进程可读、不受分区存储限制），
     * 再退回旧的兜底文件（兼容老版本残留）。设置页每次保存都会整份重写公共键，
     * 所以只要用户动过开关，这里一定读得到。</p>
     */
    private static String readKeyFromFiles(String key) {
        if (key == null) {
            return null;
        }
        String viaSystem = readKeyFromSystem(key);
        if (viaSystem != null) {
            return viaSystem;
        }
        for (String path : FALLBACK_FILES) {
            java.io.BufferedReader br = null;
            try {
                File f = new File(path);
                if (!f.isFile()) {
                    continue;
                }
                long len = f.length();
                if (len <= 0L || len > 65536L) {
                    continue;
                }
                br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    if (!key.equals(line.substring(0, eq).trim())) {
                        continue;
                    }
                    String v = line.substring(eq + 1).trim();
                    probe("fallback " + path + " -> " + key + "=" + v);
                    return v;
                }
            } catch (Throwable ignored) {
                // 读不到就换下一个候选路径
            } finally {
                try {
                    if (br != null) {
                        br.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 从 Settings.System 公共键里读单个键（读不到返回 null） */
    private static String readKeyFromSystem(String key) {
        try {
            Context c = sysCtx();
            if (c == null) {
                return null;
            }
            // provider 优先、Settings.System 兜底（system_server 里同样可用）
            String text = Prefs.readCrossProcCfg(c);
            if (text == null || text.length() == 0) {
                return null;
            }
            // v158 补强（「关掉开关仍注入」的最后一环）：provider 一旦短暂不可用，
            // readCfgText 会降级返回 cache-stale —— 那是上一次成功读到的旧全文，
            // 里面可能仍是被关闭前的 ul_app_shortcut_freeform=1。
            // 门控开关绝不能建立在旧快照上：通道降级即按「无数据」处理，
            // 交给 enabled() 的安全默认（false）；provider 恢复后自动回到真值。
            // 实测证据：provider 值=0、磁盘 prefs=false，仍在 08:48:54 注入。
            if ("cache-stale".equals(Prefs.lastCfgChannel())) {
                probe("system cfg stale(channel=cache-stale) -> 按无数据处理: " + key);
                return null;
            }
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                int eq = line.indexOf('=');
                if (eq <= 0 || !key.equals(line.substring(0, eq).trim())) {
                    continue;
                }
                String v = line.substring(eq + 1).trim();
                probe("system cfg -> " + key + "=" + v);
                return v;
            }
        } catch (Throwable ignored) {
            // 公共键不可用时退回文件通道
        }
        return null;
    }

    /** 本进程的 Context：普通进程用 currentApplication，system_server 用 getSystemContext */
    private static Context mSysCtx;

    private static Context sysCtx() {
        Context c = mSysCtx;
        if (c != null) {
            return c;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (!(app instanceof Context)) {
                Object th = at.getMethod("currentActivityThread").invoke(null);
                if (th != null) {
                    app = at.getMethod("getSystemContext").invoke(th);
                }
            }
            if (app instanceof Context) {
                mSysCtx = (Context) app;
                return mSysCtx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 桌面快捷菜单一次能显示的快捷项上限。
     *
     * <p>默认 {@link #MAX_ITEMS}。实测调参用：在兜底配置文件里写一行
     * {@code ul_app_shortcut_max=3} 即可覆盖，不用重新编译（重启生效）。</p>
     */
    private static int maxItems() {
        String v = readKeyFromFiles(KEY_MAX_ITEMS);
        if (v != null) {
            try {
                int n = Integer.parseInt(v.trim());
                if (n >= 1 && n <= 8) {
                    return n;
                }
            } catch (Throwable ignored) {
                // 值不合法就用默认值
            }
        }
        return MAX_ITEMS;
    }


    // ────────────────────── 屏蔽关键词（桌面菜单） ──────────────────────

    /**
     * 读一次屏蔽规则：remote prefs 优先，空值回退兜底配置文件。
     *
     * <p>返回 {@code String[2]} = {全局关键词, 应用专属规则}。结果按 {@link #FILE_TTL_MS}
     * 缓存：长按时会连续触发多次 getShortcuts，不能在回调里反复做 binder / 磁盘 IO。</p>
     */
    private static String[] blockRaw() {
        long now = SystemClock.uptimeMillis();
        synchronized (LauncherShortcut.class) {
            if (now - sBlockAt < FILE_TTL_MS && sBlockGen == Prefs.cfgGen()) {
                return new String[] { sBlockAll, sBlockApp };
            }
            sBlockAt = now;
            sBlockGen = Prefs.cfgGen();
        }
        String all = null;
        String app = null;
        try {
            SharedPreferences sp = sPP == null ? null : sPP.prefs();
            if (sp != null) {
                try {
                    Method m = sp.getClass().getMethod("reload");
                    m.setAccessible(true);
                    m.invoke(sp);
                } catch (Throwable ignored) {
                }
                all = sp.getString(KEY_BLOCK_ALL, null);
                app = sp.getString(KEY_BLOCK_APP, null);
            }
        } catch (Throwable ignored) {
            // 通道不可用就走兜底文件
        }
        if (all == null || all.length() == 0) all = readKeyFromFiles(KEY_BLOCK_ALL);
        if (app == null || app.length() == 0) app = readKeyFromFiles(KEY_BLOCK_APP);
        synchronized (LauncherShortcut.class) {
            sBlockAll = all;
            sBlockApp = app;
        }
        return new String[] { all, app };
    }

    /**
     * 按屏蔽关键词裁剪一次快捷方式列表。
     *
     * <p>没有规则、或没有命中时返回原实例（调用方用 {@code == } 判断是否发生裁剪，
     * 避免无谓重建 ParceledListSlice）。模块自己注入的 {@link #ID} 永不被屏蔽。</p>
     */
    private static List<ShortcutInfo> filterBlocked(List<ShortcutInfo> items, String pkg, String where) {
        if (items == null || items.isEmpty()) return items;
        String[] raw = blockRaw();
        boolean emptyAll = raw[0] == null || raw[0].length() == 0;
        boolean emptyApp = raw[1] == null || raw[1].length() == 0;
        if (emptyAll && emptyApp) return items;
        List<String> kws = ShortcutBlock.rules(raw[0], raw[1], pkg,
                ShortcutBlock.appLabelOf(systemContext(), pkg));
        if (kws.isEmpty()) return items;
        List<String> blocked = new ArrayList<String>();
        List<ShortcutInfo> kept = ShortcutBlock.filter(items, kws, ID, blocked);
        if (kept != items) {
            log("屏蔽快捷方式(" + where + ") pkg=" + pkg + " " + items.size() + "->" + kept.size()
                    + " 命中=" + blocked);
        }
        return kept;
    }

    /** 用裁剪后的列表重建 ParceledListSlice；失败时退回原返回值（宁可漏屏蔽，也不让宿主拿到 null） */
    private static Object reslice(List<ShortcutInfo> content, Object fallback) {
        Object slice = newSlice(content, content);
        return slice != null ? slice : fallback;
    }

    // ───────────────────────────── 安装 ─────────────────────────────

    public static void install(final XposedModule module, final ClassLoader cl,
                               final FreeformHooks.PrefProvider pp) {
        sPP = pp;
        sModule = module;
        synchronized (LauncherShortcut.class) {
            if (sInstalled) {
                log("install skipped (already installed)");
                return;
            }
        }
        log("install begin, loader=" + cl);

        ensureIconFile();

        // 1) ShortcutService$LocalService —— 权威数据源
        Class<?> svc = findClass(cl, L_SVC);
        if (svc == null) {
            logE("找不到 " + L_SVC, null);
        } else {
            for (Method m : svc.getDeclaredMethods()) {
                String n = m.getName();
                try {
                    if ("getShortcuts".equals(n) && List.class.isAssignableFrom(m.getReturnType())) {
                        hookLocalGetShortcuts(module, m);
                    } else if ("createShortcutIntents".equals(n) && m.getReturnType() == Intent[].class) {
                        hookCreateIntents(module, m);
                    } else if ("getShortcutIconFd".equals(n)
                            && m.getReturnType() == ParcelFileDescriptor.class) {
                        hookIconFd(module, m);
                    } else if ("getShortcutIconResId".equals(n) && m.getReturnType() == int.class) {
                        hookIconResId(module, m);
                    } else if ("getShortcutIconUri".equals(n) && m.getReturnType() == String.class) {
                        hookIconUri(module, m);
                    } else if ("getShortcutStartingThemeResName".equals(n)
                            && m.getReturnType() == String.class) {
                        hookStartingTheme(module, m);
                    }
                } catch (Throwable t) {
                    logE("hook " + L_SVC + "#" + n, t);
                }
            }
        }

        // 2) LauncherAppsService$LauncherAppsImpl —— 兜底注入 + 点击兜底
        Class<?> impl = findClass(cl, L_IMPL);
        if (impl == null) {
            logE("找不到 " + L_IMPL, null);
        } else {
            for (Method m : impl.getDeclaredMethods()) {
                String n = m.getName();
                try {
                    if ("getShortcuts".equals(n) && m.getParameterCount() == 3) {
                        hookImplGetShortcuts(module, m);
                    } else if ("startShortcutInner".equals(n)) {
                        hookStartShortcutInner(module, m);
                    }
                } catch (Throwable t) {
                    logE("hook " + L_IMPL + "#" + n, t);
                }
            }
        }
        boolean ok = (svc != null) || (impl != null);
        if (ok) {
            synchronized (LauncherShortcut.class) {
                sInstalled = true;
            }
            log("install done (hooked)");
        } else {
            // system_server 极早期 loader 可能还不完整（libxposed 传入的
            // PathClassLoader 里没有 services.jar），此时不要锁死安装标记，
            // 交给 onPackageReady 入口或延迟重试再试一次。
            logE("install failed: 两个目标类都没找到，稍后重试", null);
            scheduleRetry(module, cl, pp);
        }
    }

    private static volatile int sRetry;

    /** system_server 启动早期 loader 不完整时的延迟重试（最多 3 次）。 */
    private static void scheduleRetry(final XposedModule module, final ClassLoader cl,
                                      final FreeformHooks.PrefProvider pp) {
        final int attempt = ++sRetry;
        if (attempt > 3) return;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    log("install retry #" + attempt);
                    try {
                        install(module, cl, pp);
                    } catch (Throwable t) {
                        logE("install retry #" + attempt + " failed", t);
                    }
                }
            }, 6000L * attempt);
        } catch (Throwable t) {
            logE("scheduleRetry failed", t);
        }
    }

    // ───────────────────── 钩子：列表注入 ─────────────────────

    /**
     * {@code List getShortcuts(int launcherUserId, String callingPackage, long changedSince,
     * String packageName, List shortcutIds, List categories, ComponentName componentName,
     * int queryFlags, int userId, int cacheFlags, int flags)}
     */
    private static void hookLocalGetShortcuts(XposedModule module, Method m) {
        hookSafe(module, m, new FreeformHooks.HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    List<?> args = chain.getArgs();
                    if (!(result instanceof List) || args == null || args.size() < 9) return result;
                    String caller = str(args.get(1));
                    String pkg = str(args.get(3));
                    List<?> ids = args.get(4) instanceof List ? (List<?>) args.get(4) : null;
                    int userId = (args.get(8) instanceof Integer) ? (Integer) args.get(8) : 0;
                    @SuppressWarnings("unchecked")
                    List<ShortcutInfo> list = (List<ShortcutInfo>) result;
                    probe("Local.getShortcuts caller=" + caller + " pkg=" + pkg
                            + " u=" + userId + " ids=" + ids + " n=" + list.size()
                            + " " + dump(list));
                    // 屏蔽规则独立于「小窗」开关：先裁剪，再决定要不要注入
                    List<ShortcutInfo> kept = pkg == null ? list : filterBlocked(list, pkg, "local");
                    boolean trimmed = kept != list;
                    if (!enabled() || pkg == null) {
                        return trimmed ? kept : result;
                    }
                    if (ids != null && !ids.contains(ID)) return trimmed ? kept : result;
                    // 必须改副本：result 可能是 ShortcutService 的缓存列表，
                    // 就地裁剪会把它自己的快捷项从缓存里删掉（用户下次长按就少几项）。
                    List<ShortcutInfo> copy = new ArrayList<ShortcutInfo>(kept);
                    if (inject(copy, pkg, userId)) return copy;
                    return trimmed ? kept : result;
                } catch (Throwable t) {
                    logE("inject(local) failed", t);
                }
                return result;
            }
        });
        log("hooked LocalService#getShortcuts");
    }

    /** {@code ParceledListSlice getShortcuts(String callingPackage, ShortcutQueryWrapper q, UserHandle user)} */
    private static void hookImplGetShortcuts(XposedModule module, Method m) {
        hookSafe(module, m, new FreeformHooks.HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    List<?> args = chain.getArgs();
                    if (args == null || args.isEmpty()) return result;
                    String caller = str(args.get(0));
                    UserHandle uh = (args.size() > 2 && args.get(2) instanceof UserHandle)
                            ? (UserHandle) args.get(2) : null;
                    List<ShortcutInfo> items = sliceList(result);
                    probe("Impl.getShortcuts caller=" + caller + " u=" + uh
                            + " strip=" + (items == null ? "?" : items.size())
                            + (items == null ? "" : " " + dump(items)));
                    if (items == null || items.isEmpty()) return result;
                    String pkg = items.get(0).getPackage();
                    // 先按屏蔽关键词裁剪（独立于「小窗」开关），再决定要不要注入
                    List<ShortcutInfo> kept = pkg == null ? items : filterBlocked(items, pkg, "impl");
                    boolean trimmed = kept != items;
                    if (!enabled() || pkg == null) {
                        return trimmed ? reslice(kept, result) : result;
                    }
                    if (hasId(kept)) return trimmed ? reslice(kept, result) : result;
                    int userId = userIdOf(uh);
                    List<ShortcutInfo> copy = new ArrayList<ShortcutInfo>(kept);
                    boolean added = inject(copy, pkg, userId);
                    if (!added) return trimmed ? reslice(kept, result) : result;
                    Object slice = newSlice(items, copy);
                    return slice != null ? slice : result;
                } catch (Throwable t) {
                    logE("inject(impl) failed", t);
                }
                return result;
            }
        });
        log("hooked LauncherAppsImpl#getShortcuts");
    }

    // ───────────────────── 钩子：点击分发 ─────────────────────

    /**
     * {@code Intent[] createShortcutIntents(int userId, String callingPackage, String packageName,
     * String shortcutId, int launcherUserId, int queryFlags, int flags)}
     */
    private static void hookCreateIntents(XposedModule module, Method m) {
        hookSafe(module, m, new FreeformHooks.HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                List<?> args = chain.getArgs();
                String id = args != null && args.size() > 3 ? str(args.get(3)) : null;
                if (!ID.equals(id)) {
                    Object r = chain.proceed();
                    if (id != null) probe("createShortcutIntents id=" + id + " -> " + r);
                    return r;
                }
                String pkg = str(args.get(2));
                int userId = (args.size() > 4 && args.get(4) instanceof Integer)
                        ? (Integer) args.get(4) : 0;
                // 「桌面增强」按应用过滤：该应用关掉后直接放行，不再注入小窗意图
                if (!enabled()) return chain.proceed();
                log("createShortcutIntents hit pkg=" + pkg + " u=" + userId);
                return new Intent[] { trampoline(pkg, userId) };
            }
        });
        log("hooked LocalService#createShortcutIntents");
    }

    /**
     * {@code boolean startShortcutInner(IBinder token, int a, int b, int c, String s1,
     * String s2, String s3, String s4, Rect bounds, Bundle opts, int flags)}
     *
     * <p>兜底：万一桌面在自己进程里解析 Intent（没走 createShortcutIntents），这里仍拦住。</p>
     */
    private static void hookStartShortcutInner(XposedModule module, Method m) {
        hookSafe(module, m, new FreeformHooks.HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                List<?> args = chain.getArgs();
                String ours = null;
                String pkg = null;
                if (args != null) {
                    // 参数顺序：token, callingUid, callingPid, launcherUserId, callingPackage,
                    // packageName, shortcutId, ?, rect, opts, flags —— 优先按索引取目标包
                    if (args.size() > 5 && args.get(5) instanceof String
                            && looksLikePackage((String) args.get(5))
                            && !isHost((String) args.get(5))) {
                        pkg = (String) args.get(5);
                    }
                    for (Object a : args) {
                        if (!(a instanceof String)) continue;
                        String s = (String) a;
                        if (ID.equals(s)) ours = s;
                        else if (pkg == null && looksLikePackage(s) && !isHost(s)) pkg = s;
                    }
                }
                if (ours == null) return chain.proceed();
                probe("startShortcutInner id=" + ours + " pkg=" + pkg + " args=" + args);
                log("click id=" + ours + " pkg=" + pkg + " callingUid=" + Binder.getCallingUid()
                        + " enabled=" + enabled());
                if (!enabled() || pkg == null) return chain.proceed();
                Context ctx = systemContext();
                if (ctx == null) return chain.proceed();
                // 就地以小窗启动，并吃掉这次调用（不再走 LauncherApps 自己的启动路径）
                boolean ok = launchFreeform(ctx, pkg);
                log("startShortcutInner -> freeform pkg=" + pkg + " ok=" + ok);
                if (ok) return Boolean.TRUE;
                // 自己启动失败就放行，让系统按原路径（桌面解析 shortcut 的 Intent）处理，
                // 至少保证点击有反应
                log("launch failed -> fallback original startShortcutInner");
                return chain.proceed();
            }
        });
        log("hooked LauncherAppsImpl#startShortcutInner");
    }

    // ───────────────────── 钩子：图标 ─────────────────────

    private static void hookIconFd(XposedModule module, Method m) {
        hookSafe(module, m, new FreeformHooks.HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                List<?> args = chain.getArgs();
                if (args == null || args.size() < 4 || !ID.equals(str(args.get(3)))) {
                    return chain.proceed();
                }
                probe("getShortcutIconFd(ours)");
                try {
                    File f = iconFile();
                    if (f != null) {
                        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                    }
                } catch (Throwable t) {
                    logE("icon fd failed", t);
                }
                return null;
            }
        });
        log("hooked LocalService#getShortcutIconFd");
    }

    private static void hookIconResId(XposedModule module, Method m) {
        hookSafe(module, m, new FreeformHooks.HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                List<?> args = chain.getArgs();
                if (args != null && args.size() >= 4 && ID.equals(str(args.get(3)))) {
                    probe("getShortcutIconResId(ours)");
                    return 0;
                }
                return chain.proceed();
            }
        });
        log("hooked LocalService#getShortcutIconResId");
    }

    private static void hookIconUri(XposedModule module, Method m) {
        hookSafe(module, m, new FreeformHooks.HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                List<?> args = chain.getArgs();
                if (args != null && args.size() >= 4 && ID.equals(str(args.get(3)))) {
                    probe("getShortcutIconUri(ours)");
                    return null;
                }
                return chain.proceed();
            }
        });
        log("hooked LocalService#getShortcutIconUri");
    }

    private static void hookStartingTheme(XposedModule module, Method m) {
        hookSafe(module, m, new FreeformHooks.HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                List<?> args = chain.getArgs();
                if (args != null && args.size() >= 4 && ID.equals(str(args.get(3)))) {
                    probe("getShortcutStartingThemeResName(ours)");
                    return null;
                }
                return chain.proceed();
            }
        });
        log("hooked LocalService#getShortcutStartingThemeResName");
    }

    // ───────────────────── 合成 ShortcutInfo ─────────────────────

    private static boolean inject(List<ShortcutInfo> list, String pkg, int userId) {
        if (list == null || pkg == null) return false;
        if (isHost(pkg)) return false;
        if (hasId(list)) return false;
        // v150 重大修正：删除「A 开启则 B 退场」的互斥逻辑（原 v35~v149 一直如此，
        // 是整套方案里最后一个坑）。实测证据链：
        //  1) A 的 native patch 目标是 ValidSmallWindowShortcutMenu.isValid，
        //     它只做「放行」—— 让 freeform 类快捷方式通过桌面过滤，自身不产生数据。
        //  2) 快捷方式数据由本方法注入的 camellia_freeform 提供。
        //  3) 用户实测：A 开关开启 + patch 确认执行（memfd 锚定页存在、信标 pid
        //     与当前桌面对得上）→ 长按菜单里两个「小窗」全灭。原因就是「放行了，
        //     但系统里没有任何 freeform 快捷方式可放行」（B 被跳过）。
        // 结论：A/B 是上下游配套关系，必须同时生效，不存在互斥。
        // 长按菜单最终只会多出「小窗」一项（由 B 注入的数据提供），A 不新增任何
        // 菜单项，所以原先担心的「两个小窗」并不成立 —— 真正的后果是「两个都灭」。
        // 桌面按 rank 升序排列（rank 越小越靠前）——2026-11-13 实测证据：
        // 手机管家注入后 final=[camellia_freeform(r=2), clean_master(r=1),
        // anti_virus(r=0)]，而长按菜单实际显示顺序是「病毒扫描(r=0) →
        // 垃圾清理(r=1) → 小窗(r=2)」，即 rank 小的在前。旧注释写的
        // 「rank 越大越靠前」是反的，取 maxRank+1 反而把小窗顶到最后一格。
        // 这里改为取当前最小 rank - 1，让它稳定落在快捷方式区第一格。
        int minRank = 0;
        for (ShortcutInfo s : list) {
            if (s == null) continue;
            try {
                minRank = Math.min(minRank, s.getRank());
            } catch (Throwable ignored) {
            }
        }
        int myRank = minRank <= Integer.MIN_VALUE + 1 ? Integer.MIN_VALUE : minRank - 1;
        ShortcutInfo si = build(pkg, userId, myRank);
        if (si == null) return false;
        try {
            StringBuilder dropped = new StringBuilder();
            int max = maxItems();
            while (list.size() >= max) {
                ShortcutInfo last = list.remove(list.size() - 1);
                if (last != null) {
                    if (dropped.length() > 0) dropped.append(',');
                    dropped.append(last.getId());
                }
            }
            list.add(0, si);
            log("injected " + ID + " into " + pkg + "/u" + userId + " (pkgField=" + si.getPackage()
                    + ", rank=" + si.getRank() + ", n=" + list.size()
                    + ", dropped=[" + dropped + "], final=" + dump(list) + ")");
            return true;
        } catch (Throwable t) {
            logE("list.add failed (unmodifiable?)", t);
            return false;
        }
    }

    private static boolean hasId(List<ShortcutInfo> list) {
        if (list == null) return true;
        for (ShortcutInfo s : list) {
            if (s != null && ID.equals(s.getId())) return true;
        }
        return false;
    }

    private static String dump(List<ShortcutInfo> list) {
        StringBuilder sb = new StringBuilder("[");
        for (ShortcutInfo s : list) {
            if (s == null) continue;
            sb.append(s.getId()).append("(r=").append(s.getRank())
                    .append(",p=").append(s.getPackage()).append("),");
        }
        return sb.append(']').toString();
    }

    private static ShortcutInfo build(String pkg, int userId, int rank) {
        Context sys = systemContext();
        if (sys == null) {
            logE("no system context", null);
            return null;
        }
        UserHandle uh = userHandle(userId);
        Context uctx = null;
        try {
            Method m = Context.class.getMethod("createPackageContextAsUser",
                    String.class, int.class, UserHandle.class);
            m.setAccessible(true);
            uctx = (Context) m.invoke(sys, pkg, 0, uh);
        } catch (Throwable t) {
            logE("createPackageContextAsUser failed: " + pkg, t);
        }
        if (uctx == null) {
            try {
                uctx = sys.createPackageContext(pkg, 0);
            } catch (Throwable t) {
                logE("createPackageContext failed: " + pkg, t);
            }
        }
        if (uctx == null) uctx = sys;

        try {
            ShortcutInfo.Builder b = new ShortcutInfo.Builder(uctx, ID);
            b.setShortLabel(LABEL);
            b.setLongLabel(LABEL);
            Icon icon = iconFor(sys, pkg);
            if (icon != null) b.setIcon(icon);
            b.setIntent(trampoline(pkg, userId));
            ComponentName act = mainActivity(sys, pkg);
            if (act != null) b.setActivity(act);
            b.setRank(rank >= 0 ? rank : RANK);
            try {
                b.setLongLived(true);
            } catch (Throwable ignored) {
            }
            ShortcutInfo si = b.build();
            if (!pkg.equals(si.getPackage())) fixup(si, pkg, userId);
            return si;
        } catch (Throwable t) {
            logE("build ShortcutInfo failed: " + pkg, t);
            return null;
        }
    }

    /** 目标包自己的启动图标（资源型 Icon，随 ShortcutInfo 一起过 binder） */
    private static Icon iconFor(Context sys, String pkg) {
        try {
            ApplicationInfo ai = sys.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai != null && ai.icon != 0) return Icon.createWithResource(pkg, ai.icon);
        } catch (Throwable ignored) {
        }
        try {
            Drawable d = sys.getPackageManager().getApplicationIcon(pkg);
            if (d != null) {
                int w = Math.max(1, d.getIntrinsicWidth());
                int h = Math.max(1, d.getIntrinsicHeight());
                Bitmap bmp = Bitmap.createBitmap(Math.min(w, 192), Math.min(h, 192),
                        Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, bmp.getWidth(), bmp.getHeight());
                d.draw(c);
                return Icon.createWithBitmap(bmp);
            }
        } catch (Throwable ignored) {
        }
        File f = iconFile();
        return f != null ? Icon.createWithFilePath(f.getAbsolutePath()) : null;
    }

    private static ComponentName mainActivity(Context sys, String pkg) {
        try {
            Intent i = sys.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) return i.getComponent();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 兜底：直接改私有字段（Builder 用了错误包名的 Context 时） */
    private static void fixup(ShortcutInfo si, String pkg, int userId) {
        try {
            Field f = ShortcutInfo.class.getDeclaredField("mPackageName");
            f.setAccessible(true);
            f.set(si, pkg);
            Field g = ShortcutInfo.class.getDeclaredField("mUserId");
            g.setAccessible(true);
            g.set(si, userId);
            log("fixup package field -> " + pkg + "/u" + userId);
        } catch (Throwable t) {
            logE("fixup failed", t);
        }
    }

    /** 桌面点这条 shortcut 时真正被启动的东西：模块自己的透明中转页 */
    private static Intent trampoline(String pkg, int userId) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(new ComponentName(HOST_PKG, HOST_ACT));
        i.putExtra(EXTRA_PKG, pkg);
        i.putExtra(EXTRA_USER, userId);
        ComponentName target = mainActivity(systemContext(), pkg);
        // 注意：ShortcutInfo 构造时会走 Intent.fixUpIntentExtras 校验 extras 的
        // 可持久化类型，ComponentName 不被 PersistableBundle 支持（会抛
        // IllegalArgumentException: Bad value in PersistableBundle），
        // 因此这里序列化成 "pkg/cls" 字符串传递。
        if (target != null) i.putExtra(EXTRA_COMPONENT, target.flattenToString());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return i;
    }

    /**
     * 取「与当前 Binder 调用者 uid 匹配」的 Context 作为启动方。
     *
     * <p>system_server 里 {@code ContextImpl.startActivity} 会把自身 basePackageName 当
     * callingPackage 上报（systemContext 下是 {@code "android"}），ATMS 会校验 callingPackage
     * 是否属于 callingUid（这里是发起 {@code startShortcut} 的桌面，如 uid 10146），不匹配就抛
     * {@code SecurityException: Permission Denial: package=android does not belong to uid=…}。
     * 换成调用者 uid 自己拥有的包来建 PackageContext，校验即通过。</p>
     *
     * @return 可直接用于 startActivity 的 Context；无法改善时返回 null（调用方保持原 ctx）
     */
    private static Context callerContext(Context sys) {
        if (sys == null) return null;
        int uid;
        try {
            uid = Binder.getCallingUid();
        } catch (Throwable ignored) {
            return null;
        }
        // 非 binder 线程（自己就在 system_server 主线程）→ uid 1000，与 "android" 天然匹配
        if (uid == android.os.Process.SYSTEM_UID || uid == 0) return null;
        try {
            String[] pkgs = sys.getPackageManager().getPackagesForUid(uid);
            if (pkgs != null) {
                for (String p : pkgs) {
                    if (p == null || "android".equals(p)) continue;
                    try {
                        return sys.createPackageContext(p, 0);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 以小窗参数启动目标应用。
     *
     * <p>与侧边栏「小窗」快捷项完全同一条链路：{@link SecurityDiffFeatures#smallWindowOptions}
     * 取 MIUI 的 ActivityOptions，再交给 {@code startActivity}。调用方必须是系统进程
     * （system_server / SystemUI）才会被 MIUI 认，所以 {@link #hookStartShortcutInner}
     * 在 system_server 里就地启动。</p>
     *
     * @return true 表示已发起启动
     */
    private static boolean launchFreeform(Context ctx, String pkg) {
        if (ctx == null || pkg == null || isHost(pkg)) return false;
        log("launchFreeform pkg=" + pkg + " callingUid=" + Binder.getCallingUid());
        Intent launch = null;
        try {
            launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        } catch (Throwable ignored) {
        }
        if (launch == null) {
            ComponentName cn = mainActivity(ctx, pkg);
            if (cn == null) {
                logE("no launch intent for " + pkg, null);
                return false;
            }
            launch = new Intent(Intent.ACTION_MAIN).setComponent(cn);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 关键：清掉可能被 ShortcutInfo 带进来的「重新定向到自己」标志
        launch.removeExtra(EXTRA_PKG);
        try {
            Bundle opts = null;
            try {
                opts = SecurityDiffFeatures.smallWindowOptions(
                        LauncherShortcut.class.getClassLoader(), ctx, pkg);
            } catch (Throwable t) {
                logE("smallWindowOptions failed: " + pkg, t);
            }
            // 关键：本方法跑在桌面的 binder 线程里，Binder.getCallingUid() 是桌面 uid，
            // 而 systemContext 上报的 callingPackage 是 "android"，ATMS 会校验
            // 「callingPackage 必须属于 callingUid」→ SecurityException:
            //   Permission Denial: package=android does not belong to uid=10146
            // 临时清掉调用者身份，ATMS 便视其为 system_server(uid 1000) 自身发起的启动。
            long ident = Binder.clearCallingIdentity();
            try {
                if (opts != null) {
                    log("freeform start " + pkg + " opts=" + opts.keySet()
                            + " startUid=" + Binder.getCallingUid());
                    ctx.startActivity(launch, opts);
                } else {
                    log("freeform fallback (no options) " + pkg);
                    ctx.startActivity(launch);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return true;
        } catch (Throwable t) {
            logE("startActivity failed: " + pkg, t);
            return false;
        }
    }

    // ───────────────────── 工具 ─────────────────────

    /** UserHandle → userId（getIdentifier 是隐藏 API，用反射；兜底读私有字段 mHandle） */
    private static int userIdOf(UserHandle uh) {
        if (uh == null) return 0;
        try {
            Method m = UserHandle.class.getMethod("getIdentifier");
            m.setAccessible(true);
            Object v = m.invoke(uh);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {
        }
        try {
            Field f = UserHandle.class.getDeclaredField("mHandle");
            f.setAccessible(true);
            return f.getInt(uh);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static UserHandle userHandle(int userId) {
        try {
            Method m = UserHandle.class.getMethod("of", int.class);
            m.setAccessible(true);
            return (UserHandle) m.invoke(null, userId);
        } catch (Throwable t) {
            return android.os.Process.myUserHandle();
        }
    }

    private static Context systemContext() {
        if (sSysCtx != null) return sSysCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            Method gs = at.getMethod("getSystemContext");
            gs.setAccessible(true);
            sSysCtx = (Context) gs.invoke(thread);
        } catch (Throwable t) {
            logE("systemContext failed", t);
        }
        return sSysCtx;
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    /** 参数里挑包名：能被 PackageManager 解析到的字符串就是包名 */
    private static boolean looksLikePackage(String s) {
        if (s == null || s.indexOf('.') < 0 || s.length() > 128) return false;
        Context ctx = systemContext();
        if (ctx == null) return false;
        try {
            ctx.getPackageManager().getApplicationInfo(s, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 宿主包（桌面 / SystemUI / 模块自身）不算「目标应用」 */
    private static boolean isHost(String pkg) {
        return HOST_PKG.equals(pkg)
                || "com.miui.home".equals(pkg)
                || "com.android.systemui".equals(pkg)
                || "android".equals(pkg);
    }

    /** ParceledListSlice → List（反射：该类不在公开 API 里） */
    @SuppressWarnings("unchecked")
    private static List<ShortcutInfo> sliceList(Object slice) {
        if (slice instanceof List) return (List<ShortcutInfo>) slice;
        if (slice == null) return null;
        try {
            Method m = slice.getClass().getMethod("getList");
            m.setAccessible(true);
            Object r = m.invoke(slice);
            return r instanceof List ? (List<ShortcutInfo>) r : null;
        } catch (Throwable t) {
            logE("ParceledListSlice.getList failed", t);
            return null;
        }
    }

    /** 用同类型重建一个 ParceledListSlice */
    private static Object newSlice(List<ShortcutInfo> orig, List<ShortcutInfo> modified) {
        try {
            Class<?> cls = Class.forName("android.content.pm.ParceledListSlice",
                    false, orig.getClass().getClassLoader());
            java.lang.reflect.Constructor<?> c = cls.getDeclaredConstructor(List.class);
            c.setAccessible(true);
            return c.newInstance(modified);
        } catch (Throwable t) {
            logE("new ParceledListSlice failed", t);
            return null;
        }
    }

    /**
     * 从 system_server 自己的 Binder 服务实例反查 services.jar 的 ClassLoader。
     *
     * <p>实测 {@code ActivityThread.getSystemContext().getClassLoader()} 返回的是空的
     * {@code PathClassLoader[[directory "."]]}（BOOTCLASSPATH 不含 services.jar），
     * 用它 {@code Class.forName} 任何 {@code com.android.server.*} 类都是 ClassNotFound。</p>
     *
     * <p>但 system_server 内的服务实现类本身（{@code ShortcutService$LocalService}、
     * {@code PackageManagerService$IPackageManagerImpl}、{@code LauncherAppsService$LauncherAppsImpl}）
     * 就是从 services.jar / miui-services.jar 加载的，实例的 ClassLoader 必然正确。
     * {@code ServiceManager} / {@code IBinder} 都在 BOOTCLASSPATH 内，反射调用即可。</p>
     */
    static ClassLoader binderServiceLoader() {
        String[][] probes = new String[][] {
                {"shortcut", "android.content.pm.IShortcutService"},
                {"package", "android.content.pm.IPackageManager"},
                {"launcherapps", "android.content.pm.ILauncherApps"}
        };
        for (String[] p : probes) {
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                java.lang.reflect.Method get = sm.getMethod("getService", String.class);
                Object b = get.invoke(null, p[0]);
                if (b == null) continue;
                android.os.IBinder ib = (android.os.IBinder) b;
                Object iface = ib.queryLocalInterface(p[1]);
                Object target = iface != null ? iface : ib;
                ClassLoader l = target.getClass().getClassLoader();
                if (l != null) {
                    log("binderServiceLoader via " + p[0] + ": " + l);
                    return l;
                }
            } catch (Throwable t) {
                logE("binderServiceLoader " + p[0] + " failed", t);
            }
        }
        logE("binderServiceLoader: all probes failed", null);
        return null;
    }

    private static Class<?> findClass(ClassLoader cl, String name) {
        ClassLoader[] loads = new ClassLoader[] {
                binderServiceLoader(),
                systemServerLoader(),
                ClassLoader.getSystemClassLoader(),
                cl,
                cl == null ? null : cl.getParent(),
                Thread.currentThread().getContextClassLoader(),
                LauncherShortcut.class.getClassLoader()
        };
        for (ClassLoader l : loads) {
            if (l == null) continue;
            try {
                Class<?> c = Class.forName(name, false, l);
                log("findClass ok: " + name + " via " + l);
                return c;
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> c = Class.forName(name);
            log("findClass ok(default): " + name);
            return c;
        } catch (Throwable ignored) {
            logE("findClass FAIL: " + name + " (all loaders)", null);
            return null;
        }
    }

    /**
     * system_server 真正的 ClassLoader（含 services.jar / miui-services.jar）。
     *
     * <p>libxposed 的 {@code SystemServerStartingParam.getClassLoader()} 在部分版本
     * 返回的是模块自身的空 PathClassLoader（DexPathList 只有 "."，实测加载
     * {@code com.android.server.pm.ShortcutService} 直接 ClassNotFound）。
     * 真正的 loader 藏在 system_server 的「系统 Context」里：
     * {@code ActivityThread.sCurrentActivityThread.getSystemContext().getClassLoader()}，
     * 它由 SystemServer 启动时用 {@code --runtime-classpath} 构造，含 services.jar。</p>
     */
    static ClassLoader systemServerLoader() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Field f = at.getDeclaredField("sCurrentActivityThread");
            f.setAccessible(true);
            Object inst = f.get(null);
            if (inst == null) return null;
            java.lang.reflect.Method gm = at.getDeclaredMethod("getSystemContext");
            gm.setAccessible(true);
            Object ctx = gm.invoke(inst);
            if (ctx == null) return null;
            ClassLoader l = ((android.content.Context) ctx).getClassLoader();
            if (l != null && !l.getClass().getName().contains("InjectedModule")) {
                log("systemServerLoader via systemContext: " + l);
                return l;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hookSafe(XposedModule module, Method m, final FreeformHooks.HookHandler h) {
        try {
            m.setAccessible(true);
            module.hook(m).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    return h.handle(chain);
                }
            });
        } catch (Throwable t) {
            logE("hook failed: " + m.getDeclaringClass().getName() + "#" + m.getName(), t);
        }
    }

    // ───────────────────── 图标文件 ─────────────────────

    private static File iconFile() {
        File f = new File(ICON_FILE);
        return (f.exists() && f.length() > 0) ? f : null;
    }

    /** 渲染一条「小窗」图标（96x96 PNG）到 /data/system，失败则无图标（退化为应用图标） */
    private static void ensureIconFile() {
        try {
            File f = new File(ICON_FILE);
            if (f.exists() && f.length() > 0) return;
            Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF2A2D33);
            c.drawRoundRect(new RectF(0, 0, 96, 96), 22, 22, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5);
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(new RectF(14, 16, 82, 60), 6, 6, p);
            c.drawLine(38, 76, 58, 76, p);
            c.drawLine(48, 60, 48, 76, p);
            FileOutputStream out = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
            log("icon rendered -> " + ICON_FILE + " (" + f.length() + "B)");
        } catch (Throwable t) {
            logE("render icon failed", t);
        }
    }
}
