/*
 * camellia_dsnap.c —— camd 的「定时快照」模块（桌面布局）
 *
 * 设计要点（D：事件驱动 + 间隔下限）：
 *   · 平时零动作：inotify 盯桌面布局库，只有库被写过才置 dirty；
 *   · 三重门控：内容真变了(digest) 且 距上次成功 >= N 天 且 当前不忙(救砖) 才备份；
 *   · 一致性快照：嵌入式 SQLite 对主库/抽屉库做 VACUUM INTO（含 WAL 已提交内容）；
 *   · 幂等去重：只对 favorites/screens 的「排版列」算指纹，排除 launchCount 等易变列；
 *   · 只删自动份：prune 只淘汰 src=auto，手动/pre-restore 不删。
 *
 * 与 camd 其它部分的耦合极小：
 *   · rescue_tick 每轮调一次 dsnap_heartbeat()（30s 节拍内）；
 *   · ui_apply 遇到 dsnap_* 命令转 dsnap_ui_apply()；
 *   · ui_dump 用 dsnap_status_fields() 追加状态字段；
 *   · 根目录复用 extern cam_dir_root()。
 *
 * 存储：<root>/dsnap/<id>/{meta,launcher.db,drawer.db,prefs.xml}
 * 配置：<root>/dsnap.conf（行式 key=value，热读）
 * 状态：<root>/state/dsnap_*（复用 camd state 目录，前缀 dsnap_ 不冲突）
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
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/inotify.h>
#include <sys/wait.h>
#include <signal.h>
#include <ftw.h>
#include "sqlite3.h"

/* 由 camellia_rescue.c 提供的非 static 根目录 */
extern const char *cam_dir_root(void);
extern int camd_global_ns;   /* camd 是否已切到 init 全局 mount ns */
static void dsnap_write_list(void);   /* 备份/删除后刷新 UI 快照列表 */
extern void rescue_after_own_restart(void);  /* 重置 target pid 基线，避免自己动作被计崩溃 */

/* 桌面数据（OS4 实测路径） */
#define HOME_DB_DIR   "/data/user_de/0/com.miui.home/databases"
#define HOME_DB_SUB   "/data/user_de/0/com.miui.home/databases/drawer"
#define HOME_PREFS    "/data/user_de/0/com.miui.home/shared_prefs/launcher_sharedpreference.xml"

#define DAY_SEC 86400L

/* ------------------------------------------------------------------ 小工具 */
static void dsnap_log(const char *fmt, ...) {
    char p[512];
    snprintf(p, sizeof(p), "%s/state/dsnap.log", cam_dir_root());
    FILE *f = fopen(p, "a");
    if (!f) return;
    va_list ap; va_start(ap, fmt); vfprintf(f, fmt, ap); va_end(ap);
    fputc('\n', f); fclose(f);
}

static void dsnap_root(char *out, size_t n) {
    snprintf(out, n, "%s/dsnap", cam_dir_root());
}

static int mkdir_p(const char *path, mode_t mode) {
    char tmp[640];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') { *p = 0; mkdir(tmp, mode); *p = '/'; }
    }
    return mkdir(tmp, mode);
}

/* ---- state 读写（<root>/state/<key>，与 rescue 的 st_* 同目录不同前缀） */
static void dsnap_spath(const char *k, char *out, size_t n) {
    snprintf(out, n, "%s/state/%s", cam_dir_root(), k);
}
static long dsnap_sget(const char *k) {
    char p[512]; FILE *f; long v = 0;
    dsnap_spath(k, p, sizeof(p));
    f = fopen(p, "r"); if (!f) return 0;
    if (fscanf(f, "%ld", &v) != 1) v = 0;
    fclose(f); return v;
}
static void dsnap_sset(const char *k, long v) {
    char p[512], t[64]; FILE *f;
    dsnap_spath(k, p, sizeof(p));
    f = fopen(p, "r");
    if (f) { long old = 0; int ok = fscanf(f, "%ld", &old) == 1; fclose(f); if (ok && old == v) return; }
    f = fopen(p, "w"); if (!f) return;
    fprintf(f, "%ld\n", v); fclose(f);
    (void)t;
}
static int dsnap_sstr(const char *k, char *out, size_t n) {
    char p[512]; FILE *f;
    dsnap_spath(k, p, sizeof(p));
    out[0] = 0; f = fopen(p, "r"); if (!f) return -1;
    if (!fgets(out, (int)n, f)) out[0] = 0;
    fclose(f);
    size_t L = strlen(out); while (L && (out[L-1] == '\n' || out[L-1] == '\r')) out[--L] = 0;
    return out[0] ? 0 : -1;
}
static void dsnap_swstr(const char *k, const char *v) {
    char p[512]; FILE *f;
    dsnap_spath(k, p, sizeof(p));
    f = fopen(p, "w"); if (!f) return;
    fprintf(f, "%s\n", v ? v : ""); fclose(f);
}

/* ---- 简易 run / popen ---- */
static int run_sh(const char *cmd) {
    FILE *f = popen(cmd, "r");
    if (!f) return -1;
    char b[256]; while (fgets(b, sizeof(b), f)) { }
    int rc = pclose(f);
    return rc;
}

/* ---- 救砖是否在进行（让路判据，直接读 rescue 的 state 文件） ---- */
static int dsnap_rescue_busy(void) {
    char p[512];
    snprintf(p, sizeof(p), "%s/state/halt", cam_dir_root());
    if (access(p, F_OK) == 0) return 1;
    if (dsnap_sget("sr_ts") > 0) return 1;
    if (dsnap_sget("verify_ts") > 0) return 1;
    return 0;
}

/* ------------------------------------------------------------------ 配置 */
typedef struct {
    int  enabled;
    int  days;
    int  hour;
    int  keep;
    char kinds[128];
} dcfg;
static void dcfg_default(dcfg *c) {
    c->enabled = 0; c->days = 3; c->hour = 3; c->keep = 5;
    snprintf(c->kinds, sizeof(c->kinds), "launcher theme wallpaper");
}
static void dcfg_path(char *out, size_t n) { snprintf(out, n, "%s/dsnap.conf", cam_dir_root()); }
static long g_cfg_mt = -2; static long g_cfg_sz = -2; static int g_cfg_ok = 0; static dcfg g_cfg_cache;
static void dcfg_load(dcfg *c) {
    char p[512]; struct stat st;
    dcfg_path(p, sizeof(p));
    if (stat(p, &st) == 0 && g_cfg_ok && st.st_mtime == g_cfg_mt && (long)st.st_size == g_cfg_sz) {
        *c = g_cfg_cache; return;
    }
    dcfg_default(c);
    FILE *f = fopen(p, "r");
    if (f) {
        char line[256];
        while (fgets(line, sizeof(line), f)) {
            char *eq = strchr(line, '='); if (!eq) continue; *eq = 0;
            char *k = line; char *v = eq + 1;
            size_t L = strlen(v); while (L && (v[L-1] == '\n' || v[L-1] == '\r')) v[--L] = 0;
            if (!strcmp(k, "enabled")) c->enabled = atoi(v) ? 1 : 0;
            else if (!strcmp(k, "days")) { int x = atoi(v); if (x >= 1 && x <= 30) c->days = x; }
            else if (!strcmp(k, "hour")) { int x = atoi(v); if (x >= 0 && x <= 23) c->hour = x; }
            else if (!strcmp(k, "keep")) { int x = atoi(v); if (x >= 1 && x <= 10) c->keep = x; }
            else if (!strcmp(k, "kinds")) snprintf(c->kinds, sizeof(c->kinds), "%s", v);
        }
        fclose(f);
    }
    g_cfg_cache = *c; g_cfg_ok = 1;
    if (stat(p, &st) == 0) { g_cfg_mt = st.st_mtime; g_cfg_sz = (long)st.st_size; }
}
static void dcfg_save(const dcfg *c) {
    char p[512]; FILE *f;
    dcfg_path(p, sizeof(p));
    { char _r[520]; snprintf(_r, sizeof(_r), "%s", cam_dir_root()); mkdir_p(_r, 0700); }
    f = fopen(p, "w"); if (!f) return;
    fprintf(f, "enabled=%d\n", c->enabled);
    fprintf(f, "days=%d\n", c->days);
    fprintf(f, "hour=%d\n", c->hour);
    fprintf(f, "keep=%d\n", c->keep);
    fprintf(f, "kinds=%s\n", c->kinds);
    fclose(f);
    chmod(p, 0600);
}

