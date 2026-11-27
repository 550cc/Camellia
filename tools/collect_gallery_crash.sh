#!/system/bin/sh
# ============================================================
# collect_gallery_crash.sh
#   专项诊断包采集：「点击模块界面里的『小米画报』入口 → 闪退」
#
# 适用：任何一台已 root 的小米/澎湃设备（原厂 pm/dumpsys，不依赖 busybox）
#
# 用法（在【出问题的那台手机】上，root 终端执行）：
#   1) 先复现：打开 Camellia 模块设置 → 点「小米画报」卡片，等闪退
#   2) 立刻执行（间隔越短越好，dropbox/logcat 都是滚动缓冲）：
#        su
#        sh /sdcard/collect_gallery_crash.sh
#
# 产物：
#   /sdcard/gallery-crash-<时间戳>.tar.gz      ← 发回来给我
#   /sdcard/gallery-crash-<时间戳>/SUMMARY.txt ← 现场直接看结论
#
# 设计要点：
#   - dropbox 按【包名内容】过滤，只收 Camellia / 画报 / SystemUI 相关，
#     每份截前 16KB（够看 Process + 异常首行 + Caused by 链 + 首个业务帧）
#   - 直接判定残缺包特征：dex 里是否真的有 SettingsActivity 类
#   - 全程只读，不写 /data/adb/camellia，不污染现场
# ============================================================

TS=$(date +%Y%m%d-%H%M%S 2>/dev/null || echo unknown)
STAGE=/sdcard/gallery-crash-$TS
TARBALL=/sdcard/gallery-crash-$TS.tar.gz
SUMMARY=$STAGE/SUMMARY.txt
C=/data/adb/camellia
PKG_MOD=com.hyper.Camellia
PKG_GAL=com.mfashiongallery.emag
PKG_SYS=com.android.systemui

rm -rf "$STAGE"
mkdir -p "$STAGE/dropbox" "$STAGE/logcat" "$STAGE/lspd" "$STAGE/tombstones" 2>/dev/null

# ---------------- 0. 摘要头 ----------------
{
    echo "================ 采集信息 ================"
    echo "采集时间   : $(date 2>/dev/null)"
    echo "设备型号   : $(getprop ro.product.model 2>/dev/null)"
    echo "系统版本   : $(getprop ro.build.version.release 2>/dev/null) / SDK $(getprop ro.build.version.sdk 2>/dev/null)"
    echo "MIUI/Hyper : $(getprop ro.miui.ui.version.name 2>/dev/null) $(getprop ro.build.version.incremental 2>/dev/null)"
    echo "ro.crypto  : $(getprop ro.crypto.state 2>/dev/null)"
    echo
    echo "================ 三包现场 ================"
    for p in $PKG_MOD $PKG_GAL $PKG_SYS; do
        echo "--- $p"
        echo "    path : $(pm path $p 2>&1 | head -3 | tr '\n' ' ')"
        dumpsys package $p 2>/dev/null \
            | grep -E 'versionCode=|versionName=|codePath=|lastUpdateTime=|firstInstallTime=' \
            | head -6 | sed 's/^/    /'
    done
    echo
    echo "================ 进程 ================"
    ps -A 2>/dev/null | grep -iE 'Camellia|mfashion|camd' | sed 's/^/  /'
    echo
} > "$SUMMARY" 2>&1

# ---------------- 1. 残缺包检测（dex 里是否真有 SettingsActivity） ----------------
{
    echo "================ 完整性检测 ================"
    APK=$(pm path $PKG_MOD 2>/dev/null | grep -m1 'base.apk' | sed 's/^package://')
    echo "base.apk = ${APK:-<未取到>}"
    if [ -n "$APK" ] && [ -f "$APK" ]; then
        echo "size     = $(ls -l "$APK" 2>/dev/null | awk '{print $5}') bytes"
        # manifest 侧：SettingsActivity 是否被声明
        if dumpsys package $PKG_MOD 2>/dev/null | grep -q "SettingsActivity"; then
            echo "manifest : 已声明 SettingsActivity"
        else
            echo "manifest : 未声明 SettingsActivity  ← 异常"
        fi
        # dex 侧：逐个 classes*.dex 查类描述符（SettingsActivity 通常不在 classes.dex）
        UNZ=""
        if command -v unzip >/dev/null 2>&1; then
            UNZ=unzip
        elif [ -x /data/adb/magisk/busybox ]; then
            UNZ="/data/adb/magisk/busybox unzip"
        fi
        if [ -n "$UNZ" ]; then
            FOUND=""
            for dx in $($UNZ -l "$APK" 2>/dev/null | awk '/classes.*\.dex/{print $NF}'); do
                $UNZ -p "$APK" "$dx" 2>/dev/null | grep -q "Camellia/SettingsActivity" && FOUND="$FOUND $dx"
            done
            if [ -n "$FOUND" ]; then
                echo "dex      : 命中于$FOUND  → 类存在，包完整"
            else
                echo "dex      : 全部 dex 均未命中 → 类不存在 = 残缺包（build.sh 产物）！"
            fi
            $UNZ -l "$APK" 2>/dev/null | grep -c 'classes.*\.dex' | sed 's/^/dex 数量 : /'
        else
            echo "dex      : UNKNOWN（无 unzip；以下方 dropbox 是否出现 ClassNotFoundException 为准）"
        fi
    fi
    echo
} >> "$SUMMARY" 2>&1

