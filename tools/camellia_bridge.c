/*
 * camellia_bridge.c —— Camellia root 桥（C 版）
 *
 * 背景：Camellia 作为 LSPosed 模块运行在 system_server 进程（uid=1000）里，
 *       该 SELinux 域不允许 fork/exec shell_exec 标签的程序（/system/bin/cmd、
 *       pm、su 全部 error=13），所以自救阶梯必须借一个 root 常驻进程代跑命令。
 *
 * 通信：system_server 往 /data/system/camellia_action/ 写 req.<nonce>，
 *       桥执行后把输出写 res.<nonce>，system_server 读回。
 *
 * 相比 sh 版的三点改进：
 *   1. inotify 事件驱动（毫秒级），不再 1 秒轮询；另有 1 秒兜底扫描防漏事件。
 *   2. 安全校验：目录 0770 root:system + 请求方 uid 白名单 + HMAC-SHA256 签名
 *      + 时间窗 + nonce 去重 + 命令首 token 白名单，杜绝被当成通用 root shell。
 *   3. 并发执行不阻塞队列：每个请求 fork 独立 handler，父进程靠 SIGCHLD 自动回收。
 *
 * 请求文件格式（第一行是头，其后全部是命令）：
 *     CCB1 <ts_ms> <nonce> <sig_hex>
 *     <shell command...>
 *
 * sig = hex(HMAC-SHA256(KEY, "<ts_ms>\n<nonce>\n<command>"))
 *
 * 编译：gcc -static -O2 -o camellia_bridge camellia_bridge.c
 * 用法：
 *     camellia_bridge            守护模式（前台常驻，供 service.sh 拉起）
 *     camellia_bridge --check    自检：打印目录/权限/守护状态后退出
 *     camellia_bridge --ping     发一条 ping 请求并等回包（验证整条通路）
 *     camellia_bridge --sign <ts> <nonce> <cmd>   打印签名（调试用）
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
#include <time.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/inotify.h>
#include <poll.h>
#include <sys/file.h>
#include <sched.h>

/* 是否已成功切到 init 全局 mount ns（供 camellia_dsnap.c extern） */
int camd_global_ns = 0;

#define BRIDGE_DIR      "/data/system/camellia_action"
#define BAK_DIR         "/data/system/camellia_bak"

/* 与 Java 侧 Prefs.BRIDGE_KEY 必须完全一致 */
static const char BRIDGE_KEY[] = "Camellia#Bridge#2026#v1#a7f3c9e1b5d20846";

#define TS_WINDOW_MS    120000LL
#define NONCE_KEEP      64
#define MAX_CMD         8192
#define MAX_OUT         65536

/* ------------------------------------------------------------------ SHA256 */
typedef struct { uint32_t h[8]; uint64_t len; unsigned char buf[64]; size_t n; } sha256_ctx;

static const uint32_t K256[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

#define ROR(x,n) (((x) >> (n)) | ((x) << (32 - (n))))

static void sha256_block(sha256_ctx *c, const unsigned char *p) {
    uint32_t w[64], a, b, cc, d, e, f, g, h, t1, t2;
    uint32_t s0, s1, S0, S1, ch, mj;
    int i;
    for (i = 0; i < 16; i++)
        w[i] = ((uint32_t)p[i*4] << 24) | ((uint32_t)p[i*4+1] << 16) |
               ((uint32_t)p[i*4+2] << 8) | (uint32_t)p[i*4+3];
    for (i = 16; i < 64; i++) {
        s0 = ROR(w[i-15], 7) ^ ROR(w[i-15], 18) ^ (w[i-15] >> 3);
        s1 = ROR(w[i-2], 17) ^ ROR(w[i-2], 19) ^ (w[i-2] >> 10);
        w[i] = w[i-16] + s0 + w[i-7] + s1;
    }
    a = c->h[0]; b = c->h[1]; cc = c->h[2]; d = c->h[3];
    e = c->h[4]; f = c->h[5]; g = c->h[6]; h = c->h[7];
    for (i = 0; i < 64; i++) {
        S1 = ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25);
        ch = (e & f) ^ ((~e) & g);
        t1 = h + S1 + ch + K256[i] + w[i];
        S0 = ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22);
        mj = (a & b) ^ (a & cc) ^ (b & cc);
        t2 = S0 + mj;
        h = g; g = f; f = e; e = d + t1; d = cc; cc = b; b = a; a = t1 + t2;
    }
    c->h[0] += a; c->h[1] += b; c->h[2] += cc; c->h[3] += d;
    c->h[4] += e; c->h[5] += f; c->h[6] += g; c->h[7] += h;
}

static void sha256_init(sha256_ctx *c) {
    c->h[0] = 0x6a09e667; c->h[1] = 0xbb67ae85; c->h[2] = 0x3c6ef372; c->h[3] = 0xa54ff53a;
    c->h[4] = 0x510e527f; c->h[5] = 0x9b05688c; c->h[6] = 0x1f83d9ab; c->h[7] = 0x5be0cd19;
    c->len = 0; c->n = 0;
}