/* ------------------------------------------------------------------ SHA-256 */
typedef struct { uint32_t h[8]; uint64_t len; uint8_t buf[64]; size_t n; } sha_ctx;
static const uint32_t SHK[64] = {
 0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
 0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
 0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
 0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
 0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
 0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
 0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
 0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
static uint32_t ror(uint32_t x,int n){return (x>>n)|(x<<(32-n));}
static void sha_block(sha_ctx*c,const uint8_t*p){
    uint32_t w[64];
    for(int i=0;i<16;i++) w[i]=(p[i*4]<<24)|(p[i*4+1]<<16)|(p[i*4+2]<<8)|p[i*4+3];
    for(int i=16;i<64;i++){uint32_t s0=ror(w[i-15],7)^ror(w[i-15],18)^(w[i-15]>>3);uint32_t s1=ror(w[i-2],17)^ror(w[i-2],19)^(w[i-2]>>10);w[i]=w[i-16]+s0+w[i-7]+s1;}
    uint32_t a=c->h[0],b=c->h[1],cc=c->h[2],d=c->h[3],e=c->h[4],f=c->h[5],g=c->h[6],hh=c->h[7];
    for(int i=0;i<64;i++){uint32_t S1=ror(e,6)^ror(e,11)^ror(e,25);uint32_t ch=(e&f)^((~e)&g);uint32_t t1=hh+S1+ch+SHK[i]+w[i];uint32_t S0=ror(a,2)^ror(a,13)^ror(a,22);uint32_t mj=(a&b)^(a&cc)^(b&cc);uint32_t t2=S0+mj;hh=g;g=f;f=e;e=d+t1;d=cc;cc=b;b=a;a=t1+t2;}
    c->h[0]+=a;c->h[1]+=b;c->h[2]+=cc;c->h[3]+=d;c->h[4]+=e;c->h[5]+=f;c->h[6]+=g;c->h[7]+=hh;
}
static void sha_init(sha_ctx*c){c->h[0]=0x6a09e667;c->h[1]=0xbb67ae85;c->h[2]=0x3c6ef372;c->h[3]=0xa54ff53a;c->h[4]=0x510e527f;c->h[5]=0x9b05688c;c->h[6]=0x1f83d9ab;c->h[7]=0x5be0cd19;c->len=0;c->n=0;}
static void sha_upd(sha_ctx*c,const void*data,size_t len){const uint8_t*p=data;c->len+=len;while(len){size_t t=64-c->n;if(t>len)t=len;memcpy(c->buf+c->n,p,t);c->n+=t;p+=t;len-=t;if(c->n==64){sha_block(c,c->buf);c->n=0;}}}
static void sha_fin(sha_ctx*c,char hex[65]){uint64_t bits=c->len*8;uint8_t pad=0x80;sha_upd(c,&pad,1);uint8_t z=0;while(c->n!=56)sha_upd(c,&z,1);uint8_t lb[8];for(int i=0;i<8;i++)lb[i]=(uint8_t)(bits>>(56-i*8));sha_upd(c,lb,8);for(int i=0;i<8;i++)sprintf(hex+i*8,"%08x",c->h[i]);hex[64]=0;}

static int sha_file(const char *path, char hex[65]) {
    FILE *f = fopen(path, "rb"); if (!f) return -1;
    sha_ctx c; sha_init(&c);
    char buf[8192]; size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) sha_upd(&c, buf, n);
    fclose(f); sha_fin(&c, hex); return 0;
}

/* ------------------------------------------------------------------ 库发现 */
/* 在 dir 下找第一个 launcher*.db（普通文件） */
static int find_launcher_db(const char *dir, char *out, size_t n) {
    DIR *d = opendir(dir); struct dirent *e;
    if (!d) return -1;
    while ((e = readdir(d))) {
        const char *nm = e->d_name;
        if (strncmp(nm, "launcher", 8) != 0) continue;
        size_t L = strlen(nm);
        if (L < 4 || strcmp(nm + L - 3, ".db") != 0) continue;
        char full[700]; snprintf(full, sizeof(full), "%s/%s", dir, nm);
        struct stat st; if (stat(full, &st) != 0 || !S_ISREG(st.st_mode)) continue;
        snprintf(out, n, "%s", full); closedir(d); return 0;
    }
    closedir(d); return -1;
}

/* ------------------------------------------------------------------ 排版摘要 */
static void digest_db(const char *dbpath, sha_ctx *c) {
    sqlite3 *h;
    if (sqlite3_open_v2(dbpath, &h, SQLITE_OPEN_READWRITE, NULL) != SQLITE_OK) { if(h)sqlite3_close(h); return; }
    sqlite3_busy_timeout(h, 3000);
    const char *q1 =
      "SELECT _id,title,intent,container,screen,cellX,cellY,spanX,spanY,"
      "itemType,appWidgetId,isShortcut,profileId,label,appWidgetProvider,iconPackage,iconResource "
      "FROM favorites ORDER BY _id";
    const char *q2 = "SELECT _id,title,screenOrder,screenType FROM screens ORDER BY _id";
    const char *qs[2] = { q1, q2 };
    for (int qi = 0; qi < 2; qi++) {
        sqlite3_stmt *st;
        if (sqlite3_prepare_v2(h, qs[qi], -1, &st, NULL) != SQLITE_OK) continue;
        while (sqlite3_step(st) == SQLITE_ROW) {
            int nc = sqlite3_column_count(st);
            for (int i = 0; i < nc; i++) {
                const unsigned char *t = sqlite3_column_text(st, i);
                if (t) sha_upd(c, t, strlen((const char *)t));
                sha_upd(c, "|", 1);
            }
            sha_upd(c, "\n", 1);
        }
        sqlite3_finalize(st);
    }
    sqlite3_close(h);
}

/* 返回 0=成功算出 digest；把主库+抽屉库的排版列 + prefs 内容一起哈希 */
static int dsnap_digest(char out[65]) {
    sha_ctx c; sha_init(&c);
    char db[700];
    if (find_launcher_db(HOME_DB_DIR, db, sizeof(db)) == 0) digest_db(db, &c);
    else sha_upd(&c, "nomain;", 7);
    if (find_launcher_db(HOME_DB_SUB, db, sizeof(db)) == 0) digest_db(db, &c);
    else sha_upd(&c, "nodrawer;", 9);
    /* 不要把 prefs.xml 整文件纳入布局指纹：它被桌面频繁重写（时间戳/计数等易变项），
     * 会把"布局没变"误判成变了，导致永不跳过。真正的布局变化（含网格变化引起的
     * 图标重排）都体现在上面两库 favorites/screens 排版列里，digest_db 已覆盖。 */
    sha_fin(&c, out);
    return 0;
}

/* ------------------------------------------------------------------ VACUUM */
static int dsnap_vacuum(const char *dbpath, const char *outpath) {
    sqlite3 *h;
    if (sqlite3_open_v2(dbpath, &h, SQLITE_OPEN_READWRITE, NULL) != SQLITE_OK) { if(h)sqlite3_close(h); return -1; }
    sqlite3_busy_timeout(h, 3000);
    unlink(outpath);
    char *sql = sqlite3_mprintf("VACUUM INTO %Q", outpath);
    int rc = sqlite3_exec(h, sql, NULL, NULL, NULL);
    sqlite3_free(sql); sqlite3_close(h);
    return rc == SQLITE_OK ? 0 : -1;
}

