#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 Camellia root 桥「一键部署脚本」（把 C 二进制 gzip+base64 内嵌进 sh）。

用法：
    python3 tools/gen_bridge_deploy.py <编译好的 camellia_bridge 二进制> [输出路径]

为什么内嵌二进制：
    设备上没有 gcc/交叉编译器，也没有可靠的联网下包条件；部署脚本必须是
    单文件自包含，用户在手机上一句 `su -c "sh xxx.sh"` 就能装好。
    gzip -9 后约 300KB，base64 约 400KB，手机文件管理器/su 传输都没问题。
"""

import base64
import gzip
import io
import os
import sys

TEMPLATE = r'''#!/system/bin/sh
# =============================================================================
# Camellia root 桥 v2（C 原生守护进程）—— 一键部署 + 自检
#
# 用法（必须有 root）：
#     su -c "sh /sdcard/Download/camellia_bridge_deploy.sh"
#
# 为什么换成 C 而不是原来的 shell 桥：
#   1. inotify 事件驱动，响应 <10ms；旧 shell 桥 sleep 1 轮询，一次 pm install
#      就把队列堵死，后续请求全超时，看起来像「桥没工作」。
#   2. 内置校验：HMAC-SHA256 签名 + 120s 时间窗 + nonce 去重 + 命令白名单。
#      旧桥目录 0777、内容就是裸 shell 命令 —— 任何 App 写个 req 就能拿到 root。
#   3. 静态链接单文件，不依赖设备上 busybox/toybox 的版本差异。
# =============================================================================
set -u

BIN=/data/local/tmp/camellia_bridge
D=/data/system/camellia_action
BAK=/data/system/camellia_bak
SVC=/data/adb/service.d/camellia_bridge.sh
OLD_SVC=/data/adb/service.d/camellia_action.sh
B64=/data/local/tmp/ccb.b64
EXPECT=__SIZE__

echo "======================================================"
echo "  Camellia root 桥 v2 (C 守护进程) 部署"
echo "======================================================"

# ---------------- 1. 环境 ----------------
echo "[1/6] 环境检查"
U=$(id -u 2>/dev/null)
if [ "$U" != "0" ]; then
    echo "  ❌ 不是 root (uid=$U)"
    echo "     请用: su -c \"sh /sdcard/Download/camellia_bridge_deploy.sh\""
    exit 1
fi
echo "  ✅ root (uid=0)"
if [ ! -d /data/adb ]; then
    echo "  ❌ /data/adb 不存在 —— 没装 Magisk/KernelSU"
    exit 1
fi
echo "  ✅ /data/adb 存在"

# 旧 shell 桥必须清掉：它轮询的是同一个 req 目录，会把带签名的请求
# （"CCB1 <ts> <nonce> <sig>" 那行）当命令执行，并抢在 C 桥之前把请求删掉，
# 表现就是「C 桥偶尔不响应」这种随机故障。
if [ -f "$OLD_SVC" ]; then
    pkill -f "service.d/camellia_action.sh" 2>/dev/null
    pkill -f "camellia_action.sh loop" 2>/dev/null
    pkill -f "camellia_action.sh watchdog" 2>/dev/null
    rm -f "$OLD_SVC"
    echo "  ✅ 已移除旧 shell 桥 $OLD_SVC（避免抢请求）"
fi

# ---------------- 2. 释放二进制 ----------------
echo "[2/6] 释放二进制 -> $BIN"
cat > "$B64" <<'CCB_B64_EOF'
__B64__
CCB_B64_EOF
rm -f "$BIN"
base64 -d "$B64" 2>/dev/null | gzip -d > "$BIN" 2>/dev/null
rm -f "$B64"
if [ ! -s "$BIN" ]; then
    echo "  ❌ 解码失败（base64/gzip 不可用？）"
    exit 1
fi
chmod 755 "$BIN"
SZ=$(ls -l "$BIN" | awk '{print $5}')
if [ "$SZ" != "$EXPECT" ]; then
    echo "  ❌ 二进制大小不符: $SZ != $EXPECT"
    exit 1
fi
echo "  ✅ $SZ 字节, mode 755"

# ---------------- 3. 目录 ----------------
echo "[3/6] 准备桥目录（root:system 0770 —— system_server 必须能进）"
mkdir -p "$D" 2>/dev/null
chown 0:1000 "$D" 2>/dev/null
chmod 0770 "$D" 2>/dev/null
mkdir -p "$BAK" 2>/dev/null
chown 0:1000 "$BAK" 2>/dev/null
chmod 0770 "$BAK" 2>/dev/null
ls -ld "$D" | sed 's/^/  /'

# ---------------- 4. 开机自启 ----------------
echo "[4/6] 安装开机自启 -> $SVC"
mkdir -p /data/adb/service.d 2>/dev/null
cat > "$SVC" <<'SVC_EOF'
#!/system/bin/sh
# Camellia root 桥 watchdog：桥不在就拉起（60s 一次，最多守 2 小时）
B=/data/local/tmp/camellia_bridge
i=0
while [ "$i" -lt 120 ]; do
    if [ ! -x "$B" ]; then
        sleep 5
        i=$((i + 1))
        continue
    fi
    if ! pgrep -x camellia_bridge >/dev/null 2>&1; then
        setsid "$B" </dev/null >/dev/null 2>&1 &
    fi
    sleep 60
done
SVC_EOF
chmod 755 "$SVC" 2>/dev/null
ls -l "$SVC" | sed 's/^/  /'

# ---------------- 5. 启动 ----------------
echo "[5/6] 启动桥进程"
pkill -x camellia_bridge 2>/dev/null
sleep 1
setsid "$BIN" </dev/null >/dev/null 2>&1 &
sleep 2
N=$(pgrep -x camellia_bridge 2>/dev/null | wc -l | tr -d ' ')
echo "  实例数=$N"
if [ "$N" = "0" ]; then
    echo "  ⚠️ 未启动，前台试跑一次看报错："
    "$BIN" 2>&1 | head -5
fi

# ---------------- 6. 自检 ----------------
echo "[6/6] 自检"
"$BIN" --check
RC=$?

echo
if [ "$RC" = "0" ]; then
    echo "======================================================"
    echo "  ✅ 部署完成，通路已验证"
    echo "======================================================"
    echo "接下来："
    echo "  1) 重启手机（让 Camellia 模块加载带签名的桥客户端）"
    echo "  2) 触发一次 SystemUI 崩溃，看 action_log 出现 via_bridge"
    echo "  3) 随时复查通路: $BIN --check"
else
    echo "======================================================"
    echo "  ⚠️ 自检未通过，把上面的输出发我"
    echo "======================================================"
fi
exit $RC
'''


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    binpath = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else "/sdcard/Download/camellia_bridge_deploy.sh"

    raw = open(binpath, "rb").read()
    b64 = base64.b64encode(gzip.compress(raw, 9)).decode()
    block = "\n".join(b64[i:i + 76] for i in range(0, len(b64), 76))

    text = TEMPLATE.replace("__B64__", block).replace("__SIZE__", str(len(raw)))
    io.open(out, "w", encoding="utf-8").write(text)
    print("written %s (%d bytes, binary %d bytes)" % (out, os.path.getsize(out), len(raw)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
