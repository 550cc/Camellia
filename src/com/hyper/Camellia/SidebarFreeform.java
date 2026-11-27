package com.hyper.Camellia;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 侧边栏「快捷项：小窗」。
 *
 * <p>侧边栏长按应用会弹出 {@code com.miui.dock.drag.DockShortCutMenu}（手机管家 :ui 进程），
 * 原生只有「全屏」与「分屏」两项（13.6.5 上第一项类名 {@code a8/f}、图标 ic_shortcut_fullscreen）。
 * 本规则在菜单装配列表 {@code DockShortCutMenu#c()V} 返回后，向同一个 {@code e:List} 头部插入
 * 一个自定义条目，后续 {@code f(La8/i)} 中的 {@code adapter.G(list)+notifyDataSetChanged()}
 * 会自动把它渲染出来。</p>
 *
 * <p>点击链路（已按 smali 核对，13.6.5）：</p>
 * <pre>
 * ViewHolder 点击 → adapter listener（com.miui.dock.drag.a → a$a = DockShortCutMenu）
 *   → DockShortCutMenu.a(holder,item)          // 菜单先把自己 setVisibility(8) 收起来
 *   → item.c(holder)                            // 基类只埋点；a8/f 覆写里回调 f.a(item)
 *   → La8/m$a#a(La8/m)                          // 菜单 host 提供的回调（w7/f → 原生打开逻辑）
 * </pre>
 * 因此不必碰 MIUI 的 w7/f 分发：注入条目时带一个自造的回调（{@link java.lang.reflect.Proxy}
 * 实现 {@code La8/m$a}），在其 {@code a(La8/m)} 里直接用小窗参数包启动目标应用即可。
 *
 * <p>条目自身表现（图标/标题/可用态）在 {@code a8/m#e(RecyclerView$c0)} 绑定阶段微调：
 * 原生绑定会按 {@code c}(图标资源)/{@code d}(标题资源)/{@code e}(enabled) 渲染，
 * 我们只在自己的条目上把标题改成「小窗」并换成小窗图标（不依赖手机管家里不存在的 freeform 图标）。</p>
 *
 * <p>定位方式：固定类名 + 方法形状（0 参 void 且独占），不依赖内部混淆类名之外的成员名。</p>
 */
final class SidebarFreeform {

    private static final String TAG = "Camellia-SidebarFF";
    private static final String MENU_CLS = "com.miui.dock.drag.DockShortCutMenu";
    /** 条目基类（13.6.5 混淆名 a8.m）：仅作 findBind 的兜底定位，主路径由菜单字段类型反推。 */
    private static final String ITEM_BASE_CLS = "a8.m";
    private static final String SEC_PKG = "com.miui.securitycenter";

    /** 注入条目的标记：写在条目的 {@code a:String}（tag）字段上，用于判重与绑定识别。 */
    private static final String TAG_ITEM = "CamelliaFreeform";
    private static final String LABEL = "小窗";

    /** 标题字符串候选（手机管家资源名）：track_side_bar_state_freeform = "小窗"（default，全语言） */
    private static final String[] TITLE_NAMES =
            {"track_side_bar_state_freeform", "gtb_guide_small_window_app_title"};
    private static final int[] TITLE_IDS = {0x7f121eed, 0x7f120db8};
    /** 图标资源候选：仅作为占位（绑定阶段会被替换成自绘图标），必须是非 0 的合法 drawable，否则原生绑定会 NPE。 */
    private static final String[] ICON_NAMES = {"ic_shortcut_fullscreen", "ic_shortcut_multi_splitescreen"};
    private static final int[] ICON_IDS = {0x7f080946, 0x7f080947};

    /** 条目视图内的图标/标题控件 id（a8/m#e 中使用的两个 id）。 */
    private static final int ICON_VIEW_ID = 0x7f0b0c11;
    private static final int TEXT_VIEW_ID = 0x7f0b0c12;

    private SidebarFreeform() {
    }

    // ───────────────────────────── 安装 ─────────────────────────────

    static int install(Unlock.Rule rule, Unlock.Sp sp, ClassLoader loader) {
        final ClassLoader cl = loader != null ? loader : SidebarFreeform.class.getClassLoader();
        Class<?> menu = load(MENU_CLS, cl);
        if (menu == null) {
            Unlock.lg(5, TAG, "未找到 " + MENU_CLS + "（侧边栏小窗快捷项不可用）");
            return 0;
        }

        int hits = 0;
        // 主注入点：菜单装配列表（c() 内先清空 e，随后 f() 会 G(e)+notifyDataSetChanged）。
        Method fill = findListBuilder(menu);
        if (fill != null) {
            final ClassLoader fcl = cl;
            if (Unlock.hookMethod(rule, fill, new Unlock.SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Unlock.Rule r) throws Throwable {
                    Object out = chain.proceed();
                    if (!Unlock.cfg(r)) return out;   // 开关关闭：保持原生菜单
                    try {
                        inject(chain.getThisObject(), fcl, false);
                    } catch (Throwable t) {
                        Unlock.lg(5, TAG, "注入小窗条目失败: " + t);
                    }
                    return out;
                }
            })) {
                hits++;
            }
        }

        // 兜底：某些版本装配方法被内联进 f(La8/i)，则在 f() 返回后补一次注入并主动刷新适配器。
        Method show = findSingleArg(menu, "f");
        if (show != null) {
            final ClassLoader scl = cl;
            if (Unlock.hookMethod(rule, show, new Unlock.SpAction() {
                @Override
                public Object run(XposedInterface.Chain chain, Unlock.Rule r) throws Throwable {
                    Object out = chain.proceed();
                    if (!Unlock.cfg(r)) return out;
                    try {
                        inject(chain.getThisObject(), scl, true);
                    } catch (Throwable t) {
                        Unlock.lg(5, TAG, "注入小窗条目失败(f): " + t);
                    }
                    return out;
                }
            })) {
                hits++;
            }
        }

        // 绑定阶段美化图标/标题。
        Method bind = findBind(menu, cl);
        if (bind != null && Unlock.hookMethod(rule, bind, new Unlock.SpAction() {
            @Override
            public Object run(XposedInterface.Chain chain, Unlock.Rule r) throws Throwable {
                Object out = chain.proceed();
                try {
                    decorate(chain.getArg(0), chain.getThisObject());
                } catch (Throwable ignored) {
                }
                return out;
            }
        })) {
            hits++;
        }

        if (hits > 0) {
            rule.where = "侧边栏菜单注入小窗项（" + MENU_CLS + "）";
            Unlock.lg(4, TAG, "小窗快捷项已安装 hooks=" + hits + " fill=" + fill + " bind=" + bind);
        }
        return hits > 0 ? 1 : 0;
    }

    // ───────────────────────────── 定位 ─────────────────────────────

    private static Class<?> load(String name, ClassLoader cl) {
        ClassLoader[] chain = {cl, SidebarFreeform.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader()};
        for (ClassLoader c : chain) {
            if (c == null) continue;
            try {
                return Class.forName(name, false, c);
            } catch (Throwable ignored) {
            }
        }
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 菜单装配列表的方法：0 参 void —— 13.6.5 为 {@code c()V}。 */
    /** 供 SidebarShortcut 复用：菜单装配列表方法。 */
    static Method findListBuilder(Class<?> menu) {
        Method unique = null;
        int count = 0;
        for (Method m : menu.getDeclaredMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            if (m.getReturnType() != void.class) continue;
            if (m.isSynthetic()) continue;
            if ("c".equals(m.getName())) return m;
            unique = m;
            count++;
        }
        return count == 1 ? unique : null;
    }

    /** 菜单展示入口：单参实例方法 {@code f(La8/i)}。 */
    /** 供 SidebarShortcut 复用：菜单单参展示方法。 */
    static Method findSingleArg(Class<?> menu, String name) {
        for (Method m : menu.getDeclaredMethods()) {
            if (!name.equals(m.getName())) continue;
            if (m.getParameterTypes().length == 1 && m.getReturnType() == void.class && !m.isSynthetic()) return m;
        }
        return null;
    }

    /**
     * 条目绑定方法 {@code e(RecyclerView$ViewHolder)V}。
     *
     * <p>安装期没有菜单实例（字段 e 为空表），因此不能靠实例取模板：改为从菜单字段类型反推条目基类 ——
     * 菜单字段 {@code f} 的类型是条目回调接口 {@code La8/m$a}（成员类），其 enclosingClass 即条目基类
     * {@code a8/m}。兜底再按已知混淆名直接加载。</p>
     */
    private static Method findBind(Class<?> menu, ClassLoader cl) {
        for (Field fl : menu.getDeclaredFields()) {
            Class<?> enclosing = fl.getType().getEnclosingClass();
            if (enclosing == null || enclosing == menu) continue;
            Method m = bindIn(enclosing);
            if (m != null) return m;
        }
        return bindIn(load(ITEM_BASE_CLS, cl));
    }

    private static Method bindIn(Class<?> itemBase) {
        if (itemBase == null) return null;
        for (Method m : itemBase.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 1 && m.getReturnType() == void.class
                    && ps[0].getName().endsWith("RecyclerView$ViewHolder")) {
                return m;
            }
        }
        return null;
    }

    // ───────────────────────────── 注入 ─────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static synchronized void inject(Object menu, ClassLoader cl, boolean refreshAlways) {
        if (!(menu instanceof View) || menu == null) return;
        Object rawList = Unlock.getObjectField(menu, "e");
        List list = asList(rawList);
        if (list == null) {
            Unlock.lg(5, TAG, "菜单列表字段缺失，跳过注入");
            return;
        }
        Object host = Unlock.getObjectField(menu, "a");
        if (host == null) {
            Unlock.lg(5, TAG, "菜单 host 字段缺失，跳过注入");
            return;
        }

        Object template = null;
        for (Object o : list) {
            if (o == null) continue;
            if (TAG_ITEM.equals(Unlock.getObjectField(o, "a"))) return; // 已注入（列表每次装配都会重建）
            if (template == null) template = o;
        }
        if (template == null) {
            Unlock.lg(5, TAG, "菜单列表为空，无模板条目，跳过注入");
            return;
        }

        // 复用原生条目的类：它的 c(holder) 覆写会回调 f.a(item)，正是我们要的点击入口。
        Class<?> itemCls = template.getClass();
        Constructor<?> ctor = null;
        Class<?> cbCls = null;
        for (Constructor<?> k : itemCls.getDeclaredConstructors()) {
            Class<?>[] ps = k.getParameterTypes();
            if (ps.length == 2 && ps[0].isInstance(host) && ps[1].isInterface()) {
                ctor = k;
                cbCls = ps[1];
                break;
            }
        }
        if (ctor == null || cbCls == null) {
            Unlock.lg(5, TAG, "未找到条目构造器(host, callback): " + itemCls);
            return;
        }
        ctor.setAccessible(true);

        Context ctx = ((View) menu).getContext();
        InvocationHandler handler = new Click(menu, cl);
        ClassLoader cbl = cbCls.getClassLoader() != null ? cbCls.getClassLoader() : cl;
        Object callback = Proxy.newProxyInstance(cbl, new Class<?>[]{cbCls}, handler);

        Object item;
        try {
            item = ctor.newInstance(host, callback);
        } catch (Throwable t) {
            Unlock.lg(5, TAG, "构造小窗条目失败: " + t);
            return;
        }

        set(item, "a", TAG_ITEM);               // tag：判重 + 绑定识别
        set(item, "c", iconId(ctx));            // 占位图标（绑定阶段会换成自绘图标）
        set(item, "d", titleId(ctx));           // 原标题「小窗」
        set(item, "e", Boolean.TRUE);           // 强制可用（原生按分屏/快捷查看态禁用）

        list.add(0, item);
        Unlock.lg(4, TAG, "已注入小窗快捷项 itemCls=" + itemCls.getSimpleName()
                + " pid=" + android.os.Process.myPid() + " size=" + list.size());

        if (refreshAlways) refresh(menu, rawList);
    }

    /** 兜底路径需要自己驱动适配器：{@code d.G(list)} + {@code notifyDataSetChanged()}。 */
    private static void refresh(Object menu, Object rawList) {
        Object adapter = Unlock.getObjectField(menu, "d");
        if (adapter == null) return;
        try {
            Method g = adapter.getClass().getMethod("G", List.class);
            g.setAccessible(true);
            g.invoke(adapter, rawList);
        } catch (Throwable t) {
            Unlock.lg(5, TAG, "适配器 G(list) 调用失败: " + t);
        }
        try {
            Method n = adapter.getClass().getMethod("notifyDataSetChanged");
            n.setAccessible(true);
            n.invoke(adapter);
        } catch (Throwable ignored) {
        }
    }

    /** 绑定阶段：把注入条目的图标/标题换成小窗样式（原生只认资源 id，故此处直接改 Drawable/文本）。 */
    private static void decorate(Object holder, Object item) {
        if (holder == null || item == null) return;
        if (!TAG_ITEM.equals(Unlock.getObjectField(item, "a"))) return; // 只处理自己注入的条目
        Object itemView = Unlock.getObjectField(holder, "itemView");
        if (!(itemView instanceof View)) return;

        View root = (View) itemView;
        int color = 0xFF191919;
        try {
            TextView tv = root.findViewById(TEXT_VIEW_ID);
            if (tv != null) {
                tv.setText(LABEL);
                tv.setEnabled(true);
                tv.setAlpha(1.0f);
                color = tv.getCurrentTextColor();
            }
        } catch (Throwable ignored) {
        }
        try {
            ImageView iv = root.findViewById(ICON_VIEW_ID);
            if (iv != null) {
                iv.setEnabled(true);
                iv.setImageDrawable(makeIcon(root.getContext(), color));
                iv.setContentDescription(LABEL);
            }
        } catch (Throwable ignored) {
        }
        root.setEnabled(true);
    }

    /**
     * 小窗图标：26dp 方框，外描边 + 内缩实心块，风格对齐手机管家 {@code ic_shortcut_fullscreen}
     * （26×26 viewport、描边 #FF191919）。取色改用标题文字色，随深色模式自适应。
     */
    private static Drawable makeIcon(Context ctx, int color) {
        float d = 1f;
        try {
            d = ctx.getResources().getDisplayMetrics().density;
        } catch (Throwable ignored) {
        }
        float size = 26f * d;
        float stroke = Math.max(2f, 1.8f * d);
        float r = 5f * d;

        ShapeDrawable outer = new ShapeDrawable(new RoundRectShape(
                new float[]{r, r, r, r, r, r, r, r}, null, null));
        Paint op = outer.getPaint();
        op.setStyle(Paint.Style.STROKE);
        op.setStrokeWidth(stroke);
        op.setColor(color);

        float ir = 1.5f * d;
        ShapeDrawable inner = new ShapeDrawable(new RoundRectShape(
                new float[]{ir, ir, ir, ir, ir, ir, ir, ir}, null, null));
        Paint ip = inner.getPaint();
        ip.setStyle(Paint.Style.FILL);
        ip.setColor((color & 0x00FFFFFF) | 0x55000000); // 约 33% 透明度

        LayerDrawable ld = new LayerDrawable(new Drawable[]{outer, inner});
        int pad = (int) Math.ceil(stroke / 2f) + 1;
        ld.setLayerInset(0, pad, pad, pad, pad);
        int inset = (int) (7f * d);
        ld.setLayerInset(1, inset, inset, inset, inset);
        ld.setBounds(0, 0, (int) size, (int) size);
        return ld;
    }

    // ───────────────────────────── 点击：启动小窗 ─────────────────────────────

    /** 自造回调：{@code La8/m$a#a(La8/m)} → 用小窗参数包启动该条目的目标应用。 */
    private static final class Click implements InvocationHandler {
        private final Object menu;
        private final ClassLoader cl;

        Click(Object menu, ClassLoader cl) {
            this.menu = menu;
            this.cl = cl;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                String n = method.getName();
                if ("toString".equals(n)) return "CamelliaFreeformClick";
                if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                if ("equals".equals(n)) return args != null && args.length > 0 && proxy == args[0];
                return null;
            }
            try {
                launch(menu, pickItem(args), cl);
            } catch (Throwable t) {
                Unlock.lg(5, TAG, "小窗快捷项点击处理失败: " + t);
            }
            return null;
        }

        private Object pickItem(Object[] args) {
            if (args == null) return null;
            for (Object a : args) {
                if (a == null) continue;
                if (Unlock.getObjectField(a, "b") != null) return a; // La8/m#b = host(La8/i)
            }
            return args.length > 0 ? args[0] : null;
        }
    }

    /** 条目的启动 Intent：0 参方法 h()，按声明查找（13.6.5 为 {@code La8/m;->h()Landroid/content/Intent;}）。 */
    private static Intent intentOf(Object item) {
        for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (!Intent.class.isAssignableFrom(m.getReturnType())) continue;
                if (!"h".equals(m.getName())) continue;
                try {
                    m.setAccessible(true);
                    return (Intent) m.invoke(item);
                } catch (Throwable t) {
                    Unlock.lg(5, TAG, "取条目 Intent 失败: " + t);
                    return null;
                }
            }
        }
        return null;
    }

    private static void launch(Object menu, Object item, ClassLoader cl) {
        if (!(menu instanceof View) || item == null) {
            Unlock.lg(5, TAG, "小窗启动参数缺失 menu=" + menu + " item=" + item);
            return;
        }
        Context ctx = ((View) menu).getContext();
        Intent intent = intentOf(item);
        if (intent == null) {
            Unlock.lg(5, TAG, "条目未给出启动 Intent，放弃");
            return;
        }
        String pkg = intent.getPackage();
        if (pkg == null && intent.getComponent() != null) pkg = intent.getComponent().getPackageName();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle opts = null;
        if (pkg != null) {
            try {
                opts = SecurityDiffFeatures.smallWindowOptions(cl, ctx, pkg);
            } catch (Throwable t) {
                Unlock.lg(5, TAG, "构造小窗参数失败 pkg=" + pkg + ": " + t);
            }
        }
        Unlock.lg(4, TAG, "小窗快捷项启动 pkg=" + pkg + " intent=" + intent + " opts=" + (opts != null));
        if (opts != null) ctx.startActivity(intent, opts);
        else ctx.startActivity(intent);
    }

    // ───────────────────────────── 小工具 ─────────────────────────────

    private static List<?> asList(Object o) {
        return o instanceof List ? (List<?>) o : null;
    }

    private static void set(Object target, String name, Object value) {
        if (target == null) return;
        try {
            Field f = null;
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField(name);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (f == null) return;
            f.setAccessible(true);
            if (value instanceof Boolean && f.getType() == boolean.class) f.setBoolean(target, (Boolean) value);
            else if (value instanceof Integer && f.getType() == int.class) f.setInt(target, (Integer) value);
            else f.set(target, value);
        } catch (Throwable t) {
            Unlock.lg(5, TAG, "写字段 " + name + " 失败: " + t);
        }
    }

    private static int titleId(Context ctx) {
        return res(ctx, "string", TITLE_NAMES, TITLE_IDS, android.R.string.ok);
    }

    private static int iconId(Context ctx) {
        return res(ctx, "drawable", ICON_NAMES, ICON_IDS, android.R.drawable.ic_menu_view);
    }

    private static int res(Context ctx, String type, String[] names, int[] ids, int fallback) {
        Resources r = ctx == null ? null : ctx.getResources();
        if (r != null) {
            for (String n : names) {
                try {
                    int id = r.getIdentifier(n, type, SEC_PKG);
                    if (id != 0) return id;
                } catch (Throwable ignored) {
                }
            }
        }
        for (int id : ids) if (id != 0) return id;
        return fallback;
    }
}
