package com.hyper.Camellia;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

/**
 * 安全服务（手机管家）功能解锁 / 阻断业务线。
 *
 * <p>按 HyperCeiler（commit 4f1402b6edac）rules/securitycenter 的定位方式与动作语义
 * 逐项复刻，全部走「字符串锚点 + 结构推导 + 运行期定位」，不硬编码混淆类名。
 *
 * <p>动作语义（与 HyperCeiler 的 returnConstant / before{setResult} 严格等价）：
 * <ul>
 *   <li>{@code A_TRUE/A_FALSE/A_NULL/A_INT100/A_INT0/A_EMPTY_LIST} —— 不执行原方法，直接返回常量</li>
 *   <li>{@code A_ARG_TRUE} —— 把第 0 个参数改为 true 后继续执行原方法</li>
 *   <li>{@code A_SP} —— 由专用安装器处理（类级 matcher / addInvoke / 栈判定等复杂形状）</li>
 * </ul>
 */
public final class Unlock {

    static final String TAG = "MiShareMT2/Unlock";

    /** 所属进程（读取 /proc/self/cmdline） */
    private static volatile String sProc = "-";
    /** 定位来源：dexkit / fallback / cache */
    private static volatile String sVia = "-";
    private static volatile long sCostMs;

    private static XposedModule sMod;
    private static ClassLoader sCl;
    private static ApplicationInfo sAi;
    private static PrefProvider sPrefs;
    private static volatile boolean sReady;
    /** true = 本次安装发生在 com.miui.guardprovider 进程内（决定 gp_* 规则是否生效） */
    private static volatile boolean sIsGuard;

    // BatteryHealth（#27）：跨方法捕获健康度 int + reference_battery_health preference
    private static volatile int sBatteryHealth = -1;
    private static volatile Object sBatteryHealthPref;

    // MoreBatteryInfoNew（#28）：动态偏好行 + 日期/容量（字段 key/标题对齐修改版 13.5.9）
    private static Object mbiCategory;
    private static Object mbiProductionDate;
    private static Object mbiFirstUseDate;
    private static Object mbiDesignCapacity;
    private static Object mbiFullCapacity;
    private static Object mbiHealthPercent;
    private static Object mbiCycleCount;
    private static Context mbiContext;
    private static final String MBI_DESIGN_TITLE = "电池设计容量";
    private static final String MBI_FULL_TITLE = "电池实际容量";
    private static final String MBI_HEALTH_PERCENT_TITLE = "电池健康百分比";
    private static final String MBI_CYCLE_COUNT_TITLE = "电池循环次数";
    private static final String MBI_CAPACITY_FMT = "%1$d mAh";
    private static final String MBI_HEALTH_PERCENT_FMT = "%.1f%%";
    private static final Set<String> MBI_KEEP_KEYS = new LinkedHashSet<String>(Arrays.asList(
            "reference_battery_health", "reference_current_temp", "reference_toady_charge_time",
            "reference_cycle_count", "reference_production_date", "reference_first_use_date",
            "reference_battery_charge_full", "reference_battery_charge_full_design",
            "reference_battery_health_percent", "reference_battery_cycle_count"));

    private static volatile int sOnCount;
    private static volatile boolean sAnyOn;

    public static final String STATUS_NAME = "misharemt2.ul.status";

    /** 「识别 BGM」gate 方法集在签名缓存里的 key（对应 q= 行），缓存路径据此直达。 */
    static final String BGM_GATES_KEY = "camellia-bgm-gates";

    /** 状态诊断：读缓存路径/存在性/命中情况（仅定位用，无功能副作用）。 */
    static volatile String sCacheDiag = "";
    /** 状态诊断：写缓存结果。 */
    static volatile String sCacheSave = "";
    private static final String STATUS_SKIP_NAME = "misharemt2.ul.skip.status";
    private static final String CACHE_NAME = "misharemt2-ul.cache";
    private static final long CFG_TTL_MS = 1500L;

    /** 配置键前缀：与设置页、侧车一致 */
    private static final String CFG_PREFIX = "ul_";
    /** 禁止展示推荐应用的门控键。 */
    private static final String SUGGEST_GATE_KEY = CFG_PREFIX + "sidebar_suggest";
    /** 视频工具箱「识别 BGM」菜单项可见性开关（原有规则 video_bgm）。 */
    private static final String BGM_MENU_KEY = CFG_PREFIX + "video_bgm";
    /** 「识别 BGM 改用网易云听歌识曲」开关（复用 video_bgm 的菜单可见性）。 */
    private static final String BGM_NETEASE_KEY = CFG_PREFIX + "video_bgm_netease";
    /** 「识别 BGM 改用第三方识曲」规则键（选择器 = video_bgm_app）。 */
    private static final String BGM_APP_KEY = CFG_PREFIX + "video_bgm_app";
    /** 选中的识曲 App：字符串配置。沿用 ul_ 前缀，侧车按整行替换才不会累积重复行。 */
    static final String BGM_APP_VALUE_KEY = CFG_PREFIX + "bgm_app";
    /**
     * 侧边栏快捷方式面板：全局屏蔽关键词（字符串配置）。
     *
     * <p>与开关一样沿用 {@code ul_} 前缀：侧车按「整行替换」重写，前缀统一才能删干净旧行。</p>
     */
    static final String SIDEBAR_SC_BLOCK_KEY = CFG_PREFIX + "sidebar_sc_block";
    /** 侧边栏快捷方式面板：应用专属屏蔽规则，形如 {@code 应用名或包名 = 关键词1, 关键词2}（分号分隔多条）。 */
    static final String SIDEBAR_SC_BLOCK_APP_KEY = CFG_PREFIX + "sidebar_sc_block_app";
    /** 识曲 App 取值：小米音乐（原生行为，不接管跳转） */
    public static final String BGM_APP_MIUI = "miui";
    /** 识曲 App 取值：网易云音乐 */
    public static final String BGM_APP_NETEASE = "netease";
    /** 识曲 App 取值：酷狗音乐 */
    public static final String BGM_APP_KUGOU = "kugou";
    /** 识曲 App 取值：酷狗音乐极速版 */
    public static final String BGM_APP_KUGOU_LITE = "kugou_lite";
    /** 识曲 App 取值：QQ 音乐 */
    public static final String BGM_APP_QQ = "qqmusic";
    /** 全部取值；顺序与设置页选择器一致 */
    public static final String[] BGM_APPS = {
            BGM_APP_MIUI, BGM_APP_NETEASE, BGM_APP_KUGOU, BGM_APP_KUGOU_LITE, BGM_APP_QQ};

    /** 目标包名（静态作用域内由 Main 分发） */
    public static final String PKG = "com.miui.securitycenter";

    /**
     * 是否属于手机管家（含子进程）。
     *
     * 手机管家的业务分散在多个进程：
     * com.miui.securitycenter / :ui / .power / .remote / .cache / .bootaware / :cache。
     * onPackageLoaded 的包名在部分框架实现里带进程后缀，因此这里同时接受
     * 「等于包名」「以 <包名> 开头且后跟 . 或 :」两种形态。
     */
    public static boolean matchPkg(String pkg) {
        if (pkg == null) {
            return false;
        }
        if (pkg.equals(PKG)) {
            return true;
        }
        if (pkg.length() <= PKG.length() || !pkg.startsWith(PKG)) {
            return false;
        }
        char c = pkg.charAt(PKG.length());
        return c == '.' || c == ':';
    }

    /**
     * 系统安全组件（com.miui.guardprovider）。
     *
     * 它与手机管家是两个独立 APK，但同属 MIUI 安全组件：负责设备状态上报、
     * 应用清单上传与云端指令下发（MiPush 透传）。本组八条规则里七条只在该进程生效，
     * 剩下的 gp_dbguard 挂在手机管家进程（行为库由管家写入）。
     */
    public static final String PKG_GP = "com.miui.guardprovider";

    /** 是否属于系统安全组件（含子进程），判定口径同 {@link #matchPkg(String)} */
    public static boolean matchGuardPkg(String pkg) {
        if (pkg == null) {
            return false;
        }
        if (pkg.equals(PKG_GP)) {
            return true;
        }
        if (pkg.length() <= PKG_GP.length() || !pkg.startsWith(PKG_GP)) {
            return false;
        }
        char c = pkg.charAt(PKG_GP.length());
        return c == '.' || c == ':';
    }

    // ───────────────────────────── 动作 ─────────────────────────────

    static final int A_TRUE = 1;        // 返回 true
    static final int A_FALSE = 2;       // 返回 false
    static final int A_INT100 = 3;      // 返回 100
    static final int A_SKIP = 4;        // 不执行原方法（void）
    static final int A_ARG0 = 5;        // 返回第 0 个参数
    static final int A_NULL = 6;        // 返回 null
    static final int A_ARG_TRUE = 7;    // 第 0 个参数 = true，继续执行
    static final int A_EMPTY_LIST = 8;  // 返回 new ArrayList()
    static final int A_INT0 = 9;        // 返回 0
    static final int A_SP = 99;         // 专用安装器

    // ───────────────────────────── 分组 ─────────────────────────────

    public static final String G_BATTERY = "battery";
    public static final String G_CAMERA = "camera";
    public static final String G_PRIVACY = "privacy";
    public static final String G_SIDEBAR = "sidebar";
    public static final String G_OTHER = "other";
    public static final String G_GAME = "game";
    public static final String G_VIDEO = "video";
    /** 系统安全组件（com.miui.guardprovider）：设备状态 / 应用清单 / 云端指令拦截 */
    public static final String G_GUARD = "guard";
    /** 隐私防护：把安全组件 / 手机管家 / 云端引擎的域网上报通道按域名掐断 */
    public static final String G_PRIVGUARD = "privguard";

    public static final String[] GROUPS = {
            G_BATTERY, G_CAMERA, G_PRIVACY, G_SIDEBAR, G_OTHER, G_GAME, G_VIDEO, G_GUARD, G_PRIVGUARD};

    public static String groupLabel(String g) {
        switch (g) {
            case G_BATTERY: return "省电与电池";
            case G_CAMERA:  return "前置摄像助手";
            case G_PRIVACY: return "隐私与安全";
            case G_SIDEBAR: return "全局侧边栏";
            case G_OTHER:   return "其他";
            case G_GAME:    return "游戏工具箱";
            case G_VIDEO:   return "视频工具箱";
            case G_GUARD:   return "安全组件数据与指令拦截";
            case G_PRIVGUARD:   return "隐私防护";
            default:        return g;
        }
    }

    public static String groupDesc(String g) {
        switch (g) {
            case G_BATTERY: return "充电能力判定、详细耗电数据与耗电排行";
            case G_CAMERA:  return "前置摄像助手 / 隐私摄像模式的机型限制";
            case G_PRIVACY: return "支付保护、Root 检测、安装拦截与无障碍保护";
            case G_SIDEBAR: return "手柄样式、背景模糊、推荐应用、数量上限与小窗 / 快捷方式面板";
            case G_OTHER:   return "晕车舒缓、评分、授权倒计时与 SIM 卡锁";
            case G_GAME:    return "侧边栏内的游戏工具箱：狂暴模式、准星与连招黑名单";
            case G_VIDEO:   return "侧边栏内的视频工具箱：独显、杜比与视频增强";
            case G_GUARD:   return "拦截安全组件（guardprovider）与手机管家（securitycenter）的数据回传：应用清单、设备状态、使用统计、AI 检测结果；掐掉云端下发的采集 / 停用 / 改配置指令；挡住往本地行为库写行为记录，以及录屏抓屏取画面。八项里六项默认开启；「禁止云端查毒」与「禁止录屏抓屏」默认关闭（前者关掉会失去云端病毒库查询，后者会让录屏 / 投屏类应用取不到画面、个别应用可能报错），需要时单独打开。改完重启手机管家与安全组件即可生效";
            case G_PRIVGUARD:   return "按域名在网络出口掐断安全组件、手机管家与云端引擎的上报通道：sec.miui.com、flash.sec.miui.com、病毒引擎与反欺诈引擎、安天日志上报。打开后这几个域名一律连不上，依赖云端判定的「云端查毒」「在线反欺诈」会同步失效；只作用于上述域名，其它联网照常，本机功能不拦";
            default:        return "";
        }
    }

    // ───────────────────────────── 规则模型 ─────────────────────────────

    static final class Rule {
        final String id;      // 配置键后缀（ul_<id>）+ 上报名
        final String group;
        final String label;
        final int act;
        /** 字符串锚点：'|' 表示 OR，'&' 表示同一方法内 AND； 表示不限 */
        String anchor;
        /** 声明类字符串锚点（与方法锚点独立） */
        String cls;
        /** 期望返回类型描述符，如 "Z" / "I" / null（不限） */
        String ret;
        /** 期望参数类型描述符；=不限，空数组=零参 */
        String[] params;
        /** 数字锚点 */
        int[] nums;
        /** 声明类名前缀 */
        String owner;
        /** 回退：硬编码类名（仅在锚点全部失效时使用，且必须容错） */
        String fbOwner;
        /** 回退：硬编码方法名（"," 分隔多候选） */
        String fbName;
        /**
         * 默认开启：当 prefs 与侧车里都查不到该键时，按「开启」处理。
         *
         * <p>只给 gp_*（系统安全组件）里需要默认开启的那几条用。手机管家规则保持历史语义——无配置即关闭，
         * 这样老用户不会因为模块升级突然多出一堆开启的规则。</p>
         */
        boolean defOn;

        volatile int state;   // 0=未定位 1=已 hook 2=未命中 3=失败
        volatile int hits;
        volatile String where = "";

        /**
         * 「原生已支持」采样结果（影子调用原方法拿到真实返回值后与 hook 目标值对比）：
         * 0=未采样 1=原生值与目标值一致（本机型/本版本本来就支持，启用与否没有区别）
         * 2=原生值不同（hook 确实改变了行为） 3=不适用（动作类型不适合判定）或采样抛异常
         * 4=采样进行中（占位，避免并发重复 proceed）
         */
        volatile int natState;
        /** 原生返回值文本（调试展示） */
        volatile String natVal = "";

        Rule(String id, String group, String label, int act, String anchor) {
            this.id = id;
            this.group = group;
            this.label = label;
            this.act = act;
            this.anchor = anchor;
        }

        Rule cls(String v) { this.cls = v; return this; }
        Rule ret(String v) { this.ret = v; return this; }
        Rule params(String... v) { this.params = v; return this; }
        Rule nums(int... v) { this.nums = v; return this; }
        Rule owner(String v) { this.owner = v; return this; }
        Rule fb(String owner, String name) { this.fbOwner = owner; this.fbName = name; return this; }
        /** 标记该规则「无配置时默认开启」（见 {@link #defOn}）。 */
        Rule defOn() { this.defOn = true; return this; }
    }

    /**
     * 表驱动规则（形状简单：字符串锚点 + 可选返回类型/参数/数字约束）。
     * 复杂形状（类级 matcher、addInvoke、栈判定、多方法联动）走 installSpecial()。
     */
    private static final Rule[] RULES = {
            // 省电与电池
            new Rule("low_temp", G_BATTERY, "解锁低温极限续航", A_TRUE,
                    "low_temperature_extreme_endurance_support"),
            new Rule("smart_charge", G_BATTERY, "补全充电与电池功能", A_TRUE,
                    "persist.vendor.smartchg"),
            new Rule("swl_charge", G_BATTERY, "解锁极速无线充电", A_TRUE,
                    "persist.vendor.tx.speed.control").ret("Z"),
            // 前置摄像助手
            new Rule("beauty_face", G_CAMERA, "解锁自动开启前置摄像", A_TRUE,
                    "taoyao").ret("Z").params(),
            new Rule("beauty_pc", G_CAMERA, "解锁前置摄像助手", A_TRUE,
                    "persist.vendor.camera.facetracker.support").ret("Z"),
            // 隐私与安全
            new Rule("hide_xopt", G_PRIVACY, "去除敏感权限使用提醒的小绿点", A_TRUE, null)
                    .cls("get third desktop provider exception!").ret("Z").params(),
            new Rule("safepay", G_PRIVACY, "关闭安全支付自动扫描", A_FALSE,
                    "key_safepay_auto_scan_state").params(),
            new Rule("root_check", G_PRIVACY, "关闭安全扫描 Root 检测", A_FALSE,
                    "/system/bin/").ret("Z"),
            // #39 other/DisableRootCheck：w4/l.o()Z = IS_ALPHA_BUILD && pref(key_check_item_root)
            new Rule("root_item", G_PRIVACY, "关闭安全中心 Root 检测项", A_FALSE,
                    "key_check_item_root").ret("Z"),
            new Rule("install_intercept", G_PRIVACY, "关闭安装拦截", A_FALSE,
                    "permcenter_install_intercept_enabled").ret("Z"),
            new Rule("acc_auto_off", G_PRIVACY, "阻止自动关闭无障碍", A_NULL,
                    "failed to close accessibility"),
            // 其他
            // 晕车舒缓「解锁」= 功能总开关 e()（FeatureParser.getBoolean("support_travel_comfort_solution")）
            // 只解锁功能、绝不 hook g(Context)/q(Context)（开关状态读写），避免锁死常开
            new Rule("car_sickness", G_OTHER, "解锁晕车舒缓", A_TRUE,
                    "support_travel_comfort_solution").ret("Z"),
            new Rule("power_saver", G_OTHER, "修复紧致模式", A_NULL,
                    "hang_up_enable"),
            // #40 FuckRiskPkg 13.6.3：风险应用描述列表数据源 = RiskyAppManager 内部
            // AsyncTask mi/b$c.c()（DB 读 risk_app_description_list）。return null 只移除
            // 通知/列表展示，不碰开关状态（key_risk_application_warning_enable）。
            new Rule("risk_pkg", G_OTHER, "移除风险应用通知", A_NULL,
                    "SELECT MAX(dataVersion) FROM risk_app_description_list")
                    .ret("Lcom/miui/riskyapp/model/RiskyAppDescriptionList;").params(),
            // #29 DisableReport：移除举报键（举报入口恒返回 false）。
            // 锚点：方法内同时含 "android.intent.action.VIEW" 与 "com.xiaomi.market"，返回 boolean。
            new Rule("disable_report", G_OTHER, "移除举报键", A_FALSE,
                    "android.intent.action.VIEW&com.xiaomi.market").ret("Z"),
            // #11 IsSbnBelongToActiveBubbleApp：气泡通知归属判定恒 true（类名/方法名均为
            // 语义名，与 HyperCeiler 一致硬编码，非混淆名）
            new Rule("sbn_bubble", G_OTHER, "气泡应用归属判定", A_TRUE, null)
                    .fb("com.miui.bubbles.settings.BubblesSettings",
                            "isSbnBelongToActiveBubbleApp")
                    .ret("Z").params("Ljava/lang/String;", "I"),
            // 游戏工具箱
            new Rule("wild_boost", G_GAME, "解锁狂暴模式", A_TRUE,
                    "support_wild_boost"),
            new Rule("gunsight", G_GAME, "解锁准星服务", A_TRUE, null)
                    .cls("gb_game_collimator_status").ret("Z").params("Ljava/lang/String;"),
            new Rule("game_toast", G_GAME, "移除游戏模式提示", A_NULL,
                    "showWildModeToastView: |cancel game toast , isCanceled : "),
            // 视频工具箱
            // 禁用移除息屏听剧与熄屏挂机（HyperCeiler DisableRemoveScreenHoldOn）：
            // 手机管家用策略串 remove_screen_off_hold_on 判定「息屏听剧/熄屏挂机」是否被移除，
            // 恒返 false 即让系统认为该能力保持可用，于是不再移除这两项。
            new Rule("screen_hold", G_VIDEO, "禁用移除息屏听剧与熄屏挂机", A_FALSE,
                    "remove_screen_off_hold_on").ret("Z"),
            new Rule("vb_mode", G_VIDEO, "解锁视频通话模式", A_TRUE,
                    "TheatreModeUtils").nums(32),
    };

    /** 专用项（复杂形状）；其 id/group/label/act 仍登记在此，供设置页与状态展示 */
    private static final Rule[] SPECIALS = {
            new Rule("picture_enhance", G_GAME, "画面增强支持判定", A_SP, null),
            new Rule("dual_visual", G_VIDEO, "双芯视效支持判定", A_SP, null),
            new Rule("video_more", G_VIDEO, "视频工具箱更多功能（组合）", A_SP, null),
            new Rule("video_cast", G_VIDEO, "显示视频投屏", A_SP, null),
            new Rule("video_screen_off", G_VIDEO, "显示息屏听剧", A_SP, null),
            new Rule("video_subtitle", G_VIDEO, "显示 AI 字幕", A_SP, null),
            new Rule("video_settings", G_VIDEO, "显示视频工具箱设置", A_SP, null),
            new Rule("video_bgm", G_VIDEO, "显示识别 BGM", A_SP, null),
            // 把视频工具箱「识别 BGM」的落地页从小米音乐换成网易云音乐听歌识曲：
            // 拦下 h0#Y(Context, Intent, pkg) 里的 miui-music://radar Intent，改为用
            // MiuiMultiWindowUtils 小窗 ActivityOptions 启动 orpheus://identify
            // （com.netease.cloudmusic 的 IdentifyActivityV2），失败才回退原小米音乐流程。
            new Rule("video_bgm_app", G_VIDEO, "识别 BGM 改用第三方识曲", A_SP, null),
            new Rule("bypass_plus", G_GAME, "旁路充电 Plus 支持判定", A_SP, null),
            new Rule("video_dfps", G_VIDEO, "智能刷新率默认支持", A_SP, null),
            new Rule("game_hdr", G_GAME, "解锁 Game HDR 支持判定", A_SP, null),
            new Rule("game_fill", G_GAME, "解锁游戏填充展示", A_SP, null),
            new Rule("game_fill_blacklist", G_GAME, "绕过游戏填充黑名单", A_SP, null),
            new Rule("cooling_fan", G_GAME, "解锁散热风扇", A_SP, "fan_support"),
            new Rule("gpu_enhance", G_GAME, "解锁独显增强", A_SP, null),
            new Rule("game_quick_start", G_GAME, "解锁游戏 3 秒极速启动", A_SP,
                    "com.tencent.tmgp.sgame&com.tencent.tmgp.pubgmhd"),
            new Rule("car_accident", G_OTHER, "解锁车祸检测入口", A_SP, null),
            new Rule("swl_tip", G_BATTERY, "无线快充状态判定", A_SP,
                    "key_is_connected_super_wls_tx"),
            new Rule("screen_used_time", G_BATTERY, "显示详细耗电数据", A_SP,
                    "ishtar&nuwa&fuxi"),
            new Rule("power_rank", G_BATTERY, "恢复耗电排行", A_SP,
                    "%d %s %d %s"),
            new Rule("beauty_privacy", G_CAMERA, "解锁隐私摄像模式", A_SP,
                    "persist.sys.privacy_camera"),
            new Rule("offline_info", G_PRIVACY, "网络助手离线信息标注", A_SP, null)
                    .fb("com.miui.networkassistant.ui.bean.OffLineData$BaseData", null),
            new Rule("car_sickness_tip", G_OTHER, "晕车舒缓提示时机", A_SP,
                    "is_support_carsick"),
            new Rule("score", G_OTHER, "安全中心评分锁定 666", A_SP, null)
                    .fb("com.miui.securityscan.scanner.ScoreManager", null),
            new Rule("skip_countdown", G_OTHER, "跳过授权倒计时", A_SP, null)
                    .fb("com.miui.permcenter.privacymanager.InterceptBaseFragment", null),
            new Rule("simlock_auth", G_OTHER, "跳过 SIM 卡锁账号验证", A_SP,
                    "SimLockUtils"),
            new Rule("simlock_dialog", G_OTHER, "移除 SIM 卡锁成功弹窗", A_SP, null)
                    .fb("com.miui.simlock.activity.SuccessDialogActivity", "onCreate"),
            new Rule("privacy_blur", G_OTHER, "新版隐私模糊缩略图", A_SP,
                    "miui_recents_privacy_thumbnail_blur"),
            new Rule("macro_blacklist", G_GAME, "移除自动连招黑名单", A_SP,
                    "pref_gb_unsupport_macro_apps"),
            // 倍速进入游戏：强制开启 debug.game.video.speed（NuPlayer 启动视频 ×3.0 变速）
            new Rule("game_speed", G_GAME, "强制开启游戏视频加速", A_SP,
                    "debug.game.video.speed"),
            new Rule("dolby", G_VIDEO, "解锁杜比音效", A_SP,
                    "dolby").cls("checkMiGamePermission error"),
            new Rule("video_func", G_VIDEO, "解锁视频增强功能", A_SP,
                    "ro.vendor.media.video.frc.support"),
            // #42 NoLowBatteryWarning：hook Settings.System.getInt/getString，按 key 条件返回
            new Rule("no_low_battery", G_OTHER, "移除低电量警告", A_SP, null),
            // #44 SimplifyMainFragment：过滤主页广告/热门卡片
            new Rule("simplify_main", G_OTHER, "简化主页卡片", A_SP, null),
            // #27 BatteryHealth：跨 3 个方法捕获健康度 int 并写入 reference_battery_health
            new Rule("battery_health", G_BATTERY, "显示电池健康度", A_SP, null),
            // #28 MoreBatteryInfoNew：显示制造日期/首次使用/设计容量/实际容量
            new Rule("more_battery_info", G_BATTERY, "显示更多电池信息", A_SP, null),
            // #10 AppLockPinScramble：应用锁 PIN 键盘乱序（hook MiuiNumericInputView 构造器）
            new Rule("app_lock_scramble", G_PRIVACY, "应用锁 PIN 键盘乱序", A_SP, null)
                    .fb("com.miui.applicationlock.widget.MiuiNumericInputView", null),
            // #21 RemoveOpenAppConfirmationPopup：移除链式启动确认弹窗（自动确认）
            new Rule("open_app_popup", G_OTHER, "移除链式启动弹窗", A_SP, null),
            // #18 BypassAdbInstallVerify：绕过 USB 安装网络校验
            new Rule("adb_install_bypass", G_PRIVACY, "绕过 USB 安装校验", A_SP,
                    "https://srv.sec.miui.com/data/adb"),
            // #30 AppRestrict：解除系统应用限制（应用管理/防火墙）
            new Rule("app_restrict", G_OTHER, "解除系统应用限制", A_SP, null)
                    .fb("com.miui.appmanager.AppManageUtils", null),
            // #28 AppDefaultSort：应用管理列表默认排序
            new Rule("app_default_sort", G_OTHER, "应用列表默认排序", A_SP, null)
                    .fb("com.miui.appmanager.AppManagerMainActivity", "onCreate"),
            // #5 气泡应用限制解除（气泡列表注入 + 默认气泡全量加载）
            new Rule("bubble_unlock", G_OTHER, "解除气泡应用限制", A_SP, null)
                    .fb("com.miui.bubbles.settings.BubblesSettings", null),
            // #36 ShowBatteryTemperatureNew：显示电池温度
            new Rule("battery_temp", G_BATTERY, "显示电池温度", A_SP, null)
                    .fb("com.miui.powercenter.BatteryFragment", null),
            // #33 AddAppManagerEntry：应用管理菜单添加 AOSP 应用管理入口
            new Rule("aosp_app_manager", G_OTHER, "AOSP 应用管理入口", A_SP, null)
                    .fb("com.miui.appmanager.AppManagerMainActivity", "onCreateOptionsMenu"),
            // #32 AddAppInfoEntry：应用详情菜单添加 AOSP 应用信息入口
            new Rule("aosp_app_info", G_OTHER, "AOSP 应用信息入口", A_SP, null)
                    .fb("com.miui.appmanager.ApplicationsDetailsActivity", "onCreateOptionsMenu"),
            // 策反XSOF内鬼：安全服务（XSOF）的设备可信 / 风险应用 / 模拟触控检测
            // 直接回良性结论，第三方 App 拿到的就不再是真实环境结论
            new Rule("xsof_block", G_PRIVACY, "策反XSOF内鬼", A_SP, null),
            // 去除视频工具箱高温限制：接管板温读数，让 41℃ 温度墙永不触发
            new Rule("video_temp", G_VIDEO, "去除视频工具箱高温限制", A_SP,
                    "read board temp fail, node="),
            // 隐藏侧边栏手柄：复刻 HyperCeiler AddSideBarExpandReceiver。
            // hook 手机管家自带的 SystemUI 采样器 RegionSamplingHelper，
            // 抹掉侧边栏手势线的绘制，并注册广播（可只手唤起侧边栏）。
            new Rule("hide_sidebar", G_SIDEBAR, "隐藏侧边栏手柄", A_SP, null),
            // 全局侧边栏背景自定义：复刻 HyperCeiler BlurSecurity 的 dock 应用栏处理。
            // 反射接管 MIUI 挂在 View 上的背景模糊私有 API，给侧边栏本体叠加自定义背景色与模糊。
            new Rule("sidebar_bg", G_SIDEBAR, "全局侧边栏背景自定义", A_SP, null),
            // 禁止展示推荐应用：HyperCeiler 侧只有开关没有实现，这里自研。
            // 侧边栏「全部应用」页由 com.miui.dock.allapps.w 承载，其布局引用了
            // suggest_app / suggest_1..8（推荐应用区），挂到窗口后把这些视图置 GONE 即可。
            new Rule("sidebar_suggest", G_SIDEBAR, "禁止展示推荐应用", A_SP, null),
            new Rule("sidebar_limit", G_SIDEBAR, "解锁侧边栏应用数量限制", A_SP, null),
            // 侧边栏快捷项：长按侧边栏应用弹出的菜单里加入「小窗」一项（原生只有全屏/分屏）。
            // 在 DockShortCutMenu 装配列表后注入条目，点击时用小窗参数包启动目标应用。
            new Rule("sidebar_freeform", G_SIDEBAR, "侧边栏快捷项：小窗", A_SP, null),
            // 侧边栏快捷方式面板：长按应用时在菜单旁挂一个面板，列出该应用的 Launcher 快捷方式，
            // 点按即以小窗启动（移植自样本 APK 的 SidebarShortcut 实现，hook 菜单 setVisibility）。
            new Rule("sidebar_shortcut", G_SIDEBAR, "侧边栏快捷方式面板", A_SP, null),

            // ── 系统安全组件（com.miui.guardprovider）──
            // 以下八条按进程分流：gp_dbguard 挂在 securitycenter 进程（sIsGuard==false 时生效），
            // 其余七条在 guardprovider 进程内安装，靠 sIsGuard 判定。
            // 命中进程不匹配时直接返回 0，不拿不存在的锚点空跑一遍全量 DexKit 查询。
            // 锚点语义：'&' = 同一方法内同时出现（见 spGpPush 的两段日志前缀）。
            // 默认开启（.defOn()）= 装好即生效，用户可在设置页显式关掉；
            // 显式关闭会写进 prefs/侧车，之后就以配置为准，不再回落到默认值。
            new Rule("gp_applist", G_GUARD, "禁止上传应用清单", A_SP,
                    "https://flash.sec.miui.com/detect/app").defOn(),
            new Rule("gp_devstate", G_GUARD, "禁止上报设备状态", A_SP,
                    "Reported device_state anomaly").defOn(),
            new Rule("gp_push", G_GUARD, "禁止接收云端指令", A_SP,
                    "unknown command: &failed to parse params: ").defOn(),
            // 以下三条（gp_track / gp_aigc / gp_cloudscan）是对照 HyperPGuard 的 guardprovider 段做的补齐，但目标按本机
            // APK 的实测结果重选，不照抄它的域名清单：
            //   · fraud-detection.engine.miui.com、logupdate.avlyun.sec.miui.com
            //     在本机 guardprovider.dex 里字符串出现 0 次（avlyun 引擎整体已不存在），
            //     照抄只会得到两条永远「未命中」的死规则，故不加；
            //   · api.sec.miui.com 的 /antivirus/viruses 是病毒库下载、/antivirus/whitelist
            //     是白名单拉取，属取数据而非上报，拦了会降低查杀能力，故不加。
            new Rule("gp_track", G_GUARD, "禁止上报使用统计", A_SP, null).defOn(),
            new Rule("gp_aigc", G_GUARD, "禁止上传 AI 检测结果", A_SP,
                    "detection-aigc.engine.miui.com").defOn(),
            // 云查杀：拦掉会失去云端病毒库查询，宁可默认关闭，由用户显式开启
            new Rule("gp_cloudscan", G_GUARD, "禁止云端查毒", A_SP,
                    "virus-engine.sec.miui.com"),
            // 行为库写入拦截：行为记录落在 securitycenter 的 fraud_detection_data.db，
            // 不走 DexKit 锚点（直接反射系统 SQLiteDatabase），故锚点传 null。
            new Rule("gp_dbguard", G_GUARD, "禁止写入行为库", A_SP, null).defOn(),
            // 录屏抓屏拦截：hook MediaProjection.createVirtualDisplay 返回 null。
            // 默认关闭 —— 调用点拿到 null 后是否判空尚未确认，开启有崩溃风险。
            new Rule("gp_capture", G_GUARD, "禁止录屏抓屏", A_SP, null),
    new Rule("pv_net_sec", G_PRIVGUARD, "拦截安全组件域名上报", A_SP, "sec.miui.com|flash.sec.miui.com|virus-engine.sec.miui.com|fraud-detection.engine.miui.com|logupdate.avlyun.sec.miui.com"),
    };

    /** 设置页用：全部规则（表驱动 + 专用） */
    public static List<Rule> allRules() {
        List<Rule> out = new ArrayList<Rule>(RULES.length + SPECIALS.length);
        out.addAll(Arrays.asList(RULES));
        out.addAll(Arrays.asList(SPECIALS));
        return out;
    }

    public static List<Rule> rulesOf(String group) {
        List<Rule> out = new ArrayList<Rule>();
        for (Rule r : allRules()) {
            if (r.group.equals(group)) out.add(r);
        }
        return out;
    }

    /**
     * 设置页用：把规则表拍平成行数据，避免 UI 侧直接触碰 Rule 类型。
     * 每行 6 列：id / group / label / state / hits / where
     */
    public static List<String[]> ruleRows() {
        List<String[]> out = new ArrayList<String[]>();
        for (Rule r : allRules()) {
            out.add(new String[]{r.id, r.group, r.label, String.valueOf(r.state),
                    String.valueOf(r.hits), r.where});
        }
        return out;
    }

    /** 分组统计：[总项数, 已开启数] */
    public static int[] groupStat(String group) {
        int total = 0;
        int on = 0;
        for (Rule r : allRules()) {
            if (!r.group.equals(group)) {
                continue;
            }
            total++;
            if (cfg(r)) {
                on++;
            }
        }
        return new int[]{total, on};
    }

    /** 全部规则分组键（设置页按此顺序渲染） */
    public static String[] groupKeys() {
        return GROUPS.clone();
    }

    // ───────────────────────────── 配置 ─────────────────────────────

    /** 由 Main 提供：跨进程偏好 */
    public interface PrefProvider {
        SharedPreferences prefs();
    }

    private static final Map<String, String> SIDECAR = new LinkedHashMap<String, String>();
    /** remote prefs 开关：位于 /data，读取极快，允许在主线程（含启动关键路径）使用 */
    private static final ConcurrentHashMap<String, Boolean> PK = new ConcurrentHashMap<String, Boolean>();
    /** /sdcard 侧车开关：FUSE 慢 I/O，只允许后台线程写入 */
    private static final ConcurrentHashMap<String, Boolean> SK = new ConcurrentHashMap<String, Boolean>();
    /** remote prefs 里的字符串配置（识曲 App 选择等）；侧车兜底，热路径只做哈希查表 */
    private static final ConcurrentHashMap<String, String> PS = new ConcurrentHashMap<String, String>();
    private static final Object CFG_LOCK = new Object();
    private static volatile long sCfgAt;
    /** 快照对应的配置失效代际（{@link Prefs#cfgGen()}），代际一变就立刻重建 */
    private static volatile long sCfgGen = -1L;
    private static volatile String sCfgSrc = "-";

    static boolean cfg(Rule r) {
        // defOn（gp_* 的默认开启项）在「从未配置过」时视为开启，显式关闭后即以配置为准。
        return cfgOr(CFG_PREFIX + r.id, r.defOn);
    }

    /**
     * 是否需要重建配置快照。
     *
     * <p>两个条件任一成立：TTL（1.5 秒）到期，或者模块 app 侧报来新的配置代际
     * （{@link Prefs#cfgGen()}，由 CfgProvider 的 notifyChange 驱动）。后者让
     * 「设置页一改、目标进程当轮就生效」，不用等 TTL 过期。</p>
     */
    private static boolean needRefresh(long now) {
        return now - sCfgAt > CFG_TTL_MS || sCfgGen != Prefs.cfgGen();
    }

    /**
     * 读布尔配置，<b>能区分「未配置」和「配置为 false」</b>。
     *
     * <p>顺序与 {@link #cfgBool} 完全一致：remote prefs（设置页实时下发）优先，
     * 其次侧车快照（guardprovider 等子进程的唯一配置来源），都没有就返回 null。</p>
     */
    static Boolean cfgOrNull(String key) {
        long now = SystemClock.uptimeMillis();
        if (needRefresh(now)) {
            synchronized (CFG_LOCK) {
                if (needRefresh(now)) {
                    // 先把时间戳推进，避免 refreshPrefs 内部任何日志回调再次进入刷新。
                    sCfgAt = now;
                    refreshPrefs();
                }
            }
        }
        Boolean b = PK.get(key);
        if (b != null) return b;
        return SK.get(key);
    }

    static boolean cfgBool(String key) {
        Boolean b = cfgOrNull(key);
        return b != null && b;
    }

    /** 无配置时按 {@link Rule#defOn} 决定的默认值，其余规则仍等价于 {@code cfgBool}。 */
    static boolean cfgOr(String key, boolean defVal) {
        Boolean b = cfgOrNull(key);
        return b != null ? b : defVal;
    }

    /**
     * 读取字符串配置（侧边栏快捷方式屏蔽关键词等）。
     *
     * <p>与 {@link #cfgBool} 共用同一套 TTL 刷新：remote prefs 命中优先（设置页改完
     * 1.5 秒内生效），否则回退侧车文本（目标进程读不到模块 prefs 时的唯一配置源）。
     * 查表本身是纯内存操作，不做文件 I/O，因此可以在菜单装配路径上调用。</p>
     */
    static String cfgText(String key) {
        long now = SystemClock.uptimeMillis();
        if (needRefresh(now)) {
            synchronized (CFG_LOCK) {
                if (needRefresh(now)) {
                    sCfgAt = now;
                    refreshPrefs();
                }
            }
        }
        String v = PS.get(key);
        if (v == null) v = SIDECAR.get(key);
        return v == null ? "" : v.trim();
    }

