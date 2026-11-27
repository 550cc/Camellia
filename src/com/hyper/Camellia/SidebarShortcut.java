package com.hyper.Camellia;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 侧边栏「长按应用菜单 → 附加该应用的 Launcher 快捷方式面板」。
 *
 * <p>侧边栏长按应用会弹出 {@code com.miui.dock.drag.DockShortCutMenu}（手机管家 :ui 进程）。
 * 本规则挂四个锚点（多锚点是为了不依赖某个版本具体调用哪条链路）：①菜单自身的装配列表方法
 * {@code c()V}（与可用规则 {@code SidebarFreeform} 同锚点，菜单每弹一次必被调用）；②菜单展示入口
 * {@code f(La8/i)}；③样本 APK 用的 {@link View#setLayoutParams(ViewGroup.LayoutParams)}
 * （仅在 {@code self.getClass()==菜单类} 且参数为 {@code FrameLayout$LayoutParams} 时命中）；
 * ④{@link View#setVisibility(int)}（用于收起时拆面板）。菜单显示时，在菜单的父
 * {@link FrameLayout} 里追加一个自绘的 {@link LinearLayout} 面板，逐行列出「长按的这个应用」
 * 的 Launcher 快捷方式（动态 / 固定 / 清单）；点按即以 MIUI 小窗启动；菜单收起时移除面板。</p>
 *
 * <p>移植来源：样本 APK（HyperFreeformX）中的 {@code f33}(SidebarShortcut) +
 * {@code wv2} 的 setVisibility 分支 + {@code d33} 面板 / {@code b33} 预绘制监听 /
 * {@code F0} 定位。与原版的差异只有两处，都是为了适配本项目的既有链路：</p>
 * <ul>
 *   <li>快捷方式点按：原版只走 {@code LauncherApps#startShortcut}（需要调用者是桌面，
 *       手机管家进程里并不保证成功）；这里改为「有 launchIntent 就走
 *       {@link Context#startActivity(Intent, Bundle)} + {@link SecurityDiffFeatures#smallWindowOptions}
 *       这条本项目已验证可用的小窗链路，没有 Intent 才回退 startShortcut」。</li>
 *   <li>目标应用反推：原版靠菜单 model 字段的值文本（{@code pkg='…', uid=…}）解析，
 *       这里先按「接口型字段」定位 model 字段，失败再全字段扫描，保证跨版本可用。</li>
 * </ul>
 *
 * <p>定位方式：固定类名 {@code com.miui.dock.drag.DockShortCutMenu}（该类只在
 * {@code com.miui.securitycenter:ui} 进程加载，其他进程 loadClass 失败 → 0 hook，安全）。</p>
 */
final class SidebarShortcut {

    private static final String TAG = "Camellia-SidebarSC";
    /** 侧边栏长按菜单（手机管家 :ui 进程）。 */
    private static final String MENU_CLS = "com.miui.dock.drag.DockShortCutMenu";

    /**
     * 点按快捷方式时的「收起菜单」广播：与原版 a33 同款。
     * 另有主手段 {@code menu.getParent().callOnClick()}（点父容器即关闭菜单，见原版 a33.a）。
     */
    private static final String CLOSE_ACTION = "miui.intent.action.INPUT_METHOD_VISIBLE_HEIGHT_CHANGED";

    /** ShortcutQuery 位标志：manifest|dynamic|pinned|cached（原版写死 0xf）。 */
    private static final int QUERY_FLAGS = 0xF;

    // ── 外观（均为 dp / sp，数值取自原版）──
    private static final int C_BG_DARK = 0xFF212327;
    private static final int C_BG_LIGHT = 0xFFFFFFFF;
    private static final int C_TX_DARK = 0xFFEEEEEE;
    private static final int C_TX_LIGHT = 0xFF000000;
    private static final int ICON_DP = 28;
    private static final int ICON_GAP_DP = 12;
    private static final int PANEL_RADIUS_DP = 18;
    private static final int PANEL_PAD_DP = 8;
    private static final int ROW_PAD_DP = 6;
    private static final int ROW_RADIUS_DP = 12;
    private static final int GAP_DP = 7;
    private static final float TEXT_SP = 14f;
    private static final int ROW_GRAVITY = Gravity.CENTER_VERTICAL;      // 0x10
    private static final int PANEL_GRAVITY = Gravity.TOP | Gravity.START; // 0x800033

    /** 面板在父容器中的身份标记（避免与 popup 里其他子 View 混淆）。 */
    private static final Object PANEL_TAG = new Object();

    /** 一个侧边栏菜单同一时刻只有一个，故用单例状态即可（与原版 f33.m / j / k 同义）。 */
    private static volatile View sPanel;
    private static volatile View sMenu;
    private static volatile ViewTreeObserver.OnPreDrawListener sListener;

    /** model 字段缓存（菜单类的成员名被混淆，只能按类型形状定位）。 */
    private static volatile Field sModelField;

    /** 置位后下一次 {@link #place} 打一条几何诊断日志（装配时置位，打完自清）。 */
    private static volatile boolean sLogPlace;

    /** 诊断用：setVisibility 命中菜单类的计数（只记前几次）。 */
    private static volatile int sCfgSeen;

    /** 诊断日志文件（:ui 进程本就有该目录写权限）。仅在罕见路径写，每进程有行数上限。 */
    private static final String DBG_FILE = "/sdcard/Android/media/com.hyper.Camellia/sidebar_sc.log";
    private static final AtomicInteger DBG_N = new AtomicInteger();

    private SidebarShortcut() {
    }

    /** 诊断日志：既进 logcat，也落盘一份（便于无电脑时排查）。 */
    private static void dbg(String msg) {
        Unlock.lg(4, TAG, msg);
        if (DBG_N.incrementAndGet() > 200) {
            return;
        }
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(DBG_FILE, true), "UTF-8");
            w.write(Unlock.proc() + " " + System.currentTimeMillis() + " " + msg + "\n");
        } catch (Throwable ignored) {
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ───────────────────────────── 安装 ─────────────────────────────

    // ───────────────────── 诊断探针（发布时把 SNIFF 置 false 即可整体关闭） ─────────────────────

    /** 诊断构建开启：嗅探 View.setVisibility，逼迫出「菜单真实类名 + 宿主进程」。 */
    private static final boolean SNIFF = true;
    private static final AtomicInteger SNIFF_N = new AtomicInteger();
    private static final AtomicInteger SNIFF_LOG = new AtomicInteger();
    private static volatile boolean sSniffDone;

    private static void sniffInstall() {
        if (!SNIFF || sSniffDone) return;
        sSniffDone = true;
        final String proc = Unlock.proc();
        if (proc == null) return;
        if (!(proc.contains("securitycenter") || proc.contains("systemui") || "system".equals(proc))) {
            return;
        }
        Method m = null;
        try {
            m = View.class.getDeclaredMethod("setVisibility", int.class);
        } catch (Throwable ignored) {
        }
        if (m != null && Unlock.hookMethod(null, m, new Unlock.SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Unlock.Rule r) throws Throwable {
                if (SNIFF_N.incrementAndGet() > 4000) return chain.proceed();
                Object self = chain.getThisObject();
                if (self != null) {
                    String cn = self.getClass().getName();
                    String lc = cn.toLowerCase(Locale.ROOT);
                    if (lc.contains("dock") || lc.contains("sidebar") || lc.contains("shortcut")
                            || (lc.contains("menu") && lc.startsWith("com.miui."))) {
                        if (SNIFF_LOG.incrementAndGet() <= 12) {
                            dbg("sniff " + proc + " " + cn + " vis=" + chain.getArg(0) + " " + frames());
                        }
                    }
                }
                return chain.proceed();
            }
        })) {
            dbg("sniff ok proc=" + proc);
        } else {
            dbg("sniff 安装失败 proc=" + proc);
        }
    }

    /** 诊断：调用栈里的前几个业务帧（看是谁在操作这个 View）。 */
    private static String frames() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (c.startsWith("com.hyper.Camellia") || c.startsWith("java.lang.Thread")
                        || c.startsWith("io.github.libxposed")) continue;
                sb.append('|').append(c).append('#').append(e.getLineNumber());
                if (++n >= 6) break;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 诊断：类的方法表摘要（方法名/参数个数）。 */
    private static String briefMethods(Class<?> c) {
        try {
            Method[] ms = c.getDeclaredMethods();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Method m : ms) {
                if (n++ > 40) {
                    sb.append("...");
                    break;
                }
                sb.append(m.getName()).append('/').append(m.getParameterCount()).append(' ');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    static int install(Unlock.Rule rule, Unlock.Sp sp, ClassLoader loader) {
        final ClassLoader cl = loader != null ? loader : SidebarShortcut.class.getClassLoader();
        Class<?> menu = load(MENU_CLS, cl);
        dbg("probe proc=" + Unlock.proc() + " menuCls=" + (menu != null)
                + (menu != null ? " methods=" + briefMethods(menu) : ""));
        sniffInstall();
        if (menu == null) {
            dbg("skip 菜单类不在本进程 proc=" + Unlock.proc() + " " + MENU_CLS);
            return 0;
        }
        final Class<?> menuCls = menu;
        int hits = 0;
        StringBuilder anchors = new StringBuilder();

        // ① 菜单自身的「装配列表」方法（与可用规则 SidebarFreeform 同锚点）：菜单每弹一次必被调用。
        Method fill = SidebarFreeform.findListBuilder(menu);
        if (fill != null && Unlock.hookMethod(rule, fill, new Unlock.SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Unlock.Rule r) throws Throwable {
                Object out = chain.proceed();
                if (!Unlock.cfg(r)) return out;
                Object self = chain.getThisObject();
                if (self instanceof View) { dbg("hit fill proc=" + Unlock.proc() + " " + self.getClass().getName()); trigger((View) self, cl, "fill"); }
                return out;
            }
        })) {
            hits++;
            anchors.append(" fill=").append(fill.getName());
        }

        // ② 菜单展示入口 f(La8/i)。
        Method show = SidebarFreeform.findSingleArg(menu, "f");
        if (show != null && Unlock.hookMethod(rule, show, new Unlock.SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Unlock.Rule r) throws Throwable {
                Object out = chain.proceed();
                if (!Unlock.cfg(r)) return out;
                Object self = chain.getThisObject();
                if (self instanceof View) { dbg("hit show proc=" + Unlock.proc() + " " + self.getClass().getName()); trigger((View) self, cl, "show"); }
                return out;
            }
        })) {
            hits++;
            anchors.append(" show=").append(show.getName());
        }

        // ③ 样本 APK 的锚点：菜单 setLayoutParams(FrameLayout$LayoutParams)（弹入动画每帧都会调）。
        Method setLp = null;
        try {
            setLp = View.class.getDeclaredMethod("setLayoutParams", ViewGroup.LayoutParams.class);
        } catch (Throwable ignored) {
        }
        if (setLp != null && Unlock.hookMethod(rule, setLp, new Unlock.SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Unlock.Rule r) throws Throwable {
                Object self = chain.getThisObject();
                if (self == null || !menuCls.getName().equals(self.getClass().getName())) return chain.proceed();
                if (!(chain.getArg(0) instanceof FrameLayout.LayoutParams)) return chain.proceed();
                Object out = chain.proceed();
                if (!Unlock.cfg(r)) return out;
                trigger((View) self, cl, "setLayoutParams");
                return out;
            }
        })) {
            hits++;
            anchors.append(" setLayoutParams");
        }

        // ④ 菜单 setVisibility：显示时装配、隐藏时移除。
        Method setVis = null;
        try {
            setVis = View.class.getDeclaredMethod("setVisibility", int.class);
        } catch (Throwable t) {
            Unlock.lg(5, TAG, "定位 View.setVisibility 失败: " + t);
        }
        if (setVis != null && Unlock.hookMethod(rule, setVis, new Unlock.SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Unlock.Rule r) throws Throwable {
                Object self = chain.getThisObject();
                // 一次引用比较过掉进程内所有其他 View 的 setVisibility（热点方法，必须极轻）
                if (self == null || !menuCls.getName().equals(self.getClass().getName())) return chain.proceed();
                Object a0 = chain.getArg(0);   // 参数须在 proceed 之前读
                int vis = a0 instanceof Integer ? (Integer) a0 : -1;
                Object out = chain.proceed();
                if (sCfgSeen++ < 3) {
                    dbg("菜单 setVisibility vis=" + vis + " cfg=" + Unlock.cfg(r)
                            + " " + self.getClass().getSimpleName());
                }
                if (!Unlock.cfg(r)) return out;
                View menuView = (View) self;
                if (vis == View.VISIBLE) trigger(menuView, cl, "setVisibility(0)");
                else detach(menuView, "setVisibility(" + vis + ")");
                return out;
            }
        })) {
            hits++;
            anchors.append(" setVisibility");
        }

        if (hits == 0) {
            dbg("所有锚点 hook 均失败，规则不生效");
            return 0;
        }
        rule.where = "侧边栏长按菜单附加「应用快捷方式」面板（" + MENU_CLS + "）";
        dbg("install hits=" + hits + anchors + " menu=" + menu.getName());
        return 1;
    }

    // ───────────────────────────── 生命周期 ─────────────────────────────

    /**
     * 菜单弹出锚点。菜单刚被装配/展示时布局未必就绪，故不要求此刻就是 VISIBLE：
     * 先 <b>post 到消息队列尾部</b>，未就绪则每 60ms 再试，最多 7 拍（≈360ms，覆盖弹入动画）。
     */
    private static void trigger(final View menu, final ClassLoader cl, final String why) {
        if (menu == null) return;
        if (why != null && why.startsWith("setVisibility")) {
            dbg("trigger " + why + " vis=" + menu.getVisibility()
                    + " " + menu.getWidth() + "x" + menu.getHeight()
                    + " scale=" + menu.getScaleX() + " chain=" + chainOf(menu));
        }
        attempt(menu, cl, why, 0);
    }

    private static void attempt(final View menu, final ClassLoader cl, final String why, final int n) {
        try {
            menu.post(new Runnable() {
                @Override
                public void run() {
                    int st;
                    try {
                        st = ensure(menu, cl, why, n);
                    } catch (Throwable t) {
                        dbg("ensure 异常(" + why + " #" + n + "): " + t);
                        return;
                    }
                    if (st == 0 && n < 6) {
                        menu.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                attempt(menu, cl, why, n + 1);
                            }
                        }, 60);
                    }
                }
            });
        } catch (Throwable t) {
            dbg("post 失败: " + t);
        }
    }

    // ───────────────────────────── 屏蔽关键词 ─────────────────────────────
    // 设置页「侧边栏快捷方式面板」下有两条字符串配置：
    //   ul_sidebar_sc_block     全局屏蔽关键词，形如「签到,领取」
    //   ul_sidebar_sc_block_app 应用专属屏蔽，形如「淘宝 = 签到,领券」（分号或换行分隔多条）
    // 命中判据：快捷方式标题或 id 含关键词（忽略大小写的子串匹配）→ 该条目不进面板。
    // 关键词只影响「显示」，不改变快捷方式本身；全部被屏蔽时干脆不弹面板。

    /**
     * 快捷方式标题：与 {@link #build} 渲染用同一份取值链（短标题 → 长标题 → id）。
     */
    private static String labelOf(ShortcutInfo info) {
        return ShortcutBlock.labelOf(info);
    }

    /**
     * 按关键词过滤条目。无规则时原样返回（零开销：两次内存查表 + 空判断）。
     */
    private static List<ShortcutInfo> applyBlock(List<ShortcutInfo> items, Context ctx, String pkg) {
        List<ShortcutInfo> all = items;
        if (all == null || all.isEmpty()) return all;
        // 关键词语义在 ShortcutBlock 里统一实现：桌面 Launcher 菜单用的是同一套规则
        List<String> kws = ShortcutBlock.rules(
                Unlock.cfgText(Unlock.SIDEBAR_SC_BLOCK_KEY),
                Unlock.cfgText(Unlock.SIDEBAR_SC_BLOCK_APP_KEY),
                pkg, ShortcutBlock.appLabelOf(ctx, pkg));
        if (kws.isEmpty()) return all;
        List<String> blocked = new ArrayList<String>();
        List<ShortcutInfo> out = ShortcutBlock.filter(all, kws, LauncherShortcut.ID, blocked);
        for (String s : blocked) {
            dbg("屏蔽快捷方式 pkg=" + pkg + " " + s);
        }
        return out;
    }

    /**
     * 装配并贴合面板。
     *
     * @return 0=尚未就绪（可重试）、1=已完成、2=已放弃（不再重试）
     */
    private static int ensure(View menu, ClassLoader cl, String why, int n) {
        if (menu.getVisibility() != View.VISIBLE) {
            if (n == 0 || n >= 6) {
                dbg("ensure 菜单不可见 vis=" + menu.getVisibility() + " n=" + n + "(" + why + ")");
            }
            return n < 6 ? 0 : 2;
        }
        FrameLayout parent = parentOf(menu);
        if (parent == null) {
            if (n == 0 || n >= 6) {
                dbg("ensure 无 FrameLayout 容器: " + chainOf(menu) + " n=" + n + "(" + why + ")");
            }
            return n < 6 ? 0 : 2;
        }
        if (n == 0) {
            dbg("ensure 容器=" + parent.getClass().getName()
                    + " direct=" + (menu.getParent() == parent) + "(" + why + ")");
        }

        View panel = findPanel(parent);
        if (panel == null) {
            Context ctx = menu.getContext();
            if (ctx == null) {
                dbg("菜单无 Context(" + why + ")");
                return 2;
            }
            Target who = targetOf(menu);
            if (who == null) {
                if (n < 3) return 0;   // 菜单可能还在填 model 字段，再等一拍
                dbg("未能从菜单反推目标应用，放弃(" + why + ") 字段=" + dumpFields(menu));
                return 2;
            }
            String pkg = who.pkg;
            if (pkg == null || pkg.isEmpty()) {
                dbg("解析到空包名 raw=" + who.raw + "(" + why + ")");
                return 2;
            }

            List<ShortcutInfo> items = shortcuts(ctx, pkg, who.uid);
            if (items == null || items.isEmpty()) {
                dbg("应用无 Launcher 快捷方式 pkg=" + pkg + " uid=" + who.uid
                        + " raw=" + who.raw + "(" + why + ")");
                return 2;
            }
            // 屏蔽关键词在「取到条目之后、建面板之前」生效：
            // 过滤后为空说明该应用整体被屏蔽，此时不弹面板（否则会留一个空壳）。
            int total = items.size();
            items = applyBlock(items, ctx, pkg);
            if (items.isEmpty()) {
                dbg("快捷方式全部被屏蔽 pkg=" + pkg + " 原始条目=" + total + "(" + why + ")");
                return 2;
            }
            panel = build(menu, parent, ctx, cl, pkg, who.uid, items);
            if (panel == null) {
                dbg("面板构建失败 pkg=" + pkg + "(" + why + ")");
                return 2;
            }
            dbg("已挂载面板 pkg=" + pkg + " uid=" + who.uid + " 条目=" + items.size()
                    + (total > items.size() ? "/" + total + " 已屏蔽=" + (total - items.size()) : "")
                    + "(" + why + ")");
        }

        FrameLayout.LayoutParams lp = lpOf(panel);
        if (lp == null) {
            dbg("面板 LayoutParams 不是 FrameLayout.LayoutParams(" + why + ")");
            return 2;
        }
        sLogPlace = true;
        place(panel, menu, lp);
        panel.setLayoutParams(lp);
        sPanel = panel;
        sMenu = menu;
        attach(menu, panel, lp);
        return 1;
    }

    /** 菜单变为不可见/被移除：拆掉面板与预绘制监听。 */
    private static void detach(View menu, String why) {
        View menu0 = sMenu;
        if (menu0 != null && menu != null && menu0 != menu) return; // 不是当前那个菜单
        if (sPanel != null) dbg("移除面板(" + why + ")");
        View panel = sPanel;
        sPanel = null;
        sMenu = null;
        ViewTreeObserver.OnPreDrawListener l = sListener;
        sListener = null;
        if (l != null && menu != null) {
            try {
                menu.getViewTreeObserver().removeOnPreDrawListener(l);
            } catch (Throwable ignored) {
            }
        }
        if (panel != null) {
            ViewParent p = panel.getParent();
            if (p instanceof ViewGroup) {
                try {
                    ((ViewGroup) p).removeView(panel);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 预绘制监听：菜单每帧改变尺寸/缩放（弹入动画、横竖屏）时重新贴合。 */
    private static void attach(final View menu, final View panel, final FrameLayout.LayoutParams lp) {
        if (sListener != null && sMenu == menu && sPanel == panel) return; // 已挂
        ViewTreeObserver.OnPreDrawListener old = sListener;
        if (old != null) {
            try {
                menu.getViewTreeObserver().removeOnPreDrawListener(old);
            } catch (Throwable ignored) {
            }
        }
        ViewTreeObserver.OnPreDrawListener real = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                try {
                    if (panel.getParent() == null) return true;
                    if (menu.getVisibility() != View.VISIBLE) {   // 菜单已被收起：自愈式拆除
                        detach(menu, "preDraw:菜单不可见");
                        return true;
                    }
                    place(panel, menu, lp);
                    panel.setLayoutParams(lp);
                } catch (Throwable ignored) {
                }
                return true;
            }
        };
        try {
            menu.getViewTreeObserver().addOnPreDrawListener(real);
            sListener = real;
        } catch (Throwable t) {
            Unlock.lg(5, TAG, "注册预绘制监听失败: " + t);
        }
    }

    // ───────────────────────────── 面板构建 ─────────────────────────────

    private static View build(View menu, FrameLayout parent, Context ctx, ClassLoader cl,
                              String pkg, int uid, List<ShortcutInfo> items) {
        boolean dark = isNight();
        LauncherApps la = null;
        try {
            la = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        } catch (Throwable ignored) {
        }
        UserHandle user = uid >= 0 ? UserHandle.getUserHandleForUid(uid) : Process.myUserHandle();

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? C_BG_DARK : C_BG_LIGHT);
        bg.setCornerRadius(dp(ctx, PANEL_RADIUS_DP));
        panel.setBackground(bg);
        int pad = dp(ctx, PANEL_PAD_DP);
        panel.setPadding(pad, pad, pad, pad);
        // 跟随菜单的缩放（侧边栏菜单弹出时会做缩放动画）
        panel.setScaleX(menu.getScaleX());
        panel.setScaleY(menu.getScaleY());

        int iconSize = dp(ctx, ICON_DP);
        int dpi = 160;
        try {
            dpi = ctx.getResources().getDisplayMetrics().densityDpi;
        } catch (Throwable ignored) {
        }

        for (ShortcutInfo info : items) {
            if (info == null) continue;
            // 与屏蔽匹配共用同一份标题取值链（短标题 → 长标题 → id）
            CharSequence label = labelOf(info);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(ROW_GRAVITY);
            int rp = dp(ctx, ROW_PAD_DP);
            row.setPadding(rp, rp, rp, rp);
            row.setClickable(true);
            row.setFocusable(true);
            GradientDrawable rbg = new GradientDrawable();
            rbg.setCornerRadius(dp(ctx, ROW_RADIUS_DP));
            rbg.setColor(0); // 透明（原版同款，按压态由系统默认前景处理）
            row.setBackground(rbg);

            // 图标
            Drawable icon = null;
            if (la != null) {
                try {
                    icon = la.getShortcutIconDrawable(info, dpi);
                } catch (Throwable ignored) {
                }
            }
            if (icon != null) {
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(icon);
                iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                LinearLayout.LayoutParams ilp =
                        new LinearLayout.LayoutParams(iconSize, iconSize);
                ilp.setMarginEnd(dp(ctx, ICON_GAP_DP));
                row.addView(iv, ilp);
            }

            // 标题
            TextView tv = new TextView(ctx);
            tv.setText(label);
            tv.setTextColor(dark ? C_TX_DARK : C_TX_LIGHT);
            tv.setTextSize(TEXT_SP);
            tv.setGravity(ROW_GRAVITY);
            tv.setTypeface(null, Typeface.BOLD);
            row.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 点击：先收菜单，再以小窗启动快捷方式
            row.setOnClickListener(new RowClick(parent, ctx, cl, pkg, user, la, info));

            panel.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        panel.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = PANEL_GRAVITY;
        panel.setTag(PANEL_TAG);
        place(panel, menu, lp);
        try {
            parent.addView(panel, lp);
        } catch (Throwable t) {
            Unlock.lg(5, TAG, "加入面板失败: " + t);
            return null;
        }
        return panel;
    }

    /** 快捷方式行点击：收起菜单 → 小窗（或普通）启动。 */
    private static final class RowClick implements View.OnClickListener {
        private final View frame;
        private final Context ctx;
        private final ClassLoader cl;
        private final String pkg;
        private final UserHandle user;
        private final LauncherApps la;
        private final ShortcutInfo info;

        RowClick(View frame, Context ctx, ClassLoader cl, String pkg,
                 UserHandle user, LauncherApps la, ShortcutInfo info) {
            this.frame = frame;
            this.ctx = ctx;
            this.cl = cl;
            this.pkg = pkg;
            this.user = user;
            this.la = la;
            this.info = info;
        }

        @Override
        public void onClick(View v) {
            // ① 收起菜单：点父容器（原版 a33.a.callOnClick()）+ 兼容广播（原版同款）
            try {
                if (frame != null) frame.callOnClick();
            } catch (Throwable ignored) {
            }
            try {
                Intent b = new Intent(CLOSE_ACTION);
                b.setPackage(ctx.getPackageName());
                ctx.sendBroadcast(b);
            } catch (Throwable ignored) {
            }

            // 原版 a33：小窗参数取自「快捷方式目标组件」，取不到再退回按包名（本项目链路）
            Bundle opts = smallWindowOptions(ctx, cl, pkg, info);

            // ② 有 launchIntent（动态快捷方式）→ 直接走本项目已验证的小窗链路
            Intent intent = null;
            try {
                intent = info.getIntent();
            } catch (Throwable ignored) {
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Unlock.lg(4, TAG, "快捷方式启动(小窗) pkg=" + pkg + " id=" + info.getId()
                        + " opts=" + (opts != null));
                try {
                    if (opts != null) ctx.startActivity(intent, opts);
                    else ctx.startActivity(intent);
                    return;
                } catch (Throwable t) {
                    Unlock.lg(5, TAG, "直接启动失败，改走 startShortcut: " + t);
                }
            }

            // ③ 清单/固定快捷方式没有 Intent → 交给 LauncherApps（原版链路）
            if (la != null) {
                try {
                    la.startShortcut(pkg, info.getId(), null, opts, user);
                    Unlock.lg(4, TAG, "快捷方式启动(startShortcut) pkg=" + pkg + " id=" + info.getId());
                    return;
                } catch (Throwable t) {
                    Unlock.lg(5, TAG, "startShortcut 失败: " + t);
                }
            }

            // ④ 兜底：启动应用主入口（小窗）
            try {
                Intent m = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (m != null) {
                    m.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (opts != null) ctx.startActivity(m, opts);
                    else ctx.startActivity(m);
                }
            } catch (Throwable t) {
                Unlock.lg(5, TAG, "兜底启动失败: " + t);
            }
        }
    }

    // ───────────────────────────── 小窗启动参数（原版 a33）─────────────────────────────

    /** 小窗参数：优先按快捷方式目标组件取（原版 {@code MiuiMultiWindowUtils#getActivityOptions(ctx, cn)}）。 */
    private static Bundle smallWindowOptions(Context ctx, ClassLoader cl, String pkg, ShortcutInfo info) {
        try {
            ComponentName cn = targetComponent(ctx, info);
            if (cn != null) {
                Bundle b = miuiOptions(ctx, cl, cn);
                if (b != null) {
                    Unlock.lg(4, TAG, "小窗参数(组件) " + cn.flattenToShortString());
                    return b;
                }
            }
        } catch (Throwable t) {
            Unlock.lg(4, TAG, "按组件取小窗参数失败: " + t);
        }
        try {
            return SecurityDiffFeatures.smallWindowOptions(cl, ctx, pkg);
        } catch (Throwable t) {
            Unlock.lg(5, TAG, "构造小窗参数失败 pkg=" + pkg + ": " + t);
            return null;
        }
    }

    /** 快捷方式真实落点（原版 a33：{@code intent.getComponent()}，为空则 resolveActivity）。 */
    private static ComponentName targetComponent(Context ctx, ShortcutInfo info) {
        Intent i = info.getIntent();
        if (i == null) return null;
        ComponentName cn = i.getComponent();
        if (cn == null) {
            try {
                cn = i.resolveActivity(ctx.getPackageManager());
            } catch (Throwable ignored) {
            }
        }
        return cn;
    }

    /** {@code android.util.MiuiMultiWindowUtils#getActivityOptions(Context, ComponentName)} → Bundle。 */
    private static Bundle miuiOptions(Context ctx, ClassLoader cl, ComponentName cn) {
        Class<?> u;
        try {
            u = Class.forName("android.util.MiuiMultiWindowUtils", false, cl);
        } catch (Throwable t) {
            Unlock.lg(4, TAG, "MiuiMultiWindowUtils 不存在: " + t);
            return null;
        }
        try {
            Method m = u.getDeclaredMethod(
                    "getActivityOptions", Context.class, ComponentName.class);
            m.setAccessible(true);
            Object o = m.invoke(null, ctx, cn);
            if (o instanceof ActivityOptions) return ((ActivityOptions) o).toBundle();
        } catch (Throwable t) {
            Unlock.lg(4, TAG, "MiuiMultiWindowUtils(Context,ComponentName) 不可用: " + t);
        }
        return null;
    }

    // ───────────────────────────── 面板定位（原版 f33.F0）─────────────────────────────

    /**
     * 把面板贴到菜单旁边：水平方向按菜单中心是否越过父容器中线决定「右对齐」还是「左对齐」，
     * 垂直方向默认与菜单顶部对齐，下方放不下则改为上移（pivot 同步调整，保证缩放朝向正确）。
     */
    private static void place(View panel, View menu, FrameLayout.LayoutParams lp) {
        try {
            panel.setScaleX(menu.getScaleX());
            panel.setScaleY(menu.getScaleY());
            FrameLayout parent = parentOf(menu);
            if (parent == null) {
                if (sLogPlace) {
                    sLogPlace = false;
                    dbg("place 无容器 " + chainOf(menu));
                }
                return;
            }
            int menuW = menu.getWidth();
            int hide = menu.getHeight();
            int panelW = panel.getMeasuredWidth();
            int panelH = panel.getMeasuredHeight();
            int gap = dp(menu.getContext(), GAP_DP);
            FrameLayout.LayoutParams mlp = menu.getLayoutParams() instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) menu.getLayoutParams() : null;
            boolean direct = menu.getParent() == parent;
            int left;
            int top;
            int center;
            if (direct && mlp != null) {
                // 原版算法：容器就是菜单的直接父级，直接用 margin 计算
                center = mlp.leftMargin + menuW / 2;
                if (center > parent.getWidth() / 2) {
                    left = mlp.leftMargin + menuW - panelW - gap;
                    panel.setPivotX(panelW);
                } else {
                    left = mlp.leftMargin + gap;
                    panel.setPivotX(0f);
                }
                top = mlp.topMargin + (int) ((hide * (menu.getScaleY() + 1.0f)) / 2.0f);
                if (parent.getHeight() - top < panelH) {
                    panel.setPivotY(panelH);
                    top = mlp.topMargin - panelH;
                } else {
                    panel.setPivotY(0f);
                }
            } else {
                // 容器是菜单的祖先（非直接父级）：按屏幕坐标折算成该容器的 margin
                int[] ml = new int[2];
                int[] pl = new int[2];
                menu.getLocationOnScreen(ml);
                parent.getLocationOnScreen(pl);
                center = ml[0] + menuW / 2;
                if (center > pl[0] + parent.getWidth() / 2) {
                    left = ml[0] + menuW - panelW - gap - pl[0] - parent.getPaddingLeft();
                    panel.setPivotX(panelW);
                } else {
                    left = ml[0] + gap - pl[0] - parent.getPaddingLeft();
                    panel.setPivotX(0f);
                }
                top = ml[1] + (int) ((hide * (menu.getScaleY() + 1.0f)) / 2.0f)
                        - pl[1] - parent.getPaddingTop();
                if (pl[1] + parent.getHeight() - (ml[1] + (hide * (menu.getScaleY() + 1.0f)) / 2.0f)
                        < panelH) {
                    panel.setPivotY(panelH);
                    top = ml[1] - panelH - pl[1] - parent.getPaddingTop();
                } else {
                    panel.setPivotY(0f);
                }
            }
            if (left < 0) left = 0;
            if (top < 0) top = 0;
            lp.leftMargin = left;
            lp.topMargin = top;
            if (sLogPlace) {
                sLogPlace = false;
                int[] ml = new int[2];
                int[] pl = new int[2];
                menu.getLocationOnScreen(ml);
                parent.getLocationOnScreen(pl);
                dbg("place direct=" + direct
                        + " menu=" + menuW + "x" + hide + "@" + ml[0] + "," + ml[1]
                        + " parent=" + parent.getClass().getName() + "@" + pl[0] + "," + pl[1]
                        + " " + parent.getWidth() + "x" + parent.getHeight()
                        + " panel=" + panelW + "x" + panelH
                        + " lp=" + lp.leftMargin + "," + lp.topMargin);
            }
        } catch (Throwable t) {
            Unlock.lg(5, TAG, "面板定位失败: " + t);
        }
    }

    // ───────────────────────────── 数据 ─────────────────────────────

    /** 快捷方式来源：优先 ShortcutManager 私有服务（原版链路），失败回退 LauncherApps。 */
    private static List<ShortcutInfo> shortcuts(Context ctx, String pkg, int uid) {
        // 原版：userId = uid / 100000（工作资料/多用户）；取不到 uid 时只试 0
        int fromUid = uid >= 0 ? uid / 100000 : 0;
        List<Integer> cand = new ArrayList<Integer>();
        cand.add(fromUid);
        if (fromUid != 0) cand.add(0);
        for (int userId : cand) {
            List<ShortcutInfo> l = queryShortcuts(ctx, pkg, userId, uid);
            if (l != null && !l.isEmpty()) return l;
        }
        return new ArrayList<ShortcutInfo>();
    }

    /** 单个 userId 下取快捷方式：① ShortcutManager 私有服务（原版链路）→ ② LauncherApps。 */
    private static List<ShortcutInfo> queryShortcuts(Context ctx, String pkg, int userId, int uid) {
        // ① 原版链路：ShortcutManager.mService#getShortcuts(pkg, flags, userId) → ParceledListSlice
        try {
            ShortcutManager mgr = (ShortcutManager) ctx.getSystemService(Context.SHORTCUT_SERVICE);
            if (mgr != null) {
                Field f = mgr.getClass().getDeclaredField("mService");
                f.setAccessible(true);
                Object svc = f.get(mgr);
                if (svc != null) {
                    Method m = null;
                    for (Method c : svc.getClass().getMethods()) {
                        if (!"getShortcuts".equals(c.getName())) continue;
                        Class<?>[] pt = c.getParameterTypes();
                        if (pt.length == 3 && pt[0] == String.class
                                && pt[1] == int.class && pt[2] == int.class) {
                            m = c;
                            break;
                        }
                    }
                    if (m != null) {
                        m.setAccessible(true);
                        Object slice = m.invoke(svc, pkg, QUERY_FLAGS, userId);
                        List<ShortcutInfo> l = listOf(slice);
                        if (l != null) return l;
                    }
                }
            }
        } catch (Throwable t) {
            Unlock.lg(4, TAG, "ShortcutManager.mService 不可用，回退 LauncherApps: " + t);
        }

        // ② 回退：LauncherApps（需要调用者身份被系统认可，能成功就用）
        try {
            LauncherApps la = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (la != null) {
                LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
                q.setPackage(pkg);
                q.setQueryFlags(QUERY_FLAGS);
                UserHandle u = uid >= 0
                        ? UserHandle.getUserHandleForUid(uid)
                        : (userId > 0
                                ? UserHandle.getUserHandleForUid(userId * 100000)
                                : Process.myUserHandle());
                List<ShortcutInfo> l = la.getShortcuts(q, u);
                if (l != null) return l;
            }
        } catch (Throwable t) {
            Unlock.lg(4, TAG, "LauncherApps.getShortcuts 不可用: " + t);
        }
        return new ArrayList<ShortcutInfo>();
    }

    /** 从 ParceledListSlice 取 List<ShortcutInfo>（getList() 在各版本都是 public）。 */
    @SuppressWarnings("unchecked")
    private static List<ShortcutInfo> listOf(Object slice) {
        if (slice == null) return null;
        if (slice instanceof List) return (List<ShortcutInfo>) slice;
        try {
            Method getList = slice.getClass().getMethod("getList");
            getList.setAccessible(true);
            Object l = getList.invoke(slice);
            if (l instanceof List) return (List<ShortcutInfo>) l;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 从菜单反推目标应用：{@code {pkg, uid}}（uid 取不到时为 -1）。
     *
     * <p>与原版 {@code f33.C} 一致：菜单里那个「类型是接口、声明方法数 == 2」的字段
     * 就是菜单 model（本机 {@code La8/c}）。该对象自身没有可读文本，AppInfo 在它的字段里
     * （本机 {@code a8.c.a : Lz7/c}），{@code toString()} 形如
     * {@code AppInfo{uid=10123, pkg='com.xx', activity='…', from='…'}} —— 所以要钻两层。</p>
     */
    private static Target targetOf(View menu) {
        Field cached = sModelField;
        if (cached != null) {
            Target r = deepParse(readField(cached, menu));
            if (r != null) return r;
            sModelField = null; // 缓存失效，重新找
        }
        // ① 主判据（原版 f33.C）：菜单里「类型是接口、声明方法数 == 2」的字段 = 菜单 model（host，本机 La8/c）
        for (Class<?> c = menu.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().isInterface()) continue;
                int n = 0;
                try {
                    for (Method m : f.getType().getDeclaredMethods()) n++;
                } catch (Throwable ignored) {
                    continue;
                }
                if (n != 2) continue;
                Target r = deepParse(readField(f, menu));
                if (r != null) {
                    f.setAccessible(true);
                    sModelField = f;
                    return r;
                }
            }
        }
        // ② 兜底：菜单任意非静态字段（同样往下钻一层）
        for (Class<?> c = menu.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Target r = deepParse(readField(f, menu));
                if (r != null) {
                    f.setAccessible(true);
                    sModelField = f;
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * 两层解析：先看对象自身的 {@code toString()}，再看它每个非静态字段。
     *
     * <p>原版 {@code f33.k} 拿到的菜单 model（本机 {@code La8/c}）本身没有可读文本，
     * AppInfo 藏在它的字段里（本机 {@code a8.c.a : Lz7/c}），只有钻一层才能拿到
     * {@code AppInfo{uid=10123, pkg='com.xx', activity='…', from='…'}}。</p>
     */
    private static Target deepParse(Object v) {
        Target r = parse(v);
        if (r != null) return r;
        if (v == null) return null;
        if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean) return null;
        for (Class<?> c = v.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Target t = parse(readField(f, v));
                if (t != null) return t;
            }
        }
        return null;
    }

    private static Object readField(Field f, Object target) {
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 目标应用（菜单 model 字段反推结果）。 */
    private static final class Target {
        final String pkg;
        final int uid;
        final String raw;

        Target(String pkg, int uid, String raw) {
            this.pkg = pkg;
            this.uid = uid;
            this.raw = raw;
        }
    }

    /**
     * 解析 {@code AppInfo{uid=10123, pkg='com.x', activity='…', from='…'}} 这类文本。
     *
     * <p>至少要能取到合法包名；uid 取不到时为 {@code -1}（后面按 0 号用户试）。</p>
     */
    private static Target parse(Object v) {
        if (v == null) return null;
        String s;
        try {
            s = String.valueOf(v);
        } catch (Throwable ignored) {
            return null;
        }
        if (s == null || s.isEmpty() || s.length() > 8192) return null;
        String pkg = null;
        if (s.contains("pkg='") || s.contains("pkg=\"")) {
            pkg = between(s, "pkg='", "'");
            if (pkg == null) pkg = between(s, "pkg=\"", "\"");
        }
        if (!validPkg(pkg)) pkg = pkgToken(s);
        if (!validPkg(pkg)) return null;
        int uid = -1;
        String us = between(s, "uid=", ",");
        if (us == null) us = between(s, "uid=", "}");
        if (us != null) {
            try {
                uid = Integer.parseInt(us.trim());
            } catch (Throwable ignored) {
            }
        }
        if (uid < 0) {
            String vs = between(s, "userId=", ",");
            if (vs != null) {
                try {
                    int userId = Integer.parseInt(vs.trim());
                    if (userId >= 0 && userId < 1000) uid = userId * 100000;
                } catch (Throwable ignored) {
                }
            }
        }
        return new Target(pkg, uid, s);
    }

    /** 合法包名：非空、含点、只由包名字符组成。 */
    private static boolean validPkg(String p) {
        if (p == null || p.length() < 3 || p.indexOf('.') <= 0) return false;
        for (int i = 0; i < p.length(); i++) {
            if (!isPkgChar(p.charAt(i))) return false;
        }
        return true;
    }

    /** 裸 {@code pkg=com.x} / {@code packageName=com.x} 形式的兜底。 */
    private static String pkgToken(String s) {
        String[] keys = {"pkg=", "packageName=", "package="};
        for (String k : keys) {
            int i = s.indexOf(k);
            while (i >= 0) {
                int j = i + k.length();
                if (j < s.length() && (s.charAt(j) == '\'' || s.charAt(j) == '"')) j++;
                int e = j;
                while (e < s.length() && isPkgChar(s.charAt(e))) e++;
                if (e > j) {
                    String p = s.substring(j, e);
                    if (validPkg(p)) return p;
                }
                i = s.indexOf(k, i + k.length());
            }
        }
        return null;
    }

    private static boolean isPkgChar(char ch) {
        return ch == '.' || ch == '_' || (ch >= '0' && ch <= '9')
                || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return null;
        i += a.length();
        int j = s.indexOf(b, i);
        if (j < 0) j = s.length();
        return s.substring(i, j).trim();
    }

    // ───────────────────────────── 小工具 ─────────────────────────────

    private static boolean isNight() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            if (thread == null) return false;
            Field f = at.getDeclaredField("mInitialApplication");
            f.setAccessible(true);
            Object app = f.get(thread);
            if (app == null) return false;
            android.content.res.Configuration cf =
                    ((Context) app).getResources().getConfiguration();
            return (cf.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 面板容器：原版取「菜单的直接父容器」（须为 FrameLayout）。
     * 本机菜单外层若被包了一层普通 ViewGroup，直接父容器定位会失败，
     * 故向上找最近的 FrameLayout 祖先（最多 4 层）作为兜底容器。
     */
    private static FrameLayout parentOf(View v) {
        try {
            ViewParent p = v.getParent();
            for (int i = 0; i < 4 && p instanceof View; i++) {
                if (p instanceof FrameLayout) return (FrameLayout) p;
                p = ((View) p).getParent();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 祖先类名链（最多 4 层），仅用于诊断。 */
    private static String chainOf(View v) {
        StringBuilder sb = new StringBuilder();
        try {
            ViewParent p = v.getParent();
            for (int i = 0; i < 4 && p != null; i++) {
                if (i > 0) sb.append("<-");
                sb.append(p.getClass().getName());
                p = (p instanceof View) ? ((View) p).getParent() : null;
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** 打印对象的实例字段（诊断 targetOf 失败用）。 */
    private static String dumpFields(Object o) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : o.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v;
                try {
                    v = f.get(o);
                } catch (Throwable t) {
                    v = "<err>";
                }
                String s = String.valueOf(v);
                if (s.length() > 60) s = s.substring(0, 60) + "…";
                sb.append(" | ").append(f.getName()).append(':')
                        .append(f.getType().getSimpleName()).append('=').append(s);
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static View findPanel(FrameLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c != null && c.getTag() == PANEL_TAG) return c;
        }
        return null;
    }

    private static FrameLayout.LayoutParams lpOf(View v) {
        try {
            ViewGroup.LayoutParams p = v.getLayoutParams();
            return p instanceof FrameLayout.LayoutParams ? (FrameLayout.LayoutParams) p : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int dp(Context ctx, int v) {
        try {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    ctx.getResources().getDisplayMetrics());
        } catch (Throwable ignored) {
            return v * 3;
        }
    }

    private static Class<?> load(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable ignored) {
        }
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