/* ------------------------------------------------------------------ 快照目录 / meta */
static int copy_file(const char *src, const char *dst) {
    FILE *in = fopen(src, "rb"); if (!in) return -1;
    FILE *out = fopen(dst, "wb"); if (!out) { fclose(in); return -1; }
    char buf[8192]; size_t n; int ok = 0;
    while ((n = fread(buf,1,sizeof(buf),in)) > 0) if (fwrite(buf,1,n,out) != n) { ok = -1; break; }
    fclose(in); fclose(out); return ok;
}

/* 用 src 覆盖 dst，但保留 dst 原属主/权限，避免 root 覆盖后桌面读不了自己的库 */
static int copy_into_existing(const char *src, const char *dst) {
    FILE *in = fopen(src, "rb"); if (!in) return -1;
    int fd = open(dst, O_WRONLY | O_TRUNC);   /* 只覆盖已存在文件：保留 inode/属主/上下文 */
    if (fd < 0) { fclose(in); return -1; }
    char buf[8192]; size_t n; int ok = 0;
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
        if (write(fd, buf, n) != (ssize_t)n) { ok = -1; break; }
    }
    fsync(fd); close(fd); fclose(in);
    return ok;
}

/* ---- 进程内工具：递归删 / 查杀进程 / 拉起桌面 / 拷贝树（不再 fork sh） ---- */
static int rm_cb(const char *fp, const struct stat *sb, int tf, struct FTW *fb) {
    (void)sb; (void)fb;
    return (tf == FTW_DP) ? rmdir(fp) : unlink(fp);
}
static int rm_tree(const char *path) { return nftw(path, rm_cb, 32, FTW_DEPTH | FTW_PHYS); }

/* 在 /proc 里找某 comm 的 pid（返回第一个，0=没有） */
static int proc_find(const char *comm) {
    DIR *d = opendir("/proc"); if (!d) return 0; struct dirent *e; int found = 0;
    for (e = readdir(d); e; e = readdir(d)) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        /* 先看 cmdline 的 argv0（更可靠，等价 pidof） */
        char cp[64]; snprintf(cp, sizeof(cp), "/proc/%s/cmdline", e->d_name);
        char buf[256]; int n = -1; FILE *f = fopen(cp, "rb");
        if (f) { n = (int)fread(buf, 1, sizeof(buf) - 1, f); fclose(f); }
        if (n > 0) {
            buf[n] = 0; const char *base = strrchr(buf, '/'); base = base ? base + 1 : buf;
            if (!strcmp(base, comm)) { found = atoi(e->d_name); break; }
        }
        /* 再退回 comm */
        snprintf(cp, sizeof(cp), "/proc/%s/comm", e->d_name);
        f = fopen(cp, "r");
        if (f) { char nm[128] = ""; if (fgets(nm, sizeof(nm), f)) { size_t L=strlen(nm); while(L&&nm[L-1]=='\n')nm[--L]=0; } fclose(f);
                 if (!strcmp(nm, comm)) { found = atoi(e->d_name); break; } }
    }
    closedir(d); return found;
}
static void kill_home(void) {
    for (int i = 0; i < 20; i++) { int pid = proc_find("com.miui.home"); if (pid <= 0) break; kill(pid, SIGKILL); usleep(30000); }
}
static void ensure_home(void) {
    for (int i = 0; i < 30; i++) { if (proc_find("com.miui.home") > 0) return; usleep(100000); }
    pid_t p = fork();               /* AMS 3s 内没拉起才兜底一次 */
    if (p == 0) { execl("/system/bin/am","am","start","-a","android.intent.action.MAIN","-c","android.intent.category.HOME",(char*)NULL); _exit(127); }
}
static int copy_tree(const char *src, const char *dst) {
    mkdir_p(dst, 0755);
    DIR *d = opendir(src); if (!d) return -1; struct dirent *e;
    while ((e = readdir(d))) {
        if (!strcmp(e->d_name,".") || !strcmp(e->d_name,"..")) continue;
        char sp[700], dp[760]; snprintf(sp,sizeof(sp),"%s/%s",src,e->d_name); snprintf(dp,sizeof(dp),"%s/%s",dst,e->d_name);
        struct stat st; if (stat(sp,&st) != 0) continue;
        if (S_ISDIR(st.st_mode)) copy_tree(sp, dp); else copy_file(sp, dp);
    }
    closedir(d); return 0;
}

static void now_id(char id[32]) {
    time_t t = time(NULL); struct tm tm; localtime_r(&t, &tm);
    strftime(id, 32, "%Y%m%d_%H%M%S", &tm);
}

/* 统计快照份数 */
static int dsnap_count(void) {
    char root[512]; dsnap_root(root, sizeof(root));
    DIR *d = opendir(root); struct dirent *e; int n = 0;
    if (!d) return 0;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        char meta[700]; snprintf(meta, sizeof(meta), "%s/%s/meta", root, e->d_name);
        if (access(meta, F_OK) == 0) n++;
    }
    closedir(d); return n;
}

/* 读取某快照 meta 里 src= 值 */
static void snap_src(const char *root, const char *id, char *out, size_t n) {
    char p[700]; snprintf(p, sizeof(p), "%s/%s/meta", root, id);
    out[0] = 0; FILE *f = fopen(p, "r"); if (!f) return;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        if (!strncmp(line, "src=", 4)) { snprintf(out, n, "%s", line + 4); break; }
    }
    fclose(f);
    size_t L = strlen(out); while (L && (out[L-1]=='\n'||out[L-1]=='\r')) out[--L]=0;
}

/* 硬上限：超过 keep 份即删最旧的（不论来源），最多保留 keep 份 */
static int dsnap_prune(int keep) {
    char root[512]; dsnap_root(root, sizeof(root));
    if (keep <= 0) keep = 5;
    char ids[64][40]; int n = 0;
    DIR *d = opendir(root); struct dirent *e;
    if (!d) return 0;
    while ((e = readdir(d)) && n < 64) {
        if (e->d_name[0] == '.') continue;
        char meta[700]; snprintf(meta, sizeof(meta), "%s/%s/meta", root, e->d_name);
        if (access(meta, F_OK) == 0) snprintf(ids[n++], 40, "%s", e->d_name);
    }
    closedir(d);
    /* 目录名 = YYYYMMDD_HHMMSS，字典序即时间序；升序排 */
    for (int i = 1; i < n; i++) { char t[40]; snprintf(t,sizeof(t),"%s",ids[i]); int j=i-1; while(j>=0&&strcmp(ids[j],t)>0){snprintf(ids[j+1],40,"%s",ids[j]);j--;} snprintf(ids[j+1],40,"%s",t); }
    int need = n - keep, deleted = 0;
    for (int i = 0; i < n && need > 0; i++) {   /* ids 升序=最旧在前：硬上限，删最旧 */
        char pth[800]; snprintf(pth, sizeof(pth), "%s/%s", root, ids[i]); rm_tree(pth); need--; deleted++;
    }
    return deleted;
}

/* 清理没有 launcher.db / 0 字节的失败快照 */
static void dsnap_clean_failed(void) {
    char root[512]; dsnap_root(root, sizeof(root));
    DIR *d = opendir(root); struct dirent *e; if (!d) return;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        char lb[720]; snprintf(lb, sizeof(lb), "%s/%s/launcher.db", root, e->d_name);
        struct stat st;
        if (stat(lb, &st) != 0 || st.st_size == 0) {
            char pth[800]; snprintf(pth, sizeof(pth), "%s/%s", root, e->d_name); rm_tree(pth);
        }
    }
    closedir(d);
}

