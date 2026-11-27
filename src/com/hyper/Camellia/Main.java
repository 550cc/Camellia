package com.hyper.Camellia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.MatchType;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Camellia — 版本自适应的 Xposed / LSPosed 模块
 *
 * 目标：把「互传接收文件后，通知栏点『打开』」的落点改成 MT 管理器。
 *   - 文档 / 音频 / 未知类型   -> MT 管理器（互传目录）
 *   - 图片 / 视频 / 联系人(vcf) -> 保持系统原有行为
 *
 * 为什么能适配未来版本：
 *   互传的逻辑类是混淆的（类名/方法名每版都变），但有两类东西稳定：
 *     1) 框架类型 android.content.Context / android.content.Intent —— 永不混淆；
 *     2) 数据模型类 com.miui.mishare.file.FileInfo —— 字段名稳定
 *        （mimeType / directory / path / name）。
 *   因此运行时用「签名形状」定位 Hook 点：
 *     找【返回 android.content.Intent】且【参数含 FileInfo[]】的静态方法，
 *     取参数最多的那个（即文件分流中枢；通知构建与点击回调都走它）。
 *   为不依赖被隐藏的 API，本模块自带极简 dex 解析器，直接从 APK 的
 *   classes*.dex 扫描方法表；找不到时再退回「已知类名」兜底。
 */
public class Main extends XposedModule {

    private static final String TAG = "MiShareMT";

    // ───────────────────── 热重载（libxposed API 102）状态 ─────────────────────
    /**
     * 当前进程的包名 / 目标 ClassLoader / ApplicationInfo。
     *
     * <p>热重载的时序是：框架先调用<b>旧代码</b>的 {@code onHotReloading}，
     * 再换掉模块 dex 与 ClassLoader，最后由<b>新代码</b>执行 {@code onHotReloaded}。
     * 所以旧类实例能读到下面这三个静态值，而新类实例里的静态字段是全新的（全为 null），
     * 只能靠 {@code setSavedInstanceState} 把值交回框架保管 —— 这也是唯一的跨重载通道。</p>
     */
    private static String sCurPkg;
    private static ClassLoader sCurCl;
    private static ApplicationInfo sCurAi;
    /**
     * 调试模式（默认关，由设置页的「调试模式」开关经远程偏好下发）。
     *
     * 关：INFO / DEBUG 级日志静默，只保留 WARN / ERROR（真出问题仍能从 logcat 看到）。
     * 开：全量日志。
     */
    private static volatile boolean DEBUG = false;
    /** 主业务线（互传）是否已安装：onPackageLoaded 与 onPackageReady 双回调，必须幂等 */
    private static volatile boolean sInstalledMain;
    /** 上次刷新 DEBUG 的时间，避免每次日志都去读远程偏好 */
    private static volatile long sDebugAt = 0L;

    /** Hook 作用域：小米互传 */
    private static final String TARGET_PKG = "com.miui.mishare.connectivity";
    /** 稳定的数据模型类（字段名稳定，可作锚点） */
    private static final String FILEINFO_CLASS = "com.miui.mishare.file.FileInfo";
    /** 已知历史版本的兜底类名（仅当 dex 扫描失败时使用） */
    private static final String[] FALLBACK_CLASSES = {
            "com.miui.mishare.view.e",
            "com.miui.mishare.view.f",
            "com.miui.mishare.view.d",
    };

    /**
     * MT 管理器「跳转到目录」Intent 协议。
     *
     * 关键：MT 的 ShortcutActivity **没有 intent-filter**，只能用显式组件调用；
     * 而且分身/克隆版可能根本没有这个组件（实测 bin.mt.plus.canary 2.26.4-clone 就没有），
     * 所以组件名必须在运行时枚举包内 activity 得到，不能按包名拼字符串。
     */
    private static final String MT_ACTION_SUFFIX = ".ACTION_SHORTCUT";
    /** MT 跳转入口的类名末段 */
    private static final String MT_ACTIVITY_SIMPLE = "ShortcutActivity";
    private static final int MT_FLAGS = 0x34000000; // NEW_TASK | CLEAR_TOP | SINGLE_TOP

    /**
     * 「打开目录」锚点（4.x legacy 家族）—— 分流中枢之外的第二条路。
     *
     * 互传把「打开接收到的文件 / 目录」交给文件管理器时，会经由一个独立构造点：
     *   4.0.5  com.miui.mishare.view.d#f(Context)Intent
     *   4.13.8 com.miui.mishare.view.d#g(Context, boolean, TapRecvData, boolean)Intent
     *   5.3.4  com.miui.mishare.view.e#h(Context, boolean, TapRecvData, boolean)Intent
     *   5.4.2  com.miui.mishare.view.e#k(Context, boolean, TapRecvData, boolean)Intent
     * 共同特征：private static + 返回 android.content.Intent + 使用字符串
     * "com.android.fileexplorer.tapShare.START_TRANSFER" / "get explorer intent!"。
     *
     * 为什么必须单独挂：中枢在「不是恰好一个文件」时（多文件接收 / 点「打开文件夹」）
     * 不会自己拼 Intent，而是直接调用这个私有静态构造点返回「打开目录」的 Intent；
     * 而中枢 hook 在 FileInfo[] 为 null / 长度 0 时保持原行为 → 只挂中枢就漏掉这条路径，
     * 表现就是「点通知还是打开系统文件管理器」，4.x 上尤其明显。
     *
     * xref 实测（MT MCP dispatch，ix = 指令序号）：
     *   4.0.5  view.d#d -> view.d#f (98)、 view.d#i -> view.d#f (223)
     *   4.13.8 view.d#e -> view.d#g (120)、view.d#j -> view.d#g (377)
     *   5.4.2  view.e#i -> view.e#k (128)、view.e#q -> view.e#k (383)
     * 又因为 Xposed 对嵌套调用同样生效，内层 hook 会先返回 MT Intent，
     * 从外侧「绕过」中枢的 keep 策略 —— 这就是补挂一层即可修好 4.x 的原因。
     */
    private static final String STR_EXPLORER_ACTION = "com.android.fileexplorer.tapShare.START_TRANSFER";
    private static final String STR_EXPLORER_LOG = "get explorer intent!";
    /** 目录构造点最多挂几个 */
    private static final int MAX_DIR_ANCHORS = 3;
    /** 原 Intent 里可能带接收目录信息的 extra key */
    private static final String[] DIR_EXTRA_KEYS = {
            "explorer_path", "path", "dir", "directory",
    };

    /**
     * 已探测过的 MT 包 -> ShortcutActivity 完整类名（空串代表该包不支持跳转）。
     *
     * <p>用 ConcurrentHashMap：这个表的读写发生在宿主各线程的 hook 回调里
     * （每次点「打开」都会查一次），普通 HashMap 并发 put 会触发 rehash 竞争，
     * 轻则读到旧值、重则链表成环。查表本身是纯内存操作，不引入任何锁竞争。</p>
     */
    private final Map<String, String> mShortcutCache = new ConcurrentHashMap<>();

    /**
     * MT 包名缓存（1s TTL）。
     *
     * 每点一次「打开」都会调 pickMtShortcut -> mtPackage，
     * 而原始实现每次都要：拿框架能力 -> 读远程偏好 -> 再依次 stat 4 条
     * /sdcard 侧车路径并读文件。1s 内复用既保留了「设置改完立即生效」，
     * 又不会在连续点击时反复做 FUSE 文件 IO。
     */
    private static final long MT_PKG_TTL_MS = 1000;
    private String mMtPkg;
    private long mMtPkgAt;
    /** 最近一次解析成功的 MT 组件（同一偏好包名时直接用，省掉枚举/查找） */
    private ComponentName mComp;
    private String mCompFor;

    // ---- 性能埋点：只做计数与计时，不改变任何判据 ----
    /** DexKitBridge.create 累计耗时（ms）—— 重复创建 bridge = 重复解析 dex */
    private static volatile long sBridgeMs;
    /** DexKit findMethod 累计耗时（ms） */
    private static volatile long sDexKitQueryMs;
    /** 拦截次数 / 累计耗时 / 单次最大耗时 */
    private static volatile long sInterceptCount;
    private static volatile long sInterceptTotalMs;
    private static volatile long sInterceptMaxMs;
    /** 最近一次写盘时刻（拦截统计节流用） */
    private static volatile long sLastPerfWrite;
    /** 最近一次 status 正文（拦截后节流重写用） */
    private static volatile String sStatusBody = "";

    /** 热路径自检结果行（空串 = 未运行） */
    private static volatile String sSelfTestNote = "";

    /**
     * 复用的 DexKit bridge。
     *
     * DexKitBridge.create() 要打开并解析目标 dex（5.4.2 的 classes.dex 有 1.3 万个类），
     * 而模块需要跑两个查询（中枢 + 目录构造点）。此前两个查询各建一次 bridge，
     * 等于把同一份 dex 解析两遍 —— 这是启动定位耗时里可以省掉的那一半。
     */
    private DexKitBridge mBridge;
    private String mBridgePath;

