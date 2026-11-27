/*
 * abx_scan.c - Android Binary XML (ABX) structural decoder
 *
 * Purpose: extract  <package name="..." codePath="..."/>  pairs from
 * /data/system/packages.xml with 100% structural accuracy (no heuristics,
 * no neighbourhood window, no substring guessing).
 *
 * ABX wire format (AOSP BinaryXmlSerializer / BinaryXmlPullParser):
 *   header : 'A' 'B' 'X' 0x00
 *   token  : 1 byte, low nibble = XML event, high nibble = value type
 *     event: 0 startDoc 1 endDoc 2 startTag 3 endTag 4 text 5 cdsect
 *            6 entityRef 7 ignorableWs 8 procInstr 9 comment 10 docdecl
 *            15 attribute
 *     type : 0x10 null 0x20 string 0x30 stringInterned 0x40 bytesHex
 *            0x50 bytesBase64 0x60 int 0x70 intHex 0x80 long 0x90 longHex
 *            0xA0 float 0xB0 double 0xC0 true 0xD0 false
 *   interned string: BE u16 index; 0xFFFF means "new string follows"
 *                    new string = BE u16 byte-length + UTF-8 bytes, appended
 *                    to the pool (id = pool size before append)
 *
 * Build: gcc -O2 -static -o abx_scan abx_scan.c
 * Usage: abx_scan <packages.xml>            dump  name<TAB>codePath
 *        abx_scan <packages.xml> <pkgname>  print codePath for one package
 *        abx_scan -t <packages.xml>          dump + timing on stderr
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <time.h>

/* ---- event nibble ---- */
#define EV_START_DOC        0
#define EV_END_DOC          1
#define EV_START_TAG        2
#define EV_END_TAG          3
#define EV_TEXT             4
#define EV_CDSECT           5
#define EV_ENTITY_REF       6
#define EV_IGNORABLE_WS     7
#define EV_PROC_INSTR       8
#define EV_COMMENT          9
#define EV_DOCDECL          10
#define EV_ATTRIBUTE        15

/* ---- value type nibble ---- */
#define T_NULL              0x10
#define T_STRING            0x20
#define T_STRING_INTERNED   0x30
#define T_BYTES_HEX         0x40
#define T_BYTES_BASE64      0x50
#define T_INT               0x60
#define T_INT_HEX           0x70
#define T_LONG              0x80
#define T_LONG_HEX          0x90
#define T_FLOAT             0xA0
#define T_DOUBLE            0xB0
#define T_BOOLEAN_TRUE      0xC0
#define T_BOOLEAN_FALSE     0xD0

#define NEW_STRING_MARKER   0xFFFF
#define MAX_INTERNED        65535

/* a decoded string: points straight into the file buffer (zero copy) */
typedef struct { const char *p; uint16_t n; } sstr;

typedef struct {
    const uint8_t *buf;
    size_t         len;
    size_t         pos;
    const char   **pool_p;
    uint16_t      *pool_n;
    int            pool_count;
    int            pool_cap;
    int            err;
} rd_t;

static inline int rd_byte(rd_t *r) {
    if (r->pos >= r->len) { r->err = 1; return -1; }
    return r->buf[r->pos++];
}
static inline int rd_peek(rd_t *r) {
    if (r->pos >= r->len) return -1;
    return r->buf[r->pos];
}
static inline int rd_short(rd_t *r) {
    if (r->pos + 2 > r->len) { r->err = 1; return -1; }
    int v = ((int)r->buf[r->pos] << 8) | r->buf[r->pos + 1];
    r->pos += 2;
    return v;
}
static int rd_skip(rd_t *r, size_t n) {
    if (r->pos + n > r->len) { r->err = 1; return -1; }
    r->pos += n;
    return 0;
}

/* BE u16 length + UTF-8 bytes, NOT added to the intern pool */
static int read_utf(rd_t *r, sstr *out) {
    int n = rd_short(r);
    if (n < 0) return -1;
    if (r->pos + (size_t)n > r->len) { r->err = 1; return -1; }
    out->p = (const char *)(r->buf + r->pos);
    out->n = (uint16_t)n;
    r->pos += (size_t)n;
    return 0;
}