static void sha256_update(sha256_ctx *c, const void *data, size_t len) {
    const unsigned char *p = (const unsigned char *)data;
    c->len += len;
    while (len > 0) {
        size_t take = 64 - c->n;
        if (take > len) take = len;
        memcpy(c->buf + c->n, p, take);
        c->n += take; p += take; len -= take;
        if (c->n == 64) { sha256_block(c, c->buf); c->n = 0; }
    }
}

static void sha256_final(sha256_ctx *c, unsigned char out[32]) {
    uint64_t bits = c->len * 8;
    unsigned char pad = 0x80, lb[8];
    int i;
    sha256_update(c, &pad, 1);
    pad = 0x00;
    while (c->n != 56) sha256_update(c, &pad, 1);
    for (i = 0; i < 8; i++) lb[i] = (unsigned char)(bits >> (56 - i * 8));
    sha256_update(c, lb, 8);
    for (i = 0; i < 8; i++) {
        out[i*4]     = (unsigned char)(c->h[i] >> 24);
        out[i*4 + 1] = (unsigned char)(c->h[i] >> 16);
        out[i*4 + 2] = (unsigned char)(c->h[i] >> 8);
        out[i*4 + 3] = (unsigned char)(c->h[i]);
    }
}

static void hmac_sha256(const char *key, const void *msg, size_t msg_len, unsigned char out[32]) {
    unsigned char k[64], ipad[64], opad[64], inner[32];
    sha256_ctx c;
    size_t klen = strlen(key);
    int i;
    memset(k, 0, sizeof(k));
    if (klen > 64) { sha256_init(&c); sha256_update(&c, key, klen); sha256_final(&c, k); }
    else memcpy(k, key, klen);
    for (i = 0; i < 64; i++) { ipad[i] = k[i] ^ 0x36; opad[i] = k[i] ^ 0x5c; }
    sha256_init(&c); sha256_update(&c, ipad, 64); sha256_update(&c, msg, msg_len); sha256_final(&c, inner);
    sha256_init(&c); sha256_update(&c, opad, 64); sha256_update(&c, inner, 32); sha256_final(&c, out);
}

static void hex32(const unsigned char in[32], char out[65]) {
    static const char *H = "0123456789abcdef";
    int i;
    for (i = 0; i < 32; i++) { out[i*2] = H[in[i] >> 4]; out[i*2 + 1] = H[in[i] & 15]; }
    out[64] = 0;
}

/* ------------------------------------------------------------------ 工具 */
static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static int mkdir_p(const char *path, mode_t mode) {
    char tmp[512];
    char *p;
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (p = tmp + 1; *p; p++) {
        if (*p == '/') { *p = 0; mkdir(tmp, mode); *p = '/'; }
    }
    return mkdir(tmp, mode);
}

/* 目录准备：必须是 root:system 0770 —— system_server(uid=1000) 要能进。
 * 用 root:root 0770 会让 system_server 直接 EACCES，表现得像「桥没工作」。 */
static void setup_dir(void) {
    mkdir_p(BRIDGE_DIR, 0770);
    if (chown(BRIDGE_DIR, 0, 1000) != 0) { /* 忽略：已有目录可能已正确 */ }
    chmod(BRIDGE_DIR, 0770);
    mkdir_p(BAK_DIR, 0770);
    if (chown(BAK_DIR, 0, 1000) != 0) { /* 忽略 */ }
    chmod(BAK_DIR, 0770);
}

/* nonce 去重环 */
static char g_nonce[NONCE_KEEP][33];
static int  g_nonce_n = 0;

static int nonce_seen(const char *n) {
    int i;
    for (i = 0; i < g_nonce_n && i < NONCE_KEEP; i++)
        if (strcmp(g_nonce[i], n) == 0) return 1;
    return 0;
}

static void nonce_add(const char *n) {
    int slot = g_nonce_n % NONCE_KEEP;
    snprintf(g_nonce[slot], sizeof(g_nonce[slot]), "%s", n);
    g_nonce_n++;
}

/* 命令首 token 白名单 */
static const char *ALLOWED[] = {
    "pm", "cmd", "am", "cp", "mkdir", "chmod", "chown", "rm", "mv", "ln",
    "ls", "cat", "id", "sh", "getprop", "setprop", "dumpsys", "sync", "kill",
    "pkill", "df", "mount", "umount", "magisk", "settings", "echo", "sleep",
    "test", "stat", "head", "tail", "grep", "sed", "tar", "unzip", "dd",
    "log", "wm", "svc", "appops", "content", "input", "screencap", "true",
    "find", "sha256sum", "md5sum", "cksum", "blockdev", "restorecon", NULL
};

