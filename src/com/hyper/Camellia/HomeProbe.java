package com.hyper.Camellia;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OS4 桌面（com.miui.home）只读探针 —— Phase 1a。
 *
 * <p>目的：在<b>不修改任何内存</b>的前提下，验证 OS4 桌面 Dart AOT 代码是否满足
 * inline hook 的前置条件：</p>
 * <ol>
 *   <li>libapp.so 是否被原样映射进进程（内存字节 vs 磁盘/APK 字节一致 → 未加壳、未加密）；</li>
 *   <li>目标函数 {@code _getAllSystemShortcutMenuItem} 能否在内存中定位
 *       （找到即得到它的运行时地址）。</li>
 * </ol>
 *
 * <p>为什么纯 Java 就够：Android 10+ 的 APK 内 .so 是直接 mmap 的，
 * {@code /proc/self/maps} 里显示的是 {@code base.apk} + APK 内偏移，拿不到 so 的
 * 加载基址。但反过来，"扫描可执行段找指令指纹"不需要基址——找到的位置本身就是
 * 运行时地址，比 base+offset 更直接，也不依赖 APK 里 .so 的起始偏移。</p>
 *
 * <p>安全性：全程只读（maps 读 + /proc/self/mem 读 + 磁盘读）。不写内存、不改权限、
 * 不 hook、不 dlopen。任何异常都吞掉，绝不影响桌面进程。</p>
 *
 * <p>指纹依据：{@code libapp.so} 偏移 {@code 0x010abd5c} 处是
 * {@code ShortcutMenuGetXController._getAllSystemShortcutMenuItem} 的入口，
 * 一个 76 字节的 Dart AOT stub（stp x29,x30 / mov x29,sp / sub sp,sp,#0x20 … ret）。
 * 函数体内含 Dart field {@code 0x69fc8} 的读取
 * （{@code add x1,x19,#0x69,lsl#12} + {@code ldr x1,[x1,#0xfc8]}）。
 * 该段全部是相对偏移与立即数、不含绝对地址，因此内存与磁盘字节必然一致，可作指纹。</p>
 */
public final class HomeProbe {

    private static final String TAG = "CamelliaHomeProbe";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    /** 目标函数在 libapp.so 内的文件偏移（Dart AOT 符号 _getAllSystemShortcutMenuItem）。 */
    private static final long SYM_FILE_OFF = 0x010abd5cL;

    /**
     * 32 字节指令指纹：函数序言 + 独特操作序列。
     * fd 79 bf a9 = stp x29,x30,[sp,#-0x10]!
     * fd 03 0f aa = mov x29,sp
     * ef 81 00 d1 = sub sp,sp,#0x20
     * e0 03 02 aa = mov x0,x2
     * a2 83 1f f8 01 f0 5f f8 = stur/ldur 组合
     * 21 7c 4c d3 = ubfx x1,x1,#12,#20
     * 3f dc 1e f1 = cmp x1,#0x7b7
     */
    private static final byte[] SIG = {
            (byte) 0xfd, (byte) 0x79, (byte) 0xbf, (byte) 0xa9,
            (byte) 0xfd, (byte) 0x03, (byte) 0x0f, (byte) 0xaa,
            (byte) 0xef, (byte) 0x81, (byte) 0x00, (byte) 0xd1,
            (byte) 0xe0, (byte) 0x03, (byte) 0x02, (byte) 0xaa,
            (byte) 0xa2, (byte) 0x83, (byte) 0x1f, (byte) 0xf8,
            (byte) 0x01, (byte) 0xf0, (byte) 0x5f, (byte) 0xf8,
            (byte) 0x21, (byte) 0x7c, (byte) 0x4c, (byte) 0xd3,
            (byte) 0x3f, (byte) 0xdc, (byte) 0x1e, (byte) 0xf1,
    };

    private static final int BLK = 64 * 1024;
    private static final long WAIT_MS = 60_000L;
    private static final long POLL_MS = 200L;

    private HomeProbe() {
    }

    /** 幂等启动；可在 onModuleLoaded / onPackageLoaded 任一处调用。 */
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    probe();
                } catch (Throwable ignored) {
                }
            }
        }, "camellia-home-probe");
        t.setDaemon(true);
        t.start();
    }

    private static void probe() {
        note("=== start pid=" + android.os.Process.myPid()
                + " proc=" + readProcName()
                + " uid=" + android.os.Process.myUid());

        long deadline = System.currentTimeMillis() + WAIT_MS;
        List<Seg> segs = null;
        while (System.currentTimeMillis() < deadline) {
            segs = readMaps();
            if (countTarget(segs) > 0) {
                break;
            }
            sleep(POLL_MS);
        }
        if (segs == null || countTarget(segs) == 0) {
            note("TIMEOUT: 60s 内未发现 com.miui.home 可执行段（libapp.so 可能未加载）");
            note("maps 中 miui 相关行:\n" + grepMaps("miui"));
            note("maps 中 base.apk 相关行(前20):\n" + grepMaps("base.apk"));
            return;
        }
        note("maps 命中 " + countTarget(segs) + " 个 com.miui.home 可执行段");

        for (Seg s : segs) {
            if (!s.isExec() || !s.isTarget()) {
                continue;
            }
            note("scan " + s.describe());
            long hit = search(s);
            if (hit != 0L) {
                verifyHit(s, hit);
                return;
            }
        }
        note("MISS: 所有目标可执行段里都没有找到指纹");
    }

    /** 命中后做「内存 vs 磁盘」逐字节比对，给出可复现的结论。 */
    private static void verifyHit(Seg s, long hit) {
        long fileOff = s.offset + (hit - s.start);
        note("HIT addr=0x" + Long.toHexString(hit)
                + " segStart=0x" + Long.toHexString(s.start)
                + " segFileOff=0x" + Long.toHexString(s.offset)
                + " -> fileOff=0x" + Long.toHexString(fileOff));
        note("HIT 段: " + s.describe());

        byte[] mem = readMem(hit, SIG.length + 32);
        byte[] disk = readFile(s.path, fileOff, SIG.length + 32);
        note("mem  = " + (mem == null ? "<unreadable>" : hex(mem)));
        note("disk = " + (disk == null ? "<unreadable>" : hex(disk)));
        if (mem != null && disk != null) {
            boolean eq = java.util.Arrays.equals(mem, disk);
            note("VERDICT equal=" + eq
                    + (eq ? " -> 代码原样映射，inline hook 前置条件成立"
                          : " -> 存在差异（加壳/校验/重定位，需进一步分析）"));
        }
        // 额外：报告该地址相对 so 文件偏移（用 libapp.so 的符号偏移对照）
        note("对照: 预期符号偏移 0x" + Long.toHexString(SYM_FILE_OFF)
                + (fileOff == SYM_FILE_OFF ? "（完全相等）" : "（不等，见上：APK 内偏移与 so 内偏移的差异）"));
    }

    /** 在段内以 64KB 块扫描指纹；返回绝对虚拟地址，未命中返回 0。 */
    private static long search(Seg s) {
        byte[] buf = new byte[BLK + SIG.length - 1];
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile("/proc/self/mem", "r");
            long pos = s.start;
            int carry = 0;
            while (pos < s.end) {
                int want = (int) Math.min(BLK, s.end - pos);
                int total;
                try {
                    f.seek(pos);
                    f.readFully(buf, carry, want);
                    total = carry + want;
                } catch (Throwable t) {
                    // 该块不可读（guard page 等），跳过
                    carry = 0;
                    pos += want;
                    continue;
                }
                int idx = indexOf(buf, total, SIG);
                if (idx >= 0) {
                    return pos - carry + idx;
                }
                carry = Math.min(SIG.length - 1, total);
                System.arraycopy(buf, total - carry, buf, 0, carry);
                pos += want;
            }
        } catch (Throwable ignored) {
        } finally {
            if (f != null) {
                try {
                    f.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return 0L;
    }

    private static int indexOf(byte[] hay, int hayLen, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hayLen; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] readMem(long addr, int len) {
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile("/proc/self/mem", "r");
            f.seek(addr);
            byte[] b = new byte[len];
            f.readFully(b);
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (f != null) {
                try {
                    f.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static byte[] readFile(String path, long off, int len) {
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(path, "r");
            f.seek(off);
            byte[] b = new byte[len];
            f.readFully(b);
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (f != null) {
                try {
                    f.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static List<Seg> readMaps() {
        List<Seg> out = new ArrayList<Seg>();
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile("/proc/self/maps", "r");
            String line;
            while ((line = f.readLine()) != null) {
                Seg s = Seg.parse(line);
                if (s != null) {
                    out.add(s);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (f != null) {
                try {
                    f.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static int countTarget(List<Seg> segs) {
        if (segs == null) {
            return 0;
        }
        int n = 0;
        for (Seg s : segs) {
            if (s.isExec() && s.isTarget()) {
                n++;
            }
        }
        return n;
    }

    private static String grepMaps(String kw) {
        StringBuilder sb = new StringBuilder();
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile("/proc/self/maps", "r");
            String line;
            int n = 0;
            while ((line = f.readLine()) != null) {
                if (line.contains(kw)) {
                    sb.append("  ").append(line).append('\n');
                    if (++n >= 20) {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (f != null) {
                try {
                    f.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.length() == 0 ? "  <none>" : sb.toString();
    }

    private static String readProcName() {
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile("/proc/self/cmdline", "r");
            byte[] b = new byte[256];
            int n = f.read(b);
            int end = 0;
            while (end < n && b[end] != 0) {
                end++;
            }
            return new String(b, 0, end, "UTF-8");
        } catch (Throwable t) {
            return "?";
        } finally {
            if (f != null) {
                try {
                    f.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {
        }
    }

    private static void note(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
        try {
            File dir = new File("/sdcard/Android/media/com.hyper.Camellia");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            FileOutputStream out = new FileOutputStream(new File(dir, "probe.txt"), true);
            out.write(("[probe] home " + msg + "\n").getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    /** /proc/self/maps 的一行。 */
    static final class Seg {
        long start;
        long end;
        long offset;
        String perms;
        String path;

        static Seg parse(String line) {
            try {
                String[] p = line.trim().split("\\s+", 6);
                if (p.length < 5) {
                    return null;
                }
                String[] r = p[0].split("-");
                if (r.length != 2) {
                    return null;
                }
                Seg s = new Seg();
                s.start = Long.parseLong(r[0], 16);
                s.end = Long.parseLong(r[1], 16);
                s.perms = p[1];
                s.offset = Long.parseLong(p[2], 16);
                s.path = p.length >= 6 ? p[5] : "";
                return s;
            } catch (Throwable t) {
                return null;
            }
        }

        boolean isExec() {
            return perms != null && perms.indexOf('x') >= 0;
        }

        boolean isTarget() {
            return path != null && path.contains("com.miui.home");
        }

        String describe() {
            return String.format("0x%x-0x%x %s off=0x%x %s",
                    start, end, perms, offset, path);
        }
    }
}
