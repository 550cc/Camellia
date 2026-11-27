package com.hyper.Camellia;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 桌面快捷方式「小窗」的透明中转页。
 *
 * <p>正常情况下用不到它：桌面点击合成项后会走
 * {@code LauncherAppsService$LauncherAppsImpl#startShortcutInner}，由
 * {@link LauncherShortcut} 在 system_server 里直接以小窗启动目标应用。
 * 但若桌面改为「自己解析 Intent 后自行 startActivity」，落地的就是本页 ——
 * 这里再补一次同样的小窗启动，保证点击一定有效。</p>
 *
 * <p>本页无 UI（透明主题）、立即结束，不进入最近任务。</p>
 *
 * <p>日志：本进程是模块 app（写不了 /data/system），日志落到私有目录
 * {@code files/trampoline.log}，用 root 可读，便于排查「点击没反应」。</p>
 */
public final class ShortcutFreeformActivity extends Activity {

    private static final String TAG = "Camellia-Launcher";

    private static String sLogPath;

    static void logA(String s) {
        Log.i(TAG, "[trampoline] " + s);
        write("I " + s);
    }

    static void logEA(String s, Throwable t) {
        Log.e(TAG, "[trampoline] " + s, t);
        write("E " + s + (t == null ? "" : "\n" + Log.getStackTraceString(t)));
    }

    private static void write(String s) {
        String p = sLogPath;
        if (p == null) return;
        try {
            File f = new File(p);
            if (f.length() > 512 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                    java.util.Locale.US).format(new java.util.Date());
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write((ts + " " + s + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // 日志失败不影响主流程
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            sLogPath = new File(getFilesDir(), "trampoline.log").getAbsolutePath();
        } catch (Throwable ignored) {
        }
        try {
            Intent in = getIntent();
            String pkg = in == null ? null : in.getStringExtra(LauncherShortcut.EXTRA_PKG);
            ComponentName cn = null;
            if (in != null) {
                try {
                    // 新格式：字符串 "pkg/cls"（ShortcutInfo 校验 extras 可持久化类型，
                    // ComponentName 不被支持，故构造 shortcut 时改传字符串）
                    String flat = in.getStringExtra(LauncherShortcut.EXTRA_COMPONENT);
                    if (flat != null && flat.length() > 0) {
                        cn = ComponentName.unflattenFromString(flat);
                    }
                } catch (Throwable ignored) {
                }
                if (cn == null) {
                    try {
                        cn = (ComponentName) in.getParcelableExtra(LauncherShortcut.EXTRA_COMPONENT);
                    } catch (Throwable ignored) {
                    }
                }
            }
            logA("onCreate pkg=" + pkg + " component=" + cn);
            if (pkg != null) {
                Intent launch;
                if (cn != null) {
                    launch = new Intent(Intent.ACTION_MAIN).setComponent(cn);
                } else {
                    launch = getPackageManager().getLaunchIntentForPackage(pkg);
                }
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    Bundle opts = null;
                    try {
                        opts = SecurityDiffFeatures.smallWindowOptions(
                                getClassLoader(), this, pkg);
                    } catch (Throwable t) {
                        logEA("smallWindowOptions failed", t);
                    }
                    if (opts != null) {
                        logA("freeform start " + pkg + " opts=" + opts.keySet());
                        startActivity(launch, opts);
                    } else {
                        logA("plain start " + pkg);
                        startActivity(launch);
                    }
                } else {
                    logA("no launch intent for " + pkg);
                }
            }
        } catch (Throwable t) {
            logEA("failed", t);
        }
        // 稍等片刻再结束，避免目标 Activity 还没起来本页就消失导致的切换动画异常
        overridePendingTransition(0, 0);
        new Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 200L);
    }
}
