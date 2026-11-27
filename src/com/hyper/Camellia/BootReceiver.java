package com.hyper.Camellia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启：与 root 管理器无关的通用通道（Scene 同款路线）。
 *
 * <p>为什么需要它：{@code /data/adb/service.d/} 只有 Magisk / KernelSU 系提供，
 * APatch、部分第三方内核、被清理过的环境都可能没有。BOOT_COMPLETED 是系统级广播，
 * 只要 App 还在就能收到 —— 收到后 su 执行一次部署脚本（脚本内部幂等，
 * 已在跑就不会重启 camd）。</p>
 *
 * <p>另监听 {@code MY_PACKAGE_REPLACED}：覆盖安装后自动重部署，
 * 解决"升级完必须手动开一次 App"的问题。</p>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "CamelliaBoot";

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        final String action = intent.getAction();
        // goAsync 让进程在 onReceive 返回后还能活到部署完成（su 调用是阻塞的）
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 定时快照补跑。与 camd 是否停用无关，单独兜异常：
                    // 快照失败不能拖累 camd 部署。
                    // LOCKED_BOOT 阶段 /data 还没解密，/data/adb/camellia 读不到，
                    // 这时候补跑只会白失败一次，直接跳过等下次解锁后的广播。
                    if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
                        try {
                            SnapScheduler.onBoot(ctx);
                        } catch (Throwable t) {
                            Log.println(Log.WARN, TAG, "snap catchup failed: " + t);
                        }
                    }
                    // 用户已「一键卸载」：停用标记在，开机/覆盖安装都不许自动装回
                    if (Prefs.isCamdDisabled(ctx)) {
                        Log.println(Log.INFO, TAG, action + " -> skipped (disabled)");
                        return;
                    }
                    String r = DaemonInstaller.ensure(ctx);
                    Log.println(Log.INFO, TAG, action + " -> " + r);
                } catch (Throwable t) {
                    Log.println(Log.WARN, TAG, action + " failed: " + t);
                } finally {
                    try {
                        pr.finish();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "camellia-boot").start();
    }
}
