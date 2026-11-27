#!/system/bin/sh
# Camellia camd 自检脚本（Android sh 兼容，勿用 bash 特性）
# 用法：su -c "sh /sdcard/Download/camellia_selftest.sh"
PKG=com.hyper.Camellia
DATA=/data/data/$PKG/files
BRIDGE_DIR=/data/system/camellia_action
OK="✅"; NO="❌"; WARN="⚠️"
pass=0; fail=0

chk() { # chk 描述 条件结果(0=通过)
  if [ "$2" = 0 ]; then echo "  $OK $1"; pass=$((pass+1));
  else echo "  $NO $1"; fail=$((fail+1)); fi
}

echo "=========================================="
echo " Camellia camd 自检  $(date '+%Y-%m-%d %H:%M:%S')"
echo "=========================================="

# ---------- 1. APK ----------
echo
echo "[1] APK 安装"
VC=$(dumpsys package $PKG 2>/dev/null | grep -o 'versionCode=[0-9]*' | head -1 | cut -d= -f2)
VN=$(dumpsys package $PKG 2>/dev/null | grep -o 'versionName=[^ ]*' | head -1 | cut -d= -f2)
APK=$(pm path $PKG 2>/dev/null | head -1 | sed 's/package://')
echo "  版本: $VN (code=$VC)"
echo "  路径: $APK"
SZ=$(stat -c %s "$APK" 2>/dev/null)
echo "  体积: $SZ B（完整包应 >10MB，2MB 左右说明是 build.sh 不完整产物）"
[ -n "$SZ" ] && [ "$SZ" -gt 10000000 ] && chk "APK 为完整 Gradle 产物" 0 || chk "APK 体积异常（缺 Kotlin UI / assets）" 1
[ -f "$DATA/camd" ] && chk "assets/camd 已解压到私有目录" 0 || chk "files/camd 缺失（App 未启动过或 assets 丢失）" 1

