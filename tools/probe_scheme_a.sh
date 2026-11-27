#!/system/bin/sh
# ============================================================================
# Camellia 方案A（camd unix socket 通道）跨机型可行性探测  —— 判据 v2
#
# 判断三件事：
#   ① camd 侧有没有把 socket 监听起来（bind + listen 成功）
#   ② App 侧能不能 connect 上去（SELinux unix_stream_socket connectto 是否放行）
#   ③ 若连上，连的是不是「真的 App 进程」（看 camd 用 SO_PEERCRED 拿到的 uid）
#
# 用法（必须 root）：
#   su -c 'sh /sdcard/probe_scheme_a.sh'
#   su -c 'sh /sdcard/probe_scheme_a.sh --active'          # 额外做强停重连测试
#   su -c 'sh /sdcard/probe_scheme_a.sh 2>&1 | tee /sdcard/Download/schemeA.txt'
#
# 前置：装好 2.31.2+ 新模块，打开过至少一次 App。整段输出发回即可。
#
# 【核心判据】camd.log 里的 "sock peer ... uid=N"：
#   N == App 的 uid  -> PASS，SELinux 已放行，方案A 可用
#   N == 0           -> 是 root 进程连的（自测脚本），不能算 App 侧放行
#   没有这行         -> camd 是旧版（不含 SO_PEERCRED），退化为看 logcat+fd
#
# 【注意】不要用「两端 socket inode 相同」判断连通：unix socket 连接的两端
#   在 /proc/net/unix 与各进程 fd 里是**两个不同 inode**（相邻分配），
#   我曾因此误判为未连接。
# ============================================================================

PKG=com.hyper.Camellia
SOCK=/data/data/$PKG/files/camd.sock
DE=/data/adb/camellia
LOGF=$DE/camd.log
ACTIVE=0
[ "$1" = "--active" ] && ACTIVE=1

sep()  { echo; echo "===== $* ====="; }
ok()   { echo "  [OK]   $*"; }
ng()   { echo "  [FAIL] $*"; }
wn()   { echo "  [WARN] $*"; }
inf()  { echo "  [*]    $*"; }
kv()   { printf '  %-16s %s\n' "$1" "$2"; }

if [ "$(id -u)" != "0" ]; then
    echo "!! 需要 root：su -c 'sh $0'"
    exit 1
fi

echo "############################################################"
echo "# Camellia 方案A（unix socket 通道）探测 · 判据 v2"
echo "# 时间 $(date '+%Y-%m-%d %H:%M:%S')"
echo "############################################################"

# ---------------------------------------------------------------- 1. 环境
sep "1. 环境"
kv "机型"      "$(getprop ro.product.model 2>/dev/null)"
kv "品牌"      "$(getprop ro.product.brand 2>/dev/null)"
kv "安卓"      "$(getprop ro.build.version.release 2>/dev/null) (SDK $(getprop ro.build.version.sdk 2>/dev/null))"
kv "内核"      "$(uname -r 2>/dev/null)"
kv "SELinux"   "$(getenforce 2>/dev/null)"
ROOTIMPL="-"
command -v ksud   >/dev/null 2>&1 && ROOTIMPL="$ROOTIMPL KernelSU"
command -v magisk >/dev/null 2>&1 && ROOTIMPL="$ROOTIMPL Magisk"
command -v apd    >/dev/null 2>&1 && ROOTIMPL="$ROOTIMPL APatch"
kv "root 方案" "$ROOTIMPL"

# ---------------------------------------------------------------- 2. 模块与 uid
sep "2. 模块与 camd"
APK_PATH=$(pm path $PKG 2>/dev/null | sed -n 's/^package://p' | head -1)
if [ -n "$APK_PATH" ]; then
    ok "模块已安装"
    dumpsys package $PKG 2>/dev/null | grep -m2 -E 'versionName|versionCode' | sed 's/^ */  /'
else
    ng "模块未安装"
