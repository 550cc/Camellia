package com.hyper.Camellia;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

/**
 * 小米画报（com.mfashiongallery.emag）自选壁纸「目录同步」。
 *
 * <p>对应 smali 改包版手册里的 CwDirSync，这里是 Xposed 实现：不往 dex 里塞新类，
 * 而是在目标进程的三个入口挂 hook，再用目标 App 自己的 ContentProvider /
 * WallpaperItem 构造器完成写库，因此不需要改包、不需要重签名，目录可配置。</p>
 *
 * <p>三个入口（与手册一致）：</p>
 * <pre>
 *   WallpaperManagerFragment#onCreate(Bundle)   进入「自选图片」页
 *   CmPresenter#load()                          列表加载前（紧贴读库）
 *   LockscreenMagazineProvider#init()           锁屏/Provider 进程被拉起
 * </pre>
 */
public final class Fg {

    private static final String TAG = "MiShareMT2/Fg";

    /** 目标包名 */
    public static final String PKG = "com.mfashiongallery.emag";
    /** 「自选图片」页（设置页用它做「打开并同步」入口） */
    public static final String CLS_ACTIVITY =
            "com.mfashiongallery.emag.customwallpaper.manager.WallpaperManagerActivity";
    /** 默认同步目录 */
    public static final String DEF_DIR = "/storage/emulated/0/Pictures/小米画报/";

    private static final String CLS_FRAGMENT =
            "com.mfashiongallery.emag.customwallpaper.manager.WallpaperManagerFragment";
    private static final String CLS_PRESENTER =
            "com.mfashiongallery.emag.customwallpaper.manager.CmPresenter";
    private static final String CLS_PROVIDER =
            "com.mfashiongallery.emag.LockscreenMagazineProvider";
    private static final String CLS_ITEM = "com.mfashiongallery.emag.lks.WallpaperItem";
    private static final String CLS_DBDEF = "com.mfashiongallery.emag.db.MiFGDBDef";

    /** 画报「数据缓存」库（右滑轮播 feedcache 表所在），与 lks_wp 不在同一个 authority */
    private static final String DC_AUTH = "com.mfashiongallery.emag.datacache";
    /** 右滑轮播在 feedcache 里的分组标记 */
    private static final String REQ_LEFT_LKS = "left_lks";
    /** 「精选壁纸」条目在 feedcache 里的分组标记 */
    private static final String REQ_MORNING = "morning_detail";
    /** 锁屏右滑轮播条目在 feedcache 里的 bizids 取值 */
    private static final String BIZ_LKS = "lks";

    /** 与官方「加自选」一致的两个常量 */
    private static final String TYPE_PIC = "l_pic";
    private static final String BIZ_CW = "cw";

    /** 缩略图子目录：以 . 开头，collectDir 会跳过，不会把自己扫成候选 */
    private static final String THUMB_SUB = ".thumb";
    /** 超过这个体积才考虑缩图（小图直接原样用，省 IO） */
    private static final long SHRINK_OVER_BYTES = 900L * 1024L;
    /** 缩略图最长边：锁屏 1440 级别足够，再大只是白烧内存 */
    private static final int THUMB_MAX_EDGE = 1440;
    /** 缩略图 JPEG 质量 */
    private static final int THUMB_QUALITY = 82;
    /** 单轮最多处理多少张：画报退到后台很快被冻结，跑太久等于白跑 */
    private static final int THUMB_PER_RUN = 48;
    /** 超过这个体积的原图直接放弃缩图（几十 MB 的 PNG 解码会把线程拖死） */
    private static final long SHRINK_SKIP_BYTES = 48L * 1024L * 1024L;

    private static final String[] EXTS = {"jpg", "jpeg", "png", "webp"};
    private static final int DEF_CAP = 100;
    /** 多个入口会在同一次交互里连续触发，800ms 内只同步一次 */
    private static final long DEBOUNCE_MS = 800;

    private static final String[] STATUS_PATHS = {
            "/sdcard/Android/media/com.hyper.Camellia/misharemt2.fg.status",
            "/storage/emulated/0/Android/media/com.hyper.Camellia/misharemt2.fg.status",
    };

    /**
     * 状态文件实际可写路径。
     *
     * 画报进程（uid=u0_a281）对 /sdcard/Android/media/com.hyper.Camellia/
     * 没有写权限（目录属主是模块 app），而且 MediaProvider 明确禁止通过
     * content://media/external_primary/file 写 Android/ 目录（allowed directories
     * are [Download, Documents]），所以旧路径必然失败、status 永远是陈旧的。
     * 改写到画报**自己的** files 目录，由模块 app 侧用 root 读走。
     */
    private static String[] statusPaths() {
        try {
            Context c = sCtx;
            if (c != null) {
                File d = dataDir(c);
                if (d != null) {
                    return new String[]{new File(d, "fg.status").getAbsolutePath()};
                }
            }
        } catch (Throwable ignored) {
        }
        return STATUS_PATHS;
    }

    /**
     * 配置缓存目录/文件：写在画报**自己的** data 目录里。
     * 跨 uid 读不到模块 app 的私有目录，但读自己的没问题，所以推送来的配置落在这里。
     */
    private static final String CACHE_SUBDIR = "misharemt2";
    private static final String MANAGED_FILE = "managed.txt";

    /**
     * 已处理过的 resetSeq 标记。
     *
     * <p>「清右滑进度」原先只靠 {@code CfgBridge.ACTION_PUSH} 广播即时触发，
     * 但用户在设置页点「重新随机」时画报进程往往没在跑，广播直接丢失；
     * 下次冷启动又只把 resetSeq 记成基线（防止每次重启清库），
     * 于是「重新随机」看起来点了没反应。这里把已处理值落盘，
     * 冷启动时比对即可补上那次丢失的清理。</p>
     */
    private static final String RESETSEQ_FILE = "resetseq.txt";

    /** 配置缓存有效期（ms）：设置页推送会立刻失效，这里只是兜底 */
    private static final long CFG_TTL_MS = 1500;

    private static XposedModule sMod;
    private static ClassLoader sCl;
    private static volatile Context sCtx;

    private static volatile boolean sRunning;
    /** 是否已安装 hook：onPackageLoaded 与 onPackageReady 双回调，必须幂等 */
    private static volatile boolean sInstalledFg;
    private static volatile long sLastTriggerAt;
    private static volatile String sLastResult = "never";
    private static volatile long sLastCostMs;
    private static volatile int sLastAdded;
    private static volatile int sLastPruned;
    private static volatile int sLastSkipped;
    /** 本次清掉的网络推荐图数量（本地照片模式） */
    private static volatile int sLastPurged;
    /** 上一轮残留扫描看到的 cw 行总数（诊断用：一直不降说明清理没生效） */
    private static volatile int sLastPurgeScan;
    /** 本次写入右滑轮播池（feedcache/left_lks）的条目数 */
    private static volatile int sLastLeftLks;
    /** 本轮「精简精选壁纸」清掉的条目数 */
    private static volatile int sCuratedPruned;
    /** 画报订阅分类缓存，格式 "t_id:name|t_id:name|..."，供设置页做多选 */
    private static volatile String sCats;
    private static volatile Thread sPurgeThread;
    private static volatile long sLastPurgeAt;
    /** feedcache 表 URI 缓存 */
    private static volatile Uri sFeedUri;
    /** 缩略图后台线程（单实例，守护） */
    private static volatile Thread sThumbThread;
    /** 本轮缩略图任务是否还在跑 */
    private static volatile boolean sThumbBusy;
    /** 本轮缩略图任务开始时刻（线程被系统冻结时 finally 不执行，靠它兜底解锁） */
    private static volatile long sThumbAt;
    /** 最近一轮缩略图成果，写进状态文件便于排查 */
    private static volatile String sLastThumb = "-";
    /** 上一轮 {@link #dedupeThumbRows} 结果：scan/missPriv/deleted，异常时追加 /err:类型 */
    private static volatile String sLastDedupe = "-";
    private static volatile ContentObserver sPurgeObserver;
    private static volatile boolean sPurgeInit;
    private static volatile String sLastPool = "";
    private static volatile String sHookInfo = "none";
    private static volatile String sFpInfo = "none";
    private static volatile boolean sFpRootInjected;
    private static volatile String sPermInfo = "-";
    private static volatile String sLastDir = DEF_DIR;
    private static volatile Cfg sCfg;
    private static volatile long sCfgAt;
    /** 快照对应的配置失效代际（{@link Prefs#cfgGen()}）：代际一变立刻重建，不等 TTL */
    private static volatile long sCfgGen = -1L;
    private static volatile String sCfgSrc = "-";
    private static volatile int sLastCap = DEF_CAP;
    private static volatile int sLastCand;
    /** 目录 list() 实际返回条目数（Android 13+ 分区存储下可能远小于真实文件数） */
    private static volatile int sLastListed;
    /** 进程内自检结论：ok / perm / empty / index */
    private static volatile String sLastNeed = "-";
    /** 最近一次写状态文件的时间（uptimeMillis），用于「指纹未变早退」补写的节流 */
    private static volatile long sStatusAt;
    private static volatile boolean sCfgRxReady;
    /** 同步途中收到配置推送 → 收尾后按新配置补跑一次 */
    private static volatile boolean sResyncPending;
    /** 共享路径探测只做一次 */
    private static volatile boolean sProbed;
    /** 最近一次同步采用的加载顺序 */
    private static volatile String sLastOrder = Prefs.ORDER_SEQ;
    /**
     * 已消费的「右滑进度重置序号」。初次读到配置时只同步、不清库，
     * 避免画报进程重启后把用户进度误清空。
     */
    private static volatile long sLastResetSeq;
    private static volatile boolean sResetSeqInited;
    /** 上次成功同步的「内容指纹」：配置 + 候选文件集，用来判断这次有没有活要干 */
    private static volatile String sFp;
    private static volatile long sFpAt;
    /**
     * 指纹有效期（毫秒）：期间内指纹没变就直接跳过全量同步。
     *
     * 指纹只看得到「图片集合 / 配置」，看不到「库被别的代码改动」这类变化，
     * 所以留一个自愈窗口，过期后强制全量跑一次兜底。
     */
    private static final long FP_TTL_MS = 600000L;

    /**
     * 「指纹未变」早退时允许补写状态文件的最小间隔（毫秒）。
     *
     * <p>画报每次读库都会触发一轮同步，正常路径下几乎都落在 {@link #FP_TTL_MS}
     * 指纹窗口内直接早退。早退如果完全不落状态，状态文件就会一直停在上一轮
     * 「完整同步」的时间戳上——用户看到的就是「模块没在跑 / 功能没生效」。
     * 这里按 5 秒节流补写：既不刷盘太狠，也能让时间戳保持新鲜。
     */
    private static final long STATUS_MIN_MS = 5000L;

    /**
     * 单轮同步允许花在「懒复制私有缩略图」上的时间预算（毫秒）。
     *
     * <p>用户点「清除画报缓存」会把 {@code files/img_cache/left_lks_wp} 一起删掉，
     * 私有副本全没。此时 {@link #pickForDb} 会在主线程当场补一份，否则这一轮
     * 一张图都入不了库，锁屏／壁纸轮播直接变空池。但全量几百张一次性复制
     * 会顶到锁屏进程的 ANR 线，所以给一个时间预算：本轮补不完的交给
     * {@link #kickThumbWorker} 的后台队列继续，下一次同步自然补齐。
     */
    private static final long LAZY_COPY_BUDGET_MS = 2000L;
    /** 缩略图未就绪时最多补跑几轮（防止某张图永远生成不出缩略图造成死循环） */
    private static final int MAX_LAZY_FOLLOWUP = 5;
    /** 补跑延迟：给后台缩略图线程留出生成时间 */
    private static final long LAZY_FOLLOWUP_MS = 1500L;
    /** 本轮懒复制已花掉的时间（毫秒），每轮同步开头清零 */
    private static long sLazyCopySpent;
    /** 本轮懒复制成功的张数，仅用于状态展示 */
    private static int sLazyCopyCount;
    /** 本轮因「入库副本还没生成」被跳过的张数（>0 表示这一轮不是完整同步） */
    private static volatile int sDbSkipNoThumb;
    /** 已补跑轮数 */
    private static volatile int sLazyFollowup;

    /** 同步串行锁：保证「排队标记」和「跑完清标记」不会互相踩 */
    private static final Object sSyncLock = new Object();
    /** 已有一轮同步在后台排队／执行中，后续触发直接丢弃（合并成一次） */
    private static volatile boolean sSyncQueued;
    /** 最近一轮检测到「画报私有缓存被清空」（副本数不足候选数一半） */
    private static volatile boolean sLastPrivWiped;
    /**
     * 【v2.31.6】老版本整批行共用同一个 cache_o。
     * 画报「取下一张」是 order by cache_o asc，cache_o 全等时排序退化，
     * 顺序只能靠 _id 兜底 —— 加载顺序改了也不会生效。
     * 0=本进程还没查过 1=顺序正常 2=发现重复，需要走一次全量重排迁移
     */
    private static volatile int sOrderNorm;

    private static volatile Constructor<?> sItemCtor;
    private static volatile boolean sItemCtorBad;
    private static volatile Uri sUri;
    private static volatile Class<?> sFpClass;

    private Fg() {
    }

    // =====================================================================
    // 安装
    // =====================================================================