# ---------- 2. 守护进程 ----------
echo
echo "[2] camd 进程"
P=$(pidof camd 2>/dev/null | awk '{print $1}')
NP=$(pidof camd 2>/dev/null | wc -w)
if [ -n "$P" ]; then
  chk "camd 运行中 (PID=$P)" 0
  [ "$NP" = 1 ] && chk "单实例（无重复进程）" 0 || chk "存在 $NP 个 camd 进程（多实例！）" 1
  NI=$(ps -o NI= -p $P 2>/dev/null | tr -d ' ')
  echo "  优先级 NI=$NI（-20 最佳）"
  [ "$NI" = "-20" ] && chk "renice -20 生效" 0 || chk "renice 未生效（当前 $NI）" 1
  EXE=$(readlink /proc/$P/exe 2>/dev/null)
  echo "  运行副本: $EXE"
  case "$EXE" in
    /dev/?*/.x/camd|/dev/*/camd) chk "运行副本位于 /dev 隐藏点" 0 ;;
    *) chk "运行副本未隐藏（$EXE）" 1 ;;
  esac
  case "$EXE" in *".x/camd") echo "  母本: $DATA/camd（App 目录，可被检测）" ;; esac
else
  chk "camd 未运行（先打开一次 App 授权 root）" 1
fi

# ---------- 3. 隐藏点权限 ----------
echo
echo "[3] 隐藏点"
H=$(readlink /proc/$P/exe 2>/dev/null | sed 's#/\.x/camd$##; s#/camd$##')
if [ -n "$H" ] && [ -d "$H" ]; then
  PERM=$(stat -c '%A %U:%G' "$H" 2>/dev/null)
  echo "  目录: $H  权限: $PERM"
  OWN=$(stat -c '%u' "$H" 2>/dev/null)
  [ "$OWN" = "0" ] && chk "隐藏点属主 root" 0 || chk "隐藏点属主非 root" 1
  MODE=$(stat -c '%a' "$H" 2>/dev/null)
  [ "$MODE" = "600" ] || [ "$MODE" = "700" ] && chk "隐藏点非 root 不可遍历（$MODE）" 0 || chk "隐藏点权限过宽（$MODE）" 1
  ISMNT=$(awk -v m="$H/.x" '$2==m{print $3}' /proc/mounts 2>/dev/null)
  [ -n "$ISMNT" ] && echo "  $H/.x 已挂载为 $ISMNT（独立文件系统）" || echo "  $H/.x 未单独挂载（非致命）"
else
  chk "无法定位隐藏点" 1
fi

# ---------- 4. 部署脚本 ----------
echo
echo "[4] 开机自启"
SD=/data/adb/service.d/camellia_daemon.sh
[ -f "$SD" ] && chk "service.d 脚本已写入 ($(stat -c %s $SD) B)" 0 || chk "service.d 脚本缺失（重启不会自启）" 1
[ -x "$SD" ] && chk "service.d 脚本可执行" 0 || chk "service.d 脚本无执行权限" 1
grep -q 'hide_prepare' "$SD" 2>/dev/null && chk "脚本含隐藏点逻辑" 0 || chk "脚本为旧版（无隐藏点）" 1
grep -q 'renice' "$SD" 2>/dev/null && chk "脚本含 renice 逻辑" 0 || chk "脚本缺 renice" 1
[ -f "$DATA/camellia_daemon.sh" ] && chk "App 私有目录脚本副本存在" 0 || chk "私有目录脚本副本缺失" 1

# ---------- 5. 桥目录与通路 ----------
echo
echo "[5] 桥通路"
if [ -d "$BRIDGE_DIR" ]; then
  BP=$(stat -c '%A %u:%g' "$BRIDGE_DIR" 2>/dev/null)
  echo "  目录: $BRIDGE_DIR  $BP"
  BG=$(stat -c '%g' "$BRIDGE_DIR" 2>/dev/null)
  [ "$BG" = "1000" ] && chk "目录组为 system(1000)" 0 || chk "目录组不是 system（system_server 会 EACCES）" 1
else
  chk "桥目录不存在" 1
fi
if [ -n "$P" ]; then
  R=$($EXE --ping "id" 2>&1)
  echo "$R" | grep -q 'uid=0' && chk "--ping id 回包正常" 0 || { chk "--ping id 失败" 1; echo "    输出: $R"; }
  R2=$($EXE --ping "getprop ro.product.model" 2>&1)
  echo "$R2" | grep -qE '[A-Za-z0-9]' && chk "--ping getprop 回包正常" 0 || chk "--ping getprop 无输出" 1
fi

# ---------- 6. App 保活面 ----------
echo
echo "[6] App 保活策略"
dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PKG" && chk "电池优化白名单" 0 || chk "不在电池优化白名单" 1
BUCKET=$(am get-standby-bucket $PKG 2>/dev/null | tail -1 | tr -dc '0-9')
echo "  待机桶: ${BUCKET:-未知}（10/active 最佳）"
[ "$BUCKET" = "10" ] && chk "待机桶 active" 0 || echo "  $WARN 待机桶非 active（非致命）"
RL=$(dumpsys activity 2>/dev/null | grep -m1 -o "restrictionLevel=[a-z]*")
echo "  后台限制: ${RL:-未知}"

# ---------- 7. 日志 ----------
echo
echo "[7] 日志"
[ -s "$DATA/camd.log" ] && { echo "  --- camd.log ---"; tail -5 "$DATA/camd.log" | sed 's/^/    /'; } || echo "  $WARN camd.log 为空"
[ -s "$DATA/camd.err.log" ] && { echo "  --- camd.err.log (历史, 轮转而来) ---"; tail -3 "$DATA/camd.err.log" | sed 's/^/    /'; }
SIG=$(cat "$DATA/camd.sig" 2>/dev/null); echo "  camd.sig: $SIG"

# ============================================================
#  以下为「急救引擎」诊断：camd 活着 ≠ 急救能用
# ============================================================

# ---------- 8. 模块激活 ----------
echo
echo "[8] 模块激活状态"
GS=$(settings get global camellia_guard_state 2>/dev/null)
echo "  guard_state = ${GS:-<空>}"
case "$GS" in
  hook=*)
    chk "system_server 侧 hook 已装入并上报" 0
    HOOKN=$(echo "$GS" | sed 's/.*hook=//; s/ .*//')
    echo "    hook 计数明细: $HOOKN（形如 1/1/1/1/1/1）"
    case "$HOOKN" in
      *0*) echo "    $WARN 明细含 0：有子 hook 未兜住，对应入口失效" ;;
    esac
    ;;
  *)
    chk "hook 未上报 → 模块没激活（LSPosed 里勾选作用域并重启 SystemUI）" 1
    ;;
