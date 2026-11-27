#!/system/bin/sh
# Camellia SystemUI 急救 v4 — root 独立守护。只负责 SystemUI 急救。
# 安装：/data/adb/service.d/50-camellia-rescue.sh
set -u

PKG=com.android.systemui
BASE=${CAMELLIA_BASE:-/data/adb/camellia_rescue}
DRYRUN=${CAMELIA_DRYRUN:-${CAMELLIA_DRYRUN:-0}}
POLL=5
FAIL_AFTER=45
HEALTH_AFTER=15
BOOT_GRACE=180
CHANGE_GRACE=120
MANUAL_GRACE=120
FUSE_WINDOW=86400
FUSE_MAX=2
LOCK="$BASE/guard.lock"
LOG="$BASE/guard.log"
HIST="$BASE/history.log"
SNAP="$BASE/source.snapshot"
CUR="$BASE/source.current"
STATE="$BASE/state"
CMD="$BASE/command"
QUIET="$BASE/quiet_until"
FUSE="$BASE/fused"
SELF="$BASE/self_until"
PENDING="$BASE/pending_candidate"
KEYREQ="$BASE/key_request"
MODULE_ROOTS="/data/adb/modules /data/adb/modules_update"
mkdir -p "$BASE" 2>/dev/null || exit 1

now() { date +%s; }
log() { echo "$(date '+%F %T') [$$] $*" >> "$LOG"; }
history() { echo "$(date '+%F %T') $*" >> "$HIST"; }
trim_logs() {
    for f in "$LOG" "$HIST"; do
        [ -f "$f" ] || continue
        n=$(wc -c < "$f" 2>/dev/null || echo 0)
        case "$n" in ''|*[!0-9]*) continue;; esac
        if [ "$n" -gt 524288 ]; then tail -c 131072 "$f" > "$f.t" && mv "$f.t" "$f"; fi
    done
}
run() {
    if [ "$DRYRUN" = 1 ]; then log "DRYRUN: $*"; return 0; fi
    "$@"
}
num() { case "$1" in ''|*[!0-9]*) echo 0;; *) echo "$1";; esac; }
get_num() { [ -f "$1" ] && num "$(cat "$1" 2>/dev/null)" || echo 0; }
set_num() { echo "$1" > "$2.tmp" && mv "$2.tmp" "$2"; }
quiet_active() { [ "$(now)" -lt "$(get_num "$QUIET")" ]; }
self_active() { [ "$(now)" -lt "$(get_num "$SELF")" ]; }

# 只收集包路径与摘要；不读取应用私有数据、账号或 LSPosed 数据库。
source_path() { pm path "$PKG" 2>/dev/null | sed -n 's/^package://p' | head -1; }
source_kind() { case "$1" in /data/*) echo UPDATE;; /system/*|/product/*|/vendor/*|/system_ext/*) echo SYSTEM;; *) echo UNKNOWN;; esac; }
file_hash() { sha256sum "$1" 2>/dev/null | awk '{print $1}'; }

module_file_for() {
    root=$1
    # 通用遍历模块树，不依赖固定模块 ID 或少数分区布局；仅匹配 SystemUI APK 文件名。
    find -L "$root" -type f \( -name 'MiuiSystemUI.apk' -o -name 'SystemUI.apk' \) 2>/dev/null | head -1
}
module_candidates() {
    for tree in $MODULE_ROOTS; do
        [ -d "$tree" ] || continue
        for m in "$tree"/*; do
            [ -d "$m" ] || continue
            id=${m##*/}
            case "$tree" in */modules_update)
                [ -f "$m/remove" ] && continue
                kind=update;;
            *) [ -f "$m/disable" ] || [ -f "$m/remove" ] && continue
               kind=active;;
            esac
            f=$(module_file_for "$m")
            [ -n "$f" ] || continue
            h=$(file_hash "$f")
            echo "$kind|$id|$h|$f"
        done
    done
}
candidate_ids() { module_candidates | cut -d'|' -f2 | sort -u; }

make_snapshot() {
    p=$(source_path)
    h=$(file_hash "$p")
    {
        echo "path=$p"
        echo "kind=$(source_kind "$p")"
        echo "hash=$h"
        echo "size=$(stat -c %s "$p" 2>/dev/null || echo 0)"
        echo "modules_begin"
        module_candidates
        echo "modules_end"
    } > "$CUR"
}
snapshot_diff() { [ -f "$SNAP" ] || { echo NO_BASELINE; return; }; cmp -s "$SNAP" "$CUR" || echo CHANGED; }
save_snapshot() { cp "$CUR" "$SNAP.tmp" && mv "$SNAP.tmp" "$SNAP"; }

