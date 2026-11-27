# probe_scheme_c.sh — 跨域推送通道可行性探测

## 用途

判定「让 camd / root 进程把事件**即时推送**给 App」的两条路线，在本机能不能成立。
只做只读探测，**不修改任何系统配置**。

## 用法

```sh
su -c 'sh /sdcard/projects/misharemt2/tools/probe_scheme_c.sh <App包名>'
# 例:
su -c 'sh /sdcard/projects/misharemt2/tools/probe_scheme_c.sh com.hyper.Camellia'
```

报告写到 `/sdcard/probe_scheme_c_<时间戳>.txt`，同时打到 stdout。

## 两条路线

| 代号 | 做法 | 传输层 |
|---|---|---|
| C-1 | root 侧 `listen()` on unix socket，App 侧 `connect()` | 无文件轮询，纯 socket |
| C-2 | root 写 `/data/data/<pkg>/files/`，App 侧 `FileObserver`(inotify) | 文件 + inotify |

## 7 个检测段

1. 设备与系统环境（型号/SDK/SELinux 模式/root 方案）
2. SELinux 域（root 域、App 域、MLS 类别）—— 决定 socket 能否跨进程
3. 策略静态检查（sesearch/ksud 能力、policy 文件位置、dmesg_restrict）
4. AVC 拒绝记录（这台机器实际被挡过什么，最有力实据）
5. unix socket 能力（nc 支持、同域自测、**socket 文件标签**）
6. App 私有目录写入实测（root 能否写、能否 chown 给 App uid）
7. 判定汇总（动态汇总第 5、6 段实测结果）

## 怎么读结论

**C-1（跨域 socket）**
- 第 3 段有 `allow <app域> <root域> unix_stream_socket connectto` → 静态可行
- 第 4 段有 `scontext=untrusted_app* tcontext=<root域>` 的 denied → 确认被挡
- 第 5 段的 socket 标签决定 App 能否直连：`shell_data_file` 通常 App 不可达；
  `app_data_file`（放 App 私有目录）才有机会免策略
- **必要条件 ≠ 充分条件**：connect 是否放行必须 App 侧实跑，脚本无法代替

**C-2（私有目录 + FileObserver）**
- 第 6 段 `[OK] root 写该目录成功` + `chown 成功` → 本机可用
- 这条路线不碰 SELinux 策略，可移植性最好
- 前提：`/data` 是 f2fs/ext4（inotify 可靠），不是 FUSE

## 关键局限

- **无 sesearch 时无法自动判定策略**。多数 ROM 不带 setools。可在 PC 上分析：
  `adb pull /sys/fs/selinux/policy` → `sesearch -A -s untrusted_app_34 -t ksu -c unix_stream_socket policy`
- **socket 只能同域自测**（root→root）。跨域必须由 untrusted_app 域的进程发起，
  最省事是用 Termux 或自写测试 APK。
- **本机 nc 是 toybox 0.8.13**：unix socket 监听必须写成 `nc -lU -s <path>`，
  只写 `nc -lU <path>` 会报 `-s must be provided if using -U with -L/-l`。
- **`ls -Z` 在本机只输出 2 列**（context + path），不是 GNU 的 7 列格式；
  取标签用 `awk '{print $1}'`。
- `/sdcard` 是 FUSE → 其上 `FileObserver` 不可靠，这正是 App 目前只能轮询的根本原因。

## 背景

camd 目前在有 root 时走 `/data/adb/camellia/cmd` + inotify，无 root 时走 FUSE 轮询。
两条路线都是为了把「事件通知」从轮询变成推送，把 1s 轮询的 FUSE 往返彻底去掉。
