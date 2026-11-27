/* homeprobe.c v30 — LSPosed native_init 非阻塞探针（内嵌 ELF 动态定位版）
 * v30: 固定 APK_ELF_OFF 改为运行时发现（默认值快路径 → 文件 pread → 映射段扫 \x7fELF），
 *      配合 v29 的路径/过滤/memfd 参数化，同一份 so 可部署到任意目标 App。
 * v29: 去硬编码 —— 输出路径 / maps 过滤 / memfd 名全部按运行时包名 g_pkg 构造，
 *      同一份 so 只需勾 LSPosed 作用域即可部署到任意目标 App。
 *
 * 背景（已确认，不再验证）：
 *   com.miui.home 的 libapp.so 不是独立映射，而是以文件偏移 0x1a1c000
 *   内嵌在 base.apk 中，其 PT_LOAD 段以 base.apk 路径出现在 /proc/self/maps。
 *
 * v2 问题：
 *   probe() 在 JNI_OnLoad / constructor 中同步执行，重试 100×100ms（最长 10s），
 *   阻塞桌面主线程 → 黑屏/ANR 风险。
 *
 * v4 修正（对齐 HyperCeiler 不 ANR 模式 + 标准入口协议）：
 *   - native_init 采用标准协议签名：LibraryLoaded native_init(const ApiEntries*)，
 *     返回 nullptr（不注册库加载回调）；ctor 兜底。
 *   - pid 变化检测重置：OS4 桌面由 spawner fork，父进程跑过 probe 会留下 g_done，
 *     子进程线程不存活但标志被继承 → 必须以 pid 变化重置（否则桌面进程永不探测）。
 *   - 绝不在持 linker 锁的线程上做重活：只有 spawn，IO 全在子线程。
 *
 * v3 修正（非阻塞）：
 *   - maps 扫描 + ELF 解析 + dump 主流程移入 SYS_clone 子线程；
 *     用 CLONE_VM 共享地址空间（必须：libapp.so 可能在 spawn 之后才加载，
 *     独立地址空间的 fork 子进程会永远看不到）；
 *     线程栈静态分配 128KB，16 字节对齐。
 *   - JNI_OnLoad / native_init 仅 spawn 后立即返回，绝不阻塞桌面主线程。
 *   - 重试上限 40 × 100ms = 4s（3-5s 区间）。
 *   - 子线程结束用 exit(93)（per-thread exit；exit_group 是 94，勿混）。
 *   - 编译：gcc -shared -fPIC -nostdlib -O2 -fno-builtin -ffreestanding \
 *           -Wl,--version-script=exports.map
 *
 * 约束：纯 syscall + nostdlib，零 NEEDED；全程只读，禁止 mprotect / 写目标内存。
 */

typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;

#define AT_FDCWD (-100)
#define O_RDONLY 0
#define O_WRONLY 1
#define O_CREAT 0100
#define O_TRUNC 01000

/* v30: 内嵌 ELF 偏移改为运行时发现。此值仅作「首选尝试」，命中则零开销；
 * 未命中时在目标 APK 的映射段内扫描 \x7fELF 自动定位，从而支持任意目标 App。 */
#define APK_ELF_OFF_DEFAULT 0x1a1c000UL
#define TARGET_VADDR 0x19e737cUL
#define EXPECT_APK_OFF 0x340337CUL
#define PATCH_LEN 12
#define DUMP_LEN 16
#define MAPS_SZ (1024UL * 1024UL)
#define MAX_MAPS 128
#define MAX_PATHS 8       /* v29: 4 → 8，换目标 App 后可能命中更多 APK 段 */
#define APK_PATH_MAX 256
#define OUT_SZ 4096

/* v29: 输出路径不再写死包名 —— 由 build_paths() 用运行时包名 g_pkg 构造，
 * 使同一份 so 可直接部署到任意目标 App（LSPosed 勾选作用域即可）。
 * 三处候选：外部数据目录（诊断脚本易读）→ 私有目录（一定可写）→ /tmp（带包名防冲突） */

/* ---- v5: 主返回点 inline hook（_getAllSystemShortcutMenuItem）----
 * 0x10ac2e0: mov x0, x2   <- x2 = 返回的 List 对象
 * 0x10ac2e4: mov x15, x29
 * 0x10ac2e8: ldp x29, x30, [x15], #16
 * 0x10ac2ec: ret
 * 覆盖前 3 条（12 字节）为 adrp/add/br，trampoline 执行原 3 条后 ret。
 */
#define HOOK_ENTRY_OFF   0x584UL   /* 0x10ac2e0 - 0x10abd5c */
#define MENU_REC_MAGIC   0x4D454E55UL
#define MENU_MAX_ITEMS   8
/* v29: 原 MENU_OUT_* 宏无任何引用（死代码），已删除 */


/* 重试：40 × 100ms = 4s（3-5s 区间，避免桌面主线程长时间不可用） */
#define MAX_ATTEMPTS 200   /* v34b: 40→200（4s→20s）。桌面冷启动时 Flutter 引擎 /
                            * libapp.so 的 r-xp 段常在注入后 4s 之后才建立，旧窗口
                            * 会让探针在"还没加载"时提前放弃，现象就是「注入了但没 dump」。 */
#define RETRY_MS 100

/* aarch64 clone flags */
#define CLONE_VM       0x00000100UL
#define CLONE_FS       0x00000200UL
#define CLONE_FILES    0x00000400UL
#define CLONE_SIGHAND  0x00000800UL
#define CLONE_THREAD   0x00010000UL
#define SIGCHLD        17
#define THREAD_FLAGS   (CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND | CLONE_THREAD | SIGCHLD)

#define SYS_CLONE 220
#define SYS_EXIT  93

#define WORKER_STACK_SZ (128UL * 1024UL)

struct mapent {
    u64 start;
    u64 end;
    u64 off;
    char perm[5];
};

static struct mapent g_me[MAX_MAPS] __attribute__((aligned(16)));
static int g_nme;
static char g_paths[MAX_PATHS][APK_PATH_MAX] __attribute__((aligned(16)));
static int g_npath;
static int g_okpath;
static u64 g_elf_off = APK_ELF_OFF_DEFAULT;  /* v30: 当前目标 APK 内嵌 ELF 偏移 */
static int g_elf_src;        /* v30: 0=默认值命中 1=文件pread命中 2=动态扫描命中 */
static int g_nelf_scan;      /* v30: 动态扫描发现的合法 ELF 个数 */
static char g_pkg[96] __attribute__((aligned(16)));
static char g_tgt[APK_PATH_MAX] __attribute__((aligned(16)));  /* v34: 白名单命中的目标 APK 路径 */
/* v29: 输出路径与锚定页 memfd 名，全部按运行时包名构造 */
static char g_out_primary[384] __attribute__((aligned(16)));
static char g_out_private[384] __attribute__((aligned(16)));
static char g_out_fallback[384] __attribute__((aligned(16)));
static char g_out_media[384] __attribute__((aligned(16)));
static char g_pg_name[160] __attribute__((aligned(16)));
static void build_paths(void);
static unsigned char g_maps[MAPS_SZ] __attribute__((aligned(16)));
static char g_out[OUT_SZ] __attribute__((aligned(16)));
static int g_oi;
static int g_done;
static int g_started;
static int g_pid;

/* 子线程栈：128KB，16 字节对齐 */
static unsigned char g_stack[WORKER_STACK_SZ] __attribute__((aligned(16)));

static long s_sys(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
                     : "memory");
    return x0;
}

static long s_open(const char *p, int flags, int mode) {
    return s_sys(56, AT_FDCWD, (long)p, (long)flags, (long)mode, 0, 0);
}
static long s_mkdir(const char *p, int mode) {
    return s_sys(34, AT_FDCWD, (long)p, (long)mode, 0, 0, 0);   /* mkdirat */
}
static long s_getpid(void);   /* 定义在文件后部，此处前向声明供 dump 使用 */