    /**
     * 热路径开关查询：只查内存缓存，不做任何刷新（新鲜度由 {@link #startCfgWatch} 轮询保证）。
     *
     * <p>hook 回调里做文件/绑定器调用会拖慢宿主，所以这里必须是纯内存查表。
     * PK 命中时直接以 remote prefs 为准；否则回退侧车（目标进程唯一可读的配置源）。</p>
     *
     * <p>这个方法每次拦截都会走一遍（关闭开关也要靠它放过原逻辑），因此键不允许
     * 在回调里现拼 —— {@link Hooker} 在挂载时就把 {@code CFG_PREFIX + id} 算好，
     * 回调里只做两次哈希查表，不再产生字符串分配与 hashCode 重算。</p>
     */
    static boolean ruleOnKey(String k) {
        if (k == null) return true;
        Boolean b = PK.get(k);
        if (b != null) return b;
        Boolean s = SK.get(k);
        if (s != null && s) return true;
        // 兜底 1：「显示识别 BGM」在任一「识曲接管」开关打开时同样视为可见。
        // 菜单可见性与可点性主要由接管规则自己的 hook（恒 true 的 mb.o#c）解决；
        // 万一跨版本定位不到该使能方法，这里仍让原规则保证入口出现。
        if (BGM_MENU_KEY.equals(k)) {
            return ruleOnKey(BGM_APP_KEY) || ruleOnKey(BGM_NETEASE_KEY);
        }
        // 兜底 2：「识别 BGM 改用第三方识曲」（video_bgm_app）。同时兼容两种中间态：
        //   · 旧版本只有 video_bgm_netease 这一个布尔开关（升级后不该失效）；
        //   · 只写了 App 选择字符串、布尔开关没跟上（写 prefs 与写侧车之间被打断）。
        if (BGM_APP_KEY.equals(k)) {
            Boolean p = PK.get(BGM_NETEASE_KEY);
            if (p != null && p) return true;
            Boolean q = SK.get(BGM_NETEASE_KEY);
            if (q != null && q) return true;
            String v = normalizeBgmApp(PS.get(BGM_APP_VALUE_KEY));
            if (v == null) v = normalizeBgmApp(SIDECAR.get(BGM_APP_VALUE_KEY));
            return v != null && !BGM_APP_MIUI.equals(v);
        }
        return false;
    }

