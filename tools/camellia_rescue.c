/*
 * camellia_rescue.c —— camd 的「急救自愈」模块
 *
 * 定位：camd 是 root 常驻进程，SystemUI 崩了它照样活着。所以「发现坏包 →
 *       备份 → 删覆盖 → 软重启」这套动作必须由 camd 自主完成，不能依赖
 *       Java 侧（Java 逻辑跑在 SystemUI 进程里，SystemUI 死了它一起死）。
 *       LSPosed 模块 UI 只负责写配置，不再参与决策。
 *
 * 核心判据（全部来自文件系统/内核，不需要 pm、不需要 fork abx2xml）：
 *   1. 覆盖层存在性：扫 /data/app/<~~随机base64>/<pkg>-<随机base64>/ —— 随机名
 *      必须靠扫目录，不能拼固定路径。命中即说明该包被更新过。
 *   2. 是否在崩：dropbox 新增 system_app_crash|anr / system_server*，
 *      或 SystemUI 的 pid 发生变化（进程重启过）。
 *   3. 修 = 「有覆盖」且「在崩」，两者缺一不可 —— 否则会把正常更新删掉。
 *
 * 熔断（防止越救越坏）：
 *   - 单次 boot（boot_id 不变）内软重启计数 >= sr_max(默认2，硬顶3) → HALT
 *   - 连续 nofx_max(默认2) 次「删了没效果」→ HALT
 *   - 软重启后进入 stable_sec(默认600s) 观察期；期内又崩/覆盖又出现 → 记无效
 *   - HALT 后 camd 拒绝一切自动修复，直到手动 --reset-halt 或硬重启
 *     （boot_id 变化即视为用户介入，自动解除）
 *
 * 配置：/data/adb/camellia/config（行式 key=value，camd 每 tick 热读，
 *       UI 写完立即生效，无需重启 camd）
 * 状态：/data/adb/camellia/state/
 *
 * 编译：与 camellia_bridge.c 一起静态链接进 camd
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
#include <time.h>
#include <signal.h>
#include <poll.h>
#include <sys/syscall.h>
#include <bpf/libbpf.h>
#include <bpf/bpf.h>
#include "bpf_blob.h"
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>

/* camd 版本：由 build_camd.sh 从 build.gradle 的 versionName 注入，与 App 同源。
 * 面板要显示「camd 与 App 是否同一个版本」，硬编码在 C 里必然漂移
 * （改了 App 忘了改 camd → 面板显示一致但实际错位，比不显示更坏）。 */
#ifndef CAMD_VER
#define CAMD_VER "dev"
#endif

/* UI 状态文件心跳间隔：业务内容不变时，多久重写一次 camd_status.txt。
 * App 侧靠 ts 新鲜度判断 camd 是否活着（CamdRescue.FRESH_SEC），该阈值
 * 必须显著大于本值，否则会把正常运行的 camd 误报成「进程未运行」。
 * 注意：必须放在 #ifndef CAMD_VER 之外 —— 构建时用 -DCAMD_VER 注入版本，
 * 那样整个 #ifndef 块都会被跳过。 */
#define UI_BEAT_SEC 45

/* ------------------------------------------------------------------ 路径 */
#define CAM_DIR_DEFAULT "/data/adb/camellia"

/*
 * 运行时根目录。默认 /data/adb/camellia，可用环境变量 CAM_DIR 覆盖。
 * 目的：调试与回归时把 config / state / bak 全部落到独立目录，
 * 既读不到也写不到生产配置，避免调试动作影响真机救援行为。
 */
/* pidfd 加速 tick：目标进程退出→置位→下一次 rescue_tick 不再节流，立即判定 */
static volatile int g_force_tick = 0;
void rescue_kick(void){ g_force_tick = 1; }

static const char *cam_root(void)
{
    static char r[256];
    if (!r[0]) {
        const char *e = getenv("CAM_DIR");
        if (e && e[0] == '/') snprintf(r, sizeof(r), "%s", e);
        else                  snprintf(r, sizeof(r), "%s", CAM_DIR_DEFAULT);
    }
    return r;
}

/* 拼接根目录下路径，suffix 形如 "config" / "state" / "state/rescue.log" */
static void cam_path(const char *suffix, char *out, size_t n)
{
    snprintf(out, n, "%s/%s", cam_root(), suffix);
}

/* 供 bridge 主循环用：拿 camd 根目录去拼 caps / 挂 inotify watch。
 * bridge 是独立编译单元，static 的 cam_root() 它看不到。 */
const char *cam_dir_root(void)
{
    return cam_root();
}

/* socket 通道（camellia_sock.c）：ui_dump 检测到状态变化后主动推送，
 * App 侧不再轮询 FUSE 状态文件。 */
extern void camd_sock_broadcast(const char *payload, size_t len);
/* 在线客户端数：ui_dump 据此决定「完整采集」还是「只发心跳」。 */
extern int camd_sock_active(void);
/* 定时快照模块（camellia_dsnap.c） */
extern int  dsnap_heartbeat(void);
extern int  dsnap_status_fields(char *dst, size_t cap);
extern int  dsnap_ui_apply(const char *k, const char *v);
#define DROPBOX_DIR   "/data/system/dropbox"
#define APP_DIR       "/data/app"

#define HARD_SR_MAX   3          /* sr_max 可配上限（UI 不可超过） */
/* 单 boot 内「软重启/修复尝试」的绝对上限。
 * 它和 sr_count 是两层账：
 *   sr_count —— 两次装包之间的配额，用户装新包就归还（频繁测试友好）
 *   sr_total —— 单 boot 绝对累计，只有硬重启/手动复位/观察期满稳定才归零
 * 没有 sr_total 的话，sr_count 一旦能被装包清零，熔断就完全失效：
 * 任何会反复触发安装事件的循环都能无限软重启（越救越坏）。 */
#define BOOT_SR_TOTAL_MAX 10

/* 卸更新成功后的观察窗秒数。
 * 卸更新会让 PMS kill 掉加载该包的过程（plugin 与 SystemUI 共进程），
 * 由 Zygote 重启后回落 ROM 内置版 —— 整机软重启并非必需。但若 PMS 只
 * 标记生效、没真 kill 当前进程，崩溃会继续，而此刻覆盖层已删、B 段再无
 * 可删物，就会卡死在黑屏循环里。所以先观察，自愈则免重启，仍崩才兜底。 */
#define VERIFY_SEC    30

/* 软重启冷却窗（毫秒）。
 *
 * 软重启本身会制造崩溃：kill system_server 后 zygote 重拉全部 system 进程，
 * 期间被拉起的进程若再崩，会落一条 system_app_crash（时间戳在本动作之后）。
 * 这段余波不是「修复无效」的证据。基线取 max(动作时刻水位, 动作时刻+本窗)，
 * 把余波排除在 OBS / VERIFY 判据之外 —— 否则明明修好了也会被判 FAIL，
 * nofx_count 虚增两次即 HALT 整轮停摆。
 *
 * 取值依据：SystemUI 冷启动（zygote fork + 类加载 + 首帧）实测 4~6s。
 * 越大越安全但盲区越久；6s 对 180s 观察窗占 3.3%，对 600s 稳定窗占 1%。
 * 真坏包会持续崩，冷却窗过后照样判得出。 */
#define SR_REBASE_MS  6000L

/* 监视对象固定为这两个包，不再开放配置。
 *
 * 归因（culprit_attr）与处置名单（cand[]）本来就是按这两个包写死的：
 * 一旦允许用户改 target，就会出现「监控的是 A，真动手删的是 B」的错位，
 * 而且日志上看不出异常。多包配置的收益（换别的系统包来救）也不存在 ——
 * 急救链只认这两个包的覆盖层。故废除 target 配置项，固定为常量。 */
#define FIXED_TARGET  "com.android.systemui,miui.systemui.plugin"

/* 备份保留份数：用户可选 0~5，默认 3。
 * 0 = 不保留（只做即时备份用于本次还原，prune 时全清）。 */
#define BAK_KEEP_DEFAULT 3
#define BAK_KEEP_MAX     5

/* ------------------------------------------------------------------ 配置 */
typedef struct {
    int  auto_fix;               /* 0=只诊断不动作（默认），1=允许自动修复 */
    int  ebpf;                   /* eBPF 检测：1=自动(可用即启用,默认)，0=用户关闭(回退) */
    int  tick_sec;               /* 自检周期 */
    int  bak_keep;               /* 每个包保留备份份数 */
    int  sr_max;                 /* 单 boot 内允许的软重启次数（<=3） */
    int  nofx_max;               /* 连续无效上限 */
    long stable_sec;             /* 软重启后观察期 */
    char target[256];            /* 监视目标，固定为 FIXED_TARGET（不再可配） */
    char bak_dir[256];
    char reboot_cmd[128];
    char user_apk[256];          /* B 段兜底安装包（用户事先备好的好包），空=不用 */
    /*
     * 保命屏蔽：不依赖 LSPosed 也能拦住系统自救。
     * 与 auto_fix 无关 —— 屏蔽是「别让系统误判」，不是修复动作。
     */
    int  shield;                 /* 1=屏蔽系统自救（默认开） */
    int  shield_check;           /* 屏蔽周期校验间隔秒，防 ROM 改回 */
    int  shield_limit;           /* process_crash_count_limit 目标值 */

    /* 崩溃判定窗口：窗口内累计 N 次才动手。
     * 真机实测 SystemUI 起不来时是「3 秒一崩」的循环（Zygote fork → 加载注入
     * → 抛异常 → 进程死 → 再 fork）。单次 pid 变化就修，会撞上软重启还没
     * 完成、进程正在重启的空窗，救完立刻又判崩 → 抖动。窗口计数把「偶发
     * 一次崩溃（可能是别的原因）」和「起不来的循环崩」区分开。 */
    int  crash_window_sec;       /* 计数窗口秒（默认 60） */
    int  crash_threshold;        /* 窗口内次数阈值（默认 3） */

    /* 安装事件监听：1=开（默认）。
     * 前置条件是「CE 已解锁 + 界面/组件首次正常起来」—— DE 阶段界面本来
     * 就起不来，监听没有意义。开启后每 tick 比较两个目标包的 apk_stamp
     * （codePath + base.apk mtime/size）：戳变 = 发生了安装/替换事件。
     * 事件一到就重置崩溃计数重新判定，确认循环崩后先落坏包快照再还原，
     * 避免「用户刚装了个坏包，camd 却还拿着安装前的旧结论不动作」。 */
    int  watch_install;

    /* A 段（DE 阶段）崩溃循环判定窗口：与 B 段完全解耦。
     * 为什么必须独立：B 段窗口默认 60s，判的是「解锁后装包导致的重启循环」；
     * A 段判的是「开机后界面起不来」，而 SystemUI 首次启动本身就要 10~30s，
     * 启动过程中 pid 波动是正常现象。共用 60s/3 次会把正常启动判成崩溃。
     *
     * 引入这个窗口是为了补掉旧判据的漏：旧 A 段只用 pidof 看「进程在不在」，
     * 崩溃循环时 pidof 大概率有值（刚被 Zygote 拉起），于是被误放过 ——
     * 而这恰恰是黑屏循环里最常见的情形。
     * 新判据两条互补：
     *   pidof=0 持续存在          → 起不来
     *   窗口内 pid 变化 >= 阈值   → 崩溃循环
     * 两者都不满足（进程稳定活着）→ 不救，避免把「渲染卡死」误当崩溃删包。 */
    int  a_window_sec;           /* A 段计数窗口秒（默认 180） */
    int  a_circ_thr;             /* 窗口内 pid 变化次数阈值（默认 3） */
} cfg_t;

/* 下面几个实现位于文件后半段，先声明以便 CLI / tick 提前引用 */
static int cfg_save(const cfg_t *c);
void cfg_ensure(void);
static int pm_install(const char *apk, const char *expect, int dry, char *res, size_t resn);
static int pm_uninstall_update(const char *pkg, int dry, char *res, size_t resn);
static void reboot_after_fix(cfg_t *c);
/* 前向声明：do_soft_reboot / reboot_after_fix 定义在 now_ms_rt 实现之前，
 * 但软重启收口处需要它（crash_hist_reset 的声明下面已有，不重复）。 */
static long now_ms_rt(void);
static int ui_rescue_chain(cfg_t *c, const char *pkg, char *note, size_t nn,
                           int do_reboot);
static int shield_apply(const cfg_t *c);
static int shield_off(void);
static int cmd_shield(int argc, char **argv);
static long apk_stamp(const char *code_path);
static long apk_stamp_pkg(const char *pkg);
static void crash_hist_reset(void);
static int  code_path_is_overlay(const char *cp);
static int  pkg_has_overlay(const char *pkg);
static int  watch_install_tick(cfg_t *c, long up, char pkgs[][160], int np);
static void a_hist_reset(void);
static int is_crash_entry(const char *nm);
static int cmd_watch(int argc, char **argv);
static int cmd_snap(int argc, char **argv);

static void cfg_defaults(cfg_t *c)
{
    memset(c, 0, sizeof(*c));
    c->auto_fix   = 0;           /* 保守：默认只诊断，不动手 */
    c->ebpf       = 1;           /* eBPF：自动(可用即启用)，用户可置0关闭回退 */
    c->tick_sec   = 30;
    c->bak_keep   = BAK_KEEP_DEFAULT;
    c->sr_max     = 2;
    c->nofx_max   = 2;
    c->stable_sec = 600;
    c->shield       = 1;         /* 保命优先：默认开 */
    c->shield_check = 3600;      /* 每小时校验一次 */
    c->shield_limit = 999999;    /* 远超 AMS 默认 12，判据永不达成 */
    c->crash_window_sec = 60;    /* 解锁前 60s 内崩 3 次即判真崩溃 */
    c->crash_threshold  = 3;
    c->watch_install    = 1;     /* 默认开启：装了坏包就该被盯住 */
    c->a_window_sec     = 180;   /* DE 阶段 SystemUI 启动要 10~30s，窗口必须给足 */
    c->a_circ_thr       = 3;     /* 窗口内 pid 变 3 次才算崩溃循环，偶发一次不动手 */
    snprintf(c->target, sizeof(c->target), "%s", FIXED_TARGET);
    cam_path("bak", c->bak_dir, sizeof(c->bak_dir));
    snprintf(c->reboot_cmd, sizeof(c->reboot_cmd), "killall system_server");
}

/* 去掉首尾空白 */
static char *trim(char *s)
{
    char *e;
    while (*s == ' ' || *s == '\t' || *s == '\r' || *s == '\n') s++;
    e = s + strlen(s);
    while (e > s && (e[-1] == ' ' || e[-1] == '\t' || e[-1] == '\r' || e[-1] == '\n'))
        *--e = 0;
    return s;
}

/* 热读配置：文件不存在/字段缺失一律回落默认值，绝不因配置坏掉而罢工 */
static void cfg_load(cfg_t *c)
{
    FILE *f;
    char line[512];
    char k[128], v[384];
    char cp[320];
    struct stat sb;
    long mt = -2, sz = -1;
    /*
     * (mtime,size) 缓存：bridge 每 1~3s、ui_dump 每 5s 都会调本函数，而
     * config 几乎从不改动。文件不存在时 mt 取 -2 也照样命中缓存，避免反复
     * 走 open 失败路径。用户改 config 会同时改 mtime/size，能立即感知。
     */
    static cfg_t cached;
    static int cached_ok = 0;
    static long cached_mt = -3, cached_sz = -1;

    cfg_defaults(c);
    cam_path("config", cp, sizeof(cp));
    if (stat(cp, &sb) == 0) { mt = (long) sb.st_mtime; sz = (long) sb.st_size; }
    if (cached_ok && mt == cached_mt && sz == cached_sz) { *c = cached; return; }
    cached_mt = mt;
    cached_sz = sz;
    cached_ok = 1;

    f = fopen(cp, "r");
    if (!f) { cached = *c; return; }
    while (fgets(line, sizeof(line), f)) {
        char *p = trim(line);
        char *eq;
        if (!p[0] || p[0] == '#') continue;
        eq = strchr(p, '=');
        if (!eq) continue;
        *eq = 0;
        snprintf(k, sizeof(k), "%s", trim(p));
        snprintf(v, sizeof(v), "%s", trim(eq + 1));
        if      (!strcmp(k, "auto_fix"))   c->auto_fix   = atoi(v) ? 1 : 0;
        else if (!strcmp(k, "ebpf"))       c->ebpf       = atoi(v) ? 1 : 0;
        else if (!strcmp(k, "tick_sec"))   { int x = atoi(v); if (x >= 5 && x <= 3600) c->tick_sec = x; }
        else if (!strcmp(k, "bak_keep"))   { int x = atoi(v); if (x >= 0 && x <= BAK_KEEP_MAX) c->bak_keep = x; }
        else if (!strcmp(k, "sr_max"))     { int x = atoi(v); if (x >= 1 && x <= HARD_SR_MAX) c->sr_max = x; }
        else if (!strcmp(k, "nofx_max"))   { int x = atoi(v); if (x >= 1 && x <= 10) c->nofx_max = x; }
        else if (!strcmp(k, "stable_sec")) { long x = atol(v); if (x >= 60 && x <= 86400) c->stable_sec = x; }
        /* target 已固定（FIXED_TARGET）。旧 config 里残留的这行只做忽略：
         * 若在此赋值，老配置文件会把已废除的多包设置又带回来。 */
        else if (!strcmp(k, "target"))     { (void) v; }
        else if (!strcmp(k, "bak_dir"))    snprintf(c->bak_dir, sizeof(c->bak_dir), "%s", v);
        else if (!strcmp(k, "reboot_cmd")) snprintf(c->reboot_cmd, sizeof(c->reboot_cmd), "%s", v);
        else if (!strcmp(k, "user_apk"))   snprintf(c->user_apk, sizeof(c->user_apk), "%s", v);
        else if (!strcmp(k, "shield"))       c->shield       = atoi(v) ? 1 : 0;
        else if (!strcmp(k, "shield_check")) { int x = atoi(v); if (x >= 60 && x <= 86400) c->shield_check = x; }
        else if (!strcmp(k, "shield_limit")) { int x = atoi(v); if (x >= 13 && x <= 9999999) c->shield_limit = x; }
        else if (!strcmp(k, "crash_window_sec")) { int x = atoi(v); if (x >= 10 && x <= 3600) c->crash_window_sec = x; }
        else if (!strcmp(k, "crash_threshold"))  { int x = atoi(v); if (x >= 1 && x <= 20) c->crash_threshold = x; }
        else if (!strcmp(k, "a_window_sec"))     { int x = atoi(v); if (x >= 60 && x <= 1800) c->a_window_sec = x; }
        else if (!strcmp(k, "a_circ_thr"))       { int x = atoi(v); if (x >= 2 && x <= 20) c->a_circ_thr = x; }
        else if (!strcmp(k, "watch_install"))    c->watch_install = atoi(v) ? 1 : 0;
    }
    fclose(f);
    cached = *c;
}

/* ------------------------------------------------------------- 基础工具 */
static long now_up(void)
{
    FILE *f = fopen("/proc/uptime", "r");
    long s = 0;
    if (!f) return 0;
    if (fscanf(f, "%ld", &s) != 1) s = 0;
    fclose(f);
    return s;
}

/* boot_id：区分「软重启（system_server 重启，boot_id 不变）」与
 * 「硬重启（kernel 重启，boot_id 变化）」。熔断计数只在同一 boot 内累计；
 * 用户一手动重启就说明情况变了，计数应当清零。 */
static int boot_id(char *out, size_t n)
{
    FILE *f = fopen("/proc/sys/kernel/random/boot_id", "r");
    char buf[80];
    if (!f) { snprintf(out, n, "unknown"); return 0; }
    if (!fgets(buf, sizeof(buf), f)) { fclose(f); snprintf(out, n, "unknown"); return 0; }
    fclose(f);
    snprintf(out, n, "%s", trim(buf));
    return 1;
}

static int mkdir_p2(const char *path, mode_t mode)
{
    char tmp[512];
    char *p;
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (p = tmp + 1; *p; p++) {
        if (*p == '/') { *p = 0; mkdir(tmp, mode); *p = '/'; }
    }
    return mkdir(tmp, mode);
}

/* --------------------------------------------------------------- 状态存取 */
static void st_path(const char *k, char *out, size_t n)
{
    snprintf(out, n, "%s/state/%s", cam_root(), k);
}

static long st_get(const char *k)
{
    char p[512];
    FILE *f;
    long v = 0;
    st_path(k, p, sizeof(p));
    f = fopen(p, "r");
    if (!f) return 0;
    if (fscanf(f, "%ld", &v) != 1) v = 0;
    fclose(f);
    return v;
}

static void st_set(const char *k, long v)
{
    char p[512];
    FILE *f;
    st_path(k, p, sizeof(p));
    /*
     * 幂等短路：本函数在高频路径上每 tick 被调多次（PID 采样写入、标记位、
     * 时间戳），值绝大多数时候与上次相同。无条件 O_TRUNC 重写会让 ext4
     * 每 tick 产生一批脏页和 journal 提交（一天上万次），而"写相同的值"与
     * "不写"在语义上完全等价。多读一次（page cache 命中，几乎零成本）换掉
     * 一次真实落盘。
     */
    f = fopen(p, "r");
    if (f) {
        long cur = 0;
        int got = (fscanf(f, "%ld", &cur) == 1);
        fclose(f);
        if (got && cur == v) return;
    }
    f = fopen(p, "w");
    if (!f) return;
    fprintf(f, "%ld\n", v);
    fclose(f);
}

static int st_str(const char *k, char *out, size_t n)
{
    char p[512];
    char buf[600];
    FILE *f;
    st_path(k, p, sizeof(p));
    f = fopen(p, "r");
    if (!f) { out[0] = 0; return 0; }
    if (!fgets(buf, sizeof(buf), f)) { fclose(f); out[0] = 0; return 0; }
    fclose(f);
    /*
     * 必须用独立 buf 再拷到 out：
     * snprintf(out, n, "%s", trim(out)) 这种自引用在 bionic 下会被清空，
     * 表现为「文件内容明明正确，读出来却是空串」，进而把同一次 boot
     * 误判成新 boot，熔断计数被反复清零。
     */
    snprintf(out, n, "%s", trim(buf));
    return out[0] ? 1 : 0;
}

static void st_wstr(const char *k, const char *v)
{
    char p[512];
    FILE *f;
    st_path(k, p, sizeof(p));
    f = fopen(p, "w");
    if (!f) return;
    fprintf(f, "%s\n", v);
    fclose(f);
}

static void st_del(const char *k)
{
    char p[512];
    st_path(k, p, sizeof(p));
    unlink(p);
}

/* 追加急救日志（只增不改，作为事后复盘依据） */
static void rlog(const char *fmt, ...)
{
    char buf[512];
    char sp[320], lp[384];
    int n;
    va_list ap;
    int fd;
    cam_path("state", sp, sizeof(sp));
    cam_path("state/rescue.log", lp, sizeof(lp));
    mkdir_p2(sp, 0700);
    fd = open(lp, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd < 0) return;
    n = snprintf(buf, sizeof(buf), "[up=%ld] ", now_up());
    va_start(ap, fmt);
    n += vsnprintf(buf + n, sizeof(buf) - n, fmt, ap);
    va_end(ap);
    if (n > (int)sizeof(buf) - 2) n = (int)sizeof(buf) - 2;
    buf[n++] = '\n';
    if (write(fd, buf, (size_t)n) < 0) { /* 忽略 */ }
    close(fd);
}

/* ------------------------------------------------------- 覆盖层 / 崩溃探测 */

/*
 * 找 pkg 在 /data/app 下的覆盖目录。
 * 布局（Android 11+）：/data/app/<~~随机base64>/<pkg>-<随机base64>/
 * 也兼容旧式扁平布局 /data/app/<pkg>-<suffix>/。
 * 命中返回 1 并写出目录绝对路径。
 */
static int find_ovl(const char *pkg, char *out, size_t n)
{
    DIR *d;
    struct dirent *de;
    size_t pl = strlen(pkg);
    int found = 0;

    if (!pl) return 0;
    d = opendir(APP_DIR);
    if (!d) return 0;

    while (!found && (de = readdir(d)) != NULL) {
        char sub[512];
        struct stat st;
        DIR *d2;
        struct dirent *e2;

        if (de->d_name[0] == '.') continue;
        snprintf(sub, sizeof(sub), "%s/%s", APP_DIR, de->d_name);

        /* 旧式扁平：/data/app/<pkg>-<sfx> */
        if (strncmp(de->d_name, pkg, pl) == 0 && de->d_name[pl] == '-') {
            if (stat(sub, &st) == 0 && S_ISDIR(st.st_mode)) {
                snprintf(out, n, "%s", sub);
                found = 1;
                break;
            }
        }
        if (stat(sub, &st) != 0 || !S_ISDIR(st.st_mode)) continue;

        /* 新式两级：/data/app/~~xxx==/<pkg>-<sfx> */
        d2 = opendir(sub);
        if (!d2) continue;
        while ((e2 = readdir(d2)) != NULL) {
            char p2[1024];
            struct stat st2;
            if (strncmp(e2->d_name, pkg, pl) != 0) continue;
            if (e2->d_name[pl] != '-') continue;
            snprintf(p2, sizeof(p2), "%s/%s", sub, e2->d_name);
            if (stat(p2, &st2) == 0 && S_ISDIR(st2.st_mode)) {
                snprintf(out, n, "%s", p2);
                found = 1;
                break;
            }
        }
        closedir(d2);
    }
    closedir(d);
    return found;
}

/* dropbox 里最新的「应用崩溃/ANR/system_server 相关」时间戳（毫秒）。
 * 只读文件名，不解压 —— gz 解压要 zlib，静态链接不值得；文件名里的
 * 时间戳足够做「有没有新崩溃」的相对比较。 */
static long drop_max(void)
{
    /*
     * 事件驱动缓存：目录 mtime/size 只在 system_server 落盘新崩溃条目时变化。
     * 本函数每 tick 都被调用，而绝大多数时候目录是静止的 —— 一次 stat(约 2µs)
     * 换掉一次 readdir(0.3~2ms)。目录被系统 trim 时 mtime 也会变，触发重扫，
     * 结果仍然正确。
     */
    static long c_mt = -3, c_sz = -1, c_val = 0;
    struct stat sb;
    long nmt = -2, nsz = 0;
    DIR *d;
    struct dirent *de;
    long mx = 0;

    if (stat(DROPBOX_DIR, &sb) == 0) { nmt = (long) sb.st_mtime; nsz = (long) sb.st_size; }
    if (nmt == c_mt && nsz == c_sz) return c_val;
    c_mt = nmt;
    c_sz = nsz;

    d = opendir(DROPBOX_DIR);
    if (!d) return 0;
    while ((de = readdir(d)) != NULL) {
        const char *nm = de->d_name;
        const char *at;
        long v;
        if (strncmp(nm, "system_app_crash@", 17) &&
            strncmp(nm, "system_app_anr@", 15) &&
            strncmp(nm, "system_server", 13)) continue;
        at = strchr(nm, '@');
        if (!at) continue;
        v = atol(at + 1);
        if (v > mx) mx = v;
    }
    closedir(d);
    c_val = mx;
    return mx;
}

/* 统计 DROPBOX_DIR 下 crash 条目总数（只看文件名，不读正文、不做归因）。
 *
 * 与 dropbox_window() 的区别是判据核心：那个函数统计的是「归因命中的条数」，
 * 堆栈只有框架类时归因为 NONE 会被跳过。VERIFY（卸更新后是否自愈）要回答
 * 的是「还崩不崩」，与「崩的是谁」无关，所以必须用这个全量口径 —— 用命中数
 * 会把「还在崩」误判成「已自愈」，从而不发软重启，用户继续黑屏。 */
static int drop_entry_count(void)
{
    static long c_mt = -3, c_sz = -1;
    static int c_val = 0;
    struct stat sb;
    long nmt = -2, nsz = 0;
    DIR *d;
    struct dirent *de;
    int n = 0;

    /* 同 drop_max：目录静止时复用上次计数（缓存失效条件见那里的说明）。 */
    if (stat(DROPBOX_DIR, &sb) == 0) { nmt = (long) sb.st_mtime; nsz = (long) sb.st_size; }
    if (nmt == c_mt && nsz == c_sz) return c_val;
    c_mt = nmt;
    c_sz = nsz;

    d = opendir(DROPBOX_DIR);
    if (!d) return 0;
    while ((de = readdir(d)) != NULL)
        if (is_crash_entry(de->d_name)) n++;
    closedir(d);
    c_val = n;
    return n;
}

/* 指定包名的主进程号。
 *
 * 不能用 /proc/<pid>/comm 匹配：内核 task->comm 只保留 16 字节，且实测本机
 * SystemUI 的 comm 是 "ndroid.systemui"（包名尾部 15 字符，不是前 15 字符），
 * 任何前缀匹配都会失效 —— 这是踩过的坑。
 * 改用 /proc/<pid>/cmdline：完整 argv[0] == 包名，精确比较即可，
 * 而且对 config.target 里的任意包名都成立。
 *
 * 返回 0 = 该包当前无进程（崩溃后未拉起 或 尚未启动）。 */
#define PIDB_SLOTS 6
/* 「长期无独立进程」包的扫描退避表（见 pid_of_pkg 末尾的登记逻辑） */
static struct { char pkg[160]; int miss; int skip; } pidb[PIDB_SLOTS];

static int pid_of_pkg_raw(const char *pkg)
{
    DIR *d;
    struct dirent *de;
    int pid = 0;
    size_t pl = strlen(pkg);

    if (!pl) return 0;

    /* 快速路径：先用上次落盘的 pid 做一次精确校验（约 4 次系统调用）。
     * 判据仍是 cmdline 与包名完全相等 —— 进程换过 pid 或已退出都会失配，
     * 从而安全回退到全量扫描，不会漏掉重启后的新 pid。
     * 本机 650+ 进程时全扫约 1950 次系统调用，每 30s 一轮是最大开销源。 */
    {
        char key[220], p[64], buf[256];
        long cached;
        int fd;
        ssize_t r;
        snprintf(key, sizeof(key), "spid_%s", pkg);
        cached = st_get(key);
        if (cached > 0) {
            snprintf(p, sizeof(p), "/proc/%ld/cmdline", cached);
            fd = open(p, O_RDONLY);
            if (fd >= 0) {
                r = read(fd, buf, sizeof(buf) - 1);
                close(fd);
                if (r > 0) {
                    buf[r] = 0;
                    if (strcmp(buf, pkg) == 0) return (int) cached;
                }
            }
        }
    }

    /* 扫描退避：miui.systemui.plugin 这类插件包的 dex 是加载进
     * com.android.systemui 进程里跑的，永远查不到独立 pid，spid_ 恒为 0，
     * 快速路径命中不了，于是每 30s 白扫 650 个进程。连续 3 轮都扫不到才
     * 启用退避（跳 2 轮 ≈ 60s）。只影响历史上一直为 0 的包：它们的 old
     * 也是 0，不会因此误报 PID_CHANGE；真启动后最迟 60s 内识别出来，
     * 仍在 crash_window_sec(180s) 窗口内。 */
    {
        int i;
        for (i = 0; i < PIDB_SLOTS; i++) {
            if (pidb[i].pkg[0] && strcmp(pidb[i].pkg, pkg) == 0) {
                if (pidb[i].skip > 0) { pidb[i].skip--; return 0; }
                break;
            }
        }
    }

    d = opendir("/proc");
    if (!d) return 0;
    while ((de = readdir(d)) != NULL) {
        char p[320], buf[256];
        int fd;
        ssize_t r;
        if (de->d_name[0] < '0' || de->d_name[0] > '9') continue;
        snprintf(p, sizeof(p), "/proc/%s/cmdline", de->d_name);
        fd = open(p, O_RDONLY);
        if (fd < 0) continue;
        r = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (r <= 0) continue;
        buf[r] = 0;
        /* cmdline 以 \0 分隔多个参数，strcmp 遇到首个 \0 即止，正好比 argv[0] */
        if (strcmp(buf, pkg) == 0) {
            pid = atoi(de->d_name);
            break;
        }
    }
    closedir(d);

    /* 登记本轮结果，供下一轮的扫描退避判断使用 */
    {
        int i, slot = -1;
        for (i = 0; i < PIDB_SLOTS; i++)
            if (pidb[i].pkg[0] && strcmp(pidb[i].pkg, pkg) == 0) { slot = i; break; }
        if (slot < 0)
            for (i = 0; i < PIDB_SLOTS; i++)
                if (!pidb[i].pkg[0]) { slot = i; break; }
        if (slot >= 0) {
            if (pid > 0) {
                pidb[slot].pkg[0] = 0;   /* 找到了：清空记录，恢复每轮扫描 */
                pidb[slot].miss = 0;
                pidb[slot].skip = 0;
            } else {
                snprintf(pidb[slot].pkg, sizeof(pidb[slot].pkg), "%s", pkg);
                if (pidb[slot].miss < 8) pidb[slot].miss++;
                if (pidb[slot].miss >= 3) pidb[slot].skip = 2;
            }
        }
    }
    return pid;
}

/* ---- pid 查询结果缓存 ----------------------------------------------------
 * /proc 全量扫描是 readdir 后逐进程 open+read cmdline，本机 1000+ 进程时
 * 单次约 500KB 内存拷贝。而 state 上报 / 面板 / watchdog 会反复查询同一批
 * 包名，实测每 6s 就会白扫一次（/proc/PID/io 的 rchar 8.4MB/min）。
 *
 * 这里加 20s 结果缓存，正面与负面结果都缓存：
 *   - pid 变化最迟 20s 内被识别，相对 crash_window_sec(180s) 的判定窗口
 *     仍绰绰有余；
 *   - miui.systemui.plugin 这类永远没有独立进程的包，不必每轮翻 /proc。
 * 只缓存「查询结果」，不改变 pidb 扫描退避与 spid_ 落盘校验的行为。 */
#define PIDQ_SLOTS 6
/* 缓存有效期（秒）。
 *
 * 正结果不靠它保证正确性：每次查询都会用 pid_still_is 做单点校验
 * （1 次 read），pid 一变立刻判失效并落到全扫，识别延迟与 TTL 无关。
 *
 * 它真正约束的是「负结果」—— 确认当前没有独立进程后，多久之内不再重复
 * 确认。这里必须显著大于 tick 周期（tick_sec 默认 30）：原值 20 < 30，
 * 等于每轮 tick 都判定过期 → 把 650~980 个 /proc/N/cmdline 全部
 * open+read 一遍（strace 实测每 tick ≈980 次系统调用），是 camd 稳态
 * 系统调用开销的绝对大头。
 *
 * 取 300 后稳态每 10 个 tick 才全扫一次。负结果不影响崩溃判定的正确性：
 * 插件包平时本就没有独立进程，也不可能触发 PID_CHANGE；崩溃主证据是
 * dropbox 窗口计数，pid 变化只是辅助证据。
 *
 * 装包事件会主动 flush（见 pid_cache_flush 调用点），不会出现「装完包
 * 要等 5 分钟才识别到新进程」。
 *
 * 需要瞬时准确结果的路径（救援判定、A 段崩溃循环采样、诊断输出）不走
 * 缓存，见 pid_of_pkg_fresh()。 */
#define PIDQ_TTL   300

/*
 * 单点校验：只读 1 个 cmdline（~20B）就能确认「这个 pid 还是这个包」，
 * 而全量 /proc 扫描要 ~500KB（1000+ 进程逐个 open+read cmdline）。
 */
static int pid_still_is(int pid, const char *pkg)
{
    char p[64], buf[256];
    int fd;
    ssize_t r;

    if (pid <= 0) return 0;
    snprintf(p, sizeof(p), "/proc/%d/cmdline", pid);
    fd = open(p, O_RDONLY);
    if (fd < 0) return 0;
    r = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (r <= 0) return 0;
    buf[r] = 0;
    return strcmp(buf, pkg) == 0;
}
/* pid_batch 定义在 pid_of_pkg 之前（批量路径是 tick 的主通道），
 * 但它内部的兜底分支要回落到单包查询，故先声明。 */
static int pid_of_pkg(const char *pkg);

/* ------------------------------------------------------------ 进程定位表
 *
 * 原来是 pid_of_pkg() 里的静态局部数组，只有单包查询能用。现在 tick 需要
 * 批量定位（见 pid_batch），表要提到文件作用域给两条路径共用。
 */
static struct { char pkg[96]; int val; long at; } pidq[PIDQ_SLOTS];

static void pid_cache_put(const char *pkg, int val, long now_s)
{
    int i, slot = -1, oldest = 0;
    long oa = -1;

    if (!pkg || !pkg[0]) return;

    for (i = 0; i < PIDQ_SLOTS; i++) {
        if (pidq[i].at && strcmp(pidq[i].pkg, pkg) == 0) { slot = i; break; }
    }
    if (slot < 0) {
        for (i = 0; i < PIDQ_SLOTS; i++) {
            if (!pidq[i].at) { slot = i; break; }
            if (oa < 0 || pidq[i].at < oa) { oa = pidq[i].at; oldest = i; }
        }
        if (slot < 0) slot = oldest;        /* 槽满：淘汰最旧 */
    }
    pidq[slot].at = now_s;
    snprintf(pidq[slot].pkg, sizeof(pidq[slot].pkg), "%s", pkg);
    pidq[slot].val = val;
}

