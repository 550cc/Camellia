package com.hyper.Camellia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 目标进程（互传 / 画报）侧的「状态按需重发」监听器。
 *
 * push 通道（StatusReceiver）在小米 Greezer 把本模块 app 冻结时会丢广播，
 * 所以再提供一条 pull 通道：设置页发 STATUS_REQ，目标进程收到后立刻重发一次状态。
 * 目标进程必须已经在运行（否则它本来就没有状态可报），这一点符合预期。
 */
public final class StatusReqListener extends BroadcastReceiver {

    private static volatile StatusReqListener sInst;
    private final Runnable mCb;

    private StatusReqListener(Runnable cb) {
        mCb = cb;
    }

    /** 幂等注册（持静态引用防被 GC 回收） */
    public static void ensure(Context ctx, Runnable cb) {
        register(ctx, null, cb);
    }

    /**
     * 幂等注册，并校验 Context 的包名。
     *
     * install() 早期阶段拿到的可能只是 ActivityThread 的系统 Context（包名
     * android），在它上面注册会挂错上下文，所以必须用包名把这种 Context 挡掉，
     * 等真实 app Context（Application#onCreate）出现时再注册。
     */
    public static void register(Context ctx, String expectPkg, Runnable cb) {
        if (ctx == null || cb == null || sInst != null) {
            return;
        }
        if (expectPkg != null) {
            String pn;
            try {
                pn = ctx.getPackageName();
            } catch (Throwable t) {
                return;
            }
            if (!expectPkg.equals(pn)) {
                return;
            }
        }
        try {
            StatusReqListener r = new StatusReqListener(cb);
            IntentFilter f = new IntentFilter(StatusReceiver.ACTION_REQ);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            sInst = r;
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            mCb.run();
        } catch (Throwable ignored) {
        }
    }
}
