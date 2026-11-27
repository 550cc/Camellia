#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修正 patch_v33b：去掉 native 侧的 start= 输出。

原因：native 的 dump 跑在 clone 出的子线程里，/proc/self/stat 指向的是线程自己，
其 starttime 是线程创建时刻；而 system_server 侧读 /proc/<pid>/stat 拿到的是
主线程（进程）的 starttime，两者必然不等 —— 留着会造成永久性的身份校验失败。
进程身份三元组改由 Java 侧自己算（同进程内读自身 /proc，权限与语义都正确）。
"""
import io, sys

P = 'jni/homeprobe/homeprobe.c'
src = io.open(P, encoding='utf-8').read()

def must(cond, msg):
    if not cond:
        print('FAIL: ' + msg); sys.exit(1)
    print('ok: ' + msg)

# 1) 删输出
old = '''    o_str("\\nstart=");
    o_dec(read_starttime());
'''
must(old in src, 'start= 输出块定位')
src = src.replace(old, '', 1)

# 2) 删函数定义（从注释开始到函数结束）
start = src.find('/* v33: 读 /proc/self/stat 第 22 字段 starttime')
must(start >= 0, 'read_starttime 函数定位')
depth = 0
started = False
i = start
while i < len(src):
    if src[i] == '{':
        depth += 1
        started = True
    elif src[i] == '}':
        depth -= 1
        if started and depth == 0:
            i += 1
            break
    i += 1
must(started, 'read_starttime 函数体闭合')
end = i
# 吃掉尾部换行
while end < len(src) and src[end] == '\n':
    end += 1
removed = src[start:end].count('\n')
src = src[:start] + src[end:]
print('  删除 read_starttime 共 %d 行' % removed)

# 3) 残留检查
for sym in ['read_starttime', 'o_str("\\nstart=")']:
    must(src.count(sym) == 0, '残留 %s = %d' % (sym, src.count(sym)))

io.open(P, 'w', encoding='utf-8').write(src)
print('\n写出完成（%d 字节）' % len(src.encode('utf-8')))
