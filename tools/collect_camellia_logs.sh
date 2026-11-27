#!/system/bin/sh
# ============================================================
# Camellia 急救诊断包一键收集（Android 原生环境 / root 执行）
#
# 用法：
#   su -c 'sh /sdcard/projects/misharemt2/tools/collect_camellia_logs.sh'
# 产物：
#   /sdcard/camellia-diag-<时间戳>.tar.gz
#
# 设计要点：
#   - 不在 DE（未解锁）阶段假装成功：pm/dumpsys 不可用时只记错，不中断
#   - 排除 bak/*/base.apk（单包 30~52MB，会把包撑爆），只留 meta 与清单
#   - dropbox 崩溃原文每份只截前 8KB（够看清 Process/异常/首个业务帧）
#   - 全流程不写入 /data/adb/camellia，只读，避免污染现场
# ============================================================
TS=$(date +%Y%m%d-%H%M%S 2>/dev/null || echo unknown)
STAGE=/sdcard/camellia-diag-$TS
TARBALL=/sdcard/camellia-diag-$TS.tar.gz
C=/data/adb/camellia

rm -rf "$STAGE"
mkdir -p "$STAGE/state" "$STAGE/dropbox" "$STAGE/bak_meta" 2>/dev/null

# ---------- 1. camd 配置与状态 ----------
cp "$C/config"      "$STAGE/config"      2>/dev/null
cp "$C/camd.sig"    "$STAGE/camd.sig"    2>/dev/null
cp "$C/camd.log"    "$STAGE/camd.log"    2>/dev/null
cp "$C/state/"*     "$STAGE/state/"      2>/dev/null
cp /data/system/camellia_plugin_state "$STAGE/camellia_plugin_state" 2>/dev/null

# ---------- 2. 快照目录：清单 + meta，不含 base.apk ----------
ls -l "$C/bak" > "$STAGE/bak_list.txt" 2>/dev/null
for d in "$C"/bak/*/; do
    [ -d "$d" ] || continue
    n=$(basename "$d")
    {
        echo "===== $n ====="
        ls -l "$d" 2>/dev/null
        [ -f "$d/meta" ] && cat "$d/meta" 2>/dev/null
        echo
    } >> "$STAGE/bak_meta/all_meta.txt" 2>/dev/null
done

# ---------- 3. 系统崩溃原文（截断 8KB/份） ----------
for f in /data/system/dropbox/*crash* /data/system/dropbox/*watchdog* /data/system/dropbox/*anr*; do
    [ -f "$f" ] || continue
    head -c 8192 "$f" > "$STAGE/dropbox/$(basename "$f")" 2>/dev/null
done

# ---------- 4. 环境信息（逐项容错） ----------
{
    echo "===== 采集时间 ====="; date 2>/dev/null
    echo "===== 内核 =====";    uname -a 2>/dev/null
    echo "===== 机型/版本 ====="
    for p in ro.product.model ro.product.brand ro.build.version.release \
             ro.build.version.sdk ro.miui.ui.version.name ro.build.version.incremental \
             ro.build.fingerprint; do
        v=$(getprop $p 2>/dev/null); echo "$p=$v"
    done
    echo "===== camd 进程 ====="
    ps -A 2>/dev/null | grep -i camd
    echo "===== camd 二进制指纹 ====="
    for f in /data/adb/camellia/camd /data/data/com.hyper.Camellia/files/camd /dev/xwwoah_i/.x/camd; do
        [ -f "$f" ] && { printf '%s  ' "$f"; md5sum "$f" 2>/dev/null; }
    done
    echo "===== 关键包路径与版本 ====="
    for p in com.android.systemui miui.systemui.plugin com.hyper.Camellia; do
        echo "--- $p"
        pm path $p 2>&1
        dumpsys package $p 2>/dev/null | grep -E 'versionCode|versionName|codePath|lastUpdateTime' | head -6
    done
    echo "===== 覆盖层判定（packages.xml 中 systemui/plugin 是否在 /data/app）====="
    grep -aoE '<package name="(com.android.systemui|miui.systemui.plugin)"[^>]*codePath="[^"]*"' \
        /data/system/packages.xml 2>/dev/null | head -10
    echo "===== 解密状态 ====="
    getprop ro.crypto.state 2>/dev/null
} > "$STAGE/env.txt" 2>&1

# ---------- 5. logcat 相关行 ----------
logcat -d -v threadtime 2>/dev/null \
    | grep -iE 'camellia|camd|rescue|miui\.systemui\.plugin|UnsatisfiedLinkError' \
    | tail -n 3000 > "$STAGE/logcat_filtered.txt" 2>/dev/null

# ---------- 6. 打包 ----------
cd /sdcard 2>/dev/null
tar -czf "$TARBALL" "camellia-diag-$TS" 2>/dev/null
if [ -s "$TARBALL" ]; then
    rm -rf "$STAGE"
    echo "OK 诊断包已生成: $TARBALL"
    ls -l "$TARBALL" 2>/dev/null
else
    echo "WARN tar 打包失败，原始目录保留: $STAGE"
fi