/* v33: boot_id 前 8 个字符（UUID 的 hex 前缀，足以区分两次开机）。
 * 系统重启后旧信标立即作废，免得「上次开机写的 OK」被当成本次的有效状态。 */
static void read_boot_id(char *out) {
    long fd, n;
    int i;
    for (i = 0; i < 8; i++) out[i] = '0';
    out[8] = 0;
    fd = s_open("/proc/sys/kernel/random/boot_id", 0, 0);
    if (fd < 0) return;
    n = s_sys(63, fd, (long)out, 8, 0, 0, 0);
    s_sys(57, fd, 0, 0, 0, 0, 0);
    if (n < 8) {
        for (i = 0; i < 8; i++) out[i] = '0';
        out[8] = 0;
    }
}
static long s_read(int fd, void *buf, long n) { return s_sys(63, (long)fd, (long)buf, n, 0, 0, 0); }
static long s_write(int fd, const void *buf, long n) { return s_sys(64, (long)fd, (long)buf, n, 0, 0, 0); }
static long s_pread(int fd, void *buf, long n, long off) { return s_sys(67, (long)fd, (long)buf, n, off, 0, 0); }
static long s_close(int fd) { return s_sys(57, (long)fd, 0, 0, 0, 0, 0); }
static long s_kill(int pid, int sig) { return s_sys(129, (long)pid, (long)sig, 0, 0, 0, 0); }

/* __NR_fchmod = 52 (aarch64)。
 * 注意：open(O_CREAT) 的 mode 只对「新建」文件生效，文件已存在时被忽略。
 * 历史遗留的信标文件可能是 0600，会让 system_server 因 DAC 读不到——这里显式补一次。 */
static long s_fchmod(int fd, int mode) { return s_sys(52, (long)fd, (long)mode, 0, 0, 0, 0); }

static void s_sleep_ms(long ms) {
    struct ts_t {
        long sec;
        long nsec;
    } ts;
    ts.sec = ms / 1000;
    ts.nsec = (ms % 1000) * 1000000L;
    s_sys(101, (long)&ts, 0, 0, 0, 0, 0); /* nanosleep */
}

static void s_memcpy(void *dst, const void *src, u64 n) {
    u8 *d = (u8 *)dst;
    const u8 *s = (const u8 *)src;
    u64 i;
    for (i = 0; i < n; i++) d[i] = s[i];
}

static int s_len(const char *s) {
    int n = 0;
    while (s[n]) n++;
    return n;
}
static int s_eq(const char *a, const char *b) {
    int i = 0;
    while (a[i] && b[i]) {
        if (a[i] != b[i]) return 0;
        i++;
    }
    return a[i] == b[i];
}
static int s_contains(const char *h, const char *n) {
    int nl = s_len(n);
    int i, j;
    if (!nl) return 1;
    for (i = 0; h[i]; i++) {
        j = 0;
        while (j < nl && h[i + j] == n[j]) j++;
        if (j == nl) return 1;
    }
    return 0;
}
/* v29: 字符串拼接（本 so 为 nostdlib，无 strcat） */
static void s_cpy(char *d, const char *s) {
    int j = 0;
    while (s[j]) {
        d[j] = s[j];
        j++;
    }
    d[j] = 0;
}
static void s_cat(char *d, const char *s) {
    int i = s_len(d), j = 0;
    while (s[j]) d[i++] = s[j++];
    d[i] = 0;
}
static int hexv(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return 0;
}
static u16 rd16(const u8 *p) { return (u16)((u16)p[0] | ((u16)p[1] << 8)); }
static u32 rd32(const u8 *p) {
    return (u32)p[0] | ((u32)p[1] << 8) | ((u32)p[2] << 16) | ((u32)p[3] << 24);
}
static u64 rd64(const u8 *p) { return (u64)rd32(p) | ((u64)rd32(p + 4) << 32); }
static int elf_magic(const u8 *p) {
    return p[0] == 0x7f && p[1] == 0x45 && p[2] == 0x4c && p[3] == 0x46;
}

static void o_ch(char c) {
    if (g_oi < OUT_SZ - 1) g_out[g_oi++] = c;
}
static void o_str(const char *s) {
    while (*s) o_ch(*s++);
}
static void o_hex64(u64 v) {
    static const char d[] = "0123456789abcdef";
    int i;
    o_str("0x");
    for (i = 15; i >= 0; i--) o_ch(d[(v >> (i * 4)) & 0xF]);
}
static void o_dec(u64 v) {
    char b[24];
    int n = 0;
    if (!v) {
        o_ch('0');
        return;
    }
    while (v) {
        b[n++] = (char)('0' + (v % 10));
        v /= 10;
    }
    while (n > 0) o_ch(b[--n]);
}
static void o_bytes(const u8 *p, int n) {
    static const char d[] = "0123456789abcdef";
    int i;
    for (i = 0; i < n; i++) {
        o_ch(d[(p[i] >> 4) & 0xF]);
        o_ch(d[p[i] & 0xF]);
    }
}

static void write_all(int fd, const char *buf, int len) {
    int off = 0;
    while (off < len) {
        long r = s_write(fd, buf + off, (long)(len - off));
        if (r <= 0) break;
        off += (int)r;
    }
}

static void flush_one(const char *p) {
    int fd = (int)s_open(p, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        write_all(fd, g_out, g_oi);
        s_fchmod(fd, 0644);   /* 旧文件可能是 0600；显式补权限，供 system_server 跨进程读取 */
        s_close(fd);
    }
}

static void o_flush(void) {
    g_out[g_oi] = 0;
    /* v21: 三条路径逐一尝试，不再"首个成功就返回"。
     * 部分设备的外部数据目录不存在，私有目录 /data/data/<pkg>/files
     * 一定可写，作为主兜底（诊断脚本用 root 读取）。
     * v29: 路径由 build_paths() 按运行时包名生成，不再写死。 */
    if (!g_out_primary[0]) build_paths();   /* 惰性构造：任何调用路径都不会写死包名 */
    flush_one(g_out_primary);
    flush_one(g_out_private);
    flush_one(g_out_media);   /* 公共目录：不受 Android/data 的 FUSE 属主隔离 */
    flush_one(g_out_fallback);
}

static void read_pkg(void) {
    int fd = (int)s_open("/proc/self/cmdline", O_RDONLY, 0);
    long n;
    int i;
    if (fd < 0) return;
    n = s_read(fd, g_pkg, (long)(sizeof(g_pkg) - 1));
    s_close(fd);
    if (n <= 0) return;
    g_pkg[n] = 0;
    for (i = 0; i < n; i++) {
        if (g_pkg[i] == ':') g_pkg[i] = 0; /* 去子进程后缀 */
        if (g_pkg[i] == 0) break;
    }
}

/* v29: 按运行时包名构造所有输出路径与 memfd 名。
 * 换目标 App 只改 LSPosed 作用域，无需改一行代码、无需重编译。 */
static void build_paths(void) {
    const char *pkg = g_pkg[0] ? g_pkg : "unknown.pkg";

    s_cpy(g_out_primary, "/sdcard/Android/data/");
    s_cat(g_out_primary, pkg);
    s_cat(g_out_primary, "/files/cam_dump.txt");

    s_cpy(g_out_private, "/data/data/");
    s_cat(g_out_private, pkg);
    s_cat(g_out_private, "/files/cam_dump.txt");

    /* v33: 状态信标改投 Android/media/<pkg>/cam_a_state。
     * 选它的唯一理由：桌面进程能写、system_server 能读，是两者唯一的交集。
     *   Android/data/<pkg>  -> FUSE 按包属主隔离，system_server 读不到（实测 MISS）
     *   Download/           -> 目录属主 media_rw 组、桌面不在组内，写不进去（实测 MISS）
     *   Android/media/<pkg> -> 目录属组 media_rw，桌面经 FUSE 以 MediaProvider 身份
     *                         落盘；system_server 在 media_rw(1023) 组内，直接读。
     * v33 起不再有心跳：本文件只在补丁完成时写一次，读侧一锤定音。 */
    s_cpy(g_out_media, "/storage/emulated/0/Android/media/");
    s_cat(g_out_media, pkg);
    s_cat(g_out_media, "/cam_a_state");
    {
        char d[384];
        s_cpy(d, "/storage/emulated/0/Android/media/");
        s_cat(d, pkg);
        s_mkdir(d, 0771);   /* 已存在返回 EEXIST，忽略即可 */
    }

    s_cpy(g_out_fallback, "/data/local/tmp/cam_dump_");
    s_cat(g_out_fallback, pkg);
    s_cat(g_out_fallback, ".txt");

    s_cpy(g_pg_name, "cam_pg_");
    s_cat(g_pg_name, pkg);   /* 最长 7+95=102 < memfd 名上限 249，安全 */
}

