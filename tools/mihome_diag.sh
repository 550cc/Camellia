#!/system/bin/sh
# ============================================================
#  MiHome 小窗补丁探针 v25 —— 一键诊断脚本 (rev3 / 定稿)
#
#  用途：在目标机上收集判断「补丁是否生效 / 为何失效」所需的全部信息。
#  需要 root。
#
#  用法（推荐，自动重启桌面并实时抓日志）：
#     su -c "sh /data/local/tmp/mihome_diag.sh --reload"
#
#  用法（仅采集，不动桌面）：
#     su -c "sh /data/local/tmp/mihome_diag.sh"
#
#  输出：屏幕 + /data/local/tmp/mihome_diag_<时间>.txt
#        把该 txt 完整内容发回来即可。
# ============================================================

TS=$(date +%Y%m%d_%H%M%S)
OUT=/data/local/tmp/mihome_diag_$TS.txt
: > "$OUT" 2>/dev/null && HAVE_OUT=1 || HAVE_OUT=0
if [ "$HAVE_OUT" = 0 ]; then
    OUT=/sdcard/mihome_diag_$TS.txt
    : > "$OUT" 2>/dev/null && HAVE_OUT=1
fi

RELOAD=0
for a in "$@"; do
    case "$a" in
        --reload|-r) RELOAD=1 ;;
    esac
done

say() {
    echo "$@"
    [ "$HAVE_OUT" = 1 ] && echo "$@" >> "$OUT"
}
_emit() { while IFS= read -r _l; do say "  $_l"; done; }
_sha() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" 2>/dev/null | cut -d' ' -f1
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$1" 2>/dev/null | sed 's/.*= *//'
    else
        echo unavailable
    fi
}
_fsz() { wc -c < "$1" 2>/dev/null | tr -d ' '; }

PKG=com.probe.homenative
THOME=com.miui.home

say "============================================================"
say " MiHome 小窗补丁探针 · 诊断报告 (rev3)"
say " 时间: $(date)"
say " 模式: $([ "$RELOAD" = 1 ] && echo '重载桌面+实时抓日志' || echo '仅采集')"
say "============================================================"

# ---------- [0] 身份 ----------
say ""
say "########## [0] 运行身份 ##########"
say "uid         : $(id -u 2>/dev/null)  (必须 0)"
if [ "$(id -u 2>/dev/null)" != "0" ]; then
    say ""
    say "!!! 非 root，logcat/maps/LSPosed 目录会读失败 !!!"
    say "!!! 请改用:  su -c \"sh \$0\""
fi
say "selinux     : $(getenforce 2>/dev/null)"

# ---------- [1] 设备 ----------
say ""
say "########## [1] 设备与 ROM ##########"
say "model       : $(getprop ro.product.model) / $(getprop ro.product.device)"
say "brand       : $(getprop ro.product.brand)"
say "android     : $(getprop ro.build.version.release)  SDK=$(getprop ro.build.version.sdk)"
say "build_id    : $(getprop ro.build.id)"
say "incremental : $(getprop ro.build.version.incremental)"
say "mi_os       : $(getprop ro.mi.os.version.name) / $(getprop ro.mi.os.version.incremental)"
say "kernel      : $(uname -r)"
say "uptime_s    : $(cut -d' ' -f1 /proc/uptime 2>/dev/null)"

# ---------- [2] 模块 ----------
say ""
say "########## [2] 探针模块 $PKG ##########"
APKP=""
APK_EPOCH=0
if pm path $PKG >/dev/null 2>&1; then
    APKP=$(pm path $PKG 2>/dev/null | grep -m1 base.apk | sed 's/^package://')
    [ -z "$APKP" ] && APKP=$(pm path $PKG 2>/dev/null | head -1 | sed 's/^package://')
    say "installed   : YES"
    say "apk_path    : $APKP"
    if [ -f "$APKP" ]; then
        say "apk_size    : $(_fsz "$APKP")"
        say "apk_sha256  : $(_sha "$APKP")"
        APK_EPOCH=$(stat -c %Y "$APKP" 2>/dev/null)
        [ -z "$APK_EPOCH" ] && APK_EPOCH=0
        say "apk_mtime   : $(date -r "$APKP" 2>/dev/null || echo "$APK_EPOCH")"
    fi
    dumpsys package $PKG 2>/dev/null | grep -E "versionCode=|versionName=" | head -2 | sed 's/^ */  /'
else
    say "installed   : NO   <<< 目标机没装 v25，先装 APK 再跑"
fi

