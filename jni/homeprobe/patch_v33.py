#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v33 一次性判定改造 —— native 侧。
1) 信标路径 Download -> Android/media/<pkg>/cam_a_state
2) 删除 1s 看护循环 / 30s 心跳 / 5min 兜底扫描（打补丁后写一次即退出）
3) 删除相关全局变量与宏
"""
import io, sys, os, shutil

P = 'jni/homeprobe/homeprobe.c'
src = io.open(P, encoding='utf-8').read()

def must(cond, msg):
    if not cond:
        print('FAIL: ' + msg); sys.exit(1)
    print('ok: ' + msg)

shutil.copy(P, P + '.bak_v32')

# ---------- 1) 重命名 ----------
n = src.count('g_out_download')
must(n == 4, 'g_out_download 出现 %d 次（预期 4）' % n)
src = src.replace('g_out_download', 'g_out_media')

# ---------- 2) build_paths 路径改造 ----------
old = '''    /* 公共下载目录：不受 Android/data 的 FUSE 属主隔离，system_server 侧最可能读到。
     * 文件名按包名区分，多目标互不覆盖（点号开头 → 文件管理器默认隐藏）。 */
    s_cpy(g_out_media, "/storage/emulated/0/Download/.cam_a_state_");
    s_cat(g_out_media, pkg);
'''
new = '''    /* v33: 状态信标改投 Android/media/<pkg>/cam_a_state。
     * 选它的唯一理由：桌面进程能写、system_server 能读，是两者唯一的交集。
     *   Android/data/<pkg>  -> FUSE 按包属主隔离，system_server 读不到（实测 MISS）
     *   Download/           -> 目录属主 media_rw 组、桌面不在组内，写不进去（实测 MISS）
     *   Android/media/<pkg> -> 目录属组 media_rw，桌面经 FUSE 以 MediaProvider 身份
     *                         落盘；system_server 在 media_rw(1023) 组内，直接读。
     * v33 起不再有心跳：本文件只在补丁完成时写一次，读侧一锤定音。 */
    s_cpy(g_out_media, "/storage/emulated/0/Android/media/");
    s_cat(g_out_media, pkg);
    s_cat(g_out_media, "/cam_a_state");
    {
        char d[384];
        s_cpy(d, "/storage/emulated/0/Android/media/");
        s_cat(d, pkg);
        s_mkdir(d, 0771);   /* 已存在返回 EEXIST，忽略即可 */
    }
'''
must(old in src, 'build_paths 旧路径块定位')
src = src.replace(old, new)

# ---------- 3) 删 WATCH_MS ----------
old = '''#define WATCH_MS 1000      /* v32: 补丁看护周期（ms）。500->1000：线程唤醒 17.3万->8.6万/天，
                            * 回滚自愈延迟 0.5s->1s；实测 repatch=0（补丁稳定），无感知差异 */
'''
must(old in src, 'WATCH_MS 宏定位')
src = src.replace(old, '')

# ---------- 4) 删 g_repatch / g_beat ----------
old = '''static int g_repatch;      /* v20: 看护期间检测到回滚并重打的次数 */
static int g_beat;         /* v31 (Camellia): 看护心跳计数，用于状态信标新鲜度 */
'''
must(old in src, 'g_repatch/g_beat 声明定位')
src = src.replace(old, '')

# ---------- 5) 注入 s_mkdir ----------
anchor = '''static long s_open(const char *p, int flags, int mode) {
    return s_sys(56, AT_FDCWD, (long)p, (long)flags, (long)mode, 0, 0);
}
'''
must(anchor in src, 's_open 锚点定位')
src = src.replace(anchor, anchor + '''static long s_mkdir(const char *p, int mode) {
    return s_sys(34, AT_FDCWD, (long)p, (long)mode, 0, 0, 0);   /* mkdirat */
}
''', 1)

# ---------- 6) 按花括号配对删除看护循环 ----------
lines = src.split('\n')
start = None
for k, ln in enumerate(lines):
    if 'v20: 长期看护，防 XYRE 内存清理回滚补丁' in ln:
        start = k
        break
must(start is not None, '看护循环注释定位')

depth = 0
started = False
end = start
while end < len(lines):
    depth += lines[end].count('{') - lines[end].count('}')
    if lines[end].count('{') > 0:
        started = True
    if started and depth <= 0:
        break
    end += 1
must(started and depth <= 0, '看护循环块闭合定位')
print('  看护循环范围: 第 %d - %d 行（共 %d 行）' % (start + 1, end + 1, end - start + 1))
print('  首行: ' + lines[start].strip())
print('  末行: ' + lines[end].strip())
must(lines[end].strip() == '}', '块末行应为单独的 }')
del lines[start:end + 1]

src = '\n'.join(lines)

# ---------- 7) 残留检查 ----------
for sym in ['g_repatch', 'g_beat', 'WATCH_MS', 'for (;;)']:
    cnt = src.count(sym)
    must(cnt == 0, '残留 %s = %d 处' % (sym, cnt))

io.open(P, 'w', encoding='utf-8').write(src)
print('\n写出完成: %s（%d -> %d 字节）' % (P, os.path.getsize(P + '.bak_v32'), len(src.encode('utf-8'))))

# ---------- 8) 报告未引用函数 ----------
for fn in ['addr_in_rx', 'addr_has_prologue', 'scan_maps_once']:
    print('  %s 出现次数: %d' % (fn, src.count(fn)))
