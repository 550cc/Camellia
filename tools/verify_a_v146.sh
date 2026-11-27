#!/system/bin/sh
# Camellia A 方案 v146 验证（需 root 执行）
# 用途：整机重启后确认 native patch / 信标 / system_server 判定三环是否打通
L=/sdcard/Android/media/com.miui.home/camellia_launcher.log
B=/sdcard/Android/media/com.miui.home/cam_a_state

echo "===== 1. 桌面进程 ====="
P=$(pidof com.miui.home); echo "pidof com.miui.home = ${P:-<未运行>}"
echo

echo "===== 2. 信标内容 ====="
if [ -f "$B" ]; then
  echo "mtime: $(stat -c '%y' "$B" 2>/dev/null)"
  sed -n '1,22p' "$B"
else
  echo "❌ 信标不存在 —— native 未写入"
fi
echo

echo "===== 3. 信标 pid vs 当前桌面 pid ====="
BP=$(grep '^pid=' "$B" 2>/dev/null | cut -d= -f2)
if [ -n "$BP" ] && [ "$BP" = "$P" ]; then
  echo "✅ 一致（信标 pid=$BP）"
else
  echo "❌ 不一致（信标 pid=${BP:-无} / 桌面 ${P:-无}）"
fi
echo

echo "===== 4. A 判定日志（关键） ====="
logcat -d -s Camellia-Launcher 2>/dev/null | grep -a "A 判定\|A state\|beacon" | tail -15
echo "--- 若出现「A 判定=生效」= 成功；「暂不可用」= 还在用 B 兜底，上滑一次后再看"
echo

echo "===== 5. launcher.log 尾部 ====="
tail -12 "$L" 2>/dev/null
echo

echo "===== 6. 桌面进程内模块映射数 ====="
if [ -n "$P" ]; then
  N=$(grep -c -i "camellia" "/proc/$P/maps" 2>/dev/null)
  echo "camellia 映射条数 = ${N:-0}（>0 表示 libhomeprobe 已注入桌面）"
else
  echo "桌面未运行，跳过"
fi
echo

echo "===== 7. 当前 boot_id（信标 boot= 应等于前 8 位） ====="
head -c 8 /proc/sys/kernel/random/boot_id
echo