fi
# App 的 uid：和 camd.log 的 peer uid 对照，这是判定「真 App 连上」的基准
APP_UID=$(dumpsys package $PKG 2>/dev/null | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -1)
[ -z "$APP_UID" ] && APP_UID=$(stat -c%u /data/data/$PKG 2>/dev/null)
kv "App uid"   "${APP_UID:-未知}  （camd peer uid 应与它相等）"

CPID=$(pidof camd 2>/dev/null | awk '{print $1}')
if [ -n "$CPID" ]; then
    ok "camd 在跑 pid=$CPID"
    CAMD_DOM=$(cat /proc/$CPID/attr/current 2>/dev/null | awk '{print $1}')
    kv "camd 域"   "${CAMD_DOM:-未知}"
    kv "camd exe"  "$(readlink /proc/$CPID/exe 2>/dev/null)"
    kv "母本大小"  "$(stat -c%s $DE/camd 2>/dev/null) B （/data/adb/camellia/camd）"
    HASSOCK=$(grep -ac 'camd.sock' /proc/$CPID/exe 2>/dev/null)
    [ -z "$HASSOCK" ] && HASSOCK=0
    HASPEER=$(grep -ac 'sock peer' /proc/$CPID/exe 2>/dev/null)
    [ -z "$HASPEER" ] && HASPEER=0
    [ "$HASSOCK" -gt 0 ] && ok "camd 含 socket 支持" \
                         || ng "camd 不含 camd.sock —— 部署的是旧版 camd，方案A 不可能生效"
    [ "$HASPEER" -gt 0 ] && ok "camd 含 SO_PEERCRED（可校验对端身份）" \
                         || wn "camd 不含 SO_PEERCRED（旧版；只能靠 logcat+fd 旁证）"
else
    ng "camd 没在跑 —— 先确认 /data/adb/service.d/camellia_daemon.sh 已部署并执行过"
    CAMD_DOM=""
fi

# ---------------------------------------------------------------- 3. socket 文件
sep "3. socket 文件"
if [ -S "$SOCK" ]; then
    ok "socket 文件存在（类型 S）"
    ls -la "$SOCK" 2>/dev/null | sed 's/^/  /'
else
    if [ -e "$SOCK" ]; then
        ng "$SOCK 存在但不是 socket 类型"
        ls -la "$SOCK" 2>/dev/null | sed 's/^/  /'
    else
        ng "$SOCK 不存在 —— camd 未建 socket（旧版 camd / 建文件被拒 / App 从未启动）"
    fi
fi

# ---------------------------------------------------------------- 4. 内核 socket 表
sep "4. 内核 unix socket 表（STATE 01=LISTEN / 03=CONNECTED）"
UNIX_ALL=$(grep 'camd.sock' /proc/net/unix 2>/dev/null)
LISTEN_INO=""; CONN_INO=""
if [ -n "$UNIX_ALL" ]; then
    echo "$UNIX_ALL" | sed 's/^/  /'
    LISTEN_INO=$(echo "$UNIX_ALL" | awk -v p="$SOCK" '$8==p && $6=="01"{print $7}' | head -1)
    CONN_INO=$(  echo "$UNIX_ALL" | awk -v p="$SOCK" '$8==p && $6=="03"{print $7}' | head -1)
    [ -n "$LISTEN_INO" ] && ok "LISTEN  inode=$LISTEN_INO" || ng "无 LISTEN（camd listen() 失败）"
    [ -n "$CONN_INO" ]   && ok "CONNECTED inode=$CONN_INO（camd 侧视角）" \
                         || wn "无 CONNECTED（camd 在听，无客户端）"
else
    ng "/proc/net/unix 里没有 camd.sock 记录"
fi

# ---------------------------------------------------------------- 5. 两端核对
sep "5. 两端核对"
if [ -n "$CPID" ]; then
    NFD=$(ls -l /proc/$CPID/fd 2>/dev/null | grep -c 'socket:\[')
    kv "camd socket fd" "$NFD （1=仅监听，≥2=已有客户端）"
    [ "$NFD" -ge 2 ] && ok "camd 已持有客户端连接" || wn "camd 只有监听 fd，没有客户端"
    [ -n "$CONN_INO" ] && ls -l /proc/$CPID/fd 2>/dev/null | grep -q "socket:\[$CONN_INO\]" \
        && ok "camd fd 中确认 inode $CONN_INO" || true