# ---------- [3] LSPosed ----------
say ""
say "########## [3] LSPosed ##########"
[ -d /data/adb/lspd ] && say "lspd_dir    : YES" || say "lspd_dir    : NO"
[ -f /data/adb/lspd/version ] && say "lspd_ver    : $(cat /data/adb/lspd/version 2>/dev/null)"
say "lsposed_apk : $(pm path org.lsposed.manager 2>/dev/null | head -1 | sed 's/^package://')"
[ -d /data/adb/modules/zygisksu ] && say "zysk_mod    : present" || say "zysk_mod    : absent"

DB=""
for c in /data/adb/lspd/config/modules_config.db /data/adb/lspd/config/modules.db; do
    [ -f "$c" ] && DB="$c" && break
done
if [ -n "$DB" ]; then
    SH=$(grep -a -o "$THOME" "$DB" 2>/dev/null | wc -l | tr -d ' ')
    MH=$(grep -a -o "$PKG" "$DB" 2>/dev/null | wc -l | tr -d ' ')
    say "cfg_db      : $DB ($(_fsz "$DB") B)"
    say "scope_hit   : $SH   (>0 = 配置里有桌面作用域记录)"
    say "module_hit  : $MH   (>0 = 配置里有本模块记录)"
else
    say "cfg_db      : not found"
fi

# ---------- [4] 桌面进程 ----------
say ""
say "########## [4] 桌面进程 $THOME ##########"
TPID=$(pidof $THOME 2>/dev/null | awk '{print $1}')
TPID=${TPID%% *}
PROC_EPOCH=0
if [ -z "$TPID" ]; then
    say "pid         : NONE  <<< 桌面没在跑"
else
    say "pid         : $TPID"
    ST=$(awk '{print $22}' /proc/$TPID/stat 2>/dev/null)
    NOW=$(date +%s)
    UP=$(cut -d' ' -f1 /proc/uptime 2>/dev/null | cut -d. -f1)
    if [ -n "$ST" ] && [ -n "$UP" ]; then
        PROC_EPOCH=$(( NOW - UP + ST / 100 ))
        say "starttime   : $ST jiffies  =>  启动于 $(date -d "@$PROC_EPOCH" 2>/dev/null || echo "$PROC_EPOCH")"
    fi
    say "maps_lines  : $(wc -l < /proc/$TPID/maps 2>/dev/null | tr -d ' ')"
    # direct-from-APK 模式下 maps 路径是 base.apk，不是 libapp.so —— 两种都算
    # 注意：预装应用路径形如 /product/priv-app/MiuiHome/MiuiHome.apk，不含包名，必须一并匹配
    APP_APK_MAP=$(grep -cE 'com\.miui\.home.*\.apk|MiuiHome\.apk' /proc/$TPID/maps 2>/dev/null | tr -d ' ')
    APP_LIB_MAP=$(grep -c 'libapp\.so' /proc/$TPID/maps 2>/dev/null | tr -d ' ')
    case "$APP_LIB_MAP" in ''|*[!0-9]*) APP_LIB_MAP=0 ;; esac
    case "$APP_APK_MAP" in ''|*[!0-9]*) APP_APK_MAP=0 ;; esac
    say "libapp_segs : $APP_LIB_MAP  (显式 libapp.so 行)"
    say "deskapk_segs: $APP_APK_MAP  (>0 = direct-from-APK，libapp.so 在 APK 内)"
fi

# ---------- [4b] 时序硬判据 ----------
say ""
say "########## [4b] 时序硬判据（不依赖日志）##########"
VERDICT_STALE=0
if [ "$APK_EPOCH" -gt 0 ] 2>/dev/null && [ "$PROC_EPOCH" -gt 0 ] 2>/dev/null; then
    if [ "$APK_EPOCH" -gt "$PROC_EPOCH" ]; then
        VERDICT_STALE=1
        say ">>> 桌面启动【早于】模块安装 $(( APK_EPOCH - PROC_EPOCH )) 秒"
        say ">>> => 装完模块没重启桌面，LSPosed 注入不进旧进程（必然失败）"
    else
        say ">>> 桌面启动【晚于】模块安装 $(( PROC_EPOCH - APK_EPOCH )) 秒  => 注入条件满足"
    fi
else
    say "(时间戳不全，跳过)"
fi