/* ------------------------------------------------------------------ 生成快照 */
static long dir_size(const char *path) {
    struct stat st; if (stat(path, &st) != 0) return 0;
    if (!S_ISDIR(st.st_mode)) return (long)st.st_size;
    long tot = 0; DIR *d = opendir(path); if (!d) return 0; struct dirent *e; char p[900];
    while ((e = readdir(d))) { if (!strcmp(e->d_name,".")||!strcmp(e->d_name,"..")) continue; snprintf(p,sizeof(p),"%s/%s",path,e->d_name); tot += dir_size(p); }
    closedir(d); return tot;
}
static long dir_size_join(const char *base, const char *sub) { char p[900]; snprintf(p,sizeof(p),"%s/%s",base,sub); return dir_size(p); }
static int kind_has(const char *kinds, const char *k) {
    if (!kinds || !*kinds || !k || !*k) return 0;
    const char *p = kinds; size_t kl = strlen(k);
    while (*p) {
        while (*p == ' ' || *p == ',' || *p == '\t') p++;
        const char *q = p; while (*p && *p != ' ' && *p != ',' && *p != '\t') p++;
        if ((size_t)(p - q) == kl && !strncmp(q, k, kl)) return 1;
    }
    return 0;
}

/* ---- 变更指纹：主题/壁纸用元数据(size+mtime)聚合；壁纸 info.xml 用 md5 ---- */
static void fp_walk(const char *base, const char *cur, sha_ctx *c) {
    DIR *d = opendir(cur); if (!d) return; struct dirent *e; char p[1024];
    while ((e = readdir(d))) {
        if (!strcmp(e->d_name,".") || !strcmp(e->d_name,"..")) continue;
        snprintf(p, sizeof(p), "%s/%s", cur, e->d_name);
        struct stat st; if (lstat(p, &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) fp_walk(base, p, c);
        else {
            const char *rel = p; size_t bl = strlen(base);
            if (!strncmp(p, base, bl)) rel = p + bl; if (*rel == '/') rel++;
            char b[1200]; int n = snprintf(b, sizeof(b), "%s|%lld|%lld\n", rel, (long long)st.st_size, (long long)st.st_mtime);
            if (n > 0) sha_upd(c, b, (size_t)n);
        }
    }
    closedir(d);
}
static void fp_addfile(sha_ctx *c, const char *path, int content) {
    struct stat st; if (lstat(path, &st) != 0 || !S_ISREG(st.st_mode)) return; char b[1400];
    if (content) { char h[65]; if (sha_file(path, h) == 0) { int n=snprintf(b,sizeof(b),"%s|md5:%s\n",path,h); sha_upd(c,b,(size_t)n); return; } }
    int n = snprintf(b, sizeof(b), "%s|%lld|%lld\n", path, (long long)st.st_size, (long long)st.st_mtime); sha_upd(c, b, (size_t)n);
}
static void fp_theme(char out[65]) { sha_ctx c; sha_init(&c); fp_walk("/data/system/theme","/data/system/theme",&c); sha_fin(&c,out); }
static void fp_wp(char out[65]) {
    sha_ctx c; sha_init(&c); const char *wr = "/data/system/theme_magic/users/0/wallpaper";
    const char *subs[] = {"gallery","image","default","dark","depth_blur","data","super_save_power","super_wallpaper","maml","sensor",0};
    for (int i=0; subs[i]; i++){ char p[900]; snprintf(p,sizeof(p),"%s/%s",wr,subs[i]); struct stat st; if (stat(p,&st)==0 && S_ISDIR(st.st_mode)) fp_walk(p,p,&c); }
    fp_addfile(&c,"/data/system/users/0/wallpaper",0);
    fp_addfile(&c,"/data/system/users/0/wallpaper_orig",0);
    fp_addfile(&c,"/data/system/users/0/wallpaper_lock",0);
    fp_addfile(&c,"/data/system/users/0/wallpaper_lock_orig",0);
    fp_addfile(&c,"/data/system/users/0/blurwallpaper",0);
    fp_addfile(&c,"/data/system/users/0/wallpaper_info.xml",1);
    sha_fin(&c,out);
}
static void fp_wpv(char out[65]) {
    sha_ctx c; sha_init(&c); const char *wr = "/data/system/theme_magic/users/0/wallpaper";
    const char *vs[] = {"video","video_gallery","linkage_video","video_depth_editor",0};
    for (int i=0; vs[i]; i++){ char p[900]; snprintf(p,sizeof(p),"%s/%s",wr,vs[i]); struct stat st; if (stat(p,&st)==0 && S_ISDIR(st.st_mode)) fp_walk(p,p,&c); }
    sha_fin(&c,out);
}
/* 忙碌标记：只在长任务期间存在，App 读它显示"备份中" */
static void dsnap_busy_mark(const char *kind) {
    FILE *f = fopen("/sdcard/Android/data/com.hyper.Camellia/files/camd_busy","w");
    if (f) { fprintf(f,"ts=%ld\nkind=%s\n",(long)time(NULL), kind); fclose(f); }
}
static void dsnap_busy_clear(void) { unlink("/sdcard/Android/data/com.hyper.Camellia/files/camd_busy"); }
/* 未变单元：从最近一份含该文件的快照硬链接过来；成功返回1 */
static int reuse_prev(const char *root, const char *curid, const char *dir, const char *fname) {
    DIR *d = opendir(root); if (!d) return 0; struct dirent *e; char best[40] = "";
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue; if (!strcmp(e->d_name, curid)) continue;
        char m[840]; snprintf(m,sizeof(m),"%s/%s/meta",root,e->d_name); if (access(m,F_OK)!=0) continue;
        char p[840]; snprintf(p,sizeof(p),"%s/%s/%s",root,e->d_name,fname); if (access(p,F_OK)!=0) continue;
        if (!best[0] || strcmp(e->d_name,best) > 0) snprintf(best,sizeof(best),"%s",e->d_name);
    }
    closedir(d);
    if (!best[0]) return 0;
    char src[840], dst[820]; snprintf(src,sizeof(src),"%s/%s/%s",root,best,fname); snprintf(dst,sizeof(dst),"%s/%s",dir,fname);
    return (link(src,dst)==0) ? 1 : 0;
}

