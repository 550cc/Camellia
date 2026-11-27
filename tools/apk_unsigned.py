#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从已签名 APK 剥离签名，产出真正未签名的完整 APK。

为什么需要这个：
  AGP 对 debug 变体总是注入默认 debug 签名（~/.android/debug.keystore），
  在 buildTypes 里把 signingConfig 置空/不设置都会回退到该默认签名，
  所以无法通过 Gradle 直接产出未签名 debug 包。

做法：
  用 zipfile 重建 ZIP —— 这样 API Signing Block（v2/v3，位于 EOCD 前的
  "APK Sig Block 42" 块）不会被复制；同时跳过 META-INF 下的 v1 签名文件
  （*.SF / *.RSA / *.DSA / *.EC / MANIFEST.MF）。
  条目内容、压缩方式、时间戳、unix 权限位全部原样保留。

重建会打乱 STORED 条目对齐，所以本脚本之后必须再跑一次 zipalign：
  zipalign -P 16 -f 4 unsigned.apk aligned.apk
  注意必须用 -P 16（16 KB 页对齐，Android 15+ / 16 KB 页设备要求），
  不能用 -p（那是 4 KB 旧语义，会让 lib*.so 落在非 16 KB 偏移上，
  安装器会报 "library ... not 16 KB aligned"）。

用法：
  python3 tools/apk_unsigned.py in.apk out-unsigned.apk
"""
import sys
import zipfile

SIG_EXTS = ('.SF', '.RSA', '.DSA', '.EC')


def is_v1_signature(name):
    """META-INF 下的 v1(JAR) 签名相关条目"""
    if not name.startswith('META-INF/'):
        return False
    if '/' in name[len('META-INF/'):]:
        return False          # META-INF/services/* 之类不是签名
    upper = name.upper()
    return upper.endswith(SIG_EXTS) or upper.endswith('MANIFEST.MF')


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    src, dst = sys.argv[1], sys.argv[2]

    skipped = []
    total = 0
    with zipfile.ZipFile(src, 'r') as zin, zipfile.ZipFile(dst, 'w') as zout:
        for it in zin.infolist():
            if it.is_dir():
                continue
            if is_v1_signature(it.filename):
                skipped.append(it.filename)
                continue
            zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
            zi.compress_type = it.compress_type
            zi.external_attr = it.external_attr
            zi.internal_attr = it.internal_attr
            zi.create_system = it.create_system
            zi.comment = it.comment
            zout.writestr(zi, zin.read(it))
            total += 1

    print('[apk_unsigned] 写入 {} 个条目'.format(total))
    if skipped:
        print('[apk_unsigned] 跳过 v1 签名条目: {}'.format(', '.join(skipped)))
    print('[apk_unsigned] v2/v3 签名块已随 ZIP 重建丢弃')
    print('[apk_unsigned] 注意：还需 zipalign -p -f 4 重新对齐')
    return 0


if __name__ == '__main__':
    sys.exit(main())