# ---------- [4c] 自动重载 ----------
LG_LIVE=""
if [ "$RELOAD" = 1 ]; then
    say ""
    say "########## [4c] 自动重载桌面并实时抓日志 ##########"
    say "清空 logcat 缓冲（含 silent，桌面日志可能落在那里）..."
    logcat -b all -c 2>/dev/null || logcat -c 2>/dev/null || logcat -b main -c 2>/dev/null
    say "启动后台抓取，然后重启桌面（屏幕会闪一下，属正常）..."
    logcat -b all -s MiHomeProbe:V -v brief > /data/local/tmp/_probe_live.log 2>&1 &
    LCPID=$!
    sleep 2
    killall $THOME 2>/dev/null || am force-stop $THOME 2>/dev/null
    say "已 kill 桌面，等待 45 秒让它重新拉起并完成补丁..."
    sleep 45
    kill -9 $LCPID 2>/dev/null
    wait $LCPID 2>/dev/null
    LG_LIVE=$(cat /data/local/tmp/_probe_live.log 2>/dev/null)
    NL=$(printf '%s\n' "$LG_LIVE" | grep -c . 2>/dev/null)
    say "抓到日志行数: ${NL:-0}"
    # 立即打印关键阶段行，便于一眼看出卡在哪
    if [ -n "$LG_LIVE" ]; then
        say "关键阶段:"
        printf '%s\n' "$LG_LIVE" | grep -oE 'MiHomeProbe[^:]*: (GEN|WAIT_ELF|ELF_DYN_OFF|ELF_DYN_OFF_FAIL|WAIT_LOCATE|LOCATE|PATCH_DONE|PATCH_FAIL|WATCH_ROLLBACK|TOTAL)' 2>/dev/null | sed 's/.*: /  - /' | head -25
    fi
    kill -9 $LCPID 2>/dev/null
    wait $LCPID 2>/dev/null
    LG_LIVE=$(cat /data/local/tmp/_probe_live.log 2>/dev/null)
    NL=$(printf '%s\n' "$LG_LIVE" | grep -c . 2>/dev/null)
    say "抓到日志行数: ${NL:-0}"
    TPID=$(pidof $THOME 2>/dev/null | awk '{print $1}')
    TPID=${TPID%% *}
    say "重启后 pid  : ${TPID:-NONE}"
fi

# ---------- [5] libapp.so 指纹 ----------
say ""
say "########## [5] 桌面 APK 与 libapp.so 指纹 ##########"
say "这部分用于确认「目标函数长什么样、当前是什么版本」，跨版本比对靠它。"

