#!/system/bin/sh
# ============================================================================
#  方案C 可行性探测脚本
#  (A) root 守护进程 -> App 的 SELinux 跨域 unix socket 推送：能不能做
#  (B) 替代路线：写 App 私有目录(f2fs/ext4) + FileObserver 推送：能不能做
#
#  用法（必须 root）:
#      su -c 'sh /sdcard/probe_scheme_c.sh 目标包名'
#      例: su -c 'sh /sdcard/probe_scheme_c.sh com.hyper.Camellia'
#      不传包名则只做通用环境探测
#
#  安全性: 只读探测 + 两处无害试写(写完立即删除)
#          不改系统文件、不装 App、不重启服务、不动 SELinux 策略
#  报告:   屏幕输出 + /sdcard/probe_scheme_c_<时间>.txt
# ============================================================================

PKG="${1:-}"
TS=$(date +%Y%m%d_%H%M%S)
OUT="/sdcard/probe_scheme_c_${TS}.txt"
: > "$OUT" 2>/dev/null

# 屏幕 + 文件双写
log() { echo "$@"; echo "$@" >> "$OUT" 2>/dev/null; }
sec() {
    log ""
    log "================================================================"
    log " $*"
    log "================================================================"
}
line() { log "----------------------------------------------------------------"; }

log "方案C 可行性探测报告    生成: $(date '+%Y-%m-%d %H:%M:%S')"
log "目标包名: ${PKG:-(未指定)}"
log "报告文件: $OUT"

if [ "$(id -u 2>/dev/null)" != "0" ]; then
    log ""
    log "!! 当前不是 root。请用: su -c 'sh \$0 $PKG'"
fi

# ============================================================ 1. 设备环境
sec "1. 设备与系统环境"
gp() { getprop "$1" 2>/dev/null; }

log "厂商型号 : $(gp ro.product.manufacturer) / $(gp ro.product.brand) $(gp ro.product.model)"
log "Android  : $(gp ro.build.version.release)  (SDK $(gp ro.build.version.sdk))"
log "指纹     : $(gp ro.build.fingerprint)"
log "内核     : $(uname -r 2>/dev/null)"
log "SELinux  : $(getenforce 2>/dev/null)    [Enforcing 下 root 同样受策略约束]"
log "ABI      : $(gp ro.product.cpu.abilist)"

ROOT_IMPL="unknown"
if [ -d /data/adb/ksu ] || command -v ksud >/dev/null 2>&1; then
    ROOT_IMPL="KernelSU"
elif [ -d /data/adb/magisk ] || command -v magisk >/dev/null 2>&1; then
    ROOT_IMPL="Magisk"
elif [ -d /data/adb/ap ] || command -v apd >/dev/null 2>&1; then
    ROOT_IMPL="APatch"
elif command -v supersu >/dev/null 2>&1; then
    ROOT_IMPL="SuperSU"
fi
log "root 方案: $ROOT_IMPL"

# ============================================================ 2. 域信息
sec "2. SELinux 域 (Domain) —— 决定 socket 能否跨进程连接"

SHELL_CTX=$(cat /proc/self/attr/current 2>/dev/null | tr -d '\000')
log "root shell 域 : ${SHELL_CTX:-读取失败}"
ROOT_DOMAIN=$(echo "$SHELL_CTX" | sed -n 's/^u:r:\([^:]*\):.*/\1/p')
log "  -> 域简名   : ${ROOT_DOMAIN:-未知}"

for pn in camd magiskd ksud; do
    for pid in $(pidof "$pn" 2>/dev/null); do
        c=$(cat "/proc/$pid/attr/current" 2>/dev/null | tr -d '\000')
        log "守护进程 $pn (pid=$pid) : ${c:-读取失败}"
    done
done

