# camd 定时快照（dsnap）设计 · 定稿

> 目标：给 camd 增加"桌面布局定时快照"，替代旧的 App 进程内 `SnapshotMgr`（su+tar）。
> 决策：① 旧引擎退役、旧快照保留兼容；② SQLite 嵌入 camd；③ 默认只备份桌面布局，其余按用户勾选；④ 落地后真机验证。

## 1. 模型（D：事件驱动 + 间隔下限）

不设时钟、不轮询。三免费事件叫醒，三重门控决定是否真备份：

```
叫醒(任一)：① 布局库被写(inotify)  ② 开机/解锁  ③ 30s 心跳兜底
门控(全满足)：内容真变了(digest) 且 距上次成功≥N天 且 当前不忙(!救砖)
→ VACUUM 一致性快照(~73KB)
```

- 稳态（布局没动）：每天 0 次读库、0 次写盘；心跳只判一个 `dirty` 布尔。
- "每 N 天"是间隔下限，不是准点打卡；备份晚几小时无影响。

## 2. 存储 / 配置 / 状态

- 快照：`/data/adb/camellia/dsnap/<YYYYMMDD_HHMMSS>/{meta,launcher.db,drawer.db,prefs.xml}`（与救砖 `bak/` 分离）。
- 配置：`/data/adb/camellia/dsnap.conf`（`enabled/days/hour/keep/kinds`，热读）。
- 状态：`/data/adb/camellia/state/dsnap_*`（last_ok / last_digest / stat_sig / list）。
- 日志：`/data/adb/camellia/state/dsnap.log`。

## 3. 变更检测（排版摘要，避免误触发）

只对 `favorites` 排版列（_id,title,intent,container,screen,cellX,cellY,spanX,spanY,itemType,appWidgetId,isShortcut,profileId,label,appWidgetProvider,iconPackage,iconResource）+ `screens` + 网格 prefs 做 SHA-256；**排除 launchCount/icon/itemFlags/sortMode/displayMode**。→ 点图标不改摘要，不触发备份。

## 4. 一致性快照

嵌入式 SQLite 对主库 `databases/launcher*.db` 与抽屉库 `databases/drawer/launcher*.db` 做 `VACUUM INTO`（只读打开 + busy_timeout），把 WAL 已提交内容一并读入，单文件一致。设备无 sqlite3，故 amalgamation 编进 camd。

## 5. 保留（只删自动份）

`keep` 默认 5；只淘汰 `src=auto` 的最旧份；`manual`/`pre-restore` 不删。

## 6. 与救砖协作（让路）

`rescue_busy = halt 存在 或 sr_ts>0 或 verify_ts>0`（直接读 rescue 的 state 文件）。命中即跳过、保留 dirty。

## 7. 恢复

pre-restore 自保 → 预检 → 停 `com.miui.home`（OS4 由 usap64/hyos_spawner 拉起，kill 后系统重启）→ 临时文件 rename 原子换库 + 清 `-wal/-shm` → restorecon → 拉起 home → 建议 VERIFY 窗失败回滚（待真机打磨）。

## 8. 集成点（对 camd 改动面）

| 文件 | 改动 |
|---|---|
| `tools/camellia_dsnap.c`（新增） | heartbeat/init/rearm/digest/vacuum/prune/restore/status/ui_apply |
| `tools/sqlite3.c/.h`（新增） | SQLite amalgamation 3.54.1 |
| `tools/camellia_rescue.c` | +extern；`rescue_tick` 每轮 `dsnap_heartbeat()`；`ui_dump` 追加 `dsnap_status_fields()`；`ui_apply` 转 `dsnap_ui_apply()` |
| `tools/build_camd.sh` | 加 `camellia_dsnap.c sqlite3.c`，`-DSQLITE_THREADSAFE=0 -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_DQS=0`，`-lpthread -ldl -lm` |

命令：`dsnap_run / dsnap_list / dsnap_del <id> / dsnap_restore <id> / dsnap_cfg enabled=1,days=3,hour=3,keep=5`。

## 9. 开销（真机实测基准）

| 项 | 频率 | 成本 |
|---|---|---|
| 心跳判 dirty | 每 30s（本就在醒） | 一个布尔，0 新增唤醒 |
| 摘要 SELECT | 仅 dirty+满周期+空闲（约每 N 天 1 次） | 209+41 行只读，亚毫秒~几 ms，零写 |
| VACUUM | 仅摘要真变 | ~73KB / <100ms |
| 视频/AOD | 默认关 | 实测壁纸目录 147MB，用户开才付 |

## 10. 测试

- 已完成：静态编译通过（aarch64 ELF，sqlite 内嵌）；`--status`、`--self-test` 全 PASS（rescue 未受影响）。
- 待真机（已授权）：create 行数(209/41)、幂等恢复、改库恢复+kill 桌面重读、让路、VACUUM 实际耗时、inotify 挂载。
