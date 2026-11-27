package com.hyper.Camellia;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

/**
 * 小窗增强（移植自 HyperFreeformX，已按本机 HyperOS + Android 17 的真实类与方法重写）。
 *
 * 作用域 1: "android"（system_server / miui-services）
 *   - 解除小窗数量限制
 *   - 解除小窗黑名单与「不支持小窗」拦截
 *   - 前台钉住（后台不冻结、不隐藏 surface）
 *   - 自由小窗参数校验放宽
 *
 * 作用域 2: "com.android.systemui"（WM-Shell 运行在 SystemUI 进程内）
 *   - 通知下拉悬浮窗放行
 *   - 隐藏小窗顶部标题栏/指示点（MiuiDecorationDot）
 *   - 隐藏小窗底部标题栏（MiuiDecorationBottom）
 *   - 隐藏小窗底部小白条（MiuiDecorationHomeBottom）
 *
 * 所有开关在回调内实时读取，改设置后无需重启进程。
 */
public final class FreeformHooks {
    private static final String TAG = "Camellia-Freeform";

    /** 小窗装饰类所在包（WM-Shell，跑在 SystemUI 进程里） */
    private static final String DEC =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.";

    /** 小窗装饰顶层包（DecorViewModel / EventHandler 等） */
    private static final String DEC_PKG =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.";

    public interface PrefProvider {
        SharedPreferences prefs();
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
    }

    private static void logE(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }

    /** 需要实时读取的开关 */
    private static final String[] WATCHED_KEYS = new String[] {
            Prefs.KEY_FF_COUNT_LIMIT,
            Prefs.KEY_FF_BLACKLIST,
            Prefs.KEY_FF_PIN_FOREGROUND,
            Prefs.KEY_FF_CAN_SLIDE,
            Prefs.KEY_FF_CAPTION_TOP,
            Prefs.KEY_FF_CAPTION_BOTTOM,
            Prefs.KEY_FF_HOME_BAR
    };

    private static final long SYNC_TTL_MS = 1500L;
    private static long sSyncAt = 0L;
    private static final java.util.HashMap<String, Boolean> sSnap =
            new java.util.HashMap<String, Boolean>();

    /**
     * 读取开关（1.5 秒 TTL 快照 + reload）。
     *
     * 为什么需要它：libxposed 的 remote preferences 是只读快照，必须 reload() 才能看到新值；
     * 而开关判定发生在被 hook 的高频方法里，逐次 reload（读文件）会拖慢系统进程。
     * 折中：最多每 1.5s 同步一次，既保证「改设置后基本立刻生效」，又不影响性能。
     */
    private static boolean isFeatureEnabled(PrefProvider pp, String key, boolean defaultVal) {
        if (pp == null) return defaultVal;
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (sSnap) {
            if (now - sSyncAt >= SYNC_TTL_MS) {
                sSyncAt = now;
                try {
                    SharedPreferences sp = pp.prefs();
                    if (sp != null) {
                        tryReload(sp);
                        for (String k : WATCHED_KEYS) {
                            sSnap.put(k, sp.getBoolean(k, defaultOf(k)));
                        }
                    }
                } catch (Throwable t) {
                    // 保留旧快照
                }
            }
            Boolean v = sSnap.get(key);
            return v != null ? v : defaultVal;
        }
    }

    /** 各开关在「用户从未写过」时的默认值（与 Prefs 保持一致） */
    private static boolean defaultOf(String key) {
        if (Prefs.KEY_FF_CAPTION_TOP.equals(key)
                || Prefs.KEY_FF_CAPTION_BOTTOM.equals(key)
                || Prefs.KEY_FF_HOME_BAR.equals(key)) {
            return false;
        }
        return true;
    }

    /** libxposed remote preferences 需 reload() 才可见新值（反射调用，失败忽略） */
    private static void tryReload(SharedPreferences sp) {
        try {
            Method m = sp.getClass().getMethod("reload");
            m.setAccessible(true);
            m.invoke(sp);
        } catch (Throwable ignored) {
        }
    }

    public interface HookHandler {
        Object handle(Chain chain) throws Throwable;
    }

    /** 方法筛选器 */
    interface MethodFilter {
        boolean accept(Method m);
    }