static int cmd_allowed(const char *cmd) {
    char first[64];
    char *base = first;
    int i = 0, j = 0, k, round;
    for (round = 0; round < 2; round++) {
        first[0] = 0; j = 0;
        while (cmd[i] && (cmd[i] == ' ' || cmd[i] == '\t' || cmd[i] == '\n')) i++;
        while (cmd[i] && j < (int)sizeof(first) - 1) {
            if (cmd[i] == ' ' || cmd[i] == '\t' || cmd[i] == '\n') break;
            first[j++] = cmd[i++];
        }
        first[j] = 0;
        if (j == 0) return 0;
        base = strrchr(first, '/');
        base = base ? base + 1 : first;
        /* 形如 VAR=value 的前置赋值：跳过，继续取真正的命令 */
        if (round == 0 && strchr(base, '=')) continue;
        break;
    }
    for (k = 0; ALLOWED[k]; k++)
        if (strcmp(base, ALLOWED[k]) == 0) return 1;
    return 0;
}

/* 调试日志：仅在存在 /data/local/tmp/bridge_debug 标记文件时写 */
static void dbg(const char *fmt, ...) {
    static int on = -1;
    int fd;
    char buf[512];
    va_list ap;
    int n;
    if (on < 0) on = (access("/data/local/tmp/bridge_debug", F_OK) == 0) ? 1 : 0;
    if (!on) return;
    fd = open("/data/local/tmp/bridge_dbg.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;
    n = snprintf(buf, sizeof(buf), "[%lld] ", now_ms());
    va_start(ap, fmt);
    n += vsnprintf(buf + n, sizeof(buf) - n, fmt, ap);
    va_end(ap);
    if (n > (int)sizeof(buf) - 2) n = (int)sizeof(buf) - 2;
    buf[n++] = '\n';
    write(fd, buf, (size_t)n);
    close(fd);
}

static void write_res(const char *nonce, const char *body) {
    char path[512];
    int fd;
    size_t len;
    snprintf(path, sizeof(path), BRIDGE_DIR "/res.%s", nonce);
    fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) { dbg("write_res open fail errno=%d nonce=%s", errno, nonce); return; }
    len = strlen(body);
    if (len > MAX_OUT) len = MAX_OUT;
    if (write(fd, body, len) < 0) { /* 忽略 */ }
    close(fd);
}

