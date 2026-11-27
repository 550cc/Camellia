# -*- coding: utf-8 -*-
"""camellia_rescue.c B 段崩溃判定重构：窗口 60s/3 次 + dropbox 特征归因 + 判不出双修"""
import sys, io

P = 'tools/camellia_rescue.c'
src = io.open(P, encoding='utf-8').read()
n_edits = 0


def must(cond, msg):
    if not cond:
        print('FAIL: ' + msg)
        sys.exit(1)


def rep1(old, new, tag):
    global src, n_edits
    c = src.count(old)
    must(c == 1, '%s: occurrences=%d (expect 1)' % (tag, c))
    src = src.replace(old, new, 1)
    n_edits += 1
    print('OK  ' + tag)


def fn_span(sig):
    """按大括号配平提取函数原文区间 [start, end)"""
    i = src.index(sig)
    j = src.index('{', i)
    depth = 0
    k = j
    while k < len(src):
        ch = src[k]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return i, k + 1
        k += 1
    raise RuntimeError('unbalanced braces for ' + sig)


def rep_fn(sig, new, tag):
    global src, n_edits
    a, b = fn_span(sig)
    old = src[a:b]
    must(sig in old, tag + ': span mismatch')
    src = src[:a] + new.rstrip('\n') + src[b:]
    n_edits += 1
    print('OK  ' + tag + ' (%d chars -> %d)' % (len(old), len(new)))


# ---------------------------------------------------------------- E1: 模块挂载点
OLD_SUBS = '''    static const char *subs[] = {
        "system/system_ext/priv-app",
        "system_ext/priv-app",
        "system/priv-app",
        "system/product/priv-app",
        "product/priv-app",
        "system/system/priv-app",
        NULL
    };
'''
NEW_SUBS = '''    /*
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
'''
rep1(OLD_SUBS, NEW_SUBS, 'E1 mod_is_systemui 挂载点')

# ------------------------------------------------- E2: dropbox 窗口统计 + gz
NEW_DROPBOX = r'''/* ------------------------------------------- dropbox 窗口统计（崩溃一手证据） ====
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
        static const char *const cand[] = {
            "/system/bin/toybox gzip -dc '%s' 2>/dev/null",
            "/system/bin/gzip -dc '%s' 2>/dev/null",
            "/data/adb/ksu/bin/busybox gzip -dc '%s' 2>/dev/null",
            "/data/adb/magisk/busybox gzip -dc '%s' 2>/dev/null",
            NULL
        };
        char cmd[640];
        int i;

        for (i = 0; cand[i]; i++) {
            FILE *p;
            size_t got;
            snprintf(cmd, sizeof(cmd), cand[i], path);
            p = popen(cmd, "r");
            if (!p) continue;
            got = fread(buf, 1, n - 1, p);
            pclose(p);
            if (got > 0) {
                buf[got] = 0;
                return 0;
            }
        }
        rlog("DROPBOX_GZ_FAIL path=%s (无可用解压器)", path);
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

/* 统计窗口内「与界面相关」的崩溃条数，并给出最新命中条目的归因。
 *   cnt      -> 条数（可 NULL）
 *   attr_out -> 最新命中条目的归因（无命中为 ATTR_NONE）
 *   src/sn   -> 最新命中条目的文件名（日志用，可 NULL）
 * 返回条数。 */
static int dropbox_window(long since_ms, int *cnt, int *attr_out,
                          char *src, size_t sn)
{
    DIR *d;
    struct dirent *de;
    char names[24][300];
    long ts[24];
    int n = 0, i, j, total = 0;
    int best_attr = ATTR_NONE;
    char best[300] = "";
    long best_ts = 0;

    if (cnt) *cnt = 0;
    if (attr_out) *attr_out = ATTR_NONE;
    if (src && sn) src[0] = 0;

    d = opendir(DROPBOX_DIR);
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
        if (n >= 24) break;              /* 60s 窗口不可能有 24 条，防爆栈 */
        snprintf(names[n], sizeof(names[n]), "%s", de->d_name);
        ts[n] = t;
        n++;
    }
    closedir(d);

    /* 时间倒序：只读窗口内条目（通常 1~3 条），最新的一条排在最前 */
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
        int a;

        snprintf(path, sizeof(path), "%s/%s", DROPBOX_DIR, names[i]);
        if (dropbox_read_body(path, buf, sizeof(buf)) != 0) continue;
        a = attribute_text(buf, strlen(buf));
        if (a == ATTR_NONE) continue;    /* 无关条目：别的应用崩的 */
        total++;
        if (ts[i] > best_ts) {
            best_ts = ts[i];
            best_attr = a;
            snprintf(best, sizeof(best), "%s", names[i]);
        }
    }

    if (cnt) *cnt = total;
    if (attr_out) *attr_out = best_attr;
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

static int crash_hist_add(long now_s, int window_sec)
{
    char path[340], out[640];
    long v[HIST_MAX + 4];
    int n = 0, i, kept = 0;
    FILE *f;
    size_t olen = 0;

    cam_path("state/crash_hist", path, sizeof(path));
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

static int crash_hist_count(long now_s, int window_sec)
{
    char path[340];
    FILE *f;
    long v;
    int n = 0;

    cam_path("state/crash_hist", path, sizeof(path));
    f = fopen(path, "r");
    if (!f) return 0;
    while (fscanf(f, "%ld", &v) == 1)
        if (v <= now_s && now_s - v <= window_sec) n++;
    fclose(f);
    return n;
}
'''
rep_fn('static int attr_from_dropbox(long min_ts_ms, char *src, size_t sn)',
       NEW_DROPBOX, 'E2 attr_from_dropbox -> dropbox_window/gz/hist')