health() {
    pid=$(pidof "$PKG" 2>/dev/null | awk '{print $1}')
    [ -n "$pid" ] || { echo "PROCESS_DOWN"; return 1; }
    svc=$(dumpsys activity services "$PKG" 2>/dev/null | grep -c 'SystemUIService')
    [ "$(num "$svc")" -gt 0 ] || { echo "SERVICE_MISSING"; return 1; }
    w=$(dumpsys window windows 2>/dev/null)
    echo "$w" | grep -qE 'Window.*(StatusBar|NotificationShade)' || { echo "STATUS_WINDOW_MISSING"; return 1; }
    # NavigationBar 会因手势导航或合法 UI 状态而缺席，不能作为必需窗口。
    echo "HEALTHY pid=$pid nav=$(echo "$w" | grep -cE 'Window.*NavigationBar')"
    return 0
}
wait_healthy() {
    limit=${1:-90}; good=0; i=0
    while [ "$i" -lt "$limit" ]; do
        if health >/dev/null 2>&1; then good=$((good+1)); [ "$good" -ge 3 ] && return 0; else good=0; fi
        sleep 2; i=$((i+2))
    done
    return 1
}
recent_crash() {
    # logcat 只是佐证；读取失败不影响基于失活的检测。
    logcat -d -b crash -t 120 2>/dev/null | grep -q "$PKG"
}

capture() {
    tag=$1
    dir="$BASE/evidence"
    mkdir -p "$dir" 2>/dev/null
    {
        echo "time=$(date '+%F %T') reason=$tag"
        echo "uptime=$(cat /proc/uptime 2>/dev/null)"
        health 2>&1
        pm path "$PKG" 2>&1
        dumpsys package "$PKG" 2>/dev/null | grep -E 'codePath=|versionCode=|versionName=|lastUpdateTime=' | head -10
        echo "candidates:"; module_candidates
    } > "$dir/last.txt"
    logcat -d -b crash -t 200 > "$dir/last_crash.log" 2>/dev/null
    cp "$CUR" "$dir/last_source.txt" 2>/dev/null
}

soft_restart() {
    reason=$1
    quiet_active && { log "restart suppressed: quiet"; return 1; }
    set_num $(( $(now) + MANUAL_GRACE )) "$SELF"
    history "SOFT_RESTART reason=$reason"
    p=$(pidof "$PKG" 2>/dev/null | awk '{print $1}')
    [ -n "$p" ] && run kill -9 "$p"
    sleep 3
    # SystemUI 是 persistent，正常由 AMS 拉起；必要时补一次显式启动。
    if ! pidof "$PKG" >/dev/null 2>&1; then run am startservice -n "$PKG/.SystemUIService" >/dev/null 2>&1; fi
    if wait_healthy 90; then
        make_snapshot; save_snapshot
        history "RECOVERED action=soft_restart"
        return 0
    fi
    history "FAILED action=soft_restart"
    return 1
}

recover_update() {
    mode=${1:-manual}
    p=$(source_path)
    [ "$(source_kind "$p")" = UPDATE ] || { log "no data update"; return 2; }
    [ "$mode" = manual ] || [ -f "$PENDING" ] || return 2
    capture bad_update
    cp "$p" "$BASE/evidence/bad_systemui_update.apk" 2>/dev/null
    set_num $(( $(now) + CHANGE_GRACE )) "$SELF"
    history "RECOVER_UPDATE path=$p mode=$mode"
    run pm uninstall-system-updates "$PKG" >> "$LOG" 2>&1 || { history "FAILED update command"; return 1; }
    if wait_healthy 120; then
        make_snapshot; save_snapshot; rm -f "$PENDING"
        history "RECOVERED action=uninstall-system-updates"
        return 0
    fi
    history "FAILED action=uninstall-system-updates"
    return 1
}