esac
case "$GS" in
  *prop=ok*) chk "prop 兜底通道正常" 0 ;;
  *prop=*)   chk "prop 通道异常（$(echo "$GS" | sed 's/.*prop=//')）" 1 ;;
esac

# ---------- 9. 规则配置 ----------
echo
echo "[9] 规则配置（SharedPreferences）"
PREF=/data/data/$PKG/shared_prefs/settings.xml
# 从 prefs XML 取值：兼容 <boolean name= value=> / <string name=>v</> / <int name= value=>
gp() {
  awk -v k="$1" '{gsub(/></,">\n<"); n=split($0,a,"\n"); found=0;
    for(i=1;i<=n && !found;i++){ L=a[i];
      if(index(L,"name=\"" k "\"")>0){
        if(match(L,/value="[^"]*"/)){ print substr(L,RSTART+7,RLENGTH-8); found=1 }
        else if(match(L,/>[^<]+</)){ print substr(L,RSTART+1,RLENGTH-2); found=1 }
      }}}' "$PREF" 2>/dev/null
}
if [ -f "$PREF" ]; then
  chk "配置 XML 可读 ($(stat -c %s $PREF) B)" 0
  # v2.19 起规则配置单套共享，键名不带目标后缀
  R_ON=$(gp sysui_rule_on)
  R_WIN=$(gp sysui_rule_window)
  R_CNT=$(gp sysui_rule_count)
  R_TYP=$(gp sysui_rule_types)
  echo "  规则开关: ${R_ON:-未设置}   窗口: ${R_WIN:-600}s   阈值: ${R_CNT:-3}   触发类型: ${R_TYP:-java,native,anr}"
  if [ "$R_ON" = "true" ]; then
    chk "崩溃触发规则已开启" 0
  else
    chk "规则未开启（sysui_rule_on != true）→ 崩溃后不会自救" 1
  fi
  # APK 绑定键带目标后缀（tkeyPath），两个目标各一套
  for T in sysui plugin; do
    APKKEY="sysui_${T}_action_apk"
    AV=$(gp "$APKKEY")
    if [ -n "$AV" ]; then
      if [ -f "$AV" ]; then
        chk "[$T] 已绑定恢复用包且文件存在" 0
        echo "      $AV"
      else
        chk "[$T] 绑定路径失效（文件不存在）" 1
        echo "      $AV"
      fi
    else
      echo "  $WARN [$T] 未绑定回复用包（键 $APKKEY 为空）— 阶梯 L2/L3 会 SKIP 并顺延到 L4"
    fi
  done
else
  chk "prefs XML 不存在（App 设置页从未保存过）" 1
fi

# ---------- 10. 恢复用 APK ----------
echo
echo "[10] 阶梯救援素材（回复用包）"
for D in /data/user_de/0/$PKG/files/apk /data/system/camellia_bak/apk; do
  if [ -d "$D" ]; then
    N=$(ls -1 "$D" 2>/dev/null | wc -l)
    echo "  $D : $N 项"
    ls -l "$D" 2>/dev/null | sed -n '2,6p' | sed 's/^/    /'
  else
    echo "  $D : 不存在"
  fi
done
SYSUI_APK=$(find /data/user_de/0/$PKG/files/apk /data/system/camellia_bak/apk -name '*.apk' 2>/dev/null | head -1)
[ -n "$SYSUI_APK" ] && chk "存在可用恢复用 APK" 0 || { chk "无恢复用 APK → L2/L3 会全部 SKIP" 1; echo "    去 App「SystemUI 救援」页选一个包"; }