static void add_path(const char *p) {
    int i;
    for (i = 0; i < g_npath; i++) {
        if (s_eq(g_paths[i], p)) return;
    }
    if (g_npath < MAX_PATHS) {
        int j = 0;
        while (p[j] && j < APK_PATH_MAX - 1) {
            g_paths[g_npath][j] = p[j];
            j++;
        }
        g_paths[g_npath][j] = 0;
        g_npath++;
    }
}

static void handle_line(u64 ls, u64 le) {
    u64 p = ls;
    u64 start = 0, end = 0, off = 0;
    char perm[5];
    char path[APK_PATH_MAX];
    int i, k, cut;

    while (p < le && g_maps[p] != '-') {
        start = (start << 4) | (u64)hexv((char)g_maps[p]);
        p++;
    }
    if (p >= le) return;
    p++;
    while (p < le && g_maps[p] != ' ') {
        end = (end << 4) | (u64)hexv((char)g_maps[p]);
        p++;
    }
    while (p < le && g_maps[p] == ' ') p++;
    k = 0;
    while (p < le && k < 4 && g_maps[p] != ' ') {
        perm[k++] = (char)g_maps[p];
        p++;
    }
    perm[k] = 0;
    while (p < le && g_maps[p] == ' ') p++;
    while (p < le && g_maps[p] != ' ') {
        off = (off << 4) | (u64)hexv((char)g_maps[p]);
        p++;
    }
    while (p < le && g_maps[p] == ' ') p++;
    while (p < le && g_maps[p] != ' ') p++;
    while (p < le && g_maps[p] == ' ') p++;
    while (p < le && g_maps[p] != ' ') p++;
    while (p < le && g_maps[p] == ' ') p++;
    if (p >= le) return;

    i = 0;
    while (p < le && i < APK_PATH_MAX - 1) path[i++] = (char)g_maps[p++];
    path[i] = 0;

    /* v21 关键修正：放宽路径过滤。
     * OS 系统分区安装的 OEM APK 名常为 MiuiHome.apk 这类
     * (/product/priv-app/MiuiHome/MiuiHome.apk)，既不含 "base.apk" 也不含小写包名，
     * 旧版两道过滤会让整个进程扫不到任何候选段 → cand=0 → 永不打补丁。
     * /proc/self/maps 只包含本进程自身映射的文件，故包名校验非必需；
     * 真正的把关在后面：APK 内嵌 ELF 解析 + 254/256 掩码唯一命中。 */
    /* v22 修复自匹配漏洞（v21 放宽过滤时引入）：
     * 探针自身 APK（/data/app/.../com.probe.homenative-.../base.apk）同样在
     * 本进程内有映射，其 libhomeprobe.so 的 r-x 段内嵌 kFpVal 掩码基准数组，
     * 该数组逐 word 等于指纹本身（kFpVal[0..2] 正是 12B 原序言），扫描时会
     * 100% 自匹配得满分 best=256、second 极低，于是在 Flutter 的 libapp.so
     * 可执行段建立之前就"抢先命中"，把补丁写到探针自己的常量表上。
     * 实测 addrs=0x70b6aa58d8 = 探针 APK 的 file 0x3000 段 + 段内 0x8d8，
     * readback 当然"正确"，但桌面代码从未被修改 → 长按菜单毫无变化。
     * 因此必须排除自身 APK，并且只接受目标桌面 APK 段。 */
    if (!s_contains(path, ".apk")) return;
    /* v29: 目标 App 判定改为「排除自身 + 指纹把关」，不再写死 com.miui.home。
     *
     * 1) 必须排除探针自身模块 APK：其内嵌 kFpVal 常量表逐 word 等于指纹，
     *    会以 best=256 抢先自匹配（v22 实测踩过，补丁打到自己的常量表上）。
     *    本轮模块 applicationId = com.hyper.CamelliaLab；后两个为历史包名，保留兼容。
     * 2) 不再要求路径含目标包名（原因见上 v21 注释，OEM APK 常不含小写包名）。
     * 3) 真正的把关：APK 内嵌 ELF 解析 + 254/256 掩码唯一命中。 */
    /* v34 关键修复（本地实测根因）：上一版的自匹配排除名单只写了
     * "com.hyper.CamelliaLab"，而本模块 applicationId 实为 com.hyper.Camellia（无 Lab），
     * 排除完全失效 → v22 的自匹配漏洞复发。
     * 实测证据：cam_dump 的 addrs=0x799e89c6a8 落在
     *   /memfd:cam_pg_com.miui.home (deleted) 匿名页，
     * 紧邻 /data/app/~~.../com.hyper.Camellia.../base.apk 的 0xc54000 r-xp 段，
     * 即补丁被写进探针自己 APK 内 libhomeprobe.so 的 kFpVal 常量表（fp=256 满分命中），
     * 桌面 libapp.so 一次都没被改 → 表现就是 "PATCH_DONE/readback 正确，但长按菜单毫无变化"。
     * 改为白名单：只接受路径含目标包名 com.miui.home 的 APK
     * （实测桌面 APK 路径 /data/app/~~.../com.miui.home-.../base.apk 含小写包名），
     * 再做 APK 内嵌 ELF 解析 + 254/256 掩码唯一命中双把关。
     * 离线验证：home.apk 内 libapp.so 指纹唯一命中 file_off=0x19e737c (256/256)，
     * libapp_launcher.so 0 命中。 */
    if (!s_contains(path, "com.miui.home")) return;
    s_cpy(g_tgt, path);  /* v34: 记录白名单命中的目标 APK 路径，供 cam_dump 验证 */
    if (s_contains(path, "com.hyper.Camellia")) return;
    if (s_contains(path, "probe.homenative")) return;
    if (s_contains(path, "mihome-native")) return;

    if (g_nme < MAX_MAPS) {
        g_me[g_nme].start = start;
        g_me[g_nme].end = end;
        g_me[g_nme].off = off;
        for (k = 0; k < 5; k++) g_me[g_nme].perm[k] = perm[k];
        g_nme++;
    }

    cut = i;
    {
        const char *suf = " (deleted)";
        int sl = s_len(suf);
        if (cut > sl) {
            int match = 1;
            for (k = 0; k < sl; k++) {
                if (path[cut - sl + k] != suf[k]) {
                    match = 0;
                    break;
                }
            }
            if (match) cut -= sl;
        }
    }
    if (cut > APK_PATH_MAX - 1) cut = APK_PATH_MAX - 1;
    path[cut] = 0;
    add_path(path);
}

static int scan_maps_once(void) {
    u64 total = 0;
    u64 i;
    int fd = (int)s_open("/proc/self/maps", O_RDONLY, 0);
    if (fd < 0) return -1;
    while (total < MAPS_SZ - 1) {
        long r = s_read(fd, (void *)(g_maps + total), (long)(MAPS_SZ - 1 - total));
        if (r <= 0) break;
        total += (u64)r;
    }
    s_close(fd);
    g_maps[total] = 0;
    g_nme = 0;
    g_npath = 0;

    i = 0;
    while (i < total) {
        u64 lstart = i;
        u64 lend;
        while (i < total && g_maps[i] != '\n') i++;
        lend = i;
        if (i < total) i++;
        if (lend > lstart) handle_line(lstart, lend);
    }
    return g_nme;
}