valid_module_id() { case "$1" in ''|*[!A-Za-z0-9_.-]*) return 1;; *) return 0;; esac; }
find_module() {
    wanted=$1
    for tree in $MODULE_ROOTS; do
        [ -d "$tree/$wanted" ] && { echo "$tree/$wanted"; return; }
    done
}
disable_mount() {
    id=$1; mode=${2:-manual}
    valid_module_id "$id" || return 2
    m=$(find_module "$id")
    [ -n "$m" ] || { log "module missing: $id"; return 2; }
    [ -n "$(module_file_for "$m")" ] || { log "module has no SystemUI: $id"; return 2; }
    [ "$mode" = manual ] || [ -f "$PENDING" ] || return 2
    capture bad_mount
    case "$m" in */modules_update/*) mark=remove;; *) mark=disable;; esac
    history "DISABLE_MOUNT id=$id mark=$mark mode=$mode (reboot required)"
    run touch "$m/$mark" || return 1
    # 先保留候选；必须由新守护进程在重启后完成健康复查，才能清除或继续处置。
    history "PENDING_REBOOT id=$id"
    # 禁用只影响下一次开机；自动模式只允许在一个已确认根因的故障流程里到达这里。
    if [ "$DRYRUN" != 1 ]; then sync; run reboot; fi
    return 0
}

# 仅按 epoch 统计恢复动作；不解析本地化日期，也不从日志文本推断熔断。
action_count_24h() {
    cut=$(( $(now) - FUSE_WINDOW )); n=0
    [ -f "$BASE/action_times" ] || { echo 0; return; }
    while read -r t; do [ "$(num "$t")" -ge "$cut" ] && n=$((n+1)); done < "$BASE/action_times"
    echo "$n"
}
record_action() { now > /dev/null 2>&1; echo "$(now)" >> "$BASE/action_times"; }
allow_action() {
    [ ! -f "$FUSE" ] || { log "fused"; return 1; }
    [ "$(action_count_24h)" -lt "$FUSE_MAX" ] || { touch "$FUSE"; history "FUSED max actions/24h"; return 1; }
    ! self_active
}
prepare_candidate() {
    rm -f "$PENDING"
    kind=$(sed -n 's/^kind=//p' "$CUR" | head -1)
    oldkind=$(sed -n 's/^kind=//p' "$SNAP" 2>/dev/null | head -1)
    ids=$(candidate_ids); count=$(echo "$ids" | sed '/^$/d' | wc -l)
    # 必须看到来源变化；首次运行没有旧快照绝不猜根因。
    [ "$(snapshot_diff)" = CHANGED ] || return 1
    if [ "$kind" = UPDATE ] && [ "$oldkind" != UPDATE ]; then
        echo UPDATE > "$PENDING"; return 0
    fi
    if [ "$count" -eq 1 ]; then
        old=$(sed -n '/modules_begin/,/modules_end/p' "$SNAP" 2>/dev/null)
        new=$(sed -n '/modules_begin/,/modules_end/p' "$CUR")
        [ "$old" != "$new" ] || return 1
        echo "MOUNT|$ids" > "$PENDING"; return 0
    fi
    return 1
}

attend_failure() {
    reason=$1
    quiet_active || self_active || return 0
    capture "$reason"
    prepare_candidate
    if allow_action; then
        record_action
        if [ -f "$PENDING" ]; then
            c=$(cat "$PENDING")
            case "$c" in
                UPDATE) recover_update auto && return 0;;
                MOUNT\|*) disable_mount "${c#*|}" auto; return $?;;
            esac
        else
            # 未知根因只软重启一次，绝不自动扩大为撤包/禁模块。
            soft_restart "$reason" && return 0
        fi
    fi
    touch "$FUSE"
    history "FUSED unresolved=$reason candidate=$(cat "$PENDING" 2>/dev/null || echo none)"
}

manual_arm() { sec=${1:-120}; set_num $(( $(now) + $(num "$sec") )) "$BASE/key_armed_until"; echo "armed ${sec}s"; }
key_armed() { [ "$(now)" -lt "$(get_num "$BASE/key_armed_until")" ] || [ -f "$PENDING" ]; }
handle_key_request() {
    [ -f "$KEYREQ" ] || return 0
    req=$(cat "$KEYREQ" 2>/dev/null); rm -f "$KEYREQ"
    key_armed || { log "key ignored outside armed window"; return; }
    manual_arm 120
    case "$req" in
        restart) capture key_restart; allow_action && { record_action; soft_restart key_three_volume_up; };;
        recover)
            if [ -f "$PENDING" ]; then
                capture key_confirm; allow_action || return; record_action
                c=$(cat "$PENDING")
                case "$c" in UPDATE) recover_update manual;; MOUNT\|*) disable_mount "${c#*|}" manual;; esac
            else log "key recover ignored: no unique candidate"; fi;;
    esac
}

# 按键监听只输出 KEY_VOLUMEUP/DOWN 的 value=1；10 秒 3 次才生成请求。
key_listener() {
    getevent -lt 2>/dev/null | while read -r line; do
        case "$line" in *KEY_VOLUMEUP*' 00000001 00000000'*) k=up;; *KEY_VOLUMEDOWN*' 00000001 00000000'*) k=down;; *) continue;; esac
        key_armed || continue
        f="$BASE/key_$k"; t=$(now); last=$(get_num "$f.last"); n=$(get_num "$f.count")
        [ $((t-last)) -le 10 ] || n=0
        n=$((n+1)); set_num "$t" "$f.last"; set_num "$n" "$f.count"
        if [ "$n" -ge 3 ]; then
            set_num 0 "$f.count"
            [ "$k" = up ] && echo restart > "$KEYREQ" || echo recover > "$KEYREQ"
            cmd vibrator on 80 2>/dev/null
        fi
    done
}

status() {
    make_snapshot
    echo "version=v4 dryrun=$DRYRUN"
    health 2>&1
    echo "source=$(sed -n 's/^path=//p' "$CUR")"
    echo "kind=$(sed -n 's/^kind=//p' "$CUR")"
    echo "hash=$(sed -n 's/^hash=//p' "$CUR")"
    echo "modules:"; module_candidates
    echo "baseline=$([ -f "$SNAP" ] && echo yes || echo no) pending=$(cat "$PENDING" 2>/dev/null || echo none)"
    echo "fused=$([ -f "$FUSE" ] && echo yes || echo no) actions_24h=$(action_count_24h)"
    echo "daemon=$(pgrep -f '50-camellia-rescue.sh|sysui_rescue_guard.sh' 2>/dev/null | tr '\n' ' ')"
}

selftest() {
    fails=0
    check() { if [ "$1" = "$2" ]; then echo "PASS $3"; else echo "FAIL $3 expected=$1 actual=$2"; fails=$((fails+1)); fi; }
    check UPDATE "$(source_kind /data/app/x/base.apk)" update_kind
    check SYSTEM "$(source_kind /system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk)" system_kind
    check UNKNOWN "$(source_kind /unknown/a.apk)" unknown_kind
    valid_module_id 'good.module-1'; check 0 "$?" valid_id
    valid_module_id '../bad'; check 1 "$?" reject_traversal
    valid_module_id 'a/b'; check 1 "$?" reject_slash
    CAMELLIA_DRYRUN=1; DRYRUN=1
    run touch "$BASE/selftest_should_not_exist"; check 0 "$?" dryrun_accept
    [ ! -e "$BASE/selftest_should_not_exist" ]; check 0 "$?" dryrun_no_write
    tmp="$BASE/selftest_modules"; mkdir -p "$tmp/demo/system/system_ext/priv-app/MiuiSystemUI"
    echo fixture > "$tmp/demo/system/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
    MODULE_ROOTS="$tmp"; ids=$(candidate_ids); check demo "$ids" mount_detection
    touch "$tmp/demo/disable"; check '' "$(candidate_ids)" disabled_ignored
    rm -rf "$tmp"
    check 0 "$([ -f "$PENDING" ] && echo 1 || echo 0)" no_false_candidate
    echo "selftest failures=$fails"; [ "$fails" -eq 0 ]
}

main() {
    # mkdir+pid 文件：同机只允许一个主循环；manual 子命令不会抢锁。
    if ! mkdir "$LOCK" 2>/dev/null; then log "already running"; exit 0; fi
    trap 'rmdir "$LOCK" 2>/dev/null' EXIT INT TERM
    echo $$ > "$LOCK/pid"
    log "v4 daemon start dryrun=$DRYRUN"
    key_listener &
    fail_count=0; good_count=0; started=$(now)
    echo "boot_start=$(date '+%F %T')" > "$STATE"
    make_snapshot
    [ -f "$SNAP" ] || save_snapshot
    while true; do
        trim_logs
        if [ -f "$CMD" ]; then c=$(cat "$CMD"); rm -f "$CMD"; case "$c" in restart) soft_restart command;; esac; fi
        handle_key_request
        make_snapshot
        if health >/dev/null 2>&1; then
            good_count=$((good_count+1)); fail_count=0
            if [ "$good_count" -ge 3 ]; then
                save_snapshot
                if [ -f "$PENDING" ]; then history "RECOVERED action=disable-mount reboot-verified"; fi
                rm -f "$PENDING"
            fi
        else
            good_count=0; fail_count=$((fail_count+POLL))
            up=$(awk '{print int($1)}' /proc/uptime 2>/dev/null)
            if [ "$(num "$up")" -ge "$BOOT_GRACE" ] && [ "$fail_count" -ge "$FAIL_AFTER" ] && ! quiet_active && ! self_active; then
                attend_failure health_lost
                fail_count=0
            fi
        fi
        sleep "$POLL"
    done
}

case "${1:-daemon}" in
    daemon) main;;
    status) status;;
    restart) capture manual; record_action; soft_restart manual;;
    recover-update) recover_update manual;;
    disable-mount) [ $# -eq 2 ] || { echo 'usage: disable-mount <模块ID>'; exit 2; }; disable_mount "$2" manual;;
    arm) manual_arm "${2:-120}";;
    fuse-reset) rm -f "$FUSE" "$BASE/action_times"; history "FUSE_RESET";;
    export) out="/sdcard/Download/camellia_rescue_$(date +%Y%m%d_%H%M%S).tar.gz"; tar -czf "$out" -C "$BASE" . && echo "$out";;
    selftest) selftest;;
    *) echo 'usage: daemon|status|restart|recover-update|disable-mount <id>|arm [sec]|fuse-reset|export|selftest'; exit 2;;
esac