static int dsnap_run(int src_manual) {
    if (!camd_global_ns) { dsnap_log("DSNAP skip: global ns unavailable"); return -1; }
    dcfg c; dcfg_load(&c);
    char root[512]; dsnap_root(root, sizeof(root));
    mkdir_p(root, 0700);
    dsnap_clean_failed();

    int has_theme = kind_has(c.kinds, "theme");
    int has_wp    = kind_has(c.kinds, "wallpaper");
    int has_wpv   = kind_has(c.kinds, "wp_video");

    char dig[65]; dsnap_digest(dig);
    char lastd[65]; dsnap_sstr("dsnap_last_digest", lastd, sizeof(lastd));
    char fpt[65]={0}, fpw[65]={0}, fpv[65]={0}, lt[65]={0}, lw[65]={0}, lv[65]={0};
    if (has_theme) { fp_theme(fpt); dsnap_sstr("dsnap_fp_theme", lt, sizeof(lt)); }
    if (has_wp)    { fp_wp(fpw);    dsnap_sstr("dsnap_fp_wp", lw, sizeof(lw)); }
    if (has_wpv)   { fp_wpv(fpv);   dsnap_sstr("dsnap_fp_wpv", lv, sizeof(lv)); }

    int ch_layout = (strcmp(dig, lastd) != 0);
    int ch_theme  = has_theme && (strcmp(fpt, lt) != 0);
    int ch_wp     = has_wp    && (strcmp(fpw, lw) != 0);
    int ch_wpv    = has_wpv   && (strcmp(fpv, lv) != 0);

    if (!src_manual && !ch_layout && !ch_theme && !ch_wp && !ch_wpv) {
        dsnap_log("DSNAP skip: unchanged");
        dsnap_sset("dsnap_last_ok", (long)time(NULL));
        dsnap_swstr("dsnap_last_msg", "内容无变化，已跳过");
        return 0;
    }
    int build_theme = has_theme && (src_manual || ch_theme);
    int build_wp    = has_wp    && (src_manual || ch_wp);
    int build_wpv   = has_wpv   && (src_manual || ch_wpv);

    char id[32]; now_id(id);
    char dir[700]; snprintf(dir, sizeof(dir), "%s/%s", root, id);
    if (mkdir_p(dir, 0700) != 0 && access(dir, F_OK) != 0) { dsnap_log("DSNAP mkdir fail %s", dir); return -1; }

    dsnap_busy_mark("backup");
    int fail = 0;
    char db[700];
    if (find_launcher_db(HOME_DB_DIR, db, sizeof(db)) == 0) {
        char o[800]; snprintf(o, sizeof(o), "%s/launcher.db", dir);
        if (dsnap_vacuum(db, o) != 0) fail |= 1;
    } else { fail |= 1; }
    if (find_launcher_db(HOME_DB_SUB, db, sizeof(db)) == 0) {
        char o[800]; snprintf(o, sizeof(o), "%s/drawer.db", dir);
        if (dsnap_vacuum(db, o) != 0) fail |= 2;
    } else { fail |= 2; }
    { char o[800]; snprintf(o, sizeof(o), "%s/prefs.xml", dir); copy_file(HOME_PREFS, o); }

    if (has_theme) {
        int built = (!build_theme) ? reuse_prev(root, id, dir, "theme.tar.gz") : 0;
        if (!built) {
            char cmd[1200];
            snprintf(cmd, sizeof(cmd), "tar -czpf '%s/theme.tar.gz' -C / data/system/theme 2>'%s/theme.err'", dir, dir);
            run_sh(cmd);
            char _p[820]; struct stat _x; snprintf(_p,sizeof(_p),"%s/theme.tar.gz",dir);
            if (stat(_p,&_x)!=0 || _x.st_size==0) fail |= 4;
        }
        char sc[1800];
        snprintf(sc, sizeof(sc), "{ settings list secure 2>/dev/null | grep -E '^(theme_customization_overlay_packages|previous_font)=' | sed 's/^/secure:/'; settings list system 2>/dev/null | grep -E '^(font_scale|device_font_scale|is_theme_fod_animation)=' | sed 's/^/system:/'; } > '%s/theme_settings.txt' 2>/dev/null ; true", dir);
        run_sh(sc);
    }
    if (has_wp) {
        int built = (!build_wp) ? reuse_prev(root, id, dir, "wallpaper.tar.gz") : 0;
        if (!built) {
            char cmd[2400];
            snprintf(cmd, sizeof(cmd),
              "cd / && { for x in gallery image default dark depth_blur data super_save_power super_wallpaper maml sensor; do [ -e data/system/theme_magic/users/0/wallpaper/$x ] && echo data/system/theme_magic/users/0/wallpaper/$x; done;"
              " for f in data/system/users/0/wallpaper_info.xml data/system/users/0/wallpaper data/system/users/0/wallpaper_orig data/system/users/0/wallpaper_lock data/system/users/0/wallpaper_lock_orig data/system/users/0/blurwallpaper; do [ -e $f ] && echo $f; done; } | tar -czpf '%s/wallpaper.tar.gz' -T - 2>'%s/wp.err'",
              dir, dir);
            run_sh(cmd);
            char _p[820]; struct stat _x; snprintf(_p,sizeof(_p),"%s/wallpaper.tar.gz",dir);
            if (stat(_p,&_x)!=0 || _x.st_size==0) fail |= 5;
        }
        char sc[1800];
        snprintf(sc, sizeof(sc), "{ settings list secure 2>/dev/null | grep -E '^(flag_lock_wallpaper_type|wallpaper_changed_[0-9]+|wallpaper_effect_type_[0-9]+|home_wallpaper_scale_base|miui_wallpaper_content_type|super_wallpaper_linkage_state|aod_using_super_wallpaper)=' | sed 's/^/secure:/'; } > '%s/wp_settings.txt' 2>/dev/null ; true", dir);
        run_sh(sc);
    }
    if (has_wpv) {
        int built = (!build_wpv) ? reuse_prev(root, id, dir, "wp_video.tar.gz") : 0;
        if (!built) {
            char cmd[2000];
            snprintf(cmd, sizeof(cmd),
              "cd / && { for x in video video_gallery linkage_video video_depth_editor; do [ -e data/system/theme_magic/users/0/wallpaper/$x ] && echo data/system/theme_magic/users/0/wallpaper/$x; done; } | tar -czpf '%s/wp_video.tar.gz' -T - 2>'%s/wpv.err'",
              dir, dir);
            run_sh(cmd);
            char _p[820]; struct stat _x; snprintf(_p,sizeof(_p),"%s/wp_video.tar.gz",dir);
            if (stat(_p,&_x)!=0 || _x.st_size==0) fail |= 6;
        }
    }

    char h1[65], h2[65], h3[65];
    { char p[800]; snprintf(p,sizeof(p),"%s/launcher.db",dir); if(sha_file(p,h1))h1[0]=0; }
    { char p[800]; snprintf(p,sizeof(p),"%s/drawer.db",dir);   if(sha_file(p,h2))h2[0]=0; }
    { char p[800]; snprintf(p,sizeof(p),"%s/prefs.xml",dir);   if(sha_file(p,h3))h3[0]=0; }

    struct stat _sst; long theme_sz=0, wp_sz=0, wpv_sz=0;
    { char _p[820]; snprintf(_p,sizeof(_p),"%s/theme.tar.gz",dir); if(stat(_p,&_sst)==0) theme_sz=(long)_sst.st_size; }
    { char _p[820]; snprintf(_p,sizeof(_p),"%s/wallpaper.tar.gz",dir); if(stat(_p,&_sst)==0) wp_sz=(long)_sst.st_size; }
    { char _p[820]; snprintf(_p,sizeof(_p),"%s/wp_video.tar.gz",dir); if(stat(_p,&_sst)==0) wpv_sz=(long)_sst.st_size; }

    { char mp[800]; snprintf(mp, sizeof(mp), "%s/meta", dir);
      FILE *f = fopen(mp, "w"); if (f) {
        fprintf(f, "ts=%ld\n", (long)time(NULL));
        fprintf(f, "src=%s\n", src_manual ? "manual" : "auto");
        fprintf(f, "kinds=%s\n", c.kinds);
        fprintf(f, "digest=%s\n", dig);
        fprintf(f, "fail=%d\n", fail);
        fprintf(f, "launcher_sha=%s\n", h1);
        fprintf(f, "drawer_sha=%s\n", h2);
        fprintf(f, "prefs_sha=%s\n", h3);
        fprintf(f, "fp_theme=%s\n", fpt);
        fprintf(f, "fp_wp=%s\n", fpw);
        fprintf(f, "fp_wpv=%s\n", fpv);
        fprintf(f, "theme_size=%ld\n", theme_sz);
        fprintf(f, "wp_size=%ld\n", wp_sz);
        fprintf(f, "wpv_size=%ld\n", wpv_sz);
        fclose(f);
      }
    }
    dsnap_prune(c.keep);
    dsnap_sset("dsnap_last_ok", (long)time(NULL));
    dsnap_swstr("dsnap_last_digest", dig);
    if (has_theme) dsnap_swstr("dsnap_fp_theme", fpt);
    if (has_wp)    dsnap_swstr("dsnap_fp_wp", fpw);
    if (has_wpv)   dsnap_swstr("dsnap_fp_wpv", fpv);
    { char _m[180]; snprintf(_m, sizeof(_m), "%s %s", fail ? "部分失败" : "已备份", id); dsnap_swstr("dsnap_last_msg", _m); }
    dsnap_busy_clear();
    dsnap_log("DSNAP ok id=%s src=%s fail=%d L=%d T=%d W=%d V=%d", id, src_manual?"manual":"auto", fail, ch_layout, ch_theme, ch_wp, ch_wpv);
    dsnap_write_list();   /* 修复：备份完成立即刷新列表，否则 App 读旧 dsnap_list 看不到新快照 */
    return fail ? -1 : 1;
}