static int apk_read(u64 apk_off, u8 *dst, u64 len) {
    int i;
    for (i = 0; i < g_nme; i++) {
        u64 ms = g_me[i].start;
        u64 mo = g_me[i].off;
        u64 msz = g_me[i].end - ms;
        if (apk_off >= mo && (apk_off - mo) + len <= msz) {
            s_memcpy(dst, (const u8 *)(ms + (apk_off - mo)), len);
            return 1;
        }
    }
    for (i = -1; i < g_npath; i++) {
        int pi = (i < 0) ? g_okpath : i;
        int fd;
        long r;
        if (pi < 0 || pi >= g_npath) continue;
        if (i >= 0 && pi == g_okpath) continue;
        fd = (int)s_open(g_paths[pi], O_RDONLY, 0);
        if (fd < 0) continue;
        r = s_pread(fd, dst, (long)len, (long)apk_off);
        s_close(fd);
        if (r == (long)len) return 1;
    }
    return 0;
}

/* v30: 判定一个 ELF64/AArch64 ET_DYN 头是否「像我们要找的内嵌 so」 */
static int elf_hdr_plausible(const u8 *h) {
    u16 et, em;
    if (!elf_magic(h)) return 0;
    if (h[4] != 2) return 0;                 /* ELFCLASS64 */
    if (h[5] != 1) return 0;                 /* ELFDATA2LSB */
    et = (u16)((u16)h[16] | ((u16)h[17] << 8));
    em = (u16)((u16)h[18] | ((u16)h[19] << 8));
    if (et != 3) return 0;                   /* ET_DYN（共享库） */
    if (em != 183) return 0;                 /* EM_AARCH64 */
    return 1;
}

/* v30: 在已映射的目标 APK 段内扫描 \x7fELF，动态确定内嵌 so 的文件偏移。
 * 只在默认偏移未命中时调用（MiHome 仍走零开销快路径）。
 * 扫描预算 256MB，4 字节步进，实测 arm64 上 <200ms，且在探针子线程执行。 */
#define ELF_SCAN_BUDGET (256UL * 1024UL * 1024UL)

static int find_elf_off_in_maps(void) {
    int i;
    u64 budget = ELF_SCAN_BUDGET;
    g_nelf_scan = 0;
    for (i = 0; i < g_nme; i++) {
        u64 p = g_me[i].start;
        u64 e = g_me[i].end;
        if (e <= p) continue;
        if (e - p > budget) e = p + budget;
        budget -= (e - p);
        for (; p + 64 <= e; p += 4) {
            const u8 *q = (const u8 *)(void *)p;
            if (q[0] != 0x7f || q[1] != 'E' || q[2] != 'L' || q[3] != 'F') continue;
            if (!elf_hdr_plausible(q)) continue;
            g_nelf_scan++;
            if (g_nelf_scan == 1) {
                g_elf_off = g_me[i].off + (p - g_me[i].start);
                g_elf_src = 2;
            }
        }
        if (budget == 0) break;
    }
    return (g_nelf_scan > 0);
}

static int read_elf_hdr(u8 *hdr) {
    int i;
    /* 1) 用当前偏移（默认值 / 上次成功值）在映射段直读 */
    for (i = 0; i < g_nme; i++) {
        u64 ms = g_me[i].start;
        u64 mo = g_me[i].off;
        u64 msz = g_me[i].end - ms;
        if (g_elf_off >= mo && (g_elf_off - mo) + 64 <= msz) {
            s_memcpy(hdr, (const u8 *)(ms + (g_elf_off - mo)), 64);
            if (elf_hdr_plausible(hdr)) {
                g_okpath = -1;
                return 1;
            }
        }
    }
    /* 2) 退化为文件 pread */
    for (i = 0; i < g_npath; i++) {
        int fd = (int)s_open(g_paths[i], O_RDONLY, 0);
        long r;
        if (fd < 0) continue;
        r = s_pread(fd, hdr, 64, (long)g_elf_off);
        s_close(fd);
        if (r == 64 && elf_hdr_plausible(hdr)) {
            g_okpath = i;
            g_elf_src = 1;
            return 1;
        }
    }
    /* 3) 动态扫描（任意目标 App 的关键路径） */
    if (find_elf_off_in_maps()) {
        for (i = 0; i < g_nme; i++) {
            u64 ms = g_me[i].start, mo = g_me[i].off, msz = g_me[i].end - ms;
            if (g_elf_off >= mo && (g_elf_off - mo) + 64 <= msz) {
                s_memcpy(hdr, (const u8 *)(ms + (g_elf_off - mo)), 64);
                if (elf_hdr_plausible(hdr)) { g_okpath = -1; return 1; }
            }
        }
    }
    return 0;
}

/* 主流程（在子线程中执行，不阻塞桌面主线程） */
static void install_menu_hook(u64 fn_runtime);

/* ---- v7 patch 状态 ---- */
static int g_cand, g_patched, g_mperr, g_pn, g_rounds;
static int g_skip;         /* v31 (Camellia): 1=非目标进程，已按白名单跳过 */
static u64 g_paddr[4];
static int patch_target(u64 target_apk_off);
static long s_mprotect(u64 addr, u64 len, long prot);

/* ValidSmallWindowShortcutMenu.isValid 入口 0x19e737c 的原序言 / 补丁 */
static const u8 kOrig[PATCH_LEN] = {0xfd,0x79,0xbf,0xa9, 0xfd,0x03,0x0f,0xaa, 0xef,0x01,0x01,0xd1};
static const u8 kNew[PATCH_LEN]  = {0xc0,0x82,0x00,0x91, 0xc0,0x03,0x5f,0xd6, 0x1f,0x20,0x03,0xd5};

/* ================= v19: 跨版本指纹定位 =================
 * 旧版依赖固定常量（APK_ELF_OFF 0x1a1c000 / TARGET_VADDR 0x19e737c /
 * EXPECT_APK_OFF 0x340337C），仅在 8.01.02.6325 成立，换版本即失效。
 *
 * 跨版本方法（49 个 8.01 样本实测）：
 *   1) Dart AOT 函数序言 12B 在全部 8.01 版本 100% 一致：
 *        stp x29,x30,[x15,#-16]!  (0xa9bf79fd)
 *        mov x29,x15              (0xaa0f03fd)
 *        sub x15,x15,#0x40        (0xd10101ef)
 *      但整库约 2000 处函数复用同序言，不能唯一定位；
 *   2) 再按 isValid 前 256 条指令模板打分（相同指令条数），
 *      实测各版本最高分 0.664~0.785、次高 0.074~0.090，区分度 8~9 倍；
 *   3) 判定 best >= TMPL_MIN(115/256) 且 best >= 3*second，
 *      否则安全降级：不改任何内存，只写日志。
 */
#define TMPL_N   256
#define TMPL_MIN 115

/* 序言指纹 */
#define PRO_LO 0xaa0f03fda9bf79fdULL   /* stp x29,x30,[x15,#-16]! + mov x29,x15 */
#define PRO_HI 0xd10101efU             /* sub x15,x15,#0x40 */