/* 主动作废所有「负结果」，强制下一轮重新确认。
 * 装包/卸包后调用：新装的包可能立刻就有进程，不能干等负 TTL 到期。 */
static void pid_cache_flush(void)
{
    int i;
    for (i = 0; i < PIDQ_SLOTS; i++) {
        if (pidq[i].val <= 0) pidq[i].at = 0;   /* at=0 即空槽 */
    }
}

/*
 * 能否在不遍历 /proc 的前提下确定 pkg 的 pid。
 *   返回 >0 ：已确认 pid（1 次单点 cmdline 校验）
 *   返回  0 ：已确认「当前没有独立进程」（负数结果，仍在 TTL 内）
 *   返回 -1 ：需要真正扫描 /proc
 *
 * 抽成独立函数是为了让 pid_batch 能先做一轮「便宜判定」，只把确实
 * 需要重扫的包合并进一次 /proc 遍历 —— 已经能命中的包不该被拖着全扫。
 */
static int pid_cache_probe(const char *pkg, long now_s)
{
    int i;

    if (!pkg || !pkg[0]) return 0;

    for (i = 0; i < PIDQ_SLOTS; i++) {
        if (!pidq[i].at || strcmp(pidq[i].pkg, pkg) != 0) continue;
        /*
         * 正结果（pid>0）：先单点校验再续期。校验只读 1 个 cmdline
         * （~20B），比全量 /proc 扫描（~500KB）便宜四个数量级 ——
         * 于是缓存可以「只要 pid 还在就一直有效」，而 pid 一旦变化
         * 或被复用，下一次查询就会因 cmdline 不匹配而落到全扫，
         * 识别延迟不受 TTL 影响。
         *
         * 旧实现是纯时间 TTL(20s)：tick_sec 默认 30s，等于每轮 tick
         * 都把 2 个目标包全扫一遍（≈1MB），这是 camd 稳态读量的
         * 主要来源之一。
         */
        if (pidq[i].val > 0 && pid_still_is(pidq[i].val, pkg)) {
            pidq[i].at = now_s;
            return pidq[i].val;
        }
        /* 负结果（没找到进程）受 PIDQ_TTL 约束：插件类包平时没进程，
         * 每次 tick 重新全扫 /proc 只为确认这一点毫无意义（见宏注释）。 */
        if (pidq[i].val <= 0 && now_s - pidq[i].at < PIDQ_TTL) return 0;
#ifdef UIDUMP_DBG
        rlog("PIDPROBE stale pkg=%s val=%d age=%ld", pkg, pidq[i].val,
             now_s - pidq[i].at);
#endif
        return -1;                          /* 同包槽位已过期，需重扫 */
    }

    /* PIDQ 里没有，但落盘里有上一轮的 pid：单点校验（1 次 read）远便宜
     * 于全扫。这一步让 camd 重启后的第一轮也能零扫描命中，否则刚起来
     * 的每个 tick 都要把 /proc 翻一遍。 */
    {
        char key[220];
        long cached;
        snprintf(key, sizeof(key), "spid_%s", pkg);
        cached = st_get(key);
        if (cached > 0 && pid_still_is((int) cached, pkg)) {
            pid_cache_put(pkg, (int) cached, now_s);
            return (int) cached;
        }
#ifdef UIDUMP_DBG
        rlog("PIDPROBE noslot pkg=%s disk=%ld", pkg, cached);
#endif
    }
    return -1;
}

/*
 * 一次遍历 /proc，为所有 need 里的包同时定位 pid（找不到记 -1）。
 *
 * 判据与 pid_of_pkg_raw 完全一致：cmdline 的第一段（\0 前）与包名相等。
 * 全部命中即提前退出 —— 常见情况（systemui 在跑）能省掉大半次遍历。
 */
static void pid_scan_many(int np, char (*pkgs)[160], int *res)
{
    DIR *d;
    struct dirent *de;
    int i, left;

    for (i = 0; i < np; i++) res[i] = -1;
    if (np <= 0) return;

    d = opendir("/proc");
    if (!d) return;

    left = np;
    while (left > 0 && (de = readdir(d)) != NULL) {
        char p[64], buf[256];
        ssize_t r;
        int fd;

        if (de->d_name[0] < '0' || de->d_name[0] > '9') continue;
        snprintf(p, sizeof(p), "/proc/%s/cmdline", de->d_name);
        fd = open(p, O_RDONLY);
        if (fd < 0) continue;
        r = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (r <= 0) continue;
        buf[r] = 0;

        for (i = 0; i < np; i++) {
            if (res[i] > 0 || !pkgs[i][0]) continue;
            if (strcmp(buf, pkgs[i]) == 0) {
                res[i] = atoi(de->d_name);
                left--;
            }
        }
    }
    closedir(d);
}

/*
 * 批量定位 target 包的 pid，结果写入 out[]（无进程记 0）。
 *
 * 为什么要合并：pid_of_pkg 是「一包一扫」，每个需要重扫的包各自遍历
 * 一遍 /proc（本机 650+ 进程，每进程 open+read+close）。tick 每轮对
 * np 个 target 各查一次，实测每 30s 约 1000 次 read 系统调用 ——
 * rchar 只有几 KB/s 却有成千上万次读，说明全是小文件逐一扫描，这是
 * camd 稳态最大的系统调用开销（也是唤醒之外最值得压的一项）。
 *
 * 合并后不管 np 是几，/proc 只遍历一遍；而已经能靠单点校验确定的包
 * 根本不进这次遍历，常见稳态下（systemui 有进程）整轮只有 1~2 次读。
 */
static void pid_batch(int np, char (*pkgs)[160], int *out)
{
    long now_s = now_up();
    char need_pkg[8][160];
    int need_idx[8], need_res[8];
    int i, need = 0;

    for (i = 0; i < np; i++) {
        int v = pid_cache_probe(pkgs[i], now_s);
        if (v >= 0) {                       /* 命中：不参与扫描 */
            out[i] = v;
            continue;
        }
        if (need >= 8) {                    /* 槽位按 target 上限 8 对齐 */
            out[i] = pid_of_pkg(pkgs[i]);
            continue;
        }
        snprintf(need_pkg[need], sizeof(need_pkg[need]), "%s", pkgs[i]);
        need_idx[need] = i;
        need++;
    }
#ifdef UIDUMP_DBG
    rlog("PIDBATCH np=%d need=%d", np, need);
#endif
    if (!need) return;

    pid_scan_many(need, need_pkg, need_res);
    for (i = 0; i < need; i++) {
        int v = need_res[i] > 0 ? need_res[i] : 0;
        out[need_idx[i]] = v;
        pid_cache_put(need_pkg[i], v, now_s);
    }
}

/* ---- pidfd：盯 target 进程退出（仅用于“提前触发一次 tick”，不改记账） ---- */
static int g_pfd[2] = {-1,-1};
static int g_pfd_pid[2] = {0,0};
static int open_pidfd_r(int pid){
#ifdef SYS_pidfd_open
    return (int)syscall(SYS_pidfd_open, pid, 0);
#else
    (void)pid; return -1;
#endif
}
static int split_target(const cfg_t *c, char out[][160], int max);;
int rescue_fill_pidfds(struct pollfd *pf, int cap){
    cfg_t c; cfg_load(&c);
    char pk[8][160]; int pids[8];
    int np = split_target(&c, pk, 8); if (np > 8) np = 8;
    pid_batch(np, pk, pids);
    int n = 0;
    for (int i = 0; i < 2; i++){
        if (i >= np){ if (g_pfd[i] >= 0){ close(g_pfd[i]); g_pfd[i] = -1; } g_pfd_pid[i] = 0; continue; }
        int pid = pids[i];
        if (pid != g_pfd_pid[i]) {
            if (g_pfd[i] >= 0){ close(g_pfd[i]); g_pfd[i] = -1; }
            g_pfd_pid[i] = pid;
            if (pid > 0){ int fd = open_pidfd_r(pid); if (fd >= 0) g_pfd[i] = fd; }
        }
        if (g_pfd[i] >= 0 && n < cap){ pf[n].fd = g_pfd[i]; pf[n].events = POLLIN; pf[n].revents = 0; n++; }
    }
    return n;
}
/* ---- 卡死判据：crash 缓冲里 StackOverflow/堆栈疯长（进程没死也算崩） ---- */
static int hang_probe(void){
    FILE *p = popen("logcat -b crash -d -t 600 2>/dev/null", "r");
    if (!p) return 0;
    char l[512]; long tot = 0, stk = 0; int so = 0;
    while (fgets(l, sizeof(l), p)) {
        tot++;
        char *q = l; while (*q == ' ' || *q == '\t') q++;
        if (!strncmp(q, "at ", 3)) stk++;
        if (strstr(l, "StackOverflowError")) so = 1;
    }
    pclose(p);
    if (tot <= 0) return 0;
    if (so && stk * 100 / tot > 50) return 1;
    if (tot > 2000 && stk * 100 / tot > 80) return 1;
    return 0;
}


static int pid_of_pkg(const char *pkg)
{
    long now_s;
    int v;

    if (!pkg || !pkg[0]) return 0;

    now_s = now_up();
    v = pid_cache_probe(pkg, now_s);
    if (v >= 0) return v;

    v = pid_of_pkg_raw(pkg);
    pid_cache_put(pkg, v, now_s);
    return v;
}

/*
 * 跳过缓存取瞬时值。
 *
 * 给「答案错不得」的路径用：救援判定（删不删包）、VERIFY 裁决（是否自愈）、
 * A 段崩溃循环采样（180s 窗口内的 pid 变化）、以及人工诊断输出。
 *
 * 之所以不能共用缓存：这些判据的时间窗（60~180s）短于 PIDQ_TTL(300s)，
 * 缓存里的负结果会让「进程刚被拉起」或「刚崩掉」延迟最长 5 分钟才可见。
 *
 * 成本不会失控：pid_of_pkg_raw 内部先走落盘 pid 的单点校验（1 次 read），
 * 只有 pid 真的对不上时才全扫。也就是说正常稳态下这条路径也只是 1 次读，
 * 与缓存路径同量级；全扫只在「进程确实变了」时发生 —— 而那正是我们要
 * 知道的时刻。
 */
static int pid_of_pkg_fresh(const char *pkg)
{
    int v;

    if (!pkg || !pkg[0]) return 0;

    v = pid_of_pkg_raw(pkg);
    pid_cache_put(pkg, v, now_up());   /* 顺手刷新缓存，避免缓存变陈旧 */
    return v;
}

/* 目录大小（KB），用于诊断展示 */
static long dir_kb(const char *path)
{
    char cmd[600];
    FILE *p;
    long kb = -1;
    snprintf(cmd, sizeof(cmd), "du -sk '%s' 2>/dev/null", path);
    p = popen(cmd, "r");
    if (!p) return -1;
    if (fscanf(p, "%ld", &kb) != 1) kb = -1;
    pclose(p);
    return kb;
}

/* --------------------------------------------------------------- 备份管理 */

/*
 * 在 SIGCHLD=SIG_IGN 的环境下安全地「fork + exec + 等待」。
 *
 * camd 主进程刻意把 SIGCHLD 设为 SIG_IGN —— bridge 的 handler 子进程靠它
 * 自动回收（见 camellia_bridge.c 顶部说明）。但那种状态下 waitpid 会立刻
 * 返回 ECHILD（子进程已被内核回收），导致「备份明明成功却判定失败」。
 * 这里在等待期间临时切回 SIG_DFL，结束再还原，既拿到退出码又不破坏契约。
 */
static int run_wait(const char *path, char *const argv[])
{
    struct sigaction old, dfl;
    pid_t p;
    int st, rc = -1;

    sigaction(SIGCHLD, NULL, &old);
    dfl = old;
    dfl.sa_handler = SIG_DFL;
    dfl.sa_flags = 0;
    sigemptyset(&dfl.sa_mask);
    sigaction(SIGCHLD, &dfl, NULL);

    p = fork();
    if (p == 0) {
        /* 子进程不得继承 camd 的日志 fd：camd 的 stderr 指向
         * /data/adb/camellia/camd.log（SELinux adb_data_file），app_process 系
         * 工具（settings 等）继承这种 fd 时会因 fd 传递被拒，报
         * "Failure calling service settings: Failed transaction"。统一改到
         * /dev/null，命令才能正常执行。 */
        int dn = open("/dev/null", O_WRONLY);
        if (dn >= 0) { dup2(dn, 1); dup2(dn, 2); if (dn > 2) close(dn); }
        execv(path, argv);
        _exit(127);
    }
    if (p > 0 && waitpid(p, &st, 0) == p)
        rc = (WIFEXITED(st) && WEXITSTATUS(st) == 0) ? 0 : -1;

    sigaction(SIGCHLD, &old, NULL);
    return rc;
}

/*
 * 用 toybox sh -c 执行一串命令。
 * 不使用 libc 的 system()：bionic 的 system() 内部 waitpid 在 SIGCHLD=SIG_IGN
 * 下会返回 ECHILD 并让 system() 直接返回 -1，导致「命令其实执行成功了但判定失败」。
 */
static int sh_run(const char *cmd)
{
    char *av[4];
    av[0] = "sh";
    av[1] = "-c";
    av[2] = (char *)cmd;
    av[3] = NULL;
    return run_wait("/system/bin/sh", av);
}


/* --------------------------------------------------------- 系统自救屏蔽 */
/*
 * L0  activity_manager_constants: process_crash_count_limit=<shield_limit>
 *     AMS 靠这个值判定「进程崩够次数就触发救援」，AOSP 默认 12。
 *     设到 999999 等于把判据本身拔掉 —— 不靠事后擦除，没有竞态窗口。
 *     走 Settings.Global：ActivityManagerConstants 有 observer 实时生效，
 *     且写入自动持久化到 settings_global.xml，重启不丢。
 * L1  persist.sys.rescuepartyplus.disable* = 1（MIUI 预留逃生阀）
 * L2  周期校验：ROM 更新 / 系统脚本把它改回就重设（tick 内按 shield_check 节流）
 *
 * 为什么不用 hook：hook 要 LSPosed 常驻 system_server，框架一挂就失效；
 * 改这两个官方参数纯 root 即可，且不依赖任何进程存活 —— 这正是 camd
 * 挂在 /data/adb 上的价值。
 *
 * 安全：activity_manager_constants 是「整串替换」而不是「改单键」。
 * 直接写会抹掉 ROM 预置的其它调优（max_cached_processes / service_timeout
 * ...），可能改变后台回收与超时策略。所以必须：读全量 → 只替换目标键
 * → 整串写回；原始串备份到 state/shield.bak，关闭时原样写回
 * （而不是硬写 12 —— ROM 原本可能压根没有这个键）。
 */
#define AM_GLOBAL_KEY    "activity_manager_constants"
#define AM_LIMIT_KEY     "process_crash_count_limit"
#define AM_LIMIT_KEY_EQ  AM_LIMIT_KEY "="
#define SHIELD_LIMIT_DEF 12          /* AOSP 默认值，无备份时的还原兜底 */
#define SHIELD_PROP_ON \
    "setprop persist.sys.rescuepartyplus.disable 1; " \
    "setprop persist.sys.rescuepartyplus.disable.systemui 1; " \
    "setprop persist.sys.rescuepartyplus.disable.anr 1"
#define SHIELD_PROP_OFF \
    "setprop persist.sys.rescuepartyplus.disable 0; " \
    "setprop persist.sys.rescuepartyplus.disable.systemui 0; " \
    "setprop persist.sys.rescuepartyplus.disable.anr 0"

/* 执行命令并捕获 stdout（走管道，绝不落盘）。
 *
 * 两个坑必须绕开：
 *  1) 不用 popen：SIGCHLD=SIG_IGN 下 pclose 拿不到真实退出码（见 run_wait 注释）。
 *  2) 不「重定向到临时文件再读」：临时文件若在 /data/adb（SELinux
 *     adb_data_file），app_process 系工具（settings）会因 fd 指向该 label 而在
 *     binder 事务时被拒 —— 实测报 "Failure calling service settings: Failed
 *     transaction (2147483646)"，命令凭空失败。管道 fd 与路径 label 无关。 */
static int sh_capture(const char *cmd, char *out, size_t n)
{
    struct sigaction old, dfl;
    int pfd[2], st, rc = -1;
    pid_t p;
    ssize_t r;
    size_t used = 0;
    char buf[512];
    char *t;

    if (!out || n < 2) return -1;
    out[0] = 0;
    if (pipe(pfd) != 0) return -1;

    sigaction(SIGCHLD, NULL, &old);
    dfl = old;
    dfl.sa_handler = SIG_DFL;
    dfl.sa_flags = 0;
    sigemptyset(&dfl.sa_mask);
    sigaction(SIGCHLD, &dfl, NULL);

    p = fork();
    if (p == 0) {
        close(pfd[0]);
        dup2(pfd[1], 1);
        dup2(pfd[1], 2);          /* stderr 一起收，失败原因能写进日志 */
        if (pfd[1] > 2) close(pfd[1]);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *) 0);
        _exit(127);
    }
    close(pfd[1]);
    if (p < 0) { close(pfd[0]); sigaction(SIGCHLD, &old, NULL); return -1; }

    /* 超长输出只保留前 n-1 字节，但仍读到 EOF，否则子进程写管道会被阻塞 */
    while ((r = read(pfd[0], buf, sizeof(buf))) > 0) {
        if (used + (size_t) r < n) {
            memcpy(out + used, buf, (size_t) r);
            used += (size_t) r;
        }
    }
    close(pfd[0]);
    out[used] = 0;

    st = 0;
    if (waitpid(p, &st, 0) == p && WIFEXITED(st) && WEXITSTATUS(st) == 0) rc = 0;
    sigaction(SIGCHLD, &old, NULL);

    t = trim(out);               /* settings get 会带回车/换行 */
    if (t != out) memmove(out, t, strlen(t) + 1);
    return rc;
}

/* 在 "k1=v1,k2=v2" 串里设置 k=v：键存在就替换值，不存在就追加。0=成功 */
static int cs_set(const char *in, const char *k, const char *v, char *out, size_t n)
{
    const char *src = (in && in[0]) ? in : "";
    const char *p;
    size_t kl = strlen(k);
    char *o = out;
    size_t left = n;
    int found = 0;

    if (!strcmp(src, "null")) src = "";   /* settings get 无值返回 "" */
    out[0] = 0;
    for (p = src; *p; ) {
        const char *comma = strchr(p, ',');
        size_t len = comma ? (size_t) (comma - p) : strlen(p);
        if (len > kl && !strncmp(p, k, kl) && p[kl] == '=') {
            int w = snprintf(o, left, "%s=%s", k, v);
            if (w < 0 || (size_t) w >= left) return -1;
            o += w; left -= (size_t) w;
            found = 1;
        } else {
            if (len + 1 > left) return -1;
            memcpy(o, p, len); o += len; left -= len; *o = 0;
        }
        if (!comma) break;
        if (left < 2) return -1;
        *o++ = ','; *o = 0; left--;
        p = comma + 1;
    }
    if (!found) {
        int w = snprintf(o, left, "%s%s=%s", (o == out) ? "" : ",", k, v);
        if (w < 0 || (size_t) w >= left) return -1;
    }
    return 0;
}

/* 读取 activity_manager_constants 现状。
 * 返回 0 = 读取成功（*limit 一定有效；键缺失时填 AOSP 默认值，并通过
 *              *present=0 告知调用者「原本没有这个键」）
 * 返回 -1 = 命令本身失败（settings 服务不可用等），此时禁止动手写入。
 *
 * 这两者必须分开：键不存在是正常状态（AMS 用内置默认 12），
 * 不是读失败 —— 早期版本混为一谈，导致 shield 在真机上直接放弃。 */
static int shield_read(char *raw, size_t rn, long *limit, int *present)
{
    char out[4096];
    const char *p;

    if (raw && rn) raw[0] = 0;
    if (limit) *limit = SHIELD_LIMIT_DEF;
    if (present) *present = 0;

    if (sh_capture("settings get global " AM_GLOBAL_KEY, out, sizeof(out)) != 0)
        return -1;
    if (raw && rn) snprintf(raw, rn, "%s", out);

    /* 整串无该键时 settings 输出 "" */
    if (!out[0] || !strcmp(out, "null")) return 0;

    p = strstr(out, AM_LIMIT_KEY_EQ);
    if (p) {
        if (limit) *limit = atol(p + strlen(AM_LIMIT_KEY_EQ));
        if (present) *present = 1;
    }
    return 0;
}

/* 应用屏蔽：L0 改判据 + L1 逃生阀。0=已生效 */
static int shield_apply(const cfg_t *c)
{
    char cur[4096], want[4096], lim[32], cmd[8192], sp[320], bak[320];
    struct stat stb;
    int rc = 0, retry, present = 0, need;
    long v;

    cam_path("state", sp, sizeof(sp));
    mkdir_p2(sp, 0700);

    if (shield_read(cur, sizeof(cur), &v, &present) != 0)
        return -1;                     /* 连现状都读不到，绝不盲写 */
    snprintf(lim, sizeof(lim), "%d", c->shield_limit);
    need = 1;   /* 幂等全量写：确保 min/reset interval + device_config 每次都补齐，不再因“值已等于上限”而跳过 */

    /* 备份：只在「准备改」且尚未备份时留一份。
     * 原本没有该键 → 记 ABSENT，关闭时用 settings delete 精确回到「不存在」；
     * 否则把整串原样存下来。 */
    cam_path("state/shield.bak", bak, sizeof(bak));
    if (need && (stat(bak, &stb) != 0 || stb.st_size == 0)) {
        FILE *f = fopen(bak, "w");
        if (f) {
            if (cur[0] && strcmp(cur, "null") != 0) fprintf(f, "%s\n", cur);
            else fprintf(f, "ABSENT\n");
            fclose(f);
            chmod(bak, 0600);
        }
    }

    if (need) {
        char w2[4096], w3[4096];
        /* 键缺失时 cs_set 会追加；整串为 null 时从零开始，避免写出 ",xxx"。
         * 同时补 min_crash_interval / process_crash_count_reset_interval（同 HyperMount）：
         * 前者扩大两次崩的判定间隔、后者拉长重置周期，避免系统“口径”把我们设的上限迅速清零。 */
        if (cs_set(cur, AM_LIMIT_KEY, lim, want, sizeof(want)) != 0)
            return -1;
        if (cs_set(want, "min_crash_interval", "120000", w2, sizeof(w2)) != 0)
            return -1;
        if (cs_set(w2, "process_crash_count_reset_interval", "43200000", w3, sizeof(w3)) != 0)
            return -1;
        snprintf(want, sizeof(want), "%s", w3);
        snprintf(cmd, sizeof(cmd), "settings put global %s '%s'",
                 AM_GLOBAL_KEY, want);
        if (sh_run(cmd) != 0) rc = -1;
        /* device_config 双写（更权威路径），失败不致命 */
        snprintf(cmd, sizeof(cmd),
          "cmd device_config put activity_manager process_crash_count_limit %d 2>/dev/null; "
          "cmd device_config put activity_manager min_crash_interval 120000 2>/dev/null; "
          "cmd device_config put activity_manager process_crash_count_reset_interval 43200000 2>/dev/null",
          c->shield_limit);
        sh_run(cmd);
    }

    /* props 幂等，每次重设无害（防开机脚本覆盖） */
    if (sh_run(SHIELD_PROP_ON) != 0) rc = -1;

    /* 回读校验：命令成功 != 真的生效（SELinux / settings 服务异常都会吞） */
    for (retry = 0; ; retry++) {
        if (shield_read(NULL, 0, &v, NULL) == 0 && v == c->shield_limit) break;
        if (retry >= 3) { rc = -1; break; }
        usleep(200000);
    }
    if (v != c->shield_limit) rc = -1;

    st_set("shield_ok", rc == 0 ? 1 : 0);
    if (rc != 0) rlog("SHIELD_FAIL cur=%ld want=%d", v, c->shield_limit);
    return rc;
}

/* 关闭屏蔽：恢复到 ROM 原样 */
static int shield_off(void)
{
    char cur[4096], bak[320], cmd[8192], buf[4096], lim[32];
    int rc = 0, present = 0;
    FILE *f;
    long v;

    cam_path("state/shield.bak", bak, sizeof(bak));
    buf[0] = 0;
    f = fopen(bak, "r");
    if (f) {
        if (!fgets(buf, sizeof(buf), f)) buf[0] = 0;
        fclose(f);
        buf[strcspn(buf, "\r\n")] = 0;
    }

    (void) shield_read(cur, sizeof(cur), &v, &present);

    if (buf[0] && !strcmp(buf, "ABSENT")) {
        /* 原本没有这个键 → 删掉，回到「不存在」而不是留个 12 */
        snprintf(cmd, sizeof(cmd), "settings delete global %s", AM_GLOBAL_KEY);
    } else if (buf[0] && strcmp(buf, "null") != 0) {
        snprintf(cmd, sizeof(cmd), "settings put global %s '%s'", AM_GLOBAL_KEY, buf);
    } else {
        /* 备份丢失：至少把 limit 放回 AOSP 默认，别留个 999999 */
        snprintf(lim, sizeof(lim), "%d", SHIELD_LIMIT_DEF);
        if (cs_set(cur, AM_LIMIT_KEY, lim, buf, sizeof(buf)) != 0)
            return -1;
        snprintf(cmd, sizeof(cmd), "settings put global %s '%s'", AM_GLOBAL_KEY, buf);
    }
    if (sh_run(cmd) != 0) rc = -1;
    if (sh_run(SHIELD_PROP_OFF) != 0) rc = -1;

    (void) shield_read(NULL, 0, &v, NULL);
    unlink(bak);
    st_set("shield_ok", 0);
    rlog("SHIELD_OFF rc=%d cur=%ld", rc, v);
    return rc;
}

/* 归档坏包：只存 base.apk + meta（不再整目录 cp -a）
 *
 * 为什么不整目录备份：
 *   ① 要删的正是坏包，原样留一份不能当「健康源」——健康快照是另一套
 *      snap/，由「稳定存活 stable_sec 且零崩」产生，语义完全不同；
 *   ② /data/app/<pkg>-<hash>/ 里的 so/oat 动辄几十 MB，而留证只需要 base.apk；
 *   ③ 真要回滚，用户重装 apk 即可；meta 里的 sha256 用来识别「同一个坏包」，
 *      是后续坏包黑名单（不再重试装同一个 apk）的唯一可靠键。
 *
 * 返回 0 = base.apk 与 meta 均已落地；-1 = 失败（调用方据此放弃删包，保持保守）。
 */
/* 在备份目录里找「同包 + 同 sha256」的既有快照，命中就写出它的目录。
 *
 * 为什么必须去重：同一个坏包会被反复判崩 —— 装回 → 崩 → 删覆盖层 →
 * 系统又自动装回 → 再崩。不去重的话每崩一次就多 52MB 副本，而 bak_keep
 * 只有 3 份，很快全部被同一条崩溃现场占满，真正不同的坏包反而被挤掉。
 * 命中返回 0，未命中返回 -1。哈希不可信（拿不到/太短）时一律不去重。 */
static int bak_find_same_sha(const cfg_t *c, const char *pkg,
                             const char *sha, char *out, size_t on)
{
    DIR *d;
    struct dirent *de;
    size_t pl = strlen(pkg), sl;

    if (!sha || (sl = strlen(sha)) < 16) return -1;   /* 不像 sha256，别拿去比对 */
    d = opendir(c->bak_dir);
    if (!d) return -1;

    while ((de = readdir(d)) != NULL) {
        char mp[1100], buf[512], *q, *e;
        FILE *f;
        size_t n;

        if (strncmp(de->d_name, pkg, pl) != 0 || de->d_name[pl] != '@') continue;
        snprintf(mp, sizeof(mp), "%s/%s/meta", c->bak_dir, de->d_name);
        f = fopen(mp, "r");
        if (!f) continue;
        n = fread(buf, 1, sizeof(buf) - 1, f);
        fclose(f);
        buf[n] = 0;

        q = strstr(buf, "sha256=");
        if (!q) continue;
        q += 7;
        e = q;
        while (*e && *e != '\n' && *e != '\r') e++;
        if ((size_t)(e - q) == sl && !strncmp(q, sha, sl)) {
            snprintf(out, on, "%s/%s", c->bak_dir, de->d_name);
            closedir(d);
            return 0;
        }
    }
    closedir(d);
    return -1;
}

static int bak_save(const cfg_t *c, const char *pkg, const char *dir,
                    const char *reason, char *dst, size_t dn)
{
    char src[900];
    char path[1100];
    char cmd[4096];
    char sha[96];
    FILE *f;
    size_t i, j;

    dst[0] = 0;

    /* bak_keep=0 = 用户明确不要备份：直接放行，省掉 52MB 的读写。
     * 手动 --snap 快照是另一条路（cmd_snap 会临时按 keep>=1 调用）。 */
    if (c->bak_keep <= 0) {
        rlog("BAK_SKIP keep=0 pkg=%s reason=%s", pkg,
             reason && reason[0] ? reason : "unknown");
        return 0;
    }

    mkdir_p2(c->bak_dir, 0700);

    /* dir 通常是覆盖目录，也兼容直接指向某个 .apk 的情况 */
    if (strstr(dir, ".apk")) {
        snprintf(src, sizeof(src), "%s", dir);
    } else {
        snprintf(src, sizeof(src), "%s/base.apk", dir);
        if (access(src, F_OK) != 0) snprintf(src, sizeof(src), "%s", dir);
    }
    if (access(src, F_OK) != 0) return -1;

    /* 先对「源 apk」算哈希，再做去重：命中就能整段跳过 cp，
     * 省掉一次 52MB 的写盘（原实现是先复制再对副本算哈希）。 */
    sha[0] = 0;
    snprintf(cmd, sizeof(cmd), "sha256sum '%s' 2>/dev/null | cut -d' ' -f1", src);
    if (sh_capture(cmd, sha, sizeof(sha)) == 0) {
        i = 0;
        while (sha[i] == ' ' || sha[i] == '\t' || sha[i] == '\n') i++;
        if (i) memmove(sha, sha + i, strlen(sha + i) + 1);
        j = strlen(sha);
        while (j && (sha[j - 1] == '\n' || sha[j - 1] == '\r' || sha[j - 1] == ' '))
            sha[--j] = 0;
        if (strlen(sha) < 16) sha[0] = 0;   /* 不像 sha256 就丢弃，别记假值 */
    }

    if (bak_find_same_sha(c, pkg, sha, dst, dn) == 0) {
        rlog("BAK_DEDUP %s sha=%s reason=%s", dst, sha,
             reason && reason[0] ? reason : "unknown");
        /*
         * 刷新被复用目录的 mtime。bak_prune 按 mtime 判断「谁最旧」，
         * 这份现场刚刚被本次救援用到；若它的 mtime 还停在几天前，
         * 紧接着执行的 prune 会把它当成最旧的删掉 —— 现场当场消失。
         * 刷新后语义也更准：它确实「刚刚被用过」。
         */
        utimensat(AT_FDCWD, dst, NULL, 0);
        return 0;                            /* 已有同内容快照，不重复落盘 */
    }

    snprintf(dst, dn, "%s/%s@%ld", c->bak_dir, pkg, now_up());
    /* mkdir_p2 的 -1 也包含「已存在」，统一用 access 判成败 */
    mkdir_p2(dst, 0700);
    if (access(dst, F_OK) != 0) return -1;

    snprintf(path, sizeof(path), "%s/base.apk", dst);
    snprintf(cmd, sizeof(cmd), "cp -f '%s' '%s' 2>/dev/null", src, path);
    if (sh_run(cmd) != 0 || access(path, F_OK) != 0) return -1;

    snprintf(path, sizeof(path), "%s/meta", dst);
    f = fopen(path, "w");
    if (!f) return -1;
    fprintf(f, "pkg=%s\novl=%s\nsha256=%s\nts=%ld\nreason=%s\n",
            pkg, dir, sha[0] ? sha : "unknown", (long) time(NULL),
            reason && reason[0] ? reason : "unknown");
    fclose(f);

    rlog("BAK_SAVE %s src=%s sha=%s", dst, src, sha[0] ? sha : "unknown");
    return 0;
}

/* 轮转：同一包只保留最近 bak_keep 份。
 *
 * 排序键必须是【绝对时间】，不能用文件名里 @ 后面的 uptime 数字 —— 那是
 * 当前 boot 内的运行秒数，重启后从 0 重来，跨 boot 完全不可比。真实事故：
 * 新备份 com.android.systemui@72（本 boot 刚起来）与旧备份 @6036/@4971
 * （上一 boot）混在同一目录，按 up 降序新备份排最末，被当成「最旧」删掉，
 * 刚存的坏包现场证据当场消失，无法回滚/取证。
 * 改按目录 mtime（绝对时间、跨 boot 单调）排序，同秒再比 up 兜底。 */
static void bak_prune(const cfg_t *c, const char *pkg)
{
    DIR *d = opendir(c->bak_dir);
    struct dirent *de;
    char names[64][320];
    long nts[64];               /* mtime 秒，主序 */
    long ups[64];               /* @up，同秒 tie-break */
    int n = 0, i, j;
    size_t pl = strlen(pkg);

    if (!d) return;
    while ((de = readdir(d)) != NULL && n < 64) {
        char *at;
        char full[1100];
        struct stat st;
        if (strncmp(de->d_name, pkg, pl) != 0 || de->d_name[pl] != '@') continue;
        at = strchr(de->d_name, '@');
        if (!at) continue;
        snprintf(full, sizeof(full), "%s/%s", c->bak_dir, de->d_name);
        if (stat(full, &st) != 0) continue;   /* 拿不到时间戳就不参与轮转 */
        snprintf(names[n], sizeof(names[n]), "%s", de->d_name);
        nts[n] = (long) st.st_mtime;
        ups[n] = atol(at + 1);
        n++;
    }
    closedir(d);
    if (n <= c->bak_keep) return;

    /* 简单插入排序（n<=64）：mtime 降序（新→旧），同秒再比 up */
    for (i = 1; i < n; i++) {
        char tn[320];
        long tt = nts[i], tu = ups[i];
        snprintf(tn, sizeof(tn), "%s", names[i]);
        for (j = i - 1; j >= 0 &&
             (nts[j] < tt || (nts[j] == tt && ups[j] < tu)); j--) {
            nts[j + 1] = nts[j];
            ups[j + 1] = ups[j];
            snprintf(names[j + 1], sizeof(names[j + 1]), "%s", names[j]);
        }
        nts[j + 1] = tt;
        ups[j + 1] = tu;
        snprintf(names[j + 1], sizeof(names[j + 1]), "%s", tn);
    }
    for (i = c->bak_keep; i < n; i++) {
        char p[900];
        snprintf(p, sizeof(p), "%s/%s", c->bak_dir, names[i]);
        rlog("BAK_PRUNE %s", names[i]);
        {
            char rm[1200];
            snprintf(rm, sizeof(rm), "rm -rf '%s' 2>/dev/null", p);
            sh_run(rm);   /* 轮转失败不影响主流程 */
        }
    }
}

/* ------------------------------------------------------------------ 熔断 */

/* split_target 定义在本函数之后，这里先声明（reset_pid_baselines 要用）。 */
static int split_target(const cfg_t *c, char out[][160], int max);

/*
 * 清空所有 target 包的 pid 基线（state/spid_<pkg>）。
 *
 * 基线比较规则是「old > 0 且 cur != old 才算一次 pid 变化」，因此跨 boot
 * 留着旧值就是脏数据：新 boot 里那个 pid 必然不存在（或已被别的进程复用），
 * 首次采样必记一笔假 PID_CHANGE，把 A 段崩溃窗口弄脏 —— 既可能提前误触发，
 * 也让账本失去意义（实测：上一 boot 的 7616 -> 本 boot 的 0，虚增 circ=1/3）。
 * 与 apid_sysui 同理，区别是这里覆盖全部 target 而不只是 SystemUI。
 */
static void reset_pid_baselines(const cfg_t *c)
{
    char pk[8][160], k[220];
    int np = split_target(c, pk, 8), i;

    for (i = 0; i < np; i++) {
        snprintf(k, sizeof(k), "spid_%s", pk[i]);
        st_set(k, 0);
    }
}

/* 供 camellia_dsnap.c 调用：camd 自己做了一次"故意重启 SystemUI"后，
 * 复用引擎既有机制把 target 的 pid 基线重定(置 0)，避免这次动作被 B 段
 * 当成 PID_CHANGE 计入崩溃（与 do_soft_reboot / 修复收口处的做法一致）。 */
void rescue_after_own_restart(void)
{
    cfg_t c; cfg_load(&c);
    reset_pid_baselines(&c);
    st_set("self_restart_ts", (long)time(NULL));   /* 5min 内 pid 变化视为“自己重启”，不计数 */
    rlog("REBASELINE after own intentional restart");
}


/*
 * 返回 1 = 已熔断（禁止任何自动修复），并把原因写入 why。
 * 返回 0 = 可以继续。
 */