fi

# 关键：camd 用 SO_PEERCRED 记录的对端身份
PEER_UID=""; PEER_PID=""
if [ -f "$LOGF" ]; then
    PEER=$(grep 'sock peer' "$LOGF" 2>/dev/null | tail -1)
    if [ -n "$PEER" ]; then
        echo "  camd.log 最近一条：$PEER"
        PEER_UID=$(echo "$PEER" | sed -n 's/.*uid=\([0-9][0-9]*\).*/\1/p')
        PEER_PID=$(echo "$PEER" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p')
        kv "peer uid" "${PEER_UID:-?}"
        kv "peer pid" "${PEER_PID:-?}"
        if [ -n "$PEER_UID" ] && [ -n "$APP_UID" ] && [ "$PEER_UID" = "$APP_UID" ]; then
            ok "peer uid == App uid($APP_UID) —— 确认是 App 进程连上来的"
        elif [ "$PEER_UID" = "0" ]; then
            wn "peer uid=0 —— 连的是 root 进程（自测脚本），不代表 App 侧已放行"
        elif [ -n "$PEER_UID" ]; then
            wn "peer uid=$PEER_UID 与 App uid($APP_UID) 不符 —— 可能是别的进程"
        fi
    else
        wn "camd.log 无 'sock peer' 记录（camd 是旧版，或从未有连接）"
    fi
    LISTENLOG=$(grep -i 'sock listen' "$LOGF" 2>/dev/null | tail -1)
    [ -n "$LISTENLOG" ] && ok "camd.log 记录监听成功：$LISTENLOG" \
                        || wn "camd.log 无 'sock listen'"
else
    wn "$LOGF 不存在"
fi

APP_PIDS=$(pidof $PKG 2>/dev/null)
if [ -n "$APP_PIDS" ]; then
    ok "App 在跑 pid=$APP_PIDS"
    for p in $APP_PIDS; do
        kv "App 域($p)" "$(cat /proc/$p/attr/current 2>/dev/null)"
    done
    # App 侧是否有 socket fd（不比对 inode，只作旁证）
    APPSOCK=0
    for p in $APP_PIDS; do
        ls -l /proc/$p/fd 2>/dev/null | grep -q 'socket:\[' && APPSOCK=1
    done
    [ "$APPSOCK" = "1" ] && inf "App 持有 socket fd（旁证；Linux 进程普遍有多个 socket，单凭它不能定论）" \
                         || wn "App 没有任何 socket fd"
else
    wn "App 没在跑 —— 先打开一次模块再测"
fi

# ---------------------------------------------------------------- 6. App 侧日志
sep "6. App 侧连接日志（CamdSock）"
# 只取 tag 严格为 CamdSock 的行。某些机器（已实测 2410DPN6CC 的 Taplus.* 服务）
# 会把扫到的文本回显进 logcat，此时 grep 'CamdSock' 会把脚本自己的输出也捞回来。
LOGC=$(logcat -d -s CamdSock 2>/dev/null | grep -v -E '=====|^\s*\[' | tail -12)
if [ -z "$LOGC" ]; then
    LOGC=$(logcat -d 2>/dev/null | grep -i 'CamdSock' \
           | grep -v -E 'Taplus|=====|^\s*(\[|[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [EWI] [A-Za-z.]+: +[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [EWI] CamdSock)' \
           | tail -12)
fi
if [ -n "$LOGC" ]; then
    echo "$LOGC" | sed 's/^/  /'
    echo "$LOGC" | grep -q 'CamdSock: connected' && ok "出现过 connected" || true
    if echo "$LOGC" | grep -qi 'connect failed'; then
        if echo "$LOGC" | grep -qiE 'EACCES|Permission denied'; then
            ng "首次连接 EACCES —— SELinux 可能拦了 connectto（看第 7 段）"
        elif echo "$LOGC" | grep -qiE 'ENOENT|No such file'; then
            inf "首次失败 ENOENT —— camd 还没建 socket，属启动时序，非权限问题"
        elif echo "$LOGC" | grep -qi 'Connection refused'; then
            inf "首次失败 ECONNREFUSED —— socket 文件在但没 listen（camd 启动中或残留）"
        else
            wn "出现过 connect failed —— 异常类型未知，请看上面原文"
        fi
    fi
else
    wn "无 CamdSock 记录（App 未启动 / 日志已滚掉 / 装的是旧版模块）"
fi

# ---------------------------------------------------------------- 7. SELinux 拒绝
sep "7. SELinux 拒绝证据（只筛 socket 相关，避免误报）"
AVC=$(dmesg 2>/dev/null | grep 'avc:' | grep 'unix_stream_socket' | grep -i 'camd\.sock')
if [ -n "$AVC" ]; then
    echo "$AVC" | tail -10 | sed 's/^/  /'
    ng "camd.sock 的 unix_stream_socket 被拒 —— 方案A 在本机不可用"
else
    if dmesg >/dev/null 2>&1; then
        inf "dmesg 无 camd.sock 的 socket 拒绝记录"
    else
        wn "dmesg 不可读（dmesg_restrict=1）"
    fi
fi
# 顺带抓 camd 域相关的 file 类拒绝（建 socket 文件也要 file 权限）
if [ -n "$CAMD_DOM" ]; then
    AVC2=$(dmesg 2>/dev/null | grep 'avc:' | grep -F "$CAMD_DOM" | grep -iE 'file|dir' | grep -i 'camellia')
    [ -n "$AVC2" ] && { echo "  --- camd 域 file 类拒绝 ---"; echo "$AVC2" | tail -6 | sed 's/^/  /'; }
fi
for f in /data/misc/audit/audit.log /data/misc/audit/audit.log.1; do
    if [ -r "$f" ]; then
        A3=$(grep 'avc:' "$f" 2>/dev/null | grep -i 'camd\.sock' | tail -6)
        [ -n "$A3" ] && { echo "  --- $f ---"; echo "$A3" | sed 's/^/  /'; }
    fi
done

# ---------------------------------------------------------------- 8. 静态策略
sep "8. 静态策略（工具缺失属正常）"
if command -v sesearch >/dev/null 2>&1 && [ -n "$CAMD_DOM" ]; then
    DOM=${CAMD_DOM#u:r:}; DOM=${DOM%%:*}
    kv "camd 类型" "$DOM"
    sesearch --allow -s untrusted_app -t "$DOM" -c unix_stream_socket 2>/dev/null | head -8 | sed 's/^/  /'
else
    inf "设备上无 sesearch；确定性结论需拷 /sys/fs/selinux/policy 到 PC："
    inf "  sesearch --allow -s untrusted_app -t <camd域> -c unix_stream_socket -p connectto sepolicy"
fi

# ---------------------------------------------------------------- 9. 主动重连
if [ "$ACTIVE" = "1" ]; then
    sep "9. 主动重连测试（强停 App 再拉起）"
    OLD_PEER=$(grep 'sock peer' "$LOGF" 2>/dev/null | tail -1)
    OLD_INO=$(awk -v p="$SOCK" '$8==p && $6=="03"{print $7}' /proc/net/unix 2>/dev/null | head -1)
    kv "重启前 peer"  "${OLD_PEER:-无}"
    kv "旧连接 inode" "${OLD_INO:-无}"
    am force-stop $PKG >/dev/null 2>&1
    # 按「旧连接 inode 是否还挂在 camd 的 fd 上」判断回收，而不是数 fd 总数：
    # App 被强停后常会被系统/其他组件立刻拉起并重连，fd 总数会重新变 2，
    # 数总数会把「已正常回收 + 已重新连上」误判成「未回收」。
    GONE=0
    if [ -n "$CPID" ] && [ -n "$OLD_INO" ]; then
        i=1
        while [ $i -le 8 ]; do
            sleep 1
            if ! ls -l /proc/$CPID/fd 2>/dev/null | grep -q "socket:\[$OLD_INO\]"; then
                GONE=$i
                break
            fi
            i=$((i+1))
        done
        if [ "$GONE" != "0" ]; then
            ok "旧连接在 ${GONE}s 内被 camd 回收（EOF 处理正常）"
        else
            ng "8s 内旧连接仍挂在 camd fd 上（EOF 未处理，反复重启会泄漏 fd）"
        fi
    fi
    if pidof $PKG >/dev/null 2>&1; then
        inf "App 已自行复活（pid=$(pidof $PKG | awk '{print $1}')），跳过手动拉起"
        sleep 3
    else
        ACT=$(cmd package resolve-activity --brief $PKG 2>/dev/null | tail -1)
        if [ -n "$ACT" ]; then am start -n "$ACT" >/dev/null 2>&1
        else monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; fi
        sleep 10
    fi
    NEW_PEER=$(grep 'sock peer' "$LOGF" 2>/dev/null | tail -1)
    kv "重启后 peer" "${NEW_PEER:-无}"
    if [ -n "$NEW_PEER" ] && [ "$NEW_PEER" != "$OLD_PEER" ]; then
        ok "camd 接受了新连接 —— 自动重连正常"
    else
        ng "重启后没有新的 peer 记录 —— App 未重连（看第 6 段 errno）"
    fi
    logcat -d 2>/dev/null | grep -i CamdSock | tail -6 | sed 's/^/  /'
fi

# ---------------------------------------------------------------- 10. 结论
sep "结论"
if [ -z "$CPID" ] || [ "$HASSOCK" = "0" ]; then
    echo "  >>> FAIL（服务端没准备好，与 SELinux 无关）"
    echo "      装 2.31.2+ 新模块 → 打开一次 App → 再跑本脚本"
elif [ -z "$LISTEN_INO" ] || [ ! -S "$SOCK" ]; then
    echo "  >>> FAIL（camd 没成功 listen）"
    echo "      socket 文件不存在或无 LISTEN 记录：camd 的 bind/listen 被拒或旧版"
    echo "      查：logcat | grep camd ；  dmesg | grep avc | grep camellia"
elif [ -n "$PEER_UID" ] && [ "$PEER_UID" = "$APP_UID" ]; then
    echo "  >>> PASS —— 方案A 在本机可用"
    echo "      camd 在 listen，App 已连上并通过对端 uid 校验（$PEER_UID），SELinux 放行 connectto。"
    echo "      预期：状态推送走 socket，App 不再读 FUSE 状态文件，camd 不再每秒 fopen 取指令。"
    echo "      建议再跑一次 --active 确认强停能自动重连。"
elif [ -n "$CONN_INO" ]; then
    echo "  >>> PASS（弱证据）—— 有连接，但无法确认来自 App"
    [ -z "$PEER_UID" ] && echo "      camd 是旧版（无 SO_PEERCRED）；升级后可拿到 uid 级证据。"
    echo "      App pid=${APP_PIDS:-无}  App uid=${APP_UID:-?}"
else
    echo "  >>> FAIL（连接被拒）—— 大概率 SELinux，按下面抓原因："
    echo "      1) logcat -d | grep CamdSock"
    echo "         EACCES/EPERM          -> connectto 未放行"
    echo "         ECONNREFUSED          -> 旧 socket 文件残留，删 $SOCK 后重启 camd"
    echo "      2) dmesg | grep avc | grep unix_stream_socket"
    echo "      3) 放行后再测；或等 App 自动回退快通道（有 root 时面板显示「通道:快」）"
fi

echo
echo "  ---- 判据速查 ----"
echo "  camd.log 'sock peer uid=X' 且 X==App uid  -> PASS（最硬）"
echo "  camd.log 无 peer 行但 connected + fd>=2   -> PASS（弱，旧版 camd）"
echo "  camd.log peer uid=0                        -> 只是 root 自测，不能算放行"
echo "  仅 LISTEN + logcat EACCES                  -> FAIL（SELinux 拦 connectto）"
echo
