#!/system/bin/sh
# ---------------------------------------------------------------
# Camellia root 守护进程：部署 + 看护（v2 · DE 优先）
# 由 Camellia App 自动写入 /data/adb/service.d/，勿手改。
#
# 【v2 关键变更】母本与日志迁到 /data/adb/camellia（DE 分区）
#   旧版 SEED=/data/user/0/<pkg>/files/camd 属 CE 分区，
#   首次解锁前（Direct Boot / DE 阶段）整个 /data/user/0 不可访问，
#   脚本的 `while [ ! -x "$SEED" ]; do sleep 15; done` 会一直卡到解锁，
#   导致 camd 在解锁前永远起不来。
#   实测：DE 阶段 /data/adb 可读写、可执行，/data/user/0 完全不可见。
#   故母本改放 /data/adb/camellia/camd，CE 侧仅作回退兼容。
#
# 保活手段参考 Scene(com.omarea.vtools) 的 up.sh：
#   1. App 进程续命八连（电池优化白名单 / appops / 待机桶 / 冻结）
#   2. 随机 debugfs 隐藏挂载点，camd 运行副本藏在 root-only 目录
#   3. renice -20 提优先级（防调度饿死、防低内存优先回收）
#   4. 日志轮转（camd.log -> camd.err.log）
#   5. 二进制变更先杀后换（避免 Text file busy）
#
# 用法：
#   camd_service.sh           部署一次，然后常驻看护（service.d 开机路径）
#   camd_service.sh --once    只部署一次后退出（App 内「启动/修复」按钮）
#   camd_service.sh --status  打印当前状态后退出
# ---------------------------------------------------------------

PKG="@PKG@"
CE=/data/user/0/$PKG/files
DE=/data/adb/camellia
BINNAME=camd

# DE 目录优先创建（DE 阶段唯一可写位置）
mkdir -p "$DE" 2>/dev/null
chmod 700 "$DE" 2>/dev/null
chown 0:0 "$DE" 2>/dev/null

if [ -d "$DE" ] && [ -w "$DE" ]; then
    LOG=$DE/camd.log
    ERRLOG=$DE/camd.err.log
else
    LOG=$CE/camd.log
    ERRLOG=$CE/camd.err.log
fi

upsec() { cut -d' ' -f1 /proc/uptime 2>/dev/null; }

log() { echo "[$(date '+%m-%d %H:%M:%S')] up=$(upsec) $*" >> "$LOG" 2>/dev/null; }

# 从 /proc 读 nice（第 19 字段）。不用 ps -o NI= —— service.d 阶段 PATH 被裁剪时
# ps 会静默失败，导致日志里 nice= 恒空（实测踩过：renice 成功但日志打空）。
read_nice() { cut -f19 -d" " /proc/"$1"/stat 2>/dev/null; }