# ---------------- 2. dropbox：按包名过滤 ----------------
{
    echo "================ dropbox 崩溃（近 3 天，按包名过滤）================"
    HIT=0
    SYS_HITS=$STAGE/.sysui_hits
    : > "$SYS_HITS" 2>/dev/null
    for f in $(find /data/system/dropbox -maxdepth 1 -type f \
                 \( -name '*crash*' -o -name '*anr*' -o -name '*watchdog*' \) \
                 -mtime -3 2>/dev/null | sort -t@ -k2 -rn | head -150); do
        [ -f "$f" ] || continue
        HITK=""
        grep -q "$PKG_MOD" "$f" 2>/dev/null && HITK="$HITK [Camellia]"
        grep -q "$PKG_GAL" "$f" 2>/dev/null && HITK="$HITK [画报]"
        grep -q "$PKG_SYS" "$f" 2>/dev/null && HITK="$HITK [SystemUI]"
        [ -n "$HITK" ] || continue
        HIT=$((HIT+1))
        b=$(basename "$f")
        head -c 16384 "$f" > "$STAGE/dropbox/$b" 2>/dev/null
        if [ "$HITK" = " [SystemUI]" ]; then
            # SystemUI 崩溃量大：原文照样落盘，摘要末尾聚合，避免淹没关键证据
            {
                grep -m1 '^Timestamp:' "$f" 2>/dev/null | sed 's/^Timestamp: //'
                grep -m1 -E '^(java\.|android\.|Caused by)' "$f" 2>/dev/null | cut -c1-120
            } | tr '\n' '|' >> "$SYS_HITS" 2>/dev/null
            echo >> "$SYS_HITS" 2>/dev/null
        else
            {
                echo "----- $b $HITK"
                grep -E '^(Process|Package|PID|UID|Foreground|Timestamp):' "$f" 2>/dev/null | head -6 | sed 's/^/    /'
                echo "    [异常首行]"
                grep -m2 -E '^(java\.|android\.|kotlin\.|dalvik\.|com\.hyper|Caused by)' "$f" 2>/dev/null | cut -c1-300 | sed 's/^/      /'
                echo "    [Caused by 链]"
                grep -m4 'Caused by' "$f" 2>/dev/null | cut -c1-300 | sed 's/^/      /'
            } >> "$SUMMARY" 2>&1
        fi
    done
    # SystemUI 聚合收尾
    if [ -s "$SYS_HITS" ]; then
        SYS_N=$(grep -c . "$SYS_HITS" 2>/dev/null)
        {
            echo "----- [SystemUI] 共 ${SYS_N:-0} 次（噪音聚合；原文见 dropbox/ 目录）"
            echo "    时间范围: $(head -1 "$SYS_HITS" 2>/dev/null | cut -d'|' -f1) ... $(tail -1 "$SYS_HITS" 2>/dev/null | cut -d'|' -f1)"
            echo "    Top 原因:"
            cut -d'|' -f2 "$SYS_HITS" 2>/dev/null | sed 's/^ *//' | sort | uniq -c | sort -rn | head -4 | sed 's/^/      /'
        } >> "$SUMMARY" 2>&1
    fi
    [ "$HIT" = "0" ] && echo "  （近 3 天没有 Camellia/画报/SystemUI 的崩溃记录）" >> "$SUMMARY"
    echo >> "$SUMMARY"
} >> "$SUMMARY" 2>&1

# ---------------- 3. logcat ----------------
logcat -d -b crash -v threadtime 2>/dev/null | tail -c 400000 > "$STAGE/logcat/crash_buf.txt" 2>/dev/null
logcat -d -v threadtime 2>/dev/null \
    | grep -iE 'camellia|mfashion|Xposed|LSPosed|AndroidRuntime|FATAL EXCEPTION|ClassNotFoundException|Unable to instantiate' \
    | tail -n 6000 > "$STAGE/logcat/filtered.txt" 2>/dev/null

{
    echo "================ logcat crash 缓冲（尾部 60 行）================"
    tail -60 "$STAGE/logcat/crash_buf.txt" 2>/dev/null
    echo
} >> "$SUMMARY" 2>&1