    /** 图片 / 视频 / 联系人扩展名（命中则保持原样） */
    private static final List<String> KEEP_EXTS = Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "dng", "avif",
            "tif", "tiff", "svg", "wbmp", "xbm", "xpm", "avci", "jfif",
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "3gpp", "m4v",
            "mpeg", "mpg", "ts", "rmvb", "rm", "asf", "f4v",
            "vcf"
    );

    public Main() {
        super();
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        probe("onModuleLoaded", getFrameworkName() + " api=" + getApiVersion());
        // 模块被框架加载时（仍在模块自身进程），打印一次框架能力，
        // 便于判断 remote preferences 是否可用。
        try {
            long caps = getFrameworkProperties();
            boolean remote = (caps & PROP_CAP_REMOTE) != 0;
            lg(Log.INFO, TAG, "framework=" + getFrameworkName() + " " + getFrameworkVersion()
                    + " api=" + getApiVersion()
                    + " caps=0x" + Long.toHexString(caps)
                    + " remotePrefsSupported=" + remote);
            if (remote) {
                SharedPreferences p = getRemotePreferences(Prefs.GROUP);
                lg(Log.INFO, TAG, "module-side remote prefs=" + (p == null ? "" : "ok")
                        + ", mt_target=" + (p == null ? "-" : p.getString(Prefs.KEY_MT_TARGET, "<unset>")));
            }
            if (remote) {
                try {
                    String[] files = listRemoteFiles();
                    lg(Log.INFO, TAG, "remote files=" + (files == null ? "" : Arrays.toString(files)));
                } catch (Throwable t) {
                    dbg("listRemoteFiles failed: " + t);
                }
            }
        } catch (Throwable t) {
            lg(Log.WARN, TAG, "framework probe failed", t);
        }
    }

    /**
     * 日志门控：调试模式关闭时 INFO / DEBUG 一律不打（默认行为）。
     * WARN / ERROR 始终输出，保证真出问题时有线索。
     */
    /**
     * 诊断探针：无条件把「哪个进程 / 哪个回调 / 什么包名」追加到固定文件。
     *
     * 用途：确认 LSPosed 是否真的把本模块注入到目标进程。
     * 不受调试开关影响，也不会因为异常而干扰主流程。
     */
    private static final int PROBE_MAX_BYTES = 256 * 1024;

    /**
     * 写一行启动探针。
     *
     * 注意：本方法会在 onModuleLoaded / onPackageLoaded / onPackageReady 等
     * 进程启动关键路径上被调用，而 /sdcard 是 FUSE 用户态文件系统，
     * 启动期同步读写可能长时间阻塞，直接把宿主进程拖成
     * "failed to complete startup" ANR（手机管家白屏卡死的根因之一）。
     * 因此这里只组装文本，真正的落盘丢给后台线程。
     */
    static void probe(String where, String detail) {
        final String line;
        try {
            line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(new java.util.Date())
                    + " pid=" + android.os.Process.myPid()
                    + " uid=" + android.os.Process.myUid()
                    + " " + where
                    + " | " + detail
                    + "\n";
        } catch (Throwable t) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.File d = new java.io.File("/sdcard/Android/media/com.hyper.Camellia");
                    if (!d.isDirectory()) {
                        d.mkdirs();
                    }
                    // 双写：/sdcard 便于用户直接查看，/data/system 则是 system_server
                    // 唯一必然可写的位置（system_server 没有 /sdcard 写权限，且 FUSE 在
                    // 启动期可能尚未就绪——这正是「system 侧探针一条都没有」的原因之一）。
                    writeProbe(new java.io.File(d, "probe.txt"), line);
                    writeProbe(new java.io.File("/data/system/camellia_probe.txt"), line);
                } catch (Throwable ignored) {
                }
            }
        }, "camellia-probe");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** 单文件追加一行探针；任何异常都吞掉，绝不影响宿主进程。 */
    private static void writeProbe(java.io.File f, String line) {
        try {
            if (f.length() > PROBE_MAX_BYTES) {
                f.delete();
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write(line.getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private void lg(int prio, String tag, String msg) {
        if (prio < Log.WARN && !DEBUG) {
            return;
        }
        log(prio, tag, msg);
    }

    private void lg(int prio, String tag, String msg, Throwable t) {
        if (prio < Log.WARN && !DEBUG) {
            return;
        }
        log(prio, tag, msg, t);
    }

    /** 从设置页（远程偏好）同步调试开关，5 秒 TTL；在拦截热路径上被顺带调用 */
    private void syncDebugFlag() {
        long now = SystemClock.uptimeMillis();
        if (sDebugAt != 0L && now - sDebugAt < 5000L) {
            return;
        }
        sDebugAt = now;
        try {
            SharedPreferences p = getRemotePreferences(Prefs.GROUP);
            if (p != null) {
                DEBUG = p.getBoolean(Prefs.KEY_FG_DEBUG, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读取设置页选定的 MT 包名。
     *
     * 本类运行在**小米互传进程**里，读不到模块 App 自己的 SharedPreferences，
     * 依次尝试：
     *   1) 框架的远程偏好（需要框架具备 REMOTE 能力）
     *   2) Settings.System 公共键（设备级，任何进程可读，强兜底）
     *   3) MT 正式版（最终兜底，保证功能仍可用）
     */
    private String mtPackage() {
        syncDebugFlag();
        String cached = mMtPkg;
        long now = SystemClock.uptimeMillis();
        if (cached != null && now - mMtPkgAt < MT_PKG_TTL_MS) {
            return cached;
        }
        String v = readMtFromRemote();
        if (v == null) {
            v = readMtFromSystem();
        }
        if (v == null) {
            v = Prefs.MT_DEFAULT;
            dbg("mt package -> default (nothing readable)");
        }
        mMtPkg = v;
        mMtPkgAt = now;
        return v;
    }

    /**
     * 框架能力位（PROP_CAP_*）在进程生命周期内是常量：模块加载时由框架固定下来，
     * 运行期不会变。但 {@code getFrameworkProperties()} 是一次跨进程 binder 调用，
     * 而 {@link #readMtFromRemote} 会被「点开一次」的 1s TTL 反复触发 ——
     * 缓存后热路径不再每次都做 IPC。
     */
    private volatile long mCaps = -1L;

    /** 取框架能力位：首次查询后缓存；查询失败返回 -1 且不缓存（下次重试） */
    private long caps() {
        long v = mCaps;
        if (v < 0L) {
            try {
                v = getFrameworkProperties();
            } catch (Throwable t) {
                dbg("getFrameworkProperties failed: " + t);
                return -1L;
            }
            mCaps = v;
        }
        return v;
    }

    /** 方式 1：框架远程偏好 */
    private String readMtFromRemote() {
        long caps = caps();
        if (caps >= 0L && (caps & PROP_CAP_REMOTE) == 0) {
            dbg("framework lacks REMOTE capability, caps=0x" + Long.toHexString(caps));
            return null;
        }
        try {
            SharedPreferences p = getRemotePreferences(Prefs.GROUP);
            if (p == null) {
                dbg("remote prefs is null");
                return null;
            }
            String v = p.getString(Prefs.KEY_MT_TARGET, null);
            dbg("remote mt_target=" + v);
            if (Prefs.MT_CANARY.equals(v)) {
                return Prefs.MT_CANARY;
            }
            if (Prefs.MT_DEFAULT.equals(v)) {
                return Prefs.MT_DEFAULT;
            }
            return null;
        } catch (Throwable t) {
            dbg("remote prefs read failed: " + t);
            return null;
        }
    }

    /**
     * 方式 2：Settings.System 公共键（camellia_cfg_v1）。
     *
     * <p>Settings.System 是设备级公共键值库，任何进程（含互传进程）都能读，
     * 不需要任何权限、不受分区存储限制。模块设置页每次保存都会整份写入，
     * 因此这里是「不需要框架 REMOTE 能力」的强兜底通道。</p>
     *
     * <p>旧实现读的是 /sdcard/Android/media|data/&lt;pkg&gt;/misharemt2.ini —— 那是
     * 模块 app 的私有外部目录，属主为模块 uid、权限 0660，互传进程根本读不到，
     * 等于永远走默认值。该路径已彻底废弃。</p>
     */
    private String readMtFromSystem() {
        try {
            Context c = procCtx();
            if (c == null) {
                dbg("system cfg: no process context yet");
                return null;
            }
            // provider 优先、Settings.System 兜底（详见 Prefs.readCrossProcCfg）
            String text = Prefs.readCrossProcCfg(c);
            if (text == null || text.length() == 0) {
                return null;
            }
            String v = parseSidecar(text);
            dbg("system cfg -> " + v);
            return v;
        } catch (Throwable t) {
            dbg("system cfg read failed: " + t);
            return null;
        }
    }

    /** 目标进程的 Application Context（只用于读 Settings.System 公共键） */
    private Context mProcCtx;

    private Context procCtx() {
        Context c = mProcCtx;
        if (c != null) {
            return c;
        }
        try {
            Method cur = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication");
            Object app = cur.invoke(null);
            if (app instanceof Context) {
                mProcCtx = (Context) app;
                return mProcCtx;
            }
        } catch (Throwable t) {
            dbg("procCtx failed: " + t);
        }
        return null;
    }

    /** 模块自身包名（用于拼侧车文件路径） */
    private String modulePkg() {
        try {
            ApplicationInfo ai = getModuleApplicationInfo();
            if (ai != null && ai.packageName != null) {
                return ai.packageName;
            }
        } catch (Throwable ignored) {
        }
        return "com.hyper.Camellia";
    }

    /** 解析侧车文件的 key=value 文本 */
    private String parseSidecar(String text) {
        if (text == null) {
            return null;
        }
        String best = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (Prefs.SIDECAR_KEY_MT.equals(k)) {
                if (Prefs.MT_CANARY.equals(v)) {
                    return Prefs.MT_CANARY;
                }
                if (Prefs.MT_DEFAULT.equals(v)) {
                    best = Prefs.MT_DEFAULT;
                }
            }
        }
        return best;
    }

    private static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // =====================================================================
    // system_server 入口（libxposed API 101+）
    // =====================================================================

    /**
     * 系统服务器启动回调 —— system_server 侧唯一会被调用的入口。
     *
     * <p>LSPosed 2.x 只对 system_server 走这条 API 101+ 的专用入口：模块声明
     * {@code minApiVersion=101} 后，框架回调本方法；旧的
     * {@code onPackageLoaded("android")} / {@code onPackageReady("android")}
     * 路径不再被触发。这正是「作用域里勾了『系统框架』，却毫无注入痕迹」的根因
     * （module.prop 原先写的是 minApiVersion=100）。</p>
     *
     * <p>因此 system_server 侧的全部 hook 必须挂在这里。旧路径保留作为兜底，
     * 各业务线的 install 自带幂等保护，重复进入不会造成双 hook。</p>
     *
     * <p>注意：这里不再用开关做安装条件。system_server 只在开机时加载模块，
     * 若安装期就判定开关，用户事后打开开关必须重启才生效；改为「无条件安装
     * hook，在回调里实时读开关」。</p>
     */
    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            ClassLoader cl = param.getClassLoader();
            probe("onSystemServerStarting", "cl=" + (cl != null));
            lg(Log.WARN, TAG, "[Camellia] onSystemServerStarting cl=" + cl);
            if (cl == null) {
                lg(Log.ERROR, TAG, "onSystemServerStarting: null classloader, skip");
                return;
            }
            FreeformHooks.installAndroid(this, cl, new FreeformHooks.PrefProvider() {
                @Override
                public SharedPreferences prefs() {
                    return getRemotePreferences(Prefs.GROUP);
                }
            });
            // 救援体系已整体交给 camd（root 常驻二进制 + /data/adb/camellia），
            // 之前的 Java 侧 SysRescueGuard / SysUiWatch 于 v1.9 移除：
            // 进程内探测 SystemUI 死活本身就是矛盾设计（探测者与被探测者同进程族），
            // 且崩溃窗口内取证的代码路径极难在真机验证。这里不再挂任何救援 hook。
        } catch (Throwable t) {
            lg(Log.ERROR, TAG, "onSystemServerStarting failed", t);
        }
    }

    @Override
    /**
     * 应用真正就绪回调（安装 hook 的兜底路径）。
     *
     * onPackageLoaded 阶段的 {@code getDefaultClassLoader()} 在部分进程会返回 null
     * （此时应用类加载器尚未建立，手机管家各进程就是这种情况），
     * 因此这里改用 {@code PackageReadyParam#getClassLoader()} 再安装一次。
     * 各业务线的 install 自身带幂等保护，重复进入不会造成双 hook。
     */
    public void onPackageReady(PackageReadyParam param) {
        try {
            String pkg = param.getPackageName();
            ClassLoader cl = param.getClassLoader();
            probe("onPackageReady", pkg + " cl=" + (cl != null));
            dispatchInstall(pkg, cl, param.getApplicationInfo());
        } catch (Throwable t) {
            lg(Log.ERROR, TAG, "onPackageReady failed", t);
        }
    }

    /**
     * 按包名分发安装 —— {@code onPackageReady} 与 {@code onHotReloaded} 共用同一套分支。
     *
     * <p>热重载后必须重跑一遍：框架的热重载只替换模块 dex 与模块 ClassLoader，
     * 目标进程里挂着的仍是旧代码注册的 hook。不重新 install 的话，
     * 新代码的逻辑永远不会生效，旧 hook 还会继续按旧逻辑跑。</p>
     */
    private void dispatchInstall(String pkg, ClassLoader cl, ApplicationInfo ai) {
        if (cl == null || pkg == null) {
            // 绝不静默失败：热重载后若拿不到目标 CL，会留下「旧 hook 已撤、新 hook 未装」的空窗，
            // 这个进程从此不受保护，必须留下可查证的日志。
            lg(Log.ERROR, TAG, "[Camellia] dispatchInstall skipped: pkg=" + pkg
                    + " cl=" + (cl != null));
            return;
        }
        // 记录本进程的安装现场，供 onHotReloading 交给框架保管
        sCurPkg = pkg;
        sCurCl = cl;
        sCurAi = ai;
        if (Fg.PKG.equals(pkg)) {
            Fg.install(this, cl);
            return;
        }
            if (Unlock.matchGuardPkg(pkg)) {
                // 系统安全组件（com.miui.guardprovider）：独立入口，只装 gp_* 七条（gp_dbguard 挂手机管家），
                // 不复用手机管家的签名缓存（两套规则集会互相判为「不完整」）。
                lg(Log.INFO, TAG, "onPackageReady pkg=" + pkg + " (系统安全组件)");
                Unlock.installGuardProvider(this, cl, ai, pkg,
                        new Unlock.PrefProvider() {
                            @Override
                            public SharedPreferences prefs() {
                                return getRemotePreferences(Prefs.GROUP);
                            }
                        });
                return;
            }
            if (Unlock.matchPkg(pkg)) {
                Unlock.install(this, cl, ai, pkg, new Unlock.PrefProvider() {
                    @Override
                    public SharedPreferences prefs() {
                        return getRemotePreferences(Prefs.GROUP);
                    }
                });
                return;
            }
            if ("android".equals(pkg)) {
                FreeformHooks.installAndroid(this, cl, new FreeformHooks.PrefProvider() {
                    @Override
                    public SharedPreferences prefs() {
                        return getRemotePreferences(Prefs.GROUP);
                    }
                });
                return;
            }
            if ("com.android.systemui".equals(pkg)) {
                // 注意：旧版 SysUiRescue（SystemUI 进程内取证）与后续的 SysUiWatch 均已删除。
                // 崩溃观测与处置改由 camd 承担：它是独立进程，不随 SystemUI 一起死。
                FreeformHooks.installSystemUI(this, cl, new FreeformHooks.PrefProvider() {
                    @Override
                    public SharedPreferences prefs() {
                        return getRemotePreferences(Prefs.GROUP);
                    }
                });
                return;
            }
            if (!TARGET_PKG.equals(pkg)) {
                return;
            }
            install(cl, ai);
    }

    /**
     * 热重载询问回调 —— 框架在真正热重载之前调用，<b>返回值决定放行与否</b>。
     *
     * <p>接口里这个方法是 {@code default boolean}，字节码就是 {@code return false}。
     * 换句话说：模块只要不覆写它，框架就一律判定「本模块不允许热重载」并拒绝请求 ——
     * 这正是设置页点热重载一直失败的原因。</p>
     *
     * <p>顺便把当前进程的安装现场交给框架保管：重载会替换模块的 ClassLoader，
     * 模块类里的静态字段随之重置，只有 {@code setSavedInstanceState} 交出去的对象
     * 能活到 {@code onHotReloaded}。数组元素全是 JDK / 框架类型，
     * 由 boot classloader 加载，不会出现跨 ClassLoader 的类型不匹配。</p>
     */
    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        String pkg = sCurPkg;
        ClassLoader cl = sCurCl;
        ApplicationInfo ai = sCurAi;
        probe("onHotReloading", "pkg=" + pkg + " cl=" + (cl != null));
        lg(Log.WARN, TAG, "[Camellia] onHotReloading pkg=" + pkg + " cl=" + (cl != null));
        try {
            param.setSavedInstanceState(new Object[]{pkg, cl, ai});
        } catch (Throwable t) {
            lg(Log.ERROR, TAG, "onHotReloading: setSavedInstanceState failed", t);
        }
        // 必须显式返回 true，否则框架直接拒绝热重载
        return true;
    }

    /**
     * 热重载完成回调 —— 撤销旧 hook，再用新代码重新安装。
     *
     * <p>框架不会自动撤销旧 hook，{@code getOldHookHandles()} 只是把句柄交给我们处理。
     * 不先 unhook 就重新 install，同一方法会被新旧两份 hook 同时拦截。</p>
     *
     * <p>安装期缓存（签名缓存、DexKit 结果、幂等标志）都随旧 ClassLoader 一起废弃，
     * 新类的静态字段天然是空的，因此重装时不会读到过期判断。</p>
     */
    @Override
    public void onHotReloaded(HotReloadedParam param) {
        String pkg = sCurPkg;
        ClassLoader cl = sCurCl;
        ApplicationInfo ai = sCurAi;
        Object saved = null;
        try {
            saved = param.getSavedInstanceState();
        } catch (Throwable ignored) {
        }
        if (saved instanceof Object[]) {
            Object[] a = (Object[]) saved;
            if (a.length == 3) {
                if (a[0] instanceof String) {
                    pkg = (String) a[0];
                }
                if (a[1] instanceof ClassLoader) {
                    cl = (ClassLoader) a[1];
                }
                if (a[2] instanceof ApplicationInfo) {
                    ai = (ApplicationInfo) a[2];
                }
            }
        }
        // saved state 里只有 String / ApplicationInfo 这类可序列化对象能活着回来，
        // ClassLoader 会被框架丢成 null（实测：pkg 恢复成功，cl 却是 null，重装被静默跳过）。
        // 兜底反射 ActivityThread 取当前进程运行中的 Application —— 它的 CL 就是目标应用的 CL，
        // 不随模块 CL 热替换而改变；本项目 Prefs/Main 已有同类反射先例，实测可用。
        String clSrc = cl != null ? "saved" : "none";
        if (cl == null) {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object app = at.getMethod("currentApplication").invoke(null);
                if (app != null) {
                    cl = app.getClass().getClassLoader();
                    clSrc = "reflect";
                    if (pkg == null) {
                        try {
                            pkg = (String) app.getClass().getMethod("getPackageName").invoke(app);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (ai == null) {
                        try {
                            ai = (ApplicationInfo) app.getClass()
                                    .getMethod("getApplicationInfo").invoke(app);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (pkg == null) {
            try {
                pkg = param.getProcessName();
            } catch (Throwable ignored) {
            }
        }
        if (cl == null) {
            // 安全降级：宁可让旧代码的 hook 继续生效，也不要把进程变成无保护状态。
            // （先撤后装一旦装不上，guardprovider 就彻底失防了。）
            lg(Log.ERROR, TAG, "[Camellia] onHotReloaded: target ClassLoader unavailable,"
                    + " keep old hooks (pkg=" + pkg + ")");
            return;
        }
        int unhooked = 0;
        try {
            List<XposedInterface.HookHandle> old = param.getOldHookHandles();
            if (old != null) {
                for (XposedInterface.HookHandle h : old) {
                    try {
                        if (h != null) {
                            h.unhook();
                            unhooked++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        probe("onHotReloaded", "pkg=" + pkg + " unhooked=" + unhooked + " cl=" + clSrc);
        lg(Log.WARN, TAG, "[Camellia] onHotReloaded pkg=" + pkg + " unhooked=" + unhooked
                + " cl=" + clSrc);
        try {
            dispatchInstall(pkg, cl, ai);
        } catch (Throwable t) {
            lg(Log.ERROR, TAG, "onHotReloaded: reinstall failed", t);
        }
    }

    public void onPackageLoaded(PackageLoadedParam param) {
        try {
            String pkg = param.getPackageName();
            probe("onPackageLoaded", pkg);
            if (Fg.PKG.equals(pkg)) {
                // 小米画报：自选壁纸目录同步（另一条完全独立的业务线）
                lg(Log.INFO, TAG, "onPackageLoaded pkg=" + pkg + " (画报)");
                Fg.install(this, param.getDefaultClassLoader());
                return;
            }
            if (Unlock.matchGuardPkg(pkg)) {
                // 系统安全组件（com.miui.guardprovider）：独立入口，只装 gp_* 七条（gp_dbguard 挂手机管家）
                lg(Log.INFO, TAG, "onPackageLoaded pkg=" + pkg + " (系统安全组件)");
                Unlock.installGuardProvider(this, param.getDefaultClassLoader(),
                        param.getApplicationInfo(), pkg, new Unlock.PrefProvider() {
                            @Override
                            public SharedPreferences prefs() {
                                return getRemotePreferences(Prefs.GROUP);
                            }
                        });
                return;
            }
            if (Unlock.matchPkg(pkg)) {
                // 安全服务（手机管家）：解锁 / 阻断业务线
                lg(Log.INFO, TAG, "onPackageLoaded pkg=" + pkg + " (手机管家)");
                Unlock.install(this, param.getDefaultClassLoader(), param.getApplicationInfo(),
                        pkg, new Unlock.PrefProvider() {
                            @Override
                            public SharedPreferences prefs() {
                                return getRemotePreferences(Prefs.GROUP);
                            }
                        });
                return;
            }
            if ("android".equals(pkg)) {
                lg(Log.INFO, TAG, "onPackageLoaded pkg=android (系统框架)");
                ClassLoader cl = param.getDefaultClassLoader();
                if (cl != null) {
                    FreeformHooks.installAndroid(this, cl, new FreeformHooks.PrefProvider() {
                        @Override
                        public SharedPreferences prefs() {
                            return getRemotePreferences(Prefs.GROUP);
                        }
                    });
                }
                return;
            }
            if ("com.android.systemui".equals(pkg)) {
                lg(Log.INFO, TAG, "onPackageLoaded pkg=com.android.systemui (SystemUI)");
                ClassLoader cl = param.getDefaultClassLoader();
                if (cl != null) {
                    // 旧版 SysUiRescue / SysUiWatch 已删除，这里只留功能 hook。
                    FreeformHooks.installSystemUI(this, cl, new FreeformHooks.PrefProvider() {
                        @Override
                        public SharedPreferences prefs() {
                            return getRemotePreferences(Prefs.GROUP);
                        }
                    });
                }
                return;
            }
            if (!TARGET_PKG.equals(pkg)) {
                return;
            }
            lg(Log.INFO, TAG, "onPackageLoaded pkg=" + pkg);
            ClassLoader cl = param.getDefaultClassLoader();
            ApplicationInfo ai = param.getApplicationInfo();
            install(cl, ai);
        } catch (Throwable t) {
            lg(Log.ERROR, TAG, "onPackageLoaded failed", t);
        }
    }


    // =====================================================================
    // 安装 Hook
    // =====================================================================

    private void install(ClassLoader cl, ApplicationInfo ai) {
        if (sInstalledMain) {
            return;
        }
        sInstalledMain = true;
        long tStart = SystemClock.uptimeMillis();
        sBridgeMs = 0;
        sDexKitQueryMs = 0;
        Context appCtx = anyContext();
        ensureStatusPull(appCtx);
        List<Method> picks = new ArrayList<>();
        String via = null;
        List<Method> dirs = null;   // 目录构造点：缓存命中时一并带出
        // 0) 签名缓存（首选）：同一份 APK（versionCode/versionName/lastUpdateTime
        //    都没变）的类与方法签名是稳定的，直接按缓存的签名反射解析即可，
        //    省掉「每次互传进程启动都重新建 DexKit bridge + 扫全量 dex」的开销。
        SigCache sig = loadSigCache(cl, ai);
        if (sig != null) {
            picks = sig.picks;
            dirs = sig.dirs;
            via = "cache";
        }
        // 1) DexKit 结构化查询（首选）：只用「返回 android.content.Intent」
        //    这一个永不混淆的特征取全集，再按多信号打分挑中枢 —— 新旧版本同一套规则。
        try {
            if (picks.isEmpty()) {
                picks = findDispatchersByDexKit(cl, ai);
                // 注意：只有真正跑过 DexKit 才能标 via=dexkit，
                // 否则会把缓存的 via=cache 覆盖掉（status 会误导）。
                if (!picks.isEmpty()) {
                    via = "dexkit";
                }
            }
        } catch (Throwable t) {
            lg(Log.WARN, TAG, "dexkit lookup failed", t);
        }
        // 2) 自带极简 dex 解析器（无原生依赖，与 1) 完全同一打分口径）
        if (picks.isEmpty()) {
            try {
                picks = findDispatchersByDex(cl, ai);
                if (!picks.isEmpty()) {
                    via = "dex-scan";
                }
            } catch (Throwable t) {
                lg(Log.ERROR, TAG, "dex scan failed", t);
            }
        }
        // 3) 已知类名兜底（仅当上面都失败；仍不依赖具体方法名）
        if (picks.isEmpty()) {
            lg(Log.WARN, TAG, "no dex candidate, try fallback class names");
            picks = findDispatchersByFallback(cl);
            if (!picks.isEmpty()) {
                via = "fallback-name";
            }
        }
        if (picks.isEmpty()) {
            lg(Log.ERROR, TAG, "!! dispatcher not found, module idle");
            closeSharedBridge();
            writeStatus("result=no-dispatcher\n");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Method m : picks) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(sign(m));
        }
        long tVer = SystemClock.uptimeMillis();
        String ver = targetVersion(appCtx);
        long verMs = SystemClock.uptimeMillis() - tVer;
        lg(Log.INFO, TAG, "target " + ver + " adapted via " + via
                + ", hooks(" + picks.size() + ")=" + sb);

        int ok = 0;
        for (Method m : picks) {
            if (hookDispatcher(m)) {
                ok++;
            }
        }
        lg(Log.INFO, TAG, "dispatcher hooks installed=" + ok + "/" + picks.size());

        // 4) 目录构造点（4.x legacy 家族）：覆盖「非单文件 / 打开接收目录」这条路。
        long tDir = SystemClock.uptimeMillis();
        if (dirs == null) {
            dirs = findDirAnchors(cl, ai);
        }
        long dirMs = SystemClock.uptimeMillis() - tDir;
        StringBuilder dsb = new StringBuilder();
        int dok = 0;
        for (Method m : dirs) {
            if (hookDirAnchor(m)) {
                dok++;
                if (dsb.length() > 0) {
                    dsb.append(", ");
                }
                dsb.append(sign(m));
            }
        }
        lg(Log.INFO, TAG, "dir-anchor hooks installed=" + dok + "/" + dirs.size()
                + (dsb.length() > 0 ? " => " + dsb : ""));
        if (!"cache".equals(via)) {
            saveSigCache(ai, picks, dirs);
        }

        if (selfTestRequested()) {
            runSelfTest(picks, dirs);
        }

        String mtNote;
        long tMt = SystemClock.uptimeMillis();
        try {
            ComponentName mtc = pickMtShortcut(anyContext());
            mtNote = mtc == null ? "none" : mtc.flattenToShortString();
        } catch (Throwable t) {
            mtNote = "error:" + t;
        }
        long mtMs = SystemClock.uptimeMillis() - tMt;
        lg(Log.INFO, TAG, "mt shortcut = " + mtNote);
        // 目标进程侧「状态按需重发」（设置页刷新按钮 / 进设置页自动 pull）：
        // install() 阶段只有系统 Context，注册会挂错上下文，所以挂
        // Application#onCreate（类名不被混淆）用真实 app Context 注册。
        try {
            Method appOnCreate = Class.forName("android.app.Application", false, cl)
                    .getDeclaredMethod("onCreate");
            hook(appOnCreate).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof Context) {
                            ensureStatusPull((Context) self);
                        }
                    } catch (Throwable t) {
                        lg(Log.WARN, TAG, "ensureStatusPull(app) failed", t);
                    }
                    return r;
                }
            });
            lg(Log.INFO, TAG, "hooked Application#onCreate (status pull)");
        } catch (Throwable t) {
            lg(Log.WARN, TAG, "hook Application#onCreate failed: " + t);
        }
        // ── 小米互传功能解锁：极速传输（160M）/ 苹果设备（AirDrop）互联 ──
        String unlockNote = installUnlock(cl, ai);
        closeSharedBridge();
        writeStatus("version=" + ver + "\nvia=" + via + "\n"
                + "pid=" + android.os.Process.myPid() + "\n"
                + "hooks=" + sb + "\n"
                + "dirAnchors=" + dsb + "\n"
                + "mt=" + mtNote + "\n"
                + "unlock=" + unlockNote + "\n"
                + "perf=locate=" + (SystemClock.uptimeMillis() - tStart) + "ms,"
                + "dexkitBridge=" + sBridgeMs + "ms,"
                + "dexkitQuery=" + sDexKitQueryMs + "ms,"
                + "dirAnchor=" + dirMs + "ms,"
                + "verIpc=" + verMs + "ms,"
                + "mtProbe=" + mtMs + "ms\n");
    }

    // =====================================================================
    // 小米互传功能解锁：极速传输（160M）/ 苹果设备（AirDrop）互联
    //
    // 这两个功能在原版里都由「支持判定」类的无参静态方法决定，改判定即解锁，
    // 不需要动业务流程：
    //   1) d2.i#z()Z            —— 本机是否支持极速传输。恒 true 后：
    //                              设置页显示极速开关、握手包宣告支持 160M。
    //                              对端不支持时 s1.l#A() 仍返回普通模式，
    //                              所以不会破坏与老设备的兼容。
    //   2) connectivity.c#c()Z  —— 苹果设备（AirDrop）入口判定，恒 true 后出现入口。
    //
    // 类名是混淆的、每版都可能变，所以用「候选列表 + 逐个容错」：
    // 命中即挂，全部未命中只记 WARN，绝不影响主业务线（文件落点改 MT）。
    // =====================================================================

    /** 极速传输「本机支持」判定的候选类名（混淆名，仅作锚点失败时的兜底） */
    private static final String[] TURBO_OWNERS = {
            "d2.j", "F1.i", "d2.i", "d2.h", "d2.g",
    };
    /** 苹果设备（AirDrop）入口判定的候选类名 */
    private static final String[] AIRDROP_OWNERS = {
            "com.miui.mishare.connectivity.c",
    };

    /**
     * DexKit 字符串锚点。
     *
     * <p>互传把能力判定类整个混淆了：同一个 {@code isDeviceSupportTurboMode}
     * 在 5.3.4 里是 {@code F1.i#z()}、到 5.4.2 变成 {@code d2.j#z()}，
     * 写死类名必然随版本失效。但这两个字符串常量是稳定的，
     * 所以优先用锚点定位，类名列表只作最后兜底。</p>
     *
     * <p>4.0.5 / 4.13.8 的 dex 里没有这两个字符串（极速传输是 5.x 才有的能力），
     * 锚点会自然 miss 并退回候选类名，兜不住就安全跳过 —— 绝不影响主业务线。</p>
     */
    private static final String[] TURBO_ANCHORS = {
            // 5.3.4 / 5.4.2：方法体里直接带这个短串
            "isDeviceSupportTurboMode",
            // 5.5.0+：日志模板化，短串被拆开，只剩这两种带后缀的长串
            "isDeviceSupportTurboMode() hardware not supported",
            "isDeviceSupportTurboMode() hardware:true cloudControl:",
    };

    /**
     * 苹果设备（AirDrop）入口判定的锚点。
     *
     * <p>5.5.0 实测：真正的判定是 {@code isWonderEnabled()} —— 它读内核节点
     * {@code /proc/sys/net/wonder/wonder_enable}，节点不存在就恒 false，所以
     * 用这个路径串定位到的才是判定方法本身。</p>
     *
     * <p>早先用的 {@code "AirDropAvailability"} 命中的是日志 Tag 常量所在的
     * {@code connectivity.c#d}，hook 成 true 后 {@code available} 依旧 false，
     * 只保留作老版本兜底。</p>
     */
    private static final String[] AIRDROP_ANCHORS = {
            "/proc/sys/net/wonder/wonder_enable",
            "AirDropAvailability",
    };

    /** 内核 wonder 能力节点；不存在说明本机内核不带 AirDrop 支持 */
    private static final String WONDER_NODE = "/proc/sys/net/wonder/wonder_enable";

    /**
     * 本机内核是否具备 AirDrop 能力。
     *
     * <p>实测 Xiaomi 17 Pro Max（内核 6.12.69）既没有该 proc 节点，
     * {@code /vendor/lib/modules} 里也没有任何 wonder 模块，属于内核层面
     * 不具备 —— 这种情况硬挂 hook 只能造出一个点了必失败的假入口，
     * 所以宁可如实标注「不支持」。</p>
     */
    private static boolean hasKernelWonder() {
        try {
            return new File(WONDER_NODE).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 安装功能解锁钩子。
     *
     * <p>两个开关各自独立：关闭时返回 {@code off} 且不挂 hook，
     * 用户重启互传即恢复原版行为，不需要卸载模块。</p>
     *
     * @return 形如 {@code turbo=d2.j#z,airdrop=miss} 的简短结果，写进状态文件便于排查
     */
    private String installUnlock(ClassLoader cl, ApplicationInfo ai) {
        // 整段兜住：解锁只是锦上添花，绝不能因为它失败就打断 Main.install()
        // 后面的状态写入（早先就是这里抛 NPE，导致 unlock 记录都写不出来）
        String turbo = "err";
        String air = "err";
        try {
            boolean turboOn = unlockSwitch(Prefs.ID_UL_MISHARE_TURBO);
            boolean airOn = unlockSwitch(Prefs.ID_UL_MISHARE_AIRDROP);
            turbo = turboOn
                    ? hookUnlock(cl, ai, TURBO_ANCHORS, TURBO_OWNERS, "z", "turbo")
                    : "off";
            if (!airOn) {
                air = "off";
            } else if (!hasKernelWonder()) {
                // 内核没有 wonder 节点/模块：AirDrop 无从建立连接，
                // 挂 hook 只会造出点了必失败的假入口，不如如实标注
                air = "unsupported";
                lg(Log.INFO, TAG, "unlock airdrop skipped: kernel lacks " + WONDER_NODE);
            } else {
                air = hookUnlock(cl, ai, AIRDROP_ANCHORS, AIRDROP_OWNERS, "c", "airdrop");
            }
        } catch (Throwable t) {
            lg(Log.WARN, TAG, "installUnlock failed: " + t);
        }
        lg(Log.INFO, TAG, "unlock turbo=" + turbo + " airdrop=" + air);
        return "turbo=" + turbo + ",airdrop=" + air;
    }

    /**
     * 读互传解锁开关。
     *
     * <p>这里<b>不能</b>用 {@code Prefs.isMiShareUnlockOn(ctx, id)}：那走的是
     * {@code ctx.getSharedPreferences}，而 Main 跑在互传进程里，拿到的 ctx
     * 要么是 null（NPE），要么是互传自己的 Context（读到的永远是互传的私有
     * prefs，而不是模块配置，开关等于失效）。</p>
     *
     * <p>按本项目既定约定改走 {@code getRemotePreferences}（跨进程读模块设置）。
     * 读不到时退回「开」——装了这个模块就是要解锁，没配置过应当生效。</p>
     */
    private boolean unlockSwitch(String id) {
        try {
            SharedPreferences sp = getRemotePreferences(Prefs.GROUP);
            if (sp != null) {
                return sp.getBoolean(Prefs.KEY_UL_PREFIX + id, true);
            }
        } catch (Throwable t) {
            lg(Log.WARN, TAG, "unlockSwitch(" + id + ") failed: " + t);
        }
        return true;
    }

    /**
     * 锚点优先、类名兜底地挂一个「恒 true」钩子。
     *
     * <p>锚点命中时直接用反射拿到的那个 {@link Method}，连方法名都不用猜；
     * 只有锚点没命中（老版本没有该字符串）才退回「候选类名 + 方法名」的老路子。</p>
     */
    private String hookUnlock(ClassLoader cl, ApplicationInfo ai, String[] anchors,
                              String[] owners, String name, String tag) {
        for (String anchor : anchors) {
            Method hit = locateConstTrueByAnchor(cl, ai, anchor, tag);
            if (hit == null) {
                continue;
            }
            String owner = hit.getDeclaringClass().getName();
            String mName = hit.getName();
            if (hookTrue(hit, tag)) {
                return owner + "#" + mName;
            }
        }
        return hookConstTrue(cl, owners, name, tag);
    }

    /**
     * 把「无参且返回 boolean」的方法 hook 成恒 true。
     *
     * @param owners 候选类名（混淆名），按顺序尝试
     * @param name   方法名
     * @param tag    日志标签
     * @return 命中的类名；全部未命中返回 {@code miss}
     */
    private String hookConstTrue(ClassLoader cl, String[] owners, String name, String tag) {
        for (String owner : owners) {
            Method m = resolveMethod(cl, owner, name, new String[0]);
            if (m == null) {
                continue;
            }
            if (m.getReturnType() != boolean.class) {
                lg(Log.WARN, TAG, "unlock " + tag + ": " + owner + "#" + name + " 非 boolean，跳过");
                continue;
            }
            if (hookTrue(m, tag)) {
                lg(Log.INFO, TAG, "unlock " + tag + " hooked: " + owner + "#" + name);
                return owner;
            }
        }
        lg(Log.WARN, TAG, "unlock " + tag + " 未命中（锚点 + " + owners.length + " 个候选都失败）");
        return "miss";
    }

    /** 把一个「无参返回 boolean」的方法 hook 成恒 true；成功返回 true */
    private boolean hookTrue(final Method m, String tag) {
        try {
            hook(m).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    return Boolean.TRUE;
                }
            });
            return true;
        } catch (Throwable t) {
            lg(Log.ERROR, TAG, "unlock " + tag + " hook failed: " + sign(m), t);
            return false;
        }
    }

    /**
     * 用 DexKit 字符串锚点定位「无参且返回 boolean」的方法。
     *
     * <p>为什么只给「含该字符串 + 无参 + 返回 boolean」三个条件：互传把能力判定类
     * 整体混淆了（类名/方法名/字段名全变），唯一稳定的是这些会被写进日志或被反射
     * 调用的字符串，所以这个组合在实践中几乎是唯一候选。</p>
     *
     * @return 命中的方法（已 setAccessible）；定位失败 / 未装 DexKit 返回 {@code null}
     */
    private Method locateConstTrueByAnchor(ClassLoader cl, ApplicationInfo ai,
                                           String anchor, String tag) {
        if (ai == null || ai.sourceDir == null || !ensureDexKit()) {
            return null;
        }
        try {
            DexKitBridge bridge = sharedBridge(ai);
            if (bridge == null || !bridge.isValid()) {
                return null;
            }
            long tQ = SystemClock.uptimeMillis();
            MethodDataList list = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("boolean", StringMatchType.Equals, false)
                            .usingStrings(Collections.singletonList(anchor),
                                    StringMatchType.Equals, false)));
            sDexKitQueryMs += SystemClock.uptimeMillis() - tQ;
            lg(Log.INFO, TAG, "unlock " + tag + " anchor " + anchor + " matches=" + list.size());
            for (MethodData md : list) {
                Method m = null;
                try {
                    m = md.getMethodInstance(cl);
                } catch (Throwable t) {
                    dbg("unlock " + tag + " getMethodInstance failed: " + md.getName() + " : " + t);
                }
                if (m == null) {
                    continue;
                }
                // DexKit 的 returnType 匹配基于描述符，仍以反射结果复核一次：
                // 只有真正的「无参 boolean」才有资格被替换成恒 true。
                if (m.getReturnType() != boolean.class || m.getParameterTypes().length != 0) {
                    continue;
                }
                m.setAccessible(true);
                lg(Log.INFO, TAG, "unlock " + tag + " anchor hit: "
                        + m.getDeclaringClass().getName() + "#" + m.getName());
                return m;
            }
            lg(Log.WARN, TAG, "unlock " + tag + " anchor miss: " + anchor);
        } catch (Throwable t) {
            lg(Log.WARN, TAG, "unlock " + tag + " anchor query failed: " + t);
        }
        return null;
    }

    /**
     * 挂载一个分流候选，并采用「先放行、再择优改写」：
     *   1) 先真正执行原方法拿到它本来要用的 Intent —— 不凭空创造行为；
     *   2) 若结果是分享类 Intent（SEND/SEND_MULTIPLE/CHOOSER）→ 原样返回
     *      （这些流程与「打开」无关，改了会破坏分享）；
     *   3) 否则按文件类型判据决定是否改写成 MT 跳转 Intent；
     *   4) 任何异常 / 构造不出 MT Intent → 返回原结果，等价于没装模块。
     *
     * 多个候选共用同一判据，所以多挂是幂等的、不会互相干扰。
     */
    private boolean hookDispatcher(final Method m) {
        final String who = sign(m);
        // 参数下标只在挂载时解析一次：拦截时直接按位取值，
        // 省掉每次调用的「遍历参数 + 反射判类型」开销。
        final int fiIdx = indexOfFileInfoArrayParam(m);
        final int ctxIdx = indexOfContextParam(m);
        try {
            hook(m).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    long t0 = SystemClock.uptimeMillis();
                    Object result = chain.proceed();
                    try {
                        if (isShareIntent(result)) {
                            if (DEBUG) {
                                dbg(who + ": keep share intent (" + actionOf(result) + ")");
                            }
                            return result;
                        }
                        Object[] files = argFiles(chain, fiIdx);
                        if (shouldRedirect(files)) {
                            Object mt = buildMtIntent(files, argContext(chain, ctxIdx));
                            if (mt != null) {
                                return mt;
                            }
                        }
                    } catch (Throwable t) {
                        lg(Log.ERROR, TAG, who + ": intercept error", t);
                    } finally {
                        noteIntercept(SystemClock.uptimeMillis() - t0);
                    }
                    return result;
                }
            });
            dbg("hooked " + who);
            return true;
        } catch (Throwable t) {
            lg(Log.WARN, TAG, "hook failed: " + who, t);
            return false;
        }
    }

    /** 判断本次调用是否应改为 MT 管理器 */
    private boolean shouldRedirect(Object[] files) {
        if (files == null || files.length == 0) {
            return false;
        }
        for (Object f : files) {
            if (f == null) {
                continue;
            }
            if (isKeepOriginal(f)) {
                if (DEBUG) {
                    dbg("keep original: " + describe(f));
                }
                return false;
            }
        }
        if (DEBUG) {
            dbg("redirect to MT: " + files.length + " file(s)");
        }
        return true;
    }

    /**
     * 构造 MT 管理器 Intent（定位到收到的文件 / 目录）。
     *
     * 返回 null 表示当前设备上没有任何可用的 MT 跳转入口，
     * 调用方应保持互传原有行为（避免「点了没反应」）。
     */
    private Object buildMtIntent(Object[] files, Context ctx) {
        Context c = ctx != null ? ctx : anyContext();
        // 先确认 MT 可用，再做（相对昂贵的）路径解析：
        // 没装 MT 的设备上这一步能省掉整条 resolveDir 的反射 / ContentResolver 查询。
        if (pickMtShortcut(c) == null) {
            return null;
        }
        if (DEBUG && files != null && files.length > 0 && files[0] != null) {
            Object f0 = files[0];
            lg(Log.INFO, TAG, "file[0] name=" + getField(f0, "name")
                    + " type=" + getField(f0, "type")
                    + " mime=" + getField(f0, "mimeType")
                    + " path=" + getField(f0, "path")
                    + " directory=" + getField(f0, "directory"));
        }
        String dir = toDirTarget(resolveDir(files, c));
        return buildMtIntentAt(c, dir);
    }

    /** 构造 MT 跳转 Intent（显式组件 + path/operation=goto）；ctx 不可用时返回 null */
    private Intent buildMtIntentAt(Context ctx, String dir) {
        Context c = ctx != null ? ctx : anyContext();
        // 组件探测（有 per-pkg 缓存）先做：没有 MT 就没必要解析路径。
        ComponentName comp = pickMtShortcut(c);
        if (comp == null) {
            lg(Log.WARN, TAG, "no MT build exposing ShortcutActivity, keep original behavior");
            return null;
        }
        if (dir == null) {
            dir = toDirTarget(resolveDir(null, c));
        }
        Intent intent = new Intent(comp.getPackageName() + MT_ACTION_SUFFIX);
        intent.setComponent(comp);
        intent.putExtra("path", dir);
        intent.putExtra("operation", "goto");
        intent.putExtra("folderColorIcon", "ic_folder");
        intent.addFlags(MT_FLAGS);
        // 日志只在调试开关打开时才有意义，但字符串拼接（flattenToShortString + 多次
        // 连接）在调用点就会发生。把拼接放进 DEBUG 分支，关掉调试时热路径零字符串分配。
        if (DEBUG) {
            lg(Log.INFO, TAG, "-> " + comp.flattenToShortString() + ", path=" + dir);
        }
        return intent;
    }

    /**
     * 挂一个「目录构造点」并改写为 MT。
     *
     * 与中枢不同：这里没有 FileInfo 可判类型（该构造点本来只负责把接收目录
     * 交给文件管理器），因此只要产出的不是分享类 Intent，就说明这是「打开」流程，
     * 直接换成 MT；构造不出 MT 时原样返回，等价于没装模块。
     */
    private boolean hookDirAnchor(final Method m) {
        final String who = sign(m);
        final int ctxIdx = indexOfContextParam(m);
        try {
            hook(m).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    long t0 = SystemClock.uptimeMillis();
                    Object result = chain.proceed();
                    try {
                        if (result == null || !(result instanceof Intent) || isShareIntent(result)) {
                            return result;
                        }
                        Context ctx = argContext(chain, ctxIdx);
                        Intent mt = buildMtIntentForDir(ctx, result);
                        if (mt != null) {
                            if (DEBUG) {
                                lg(Log.INFO, TAG, who + ": dir -> "
                                        + mt.getComponent().flattenToShortString()
                                        + ", path=" + mt.getStringExtra("path")
                                        + " (was " + actionOf(result) + ")");
                            }
                            return mt;
                        }
                    } catch (Throwable t) {
                        lg(Log.ERROR, TAG, who + ": dir intercept error", t);
                    } finally {
                        noteIntercept(SystemClock.uptimeMillis() - t0);
                    }
                    return result;
                }
            });
            dbg("hooked dir anchor " + who);
            return true;
        } catch (Throwable t) {
            lg(Log.WARN, TAG, "hook dir anchor failed: " + who, t);
            return false;
        }
    }

    /** 目录构造点用：优先用原 Intent 带的目录信息，取不到再退回接收目录探测 */
    private Intent buildMtIntentForDir(Context ctx, Object original) {
        Context c = ctx != null ? ctx : anyContext();
        // 先确认 MT 可用：不可用就不必去解析原 Intent 里的目录 extras。
        if (pickMtShortcut(c) == null) {
            return null;
        }
        if (original instanceof Intent) {
            Intent oi = (Intent) original;
            for (String k : DIR_EXTRA_KEYS) {
                String v = null;
                try {
                    v = oi.getStringExtra(k);
                } catch (Throwable ignored) {
                }
                String p = realPath(ctx, v);
                if (p != null && new File(p).exists()) {
                    dbg("dir target <- extra " + k + ": " + p);
                    return buildMtIntentAt(c, toDirTarget(p));
                }
            }
            try {
                if (oi.getData() != null) {
                    String p = realPath(ctx, String.valueOf(oi.getData()));
                    if (p != null && new File(p).exists()) {
                        dbg("dir target <- data: " + p);
                        return buildMtIntentAt(c, toDirTarget(p));
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return buildMtIntentAt(c, toDirTarget(resolveDir(null, c)));
    }

    /**
     * 依次尝试「设置里选的包 -> MT 正式版 -> MT Canary」，
     * 取第一个真正带 ShortcutActivity 的包。返回 null 表示都不可用。
     */
    private ComponentName pickMtShortcut(Context ctx) {
        String pref = mtPackage();
        // 缓存命中：偏好包没变且上次解析成功 -> 直接返回（点击热路径只走这里）
        if (mComp != null && pref != null && pref.equals(mCompFor)) {
            return mComp;
        }
        // 不建 List：点一次会调好几次，直接按序试（shortcutClassOf 已按包名缓存）
        String[] order = {pref, Prefs.MT_DEFAULT, Prefs.MT_CANARY};
        for (String pkg : order) {
            if (pkg == null) {
                continue;
            }
            String cls = shortcutClassOf(ctx, pkg);
            if (cls != null) {
                ComponentName c = new ComponentName(pkg, cls);
                mComp = c;
                mCompFor = pref;
                return c;
            }
        }
        return null;
    }

    /**
     * 枚举目标包内的 activity，找出类名末段为 ShortcutActivity 的那个的完整类名。
     *
     * 不能直接拼 "<pkg>.ShortcutActivity"：分身/克隆包的组件通常写成
     * "<新applicationId>/<原类名>"，类名仍是 bin.mt.plus.ShortcutActivity。
     * 返回 null 表示该包没有这个组件（例如实测 bin.mt.plus.canary 2.26.4-clone）。
     */
    private String shortcutClassOf(Context ctx, String pkg) {
        if (ctx == null || pkg == null) {
            return null;
        }
        if (mShortcutCache.containsKey(pkg)) {
            String hit = mShortcutCache.get(pkg);
            return hit == null || hit.isEmpty() ? null : hit;
        }
        String found = null;
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            ActivityInfo[] acts = pi == null ? null : pi.activities;
            if (acts != null) {
                for (ActivityInfo ai : acts) {
                    String n = ai == null ? null : ai.name;
                    if (n == null) {
                        continue;
                    }
                    int dot = n.lastIndexOf('.');
                    String simple = dot >= 0 ? n.substring(dot + 1) : n;
                    if (MT_ACTIVITY_SIMPLE.equals(simple)) {
                        found = n;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            dbg("probe " + pkg + " failed: " + t);
        }
        if (found == null && Prefs.MT_DEFAULT.equals(pkg)) {
            // 枚举不到时的兜底：标准包名下的入口就是 <pkg>.ShortcutActivity。
            // 用 getActivityInfo 显式确认存在，避免指向不存在的组件导致点开闪退。
            try {
                ctx.getPackageManager().getActivityInfo(
                        new ComponentName(pkg, pkg + "." + MT_ACTIVITY_SIMPLE), 0);
                found = pkg + "." + MT_ACTIVITY_SIMPLE;
                dbg("probe " + pkg + " fell back to canonical " + found);
            } catch (Throwable ignored) {
            }
        }
        mShortcutCache.put(pkg, found == null ? "" : found);
        lg(Log.INFO, TAG, "probe " + pkg + " -> "
                + (found == null ? "no ShortcutActivity (不支持目录跳转)" : found));
        return found;
    }

    /**
     * 解析要交给 MT 的路径。
     *
     * 实测：互传的 FileInfo 在不同文件类型下给出的东西**不一样**：
     *   - 部分文件（如 .img）path / directory 是真实路径，
     *     且 directory 对单文件形如 "…/文件名/"（带尾斜杠）；
     *   - 部分文件（如 .json）path 是 MediaStore 的 content:// URI，
     *     例如 content://media/external_primary/file/149268 。
     *
     * 因此这里把所有来源统一解析成文件系统路径，再用「路径是否真实存在」定优先级。
     */
    private String resolveDir(Object[] files, Context ctx) {
        List<String> cands = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int checked = 0;
        if (files != null) {
            for (Object f : files) {
                if (f == null) {
                    continue;
                }
                Object pv = getField(f, "path");
                Object dv = getField(f, "directory");
                addIfNotNull(cands, realPath(ctx, str(pv)));
                addIfNotNull(cands, realPath(ctx, str(dv)));
                names.add(str(getField(f, "name")));
                // 边算边判：候选里第一个「真实存在」的就是最终答案
                // （与原先「先收集全部候选、再按序判定」结果一致），
                // 命中后不再解析后续文件的候选 —— 多文件时能省掉大量
                // realPath / ContentResolver 查询。
                for (int ci = checked; ci < cands.size(); ci++) {
                    String c = cands.get(ci);
                    if (new File(c).exists()) {
                        dbg("target <- exists: " + c);
                        return c;
                    }
                }
                checked = cands.size();
                // 4.0.5 的 FileInfo 没有 directory 字段，path 也可能是 content://：
                // 字段取不到时退回方法级取值（getUri() / getContentUri(Context)）。
                if (pv == null && dv == null) {
                    addIfNotNull(cands, realPath(ctx, callPath(f, "getUri")));
                    addIfNotNull(cands, realPath(ctx, callPath(f, "getContentUri", ctx)));
                }
            }
        }

        // 1) 真实存在的路径（文件或目录）最可信
        //    （绝大多数情况已在上面边算边判时命中返回；这里只兜底）
        for (String c : cands) {
            if (new File(c).exists()) {
                dbg("target <- exists: " + c);
                return c;
            }
        }
        // 2) directory 形如 "…/文件名/" 时，其父目录才是真目录
        for (String c : cands) {
            File parent = new File(c).getParentFile();
            if (parent != null && parent.exists()) {
                dbg("target <- parent: " + parent.getAbsolutePath());
                return parent.getAbsolutePath();
            }
        }
        // 3) 已知互传目录 + 文件名（目录列表只探测一次）
        List<String> dirs = shareDirs(ctx);
        for (String base : dirs) {
            for (String n : names) {
                if (notEmpty(n)) {
                    File x = new File(base, n);
                    if (x.exists()) {
                        dbg("target <- shareDir+name: " + x.getAbsolutePath());
                        return x.getAbsolutePath();
                    }
                }
            }
        }
        // 4) 退化：第一个可用候选
        for (String c : cands) {
            dbg("target <- fallback cand: " + c);
            return c;
        }
        // 5) 最终兜底：已存在的互传目录本身
        for (String base : dirs) {
            if (new File(base).exists()) {
                dbg("target <- shareDir: " + base);
                return base;
            }
        }
        return "/sdcard/Download/MiShare";
    }

    /**
     * MT 的 operation=goto 把传入的 path 当**目录**（实测总显示成 "path/"）。
     * 所以若解析出来的是文件，必须换成它的父目录，
     * 否则 MT 会去找一个名为 "xxx.json/" 的假目录。
     */
    private String toDirTarget(String p) {
        if (p == null) {
            return null;
        }
        File f = new File(p);
        if (f.isDirectory()) {
            return f.getAbsolutePath();
        }
        File parent = f.getParentFile();
        if (parent != null) {
            return parent.getAbsolutePath();
        }
        return p;
    }

    /** 可能的互传保存目录（按可信度排序） */
    private List<String> shareDirs(Context ctx) {
        List<String> dirs = new ArrayList<>();
        addIfNotNull(dirs, trimSlash(callMiShareDir()));
        try {
            String ext = Environment.getExternalStorageDirectory().getAbsolutePath();
            addIfNotNull(dirs, trimSlash(ext + "/Download/MiShare"));
            addIfNotNull(dirs, trimSlash(ext + "/MIUI/MiShare"));
        } catch (Throwable ignored) {
        }
        addIfNotNull(dirs, "/storage/emulated/0/Download/MiShare");
        addIfNotNull(dirs, "/sdcard/Download/MiShare");
        if (ctx != null) {
            File f = ctx.getExternalFilesDir(null);
            if (f != null) {
                addIfNotNull(dirs, f.getAbsolutePath());
            }
        }
        return dirs;
    }

    /** 把任意来源（真实路径 / file:// / content://）统一解析为文件系统路径，失败返回 null */
    private String realPath(Context ctx, String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.startsWith("/")) {
            return trimSlash(t);
        }
        if (t.startsWith("file://")) {
            try {
                return trimSlash(Uri.parse(t).getPath());
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (t.startsWith("content://")) {
            return trimSlash(fromContentUri(ctx, trimSlash(t)));
        }
        return null;
    }

    /**
     * content:// -> 真实路径。
     * 依次尝试：_data 列 -> RELATIVE_PATH + DISPLAY_NAME -> documentId(primary:xxx/yyy)
     */
    private String fromContentUri(Context ctx, String s) {
        if (ctx == null) {
            return null;
        }
        Uri uri;
        try {
            uri = Uri.parse(s);
        } catch (Throwable t) {
            return null;
        }
        if (uri == null) {
            return null;
        }
        // 1) 直接查 _data / relative_path / display_name
        try {
            String[] proj = {
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
            };
            Cursor c = ctx.getContentResolver().query(uri, proj, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String data = getCol(c, MediaStore.MediaColumns.DATA);
                        if (notEmpty(data) && data.startsWith("/")) {
                            dbg("uri _data -> " + data);
                            return data;
                        }
                        String rel = getCol(c, MediaStore.MediaColumns.RELATIVE_PATH);
                        String name = getCol(c, MediaStore.MediaColumns.DISPLAY_NAME);
                        if (notEmpty(rel) && notEmpty(name)) {
                            String base = Environment.getExternalStorageDirectory().getAbsolutePath();
                            String p = base + "/" + trimSlash(rel) + "/" + name;
                            dbg("uri relPath -> " + p);
                            return p;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            dbg("uri query failed: " + t);
        }
        // 2) documentId: primary:Download/MiShare/xxx.json
        try {
            String docId = DocumentsContract.getDocumentId(uri);
            if (notEmpty(docId) && docId.contains(":")) {
                String[] parts = docId.split(":", 2);
                String base = "primary".equals(parts[0])
                        ? Environment.getExternalStorageDirectory().getAbsolutePath()
                        : "/storage/" + parts[0];
                String p = base + "/" + parts[1];
                dbg("uri docId -> " + p);
                return p;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String getCol(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        return i >= 0 ? c.getString(i) : null;
    }

    private static void addIfNotNull(List<String> l, String s) {
        if (s != null && !s.isEmpty() && !l.contains(s)) {
            l.add(s);
        }
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    /** 去掉尾部所有 '/' */
    private static String trimSlash(String s) {
        if (s == null) {
            return null;
        }
        String r = s.trim();
        int end = r.length();
        while (end > 1 && r.charAt(end - 1) == '/') {
            end--;
        }
        r = r.substring(0, end);
        return r.isEmpty() ? null : r;
    }

    /** 反射调用 com.miui.mishare.file.a 中返回 String 的无参静态方法（历史版本为 f()） */
    private String callMiShareDir() {
        try {
            Class<?> c = Class.forName("com.miui.mishare.file.a");
            Method fallback = null;
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (m.getParameterTypes().length != 0) {
                    continue;
                }
                if (m.getReturnType() != String.class) {
                    continue;
                }
                if ("f".equals(m.getName())) {
                    fallback = m;
                    break;
                }
                if (fallback == null) {
                    fallback = m;
                }
            }
            if (fallback != null) {
                fallback.setAccessible(true);
                Object r = fallback.invoke(null);
                if (r instanceof String) {
                    return (String) r;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // =====================================================================
    // 定位：DexKit 结构化查询（首选）
    // =====================================================================

    /** libdexkit.so 是否已加载（ = 尚未尝试） */
    private static Boolean sDexKitLoaded;

    /**
     * 用 DexKit 在目标 APK 里查「文件分流中枢」。
     *
     * 查询条件只用跨版本稳定的信息：
     *   静态方法 + 返回 android.content.Intent
     * 再用「参数里含 FileInfo[]」筛选，按参数个数打分取最优。
     * DexKit 是 C++ 实现的运行时 dex 解析，多 dex / compact dex 比手写解析更稳，
     * 条件也便于随版本演进调整；任一环节失败都会抛给上层退回自带解析器。
     */
    /**
     * DexKit 结构化定位（不依赖任何类名 / 方法名）。
     *
     * 查询模板只有一条，且只用「永不混淆」的框架类型：
     *     returnType == android.content.Intent
     * 再用 {@link #scoreDispatcher} 多信号打分，形成新老版本通用的一套判据：
     *     +60  参数含 FileInfo[]（文件数组，即「一批文件」的分流入口）
     *     +25  参数含单个 FileInfo
     *     +8 * 参数个数
     *     +15  参数含 TapRecvData（碰一碰入口）
     *     +5   参数含 Context（构造 Activity Intent 必需）
     *     +10  静态方法
     *     +5   返回类型是 Intent
     * 取「最高分」与「相对阈值（best 的 2/3，且不低于 MIN_SCORE）」之间的前
     * MAX_HOOKS 个一起挂，避免只押一个方法。
     *
     * 实测效果（同一套规则、零版本分支）：
     *   小米互传 4.13.8 → com.miui.mishare.view.d#j(135) / #e(127)
     *   小米互传 5.3.4  → com.miui.mishare.view.e#l(135)
     *   小米互传 5.4.2  → com.miui.mishare.view.e#q(143)
     * 其中 #j / #l / #q 都是通知构建链（NotificationBuilder）调用的那个中枢。
     */
    // =====================================================================
    // 定位：目录构造点（4.x legacy 家族）
    // =====================================================================

    /**
     * 找「打开目录」构造点，三级回退：
     *   1) DexKit 字符串锚点（最精确，无类名依赖）
     *   2) 自带 dex 扫描（形状：返回 Intent + 目录构造点参数形状）
     *   3) 反射枚举历史类名（view.d / view.e / view.f）+ 形状校验
     */
    private List<Method> findDirAnchors(ClassLoader cl, ApplicationInfo ai) {
        List<Method> out = new ArrayList<>();
        try {
            out = findDirAnchorsByDexKit(cl, ai);
        } catch (Throwable t) {
            lg(Log.WARN, TAG, "dir dexkit scan failed", t);
        }
        if (out.isEmpty()) {
            try {
                out = findDirAnchorsByDex(cl, ai);
            } catch (Throwable t) {
                lg(Log.WARN, TAG, "dir dex scan failed", t);
            }
        }
        if (out.isEmpty()) {
            try {
                out = findDirAnchorsByShape(cl);
            } catch (Throwable t) {
                lg(Log.WARN, TAG, "dir shape scan failed", t);
            }
        }
        return out;
    }

    private List<Method> findDirAnchorsByDexKit(ClassLoader cl, ApplicationInfo ai) {
        List<Method> out = new ArrayList<>();
        if (ai == null || ai.sourceDir == null || !ensureDexKit()) {
            return out;
        }
        DexKitBridge bridge = null;
        try {
            bridge = sharedBridge(ai);
            if (bridge == null || !bridge.isValid()) {
                return out;
            }
            long tQ = SystemClock.uptimeMillis();
            MethodDataList list = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("android.content.Intent", StringMatchType.Equals, false)
                            .usingStrings(Collections.singletonList(STR_EXPLORER_ACTION),
                                    StringMatchType.Equals, false)));
            if (list.isEmpty()) {
                dbg("dir dexkit: no START_TRANSFER anchor, try log string");
                list = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType("android.content.Intent", StringMatchType.Equals, false)
                                .usingStrings(Collections.singletonList(STR_EXPLORER_LOG),
                                        StringMatchType.Equals, false)));
            }
            sDexKitQueryMs += SystemClock.uptimeMillis() - tQ;
            lg(Log.INFO, TAG, "dir dexkit matches=" + list.size());
            for (MethodData md : list) {
                if (out.size() >= MAX_DIR_ANCHORS) {
                    break;
                }
                if (!isDirAnchorSig(md.getModifiers(), md.getParamTypeNames(),
                        md.getReturnTypeName())) {
                    continue;
                }
                Method m = null;
                try {
                    m = md.getMethodInstance(cl);
                } catch (Throwable t) {
                    dbg("dir getMethodInstance failed: " + md.getMethodSign() + " : " + t);
                }
                if (m == null) {
                    continue;
                }
                m.setAccessible(true);
                dbg("dir dexkit pick " + md.getMethodSign());
                out.add(m);
            }
        } finally {
            // bridge 已被复用，故意不在这里 close —— 否则第二个查询
            // （目录构造点）会重新创建 bridge、再解析一遍同一个 dex。
            // 统一由 install() 结束时的 closeSharedBridge() 释放。
        }
        return out;
    }

    /**
     * 目录构造点形状判据：private static + 返回 Intent +
     *   (仅一个 Context 参数)                        → 4.0.5  view.d#f(Context)
     *   (Context + boolean + TapRecvData + boolean)  → 4.13.8+/5.x view.d#g / view.e#h / view.e#k
     * 带 String 参数的一律排除（那是「去应用市场」之类的另一条构造点）。
     */
    private static boolean isDirAnchorSig(int mods, List<String> params, String ret) {
        if (ret == null || !ret.endsWith("Intent")) {
            return false;
        }
        if (!Modifier.isPrivate(mods) || !Modifier.isStatic(mods)) {
            return false;
        }
        return isDirAnchorParams(params);
    }

    private static boolean isDirAnchorParams(List<String> params) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        if (!"android.content.Context".equals(params.get(0))) {
            return false;
        }
        if (params.size() == 1) {
            return true;
        }
        boolean hasTap = false;
        boolean hasBool = false;
        boolean hasString = false;
        for (int i = 1; i < params.size(); i++) {
            String p = params.get(i);
            if ("boolean".equals(p)) {
                hasBool = true;
            } else if ("java.lang.String".equals(p)) {
                hasString = true;
            } else if (p != null && p.contains("TapRecvData")) {
                hasTap = true;
            }
        }
        return hasTap && hasBool && !hasString;
    }

    private List<Method> findDirAnchorsByDex(ClassLoader cl, ApplicationInfo ai) {
        List<Candidate> all = new ArrayList<>();
        for (String apk : apkPaths(ai)) {
            ZipFile zf = null;
            try {
                zf = new ZipFile(apk);
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (!isDexEntry(e.getName())) {
                        continue;
                    }
                    scanDexForDir(readAll(zf.getInputStream(e)), all);
                }
            } catch (Throwable t) {
                lg(Log.WARN, TAG, "dir scan apk failed: " + apk + " : " + t);
            } finally {
                closeQuietly(zf);
            }
        }
        List<Method> out = new ArrayList<>();
        for (Candidate c : all) {
            if (out.size() >= MAX_DIR_ANCHORS) {
                break;
            }
            Method m = resolveMethod(cl, c.className, c.methodName, c.paramDescs);
            if (m == null) {
                continue;
            }
            if (!isDirAnchorSig(m.getModifiers(), paramNames(m),
                    m.getReturnType().getName())) {
                continue;
            }
            m.setAccessible(true);
            dbg("dir dex-scan pick " + sign(m));
            out.add(m);
        }
        lg(Log.INFO, TAG, "dir dex-scan candidates=" + all.size() + " picked=" + out.size());
        return out;
    }

    private List<Method> findDirAnchorsByShape(ClassLoader cl) {
        List<Method> out = new ArrayList<>();
        for (String name : FALLBACK_CLASSES) {
            Class<?> c;
            try {
                c = cl.loadClass(name);
            } catch (Throwable ignored) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (out.size() >= MAX_DIR_ANCHORS) {
                    return out;
                }
                if (!isDirAnchorSig(m.getModifiers(), paramNames(m),
                        m.getReturnType().getName())) {
                    continue;
                }
                m.setAccessible(true);
                dbg("dir shape pick " + sign(m));
                out.add(m);
            }
        }
        return out;
    }

    /** 只按「目录构造点形状」收集候选（access flags 校验留到反射阶段） */
    private void scanDexForDir(byte[] d, List<Candidate> out) {
        if (d == null || d.length < 0x70) {
            return;
        }
        int strOff = u32(d, 0x3C);
        int typOff = u32(d, 0x44);
        int protoOff = u32(d, 0x4C);
        int methSize = u32(d, 0x58);
        int methOff = u32(d, 0x5C);
        if (strOff <= 0 || typOff <= 0 || protoOff <= 0 || methSize <= 0 || methOff <= 0) {
            return;
        }
        for (int i = 0; i < methSize; i++) {
            int mo = methOff + i * 8;
            int classIdx = u16(d, mo);
            int protoIdx = u16(d, mo + 2);
            int nameIdx = u32(d, mo + 4);
            int po = protoOff + protoIdx * 12;
            if (!"Landroid/content/Intent;".equals(typeDesc(d, strOff, typOff, u32(d, po + 4)))) {
                continue;
            }
            String cls = typeDesc(d, strOff, typOff, classIdx);
            if (cls == null || !cls.startsWith("Lcom/miui/mishare/view/")) {
                continue; // 限定在历史实现类所在的包，避免全库误挂
            }
            int paramsOff = u32(d, po + 8);
            List<String> params = new ArrayList<>();
            if (paramsOff > 0) {
                int sz = u32(d, paramsOff);
                for (int j = 0; j < sz; j++) {
                    params.add(typeDesc(d, strOff, typOff, u16(d, paramsOff + 4 + j * 2)));
                }
            }
            List<String> names = new ArrayList<>(params.size());
            for (String p : params) {
                names.add(paramTypeName(p));
            }
            if (!isDirAnchorParams(names)) {
                continue;
            }
            Candidate c = new Candidate();
            c.className = descriptorToName(cls);
            c.methodName = readString(d, u32(d, strOff + nameIdx * 4));
            c.paramDescs = params.toArray(new String[0]);
            out.add(c);
        }
    }

    private static String paramTypeName(String desc) {
        if (desc == null) {
            return null;
        }
        if ("Z".equals(desc)) {
            return "boolean";
        }
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return descriptorToName(desc);
        }
        return desc;
    }

    private static List<String> paramNames(Method m) {
        List<String> names = new ArrayList<>();
        for (Class<?> p : m.getParameterTypes()) {
            names.add(p.getName());
        }
        return names;
    }

    private static List<String> apkPaths(ApplicationInfo ai) {
        List<String> apks = new ArrayList<>();
        if (ai != null) {
            if (ai.sourceDir != null) {
                apks.add(ai.sourceDir);
            }
            if (ai.splitSourceDirs != null) {
                for (String s : ai.splitSourceDirs) {
                    if (s != null) {
                        apks.add(s);
                    }
                }
            }
        }
        return apks;
    }

    /** 反射调用返回 Uri / String 的取值方法，统一成字符串交给 realPath */
    private static String callPath(Object obj, String name, Object... args) {
        if (obj == null) {
            return null;
        }
        try {
            for (Method m : obj.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < args.length; i++) {
                    Object a = args[i];
                    if (a == null) {
                        ok = false;
                        break;
                    }
                    if (!m.getParameterTypes()[i].isAssignableFrom(a.getClass())) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                m.setAccessible(true);
                Object r = m.invoke(obj, args);
                return r == null ? null : String.valueOf(r);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private List<Method> findDispatchersByDexKit(ClassLoader cl, ApplicationInfo ai) {
        List<Method> out = new ArrayList<>();
        if (ai == null || ai.sourceDir == null || !ensureDexKit()) {
            return out;
        }
        DexKitBridge bridge = null;
        try {
            bridge = sharedBridge(ai);
            if (bridge == null || !bridge.isValid()) {
                lg(Log.WARN, TAG, "dexkit bridge invalid");
                return out;
            }
            long tQ = SystemClock.uptimeMillis();
            MethodDataList list = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("android.content.Intent",
                                    StringMatchType.Equals, false)));
            sDexKitQueryMs += SystemClock.uptimeMillis() - tQ;
            List<Scored> scored = new ArrayList<>();
            for (MethodData md : list) {
                int score = scoreDispatcher(md.getParamTypeNames(), md.getModifiers(),
                        md.getReturnTypeName());
                if (score > 0) {
                    scored.add(new Scored(md, score));
                }
            }
            lg(Log.INFO, TAG, "dexkit intent-methods=" + list.size() + " scored=" + scored.size());
            if (scored.isEmpty()) {
                return out;
            }
            Collections.sort(scored, new Comparator<Scored>() {
                @Override
                public int compare(Scored a, Scored b) {
                    return b.score - a.score;
                }
            });
            int best = scored.get(0).score;
            int floor = Math.max(MIN_SCORE, best * 2 / 3);
            for (Scored s : scored) {
                if (out.size() >= MAX_HOOKS || s.score < floor) {
                    break;
                }
                Method m = null;
                try {
                    m = s.data.getMethodInstance(cl);
                } catch (Throwable t) {
                    dbg("dexkit getMethodInstance failed: " + s.data.getMethodSign() + " : " + t);
                }
                if (m == null) {
                    continue;
                }
                m.setAccessible(true);
                dbg("dexkit pick " + s.data.getMethodSign() + " score=" + s.score);
                out.add(m);
            }
            lg(Log.INFO, TAG, "dexkit best=" + best + " floor=" + floor + " picked=" + out.size());
        } finally {
            // bridge 已被复用，故意不在这里 close —— 否则第二个查询
            // （目录构造点）会重新创建 bridge、再解析一遍同一个 dex。
            // 统一由 install() 结束时的 closeSharedBridge() 释放。
        }
        return out;
    }

    /** 类型名归一：`[Lcom/x/FileInfo;`、`com.x.FileInfo[]` 统一成 `com.x.FileInfo[]` */
    private static String normType(String t) {
        if (t == null) {
            return null;
        }
        String s = t.trim();
        int arr = 0;
        while (s.startsWith("[")) {
            arr++;
            s = s.substring(1);
        }
        while (s.endsWith("[]")) {
            arr++;
            s = s.substring(0, s.length() - 2);
        }
        if (s.startsWith("L") && s.endsWith(";")) {
            s = s.substring(1, s.length() - 1);
        }
        s = s.replace('/', '.');
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < arr; i++) {
            sb.append("[]");
        }
        return sb.toString();
    }

    private static String simpleName(String javaType) {
        if (javaType == null) {
            return "";
        }
        int dot = javaType.lastIndexOf('.');
        return dot >= 0 ? javaType.substring(dot + 1) : javaType;
    }

    /**
     * 分流中枢打分（口径见 {@link #findDispatchersByDexKit}）。
     * 返回 0 表示「与文件模型无关」，直接排除。
     */
    private static int scoreDispatcher(List<String> params, int modifiers, String returnType) {
        if (params == null || params.isEmpty()) {
            return 0;
        }
        int arrFile = 0;
        int objFile = 0;
        int tap = 0;
        int ctx = 0;
        for (String raw : params) {
            String p = normType(raw);
            if (p == null || p.isEmpty()) {
                continue;
            }
            boolean isArr = p.endsWith("[]");
            String base = isArr ? p.substring(0, p.length() - 2) : p;
            if ("android.content.Context".equals(base)) {
                ctx++;
            }
            String simple = simpleName(base);
            if (simple.endsWith("TapRecvData")) {
                tap++;
            }
            if (simple.endsWith("FileInfo")) {
                if (isArr) {
                    arrFile++;
                } else {
                    objFile++;
                }
            }
        }
        if (arrFile == 0 && objFile == 0) {
            return 0;
        }
        int s = 0;
        if (arrFile > 0) {
            s += 60;
        }
        if (objFile > 0) {
            s += 25;
        }
        s += params.size() * 8;
        if (tap > 0) {
            s += 15;
        }
        if (ctx > 0) {
            s += 5;
        }
        if ((modifiers & Modifier.STATIC) != 0) {
            s += 10;
        }
        String rt = normType(returnType);
        if (rt != null && simpleName(rt).endsWith("Intent")) {
            s += 5;
        }
        return s;
    }

    /** 候选方法 + 分数 */
    private static final class Scored {
        final MethodData data;
        final int score;

        Scored(MethodData data, int score) {
            this.data = data;
            this.score = score;
        }
    }

    /** 加载 libdexkit.so：先按标准方式，失败再从模块 APK 里解出来手动加载 */
    private boolean ensureDexKit() {
        if (sDexKitLoaded != null) {
            return sDexKitLoaded;
        }
        boolean ok = false;
        try {
            System.loadLibrary("dexkit");
            ok = true;
        } catch (Throwable t) {
            dbg("System.loadLibrary(dexkit) failed: " + t);
        }
        if (!ok) {
            ok = loadDexKitFromModuleApk();
        }
        sDexKitLoaded = ok;
        lg(ok ? Log.INFO : Log.WARN, TAG, "dexkit native loaded=" + ok);
        return ok;
    }

    /**
     * 兜底：框架没把模块的 native 目录带进来时，
     * 直接从模块 APK 里把 libdexkit.so 解出来再 System.load。
     */
    private boolean loadDexKitFromModuleApk() {
        try {
            ApplicationInfo mai = getModuleApplicationInfo();
            Context ctx = appContext();
            if (mai == null || mai.sourceDir == null || ctx == null) {
                return false;
            }
            File dir = new File(ctx.getCodeCacheDir(), "mt_dexkit");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            File out = new File(dir, "libdexkit.so");
            ZipFile zf = null;
            InputStream in = null;
            FileOutputStream fos = null;
            try {
                zf = new ZipFile(mai.sourceDir);
                ZipEntry e = zf.getEntry("lib/arm64-v8a/libdexkit.so");
                if (e == null) {
                    return false;
                }
                in = zf.getInputStream(e);
                fos = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            } finally {
                closeQuietly(fos);
                closeQuietly(in);
                closeQuietly(zf);
            }
            out.setReadable(true, true);
            out.setExecutable(true, true);
            System.load(out.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            dbg("manual dexkit load failed: " + t);
            return false;
        }
    }

    /** 宿主应用 Context（只用于手动解 so；取不到不影响主流程） */
    private static Context sApp;

    /**
     * 取（或创建）本进程共用的 DexKit bridge。
     * 目标 dex 路径相同就复用同一个实例：一次解析，两处查询。
     */
    private DexKitBridge sharedBridge(ApplicationInfo ai) {
        if (ai == null || ai.sourceDir == null || !ensureDexKit()) {
            return null;
        }
        if (mBridge != null && ai.sourceDir.equals(mBridgePath)) {
            return mBridge;
        }
        closeSharedBridge();
        long tB = SystemClock.uptimeMillis();
        DexKitBridge b = DexKitBridge.create(ai.sourceDir);
        sBridgeMs += SystemClock.uptimeMillis() - tB;
        if (b == null || !b.isValid()) {
            lg(Log.WARN, TAG, "dexkit bridge invalid");
            return null;
        }
        mBridge = b;
        mBridgePath = ai.sourceDir;
        return b;
    }

    /** 释放复用的 bridge（install() 结束时调用，避免长期占着 dex 索引内存） */
    private void closeSharedBridge() {
        DexKitBridge b = mBridge;
        mBridge = null;
        mBridgePath = null;
        if (b != null) {
            try {
                b.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 注册「状态按需重发」监听（包名校验，避免挂到系统 Context 上） */
    private static void ensureStatusPull(Context ctx) {
        StatusReqListener.register(ctx, TARGET_PKG, new Runnable() {
            @Override
            public void run() {
                writeStatus(sStatusBody != null ? sStatusBody : "result=unknown\n");
            }
        });
    }

    private static Context appContext() {
        if (sApp != null) {
            return sApp;
        }
        try {
            Method cur = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication");
            sApp = (Context) cur.invoke(null);
        } catch (Throwable ignored) {
        }
        return sApp;
    }

    /**
     * 尽量拿到一个可用 Context：
     *   1) 框架给的应用 Context（onPackageLoaded 阶段可能还没就绪 —— v1.8 之前这里
     *      为 null，直接导致状态页 target unknown 且 MT 探测整体跳过）；
     *   2) ActivityThread 的系统 Context（对自己包做 PackageManager 查询足够，
     *      且不受目标应用包可见性限制影响）。
     */
    private static Context anyContext() {
        Context c = appContext();
        if (c != null) {
            return c;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object t = at.getMethod("currentActivityThread").invoke(null);
            if (t != null) {
                Object sc = at.getMethod("getSystemContext").invoke(t);
                if (sc instanceof Context) {
                    return (Context) sc;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // =====================================================================
    // 定位：签名形状扫描（自带极简 dex 解析）
    // =====================================================================

    private List<Method> findDispatchersByDex(ClassLoader cl, ApplicationInfo ai) {
        List<String> apks = new ArrayList<>();
        if (ai != null) {
            if (ai.sourceDir != null) {
                apks.add(ai.sourceDir);
            }
            if (ai.splitSourceDirs != null) {
                for (String s : ai.splitSourceDirs) {
                    if (s != null) {
                        apks.add(s);
                    }
                }
            }
        }
        List<Candidate> all = new ArrayList<>();
        for (String apk : apks) {
            ZipFile zf = null;
            try {
                zf = new ZipFile(apk);
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!isDexEntry(n)) {
                        continue;
                    }
                    byte[] data = readAll(zf.getInputStream(e));
                    scanDex(data, all);
                }
            } catch (Throwable t) {
                lg(Log.WARN, TAG, "scan apk failed: " + apk + " : " + t);
            } finally {
                closeQuietly(zf);
            }
        }
        List<Method> out = new ArrayList<>();
        int best = -1;
        for (Candidate c : all) {
            if (c.score > best) {
                best = c.score;
            }
        }
        if (best <= 0) {
            return out;
        }
        Collections.sort(all, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return b.score - a.score;
            }
        });
        int floor = Math.max(MIN_SCORE, best * 2 / 3);
        for (Candidate c : all) {
            if (out.size() >= MAX_HOOKS || c.score < floor) {
                break;
            }
            Method m = resolveMethod(cl, c.className, c.methodName, c.paramDescs);
            if (m == null) {
                continue;
            }
            m.setAccessible(true);
            dbg("dex-scan pick " + c.className + "#" + c.methodName + " score=" + c.score);
            out.add(m);
        }
        lg(Log.INFO, TAG, "dex-scan total=" + all.size() + " best=" + best
                + " floor=" + floor + " picked=" + out.size());
        return out;
    }

    private List<Method> findDispatchersByFallback(ClassLoader cl) {
        List<ScoredMethod> cands = new ArrayList<>();
        for (String name : FALLBACK_CLASSES) {
            Class<?> c;
            try {
                c = cl.loadClass(name);
            } catch (Throwable ignored) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != Intent.class || !hasFileInfoParam(m.getParameterTypes())) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                List<String> names = new ArrayList<>(pts.length);
                for (Class<?> pt : pts) {
                    names.add(pt.getName());
                }
                int score = scoreDispatcher(names, m.getModifiers(), "android.content.Intent");
                if (score > 0) {
                    cands.add(new ScoredMethod(m, score));
                }
            }
        }
        List<Method> out = new ArrayList<>();
        if (cands.isEmpty()) {
            return out;
        }
        Collections.sort(cands, new Comparator<ScoredMethod>() {
            @Override
            public int compare(ScoredMethod a, ScoredMethod b) {
                return b.score - a.score;
            }
        });
        int floor = Math.max(MIN_SCORE, cands.get(0).score * 2 / 3);
        for (ScoredMethod s : cands) {
            if (out.size() >= MAX_HOOKS || s.score < floor) {
                break;
            }
            s.method.setAccessible(true);
            dbg("fallback pick " + sign(s.method) + " score=" + s.score);
            out.add(s.method);
        }
        return out;
    }

    /** 反射候选 + 分数 */
    private static final class ScoredMethod {
        final Method method;
        final int score;

        ScoredMethod(Method method, int score) {
            this.method = method;
            this.score = score;
        }
    }

    private Method resolveMethod(ClassLoader cl, String className, String methodName, String[] paramDescs) {
        try {
            Class<?> c = cl.loadClass(className);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != paramDescs.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < pts.length; i++) {
                    if (!descOf(pts[i]).equals(paramDescs[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable t) {
            lg(Log.WARN, TAG, "resolveMethod failed: " + className + " : " + t);
        }
        return null;
    }

    // =====================================================================
    // 极简 dex 解析：只读 header + string/type/proto/method 四张表
    // =====================================================================

    private void scanDex(byte[] d, List<Candidate> out) {
        if (d == null || d.length < 0x70) {
            return;
        }
        int strOff = u32(d, 0x3C);
        int typOff = u32(d, 0x44);
        int protoOff = u32(d, 0x4C);
        int methSize = u32(d, 0x58);
        int methOff = u32(d, 0x5C);
        if (strOff <= 0 || typOff <= 0 || protoOff <= 0 || methSize <= 0 || methOff <= 0) {
            return;
        }
        for (int i = 0; i < methSize; i++) {
            int mo = methOff + i * 8;
            int classIdx = u16(d, mo);
            int protoIdx = u16(d, mo + 2);
            int nameIdx = u32(d, mo + 4);
            int po = protoOff + protoIdx * 12;
            String ret = typeDesc(d, strOff, typOff, u32(d, po + 4));
            if (!"Landroid/content/Intent;".equals(ret)) {
                continue;
            }
            int paramsOff = u32(d, po + 8);
            List<String> params = new ArrayList<>();
            if (paramsOff > 0) {
                int sz = u32(d, paramsOff);
                for (int j = 0; j < sz; j++) {
                    params.add(typeDesc(d, strOff, typOff, u16(d, paramsOff + 4 + j * 2)));
                }
            }
            // 与 DexKit 路线同一打分口径（这里拿不到 access flags，故不含静态加分）
            int score = scoreDispatcher(params, 0, ret);
            if (score <= 0) {
                continue;
            }
            Candidate c = new Candidate();
            c.score = score;
            c.className = descriptorToName(typeDesc(d, strOff, typOff, classIdx));
            c.methodName = readString(d, u32(d, strOff + nameIdx * 4));
            c.paramDescs = params.toArray(new String[0]);
            out.add(c);
        }
    }

    private static final class Candidate {
        int score;
        String className;
        String methodName;
        String[] paramDescs;
    }

    private static String typeDesc(byte[] d, int strOff, int typOff, int typeIdx) {
        int strIdx = u32(d, typOff + typeIdx * 4);
        int dataOff = u32(d, strOff + strIdx * 4);
        return readString(d, dataOff);
    }

    private static int u16(byte[] d, int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8);
    }

    private static int u32(byte[] d, int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8)
                | ((d[o + 2] & 0xff) << 16) | ((d[o + 3] & 0xff) << 24);
    }

    /** dex string_data_item：uleb128 长度 + MUTF-8 字节 + NUL */
    private static String readString(byte[] d, int off) {
        int p = off;
        int shift = 0;
        while (p < d.length) {
            int b = d[p++] & 0xff;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 28) {
                break;
            }
        }
        StringBuilder sb = new StringBuilder(32);
        while (p < d.length) {
            int a = d[p++] & 0xff;
            if (a == 0) {
                break;
            }
            if (a < 0x80) {
                sb.append((char) a);
            } else if ((a & 0xe0) == 0xc0 && p < d.length) {
                int b = d[p++] & 0x3f;
                sb.append((char) (((a & 0x1f) << 6) | b));
            } else if ((a & 0xf0) == 0xe0 && p + 1 < d.length) {
                int b = d[p++] & 0x3f;
                int c = d[p++] & 0x3f;
                sb.append((char) (((a & 0x0f) << 12) | (b << 6) | c));
            } else {
                break;
            }
        }
        return sb.toString();
    }

    // =====================================================================
    // 类型判断与工具
    // =====================================================================

    /** 命中图片/视频/联系人 => 保持原样 */
    private boolean isKeepOriginal(Object fileInfo) {
        String mime = (String) getField(fileInfo, "mimeType");
        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.startsWith("image/") || m.startsWith("video/")) {
                return true;
            }
            if (m.equals("text/x-vcard") || m.equals("text/vcard") || m.equals("text/directory")) {
                return true;
            }
        }
        String name = (String) getField(fileInfo, "name");
        if (name == null) {
            name = (String) getField(fileInfo, "path");
        }
        String e = ext(name);
        return e != null && KEEP_EXTS.contains(e);
    }

    private static String ext(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String describe(Object f) {
        return String.valueOf(getField(f, "name")) + " [" + getField(f, "mimeType") + "]";
    }

    /** 在参数中找出 FileInfo[] */
    /**
     * 在参数里找「文件数组」。
     *
     * 版本无关化：不再只认类名以 FileInfo 结尾，而是
     *   1) 元素类型类名以 FileInfo 结尾（4.13.8 / 5.3.4 / 5.4.2 都成立），或
     *   2) 该元素类型「长得像文件模型」——同时拥有 name + path + mimeType 字段。
     *      实测这三个字段名在 4.13.8 与 5.x 完全相同，是比类名更稳的锚点。
     * 这样即使未来改类名/挪包名，也只按字段形状识别。
     */
    private static Object[] findFileInfoArray(Object[] args) {
        for (Object a : args) {
            if (a == null || !a.getClass().isArray()) {
                continue;
            }
            Class<?> comp = a.getClass().getComponentType();
            if (comp != null && (comp.getName().endsWith("FileInfo") || looksLikeFileInfo(comp))) {
                return (Object[]) a;
            }
        }
        return null;
    }

    /** 判过的类缓存，避免每次调用都反射一遍字段 */
    private static final Map<Class<?>, Boolean> sFileInfoCache = new HashMap<>();

    /** 字段形状判据：name + path + mimeType 三个字段同时存在 */
    private static boolean looksLikeFileInfo(Class<?> c) {
        if (c == null || c.isPrimitive()) {
            return false;
        }
        synchronized (sFileInfoCache) {
            Boolean hit = sFileInfoCache.get(c);
            if (hit != null) {
                return hit;
            }
        }
        boolean r = false;
        try {
            boolean name = false;
            boolean path = false;
            boolean mime = false;
            Class<?> t = c;
            while (t != null && t != Object.class) {
                for (Field f : t.getDeclaredFields()) {
                    String n = f.getName();
                    if ("name".equals(n)) {
                        name = true;
                    } else if ("path".equals(n)) {
                        path = true;
                    } else if ("mimeType".equals(n)) {
                        mime = true;
                    }
                }
                t = t.getSuperclass();
            }
            r = name && path && mime;
        } catch (Throwable ignored) {
        }
        synchronized (sFileInfoCache) {
            sFileInfoCache.put(c, r);
        }
        return r;
    }

    private static Context findContext(Object[] args) {
        for (Object a : args) {
            if (a instanceof Context) {
                return (Context) a;
            }
        }
        return null;
    }

    /**
     * 反射读字段。
     *
     * 原先每次调用都要 getDeclaredField + setAccessible（一次点击最坏要读十几个字段），
     * 现在按「类 + 字段名」缓存 Field 对象，命中后只剩一次 Field.get()。
     * 字段缺失同样缓存（用 FIELD_ABSENT 占位），避免反复走异常路径。
     */
    private static Object getField(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        Field f = fieldOf(obj.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final Map<Class<?>, Map<String, Object>> sFieldCache = new ConcurrentHashMap<>();
    private static final Object FIELD_ABSENT = new Object();

    private static Field fieldOf(Class<?> c, String name) {
        Map<String, Object> m = sFieldCache.get(c);
        if (m == null) {
            Map<String, Object> fresh = new ConcurrentHashMap<>();
            Map<String, Object> prev = sFieldCache.putIfAbsent(c, fresh);
            m = prev != null ? prev : fresh;
        }
        Object hit = m.get(name);
        if (hit != null) {
            return hit == FIELD_ABSENT ? null : (Field) hit;
        }
        Field found = null;
        Class<?> t = c;
        while (t != null && t != Object.class) {
            try {
                Field f = t.getDeclaredField(name);
                f.setAccessible(true);
                found = f;
                break;
            } catch (NoSuchFieldException nsfe) {
                t = t.getSuperclass();
            } catch (Throwable other) {
                break;
            }
        }
        m.put(name, found == null ? FIELD_ABSENT : found);
        return found;
    }

    /** 参数里 FileInfo[] 的下标（-1 = 没有）；只在挂载 hook 时算一次 */
    private static int indexOfFileInfoArrayParam(Method m) {
        Class<?>[] pts;
        try {
            pts = m.getParameterTypes();
        } catch (Throwable t) {
            return -1;
        }
        for (int i = 0; i < pts.length; i++) {
            Class<?> p = pts[i];
            if (!p.isArray()) {
                continue;
            }
            Class<?> comp = p.getComponentType();
            if (comp != null && (comp.getName().endsWith("FileInfo") || looksLikeFileInfo(comp))) {
                return i;
            }
        }
        return -1;
    }

    /** 参数里 Context 的下标（-1 = 没有） */
    private static int indexOfContextParam(Method m) {
        Class<?>[] pts;
        try {
            pts = m.getParameterTypes();
        } catch (Throwable t) {
            return -1;
        }
        for (int i = 0; i < pts.length; i++) {
            if (Context.class.isAssignableFrom(pts[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 取 FileInfo[] 参数：优先用挂载时算好的下标，
     * 取不到再退回「遍历参数 + 形状识别」（保证任何签名都不会漏）。
     */
    private static Object[] argFiles(Chain chain, int idx) {
        List<Object> args = chain.getArgs();
        if (idx >= 0 && args != null && idx < args.size()) {
            Object a = args.get(idx);
            if (a instanceof Object[]) {
                return (Object[]) a;
            }
        }
        return findFileInfoArray(args == null ? new Object[0] : args.toArray());
    }

    /** 取 Context 参数：同上 */
    private static Context argContext(Chain chain, int idx) {
        List<Object> args = chain.getArgs();
        if (idx >= 0 && args != null && idx < args.size()) {
            Object a = args.get(idx);
            if (a instanceof Context) {
                return (Context) a;
            }
        }
        return findContext(args == null ? new Object[0] : args.toArray());
    }

    private static boolean hasFileInfoParam(Class<?>[] pts) {
        for (Class<?> p : pts) {
            if (!p.isArray()) {
                continue;
            }
            Class<?> comp = p.getComponentType();
            if (comp != null && (comp.getName().endsWith("FileInfo") || looksLikeFileInfo(comp))) {
                return true;
            }
        }
        return false;
    }

    private static String descOf(Class<?> c) {
        if (c.isArray()) {
            return "[" + descOf(c.getComponentType());
        }
        if (c.isPrimitive()) {
            if (c == boolean.class) return "Z";
            if (c == byte.class) return "B";
            if (c == char.class) return "C";
            if (c == short.class) return "S";
            if (c == int.class) return "I";
            if (c == long.class) return "J";
            if (c == float.class) return "F";
            if (c == double.class) return "D";
            return "V";
        }
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private static String descriptorToName(String desc) {
        if (desc == null) {
            return null;
        }
        String s = desc;
        if (s.startsWith("L") && s.endsWith(";")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace('/', '.');
    }

    private static boolean notEmpty(String s) {
        return s != null && s.length() > 0;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isDexEntry(String name) {
        return name.startsWith("classes") && name.endsWith(".dex");
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(ZipFile zf) {
        if (zf != null) {
            try {
                zf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // =====================================================================
    // 版本自适应：常量与辅助
    // =====================================================================

    /** 最多同时挂几个分流候选（覆盖同一版本里可能存在的多条打开路径） */
    private static final int MAX_HOOKS = 4;
    /** 打分下限：低于此分不认为是分流中枢 */
    private static final int MIN_SCORE = 90;

    /**
     * 这些 action 属于「分享 / 选择器」，与「打开」无关，必须原样保留。
     *
     * <p>用 Set 而不是数组：{@link #isShareIntent} 在每次拦截（也就是每次点「打开」）
     * 都会走一遍，线性 equals 扫描换成哈希查表后是 O(1) 的常量开销。</p>
     */
    private static final Set<String> SHARE_ACTIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    Intent.ACTION_SEND,
                    Intent.ACTION_SEND_MULTIPLE,
                    Intent.ACTION_CHOOSER)));

    /** 统一写法：类名#方法名(参数简名...) */
    private static String sign(Method m) {
        return m.getDeclaringClass().getName() + "#" + m.getName() + paramSuffix(m);
    }

    private static String actionOf(Object r) {
        return (r instanceof Intent) ? ((Intent) r).getAction() : null;
    }

    private static boolean isShareIntent(Object r) {
        String a = actionOf(r);
        if (a == null) {
            return false;
        }
        return SHARE_ACTIONS.contains(a);
    }

    /** 目标应用版本，例如 "5.3.4(50304)"；取不到返回 unknown */
    private String targetVersion(Context ctx) {
        Context c = ctx != null ? ctx : anyContext();
        if (c == null) {
            return "unknown";
        }
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(TARGET_PKG, 0);
            return pi.versionName + "(" + (long) pi.versionCode + ")";
        } catch (Throwable t) {
            dbg("targetVersion failed: " + t);
            return "unknown";
        }
    }

    /**
     * 运行期适配状态落盘（best-effort，失败无副作用），设置页读取后展示。
     * 目标进程能写模块 Android/media 目录的前提是它有存储权限
     * （小米互传必然有——它本来就要往 /sdcard 各处落文件）；
     * 写不进去时设置页会提示改看 LSPosed 日志。
     */
    private static final String[] STATUS_PATHS = {
            "/sdcard/Android/media/com.hyper.Camellia/misharemt2.status",
            "/storage/emulated/0/Android/media/com.hyper.Camellia/misharemt2.status",
    };

    private static void writeStatus(String body) {
        sStatusBody = body;
        String text = body + perfLine() + sSelfTestNote
                + "time=" + new java.util.Date() + "\n";
        for (String p : STATUS_PATHS) {
            FileOutputStream fos = null;
            try {
                File f = new File(p);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                fos = new FileOutputStream(f);
                fos.write(text.getBytes("UTF-8"));
                fos.flush();
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(fos);
            }
        }
        // 目标进程写不了本模块的 media 目录，状态用广播回传给设置页
        StatusReceiver.send(anyContext(), StatusReceiver.SRC_MISHARE, text);
    }

    // =====================================================================
    // hook 签名缓存：同一份 APK 只解析一次 dex
    // =====================================================================

    /**
     * 缓存键 = versionName(versionCode)|lastUpdateTime。
     *
     * 只要 APK 被升级/降级/重装，lastUpdateTime 必然变化，缓存立即失效并退回
     * DexKit 全量解析后重写缓存，因此缓存不可能指向「另一份 dex」。
     * 命中时仍然逐条反射校验：类存在、方法名/参数个数/参数类型/返回类型完全一致
     * 才采用（任一条不符就整体放弃，走原路径）。
     *
     * 收益：install() 在每个互传进程启动时都会跑，创建 DexKit bridge + 扫描
     * dex 需要 30~80ms；命中缓存后只剩几次 Class.forName + getDeclaredMethod，
     * <2ms。
     */
    private static final String[] SIG_CACHE_PATHS = {
            "/sdcard/Android/media/com.hyper.Camellia/hooksig.cache",
            "/storage/emulated/0/Android/media/com.hyper.Camellia/hooksig.cache",
    };

    /**
     * 签名缓存格式 / 定位策略版本号。
     * 只要改动"怎么找目标方法"（锚点、候选类、dir 锚点策略）或缓存文件结构，就必须 bump：
     * 否则模块升级而小米互传未升级时旧缓存的 key 不变、仍会命中，改动被静默绕过
     * （历史故障：新增 dir 锚点被旧缓存整体绕过，表现为"改了没用，清缓存才好"）。
     */
    private static final String SIG_CACHE_FMT = "tapdir-v1";

    /** 缓存解析结果：picks/dirs 要么全用缓存，要么整体回退 */
    private static final class SigCache {
        final List<Method> picks = new ArrayList<>();
        final List<Method> dirs = new ArrayList<>();
    }

    private static String sigCacheKey(ApplicationInfo ai, Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
            return pi.versionName + "(" + pi.versionCode + ")|" + pi.lastUpdateTime;
        } catch (Throwable t) {
            return null;
        }
    }

    private SigCache loadSigCache(ClassLoader cl, ApplicationInfo ai) {
        try {
            Context ctx = anyContext();
            if (ctx == null) {
                return null;
            }
            String key = sigCacheKey(ai, ctx);
            if (key == null) {
                return null;
            }
            String text = readFirstText(SIG_CACHE_PATHS);
            if (text == null) {
                return null;
            }
            SigCache c = new SigCache();
            String fmt = null;
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String kind = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if ("key".equals(kind)) {
                    if (!key.equals(val)) {
                        dbg("sig cache stale (" + val + " != " + key + ")");
                        return null;
                    }
                } else if ("fmt".equals(kind)) {
                    fmt = val;
                } else if ("tap".equals(kind) || "dir".equals(kind)) {
                    Method m = resolveSig(cl, val);
                    if (m == null) {
                        dbg("sig cache miss [" + val + "]");
                        return null;
                    }
                    (("tap".equals(kind)) ? c.picks : c.dirs).add(m);
                }
            }
            if (!SIG_CACHE_FMT.equals(fmt)) {
                dbg("sig cache fmt stale (" + fmt + " != " + SIG_CACHE_FMT + ")");
                return null;
            }
            if (c.picks.isEmpty()) {
                return null;
            }
            lg(Log.INFO, TAG, "sig cache hit: tap=" + c.picks.size()
                    + ", dir=" + c.dirs.size() + ", key=" + key);
            return c;
        } catch (Throwable t) {
            dbg("sig cache read failed: " + t);
            return null;
        }
    }

    /** 反射解析一条缓存签名：Class#name#p1,p2#ret */
    private static Method resolveSig(ClassLoader cl, String sig) {
        try {
            int h1 = sig.indexOf('#');
            int h2 = sig.indexOf('#', h1 + 1);
            int h3 = sig.indexOf('#', h2 + 1);
            if (h1 < 0 || h2 < 0 || h3 < 0) {
                return null;
            }
            Class<?> cls = classForName(sig.substring(0, h1), cl);
            String name = sig.substring(h1 + 1, h2);
            String params = sig.substring(h2 + 1, h3);
            Class<?> ret = classForName(sig.substring(h3 + 1), cl);
            if (cls == null || ret == null) {
                return null;
            }
            Class<?>[] pts = classesForNames(params, cl);
            if (pts == null) {
                return null;
            }
            Method m = cls.getDeclaredMethod(name, pts);
            if (!Modifier.isStatic(m.getModifiers())) {
                return null;
            }
            if (!m.getReturnType().equals(ret)) {
                return null;
            }
            // 缓存路径不走 findXxx()，要自己打开可见性（非 public 类/方法）
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> classForName(String n, ClassLoader cl) {
        try {
            if ("boolean".equals(n)) {
                return boolean.class;
            }
            if ("int".equals(n)) {
                return int.class;
            }
            if ("long".equals(n)) {
                return long.class;
            }
            if ("void".equals(n)) {
                return void.class;
            }
            return Class.forName(n, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?>[] classesForNames(String params, ClassLoader cl) {
        if (params == null || params.length() == 0) {
            return new Class<?>[0];
        }
        String[] parts = params.split(",");
        Class<?>[] pts = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Class<?> c = classForName(parts[i].trim(), cl);
            if (c == null) {
                return null;
            }
            pts[i] = c;
        }
        return pts;
    }

    private void saveSigCache(ApplicationInfo ai, List<Method> picks, List<Method> dirs) {
        try {
            Context ctx = anyContext();
            if (ctx == null || picks.isEmpty()) {
                return;
            }
            String key = sigCacheKey(ai, ctx);
            if (key == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("fmt=").append(SIG_CACHE_FMT).append('\n');
            sb.append("key=").append(key).append('\n');
            for (Method m : picks) {
                sb.append("tap=").append(sigOf(m)).append('\n');
            }
            for (Method m : dirs) {
                sb.append("dir=").append(sigOf(m)).append('\n');
            }
            if (writeAllText(SIG_CACHE_PATHS, sb.toString())) {
                dbg("sig cache saved (tap=" + picks.size() + ", dir=" + dirs.size() + ")");
            } else {
                dbg("sig cache write failed");
            }
        } catch (Throwable t) {
            dbg("sig cache save failed: " + t);
        }
    }

    private static String sigOf(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getName()).append('#').append(m.getName()).append('#');
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(pts[i].getName());
        }
        return sb.append('#').append(m.getReturnType().getName()).toString();
    }

    private static String readFirstText(String[] paths) {
        for (String p : paths) {
            try {
                File f = new File(p);
                if (f.isFile()) {
                    return readText(f);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean writeAllText(String[] paths, String text) {
        boolean ok = false;
        for (String p : paths) {
            FileOutputStream fos = null;
            try {
                File f = new File(p);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                fos = new FileOutputStream(f);
                fos.write(text.getBytes("UTF-8"));
                fos.flush();
                ok = true;
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(fos);
            }
        }
        return ok;
    }


    // =====================================================================

    /**
     * 自检标记：在模块的 Android/media 目录下放一个名为 selftest 的文件，
     * 下一次互传进程启动时就会跑一次热路径自检，结果写进 status 的 selftest= 行。
     *
     * 目的：单次拦截（点通知「打开」/「打开接收目录」）的耗时不便于反复复现，
     * 这里用合成参数直接调用已挂载的 hook 点，走的是**同一条**拦截链路
     * （proceed -> 判类型 -> buildMtIntent(ForDir) -> MT 探测 -> resolveDir），
     * 因此量出来的耗时就是真实点击时的量级。返回值丢弃，不会真的跳转。
     */
    private static final String[] SELFTEST_MARKERS = {
            "/sdcard/Android/media/com.hyper.Camellia/selftest",
            "/storage/emulated/0/Android/media/com.hyper.Camellia/selftest",
    };
    /** 合成 FileInfo 指向的真实文件（让 resolveDir 的 exists 分支也能被走到） */
    private static final String SELFTEST_FILE =
            "/sdcard/Android/media/com.hyper.Camellia/selftest.txt";

    private static boolean selfTestRequested() {
        for (String p : SELFTEST_MARKERS) {
            try {
                if (new File(p).exists()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private void runSelfTest(List<Method> picks, List<Method> dirs) {
        sSelfTestNote = "";
        try {
            ensureSelfTestFile();
            Context ctx = anyContext();
            StringBuilder sb = new StringBuilder();
            for (Method m : dirs) {
                testOneHook(m, ctx, sb, "dir");
            }
            for (Method m : picks) {
                testOneHook(m, ctx, sb, "tap");
            }
            lg(Log.INFO, TAG, "selftest " + sb);
            sSelfTestNote = "selftest=" + sb + "\n";
        } catch (Throwable t) {
            sSelfTestNote = "selftest=failed:" + t + "\n";
        }
    }

    private static void ensureSelfTestFile() {
        try {
            File probe = new File(SELFTEST_FILE);
            if (probe.exists()) {
                return;
            }
            File parent = probe.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(probe);
            try {
                fos.write("selftest".getBytes("UTF-8"));
                fos.flush();
            } finally {
                closeQuietly(fos);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void testOneHook(Method m, Context ctx, StringBuilder sb, String kind) {
        Object[] args;
        try {
            args = syntheticArgs(m, ctx);
        } catch (Throwable t) {
            return;
        }
        long c0 = sInterceptCount;
        long t0 = SystemClock.uptimeMillis();
        String outcome;
        try {
            Object r = m.invoke(null, args);
            outcome = r instanceof Intent ? "intent"
                    : (r == null ? "" : r.getClass().getSimpleName());
        } catch (Throwable t) {
            outcome = "throw:" + t.getClass().getSimpleName();
        }
        long ms = SystemClock.uptimeMillis() - t0;
        long hooked = sInterceptCount - c0;
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(kind).append('/').append(sign(m))
                .append('=').append(ms).append("ms:")
                .append(outcome).append(",hook+").append(hooked);
    }

    /** 合成参数：Context 用当前进程 Context，FileInfo[] 造一个带真实路径的样本，其余给默认值 */
    private static Object[] syntheticArgs(Method m, Context ctx) {
        Class<?>[] pts = m.getParameterTypes();
        Object[] a = new Object[pts.length];
        for (int i = 0; i < pts.length; i++) {
            Class<?> p = pts[i];
            if (Context.class.isAssignableFrom(p)) {
                a[i] = ctx;
            } else if (p == boolean.class) {
                a[i] = Boolean.FALSE;
            } else if (p == int.class) {
                a[i] = Integer.valueOf(0);
            } else if (p == long.class) {
                a[i] = Long.valueOf(0L);
            } else if (p.isArray()) {
                a[i] = syntheticArray(p.getComponentType());
            } else {
                a[i] = null;
            }
        }
        return a;
    }

    private static Object syntheticArray(Class<?> comp) {
        Object one = newFileInfoLike(comp);
        int len = one == null ? 0 : 1;
        Object arr = java.lang.reflect.Array.newInstance(comp, len);
        if (one != null) {
            java.lang.reflect.Array.set(arr, 0, one);
        }
        return arr;
    }

    /** 反射造一个「文件模型」样本：字段名 name / mimeType / path 在各版本一致 */
    private static Object newFileInfoLike(Class<?> comp) {
        if (comp == null || comp.isPrimitive()) {
            return null;
        }
        if (!comp.getName().endsWith("FileInfo") && !looksLikeFileInfo(comp)) {
            return null;
        }
        try {
            java.lang.reflect.Constructor<?> c = comp.getDeclaredConstructor();
            c.setAccessible(true);
            Object o = c.newInstance();
            setFieldValue(o, "name", "selftest.txt");
            setFieldValue(o, "mimeType", "text/plain");
            setFieldValue(o, "path", SELFTEST_FILE);
            setFieldValue(o, "directory", SELFTEST_FILE);
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setFieldValue(Object o, String name, String v) {
        Class<?> c = o.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, v);
                return;
            } catch (NoSuchFieldException nsfe) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return;
            }
        }
    }

    /**
     * 记录一次拦截耗时。
计数只走内存；status 重写节流到 5s 一次
     * （status 是唯一的运行期观测面 —— LSPosed 会把模块日志转走，
     *  设备上不一定能用 logcat 抓到）。
     */
    private static void noteIntercept(long ms) {
        sInterceptCount++;
        sInterceptTotalMs += ms;
        if (ms > sInterceptMaxMs) {
            sInterceptMaxMs = ms;
        }
        long now = SystemClock.uptimeMillis();
        if (sStatusBody.length() > 0 && now - sLastPerfWrite >= 5000) {
            sLastPerfWrite = now;
            writeStatus(sStatusBody);
        }
    }

    /** 拦截耗时摘要行；从未拦截过则不写这一行 */
    private static String perfLine() {
        long n = sInterceptCount;
        if (n == 0) {
            return "";
        }
        return "intercept=n=" + n + ",avg=" + (sInterceptTotalMs / n)
                + "ms,max=" + sInterceptMaxMs + "ms\n";
    }

    private static String paramSuffix(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(p.getSimpleName());
        }
        return sb.append(")").toString();
    }

    private void dbg(String msg) {
        if (DEBUG) {
            lg(Log.INFO, TAG, msg);
        }
    }
}