static int guard(cfg_t *c, char *why, size_t wn)
{
    char bid[64], old[64];
    long sr, nf, tot;
    int have_old;

    /*
     * 拿不到当前 boot_id：状态不可判定。
     * 这里必须保守阻断并保留已有预算 —— 把「读不到」当成硬重启去清零，
     * 会让熔断在最该生效的时候失效（真机上就是无限修复循环）。
     */
    if (!boot_id(bid, sizeof(bid)) || !bid[0]) {
        snprintf(why, wn, "boot_id_unavailable");
        return 1;
    }

    have_old = st_str("boot_id", old, sizeof(old));

    if (!have_old) {
        /*
         * 首次部署，或记录丢失/损坏：只登记当前 boot，不动任何计数。
         * 预算保持原值，不当作硬重启清零。
         */
        st_wstr("boot_id", bid);
        reset_pid_baselines(c);   /* 记录丢失时 pid 基线同样不可信 */
        rlog("BOOT_INIT id=%s", bid);
        return 0;
    }

    if (strcmp(bid, old) != 0) {
        /* 确认换了 boot：硬重启 = 用户介入，情况已变，清空熔断。 */
        st_wstr("boot_id", bid);
        st_set("sr_count", 0);
        st_set("sr_total", 0);   /* 硬重启 = 新的绝对预算 */
        st_set("nofx_count", 0);
        st_set("sr_ts", 0);
        st_set("sr_drop_max", 0);
        st_set("sr_rebase", 0);   /* 冷却窗跨 boot 作废：新 boot 墙钟可能回拨 */
        /*
         * apid_sysui 必须一起清：它是「上一次采样看到的 SystemUI pid」。
         * 跨 boot 留着就是脏基线 —— 新 boot 里那个 pid 必然不存在，首次
         * 采样必然记一笔假崩（实测：上一 boot 的 7722 → 本 boot 的 0），
         * 把 A 段窗口弄脏，既可能提前误触发，也让账本失去意义。
         */
        st_set("apid_sysui", 0);
        /*
         * 多包通用的 pid 基线同样跨 boot 作废：不清的话新 boot 首次采样
         * 必然记一笔假 PID_CHANGE（上一 boot 的 7616 -> 本 boot 的 0）。
         */
        reset_pid_baselines(c);
        st_set("verify_ts", 0);   /* 旧的卸更新观察窗跨 boot 作废 */
        st_del("halt");
        rlog("BOOT_NEW %s -> %s state reset", old, bid);
        return 0;
    }

    if (st_str("halt", why, wn)) return 1;   /* 已熔断，原因沿用 */

    /*
     * 单 boot 绝对上限先判：sr_count 会被「装包」归还，
     * 唯一绕不过的就是 sr_total —— 这才是防无限循环的最后一层。
     */
    tot = st_get("sr_total");
    /*
     * 升级迁移：老版本状态里没有 sr_total 字段（读出来是 0），
     * 若此时 sr_count>0，直接按 0 起算等于升级瞬间白送满额预算。
     * 这里按 sr_count 补齐，且只补不降 —— 保证 tot >= sr 恒成立。
     */
    {
        long sr_now = st_get("sr_count");
        if (tot < sr_now) {
            tot = sr_now;
            st_set("sr_total", tot);
            rlog("SR_TOTAL_MIGRATE sr_total=%ld (按 sr_count 补齐)", tot);
        }
    }
    if (tot >= BOOT_SR_TOTAL_MAX) {
        snprintf(why, wn, "soft_reboot_total(%ld/%d)", tot, BOOT_SR_TOTAL_MAX);
        st_wstr("halt", why);
        rlog("HALT %s", why);
        return 1;
    }

    sr = st_get("sr_count");
    if (sr >= HARD_SR_MAX) {
        snprintf(why, wn, "soft_reboot_hard_limit(%ld/%d)", sr, HARD_SR_MAX);
        st_wstr("halt", why);
        rlog("HALT %s", why);
        return 1;
    }
    if (sr >= c->sr_max) {
        snprintf(why, wn, "soft_reboot_limit(%ld/%d)", sr, c->sr_max);
        st_wstr("halt", why);
        rlog("HALT %s", why);
        return 1;
    }
    nf = st_get("nofx_count");
    if (nf >= c->nofx_max) {
        snprintf(why, wn, "fix_not_effective(%ld/%d)", nf, c->nofx_max);
        st_wstr("halt", why);
        rlog("HALT %s", why);
        return 1;
    }
    return 0;
}

/* ------------------------------------------------ 目标包 / 修复执行 -------- */
static int split_target(const cfg_t *c, char out[][160], int max)
{
    char buf[512];
    char *p, *q;
    int n = 0;
    snprintf(buf, sizeof(buf), "%s", c->target);
    p = buf;
    while (p && *p && n < max) {
        q = strchr(p, ',');
        if (q) *q = 0;
        {
            char *t = trim(p);
            if (*t) snprintf(out[n++], 160, "%s", t);
        }
        p = q ? q + 1 : NULL;
    }
    return n;
}

/* 软重启：killall system_server。camd 挂在 init 下，不受影响，会继续跑。 */
static void do_soft_reboot(const cfg_t *c)
{
    char *av[4];
    long dm;

    /* 【收口：软重启前必须落基线】
     * 三条重启路径（reboot_after_fix / A 段 / VERIFY_FAIL 兜底）都汇到这里，
     * 基线统一在此设置，避免各调用点漏配 —— VERIFY_FAIL 此前完全没设水位，
     * 导致软重启余波被下一轮判据当成新崩，nofx_count 虚增后熔断。
     *   ① crash_hist_reset：软重启后 pid 必变，旧账与重拉余波都不该算
     *   ② sr_drop_max 只升不降：兜住「动作时刻水位」，不覆盖更晚的值
     *   ③ sr_rebase：冷却窗下限，判据取 max 后即可排除余波 */
    crash_hist_reset();
    dm = drop_max();
    if (dm > st_get("sr_drop_max")) st_set("sr_drop_max", dm);
    st_set("sr_rebase", now_ms_rt() + SR_REBASE_MS);

    av[0] = "sh";
    av[1] = "-c";
    av[2] = (char *)c->reboot_cmd;
    av[3] = NULL;
    run_wait("/system/bin/sh", av);
}

/*
 * 对单个包执行：备份 → 删覆盖 → 记状态 → （可选）软重启
 * 返回 0=已处理 1=无需处理 -1=失败
 */
static int fix_pkg(cfg_t *c, const char *pkg, int do_reboot, char *note, size_t nn)
{
    char dir[640];
    char bak[1024];
    char rm[1400];

    if (!find_ovl(pkg, dir, sizeof(dir))) {
        snprintf(note, nn, "no_ovl");
        return 1;
    }
    if (bak_save(c, pkg, dir, "delete_bad_package", bak, sizeof(bak)) != 0) {
        snprintf(note, nn, "backup_failed dir=%s", dir);
        rlog("FIX_ABORT pkg=%s backup failed dir=%s", pkg, dir);
        return -1;
    }
    rlog("FIX_START pkg=%s ovl=%s bak=%s", pkg, dir, bak);

    snprintf(rm, sizeof(rm), "rm -rf '%s' 2>/dev/null", dir);
    if (sh_run(rm) != 0) {
        snprintf(note, nn, "rm_failed dir=%s", dir);
        rlog("FIX_ABORT pkg=%s rm failed dir=%s", pkg, dir);
        return -1;
    }
    if (access(dir, F_OK) == 0) {
        snprintf(note, nn, "rm_ineffective dir=%s", dir);
        rlog("FIX_ABORT pkg=%s dir still exists", pkg);
        return -1;
    }
    sh_run("sync");
    bak_prune(c, pkg);
    snprintf(note, nn, "fixed bak=%s", bak);
    rlog("FIX_DONE pkg=%s removed=%s", pkg, dir);

    if (do_reboot) reboot_after_fix(c);
    return 0;
}

/* 修复动作之后统一走这里：记账 + 发软重启。
 * 记账必须抢在重启之前 —— 软重启会杀掉 camd 自身，重启后的观察期
 * 完全依赖 sr_ts / sr_count / sr_drop_max 判断「这次修到底有没有用」。 */
static void reboot_after_fix(cfg_t *c)
{
    /* 修复收尾统一作废 A 段账本（本函数由 B 段与组件链共用）：
     * ① 软重启前剩余 tick 不该再拿旧账判一次；
     * ② 软重启换掉 system_server 后 SystemUI 的 pid 必然变化，不清基线
     *    会让重启后首次采样记一笔假崩，把 180s 窗口弄脏。 */
    a_hist_reset();
    /* B 段账本同样要作废：判定起点是「修复成功那一刻」。软重启清不掉
       kernel uptime，修复前那几次崩溃的时间戳仍落在窗口内，留着会让观察期
       的 OBS_FAIL 拿旧账误判 —— nofx_count 虚增几次就直接熔断。 */
    crash_hist_reset();
    st_set("apid_sysui", 0);
    /* 软重启不换 boot_id（/proc/sys/kernel/random/boot_id 只随内核重启变化），
     * 上面 BOOT_NEW 分支根本不会执行 —— 多包通用基线必须在这里手动作废。
     * 否则软重启后 SystemUI / plugin 的 pid 必然变化（zygote 重新拉起），
     * 下一轮 tick 立刻记一笔假 PID_CHANGE，把「这次修复是否有效」的观察窗
     * 弄脏：OBS_FAIL 会拿它当新崩，nofx_count 虚增两次就 HALT。 */
    reset_pid_baselines(c);

    st_set("sr_ts", now_up());
    st_set("obs_ts", now_ms_rt());   /* 有效性观察窗起始(墙钟ms) */
    st_set("sr_count", st_get("sr_count") + 1);
    st_set("sr_total", st_get("sr_total") + 1);
    /* F3：水位用“修复完成这一刻的墙钟”作硬下限，彻底排除重启前/同秒旧条目 */
    { long _n = now_ms_rt(), _d = drop_max(); st_set("sr_drop_max", _n > _d ? _n : _d); }
    rlog("SOFT_REBOOT cmd=[%s] sr_count=%ld total=%ld", c->reboot_cmd,
         st_get("sr_count"), st_get("sr_total"));
    do_soft_reboot(c);
}

/* B 段界面修复链：手段从轻到重依次尝试，任一步成功即停。
 *
 *   ① 卸系统更新        —— 退回出厂版本，用户数据不动，最轻、可逆
 *   ② 装兜底 APK        —— 用用户事先备好的好包顶上去
 *   ③ 删覆盖层 + 软重启 —— 物理移除注入源，破坏性最大，作为最后手段
 *
 * 顺序不能颠倒，原因很实在：②③ 都会让 camd 随 system_server 一起重启，
 * 一旦先做了，就更轻的手段再没有机会被试到。① 失败是最常见情况
 * （目标不是系统应用、或本来就没有更新），此时静默进下一步。 */
/* --------------------------------------------------- 包类型 & 修复前快照 ---- */

/* 目标包类型：ROM SystemUI 本体 vs 可独立更新的界面组件。
 *
 * 两者救法完全不同，这是真机踩出来的，绝对不能混：
 *
 *   com.android.systemui —— ROM 的 system_ext priv-app 独立 APK，用户装的坏包
 *       落在 /data/app 覆盖层。三条路只有最后一条有效：
 *         · `pm uninstall-system-updates` 无效：它在这个包上返回 not a system app
 *           —— 覆盖层不是「系统应用的更新」，就是这个包本体，没有更新层可卸。
 *         · `pm install` 覆盖安装也救不回来：签名/版本冲突直接失败；即便装上，
 *           写的仍是同一条坏覆盖层，问题原地不动。
 *         · 唯一手段：删掉 /data/app 覆盖层目录，再软重启让 PMS 回落 ROM 内置版。
 *           注意删除本身不生效 —— 已加载的坏包还在内存里，必须靠软重启回滚。
 *
 *   miui.systemui.plugin —— ROM 自带独立系统应用，有更新层，卸更新最轻、可逆、
 *       用户数据不动。
 */
static int pkg_is_sysui(const char *pkg)
{
    /* 精确比对。用 strstr("plugin") 反推太脆：target 里只要配一个不含 plugin
       字样的组件包，就会被误当成 SystemUI 本体走「删包」—— 破坏面从可逆的
       卸更新直接放大到删覆盖层 + 重启。 */
    return !strcmp(pkg, "com.android.systemui");
}

/* 「组件」= target 里除界面本体之外的任何包。
 * 用 !pkg_is_sysui 而不是 strstr("plugin")：后者只认名字，配置里换一个
 * 不含 plugin 字样的组件包就会被当成界面本体去删覆盖层，破坏面从可逆的
 * 卸更新放大到删包+重启。反过来，本体之外的一切都走卸更新（可逆），安全。 */
static int pkg_is_plugin(const char *pkg)
{
    return !pkg_is_sysui(pkg);
}


/* 修复动作之前统一落快照。
 *
 * 之前只有「删覆盖层」这条分支（fix_pkg）会调 bak_save，卸更新 / 装兜底包
 * 这两条更常用的路径完全没有备份 —— 这就是"看不到坏包快照"的根因。
 * 快照失败只记日志、不阻塞修复：留证是附加价值，把界面救回来才是第一目标。
 */
static void snap_before(cfg_t *c, const char *pkg, const char *why)
{
    char dir[640], bak[1024];

    if (!find_ovl(pkg, dir, sizeof(dir))) {
        rlog("SNAP_SKIP pkg=%s why=%s (无覆盖层)", pkg, why);
        return;
    }
    if (bak_save(c, pkg, dir, why, bak, sizeof(bak)) == 0) {
        rlog("SNAP pkg=%s why=%s ovl=%s -> %s", pkg, why, dir, bak);
        /*
         * 轮转必须跟着走。bak_keep 是「每包保留份数」的承诺，但此前只有
         * fix_pkg（删覆盖层）会调 bak_prune，卸更新 / 装兜底包这两条更
         * 常用的路径只存不删 —— bak_keep 对它们完全失效。真机后果：
         * 同一个 plugin 坏包被反复救援，快照堆到 4 份、213MB 永不回收。
         */
        bak_prune(c, pkg);
    } else
        rlog("SNAP_FAIL pkg=%s why=%s ovl=%s", pkg, why, dir);
}

static int ui_rescue_chain(cfg_t *c, const char *pkg, char *note, size_t nn,
                           int do_reboot)
{
    char res[512];
    int is_plugin = pkg_is_plugin(pkg);

    res[0] = 0;

    /*
     * ① 卸系统更新 —— 只对「组件」成立。
     * SystemUI 本体没有更新层（真机 pm 返回 not a system app），对它调这一步
     * 纯属浪费一次 pm 事务、拖慢急救时间，所以直接跳过。
     */
    if (is_plugin) {
        snap_before(c, pkg, "before_uninstall_update");
        if (pm_uninstall_update(pkg, 0, res, sizeof(res)) == 0) {
            rlog("UI_RESCUE uninstall_update pkg=%s -> %s", pkg, res);
            snprintf(note, nn, "① 已卸更新(组件): %s", res);
            if (do_reboot) {
                /*
                 * 卸更新本身通常足以收工：PMS 会 kill 掉加载该包的过程
                 * （plugin 与 SystemUI 共进程），Zygote 随即拉起，加载回落
                 * 到 ROM 内置版 —— 整机软重启并非必需。所以这里不发重启，
                 * 只记账并开观察窗，由 tick 里的 VERIFY 阶段裁决：
                 *   SystemUI 活下来且观察窗内无新崩溃 → 免重启
                 *   进程仍无 / 又有新崩溃               → 软重启兜底
                 * sr_count 在此先计一次（这是真实的修复尝试），VERIFY_FAIL
                 * 走 do_soft_reboot 而不是 reboot_after_fix，避免重复计数。
                 */
                a_hist_reset();
                crash_hist_reset();   /* 卸更新即判定起点，旧崩账不能算进观察期 */
                st_set("apid_sysui", 0);
                reset_pid_baselines(c);   /* 同上：包被重拉，旧 pid 基线作废 */
                st_set("sr_ts", now_up());
                st_set("sr_count", st_get("sr_count") + 1);
                st_set("sr_total", st_get("sr_total") + 1);
                st_set("sr_drop_max", drop_max());
                /* 卸更新会让 PMS kill 并重拉 SystemUI，重拉期若落崩溃同样
                 * 属于动作余波。本路径不发软重启，不会经过 do_soft_reboot，
                 * 冷却窗必须在这里单独设置，否则 VERIFY 判据会把它算成新崩
                 * → 明明修好了却判 VERIFY_FAIL → 多余软重启一次。 */
                st_set("sr_rebase", now_ms_rt() + SR_REBASE_MS);
                st_set("verify_ts", now_up());
                rlog("UI_RESCUE 卸更新成功 -> 免软重启, 进入 %ds 观察窗", VERIFY_SEC);
            }
            return 0;
        }
        rlog("UI_RESCUE uninstall_update 不适用 pkg=%s (%s)", pkg, res);
    } else {
        rlog("UI_RESCUE 跳过卸更新 pkg=%s (界面本体无更新层 -> 删包回滚)", pkg);
    }

    /* ② 装用户兜底包 —— 两类包都适用，比删包温和（能保住新版本功能）。 */
    if (c->user_apk[0]) {
        snap_before(c, pkg, "before_install_apk");
        if (pm_install(c->user_apk, pkg, 0, res, sizeof(res)) == 0) {
            rlog("UI_RESCUE install pkg=%s apk=%s -> %s", pkg, c->user_apk, res);
            snprintf(note, nn, "② 已装兜底包: %s", res);
            if (do_reboot) reboot_after_fix(c);
            return 0;
        }
        rlog("UI_RESCUE install 失败 pkg=%s apk=%s (%s)", pkg, c->user_apk, res);
    }

    /* ③ 删覆盖层 + 软重启 —— 破坏性最大，最后手段。fix_pkg 内部自带快照。 */
    rlog("UI_RESCUE fix_pkg pkg=%s (删覆盖层回滚)", pkg);
    return fix_pkg(c, pkg, do_reboot, note, nn);
}

/* ================================================================= 归因器 ====
 *
 * DE 阶段必须回答：黑屏到底是谁的错？
 *   com.android.systemui（系统界面）与 miui.systemui.plugin（系统界面组件）
 *   共用同一个进程 —— 实测 ps 只有 com.android.systemui 一个 pid，plugin
 *   没有独立进程。所以 pid 变化只能证明「这个进程崩了」，证明不了是哪一方
 *   的代码崩的。删错包 = 白救一次，还可能把本来能用的包装回去。
 *
 * 归因证据链（按可信度排序，全部在 DE 可读，不需要 pm / CE）：
 *   1. dropbox 崩溃正文：/data/system/dropbox/system_app_crash@<ts> 的堆栈帧
 *      直接带类名/包名，唯一的一手证据。
 *   2. 插件加载期标记：/data/system/camellia_plugin_state（Java 侧写，格式
 *      state|ts_ms|note）。插件正在加载/刚报错时崩，即使堆栈里只有 SystemUI
 *      自己的类，也能归到组件。
 *   3. 改动面排除法：packages.xml 的 codePath 是否指向 /data/app（有覆盖 =
 *      被改过），以及 lastUpdateTime（谁最近被装）。
 *
 * 规则移植自 Java 侧 SysUiWatch.attribute（经「双 APK 反汇编差集校准」：
 * 组件 18.2.2.2.0 vs SystemUI 17.03.260226.r）。不移植会踩这些坑：
 *   - SystemUI 自带 miui.systemui.utils.CallbackControllerUtils → 误判组件
 *   - SystemUI 自带 com.miui.systemui.*（safemode 等）          → 误判组件
 *   - 组件里 637 个 com.android.systemui.* 扩展类（MiPlay 投屏 / QS /
 *     ControlCenter 系列），SystemUI 原包 MiPlay* = 0          → 漏判组件
 *   - SystemUI 的 lib/ 条目数 = 0（纯 Java）；组件带 libmglnative.so、
 *     libMiMainColor.so                                         → 组件强特征
 *   - 组件独有资源 qs_customize_button                          → 组件强特征
 */

#define ATTR_NONE   0
#define ATTR_PLUGIN 1
#define ATTR_SYSUI  2
/* 判不出是谁的锅 → 两个一起修。
 * 典型场景：崩溃堆栈只有框架类（ZygoteInit / ActivityThread / Looper /
 * Handler），两个包都可能是注入源，正文特征一个都不命中。此时宁可把两个
 * 覆盖层都回退：ROM 内置版仍在，用户随时能装回来；而猜错一次就要再来一轮
 * 重启，成本比多删一个已废弃的覆盖层高得多。 */
#define ATTR_BOTH   3

#define PLUGIN_STATE_FILE        "/data/system/camellia_plugin_state"
#define PLUGIN_LOADING_WINDOW_MS 20000L
#define PLUGIN_ERROR_WINDOW_MS   60000L
#define CRASH_TEXT_MAX           8192

/* ---------------------------------------------------- ABX 二进制 XML 解析
 *
 * packages.xml 是二进制 XML（magic "ABX\0"），不能字符串搜索取属性：文件里
 * <updated-package> 也带 codePath，属性顺序也不固定，邻域窗口法会把两者搞混。
 * 这里按 token 流结构化解析，保证 name/codePath/lastUpdateTime 来自同一元素。
 *
 * 只认 <package>，不认 <updated-package>：前者是在装包，后者是已卸载残留记录；
 * 救援只关心在装的，否则会把已卸载包的旧 codePath 当成「覆盖」。
 */
typedef struct { const char *p; uint16_t n; } bxs_t;

typedef struct {
    const uint8_t *buf;
    size_t len, pos;
    const char **pool_p;
    uint16_t   *pool_n;
    int pool_count, pool_cap, err;
} bxr_t;

static int bx_byte(bxr_t *r)
{
    if (r->pos >= r->len) { r->err = 1; return -1; }
    return r->buf[r->pos++];
}
static int bx_peek(bxr_t *r)
{
    if (r->pos >= r->len) return -1;
    return r->buf[r->pos];
}
static int bx_short(bxr_t *r)
{
    int v;
    if (r->pos + 2 > r->len) { r->err = 1; return -1; }
    v = ((int)r->buf[r->pos] << 8) | r->buf[r->pos + 1];
    r->pos += 2;
    return v;
}
static int bx_skip(bxr_t *r, size_t n)
{
    if (r->pos + n > r->len) { r->err = 1; return -1; }
    r->pos += n;
    return 0;
}
static int bx_utf(bxr_t *r, bxs_t *out)
{
    int n = bx_short(r);
    if (n < 0) return -1;
    if (r->pos + (size_t)n > r->len) { r->err = 1; return -1; }
    out->p = (const char *)(r->buf + r->pos);
    out->n = (uint16_t)n;
    r->pos += (size_t)n;
    return 0;
}
static int bx_interned(bxr_t *r, bxs_t *out)
{
    int idx = bx_short(r);
    if (idx < 0) return -1;
    if (idx == 0xFFFF) {
        bxs_t s;
        if (bx_utf(r, &s) != 0) return -1;
        if (r->pool_count < r->pool_cap) {
            r->pool_p[r->pool_count] = s.p;
            r->pool_n[r->pool_count] = s.n;
            r->pool_count++;
        } else { r->err = 1; return -1; }
        *out = s;
        return 0;
    }
    if (idx >= r->pool_count) { r->err = 1; return -1; }
    out->p = r->pool_p[idx];
    out->n = r->pool_n[idx];
    return 0;
}
static int bx_skip_value(bxr_t *r, int type)
{
    bxs_t tmp;
    int n;
    switch (type) {
    case 0x10: return 0;                       /* null */
    case 0x20: return bx_utf(r, &tmp);         /* string */
    case 0x30: return bx_interned(r, &tmp);    /* stringInterned */
    case 0x40: case 0x50:                      /* bytesHex / bytesBase64 */
        n = bx_short(r);
        if (n < 0) return -1;
        return bx_skip(r, (size_t)n);
    case 0x60: case 0x70: case 0xA0:           /* int / intHex / float */
        return bx_skip(r, 4);
    case 0x80: case 0x90: case 0xB0:           /* long / longHex / double */
        return bx_skip(r, 8);
    case 0xC0: case 0xD0: return 0;            /* true / false */
    default: r->err = 1; return -1;
    }
}
static int bxs_eq(const bxs_t *s, const char *lit)
{
    size_t n = strlen(lit);
    return s->n == n && memcmp(s->p, lit, n) == 0;
}

/* 读一个 string / stringInterned 属性值，拷进 NUL 结尾的 out */
static int bx_read_str(bxr_t *r, int ty, char *out, size_t n)
{
    bxs_t s;
    if (ty == 0x20) { if (bx_utf(r, &s) != 0) return -1; }
    else if (ty == 0x30) { if (bx_interned(r, &s) != 0) return -1; }
    else return -1;
    if (s.n >= n) s.n = (uint16_t)(n - 1);
    memcpy(out, s.p, s.n);
    out[s.n] = 0;
    return 0;
}

/* 读 int / long（BE），其它类型跳过并返回 0 */
static long bx_read_num(bxr_t *r, int ty)
{
    long v = 0;
    int i;
    if (ty == 0x60 || ty == 0x70) {           /* int / intHex */
        if (r->pos + 4 > r->len) { r->err = 1; return 0; }
        v = (long)(int32_t)(((uint32_t)r->buf[r->pos] << 24) |
                            ((uint32_t)r->buf[r->pos + 1] << 16) |
                            ((uint32_t)r->buf[r->pos + 2] << 8) |
                             (uint32_t)r->buf[r->pos + 3]);
        r->pos += 4;
        return v;
    }
    if (ty == 0x80 || ty == 0x90) {           /* long / longHex */
        if (r->pos + 8 > r->len) { r->err = 1; return 0; }
        for (i = 0; i < 8; i++) v = (v << 8) | r->buf[r->pos + i];
        r->pos += 8;
        return v;
    }
    bx_skip_value(r, ty);
    return 0;
}

typedef struct {
    char code_path[512];
    long version_code;
    long last_update;      /* 毫秒时间戳 */
    int  found;
} pkginfo_t;

/* 从 packages.xml 取一个包的结构化信息。
 * 返回 1=命中，0=没这个包 / 文件读不了 / 解析中断（调用方下次重试即可）。 */
static int pkg_query(const char *pkg, pkginfo_t *info)
{
    int fd;
    struct stat st;
    uint8_t *buf = NULL;
    size_t len, got = 0;
    bxr_t r;
    int found = 0;

    if (info) memset(info, 0, sizeof(*info));
    if (!pkg || !pkg[0]) return 0;

    fd = open("/data/system/packages.xml", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    if (fstat(fd, &st) != 0 || st.st_size < 8) { close(fd); return 0; }
    len = (size_t)st.st_size;
    buf = malloc(len);
    if (!buf) { close(fd); return 0; }
    while (got < len) {
        ssize_t k = read(fd, buf + got, len - got);
        if (k <= 0) break;
        got += (size_t)k;
    }
    close(fd);
    len = got;
    if (len < 8 || memcmp(buf, "ABX", 3) != 0 || buf[3] != 0) {
        free(buf);
        return 0;
    }

    memset(&r, 0, sizeof(r));
    r.buf = buf;
    r.len = len;
    r.pos = 4;
    r.pool_cap = 65535;
    r.pool_p = malloc(sizeof(char *) * (size_t)r.pool_cap);
    r.pool_n = malloc(sizeof(uint16_t) * (size_t)r.pool_cap);
    if (!r.pool_p || !r.pool_n) {
        free(r.pool_p); free(r.pool_n); free(buf);
        return 0;
    }

    while (!r.err) {
        int tok = bx_byte(&r);
        int ev, ty;
        if (tok < 0) break;
        ev = tok & 0x0F;
        ty = tok & 0xF0;
        if (ev == 1) break;                      /* endDoc */
        if (ev == 2) {                           /* startTag */
            bxs_t tag;
            int is_pkg;
            char a_name[256] = "", a_cp[512] = "";
            int has_name = 0, has_cp = 0;
            long vc = 0, lu = 0;
            if (bx_interned(&r, &tag) != 0) break;
            is_pkg = bxs_eq(&tag, "package");
            while (!r.err) {
                int p = bx_peek(&r);
                int at, aty;
                bxs_t an;
                if (p < 0 || (p & 0x0F) != 15) break;   /* attribute */
                at = bx_byte(&r);
                aty = at & 0xF0;
                if (bx_interned(&r, &an) != 0) break;
                if (is_pkg) {
                    if (bxs_eq(&an, "name")) {
                        if (bx_read_str(&r, aty, a_name, sizeof(a_name)) == 0) has_name = 1;
                        continue;
                    }
                    if (bxs_eq(&an, "codePath")) {
                        if (bx_read_str(&r, aty, a_cp, sizeof(a_cp)) == 0) has_cp = 1;
                        continue;
                    }
                    if (bxs_eq(&an, "versionCode")) {
                        vc = bx_read_num(&r, aty);
                        continue;
                    }
                    if (bxs_eq(&an, "lastUpdateTime")) {
                        lu = bx_read_num(&r, aty);
                        continue;
                    }
                }
                if (bx_skip_value(&r, aty) != 0) break;
            }
            if (r.err) break;
            if (is_pkg && has_name && has_cp && strcmp(a_name, pkg) == 0) {
                if (info) {
                    snprintf(info->code_path, sizeof(info->code_path), "%s", a_cp);
                    info->version_code = vc;
                    info->last_update = lu;
                    info->found = 1;
                }
                found = 1;
                break;
            }
        } else if (ev == 3) {                    /* endTag */
            bxs_t tag;
            if (bx_interned(&r, &tag) != 0) break;
        } else if (ty == 0x20 && ev >= 4 && ev <= 10) {
            bxs_t tmp;
            if (bx_utf(&r, &tmp) != 0) break;
        } else if (ev != 0) {
            if (bx_skip_value(&r, ty) != 0) break;
        }
    }

    free(r.pool_p);
    free(r.pool_n);
    free(buf);
    return found;
}

/* lastUpdateTime 在 Android 13+ 的 packages.xml（ABX 二进制）里已不再写入，
 * 实测 grep 命中 0 次 -> pkginfo.last_update 恒为 0，"两个包都有覆盖层时谁
 * 更新"的判断随之失效。退而用覆盖层版本目录的 mtime：安装/更新都会重写该
 * 目录时间，与 lastUpdateTime 语义接近且永远可取。返回秒级时间戳。 */
static long ovl_stamp(const char *code_path)
{
    struct stat st;
    char dir[640];
    char *p;
    if (!code_path || !code_path[0]) return 0;
    snprintf(dir, sizeof(dir), "%s", code_path);
    p = strrchr(dir, '/');
    if (!p) return 0;
    *p = 0;                                   /* 去掉 /base.apk，留版本目录 */
    if (stat(dir, &st) != 0) return 0;
    return (long)st.st_mtime;
}

/* ---------------------------------------------------------- 文本级归因 */
static int memhas(const char *t, size_t len, const char *needle)
{
    size_t nl = strlen(needle), i;
    if (!nl || len < nl) return 0;
    for (i = 0; i + nl <= len; i++)
        if (memcmp(t + i, needle, nl) == 0) return 1;
    return 0;
}

/* ------------------------------------------------- pkg_query 的 tick 级复用
 *
 * 为什么不做「按 packages.xml 的 mtime 缓存」：开机早期 SystemUI / 组件
 * 正在崩溃时，墙钟可能还没同步、packages.xml 也可能正被 PMS 重写，
 * 用「mtime 没变」推断「内容没变」并不可靠。一旦拿旧数据做了「这个包的
 * 覆盖层该不该删」的判断，后果就是越救越坏。
 *
 * 所以缓存寿命严格限定在「同一次 tick 内」：同一条判定流程里会重复查
 * 同一个包（attr_actionable 与 culprit_attr 各查一次 sysui/plugin），
 * 这部分只解析一次 823KB 的 XML；跨 tick 一律重新直读。
 * 真正动手前后的查询（a_fix_one / pm_install / pm_uninstall_update /
 * pkg_has_overlay / apk_stamp_pkg）永远走 pkg_query()，不碰缓存。
 */
static unsigned pkg_epoch;                 /* rescue_tick 每次进入 +1 */
typedef struct {
    char      pkg[160];
    pkginfo_t info;
    int       hit;
    unsigned  ep;                          /* 0 = 空槽 */
    /* 生成该槽位时 packages.xml 的指纹，用于跨 tick 复用 */
    long      fdev, fino, fmt, fsz;
    int       fok;
} pq_slot_t;
static pq_slot_t pq_slot[4];

/* 取 packages.xml 指纹。注意用 (dev,ino,mtime,size) 做「相等性」判断，
 * 不做时间差比较 —— 墙钟即使跳变也不影响结论（见上方长注释的顾虑）。 */
static int pq_fingerprint(long *dv, long *in, long *mt, long *sz)
{
    struct stat sb;
    if (stat("/data/system/packages.xml", &sb) != 0) return 0;
    *dv = (long) sb.st_dev;
    *in = (long) sb.st_ino;
    *mt = (long) sb.st_mtime;
    *sz = (long) sb.st_size;
    return 1;
}

/*
 * 跨 tick 复用：只要 packages.xml 的指纹与「生成槽位时」完全一致就允许命中。
 *
 * 为什么这不会重蹈「mtime 缓存不可靠」的覆辙：
 *   ① 判据是四元组相等，不是「距上次多久」。墙钟没同步、跳变，都不影响
 *      「这次看到的是不是和上次同一个文件」这个判断。
 *   ② PMS 装/卸/更新包时以 atomic rename 重写 packages.xml，inode 必变；
 *      即使原地改写，mtime/size 也会变。任一变化即整体作废重读。
 *   ③ 原设计的硬边界完整保留：真正动手前后的查询（a_fix_one / pm_install /
 *      pm_uninstall_update / pkg_has_overlay / apk_stamp_pkg）仍走
 *      pkg_query() 直读，绝不经过本缓存。
 *
 * 收益：packages.xml 本机 825KB，原来每 tick 至少整读一遍（≈27KB/s，
 * 叠加 tick 内多次调用可达 55~90KB/s），是 camd 稳态读量的绝对大头。
 * 稳态下系统不装包，指纹稳定，于是降为 0 读。
 */
static int pkg_query_tick(const char *pkg, pkginfo_t *info)
{
    int i, spare = -1;
    long dv = -1, in = -1, mt = -1, sz = -1;
    int fp_ok = pq_fingerprint(&dv, &in, &mt, &sz);

    for (i = 0; i < 4; i++) {
        int same_fp = fp_ok && pq_slot[i].fok
                      && pq_slot[i].fdev == dv && pq_slot[i].fino == in
                      && pq_slot[i].fmt == mt && pq_slot[i].fsz == sz;
        if (pq_slot[i].ep && !strcmp(pq_slot[i].pkg, pkg)
            && (pq_slot[i].ep == pkg_epoch || same_fp)) {
            if (info) *info = pq_slot[i].info;
            return pq_slot[i].hit;
        }
        /* 可复用的空槽：不属于本 tick，且指纹也不匹配（是真过期） */
        if (pq_slot[i].ep != pkg_epoch && !same_fp) spare = i;
    }
    {
        int hit = pkg_query(pkg, info);
        if (spare >= 0) {
            snprintf(pq_slot[spare].pkg, sizeof(pq_slot[spare].pkg), "%s", pkg);
            if (info) pq_slot[spare].info = *info;
            pq_slot[spare].hit = hit;
            pq_slot[spare].ep  = pkg_epoch;
            pq_slot[spare].fok = fp_ok;
            pq_slot[spare].fdev = dv;
            pq_slot[spare].fino = in;
            pq_slot[spare].fmt  = mt;
            pq_slot[spare].fsz  = sz;
        }
        return hit;
    }
}

/* 「miui.systemui.」/「miui/systemui/」出现，但剔除 SystemUI 自带的
 * com.miui.systemui.* 与 miui.systemui.utils.*（后者是 SystemUI 的
 * CallbackControllerUtils，名字撞车） */
static int has_plugin_class(const char *t, size_t len)
{
    size_t i;
    for (i = 0; i + 14 <= len; i++) {
        if (memcmp(t + i, "miui.systemui.", 14) != 0 &&
            memcmp(t + i, "miui/systemui/", 14) != 0) continue;
        if (i >= 4 && memcmp(t + i - 4, "com.", 4) == 0) continue;
        if (i + 20 <= len && (memcmp(t + i, "miui.systemui.utils.", 20) == 0 ||
                              memcmp(t + i, "miui/systemui/utils/", 20) == 0))
            continue;
        return 1;
    }
    return 0;
}

/* 一级归属：只看 crash 正文的 Process: 行，不解析堆栈。
 *
 * 为什么要单独一级：堆栈只有框架类（ZygoteInit / ActivityThread /
 * Looper / Handler）时 attribute_text 归 ATTR_NONE，过去被 dropbox_window
 * 直接 continue 丢掉 —— 可那确实是界面进程在崩，只是堆栈没特征。
 * 丢掉之后「崩了几次」这个事实就没了，只剩 pid 变化兜底，而 pid 变化
 * 既有采样间隔、又分不清崩溃与正常重启。所以先按进程名记一笔
 * （两个包共用 com.android.systemui 进程，一级层面分不开，记 ATTR_BOTH
 * 表示「确实有界面进程在崩」），谁崩的交给二级堆栈归因。 */
static int process_owner(const char *t, size_t len)
{
    if (!t || !len) return ATTR_NONE;
    if (memhas(t, len, "Process: system_server")) return ATTR_NONE;
    /* 组件若被 ROM 配了独立进程，一级就能定性 */
    if (memhas(t, len, "Process: miui.systemui.plugin")) return ATTR_PLUGIN;
    if (memhas(t, len, "Process: com.android.systemui")) return ATTR_BOTH;
    return ATTR_NONE;
}

/* 崩溃正文归因。规则顺序与 Java 侧完全一致：组件强特征优先，SystemUI 兜底。 */
static int attribute_text(const char *t, size_t len)
{
    if (!t || !len) return ATTR_NONE;
    /* system_server 自身崩溃不算：它是软重启元凶，归错会白耗一次崩溃额度 */
    if (memhas(t, len, "Process: system_server")) return ATTR_NONE;
    /* 1) 组件包名，必须跟点（排除 classpath 里的 .../plugin-XXX/base.apk） */
    // step1：miui.systemui.plugin. 归属组件；但要排除 SystemUI 自带的 "com.miui.systemui.plugin."
    if (memhas(t, len, "miui.systemui.plugin.")) {
        int _hit = 0;
        for (size_t _i = 0; _i + 21 <= len; _i++) {
            if (memcmp(t + _i, "miui.systemui.plugin.", 21) != 0) continue;
            if (_i >= 4 && memcmp(t + _i - 4, "com.", 4) == 0) continue;  /* com.miui.systemui.plugin.* 属 SYS */
            _hit = 1; break;
        }
        if (_hit) return ATTR_PLUGIN;
    }
    /* 2) 组件独有类前缀（剔除 SystemUI 自带者） */
    if (has_plugin_class(t, len)) return ATTR_PLUGIN;
    /* 3) 组件独有 MiPlay / 投屏扩展类（SystemUI 原包 MiPlay* = 0） */
    if (memhas(t, len, "com.android.systemui.MiPlay") ||
        memhas(t, len, "Lcom/android/systemui/MiPlay") ||
        memhas(t, len, "QSControlMiPlay") ||
        memhas(t, len, "ControlCenterMiPlay")) return ATTR_PLUGIN;
    /* 4) native 库归属：不能只看库名。
     *    逆向两个 APK 已证实 libMiMainColor.so / libmglnative.so 等库
     *    SystemUI 也从 /system_ext/lib64 加载 —— 同名不同主，按库名判
     *    必然误判。只有库出现在组件覆盖层目录里才算组件的：
     *    /data/app/.../miui.systemui.plugin-XXXX==/lib/arm64/libXxx.so */
    if (memhas(t, len, "/miui.systemui.plugin-") ||
        memhas(t, len, "miui.systemui.plugin/lib/")) return ATTR_PLUGIN;
    /* 对称地：覆盖层目录名带包名+随机后缀，是最硬的归属证据 ——
     * 比下面「出现 com.android.systemui 字样」强得多（后者在 plugin
     * 自己的代码里也大量存在：plugin 编入了 627 个 com/android/systemui
     * 类，如 miui/volume、globalactions）。 */
    if (memhas(t, len, "/com.android.systemui-")) return ATTR_SYSUI;
    /* 5) 组件独有资源 */
    if (memhas(t, len, "qs_customize_button")) return ATTR_PLUGIN;
    /* 6) SystemUI 兜底（含它自带的 com.miui.systemui.*） */
    if (memhas(t, len, "com.android.systemui") ||
        memhas(t, len, "com.miui.systemui.")) return ATTR_SYSUI;
    return ATTR_NONE;
}

static long now_ms_rt(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_REALTIME, &ts) != 0) return 0;
    return (long)ts.tv_sec * 1000L + (long)(ts.tv_nsec / 1000000L);
}