static const u32 kTmpl[TMPL_N] = {
    0xa9bf79fdU, 0xaa0f03fdU, 0xd10101efU, 0xf81f83b6U, 0xf81f03a1U, 0xaa0203f0U,
    0xaa0103e2U, 0xaa1003e1U, 0xf81e83a1U, 0xf81e03a3U, 0xf81d83a5U, 0xf944db60U,
    0x97f7f50cU, 0xf85e83a1U, 0xf85ff020U, 0xd34c7c00U, 0xf81d03a0U, 0xf11ecc1fU,
    0x54000261U, 0xf9403f40U, 0xf951c800U, 0xf9402370U, 0x6b10001fU, 0x54000061U,
    0xf9403362U, 0x94022ef1U, 0x9141df61U, 0xf9410021U, 0xaa1603e2U, 0xf81d83a0U,
    0x94023367U, 0xf85d83a1U, 0xaa0003e2U, 0xf9403b64U, 0x97c03d33U, 0x9100c2c0U,
    0x17f7f4e9U, 0xf9403f42U, 0xf952fc42U, 0x6b16005fU, 0x540001a1U, 0xf9403f40U,
    0xf94df400U, 0xf9402370U, 0x6b10001fU, 0x54000061U, 0xf97f3b62U, 0x94022edbU,
    0xf97f3f70U, 0xf90001f0U, 0xf9402b64U, 0x97c0fa5cU, 0x14000002U, 0xaa0203e0U,
    0xb84db001U, 0x8b1c8021U, 0x372001c1U, 0xf9403f40U, 0xf9522c00U, 0xf9402370U,
    0x6b10001fU, 0x54000061U, 0xf97ec762U, 0x94022ecbU, 0xb8473002U, 0x8b1c8042U,
    0xaa0203e1U, 0xf81d83a2U, 0x97f500bbU, 0x37200260U, 0xf9403f40U, 0xf951c800U,
    0xf9402370U, 0x6b10001fU, 0x54000061U, 0xf9403362U, 0x94022ebeU, 0x9141df61U,
    0xf9410421U, 0xaa1603e2U, 0xf81c83a0U, 0x94023334U, 0xf85c83a1U, 0xaa0003e2U,
    0xf9403b64U, 0x97c03d00U, 0x9100c2c0U, 0x17f7f4b6U, 0xf85e83a1U, 0xf85ff020U,
    0xd34c7c00U, 0xd12ec81eU, 0xf87e7abeU, 0xd63f03c0U, 0x37200260U, 0xf9403f40U,
    0xf951c800U, 0xf9402370U, 0x6b10001fU, 0x54000061U, 0xf9403362U, 0x94022ea5U,
    0x9141df61U, 0xf9410821U, 0xaa1603e2U, 0xf81e83a0U, 0x9402331bU, 0xf85e83a1U,
    0xaa0003e2U, 0xf9403b64U, 0x97c03ce7U, 0x9100c2c0U, 0x17f7f49dU, 0xf85d03a0U,
    0xf11ebc1fU, 0x54000261U, 0xf9403f40U, 0xf951c800U, 0xf9402370U, 0x6b10001fU,
    0x54000061U, 0xf9403362U, 0x94022e90U, 0x9141df61U, 0xf9410c21U, 0xaa1603e2U,
    0xf81e83a0U, 0x94023306U, 0xf85e83a1U, 0xaa0003e2U, 0xf9403b64U, 0x97c03cd2U,
    0x9100c2c0U, 0x17f7f488U, 0xf85d83a1U, 0x97f50078U, 0x37200260U, 0xf9403f40U,
    0xf951c800U, 0xf9402370U, 0x6b10001fU, 0x54000061U, 0xf9403362U, 0x94022e7bU,
    0x9141df61U, 0xf9411021U, 0xaa1603e2U, 0xf81e83a0U, 0x940232f1U, 0xf85e83a1U,
    0xaa0003e2U, 0xf9403b64U, 0x97c03cbdU, 0x9100c2c0U, 0x17f7f473U, 0xf85e03a3U,
    0xb8417060U, 0x8b1c8000U, 0x6b16001fU, 0x54000261U, 0xf9403f40U, 0xf951c800U,
    0xf9402370U, 0x6b10001fU, 0x54000061U, 0xf9403362U, 0x94022e64U, 0x9141df61U,
    0xf9411421U, 0xaa1603e2U, 0xf81e83a0U, 0x940232daU, 0xf85e83a1U, 0xaa0003e2U,
    0xf9403b64U, 0x97c03ca6U, 0x9100c2c0U, 0x17f7f45cU, 0xb8407002U, 0x8b1c8042U,
    0xf81e83a2U, 0xd2800021U, 0x940231deU, 0xf85e83a2U, 0xf81c83a0U, 0xb800f002U,
    0xf9403f41U, 0xf961b421U, 0x372002c1U, 0xf9403f41U, 0xf961ac21U, 0x36200581U,
    0xf9403f40U, 0xf951c800U, 0xf9402370U, 0x6b10001fU, 0x54000061U, 0xf9403362U,
    0x94022e44U, 0x9141df61U, 0xf9411821U, 0xaa1603e2U, 0xf81d83a0U, 0x940232baU,
    0xf85d83a1U, 0xaa0003e2U, 0xf9403b64U, 0x97c03c86U, 0x9100c2c0U, 0x17f7f43cU,
    0x97fcb01aU, 0xaa0003e1U, 0xf81d83a1U, 0x97f7ec99U, 0x910082d0U, 0x6b10001fU,
    0x54000260U, 0xf9403f40U, 0xf951c800U, 0xf9402370U, 0x6b10001fU, 0x54000061U,
    0xf9403362U, 0x94022e2bU, 0x9141df61U, 0xf9411c21U, 0xaa1603e2U, 0xf81d83a0U,
    0x940232a1U, 0xf85d83a1U, 0xaa0003e2U, 0xf9403b64U, 0x97c03c6dU, 0x9100c2c0U,
    0x17f7f423U, 0xf85f03a1U, 0xf85e83a2U, 0x940000c0U, 0xaa0003e1U, 0xf81d83a1U,
    0x97f7ec7eU, 0x910082d0U, 0x6b10001fU, 0x54000260U, 0xf9403f40U, 0xf951c800U,
    0xf9402370U, 0x6b10001fU, 0x54000061U, 0xf9403362U, 0x94022e10U, 0xf85c83a2U,
    0x9141df61U, 0xf9412021U, 0xf81d83a0U, 0x94023286U,
};

/* ---- v20: 跨版本掩码指纹（49 个 8.01 系样本自动推导）----
 * 与上面 kTmpl 的"逐 word 精确相等"不同，掩码只约束 49 个版本间恒定的位（85%），
 * 对版本漂移免疫：实测 49/49 唯一命中，其余 ~1965 个序言候选 0 命中（ratio = inf）。
 * 生成链: tools/smallwindow/{parse_tmpl,collect_all,derive_mask,gen_header}.py
 * 适配新版本 = 把新 libapp.so 丢进样本目录重跑脚本，无需改本文件。 */
#include "mask_table.h"

typedef struct {
    u64 addr;
    int best;
    int second;
    int cand;
    int mode;      /* 1=fp12 严格序言  2=fp8 宽松  0=未命中 */
} loc_t;

static loc_t g_loc;

static int patch_at(u64 a);
static int addr_has_prologue(u64 a);
static int locate_target(void);
static int has_exec_apk_seg(void);
static int addr_in_rx(u64 a);          /* v20: 看护读前地址校验 */

/* ---- v28: 页锚定，防回滚治本 ----
 * 目标函数位于 base.apk 的 r-xp 文件映射页上；该页被 MADV_DONTNEED 后
 * 内核会从 APK 重填原始字节，补丁静默消失（v20 只能周期性重打兜底）。
 * 治本做法：把这一页的后备存储换成我方 memfd，再以 MAP_FIXED|MAP_SHARED
 * 覆盖同一虚拟地址。此后任何丢弃/重填都来自 memfd，内容恒为补丁后字节。
 * 任一步失败都静默回退，不影响既有「看护 + 原地重打」自愈路径。 */
#define SYS_FTRUNCATE   46
#define SYS_PWRITE64    68
#define SYS_MMAP        222
#define SYS_MLOCK       228
#define SYS_MEMFD_CREATE 279
#define ANCHOR_PG_SZ    4096UL
#define PROT_RX         5      /* PROT_READ|PROT_EXEC */
#define MAP_SHARED_FIXED 0x11  /* MAP_SHARED(1)|MAP_FIXED(0x10) */

static u8  g_page[ANCHOR_PG_SZ];   /* 整页副本缓冲 */
static u64 g_anchored;             /* 已锚定页基址，0=未锚定 */
static int g_anchor_rc;            /* 1=成功 2=memfd/ftruncate/pwrite失败 3=mmap失败 4=校验失败 */
static int anchor_page(u64 a);

