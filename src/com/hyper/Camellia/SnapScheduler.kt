package com.hyper.Camellia

import android.content.Context

/**
 * 定时快照的触发点已迁移到 camd（见 tools/camellia_dsnap.c 的 dsnap_heartbeat）。
 * 这里保留空实现，仅为兼容旧的调用点（BootReceiver / App.onCreate / 设置页）。
 */
internal object SnapScheduler {
    @JvmStatic
    fun onBoot(ctx: Context) { /* 由 camd 负责 */ }

    @JvmStatic
    fun onAppStart(ctx: Context) { /* 由 camd 负责 */ }

    fun onPageOpen(ctx: Context): Pair<Boolean, String>? = null
}
