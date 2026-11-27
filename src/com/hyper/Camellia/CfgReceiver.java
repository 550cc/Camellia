package com.hyper.Camellia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 模块 app 侧：响应目标进程的「配置拉取请求」（{@link CfgBridge#ACTION_REQ}）。
 *
 * <p>目标进程（小米画报）在进程里第一次同步时会发一次 REQ；
 * 因为它可能在设置页改动时**不在运行**（收不到 PUSH），所以启动时必须自己来拉一次，
 * 这样即使设置页改了目录、画报进程当时是死的，下次打开画报也能拿到最新配置。</p>
 */
public class CfgReceiver extends BroadcastReceiver {

    private static final String TAG = "MiShareMT2/CfgRx";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!CfgBridge.ACTION_REQ.equals(action)) {
            return;
        }
        String requester = intent.getStringExtra(CfgBridge.EXT_TARGET);
        Prefs.logApp(context, Log.INFO, TAG, "cfg req from " + requester);
        try {
            CfgBridge.replyTo(context, requester);
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "reply failed: " + t);
        }
    }
}