/* camd 在模块 App 的 mount ns 里看不到别的 app 的 /data/user_de；备份/恢复
 * 的文件动作放进 `nsenter -t 1 -m` 子进程（全局 ns）执行。 */
static int dsnap_run_ns(int src_manual) {
    return dsnap_run(src_manual);   /* camd 已在全局 ns：本进程直接跑，不派生 */
}
int dsnap_child_cli(int argc, char **argv) {
    int src = (argc >= 3) ? atoi(argv[2]) : 0;
    int r = dsnap_run(src);
    return (r >= 0) ? 0 : 1;
}

/* ------------------------------------------------------------------ inotify */
static int g_ifd = -1, g_inotify_ok = 0, g_dirty = 0;
static int g_wd[16], g_nwd = 0;
static long g_last_rearm = 0;

static void dsnap_add_watch(const char *dir) {
    if (g_nwd >= 15) return;
    /* 目录本身：捕捉新建/删除 wal、库重建 */
    int wd = inotify_add_watch(g_ifd, dir, IN_CREATE | IN_MOVED_TO | IN_DELETE);
    if (wd >= 0) g_wd[g_nwd++] = wd;
    /* 目录里的 db / wal 文件：捕捉内容写入（SQLite 就地写） */
    DIR *d = opendir(dir); struct dirent *e;
    if (!d) return;
    while ((e = readdir(d)) && g_nwd < 15) {
        const char *nm = e->d_name;
        if (strncmp(nm, "launcher", 8) != 0) continue;
        if (!strstr(nm, ".db")) continue;
        char fp[800]; snprintf(fp, sizeof(fp), "%s/%s", dir, nm);
        int w = inotify_add_watch(g_ifd, fp, IN_MODIFY | IN_CLOSE_WRITE | IN_DELETE_SELF | IN_MOVE_SELF);
        if (w >= 0) g_wd[g_nwd++] = w;
    }
    closedir(d);
}
static void dsnap_rearm(void) {
    if (!g_inotify_ok) return;
    g_nwd = 0;   /* 旧 wd 由内核在 IN_IGNORED 后自动失效 */
    dsnap_add_watch(HOME_DB_DIR);
    dsnap_add_watch(HOME_DB_SUB);
    g_last_rearm = (long)time(NULL);
}
static void dsnap_init(void) {
    if (g_ifd >= 0) return;
    g_ifd = inotify_init1(IN_NONBLOCK);
    if (g_ifd < 0) { g_inotify_ok = 0; dsnap_log("DSNAP inotify init fail: %s", strerror(errno)); return; }
    g_inotify_ok = 1;
    dsnap_rearm();
}
static void dsnap_drain(void) {
    if (g_ifd < 0) return;
    char buf[4096]; ssize_t n; int rearm = 0;
    while ((n = read(g_ifd, buf, sizeof(buf))) > 0) {
        char *p = buf;
        while (p < buf + n) {
            struct inotify_event *ev = (struct inotify_event *)p;
            if (ev->mask & IN_IGNORED) rearm = 1;
            else if (ev->mask & (IN_MODIFY|IN_CLOSE_WRITE|IN_CREATE|IN_MOVED_TO|IN_DELETE)) g_dirty = 1;
            p += sizeof(struct inotify_event) + ev->len;
        }
    }
    if (rearm) dsnap_rearm();
}

/* inotify 不可用时的兜底：stat 主库+抽屉库+prefs 的 (mtime,size) */
static int dsnap_db_touched(void) {
    char sig[256] = ""; char db[700]; struct stat st;
    if (find_launcher_db(HOME_DB_DIR, db, sizeof(db)) == 0 && stat(db, &st) == 0) {
        char t[96]; snprintf(t, sizeof(t), "%ld.%ld;", (long)st.st_mtime, (long)st.st_size); strncat(sig, t, sizeof(sig)-strlen(sig)-1);
    }
    if (find_launcher_db(HOME_DB_SUB, db, sizeof(db)) == 0 && stat(db, &st) == 0) {
        char t[96]; snprintf(t, sizeof(t), "%ld.%ld;", (long)st.st_mtime, (long)st.st_size); strncat(sig, t, sizeof(sig)-strlen(sig)-1);
    }
    if (stat(HOME_PREFS, &st) == 0) { char t[96]; snprintf(t, sizeof(t), "%ld.%ld;", (long)st.st_mtime, (long)st.st_size); strncat(sig, t, sizeof(sig)-strlen(sig)-1); }
    char last[256]; dsnap_sstr("dsnap_stat_sig", last, sizeof(last));
    if (strcmp(sig, last) != 0) { dsnap_swstr("dsnap_stat_sig", sig); return 1; }
    return 0;
}

/* ------------------------------------------------------------------ 心跳（rescue_tick 每轮调） */
static long g_ok_mt = -1; static long g_ok_val = 0; static int g_ok_init = 0;
static long dsnap_last_ok_cached(void) {
    char p[512]; struct stat st;
    dsnap_spath("dsnap_last_ok", p, sizeof(p));
    if (stat(p, &st) == 0) {
        if (g_ok_init && st.st_mtime == g_ok_mt) return g_ok_val;
        long v = dsnap_sget("dsnap_last_ok");
        g_ok_val = v; g_ok_mt = st.st_mtime; g_ok_init = 1; return v;
    }
    return 0;
}
int dsnap_heartbeat(void) {
    static int inited = 0;
    if (!inited) { dsnap_init(); inited = 1; }
    dsnap_drain();
    dcfg c; dcfg_load(&c);
    if (!c.enabled) return 0;
    long now = (long)time(NULL);
    long last = dsnap_last_ok_cached();
    if (now - last < (long)c.days * DAY_SEC) return 0;
    if (dsnap_rescue_busy()) return 0;
    int r = dsnap_run_ns(0);
    return r > 0 ? 1 : 0;
}

/* ------------------------------------------------------------------ 状态字段（ui_dump 追加） */
int dsnap_status_fields(char *dst, size_t cap) {
    dcfg c; dcfg_load(&c);
    long ok = dsnap_sget("dsnap_last_ok");
    char msg[160]; if (dsnap_sstr("dsnap_last_msg", msg, sizeof(msg)) != 0) msg[0] = 0;
    int n = snprintf(dst, cap,
        "dsnap_enabled=%d\ndsnap_days=%d\ndsnap_hour=%d\ndsnap_keep=%d\n"
        "dsnap_last_ok=%ld\ndsnap_count=%d\ndsnap_dirty=%d\ndsnap_last_msg=%s\ndsnap_kinds=%s\n",
        c.enabled, c.days, c.hour, c.keep, ok, dsnap_count(), g_dirty, msg, c.kinds);
    if (n < 0) return 0; if ((size_t)n >= cap) return (int)cap;
    { char root[512]; dsnap_root(root, sizeof(root));
      char list[1200]; int L = 0; list[0] = 0;
      DIR *d = opendir(root); struct dirent *e;
      if (d) { while ((e = readdir(d))) {
          if (e->d_name[0] == '.') continue;
          char meta[700]; snprintf(meta, sizeof(meta), "%s/%s/meta", root, e->d_name);
          if (access(meta, F_OK) != 0) continue;
          char src[32]; snap_src(root, e->d_name, src, sizeof(src));
          char lb[720]; snprintf(lb, sizeof(lb), "%s/%s/launcher.db", root, e->d_name);
          struct stat st; long sz = 0; if (stat(lb, &st) == 0) sz = (long)st.st_size;
          long ts = 0, tsz = 0, wsz = 0, vsz = 0; FILE *f = fopen(meta, "r");
          if (f) { char ln[160]; while (fgets(ln, sizeof(ln), f)) {
              if (!strncmp(ln,"ts=",3)) ts=atol(ln+3);
              else if (!strncmp(ln,"theme_size=",11)) tsz=atol(ln+11);
              else if (!strncmp(ln,"wp_size=",8)) wsz=atol(ln+8);
              else if (!strncmp(ln,"wpv_size=",9)) vsz=atol(ln+9);
          } fclose(f); }
          L += snprintf(list + L, sizeof(list) - L, "%s%s,%s,%ld,%ld,%ld,%ld,%ld", L ? ";" : "", e->d_name, src[0] ? src : "?", sz, ts, tsz, wsz, vsz);
          if ((size_t)L >= sizeof(list) - 64) break;
      } closedir(d); }
      int m = snprintf(dst + n, cap - (size_t)n, "dsnap_list=%s\n", list);
      if (m > 0) n += m;
    }
    return ((size_t)n < cap) ? n : (int)cap;
}