    public static void install(XposedModule mod, ClassLoader cl) {
        if (sInstalledFg) {
            return;
        }
        sInstalledFg = true;
        sMod = mod;
        sCl = cl;
        long t0 = SystemClock.uptimeMillis();
        StringBuilder hooked = new StringBuilder();
        // 手册三处注入：onCreate 末尾 / load 开头 / init 开头
        if (tryHook(cl, CLS_FRAGMENT, "onCreate",
                new Class<?>[]{android.os.Bundle.class}, false, "fragment.onCreate")) {
            hooked.append("fragment.onCreate ");
        }
        if (tryHook(cl, CLS_PRESENTER, "load", new Class<?>[0], true, "presenter.load")) {
            hooked.append("presenter.load ");
        }
        if (tryHook(cl, CLS_PROVIDER, "init", new Class<?>[0], true, "provider.init")) {
            hooked.append("provider.init ");
        }
        // 推广屏蔽 + 本地照片替换（失败只记日志，不影响上面的核心注入）
        try {
            hooked.append("ad[").append(installAdBlock(cl)).append("] ");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "installAdBlock failed: " + t);
        }
        try {
            hooked.append("photo[").append(installLocalPhoto(cl)).append("] ");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "installLocalPhoto failed: " + t);
        }
        sHookInfo = hooked.length() == 0 ? "none" : hooked.toString().trim();
        try {
            patchFileProvider(cl);
        } catch (Throwable t) {
            sFpInfo = "error";
            log(Log.ERROR, TAG, "patchFileProvider failed: " + t);
        }
        log(Log.INFO, TAG, "画报 install hooks=[" + sHookInfo + "] fp=" + sFpInfo
                + " cost=" + (SystemClock.uptimeMillis() - t0) + "ms");
        writeStatus();
    }

    // =====================================================================
    // 推广屏蔽（广告不请求 / 不入池 / 不展示）
    // =====================================================================

    private static final String CLS_WP_AD = "com.mfashiongallery.emag.utils.WPAdManager";
    private static final String CLS_WP_MGR = "com.mfashiongallery.emag.lks.WallpaperManager";
    private static final String CLS_APPLY_WP = "com.mfashiongallery.emag.wallpaper.ApplyWallpaper";
    private static final String CLS_APPLY_LOCK = "com.mfashiongallery.emag.wallpaper.ApplyLockScreenWallpaper";
    private static final String CLS_APPLY_DESK = "com.mfashiongallery.emag.wallpaper.ApplyDesktopWallpaper";
    private static final String CLS_WP_INFO = "com.mfashiongallery.emag.preview.model.WallpaperInfo";
    /** 壁纸类型里表示广告的值 */
    private static final String TYPE_AD = "s_ads";

    /** 实时开关：cfg() 每次解析配置时刷新，hook 里零开销读取 */
    private static volatile boolean sBlockAd = true;
    /** 「禁用推广」实际拦截命中次数（每拦一条广告 +1）——设置页用它证明开关真的在干活 */
    private static final java.util.concurrent.atomic.AtomicInteger sAdHits =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 成功装上的广告拦截 hook 数量（装不上就等于没生效） */
    private static volatile int sAdHooks;
    /** 上次拦到广告的时间戳（uptimeMillis），0 表示从未拦到 */
    private static volatile long sAdLastAt;
    private static volatile boolean sLocalPhoto;
    private static volatile String sPhotoDir;
    private static final java.util.Random sRandom = new java.util.Random();

    /**
     * 广告拦截：WPAdManager 的四个判定点 + 轮播池里的 s_ads 过滤。
     * 全部为独立 try：某个类改名只丢一项，不影响其他。
     */
    private static String installAdBlock(ClassLoader cl) {
        StringBuilder sb = new StringBuilder();
        if (hookConstBoolean(cl, CLS_WP_AD, "isRequestAds", false)) {
            sb.append("isRequestAds ");
        }
        if (hookConstBoolean(cl, CLS_WP_AD, "hasShowToday", true)) {
            sb.append("hasShowToday ");
        }
        if (hookConstBoolean(cl, CLS_WP_AD, "isResourceReady", false)) {
            sb.append("isResourceReady ");
        }
        if (hookConstString(cl, CLS_WP_AD, "getImageIds", "")) {
            sb.append("getImageIds ");
        }
        if (hookDropAdFromPool(cl)) {
            sb.append("pool ");
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    /** 把无参 boolean 方法钉成常量（sBlockAd 关时照常走原逻辑） */
    private static boolean hookConstBoolean(ClassLoader cl, String cls, String method, final boolean value) {
        try {
            Class<?> c = Class.forName(cls, false, cl);
            java.lang.reflect.Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                java.lang.reflect.Method m = ms[i];
                if (!m.getName().equals(method) || m.getParameterTypes().length != 0
                        || m.getReturnType() != boolean.class) {
                    continue;
                }
                m.setAccessible(true);
                sMod.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        if (!sBlockAd) {
                            return chain.proceed();
                        }
                        sAdHits.incrementAndGet();
                        sAdLastAt = SystemClock.uptimeMillis();
                        return Boolean.valueOf(value);
                    }
                });
                sAdHooks++;
                verbose("hooked ad " + cls + "#" + method + "=" + value);
                return true;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "ad hook miss " + cls + "#" + method + ": " + t);
        }
        return false;
    }

    /** 把无参 String 方法钉成常量（如 getImageIds 返回空串 = 没有待展示广告图） */
    private static boolean hookConstString(ClassLoader cl, String cls, String method, final String value) {
        try {
            Class<?> c = Class.forName(cls, false, cl);
            java.lang.reflect.Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                java.lang.reflect.Method m = ms[i];
                if (!m.getName().equals(method) || m.getParameterTypes().length != 0
                        || m.getReturnType() != String.class) {
                    continue;
                }
                m.setAccessible(true);
                sMod.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        if (!sBlockAd) {
                            return chain.proceed();
                        }
                        sAdHits.incrementAndGet();
                        sAdLastAt = SystemClock.uptimeMillis();
                        return value;
                    }
                });
                sAdHooks++;
                verbose("hooked ad " + cls + "#" + method + "=\"" + value + "\"");
                return true;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "ad hook miss " + cls + "#" + method + ": " + t);
        }
        return false;
    }

    /** 轮播池过滤：loadWallpaperByBizId 返回的链表里剔掉 type == s_ads 的条目 */
    private static boolean hookDropAdFromPool(ClassLoader cl) {
        try {
            final Class<?> itemCls = Class.forName(CLS_ITEM, false, cl);
            final java.lang.reflect.Method getType = itemCls.getMethod("getType");
            Class<?> mgr = Class.forName(CLS_WP_MGR, false, cl);
            java.lang.reflect.Method[] ms = mgr.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                final java.lang.reflect.Method m = ms[i];
                if (!m.getName().equals("loadWallpaperByBizId")) {
                    continue;
                }
                if (!java.util.List.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                m.setAccessible(true);
                sMod.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        Object r = chain.proceed();
                        if (!sBlockAd || r == null) {
                            return r;
                        }
                        try {
                            java.util.List<?> list = (java.util.List<?>) r;
                            java.util.Iterator<?> it = list.iterator();
                            int n = 0;
                            while (it.hasNext()) {
                                Object item = it.next();
                                if (item == null) {
                                    continue;
                                }
                                Object type = getType.invoke(item);
                                if (TYPE_AD.equals(type)) {
                                    it.remove();
                                    n++;
                                }
                            }
                            if (n > 0) {
                                sAdHits.addAndGet(n);
                                sAdLastAt = SystemClock.uptimeMillis();
                                verbose("ad pool dropped " + n);
                            }
                        } catch (Throwable t) {
                            verbose("ad pool filter failed: " + t);
                        }
                        return r;
                    }
                });
                sAdHooks++;
                verbose("hooked ad pool " + CLS_WP_MGR + "#loadWallpaperByBizId");
                return true;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "ad pool hook miss: " + t);
        }
        return false;
    }

    // =====================================================================
    // 本地照片替换（锁屏 / 桌面画报换图时用同步目录里的随机照片）
    // =====================================================================

    private static String installLocalPhoto(ClassLoader cl) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> infoCls = Class.forName(CLS_WP_INFO, false, cl);
            java.lang.reflect.Field uriF = infoCls.getField("wallpaperUri");
            java.lang.reflect.Method isAdM = null;
            try {
                isAdM = infoCls.getMethod("isAd");
            } catch (Throwable ignored) {
            }
            // 主替换点：generateWallpaperInfo() 之后、可读性校验之前，锁屏/桌面两条路径都经过这里
            if (hookGenerateInfo(cl, infoCls, uriF, isAdM)) {
                sb.append("gen ");
            }
            // 【已移除】兜底的 wallpaperUri 替换（setImageAsWallpaper / setWp）。
            // 这两个方法是「真正设壁纸」的入口，在这里把 uri 换成本地图，
            // 等于把锁屏/桌面壁纸钉死成同一张、轮播彻底失效 ——
            // 而「右滑轮播图片接管」只需要写 feedcache，不需要动壁纸 uri。
        } catch (Throwable t) {
            log(Log.WARN, TAG, "local photo install failed: " + t);
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    /**
     * 画报「生成下一张壁纸信息」的出口：在这里把远程图地址换成本地照片，
     * 并对残留的推广壁纸直接返回 null（画报据此跳过本次换图，不上屏）。
     */
    private static boolean hookGenerateInfo(ClassLoader cl, Class<?> infoCls,
            final java.lang.reflect.Field uriF, final java.lang.reflect.Method isAdM) {
        try {
            Class<?> c = Class.forName(CLS_APPLY_WP, false, cl);
            java.lang.reflect.Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                final java.lang.reflect.Method m = ms[i];
                if (!m.getName().equals("generateWallpaperInfo")
                        || m.getParameterTypes().length != 0
                        || m.getReturnType() != infoCls) {
                    continue;
                }
                m.setAccessible(true);
                sMod.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        Object info = chain.proceed();
                        if (info == null) {
                            return null;
                        }
                        try {
                            // 【重要】不再用本地图替换 wallpaperUri。
                            // sLocalPhoto 现在是「右滑轮播图片接管」，只该影响 feedcache；
                            // 而 wallpaperUri 是锁屏/桌面壁纸地址，替换掉就会把锁屏
                            // 固定成同一张本地图、不再轮播（旧版就是这么坏的）。
                            if (sBlockAd && isAdM != null) {
                                Object ad = isAdM.invoke(info);
                                if (Boolean.TRUE.equals(ad)) {
                                    sAdHits.incrementAndGet();
                                    sAdLastAt = SystemClock.uptimeMillis();
                                    verbose("ad wallpaper blocked");
                                    return null;
                                }
                            }
                        } catch (Throwable t) {
                            verbose("photo/ad info hook failed: " + t);
                        }
                        return info;
                    }
                });
                verbose("hooked photo/ad " + CLS_APPLY_WP + "#generateWallpaperInfo");
                return true;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "generateWallpaperInfo hook miss: " + t);
        }
        return false;
    }

    /** 该 uri 是否已经是本模块塞进去的本地照片（避免同一次换图被重复替换） */
    private static boolean isLocalPick(Object uri) {
        String dir = sPhotoDir;
        return uri instanceof String && dir != null && dir.length() > 0
                && ((String) uri).startsWith(dir);
    }

    /**
     * 在「真正设壁纸」的方法里把 WallpaperInfo.wallpaperUri 换成随机本地照片。
     * 不改参数（参数是对象引用，直接改字段），因此不受 Chain 参数可写性影响。
     */
    private static boolean hookReplaceUri(ClassLoader cl, String cls, String method,
            final Class<?> infoCls, final java.lang.reflect.Field uriF) {
        try {
            Class<?> c = Class.forName(cls, false, cl);
            java.lang.reflect.Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                final java.lang.reflect.Method m = ms[i];
                if (!m.getName().equals(method)) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                int idx = -1;
                for (int k = 0; k < ps.length; k++) {
                    if (ps[k] == infoCls) {
                        idx = k;
                        break;
                    }
                }
                if (idx < 0) {
                    continue;
                }
                final int fi = idx;
                m.setAccessible(true);
                sMod.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        if (sLocalPhoto) {
                            try {
                                Object info = argAt(chain, fi);
                                if (info != null && !isLocalPick(uriF.get(info))) {
                                    String pick = pickLocalPhoto();
                                    if (pick != null) {
                                        uriF.set(info, pick);
                                        verbose("photo replace(fallback) -> " + pick);
                                    }
                                }
                            } catch (Throwable t) {
                                verbose("photo replace failed: " + t);
                            }
                        }
                        return chain.proceed();
                    }
                });
                verbose("hooked photo " + cls + "#" + method + " arg" + idx);
                return true;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "photo hook miss " + cls + "#" + method + ": " + t);
        }
        return false;
    }

    /** 取第 idx 个参数（兼容 getArgs() 返回 List 或数组两种实现） */
    private static Object argAt(Chain chain, int idx) {
        try {
            Object r = chain.getArgs();
            if (r instanceof java.util.List) {
                java.util.List<?> l = (java.util.List<?>) r;
                return idx < l.size() ? l.get(idx) : null;
            }
            if (r instanceof Object[]) {
                Object[] a = (Object[]) r;
                return idx < a.length ? a[idx] : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 从同步目录随机取一张可读图片；没有可用图片时返回 null（调用方退回原图） */
    static String pickLocalPhoto() {
        String dir = sPhotoDir;
        if (dir == null || dir.length() == 0) {
            return null;
        }
        try {
            File d = new File(dir);
            String[] names = d.list();
            if (names == null || names.length == 0) {
                return null;
            }
            java.util.ArrayList<String> pics = new java.util.ArrayList<String>();
            for (int i = 0; i < names.length; i++) {
                String n = names[i];
                if (n == null || n.startsWith(".") || !isSupported(n)) {
                    continue;
                }
                File f = new File(d, n);
                if (f.isFile() && f.length() > 0 && f.canRead()) {
                    pics.add(f.getAbsolutePath());
                }
            }
            if (pics.isEmpty()) {
                return null;
            }
            return pics.get(sRandom.nextInt(pics.size()));
        } catch (Throwable t) {
            verbose("pickLocalPhoto failed: " + t);
            return null;
        }
    }

    /**
     * @param before true=在原方法执行前同步（读库前必须先写库，如 load()/init()）
     */
    private static boolean tryHook(ClassLoader cl, String className, String method,
                                   Class<?>[] params, final boolean before, final String trigger) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            Method m = c.getDeclaredMethod(method, params);
            m.setAccessible(true);
            sMod.hook(m).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    if (before) {
                        runSync(trigger);
                        return chain.proceed();
                    }
                    Object r = chain.proceed();
                    runSync(trigger);
                    return r;
                }
            });
            verbose("hooked " + className + "#" + method);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook miss " + className + "#" + method + ": " + t);
            return false;
        }
    }

    /** 供设置页/自检使用：立即同步一次（可跨进程用 sendBroadcast 触发，这里保留 API） */
    public static String syncNow() {
        sync(appCtx(), "manual");
        return sLastResult;
    }

    // =====================================================================
    // 同步主流程（等价 CwDirSync）
    // =====================================================================

    /**
     * 触发一轮同步。
     *
     * <p>必须异步。这个方法是从画报的 {@code Fragment.onCreate} /
     * {@code Presenter.load} / {@code ContentProvider.init} 里调过来的，全都在
     * 主线程上。用户点「清除画报缓存」之后私有缩略图全被删掉，一轮同步要重新
     * 扫几百个文件、补几百份副本、再删旧行插新行，在主线程跑就是几百毫秒到几秒，
     * 正好压在锁屏读库的同一根线程上 —— 用户看到的就是「清缓存后锁屏／壁纸
     * 轮播异常、卡住、只剩黑屏」。
     *
     * <p>改成后台线程执行，hook 立刻返回；重复触发用 {@link #sSyncQueued}
     * 合并成一次，不会堆线程。
     */
    private static void runSync(final String trigger) {
        synchronized (sSyncLock) {
            if (sSyncQueued) {
                return;
            }
            sSyncQueued = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sync(appCtx(), trigger);
                } catch (Throwable e) {
                    log(Log.ERROR, TAG, "sync error(" + trigger + ")", e);
                } finally {
                    synchronized (sSyncLock) {
                        sSyncQueued = false;
                    }
                }
            }
        }, "MiShare-FgSync");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable e) {
            synchronized (sSyncLock) {
                sSyncQueued = false;
            }
            log(Log.WARN, TAG, "sync thread start failed: " + e);
        }
    }

    static void sync(Context ctx, String trigger) {
        long now = SystemClock.uptimeMillis();
        if (sRunning) {
            verbose("sync(" + trigger + ") skipped: reentrant");
            return;
        }
        if (now - sLastTriggerAt < DEBOUNCE_MS) {
            verbose("sync(" + trigger + ") skipped: debounce");
            return;
        }
        sLastTriggerAt = now;
        // 每轮重新发放「懒复制私有副本」的时间预算
        sLazyCopySpent = 0;
        sLazyCopyCount = 0;
        sDbSkipNoThumb = 0;
        if (ctx == null) {
            ctx = appCtx();
        }
        if (ctx == null) {
            sLastResult = "no-context";
            writeStatus();
            return;
        }
        sCtx = ctx;
        ensureCfgReceiver(ctx);
        if (!sProbed) {
            sProbed = true;
            probeSharedPaths();
        }
        StatusReqListener.register(ctx, Prefs.FG_PKG, new Runnable() {
            @Override
            public void run() {
                writeStatus();
            }
        });
        sRunning = true;
        int added = 0;
        int pruned = 0;
        int skipped = 0;
        String result = "running";
        try {
            Cfg cfg = refreshCfg(ctx);
            sLastDir = cfg.dir;
            // 补做画报没在跑时丢失的「重新随机 → 清进度」
            checkResetSeqDurable(ctx, cfg, trigger);
            if (!cfg.enabled) {
                result = "disabled";
            } else {
                // 【右滑轮播图片接管】完全不碰 lks_wp，也不改画报的壁纸模式。
                // 旧实现在这里无条件调 ensureCustomMode()，会把画报切成「自选壁纸」，
                // 而该模式下画报只认自选的那一张图 —— 表现就是「锁屏固定一张、不再轮播」。
                // 【自愈】清缓存后先别动壁纸模式。此时私有副本全被删、库里一堆
                // 指向已删文件的死行，如果先把画报切成「自选壁纸」，它会停在
                // 「custom 模式 + 图片全失效」的状态上，锁屏直接不轮播／黑屏。
                // 先把数据修好（下面的 ①②③），下一轮再切模式。
                // 私有副本最多只建 cap 份，用 cand 当期望值会永远判成 wiped
                // 注意：这个判定只对「自选壁纸注入」路线有意义。右滑接管路线根本不
                // 生成私有副本，privLooksWiped 会恒返回 true —— 旧代码把它同时用在
                // 两条路线上，导致 `localPhoto && !wipedNow` 永远为 false，
                // 画报因此永远切不回原生锁屏轮播（锁屏固定一张 / 灰块）。
                // 【v2.16】叠加模式下两条路线都生成私有副本，wiped 判定与开关无关
                boolean wipedNow = privLooksWiped(Math.min(sLastCand, sLastCap));
                if (wipedNow) {
                    log(Log.INFO, TAG, "priv cache wiped(last cand=" + sLastCand
                            + "), defer wallpaper mode switch");
                }
                if (!wipedNow) {
                    // 先保证画报处于自选壁纸模式，否则下面写进 lks_wp 的图不参与轮播
                    ensureCustomMode(ctx);
                    // 池里残留的网络推荐图必须剔掉，否则会混进来
                    //（表现就是「开了本地照片还在放网络图」）
                    purgeNetworkPics(ctx);
                    // 画报会反复把预置图/网络图塞回池子，需要常驻清理
                    ensurePurgeLoop();
                }
                Uri uri = wallpaperUri(cl());
                if (uri == null) {
                    result = "no-uri";
                } else {
                    // 接管右滑轮播：把历史写进 lks_wp 的行撤掉，并让画报切回原生锁屏轮播。
                    // 不做这一步，之前注入的本地图会继续占着锁屏池，表现就是「锁屏固定一张」。
                    // 接管右滑轮播：只把画报从 custom_wallpaper 切回原生锁屏轮播。
                    // 【不再删 lks_wp 的 l_pic】—— 那是右滑的数据源，删了右滑就没图；
                    // 下面 ①②③ 会按「已就绪的缩略图」把它重建，指向不存在缩略图的
                    // 残行（画报解码失败 → 灰块）由 pruneManaged 清掉。
                    // 接管右滑轮播：模式与 lks_wp 的清理统一交给 revokeLksThrottled
                    //（放在 ③ 之后执行，避免与 ①②③④ 抢先/重复），
                    // 这里不再单独调 restoreLksMode。
                    // 候选 = 目录扫描 + MediaStore 兜底，按路径去重
                    LinkedHashMap<String, File> cand = new LinkedHashMap<String, File>();
                    List<File> tmp = new ArrayList<File>();
                    collectDir(cfg, tmp, "dir");
                    mergeCand(cand, tmp);
                    tmp.clear();
                    collectMediaStore(ctx, cfg, tmp, "ms");
                    mergeCand(cand, tmp);
                    sLastCand = cand.size();
                    // 每次同步都刷新权限自检：旧实现只在 list()== 时更新，
                    // 正常路径下 status 里的 perm 永远是初始值 "-"，设置页拿不到真实状态
                    sPermInfo = permLine(new File(cfg.dir));
                    // 池子类型分布：开了本地照片却还混着预置图/网络图时，设置页要能看出来
                    sLastPool = poolStat();
                    sLastNeed = diagNeed(cand.size());

                    // 【提速】内容指纹没变就直接收工。
                    // 图片集合 / 配置 / 顺序都没动时，下面三次 ContentResolver 往返
                    // （snapshotManaged、pruneManaged、enforceOrder）和 managed 落盘
                    // 全是空转——而本方法是 hook 在主线程上同步执行的，这段空转
                    // 就是「打开自选图片页卡一下」的来源。
                    String fp = fpOf(cfg, cand);
                    // 【自愈】私有副本被清空时指纹是看不出来的（图片集合／配置都没变），
                    // 必须绕过节流，否则死行不清、副本不补，锁屏／壁纸轮播一直坏着。
                    // 期望值要夹到 cap：私有副本上限就是 cap 份，用 cand 会永远判 wiped
                    boolean privWiped = privLooksWiped(Math.min(cand.size(), cfg.cap));
                    sLastPrivWiped = privWiped;
                    // 【v2.16】右滑轮播池(feedcache/left_lks)被清空时指纹同样看不出来：
                    // 图片集合/配置都没变 → fp 相同 → 早退 → syncLeftLks 永远不执行 →
                    // 右滑/锁屏轮播永久空池。池空必须强制跑一轮全量。
                    boolean leftPoolEmpty = cfg.localPhoto && leftLksCount(ctx) <= 0;
                    if (leftPoolEmpty) {
                        log(Log.INFO, TAG, "feedcache left_lks empty, forcing full sync");
                    }
                    if (sOrderNorm == 0) {
                        // 每个进程只探一次：探到重复就一直保持 2，直到重排把顺序修好
                        Uri nu = wallpaperUri(cl());
                        sOrderNorm = (nu != null && hasDuplicateCacheO(ctx, nu, null)) ? 2 : 1;
                        if (sOrderNorm == 2) {
                            log(Log.INFO, TAG, "cache_o duplicated (legacy rows),"
                                    + " force one-shot normalize");
                        }
                    }
                    if (!privWiped && !leftPoolEmpty && fp.equals(sFp)
                            && now - sFpAt < FP_TTL_MS && sOrderNorm != 2) {
                        result = "unchanged";
                        sLastOrder = cfg.order;
                        // 早退也补写一次状态（节流），否则状态文件会永远停在上一轮
                        // 完整同步的时间上，用户看起来就像「模块没在跑 / 功能没生效」。
                        if (now - sStatusAt >= STATUS_MIN_MS) {
                            writeStatus();
                        }
                        verbose("skip: fp unchanged (last full sync "
                                + ((now - sFpAt) / 1000) + "s ago, cost="
                                + (SystemClock.uptimeMillis() - now) + "ms)");
                        return;
                    }
                    if (privWiped) {
                        log(Log.INFO, TAG, "priv cache wiped (cand=" + cand.size()
                                + "), forcing full sync");
                    }

                    // 新 -> 旧排序，取最新 cap 张当「保留集」
                    // （旧实现只限制「本次新增」的数量，库里已有的一大堆永远不删，
                    //   所以把上限从 100 改成 10 看上去毫无效果）
                    List<File> all = new ArrayList<File>(cand.values());
                    Collections.sort(all, BY_MTIME_DESC);
                    // 固定「原图 -> 实际入库文件」映射：大图用后台生成的缩略图。
                    // 一次性算好，避免缩略图在 keep / want 两次判定之间刚好生成
                    // 造成两处结论不一致（会误 prune 或漏插）。
                    Map<String, File> pickMap = new LinkedHashMap<String, File>();
                    for (int i = 0; i < all.size(); i++) {
                        File src = all.get(i);
                        // 注意：这里必须「乐观」用 pickFile，不能用严格版。
                        // keep 是 prune 的保留集，如果因为缩略图还没生成就让 keep
                        // 变小，pruneManaged 会把库里已有的行整片删掉，右滑直接变空。
                        // 「严格」只用在真正写库的地方（insertOne / enforceOrder）。
                        pickMap.put(src.getAbsolutePath(), pickFile(src));
                    }
                    LinkedHashSet<String> keep = new LinkedHashSet<String>();
                    for (int i = 0; i < all.size() && keep.size() < cfg.cap; i++) {
                        keep.add(pickMap.get(all.get(i).getAbsolutePath()).getAbsolutePath());
                    }

                    // ① 先删：本模块管过、但已不在保留集里的（超上限 / 文件已删 / 换了目录）
                    //    接管右滑轮播时不动 lks_wp，这一步整体跳过
                    // 【互斥路线】整个 ①②③④ 段只有「自选壁纸注入」路线才允许执行：
                    //   localPhoto=false → 写 lks_wp(bizids=cw) + 画报切 custom_wallpaper
                    //   localPhoto=true  → 写 feedcache/left_lks + 画报切 auto_recommand_paper
                    // 两条路线同时生效就会互相破坏（图片混池、模式与数据不匹配），
                    // 旧实现无条件执行 ①②③④ 正是画报功能「一团乱麻」的核心缺陷。
                    // 【v2.16】自选图片页(lks_wp/bizids=cw) 与右滑轮播(feedcache/left_lks)
                    // 是两张不同的表，改为叠加维护：开启右滑接管后自选页不再被清空
                    boolean writeLks = uri != null;
                    Set<String> removed = new HashSet<String>();

                    int[] pr = new int[2];
                    // 【自愈】私有副本被清空的那一轮先别 prune：库里那些行确实指向
                    // 已被删掉的文件，但副本还没补齐，先删就等于把锁屏池清空 ——
                    // 用户看到的是「清缓存后壁纸轮播直接没了」。等下一轮副本补齐
                    // 再清；中间这段时间宁可留着旧行（最多灰块）也不要空池。
                    if (!writeLks) {
                        // 接管右滑轮播：lks_wp 是画报原生的「锁屏推荐池」，与模块的
                        // 右滑接管路线互斥。旧实现在这条路上照样 prune / insert /
                        // dedupe，把本地图塞进推荐池，于是「锁屏固定一张、灰块、
                        // 不轮播、开关关了还生效」同时出现 —— 这就是一团乱麻的根因。
                        verbose("skip lks_wp prune (localPhoto=" + cfg.localPhoto
                                + ", uri=" + uri + ")");
                    } else if (privWiped) {
                        log(Log.INFO, TAG, "priv cache wiped, skip prune (pool kept)");
                    } else {
                        // 无条件执行：keep 是「乐观」路径集（缩略图还没生成时退回原图路径），
                        // 所以不会把刚入库的行误删；而库里指向「已不存在的缩略图」的残行
                        // 会在这里被清掉 —— 那正是灰块的来源。
                        pruneManaged(ctx, uri, keep, pr, removed);
                        pruned = pr[0];
                        skipped = pr[1];
                    }

                    // ② 目标顺序：顺序=最新在前（画报默认行为）/ 倒序=最旧在前 / 随机=按种子洗牌
                    List<File> want = new ArrayList<File>();
                    for (int i = 0; i < all.size(); i++) {
                        File src = all.get(i);
                        File use = pickMap.get(src.getAbsolutePath());
                        if (use != null && keep.contains(use.getAbsolutePath())) {
                            want.add(src);
                        }
                    }
                    applyOrderMode(want, cfg);
                    sLastOrder = cfg.order;

                    // ③ 再增：保留集里 provider 还没有的（按目标顺序插入）。
                    //     先用「一条查询」把库里已有的行读成集合：
                    //     旧实现是每张图一次 existsInDb，cap=100 时就是 100 次 ContentResolver 往返，
                    //     这也是同步耗时里占比最大的一块。
                    int reordered = 0;
                    if (writeLks) {
                    // 无条件执行：insertOne / enforceOrder 内部都用 pickForDb（严格），
                    // 缩略图没就绪的图这一轮直接跳过，绝不会再写入指向原图的灰块行。
                    {
                        Set<String> present = snapshotManaged(ctx, uri);
                        Set<String> addedPaths = new HashSet<String>();
                        for (int i = 0; i < want.size(); i++) {
                            File f = want.get(i);
                            if (insertOne(ctx, uri, f, cfg, present)) {
                                added++;
                                addedPaths.add(f.getAbsolutePath());
                            }
                        }
                        // ④ 库里先后与目标不一致时重排（换了顺序设置、或目录增删过文件）
                        reordered = enforceOrder(ctx, uri, keep, want, cfg, present);
                        updateManaged(ctx, addedPaths, removed, cfg.dir);
                    }
                    // ④b 「自选图片」页灰缩略图：.thumb 是隐藏目录，画报没有
                    //     MANAGE_EXTERNAL_STORAGE，受 scoped storage / FUSE 过滤读不到，
                    //     Glide 解码失败只画一层灰蒙版。③ 已经把私有副本行插进去了，
                    //     但 keep 用的是「乐观」pickFile（.thumb 路径），pruneManaged 不会删
                    //     旧行 —— 结果同一张图留下两行：一行 .thumb（灰）、一行 priv（好）。
                    //     这里按 _id 精确清掉私有副本已就绪的 .thumb 行。
                    try {
                        dedupeThumbRows(ctx, uri);
                    } catch (Throwable t) {
                        verbose("dedupeThumbRows failed: " + t);
                    }
                    }
                    // 【v2.16】不再调 revokeLksThrottled：它会把本模块写进 lks_wp 的
                    // 行整批删掉/把画报切回原生模式，正是「自选页图片全消失 + 锁屏
                    // 不再用本地图轮播」的直接原因。
                    // ⑤ 右滑轮播池（feedcache / left_lks）。画报右滑完全不读 lks_wp，
                    //    只同步上面那张表的话右滑永远还是网络图
                    if (cfg.localPhoto) {
                        try {
                            syncLeftLks(ctx, want);
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "syncLeftLks failed: " + t);
                        }
                        // 迁移标记复位：enforceOrder 已经把 cache_o 重写成严格递增，
                        // 不清掉这个标记会导致每轮同步都强制全量重排（每次重插都清 show_t）
                        if (sOrderNorm == 2) {
                            sOrderNorm = 1;
                        }
                        markLeftLksOn(ctx);
                    } else {
                        // 开关关闭：把之前塞进右滑轮播池的行清掉。这些行的 url_l 和
                        // 画报原生行格式完全一致（都是 files/img_cache/left_lks_wp/<id>），
                        // 没法按路径挑出来，所以整批删 req_tag='left_lks'，让画报下次
                        // 右滑重新向服务端拉网络图。不清的话本地图会永久留在库里，
                        // 开关关了照样是本地图 —— 这就是「关不掉」的根因。
                        try {
                            purgeLeftLksOnce(ctx);
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "purgeLeftLks failed: " + t);
                        }
                    }
                    // ⑤b 精简精选壁纸：清掉白名单之外的精选壁纸条目
                    if (cfg.curated != null && cfg.curated.length() > 0) {
                        try {
                            purgeCurated(ctx, cfg);
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "purgeCurated failed: " + t);
                        }
                    }
                    // ⑤c 分类清单：设置页做「精简精选壁纸」多选用，读到一次就缓存
                    if (sCats == null) {
                        try {
                            sCats = readCategories(ctx);
                        } catch (Throwable t) {
                            verbose("readCategories failed: " + t);
                        }
                    }
                    // ⑥ 大图缩略图交给后台线程，不阻塞 hook 所在的主线程
                    kickThumbWorker(ctx, want);
                    verbose("plan cand=" + cand.size() + " keep=" + keep.size()
                            + " cap=" + cfg.cap + " add=" + added + " prune=" + pruned
                            + " order=" + cfg.order + " reorder=" + reordered
                            + " lks=" + sLastLeftLks);
                    // 全量跑完才记指纹：万一中途抛异常，下次仍然会重新全量，不会漏干活。
                    // 「入库副本还没生成」的那几张不算完整同步：本轮不记指纹，并安排一次
                    // 延迟补跑。否则下一轮 fp 相同会被判成 unchanged 早退，缺的那几张永远
                    // 补不上 —— 这就是「设置 cap=10，实际只加进去 8 张」的直接原因。
                    if (sDbSkipNoThumb > 0) {
                        result = "partial";
                        scheduleLazyFollowup(ctx);
                    } else {
                        result = "ok";
                        sLazyFollowup = 0;
                        sFp = fp;
                        sFpAt = SystemClock.uptimeMillis();
                    }
                }
            }
        } catch (Throwable t) {
            result = "error:" + t;
            log(Log.ERROR, TAG, "sync(" + trigger + ") failed", t);
        } finally {
            sRunning = false;
            sLastCostMs = SystemClock.uptimeMillis() - now;
            sLastResult = result;
            sLastAdded = added;
            sLastPruned = pruned;
            sLastSkipped = skipped;
        }
        log(Log.INFO, TAG, "sync(" + trigger + ") dir=" + sLastDir + " cap=" + sLastCap
                + " cfg=" + sCfgSrc + " order=" + sLastOrder
                + " cand=" + sLastCand + " add=" + added
                + " prune=" + pruned + " skip=" + skipped + " -> " + result
                + " (" + sLastCostMs + "ms)");
        // 入口很频繁，状态只在有变化或异常时落盘，避免无谓 IO
        if (added > 0 || pruned > 0 || !"ok".equals(result) || sLastCostMs > 400) {
            writeStatus();
        }
        // 同步途中收到配置推送：这次跑的还是旧配置，收尾后按新配置补跑一次
        if (sResyncPending) {
            sResyncPending = false;
            sLastTriggerAt = 0;
            sync(sCtx, "cfg-resync");
        }
    }

    /** 清孤儿行 + 删除「文件已不存在」的行（手册语义）；out[0]=删除数 out[1]=保留数 */
    private static final Comparator<File> BY_MTIME_DESC = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            long ma = a.lastModified();
            long mb = b.lastModified();
            if (ma == mb) {
                // 全路径兜底，保证排序是全序（否则相同 mtime 的先后会随扫描顺序漂移）
                return a.getAbsolutePath().compareTo(b.getAbsolutePath());
            }
            return mb > ma ? 1 : -1;
        }
    };

    private static void mergeCand(LinkedHashMap<String, File> cand, List<File> src) {
        for (int i = 0; i < src.size(); i++) {
            File f = src.get(i);
            String p = f.getAbsolutePath();
            if (!cand.containsKey(p)) {
                cand.put(p, f);
            }
        }
    }

    /**
     * 清理：
     * ①空 url_l 的脏记录；
     * ②本模块插入过、但已不在保留集里的（超上限 / 文件已删 / 换了目录）。
     * 用户自己手动添加的自选图片（不在接管记录里、也不落在同步目录下）一律不动。
     */
    private static void pruneManaged(Context ctx, Uri uri, Set<String> keep, int[] out,
                                     Set<String> removedOut) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            int n = cr.delete(uri,
                    "bizids like '%cw%' and (url_l is null or if(RTRIM(url_l), '')='')", null);
            if (n > 0) {
                verbose("prune empty=" + n);
            }
            out[0] += Math.max(0, n);
        } catch (Throwable t) {
            verbose("prune empty failed: " + t);
        }
        Set<String> managed = readManaged(ctx, "p=");
        Set<String> dirs = readManaged(ctx, "d=");
        // 旧版本只会从默认目录同步，历史遗留的行也算我们管的
        dirs.add(DEF_DIR);
        dirs.add(sLastDir);
        List<String> dead = new ArrayList<String>();
        /** 与 dead 一一对应的行主键：删行按 _id 走，见下面删除段的注释 */
        List<Long> deadIds = new ArrayList<Long>();
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{"_id", "url_l"}, "bizids like '%cw%'", null, null);
            if (c == null) {
                return;
            }
            while (c.moveToNext()) {
                long rowId = c.getLong(0);
                String p = c.getString(1);
                if (p == null || p.length() == 0) {
                    continue;
                }
                if (keep.contains(p)) {
                    out[1]++;
                    continue;
                }
                // 画报自己的私有缓存（files/img_cache/…）：网络推荐图、广告素材都缓存在
                // 这里，和「用户手动添加的照片」（/storage/… 原路径）可以明确区分。
                // 本地照片模式下这些行必须清掉，否则「自选图片」页会一直混着画报拉的
                // 网图 —— 用户表现就是「上限设成 10，页面还是一百多张」。
                // 右滑轮播的数据在另一个库（data_cache.db/feedcache），清这里不影响右滑。
                boolean ours = managed.contains(p) || underAny(p, dirs)
                        || isPrivCachePath(p) || !new File(p).exists();
                if (!ours) {
                    // 用户自己加的、且不在同步目录里 —— 保留
                    out[1]++;
                    continue;
                }
                dead.add(p);
                deadIds.add(Long.valueOf(rowId));
            }
        } catch (Throwable t) {
            verbose("prune query failed: " + t);
        } finally {
            close(c);
        }
        // 逐行按主键删（_id = ?）。
        // 这里踩过两次坑：① url_l in (?,?,...) 批量提交，画报 Provider 对这种 selection
        // 静默返回 0；② 用 "url_l = ? and bizids like '%cw%'" 这种组合条件，Provider 同样
        // 可能返回 0 —— 于是死行永远留在池子里，「上限 10 张、页面一百多张」就一直好不了。
        // _id 是主键，Provider 走标准 SQLiteDatabase.delete，最可靠；主键删失败再退化按 url_l 删。
        for (int i = 0; i < dead.size(); i++) {
            String p = dead.get(i);
            long id = deadIds.get(i).longValue();
            try {
                int d = cr.delete(uri, "_id = ?", new String[]{String.valueOf(id)});
                if (d <= 0) {
                    d = cr.delete(uri, "url_l = ? and bizids like '%cw%'", new String[]{p});
                }
                if (d > 0) {
                    out[0] += d;
                    if (removedOut != null) {
                        removedOut.add(p);
                    }
                    verbose("prune " + p + " -> " + d);
                }
            } catch (Throwable t) {
                verbose("prune delete failed: " + t);
            }
        }
    }

    private static boolean underAny(String path, Set<String> dirs) {
        for (String d : dirs) {
            if (d != null && d.length() > 0 && path.startsWith(d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清掉 {@code lks_wp} 里 url_l 仍指向 {@code .thumb} 的行。
     *
     * <p>「自选图片」页显示的正是这张表（{@code bizids='cw'} 的 {@code l_pic}）。
     * {@code .thumb} 是隐藏目录，不会进 MediaStore，而画报只有 READ_MEDIA_IMAGES、
     * 没有 MANAGE_EXTERNAL_STORAGE，直接按路径 open 会被 FUSE 拦住 —— Glide 解码
     * 失败后只画一层灰蒙版，就是自选图片页的灰缩略图。
     *
     * <p>私有副本行由 {@link #insertOne} 通过 {@link #pickForDb} 写入，路径形如
     * {@code /data/user/0/com.mfashiongallery.emag/files/img_cache/left_lks_wp/<stem>}。
     * 但 {@link #pruneManaged} 的保留集 keep 用的是「乐观」的 {@link #pickFile}（.thumb 路径），
     * 两套路径对不上，旧行就留了下来，同一张图变成「一行灰 + 一行好」。
     *
     * <p>只删「私有副本已经存在且非空」的行；副本还没生成的保留原样，
     * 免得把唯一一行删掉之后这张图直接消失。
     */
    private static void dedupeThumbRows(Context ctx, Uri uri) {
        if (ctx == null || uri == null) {
            sLastDedupe = "skip";
            return;
        }
        int scan = 0;
        int miss = 0;
        int del = 0;
        Cursor c = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            c = cr.query(uri, new String[]{"_id", "url_l"},
                    "type='l_pic' AND url_l LIKE '%/.thumb/%'", null, null);
            if (c == null) {
                sLastDedupe = "noquery";
                return;
            }
            List<Long> ids = new ArrayList<Long>();
            while (c.moveToNext()) {
                scan++;
                long id = c.getLong(0);
                String u = c.getString(1);
                if (u == null || u.length() == 0) {
                    miss++;
                    continue;
                }
                File pv = privThumbOf(new File(u));
                if (pv != null && pv.isFile() && pv.length() > 0) {
                    ids.add(id);
                } else {
                    miss++;
                }
            }
            if (ids.isEmpty()) {
                sLastDedupe = scan + "/" + miss + "/0";
                return;
            }
            // 逐行按主键删：Provider 对 "_id IN (…)" 这种 selection 会静默返回 0，
            // 旧的批量写法等于什么都没删（脏 .thumb 行一直留着，表现是右滑灰块）。
            for (int i = 0; i < ids.size(); i++) {
                try {
                    del += cr.delete(uri, "_id = ?",
                            new String[]{String.valueOf(ids.get(i).longValue())});
                } catch (Throwable t) {
                    verbose("dedupe delete failed: " + t);
                }
            }
            if (del > 0) {
                log(Log.INFO, TAG, "dedupe thumb rows=" + del);
            }
        } catch (Throwable t) {
            sLastDedupe = scan + "/" + miss + "/0/err:" + t.getClass().getSimpleName();
            verbose("dedupe thumb rows failed: " + t);
            return;
        } finally {
            close(c);
        }
        sLastDedupe = scan + "/" + miss + "/" + del;
    }

    // ---------------- 配置推送接收 / 拉取 ----------------

    /** 在画报进程里注册一次配置推送接收器（画报可能有多个进程，各自注册） */
    private static void ensureCfgReceiver(final Context ctx) {
        if (sCfgRxReady || ctx == null) {
            return;
        }
        sCfgRxReady = true;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !CfgBridge.ACTION_PUSH.equals(intent.getAction())) {
                        return;
                    }
                    applyPush(context, intent);
                }
            };
            IntentFilter f = new IntentFilter(CfgBridge.ACTION_PUSH);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            verbose("cfg receiver ready");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cfg receiver register failed: " + t);
        }
    }

    /** 收到「配置变了」信号：失效缓存 -> 重新从 provider 读 -> 立刻同步一次（不用重启画报） */
    private static void applyPush(Context ctx, Intent intent) {
        try {
            invalidateCfg();
            // 同时打脏 Prefs 层的跨进程配置缓存：否则一次同步里的其它读取点仍会命中旧全文
            Prefs.invalidateCfgCache();
            sCtx = ctx;
            Cfg c = cfg(ctx);
            // 用户主动改「加载顺序」/ 点「重新随机」→ 清掉右滑轮播进度，让新一轮从第一张播起
            long rs = c.resetSeq;
            if (!sResetSeqInited) {
                // 进程刚起来：只记录基线，绝不清库（否则每次重启画报都会从头播）
                sResetSeqInited = true;
                sLastResetSeq = rs;
            } else if (rs != sLastResetSeq) {
                sLastResetSeq = rs;
                // 先落盘再清：万一清库过程中进程被杀，下次启动不会被判成「还没处理」
                writeResetSeqMark(ctx, rs);
                resetLeftLksProgress(ctx, "reset-seq=" + rs);
            }
            log(Log.INFO, TAG, "cfg changed -> dir=" + c.dir + " cap=" + c.cap
                    + " enabled=" + c.enabled + " order=" + c.order
                    + " localPhoto=" + c.localPhoto + " curated=" + c.curated
                    + " blockAd=" + c.blockAd + " debug=" + c.debug);
            writeStatus();
            // 配置刚变，绕过去抖立刻按新配置同步一次（否则可能在 800ms 内被当成重复触发丢掉）
            if (sRunning) {
                sResyncPending = true;
            } else {
                sLastTriggerAt = 0;
                sync(ctx, "cfg-push");
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cfg push apply failed: " + t);
        }
    }

    /**
     * 把右滑轮播（req_tag=left_lks）的图片全部重新标记为「未展示」。
     *
     * 用户改「加载顺序」或点「重新随机」时必须清一次：画报 loadMiFGFeeds2 用
     * {@code req_tag=? and show_t=0} 取下一张，show_t 留着就代表「这张已经播过」，
     * 整批跳过 → 表面上像「换了顺序却没生效」。
     */
    private static void resetLeftLksProgress(Context ctx, String why) {
        try {
            Uri uri = sFeedUri != null
                    ? sFeedUri
                    : Uri.parse("content://" + DC_AUTH + "/feedcache");
            ContentValues cv = new ContentValues();
            cv.put("show_t", 0L);
            int n = ctx.getContentResolver()
                    .update(uri, cv, "req_tag=?", new String[]{REQ_LEFT_LKS});
            log(Log.INFO, TAG, "left_lks progress reset (" + why + ") rows=" + n);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "left_lks progress reset failed: " + t);
        }
    }

    /** 读「已处理过的 resetSeq」；无记录返回 -1（首次运行，只记基线，绝不清库） */
    private static long readResetSeqMark(Context ctx) {
        try {
            File d = dataDir(ctx);
            if (d == null) {
                return -1L;
            }
            File f = new File(d, RESETSEQ_FILE);
            if (!f.isFile()) {
                return -1L;
            }
            String s = readText(f);
            if (s == null) {
                return -1L;
            }
            s = s.trim();
            return s.length() == 0 ? -1L : Long.parseLong(s);
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static void writeResetSeqMark(Context ctx, long v) {
        FileOutputStream fos = null;
        try {
            File d = dataDir(ctx);
            if (d == null) {
                return;
            }
            fos = new FileOutputStream(new File(d, RESETSEQ_FILE), false);
            fos.write(Long.toString(v).getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable t) {
            log(Log.WARN, TAG, "write resetSeq mark failed: " + t);
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 冷启动补做「丢失的清进度」：比较落盘值与本轮 resetSeq，变大了说明
     * 用户在画报没跑时点过「重新随机/换顺序」，这里补清一次 show_t。
     */
    private static void checkResetSeqDurable(Context ctx, Cfg cfg, String trigger) {
        long rs = cfg.resetSeq;
        long seen = readResetSeqMark(ctx);
        if (seen < 0L) {
            writeResetSeqMark(ctx, rs);
            return;
        }
        if (rs > seen) {
            writeResetSeqMark(ctx, rs);
            resetLeftLksProgress(ctx, "reset-seq=" + rs + " durable@" + trigger);
        } else if (rs < seen) {
            // 用户清了画报数据或换回旧配置：只对齐基线，不去动进度
            writeResetSeqMark(ctx, rs);
        }
    }

    /** 扫描目录：只收图片扩展名 */
    private static void collectDir(Cfg cfg, List<File> out, String tag) {
        try {
            File dir = new File(cfg.dir);
            if (!dir.isDirectory()) {
                verbose(tag + " dir missing: " + cfg.dir);
                return;
            }
            if (!dir.canRead()) {
                verbose(tag + " dir unreadable: " + cfg.dir);
            }
            String[] names = dir.list();
            if (names == null) {
                log(Log.WARN, TAG, tag + " list()=, 存储权限可能不足: " + sPermInfo);
                sPermInfo = permLine(dir);
                return;
            }
            int n = 0;
            for (int i = 0; i < names.length; i++) {
                String name = names[i];
                if (name == null || name.startsWith(".")) {
                    continue;
                }
                File f = new File(cfg.dir, name);
                if (!f.isFile() || !isSupported(name)) {
                    continue;
                }
                out.add(f);
                n++;
            }
            if ("dir".equals(tag)) {
                sLastListed = names.length;
            }
            verbose(tag + " listed=" + names.length + " pics=" + n);
        } catch (Throwable t) {
            verbose(tag + " collect failed: " + t);
        }
    }

    /** MediaStore 兜底：某些 MIUI 版本直接 list() 目录拿不到，但媒体库里有 */
    private static void collectMediaStore(Context ctx, Cfg cfg, List<File> out, String tag) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            String dirName = new File(cfg.dir).getName();
            // 1) 传统 _data 列（Android 10 起可能不可用，失败就换 RELATIVE_PATH）
            try {
                Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Images.Media.DATA},
                        MediaStore.Images.Media.DATA + " LIKE ?",
                        new String[]{"%" + cfg.dir + "%"}, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        String p = c.getString(0);
                        if (p != null && isSupported(p)) {
                            File f = new File(p);
                            if (f.isFile()) {
                                out.add(f);
                            }
                        }
                    }
                    close(c);
                    verbose(tag + " _data ok");
                    return;
                }
            } catch (Throwable t) {
                verbose(tag + " _data unavailable: " + t);
            }
            // 2) RELATIVE_PATH + DISPLAY_NAME（Android 10+）
            Cursor c2 = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media.RELATIVE_PATH,
                            MediaStore.Images.Media.DISPLAY_NAME},
                    MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?",
                    new String[]{"%" + dirName + "%"}, null);
            if (c2 != null) {
                File ext = Environment.getExternalStorageDirectory();
                while (c2.moveToNext()) {
                    String rel = c2.getString(0);
                    String name = c2.getString(1);
                    if (name == null || rel == null || !isSupported(name)) {
                        continue;
                    }
                    File f = new File(ext, rel + name);
                    if (f.isFile()) {
                        out.add(f);
                    }
                }
                close(c2);
                verbose(tag + " relative_path ok");
            }
        } catch (Throwable t) {
            verbose(tag + " failed: " + t);
        }
    }

    /** 按全路径升序（洗牌前先归一，使结果只取决于文件集合） */
    private static final Comparator<File> BY_PATH_ASC = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return a.getAbsolutePath().compareTo(b.getAbsolutePath());
        }
    };

    /**
     * 按所选模式调整「保留集」在库中的先后。
     * seq：最新在前（want 已是 mtime 降序）；desc：反过来；random：先按路径归一再用
     * 「文件集合派生值 ^ 种子」洗牌，保证同一批文件＋同一粒种子永远得到同一顺序。
     * 右滑轮播（feedcache/left_lks）由 cache_o 严格递增承载该顺序，锁屏自选（lks_wp）
     * 无 ORDER BY 时按 _id（= 重插顺序）决出。
     */
    private static void applyOrderMode(List<File> want, Cfg cfg) {
        if (want == null || want.size() < 2) {
            return;
        }
        if (Prefs.ORDER_DESC.equals(cfg.order)) {
            Collections.reverse(want);
        } else if (Prefs.ORDER_RANDOM.equals(cfg.order)) {
            Collections.sort(want, BY_PATH_ASC);
            long h = 1125899906842597L;
            for (int i = 0; i < want.size(); i++) {
                h = h * 31 + want.get(i).getAbsolutePath().hashCode();
            }
            Collections.shuffle(want, new java.util.Random(h ^ cfg.seed));
        }
    }

    /** 读库里本模块管的行（按 _id 升序＝画报读取顺序），返回它们的 url_l */
    private static List<String> queryManagedOrder(Context ctx, Uri uri, Set<String> keep) {
        // 必须与画报「取下一张」的口径一致：画报是
        //   order by cache_o asc/desc + show_t=0
        // 而这里旧实现不传 sortOrder，拿到的是 provider 默认顺序（通常是 _id 升序）。
        // 两边口径不同就会出现「模块认为顺序已经对了、画报播出来却是另一个顺序」，
        // 表现出来就是「改了加载顺序但没生效」。先按 cache_o 排，取不到再退回默认。
        List<String> cur = queryManagedOrderBy(ctx, uri, keep, "cache_o ASC");
        if (cur.isEmpty()) {
            cur = queryManagedOrderBy(ctx, uri, keep, null);
        }
        return cur;
    }

    private static List<String> queryManagedOrderBy(Context ctx, Uri uri, Set<String> keep,
                                                    String sortOrder) {
        List<String> cur = new ArrayList<String>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{"url_l", "bizids"},
                    null, null, sortOrder);
            while (c != null && c.moveToNext()) {
                String u = c.getString(0);
                String b = c.getString(1);
                if (u == null || b == null || b.indexOf("cw") < 0) {
                    continue;
                }
                if (keep != null && !keep.contains(u)) {
                    continue;
                }
                cur.add(u);
            }
        } catch (Throwable t) {
            verbose("order query failed: " + t);
        } finally {
            close(c);
        }
        return cur;
    }

    /**
     * 让库里的先后与目标顺序一致。不一致时把这批行删掉再按目标顺序重新插入
     * （_id 是自增主键，重插后 _id 升序恰好就是目标顺序）。
     * 返回被重排的行数，0 表示本来就已经一致。
     */
    private static int enforceOrder(Context ctx, Uri uri, Set<String> keep, List<File> want, Cfg cfg,
                                    Set<String> present) {
        if (want == null || want.isEmpty()) {
            return 0;
        }
        if (itemCtor() == null) {
            // 没有可用的插入构造器就别删，避免把行删了却插不回来
            return 0;
        }
        List<String> cur = queryManagedOrder(ctx, uri, keep);
        List<String> target = new ArrayList<String>(want.size());
        for (int i = 0; i < want.size(); i++) {
            // 与 insertOne 用同一套「实际入库路径」，否则每次 sync 都会
            // 误判顺序不一致，白删一遍再重插
            File u = pickForDb(want.get(i));
            if (u != null) {
                target.add(u.getAbsolutePath());
            }
        }
        // 缩略图还没全部就绪时不要重排：target 会短于 want，
        // 一旦判定「顺序不一致」就会把库里已有的行整片删掉再插回一个残缺列表，
        // 右滑直接少图。等 worker 补齐后下一轮 sync 再来。
        if (target.size() != want.size()) {
            verbose("reorder skip: thumb " + target.size() + "/" + want.size());
            return 0;
        }
        if (cur.equals(target) && !hasDuplicateCacheO(ctx, uri, keep)) {
            return 0;
        }
        int n = 0;
        // 删除按每段 100 个参数批量下发：旧实现逐行 delete，
        // cap=100 时就是 100 次 ContentResolver 往返（换加载顺序、增删图片时必现）。
        for (int from = 0; from < cur.size(); from += 100) {
            int to = Math.min(from + 100, cur.size());
            StringBuilder ph = new StringBuilder();
            String[] args = new String[to - from];
            for (int i = from; i < to; i++) {
                if (i > from) {
                    ph.append(',');
                }
                ph.append('?');
                args[i - from] = cur.get(i);
            }
            try {
                int d = ctx.getContentResolver().delete(uri,
                        "url_l in (" + ph + ") and bizids like '%cw%'", args);
                n += Math.max(0, d);
            } catch (Throwable t) {
                verbose("reorder delete failed at " + from + ": " + t);
            }
        }
        if (present != null) {
            // 这批行刚被删掉，避免重插时被「已存在」集合挡住
            present.removeAll(cur);
        }
        for (int i = 0; i < want.size(); i++) {
            insertOne(ctx, uri, want.get(i), cfg, present);
        }
        log(Log.INFO, TAG, "reorder rows=" + n + " order=" + cfg.order
                + (cur.equals(target) ? "" : " (was " + cur.size() + " want " + target.size() + ")"));
        return n;
    }

    /**
     * 本模块管的这批行里，cache_o 是否存在重复值。
     *
     * 早期版本整批只写了一个 cache_o（同一个 now），28 行共用同一个值。
     * 画报「取下一张」是 order by cache_o asc + show_t=0，cache_o 全等时排序退化，
     * 顺序只能靠 _id 兜底 —— 一旦中途增量插入或重排，顺序就和「加载顺序」对不上。
     * 所以这里一旦发现重复，就认为顺序不可靠，让 enforceOrder 走一次整体重排，
     * 把 cache_o 重写成严格递增（重插时 cache_o = now + i）。
     *
     * 读取失败时返回 true：宁可多排一次把顺序修正过来，也不要留下一个顺序错乱的库。
     */
    private static boolean hasDuplicateCacheO(Context ctx, Uri uri, Set<String> keep) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{"url_l", "cache_o"},
                    "bizids like '%cw%'", null, null);
            if (c == null) {
                return true;
            }
            Set<String> seenO = new HashSet<String>();
            int rows = 0;
            while (c.moveToNext()) {
                String u = c.getString(0);
                if (u == null) {
                    continue;
                }
                if (keep != null && !keep.contains(u)) {
                    continue;
                }
                rows++;
                seenO.add(String.valueOf(c.getLong(1)));
            }
            if (rows <= 1) {
                return false;
            }
            return seenO.size() < rows;
        } catch (Throwable t) {
            verbose("cache_o dup probe failed: " + t);
            return true;
        } finally {
            close(c);
        }
    }

    /**
     * 一次性读出库里本模块管的行（bizids 含 cw）的 url_l。
     *
     * 返回 null 表示读取失败，调用方应退回逐条 {@link #existsInDb}。
     */
    private static Set<String> snapshotManaged(Context ctx, Uri uri) {
        Set<String> out = new HashSet<String>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{"url_l"},
                    "bizids like '%cw%'", null, null);
            if (c == null) {
                return null;
            }
            while (c.moveToNext()) {
                String u = c.getString(0);
                if (u != null && u.length() > 0) {
                    out.add(u);
                }
            }
        } catch (Throwable t) {
            verbose("snapshot failed: " + t);
            return null;
        } finally {
            close(c);
        }
        return out;
    }

    /**
     * 单张入库：已存在则跳过；返回 true 表示这次真的插了一行。
     *
     * present 非空时直接查内存集合，省掉每张图一次 ContentResolver 查询；
     * 插入成功后会加进集合，保证同一次同步内的重复项也被挡住。
     */
    private static boolean insertOne(Context ctx, Uri uri, File f, Cfg cfg, Set<String> present) {
        try {
            // 大图必须用后台生成的缩略图：画报直接按路径解码，原图 4K-7K
            // 会拖垮锁屏并渲染成灰块。缩略图还没好，这一轮先不入库。
            File use = pickForDb(f);
            if (use == null) {
                // 入库副本（画报私有目录里的缩略图）还没生成好，这一轮先不入库，
                // 但要记下来：本轮因此不算完整同步，由 scheduleLazyFollowup 补跑。
                sDbSkipNoThumb++;
                return false;
            }
            String path = use.getAbsolutePath();
            if (present != null) {
                if (present.contains(path)) {
                    return false;
                }
            } else if (existsInDb(ctx, uri, path)) {
                return false;
            }
            Constructor<?> ctor = itemCtor();
            if (ctor == null) {
                return false;
            }
            // 与官方「加自选」完全同一条路径：构造器内部自己 insert 到 lks_wp
            ctor.newInstance(use, TYPE_PIC, BIZ_CW);
            if (present != null) {
                present.add(path);
            }
            verbose("insert " + path);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "insert failed " + f + ": " + t);
            return false;
        }
    }

    private static boolean existsInDb(Context ctx, Uri uri, String path) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{"url_l"},
                    "url_l=? and bizids like '%cw%'", new String[]{path}, null);
            return c != null && c.moveToFirst();
        } catch (Throwable t) {
            verbose("exists check failed " + path + ": " + t);
            return false;
        } finally {
            close(c);
        }
    }

    private static Constructor<?> itemCtor() {
        Constructor<?> c = sItemCtor;
        if (c != null || sItemCtorBad) {
            return c;
        }
        try {
            Class<?> k = Class.forName(CLS_ITEM, false, cl());
            for (Constructor<?> cc : k.getDeclaredConstructors()) {
                Class<?>[] ps = cc.getParameterTypes();
                if (ps.length == 3 && ps[0] == File.class
                        && ps[1] == String.class && ps[2] == String.class) {
                    cc.setAccessible(true);
                    sItemCtor = cc;
                    return cc;
                }
            }
            sItemCtorBad = true;
            log(Log.ERROR, TAG, "WallpaperItem(File,String,String) 构造器没找到");
        } catch (Throwable t) {
            sItemCtorBad = true;
            log(Log.ERROR, TAG, "WallpaperItem 解析失败", t);
        }
        return null;
    }

    // =====================================================================
    // 目标 URI / 运行环境
    // =====================================================================

    /** 拿 lks_wp 表 URI：优先 MiFGDBDef.LKS_WALLPAPER_URI，其次扫 static Uri 字段 */
    // =====================================================================
    // 模式自愈：画报只有在 custom_wallpaper 模式下才用「自选壁纸」池轮播
    // =====================================================================

    /** 画报数据库文件名：自选壁纸池 lks_wp 与 setting 表都在这里 */
    private static final String DB_NAME = "mifg-data.db";
    private static final String MODE_KEY = "mode_type";
    /**
     * setting 表的 attr 列语义（逆向 {@code SettingManager.saveToDb} /
     * {@code cleanOnlineSetting}）：
     * <ul>
     *   <li>{@code 1} = 在线下发设置，{@code cleanOnlineSetting()} 会按
     *       {@code attr=?} 全部删掉</li>
     *   <li>{@code 2} = 本地设置（画报自己写 sst_mode / mode_type 用的就是这个）</li>
     * </ul>
     * 模块旧实现写 {@code "1"}，于是自己刚写进去的 mode_type 会被画报的在线设置
     * 清理逻辑删掉 —— 模式写入时灵时不灵的直接原因。
     */
    private static final int SETTING_ATTR_LOCAL = 2;
    private static final String MODE_CW = "custom_wallpaper";
    /**
     * 画报原生「锁屏壁纸轮播」模式（= 画报里「推荐壁纸」）。
     *
     * <p>取值经逆向校准（{@code com.mfashiongallery.emag.ssetting.SSettingUtils
     * .isSstMode}）：模式只存在于 SharedPreferences {@code setting} 的
     * {@code sst_mode} 键，画报读该键时用的默认值就是 {@code auto_recommand_paper}。
     *
     * <p>旧实现在这里写的是 {@code "lockscreen"} —— 那只是画报埋点里的页面
     * scope 名（{@code CurrentWallpaperInfo}/{@code LockscreenMagazineProvider}
     * 里作为 type 参数使用），<b>不是合法的模式值</b>。写进去以后
     * {@code isSstMode("custom_wallpaper")} 与 {@code isSstMode("auto_recommand_paper")}
     * 双双为 false，画报落进「未知模式」分支，是「切换开关后画报行为诡异、
     * 有时不轮播、设置页显示的状态与实际不符」的根因之一。
     */
    private static final String MODE_NATIVE = "auto_recommand_paper";
    /** 旧名兼容：语义同 {@link #MODE_NATIVE} */
    private static final String MODE_LKS = MODE_NATIVE;
    /** 模式自愈节流时间戳 */
    private static volatile long sModeFixAt;
    /** 上次把画报切回原生锁屏轮播模式的时间（节流用） */
    private static volatile long sModeRestoreAt;

    /**
     * 保证画报处于「自选壁纸」模式。
     *
     * <p>只把本地目录的图片同步进 {@code lks_wp}（bizids=cw）而不切模式时，画报轮播
     * 仍走推荐池（{@code files/img_cache} 里早就下载好的网络图），于是表现为
     * 「开了本地照片还是放以前的缓存图」「锁屏根本不换图」。手动在画报里添加一张图
     * 之所以能修好，就是因为那一步顺带把模式切成了 {@code custom_wallpaper}。</p>
     *
     * <p>两处都要写：SharedPreferences 的 {@code sst_mode}（同进程时写的就是目标
     * 内存里的同一个实例，立即生效）和 DB {@code setting} 表的 {@code mode_type}
     * （跨进程 / 重启后的权威值）。</p>
     */
    // =====================================================================
    // 锁屏右滑轮播：feedcache / left_lks
    // =====================================================================
    //
    // 「自选图片」页写的是 lks_wp（bizids=cw），只影响画报自己的自选池；
    // 锁屏**右滑**读的却是另一个库 datacache 的 feedcache 表里
    // req_tag='left_lks' 的那批行，内容全是服务端下发的网络图
    // （url_h/url_m/url_l 指向 pandora CDN）。所以只同步 lks_wp 时，
    // 右滑永远是网图。这里把同一批本地照片也写进 feedcache。

    /** feedcache 表 URI：优先 MiFGDBDef.FEED_CACHE_URI，其次按已知 authority 拼 */
    private static Uri feedCacheUri() {
        Uri u = sFeedUri;
        if (u != null) {
            return u;
        }
        try {
            Class<?> def = Class.forName(CLS_DBDEF, false, cl());
            Field f = def.getField("FEED_CACHE_URI");
            Object v = f.get(null);
            if (v instanceof Uri) {
                sFeedUri = (Uri) v;
                return sFeedUri;
            }
        } catch (Throwable ignored) {
        }
        sFeedUri = Uri.parse("content://" + DC_AUTH + "/feedcache");
        return sFeedUri;
    }

    /** 缩略图路径：同目录下的 .thumb/<同名>.jpg */
    private static File thumbOf(File src) {
        File dir = src.getParentFile();
        if (dir == null) {
            return src;
        }
        return new File(new File(dir, THUMB_SUB), stem(src.getName()) + ".jpg");
    }

    /** 去掉扩展名的文件名 */
    private static String stem(String name) {
        int i = name.lastIndexOf('.');
        return i <= 0 ? name : name.substring(0, i);
    }

    /**
     * 入库／写 feedcache 时实际使用的文件：缩略图存在就用缩略图。
     *
     * <p>画报把 URL 直接当路径解码，5300x7067 的 PNG 一解码就是 37M 像素、
     * 上百 MB 内存，锁屏进程直接被拖垮（表现为解锁卡顿、轮播停在上一张）。
     * 缩略图由后台线程逐步生成；生成前后这里返回不同文件，下一次 sync 会
     * 自然把库里那行换成缩略图版本（旧行被 prune、新行被 insert）。</p>
     */
    private static File pickFile(File src) {
        try {
            File th = thumbOf(src);
            if (!th.equals(src) && th.isFile() && th.length() > 0
                    && th.lastModified() >= src.lastModified()) {
                return th;
            }
        } catch (Throwable ignored) {
        }
        return src;
    }

    /**
     * 入库专用：只有缩略图已就绪才返回它，否则返回 null 表示「这一轮先别入库」。
     *
     * <p>为什么不能退回原图：画报把 url_l 直接当本地路径解码，25MB 的 PNG
     * 解出来是几十 MB 位图，锁屏进程加载失败后只画一层灰色蒙版——这正是
     * 「一部分图正常、一部分是灰块」的来源。宁可这张图晚一轮进轮播，
     * 也不要让用户看到灰块。</p>
     */
    private static File pickForDb(File src) {
        try {
            File th = thumbOf(src);
            if (th.equals(src)) {
                return null;
            }
            // 注意：这里不能拿 .thumb 是否可读做前置检查。画报进程没有
            // MANAGE_EXTERNAL_STORAGE，.thumb 是隐藏目录、未进 MediaStore，
            // 受 scoped storage / FUSE 过滤根本读不到（实测 lazy 恒为 0）。
            // 一旦用它做门禁，用户清一次画报缓存后 pickForDb 就永远返回 null，
            // 锁屏／壁纸轮播池再也补不回来。私有副本才是唯一可信的判据。
            File pv = privThumbOf(th);
            if (pv == null) {
                return null;
            }
            // 主线程只做一次 stat。这里必须用画报私有目录的副本：
            // .thumb 是隐藏目录，没进 MediaStore，画报进程受 scoped storage /
            // FUSE 过滤读不到，Glide 加载失败后只画一层灰蒙版 —— 右滑灰块根因。
            if (pv.isFile() && pv.length() > 0) {
                return pv;
            }
            // 私有副本缺失：用户「清除画报缓存」会把 files/img_cache 整个删掉，
            // 或者这是私有副本机制上线后的第一次运行。此时当场补一份，
            // 否则这里会一直返回 null —— 清一次缓存就等于永久失去全部本地图，
            // 锁屏／壁纸轮播只剩空池（用户看到的就是「模块把轮播搞坏了」）。
            //
            // 缩略图通常几十 KB，一次几毫秒；用 LAZY_COPY_BUDGET_MS 卡住本轮总耗时，
            // 超预算的部分交给后台 worker，避免在锁屏进程主线程上顶到 ANR 线。
            if (sLazyCopySpent < LAZY_COPY_BUDGET_MS) {
                long t0 = SystemClock.uptimeMillis();
                // 先试复制 .thumb（快，几毫秒）；画报读不到隐藏目录时复制必然失败，
                // 退回从原图缩放生成 —— 这样清缓存后第一轮就能补回一批，不用干等 worker。
                boolean ok = copyThumbToPriv(th, pv);
                if (!ok) {
                    ok = doShrink(src, pv);
                }
                sLazyCopySpent += SystemClock.uptimeMillis() - t0;
                if (ok) {
                    sLazyCopyCount++;
                    return pv;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * p 是否落在画报自己的私有缓存目录里（{@code files/img_cache/…}）。
     *
     * <p>画报的原生网络图、广告素材都缓存在这里，本模块注入的私有副本也放在
     * {@code files/img_cache/left_lks_wp} 里。这类行的 {@code url_l} 形如
     * {@code /data/user/0/com.mfashiongallery.emag/files/img_cache/left_lks_wp/xxx}，
     * 既不以 {@code /storage/emulated/} 开头，也不是用户手动添加的照片路径。
     *
     * <p>所以「这条行归不归本模块管」不能只看外部存储前缀——历史版本写进去的行
     * 全是私有副本路径，只按外部存储前缀判断会一条都匹配不上，「上限 10 张、
     * 自选图片页却有一百多张」的残留就是这么留下来的。
     *
     * <p>画报原生推荐图（p_pic）在 {@code files/preloaded}、用户手动添加的自选图在
     * {@code files/custom_wallpaper_cache}，都刻意不在判定范围内。
     */
    private static boolean isPrivCachePath(String p) {
        if (p == null || p.length() == 0) {
            return false;
        }
        String s = p.replace('\\', '/');
        if (!s.startsWith("/data/")) {
            return false;
        }
        return s.indexOf("/files/img_cache/") >= 0;
    }

    /** 画报私有缩略图目录，与原生 left_lks_wp 布局一致，画报同 uid 可直接读 */
    private static File privThumbDir() {
        Context c = sCtx;
        if (c == null) {
            return null;
        }
        return new File(c.getFilesDir(), "img_cache/left_lks_wp");
    }

    /**
     * 私有缩略图目录是否「被清过」：期望 {@code expected} 份副本，实际不到一半。
     *
     * <p>用户点「清除画报缓存」会删掉 {@code files/img_cache}，画报自己的缓存和
     * 我们的私有副本一起没。此时图片集合／配置都没变，内容指纹一样，同步会被
     * 直接 skip —— 死行不清、副本不补，锁屏／壁纸轮播就一直坏着。
     * 所以检测到这个状态必须无视指纹强制跑一轮全量。
     */
    private static boolean privLooksWiped(int expected) {
        if (expected <= 0) {
            return false;
        }
        try {
            File d = privThumbDir();
            if (d == null) {
                return false;
            }
            if (!d.isDirectory()) {
                return true;
            }
            String[] names = d.list();
            int have = names == null ? 0 : names.length;
            return have < Math.max(1, expected / 2);
        } catch (Throwable t) {
            return false;
        }
    }

    /** .thumb 里那张缩略图在画报私有目录里的对应文件（与原生一样无扩展名） */
    private static File privThumbOf(File thumb) {
        File dir = privThumbDir();
        if (dir == null || thumb == null) {
            return null;
        }
        return new File(dir, stem(thumb.getName()));
    }

    /** 把 .thumb 缩略图复制到画报私有目录；只能在后台线程调用 */
    private static boolean copyThumbToPriv(File thumb, File priv) {
        if (thumb == null || priv == null) {
            return false;
        }
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            if (!(thumb.isFile() && thumb.length() > 0)) {
                return false;
            }
            File d = priv.getParentFile();
            if (d != null && !d.isDirectory() && !d.mkdirs()) {
                return false;
            }
            in = new java.io.FileInputStream(thumb);
            out = new java.io.FileOutputStream(priv);
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;
            return priv.isFile() && priv.length() > 0;
        } catch (Throwable t) {
            verbose("priv copy failed " + priv.getName() + ": " + t);
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 生成单张缩略图（同步、可能耗时，只能在后台线程调用） */
    private static boolean doShrink(File src, File dst) {
        Bitmap bmp = null;
        try {
            long len = src.length();
            // 几十 MB 的原图（PNG 居多）光解码就要几百 ms 到数秒，很容易把
            // 后台线程拖到被系统冻结。这种直接放弃，让画报按需自己加载。
            if (len > SHRINK_SKIP_BYTES) {
                verbose("shrink skip huge " + src.getName() + " " + (len / 1048576) + "MB");
                return false;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getAbsolutePath(), o);
            int w = o.outWidth;
            int h = o.outHeight;
            if (w <= 0 || h <= 0) {
                return false;
            }
            int max = Math.max(w, h);
            // 小图且尺寸不大：没必要缩放，但缩略图**必须存在**。
            // pickForDb 是严格模式：没有 .thumb 就整张跳过入库，于是出现
            // 「上限 10、实际只同步 8 张」——两张 200KB 的小图永远凑不满。
            // 这里直接把原图复制成缩略图（成本几 ms，解码压力也小）。
            if (len <= SHRINK_OVER_BYTES && max <= THUMB_MAX_EDGE) {
                return copyThumbToPriv(src, dst);
            }
            int sample = 1;
            while (max / (sample * 2) >= THUMB_MAX_EDGE) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
            if (bmp == null) {
                return false;
            }
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            int bmax = Math.max(bw, bh);
            if (bmax > THUMB_MAX_EDGE) {
                float sc = (float) THUMB_MAX_EDGE / (float) bmax;
                int nw = Math.max(1, Math.round(bw * sc));
                int nh = Math.max(1, Math.round(bh * sc));
                Bitmap s2 = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                if (s2 != bmp) {
                    bmp.recycle();
                }
                bmp = s2;
            }
            File p = dst.getParentFile();
            if (p != null && !p.isDirectory() && !p.mkdirs()) {
                return false;
            }
            FileOutputStream fo = new FileOutputStream(dst);
            try {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, fo)) {
                    return false;
                }
                fo.flush();
            } finally {
                try {
                    fo.close();
                } catch (Throwable ignored) {
                }
            }
            if (dst.isFile() && dst.length() > 0) {
                // 让 pickFile 的 mtime 判定稳定成立
                dst.setLastModified(Math.max(src.lastModified(), System.currentTimeMillis()));
                verbose("thumb " + src.getName() + " " + (len / 1024) + "KB -> "
                        + (dst.length() / 1024) + "KB " + bmp.getWidth() + "x" + bmp.getHeight());
                return true;
            }
        } catch (Throwable t) {
            verbose("shrink failed " + src.getName() + ": " + t);
        } finally {
            if (bmp != null) {
                try {
                    bmp.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /**
     * 后台补齐缩略图。单实例守护线程，逐个处理，不阻塞 hook 所在的主线程。
     *
     * <p>处理完不主动触发 sync：在锁屏进程里反复唤醒画报反而更费电，
     * 下次画报入口触发 sync 时自然会用上新缩略图。</p>
     */
    /** 缩略图还没就绪的那几张：延迟补跑一轮（最多 MAX_LAZY_FOLLOWUP 轮，避免死循环） */
    private static void scheduleLazyFollowup(final Context ctx) {
        if (sLazyFollowup >= MAX_LAZY_FOLLOWUP) {
            verbose("lazy followup giveup after " + sLazyFollowup + " rounds");
            return;
        }
        sLazyFollowup++;
        final Context c = ctx;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(LAZY_FOLLOWUP_MS);
                } catch (Throwable ignored) {
                }
                try {
                    sLastTriggerAt = 0;
                    sync(c, "lazy-followup");
                } catch (Throwable ignored) {
                }
            }
        }, "MiShareMT2-lazy-followup");
        t.setDaemon(true);
        t.start();
        verbose("lazy followup #" + sLazyFollowup + " in " + LAZY_FOLLOWUP_MS + "ms");
    }

    private static void kickThumbWorker(Context ctx, List<File> want) {
        if (want == null || want.isEmpty()) {
            return;
        }
        // 画报退到后台会被系统冻结，线程停在 decode 里时 finally 永不执行，
        // sThumbBusy 就一直是 true —— 之后每次 sync 都跳过缩图，一张也补不出来。
        // 用开始时刻兜底：超过 90s 视为上一轮已废，直接重开一轮。
        if (sThumbBusy && SystemClock.uptimeMillis() - sThumbAt < 90000L) {
            return;
        }
        final List<File> queue = new ArrayList<File>(want);
        sThumbBusy = true;
        sThumbAt = SystemClock.uptimeMillis();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                int skip = 0;
                int priv = 0;
                long t0 = SystemClock.uptimeMillis();
                try {
                    // 分批做，每批 THUMB_PER_RUN 张，批间让出 CPU。
                    // 一次做太多跑到一半被冻结等于白跑，所以批内不 sleep、批间 sleep。
                    for (int from = 0; from < queue.size(); from += THUMB_PER_RUN) {
                        int to = Math.min(from + THUMB_PER_RUN, queue.size());
                        for (int i = from; i < to; i++) {
                            File f = queue.get(i);
                            try {
                                File th = thumbOf(f);
                                if (!(th.isFile() && th.length() > 0
                                        && th.lastModified() >= f.lastModified())) {
                                    if (doShrink(f, th)) {
                                        ok++;
                                    } else {
                                        skip++;
                                    }
                                }
                                // 补齐画报私有目录副本。画报读不到 .thumb（隐藏目录
                                // 未进 MediaStore，受 scoped storage / FUSE 过滤），
                                // 只有私有副本能让右滑／锁屏正常出图而不是灰块。
                                // 复制必然失败时（.thumb 读不到）直接从原图缩放生成，
                                // 这样用户清掉画报缓存后也能自动重建，无需人工急救。
                                File pv = privThumbOf(th);
                                if (pv != null && !(pv.isFile() && pv.length() > 0)) {
                                    if (copyThumbToPriv(th, pv) || doShrink(f, pv)) {
                                        priv++;
                                    }
                                }
                            } catch (Throwable ignored) {
                                skip++;
                            }
                        }
                        if (to < queue.size()) {
                            try {
                                Thread.sleep(200L);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                } finally {
                    sThumbBusy = false;
                    sThumbThread = null;
                    sThumbAt = 0L;
                }
                sLastThumb = ok + "new/" + skip + "skip/" + priv + "priv/"
                        + queue.size()
                        + " " + (SystemClock.uptimeMillis() - t0) + "ms";
                log(Log.INFO, TAG, "thumb worker done: " + sLastThumb);
            }
        }, "MiShare-FgThumb");
        t.setDaemon(true);
        sThumbThread = t;
        t.start();
    }

    /**
     * 把本地照片同步进锁屏右滑轮播池（feedcache / req_tag='left_lks'）。
     *
     * <p>整批删掉重插：这批行的 order_f 决定右滑顺序，逐行 diff 的收益
     * 远小于「顺序错乱」的风险。任何一步失败都只记日志，不影响主同步。</p>
     */
    /**
     * 关闭「本地图片接管」后，把右滑轮播池恢复成画报自己的内容。
     *
     * <p>只做一次：处理完写一个状态文件，避免每 800ms 的 sync 反复 delete，
     * 把画报刚拉回来的网络图又删掉。</p>
     */
    private static void purgeLeftLksOnce(Context ctx) {
        File mark = leftLksMarkFile(ctx);
        if (mark != null && mark.isFile()) {
            return;
        }
        Uri uri = feedCacheUri();
        if (uri == null || ctx == null) {
            return;
        }
        int d = 0;
        try {
            d = ctx.getContentResolver().delete(uri, "req_tag=?",
                    new String[]{REQ_LEFT_LKS});
        } catch (Throwable t) {
            log(Log.WARN, TAG, "purgeLeftLks delete failed: " + t);
            return;
        }
        sLastLeftLks = 0;
        writeSmallFile(mark, "purged\n");
        log(Log.INFO, TAG, "feedcache left_lks purged (localPhoto=off): " + d);
    }

    /** 开关重新打开时清掉「已清理」标记，下次关闭才会再清一遍 */
    private static void markLeftLksOn(Context ctx) {
        File mark = leftLksMarkFile(ctx);
        if (mark != null && mark.isFile()) {
            if (!mark.delete()) {
                log(Log.WARN, TAG, "leftlks mark delete failed");
            }
        }
    }

    private static File leftLksMarkFile(Context ctx) {
        File d = dataDir(ctx);
        return d == null ? null : new File(d, "leftlks.off");
    }

    /** 写小文本文件（配置/标记用），失败只记日志 */
    private static void writeSmallFile(File f, String text) {
        if (f == null) {
            return;
        }
        FileOutputStream os = null;
        try {
            File p = f.getParentFile();
            if (p != null && !p.isDirectory()) {
                p.mkdirs();
            }
            os = new FileOutputStream(f, false);
            os.write(text.getBytes("UTF-8"));
            os.flush();
        } catch (Throwable t) {
            log(Log.WARN, TAG, "writeSmallFile failed: " + f + " " + t);
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** feedcache 里右滑轮播池(left_lks)当前行数；查询失败返回 -1（不触发强制全量） */
    private static int leftLksCount(Context ctx) {
        if (ctx == null) {
            return -1;
        }
        Uri u = feedCacheUri();
        if (u == null) {
            return -1;
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(u, new String[]{"fid"},
                    "req_tag=?", new String[]{REQ_LEFT_LKS}, null);
            return c == null ? -1 : c.getCount();
        } catch (Throwable t) {
            log(Log.WARN, TAG, "leftLksCount failed: " + t);
            return -1;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void syncLeftLks(Context ctx, List<File> want) {
        Uri uri = feedCacheUri();
        if (uri == null || ctx == null || want == null) {
            return;
        }
        ContentResolver cr = ctx.getContentResolver();
        // 先比对：库里已有行和目标完全一致（含顺序）就整批跳过。
        // 否则每 800ms 一次的 sync 都会把 show_t 清零，右滑永远从第一张重新开始。
        java.util.List<String> have = new ArrayList<String>();
        java.util.Set<String> haveIds = new HashSet<String>();
        /** fid -> 库里当前的 url_l，用来判断「已有行要不要换成本地私有副本路径」 */
        java.util.Map<String, String> haveUrl = new java.util.HashMap<String, String>();
        /**
         * fid -> 库里当前的 show_t（0=未播，非 0=已播时间戳）。
         * 画报取下一张的条件是 show_t=0，所以整表重建时必须把它按 fid 回填，
         * 否则每加一批新图进度就整体归零，用户看到的就是「又从头播」。
         */
        java.util.Map<String, Long> haveShowT = new java.util.HashMap<String, Long>();
        try {
            Cursor c = cr.query(uri, new String[]{"fid", "url_l", "show_t"}, "req_tag=?",
                    new String[]{REQ_LEFT_LKS}, "order_f ASC");
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String fid = c.getString(0);
                        String u = c.getString(1);
                        haveIds.add(fid);
                        haveUrl.put(fid, u == null ? "" : u);
                        try {
                            haveShowT.put(fid, Long.valueOf(c.getLong(2)));
                        } catch (Throwable ignored) {
                        }

                        // url_l 也参与比对：缩略图后补生成时 fid 不变、url_l 变了，
                        // 只比 fid 会导致库里永远是那张指向原图的灰块行。
                        have.add(fid + "\u0001" + (u == null ? "" : u));
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "feedcache query failed: " + t);
        }
        // 一次判定、两处复用：下面 insert 循环直接用这张表，
        // 避免对同一张图重复做 isFile/length 两次 stat（cap=100 时就是省 100 次）
        File[] ready = new File[want.size()];
        java.util.List<String> target = new ArrayList<String>();
        for (int i = 0; i < want.size(); i++) {
            File u = pickForDb(want.get(i));
            ready[i] = u;
            if (u == null) {
                continue;
            }
            target.add(stem(u.getName()) + "-" + REQ_LEFT_LKS + "\u0001" + u.getAbsolutePath());
        }
        if (have.equals(target)) {
            sLastLeftLks = have.size();
            return;
        }
        // 缩略图还没全好时 target 是残缺的，整表删完就只剩几张，右滑直接少图。
        // 这种情况改为「增量补插」：已有的行原样不动（保住 show_t 进度），
        // 只补新就绪的那几张。等全部就绪后下一轮 sync 再走整表重建。
        boolean allReady = (target.size() == want.size());
        int del = 0;
        if (allReady) {
            try {
                del = cr.delete(uri, "req_tag=?", new String[]{REQ_LEFT_LKS});
            } catch (Throwable t) {
                log(Log.WARN, TAG, "feedcache delete failed: " + t);
                return;
            }
        } else {
            // 增量模式：先把「指向 .thumb / 原图」的脏行清掉。
            // 这类行是旧版本写进去的，画报读不到（.thumb 是隐藏目录，未进
            // MediaStore，受 scoped storage / FUSE 过滤），表现就是右滑灰块。
            // 留着它们会永远灰，删掉后等私有副本就绪，下面的增量补插会重新
            // 插入正确路径的行。
            try {
                File pd = privThumbDir();
                String prefix = pd == null ? "" : pd.getAbsolutePath() + "/";
                if (prefix.length() > 1) {
                    del = cr.delete(uri,
                            "req_tag=? and (url_l is null or url_l not like ?)",
                            new String[]{REQ_LEFT_LKS, prefix + "%"});
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "feedcache clean stale failed: " + t);
            }
        }
        long now = System.currentTimeMillis();
        int n = 0;
        for (int i = 0; i < want.size(); i++) {
            // 前 12 张就是右滑首屏。缩略图缺失时当场补一张：sync 本身跑在后台
            // 线程，多花几百毫秒换首屏不卡，比等 worker 慢慢补划算得多。
            if (i < 12) {
                try {
                    File src = want.get(i);
                    File th = thumbOf(src);
                    if (!(th.isFile() && th.length() > 0
                            && th.lastModified() >= src.lastModified())) {
                        doShrink(src, th);
                    }
                    // 首屏同样需要私有副本，否则右滑第一屏就是灰块
                    File pv = privThumbOf(th);
                    if (pv != null && th.isFile() && th.length() > 0
                            && !(pv.isFile() && pv.length() == th.length())) {
                        copyThumbToPriv(th, pv);
                    }
                } catch (Throwable ignored) {
                }
            }
            // 前 12 张上面可能刚补生成缩略图，要重新判定一次；其余直接复用预判结果
            File f = (i < 12) ? pickForDb(want.get(i)) : ready[i];
            if (f == null) {
                // 缩略图还没生成好：宁可这一轮少一张，也不写指向原图的灰块行
                continue;
            }
            String id = stem(f.getName());
            String fid = id + "-" + REQ_LEFT_LKS;
            if (!allReady && haveIds.contains(fid)) {
                // 增量模式：已在库里的行不要 REPLACE，否则 show_t 被清零，
                // 右滑进度每次 sync 都退回第一张。
                // 但如果 url_l 和目标不一致（旧行指向 .thumb 灰块路径，或私有
                // 副本被重建），必须原地 update —— 只比 fid 跳过的话，库里会
                // 永远留着那条灰块行，右滑永远是灰的。
                String wantUrl = f.getAbsolutePath();
                String curUrl = haveUrl.get(fid);
                if (wantUrl.equals(curUrl)) {
                    continue;
                }
                try {
                    ContentValues up = new ContentValues();
                    up.put("url_l", wantUrl);
                    up.put("update_t", now);
                    // 只挪排序位，绝不动 show_t（增量模式存在的意义就是保进度）
                    up.put("cache_o", now + i);
                    int[] wh2 = imageSize(f);
                    up.put("raw_d", buildFeedJson(id, wantUrl, i + 1, now, wh2[0], wh2[1]));
                    if (cr.update(uri, up, "fid=?", new String[]{fid}) > 0) {
                        n++;
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "feedcache update failed #" + i + ": " + t);
                }
                continue;
            }
            try {
                ContentValues cv = new ContentValues();
                cv.put("fid", fid);
                cv.put("img_id", id);
                cv.put("req_tag", REQ_LEFT_LKS);
                cv.put("item_type", "image");
                cv.put("order_f", i + 1);
                // cache_o 才是画报 loadMiFGFeeds2 的真实排序字段（"cache_o ASC/DESC"）。
                // 以前整批写同一个 now，cache_o 全等，顺序只能靠隐式 _id 决出，
                // SQLite 换了扫描路径顺序就散。改成 now+i 严格递增后，cache_o 顺序
                // 恒等于 want 顺序（与 order_f 同源），顺序/倒序/随机三态都直接生效。
                cv.put("cache_o", now + i);
                cv.put("update_t", now);
                cv.put("url_l", f.getAbsolutePath());
                // 按 fid 回填原有 show_t：老图保住播放进度，新图查不到记录即 0（未播）
                Long oldShowT = haveShowT.get(fid);
                cv.put("show_t", oldShowT == null ? 0L : oldShowT.longValue());
                int[] wh = imageSize(f);
                cv.put("raw_d", buildFeedJson(id, f.getAbsolutePath(), i + 1, now,
                        wh[0], wh[1]));
                if (cr.insert(uri, cv) != null) {
                    n++;
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "feedcache insert failed #" + i + ": " + t);
                break;
            }
        }
        sLastLeftLks = n;
        log(Log.INFO, TAG, "feedcache left_lks: del=" + del + " insert=" + n
                + " of " + want.size());
        // 【v2.16】全部就绪时才清孤儿：换图/降 cap 后旧副本会一直躺在
        // files/img_cache/left_lks_wp 里（实测 35 个文件只用到 16 个）。
        // 部分就绪时 target 是残缺的，按它删会误删还在用的副本。
        if (allReady) {
            prunePrivOrphans(target);
        }
    }

    /** 删除私有副本目录里不再被 target 引用的文件（换图/降 cap 后的孤儿） */
    private static void prunePrivOrphans(java.util.List<String> target) {
        try {
            File d = privThumbDir();
            if (d == null || !d.isDirectory()) {
                return;
            }
            java.util.Set<String> keep = new HashSet<String>();
            for (String t : target) {
                int p = t.indexOf((char) 1);
                String path = p >= 0 ? t.substring(p + 1) : t;
                keep.add(new File(path).getName());
            }
            File[] fs = d.listFiles();
            if (fs == null) {
                return;
            }
            int del = 0;
            for (File f : fs) {
                if (!f.isDirectory() && !keep.contains(f.getName()) && f.delete()) {
                    del++;
                }
            }
            if (del > 0) {
                log(Log.INFO, TAG, "prune priv orphans: " + del);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "prunePrivOrphans failed: " + t);
        }
    }

    /**
     * 构造 feedcache.raw_d。画报把整条 item 序列化进这一列，右滑时直接反序列化，
     * 所以结构必须和网络下发的保持一致（mMifgItem 是嵌套的旧版模型）。
     * 图片三级 URL 全部指向同一个本地文件：画报按分辨率挑一档加载。
     */
    private static String buildFeedJson(String id, String path, int order, long now,
                                        int w, int h) {
        StringBuilder sb = new StringBuilder(1024);
        String sec = String.valueOf(now / 1000L);
        String rc = "\"rcm_info\":{\"expid\":\"\",\"order_f\":" + order
                + ",\"rcm_type\":\"recommend\",\"reqid\":\"\",\"traceid\":\"\"}";
        String times = "\"times\":{\"begin_t\":0,\"create\":0,"
                + "\"end_t\":9223372036854775,\"update\":0}";
        String exp = "\"exparam\":{\"dominant_color\":\"0\",\"mask_color\":\"\","
                + "\"img_level\":\"0\",\"stay_seconds\":\"0\"}";
        String meta = "\"meta\":{\"cp\":{\"icon\":\"\",\"id\":\"\",\"name\":\"本地\"},"
                + "\"desc\":\"\",\"tags\":[],\"categories\":[],\"title\":\"\"}";
        sb.append('{');
        sb.append("\"actions\":[],");
        sb.append("\"bizids\":[\"").append(BIZ_LKS).append("\"],");
        sb.append("\"id\":\"").append(esc(id)).append("\",");
        sb.append("\"is_favor\":0,");
        sb.append("\"mAbExpId\":\"\",");
        sb.append("\"mAbExpIdList\":\"\",");
        sb.append("\"mAlbumId\":\"\",");
        sb.append("\"mCategories\":[],");
        sb.append("\"mCpId\":\"\",");
        sb.append("\"mCpName\":\"本地\",");
        sb.append("\"mCpText\":\"本地\",");
        sb.append("\"mCreateTime\":").append(sec).append(',');
        sb.append("\"mDesc\":\"\",");
        sb.append(exp).append(',');
        sb.append("\"mFavorCount\":0,");
        sb.append("\"mHasMaskColor\":false,");
        appendUrlObj(sb, "\"mImageUrl\":", path);
        sb.append(',');
        sb.append("\"mImageUrlList\":[{");
        appendUrlObj(sb, "\"cl_url\":", path);
        sb.append("}],");
        sb.append("\"mImgHeight\":").append(h).append(',');
        sb.append("\"mImgMaskColor\":\"\",");
        sb.append("\"mImgWidth\":").append(w).append(',');
        sb.append("\"mIsHotArticle\":false,");
        sb.append("\"mIsShowXout\":false,");
        sb.append("\"mItemType\":\"image\",");
        sb.append("\"mItemUIType\":0,");
        sb.append("\"mJumpActions\":[],");
        sb.append("\"mMediaIconRawUrl\":\"\",");
        sb.append("\"mMediaId\":\"\",");
        sb.append("\"mMediaName\":\"\",");
        // mMifgItem：旧版嵌套模型
        sb.append("\"mMifgItem\":{");
        sb.append("\"abExpId\":\"\",");
        sb.append("\"abExpIdList\":\"\",");
        sb.append("\"actions\":[],");
        sb.append("\"bizids\":[\"").append(BIZ_LKS).append("\"],");
        sb.append("\"child_item_count\":1,");
        sb.append(exp).append(',');
        sb.append("\"favor_count\":0,");
        sb.append("\"id\":\"").append(esc(id)).append("\",");
        sb.append("\"images\":[{");
        appendUrlObj(sb, "\"cl_url\":", path);
        sb.append("}],");
        sb.append("\"is_favor\":0,");
        sb.append("\"item_type\":\"image\",");
        sb.append("\"j_actions\":[],");
        sb.append(meta).append(',');
        sb.append(rc).append(',');
        sb.append(times).append(',');
        sb.append("\"tracking_url\":[],");
        sb.append("\"ui_type\":0");
        sb.append("},");
        sb.append("\"mRcmInfo\":{\"expid\":\"\",\"order_f\":").append(order)
                .append(",\"rcm_type\":\"recommend\",\"reqid\":\"\",\"traceid\":\"\"},");
        sb.append("\"mTitle\":\"\",");
        sb.append("\"mTotalCountInAlbum\":0,");
        sb.append("\"mTrackUrls\":[],");
        sb.append("\"mTxtSize\":34,");
        sb.append("\"mUpdateTime\":").append(sec).append(',');
        sb.append("\"mVideoDuration\":0,");
        sb.append(meta).append(',');
        sb.append("\"source\":0,");
        sb.append(times);
        sb.append('}');
        return sb.toString();
    }

    /**
     * 只读文件头拿宽高（inJustDecodeBounds，不分配像素内存，几毫秒返回）。
     * 画报用这两个值算锁屏壁纸的裁切/缩放，填 -1 会让它走兜底路径。
     */
    private static int[] imageSize(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth > 0 && o.outHeight > 0) {
                return new int[]{o.outWidth, o.outHeight};
            }
        } catch (Throwable ignored) {
        }
        return new int[]{-1, -1};
    }

    /** 追加一个 {locator,md5_*,url_full,url_h,url_l,url_m,url_root} 对象 */
    private static void appendUrlObj(StringBuilder sb, String key, String path) {
        String p = esc(path);
        sb.append(key).append('{');
        sb.append("\"locator\":\"\",");
        sb.append("\"md5_h\":\"\",\"md5_l\":\"\",\"md5_m\":\"\",");
        sb.append("\"url_full\":\"\",");
        sb.append("\"url_h\":\"").append(p).append("\",");
        sb.append("\"url_l\":\"").append(p).append("\",");
        sb.append("\"url_m\":\"").append(p).append("\",");
        sb.append("\"url_root\":\"\"");
        sb.append('}');
    }

    /** JSON 字符串转义（路径里可能有引号/反斜杠；中文原样输出即可） */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append("\\u");
                        String hx = Integer.toHexString(c);
                        for (int k = hx.length(); k < 4; k++) {
                            b.append('0');
                        }
                        b.append(hx);
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    /**
     * 清掉 {@code lks_wp} 里的网络推荐图（{@code type='o_pic'}）。
     *
     * <p>画报切到自选壁纸模式后，轮播仍然是在整个 {@code lks_wp} 表里取，
     * 而用户之前看过的网络图（{@code source='lockscreen_wp'} / {@code type='o_pic'}）
     * 早就落了库，于是「开了本地照片轮播，锁屏还是在放网图」。
     * 本地照片模式下每次同步顺手清一遍，只留本地照片（{@code l_pic}）和预置图
     * （{@code p_pic}），池子才是干净的。
     */
    private static void purgeNetworkPics(Context ctx) {
        try {
            Uri uri = wallpaperUri(cl());
            if (uri == null) {
                return;
            }
            ContentResolver cr = ctx.getContentResolver();
            // 只清网络推荐图（o_pic）。
            // p_pic 是画报自身的预置轮播池：画报会持续重建它，删掉只会形成
            // 「我们删 -> 画报补」的反复拉锯，反而把画报 App 的自选/默认轮播
            // 搅乱（用户表现为「自选图片轮播失效」）。l_pic 是用户照片，更不能动。
            String where = "type='o_pic'";
            int n = cr.delete(uri, where, null);
            if (n > 0) {
                sLastPurged = n;
                log(Log.INFO, TAG, "purge non-local pics=" + n + " where=" + where);
            }
        } catch (Throwable t) {
            verbose("purge network pics failed: " + t);
        }
    }

    /**
     * 画报会周期性把预置图（p_pic）和网络推荐图（o_pic）重新塞回 lks_wp 池子，
     * 只靠 sync() 时清一次压不住——用户看到的就是「开了本地照片还在放画报默认图」。
     *
     * 这里用「变化驱动」而不是固定轮询：注册 ContentObserver 监听 lks_wp，
     * 池子一有变动就清一次；空闲时零唤醒，避免 5 秒一次的 IO/电量开销。
     * 另留一个 60 秒的兜底扫描，防止 observer 漏事件。
     */
    private static void ensurePurgeLoop() {
        if (sPurgeInit) {
            return;
        }
        synchronized (Fg.class) {
            if (sPurgeInit) {
                return;
            }
            // 用独立标志而不是 sPurgeObserver：observer 注册失败时也不能重复起线程
            sPurgeInit = true;
            try {
                final Context ctx = sCtx;
                if (ctx != null) {
                    ContentObserver ob = new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            onChange(selfChange, null);
                        }

                        @Override
                        public void onChange(boolean selfChange, Uri u) {
                            // sLocalPhoto 现在的语义是「右滑轮播图片接管」：该模式完全不碰
                            // lks_wp，常驻清理必须停掉，否则锁屏壁纸池会被持续清空
                            if (sCtx == null) {
                                return;
                            }
                            long now = SystemClock.uptimeMillis();
                            // 自己 delete 也会回调，3 秒内不重复
                            if (now - sLastPurgeAt < 3000L) {
                                return;
                            }
                            sLastPurgeAt = now;
                            try {
                                purgeNetworkPics(sCtx);
                            } catch (Throwable t) {
                                // ignore
                            }
                        }
                    };
                    ctx.getContentResolver().registerContentObserver(
                            wallpaperUri(cl()), true, ob);
                    sPurgeObserver = ob;
                    log(Log.INFO, TAG, "purge observer registered");
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "register purge observer failed", t);
            }
            // 兜底扫描：observer 万一漏了，60 秒补一次（比 5 秒省 12 倍唤醒）
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(60000L);
                        } catch (Throwable e) {
                            return;
                        }
                        try {
                            // 接管右滑轮播时不动 lks_wp，清理线程即使还活着也不该动手
                            if (sCtx != null) {
                                purgeNetworkPics(sCtx);
                            }
                        } catch (Throwable e) {
                            // 守护线程不能因为单次失败退出
                        }
                    }
                }
            }, "MiShare-FgPurge");
            t.setDaemon(true);
            sPurgeThread = t;
            t.start();
            log(Log.INFO, TAG, "purge fallback loop started (60s)");
        }
    }

    private static void ensureCustomMode(Context ctx) {
        if (ctx == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sModeFixAt < 60000L) {
            return;
        }
        sModeFixAt = now;
        // 1) SharedPreferences
        try {
            SharedPreferences sp = ctx.getSharedPreferences("setting", Context.MODE_PRIVATE);
            String cur = sp.getString("sst_mode", null);
            if (!MODE_CW.equals(cur)) {
                sp.edit().putString("sst_mode", MODE_CW).apply();
                log(Log.INFO, TAG, "mode sst_mode: " + cur + " -> " + MODE_CW);
            }
        } catch (Throwable t) {
            verbose("mode sp failed: " + t);
        }
        // 2) DB setting 表
        try {
            File db = ctx.getDatabasePath(DB_NAME);
            if (db == null || !db.exists()) {
                verbose("mode db missing");
                return;
            }
            SQLiteDatabase d = SQLiteDatabase.openDatabase(db.getPath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            try {
                String cur = null;
                Cursor c = d.rawQuery("select value from setting where name=?",
                        new String[]{MODE_KEY});
                try {
                    if (c.moveToFirst()) {
                        cur = c.getString(0);
                    }
                } finally {
                    c.close();
                }
                if (MODE_CW.equals(cur)) {
                    return;
                }
                long ts = System.currentTimeMillis();
                if (cur == null) {
                    d.execSQL("insert into setting(name,value,update_t,attr) values(?,?,?,?)",
                            new Object[]{MODE_KEY, MODE_CW, ts, SETTING_ATTR_LOCAL});
                } else {
                    d.execSQL("update setting set value=?,update_t=?,attr=? where name=?",
                            new Object[]{MODE_CW, ts, SETTING_ATTR_LOCAL, MODE_KEY});
                }
                log(Log.INFO, TAG, "mode mode_type: " + cur + " -> " + MODE_CW);
            } finally {
                d.close();
            }
        } catch (Throwable t) {
            verbose("mode db failed: " + t);
        }
    }

    /**
     * 右滑轮播接管专用：把本模块以前写进 lks_wp 的行全部撤掉。
     * <p>画报在「自选壁纸」模式下把整个 lks_wp 当锁屏轮播池，池子里一旦塞满
     * bizids='cw' 的本地图，画报就只认其中一张，锁屏表现为「固定一张、不再轮播」。
     * 接管右滑轮播时这些行必须清掉，锁屏才能回到画报原生的网络图轮播。
     */
    /** {@link #revokeLks} 的节流包装：sync 每 800ms 一轮，没必要每轮扫库 */
    private static volatile long sRevokeAt;
    private static volatile boolean sRevokeDone;

    /**
     * 接管右滑轮播期间调用：确认 lks_wp 里没有本模块残留的本地图，并把画报模式
     * 拉回原生锁屏轮播。
     *
     * <p>旧实现里 {@link #revokeLks} 只有定义、从未被调用（死代码），所以这条路线
     * 一直只依赖 {@code restoredLksMode} 单点切换模式：历史注入行永久留在池子里，
     * 模式又已经切走 —— 锁屏既不是纯原生推荐，也不是自选壁纸。
     */
    private static void revokeLksThrottled(Context ctx, Uri uri) {
        long now = SystemClock.uptimeMillis();
        if (sRevokeDone) {
            // 模式只在首轮切一次（restoreLksMode 会写画报自己的 sp/setting，
            // 反复切会让画报一直重置轮播状态）。残留清理则周期性兜底：
            // 旧实现 60s 之后彻底不再执行，首轮删失败（Provider 静默返回 0）
            // 就让那批死行永久留在池子里 —— 表现就是「上限 10 张、页面一百多张」。
            if (now - sRevokeAt < 300000L) {
                return;
            }
            sRevokeAt = now;
            try {
                purgeInjectedLks(ctx);
            } catch (Throwable t) {
                verbose("revoke purge failed: " + t);
            }
            return;
        }
        sRevokeDone = true;
        sRevokeAt = now;
        revokeLks(ctx, uri);
    }

    private static void revokeLks(Context ctx, Uri uri) {
        try {
            if (uri != null) {
                Set<String> removed = new HashSet<String>();
                int[] pr = new int[2];
                // keep 传空集 = 把本模块管过的行全部删除
                pruneManaged(ctx, uri, new HashSet<String>(), pr, removed);
                if (pr[0] > 0) {
                    updateManaged(ctx, new HashSet<String>(), removed, sLastDir);
                    log(Log.INFO, TAG, "revoke lks_wp rows=" + pr[0]);
                }
                // managed 记录在新装/清数据后会丢，残留行按路径特征再扫一遍
                purgeInjectedLks(ctx);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "revokeLks failed: " + t);
        }
        // 画报停在 custom_wallpaper 时锁屏只认一张自选图，必须切回原生锁屏轮播
        restoreLksMode(ctx);
    }

    /**
     * 右滑轮播接管专用：按路径特征清掉本模块历史上写进 lks_wp 的本地图。
     *
     * <p>与 {@link #pruneManaged} 不同，这里不依赖 managed 状态文件——那份记录
     * 在新装 / 清数据后就丢了，残留的行会继续占着锁屏轮播池（表现为锁屏固定一张）。
     * 判断依据是 url_l 指向外部存储下的本地照片，而画报原生的 p_pic 走
     * {@code /data/user/0/com.mfashiongallery.emag/...}，o_pic 是网络地址，
     * 都不会被误删。
     */
    private static void purgeInjectedLks(Context ctx) {
        try {
            Uri uri = wallpaperUri(cl());
            if (uri == null) {
                return;
            }
            ContentResolver cr = ctx.getContentResolver();
            // 先 query 出候选行、再按主键逐行删。
            // 旧实现直接发两条 "bizids='cw' AND url_l LIKE '/storage/emulated/%'" /
            // "'/sdcard/%'" 的 delete —— 而本模块实际写进去的 url_l 全是画报私有副本路径
            // （/data/user/0/<pkg>/files/img_cache/left_lks_wp/xxx），两个 LIKE 都不匹配，
            // 所以历史注入行一条都删不掉，池子里长期囤着上百个死行。
            Cursor c = null;
            List<Long> ids = new ArrayList<Long>();
            int scan = 0;
            try {
                c = cr.query(uri, new String[]{"_id", "url_l"}, "bizids like '%cw%'", null, null);
                while (c != null && c.moveToNext()) {
                    scan++;
                    long id = c.getLong(0);
                    String p = c.getString(1);
                    if (p == null || p.length() == 0) {
                        // 空路径行留着也没用，画报画不出来
                        ids.add(Long.valueOf(id));
                        continue;
                    }
                    // 归本模块管的三种形态：① 画报私有缓存里的副本（当前写法）
                    // ② 外部存储 / 内置存储路径（更早版本的写法）③ 文件已经不存在（死行）
                    boolean mine = isPrivCachePath(p)
                            || p.startsWith("/storage/emulated/")
                            || p.startsWith("/sdcard/")
                            || !new File(p).exists();
                    if (mine) {
                        ids.add(Long.valueOf(id));
                    }
                }
            } catch (Throwable t) {
                verbose("purge injected lks query failed: " + t);
            } finally {
                close(c);
            }
            int total = 0;
            for (int i = 0; i < ids.size(); i++) {
                try {
                    total += cr.delete(uri, "_id = ?",
                            new String[]{String.valueOf(ids.get(i).longValue())});
                } catch (Throwable t) {
                    verbose("purge injected lks delete failed: " + t);
                }
            }
            sLastPurgeScan = scan;
            if (total > 0) {
                sLastPurged = total;
                log(Log.INFO, TAG, "purge injected lks_wp rows=" + total + " scanned=" + scan);
            }
        } catch (Throwable t) {
            verbose("purge injected lks failed: " + t);
        }
    }

    /** 精选壁纸精简节流：白名单 + 上次执行时间 */
    private static volatile String sCuratedKey;
    private static volatile long sCuratedAt;

    /**
     * 精简精选壁纸：把 feedcache 里分类不在白名单的条目删掉。
     *
     * <p>画报把整条 item 的 JSON 塞进 {@code raw_d}，其中 {@code mCategories}
     * 形如 {@code [{"id":"CC00000005","name":"阅读"}]}。这里按
     * {@code scrb_items1.t_id} 白名单过滤：条目里没有任何一个分类命中白名单就删。
     *
     * <p>白名单为空表示不过滤（没开这个功能，或全选）。
     * 注意只处理 {@code req_tag='morning_detail'}：{@code left_lks} 是「右滑轮播
     * 图片接管」注入的本地照片，没有 mCategories 字段，一起删会把接管功能废掉。
     */
    private static int purgeCurated(Context ctx, Cfg cfg) {
        if (ctx == null || cfg == null || cfg.curated == null) {
            return 0;
        }
        // 节流：sync 每 800ms 一轮，整表扫 raw_d 再逐条 delete 代价太大，
        // 而白名单在设置页里才可能变化。同一份白名单 60s 内只执行一次。
        long nowMs = SystemClock.uptimeMillis();
        String key = cfg.curated;
        if (key.equals(sCuratedKey) && nowMs - sCuratedAt < 60000L) {
            return 0;
        }
        sCuratedKey = key;
        sCuratedAt = nowMs;
        String[] parts = cfg.curated.split(",");
        java.util.List<String> keep = new java.util.ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            if (s.length() > 0) {
                keep.add("\"id\":\"" + s + "\"");
            }
        }
        if (keep.isEmpty()) {
            return 0;
        }
        int del = 0;
        Cursor c = null;
        try {
            Uri uri = feedCacheUri();
            c = ctx.getContentResolver().query(uri, new String[]{"_id", "raw_d"},
                    "req_tag=?", new String[]{REQ_MORNING}, null);
            java.util.List<String> drop = new java.util.ArrayList<String>();
            while (c != null && c.moveToNext()) {
                String raw = c.getString(1);
                if (raw == null || raw.length() == 0) {
                    continue;
                }
                // 只在 mCategories 附近找，避免误命中 mAlbumId 之类的字段
                int at = raw.indexOf("\"mCategories\"");
                String seg = at < 0 ? raw : raw.substring(at, Math.min(raw.length(), at + 512));
                boolean hit = false;
                for (int i = 0; i < keep.size(); i++) {
                    if (seg.indexOf(keep.get(i)) >= 0) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    drop.add(String.valueOf(c.getLong(0)));
                }
            }
            for (int i = 0; i < drop.size(); i++) {
                try {
                    del += ctx.getContentResolver().delete(uri, "_id=?", new String[]{drop.get(i)});
                } catch (Throwable t) {
                    verbose("curated delete failed: " + t);
                }
            }
        } catch (Throwable t) {
            verbose("curated purge failed: " + t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        sCuratedPruned = del;
        if (del > 0) {
            log(Log.INFO, TAG, "curated purge del=" + del + " keep=" + cfg.curated);
        }
        return del;
    }

    /**
     * 读画报的订阅分类表（scrb_items1），给设置页做「精简精选壁纸」的多选项。
     *
     * <p>模块 app 进程没有画报私有库的权限，只能由 hook 所在的画报进程读出来、
     * 再通过状态文件带回设置页。格式 "t_id:name|t_id:name|..."——分类名里可能出现
     * 空格、括号等，但不会含这两个分隔符。
     */
    private static String readCategories(Context ctx) {
        StringBuilder sb = new StringBuilder();
        Cursor c = null;
        try {
            Uri base = wallpaperUri(cl());
            if (base == null || base.getAuthority() == null) {
                return "";
            }
            Uri u = Uri.parse("content://" + base.getAuthority() + "/scrb_items1");
            c = ctx.getContentResolver().query(u, new String[]{"t_id", "name"},
                    "t_id<>?", new String[]{"tag_id_system_default"}, "order_f ASC");
            while (c != null && c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                if (id == null || id.length() == 0 || name == null || name.length() == 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(id).append(':').append(name);
            }
        } catch (Throwable t) {
            verbose("readCategories failed: " + t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
    }

    /**
     * 把画报从「自选壁纸」模式切回原生「锁屏壁纸轮播」模式。
     * <p>两处都要写：SharedPreferences 的 {@code sst_mode} 和 DB setting 表的
     * {@code mode_type}，画报不同路径读的不是同一处。
     */
    private static void restoreLksMode(Context ctx) {
        if (ctx == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sModeRestoreAt < 60000L) {
            return;
        }
        sModeRestoreAt = now;
        // 1) SharedPreferences
        try {
            SharedPreferences sp = ctx.getSharedPreferences("setting", Context.MODE_PRIVATE);
            String cur = sp.getString("sst_mode", null);
            if (!MODE_LKS.equals(cur)) {
                sp.edit().putString("sst_mode", MODE_LKS).apply();
                log(Log.INFO, TAG, "restore sst_mode: " + cur + " -> " + MODE_LKS);
            }
        } catch (Throwable t) {
            verbose("restore sp failed: " + t);
        }
        // 2) DB setting 表
        try {
            File db = ctx.getDatabasePath(DB_NAME);
            if (db == null || !db.exists()) {
                return;
            }
            SQLiteDatabase d = SQLiteDatabase.openDatabase(db.getPath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            try {
                String cur = null;
                Cursor c = d.rawQuery("select value from setting where name=?",
                        new String[]{MODE_KEY});
                try {
                    if (c.moveToFirst()) {
                        cur = c.getString(0);
                    }
                } finally {
                    c.close();
                }
                if (MODE_LKS.equals(cur)) {
                    return;
                }
                long ts = System.currentTimeMillis();
                if (cur == null) {
                    d.execSQL("insert into setting(name,value,update_t,attr) values(?,?,?,?)",
                            new Object[]{MODE_KEY, MODE_NATIVE, ts, SETTING_ATTR_LOCAL});
                } else {
                    d.execSQL("update setting set value=?,update_t=?,attr=? where name=?",
                            new Object[]{MODE_NATIVE, ts, SETTING_ATTR_LOCAL, MODE_KEY});
                }
                log(Log.INFO, TAG, "restore mode_type: " + cur + " -> " + MODE_NATIVE);
            } finally {
                d.close();
            }
        } catch (Throwable t) {
            verbose("restore db failed: " + t);
        }
    }

    private static Uri wallpaperUri(ClassLoader cl) {
        Uri u = sUri;
        if (u != null) {
            return u;
        }
        try {
            Class<?> def = Class.forName(CLS_DBDEF, false, cl);
            try {
                Field f = def.getField("LKS_WALLPAPER_URI");
                Object v = f.get(null);
                if (v instanceof Uri) {
                    sUri = (Uri) v;
                    return sUri;
                }
            } catch (Throwable ignored) {
            }
            Uri fallback = null;
            for (Field f : def.getDeclaredFields()) {
                if (f.getType() != Uri.class || !Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (!(v instanceof Uri)) {
                        continue;
                    }
                    Uri cand = (Uri) v;
                    String s = cand.toString();
                    if (s.contains("lks_wp")) {
                        sUri = cand;
                        return cand;
                    }
                    if (fallback == null) {
                        fallback = cand;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (fallback != null) {
                sUri = fallback;
                return fallback;
            }
            Field af = def.getField("CONTENT_AUTHORITY");
            af.setAccessible(true);
            Object a = af.get(null);
            if (a instanceof String) {
                sUri = Uri.parse("content://" + a + "/lks_wp");
                return sUri;
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "wallpaperUri 定位失败", t);
        }
        return null;
    }

    private static ClassLoader cl() {
        if (sCl != null) {
            return sCl;
        }
        Context c = sCtx != null ? sCtx : appCtx();
        return c != null ? c.getClassLoader() : Fg.class.getClassLoader();
    }

    /** 目标进程的 Application Context（三个入口都没有 Context 参数，只能这样拿） */
    static Context appCtx() {
        Context c = sCtx;
        if (c != null) {
            return c;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = callStatic(at, "currentApplication");
            if (app instanceof Context) {
                sCtx = (Context) app;
                return sCtx;
            }
            Object thread = callStatic(at, "currentActivityThread");
            if (thread != null) {
                Object a2 = call(thread, "getApplication");
                if (a2 instanceof Context) {
                    sCtx = (Context) a2;
                    return sCtx;
                }
            }
        } catch (Throwable ignored) {
        }
        // 兜底：画报自己的静态单例
        try {
            Class<?> k = Class.forName("com.mfashiongallery.emag.MiFGBaseStaticInfo", false, cl());
            Object inst = callStatic(k, "getInstance");
            if (inst != null) {
                Object a = call(inst, "getAppContext");
                if (a instanceof Context) {
                    sCtx = (Context) a;
                    return sCtx;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // =====================================================================
    // 配置（默认值 + 设置页即时生效）
    // =====================================================================

    static final class Cfg {
        boolean enabled = true;
        String dir = DEF_DIR;
        boolean strict;
        int cap = DEF_CAP;
        /** 加载顺序：seq（顺序，最新在前）/ desc（倒序）/ random（随机） */
        String order = Prefs.ORDER_SEQ;
        /** 随机顺序的种子 */
        long seed;
        /**
         * 右滑进度重置序号：用户改顺序/重新随机时 +1。
         * 画报进程对比到变化就把 left_lks 的 show_t 清零，让新一轮从第一张播起。
         */
        long resetSeq;
        /** 调试模式（设置页开关，经侧车文件 / 远程偏好下发；默认关） */
        boolean debug;
        /** 禁用推广（默认开）：锁屏画报广告不请求、不进轮播池、不展示 */
        boolean blockAd = true;
        /** 本地照片替换（默认关）：换图时用同步目录里的随机照片顶替远程图片 */
        boolean localPhoto;
        /**
         * 精简精选壁纸：只保留这些订阅分类（scrb_items1.t_id）的图片。
         * 空串 = 不过滤；逗号分隔的 t_id 白名单。
         */
        String curated = "";
        /**
         * 配置生成时间戳（毫秒，由设置页写入）。
         *
         * <p>多通道并存时用它决定「谁是最新」：Settings.System 公共键与推送缓存
         * 都带 ts，取较大者，避免旧副本（进程重启前的推送、或过期的 remote 快照）
         * 覆盖用户刚做的改动。0 表示该通道没有时间戳（老格式）。</p>
         */
        long ts;
    }

    /**
     * 解析当前配置。<b>只有一个来源</b>：CfgProvider（模块 app 的真源实时值）。
     *
     * <p>画报进程拿不到模块 prefs，也不再保留任何本进程快照。多源并存时「谁是最新」
     * 无从判断，曾直接导致「设置里 cap=10，实际只加进去 8 张」这类不一致。
     * provider 查不到就退回内置默认值。</p>
     *
     * <p>provider 由模块 app 进程应答：它没在运行时，一次查询会把模块 app 拉起
     * （冷启动几百毫秒，之后就一直可用），所以同步入口多等这一下是可接受的。</p>
     */
    static Cfg cfg(Context ctx) {
        Cfg c = loadProviderCfg(ctx);
        String src = Prefs.lastCfgChannel();
        if (c == null) {
            c = new Cfg();
            src = "default";
        }
        if (c.cap <= 0 || c.cap > 1000) {
            c.cap = DEF_CAP;
        }
        c.dir = normDir(c.dir);
        sCfgSrc = src;
        sLastCap = c.cap;
        sBlockAd = c.blockAd;
        sLocalPhoto = c.localPhoto;
        sPhotoDir = c.dir;
        sDebugHint = c.debug;
        return c;
    }

    /** 每次同步都重新读一遍配置（缓存文件很小，避免进程内长期缓存导致「改了不生效」） */
    /** 归一化加载顺序取值 */
    private static String normOrder(String v) {
        if (Prefs.ORDER_DESC.equals(v) || Prefs.ORDER_RANDOM.equals(v)) {
            return v;
        }
        return Prefs.ORDER_SEQ;
    }

    /**
     * 「内容指纹」：配置 + 候选文件（路径 / 大小 / mtime）的有序摘要。
     *
     * <p>只用来回答「这次同步有没有活要干」，不参与业务语义。
     * 背景：{@code load()} 是被 hook 在 Fragment.onCreate / presenter.load 上同步执行的，
     * 而同步里包含 snapshotManaged / pruneManaged / enforceOrder 三次 ContentResolver 往返
     * 和一次 managed 落盘；图片没变时这些全是空转（实测 500ms 量级），
     * 用户感知就是「打开自选图片页卡一下」。
     */
    private static String fpOf(Cfg cfg, LinkedHashMap<String, File> cand) {
        long h = 1125899906842597L;
        h = h * 31 + (cfg.dir == null ? 0 : cfg.dir.hashCode());
        h = h * 31 + cfg.cap;
        h = h * 31 + (cfg.order == null ? 0 : cfg.order.hashCode());
        h = h * 31 + (cfg.strict ? 1 : 0);
        h = h * 31 + cfg.seed;
        // 路径升序后逐个入摘要：与扫描顺序无关，且只看文件元数据（不读文件内容）
        List<String> ps = new ArrayList<String>(cand.keySet());
        Collections.sort(ps);
        for (int i = 0; i < ps.size(); i++) {
            String p = ps.get(i);
            File f = cand.get(p);
            h = h * 31 + p.hashCode();
            if (f != null) {
                h = h * 31 + f.length();
                h = h * 31 + f.lastModified();
            }
        }
        return cfg.dir + '#' + cfg.cap + '#' + cfg.order + '#' + (cfg.strict ? 1 : 0)
                + '#' + cand.size() + '#' + Long.toHexString(h);
    }

    private static Cfg refreshCfg(Context ctx) {
        Cfg c = cfg(ctx);
        sCfg = c;
        sCfgAt = SystemClock.uptimeMillis();
        sCfgGen = Prefs.cfgGen();
        return c;
    }

    private static void invalidateCfg() {
        sCfg = null;
        sCfgAt = 0;
    }

    // ---------------- 配置缓存 / 接管记录（都在画报自己的 data 目录） ----------------

    private static File dataDir(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            File d = new File(ctx.getFilesDir(), CACHE_SUBDIR);
            if (!d.isDirectory()) {
                d.mkdirs();
            }
            return d.isDirectory() ? d : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File managedFile(Context ctx) {
        File d = dataDir(ctx);
        return d == null ? null : new File(d, MANAGED_FILE);
    }

    /** 读接管记录：prefix="p=" 是插入过的路径，"d=" 是同步过的目录 */
    private static Set<String> readManaged(Context ctx, String prefix) {
        Set<String> set = new HashSet<String>();
        File f = managedFile(ctx);
        if (f == null || !f.isFile()) {
            return set;
        }
        String text = readText(f);
        if (text == null) {
            return set;
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith(prefix)) {
                String v = line.substring(prefix.length()).trim();
                if (v.length() > 0) {
                    set.add(v);
                }
            }
        }
        return set;
    }

    private static void updateManaged(Context ctx, Set<String> add, Set<String> remove, String dir) {
        try {
            Set<String> paths = readManaged(ctx, "p=");
            Set<String> dirs = readManaged(ctx, "d=");
            if (add != null) {
                paths.addAll(add);
            }
            if (remove != null) {
                paths.removeAll(remove);
            }
            if (dir != null && dir.length() > 0) {
                dirs.add(dir);
            }
            StringBuilder sb = new StringBuilder("# 本模块插入过的自选图片路径 / 曾同步过的目录"
                    + "（用于只清理自己加的行，不动用户手动添加的）\n");
            int n = 0;
            for (String d : dirs) {
                sb.append("d=").append(d).append('\n');
                if (++n > 40) {
                    break;
                }
            }
            n = 0;
            for (String p : paths) {
                sb.append("p=").append(p).append('\n');
                if (++n > 3000) {
                    break;
                }
            }
            File f = managedFile(ctx);
            if (f == null) {
                return;
            }
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable t) {
            verbose("managed save failed: " + t);
        }
    }

    /**
     * 读完整画报配置（唯一通道：CfgProvider）。
     *
     * <p>{@link Prefs#readCfgText} 是纯 Binder 查询，任何进程可调、不受分区存储限制，
     * 拿到的是设置页最后一次写入的真源值。失败返回 null，由调用方退回默认值。</p>
     */
    private static Cfg loadProviderCfg(Context ctx) {
        String text;
        try {
            text = Prefs.readCrossProcCfg(ctx);
        } catch (Throwable t) {
            return null;
        }
        if (text == null || text.length() == 0) {
            return null;
        }
        Cfg c = new Cfg();
        boolean any = false;
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (Prefs.SIDECAR_KEY_FG_DIR.equals(k) && v.length() > 0) {
                c.dir = normDir(v);
                any = true;
            } else if (Prefs.SIDECAR_KEY_FG_CAP.equals(k)) {
                try {
                    c.cap = Integer.parseInt(v);
                } catch (Throwable ignored) {
                }
                any = true;
            } else if (Prefs.SIDECAR_KEY_FG_ENABLED.equals(k)) {
                c.enabled = "1".equals(v) || "true".equalsIgnoreCase(v);
                any = true;
            } else if (Prefs.SIDECAR_KEY_FG_STRICT.equals(k)) {
                c.strict = "1".equals(v) || "true".equalsIgnoreCase(v);
                any = true;
            } else if (Prefs.SIDECAR_KEY_FG_ORDER.equals(k)) {
                c.order = normOrder(v);
                any = true;
            } else if (Prefs.SIDECAR_KEY_FG_SEED.equals(k)) {
                try {
                    c.seed = Long.parseLong(v);
                } catch (Throwable ignored) {
                }
                any = true;
            } else if (Prefs.SIDECAR_KEY_FG_RESET_SEQ.equals(k)) {
                try {
                    c.resetSeq = Long.parseLong(v);
                } catch (Throwable ignored) {
                }
                any = true;
            } else if (Prefs.SIDECAR_KEY_FG_BLOCK_AD.equals(k)) {
                c.blockAd = "1".equals(v) || "true".equalsIgnoreCase(v);
                any = true;
            } else if (Prefs.SIDECAR_KEY_FG_LOCAL_PHOTO.equals(k)) {
                c.localPhoto = "1".equals(v) || "true".equalsIgnoreCase(v);
                any = true;
            } else if (Prefs.SIDECAR_KEY_FG_CURATED.equals(k)) {
                c.curated = v;
                any = true;
            } else if (Prefs.SIDECAR_KEY_DEBUG.equals(k)) {
                c.debug = "1".equals(v) || "true".equalsIgnoreCase(v);
                sDebugHint = c.debug;
                any = true;
            } else if ("ts".equals(k)) {
                try {
                    c.ts = Long.parseLong(v);
                } catch (Throwable ignored) {
                }
            }
        }
        if (!any) {
            return null;
        }
        verbose("system cfg ok: ts=" + c.ts + " cap=" + c.cap + " dir=" + c.dir);
        return c;
    }

    /** 诊断：打印画报进程对几个关键共享路径的访问能力（只在 verbose 打开时输出） */
    private static void probeSharedPaths() {
        // 跨进程配置现在只走 Settings.System 公共键，这里顺带打印它的可读性与长度，
        // 排查「改了设置画报没反应」时先看这一行： / 0 = 模块侧没写进去。
        try {
            String cfgText = Prefs.readCrossProcCfg(sCtx);
            verbose("probe cross-proc cfg readable=" + (cfgText != null)
                    + " len=" + (cfgText == null ? 0 : cfgText.length()));
        } catch (Throwable t) {
            verbose("probe system cfg failed: " + t);
        }
        String[] paths = {
                "/sdcard/Download",
                "/sdcard/Pictures",
        };
        for (int i = 0; i < paths.length; i++) {
            try {
                File f = new File(paths[i]);
                String extra = "";
                if (f.isDirectory()) {
                    String[] ls = f.list();
                    extra = " list=" + (ls == null ? "" : String.valueOf(ls.length));
                }
                verbose("probe " + paths[i] + " exists=" + f.exists() + " dir=" + f.isDirectory()
                        + " read=" + f.canRead() + " len=" + f.length() + extra);
            } catch (Throwable t) {
                verbose("probe " + paths[i] + " failed: " + t);
            }
        }
    }

    static String normDir(String d) {
        if (d == null || d.length() == 0) {
            return DEF_DIR;
        }
        String s = d.trim();
        if (s.startsWith("/storage/emulated/0/")) {
            // 统一成 /sdcard 的等价规范形式，避免同一目录两种写法导致去重失败
            s = s;
        }
        if (!s.endsWith("/")) {
            s = s + "/";
        }
        return s;
    }

    private static String readText(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(8192, f.length() + 1)];
            int n = in.read(buf);
            return n <= 0 ? null : new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    static boolean isSupported(String name) {
        String e = ext(name);
        for (int i = 0; i < EXTS.length; i++) {
            if (EXTS[i].equals(e)) {
                return true;
            }
        }
        return false;
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static void close(Cursor c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 进程内自检：只基于画报进程自己看得到的信息判断。
     *
     * <p>Android 13+ 分区存储下 dir.list() 只返回「已被 MediaStore 索引」的条目，
     * 所以 listed 很小并不代表目录真的只有这么几张图。真正的「目录真实文件数 vs
     * 索引数」对比必须由设置页（有 root）来做，这里只如实上报可见性与权限状态。</p>
     */
    private static String diagNeed(int cand) {
        try {
            Context c = sCtx;
            if (c != null) {
                boolean permOk = c.checkSelfPermission("android.permission.READ_MEDIA_IMAGES")
                        == PackageManager.PERMISSION_GRANTED;
                boolean allFiles = Build.VERSION.SDK_INT >= 30
                        && Environment.isExternalStorageManager();
                if (!permOk && !allFiles) {
                    return "perm";
                }
            }
        } catch (Throwable ignored) {
        }
        if (sLastListed == 0) {
            return "empty";
        }
        if (cand == 0) {
            return "index";
        }
        // 自选壁纸注入模式下池子不该有网络图；残留 o_pic 说明清理没跟上。
        // 接管右滑轮播时不碰 lks_wp，池里有 o_pic 属正常，不能报诊断错误。
        if (!sLocalPhoto) {
            String pool = sLastPool;
            if (pool.length() > 0 && !pool.contains("o_pic:0")) {
                return "pool";
            }
        }
        return "ok";
    }

    /** 统计壁纸池各 type 数量，形如 "l_pic:22 p_pic:8 o_pic:0"。 */
    private static String poolStat() {
        Cursor c = null;
        try {
            Context ctx = sCtx;
            if (ctx == null) {
                return "";
            }
            c = ctx.getContentResolver().query(wallpaperUri(cl()),
                    new String[]{"type"}, null, null, null);
            if (c == null) {
                return "";
            }
            int nL = 0;
            int nP = 0;
            int nO = 0;
            int nOther = 0;
            while (c.moveToNext()) {
                String t = c.getString(0);
                if (TYPE_PIC.equals(t)) {
                    nL++;
                } else if ("p_pic".equals(t)) {
                    nP++;
                } else if ("o_pic".equals(t)) {
                    nO++;
                } else {
                    nOther++;
                }
            }
            String s = "l_pic:" + nL + " p_pic:" + nP + " o_pic:" + nO;
            if (nOther > 0) {
                s += " other:" + nOther;
            }
            return s;
        } catch (Throwable t) {
            return "";
        } finally {
            close(c);
        }
    }

    private static String permLine(File dir) {
        StringBuilder sb = new StringBuilder();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                sb.append("allFiles=").append(Environment.isExternalStorageManager());
            } else {
                sb.append("allFiles=n/a");
            }
        } catch (Throwable t) {
            sb.append("allFiles=?");
        }
        try {
            Context c = sCtx;
            if (c != null) {
                sb.append(" readMediaImages=")
                        .append(c.checkSelfPermission("android.permission.READ_MEDIA_IMAGES")
                                == PackageManager.PERMISSION_GRANTED);
            }
        } catch (Throwable ignored) {
        }
        try {
            sb.append(" dirExists=").append(dir != null && dir.isDirectory());
            sb.append(" dirReadable=").append(dir != null && dir.canRead());
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static Object callStatic(Class<?> k, String name) throws Exception {
        Method m = k.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(null);
    }

    private static Object call(Object target, String name) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /** 配置里读到的调试开关（由 loadProviderCfg 顺手更新，避免递归读取） */
    private static volatile boolean sDebugHint = false;
    private static volatile boolean sDebugFlag = false;
    private static volatile long sDebugAt = 0L;

    /**
     * 调试模式是否开启（默认关）。
     *
     * 关：只输出 WARN / ERROR，INFO / DEBUG 全部静默（用户要求「默认不输出调试信息」）。
     * 开：全量日志，便于排障。
     *
     * 取值来源：侧车文件 debug= 行（最可靠）-> 模块偏好 / 标记文件 fg.debug。
     * 结果缓存 3 秒：hook 热路径上每次拦截都会调到这里，不能每次都做文件 IO。
     */
    private static boolean isDebugNow() {
        long now = SystemClock.uptimeMillis();
        if (sDebugAt != 0L && now - sDebugAt < 3000L) {
            return sDebugFlag;
        }
        boolean d = sDebugHint;
        if (!d) {
            try {
                d = Prefs.isFgDebug(sCtx);
            } catch (Throwable ignored) {
            }
        }
        sDebugFlag = d;
        sDebugAt = now;
        return d;
    }

    private static void log(int prio, String tag, String msg) {
        try {
            if (prio < Log.WARN && !isDebugNow()) {
                return;
            }
            Log.println(prio, tag, msg);
        } catch (Throwable ignored) {
        }
    }

    private static void log(String msg) {
        log(Log.INFO, TAG, msg);
    }

    private static void log(int prio, String tag, String msg, Throwable t) {
        log(prio, tag, msg + ": " + t);
    }

    private static void verbose(String msg) {
        try {
            if (isDebugNow()) {
                Log.println(Log.DEBUG, TAG, msg);
            }
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // FileProvider 根目录注入
    //
    // 画报的 file_paths.xml 只有 files-path / external-files-path，
    // 没有任何 /sdcard 根，所以把它配置目录里的文件转 content:// 时会抛
    // 「Failed to find configured root」。模板手册的做法是改 xml；
    // 这里改成运行期把根塞进 PathStrategy，不动 APK。
    // =====================================================================

    private static final String ROOT_NAME = "mifg_sync";

    private static void patchFileProvider(ClassLoader cl) throws Throwable {
        Class<?> fp = Class.forName("androidx.core.content.FileProvider", false, cl);
        sFpClass = fp;
        StringBuilder info = new StringBuilder();

        // 1) attachInfo 之后：provider 刚建好，直接注入它的 mStrategy
        try {
            Method attach = fp.getDeclaredMethod("attachInfo", Context.class, ProviderInfo.class);
            attach.setAccessible(true);
            sMod.hook(attach).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try {
                        injectIntoProvider(chain.getThisObject());
                    } catch (Throwable t) {
                        verbose("attach inject failed: " + t);
                    }
                    return r;
                }
            });
            info.append("attach ");
        } catch (Throwable t) {
            verbose("attachInfo hook miss: " + t);
        }

        // 2) getUriForFile 之前：兜底（provider 可能早于模块挂载完成 attach）；
        //    万一还是没命中根，注入后重试一次。
        int n = 0;
        try {
            Method[] ms = fp.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                final Method m = ms[i];
                if (!"getUriForFile".equals(m.getName())
                        || !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 3 || ps[0] != Context.class
                        || ps[1] != String.class || ps[2] != File.class) {
                    continue;
                }
                m.setAccessible(true);
                sMod.hook(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        Object f = chain.getArg(2);
                        try {
                            if (f instanceof File) {
                                ensureRoots((File) f);
                            }
                            return chain.proceed();
                        } catch (Throwable t) {
                            // 失败多半是「strategy 是本次调用才建的，根还没注入」
                            try {
                                if (f instanceof File) {
                                    ensureRoots((File) f);
                                }
                                return chain.proceed();
                            } catch (Throwable t2) {
                                throw t;
                            }
                        }
                    }
                });
                n++;
            }
        } catch (Throwable t) {
            verbose("getUriForFile hook miss: " + t);
        }
        info.append("getUri=").append(n);
        sFpInfo = info.toString().trim();
    }

    /** 只在「文件确实位于配置目录」时才动 strategy，其他文件一律不碰 */
    private static void ensureRoots(File f) {
        if (f == null || sFpClass == null) {
            return;
        }
        Cfg cfg = currentCfg();
        if (cfg == null) {
            return;
        }
        String path = canon(f);
        if (!path.startsWith(canon(new File(cfg.dir)))) {
            return;
        }
        try {
            Field fc = findField(sFpClass, "sCache");
            fc.setAccessible(true);
            Object v = fc.get(null);
            if (v instanceof Map) {
                for (Object st : ((Map<?, ?>) v).values()) {
                    injectIntoStrategy(st, cfg);
                }
            }
        } catch (Throwable t) {
            verbose("sCache inject failed: " + t);
        }
    }

    private static void injectIntoProvider(Object provider) {
        if (provider == null) {
            return;
        }
        Cfg cfg = currentCfg();
        if (cfg == null) {
            return;
        }
        try {
            // 字段解析结果（含负缓存）已缓存，这里不再每次 setAccessible
            Field f = findField(provider.getClass(), "mStrategy");
            injectIntoStrategy(f.get(provider), cfg);
        } catch (Throwable t) {
            verbose("mStrategy inject failed: " + t);
        }
    }

    private static void injectIntoStrategy(Object strategy, Cfg cfg) {
        if (strategy == null || cfg == null) {
            return;
        }
        try {
            Map<?, ?> roots = findRootsMap(strategy);
            if (roots == null) {
                verbose("mRoots not found in " + strategy.getClass().getName());
                return;
            }
            File canonical = canonicalDir(cfg.dir);
            // 已经注入过同一个 root：getUriForFile 会被每张图调用，这里直接返回，
            // 跳过 put/日志；与旧实现「first==false」分支完全等价（不置 sFpRootInjected、不打日志）。
            if (canonical.equals(roots.get(ROOT_NAME))) {
                return;
            }
            ((Map<Object, Object>) roots).put(ROOT_NAME, canonical);
            sFpRootInjected = true;
            log(Log.INFO, TAG, "FileProvider root +" + ROOT_NAME + "=" + canonical
                    + " roots=" + roots.size());
        } catch (Throwable t) {
            verbose("injectIntoStrategy failed: " + t);
        }
    }

    /**
     * 解析目标目录的 canonical File，并保证目录已创建。结果按目录字符串缓存。
     *
     * <p>原实现在每次注入时都要走 {@code new File} + {@code isDirectory()} +
     * {@code getCanonicalFile()} + {@code exists()}（必要时再 {@code mkdirs()}）——
     * 而注入点是每次 {@code getUriForFile}。缓存后热路径只剩 1 次 stat，
     * 且目录被删掉时仍会重新创建（缓存命中时用 {@code isDirectory()} 校验）。</p>
     */
    private static File canonicalDir(String dir) {
        File hit = sCanonCache.get(dir);
        if (hit != null && hit.isDirectory()) {
            return hit;
        }
        File root = new File(dir);
        if (!root.isDirectory()) {
            root.mkdirs();
        }
        File canonical;
        try {
            canonical = root.getCanonicalFile();
        } catch (Throwable t) {
            canonical = root;
        }
        if (!canonical.exists()) {
            canonical.mkdirs();
        }
        sCanonCache.put(dir, canonical);
        return canonical;
    }

    /** 目录字符串 -> canonical File（命中时仍会校验目录存在） */
    private static final Map<String, File> sCanonCache = new ConcurrentHashMap<String, File>();

    private static Map<?, ?> findRootsMap(Object strategy) throws Exception {
        Field f = rootsFieldOf(strategy.getClass());
        if (f == null) {
            return null;
        }
        Object v = f.get(strategy);
        if (v instanceof Map) {
            return (Map<?, ?>) v;
        }
        // 字段存在但值不是 Map：退回逐字段扫描，与原实现同语义（罕见分支）
        return scanMapField(strategy);
    }

    /** 逐字段扫描第一个 Map 类型字段（原 findRootsMap 的兜底分支） */
    private static Map<?, ?> scanMapField(Object strategy) {
        for (Field ff : strategy.getClass().getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(ff.getType())) {
                continue;
            }
            try {
                ff.setAccessible(true);
                Object v = ff.get(strategy);
                if (v instanceof Map) {
                    return (Map<?, ?>) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * strategy 类 -> 存放 root 映射的字段，按类缓存。
     *
     * <p>{@code mRoots} 拿不到时才走「第一个 Map 字段」的类型兜底扫描，
     * 该扫描结果同样会缓存，避免把 {@code getDeclaredFields()} 放进热路径。</p>
     */
    private static final Map<Class<?>, Object> sRootsFieldCache = new ConcurrentHashMap<Class<?>, Object>();

    private static Field rootsFieldOf(Class<?> k) {
        Object hit = sRootsFieldCache.get(k);
        if (hit != null) {
            return hit == FIELD_ABSENT ? null : (Field) hit;
        }
        Field f = findFieldOrNull(k, "mRoots");
        if (f == null) {
            for (Field ff : k.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(ff.getType())) {
                    continue;
                }
                try {
                    ff.setAccessible(true);
                    f = ff;
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        sRootsFieldCache.put(k, f == null ? FIELD_ABSENT : f);
        return f;
    }

    private static final Object FIELD_ABSENT = new Object();

    /** 反射字段缓存：类(身份) -> 字段名 -> Field / 负缓存 */
    private static final Map<Class<?>, Map<String, Object>> sFieldCache =
            new ConcurrentHashMap<Class<?>, Map<String, Object>>();

    /** 沿继承链解析字段并缓存（含负缓存）；找不到返回 null，找到则已 setAccessible */
    private static Field findFieldOrNull(Class<?> k, String name) {
        Map<String, Object> m = sFieldCache.get(k);
        if (m == null) {
            Map<String, Object> fresh = new ConcurrentHashMap<String, Object>();
            Map<String, Object> prev = sFieldCache.putIfAbsent(k, fresh);
            m = prev != null ? prev : fresh;
        }
        Object hit = m.get(name);
        if (hit != null) {
            return hit == FIELD_ABSENT ? null : (Field) hit;
        }
        Field found = null;
        for (Class<?> c = k; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                found = f;
                break;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable other) {
                break;
            }
        }
        m.put(name, found == null ? FIELD_ABSENT : found);
        return found;
    }

    private static Field findField(Class<?> k, String name) throws NoSuchFieldException {
        Field f = findFieldOrNull(k, name);
        if (f == null) {
            throw new NoSuchFieldException(name + " in " + k.getName());
        }
        return f;
    }

    private static String canon(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Throwable t) {
            return f.getAbsolutePath();
        }
    }

    private static Cfg currentCfg() {
        Cfg cfg = sCfg;
        long now = SystemClock.uptimeMillis();
        // 加 TTL：getUriForFile 会被每张图调用，不能每次都读文件；
        // 但也不能像以前那样永久缓存（改了目录会对不上）
        if (cfg != null && now - sCfgAt < CFG_TTL_MS && sCfgGen == Prefs.cfgGen()) {
            return cfg;
        }
        Context c = sCtx != null ? sCtx : appCtx();
        if (c == null) {
            return cfg;
        }
        cfg = cfg(c);
        sCfg = cfg;
        sCfgAt = now;
        sCfgGen = Prefs.cfgGen();
        return cfg;
    }

    // =====================================================================
    // 状态落盘（设置页读取展示）
    // =====================================================================

    static void writeStatus() {
        sStatusAt = SystemClock.uptimeMillis();
        String text = "# 小米画报目录同步状态\n"
                + "result=" + sLastResult + "\n"
                + "dir=" + sLastDir + "\n"
                + "cap=" + sLastCap + "\n"
                + "order=" + sLastOrder + "\n"
                + "cfg=" + sCfgSrc + "\n"
                + "cand=" + sLastCand + "\n"
                + "listed=" + sLastListed + "\n"
                + "pool=" + sLastPool + "\n"
                + "feed=lks:" + leftLksCount(sCtx)
                + " wrote=" + sLastLeftLks + "\n"
                + "need=" + sLastNeed + "\n"
                + "hooks=" + sHookInfo + "\n"
                + "ad=" + (sBlockAd ? "on" : "off") + " adhooks=" + sAdHooks
                + " adhits=" + sAdHits.get()
                + " adlast=" + (sAdLastAt == 0 ? "-" : String.valueOf(sAdLastAt)) + "\n"
                + "fileprovider=" + sFpInfo + (sFpRootInjected ? "+root" : "") + "\n"
                + "perm=" + sPermInfo + "\n"
                + "added=" + sLastAdded + " pruned=" + sLastPruned + " kept=" + sLastSkipped
                + " purge=" + sLastPurged + "/" + sLastPurgeScan + "\n"
                + "cost=" + sLastCostMs + "ms\n"
                + "thumb=" + sLastThumb + "\n"
                + "dedupe=" + sLastDedupe + "\n"
                + "selfheal=" + (sLastPrivWiped ? "wiped" : "-")
                + " lazy=" + sLazyCopyCount + "\n"
                + "curated=" + (sCfg == null || sCfg.curated == null ? "" : sCfg.curated)
                + " curatedpruned=" + sCuratedPruned + "\n"
                + "cats=" + (sCats == null ? "" : sCats) + "\n"
                + "time=" + new java.util.Date() + "\n";
        String[] paths = statusPaths();
        for (int i = 0; i < paths.length; i++) {
            try {
                File f = new File(paths[i]);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(text.getBytes("UTF-8"));
                fos.flush();
                fos.close();
            } catch (Throwable ignored) {
            }
        }
        // 画报进程写不了本模块的 media 目录，状态用广播回传给设置页
        StatusReceiver.send(sCtx, StatusReceiver.SRC_FG, text);
    }
}