# ---------------- 4. LSPosed：日志 + 作用域 ----------------
{
    echo "================ LSPosed ================"
    if [ -d /data/adb/lspd ]; then
        echo "lspd 目录存在"
        # 作用域数据库（含「模块↔画报」是否勾选）
        cp /data/adb/lspd/config/modules_config.db      "$STAGE/lspd/" 2>/dev/null
        cp /data/adb/lspd/config/modules_config.db-wal  "$STAGE/lspd/" 2>/dev/null
        # 最近日志
        for lf in $(find /data/adb/lspd/log -maxdepth 2 -type f -mtime -3 2>/dev/null | head -6); do
            tail -c 120000 "$lf" > "$STAGE/lspd/$(basename "$lf")" 2>/dev/null
        done
        ls -l /data/adb/lspd/log 2>/dev/null | tail -8 | sed 's/^/  /'
        # 若系统自带 sqlite3 则直接查作用域
        if command -v sqlite3 >/dev/null 2>&1; then
            echo "--- scope（sqlite3）"
            sqlite3 /data/adb/lspd/config/modules_config.db \
                "select * from scope where app_pkg_name in ('$PKG_GAL','$PKG_MOD');" 2>&1 | sed 's/^/  /'
        else
            echo "--- 无 sqlite3，已把 modules_config.db 打包（我可离线解析作用域）"
        fi
    else
        echo "未找到 /data/adb/lspd（非 LSPosed 环境？）"
    fi
    echo
} >> "$SUMMARY" 2>&1

# ---------------- 5. tombstone / ANR ----------------
for t in $(ls -t /data/tombstones 2>/dev/null | head -3); do
    [ -f "/data/tombstones/$t" ] && head -c 200000 "/data/tombstones/$t" > "$STAGE/tombstones/$t" 2>/dev/null
done
if [ -d /data/anr ]; then
    for a in $(ls -t /data/anr 2>/dev/null | head -2); do
        [ -f "/data/anr/$a" ] && head -c 200000 "/data/anr/$a" > "$STAGE/tombstones/anr_$a" 2>/dev/null
    done
fi
{
    echo "================ tombstone / ANR ================"
    ls -lt /data/tombstones 2>/dev/null | head -5 | sed 's/^/  /'
    ls -lt /data/anr 2>/dev/null | head -5 | sed 's/^/  /'
    echo
} >> "$SUMMARY" 2>&1

# ---------------- 6. camd 现场（模块的 root 侧，闪退排查有时相关） ----------------
{
    echo "================ camd 现场 ================"
    echo "--- state 文件"
    for f in "$C"/state/*; do
        [ -f "$f" ] || continue
        echo "  $(basename "$f") = $(head -c 200 "$f" 2>/dev/null | tr '\n' ' ')"
    done
    echo "--- camd 二进制指纹"
    for f in "$C/camd" "/data/data/$PKG_MOD/files/camd" /dev/xwwoah_i/.x/camd /dev/tsiltdid/.x/camd; do
        [ -f "$f" ] && { printf '  %s  ' "$f"; md5sum "$f" 2>/dev/null | awk '{print $1}'; }
    done
    echo "--- camd.log 尾部 40 行"
    tail -40 "$C/camd.log" 2>/dev/null | sed 's/^/  /'
    cp "$C/config" "$STAGE/camd_config" 2>/dev/null
    echo
} >> "$SUMMARY" 2>&1

# ---------------- 7. 打包 ----------------
cd /sdcard 2>/dev/null
tar -czf "$TARBALL" "gallery-crash-$TS" 2>/dev/null

echo "=============================================="
if [ -s "$TARBALL" ]; then
    echo "OK  诊断包已生成：$TARBALL"
    ls -l "$TARBALL" 2>/dev/null
else
    echo "WARN tar 失败，原始目录仍可用：$STAGE"
fi
echo "----------------------------------------------"
echo "【关键判定】"
CN=$(grep -cF ' [Camellia]' "$SUMMARY" 2>/dev/null)
GN=$(grep -cF ' [画报]' "$SUMMARY" 2>/dev/null)
echo "  Camellia 崩溃条目 : ${CN:-0}"
echo "  画报 emag 崩溃    : ${GN:-0}"
grep -m1 -F '[SystemUI] 共' "$SUMMARY" 2>/dev/null | sed 's/^/  /'
if grep -qF 'Camellia.SettingsActivity' "$SUMMARY" 2>/dev/null \
   && grep -qF 'ClassNotFoundException' "$SUMMARY" 2>/dev/null; then
    echo "  ★ 命中 ClassNotFoundException: Camellia.SettingsActivity"
    echo "    → 装机 APK 缺类（残缺包 / 降级装错包），复装完整 APK 即可"
fi
if grep -qF 'Unable to instantiate activity' "$SUMMARY" 2>/dev/null; then
    echo "  ★ 命中 Unable to instantiate activity（Activity 类缺失）"
fi
echo "----------------------------------------------"
echo "完整摘要：$SUMMARY"
echo "诊断包  ：$TARBALL"
echo "把上面那个 .tar.gz 发回来即可。"