/* 处理单个请求；name 形如 "req.<nonce>" */
static void handle_req(const char *path, const char *name) {
    const char *nonce = name + 4;
    struct stat st;
    char req[MAX_CMD + 1];
    char msg[MAX_CMD + 160];
    char hnonce[64], sig[80], machex[65];
    char *nl, *cmd;
    long long ts = 0, d;
    unsigned char mac[32];
    ssize_t n;
    int fd, mlen;
    pid_t pid;

    if (stat(path, &st) != 0) { dbg("stat fail %s", path); return; }
    dbg("enter %s uid=%u", name, (unsigned)st.st_uid);

    /* 1) 身份校验：只接受 system(1000) 与 root(0) */
    if (st.st_uid != 1000 && st.st_uid != 0) {
        char buf[128];
        snprintf(buf, sizeof(buf), "DENY(uid=%u not allowed) (exit=1)", (unsigned)st.st_uid);
        write_res(nonce, buf);
        unlink(path);
        return;
    }

    /* 空文件 = 对端还在写（IN_CREATE 竞态）；跳过，等 CLOSE_WRITE */
    if (st.st_size <= 0) { dbg("skip empty (still being written)"); return; }

    /* 读请求体；读到内容后再删除，防重复处理 */
    fd = open(path, O_RDONLY);
    if (fd < 0) { dbg("open req fail errno=%d", errno); return; }
    n = read(fd, req, MAX_CMD);
    close(fd);
    if (n <= 0) { dbg("read 0, keep for retry"); return; }
    unlink(path);
    req[n] = 0;

    /* 2) 解析头：CCB1 <ts> <nonce> <sig> */
    nl = strchr(req, '\n');
    dbg("body=%d bytes", (int)n);
    if (!nl) { dbg("no header"); write_res(nonce, "DENY(no header) (exit=1)"); return; }
    *nl = 0;
    cmd = nl + 1;
    while (*cmd == '\n') cmd++;

    hnonce[0] = 0; sig[0] = 0;
    dbg("hdr parsed: ts=%lld nonce=%s sig=%.16s", ts, hnonce, sig);
    if (sscanf(req, "CCB1 %lld %63s %79s", &ts, hnonce, sig) != 3) {
        write_res(nonce, "DENY(bad header) (exit=1)");
        return;
    }

    /* 3) 时间窗（防重放） */
    d = now_ms() - ts;
    if (d < 0) d = -d;
    dbg("ts delta=%lld", d);
    if (d > TS_WINDOW_MS) {
        write_res(nonce, "DENY(expired) (exit=1)");
        return;
    }

    /* 4) nonce 一致性 + 去重 */
    if (strcmp(hnonce, nonce) != 0) {
        write_res(nonce, "DENY(nonce mismatch) (exit=1)");
        return;
    }
    if (nonce_seen(nonce)) {
        write_res(nonce, "DENY(replay) (exit=1)");
        return;
    }

    /* 5) HMAC 校验 */
    mlen = snprintf(msg, sizeof(msg), "%lld\n%s\n%s", ts, hnonce, cmd);
    hmac_sha256(BRIDGE_KEY, msg, (size_t)mlen, mac);
    hex32(mac, machex);
    dbg("sig calc=%.16s got=%.16s cmd=[%s]", machex, sig, cmd);
    if (strcmp(machex, sig) != 0) {
        write_res(nonce, "DENY(bad sig) (exit=1)");
        return;
    }
    nonce_add(nonce);
    dbg("sig OK");

    /* 6) 命令白名单 */
    dbg("cmd_allowed=%d", cmd_allowed(cmd));
    if (!cmd_allowed(cmd)) {
        write_res(nonce, "DENY(cmd not allowed) (exit=1)");
        return;
    }

    /* 7) 执行：fork 独立 handler，父进程不等待 —— 一次 49MB 的 pm install
     *    不会把队列堵死，后续请求照常处理。 */
    pid = fork();
    if (pid == 0) {
        char respath[512];
        char script[MAX_CMD + 160];
        int out;
        snprintf(respath, sizeof(respath), BRIDGE_DIR "/res.%s", nonce);
        out = open(respath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (out < 0) dbg("child res open fail errno=%d path=%s", errno, respath);
        if (out >= 0) {
            dup2(out, STDOUT_FILENO);
            dup2(out, STDERR_FILENO);
            if (out > 2) close(out);
        }
        snprintf(script, sizeof(script), "%s\necho \" (exit=$?)\"", cmd);
        execl("/system/bin/sh", "sh", "-c", script, (char *)NULL);
        execl("/bin/sh", "sh", "-c", script, (char *)NULL);
        _exit(127);
    }
    /* 父进程立刻返回继续扫下一个 */
}

/* ------------------------------------------------------------------ 扫描 */
/* 清理 BRIDGE_DIR 里超过 10 分钟的 req./res. 文件，避免目录无限增长。
 *
 * 从 sweep() 里拆出来：sweep 要降频到 300s（快通道正常时它只是兜底），
 * 若清理还挂在它内部，陈旧文件最长要躺 300s + 10min 才被回收。
 * 拆开后按自己的 60s 节流走，与 sweep 频率彻底解耦。 */
static void sweep_clean(void) {
    static long long last_clean = 0;
    long long now = now_ms();
    DIR *d;
    struct dirent *de;

    if (now - last_clean < 60000) return;
    last_clean = now;

    d = opendir(BRIDGE_DIR);
    if (!d) return;
    while ((de = readdir(d)) != NULL) {
        char path[512];
        struct stat st;
        if (strncmp(de->d_name, "req.", 4) != 0 && strncmp(de->d_name, "res.", 4) != 0)
            continue;
        snprintf(path, sizeof(path), BRIDGE_DIR "/%s", de->d_name);
        if (stat(path, &st) == 0 && (now - (long long)st.st_mtime * 1000LL) > 600000LL)
            unlink(path);
    }
    closedir(d);
}

static int sweep(void) {
    DIR *d;
    struct dirent *de;
    int cnt = 0;

    d = opendir(BRIDGE_DIR);
    if (!d) return 0;
    while ((de = readdir(d)) != NULL) {
        char path[512];
        size_t l;
        if (strncmp(de->d_name, "req.", 4) != 0) continue;
        dbg("sweep found %s", de->d_name);
        l = strlen(de->d_name);
        if (l <= 4) continue;
        if (strcmp(de->d_name + l - 4, ".tmp") == 0) continue;
        snprintf(path, sizeof(path), BRIDGE_DIR "/%s", de->d_name);
        handle_req(path, de->d_name);
        cnt++;
    }
    closedir(d);
    return cnt;
}

/* ------------------------------------------------------------------ 自检 */
static int count_procs(void) {
    DIR *d = opendir("/proc");
    struct dirent *de;
    int n = 0;
    if (!d) return -1;
    while ((de = readdir(d)) != NULL) {
        char p[128], buf[64];
        int fd;
        ssize_t r;
        if (de->d_name[0] < '0' || de->d_name[0] > '9') continue;
        snprintf(p, sizeof(p), "/proc/%s/comm", de->d_name);
        fd = open(p, O_RDONLY);
        if (fd < 0) continue;
        r = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (r <= 0) continue;
        buf[r] = 0;
        if (strstr(buf, "camellia_bridge")) n++;
    }
    closedir(d);
    return n;
}

/* 发一条请求并等到回包；返回 0 表示成功 */
static int send_and_wait(const char *cmd, int wait_ms) {
    char nonce[33], path[512], msg[512], sig[65], hdr[256];
    unsigned char mac[32];
    long long ts = now_ms();
    int fd, i, waited = 0;
    snprintf(nonce, sizeof(nonce), "%llx%x", (unsigned long long)ts, (unsigned)getpid());
    snprintf(msg, sizeof(msg), "%lld\n%s\n%s", ts, nonce, cmd);
    hmac_sha256(BRIDGE_KEY, msg, strlen(msg), mac);
    hex32(mac, sig);
    snprintf(hdr, sizeof(hdr), "CCB1 %lld %s %s\n%s", ts, nonce, sig, cmd);

    snprintf(path, sizeof(path), BRIDGE_DIR "/req.%s", nonce);
    fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) { printf("  ❌ 无法写请求文件: %s\n", strerror(errno)); return -1; }
    if (write(fd, hdr, strlen(hdr)) < 0) { /* 忽略 */ }
    close(fd);

    for (i = 0; i < wait_ms / 50; i++) {
        char rp[512];
        snprintf(rp, sizeof(rp), BRIDGE_DIR "/res.%s", nonce);
        if (access(rp, F_OK) == 0) {
            char out[MAX_OUT];
            int n;
            fd = open(rp, O_RDONLY);
            n = fd >= 0 ? (int)read(fd, out, sizeof(out) - 1) : -1;
            if (fd >= 0) close(fd);
            if (n > 0) { out[n] = 0; printf("  ✅ 回包: %s", out); }
            else printf("  ⚠️ 回包为空\n");
            unlink(rp);
            return 0;
        }
        usleep(50000);
        waited += 50;
    }
    snprintf(path, sizeof(path), BRIDGE_DIR "/req.%s", nonce);
    unlink(path);
    printf("  ❌ %dms 内无回包（桥没跑？）\n", waited);
    return -1;
}

