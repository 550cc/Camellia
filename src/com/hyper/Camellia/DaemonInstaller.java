package com.hyper.Camellia;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * root 守护进程（camd）的自安装 / 自启动 / 自检 —— 用户零操作。
 *
 * <h3>为什么必须有这个守护进程</h3>
 * <p>模块代码被 LSPosed 注入到 system_server 进程里执行，注入后的身份不是本 App，
 * 而是宿主：uid=1000、SELinux 域 {@code u:r:system_server:s0}。该域被策略禁止
 * fork/exec {@code shell_exec} 标签的工具（sh / cmd / pm），实测报
 * {@code exec fail /system/bin/cmd}（error=13 Permission denied）。</p>
 *
 * <p>而 KSU / Magisk 的 root 授权按 uid 签发，签给的是本 App（uid 10xxx）——
 * system_server 不是本 App，它没有任何途径自己变成 uid=0。所以
 * 「模块已经 root 了」只对 App 进程成立，对注入到 system_server 的那份代码不成立。</p>
 *
 * <p>唯一可靠的通路是一个真正的 uid=0 常驻进程代为执行。本类负责把它装好：
 * <pre>
 *   assets/camd.bin --解压--> files/camd --su 拉起--> 常驻 camd (uid=0)
 *                                       --自动写入--> /data/adb/service.d/camellia_daemon.sh
 * </pre>
 * 打开 App 自动完成，KSU 弹一次授权，之后开机自启。</p>
 *
 * <h3>与 system_server 的通信协议</h3>
 * <p>与 {@code tools/camellia_bridge.c} 严格约定，不要单方面修改：</p>
 * <pre>
 *   工作目录 /data/system/camellia_action（root:system 0770 —— system_server 要能写）
 *   请求 req.&lt;nonce&gt; = "CCB1 &lt;tsMs&gt; &lt;nonce&gt; &lt;hmac&gt;\n&lt;命令&gt;"
 *   hmac = HMAC-SHA256(BRIDGE_KEY, "&lt;tsMs&gt;\n&lt;nonce&gt;\n&lt;命令&gt;")
 *   应答 res.&lt;nonce&gt; = 标准输出 + " (exit=N)"
 * </pre>
 */
public final class DaemonInstaller {

    private static final String TAG = "MiShareMT2/Camd";

    /** 守护进程二进制名（释放到 App 私有 files/ 下，su 可直接执行 app_data_file） */
    public static final String BIN = "camd";
    /** APK 内打包的二进制（gzip 压缩内容，扩展名故意用 .bin：AGP 会把 *.gz 自动解压） */
    private static final String ASSET = "camd.bin";
    /** 开机自启脚本名（assets/camd_service.sh 释放而来） */
    private static final String SVC_NAME = "camellia_daemon.sh";
    /** KSU / Magisk 的 service.d 目录（init 以 root 执行其中脚本） */
    private static final String SERVICE_D = "/data/adb/service.d/";
    /** 守护进程工作目录（system_server 与 root 都能访问） */
    public static final String WORK_DIR = "/data/system/camellia_action";

    /**
     * 母本 DE 副本目录。
     *
     * <p>{@code /data/adb} 属 DE（Direct Boot）分区，首次解锁前即可读写执行；
     * 而 {@code /data/user/0/<pkg>/files} 属 CE 分区，DE 阶段整个不可访问
     * （实测 DE 时 {@code /data/user/0} 列目录为空、stat 失败）。</p>
     *
     * <p>所以开机脚本必须从这里拿母本，否则会出现「脚本在跑、却永远等不到
     * 可执行母本」的死等，camd 要拖到用户解锁后才起得来。</p>
     */
    public static final String DE_DIR = "/data/adb/camellia";

    /** DE 母本文件名 **/
    private static final String DE_BIN = DE_DIR + "/" + BIN;

    /** 本次 ensure 是否刚替换了二进制（替换过就必须重启进程，否则跑的是旧代码） */
    private static volatile boolean sBinChanged;

