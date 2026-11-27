/*
 * camellia_sock.c —— camd <-> App 的 unix domain socket 通道（方案A）
 *
 * 【为什么还要一条 socket 通道】
 * UI 通道建在 /sdcard/Android/data/<pkg>/files（FUSE）上，FUSE 不支持可靠的
 * inotify，所以 camd 必须每秒 fopen(camd_cmd) 才能保证指令延迟 ≤1s —— 一天
 * 8.6 万次跨进程往返，99.99% 只是在确认「没有新指令」。拿到 root 后加的 ext4
 * 快通道（/data/adb/camellia/cmd）把命令方向降到零轮询，但 App 读状态仍在读
 * FUSE 文件，UI 刷新侧还有周期读盘。
 *
 * 本文件再加一条双向 socket 通道，把「命令 + 状态」收进一条长连接：
 *   camd -> App   状态变化时主动推送（ui_dump 5s 节流 + 内容变化检测之后）
 *   App -> camd   命令按行写入，落成 CAM_DIR/cmd 复用救援侧同一条解析链
 *
 * 【socket 放哪】
 * /data/data/<pkg>/files/camd.sock（App 私有目录，ext4）。不用 abstract
 * namespace：文件路径便于排查，且目录属主就是 App。camd 以 root 创建后
 * chmod 0666 —— socket 文件的权限位只约束 connect，真正拦人的是 SELinux 的
 * unix_stream_socket connectto 与 file 读权限。若未放行，connect() 报 EACCES，
 * App 侧自动回退文件通道，可用性不受影响。
 *
 * 【协议】
 * 帧 = "<长度>\n<payload>"，payload 是 ui_dump 生成的多行 key=value。
 * 用长度前缀而非行边界：payload 自身含换行，行边界无法定帧。
 * App -> camd 方向相反，直接用 '\n' 分行（命令都是单行 key=value）。
 */
#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define SOCK_MAX_CLI 4
#define SOCK_RBUF    4096

extern const char *cam_dir_root(void);
extern void rescue_poll_cmd(void);

static int  g_srv = -1;
static int  g_cli[SOCK_MAX_CLI] = { -1, -1, -1, -1 };
static char g_path[300];
static long g_retry_at = 0;
static char g_last[3072];
static size_t g_last_len = 0;

static long mono_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long) ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/* App 私有 files 目录。camd 以 root 运行可直接访问；$CAM_APP_DIR 供调试覆盖。 */
static const char *app_files_dir(void) {
    const char *e = getenv("CAM_APP_DIR");
    if (e && e[0]) return e;
    return "/data/data/com.hyper.Camellia/files";
}

static void cli_close(int idx) {
    if (idx < 0 || idx >= SOCK_MAX_CLI) return;
    if (g_cli[idx] >= 0) {
        close(g_cli[idx]);
        g_cli[idx] = -1;
    }
}

static int send_frame(int fd, const char *payload, size_t len) {
    char hdr[32];
    struct iovec iov[2];
    struct msghdr mh;
    int hl;

    hl = snprintf(hdr, sizeof(hdr), "%zu\n", len);
    memset(&mh, 0, sizeof(mh));
    iov[0].iov_base = hdr;
    iov[0].iov_len  = (size_t) hl;
    iov[1].iov_base = (void *) payload;
    iov[1].iov_len  = len;
    mh.msg_iov = iov;
    mh.msg_iovlen = 2;
    return (int) sendmsg(fd, &mh, MSG_NOSIGNAL);
}

/*
 * 建监听。失败（App 还没跑过、目录不存在、SELinux 拒绝）时 5s 后重试，
 * 不能每次主循环都 socket()/bind() 一次 —— 那本身就是新的空转。
 */
int camd_sock_listen(void) {
    struct sockaddr_un sa;
    struct stat st;
    long now;

    if (g_srv >= 0) return g_srv;

    now = mono_ms();
    if (g_retry_at && now < g_retry_at) return -1;

    if (stat(app_files_dir(), &st) != 0 || !S_ISDIR(st.st_mode)) {
        g_retry_at = now + 5000;   /* App 尚未启动/已卸载 */
        return -1;
    }

    snprintf(g_path, sizeof(g_path), "%s/camd.sock", app_files_dir());
    if (strlen(g_path) >= sizeof(sa.sun_path)) {
        g_retry_at = now + 60000;
        return -1;
    }

    /* 清掉上次异常退出残留的 socket 文件，否则 bind 报 EADDRINUSE */
    if (stat(g_path, &st) == 0 && S_ISSOCK(st.st_mode)) unlink(g_path);

    g_srv = socket(AF_UNIX, SOCK_STREAM, 0);
    if (g_srv < 0) {
        g_retry_at = now + 5000;
        return -1;
    }

    memset(&sa, 0, sizeof(sa));
    sa.sun_family = AF_UNIX;
    /* 长度前面已校验 < sizeof(sun_path)，直接精确拷贝（含结尾 NUL） */
    memcpy(sa.sun_path, g_path, strlen(g_path) + 1);

    if (bind(g_srv, (struct sockaddr *) &sa, sizeof(sa)) != 0 ||
        listen(g_srv, 4) != 0) {
        close(g_srv);
        g_srv = -1;
        g_retry_at = now + 5000;
        return -1;
    }

    /* 0666：socket 文件的权限位只影响 connect，真正拦截的是 SELinux。
     * App 与 camd 不同 uid，0600 会让 App 连不上（EACCES）。 */
    chmod(g_path, 0666);
    fcntl(g_srv, F_SETFL, fcntl(g_srv, F_GETFL) | O_NONBLOCK);
    fprintf(stderr, "camd: sock listen %s\n", g_path);
    return g_srv;
}

