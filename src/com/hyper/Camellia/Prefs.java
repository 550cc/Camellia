package com.hyper.Camellia;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模块配置。
 *
 * 重要：本类的常量会被 Main（运行在**小米互传进程**里）以编译期常量方式内联引用，
 * 用于通过 XposedModule#getRemotePreferences(GROUP) 跨进程读取设置。
 * 因此 GROUP / KEY_* / MT_* 的值一旦发布就不要再改。
 *
 * 注意：Main 运行在目标进程，**不能**使用 Prefs.get(ctx)（那会读到小米互传自己的
 * SharedPreferences 而不是模块的）。
 *
 * 兜底：部分 LSPosed 版本 / 场景下 getRemotePreferences 不可用（返回 null 或空），
 * 因此每次保存设置都会把关键配置**同时写一份纯文本侧车文件**到本模块的外部目录，
 * Main 在读不到 remote prefs 时会直接读这些文件。
 */
public final class Prefs {

    // ───────────────────── 后台 IO 队列（性能） ─────────────────────

    /**
     * 侧车写入 / 配置下发 / 诊断落盘统一走这条单线程队列。
     *
     * <p>为什么：侧车文件在 {@code /sdcard}（FUSE）上，单次写入约 10~50ms，
     * 早期实现在设置页主线程同步做，于是「每点一下开关都卡一下」。
     * 单线程保证顺序（先写侧车、再下发），线程为 daemon，不阻塞进程退出。</p>
     */
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "camellia-prefs-io");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        }
    });

    /** 提交一个后台任务；队列不可用时退化为当前线程执行（不丢写入） */
    static void bg(Runnable r) {
        try {
            BG.execute(r);
        } catch (Throwable t) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    // ───────────────────── 调试模式判定缓存 ─────────────────────

    /** debug 标记文件（目标进程也可读，用于不装设置页就开日志） */
    private static final String[] DEBUG_FLAG_PATHS = new String[]{
            "/sdcard/Android/media/com.hyper.Camellia/fg.debug",
            "/storage/emulated/0/Android/media/com.hyper.Camellia/fg.debug"};

    /** 判定结果缓存：/sdcard 上 stat 一次要几毫秒，而 logApp 每次调用都要判一次 */
    private static final long DEBUG_TTL_MS = 2000L;
    private static volatile boolean sDebugCached = false;
    private static volatile long sDebugAt = -DEBUG_TTL_MS;

    /** 设置页切换 debug 时立即生效，同时让缓存立刻反映新值 */
    static void setDebugCache(boolean on) {
        sDebugCached = on;
        sDebugAt = SystemClock.uptimeMillis();
    }

    /** SharedPreferences 组名，必须与目标进程 getRemotePreferences(...) 一致 */
    public static final String GROUP = "settings";

    public static final String KEY_DEBUG = "ui_debug_mode";
    public static final String KEY_HIDE_ICON = "hide_icon";

    /** 首次启动引导是否已完成（用户协议 + 授权检查） */
    public static final String KEY_GUIDE_DONE = "guide_done";
    public static final String KEY_MT_TARGET = "mt_target";

    // ---- 模块自身外观（只影响本设置页，目标进程不读这些键）----
    /** 主题模式：跟随系统 / 浅色 / 深色 */
    public static final String KEY_THEME_MODE = "theme_mode";
    /** 底栏样式：HyperOS 底栏 / HyperOS 悬浮底栏 / 液态玻璃底栏 */
    public static final String KEY_BOTTOM_BAR = "bottom_bar_style";

    /** 主题模式取值（KEY_THEME_MODE） */
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    /** 底栏样式取值（KEY_BOTTOM_BAR） */
    public static final String BAR_HYPEROS = "hyperos";
    public static final String BAR_FLOATING = "floating";
    public static final String BAR_GLASS = "glass";
    public static final String KEY_TOP_BAR_STYLE = "top_bar_style";
    public static final String TOP_CLASSIC = "tclassic";
    public static final String TOP_ROUND = "tround";
    public static final String TOP_GLASS = "tglass";
    public static String getTopBarStyle(Context c){ String v=get(c).getString(KEY_TOP_BAR_STYLE, TOP_CLASSIC);
        return TOP_ROUND.equals(v)?TOP_ROUND:(TOP_GLASS.equals(v)?TOP_GLASS:TOP_CLASSIC); }
    public static void setTopBarStyle(Context c, String v){ get(c).edit().putString(KEY_TOP_BAR_STYLE, v).apply(); }

    // ---- 主题设置：Monet取色 / 深色纯黑 / 模糊强度 / 界面缩放 ----
    public static final String KEY_THEME_MONET = "theme_monet";
    public static final String KEY_THEME_PURE_BLACK = "theme_pure_black";
    public static final String KEY_GLASS_BLUR = "glass_blur";
    public static final String KEY_UI_SCALE = "ui_scale";
    public static final String KEY_SHOW_HINTS = "show_hints";
    public static final String KEY_THEME_STYLE = "theme_style";
    public static final String STYLE_MIUIX = "miuix";
    public static final String STYLE_MATERIAL = "material3";
    public static final String KEY_UI_STYLE = "ui_style";
    public static final String UI_MIUIX = "miuix";
    public static final String UI_MATERIAL = "material3";
    public static String getUiStyle(Context c){ String v=get(c).getString(KEY_UI_STYLE, UI_MIUIX); return UI_MATERIAL.equals(v)?UI_MATERIAL:UI_MIUIX; }
    public static void setUiStyle(Context c, String v){ get(c).edit().putString(KEY_UI_STYLE, UI_MATERIAL.equals(v)?UI_MATERIAL:UI_MIUIX).apply(); }
    public static final String KEY_COLOR_STANDARD = "color_standard";
    public static final String STD_TONAL = "tonal";
    public static final String STD_FIDELITY = "fidelity";
    public static final String STD_VIBRANT = "vibrant";
    public static final String STD_CONTENT = "content";
    public static final String STD_MONO = "mono";
    public static final String KEY_ACCENT_COLOR = "accent_color";
    public static int getAccentColor(Context c){ return get(c).getInt(KEY_ACCENT_COLOR, 0); }
    public static void setAccentColor(Context c, int argb){ get(c).edit().putInt(KEY_ACCENT_COLOR, argb).apply(); }
    public static String getColorStandard(Context c){ String v=get(c).getString(KEY_COLOR_STANDARD, "TonalSpot"); java.util.List<String> ok=java.util.Arrays.asList("TonalSpot","Neutral","Vibrant","Expressive","Rainbow","FruitSalad","Monochrome","Fidelity","Content"); return ok.contains(v)?v:"TonalSpot"; }
    public static void setColorStandard(Context c, String v){ get(c).edit().putString(KEY_COLOR_STANDARD, v).apply(); }
    public static String getThemeStyle(Context c){ String v=get(c).getString(KEY_THEME_STYLE, "2021"); return "2025".equals(v)?"2025":"2021"; }
    public static void setThemeStyle(Context c, String v){ get(c).edit().putString(KEY_THEME_STYLE, "2025".equals(v)?"2025":"2021").apply(); }
    public static boolean getShowHints(Context c){ return get(c).getBoolean(KEY_SHOW_HINTS, false); }
    public static void setShowHints(Context c, boolean v){ get(c).edit().putBoolean(KEY_SHOW_HINTS, v).apply(); }
    public static boolean getThemeMonet(Context c){ return get(c).getBoolean(KEY_THEME_MONET, false); }
    public static void setThemeMonet(Context c, boolean v){ get(c).edit().putBoolean(KEY_THEME_MONET, v).apply(); }
    public static boolean getPureBlack(Context c){ return get(c).getBoolean(KEY_THEME_PURE_BLACK, false); }
    public static void setPureBlack(Context c, boolean v){ get(c).edit().putBoolean(KEY_THEME_PURE_BLACK, v).apply(); }
    public static int getGlassBlur(Context c){ int v=get(c).getInt(KEY_GLASS_BLUR, 2); return v<0?0:(v>2?2:v); }
    public static void setGlassBlur(Context c, int v){ get(c).edit().putInt(KEY_GLASS_BLUR, Math.max(0,Math.min(2,v))).apply(); }
    public static int getUiScale(Context c){ int v=get(c).getInt(KEY_UI_SCALE, 100); return v<85?85:(v>115?115:v); }
    public static void setUiScale(Context c, int v){ get(c).edit().putInt(KEY_UI_SCALE, Math.max(85,Math.min(115,v))).apply(); }

    // ---- 小米画报（com.mfashiongallery.emag）自选壁纸目录同步 ----
    /** 是否启用画报目录同步 */
    public static final String KEY_FG_ENABLED = "fg_enabled";
    /** 同步目录（默认 /storage/emulated/0/Pictures/小米画报/） */
    public static final String KEY_FG_DIR = "fg_dir";
    /** 严格模式：丢弃不属于当前目录的历史记录 */
    public static final String KEY_FG_STRICT = "fg_strict";
    /** 单次最多新增条数 */
    public static final String KEY_FG_CAP = "fg_cap";
    /** 详细日志开关（写死文件 fg.debug 亦可） */
    public static final String KEY_FG_DEBUG = "fg_debug";
    /** 禁用推广：锁屏画报广告不请求、不入池、不展示 */
    public static final String KEY_FG_BLOCK_AD = "fg_block_ad";
    /** 本地照片替换：锁屏/桌面画报换图时用同步目录里的随机照片顶替远程图 */
    public static final String KEY_FG_LOCAL_PHOTO = "fg_local_photo";
    /** 精简精选壁纸：逗号分隔的 scrb_items1.t_id 白名单（空串 = 不过滤） */
    public static final String KEY_FG_CURATED = "fg_curated";
    /** 环境自动修复（默认关）：检测到画报缺权限 / 图片未被系统索引时自动修复 */
    public static final String KEY_FG_AUTO_HEAL = "fg_auto_heal";

    /** MT 管理器（正式版） */
    public static final String MT_DEFAULT = "bin.mt.plus";
    /** MT 管理器 Canary（分身/测试版） */
    public static final String MT_CANARY = "bin.mt.plus.canary";

    /** 桌面图标别名，对应 AndroidManifest 里的 activity-alias */
    public static final String LAUNCHER_ALIAS = "com.hyper.Camellia.LauncherAlias";

    /** 侧车配置文件名（纯文本，key=value，每行一条） */
    public static final String SIDECAR_NAME = "misharemt2.ini";
    /** 侧车文件里的键名 */
    public static final String SIDECAR_KEY_MT = "mt_target";
    public static final String SIDECAR_KEY_FG_DIR = "fg_dir";
    public static final String SIDECAR_KEY_FG_CAP = "fg_cap";
    public static final String SIDECAR_KEY_FG_ENABLED = "fg_enabled";
    public static final String SIDECAR_KEY_FG_STRICT = "fg_strict";
    public static final String SIDECAR_KEY_FG_ORDER = "fg_order";
    public static final String SIDECAR_KEY_FG_SEED = "fg_seed";
    public static final String SIDECAR_KEY_FG_RESET_SEQ = "fg_reset_seq";
    public static final String SIDECAR_KEY_FG_BLOCK_AD = "fg_block_ad";
    public static final String SIDECAR_KEY_FG_LOCAL_PHOTO = "fg_local_photo";
    public static final String SIDECAR_KEY_FG_CURATED = "fg_curated";
    /** 调试模式（默认关：目标进程不写调试日志，设置页不显示调试明细） */
    public static final String SIDECAR_KEY_DEBUG = "debug";

    /** 侧边栏背景色（16 进制 ARGB，手机管家进程从侧车读取） */
    public static final String SIDECAR_KEY_SB_COLOR = "sb_color";

    /** 侧边栏背景模糊程度（30-99，对齐 HyperCeiler 的 blurradius 默认 60） */
    public static final String SIDECAR_KEY_SB_BLUR = "sb_blur";

    /**
     * 自动莫奈取色开关（1=用户没设过颜色时，用壁纸主色当默认值；0=始终用默认半透明黑）。
     *
     * <p>默认开启：「没设颜色」本来就走 {@link #SB_COLOR_DEF} 那个半透明黑，
     * 换成壁纸主色只会更贴合用户当前主题，而用户一旦主动选过颜色就以他选的为准。</p>
     */
    public static final String SIDECAR_KEY_SB_MONET = "sb_monet";

    /**
     * 动态取色方案（单选，默认 0）。0=图标面积主色 1=壁纸主色 2=图标最饱和
     * 3=图标平均色 4=图标最亮 5=图标提亮版。
     */
    public static final String SIDECAR_KEY_SB_MODE = "sb_mode";

    /**
     * 规则 id：「长按图标 → 快捷菜单 → 小窗」。
     *
     * <p>它不在 {@link Unlock} 规则表里（由设置页直接 {@link #setUlOn} 写入），
     * 所以不会被 {@code ulSidecarBody} 的规则循环带出去 —— 必须单独补一行，
     * 否则侧车 / 公开副本里永远没有这个键，system_server 的兜底通道就失效。</p>
     */
    public static final String ID_UL_APP_SHORTCUT = "app_shortcut_freeform";
    /**
     * 上面这条规则在侧车 / 公开副本里的键名（与 prefs 键同形：ul_ + id）。
     *
     * <p>这里写字面量而不是 {@code KEY_UL_PREFIX + ID_UL_APP_SHORTCUT}：后者虽然也是
     * 常量表达式，但 {@code KEY_UL_PREFIX} 声明在文件下方，用简单名引用会触发
     * javac 的 {@code illegal forward reference}。</p>
     */
    public static final String SIDECAR_KEY_UL_APP_SHORTCUT = "ul_app_shortcut_freeform";

    /**
     * 「桌面内自由小窗」开关（A 方案）。
     *
     * <p>语义（v151 修正）：A 的 native patch 只负责<b>放行</b>桌面自身的 freeform
     * 快捷方式过滤（{@code ValidSmallWindowShortcutMenu.isValid}），它本身不产生任何数据；
     * 快捷方式数据由 B（{@link #ID_UL_APP_SHORTCUT}）注入。两者是上下游配套关系，
     * <b>必须同时开启</b>，不存在互斥。</p>
     *
     * <p>与 {@link #ID_UL_APP_SHORTCUT} 一样不在 {@link Unlock} 规则表里（由设置页
     * 直接 {@link #setUlOn} 写入），所以必须在 {@code ulSidecarBody} 单独补一行，
     * 否则 LauncherShortcut 的兜底通道读不到它。</p>
     */
    public static final String ID_UL_LAUNCHER_FREEFORM = "launcher_freeform";
    public static final String SIDECAR_KEY_UL_LAUNCHER_FREEFORM = "ul_launcher_freeform";

    /** 小米互传「极速传输（160M）」解锁开关 id */
    public static final String ID_UL_MISHARE_TURBO = "mishare_turbo";
    /** 小米互传「苹果设备（AirDrop）互联」解锁开关 id */
    public static final String ID_UL_MISHARE_AIRDROP = "mishare_airdrop";
    public static final String SIDECAR_KEY_UL_MISHARE_TURBO = "ul_mishare_turbo";
    public static final String SIDECAR_KEY_UL_MISHARE_AIRDROP = "ul_mishare_airdrop";

    /** 小米互传包名 */
    public static final String MISHARE_PKG = "com.miui.mishare.connectivity";

    /**
     * 极速传输 / AirDrop 的最低 versionCode 门槛。
     *
     * <p>实测锚点存在性：4.0.5 / 4.13.8 的 dex 里没有 {@code isDeviceSupportTurboMode}
     * 这个字符串，极速传输是 5.x 才引入的能力；5.3.4 有极速、但没有
     * {@code AirDropAvailability}；5.4.2 两者都有。</p>
     *
     * <p>低于门槛的版本让设置页把开关置灰，避免"开了开关却毫无效果"。</p>
     */
    public static final int MISHARE_MIN_TURBO = 50000;
    public static final int MISHARE_MIN_AIRDROP = 50400;

    /**
     * A 开关的 native 侧落盘副本（v35）。
     *
     * <p>背景：A 的补丁在 {@code libhomeprobe.so} 里，由 LSPosed native 模块机制在
     * 桌面进程启动早期 dlopen。它既读不到本模块的 prefs，也没法反查
     * {@link CfgProvider}，所以「关开关」原本对补丁毫无影响 —— 表现为关掉
     * 「桌面内自由小窗」后，长按菜单里仍有图标版的「小窗」。</p>
     *
     * <p>这里把开关值落到本模块自己的 {@code Android/media} 目录：本进程可写，
     * 桌面进程可读。native 侧打补丁前只读首字节，{@code '0'} 就放弃。
     * 文件不存在时 native 按「开」处理，兼容没写过该文件的旧安装。</p>
     */
    public static final String A_SWITCH_FILE =
            "/sdcard/Android/media/com.hyper.Camellia/cam_a_switch";

    /** 画报「自选图片」加载顺序：顺序（默认，最新在前）/ 倒序（最旧在前）/ 随机 */
    public static final String ORDER_SEQ = "seq";
    public static final String ORDER_DESC = "desc";
    public static final String ORDER_RANDOM = "random";
    public static final String KEY_FG_ORDER = "fg_order";
    public static final String KEY_FG_SEED = "fg_seed";
    /**
     * 右滑轮播进度重置序号。每次用户主动改「加载顺序」或点「重新随机」都 +1，
     * 画报进程侧对比到变化就把 feedcache 里 req_tag=left_lks 的 show_t 清零，
     * 让新一轮顺序从第一张重新播起（否则老图 show_t 已非 0，画报会直接跳过）。
     */
    public static final String KEY_FG_RESET_SEQ = "fg_reset_seq";

    // ───────────────────── camd 守护进程 ─────────────────────
    /**
     * camd 已卸载标记，默认 false（= 启用）。
     *
     * <p>用户点「一键卸载 camd 组件」后置 true：此后 App 启动 / 开机广播都不再自动
     * 重装，否则卸载等于白做（{@link App#onCreate} 每次启动都会 ensureAsync）。
     * 面板上的「重新部署」会把它改回 false。</p>
     */
    public static final String KEY_CAMD_DISABLED = "camd_disabled";

    // ───────────────────── 小窗增强配置项 ─────────────────────
    /** 屏蔽系统自救（RescueParty / RescueParty+ / 12 次强制自救），默认开 */
    public static final String KEY_GUARD_ON = "sysui_guard_on";
    /** 崩溃规则引擎总开关，默认关（动作会改系统状态，必须用户显式开启） */
    public static final String KEY_RULE_ON = "sysui_rule_on";
    /** 规则统计窗口，单位秒 */
    public static final String KEY_RULE_WINDOW = "sysui_rule_window";
    /** 窗口内命中次数阈值 */
    public static final String KEY_RULE_COUNT = "sysui_rule_count";
    /** 计入的崩溃类型，逗号分隔：java,native,anr,kill */
    public static final String KEY_RULE_TYPES = "sysui_rule_types";
    /** 命中后动作：off / uninstall_update / install_apk */
    public static final String KEY_ACTION = "sysui_action";
    /** install_apk 动作使用的 APK 绝对路径 */
    public static final String KEY_ACTION_APK = "sysui_action_apk";
    /** 动作目标包名 */
    public static final String KEY_ACTION_PKG = "sysui_action_pkg";
    /** 动作冷却时间，单位秒（从动作执行完成时刻起算） */
    public static final String KEY_ACTION_COOLDOWN = "sysui_action_cooldown";

    public static final String KEY_FF_COUNT_LIMIT = "ff_count_limit";
    public static final String KEY_FF_BLACKLIST = "ff_blacklist";
    public static final String KEY_FF_PIN_FOREGROUND = "ff_pin_foreground";
    public static final String KEY_FF_CAN_SLIDE = "ff_can_slide";

    // ---------------- SystemUI 自救（v2.17 重写） ----------------

    /** 屏蔽系统自救。默认 true —— 不装任何东西也要先挡住系统把 SystemUI 判死。 */
    // ---------------- 调试模式 ----------------
    // 界面里长篇的「为什么这么做」解释只在调试模式显示。
    // 普通用户只关心「现在是什么状态、该点哪个按钮」，
    // 整页几十段原理说明会把真正的状态和按钮淹掉。

    public static boolean isDebug(Context ctx) {
        return get(ctx).getBoolean(KEY_DEBUG, false);
    }

    public static void setDebug(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_DEBUG, on).apply();
    }

    public static boolean isCamdDisabled(Context ctx) {
        return get(ctx).getBoolean(KEY_CAMD_DISABLED, false);
    }

    public static void setCamdDisabled(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_CAMD_DISABLED, on).apply();
    }

    public static boolean isGuardOn(Context ctx) {
        return get(ctx).getBoolean(KEY_GUARD_ON, true);
    }

    public static void setGuardOn(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_GUARD_ON, on).apply();
    }

    /** 崩溃规则引擎。默认 false：动作会真的改系统，必须用户显式打开。 */
    public static boolean isRuleOn(Context ctx) {
        return get(ctx).getBoolean(KEY_RULE_ON, false);
    }

    public static void setRuleOn(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_RULE_ON, on).apply();
    }

    /** 统计窗口（秒），默认 600 */
    public static int getRuleWindow(Context ctx) {
        return clampInt(get(ctx).getString(KEY_RULE_WINDOW, "600"), 600, 30, 86400);
    }

    public static void setRuleWindow(Context ctx, int v) {
        get(ctx).edit().putString(KEY_RULE_WINDOW, String.valueOf(v)).apply();
    }

    /** 窗口内命中次数阈值，默认 3 */
    public static int getRuleCount(Context ctx) {
        return clampInt(get(ctx).getString(KEY_RULE_COUNT, "3"), 3, 1, 100);
    }

    public static void setRuleCount(Context ctx, int v) {
        get(ctx).edit().putString(KEY_RULE_COUNT, String.valueOf(v)).apply();
    }

    /** 计入的崩溃类型，默认 java,native,anr（不含 kill：进程被 LMK 杀不算崩溃） */
    public static String getRuleTypes(Context ctx) {
        String v = get(ctx).getString(KEY_RULE_TYPES, "java,native,anr");
        return v == null ? "java,native,anr" : v;
    }

    public static void setRuleTypes(Context ctx, String v) {
        get(ctx).edit().putString(KEY_RULE_TYPES, v == null ? "" : v).apply();
    }

    /** 命中后动作：off / uninstall_update / install_apk，默认 off */
    public static String getAction(Context ctx) {
        String v = get(ctx).getString(KEY_ACTION, "off");
        return v == null ? "off" : v;
    }

    public static void setAction(Context ctx, String v) {
        get(ctx).edit().putString(KEY_ACTION, v == null ? "off" : v).apply();
    }

    public static String getActionApk(Context ctx) {
        String v = get(ctx).getString(KEY_ACTION_APK, "");
        return v == null ? "" : v;
    }

    public static void setActionApk(Context ctx, String v) {
        get(ctx).edit().putString(KEY_ACTION_APK, v == null ? "" : v).apply();
    }

    /** 动作目标包名，默认 com.android.systemui */
    public static String getActionPkg(Context ctx) {
        String v = get(ctx).getString(KEY_ACTION_PKG, "com.android.systemui");
        if (v == null || v.trim().isEmpty()) {
            return "com.android.systemui";
        }
        return v.trim();
    }

    public static void setActionPkg(Context ctx, String v) {
        get(ctx).edit().putString(KEY_ACTION_PKG, v == null ? "" : v).apply();
    }

    /** 动作冷却（秒），默认 30；从「动作执行完成」起算，非动作开始 */
    public static int getActionCooldown(Context ctx) {
        return clampInt(get(ctx).getString(KEY_ACTION_COOLDOWN, "30"), 30, 1, 3600);
    }

    public static void setActionCooldown(Context ctx, int v) {
        get(ctx).edit().putString(KEY_ACTION_COOLDOWN, String.valueOf(v)).apply();
    }

    // ===================== 多目标（v2.18） =====================
    // 自救对象从「只有 SystemUI」扩成多目标，每个目标各自一套计数 / 规则 / 动作：
    //   sysui  —— com.android.systemui，有独立进程，能直接监测 pid 与崩溃
    //   plugin —— miui.systemui.plugin（系统界面组件），无独立进程，加载在 SystemUI 里，
    //             只能靠崩溃正文归因 + 包状态变化来监测
    // 键名策略：sysui 沿用历史键（sysui_xxx），其它目标换成自己的前缀，老配置不失效。

    public static final String TGT_SYSUI = "sysui";
    public static final String TGT_PLUGIN = "plugin";

    public static final String PKG_SYSUI = "com.android.systemui";
    public static final String PKG_PLUGIN = "miui.systemui.plugin";

    public static String[] targets() {
        return new String[]{TGT_SYSUI, TGT_PLUGIN};
    }

    /** 目标的默认包名 */
    public static String targetPkg(String t) {
        return TGT_PLUGIN.equals(t) ? PKG_PLUGIN : PKG_SYSUI;
    }

    public static String targetLabel(String t) {
        return TGT_PLUGIN.equals(t) ? "系统界面组件" : "系统界面";
    }

    /** 目标键名映射。v2.19：规则配置改为全目标共享一套，见下方注释。 */
    /** 按目标独立的键名。
     *
     *  「恢复用安装包路径」不能像规则参数那样共享：两个目标各有自己的落地文件
     *  （sysui.apk / plugin.apk），共享一个键的结果就是后保存的那次把先前的
     *  路径覆盖掉——实测最终被写成空串，system_server 侧只能靠固定候选路径猜。
     */
    public static String tkeyPath(String t, String base) {
        String s = (t == null || t.isEmpty()) ? "sysui" : t;
        return s + "_" + base;
    }

    public static String tkey(String t, String base) {
        // v2.19 起规则配置「单套共享」：崩溃窗口、次数、类型、动作、冷却、APK
        // 全目标共用一套。原因是让用户为两个目标各配一遍，漏配的那个目标就永远
        // 不触发自救，而这种「静默不救」比不救更难排查。
        // 运行状态（计数/事件/动作日志）仍按目标隔离——那些键由
        // SysRescueGuard.kRuleState()/kRuleEvents()/kActionLog() 自行拼后缀，
        // SysUiWatch 的计数键也各自带后缀，都不走这里。
        return base;
    }

    public static boolean isRuleOn(Context ctx, String t) {
        return get(ctx).getBoolean(tkey(t, KEY_RULE_ON), false);
    }

    public static void setRuleOn(Context ctx, String t, boolean on) {
        get(ctx).edit().putBoolean(tkey(t, KEY_RULE_ON), on).apply();
    }

    public static int getRuleWindow(Context ctx, String t) {
        return clampInt(get(ctx).getString(tkey(t, KEY_RULE_WINDOW), "600"), 600, 30, 86400);
    }

    public static void setRuleWindow(Context ctx, String t, int v) {
        get(ctx).edit().putString(tkey(t, KEY_RULE_WINDOW), String.valueOf(v)).apply();
    }

    public static int getRuleCount(Context ctx, String t) {
        return clampInt(get(ctx).getString(tkey(t, KEY_RULE_COUNT), "3"), 3, 1, 100);
    }

    public static void setRuleCount(Context ctx, String t, int v) {
        get(ctx).edit().putString(tkey(t, KEY_RULE_COUNT), String.valueOf(v)).apply();
    }

    public static String getRuleTypes(Context ctx, String t) {
        String v = get(ctx).getString(tkey(t, KEY_RULE_TYPES), "java,native,anr");
        return v == null ? "java,native,anr" : v;
    }

    public static void setRuleTypes(Context ctx, String t, String v) {
        get(ctx).edit().putString(tkey(t, KEY_RULE_TYPES), v == null ? "" : v).apply();
    }

    public static String getAction(Context ctx, String t) {
        String v = get(ctx).getString(tkey(t, KEY_ACTION), "off");
        return v == null ? "off" : v;
    }

    public static void setAction(Context ctx, String t, String v) {
        get(ctx).edit().putString(tkey(t, KEY_ACTION), v == null ? "off" : v).apply();
    }

    public static String getActionApk(Context ctx, String t) {
        String v = get(ctx).getString(tkeyPath(t, KEY_ACTION_APK), "");
        return v == null ? "" : v;
    }

    public static void setActionApk(Context ctx, String t, String v) {
        get(ctx).edit().putString(tkeyPath(t, KEY_ACTION_APK), v == null ? "" : v).apply();
    }

    /** 动作目标包名，默认取该目标的固定包名 */
    public static String getActionPkg(Context ctx, String t) {
        String def = targetPkg(t);
        String v = get(ctx).getString(tkey(t, KEY_ACTION_PKG), def);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        return v.trim();
    }

    public static void setActionPkg(Context ctx, String t, String v) {
        get(ctx).edit().putString(tkey(t, KEY_ACTION_PKG), v == null ? "" : v).apply();
    }

    public static int getActionCooldown(Context ctx, String t) {
        return clampInt(get(ctx).getString(tkey(t, KEY_ACTION_COOLDOWN), "30"), 30, 1, 3600);
    }

    public static void setActionCooldown(Context ctx, String t, int v) {
        get(ctx).edit().putString(tkey(t, KEY_ACTION_COOLDOWN), String.valueOf(v)).apply();
    }

    // ===================== v2.19 分级救援阶梯 + 问题包备份 =====================
    // 一次性「卸更新」不够：卸完仍旧崩，就会一直在同一个低效动作上打转。
    // 阶梯让动作随失败自动升级，并在稳定后自行回落，避免恢复好的机器被反复卸包。

    /** 分级救援总开关：L1 卸更新 → L2 装指定 APK → L3 恢复系统界面 */
    public static final String KEY_LADDER_ON = "sysui_ladder_on";
    /** 升级判定窗口（秒）：一次动作后该时间内再崩，升一级 */
    public static final String KEY_LADDER_WINDOW = "sysui_ladder_window";
    /** 稳定回落窗口（秒）：该时间内无新崩溃，阶梯回到 L1 */
    public static final String KEY_LADDER_STABLE = "sysui_ladder_stable";
    /** L3 是否启用「恢复系统界面」（清数据 + 重新启用） */
    public static final String KEY_LADDER_L3 = "sysui_ladder_l3";
    /** 动作前备份当前安装包（MD5 去重） */
    public static final String KEY_BAK_ON = "sysui_bak_on";
    /** 备份保留份数上限 */
    public static final String KEY_BAK_KEEP = "sysui_bak_keep";
    /** 备份索引（每行：md5|包名|版本名|版本号|字节数|时间|路径） */
    public static final String KEY_BAK_LIST = "sysui_bak_list";

    public static boolean isLadderOn(Context ctx) {
        return get(ctx).getBoolean(KEY_LADDER_ON, true);
    }

    public static void setLadderOn(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_LADDER_ON, on).apply();
    }

    public static int getLadderWindow(Context ctx) {
        return clampInt(get(ctx).getString(KEY_LADDER_WINDOW, "1800"), 1800, 60, 86400);
    }

    public static void setLadderWindow(Context ctx, int v) {
        get(ctx).edit().putString(KEY_LADDER_WINDOW, String.valueOf(v)).apply();
    }

    public static int getLadderStable(Context ctx) {
        return clampInt(get(ctx).getString(KEY_LADDER_STABLE, "3600"), 3600, 300, 86400);
    }

    public static void setLadderStable(Context ctx, int v) {
        get(ctx).edit().putString(KEY_LADDER_STABLE, String.valueOf(v)).apply();
    }

    public static boolean isLadderL3(Context ctx) {
        return get(ctx).getBoolean(KEY_LADDER_L3, true);
    }

    public static void setLadderL3(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_LADDER_L3, on).apply();
    }

    // ================= 急救规则模型 v2（Incident 状态机） =================
    // v1 用「窗口 / 次数 / 冷却 / 静默清零 / 升级窗口 / 稳定回落」六个参数描述行为，
    // 但语义互相重叠：冷却期被升级窗口完全包含，静默清零与升级窗口对同一次崩溃给出
    // 相反解释（一个当「第 1 次」、一个当「升一级」）。结果是同一次崩溃既能清零计数
    // 又能触发升阶，行为不可预测，也是「反复循环恢复」的成因。
    //
    // v2 收敛为三个各管一件事的参数，状态转移见 SysRescueGuard.fire()：
    //   N 判定次数 —— 同一实物崩到第 N 次才动手（原来「次数」与「指纹门槛」两份）
    //   W 观察期   —— 动作后 W 内再崩 = 这级没治好，立刻进下一步（原来「冷却+升级窗口」）
    //   S 稳定期   —— 动作后安静 S = 真救回来了，归零（原来「稳定回落」）
    // 「静默清零」被观察期自然吸收：过了 W 还没崩就不是同一件事，计数自动从 1 重新算。

    /** 判定次数：同一指纹累计崩到第 N 次才动手 */
    public static final String KEY_INC_N = "sysui_inc_n";
    /** 观察期（秒）：动作后这段时间内再崩 = 这级没治好 → 立刻进下一步 */
    public static final String KEY_INC_WATCH = "sysui_inc_watch";
    /** 稳定期（秒）：动作后安静这么久 = 真的救回来了 → 状态归零 */
    public static final String KEY_INC_SETTLE = "sysui_inc_settle";

    public static int getIncN(Context ctx) {
        return clampInt(get(ctx).getString(KEY_INC_N, "2"), 2, 1, 10);
    }

    public static void setIncN(Context ctx, int v) {
        get(ctx).edit().putString(KEY_INC_N, String.valueOf(v)).apply();
    }

    public static int getIncWatch(Context ctx) {
        return clampInt(get(ctx).getString(KEY_INC_WATCH, "120"), 120, 30, 3600);
    }

    public static void setIncWatch(Context ctx, int v) {
        get(ctx).edit().putString(KEY_INC_WATCH, String.valueOf(v)).apply();
    }

    public static int getIncSettle(Context ctx) {
        return clampInt(get(ctx).getString(KEY_INC_SETTLE, "900"), 900, 60, 86400);
    }

    public static void setIncSettle(Context ctx, int v) {
        get(ctx).edit().putString(KEY_INC_SETTLE, String.valueOf(v)).apply();
    }

    public static boolean isBakOn(Context ctx) {
        return get(ctx).getBoolean(KEY_BAK_ON, true);
    }

    public static void setBakOn(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_BAK_ON, on).apply();
    }

    public static int getBakKeep(Context ctx) {
        return clampInt(get(ctx).getString(KEY_BAK_KEEP, "3"), 3, 1, 20);
    }

    public static void setBakKeep(Context ctx, int v) {
        get(ctx).edit().putString(KEY_BAK_KEEP, String.valueOf(v)).apply();
    }

    /**
     * 备份索引走 Settings.Global：写入方是 system_server 里的规则引擎，
     * 读取方是设置页，跨进程只能走 Global（SharedPreferences 不同进程各一份）。
     * 格式每行：md5|包名|字节数|时间|路径
     */
    public static String getBakList(Context ctx) {
        return getSysUiMark(ctx, KEY_BAK_LIST);
    }

    public static void setBakList(Context ctx, String v) {
        putGlobal(ctx, KEY_BAK_LIST, v == null ? "" : v);
    }

    /** 写 Settings.Global（system_server 侧同 uid，普通应用需 WRITE_SECURE_SETTINGS） */
    public static void putGlobal(Context ctx, String key, String v) {
        try {
            android.provider.Settings.Global.putString(ctx.getContentResolver(), key, v);
        } catch (Throwable t) {
            // 读侧静默，写失败也不抛：设置页只需展示，不影响规则判定
        }
    }

    private static int clampInt(String s, int def, int min, int max) {
        int v;
        try {
            v = Integer.parseInt(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return def;
        }
        return v < min ? min : (v > max ? max : v);
    }

    /** 读 SystemUI 侧写进 Settings.Global 的启动记录；无记录返回空串 */
    public static String getSysUiMark(Context ctx, String key) {
        try {
            String v = android.provider.Settings.Global.getString(ctx.getContentResolver(), key);
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean isFfCountLimit(Context ctx) {
        return get(ctx).getBoolean(KEY_FF_COUNT_LIMIT, true);
    }

    public static void setFfCountLimit(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FF_COUNT_LIMIT, on).apply();
    }

    public static boolean isFfBlacklist(Context ctx) {
        return get(ctx).getBoolean(KEY_FF_BLACKLIST, true);
    }

    public static void setFfBlacklist(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FF_BLACKLIST, on).apply();
    }

    public static boolean isFfPinForeground(Context ctx) {
        return get(ctx).getBoolean(KEY_FF_PIN_FOREGROUND, true);
    }

    public static void setFfPinForeground(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FF_PIN_FOREGROUND, on).apply();
    }

    public static boolean isFfCanSlide(Context ctx) {
        return get(ctx).getBoolean(KEY_FF_CAN_SLIDE, true);
    }

    public static void setFfCanSlide(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FF_CAN_SLIDE, on).apply();
    }

    // ── 小窗装饰隐藏（SystemUI / WM-Shell）──
    /** 隐藏小窗顶部标题栏与指示点 */
    public static final String KEY_FF_CAPTION_TOP = "ff_caption_top";
    /** 隐藏小窗底部标题栏 */
    public static final String KEY_FF_CAPTION_BOTTOM = "ff_caption_bottom";
    /** 隐藏小窗底部小白条（桌面模式） */
    public static final String KEY_FF_HOME_BAR = "ff_home_bar";

    public static boolean isFfCaptionTop(Context ctx) {
        return get(ctx).getBoolean(KEY_FF_CAPTION_TOP, false);
    }

    public static void setFfCaptionTop(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FF_CAPTION_TOP, on).apply();
    }

    public static boolean isFfCaptionBottom(Context ctx) {
        return get(ctx).getBoolean(KEY_FF_CAPTION_BOTTOM, false);
    }

    public static void setFfCaptionBottom(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FF_CAPTION_BOTTOM, on).apply();
    }

    public static boolean isFfHomeBar(Context ctx) {
        return get(ctx).getBoolean(KEY_FF_HOME_BAR, false);
    }

    public static void setFfHomeBar(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FF_HOME_BAR, on).apply();
    }

    // ── 侧边栏「小窗」快捷项 ──
    /** 手机管家侧边栏 DockShortCutMenu 注入「小窗」快捷项 */
    public static final String KEY_FF_SIDEBAR_SHORTCUT = "ff_sidebar_shortcut";

    public static boolean isFfSidebarShortcut(Context ctx) {
        return get(ctx).getBoolean(KEY_FF_SIDEBAR_SHORTCUT, false);
    }

    public static void setFfSidebarShortcut(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FF_SIDEBAR_SHORTCUT, on).apply();
    }

    /** 目标应用包名（小米互传） */
    public static final String TARGET_PKG = "com.miui.mishare.connectivity";
    /** 运行期适配状态文件名（由 Main 在目标进程写入，设置页读取展示） */
    public static final String STATUS_NAME = "misharemt2.status";
    /** 画报同步状态文件名（由 Fg 在画报进程写入，设置页读取展示） */
    public static final String STATUS_NAME_FG = "misharemt2.fg.status";
    /** 画报包名 */
    public static final String FG_PKG = "com.mfashiongallery.emag";
    /** 画报「自选图片」页 */
    public static final String FG_ACTIVITY =
            "com.mfashiongallery.emag.customwallpaper.manager.WallpaperManagerActivity";
    /** 画报默认同步目录 */
    public static final String FG_DEFAULT_DIR = "/storage/emulated/0/Pictures/小米画报/";
    /** 画报同步上限默认值 */
    public static final int FG_DEFAULT_CAP = 100;

    private Prefs() {
    }

    // ---------------- 版本 / 运行期状态 ----------------

    /** 目标应用版本，例如 "5.3.4（50304）"；未安装或不可见时返回 null */
    public static String installedVersion(Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName + "（" + (long) pi.versionCode + "）";
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读取目标进程写下的运行期适配状态（version/via/hooks/time 或 result）。
     * 读不到返回 null（例如还没启用作用域、或目标进程没写成）。
     */
    public static String readRuntimeStatus(Context ctx) {
        // 目标进程用广播回传（它写不了本模块的 Android/media 目录），优先用它
        String pushed = StatusReceiver.read(ctx, StatusReceiver.SRC_MISHARE);
        if (pushed != null && pushed.length() > 0) {
            return pushed;
        }
        // 状态文件与配置通道无关：配置走 CfgProvider，状态文件是
        // 「目标进程 → 设置页」的单向回传（目标进程有存储权限，可直接写 Android/media）。
        List<String> paths = new ArrayList<>();
        paths.add("/sdcard/Android/media/com.hyper.Camellia/" + STATUS_NAME);
        paths.add("/storage/emulated/0/Android/media/com.hyper.Camellia/" + STATUS_NAME);
        for (String p : paths) {
            FileInputStream in = null;
            try {
                File f = new File(p);
                if (!f.isFile() || f.length() <= 0) {
                    continue;
                }
                byte[] buf = new byte[(int) Math.min(f.length(), 8192)];
                in = new FileInputStream(f);
                int n = in.read(buf);
                if (n > 0) {
                    return new String(buf, 0, n, Charset.forName("UTF-8"));
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 取本模块的 SharedPreferences。
     *
     * <p><b>不保证非空</b>，调用方必须容忍 {@code null}。</p>
     *
     * <p>{@code ctx} 为 null 时退到 {@code ActivityThread#currentApplication}。
     * 这一点在子系统进程里是必需的：我们并没有 hook 小米互传的 Application，
     * 它的 {@code Main.anyContext()} 常常返回 null，早先直接解引用就抛 NPE，
     * 把整个 {@code Main.install()} 打断 —— 表现为解锁 hook 全不装、
     * 连状态文件都不写。</p>
     */
    public static SharedPreferences get(Context ctx) {
        if (ctx == null) {
            ctx = currentAppContext();
        }
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getSharedPreferences(GROUP, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前进程的 Application；拿不到返回 null。 */
    public static Context currentAppContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 手机管家（安全服务）业务线 ----------------

    /** 手机管家规则配置键前缀（Unlock.java 读取同一前缀） */
    public static final String KEY_UL_PREFIX = "ul_";

    /** 手机管家状态文件名（与 Unlock.STATUS_NAME 一致） */
    public static final String STATUS_NAME_UL = "misharemt2.ul.status";

    /** 目标包名：安全服务（手机管家） */
    public static final String SECURITY_PKG = "com.miui.securitycenter";

    /** 读小米互传的 versionCode；未安装 / 读取失败返回 -1 */
    public static int miShareVersionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(MISHARE_PKG, 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 小米互传解锁开关的读取（默认<b>开</b>）。
     *
     * <p>与 {@link #isUlOn} 的唯一区别是默认值：手机管家那些规则属于"改写既有行为"，
     * 默认关；而互传解锁是"装了这个模块就是要它"，用户没动过时应该生效，
     * 而不是要求先手动开一遍。</p>
     */
    public static boolean isMiShareUnlockOn(Context ctx, String id) {
        SharedPreferences sp = get(ctx);
        // 读不到配置时退回「开」：装模块的目的就是要解锁，没配置过应当生效
        return sp == null || sp.getBoolean(KEY_UL_PREFIX + id, true);
    }

    /** 当前安装的互传版本是否支持极速传输（低于门槛 → 设置页置灰） */
    public static boolean isMiShareTurboSupported(Context ctx) {
        return miShareVersionCode(ctx) >= MISHARE_MIN_TURBO;
    }

    /** 当前安装的互传版本是否支持苹果设备（AirDrop）互联 */
    public static boolean isMiShareAirDropSupported(Context ctx) {
        return miShareVersionCode(ctx) >= MISHARE_MIN_AIRDROP;
    }

    /**
     * 本机内核是否具备 AirDrop（wonder）能力。
     *
     * <p>小米的苹果设备互联最终依赖内核 wonder 模块提供的
     * {@code /proc/sys/net/wonder/wonder_enable} 节点。实测 Xiaomi 17 Pro Max
     * （内核 6.12.69）既没有该节点，{@code /vendor/lib/modules} 的 412 个模块里
     * 也没有任何 wonder 相关项 —— 属于内核层面就不具备。这种情况下 App 侧
     * hook 只能造出一个点了必然失败的假入口，所以如实返回 false 让设置页置灰。</p>
     *
     * <p>反过来，若某台设备内核带 wonder，这个方法返回 true，运行期 hook
     * {@code isWonderEnabled()} 才真正有意义。</p>
     */
    public static boolean isKernelWonderSupported() {
        try {
            return new File("/proc/sys/net/wonder/wonder_enable").exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 写入互传解锁开关。
     *
     * <p>刻意不复用 {@link #setUlOn}：那个方法会顺手重写 {@code /sdcard} 侧的侧车文件
     * 并向手机管家下发配置，而互传开关只由本模块自己的进程（互传内的 hook）读取，
     * 不需要侧车，切开关时也不该产生任何磁盘写。</p>
     */
    public static void setMiShareUnlockOn(Context ctx, String id, boolean on) {
        get(ctx).edit().putBoolean(KEY_UL_PREFIX + id, on).apply();
    }

    /** 规则默认关闭：不主动改变用户手机管家的既有行为 */
    public static boolean isUlOn(Context ctx, String id) {
        // 读不到配置时退回「关」：手机管家规则属于改写既有行为，宁可不动。
        // 需要默认开启的规则（gp_*）请用下面的三参重载。
        return isUlOn(ctx, id, false);
    }

    /**
     * 读取规则开关，<b>未配置时使用给定默认值</b>。
     *
     * <p>系统安全组件（gp_*）三条属于「装好就该生效」的防护规则，默认开启；
     * 其余规则仍传 false。设置页与侧车写入都必须走这个重载，否则
     * 「用户没动过」会被当成「用户关掉了」，把默认开压成默认关。</p>
     */
    public static boolean isUlOn(Context ctx, String id, boolean defVal) {
        SharedPreferences sp = get(ctx);
        return sp != null ? sp.getBoolean(KEY_UL_PREFIX + id, defVal) : defVal;
    }

    /**
     * 写入手机管家规则开关。
     *
     * <p>必须同时重写侧车：目标进程（手机管家）以自身 uid 运行，读不到本模块私有目录里的
     * prefs，侧车是它唯一能读到的配置来源（见 {@link #ulSidecarBody} 注释）。
     * 早期版本只写 prefs，导致「设置页开关已打开、手机管家侧永远未开启」。</p>
     */
    public static void setUlOn(Context ctx, String id, boolean on) {
        SharedPreferences p = get(ctx);
        p.edit().putBoolean(KEY_UL_PREFIX + id, on).apply();
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        // prefs 立刻生效（设置页/目标进程读 remote prefs 都能马上看到），
        // 「侧车写入 + 读回校验 + 配置下发」整体挪到后台串行队列：
        // 侧车在 /sdcard 上，同步做会让每次切开关阻塞主线程上百毫秒。
        final Context fapp = app;
        final String fid = id;
        final boolean fon = on;
        bg(new Runnable() {
            @Override
            public void run() {
                try {
                    patchUlSidecar(fapp);
                } catch (Throwable t) {
                    ulDiag(fapp, "setUlOn id=" + fid + " patchUlSidecar FAILED " + t);
                }
                // A：把开关同步给 native 侧（补丁在桌面进程里跑，只能读落盘副本）
                if (ID_UL_LAUNCHER_FREEFORM.equals(fid)) {
                    syncASwitch(fon);
                }
                // 读回校验：apply() 后内存值应立即可见，若这里就已经不一致，说明写的是别的 store
                boolean readBack = get(fapp).getBoolean(KEY_UL_PREFIX + fid, false);
                ulDiag(fapp, "setUlOn id=" + fid + " want=" + fon
                        + " prefsReadBack=" + readBack
                        + " sidecar=" + sidecarVal(fapp, KEY_UL_PREFIX + fid));
                try {
                    CfgBridge.push(fapp, TARGET_PKG);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * 把 A 开关写到 native 侧可读的落盘副本（见 {@link #A_SWITCH_FILE}）。
     *
     * <p>写失败既不抛异常也不提示：native 侧读不到文件时按「开」处理，
     * 与改动前行为一致，不至于因为一次写盘失败把功能弄没。</p>
     */
    public static void syncASwitch(boolean on) {
        java.io.FileOutputStream fos = null;
        try {
            java.io.File d = new java.io.File("/sdcard/Android/media/com.hyper.Camellia");
            if (!d.exists()) d.mkdirs();
            fos = new java.io.FileOutputStream(A_SWITCH_FILE);
            fos.write(on ? '1' : '0');
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 读取「识曲 App」选择（设置页侧）。
     *
     * <p>与布尔开关不同，这是字符串配置，同样必须落进侧车：目标进程只认侧车。</p>
     */
    public static String getUlApp(Context ctx) {
        String v = Unlock.normalizeBgmApp(get(ctx).getString(Unlock.BGM_APP_VALUE_KEY, null));
        return v == null ? Unlock.BGM_APP_NETEASE : v;
    }

    /**
     * 写入「识曲 App」选择。
     *
     * <p>顺序与 {@link #setUlOn} 一致：prefs（设置页自己看）→ 侧车（目标进程唯一可靠来源）
     * → 读回校验。侧车按 {@code ul_} 前缀整段重写，所以同一个键不会累积多行。</p>
     */
    public static void setUlApp(Context ctx, String value) {
        String v = Unlock.normalizeBgmApp(value);
        if (v == null) v = Unlock.BGM_APP_NETEASE;
        get(ctx).edit().putString(Unlock.BGM_APP_VALUE_KEY, v).apply();
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        final Context fapp = app;
        final String fv = v;
        bg(new Runnable() {
            @Override
            public void run() {
                try {
                    patchUlSidecar(fapp);
                } catch (Throwable t) {
                    ulDiag(fapp, "setUlApp v=" + fv + " patchUlSidecar FAILED " + t);
                }
                ulDiag(fapp, "setUlApp v=" + fv
                        + " sidecar=" + sidecarVal(fapp, Unlock.BGM_APP_VALUE_KEY));
                try {
                    CfgBridge.push(fapp, TARGET_PKG);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * 读取「侧边栏快捷方式面板」的全局屏蔽关键词（空串 = 不屏蔽任何条目）。
     */
    public static String getSidebarScBlock(Context ctx) {
        String v = get(ctx).getString(Unlock.SIDEBAR_SC_BLOCK_KEY, "");
        return v == null ? "" : v;
    }

    /**
     * 读取「侧边栏快捷方式面板」的应用专属屏蔽规则。
     *
     * <p>每行（或用分号分隔）形如 {@code 应用名或包名 = 关键词1, 关键词2}。</p>
     */
    public static String getSidebarScBlockApp(Context ctx) {
        String v = get(ctx).getString(Unlock.SIDEBAR_SC_BLOCK_APP_KEY, "");
        return v == null ? "" : v;
    }

    /**
     * 写入「侧边栏快捷方式面板」的屏蔽关键词（全局 + 应用专属一次写完）。
     *
     * <p>顺序与 {@link #setUlOn} / {@link #setUlApp} 一致：prefs（设置页自己看）→ 侧车
     * （目标进程 :ui 的唯一可靠来源）→ 配置下发。侧车是「一行一个键」的文本文件，
     * 所以应用专属规则里的换行必须先压成 {@code ;}，hook 侧两种分隔符都认。</p>
     */
    public static void setSidebarScBlock(Context ctx, String global, String perApp) {
        final String g = global == null ? "" : global;
        final String a = perApp == null ? "" : perApp;
        get(ctx).edit()
                .putString(Unlock.SIDEBAR_SC_BLOCK_KEY, g)
                .putString(Unlock.SIDEBAR_SC_BLOCK_APP_KEY, a)
                .apply();
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        final Context fapp = app;
        bg(new Runnable() {
            @Override
            public void run() {
                try {
                    patchUlSidecar(fapp);
                } catch (Throwable t) {
                    ulDiag(fapp, "setSidebarScBlock patchUlSidecar FAILED " + t);
                }
                try {
                    ulDiag(fapp, "setSidebarScBlock global=" + g.length() + "字 app=" + a.length() + "字"
                            + " sidecarGlobal=" + sidecarVal(fapp, Unlock.SIDEBAR_SC_BLOCK_KEY));
                } catch (Throwable ignored) {
                }
                try {
                    CfgBridge.push(fapp, TARGET_PKG);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 侧车文本值：把换行压成 {@code ;}，保证「一行一个键」不被多行文本破坏。 */
    private static String oneLine(String v) {
        if (v == null) return "";
        return v.replace('\r', ';').replace('\n', ';').trim();
    }

    /** 读取手机管家规则状态文件（目标进程写，设置页只读） */
    public static String readUlStatus(Context ctx) {
        String[] dirs = {
                "/sdcard/Android/media/com.hyper.Camellia/",
                "/storage/emulated/0/Android/media/com.hyper.Camellia/",
        };
        for (String d : dirs) {
            File f = new File(d, STATUS_NAME_UL);
            try {
                if (!f.isFile() || f.length() <= 0L) {
                    continue;
                }
                byte[] buf = new byte[(int) Math.min(f.length(), 65536L)];
                FileInputStream in = new FileInputStream(f);
                int n = in.read(buf);
                in.close();
                if (n > 0) {
                    return new String(buf, 0, n, Charset.forName("UTF-8"));
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    // ---------------- 首次启动引导 ----------------

    /** 引导是否已完成；false 时设置页会先展示引导（用户协议 + 必要授权） */
    public static boolean isGuideDone(Context ctx) {
        return get(ctx).getBoolean(KEY_GUIDE_DONE, false);
    }

    public static void setGuideDone(Context ctx, boolean done) {
        get(ctx).edit().putBoolean(KEY_GUIDE_DONE, done).apply();
    }

    // ---------------- 隐藏桌面图标 ----------------

    public static boolean isHideIcon(Context ctx) {
        return get(ctx).getBoolean(KEY_HIDE_ICON, false);
    }

    public static void setHideIcon(Context ctx, boolean hide) {
        get(ctx).edit().putBoolean(KEY_HIDE_ICON, hide).apply();
        applyIconVisibility(ctx, hide);
    }

    /**
     * 启用 / 禁用桌面图标（activity-alias）。
     * 操作的是本应用自己的组件，无需任何权限。
     * 隐藏后仍可从 LSPosed 管理器点进本设置页（MODULE_SETTINGS 分类）。
     */
    public static void applyIconVisibility(Context ctx, boolean hide) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        final Context fapp = app;
        final boolean fhide = hide;
        bg(new Runnable() {
            @Override
            public void run() {
                try {
                    ComponentName alias = new ComponentName(fapp.getPackageName(), LAUNCHER_ALIAS);
                    fapp.getPackageManager().setComponentEnabledSetting(
                            alias,
                            fhide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                  : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ---------------- 打开方式 ----------------

    public static String getMtTarget(Context ctx) {
        String v = get(ctx).getString(KEY_MT_TARGET, MT_DEFAULT);
        return MT_CANARY.equals(v) ? MT_CANARY : MT_DEFAULT;
    }

    public static void setMtTarget(Context ctx, String pkg) {
        String v = MT_CANARY.equals(pkg) ? MT_CANARY : MT_DEFAULT;
        get(ctx).edit().putString(KEY_MT_TARGET, v).apply();
    }

    /** 目标包是否已安装（依赖 manifest 里的 &lt;queries&gt;） */
    public static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 目录跳转能力探测 ----------------

    /** MT 跳转入口的类名末段 */
    public static final String MT_SHORTCUT_SIMPLE = "ShortcutActivity";

    /**
     * 目标包是否真的支持「跳转到指定目录」。
     *
     * MT 的 ShortcutActivity 没有 intent-filter，只能用显式组件调用；
     * 而且分身/克隆版可能根本没有这个组件（实测 bin.mt.plus.canary 2.26.4-clone 就没有），
     * 此时按 "<包名>.ShortcutActivity" 拼出来的 Intent 无处可去，看起来就是「点了没反应」。
     */
    public static boolean supportsFolderJump(Context ctx, String pkg) {
        return shortcutClass(ctx, pkg) != null;
    }

    /** 返回目标包内 ShortcutActivity 的完整类名；没有该组件则返回 null */
    public static String shortcutClass(Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            ActivityInfo[] acts = pi == null ? null : pi.activities;
            if (acts != null) {
                for (ActivityInfo ai : acts) {
                    String n = ai == null ? null : ai.name;
                    if (n == null) {
                        continue;
                    }
                    int dot = n.lastIndexOf('.');
                    String simple = dot >= 0 ? n.substring(dot + 1) : n;
                    if (MT_SHORTCUT_SIMPLE.equals(simple)) {
                        return n;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 设置页用的状态标签 */
    public static String statusTag(Context ctx, String pkg) {
        if (!isInstalled(ctx, pkg)) {
            return "  [未安装]";
        }
        if (!supportsFolderJump(ctx, pkg)) {
            return "  [不支持目录跳转]";
        }
        return "";
    }

    // ---------------- 跨进程配置（CfgProvider，唯一通道） ----------------

    /**
     * 跨进程配置通道：<b>只有 CfgProvider 这一条</b>。
     *
     * <pre>
     *   真源：本模块的 SharedPreferences（设置页写、模块进程读）
     *   通道：content://com.hyper.Camellia.cfg/cfg   (CfgProvider, exported, 无权限)
     *   信号：CfgBridge 广播 —— 不带配置内容，只表示「配置变了，去重读」
     * </pre>
     *
     * <p>历史通道全部删除，原因：</p>
     * <ul>
     *   <li>/sdcard 侧车文件 —— Android 13+ 分区存储，目标进程读不到别的 app 的
     *       Android/data|media，读 Download 里的 .txt 也被 MediaProvider 拒绝。</li>
     *   <li>Settings.System 公共键 —— 本机实测普通应用<b>写不进</b>自定义键
     *       （{@code You cannot keep your settings in the secure settings}），只有 root/shell
     *       能写，作为通道永远不可靠。</li>
     *   <li>Xposed remote preferences —— 模块 app 被冻结或 LSPosed 快照过期时内容滞后。</li>
     *   <li>画报进程内的 cfg.txt 快照 —— 又一份可写状态，偏离真源。</li>
     * </ul>
     *
     * <p>多通道并存时「谁是最新」无从判断，曾直接导致「cap 显示 10、实际只有 8」。
     * 所以这里只允许一条路。</p>
     */
    public static final String CFG_AUTHORITY = "com.hyper.Camellia.cfg";

    /** 最近一次读取命中的通道（cache / provider / -），供状态文件诊断 */
    private static volatile String sLastChannel = "-";

    public static String lastCfgChannel() {
        return sLastChannel;
    }

    // ───────────── 跨进程配置缓存（代际校验，借鉴 HyperCeiler PrefsBridge） ─────────────

    /**
     * 「可信窗口」：这段时间内直接复用上一次的全文，<b>零 Binder</b>。
     *
     * <p>为什么需要：一次 {@code Fg.load()} 里要读好几次配置（扫描前、注入前、
     * 状态落盘前），每次都是几百字节的跨进程文本传输 + 目标进程侧的逐行解析。
     * 同一轮同步里的这些读取不可能拿到不同结果，全部复用即可。</p>
     */
    private static final long CFG_TRUST_MS = 800L;

    /**
     * 配置失效代际：{@link #invalidateCfgCache()} 每次被调用就 +1。
     *
     * <p>各进程在配置之上还各自有一层「解析后的快照」（Unlock 的规则表、
     * LauncherShortcut 的屏蔽词、Fg 的 {@code Cfg}）。它们的 TTL 是 1.5~3 秒，
     * 光靠 TTL 用户会感觉「改了要等一会儿才生效」。把代际记进快照，
     * 代际一不等就立刻重建 —— 既保留 TTL 兜底，又做到变更当轮生效。</p>
     */
    private static final AtomicLong CFG_GEN = new AtomicLong();

    /** 当前配置失效代际（单调递增，进程内有效） */
    public static long cfgGen() {
        return CFG_GEN.get();
    }

    private static final Object CFG_LOCK = new Object();

    /** 上次成功读到的全文（ = 还没有成功读过）。只在 {@link #CFG_LOCK} 里改 */
    private static String sCfgText;

    /**
     * 该全文对应的 provider 版本戳（{@code 代际:计数}）。
     *
     * <p>不能只用一个自增数字：模块 app 重装 / provider 进程重启后计数会从 0 重新涨，
     * 数字比旧缓存还小，缓存方会永远认为「没变」——这正是「更新 App 后配置还是旧的」
     * 的机制性根因。代际串一变，字符串相等判断必然失败，缓存立刻作废。</p>
     */
    private static String sCfgVer;

    /** 上次确认（或刷新）的时间，uptimeMillis 基准 */
    private static volatile long sCfgAt = -CFG_TRUST_MS;

    /** ContentObserver 只注册一次（各进程各一份） */
    private static volatile boolean sCfgObserved;

    /**
     * 读取跨进程配置文本（唯一入口，所有目标进程都调它）。
     *
     * <p>三级取数，从便宜到贵：</p>
     * <ol>
     *   <li><b>本地全文缓存</b>（{@link #CFG_TRUST_MS} 内）—— 零 IPC。</li>
     *   <li><b>版本探测</b> —— 只取几字节的 ver 列，与缓存版本相等就复用全文。</li>
     *   <li><b>全量拉取</b> —— 版本变了（或首次）才传输整份配置。</li>
     * </ol>
     *
     * <p>配置变更会经 {@link #ensureCfgObserver} 的 ContentObserver 立刻把缓存打脏，
     * 所以「改了不生效」不再依赖 TTL 到期，最多晚一轮读取。</p>
     */
    public static String readCfgText(Context ctx) {
        ensureCfgObserver(ctx);
        long now = SystemClock.uptimeMillis();
        if (sCfgText != null && now - sCfgAt < CFG_TRUST_MS) {
            sLastChannel = "cache";
            return sCfgText;
        }
        synchronized (CFG_LOCK) {
            now = SystemClock.uptimeMillis();
            if (sCfgText != null && now - sCfgAt < CFG_TRUST_MS) {
                sLastChannel = "cache";
                return sCfgText;
            }
            // 轻量探测：版本没变就不传正文
            String ver = queryCfgVersion(ctx);
            if (ver != null && sCfgText != null && ver.equals(sCfgVer)) {
                sCfgAt = now;
                sLastChannel = "provider";
                return sCfgText;
            }
            String text = queryCfgProvider(ctx);
            if (text != null && text.length() > 0) {
                sCfgText = text;
                sCfgVer = ver;
                sCfgAt = now;
                sLastChannel = "provider";
                return text;
            }
            // provider 暂时不可用（模块 app 正在重启/被冻结）：
            // 有旧全文就用旧全文（配置不可能变，因为没有进程能改它），否则交回 null 让调用方用自身默认
            sLastChannel = sCfgText != null ? "cache-stale" : "-";
            return sCfgText;
        }
    }

    /**
     * 丢弃缓存的时间戳（不丢正文），下一次读取必然重新探测版本。
     *
     * <p>收到「配置变了」广播时调用，让改动立刻可见；不清正文是为了 provider
     * 一时不可用时仍能退回上一个已知良好的配置。</p>
     */
    public static void invalidateCfgCache() {
        long gen = CFG_GEN.incrementAndGet();
        // 每个订阅进程在配置真正变更时只打一行，用于确认 notifyChange 到达了哪些进程
        Log.println(Log.INFO, "MiShareMT2/Prefs", "cfg invalidated gen=" + gen
                + " pid=" + android.os.Process.myPid()
                + " proc=" + currentProcName());
        synchronized (CFG_LOCK) {
            sCfgAt = -CFG_TRUST_MS;
        }
    }

    /** 当前进程名（诊断用）；取不到就返回 "?" */
    private static String currentProcName() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/cmdline"));
            try {
                String line = r.readLine();
                if (line != null) {
                    int z = line.indexOf(0);
                    return z >= 0 ? line.substring(0, z) : line;
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /**
     * 注册配置变更观察者（幂等）。
     *
     * <p>provider 侧任何 prefs 键变更都会 {@code notifyChange}，这里把缓存打脏。
     * 于是手机管家（{@code :ui}）、system_server（快捷方式）、互传进程这些
     * <b>没有广播接收端</b>的读取点也能做到底层配置一改、下一次读取就是新值。</p>
     */
    public static void ensureCfgObserver(Context ctx) {
        if (sCfgObserved || ctx == null) {
            return;
        }
        synchronized (CFG_LOCK) {
            if (sCfgObserved) {
                return;
            }
            sCfgObserved = true;
            try {
                Handler h = new Handler(Looper.getMainLooper());
                ContentObserver o = new ContentObserver(h) {
                    @Override
                    public void onChange(boolean selfChange) {
                        invalidateCfgCache();
                    }
                };
                ctx.getContentResolver().registerContentObserver(
                        Uri.parse("content://" + CFG_AUTHORITY + "/cfg"), true, o);
            } catch (Throwable t) {
                // 拿不到 Looper / provider 不可用：不致命，靠时间窗口 + 版本探测兜底
                Log.println(Log.INFO, "MiShareMT2/Prefs", "cfg observer register failed: " + t);
            }
        }
    }

    /** 只查 provider 的版本列（几字节，用于判断全文缓存是否还有效） */
    public static String queryCfgVersion(Context ctx) {
        if (ctx == null) {
            return null;
        }
        Cursor c = null;
        // 与 queryCfgProvider 同理：system_server 侧 Binder 身份需要清零，
        // 否则 callingPackage "android" 与调用方 uid 不匹配 → SecurityException。
        long identity = Binder.clearCallingIdentity();
        try {
            c = ctx.getContentResolver().query(
                    Uri.parse("content://" + CFG_AUTHORITY + "/cfg"),
                    new String[]{"ver"}, null, null, null);
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            int idx = c.getColumnIndex("ver");
            return idx < 0 ? null : c.getString(idx);
        } catch (Throwable t) {
            Log.println(Log.INFO, "MiShareMT2/Prefs", "cfg ver probe failed: " + t);
            return null;
        } finally {
            try {
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable ignored) {
            }
            try {
                if (c != null) {
                    c.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 兼容旧调用点的名字，等价于 {@link #readCfgText(Context)} */
    public static String readCrossProcCfg(Context ctx) {
        return readCfgText(ctx);
    }

    /** 通过 CfgProvider 拉取配置文本（失败返回 null，不抛异常） */
    public static String queryCfgProvider(Context ctx) {
        if (ctx == null) {
            return null;
        }
        Cursor c = null;
        // LSPosed 在 system_server 的 Binder 线程里执行本方法时，Binder 调用身份仍
        // 沿袭发起方（com.miui.home，uid 10146），而 systemContext 的 opPackageName
        // 是 "android"，二者不匹配会让 ContentProvider 直接抛
        //   SecurityException: Given calling package android does not match caller's uid
        // 结果 provider 通道在 system_server 侧彻底失效，开关读到 null 后回落到
        // remote prefs 的旧缓存，表现为「设置里关了但小窗照样出现」。
        // 清零调用身份后 Binder.getCallingUid() 变回本进程（system=1000），与
        // "android" 匹配，provider 查询才通。
        long identity = Binder.clearCallingIdentity();
        try {
            c = ctx.getContentResolver().query(
                    Uri.parse("content://" + CFG_AUTHORITY + "/cfg"),
                    null, null, null, null);
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            int idx = c.getColumnIndex("cfg");
            return idx < 0 ? null : c.getString(idx);
        } catch (Throwable t) {
            Log.println(Log.INFO, "MiShareMT2/Prefs", "cfg provider query failed: " + t);
            return null;
        } finally {
            try {
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable ignored) {
            }
            try {
                if (c != null) {
                    c.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }


    /**
     * 构建跨进程配置文本（key=value，每行一条）。
     *
     * <p>这是配置的<b>唯一</b>序列化出口：{@code CfgProvider} 每次查询都调用它重新构建，
     * 不做缓存，所以目标进程读到的永远是设置页写入的真源值。{@code CfgBridge} 广播
     * 只承载「变了」这个信号，不携带内容。</p>
     */
    public static String buildCfgBody(Context ctx, String mtTarget, String fgDir) {
        // 带上画报的完整配置：远程偏好读不到时，这是画报唯一的配置来源，缺一项就会退回默认值
        return "# Camellia — 跨进程配置\n"
                + SIDECAR_KEY_MT + "=" + mtTarget + "\n"
                + SIDECAR_KEY_FG_DIR + "=" + fgDir + "\n"
                + SIDECAR_KEY_FG_CAP + "=" + getFgCap(ctx) + "\n"
                + SIDECAR_KEY_FG_ENABLED + "=" + (isFgEnabled(ctx) ? "1" : "0") + "\n"
                + SIDECAR_KEY_FG_STRICT + "=" + (isFgStrict(ctx) ? "1" : "0") + "\n"
                + SIDECAR_KEY_FG_ORDER + "=" + getFgOrder(ctx) + "\n"
                + SIDECAR_KEY_FG_SEED + "=" + getFgSeed(ctx) + "\n"
                + SIDECAR_KEY_FG_RESET_SEQ + "=" + getFgResetSeq(ctx) + "\n"
                + SIDECAR_KEY_FG_BLOCK_AD + "=" + (isFgBlockAd(ctx) ? "1" : "0") + "\n"
                + SIDECAR_KEY_FG_LOCAL_PHOTO + "=" + (isFgLocalPhoto(ctx) ? "1" : "0") + "\n"
                + SIDECAR_KEY_FG_CURATED + "=" + getFgCurated(ctx) + "\n"
                + SIDECAR_KEY_DEBUG + "=" + (isFgDebug(ctx) ? "1" : "0") + "\n"
                + SIDECAR_KEY_SB_COLOR + "=" + String.format("%08X", Integer.valueOf(getSidebarColor(ctx))) + "\n"
                + SIDECAR_KEY_SB_BLUR + "=" + getSidebarBlur(ctx) + "\n"
                + SIDECAR_KEY_SB_MONET + "=" + (isSidebarMonet(ctx) ? "1" : "0") + "\n"
                + SIDECAR_KEY_SB_MODE + "=" + getSidebarMode(ctx) + "\n"
                + KEY_SIDEBAR_LIMIT + "=" + getSidebarLimit(ctx) + "\n"
                + "ts=" + System.currentTimeMillis() + "\n"
                + ulSidecarBody(ctx);
    }

    /**
     * 把手机管家（安全服务）各规则的开关状态编成配置行。
     *
     * <p>目标进程（手机管家）以自身 uid 运行，读不到本模块 prefs，因此这份文本经
     * CfgProvider 下发。每个 {@code ul_<id>} 都必须在这里有一行，否则规则永远是「未开启」。</p>
     */
    private static String ulSidecarBody(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            for (String[] r : Unlock.ruleRows()) {
                if (r == null || r.length == 0 || r[0] == null) {
                    continue;
                }
                // 必须带默认值：gp_* 未配置时应写 1（默认开），否则侧车会把
                // 「用户没动过」固化成 0，目标进程读到显式 false，默认开就失效了。
                sb.append(KEY_UL_PREFIX).append(r[0]).append('=')
                        .append(isUlOn(ctx, r[0], Unlock.ruleDefOn(r[0])) ? "1" : "0")
                        .append('\n');
            }
        } catch (Throwable ignored) {
        }
        // 字符串配置（识曲 App）也必须进侧车：目标进程读不到模块 prefs，只认侧车这一份。
        try {
            sb.append(Unlock.BGM_APP_VALUE_KEY).append('=')
                    .append(getUlApp(ctx)).append('\n');
        } catch (Throwable ignored) {
        }
        // 「侧边栏快捷方式面板」的屏蔽关键词（全局 / 应用专属）同样是字符串配置，
        // 目标进程 :ui 只认侧车这一份；多行的应用专属规则要先压成单行（oneLine）。
        try {
            sb.append(Unlock.SIDEBAR_SC_BLOCK_KEY).append('=')
                    .append(oneLine(getSidebarScBlock(ctx))).append('\n');
            sb.append(Unlock.SIDEBAR_SC_BLOCK_APP_KEY).append('=')
                    .append(oneLine(getSidebarScBlockApp(ctx))).append('\n');
        } catch (Throwable ignored) {
        }
        // 「长按图标 → 快捷菜单 → 小窗」开关不在 Unlock 规则表里（由设置页直接 setUlOn），
        // 但它同样必须进侧车 / 公开副本：system_server 侧 LauncherShortcut 在
        // remote preferences 不可用时要靠这两份文件兜底。
        try {
            sb.append(SIDECAR_KEY_UL_APP_SHORTCUT).append('=')
                    .append(isUlOn(ctx, ID_UL_APP_SHORTCUT) ? "1" : "0").append('\n');
        } catch (Throwable ignored) {
        }
        // 「桌面内自由小窗」（A）开关：native patch 侧据此决定是否加载放行逻辑。
        // 注意（v151）：A 与 B 是上下游配套（A 放行、B 供数据），不再有「A 开则 B 失效」。
        try {
            sb.append(SIDECAR_KEY_UL_LAUNCHER_FREEFORM).append('=')
                    .append(isUlOn(ctx, ID_UL_LAUNCHER_FREEFORM) ? "1" : "0").append('\n');
        } catch (Throwable ignored) {
        }
        // gp_push 的 10 条命令级子开关（对齐上游 block_push_command_<cmd>）。
        // 它们不在 Unlock 规则表里，上面的规则循环带不出来，必须逐个补：
        // 缺一行就意味着该条指令的子开关在 guardprovider 进程里永远取默认值，
        // 用户关了也不生效。三参重载的默认值传 true，与 gp_push 本体口径一致。
        try {
            for (String cmd : Unlock.GP_PUSH_SUB_CMDS) {
                String subId = Unlock.gpPushSubId(cmd);
                if (subId == null) {
                    continue;
                }
                sb.append(KEY_UL_PREFIX).append(subId).append('=')
                        .append(isUlOn(ctx, subId, true) ? "1" : "0").append('\n');
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 开关链路自检：写 logcat + 模块自己的外部目录日志（排查「开关不生效」用） */
    private static void ulDiag(final Context ctx, final String msg) {
        try {
            Log.println(Log.ERROR, "Camellia/UlCfg", msg);
        } catch (Throwable ignored) {
        }
        // 文件落盘挪到后台队列：切开关时不再阻塞主线程
        bg(new Runnable() {
            @Override
            public void run() {
                try {
                    File[] dirs = ctx.getExternalMediaDirs();
                    if (dirs == null || dirs.length == 0 || dirs[0] == null) return;
                    File f = new File(dirs[0], "setul.log");
                    FileOutputStream fos = new FileOutputStream(f, true);
                    fos.write((System.currentTimeMillis() + " " + msg + "\n")
                            .getBytes(Charset.forName("UTF-8")));
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 读配置文本里某个键的实际值（来源：CfgProvider），仅供诊断日志 */
    private static String sidecarVal(Context ctx, String key) {
        String text = queryCfgProvider(ctx);
        if (text == null || text.length() == 0) {
            return "no-provider";
        }
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + "=")) {
                return t.substring(key.length() + 1);
            }
        }
        return "key-missing";
    }

    /**
     * 刷新 {@code ul_*} 规则状态。
     *
     * <p>原来是在侧车文件里做「只替换 ul_ 行、保留其它行」的增量补丁（怕 prefs 读不到时
     * 把已有配置覆盖成默认值）。改走 Settings.System 后不存在这个问题：公共键里始终
     * 是一份由模块 prefs 构建的完整快照，直接整份重写即可。</p>
     */
    private static void patchUlSidecar(Context ctx) {
        // 单通道：真源是 prefs，CfgProvider 随时能读到最新值。
        // 这里只补一个「配置变了，去重读」的刷新信号。
        CfgBridge.push(ctx, TARGET_PKG);
    }

    // ---------------- 小米画报配置 ----------------

    public static boolean isFgEnabled(Context ctx) {
        return get(ctx).getBoolean(KEY_FG_ENABLED, true);
    }

    public static void setFgEnabled(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FG_ENABLED, on).apply();
        CfgBridge.push(ctx, FG_PKG);
    }

    /** 禁用画报推广（默认开）：广告不请求、不进轮播池、不展示 */
    public static boolean isFgBlockAd(Context ctx) {
        return get(ctx).getBoolean(KEY_FG_BLOCK_AD, true);
    }

    public static void setFgBlockAd(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FG_BLOCK_AD, on).apply();
        CfgBridge.push(ctx, FG_PKG);
    }

    /** 右滑轮播图片接管（默认关）：画报右滑轮播池改用同步目录里的本地图片；锁屏壁纸轮播不受影响 */
    public static boolean isFgLocalPhoto(Context ctx) {
        return get(ctx).getBoolean(KEY_FG_LOCAL_PHOTO, false);
    }

    public static void setFgLocalPhoto(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FG_LOCAL_PHOTO, on).apply();
    }

    /** 精简精选壁纸：逗号分隔的 scrb_items1.t_id 白名单，空串表示不过滤 */
    public static String getFgCurated(Context ctx) {
        return get(ctx).getString(KEY_FG_CURATED, "");
    }

    public static void setFgCurated(Context ctx, String ids) {
        get(ctx).edit().putString(KEY_FG_CURATED, ids == null ? "" : ids).apply();
        CfgBridge.push(ctx, FG_PKG);
    }

    /**
     * 环境自动修复（默认关）。
     *
     * <p>纯 App 侧行为：打开画报设置页时若自检发现异常，自动执行 root 授权 + 媒体扫描。
     * 画报进程不需要知道这个开关，所以不推 sidecar、不发 CfgBridge。</p>
     */
    public static boolean isFgAutoHeal(Context ctx) {
        return get(ctx).getBoolean(KEY_FG_AUTO_HEAL, false);
    }

    public static void setFgAutoHeal(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FG_AUTO_HEAL, on).apply();
    }

    public static String getFgDir(Context ctx) {
        String v = get(ctx).getString(KEY_FG_DIR, FG_DEFAULT_DIR);
        if (v == null || v.trim().length() == 0) {
            return FG_DEFAULT_DIR;
        }
        v = v.trim();
        return v.endsWith("/") ? v : v + "/";
    }

    public static void setFgDir(Context ctx, String dir) {
        get(ctx).edit().putString(KEY_FG_DIR, dir == null ? FG_DEFAULT_DIR : dir).apply();
        CfgBridge.push(ctx, FG_PKG);
    }

    /** 加载顺序：顺序 / 倒序 / 随机 */
    public static String getFgOrder(Context ctx) {
        String v = get(ctx).getString(KEY_FG_ORDER, ORDER_SEQ);
        if (ORDER_DESC.equals(v) || ORDER_RANDOM.equals(v)) {
            return v;
        }
        return ORDER_SEQ;
    }

    public static void setFgOrder(Context ctx, String order) {
        String v = ORDER_DESC.equals(order) || ORDER_RANDOM.equals(order) ? order : ORDER_SEQ;
        get(ctx).edit().putString(KEY_FG_ORDER, v).apply();
        CfgBridge.push(ctx, FG_PKG);
    }

    /**
     * 随机顺序用的种子（0 = 未设置，由文件集合派生，保证同一批文件顺序稳定）。
     * 换种子＝重新洗牌。这里不自动生成种子，避免偏好读不到时每次同步都重排。
     */
    public static long getFgSeed(Context ctx) {
        return get(ctx).getLong(KEY_FG_SEED, 0L);
    }

    public static void setFgSeed(Context ctx, long seed) {
        get(ctx).edit().putLong(KEY_FG_SEED, seed).apply();
        CfgBridge.push(ctx, FG_PKG);
    }

    /**
     * 读「右滑进度重置序号」。0 = 从未设置过。
     * 画报进程只认序号变化，所以这里不做任何自动维护（不做时间戳兜底）。
     */
    public static long getFgResetSeq(Context ctx) {
        return get(ctx).getLong(KEY_FG_RESET_SEQ, 0L);
    }

    /**
     * 序号 +1（只写值，不推配置）。调用方负责随后的 {@link CfgBridge#push}，
     * 这样「改顺序」和「重新随机」都能把新序号一次性带过去。
     */
    public static long bumpFgResetSeq(Context ctx) {
        long next = getFgResetSeq(ctx) + 1L;
        get(ctx).edit().putLong(KEY_FG_RESET_SEQ, next).apply();
        return next;
    }

    public static boolean isFgStrict(Context ctx) {
        return get(ctx).getBoolean(KEY_FG_STRICT, false);
    }

    public static void setFgStrict(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FG_STRICT, on).apply();
        CfgBridge.push(ctx, FG_PKG);
    }

    public static final String KEY_SIDEBAR_LIMIT = "sidebar_app_limit";
    public static final int SIDEBAR_LIMIT_DEFAULT = 50;
    public static final int SIDEBAR_LIMIT_MAX = 200;

    public static int getSidebarLimit(Context ctx) {
        return Math.max(16, Math.min(SIDEBAR_LIMIT_MAX,
                get(ctx).getInt(KEY_SIDEBAR_LIMIT, SIDEBAR_LIMIT_DEFAULT)));
    }

    public static void setSidebarLimit(Context ctx, int value) {
        int v = Math.max(16, Math.min(SIDEBAR_LIMIT_MAX, value));
        get(ctx).edit().putInt(KEY_SIDEBAR_LIMIT, v).apply();
        try { patchSidecarKey(ctx, KEY_SIDEBAR_LIMIT, String.valueOf(v)); }
        catch (Throwable ignored) { }
    }

    // ---------------- 全局侧边栏背景自定义（手机管家侧边栏） ----------------

    /** 侧边栏背景色键（Unlock.java 读同一名字，只是走侧车通道） */
    public static final String KEY_SB_COLOR = SIDECAR_KEY_SB_COLOR;
    /** 默认背景色：半透明黑，接近 MIUI 原生观感 */
    public static final int SB_COLOR_DEF = 0xCC000000;

    public static int getSidebarColor(Context ctx) {
        return get(ctx).getInt(KEY_SB_COLOR, SB_COLOR_DEF);
    }

    /** 保存侧边栏背景色；目标进程读不到本 app 私有 prefs，所以必须同步写侧车 */
    public static void setSidebarColor(Context ctx, int color) {
        get(ctx).edit().putInt(KEY_SB_COLOR, color).apply();
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        try {
            patchSidecarKey(app, KEY_SB_COLOR, String.format("%08X", Integer.valueOf(color)));
        } catch (Throwable ignored) {
        }
        try {
            CfgBridge.push(app, SECURITY_PKG);
        } catch (Throwable ignored) {
        }
    }

    /** 模糊程度键（Unlock.java 读同一名字，同样走侧车通道） */
    public static final String KEY_SB_BLUR = SIDECAR_KEY_SB_BLUR;
    /** 模糊程度默认值：与 HyperCeiler 的 blurradius 默认 60 一致 */
    public static final int SB_BLUR_DEF = 60;
    /** 模糊程度下限 / 上限：上限对齐 HyperCeiler SeekBarPreferenceCompat 的 maxValue；
     *  下限按要求放宽到 10（原 30），10 仍能给出可辨识的轻微虚化 */
    public static final int SB_BLUR_MIN = 10;
    public static final int SB_BLUR_MAX = 99;

    private static int clampSbBlur(int v) {
        if (v < SB_BLUR_MIN) {
            return SB_BLUR_MIN;
        }
        if (v > SB_BLUR_MAX) {
            return SB_BLUR_MAX;
        }
        return v;
    }

    public static int getSidebarBlur(Context ctx) {
        return clampSbBlur(get(ctx).getInt(KEY_SB_BLUR, SB_BLUR_DEF));
    }

    /** 保存模糊程度；与背景色同理，目标进程读不到本 app 私有 prefs，必须同步写侧车 */
    public static void setSidebarBlur(Context ctx, int blur) {
        int v = clampSbBlur(blur);
        get(ctx).edit().putInt(KEY_SB_BLUR, v).apply();
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        try {
            patchSidecarKey(app, KEY_SB_BLUR, String.valueOf(v));
        } catch (Throwable ignored) {
        }
        try {
            CfgBridge.push(app, SECURITY_PKG);
        } catch (Throwable ignored) {
        }
    }

    /** 自动莫奈取色开关键（Unlock.java 读同一名字，同样走侧车通道） */
    public static final String KEY_SB_MONET = SIDECAR_KEY_SB_MONET;
    /** 默认开启：未设色时用壁纸主色，比固定的半透明黑更贴合当前主题 */
    public static final boolean SB_MONET_DEF = true;

    public static boolean isSidebarMonet(Context ctx) {
        return get(ctx).getBoolean(KEY_SB_MONET, SB_MONET_DEF);
    }

    /** 保存自动莫奈取色开关；与背景色同理，必须同步写侧车目标进程才读得到 */
    public static void setSidebarMonet(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_SB_MONET, on).apply();
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        try {
            patchSidecarKey(app, KEY_SB_MONET, on ? "1" : "0");
        } catch (Throwable ignored) {
        }
        try {
            CfgBridge.push(app, SECURITY_PKG);
        } catch (Throwable ignored) {
        }
    }

    /** 动态取色方案键（Unlock.java 读同一名字，同样走侧车通道） */
    public static final String KEY_SB_MODE = SIDECAR_KEY_SB_MODE;
    /** 方案数量与默认值 */
    public static final int SB_MODE_COUNT = 7;
    public static final int SB_MODE_DEF = 0;

    public static int getSidebarMode(Context ctx) {
        int v = get(ctx).getInt(KEY_SB_MODE, SB_MODE_DEF);
        return (v < 0 || v >= SB_MODE_COUNT) ? SB_MODE_DEF : v;
    }

    /** 保存动态取色方案；与背景色同理，必须同步写侧车目标进程才读得到 */
    public static void setSidebarMode(Context ctx, int mode) {
        int v = (mode < 0 || mode >= SB_MODE_COUNT) ? SB_MODE_DEF : mode;
        get(ctx).edit().putInt(KEY_SB_MODE, v).apply();
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        try {
            patchSidecarKey(app, KEY_SB_MODE, String.valueOf(v));
        } catch (Throwable ignored) {
        }
        try {
            CfgBridge.push(app, SECURITY_PKG);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 单个键变更后刷新跨进程配置。
     *
     * <p>原来是在侧车文件里做「只替换这一行」的增量补丁，现在统一整份写入
     * Settings.System：一次 put 的成本远低于一次文件重写，也没有并发覆盖文件的风险。</p>
     */
    /**
     * 单键刷新（历史名字保留）。
     *
     * <p>以前是在侧车文件里替换单行；现在真源就是 prefs，provider 每次查询都重新构建，
     * 所以不需要增量写，只要发一个刷新信号让目标进程立刻重读。</p>
     */
    private static void patchSidecarKey(Context ctx, String key, String value) {
        CfgBridge.push(ctx, TARGET_PKG);
    }

    public static int getFgCap(Context ctx) {
        int v = get(ctx).getInt(KEY_FG_CAP, FG_DEFAULT_CAP);
        if (v <= 0 || v > 1000) {
            return FG_DEFAULT_CAP;
        }
        return v;
    }

    public static void setFgCap(Context ctx, int cap) {
        get(ctx).edit().putInt(KEY_FG_CAP, cap).apply();
        CfgBridge.push(ctx, FG_PKG);
    }

    public static boolean isFgDebug(Context ctx) {
        long now = SystemClock.uptimeMillis();
        if (now - sDebugAt < DEBUG_TTL_MS) {
            return sDebugCached;
        }
        boolean v = false;
        try {
            if (get(ctx).getBoolean(KEY_FG_DEBUG, false)) {
                v = true;
            }
        } catch (Throwable ignored) {
        }
        if (!v) {
            for (String p : DEBUG_FLAG_PATHS) {
                try {
                    if (new File(p).isFile()) {
                        v = true;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        sDebugCached = v;
        sDebugAt = now;
        return v;
    }

    /**
     * 模块 App 进程内的统一日志出口。
     *
     * 调试模式关闭（默认）时 INFO / DEBUG 一律不打，只保留 WARN / ERROR，
     * 这样不打开调试模式时 logcat 干净，需要排障时在设置页打开即可。
     */
    public static void logApp(Context ctx, int prio, String tag, String msg) {
        try {
            if (prio < Log.WARN && !isFgDebug(ctx)) {
                return;
            }
            Log.println(prio, tag, msg);
        } catch (Throwable ignored) {
        }
    }

    /** 调试模式开关（设置页唯一入口；改动即时写偏好 + 下发目标进程） */
    public static void setFgDebug(Context ctx, boolean on) {
        get(ctx).edit().putBoolean(KEY_FG_DEBUG, on).apply();
        setDebugCache(on);
        // 目标进程（互传）走远程偏好；画报走侧车文件里的 debug= 行
        CfgBridge.push(ctx, FG_PKG);
    }

    /** 画报进程自己写状态文件的位置（跨 uid 只能 root 读） */
    public static final String FG_STATUS_ROOT_PATH =
            "/data/data/com.mfashiongallery.emag/files/misharemt2/fg.status";

    /** 用 root 读一个小文本文件；无 root 或失败返回 null。 */
    private static String readFileAsRoot(String path) {
        java.lang.Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
            java.io.InputStream in = proc.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            proc.waitFor();
            String s = new String(bos.toByteArray(), Charset.forName("UTF-8"));
            return s.length() > 0 ? s : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 画报同步状态（原始文本，供设置页展示） */
    public static String readFgStatus(Context ctx) {
        String pushed = StatusReceiver.read(ctx, StatusReceiver.SRC_FG);
        if (pushed != null && pushed.length() > 0) {
            return pushed;
        }
        // 画报进程写不了 /sdcard/Android/media/...（目录属主是模块 app，
        // 且 MediaProvider 禁止通过 content:// 写 Android/ 目录，allowed=[Download,Documents]），
        // 实际状态落在它自己的 files 目录，跨 uid 只能靠 root 读回来。
        String viaRoot = readFileAsRoot(FG_STATUS_ROOT_PATH);
        if (viaRoot != null && viaRoot.length() > 0) {
            return viaRoot;
        }
        for (String p : new String[]{
                "/sdcard/Android/media/com.hyper.Camellia/" + STATUS_NAME_FG,
                "/storage/emulated/0/Android/media/com.hyper.Camellia/" + STATUS_NAME_FG}) {
            try {
                File f = new File(p);
                if (!f.isFile()) {
                    continue;
                }
                byte[] buf = new byte[(int) Math.min(8192, f.length() + 1)];
                FileInputStream in = new FileInputStream(f);
                int n = in.read(buf);
                in.close();
                if (n > 0) {
                    return new String(buf, 0, n, Charset.forName("UTF-8"));
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ---------------- 模块自身外观 ----------------

    /**
     * 主题模式：system / light / dark。
     *
     * 只被设置页自己读取（CamelliaTheme 决定用浅色还是深色方案），
     * 因此不需要推送给目标进程，也不进侧车文件。
     */
    public static String getThemeMode(Context ctx) {
        String v = get(ctx).getString(KEY_THEME_MODE, THEME_SYSTEM);
        if (THEME_LIGHT.equals(v) || THEME_DARK.equals(v)) {
            return v;
        }
        return THEME_SYSTEM;
    }

    public static void setThemeMode(Context ctx, String mode) {
        String v = THEME_LIGHT.equals(mode) || THEME_DARK.equals(mode) ? mode : THEME_SYSTEM;
        get(ctx).edit().putString(KEY_THEME_MODE, v).apply();
    }

    /** 底栏样式：hyperos / floating / glass（默认玻璃，保持旧版观感） */
    public static String getBottomBarStyle(Context ctx) {
        String v = get(ctx).getString(KEY_BOTTOM_BAR, BAR_GLASS);
        if (BAR_HYPEROS.equals(v) || BAR_FLOATING.equals(v)) {
            return v;
        }
        return BAR_GLASS;
    }

    public static void setBottomBarStyle(Context ctx, String style) {
        String v = BAR_HYPEROS.equals(style) || BAR_FLOATING.equals(style) ? style : BAR_GLASS;
        get(ctx).edit().putString(KEY_BOTTOM_BAR, v).apply();
    }

}
