package com.hyper.Camellia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 运行期状态回传。
 *
 * 目标进程（小米互传 / 小米画报）里跑的模块代码**没有权限**写本模块的
 * Android/media 目录——Android 11+ 只允许目录属主写自己的
 * /sdcard/Android/media/&lt;pkg&gt;/。于是之前设置页根本读不到目标进程写的状态文件。
 *
 * 现在改成：目标进程把状态文本用广播发给本模块（显式指定包名，无需任何权限），
 * 本模块存进自己的 SharedPreferences，设置页直接从 SharedPreferences 读。
 * 文件写法保留，方便 root/调试时直接看。
 */
public class StatusReceiver extends BroadcastReceiver {

    private static final String TAG = "MiShareMT2/Status";

    public static final String ACTION = "com.hyper.Camellia.action.STATUS";
    /** 设置页 -> 目标进程：请立刻重发一次状态（push 通道被 Greezer 冻结吃掉时的兜底） */
    public static final String ACTION_REQ = "com.hyper.Camellia.action.STATUS_REQ";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_TEXT = "text";

    /** 「小米互传」侧状态 */
    public static final String SRC_MISHARE = "mishare";
    /** 「小米画报」侧状态 */
    public static final String SRC_FG = "fg";

    private static final String PREFS = "status_cache";
    private static final int MAX_LEN = 16384;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        String src = intent.getStringExtra(EXTRA_SOURCE);
        if (src == null || src.length() == 0) {
            src = SRC_MISHARE;
        }
        String text = intent.getStringExtra(EXTRA_TEXT);
        if (text == null) {
            return;
        }
        if (text.length() > MAX_LEN) {
            text = text.substring(0, MAX_LEN);
        }
        long now = System.currentTimeMillis();
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit()
                    .putString("text_" + src, text)
                    .putLong("time_" + src, now)
                    .apply();
            Prefs.logApp(context, Log.INFO, TAG, "recv " + src + " (" + text.length() + "B)");
        } catch (Throwable t) {
            Log.println(Log.ERROR, TAG, "recv failed: " + t);
        }
        // 附带写一份到本模块自己的外部目录，方便 root/文件管理器查看
        try {
            File f = new File("/sdcard/Android/media/com.hyper.Camellia/status_" + src + ".txt");
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(text.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /** 设置页调用：请求某个目标进程重发状态（显式指定包名，目标进程没在跑就静默无效） */
    public static void request(Context ctx, String targetPkg) {
        if (ctx == null || targetPkg == null) {
            return;
        }
        try {
            Intent i = new Intent(ACTION_REQ);
            i.setPackage(targetPkg);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 设置页侧：监听目标进程回传，收到就回调（用于立即刷新界面）。
     *
     * 发送方是别的 app，所以必须 RECEIVER_EXPORTED；返回的 receiver 需要在
     * Activity 销毁时 unregister。
     */
    public static BroadcastReceiver registerUiListener(Context ctx, final Runnable cb) {
        if (ctx == null) {
            return null;
        }
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (cb != null) {
                        cb.run();
                    }
                }
            };
            IntentFilter f = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 设置页读取：返回 null 表示目标进程还没回传过 */
    public static String read(Context ctx, String src) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getString("text_" + src, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 状态回传时间（毫秒），0=从未 */
    public static long readTime(Context ctx, String src) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getLong("time_" + src, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 目标进程侧调用：把状态文本广播回本模块 */
    public static void send(Context ctx, String src, String text) {
        if (ctx == null || text == null) {
            return;
        }
        try {
            Intent i = new Intent(ACTION);
            i.setPackage("com.hyper.Camellia");
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            i.putExtra(EXTRA_SOURCE, src);
            i.putExtra(EXTRA_TEXT, text);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            Log.println(Log.ERROR, TAG, "send failed: " + t);
        }
    }
}