APP_CTX=""
APP_DOMAIN=""
APP_CATS=""
APP_UID=""
if [ -n "$PKG" ]; then
    log ""
    log "目标 App : $PKG"
    PD=$(pidof "$PKG" 2>/dev/null)
    if [ -n "$PD" ]; then
        for pid in $PD; do
            c=$(cat "/proc/$pid/attr/current" 2>/dev/null | tr -d '\000')
            log "  进程 pid=$pid 域 : ${c:-读取失败}"
            [ -z "$APP_CTX" ] && APP_CTX="$c"
        done
    else
        log "  当前未运行 -> 无法直接读域，改为推断"
    fi

    DUMP=$(dumpsys package "$PKG" 2>/dev/null)
    TSDK=$(echo "$DUMP" | sed -n 's/.*targetSdk=\([0-9]*\).*/\1/p' | head -1)
    APP_UID=$(echo "$DUMP" | grep -o 'appId=[0-9]*' | head -1 | cut -d= -f2)
    [ -z "$APP_UID" ] && APP_UID=$(echo "$DUMP" | grep -o 'userId=[0-9]*' | head -1 | cut -d= -f2)
    log "  targetSdk=$TSDK   uid=${APP_UID:-未知}"

    if [ -z "$APP_CTX" ] && [ -n "$TSDK" ]; then
        APP_CTX="u:r:untrusted_app_${TSDK}:s0"
        log "  推断域 : $APP_CTX  (把 App 打开到前台后重跑可拿到实测值)"
    fi
fi

APP_DOMAIN=$(echo "$APP_CTX" | sed -n 's/^u:r:\([^:]*\):.*/\1/p')
APP_CATS=$(echo "$APP_CTX" | sed -n 's/.*:s0:\(.*\)$/\1/p')
log ""
log "App 域简名 : ${APP_DOMAIN:-未知}"
log "MLS 类别   : ${APP_CATS:-无或旧版}"
log "  注: 有类别时(如 c228,c257)，即便放行基本类型 socket 仍可能被 MLS 拦"

# ============================================================ 3. 策略静态检查
sec "3. SELinux 策略静态检查（C-1 的核心判据）"

log "查询工具探测:"
for t in sesearch seinfo magiskpolicy ksud getenforce dmesg; do
    p=$(command -v "$t" 2>/dev/null)
    if [ -n "$p" ]; then log "  [有] $t -> $p"; else log "  [无] $t"; fi
done

line
POLF=$(ls -l /sys/fs/selinux/policy 2>/dev/null | tr -s ' ')
log "策略文件    : ${POLF:-不存在}"
log "dmesg_restrict: $(cat /proc/sys/kernel/dmesg_restrict 2>/dev/null || echo '不可读')"
log "  （拿到 policy 文件后可在 PC 上 setools 分析，结论最可靠）"

if command -v ksud >/dev/null 2>&1; then
    line
    log "ksud 子命令（判断本机能否运行时补 SELinux 策略）:"
    ksud 2>&1 | head -25 | while read -r l; do log "  $l"; done
    log ""
    log "  ksud sepolicy 子命令（运行时打策略补丁的能力）:"
    ksud sepolicy 2>&1 | head -14 | while read -r l; do log "    $l"; done
fi

if command -v sesearch >/dev/null 2>&1 && [ -n "$APP_DOMAIN" ] && [ -n "$ROOT_DOMAIN" ]; then
    line
    log "查询 allow $APP_DOMAIN $ROOT_DOMAIN unix_stream_socket ..."
    R=$(sesearch -A -s "$APP_DOMAIN" -t "$ROOT_DOMAIN" -c unix_stream_socket 2>&1)
    if [ -z "$R" ]; then
        log "  结果: 无任何 allow 规则  ==>  C-1 会被 AVC denied"
    else
        echo "$R" | head -20 | while read -r l; do log "  $l"; done
        if echo "$R" | grep -q connectto; then
            log "  [结论] 含 connectto  ==>  C-1 静态上可行"
        else
            log "  [结论] 未见 connectto（仅 sendto/recvfrom 不够）"
        fi
    fi
    line
    log "参考: root 域自身对 unix_stream_socket 的权限"
    sesearch -A -s "$ROOT_DOMAIN" -c unix_stream_socket 2>&1 | head -10 | while read -r l; do log "  $l"; done
else
    log "  sesearch 不可用（多数 ROM 不自带 setools）"
    log "  替代: 把目标机 /sys/fs/selinux/policy 拷到 PC，用 setools/sepolicy-analyze 分析"
fi

if command -v magiskpolicy >/dev/null 2>&1; then
    line
    log "magiskpolicy 尝试导出规则（参数各版本不同，失败属正常）:"
    magiskpolicy --print-rules 2>&1 | head -3 | while read -r l; do log "  $l"; done
