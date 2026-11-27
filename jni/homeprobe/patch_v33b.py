#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v33 一次性判定改造 —— native 侧第二步：给状态信标标注进程身份。

为什么不靠 mtime 新鲜度：老方案靠「心跳 mtime < 120s」推断 A 是否还活着，
代价是每天 86400 次线程唤醒去续命，而且判错方向（心跳停 = A 失效）时
还要延迟 120s 才能发现。改用 pid + starttime 精确标注「写这份状态的是哪个进程」：
system_server 读 /proc/<pid>/stat 比对 starttime，一次读定生死，不需要心跳。
boot_id 用于让旧开机的残留信标立即作废。
"""
import io, sys, shutil

P = 'jni/homeprobe/homeprobe.c'
src = io.open(P, encoding='utf-8').read()

def must(cond, msg):
    if not cond:
        print('FAIL: ' + msg); sys.exit(1)
    print('ok: ' + msg)

shutil.copy(P, P + '.bak_v33a')

# ---------- 1) 前向声明 s_getpid ----------
anchor = '''static long s_mkdir(const char *p, int mode) {
    return s_sys(34, AT_FDCWD, (long)p, (long)mode, 0, 0, 0);   /* mkdirat */
}
'''
must(anchor in src, 's_mkdir 锚点定位')
add = anchor + '''static long s_getpid(void);   /* 定义在文件后部，此处前向声明供 dump 使用 */

/* v33: 读 /proc/self/stat 第 22 字段 starttime（自开机以来的 jiffies）。
 * 用途：给状态信标标注「写这份状态的进程身份」。system_server 侧读
 * /proc/<pid>/stat 比对同一个值，就能确认"打补丁的那个进程还活着"，
 * 而不是靠心跳 mtime 去猜。 */
static u64 read_starttime(void) {
    char b[512];
    long n, i, k = -1, fd;
    int field = 0;
    fd = s_open("/proc/self/stat", 0, 0);
    if (fd < 0) return 0;
    n = s_sys(63, fd, (long)b, (long)(sizeof(b) - 1), 0, 0, 0);
    s_sys(57, fd, 0, 0, 0, 0, 0);
    if (n <= 0) return 0;
    b[n] = 0;
    /* comm 字段可能含空格与括号，必须以最后一个 ')' 为界 */
    for (i = 0; i < n; i++) if (b[i] == ')') k = i;
    if (k < 0) return 0;
    i = k + 1;
    for (;;) {
        u64 v = 0;
        int i2;
        while (i < n && b[i] == ' ') i++;
        if (i >= n) break;
        field++;
        if (field == 20) {
            for (i2 = 0; i < n && b[i] >= '0' && b[i] <= '9'; i++)
                v = v * 10 + (u64)(b[i] - '0');
            return v;
        }
        while (i < n && b[i] != ' ') i++;
    }
    return 0;
}

/* v33: boot_id 前 8 个字符（UUID 的 hex 前缀，足以区分两次开机）。
 * 系统重启后旧信标立即作废，免得「上次开机写的 OK」被当成本次的有效状态。 */
static void read_boot_id(char *out) {
    long fd, n;
    int i;
    for (i = 0; i < 8; i++) out[i] = '0';
    out[8] = 0;
    fd = s_open("/proc/sys/kernel/random/boot_id", 0, 0);
    if (fd < 0) return;
    n = s_sys(63, fd, (long)out, 8, 0, 0, 0);
    s_sys(57, fd, 0, 0, 0, 0, 0);
    if (n < 8) {
        for (i = 0; i < 8; i++) out[i] = '0';
        out[8] = 0;
    }
}
'''
src = src.replace(anchor, add, 1)

# ---------- 2) 结果段补 pid/start/boot ----------
old = '''    o_str("\\nelf_off=");
    o_hex64(g_elf_off);
    o_ch('\\n');
    o_flush();
'''
new = '''    o_str("\\nelf_off=");
    o_hex64(g_elf_off);
    /* v33: 进程身份三元组。写一次即可 —— 桌面进程活着这些值就有效，
     * 进程重启会重新跑 native_init 并覆盖本文件，所以不需要心跳续期。 */
    o_str("\\npid=");
    o_dec((u64)s_getpid());
    o_str("\\nstart=");
    o_dec(read_starttime());
    o_str("\\nboot=");
    {
        char bid[9];
        read_boot_id(bid);
        o_str(bid);
    }
    o_ch('\\n');
    o_flush();
'''
must(old in src, '结果段尾部锚点定位')
src = src.replace(old, new, 1)

# ---------- 3) u64 类型确认 ----------
must('typedef unsigned long long u64' in src or 'typedef unsigned long u64' in src
     or 'u64;' in src, 'u64 类型已定义')

io.open(P, 'w', encoding='utf-8').write(src)
print('\n写出完成（%d 字节）' % len(src.encode('utf-8')))