/* ------------------------------------------------------------------ 恢复（P0 骨架，真机验证后打磨） */
static int valid_id(const char *id) {
    int c = 0;
    for (const char *p = id; *p; p++, c++) {
        if (c == 8) { if (*p != '_') return 0; continue; }
        if (*p < '0' || *p > '9') return 0;
    }
    return c == 15;   /* yyyymmdd_hhmmss */
}
static int db_ok(const char *path) {
    sqlite3 *h;
    if (sqlite3_open_v2(path, &h, SQLITE_OPEN_READONLY, NULL) != SQLITE_OK) { if (h) sqlite3_close(h); return 0; }
    int ok = 0; sqlite3_stmt *st;
    if (sqlite3_prepare_v2(h, "PRAGMA quick_check", -1, &st, NULL) == SQLITE_OK) {
        if (sqlite3_step(st) == SQLITE_ROW) { const unsigned char *r = sqlite3_column_text(st, 0); ok = r && !strcmp((const char*)r, "ok"); }
        sqlite3_finalize(st);
    }
    sqlite3_close(h);
    return ok;
}
static int dsnap_restore(const char *id) {
    if (!camd_global_ns) { dsnap_log("DSNAP restore skip: global ns unavailable"); return -1; }
    if (!valid_id(id)) { dsnap_log("DSNAP restore reject id=%s", id); return -1; }
    char root[512]; dsnap_root(root, sizeof(root));
    char dir[700]; snprintf(dir, sizeof(dir), "%s/%s", root, id);
    char lb[800]; snprintf(lb, sizeof(lb), "%s/launcher.db", dir);
    if (access(lb, F_OK) != 0) { dsnap_log("DSNAP restore missing %s", lb); return -1; }
    if (!db_ok(lb)) { dsnap_log("DSNAP restore snapshot corrupt %s", lb); return -1; }

    dsnap_busy_mark("restore");

    /* 主题/壁纸恢复：照旧版 su -M -c 的 scriptRestore 移植（逐条删→解包→restorecon→壁纸自愈→flag类型对齐→重启引擎） */
    int twik = 0, wpik = 0;
    { char _t[820]; snprintf(_t,sizeof(_t),"%s/theme.tar.gz",dir); if (access(_t,F_OK)==0) twik = 1; }
    { char _t[820]; snprintf(_t,sizeof(_t),"%s/wallpaper.tar.gz",dir); if (access(_t,F_OK)==0) wpik = 1; }
    {
        char sp[600]; snprintf(sp, sizeof(sp), "%s/state/dsnap_rst.sh", cam_dir_root());
        FILE *sf = fopen(sp, "w");
        if (sf) {
            fprintf(sf, "#!/system/bin/sh\nD='%s'\n", dir);
            fputs("ap(){ [ -f \"$1\" ] && while IFS= read -r ln; do ns=${ln%%:*}; kv=${ln#*:}; k=${kv%%=*}; v=${kv#*=}; [ -n \"$k\" ] && [ -n \"$v\" ] && settings put \"$ns\" \"$k\" \"$v\" 2>/dev/null; done < \"$1\"; }\nif [ -f \"$D/theme.tar.gz\" ]; then\n  if tar -tzf \"$D/theme.tar.gz\" >/dev/null 2>&1; then\n    rm -rf /data/system/theme\n    tar -xzpf \"$D/theme.tar.gz\" -C / 2>/dev/null\n    restorecon -R /data/system/theme >/dev/null 2>&1\n  fi\n  ap \"$D/theme_settings.txt\"\n  p=$(pidof com.android.systemui); [ -n \"$p\" ] && kill $p\nfi\nif [ -f \"$D/wp_video.tar.gz\" ]; then\n  W=/data/system/theme_magic/users/0/wallpaper\n  mkdir -p \"$W\"\n  tar -tzf \"$D/wp_video.tar.gz\" > \"$D/wv.lst\" 2>/dev/null\n  while IFS= read -r f; do [ -n \"$f\" ] && rm -rf \"/$f\"; done < \"$D/wv.lst\"\n  tar -xzpf \"$D/wp_video.tar.gz\" -C / 2>/dev/null\n  restorecon -R \"$W\" >/dev/null 2>&1\nfi\nif [ -f \"$D/wallpaper.tar.gz\" ]; then\n  W=/data/system/theme_magic/users/0/wallpaper\n  mkdir -p \"$W\"\n  tar -tzf \"$D/wallpaper.tar.gz\" > \"$D/w.lst\" 2>/dev/null\n  while IFS= read -r f; do [ -n \"$f\" ] && rm -rf \"/$f\"; done < \"$D/w.lst\"\n  tar -xzpf \"$D/wallpaper.tar.gz\" -C / 2>/dev/null\n  restorecon -R \"$W\" >/dev/null 2>&1\n  restorecon /data/system/users/0/wallpaper_info.xml /data/system/users/0/wallpaper /data/system/users/0/wallpaper_orig /data/system/users/0/wallpaper_lock /data/system/users/0/wallpaper_lock_orig /data/system/users/0/blurwallpaper >/dev/null 2>&1\n  ap \"$D/wp_settings.txt\"\n  for n in home lock; do\n    X=\"$W/data/$n.xml\"\n    [ -f \"$X\" ] || continue\n    p0=$(grep -oE 'path0=\"[^\"]+\"' \"$X\" | head -1 | sed 's/.*=\"//; s/\"$//')\n    [ -n \"$p0\" ] || continue\n    [ -f \"$p0\" ] && continue\n    alt=\"\"\n    for c in \"$W/image/${n}_wallpaper.jpg\" \"$W/image/${n}_wallpaper.png\" \"$W/gallery/${n}_wallpaper.jpg\" \"$W/default/${n}_wallpaper.jpg\"; do [ -f \"$c\" ] && { alt=\"$c\"; break; }; done\n    if [ -n \"$alt\" ]; then\n      sed -i 's/type=\"video\"/type=\"image\"/g' \"$X\"\n      sed -i 's/last_type=\"video\"/last_type=\"image\"/g' \"$X\"\n      sed -i \"s|path0=\\\"[^\\\"]*\\\"|path0=\\\"$alt\\\"|\" \"$X\"\n    else\n      vv=\"\"\n      for c in \"$W/video/${n}_wallpaper_video.mp4\" \"$W/video/home_wallpaper_video.mp4\"; do [ -f \"$c\" ] && { vv=\"$c\"; break; }; done\n      if [ -n \"$vv\" ]; then\n        sed -i 's/type=\"image\"/type=\"video\"/g' \"$X\"\n        sed -i 's/last_type=\"image\"/last_type=\"video\"/g' \"$X\"\n        sed -i \"s|path0=\\\"[^\\\"]*\\\"|path0=\\\"$vv\\\"|\" \"$X\"\n      fi\n    fi\n  done\n  t=$(grep -oE 'type=\"[^\"]+\"' \"$W/data/lock.xml\" 2>/dev/null | head -1 | sed 's/.*=\"//; s/\"$//')\n  [ -n \"$t\" ] && settings put secure flag_lock_wallpaper_type \"$t\"\n  for s in com.miui.miwallpaper com.miui.aod; do p=$(pidof $s); [ -n \"$p\" ] && kill -9 $p; done\n  p=$(pidof com.android.systemui); [ -n \"$p\" ] && kill $p\nfi\necho RESTORE_OK\n", sf);
            fclose(sf); chmod(sp, 0700);
            char rc2[1400]; snprintf(rc2, sizeof(rc2), "sh '%s'", sp); run_sh(rc2);
        }
    }
    dsnap_log("DSNAP restore theme=%d wp=%d id=%s", twik, wpik, id);

    /* 停桌面后立即覆盖库（不睡，避免 AMS 在替换前把桌面复活成旧库） */
    kill_home();

    int rc = 0;
    char db[700];
    if (find_launcher_db(HOME_DB_DIR, db, sizeof(db)) == 0) {
        char w[760]; snprintf(w,sizeof(w),"%s-wal",db); unlink(w); snprintf(w,sizeof(w),"%s-shm",db); unlink(w);
        if (copy_into_existing(lb, db) != 0) rc = -1;
    } else rc = -1;
    { char dd[800]; snprintf(dd, sizeof(dd), "%s/drawer.db", dir);
      if (access(dd, F_OK) == 0 && find_launcher_db(HOME_DB_SUB, db, sizeof(db)) == 0) {
        char w[760]; snprintf(w,sizeof(w),"%s-wal",db); unlink(w); snprintf(w,sizeof(w),"%s-shm",db); unlink(w);
        copy_into_existing(dd, db);
      } }
    { char pp[800]; snprintf(pp, sizeof(pp), "%s/prefs.xml", dir);
      if (access(pp, F_OK) == 0) copy_into_existing(pp, HOME_PREFS); }

    kill_home();      /* 清掉替换过程中被 AMS 复活的旧实例（它加载的是旧库） */
    ensure_home();    /* 拉起新实例，读新库 */
    rescue_after_own_restart();  /* 本次故意重启 SystemUI：重定 pid 基线，不计崩溃 */
    dsnap_busy_clear();
    dsnap_log("DSNAP restore done id=%s rc=%d", id, rc);
    return rc;
}