int camd_sock_fd(void) {
    return g_srv;
}

int camd_sock_active(void) {
    int i, n = 0;
    for (i = 0; i < SOCK_MAX_CLI; i++) if (g_cli[i] >= 0) n++;
    return n;
}

/* 只把「已连接的客户端」填进 pfds，监听 fd 由调用方自己占位。 */
int camd_sock_fill(struct pollfd *pfds, int max) {
    int i, n = 0;
    if (!pfds || max <= 0) return 0;
    for (i = 0; i < SOCK_MAX_CLI && n < max; i++) {
        if (g_cli[i] >= 0) {
            pfds[n].fd = g_cli[i];
            pfds[n].events = POLLIN;
            pfds[n].revents = 0;
            n++;
        }
    }
    return n;
}

static void on_accept(void) {
    int fd, i;

    for (;;) {
        fd = accept(g_srv, NULL, NULL);
        if (fd < 0) return;   /* EAGAIN：已排空 */

        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
        for (i = 0; i < SOCK_MAX_CLI; i++) if (g_cli[i] < 0) break;
        if (i == SOCK_MAX_CLI) {
            /* 槽位满：踢掉最早的一个（App 重启会留下半死连接） */
            cli_close(0);
            for (i = 0; i < SOCK_MAX_CLI - 1; i++) g_cli[i] = g_cli[i + 1];
            i = SOCK_MAX_CLI - 1;
        }
        g_cli[i] = fd;

        /* 对端身份：跨机型排查时这是最硬的证据 —— 能直接看出「是谁连上来的」。
         * 正常情况下应为 App 的 uid（untrusted_app，非 0）；若 uid=0 说明是某个
         * root 进程连的（多半是自测脚本），不能证明 App 侧 SELinux 已放行。 */
        {
            struct ucred cr;
            socklen_t cl = sizeof(cr);
            if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cr, &cl) == 0)
                fprintf(stderr, "camd: sock peer pid=%d uid=%d gid=%d\n",
                        (int) cr.pid, (int) cr.uid, (int) cr.gid);
            else
                fprintf(stderr, "camd: sock peer unknown errno=%d\n", errno);
        }

        /* 新连接立刻补一份最新状态，否则 UI 要等下一次状态变化才有数据 */
        if (g_last_len) (void) send_frame(fd, g_last, g_last_len);
    }
}

static void on_read(int fd) {
    char buf[SOCK_RBUF];
    ssize_t n;
    int i;

    n = recv(fd, buf, sizeof(buf) - 1, 0);
    if (n <= 0) {
        for (i = 0; i < SOCK_MAX_CLI; i++) if (g_cli[i] == fd) cli_close(i);
        return;
    }
    buf[n] = 0;

    /* 命令落成快通道 cmd 文件：复用 rescue_poll_cmd 里已有的
     * 白名单校验 / 单次执行 / 限流逻辑，不另造一套解析。 */
    {
        char cp[320];
        char *p, *nl;
        FILE *f;

        snprintf(cp, sizeof(cp), "%s/cmd", cam_dir_root());
        f = fopen(cp, "a");
        if (!f) return;
        for (p = buf; p && *p; p = nl ? nl + 1 : NULL) {
            nl = strchr(p, '\n');
            if (nl) *nl = 0;
            if (*p && strchr(p, '=')) fprintf(f, "%s\n", p);
        }
        fclose(f);
        rescue_poll_cmd();
    }
}

void camd_sock_accept(void) {
    if (g_srv >= 0) on_accept();
}

void camd_sock_read(int fd) {
    on_read(fd);
}

/* 状态推送：ui_dump 检测到内容变化时调用。同时缓存一份给后连上的客户端。 */
void camd_sock_broadcast(const char *payload, size_t len) {
    int i;

    if (!payload || !len || len > sizeof(g_last)) return;
    memcpy(g_last, payload, len);
    g_last_len = len;

    for (i = 0; i < SOCK_MAX_CLI; i++) {
        if (g_cli[i] >= 0 && send_frame(g_cli[i], payload, len) < 0)
            cli_close(i);   /* EPIPE/ENOTCONN：对端已走 */
    }
}
