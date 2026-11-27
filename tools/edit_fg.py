# -*- coding: utf-8 -*-
"""Fg.java 单通道收敛：删除 remote prefs / cfg.txt 快照 / push payload，只读 CfgProvider。"""
import io, re, sys

P = "/sdcard/projects/misharemt2/src/com/hyper/Camellia/Fg.java"
src = io.open(P, encoding="utf-8").read()

def rep1(old, new, label):
    global src
    n = src.count(old)
    if n != 1:
        print("COUNT", label, "got", n); sys.exit(1)
    src = src.replace(old, new)
    print("OK:", label)

def cut(start, end, new, label):
    global src
    i = src.find(start)
    if i < 0:
        print("MISS start", label); sys.exit(1)
    j = src.find(end, i)
    if j < 0:
        print("MISS end", label); sys.exit(1)
    src = src[:i] + new + src[j:]
    print("OK:", label)

# A) 删除 PrefProvider 接口与 sPrefsProvider 字段
rep1('''    /** 由 Main 注入的「读远程偏好」能力（设置页改完即时生效） */
    public interface PrefProvider {
        SharedPreferences prefs();
    }

    private static XposedModule sMod;
    private static PrefProvider sPrefsProvider;
''', '''    private static XposedModule sMod;
''', "drop PrefProvider field")

# B) install 签名去掉 pp
rep1("    public static void install(XposedModule mod, ClassLoader cl, PrefProvider pp) {",
     "    public static void install(XposedModule mod, ClassLoader cl) {", "install sig")
rep1("        sCl = cl;\n        sPrefsProvider = pp;\n", "        sCl = cl;\n", "install body")

# C) 删除 sRemoteNote 字段
rep1('''    /** 远程偏好（getRemotePreferences）最近一次读取结果，仅用于日志诊断 */
    private static volatile String sRemoteNote = "unknown";
''', "", "drop sRemoteNote")

# D) 状态日志去掉 rem=
rep1('''                + " cfg=" + sCfgSrc + " rem=" + sRemoteNote + " order=" + sLastOrder''',
     '''                + " cfg=" + sCfgSrc + " order=" + sLastOrder''', "log line")
rep1('''                + "cfg=" + sCfgSrc + " rem=" + sRemoteNote + "\\n"''',
     '''                + "cfg=" + sCfgSrc + "\\n"''', "status line")

# E) cfg() 主链路改为只读 provider
cut('''    /**
     * 解析当前配置。取值优先级：''',
    '''    /** 每次同步都重新读一遍配置（缓存文件很小，避免进程内长期缓存导致「改了不生效」） */
''',
'''    /**
     * 解析当前配置。<b>只有一个来源</b>：CfgProvider（模块 app 的真源实时值）。
     *
     * <p>画报进程拿不到模块 prefs，也不再保留任何本进程快照。多源并存时「谁是最新」
     * 无从判断，曾直接导致「设置里 cap=10，实际只加进去 8 张」这类不一致。
     * provider 查不到就退回内置默认值。</p>
     *
     * <p>provider 由模块 app 进程应答：它没在运行时，一次查询会把模块 app 拉起
     * （冷启动几百毫秒，之后就一直可用），所以同步入口多等这一下是可接受的。</p>
     */
    static Cfg cfg(Context ctx) {
        Cfg c = loadProviderCfg(ctx);
        String src = Prefs.lastCfgChannel();
        if (c == null) {
            c = new Cfg();
            src = "default";
        }
        if (c.cap <= 0 || c.cap > 1000) {
            c.cap = DEF_CAP;
        }
        c.dir = normDir(c.dir);
        sCfgSrc = src;
        sLastCap = c.cap;
        sBlockAd = c.blockAd;
        sLocalPhoto = c.localPhoto;
        sPhotoDir = c.dir;
        sDebugHint = c.debug;
        return c;
    }

''', "cfg()")

# F1) 删除 cfgFile()
cut('''    private static File cfgFile(Context ctx) {''',
    '''    private static File managedFile(Context ctx) {''',
    '''    private static File managedFile(Context ctx) {''', "drop cfgFile")

# F2) 删除 loadPushed / savePushed / writeCfgFile* / loadRemote
cut('''    private static Cfg loadPushed(Context ctx) {''',
    '''    /** 读接管记录：prefix="p=" 是插入过的路径，"d=" 是同步过的目录 */''',
    '''    /** 读接管记录：prefix="p=" 是插入过的路径，"d=" 是同步过的目录 */''',
    "drop pushed/remote loaders")

# G) loadSystemCfg -> loadProviderCfg
cut('''    /**
     * 读完整画报配置（跨进程主通道）。''',
    '''    private static Cfg loadSystemCfg(Context ctx) {''',
'''    /**
     * 读完整画报配置（唯一通道：CfgProvider）。
     *
     * <p>{@link Prefs#readCfgText} 是纯 Binder 查询，任何进程可调、不受分区存储限制，
     * 拿到的是设置页最后一次写入的真源值。失败返回 null，由调用方退回默认值。</p>
     */
    private static Cfg loadProviderCfg(Context ctx) {''', "loadProviderCfg sig")

# H) applyPush 改为纯刷新
cut('''    /** 设置页推来新配置：落盘 -> 立刻失效缓存 -> 马上同步一次（不用重启画报） */''',
    '''    /** 画报进程第一次同步时向模块 app 拉一次配置（设置页改动时画报可能没在运行） */''',
'''    /** 收到「配置变了」信号：失效缓存 -> 重新从 provider 读 -> 立刻同步一次（不用重启画报） */
    private static void applyPush(Context ctx, Intent intent) {
        try {
            invalidateCfg();
            sCtx = ctx;
            Cfg c = cfg(ctx);
            log(Log.INFO, TAG, "cfg changed -> dir=" + c.dir + " cap=" + c.cap
                    + " enabled=" + c.enabled + " order=" + c.order
                    + " localPhoto=" + c.localPhoto + " curated=" + c.curated
                    + " blockAd=" + c.blockAd + " debug=" + c.debug);
            writeStatus();
            // 配置刚变，绕过去抖立刻按新配置同步一次（否则可能在 800ms 内被当成重复触发丢掉）
            if (sRunning) {
                sResyncPending = true;
            } else {
                sLastTriggerAt = 0;
                sync(ctx, "cfg-push");
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cfg push apply failed: " + t);
        }
    }

''', "applyPush")

# I) 删除 requestCfgOnce 及其调用、sCfgPulled 字段
cut('''    /** 画报进程第一次同步时向模块 app 拉一次配置（设置页改动时画报可能没在运行） */''',
    '''    /** 扫描目录：只收图片扩展名 */''',
    '''    /** 扫描目录：只收图片扩展名 */''', "drop requestCfgOnce")
rep1("        ensureCfgReceiver(ctx);\n        requestCfgOnce(ctx);\n",
     "        ensureCfgReceiver(ctx);\n", "call site")
rep1('''    private static volatile boolean sCfgPulled;
''', "", "drop sCfgPulled")

# J) CFG_FILE 常量
rep1('''    private static final String CFG_FILE = "cfg.txt";
''', "", "drop CFG_FILE const")
rep1('''    /** 侧车文件里读到的调试开关（由 loadSidecar/loadPushed 顺手更新，避免递归读取） */''',
     '''    /** 配置里读到的调试开关（由 loadProviderCfg 顺手更新，避免递归读取） */''', "debug hint comment")

io.open(P, "w", encoding="utf-8").write(src)
print("done, bytes:", len(src), "braces:", src.count("{") - src.count("}"))