static int do_check(void) {
    struct stat st;
    int procs, reqs = 0, ress = 0;
    DIR *d;
    struct dirent *de;

    printf("===== camellia_bridge 自检 =====\n");
    printf("[1] 目录 %s\n", BRIDGE_DIR);
    if (stat(BRIDGE_DIR, &st) != 0) {
        printf("    ❌ 不存在 (%s)\n", strerror(errno));
        return 1;
    }
    printf("    ✅ 存在 mode=%04o uid=%u gid=%u\n",
           (unsigned)(st.st_mode & 07777), (unsigned)st.st_uid, (unsigned)st.st_gid);

    printf("[2] 守护进程\n");
    procs = count_procs();
    printf("    %s 实例数=%d\n", procs >= 1 ? "✅" : "❌", procs);

    d = opendir(BRIDGE_DIR);
    if (d) {
        while ((de = readdir(d)) != NULL) {
            if (strncmp(de->d_name, "req.", 4) == 0) reqs++;
            else if (strncmp(de->d_name, "res.", 4) == 0) ress++;
        }
        closedir(d);
    }
    printf("[3] 队列 req=%d res=%d\n", reqs, ress);

    printf("[4] 通路测试\n");
    if (procs < 1) { printf("    ⏭  跳过（桥未运行）\n"); return 1; }
    return send_and_wait("id -u\necho BRIDGE_OK", 5000);
}

/* --------------------------------------------------- 单实例（flock） */
/* 两个实例同时 inotify 同一目录会重复消费请求 —— 同一条命令被执行两次，
 * 对 pm uninstall / clear 这类动作是灾难。开机时 App 侧 su 与 service.d
 * watchdog 存在竞态（两边都会拉起），必须靠内核文件锁兜底。 */
static int acquire_single_instance(void) {
    int fd = open(BRIDGE_DIR "/.daemon.lock", O_RDWR | O_CREAT, 0644);
    if (fd < 0) {
        dbg("lock open fail errno=%d (continue without lock)", errno);
        return -1;              /* 锁都打不开时宁可跑起来，否则等于没有桥 */
    }
    if (flock(fd, LOCK_EX | LOCK_NB) != 0) {
        close(fd);
        return -2;              /* 已有实例持锁 */
    }
    return fd;                  /* 保持打开 = 持锁；进程退出内核自动释放 */
}

/* camellia_rescue.c 提供的自愈能力：本进程每秒 tick 一次（内部按配置节流），
 * 以及 --status/--diagnose/--fix/--reset-halt 子命令。 */
extern int rescue_tick(void);
extern int rescue_fill_pidfds(struct pollfd *pf, int cap);
extern void rescue_kick(void);
extern int rescue_ebpf_init(void);
extern int rescue_ebpf_pollfd(void);
extern void rescue_ebpf_drain(void);
extern int rescue_cli(int argc, char **argv);
/* UI 通道：App 是普通应用（无 root、读不到 /data/adb），
 * 只能靠外部私有目录的文件与 camd 交换「状态 / 开关」。 */
extern void rescue_poll_cmd(void);
extern void rescue_write_status(void);
extern void rescue_write_status_force(void);
/* 首启补齐 config：cfg_load 只读不建，新机否则看不到配置文件 */
extern void cfg_ensure(void);
/* camd 根目录（/data/adb/camellia，可用 $CAM_DIR 覆盖） */
extern const char *cam_dir_root(void);

/* socket 通道（camellia_sock.c）：App 直连，状态推送 + 命令下发。
 * 让「UI 状态」从「App 轮询读 FUSE 文件」变成「camd 变化时主动推」。 */
extern int  camd_sock_listen(void);
extern int  camd_sock_fd(void);
extern int  camd_sock_active(void);
extern int  camd_sock_fill(struct pollfd *pfds, int max);
extern void camd_sock_accept(void);
extern void camd_sock_read(int fd);
extern void camd_sock_broadcast(const char *payload, size_t len);