/* 插件加载期标记。文件格式 state|ts_ms|note，Java 侧写；DE 阶段 remote prefs
 * 在 CE 里读不到，只剩这个文件通道。残留标记靠时间窗自然失效。 */
static int attr_plugin_state(void)
{
    FILE *f;
    char buf[256];
    char *p1, *p2;
    long ts, age;
    size_t n;

    f = fopen(PLUGIN_STATE_FILE, "r");
    if (!f) return ATTR_NONE;
    n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    if (n == 0) return ATTR_NONE;
    buf[n] = 0;
    p1 = strchr(buf, '|');
    if (!p1) return ATTR_NONE;
    *p1 = 0;
    p2 = strchr(p1 + 1, '|');
    if (p2) *p2 = 0;
    ts = atol(p1 + 1);
    if (ts <= 0) return ATTR_NONE;
    age = now_ms_rt() - ts;
    if (age < 0) return ATTR_NONE;
    if (strcmp(buf, "LOADING") == 0 && age <= PLUGIN_LOADING_WINDOW_MS)
        return ATTR_PLUGIN;
    if (strcmp(buf, "ERROR") == 0 && age <= PLUGIN_ERROR_WINDOW_MS)
        return ATTR_PLUGIN;
    return ATTR_NONE;
}

/* 扫 dropbox 取最近一次崩溃的归因。min_ts_ms：只认不早于此刻的事件（0=不限）。
 * src 写出命中的文件名。读不到或正文无特征 → ATTR_NONE（交给排除法）。 */
/* ------------------------------------------- dropbox 窗口统计（崩溃一手证据） ====
 *
 * 旧实现只取「时间最新的一条」做归因，有两个硬伤：
 *   ① dropbox 里同时躺着别的应用的崩溃（data_app_crash 等），最新的那条
 *      可能跟 SystemUI 毫无关系 -> 归因被污染；
 *   ② 只拿一条，判不出就落回排除法，「其实崩了 3 次」这个事实被浪费掉。
 *
 * 新实现按「时间窗口 + 正文特征」统计：
 *   只把窗口内、正文归因命中的条目算作一次界面崩溃。别的应用崩溃正文里
 *   没有 systemui 特征，天然被过滤。这样条数本身就是可靠信号。
 *
 * 正文可能是 .txt 或 .txt.gz。camd 是静态链接（proot 里没有 libz.a），
 * 不自带 inflate，改调设备自带的 toybox gzip -dc（真机已确认 /system/bin/
 * gzip 是 toybox 的软链且支持 -dc），KSU/Magisk 的 busybox 作为兜底。
 */

/* dropbox 崩溃类条目名判定。
 *   system_app_*     -> uid 1000 的进程（SystemUI 走这条）
 *   system_server_*  -> system_server 自身（正文会被 attribute_text 排除：
 *                       它崩是整个系统重启，不该算到界面头上）
 *   data_app_*       -> 普通应用（正文无特征时自动被过滤，列出来只是不漏）
 */
static int is_crash_entry(const char *nm)
{
    static const char *const pre[] = {
        "system_app_crash@", "system_app_anr@",
        "system_server_crash@", "system_server_watchdog@", "system_server_anr@",
        "data_app_crash@", "data_app_anr@",
        NULL
    };
    int i;

    if (!nm) return 0;
    for (i = 0; pre[i]; i++)
        if (strncmp(nm, pre[i], strlen(pre[i])) == 0) return 1;
    return 0;
}

/* ---------------------------------------------------------- gz 解压器探测
 *
 * .gz 条目要用设备自带的 gzip 解（camd 静态链接，proot 里没有 libz.a，
 * 不带 inflate）。候选有 5 个，但原来「每条 .gz 都从第 1 个候选试起」，
 * 一个 tick 最多 24 条 → 最坏 120 次 fork，且每条都在重复证明同一件事：
 * 这台机器到底哪个解压器能用。
 *
 * 所以把探测结果缓存下来，并区分两种失败：
 *   ① 候选跑不起来（exit 126/127：不存在 / 不可执行）→ 换下一个候选；
 *   ② 候选能跑但这条数据坏（CRC 错、截断）→ 是条目问题，不该怪解压器。
 *
 * 负缓存必须可回退：首次探测正好赶上开机早期 SELinux 还没放行、
 * 或者 busybox 正被播种，会把「本机没有解压器」永久记死，之后所有
 * 崩溃条目都归因失败（等于瞎了）。所以全失败后每 GZ_NEG_MAX 次
 * 重新探测一轮；正向缓存的解压器一旦失效（被删/换 ROM）也立刻重探。 */
#define GZ_NEG_MAX 8

static int gz_decoder = -1;    /* -1=未探测  >=0=可用候选下标  -2=全失败 */
static int gz_neg;             /* 负缓存后已跳过多少条 */

static const char *const gz_cand[] = {
    /* 首选随 camd 落在 DE 目录的自带 busybox（由 camd_service.sh 播种）：
     * 不依赖 PATH，也不依赖 ROM 的 toybox 是否编入 gzip applet。 */
    "/data/adb/camellia/busybox gzip -dc '%s' 2>/dev/null",
    "/system/bin/toybox gzip -dc '%s' 2>/dev/null",
    "/system/bin/gzip -dc '%s' 2>/dev/null",
    "/data/adb/ksu/bin/busybox gzip -dc '%s' 2>/dev/null",
    "/data/adb/magisk/busybox gzip -dc '%s' 2>/dev/null",
    NULL
};

/* 用某个候选解一条。1=成功；0=该候选不可用；-1=候选可用但本条数据坏 */
static int gz_try(const char *tpl, const char *path, char *buf, size_t n)
{
    char cmd[640];
    FILE *p;
    size_t got;
    int st;

    snprintf(cmd, sizeof(cmd), tpl, path);
    p = popen(cmd, "r");
    if (!p) return 0;                       /* fork 不出去，换个候选 */
    got = fread(buf, 1, n - 1, p);
    st  = pclose(p);
    if (got > 0) { buf[got] = 0; return 1; }

    /* 126/127 是 shell 明确告诉「这个命令跑不了」，其余非零一律当数据坏。
     * 拿不准（st==-1）时按不可用处理，宁可多试一个候选取到数据。 */
    if (st == -1) return 0;
    if (WIFEXITED(st) && (WEXITSTATUS(st) == 126 || WEXITSTATUS(st) == 127))
        return 0;
    return -1;
}

static int gz_decode(const char *path, char *buf, size_t n)
{
    int i, r;

    if (gz_decoder >= 0) {
        r = gz_try(gz_cand[gz_decoder], path, buf, n);
        if (r == 1) return 0;
        if (r < 0) return -1;               /* 解压器没问题，是这条坏了 */
        rlog("GZ_DECODER_LOST idx=%d，重新探测", gz_decoder);
        gz_decoder = -1;                    /* 解压器没了，立刻重探 */
    }

    if (gz_decoder == -2) {
        if (++gz_neg < GZ_NEG_MAX) return -1;
        gz_neg = 0;                         /* 到点重探一轮，别把负缓存记死 */
        gz_decoder = -1;
    }

    for (i = 0; gz_cand[i]; i++) {
        r = gz_try(gz_cand[i], path, buf, n);
        if (r == 1) {
            if (gz_decoder != i) rlog("GZ_DECODER=%d %s", i, gz_cand[i]);
            gz_decoder = i;
            gz_neg = 0;
            return 0;
        }
        if (r < 0) {                        /* 能跑，只是这条数据坏 */
            gz_decoder = i;
            gz_neg = 0;
            return -1;
        }
    }
    rlog("GZ_DECODER_NONE 5 个候选都不可用，%d 条后重探", GZ_NEG_MAX);
    gz_decoder = -2;
    gz_neg = 0;
    return -1;
}

/* 读 dropbox 条目正文，自动处理 .gz。0=成功，-1=失败。
 * 只用于崩溃条目（通常 < 100KB）；读不全不影响归因 —— 堆栈特征在头部。 */
static int dropbox_read_body(const char *path, char *buf, size_t n)
{
    size_t plen;
    int fd;

    if (!path || !*path || !buf || n < 2) return -1;
    buf[0] = 0;
    plen = strlen(path);

    if (plen > 3 && strcmp(path + plen - 3, ".gz") == 0) {
        if (gz_decode(path, buf, n) == 0) return 0;
        rlog("DROPBOX_GZ_FAIL path=%s", path);
        return -1;
    }

    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    {
        ssize_t r = read(fd, buf, n - 1);
        close(fd);
        if (r <= 0) return -1;
        buf[r] = 0;
    }
    return 0;
}

/* ---- dropbox_window 的事件驱动缓存 --------------------------------
 * dropbox 里每次崩溃都是「新建一个文件」，所以「条目集合有没有变」可以用目录
 * 自身的 stat 判定 —— 本文件的 drop_max() / drop_entry_count() 已经在用同一
 * 套 (mtime,size) 指纹，此处沿用，但缓存的是**逐条归因明细**而不只是计数：
 * 本函数最贵的部分不是 readdir，而是把窗口内条目正文读出来做 process_owner /
 * attribute_text 解析（单条上限 CRASH_TEXT_MAX）。命中缓存后完全跳过读正文。
 *
 * 与那两个函数的关键差别：它们的返回值只取决于目录内容，而本函数的统计结果
 * 还取决于调用方传入的 since_ms（窗口左界右移会让旧条目滑出）。所以缓存的
 * 不是「统计结果」而是「条目明细」，命中后用当前 since_ms 在内存里重新过滤、
 * 重新累加 —— 零 IO，且窗口滑动语义与每次全量扫描完全一致。
 *
 * 命中判据必须包含 since_ms >= db_cache_since：
 *   · since_ms 变大（窗口左界右移）→ 所需集合是上次扫描结果的子集，可复用
 *   · since_ms 变小（窗口变宽，如某些调用点传 0 要全量历史）→ 上次缓存可能
 *     漏掉了当时被窗口滤掉的条目，必须重扫，否则会少报崩溃、漏救援
 * 这条判据同时保证「只读窗口内正文」的省 IO 特性不被破坏。
 *
 * 为什么还要 DB_FORCE_RESCAN 周期重扫：mtime 是秒级精度，若扫描与崩溃落在
 * 同一秒，新文件的秒级 mtime 与缓存指纹相同 → 缓存不失效 → 漏掉这一条。而
 * 「崩一次就停」的 B 类故障恰恰只产生一条，漏掉就等于救援彻底失效。周期
 * 强制重扫把这种同秒碰撞的暴露窗口压到有限次调用内（该计数只在命中路径上
 * 递增，目录一旦真变化就已经重扫过，不需要再补）。
 */
#define DB_CACHE_MAX 24
#define DB_FORCE_RESCAN 40
static struct {
    long ts;
    int  attr;
    int  proc_hit;
    char name[300];
} db_cache[DB_CACHE_MAX];
static int  db_cache_n     = 0;
static int  db_cache_ok    = 0;    /* 缓存是否已填充过 */
static long db_cache_mt    = -3;
static long db_cache_ns    = -1;   /* 目录 mtime 纳秒：秒级同秒碰撞会让新崩溃漏缓存 */
static long db_cache_sz    = -1;
static long db_cache_since = 0;    /* 填充缓存时用的窗口左界（毫秒） */
static int  db_cache_tick  = 0;

/* 全量重扫：只读 since_ms 窗口内的条目（与旧实现的读取范围一致），
 * 把每条的时间戳、文件名、二级归因、一级进程命中都存进缓存。 */
static int dropbox_scan_cache(long since_ms)
{
    DIR *d;
    struct dirent *de;
    char names[DB_CACHE_MAX][300];
    long ts[DB_CACHE_MAX];
    int n = 0, i, j;

    db_cache_n = 0;
    d = opendir(DROPBOX_DIR);
    /* 打不开就明确告诉调用方「缓存无效」：绝不能把「这次没读到」固化成
     * 「没有崩溃」，否则一次瞬时权限失败就会让救援静默失效到下次崩溃。 */
    if (!d) return 0;
    while ((de = readdir(d)) != NULL) {
        const char *at;
        long t;

        if (!is_crash_entry(de->d_name)) continue;
        at = strchr(de->d_name, '@');
        if (!at) continue;
        t = atol(at + 1);
        if (t <= 0) continue;
        if (since_ms > 0 && t < since_ms) continue;
        /*
         * 维护「时间最新的 DB_CACHE_MAX 条」，而不是满了就 break。
         * readdir 顺序不确定，直接截断可能把真正最新的崩溃条目丢掉，
         * 那是漏报 —— 比多扫几个文件危险得多。
         */
        if (n < DB_CACHE_MAX) {
            snprintf(names[n], sizeof(names[n]), "%s", de->d_name);
            ts[n] = t;
            n++;
        } else {
            int mn = 0;
            for (i = 1; i < n; i++) if (ts[i] < ts[mn]) mn = i;
            if (t > ts[mn]) {
                snprintf(names[mn], sizeof(names[mn]), "%s", de->d_name);
                ts[mn] = t;
            }
        }
    }
    closedir(d);

    /* 时间倒序：最新的一条排在最前，保证 best 的挑选与旧实现一致 */
    for (i = 0; i < n; i++) {
        for (j = i + 1; j < n; j++) {
            if (ts[j] > ts[i]) {
                long tt = ts[i];
                char tmp[300];
                ts[i] = ts[j];
                ts[j] = tt;
                snprintf(tmp, sizeof(tmp), "%s", names[i]);
                snprintf(names[i], sizeof(names[i]), "%s", names[j]);
                snprintf(names[j], sizeof(names[j]), "%s", tmp);
            }
        }
    }

    for (i = 0; i < n; i++) {
        char path[420];
        char buf[CRASH_TEXT_MAX + 1];
        size_t blen;

        db_cache[i].ts = ts[i];
        db_cache[i].attr = ATTR_NONE;
        db_cache[i].proc_hit = 0;
        snprintf(db_cache[i].name, sizeof(db_cache[i].name), "%s", names[i]);

        snprintf(path, sizeof(path), "%s/%s", DROPBOX_DIR, names[i]);
        if (dropbox_read_body(path, buf, sizeof(buf)) != 0) continue;
        blen = strlen(buf);
        /* 一级：进程名命中就记一笔。堆栈只有框架类时二级归因是 NONE，
           但「界面进程又崩了一次」是事实，必须留下痕迹。 */
        db_cache[i].proc_hit = (process_owner(buf, blen) != ATTR_NONE);
        db_cache[i].attr = attribute_text(buf, blen);
    }
    db_cache_n = n;
    return 1;
}

/* 统计窗口内「与界面相关」的崩溃条数，并给出最新命中条目的归因。
 *   cnt      -> 二级条数：堆栈归因命中的条数（可 NULL）
 *   attr_out -> 最新命中条目的归因（无命中为 ATTR_NONE）
 *   src/sn   -> 最新命中条目的文件名（日志用，可 NULL）
 *   proc_cnt -> 一级条数：Process: 行命中界面进程的条数，含归因 NONE 者
 *               （可 NULL）。堆栈只有框架类时二级为 0、一级仍 >0，
 *               这正是「还在崩但判不出是谁」的情形，不能当没崩。
 * 返回二级条数。 */
static int dropbox_window(long since_ms, int *cnt, int *attr_out,
                          char *src, size_t sn, int *proc_cnt)
{
    int i, total = 0, proc_total = 0;
    int best_attr = ATTR_NONE;
    char best[300] = "";
    long best_ts = 0;
    struct stat sb;
    long nmt = -2, nns = -1, nsz = 0;

    if (cnt) *cnt = 0;
    if (attr_out) *attr_out = ATTR_NONE;
    if (proc_cnt) *proc_cnt = 0;
    if (src && sn) src[0] = 0;

    if (stat(DROPBOX_DIR, &sb) == 0) {
        nmt = (long) sb.st_mtime;
        /* 纳秒也纳入指纹：dropbox 的崩溃条目文件名即时间戳，秒级粒度下
         * 「崩溃与本次扫描落在同一秒」会让指纹不变 → 该条漏进缓存 →
         * 「崩一次就停」的 B 类故障救援彻底失效。这正是强制重扫存在的
         * 唯一理由；有了纳秒，碰撞窗口降到纳秒级，强制重扫退化为纯兜底，
         * 间隔得以从 10 次调用放宽到 40 次（读量均摊降 4 倍）。 */
        nns = (long) sb.st_mtim.tv_nsec;
        nsz = (long) sb.st_size;
    }

    /*
     * 只在「目录指纹变了 / 周期到了」时才真正扫盘读正文。
     * 缓存一律按全量（since_ms=0）填充：各调用方要的窗口并不相同
     * （B 段 60s、A 段全量），若按调用方窗口填缓存，则「先窄后宽」
     * 的调用顺序会让宽窗口请求每次都判定失效 —— 实测每 tick 重扫整个
     * dropbox 目录并读全部正文（193 文件 ≈ 1.6MB/tick ≈ 54KB/s），
     * 成为 camd 稳态读量的最大来源。全量填充后，窄窗口由下方 ts 过滤
     * 满足，语义完全不变，而重扫只在目录真的变化或周期到期时发生。
     */
    if (!db_cache_ok || nmt != db_cache_mt || nns != db_cache_ns
        || nsz != db_cache_sz || ++db_cache_tick >= DB_FORCE_RESCAN) {
        db_cache_tick = 0;
        if (dropbox_scan_cache(0)) {
            db_cache_mt = nmt;
            db_cache_ns = nns;
            db_cache_sz = nsz;
            db_cache_since = 0;
            db_cache_ok = 1;
        } else {
            /* 扫描失败：既不算命中也不算已扫，下次调用重试 */
            db_cache_ok = 0;
            db_cache_n = 0;
        }
    }

    for (i = 0; i < db_cache_n; i++) {
        if (since_ms > 0 && db_cache[i].ts < since_ms) continue;
        if (db_cache[i].proc_hit) proc_total++;
        if (db_cache[i].attr == ATTR_NONE) continue;  /* 二级判不出：只算一级 */
        total++;
        if (db_cache[i].ts > best_ts) {
            best_ts = db_cache[i].ts;
            best_attr = db_cache[i].attr;
            snprintf(best, sizeof(best), "%s", db_cache[i].name);
        }
    }

    if (cnt) *cnt = total;
    if (attr_out) *attr_out = best_attr;
    if (proc_cnt) *proc_cnt = proc_total;
    if (src && sn) snprintf(src, sn, "%s", best);
    return total;
}

/* ---------------------------------------- pid 变化历史（崩溃的间接证据） ====
 *
 * pid 只能证明「进程重启过」。软重启后的空窗期、zygote 预热、正常内存回收
 * 之后的重启，都会让 pid 变一次。所以 pid 变化不当触发条件，只记账，
 * 由窗口内的累计次数参与判定（与 dropbox 条数取较大者）。
 */
#define HIST_MAX 16

static int hist_add_file(const char *rel, long now_s, int window_sec)
{
    char path[340], out[640];
    long v[HIST_MAX + 4];
    int n = 0, i, kept = 0;
    FILE *f;
    size_t olen = 0;

    cam_path(rel, path, sizeof(path));
    f = fopen(path, "r");
    if (f) {
        while (n < HIST_MAX + 4 && fscanf(f, "%ld", &v[n]) == 1) n++;
        fclose(f);
    }
    /* 丢掉窗口外的；硬重启后 uptime 归零，v[i] > now 的也丢 */
    for (i = 0; i < n; i++) {
        if (v[i] <= now_s && now_s - v[i] <= window_sec) {
            if (kept != i) v[kept] = v[i];
            kept++;
        }
    }
    n = kept;
    if (n >= HIST_MAX) {                 /* 满了挤掉最旧 */
        for (i = 1; i < n; i++) v[i - 1] = v[i];
        n--;
    }
    v[n++] = now_s;

    out[0] = 0;
    for (i = 0; i < n; i++) {
        int w = snprintf(out + olen, sizeof(out) - olen,
                         (i == 0) ? "%ld" : " %ld", v[i]);
        if (w <= 0) break;
        olen += (size_t)w;
        if (olen + 24 >= sizeof(out)) break;
    }
    f = fopen(path, "w");
    if (f) {
        fputs(out, f);
        fputc('\n', f);
        fclose(f);
    }
    return n;
}

/* 清空窗口计数：安装事件之后必须重新判定，不能带着事件前的旧崩计数。
 * （否则「装包前崩了 2 次 + 装包后崩 1 次」会被合成 3 次，误判新包是坏包） */
static void hist_reset_file(const char *rel, const char *tag)
{
    char path[340];
    cam_path(rel, path, sizeof(path));
    unlink(path);
    rlog("%s", tag);
}

static int hist_count_file(const char *rel, long now_s, int window_sec)
{
    char path[340];
    FILE *f;
    long v;
    int n = 0;

    cam_path(rel, path, sizeof(path));
    f = fopen(path, "r");
    if (!f) return 0;
    while (fscanf(f, "%ld", &v) == 1)
        if (v <= now_s && now_s - v <= window_sec) n++;
    fclose(f);
    return n;
}

/* ---- 账本封装 ----
 * B 段：state/crash_hist   —— 解锁后，装包/更新导致的重启循环（窗口短）
 * A 段：state/a_crash_hist —— 开机 DE 阶段，界面起不来（窗口长）
 * 两者物理隔离。共用一份账会让 A 段 180s 的长窗口把 B 段刚清零的计数
 * 又算进来，也会让 B 段的 watch_install 重置误伤 A 段。 */
static int  crash_hist_add(long now_s, int window_sec)
{ return hist_add_file("state/crash_hist", now_s, window_sec); }

static int  crash_hist_count(long now_s, int window_sec)
{ return hist_count_file("state/crash_hist", now_s, window_sec); }

static void crash_hist_reset(void)
{ hist_reset_file("state/crash_hist", "CRASH_HIST_RESET"); }

static int  a_hist_add(long now_s, int window_sec)
{ return hist_add_file("state/a_crash_hist", now_s, window_sec); }

static int  a_hist_count(long now_s, int window_sec)
{ return hist_count_file("state/a_crash_hist", now_s, window_sec); }

static void a_hist_reset(void)
{ hist_reset_file("state/a_crash_hist", "A_HIST_RESET"); }

/* A 段专用：每 tick 采样 SystemUI 的 pid，变化即记一笔「A 段账」。
 *
 * 为什么不能只靠 pidof 判死活：崩溃循环时 pidof 大概率是有值的 ——
 * Zygote 刚 fork 出进程、还没来得及崩，采样恰好落在窗口里。旧 A 段判据
 * 「pidof 有值就 return 0」于是直接放过，而这恰恰是 DE 黑屏循环最典型的
 * 形态（3 秒一崩，phases 里永远能抓到活进程）。
 * pid 在同一窗口里反复变化，才是「起不来」的可靠信号。
 *
 * 采样必须放在 up<90 早退之前调用：崩溃循环最早出现在开机后 10~60s，
 * 若等 90s 才开始记账，第一波崩已经漏光，账本为空 → 又判不出来。
 *
 * dry 模式只读不写：诊断不该搅乱账本，否则 --self-test 跑一次就把真机
 * 上的计时弄脏。
 *
 * 返回值：A 段窗口内的 pid 变化次数。
 */
static int a_pid_track(cfg_t *c, int dry)
{
    long up = now_up();
    int cur = pid_of_pkg_fresh("com.android.systemui");
    long old;

    if (!dry) {
        old = st_get("apid_sysui");
        /* old<=0 表示「上次采样时没进程」或首次运行。
         * 从无进程→有进程是启动，不是崩溃，不记账。 */
        if (old > 0 && (long) cur != old) {
            rlog("A_PID_CHANGE sysui %ld -> %d", old, cur);
            a_hist_add(up, c->a_window_sec);
        }
        st_set("apid_sysui", cur);   /* 无条件落盘（含 0）：同一事件只记一次 */
    }
    return a_hist_count(up, c->a_window_sec);
}

/* 该归因「可行动」吗？= 对应包当前是否真有 /data/app 覆盖层。
 *
 * 为什么要这道门禁：修复动作只有一种 —— 删覆盖层 + 软重启。归因指向的包
 * 若根本没有覆盖层，删包必然在 find_ovl 处失败，这一轮就白跑（每次约
 * tick_sec 秒）；而 dropbox 证据有窗口寿命，空转几轮后证据滑出窗口、
 * 二级归因退化成 NONE，才勉强落到「覆盖层存在性」判据把活干完。
 *
 * 2026-11-03 实弹就是这形态：坏的明明是 SystemUI 覆盖层，但崩溃堆栈里
 * 带着 plugin 帧（SystemUI 启动时会主动加载 plugin 包），静态规则判成
 * PLUGIN；PLUGIN 没有覆盖层 → 每轮空转 150s，共耗 211s / 8 次崩溃才救回。
 *
 * 加门禁后：判出 PLUGIN 但 plugin 无覆盖层 → 该归因作废（无修复价值），
 * 继续往下走覆盖层存在性判据，立刻落到唯一能动手的 sysui 覆盖层。
 *
 * 注意这里只判「有没有覆盖层」，不判归因对错 —— 归因层不能因为怀疑自己
 * 就把结论丢掉，那样会让只剩单包覆盖层的现场连动手机会都没有。 */
static int attr_actionable(int attr)
{
    pkginfo_t pi;

    if (attr == ATTR_SYSUI) {
        return pkg_query_tick("com.android.systemui", &pi) == 1 &&
               strncmp(pi.code_path, APP_DIR, strlen(APP_DIR)) == 0;
    }
    if (attr == ATTR_PLUGIN) {
        return pkg_query_tick("miui.systemui.plugin", &pi) == 1 &&
               strncmp(pi.code_path, APP_DIR, strlen(APP_DIR)) == 0;
    }
    /* BOTH：调用方两边都会试，至少有一边能动手 */
    return 1;
}

/* 综合归因：dropbox 一手证据 → 插件加载期标记 → 改动面排除法。
 * 返回 ATTR_PLUGIN / ATTR_SYSUI / ATTR_NONE；why 写判定依据。 */
static int culprit_attr(long min_ts_ms, char *why, size_t wn)
{
    char src[320];
    char note[96] = "";   /* ①②「判出但删不了」的记录，拼进最终 why */
    int a;

    {
        int cnt = 0;
        dropbox_window(min_ts_ms, &cnt, &a, src, sizeof(src), NULL);
        if (a != ATTR_NONE) {
            if (attr_actionable(a)) {
                if (why && wn) snprintf(why, wn, "dropbox:%s(cnt=%d)", src, cnt);
                return a;
            }
            /* 归因包没覆盖层 → 删不了。别把结论丢掉就走人：继续往下看
             * 覆盖层存在性判据，找真正能动手的候选。 */
            rlog("ATTR_UNActionable attr=%d src=%s cnt=%d -> 继续寻可行动候选",
                 a, src, cnt);
            snprintf(note, sizeof(note), "dropbox:%s(cnt=%d)但无覆盖层; ",
                     src, cnt);
        }
    }
    a = attr_plugin_state();
    if (a != ATTR_NONE) {
        if (attr_actionable(a)) {
            if (why && wn) snprintf(why, wn, "plugin_state=LOADING/ERROR");
            return a;
        }
        /* 同理：plugin 报 LOADING/ERROR 但它本身没覆盖层（原版 ROM 包在报错），
         * 删无可删，继续往下找候选。 */
        rlog("ATTR_UNActionable plugin_state attr=%d -> 继续寻可行动候选", a);
        strncat(note, "plugin_state判plugin但无覆盖层; ",
                sizeof(note) - strlen(note) - 1);
    }
    {
        pkginfo_t si, pi;
        int s_ovl, p_ovl;
        int hs = pkg_query_tick("com.android.systemui", &si);
        int hp = pkg_query_tick("miui.systemui.plugin", &pi);
        s_ovl = hs && strncmp(si.code_path, APP_DIR, strlen(APP_DIR)) == 0;
        p_ovl = hp && strncmp(pi.code_path, APP_DIR, strlen(APP_DIR)) == 0;
        /* lu 只用来比「谁更新」，拿不到就退回目录 mtime（见 ovl_stamp） */
        {
            long lu_s = si.last_update ? si.last_update : ovl_stamp(si.code_path);
            long lu_p = pi.last_update ? pi.last_update : ovl_stamp(pi.code_path);

            if (p_ovl && !s_ovl) {
                if (why && wn) snprintf(why, wn, "%sonly-plugin-overlay(lu=%ld)", note, lu_p);
                return ATTR_PLUGIN;
            }
            if (s_ovl && !p_ovl) {
                if (why && wn) snprintf(why, wn, "%sonly-sysui-overlay(lu=%ld)", note, lu_s);
                return ATTR_SYSUI;
            }
            if (s_ovl && p_ovl) {
                if (lu_s >= lu_p) {
                    if (why && wn) snprintf(why, wn, "%sboth-overlay-sysui-newer(%ld>=%ld)",
                                            note, lu_s, lu_p);
                    return ATTR_SYSUI;
                }
                if (why && wn) snprintf(why, wn, "%sboth-overlay-plugin-newer(%ld>%ld)",
                                        note, lu_p, lu_s);
                return ATTR_PLUGIN;
            }
        }
        /* 判不出来 -> 双修。两方都没有覆盖层时 find_ovl 全失败，
         * 调用方自然什么都不做，不会误删。 */
        if (why && wn) snprintf(why, wn, "%sunknown->both(sysui=%s,plugin=%s)", note,
                                hs ? (s_ovl ? "app" : "rom") : "?",
                                hp ? (p_ovl ? "app" : "rom") : "?");
    }
    return ATTR_BOTH;
}

/* ------------------------------------------------------- 周期自检（tick） */

/*
 * 由 camd 守护循环每秒调用一次，内部按 cfg.tick_sec 节流。
 * 决策链：熔断 → 观察期 → 是否有崩溃 → 是否该修 → 修
 */

/* ================================================================ DE 判定 ====
 * CE（Credential Encrypted）可用 ⇔ /data/user/0 存在。
 * 实测（de_probe，Android 17 / HyperOS）：DE 阶段 /data/user/0 不存在
 * （File.exists=false），/data/data 可读但条目为空族；解锁瞬间出现。
 *
 * 这条判据决定了「要不要走 A 段」：
 *   DE  → 用户看不到锁屏、解不了锁、pm 不可用，只能删包重启（A 段）
 *   CE  → 用户已经在桌面，可以走 pm 通道（B 段）
 */
/*
 * 直接 exec getprop，不经 /system/bin/sh。
 * 为什么不复用 sh_capture：fork 之后加载 /system/bin/sh（mksh + linker + libc）
 * 的读量会被记在本进程 rchar 上 —— 实测单次 ce_ok 探测带来约 500KB。
 * getprop 只需要一个 key，完全用不到 shell 的解析能力，直连可省掉这一层。
 */
static int prop_get(const char *key, char *out, size_t n)
{
    struct sigaction old, dfl;
    int pfd[2], st, rc = -1;
    pid_t p;
    ssize_t r;
    size_t used = 0;
    char buf[128];

    if (!out || n < 2) return -1;
    out[0] = 0;
    if (pipe(pfd) != 0) return -1;

    sigaction(SIGCHLD, NULL, &old);
    dfl = old;
    dfl.sa_handler = SIG_DFL;
    dfl.sa_flags = 0;
    sigemptyset(&dfl.sa_mask);
    sigaction(SIGCHLD, &dfl, NULL);

    p = fork();
    if (p == 0) {
        close(pfd[0]);
        dup2(pfd[1], 1);
        if (pfd[1] > 2) close(pfd[1]);
        execl("/system/bin/getprop", "getprop", key, (char *) 0);
        _exit(127);
    }
    close(pfd[1]);
    if (p < 0) { close(pfd[0]); sigaction(SIGCHLD, &old, NULL); return -1; }

    while ((r = read(pfd[0], buf, sizeof(buf))) > 0) {
        if (used + (size_t) r < n) {
            memcpy(out + used, buf, (size_t) r);
            used += (size_t) r;
        }
    }
    close(pfd[0]);
    out[used] = 0;

    st = 0;
    if (waitpid(p, &st, 0) == p && WIFEXITED(st) && WEXITSTATUS(st) == 0) rc = 0;
    sigaction(SIGCHLD, &old, NULL);
    return rc;
}

/* ce_ok 的探测结果缓存：提到文件作用域，供只读的 ce_ok_peek 共享。 */
static long ce_cache_up = -1;
static int ce_cache_val = 0;
static int ce_cache_logged = -2;

static int ce_ok(void)
{
    /* 【判据演进史，别再退回老路】
     * v1: access("/data/user/0", F_OK) —— 错。F_OK 只查路径可解析，
     *     CE 存储的挂载点在 DE 阶段就已存在 → 恒判 CE，A 段永不执行。
     * v2: getprop + readdir 双级 —— 仍错。FBE 只加密【文件内容】，
     *     目录项（包名）在 DE 阶段完全可见，readdir 照样清点出全部
     *     子项 → 依旧恒判 CE。
     *
     * v3（当前）：只认 init 设置的权威属性。
     *   sys.user.0.ce_available 由 init 在 CE 存储挂载且用户解锁后才置
     *   true（本机实测输出 "true"/"false" 字符串，不是 "1"/"0"）。
     *   只有明确 true 才算 CE；空值 / false / 命令失败一律判 DE ——
     *   宁可多跑一次 A 段诊断，也不能在真黑屏现场装聋。
     *
     * 60s 缓存：DE/CE 只会在开机解锁的那一刻翻转，之后再问也不会变。
     * 缓存过短（原为 5s）会让每轮 ui_dump 都穿透并 fork 一次 getprop，
     * 实测每次约 500KB 读量 —— 这是 camd 稳态 IO 的最大单一来源。 */
    long up = now_up();
    char b[64] = "";
    int r;

    if (ce_cache_up >= 0 && up - ce_cache_up < 60) return ce_cache_val;

    r = 0;
    if (prop_get("sys.user.0.ce_available", b, sizeof(b)) == 0) {
        r = (b[0] == '1' || b[0] == 't') ? 1 : 0;
    }

    /* 值翻转时记一条，便于事后核对 DE/CE 到底哪一刻切的 */
    if (r != ce_cache_logged) {
        rlog("PHASE_DET ce_avail='%s' up=%ld ce=%d de=%d", b, up, r, !r);
        ce_cache_logged = r;
    }

    ce_cache_up = up;
    ce_cache_val = r;
    return r;
}