# ------------------------------------------------- E3: culprit_attr 用新函数
rep1('''    a = attr_from_dropbox(min_ts_ms, src, sizeof(src));
    if (a != ATTR_NONE) {
        if (why && wn) snprintf(why, wn, "dropbox:%s", src);
        return a;
    }''',
     '''    {
        int cnt = 0;
        dropbox_window(min_ts_ms, &cnt, &a, src, sizeof(src));
        if (a != ATTR_NONE) {
            if (why && wn) snprintf(why, wn, "dropbox:%s(cnt=%d)", src, cnt);
            return a;
        }
    }''', 'E3a culprit_attr 调 dropbox_window')

# ------------------------------------------------- E4: culprit_attr 末尾 -> BOTH
rep1('''        if (why && wn) snprintf(why, wn, "no-overlay(sysui=%s,plugin=%s)",
                                hs ? (s_ovl ? "app" : "rom") : "?",
                                hp ? (p_ovl ? "app" : "rom") : "?");
    }
    return ATTR_NONE;
}''',
     '''        /* 判不出来 -> 双修。两方都没有覆盖层时 find_ovl 全失败，
         * 调用方自然什么都不做，不会误删。 */
        if (why && wn) snprintf(why, wn, "unknown->both(sysui=%s,plugin=%s)",
                                hs ? (s_ovl ? "app" : "rom") : "?",
                                hp ? (p_ovl ? "app" : "rom") : "?");
    }
    return ATTR_BOTH;
}''', 'E4 culprit_attr 末尾返回 ATTR_BOTH')

# ---------------------------------------- E5: ui_rescue_chain 加 do_reboot
rep1('static int ui_rescue_chain(cfg_t *c, const char *pkg, char *note, size_t nn);',
     'static int ui_rescue_chain(cfg_t *c, const char *pkg, char *note, size_t nn,\n'
     '                           int do_reboot);', 'E5a ui_rescue_chain 声明')