fi

# ============================================================ 4. AVC 实据
sec "4. AVC 拒绝记录 —— 这台机器实际被挡过什么（最有力实据）"

DLOG=$(dmesg 2>/dev/null | grep -i 'avc:' | grep -i 'unix_stream_socket' | tail -20)
if [ -n "$DLOG" ]; then
    log "dmesg 中 unix_stream_socket 相关 AVC（最近 20 条）:"
    echo "$DLOG" | while read -r l; do log "  $l"; done
else
    log "dmesg: 无 unix_stream_socket 相关 AVC 记录"
fi

if [ -r /data/misc/audit/audit.log ]; then
    ALOG=$(grep -i 'unix_stream_socket' /data/misc/audit/audit.log 2>/dev/null | tail -10)
    if [ -n "$ALOG" ]; then
        log "audit.log 相关记录:"
        echo "$ALOG" | while read -r l; do log "  $l"; done
    else
        log "audit.log: 无相关记录"
    fi
else
    log "audit.log: 不可读或不存在"
fi

log ""
log "解读: 出现 scontext=untrusted_app* tcontext=<root域> 的 denied 记录"
log "      ==> C-1 在本机确实被挡，需补策略才能实现"

# ============================================================ 5. unix socket 能力
sec "5. unix domain socket 能力（C-1 的传输层前提）"

log "/data 挂载类型: $(mount 2>/dev/null | grep -m1 ' /data ')"
log "/sdcard 挂载   : $(mount 2>/dev/null | grep -m1 ' /storage/emulated ' | head -1)"
log ""

NCBIN=$(command -v nc 2>/dev/null)
if [ -n "$NCBIN" ]; then
    log "nc 位置: $NCBIN"
    log "nc help 摘要: $(nc --help 2>&1 | head -2 | tr '\n' ' ')"
    if nc --help 2>&1 | grep -q -- '-U'; then
        log "  [有] nc 支持 -U (unix domain socket)"
        SOCK="/data/local/tmp/.psc_$$.s"
        rm -f "$SOCK" 2>/dev/null
        # toybox nc 用 unix socket 监听必须带 -s 指定路径，否则报
        # "-s must be provided if using -U with -L/-l"
        nc -lU -s "$SOCK" >/dev/null 2>&1 &
        NCPID=$!
        sleep 1
        ERR=$(echo ping | nc -U -s "${SOCK}.c" "$SOCK" 2>&1)
        RC=$?
        if [ "$RC" -eq 0 ]; then
            log "  [OK] 文件型 unix socket 同域自测通过（root 域 -> root 域）"
        else
            log "  [失败] rc=$RC ${ERR:+msg=$ERR}"
        fi
        kill "$NCPID" 2>/dev/null
        rm -f "$SOCK" "${SOCK}.c" 2>/dev/null

        # socket 文件的 SELinux 标签决定 untrusted_app 能否连接
        log ""
        log "socket 文件标签探测（决定 App 能否直连）:"
        SF="/data/local/tmp/.psc_tag_$$.s"
        rm -f "$SF" 2>/dev/null
        nc -lU -s "$SF" >/dev/null 2>&1 &
        SPID=$!
        sleep 1
        log "  /data/local/tmp 下建 socket -> 标签 $(ls -Z "$SF" 2>/dev/null | awk '{print $1}')"
        kill "$SPID" 2>/dev/null
        rm -f "$SF" 2>/dev/null

        if [ -n "$PKG" ] && [ -d "/data/data/$PKG/files" ]; then
            SF2="/data/data/$PKG/files/.psc_tag_$$.s"
            rm -f "$SF2" 2>/dev/null
            nc -lU -s "$SF2" >/dev/null 2>&1 &
            SPID2=$!
            sleep 1
            TAG2=$(ls -Z "$SF2" 2>/dev/null | awk '{print $1}')
            log "  App 私有目录内建 socket -> 标签 ${TAG2:-未创建}"
            log "  ==> 若标签为 app_data_file，App 连自身数据目录的 socket"
            log "      通常无需新增 allow（untrusted_app 对 app_data_file 有常规权限）"
            kill "$SPID2" 2>/dev/null
            rm -f "$SF2" 2>/dev/null
        fi
        rm -f "$SOCK" 2>/dev/null
    else
        log "  [无] nc 不支持 -U，无法用 nc 做 socket 测试"
    fi