    /** 安全 hook 方法辅助工具 (libxposed: module.hook(m).intercept(Hooker)) */
    private static boolean hookMethodSafe(XposedModule module, Method method, final HookHandler handler) {
        if (method == null) return false;
        try {
            method.setAccessible(true);
            module.hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    return handler.handle(chain);
                }
            });
            return true;
        } catch (Throwable t) {
            logE("hookMethodSafe failed on " + method.getDeclaringClass().getName() + "#" + method.getName(), t);
            return false;
        }
    }

    private static boolean isBool(Method m) {
        return m.getReturnType() == boolean.class;
    }

    private static boolean isInt(Method m) {
        return m.getReturnType() == int.class;
    }

    private static boolean isList(Method m) {
        return List.class.isAssignableFrom(m.getReturnType());
    }

    /**
     * 多 ClassLoader 尝试查找目标类：
     * SystemUI 进程里 WM-Shell 的类不一定由默认 ClassLoader 加载，需逐个兜底。
     */
    private static Class<?> findClass(ClassLoader cl, String name) {
        ClassLoader[] loads = new ClassLoader[] {
                LauncherShortcut.systemServerLoader(),
                ClassLoader.getSystemClassLoader(),
                cl,
                cl == null ? null : cl.getParent(),
                Thread.currentThread().getContextClassLoader(),
                FreeformHooks.class.getClassLoader()
        };
        for (ClassLoader l : loads) {
            if (l == null) continue;
            try {
                Class<?> c = Class.forName(name, false, l);
                log("findClass ok: " + name + " via " + l);
                return c;
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> c = Class.forName(name);
            log("findClass ok(default): " + name);
            return c;
        } catch (Throwable ignored) {
            logE("findClass FAIL: " + name + " (all loaders)", null);
            return null;
        }
    }

    /** 对 cls 中所有满足 filter 的方法统一挂 handler */
    private static void hookIf(XposedModule module, Class<?> cls, MethodFilter filter, String label, HookHandler h) {
        if (cls == null) return;
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            try {
                if (!filter.accept(m)) continue;
                if (hookMethodSafe(module, m, h)) {
                    n++;
                    log("hooked " + label + "#" + m.getName() + "/" + m.getParameterCount());
                }
            } catch (Throwable t) {
                logE("hookIf " + label + "#" + m.getName(), t);
            }
        }
        if (n == 0) log("no target matched in " + label);
    }

    /** 取链式调用第一个参数（装饰可见性等 setVisibility(Z) 场景） */
    private static boolean firstArgIsTrue(Chain chain) {
        try {
            List<?> args = chain.getArgs();
            return args != null && !args.isEmpty() && Boolean.TRUE.equals(args.get(0));
        } catch (Throwable t) {
            return false;
        }
    }

    private static final HookHandler H_TRUE = new HookHandler() {
        @Override
        public Object handle(Chain chain) {
            return Boolean.TRUE;
        }
    };

    private static final HookHandler H_FALSE = new HookHandler() {
        @Override
        public Object handle(Chain chain) {
            return Boolean.FALSE;
        }
    };

    /** 解除数量限制后返回的上限值 */
    private static final int MAX_STACK_COUNT = 99;

    private static final HookHandler H_MAX = new HookHandler() {
        @Override
        public Object handle(Chain chain) {
            return MAX_STACK_COUNT;
        }
    };

    private static final HookHandler H_NULL = new HookHandler() {
        @Override
        public Object handle(Chain chain) {
            return null;
        }
    };

    private static final HookHandler H_EMPTY_LIST = new HookHandler() {
        @Override
        public Object handle(Chain chain) {
            return Collections.emptyList();
        }
    };

    private static final HookHandler H_ZERO = new HookHandler() {
        @Override
        public Object handle(Chain chain) {
            return 0;
        }
    };

    // ── 开关门控的处理器（开关关闭时放行原逻辑）───────────────

    /** 开关打开时返回布尔常量 */
    private static HookHandler onBool(final PrefProvider pp, final String key, final boolean on) {
        return new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (isFeatureEnabled(pp, key, defaultOf(key))) return Boolean.valueOf(on);
                return chain.proceed();
            }
        };
    }

    /** 开关打开时返回整数常量 */
    private static HookHandler onInt(final PrefProvider pp, final String key, final int value) {
        return new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (isFeatureEnabled(pp, key, defaultOf(key))) return Integer.valueOf(value);
                return chain.proceed();
            }
        };
    }

    /** 开关打开时返回空 List（清空黑名单） */
    private static HookHandler onEmptyList(final PrefProvider pp, final String key) {
        return new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (isFeatureEnabled(pp, key, defaultOf(key))) return Collections.emptyList();
                return chain.proceed();
            }
        };
    }

    /** 开关打开时返回 null */
    private static HookHandler onNull(final PrefProvider pp, final String key) {
        return new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (isFeatureEnabled(pp, key, defaultOf(key))) return null;
                return chain.proceed();
            }
        };
    }

    // ─────────────────────────────────────────────────────────────
    // 作用域 1: "android" (system_server / miui-services)
    // ─────────────────────────────────────────────────────────────

    public static void installAndroid(final XposedModule module, final ClassLoader cl, final PrefProvider prefProvider) {
        log("installAndroid begin with loader: " + cl);

        // 注意：这里不再用开关做安装条件。system_server 只有开机时才加载模块，
        // 若安装期判定开关，用户事后打开开关要重启才生效。改为「总是安装 hook，
        // 在回调里实时读开关」（见 isFeatureEnabled 的 TTL 快照）。

        try {
            hookRemoveFreeformCountLimit(module, cl, prefProvider);
        } catch (Throwable t) {
            logE("hookRemoveFreeformCountLimit failed", t);
        }

        try {
            hookRemoveFreeformBlacklist(module, cl, prefProvider);
        } catch (Throwable t) {
            logE("hookRemoveFreeformBlacklist failed", t);
        }

        try {
            hookForcePinWindowForeground(module, cl, prefProvider);
        } catch (Throwable t) {
            logE("hookForcePinWindowForeground failed", t);
        }

        // 4. 桌面「长按图标 → 快捷方式」里注入「小窗」项（合成 ShortcutInfo + 接管点击）
        try {
            LauncherShortcut.install(module, cl, prefProvider);
        } catch (Throwable t) {
            logE("install LauncherShortcut failed", t);
        }

        // 5. (v151) A 自检已删除：A/B 不再互斥，B 注入只看自身开关
    }

    /**
     * 1. 解除小窗数量限制
     * 真实入口：com.android.server.wm.MiuiFreeFormStackDisplayStrategy#getMaxMiuiFreeFormStackCount(String,MiuiFreeFormActivityStack)I
     */
    private static void hookRemoveFreeformCountLimit(XposedModule module, ClassLoader cl,
                                                     final PrefProvider pp) {
        Class<?> strategy = findClass(cl, "com.android.server.wm.MiuiFreeFormStackDisplayStrategy");
        hookIf(module, strategy, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isInt(m) && "getMaxMiuiFreeFormStackCount".equals(m.getName());
            }
        }, "MiuiFreeFormStackDisplayStrategy", onInt(pp, Prefs.KEY_FF_COUNT_LIMIT, MAX_STACK_COUNT));

        // 兜底：部分版本数量上限在 MiuiFreeformServiceImpl / 内部 CountManager 判定
        Class<?> svc = findClass(cl, "com.android.server.wm.MiuiFreeformServiceImpl");
        hookIf(module, svc, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                String n = m.getName();
                if (!isInt(m)) return false;
                String low = n.toLowerCase();
                return "getNormalFreeformTaskMaxNum".equals(n)
                        || "getMaxFreeformTaskNum".equals(n)
                        || (low.startsWith("getmax") && low.contains("freeform"));
            }
        }, "MiuiFreeformServiceImpl/max", onInt(pp, Prefs.KEY_FF_COUNT_LIMIT, MAX_STACK_COUNT));

        hookIf(module, svc, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                String n = m.getName();
                return isBool(m) && ("freeformTaskCanRun".equals(n)
                        || "isSupportMultipleFreeform".equals(n)
                        || "isSupportMultipleFreeForm".equals(n));
            }
        }, "MiuiFreeformServiceImpl/support", onBool(pp, Prefs.KEY_FF_COUNT_LIMIT, true));

        // 内部类 MiuiFreeformCountManager
        if (svc != null) {
            for (Class<?> inner : svc.getDeclaredClasses()) {
                hookIf(module, inner, new MethodFilter() {
                    @Override
                    public boolean accept(Method m) {
                        return isBool(m) && "isSupportCountLimit".equals(m.getName());
                    }
                }, inner.getSimpleName(), onBool(pp, Prefs.KEY_FF_COUNT_LIMIT, false));
            }
        }
    }

    /**
     * 2. 解除应用小窗黑名单 / 「不支持小窗」拦截
     * 真实入口：com.android.server.wm.MiuiFreeformServiceImpl 的各类 *BlackList 列表与支持判定；
     *           com.xiaomi.freeform.MiuiFreeformImpl#packageSupportFreeform
     */
    private static void hookRemoveFreeformBlacklist(XposedModule module, ClassLoader cl,
                                                    final PrefProvider pp) {
        final HookHandler emptyList = onEmptyList(pp, Prefs.KEY_FF_BLACKLIST);
        final HookHandler allow = onBool(pp, Prefs.KEY_FF_BLACKLIST, true);
        final HookHandler deny = onBool(pp, Prefs.KEY_FF_BLACKLIST, false);

        // 所有 get*BlackList() 一律返回空列表（应用黑名单 / 来源黑名单 / 异常黑名单 / 钉住黑名单）
        final MethodFilter listFilter = new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                if (!isList(m)) return false;
                String n = m.getName();
                return n.startsWith("get") && n.contains("BlackList");
            }
        };
        // 白名单/支持判定放行
        final MethodFilter supportFilter = new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                if (!isBool(m)) return false;
                String n = m.getName();
                return "inFreeformWhiteList".equals(n)
                        || "inFreeformWhiteListInSystem".equals(n)
                        || "isSupportFreeFormMultiTask".equals(n)
                        || "isAppSupportFreeform".equals(n)
                        || "isSupportFreeform".equals(n)
                        || "isSupportFreeForm".equals(n);
            }
        };
        // 「内存不足所以不给小窗」等硬件/内存级限制
        final MethodFilter memFilter = new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                if (!isBool(m)) return false;
                String n = m.getName();
                return n.contains("NotSupportFreeform") || n.contains("NotSupportFreeForm");
            }
        };

        // 2.1 miui-framework 适配层（各 app 进程实际读到的黑名单在这里，样本验证过的最关键入口）
        Class<?> adapter = findClass(cl, "android.util.MiuiMultiWindowAdapter");
        hookIf(module, adapter, listFilter, "MiuiMultiWindowAdapter/BlackList", emptyList);
        hookIf(module, adapter, supportFilter, "MiuiMultiWindowAdapter/support", allow);

        Class<?> utils = findClass(cl, "android.util.MiuiMultiWindowUtils");
        hookIf(module, utils, listFilter, "MiuiMultiWindowUtils/BlackList", emptyList);
        hookIf(module, utils, supportFilter, "MiuiMultiWindowUtils/support", allow);
        hookIf(module, utils, memFilter, "MiuiMultiWindowUtils/memNotSupport", deny);

        // 2.2 服务端实现（miui-services.jar）：列表 + 支持判定 + 内部实现类
        Class<?> svc = findClass(cl, "com.android.server.wm.MiuiFreeformServiceImpl");
        hookIf(module, svc, listFilter, "MiuiFreeformServiceImpl/BlackList", emptyList);
        hookIf(module, svc, supportFilter, "MiuiFreeformServiceImpl/support", allow);

        // 2.3 AOSP 层（services.jar / framework.jar）
        hookIf(module, findClass(cl, "com.android.server.am.ActivityManagerService"), listFilter,
                "ActivityManagerService/BlackList", emptyList);
        hookIf(module, findClass(cl, "android.app.ActivityManager"), listFilter,
                "ActivityManager/BlackList", emptyList);

        // 2.4 自由窗实现层（miui-services 里的 xiaomi.freeform 实现）
        Class<?> ximpl = findClass(cl, "com.xiaomi.freeform.MiuiFreeformImpl");
        hookIf(module, ximpl, listFilter, "MiuiFreeformImpl/BlackList", emptyList);
        hookIf(module, ximpl, supportFilter, "MiuiFreeformImpl/support", allow);
        Class<?> direct = findClass(cl, "com.xiaomi.freeform.MiuiFreeformDirectImpl");
        hookIf(module, direct, listFilter, "MiuiFreeformDirectImpl/BlackList", emptyList);
        hookIf(module, direct, supportFilter, "MiuiFreeformDirectImpl/support", allow);

        // 2.5 「不支持小窗」提示/埋点抑制（避免弹「该应用不支持小窗」）
        hookIf(module, svc, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                String n = m.getName();
                if (!(n.contains("NotSupportMultiWindow") || n.contains("NotSupportMultiWin"))) return false;
                return isBool(m) || isList(m) || m.getReturnType() == String.class;
            }
        }, "MiuiFreeformServiceImpl/NotSupportMultiWindow", onNull(pp, Prefs.KEY_FF_BLACKLIST));
    }

    /**
     * 3. 前台钉住小窗 / 防后台冻结
     * 真实入口：com.android.server.wm.MiuiFreeFormManagerService
     *          （shouldForegroundPin / getMiuiFreeformIsForegroundPin / isSupportPin）
     *          注意：shouldSkipSurfaceVisibilityUpdateForForegroundPin 必须排除，
     *          它会打断所有 Surface 可见性更新（详见 accept 内注释）。
     *          + com.android.server.wm.MiuiFreeFormGestureController#needForegroundPin
     */
    private static void hookForcePinWindowForeground(XposedModule module, ClassLoader cl,
                                                     final PrefProvider pp) {
        final HookHandler pinOn = onBool(pp, Prefs.KEY_FF_PIN_FOREGROUND, true);
        Class<?> svc = findClass(cl, "com.android.server.wm.MiuiFreeFormManagerService");
        hookIf(module, svc, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                if (!isBool(m)) return false;
                String n = m.getName();
                // 注意：绝对不要 hook shouldSkipSurfaceVisibilityUpdateForForegroundPin。
                // 该方法是 WM 在每次 Activity 可见性计算（ensureActivitiesVisible）
                // 时都会无条件查询的全局开关；恒返回 true 会让系统跳过全部 Surface
                // 可见性更新，导致全屏 Activity 返回时下层 Activity 拿不回 Surface
                // —— 这正是「抖音作品详情页返回黑屏」的根因。
                return "shouldForegroundPin".equals(n)
                        || "getMiuiFreeformIsForegroundPin".equals(n)
                        || "isSupportPin".equals(n);
            }
        }, "MiuiFreeFormManagerService/Pin", pinOn);

        Class<?> gesture = findClass(cl, "com.android.server.wm.MiuiFreeFormGestureController");
        hookIf(module, gesture, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isBool(m) && "needForegroundPin".equals(m.getName());
            }
        }, "MiuiFreeFormGestureController/Pin", pinOn);

        // 兜底 1：AMS OomAdjuster 的钉住状态更新（返回 null 即「无需处理」，不再降级小窗）
        try {
            Class<?> oom = findClass(cl, "com.android.server.am.ActivityManagerService$OomAdjusterImpl");
            if (oom == null) oom = findClass(cl, "com.android.server.am.OomAdjusterImpl");
            hookIf(module, oom, new MethodFilter() {
                @Override
                public boolean accept(Method m) {
                    return "updateFreeFormWindowPinState".equals(m.getName());
                }
            }, "OomAdjusterImpl", onNull(pp, Prefs.KEY_FF_PIN_FOREGROUND));
        } catch (Throwable t) {
            logE("hook OomAdjusterImpl failed", t);
        }

        // 兜底 2：小窗 ActivityRecord 保持可见
        try {
            Class<?> arCls = findClass(cl, "com.android.server.wm.ActivityRecord");
            hookIf(module, arCls, new MethodFilter() {
                @Override
                public boolean accept(Method m) {
                    return isBool(m) && m.getParameterCount() == 0 && "shouldBeVisible".equals(m.getName());
                }
            }, "ActivityRecord/shouldBeVisible", new HookHandler() {
                @Override
                public Object handle(Chain chain) throws Throwable {
                    Object res = chain.proceed();
                    if (Boolean.TRUE.equals(res)) return res;
                    // 受「前台小窗防冻结」开关约束：关闭时完全不干预可见性计算，
                    // 避免把全屏 Activity 错误地钉成 visible 而阻塞返回转场。
                    if (!isFeatureEnabled(pp, Prefs.KEY_FF_PIN_FOREGROUND, true)) return res;
                    Object ar = chain.getThisObject();
                    if (ar != null) {
                        try {
                            Method inFreeform = ar.getClass().getMethod("inFreeformWindowingMode");
                            if (Boolean.TRUE.equals(inFreeform.invoke(ar))) {
                                return Boolean.TRUE;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    return res;
                }
            });
        } catch (Throwable t) {
            logE("hook ActivityRecord#shouldBeVisible failed", t);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 作用域 2: "com.android.systemui"（WM-Shell 装饰层）
    // ─────────────────────────────────────────────────────────────

    public static void installSystemUI(final XposedModule module, final ClassLoader cl, final PrefProvider prefProvider) {
        log("installSystemUI begin with loader: " + cl);

        // 通知下拉放行：开关在回调内实时读取
        try {
            hookCanSlideNotification(module, cl, prefProvider);
        } catch (Throwable t) {
            logE("hookCanSlideNotification failed", t);
        }

        // 小窗装饰（标题栏 / 指示点 / 小白条）：开关在回调内实时读取，这里总是安装
        try {
            hookFreeformDecoration(module, cl, prefProvider);
        } catch (Throwable t) {
            logE("hookFreeformDecoration failed", t);
        }
    }

    /** 通知下拉时允许悬浮小窗滑动展开（C 项：小窗内播放/通知控制） */
    private static void hookCanSlideNotification(XposedModule module, ClassLoader cl,
                                                 final PrefProvider pp) {
        // 设备实测唯一门控（本机 SystemUI 清单 0x0065ac5c）：
        //   com.android.systemui.statusbar.notification.policy.AppMiniWindowManagerImpl
        //     #canNotificationSlide(String, PendingIntent)Z  → true 即放行小窗内下拉通知
        final HookHandler forceTrue = onBool(pp, Prefs.KEY_FF_CAN_SLIDE, true);
        String[] amwNames = new String[]{
                "com.android.systemui.statusbar.notification.policy.AppMiniWindowManagerImpl",
                "com.android.systemui.appminiwindow.AppMiniWindowManagerImpl",
                "com.android.wm.shell.appminiwindow.AppMiniWindowManagerImpl",
                "com.miui.appminiwindow.AppMiniWindowManagerImpl",
        };
        MethodFilter slideFilter = new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isBool(m) && m.getName().contains("NotificationSlide");
            }
        };
        for (String name : amwNames) {
            Class<?> amw = findClass(cl, name);
            hookIf(module, amw, slideFilter, name.substring(name.lastIndexOf('.') + 1), forceTrue);
        }
        // 兜底：部分版本通知可滑动判定走 NotificationUtil#canSlide
        Class<?> nu = findClass(cl, "com.android.systemui.statusbar.notification.NotificationUtil");
        if (nu == null) {
            nu = findClass(cl, "com.android.systemui.statusbar.notification.NotificationUtilKt");
        }
        hookIf(module, nu, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isBool(m) && "canSlide".equals(m.getName());
            }
        }, "NotificationUtil/canSlide", forceTrue);
    }

    /**
     * 小窗顶部标题栏/指示点、底部标题栏、底部小白条的隐藏。
     *
     * 目标（WM-Shell，跑在 SystemUI 进程内）：
     *   decoration.MiuiDecorationDot        —— 顶部标题栏 + 指示点
     *   decoration.MiuiDecorationBottom     —— 底部标题栏
     *   decoration.MiuiDecorationHomeBottom —— 桌面模式底部小白条
     *   decoration.MiuiDecorationBase       —— setVisibility(Z) / setVisibilityDuringDecorationAnim(Z)
     *   decoration.MiuiDecorationController —— getTopCaptionVisibility/setTopCaptionVisibility（总控）
     *   decoration.MiuiDecorationHomeController —— getBottomCaptionVisibility/setBottomCaptionVisibility
     */
    private static void hookFreeformDecoration(final XposedModule module, ClassLoader cl, final PrefProvider pp) {
        final Class<?> dot = findClass(cl, DEC + "MiuiDecorationDot");
        final Class<?> bottom = findClass(cl, DEC + "MiuiDecorationBottom");
        final Class<?> homeBottom = findClass(cl, DEC + "MiuiDecorationHomeBottom");
        final Class<?> base = findClass(cl, DEC + "MiuiDecorationBase");
        final Class<?> controller = findClass(cl, DEC + "MiuiDecorationController");
        final Class<?> homeController = findClass(cl, DEC + "MiuiDecorationHomeController");

        log("decoration classes: dot=" + dot + " bottom=" + bottom + " homeBottom=" + homeBottom
                + " base=" + base + " controller=" + controller + " homeController=" + homeController);

        // 0) 【设备实测精确目标】与 HyperCeiler 的 SDK>=36 分支一致，本机 HyperOS4 逐字命中：
        //    顶部指示点/手柄菜单 → createHandleMenu 置空 + 视图 onDraw 空转；
        //    freeform 底部小白条高度 → getFreeformBottomCaptionHeight() 返回 0；
        //    底部标题栏高度 → getBottomCaptionHeight() 返回 0。
        Class<?> dotView = findClass(cl, DEC + "MiuiDecorationDotView");
        Class<?> decoInfo = findClass(cl, DEC + "MiuiDecorationInfo");
        hookIf(module, dot, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return "createHandleMenu".equals(m.getName());
            }
        }, "MiuiDecorationDot/createHandleMenu", onNull(pp, Prefs.KEY_FF_CAPTION_TOP));
        hookIf(module, dotView, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return "onDraw".equals(m.getName());
            }
        }, "MiuiDecorationDotView/onDraw", onNull(pp, Prefs.KEY_FF_CAPTION_TOP));
        hookIf(module, decoInfo, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isInt(m) && m.getParameterCount() == 0
                        && "getFreeformBottomCaptionHeight".equals(m.getName());
            }
        }, "MiuiDecorationInfo/getFreeformBottomCaptionHeight", onInt(pp, Prefs.KEY_FF_HOME_BAR, 0));
        hookIf(module, bottom, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isInt(m) && m.getParameterCount() == 0
                        && "getBottomCaptionHeight".equals(m.getName());
            }
        }, "MiuiDecorationBottom/getBottomCaptionHeight", new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (bottomHidden(pp)) return 0;
                return chain.proceed();
            }
        });

        // 1) 各装饰部件的「是否显示标题栏」判定 —— 直接 false，从源头不显示
        hookShouldShowCaption(module, dot, pp, "MiuiDecorationDot", Prefs.KEY_FF_CAPTION_TOP);
        hookShouldShowCaption(module, bottom, pp, "MiuiDecorationBottom",
                Prefs.KEY_FF_CAPTION_BOTTOM, Prefs.KEY_FF_HOME_BAR);
        hookShouldShowCaption(module, homeBottom, pp, "MiuiDecorationHomeBottom", Prefs.KEY_FF_HOME_BAR);

        // 2) 各装饰部件的目标 alpha —— 置 0（隐藏且不参与合成）
        hookTargetAlpha(module, dot, pp, "MiuiDecorationDot", Prefs.KEY_FF_CAPTION_TOP);
        hookTargetAlpha(module, bottom, pp, "MiuiDecorationBottom",
                Prefs.KEY_FF_CAPTION_BOTTOM, Prefs.KEY_FF_HOME_BAR);
        hookTargetAlpha(module, homeBottom, pp, "MiuiDecorationHomeBottom", Prefs.KEY_FF_HOME_BAR);

        // 3) 可见性设置 —— 按实例类型判断属于哪个部件，置 true 的调用直接丢弃
        hookIf(module, base, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                String n = m.getName();
                return ("setVisibility".equals(n) || "setVisibilityDuringDecorationAnim".equals(n))
                        && m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class;
            }
        }, "MiuiDecorationBase/setVisibility", new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (partHidden(pp, chain.getThisObject()) && firstArgIsTrue(chain)) {
                    return null;
                }
                return chain.proceed();
            }
        });

        // 4) 顶部标题栏总控（MiuiDecorationController）
        hookIf(module, controller, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isBool(m) && "getTopCaptionVisibility".equals(m.getName());
            }
        }, "MiuiDecorationController/getTopCaptionVisibility", new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (isFeatureEnabled(pp, Prefs.KEY_FF_CAPTION_TOP, false)) return Boolean.FALSE;
                return chain.proceed();
            }
        });
        hookIf(module, controller, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return "setTopCaptionVisibility".equals(m.getName()) && m.getParameterCount() == 1;
            }
        }, "MiuiDecorationController/setTopCaptionVisibility", new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (isFeatureEnabled(pp, Prefs.KEY_FF_CAPTION_TOP, false) && firstArgIsTrue(chain)) return null;
                return chain.proceed();
            }
        });

        // 5) 底部标题栏总控（Controller 或 HomeController）
        hookIf(module, controller, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isBool(m) && "getBottomCaptionVisibility".equals(m.getName());
            }
        }, "MiuiDecorationController/getBottomCaptionVisibility", new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (bottomHidden(pp)) return Boolean.FALSE;
                return chain.proceed();
            }
        });
        hookIf(module, controller, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return "setBottomCaptionVisibility".equals(m.getName()) && m.getParameterCount() == 1;
            }
        }, "MiuiDecorationController/setBottomCaptionVisibility", new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (bottomHidden(pp) && firstArgIsTrue(chain)) return null;
                return chain.proceed();
            }
        });
        hookIf(module, homeController, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isBool(m) && "getBottomCaptionVisibility".equals(m.getName());
            }
        }, "MiuiDecorationHomeController/getBottomCaptionVisibility", new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (isFeatureEnabled(pp, Prefs.KEY_FF_HOME_BAR, false)) return Boolean.FALSE;
                return chain.proceed();
            }
        });
        hookIf(module, homeController, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return "setBottomCaptionVisibility".equals(m.getName()) && m.getParameterCount() == 1;
            }
        }, "MiuiDecorationHomeController/setBottomCaptionVisibility", new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (isFeatureEnabled(pp, Prefs.KEY_FF_HOME_BAR, false) && firstArgIsTrue(chain)) return null;
                return chain.proceed();
            }
        });

        // 6) 装饰容器整体（DecorViewModel）——三开关全开时直接不显示窗口装饰
        Class<?> vm = findClass(cl, "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MulWinSwitchDecorViewModel");
        hookIf(module, vm, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isBool(m) && "shouldShowWindowDecor".equals(m.getName());
            }
        }, "MulWinSwitchDecorViewModel/shouldShowWindowDecor", new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (anyOn(pp, Prefs.KEY_FF_CAPTION_TOP) && bottomHidden(pp)
                        && anyOn(pp, Prefs.KEY_FF_HOME_BAR)) {
                    return Boolean.FALSE;
                }
                return chain.proceed();
            }
        });
    }

    /** 部件实例 → 该部件的隐藏开关是否已打开（Dot=顶部；Bottom=底部标题栏/小白条；HomeBottom=小白条） */
    private static boolean partHidden(PrefProvider pp, Object o) {
        if (o == null) return false;
        String n = o.getClass().getName();
        if (n.endsWith("MiuiDecorationHomeBottom")) return anyOn(pp, Prefs.KEY_FF_HOME_BAR);
        if (n.endsWith("MiuiDecorationDot")) return anyOn(pp, Prefs.KEY_FF_CAPTION_TOP);
        if (n.endsWith("MiuiDecorationBottom")) return bottomHidden(pp);
        // 其它装饰部件（顶部标题栏容器等）：跟随顶部开关
        if (n.contains("DecorationTop") || n.contains("DecorationCaption")) {
            return anyOn(pp, Prefs.KEY_FF_CAPTION_TOP);
        }
        return false;
    }

    /** 任一开关打开即视为「底部隐藏」（底部标题栏与小白条在同一部件上，两者取或） */
    private static boolean bottomHidden(PrefProvider pp) {
        return isFeatureEnabled(pp, Prefs.KEY_FF_CAPTION_BOTTOM, defaultOf(Prefs.KEY_FF_CAPTION_BOTTOM))
                || isFeatureEnabled(pp, Prefs.KEY_FF_HOME_BAR, defaultOf(Prefs.KEY_FF_HOME_BAR));
    }

    /** 多开关取或 */
    private static boolean anyOn(PrefProvider pp, String... keys) {
        for (String k : keys) {
            if (isFeatureEnabled(pp, k, defaultOf(k))) return true;
        }
        return false;
    }

    /** shouldShowCaption*()Z 系列判定（多个 key 取或） */
    private static void hookShouldShowCaption(XposedModule module, Class<?> cls, final PrefProvider pp,
                                              String label, final String... keys) {
        hookIf(module, cls, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isBool(m) && m.getName().startsWith("shouldShowCaption");
            }
        }, label + "/shouldShowCaption", new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (anyOn(pp, keys)) return Boolean.FALSE;
                return chain.proceed();
            }
        });
    }

    /** getTargetAlpha()I 系列（多个 key 取或） */
    private static void hookTargetAlpha(XposedModule module, Class<?> cls, final PrefProvider pp,
                                        String label, final String... keys) {
        hookIf(module, cls, new MethodFilter() {
            @Override
            public boolean accept(Method m) {
                return isInt(m) && m.getParameterCount() == 0 && "getTargetAlpha".equals(m.getName());
            }
        }, label + "/getTargetAlpha", new HookHandler() {
            @Override
            public Object handle(Chain chain) throws Throwable {
                if (anyOn(pp, keys)) return 0;
                return chain.proceed();
            }
        });
    }
}
