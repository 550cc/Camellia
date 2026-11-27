package com.hyper.Camellia;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 设置页 ⇄ 目标进程 的「配置下发」通道。
 *
 * <p>为什么需要它：目标进程（小米画报）读模块设置只有两条老路——
 * {@code getRemotePreferences} 和「模块 app 私有目录里的侧车文件」。
 * 前者在部分场景（模块 app 被系统/Greezer 冻结、LSPosed 远程偏好不可用）会失败，
 * 后者因为 Android 11+ 的分区存储，**别的 App 根本读不到**
 * {@code /sdcard/Android/media/com.hyper.Camellia/}，于是画报永远退回默认目录——
 * 表现就是「改设置没用」。</p>
 *
 * <p>现在改成广播（显式指定包名，不需要任何权限）：</p>
 * <pre>
 *   设置页 --CFG_PUSH(dir/cap/enabled/strict)--> 目标进程（落盘成目标自己的私有文件）
 *   目标进程 --CFG_REQ(带自己包名)--> 模块 app 的 CfgReceiver --CFG_PUSH--> 目标进程
 * </pre>
 * 目标进程把收到的配置写进**自己的** data 目录，之后每次同步都从那里读，
 * 既不用跨进程、也不受存储权限影响。
 */
public final class CfgBridge {

    private static final String TAG = "MiShareMT2/Cfg";

    /** 模块 app -> 目标进程：这是当前配置 */
    public static final String ACTION_PUSH = "com.hyper.Camellia.action.CFG_PUSH";
    /** 目标进程 -> 模块 app：请把当前配置推给我 */
    public static final String ACTION_REQ = "com.hyper.Camellia.action.CFG_REQ";

    /** 本模块自己的包名（画报进程里的 ctx.getPackageName() 是画报，不能用） */
    public static final String APP_PKG = "com.hyper.Camellia";

    /** CFG_REQ 里携带请求方包名（模块 app 据此决定推给谁） */
    public static final String EXT_TARGET = "target";
    public static final String EXT_DIR = "fg_dir";
    public static final String EXT_CAP = "fg_cap";
    public static final String EXT_ENABLED = "fg_enabled";
    public static final String EXT_STRICT = "fg_strict";
    public static final String EXT_ORDER = "fg_order";
    public static final String EXT_SEED = "fg_seed";
    public static final String EXT_BLOCK_AD = "fg_block_ad";
    public static final String EXT_LOCAL_PHOTO = "fg_local_photo";
    public static final String EXT_CURATED = "fg_curated";
    public static final String EXT_DEBUG = "fg_debug";
    public static final String EXT_TS = "ts";

    private CfgBridge() {
    }

    /** 模块 app 侧：把当前设置推给目标进程（目标进程不在运行时会丢，它下次启动会自己来拉） */
    public static void push(Context ctx, String target) {
        if (ctx == null || target == null || target.length() == 0) {
            return;
        }
        try {
            // 模块 app 进程内：先确保 prefs 监听挂着、并把「配置变了」通知出去。
            // 广播只覆盖画报进程（它自己接 CFG_PUSH），手机管家/SystemUI 里的
            // Unlock / LauncherShortcut 没有广播接收器，只靠这条 notifyChange
            // 让本地快照（含 1.5~3 秒 TTL）当轮失效。
            if (APP_PKG.equals(ctx.getPackageName())) {
                CfgProvider.ensureWatching(ctx);
                CfgProvider.bump(ctx);
            }
            Intent i = new Intent(ACTION_PUSH);
            i.setPackage(target);
            i.putExtra(EXT_DIR, Prefs.getFgDir(ctx));
            i.putExtra(EXT_CAP, Prefs.getFgCap(ctx));
            i.putExtra(EXT_ENABLED, Prefs.isFgEnabled(ctx));
            i.putExtra(EXT_STRICT, Prefs.isFgStrict(ctx));
            i.putExtra(EXT_ORDER, Prefs.getFgOrder(ctx));
            i.putExtra(EXT_SEED, Prefs.getFgSeed(ctx));
            i.putExtra(EXT_BLOCK_AD, Prefs.isFgBlockAd(ctx));
            i.putExtra(EXT_LOCAL_PHOTO, Prefs.isFgLocalPhoto(ctx));
            i.putExtra(EXT_CURATED, Prefs.getFgCurated(ctx));
            i.putExtra(EXT_DEBUG, Prefs.isFgDebug(ctx));
            i.putExtra(EXT_TS, System.currentTimeMillis());
            ctx.sendBroadcast(i);
            Prefs.logApp(ctx, Log.INFO, TAG, "push -> " + target
                    + " dir=" + Prefs.getFgDir(ctx) + " cap=" + Prefs.getFgCap(ctx)
                    + " enabled=" + Prefs.isFgEnabled(ctx)
                    + " order=" + Prefs.getFgOrder(ctx));
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "push failed: " + t);
        }
    }

    /** 模块 app 侧：收到 CFG_REQ 后把配置推给请求方 */
    public static void replyTo(Context ctx, String requester) {
        String target = requester == null || requester.length() == 0 ? Fg.PKG : requester;
        push(ctx, target);
    }

    /** 目标进程侧：向模块 app 请求一次配置 */
    public static void request(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent i = new Intent(ACTION_REQ);
            i.setPackage(APP_PKG);
            // 模块 app 可能处于停止态，带上这个 flag 才能把它拉起来应答
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            i.putExtra(EXT_TARGET, ctx.getPackageName());
            ctx.sendBroadcast(i);
            Prefs.logApp(ctx, Log.INFO, TAG, "cfg req -> " + APP_PKG);
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "cfg req failed: " + t);
        }
    }
}