# ---------- 11. 运行态与历史动作 ----------
echo
echo "[11] 运行态 / 历史动作日志"
for T in sysui plugin; do
  echo "  ── 目标: $T ──"
  RS=$(settings get global camellia_rule_state_$T 2>/dev/null)
  EV=$(settings get global camellia_rule_events_$T 2>/dev/null)
  LD=$(settings get global sysui_ladder_$T 2>/dev/null)
  LA=$(settings get global camellia_action_log_$T 2>/dev/null)
  LAST=$(settings get global camellia_action_last_$T 2>/dev/null)
  echo "    规则状态: ${RS:-<空>}"
  echo "    事件计数: ${EV:-<空>}"
  echo "    阶梯状态: ${LD:-<空>}   (形如 {等级,上次动作时刻})"
  echo "    最近动作: ${LAST:-<空>}"
  # --- 自动判读 ---
  R_EV=$(echo "$RS" | sed -n 's/.*ev=\([0-9]*\).*/\1/p')
  R_CNT=$(echo "$RS" | sed -n 's/.*cnt=\([0-9]*\).*/\1/p')
  R_HIT=$(echo "$RS" | sed -n 's/.*hit=\([0-9]*\).*/\1/p')
  R_LV=$(echo "$RS" | sed -n 's/.*ladder=\([A-Za-z0-9]*\).*/\1/p')
  R_AGO=$(echo "$RS" | sed -n 's/.*ago=\([0-9]*\).*/\1/p')
  if [ -n "$R_EV" ]; then
    echo "    判读: 捕获崩溃事件 ev=$R_EV, 当前窗口计数 cnt=$R_CNT, 累计命中 hit=$R_HIT, 阶梯=$R_LV, 距上次动作 ${R_AGO}s"
    if [ "$R_EV" = "0" ]; then
      echo "    $WARN ev=0：自上次重置后未捕获任何崩溃。若期间确实崩过，说明 sys_server 侧抓取入口没接住"
    fi
    if [ -n "$R_LV" ] && [ "$R_LV" != "L0" ]; then
      STB=$(gp sysui_ladder_stable); [ -z "$STB" ] && STB=3600
      if [ -n "$R_AGO" ] && [ "$R_AGO" -lt "$STB" ]; then
        echo "    ℹ️  阶梯在 $R_LV，处于 ${STB}s 稳定观察期内（已 ${R_AGO}s/${STB}s）→ 未回落属正常，不代表失败"
      else
        echo "    ℹ️  阶梯停在 $R_LV 已超稳定窗口 ${STB}s：回落判断只在下一个崩溃事件到来时执行，"
        echo "        所以无新事件时该状态会一直挂着，属陈旧记录，不代表救援失败"
      fi
    fi
    if [ -n "$R_AGO" ] && [ "$R_AGO" -gt 3600 ]; then
      echo "    ℹ️  距上次动作 $((R_AGO/60)) 分钟，期间无新事件"
    fi
  fi
  if [ -z "$LA" ] || [ "$LA" = "" ]; then
    echo "    动作日志: <空>"
    echo "    $WARN 无任何动作记录 → 要么没触发过规则，要么 hook 没生效"
  else
    echo "    动作日志:"
    echo "$LA" | tr '|' '\n' | tail -8 | sed 's/^/      /'
    case "$LA" in
      *via_bridge*)  echo "    $OK 历史出现过 via_bridge（root 桥通路成功）" ;;
    esac
    case "$LA" in
      *"exec fail"*) echo "    $NO 历史出现 exec fail → 桥未接通，命令退化为 system_server 裸执行" ;;
    esac
    case "$LA" in
      *"bridge timeout"*) echo "    $WARN 历史出现 bridge timeout（命令超过桥超时，如 pm 类慢命令）" ;;
    esac
    case "$LA" in
      *"SKIP(未选恢复用包)"*) echo "    $WARN 历史出现「未选恢复用包」→ 阶梯缺素材" ;;
    esac
    case "$LA" in
      *persistent*) echo "    ℹ️  日志提到 persistent（SystemUI 是持久应用，不走覆盖安装）" ;;
    esac
  fi
done

# ---------- 12. 桥能否承接慢命令 ----------
echo
echo "[12] 桥对慢命令的支持（急救靠 pm install，需数秒）"
if [ -n "$P" ]; then
  T0=$(date +%s)
  R3=$($EXE --ping "pm path $PKG" 2>&1)
  T1=$(date +%s)
  echo "    pm path 耗时 $((T1-T0))s，回包: $(echo "$R3" | tr -d '\n' | cut -c1-80)"
  echo "$R3" | grep -q 'package:' && chk "桥能承接 pm 类慢命令" 0 || {
    chk "桥对 pm 命令回空 → 急救的 install/clear 会超时" 1
    echo "    原因多为 --ping 固定 8s 等待，而 pm 启动需 2-5s + 输出；"
    echo "    真实救援走 SysRescueGuard.runViaRootBridge，超时设置不同，需看 action_log 是否有 bridge timeout"
  }
fi

# ---------- 汇总 ----------
echo
echo "=========================================="
echo " 通过 $pass 项，失败 $fail 项"
[ "$fail" = 0 ] && echo " 结论：全部就位 ✅" || echo " 结论：有 $fail 项异常，见上方 ❌"
echo "=========================================="
