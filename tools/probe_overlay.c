/*
 * probe_overlay.c - 验证「覆盖安装态」的文件系统判定方法与耗时
 *
 * 判据：/data/app/<~~rand==>/<pkg>-<suffix>/ 存在 ⇒ 该包处于覆盖态
 *       （系统预装应用本不应出现在 /data/app，出现即为被 pm install 覆盖）
 *
 * 目标：证明该判定可零 fork 完成（不用 pm / dumpsys），并测出真实耗时，
 *       以决定 camd 是「每次崩溃时现扫」还是「启动建索引 + inotify 维护」。
 */
#include <stdio.h>
#include <string.h>
#include <dirent.h>
#include <time.h>

static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec * 1000.0 + (double)t.tv_nsec / 1e6;
}

/* 返回 1 = 覆盖态（找到 data 层目录），0 = 原生态，-1 = 无法打开 /data/app */
static int is_overlaid(const char *pkg, char *found, size_t n) {
    DIR *d = opendir("/data/app");
    if (!d) return -1;

    struct dirent *e;
    size_t pl = strlen(pkg);
    int hit = 0;
    int scanned = 0;

    while ((e = readdir(d)) != NULL) {
        /* 只关心 Android 11+ 的 ~~<base64>== 随机目录 */
        if (e->d_name[0] != '~') continue;
        scanned++;

        char sub[512];
        snprintf(sub, sizeof sub, "/data/app/%s", e->d_name);

        DIR *d2 = opendir(sub);
        if (!d2) continue;

        struct dirent *e2;
        while ((e2 = readdir(d2)) != NULL) {
            if (strncmp(e2->d_name, pkg, pl) != 0) continue;
            /* 必须紧跟 '-' 或结束，避免 com.android.systemui.plugin 之类前缀误匹配 */
            if (e2->d_name[pl] != '-' && e2->d_name[pl] != '\0') continue;
            if (found) snprintf(found, n, "%s/%s", sub, e2->d_name);
            hit = 1;
            break;
        }
        closedir(d2);
        if (hit) break;
    }
    closedir(d);

    if (found == NULL) return hit;
    return hit;
}

int main(void) {
    const char *pkgs[] = {
        "com.android.systemui",
        "miui.systemui.plugin",
        NULL
    };
    char buf[600];
    int i, n = 0;
    double total0 = now_ms(), total1;

    for (i = 0; pkgs[i]; i++) {
        double a, b;
        int r;
        buf[0] = '\0';
        a = now_ms();
        r = is_overlaid(pkgs[i], buf, sizeof buf);
        b = now_ms();
        printf("%-24s overlaid=%d  %7.3f ms  %s\n",
               pkgs[i], r, b - a, (r == 1) ? buf : "(原生)");
        n++;
    }
    total1 = now_ms();
    printf("--------------------------------------------------\n");
    printf("单包均摊 %.3f ms  全部 %d 包共 %.3f ms\n",
           (total1 - total0) / n, n, total1 - total0);
    return 0;
}
