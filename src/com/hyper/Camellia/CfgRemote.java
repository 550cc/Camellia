package com.hyper.Camellia;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.HotReloadResult;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 设置页 → 框架 remote preferences 的镜像器（跑在**模块 App 进程**里）。
 *
 * <h3>为什么必须有它</h3>
 * <p>目标进程里的 hook（system_server {@link LauncherShortcut}、小米互传 {@link Fg} 等）
 * 读设置只有一条路：{@code XposedModule#getRemotePreferences("settings")}。
 * 而 <b>remote preferences 由框架（LSPosed）持有</b>，模块 App 想写它，
 * 必须先通过 {@code <applicationId>.XposedService} 这个 provider 拿到框架下发的
 * {@link XposedService} binder（见 manifest 里的 {@code XposedProvider} 声明与
 * libxposed/service 的约定）。</p>
 *
 * <p>历史版本只写模块自己的私有 prefs + 侧车文件，从未写进框架侧，
 * 于是 system_server 里读到的永远是默认值 false —— 表现就是
 * 「设置页开关已打开、长按图标菜单里却没有『小窗』」。</p>
 *
 * <h3>它是怎么工作的</h3>
 * <ol>
 *   <li>App 启动时 {@link #init(Context)} 注册 service 监听；</li>
 *   <li>框架绑定成功后 {@link #syncAll()} 把本地 prefs 全量镜像过去；</li>
 *   <li>之后本地 prefs 任意改动都会经 {@link #syncKey(String)} 增量镜像。</li>
 * </ol>
 *
 * <p>注意：remote preferences 只支持基本类型 + StringSet，且在框架侧是
 * “存数据库 + 推送变更”，所以这里不做高频写。</p>
 */
public final class CfgRemote {

    private static final String TAG = "Camellia/CfgRemote";

    private static final Object LOCK = new Object();

    private static volatile XposedService sSvc;
    private static volatile Context sApp;
    private static volatile boolean sInited;
    /** 必须强引用：SharedPreferences 内部只弱引用监听器 */
    private static volatile SharedPreferences.OnSharedPreferenceChangeListener sLocalListener;

    private CfgRemote() {
    }

    // ─────────────────────────── 初始化 ───────────────────────────

    /** 在模块 App 的 {@code Application#onCreate()} 里调用一次。 */
    public static void init(Context ctx) {
        if (ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        sApp = app == null ? ctx : app;
        synchronized (LOCK) {
            if (sInited) {
                return;
            }
            sInited = true;
        }
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    bind(service);
                }

                @Override
                public void onServiceDied(XposedService service) {
                    if (sSvc == service) {
                        sSvc = null;
                        Log.println(Log.WARN, TAG, "framework service died");
                    }
                }
            });
            Log.i(TAG, "XposedService listener registered");
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "registerListener failed: " + t);
        }
        installLocalListener();
    }

    /** 框架服务是否已就绪（设置页可据此提示「本机框架不支持，已走文件通道」）。 */
    public static boolean available() {
        return sSvc != null;
    }

    /**
     * 框架名称，如 {@code LSPosed} / {@code Vector}；未绑定或调用失败返回 null。
     *
     * <p>这是「LSPosed 环境是否可用」最权威的信号：libxposed service 的 binder 只会在
     * 框架识别出本模块（已安装且已启用）之后，经 {@code <pkg>.XposedService} provider 下发。
     * 也就是说 binder 到手 = 框架在跑 + 模块被框架认可，不需要再去猜包名或读 /data/adb。
     */
    public static String frameworkName() {
        XposedService svc = sSvc;
        if (svc == null) {
            return null;
        }
        try {
            return svc.getFrameworkName();
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "frameworkName failed: " + t);
            return null;
        }
    }

    /** 框架版本字符串（如 {@code 2.0.3 (7361)}）；未绑定返回 null。 */
    public static String frameworkVersion() {
        XposedService svc = sSvc;
        if (svc == null) {
            return null;
        }
        try {
            return svc.getFrameworkVersion();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 框架已授予本模块的作用域（用户在 LSPosed 里勾选的目标包名）。
     *
     * @return 未绑定返回 null；框架不支持/调用失败返回空表。非空即代表「作用域已打开」
     */
    public static List<String> scope() {
        XposedService svc = sSvc;
        if (svc == null) {
            return null;
        }
        try {
            List<String> s = svc.getScope();
            return s == null ? new ArrayList<String>() : s;
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "scope failed: " + t);
            return new ArrayList<String>();
        }
    }

    /**
     * 当前已被本模块 hook 且仍存活的进程名（libxposed API 102 的 {@code getRunningTargets}）。
     *
     * <p>用来区分「框架在、但模块还没注入任何目标进程」：勾了作用域但目标应用没启动过时为空。
     * 老框架没有该接口，返回空表而不是 null。
     */
    public static List<String> runningTargets() {
        XposedService svc = sSvc;
        if (svc == null) {
            return null;
        }
        List<String> out = new ArrayList<String>();
        try {
            List<HookedTarget> targets = svc.getRunningTargets();
            if (targets != null) {
                for (HookedTarget t : targets) {
                    if (t != null) {
                        out.add(t.getProcessName());
                    }
                }
            }
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "runningTargets failed: " + t);
        }
        return out;
    }

    // ─────────────────── 热重载（libxposed service API 102） ───────────────────
    //
    // 场景：模块 APK 安装/更新后，已经跑着旧代码的目标进程（system_server、互传、系统安全组件…）
    // 不会自己换代码。框架给两条路：
    //   1) module.prop 里 autoHotReload=true —— 装完 APK 由框架自动重载，用户无需操作；
    //   2) 本段这套手动入口 —— 设置页点一下，按需重载单个/全部目标，并显示谁还停在旧版。
    // HookedTarget.State：UP_TO_DATE=跑的是当前版本；STALE=旧版代码待重载；RELOADING；FAILED。

    /** 框架热重载结果回调（主线程派发）。 */
    public interface HotReloadListener {
        /**
         * @param status  SUCCEEDED / FAILED / UNSUPPORTED / IN_PROGRESS / PROCESS_DIED
         *                （另有两种本地判定：SERVICE_UNAVAILABLE=框架未绑定，INVALID_TARGET=目标为空）
         * @param message 框架给的说明，可能为 null
         */
        void onResult(HookedTarget target, String status, String message);
    }

    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    private static void postResult(final HotReloadListener l, final HookedTarget t,
                                   final String status, final String msg) {
        if (l == null) {
            return;
        }
        try {
            mainHandler().post(new Runnable() {
                @Override
                public void run() {
                    l.onResult(t, status, msg);
                }
            });
        } catch (Throwable e) {
            Log.println(Log.WARN, TAG, "postResult failed: " + e);
        }
    }

    /** 框架 service API 版本；框架未绑定返回 -1。 */
    public static int apiVersion() {
        XposedService svc = sSvc;
        if (svc == null) {
            return -1;
        }
        try {
            return svc.getApiVersion();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 框架是否支持热重载（API 102 起提供 {@code hotReloadModule}）。 */
    public static boolean hotReloadSupported() {
        return apiVersion() >= XposedService.API_102;
    }

    /** 已注入且存活的目标（含 pid / uid / state）；框架未绑定返回 null。 */
    public static List<HookedTarget> hookedTargets() {
        XposedService svc = sSvc;
        if (svc == null) {
            return null;
        }
        try {
            List<HookedTarget> t = svc.getRunningTargets();
            return t == null ? new ArrayList<HookedTarget>() : t;
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "hookedTargets failed: " + t);
            return new ArrayList<HookedTarget>();
        }
    }

    /** 单行描述，供设置页直接展示，如 {@code com.miui.guardprovider:push [pid 1234] STALE}。 */
    public static String describe(HookedTarget t) {
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(t.getProcessName() == null ? "?" : t.getProcessName());
        try {
            sb.append(" [pid ").append(t.getPid()).append(", uid ").append(t.getUid()).append(']');
            sb.append(" v").append(t.getLoadedVersionCode());
        } catch (Throwable ignored) {
        }
        try {
            if (t.getState() != null) {
                sb.append(' ').append(t.getState().name());
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 按进程名精确匹配一个运行中目标；没有则 null。 */
    public static HookedTarget findTarget(String processName) {
        if (processName == null) {
            return null;
        }
        List<HookedTarget> all = hookedTargets();
        if (all == null) {
            return null;
        }
        for (HookedTarget t : all) {
            if (t != null && processName.equals(t.getProcessName())) {
                return t;
            }
        }
        return null;
    }

    /** 仍在跑旧版模块代码（state=STALE）的目标数，也就是「装完 APK 还要重载几个」。 */
    public static int staleCount() {
        List<HookedTarget> all = hookedTargets();
        if (all == null || all.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (HookedTarget t : all) {
            try {
                if (t != null && t.getState() == HookedTarget.State.STALE) {
                    n++;
                }
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /**
     * 对单个目标发起热重载。
     *
     * @return true=请求已交给框架（结果走 listener）；false=不支持/未绑定/异常，已直接回调原因
     */
    public static boolean hotReload(HookedTarget target, HotReloadListener l) {
        XposedService svc = sSvc;
        if (svc == null) {
            postResult(l, target, "SERVICE_UNAVAILABLE", "框架未绑定（模块管理器未授权或框架未运行）");
            return false;
        }
        if (target == null) {
            postResult(l, null, "INVALID_TARGET", "目标为空");
            return false;
        }
        if (apiVersion() < XposedService.API_102) {
            postResult(l, target, "UNSUPPORTED", "框架 service API < 102，不支持热重载");
            return false;
        }
        final HookedTarget asked = target;
        try {
            svc.hotReloadModule(target, null, new XposedService.HotReloadCallback() {
                @Override
                public void onHotReloadResult(HookedTarget t, HotReloadResult r) {
                    String st = "UNKNOWN";
                    String msg = null;
                    try {
                        if (r != null && r.status() != null) {
                            st = r.status().name();
                        }
                        msg = r == null ? null : r.message();
                    } catch (Throwable ignored) {
                    }
                    postResult(l, t == null ? asked : t, st, msg);
                }
            });
            return true;
        } catch (UnsupportedOperationException e) {
            postResult(l, asked, "UNSUPPORTED", String.valueOf(e.getMessage()));
        } catch (SecurityException e) {
            postResult(l, asked, "FAILED", "框架拒绝该操作: " + e.getMessage());
        } catch (Throwable t) {
            postResult(l, asked, "FAILED", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return false;
    }

    /** 按进程名热重载（精确匹配）；未找到时回调 NO_TARGET。 */
    public static boolean hotReloadProcess(String processName, HotReloadListener l) {
        HookedTarget t = findTarget(processName);
        if (t == null) {
            postResult(l, null, "NO_TARGET", processName + " 未在运行（或未注入本模块）");
            return false;
        }
        return hotReload(t, l);
    }

    /** 全部运行中目标逐个热重载，返回发起的请求数。 */
    public static int hotReloadAll(HotReloadListener l) {
        List<HookedTarget> all = hookedTargets();
        if (all == null || all.isEmpty()) {
            postResult(l, null, "NO_TARGET", "当前没有任何已注入的运行中进程");
            return 0;
        }
        int n = 0;
        for (HookedTarget t : all) {
            if (t == null) {
                continue;
            }
            hotReload(t, l);
            n++;
        }
        return n;
    }

    /** 只重载仍停在旧代码（STALE）的目标，返回发起的请求数。 */
    public static int hotReloadStale(HotReloadListener l) {
        List<HookedTarget> all = hookedTargets();
        if (all == null || all.isEmpty()) {
            postResult(l, null, "NO_TARGET", "当前没有任何已注入的运行中进程");
            return 0;
        }
        int n = 0;
        for (HookedTarget t : all) {
            if (t == null) {
                continue;
            }
            boolean stale = false;
            try {
                stale = t.getState() == HookedTarget.State.STALE;
            } catch (Throwable ignored) {
            }
            if (!stale) {
                continue;
            }
            hotReload(t, l);
            n++;
        }
        if (n == 0) {
            postResult(l, null, "UP_TO_DATE", "所有已注入进程都在跑当前版本，无需重载");
        }
        return n;
    }

    private static void installLocalListener() {
        Context app = sApp;
        if (app == null) {
            return;
        }
        try {
            SharedPreferences.OnSharedPreferenceChangeListener l =
                    new SharedPreferences.OnSharedPreferenceChangeListener() {
                        @Override
                        public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                            syncKey(key);
                        }
                    };
            sLocalListener = l;
            Prefs.get(app).registerOnSharedPreferenceChangeListener(l);
            Log.i(TAG, "local prefs change listener installed");
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "installLocalListener failed: " + t);
        }
    }

    // ─────────────────────────── 绑定 ───────────────────────────

    private static void bind(XposedService service) {
        if (service == null) {
            return;
        }
        sSvc = service;
        long props = 0L;
        try {
            props = service.getFrameworkProperties();
            Log.i(TAG, "service bound: name=" + service.getFrameworkName()
                    + " ver=" + service.getFrameworkVersion()
                    + " api=" + service.getApiVersion()
                    + " props=0x" + Long.toHexString(props));
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "read framework properties failed: " + t);
        }
        if ((props & XposedService.PROP_CAP_REMOTE) == 0L) {
            Log.println(Log.WARN, TAG,
                    "framework reports no REMOTE capability — remote prefs may be unavailable");
        }
        syncAll();
    }

    // ─────────────────────────── 同步 ───────────────────────────

    /** 把本地 prefs 全量镜像到框架 remote preferences（group = {@link Prefs#GROUP}）。 */
    public static void syncAll() {
        XposedService svc = sSvc;
        Context app = sApp;
        if (svc == null || app == null) {
            return;
        }
        try {
            Map<String, ?> all = Prefs.get(app).getAll();
            SharedPreferences remote = svc.getRemotePreferences(Prefs.GROUP);
            if (remote == null) {
                Log.println(Log.WARN, TAG, "syncAll: remote prefs is null");
                return;
            }
            SharedPreferences.Editor ed = remote.edit();
            int n = 0;
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (put(ed, e.getKey(), e.getValue())) {
                    n++;
                }
            }
            ed.apply();
            Log.i(TAG, "syncAll: " + n + "/" + all.size()
                    + " keys -> remote prefs[" + Prefs.GROUP + "]");
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "syncAll failed: " + t);
        }
    }

    /** 单个键增量镜像（本地 prefs 变更回调里用）。 */
    public static void syncKey(String key) {
        XposedService svc = sSvc;
        Context app = sApp;
        if (svc == null || app == null || key == null || key.length() == 0) {
            return;
        }
        try {
            Object v = Prefs.get(app).getAll().get(key);
            SharedPreferences remote = svc.getRemotePreferences(Prefs.GROUP);
            if (remote == null) {
                return;
            }
            SharedPreferences.Editor ed = remote.edit();
            if (v == null) {
                ed.remove(key);
            } else if (!put(ed, key, v)) {
                return;
            }
            ed.apply();
            Log.i(TAG, "sync " + key + "=" + v + " -> remote prefs");
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "syncKey(" + key + ") failed: " + t);
        }
    }

    /** remote preferences 只认基本类型 + StringSet，其它类型直接跳过。 */
    private static boolean put(SharedPreferences.Editor ed, String k, Object v) {
        if (k == null || v == null) {
            return false;
        }
        if (v instanceof Boolean) {
            ed.putBoolean(k, (Boolean) v);
        } else if (v instanceof String) {
            ed.putString(k, (String) v);
        } else if (v instanceof Integer) {
            ed.putInt(k, (Integer) v);
        } else if (v instanceof Long) {
            ed.putLong(k, (Long) v);
        } else if (v instanceof Float) {
            ed.putFloat(k, (Float) v);
        } else if (v instanceof Set) {
            HashSet<String> set = new HashSet<String>();
            for (Object o : (Set<?>) v) {
                if (o instanceof String) {
                    set.add((String) o);
                }
            }
            ed.putStringSet(k, set);
        } else {
            return false;
        }
        return true;
    }
}