/* ---- v35: A 开关（Camellia「桌面内自由小窗」）----
 * 本 so 由 LSPosed native 模块机制在桌面进程启动早期 dlopen，读不到 Camellia 的
 * prefs，也无法反查 CfgProvider。开关值由 Camellia 落在共享存储上（App.onCreate
 * 与设置页切开关时写），这里只读首字节：'0' = 关，'1' / 文件不存在 = 开。
 * 返回 1 表示「开关明确关闭，必须放弃打补丁」。 */
static int a_switch_off(void) {
    static const char *paths[] = {
        "/sdcard/Android/media/com.hyper.Camellia/cam_a_switch",
        "/storage/emulated/0/Android/media/com.hyper.Camellia/cam_a_switch",
        "/data/media/0/Android/media/com.hyper.Camellia/cam_a_switch",
    };
    char b[8];
    unsigned i;
    for (i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {
        int fd = (int)s_open(paths[i], O_RDONLY, 0);
        long n;
        if (fd < 0) continue;
        n = s_read(fd, b, sizeof(b));
        s_close(fd);
        if (n > 0) {
            if (b[0] == '0') return 1;
            if (b[0] == '1') return 0;
        }
    }
    return 0;
}


static void probe_worker(void) {
    u8 hdr[64] __attribute__((aligned(16)));
    u8 ph[56] __attribute__((aligned(16)));
    u8 bytes[DUMP_LEN] __attribute__((aligned(16)));
    u64 e_phoff;
    u64 i;
    u16 e_phentsize, e_phnum;
    u64 seg_off = 0, seg_va = 0, seg_mem = 0;
    u64 target_apk_off = 0;
    u64 runtime = 0;
    char mperm[5];
    int attempt = 0, ok = 0, found = 0, mfound = 0;
    int n, k;

    if (g_done) return;
    g_done = 1;
    g_okpath = -1;

    read_pkg();
    build_paths();   /* v29: 包名已知后立刻构造输出路径 */

    /* ---- v31 (Camellia): 进程白名单 ----
     * 本 so 由 native_init.list 在所有作用域进程里被 dlopen（Camellia 的 scope
     * 含 system / systemui / securitycenter 等）。只有桌面进程才存在 libapp.so
     * 中的 ValidSmallWindowShortcutMenu.isValid，其它进程一律立即退出：
     * 既避免无谓的 maps 扫描，也防止在 system_server 里误匹配到同序言的方法。 */
    if (!s_eq(g_pkg, "com.miui.home")) {
        g_skip = 1;
        /* v34b 诊断：旧版这里静默 return，完全不留痕，无法区分
         * 「模块没注入本进程」与「注入了但包名判定不通过」。
         * 现在把实际 pid/cmdline 落盘（每进程一份，见 build_paths 的 fallback）。 */
        g_oi = 0;
        o_str("SKIP_NOT_HOME\npid=");
        o_dec((u64)s_getpid());
        o_str("\npkg=");
        o_str(g_pkg);
        o_ch('\n');
        o_flush();
        return;
    }

    /* ---- v35: A 开关拦截 ----
     * 用户在 Camellia 设置页关掉「桌面内自由小窗」后，补丁必须真的不打 ——
     * 否则桌面原生 freeform 过滤仍被放行，长按菜单照旧出现图标版「小窗」。 */
    if (a_switch_off()) {
        g_skip = 1;
        g_oi = 0;
        o_str("SKIP_A_SWITCH_OFF\npid=");
        o_dec((u64)s_getpid());
        o_str("\npkg=");
        o_str(g_pkg);
        o_ch('\n');
        o_flush();
        return;
    }

    /* 整链路重试，直到真的读到 ELF 魔数（0x1a1c000 段可能晚于注入点建立）
     * v3：上限 4s（40 × 100ms），不再让桌面主线程等待 */
    for (attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        if (scan_maps_once() > 0 && read_elf_hdr(hdr)) {
            ok = 1;
            break;
        }
        s_sleep_ms(RETRY_MS);
    }
    if (!ok) {
        g_oi = 0;
        o_str("NO_ELF_AFTER_RETRY\nattempts=");
        o_dec((u64)attempt);
        o_str("\nmaps=");
        o_dec((u64)g_nme);
        o_str("\npaths=");
        o_dec((u64)g_npath);
        o_str("\npkg=");
        o_str(g_pkg);
        o_str("\nelf_off=");
        o_hex64(g_elf_off);
        o_str("\nelf_src=");
        o_dec((u64)g_elf_src);
        o_str("\nelf_scan=");
        o_dec((u64)g_nelf_scan);
        o_ch('\n');
        for (n = 0; n < g_npath && n < MAX_PATHS; n++) {
            int fd = (int)s_open(g_paths[n], O_RDONLY, 0);
            o_str("p");
            o_dec((u64)n);
            o_str("=");
            o_str(g_paths[n]);
            if (fd < 0) {
                o_str(" open_errno=");
                o_dec((u64)(-fd));
            } else {
                u8 t[8];
                long r = s_pread(fd, t, 8, (long)g_elf_off);
                s_close(fd);
                o_str(" pread=");
                o_dec((u64)(r < 0 ? -r : r));
                if (r == 8) {
                    o_str(" first8=");
                    o_bytes(t, 8);
                }
            }
            o_ch('\n');
        }
        for (n = 0; n < g_nme && n < 8; n++) {
            o_str("m");
            o_dec((u64)n);
            o_str("=");
            o_hex64(g_me[n].start);
            o_str("-");
            o_hex64(g_me[n].end);
            o_str(" off=");
            o_hex64(g_me[n].off);
            o_str(" ");
            o_str(g_me[n].perm);
            o_ch('\n');
        }
        o_flush();
        return;
    }

    /* ---- v19: 跨版本指纹定位 ---- */
    for (attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        if (scan_maps_once() > 0 && has_exec_apk_seg()) {
            if (locate_target() > 0) { ok = 1; break; }
        }
        s_sleep_ms(RETRY_MS);
    }

    g_oi = 0;
    o_str("LOCATE\nmode=");
    o_str(g_loc.mode == 1 ? "fp12" : (g_loc.mode == 2 ? "fp8" : "none"));
    o_str("\ncand=");
    o_dec((u64)g_loc.cand);
    o_str("\nbest=");
    o_dec((u64)g_loc.best);
    o_str("\nsecond=");
    o_dec((u64)g_loc.second);
    o_str("\nattempts=");
    o_dec((u64)attempt);
    o_str("\nsegs=");
    o_dec((u64)g_nme);
    o_str("\naddr=");
    if (ok) o_hex64(g_loc.addr); else o_str("n/a");
    o_str("\npkg=");
    o_str(g_pkg);
    o_str("\nelf_off=");
    o_hex64(g_elf_off);
    o_str("\nelf_src=");
    o_dec((u64)g_elf_src);
    o_str("\nelf_scan=");
    o_dec((u64)g_nelf_scan);
    o_ch('\n');
    o_flush();

    if (!ok) return;

    /* ---- 运行时补丁（幂等：已是补丁字节直接算成功）---- */
    for (n = 0; n < 50; n++) {
        if (patch_at(g_loc.addr) > 0) break;
        g_rounds++;
        s_sleep_ms(100);
    }

    /* ---- v28: 把目标页后备换成 memfd，从根上阻断「回填原始字节」 ---- */
    if (g_patched > 0 && g_pn > 0) anchor_page(g_paddr[0]);

    /* ---- 3) 结果 ---- */
    g_oi = 0;
    o_str("CAM_A_STATE=OK\nPATCH_DONE\ncand=");
    o_dec((u64)g_cand);
    o_str("\npatched=");
    o_dec((u64)g_patched);
    o_str("\nrounds=");
    o_dec((u64)g_rounds);
    o_str("\nmprotect_err=");
    o_dec((u64)g_mperr);
    o_str("\naddrs=");
    for (n = 0; n < g_pn && n < 4; n++) { o_hex64(g_paddr[n]); o_ch(','); }
    o_str("\nreadback=");
    if (g_patched > 0) o_bytes((const u8 *)(void *)g_paddr[0], PATCH_LEN);
    else o_str("n/a");
    o_str("\nfp_best=");
    o_dec((u64)g_loc.best);
    o_str("\nfp_second=");
    o_dec((u64)g_loc.second);
    o_str("\nfp_min=");
    o_dec((u64)FP_MIN);
    o_str("\nanchor=");
    o_dec((u64)g_anchor_rc);
    o_str("\nanchored_pg=");
    o_hex64(g_anchored);
    o_str("\ntgt=");
    o_str(g_tgt);  /* v34: 实际命中的目标 APK；若为空说明没扫到桌面 APK */
    o_str("\npkg=");
    o_str(g_pkg);
    o_str("\nelf_off=");
    o_hex64(g_elf_off);
    /* v33: 进程身份三元组。写一次即可 —— 桌面进程活着这些值就有效，
     * 进程重启会重新跑 native_init 并覆盖本文件，所以不需要心跳续期。 */
    o_str("\npid=");
    o_dec((u64)s_getpid());
    o_str("\nboot=");
    {
        char bid[9];
        read_boot_id(bid);
        o_str(bid);
    }
    o_ch('\n');
    o_flush();

}

/* 线程启动：raw clone（CLONE_VM 线程，共享地址空间）
 * 子线程从 svc 返回后 x0=0，跳转 fn 执行；父线程拿 tid 立即返回。 */
static long start_thread(void (*fn)(void), void *stack_top) {
    register long x0 __asm__("x0") = (long)THREAD_FLAGS;
    register long x1 __asm__("x1") = (long)stack_top;
    register long x2 __asm__("x2") = 0;
    register long x3 __asm__("x3") = 0;
    register long x4 __asm__("x4") = 0;
    register long x8 __asm__("x8") = (long)SYS_CLONE;
    register long x9 __asm__("x9") = (long)fn;
    __asm__ volatile(
        "svc #0\n"
        "cbnz x0, 1f\n"
        "blr x9\n"
        "mov x0, #0\n"
        "mov x8, #93\n"
        "svc #0\n"
        "1:\n"
        : "+r"(x0)
        : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x8), "r"(x9)
        : "memory", "x10", "x11", "x12", "x13", "x14", "x15", "x16", "x17", "x18", "x30");
    return x0;
}

