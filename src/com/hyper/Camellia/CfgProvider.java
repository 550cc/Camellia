package com.hyper.Camellia;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨进程配置 provider（只读）。
 *
 * <p>为什么需要它：目标进程（小米画报 / 小米互传 / system_server，以及手机管家
 * {@code :ui} 进程）与模块 app 是不同 uid，读不到模块的 SharedPreferences；
 * 旧方案靠 /sdcard 侧车文件，但 Android 13+ 分区存储下那些路径对目标进程一律
 * 拒绝（MediaProvider 明确拒绝非媒体文件）。改成 Settings.System 公共键后，
 * 本机 HyperOS 实测又拒绝普通应用写自定义键（{@code You cannot keep your settings
 * in the secure settings.}，只有 root/shell 能写）。</p>
 *
 * <p>provider 是纯 Binder 调用：不需要任何权限、不受分区存储约束、不受设置键白名单
 * 约束，任何进程用自家 Context 的 ContentResolver 都能查到。</p>
 *
 * <h3>版本戳（借鉴 HyperCeiler 的 PrefsBridge）</h3>
 * <p>除了 {@code cfg} 全文，还返回一列 {@code ver}，格式 {@code 代际:计数}：</p>
 * <ul>
 *   <li><b>代际</b>：provider 进程启动时随机生成。模块 app 重装 / 进程重启后必然变化，
 *       因此目标进程绝不会把「新进程的 0 号配置」误判成「比旧缓存更旧」——
 *       这类单调计数倒退正是「更新 App 后还是旧配置」的机制性根因。</li>
 *   <li><b>计数</b>：每次 prefs 键变更 +1，由 {@link SharedPreferences.OnSharedPreferenceChangeListener}
 *       驱动。</li>
 * </ul>
 * <p>读取方先只取 {@code ver}（几字节）判断缓存是否还有效，变了才拉全文；
 * 全文本身也按 ver 缓存在 provider 侧，键没变就不会重复拼装几十个键。</p>
 */
public class CfgProvider extends ContentProvider {

    private static final String TAG = "MiShareMT2/CfgProvider";

    /** 查询路径（随便什么路径都返回同一份配置，保留路径只为可读性） */
    public static final String PATH_CFG = "cfg";

    public static final String COL_CFG = "cfg";
    /** 版本戳：代际:计数 —— 只取这一列即为「轻量探测」 */
    public static final String COL_VER = "ver";
    public static final String COL_TS = "ts";

    /** provider 进程代际（进程重启 / App 重装必然不同） */
    private static final String GEN = Long.toHexString(
            System.currentTimeMillis() ^ (System.nanoTime() << 1));

    /** 配置变更计数：任何 prefs 键写入都会自增 */
    private static final AtomicLong EPOCH = new AtomicLong();

    /** 全文缓存：ver 未变时直接复用，不再遍历几十个 prefs 键 */
    private static String sBody;
    private static String sBodyVer;

    /** prefs 变更监听只注册一次 */
    private static boolean sWatching;

    /** 监听器必须静态持有：SharedPreferences 只保留弱引用 */
    private static final SharedPreferences.OnSharedPreferenceChangeListener WATCHER =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    EPOCH.incrementAndGet();
                    synchronized (CfgProvider.class) {
                        sBody = null;
                        sBodyVer = null;
                    }
                    notifyAllWatchers();
                }
            };

    /** 当前版本戳 */
    static String version() {
        return GEN + ":" + EPOCH.get();
    }

    /**
     * 主动标记配置已变更（设置页在 prefs 之外改了别的东西时补一刀）。
     *
     * <p>绝大多数情况下不需要显式调用：prefs 的变更监听已经覆盖。</p>
     */
    static void bump(Context ctx) {
        EPOCH.incrementAndGet();
        synchronized (CfgProvider.class) {
            sBody = null;
            sBodyVer = null;
        }
        notifyAllWatchers();
    }

    /** 通知所有 ContentObserver（目标进程据此立刻丢掉本地缓存） */
    private static void notifyAllWatchers() {
        try {
            Context c = sAppCtx;
            if (c == null) {
                return;
            }
            c.getContentResolver().notifyChange(
                    Uri.parse("content://" + Prefs.CFG_AUTHORITY + "/" + PATH_CFG), null);
        } catch (Throwable t) {
            Log.println(Log.INFO, TAG, "notifyChange failed: " + t);
        }
    }

    /** onCreate 拿到的 application context，供静态回调使用 */
    private static volatile Context sAppCtx;

    @Override
    public boolean onCreate() {
        sAppCtx = getContext();
        ensureWatch(sAppCtx);
        return true;
    }

    /**
     * 模块 app 侧主动挂上监听。
     *
     * <p>provider 只有在被 {@code ContentResolver.query} 访问到时才 onCreate，而
     * 「设置页写 prefs」这条路径并不经过 query。不主动挂一次的话，
     * {@link #WATCHER} 永远不注册，目标进程的 observer 也就永远收不到
     * {@code notifyChange} —— 这是「改了设置，已有进程还是旧配置」的另一条隐患。
     * 所以设置页一进来就调这里。</p>
     */
    public static void ensureWatching(Context ctx) {
        if (ctx == null) {
            return;
        }
        if (sAppCtx == null) {
            sAppCtx = ctx.getApplicationContext();
        }
        ensureWatch(sAppCtx);
    }

    /** 挂上 prefs 变更监听（幂等）；provider 进程 = 模块 app 进程，写设置就在这里 */
    private static void ensureWatch(Context ctx) {
        if (ctx == null) {
            return;
        }
        synchronized (CfgProvider.class) {
            if (sWatching) {
                return;
            }
            sWatching = true;
        }
        try {
            Prefs.get(ctx).registerOnSharedPreferenceChangeListener(WATCHER);
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "register prefs watcher failed: " + t);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        if (ctx == null) {
            ctx = sAppCtx;
        }
        ensureWatch(ctx);

        String ver = version();
        // 轻量探测：只请求 ver 列（目标进程判断缓存是否还有效，不传正文）
        boolean verOnly = projection != null && projection.length == 1
                && COL_VER.equals(projection[0]);

        MatrixCursor cursor = verOnly
                ? new MatrixCursor(new String[]{COL_VER})
                : new MatrixCursor(new String[]{COL_CFG, COL_VER, COL_TS});
        try {
            if (ctx == null) {
                return cursor;
            }
            if (verOnly) {
                cursor.addRow(new Object[]{ver});
                return cursor;
            }
            String body;
            synchronized (CfgProvider.class) {
                if (sBody != null && ver.equals(sBodyVer)) {
                    body = sBody;
                } else {
                    body = Prefs.buildCfgBody(ctx, Prefs.getMtTarget(ctx), Prefs.getFgDir(ctx));
                    sBody = body;
                    sBodyVer = version();
                }
            }
            cursor.addRow(new Object[]{body, ver, Long.valueOf(System.currentTimeMillis())});
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "query failed: " + t);
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.camellia.cfg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
