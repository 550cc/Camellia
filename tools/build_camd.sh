#!/usr/bin/env bash
# 编译 root 守护进程 camd 并放入 assets/camd.bin
# 静态链接：proot 里 gcc 产出的动态 ELF 依赖 rootfs 的 loader/libc，手机上跑不起来
set -e
cd "$(dirname "$0")/.."
mkdir -p assets .camd-tmp

# camd 版本号从 build.gradle 的 versionName 取，保证「面板显示的 camd 版本」
# 与 APK 版本同源。写死在 C 里必然漂移：改了 App 忘了改 camd，面板就会
# 报出一个看起来很正常的旧版本号，比不显示更难查。
VER=$(sed -n "s/^[[:space:]]*versionName[[:space:]]*'\([^']*\)'.*/\1/p" build.gradle | head -1)
[ -z "$VER" ] && VER=unknown

# -Wno-format-truncation：所有路径 buffer 都由 snprintf 有界写入（不会溢出），
# 而实际路径（/data/adb/camellia、/data/app/~~xx==/pkg-xx==）远小于 buffer。
# gcc 的这项告警按「理论最大源长度」推算，会随 buffer 放大而自我循环，故关闭。
# CAMD_DEBUG=1 时打开 UIDUMP_DBG：ui_dump 各阶段做 /proc/self/io 计数，
# 用于排查读量来源。生产构建务必留空（每次 ui_dump 多 11 次 /proc 访问）。
EXTRA=""
if [ -n "$CAMD_DEBUG" ]; then EXTRA="-DUIDUMP_DBG"; fi
gcc -static -O2 -s -Wall -Wno-format-truncation -DCAMD_VER="\"$VER\"" \
    $EXTRA \
    -DSQLITE_THREADSAFE=0 -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_DQS=0 \
    -o .camd-tmp/camd \
    tools/camellia_bridge.c \
    tools/camellia_rescue.c \
    tools/camellia_sock.c \
    tools/camellia_dsnap.c \
    tools/sqlite3.c \
    -lpthread -ldl -lm -lbpf -lelf -lzstd -lz
# sdcard 是 Android FUSE，gcc 产物可能拿不到执行位（-rw-rw----），补回来，
# 否则本地 proot 测试时 chmod 也不认，只能复制到 /tmp 才能跑。
chmod 0755 .camd-tmp/camd
gzip -9 -c .camd-tmp/camd > assets/camd.bin
echo "camd: $(stat -c%s .camd-tmp/camd) B (ver=$VER) -> assets/camd.bin $(stat -c%s assets/camd.bin) B"