static int phase_de(void)
{
    return !ce_ok();
}

/*
 * 只读缓存版本：绝不触发 fork/IO。
 * ui_dump 每 6s 都要往状态文件写 phase 字段，但真实的 DE/CE 只在开机
 * 解锁那一刻翻转一次 —— 展示路径没有任何理由反复探测（实测每次探测
 * 会因加载 getprop 而带来数百 KB 读量）。缓存未初始化时按 DE 返回，
 * 与 ce_ok 的保守口径保持一致。
 */
static int ce_ok_peek(void)
{
    return ce_cache_up >= 0 ? ce_cache_val : 0;
}

static int phase_de_peek(void)
{
    return !ce_ok_peek();
}

/* ------------------------------------------------------ 挂载模块检测/禁用
 *
 * 为什么不用 /proc/self/mountinfo：
 *   KernelSU 的模块挂载由 ksud 注入到「每个 app 进程自己的 mount
 *   namespace」。camd 从 init（service.d）起来，namespace 是干净的 ——
 *   实测本机装着 23 个模块（含 systemui_mount），root shell 里
 *   grep /data/adb /proc/self/mountinfo 得到 0 条。mountinfo 路线在任何
 *   一台 KernelSU 设备上都会静默失效，假阴性比不检测更坏。
 *
 * 改成纯文件系统判定（De 阶段可读，de_probe 已验证）：
 *   A. <mod>/system/priv-app/MiuiSystemUI 等      —— 标准模块布局
 *   B. <mod>/ 下 *.apk 名字含 systemui           —— mountify 类素材外置
 *   C. service.sh / post-fs-data.sh 等含 systemui —— 运行时 bind mount
 *
 * 已带 disable / remove 的模块不算「活跃」，但要单独报出来：那意味着
 * 上一次已经处置过、只差重启生效，这种只重启、不重复写 disable。
 */

/* 大小写不敏感子串匹配 */
static int ci_has(const char *hay, const char *needle)
{
    size_t nl = strlen(needle), i, j;
    if (!nl) return 0;
    for (i = 0; hay[i]; i++) {
        for (j = 0; j < nl; j++) {
            unsigned char a = (unsigned char)hay[i + j], b = (unsigned char)needle[j];
            if (!a) return 0;
            if (a >= 'A' && a <= 'Z') a = (unsigned char)(a - 'A' + 'a');
            if (b >= 'A' && b <= 'Z') b = (unsigned char)(b - 'A' + 'a');
            if (a != b) break;
        }
        if (j == nl) return 1;
    }
    return 0;
}

/* dir 下是否有任一条目名含 needle（大小写不敏感） */
static int dir_has_entry_ci(const char *dir, const char *needle)
{
    DIR *d = opendir(dir);
    struct dirent *e;
    int hit = 0;
    if (!d) return 0;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.' &&
            (e->d_name[1] == 0 || (e->d_name[1] == '.' && e->d_name[2] == 0)))
            continue;
        if (ci_has(e->d_name, needle)) { hit = 1; break; }
    }
    closedir(d);
    return hit;
}

/* 普通文件内容是否含 needle（大小写不敏感），只读前 max 字节 */
static int file_has_ci(const char *path, const char *needle, size_t max)
{
    int fd;
    char *buf;
    ssize_t n;
    int hit = 0;

    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    buf = (char *)malloc(max + 1);
    if (!buf) { close(fd); return 0; }
    n = read(fd, buf, max);
    close(fd);
    if (n > 0) {
        buf[n] = 0;
        hit = ci_has(buf, needle);
    }
    free(buf);
    return hit;
}

/* 一个模块目录是否挂载了 SystemUI / 界面组件 */
static int mod_is_systemui(const char *moddir)
{
    /*
     * 挂载点清单。priv-app 和 app 都要查，这是踩过的坑：
     *   宿主 com.android.systemui → /system_ext/priv-app/MiuiSystemUI
     *   组件 miui.systemui.plugin → /product/app/MIUISystemUIPlugin
     * 组件在 app 不在 priv-app。旧清单只有 priv-app，模块若走「挂组件」路线
     * （把组件塞进 product），A 段会一条都搜不到，静默空转 —— 而这恰恰是
     * 「刷了模块后黑屏」最常见的一类。
     * overlay 也列上：RRO 资源覆盖同样能把界面搞崩，而且不产生 apk 目录。
     */
    static const char *subs[] = {
        /* 宿主（系统界面）常见位置 */
        "system/system_ext/priv-app",
        "system_ext/priv-app",
        "system/priv-app",
        "system/system/priv-app",
        "system/product/priv-app",
        "product/priv-app",
        /* 组件（系统界面组件）—— 真机实测在 /product/app 下 */
        "system/product/app",
        "product/app",
        "system/system_ext/app",
        "system_ext/app",
        "system/system/app",
        "system/app",
        /* RRO 资源覆盖 */
        "system/product/overlay",
        "product/overlay",
        "system/system/overlay",
        "system/overlay",
        "vendor/overlay",
        NULL
    };
    static const char *scripts[] = {
        "service.sh", "post-fs-data.sh", "boot-completed.sh",
        "mount.sh", "customize.sh", NULL
    };
    char p[640];
    int i;

    /* A. 标准 priv-app 布局 */
    for (i = 0; subs[i]; i++) {
        snprintf(p, sizeof(p), "%s/%s", moddir, subs[i]);
        if (dir_has_entry_ci(p, "systemui")) return 1;
    }
    /* B. 模块根上的 apk 素材 */
    if (dir_has_entry_ci(moddir, "systemui")) {
        DIR *d = opendir(moddir);
        struct dirent *e;
        if (d) {
            int hit = 0;
            while ((e = readdir(d)) != NULL) {
                size_t l = strlen(e->d_name);
                if (l > 4 && strcmp(e->d_name + l - 4, ".apk") == 0 &&
                    ci_has(e->d_name, "systemui")) { hit = 1; break; }
            }
            closedir(d);
            if (hit) return 1;
        }
    }
    /* C. 运行时挂载脚本 */
    for (i = 0; scripts[i]; i++) {
        snprintf(p, sizeof(p), "%s/%s", moddir, scripts[i]);
        if (file_has_ci(p, "systemui", 65536)) return 1;
    }
    return 0;
}

/*
 * 扫 /data/adb/modules，挑出「挂载界面」的模块。
 * include_disabled=0：只要活跃的（没有 disable/remove）
 * include_disabled=1：只要已处置过的（有 disable/remove）
 */
/* 模块根目录：默认 /data/adb/modules，CAM_MODULES_DIR 可覆盖（测试用，
 * 免得为了验证扫描逻辑而在生产 modules 下造临时模块 —— 那玩意一旦重启
 * 真的会去挂 SystemUI） */
static const char *mod_root(void)
{
    const char *e = getenv("CAM_MODULES_DIR");
    return (e && *e) ? e : "/data/adb/modules";
}

static int mod_scan(char ids[][64], int max, int include_disabled)
{
    DIR *d;
    struct dirent *e;
    int n = 0;

    d = opendir(mod_root());
    if (!d) return 0;
    while ((e = readdir(d)) != NULL && n < max) {
        char moddir[560], flag[600];
        int dis;

        if (e->d_name[0] == '.') continue;
        snprintf(moddir, sizeof(moddir), "%s/%s", mod_root(), e->d_name);

        snprintf(flag, sizeof(flag), "%s/disable", moddir);
        dis = (access(flag, F_OK) == 0);
        if (!dis) {
            snprintf(flag, sizeof(flag), "%s/remove", moddir);
            dis = (access(flag, F_OK) == 0);
        }
        if (include_disabled ? !dis : dis) continue;

        if (!mod_is_systemui(moddir)) continue;
        snprintf(ids[n], 64, "%s", e->d_name);
        n++;
    }
    closedir(d);
    return n;
}

/* Magisk / KernelSU / APatch 统一约定：模块目录下放 disable 空文件即停用。
 * 这是「下次开机不挂载」，对当前进程无效 —— 所以必须配合重启。 */
static int disable_module(const char *id)
{
    char p[512];
    int fd;
    snprintf(p, sizeof(p), "%s/%s/disable", mod_root(), id);
    fd = open(p, O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
    if (fd < 0) {
        rlog("MOD_DISABLE_FAIL id=%s errno=%d", id, errno);
        return -1;
    }
    close(fd);
    rlog("MOD_DISABLE id=%s path=%s", id, p);
    return 0;
}

static void hard_reboot(void)
{
    char *av[2];
    rlog("HARD_REBOOT");
    sh_run("sync");
    av[0] = "reboot";
    av[1] = NULL;
    run_wait("/system/bin/reboot", av);
}

/* ---------------------------------------------------------- A 段主流程 ====
 *
 * DE 阶段（用户还没解锁）自主急救。此时：
 *   - pm 不可用（system_server 没跑到 boot_completed）
 *   - 锁屏画不出来（SystemUI 崩了），用户永远解不了锁
 *   - 唯一能做的是「在根文件系统层把坏包拿掉 + 重启」
 *   - 唯一的一手证据是 dropbox 正文 + packages.xml + mountinfo
 *
 * 动作顺序（严格按用户定的策略）：
 *   1. 检测到模块挂载 → 置模块 disable → 重启
 *   2. 归因到「安装错包 / 组件」→ 删 /data/app 覆盖目录 → 软重启
 *      （PMS 发现 codePath 不在，自动回落到 ROM 内置版本，等价于
 *       「卸载更新」，但不需要 pm）
 *   3. 归因不到 → 不动手，只写诊断
 *
 * 幂等：同一 boot_id + 同一 lastUpdateTime 只救一次，避免循环删包。
 * 用户重装了包（lastUpdateTime 变）会重新获得一次救援机会。
 */
/* a_phase 的 dry-run 标志。所有「写盘 / 重启」类副作用统一先过这道闸：
 * 之前只有部分分支判断 dry，结果 --a-phase（本该只读）把设备重启了。 */
static int g_a_dry = 0;

static void write_pending(const char *pkg, const char *action, const char *ovl,
                          const char *why)
{
    char dir[320], path[512], tmp[1024];
    FILE *f;

    if (g_a_dry) {
        printf("DRY pending: pkg=%s action=%s ovl=%s why=%s\n",
               pkg, action, ovl ? ovl : "", why ? why : "");
        return;
    }

    cam_path("action", dir, sizeof(dir));
    mkdir_p2(dir, 0771);
    snprintf(path, sizeof(path), "%s/%s.pending", dir, pkg);
    f = fopen(path, "w");
    if (!f) {
        rlog("PENDING_WRITE_FAIL pkg=%s", pkg);
        return;
    }
    fprintf(f, "pkg=%s\naction=%s\novl=%s\nwhy=%s\nts=%ld\n",
            pkg, action, ovl ? ovl : "", why ? why : "", (long)time(NULL));
    fclose(f);

    /* 同步一份摘要进 state：/data/system 下 App 读不到，
     * 只能由 camd 经 camd_status.txt 广播出去。 */
    st_wstr("a_last_action", action);
    st_wstr("a_last_pkg", pkg);
    st_wstr("a_last_why", why ? why : "");
    st_set("a_last_ts", (long)time(NULL));
    /* 交给 App 侧读：/data/system 默认 771，chmod 0644 后 shell 可见 */
    snprintf(tmp, sizeof(tmp), "chmod 0644 '%s' 2>/dev/null", path);
    sh_run(tmp);
}

/* ---------------------------------------------------------------------------
 * A 段单包处置：删 /data/app 覆盖层，让 PMS 回退到 ROM 内置版。
 *
 * 返回 1=已删（调用方负责重启）、0=跳过/无需处理、-1=失败。
 * 抽成函数是为了支持「归因不明时两个包都修」：去重 / 归档 / rm 这套判断
 * 只写一遍，宿主与组件共用。
 *
 * DE 阶段只归档 base.apk（几十 MB 的 so/oat 不碰），给用户留一条「事后能
 * 装回去」的路。归档失败不阻塞删包 —— 黑屏急救时救活开机优先于留证。
 * ------------------------------------------------------------------------- */
static int a_fix_one(cfg_t *c, const char *bid, const char *pkg, int dry,
                     const char *why)
{
    char dir[640], key[128], bak[1024], rm[1400];
    long lu = 0;
    pkginfo_t pi;

    if (pkg_query(pkg, &pi)) lu = pi.last_update;
    snprintf(key, sizeof(key), "a_done_%s", bid);
    if (lu != 0 && st_get(key) == lu) {
        rlog("A_SKIP already handled pkg=%s lu=%ld", pkg, lu);
        return 0;
    }
    if (!find_ovl(pkg, dir, sizeof(dir))) {
        /* ROM 内置包：删了系统就起不来。只记诊断，交给人/下阶段 */
        rlog("A_NO_OVL pkg=%s (ROM builtin, refuse to delete)", pkg);
        write_pending(pkg, "diagnose_only", "", "rom-builtin");
        return 0;
    }
    if (dry) {
        printf("DRY would delete %s -> rm -rf %s then soft reboot\n", pkg, dir);
        return 0;
    }

    st_set(key, lu);
    write_pending(pkg, "delete_apk", dir, why);

    if (bak_save(c, pkg, dir, "a_phase_delete", bak, sizeof(bak)) != 0)
        rlog("A_BAK_FAIL pkg=%s dir=%s (继续删)", pkg, dir);

    rlog("A_FIX pkg=%s ovl=%s", pkg, dir);
    snprintf(rm, sizeof(rm), "rm -rf '%s' 2>/dev/null", dir);
    if (sh_run(rm) != 0 || access(dir, F_OK) == 0) {
        rlog("A_FIX_FAIL pkg=%s dir=%s", pkg, dir);
        return -1;
    }
    sh_run("sync");
    rlog("A_FIX_DONE pkg=%s", pkg);
    st_set("a_fix_ts", (long)time(NULL));
    return 1;
}

static int a_phase(cfg_t *c, int dry)
{
    char ids[16][64];
    char why[256] = "";
    char bid[80];
    int nmod, i, attr, a_circ = 0;
    long up = now_up();

    g_a_dry = dry;

    if (!dry && !c->auto_fix) return 0;

    /* A 段的语义是「用户还没解锁时救」。CE 阶段一律不该动：一旦误用
     * --a-phase --apply，就会在用户正用着手机时删包 / 重启。 */
    if (!dry && !phase_de()) {
        rlog("A_REFUSE not in DE phase");
        printf("REFUSE: A 段只在 DE 阶段执行（当前 CE 已解锁）\n");
        return 0;
    }

    /* A 段崩溃循环采样：必须跑在 up<90 早退之前。
     * 开机后 10~60s 正是崩溃循环高发段，若等 90s 才开始记账，第一波崩
     * 全部漏记，账本恒为 0，循环崩永远判不出来。dry 模式只读不写。 */
    a_circ = a_pid_track(c, dry);

    /* 开机早期不动手：SystemUI 正常启动也要 10~30s，抢跑会误判「崩了」 */
    if (!dry && up < 90) return 0;

    boot_id(bid, sizeof(bid));
    {
        /* 已「A 段出过手」的标记：同一 boot 内最多一次模块处置 */
        char key[128];
        snprintf(key, sizeof(key), "a_mod_%s", bid);
        nmod = mod_scan(ids, 16, 0);
        if (nmod == 0) {
            /*
             * 没有「活跃」的界面模块。若存在已带 disable 的模块，说明上一个
             * boot 已经处置过、并且重启生效了 —— 这里绝不能再重启：
             *   1) disable 已生效，重启不改变任何东西；
             *   2) 硬重启会换 boot_id，本 boot 的幂等标记全部失效，于是每次
             *      开机 DE 阶段都重启一次 → 无限重启循环。
             * 这台机器 /data/adb/modules/systemui_mount 长期带 disable，
             * 走错这条分支就是「手机永远在开机画面打转」。
             * 只记日志，继续往下走归因，去找 /data/app 覆盖层。
             */
            int nd = mod_scan(ids, 16, 1);
            if (nd > 0) {
                static long last_log = 0;
                if (last_log == 0 || up - last_log > 3600) {
                    last_log = up;
                    rlog("A_MOD_ALREADY_DISABLED id=%s (disable 已生效，不重启)", ids[0]);
                }
            }
        }
        if (nmod > 0 && st_get(key) == 0) {
            if (dry) {
                printf("DRY would disable %d module(s) then hard reboot:", nmod);
                for (i = 0; i < nmod; i++) printf(" %s", ids[i]);
                printf("\n");
                return 0;
            }
            st_set(key, 1);
            rlog("A_MOD n=%d first=%s", nmod, ids[0]);
            for (i = 0; i < nmod; i++) disable_module(ids[i]);
            write_pending("miui.systemui.plugin", "disable_module", "", "mounted-module");
            sh_run("sync");
            rlog("A_MOD_DONE reboot");
            hard_reboot();
            return 1;
        }
        if (nmod > 0) {
            rlog("A_SKIP mod already handled boot=%s", bid);
            return 0;
        }
    }

    /* ---- A 段触发判据（本次修复核心）----
     * 旧实现：pidof 有值就 return 0（「还活着就别乱删」）。
     * 该实现的致命漏：崩溃循环时 pidof 大概率有值 —— Zygote 3 秒一拉，
     * 采样大概率落在「进程在但还没崩」的窗口里，于是永远 return 0，
     * DE 黑屏循环救不了。
     *
     * 新判据两条互补，任一成立才继续归因：
     *   ① 进程真的没了（pidof=0）        → 起不来
     *   ② 窗口内 pid 变化 >= a_circ_thr  → 崩溃循环
     * 进程稳定活着（pidof 有值 且 窗口内没反复变 pid）→ 不救：
     * 黑屏可能是渲染卡死/SurfaceFlinger 问题，删包只会让用户更难恢复。 */
    {
        int alive = (pid_of_pkg_fresh("com.android.systemui") > 0);

        if (alive && a_circ < c->a_circ_thr) {
            static long last_skip = 0;
            if (last_skip == 0 || up - last_skip > 600) {
                last_skip = up;
                rlog("A_ALIVE_SKIP sysui alive circ=%d/%d win=%ds",
                     a_circ, c->a_circ_thr, c->a_window_sec);
            }
            return 0;
        }
        rlog("A_TRIGGER sysui %s circ=%d thr=%d win=%ds",
             alive ? "crashloop" : "dead",
             a_circ, c->a_circ_thr, c->a_window_sec);
    }

    attr = culprit_attr(0, why, sizeof(why));
    rlog("A_ATTR %s why=%s",
         attr == ATTR_PLUGIN ? "plugin" :
         attr == ATTR_SYSUI  ? "sysui"  :
         attr == ATTR_BOTH   ? "both"   : "none", why);

    /*
     * 处置名单：定性明确 -> 只动被认定的那个；判不出来（ATTR_BOTH）-> 两个都动。
     *
     * 为什么判不出就双删：归因不明时两个覆盖层都可能是注入源，ROM 内置版
     * 仍在、随时能装回；而猜错一次就要让用户在没有界面的黑屏里再等一轮
     * 重启，代价远高于多删一个已废弃的覆盖层。
     *
     * 两方都没有覆盖层时，两次 a_fix_one 会在 find_ovl 处返回 0，等于不动手。
     * culprit_attr 已不会返回 ATTR_NONE（判不出即 BOTH），故不再需要
     * 「unknown -> 只诊断」这一支。
     */
    {
        const char *cand[2];
        int nc = 0, k, acted = 0;

        /*
         * A 段没有「窗口内已崩 N 次」这个前提，所以归因不明时绝不能双删 ——
         * 那等于每次开机（只要 auto_fix 开着、uptime>90s）都把宿主和组件
         * 的覆盖层删一遍。A 段的保守原则：证据不足就只诊断、不动手。
         * 双修只属于 B 段（那边有「确实崩了 N 次」这个事实做前提）。
         */
        if (attr == ATTR_BOTH) {
            rlog("A_UNKNOWN insufficient evidence -> diagnose only");
            if (dry) {
                printf("DRY 归因不明（%s）→ A 段只诊断，不动手\n", why);
                return 0;
            }
            write_pending("unknown", "diagnose_only", "", why);
            return 0;
        }

        if (attr == ATTR_PLUGIN) {
            cand[nc++] = "miui.systemui.plugin";
        } else if (attr == ATTR_SYSUI) {
            cand[nc++] = "com.android.systemui";
        } else {
            /* 只剩「宿主 + 组件」都判成同一方（理论不可达），按宿主处理 */
            cand[nc++] = "com.android.systemui";
        }
        for (k = 0; k < nc; k++) {
            int r = a_fix_one(c, bid, cand[k], dry, why);
            if (r > 0) acted = 1;
        }
        if (!acted) return 0;
    }

    /* 处置完毕，A 段账本作废（a_phase 自己走软重启，不经过 reboot_after_fix）：
     * ① 软重启前剩余 tick 不该拿旧账再判一次；
     * ② 软重启换掉 system_server 后 SystemUI 的 pid 必然变化，不清 pid 基线
     *    会导致重启后首次采样记一笔假崩，把 180s 窗口弄脏。 */
    a_hist_reset();
    st_set("apid_sysui", 0);
    reset_pid_baselines(c);

    st_set("sr_ts", now_up());
    st_set("obs_ts", now_ms_rt());   /* 有效性观察窗起始(墙钟ms) */
    st_set("sr_count", st_get("sr_count") + 1);
    st_set("sr_total", st_get("sr_total") + 1);
    /* F3：水位用“修复完成这一刻的墙钟”作硬下限，彻底排除重启前/同秒旧条目 */
    { long _n = now_ms_rt(), _d = drop_max(); st_set("sr_drop_max", _n > _d ? _n : _d); }
    rlog("A_SOFT_REBOOT cmd=[%s] total=%ld", c->reboot_cmd, st_get("sr_total"));
    do_soft_reboot(c);
    return 1;
}


/* ---------------- eBPF 加速检测（目标进程退出事件；可选、失败自动回退） ----------------
 * 说明：只用于“把目标(SystemUI/组件)进程退出更早、更省地通知到本进程”——命中即
 * g_force_tick=1（提前触发一次现有 tick 判定），不改任何崩溃记账。加载/挂载失败、
 * 或用户置 ebpf=0，则 g_ebpf_state 非 1，保持原有 pidfd+dropbox 检测（回退）。
 * 注：不要用 signal_generate 判崩——Android 运行时大量非致命 SIGSEGV（实测一次 342 条）。 */
static struct bpf_object *g_ebpf_obj = NULL;
static struct ring_buffer *g_ebpf_rb = NULL;
static int g_ebpf_state = 0;   /* 0=关闭(用户)，1=已启用，2=不可用(已回退) */
static int ebpf_cb(void *ctx, void *data, size_t sz){ (void)ctx;(void)data;(void)sz; g_force_tick=1; return 0; }
static void ebpf_write_state(const char *v){ char st[400],sp[512]; cam_path("state",st,sizeof(st)); snprintf(sp,sizeof(sp),"%s/ebpf_state",st); FILE*f=fopen(sp,"w"); if(f){fputs(v,f);fputc('\n',f);fclose(f);} }
int rescue_ebpf_init(void){
    cfg_t c; cfg_load(&c);
    char st[400]; cam_path("state", st, sizeof(st)); mkdir_p2(st, 0700);
    if (c.ebpf == 0){ g_ebpf_state = 0; ebpf_write_state("disabled"); rlog("EBPF disabled(config)"); return 0; }
    char op[560]; snprintf(op, sizeof(op), "%s/ebpf.o", st);
    FILE *f = fopen(op, "wb"); if (!f){ g_ebpf_state=2; ebpf_write_state("unavailable"); rlog("EBPF tmp write fail"); return -1; }
    fwrite(sysui_mon_bpf_o, 1, sysui_mon_bpf_o_len, f); fclose(f); chmod(op, 0600);
    libbpf_set_print(NULL);
    g_ebpf_obj = bpf_object__open_file(op, NULL);
    if (!g_ebpf_obj){ g_ebpf_state=2; ebpf_write_state("unavailable"); rlog("EBPF obj open fail"); return -1; }
    if (bpf_object__load(g_ebpf_obj)){ g_ebpf_state=2; ebpf_write_state("unavailable"); rlog("EBPF load fail"); bpf_object__close(g_ebpf_obj); g_ebpf_obj=NULL; return -1; }
    struct bpf_program *pr; int n=0;
    bpf_object__for_each_program(pr, g_ebpf_obj){
        if (!bpf_program__attach(pr)){ g_ebpf_state=2; ebpf_write_state("unavailable"); rlog("EBPF attach fail"); bpf_object__close(g_ebpf_obj); g_ebpf_obj=NULL; return -1; }
        n++;
    }
    int mfd = bpf_object__find_map_fd_by_name(g_ebpf_obj, "rb");
    g_ebpf_rb = ring_buffer__new(mfd, ebpf_cb, NULL, NULL);
    if (!g_ebpf_rb){ g_ebpf_state=2; ebpf_write_state("unavailable"); rlog("EBPF ringbuf fail"); return -1; }
    g_ebpf_state = 1; ebpf_write_state("enabled");
    rlog("EBPF enabled attached=%d", n);
    return 0;
}
int rescue_ebpf_pollfd(void){ if (g_ebpf_state==1 && g_ebpf_rb) return ring_buffer__epoll_fd(g_ebpf_rb); return -1; }
void rescue_ebpf_drain(void){ if (g_ebpf_state==1 && g_ebpf_rb) ring_buffer__poll(g_ebpf_rb, 0); }
int  rescue_ebpf_state(void){ return g_ebpf_state; }
static void rescue_ebpf_teardown(void){ if(g_ebpf_rb){ ring_buffer__free(g_ebpf_rb); g_ebpf_rb=NULL; } if(g_ebpf_obj){ bpf_object__close(g_ebpf_obj); g_ebpf_obj=NULL; } }
static void rescue_ebpf_apply(int on){
    if (on) { if (g_ebpf_state==1) return; rescue_ebpf_init(); }
    else { rescue_ebpf_teardown(); g_ebpf_state=0; ebpf_write_state("disabled"); rlog("EBPF disabled(user)"); }
}

int rescue_tick(void)
{
    static long last = 0;
    cfg_t c;
    char why[192];
    char pkgs[8][160];
    long up, sr_ts, dmax, seen;
    int np, i, crash = 0;

    /* cfg_load 现在按 (mtime,size) 缓存，配置没改时只有一次 stat，可以放心
     * 放在节流判断之前 —— 节流必须用最新的 tick_sec 才能生效。 */
    cfg_load(&c);
    up = now_up();
    if (!g_force_tick && up - last < c.tick_sec) return 0;
    g_force_tick = 0;
    last = up;
    /* pkg_epoch 标记的是「新的一轮」，必须放在节流通过之后：被节流挡掉的
     * 调用并不是一轮 tick，让它递增会白白作废跨调用复用缓存。 */
    if (++pkg_epoch == 0) pkg_epoch = 1;
    dsnap_heartbeat();   /* 定时快照：dirty/到期/空闲 判定，见 camellia_dsnap.c */

    np = split_target(&c, pkgs, 8);

    /*
     * 装包检测必须排在 guard() 之前，理由有两层：
     * ① 它是「状态维护」而非「修复动作」—— 熔断不该挡住信息采集；
     * ② 用户装了新包 = 新一轮测试，配额该还，熔断该解。
     *    原来它排在 guard 之后，配额一满停摆就永久失聪，只能重启手机。
     * 检测到真装包时（/data/app 有覆盖层）会顺带清 sr_count 并删 halt，
     * 于是紧随其后的 guard() 直接放行 —— 不需要在熔断分支里开洞。
     */
    (void) watch_install_tick(&c, up, pkgs, np);

    /*
     * guard 只做状态维护（boot_id 登记、熔断判定），本身不产生修复副作用，
     * 所以无论 auto_fix 开关都执行：诊断模式下也留下真实 boot 与熔断状态，
     * 切回自动修复时不会因为「从没登记过 boot_id」而误走 BOOT_INIT 分支。
     */
    if (guard(&c, why, sizeof(why))) {
        /* 已熔断：什么都不做，只留一条低频痕迹（每次 tick 都写会刷屏） */
        static long last_halt_log = 0;
        if (up - last_halt_log > 3600) {
            last_halt_log = up;
            rlog("TICK_HALTED %s", why);
        }
        return 0;
    }

    /*
     * 保命屏蔽的周期校验：与 auto_fix 无关。
     * 屏蔽是「别让系统把 SystemUI 判死」，不是修复动作 —— 关掉自动修复时
     * 也必须保证屏蔽在位，否则 UI 一崩就直接进系统自救流程。
     * 按 shield_check 节流（默认 1 小时），一次校验约 2 次 settings 读。
     */
    if (c.shield) {
        static long last_shield = 0;
        long iv = c.shield_check > 0 ? c.shield_check : 3600;
        if (last_shield == 0 || up - last_shield >= iv) {
            last_shield = up;
            if (shield_apply(&c) == 0) rlog("SHIELD_OK (周期校验)");
        }
    }

    /* 仅诊断模式：状态已维护，绝不动手。
     * 放在监听之后 —— 监听只记录事件、重置计数，不做任何修复动作，
     * auto_fix=0 时同样要维护 stamp 基线，否则开关一开基线是空的。 */
    if (!c.auto_fix) return 0;

    /* ---- 卸更新后的观察窗裁决（VERIFY）----
     * ① 卸更新成功后不再立即软重启，改由这里裁决它到底自愈了没有。
     * 必须排在 A 段之前：上一次修复的结果要先落定，否则 A 段会拿旧账再判
     * 一次，把「刚修好、进程正被重启」误当成新的崩溃循环。
     * 两条都满足才算自愈：进程活着 + 期间没有新的崩溃落盘。 */
    {
        long vts = st_get("verify_ts");
        if (vts > 0) {
            long age = now_up() - vts;
            if (age > (long)VERIFY_SEC * 20) {
                /* 中间可能被关过 auto_fix，窗口早已失去意义：作废不动作 */
                st_set("verify_ts", 0);
                rlog("VERIFY_EXPIRE age=%lds -> 作废(不重启)", age);
            } else if (age >= VERIFY_SEC) {
                int p = pid_of_pkg_fresh("com.android.systemui");
                /*
                 * 「有没有新崩溃」必须按【全部 crash 条目】判定，不能复用
                 * dropbox_window 的命中数：那个函数只统计归因命中的条目
                 * （attribute_text 返回 NONE 的直接跳过），堆栈里全是框架类
                 * （ZygoteInit/ActivityThread/Looper/Handler）时归因为 NONE，
                 * 会被过滤成 0 —— 于是「还在崩」被误判成「已自愈」，不重启，
                 * 用户继续黑屏。这里改用 drop_max()（目录内所有 crash 条目的
                 * 最大时间戳），只比大小、不做归因，判据与「是否还在崩」一致。
                 *
                 * 基线必须锚在【卸更新那一刻】。sr_drop_max 记录的就是当时
                 * 的最新崩溃时间戳；若当时一条崩溃都没有（=0），则用 vts 的
                 * uptime 反推 wall clock。绝不能简单用 now-VERIFY_SEC —— 那会
                 * 把 30s 窗口划到「现在」，反而漏掉刚卸完就崩的那几秒。
                 */
                long vwall  = now_ms_rt() - (now_up() - vts) * 1000L;
                long base   = st_get("sr_drop_max");
                long rb     = st_get("sr_rebase");
                long dmax   = drop_max();
                int  new_cr;
                if (base < vwall) base = vwall;   /* 不早于卸更新时刻 */
                /* 动作冷却窗：卸更新/软重启后的重拉余波不是「新崩」。
                 * 只抬高下限，不放宽判据。 */
                if (rb > base) base = rb;
                /* 只把「目标进程(systemui/plugin)」的崩溃算新崩；无关 app(如
                 * com.miui.guardprovider)的崩溃一律不计 —— 否则无关 app 一崩
                 * 就会被判成「卸更新没自愈」→ 误软重启兜底。
                 * 用 dropbox_window 的 proc_hit(按 "Process:" 归属)：既覆盖
                 * 「框架栈 attr=NONE 但确实目标进程崩了」，又排除无关 app。 */
                { int c2=0,a2=ATTR_NONE,p2=0; char ds2[300]="";
                  dropbox_window(base,&c2,&a2,ds2,sizeof(ds2),&p2);
                  new_cr = (p2>0) || (c2>0 && (a2==ATTR_SYSUI||a2==ATTR_PLUGIN||a2==ATTR_BOTH)); }
                st_set("verify_ts", 0);
                if (p > 0 && !new_cr) {
                    /*
                     * 自愈成立：进程活着且窗口内没有任何新崩溃落盘。
                     * sr_count 在卸更新时预记过一次（那是「真实修复尝试」），
                     * 但实际并没有发生软重启 —— 这里回退这一笔，让 sr_count
                     * 严格等于「本 boot 真实软重启次数」。否则连续两次装坏包
                     * 即使都自愈，也会把 sr_max 预算耗光直接熔断。
                     */
                    long sc = st_get("sr_count");
                    long stt = st_get("sr_total");
                    if (sc > 0) st_set("sr_count", sc - 1);
                    if (stt > 0) st_set("sr_total", stt - 1);
                    rlog("VERIFY_OK sysui pid=%d dmax=%ld base=%ld -> 免软重启(sr_count %ld->%ld)",
                         p, dmax, base, sc, st_get("sr_count"));
                    st_set("apid_sysui", p);
                    st_set("sr_ts", now_up());  /* 从此刻重新算稳定观察期 */
                } else {
                    rlog("VERIFY_FAIL sysui pid=%d new_crash=%d dmax=%ld base=%ld -> 软重启兜底",
                         p, new_cr, dmax, base);
                    a_hist_reset();
                    st_set("apid_sysui", 0);
                    reset_pid_baselines(&c);
                    rlog("SOFT_REBOOT cmd=[%s] sr_count=%ld (verify 兜底)",
                         c.reboot_cmd, st_get("sr_count"));
                    do_soft_reboot(&c);
                }
                return 0;
            }
        }
    }

    /*
     * A 段（DE 急救）：用户还没解锁时 SystemUI 崩成黑屏，锁屏画不出来，
     * 用户永远解不了锁，pm 也不可用 —— 这时唯一能做的是在根 FS 层把坏包
     * 拿掉再重启。必须在 B 段崩溃判定之前跑：B 段依赖 pid 变化，而 DE 阶段
     * SystemUI 可能从头到尾都没起来过（pid 恒为 0），pid 变化判据会漏。
     * a_phase 返回 1 表示已动作（软/硬重启已发起），本轮收工。
     */
    if (phase_de() && a_phase(&c, 0) != 0) return 0;

    /*
     * 崩溃判定：主判据是「目标包进程 pid 变化」。
     * 包崩了必被重启 → pid 必变；崩了没被拉起 → pid 由 N 变 0，同样算变化。
     * 这比 dropbox 精确得多：dropbox 的 system_app_crash 覆盖所有应用，
     * 拿它当触发条件会误删正常包。dropbox 在这里只做日志辅助，不参与决策。
     */
    /*
     * 崩溃计数改「窗口口径」：pid 变化只记账，不直接算崩。
     * 单次 pid 变化分不清「崩了」和「正在起来」—— 软重启后的空窗、zygote
     * 预热、内存回收后重启都会变。窗口内累计到 crash_threshold 次才判定为
     * 循环崩，偶发一次不动手。
     *
     * 两条独立信号取较大者：
     *   ① dropbox 崩溃正文归因命中的条数（一手证据，能区分组件/界面）
     *   ② pid 变化次数（不依赖 dropbox 落盘时机，dropbox 满盘时不失效）
     */
    {
        /* 一次 /proc 遍历解决全部 target 包（原来每个包各自全扫一遍，
         * 3 个 target 就是 3 遍、约 1000 次 read）。详见 pid_batch()。 */
        int pids[8];
        pid_batch(np, pkgs, pids);
        for (i = 0; i < np; i++) {
            char key[220];
            int cur = pids[i];
            long old;
            snprintf(key, sizeof(key), "spid_%s", pkgs[i]);
            old = st_get(key);
            if (old > 0 && (long)cur != old) {
                long _smt = st_get("self_restart_ts");
                if (_smt > 0 && labs((long)time(NULL) - _smt) <= 300) {
                    st_set("self_restart_ts", 0);
                    rlog("PID_CHANGE %s %ld -> %d skipped(self_restart)", pkgs[i], old, cur);
                } else {
                    rlog("PID_CHANGE %s %ld -> %d", pkgs[i], old, cur);
                    crash_hist_add(up, c.crash_window_sec);
                }
            }
            st_set(key, cur);      /* 无条件落盘（含 0）：同一事件只记一次 */
        }
    }

    {
        long since = now_ms_rt() - (long)c.crash_window_sec * 1000L;
        long base  = st_get("sr_drop_max");
        int db_cnt = 0, db_attr = ATTR_NONE, pid_cnt, db_proc = 0;
        /*
         * 修复/软重启【之前】就落盘的崩溃不算「新崩」。
         * 软重启只换掉 system_server，换不掉墙钟 —— 重启前那几条
         * system_app_crash/anr 仍落在 60s 窗口里，继续参与计数就会把
         * 刚生效的修复判成 OBS_FAIL；nofx_count 虚增两次即 HALT
         * fix_not_effective，明明修好了却整轮停摆。
         * sr_drop_max 是修复动作那一刻的 dropbox 水位（与 VERIFY 判据
         * 同源，见 reboot_after_fix / A_SOFT_REBOOT / UI_RESCUE），
         * 计数下限锚在它之后，两套口径才对得上。
         */
        if (base > 0 && base > since) since = base + 1;
        /* 冷却窗下限：软重启余波（zygote 重拉期间落的新崩溃）不是新崩。
         * 只抬高判据起点，不放宽阈值。放在 base+1 之后取 max，两者不冲突。 */
        {
            long rb = st_get("sr_rebase");
            if (rb > since) since = rb;
        }
        char dsrc[300] = "";

        dropbox_window(since, &db_cnt, &db_attr, dsrc, sizeof(dsrc), &db_proc);
        { static long last_hang = 0; if (up - last_hang >= 60) { last_hang = up; if (hang_probe()) { rlog("HANG_DETECTED log-flood/StackOverflow"); crash_hist_add(up, c.crash_window_sec); } } }
        pid_cnt = crash_hist_count(up, c.crash_window_sec);
        if (db_cnt > 0 || db_proc > 0 || pid_cnt > 0)
            rlog("CRASH_WIN db=%d proc=%d(%s %s) pid=%d win=%ds thr=%d",
                 db_cnt, db_proc,
                 db_attr == ATTR_PLUGIN ? "plugin" :
                 db_attr == ATTR_SYSUI  ? "sysui"  :
                 db_attr == ATTR_BOTH   ? "both"   : "none",
                 dsrc, pid_cnt, c.crash_window_sec, c.crash_threshold);
        /*
         * 三个信号任一达标即判崩溃循环：
         *   db_cnt  二级 —— 堆栈归因命中，能分清是谁崩的
         *   db_proc 一级 —— 进程名命中但堆栈只有框架类，分不清是谁，但
         *                   「界面进程又崩了一次」是事实
         *   pid_cnt 间接 —— 进程反复重启（dropbox 没落盘/满盘时的唯一证据）
         * 缺了 db_proc，Zygote 3 秒一轮的崩溃循环里堆栈全是框架类，
         * 二级恒为 0，只能靠 pid 兜底；而 pid 采样有间隔又分不清正常重启，
         * 结果就是用户一直黑屏、camd 一直不出手。
         */
        /* 取证门：真崩溃必落 dropbox；良性/外部重启(如 HyperCeiler)不落 dropbox。
         * 纯 pid 变化不足以触发，必须伴随至少一条 dropbox 崩溃证据。 */
        int drop_ok = (db_cnt >= c.crash_threshold || db_proc >= c.crash_threshold);
        int pid_ok  = (pid_cnt >= c.crash_threshold);
        if (drop_ok || (pid_ok && (db_cnt > 0 || db_proc > 0)))
            crash = 1;
    }

    /* dropbox：只记录，不触发 */
    dmax = drop_max();
    seen = st_get("seen_drop");
    if (seen == 0) {
        st_set("seen_drop", dmax);     /* 首次上线只记基线 */
    } else if (dmax > seen) {
        st_set("seen_drop", dmax);
        rlog("DROPBOX_NEW %ld (辅助信息)", dmax);
    }

    sr_ts = st_get("sr_ts");

    /* 观察期：软重启后 stable_sec 内不重复修，只看有没有好转 */
    if (sr_ts > 0) {
        long el = up - sr_ts;
        /* 有效性判据(F2)：只看「被修复的那个包是否还在崩」；不再看 /data/app
         * 覆盖层目录残留(find_ovl)，也不看兄弟包。卸更新回落后目录会残一会儿
         * (等重启清)，拿它判“无效”会把已回落、不再崩的修复误判成 OBS_FAIL。 */
        char obs[160]; if (st_str("obs_pkg", obs, sizeof(obs)) != 0) obs[0] = 0;
        int eff_fail = 0;
        if (obs[0]) {
            long s_ms = st_get("obs_ts");
            if (s_ms <= 0) s_ms = now_ms_rt() - (long)c.stable_sec * 1000L;
            int c2 = 0, a2 = ATTR_NONE, p2 = 0; char ds2[300] = "";
            dropbox_window(s_ms, &c2, &a2, ds2, sizeof(ds2), &p2);
            int attr_obs = (strstr(obs, "plugin") != NULL) ? ATTR_PLUGIN :
                           (strstr(obs, "systemui") != NULL) ? ATTR_SYSUI : ATTR_NONE;
            if (attr_obs != ATTR_NONE) {
                int own = (a2 == attr_obs) ? c2 : (a2 == ATTR_BOTH ? (c2 + p2) : 0);
                if (own >= c.crash_threshold) eff_fail = 1;
            }
        } else {
            eff_fail = crash;   /* 无 obs_pkg 记录时退回旧口径 */
        }
        if (el < c.stable_sec) {
            if (eff_fail) {
                long nf = st_get("nofx_count") + 1;
                st_set("nofx_count", nf);
                rlog("OBS_FAIL el=%ld obs=%s nofx=%ld (该包仍在崩)", el, obs[0]?obs:"?", nf);
                st_set("sr_ts", 0);
                if (nf >= c.nofx_max) {
                    st_wstr("halt", "fix_not_effective");
                    rlog("HALT fix_not_effective");
                }
            }
            return 0;
        }
        /* 观察期满：无该包新崩溃 => 判有效，清零计数 */
        if (!eff_fail) {
            st_set("sr_count", 0);
            st_set("sr_total", 0);
            st_set("nofx_count", 0);
            st_set("sr_ts", 0);
            st_set("sr_rebase", 0);
            st_del("obs_pkg");
            rlog("STABLE el=%ld obs=%s -> counters reset", el, obs[0]?obs:"?");
            return 0;
        }
        st_set("sr_ts", 0);
    }

    if (!crash) return 0;                 /* 窗口内没崩够次数就不动手 */

    /*
     * 修复决策：先归因，再决定动谁。
     *   能定性  -> 只动被认定的那个包（破坏面最小）
     *   判不出  -> 两个都动（ATTR_BOTH）：ROM 内置版仍在，回退覆盖层可逆，
     *              比把故障留给用户再熬一轮重启划算
     * 双修时先不重启：ui_rescue_chain 传 do_reboot=0，两个包都处理完
     * 再统一软重启一次，避免「修了第一个就重启、第二个没机会修」。
     */
    {
        int attr;
        char why[192] = "";
        /*
         * 归因必须限定在同一时间窗口内。否则会把几天前那条崩溃的归因
         * 套到这次崩溃上 —— 真机已复现：窗口内 0 条命中，却因为
         * culprit_attr(0) 不限时间，拿 2.9 天前的 system_app_anr 归成 sysui。
         * 方向一错就会去删错的包。
         */
        long since = now_ms_rt() - (long)c.crash_window_sec * 1000L;

        attr = culprit_attr(since, why, sizeof(why));
        rlog("TICK_ATTR %s why=%s",
             attr == ATTR_PLUGIN ? "plugin" :
             attr == ATTR_SYSUI  ? "sysui"  :
             attr == ATTR_BOTH   ? "both"   : "none", why);

        if (attr == ATTR_BOTH) {
            int acted = 0;
            for (i = 0; i < np; i++) {
                char d[640], note[800];
                if (!find_ovl(pkgs[i], d, sizeof(d))) continue;
                rlog("TICK_ACT both pkg=%s -> ui_rescue_chain(no-reboot)", pkgs[i]);
                if (ui_rescue_chain(&c, pkgs[i], note, sizeof(note), 0) == 0) {
                    rlog("TICK_FIX_OK pkg=%s %s", pkgs[i], note);
                    acted = 1;
                } else {
                    rlog("TICK_FIX_FAIL pkg=%s %s", pkgs[i], note);
                }
            }
            if (acted) { st_del("obs_pkg"); reboot_after_fix(&c); }
            return 0;
        }

        /* 定性明确：只修对应那个包，成功即收工（它自己会重启） */
        {
            int matched = 0;
            int any_cand = 0;
            for (i = 0; i < np; i++) {
                char d[640], note[800];
                int isp = (strstr(pkgs[i], "plugin") != NULL);

                if (attr == ATTR_PLUGIN && !isp) continue;
                if (attr == ATTR_SYSUI && isp) continue;
                any_cand = 1;   /* 配置里有这个类型的包（与有没有覆盖层无关） */
                if (!find_ovl(pkgs[i], d, sizeof(d))) continue;
                matched = 1;
                rlog("TICK_ACT pkg=%s attr=%s -> ui_rescue_chain", pkgs[i],
                     attr == ATTR_PLUGIN ? "plugin" : "sysui");
                if (ui_rescue_chain(&c, pkgs[i], note, sizeof(note), 1) == 0) {
                    st_wstr("obs_pkg", pkgs[i]);
                    return 0;
                }
                rlog("TICK_FIX_FAIL pkg=%s %s", pkgs[i], note);
            }
            /*
             * matched 只在 find_ovl 成功时才置 1，所以没匹配上有两种截然不同的
             * 原因，必须分开报，否则会把人带去查一个根本不存在的问题：
             *   any_cand=0 —— 配置 target 里确实没有这类包（配置问题）
             *   any_cand=1 —— 包在，但它当前没有 /data/app 覆盖层，没东西可删
             *                 （典型场景：① 卸更新刚成功，包已回落 ROM 内置版）
             */
            if (!matched)
                rlog(any_cand
                     ? "TICK_NO_OVL attr=%s (目标包无 /data/app 覆盖层，无可删物)"
                     : "TICK_NO_MATCH attr=%s (配置 target 里无对应包，本轮不动)",
                     attr == ATTR_PLUGIN ? "plugin" : "sysui");
        }
    }
    return 0;
}

/* ------------------------------------------------ APK 包名解析（AXML） */

/*
 * AndroidManifest.xml 在 APK 里是二进制 AXML，不是文本。这里只做一件事：
 * 找到 <manifest> 元素的 package 属性。不需要完整 XML 能力。
 *
 * 取 manifest 用设备自带的 /system/bin/unzip（toybox），而不是在 camd 里
 * 自解 zip：manifest 基本都是 deflate 压缩，自解要带 inflate，而 camd 是
 * 静态链接（proot 里没有 libz.a），unzip 现成且不引入新的失效面。
 *
 * 这个函数的唯一用途是「装包前确认 APK 真的是目标包」——装错包（比如把
 * 插件包装成 SystemUI 覆盖）是会二次搞坏系统的，所以解析失败一律返回
 * 失败，绝不猜。
 */

#define AXML_MAGIC       0x00080003u
#define AXML_CHUNK_POOL  0x0001
#define AXML_CHUNK_START 0x0102
#define AXML_NO_INDEX    0xFFFFFFFFu

static uint16_t ax_rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t ax_rd32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

typedef struct {
    const uint8_t *pool;    /* 字符串池 chunk 起始 */
    const uint8_t *data;    /* 字符串数据区起始 */
    uint32_t       count;   /* 字符串个数 */
    int            utf8;    /* 池编码：1=UTF-8，0=UTF-16 */
} axp_t;

/* 取字符串池第 idx 个串。超长（>=n）视为解析偏移出错，返回失败而非截断。 */
static int axp_str(const axp_t *p, uint32_t idx, char *out, size_t n)
{
    uint32_t off, len;
    const uint8_t *s;

    if (!out || n < 2) return -1;
    out[0] = 0;
    if (idx >= p->count) return -1;

    off = ax_rd32(p->pool + ax_rd16(p->pool + 2) + idx * 4);
    s = p->data + off;

    if (p->utf8) {
        /* UTF-8 池：先 1~2 字节「UTF-16 长度」，再 1~2 字节「UTF-8 字节数」 */
        uint32_t a = *s++, b;
        if (a & 0x80) a = ((a & 0x7F) << 8) | *s++;
        b = *s++;
        if (b & 0x80) b = ((b & 0x7F) << 8) | *s++;
        len = b;
        if (len >= n) return -1;
        memcpy(out, s, len);
        out[len] = 0;
        return 0;
    }

    /* UTF-16 池：包名与属性名都是 ASCII，只保留低字节 */
    {
        uint32_t c = ax_rd16(s); s += 2;
        size_t i, j = 0;
        if (c & 0x8000) { c = ((c & 0x7FFF) << 16) | ax_rd16(s); s += 2; }
        len = c;
        if (len >= n) return -1;
        for (i = 0; i < len; i++, s += 2) {
            uint16_t ch = ax_rd16(s);
            out[j++] = (ch < 0x80) ? (char)ch : '?';
        }
        out[j] = 0;
        return 0;
    }
}

/* 解析 AXML，输出 <manifest package="...">。0=成功，-1=失败 */
static int axml_package(const uint8_t *buf, size_t len, char *out, size_t n)
{
    axp_t pool;
    size_t off;
    const uint8_t *sp;

    if (!buf || len < 16 || ax_rd32(buf) != AXML_MAGIC) return -1;

    sp = buf + 8;
    if (ax_rd16(sp) != AXML_CHUNK_POOL) return -1;
    pool.pool  = sp;
    pool.count = ax_rd32(sp + 8);
    pool.utf8  = (ax_rd32(sp + 16) & 0x100) != 0;
    pool.data  = sp + ax_rd32(sp + 20);
    if (pool.count == 0 || pool.count > (1u << 20)) return -1;

    off = 8;
    while (off + 8 <= len) {
        uint16_t type = ax_rd16(buf + off);
        uint32_t sz   = ax_rd32(buf + off + 4);

        if (sz < 8 || sz > len - off) return -1;   /* chunk 越界 */

        if (type == AXML_CHUNK_START) {
            char tag[64];
            uint16_t astart, asize, acount, i;
            size_t abase;

            if (axp_str(&pool, ax_rd32(buf + off + 20), tag, sizeof(tag)) != 0)
                return -1;
            if (strcmp(tag, "manifest") != 0) { off += sz; continue; }

            /* ResXMLTree_attrExt: +16 ns, +20 name, +24 attrStart,
             * +26 attrSize, +28 attrCount；属性从 off+16+attrStart 开始，
             * 标准布局即 off+36。 */
            astart = ax_rd16(buf + off + 24);
            asize  = ax_rd16(buf + off + 26);
            acount = ax_rd16(buf + off + 28);
            if (asize < 20) asize = 20;
            abase  = off + 16 + astart;
            if (abase + (size_t)asize * acount > off + sz)
                abase = off + 36;

            for (i = 0; i < acount; i++) {
                const uint8_t *a = buf + abase + (size_t)i * asize;
                char an[32];
                uint32_t raw, val;

                if (abase + (size_t)(i + 1) * asize > len) break;
                if (axp_str(&pool, ax_rd32(a + 4), an, sizeof(an)) != 0) continue;
                if (strcmp(an, "package") != 0) continue;

                raw = ax_rd32(a + 8);
                val = (raw != AXML_NO_INDEX) ? raw : ax_rd32(a + 16);
                return axp_str(&pool, val, out, n);
            }
            return -1;    /* 有 manifest 元素但没有 package 属性 */
        }
        off += sz;
    }
    return -1;
}

/* 用 unzip 取出 APK 内的 AndroidManifest.xml，读进内存。0=成功 */
static int apk_manifest(const char *apk, uint8_t **out, size_t *outn)
{
    char tmp[640], f[768], cmd[2200];
    struct stat st;
    ssize_t got = 0;
    size_t off = 0;
    int fd;
    uint8_t *buf = NULL;

    *out = NULL; *outn = 0;
    if (!apk || !apk[0]) return -1;

    cam_path("tmp", tmp, sizeof(tmp));
    /* mkdir_p2 对「已存在」的目录返回 -1(EEXIST)，这里必须容忍：
     * 只有第一次调用目录是新建的，之后每次都在，若不容忍包名校验
     * 会从第二次起永久失效。 */
    {
        struct stat ds;
        if (stat(tmp, &ds) != 0) {
            if (mkdir_p2(tmp, 0700) != 0) return -1;
        } else if (!S_ISDIR(ds.st_mode)) {
            return -1;
        }
    }
    snprintf(f, sizeof(f), "%s/manifest.axml", tmp);

    /* 路径全部单引号包裹：APK 路径里常见 ~~xx== 这种字符。
     * unzip 不走 PATH：DE 阶段 PATH 可能被裁剪，且部分 ROM 的 toybox 未必
     * 编入 unzip applet。首选随 camd 落在 DE 目录的自带 busybox。 */
    {
        static const char *const ucand[] = {
            "/data/adb/camellia/busybox unzip -p '%s' AndroidManifest.xml > '%s' 2>/dev/null",
            "/system/bin/toybox unzip -p '%s' AndroidManifest.xml > '%s' 2>/dev/null",
            "/system/bin/unzip -p '%s' AndroidManifest.xml > '%s' 2>/dev/null",
            "/data/adb/ksu/bin/busybox unzip -p '%s' AndroidManifest.xml > '%s' 2>/dev/null",
            "/data/adb/magisk/busybox unzip -p '%s' AndroidManifest.xml > '%s' 2>/dev/null",
            NULL
        };
        int ui, got_it = 0;
        for (ui = 0; ucand[ui]; ui++) {
            unlink(f);          /* 先清掉上一轮残留（上次失败可能留下 0 字节文件） */
            snprintf(cmd, sizeof(cmd), ucand[ui], apk, f);
            if (sh_run(cmd) == 0 && stat(f, &st) == 0 && st.st_size >= 16) {
                got_it = 1;
                break;
            }
        }
        if (!got_it) { unlink(f); return -1; }
    }

    fd = open(f, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    if (fstat(fd, &st) != 0 || st.st_size < 16 || st.st_size > (4 << 20)) {
        close(fd); unlink(f); return -1;
    }
    buf = malloc((size_t)st.st_size);
    if (!buf) { close(fd); unlink(f); return -1; }

    while (off < (size_t)st.st_size) {
        got = read(fd, buf + off, (size_t)st.st_size - off);
        if (got <= 0) break;
        off += (size_t)got;
    }
    close(fd);
    unlink(f);                  /* 临时文件不留在盘上 */

    if (off != (size_t)st.st_size) { free(buf); return -1; }

    *out = buf;
    *outn = off;
    return 0;
}

/* 取 APK 包名。0=成功 */
static int apk_pkgname(const char *apk, char *out, size_t n)
{
    uint8_t *buf = NULL;
    size_t len = 0;
    int rc;

    if (apk_manifest(apk, &buf, &len) != 0) return -1;
    rc = axml_package(buf, len, out, n);
    free(buf);
    return rc;
}

static int cmd_pkg_of(int argc, char **argv)
{
    char pkg[192];

    if (argc < 3) { printf("用法: camd --pkg-of <apk>\n"); return 2; }
    if (apk_pkgname(argv[2], pkg, sizeof(pkg)) != 0) {
        printf("ERR 解析包名失败: %s\n", argv[2]);
        return 1;
    }
    printf("%s\n", pkg);
    return 0;
}

/* ---------------------------------------------------- B 段：装包 / 卸更新 */

/*
 * 这两件事原来走 Java：Java 进程要有 root → 发请求给守护进程 → 守护
 * 进程执行。三段任一环挂掉自救就哑了，而 B 段恰恰是「系统已经坏到界面
 * 起不来」时最后的招。下沉到 camd 后 camd 自己就是 root，直接 exec pm，
 * 不再有中间人。
 */

static int pkgname_ok(const char *p)
{
    size_t i;
    if (!p || !p[0] || strlen(p) > 200) return 0;
    for (i = 0; p[i]; i++) {
        char c = p[i];
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '.' || c == '_'))
            return 0;
    }
    return p[0] != '.';
}