/* ---- LSPosed native 入口协议（对齐 HyperCeiler native_home_entry.h）----
 * struct ApiEntries { u32 version; HookFunction hook_func; UnhookFunction unhook_func; }
 * extern "C" LibraryLoaded native_init(const ApiEntries *entries);
 * LibraryLoaded = void (*)(const char *name, void *handle)  —— 库加载回调
 * 返回 nullptr 合法（HyperCeiler 在 entries==ptr / safe_mode 时同样返回 nullptr）。
 */
struct api_entries {
    u32 version;
    void *hook_func;
    void *unhook_func;
};
typedef void (*library_loaded_fn)(const char *name, void *handle);

static long s_getpid(void) { return s_sys(172, 0, 0, 0, 0, 0, 0); }

/* 非阻塞入口：只 spawn，不等待。
 * pid 变化检测（对齐 HyperCeiler HomeLayoutEnginePrepareForLauncherChild）：
 * OS4 桌面由 spawner fork 而来，父进程跑过 probe 会留下 g_pid/g_done，
 * 子进程线程不存活但标志被继承 → 必须以 pid 变化重置，否则桌面进程里永不探测。 */
static void spawn_worker(void) {
    long pid = s_getpid();
    if (pid > 0 && g_pid == (int)pid) return; /* 同进程已启动过 */
    g_pid = (int)pid;
    g_done = 0;
    g_started = 1;
    (void)start_thread(probe_worker, (void *)(g_stack + WORKER_STACK_SZ));
}

/* LSPosed native_init 入口（标准协议签名；dlopen 时由 LSPosed dlsym 调用）。
 * 仅 spawn 后立即返回 —— 绝不在持 linker 锁的线程上做 IO/解压重活。 */
__attribute__((visibility("default"))) library_loaded_fn native_init(const struct api_entries *entries) {
    (void)entries;
    spawn_worker();
    return (library_loaded_fn)0;
}

/* constructor 兜底（若模块以普通方式 dlopen 亦可自动触发）；pid 检测防重复 */
__attribute__((constructor)) static void homeprobe_ctor(void) { spawn_worker(); }

/* JNI 入口（若被 System.loadLibrary 加载时同样只 spawn） */
__attribute__((visibility("default"))) long JNI_OnLoad(void *vm, void *res) {
    (void)vm;
    (void)res;
    spawn_worker();
    return 0x00010006L; /* JNI_VERSION_1_6 */
}

/* ================= v7: 运行时字节补丁 =================
 * 目标：ValidSmallWindowShortcutMenu.isValid @ 0x19e737c（1220B）
 * 原序言 12B：stp x29,x30,[x15,#-16]! / mov x29,x15 / sub x15,x15,#0x40
 * 补丁   12B：add x0,x22,#0x20 / ret / nop
 *   add x0,x22,#0x20  -> 返回 Dart bool.TRUE（x22 = PP，TRUE=PP+0x20，FALSE=PP+0x30）
 *   不改 x15，栈平衡；直接跳过整段判定，使「小窗」通过 isValid 过滤。
 *   只改 MAP_PRIVATE 代码页（CoW），不动 APK 文件；mprotect 后立即恢复 r-x。
 */
#define PAGE_SZ 4096UL

static long s_mprotect(u64 addr, u64 len, long prot) {
    return s_sys(226, (long)addr, (long)len, prot, 0, 0, 0);
}

static void flush_icache(u64 addr, u64 len) {
    u64 line = 64;
    u64 p = addr & ~(line - 1);
    u64 e = addr + len;
    for (; p < e; p += line)
        __asm__ __volatile__("dc cvau, %0" :: "r"(p) : "memory");
    __asm__ __volatile__("dsb ish" ::: "memory");
    for (p = addr & ~(line - 1); p < e; p += line)
        __asm__ __volatile__("ic ivau, %0" :: "r"(p) : "memory");
    __asm__ __volatile__("dsb ish" ::: "memory");
    __asm__ __volatile__("isb" ::: "memory");
}

/* 段是否已建立：base.apk 的 r-x 映射且 >= 1MB（libapp.so .text 约 19MB） */
static int has_exec_apk_seg(void) {
    int n;
    for (n = 0; n < g_nme; n++) {
        u64 msz = g_me[n].end - g_me[n].start;
        if (g_me[n].perm[0] == 'r' && g_me[n].perm[2] == 'x' && msz >= 0x100000UL)
            return 1;
    }
    return 0;
}

/* v20 看护安全：地址是否仍落在当前快照的 r-x 段内。
 * 用途：看护线程周期性读回补丁字节，若 libapp.so 被卸载/remap，
 * 直接解引用旧地址会 SIGSEGV 把桌面打死（用户最怕的黑屏）。
 * 读之前先做此校验，失效则强制重定位。 */
static int addr_in_rx(u64 a) {
    int n;

    /* v28: 目标页被 anchor_page 换成我方 memfd 后，maps 里的路径不再是
     * base.apk，不会出现在 g_me（只收 .apk 段）中。该页地址依然有效，
     * 且内容由 memfd 保证恒为补丁字节，必须白名单放行；否则看护会误判
     * 「地址失效」→ 反复触发 REPATCH → 全量扫描只剩噪声匹配（best≈31），
     * 这正是外部设备上 best=35 的成因。 */
    if (g_anchored && (a & ~(ANCHOR_PG_SZ - 1)) == g_anchored
        && a + (u64)PATCH_LEN <= g_anchored + ANCHOR_PG_SZ)
        return 1;

    for (n = 0; n < g_nme; n++) {
        u64 s = g_me[n].start, e = g_me[n].end;
        if (g_me[n].perm[0] == 'r' && g_me[n].perm[2] == 'x'
            && a >= s && a + (u64)PATCH_LEN <= e)
            return 1;
    }
    return 0;
}

