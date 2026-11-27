package com.hyper.Camellia;

import java.lang.reflect.Method;
import java.util.List;
import io.github.libxposed.api.XposedInterface;

/** 以用户提供的 DEX 差异为依据，保留原调用中的初始化与异常语义。 */
final class SecurityDiffFeatures {
    private SecurityDiffFeatures() {}

    private static final Unlock.SpAction AFTER_TRUE = new Unlock.SpAction() {
        @Override public Object run(XposedInterface.Chain chain, Unlock.Rule rule) throws Throwable {
            chain.proceed();
            return Boolean.TRUE;
        }
    };

    private static int installDefault(Unlock.Rule rule, Unlock.Sp sp, String key) {
        return installDefault(rule, sp, key, null);
    }

    private static int installDefault(Unlock.Rule rule, Unlock.Sp sp, String key, String owner) {
        List<Method> parents = sp.ms(key, null, null, null);
        if (owner != null) {
            parents = new java.util.ArrayList<>(parents);
            final String expectedOwner = owner;
            parents.removeIf(method -> !method.getDeclaringClass().getName().equals(expectedOwner));
        }
        if (parents.isEmpty()) return 0;
        String cacheKey = "sc-default-reader:" + key;
        List<Method> readers = sp.resolvedM.get(cacheKey);
        if (readers == null && sp.bridge != null) {
            readers = new java.util.ArrayList<>();
            for (org.luckypray.dexkit.result.MethodData parent : sp.q(key, null, null)) {
                for (org.luckypray.dexkit.result.MethodData called : parent.getInvokes()) {
                    List<String> types = called.getParamTypeNames();
                    if (!"boolean".equals(called.getReturnTypeName())) continue;
                    if (!(types.equals(java.util.Arrays.asList("java.lang.String", "boolean"))
                            || types.equals(java.util.Arrays.asList("java.lang.String", "java.lang.String", "boolean")))) continue;
                    try {
                        Method reader = called.getMethodInstance(parents.get(0).getDeclaringClass().getClassLoader());
                        if (!readers.contains(reader)) readers.add(reader);
                    } catch (Throwable ignored) { }
                }
            }
            sp.resolvedM.put(cacheKey, readers);
        }
        if (parents.isEmpty() || readers == null || readers.isEmpty()) return 0;
        final boolean hdr = "support_displayfeature_gamemode_HDR".equals(key);
        final ThreadLocal<Boolean> active = new ThreadLocal<>();
        // 0=未读取/异常，1=返回 false，2=返回 true。
        final ThreadLocal<int[]> hdrRead = new ThreadLocal<>();
        List<Method> combined = sp.resolvedM.get("sc-hdr-combined");
        if (hdr && combined == null && sp.bridge != null) {
            combined = new java.util.ArrayList<>();
            for (org.luckypray.dexkit.result.MethodData data : sp.q(key, null, null)) {
                boolean hasCapabilityCall = false;
                for (org.luckypray.dexkit.result.MethodData call : data.getInvokes()) {
                    if ("boolean".equals(call.getReturnTypeName()) && call.getParamCount() == 0) hasCapabilityCall = true;
                }
                if (hasCapabilityCall) {
                    try { combined.add(data.getMethodInstance(parents.get(0).getDeclaringClass().getClassLoader())); }
                    catch (Throwable ignored) { }
                }
            }
            sp.resolvedM.put("sc-hdr-combined", combined);
        }
        final List<Method> combinedMethods = combined;
        int hooked = 0;
        for (Method reader : readers) {
            if (Unlock.hookMethod(rule, reader, (chain, r) -> {
                if (!Boolean.TRUE.equals(active.get())) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                int index = args.length - 2;
                if (index < 0 || !key.equals(args[index])) return chain.proceed();
                args[args.length - 1] = Boolean.TRUE;
                try {
                    Object result = chain.proceed(args);
                    int[] marker = hdrRead.get();
                    if (hdr && marker != null) marker[0] = Boolean.TRUE.equals(result) ? 2 : 1;
                    return result;
                } catch (Throwable error) {
                    int[] marker = hdrRead.get();
                    if (hdr && marker != null) marker[0] = -1;
                    throw error;
                }
            })) hooked++;
        }
        if (hooked == 0) return 0;
        int entries = 0;
        for (Method parent : parents) {
            if (parent.getReturnType() != boolean.class || parent.getParameterCount() != 0) continue;
            if (Unlock.hookMethod(rule, parent, (chain, r) -> {
                Boolean previous = active.get();
                int[] previousRead = hdrRead.get();
                int[] marker = new int[1];
                active.set(Boolean.TRUE);
                if (hdr) hdrRead.set(marker);
                try {
                    Object result = chain.proceed();
                    if (hdr && (marker[0] == -1 || (marker[0] == 1
                            && combinedMethods != null && combinedMethods.contains(parent)))) return Boolean.TRUE;
                    return result;
                } finally {
                    if (previous == null) active.remove(); else active.set(previous);
                    if (hdr) { if (previousRead == null) hdrRead.remove(); else hdrRead.set(previousRead); }
                }
            })) entries++;
        }
        return entries;
    }

    private static int installVisionDefaults(Unlock.Rule rule, Unlock.Sp sp) {
        String anchor = "visionEnhance - isSupportDualDPU = ";
        Class<?> target = sp.cls(anchor);
        if (target == null) return 0;
        String cacheKey = "sc-vision-initializer-readers";
        List<Method> readers = sp.resolvedM.get(cacheKey);
        if (readers == null && sp.bridge != null) {
            readers = new java.util.ArrayList<>();
            for (org.luckypray.dexkit.result.MethodData initializer : sp.q(anchor, null, null)) {
                if (!initializer.isStaticInitializer()) continue;
                for (org.luckypray.dexkit.result.MethodData called : initializer.getInvokes()) {
                    boolean bool = "boolean".equals(called.getReturnTypeName());
                    boolean integer = "int".equals(called.getReturnTypeName());
                    if (!(bool || integer) || !called.getParamTypeNames().equals(
                            java.util.Arrays.asList("java.lang.String", bool ? "boolean" : "int"))) continue;
                    try {
                        Method reader = called.getMethodInstance(target.getClassLoader());
                        if (!readers.contains(reader)) readers.add(reader);
                    } catch (Throwable ignored) { }
                }
            }
            sp.resolvedM.put(cacheKey, readers);
        }
        if (readers == null || readers.size() != 2) return 0;
        final java.util.Set<String> keys = new java.util.HashSet<>(java.util.Arrays.asList(
                "ro.vendor.display.hyperos.miDualDPU_support",
                "ro.vendor.display.hyperos.miDualDPU_gamebox_version",
                "ro.vendor.xiaomi.sr.support", "ro.vendor.gpp.frc.support", "ro.vendor.display.iris_x7.support"));
        final ThreadLocal<Boolean> active = new ThreadLocal<>();
        for (Method reader : readers) {
            final Object defaultValue = reader.getReturnType() == boolean.class ? Boolean.TRUE : Integer.valueOf(1);
            if (!Unlock.hookMethod(rule, reader, (chain, r) -> {
                if (!Boolean.TRUE.equals(active.get())) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                if (args.length != 2 || !keys.contains(args[0])) return chain.proceed();
                args[1] = defaultValue;
                return chain.proceed(args);
            })) return 0;
        }
        if (!Unlock.hookInitializer(rule, target, (chain, r) -> {
            Boolean previous = active.get();
            active.set(Boolean.TRUE);
            try { return chain.proceed(); }
            finally { if (previous == null) active.remove(); else active.set(previous); }
        })) return 0;
        return 1;
    }