static void pkg_one_line(char *s)
{
    for (; *s; s++)
        if (*s == '\n' || *s == '\r' || *s == '\t') *s = ' ';
}

/* 用 codePath/base.apk 的 mtime+size 拼一个「安装戳」。
 *
 * 为什么不用 packages.xml 的 versionCode/lastUpdateTime：Android 14 起
 * 的 ABX 版 packages.xml 里 <package> 只保留了 name/codePath 等少数属性，
 * 这两个字段压根不存在（strings 一看就明白），pkg_query 只能读出 0。
 * 拿 0 做「装后是否变化」的判据会让每次安装都被误判成失败。
 * 重装一定会重写 base.apk，mtime/size 必然变化，这个判据才是真的。 */
/* 由包名取 apk 指纹（codePath + base.apk mtime/size）。
 * 给安装事件监听用：pkg_query 走 ABX 解析，只在戳变化判定时调用，
 *
 * 不要直连 pkg_query()：watch_install_tick 每 tick 会对 pkgs[] 每个包调一次，
 * 而 packages.xml 本机 825KB，2 个包就是 1.65MB/tick ≈ 55KB/s —— 实测正是
 * camd 稳态读量的全部来源（/proc/camd/io 稳定 61557 B/s）。
 * 改走 pkg_query_tick()：它按 (dev,ino,mtime,size) 指纹复用解析结果，指纹
 * 一致即内容一致，codePath/versionCode 必然相同，缓存值与直读完全等价；
 * PMS 装/卸包以 atomic rename 重写该文件，指纹任一变化即失效重读。
 * apk 文件本身的 stat 照旧每次做，真装包仍能第一时间发现。 */
static long apk_stamp_pkg(const char *pkg)
{
    pkginfo_t info;
    memset(&info, 0, sizeof(info));
    /* pkg_query 返回 1=找到、0=没找到 —— 别写成 != 0，那会永远短路返回 0 */
    if (!pkg_query_tick(pkg, &info) || !info.found) return 0;
    return apk_stamp(info.code_path);
}

/* 当前 codePath 是否落在 /data/app —— 即「存在覆盖层/更新层」。
 * 用来区分两种 sig 变化：
 *   真装包   codePath=/data/app/~~x==/pkg-y==/base.apk  -> 1
 *   删包回落 codePath=/system_ext/priv-app/...          -> 0
 * 只看 sig 变化会把自己删完包的回落也当成装包，配额被无限归还、
 * 熔断形同虚设（日志 up=429 那条 INSTALL_EVENT 正是删包回落）。 */
static int code_path_is_overlay(const char *cp)
{
    size_t n;
    if (!cp || !cp[0]) return 0;
    n = strlen(APP_DIR);
    if (strncmp(cp, APP_DIR, n) != 0) return 0;
    /* 必须是 APP_DIR 的下一级：/data/app/...
     * 否则 /data/appfoo 这种前缀相似路径会被误判成有覆盖层。 */
    return cp[n] == '/';
}

static int pkg_has_overlay(const char *pkg)
{
    pkginfo_t info;
    memset(&info, 0, sizeof(info));
    if (!pkg_query(pkg, &info) || !info.found) return 0;
    return code_path_is_overlay(info.code_path);
}

/* 安装事件监听。返回 1 = 检测到「新装覆盖层」并已归还救援配额。
 * 必须跑在 guard() 之前：熔断只该挡住自动修复动作，不能把
 * 「用户装了新包」这个新信息一起挡掉 —— 否则配额用满停摆后只能重启手机，
 * 频繁装坏包测试的用户会被卡死在这个循环里。 */
static int watch_install_tick(cfg_t *c, long up, char pkgs[][160], int np)
{
    int i, credited = 0;

    (void) up;   /* up 原用于写 w_event_ts（已确认是死键、已删），保留参数兼容调用点 */

    if (!c->watch_install || phase_de()) return 0;

    if (st_get("w_armed") == 0) {
        st_set("w_armed", 1);
        for (i = 0; i < np; i++) {
            char k2[220];
            long st0 = apk_stamp_pkg(pkgs[i]);
            snprintf(k2, sizeof(k2), "wstamp_%s", pkgs[i]);
            rlog("WATCH_SEED pkg=%s stamp=%ld", pkgs[i], st0);
            if (st0 > 0) st_set(k2, st0);
        }
        rlog("WATCH_ARMED np=%d (CE 解锁)", np);
        return 0;
    }

    for (i = 0; i < np; i++) {
        char k2[220], hb[160];
        long prev, cur;

        snprintf(k2, sizeof(k2), "wstamp_%s", pkgs[i]);
        prev = st_get(k2);
        cur  = apk_stamp_pkg(pkgs[i]);
        if (prev > 0 && cur > 0 && cur != prev) {
            if (pkg_has_overlay(pkgs[i])) {
                /* 真装包：用户开始新一轮测试 -> 归还配额。
                 * sr_total 故意不回退：装包绕不过单 boot 绝对上限。 */
                rlog("INSTALL_EVENT pkg=%s prev=%ld cur=%ld -> 新装覆盖层, 配额重置",
                     pkgs[i], prev, cur);
                st_set("sr_count", 0);
                st_set("nofx_count", 0);
                st_set("sr_drop_max", 0);
                st_set("sr_ts", 0);
                st_set("sr_rebase", 0);   /* 新装包=新起点，冷却窗一并归零 */
                /* 新装包=新起点：上一轮卸更新的观察窗与 pid 基线全部作废。
                 * 否则 30s 内连续装包时，VERIFY 会用旧 verify_ts 反推 base，
                 * 把新包自己的崩溃判成「上一轮修复失败」→ 无罪软重启 + nofx+1。 */
                st_set("verify_ts", 0);
                st_set("apid_sysui", 0);
                reset_pid_baselines(c);
                pid_cache_flush();   /* 新包可能立刻起进程，别等负 TTL */
                if (st_str("halt", hb, sizeof(hb))) {
                    st_del("halt");
                    rlog("UNHALT by-install (was: %s)", hb);
                }
                credited = 1;
            } else {
                /* 删包回落（camd 自己刚删完覆盖层）：配额一分不动 */
                rlog("INSTALL_FALLBACK pkg=%s prev=%ld cur=%ld (删包回落, 配额不动)",
                     pkgs[i], prev, cur);
            }
            crash_hist_reset();      /* 包状态变了，旧崩账不能算到新包头上 */
        }
        if (cur > 0) st_set(k2, cur);
    }
    return credited;
}

static long apk_stamp(const char *code_path)
{
    char p[640];
    struct stat st;
    size_t n;

    if (!code_path || !code_path[0]) return 0;
    n = strlen(code_path);
    if (n > 4 && !strcmp(code_path + n - 4, ".apk")) {
        /* 形态一：codePath 直接指向 apk 文件 */
        snprintf(p, sizeof(p), "%s", code_path);
    } else {
        /* 形态二：codePath 是目录。系统应用目录下的 apk 名字不固定
         * （/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk，
         *  /data/app/~~x==/pkg-y==/base.apk），所以扫第一个 .apk。 */
        DIR *dd = opendir(code_path);
        struct dirent *de2;

        p[0] = 0;
        if (dd) {
            while ((de2 = readdir(dd)) != NULL) {
                size_t m = strlen(de2->d_name);
                if (m > 4 && !strcmp(de2->d_name + m - 4, ".apk")) {
                    snprintf(p, sizeof(p), "%s/%s", code_path, de2->d_name);
                    break;
                }
            }
            closedir(dd);
        }
        if (!p[0]) snprintf(p, sizeof(p), "%s/base.apk", code_path);
    }
    if (stat(p, &st) != 0) return 0;
    return (long)(st.st_mtime ^ ((long)st.st_size << 1));
}

static int argv_has_flag(int argc, char **argv, const char *flag)
{
    int i;
    for (i = 2; i < argc; i++)
        if (!strcmp(argv[i], flag)) return 1;
    return 0;
}

/* 把 APK 暂存到 system_server 读得到的位置。
 *
 * pm install 并不是在 camd 里解包的：它把路径交给 system_server，由
 * system_server 自己去读那个文件。而 system_server 的 SELinux 域读不到
 * /sdcard（fuse），直接装 /sdcard 上的包只会得到：
 *   avc: denied { read } scontext=u:r:system_server:s0 tcontext=u:object_r:fuse:s0
 * 这才是「装包莫名失败」的真正原因，和有没有 root 无关 —— 实测从
 * /data/local/tmp 装同一个包就能正常走到安装流程。/data/local/tmp 属
 * shell_data_file，system_server 可读。
 */
static int apk_stage(const char *apk, char *out, size_t n)
{
    char cmd[2600];
    struct stat s1, s2;

    out[0] = 0;
    if (stat(apk, &s1) != 0) return -1;

    /* 源已在可读目录就直接用，不必白白拷一份 */
    if (!strncmp(apk, "/data/local/tmp/", 16)) {
        snprintf(out, n, "%s", apk);
        return 0;
    }

    snprintf(out, n, "/data/local/tmp/camd_stage_%d.apk", (int)getpid());
    snprintf(cmd, sizeof(cmd), "cp -f '%s' '%s' && chmod 644 '%s'", apk, out, out);
    if (sh_run(cmd) != 0) { out[0] = 0; return -1; }

    /* 大小必须一致：拷一半的 APK 装下去会产生比失败更糟的半状态 */
    if (stat(out, &s2) != 0 || s2.st_size != s1.st_size) {
        unlink(out);
        out[0] = 0;
        return -1;
    }
    return 0;
}

/* 装 APK。expect 非空则要求 APK 包名与之完全一致。0=成功
 *
 * 包名预检是这里唯一能拦住「装错包二次搞坏系统」的闸门：用户放的 APK
 * 可能根本不是他想装的那个（下错文件、名字像、旧版本包名不同），装进
 * 系统分区级组件的位置会直接把系统再搞坏一次。宁可拒绝，不猜。 */
static int pm_install(const char *apk, const char *expect, int dry,
                      char *res, size_t resn)
{
    char pkg[192] = {0}, cmd[2600], out[1024], staged[256];
    pkginfo_t before, after;
    int had;
    long b_stamp;

    res[0] = 0;
    if (!apk || !apk[0]) { snprintf(res, resn, "未指定 APK"); return -1; }

    if (apk_pkgname(apk, pkg, sizeof(pkg)) != 0) {
        snprintf(res, resn, "包名解析失败，拒绝安装");
        return -1;
    }
    if (!pkgname_ok(pkg)) {
        snprintf(res, resn, "包名非法: %s", pkg);
        return -1;
    }
    if (expect && expect[0] && strcmp(pkg, expect) != 0) {
        snprintf(res, resn, "包名不符 APK=%s 期望=%s", pkg, expect);
        return -1;
    }

    had = pkg_query(pkg, &before);
    b_stamp = had ? apk_stamp(before.code_path) : 0;

    if (dry) {
        snprintf(res, resn, "dry 将安装 %s (%s)%s", pkg, apk,
                 had ? " 覆盖现有" : " 全新");
        return 0;
    }

    /* 先暂存到 system_server 读得到的位置，否则必然 SELinux 拒绝 */
    if (apk_stage(apk, staged, sizeof(staged)) != 0) {
        snprintf(res, resn, "暂存失败（/data/local/tmp 空间不足？）");
        return -1;
    }

    /* -r 覆盖；-d 允许降级 —— 回退版本本来就比当前低 */
    snprintf(cmd, sizeof(cmd), "pm install -r -d '%s'", staged);
    out[0] = 0;
    sh_capture(cmd, out, sizeof(out));
    pkg_one_line(out);

    if (strcmp(staged, apk) != 0) unlink(staged);

    if (!strstr(out, "Success")) {
        snprintf(res, resn, "安装失败: %.150s", out);
        return -1;
    }

    /* 装后校验以系统实际状态为准，不信 pm 的自述。 */
    if (!pkg_query(pkg, &after)) {
        snprintf(res, resn, "装后查不到 %s", pkg);
        return -1;
    }
    if (had && !strcmp(after.code_path, before.code_path)) {
        long as = apk_stamp(after.code_path);
        if (as != 0 && as == b_stamp) {
            snprintf(res, resn, "装后未变（与已装版本完全相同？）");
            return -1;
        }
    }

    snprintf(res, resn, "已安装 %s -> %s", pkg, after.code_path);
    return 0;
}

/* 手动快照重装的结果写进 a_last_* 通道。
 *
 * 为什么不新加字段：UI 的「上次急救」卡片已经在渲染 a_last_action/pkg/ts，
 * 复用它可以零 UI 改造就把失败原因送到用户眼前。camd 内 a_last_* 只写不读
 * （5878 处仅转写到状态文本），不存在污染自动归因的问题。
 *
 * pm 的输出带换行，而 a_last_why 会被拼进 key=value 单行状态文本，
 * 必须先压成一行，否则整份 status 解析会错位。
 */
static void snap_restore_note(const char *snap, const char *msg)
{
    char one[120];
    size_t i, j = 0;

    if (!msg) msg = "";
    for (i = 0; msg[i] && j + 1 < sizeof(one); i++) {
        char ch = msg[i];
        one[j++] = (ch == '\n' || ch == '\r' || ch == '\t') ? ' ' : ch;
    }
    while (j > 0 && one[j - 1] == ' ') j--;
    one[j] = 0;
    if (!one[0]) snprintf(one, sizeof(one), "完成");

    st_wstr("a_last_action", "snap_restore");
    st_wstr("a_last_pkg", snap);
    st_wstr("a_last_why", one);
    st_set("a_last_ts", (long) time(NULL));
}

/* 卸掉系统更新，退回基线版本。0=成功 */
static int pm_uninstall_update(const char *pkg, int dry, char *res, size_t resn)
{
    char cmd[600], out[768];
    pkginfo_t pi;

    res[0] = 0;
    if (!pkgname_ok(pkg)) {
        snprintf(res, resn, "包名非法: %s", (pkg && pkg[0]) ? pkg : "(空)");
        return -1;
    }

    if (dry) { snprintf(res, resn, "dry 将卸更新 %s", pkg); return 0; }

    snprintf(cmd, sizeof(cmd), "pm uninstall-system-updates %s", pkg);
    out[0] = 0;
    sh_capture(cmd, out, sizeof(out));
    pkg_one_line(out);

    if (!strstr(out, "Success")) {
        /* pm 对「普通应用 / 不存在的包」的失败输出是一坨 java.lang.NullPointerException
         * 堆栈（它在 ApplicationInfo 上解引用空指针），原样传出去会让人以为
         * camd 自己炸了。真实语义就一句：这包没有可卸的系统更新。 */
        if (strstr(out, "NullPointerException") || strstr(out, "Unknown package"))
            snprintf(res, resn, "%s 不是系统更新应用（无可卸更新）", pkg);
        else
            snprintf(res, resn, "卸更新失败: %.120s", out);
        return -1;
    }

    /* 卸更新只应回退版本，包本身必须还在；包没了说明命令行为不符预期，
     * 这比「卸不掉」更糟，必须报出来。 */
    if (!pkg_query(pkg, &pi)) {
        snprintf(res, resn, "卸更新后包消失!: %s", pkg);
        return -1;
    }

    snprintf(res, resn, "已卸更新 %s -> %s", pkg, pi.code_path);
    return 0;
}

static int cmd_install_apk(int argc, char **argv)
{
    char res[512];
    const char *apk, *expect = NULL;
    int dry;

    if (argc < 3) {
        printf("用法: camd --install-apk <apk> [期望包名] [--dry]\n");
        return 2;
    }
    apk = argv[2];
    if (argc >= 4 && strncmp(argv[3], "--", 2) != 0) expect = argv[3];
    dry = argv_has_flag(argc, argv, "--dry");

    if (pm_install(apk, expect, dry, res, sizeof(res)) != 0) {
        printf("ERR %s\n", res);
        return 1;
    }
    printf("OK %s\n", res);
    return 0;
}

static int cmd_uninstall_update(int argc, char **argv)
{
    char res[512];
    int dry;

    if (argc < 3) {
        printf("用法: camd --uninstall-update <包名> [--dry]\n");
        return 2;
    }
    dry = argv_has_flag(argc, argv, "--dry");

    if (pm_uninstall_update(argv[2], dry, res, sizeof(res)) != 0) {
        printf("ERR %s\n", res);
        return 1;
    }
    printf("OK %s\n", res);
    return 0;
}

/* --------------------------------------------------------------- CLI 输出 */

