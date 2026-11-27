package com.hyper.Camellia;

import android.app.Application;

/**
 * 模块自身的 Application。
 * 每次模块 App 进程启动时按保存的偏好同步一次「桌面图标」状态，
 * 避免覆盖安装 / 系统重置后图标状态与设置不一致。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Prefs.applyIconVisibility(this, Prefs.isHideIcon(this));
        } catch (Throwable ignored) {
        }
        // A 开关（「桌面内自由小窗」）：补丁跑在桌面进程的 native 侧，那里读不到 prefs，
        // 只能读落盘副本。App 每次启动补写一次，保证文件与设置一致（清数据/换机恢复后尤其重要）。
        try {
            Prefs.syncASwitch(Prefs.isUlOn(this, Prefs.ID_UL_LAUNCHER_FREEFORM));
        } catch (Throwable ignored) {
        }
        // 设置页 ⇄ 框架 remote preferences 的镜像通道：
        // 没有它，system_server / 目标进程里的 hook（长按菜单「小窗」开关等）
        // 读到的永远是默认值 false（详见 CfgRemote 类注释）。
        try {
            CfgRemote.init(this);
        } catch (Throwable ignored) {
        }
        // root 守护进程（camd）：注入 system_server 的模块代码所在 SELinux 域
        // (u:r:system_server:s0) 禁止 fork/exec sh|cmd|pm，所有特权动作必须由
        // uid=0 的 camd 代跑。这里后台自动释放二进制 + su 拉起 + 写 service.d 自启，
        // 用户只需在 KSU 弹窗点一次允许（详见 DaemonInstaller 类注释）。
        try {
            DaemonInstaller.ensureAsync(this);
        } catch (Throwable ignored) {
        }
        // camd 的 socket 通道（方案A）：连上后命令与状态都走这条长连接，
        // App 不再读 FUSE 状态文件、camd 不再每秒 fopen 取指令。
        // 连不上（SELinux 未放行 / camd 未起）时静默重试，调用方自动回退文件通道。
        try {
            if (!Prefs.isCamdDisabled(this)) {
                CamdSocket.start();
            }
        } catch (Throwable ignored) {
        }
        // 定时快照的到期检查：不用闹钟、不用 camd 常驻。
        // 模块进程每次被拉起（开机 / 打开设置 / 框架拉起）都是一次机会，
        // 到期就后台补一份，见 SnapScheduler 类注释。
        try {
            SnapScheduler.onAppStart(this);
        } catch (Throwable ignored) {
        }
    }
}