NEW_CHAIN = '''static int ui_rescue_chain(cfg_t *c, const char *pkg, char *note, size_t nn,
                           int do_reboot)
{
    char res[512];

    res[0] = 0;
    if (pm_uninstall_update(pkg, 0, res, sizeof(res)) == 0) {
        rlog("UI_RESCUE uninstall_update pkg=%s -> %s", pkg, res);
        snprintf(note, nn, "① 已卸更新: %s", res);
        if (do_reboot) reboot_after_fix(c);
        return 0;
    }
    rlog("UI_RESCUE uninstall_update 不适用 pkg=%s (%s)", pkg, res);

    if (c->user_apk[0]) {
        if (pm_install(c->user_apk, pkg, 0, res, sizeof(res)) == 0) {
            rlog("UI_RESCUE install pkg=%s apk=%s -> %s", pkg, c->user_apk, res);
            snprintf(note, nn, "② 已装兜底包: %s", res);
            if (do_reboot) reboot_after_fix(c);
            return 0;
        }
        rlog("UI_RESCUE install 失败 pkg=%s apk=%s (%s)", pkg, c->user_apk, res);
    }

    rlog("UI_RESCUE fallback fix_pkg pkg=%s", pkg);
    return fix_pkg(c, pkg, do_reboot, note, nn);
}'''
rep_fn('static int ui_rescue_chain(cfg_t *c, const char *pkg, char *note, size_t nn)\n{',
       NEW_CHAIN, 'E5b ui_rescue_chain 定义')

# ---------------------------------------- E6: rescue_tick pid -> 窗口判定
rep1('''    for (i = 0; i < np; i++) {
        char key[220];
        int cur;
        long old;
        snprintf(key, sizeof(key), "spid_%s", pkgs[i]);
        cur = pid_of_pkg(pkgs[i]);
        old = st_get(key);
        if (old > 0 && (long)cur != old) {
            crash = 1;
            rlog("PID_CHANGE %s %ld -> %d", pkgs[i], old, cur);
        }
        st_set(key, cur);          /* 无条件落盘（含 0）：同一事件只记一次 */
    }''',
     '''    /*
     * 崩溃计数改「窗口口径」：pid 变化只记账，不直接算崩。
     * 单次 pid 变化分不清「崩了」和「正在起来」—— 软重启后的空窗、zygote
     * 预热、内存回收后重启都会变。窗口内累计到 crash_threshold 次才判定为
     * 循环崩，偶发一次不动手。
     *
     * 两条独立信号取较大者：
     *   ① dropbox 崩溃正文归因命中的条数（一手证据，能区分组件/界面）
     *   ② pid 变化次数（不依赖 dropbox 落盘时机，dropbox 满盘时不失效）
     */
    for (i = 0; i < np; i++) {
        char key[220];
        int cur;
        long old;
        snprintf(key, sizeof(key), "spid_%s", pkgs[i]);
        cur = pid_of_pkg(pkgs[i]);
        old = st_get(key);
        if (old > 0 && (long)cur != old) {
            rlog("PID_CHANGE %s %ld -> %d", pkgs[i], old, cur);
            crash_hist_add(up, c.crash_window_sec);
        }
        st_set(key, cur);          /* 无条件落盘（含 0）：同一事件只记一次 */
    }

    {
        long since = now_ms_rt() - (long)c.crash_window_sec * 1000L;
        int db_cnt = 0, db_attr = ATTR_NONE, pid_cnt;
        char dsrc[300] = "";

        dropbox_window(since, &db_cnt, &db_attr, dsrc, sizeof(dsrc));
        pid_cnt = crash_hist_count(up, c.crash_window_sec);
        if (db_cnt > 0 || pid_cnt > 0)
            rlog("CRASH_WIN db=%d(%s %s) pid=%d win=%ds thr=%d",
                 db_cnt,
                 db_attr == ATTR_PLUGIN ? "plugin" :
                 db_attr == ATTR_SYSUI  ? "sysui"  :
                 db_attr == ATTR_BOTH   ? "both"   : "none",
                 dsrc, pid_cnt, c.crash_window_sec, c.crash_threshold);
        if (db_cnt >= c.crash_threshold || pid_cnt >= c.crash_threshold)
            crash = 1;
    }''', 'E6 rescue_tick pid -> 窗口计数')

