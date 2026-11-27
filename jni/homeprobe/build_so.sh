#!/bin/sh
# MiHomeProbeLab 探针 so 编译脚本
# 约束（源码硬要求）：纯 syscall + nostdlib，零 NEEDED，只读目标内存
# host 是 aarch64 Linux（proot），gcc 直接产出 arm64-android 可用的 .so
#
# 输出位置：jni/arm64-v8a/ —— 与项目原有 libdexkit.so 同级。
# 注意：build.gradle 的 jniLibs.srcDirs=['jni'] 会【递归】扫描 jni 下所有
# arm64-v8a 目录。若把 so 输出到 jni/homeprobe/arm64-v8a/，就会出现
# 「2 files found with path 'lib/arm64-v8a/libhomeprobe.so'」导致
# mergeDebugNativeLibs 失败。所以这里固定输出到 ../arm64-v8a/（即 jni/arm64-v8a/）。
set -e
cd "$(dirname "$0")"
OUT=../arm64-v8a
mkdir -p "$OUT"
gcc -shared -fPIC -nostdlib -O2 -fno-builtin -ffreestanding \
    -Wl,--version-script=exports.map \
    -o "$OUT/libhomeprobe.so" homeprobe.c
echo "built: $(cd "$OUT" && pwd)/libhomeprobe.so"
ls -la "$OUT/libhomeprobe.so"
readelf -d "$OUT/libhomeprobe.so" | grep NEEDED || echo "NEEDED: 无（自包含 OK）"
readelf -sW "$OUT/libhomeprobe.so" | grep -E "native_init|JNI_OnLoad" | grep GLOBAL | head -3