/* 候选打分（精确）：返回与 256 条模板完全相同的指令条数。保留作诊断对照。 */
static int score_cand(const u8 *p) {
    const u32 *c = (const u32 *)(const void *)p;
    int i, same = 0;
    for (i = 0; i < TMPL_N; i++)
        if (c[i] == kTmpl[i]) same++;
    return same;
}

/* 候选打分（掩码，v20 主路径）：返回满足 ((w ^ VAL) & MASK) == 0 的 word 数。
 * 掩码只约束 49 个版本间恒定的 85% 的位，故对版本漂移免疫、跨版本恒为 FP_N。 */
static int score_mask(const u8 *p) {
    const u32 *c = (const u32 *)(const void *)p;
    int i, ok = 0;
    for (i = 0; i < FP_N; i++)
        if (((c[i] ^ kFpVal[i]) & kFpMask[i]) == 0) ok++;
    return ok;
}

/* 扫描全部 base.apk 的 r-x 段；strict=1 要求 12B 完整序言，0 只要求前 8B。
 * 返回掩码最高分（0..FP_N）。 */
static int locate_scan(int strict) {
    int n;
    int cand = 0, best = 0, second = 0;
    u64 bestaddr = 0;

    for (n = 0; n < g_nme; n++) {
        u64 ms = g_me[n].start;
        u64 msz = g_me[n].end - ms;
        const u8 *p;
        u64 off;

        if (g_me[n].perm[0] != 'r' || g_me[n].perm[2] != 'x') continue;
        if (msz < (u64)FP_N * 4UL) continue;

        p = (const u8 *)(void *)ms;
        for (off = 0; off + (u64)FP_N * 4UL <= msz; off += 4UL) {
            int s;
            if (rd64(p + off) != PRO_LO) continue;
            if (strict && rd32(p + off + 8) != PRO_HI) continue;
            s = score_mask(p + off);              /* v20: 掩码打分 */
            cand++;
            if (s > best) { second = best; best = s; bestaddr = ms + off; }
            else if (s > second) second = s;
        }
    }

    g_loc.cand = cand;
    g_loc.best = best;
    g_loc.second = second;
    g_loc.addr = bestaddr;
    g_loc.mode = (best > 0) ? (strict ? 1 : 2) : 0;
    return best;
}

/* 判定铁律：必须是唯一最高分（best > second）且达到容错下限 FP_MIN。
 * 任何歧义或低分一律返回 0 → 上层 fail-closed 不打补丁。 */
static int locate_ok(void) {
    return (g_loc.best >= FP_MIN && g_loc.best > g_loc.second) ? g_loc.best : 0;
}

static int locate_target(void) {
    int best = locate_scan(1);
    if (locate_ok()) return best;
    best = locate_scan(0);            /* 帧大小变体兜底 */
    if (locate_ok()) return best;
    return 0;
}

/* v27: 判断某个地址处是否仍是原始序言。
 * 背景：4KB 页被 MADV_DONTNEED 丢弃后，再次访问会从 base.apk 重新填充原始字节，
 * 此时无需重新定位（locate_target 的扫描窗口在 4KB 段内只有 1024B，
 * 锚点靠近页尾时必然搜不到），直接原地重打即可。 */
static int addr_has_prologue(u64 a) {
    const u8 *p = (const u8 *)(void *)a;
    if (a & 3UL) return 0;
    if (rd64(p) != PRO_LO) return 0;
    if (rd32(p + 8) != PRO_HI) return 0;
    return 1;
}

/* 在已定位的运行时地址写补丁；幂等、失败不改内存 */
static int patch_at(u64 a) {
    int i, ok;
    volatile u8 *p;
    u64 pg, pend;

    g_cand = 0;
    g_patched = 0;
    g_mperr = 0;
    g_pn = 0;

    if (a & 3UL) return 0;
    p = (volatile u8 *)(void *)a;

    /* 幂等：已是补丁字节 */
    ok = 1;
    for (i = 0; i < PATCH_LEN; i++)
        if (p[i] != kNew[i]) { ok = 0; break; }
    if (ok) {
        g_cand = 1; g_pn = 1; g_paddr[0] = a; g_patched = 1;
        return 1;
    }

    /* 必须仍是原序言，否则拒绝改动 */
    ok = 1;
    for (i = 0; i < PATCH_LEN; i++)
        if (p[i] != kOrig[i]) { ok = 0; break; }
    if (!ok) return 0;
    g_cand = 1;

    pg = a & ~(PAGE_SZ - 1);
    pend = (a + PATCH_LEN + PAGE_SZ - 1) & ~(PAGE_SZ - 1);
    if (s_mprotect(pg, pend - pg, 7) != 0) { g_mperr++; return 0; }

    for (i = 0; i < PATCH_LEN; i++) p[i] = kNew[i];
    flush_icache(a, PATCH_LEN);
    s_mprotect(pg, pend - pg, 5);     /* 恢复 r-x */

    ok = 1;
    for (i = 0; i < PATCH_LEN; i++)
        if (p[i] != kNew[i]) { ok = 0; break; }
    if (ok) {
        if (g_pn < 4) g_paddr[g_pn] = a;
        g_pn++;
        g_patched++;
    }
    return g_patched;
}

/* v28: 把 a 所在页的后备存储换成 memfd（内容 = 当前页 + 补丁），防回滚。
 * 成功返回 1；失败返回 0 并置 g_anchor_rc，调用方忽略即可（回落自愈路径）。 */
static int anchor_page(u64 a) {
    u64 pg = a & ~(ANCHOR_PG_SZ - 1);
    u32 off = (u32)(a - pg);
    long fd, r;
    int i;

    g_anchor_rc = 0;
    if (g_anchored == pg) { g_anchor_rc = 1; return 1; }
    if (off + PATCH_LEN > ANCHOR_PG_SZ) { g_anchor_rc = 4; return 0; }  /* 跨页不支持 */

    /* 1) 复制整页当前内容（含已写入的补丁），并确保目标 12 字节为补丁值 */
    for (i = 0; i < (int)ANCHOR_PG_SZ; i++) g_page[i] = ((const volatile u8 *)(void *)pg)[i];
    for (i = 0; i < PATCH_LEN; i++) g_page[off + i] = kNew[i];

    /* 2) 建 memfd 并写入页内容 */
    /* v29: memfd 名按目标包名构造（cam_pg_<pkg>），多 App 并存时一眼可辨 */
    fd = s_sys(SYS_MEMFD_CREATE,
               (long)(g_pg_name[0] ? g_pg_name : "cam_pg"),
               1 /*MFD_CLOEXEC*/, 0, 0, 0, 0);
    if (fd < 0) { g_anchor_rc = 2; return 0; }
    if (s_sys(SYS_FTRUNCATE, fd, (long)ANCHOR_PG_SZ, 0, 0, 0, 0) != 0) {
        g_anchor_rc = 2; s_close((int)fd); return 0;
    }
    if (s_sys(SYS_PWRITE64, fd, (long)g_page, (long)ANCHOR_PG_SZ, 0, 0, 0) < 0) {
        g_anchor_rc = 2; s_close((int)fd); return 0;
    }

    /* 3) 覆盖同地址：此后该页由 memfd 提供，被丢弃也从 memfd 重填 */
    r = s_sys(SYS_MMAP, (long)pg, (long)ANCHOR_PG_SZ, PROT_RX, MAP_SHARED_FIXED, fd, 0);
    if ((u64)r != pg) { g_anchor_rc = 3; s_close((int)fd); return 0; }

    /* 4) 尝试锁页（失败不致命：MAP_SHARED 被丢弃也会从 memfd 重填） */
    s_sys(SYS_MLOCK, (long)pg, (long)ANCHOR_PG_SZ, 0, 0, 0, 0);

    s_close((int)fd);   /* 映射已建立，关闭 fd 不影响 */

    /* 5) 回读校验 */
    for (i = 0; i < PATCH_LEN; i++)
        if (((const volatile u8 *)(void *)(pg + off))[i] != kNew[i]) { g_anchor_rc = 4; return 0; }
    g_anchored = pg;
    g_anchor_rc = 1;
    return 1;
}
