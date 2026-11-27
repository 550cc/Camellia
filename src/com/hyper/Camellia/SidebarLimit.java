package com.hyper.Camellia;

import android.app.Activity;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.*;
import java.util.*;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.*;

/** 手机管家 13.6.3：按数字、调用关系及方法形状定位；不依赖混淆成员名。 */
final class SidebarLimit {
    private static final String CACHE = "sidebar-limit-v3";
    private static final ThreadLocal<Boolean> EDIT = new ThreadLocal<>();
    /** 诊断开关：定位期内打开，问题解决后置 false 即可 */
    private static final boolean DBG = false;
    private static volatile int sCnt;

    // ───────────────────────── 诊断日志 ─────────────────────────

    static void dbg(String msg) {
        if (!DBG) return;
        final String line;
        try {
            line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                            .format(new java.util.Date())
                    + " pid=" + android.os.Process.myPid()
                    + " proc=" + procName()
                    + " " + msg + "\n";
        } catch (Throwable t) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File d = new File("/sdcard/Android/media/com.hyper.Camellia");
                    if (!d.isDirectory()) d.mkdirs();
                    File f = new File(d, "sidebar_dbg.log");
                    if (f.length() > 512 * 1024) f.delete();
                    FileOutputStream o = new FileOutputStream(f, true);
                    o.write(line.getBytes("UTF-8"));
                    o.flush();
                    o.close();
                } catch (Throwable ignored) {
                }
            }
        }, "camellia-sbdbg");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static String procName() {
        try {
            return android.app.Application.getProcessName();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String md(MethodData d) {
        if (d == null) return "";
        try {
            return d.getDeclaredClassName() + "#" + d.getName() + d.getParamTypeNames()
                    + ":" + d.getReturnTypeName();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ───────────────────────── 安装 ─────────────────────────

    static int install(Unlock.Rule rule, Unlock.Sp sp, ClassLoader loader) throws Exception {
        List<Method> plan = sp.resolvedM.get(CACHE);
        if (plan == null && sp.bridge != null) {
            MethodData g = null, k = null, m = null, init = null, count = null, size = null, selected = null, loaded = null;
            String activity = "com.miui.dock.edit.DockAppEditActivity";
            for (MethodData d : sp.bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().declaredClass(activity).usingNumbers(10)))) {
                if (d.getParamCount() == 2
                        && d.getParamTypeNames().equals(Arrays.asList("androidx.recyclerview.widget.RecyclerView", "java.util.List"))
                        && d.getReturnTypeName().startsWith("com.miui.dock.edit.")) {
                    if (g != null) return 0;
                    g = d;
                } else if (d.getParamCount() == 3 && d.getUsingStrings().contains("icon")) {
                    if (k != null) return 0;
                    k = d;
                } else if (d.getParamCount() == 2 && d.getUsingStrings().contains("icon")) {
                    if (m != null) return 0;
                    m = d;
                } else if (d.getParamCount() == 0 && invokes(d, "android.content.res.Resources", "getQuantityString")) {
                    if (init != null) return 0;
                    init = d;
                }
            }
            dbg("resolve g=" + md(g) + " k=" + md(k) + " m=" + md(m) + " init=" + md(init));
            if (g == null || k == null || m == null || init == null) return 0;
            // K0 同时调用了 b.m() 与 a.getItemCount()：两者都是「零参 int 且调用 List.size()」，
            // 旧写法一旦遇到第二个候选就直接 return 0 —— 于是整条规则在 13.6.3 上永远未命中。
            // 真正的 Dock 适配器还必须同时声明 getItemCount()（固定返回 10 = 槽位数），
            // 用这一点做二次判据即可唯一定位到 b.m()（a.getItemCount() 不含数字 10，自动排除）。
            for (MethodData d : k.getInvokes()) {
                if (d.getParamCount() != 0 || !"int".equals(d.getReturnTypeName())) continue;
                if (!d.getDeclaredClassName().startsWith("com.miui.dock.edit.")) continue;
                if (!invokes(d, "java.util.List", "size")) continue;
                MethodData c = null;
                for (MethodData cd : sp.bridge.findMethod(FindMethod.create().matcher(
                        MethodMatcher.create().declaredClass(d.getDeclaredClassName())
                                .name("getItemCount").usingNumbers(10)))) c = cd;
                if (c == null) continue;
                if (size != null) return 0;
                size = d;
                count = c;
            }
            dbg("resolve size=" + md(size) + " count=" + md(count));
            if (size == null || count == null) return 0;
            for (MethodData d : sp.bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().declaredClass(activity).usingNumbers(10)))) {
                if (d.getParamCount() == 0 && "void".equals(d.getReturnTypeName())
                        && invokes(d, size.getDeclaredClassName(), size.getName())
                        && invokes(d, "androidx.recyclerview.widget.RecyclerView$h", "notifyDataSetChanged")) {
                    if (loaded != null) return 0;
                    loaded = d;
                }
            }
            if (loaded == null) return 0;
            // 三个含16的添加状态方法必须指向同一个零参 List 计数入口。
            Map<String, MethodData> candidates = new HashMap<>();
            Map<String, Integer> refs = new HashMap<>();
            for (MethodData d : sp.bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingNumbers(16)))) {
                if (!d.getDeclaredClassName().startsWith("com.miui.dock.allapps.")) continue;
                for (MethodData call : d.getInvokes()) {
                    if (call.getParamCount() == 0 && "java.util.List".equals(call.getReturnTypeName())
                            && call.getDeclaredClassName().equals(d.getDeclaredClassName())
                            && Modifier.isPrivate(call.getModifiers())) {
                        String key = call.getDescriptor();
                        candidates.put(key, call);
                        refs.put(key, refs.getOrDefault(key, 0) + 1);
                    }
                }
            }
            for (String key : candidates.keySet()) if (refs.get(key) >= 3) {
                if (selected != null) return 0;
                selected = candidates.get(key);
            }
            dbg("resolve selected=" + md(selected));
            if (count == null || selected == null) return 0;
            plan = new ArrayList<>();
            for (MethodData d : Arrays.asList(g, k, m, init, count, size, selected, loaded)) plan.add(d.getMethodInstance(loader));
            sp.resolvedM.put(CACHE, plan);
        }
        if (plan == null || plan.size() != 8) {
            rule.where = "Dock 编辑页未命中";
            return 0;
        }
        final Method count = plan.get(4), size = plan.get(5);
        final Class<?> adapter = count.getDeclaringClass();
        Field listField = null;
        for (Field f : adapter.getDeclaredFields()) if (f.getType() == List.class) {
            if (listField != null) return 0;
            listField = f;
        }
        if (listField == null) return 0;
        final Field data = listField;
        data.setAccessible(true);
        dbg("install begin proc=" + procName() + " limit=" + Unlock.sidebarLimit()
                + " adapter=" + adapter.getName() + " dataField=" + data.getName()
                + " activity=" + plan.get(0).getDeclaringClass().getName());
        int hooks = 0;
        for (int index = 0; index < 3; index++) {
            final boolean wrap = index < 2;
            final int idx = index;
            if (Unlock.hookMethod(rule, plan.get(index), (chain, r) -> {
                Object owner = chain.getThisObject();
                Field selectedField = null;
                Object original = null;
                StringBuilder tag = new StringBuilder(160);
                tag.append("hook[").append(idx).append("] enter this=")
                        .append(owner == null ? "" : owner.getClass().getName()).append(" wrap=").append(wrap);
                if (wrap && owner != null) {
                    try {
                        Object real = null;
                        for (Field f : owner.getClass().getDeclaredFields()) if (f.getType() == adapter) {
                            f.setAccessible(true);
                            Object a = f.get(owner);
                            if (a != null) real = data.get(a);
                        }
                        tag.append(" adapterField=").append(real == null ? "" : "ok")
                                .append(" realSize=").append(real == null ? "-" : String.valueOf(((List<?>) real).size()));
                        if (real != null) for (Field f : owner.getClass().getDeclaredFields()) if (f.getType() == List.class) {
                            f.setAccessible(true);
                            if (f.get(owner) == real) { selectedField = f; original = real; break; }
                        }
                        tag.append(" listField=").append(selectedField == null ? "" : selectedField.getName());
                    } catch (Throwable t) {
                        tag.append(" SCAN-FAIL=").append(t);
                    }
                }
                Boolean before = EDIT.get();
                EDIT.set(Boolean.TRUE);
                try {
                    if (selectedField != null) {
                        try {
                            selectedField.set(owner, new CountView((List<?>) original, 10));
                            tag.append(" SET-OK mapped=").append(mapped(((List<?>) original).size(), 10));
                        } catch (Throwable t) {
                            tag.append(" SET-FAIL=").append(t.getClass().getName()).append(':').append(t.getMessage());
                        }
                    }
                    Object ret = chain.proceed();
                    dbg(tag.append(" -> proceeded").toString());
                    return ret;
                } catch (Throwable t) {
                    dbg(tag.append(" PROCEED-THROW=").append(t).toString());
                    throw t;
                } finally {
                    if (selectedField != null) {
                        try {
                            selectedField.set(owner, original);
                        } catch (Throwable t) {
                            dbg("restore FAIL " + t);
                        }
                    }
                    if (before == null) EDIT.remove(); else EDIT.set(before);
                }
            })) hooks++;
        }
        if (Unlock.hookMethod(rule, plan.get(7), (chain, r) -> {
            Boolean before = EDIT.get();
            EDIT.set(Boolean.TRUE);
            try { return chain.proceed(); }
            finally { if (before == null) EDIT.remove(); else EDIT.set(before); }
        })) hooks++;
        if (Unlock.hookMethod(rule, size, (chain, r) -> {
            int actual = ((Number) chain.proceed()).intValue();
            boolean edit = Boolean.TRUE.equals(EDIT.get());
            int ret = edit ? mapped(actual, 10) : actual;
            if (edit) dbg("size() actual=" + actual + " limit=" + Unlock.sidebarLimit() + " -> " + ret);
            return ret;
        })) hooks++;
        if (Unlock.hookMethod(rule, count, (chain, r) -> {
            // 原生区分真实条目和占位格，固定 200 本身不能证明越界。
            // 保留按真实数据扩展的布局，数量闸门由 scoped size 映射放宽。
            List<?> real = (List<?>) data.get(chain.getThisObject());
            int ret = Math.max(10, real.size());
            if ((sCnt++ & 31) == 0) dbg("getItemCount real=" + real.size() + " -> " + ret);
            return ret;
        })) hooks++;
        if (Unlock.hookMethod(rule, plan.get(6), (chain, r) -> {
            Object src = chain.proceed();
            dbg("selected() src=" + (src == null ? "" : src.getClass().getName() + " size=" + ((List<?>) src).size()));
            return new CountView((List<?>) src, 16);
        })) hooks++;
        if (Unlock.hookMethod(rule, plan.get(3), (chain, r) -> {
            Object result = chain.proceed();
            try {
                Activity a = (Activity) chain.getThisObject();
                int id = a.getResources().getIdentifier("tv_desc", "id", a.getPackageName());
                android.view.View view = a.findViewById(id);
                dbg("initView proc=" + procName() + " tv_desc=" + id + " view="
                        + (view instanceof TextView ? ("TextView[" + ((TextView) view).getText() + "]") : String.valueOf(view)));
                if (view instanceof TextView) ((TextView) view).setText("最多添加 " + Unlock.sidebarLimit() + " 个应用");
            } catch (Throwable t) {
                dbg("initView text FAIL " + t);
            }
            return result;
        })) hooks++;
        // 真正的「落库」闸门在侧边栏窗口的 newbox 层：p0.z(事件) 把硬编码的 16
        // 传给 r0.c(画格子) 与 r0.d(添加)，两处都必须放宽，否则 g0.J() 只解决
        // 「图标不变灰、能点」，第 17 个应用点下去仍被 r0.d 静默丢弃。
        int extra = hookNewboxLimit(rule, sp, loader);
        rule.where = "Dock 数量限制（" + hooks + "/8）＋侧边栏添加闸门（newbox " + extra + "/2）";
        dbg("install done hooks=" + hooks + "/8 newbox=" + extra + "/2 proc=" + procName());
        return hooks == 8 ? hooks + extra : 0;
    }

    /**
     * 侧边栏应用栏（用户所说的「左侧应用栏」）真正的数量闸门。
     *
     * <p>手机管家 13.6.3 中侧边栏窗口由 {@code com.miui.gamebooster.windowmanager.newbox}
     * 承载，事件处理器是 {@code p0.z(v7.a)}：</p>
     * <ul>
     *   <li>打开事件 {@code v7.a$c} → {@code r0.c(list, 16)}：按 16 个格子构建显示列表，
     *       不足用占位符 {@code a8.d} 补齐 —— 所以栏位永远只有 16 格；</li>
     *   <li>添加事件 {@code v7.a$b} → {@code r0.d(adapter.getData(), dock, item, 16)}：
     *       统计「列表里 a8.e 的数量 + 目标容器 y.v()」，{@code >= 16} 直接 return，
     *       第 17 个应用被静默丢弃 —— 这就是「能点按添加但没反应」的确切原因；</li>
     *   <li>移除事件 {@code v7.a$d} → {@code r0.e(...)}。</li>
     * </ul>
     *
     * <p>这两处 limit 都是 {@code p0.z} 里的字面量 16，与 {@code g0.J()} 的状态闸门是
     * 三套彼此独立的限制（状态闸门只决定图标是否变灰/是否可点）。本方法按签名形状定位：
     * 静态 {@code (List,int)->List} 即画格子的 c，静态 {@code (List,?,?,int)V} 即添加的 d，
     * 不依赖 {@code a8.i}/{@code newbox.y} 这些混淆类型名。</p>
     */
    private static int hookNewboxLimit(Unlock.Rule rule, Unlock.Sp sp, ClassLoader loader) {
        // 同一个含原生上限 16 的事件方法必须调用同一工具类的画格子和添加方法。
        // 仅凭混淆名或单个签名不足以证明用途；候选不唯一时保留系统行为。
        Class<?> host = null;
        final String cacheKey = CACHE + "-newbox";
        List<Method> cached = sp.resolvedM.get(cacheKey);
        if (cached != null && cached.size() == 2) {
            host = cached.get(0).getDeclaringClass();
            if (cached.get(1).getDeclaringClass() != host) return 0;
        }
        if (host == null && sp.bridge == null) return 0;
        Set<String> owners = new HashSet<>();
        try {
            if (host == null) {
            for (MethodData caller : sp.bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().usingNumbers(16)))) {
                if (!caller.getDeclaredClassName().startsWith("com.miui.gamebooster.windowmanager.newbox.")) continue;
                Set<String> grids = new HashSet<>(), adds = new HashSet<>();
                for (MethodData call : caller.getInvokes()) {
                    if (!Modifier.isStatic(call.getModifiers())) continue;
                    if (!call.getDeclaredClassName().startsWith("com.miui.gamebooster.windowmanager.newbox.")) continue;
                    List<String> p = call.getParamTypeNames();
                    if (p.size() == 2 && "java.util.List".equals(p.get(0)) && "int".equals(p.get(1))
                            && "java.util.List".equals(call.getReturnTypeName())) grids.add(call.getDeclaredClassName());
                    if (p.size() == 4 && "java.util.List".equals(p.get(0)) && "int".equals(p.get(3))
                            && "void".equals(call.getReturnTypeName())) adds.add(call.getDeclaredClassName());
                }
                grids.retainAll(adds);
                owners.addAll(grids);
            }
            if (owners.size() != 1) return 0;
            host = Class.forName(owners.iterator().next(), false, loader);
            }
        } catch (Throwable t) {
            dbg("newbox 调用关系定位失败: " + t);
            return 0;
        }
        // 让原生画格子方法创建正确类型的占位元素，不猜测混淆模型类。
        int grids = 0, adds = 0;
        for (Method m : host.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == List.class && p[1] == int.class && m.getReturnType() == List.class) grids++;
            if (p.length == 4 && p[0] == List.class && p[3] == int.class && m.getReturnType() == void.class) adds++;
        }
        if (grids != 1 || adds != 1) return 0;
        List<Method> resolved = new ArrayList<>();
        for (Method method : host.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] params = method.getParameterTypes();
            if ((params.length == 2 && params[0] == List.class && params[1] == int.class
                    && method.getReturnType() == List.class)
                    || (params.length == 4 && params[0] == List.class && params[3] == int.class
                    && method.getReturnType() == void.class)) resolved.add(method);
        }
        sp.resolvedM.put(cacheKey, resolved);
        int n = 0;
        for (Method m : host.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            final int idx;
            final boolean grid;
            if (p.length == 2 && List.class.isAssignableFrom(p[0]) && p[1] == int.class
                    && List.class.isAssignableFrom(m.getReturnType())) {
                idx = 1;                                      // r0.c(list, 格子数) -> 显示列表
                grid = true;
            } else if (p.length == 4 && List.class.isAssignableFrom(p[0]) && p[3] == int.class
                    && m.getReturnType() == void.class) {
                idx = 3;                                      // r0.d(list, 目标容器, 条目, 上限)
                grid = false;
            } else {
                continue;
            }
            if (Unlock.hookMethod(rule, m, (chain, r) -> {
                List<Object> args = chain.getArgs();
                int lim = Unlock.sidebarLimit();
                if (args.size() <= idx || !(args.get(idx) instanceof Integer)) return chain.proceed();
                int old = (Integer) args.get(idx);
                if (lim <= old) return chain.proceed();       // 只放宽，绝不收紧
                Object[] changed = args.toArray();
                changed[idx] = lim;
                return chain.proceed(changed);
            })) {
                n++;
                dbg("newbox hook " + (grid ? "grid" : "add") + " " + m);
            }
        }
        return n;
    }

    private static boolean invokes(MethodData d, String owner, String name) {
        for (MethodData c : d.getInvokes()) if (owner.equals(c.getDeclaredClassName()) && name.equals(c.getName())) return true;
        return false;
    }

    static int mapped(int actual, int nativeLimit) {
        return Math.max(0, actual - Unlock.sidebarLimit() + nativeLimit);
    }

    /** 仅用于已核实的 size 判断；真实列表始终留在适配器中。 */
    private static final class CountView extends AbstractList<Object> {
        final List<?> source;
        final int nativeLimit;
        CountView(List<?> source, int nativeLimit) { this.source = source; this.nativeLimit = nativeLimit; }
        @Override public int size() { return mapped(source.size(), nativeLimit); }
        @Override public Object get(int index) { return source.get(index); }
        @Override public boolean isEmpty() { return source.isEmpty(); }
        @Override public Iterator<Object> iterator() { return listIterator(0); }
        @Override public ListIterator<Object> listIterator(int index) {
            final ListIterator<?> it = source.listIterator(index);
            return new ListIterator<Object>() {
                public boolean hasNext() { return it.hasNext(); }
                public Object next() { return it.next(); }
                public boolean hasPrevious() { return it.hasPrevious(); }
                public Object previous() { return it.previous(); }
                public int nextIndex() { return it.nextIndex(); }
                public int previousIndex() { return it.previousIndex(); }
                public void remove() { it.remove(); }
                public void set(Object value) { throw new UnsupportedOperationException(); }
                public void add(Object value) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public Object[] toArray() { return source.toArray(); }
        @Override public <T> T[] toArray(T[] array) { return source.toArray(array); }
        @Override public boolean contains(Object value) { return source.contains(value); }
        @Override public int indexOf(Object value) { return source.indexOf(value); }
        @Override public int lastIndexOf(Object value) { return source.lastIndexOf(value); }
    }
}