# system_server 完全就绪后才允许跑 am/cmd/dumpsys；DE 阶段这些会全部失败，
# 每次拉起都刷 8 条错误只是噪音，还拖慢启动。
booted() { [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; }

# 母本解析：DE 优先，CE 回退（兼容旧版本已释放的 CE 母本）
resolve_seed() {
    if [ -x "$DE/$BINNAME" ]; then echo "$DE/$BINNAME"; return 0; fi
    if [ -x "$CE/$BINNAME" ]; then echo "$CE/$BINNAME"; return 0; fi
    echo ""
}

seed_sig() {
    # 判据取「母本内容本身」，不读 camd.sig。
    # camd.sig 是「大小-时间戳」格式，而 camd 重编后大小常常一字不差
    # （2026-11-03：旧 673424B / 新 673424B，md5 却从 c10efa81 变成 0adf00a3），
    # 只比 sig 会让 deploy_once 认定「已是最新」，隐藏副本永远停在旧版。
    _f=$(resolve_seed)
    [ -n "$_f" ] || return 0
    md5sum "$_f" 2>/dev/null | cut -c1-16
}

# ---------------- 1. App 进程续命八连（只作用于本包名，幂等） ----------------
keepalive_app() {
    booted || return 0
    dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1
    cmd appops set $PKG RUN_IN_BACKGROUND allow >/dev/null 2>&1
    cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
    am set-standby-bucket $PKG active >/dev/null 2>&1
    am set-inactive --user 0 $PKG false >/dev/null 2>&1
    am set-bg-restriction-level --user 0 $PKG unrestricted >/dev/null 2>&1
    am set-foreground-service-delegate --user 0 $PKG start >/dev/null 2>&1
    am unfreeze --sticky $PKG >/dev/null 2>&1
}

# ---------------- 2. 随机 debugfs 隐藏挂载点 ----------------
# 把 camd 运行副本放进 /dev/<随机8位>（chmod 600，非 root 无法遍历），
# 常规文件扫描（/data/data、/data/local/tmp）看不到；该目录下再挂一份
# 独立 debugfs（系统未挂时），兼作内核调试能力 —— Scene 同款做法。
hide_prepare() {
    h=$(ls -d /dev/*/.x 2>/dev/null | head -1)
    if [ -n "$h" ]; then
        h=$(dirname "$h")
        if [ -d "$h" ] && [ -w "$h" ]; then echo "$h"; return; fi
    fi
    r1=$(tr -dc 'a-z_' < /dev/urandom 2>/dev/null | head -c 8)
    [ -n "$r1" ] || r1="dev_$$"
    h="/dev/$r1"
    mkdir -p "$h" 2>/dev/null || { echo ""; return; }
    chmod 600 "$h" 2>/dev/null
    if [ -z "$(awk '$3=="debugfs"{print $2; exit}' /proc/mounts 2>/dev/null)" ]; then
        r2=$(tr -dc 'a-z_' < /dev/urandom 2>/dev/null | head -c 8)
        [ -n "$r2" ] || r2="mnt_$$"
        if mkdir -p "$h/$r2" 2>/dev/null; then
            mount -t debugfs debugfs "$h/$r2" -o mode=755 2>/dev/null || rmdir "$h/$r2" 2>/dev/null
        fi
    fi
    echo "$h"
}

# ---------------- 2.5 自带 busybox ----------------
# camd 依赖 sh / gzip / unzip。DE 阶段 /system 一般已可读，但不同 ROM 的
# toybox 未必编入 gzip、unzip applet，且 service.d 拉起时 PATH 可能被裁剪。
# 把 busybox 落到 DE 固定路径，camd 侧以它为第一候选，彻底不依赖 PATH。
seed_busybox() {
    [ -x "$DE/busybox" ] && return 0
    for c in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox; do
        if [ -x "$c" ]; then
            if cp -f "$c" "$DE/busybox" 2>/dev/null; then
                chmod 755 "$DE/busybox" 2>/dev/null
                log "busybox seeded src=$c size=$(stat -c%s "$DE/busybox" 2>/dev/null)"
                return 0
            fi
        fi
    done
    log "busybox absent (no ksu/magisk busybox); camd falls back to toybox"
    return 1
}

# ---------------- 3. 部署 + 拉起 ----------------
deploy_once() {
    SEED=$(resolve_seed)
    if [ -z "$SEED" ]; then
        log "NO_SEED de_missing ce_missing booted=$(getprop sys.boot_completed 2>/dev/null)"
        echo "NO_SEED"
        return 1
    fi

    keepalive_app
    seed_busybox

    HIDE=$(hide_prepare)
    [ -n "$HIDE" ] || HIDE="$DE"
    WORK="$HIDE/.x"
    mkdir -p "$WORK" 2>/dev/null
    chmod 700 "$WORK" 2>/dev/null

    # 母本更新时刷新隐藏副本（先杀后换，否则 cp 覆盖运行中的文件会 ETXTBSY）
    SIGV=$(seed_sig)
    if [ ! -s "$WORK/$BINNAME" ] || [ "$SIGV" != "$(cat "$WORK/.sig" 2>/dev/null)" ]; then
        pkill -x $BINNAME >/dev/null 2>&1
        sleep 1
        if cp -f "$SEED" "$WORK/$BINNAME" 2>/dev/null; then
            chmod 700 "$WORK/$BINNAME"
            echo "$SIGV" > "$WORK/.sig"
            log "binary refreshed seed=$SEED sig=$SIGV"
        else
            log "COPY_FAIL seed=$SEED"
            echo "COPY_FAIL"
            return 1
        fi
    fi

    if ! pidof $BINNAME >/dev/null 2>&1; then
        [ -f "$LOG" ] && [ "$(stat -c%s "$LOG" 2>/dev/null || echo 0)" -gt 262144 ] && mv -f "$LOG" "$ERRLOG" 2>/dev/null
        setsid "$WORK/$BINNAME" </dev/null >/dev/null 2>>"$LOG" &
        sleep 1
        P=$(pidof $BINNAME | awk '{print $1}')
        [ -n "$P" ] && renice -n -20 -p "$P" >/dev/null 2>&1
        log "started pid=$P nice=$(read_nice "$P") work=$WORK seed=$SEED booted=$(getprop sys.boot_completed 2>/dev/null)"
    fi

    P=$(pidof $BINNAME)
    if [ -n "$P" ]; then
        for x in $P; do renice -n -20 -p "$x" >/dev/null 2>&1; done
        P1=$(echo $P | awk '{print $1}')
        echo "OK pid=$P1 nice=$(read_nice "$P1") n=$(echo $P | wc -w) dir=$WORK"
        return 0
    fi
    log "FAIL camd not running after start"
    echo "FAIL camd not running"
    return 1
}

# ---------------- 4. 状态 ----------------
show_status() {
    echo "DE      : $DE $( [ -d "$DE" ] && echo OK || echo MISSING )"
    echo "DE camd : $( [ -x "$DE/$BINNAME" ] && echo "present $(stat -c%s "$DE/$BINNAME" 2>/dev/null)B" || echo absent )"
    echo "CE camd : $( [ -x "$CE/$BINNAME" ] && echo present || echo absent )"
    echo "SEED    : $(resolve_seed)"
    echo "busybox : $( [ -x "$DE/busybox" ] && echo "present $(stat -c%s "$DE/busybox" 2>/dev/null)B" || echo absent )"
    echo "booted  : $(getprop sys.boot_completed 2>/dev/null)"
    echo "uptime  : $(upsec)"
    P=$(pidof $BINNAME)
    echo "pid     : ${P:-none}"
    [ -n "$P" ] && for x in $P; do echo "  $x exe=$(readlink /proc/$x/exe) nice=$(read_nice "$x")"; done
    echo "log     : $LOG $( [ -f "$LOG" ] && echo "$(stat -c%s "$LOG")B" || echo missing )"
}

# ---------------- 暂停标记 ----------------
# 面板「暂停 camd」会 touch $DE/paused 并杀掉常驻循环。开机时 init 仍会执行本
# 脚本一次，这里先看一眼标记直接退出，避免重启手机后 camd 被悄悄拉起。
# 成本：开机一次 stat，无任何常驻开销；App 显式调用 --once / --status 不受影响
# （「重新启动」会先删标记再拉起）。
if [ -z "$1" ] && [ -e "$DE/paused" ]; then
    log "paused: skip boot start"
    exit 0
fi

# ---------------- 入口 ----------------
case "$1" in
    --once)
        deploy_once
        exit $?
        ;;
    --status)
        show_status
        exit 0
        ;;
esac

# service.d 路径：DE 阶段即可启动，不再等待 CE 解密
log "daemon boot start (de=$DE booted=$(getprop sys.boot_completed 2>/dev/null) up=$(upsec))"
deploy_once >>"$LOG" 2>&1
while true; do
    sleep 60
    if ! pidof $BINNAME >/dev/null 2>&1; then
        deploy_once >>"$LOG" 2>&1
    else
        keepalive_app
        for x in $(pidof $BINNAME); do renice -n -20 -p "$x" >/dev/null 2>&1; done
    fi
done
