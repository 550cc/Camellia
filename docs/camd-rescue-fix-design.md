# camd 救援逻辑修复设计（基于现有代码 + 实测崩溃日志）

> 源码已备份 `.backup/20261127_094134/`。基于 v29。涉及文件：`tools/camellia_rescue.c`（主）、少量 `camellia_bridge.c` 不动。

## 一、当前 B 段(CE 解锁后)流程（rescue_tick 内，行号近似）
1. `pid_batch(np,pkgs,pids)`：target pid 变化 → `crash_hist_add()`（已加 self_restart 跳过）。
2. `since = now_ms - crash_window`；`base=sr_drop_max`；`if(base>since) since=base+1`；`sr_rebase` 冷却。
3. `dropbox_window(since,…)` → `db_cnt/db_attr/db_proc`（读 dropbox 崩溃正文归因 SYSUI/PLUGIN）。
4. `hang_probe`（每 60s，auto_fix 开）→ 命中 `crash_hist_add`。
5. `pid_cnt = crash_hist_count(up,crash_window)`。
6. **触发判据**：`crash = db_cnt>=thr || db_proc>=thr || pid_cnt>=thr`。
7. `culprit_attr(since)` 归因 → `ui_rescue_chain(pkg,…)`（备份坏包→卸更新/删覆盖层→`reboot_after_fix`）。
8. `reboot_after_fix`：`a_hist_reset(); crash_hist_reset(); apid_sysui=0; reset_pid_baselines; sr_ts=now_up; sr_count++; sr_total++; sr_drop_max=drop_max(); sr_rebase; 软重启`。
9. **OBS 观察窗**：`still = Σ find_ovl(pkgs[i])`（遍历**所有 target** 的 `/data/app` 目录）；`el<stable_sec 且 (still>0||crash)` → `nofx++ / OBS_FAIL / sr_ts=0`；`nofx>=nofx_max → HALT`。

## 二、实测崩溃日志暴露的问题
（场景：先测组件包崩溃→回退；再测系统界面本体→装错几次→HyperCeiler 重启触发崩溃）
- **P1｜OBS_FAIL 误判（关键）**：`OBS_FAIL el=30 ovl=1 crash=0 nofx=1`。组件包(plugin)已成功 `uninstall_update→/product` 回落、`VERIFY_OK`、`crash=0`，但 `find_ovl(plugin)` 仍见 `/data/app` 覆盖目录残留（卸更新后要等 PMS 清理/重启才消失）→ `still=1` → 误判“修复无效” → `nofx+1`。**连续两次即误 `HALT fix_not_effective`**。
- **P2｜OBS/触发 `still` 遍历所有 target**：两包同坏时，修好 A 会被 B 的覆盖层算成 A 无效（兄弟污染）。
- **P3｜纯 pid 触发**：`pid_cnt>=thr` 可**单独**触发修复（无需 dropbox）。良性/外部重启（如 HyperCeiler 干净重启 3 次、无 crash）会被误动作。
- **P4（中）｜窗口 floor 不够硬**：软重启后仍见到 `db=1`（可能是重启前残留或重启早期新 ANR）被计入；`sr_drop_max` 用 `drop_max()`（当时最大条目 ts），存在同秒竞态/无关条目泄漏。

## 三、根因
- **有效性判据选错代理**：用“覆盖层目录是否还在(find_ovl)”代表“修好没修好”。但“卸更新→回落系统版”后 `/data/app` 目录会残留一段时间 → 假失败；且遍历所有 target → 兄弟污染。正确代理应是“**被修复的那个包是否还在崩**”。
- **触发判据缺取证门**：pid 变化不区分“崩溃/正常/外部重启”，单靠它触发会误伤良性重启。真崩溃必落 dropbox（本次 db=3/5 即证）→ 应以 dropbox 为主证据，pid 仅作有佐证时的加速。
- **floor 用“当时最大条目”而非“修复时刻墙钟”**：同秒竞态/无关条目可能漏进窗口。

## 四、修复方案（最小增量，保留预算/halt/归因/DE-CE/备份优势）

### F1｜触发加“取证门”（治 P3）
```
int drop_ok = (db_cnt >= thr || db_proc >= thr);
int pid_ok  = (pid_cnt >= thr);
int crash   = drop_ok || (pid_ok && (db_cnt > 0 || db_proc > 0));  /* pid 需 dropbox 佐证 */
```
- 无任何 dropbox 证据时，纯 pid 变化（良性/外部重启）**不触发**。真崩溃必刷 dropbox。保留 pid 作加速/确认。
- dropbox 满盘极端情况后续用 eBPF 退出信号补（PoC 已验证 ksu 域可加载 tracepoint）。

### F2｜有效性改“被修复包自身是否再崩”（治 P1/P2）
- 动作时记录 `obs_pkg`（本次 `ui_rescue_chain` 修的包），`sr_ts=now`。
- OBS 窗口内计算 `eff_fail`：以 `since=sr_ts_ms` 调 `dropbox_window`，看**归因到 obs_pkg** 的条数 ≥ thr（或该包 pid 变化≥thr）。
- `if (el<stable_sec){ if(eff_fail){ nofx++/OBS_FAIL } }`；到达 `stable_sec` 且无该包新崩 → **成功，nofx=0、清 sr_ts/obs_pkg**。
- **彻底删除对 `find_ovl`（overlay 目录存在）的依赖**；不再遍历兄弟包。overlay 目录残留/等重启清理**不再算失败**。

### F3｜窗口 floor 用“修复时刻墙钟”（治 P4）
- `reboot_after_fix` / A 软重启收口：`sr_drop_max = max(now_ms_rt(), drop_max())`（用当前墙钟兜住同秒竞态）。
- `dropbox_window` 的 `since = max(now-window, sr_drop_max, sr_rebase)`（补 `now_ms` 兜底，已有 base/rebase 保留）。
- `INSTALL_FALLBACK`（删包回落）**不清零 sr_drop_max**（保留 floor）；仅“真·新装覆盖层”按新包重置（合理）。

### F4｜降噪（可选）
- `CRASH_WIN` 同 `(db,proc,pid)` 值去重打印；崩溃循环期合并 `PID_CHANGE` 日志。

### 不改
- sr_count/sr_total 预算、halt/nofx 熔断、A 段(DE)、dropbox 正文归因、self_restart、pidfd、dsnap 备份。

## 五、验证方法（真机）
- **F1**：不动包、用 HyperCeiler 干净重启 SystemUI 3 次（无崩溃）→ camd 应 **只记 PID_CHANGE 但不触发修复**；随后装真崩溃包 → dropbox 达阈 → 正常修复。
- **F2**：装坏组件包→修复→回落系统版；观察**不再出现 `OBS_FAIL ovl=1 crash=0`**（残留目录不算失败）；若该包真继续崩→应 `OBS_FAIL`（nofx）——两端都对。
- **F3**：修复软重启后，`CRASH_WIN` 不应把重启前旧条目计入；只在阈值达 3 才动作。
- **回归**：auto_fix=0 全程不动手；sr 预算/halt 仍生效；bridge/socket/backup 不受影响。

## 六、风险/回滚
- 均 rescue.c 内部小改；`auto_fix` 默认 0，验证期不动手，零爆炸风险。
- 源码快照在 `.backup/20261127_094134/`，可随时回滚。
