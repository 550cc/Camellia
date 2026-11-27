# -*- coding: utf-8 -*-
"""Prefs.java 第 2 批：删除 writeSidecar/writeSidecarNow，patch* 改为只发刷新信号。"""
import io, re, sys

P = "/sdcard/projects/misharemt2/src/com/hyper/Camellia/Prefs.java"
src = io.open(P, encoding="utf-8").read()

def rep(old, new, label, count=1):
    global src
    n = src.count(old)
    if n != count:
        print("COUNT MISMATCH", label, "expect", count, "got", n)
        sys.exit(1)
    src = src.replace(old, new)
    print("OK:", label)

# ---- 1) 删除三个写侧车的方法定义 ----
start = src.find("    /** 把「打开方式」写成纯文本侧车文件；写入失败的位置直接跳过，不影响主流程 */")
end_marker = """    /** 落配置（只应在后台队列里调用） */
    static void writeSidecarNow(Context ctx, String mtTarget, String fgDir) {
        String body = buildCfgBody(ctx, mtTarget, fgDir);
        // Settings.System 能写就写一份：目标进程直接读它最省事（不需要拉起本进程）。
        // 写不进去也无所谓 —— 目标进程会用 CfgProvider 主动拉，见 readCrossProcCfg。
        writeSystemCfg(ctx, body);
    }
"""
end = src.find(end_marker)
if start < 0 or end < 0:
    print("MISS sidecar methods", start, end); sys.exit(1)
end += len(end_marker)
src = src[:start] + src[end:]
print("OK: removed writeSidecar/writeSidecarNow defs")

# ---- 2) 删除所有调用语句（单行） ----
lines = src.split("\n")
kept = []
removed = 0
for ln in lines:
    s = ln.strip()
    if (s.startswith("writeSidecar(") or s.startswith("Prefs.writeSidecar(")) and s.endswith(");"):
        removed += 1
        continue
    kept.append(ln)
src = "\n".join(kept)
print("OK: removed call sites =", removed)

# ---- 3) patchUlSidecar ----
old = """    private static void patchUlSidecar(Context ctx) {
        writeSidecar(ctx, getMtTarget(ctx), getFgDir(ctx));
    }"""
if old not in src:
    # 可能带注释体，用正则定位方法体
    m = re.search(r"    private static void patchUlSidecar\(Context ctx\) \{.*?\n    \}\n", src, re.S)
    if not m:
        print("MISS patchUlSidecar"); sys.exit(1)
    src = src[:m.start()] + """    private static void patchUlSidecar(Context ctx) {
        // 单通道：真源是 prefs，CfgProvider 随时能读到最新值。
        // 这里只补一个「配置变了，去重读」的刷新信号。
        CfgBridge.push(ctx, TARGET_PKG);
    }
""" + src[m.end():]
    print("OK: patchUlSidecar (regex)")
else:
    src = src.replace(old, """    private static void patchUlSidecar(Context ctx) {
        // 单通道：真源是 prefs，CfgProvider 随时能读到最新值。
        // 这里只补一个「配置变了，去重读」的刷新信号。
        CfgBridge.push(ctx, TARGET_PKG);
    }""")
    print("OK: patchUlSidecar")

# ---- 4) patchSidecarKey ----
m = re.search(r"    private static void patchSidecarKey\(Context ctx, String key, String value\) \{.*?\n    \}\n", src, re.S)
if not m:
    print("MISS patchSidecarKey"); sys.exit(1)
src = src[:m.start()] + """    /**
     * 单键刷新（历史名字保留）。
     *
     * <p>以前是在侧车文件里替换单行；现在真源就是 prefs，provider 每次查询都重新构建，
     * 所以不需要增量写，只要发一个刷新信号让目标进程立刻重读。</p>
     */
    private static void patchSidecarKey(Context ctx, String key, String value) {
        CfgBridge.push(ctx, TARGET_PKG);
    }
""" + src[m.end():]
print("OK: patchSidecarKey")

# ---- 5) sidecarVal 改读 provider ----
m = re.search(r"    /\*\* 读配置里某个键的实际值（来源：Settings\.System 公共键） \*/\n    private static String sidecarVal\(Context ctx, String key\) \{.*?\n    \}\n", src, re.S)
if not m:
    print("MISS sidecarVal"); sys.exit(1)
src = src[:m.start()] + """    /** 读配置文本里某个键的实际值（来源：CfgProvider），仅供诊断日志 */
    private static String sidecarVal(Context ctx, String key) {
        String text = queryCfgProvider(ctx);
        if (text == null || text.length() == 0) {
            return "no-provider";
        }
        for (String line : text.split("\\n")) {
            String t = line.trim();
            if (t.startsWith(key + "=")) {
                return t.substring(key.length() + 1);
            }
        }
        return "key-missing";
    }
""" + src[m.end():]
print("OK: sidecarVal")

# ---- 6) 注释里过时的「三条通道」描述 ----
src = src.replace(
    """     * <p>这是配置的唯一序列化出口：{@code CfgProvider} 按需返回它、{@code CfgBridge}
     * 广播携带它、{@code Settings.System} 也写一份。三条通道必须内容一致，否则目标进程
     * 从不同通道读到不同的 cap 就会出现「数量对不上」。</p>""",
    """     * <p>这是配置的<b>唯一</b>序列化出口：{@code CfgProvider} 每次查询都调用它重新构建，
     * 不做任何缓存，所以目标进程读到的永远是设置页写入的真源值。{@code CfgBridge} 广播
     * 只承载「变了吗」这个信号，不携带内容。</p>""")
src = src.replace(
    """     * <p>目标进程拿不到 remote prefs（Xposed 以目标进程身份读模块私有目录必然失败），
     * 因此靠 Settings.System 公共键与 CfgBridge 广播下发，所以每个 {@code ul_<id>}
     * 都必须在这里有一行，否则规则永远是「未开启」。</p>""",
    """     * <p>目标进程（手机管家）以自身 uid 运行，读不到本模块 prefs，因此这份文本经
     * CfgProvider 下发。每个 {@code ul_<id>} 都必须在这里有一行，否则规则永远是「未开启」。</p>""")
src = src.replace(
    """        // 状态文件与配置通道无关：配置走 Settings.System 公共键，状态文件是""",
    """        // 状态文件与配置通道无关：配置走 CfgProvider，状态文件是""")

io.open(P, "w", encoding="utf-8").write(src)
print("done, bytes:", len(src))