HOME_APK=""
if pm path $THOME >/dev/null 2>&1; then
    _pmraw=$(pm path $THOME 2>/dev/null | sed 's/^package://')
    HOME_APK=$(printf '%s\n' "$_pmraw" | grep -m1 'base\.apk$')
    [ -z "$HOME_APK" ] && HOME_APK=$(printf '%s\n' "$_pmraw" | grep -m1 '\.apk$')
    say "home_apk    : ${HOME_APK:-<empty>}"
    say "home_apks_n : $(printf '%s\n' "$_pmraw" | grep -c '\.apk$')  (split 分片数；2 = 预装+更新并存)"
    say "home_all    : $(printf '%s\n' "$_pmraw" | tr '\n' ' ')"
    if [ -f "$HOME_APK" ]; then
        say "apk_size    : $(_fsz "$HOME_APK")"
        say "apk_sha256  : $(_sha "$HOME_APK")"
    fi
    HOFF=$(dumpsys package $THOME 2>/dev/null | sed -n '/versionName=/p' | head -1 | sed 's/^ *//')
    say "home_ver    : $HOFF"
    HTC=$(dumpsys package $THOME 2>/dev/null | grep -m1 'versionCode=' | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p')
    say "home_vcode  : ${HTC:-?}"
    HSDK=$(dumpsys package $THOME 2>/dev/null | grep -m1 'targetSdk=' | sed -n 's/.*targetSdk=\([0-9]*\).*/\1/p')
    HMSDK=$(dumpsys package $THOME 2>/dev/null | grep -m1 'minSdk=' | sed -n 's/.*minSdk=\([0-9]*\).*/\1/p')
    say "home_sdk    : minSdk=${HMSDK:-?} targetSdk=${HSDK:-?}"
else
    say "home_apk    : NOT FOUND"
fi

LIB=/data/local/tmp/libapp_diag.so
rm -f "$LIB"
EXTRACTED=0
if [ -n "$HOME_APK" ] && [ -f "$HOME_APK" ]; then
    if command -v unzip >/dev/null 2>&1; then
        unzip -p "$HOME_APK" lib/arm64-v8a/libapp.so > "$LIB" 2>/dev/null
        if [ -s "$LIB" ]; then
            EXTRACTED=1
        else
            say "unzip 解出为空，尝试 toybox 备用路径"
        fi
    fi
    if [ "$EXTRACTED" = 0 ] && [ -f "/data/data/$THOME/config.arm64_v8a.apk" ]; then
        say "(fallback) 从 config.arm64_v8a.apk 取"
        unzip -p "/data/data/$THOME/config.arm64_v8a.apk" lib/arm64-v8a/libapp.so > "$LIB" 2>/dev/null
        [ -s "$LIB" ] && EXTRACTED=1
    fi
fi

KORIG=0
if [ "$EXTRACTED" = 1 ]; then
    LIB_SZ=$(_fsz "$LIB")
    LIB_SHA=$(_sha "$LIB")
    say "lib_size    : $LIB_SZ"
    say "lib_sha256  : $LIB_SHA"
    # ---- 关键：原始序言特征串出现次数 ----
    # 主口径 = v25 真实使用的 12 字节指纹，与探针日志 LOCATE 的 cand= 同尺度比对。
    # （实测本机：本值 1959，探针 cand=1965，差 6 属扫描范围差异，量级一致即同一函数族）
    PAT12=$(printf '\xfd\x79\xbf\xa9\xfd\x03\x0f\xaa\xef\x01\x01\xd1')
    KORIG=$(grep -a -o -F "$PAT12" "$LIB" 2>/dev/null | wc -l | tr -d ' ')
    case "$KORIG" in ''|*[!0-9]*) KORIG=0 ;; esac
    say "kOrig_cand  : $KORIG   (v25 完整 12 字节指纹命中数，与探针 cand= 直接比对)"
    # 松口径：只比前 8 字节。命中数会大一两个数量级，仅用于「函数是否还在」的粗筛。
    PAT8=$(printf '\xfd\x79\xbf\xa9\xfd\x03\x0f\xaa')
    KLOOSE=$(grep -a -o -F "$PAT8" "$LIB" 2>/dev/null | wc -l | tr -d ' ')
    case "$KLOOSE" in ''|*[!0-9]*) KLOOSE=0 ;; esac
    say "kOrig_loose : $KLOOSE   (前 8 字节，仅粗筛用，无区分度)"
    NPAT=$(printf '\xc0\x82\x00\x91\xc0\x03\x5f\xd6')
    KNEW=$(grep -a -o -F "$NPAT" "$LIB" 2>/dev/null | wc -l | tr -d ' ')
    case "$KNEW" in ''|*[!0-9]*) KNEW=0 ;; esac
    say "kNew_hit    : $KNEW   (>0 = APK 内已是打过补丁的版本)"
else
    say "lib_size    : (提取失败)"
    # 提取失败时的关键旁证：APK 里到底有没有 native 库、叫什么名字
    if [ -n "$HOME_APK" ] && [ -f "$HOME_APK" ] && command -v unzip >/dev/null 2>&1; then
        say "apk_native  : (列出 APK 内 native 目录，判断 libapp.so 是否存在/改名)"
        unzip -l "$HOME_APK" 2>/dev/null | grep -E 'lib/[a-z0-9_-]+/' | head -15 | _emit
    fi
fi

# ---------- [5c] 桌面进程 native 库清单 ----------
say ""
say "########## [5c] 桌面进程 native 库清单 ##########"
say "找不到 libapp.so 时看这里：是没加载、还是被改名/移除了。"
if [ -n "$TPID" ] && [ -r /proc/$TPID/maps ]; then
    SO_LIST=$(grep -oE '/[^ ]+\.so(\.[0-9]+)*' /proc/$TPID/maps 2>/dev/null | sort -u)
    SO_CNT=$(printf '%s\n' "$SO_LIST" | grep -c '\.so')
    HITLIB=$(printf '%s\n' "$SO_LIST" | grep -c 'libapp\.so')
    say "so_unique   : $SO_CNT   (进程加载的唯一 .so 数)"
    say "libapp_hit  : $HITLIB   (>0 = 有独立 libapp.so；0 且 deskapk_segs>0 = 走 APK 内直映射)"
    say "--- 含 app/miui/home/launcher 的库 ---"
    _h=$(printf '%s\n' "$SO_LIST" | grep -iE 'app|miui|home|launcher' | head -20)
    if [ -n "$_h" ]; then printf '%s\n' "$_h" | _emit; else say "  (无)"; fi
    say "--- 全部库前 20 个 ---"
    printf '%s\n' "$SO_LIST" | head -20 | _emit