    // === 视频工具箱「识别 BGM」→ 网易云音乐听歌识曲 ===
    //
    // 原链路（手机管家 13.6.5 实测）：
    //   kb.h#onClick(View)  [视频工具箱菜单项 5 = 识别 BGM]
    //     -> mb.o#a()                      构造 Intent(ACTION_VIEW, "miui-music://radar?miref=VideoToolBox")
    //     -> h0#Y(Context, Intent, "com.miui.player")
    //     -> h0#Z(Context, Intent, pkg, -1) 加 FLAG_ACTIVITY_NEW_TASK
    //     -> h0#c(Context, pkg)             MiuiMultiWindowUtils 取小窗 ActivityOptions
    //     -> Context#startActivity(intent, options)
    // 也就是说「自动小窗」就是这条链本身自带的 ActivityOptions 行为。
    //
    // 接管点选 h0#Y（单点、直接来自 onClick）：只替换 Intent 与 pkg 两个参数，
    // 下游 Z/c 的 NEW_TASK 与小窗逻辑全部沿用，所以网易云同样以小窗弹出。
    private static final String NETEASE_PKG = "com.netease.cloudmusic";
    /** 网易云导出的 RedirectActivity 承接的 deeplink，实测直达听歌识曲 IdentifyActivityV2。 */
    private static final String NETEASE_IDENTIFY_URI = "orpheus://identify";
    /** 视频工具箱 BGM 入口原本构造的 Intent（唯一字符串锚点）。 */
    private static final String BGM_RADAR_URI = "miui-music://radar";
    /** 网易云：承接 orpheus:// 等 deeplink 的 exported 中转页（deeplink 隐式解析失败时显式拉起）。 */
    private static final String NETEASE_REDIRECT_ACTIVITY = "com.netease.cloudmusic.activity.RedirectActivity";
    /** 酷狗音乐 / 极速版：共用的「快捷进入」入口组件与 action（manifest 实证 exported=true）。 */
    private static final String KUGOU_PKG = "com.kugou.android";
    private static final String KUGOU_LITE_PKG = "com.kugou.android.lite";
    private static final String KUGOU_SHORTCUT_ACTIVITY = "com.kugou.android.app.splash.MHShortcutEntryActivity";
    private static final String KUGOU_SHORTCUT_ACTION = "kugou.intent.action.SHORTCUT";
    private static final String KUGOU_SHORTCUT_URI = "kugousc://hunter";
    /** QQ 音乐：识曲入口（.recognizekt 为 dex 里的实际实现，.recognize 为其门面）。 */
    private static final String QQ_PKG = "com.tencent.qqmusic";
    private static final String QQ_RECOGNIZE_ACTION = "com.tencent.qqmusic.recognizer";
    private static final String QQ_RECOGNIZE_ACTIVITY = "com.tencent.qqmusic.recognizekt.RecognizeActivity";
    private static final String QQ_RECOGNIZE_ACTIVITY_ALT = "com.tencent.qqmusic.recognize.RecognizeActivity";

