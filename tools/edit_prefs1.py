# -*- coding: utf-8 -*-
"""Prefs.java 单通道收敛：删除 Settings.System 通道，只留 CfgProvider。"""
import io, re, sys

P = "/sdcard/projects/misharemt2/src/com/hyper/Camellia/Prefs.java"
src = io.open(P, encoding="utf-8").read()
orig = src

def cut(start_marker, end_marker, new, label):
    global src
    i = src.find(start_marker)
    if i < 0:
        print("MISS start:", label); sys.exit(1)
    j = src.find(end_marker, i)
    if j < 0:
        print("MISS end:", label); sys.exit(1)
    j += len(end_marker)
    src = src[:i] + new + src[j:]
    print("OK:", label)

# ---------- 1) 整个跨进程通道块：Settings.System -> provider only ----------
NEW_BLOCK = '''    // ---------------- 跨进程配置（CfgProvider，唯一通道） ----------------

    /**
     * 跨进程配置通道：<b>只有 CfgProvider 这一条</b>。
     *
     * <pre>
     *   真源：本模块的 SharedPreferences（设置页写、模块进程读）
     *   通道：content://com.hyper.Camellia.cfg/cfg   (CfgProvider, exported, 无权限)
     *   信号：CfgBridge 广播 —— 不带配置内容，只表示「配置变了，去重读」
     * </pre>
     *
     * <p>历史通道全部删除，原因：</p>
     * <ul>
     *   <li>/sdcard 侧车文件 —— Android 13+ 分区存储，目标进程读不到别的 app 的
     *       Android/data|media，读 Download 里的 .txt 也被 MediaProvider 拒绝。</li>
     *   <li>Settings.System 公共键 —— 本机实测普通应用<b>写不进</b>自定义键
     *       （{@code You cannot keep your settings in the secure settings}），只有 root/shell
     *       能写，作为通道永远不可靠。</li>
     *   <li>Xposed remote preferences —— 模块 app 被冻结或 LSPosed 快照过期时内容滞后。</li>
     *   <li>画报进程内的 cfg.txt 快照 —— 又一份可写状态，偏离真源。</li>
     * </ul>
     *
     * <p>多通道并存时「谁是最新」无从判断，曾直接导致「cap 显示 10、实际只有 8」。
     * 所以这里只允许一条路。</p>
     */
    public static final String CFG_AUTHORITY = "com.hyper.Camellia.cfg";

    /** 最近一次读取命中的通道（provider / -），供状态文件诊断 */
    private static volatile String sLastChannel = "-";

    public static String lastCfgChannel() {
        return sLastChannel;
    }

    /**
     * 读取跨进程配置文本（唯一入口，所有目标进程都调它）。
     *
     * <p>纯 Binder 调用：不需要权限、不受分区存储限制、永远是真源实时值。
     * 查询失败返回 null，调用方退回各自默认值。</p>
     */
    public static String readCfgText(Context ctx) {
        String text = queryCfgProvider(ctx);
        sLastChannel = (text != null && text.length() > 0) ? "provider" : "-";
        return text;
    }

    /** 兼容旧调用点的名字，等价于 {@link #readCfgText(Context)} */
    public static String readCrossProcCfg(Context ctx) {
        return readCfgText(ctx);
    }

    /** 通过 CfgProvider 拉取配置文本（失败返回 null，不抛异常） */
    public static String queryCfgProvider(Context ctx) {
        if (ctx == null) {
            return null;
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    Uri.parse("content://" + CFG_AUTHORITY + "/cfg"),
                    null, null, null, null);
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            int idx = c.getColumnIndex("cfg");
            return idx < 0 ? null : c.getString(idx);
        } catch (Throwable t) {
            Log.println(Log.INFO, "MiShareMT2/Prefs", "cfg provider query failed: " + t);
            return null;
        } finally {
            try {
                if (c != null) {
                    c.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
'''
# 从 "// ---------------- 跨进程配置（Settings.System 公共键）" 到 warnSystemCfgOnce 结束
cut('    // ---------------- 跨进程配置（Settings.System 公共键） ----------------',
    '''        Log.println(Log.INFO, "MiShareMT2/Prefs",
                "system cfg unavailable (" + why + "); 目标进程改走 CfgProvider 拉取");
    }
''', NEW_BLOCK, "cross-proc block")

io.open(P, "w", encoding="utf-8").write(src)
print("bytes:", len(orig), "->", len(src))