else
    say "(桌面未运行或 maps 不可读)"
fi

# ---------- [6] 补丁自身落盘记录（主判据）----------
say ""
say "########## [6] cam_dump.txt —— 补丁自身的落盘记录【主判据】##########"
say "这是补丁运行时由 so 自己写下的最后一条记录，不受 logcat 滚动影响。"
say "优先级高于 logcat：只要这里出现 PATCH_DONE/REPATCH，就说明补丁确实跑通了。"
DUMP=""
for c in /data/data/$THOME/cam_dump.txt /data/data/$THOME/files/cam_dump.txt; do
    if [ -f "$c" ]; then
        DUMP="$c"
        say "found       : $c"
        say "size        : $(_fsz "$c")"
        say "mtime       : $(date -r "$c" 2>/dev/null)"
        say "--- 内容开始 ---"
        cat "$c" 2>/dev/null | _emit
        say "--- 内容结束 ---"
        break
    fi
done
[ -z "$DUMP" ] && say "found       : (无 cam_dump.txt，说明 patch 从未执行到写文件这一步)"

# ---------- [7] logcat ----------
say ""
say "########## [7] logcat（辅助，可能已滚动丢失）##########"
GB=$(logcat -g 2>/dev/null | head -2 | tr '\n' ';')
say "logcat_buf  : ${GB:-<logcat -g 失败>}"
LG=$(logcat -d -b all -s MiHomeProbe:V -v brief 2>/dev/null)
NLG=$(printf '%s\n' "$LG" | grep -c . 2>/dev/null)
say "silent捕获  : ${NLG:-0} 行"
if [ -n "$LG" ]; then
    say "--- 开始 ---"
    printf '%s\n' "$LG" | _emit
    say "--- 结束 ---"
else
    say "(空。可能原因：1) 未注入进程 2) 2MiB 环形缓冲已被刷爆 —— 请用 --reload 模式重跑)"
fi
if [ -n "$LG_LIVE" ]; then
    say ""
    say "--- [4c] 重载期间实时抓取 ---"
    printf '%s\n' "$LG_LIVE" | _emit
    say "--- 实时结束 ---"
fi

# ---------- [8] 注入旁证（去噪）----------
say ""
say "########## [8] 注入旁证（已过滤刷屏噪音）##########"
say "A=探针自身日志（最硬证据）  B=桌面进程内的框架注入日志"
say "若整段为空，通常是 2MiB 缓冲已滚掉 —— 用 --reload 模式重跑。"

NOISE8='SurfaceFlinger|SF-MICapture|ActivityRecordInputSink|ActivityTaskManager|WindowManager|PowerRankHelperHolder|LLMClient|toolCall|CodeForge|write_file|read_file|bash_exec|InputDispatcher|ExpertSupervisor|ExpertSwarm|DexOptHelperImpl|artd|AppsFilter|PackageManager|UserManagerService|SceneContextCollector|MiSight|RenderJank|fillFaultRecordContext|Choreographer|FrameStats|SSRU-|powerkeeper'

_LC8=$(logcat -d -b all -v brief 2>/dev/null || logcat -d -v brief 2>/dev/null)
PROBE8=$(printf '%s\n' "$_LC8" | grep -avE "$NOISE8" | grep -a 'MiHomeProbe' | cut -c1-240 | tail -20)

HPID8=$(pidof "$THOME" 2>/dev/null | tr ' ' '\n' | head -1)
HOOK8=$(printf '%s\n' "$_LC8" | grep -avE "$NOISE8" | awk -v p="$THOME" -v hp="$HPID8" '
{
  low = tolower($0)
  if (index(low,"lsposed")||index(low,"xposed")||index(low,"libapp.so")||index(low,"probe.homenative")) {
    if (index(low,p) || (hp != "" && index(" " $0 " ", " " hp " "))) print
  }
}' | cut -c1-240 | tail -15)

say "--- A) MiHomeProbe 探针日志 ---"
if [ -n "$PROBE8" ]; then
    printf '%s\n' "$PROBE8" | _emit
else
    say "  (无。探针未运行，或日志已滚掉)"
fi

say "--- B) 桌面进程(PID=${HPID8:-?})注入框架日志 ---"
if [ -n "$HOOK8" ]; then
    printf '%s\n' "$HOOK8" | _emit
else
    say "  (无。若 A 也为空，多半是模块未加载/未注入)"
fi

# ---------- [9] 判定 ----------
say ""
say "########## [9] 综合判定 ##########"

DUMP_TXT=""
[ -n "$DUMP" ] && DUMP_TXT=$(cat "$DUMP" 2>/dev/null)
ALLG="$LG
$LG_LIVE"
# [9a] 判定前补抓一次（-b all：main/system/crash 全 buffer），弥补 [7]→[8] 期间缓冲滚动
MP_RE=$(logcat -d -b all -s MiHomeProbe:V -v brief 2>/dev/null)
if [ -n "$MP_RE" ]; then
    case "$ALLG" in
        *"$MP_RE"*) : ;;
        *) ALLG="$ALLG
