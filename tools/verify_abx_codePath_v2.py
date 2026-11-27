#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ABX 二进制提取安装目录 v2
# 关键修正:
#   1. 字符串头 = 2f | uint16 A | uint16 len | 内容  (len 精确, 不靠字符扫描猜边界)
#   2. 分两类评估:
#      A) codePath 在 /data/app/ 下 -> 路径本身含包名, 可 100% 判定归属
#      B) codePath 在系统分区/apex 下 -> 路径不含包名, 需邻域关联(可能失败)
# 用法: python3 verify_abx_codePath_v2.py <packages.xml> <packages_decoded.xml>
import re
import sys
import time

BIN = sys.argv[1] if len(sys.argv) > 1 else '/sdcard/codeforge/tmp/abxtest/packages.xml'
TXT = sys.argv[2] if len(sys.argv) > 2 else '/sdcard/codeforge/tmp/abxtest/packages_decoded.xml'

PATHCHARS = set(b'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.+/=~-@')
PREFIXES = (b'/data/app/', b'/data/priv-app/', b'/data/preloads/', b'/system_ext/',
            b'/system/', b'/product/', b'/vendor/', b'/odm/', b'/oem/', b'/apex/',
            b'/cust/', b'/mnt/')


def load(p):
    with open(p, 'rb') as f:
        return f.read()


def parse_truth(txt):
    truth = {}
    for m in re.finditer(r'<package\s+name="([^"]+)"([^>]*)>', txt):
        name, attrs = m.group(1), m.group(2)
        cm = re.search(r'codePath="([^"]*)"', attrs)
        if cm and name not in truth:
            truth[name] = cm.group(1)
    return truth


def extract_strings(data):
    """按 2f|uint16A|uint16len 头精确切分所有字符串, 返回 [(offset, bytes)]"""
    out = []
    n = len(data)
    i = 0
    while i + 5 < n:
        if data[i] == 0x2F:
            ln = (data[i + 3] << 8) | data[i + 4]
            if 1 <= ln <= 2048 and i + 5 + ln <= n:
                s = data[i + 5:i + 5 + ln]
                if all(b in PATHCHARS for b in s):
                    out.append((i + 5, s))
                    i += 5 + ln
                    continue
        i += 1
    return out


def main():
    t0 = time.time()
    data = load(BIN)
    truth = parse_truth(load(TXT).decode('utf-8', 'replace'))
    print("文件 %d 字节, 基准 %d 个包\n" % (len(data), len(truth)))

    t1 = time.time()
    strings = extract_strings(data)
    t2 = time.time()
    paths = [(o, s) for o, s in strings if s.startswith(PREFIXES)]
    print("头驱动提取: 字符串 %d 条, 其中路径 %d 条, 耗时 %d ms"
          % (len(strings), len(paths), int((t2 - t1) * 1000)))

    data_app = [(o, s) for o, s in paths if s.startswith(b'/data/app/')]

    # --- A 类: /data/app 路径含包名 -> 精确归属 ---
    a_total = a_ok = 0
    a_fail = []
    t3 = time.time()
    for name, want in truth.items():
        if not want.startswith('/data/app/'):
            continue
        a_total += 1
        key = ('/' + name + '-').encode()
        found = [s for o, s in data_app if key in s]
        if found and found[0].decode('latin1').rstrip('/') == want.rstrip('/'):
            a_ok += 1
        else:
            a_fail.append((name, want, found[0].decode('latin1') if found else None))
    t4 = time.time()

    # --- B 类: 系统分区路径 -> 邻域关联 ---
    def near_path(name):
        poss = [m.start() for m in re.finditer(re.escape(name.encode()), data)]
        best = None
        for p in poss:
            lo, hi = max(0, p - 256), min(len(data), p + 256)
            for o, s in paths:
                if lo <= o <= hi:
                    d = abs(o - p)
                    if best is None or d < best[0]:
                        best = (d, s.decode('latin1'))
        return best

    b_total = b_ok = 0
    b_fail = []
    for name, want in truth.items():
        if want.startswith('/data/app/'):
            continue
        b_total += 1
        r = near_path(name)
        if r and r[1].rstrip('/') == want.rstrip('/'):
            b_ok += 1
        else:
            b_fail.append((name, want, r[1] if r else None))

    print("=" * 74)
    print("[A类] codePath 在 /data/app/ 下 (路径含包名, 可精确归属)")
    print("      %d/%d 命中 (%.2f%%)  耗时 %d ms"
          % (a_ok, a_total, a_ok * 100.0 / max(a_total, 1), int((t4 - t3) * 1000)))
    if a_fail:
        print("      失败 %d 个: %s" % (len(a_fail), [x[0] for x in a_fail[:5]]))
    print()
    print("[B类] codePath 在系统分区/apex 下 (路径不含包名, 需邻域关联)")
    print("      %d/%d 命中 (%.2f%%)"
          % (b_ok, b_total, b_ok * 100.0 / max(b_total, 1)))
    if b_fail:
        print("      失败样本:")
        for n, w, g in b_fail[:6]:
            print("        %s\n          期望 %s\n          实得 %s" % (n, w, g))
    print("=" * 74)

    # 重点包
    print("\n★ 重点包验证")
    for sp in ('tv.danmaku.bili', 'com.android.systemui', 'miui.systemui.plugin'):
        if sp not in truth:
            continue
        want = truth[sp]
        # 方法1: 路径含包名
        key = ('/' + sp + '-').encode()
        hit = [s for o, s in data_app if key in s]
        r1 = hit[0].decode('latin1') if hit else None
        # 方法2: 邻域
        r2 = near_path(sp)
        print("  %s" % sp)
        print("    基准(abx2xml)   : %s" % want)
        print("    二进制/路径含包名: %s  %s"
              % (r1, '✅' if r1 and r1.rstrip('/') == want.rstrip('/') else '—'))
        print("    二进制/邻域关联  : %s  (距离 %s)  %s"
              % (r2[1] if r2 else None, r2[0] if r2 else '-',
                 '✅' if r2 and r2[1].rstrip('/') == want.rstrip('/') else '❌'))
    print("\n总耗时 %d ms" % int((time.time() - t0) * 1000))


if __name__ == '__main__':
    main()