/*
 * App 的能力声明：CAM_DIR/caps 内含 app_root=1 表示「本 App 已拿到 root，
 * 后续指令走 CAM_DIR/cmd 快通道」。读不到 / 内容不对一律按慢通道（保守）：
 * 宁可多轮询 FUSE，也不能因为误判而漏掉 UI 指令。
 */
/* 三态返回，区分「明确无 root」与「连 caps 都读不到」：
 *   1  = 快通道就绪（app_root=1）
 *   0  = 明确无 root（caps 存在但未声明）→ FUSE 通道有效，可 5s 轮询
 *  -1  = 读不到 caps（camd 自身失权 / 目录消失）→ FUSE 也大概率不可用，
 *        必须降频（原来把 -1 当 0，退化成 3s 高频轮询，失 root 反而多唤醒）
 * 保守性不变：-1 不会让任何指令被丢弃，只是拉长兜底轮询间隔。 */
static int cam_caps_root(void) {
    char path[320], buf[256];
    const char *root = cam_dir_root();
    int fd;
    ssize_t n;

    if (!root || !root[0]) return -1;
    snprintf(path, sizeof(path), "%s/caps", root);
    fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = 0;
    return strstr(buf, "app_root=1") != NULL ? 1 : 0;
}

int main(int argc, char **argv) {
    int ifd, wd, ifd_cam = -1, wd_cam = -1;
    /* 2 个 inotify + 1 个监听 + 最多 4 个客户端（与 camellia_sock.c 的
     * SOCK_MAX_CLI 对齐，多给 1 个余量）。 */
    struct pollfd pfds[16];

    signal(SIGCHLD, SIG_IGN);   /* 子进程自动回收，不留僵尸 */
    signal(SIGPIPE, SIG_IGN);

    if (argc >= 2 && !strcmp(argv[1], "--dsnap-child")) {
        extern int dsnap_child_cli(int, char **);
        return dsnap_child_cli(argc, argv);
    }
    if (argc >= 2 && !strcmp(argv[1], "--dsnap-restore")) {
        extern int dsnap_child_restore_cli(int, char **);
        return dsnap_child_restore_cli(argc, argv);
    }

    if (argc < 2) {
        /* 守护模式 */
        int lockfd;
        int fast, poll_cnt = 0;
        setup_dir();
        cfg_ensure();           /* 新机首次启动即落出 config，才可调参 */
        /* 切到 init 的全局 mount namespace：本进程即可见 /data/user_de（其它 app 数据），
         * dsnap 备份/恢复全程本进程完成，不再 fork/nsenter 子进程。 */
        { int nsfd = open("/proc/1/ns/mnt", O_RDONLY);
          if (nsfd >= 0) {
              if (setns(nsfd, CLONE_NEWNS) == 0) camd_global_ns = 1;
              else dbg("setns init mnt failed errno=%d", errno);
              close(nsfd);
          } else dbg("open /proc/1/ns/mnt failed errno=%d", errno);
          dbg("camd_global_ns=%d", camd_global_ns); }
        lockfd = acquire_single_instance();
        if (lockfd == -2) {
            fprintf(stderr, "camd: another instance already running, exit\n");
            return 0;
        }
        (void) lockfd;

        ifd = inotify_init1(IN_NONBLOCK);
        wd = ifd >= 0 ? inotify_add_watch(ifd, BRIDGE_DIR,
                 IN_CLOSE_WRITE | IN_MOVED_TO) : -1;

        /* 第二个 watch 盯 camd 自己的目录（ext4 上）：App 有 root 后把指令写到
         * CAM_DIR/cmd、能力声明写到 CAM_DIR/caps，两者都在这里触发事件。
         * ext4 的 inotify 完全可靠；/sdcard 的 FUSE 不可靠，所以慢通道只能轮询。 */
        ifd_cam = inotify_init1(IN_NONBLOCK);
        if (ifd_cam >= 0) {
            wd_cam = inotify_add_watch(ifd_cam, cam_dir_root(),
                     IN_CLOSE_WRITE | IN_MOVED_TO);
        }
        fast = cam_caps_root();
        dbg("poll mode fast=%d", fast);

        rescue_ebpf_init();   /* eBPF 目标退出检测：可用即启用，失败自动回退 */
        sweep();
        for (;;) {
            int cam_hit = 0, nfd, srv_idx, cli_base, ncli, i, pid_base = 0, npid = 0, k, eb_idx = -1, eb_ok = 0;

            /* socket 通道：App 私有目录就绪才建，失败 5s 后重试。
             * 建好之后 App 与 camd 之间的状态/命令都走这条长连接。 */
            (void) camd_sock_listen();

            pfds[0].fd = (wd >= 0) ? ifd : -1;
            pfds[0].events = POLLIN;
            pfds[0].revents = 0;
            pfds[1].fd = (wd_cam >= 0) ? ifd_cam : -1;
            pfds[1].events = POLLIN;
            pfds[1].revents = 0;
            nfd = 2;

            srv_idx = -1;
            if (camd_sock_fd() >= 0) {
                srv_idx = nfd;
                pfds[nfd].fd = camd_sock_fd();
                pfds[nfd].events = POLLIN;
                pfds[nfd].revents = 0;
                nfd++;
            }
            cli_base = nfd;
            ncli = camd_sock_fill(&pfds[nfd],
                                  (int) (sizeof(pfds) / sizeof(pfds[0])) - nfd);
            /* pidfd：盯 target(SystemUI/plugin) 退出，退出即“提前触发一次 tick 判定”，
             * 不改任何记账；老内核无 pidfd 时返回 0，退回 tick 采样。 */
            pid_base = nfd + ncli;
            npid = rescue_fill_pidfds(&pfds[pid_base],
                                  (int) (sizeof(pfds) / sizeof(pfds[0])) - pid_base);
            /* eBPF ringbuf：目标进程退出事件（可用时加入 poll，事件即唤醒） */
            eb_idx = -1; eb_ok = 0;
            { int efd = rescue_ebpf_pollfd(); if (efd >= 0) { int ix = pid_base + npid;
                if (ix < (int)(sizeof(pfds)/sizeof(pfds[0]))) { pfds[ix].fd = efd; pfds[ix].events = POLLIN; pfds[ix].revents = 0; eb_idx = ix; eb_ok = 1; } } }

            if (wd < 0 && wd_cam < 0 && ncli == 0) {
                sleep(1);
            } else {
                /* 有客户端在线时状态由 push 驱动，超时放长；仍保留兜底
                 * （半死连接不发 FIN，只能靠超时 + 后续写失败发现并清理）。
                 *
                 * 超时分档（性能优化核心）：poll 超时决定了主循环里所有
                 * 「按轮计频」任务的实际节拍，是唤醒次数的直接乘数。
                 *   · ncli>0  ：面板要 5s 刷新一次状态，取 5000
                 *   · 有 root  ：命令走 inotify(cmd)+socket 快通道，零轮询，
                 *                这里只承担 tick(30s)/sweep/心跳(45s) 的心跳，
                 *                取 30000 —— 30s 是 tick_sec 的下限，再长会
                 *                拖慢故障发现
                 *   · 无 root  ：FUSE 文件是唯一命令通道，必须保持 3s 才能
                 *                做到指令可接受的延迟，无法降频
                 * 原来三种情况都是 3000，等于有 root 时白醒 28800 次/天。 */
                /* 二档收敛：有面板客户端(需 5s 刷新) 或 明确无 root(FUSE 是
                 * 唯一命令通道) → 5000；有 root 或失权(fast<0，FUSE 也不可用)
                 * → 30000。 */
                int to = (ncli > 0 || fast == 0) ? 5000 : 30000;
                if (poll(pfds, nfd + ncli + npid + eb_ok, to) > 0) {
                    char buf[4096];
                    if (pfds[0].fd >= 0 && (pfds[0].revents & POLLIN)) {
                        while (read(ifd, buf, sizeof(buf)) > 0) { /* 排空事件 */ }
                    }
                    if (pfds[1].fd >= 0 && (pfds[1].revents & POLLIN)) {
                        while (read(ifd_cam, buf, sizeof(buf)) > 0) { /* 排空 */ }
                        cam_hit = 1;
                    }
                    if (srv_idx >= 0 && (pfds[srv_idx].revents & POLLIN)) {
                        camd_sock_accept();
                        /* 新客户端刚连上：立刻做一次完整采集并广播，
                         * 绕过 5s 节流与「无客户端只发心跳」的降级。
                         * 面板打开瞬间就该看到真实状态，而不是心跳里
                         * 沿用下来的旧业务指纹。 */
                        rescue_write_status_force();
                    }
                    for (i = 0; i < ncli; i++) {
                        if (pfds[cli_base + i].revents)
                            camd_sock_read(pfds[cli_base + i].fd);
                    }
                    for (k = 0; k < npid; k++) {
                        if (pfds[pid_base + k].fd >= 0 && (pfds[pid_base + k].revents & POLLIN))
                            rescue_kick();
                    }
                    if (eb_ok && eb_idx >= 0 && (pfds[eb_idx].revents & POLLIN))
                        rescue_ebpf_drain();
                }
            }

            if (cam_hit) {
                /* caps 刚被改（App 授权 / 撤销）或 App 刚下发指令：重读能力声明
                 * 并立刻处理指令，延迟基本为 0，且不产生任何轮询开销。 */
                fast = cam_caps_root();
                rescue_poll_cmd();
                poll_cnt = 0;
            } else if (fast > 0) {
                /* 快通道就绪：FUSE 轮询降为 60s 一次的兜底（原为每秒，
                 * 一天 8.6 万次跨进程往返全部省掉）。顺带重读 caps，
                 * 覆盖「App 撤销 root 却没来得及写 caps」的极端情况。 */
                /* 兜底重读 caps + 取指令的周期保持 60s。原来是 3s 轮 × 20，
                 * 现在 fast 分支轮长 30s，按秒对齐改为 2 轮（30s × 2 = 60s），
                 * 否则「App 中途撤销 root」的降级检测会拖到 10 分钟。 */
                if (++poll_cnt >= 2) {
                    poll_cnt = 0;
                    fast = cam_caps_root();
                    rescue_poll_cmd();
                }
            } else {
                /* 无 root：只能轮询 FUSE 取指令。若 App 已通过 socket 连上，
                 * 命令走 socket 即时到达，这里降为每 4 轮（约 12s）兜底，
                 * 防止 socket 半死时彻底收不到指令。 */
                static int slow_tick = 0;
                if (ncli > 0) {
                    if (++slow_tick >= 4) { slow_tick = 0; rescue_poll_cmd(); }
                } else {
                    /*
                     * 零客户端 + 无 root：纯 FUSE 轮询，原来是每 1s 一次
                     * （8.6 万次/天跨进程 open）。这个状态下绝大多数指令
                     * 即使读到也执行不了，没必要秒级去摸。改为阶梯降频：
                     * 前 6 轮按 3s，之后固定 5s；一有客户端连上就立刻走上面
                     * 的 socket 即时通道，所以延迟不会真的变成 5s 量级。
                     */
                    static int idle = 0;
                    /* fast<0（连 caps 都读不到）：FUSE 大概率同样不可用，
                     * 轮询拉长到每 2 轮一次，且每次顺带重读 caps —— 失权
                     * 期间 inotify/sweep 都收不到事件，只能靠这里发现恢复。 */
                    int need = (fast < 0) ? 2 : ((idle > 6) ? 5 : 3);
                    if (++idle >= need) {
                        idle = 0;
                        if (fast < 0) {
                            fast = cam_caps_root();
                            dbg("caps recheck fast=%d", fast);
                        }
                        rescue_poll_cmd();
                    }
                }
            }

            /* sweep 扫 BRIDGE_DIR（/data/system，ext4）里的 req.* 请求文件。
             * 真正的快通道是 inotify + socket，sweep 只是兜底：防 inotify
             * 事件丢失、防 App 直接写文件。
             *  · 有 root（快通道就绪）：30s 轮 × 10 = 300s 一次。它保的是
             *    「inotify 完全静默失效」这种低概率事件，晚 5 分钟发现和
             *    晚 1 分钟发现没有本质区别；而 inotify 全废的极端情况由
             *    上面 wd<0 分支的 sleep 循环兜住，不会真的靠 sweep 保命。
             *  · 无 root：FUSE 文件仍是可选通道，保留阶梯（空扫越久越疏），
             *    有事件（cam_hit）立即复位。
             * 陈旧文件清理不在这里，见 sweep_clean()：它要维持 60s 级。 */
            {
                static int sw_tick = 0;
                static int sw_idle = 0;
                if (cam_hit) sw_idle = 0;
                /* fast != 0：有快通道 或 已失权（sweep 扫的是 /data/system，
                 * 失权时同样读不到，走稀疏档即可）；只有「明确无 root」才
                 * 需要阶梯式 FUSE 兜底。 */
                if (fast != 0) {
                    if ((sw_tick++ % 10) == 0) sweep();
                } else {
                    int step = (sw_idle < 2) ? 1 : ((sw_idle < 6) ? 2 : 4);
                    if ((sw_tick++ % step) == 0) sweep();
                    sw_idle++;
                }
                sweep_clean();
            }
            rescue_write_status();  /* 状态快照给 UI：内部 5s 节流 */
            rescue_tick();      /* 自愈自检：内部按 config.tick_sec 节流 */
        }
    }

    /* rescue 子命令优先分发（--status/--diagnose/--fix/--reset-halt/--tick-now） */
    {
        int rc = rescue_cli(argc, argv);
        if (rc >= 0) return rc;
    }

    if (strcmp(argv[1], "--check") == 0) return do_check() == 0 ? 0 : 1;
    if (strcmp(argv[1], "--ping") == 0) {
        setup_dir();
        return send_and_wait(argc > 2 ? argv[2] : "id -u\necho BRIDGE_OK", 8000) == 0 ? 0 : 1;
    }
    if (strcmp(argv[1], "--sign") == 0 && argc >= 5) {
        char msg[1024], hex[65];
        unsigned char mac[32];
        snprintf(msg, sizeof(msg), "%s\n%s\n%s", argv[2], argv[3], argv[4]);
        hmac_sha256(BRIDGE_KEY, msg, strlen(msg), mac);
        hex32(mac, hex);
        printf("%s\n", hex);
        return 0;
    }
    printf("用法:\n  %s              守护模式\n  %s --check     自检\n  %s --ping [cmd] 通路测试\n"
           "  %s --sign ts nonce cmd   打印签名\n"
           "自愈子命令:\n"
           "  %s --status       配置/熔断/目标包状态\n"
           "  %s --diagnose     只读诊断（不动作）\n"
           "  %s --fix [pkg]    立即修复（备份+删覆盖+软重启）\n"
           "  %s --reset-halt   清除熔断、计数归零\n"
           "  %s --tick-now     跳过节流立即跑一轮自检\n",
           argv[0], argv[0], argv[0], argv[0],
           argv[0], argv[0], argv[0], argv[0], argv[0]);
    return 2;
}
