#!/system/bin/sh
# =============================================================================
# Camellia root 桥
#
# 背景：Camellia 在 system_server 里跑（LSPosed），它的 SELinux 域不允许
# fork 执行 /system/bin/cmd、/system/bin/pm、/system/bin/su
# （实测 error=13 Permission denied / ENOENT），所以自救阶梯的 L1~L4 全部动不了。
#
# 做法：system_server 把要执行的命令写进 /data/system/camellia_action/req.<id>，
#       本脚本以 root 常驻轮询，执行后把输出写到 res.<id>，system_server 读回。
#
# 三个必须遵守的点（都是踩过的坑）：
#   1. 每条请求必须后台并发执行 —— 否则一次 49MB 的 pm install 会把队列堵死，
#      后续请求全部超时，看起来像「桥没工作」。
#   2. 必须 setsid 脱离启动者的进程组 —— 否则被 su 会话结束时连带 kill，
#      表现为「重启后桥静默消失」。
#   3. 要有看门狗 —— loop 意外退出后自动拉起，否则一次意外就得重启手机。
#
# 部署：cp 到 /data/adb/service.d/camellia_action.sh && chmod 755
# 卸载：删掉该文件并 kill 掉相关进程即可（无其他副作用）
# =============================================================================

D=/data/system/camellia_action
BAK=/data/system/camellia_bak
SELF=/data/adb/service.d/camellia_action.sh

case "$1" in
    # ---------- 入口：派生出脱离进程组的看门狗后立刻退出，不阻塞开机 ----------
    "")
        setsid /system/bin/sh "$SELF" watchdog >/dev/null 2>&1 &
        exit 0
        ;;
    # ---------- 看门狗：loop 掉了就拉起来 ----------
    watchdog)
        while true; do
            if ! pgrep -f "camellia_action.sh loop" >/dev/null 2>&1; then
                setsid /system/bin/sh "$SELF" loop >/dev/null 2>&1 &
            fi
            sleep 60
        done
        ;;
    # ---------- 主循环：并发处理请求 ----------
    loop)
        ;;
    *)
        exit 0
        ;;
esac

mkdir -p "$D" 2>/dev/null
chmod 0777 "$D" 2>/dev/null
# 备份目录要允许 system_server（system uid）写入
if [ -d "$BAK" ]; then
    chmod -R 0777 "$BAK" 2>/dev/null
fi

while true; do
    for f in "$D"/req.*; do
        [ -f "$f" ] || continue
        case "$f" in
            *.tmp|*.run) continue ;;
        esac
        # 抢占式重命名，避免与下一轮循环或另一实例重复执行同一请求
        run="$f.run"
        mv "$f" "$run" 2>/dev/null || continue
        id=${run##*/req.}
        id=${id%.run}
        out="$D/res.$id"
        cmd=$(cat "$run" 2>/dev/null)
        rm -f "$run"
        (
            /system/bin/sh -c "$cmd" >"$out" 2>&1
            echo " (exit=$?)" >>"$out"
            chmod 0666 "$out" 2>/dev/null
        ) &
    done
    sleep 1
done