/* BE u16 index; 0xFFFF => new string (parse + intern), else pool lookup */
static int read_interned(rd_t *r, sstr *out) {
    int idx = rd_short(r);
    if (idx < 0) return -1;
    if (idx == NEW_STRING_MARKER) {
        sstr s;
        if (read_utf(r, &s) != 0) return -1;
        if (r->pool_count < r->pool_cap) {
            r->pool_p[r->pool_count] = s.p;
            r->pool_n[r->pool_count] = s.n;
            r->pool_count++;
        } else {
            r->err = 1;
            return -1;
        }
        *out = s;
        return 0;
    }
    if (idx >= r->pool_count) { r->err = 1; return -1; }
    out->p = r->pool_p[idx];
    out->n = r->pool_n[idx];
    return 0;
}

/* consume one attribute value according to its high-nibble type */
static int skip_value(rd_t *r, int type) {
    sstr tmp;
    int n;
    switch (type) {
    case T_NULL:
        return 0;
    case T_STRING:
        return read_utf(r, &tmp);
    case T_STRING_INTERNED:
        return read_interned(r, &tmp);
    case T_BYTES_HEX:
    case T_BYTES_BASE64:
        n = rd_short(r);
        if (n < 0) return -1;
        return rd_skip(r, (size_t)n);
    case T_INT:
    case T_INT_HEX:
    case T_FLOAT:
        return rd_skip(r, 4);
    case T_LONG:
    case T_LONG_HEX:
    case T_DOUBLE:
        return rd_skip(r, 8);
    case T_BOOLEAN_TRUE:
    case T_BOOLEAN_FALSE:
        return 0;
    default:
        r->err = 1;
        return -1;
    }
}

static inline int sstr_eq(const sstr *s, const char *lit) {
    size_t n = strlen(lit);
    return s->n == n && memcmp(s->p, lit, n) == 0;
}

static inline void sstr_print(const sstr *s) {
    if (s->n) fwrite(s->p, 1, s->n, stdout);
}

/* caller is responsible for buffer allocation */
static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1e6;
}