    /** 归一化识曲 App 取值；空值或未知值返回 null，由调用方决定回退。 */
    public static String normalizeBgmApp(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < BGM_APPS.length; i++) {
            if (BGM_APPS[i].equals(s)) return s;
        }
        return null;
    }

    /**
     * 当前选中的识曲 App（目标进程内调用）。
     *
     * <p>只在用户点「识别 BGM」时调一次，因此这里借 {@link #cfgBool} 的 1.5s TTL
     * 顺手刷新一次配置缓存：设置页改完选择后无需重启手机管家即可生效。</p>
     */
    public static String bgmApp() {
        cfgBool(BGM_APP_VALUE_KEY); // 仅为触发 TTL 刷新，返回值不用
        String v = normalizeBgmApp(PS.get(BGM_APP_VALUE_KEY));
        if (v == null) v = normalizeBgmApp(SIDECAR.get(BGM_APP_VALUE_KEY));
        if (v != null) return v;
        // 没写过选择（首次打开开关）：按网易云处理；规则关闭时才回落到小米音乐原生行为。
        // 这里必须用「规则是否开启」判断，不能只看旧的 video_bgm_netease 开关，
        // 否则「刚开开关、还没进设置页选 App」会出现「点了没反应」。
        return ruleOnKey(BGM_APP_KEY) ? BGM_APP_NETEASE : BGM_APP_MIUI;
    }

    /** 供非热路径（安装期/状态页）按规则 id 查询 */
    static boolean ruleOn(String id) {
        return ruleOnKey(CFG_PREFIX + id);
    }

    /**
     * hook 热路径用的规则开关：带 {@link Rule#defOn} 默认值。
     *
     * <p>和 {@link #ruleOnKey} 的唯一区别是「两者都查不到」时的兜底值。
     * 对 defOn=false 的规则（也就是除 gp_* 之外的全部规则），二者完全等价，
     * 所以手机管家的行为一个字都没变。</p>
     *
     * <p>仍然只读内存快照（PK/SK），不做文件或 Binder 调用——这是热路径铁的约束。</p>
     */
    static boolean ruleOn(Rule r, String k) {
        if (r == null || !r.defOn) return ruleOnKey(k);
        if (k == null) return true;
        Boolean b = PK.get(k);
        if (b != null) return b;
        Boolean s = SK.get(k);
        if (s != null) return s;
        return true;   // 从未配置过 -> 默认开启
    }

    /** 该规则是否「无配置时默认开启」，供设置页展示开关初值。 */
    public static boolean ruleDefOn(String id) {
        if (id == null) return false;
        for (Rule r : RULES) if (id.equals(r.id)) return r.defOn;
        for (Rule r : SPECIALS) if (id.equals(r.id)) return r.defOn;
        return false;
    }

    private static volatile boolean sCfgWatch;
    private static volatile int[] sLastStats;
    private static volatile int sLastHits = -1;

    /**
     * 启动侧车轮询线程（后台、5s 一次）。
     *
     * <p>目标进程启动时只读一次侧车，之后不会再变；设置页改开关时进程可能一直活着，
     * 于是出现「打开之后关不掉 / 关闭后不生效」。轮询让运行中的进程也能跟上改动，
     * 配合 {@link #ruleOn} 的运行时判定即可做到即时生效。</p>
     */
    private static void startCfgWatch() {
        if (sCfgWatch) return;
        sCfgWatch = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        loadSystemCfg();
                    } catch (Throwable ignored) {
                    }
                    try {
                        // 命中数变化时才重写状态文件，让设置页能看到实时命中（也便于定位
                        // 「hook 装上了但从未被调用」这类问题）。
                        int tot = 0;
                        for (Rule r : allRules()) tot += r.hits;
                        if (tot != sLastHits) {
                            sLastHits = tot;
                            int[] st = sLastStats;
                            if (st != null) writeStatusNow(st);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "camellia-cfg-watch");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 刷新 remote prefs 开关。
     *
     * 本方法会在进程启动关键路径（主线程）上被调用，因此**绝对不能触碰 /sdcard**：
     * /sdcard 走 FUSE 用户态文件系统，启动期同步读写可能长时间阻塞，
     * 直接把宿主进程拖成 "failed to complete startup" ANR。
     * 这里为了绝对安全，内部也不做任何日志回调（防止重入放大）。
     */
    private static void refreshPrefs() {
        sCfgGen = Prefs.cfgGen();
        SharedPreferences p = null;
        try {
            if (sPrefs != null) p = sPrefs.prefs();
        } catch (Throwable ignored) {
        }
        if (p != null) {
            try {
                for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Boolean) {
                        PK.put(e.getKey(), (Boolean) v);
                    } else if (v instanceof String) {
                        PS.put(e.getKey(), (String) v);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        sCfgSrc = !PK.isEmpty() ? "prefs" : (SK.isEmpty() ? "none" : "system");
    }

    /** 取一个可用的 Context（读 Settings.System 公共键用） */
    private static Context cfgContext() {
        Context c = mbiContext;
        if (c == null) {
            c = scContext();
        }
        return c;
    }

    /**
     * 读跨进程配置（Settings.System 公共键 camellia_cfg_v1）。
     *
     * <p>为什么不用 /sdcard 侧车：手机管家进程是 system_app，与模块 app 不同 uid，
     * Android 13+ 分区存储下读模块的 Android/media 目录必然被拒；
     * 而 Settings.System 是设备级公共键值库，任何进程都能读、不需要权限，
     * 模块侧每次保存设置都会整份写入（含 ts）。</p>
     *
     * <p>只允许后台线程调用 —— 这是一次 binder 往返，不放主线程。</p>
     */
    private static void loadSystemCfg() {
        Context c = cfgContext();
        if (c == null) {
            // Context 还没就绪（hook 安装早期）：跳过，等下一轮重试（配置有 TTL）
            return;
        }
        try {
            String text = Prefs.readCrossProcCfg(c);
            if (text == null || text.length() == 0) {
                return;
            }
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                String k = t.substring(0, eq).trim();
                String v = t.substring(eq + 1).trim();
                SK.put(k, "1".equals(v) || "true".equalsIgnoreCase(v));
                SIDECAR.put(k, v);
            }
            sCfgSrc = PK.isEmpty() ? "system" : "prefs";
        } catch (Throwable t) {
            lg(5, TAG, "读取 Settings.System 配置失败: " + t);
        }
    }

    /**
     * 重读侧车配置。
     *
     * <p>侧边栏背景在「每次呼出侧边栏」时都会重新取颜色/模糊，若沿用安装时一次性读入的
     * 快照，用户在设置页改完色就必须重启手机管家才可见。侧车文件只有几 KB，
     * 调用点也只在侧边栏呼出时，因此这里直接重读（而非用 mtime+长度做指纹：
     * 同秒内把模糊从 60 改成 70 长度不变，指纹会漏检），
     * 从而实现「设置页改色 → 下次呼出侧边栏即生效」。</p>
     */
    private static synchronized void refreshCfgLight() {
        try {
            loadSystemCfg();
        } catch (Throwable ignore) {
        }
    }

    static boolean debugOn() {
        return cfgBool("debug");
    }

    // ══════════════ 系统安全组件（com.miui.guardprovider） ══════════════
    //
    // 思路来自 WOMMO（YifePlayte/WOMMO，main @ 33626ea）对其 SecurityCenter /
    // GuardProvider hook 集里的「数据上报与云端指令」一类需求的处理方式。
    // 这里只取与本模块定位吻合的几条，且全部重写为字符串锚点 / 类级 hook + 运行时开关门控，
    // 不照搬其按 obfuscated 类名硬编码的写法（那是绑定单一版本的，跨版本必失效）：
    //   ① gp_applist   拦截应用清单上传（原实现里对应「返回空 List」，见 spGpAppList）
    //   ② gp_devstate  拦截设备状态上报（对应其 device_state 上报点）
    //   ③ gp_push      拦截云端下发指令（对应其 command 分发点）
    //
    // 其余同类 hook（广告、安装拦截、车载、倒计时等）在 RULES 表里已有覆盖面更宽的
    // 既有实现，不在此重复安装——两条 hook 挂同一方法会互相覆盖 chain，后装者胜。

    /** 该规则是否属于系统安全组件（gp_ 前缀） */
    private static boolean guardRule(Rule r) {
        if (r == null || r.id == null || !r.id.startsWith("gp_")) return false;
        // gp_dbguard 是唯一例外：行为库 fraud_detection_data.db 的实际写入方是手机管家
        // 主进程（securitycenter），不在 guardprovider 进程，所以它归手机管家侧安装。
        return !"gp_dbguard".equals(r.id);
    }

    /**
     * 系统安全组件的安装入口。
     *
     * 与手机管家共用绝大多数机制，但有四处必须分开，否则会互相拖累：
     * <p>
     * ① 规则表只取 gp_*：guardprovider 里不存在手机管家那 40 多条锚点，
     * 全表拖进来只是空跑一次 3.9~6.4s 的 DexKit 全量查询。
     * <p>
     * ② 不读也不写手机管家的签名缓存：缓存可用性按「一套 App 的规则全集」校验
     * （{@link #cacheCovers}），两个 App 共用同一份会被对方永远判为「规则集不完整」
     * 而双双退回 DexKit。该进程实际只装七条规则，且启动频率远低于手机管家，
     * 直接放弃缓存最省事也最稳。
     * <p>
     * ③ 开关计数只数 gp_*：{@link #countOnRules} 统计全表，若沿用则手机管家开着的
     * 任何开关都会让 guardprovider 误以为「有规则要装」。
     * <p>
     * ④ 状态仍写同一份状态文件、同一批 Rule 对象：设置页只读这一份，
     * 新分组「系统安全组件」才能和其它分组一样显示命中/未命中。
     */
    public static void installGuardProvider(XposedModule mod, ClassLoader cl, ApplicationInfo ai,
                                           String pkg, PrefProvider pp) {
        Main.probe("Unlock.installGuardProvider", pkg + " cl=" + (cl != null) + " ready=" + sReady);
        if (sReady) {
            return;
        }
        if (cl == null) {
            // guardprovider 的 Application 加载极早，classloader 偶尔晚一拍，交给 onPackageReady
            lg(4, TAG, "guardprovider classloader 未就绪，等待 onPackageReady");
            return;
        }
        sReady = true;
        sIsGuard = true;
        sMod = mod;
        sCl = cl;
        sAi = ai;
        sPrefs = pp;
        sProc = procName();

        // 与手机管家同口径：能读到 remote prefs 就用它，读不到回退 /sdcard 侧车。
        // DexKit 定位必须离开回调线程（guardprovider 在开屏动画期间被拉起，
        // 主线程阻塞会直接表现为「开机后一段时间安全组件无响应」）。
        boolean prefsUsable = false;
        try {
            SharedPreferences p = pp.prefs();
            prefsUsable = p != null && !p.getAll().isEmpty();
        } catch (Throwable ignored) {
        }
        final boolean prefsOk = prefsUsable;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                if (prefsOk) {
                    synchronized (CFG_LOCK) {
                        sCfgAt = SystemClock.uptimeMillis();
                        refreshPrefs();
                    }
                } else {
                    try {
                        loadSystemCfg();
                    } catch (Throwable ignored) {
                    }
                }
                runGuardInstall();
            }
        }, "camellia-guard-init");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    /** guardprovider 进程内的实际安装：只跑 gp_* 七条（gp_dbguard 挂手机管家），不碰签名缓存 */
    private static void runGuardInstall() {
        try {
            long t0 = SystemClock.uptimeMillis();
            List<Rule> sps = new ArrayList<Rule>();
            for (Rule r : SPECIALS) {
                if (guardRule(r)) {
                    sps.add(r);
                }
            }
            int enabled = 0;
            for (Rule r : sps) {
                if (cfg(r)) {
                    enabled++;
                }
            }
            sOnCount = enabled;
            sAnyOn = enabled > 0;
            sVia = "guard";
            if (!sAnyOn) {
                // 规则全关：不建 bridge、不查询，直接写一份「已跳过」的状态
                writeStatus(new int[]{0, 0, 0, 1});
                lg(4, TAG, "guard 规则全部关闭，跳过安装");
                return;
            }

            DexKitBridge bridge = null;
            try {
                if (ensureDexKit()) {
                    bridge = DexKitBridge.create(sAi.sourceDir);
                }
            } catch (Throwable t) {
                lg(5, TAG, "guard 创建 DexKit bridge 失败: " + t);
                bridge = null;
            }
            if (bridge == null) {
                // DexKit 不可用时不写「全 miss 状态」以外的任何缓存，避免固化失败结果
                // 但 gp_track 走 Class.forName 反射定位、gp_capture 反射系统
                // MediaProjection，两者都不依赖 DexKit，仍要尽力装上：否则一次
                // 初始化失败（dex 布局与预期不同等）就会让埋点拦截与录屏拦截
                // 永久失守，而 gp_track 恰恰是上游 HyperPGuard 也漏掉的统计链路。
                int hooked0 = 0;
                int miss0 = 0;
                for (Rule r : sps) {
                    if (!GP_NO_DEX.equals(r.id) && !GP_NO_DEX_CAPTURE.equals(r.id)) {
                        r.state = 2;
                        miss0++;
                        continue;
                    }
                    try {
                        if (installSpecial(r, null) > 0) {
                            r.state = 1;
                            hooked0++;
                        } else {
                            r.state = 2;
                            miss0++;
                        }
                    } catch (Throwable t) {
                        r.state = 3;
                        miss0++;
                        lg(5, TAG, "guard 无 DexKit 下安装 " + r.id + " 失败: " + t);
                    }
                }
                writeStatus(new int[]{hooked0, miss0, 0, 0});
                lg(5, TAG, "guard 无 DexKit 可用，仅装不依赖 DexKit 的规则：命中 " + hooked0);
                return;
            }

            Sp sp = new Sp(bridge, new LinkedHashMap<String, MethodDataList>());
            int hooked = 0, miss = 0, fail = 0;
            for (Rule r : sps) {
                try {
                    int n = installSpecial(r, sp);
                    if (n > 0) {
                        r.state = 1;
                        hooked++;
                        lg(4, TAG, "guard 已挂载 " + r.id + " x" + n);
                    } else {
                        r.state = 2;
                        miss++;
                        lg(4, TAG, "guard 未命中 " + r.id + " 锚点=" + r.anchor);
                    }
                } catch (Throwable t) {
                    r.state = 3;
                    fail++;
                    r.where = "异常 " + t.getClass().getSimpleName();
                    lg(5, TAG, "guard 失败 " + r.id + ": " + t);
                }
            }
            sCostMs = SystemClock.uptimeMillis() - t0;
            writeStatus(new int[]{hooked, miss, fail, 0});
            lg(4, TAG, "guard 安装完成 命中=" + hooked + " 未命中=" + miss
                    + " 失败=" + fail + " 耗时=" + sCostMs + "ms");
        } catch (Throwable t) {
            lg(5, TAG, "runGuardInstall 异常: " + t);
        }
    }

    /** 上传类方法的常见命名片段：URL 常量可能被多个方法引用，先用名字收窄再兜底 */
    private static final String[] GP_UPLOAD_HINTS = {
            "upload", "report", "send", "post", "submit", "commit", "sync", "detect", "check"};

    /**
     * gp_applist：拦截应用清单上传。
     *
     * 锚点是上报接口的 URL 字面量，引用它的方法就是「把已安装应用清单打包发给云端」的那一层。
     * 处理方式不是返回空 List（那样调用方可能把 null 当异常继续重试），而是按返回类型给出
     * 一个「对方会认为已经上报成功、但实际什么都没发」的值：
     * void → 直接跳过；boolean → false；基本数值 → 0；其余引用类型 → null。
     */
    private static int spGpAppList(Rule r, Sp sp) {
        List<Method> ms = sp.ms(r.anchor, null, null, null);
        if (ms.isEmpty()) {
            return 0;
        }
        // 上游是 findMethod(...).single() 唯一命中；这里按形状逐层收紧，避免多命中时
        // 把「取 URL / 拼常量」的无关方法也挂上。
        List<Method> picked = new ArrayList<Method>();
        if (ms.size() == 1) {
            picked = ms;                       // 与上游 single() 等价
        } else {
            // 优先只挂名字像上传的方法
            for (Method m : ms) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                for (String h : GP_UPLOAD_HINTS) {
                    if (n.contains(h)) {
                        picked.add(m);
                        break;
                    }
                }
            }
            if (picked.isEmpty()) {
                // 上游对该 POST 方法统一 param.result = null。返回 String/CharSequence 的方法
                // 多半是取 URL 的辅助方法，拦成 null 会让调用方 NPE，先排掉；排空了才退回全挂。
                for (Method m : ms) {
                    Class<?> rt = m.getReturnType();
                    if (rt != String.class && !CharSequence.class.isAssignableFrom(rt)) {
                        picked.add(m);
                    }
                }
                if (picked.isEmpty()) {
                    picked = ms;
                }
            }
        }
        int n = 0;
        for (Method m : picked) {
            if (hookMethod(r, m, GP_APP_LIST_ACTION)) {
                n++;
            }
        }
        return n;
    }

    private static final SpAction GP_APP_LIST_ACTION = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule r) throws Throwable {
            Class<?> rt = ((Method) chain.getExecutable()).getReturnType();
            if (rt == void.class || rt == Void.class) {
                return null;                       // 原方法不执行，什么都不发
            }
            if (rt == boolean.class || rt == Boolean.class) {
                return Boolean.FALSE;
            }
            if (rt.isPrimitive()) {
                return 0;                          // int/long/short/byte
            }
            return null;
        }
    };

    /** 与上游 BlockDeviceStateReport.TASK_DEVICE_STATE 一致：统计任务里「设备状态上报」的编号 */
    /**
     * 不依赖 DexKit 的 guardprovider 规则 id。{@link #spGpTrack} 走 Class.forName
     * 直接定位类，DexKit 初始化失败时仍应尽力安装，否则一次失败会让埋点长期失守。
     */
    static final String GP_NO_DEX = "gp_track";

    /**
     * 同样不依赖 DexKit 的 guardprovider 规则 id：{@link #spGpCapture} 反射系统类
     * {@code android.media.projection.MediaProjection}，不查任何 APK 内符号，
     * 故 DexKit 不可用时也应安装，保证录屏拦截在弱环境下仍然生效。
     */
    static final String GP_NO_DEX_CAPTURE = "gp_capture";

    /**
     * guardprovider 内嵌的是<b>原版</b> OneTrack SDK：本机 APK 中
     * {@code com.xiaomi.onetrack} 被引用 276 次，自带完整 SDK；它与手机管家 APK 的
     * {@code com.miui.analytics.onetrack} 是两套不同组件，不能共用锚点。
     */
    static final String GP_ONETRACK_CLASS = "com.xiaomi.onetrack.OneTrack";

    /**
     * gp_track：拦截 guardprovider 的 OneTrack 统计埋点。
     *
     * <p>与其余 guard 规则不同，这里<b>不按 URL 锚点</b>定位。OneTrack 的上报域名
     * （{@code tracking.*.miui.com}、{@code sdkconfig.*.xiaomi.com}）由 SDK 内部的
     * 配置类拼出来，引用这些字面量的多半是 getter，hook 它们既拦不住事件、又容易
     * NPE。改在 SDK 的唯一出口上拦：OneTrack 的全部事件最终都从
     * {@code track*}/{@code adTrack*} 这些 void 方法进队列，不 proceed 就等于
     * 「事件从未发生」——不落盘，也不会在下次联网时补发。</p>
     *
     * <p>只动 void 返回的方法：getter、{@code isDisable}、配置类方法一律保持原样，
     * 避免组件把 SDK 当成「未初始化」而走异常分支（与上游 TrackHooks 的结论一致）。</p>
     */
    private static int spGpTrack(Rule r) {
        int n = 0;
        try {
            Class<?> c = Class.forName(GP_ONETRACK_CLASS, false, sCl);
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.startsWith("track") && !name.startsWith("adTrack")) continue;
                if (m.getReturnType() != void.class) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                // GP_APP_LIST_ACTION 对 void 方法返回 null，语义正是「原方法不执行」
                if (hookMethod(r, m, GP_APP_LIST_ACTION)) n++;
            }
            lg(4, TAG, "gp_track OneTrack 已挂载 x" + n);
        } catch (Throwable t) {
            lg(5, TAG, "gp_track 定位 " + GP_ONETRACK_CLASS + " 失败: " + t);
        }
        return n;
    }

    private static final int GP_TASK_DEVICE_STATE = 6;

    /**
     * gp_devstate：拦截设备状态上报。
     *
     * 上报内容是 bootloader 锁状态、SELinux 状态、已启用的无障碍服务等。这条上报由统计模块
     * 的一个任务对象执行，同一个 {@code run()} 上还挂着十几个任务编号不同的实例，因此既不能
     * 整体拦截，也不该改状态位影响后续流程：只在 {@code this} 的任务编号字段等于
     * {@link #GP_TASK_DEVICE_STATE} 时返回结果、跳过这一次 {@code run()}。
     *
     * 拦截点选在任务入口而不是它调用的统计接口——统计 SDK 会吞掉自己抛出的异常，在上游
     * 抛异常并不能阻止任务继续写上报时间戳（上游 BlockDeviceStateReport 同此结论）。
     *
     * 本模块走 libxposed API 102 的 {@code Hooker.intercept(chain)}：不调用
     * {@code chain.proceed()} 即等价于上游 EzXposed 的 {@code param.result = null}。
     */
    private static int spGpDevState(Rule r, Sp sp) {
        List<Method> ms = sp.ms(r.anchor, null, null, null);
        // 上游 DexKit 条件含 name("run")；这里按形状先捞一遍，优先 run 名下的方法，
        // 若版本差异导致方法名被混淆，再退回锚点命中的全部零参 void 方法。
        List<Method> shapes = new ArrayList<Method>();
        List<Method> runs = new ArrayList<Method>();
        for (Method m : ms) {
            if (m.getParameterTypes().length != 0 || m.getReturnType() != void.class) {
                continue;
            }
            shapes.add(m);
            if ("run".equals(m.getName())) {
                runs.add(m);
            }
        }
        List<Method> targets = runs.isEmpty() ? shapes : runs;
        int n = 0;
        for (Method m : targets) {
            final Field f = firstIntField(m.getDeclaringClass());
            if (f == null) {
                continue;
            }
            SpAction act = new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rr) throws Throwable {
                    Object self = chain.getThisObject();
                    if (self != null) {
                        try {
                            if (f.getInt(self) == GP_TASK_DEVICE_STATE) {
                                return null;   // 跳过这次 run()，其余任务编号照常执行
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    return chain.proceed();
                }
            };
            if (hookMethod(r, m, act)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 挑一个实例 int 字段作为「任务编号」字段。
     *
     * 上游是 {@code findFieldOrNull { type(Int) }} 直接取第一个 int 字段；这里额外按语义名
     * 加权（task/type/cmd/id…），避免同类中存在多个 int 字段时误取到状态位、计数位。
     * 找不到语义名就退回第一个可访问的实例 int 字段，与上游一致。
     */
    private static Field firstIntField(Class<?> c) {
        Field fallback = null;
        // 先把整条继承链扫完再决定：命中语义名立即返回，否则用第一个可访问的实例 int 字段
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fs;
            try {
                fs = k.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getType() != int.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                String fn = f.getName().toLowerCase(Locale.ROOT);
                if (fn.contains("task") || fn.contains("type") || fn.contains("cmd")
                        || fn.equals("id") || fn.equals("index") || fn.equals("code")) {
                    return f;
                }
                if (fallback == null) {
                    fallback = f;
                }
            }
        }
        return fallback;
    }

    /**
     * 云端可控指令的本地黑名单 —— 与 WOMMO 上游 {@code BlockPushCommand} 的 CMD_* 常量逐条对齐。
     *
     * 这 10 条是安全组件真实下发、且带「采集/启停/改配置/升级病毒库」语义的指令；其余指令
     * （例如开屏时的普通设置项）照常分发，全拦会让本地策略永远停在旧版本。
     *
     * 与上游的差异：上游为每条指令各配一个 {@code block_push_command_<cmd>} 开关，本模块把
     * guardprovider 相关规则统一收进 G_GUARD 组，10 条指令拦截共用单开关 {@code gp_push}，
     * 语义等价于上游全开。
     * 上游另有 {@code getSettingsConfig} 字符串 contains 匹配，本模块不引入（它属于设置类指令，
     * 不在 CMD_* 白名单内，按设计放行）。
     */
    /** 10 条命令的规范顺序（设置页渲染与侧车写出都按此顺序，保证稳定）。 */
    public static final String[] GP_PUSH_SUB_CMDS = {
            "collect_package",        // CMD_COLLECT_PACKAGE    采集应用清单
            "disable_package",        // CMD_DISABLE_PACKAGE    停用应用
            "enable_package",         // CMD_ENABLE_PACKAGE     启用应用
            "disable_component",      // CMD_DISABLE_COMPONENT  停用组件
            "enable_component",       // CMD_ENABLE_COMPONENT   启用组件
            "clear_a11y",             // CMD_CLEAR_A11Y         清除无障碍服务
            "scan_and_process",       // CMD_SCAN_AND_PROCESS   扫描并处理
            "set_prop",               // CMD_SET_PROP           写系统属性
            "set_settings",           // CMD_SET_SETTINGS       写系统设置
            "update_virus_db",        // CMD_UPDATE_VIRUS_DB    升级病毒库
    };

    /** 白名单集合由上面的顺序数组派生，避免两份清单漂移。 */
    private static final Set<String> GP_COMMANDS =
            new HashSet<String>(Arrays.asList(GP_PUSH_SUB_CMDS));

    /**
     * gp_push 命令级子开关的 id 前缀 —— 对齐上游 {@code BlockPushCommand} 的
     * {@code block_push_command_<cmd>} 逐条开关，本模块统一为 {@code gp_push_<cmd>}。
     *
     * <p>这些 id 刻意<b>不</b>进 {@link #SPECIALS}/{@link #RULES}：规则表的单位是
     * 「锚点 + hook 安装」，而子开关只是同一条 hook 内部的运行时判定。进表会额外
     * 产生 10 个一级条目、并让 {@link #countOnRules()} 多统计 10 条命中。
     * 它们的配置由 {@code Prefs.ulSidecarBody} 单独补写进侧车（目标进程读不到模块 prefs）。</p>
     */
    private static final String GP_PUSH_SUB_PREFIX = "gp_push_";

    /** 命令名 → 子开关规则 id（与 prefs/侧车的 {@code ul_} 前缀拼接后即成配置键）。 */
    public static String gpPushSubId(String cmd) {
        return cmd == null ? null : GP_PUSH_SUB_PREFIX + cmd;
    }

    /**
     * 单条下发指令是否仍要拦截。默认 true（与 gp_push 本体一致，装好即生效）；
     * 用户在设置页显式关掉某条后，该条指令放行、其余照常拦截。
     *
     * <p>注意：配置的真实键是 {@code ul_gp_push_<cmd>}（Prefs 侧写 prefs 与侧车都拼过
     * {@code KEY_UL_PREFIX="ul_"}），而 {@link #cfgOr(String, boolean)} 不做任何前缀补全，
     * 因此这里必须显式拼 {@link #CFG_PREFIX}，与 {@link #cfg(Rule)} 的口径保持一致。
     * 漏掉这一步会导致子开关在 guardprovider 进程里永远读不到、恒取默认 true。</p>
     */
    public static boolean gpPushSubOn(String cmd) {
        String id = gpPushSubId(cmd);
        return id == null || cfgOr(CFG_PREFIX + id, true);
    }

    /** 命令级子开关的中文标签（设置页二级列表与拦截日志共用）。 */
    public static String gpPushSubLabel(String cmd) {
        if (cmd == null) {
            return "";
        }
        if ("collect_package".equals(cmd)) return "采集应用清单";
        if ("disable_package".equals(cmd)) return "停用应用";
        if ("enable_package".equals(cmd)) return "启用应用";
        if ("disable_component".equals(cmd)) return "停用组件";
        if ("enable_component".equals(cmd)) return "启用组件";
        if ("clear_a11y".equals(cmd)) return "清除无障碍服务";
        if ("scan_and_process".equals(cmd)) return "扫描并处理";
        if ("set_prop".equals(cmd)) return "写系统属性";
        if ("set_settings".equals(cmd)) return "写系统设置";
        if ("update_virus_db".equals(cmd)) return "升级病毒库";
        return cmd;
    }      // CMD_UPDATE_VIRUS_DB  升级病毒库

    /**
     * gp_push：拦截云端下发指令。
     *
     * 锚点是「未知指令 / 参数解析失败」这两段日志前缀，它们出现在同一个指令分发方法里
     * （AND 关系）。分发方法的第二个参数就是指令对象，指令名从它的无参 String getter 里取：
     * 命中黑名单则伪造一个「已成功执行」的结果返回，原方法不执行；否则照常 proceed。
     */
    private static int spGpPush(Rule r, Sp sp) {
        List<Method> all = sp.ms(r.anchor, null, null, null);
        if (all.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Method m : all) {
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length < 2) {
                continue;   // 分发方法至少要拿到「上下文 + 指令对象」
            }
            if (hookMethod(r, m, GP_PUSH_ACTION)) {
                n++;
            }
        }
        return n;
    }

    private static final SpAction GP_PUSH_ACTION = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule r) throws Throwable {
            List<Object> args = chain.getArgs();
            if (args == null || args.size() < 2) {
                return chain.proceed();
            }
            Object cmdObj = args.get(1);
            String name = gpActionName(cmdObj);
            if (name == null || !GP_COMMANDS.contains(name)) {
                return chain.proceed();            // 非黑名单指令：原样分发
            }
            // 命令级子开关（对齐上游 block_push_command_<cmd>）：总开关 gp_push 已在
            // ruleOn 阶段门控过，这里再按单条命令判定 —— 关掉某条只放行该条指令。
            if (!gpPushSubOn(name)) {
                lg(4, TAG, "guard 放行指令（子开关已关） " + name);
                return chain.proceed();
            }
            Class<?> rt = ((Method) chain.getExecutable()).getReturnType();
            if (rt == void.class || rt == Void.class) {
                lg(4, TAG, "guard 已拦截指令 " + name);
                return null;                       // 不执行原方法即等于不下发
            }
            Object fake = gpFakeResult(rt, name);
            if (fake != null) {
                lg(4, TAG, "guard 已拦截指令 " + name + " → 伪造成功");
                return fake;
            }
            return chain.proceed();                // 造不出合法返回值就不要拦，宁可放过
        }
    };

    /**
     * 从指令对象里取指令名。
     *
     * 上游 {@code BlockPushCommand} 用 DexKit 的 {@code callerMethods} 反查「被分发方法调用的
     * 无参 String getter」再固定调用它；混淆后 getter 名不可预测，硬编码 getName/getCmd 会失效。
     * 这里在运行时扫描指令对象整条继承链上的所有无参实例 String 方法，只有返回值落在
     * {@link #GP_COMMANDS} 白名单里才认作指令名——等价于上游的 {@code isCommandBlocked}，
     * 且不会把 toString()/随机字符串误判成指令。
     */
    private static String gpActionName(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String) {
            return ((String) o).toLowerCase(Locale.ROOT);
        }
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            Method[] gs;
            try {
                gs = k.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method g : gs) {
                if (g.getParameterTypes().length != 0
                        || g.getReturnType() != String.class
                        || Modifier.isStatic(g.getModifiers())) {
                    continue;
                }
                try {
                    g.setAccessible(true);
                    Object v = g.invoke(o);
                    if (v instanceof String) {
                        String s = ((String) v).toLowerCase(Locale.ROOT);
                        if (GP_COMMANDS.contains(s)) {
                            return s;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 为指令结果构造一个「成功」的假值。
     *
     * 安全组件的分发结果大多是 {@code XxxResult(String action, int code, String msg)}，
     * 其中 code=0 表示成功。构造失败就返回 null，由调用方决定 proceed，
     * 绝不为了拦截而抛异常——那会让安全组件进入错误分支反复重试。
     */
    private static Object gpFakeResult(Class<?> rt, String action) {
        Constructor<?>[] ks;
        try {
            ks = rt.getDeclaredConstructors();
        } catch (Throwable t) {
            return null;
        }
        // 优先 (String, int, String)
        for (Constructor<?> k : ks) {
            Class<?>[] pt = k.getParameterTypes();
            if (pt.length == 3 && pt[0] == String.class && (pt[1] == int.class)
                    && pt[2] == String.class) {
                try {
                    k.setAccessible(true);
                    // 与上游 BlockPushCommand 的 newInstance(command, 0, "blocked") 逐字对齐：
                    // code=0 让调用方按「执行成功」收尾，不再重试；msg=blocked 便于日志溯源。
                    return k.newInstance(action, 0, "blocked");
                } catch (Throwable ignored) {
                }
            }
        }
        // 退一步 (String, int)
        for (Constructor<?> k : ks) {
            Class<?>[] pt = k.getParameterTypes();
            if (pt.length == 2 && pt[0] == String.class && pt[1] == int.class) {
                try {
                    k.setAccessible(true);
                    return k.newInstance(action, 0);
                } catch (Throwable ignored) {
                }
            }
        }
        // 再退一步：只有一个 boolean/int 结果位的
        for (Constructor<?> k : ks) {
            Class<?>[] pt = k.getParameterTypes();
            if (pt.length == 1 && pt[0] == int.class) {
                try {
                    k.setAccessible(true);
                    return k.newInstance(0);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    // ───────────────────────────── 入口 ─────────────────────────────

    public static void install(XposedModule mod, ClassLoader cl, ApplicationInfo ai,
                               String pkg, PrefProvider pp) {
        Main.probe("Unlock.install", pkg + " cl=" + (cl != null) + " ready=" + sReady);
        if (sReady) return;
        if (cl == null) {
            lg(4, TAG, "classloader 尚未就绪，等待 onPackageReady");
            return;
        }
        sReady = true;
        sMod = mod;
        sCl = cl;
        sAi = ai;
        sPrefs = pp;
        sProc = procName();

        // 分流依据：remote prefs 位于 /data 读取极快；读不到时回退 /sdcard 侧车。
        // 但无论哪种配置源，「DexKitBridge.create + 38 条规则全量查询」耗时
        // 3.9~6.4s，绝不能在主线程（onPackageReady 回调线程）同步执行——
        // 否则卡死宿主进程触发 ANR → 系统 kill → 手机管家进程反复重启（偶发脱钩根因）。
        // 因此「读配置 + DexKit 定位 + hook 安装 + 写状态」统一挪到后台线程。
        boolean prefsUsable = false;
        try {
            SharedPreferences p = pp.prefs();
            prefsUsable = p != null && !p.getAll().isEmpty();
        } catch (Throwable ignored) {
        }

        final boolean prefsOk = prefsUsable;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                if (prefsOk) {
                    long now = SystemClock.uptimeMillis();
                    synchronized (CFG_LOCK) {
                        sCfgAt = now;
                        refreshPrefs();
                    }
                } else {
                    try {
                        loadSystemCfg();
                    } catch (Throwable ignored) {
                    }
                }
                runInstall();
            }
        }, "camellia-unlock-init");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    private static void runInstall() {
        try {
            long t0 = SystemClock.uptimeMillis();
            int[] st = doInstall();
            sCostMs = SystemClock.uptimeMillis() - t0;
            sLastStats = st;
            sLastHits = 0;
            writeStatus(st);
            startCfgWatch();
            // 设备能力判据（杜比全景声这类机型能力，与返回值无关）
            natCapPass();
            // 「原生已支持」主动探测（零参 / 单 Context 静态目标各调用一次，采样在 hook 内完成）
            Thread np = new Thread(new Runnable() {
                @Override
                public void run() {
                    natActiveProbe();
                }
            }, "camellia-natprobe");
            np.setPriority(Thread.MIN_PRIORITY);
            np.start();
            lg(4, TAG, "安装完成 hooked=" + st[0] + " miss=" + st[1] + " fail=" + st[2]
                    + " skip=" + st[3] + " cost=" + sCostMs + "ms via=" + sVia);
        } catch (Throwable t) {
            lg(6, TAG, "安装失败: " + t);
            sVia = "error";
            writeStatus(new int[]{0, 0, 0, 0});
        }
    }

    private static String procName() {
        try {
            FileInputStream in = new FileInputStream("/proc/self/cmdline");
            byte[] b = new byte[256];
            int n = in.read(b);
            in.close();
            if (n <= 0) return "-";
            int e = 0;
            while (e < n && b[e] != 0) e++;
            return e > 0 ? new String(b, 0, e, StandardCharsets.UTF_8) : "-";
        } catch (Throwable t) {
            return "-";
        }
    }

    public static String proc() {
        return sProc;
    }

    static void lg(int prio, String tag, String msg) {
        try {
            if (prio >= 5 || debugOn()) Log.println(prio, tag, msg);
        } catch (Throwable ignored) {
        }
    }

    // ─────────────────────────── 专用安装器上下文 ───────────────────────────

    /** 传给专用安装器的上下文（DexKit bridge + 查询缓存） */
    static final class Sp {
        final DexKitBridge bridge;
        final Map<String, MethodDataList> cache;
        /** 定位点 -> 已反射方法（签名缓存命中时直接取用；DexKit 路径时收集） */
        final Map<String, List<Method>> resolvedM;
        /** 类锚点 -> 已反射类 */
        final Map<String, Class<?>> resolvedC;

        Sp(DexKitBridge b, Map<String, MethodDataList> c) {
            this(b, c, null, null);
        }

        Sp(DexKitBridge b, Map<String, MethodDataList> c,
           Map<String, List<Method>> rm, Map<String, Class<?>> rc) {
            this.bridge = b;
            this.cache = c;
            this.resolvedM = rm;
            this.resolvedC = rc;
        }

        MethodDataList q(String anchor, int[] nums, String cls) {
            return query(bridge, anchor, nums, cls, cache);
        }

        /** 查询并按形状/所属类过滤出反射方法列表 */
        List<Method> ms(String anchor, int[] nums, String cls, Rule shape) {
            String key = keyOf(anchor, cls, nums);
            if (resolvedM != null && resolvedM.containsKey(key)) {
                return resolvedM.get(key);
            }
            List<Method> out = new ArrayList<Method>();
            MethodDataList list = q(anchor, nums, cls);
            if (list == null) {
                if (resolvedM != null) resolvedM.put(key, Collections.<Method>emptyList());
                return out;
            }
            for (int i = 0; i < list.size(); i++) {
                MethodData md = list.get(i);
                if (shape != null && !shapeMatch(md, shape)) continue;
                Method m = reflectMethod(dot(md.getDeclaredClassName()), md.getName(), shape);
                if (m != null && !contains(out, m)) out.add(m);
            }
            if (resolvedM != null) resolvedM.put(key, new ArrayList<Method>(out));
            return out;
        }

        /** 查询一个类（用于类级 hook） */
        Class<?> cls(String anchor) {
            if (resolvedC != null && resolvedC.containsKey(anchor)) {
                return resolvedC.get(anchor);
            }
            if (bridge == null || anchor == null) return null;
            try {
                ClassData cd = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create().usingStrings(anchor))).singleOrNull();
                if (cd == null) {
                    if (resolvedC != null) resolvedC.put(anchor, null);
                    return null;
                }
                Class<?> c = Class.forName(dot(cd.getName()), false, sCl);
                if (resolvedC != null) resolvedC.put(anchor, c);
                return c;
            } catch (Throwable t) {
                return null;
            }
        }

        Method reflectOne(String ownerDot, String name, Rule shape) {
            return reflectBy(ownerDot, name, shape);
        }
    }

    // ─────────────────────────── 签名缓存（跨进程复用定位结果） ───────────────────────────

    /** 目标应用（手机管家）自身的 Context，用于拿它可读写的 cacheDir */
    private static Context scContext() {
        try {
            Method cur = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication");
            Object app = cur.invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 缓存文件：与互传的 hooksig.cache 独立 */
    private static File ulCacheFile() {
        // 手机管家进程是 system_app：
        //   · /sdcard/Android/media/<pkg>/ 下创建的缓存 owner 会被 FUSE 强制归属模块 uid，
        //     system 写得出却读不回（660），跨进程缓存命中失效；
        //   · /data/misc 写被 SELinux 静默拒绝（dontaudit，无 avc 日志）。
        // 最可靠的是写到目标应用自己的 cacheDir（app 私有，system_app 读写均允许）。
        // 读缓存发生在进程启动早期，ActivityThread.currentApplication() 可能为 null，
        // 且实测 LSPosed 传入的 ApplicationInfo.dataDir 为 null，故用
        // /data/user/<userId>/<pkg>/cache 直接构造路径（启动即就绪，不依赖 Context）。
        if (sAi != null && sAi.packageName != null) {
            int userId = Process.myUid() / 100000;
            File cd = new File("/data/user/" + userId + "/" + sAi.packageName, "cache");
            try {
                if (!cd.isDirectory()) cd.mkdirs();
                if (cd.isDirectory()) return new File(cd, "hooksig.ul.cache");
            } catch (Throwable ignored) {
            }
        }
        if (sAi != null && sAi.dataDir != null) {
            File cd = new File(sAi.dataDir, "cache");
            try {
                if (!cd.isDirectory()) cd.mkdirs();
                if (cd.isDirectory()) return new File(cd, "hooksig.ul.cache");
            } catch (Throwable ignored) {
            }
        }
        try {
            Context c = scContext();
            if (c != null) {
                File cd = c.getCacheDir();
                if (cd != null) return new File(cd, "hooksig.ul.cache");
            }
        } catch (Throwable ignored) {
        }
        return new File(statusDir(), "hooksig.ul.cache");
    }

    /** dataDir 候选路径（进程启动早期用于判断 CE 目录是否已挂载） */
    private static File dataDirCandidate() {
        if (sAi != null && sAi.dataDir != null) return new File(sAi.dataDir);
        if (sAi != null && sAi.packageName != null) {
            return new File("/data/user/" + (Process.myUid() / 100000) + "/" + sAi.packageName);
        }
        return null;
    }

    /** 定位点 -> 缓存键（与 query() 内部的 cache key 完全一致） */
    static String keyOf(String anchor, String cls, int[] nums) {
        return (anchor == null ? "" : anchor) + '\u0001'
                + (cls == null ? "" : cls) + '\u0001'
                + (nums == null ? "" : Arrays.toString(nums));
    }

    /**
     * 「识别 BGM」置灰解除所需的 gate 方法集。
     *
     * <p>以 anchor（方法体内出现 {@code music_bgm_status}）定位 holder 方法，再取其调用者中
     * 形状为 {@code static boolean(Context)} 的判定方法。这些 gate 决定菜单项「是否可点」。</p>
     *
     * <p>关键：结果写入 {@code sp.resolvedM}（key={@link #BGM_GATES_KEY}），这样
     * {@link #saveRuleCache} 的 {@code q=} 行会带上它们，缓存命中路径（bridge == null）
     * 可直达而无需 DexKit；否则清空/命中缓存后该项必然失效。</p>
     */
    static List<Method> bgmGates(Sp sp, String anchor) {
        List<Method> out = new ArrayList<Method>();
        if (sp == null) return out;
        if (sp.resolvedM != null) {
            List<Method> cached = sp.resolvedM.get(BGM_GATES_KEY);
            if (cached != null && !cached.isEmpty()) return new ArrayList<Method>(cached);
        }
        List<Method> holders = sp.ms(anchor, null, null, null);
        if (holders.isEmpty()) return out;
        ClassLoader loader = holders.get(0).getDeclaringClass().getClassLoader();
        MethodDataList mds = sp.q(anchor, null, null);
        // 缓存路径：bridge == null ⇒ query() 返回 null。必须安全退出，
        // 否则 for-each 抛 NPE 会把整条规则判为 fail（video_bgm_app 失效的直接原因）。
        if (mds == null) return out;
        java.util.LinkedHashSet<Method> set = new java.util.LinkedHashSet<Method>();
        for (MethodData h : mds) {
            for (MethodData caller : h.getCallers()) {
                if (!"boolean".equals(caller.getReturnTypeName())
                        || !java.lang.reflect.Modifier.isStatic(caller.getModifiers())) continue;
                if (!java.util.Arrays.asList("android.content.Context").equals(caller.getParamTypeNames()))
                    continue;
                try {
                    set.add(caller.getMethodInstance(loader));
                } catch (Throwable ignored) {
                }
            }
        }
        out.addAll(set);
        if (out.isEmpty()) {
            // 二级兜底：调用者图可能因宿主混淆/去虚化差异取不到，但判据与 holder 同族，
            // 就落在 holder 所在类里（宿主 13.6.5 实测为 Lmb/o;->c(Context)Z，见 kb.h#d()
            // packed-switch case 0x5 直接 return 其返回值）。按形状取同类方法。
            try {
                Class<?> hc = holders.get(0).getDeclaringClass();
                for (Method m : hc.getDeclaredMethods()) {
                    if (m.getReturnType() != boolean.class) continue;
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length != 1 || pt[0] != android.content.Context.class) continue;
                    out.add(m);
                }
                lg(5, TAG, "bgmGates 兜底(同类静态 Context 方法)=" + out.size()
                        + " holderCls=" + holders.get(0).getDeclaringClass().getName());
            } catch (Throwable t) {
                lg(5, TAG, "bgmGates 兜底失败: " + t);
            }
        }
        if (sp.resolvedM != null && !out.isEmpty()) {
            sp.resolvedM.put(BGM_GATES_KEY, new ArrayList<Method>(out));
        }
        return out;
    }

    /** 缓存解析结果 */
    static final class RuleCache {
        final Map<String, List<Method>> rules = new HashMap<String, List<Method>>();
        final Map<String, List<Method>> qmap = new HashMap<String, List<Method>>();
        final Map<String, Class<?>> cmap = new HashMap<String, Class<?>>();
        /**
         * 写入缓存那一刻"存在的规则全集"（含定位未成功的规则，所以不能用 rules.keySet()）。
         * 读取端用它判断模块是否新增/改名了规则：缓存没覆盖当前规则集 -> 整体退回重定位一次。
         * 这样"模块升级后新规则生效"不再依赖人工 bump ulCacheKey 里的版本串。
         */
        final Set<String> ids = new HashSet<String>();
    }

    private static String ulCacheKey(ApplicationInfo ai) {
        if (ai == null || ai.sourceDir == null) return null;
        try {
            File apk = new File(ai.sourceDir);
            return ai.sourceDir + "|" + apk.lastModified() + "|" + apk.length() + "|sidebar-limit-v6-user-state-hdr";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readAllText(File f) {
        try {
            if (!f.isFile()) return null;
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            } finally {
                in.close();
            }
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean writeAllText(File f, String text) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 原子写：先写 .tmp 再 rename，避免多进程并发写缓存产生半截文件 */
    private static boolean writeAllTextAtomic(File f, String text) {
        try {
            File dir = f.getParentFile();
            if (dir == null) return writeAllText(f, text);
            if (!dir.isDirectory()) dir.mkdirs();
            File tmp = new File(dir, f.getName() + ".tmp." + Process.myPid());
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            if (tmp.renameTo(f)) return true;
            tmp.delete();
            return writeAllText(f, text);
        } catch (Throwable t) {
            lg(5, TAG, "写缓存原子写失败: " + t);
            return false;
        }
    }

    /** 反射解析一条缓存签名：Class#name#p1,p2#ret（不要求 static，但排除抽象方法） */
    private static Method resolveSigU(String sig) {
        try {
            int h1 = sig.indexOf('#');
            int h2 = sig.indexOf('#', h1 + 1);
            int h3 = sig.indexOf('#', h2 + 1);
            if (h1 < 0 || h2 < 0 || h3 < 0) return null;
            Class<?> cls = classForNameU(sig.substring(0, h1));
            String name = sig.substring(h1 + 1, h2);
            String params = sig.substring(h2 + 1, h3);
            Class<?> ret = classForNameU(sig.substring(h3 + 1));
            if (cls == null || ret == null) return null;
            Class<?>[] pts = classesForNamesU(params);
            if (pts == null) return null;
            Method m = cls.getDeclaredMethod(name, pts);
            if (Modifier.isAbstract(m.getModifiers())) return null;
            if (!m.getReturnType().equals(ret)) return null;
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> classForNameU(String n) {
        try {
            if ("boolean".equals(n)) return boolean.class;
            if ("byte".equals(n)) return byte.class;
            if ("char".equals(n)) return char.class;
            if ("short".equals(n)) return short.class;
            if ("int".equals(n)) return int.class;
            if ("long".equals(n)) return long.class;
            if ("float".equals(n)) return float.class;
            if ("double".equals(n)) return double.class;
            if ("void".equals(n)) return void.class;
            return Class.forName(n, false, sCl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?>[] classesForNamesU(String params) {
        if (params == null || params.length() == 0) return new Class<?>[0];
        String[] parts = params.split(",");
        Class<?>[] pts = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Class<?> c = classForNameU(parts[i].trim());
            if (c == null) return null;
            pts[i] = c;
        }
        return pts;
    }

    private static String sigOfU(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getName()).append('#').append(m.getName()).append('#');
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(pts[i].getName());
        }
        return sb.append('#').append(m.getReturnType().getName()).toString();
    }

    /** 解析签名字符串：空=miss（返回空表），任一失败=整体回退（返回 null）。
     *  分隔符用 '|'：数组类型 getName 含 ';'（如 [Ljava.lang.String;），不能用 ';'。 */
    private static List<Method> resolveSigs(String sigs) {
        List<Method> out = new ArrayList<Method>();
        if (sigs == null || sigs.isEmpty()) return out;
        for (String s : sigs.split("\\|")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            Method m = resolveSigU(t);
            if (m == null) return null;
            out.add(m);
        }
        return out;
    }

    private static RuleCache loadRuleCache() {
        try {
            String key = ulCacheKey(sAi);
            if (key == null) { sCacheDiag = "key="; return null; }
            File cf = ulCacheFile();
            sCacheDiag = cf.getAbsolutePath() + "|exists=" + cf.isFile() + "|size=" + cf.length();
            String text = readAllText(cf);
            if (text == null) {
                // 进程启动早期 CE dataDir 可能尚未挂载（手机管家 system_app 的 dataDir
                // 由 bindApplication 阶段挂载）。此时 ulCacheFile() 会落到 /sdcard 兜底，
                // 缓存永久读不到。等待 dataDir 就绪后重读，命中即跳过 DexKit。
                File dd = dataDirCandidate();
                long deadline = SystemClock.uptimeMillis() + 4000;
                while (dd != null && !dd.isDirectory()
                        && SystemClock.uptimeMillis() < deadline) {
                    try {
                        Thread.sleep(150);
                    } catch (Throwable ignored) {
                    }
                }
                if (dd != null && dd.isDirectory()) {
                    text = readAllText(cf);
                }
            }
            if (text == null) { sCacheDiag += "|read="; return null; }
            RuleCache rc = new RuleCache();
            boolean sawKey = false;
            int skipped = 0;
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                // 兼容历史格式：早期写入端把 key 行写成 "key="，而这里用
                // charAt(1)=='=' 判 kind，于是 key 行被整行跳过、sawKey 恒为 false，
                // 缓存被判无效 -> 每次冷启动重跑 DexKit 全量（6~14.6s）。
                if (line.startsWith("key=")) line = "k=" + line.substring(4);
                if (line.length() < 3 || line.charAt(1) != '=') continue;
                char kind = line.charAt(0);
                // 注意：不要对 val 做 trim()——queryKey 以 '\u0001' 开头（anchor 为空时），
                // trim() 会把 '\u0001'（值 1 < 0x20）当空白去掉，导致缓存键不一致。
                String val = line.substring(2);
                if (kind == 'k') {
                    if (!key.equals(val)) {
                        sCacheDiag += "|key-mismatch";
                        lg(5, TAG, "签名缓存失效（版本变化）");
                        return null;
                    }
                    sawKey = true;
                } else if (kind == 'r') {
                    int eq = val.lastIndexOf('=');
                    if (eq < 0) return null;
                    List<Method> ms = resolveSigs(val.substring(eq + 1));
                    if (ms == null) { skipped++; continue; }
                    rc.rules.put(val.substring(0, eq), ms);
                } else if (kind == 'q') {
                    int eq = val.lastIndexOf('=');
                    if (eq < 0) return null;
                    List<Method> ms = resolveSigs(val.substring(eq + 1));
                    if (ms == null) { skipped++; continue; }
                    rc.qmap.put(val.substring(0, eq), ms);
                } else if (kind == 'n') {
                    // 规则 id 全集（逗号分隔；id 都是 ASCII 标识符，trim 安全）
                    rc.ids.clear();
                    for (String one : val.split(",")) {
                        String oneId = one.trim();
                        if (!oneId.isEmpty()) rc.ids.add(oneId);
                    }
                } else if (kind == 'c') {
                    int eq = val.lastIndexOf('=');
                    if (eq < 0) return null;
                    String cn = val.substring(eq + 1);
                    Class<?> c = cn.isEmpty() ? null : classForNameU(cn);
                    rc.cmap.put(val.substring(0, eq), c);
                }
            }
            if (!sawKey) { sCacheDiag += "|sawKey=0"; return null; }
            sCacheDiag += "|HIT rules=" + rc.rules.size() + " q=" + rc.qmap.size()
                    + " c=" + rc.cmap.size() + " skipped=" + skipped
                    + " gates=" + (rc.qmap.containsKey(BGM_GATES_KEY) ? 1 : 0)
                    + " hdr=" + (rc.qmap.containsKey("sc-hdr-combined") ? 1 : 0);
            lg(5, TAG, "签名缓存命中 rules=" + rc.rules.size() + " q=" + rc.qmap.size()
                    + " c=" + rc.cmap.size() + " skipped=" + skipped);
            return rc;
        } catch (Throwable t) {
            lg(5, TAG, "读签名缓存失败: " + t);
            return null;
        }
    }

    private static void saveRuleCache(Map<String, List<Method>> ruleMethods, Sp sp) {
        try {
            String key = ulCacheKey(sAi);
            if (key == null) return;
            StringBuilder sb = new StringBuilder(4096);
            sb.append("k=").append(key).append('\n');
            // n= 行：写入时的规则全集（不只含定位成功的那些）。
            // 模块升级新增规则后，旧缓存的 n= 不含新 id，读取端据此判为不完整，
            // 自动退回 DexKit 重跑一次定位，避免新规则静默 miss。
            sb.append("n=");
            boolean firstId = true;
            for (Rule r : RULES) {
                if (!firstId) sb.append(',');
                firstId = false;
                sb.append(r.id);
            }
            for (Rule r : SPECIALS) {
                if (!firstId) sb.append(',');
                firstId = false;
                sb.append(r.id);
            }
            sb.append('\n');
            for (Rule r : RULES) {
                List<Method> ms = ruleMethods.get(r.id);
                if (ms == null) continue;
                sb.append("r=").append(r.id).append('=');
                for (int i = 0; i < ms.size(); i++) {
                    if (i > 0) sb.append('|');
                    sb.append(sigOfU(ms.get(i)));
                }
                sb.append('\n');
            }
            if (sp != null && sp.resolvedM != null) {
                for (Map.Entry<String, List<Method>> e : sp.resolvedM.entrySet()) {
                    sb.append("q=").append(e.getKey()).append('=');
                    List<Method> ms = e.getValue();
                    for (int i = 0; i < ms.size(); i++) {
                        if (i > 0) sb.append('|');
                        sb.append(sigOfU(ms.get(i)));
                    }
                    sb.append('\n');
                }
                for (Map.Entry<String, Class<?>> e : sp.resolvedC.entrySet()) {
                    Class<?> c = e.getValue();
                    sb.append("c=").append(e.getKey()).append('=')
                            .append(c == null ? "" : c.getName()).append('\n');
                }
            }
            File cf = ulCacheFile();
            boolean wrote = writeAllTextAtomic(cf, sb.toString());
            sCacheSave = cf.getAbsolutePath() + "|ok=" + wrote + "|bytes=" + sb.length()
                    + "|rules=" + ruleMethods.size();
            lg(5, TAG, "签名缓存写入 " + sCacheSave);
        } catch (Throwable t) {
            lg(5, TAG, "写签名缓存失败: " + t);
        }
    }

    /**
     * 缓存是否覆盖了当前全部规则（onTable + onSp）。
     * 缺某个 id ⇒ 该规则是本模块升级后新增/改名的，不能让它静默 miss。
     * 旧格式缓存（无 n= 行 → ids 为空）同样判为不完整：升级后只多跑一次全量，随后恢复命中。
     */
    private static boolean cacheCovers(RuleCache rc, List<Rule> onTable, List<Rule> onSp) {
        if (rc == null || rc.ids.isEmpty()) return false;
        for (Rule r : onTable) {
            if (!rc.ids.contains(r.id)) return false;
        }
        for (Rule r : onSp) {
            if (!rc.ids.contains(r.id)) return false;
        }
        // 中间结果校验：「识别BGM」的可点性 gate 方法集必须已在缓存里。
        // 否则缓存命中时 bgmGates() 无 bridge 可用（bridge==），video_bgm_app
        // 静默失效（state=1 但 hits=0、视频工具箱里项目置灰），且需人工清缓存才能恢复。
        for (Rule r : onSp) {
            if (r.id != null && r.id.startsWith("video_bgm")) {
                List<Method> g = rc.qmap.get(BGM_GATES_KEY);
                if (g == null || g.isEmpty()) {
                    sCacheDiag += "|no-gates-cache";
                    return false;
                }
                break;
            }
        }
        return true;
    }

    /** 缓存命中：纯反射安装，无 DexKit，<100ms 就位 */
    private static int[] installFromCache(List<Rule> onTable, List<Rule> onSp, RuleCache rc) {
        int hooked = 0, miss = 0, fail = 0;
        for (Rule r : onTable) {
            List<Method> ms = rc.rules.get(r.id);
            if (ms == null || ms.isEmpty()) {
                r.state = 2;
                miss++;
                continue;
            }
            int ok = 0;
            for (Method m : ms) if (hookOne(r, m)) ok++;
            r.hits = 0;
            if (ok > 0) {
                r.state = 1;
                hooked++;
            } else {
                r.state = 3;
                fail++;
            }
        }
        Sp sp = new Sp(null, null, rc.qmap, rc.cmap);
        for (Rule r : onSp) {
            try {
                int n = installSpecial(r, sp);
                if (n > 0) {
                    r.state = 1;
                    hooked++;
                } else {
                    r.state = 2;
                    miss++;
                    lg(4, TAG, "专用项未命中(缓存) " + r.id);
                }
            } catch (Throwable t) {
                r.state = 3;
                fail++;
                r.where = "异常 " + t.getClass().getSimpleName();
                lg(5, TAG, "专用项失败(缓存) " + r.id + ": " + t);
            }
        }
        return new int[]{hooked, miss, fail, 0};
    }

    // ─────────────────────────── 安装主流程 ───────────────────────────

    private static volatile boolean sUsedDexkit;
    private static volatile boolean sUsedFallback;
    private static volatile boolean sDkLoaded;

    private static boolean ensureDexKit() {
        if (sDkLoaded) return true;
        try {
            System.loadLibrary("dexkit");
            sDkLoaded = true;
            return true;
        } catch (Throwable t) {
            lg(5, TAG, "加载 libdexkit 失败: " + t);
            return false;
        }
    }

    /** 统计当前配置下打开的规则数（prefs/侧车的内存快照，纯内存读取）。 */
    private static int countOnRules() {
        // 按进程只统计本进程真正会挂的规则集：guardprovider 进程只认 gp_*，
        // 手机管家进程只认 RULES + 非 gp 的 SPECIALS（gp_dbguard 按 guardRule 归此类）。
        // 否则管家规则默认开启会让 guardprovider 进程误判「有规则启用」，白跑一轮 DexKit 且污染状态文件。
        int enabled = 0;
        if (!sIsGuard) {
            for (Rule r : RULES) if (cfg(r)) enabled++;
        }
        for (Rule r : SPECIALS) {
            if (guardRule(r) == sIsGuard && cfg(r)) enabled++;
        }
        return enabled;
    }

    private static int[] doInstall() {
        // 全部规则都安装 hook，开关交给 Hooker 里的运行时门控（ruleOn）判定：
        int enabled = countOnRules();
        if (enabled == 0) {
            // 时序兜底：宿主进程刚被拉起时配置源可能尚未就绪（实测 force-stop 后
            // 2s 内启动会读到空配置 → 全部 skip，表现为「所有解锁功能突然失效」）。
            // 有限重试（最多 ~3.2s，仍在后台线程），等 remote prefs / 侧车挂上再判定。
            for (int i = 0; i < 8 && enabled == 0; i++) {
                try {
                    Thread.sleep(400L);
                } catch (InterruptedException ie) {
                    break;
                }
                try {
                    if (sPrefs != null) {
                        synchronized (CFG_LOCK) {
                            sCfgAt = SystemClock.uptimeMillis();
                            refreshPrefs();
                        }
                    } else {
                        loadSystemCfg();
                    }
                } catch (Throwable ignored) {
                }
                enabled = countOnRules();
            }
            if (enabled > 0) {
                lg(5, TAG, "配置源延迟就绪，重试后启用规则数=" + enabled + " src=" + sCfgSrc);
            }
        }
        sOnCount = enabled;
        sAnyOn = enabled > 0;
        List<Rule> onTable = new ArrayList<Rule>();
        if (!sIsGuard) {
            // guardprovider 进程里不存在手机管家的类，挂它们只会白跑 DexKit，
            // 并且这些 miss 会被写进状态文件污染设置页的命中显示。
            onTable.addAll(Arrays.asList(RULES));
        }
        List<Rule> onSp = new ArrayList<Rule>();
        for (Rule r : SPECIALS) {
            // gp_* 属于系统安全组件，只在 guardprovider 进程安装；反之手机管家进程
            // 也不该去装它们——那边没有这些锚点，查询只会白花 DexKit 时间并污染状态。
            // 例外是 gp_dbguard：拦的是手机管家进程自己写的 fraud_detection_data.db，
            // 由 guardRule 判定为手机管家侧，不走系统安全组件分流。
            if (sIsGuard == guardRule(r)) {
                onSp.add(r);
            }
        }
        if (!sAnyOn) {
            writeStatus(new int[]{0, 0, 0, 1});
            lg(4, TAG, "没有启用任何规则，跳过");
            return new int[]{0, 0, 0, 1};
        }
        if (sAi == null || sAi.sourceDir == null) {
            writeStatus(new int[]{0, 0, 0, 1});
            lg(4, TAG, "无宿主代码，跳过");
            return new int[]{0, 0, 0, 1};
        }

        // 签名缓存命中：纯反射安装，无 DexKit（<100ms），赶上手机管家进程存活窗口。
        // 手机管家进程（主进程/:ui/.remote）存活窗口仅 0.5~4s，DexKit 全量解析 3.9~6.4s
        // 必然赶不上；命中缓存后只剩若干次 Class.forName + getDeclaredMethod。
        RuleCache rc = loadRuleCache();
        if (rc != null && !cacheCovers(rc, onTable, onSp)) {
            // 模块升级新增/改名了规则，旧缓存的规则全集不含这些 id：整体退回 DexKit
            // 重定位一次（一次性，重写完缓存即恢复命中），而不是让新规则静默 miss。
            lg(4, TAG, "缓存规则集不完整（模块已更新），退回 DexKit 重定位");
            rc = null;
        }
        if (rc != null) {
            int[] cached = installFromCache(onTable, onSp, rc);
            // 兜底：缓存虽命中却一条 hook 都没装上（宿主 dex 变了/key 碰撞/
            // 签名解析失败过多），退回 DexKit 全量定位并重写缓存。
            if (cached[0] > 0 || onTable.isEmpty()) {
                sVia = "cache";
                return cached;
            }
            lg(5, TAG, "缓存命中但零 hook，退回 DexKit 重新定位");
        }

        DexKitBridge bridge = null;
        try {
            if (ensureDexKit()) {
                long t0 = SystemClock.uptimeMillis();
                bridge = DexKitBridge.create(sAi.sourceDir);
                lg(4, TAG, "DexKitBridge.create " + (SystemClock.uptimeMillis() - t0) + "ms");
            }
        } catch (Throwable t) {
            lg(5, TAG, "创建 DexKit bridge 失败，转用回退定位: " + t);
            bridge = null;
        }

        Map<String, MethodDataList> cache = new LinkedHashMap<String, MethodDataList>();
        Map<String, List<Method>> ruleMethods = new HashMap<String, List<Method>>();
        int hooked = 0, miss = 0, fail = 0;
        try {
            for (Rule r : onTable) {
                List<Method> ms;
                try {
                    ms = locate(r, bridge, cache);
                } catch (Throwable t) {
                    ms = Collections.emptyList();
                    lg(5, TAG, "定位异常 " + r.id + ": " + t);
                }
                ruleMethods.put(r.id, ms == null ? Collections.<Method>emptyList() : ms);
                if (ms.isEmpty()) {
                    r.state = 2;
                    miss++;
                    lg(4, TAG, "未命中 " + r.id + " 锚点=" + r.anchor + " 类=" + r.cls);
                    continue;
                }
                int ok = 0;
                for (Method m : ms) if (hookOne(r, m)) ok++;
                r.hits = 0;
                if (ok > 0) {
                    r.state = 1;
                    hooked++;
                } else {
                    r.state = 3;
                    fail++;
                }
            }

            Sp sp = new Sp(bridge, cache,
                    new HashMap<String, List<Method>>(), new HashMap<String, Class<?>>());
            for (Rule r : onSp) {
                try {
                    int n = installSpecial(r, sp);
                    if (n > 0) {
                        r.state = 1;
                        hooked++;
                    } else {
                        r.state = 2;
                        miss++;
                        lg(4, TAG, "专用项未命中 " + r.id);
                    }
                } catch (Throwable t) {
                    r.state = 3;
                    fail++;
                    r.where = "异常 " + t.getClass().getSimpleName();
                    lg(5, TAG, "专用项失败 " + r.id + ": " + t);
                }
            }

            // 完整跑完后写签名缓存（只有 DexKit 可用时才写，避免把「DexKit 临时失败」
            // 固化成「SPECIALS 全 miss」的错误缓存）。
            if (bridge != null) saveRuleCache(ruleMethods, sp);
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        sVia = sUsedDexkit ? (sUsedFallback ? "dexkit+fallback" : "dexkit")
                : (sUsedFallback ? "fallback" : "-");
        return new int[]{hooked, miss, fail, 0};
    }

    // ─────────────────────────── 定位引擎 ───────────────────────────

    private static MethodDataList query(DexKitBridge bridge, String anchor, int[] nums,
                                        String clsAnchor, Map<String, MethodDataList> cache) {
        if (bridge == null || (anchor == null && clsAnchor == null && nums == null)) return null;
        String key = (anchor == null ? "" : anchor) + '\u0001'
                + (clsAnchor == null ? "" : clsAnchor) + '\u0001'
                + (nums == null ? "" : Arrays.toString(nums));
        MethodDataList hit = cache.get(key);
        if (hit != null) return hit;
        try {
            MethodMatcher mm = MethodMatcher.create();
            if (anchor != null && !anchor.isEmpty()) {
                String[] parts = anchor.split("&");
                if (parts.length == 1) {
                    mm.usingStrings(parts[0]);
                } else {
                    mm.usingStrings(parts);
                }
            }
            if (clsAnchor != null && !clsAnchor.isEmpty()) {
                mm.declaredClass(ClassMatcher.create().usingStrings(clsAnchor));
            }
            if (nums != null && nums.length > 0) {
                Number[] boxed = new Number[nums.length];
                for (int i = 0; i < nums.length; i++) boxed[i] = nums[i];
                mm.usingNumbers(boxed);
            }
            MethodDataList list = bridge.findMethod(FindMethod.create().matcher(mm));
            if (list != null) cache.put(key, list);
            return list;
        } catch (Throwable t) {
            lg(5, TAG, "DexKit 查询失败 [" + key + "]: " + t);
            return null;
        }
    }

    private static boolean shapeMatch(MethodData md, Rule r) {
        if (r == null) return true;
        try {
            if (r.ret != null && !r.ret.equals(norm(md.getReturnTypeName()))) return false;
            if (r.params != null) {
                ClassDataList pt = md.getParamTypes();
                int n = pt == null ? 0 : pt.size();
                if (n != r.params.length) return false;
                for (int i = 0; i < n; i++) {
                    ClassData cd = pt.get(i);
                    if (cd == null || !r.params[i].equals(norm(cd.getName()))) return false;
                }
            }
            if (r.owner != null) {
                String dn = dot(md.getDeclaredClassName());
                if (!dn.startsWith(r.owner)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按名字 + 形状解析反射方法；同名重载优先取第一个匹配形状的 */
    private static Method reflectMethod(String ownerDot, String name, Rule r) {
        try {
            Class<?> c = Class.forName(ownerDot, false, sCl);
            Method first = null;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (first == null) first = m;
                if (shapeOk(m, r)) return m;
            }
            return first;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method reflectBy(String ownerDot, String name, Rule r) {
        try {
            Class<?> c = Class.forName(ownerDot, false, sCl);
            Method byName = null;
            Method any = null;
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (name != null && !m.getName().equals(name)) continue;
                if (name != null && shapeOk(m, r)) return m;
                if (name != null && byName == null) byName = m;
                if (name == null && shapeOk(m, r) && any == null) any = m;
            }
            return name != null ? byName : any;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean shapeOk(Method m, Rule r) {
        if (r == null) return true;
        if (r.ret != null && !r.ret.equals(desc(m.getReturnType()))) return false;
        if (r.params != null && !paramMatch(m, r.params)) return false;
        if (r.owner != null && !m.getDeclaringClass().getName().startsWith(r.owner)) return false;
        return true;
    }

    private static boolean paramMatch(Method m, String[] want) {
        if (want == null) return true;
        Class<?>[] pt = m.getParameterTypes();
        if (pt.length != want.length) return false;
        for (int i = 0; i < pt.length; i++) {
            if (!want[i].equals(desc(pt[i]))) return false;
        }
        return true;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static List<Method> locate(Rule r, DexKitBridge bridge,
                                       Map<String, MethodDataList> cache) {
        List<Method> out = new ArrayList<Method>();
        // 支持三种锚点：方法级字符串(anchor) / 类级字符串(cls) / 数字(nums)。
        // 只有 cls 或 nums 时同样要发查询 —— 很多目标的字符串落在同类的另一个方法里，
        // 只能用「类级 usingStrings + 方法形状」命中（HyperCeiler 的通行做法）。
        if (bridge != null && (notEmpty(r.anchor) || notEmpty(r.cls)
                || (r.nums != null && r.nums.length > 0))) {
            // anchor 里 '|' 表示 OR（多个候选锚点）；没有 anchor 时按单次查询走 cls/nums
            String[] ors = notEmpty(r.anchor) ? r.anchor.split("\\|") : new String[]{null};
            for (String part : ors) {
                String or = part == null ? null : part.trim();
                if (or != null && or.isEmpty()) continue;
                MethodDataList list = query(bridge, or, r.nums, r.cls, cache);
                if (list == null) continue;
                for (int i = 0; i < list.size(); i++) {
                    MethodData md = list.get(i);
                    if (!shapeMatch(md, r)) continue;
                    Method m = reflectMethod(dot(md.getDeclaredClassName()), md.getName(), r);
                    if (m != null && !contains(out, m)) out.add(m);
                }
            }
            if (!out.isEmpty()) {
                sUsedDexkit = true;
                r.where = notEmpty(r.anchor) ? ("锚点 " + r.anchor) : ("类锚点 " + r.cls);
                return out;
            }
        }
        if (r.fbOwner != null) {
            if (r.fbName == null) {
                Method m = reflectBy(dot(r.fbOwner), null, r);
                if (m != null) out.add(m);
            } else {
                for (String nm : r.fbName.split(",")) {
                    String t = nm.trim();
                    if (t.isEmpty()) continue;
                    Method m = reflectBy(dot(r.fbOwner), t, r);
                    if (m != null && !contains(out, m)) out.add(m);
                }
            }
            if (!out.isEmpty()) {
                sUsedFallback = true;
                r.where = "回退 " + r.fbOwner + (r.fbName == null ? "" : "#" + r.fbName);
                return out;
            }
        }
        r.where = "未定位";
        return out;
    }

    // ─────────────────────────── hook 引擎 ───────────────────────────

    interface SpAction {
        Object run(XposedInterface.Chain chain, Rule r) throws Throwable;
    }

    static final class Hooker implements XposedInterface.Hooker {
        final Rule rule;
        final SpAction action;
        /** 挂载时预计算的配置键：拦截回调里不再拼字符串 */
        final String cfgKey;

        Hooker(Rule r, SpAction a) {
            this.rule = r;
            this.action = a;
            this.cfgKey = r == null ? null : CFG_PREFIX + r.id;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Rule r = rule;
            if (r != null) {
                // 「原生已支持」侦测：与本规则开关无关，进程内首次被调用时采样一次。
                // 影子执行原方法拿到真实返回值，再和 hook 要写入的目标值对比：
                // 相等 ⇒ 该机型 / 该版本本来就支持，开不开没有区别，设置页据此置灰告知。
                Object nat = null;
                boolean probed = false;
                if (r.natState == 0) {
                    if (natComparable(chain, r, action)) {
                        nat = natSample(r, action, chain);
                        probed = true;
                    } else {
                        // void 返回（跳过执行型）/ 动作语义未知：返回值对比没有意义，
                        // 记为「不参与判定」（5）。这类规则的「原生已支持」只能靠设备能力
                        // 判据回答，见 natCapPass —— 绝不能因为 proceed() 返回 null 就当成
                        // 「原生相同」而错误置灰。
                        r.natState = 5;
                    }
                }
                // 运行时开关：设置页关掉后立即放过原逻辑，无需重启目标进程。
                // 早期版本 hook 一旦安装就永久生效 —— 这是「打开之后关不掉」的根因。
                if (!ruleOn(r, cfgKey)) return probed ? nat : chain.proceed();
                r.hits++;
            }
            if (action != null) return action.run(chain, r);
            return onIntercept(chain, r);
        }
    }

    /** 「原生已支持」主动探测候选：参数可安全合成（静态零参 / 静态单 Context） */
    private static final List<Object[]> NAT_CANDS = new ArrayList<>();
    private static volatile boolean sNatProbedOnce;

    /**
     * 登记「原生已支持」主动探测候选，让设置页不必等用户碰到该功能就能看到结果。
     *
     * <p>只挑参数能安全合成的形态：静态 + 零参，或静态 + 恰好一个 Context（用手机管家
     * 自己的 Context）。绝不伪造 String / int 等业务参数 —— 传错参数可能让方法返回
     * 「不支持」，反而把「本来就支持」误判成「有差异」。实例方法拿不到 this，一律不登记；
     * void 返回的方法没有可对比的值，也一律不登记。</p>
     */
    static void regNatCandidate(Rule r, Method m, SpAction a) {
        if (r == null || m == null) return;
        if (m.getReturnType() == Void.TYPE) return;
        if (natTarget(r, a) == NAT_NO) return;
        if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) return;
        Class<?>[] ps = m.getParameterTypes();
        if (ps.length > 1) return;
        if (ps.length == 1 && ps[0] != Context.class) return;
        synchronized (NAT_CANDS) {
            NAT_CANDS.add(new Object[]{r, m});
        }
    }

    private static boolean hookOne(Rule r, Method m) {
        try {
            m.setAccessible(true);
            sMod.hook(m).intercept(new Hooker(r, null));
            regNatCandidate(r, m, null);
            return true;
        } catch (Throwable t) {
            lg(5, TAG, "hook 失败 " + r.id + " " + m + ": " + t);
            return false;
        }
    }

    /** 通用方法 hook（专用项使用） */
    static boolean hookInitializer(Rule r, Class<?> target, SpAction act) {
        try {
            sMod.hookClassInitializer(target).intercept(new Hooker(r, act));
            return true;
        } catch (Throwable error) {
            lg(5, TAG, "静态初始化 hook 失败 " + r.id + ": " + error);
            return false;
        }
    }

    static boolean hookMethod(Rule r, Method m, SpAction act) {
        if (m == null) return false;
        try {
            m.setAccessible(true);
            sMod.hook(m).intercept(new Hooker(r, act));
            regNatCandidate(r, m, act);
            return true;
        } catch (Throwable t) {
            lg(5, TAG, "hook 失败 " + (r == null ? m.getName() : r.id) + " " + m + ": " + t);
            return false;
        }
    }

    /** 构造器 hook（专用项使用） */
    static boolean hookConstructor(Rule r, Constructor<?> k, SpAction act) {
        if (k == null) return false;
        try {
            k.setAccessible(true);
            sMod.hook(k).intercept(new Hooker(r, act));
            return true;
        } catch (Throwable t) {
            lg(5, TAG, "hook 构造器失败 " + (r == null ? k.getName() : r.id) + " " + k + ": " + t);
            return false;
        }
    }

    /**
     * 反射字段缓存：类(按 Class 身份，避免同名不同 ClassLoader 混淆) -> 字段名 -> Field。
     *
     * <p>命中 {@link #FIELD_ABSENT} 表示该类沿继承链确实没有这个字段（负缓存），
     * 免得每次都把整条继承链扫一遍。这些字段读取发生在 hook 回调里
     * （通知气泡解锁、应用限制等），属于每次调用都要走的路径。</p>
     */
    private static final Map<Class<?>, Map<String, Object>> FIELD_CACHE =
            new ConcurrentHashMap<Class<?>, Map<String, Object>>();
    private static final Object FIELD_ABSENT = new Object();

    /** 沿继承链解析字段并缓存（含负缓存）；找不到返回 null */
    private static Field fieldOf(Class<?> c, String name) {
        Map<String, Object> m = FIELD_CACHE.get(c);
        if (m == null) {
            Map<String, Object> fresh = new ConcurrentHashMap<String, Object>();
            Map<String, Object> prev = FIELD_CACHE.putIfAbsent(c, fresh);
            m = prev != null ? prev : fresh;
        }
        Object hit = m.get(name);
        if (hit != null) {
            return hit == FIELD_ABSENT ? null : (Field) hit;
        }
        Field found = null;
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                found = f;
                break;
            } catch (Throwable ignore) {
            }
        }
        m.put(name, found == null ? FIELD_ABSENT : found);
        return found;
    }

    /** 反射读实例字段（沿父类向上查找） */
    static Object getObjectField(Object obj, String name) {
        if (obj == null) return null;
        Field f = fieldOf(obj.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(obj);
        } catch (Throwable ignore) {
            // 缓存字段不可读（例如同名类来自另一个 ClassLoader）时，回退到逐层查找，
            // 保持与旧实现「读失败继续往上找」完全一致的语义。
            return getObjectFieldSlow(obj, name);
        }
    }

    private static Object getObjectFieldSlow(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignore) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 反射写实例字段（沿父类向上查找） */
    static boolean setObjectField(Object obj, String name, Object value) {
        if (obj == null) return false;
        Field f = fieldOf(obj.getClass(), name);
        if (f == null) return false;
        try {
            f.set(obj, value);
            return true;
        } catch (Throwable ignore) {
            return setObjectFieldSlow(obj, name, value);
        }
    }

    private static boolean setObjectFieldSlow(Object obj, String name, Object value) {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return true;
            } catch (Throwable ignore) {
            }
            c = c.getSuperclass();
        }
        return false;
    }

    static boolean setBooleanField(Object obj, String name, boolean value) {
        return setObjectField(obj, name, value);
    }

    /** 反射读静态字段（沿父类向上查找） */
    static Object getStaticObjectField(Class<?> c, String name) {
        if (c == null) return null;
        Field f = fieldOf(c, name);
        if (f == null) return null;
        try {
            return f.get(null);
        } catch (Throwable ignore) {
            return getStaticObjectFieldSlow(c, name);
        }
    }

    private static Object getStaticObjectFieldSlow(Class<?> c, String name) {
        Class<?> k = c;
        while (k != null) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(null);
            } catch (Throwable ignore) {
            }
            k = k.getSuperclass();
        }
        return null;
    }

    static Object onIntercept(XposedInterface.Chain chain, Rule r) throws Throwable {
        if (r == null) return chain.proceed();
        switch (r.act) {
            case A_TRUE:
                return Boolean.TRUE;
            case A_FALSE:
                return Boolean.FALSE;
            case A_INT100:
                return 100;
            case A_INT0:
                return 0;
            case A_NULL:
            case A_SKIP:
                return null;
            case A_EMPTY_LIST:
                return new ArrayList<String>();
            case A_ARG0: {
                List<Object> a = chain.getArgs();
                return (a == null || a.isEmpty()) ? null : a.get(0);
            }
            case A_ARG_TRUE: {
                List<Object> a = chain.getArgs();
                if (a != null && !a.isEmpty()) a.set(0, Boolean.TRUE);
                return chain.proceed();
            }
            default:
                return chain.proceed();
        }
    }

    // ──────────────── 「原生已支持」侦测：影子采样 ────────────────

    /** 「不做原生判定」哨兵（动作语义未知 / 原方法有副作用） */
    static final Object NAT_NO = new Object();
    /** 「目标值为空集合」哨兵 */
    static final Object NAT_EMPTY = new Object();

    /** 最近一次状态统计（供采样后重写状态文件） */
    private static volatile int[] sStat;
    private static volatile long sNatFlushMs;
    private static volatile boolean sNatPending;

    /**
     * 该规则的 hook 目标值；返回 {@link #NAT_NO} 表示不做原生判定。
     *
     * <p>只有「返回值替换型」动作才判定：这类动作的语义就是「本方法是个判定/查询」，
     * 影子执行一次几乎没有副作用。而 A_SKIP / A_ARG_TRUE 会真的执行原逻辑（可能写盘、
     * 弹窗、改设置），采样即副作用，坚决不碰。</p>
     */
    static Object natTarget(Rule r, SpAction a) {
        if (a == RET_TRUE) return Boolean.TRUE;
        if (a == RET_FALSE) return Boolean.FALSE;
        if (a == RET_NULL2) return null;
        if (a == CONST0) return 0;
        if (a == CONST100) return 100;
        if (a != null) return NAT_NO;
        switch (r.act) {
            case A_TRUE: return Boolean.TRUE;
            case A_FALSE: return Boolean.FALSE;
            case A_INT100: return 100;
            case A_INT0: return 0;
            case A_NULL: return null;
            case A_EMPTY_LIST: return NAT_EMPTY;
            default: return NAT_NO;
        }
    }

    /**
     * 这次拦截的目标方法是否值得做返回值对比。
     *
     * <p>void 方法必须排除：它「返回」的永远是 null，若 hook 目标值也是 null
     * （A_NULL / RET_NULL2）就会被判成「原生相同」→ 错误置灰。dolby 正是这种
     * 「跳过执行型」规则（目标 DockWindowManagerService.p2()V）。</p>
     */
    static boolean natComparable(XposedInterface.Chain chain, Rule r, SpAction a) {
        if (natTarget(r, a) == NAT_NO) return false;
        try {
            java.lang.reflect.Executable ex = chain.getExecutable();
            if (ex instanceof Method) return ((Method) ex).getReturnType() != Void.TYPE;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 影子执行原方法，把结果与 hook 目标值比较后写入 {@link Rule#natState}。
     *
     * <p>返回原生返回值，供「开关关闭」路径直接复用 —— 保证每次拦截只 proceed 一次。</p>
     */
    static Object natSample(Rule r, SpAction a, XposedInterface.Chain chain) throws Throwable {
        Object tgt = natTarget(r, a);
        r.natState = 4;
        Object nat;
        try {
            nat = chain.proceed();
        } catch (Throwable t) {
            // 原方法抛异常：记为「不适用」，异常原样抛出（与完全没 hook 时行为一致）
            r.natVal = "throw:" + t.getClass().getSimpleName();
            r.natState = 3;
            natFlush();
            throw t;
        }
        r.natVal = natText(nat);
        r.natState = natMatch(nat, tgt) ? 1 : 2;
        natFlush();
        return nat;
    }

    /** 原生返回值是否与 hook 目标值语义一致 */
    static boolean natMatch(Object nat, Object tgt) {
        if (tgt == NAT_NO) return false;
        if (tgt == NAT_EMPTY) {
            return nat instanceof java.util.Collection && ((java.util.Collection<?>) nat).isEmpty();
        }
        if (tgt == null) return nat == null;
        if (tgt instanceof Boolean) return tgt.equals(nat);
        if (tgt instanceof Integer) {
            return nat instanceof Number && ((Number) nat).intValue() == (Integer) tgt;
        }
        return false;
    }

    /** 原生返回值文本（截断 + 去掉会破坏状态文件的字符） */
    static String natText(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        StringBuilder b = new StringBuilder(40);
        b.append(v.getClass().getSimpleName()).append(':');
        for (int i = 0; i < s.length() && b.length() < 32; i++) {
            char c = s.charAt(i);
            b.append((c == '|' || c == '\n' || c == '\r') ? ' ' : c);
        }
        return b.toString();
    }

    /**
     * 采样结果变化后刷新状态文件。
     *
     * <p>采样最多每条规则一次（总量很小），但落在 {@code /sdcard} 这种 FUSE 路径上，
     * 所以做 800ms 节流 + 一次延迟补写：既不轰炸 FUSE，也不丢最后一次结果。</p>
     */
    static void natFlush() {
        long now = System.currentTimeMillis();
        if (now - sNatFlushMs < 800L) {
            if (!sNatPending) {
                sNatPending = true;
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(900L);
                        } catch (InterruptedException ignored) {
                        }
                        sNatPending = false;
                        sNatFlushMs = System.currentTimeMillis();
                        int[] st = sStat;
                        if (st != null) writeStatus(st);
                    }
                }, "camellia-nat");
                t.setPriority(Thread.MIN_PRIORITY);
                t.start();
            }
            return;
        }
        sNatFlushMs = now;
        int[] st = sStat;
        if (st != null) writeStatus(st);
    }

    /** 无视节流立即刷新状态文件（探测收尾用） */
    static void natFlushForce() {
        int[] st = sStat;
        if (st != null) writeStatus(st);
    }

    /**
     * 主动探测：安装完成后，对「零参 + 静态」的目标方法各调用一次。
     *
     * <p>这次调用会命中我们自己挂的 hook，采样就在 intercept 里完成；返回值被丢弃，
     * 对目标进程没有任何可见影响（这正是钩子开着的语义：外界看到的仍是 hook 的目标值）。</p>
     *
     * <p>只做一轮：试不出来的（依赖早期初始化、需要真实参数等）保持「未采样」，
     * 等应用真的调用到那个方法时再被动采样。</p>
     */
    static void natActiveProbe() {
        if (sNatProbedOnce) return;
        sNatProbedOnce = true;
        try {
            // 等目标进程把安装收尾做完再探，避免在 attach 早期调出问题
            Thread.sleep(4000L);
        } catch (InterruptedException ignored) {
        }
        Object[][] cands;
        synchronized (NAT_CANDS) {
            cands = NAT_CANDS.toArray(new Object[0][]);
        }
        for (Object[] c : cands) {
            Rule r = (Rule) c[0];
            Method m = (Method) c[1];
            if (r.natState != 0) continue;
            try {
                if (m.getParameterCount() == 0) {
                    m.invoke(null);
                } else {
                    // 单个 Context 参数：用手机管家自己的 Context（低温柔性等 t(Context) 判定）
                    Context ctx = scContext();
                    if (ctx == null) continue;
                    m.invoke(null, ctx);
                }
            } catch (Throwable ignored) {
                // 没进到目标方法（类未初始化等）：natState 仍是 0，留给被动采样，不误判
            }
        }
        natFlushForce();
    }

    // ──────────── 「原生已支持」设备能力判据（无法采样的规则） ────────────

    /**
     * 设备能力判据：给「不是替换返回值」型规则用。
     *
     * <p>void / 跳过执行 / UI 注入型规则的「原生已支持」不是返回值问题，而是设备或系统
     * 本来就具备该能力的问题（例如杜比全景声是机型能力，跟某个方法返回什么无关），
     * 只能按设备能力回答。返回 null 表示暂无判据 —— 宁可不置灰，也不瞎猜。</p>
     */
    static String capProbe(Rule r) {
        if ("dolby".equals(r.id)) return dolbyCap();
        return null;
    }

    /**
     * 杜比全景声（Dolby Atmos / DAX）是否为该机型的原生能力。
     *
     * <p>实测机型（popsicle / 2509FPN0BC）上
     * {@code ro.vendor.audio.dolby.dax.support=true}、
     * {@code persist.vendor.audio.effectimplenter=dolby}。</p>
     */
    private static String dolbyCap() {
        if (sysPropBool("ro.vendor.audio.dolby.dax.support")) return "device:dolby-dax";
        if ("dolby".equals(sysProp("persist.vendor.audio.effectimplenter"))) {
            return "device:dolby-effect";
        }
        return null;
    }

    /**
     * 跑一遍设备能力判据。
     *
     * <p>能覆盖 natState=0（还没轮到采样）和 5（已确认无法做返回值对比）两种状态；
     * 已经采到结果（1/2）的不覆盖 —— 采样结果比能力判据更贴近真实行为。</p>
     */
    static void natCapPass() {
        for (Rule r : allRules()) {
            int s = r.natState;
            if (s != 0 && s != 5) continue;
            String hit = capProbe(r);
            if (hit != null) {
                r.natVal = hit;
                r.natState = 1;
            }
        }
        natFlushForce();
    }

    private static volatile Method sSpGet;

    /** 读系统属性（反射 android.os.SystemProperties；任何失败都返回 null） */
    static String sysProp(String key) {
        try {
            Method m = sSpGet;
            if (m == null) {
                m = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
                m.setAccessible(true);
                sSpGet = m;
            }
            Object v = m.invoke(null, key);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean sysPropBool(String key) {
        String v = sysProp(key);
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private static volatile Method sSpSet;

    /** 写系统属性（反射 android.os.SystemProperties.set；失败返回 false） */
    static boolean setSysProp(String key, String value) {
        try {
            Method m = sSpSet;
            if (m == null) {
                m = Class.forName("android.os.SystemProperties")
                        .getMethod("set", String.class, String.class);
                m.setAccessible(true);
                sSpSet = m;
            }
            m.invoke(null, key, value);
            return true;
        } catch (Throwable t) {
            lg(4, TAG, "setprop 失败 " + key + "=" + value + ": " + t);
            return false;
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    static boolean contains(List<Method> list, Method m) {
        for (Method o : list) if (o.equals(m)) return true;
        return false;
    }

    static String desc(Class<?> c) {
        if (c == Void.TYPE) return "V";
        if (c == Boolean.TYPE) return "Z";
        if (c == Integer.TYPE) return "I";
        if (c == Long.TYPE) return "J";
        if (c == Float.TYPE) return "F";
        if (c == Double.TYPE) return "D";
        if (c == Byte.TYPE) return "B";
        if (c == Short.TYPE) return "S";
        if (c == Character.TYPE) return "C";
        if (c.isArray()) return "[" + desc(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }

    static String norm(String t) {
        if (t == null) return "";
        String s = t.trim();
        if (s.isEmpty()) return "";
        if (s.equals("void")) return "V";
        if (s.equals("boolean")) return "Z";
        if (s.equals("int")) return "I";
        if (s.equals("long")) return "J";
        if (s.equals("float")) return "F";
        if (s.equals("double")) return "D";
        if (s.equals("byte")) return "B";
        if (s.equals("short")) return "S";
        if (s.equals("char")) return "C";
        if (s.startsWith("L") || s.startsWith("[")) return s;
        return "L" + s.replace('.', '/') + ";";
    }

    static String dot(String name) {
        if (name == null) return "";
        String s = name.trim();
        if (s.startsWith("L") && s.endsWith(";")) s = s.substring(1, s.length() - 1);
        return s.replace('/', '.');
    }

    static String dotOrRaw(String name) {
        return name == null ? "" : (name.startsWith("L") ? dot(name) : name);
    }

    // ─────────────────────────── 专用安装器 ───────────────────────────

    /** 常用动作：直接返回常量（语义等价 HyperCeiler 的 returnConstant） */
    static final SpAction RET_TRUE = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule r) {
            return Boolean.TRUE;
        }
    };
    static final SpAction RET_FALSE = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule r) {
            return Boolean.FALSE;
        }
    };
    static final SpAction RET_NULL2 = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule r) {
            return null;
        }
    };
    static final SpAction CONST0 = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule r) {
            return 0;
        }
    };
    static final SpAction CONST100 = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule r) {
            return 100;
        }
    };
    static final SpAction CONST666 = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule r) {
            return 666;
        }
    };
    /** 第 0 个参数改为 true 后继续执行原方法 */
    static final SpAction ARG_TRUE_NEXT = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule r) throws Throwable {
            List<Object> a = chain.getArgs();
            if (a != null && !a.isEmpty()) a.set(0, Boolean.TRUE);
            return chain.proceed();
        }
    };

    // ==================== 行为库写入拦截（securitycenter 进程） ====================

    /**
     * 行为库数据库文件名。手机管家把「应用行为 / 权限使用 / 隐私访问」这类记录写进
     * 这个库，安全组件与云端据此生成用户行为画像，是本模块最值得断掉的一条数据源。
     */
    static final String GP_DB_FILE = "fraud_detection_data.db";

    /**
     * 行为库内需要拦的表（小写、精确匹配）。
     * <p>当前留空 —— 表名尚未逐条取证。空数组的含义是「该库内所有写操作一律拦截」，
     * 是唯一不会漏的保守口径；等表名确认后直接补进来即可收窄。</p>
     */
    static final String[] GP_DB_TABLES = new String[]{
            // "app_behavior",
            // "privacy_access",
    };

    /**
     * 只有以这些动词开头的 SQL 才算「写数据」，SELECT / PRAGMA / BEGIN 一律放行。
     * <p>刻意不含 create / drop / alter / attach：建库建表属于结构操作，拦掉会让
     * 库根本打不开，反而引发崩溃。本规则只针对「往行为库里写记录」这一件事。</p>
     */
    static boolean gpDbWriteSql(String sql) {
        if (sql == null) {
            return false;
        }
        String s = sql.toLowerCase().trim();
        return s.startsWith("insert") || s.startsWith("update") || s.startsWith("delete")
                || s.startsWith("replace");
    }

    /**
     * 命中判定：先看数据库路径，再看表名。
     * <p>路径反射失败（dbPath == null）时一律放行 —— 宁少拦不误拦，
     * 避免因为拿不到路径就把整个 App 的数据库写入打断。</p>
     */
    static boolean gpDbHit(String dbPath, String table) {
        if (dbPath == null || !dbPath.contains(GP_DB_FILE)) {
            return false;
        }
        if (GP_DB_TABLES.length == 0) {
            return true;                       // 无表名清单 → 该库整库拦
        }
        if (table == null) {
            return false;
        }
        String t = table.toLowerCase().trim();
        for (String g : GP_DB_TABLES) {
            if (g.equals(t)) {
                return true;
            }
        }
        return false;
    }

    /** 反射取 SQLiteDatabase.getPath()，取不到返回 null。 */
    static String gpDbPath(Object db) {
        if (db == null) {
            return null;
        }
        try {
            Method m = db.getClass().getMethod("getPath");
            m.setAccessible(true);
            Object p = m.invoke(db);
            return p instanceof String ? (String) p : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 拦截行为库写入，覆盖 insert / insertOrThrow / insertWithOnConflict /
     * update / delete / execSQL 六条通道。
     * <p>按原方法返回类型给「假成功」而不是抛异常：抛异常会被上层 catch 后重试或
     * 直接把失败上报云端，反而更显眼。long 给 -1、int 给 0、void 直接不 proceed。</p>
     * <p>挂 securitycenter 进程（sIsGuard 为 false 时生效）。</p>
     */
    static int spGpDbGuard(final Rule r) {
        int n = 0;
        try {
            Class<?> dbCls = Class.forName("android.database.sqlite.SQLiteDatabase");
            SpAction act = new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rr) throws Throwable {
                    Object self = chain.getThisObject();
                    String dbPath = gpDbPath(self);
                    if (dbPath == null || !dbPath.contains(GP_DB_FILE)) {
                        return chain.proceed();
                    }
                    Object ex = chain.getExecutable();
                    boolean isExec = ex instanceof Method
                            && "execSQL".equals(((Method) ex).getName());
                    List<Object> args = chain.getArgs();
                    Object first = (args != null && !args.isEmpty()) ? args.get(0) : null;
                    String head = first instanceof String ? (String) first : null;
                    boolean hit;
                    if (isExec) {
                        // execSQL 首参是整条 SQL：先按动词过滤，SELECT / PRAGMA / BEGIN
                        // 这类只读语句必须原样放行，否则表名清单为空时会被整库口径一并拦掉，
                        // 变成「连读都读不了」，直接把 App 的数据库访问打断。
                        if (!gpDbWriteSql(head)) {
                            return chain.proceed();
                        }
                        hit = gpDbHit(dbPath, null);      // 写语句按整库口径判定
                    } else {
                        hit = gpDbHit(dbPath, head);      // 其余五个通道首参就是表名
                    }
                    if (!hit) {
                        return chain.proceed();
                    }
                    Class<?> rt = null;
                    if (ex instanceof Method) {
                        rt = ((Method) ex).getReturnType();
                    }
                    if (rt == null) {
                        return chain.proceed();     // 拿不到返回类型就放弃拦截，避免拆箱 NPE
                    }
                    if (rt == void.class) {
                        return null;
                    }
                    if (rt == long.class) {
                        return Long.valueOf(-1L);
                    }
                    if (rt == int.class) {
                        return Integer.valueOf(0);
                    }
                    return chain.proceed();
                }
            };
            for (Method m : dbCls.getDeclaredMethods()) {
                String name = m.getName();
                if (!("insert".equals(name) || "insertOrThrow".equals(name)
                        || "insertWithOnConflict".equals(name) || "update".equals(name)
                        || "delete".equals(name) || "execSQL".equals(name))) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 0 || ps[0] != String.class
                        || java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                    continue;
                }
                if (hookMethod(r, m, act)) {
                    n++;
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /**
     * 拦截录屏/投屏创建：hook {@code MediaProjection.createVirtualDisplay} 全部重载，
     * 一律返回 null，让调用方拿不到 VirtualDisplay，屏幕画面无法被抓取。
     * <p>挂 guardprovider 进程（sIsGuard 为 true 时生效）—— 安全组件是在这个进程里
     * 向系统申请投屏来抓屏的。</p>
     * <p>已知风险：手机管家侧调用点（Lpf3 的 :1757）拿到 null 后是否判空尚未确认，
     * 若直接解引用可能 NPE 甚至崩溃。因此本规则默认关闭，仅在需要时手动打开。</p>
     */
    static int spGpCapture(final Rule r) {
        int n = 0;
        try {
            Class<?> cls = Class.forName("android.media.projection.MediaProjection");
            SpAction act = new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rr) throws Throwable {
                    return null;                 // createVirtualDisplay() 返回 null
                }
            };
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().startsWith("createVirtualDisplay")) {
                    continue;
                }
                if (m.getReturnType() == void.class
                        || java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                    continue;
                }
                if (hookMethod(r, m, act)) {
                    n++;
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    static Method one(List<Method> list) {
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    static int installSpecial(Rule r, Sp sp) {
        String id = r.id;
        // 行为库写入拦截不走 DexKit 锚点，直接反射系统 SQLiteDatabase；
        // 必须放在最前面短路：下面的 gp_* 分支都会先去做锚点解析，慢且会白跑。
        if ("pv_net_sec".equals(id)) {
            return spPvNetSec(r);
        }
        if ("gp_dbguard".equals(id)) {
            return spGpDbGuard(r);
        }
        // 系统安全组件各条：只在 guardprovider 进程安装。手机管家进程里命中时直接 0，
        // 既不拿不存在的锚点空跑 DexKit，也让该规则在管家进程的状态保持「未定位」。
        if ("gp_applist".equals(id)) return sIsGuard ? spGpAppList(r, sp) : 0;
        if ("gp_devstate".equals(id)) return sIsGuard ? spGpDevState(r, sp) : 0;
        if ("gp_push".equals(id)) return sIsGuard ? spGpPush(r, sp) : 0;
        // AIGC 检测上报 / 云查杀请求与 gp_applist 同形：都是「URL 字面量所在方法
        // 就是上报出口」，共用同一套假成功处理，只是锚点不同。
        if ("gp_aigc".equals(id)) return sIsGuard ? spGpAppList(r, sp) : 0;
        if ("gp_cloudscan".equals(id)) return sIsGuard ? spGpAppList(r, sp) : 0;
        // OneTrack 埋点走类级 hook，不依赖 DexKit 锚点（DexKit 不可用时仍会安装）
        if ("gp_track".equals(id)) return sIsGuard ? spGpTrack(r) : 0;
        // 录屏抓屏同样不依赖 DexKit 锚点，只在 guardprovider 进程安装
        if ("gp_capture".equals(id)) return sIsGuard ? spGpCapture(r) : 0;
        if ("picture_enhance".equals(id) || "dual_visual".equals(id)
                || "video_more".equals(id) || "video_bgm".equals(id) || "video_bgm_app".equals(id)
                || "video_cast".equals(id) || "video_screen_off".equals(id)
                || "video_subtitle".equals(id) || "video_settings".equals(id)
                || "game_fill_blacklist".equals(id)
                || "bypass_plus".equals(id) || "video_dfps".equals(id) || "game_hdr".equals(id)
                || "game_fill".equals(id) || "cooling_fan".equals(id) || "gpu_enhance".equals(id)
                || "game_quick_start".equals(id) || "car_accident".equals(id))
            return SecurityDiffFeatures.install(r, sp);
        if ("swl_tip".equals(id)) return spSwlTip(r, sp);
        if ("screen_used_time".equals(id)) return spScreenUsedTime(r, sp);
        if ("power_rank".equals(id)) return spPowerRank(r, sp);
        if ("beauty_privacy".equals(id)) return spBeautyPrivacy(r, sp);
        if ("offline_info".equals(id)) return spOfflineInfo(r);
        if ("car_sickness_tip".equals(id)) return spCarSicknessTip(r, sp);
        if ("score".equals(id)) return spScore(r, sp);
        if ("skip_countdown".equals(id)) return spSkipCountdown(r, sp);
        if ("simlock_auth".equals(id)) return spSimLockAuth(r, sp);
        if ("simlock_dialog".equals(id)) return spSimLockDialog(r);
        if ("privacy_blur".equals(id)) return spPrivacyBlur(r, sp);
        if ("macro_blacklist".equals(id)) return spMacroBlacklist(r, sp);
        if ("dolby".equals(id)) return spDolby(r, sp);
        if ("video_func".equals(id)) return spVideoFunc(r, sp);
        if ("no_low_battery".equals(id)) return spNoLowBattery(r);
        if ("simplify_main".equals(id)) return spSimplifyMain(r);
        if ("battery_health".equals(id)) return spBatteryHealth(r, sp);
        if ("more_battery_info".equals(id)) return spMoreBatteryInfo(r, sp);
        if ("app_lock_scramble".equals(id)) return spAppLockScramble(r);
        if ("open_app_popup".equals(id)) return spOpenAppPopup(r);
        if ("adb_install_bypass".equals(id)) return spAdbInstallBypass(r, sp);
        if ("app_restrict".equals(id)) return spAppRestrict(r);
        if ("app_default_sort".equals(id)) return spAppDefaultSort(r);
        if ("bubble_unlock".equals(id)) return spBubbleUnlock(r);
        if ("battery_temp".equals(id)) return spBatteryTemp(r);
        if ("aosp_app_manager".equals(id)) return spAppManagerEntry(r);
        if ("aosp_app_info".equals(id)) return spAppInfoEntry(r);
        if ("xsof_block".equals(id)) return spXsofBlock(r, sp);
        if ("video_temp".equals(id)) return spVideoTemp(r, sp);
        if ("game_speed".equals(id)) return spGameSpeed(r, sp);
        if ("hide_sidebar".equals(id)) return spHideSidebar(r);
        if ("sidebar_bg".equals(id)) return spSidebarBg(r);
        if ("sidebar_suggest".equals(id)) return spSidebarSuggest(r);
        if ("sidebar_limit".equals(id)) {
            try { return SidebarLimit.install(r, sp, sCl); }
            catch (Throwable t) { lg(5, TAG, "侧边栏数量定位失败: " + t); return 0; }
        }
        if ("sidebar_freeform".equals(id)) {
            try { return SidebarFreeform.install(r, sp, sCl); }
            catch (Throwable t) { lg(5, TAG, "侧边栏小窗快捷项安装失败: " + t); return 0; }
        }
        if ("sidebar_shortcut".equals(id)) {
            try { return SidebarShortcut.install(r, sp, sCl); }
            catch (Throwable t) { lg(5, TAG, "侧边栏快捷方式面板安装失败: " + t); return 0; }
        }
        return 0;
    }

    // ───────────────── 全局侧边栏背景自定义（移植 HyperCeiler BlurSecurity 的 dock 栏部分） ─────────────────

    /** 侧边栏背景色配置键（侧车文本，16 进制不带前缀，如 CC000000） */
    static int sidebarLimit() {
        int value = 50;
        try {
            SharedPreferences p = sPrefs == null ? null : sPrefs.prefs();
            if (p != null && p.contains("sidebar_app_limit"))
                return Math.max(16, Math.min(200, p.getInt("sidebar_app_limit", 50)));
        } catch (Throwable ignored) { }
        try {
            String raw = SIDECAR.get("sidebar_app_limit");
            if (raw != null) value = Integer.parseInt(raw);
        } catch (Throwable ignored) { }
        return Math.max(16, Math.min(200, value));
    }

    static final String CFG_SIDEBAR_COLOR = "sb_color";
    /** 默认背景色：半透明黑，接近 MIUI 原生观感 */
    static final int SIDEBAR_COLOR_DEF = 0xCC000000;
    /** 模糊程度配置键（侧车文本，10-99）：与设置页「全局侧边栏背景模糊程度」同键 */
    static final String CFG_SIDEBAR_BLUR = "sb_blur";
    /** 自动莫奈取色开关键（侧车文本，1=未设色时用壁纸主色）：与设置页开关同键 */
    static final String CFG_SIDEBAR_MONET = "sb_monet";

    /**
     * 动态取色方案（默认 0）。取值：
     * 0=图标面积主色 1=壁纸主色 2=图标最饱和 3=图标平均色 4=图标最亮 5=图标提亮版
     * 6=跟随屏幕（screencap 抓帧，异步 + 缓存）
     */
    static final String CFG_SIDEBAR_MODE = "sb_mode";
    /** 模糊程度默认值：对齐 HyperCeiler blurradius 的默认 60 */
    static final int SIDEBAR_BLUR_DEF = 60;
    /** 模糊程度下限 / 上限：上限对齐 HyperCeiler 的 maxValue 99，下限放宽到 10 */
    static final int SIDEBAR_BLUR_MIN = 10;
    static final int SIDEBAR_BLUR_MAX = 99;
    /** 叠加色模式（HyperCeiler 用 103，MIUI 内部 blend mode 常量） */
    static final int SIDEBAR_BLEND_MODE = 103;

    /**
     * 侧边栏背景色。
     *
     * <p><b>「自动莫奈取色」开启时由莫奈完全接管</b>，无视设置里手选的颜色。这样换壁纸后
     * 每次呼出侧边栏都会重新取到新主色，才是真正的「动态取色」；如果只在「没设过色」
     * 时才取，用户一旦选过颜色就永远固定，那就成了静态的一次性取色。</p>
     *
     * <p>关掉莫奈时回到手选色：侧车 {@code sb_color} 能解析就用它，否则用默认色。</p>
     */
    static synchronized int sidebarColor() {
        refreshCfgLight();
        if (sidebarAutoMonet()) {
            int mode = sidebarMode();
            String fg = foregroundPackage();
            int c = 0;
            String from;
            if (mode == 1) {
                // 方案 1：直接吃壁纸主色，不查前台应用
                c = wallpaperPrimaryColor();
                from = "壁纸";
            } else if (mode == 6) {
                // 方案 6：跟随屏幕。取色在后台异步进行，首次（无缓存）必然拿不到，
                // 此时退回壁纸主色，下一次呼出就用上真正的屏幕色。
                c = screenPrimaryColor();
                from = "屏幕";
                if (c == 0) {
                    c = wallpaperPrimaryColor();
                    from = "壁纸";
                }
            } else {
                from = "图标";
                if (fg != null) {
                    c = iconColorByMode(fg, mode);
                }
                // 取不到图标色（桌面 / 设置 / 图标无彩度）→ 退回壁纸主色
                if (c == 0) {
                    c = wallpaperPrimaryColor();
                    from = "壁纸";
                }
            }
            if (c != 0) {
                int out = (c & 0x00FFFFFF) | (SIDEBAR_COLOR_DEF & 0xFF000000);
                if (!sMonetLogged) {
                    sMonetLogged = true;
                    lg(4, TAG, "sidebar_bg 动态取色 方案=" + mode + " 来源=" + from + " pkg=" + fg
                            + " raw=" + String.format("#%08X", Integer.valueOf(c))
                            + " -> " + String.format("#%08X", Integer.valueOf(out)));
                }
                return out;
            }
            if (!sMonetLogged) {
                sMonetLogged = true;
                lg(4, TAG, "sidebar_bg 动态取色两种来源都取不到，退回默认色");
            }
            return SIDEBAR_COLOR_DEF;
        }
        String v = SIDECAR.get(CFG_SIDEBAR_COLOR);
        if (v != null && !v.isEmpty()) {
            try {
                return (int) (Long.parseLong(v.trim(), 16) & 0xFFFFFFFFL);
            } catch (Throwable ignore) {
                // 解析失败退回默认色
            }
        }
        return SIDEBAR_COLOR_DEF;
    }

    /** 自动莫奈取色开关（侧车 sb_monet，默认开） */
    private static boolean sidebarAutoMonet() {
        String v = SIDECAR.get(CFG_SIDEBAR_MONET);
        if (v == null || v.isEmpty()) return true;
        return !"0".equals(v.trim());
    }

    /**
     * 动态取色方案（侧车 sb_mode）：0=图标面积主色 1=壁纸主色 2=图标最饱和
     * 3=图标平均色 4=图标最亮 5=图标提亮版 6=跟随屏幕。
     * 缺省或非法一律按 0（保持旧行为）。
     */
    private static int sidebarMode() {
        String v = SIDECAR.get(CFG_SIDEBAR_MODE);
        if (v == null || v.isEmpty()) return 0;
        try {
            int n = Integer.parseInt(v.trim());
            return (n < 0 || n > 6) ? 0 : n;
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /**
     * 取壁纸主色（Material You / 莫奈取色的轻量版）。
     *
     * <p>不用 MIUI 的 {@code miui.monet} 那套内部实现（版本间类名与签名都在变），
     * 而是走 AOSP 从 API 27 起就稳定的 {@code WallpaperManager.getWallpaperColors()}：
     * 系统已经替我们把壁纸聚类算好了，{@code getPrimaryColor()} 就是「莫奈主色」。
     * 拿不到（无壁纸 / 服务未就绪 / 无权限）时返回 0，调用方退回默认色。</p>
     *
     * <p>结果带 10 秒 TTL 缓存：侧边栏每次呼出都会问一次，而取色要跨进程查壁纸服务，
     * 没必要那么频繁；换壁纸后最多 10 秒也就刷新了。</p>
     */
    private static int wallpaperPrimaryColor() {
        long now = android.os.SystemClock.uptimeMillis();
        int cached = sMonetColor;
        // 缓存压到 2 秒：侧边栏每次呼出都会问一次，换壁纸后很快就能跟上，
        // 同时又不至于把跨进程查壁纸服务的开销放到每一次呼出上。
        if (cached != 0 && now - sMonetAt < 2000L) {
            return cached;
        }
        int out = 0;
        try {
            android.content.Context ctx = currentAppContext();
            if (ctx != null) {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
                android.app.WallpaperColors wc = null;
                if (wm != null) {
                    try {
                        wc = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
                    } catch (Throwable ignore) {
                    }
                    if (wc == null) {
                        try {
                            wc = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_LOCK);
                        } catch (Throwable ignore) {
                        }
                    }
                }
                if (wc != null && wc.getPrimaryColor() != null) {
                    out = wc.getPrimaryColor().toArgb();
                }
            }
        } catch (Throwable t) {
            out = 0;
        }
        sMonetColor = out;
        sMonetAt = now;
        return out;
    }

    // ---------------- 「跟随屏幕」取色（方案 6） ----------------
    //
    // 可行性结论（查过宿主 APK 的 manifest）：
    //   · 宿主 com.miui.securitycenter 以 android.uid.system 运行，但 **没有**
    //     READ_FRAME_BUFFER / CAPTURE_VIDEO_OUTPUT / MANAGE_MEDIA_PROJECTION，
    //     所以 SurfaceControl.captureDisplay、MediaProjection 这类正规截屏接口都走不通；
    //   · 唯一可行路径是调用 screencap 可执行文件：先直接执行（system uid 在部分 ROM 上
    //     被放行，那样零授权成本），失败再退回 su（本机已 root）。
    //
    // 代价与规避（这是本方案必须说清的缺点）：
    //   · 抓一帧 + 解码要几百毫秒，绝不能放进侧边栏呼出的同步路径 —— 所以这里永远
    //     "用缓存 + 后台异步刷新"：首次没有缓存就返回 0，由上层回退到壁纸主色；
    //   · 结果按 TTL 缓存，避免连续呼出反复抓屏。

    private static volatile int sScreenColor;
    private static volatile long sScreenColorAt;
    private static volatile boolean sScreenFetching;
    private static final long SCREEN_TTL_MS = 1500L;

    /**
     * 屏幕主色。有缓存（TTL 内）直接返回；否则返回 0 并触发一次后台抓屏，
     * 下一次呼出即可用上。**永不阻塞调用方**（侧边栏呼出路径对耗时极敏感）。
     */
    private static int screenPrimaryColor() {
        long now = android.os.SystemClock.uptimeMillis();
        int cached = sScreenColor;
        if (cached != 0 && now - sScreenColorAt < SCREEN_TTL_MS) {
            return cached;
        }
        if (!sScreenFetching) {
            sScreenFetching = true;
            try {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            int c = captureScreenPrimary();
                            if (c != 0) {
                                sScreenColor = c;
                                sScreenColorAt = android.os.SystemClock.uptimeMillis();
                            }
                        } catch (Throwable ignore) {
                        } finally {
                            sScreenFetching = false;
                        }
                    }
                }, "camellia-screen-color");
                // 抓屏只是"下一次呼出才用得上"的事，不跟用户操作抢 CPU
                t.setPriority(Thread.MIN_PRIORITY);
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                sScreenFetching = false;
            }
        }
        return cached;
    }

    /** 真正抓一帧并取主色；失败返回 0 */
    private static int captureScreenPrimary() {
        java.io.File f = new java.io.File("/data/local/tmp/.camellia_cap.png");
        try {
            if (f.exists()) f.delete();
            // ① 直接 screencap：宿主机是 system uid，部分 ROM 允许
            if (!execCapture(new String[]{"screencap", "-p", f.getAbsolutePath()})) {
                // ② 退回 su（本机已 root；首次可能需要授权）
                execCapture(new String[]{"su", "-c", "screencap -p " + f.getAbsolutePath()});
            }
            if (!f.exists() || f.length() <= 0L) return 0;
            // 缩略解码：整屏颜色极杂，缩到约 32px 足够定主色，还能省掉绝大部分解码耗时
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = o.outWidth;
            int h = o.outHeight;
            if (w <= 0 || h <= 0) return 0;
            o.inJustDecodeBounds = false;
            o.inSampleSize = Math.max(1, Math.min(w, h) / 32);
            android.graphics.Bitmap bmp =
                    android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bmp == null) return 0;
            int[] c;
            try {
                c = colorCandidatesOfBitmap(bmp, false);
            } finally {
                bmp.recycle();
            }
            // 屏幕取「面积主色」：整屏最主要的色调，最贴合"跟随屏幕"的直觉
            return c[0];
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                if (f.exists()) f.delete();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 执行一条抓屏命令。waitFor 带超时，避免异常环境下把后台线程挂死 */
    private static boolean execCapture(String[] cmd) {
        // 必须写全限定名：本文件已 import android.os.Process，裸写 Process 会被解析成它
        java.lang.Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                return p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                        && p.exitValue() == 0;
            }
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 当前进程的 Application（Xposed 环境下即宿主进程的 Context） */
    private static android.content.Context currentAppContext() {
        java.lang.ref.WeakReference<android.content.Context> ref = sAppContext;
        if (ref != null && ref.get() != null) {
            return ref.get();
        }
        try {
            Class<?> h = Class.forName("android.app.AndroidAppHelper");
            Object app = h.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                android.content.Context c = (android.content.Context) app;
                sAppContext = new java.lang.ref.WeakReference<android.content.Context>(c);
                return c;
            }
        } catch (Throwable ignore) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                android.content.Context c = (android.content.Context) app;
                sAppContext = new java.lang.ref.WeakReference<android.content.Context>(c);
                return c;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /**
     * 取当前前台应用的包名；拿不到返回 {@code null}。
     *
     * <p>手机管家是系统应用，持有 {@code REAL_GET_TASKS}，因此 {@code getTasks} 这条路
     * 在本机上是通的；不同 ROM 上取不到时返回 null，调用方会逐级退回壁纸主色。</p>
     */
    /** 前台包名的短 TTL 缓存：一次侧边栏呼出会问 4 次（本体 / dock / 抽屉页 / 工具箱） */
    private static volatile String sFgPkg;
    private static volatile long sFgPkgAt;
    private static final long FG_PKG_TTL_MS = 300L;

    private static String foregroundPackage() {
        long now = android.os.SystemClock.uptimeMillis();
        String cached = sFgPkg;
        if (cached != null && now - sFgPkgAt < FG_PKG_TTL_MS) {
            return cached.isEmpty() ? null : cached;
        }
        String out = foregroundPackageUncached();
        sFgPkg = out == null ? "" : out;
        sFgPkgAt = now;
        return out;
    }

    private static String foregroundPackageUncached() {
        // ① ActivityTaskManager.getTasks(int)（API 29+ 的 hidden API）
        try {
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Object inst = atm.getMethod("getInstance").invoke(null);
            if (inst != null) {
                // getTasks(int) 属于管理器实例，不能在 getService() 的 Binder 代理上调用。
                java.util.List<?> tasks = (java.util.List<?>) atm
                        .getMethod("getTasks", Integer.TYPE)
                        .invoke(inst, Integer.valueOf(1));
                if (tasks != null && !tasks.isEmpty()) {
                    Object top = tasks.get(0);
                    java.lang.reflect.Field f = top.getClass().getField("topActivity");
                    Object cn = f.get(top);
                    if (cn instanceof android.content.ComponentName) {
                        return ((android.content.ComponentName) cn).getPackageName();
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        // ② ActivityManager.getTasks(int, boolean) / getRunningTasks(int) 兜底
        try {
            android.content.Context ctx = currentAppContext();
            if (ctx != null) {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
                if (am != null) {
                    java.util.List<android.app.ActivityManager.RunningTaskInfo> ts = am.getRunningTasks(1);
                    if (ts != null && !ts.isEmpty() && ts.get(0).topActivity != null) {
                        return ts.get(0).topActivity.getPackageName();
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /**
     * 取某个应用图标的全部候选色；取不到返回非 null 的全 0 数组。
     *
     * <p>结果按包名缓存<b>整份候选数组</b>，所以：同一个 App 只解码一次；用户切换取色方案时
     * 直接从缓存里换一格读，<b>零额外开销</b>。</p>
     */
    private static int[] iconColors(String pkg) {
        if (pkg == null || pkg.isEmpty()) return EMPTY_ICON_COLORS;
        synchronized (sIconColorCache) {
            int[] hit = sIconColorCache.get(pkg);
            if (hit != null) return hit;
        }
        int[] out = null;
        try {
            android.content.Context ctx = currentAppContext();
            if (ctx != null) {
                android.graphics.drawable.Drawable icon =
                        ctx.getPackageManager().getApplicationIcon(pkg);
                if (icon != null) {
                    int[] c = iconColorCandidates(icon);
                    if ((c[0] | c[1] | c[2] | c[3] | c[4]) != 0) out = c;
                }
            }
        } catch (Throwable ignore) {
        }
        // Context/包管理器暂未就绪时允许下一次重试，不能把暂时失败缓存到进程退出。
        if (out == null) return EMPTY_ICON_COLORS;
        synchronized (sIconColorCache) {
            // 简单封顶，避免长跑后无限增长（正常手机也就百来个 App）
            if (sIconColorCache.size() > 256) sIconColorCache.clear();
            sIconColorCache.put(pkg, out);
        }
        return out;
    }

    /** 按方案取图标色；取不到返回 0（由上层回退壁纸主色） */
    private static int iconColorByMode(String pkg, int mode) {
        int[] c = iconColors(pkg);
        if (c == null) return 0;
        switch (mode) {
            case 2: return c[1];
            case 3: return c[2];
            case 4: return c[3];
            case 5: return c[4];
            default: return c[0];
        }
    }

    /**
     * 从图标里<b>一次</b>算出全部候选色，返回 {@code int[5]}：
     * <pre>
     *   [0] 面积主色 —— 出现最多的那一格（最像 App 品牌色）
     *   [1] 最饱和色 —— 平均饱和度最高的格（灰底图标也能出彩）
     *   [2] 平均色   —— 全部有效像素的均值（最柔和）
     *   [3] 最亮色   —— 平均明度最高的格（暗色图标也看得清）
     *   [4] 提亮版   —— [0] 抬明度、略降饱和
     * </pre>
     * 全 0 表示这张图标取不到可用颜色。
     *
     * <p>刻意不走 androidx Palette（依赖 + 多次色彩空间转换）。缩到 24x24 后量化到
     * R/G/B 各 4 bit 统计直方图；<b>一次遍历同时累计</b>面积、饱和度和、明度和与均值，
     * 所以多个取色方案共享同一份像素数据 —— 方案切换不需要重新解码。</p>
     *
     * <p>统计时丢掉「接近全透明」「过暗」「过亮」「几乎无彩度」的像素：图标通常带大片
     * 透明外沿和白色底，不过滤的话主色会永远落在黑或白上。</p>
     */
    private static int[] iconColorCandidates(android.graphics.drawable.Drawable d) {
        try {
            int n = 24;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    n, n, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, n, n);
            d.draw(cv);
            try {
                int[] c = colorCandidatesOfBitmap(bmp);
                // 黑白灰图标也有有效颜色，不应被误判为失败并改用壁纸色。
                if (c[0] == 0) c = colorCandidatesOfBitmap(bmp, false);
                return c;
            } finally {
                bmp.recycle();
            }
        } catch (Throwable t) {
            return new int[5];
        }
    }

    /**
     * 统计一张位图的候选色，语义与 {@link #iconColorCandidates} 完全一致。
     * 调用方负责 recycle 位图。
     *
     * <p>抽出来是为了让「跟随屏幕」复用同一套统计：屏幕截图和图标一样，都只是
     * 「一张缩略图 -> 一组候选色」，没必要把直方图逻辑写第二遍。</p>
     */
    private static int[] colorCandidatesOfBitmap(android.graphics.Bitmap bmp) {
        return colorCandidatesOfBitmap(bmp, true);
    }

    // 图标优先取品牌色；屏幕必须保留黑白灰，才能反映画面的实际面积。
    private static int[] colorCandidatesOfBitmap(android.graphics.Bitmap bmp, boolean colorfulOnly) {
        int[] out = new int[5];
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (w <= 0 || h <= 0) return out;
            // key（4bit×3）→ {count, satSum, lumSum}
            java.util.HashMap<Integer, int[]> hist = new java.util.HashMap<Integer, int[]>();
            long sr = 0, sg = 0, sb = 0;
            int valid = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = bmp.getPixel(x, y);
                    int a = (p >>> 24) & 0xFF;
                    if (a < 128) continue;
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    int mx = Math.max(r, Math.max(g, b));
                    int mn = Math.min(r, Math.min(g, b));
                    if (colorfulOnly && (mx < 40 || mn > 232 || mx - mn < 24)) continue;
                    int sat = mx == 0 ? 0 : (mx - mn) * 255 / mx;
                    int lum = (r * 299 + g * 587 + b * 114) / 1000;
                    int key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
                    int[] cell = hist.get(Integer.valueOf(key));
                    if (cell == null) {
                        cell = new int[3];
                        hist.put(Integer.valueOf(key), cell);
                    }
                    cell[0]++;
                    cell[1] += sat;
                    cell[2] += lum;
                    sr += r;
                    sg += g;
                    sb += b;
                    valid++;
                }
            }
            if (hist.isEmpty()) return out;
            int areaKey = -1, areaCnt = 0;
            for (java.util.Map.Entry<Integer, int[]> e : hist.entrySet()) {
                int c = e.getValue()[0];
                if (c > areaCnt) {
                    areaCnt = c;
                    areaKey = e.getKey().intValue();
                }
            }
            // 饱和度 / 明度候选必须占到最大面积的 1/6，避免被个别噪点带偏
            int minCnt = Math.max(1, areaCnt / 6);
            int satKey = -1, lumKey = -1, satBest = -1, lumBest = -1;
            for (java.util.Map.Entry<Integer, int[]> e : hist.entrySet()) {
                int[] cell = e.getValue();
                if (cell[0] < minCnt) continue;
                int avgSat = cell[1] / cell[0];
                int avgLum = cell[2] / cell[0];
                if (avgSat > satBest) {
                    satBest = avgSat;
                    satKey = e.getKey().intValue();
                }
                if (avgLum > lumBest) {
                    lumBest = avgLum;
                    lumKey = e.getKey().intValue();
                }
            }
            out[0] = cellToArgb(areaKey);
            out[1] = cellToArgb(satKey >= 0 ? satKey : areaKey);
            out[3] = cellToArgb(lumKey >= 0 ? lumKey : areaKey);
            if (valid > 0) {
                out[2] = 0xFF000000 | ((int) (sr / valid) << 16)
                        | ((int) (sg / valid) << 8) | (int) (sb / valid);
            }
            out[4] = brighten(out[0]);
        } catch (Throwable t) {
            // 取不到就保持全 0，由上层逐级回退
        }
        return out;
    }

    /** 4bit×3 的格键 → ARGB（还原 8 bit 时补 0x08，避免整体偏暗） */
    private static int cellToArgb(int key) {
        if (key < 0) return 0;
        int r = (((key >> 8) & 0xF) << 4) | 0x08;
        int g = (((key >> 4) & 0xF) << 4) | 0x08;
        int b = ((key & 0xF) << 4) | 0x08;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 提亮：明度 ×1.35 + 0.08，饱和度 ×0.92（防止颜色发荧光） */
    private static int brighten(int c) {
        if (c == 0) return 0;
        try {
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(c, hsv);
            hsv[2] = Math.min(1f, hsv[2] * 1.35f + 0.08f);
            hsv[1] = hsv[1] * 0.92f;
            return android.graphics.Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return c;
        }
    }

    /** 图标候选色缓存（包名 → int[5]，全 0 表示取不到，也缓存以避免反复重试） */
    private static final int[] EMPTY_ICON_COLORS = new int[5];
    private static final java.util.HashMap<String, int[]> sIconColorCache =
            new java.util.HashMap<String, int[]>();

    /** 莫奈取色缓存：壁纸主色 + 取色时刻（10 秒 TTL） */
    private static volatile boolean sMonetLogged;
    private static volatile int sMonetColor;
    private static volatile long sMonetAt;
    private static volatile java.lang.ref.WeakReference<android.content.Context> sAppContext;

    /** 侧边栏模糊程度：侧车优先（30-99），缺失或越界时退回默认 60 */
    static synchronized int sidebarBlur() {
        refreshCfgLight();
        String v = SIDECAR.get(CFG_SIDEBAR_BLUR);
        if (v == null || v.isEmpty()) {
            return SIDEBAR_BLUR_DEF;
        }
        try {
            int n = Integer.parseInt(v.trim());
            if (n < SIDEBAR_BLUR_MIN) {
                return SIDEBAR_BLUR_MIN;
            }
            if (n > SIDEBAR_BLUR_MAX) {
                return SIDEBAR_BLUR_MAX;
            }
            return n;
        } catch (Throwable t) {
            return SIDEBAR_BLUR_DEF;
        }
    }

    /** 反射缓存：背景模糊半径（OS4 setBackgroundBlurRadius / 老 MIUI setMiBackgroundBlurRadius）与窗口模糊开关（setPassWindowBlurEnabled） */
    private static volatile Method sMiBlurRadius, sMiBlurMode;
    /** 宿主自己的背景管线（com.miui.common.utils.n，内部类 BlurUtils）：g=混合色 i=模糊半径 h=模糊模式 */
    private static volatile Method sNBlend, sNBlurRadius, sNBlurMode;

    private static Method miViewMethod(String name, Class<?>... ps) {
        try {
            Method m = android.view.View.class.getDeclaredMethod(name, ps);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 给「侧边栏展开面板」的实体背景视图叠加自定义背景色与背景模糊。
     *
     * <p>只做两件事，刻意不做任何会改变原视图几何或轮廓的操作（早期实现加过
     * {@code setClipToOutline} + {@code ViewOutlineProvider} 圆角裁切与 {@code RenderEffect}：
     * 前者会在细长的侧边栏视图上描出轮廓而显得「加粗」，后者会把面板内的图标与文字一起糊掉；
     * 对 ImageView 直接用 colorFilter 叠色还会把侧边栏收起态的那条竖条一起染色）：</p>
     * <ol>
     *   <li>背景色：{@code setBackgroundColor(带 alpha 的自定义色)}，覆盖宿主原本
     *       {@code setBackgroundResource} 设下的底；</li>
     *   <li>背景模糊：优先 {@code View.setBackgroundBlurRadius(int)}（HyperOS 上真实存在，
     *       老 MIUI 名为 {@code setMiBackgroundBlurRadius}，两者都试），再补一次
     *       {@code setPassWindowBlurEnabled(true)} 让面板背后的窗口内容参与模糊合成。</li>
     * </ol>
     * 任一反射失败都静默降级为纯色背景，绝不把异常抛给宿主。
     */
    static void applySidebarBg(android.view.View v) {
        applySidebarBg(v, -1);
    }

    /**
     * @param forcedCornerPx 调用方已经量好的圆角（px）。&gt;0 时优先使用它 —— 视频工具箱的
     *                       圆角长在内容子视图 {@code mc} 上，而我们要清掉 {@code mc} 的背景，
     *                       清完就再也读不到了，所以必须由调用方**在清背景之前**量好传进来。
     *                       传 -1 表示由本方法自行推断。
     */
    static void applySidebarBg(android.view.View v, int forcedCornerPx) {
        if (v == null) return;
        final int color = sidebarColor();
        final int blur = sidebarBlur();
        boolean colorOk = false;
        boolean blendOk = false;
        boolean blurOk = false;
        // ① 整体替换面板背景 Drawable —— 对齐 HyperCeiler 默认分支
        //     （BlurSecurity.setBlurBg → BlurUtils.createBlurDrawable）。
        //
        // 宿主 TurboLayout.S() 的做法是：先 setBackgroundResource(底图)，再用
        // com.miui.common.utils.n（内部类 BlurUtils）叠加「背景混合色 + 模糊模式 +
        // 模糊半径」。所以单纯 setBackgroundColor 会被这层管线整个盖住，表现为
        // 「日志有、画面无」；而只调 n.g(混合色) 又只是在原底图上浮一层颜色，
        // 换不掉面板本体 —— 用户看到的正是后者（「改的是透明叠加层」）。
        //
        // 正解是直接换掉整个 background：ViewRootImpl.createBackgroundBlurDrawable()
        // → setBlurRadius / setCornerRadius / setColor，一步拿到「自带真模糊的自定义
        // 颜色背景」，再 setBackground 覆盖宿主设下的所有背景。
        try {
            android.graphics.drawable.Drawable bg = sidebarBlurDrawable(v, blur, color, forcedCornerPx);
            if (bg != null) {
                v.setBackground(bg);
                colorOk = true;
                blurOk = true;
            }
        } catch (Throwable ignored) {
        }
        // ①′ 兜底：API < 31 或 ROM 阉割了创建接口时退回「纯色 + 模糊半径」，
        //     至少保证底色被换掉（模糊可能失效，颜色仍正确）。
        if (!colorOk) {
            try {
                int radius = forcedCornerPx > 0 ? forcedCornerPx : originalCornerRadius(v);
                if (radius <= 0) radius = Math.round(12f * v.getResources().getDisplayMetrics().density);
                android.graphics.drawable.GradientDrawable fallback = new android.graphics.drawable.GradientDrawable();
                fallback.setColor(color);
                fallback.setCornerRadius(radius);
                v.setBackground(fallback);
                colorOk = true;
            } catch (Throwable ignored) {
            }
            try {
                if (sMiBlurRadius == null) {
                    sMiBlurRadius = miViewMethod("setBackgroundBlurRadius", Integer.TYPE);
                }
                if (sMiBlurRadius == null) {
                    sMiBlurRadius = miViewMethod("setMiBackgroundBlurRadius", Integer.TYPE);
                }
                if (sMiBlurRadius != null) {
                    sMiBlurRadius.invoke(v, Integer.valueOf(blur));
                    blurOk = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!sSidebarBgLogged) {
            sSidebarBgLogged = true;
            lg(4, TAG, "sidebar_bg 已应用 color=" + String.format("#%08X", Integer.valueOf(color))
                    + " blur=" + blur + " colorOk=" + colorOk + " blurOk=" + blurOk
                    + " view=" + v.getClass().getName());
        } else {
            // 诊断用：带上视图身份与尺寸，可判断「改的是不是面板本体」「尺寸是否为 0」
            lg(4, TAG, "sidebar_bg 刷新 color=" + String.format("#%08X", Integer.valueOf(color))
                    + " blur=" + blur
                    + " view=" + v.getClass().getSimpleName()
                    + " id=0x" + Integer.toHexString(v.getId())
                    + " size=" + v.getWidth() + "x" + v.getHeight()
                    + " colorOk=" + colorOk + " blendOk=" + blendOk + " blurOk=" + blurOk);
        }
    }

    /**
     * 创建「自带真模糊的自定义颜色背景」Drawable —— 与 HyperCeiler 的
     * {@code BlurUtils.createBlurDrawable} 完全同路。
     *
     * <p>管线：{@code View.getViewRootImpl()} → {@code ViewRootImpl.createBackgroundBlurDrawable()}
     * → {@code setBlurRadius(int)} / {@code setCornerRadius(int)} / {@code setColor(int)}。
     * 这几个都是 Android 12+ 的接口（HyperOS 上真实存在），拿到后直接
     * {@code view.setBackground(drawable)} 就能整体替换宿主背景，颜色与模糊一并生效；
     * 不像 {@code setMiBackgroundBlendColors} 那样只是在原底图上再浮一层颜色
     * （用户反馈的「改的是透明叠加层，不是面板本体」）。</p>
     *
     * <p>任一步失败（老系统 / ROM 阉割 / 拿不到 ViewRootImpl）返回 null，
     * 调用方会退回纯色背景，绝不把异常抛给宿主。</p>
     */
    private static android.graphics.drawable.Drawable sidebarBlurDrawable(
            android.view.View v, int blur, int color, int forcedCornerPx) {
        try {
            if (sGetViewRootImpl == null) {
                Method m = android.view.View.class.getDeclaredMethod("getViewRootImpl");
                m.setAccessible(true);
                sGetViewRootImpl = m;
            }
            Object vri = sGetViewRootImpl.invoke(v);
            if (vri == null) return null;
            if (sCreateBgBlurDrawable == null) {
                sCreateBgBlurDrawable = findMethodInHierarchy(vri.getClass(), "createBackgroundBlurDrawable");
            }
            if (sCreateBgBlurDrawable == null) return null;
            Object d = sCreateBgBlurDrawable.invoke(vri);
            if (!(d instanceof android.graphics.drawable.Drawable)) return null;
            android.graphics.drawable.Drawable drawable = (android.graphics.drawable.Drawable) d;
            Class<?> dc = drawable.getClass();
            if (sDrawableSetBlurRadius == null) {
                sDrawableSetBlurRadius = findMethodInHierarchy(dc, "setBlurRadius", Integer.TYPE);
            }
            if (sDrawableSetCornerRadius == null) {
                sDrawableSetCornerRadius = findMethodInHierarchy(dc, "setCornerRadius", Float.TYPE);
                if (sDrawableSetCornerRadius == null) {
                    sDrawableSetCornerRadius = findMethodInHierarchy(dc, "setCornerRadius", Integer.TYPE);
                }
            }
            if (sDrawableSetColor == null) {
                sDrawableSetColor = findMethodInHierarchy(dc, "setColor", Integer.TYPE);
            }
            if (blur > 0 && sDrawableSetBlurRadius != null) {
                sDrawableSetBlurRadius.invoke(drawable, Integer.valueOf(blur));
            }
            if (sDrawableSetCornerRadius != null) {
                // 圆角必须沿用宿主原本的，否则 setBackground 换掉原 Drawable 之后
                // 圆角轮廓一起丢失，方角会直接露出来（视频工具箱尤其明显）。
                int r = forcedCornerPx > 0 ? forcedCornerPx : originalCornerRadius(v);
                if (r > 0) {
                    if (sDrawableSetCornerRadius.getParameterTypes()[0] == Float.TYPE) {
                        sDrawableSetCornerRadius.invoke(drawable, Float.valueOf(r));
                    } else {
                        sDrawableSetCornerRadius.invoke(drawable, Integer.valueOf(r));
                    }
                } else {
                    // 推断不出时按 12dp 兜底（原实现硬编码 40px，在 2K 屏上只有 ~14dp，
                    // 与宿主实际圆角对不上）
                    int r2 = (int) (12f * v.getResources().getDisplayMetrics().density + 0.5f);
                    if (sDrawableSetCornerRadius.getParameterTypes()[0] == Float.TYPE) {
                        sDrawableSetCornerRadius.invoke(drawable, Float.valueOf(r2));
                    } else {
                        sDrawableSetCornerRadius.invoke(drawable, Integer.valueOf(r2));
                    }
                }
            }
            if (sDrawableSetColor != null) {
                sDrawableSetColor.invoke(drawable, Integer.valueOf(color));
            }
            return drawable;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 推断视图当前应有的圆角半径（px）；推断不出返回 -1。
     *
     * <p>宿主原本的背景 Drawable 自带圆角（或视图自设了 outlineProvider）。我们一旦
     * {@code setBackground} 把它整个换掉，圆角轮廓就跟着丢了 —— 表现就是「圆角外的
     * 方块角也被染色」。所以换之前先把原圆角读出来。</p>
     */
    /**
     * 推断视图当前应有的圆角半径（px）；推断不出返回 -1。
     *
     * <p>宿主原本的背景 Drawable 自带圆角（或视图自设了 outlineProvider）。我们一旦
     * {@code setBackground} 把它整个换掉，圆角轮廓就跟着丢了 —— 表现就是「圆角外的
     * 方块角也被染色」。</p>
     *
     * <p><b>为什么要沿 parent 链找</b>：视频工具箱就是典型 —— 视觉圆角在它的内容子视图
     * （{@code mc}）身上，而承载我们背景的却是外层 {@code TurboLayout}（矩形）。只读自己的
     * 轮廓会得到 0，于是四角露方。这里最多上溯 3 层，且要求父层「宽度不小于自身」，
     * 避免误取到全屏容器那种与面板无关的大圆角。</p>
     */
    private static int originalCornerRadius(android.view.View v) {
        if (v == null) return -1;
        android.view.View cur = v;
        for (int depth = 0; depth < 3 && cur != null; depth++) {
            try {
                android.view.ViewOutlineProvider p = cur.getOutlineProvider();
                if (p != null) {
                    android.graphics.Outline o = new android.graphics.Outline();
                    p.getOutline(cur, o);
                    int r = outlineRadius(o);
                    if (r > 0) return r;
                }
            } catch (Throwable ignore) {
            }
            try {
                android.graphics.drawable.Drawable bg = cur.getBackground();
                if (bg != null) {
                    android.graphics.Outline o = new android.graphics.Outline();
                    bg.getOutline(o);
                    int r = outlineRadius(o);
                    if (r > 0) return r;
                }
            } catch (Throwable ignore) {
            }
            try {
                android.view.ViewParent pp = cur.getParent();
                if (!(pp instanceof android.view.View)) break;
                android.view.View pv = (android.view.View) pp;
                // 只接受不比当前视图窄的父层，避免把全屏容器的圆角误当成面板圆角
                if (pv.getWidth() > 0 && cur.getWidth() > 0 && pv.getWidth() < cur.getWidth()) {
                    break;
                }
                cur = pv;
            } catch (Throwable ignore) {
                break;
            }
        }
        return -1;
    }

    /** 读 {@link android.graphics.Outline} 的圆角半径（getRadius 是 hidden，需反射） */
    private static int outlineRadius(android.graphics.Outline o) {
        try {
            if (sOutlineGetRadius == null) {
                Method m = android.graphics.Outline.class.getDeclaredMethod("getRadius");
                m.setAccessible(true);
                sOutlineGetRadius = m;
            }
            Object r = sOutlineGetRadius.invoke(o);
            if (r instanceof Float) return (int) ((Float) r).floatValue();
            if (r instanceof Integer) return ((Integer) r).intValue();
        } catch (Throwable ignore) {
        }
        return -1;
    }

    /** 沿类继承链查找方法（含 private / hidden，全部 setAccessible）。找不到返回 null */
    private static Method findMethodInHierarchy(Class<?> c, String name, Class<?>... ps) {
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                Method m = k.getDeclaredMethod(name, ps);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignore) {
            }
            k = k.getSuperclass();
        }
        return null;
    }

    /** 背景 Drawable 的圆角（px）。HyperCeiler 在 BlurSecurity 里同样硬编码 40 */
    private static final int SIDEBAR_BG_CORNER_PX = 40;

    private static volatile Method sGetViewRootImpl, sCreateBgBlurDrawable;
    private static volatile Method sDrawableSetBlurRadius, sDrawableSetCornerRadius, sDrawableSetColor;
    private static volatile Method sOutlineGetRadius;

    /** 「首次应用」日志只打一次，避免侧边栏频繁弹出时刷屏 */
    private static volatile boolean sSidebarBgLogged;

    /** TurboLayout 内保存「实际承载面板背景」的视图字段名（类型 newbox.p0 extends FrameLayout） */
    private static final String SIDEBAR_PANEL_FIELD = "m";

    /** 侧边栏面板容器的弱引用：宿主只在创建/刷新时设置一次背景，改色后靠显示路径兜底重刷 */
    private static volatile java.lang.ref.WeakReference<Object> sSidebarTurbo;

    /**
     * OS4（HyperOS 4）侧边栏背景接管：锚点 {@code com.miui.gamebooster.windowmanager.newbox.TurboLayout}。
     *
     * <p>真实结构（由 APK 反查得到）：侧边栏收起态显示的是
     * {@code com.miui.dock.sidebar.RegionSamplingImageView}（资源 id {@code sidebar_background}，
     * 尺寸 {@code sidebar_width_vertical × sidebar_height_vertical}），它只是一条细长的竖条，
     * 由 CompositionSamplingListener 采样屏幕内容做模糊底图；用户真正想改色的「展开面板」
     * 是同一个布局 {@code res/Noy.xml} 里的 {@code newbox.TurboLayout}（资源 id
     * {@code sidebar_panel}，RelativeLayout）。</p>
     *
     * <p>面板背景并不是设在 TurboLayout 自己身上，而是设在它的字段 {@code m}
     * （{@code newbox.p0}，FrameLayout，内部再放 FadingEdgeRecyclerView 列表）上：
     * {@code TurboLayout.S()} 给该视图 {@code setBackgroundResource(0x7f060f71)} 色资源或
     * {@code 0x7f08107c} drawable，并调 {@code com.miui.common.utils.n.k(view,true)}
     * （即反射 {@code View.setPassWindowBlurEnabled}）打开背景毛玻璃。</p>
     *
     * <p>因此这里 hook 三个点，保证「弹出即上色、改色下次弹出即生效」：
     * {@code S()}（宿主设置背景的出口）、{@code U()}（S 的唯一调用者，每次刷新都会走）
     * 以及构造器兜底。命中后在宿主逻辑之后叠加自定义颜色与模糊，不改动原有代码。</p>
     */
    private static int installSidebarBgPanel(final Rule r) {
        final Class<?> c;
        try {
            c = Class.forName("com.miui.gamebooster.windowmanager.newbox.TurboLayout", false, sCl);
        } catch (Throwable t) {
            lg(5, TAG, "sidebar_bg OS4 锚点不可用: " + t);
            return 0;
        }
        final SpAction act = new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                Object out = chain.proceed();
                try {
                    applySidebarPanelBg(chain.getThisObject());
                } catch (Throwable ignore) {
                }
                return out;
            }
        };
        int n = 0;
        // ① S()：宿主设置面板背景的唯一出口
        try {
            if (hookMethod(r, c.getDeclaredMethod("S"), act)) n++;
        } catch (Throwable t) {
            lg(5, TAG, "sidebar_bg 未找到 TurboLayout.S(): " + t);
        }
        // ② U()：S() 的唯一调用者，侧边栏每次刷新背景都会经过
        try {
            if (hookMethod(r, c.getDeclaredMethod("U"), act)) n++;
        } catch (Throwable t) {
            lg(5, TAG, "sidebar_bg 未找到 TurboLayout.U(): " + t);
        }
        // ③ 构造器兜底：面板首次创建时补一次上色
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (hookConstructor(r, k, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    Object out = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        sSidebarTurbo = new java.lang.ref.WeakReference<Object>(self);
                        applySidebarPanelBg(self);
                    } catch (Throwable ignore) {
                    }
                    return out;
                }
            })) {
                n++;
            }
        }
        // ⑤ 面板本体（newbox.p0）：宿主把背景设在它身上，只给外层 TurboLayout 上色会被
        //    它整个盖住。直接接管它的构造并在挂载后补色，确保拿到的是真正承载面板底图
        //    的那个 FrameLayout（内部还有一个 FadingEdgeRecyclerView 列表）。
        try {
            final Class<?> pc = Class.forName("com.miui.gamebooster.windowmanager.newbox.p0", false, sCl);
            for (Constructor<?> k : pc.getDeclaredConstructors()) {
                if (hookConstructor(r, k, new SpAction() {
                    @Override
                    public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                        Object out = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            if (self instanceof android.view.View) {
                                final android.view.View v = (android.view.View) self;
                                v.addOnAttachStateChangeListener(
                                        new android.view.View.OnAttachStateChangeListener() {
                                            @Override
                                            public void onViewAttachedToWindow(android.view.View view) {
                                                applySidebarBg(view);
                                            }

                                            @Override
                                            public void onViewDetachedFromWindow(android.view.View view) {
                                            }
                                        });
                            }
                        } catch (Throwable ignore) {
                        }
                        return out;
                    }
                })) {
                    n++;
                }
            }
        } catch (Throwable t) {
            lg(5, TAG, "sidebar_bg 未找到 newbox.p0 构造器: " + t);
        }
        // ④ 显示路径兜底：宿主只在创建/刷新时设置一次背景，用户改色后
        //    必须等下一次「呼出侧边栏」重新走一遍显示流程，才能做到即时生效
        n += installSidebarBgShowHook(r);
        if (n > 0) {
            r.where = "侧边栏面板背景 #" + String.format("%08X", Integer.valueOf(sidebarColor()))
                    + "，模糊 " + sidebarBlur() + "（TurboLayout）";
            lg(4, TAG, "sidebar_bg 已接管 " + c.getName() + " x" + n);
        }
        return n;
    }

    /**
     * 「每一次呼出侧边栏」都会经过的显示路径锚点：{@code vb.l0.f0()}。
     *
     * <p>它同时是侧边栏收起态采样视图 {@code RegionSamplingImageView.g(Z)} 的唯一调用者，
     * 因此是「侧边栏显示」的可靠信号。宿主只在创建/刷新（{@code TurboLayout.S()/U()}）时
     * 设置一次背景，改色后必须在这里再刷一次，用户才不需要重启手机管家。</p>
     */
    private static int installSidebarBgShowHook(final Rule r) {
        final Class<?> c;
        try {
            c = Class.forName("vb.l0", false, sCl);
        } catch (Throwable t) {
            lg(5, TAG, "sidebar_bg 显示路径锚点不可用: " + t);
            return 0;
        }
        try {
            Method f0 = c.getDeclaredMethod("f0");
            if (hookMethod(r, f0, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    Object out = chain.proceed();
                    try {
                        applySidebarBgOnShow(chain.getThisObject());
                    } catch (Throwable ignore) {
                    }
                    return out;
                }
            })) {
                return 1;
            }
        } catch (Throwable t) {
            lg(5, TAG, "sidebar_bg 未找到 vb.l0.f0(): " + t);
        }
        return 0;
    }

    /** 显示路径上重新取到侧边栏面板并上色：优先宿主对象自持的字段，退回最近构造的实例 */
    private static void applySidebarBgOnShow(Object holder) {
        // 只认 holder 自己持有的 TurboLayout。这里刻意不做任何"最近实例"弱引用兜底：
        // 锁屏解锁会让侧边栏视图重建，旧引用可能已被复用，染错对象就是灾难级表现
        // （整片透明层被染色）。宁可这次不染，也不能染错。
        Object turbo = findFieldOfType(holder, "com.miui.gamebooster.windowmanager.newbox.TurboLayout");
        if (turbo != null) {
            applySidebarPanelBg(turbo);
        }
    }

    /** 按字段类型在对象（含父类链）的非静态字段中查找实例，找不到返回 null */
    private static Object findFieldOfType(Object target, String typeName) {
        if (target == null) {
            return null;
        }
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (!f.getType().getName().equals(typeName)) continue;
                    f.setAccessible(true);
                    Object o = f.get(target);
                    if (o != null) return o;
                } catch (Throwable ignore) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 从 TurboLayout 实例取出承载面板背景的视图（字段 {@code m}）并套用自定义背景。
     *
     * <p>字段读取失败或面板尚未创建时静默跳过，不影响宿主逻辑。</p>
     */
    private static void applySidebarPanelBg(Object turbo) {
        if (turbo == null) return;
        android.view.View panel = null;
        try {
            // 必须沿继承链找：锁屏解锁会让侧边栏视图重建，拿到的可能是子类实例，
            // 而字段 m 声明在父类上 —— 只查 getDeclaredField 会漏掉，进而走进兜底。
            java.lang.reflect.Field f = findFieldInHierarchy(turbo.getClass(), SIDEBAR_PANEL_FIELD);
            if (f != null) {
                f.setAccessible(true);
                Object o = f.get(turbo);
                if (o instanceof android.view.View) {
                    panel = (android.view.View) o;
                }
            }
        } catch (Throwable ignore) {
        }
        if (panel != null) {
            applySidebarBg(panel);
            return;
        }
        // 绝不退回容器。TurboLayout 本身是透明容器，染它会把侧边栏底下的整片透明层
        // 一起改掉 —— 用户反馈的「整个侧边栏底下的透明层被染色」就是这么来的
        // （解锁后视图重建、字段取不到时命中旧兜底）。取不到就什么都不做。
        lg(5, TAG, "sidebar_bg 字段 " + SIDEBAR_PANEL_FIELD + " 在 "
                + turbo.getClass().getName() + " 上取不到，跳过（不染透明容器）");
    }

    /** 沿类继承链查找字段（含 private / hidden），找不到返回 null */
    private static java.lang.reflect.Field findFieldInHierarchy(Class<?> c, String name) {
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignore) {
            }
            k = k.getSuperclass();
        }
        return null;
    }

    /**
     * 全局侧边栏背景自定义入口。
     *
     * <p>「面板本体」与「dock 应用栏」是两个不同的视图，必须都接管：</p>
     * <ul>
     *   <li>{@code newbox.p0}（TurboLayout 字段 m）= 侧边栏主面板底图；</li>
     *   <li>{@code TurboLayout.getDockLayout()} 的返回类型 = 侧边栏「最下方按钮展开的
     *       那个应用菜单」，HyperCeiler 的 {@code BlurSecurity} 只处理它。</li>
     * </ul>
     * <p>早期实现里前者一命中就 {@code return}，dock 菜单因此始终没跟色。</p>
     */
    private static int spSidebarBg(Rule r) {
        int n = installSidebarBgPanel(r);
        n += installSidebarBgDock(r);
        n += installSidebarBgAllApps(r);
        n += installSidebarBgTurboBox(r);
        n += installSidebarBgGameBox(r);
        return n;
    }

    /**
     * 接管视频工具箱主体（{@code TurboLayout.getTargetBox()} 的返回值）。
     *
     * <p>视频 / 会议 / 通话工具箱共用 {@code TurboLayout}，主体视图由
     * {@code getTargetBox()} 给出；游戏工具箱虽然也用 TurboLayout，但它的主体由
     * {@link #installSidebarBgGameBox} 单独处理，两者必须区分开。</p>
     *
     * <p><b>区分依据</b>：视频类工具箱主体的第一个子 View 带有资源 id（{@code main_content}），
     * 游戏工具箱主体无 id。这个判断不能省 —— 对游戏工具箱主体染色会让它本该透明的
     * 扩展区域（例如「亮度」）带上背景，出现视觉穿帮。</p>
     */
    private static int installSidebarBgTurboBox(Rule r) {
        int n = 0;
        try {
            Class<?> turbo = Class.forName(
                    "com.miui.gamebooster.windowmanager.newbox.TurboLayout", false, sCl);
            Method gt = null;
            for (Method m : turbo.getDeclaredMethods()) {
                if ("getTargetBox".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    gt = m;
                    break;
                }
            }
            if (gt == null) {
                lg(5, TAG, "sidebar_bg 未找到 TurboLayout.getTargetBox()");
                return 0;
            }
            gt.setAccessible(true);
            if (hookMethod(r, gt, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    Object out = chain.proceed();
                    try {
                        if (out instanceof android.view.View) {
                            final android.view.View box = (android.view.View) out;
                            box.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                                @Override
                                public void onViewAttachedToWindow(android.view.View view) {
                                    try {
                                        if (!(view instanceof android.view.ViewGroup)) return;
                                        android.view.ViewGroup g = (android.view.ViewGroup) view;
                                        if (g.getChildCount() <= 0) return;
                                        android.view.View mc = g.getChildAt(0);
                                        // id 非 NO_ID ⇒ 视频 / 会议 / 通话工具箱
                                        if (mc == null || mc.getId() == android.view.View.NO_ID) return;
                                        // 视觉圆角长在内容子视图 mc 上，而 mc 的背景马上要被清掉 ——
                                        // 必须在这之前把圆角量好，否则清完只剩外层矩形的四个方角
                                        // （用户反馈的「圆角外的四个角又被染色」）。
                                        int corner = originalCornerRadius(mc);
                                        if (corner <= 0) {
                                            corner = originalCornerRadius(view);
                                        }
                                        // 背景留在内容层，外层的透明边距不参与染色。
                                        int radiusId = mc.getResources().getIdentifier(
                                                "videobox_main_bg_corner_radius", "dimen", "com.miui.securitycenter");
                                        if (radiusId != 0) corner = mc.getResources().getDimensionPixelSize(radiusId);
                                        applySidebarBg(mc, corner);
                                    } catch (Throwable ignore) {
                                    }
                                }

                                @Override
                                public void onViewDetachedFromWindow(android.view.View view) {
                                }
                            });
                        }
                    } catch (Throwable ignore) {
                    }
                    return out;
                }
            })) {
                n++;
                lg(4, TAG, "sidebar_bg 已接管视频工具箱 TurboLayout.getTargetBox()");
            }
        } catch (Throwable t) {
            lg(5, TAG, "sidebar_bg 定位视频工具箱锚点失败: " + t);
        }
        return n;
    }

    /**
     * 游戏工具箱内容层。
     *
     * <p>13.6.3 的 GameTurboMainView（z1）持有游戏工具和游戏中心两页，
     * o() 为它设置背景与圆角。仅接管此主体，挂载及切换页面后重新应用。</p>
     */
    private static int installSidebarBgGameBox(Rule r) {
        int n = 0;
        try {
            Class<?> host = Class.forName("com.miui.gamebooster.windowmanager.newbox.z1", false, sCl);
            // p() 在切换游戏中心/游戏工具时重新设置标题底图。
            Method update = findMethodInHierarchy(host, "p");
            if (update != null && update.getReturnType() == Void.TYPE) {
                if (hookMethod(r, update, new SpAction() {
                    @Override
                    public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                        Object out = chain.proceed();
                        if (chain.getThisObject() instanceof android.view.View) {
                            tintGameToolboxTitle((android.view.View) chain.getThisObject());
                        }
                        return out;
                    }
                })) n++;
            }
            for (Constructor<?> k : host.getDeclaredConstructors()) {
                if (hookConstructor(r, k, new SpAction() {
                    @Override
                    public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                        Object out = chain.proceed();
                        if (chain.getThisObject() instanceof android.view.View) {
                            android.view.View view = (android.view.View) chain.getThisObject();
                            view.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                                @Override
                                public void onViewAttachedToWindow(final android.view.View v) {
                                    v.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!v.isAttachedToWindow()) return;
                                            tintGameToolboxTitle(v);
                                        }
                                    });
                                }
                                @Override
                                public void onViewDetachedFromWindow(android.view.View v) { }
                            });
                        }
                        return out;
                    }
                })) n++;
            }
        } catch (Throwable t) {
            lg(5, TAG, "sidebar_bg 游戏标题背景定位失败: " + t);
        }
        return n;
    }

    // z1 是宿主直接设置模糊与圆角的主体；不要沿 parent 向外染到扩展容器。
    private static void tintGameToolboxTitle(android.view.View root) {
        try {
            if (!root.isAttachedToWindow()) return;
            // 清理宿主独立的混色管线，避免覆盖新的 Drawable。
            Method blend = miViewMethod("setMiBackgroundBlendColors", java.util.ArrayList.class);
            if (blend != null) {
                try { blend.invoke(root, new java.util.ArrayList<android.graphics.Point>()); }
                catch (Throwable ignored) { }
            }
            Method mode = miViewMethod("setMiBackgroundBlurMode", Integer.TYPE);
            if (mode != null) {
                try { mode.invoke(root, Integer.valueOf(0)); }
                catch (Throwable ignored) { }
            }
            int radiusId = root.getResources().getIdentifier(
                    "game_toolbox_background_radius", "dimen", PKG);
            int radius = radiusId == 0 ? Math.round(24f * root.getResources().getDisplayMetrics().density)
                    : root.getResources().getDimensionPixelSize(radiusId);
            applySidebarBg(root, radius);
            int id = root.getResources().getIdentifier("iv_game_toolbox_title_dual_bg",
                    "id", "com.miui.securitycenter");
            if (id == 0) return;
            android.view.View title = root.findViewById(id);
            if (title == null || title.getBackground() == null) return;
            android.graphics.drawable.Drawable bg = title.getBackground().mutate();
            bg.setTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
            bg.setTint(sidebarColor());
            title.setBackground(bg);
        } catch (Throwable t) {
            lg(5, TAG, "sidebar_bg 游戏标题染色失败: " + t);
        }
    }

    /**
     * 接管侧边栏「全部应用」抽屉页（{@code com.miui.dock.allapps.w}）的背景。
     *
     * <p>它是右侧展开的那个应用网格菜单（顶部「搜索应用」输入框、分区标题「全部小窗应用」、
     * 下面是应用图标网格），与主面板 {@code newbox.p0}、dock 应用栏都不是同一个视图，
     * 所以需要单独接管。</p>
     *
     * <p>只染它自己。宿主给这一页 prepend 的背景就在这个 LinearLayout 上，
     * 不涉及透明容器。</p>
     */
    private static int installSidebarBgAllApps(Rule r) {
        int n = 0;
        Class<?> host = null;
        try {
            host = Class.forName("com.miui.dock.allapps.w", false, sCl);
        } catch (Throwable t) {
            // 该版本没有这一页（或类名变了）时静默跳过，不影响面板 / dock 的染色
            return 0;
        }
        if (host == null || !android.view.View.class.isAssignableFrom(host)) {
            return 0;
        }
        for (Constructor<?> k : host.getDeclaredConstructors()) {
            if (hookConstructor(r, k, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    Object out = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof android.view.View) {
                            final android.view.View v = (android.view.View) self;
                            v.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                                @Override
                                public void onViewAttachedToWindow(android.view.View view) {
                                    applySidebarBg(view);
                                }

                                @Override
                                public void onViewDetachedFromWindow(android.view.View view) {
                                }
                            });
                        }
                    } catch (Throwable ignore) {
                    }
                    return out;
                }
            })) n++;
        }
        if (n > 0) {
            lg(4, TAG, "sidebar_bg 已接管全部应用抽屉 " + host.getName() + " 构造器 x" + n);
        }
        return n;
    }

    /**
     * 接管 dock 应用栏（{@code TurboLayout.getDockLayout()} 的返回类型）的构造。
     *
     * <p>只染它自己 —— dock 是个具体视图，不是透明容器，染色安全。</p>
     */
    private static int installSidebarBgDock(Rule r) {
        int n = 0;
        Class<?> dock = null;
        try {
            Class<?> turbo = Class.forName("com.miui.gamebooster.windowmanager.newbox.TurboLayout", false, sCl);
            for (Method m : turbo.getDeclaredMethods()) {
                if ("getDockLayout".equals(m.getName())) {
                    dock = m.getReturnType();
                    break;
                }
            }
        } catch (Throwable t) {
            lg(4, TAG, "sidebar_bg 定位 TurboLayout 失败: " + t);
        }
        if (dock == null || !android.view.View.class.isAssignableFrom(dock)) {
            lg(4, TAG, "sidebar_bg：dock 应用栏视图类不可用，跳过");
            return 0;
        }
        for (Constructor<?> k : dock.getDeclaredConstructors()) {
            if (hookConstructor(r, k, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    Object out = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof android.view.View) {
                            final android.view.View v = (android.view.View) self;
                            v.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                                @Override
                                public void onViewAttachedToWindow(android.view.View view) {
                                    applySidebarBg(view);
                                }

                                @Override
                                public void onViewDetachedFromWindow(android.view.View view) {
                                }
                            });
                        }
                    } catch (Throwable ignore) {
                    }
                    return out;
                }
            })) n++;
        }
        if (n > 0) {
            r.where = "侧边栏背景 #" + String.format("%08X", Integer.valueOf(sidebarColor()))
                    + "，模糊 " + sidebarBlur();
            lg(4, TAG, "sidebar_bg 已接管 " + dock.getName() + " 构造器 x" + n);
        }
        return n;
    }

    // ───────── 推荐应用：隐藏推荐区 ─────────

    /**
     * 侧边栏「全部应用」页推荐区（"Suggestions"）的宿主类。
     *
     * <p>该页就是 {@code AppClassificationActivity}（label = app_predict_search_title「全部应用」），
     * 推荐槽位的点亮逻辑在基类 {@code FolderSearchActivity.d1()} 里，因此按类名一起挂。</p>
     */
    private static final String[] SUGGEST_HOSTS = {
            "com.miui.apppredict.activity.FolderSearchActivity",
            "com.miui.apppredict.activity.AppClassificationActivity",
    };

    /**
     * 隐藏侧边栏推荐区。
     *
     * <p>槽位本体在 res/k9R.xml：4×2 共 8 个 {@code suggest_1..suggest_8}（标题
     * {@code suggest_app}，整块由 ConstraintLayout 的 {@code Group group_suggest} 管理）。
     * 点亮它们的是 {@code FolderSearchActivity.d1()}：取 Intent 里的
     * {@code folder_package_names}，按 {@code min(8, size)} 逐个 setVisibility(VISIBLE)
     * 并绑定图标；列表为空时才整块 GONE。因此数量控制点在这个 Activity，
     * 而不是 dock 侧的全应用视图类。</p>
     *
     * <p>原生逻辑结束后隐藏推荐区，布局回调幂等复查异步加载的槽位。
     * 「禁止展示推荐应用」关闭时不干预，控制器按根视图去重。</p>
     */
    private static int spSidebarSuggest(Rule r) {
        int n = 0;
        for (String cn : SUGGEST_HOSTS) {
            Class<?> host = null;
            try {
                host = Class.forName(cn, false, sCl);
            } catch (Throwable ignored) {
            }
            if (host == null) continue;
            for (Method m : host.getDeclaredMethods()) {
                if (isSuggestEntry(m) && hookMethod(r, m, SUGGEST_ENTRY_ACTION)) n++;
            }
        }
        // 兜底：部分版本把推荐区放在 dock 的全应用视图类里
        Class<?> w = null;
        try {
            w = Class.forName("com.miui.dock.allapps.w", false, sCl);
        } catch (Throwable ignored) {
        }
        if (w != null && android.view.View.class.isAssignableFrom(w)) {
            for (Constructor<?> k : w.getDeclaredConstructors()) {
                if (hookConstructor(r, k, SUGGEST_ENTRY_ACTION)) n++;
            }
        }
        if (n == 0) lg(4, TAG, "sidebar_suggest 未找到宿主（页面版本不匹配）");
        return n;
    }

    /** 推荐区入口方法：onCreate(Bundle) 或无参返回 void 的 d1() */
    private static boolean isSuggestEntry(Method m) {
        if (m == null) return false;
        if ("onCreate".equals(m.getName())) {
            Class<?>[] p = m.getParameterTypes();
            return p.length == 1 && p[0] == android.os.Bundle.class;
        }
        return "d1".equals(m.getName()) && m.getParameterTypes().length == 0
                && m.getReturnType() == void.class;
    }

    /** 推荐区 hook 动作：原生逻辑跑完后挂上槽位控制器（Activity 或 View 均可） */
    private static final SpAction SUGGEST_ENTRY_ACTION = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
            Object out = chain.proceed();
            try {
                attachSuggestSlots(chain.getThisObject());
            } catch (Throwable ignore) {
            }
            return out;
        }
    };

    /**
     * 在宿主（Activity 或 View）上安装推荐区槽位控制器。
     *
     * <p>立即应用一次，再延迟一次，并注册布局完成回调持续复查：宿主可能在数据到位后
     * 重新点亮槽位。视图摘下时注销回调，避免回调残留。</p>
     */
    private static final java.util.WeakHashMap<android.view.View, java.lang.ref.WeakReference<SuggestSlots>>
            SUGGEST_CONTROLLERS = new java.util.WeakHashMap<>();

    private static void attachSuggestSlots(Object self) {
        try {
            android.view.View root = null;
            if (self instanceof android.app.Activity) {
                root = ((android.app.Activity) self).getWindow().getDecorView();
            } else if (self instanceof android.view.View) {
                root = (android.view.View) self;
            }
            if (root == null) return;
            synchronized (SUGGEST_CONTROLLERS) {
                java.lang.ref.WeakReference<SuggestSlots> ref = SUGGEST_CONTROLLERS.get(root);
                if (ref != null && ref.get() != null) return;
            }
            final SuggestSlots slots = new SuggestSlots(root);
            synchronized (SUGGEST_CONTROLLERS) {
                SUGGEST_CONTROLLERS.put(root, new java.lang.ref.WeakReference<>(slots));
            }
            slots.apply();
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    slots.apply();
                }
            }, 300L);
            root.getViewTreeObserver().addOnGlobalLayoutListener(slots);
            root.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(android.view.View v) {
                    v.getViewTreeObserver().addOnGlobalLayoutListener(slots);
                    slots.apply();
                }

                @Override
                public void onViewDetachedFromWindow(android.view.View v) {
                    slots.release();
                }
            });
        } catch (Throwable t) {
            lg(4, TAG, "sidebar_suggest 挂载槽位控制器失败: " + t);
        }
    }

    /**
     * 侧边栏「常用推荐应用」槽位控制器。
     *
     * <p>隐藏标题、Group 与 {@code suggest_1..suggest_8} 槽位，不改动推荐数据源。</p>
     *
     * <p>id/视图只解析一次，此后每次布局完成只做可见性复查（{@link #onGlobalLayout()}），
     * 设置值按 1s 缓存，避免每次布局都去读侧车文件。</p>
     *
     * <p>仅在「禁止展示推荐应用」开启时隐藏整块推荐区。</p>
     */
    private static final class SuggestSlots
            implements android.view.ViewTreeObserver.OnGlobalLayoutListener {

        /** 设置值缓存时长：侧边栏开着时也不至于每次布局都读侧车 */
        private static final long CFG_TTL_MS = 1000L;
        /** 槽位总数（与 res/k9R.xml 的 suggest_1..suggest_8 一致） */
        private static final int SLOT_MAX = 8;

        private final android.view.View root;
        private final android.view.View[] slots = new android.view.View[SLOT_MAX];
        private android.view.View title;
        private android.view.View group;
        private boolean resolved;
        private long cfgAt;
        private boolean hideAll;

        SuggestSlots(android.view.View root) {
            this.root = root;
        }

        /** 按资源名解析标题、整块 Group 与 8 个槽位视图（只做一次） */
        private void resolve() {
            if (resolved) return;
            resolved = true;
            try {
                android.content.Context ctx = root.getContext();
                android.content.res.Resources res = ctx.getResources();
                String pkg = ctx.getPackageName();
                title = find(res, pkg, "suggest_app");
                group = find(res, pkg, "group_suggest");
                for (int i = 0; i < SLOT_MAX; i++) slots[i] = find(res, pkg, "suggest_" + (i + 1));
            } catch (Throwable ignored) {
            }
        }

        private android.view.View find(android.content.res.Resources res, String pkg, String name) {
            try {
                int id = res.getIdentifier(name, "id", pkg);
                return id == 0 ? null : root.findViewById(id);
            } catch (Throwable t) {
                return null;
            }
        }

        /** 读一次隐藏开关（1s 缓存）。 */
        private void refreshCfg() {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - cfgAt < CFG_TTL_MS) return;
            cfgAt = now;
            hideAll = sidebarSuggestHideAll();
        }

        @Override
        public void onGlobalLayout() {
            apply();
        }

        /** 按当前设置复查可见性；返回本次置为 GONE 的视图数 */
        int apply() {
            try {
                resolve();
                refreshCfg();
                if (!hideAll) return 0;
                int hidden = hideGone(title) + hideGone(group);
                for (int i = 0; i < SLOT_MAX; i++) hidden += hideGone(slots[i]);
                return hidden;
            } catch (Throwable t) {
                lg(4, TAG, "sidebar_suggest 应用失败: " + t);
                return 0;
            }
        }

        /** 视图摘下：注销布局回调，避免残留 */
        void release() {
            try {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 置为 GONE；视图为空或本就隐藏时返回 0（便于统计实际动作数） */
    private static int hideGone(android.view.View v) {
        if (v == null || v.getVisibility() == android.view.View.GONE) return 0;
        v.setVisibility(android.view.View.GONE);
        return 1;
    }

    /**
     * 「禁止展示推荐应用」的状态；优先读取框架配置，缺失时读取侧车。
     */
    private static boolean sidebarSuggestHideAll() {
        try {
            SharedPreferences p = sPrefs == null ? null : sPrefs.prefs();
            if (p != null && p.contains(SUGGEST_GATE_KEY))
                return p.getBoolean(SUGGEST_GATE_KEY, false);
        } catch (Throwable ignored) {
        }
        try {
            refreshCfgLight();
        } catch (Throwable ignored) {
        }
        Boolean b = SK.get(SUGGEST_GATE_KEY);
        return b != null && b;
    }

    // ───────────────── 隐藏侧边栏手柄（移植 HyperCeiler AddSideBarExpandReceiver） ─────────────────

    /**
     * 唤起全局侧边栏的广播动作。
     *
     * <p>发一条 {@code adb shell am broadcast -a com.hyper.Camellia.action.ShowSideBar}
     * 即可滑出侧边栏；带 {@code actionInfo} Bundle 且含 {@code inDirection}（0=左 1=右）
     * 时可以指定滑出方向。</p>
     */
    public static final String ACTION_SHOW_SIDEBAR = "com.hyper.Camellia.action.ShowSideBar";
    /** 侧边栏手柄 View（RegionSamplingHelper 构造时捕获） */
    private static volatile android.view.View sSidebarView;
    private static android.content.BroadcastReceiver sSidebarReceiver;
    /** 侧边栏所在侧：0=左 1=右（与手机管家 sp_video_box/dock_line_location 一致） */
    private static volatile int sSidebarDockLocation = -1;
    private static final Object SIDEBAR_LOCK = new Object();

    /**
     * 隐藏侧边栏手柄：复刻 HyperCeiler securitycenter/sidebar/AddSideBarExpandReceiver。
     *
     * <p>手机管家内部自带了一份 SystemUI 的手势区域采样器
     * {@code com.android.systemui.navigationbar.gestural.RegionSamplingHelper}
     * （全局侧边栏那条可滑动的手势线就是它负责采样的 View），因此 hook 它的构造器
     * 就能拿到手柄 View —— 无需 systemui 作用域。</p>
     *
     * <p>三步：①手柄就绪后抹掉绘制（整条 View 透明，触摸仍可用）；
     * ②注册广播接收器，收到 {@link #ACTION_SHOW_SIDEBAR} 时用合成触摸事件把侧边栏滑出来；
     * ③拦掉「返回 void 且只吃一个 Rect」的采样方法，手柄不再参与屏幕采样与模糊。</p>
     */
    private static int spHideSidebar(Rule r) {
        Class<?> c;
        try {
            c = Class.forName("com.android.systemui.navigationbar.gestural.RegionSamplingHelper", false, sCl);
        } catch (Throwable t) {
            lg(5, TAG, "hide_sidebar 定位失败: " + t);
            return 0;
        }
        int n = 0;
        // ① 构造器 (View, ...)：捕获手柄 View
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            Class<?>[] ps = k.getParameterTypes();
            if (ps.length != 2 || !android.view.View.class.isAssignableFrom(ps[0])) continue;
            if (hookConstructor(r, k, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    Object out = chain.proceed();
                    try {
                        onSidebarHandleReady(chain.getArg(0));
                    } catch (Throwable ignore) {
                    }
                    return out;
                }
            })) n++;
        }
        // ② onViewDetachedFromWindow(View)：手柄从窗口摘下时注销广播
        try {
            Method m = c.getDeclaredMethod("onViewDetachedFromWindow", android.view.View.class);
            if (hookMethod(r, m, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    Object out = chain.proceed();
                    try {
                        unregisterSidebarReceiver();
                    } catch (Throwable ignore) {
                    }
                    return out;
                }
            })) n++;
        } catch (Throwable ignore) {
        }
        // ③ 采样方法：混淆名，按形状定位（public void f(Rect)）—— 直接跳过原实现
        for (Method m : c.getDeclaredMethods()) {
            if (m.getReturnType() != Void.TYPE) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != android.graphics.Rect.class) continue;
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (hookMethod(r, m, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) {
                    return null;
                }
            })) n++;
        }
        lg(5, TAG, "hide_sidebar hooks=" + n);
        if (n == 0) return 0;
        r.where = "RegionSamplingHelper 构造器/采样/卸载（" + n + " 处）";
        return n;
    }

    /** 手柄 View 就绪：记录所在侧、注册广播、稍后抹掉绘制 */
    private static void onSidebarHandleReady(Object viewObj) {
        if (!(viewObj instanceof android.view.View)) return;
        final android.view.View view = (android.view.View) viewObj;
        sSidebarView = view;
        if (sSidebarDockLocation == -1) {
            try {
                Context ctx = view.getContext();
                if (ctx != null) {
                    sSidebarDockLocation = ctx.getSharedPreferences("sp_video_box", Context.MODE_PRIVATE)
                            .getInt("dock_line_location", 0);
                }
            } catch (Throwable ignore) {
            }
        }
        registerSidebarReceiver(view);
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    hideSidebarHandlePaint(view);
                }
            }, 150L);
        } catch (Throwable ignore) {
        }
    }

    /** 注册「唤起侧边栏」广播：模块自己、adb、自动化工具都能发 */
    private static void registerSidebarReceiver(final android.view.View view) {
        synchronized (SIDEBAR_LOCK) {
            if (sSidebarReceiver != null) return;
            Context ctx = view.getContext();
            if (ctx == null) return;
            android.content.BroadcastReceiver rec = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, android.content.Intent intent) {
                    try {
                        showSidebarByTouch(view, intent);
                    } catch (Throwable t) {
                        lg(5, TAG, "唤起侧边栏失败: " + t);
                    }
                }
            };
            try {
                android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_SHOW_SIDEBAR);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(rec, filter, Context.RECEIVER_EXPORTED);
                } else {
                    ctx.registerReceiver(rec, filter);
                }
                sSidebarReceiver = rec;
                lg(5, TAG, "侧边栏广播已注册: " + ACTION_SHOW_SIDEBAR);
            } catch (Throwable t) {
                lg(5, TAG, "侧边栏广播注册失败: " + t);
            }
        }
    }

    /** 手柄 View 从窗口摘下：注销广播，避免重复注册与泄漏 */
    private static void unregisterSidebarReceiver() {
        synchronized (SIDEBAR_LOCK) {
            android.content.BroadcastReceiver rec = sSidebarReceiver;
            android.view.View view = sSidebarView;
            sSidebarReceiver = null;
            sSidebarView = null;
            if (rec == null || view == null) return;
            try {
                Context ctx = view.getContext();
                if (ctx != null) ctx.unregisterReceiver(rec);
            } catch (Throwable ignore) {
            }
        }
    }

    /** 抹掉手柄线的绘制：整条 View 透明（不改变可见性，滑动手势仍然有效） */
    private static void hideSidebarHandlePaint(android.view.View view) {
        lg(5, TAG, "侧边栏手柄已抹除绘制 hash=" + view.hashCode());
        try {
            view.setAlpha(0f);
        } catch (Throwable ignore) {
        }
        try {
            if (view instanceof android.widget.ImageView) {
                ((android.widget.ImageView) view).setImageAlpha(0);
            }
        } catch (Throwable ignore) {
        }
    }

    /** 用合成触摸事件把侧边栏滑出来（方向取 sp_video_box 记录，或广播里的 inDirection） */
    private static void showSidebarByTouch(android.view.View view, android.content.Intent intent) {
        int pos = sSidebarDockLocation < 0 ? 0 : sSidebarDockLocation;
        android.os.Bundle extra = intent == null ? null : intent.getBundleExtra("actionInfo");
        if (extra != null) {
            pos = extra.getInt("inDirection", pos);
            try {
                Context ctx = view.getContext();
                if (ctx != null) {
                    ctx.getSharedPreferences("sp_video_box", Context.MODE_PRIVATE)
                            .edit().putInt("dock_line_location", pos).apply();
                }
            } catch (Throwable ignore) {
            }
            sSidebarDockLocation = pos;
        }
        lg(5, TAG, "收到唤起侧边栏广播 pos=" + pos + " attached=" + view.isAttachedToWindow());
        if (!view.isAttachedToWindow()) return;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float y = loc[1] + 15f;
        long t = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down;
        android.view.MotionEvent move;
        android.view.MotionEvent up;
        if (pos == 0) {
            down = sidebarTouchEvent(t, android.view.MotionEvent.ACTION_DOWN, 4f, y);
            move = sidebarTouchEvent(t + 20L, android.view.MotionEvent.ACTION_MOVE, 160f, y);
            up = sidebarTouchEvent(t + 21L, android.view.MotionEvent.ACTION_UP, 160f, y);
        } else {
            float x = loc[0];
            down = sidebarTouchEvent(t, android.view.MotionEvent.ACTION_DOWN, x - 4f, y);
            move = sidebarTouchEvent(t + 20L, android.view.MotionEvent.ACTION_MOVE, x - 160f, y);
            up = sidebarTouchEvent(t + 21L, android.view.MotionEvent.ACTION_UP, x - 160f, y);
        }
        try {
            view.dispatchTouchEvent(down);
            view.dispatchTouchEvent(move);
            view.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            move.recycle();
            up.recycle();
        }
    }

    /** 合成触摸事件；source=9999 作为「模块合成」标记（与 HyperCeiler 一致） */
    private static android.view.MotionEvent sidebarTouchEvent(long time, int action, float x, float y) {
        android.view.MotionEvent e = android.view.MotionEvent.obtain(time, time, action, x, y, 0);
        e.setSource(9999);
        return e;
    }

    /** #10 AppLockPinScramble：应用锁 PIN 键盘乱序（hook 构造器后打乱 3×4 数字网格） */
    private static int spAppLockScramble(Rule r) {
        Class<?> c;
        try {
            c = Class.forName("com.miui.applicationlock.widget.MiuiNumericInputView", false, sCl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (hookConstructor(r, k, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    Object out = chain.proceed();
                    try {
                        scramblePinPad(chain.getThisObject());
                    } catch (Throwable ignore) {
                    }
                    return out;
                }
            })) n++;
        }
        if (n == 0) return 0;
        r.where = "MiuiNumericInputView 构造器乱序（" + n + "）";
        return n;
    }

    /** 打乱 3×4 数字键盘顺序（最后一行首/尾键位置固定） */
    private static void scramblePinPad(Object view) {
        if (!(view instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup keys = (android.view.ViewGroup) view;
        if (keys.getChildCount() < 4) return;
        List<android.view.View> randomViews = new ArrayList<android.view.View>();
        android.view.View bottom0 = null;
        android.view.View bottom2 = null;
        for (int row = 0; row <= 3; row++) {
            if (!(keys.getChildAt(row) instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup cols = (android.view.ViewGroup) keys.getChildAt(row);
            if (cols.getChildCount() < 3) return;
            for (int col = 0; col <= 2; col++) {
                android.view.View child = cols.getChildAt(col);
                if (row == 3 && col == 0) { bottom0 = child; continue; }
                if (row == 3 && col == 2) { bottom2 = child; continue; }
                randomViews.add(child);
            }
            cols.removeAllViews();
        }
        Collections.shuffle(randomViews);
        int cnt = 0;
        for (int row = 0; row <= 3; row++) {
            android.view.ViewGroup cols = (android.view.ViewGroup) keys.getChildAt(row);
            for (int col = 0; col <= 2; col++) {
                if (row == 3 && col == 0) { if (bottom0 != null) cols.addView(bottom0); continue; }
                if (row == 3 && col == 2) { if (bottom2 != null) cols.addView(bottom2); continue; }
                if (cnt < randomViews.size()) cols.addView(randomViews.get(cnt));
                cnt++;
            }
        }
    }

    /** 目标包内 string 资源缓存（避免热路径重复 getIdentifier） */
    private static final Map<String, String> S_RES_CACHE = new ConcurrentHashMap<String, String>();

    /** 读取目标包内 string 资源（带短缓存） */
    private static String resourceString(android.content.Context ctx, String name) {
        if (ctx == null) return null;
        String pkg = ctx.getPackageName();
        String key = pkg + '/' + name;
        String v = S_RES_CACHE.get(key);
        if (v != null) return v.isEmpty() ? null : v;
        try {
            int id = ctx.getResources().getIdentifier(name, "string", pkg);
            v = id == 0 ? "" : ctx.getResources().getString(id);
        } catch (Throwable t) {
            v = "";
        }
        S_RES_CACHE.put(key, v);
        return v.isEmpty() ? null : v;
    }

    /** #21 RemoveOpenAppConfirmationPopup：链式启动确认弹窗自动确认 */
    private static int spOpenAppPopup(Rule r) {
        Method setText = null;
        for (Method m : android.widget.TextView.class.getDeclaredMethods()) {
            if (!m.getName().equals("setText")) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 1 && pt[0] == CharSequence.class) { setText = m; break; }
        }
        if (setText == null) return 0;
        if (hookMethod(r, setText, new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                Object out = chain.proceed();
                List<Object> a = chain.getArgs();
                if (a == null || a.isEmpty() || a.get(0) == null) return out;
                Object self = chain.getThisObject();
                if (!(self instanceof android.widget.TextView)) return out;
                try {
                    android.widget.TextView tv = (android.widget.TextView) self;
                    String accept = resourceString(tv.getContext(), "button_text_accept");
                    if (accept != null && accept.equals(a.get(0).toString())) tv.performClick();
                } catch (Throwable ignore) {
                }
                return out;
            }
        })) {
            r.where = "TextView.setText 自动确认（button_text_accept）";
            return 1;
        }
        return 0;
    }

    /** #18 BypassAdbInstallVerify：绕过 USB 安装网络校验 */
    private static int spAdbInstallBypass(Rule r, Sp sp) {
        List<Method> verify = sp.ms("connectivity", null, "https://srv.sec.miui.com/data/adb", null);
        List<Method> caller = sp.ms("AdbInstallActivity&start request for adb install!", null, null, null);
        int n = 0;
        for (final Method m : verify) {
            if (hookMethod(r, m, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    try {
                        Object instance = chain.getThisObject();
                        Class<?> bc = Class.forName("com.miui.permcenter.install.AdbInstallVerifyActivity$b", false, sCl);
                        Object task = newInstance(bc, instance);
                        if (task != null) {
                            setObjectField(instance, "d", task);
                            Object exec = getStaticObjectField(android.os.AsyncTask.class, "THREAD_POOL_EXECUTOR");
                            if (exec != null) callMethod(task, "executeOnExecutor", exec, new Void[]{});
                        }
                    } catch (Throwable ignore) {
                    }
                    return null;
                }
            })) n++;
        }
        for (final Method m : caller) {
            if (hookMethod(r, m, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    return null;
                }
            })) n++;
        }
        if (n == 0) return 0;
        r.where = "AdbInstall 校验绕过（" + n + " 方法）";
        return n;
    }

    /** #30 AppRestrict：解除系统应用限制（应用管理 / 防火墙） */
    private static int spAppRestrict(Rule r) {
        int n = 0;
        // ① AppManageUtils.getAppInfo(...)：返回值清除 FLAG_SYSTEM
        try {
            Class<?> c = Class.forName("com.miui.appmanager.AppManageUtils", false, sCl);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("getAppInfo")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 5 || pt[0] != ApplicationInfo.class) continue;
                if (hookMethod(r, m, new SpAction() {
                    @Override
                    public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                        Object out = chain.proceed();
                        if (out instanceof ApplicationInfo) {
                            List<Object> a = chain.getArgs();
                            boolean sys = false;
                            if (a != null) {
                                for (Object arg : a) {
                                    if (arg instanceof Integer && ((Integer) arg) == 128) { sys = true; break; }
                                }
                            }
                            if (sys) ((ApplicationInfo) out).flags &= ~ApplicationInfo.FLAG_SYSTEM;
                        }
                        return out;
                    }
                })) n++;
            }
        } catch (Throwable ignore) {
        }
        // ② ShowAppDetailFragment.initFirewallData：强制非系统应用
        try {
            Class<?> c = Class.forName("com.miui.networkassistant.ui.fragment.ShowAppDetailFragment", false, sCl);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("initFirewallData")) continue;
                if (hookMethod(r, m, new SpAction() {
                    @Override
                    public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                        Object appInfo = getObjectField(chain.getThisObject(), "mAppInfo");
                        if (appInfo != null) setBooleanField(appInfo, "isSystemApp", false);
                        return chain.proceed();
                    }
                })) n++;
            }
        } catch (Throwable ignore) {
        }
        // ③ FirewallService.setSystemAppWifiRuleAllow：空操作
        try {
            Class<?> c = Class.forName("com.miui.networkassistant.service.FirewallService", false, sCl);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("setSystemAppWifiRuleAllow")) continue;
                if (hookMethod(r, m, new SpAction() {
                    @Override
                    public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                        return null;
                    }
                })) n++;
            }
        } catch (Throwable ignore) {
        }
        if (n == 0) return 0;
        r.where = "系统应用限制解除（" + n + " 方法）";
        return n;
    }

    /** #28 AppDefaultSort：应用管理列表默认排序（固定为名称排序） */
    private static int spAppDefaultSort(Rule r) {
        Class<?> c;
        try {
            c = Class.forName("com.miui.appmanager.AppManagerMainActivity", false, sCl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("onCreate")) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != 1 || pt[0] != Bundle.class) continue;
            if (hookMethod(r, m, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    List<Object> a = chain.getArgs();
                    if (a != null && !a.isEmpty()) {
                        Bundle b = (Bundle) a.get(0);
                        if (b == null) b = new Bundle();
                        b.putInt("current_sory_type", 0);
                        b.putInt("current_sort_type", 0);
                        a.set(0, b);
                    }
                    return chain.proceed();
                }
            })) n++;
        }
        if (n == 0) return 0;
        r.where = "AppManagerMainActivity.onCreate 默认名称排序";
        return n;
    }

    /** #5 气泡应用限制解除：气泡列表注入 + 默认气泡全量加载 */
    private static int spBubbleUnlock(Rule r) {
        Class<?> c;
        try {
            c = Class.forName("com.miui.bubbles.settings.BubblesSettings", false, sCl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        // ① getBubbleAppString：按 mActiveBubbles 构建「pkg:userId,」串
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("getBubbleAppString")) continue;
            if (hookMethod(r, m, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    Object active = getObjectField(chain.getThisObject(), "mActiveBubbles");
                    if (!(active instanceof Set)) return chain.proceed();
                    StringBuilder sb = new StringBuilder();
                    for (Object bubble : (Set<?>) active) {
                        if (bubble == null) continue;
                        sb.append(callMethod(bubble, "getPackageName")).append(':')
                                .append(getObjectField(bubble, "userId")).append(',');
                    }
                    return sb.toString();
                }
            })) n++;
        }
        // ② getDefaultBubbles：返回全部自由窗口建议应用
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("getDefaultBubbles")) continue;
            if (hookMethod(r, m, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    try {
                        Object self = chain.getThisObject();
                        Class<?> bubbleApp = Class.forName("com.miui.bubbles.settings.BubbleApp", false, sCl);
                        Object ctx = getObjectField(self, "mContext");
                        Object uidObj = getObjectField(self, "mCurrentUserId");
                        int userId = uidObj instanceof Integer ? (Integer) uidObj : 0;
                        Object list = callStaticMethod(
                                Class.forName("android.util.MiuiMultiWindowUtils", false, sCl),
                                "getFreeformSuggestionList", ctx);
                        if (list instanceof List) {
                            android.util.ArrayMap<String, Object> map = new android.util.ArrayMap<String, Object>();
                            for (Object s : (List<?>) list) {
                                Object app = newInstance(bubbleApp, String.valueOf(s), userId);
                                if (app == null) continue;
                                callMethod(app, "setChecked", Boolean.TRUE);
                                map.put(String.valueOf(s), app);
                            }
                            return map;
                        }
                    } catch (Throwable ignore) {
                    }
                    return chain.proceed();
                }
            })) n++;
        }
        if (n == 0) return 0;
        r.where = "BubblesSettings 气泡限制解除（" + n + " 方法）";
        return n;
    }

    /** #36 ShowBatteryTemperatureNew：显示电池温度（替换电池界面温度文本） */
    private static int spBatteryTemp(Rule r) {
        int n = 0;
        // 语义类优先（未混淆），失败再试常见混淆名
        n += hookBatteryTemp(r, "com.miui.powercenter.BatteryFragment");
        if (n == 0) n += hookBatteryTemp(r, "com.miui.powercenter.a");
        if (n == 0) return 0;
        r.where = "电池温度文本替换（" + n + " 方法）";
        return n;
    }

    private static int hookBatteryTemp(Rule r, String ownerDot) {
        Class<?> c;
        try {
            c = Class.forName(ownerDot, false, sCl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getReturnType() != String.class) continue;
            if (hookMethod(r, m, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    Object out = chain.proceed();
                    List<Object> a = chain.getArgs();
                    Object ctx = (a == null || a.isEmpty()) ? null : a.get(0);
                    if (ctx instanceof android.content.Context) {
                        return batteryTemperatureText((android.content.Context) ctx);
                    }
                    return out;
                }
            })) n++;
        }
        return n;
    }

    /** 读取当前电池温度文本（℃，读不到时回退占位） */
    private static String batteryTemperatureText(android.content.Context ctx) {
        try {
            android.content.Intent it = ctx.registerReceiver(null, new android.content.IntentFilter(
                    android.content.Intent.ACTION_BATTERY_CHANGED));
            if (it != null) {
                int t = it.getIntExtra("temperature", 0) / 10;
                return t + " ℃";
            }
        } catch (Throwable ignore) {
        }
        return "-- ℃";
    }

    /** #33 AddAppManagerEntry：应用管理菜单添加 AOSP 应用管理入口 */
    private static int spAppManagerEntry(Rule r) {
        Class<?> c;
        try {
            c = Class.forName("com.miui.appmanager.AppManagerMainActivity", false, sCl);
        } catch (Throwable t) {
            return 0;
        }
        if (hookMenuEntry(r, c, "com.android.settings",
                "com.android.settings.applications.ManageApplications", null, "AOSP 应用管理")) {
            r.where = "AppManagerMainActivity.onCreateOptionsMenu + AOSP 入口";
            return 1;
        }
        return 0;
    }

    /** #32 AddAppInfoEntry：应用详情菜单添加 AOSP 应用信息入口 */
    private static int spAppInfoEntry(Rule r) {
        Class<?> c;
        try {
            c = Class.forName("com.miui.appmanager.ApplicationsDetailsActivity", false, sCl);
        } catch (Throwable t) {
            return 0;
        }
        if (hookMenuEntry(r, c, "com.android.settings", "com.android.settings.SubSettings",
                "com.android.settings.applications.appinfo.AppInfoDashboardFragment", "AOSP 应用信息")) {
            r.where = "ApplicationsDetailsActivity.onCreateOptionsMenu + AOSP 入口";
            return 1;
        }
        return 0;
    }

    // ──────────────── 「策反 XSOF 内鬼」：安全服务检测链路阻断 ────────────────

    /** 实现类的稳定日志锚点：整个 APK 里只有 MiSafetyDetectService$MiSafetyDetectImpl 含此串 */
    private static final String XSOF_ANCHOR_IMPL = "Call API 001 from package : ";

    /** 任务对象类的日志锚点，仅用于与结构判定交叉验证（命中不一致时以结构判定为准） */
    private static final String XSOF_ANCHOR_TASK = "ClientApiRequest";

    /** 「任务号 -> 能力名」映射类锚点：全 APK 只有埋点工具类含 xsof_*_result 这些键 */
    private static final String XSOF_ANCHOR_CAPMAP = "xsof_device_trust_result";

    /** 任务号：设备可信 */
    private static final int XSOF_CAP_TRUST = 10;
    /** 任务号：风险应用 */
    private static final int XSOF_CAP_RISK = 11;
    /** 任务号：模拟触控 */
    private static final int XSOF_CAP_TOUCH = 12;

    /**
     * 任务号 -> 良性结论 JSON；返回 null 表示「没有可靠良性值，不干预」。
     *
     * <p>结论格式照抄安全服务自己成功时的写法（risk 取 guard provider 的 riskData 字符串；
     * simulated_touch 是 {@code {"result":boolean}}），因此调用方解析不会异常。</p>
     */
    private static String xsofBenign(int cap) {
        switch (cap) {
            case XSOF_CAP_RISK:
                return "[]";
            case XSOF_CAP_TOUCH:
                return "{\"result\":false}";
            default:
                return null;
        }
    }

    /**
     * 拿不到良性结论时的错误码。
     *
     * <p>设备可信的结论来自 trustservice，格式无从伪造，只能回「检测失败」：
     * 沿用旧实现观测到的 1005（安全服务自己在无法完成检测时也是这个形态，
     * 例如 {@code c(null, 1013)}），调用方会放弃该结论而不是拿到「不可信」。</p>
     */
    private static int xsofFailCode(int cap) {
        return cap == XSOF_CAP_TRUST ? 1005 : 1000;
    }

    /** 本能力是否可给良性结论；反诈等一律不干预（不伪造结论） */
    private static boolean xsofKnown(int cap) {
        return cap == XSOF_CAP_TRUST || cap == XSOF_CAP_RISK || cap == XSOF_CAP_TOUCH;
    }

    // ── 跨版本加固：能力语义以 APK 自带映射表为准，任务号常量只作回退 ──

    /** 「任务号 -> 能力名」映射方法（public static String a(int)）； = 不可用 */
    private static volatile Method sXsofCapName;
    private static volatile boolean sXsofCapNameTried;

    /** 找到映射类里的 public static String x(int) */
    private static Method xsofFindCapName(Class<?> c) {
        if (c == null) return null;
        for (Method m : c.getMethods()) {
            if (m.getReturnType() != String.class) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != Integer.TYPE) continue;
            return m;
        }
        return null;
    }

    /** 安装期定位映射类（结果走签名缓存，进程内只查一次） */
    private static void xsofLocateCapMap(Sp sp) {
        if (sXsofCapNameTried) return;
        sXsofCapNameTried = true;
        try {
            sXsofCapName = xsofFindCapName(sp.cls(XSOF_ANCHOR_CAPMAP));
        } catch (Throwable t) {
            sXsofCapName = null;
        }
        lg(4, TAG, "xsof 能力名映射 " + (sXsofCapName == null ? "不可用（回退任务号常量）" : "已就绪"));
    }

    /** 任务号 -> 能力名； = 映射不可用 */
    private static String xsofCapNameOf(int cap) {
        Method m = sXsofCapName;
        if (m == null) return null;
        try {
            Object s = m.invoke(null, cap);
            return (s instanceof String) ? (String) s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 任务号 -> 本规则处理的能力组；-1 = 不干预。
     *
     * <p>优先按 APK 自带映射表给出的能力名判定语义，这样即使以后版本把任务号重排
     * （例如中间插入一个新能力）也不会张冠李戴；只有映射不可用时才回退到任务号
     * 常量 10/11/12（13.5.x / 13.6.x 实测一致）。</p>
     */
    private static int xsofCapOf(int cap) {
        String n = xsofCapNameOf(cap);
        if (n != null) {
            if (n.contains("device_trust")) return XSOF_CAP_TRUST;
            if (n.contains("risk_app")) return XSOF_CAP_RISK;
            if (n.contains("simulated_touch")) return XSOF_CAP_TOUCH;
            return -1;
        }
        return (cap >= XSOF_CAP_TRUST && cap <= XSOF_CAP_TOUCH) ? cap : -1;
    }

    /** 框架 / 依赖库类型：不可能是检测执行器的持有者 */
    private static boolean xsofFw(Class<?> c) {
        if (c == null || c.isPrimitive() || c.isArray()) return true;
        String n = c.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("android.")
                || n.startsWith("androidx.") || n.startsWith("kotlin.")
                || n.startsWith("com.xiaomi.trustservice.");
    }

    /**
     * 任务对象的回传方法：{@code (String result, int code)V}。
     *
     * <p>调用它就等于「结束本次检测并把结果回给调用方」—— 这正是阻断点。</p>
     */
    private static Method xsofFindFinish(Class<?> c) {
        if (c == null) return null;
        for (Method m : c.getMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (m.getReturnType() == Void.TYPE && ps.length == 2
                    && ps[0] == String.class && ps[1] == Integer.TYPE) {
                return m;
            }
        }
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (m.getReturnType() == Void.TYPE && ps.length == 2
                    && ps[0] == String.class && ps[1] == Integer.TYPE) {
                return m;
            }
        }
        return null;
    }

    /** 任务对象特征：有非静态 int 字段（任务号）且带回传方法 */
    private static boolean xsofIsTask(Class<?> p) {
        if (p == null) return false;
        boolean hasId = false;
        for (Field f : p.getDeclaredFields()) {
            if (f.getType() == Integer.TYPE && !Modifier.isStatic(f.getModifiers())) {
                hasId = true;
                break;
            }
        }
        return hasId && xsofFindFinish(p) != null;
    }

    /** 任务对象类 -> 任务号字段（按类型定位，不依赖混淆后的字段名） */
    private static final Map<Class<?>, Object> XSOF_ID_FLD =
            new ConcurrentHashMap<Class<?>, Object>();

    private static int xsofTaskId(Object task) {
        if (task == null) return -1;
        Class<?> c = task.getClass();
        Object hit = XSOF_ID_FLD.get(c);
        if (hit == null) {
            Field found = null;
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != Integer.TYPE || Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                } catch (Throwable ignore) {
                }
                found = f;
                break;
            }
            hit = (found == null) ? FIELD_ABSENT : (Object) found;
            XSOF_ID_FLD.put(c, hit);
        }
        if (hit == FIELD_ABSENT) return -1;
        try {
            return ((Field) hit).getInt(task);
        } catch (Throwable ignore) {
            return -1;
        }
    }

    /** 任务对象类 -> 回传方法 */
    private static final Map<Class<?>, Object> XSOF_FIN_M =
            new ConcurrentHashMap<Class<?>, Object>();

    /** 结束任务并回传结果（异常一律吞掉：阻断失败也不能影响原流程） */
    private static boolean xsofFinish(Object task, String result, int code) {
        if (task == null) return false;
        Class<?> c = task.getClass();
        Object hit = XSOF_FIN_M.get(c);
        if (hit == null) {
            Method found = xsofFindFinish(c);
            if (found != null) {
                try {
                    found.setAccessible(true);
                } catch (Throwable ignore) {
                }
            }
            hit = (found == null) ? FIELD_ABSENT : (Object) found;
            XSOF_FIN_M.put(c, hit);
        }
        if (hit == FIELD_ABSENT) return false;
        try {
            ((Method) hit).invoke(task, result, code);
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * 阻断动作：不跑真实检测，直接给良性结论并结束该任务。
     *
     * <p>为什么能在执行器入口直接回传：任务对象自己带结束方法，安全服务的失败路径
     * 也是同样的形态（{@code c(null, code)}），所以调用方拿到的是「无风险 / 检测失败」，
     * 而不是 trustservice、guard provider 或 SecurityManager 的真实结论。</p>
     */
    static final SpAction XSOF_BLOCK = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule r) throws Throwable {
            List<Object> a = chain.getArgs();
            Object task = (a == null || a.isEmpty()) ? null : a.get(0);
            // 任务号 -> 能力语义：优先用 APK 自带映射表（跨版本任务号重排也不张冠李戴），
            // 映射不可用时才回退到任务号常量
            int cap = xsofCapOf(xsofTaskId(task));
            if (!xsofKnown(cap)) return chain.proceed();
            String json = xsofBenign(cap);
            int code = (json == null) ? xsofFailCode(cap) : 0;
            if (xsofFinish(task, json, code)) {
                lg(4, TAG, "xsof 已策反 cap=" + cap + " code=" + code);
            }
            return null;
        }
    };

    /**
     * 「策反XSOF内鬼」安装器：定位 XSOF 的 3 个任务执行器并阻断。
     *
     * <p>定位全部靠稳定锚点 + 结构，不用混淆后的类名/方法名：</p>
     * <ol>
     *   <li>实现类：DexKit 类锚点 {@link #XSOF_ANCHOR_IMPL}（进程内首次解析后被签名缓存）</li>
     *   <li>服务类：实现类的外层类（MiSafetyDetectService，进程 .remote）</li>
     *   <li>执行器持有者：服务类里「非框架类型」的实例字段（no/j、eo/b、mo/b）</li>
     *   <li>执行器方法：持有者类中「public + void + 唯一参数是任务对象」的方法</li>
     *   <li>任务对象：被最多持有者当作执行器参数、且满足「int 任务号 + 回传方法」的类</li>
     * </ol>
     */
    private static int spXsofBlock(Rule r, Sp sp) {
        if (sp == null) return 0;
        Class<?> impl = sp.cls(XSOF_ANCHOR_IMPL);
        if (impl == null) impl = sp.cls(XSOF_ANCHOR_IMPL.trim());
        if (impl == null) {
            lg(4, TAG, "xsof：实现类未命中，跳过");
            return 0;
        }
        xsofLocateCapMap(sp);
        String implName = impl.getName();
        int d = implName.indexOf('$');
        if (d <= 0) return 0;
        Class<?> svc;
        try {
            svc = Class.forName(implName.substring(0, d), false, sCl);
        } catch (Throwable t) {
            return 0;
        }

        Map<Class<?>, List<Method>> byParam = new LinkedHashMap<Class<?>, List<Method>>();
        try {
            for (Field f : svc.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> holder = f.getType();
                if (xsofFw(holder)) continue;
                for (Method m : holder.getDeclaredMethods()) {
                    if (m.getReturnType() != Void.TYPE) continue;
                    if (!Modifier.isPublic(m.getModifiers())) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length != 1 || xsofFw(ps[0])) continue;
                    List<Method> l = byParam.get(ps[0]);
                    if (l == null) {
                        l = new ArrayList<Method>();
                        byParam.put(ps[0], l);
                    }
                    if (!contains(l, m)) l.add(m);
                }
            }
        } catch (Throwable t) {
            lg(5, TAG, "xsof：扫描执行器失败 " + t);
            return 0;
        }

        Class<?> task = null;
        List<Method> best = null;
        for (Map.Entry<Class<?>, List<Method>> e : byParam.entrySet()) {
            if (!xsofIsTask(e.getKey())) continue;
            List<Method> l = e.getValue();
            if (best == null || l.size() > best.size()) {
                task = e.getKey();
                best = l;
            }
        }
        if (best == null) {
            lg(4, TAG, "xsof：执行器未命中 候选参数类=" + byParam.keySet());
            return 0;
        }
        Class<?> anchor = sp.cls(XSOF_ANCHOR_TASK);
        if (anchor != null && anchor != task) {
            lg(4, TAG, "xsof：锚点类 " + anchor.getName() + " 与结构判定 " + task.getName()
                    + " 不一致，以结构判定为准");
        }
        int n = 0;
        for (Method m : best) if (hookMethod(r, m, XSOF_BLOCK)) n++;
        if (n > 0) {
            r.where = "执行器 " + n + " 处回良性结论（任务对象 " + task.getName() + "）";
        }
        lg(4, TAG, "xsof 已策反执行器 " + n + "/" + best.size()
                + " task=" + task.getName() + " impl=" + implName);
        return n;
    }

    // ───────────────────── 视频工具箱温度墙 ─────────────────────

    /**
     * 板温读取失败的日志串：全 APK 只有 VideoToolboxTempMonitor 的读温方法用它，
     * 所以既能定位到「温度监控类」，也能定位到「读温方法」本身。
     */
    private static final String VT_ANCHOR_TEMP = "read board temp fail, node=";

    /** 伪造的板温读数：低于 41℃ 高温墙，也低于 39℃ 缓冲阈值，状态机永远停在不动作分支。 */
    private static final float VT_FAKE_TEMP = 25.0f;

    /**
     * 「去除视频工具箱高温限制」安装器。
     *
     * <p>原版逻辑（VideoToolboxTempMonitor 的 5 秒轮询 Runnable）：读板温，温度 &gt; 41℃
     * 且首次超温就 post 禁用任务（极清播放 / 画质提升等被关掉并提示「温度过高」），
     * 39~41℃ 只记录时间，低于 39℃ 且持续 10 分钟才恢复。教程的改包路线是把判定里的
     * {@code 41.0f} 改大到 {@code 1000.0f}；模块走运行时路线，更简单地让「板温读数」
     * 恒为常温：状态机永远不进超温分支，因此既不触发禁用，也不产生埋点与 UI 提示，
     * 而且温度确实回落时也不会有「已经禁用过」的残留状态。</p>
     *
     * <p>副作用面：该读温方法只有监控类自己的轮询会调用（DexKit xref 确认唯一调用者），
     * 所以不会影响其它用到温度的模块。</p>
     */
    /** 倍速进入游戏：NuPlayer::onStart 读取的调试属性键 */
    private static final String GS_KEY = "debug.game.video.speed";
    /** 倍速进入游戏：开机加速（与 GS_KEY 同一套判定，手机管家会一并复位） */
    private static final String GS_BOOT = "debug.game.video.boot";
    /** 倍速进入游戏：能力位（手机管家侧判定用；只改判定返回值，不写属性） */
    private static final String GS_SUP = "debug.game.video.support";
    /**
     * 状态机日志锚点：手机管家<strong>每次游戏状态变化</strong>都会调用带这条日志的方法来下发
     * 「极速启动」开关（真机 smali：{@code j1.k0(Z)}，日志
     * {@code "setStartSpeedLaunchWhenSupport(speed: %s)"}）。原版实现里它<b>只写 BOOT，从不写
     * SPEED</b>，这正是「3 秒极速启动」在本机失效的直接原因：speed 只在服务 onCreate / 设置开关
     * 回调里写，而这两条路径本机都不走。用日志文案做跨版本锚点最稳（它是 log 文本，改动概率最低）。
     */
    private static final String GS_STATE = "setStartSpeedLaunchWhenSupport";
    /**
     * 写入值。读取端是 {@code android::base::GetBoolProperty}（真机
     * libmediaplayerservice.so 的 {@code NuPlayer::onStart} 里 {@code tst w0, #0xff}），
     * "1"/"true" 都算真；同时 "1" 对 atoi 语义的读取端也安全，所以统一写 "1"。
     */
    private static final String GS_VAL = "1";

    /**
     * 写入入口的 hook：<b>先执行宿主原方法</b>（保留它的副作用），<b>再强制调用宿主自己的
     * 写入工具</b>把该键写成 {@link #GS_VAL}。
     *
     * <p>为什么必须落在入口这一层：原版宿主 {@code o0.k0(Z)V / o0.m0(Z)V} 的方法体里
     * {@code const/4 p0, 0x0} 把入参丢掉、写死 {@code "false"}（真机在装宿主 base.apk
     * md5 085f1d97… 反编译确认；「安全服务修改版 13.6.5」只是把它改成 {@code 0x1} 写
     * "true"）。所以「门禁放行 → 宿主自己调 k0(true)」在语义上写不出真值 —— 必须接管这两个
     * 入口的效果，等价于修改版那 2 字节补丁，但用 hook 实现、不改宿主 APK。</p>
     *
     * <p>模块自身不发 setprop：{@code SystemProperties.set} 是 @hide，模块反射调用会被
     * hidden API 拦掉（早期实现就卡在这里，日志「setprop 失败」）。这里调用的是宿主自带的
     * 写入工具（{@code com.miui.common.utils.f2.e(String,String)}，方法体内直接
     * {@code invoke-static SystemProperties.set}），普通应用方法，不受 hidden API 限制。</p>
     */
    private static final class GSForceWrite implements SpAction {
        private final String key;
        private final Method writer;
        /** 命中计数（诊断用：只打印前若干次，避免刷屏） */
        private static final java.util.concurrent.atomic.AtomicInteger HITS =
                new java.util.concurrent.atomic.AtomicInteger();

        GSForceWrite(String key, Method writer) {
            this.key = key;
            this.writer = writer;
        }

        @Override
        public Object run(XposedInterface.Chain chain, Rule r) throws Throwable {
            final int hit = HITS.incrementAndGet();
            String arg = "-";
            try {
                Object[] a = chain.getArgs().toArray();
                if (a.length > 0 && a[0] != null) arg = String.valueOf(a[0]);
            } catch (Throwable ignored) {
            }
            Object ret = chain.proceed();             // 宿主原逻辑（含它自己那句写 "false"）
            try {
                writer.setAccessible(true);
                writer.invoke(null, key, GS_VAL);     // 强制调用宿主写入工具 → 置位
                if (hit <= 40) {
                    lg(5, TAG, "game_speed：入口命中#" + hit + " key=" + key + " 入参=" + arg
                            + " → 已强制调用宿主写入工具置 " + GS_VAL);
                }
            } catch (Throwable t) {
                if (hit <= 40) {
                    lg(5, TAG, "game_speed：入口命中#" + hit + " key=" + key + " 入参=" + arg
                            + " → 强制写入失败 " + t);
                }
            }
            return ret;
        }
    }

    /**
     * 状态机同步：宿主每次「游戏状态变化」都会调用带 {@link #GS_STATE} 日志的那个方法下发
     * 极速启动开关（原版实现里它只写 BOOT，从不写 SPEED）。我们在它跑完后，
     * 用宿主自己的写入工具把 SPEED 也置上，等价于「修改版把 k0/m0 恒写 true」的效果。
     */
    private static final class GSStateSync implements SpAction {
        private final Method writer;
        private static final java.util.concurrent.atomic.AtomicInteger HITS =
                new java.util.concurrent.atomic.AtomicInteger();

        GSStateSync(Method writer) {
            this.writer = writer;
        }

        @Override
        public Object run(XposedInterface.Chain chain, Rule r) throws Throwable {
            final int hit = HITS.incrementAndGet();
            String arg = "-";
            try {
                Object[] a = chain.getArgs().toArray();
                if (a.length > 0 && a[0] != null) arg = String.valueOf(a[0]);
            } catch (Throwable ignored) {
            }
            Object ret = chain.proceed();          // 宿主自己的状态机逻辑（原样执行）
            try {
                writer.setAccessible(true);
                writer.invoke(null, GS_KEY, GS_VAL);   // 关键：补上 SPEED
                if (hit <= 30) {
                    lg(5, TAG, "game_speed：状态机命中#" + hit + " 宿主下发=" + arg
                            + " → 已用宿主写入工具补写 " + GS_KEY + "=" + GS_VAL);
                }
            } catch (Throwable t) {
                if (hit <= 30) {
                    lg(5, TAG, "game_speed：状态机命中#" + hit + " → 补写失败 " + t);
                }
            }
            return ret;
        }
    }

    /**
     * 诊断：只记录「宿主自己」调用属性写入工具这件事（键/值/调用栈），默认不改变行为。
     * 当状态机入口没找到时（{@code alsoSpeed}=true）退化为兜底：只要宿主写 BOOT，就顺带把 SPEED 补上。
     */
    private static final class GSLogWrite implements SpAction {
        private static final java.util.concurrent.atomic.AtomicInteger N =
                new java.util.concurrent.atomic.AtomicInteger();

        private final Method writer;
        private final boolean alsoSpeed;

        GSLogWrite() {
            this(null, false);
        }

        GSLogWrite(Method writer, boolean alsoSpeed) {
            this.writer = writer;
            this.alsoSpeed = alsoSpeed;
        }

        @Override
        public Object run(XposedInterface.Chain chain, Rule r) throws Throwable {
            String key = null;
            String val = null;
            try {
                Object[] a = chain.getArgs().toArray();
                key = String.valueOf(a[0]);
                val = String.valueOf(a[1]);
            } catch (Throwable ignored) {
            }
            if (alsoSpeed && writer != null && GS_BOOT.equals(key)) {
                try {
                    writer.setAccessible(true);
                    writer.invoke(null, GS_KEY, GS_VAL);
                } catch (Throwable ignored) {
                }
            }
            if (key != null && (GS_KEY.equals(key) || GS_BOOT.equals(key) || GS_SUP.equals(key))
                    && N.incrementAndGet() <= 30) {
                StringBuilder sb = new StringBuilder("game_speed：宿主写入 " + key + "=" + val + " 调用栈:");
                try {
                    StackTraceElement[] st = new Throwable().getStackTrace();
                    int shown = 0;
                    for (int i = 0; i < st.length && shown < 8; i++) {
                        String cn = st[i].getClassName();
                        if (cn.startsWith("com.hyper.Camellia")) continue;   // 跳过模块自身
                        sb.append("\n      ").append(cn).append('.')
                                .append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
                        shown++;
                    }
                } catch (Throwable ignored) {
                }
                lg(5, TAG, sb.toString());
            }
            return chain.proceed();
        }
    }

    /**
     * 兜底：若某版本找不到「方法体含属性名 + 调用静态 (String,String) 写入工具」的入口，
     * 则退回拦截宿主写入工具的调用、只改写这两个键的值（写入同样由宿主执行）。
     */
    private static final SpAction GS_REWRITE = new SpAction() {
        @Override
        public Object run(XposedInterface.Chain chain, Rule r) throws Throwable {
            Object[] a = chain.getArgs().toArray();
            if (a.length == 2 && a[0] instanceof String
                    && (GS_KEY.equals(a[0]) || GS_BOOT.equals(a[0]))) {
                a[1] = GS_VAL;
                return chain.proceed(a);
            }
            return chain.proceed();
        }
    };

    /**
     * 强制开启「倍速进入游戏」（{@code debug.game.video.speed} / {@code .boot}）。
     *
     * <p>机制（真机 libmediaplayerservice.so 反汇编 + logcat 双向确认）：MIUI 定制的
     * {@code NuPlayer::onStart} 用 {@code android::base::GetBoolProperty} 读该属性，为真即
     * 打印「game enters the acceleration state」并把开场视频速率抬到 3.0，进程启动期的
     * 开场视频因此被快进过去。</p>
     *
     * <p>不硬编码任何宿主类名：以「方法体内出现属性名」为锚点找到写入方法
     * （原版是 {@code o0.k0} / {@code o0.m0}），再从它们的被调用列表里筛出静态
     * {@code (String,String)V} —— 那就是宿主的属性写入工具。hook 这些<b>入口</b>并在其中
     * 强制调用该写入工具，于是宿主的每一次复位都变成置位（门禁/入参一概不看）。</p>
     */
    private static int spGameSpeed(Rule r, Sp sp) {
        if (sp == null) return 0;
        int entries = 0;
        List<Method> writers = new ArrayList<Method>();
        for (String anchor : new String[]{GS_KEY, GS_BOOT}) {
            MethodDataList list = sp.q(anchor, null, null);
            if (list == null) continue;
            for (int i = 0; i < list.size(); i++) {
                MethodData md = list.get(i);
                if (!"void".equals(md.getReturnTypeName())) continue;
                // 入口方法体里的静态 (String,String)V = 宿主的属性写入工具
                Method w = null;
                MethodDataList calls = md.getInvokes();
                for (int j = 0; j < calls.size(); j++) {
                    MethodData call = calls.get(j);
                    if (call.getParamCount() != 2) continue;
                    if (!"void".equals(call.getReturnTypeName())) continue;
                    if (!Modifier.isStatic(call.getModifiers())) continue;
                    List<String> pt = call.getParamTypeNames();
                    if (!"java.lang.String".equals(pt.get(0))
                            || !"java.lang.String".equals(pt.get(1))) continue;
                    try {
                        w = call.getMethodInstance(sCl);
                        if (w != null && !contains(writers, w)) writers.add(w);
                    } catch (Throwable ignored) {
                    }
                    break;
                }
                if (w == null) continue;
                try {
                    Method entry = md.getMethodInstance(sCl);
                    if (entry != null && hookMethod(r, entry, new GSForceWrite(anchor, w))) {
                        entries++;
                        lg(5, TAG, "game_speed：已接管写入入口 "
                                + entry.getDeclaringClass().getName() + "."
                                + entry.getName() + "（键 " + anchor + "）→ 写入工具 "
                                + w.getDeclaringClass().getName() + "." + w.getName());
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // 加载期置位：debug.* 属性重启即空，而宿主在部分机型上根本不会写 SPEED
        // （真机证据：9 分钟内 o0.k0 一次未被调用），所以这里用宿主自己的写入工具先置一次，
        // 保证「游戏开场视频」第一次被 NuPlayer 解码时属性已经是真值。
        if (!writers.isEmpty()) {
            try {
                Method w = writers.get(0);
                w.setAccessible(true);
                w.invoke(null, GS_KEY, GS_VAL);
                w.invoke(null, GS_SUP, "true");
                lg(5, TAG, "game_speed：模块加载即置位 " + GS_KEY + "=" + GS_VAL
                        + "、" + GS_SUP + "=true（宿主写入工具 "
                        + w.getDeclaringClass().getName() + "." + w.getName() + "）");
            } catch (Throwable t) {
                lg(5, TAG, "game_speed：加载期置位失败 " + t);
            }
        }

        // 状态机入口：宿主下发给定的「极速启动」开关时，把 SPEED 一并补上
        int stateHooks = 0;
        if (!writers.isEmpty()) {
            MethodDataList sl = sp.q(GS_STATE, null, null);
            if (sl != null) {
                for (int i = 0; i < sl.size(); i++) {
                    MethodData md = sl.get(i);
                    if (!"void".equals(md.getReturnTypeName())) continue;
                    List<String> pt = md.getParamTypeNames();
                    if (pt.size() != 1 || !"boolean".equals(pt.get(0))) continue;
                    try {
                        Method m = md.getMethodInstance(sCl);
                        if (m != null && hookMethod(r, m, new GSStateSync(writers.get(0)))) {
                            stateHooks++;
                            lg(5, TAG, "game_speed：已接管状态机入口 "
                                    + m.getDeclaringClass().getName() + "." + m.getName()
                                    + "(Z) → 每次下发都补写 " + GS_KEY + "=" + GS_VAL);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (stateHooks == 0) {
            lg(5, TAG, "game_speed：状态机入口未命中（锚点 " + GS_STATE + "），SPEED 改由 BOOT 写入时顺带补写");
        }

        int n = entries + stateHooks;
        if (entries == 0) {
            // 兜底（正常版本走不到）：改写宿主写入工具的入参
            for (final Method w : writers) {
                if (hookMethod(r, w, GS_REWRITE)) n++;
            }
            lg(5, TAG, "game_speed：未找到写入入口，退回改写写入工具入参（" + n + " 个）");
        } else {
            // 诊断 + 兜底：接管入口的情况下，再看宿主「自己」有没有写这两个键、从哪条链写
            boolean alsoSpeed = (stateHooks == 0);
            for (final Method w : writers) {
                if (hookMethod(r, w, new GSLogWrite(w, alsoSpeed))) n++;
            }
            lg(5, TAG, "game_speed：写入工具已加诊断记录（" + writers.size() + " 个，兜底补写=" + alsoSpeed + "）");
        }
        // 能力位：debug.game.video.support 的判定恒 true（只改判定，不写属性）
        for (final Method m : sp.ms(GS_SUP, null, null, null)) {
            if (m.getReturnType() != Boolean.TYPE) continue;
            if (hookMethod(r, m, RET_TRUE)) n++;
        }
        if (n == 0) return 0;
        r.where = "倍速进入游戏：接管 " + entries + " 个写入入口，强制宿主置 " + GS_VAL
                + "（写入工具 " + writers.size() + " 个）";
        return n;
    }

    private static int spVideoTemp(Rule r, Sp sp) {
        if (sp == null) return 0;
        Class<?> mon = sp.cls(VT_ANCHOR_TEMP);
        if (mon == null) {
            lg(4, TAG, "video_temp：温度监控类未命中，跳过");
            return 0;
        }
        Method read = null;
        try {
            for (Method m : mon.getDeclaredMethods()) {
                if (m.getReturnType() != Float.TYPE) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (read != null) {
                    // 形状不唯一（版本变化）：宁可不 hook，也不要误接管别的读数
                    lg(4, TAG, "video_temp：读温方法候选不唯一 " + read.getName() + " / " + m.getName());
                    return 0;
                }
                read = m;
            }
        } catch (Throwable t) {
            lg(5, TAG, "video_temp：扫描读温方法失败 " + t);
            return 0;
        }
        if (read == null) {
            lg(4, TAG, "video_temp：读温方法未命中 " + mon.getName());
            return 0;
        }
        final String ownerName = mon.getName();
        final String readName = read.getName();
        if (!hookMethod(r, read, new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                return Float.valueOf(VT_FAKE_TEMP);
            }
        })) {
            return 0;
        }
        r.where = "板温读数恒为 " + (int) VT_FAKE_TEMP + "℃（41℃ 温度墙永不触发）"
                + ownerName + "." + readName;
        lg(4, TAG, "video_temp 已接管板温读数 " + ownerName + "." + readName);
        return 1;
    }

    /** 在 Activity.onCreateOptionsMenu(Menu) 之后追加一个跳转 AOSP 设置的菜单项 */
    private static boolean hookMenuEntry(Rule r, Class<?> activity, String targetPkg, String targetCls,
                                         String fragment, String label) {
        for (Method m : activity.getDeclaredMethods()) {
            if (!m.getName().equals("onCreateOptionsMenu")) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != 1 || !android.view.Menu.class.isAssignableFrom(pt[0])) continue;
            return hookMethod(r, m, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    Object out = chain.proceed();
                    addMenuEntry(chain, targetPkg, targetCls, fragment, label);
                    return out;
                }
            });
        }
        return false;
    }

    private static void addMenuEntry(XposedInterface.Chain chain, String targetPkg, String targetCls,
                                     String fragment, String label) {
        try {
            List<Object> a = chain.getArgs();
            if (a == null || a.isEmpty() || !(a.get(0) instanceof android.view.Menu)) return;
            android.view.Menu menu = (android.view.Menu) a.get(0);
            Object self = chain.getThisObject();
            if (!(self instanceof android.app.Activity)) return;
            android.app.Activity act = (android.app.Activity) self;
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .setClassName(targetPkg, targetCls);
            if (fragment != null) {
                String pkg = act.getIntent().getStringExtra("package_name");
                if (pkg == null) return;
                int uid = act.getIntent().getIntExtra("miui.intent.extra.USER_ID",
                        android.os.Process.myUid());
                android.os.Bundle b = new android.os.Bundle();
                b.putString("package", pkg);
                b.putInt("uid", uid);
                it.putExtra(":settings:show_fragment", fragment);
                it.putExtra(":settings:show_fragment_title", label);
                it.putExtra(":settings:show_fragment_args", b);
            }
            android.view.MenuItem item = menu.add(android.view.Menu.NONE, 0, 0, label);
            item.setIntent(it);
            item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        } catch (Throwable ignore) {
        }
    }

    /** #42 NoLowBatteryWarning：hook Settings.System.getInt/getString，按 key 条件返回 */
    private static int spNoLowBattery(Rule r) {
        int n = 0;
        try {
            Class<?> sys = Class.forName("android.provider.Settings$System", false, sCl);
            for (Method m : sys.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2 || pt[0] != android.content.ContentResolver.class) continue;
                if (m.getName().equals("getInt")) {
                    if (hookMethod(r, m, new SpAction() {
                        @Override
                        public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                            List<Object> a = chain.getArgs();
                            if (a != null && a.size() > 1
                                    && "low_battery_dialog_disabled".equals(a.get(1))) {
                                return 1;
                            }
                            return chain.proceed();
                        }
                    })) n++;
                } else if (m.getName().equals("getString")) {
                    if (hookMethod(r, m, new SpAction() {
                        @Override
                        public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                            List<Object> a = chain.getArgs();
                            if (a != null && a.size() > 1
                                    && "low_battery_sound".equals(a.get(1))) {
                                return null;
                            }
                            return chain.proceed();
                        }
                    })) n++;
                }
            }
        } catch (Throwable t) {
            lg(5, TAG, "noLowBattery hook 失败: " + t);
        }
        if (n == 0) return 0;
        r.where = "Settings.System low_battery（" + n + " 方法）";
        return n;
    }

    /** #44 SimplifyMainFragment：过滤主页广告/热门卡片 */
    private static int spSimplifyMain(Rule r) {
        final String owner = "com.miui.common.card.CardViewRvAdapter";
        Class<?> c;
        try {
            c = Class.forName(owner, false, sCl);
        } catch (Throwable t) {
            return 0;
        }
        Method m = null;
        for (Method x : c.getDeclaredMethods()) {
            if (x.getName().equals("addAll")) m = x;
        }
        if (m == null) return 0;
        if (hookMethod(r, m, new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                List<Object> a = chain.getArgs();
                if (a != null && !a.isEmpty() && a.get(0) instanceof List) {
                    List<?> list = (List<?>) a.get(0);
                    List<Object> keep = new ArrayList<Object>();
                    for (Object model : list) {
                        if (model == null) continue;
                        String cn = model.getClass().getName();
                        if ("com.miui.common.card.models.FuncListBannerCardModel".equals(cn)
                                || "com.miui.common.card.models.PopularActionCardModel".equals(cn)) {
                            continue;
                        }
                        keep.add(model);
                    }
                    a.set(0, keep);
                }
                return chain.proceed();
            }
        })) {
            r.where = "CardViewRvAdapter.addAll 过滤广告卡片";
            return 1;
        }
        return 0;
    }

    /** #28 MoreBatteryInfoNew：动态注入制造日期/首次使用/设计容量/实际容量偏好行 */
    private static int spMoreBatteryInfo(Rule r, Sp sp) {
        int n = 0;
        // 4 个 static 锚点方法（刷新时 invoke(null) 取值）
        final Method mManufacturing = one(sp.ms("manufacturing_date", null, null, null));
        final Method mFirstUsage = one(sp.ms("first_usage_date", null, null, null));
        final Method mFullCapacity = one(sp.ms("getBatteryChargeFull", null, null, null));
        final Method mDesignCapacity = one(sp.ms("charge_full_design", null, null, null));

        // ① PreferenceGroup.removePreference：阻止移除电池信息偏好行
        Method rm = reflectBy("androidx.preference.PreferenceGroup", "removePreference",
                new Rule("x", G_BATTERY, "x", A_SP, null).params("Landroidx/preference/Preference;"));
        if (rm != null && hookMethod(r, rm, new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                List<Object> a = chain.getArgs();
                Object pref = (a != null && !a.isEmpty()) ? a.get(0) : null;
                String key = pref == null ? null : (String) callMethod(pref, "getKey");
                if (key != null && MBI_KEEP_KEYS.contains(key)) return Boolean.FALSE; // 阻止移除
                return chain.proceed();
            }
        })) n++;

        // ② ChargeProtectFragment.onCreatePreferences：after 捕获 + 刷新
        Method onCreate = reflectBy("com.miui.powercenter.nightcharge.ChargeProtectFragment",
                "onCreatePreferences", r);
        if (onCreate != null && hookMethod(r, onCreate, new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                clearMbiPrefs();
                Object out = chain.proceed();
                captureMbiPrefs(chain.getThisObject());
                refreshMbiRows(mManufacturing, mFirstUsage, mFullCapacity, mDesignCapacity);
                return out;
            }
        })) n++;

        // ③ ChargeProtectFragment$c.handleMessage：after 刷新
        Method handler = reflectBy("com.miui.powercenter.nightcharge.ChargeProtectFragment$c",
                "handleMessage", r);
        if (handler != null && hookMethod(r, handler, new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                Object out = chain.proceed();
                Object h = chain.getThisObject();
                if (h != null) {
                    callMethod(h, "a");
                    callMethod(h, "b");
                }
                refreshMbiRows(mManufacturing, mFirstUsage, mFullCapacity, mDesignCapacity);
                return out;
            }
        })) n++;

        if (n == 0) return 0;
        r.where = "MoreBatteryInfo（" + n + " 方法）";
        sUsedDexkit = true;
        return n;
    }

    private static void clearMbiPrefs() {
        mbiCategory = null;
        mbiProductionDate = null;
        mbiFirstUseDate = null;
        mbiDesignCapacity = null;
        mbiFullCapacity = null;
        mbiHealthPercent = null;
        mbiCycleCount = null;
    }

    private static void captureMbiPrefs(Object fragment) {
        clearMbiPrefs();
        if (fragment == null) return;
        Object ctx = callMethod(fragment, "getContext");
        if (ctx == null) ctx = callMethod(fragment, "requireContext");
        mbiContext = ctx instanceof Context ? (Context) ctx : null;
        mbiCategory = callMethod(fragment, "findPreference", "preference_key_category_battery_info");
        mbiProductionDate = callMethod(fragment, "findPreference", "reference_production_date");
        mbiFirstUseDate = callMethod(fragment, "findPreference", "reference_first_use_date");
        mbiDesignCapacity = callMethod(fragment, "findPreference", "reference_battery_charge_full_design");
        mbiFullCapacity = callMethod(fragment, "findPreference", "reference_battery_charge_full");
        mbiHealthPercent = callMethod(fragment, "findPreference", "reference_battery_health_percent");
        mbiCycleCount = callMethod(fragment, "findPreference", "reference_battery_cycle_count");
    }

    private static void refreshMbiRows(Method manufacturing, Method firstUsage,
                                       Method fullCap, Method designCap) {
        Context ctx = mbiContext;
        if (ctx == null) return;
        ensureMbiDynamicPrefs(ctx);
        updateDatePref(mbiProductionDate, resolveDateText(manufacturing, 7, ctx, "production"));
        updateDatePref(mbiFirstUseDate, resolveDateText(firstUsage, 8, ctx, "first use"));
        Long design = resolveRawCapacity(designCap,
                new String[]{"/sys/class/power_supply/battery/charge_full_design",
                        "/sys/class/power_supply/bms/charge_full_design"});
        Long full = resolveRawCapacity(fullCap,
                new String[]{"/sys/class/power_supply/battery/charge_full",
                        "/sys/class/power_supply/bms/charge_full"});
        setPrefText(mbiDesignCapacity, formatCapacity(design));
        setPrefText(mbiFullCapacity, formatCapacity(full));
        setPrefText(mbiHealthPercent, formatHealthPercent(full, design));
        setPrefText(mbiCycleCount, resolveCycleCountText());
    }

    private static void ensureMbiDynamicPrefs(Context ctx) {
        Object category = mbiCategory;
        if (category == null || ctx == null) return;
        boolean needFull = mbiFullCapacity == null;
        boolean needDesign = mbiDesignCapacity == null;
        boolean needHealth = mbiHealthPercent == null;
        boolean needCycle = mbiCycleCount == null;
        if (!needFull && !needDesign && !needHealth && !needCycle) return;
        int next = resolveNextOrder(category);
        if (needFull) {
            mbiFullCapacity = ensureTextPref(category, ctx,
                    "reference_battery_charge_full", MBI_FULL_TITLE, next);
        }
        if (needDesign) {
            mbiDesignCapacity = ensureTextPref(category, ctx,
                    "reference_battery_charge_full_design", MBI_DESIGN_TITLE, next + 1);
        }
        if (needHealth) {
            mbiHealthPercent = ensureTextPref(category, ctx,
                    "reference_battery_health_percent", MBI_HEALTH_PERCENT_TITLE, next + 2);
        }
        if (needCycle) {
            mbiCycleCount = ensureTextPref(category, ctx,
                    "reference_battery_cycle_count", MBI_CYCLE_COUNT_TITLE, next + 3);
        }
    }

    private static Object ensureTextPref(Object category, Context ctx, String key, String title, int order) {
        try {
            Class<?> tp = Class.forName("miuix.preference.TextPreference", false, sCl);
            Object pref = newInstance(tp, ctx);
            if (pref == null) pref = newInstance(tp, ctx, (Object) null);
            if (pref == null) return null;
            callMethod(pref, "setKey", key);
            callMethod(pref, "setTitle", title);
            callMethod(pref, "setClickable", false);
            callMethod(pref, "setTouchAnimationEnable", false);
            callMethod(pref, "setOrder", order);
            callMethod(category, "addPreference", pref);
            return pref;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int resolveNextOrder(Object category) {
        Integer count = (Integer) callMethod(category, "getPreferenceCount");
        if (count == null) return 1000;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            Object p = callMethod(category, "getPreference", i);
            if (p == null) continue;
            Integer o = (Integer) callMethod(p, "getOrder");
            if (o != null && o > max) max = o;
        }
        if (max == Integer.MIN_VALUE) return count + 100;
        return max >= Integer.MAX_VALUE - 2 ? Integer.MAX_VALUE - 2 : max + 1;
    }

    private static String resolveDateText(Method official, int propertyId, Context ctx, String label) {
        String f = formatDateCandidate((String) callStaticValue(official));
        if (f != null) return f;
        Long secs = readBatteryDateSeconds(ctx, propertyId);
        String fallback = secs == null ? null : secondsToYyyyMMdd(secs);
        f = formatDateCandidate(fallback);
        if (f != null) return f;
        return formatDateCandidate(readDateFromSysfs(label));
    }

    private static Object callStaticValue(Method m) {
        if (m == null) return null;
        try {
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String formatDateCandidate(String candidate) {
        if (candidate == null) return null;
        String v = candidate.trim();
        if (v.length() != 8 || v.equals("00000000") || v.equals("99999999")) return null;
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) < '0' || v.charAt(i) > '9') return null;
        }
        try {
            java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US);
            parser.setLenient(false);
            java.util.Date parsed = parser.parse(v);
            if (parsed == null) return null;
            String pattern = android.text.format.DateFormat.getBestDateTimePattern(
                    Locale.getDefault(), "yMMM");
            return new java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(parsed);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Long readBatteryDateSeconds(Context ctx, int propertyId) {
        try {
            android.os.BatteryManager bm =
                    (android.os.BatteryManager) ctx.getSystemService("batterymanager");
            if (bm == null) return null;
            long v = bm.getLongProperty(propertyId);
            return v > 0 ? v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String secondsToYyyyMMdd(long seconds) {
        if (seconds <= 0) return null;
        try {
            return new java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
                    .format(new java.util.Date(seconds * 1000L));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Long resolveRawCapacity(Method m, String[] paths) {
        Long raw = null;
        Object v = callStaticValue(m);
        if (v instanceof Integer) raw = ((Integer) v).longValue();
        else if (v instanceof Long) raw = (Long) v;
        else if (v instanceof Number) raw = ((Number) v).longValue();
        else if (v instanceof String) {
            try {
                raw = Long.parseLong((String) v);
            } catch (Throwable ignored) {
            }
        }
        if (raw == null || raw <= 0) {
            Integer n = readFirstPositiveNumber(paths);
            if (n != null) raw = n.longValue();
        }
        return (raw != null && raw > 0) ? raw : null;
    }

    private static String formatCapacity(Long raw) {
        if (raw == null) return "--";
        return String.format(Locale.getDefault(), MBI_CAPACITY_FMT, raw / 1000L);
    }

    private static String formatHealthPercent(Long full, Long design) {
        if (full == null || design == null || design <= 0) return "--";
        return String.format(Locale.getDefault(), MBI_HEALTH_PERCENT_FMT,
                full * 100.0 / design);
    }

    private static String resolveCycleCountText() {
        for (String p : new String[]{"/sys/class/power_supply/battery/cycle_count",
                "/sys/class/power_supply/bms/cycle_count"}) {
            String v = readFirstLine(p);
            if (v != null && !v.trim().isEmpty()) return v.trim() + "次";
        }
        return "--";
    }

    private static Integer readFirstPositiveNumber(String[] paths) {
        for (String p : paths) {
            String raw = readFirstLine(p);
            if (raw == null) continue;
            String t = raw.trim();
            if (t.isEmpty()) continue;
            try {
                int v = Integer.parseInt(t);
                if (v > 0) return v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String readDateFromSysfs(String label) {
        String[] paths = "production".equals(label)
                ? new String[]{"/sys/class/power_supply/battery/manufacturing_date",
                        "/sys/class/power_supply/battery/production_date",
                        "/sys/class/power_supply/bms/manufacturing_date"}
                : new String[]{"/sys/class/power_supply/battery/first_usage_date",
                        "/sys/class/power_supply/bms/first_usage_date"};
        for (String p : paths) {
            String v = readFirstLine(p);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private static void setPrefText(Object pref, String text) {
        if (pref == null) return;
        callMethod(pref, "setText", text);
    }

    private static void updateDatePref(Object pref, String text) {
        if (pref == null) return;
        boolean vis = text != null && !text.isEmpty();
        callMethod(pref, "setVisible", vis);
        if (vis) setPrefText(pref, text);
    }

    /** #27 BatteryHealth：捕获健康度 int + 定位 reference_battery_health + 写入百分比 */
    private static int spBatteryHealth(Rule r, Sp sp) {
        int n = 0;
        // ① qg/a.x(int)：手机管家存储健康度的 static 方法（唯一使用 battery_health_soh）
        Method health = one(sp.ms("battery_health_soh", null, null, null));
        if (health != null && hookMethod(r, health, new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                Object out = chain.proceed();
                List<Object> a = chain.getArgs();
                if (a != null && !a.isEmpty() && a.get(0) instanceof Integer) {
                    sBatteryHealth = (Integer) a.get(0);
                }
                return out;
            }
        })) n++;

        // ② ChargeProtectFragment.onCreatePreferences：after 定位 reference_battery_health
        Method find = reflectBy("com.miui.powercenter.nightcharge.ChargeProtectFragment",
                "onCreatePreferences", r);
        if (find != null && hookMethod(r, find, new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                Object out = chain.proceed();
                sBatteryHealthPref = callMethod(chain.getThisObject(),
                        "findPreference", "reference_battery_health");
                return out;
            }
        })) n++;

        // ③ ChargeProtectFragment$c.handleMessage：after 写入健康度文本
        Method cc = reflectBy("com.miui.powercenter.nightcharge.ChargeProtectFragment$c",
                "handleMessage", r);
        if (cc != null && hookMethod(r, cc, new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                Object out = chain.proceed();
                if (sBatteryHealthPref != null && sBatteryHealth >= 0) {
                    callMethod(sBatteryHealthPref, "setText", sBatteryHealth + " %");
                }
                return out;
            }
        })) n++;

        if (n == 0) return 0;
        r.where = "BatteryHealth（" + n + " 方法）";
        sUsedDexkit = true;
        return n;
    }

    /** 无线快充状态判定：void 则改第 0 参为 true 后继续，否则直接返回 true */
    private static int spSwlTip(Rule r, Sp sp) {
        Method m = one(sp.ms("key_is_connected_super_wls_tx", null, null, null));
        if (m == null) return 0;
        r.where = "锚点 key_is_connected_super_wls_tx";
        sUsedDexkit = true;
        if (m.getReturnType() == Void.TYPE) {
            return hookMethod(r, m, ARG_TRUE_NEXT) ? 1 : 0;
        }
        return hookMethod(r, m, RET_TRUE) ? 1 : 0;
    }

    /**
     * 显示详细耗电数据：PowerRankHelperHolder 内的支持名单方法返回 true，
     * 同族其它机型判定方法返回 false（与 HyperCeiler 的 method1/method2 对照一致）。
     */
    private static int spScreenUsedTime(Rule r, Sp sp) {
        Rule shape = new Rule("x", r.group, r.label, A_SP, null).ret("Z").params();
        List<Method> support = sp.ms("ishtar&nuwa&fuxi", null, null, shape);
        List<Method> family = sp.ms(null, null, "PowerRankHelperHolder", shape);
        if (family.isEmpty()) return 0;
        final HashSet<Method> set = new HashSet<Method>(support);
        int n = 0;
        for (final Method m : family) {
            final boolean yes = set.contains(m);
            if (hookMethod(r, m, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) {
                    return yes ? Boolean.TRUE : Boolean.FALSE;
                }
            })) n++;
        }
        if (n == 0) return 0;
        r.where = "锚点 PowerRankHelperHolder（" + n + " 方法，支持名单 " + set.size() + "）";
        sUsedDexkit = true;
        return n;
    }

    /** 恢复耗电排行：版本判定方法（ro.miui.ui.version.code + 数字 9）返回 false */
    private static int spPowerRank(Rule r, Sp sp) {
        Rule shape = new Rule("x", r.group, r.label, A_SP, null).ret("Z");
        List<Method> ms = sp.ms(null, new int[]{9}, "ro.miui.ui.version.code", shape);
        if (ms.isEmpty()) ms = sp.ms(null, new int[]{9}, "ro.miui.ui.version.code", null);
        int n = 0;
        for (Method m : ms) if (hookMethod(r, m, RET_FALSE)) n++;
        if (n == 0) return 0;
        r.where = "锚点 ro.miui.ui.version.code + 数字 9（" + n + " 方法）";
        sUsedDexkit = true;
        return n;
    }

    /** 解锁隐私摄像模式：开关方法改参数为 true，同族判定方法返回 true */
    private static int spBeautyPrivacy(Rule r, Sp sp) {
        Method r0 = one(sp.ms("persist.sys.privacy_camera", null, null, null));
        if (r0 == null) return 0;
        int n = 0;
        if (hookMethod(r, r0, ARG_TRUE_NEXT)) n++;
        Rule shape = new Rule("x", r.group, r.label, A_SP, null).ret("Z").params();
        for (Method m : sp.ms(null, null, "persist.sys.privacy_camera", shape)) {
            if (m.equals(r0)) continue;
            if (hookMethod(r, m, RET_TRUE)) n++;
        }
        if (n == 0) return 0;
        r.where = "锚点 persist.sys.privacy_camera（" + n + " 方法）";
        sUsedDexkit = true;
        return n;
    }

    /**
     * 网络助手离线信息标注：HyperCeiler 同样硬编码该类名。
     * 构造器内把 isOffline 压成 false，取值方法直接返回 false。
     */
    private static int spOfflineInfo(Rule r) {
        final String owner = "com.miui.networkassistant.ui.bean.OffLineData$BaseData";
        Class<?> c;
        try {
            c = Class.forName(owner, false, sCl);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            try {
                k.setAccessible(true);
                sMod.hook(k).intercept(new Hooker(r, new SpAction() {
                    @Override
                    public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                        Object out = chain.proceed();
                        setBoolField(chain.getThisObject(), "isOffline");
                        return out;
                    }
                }));
                n++;
            } catch (Throwable t) {
                lg(5, TAG, "offlineInfo 构造器 hook 失败: " + t);
            }
        }
        try {
            Method m = c.getDeclaredMethod("isOffline");
            if (hookMethod(r, m, RET_FALSE)) n++;
        } catch (Throwable ignored) {
        }
        if (n == 0) return 0;
        r.where = "回退 " + owner;
        sUsedFallback = true;
        return n;
    }

    /** 晕车舒缓提示时机：解锁 i(Context)（恒 false 占位，onCreatePreferences 据此决定是否移除提示时机下拉） */
    private static int spCarSicknessTip(Rule r, Sp sp) {
        String[] want = {"Landroid/content/Context;"};
        List<Method> skip = new ArrayList<Method>();
        // g(Context)：读 settings_car_sickness_mode 的开关状态，绝不能 hook
        for (Method m : sp.ms("settings_car_sickness_mode", null, "car_sickness_is_support", null)) {
            if (m.getReturnType() == Boolean.TYPE && paramMatch(m, want)) skip.add(m);
        }
        // h(Context)：传感器硬件支持，属于「解锁晕车舒缓」而非提示时机
        for (Method m : sp.ms("sensor", null, "car_sickness_is_support", null)) {
            if (m.getReturnType() == Boolean.TYPE && paramMatch(m, want)) skip.add(m);
        }
        int n = 0;
        for (Method m : sp.ms(null, null, "car_sickness_is_support", null)) {
            if (m.getReturnType() != Boolean.TYPE) continue;
            if (!paramMatch(m, want)) continue;
            if (skip.contains(m)) continue; // 只剩 i(Context)
            if (hookMethod(r, m, RET_TRUE)) n++;
        }
        if (n == 0) return 0;
        r.where = "锚点 car_sickness_is_support（提示时机 " + n + " 方法）";
        sUsedDexkit = true;
        return n;
    }

    /**
     * 安全中心评分锁定 666：复刻安全服务修改版 13.5.9 —— 只把 ScoreManager
     * 的 j()/s()（当前分数，返回 int 零参）直接返回 666，首页点击入口屏蔽。
     */
    private static int spScore(Rule r, Sp sp) {
        final String owner = "com.miui.securityscan.scanner.ScoreManager";
        int n = 0;
        try {
            Class<?> c = Class.forName(owner, false, sCl);
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                String name = m.getName();
                // 复刻修改版：只压平 j()/s() 两个「当前分数」方法（返回 int 零参），
                // 不再误伤 r()/q() 等扣分累加方法。
                if (m.getReturnType() != Integer.TYPE) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (!"j".equals(name) && !"s".equals(name)) continue;
                if (hookMethod(r, m, CONST666)) n++;
            }
            if (n > 0) {
                r.where = "回退 " + owner + " j/s → 666（" + n + " 处）";
                sUsedFallback = true;
            }
        } catch (Throwable t) {
            lg(5, TAG, "score 定位失败: " + t);
        }
        // 首页分数控件点击入口：不再弹出评分详情
        try {
            Class<?> fc = Class.forName("com.miui.securityscan.ui.main.MainContentFrame", false, sCl);
            for (Method m : fc.getDeclaredMethods()) {
                if (!"onClick".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 1) continue;
                if (hookMethod(r, m, RET_NULL2)) n++;
                break;
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /**
     * 跳过授权倒计时（敏感权限/隐私拦截弹窗的 N 秒等待）。
     *
     * 宿主 13.6.x 的实现：InterceptBaseFragment 持有一个 Handler 内部类
     * {@code InterceptBaseFragment$b}，字段 a = 剩余秒数（默认 5，来自 g0()）；
     * {@code b.c()} 每 1000ms 投递一条消息，{@code b.handleMessage()} 里
     * a-1 后回调 {@code fragment.k0(a)}，子类 k0 在 a<=0 时才把按钮
     * 设为「确定/允许」并 setEnabled(true)。
     *
     * 因此这里不再依赖 Activity.onCreate(Bundle)（新版本 onCreate 只把
     * Bundle 存进字段，不再读取），而是直接在倒计时器第一次启动时把剩余
     * 秒数压到 1 并立即投递一条消息，使 k0(0) 在同一帧内执行。
     */
    private static int spSkipCountdown(Rule r, Sp sp) {
        int n = 0;
        final String frag = "com.miui.permcenter.privacymanager.InterceptBaseFragment";
        final String timer = frag + "$b";
        final String act = "com.miui.permcenter.privacymanager.model.InterceptBaseActivity";

        // 锚点 1：倒计时器 InterceptBaseFragment$b#c()V —— 启动时立即结算
        try {
            Class<?> tc = Class.forName(timer, false, sCl);
            for (Method m : tc.getDeclaredMethods()) {
                if (!"c".equals(m.getName()) || m.getParameterTypes().length != 0) continue;
                if (hookMethod(r, m, new SpAction() {
                    @Override
                    public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                        try {
                            Object o = chain.getThisObject();
                            Field f = o.getClass().getDeclaredField("a");
                            f.setAccessible(true);
                            int left = f.getInt(o);
                            if (left > 1) {
                                f.setInt(o, 1);
                                if (o instanceof Handler) {
                                    ((Handler) o).sendEmptyMessage(1);
                                }
                                lg(4, TAG, "skipCountdown: 剩余 " + left + "s，已立即结算");
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }
                })) {
                    n++;
                    r.where = "倒计时器 " + timer + "#c（剩余秒数压到 1 并立即 dispatch）";
                }
                break;
            }
        } catch (Throwable t) {
            lg(4, TAG, "skipCountdown 锚点1 不可用: " + t);
        }

        // 锚点 2：倒计时器 setter InterceptBaseFragment$b#b(I)V —— 初始秒数直接归 0
        if (n == 0) {
            try {
                Class<?> tc = Class.forName(timer, false, sCl);
                for (Method m : tc.getDeclaredMethods()) {
                    if (!"b".equals(m.getName()) || m.getParameterTypes().length != 1) continue;
                    if (m.getParameterTypes()[0] != Integer.TYPE) continue;
                    if (hookMethod(r, m, new SpAction() {
                        @Override
                        public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                            List<Object> a = chain.getArgs();
                            if (a != null && !a.isEmpty()) a.set(0, 0);
                            return chain.proceed();
                        }
                    })) {
                        n++;
                        r.where = "倒计时器 " + timer + "#b(I)（初始秒数归 0）";
                    }
                    break;
                }
            } catch (Throwable t) {
                lg(4, TAG, "skipCountdown 锚点2 不可用: " + t);
            }
        }

        // 锚点 3（旧版本回退）：InterceptBaseActivity#onCreate 注入 bundle
        if (n == 0) {
            try {
                Class<?> ac = Class.forName(act, false, sCl);
                for (Method m : ac.getDeclaredMethods()) {
                    if (!"onCreate".equals(m.getName())) continue;
                    if (m.getParameterTypes().length != 1 || m.getParameterTypes()[0] != Bundle.class) continue;
                    if (hookMethod(r, m, new SpAction() {
                        @Override
                        public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                            List<Object> a = chain.getArgs();
                            if (a != null && !a.isEmpty()) {
                                Object arg = a.get(0);
                                Bundle b;
                                if (arg instanceof Bundle) {
                                    b = (Bundle) arg;
                                } else {
                                    b = new Bundle();
                                    a.set(0, b);
                                }
                                b.putInt("KET_STEP_COUNT", 0);
                                b.putBoolean("KEY_ALLOW_ENABLE", true);
                            }
                            return chain.proceed();
                        }
                    })) {
                        n++;
                        r.where = "回退 " + act + "#onCreate（KET_STEP_COUNT=0 / KEY_ALLOW_ENABLE=true）";
                    }
                    break;
                }
            } catch (Throwable t) {
                lg(5, TAG, "skipCountdown 失败: " + t);
            }
        }
        if (n > 0) sUsedFallback = true;
        return n;
    }

    /** 跳过 SIM 卡锁的小米账号验证：类含 SimLockUtils 的 boolean(Context) 判定返回 true */
    private static int spSimLockAuth(Rule r, Sp sp) {
        String[] want = {"Landroid/content/Context;"};
        Method pick = null;
        for (Method m : sp.ms(null, null, "SimLockUtils", null)) {
            if (m.getReturnType() == Boolean.TYPE && paramMatch(m, want)) pick = m;
        }
        if (pick != null) {
            r.where = "锚点 SimLockUtils（boolean(Context)）";
            sUsedDexkit = true;
            return hookMethod(r, pick, RET_TRUE) ? 1 : 0;
        }
        try {
            Class<?> c = Class.forName("com.miui.simlock.utils.SimLockUtils", false, sCl);
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() == Boolean.TYPE && paramMatch(m, want)) pick = m;
            }
        } catch (Throwable ignored) {
        }
        if (pick == null) return 0;
        r.where = "回退 com.miui.simlock.utils.SimLockUtils";
        sUsedFallback = true;
        return hookMethod(r, pick, RET_TRUE) ? 1 : 0;
    }

    /** 移除 SIM 卡锁成功弹窗：直接 finish 掉成功页 */
    private static int spSimLockDialog(Rule r) {
        try {
            Class<?> c = Class.forName("com.miui.simlock.activity.SuccessDialogActivity", false, sCl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"onCreate".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 1) continue;
                if (hookMethod(r, m, new SpAction() {
                    @Override
                    public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                        Object out = chain.proceed();
                        try {
                            Method fin = chain.getThisObject().getClass()
                                    .getMethod("finish", new Class<?>[0]);
                            fin.invoke(chain.getThisObject());
                        } catch (Throwable ignored) {
                        }
                        return out;
                    }
                })) {
                    r.where = "回退 com.miui.simlock.activity.SuccessDialogActivity";
                    sUsedFallback = true;
                    return 1;
                }
                break;
            }
        } catch (Throwable t) {
            lg(5, TAG, "simlock_dialog 失败: " + t);
        }
        return 0;
    }

    /**
     * 新版隐私模糊缩略图：蓝图中除「隐私模糊设置页」以外的调用一律放行（不模糊）。
     * 与 HyperCeiler 的栈帧判定一致。
     */
    private static int spPrivacyBlur(Rule r, Sp sp) {
        Rule shape = new Rule("x", r.group, r.label, A_SP, null)
                .params("Landroid/content/Context;", "Ljava/lang/String;", "Z");
        List<Method> ms = sp.ms("miui_recents_privacy_thumbnail_blur", null, null, shape);
        if (ms.isEmpty()) ms = sp.ms("miui_recents_privacy_thumbnail_blur", null, null, null);
        int n = 0;
        for (Method m : ms) {
            if (hookMethod(r, m, new SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Rule rule) throws Throwable {
                    boolean inSettings = false;
                    for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                        if ("PrivacyThumbnailBlurSettings".equals(e.getClassName())) {
                            inSettings = true;
                            break;
                        }
                    }
                    // 仅设置页自身需要真实结果；其它调用一律不模糊
                    return inSettings ? chain.proceed() : null;
                }
            })) n++;
        }
        if (n == 0) return 0;
        r.where = "锚点 miui_recents_privacy_thumbnail_blur（" + n + " 方法）";
        sUsedDexkit = true;
        return n;
    }

    /** 移除自动连招黑名单：名单方法返回空表、判定方法返回 false、宏状态查询全部放行 */
    private static int spMacroBlacklist(Rule r, Sp sp) {
        int n = 0;
        final Rule zero = new Rule("x", r.group, r.label, A_SP, null).params();
        Method m1 = one(sp.ms("pref_gb_unsupport_macro_apps", null, null, zero));
        if (m1 != null && hookMethod(r, m1, new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rule) {
                return new ArrayList<String>();
            }
        })) n++;
        // 同声明的 0 参 boolean 方法（内部会读黑名单表）一律返回 false
        Class<?> owner = null;
        if (m1 != null) {
            owner = m1.getDeclaringClass();
            for (Method m : owner.getDeclaredMethods()) {
                if (m.getReturnType() != Boolean.TYPE) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (m.equals(m1)) continue;
                if (hookMethod(r, m, RET_FALSE)) n++;
            }
        }
        // 宏状态 Provider 的查询方法（2 / 3 参）全部放行
        Class<?> provider = sp.cls("content://com.xiaomi.macro.MacroStatusProvider/game_macro_change");
        if (provider != null) {
            for (Method m : provider.getDeclaredMethods()) {
                int pc = m.getParameterTypes().length;
                if (pc != 2 && pc != 3) continue;
                if (hookMethod(r, m, RET_TRUE)) n++;
            }
        }
        if (n == 0) return 0;
        r.where = "锚点 pref_gb_unsupport_macro_apps + MacroStatusProvider";
        sUsedDexkit = sp.cls("content://com.xiaomi.macro.MacroStatusProvider/game_macro_change") != null;
        return n;
    }

    /** 解锁杜比音效：checkMiGamePermission 所在类里的 dolby 判定直接返回 null */
    private static int spDolby(Rule r, Sp sp) {
        List<Method> ms = sp.ms("dolby", null, "checkMiGamePermission error", null);
        if (ms.isEmpty()) ms = sp.ms("dolby", null, null, null);
        int n = 0;
        for (Method m : ms) if (hookMethod(r, m, RET_NULL2)) n++;
        if (n == 0) return 0;
        r.where = "锚点 dolby + 类 checkMiGamePermission error（" + n + " 方法）";
        sUsedDexkit = true;
        return n;
    }

    /** 解锁视频增强功能：独显 / 超分 / 轮廓增强判定全部放行 */
    private static int spVideoFunc(Rule r, Sp sp) {
        final String clsAnchor = "ro.vendor.media.video.frc.support";
        Class<?> c = sp.cls(clsAnchor);
        if (c == null) return 0;
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            boolean flag = m.getReturnType() == Boolean.TYPE;
            boolean str = m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == String.class;
            if (flag && str) {
                if (hookMethod(r, m, RET_TRUE)) n++;
            }
        }
        for (Method m : sp.ms("debug.config.media.video.ais.support", null, clsAnchor, null)) {
            if (hookMethod(r, m, RET_TRUE)) n++;
        }
        if (n == 0) return 0;
        r.where = "锚点 " + clsAnchor + "（布尔判定 " + n + " 方法）";
        sUsedDexkit = true;
        return n;
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    static void setBoolField(Object obj, String name) {
        if (obj == null) return;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setBoolean(obj, false);
                return;
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
    }

    /** 反射调用实例方法（按方法名 + 参数可赋值匹配，含基本类型装箱） */
    static Object callMethod(Object obj, String name, Object... args) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != args.length) continue;
                boolean ok = true;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] == null) continue; // null 可赋给任意引用类型
                    Class<?> want = pt[i];
                    if (want.isPrimitive()) want = box(want);
                    if (!want.isInstance(args[i])) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(obj, args);
                } catch (Throwable t) {
                    lg(5, TAG, "callMethod " + name + " 失败: " + t);
                    return null;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    static Class<?> box(Class<?> p) {
        if (p == Boolean.TYPE) return Boolean.class;
        if (p == Byte.TYPE) return Byte.class;
        if (p == Short.TYPE) return Short.class;
        if (p == Character.TYPE) return Character.class;
        if (p == Integer.TYPE) return Integer.class;
        if (p == Long.TYPE) return Long.class;
        if (p == Float.TYPE) return Float.class;
        if (p == Double.TYPE) return Double.class;
        return p;
    }

    /** 反射调用 static 方法（按方法名 + 参数可赋值匹配，含基本类型装箱） */
    static Object callStaticMethod(Class<?> c, String name, Object... args) {
        if (c == null) return null;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != args.length) continue;
            boolean ok = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) continue;
                Class<?> want = pt[i];
                if (want.isPrimitive()) want = box(want);
                if (!want.isInstance(args[i])) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            try {
                m.setAccessible(true);
                return m.invoke(null, args);
            } catch (Throwable t) {
                lg(5, TAG, "callStaticMethod " + name + " 失败: " + t);
                return null;
            }
        }
        return null;
    }

    /** 反射构造实例（按构造器参数可赋值匹配） */
    static Object newInstance(Class<?> c, Object... args) {
        if (c == null) return null;
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            Class<?>[] pt = k.getParameterTypes();
            if (pt.length != args.length) continue;
            boolean ok = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) continue;
                Class<?> want = pt[i];
                if (want.isPrimitive()) want = box(want);
                if (!want.isInstance(args[i])) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            try {
                k.setAccessible(true);
                return k.newInstance(args);
            } catch (Throwable t) {
                lg(5, TAG, "newInstance " + c.getName() + " 失败: " + t);
                return null;
            }
        }
        return null;
    }

    /** 读取文件首行（sysfs 等），失败返回 null */
    static String readFirstLine(String path) {
        try {
            File f = new File(path);
            if (!f.isFile()) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            try {
                return r.readLine();
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ─────────────────────────── 状态 ───────────────────────────

    private static File statusDir() {
        File d = new File("/sdcard/Android/media/com.hyper.Camellia");
        try {
            if (!d.isDirectory()) d.mkdirs();
            if (d.isDirectory()) return d;
        } catch (Throwable ignored) {
        }
        if (sAi != null && sAi.dataDir != null) return new File(sAi.dataDir);
        return d;
    }

    private static void writeStatus(final int[] st) {
        sStat = st;
        // 状态文件位于 /sdcard（设置页需要跨 App 读取），但绝不能在启动关键路径上
        // 同步写：FUSE 阻塞同样会触发 "failed to complete startup" ANR。
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                writeStatusNow(st);
            }
        }, "camellia-status");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void writeStatusNow(int[] st) {
        int hooks = st.length > 0 ? st[0] : 0;
        int miss = st.length > 1 ? st[1] : 0;
        int fail = st.length > 2 ? st[2] : 0;
        int skip = st.length > 3 ? st[3] : 0;
        StringBuilder sb = new StringBuilder(2048);
        sb.append("proc=").append(sProc).append('\n');
        sb.append("via=").append(sVia).append('\n');
        sb.append("cfg=").append(sCfgSrc).append('\n');
        sb.append("on=").append(sOnCount).append('/').append(RULES.length + SPECIALS.length).append('\n');
        sb.append("cost=").append(sCostMs).append("ms\n");
        sb.append("stat=hooked=").append(hooks).append(" miss=").append(miss)
                .append(" fail=").append(fail).append(" skip=").append(skip).append('\n');
        sb.append("cache=").append(sCacheDiag).append('\n');
        sb.append("cachesave=").append(sCacheSave).append('\n');
        int natSame = 0, natDiff = 0, natPend = 0, natSkip = 0;
        for (Rule r : allRules()) {
            int ns = r.natState;
            if (ns == 1) natSame++;
            else if (ns == 2) natDiff++;
            else if (ns == 0 || ns == 4) natPend++;
            else natSkip++;
            sb.append("r=").append(r.id).append('|').append(r.group).append('|').append(r.label)
                    .append('|').append(r.state).append('|').append(r.hits)
                    .append('|').append(r.where)
                    .append("|n=").append(ns).append("|nv=").append(r.natVal).append('\n');
        }
        sb.append("native=same=").append(natSame).append(" diff=").append(natDiff)
                .append(" pend=").append(natPend).append(" skip=").append(natSkip).append('\n');
        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
        // 主文件：设置页按固定名读取
        File f = new File(statusDir(), STATUS_NAME);
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(data);
            out.flush();
            out.close();
        } catch (Throwable t) {
            lg(5, TAG, "写状态失败: " + t);
        }
        // 分进程副本：宿主有 :ui / :remote 等多个进程，同名文件会互相覆盖，
        // 「hook 装了但 hits=0」就无法区分是哪个进程的状态。
        String tag = sProc == null ? "app" : sProc.replace(':', '_');
        if (tag.isEmpty()) tag = "app";
        try {
            FileOutputStream out = new FileOutputStream(
                    new File(statusDir(), STATUS_NAME + "." + tag));
            out.write(data);
            out.flush();
            out.close();
        } catch (Throwable ignored) {
        }
    }

    /** 隐私防护：域名黑名单命中判定（含子域，忽略大小写） */
    private static boolean hitHost(String h, String[] hs) {
        if (h == null || hs == null) {
            return false;
        }
        String s = h.toLowerCase(java.util.Locale.ROOT);
        for (String t : hs) {
            if (t == null || t.isEmpty()) {
                continue;
            }
            String u = t.toLowerCase(java.util.Locale.ROOT);
            if (s.equals(u) || s.endsWith("." + u)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 隐私防护：拦截安全组件域名解析。
     *
     * <p>命中把域名解析成 127.0.0.1（getByName）或空数组（getAllByName），
     * 让上层的上报 / 查毒 / 日志上传请求在 DNS 阶段就失败；未命中照常走原流程。
     * 域名只在方法入口拆一次，getByName 是进程内超高频调用，回调里不再做任何字符串拆分。</p>
     */
    static int spPvNetSec(final Rule r) {
        final String[] hs = (r.anchor == null || r.anchor.isEmpty())
                ? new String[0] : r.anchor.split("\\|");
        if (hs.length == 0) {
            return 0;
        }
        int n = 0;
        SpAction one = new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rr) throws Throwable {
                String h = String.valueOf(chain.getArg(0));
                if (!hitHost(h, hs)) {
                    return chain.proceed();
                }
                return java.net.InetAddress.getByAddress(h, new byte[]{127, 0, 0, 1});
            }
        };
        SpAction all = new SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Rule rr) throws Throwable {
                String h = String.valueOf(chain.getArg(0));
                if (!hitHost(h, hs)) {
                    return chain.proceed();
                }
                return new java.net.InetAddress[0];
            }
        };
        try {
            if (hookMethod(r, java.net.InetAddress.class.getMethod("getByName", String.class), one)) {
                n++;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (hookMethod(r, java.net.InetAddress.class.getMethod("getAllByName", String.class), all)) {
                n++;
            }
        } catch (Throwable ignored) {
        }
        return n;
    }
}