static int cmd_status(void)
{
    cfg_t c;
    char why[192], bid[64], seen[64];
    int i, np;
    char pkgs[8][160];
    long dmax;

    cfg_load(&c);
    boot_id(bid, sizeof(bid));
    printf("===== camd rescue status =====\n");
    { char cp[320]; cam_path("config", cp, sizeof(cp)); printf("[config] %s\n", cp); }
    printf("  auto_fix   = %d %s\n", c.auto_fix, c.auto_fix ? "(自动修复已开启)" : "(仅诊断)");
    printf("  tick_sec   = %d\n", c.tick_sec);
    printf("  target     = %s\n", c.target);
    printf("  bak_dir    = %s (keep=%d)\n", c.bak_dir, c.bak_keep);
    printf("  sr_max     = %d (硬顶 %d)\n", c.sr_max, HARD_SR_MAX);
    printf("  nofx_max   = %d\n", c.nofx_max);
    printf("  stable_sec = %ld\n", c.stable_sec);
    /* 两段账本分开显示：A 段判「开机界面起不来」，B 段判「解锁后装包崩」。
     * 窗口/thr 都解耦，账本各自独立，这里一并列出便于现场核对。 */
    printf("  A段窗口    = %ds thr=%d (账本=%d条)\n",
           c.a_window_sec, c.a_circ_thr,
           a_hist_count(now_up(), c.a_window_sec));
    printf("  B段窗口    = %ds thr=%d (账本=%d条)\n",
           c.crash_window_sec, c.crash_threshold,
           crash_hist_count(now_up(), c.crash_window_sec));
    printf("  reboot_cmd = %s\n", c.reboot_cmd);
    {
        char raw[512];
        int present = 0;
        long lim = -1;
        if (shield_read(raw, sizeof(raw), &lim, &present) != 0)
            printf("  shield     = %d (读取失败，settings 不可用)\n", c.shield);
        else {
            printf("  shield     = %d %s (check=%ds limit=%d)\n",
                   c.shield, c.shield ? "(屏蔽系统自救已开)" : "(未屏蔽，系统可自救)",
                   c.shield_check, c.shield_limit);
            printf("  [am] crash_limit=%ld %s\n", lim,
                   lim == c.shield_limit ? "(已生效)"
                                         : (present ? "(未生效)" : "(原本未设置·按默认12)"));
            if (raw[0]) printf("  [am] constants=%s\n", raw);
        }
        printf("  [state] shield_ok=%ld\n", st_get("shield_ok"));
    }

    printf("[boot] id=%s up=%lds\n", bid, now_up());
    if (st_str("halt", why, sizeof(why)))
        printf("[circuit] ⛔ HALTED: %s\n", why);
    else if (st_str("boot_id", seen, sizeof(seen)) && strcmp(seen, bid) != 0)
        printf("[circuit] ⚠️  boot_id 不一致（下次 tick 将重置计数）\n");
    else
        printf("[circuit] ✅ 正常\n");
    printf("  sr_count   = %ld (两次装包之间的配额)\n", st_get("sr_count"));
    printf("  sr_total   = %ld / %d (单 boot 绝对上限, 装包不清)\n",
           st_get("sr_total"), BOOT_SR_TOTAL_MAX);
    printf("  nofx_count = %ld\n", st_get("nofx_count"));
    printf("  sr_ts      = %ld\n", st_get("sr_ts"));

    dmax = drop_max();
    printf("[dropbox] latest=%ld seen=%ld (仅辅助记录，不触发修复)\n",
           dmax, st_get("seen_drop"));

    np = split_target(&c, pkgs, 8);
    printf("[targets] (pid 变化 = 该包崩溃重启)\n");
    for (i = 0; i < np; i++) {
        char d[640], key[220];
        long kb, old;
        int cur;
        snprintf(key, sizeof(key), "spid_%s", pkgs[i]);
        cur = pid_of_pkg_fresh(pkgs[i]);
        old = st_get(key);
        if (find_ovl(pkgs[i], d, sizeof(d))) {
            kb = dir_kb(d);
            printf("  %-32s pid=%-6d(prev=%ld) 有覆盖 %s (%ld KB)\n",
                   pkgs[i], cur, old, d, kb);
        } else {
            printf("  %-32s pid=%-6d(prev=%ld) 无覆盖（系统分区版本）\n",
                   pkgs[i], cur, old);
        }
    }
    return 0;
}

/*
 * camd --self-test
 *
 * 只验证判定逻辑本身：窗口计数、过期丢弃、重启防护、.gz 归因。
 * 全程不触发任何修复动作（不删包 / 不装包 / 不重启）。
 * 会临时改写 state/crash_hist，测完恢复原内容。
 */
static int cmd_self_test(void)
{
    char path[340], bak[360], out[2048];
    char st_tmp[220];
    cfg_t c;
    FILE *f;
    long up = now_up();
    int i, ok = 1, n, saved = 0;
    size_t olen = 0;

    cfg_load(&c);
    /* 自测造的现场一律放独立临时目录，不要落在 /data/adb/camellia/state 下：
     * camd 与面板都会扫 state，残留一份 selftest_bak 会被当成真实快照，
     * 用户看到「有一份刚存下的坏包备份」却根本不是真事故。
     * /data/local/tmp 是 root 可写、重启自动清空的 tmpfs，最安全。 */
    snprintf(st_tmp, sizeof(st_tmp), "/data/local/tmp/camd_selftest.%d",
             (int) getpid());
    {
        char t[320];
        snprintf(t, sizeof(t), "rm -rf '%s'; mkdir -p '%s'", st_tmp, st_tmp);
        sh_run(t);
    }
    cam_path("state/crash_hist", path, sizeof(path));
    snprintf(bak, sizeof(bak), "%s.selftest", path);

    f = fopen(path, "r");
    if (f) {
        olen = fread(out, 1, sizeof(out) - 1, f);
        out[olen] = 0;
        fclose(f);
        saved = 1;
    }

    printf("===== camd self-test（只算不动作）=====\n");
    printf("win=%ds thr=%d up=%ld\n", c.crash_window_sec, c.crash_threshold, up);

    /* T1: 窗口内 3 条 + 窗口外 2 条，只应认窗口内 */
    f = fopen(path, "w");
    if (f) {
        for (i = 0; i < 3; i++) fprintf(f, "%ld\n", up - i);
        fprintf(f, "%ld\n%ld\n",
                up - (long)c.crash_window_sec - 60,
                up - (long)c.crash_window_sec - 120);
        fclose(f);
    }
    n = crash_hist_count(up, c.crash_window_sec);
    printf("  T1 3条窗口内+2条过期 → count=%d（应3）%s\n", n, n == 3 ? "PASS" : "FAIL");
    if (n != 3) ok = 0;

    /* T2: 追加一条，过期条目应被挤出文件 */
    n = crash_hist_add(up, c.crash_window_sec);
    printf("  T2 add 后 → count=%d（应4，说明过期已挤掉）%s\n",
           n, n == 4 ? "PASS" : "FAIL");
    if (n != 4) ok = 0;

    /* T3: 硬重启后 uptime 归零，旧记录的时间戳会「在未来」，必须丢弃 */
    f = fopen(path, "w");
    if (f) {
        fprintf(f, "%ld\n%ld\n%ld\n", up + 5000, up + 5001, up);
        fclose(f);
    }
    n = crash_hist_count(up, c.crash_window_sec);
    printf("  T3 含未来时间戳(模拟重启) → count=%d（应1）%s\n",
           n, n == 1 ? "PASS" : "FAIL");
    if (n != 1) ok = 0;

    /* T4: dropbox 窗口 + .gz 可读性（这条最能证明归因有眼睛） */
    {
        int cnt = 0, a = ATTR_NONE;
        char s[300] = "";
        dropbox_window(now_ms_rt() - (long)c.crash_window_sec * 1000L,
                       &cnt, &a, s, sizeof(s), NULL);
        printf("  T4a dropbox %ds 窗口内 %d 条%s%s\n", c.crash_window_sec, cnt,
               s[0] ? " 最新=" : "", s);
        cnt = 0; a = ATTR_NONE; s[0] = 0;
        dropbox_window(0, &cnt, &a, s, sizeof(s), NULL);
        printf("  T4b 不限时间 %d 条%s%s\n", cnt, s[0] ? " 最新=" : "", s);
    }

    /* T5: 归因两档，确认不会返回 NONE（判不出要给 both 才能双修） */
    {
        char why[192] = "";
        int a = culprit_attr(now_ms_rt() - (long)c.crash_window_sec * 1000L,
                             why, sizeof(why));
        printf("  T5a 归因(窗口内)=%s (%s)\n",
               a == ATTR_PLUGIN ? "plugin" : a == ATTR_SYSUI ? "sysui" :
               a == ATTR_BOTH ? "both" : "none", why);
        if (a == ATTR_NONE) ok = 0;
        why[0] = 0;
        a = culprit_attr(0, why, sizeof(why));
        printf("  T5b 归因(不限时间)=%s (%s)\n",
               a == ATTR_PLUGIN ? "plugin" : a == ATTR_SYSUI ? "sysui" :
               a == ATTR_BOTH ? "both" : "none", why);
    }

    /* T6: A/B 账本必须物理隔离。
     * 共用一份账会出两个方向的错：A 段 180s 长窗口把 B 段刚清零的计数又
     * 算回来；B 段的 watch_install 重置误清 A 段在开机期攒的崩账。
     * 验证法：只往 B 段记账，A 段计数必须纹丝不动。 */
    {
        int aa0 = a_hist_count(up, c.a_window_sec);
        crash_hist_add(up, c.crash_window_sec);   /* 只写 B 段 */
        {
            int aa1 = a_hist_count(up, c.a_window_sec);
            printf("  T6 只写B段后 A段计数 %d→%d（应不变）%s\n",
                   aa0, aa1, aa0 == aa1 ? "PASS" : "FAIL");
            if (aa0 != aa1) ok = 0;
        }
    }

    /* T7: A 段窗口参数已从配置读出（与 B 段解耦，默认 180s/3 次） */
    printf("  T7 A段 win=%ds thr=%d %s\n", c.a_window_sec, c.a_circ_thr,
           (c.a_window_sec >= 60 && c.a_window_sec <= 1800 &&
            c.a_circ_thr >= 2 && c.a_circ_thr <= 20) ? "PASS" : "FAIL");
    if (!(c.a_window_sec >= 60 && c.a_window_sec <= 1800 &&
          c.a_circ_thr >= 2 && c.a_circ_thr <= 20)) ok = 0;

    /* T8: VERIFY 自愈判据（卸更新成功后该不该免软重启）
     *
     * 判据必须是「只比 crash 时间戳、不做归因」：
     *   有新条目落盘 → 没自愈 → 软重启兜底
     *   没有任何新条目 → 自愈 → 免重启
     * 若改用 dropbox_window 的命中数，堆栈只有框架类时归因 NONE 被跳过，
     * 「还在崩」会被误判成「已自愈」—— 这里把两种口径的差值打出来作证据。 */
    {
        long dmax = drop_max();
        long base_same = dmax, base_old = dmax - 1;
        int cr_same = (dmax > base_same);   /* 基线=当前最新 → 应 0 */
        int cr_old  = (dmax > base_old);    /* 基线早 1ms   → 应 1 */
        int all_cnt = drop_entry_count();
        int attr_cnt = 0;
        dropbox_window(0, &attr_cnt, NULL, NULL, 0, NULL);

        printf("  T8a 基线=dmax(%ld) → new_crash=%d（应0）%s\n",
               dmax, cr_same, cr_same == 0 ? "PASS" : "FAIL");
        printf("  T8b 基线=dmax-1 → new_crash=%d（应1）%s\n",
               cr_old, cr_old == 1 ? "PASS" : "FAIL");
        printf("  T8c 全部crash=%d 归因命中=%d（采样上限24）→ 口径差 %d\n",
               all_cnt, attr_cnt, all_cnt - attr_cnt);
        if (cr_same != 0 || cr_old != 1) ok = 0;
        if (all_cnt > attr_cnt)
            printf("       ↑ 有 %d 条未归因，若 VERIFY 用命中数会漏判「还在崩」\n",
                   all_cnt - attr_cnt);
    }

    /* T9: 崩溃计数两级口径（一级 Process 行归属 / 二级堆栈归因）
     *
     * 目的：证明「堆栈只有框架类、进程名却写着界面进程」的崩溃不会被当成
     * 无关条目扔掉。先用合成正文直接测 process_owner（纯函数，不碰设备上的
     * dropbox），再用真实 dropbox 验证一级 >= 二级。 */
    {
        static const struct {
            const char *body;
            int want;
            const char *name;
        } tc[] = {
            { "Process: system_server\njava.lang.RuntimeException: boom\n",
              ATTR_NONE,   "system_server 排除" },
            { "Process: com.android.systemui\n"
              "java.lang.RuntimeException\n"
              "\tat android.os.Handler.dispatchMessage(Handler.java:106)\n",
              ATTR_BOTH,   "界面进程(两包共用名)" },
            { "Process: miui.systemui.plugin\n"
              "java.lang.NullPointerException\n",
              ATTR_PLUGIN, "组件独立进程" },
            { "Process: com.tencent.mm\njava.lang.RuntimeException\n",
              ATTR_NONE,   "别的应用" },
            { "*** FATAL EXCEPTION IN SYSTEM PROCESS\n"
              "\tat com.android.internal.os.ZygoteInit.main(ZygoteInit.java:1000)\n",
              ATTR_NONE,   "无 Process 行" },
        };
        int k, bad = 0;
        int attr_cnt = 0, proc_cnt = 0;

        for (k = 0; k < (int)(sizeof(tc) / sizeof(tc[0])); k++) {
            int g = process_owner(tc[k].body, strlen(tc[k].body));
            int pass = (g == tc[k].want);
            printf("  T9.%d %s → %s（应 %s）%s\n",
                   k + 1, tc[k].name,
                   g == ATTR_PLUGIN ? "plugin" : g == ATTR_SYSUI ? "sysui" :
                   g == ATTR_BOTH   ? "both"   : "none",
                   tc[k].want == ATTR_PLUGIN ? "plugin" :
                   tc[k].want == ATTR_SYSUI  ? "sysui" :
                   tc[k].want == ATTR_BOTH   ? "both"   : "none",
                   pass ? "PASS" : "FAIL");
            if (!pass) { bad++; ok = 0; }
        }
        dropbox_window(0, &attr_cnt, NULL, NULL, 0, &proc_cnt);
        printf("  T9d 一级(Process命中)=%d 二级(堆栈归因)=%d → 一级>=二级 %s\n",
               proc_cnt, attr_cnt, proc_cnt >= attr_cnt ? "PASS" : "FAIL");
        if (proc_cnt < attr_cnt) ok = 0;
        if (proc_cnt > attr_cnt)
            printf("       ↑ 一级多出 %d 条：在崩但堆栈无特征，旧实现直接丢弃\n",
                   proc_cnt - attr_cnt);
        (void)bad;
    }

    /* T10: 归因判据修正（so 同名不同主 / 覆盖层路径硬证据）
     *
     * 目的：把 2026-11-03 实弹踩到的坑钉死，防止以后有人"顺手"把库名判据
     * 加回来。两个坑：
     *   ① libMiMainColor.so / libmglnative.so 这类库，SystemUI 也从
     *      /system_ext/lib64 加载 —— 同名不同主，只凭库名判 plugin 必错。
     *      现在只认「库出现在组件覆盖层目录里」。
     *   ② 覆盖层目录名带包名+随机后缀（.../com.android.systemui-XXXX==/），
     *      这是最硬的归属证据，比「正文里出现 com.android.systemui 字样」
     *      强得多（plugin 自己就编入了 627 个 com/android/systemui 类）。 */
    {
        static const struct {
            const char *body;
            int want;
            const char *name;
        } tc10[] = {
            { "Process: com.android.systemui\n"
              "java.lang.NoSuchMethodError: No virtual method shade()\n"
              "\tat com.android.systemui.shade.ShadeControllerImpl.<init>(Unknown)\n"
              "Caused by: /data/app/~~ab==/com.android.systemui-cd==/base.apk\n",
              ATTR_SYSUI,  "sysui 覆盖层路径(硬证据)" },
            { "Process: com.android.systemui\n"
              "java.lang.NoClassDefFoundError: miui.systemui.plugin.core.PluginCore\n"
              "\tat miui.systemui.plugin.core.PluginCore.<clinit>(Unknown)\n"
              "Caused by: /data/app/~~ef==/miui.systemui.plugin-gh==/base.apk\n",
              ATTR_PLUGIN, "plugin 覆盖层路径(硬证据)" },
            { "Process: com.android.systemui\n"
              "*** *** *** *** *** *** *** *** *** ***\n"
              "---> Native crash\n"
              "#00 pc 0000000000123456  /system_ext/lib64/libMiMainColor.so\n",
              ATTR_SYSUI,  "只凭库名→不得判plugin" },
            { "Process: com.android.systemui\n"
              "java.lang.RuntimeException: boom\n"
              "\tat miui.systemui.controlcenter.policy.Foo.run(Foo.java:12)\n",
              ATTR_PLUGIN, "plugin 类名特征(保留)" },
            { "Process: com.android.systemui\n"
              "java.lang.RuntimeException: boom\n"
              "\tat com.android.systemui.keyguard.KeyguardViewMediator.onStarted(Unknown)\n",
              ATTR_SYSUI,  "sysui 兜底" },
        };
        int k10;
        for (k10 = 0; k10 < (int)(sizeof(tc10) / sizeof(tc10[0])); k10++) {
            int g = attribute_text(tc10[k10].body, strlen(tc10[k10].body));
            int pass = (g == tc10[k10].want);
            printf("  T10.%d %s → %s（应 %s）%s\n",
                   k10 + 1, tc10[k10].name,
                   g == ATTR_PLUGIN ? "plugin" : g == ATTR_SYSUI ? "sysui" :
                   g == ATTR_BOTH   ? "both"   : "none",
                   tc10[k10].want == ATTR_PLUGIN ? "plugin" :
                   tc10[k10].want == ATTR_SYSUI  ? "sysui" :
                   tc10[k10].want == ATTR_BOTH   ? "both"   : "none",
                   pass ? "PASS" : "FAIL");
            if (!pass) ok = 0;
        }
    }

    /* T11: 可行动门禁 —— 现场真实包状态（只观测不断言，因为装的包随时会变）
     *
     * 这条的意义：把「归因说删谁」和「谁真能删」摆在一起给人看。两列不一致
     * 的场景就是过去空转的现场（归因 plugin / 可行动只有 sysui）。 */
    {
        pkginfo_t pi11;
        int hs11 = pkg_query("com.android.systemui", &pi11);
        int sysui_ovl11 = hs11 && strncmp(pi11.code_path, APP_DIR, strlen(APP_DIR)) == 0;
        int hp11 = pkg_query("miui.systemui.plugin", &pi11);
        int plugin_ovl11 = hp11 && strncmp(pi11.code_path, APP_DIR, strlen(APP_DIR)) == 0;

        printf("  T11 sysui=%s plugin=%s → 可行动 sysui=%d plugin=%d\n",
               hs11 ? (sysui_ovl11 ? "有覆盖层" : "ROM版") : "未装",
               hp11 ? (plugin_ovl11 ? "有覆盖层" : "ROM版") : "未装",
               attr_actionable(ATTR_SYSUI), attr_actionable(ATTR_PLUGIN));
    }

    /* T12: 「真装包 vs 删包回落」的分水岭 —— code_path_is_overlay
     *
     * INSTALL_EVENT 只看 sig 变化，用户装包和 camd 自己删完包的回落都会命中
     * （实弹日志 up=429 那条 prev=1826272878->cur=1332707988 就是删包回落）。
     * 两类事件必须区别对待：
     *   真装包   -> 归还 sr_count 配额、解除熔断（新一轮测试开始）
     *   删包回落 -> 配额一分不动（否则熔断被自己的救援动作无限绕过）
     * 所以判据必须锚在 codePath 是否落在 /data/app，而不是 sig 有没有变。 */
    {
        struct { const char *cp; int want; const char *name; } tc12[] = {
            { "/data/app/~~x==/com.android.systemui-y==/base.apk", 1, "覆盖层(真装包)" },
            { "/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk", 0, "ROM版(删包回落)" },
            { "/product/priv-app/MiuiSystemUI/MiuiSystemUI.apk",   0, "product 分区" },
            { "/data/appfoo/base.apk",                             0, "前缀相似" },
            { "",                                                  0, "空路径" },
        };
        int k12;
        for (k12 = 0; k12 < (int)(sizeof(tc12) / sizeof(tc12[0])); k12++) {
            int g = code_path_is_overlay(tc12[k12].cp);
            int pass = (g == tc12[k12].want);
            printf("  T12.%d %s -> %d（应 %d）%s\n",
                   k12 + 1, tc12[k12].name, g, tc12[k12].want, pass ? "PASS" : "FAIL");
            if (!pass) ok = 0;
        }
    }

    /* T13: 备份轮转必须按 mtime，不能按文件名里的 @up
     *
     * @up 是【当前 boot 内】的运行秒数，重启后从 0 重来，跨 boot 完全不可比。
     * 实弹事故：新备份 com.android.systemui@72（本 boot 刚起来）与旧备份
     * @6036/@4971（上一 boot）同处一个目录，按 up 降序排列时新备份排最末，
     * 被当成「最旧」删掉 —— 刚存下的坏包现场证据当场消失，无法回滚/取证。
     * 这里造三个 mtime 递减的备份，轮转后最旧的（mtime 最小）必须被删，
     * 最新的 @72 必须留下。 */
    {
        const char *td = st_tmp;
        struct { const char *name; long ago; int keep; } tc13[] = {
            { "com.t.prune@6036", 7200, 0 },   /* 最旧，应删 */
            { "com.t.prune@4971", 3600, 1 },   /* 次旧，应留 */
            { "com.t.prune@72",      0, 1 },   /* 最新，必须留（按 up 排序会删它） */
        };
        cfg_t c13;
        char cmd13[700], p13[1100];
        int k13, good = 1;

        snprintf(cmd13, sizeof(cmd13), "rm -rf '%s'; mkdir -p '%s'", td, td);
        sh_run(cmd13);
        for (k13 = 0; k13 < 3; k13++) {
            struct timespec ts[2];
            snprintf(p13, sizeof(p13), "%s/%s", td, tc13[k13].name);
            if (mkdir(p13, 0755) != 0) good = 0;
            ts[0].tv_sec = ts[1].tv_sec = (time_t)((long) time(NULL) - tc13[k13].ago);
            ts[0].tv_nsec = ts[1].tv_nsec = 0;
            utimensat(AT_FDCWD, p13, ts, 0);
        }
        memset(&c13, 0, sizeof(c13));
        c13.bak_keep = 2;
        snprintf(c13.bak_dir, sizeof(c13.bak_dir), "%s", td);
        bak_prune(&c13, "com.t.prune");

        for (k13 = 0; k13 < 3; k13++) {
            struct stat st13;
            int e13;
            snprintf(p13, sizeof(p13), "%s/%s", td, tc13[k13].name);
            e13 = (stat(p13, &st13) == 0);
            if (e13 != tc13[k13].keep) {
                good = 0;
                printf("  T13 ! %s %s（应%s）\n", tc13[k13].name,
                       e13 ? "仍在" : "被删", tc13[k13].keep ? "保留" : "删除");
            }
        }
        printf("  T13 轮转按 mtime（最新 @72 必须留）%s\n", good ? "PASS" : "FAIL");
        if (!good) ok = 0;
        snprintf(cmd13, sizeof(cmd13), "rm -rf '%s'", td);
        sh_run(cmd13);
    }

    /* 恢复原始历史，别污染真实判定 */
    if (saved) {
        f = fopen(path, "w");
        if (f) { fwrite(out, 1, olen, f); fclose(f); }
        printf("  已恢复原始 crash_hist（%d 字节）\n", (int)olen);
    } else {
        unlink(path);
        printf("  原本无 crash_hist，已清理\n");
    }

    /* 临时现场兜底清理（T13 正常路径已删，这里防中途 return 残留） */
    {
        char t[320];
        snprintf(t, sizeof(t), "rm -rf '%s'", st_tmp);
        sh_run(t);
    }

    printf("%s\n", ok ? "SELF-TEST PASS" : "SELF-TEST FAIL");
    return ok ? 0 : 1;
}

static int cmd_diagnose(void)
{
    cfg_t c;
    int i, np, db_cnt = 0, db_attr = ATTR_NONE, pid_cnt, attr;
    char pkgs[8][160];
    char dsrc[300] = "";
    char why[192] = "";
    long up, since, dmax;

    cfg_load(&c);
    up = now_up();
    np = split_target(&c, pkgs, 8);
    since = now_ms_rt() - (long)c.crash_window_sec * 1000L;
    dmax = drop_max();

    printf("===== camd diagnose（只读，不动作）=====\n");
    printf("up=%lds  win=%ds  thr=%d\n", up, c.crash_window_sec, c.crash_threshold);
    if (dmax > 0)
        printf("最新 dropbox 条目 age=%lds（仅参考，单条不触发）\n",
               (now_ms_rt() - dmax) / 1000);

    for (i = 0; i < np; i++) {
        char d[640], key[220];
        long kb, old;
        int cur;
        snprintf(key, sizeof(key), "spid_%s", pkgs[i]);
        cur = pid_of_pkg_fresh(pkgs[i]);
        old = st_get(key);
        /* pid 变化只记账：它只证明「进程重启过」，算不算崩由窗口计数决定 */
        printf("  %-32s pid=%-6d prev=%-6ld %s\n", pkgs[i], cur, old,
               (old > 0 && (long)cur != old) ? "→ pid 变过（只记账，不算崩）"
                                             : "→ 正常");
        if (find_ovl(pkgs[i], d, sizeof(d))) {
            kb = dir_kb(d);
            printf("      覆盖层: %s (%ld KB)\n", d, kb);
        } else {
            pkginfo_t pi2;
            memset(&pi2, 0, sizeof(pi2));
            pkg_query(pkgs[i], &pi2);
            printf("      覆盖层: 无（系统分区版本，不会去删）\n");
            printf("      codePath=%s stamp=%ld\n",
                   pi2.found ? pi2.code_path : "(未找到)",
                   apk_stamp(pi2.code_path));
        }
    }

    /* 判定依据：与 rescue_tick 同一套口径，避免「诊断说崩了但 tick 不动」 */
    dropbox_window(since, &db_cnt, &db_attr, dsrc, sizeof(dsrc), NULL);
    pid_cnt = crash_hist_count(up, c.crash_window_sec);
    printf("\n[判定依据]\n");
    printf("  dropbox 窗口内命中 %d 条%s%s\n", db_cnt,
           dsrc[0] ? "  最新=" : "", dsrc);
    printf("  pid 变化窗口内 %d 次\n", pid_cnt);
    printf("  阈值 %d 次 → %s\n", c.crash_threshold,
           (db_cnt >= c.crash_threshold || pid_cnt >= c.crash_threshold)
               ? "会触发修复" : "不会触发");
    attr = culprit_attr(since, why, sizeof(why));
    printf("  归因(窗口内): %s (%s)\n",
           attr == ATTR_PLUGIN ? "plugin" :
           attr == ATTR_SYSUI  ? "sysui"  :
           attr == ATTR_BOTH   ? "both（判不出 → B段双修）" : "none", why);
    if (attr == ATTR_BOTH) {
        char w2[192] = "";
        attr = culprit_attr(0, w2, sizeof(w2));
        printf("  归因(不限时间,仅供参考): %s (%s)\n",
               attr == ATTR_PLUGIN ? "plugin" :
               attr == ATTR_SYSUI  ? "sysui"  :
               attr == ATTR_BOTH   ? "both"   : "none", w2);
    }
    return 0;
}

static int cmd_fix(int argc, char **argv)
{
    cfg_t c;
    char note[800];
    char why[160];
    int i, rc = 1;

    cfg_load(&c);

    /*
     * 先对齐 boot 状态。cmd_fix 不走 rescue_tick，若这里不登记 boot_id，
     * 之后第一次 tick 会把缺失的 state/boot_id 当成「新 boot」→ 清空计数，
     * 刚累加的 sr_count 直接归零，熔断形同虚设。
     * 手动 --fix 是用户明确意图，故即便已熔断也只提示、不拒绝执行。
     */
    if (guard(&c, why, sizeof(why)))
        printf("[warn] 熔断中（%s），手动操作照常执行\n", why);

    if (argc > 2) {
        /* 指定包 */
        for (i = 2; i < argc; i++)
            if (fix_pkg(&c, argv[i], 1, note, sizeof(note)) == 0) { printf("OK %s: %s\n", argv[i], note); rc = 0; }
            else printf("FAIL %s: %s\n", argv[i], note);
    } else {
        char pkgs[8][160];
        int np = split_target(&c, pkgs, 8);
        for (i = 0; i < np; i++) {
            char d[640];
            if (!find_ovl(pkgs[i], d, sizeof(d))) continue;
            if (fix_pkg(&c, pkgs[i], 1, note, sizeof(note)) == 0) { printf("OK %s: %s\n", pkgs[i], note); rc = 0; }
            else printf("FAIL %s: %s\n", pkgs[i], note);
            break;      /* 一次只修一个包 */
        }
    }
    if (rc != 0) printf("无可修复目标（未找到覆盖层）\n");
    return rc;
}

/* camd --set-apk [<path.apk>|none]
 * 设置 B 段（界面修复）用的兜底安装包。设的时候就解析包名并校验，
 * 避免真出事了才发现备的包是坏的 —— 那时候再换包要重启。
 *
 * 这条路故意只开给命令行，不开给 UI 文件通道：ui_apply() 的白名单
 * 拒绝 target/reboot_cmd/bak_dir，user_apk 同理 —— 一个能写通道文件的
 * App 若能指定任意 APK 路径，就等于给了它「让 camd 以 root 装任意包」的权限。 */
static int cmd_set_apk(int argc, char **argv)
{
    cfg_t c;
    const char *v = (argc >= 3) ? argv[2] : "";

    cfg_load(&c);

    if (!v[0]) {
        printf("[user_apk] %s\n", c.user_apk[0] ? c.user_apk : "(未设置)");
        return 0;
    }
    if (!strcmp(v, "none")) {
        c.user_apk[0] = 0;
    } else {
        char pkg[192];

        if (access(v, R_OK) != 0) {
            printf("FAIL 文件不可读: %s\n", v);
            return 1;
        }
        if (apk_pkgname(v, pkg, sizeof(pkg)) != 0) {
            printf("FAIL 包名解析失败（不是合法 APK？）: %s\n", v);
            return 1;
        }
        if (!pkgname_ok(pkg)) {
            printf("FAIL 包名非法: %s\n", pkg);
            return 1;
        }
        snprintf(c.user_apk, sizeof(c.user_apk), "%s", v);
        printf("OK 兜底包 = %s (包名 %s)\n", v, pkg);
    }
    if (cfg_save(&c) != 0) {
        printf("FAIL 配置写入失败\n");
        return 1;
    }
    if (!c.user_apk[0]) printf("OK 已清空兜底包\n");
    return 0;
}

static int cmd_reset_halt(void)
{
    st_del("halt");
    st_set("sr_count", 0);
    st_set("sr_total", 0);
    st_set("nofx_count", 0);
    st_set("sr_ts", 0);
    st_set("seen_drop", drop_max());
    rlog("HALT_CLEARED by cli");
    printf("✅ 熔断已清除，计数归零\n");
    return 0;
}

/* camd --shield [on|off|status]
 * 保命屏蔽的手动入口：改配置 + 立即生效，并把结果打给操作者。 */
static int cmd_shield(int argc, char **argv)
{
    const char *act = (argc >= 3) ? argv[2] : "status";
    cfg_t c;
    char raw[1024];
    int present = 0;
    long lim = -1;

    cfg_load(&c);

    if (!strcmp(act, "on") || !strcmp(act, "off")) {
        int on = !strcmp(act, "on");
        c.shield = on;
        if (cfg_save(&c) != 0) {
            printf("FAIL 配置写入失败\n");
            return 1;
        }
        if (on) {
            if (shield_apply(&c) == 0) {
                printf("OK 已屏蔽系统自救（crash_limit=%d, props=1）\n", c.shield_limit);
            } else {
                printf("FAIL 屏蔽未生效，请检查 root / settings 服务\n");
                return 1;
            }
        } else {
            if (shield_off() == 0) {
                printf("OK 已还原系统自救（config shield=0）\n");
            } else {
                printf("FAIL 还原异常，请用 camd --status 核对\n");
                return 1;
            }
        }
        rlog("SHIELD CLI %s ok", act);
        return 0;
    }

    /* status */
    printf("[shield] config=%d (1=屏蔽 0=放行) check=%ds limit=%d\n",
           c.shield, c.shield_check, c.shield_limit);
    if (shield_read(raw, sizeof(raw), &lim, &present) != 0) {
        printf("[am] 读取失败：settings 不可用\n");
    } else {
        printf("[am] crash_limit=%ld %s\n", lim,
               lim == c.shield_limit ? "已生效"
                                     : (present ? "未生效" : "原本未设置(按默认12)"));
        if (raw[0]) printf("[am] constants=%s\n", raw);
    }
    printf("[state] shield_ok=%ld\n", st_get("shield_ok"));
    {
        char props[1024] = "";
        if (sh_capture("getprop persist.sys.rescuepartyplus.disable", props, sizeof(props)) == 0)
            printf("[prop] disable=%s\n", props[0] ? props : "(空)");
    }
    return 0;
}

/* 诊断 A 段决策：默认 dry-run（只打印打算做什么），--apply 才真动手 */
static int cmd_a_phase(int argc, char **argv)
{
    cfg_t c;
    char why[256] = "";
    char ids[16][64];
    int dry = 1, i, n, a;

    if (argc > 2 && strcmp(argv[2], "--apply") == 0) dry = 0;
    cfg_load(&c);

    printf("phase=%s ce_ok=%d auto_fix=%d up=%ld dry=%d\n",
           phase_de() ? "DE" : "CE", ce_ok(), c.auto_fix, now_up(), dry);

    n = mod_scan(ids, 16, 0);
    printf("mod_systemui_active=%d", n);
    for (i = 0; i < n; i++) printf(" %s", ids[i]);
    printf("\n");
    n = mod_scan(ids, 16, 1);
    printf("mod_systemui_disabled=%d", n);
    for (i = 0; i < n; i++) printf(" %s", ids[i]);
    printf("\n");

    a = culprit_attr(0, why, sizeof(why));
    printf("attr=%s why=%s\n",
           a == ATTR_PLUGIN ? "plugin" : a == ATTR_SYSUI ? "sysui" : "none", why);
    printf("systemui_pid=%d alive=%d\n",
           pid_of_pkg_fresh("com.android.systemui"),
           pid_of_pkg_fresh("com.android.systemui") > 0);
    /* A 段判据的可验证输出：账本条数、窗口、阈值。dry 只读不写，
     * 所以这里的 a_hist 是真实账本，直接反映「现在会不会触发」。 */
    printf("a_win=%ds a_thr=%d a_hist=%d\n",
           c.a_window_sec, c.a_circ_thr,
           a_hist_count(now_up(), c.a_window_sec));

    /* dry 也走一遍 a_phase：它会打印「打算干什么」而不真动手，
     * 是验证决策链唯一的无副作用手段 */
    printf("a_phase=%d\n", a_phase(&c, dry));
    return 0;
}

/* 离线回归：对单个 crash/dropbox 文本跑纯文本归因，只读、无副作用。
 * 用途：拿历史 dropbox 样本逐条验证判据（新规则上线前的 ground truth 回归）。 */
static int cmd_attr_file(int argc, char **argv)
{
    int fd, a;
    struct stat st;
    char *buf;
    ssize_t n;

    if (argc < 3) { fprintf(stderr, "用法: camd --attr-file <crash.txt>\n"); return 2; }
    fd = open(argv[2], O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "打不开 %s: %s\n", argv[2], strerror(errno));
        return 2;
    }
    if (fstat(fd, &st) != 0 || st.st_size <= 0 || st.st_size > (1 << 20)) {
        fprintf(stderr, "文件为空或超过 1MB\n");
        close(fd);
        return 2;
    }
    buf = (char *)malloc((size_t)st.st_size + 1);
    if (!buf) { close(fd); return 2; }
    n = read(fd, buf, (size_t)st.st_size);
    close(fd);
    if (n <= 0) { free(buf); return 2; }
    buf[n] = 0;

    a = attribute_text(buf, (size_t)n);
    printf("%-56s => %s\n", argv[2],
           a == ATTR_SYSUI  ? "SYSUI"  :
           a == ATTR_PLUGIN ? "PLUGIN" :
           a == ATTR_BOTH   ? "BOTH"   : "NONE");
    free(buf);
    return 0;
}

/* 返回 >=0 表示本命令已被 rescue 模块处理；-1 表示不归它管 */
int rescue_cli(int argc, char **argv)
{
    char sp[320];
    if (argc < 2) return -1;
    cam_path("state", sp, sizeof(sp));
    mkdir_p2(sp, 0700);
    cfg_ensure();               /* 新机首次跑任何子命令都补出 config */
    if (!strcmp(argv[1], "--status"))     return cmd_status();
    if (!strcmp(argv[1], "--pkg-of"))     return cmd_pkg_of(argc, argv);
    if (!strcmp(argv[1], "--install-apk"))      return cmd_install_apk(argc, argv);
    if (!strcmp(argv[1], "--uninstall-update")) return cmd_uninstall_update(argc, argv);
    if (!strcmp(argv[1], "--reset-halt")) return cmd_reset_halt();
    if (!strcmp(argv[1], "--set-apk"))    return cmd_set_apk(argc, argv);
    if (!strcmp(argv[1], "--diagnose"))   return cmd_diagnose();
    if (!strcmp(argv[1], "--self-test"))  return cmd_self_test();
    if (!strcmp(argv[1], "--watch"))      return cmd_watch(argc, argv);
    if (!strcmp(argv[1], "--snap"))       return cmd_snap(argc, argv);
    if (!strcmp(argv[1], "--fix"))        return cmd_fix(argc, argv);
    if (!strcmp(argv[1], "--tick-now"))   { rescue_tick(); return 0; }
    if (!strcmp(argv[1], "--shield"))     return cmd_shield(argc, argv);
    if (!strcmp(argv[1], "--a-phase"))    return cmd_a_phase(argc, argv);
    if (!strcmp(argv[1], "--attr-file"))  return cmd_attr_file(argc, argv);
    return -1;
}