$MP_RE" ;;
    esac
fi

has() { [ -n "$1" ] && printf '%s\n' "$1" | grep -q -- "$2" 2>/dev/null; }

D_PATCH=0; D_ROLL=0; D_TOTAL=0
has "$DUMP_TXT" 'PATCH_DONE' && D_PATCH=1
has "$DUMP_TXT" 'REPATCH'    && D_PATCH=1
has "$DUMP_TXT" 'ROLLBACK'   && D_ROLL=1
has "$DUMP_TXT" 'FAIL'       && D_TOTAL=1

L_LOC=0; L_PATCH=0; L_ROLL=0; L_WELF=0; L_TOTAL=0
has "$ALLG" 'WAIT_ELF'       && L_WELF=1
has "$ALLG" 'LOCATE'         && L_LOC=1
has "$ALLG" 'PATCH_DONE'     && L_PATCH=1
has "$ALLG" 'WATCH_ROLLBACK' && L_ROLL=1
has "$ALLG" 'TOTAL'          && L_TOTAL=1

# ---- v26 新增：ELF 偏移探测 / 定位结果细节 ----
# 把 "I/MiHomeProbe( pid): xxx" 前缀剥掉，得到纯消息行
NORM=$(printf '%s\n' "$ALLG" | sed -n 's/.*MiHomeProbe[^:]*:[ ]*//p')
E_DYN=0; E_DYNFAIL=0; L_NA=0
has "$NORM" '^ELF_DYN_OFF'      && E_DYN=1
has "$NORM" '^ELF_DYN_OFF_FAIL' && E_DYNFAIL=1
has "$NORM" '^addr=n/a'         && L_NA=1
E_OFF=$(printf '%s\n' "$NORM" | sed -n 's/^off=\(0x[0-9a-fA-F]*\)$/\1/p' | head -1)
L_MODE=$(printf '%s\n' "$NORM" | sed -n 's/^mode=\([a-z0-9_]*\)$/\1/p' | head -1)
L_CAND=$(printf '%s\n' "$NORM" | sed -n 's/^cand=\([0-9][0-9]*\)$/\1/p' | head -1)
L_BEST=$(printf '%s\n' "$NORM" | sed -n 's/^best=\([0-9][0-9]*\)$/\1/p' | head -1)
L_SEC=$(printf '%s\n' "$NORM" | sed -n 's/^second=\([0-9][0-9]*\)$/\1/p' | head -1)
case "$L_CAND" in ''|*[!0-9]*) L_CAND=0 ;; esac
case "$L_BEST" in ''|*[!0-9]*) L_BEST=0 ;; esac
case "$L_SEC"  in ''|*[!0-9]*) L_SEC=0  ;; esac

say "signals :"
say "  cam_dump : PATCH=$D_PATCH  ROLLBACK=$D_ROLL  FAIL=$D_TOTAL"
say "  logcat   : WAIT_ELF=$L_WELF  LOCATE=$L_LOC  PATCH=$L_PATCH  ROLLBACK=$L_ROLL"
say "  elf      : DYN_OFF=$E_DYN(${E_OFF:-?})  DYN_FAIL=$E_DYNFAIL  locate_ok=$([ "$L_NA" = 1 ] && echo 0 || echo $([ "$L_LOC" = 1 ] && echo 1 || echo -))"
[ "$L_LOC" = 1 ] && say "  locate   : mode=${L_MODE:-?} cand=$L_CAND best=$L_BEST second=$L_SEC"
say ""

RESULT="UNKNOWN"
if [ "$D_ROLL" = 1 ] && [ "$D_PATCH" = 0 ]; then
    RESULT="ROLLBACK"
    say ">>> 结论：补丁打上了但【被完整性校验回滚】（cam_dump=ROLLBACK）"
    say ">>> 含义：桌面有反篡改逻辑，写进去的指令被改回去了。"
    say ">>> 下一步：需要持续重打（WATCH 循环里改）或直接 patch 掉校验点。"