int main(int argc, char **argv) {
    int timing = 0;
    int ai = 1;
    if (ai < argc && strcmp(argv[ai], "-t") == 0) { timing = 1; ai++; }
    if (ai >= argc) {
        fprintf(stderr, "usage: %s [-t] <packages.xml> [pkgname]\n", argv[0]);
        return 2;
    }
    const char *path = argv[ai++];
    const char *query = (ai < argc) ? argv[ai] : NULL;

    double t0 = timing ? now_ms() : 0.0;

    int fd = open(path, O_RDONLY);
    if (fd < 0) { perror("open"); return 1; }
    struct stat st;
    if (fstat(fd, &st) != 0) { perror("fstat"); close(fd); return 1; }
    size_t len = (size_t)st.st_size;
    uint8_t *buf = malloc(len ? len : 1);
    if (!buf) { fprintf(stderr, "oom\n"); close(fd); return 1; }
    size_t got = 0;
    while (got < len) {
        ssize_t k = read(fd, buf + got, len - got);
        if (k <= 0) break;
        got += (size_t)k;
    }
    close(fd);
    len = got;

    if (len < 4 || buf[0] != 'A' || buf[1] != 'B' || buf[2] != 'X' || buf[3] != 0x00) {
        fprintf(stderr, "not ABX (magic mismatch)\n");
        free(buf);
        return 1;
    }

    rd_t r;
    memset(&r, 0, sizeof(r));
    r.buf = buf;
    r.len = len;
    r.pos = 4; /* skip magic */
    r.pool_cap = MAX_INTERNED;
    r.pool_p = malloc(sizeof(char *) * (size_t)r.pool_cap);
    r.pool_n = malloc(sizeof(uint16_t) * (size_t)r.pool_cap);
    if (!r.pool_p || !r.pool_n) { fprintf(stderr, "oom pool\n"); return 1; }

    /* element stack: tag name + start-tag index (so we know the parent tag) */
    enum { MAX_DEPTH = 64 };
    sstr stack[MAX_DEPTH];
    int depth = 0;

    long n_pkg = 0, n_upd = 0, n_match = 0;
    int found = 0;

    while (!r.err) {
        int tok = rd_byte(&r);
        if (tok < 0) break;
        int ev = tok & 0x0F;
        int ty = tok & 0xF0;

        if (ev == EV_END_DOC) break;

        if (ev == EV_START_TAG) {
            sstr tag;
            if (read_interned(&r, &tag) != 0) break;

            sstr a_name = {0, 0}, a_codepath = {0, 0};
            int has_name = 0, has_codepath = 0;
            int is_package = sstr_eq(&tag, "package");
            int is_updated = sstr_eq(&tag, "updated-package");
            int capture = is_package || is_updated;

            /* attributes are inlined immediately after the tag name */
            while (!r.err) {
                int p = rd_peek(&r);
                if (p < 0) break;
                if ((p & 0x0F) != EV_ATTRIBUTE) break;
                int at = rd_byte(&r);
                int aty = at & 0xF0;
                sstr an;
                if (read_interned(&r, &an) != 0) break;

                if (capture && aty == T_STRING) {
                    /* package name / codePath are plain strings, capture before skipping */
                    if (sstr_eq(&an, "name")) {
                        if (read_utf(&r, &a_name) == 0) has_name = 1;
                        continue;
                    }
                    if (sstr_eq(&an, "codePath")) {
                        if (read_utf(&r, &a_codepath) == 0) has_codepath = 1;
                        continue;
                    }
                } else if (capture && aty == T_STRING_INTERNED) {
                    if (sstr_eq(&an, "name")) {
                        if (read_interned(&r, &a_name) == 0) has_name = 1;
                        continue;
                    }
                    if (sstr_eq(&an, "codePath")) {
                        if (read_interned(&r, &a_codepath) == 0) has_codepath = 1;
                        continue;
                    }
                }
                if (skip_value(&r, aty) != 0) break;
            }
            if (r.err) break;

            /* structural binding: name + codePath came from THIS element */
            if (capture && has_name && has_codepath) {
                if (is_updated) n_upd++; else n_pkg++;

                if (query) {
                    /* query mode reports only the live <package> entry */
                    if (is_package && a_name.n == strlen(query) &&
                        memcmp(a_name.p, query, a_name.n) == 0) {
                        n_match++;
                        sstr_print(&a_name);
                        putchar('\t');
                        sstr_print(&a_codepath);
                        putchar('\n');
                        found = 1;
                        break;
                    }
                } else {
                    putchar(is_package ? 'P' : 'U');
                    putchar('\t');
                    sstr_print(&a_name);
                    putchar('\t');
                    sstr_print(&a_codepath);
                    putchar('\n');
                    n_match++;
                }
            }

            if (depth < MAX_DEPTH) stack[depth] = tag;
            depth++;
        } else if (ev == EV_END_TAG) {
            sstr tag;
            if (read_interned(&r, &tag) != 0) break;
            if (depth > 0) depth--;
        } else if (ty == T_STRING &&
                   (ev == EV_TEXT || ev == EV_CDSECT || ev == EV_COMMENT ||
                    ev == EV_PROC_INSTR || ev == EV_DOCDECL ||
                    ev == EV_ENTITY_REF || ev == EV_IGNORABLE_WS ||
                    ev == EV_START_DOC)) {
            sstr tmp;
            if (read_utf(&r, &tmp) != 0) break;
        } else if (ev == EV_START_DOC) {
            /* nothing */
        } else {
            /* unknown event with a value type we cannot size -> stop safely */
            if (skip_value(&r, ty) != 0) break;
        }
    }

    if (query && !found) {
        printf("%s\t<NOT FOUND>\n", query);
    }

    if (timing) {
        double t1 = now_ms();
        fprintf(stderr, "[abx_scan] file=%.2f MB  package=%ld  updated=%ld  "
                        "emitted=%ld  pool=%d  err=%d  elapsed=%.3f ms\n",
                (double)len / (1024.0 * 1024.0), n_pkg, n_upd, n_match,
                r.pool_count, r.err, t1 - t0);
    }

    free(r.pool_p);
    free(r.pool_n);
    free(buf);

    /* exit codes (caller must treat 4 as "retry", file may be mid-write):
     *   0 complete parse   1 file/open error
     *   3 query not found  4 truncated/parse error   */
    if (query && !found) return 3;
    if (r.err) return 4;
    return 0;
}