int dsnap_child_restore_cli(int argc, char **argv) {
    const char *id = (argc >= 3) ? argv[2] : "";
    return (dsnap_restore(id) == 0) ? 0 : 1;
}

static void dsnap_export_all(void) {
    char root[512]; dsnap_root(root, sizeof(root));
    const char *dest = "/storage/emulated/0/Download/Camellia";
    mkdir_p(dest, 0755);
    copy_tree(root, dest);
    dsnap_swstr("dsnap_last_msg", "已导出到 /storage/emulated/0/Download/Camellia");
    dsnap_log("DSNAP export_all root=%s", root);
}

/* 列出快照到 <root>/state/dsnap_list.txt（UI 读） */
static void dsnap_write_list(void) {
    char root[512]; dsnap_root(root, sizeof(root));
    const char *paths[2];
    char p0[512], p1[160];
    snprintf(p0, sizeof(p0), "%s/state/dsnap_list.txt", cam_dir_root());
    snprintf(p1, sizeof(p1), "/sdcard/Android/data/com.hyper.Camellia/files/dsnap_list.txt");
    paths[0] = p0; paths[1] = p1;
    for (int pi = 0; pi < 2; pi++) {
        FILE *o = fopen(paths[pi], "w"); if (!o) continue;
        DIR *d = opendir(root); struct dirent *e;
        if (d) {
            while ((e = readdir(d))) {
                if (e->d_name[0] == '.') continue;
                char meta[700]; snprintf(meta, sizeof(meta), "%s/%s/meta", root, e->d_name);
                if (access(meta, F_OK) != 0) continue;
                char src[32]; snap_src(root, e->d_name, src, sizeof(src));
                char lb[720]; snprintf(lb, sizeof(lb), "%s/%s/launcher.db", root, e->d_name);
                struct stat st; long sz = 0; if (stat(lb, &st) == 0) sz = (long)st.st_size;
                long ts = 0, tsz = 0, wsz = 0, vsz = 0; FILE *f = fopen(meta, "r");
                if (f) { char ln[160]; while (fgets(ln, sizeof(ln), f)) {
                    if (!strncmp(ln,"ts=",3)) ts=atol(ln+3);
                    else if (!strncmp(ln,"theme_size=",11)) tsz=atol(ln+11);
                    else if (!strncmp(ln,"wp_size=",8)) wsz=atol(ln+8);
                    else if (!strncmp(ln,"wpv_size=",9)) vsz=atol(ln+9);
                } fclose(f); }
                fprintf(o, "%s|%s|%ld|%ld|%ld|%ld|%ld\n", e->d_name, src[0] ? src : "?", sz, ts, tsz, wsz, vsz);
            }
            closedir(d);
        }
        fclose(o);
        chmod(paths[pi], 0644);
    }
}

/* ------------------------------------------------------------------ UI 命令分派（ui_apply 转进来） */
int dsnap_ui_apply(const char *k, const char *v) {
    if (!strcmp(k, "dsnap_run"))    { dsnap_run_ns(1); return 1; }
    if (!strcmp(k, "dsnap_list"))   { dsnap_write_list(); return 1; }
    if (!strcmp(k, "dsnap_del"))    {
        if (!valid_id(v)) { dsnap_log("DSNAP del reject %s", v); return 0; }
        char root[512], pth[800]; dsnap_root(root, sizeof(root));
        snprintf(pth, sizeof(pth), "%s/%s", root, v); rm_tree(pth);
        dsnap_write_list(); return 1;
    }
    if (!strcmp(k, "dsnap_restore")) { dsnap_restore(v); return 1; }
    if (!strcmp(k, "dsnap_export_all")) { dsnap_export_all(); return 1; }
    if (!strcmp(k, "dsnap_clean")) { dsnap_clean_failed(); dsnap_write_list(); return 1; }
    if (!strcmp(k, "dsnap_cfg")) {
        dcfg c; dcfg_load(&c);
        char buf[256]; snprintf(buf, sizeof(buf), "%s", v ? v : "");
        char *save = NULL;
        for (char *tok = strtok_r(buf, ",", &save); tok; tok = strtok_r(NULL, ",", &save)) {
            char *eq = strchr(tok, '='); if (!eq) continue; *eq = 0;
            char *kk = tok, *vv = eq + 1;
            if (!strcmp(kk, "enabled")) c.enabled = atoi(vv) ? 1 : 0;
            else if (!strcmp(kk, "days")) { int x = atoi(vv); if (x>=1&&x<=30) c.days = x; }
            else if (!strcmp(kk, "hour")) { int x = atoi(vv); if (x>=0&&x<=23) c.hour = x; }
            else if (!strcmp(kk, "keep")) { int x = atoi(vv); if (x>=1&&x<=10) c.keep = x; }
            else if (!strcmp(kk, "kinds")) { char t2[128]; snprintf(t2,sizeof(t2),"%s",vv); for (char*q=t2;*q;q++) if(*q=='+') *q=' '; snprintf(c.kinds,sizeof(c.kinds),"%s",t2); }
        }
        dcfg_save(&c);
        return 1;
    }
    return 0;
}