elif [ "$D_PATCH" = 1 ] || [ "$L_PATCH" = 1 ]; then
    # ---- 区分「一次打好」与「回滚-重打循环」 ----
    RC=$(printf '%s\n' "$DUMP_TXT" | sed -n 's/^n=\([0-9][0-9]*\)$/\1/p' | head -1)
    case "$RC" in ''|*[!0-9]*) RC=0 ;; esac
    RC2=$(printf '%s\n' "$ALLG" | sed -n 's/.*MiHomeProbe[ ]*([ ]*[0-9][0-9]*)[ ]*:[ ]*n=[ ]*\([0-9][0-9]*\).*/\1/p' | head -1)
    case "$RC2" in ''|*[!0-9]*) RC2=0 ;; esac
    [ "$RC2" -gt "$RC" ] 2>/dev/null && RC=$RC2
    IS_REP=0
    { has "$DUMP_TXT" 'REPATCH' || has "$ALLG" 'REPATCH'; } && IS_REP=1
    if [ "$IS_REP" = 1 ] && [ "$RC" -ge 2 ] 2>/dev/null; then
        RESULT="OK_REPATCH_LOOP"
        say ">>> 结论：补丁【生效中，但存在回滚-重打循环】（REPATCH, n=$RC）"
        say ">>> 含义：桌面有完整性校验，把改写的指令改回去；探针 WATCH 循环不断重打维持。"
        say ">>> 影响：功能可用；但持续有小幅 CPU 开销，极端时序下可能瞬时失效。"
        say ">>> 下一步（可选优化）：patch 掉完整性校验点，让重打变成一次到位。"
    else
        RESULT="OK"
        say ">>> 结论：补丁【已生效】（$( [ "$D_PATCH" = 1 ] && echo cam_dump || echo logcat ) 记录到 PATCH_DONE）"
        say ">>> 含义：目标函数定位并改写成功，当前处于补丁状态。"
        say ">>> 若长按桌面图标仍无「小窗」，问题在更上层（入口条件/菜单可见性），不在 native 打补丁。"
    fi
elif [ "$D_TOTAL" = 1 ] || [ "$L_TOTAL" = 1 ]; then
    RESULT="TOTAL_FAIL"
    say ">>> 结论：所有候选都试过仍失败（TOTAL / FAIL）"
    say ">>> 含义：桌面更新后目标函数【换了版本或换了实现】，v25 的 12 字节指纹匹配不到。"
    say ">>> 下一步：把 [5] 的 lib_sha256 / lib_size / kOrig_cand / kOrig_loose 发回，需要重新定位。"
elif [ "$L_ROLL" = 1 ]; then
    RESULT="ROLLBACK"
    say ">>> 结论：补丁被打过但【被回滚】（logcat WATCH_ROLLBACK）"
elif [ "$L_LOC" = 1 ] && [ "$L_NA" = 1 ]; then
    RESULT="STUCK_LOCATE"
    say ">>> 结论：【指纹定位失败】——ELF 已读到，但没在进程里找到目标函数，补丁未打上"
    say ">>> 明细：mode=${L_MODE:-?} cand=$L_CAND best=$L_BEST second=$L_SEC"
    if [ "$L_CAND" = 0 ]; then
        say ">>> 原因候选①：可执行段还没建立完（定位太早）"
        say ">>> 原因候选②：该桌面版本的目标函数字节序列与指纹表不符"
        say ">>> 下一步：把 [5] 的 lib_sha256 / lib_size 发回，需按该版本重算指纹表"
    else
        say ">>> 原因候选：指纹不唯一或相似度不达标（要求 best>=256 且 second < best/2）"
        say ">>> 下一步：把本段 [9] 明细发回，需调整指纹（增加 feature 字节或换锚点）"
    fi
elif [ "$L_LOC" = 1 ]; then
    RESULT="LOCATE_ONLY"
    say ">>> 结论：函数【定位成功】但补丁写入失败（有 addr=0x…，无 PATCH_DONE）"
    say ">>> 含义：最可能是 mprotect 失败 / 该页不可写。"
elif [ "$KNEW" -gt 0 ] 2>/dev/null; then
    RESULT="PREPATCHED"
    say ">>> 异常：APK 内已是补丁后的字节（kNew_hit>0），但运行时无任何记录"
    say ">>> 含义：装的是被别人改过的桌面 APK，或上次补丁已被写回文件（不该发生）。"
