/*
 * abx_path_probe.c —— 从 ABX 二进制 packages.xml 直接提取包的 codePath
 *
 * 用途：证明 camd 无需 fork abx2xml、无需 fork pm/dumpsys，
 *       仅 mmap + memmem 即可拿到安装目录（DE 阶段可用）。
 *
 * ABX 字符串布局（逆向实测）：
 *     2f | uint16 A | uint16 len | <len 字节内容>
 *   len 为大端 uint16，内容为明文。codePath 值紧跟包名 name 属性之后
 *   约 20~25 字节处。
 *
 * 编译: gcc -O2 -static -D_GNU_SOURCE -o abx_path_probe abx_path_probe.c
 * 用法: ./abx_path_probe /data/system/packages.xml pkg1 [pkg2 ...]
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <unistd.h>
#include <time.h>

#define MAX_PATH_LEN 1024
#define NEAR_WINDOW  256

static const char *kPrefixes[] = {
    "/data/app/", "/data/priv-app/", "/data/preloads/",
    "/system_ext/", "/system/", "/product/", "/vendor/",
    "/odm/", "/oem/", "/apex/", "/cust/", "/mnt/"
};
#define NPREFIX ((int)(sizeof(kPrefixes) / sizeof(kPrefixes[0])))

static inline int is_path_char(unsigned char c)
{
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
           (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '/' ||
           c == '+' || c == '=' || c == '~' || c == '-' || c == '@';
}

/* 包名字符集：用于判定命中处是否是完整包名 token（避免 "android" 之类的子串污染） */
static inline int is_pkg_char(unsigned char c)
{
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
           (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
}

static int is_whole_token(const uint8_t *d, size_t n, size_t off, size_t len)
{
    if (off > 0 && is_pkg_char(d[off - 1])) return 0;
    if (off + len < n && is_pkg_char(d[off + len])) return 0;
    return 1;
}

static int valid_prefix(const char *s, size_t n)
{
    for (int i = 0; i < NPREFIX; i++) {
        size_t pl = strlen(kPrefixes[i]);
        if (n >= pl && memcmp(s, kPrefixes[i], pl) == 0)
            return 1;
    }
    return 0;
}

/*
 * 在 [lo, hi) 范围内按 2f|A|len 头扫描路径串。
 * 命中返回 1 并把起点写入 *out_ptr、长度写入 *out_len。
 */
static int scan_path_in(const uint8_t *d, size_t n, size_t lo, size_t hi,
                        const char **out_ptr, size_t *out_len)
{
    if (hi > n) hi = n;
    for (size_t i = lo; i + 5 < hi; i++) {
        if (d[i] != 0x2F) continue;
        size_t ln = ((size_t)d[i + 3] << 8) | d[i + 4];
        if (ln < 8 || ln > MAX_PATH_LEN) continue;
        if (i + 5 + ln > n) continue;
        const char *s = (const char *)(d + i + 5);
        int ok = 1;
        for (size_t k = 0; k < ln; k++) {
            if (!is_path_char((unsigned char)s[k])) { ok = 0; break; }
        }
        if (!ok) continue;
        if (!valid_prefix(s, ln)) continue;
        *out_ptr = s;
        *out_len = ln;
        return 1;
    }

    /* fallback：宽松 run 扫描，兼容字符串头布局不标准的变体
     * （有些包 codePath 值前紧邻属性名，长度头不在预期位置） */
    for (size_t i = lo; i < hi; i++) {
        if (d[i] != 0x2F) continue;                 /* 必须从 '/' 起头 */
        size_t j = i;
        while (j < n && is_path_char(d[j])) j++;
        size_t ln = j - i;
        if (ln < 8 || ln > MAX_PATH_LEN) continue;
        const char *s = (const char *)(d + i);
        if (!valid_prefix(s, ln)) continue;
        size_t sl = 0;
        for (size_t k = 0; k < ln; k++) if (s[k] == '/') sl++;
        if (sl < 2) continue;                       /* /xxx 这种短词排除 */
        while (ln > 0 && s[ln - 1] == '/') ln--;    /* 剥掉 run 尾巴的 '/' */
        if (ln < 8) continue;
        *out_ptr = s;
        *out_len = ln;
        return 1;
    }
    return 0;
}

static void now_ms(const char *tag, struct timespec *t0)
{
    struct timespec t1;
    clock_gettime(CLOCK_MONOTONIC, &t1);
    long ms = (t1.tv_sec - t0->tv_sec) * 1000L +
              (t1.tv_nsec - t0->tv_nsec) / 1000000L;
    fprintf(stderr, "  [%s] %ld ms\n", tag, ms);
}

int main(int argc, char **argv)
{
    if (argc < 3) {
        fprintf(stderr, "usage: %s <packages.xml> <pkg> [pkg...]\n", argv[0]);
        return 2;
    }

    struct timespec t0;
    clock_gettime(CLOCK_MONOTONIC, &t0);

    const char *file = argv[1];
    int fd = open(file, O_RDONLY);
    if (fd < 0) { perror("open"); return 1; }
    struct stat st;
    if (fstat(fd, &st) < 0) { perror("fstat"); close(fd); return 1; }
    size_t n = (size_t)st.st_size;
    const uint8_t *d = mmap(NULL, n, PROT_READ, MAP_PRIVATE, fd, 0);
    if (d == MAP_FAILED) { perror("mmap"); close(fd); return 1; }
    close(fd);

    fprintf(stderr, "文件 %s  %zu 字节\n", file, n);
    now_ms("mmap", &t0);

    int fail = 0;
    for (int a = 2; a < argc; a++) {
        const char *pkg = argv[a];
        size_t pkglen = strlen(pkg);
        const char *best_ptr = NULL;
        size_t best_len = 0;
        size_t best_dist = (size_t)-1;
        int occ = 0, whole = 0;

        struct timespec tp;
        clock_gettime(CLOCK_MONOTONIC, &tp);

        /* 遍历包名所有出现位置 */
        for (size_t p = 0; p + pkglen <= n; ) {
            const uint8_t *hit = memmem(d + p, n - p, pkg, pkglen);
            if (!hit) break;
            size_t off = (size_t)(hit - d);
            occ++;
            /* 只接受完整包名 token，过滤 "android" 这类子串命中 */
            if (is_whole_token(d, n, off, pkglen)) {
                whole++;
                const char *ptr;
                size_t len;
                /* 包名之后找 codePath（XML 属性顺序 name -> codePath） */
                if (scan_path_in(d, n, off + pkglen, off + pkglen + NEAR_WINDOW, &ptr, &len)) {
                    size_t dist = (size_t)(ptr - ((const char *)d + off));
                    if (dist < best_dist) { best_dist = dist; best_ptr = ptr; best_len = len; }
                }
            }
            p = off + pkglen;
        }

        struct timespec t1;
        clock_gettime(CLOCK_MONOTONIC, &t1);
        long us = (t1.tv_sec - tp.tv_sec) * 1000000L +
                  (t1.tv_nsec - tp.tv_nsec) / 1000L;

        if (best_ptr) {
            printf("%-32s %.*s\n", pkg, (int)best_len, best_ptr);
            fprintf(stderr, "  出现 %d 次(完整token %d), 距离 %zu 字节, 查询耗时 %ld us\n",
                    occ, whole, best_dist, us);
        } else {
            printf("%-32s <NOT FOUND>\n", pkg);
            fprintf(stderr, "  出现 %d 次(完整token %d), 未找到邻域路径, 耗时 %ld us\n",
                    occ, whole, us);
            fail++;
        }
    }

    now_ms("总计", &t0);
    munmap((void *)d, n);
    return fail ? 1 : 0;
}