# ---------------------------------------- E7: rescue_tick 修复段 -> 归因+双修
rep1('''    if (!crash) return 0;                 /* 没崩就不动手 */

    /* 有崩溃：只修「同时存在覆盖层」的包，一次一个 */
    for (i = 0; i < np; i++) {
        char d[640], note[800];
        if (!find_ovl(pkgs[i], d, sizeof(d))) continue;
        rlog("TICK_ACT pkg=%s crash=1 -> ui_rescue_chain", pkgs[i]);
        if (ui_rescue_chain(&c, pkgs[i], note, sizeof(note)) == 0) return 0;
        rlog("TICK_FIX_FAIL pkg=%s %s", pkgs[i], note);
    }
    return 0;
}''',
     '''    if (!crash) return 0;                 /* 窗口内没崩够次数就不动手 */

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

        attr = culprit_attr(0, why, sizeof(why));
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
            if (acted) reboot_after_fix(&c);
            return 0;
        }

        /* 定性明确：只修对应那个包，成功即收工（它自己会重启） */
        {
            int matched = 0;
            for (i = 0; i < np; i++) {
                char d[640], note[800];
                int isp = (strstr(pkgs[i], "plugin") != NULL);

                if (attr == ATTR_PLUGIN && !isp) continue;
                if (attr == ATTR_SYSUI && isp) continue;
                if (!find_ovl(pkgs[i], d, sizeof(d))) continue;
                matched = 1;
                rlog("TICK_ACT pkg=%s attr=%s -> ui_rescue_chain", pkgs[i],
                     attr == ATTR_PLUGIN ? "plugin" : "sysui");
                if (ui_rescue_chain(&c, pkgs[i], note, sizeof(note), 1) == 0)
                    return 0;
                rlog("TICK_FIX_FAIL pkg=%s %s", pkgs[i], note);
            }
            if (!matched)
                rlog("TICK_NO_MATCH attr=%s (配置 target 里无对应包，本轮不动)",
                     attr == ATTR_PLUGIN ? "plugin" : "sysui");
        }
    }
    return 0;
}''', 'E7 rescue_tick 修复段 -> 归因/双修')

# ---------------------------------------- E8: A 段单包处置抽成 helper
A_FIX_ONE = '''/* ---------------------------------------------------------------------------
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
        printf("DRY would delete %s -> rm -rf %s then soft reboot\\n", pkg, dir);
        return 0;
    }

    st_set(key, lu);
    write_pending(pkg, "delete_apk", dir, why);

    if (bak_save(c, pkg, dir, bak, sizeof(bak)) != 0)
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

'''
rep1('static int a_phase(cfg_t *c, int dry)\n{',
     A_FIX_ONE + 'static int a_phase(cfg_t *c, int dry)\n{', 'E8 插入 a_fix_one')

# ---------------------------------------- E9: a_phase 主体 -> 多包处置
ANCH = '\n    attr = culprit_attr(0, why, sizeof(why));\n'
END = '        st_set("a_fix_ts", (long)time(NULL));\n    }\n'
# 用带前导换行的锚点：8 空格缩进处（E7 新增的 TICK_ATTR）含 "    attr" 子串，会被误匹配
must(src.count(ANCH) == 1, 'E9 start anchor count=%d' % src.count(ANCH))
i = src.index(ANCH) + 1
j = src.index(END, i) + len(END)
OLD_BLOCK = src[i:j]
must('ATTR_NONE' in OLD_BLOCK, 'E9 block sanity')

NEW_BLOCK = '''    attr = culprit_attr(0, why, sizeof(why));
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

        if (attr == ATTR_PLUGIN) {
            cand[nc++] = "miui.systemui.plugin";
        } else if (attr == ATTR_SYSUI) {
            cand[nc++] = "com.android.systemui";
        } else {
            /* 宿主排在前面：先删宿主覆盖层，日志里更容易看出是哪一步救活的 */
            cand[nc++] = "com.android.systemui";
            cand[nc++] = "miui.systemui.plugin";
        }
        for (k = 0; k < nc; k++) {
            int r = a_fix_one(c, bid, cand[k], dry, why);
            if (r > 0) acted = 1;
        }
        if (!acted) return 0;
    }
'''
src = src[:i] + NEW_BLOCK + src[j:]
n_edits += 1
print('OK  E9 a_phase 多包处置 (%d chars -> %d)' % (len(OLD_BLOCK), len(NEW_BLOCK)))

# ---------------------------------------------------------------- 落盘
io.open(P, 'w', encoding='utf-8').write(src)
print('\n完成 %d 处编辑，已写入 %s' % (n_edits, P))