elif [ "$VERDICT_STALE" = 1 ]; then
    RESULT="NO_RELOAD"
    say ">>> 结论：模块装了但【桌面没重启】，LSPosed 没机会注入旧进程"
    say ">>> 下一步：su -c \"sh \$0 --reload\""
elif [ "$L_WELF" = 1 ]; then
    RESULT="WAIT_ELF_TIMEOUT"
    say ">>> 结论：so 已加载（有 WAIT_ELF）但等到超时也没定位到 libapp"
    if [ "$E_DYNFAIL" = 1 ]; then
        say ">>> 细分：APK 内【找不到 lib/arm64-v8a/libapp.so】——偏移探测失败"
        say ">>> 含义：桌面把 native 库改了名，或 lite 版不打包该 so。"
        say ">>> 下一步：把本段 [5] 的 APK 内 so 清单发回，需按实际文件名适配。"
    elif [ "$E_DYN" = 1 ]; then
        say ">>> 细分：已算出 libapp.so 偏移 ${E_OFF:-?}，但读不到 ELF 魔数（文件不可读/路径异常）"
        say ">>> 下一步：看 [5] 段 APK 路径与 first8，确认该偏移处是否为 \\x7fELF。"
    else
        say ">>> 细分：日志里没有 ELF_DYN_OFF —— 探针版本过旧（<v26）"
        say ">>> 下一步：安装 v26 探针后重跑本脚本。"
    fi
elif [ "$RELOAD" = 1 ]; then
    RESULT="NO_EVIDENCE"
    say ">>> 结论：已重载桌面，但仍【没有任何日志/文件证据】"
    say ">>> 含义：LSPosed 没把模块注入该进程。常见原因："
    say ">>>   1) LSPosed 里模块没启用"
    say ">>>   2) 作用域没勾选「系统桌面 / 小米桌面 (com.miui.home)」"
    say ">>>   3) 勾了作用域但没重启桌面（本脚本已代劳）"
    say ">>>   4) 模块版本与当前 LSPosed/Android 不兼容"
else
    RESULT="NO_EVIDENCE"
    say ">>> 结论：没有任何证据，无法判断（日志可能已滚掉）"
    say ">>> 下一步：重跑并加 --reload，一次搞定："
    say ">>>   su -c \"sh \$0 --reload\""
fi
say ""
say ">>> 判定码: $RESULT"

# ---------- [10] 判定码手册 ----------
say ""
say "########## [10] 判定码对照手册 ##########"
say "OK            补丁生效。若界面无小窗，问题在上层入口。"
say "OK_REPATCH_LOOP  生效中但有回滚-重打循环（REPATCH n>=2）。功能可用，有持续开销。"
say "ROLLBACK      打上又被改回。需持续重打或 patch 校验点。"
say "LOCATE_ONLY   找到函数但写不进去（addr 有值）。查 mprotect。"
say "STUCK_LOCATE  指纹定位失败（addr=n/a）。指纹表/段建立时机问题。"
say "TOTAL_FAIL    指纹匹配不到任何函数。函数换版本，需重新定位。"
say "WAIT_ELF_TIMEOUT  so 加载了但没找到 libapp。"
say "PREPATCHED    APK 本身含补丁字节，桌面包被改过。"
say "NO_RELOAD     装了模块没重启桌面。"
say "NO_EVIDENCE   LSPosed 没注入，查模块启用/作用域。"

# ---------- [11] 附录 ----------
say ""
say "########## [11] 附录：maps 采样 ##########"
if [ -n "$TPID" ]; then
    grep -E '\.apk|libapp|homeprobe' /proc/$TPID/maps 2>/dev/null | head -12 | cut -c1-190 | _emit
fi

say ""
say "########## [12] 附录：桌面版本摘要 ##########"
say "(桌面属「系统预装 + 更新版」，可能打印两套 versionCode；以 lastUpdateTime 较新者为准)"
dumpsys package $THOME 2>/dev/null | grep -E 'versionName=|versionCode=|targetSdk=|firstInstallTime=|lastUpdateTime=|pkgFlags=' | head -10 | sed 's/^ */  /' | _emit

say ""
say "============================================================"
say " 采集结束。判定码: $RESULT"
if [ "$HAVE_OUT" = 1 ]; then
    say " 报告文件: $OUT"
    say " 把上面这份 txt 完整内容发回来即可。"
else
    say " 报告文件写入失败，请直接复制上面屏幕内容发回。"
fi
say "============================================================"

if [ -f "$LIB" ]; then
    rm -f "$LIB" 2>/dev/null
fi