    /** 防止 App 每次启动都重复跑一轮 su（进程内只做一次） */
    private static volatile boolean sTried;
    private static volatile String sLast = "";
    /** assets/camd.bin 解压后的内容哈希（MD5 前 16 位 hex）；进程内缓存 */
    private static volatile String sAssetDigest;

    private DaemonInstaller() {
    }

    // ===================== 对外入口 =====================

    /** App 启动时调用：后台线程装好并拉起守护进程，绝不阻塞主线程（su 会等用户授权） */
    public static void ensureAsync(final Context ctx) {
        if (ctx == null || sTried) {
            return;
        }
        // 用户点过「一键卸载」：停用标记在，就不许自动装回（否则卸载等于白做）
        try {
            if (Prefs.isCamdDisabled(ctx)) {
                sTried = true;
                sLast = "已卸载（停用标记）";
                return;
            }
        } catch (Throwable ignored) {
        }
        sTried = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String r = ensure(ctx);
                    sLast = r;
                    Log.println(Log.INFO, TAG, "ensure -> " + r);
                    Prefs.logApp(ctx, Log.INFO, TAG, "daemon ensure -> " + r);
                } catch (Throwable t) {
                    sLast = "ensure exception: " + t;
                    Log.println(Log.WARN, TAG, sLast);
                }
            }
        }, "CamelliaDaemonEnsure").start();
    }

    /** 设置页「立即启动 / 修复」按钮：同步跑一遍（调用方自行放到子线程） */
    public static String ensureNow(Context ctx) {
        try {
            sLast = ensure(ctx);
        } catch (Throwable t) {
            sLast = "失败: " + t;
        }
        return sLast;
    }

    // ===================== 一键卸载 =====================

    /**
     * 一键卸载 camd 组件：停保活 → 杀进程 → 删自启脚本 → 删二进制/运行时数据。
     *
     * <p>调用方必须放到子线程（内部多次 su + sleep，最长 ~15s）。</p>
     *
     * <h3>为什么先置停用标记</h3>
     * <p>{@link App#onCreate} 每次进程启动都会 {@link #ensureAsync} 自动重装，
     * {@link BootReceiver} 开机也会补一次。不先落标记，卸载会在下次开 App 时被
     * 静默装回来 —— 用户看到的就是「卸载了但状态还是运行中」。标记由面板的
     * 「重新部署」清除。</p>
     *
     * <h3>为什么要写成脚本文件再执行</h3>
     * <p>保活脚本进程的 cmdline 含 {@code camellia_daemon.sh}，若用
     * {@code su -c "pgrep -f camellia_daemon.sh ..."} 内联执行，执行它的这个 shell
     * 自身 cmdline 也含该串，pgrep 会命中自己并把脚本杀掉，导致后面的删除步骤
     * 全部丢失（踩过：卸载只成功了一半，camd 仍在跑）。写成独立脚本后命令行只有
     * 脚本路径，不含关键字。</p>
     *
     * @return "OK" 卸载干净 · "NOSU" 拿不到 root · "PARTIAL" 有残留 · "ERR|..." 异常
     */
    public static String uninstall(Context ctx) {
        if (ctx == null) return "ERR|no ctx";
        // 先落标记：即使后续 su 失败，也不会刚删完就被自动装回
        try {
            Prefs.setCamdDisabled(ctx, true);
        } catch (Throwable ignored) {
        }
        File sh = new File(ctx.getFilesDir(), "camd_uninstall.sh");
        try {
            String pkg = ctx.getPackageName();
            String script = String.join("\n",
                    "#!/system/bin/sh",
                    "SD=/data/adb/service.d",
                    "DE=" + DE_DIR,
                    "CE=/data/user/0/" + pkg + "/files",
                    "UIDIR=/sdcard/Android/data/" + pkg + "/files",
                    "",
                    "# 1) 停保活循环：只杀真正的守护脚本进程，排除自身",
                    "for p in $(pgrep -f 'camellia_daemon\\.sh' 2>/dev/null); do",
                    "  [ \"$p\" = \"$$\" ] && continue",
                    "  kill -TERM \"$p\" 2>/dev/null",
                    "done",
                    "sleep 1",
                    "",
                    "# 2) 删 service.d 自启脚本，否则下次开机 init 又拉起来",
                    "rm -f \"$SD/camellia_daemon.sh\" \"$SD/camd_service.sh\"",
                    "",
                    "# 3) 杀 camd：先 TERM 留自清理机会，不走再 KILL",
                    "i=0",
                    "while [ $i -lt 5 ]; do",
                    "  P=$(pidof camd 2>/dev/null)",
                    "  [ -z \"$P\" ] && break",
                    "  kill -TERM $P 2>/dev/null",
                    "  sleep 1",
                    "  i=$((i+1))",
                    "done",
                    "P=$(pidof camd 2>/dev/null)",
                    "[ -n \"$P\" ] && kill -KILL $P 2>/dev/null",
                    "",
                    "# 4) 删二进制与运行时数据（含日志/快照/配置/socket）",
                    "rm -rf \"$DE\"",
                    "rm -f \"$CE/camd\" \"$CE/camd.sig\"",
                    "rm -f \"$CE/camd.log\" \"$CE/camd.err.log\" \"$CE/camd.sock\" \"$CE/camellia_daemon.sh\"",
                    "rm -rf " + WORK_DIR,
                    "",
                    "# 4.5) 清 /dev 隐藏运行副本：新版启动时把 camd 拷进"
                            + " /dev/<随机8位>/.x 运行（目录名每次部署随机生成，"
                            + "不能写死；老设备可能残留 /dev/tsiltdid/.x）",
                    "for d in /dev/*/.x; do",
                    "  [ -d \"$d\" ] || continue",
                    "  rm -f \"$d/camd\" \"$d/.sig\" 2>/dev/null",
                    "  rmdir \"$d\" 2>/dev/null",
                    "done",
                    "",
                    "# 5) 清面板读的状态文件，避免残留旧快照",
                    "rm -f \"$UIDIR/camd_status.txt\" \"$UIDIR/camd_cmd\" \"$UIDIR/camd_snap.txt\"",
                    "",
                    "# 6) 结论",
                    "if [ -z \"$(pidof camd 2>/dev/null)\" ] && [ ! -e \"$DE\" ]"
                            + " && [ ! -e \"$SD/camellia_daemon.sh\" ]"
                            + " && [ -z \"$(ls -d /dev/*/.x 2>/dev/null)\" ]; then",
                    "  echo OK",
                    "else",
                    "  echo PARTIAL",
                    "fi",
                    "");
            FileOutputStream fos = new FileOutputStream(sh);
            try {
                fos.write(script.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            String out = suExec("chmod 700 " + q(sh.getAbsolutePath())
                    + " && sh " + q(sh.getAbsolutePath()), 25000);
            if (out == null) return "NOSU";
            String t = out.trim();
            if (t.contains("OK")) return "OK";
            if (t.contains("PARTIAL")) return "PARTIAL";
            sLast = "uninstall: " + t;
            return "ERR|" + (t.isEmpty() ? "empty" : t);
        } catch (Throwable t) {
            return "ERR|" + t;
        } finally {
            try {
                sh.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    // ===================== 暂停 / 重新启动 =====================

    /**
     * 暂停 camd：停保活循环 → 杀进程 → 落 {@code $DE_DIR/paused} 标记，
     * 但<b>保留</b>二进制、配置、快照与自启脚本。
     *
     * <p>与 {@link #uninstall} 的区别：卸载是删干净且不可恢复，暂停只是让它停下，
     * 用户随时可以「重新启动」原样恢复，配置（target / auto_fix / 监控包）一字不动。</p>
     *
     * <p>为什么要落 paused 标记而不只是杀进程：service.d 里的自启脚本是 60 秒死循环
     * 保活，只杀 camd 的话它 60 秒后就会被重新拉起；重启手机 init 也会再执行一次。
     * 标记让脚本在开机入口处直接退出（一次 stat，无常驻开销）。</p>
     *
     * <p>调用方必须放到子线程（内部多次 su + sleep，最长可达十几秒）。</p>
     *
     * @return "OK" 已停 · "NOSU" 拿不到 root · "PARTIAL" 仍有余留 · "ERR|..." 异常
     */
    public static String stop(Context ctx) {
        if (ctx == null) return "ERR|no ctx";
        // 先落停用标记：即使后续 su 失败，App 重启也不会被 ensureAsync 自动装回
        try {
            Prefs.setCamdDisabled(ctx, true);
        } catch (Throwable ignored) {
        }
        File sh = new File(ctx.getFilesDir(), "camd_stop.sh");
        try {
            // 写成脚本文件再执行：pgrep -f 若内联执行会命中执行它的 shell 自身
            String script = String.join("\n",
                    "#!/system/bin/sh",
                    "SD=/data/adb/service.d",
                    "DE=" + DE_DIR,
                    "",
                    "# 1) 落暂停标记：开机自启脚本据此直接退出",
                    "mkdir -p \"$DE\" 2>/dev/null",
                    ": > \"$DE/paused\" 2>/dev/null",
                    "",
                    "# 2) 停保活循环（service.d 自启脚本 / 旧版 daemon 脚本）",
                    "for p in $(pgrep -f 'camellia_daemon\\.sh' 2>/dev/null); do",
                    "  [ \"$p\" = \"$$\" ] && continue",
                    "  kill -TERM \"$p\" 2>/dev/null",
                    "done",
                    "sleep 1",
                    "",
                    "# 3) 杀 camd：先 TERM 留自清理机会，不走再 KILL",
                    "i=0",
                    "while [ $i -lt 5 ]; do",
                    "  P=$(pidof camd 2>/dev/null)",
                    "  [ -z \"$P\" ] && break",
                    "  kill -TERM $P 2>/dev/null",
                    "  sleep 1",
                    "  i=$((i+1))",
                    "done",
                    "P=$(pidof camd 2>/dev/null)",
                    "[ -n \"$P\" ] && kill -KILL $P 2>/dev/null",
                    "sleep 1",
                    "",
                    "# 4) 结论：camd 与保活循环都要消失才算停干净",
                    "if [ -z \"$(pidof camd 2>/dev/null)\" ]"
                            + " && [ -z \"$(pgrep -f 'camellia_daemon\\.sh' 2>/dev/null)\" ]; then",
                    "  echo OK",
                    "else",
                    "  echo PARTIAL",
                    "fi",
                    "");
            writeText(sh, script);
            String out = suExec("chmod 700 " + q(sh.getAbsolutePath())
                    + " && sh " + q(sh.getAbsolutePath()), 25000);
            if (out == null) return "NOSU";
            String t = out.trim();
            if (t.contains("OK")) return "OK";
            if (t.contains("PARTIAL")) return "PARTIAL";
            return "ERR|" + (t.isEmpty() ? "empty" : t);
        } catch (Throwable t) {
            return "ERR|" + t;
        } finally {
            try {
                sh.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 重新启动 camd：撤停用标记 → 删 paused 标记 → 杀残留 → 重新部署拉起
     * → 确认常驻保活循环在跑。
     *
     * <p>比「重新部署」轻：二进制内容没变时 {@link #ensure} 内部不会重写它，
     * 只做拉起与自启脚本安装。暂停期间被杀的保活循环也会在这里补回来。</p>
     *
     * <p>调用方必须放到子线程。</p>
     */
    public static String restart(Context ctx) {
        if (ctx == null) return "ERR|no ctx";
        clearDisabled(ctx);
        try {
            suExec("rm -f " + DE_DIR + "/paused 2>/dev/null; "
                    + "P=$(pidof camd 2>/dev/null); "
                    + "[ -n \"$P\" ] && kill -TERM $P 2>/dev/null; sleep 1; "
                    + "P=$(pidof camd 2>/dev/null); "
                    + "[ -n \"$P\" ] && kill -KILL $P 2>/dev/null; true", 12000);
        } catch (Throwable ignored) {
        }
        String r;
        try {
            r = ensure(ctx);
        } catch (Throwable t) {
            return "ERR|" + t;
        }
        // ensure 只跑了一次 --once；保活循环若在暂停时被杀，这里补起来，
        // 否则 camd 崩了就再没人管，直到下次开机或开 App。
        try {
            suExec("SD=/data/adb/service.d; F=\"$SD/camellia_daemon.sh\"; "
                    + "if [ -x \"$F\" ] && [ -z \"$(pgrep -f 'camellia_daemon\\.sh' 2>/dev/null)\" ]; then "
                    + "nohup sh \"$F\" >/dev/null 2>&1 & fi; sleep 1; "
                    + "pgrep -f 'camellia_daemon\\.sh' >/dev/null 2>&1 && echo KA_ON || echo KA_OFF",
                    15000);
        } catch (Throwable ignored) {
        }
        return r;
    }

    /** 撤销停用标记（面板「重新部署」用），随后调用方自行 {@link #ensureNow}。 */
    public static void clearDisabled(Context ctx) {
        try {
            Prefs.setCamdDisabled(ctx, false);
        } catch (Throwable ignored) {
        }
        sTried = false;
    }

    /** 状态摘要（设置页展示用；不阻塞：只在必要时才 su） */
    /**
     * 结构化探测 camd 守护进程状态（一次 su 调用拿全，省掉多次 su 唤起）。
     *
     * <p>@return "NOBIN" 二进制没装 · "NOSU" su 无响应 · "DOWN" 装了没跑 ·
     * "MULTI|p1,p2" 多实例 · "OK|pid|nice|exe" 正常 · "ERR|..." 异常</p>
     *
     * <p>多实例是真实故障：两个 camd 会抢同一条桥 socket，谁先 accept 不确定，
     * 表现为「有时能执行、有时静默超时」——这种不稳定比彻底不跑更难查。</p>
     */
    public static String probe(Context ctx) {
        try {
            File bin = new File(ctx.getFilesDir(), BIN);
            if (!bin.isFile() || bin.length() <= 0L) return "NOBIN";
            String out = suExec("D=" + DE_DIR + "; S=$([ -x \"$D/" + BIN + "\" ] && echo 1 || echo 0); "
                    + "P=$(pidof " + BIN + " 2>/dev/null); "
                    + "if [ -z \"$P\" ]; then echo \"DOWN|$S\"; else N=$(echo $P | wc -w); "
                    + "if [ $N -gt 1 ]; then echo \"MULTI|$(echo $P | tr ' ' ',')\"; "
                    + "else echo \"OK|$P|$(ps -o NI= -p $P | tr -d ' ')|$(readlink /proc/$P/exe)|$S\"; fi; fi", 8000);
            if (out == null) return "NOSU";
            String s = out.trim();
            return s.length() == 0 ? "ERR|空输出" : s;
        } catch (Throwable t) {
            return "ERR|" + t;
        }
    }

    public static String statusText(Context ctx) {
        String p = probe(ctx);
        if (p.equals("NOBIN")) return "未安装（打开 App 会自动安装）";
        if (p.equals("NOSU")) return "已安装，但 su 无响应（检查 KSU/Magisk 授权）";
        if (p.startsWith("ERR|")) return "状态检测失败: " + p.substring(4);
        if (p.startsWith("DOWN")) {
            boolean de = p.endsWith("|1");
            return de ? "已安装，未运行（DE 母本就绪，点「启动」）"
                      : "已安装，未运行（⚠ DE 母本缺失，解锁前无法自启）";
        }
        String[] f = p.split("\\|", -1);
        if (p.startsWith("MULTI|")) {
            return "⚠ 检测到多个实例 pid=" + (f.length > 1 ? f[1] : "?") + "（重复启动会互相抢桥，点「启动 / 修复」收编）";
        }
        boolean deReady = f.length > 4 && "1".equals(f[4]);
        return "运行中 pid=" + (f.length > 1 ? f[1] : "?") + " nice=" + (f.length > 2 ? f[2] : "?")
                + "\n路径 " + (f.length > 3 ? f[3] : "?")
                + "\nDE 母本 " + (deReady ? "✅ 就绪（解锁前可自启）" : "❌ 缺失");
    }

    /** 守护进程当前是否活着（system_server 侧也可用来做提示） */
    public static boolean isAlive(Context ctx) {
        String out = suExec("pgrep -x " + BIN, 8000);
        return out != null && out.trim().length() > 0;
    }

    // ===================== 安装主流程 =====================

    /**
     * 幂等安装：释放二进制（版本变了才重写）→ 拉起 → 写开机自启。
     *
     * @return 人类可读的结果描述（同时写进 action_log）
     */
    public static String ensure(Context ctx) {
        if (ctx == null) {
            return "ctx null";
        }
        sBinChanged = false;
        // 0. root 可用性（KSU 首次会弹授权框，给足 30s）
        String uid = suExec("id -u", 30000);
        if (uid == null) {
            return "NO_ROOT su 无响应";
        }
        if (!"0".equals(uid.trim())) {
            return "NO_ROOT 未授权 (id -u=" + uid.trim() + ")";
        }

        // 1. 释放二进制
        File bin;
        try {
            bin = releaseBin(ctx);
        } catch (Throwable t) {
            return "ASSET_FAIL " + t;
        }

        suExec("chmod 755 " + q(bin.getAbsolutePath()), 8000);

        // 1.5 同步母本到 DE 分区：解锁前 camd 只能从这里拉起
        //     （失败不阻断流程，CE 母本仍在，解锁后功能不受影响）
        String desync = syncToDe(ctx, bin);

        // 2. 释放部署脚本并装到 service.d（开机由 init 以 root 执行）
        File script = installScript(ctx);
        if (script == null) {
            return "SCRIPT_FAIL assets/camd_service.sh 缺失";
        }

        // 3. 跑一次部署：App 保活八连 + 随机隐藏挂载点 + 拉起 + renice -20
        //    （脚本内部幂等：camd 已在跑且二进制未变时不会重启它）
        String dep = suExec("sh " + q(script.getAbsolutePath()) + " --once", 60000);
        if (dep == null) {
            return "FAIL 部署脚本无响应（su 超时，检查 KSU 授权）";
        }
        String d = dep.trim();
        if (!d.contains("OK pid=")) {
            return "FAIL de=" + desync + " " + d.replace('\n', ' ').trim();
        }
        // 部署成功后初始化救援配置：仅在 config 缺失时写默认（auto_fix=0 只诊断）。
        // 已存在则一字不改——生产配置的 target/auto_fix 决定安全边界，绝不能被覆盖。
        // 失败不阻断部署结果，用户仍可手工放 config。
        try {
            CamdRescue.ensureConfig();
        } catch (Throwable ignored) {
        }
        return "de=" + desync + " " + d.replace('\n', ' ').trim();
    }

    // ===================== 内部实现 =====================

    /** 解压 assets/camd.bin 到 files/camd；App 版本没变且文件已在则跳过 */
    private static File releaseBin(Context ctx) throws Exception {
        File dir = ctx.getFilesDir();
        File bin = new File(dir, BIN);
        File sigFile = new File(dir, BIN + ".sig");
        String sig = appVersion(ctx);

        String old = readText(sigFile);
        if (bin.isFile() && bin.length() > 0L && sig.equals(old)) {
            return bin;
        }

        File tmp = new File(dir, BIN + ".tmp");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = openAsset(ctx);
            out = new FileOutputStream(tmp, false);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
        if (tmp.length() <= 0L) {
            tmp.delete();
            throw new IllegalStateException("解压后为空（APK 里 assets/" + ASSET + " 缺失？）");
        }
        // rename 在 Linux 上是原子覆盖：不需要先 delete（先删会留一个"文件不存在"的窗口，
        // 且运行中的 camd 占的是隐藏副本，不会被这里阻塞）
        if (!tmp.renameTo(bin)) {
            bin.delete();
            if (!tmp.renameTo(bin)) {
                tmp.delete();
                throw new IllegalStateException("rename 失败");
            }
        }
        bin.setReadable(true, false);
        bin.setExecutable(true, false);
        writeText(sigFile, sig);
        sBinChanged = true;
        Log.println(Log.INFO, TAG, "released " + BIN + " " + bin.length() + "B (ver " + sig + ")");
        return bin;
    }

    /**
     * 把母本同步到 DE 分区 {@code /data/adb/camellia/camd}。
     *
     * <p>开机脚本从 DE 取母本，才能在「首次解锁前」把 camd 拉起来。同步按
     * 版本签名（versionCode + lastUpdateTime）判重：签名一致就跳过，
     * 避免每次启动都白拷 280KB。</p>
     *
     * <p>写入走 {@code camd.tmp → mv}：同一文件系统上的 mv 是原子的，
     * 不会留下「母本写了一半」的破损窗口（此时开机脚本正好可能来读）。</p>
     *
     * @return {@code SYNCED} 已更新 · {@code FRESH} 已是最新 · {@code ERR...} 失败
     */
    private static String syncToDe(final Context ctx, final File bin) {
        try {
            final String sig = appVersion(ctx);
            final String digest = assetDigest(ctx);
            // 判据用「DE 母本内容哈希 vs APK 内 camd 内容哈希」，不再用 camd.sig 里的
            // 版本字符串：版本号只能说明 App 换了，说明不了二进制换没换；反过来，
            // 二进制换了而版本没升（调试期常态）时旧判据会误判 FRESH，把新版挡在门外。
            String cmd = "D=" + DE_DIR + "; mkdir -p \"$D\" 2>/dev/null; chmod 700 \"$D\" 2>/dev/null; "
                    + "cur=$(md5sum \"$D/" + BIN + "\" 2>/dev/null | cut -c1-16); "
                    + "if [ -n \"$cur\" ] && [ \"$cur\" = \"" + digest + "\" ] && [ -x \"$D/" + BIN + "\" ]; "
                    + "then echo FRESH; "
                    + "else cp -f " + q(bin.getAbsolutePath()) + " \"$D/camd.tmp\" "
                    + "&& chmod 700 \"$D/camd.tmp\" && chown 0:0 \"$D/camd.tmp\" "
                    + "&& mv -f \"$D/camd.tmp\" \"$D/" + BIN + "\" "
                    + "&& md5sum \"$D/" + BIN + "\" | cut -c1-16 > \"$D/camd.sig\" "
                    + "&& echo SYNCED || echo ERR; fi";
            String out = suExec(cmd, 20000);
            if (out == null) {
                return "ERR:su超时";
            }
            String s = out.trim();
            Log.println(Log.INFO, TAG, "syncToDe -> " + s + " digest=" + digest + " sig=" + sig);
            return s.length() == 0 ? "ERR:空输出" : s;
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    /**
     * 打开 assets 里的二进制。
     * 打包路径不同，资源形态可能不同：build.sh 原样打包 gzip 内容，而 AGP 会把
     * *.gz 自动解压——所以先按 gzip 读，失败再按原始 ELF 读。
     */
    private static InputStream openAsset(Context ctx) throws Exception {
        Exception first = null;
        try {
            return new GZIPInputStream(ctx.getAssets().open(ASSET), 8192);
        } catch (Exception e) {
            first = e;
        }
        try {
            return ctx.getAssets().open("camd");
        } catch (Exception e) {
            throw first != null ? first : e;
        }
    }

    /**
     * 释放部署脚本：assets/camd_service.sh -> files/camellia_daemon.sh
     * 并安装到 /data/adb/service.d/（root 执行，幂等覆盖）。
     *
     * <p>整套部署逻辑（App 保活八连、随机 debugfs 隐藏挂载点、camd 拉起 + renice、
     * 日志轮转、60s 看护）都写在脚本里，Java 侧只管释放和触发 —— 逻辑单点维护，
     * 改脚本不需要动代码。</p>
     *
     * @return 脚本本地路径；assets 缺失时返回 null
     */
    private static File installScript(Context ctx) {
        try {
            File local = new File(ctx.getFilesDir(), SVC_NAME);
            String body = readAssetText(ctx, "camd_service.sh");
            if (body == null || body.length() < 500) {
                Log.println(Log.WARN, TAG, "assets/camd_service.sh missing or too short");
                return null;
            }
            body = body.replace("@PKG@", ctx.getPackageName());
            if (!body.equals(readText(local))) {
                writeText(local, body);
            }
            String dst = SERVICE_D + SVC_NAME;
            suExec("mkdir -p " + SERVICE_D + "; cp " + q(local.getAbsolutePath()) + " " + dst
                    + "; chmod 755 " + dst + "; chown 0:0 " + dst, 15000);
            return local;
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "installScript failed: " + t);
            return null;
        }
    }

    /** 读 assets 文本（缺失返回 null） */
    private static String readAssetText(Context ctx, String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            byte[] b = new byte[(int) Math.max(1024, in.available())];
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(b)) > 0) {
                bo.write(b, 0, n);
            }
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 执行 su -c 命令并返回 stdout+stderr；超时或异常返回 null（调用方据此判 NO_ROOT） */
    private static String suExec(String cmd, int timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(new String[]{"su", "-c", cmd});
            pb.redirectErrorStream(true);
            p = pb.start();
            final StringBuilder sb = new StringBuilder();
            final InputStream is = p.getInputStream();
            final byte[] buf = new byte[2048];
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            sb.append(new String(buf, 0, n, "UTF-8"));
                            if (sb.length() > 8192) {
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "CamdSuRead");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
                return null;
            }
            reader.join(600);
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 二进制新旧判据：版本号 + APK 安装时间。
     * 只用 versionCode 不够——同版本重装（调试时天天发生）不会刷新 camd，
     * 结果跑的是上一次构建的旧二进制，现象是「改了代码但行为没变」。
     */
    private static String appVersion(Context ctx) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            // 末尾拼 assets 内容哈希：只带 versionCode/lastUpdateTime 时，若 APK 不升版本
            // 却换了 assets/camd.bin（调试期常态），判据不变 → releaseBin 认为「已是最新」
            // → 装回去的仍是旧守护进程。2026-11-03 实际踩到：新旧 camd 都是 673424 B，
            // size-mtime 型 sig 一字不差，线上一直跑旧版。内容哈希让判据与二进制内容绑定。
            return pi.versionCode + "-" + pi.lastUpdateTime + "-" + assetDigest(ctx);
        } catch (Throwable t) {
            return "0";
        }
    }

    /**
     * assets 内 camd 解压后的 MD5 前 16 位（8 字节 hex）。
     *
     * <p>口径必须与 {@code md5sum <DE 母本> | cut -c1-16} 一致，否则 syncToDe 的
     * 比对会永远不等、每次启动都白刷一遍母本。{@link #openAsset} 已处理
     * 「gzip 打包 / 原始 ELF」两种形态，这里直接复用。</p>
     *
     * <p>失败返回 {@code "0"}：判据退化成老格式，行为等同旧版——宁可漏刷新，
     * 也不要因为算哈希失败把整个部署链卡死。</p>
     */
    private static String assetDigest(Context ctx) {
        String cached = sAssetDigest;
        if (cached != null) {
            return cached;
        }
        InputStream in = null;
        try {
            in = openAsset(ctx);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            String s = sb.toString();
            sAssetDigest = s;
            return s;
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "assetDigest failed: " + t);
            return "0";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 单引号安全包裹（路径里不会有单引号，但仍按规矩来） */
    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String readText(File f) {
        try {
            if (!f.isFile() || f.length() <= 0L || f.length() > 4096L) {
                return "";
            }
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n;
            try {
                n = in.read(b);
            } finally {
                in.close();
            }
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeText(File f, String s) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(s.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
