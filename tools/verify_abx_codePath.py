#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ABX 二进制直取安装目录 —— 全量可行性验证

目的：证明 camd 不需要 fork abx2xml、不需要完整解析 ABX 结构，
      仅靠「二进制里搜包名 + 邻域搜路径串」就能准确拿到 codePath。

判定链：
  1. 在 packages.xml 二进制里搜包名字节串，得到所有出现位置
  2. 对每个出现位置，在 ±WINDOW 字节邻域内搜以 / 开头的路径串
  3. 用「字符串前缀是 uint16 大端长度且等于串长」验证该串是独立字符串（非拼接产物）
  4. 取距离最近且通过长度验证的路径 = codePath
  5. 与 abx2xml 解码结果逐条对比

用法: python3 verify_abx_codePath.py <packages.xml> <packages_decoded.xml>
"""
import re
import sys
import time

BIN_PATH = sys.argv[1] if len(sys.argv) > 1 else '/sdcard/codeforge/tmp/abxtest/packages.xml'
TXT_PATH = sys.argv[2] if len(sys.argv) > 2 else '/sdcard/codeforge/tmp/abxtest/packages_decoded.xml'
WINDOW = 512

# 路径合法字符
PATHCHARS = set(b'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.+/=~-')

# 合法安装路径前缀（系统分区 / 数据分区 / apex / 挂载点）
PREFIXES = [
    b'/data/app/', b'/data/priv-app/', b'/data/preloads/',
    b'/system_ext/', b'/system/', b'/product/', b'/vendor/',
    b'/odm/', b'/oem/', b'/apex/', b'/cust/', b'/mnt/', b'/data/',
]


def load(p):
    with open(p, 'rb') as f:
        return f.read()


def parse_truth(txt):
    """从解码后的 XML 文本提取 name -> codePath 的基准答案"""
    truth = {}
    for m in re.finditer(r'<package\s+name="([^"]+)"([^>]*)>', txt):
        name = m.group(1)
        attrs = m.group(2)
        cm = re.search(r'codePath="([^"]*)"', attrs)
        if cm and name not in truth:
            truth[name] = cm.group(1)
    return truth


def len_prefix_matches(data, pos, slen):
    """验证 pos 处的字符串是否被 uint16 BE 长度前缀正确标记（独立字符串的特征）"""
    if pos >= 2:
        if ((data[pos - 2] << 8) | data[pos - 1]) == slen:
            return True
    if pos >= 1:
        if data[pos - 1] == slen and slen < 256:
            return True
    return False


def read_path_at(data, i):
    """从 i（指向 '/'）读一个路径串，返回 (str_bytes, end_pos)"""
    n = 0
    ln = len(data)
    while i + n < ln and data[i + n] in PATHCHARS:
        n += 1
    return data[i:i + n], i + n


def find_nearby_paths(data, center, window=WINDOW):
    """在 center 附近搜路径串，返回 [(距离, 字符串, 长度验证通过, 偏移)]"""
    lo = max(0, center - window)
    hi = min(len(data), center + window)
    out = []
    i = lo
    while i < hi:
        if data[i] == 0x2F:  # '/'
            hit = False
            for p in PREFIXES:
                if data[i:i + len(p)] == p:
                    hit = True
                    break
            if hit:
                s, end = read_path_at(data, i)
                if len(s) >= 8:
                    ok = len_prefix_matches(data, i, len(s))
                    out.append((abs(i - center), s.decode('latin1'), ok, i))
                i = max(end, i + 1)
                continue
        i += 1
    return out


def find_all(data, needle):
    """bytes.find 循环，返回所有出现位置"""
    out = []
    start = 0
    while True:
        p = data.find(needle, start)
        if p < 0:
            return out
        out.append(p)
        start = p + 1


def main():
    t0 = time.time()
    data = load(BIN_PATH)
    txt = load(TXT_PATH).decode('utf-8', 'replace')
    truth = parse_truth(txt)
    print(f"文件: {BIN_PATH}  {len(data)} 字节")
    print(f"基准答案(abx2xml 解码): {len(truth)} 个包带 codePath\n")

    hit = 0            # 提取成功且与基准一致
    hit_lenok = 0      # 其中通过长度前缀验证的
    miss = 0           # 邻域内没找到任何路径
    wrong = 0          # 找到了但与基准不一致
    wrong_list = []

    t1 = time.time()
    for name, want in truth.items():
        poss = find_all(data, name.encode('utf-8'))
        got = None
        got_ok = False
        best_lenok = None
        for p in poss:
            cands = find_nearby_paths(data, p)
            if not cands:
                continue
            # 优先取「长度验证通过」的最近候选
            ok_c = [c for c in cands if c[2]]
            pick = min(ok_c, key=lambda c: c[0]) if ok_c else min(cands, key=lambda c: c[0])
            if pick[2]:
                best_lenok = pick[1]
                if got is None:
                    got = pick[1]
                    got_ok = True
                break
            if got is None:
                got = pick[1]
        if got is None:
            miss += 1
            continue
        # 归一化：去掉尾部 '/' 再比
        g = got.rstrip('/')
        w = want.rstrip('/')
        if g == w:
            hit += 1
            if got_ok:
                hit_lenok += 1
        else:
            wrong += 1
            if len(wrong_list) < 8:
                wrong_list.append((name, w, g))
    t2 = time.time()

    total = len(truth)
    print("=" * 70)
    print(f"命中(与基准完全一致)  : {hit}/{total}  ({hit * 100.0 / max(total,1):.2f}%)")
    print(f"  其中长度前缀验证通过: {hit_lenok}")
    print(f"错误(找到但不一致)    : {wrong}")
    print(f"缺失(邻域内无路径)    : {miss}")
    print("=" * 70)
    if wrong_list:
        print("\n错误样本:")
        for n, w, g in wrong_list:
            print(f"  {n}\n    期望: {w}\n    实得: {g}")
    print(f"\n耗时: 解析+比对 {int((t2-t1)*1000)} ms, 总计 {int((time.time()-t0)*1000)} ms")
    print(f"平均每包 {(t2-t1)*1000.0/max(total,1):.2f} ms")

    # 重点验证
    for special in ('tv.danmaku.bili', 'com.android.systemui', 'miui.systemui.plugin'):
        if special in truth:
            want = truth[special]
            poss = find_all(data, special.encode())
            best = None
            for p in poss:
                cands = [c for c in find_nearby_paths(data, p) if c[2]]
                if cands:
                    c = min(cands, key=lambda x: x[0])
                    if best is None or c[0] < best[0]:
                        best = c
            print(f"\n★ {special}")
            print(f"    基准(abx2xml): {want}")
            print(f"    二进制直取  : {best[1] if best else '(未取到)'}"
                  f"{'  [长度验证OK, 距离 %d 字节]' % best[0] if best else ''}")
            print(f"    一致: {'✅' if best and best[1].rstrip('/') == want.rstrip('/') else '❌'}")


if __name__ == '__main__':
    main()
