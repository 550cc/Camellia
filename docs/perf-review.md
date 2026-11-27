# camd 性能重审结论

- 日期：2026-11-06
- 范围：`tools/camellia_bridge.c`、`tools/camellia_sock.c`、`tools/camellia_rescue.c`；App 侧 `CamdRescue.java` / `CamdSocket.java`
- 结论：**计划清单 6 项全部已在源码实现并已部署运行；本次重审未发现需要新增实施的优化项。**

## 0. 一致性校验（源码 = 设备运行版本）

| 环节 | 路径 | 大小 | md5 |
|---|---|---|---|
| 编译产物 | `tools/.camd-tmp/camd` | 739168 | `68f5c30400b71339b1e899c14a20ee42` |
| APK 资源 | `assets/camd.bin`（gzip） | 339300 | gzip `a78918d…`，解压后 `68f5c304…` |
| 设备二进制 | `/data/adb/camellia/camd` | 739168 | `68f5c30400b71339b1e899c14a20ee42` |
| 运行进程 | PID 17985（14:34:56 起） | — | — |

复现命令：

```bash
gzip -dc assets/camd.bin | md5sum      # 应等于设备二进制 md5
md5sum /data/adb/camellia/camd
```

源码 mtime（11-04 17:34~17:36）早于编译（11-06 13:51）、部署（14:21）、APK 打包（14:34），
因此二进制包含全部源码改动。

## 1. 清单逐项核实

### 1.1 poll 降频 — `camellia_bridge.c:683-691`

```c
int to = (ncli > 0 || fast == 0) ? 5000 : 30000;
poll(pfds, nfd + ncli, to);
```

- 面板客户端在线 **或** 明确无 root（FUSE 是唯一命令通道）→ 5000ms
- 有 root / 已失权 → 30000ms
- 注释实测：原统一 3000ms，有 root 时白醒 28800 次/天
- inotify(caps) 命中 `cam_hit` → 立即 `rescue_poll_cmd()`，命令方向零轮询

### 1.2 sweep 阶梯降级 — `camellia_bridge.c:764-788`

- `fast != 0`（快通道就绪或已失权）→ `sw_tick % 10`（30s×10 = 300s 一次）
- 明确无 root → `step = sw_idle<2 ? 1 : (sw_idle<6 ? 2 : 4)`，`cam_hit` 复位 `sw_idle=0`
- `sweep_clean()` 独立维持 60s 级陈旧文件清理

### 1.3 状态文件按需写 — `camellia_rescue.c:5820-5960`（`ui_dump`）

- 5s 节流：`!force && up - last < 5` 直接返回
- 业务指纹 `fp`（不含 ts/uptime）与 `last_fp` 比较：变化立即写
- 未变化且距上次写 < `UI_BEAT_SEC`(45s) → 直接返回
- **无 socket 客户端在线** → 跳过全部状态采集，仅每 45s 重拼 ts 心跳（沿用 `last_fp`）
- 写后 `camd_sock_broadcast()` 推送，App 在线时无需读 FUSE 文件
- App 侧判活阈值 `FRESH_SEC`=180s，需 > 45s×2（注释已约束）

### 1.4 dropbox 事件驱动 — `camellia_rescue.c:517-560`、`2620-2770`

- `drop_max()`：目录 mtime/size 缓存，静止时 1 次 stat（~2µs）替代 readdir（0.3~2ms）
- `dropbox_scan_cache()` / `dropbox_window()`：指纹 = mtime + **tv_nsec** + size
  - 纳秒纳入指纹：避免「崩溃与扫描落在同一秒」导致该条漏进缓存、B 类故障救援失效
  - `DB_FORCE_RESCAN`(40 次) 兜底重扫
  - 缓存一律按 `since_ms=0` 全量填充，窄窗口由 ts 过滤满足，避免「先窄后宽」反复失效
- 注释实测：改前 193 文件 ≈1.6MB/tick ≈54KB/s，为稳态读量最大来源

### 1.5 pid 单次扫描 — `camellia_rescue.c:858-985`

- `pid_scan_many()` / `pid_batch()`：一次遍历 /proc 为全部 target 定位 pid，全部命中提前退出
- 原实现每包各扫一遍（3 包 ≈1000 次 read）
- `pid_cache_probe()`：正结果单点 cmdline 校验续期（1 次 read vs 全扫 ~500KB）；
  负结果受 `PIDQ_TTL` 约束；落盘 `spid_` 使 camd 重启后首轮零扫描命中

### 1.6 失 root 兜底轮询 — `camellia_bridge.c:730-747`

- `fast < 0`（失权）→ 每 2 轮（60s）重读 caps，恢复后回快通道
- `fast == 0`（明确无 root）+ `ncli > 0` → 每 4 轮（20s）
- `fast == 0` + 零客户端 → 阶梯 3s→5s

## 2. 重审额外确认（清单外的既有优化）

| 位置 | 机制 |
|---|---|
| `camellia_rescue.c:369` `st_set()` | 幂等短路：先读比较，值相同不落盘，避免 ext4 每 tick 脏页/journal |
| `camellia_rescue.c:5636` `ui_write_snaps()` | `strcmp(body,last)` 未变化不写 |
| `camellia_rescue.c` `cfg_load()` | 按 (mtime,size) 缓存，配置未改只 1 次 stat |
| `camellia_rescue.c` `shield_apply()` | 按 `shield_check`（默认 3600s）节流 |
| `camellia_rescue.c:6216` `rescue_write_status()` | = `ui_dump(0)`，与主循环同一入口，无重复写 |
| `camellia_sock.c:96-148` `camd_sock_listen()` | 失败 5s/60s 重试，避免每轮 socket()/bind() 空转 |

## 3. 残余成本（设计内，无需动作）

- **面板在线时** `ui_dump` 每 5s 全量采集：`snap_collect()` 遍历 bak 目录 + 每项 stat `base.apk`（≤64 项）。
  面板关闭即降级为 45s 心跳，成本归零。
- App 侧 `CamdRescue.status()` 每 2s `parse()` 一次 ~3KB 快照（socket 内存推送，零 IO）；
  十余个辅助方法（`halt/phase/srCount/nofxCount/ver/cfgOk/aMod/bakN…`）全项目无热路径调用，
  面板刷新只在 2s 循环里取一次 `status()`。
- `rescue_tick()` 默认 `tick_sec=30`，其中 `st_set` 全部走幂等短路，实际落盘仅状态变化时。

## 4. 结论

清单 6 项均为「已完成」状态，源码注释保留实测数据与踩坑记录（纳秒指纹、全量填充缓存、
3000→30000 唤醒次数、193 文件/1.6MB per tick 等）。

若后续仍要压开销，唯一有量级的候选是「面板在线时 5s 全量采集」，但那是面板可读性的代价，
不建议改动。**当前版本无需性能改动，保持现状。**