    private static int installBgmApp(Unlock.Rule rule, Unlock.Sp sp) {
        if (sp == null) return 0;
        String cacheKey = "sc-video-bgm-launcher";
        List<Method> launchers = sp.resolvedM == null ? null : sp.resolvedM.get(cacheKey);
        if (launchers == null && sp.bridge != null) {
            // 目标形状：void Y(Context, Intent, String)
            Unlock.Rule shape = new Unlock.Rule("", "", "", 0, null).ret("V")
                    .params("Landroid/content/Context;", "Landroid/content/Intent;", "Ljava/lang/String;");
            List<Method> strict = new java.util.ArrayList<>();
            List<Method> loose = new java.util.ArrayList<>();
            for (org.luckypray.dexkit.result.MethodData radar : sp.q(BGM_RADAR_URI, null, null)) {
                // 点击处理器：onClick(View)，实例方法。
                for (org.luckypray.dexkit.result.MethodData click : radar.getCallers()) {
                    if (!click.getParamTypeNames().equals(java.util.Arrays.asList("android.view.View"))) continue;
                    for (org.luckypray.dexkit.result.MethodData call : click.getInvokes()) {
                        if (!"void".equals(call.getReturnTypeName())) continue;
                        if (!call.getParamTypeNames().equals(java.util.Arrays.asList(
                                "android.content.Context", "android.content.Intent", "java.lang.String"))) continue;
                        // 只借 DexKit 得到「类名 + 方法名」，反射交给 sCl，避免自己拿 ClassLoader。
                        Method launcher = sp.reflectOne(Unlock.dot(call.getDeclaredClassName()),
                                call.getName(), shape);
                        if (launcher == null || Unlock.contains(loose, launcher)) continue;
                        loose.add(launcher);
                        // 再向下确认一层 Y(...)->Z(...,int)，命中才说明是「带小窗选项的跳转入口」。
                        for (org.luckypray.dexkit.result.MethodData inner : call.getInvokes()) {
                            if (!"void".equals(inner.getReturnTypeName())) continue;
                            if (!inner.getParamTypeNames().equals(java.util.Arrays.asList(
                                    "android.content.Context", "android.content.Intent",
                                    "java.lang.String", "int"))) continue;
                            if (!Unlock.contains(strict, launcher)) strict.add(launcher);
                            break;
                        }
                    }
                }
            }
            // 严格链失配时退回宽松候选（信号不变，仅多挂一层无害的跳转入口）。
            launchers = strict.isEmpty() ? loose : strict;
            if (sp.resolvedM != null) sp.resolvedM.put(cacheKey, launchers);
        }
        if (launchers == null || launchers.isEmpty()) return 0;
        int hooked = 0;
        for (Method launcher : launchers) {
            final ClassLoader loader = launcher.getDeclaringClass().getClassLoader();
            if (Unlock.hookMethod(rule, launcher, (chain, r) -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length != 3 || !(args[0] instanceof android.content.Context)
                        || !(args[1] instanceof android.content.Intent)) return chain.proceed();
                android.content.Context ctx = (android.content.Context) args[0];
                android.content.Intent source = (android.content.Intent) args[1];
                android.net.Uri data = source.getData();
                // 只接管视频工具箱的 BGM 雷达跳转；投屏／设置等其它跳转原样放行。
                if (data == null || !"miui-music".equals(data.getScheme())
                        || !"radar".equals(data.getHost())) return chain.proceed();
                // 点击时才读当前选择：设置页改完「识曲 App」后无需重启手机管家即可生效。
                String app = Unlock.bgmApp();
                if (Unlock.BGM_APP_MIUI.equals(app)) return chain.proceed(args);
                java.util.List<android.content.Intent> queue = bgmIntentQueue(app);
                if (queue.isEmpty()) return chain.proceed(args);
                Throwable last = null;
                for (android.content.Intent target : queue) {
                    String pkg = target.getPackage();
                    // setClassName 构造的 Intent 只写 ComponentName、不写包名字段，
                    // getPackage() 会返回 null。漏掉这个回退，「取小窗 options」会被整段
                    // 跳过 -> 静默全屏启动，这正是酷狗 / QQ 音乐点「识别 BGM」变全屏的根因。
                    if (pkg == null && target.getComponent() != null) {
                        pkg = target.getComponent().getPackageName();
                    }
                    // 不预检「是否已安装」：手机管家进程的包可见性不可靠，误判会让候选被静默跳过。
                    // 未安装/未导出/不认该 deeplink 都会抛异常，下面 catch 后继续试下一个候选。
                    target.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        // 自己取小窗 ActivityOptions 再启动：原 h0#Z 在 options 为 null 时直接 return，
                        // 会「点了没反应」，所以不能只替换 Intent 后继续走原方法。
                        android.os.Bundle options = pkg == null ? null : smallWindowOptions(loader, ctx, pkg);
                        if (options != null) ctx.startActivity(target, options);
                        else ctx.startActivity(target);
                        android.util.Log.i("CamelliaBgm", "bgm -> " + app
                                + " smallWindow=" + (options != null)
                                + " " + describeBundle(options)
                                + " target=" + target);
                        return null; // 已完成接管，不再执行「打开小米音乐」原逻辑
                    } catch (Throwable error) {
                        last = error; // 不认该 deeplink／被系统拦截：试下一个候选
                    }
                }
                // 全部候选都失败：回退原小米音乐流程，保证「点了有反应」。
                android.util.Log.w("CamelliaBgm", "bgm redirect failed: " + app, last);
                return chain.proceed(args);
            })) hooked++;
        }
        return hooked;
    }

    /**
     * 当前选择对应的识曲入口候选（首个启动成功即停）。
     *
     * <p>各 App 的入口来源都是「manifest + 实机 am start」双重实证：
     * 网易云 orpheus://identify 直达 IdentifyActivityV2（RedirectActivity 承接，exported）；
     * 酷狗 MHShortcutEntryActivity（exported、launchMode=singleTask、filter 含 VIEW+BROWSABLE）；
     * QQ 音乐 .recognizekt.RecognizeActivity（exported、filter 含 com.tencent.qqmusic.recognizer）。</p>
     */
    private static java.util.List<android.content.Intent> bgmIntentQueue(String app) {
        java.util.List<android.content.Intent> out = new java.util.ArrayList<android.content.Intent>();
        if (Unlock.BGM_APP_NETEASE.equals(app)) {
            android.content.Intent deep = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(NETEASE_IDENTIFY_URI));
            deep.setPackage(NETEASE_PKG);
            out.add(deep);
            android.content.Intent alt = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(NETEASE_IDENTIFY_URI));
            alt.setClassName(NETEASE_PKG, NETEASE_REDIRECT_ACTIVITY);
            out.add(alt);
        } else if (Unlock.BGM_APP_KUGOU.equals(app) || Unlock.BGM_APP_KUGOU_LITE.equals(app)) {
            String pkg = Unlock.BGM_APP_KUGOU_LITE.equals(app) ? KUGOU_LITE_PKG : KUGOU_PKG;
            android.content.Intent direct = new android.content.Intent(KUGOU_SHORTCUT_ACTION,
                    android.net.Uri.parse(KUGOU_SHORTCUT_URI));
            direct.setClassName(pkg, KUGOU_SHORTCUT_ACTIVITY);
            out.add(direct);
            // 兜底：只给 scheme，让系统按 manifest 的 VIEW+BROWSABLE filter 自行解析。
            android.content.Intent resolved = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(KUGOU_SHORTCUT_URI));
            resolved.setPackage(pkg);
            out.add(resolved);
        } else if (Unlock.BGM_APP_QQ.equals(app)) {
            android.content.Intent direct = new android.content.Intent(QQ_RECOGNIZE_ACTION);
            direct.setClassName(QQ_PKG, QQ_RECOGNIZE_ACTIVITY);
            out.add(direct);
            android.content.Intent alt = new android.content.Intent(QQ_RECOGNIZE_ACTION);
            alt.setClassName(QQ_PKG, QQ_RECOGNIZE_ACTIVITY_ALT);
            out.add(alt);
            // 兜底：只给 action+包名，交给 manifest 的 com.tencent.qqmusic.recognizer filter 解析。
            android.content.Intent resolved = new android.content.Intent(QQ_RECOGNIZE_ACTION);
            resolved.setPackage(QQ_PKG);
            out.add(resolved);
        }
        return out;
    }

    /** WINDOWING_MODE_FREEFORM：小窗（freeform）窗口模式，MIUI 的「小窗」走的就是它。 */
    private static final int WINDOWING_MODE_FREEFORM = 5;

    /**
     * ActivityOptions 里「窗口模式」真正的 bundle 键名。
     *
     * <p>本机 Android 17 实测（app_process 里调用 {@code setLaunchWindowingMode(5)} 后对比
     * bundle 键集合）：写进去的键是 {@code android.activity.windowingMode}；老版本猜的
     * {@code android:activity.launchWindowingMode} 在 framework.jar 里已经不存在了。
     * 直接 {@code putInt} 这个键等价于 {@code setLaunchWindowingMode}，且是公开 API，
     * 不会被隐藏 API 策略拦。</p>
     */
    private static final String KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode";

    /** AOSP 实测键名：小窗启动边界（值类型 {@code android.graphics.Rect}）。 */
    private static final String KEY_LAUNCH_BOUNDS = "android:activity.launchBounds";

    /** MIUI 私有注入键（值类型 {@code ActivityOptionsInjector}，含 freeformScale）。 */
    private static final String KEY_MIUI_INJECTOR = "miui:activity.options.injector";

    /**
     * MIUI 小窗真正的开关值（注入器里的 freeformScale）。
     *
     * <p>app_process 探针实测：{@code makeBasic()} 给的注入器是 {@code freeformScale:-1.0}
     * + {@code mIsNormalFreeForm:true}（= 全屏），而手机管家给网易云的那份是
     * {@code freeformScale:0.7} —— 能不能进小窗就差这一个字段。</p>
     */
    private static final float FREEFORM_SCALE_SMALL_WINDOW = 0.7f;

    /**
     * 取「小窗」ActivityOptions（手机管家 h0#c 同款链路）。
     *
     * <p>顺序：MIUI 官方入口 {@code android.util.MiuiMultiWindowUtils#getActivityOptions}
     * → 就地改注入器的 freeformScale（本机实测唯一有效的小窗开关）→ 借网易云的壳
     * → AOSP 窗口模式键兜底。返回的 bundle 一定带 {@code freeformScale > 0} 或窗口模式 5，
     * 不会悄悄退回全屏。</p>
     */
    static android.os.Bundle smallWindowOptions(ClassLoader loader,
                                                       android.content.Context ctx, String pkg) {
        android.os.Bundle options = miuiSmallWindowOptions(loader, ctx, pkg);
        // 1) 手机管家那条链路自己就给了小窗形状（本机：网易云）→ 直接用。
        if (hasSmallFreeform(options)) return options;
        // 2) 借壳优先：网易云那份 bundle 与目标包无关（差别只在注入器 freeformScale=0.7），
        //    是本机唯一「实测确实能进小窗」的形状。MIUI 只给它认可小窗的包发这种形状，对
        //    酷狗 / 酷狗极速版 / QQ 音乐要么抛异常、要么给 freeformScale=-1 的全屏形状，
        //    所以不赌「就地改 scale 是否等效」，先借现成的。
        if (!NETEASE_PKG.equals(pkg)) {
            android.os.Bundle donor = miuiSmallWindowOptions(loader, ctx, NETEASE_PKG);
            if (hasSmallFreeform(donor)) {
                android.util.Log.i("CamelliaBgm", "borrow small window options from "
                        + NETEASE_PKG + " for " + pkg + " " + describeBundle(donor));
                return donor;
            }
        }
        // 3) 借不到 donor 时退而求其次：把手机管家给的 options 就地改成小窗形状
        //    （freeformScale=0.7 + 补齐边界）—— MIUI 小窗真正的开关，见 patchSmallWindow 注释。
        android.os.Bundle patched = patchSmallWindow(ctx, pkg, options);
        if (hasSmallFreeform(patched)) return patched;
        // 4) 最后兜底：AOSP 公开键（窗口模式 5 + 边界）。这条分支要求 Activity 声明
        //    supportsFreeform，成功率低，但好过悄悄全屏。
        return patchAospWindowingMode(ctx, pkg, patched != null ? patched : options);
    }

    /**
     * 调 MIUI 自己的入口（手机管家 h0#c 就是这么取的）。
     *
     * <p>按「声明方法名 + 参数形状」扫描而不是精确签名：不同 MIUI 版本的重载表不一样，
     * 参数少的优先，首选 (Context,String,boolean) —— 与手机管家完全一致。</p>
     */
    private static android.os.Bundle miuiSmallWindowOptions(ClassLoader loader,
                                                           android.content.Context ctx, String pkg) {
        Class<?> utils;
        try {
            utils = Class.forName("android.util.MiuiMultiWindowUtils", false, loader);
        } catch (Throwable error) {
            android.util.Log.w("CamelliaBgm", "MiuiMultiWindowUtils not found", error);
            return null;
        }
        java.util.List<Method> candidates = new java.util.ArrayList<Method>();
        for (Method method : utils.getDeclaredMethods()) {
            if (!"getActivityOptions".equals(method.getName())) continue;
            if (!android.app.ActivityOptions.class.equals(method.getReturnType())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 2 || types[0] != android.content.Context.class
                    || types[1] != String.class) continue;
            boolean usable = true;
            for (int i = 2; i < types.length; i++) {
                if (types[i] != boolean.class && types[i] != int.class) usable = false;
            }
            if (usable) candidates.add(method);
        }
        java.util.Collections.sort(candidates, new java.util.Comparator<Method>() {
            @Override
            public int compare(Method a, Method b) {
                return a.getParameterTypes().length - b.getParameterTypes().length;
            }
        });
        if (candidates.isEmpty()) {
            android.util.Log.w("CamelliaBgm",
                    "MiuiMultiWindowUtils.getActivityOptions not found for " + pkg);
        }
        android.os.Bundle fallback = null;
        for (Method method : candidates) {
            Class<?>[] types = method.getParameterTypes();
            Object[] args = new Object[types.length];
            args[0] = ctx;
            args[1] = pkg;
            for (int i = 2; i < types.length; i++) {
                // 第 3 参是 noCheck（手机管家传 true）；更靠后的 boolean 一般是
                // isDesktopModeActive，只在桌面模式下才为 true，这里与实测保持一致传 false。
                args[i] = types[i] == boolean.class
                        ? Boolean.valueOf(i == 2) : Integer.valueOf(-1);
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(null, args);
                if (value instanceof android.app.ActivityOptions) {
                    android.os.Bundle bundle =
                            ((android.app.ActivityOptions) value).toBundle();
                    android.util.Log.i("CamelliaBgm", "miui helper pkg=" + pkg + " args="
                            + types.length + " " + describeBundle(bundle));
                    // 重载之间有差异（有的分支不写窗口模式），优先取真正带小窗模式的那份。
                    if (isSmallWindowBundle(bundle)) return bundle;
                    if (fallback == null) fallback = bundle;
                }
            } catch (Throwable error) {
                // MIUI 对「不在它小窗白名单里」的包可能直接抛异常；静默吞掉会让排查无线索。
                android.util.Log.w("CamelliaBgm", "miui helper failed pkg=" + pkg
                        + " args=" + types.length + " err=" + error);
            }
        }
        return fallback;
    }

    /**
     * 把一份 ActivityOptions bundle 就地改成「MIUI 小窗」形状。
     *
     * <p>取证（app_process 探针，本机 Android 17 / HyperOS 4）：MIUI 在小窗链路上给
     * {@code ActivityOptions} 注入一个私有对象 {@code
     * android.app.ActivityOptions$ActivityOptionsInjector}（bundle 键
     * {@code miui:activity.options.injector}），它只有两个字段：{@code float mFreeformScale}
     * 与 {@code boolean mIsNormalFreeForm}，配套 {@code setFreeformScale(float)} /
     * {@code getFreeformScale()}。{@code makeBasic()} 默认 {@code -1.0}（全屏），手机管家给
     * 网易云的那份是 {@code 0.7}。注入器对象与 options 是同一个引用，改完直接启动即生效。</p>
     *
     * <p>相比之下 {@code android.activity.windowingMode=5} 是 AOSP 的自由窗口，MIUI 把它当
     * 两码事（MIUI 自己给的小窗 bundle 里没有这个键），而且 AOSP 分支要求 Activity 声明
     * {@code supportsFreeform}，多数第三方 App 没有 → 静默退回全屏。这正是酷狗 / 酷狗极速版 /
     * QQ 音乐点「识别 BGM」变全屏、而网易云正常小窗的直接原因。</p>
     */
    private static android.os.Bundle patchSmallWindow(android.content.Context ctx, String pkg,
                                                    android.os.Bundle source) {
        if (source == null) return null;
        Object injector = source.get(KEY_MIUI_INJECTOR);
        if (injector == null) {
            injector = freshInjector();
            if (injector instanceof android.os.Parcelable) {
                source.putParcelable(KEY_MIUI_INJECTOR, (android.os.Parcelable) injector);
            }
        }
        float scale = setFreeformScale(injector, FREEFORM_SCALE_SMALL_WINDOW);
        if (!(source.get(KEY_LAUNCH_BOUNDS) instanceof android.graphics.Rect)) {
            // 没有边界的小窗会落到屏幕外（实测无边界时越界）。
            android.graphics.Rect bounds = defaultSmallWindowBounds(ctx);
            if (bounds != null) source.putParcelable(KEY_LAUNCH_BOUNDS, bounds);
        }
        android.util.Log.i("CamelliaBgm", "patch small window for " + pkg + " scale=" + scale
                + " " + describeBundle(source));
        logHiddenApiProbe();
        return source;
    }

    /** 兜底：直写 AOSP 公开的窗口模式键（+ 边界）。 */
    private static android.os.Bundle patchAospWindowingMode(android.content.Context ctx, String pkg,
                                                          android.os.Bundle source) {
        if (source == null) {
            try {
                source = android.app.ActivityOptions.makeBasic().toBundle();
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (source.getInt(KEY_LAUNCH_WINDOWING_MODE, -1) != WINDOWING_MODE_FREEFORM) {
            source.putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FREEFORM);
        }
        if (!(source.get(KEY_LAUNCH_BOUNDS) instanceof android.graphics.Rect)) {
            android.graphics.Rect bounds = defaultSmallWindowBounds(ctx);
            if (bounds != null) source.putParcelable(KEY_LAUNCH_BOUNDS, bounds);
        }
        android.util.Log.i("CamelliaBgm", "aosp windowing mode fallback for " + pkg + " "
                + describeBundle(source));
        return source;
    }

    /** 从 MIUI 自己造的 options 里取一份空注入器（正常链路用不到，极端情况兜底）。 */
    private static Object freshInjector() {
        try {
            return android.app.ActivityOptions.makeBasic().toBundle().get(KEY_MIUI_INJECTOR);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 改注入器的 freeformScale，返回改后的值（&lt;= 0 表示没改成）。
     *
     * <p>只用注入器对象自己的类做反射，不碰 {@code ActivityOptions} 上的 @hide 方法：后者在本
     * 模块进程里会被隐藏 API 策略拦掉、异常还被吞，是「静默全屏」的老坑。</p>
     */
    private static float setFreeformScale(Object injector, float scale) {
        if (injector == null) return -1f;
        Class<?> type = injector.getClass();
        try {
            Method setter = type.getMethod("setFreeformScale", float.class);
            setter.setAccessible(true);
            setter.invoke(injector, Float.valueOf(scale));
        } catch (Throwable first) {
            try {
                java.lang.reflect.Field field = type.getField("mFreeformScale");
                field.setAccessible(true);
                field.setFloat(injector, scale);
            } catch (Throwable second) {
                android.util.Log.w("CamelliaBgm", "setFreeformScale failed: " + first
                        + " / " + second);
                return -1f;
            }
        }
        return freeformScale(injector);
    }

    /** 诊断用：本进程里隐藏 API 反射到底通不通（决定必须走 bundle 直写而不是反射）。 */
    private static void logHiddenApiProbe() {
        try {
            android.app.ActivityOptions probe = android.app.ActivityOptions.makeBasic();
            Method getter = android.app.ActivityOptions.class.getMethod("getLaunchWindowingMode");
            getter.setAccessible(true);
            android.util.Log.i("CamelliaBgm", "hidden api ok getLaunchWindowingMode="
                    + getter.invoke(probe));
        } catch (Throwable error) {
            android.util.Log.w("CamelliaBgm", "hidden api blocked: " + error);
        }
    }

    /**
     * 这份 bundle 看起来是不是「MIUI 小窗」：注入器 freeformScale &gt; 0，或 AOSP 窗口模式 5。
     */
    private static boolean isSmallWindowBundle(android.os.Bundle bundle) {
        if (hasSmallFreeform(bundle)) return true;
        return bundle != null
                && bundle.getInt(KEY_LAUNCH_WINDOWING_MODE, -1) == WINDOWING_MODE_FREEFORM;
    }

    /** 只有 MIUI 小窗那一份注入器才带 freeformScale &gt; 0（默认 -1 = 全屏）。 */
    private static boolean hasSmallFreeform(android.os.Bundle bundle) {
        return bundle != null && freeformScale(bundle.get(KEY_MIUI_INJECTOR)) > 0f;
    }

    /**
     * 读注入器的 freeformScale：先反射取值；被隐藏 API 策略拦掉时退回解析 {@code toString()}。
     *
     * <p>探针实测该内部类的 toString 形如
     * {@code ActivityOptionsInjector{freeformScale: -1.0, mIsNormalFreeForm: true}}，
     * 所以文本兜底是可靠的。</p>
     */
    private static float freeformScale(Object injector) {
        if (injector == null) return -1f;
        try {
            Method getter = injector.getClass().getMethod("getFreeformScale");
            getter.setAccessible(true);
            Object value = getter.invoke(injector);
            if (value instanceof Float) return ((Float) value).floatValue();
        } catch (Throwable ignored) {
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("freeformScale:\\s*(-?[0-9.]+)").matcher(String.valueOf(injector));
        if (!matcher.find()) return -1f;
        try {
            return Float.parseFloat(matcher.group(1));
        } catch (Throwable ignored) {
            return -1f;
        }
    }

    /** 一行摘要，实机排查用（bundle 里的值都是 Parcelable，toString 够用）。 */
    private static String describeBundle(android.os.Bundle bundle) {
        if (bundle == null) return "bundle=";
        return "windowingMode=" + bundle.getInt(KEY_LAUNCH_WINDOWING_MODE, -1)
                + " bounds=" + bundle.get(KEY_LAUNCH_BOUNDS)
                + " injector=" + bundle.get(KEY_MIUI_INJECTOR);
    }

    /** MIUI 实测的小窗边界比例（本机 1200x2608 屏上实测 [180,284][1380,2204]）。 */
    private static android.graphics.Rect defaultSmallWindowBounds(android.content.Context ctx) {
        try {
            android.util.DisplayMetrics metrics = ctx.getResources().getDisplayMetrics();
            if (metrics == null || metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
                return null;
            }
            int left = (int) (metrics.widthPixels * 0.15f);
            int top = (int) (metrics.heightPixels * 0.109f);
            return new android.graphics.Rect(left, top, left + metrics.widthPixels,
                    top + (int) (metrics.heightPixels * 0.736f));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 解除「识别 BGM」菜单项置灰（可见但点不动）。
     *
     * <p>菜单项的使能由 {@code mb.o#c(Context)} 决定，而它第一步就查小米音乐
     * {@code com.miui.player} 的版本（本机未装 → false）。手机管家里有两条独立调用：
     * {@code lib.i#m(Context,ZZ)} 用它决定「是否把该项加进菜单」（可见性），
     * {@code kb.h#d()} 用它决定「该项是否可点」（灰色/使能）。</p>
     *
     * <p>Camellia 原有 {@code video_bgm} 规则只在菜单构建期（ThreadLocal 生效域内）
     * 放行该方法，渲染期 {@code kb.h#d()} 直接调用同一方法拿到的仍是 false，
     * 于是「可见但置灰」。这里以方法体内出现 {@code music_bgm_status} 为锚点，
     * 取该锚点方法的调用者中形状为 {@code static boolean(Context)} 的判定方法，
     * 直接恒 true —— 可见性与可点性一并解决。</p>
     */
    private static int installBgmEnabled(Unlock.Rule rule, Unlock.Sp sp, String anchor) {
        int hooked = 0;
        // 解析与缓存回填统一在 Unlock.bgmGates 里完成（缓存命中路径 bridge == null 时
        // 直接取 resolvedM，绝不 NPE）。
        for (Method gate : Unlock.bgmGates(sp, anchor)) {
            if (Unlock.hookMethod(rule, gate, AFTER_TRUE)) hooked++;
        }
        return hooked;
    }

    /**
     * 游戏包名判定（知识库标注：s1#l(String)）——「3s 极速启动」门控链里的游戏名单判断。
     *
     * <p>原始实现只对 {@code com.tencent.tmgp.sgame} / {@code com.tencent.tmgp.pubgmhd}
     * 放行，作者把它改成恒 true。这里以「方法体内出现游戏包名字符串」为锚点定位，
     * 不写死类名 / 方法名，只接受 {@code boolean(String)} 形态，
     * 因此类名混淆或版本升级后依然可用，也不会误 hook 到同名但形状不同的方法。
     */
    private static int installGamePackagePredicate(Unlock.Rule rule, Unlock.Sp sp, String anchor) {
        int n = 0;
        for (Method method : sp.ms(anchor, null, null, null)) {
            if (method.getReturnType() != boolean.class
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != String.class) continue;
            if (Unlock.hookMethod(rule, method, AFTER_TRUE)) n++;
        }
        return n;
    }

    static int install(Unlock.Rule rule, Unlock.Sp sp) {
        if (sp == null) return 0;
        int count = 0;
        if ("cooling_fan".equals(rule.id)) {
            List<Method> methods = sp.ms("fan_support", null, null, null);
            for (Method method : methods) {
                if (method.getReturnType() == boolean.class && method.getParameterCount() == 0
                        && Unlock.hookMethod(rule, method, AFTER_TRUE)) count++;
            }
        } else if ("game_quick_start".equals(rule.id)) {
            // A13「3s 极速启动」：知识库唯一改动点 = 游戏名单判定（样本为
            // com.miui.gamebooster.utils.s1#l(String)）恒返回 true ——
            // 原版只对 sgame / pubgmhd 放行，改后任何游戏都能命中加速提示。
            // 跨版本：不写死类名 / 方法名，以「方法体内出现游戏包名」为锚点，
            // 严格锚点（两个包名同现）失配时再退回宽松锚点（任一包名）。
            // 注：o0.G / o0.k0 / o0.m0 属视频加速属性（debug.game.video.support/speed），
            //     由独立规则 game_speed（Unlock.spGameSpeed）负责 —— 生效的值必须是数字
            //     （native 侧 property_get_int32 / atoi，"true" 会被解析成 0 而失效），
            //     并入本规则既会串味、也会用错值型。
            count += installGamePackagePredicate(rule, sp, "com.tencent.tmgp.sgame&com.tencent.tmgp.pubgmhd");
            if (count == 0) {
                count += installGamePackagePredicate(rule, sp, "com.tencent.tmgp.sgame|com.tencent.tmgp.pubgmhd");
            }
        } else if ("video_dfps".equals(rule.id) || "game_hdr".equals(rule.id)) {
            String[] keys = "video_dfps".equals(rule.id)
                    ? new String[]{"support_video_dfps"}
                    : new String[]{"ro.vendor.pq.disp_game_HDR_support", "support_displayfeature_gamemode_HDR"};
            for (String key : keys) {
                if ("game_hdr".equals(rule.id)) {
                    // 仅解锁能力判定；效果值、用户开关与底层下发沿用原流程。
                    for (Method method : sp.ms(key, null, null, null)) {
                        if (method.getReturnType() == boolean.class && method.getParameterCount() == 0
                                && Unlock.hookMethod(rule, method, AFTER_TRUE)) count++;
                    }
                } else count += installDefault(rule, sp, key);
            }
        } else if ("dual_visual".equals(rule.id)) {
            List<Method> settings = sp.ms("extreme_video_mode_switch_dualDPU", null, null, null);
            for (Method setting : settings) {
                if (setting.getReturnType() != boolean.class || setting.getParameterCount() != 0) continue;
                count += installDefault(rule, sp, "ro.vendor.display.hyperos.miDualDPU_support",
                        setting.getDeclaringClass().getName());
                if (Unlock.hookMethod(rule, setting, AFTER_TRUE)) count++;
            }
        } else if ("picture_enhance".equals(rule.id)) {
            count += installVisionDefaults(rule, sp);
            // 仅将未保存游戏的默认开关设为关闭；已有的逐游戏选择仍由原读取器返回。
            for (Method defaults : sp.ms("gamebooster_vision_enhance_default_switch", null, null, null)) {
                if (defaults.getReturnType() == boolean.class && defaults.getParameterCount() == 0
                        && Unlock.hookMethod(rule, defaults, (chain, r) -> Boolean.FALSE)) count++;
            }
            List<Method> methods = sp.ms("Failed to bind Joyose service", null, null, null);
            if (methods.size() != 1) return 0;
            Method method = methods.get(0);
            if (method.getReturnType() != boolean.class || method.getParameterCount() != 0) return 0;
            count += installDefault(rule, sp, "ro.vendor.display.hyperos.miDualDPU_support",
                    method.getDeclaringClass().getName());
            if (Unlock.hookMethod(rule, method, AFTER_TRUE)) count++;
        } else if ("video_more".equals(rule.id) || "video_bgm".equals(rule.id)
                || "video_cast".equals(rule.id) || "video_screen_off".equals(rule.id)
                || "video_subtitle".equals(rule.id) || "video_settings".equals(rule.id)) {
            final boolean bgmOnly = "video_bgm".equals(rule.id);
            final boolean combined = "video_more".equals(rule.id);
            final boolean settingsOnly = "video_settings".equals(rule.id);
            final boolean subtitleOnly = "video_subtitle".equals(rule.id);
            final List<Method> castGates = sp.ms("disconnectWifiDisplay", null, null, null);
            List<Method> anchors = sp.ms("checkAISubtitle Result = ", null, null, null);
            if (anchors.size() != 1) return 0;
            String cacheKey = "sc-video-menu";
            List<Method> menu = sp.resolvedM.get(cacheKey);
            if (menu == null && sp.bridge != null) {
                menu = new java.util.ArrayList<>();
                for (org.luckypray.dexkit.result.MethodData anchor : sp.q("checkAISubtitle Result = ", null, null)) {
                    for (org.luckypray.dexkit.result.MethodData caller : anchor.getCallers()) {
                        if (!caller.getParamTypeNames().equals(java.util.Arrays.asList("android.content.Context", "boolean", "boolean"))
                                || !java.lang.reflect.Modifier.isStatic(caller.getModifiers())) continue;
                        List<org.luckypray.dexkit.result.MethodData> gates = new java.util.ArrayList<>();
                        for (org.luckypray.dexkit.result.MethodData call : caller.getInvokes()) {
                            if ("boolean".equals(call.getReturnTypeName()) && java.lang.reflect.Modifier.isStatic(call.getModifiers())
                                    && (call.getParamCount() == 0 || call.getParamTypeNames().equals(java.util.Arrays.asList("android.content.Context")))) gates.add(call);
                        }
                        if (gates.size() != 4) continue;
                        if (!menu.isEmpty()) return 0;
                        try {
                            ClassLoader loader = anchors.get(0).getDeclaringClass().getClassLoader();
                            menu.add(caller.getMethodInstance(loader));
                            for (org.luckypray.dexkit.result.MethodData gate : gates) menu.add(gate.getMethodInstance(loader));
                        } catch (Throwable error) { return 0; }
                    }
                }
                sp.resolvedM.put(cacheKey, menu);
            }
            if (menu == null || menu.size() != 5) return 0;
            final ThreadLocal<Boolean> active = new ThreadLocal<>();
            int installed = 0;
            for (int i = 1; i < menu.size(); i++) {
                Method gate = menu.get(i);
                boolean isBgm = gate.getParameterCount() == 1 && !gate.equals(anchors.get(0));
                boolean isSubtitle = gate.equals(anchors.get(0));
                boolean isCast = castGates.contains(gate);
                boolean selected = combined ? !isBgm : bgmOnly ? isBgm
                        : subtitleOnly ? isSubtitle : "video_cast".equals(rule.id) ? isCast
                        : "video_screen_off".equals(rule.id) && !isBgm && !isSubtitle && !isCast;
                if (settingsOnly || !selected) continue;
                if (Unlock.hookMethod(rule, gate, (chain, r) -> Boolean.TRUE.equals(active.get()) ? Boolean.TRUE : chain.proceed())) installed++;
            }
            if (installed != (settingsOnly ? 0 : combined ? 3 : 1)) return 0;
            if (Unlock.hookMethod(rule, menu.get(0), (chain, r) -> {
                Boolean previous = active.get();
                active.set(Boolean.TRUE);
                try {
                    if (!combined && !settingsOnly && !subtitleOnly) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    if (combined || settingsOnly) args[1] = Boolean.TRUE;
                    if (combined || subtitleOnly) args[2] = Boolean.FALSE;
                    return chain.proceed(args);
                } finally { if (previous == null) active.remove(); else active.set(previous); }
            })) count = installed + 1;
        } else if ("bypass_plus".equals(rule.id)) {
            for (Method gate : sp.ms("persist.vendor.smartchg", null, null, null)) {
                if (gate.getReturnType() == boolean.class && gate.getParameterCount() == 0
                        && Unlock.hookMethod(rule, gate, AFTER_TRUE)) count++;
            }
            List<Method> parents = new java.util.ArrayList<>(sp.ms("isSupportSmartBypassPlus: ", null, null, null));
            parents.removeIf(m -> m.getReturnType() != boolean.class || m.getParameterCount() != 0);
            if (parents.size() != 1) return 0;
            Method parent = parents.get(0);
            if (parent.getReturnType() != boolean.class || parent.getParameterCount() != 0) return 0;
            String cacheKey = "sc-bypass-property-reader";
            List<Method> readers = sp.resolvedM.get(cacheKey);
            if (readers == null && sp.bridge != null) {
                readers = new java.util.ArrayList<>();
                for (org.luckypray.dexkit.result.MethodData candidate : sp.q("persist.vendor.smart.bypass.plus", null, null)) {
                    for (org.luckypray.dexkit.result.MethodData called : candidate.getInvokes()) {
                        if (!"java.lang.String".equals(called.getReturnTypeName())
                                || !called.getParamTypeNames().equals(java.util.Arrays.asList("java.lang.String", "java.lang.String"))
                                || !java.lang.reflect.Modifier.isStatic(called.getModifiers())) continue;
                        try {
                            Method reader = called.getMethodInstance(parent.getDeclaringClass().getClassLoader());
                            if (!readers.contains(reader)) readers.add(reader);
                        } catch (Throwable ignored) { }
                    }
                }
                sp.resolvedM.put(cacheKey, readers);
            }
            if (readers == null || readers.size() != 1) return 0;
            final Method reader = readers.get(0);
            reader.setAccessible(true);
            if (Unlock.hookMethod(rule, parent, (chain, r) -> {
                try {
                    String value = (String) reader.invoke(null, "persist.vendor.smart.bypass.plus", "");
                    boolean supported = true; // 解锁 Plus 入口，实际充电开关仍由原 UI 控制。
                    android.util.Log.i("GameBoosterFeatureUtils", "isSupportSmartBypassPlus: " + supported);
                    return supported;
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw error.getCause();
                }
            })) count++;
        } else if ("game_fill_blacklist".equals(rule.id)) {
            for (Method check : sp.ms("isGetGameBlackListFromMiui() === ", null, null, null)) {
                if (check.getReturnType() == boolean.class
                        && java.util.Arrays.equals(check.getParameterTypes(), new Class<?>[]{String.class})
                        && Unlock.hookMethod(rule, check, AFTER_TRUE)) count++;
            }
        } else if ("game_fill".equals(rule.id)) {
            List<Method> entries = sp.ms("device is support ratio === ", null, null, null);
            List<Method> checks = sp.ms("isGetGameBlackListFromMiui() === ", null, null, null);
            if (entries.size() != 1 || checks.size() != 1) return 0;
            Method entry = entries.get(0), check = checks.get(0);
            if (entry.getDeclaringClass() != check.getDeclaringClass()
                    || entry.getReturnType() != boolean.class || check.getReturnType() != boolean.class
                    || !java.util.Arrays.equals(entry.getParameterTypes(), new Class<?>[]{String.class})
                    || !java.util.Arrays.equals(check.getParameterTypes(), new Class<?>[]{String.class})) return 0;
            // 标记原方法是否走到包名检查；只改两个提前返回，保留黑名单结果。
            final ThreadLocal<boolean[]> reached = new ThreadLocal<>();
            if (!Unlock.hookMethod(rule, check, (chain, r) -> {
                boolean[] marker = reached.get();
                if (marker != null) marker[0] = true;
                return chain.proceed();
            })) return 0;
            if (Unlock.hookMethod(rule, entry, (chain, r) -> {
                boolean[] previous = reached.get();
                boolean[] marker = new boolean[1];
                reached.set(marker);
                try {
                    Object original = chain.proceed();
                    return marker[0] ? original : Boolean.TRUE;
                } finally {
                    if (previous == null) reached.remove(); else reached.set(previous);
                }
            })) count = 2;
        } else if ("car_accident".equals(rule.id)) {
            Unlock.Rule shape = new Unlock.Rule("", "", "", 0, null)
                    .ret("Z").params("Landroid/content/Context;");
            Method method = sp.reflectOne("com.miui.warningcenter.caraccident.CarAccidentUtils",
                    "isSupportCarAccidentDetect", shape);
            if (method != null && method.getReturnType() == boolean.class
                    && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{android.content.Context.class})
                    && Unlock.hookMethod(rule, method, AFTER_TRUE)) count++;
        } else if ("gpu_enhance".equals(rule.id)) {
            String owner = "com.xiaomi.joyose.securitycenter.IGPUTunerInterface$Stub$a";
            String[] names = {"checkSupportGpuTuner", "checkSupportUgd", "isSupportPerformancePolicy",
                    "isSupportSuperResolutionWithFrameInsert", "isSupportGameEnhancePkg"};
            for (int i = 0; i < names.length; i++) {
                Unlock.Rule shape = new Unlock.Rule("", "", "", 0, null).ret(i < 4 ? "Z" : "I");
                if (i < 2) shape.params(); else shape.params("Ljava/lang/String;");
                Method method = sp.reflectOne(owner, names[i], shape);
                if (method == null || method.getReturnType() != (i < 4 ? boolean.class : int.class)
                        || !java.util.Arrays.equals(method.getParameterTypes(),
                                i < 2 ? new Class<?>[0] : new Class<?>[]{String.class})) continue;
                final Object value = i < 4 ? Boolean.TRUE : Integer.valueOf(i == 4 ? 2 : 1);
                if (Unlock.hookMethod(rule, method, new Unlock.SpAction() {
                    @Override public Object run(XposedInterface.Chain chain, Unlock.Rule r) {
                        return value;
                    }
                })) count++;
            }
        } else if ("video_bgm_app".equals(rule.id)) {
            count += installBgmApp(rule, sp);
            count += installBgmEnabled(rule, sp, "music_bgm_status");
        }
        if (count > 0) rule.where = "DEX 差异移植（" + count + " 方法）";
        return count;
    }
}
