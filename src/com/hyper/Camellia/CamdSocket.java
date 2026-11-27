package com.hyper.Camellia;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * camd <-> App 的 unix domain socket 通道（方案A，服务端见 tools/camellia_sock.c）。
 *
 * 【它解决什么】
 * 原有两条通道都要么靠轮询、要么靠 su：
 *   FUSE 通道  /sdcard/Android/data/.../files   camd 每秒 fopen 取指令、App 读文件取状态
 *   ext4 通道  /data/adb/camellia/cmd|caps      App 用 su 写，camd inotify 唤醒
 * 两条都是「以文件为信箱」：App 想知道状态就得反复读，camd 想知道有没有指令就得反复看。
 *
 * socket 把信箱换成一条长连接：
 *   命令  App 直接 write，camd 立刻收到（不 fork su、不落盘）
 *   状态  camd 在 ui_dump 检测到变化时主动推，App 只负责收（不读 FUSE）
 * 传输层是 ext4 上的 App 私有目录，和 /data/adb 快通道相比还少了一次 su 进程。
 *
 * 【为什么用 LocalSocket 而不是 java.net】
 * Android 的 java.net 只有 TCP/UDP，没有 AF_UNIX。LocalSocket(FILESYSTEM
 * namespace) 就是 AF_UNIX 的文件路径 socket，与服务端 bind 的路径对应。
 *
 * 【失败即降级】
 * SELinux 未放行 connectto 时 connect() 抛 EACCES/EPERM，本类只会周期性重试；
 * 调用方（CamdRescue）发现 send()/latest() 不可用就回退文件通道，功能不受影响。
 */
final class CamdSocket {

    private CamdSocket() {}

    /** 与 camd 侧 camd_sock_listen() 拼出的路径严格一致。 */
    static final String PATH = "/data/data/com.hyper.Camellia/files/camd.sock";

    /** 断线重连间隔：camd 重启（升级/被 kill）后靠它重接。 */
    private static final String TAG = "CamdSock";

    private static final long RECONNECT_MS = 5000L;

    /** 重连退避：连续失败时先快速重试再拉长到 RECONNECT_MS。
     *  冷启动实测（2410DPN6CC）：App 常比 camd 先起来，第一次 connect 拿到
     *  ENOENT（socket 文件还没建）或 ECONNREFUSED（还没 listen），固定 5s
     *  会让 UI 有 5s 空白期。200/500/1000/2000 的重试能让它在 camd 就绪后
     *  一秒内接上；camd 长期不在时退到 5s，不空转。 */
    private static final long[] BACKOFF_MS = { 200L, 500L, 1000L, 2000L, 5000L };

    /** 日志状态：仅在「连接状态发生变化」时打一条，避免每 5s 刷 logcat。 */
    private static volatile boolean logged;
    private static int failStreak;

    /** 状态推送间隔是 5s（camd 侧 ui_dump 节流），超过这个时长没新数据
     *  就认为连接已僵死，不再拿旧快照糊弄 UI。 */
    private static final long STALE_MS = 20000L;

    private static final Object LOCK = new Object();

    private static LocalSocket sock;
    private static OutputStream out;

    private static volatile String latest;
    private static volatile long latestAt;
    private static volatile boolean running;

    /** 启动后台收发线程（幂等）。App 进程存活期间一直重连。 */
    static void start() {
        synchronized (LOCK) {
            if (running) return;
            running = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "camd-sock");
        t.setDaemon(true);
        t.start();
    }

    /** 最近一次推送的状态原文（每行 key=value）；未连接/已过期返回 null。 */
    static String latest() {
        String s = latest;
        if (s == null) return null;
        if (SystemClock.elapsedRealtime() - latestAt > STALE_MS) return null;
        return s;
    }

    /** 是否已连上 camd。 */
    static boolean connected() {
        synchronized (LOCK) {
            return sock != null && sock.isConnected();
        }
    }

    /**
     * 把命令行（key=value，'\n' 分隔）发给 camd。
     * 未连接或写失败返回 false，由调用方回退文件通道。
     */
    static boolean send(String lines) {
        OutputStream os;
        synchronized (LOCK) {
            os = out;
        }
        if (os == null) return false;
        try {
            os.write(lines.getBytes("UTF-8"));
            os.flush();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ 收发线程

    private static void loop() {
        while (true) {
            LocalSocket ls = null;
            try {
                ls = new LocalSocket();
                ls.connect(new LocalSocketAddress(PATH,
                        LocalSocketAddress.Namespace.FILESYSTEM));
                InputStream in = ls.getInputStream();
                OutputStream os = ls.getOutputStream();
                synchronized (LOCK) {
                    sock = ls;
                    out = os;
                }
                if (!logged) {
                    logged = true;
                    failStreak = 0;
                    Log.i(TAG, "connected " + PATH);
                }
                readFrames(in);   // 阻塞到对端关闭或出错
            } catch (Throwable t) {
                /* 未授权 / camd 未起 / SELinux 拒绝：静默重试，不打扰用户。
                   日志退避：首次一条，之后每 60 次（约 5 分钟）一条，避免刷 logcat。 */
                failStreak++;
                if (failStreak == 1 || failStreak % 60 == 0) {
                    Log.w(TAG, "connect failed (streak=" + failStreak + "): " + t);
                }
                logged = false;
            } finally {
                synchronized (LOCK) {
                    sock = null;
                    out = null;
                }
                closeQuietly(ls);
            }
            int step = failStreak < BACKOFF_MS.length ? failStreak : BACKOFF_MS.length - 1;
            if (step < 0) step = 0;
            sleepQuietly(BACKOFF_MS[step]);
        }
    }

    /** 帧格式： "<长度>\n<payload>"，payload 自身含换行，所以必须带长度。 */
    private static void readFrames(InputStream in) throws Exception {
        while (true) {
            int len = readHeader(in);
            if (len < 0) return;           // 对端关闭
            if (len == 0) continue;
            byte[] body = readFully(in, len);
            latest = new String(body, "UTF-8");
            latestAt = SystemClock.elapsedRealtime();
        }
    }

    private static int readHeader(InputStream in) throws Exception {
        int n = 0;
        boolean any = false;
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c < '0' || c > '9') throw new Exception("bad header");
            n = n * 10 + (c - '0');
            any = true;
            if (n > (1 << 20)) throw new Exception("frame too large");
        }
        if (c == -1 && !any) return -1;
        return any ? n : -1;
    }

    private static byte[] readFully(InputStream in, int len) throws Exception {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(b, off, len - off);
            if (r < 0) throw new Exception("eof");
            off += r;
        }
        return b;
    }

    private static void closeQuietly(LocalSocket ls) {
        if (ls == null) return;
        try {
            ls.close();
        } catch (Throwable ignored) {
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