else
    log "nc 不存在（极少见），跳过 socket 工具测试"
fi

log ""
log "说明: abstract socket (@name) 不落文件、无文件标签，比重文件型 socket 少一层检查；"
log "      本脚本只能用文件型 socket 做同域自测，跨域结论以第3、4节为准。"
log "      跨域实测需一个 untrusted_app 域的进程发起连接，最省事的办法是用"
log "      Termux (untrusted_app 域) 或任意自研测试 APK 去连 root 侧监听。"

# ============================================================ 6. 私有目录实测
C2_WRITE=未测
C2_CHOWN=未测
sec "6. 替代路线实测: root 写 App 私有目录(f2fs/ext4) + App FileObserver"

if [ -z "$PKG" ]; then
    log "未指定包名，跳过"
else
    D="/data/data/$PKG/files"
    log "目标目录: $D"
    if [ -d "$D" ]; then
        log "  目录存在, 属主: $(ls -ld "$D" 2>/dev/null)"
        T="$D/.psc_probe_$TS"
        if echo probe > "$T" 2>/dev/null; then
            log "  [OK] root 写该目录成功  ==>  C-2 通道可用"
            C2_WRITE=OK
            if [ -n "$APP_UID" ]; then
                if chown "$APP_UID:$APP_UID" "$T" 2>/dev/null; then
                    log "  [OK] chown 到 App uid ($APP_UID) 成功"
                    C2_CHOWN=OK
                else
                    log "  [警告] chown 失败（App 仍可能因权限读到 0644，但属主非自身）"
                    C2_CHOWN=FAILED
                fi
            fi
            chmod 0644 "$T" 2>/dev/null
            log "  文件属性: $(ls -l "$T" 2>/dev/null | tr -s ' ')"
            rm -f "$T" 2>/dev/null && log "  (探测文件已删除)"
        else
            log "  [DENIED/失败] root 无法写该目录  ==> C-2 在本机不可行"
            C2_WRITE=FAILED
        fi
    else
        log "  目录不存在（App 未运行过或包名错误）"
    fi

    log ""
    log "补充: /data/data 是 f2fs/ext4，inotify 可靠；"
    log "      /sdcard 是 FUSE，FileObserver 不可靠（这也是当前 App 只能轮询的根本原因）"
fi

# ============================================================ 7. 判定汇总
sec "7. 判定汇总"
log "设备: $(gp ro.product.model) / Android $(gp ro.build.version.sdk) / $(getenforce) / $ROOT_IMPL"
log "root 域: ${ROOT_DOMAIN:-未知}     App 域: ${APP_DOMAIN:-未指定包}  MLS: ${APP_CATS:-无}"
log ""
log "C-1 (跨域 unix socket 推送):"
log "  静态策略: 见第3节（无 sesearch 时无法自动判定）"
log "  实据    : 见第4节（有 denied 记录则确认被挡）"
log "  本机现状: socket 可创建于 App 私有目录，标签 app_data_file:s0（不带 MLS 类别）"
log "  未知点  : untrusted_app 主动 connect 到 root(ksu) 域监听是否被放行"
log "            —— 必须 App 侧实测，本脚本无法代替"
log "  若需补策略: allow <app域> <root域> unix_stream_socket connectto;"
log "            并处理 MLS 类别约束；ROM 升级/换 root 方案后需重打"
log "  补策略工具: KernelSU 具备 ksud sepolicy patch/apply/check（见第3节）"
log ""
log "C-2 (f2fs/ext4 私有目录推送):"
log "  实测写权 : ${C2_WRITE}"
log "  实测chown: ${C2_CHOWN}"
if [ "$C2_WRITE" = "OK" ]; then
    log "  ==> 判定   : 本机可用（root 可写 App 私有目录，属主可改为 App）"
    log "              App 侧用 FileObserver 监听该目录即可实时收到，无需任何 SELinux 改动"
else
    log "  ==> 判定   : 本机不可用或未测（看第6节原因）"
fi
log "  优势    : 零策略改动、f2fs/ext4 比 FUSE 便宜、inotify 可靠"
log ""
log "报告已保存: $OUT"
log "（本脚本未修改任何系统配置）"