/* ==================================================================
 * UI ↔ camd 文件通道
 *
 * 为什么不用 /data/adb 或 /data/system/camellia_action：
 *   模块 UI 跑在普通 App 进程（uid=u0_aXXX），既没有 root，也没有
 *   system uid —— 这两个目录它连接口都打不开（isDirectory() 就是 false），
 *   更没有 su 可用。所以在 App 进程里 fork("sh -c 'test -x /data/adb/...'")
 *   必然失败，UI 只能显示「未部署」。
 *
 * 因此走「App 可写、root 可读写」的外部私有目录：
 *     /sdcard/Android/data/com.hyper.Camellia/files/
 *   camd_status.txt   camd 周期写（App 只读）—— 状态展示
 *   camd_cmd          UI 写（camd 读后立即 unlink）—— 开关上行
 *
 * Android 11+ 该目录受 scoped storage 保护，其它应用无权写入，
 * 所以下行指令通道不会被第三方投毒。即便如此，ui_apply() 仍然只
 * 白名单接受「开关与阈值」，target / reboot_cmd / bak_dir 一律拒绝：
 * 否则一个能写该目录的 App 就能把救援引到任意包名或换掉重启命令。
 * ================================================================== */
#define UI_DIR     "/sdcard/Android/data/com.hyper.Camellia/files"
#define UI_STATUS  UI_DIR "/camd_status.txt"
#define UI_CMD     UI_DIR "/camd_cmd"
/* 快照索引与候选包：App 没有 root，读不了 /data/adb/camellia/state 与 bak，
 * 也枚举不到系统包（Android 11+ 包可见性），统一由 camd 代读后落成这两个文件。 */
#define UI_SNAP    UI_DIR "/camd_snap.txt"

/* App 的 data 目录：它的属主就是 App 运行时的 uid，用它反查 App uid，
 * 不需要 pm / packages.list（那些在开机早期可能还不可读）。 */
#define UI_APP_DATA "/data/user/0/com.hyper.Camellia"

/* 建通信目录，并把属主交还给 App。
 *
 * 只有「camd 先起、App 还没装过」的机器上，mkdir 出来的目录属主才会是
 * root；而 /sdcard 的 fuse 只认「uid == 目录属主」，App 既写不进 camd_cmd，
 * 也读不到 camd_status.txt —— 面板显示「未部署 / 版本 - / 配置未运行」，
 * 但 camd 自己是 owner，一切自测正常，现场极难排查（第二台手机即此情况）。
 * gid 取 ext_data_rw(1077)，与系统自己创建的 Android/data/<pkg> 一致；
 * 目录已存在且属主正确时什么都不做（幂等）。 */
static uid_t g_ui_uid = 0;     /* App uid 缓存，让频繁探测只花 1 次 stat */
static gid_t g_ui_gid = 0;

/* 廉价探测：1 次 stat 判断目录属主是否仍是 App。
 * 返回 1=无需处理，0=需要走 ui_ensure_dir()。
 *
 * 为什么缓存 uid 不会漏判「App 重装换了 uid」：卸载会连
 * /sdcard/Android/data/<pkg> 整个目录一起删掉，目录消失必然让探测失败；
 * 而 adb install -r 覆盖安装 uid 不变，本就不需要处理。 */
static int ui_dir_ok(void)
{
    struct stat st;

    if (!g_ui_uid) return 0;
    if (stat(UI_DIR, &st) != 0) return 0;
    return st.st_uid == g_ui_uid;
}

/* 0=已就绪；-1=App 数据目录暂时不可读（CE 未解锁/App 未装），稍后再试 */
static int ui_ensure_dir(void)
{
    struct stat appst, dirst, pst;
    gid_t gid = 1077;          /* AID_EXT_DATA_RW，全版本固定值 */

    mkdir_p2(UI_DIR, 0771);
    if (stat(UI_DIR, &dirst) != 0) return -1;
    if (stat("/sdcard/Android/data/com.hyper.Camellia", &pst) == 0 && pst.st_gid)
        gid = pst.st_gid;      /* 优先跟随父目录的真实 gid */
    if (stat(UI_APP_DATA, &appst) != 0) return -1;  /* CE 未解锁 / 还没装 */
    g_ui_uid = appst.st_uid;
    g_ui_gid = gid;
    if (dirst.st_uid == appst.st_uid && dirst.st_gid == gid) return 0;
    if (chown(UI_DIR, appst.st_uid, gid) != 0) return -1;  /* 非 root 时静默放弃 */
    (void)chmod(UI_DIR, 02771);    /* setgid + 与系统一致的 drwxrws--x */
    return 0;
}

/* tmp + rename 原子提交：App 永远读不到写了一半的状态文件。
 *
 * 关键：/sdcard 是 fuse(fsmediaprovider)，Android/data/<pkg> 下的访问校验
 * 只认「uid == 目录属主」，不看 POSIX 的 group/other 位。camd 是 root，
 * 直写的文件 uid=0，App 自己反而读不到（Permission denied）。
 * 所以写完立刻把属主交还给目录属主（App uid + ext_data_rw gid）。 */
static void ui_write_atomic(const char *path, const char *data, size_t len)
{
    char tmp[512];
    struct stat dirst;
    FILE *f;
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    f = fopen(tmp, "w");
    if (!f) return;
    if (len && fwrite(data, 1, len, f) != len) {
        fclose(f);
        unlink(tmp);
        return;
    }
    if (stat(UI_DIR, &dirst) == 0)
        (void)fchown(fileno(f), dirst.st_uid, dirst.st_gid);
    fclose(f);
    chmod(tmp, 0664);
    rename(tmp, path);
}

/* ------------------------------------------- 面板数据：包校验 / 快照 / 候选 */

/* 包名字符集校验：pm 参数是拼进 shell 的，必须挡住引号/分号之类的注入面 */
static int pkg_name_ok(const char *s)
{
    size_t i, n;
    if (!s || !(n = strlen(s)) || n > 100) return 0;
    if (s[0] == '.') return 0;
    for (i = 0; i < n; i++) {
        char ch = s[i];
        if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
              (ch >= '0' && ch <= '9') || ch == '.' || ch == '_'))
            return 0;
    }
    return 1;
}


struct snap_ent {
    char name[128];
    char pkg[128];
    long mt;
    long bytes;
    int  apk;
};

/* 扫 bak_dir，按 mtime 新→旧收集快照目录 */
static int snap_collect(const cfg_t *c, struct snap_ent *v, int max)
{
    DIR *d;
    struct dirent *de;
    int n = 0;

    d = opendir(c->bak_dir);
    if (!d) return 0;
    while ((de = readdir(d)) != NULL && n < max) {
        char mp[1200], ap[1280], line[400];
        struct stat st;
        FILE *f;

        if (de->d_name[0] == '.') continue;
        snprintf(mp, sizeof(mp), "%s/%s", c->bak_dir, de->d_name);
        if (stat(mp, &st) != 0 || !S_ISDIR(st.st_mode)) continue;

        snprintf(v[n].name, sizeof(v[n].name), "%s", de->d_name);
        v[n].pkg[0] = 0;

        /* 包名优先取 meta 的 pkg=（权威），退回目录名 @ 之前那段 */
        snprintf(line, sizeof(line), "%s/meta", mp);
        f = fopen(line, "r");
        if (f) {
            char buf[400];
            while (fgets(buf, sizeof(buf), f)) {
                if (!strncmp(buf, "pkg=", 4)) {
                    char *e = strpbrk(buf + 4, "\r\n");
                    if (e) *e = 0;
                    snprintf(v[n].pkg, sizeof(v[n].pkg), "%s", buf + 4);
                    break;
                }
            }
            fclose(f);
        }
        if (!pkg_name_ok(v[n].pkg)) {
            char *at = strchr(de->d_name, '@');
            size_t i = at ? (size_t)(at - de->d_name) : strlen(de->d_name);
            if (i >= sizeof(v[n].pkg)) i = sizeof(v[n].pkg) - 1;
            memcpy(v[n].pkg, de->d_name, i);
            v[n].pkg[i] = 0;
        }
        if (!pkg_name_ok(v[n].pkg)) continue;   /* 认不出包名的快照对面板没意义 */

        v[n].mt = (long) st.st_mtime;
        snprintf(ap, sizeof(ap), "%s/base.apk", mp);
        if (stat(ap, &st) == 0) {
            v[n].bytes = (long) st.st_size;
            v[n].apk = 1;
        } else {
            v[n].bytes = 0;
            v[n].apk = 0;
        }
        n++;
    }
    closedir(d);

    /* 新的排前面：面板一屏就那么多行，用户最想看到的是刚存下的那份坏包 */
    {
        int i, j;
        for (i = 1; i < n; i++) {
            struct snap_ent k = v[i];
            for (j = i - 1; j >= 0 && v[j].mt < k.mt; j--) v[j + 1] = v[j];
            v[j + 1] = k;
        }
    }
    return n;
}

/* 落快照索引 camd_snap.txt：一行一条 name|pkg|mtime|bytes|apk，
 * 返回快照总数（顺带供 status 的 bak_n 用）。 */
static int ui_write_snaps(const cfg_t *c)
{
    static char last[4096];
    static struct snap_ent v[64];
    char body[4096];
    int n, i, off = 0;

    n = snap_collect(c, v, 64);
    body[0] = 0;
    for (i = 0; i < n && off < (int) sizeof(body) - 256; i++)
        off += snprintf(body + off, sizeof(body) - (size_t) off,
                        "%s|%s|%ld|%ld|%d\n",
                        v[i].name, v[i].pkg, v[i].mt, v[i].bytes, v[i].apk);

    if (!strcmp(body, last)) return n;      /* 没变化别反复写 flash */
    snprintf(last, sizeof(last), "%s", body);
    if (!ui_dir_ok()) (void) ui_ensure_dir();   /* 1 次 stat；正常态到此为止 */
    ui_write_atomic(UI_SNAP, body, strlen(body));
    return n;
}

/* 把快照列表编码成单行 "name,pkg,mt,bytes,apk;..."，塞进 socket 状态帧下发
 * （App 读 /sdcard 是 0 字节，只有 socket 通道可靠）。 */
static int snap_encode(const cfg_t *c, char *out, size_t n)
{
    struct snap_ent v[64];
    int cnt = snap_collect(c, v, 64), i;
    size_t off = 0;
    if (n) out[0] = 0;
    for (i = 0; i < cnt; i++) {
        int w = snprintf(out + off, n - off, "%s%s,%s,%ld,%ld,%d",
                         i ? ";" : "", v[i].name, v[i].pkg, v[i].mt, v[i].bytes, v[i].apk);
        if (w <= 0 || (size_t) w >= n - off) break;
        off += (size_t) w;
        if (off + 200 >= n) break;
    }
    return (int) off;
}

/* 整份回写配置：保留全部键，未显式给的用内存里的当前值 */
/* --watch on|off ：安装事件监听开关（写 config）。
 * 不带参数时只回显当前值，方便 shell 里查。 */
static int cmd_watch(int argc, char **argv)
{
    cfg_t c;

    cfg_load(&c);
    if (argc < 3) {
        printf("watch_install=%d\n", c.watch_install);
        return 0;
    }
    if (!strcmp(argv[2], "on"))       c.watch_install = 1;
    else if (!strcmp(argv[2], "off")) c.watch_install = 0;
    else { printf("用法: --watch on|off\n"); return 2; }

    if (cfg_save(&c) != 0) { printf("write config failed\n"); return 1; }
    printf("watch_install=%d\n", c.watch_install);
    rlog("WATCH_SET %d", c.watch_install);
    return 0;
}

/* --snap [pkg] ：列出修复前落下的坏包快照（base.apk + meta）。
 * 快照目录就是 bak_dir，命名 <pkg>@<uptime>，meta 第一行即 pkg=... */
static int cmd_snap(int argc, char **argv)
{
    cfg_t c;
    DIR *d;
    struct dirent *de;
    int n = 0;

    cfg_load(&c);
    d = opendir(c.bak_dir);
    if (!d) {
        printf("(无快照目录 %s)\n", c.bak_dir);
        return 0;
    }
    while ((de = readdir(d)) != NULL) {
        char mp[1200], line[400];
        FILE *f;

        if (de->d_name[0] == '.') continue;
        if (argc >= 3 && strncmp(de->d_name, argv[2], strlen(argv[2])) != 0) continue;

        snprintf(mp, sizeof(mp), "%s/%s/meta", c.bak_dir, de->d_name);
        line[0] = 0;
        f = fopen(mp, "r");
        if (f) {
            char buf[400];
            if (fgets(buf, sizeof(buf), f)) snprintf(line, sizeof(line), "%s", buf);
            fclose(f);
        }
        printf("%-46s %s", de->d_name, line[0] ? line : "(meta missing)\n");
        n++;
    }
    closedir(d);
    printf("共 %d 份快照，目录 %s\n", n, c.bak_dir);
    return 0;
}

/* 生成带注释的 config 全文。
 * 注释版是唯一格式：cfg_save 与 cfg_ensure 都走这里。
 * 否则 App UI 下发一次开关（UI set auto_fix=...）就会把用户看到的注释抹掉，
 * 用户改到一半的说明凭空消失，比没有配置更迷惑。 */
static int cfg_render(const cfg_t *c, char *out, size_t n)
{
    return snprintf(out, n,
        "# camellia 急救配置（由 camd 自动维护，注释随保存重新生成）\n"
        "# 改完即时生效：camd 每个自检周期都会重读本文件，无需重启。\n"
        "\n"
        "# 1=自动救援（删坏覆盖层 / 软重启）   0=只诊断不动手\n"
        "auto_fix=%d\n"
        "\n"
        "# 自检周期（秒，5~3600）\n"
        "tick_sec=%d\n"
        "\n"
        "# 监视的包（固定不可改：系统界面 + 系统界面组件）\n"
        "target=%s\n"
        "\n"
        "# 坏包备份目录 / 每个包保留份数（0~5，0=不保留；同包同哈希自动去重不重复备份）\n"
        "bak_dir=%s\n"
        "bak_keep=%d\n"
        "\n"
        "# 单次开机内允许的软重启次数上限（1~3）\n"
        "sr_max=%d\n"
        "\n"
        "# 连续无效救援次数上限（1~10）\n"
        "nofx_max=%d\n"
        "\n"
        "# 软重启后观察期（秒，60~86400）\n"
        "stable_sec=%ld\n"
        "\n"
        "# 软重启命令\n"
        "reboot_cmd=%s\n"
        "\n"
        "# 保命盾 1=开 / 校验周期（秒）/ 触发阈值\n"
        "shield=%d\n"
        "shield_check=%d\n"
        "shield_limit=%d\n"
        "\n"
        "# 用户自备 APK 兜底路径（留空=不用）\n"
        "user_apk=%s\n"
        "\n"
        "# B 段（CE 解锁后）崩溃判定：窗口秒 / 次数阈值\n"
        "crash_window_sec=%d\n"
        "crash_threshold=%d\n"
        "\n"
        "# 安装事件监听 1=开（装包后重置判定）\n"
        "watch_install=%d\n"
        "\n"
        "# eBPF 加速检测 1=自动(可用即启用) 0=关闭(回退 pidfd+dropbox)\n"
        "ebpf=%d\n"
        "\n"
        "# A 段（DE 阶段）崩溃循环判定：窗口秒 / 窗口内 pid 变化阈值\n"
        "a_window_sec=%d\n"
        "a_circ_thr=%d\n",
        c->auto_fix, c->tick_sec, c->target, c->bak_dir, c->bak_keep,
        c->sr_max, c->nofx_max, c->stable_sec, c->reboot_cmd,
        c->shield, c->shield_check, c->shield_limit, c->user_apk,
        c->crash_window_sec, c->crash_threshold, c->watch_install, c->ebpf,
        c->a_window_sec, c->a_circ_thr);
}

static int cfg_save(const cfg_t *c)
{
    char path[320], body[4096];
    int n;

    cam_path("config", path, sizeof(path));
    n = cfg_render(c, body, sizeof(body));
    if (n <= 0 || (size_t) n >= sizeof(body)) return -1;
    ui_write_atomic(path, body, (size_t) n);
    chmod(path, 0600);
    return 0;
}

/* 临时调试：读本进程累计 rchar，用于定位「每 6s 514KB 读取」的来源。
 * 仅 UIDUMP_DBG 构建需要，默认不参与编译（省掉未使用函数告警）。 */
#ifdef UIDUMP_DBG
static unsigned long long ui_rchar(void)
{
    FILE *f = fopen("/proc/self/io", "r");
    char l[128];
    unsigned long long v = 0;
    if (!f) return 0;
    while (fgets(l, sizeof(l), f))
        if (!strncmp(l, "rchar:", 6)) { v = strtoull(l + 6, NULL, 10); break; }
    fclose(f);
    return v;
}
#endif

/*
 * UIDUMP_DBG 是纯诊断开关，默认关闭。
 * 打开时 ui_dump() 会在各阶段调 11 次 ui_rchar() —— 每次都是一轮
 * fopen("/proc/self/io") + 解析，而该文件的内容由内核遍历进程 mm 生成，
 * 比普通文件读取贵得多。生产版不需要这些分段计数，却要为此付
 * 11 次/proc 访问 × 17280 次/天 ≈ 19 万次/天的代价，故编译期移除。
 * 需要排查读量时：CAMD_DEBUG=1 ./tools/build_camd.sh
 */
#ifdef UIDUMP_DBG
#define UI_RC(v) ((v) = ui_rchar())
#else
#define UI_RC(v) ((void) 0)
#endif

static void ui_dump(int force)
{
    static long last = 0;
    static long last_write = 0;
    static char last_fp[3000];
    char fp[3000];
    char body[8192];
    char bid[64] = "unknown";
    char why[128] = "";
    char aact[64] = "", apkg[128] = "", awhy[128] = "";
    cfg_t c;
    long up = now_up();
    int halt, n, bak_n, cfg_ok = 0, changed;
    long amod;
#ifdef UIDUMP_DBG
    unsigned long long _r0 = 0, _r1 = 0, _r2 = 0, _r3 = 0, _r4 = 0;
    unsigned long long _r5 = 0, _r6 = 0, _r7 = 0, _r8 = 0, _r4b = 0;
#endif

    /* 5s 节流：UI 刷新足够快，也不至于每秒写一次 flash */
    if (!force && up - last < 5) return;
    last = up;
    UI_RC(_r0);
    /*DBG*/ (void)0;

    /*
     * 无客户端在线时降级为「纯心跳」——跳过下面全部状态收集。
     *
     * 下面的采集每轮要访问十几个文件：cfg_load 的 stat、boot_id、4 次
     * state 小文件（halt / a_last_*）、ui_write_snaps、mod_scan 的缓存判定、
     * config 的 stat。这些只在有人看面板时才有意义：业务内容本来就是
     * 「指纹变了才写」，无人看时采出来也只是覆盖上一次的同值内容。
     *
     * 但不能整段跳过 —— App 在 socket 不可用时会退回读 UI_STATUS
     * （CamdRescue.readStatus 的兜底路径），ts 停滞超过 FRESH_SEC=180s
     * 就会把正常运行的 camd 判成「未运行」。所以这里只重拼一个带新 ts 的
     * body 落盘，沿用上一次的业务指纹；业务状态的真实性由「连上瞬间的
     * force 刷新」补齐（见 bridge 里 camd_sock_accept() 后的调用）。
     *
     * 收益：面板关闭时每 5s 十几次文件访问 → 每 45s 一次写。
     */
    if (!force && camd_sock_active() == 0) {
        char hb[3200];
        int hn;
        if (up - last_write < UI_BEAT_SEC) return;
        if (!last_fp[0]) return;        /* 还没采集过，无可写内容 */
        last_write = up;
        hn = snprintf(hb, sizeof(hb), "ts=%ld\nuptime=%ld\n%s",
                      (long) time(NULL), up, last_fp);
        if (hn <= 0 || (size_t) hn >= sizeof(hb)) return;
        if (!ui_dir_ok()) (void) ui_ensure_dir();
        ui_write_atomic(UI_STATUS, hb, (size_t) hn);
        return;
    }

    cfg_load(&c); UI_RC(_r1);
    boot_id(bid, sizeof(bid));
    halt = st_str("halt", why, sizeof(why));
    st_str("a_last_action", aact, sizeof(aact));
    st_str("a_last_pkg", apkg, sizeof(apkg));
    st_str("a_last_why", awhy, sizeof(awhy)); UI_RC(_r2);

    /* 面板的「快照」数据在这里现算现落，内部有变化检测，没变就不写盘。 */
    bak_n = ui_write_snaps(&c); UI_RC(_r3);

    /* config 是否存在且非空：面板据此区分「首次运行自动生成」与「用户改过」 */
    {
        char cp[512];
        struct stat cst;
        cam_path("config", cp, sizeof(cp));
        cfg_ok = (stat(cp, &cst) == 0 && cst.st_size > 0) ? 1 : 0;
    }

    /*
     * 活跃界面模块数：mod_scan 要遍历 /data/adb/modules 下每个模块的
     * priv-app 目录并读脚本内容，不能跟着 5s 节流跑。60s 缓存一次足够 ——
     * 模块是被重启才改变的，不是秒级变化的量。
     */
    {
        static long last_scan = 0;
        static long cached = -1;
        if (cached < 0 || up - last_scan >= 60) {
            char tids[16][64];
            cached = mod_scan(tids, 16, 0);
            last_scan = up;
        }
        amod = cached;
    }
    UI_RC(_r4);

    const char *ph_dbg = phase_de_peek() ? "DE" : "CE";
    UI_RC(_r4b);
    n = snprintf(fp, sizeof(fp),
        "pid=%d\nboot_id=%s\n"
        "ver=%s\ncfg_ok=%d\n"
        "auto_fix=%d\ntick_sec=%d\nsr_max=%d\nnofx_max=%d\nstable_sec=%ld\n"
        "sr_count=%ld\nnofx_count=%ld\nhalt=%d\nhalt_reason=%s\ntarget=%s\n"
        "shield=%d\nshield_check=%d\nshield_limit=%d\nshield_ok=%ld\n"
        "watch_install=%d\nebpf=%d\nebpf_state=%d\nbak_dir=%s\nbak_n=%d\nbak_keep=%d\n"
        "a_window_sec=%d\na_circ_thr=%d\n"
        "phase=%s\na_mod=%ld\na_last_action=%s\na_last_pkg=%s\n"
        "a_last_why=%s\na_last_ts=%ld\n",
        (int) getpid(), bid,
        CAMD_VER, cfg_ok,
        c.auto_fix, c.tick_sec, c.sr_max, c.nofx_max, c.stable_sec,
        st_get("sr_count"), st_get("nofx_count"),
        halt ? 1 : 0, why, c.target,
        c.shield, c.shield_check, c.shield_limit, st_get("shield_ok"),
        c.watch_install, c.ebpf, rescue_ebpf_state(), c.bak_dir, bak_n, c.bak_keep,
        c.a_window_sec, c.a_circ_thr,
        ph_dbg, amod,
        aact[0] ? aact : "-", apkg[0] ? apkg : "-",
        awhy[0] ? awhy : "-", st_get("a_last_ts"));
    UI_RC(_r5);
    if (n <= 0 || (size_t) n >= sizeof(fp)) return;
    { int _dn = dsnap_status_fields(fp + n, sizeof(fp) - (size_t) n); if (_dn > 0) n += _dn; }

    /*
     * 写盘策略（性能优化）：过去 body 首行就带 ts=/uptime=，每 5s 必然不同，
     * 下面的 strcmp 永假 —— 等于每 5s 无条件重写一次 FUSE 文件。现在把业务
     * 内容与心跳拆开：
     *   · 业务指纹 fp 变化 → 立即写（UI 要立刻看到状态变化）
     *   · fp 未变          → 仅每 UI_BEAT_SEC 写一次心跳，供 UI 判活
     * 判活阈值在 App 侧 CamdRescue.FRESH_SEC，必须 > 心跳间隔的 2 倍以上。
     */
    changed = strcmp(fp, last_fp) != 0;
    if (!changed && !force && up - last_write < UI_BEAT_SEC) return;

    if (changed) snprintf(last_fp, sizeof(last_fp), "%s", fp);

    {
        char snapenc[1600]; snap_encode(&c, snapenc, sizeof snapenc);
        int bn = snprintf(body, sizeof(body), "ts=%ld\nuptime=%ld\n%s\nsnaps=%s",
                          (long) time(NULL), up, fp, snapenc);
        if (bn < 0) return;
        if ((size_t) bn >= sizeof(body)) bn = (int) strlen(body);
        last_write = up;

        if (!ui_dir_ok()) (void) ui_ensure_dir();   /* 1 次 stat；正常态到此为止 */
        UI_RC(_r6);
        ui_write_atomic(UI_STATUS, body, (size_t) bn);
        UI_RC(_r7);

        /* 推一份给 socket 客户端：App 在线时无需再读这个 FUSE 文件。 */
        camd_sock_broadcast(body, (size_t) bn);
    }
    UI_RC(_r8);
#ifdef UIDUMP_DBG
    if (_r0) rlog("UIDUMP_DBG cfg=%llu st=%llu snap=%llu mod=%llu ph=%llu fp=%llu dir=%llu wr=%llu sk=%llu tot=%llu",
                  _r1 - _r0, _r2 - _r1, _r3 - _r2, _r4 - _r3, _r4b - _r4,
                  _r5 - _r4b, _r6 - _r5, _r7 - _r6, _r8 - _r7, ui_rchar() - _r0);
#endif
}

/* 快照目录名校验：只允许 <pkg>@<uptime> 这种形态。
 * 面板传上来的是「目录名」不是路径，但它终究是外部字符串 ——
 * 一旦含 "/" 或 ".." 就能让 rm -rf 落到 bak_dir 之外，而删目录不可逆。 */
static int snap_name_ok(const char *s)
{
    size_t i, n;
    if (!s || !(n = strlen(s)) || n > 100) return 0;
    if (strstr(s, "..")) return 0;
    for (i = 0; i < n; i++) {
        char ch = s[i];
        if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
              (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' ||
              ch == '@' || ch == '-'))
            return 0;
    }
    return 1;
}

/* 应用一条 UI 指令。返回 1 表示已处理（含「值没变化」），0 表示拒绝。 */
static int ui_apply(const char *k, const char *v)
{
    cfg_t c;
    int dirty = 0, ebpf_changed = 0;

    if (!strcmp(k, "reset_halt")) {
        st_del("halt");
        st_set("sr_count", 0);
        st_set("sr_total", 0);
        st_set("nofx_count", 0);
        st_set("sr_ts", 0);
        rlog("UI reset_halt");
        return 1;
    }

    /*
     * 动作型指令放在 cfg_load 之前：它们不改配置，若混进下面的
     * 「dirty → cfg_save」链，dirty=0 时会被直接 return 1 吞掉不执行。
     */
    if (!strcmp(k, "snap_list")) {
        ui_dump(1);                  /* 强制刷一次，索引最新 */
        return 1;
    }
    /* 定时快照命令（dsnap_run/list/del/restore/cfg）转交模块处理 */
    if (dsnap_ui_apply(k, v)) return 1;
    if (!strcmp(k, "snap_del")) {
        char path[1400], cmd[1500];
        cfg_load(&c);
        if (!snap_name_ok(v)) { rlog("UI snap_del reject name=%s", v); return 0; }
        snprintf(path, sizeof(path), "%s/%s", c.bak_dir, v);
        snprintf(cmd, sizeof(cmd), "rm -rf '%s' 2>/dev/null", path);
        if (sh_run(cmd) != 0 || access(path, F_OK) == 0) {
            rlog("UI snap_del failed %s", v);
            return 0;
        }
        rlog("UI snap_del %s", v);
        ui_dump(1);
        return 1;
    }
    if (!strcmp(k, "snap_restore")) {
        char apk[1500], epkg[192], res[512];
        char *at;
        cfg_load(&c);
        if (!snap_name_ok(v)) { rlog("UI snap_restore reject name=%s", v); return 0; }
        snprintf(apk, sizeof(apk), "%s/%s/base.apk", c.bak_dir, v);
        if (access(apk, F_OK) != 0) {
            rlog("UI snap_restore missing %s", v);
            snap_restore_note(v, "快照里没有 base.apk，无法重装");
            ui_dump(1);
            return 0;
        }
        /* 快照目录名是 "<包名>@<uptime>"，取 @ 之前那段作为期望包名，
         * 交给 pm_install 做「装下去的是不是同一个包」的预检。 */
        snprintf(epkg, sizeof(epkg), "%s", v);
        at = strchr(epkg, '@');
        if (at) *at = 0;

        /* 必须走 pm_install，不能像老代码那样直接 pm install 快照原路径：
         * bak_dir 在 /data/adb 下，/data/adb → camellia → bak → 快照目录
         * 整条链都是 0700 root，system_server(uid=1000) 读不到源 APK
         * （实测 su 1000 dd 读出 0 字节），pm 只会一声不响地失败。
         * pm_install 会先 apk_stage 拷到 /data/local/tmp（shell_data_file，
         * system_server 可读，chmod 644）再装，用 "Success" 判成败并做
         * 装后复核，失败原因经 res 带回，写进 a_last_why 供 UI 展示。 */
        if (pm_install(apk, epkg, 0, res, sizeof(res)) != 0) {
            rlog("SNAP_RESTORE %s failed %s", v, res);
            snap_restore_note(v, res);
        } else {
            rlog("SNAP_RESTORE %s ok %s", v, res);
            snap_restore_note(v, res);
        }
        ui_dump(1);
        return 1;
    }

    cfg_load(&c);   /* 先读全量，稍后整份回写（缺失键由默认值补齐） */
    if (!strcmp(k, "auto_fix")) {
        int x = atoi(v) ? 1 : 0;
        if (x != c.auto_fix) { c.auto_fix = x; dirty = 1; }
    } else if (!strcmp(k, "sr_max")) {
        int x = atoi(v);
        if (x >= 1 && x <= HARD_SR_MAX && x != c.sr_max) { c.sr_max = x; dirty = 1; }
    } else if (!strcmp(k, "nofx_max")) {
        int x = atoi(v);
        if (x >= 1 && x <= 10 && x != c.nofx_max) { c.nofx_max = x; dirty = 1; }
    } else if (!strcmp(k, "tick_sec")) {
        int x = atoi(v);
        if (x >= 5 && x <= 3600 && x != c.tick_sec) { c.tick_sec = x; dirty = 1; }
    } else if (!strcmp(k, "bak_keep")) {
        int x = atoi(v);
        if (x >= 0 && x <= BAK_KEEP_MAX && x != c.bak_keep) { c.bak_keep = x; dirty = 1; }
    } else if (!strcmp(k, "shield")) {
        int x = atoi(v) ? 1 : 0;
        if (x != c.shield) { c.shield = x; dirty = 1; }
        /* 立即生效：开 → 写判据，关 → 还原 ROM 原值 */
        if (x) shield_apply(&c); else shield_off();
    } else if (!strcmp(k, "stable_sec")) {
        long x = atol(v);
        if (x >= 60 && x <= 86400 && x != c.stable_sec) { c.stable_sec = x; dirty = 1; }
    } else if (!strcmp(k, "target")) {
        /* 多包配置已废除（FIXED_TARGET 固定）。老面板还会下发这条，
         * 明确回日志而不是静默忽略，免得用户以为设置生效了。 */
        rlog("UI reject target(fixed) v=%s", v);
        return 0;
    } else if (!strcmp(k, "watch_install")) {
        int x = atoi(v) ? 1 : 0;
        if (x != c.watch_install) { c.watch_install = x; dirty = 1; }
    } else if (!strcmp(k, "ebpf")) {
        int x = atoi(v) ? 1 : 0;
        if (x != c.ebpf) { c.ebpf = x; dirty = 1; ebpf_changed = 1; }
    } else {
        rlog("UI reject key=%s", k);
        return 0;
    }

    if (!dirty) return 1;

    /*
     * 必须整份走 cfg_save 回写，不能在这里手写 body。
     * 手写版只写了 12 个键，漏掉 user_apk / watch_install / crash_window_sec /
     * crash_threshold / a_window_sec / a_circ_thr —— 这些键会变成「文件里不存在」，
     * 下次 cfg_load 回落默认值：用户设的兜底包被清空、A 段调过的窗口被打回 180/3。
     * 结果是 UI 每改一个开关就静默丢一次配置，而且不会有任何报错。
     */
    if (cfg_save(&c) != 0) {
        rlog("UI write failed key=%s", k);
        return 0;
    }
    rlog("UI set %s=%s", k, v);
    if (ebpf_changed) rescue_ebpf_apply(c.ebpf);
    return 1;
}

/* 供 bridge 主循环每秒调用：读 UI 指令 → 应用 → 立刻回写状态 */
void rescue_poll_cmd(void)
{
    char buf[1024], *p;
    FILE *f;
    size_t n;
    int hit = 0;
    /* 开机后自愈一次：DE 阶段 /data/user/0 还读不到，用退避重试一直等到
     * CE 解锁后成功一次，之后永久停手。
     *
     * 不做周期巡检的理由：FUSE(/sdcard) 的 stat 每次要跨进程往返 ~5ms，
     * 60s 一轮 4 次 stat 一天就是 5760 次白花——而 99.99% 的时间它只是在
     * 确认「一切正常」。周期巡检能覆盖的场景其实另有两处兜底：
     *   ① 目录被删/被清理工具重建 → 下次写状态时 ui_dir_ok() 失败即修；
     *   ② App 卸载重装换 uid → 卸载会连 Android/data/<pkg> 一起删，
     *      同样落到 ①。
     * 而「App 能写进 camd_cmd」本身就证明属主是对的，所以这里也不必
     * 在指令分支再探测，省掉那一次 stat。 */
    static const int ens_backoff[] = {1, 2, 4, 8, 15, 30, 60};
    static int ens_done = 0, ens_try = 0;
    static time_t ens_next = 0;
    time_t now = time(NULL);

    if (!ens_done && now >= ens_next) {
        if (ui_ensure_dir() == 0) {
            ens_done = 1;          /* 成功一次就再也不主动看 */
        } else {
            if (ens_try < (int) (sizeof(ens_backoff) / sizeof(ens_backoff[0])) - 1)
                ens_try++;
            ens_next = now + ens_backoff[ens_try];
        }
    }

    /* 指令来源双通道：
     *   ① CAM_DIR/cmd（ext4，App 有 root 时用 su 写）—— 快通道，靠 inotify 唤醒
     *   ② UI_CMD（/sdcard FUSE，App 无 root 时写）—— 慢通道，主循环每秒轮询
     * 先查快通道：两条同时读会导致同一条指令被执行两次（开关类指令幂等，
     * 但 uninstall/clear 这类会出问题）。 */
    {
        char fast_cmd[320];
        cam_path("cmd", fast_cmd, sizeof(fast_cmd));
        f = fopen(fast_cmd, "r");
        if (f) {
            n = fread(buf, 1, sizeof(buf) - 1, f);
            fclose(f);
            buf[n] = 0;
            unlink(fast_cmd);       /* 先删：同一条指令只会被执行一次 */
        } else {
            f = fopen(UI_CMD, "r");
            if (!f) return;
            n = fread(buf, 1, sizeof(buf) - 1, f);
            fclose(f);
            buf[n] = 0;
            unlink(UI_CMD);
        }
    }

    for (p = buf; p && *p; ) {
        char *nl = strchr(p, '\n');
        char *eq;
        char k[64], v[256];
        if (nl) *nl = 0;
        eq = strchr(p, '=');
        if (eq) {
            *eq = 0;
            snprintf(k, sizeof(k), "%s", trim(p));
            snprintf(v, sizeof(v), "%s", trim(eq + 1));
            if (k[0]) {
                ui_apply(k, v);
                hit = 1;
            }
        }
        if (!nl) break;
        p = nl + 1;
    }

    if (hit) ui_dump(1);        /* 让 UI 立刻看到结果，不等节流 */
}

/*
 * 首次启动补齐 config。
 *
 * 背景：cfg_load() 只读不建 —— 文件不存在就直接用内置默认值静默运行，
 * 于是新装的机器上 /data/adb/camellia/config 根本不存在，用户想调
 * auto_fix 时无处可改，还会误以为 camd 没装好。
 *
 * 这里在缺失时生成一份「带注释的完整模板」；文件已存在则原样返回，
 * 绝不覆盖用户配置。auto_fix 首次写 1（装上是来救的），只想观察的改 0。
 */
void cfg_ensure(void)
{
    char path[320];
    struct stat st;
    cfg_t c;

    cam_path("config", path, sizeof(path));
    if (stat(path, &st) == 0) return;      /* 已有配置：一律只读不改 */

    /* 首次运行：把完整模板连同注释一起落盘，用户打开就能看到所有可调项。
     * 注意 mkdir_p2 是必须的 —— camd 可能由 App 以 root 拉起，
     * 此时目录还不存在，且 CAM_DIR 被显式指定时 cfgdir() 不会兜底。 */
    mkdir_p2(cam_root(), 0700);
    cfg_defaults(&c);
    c.auto_fix = 1;                        /* 装上就是来救的；只想观察请改 0 */
    if (cfg_save(&c) != 0) return;         /* 写不进去也不致命，内存默认值照样工作 */

    rlog("CONFIG_INIT path=%s auto_fix=1 (首次生成)", path);
}

/* 供 bridge 主循环调用：按节流刷新状态快照 */
void rescue_write_status(void)
{
    ui_dump(0);
}

/*
 * App 刚连上时调用：绕过 5s 节流与心跳降级，立刻做一次完整采集并广播。
 * 面板打开瞬间必须看到真实状态，而不是上一次心跳留下的业务指纹。
 */
void rescue_write_status_force(void)
{
    ui_dump(1);
}
