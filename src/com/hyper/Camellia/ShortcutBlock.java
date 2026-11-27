package com.hyper.Camellia;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 快捷方式「屏蔽关键词」规则的唯一实现。
 *
 * <p>同一套关键词语义被两处消费：</p>
 * <ul>
 *   <li>{@code SidebarShortcut} —— 手机管家侧边栏长按弹出的快捷方式面板；</li>
 *   <li>{@code LauncherShortcut} —— 桌面 Launcher 长按图标弹出的快捷方式菜单
 *       （在 system_server 的 {@code ShortcutService} / {@code LauncherAppsService}
 *       出口处过滤，所有消费方一次生效）。</li>
 * </ul>
 *
 * <p>规则来源（设置页写入的字符串配置）：</p>
 * <pre>
 *   ul_sidebar_sc_block     全局屏蔽关键词，形如「签到,领取」
 *   ul_sidebar_sc_block_app 应用专属屏蔽，形如「淘宝 = 签到,领券」（分号或换行分隔多条）
 * </pre>
 *
 * <p>命中判据：快捷方式标题（短标题 → 长标题 → id）或 id 含关键词，忽略大小写的子串匹配。
 * 关键词只影响「显示」，不改变快捷方式本身。</p>
 */
final class ShortcutBlock {

    private ShortcutBlock() {
    }

    /**
     * 快捷方式标题：短标题 → 长标题 → id（与面板渲染、桌面菜单取值链保持一致）。
     */
    static String labelOf(ShortcutInfo info) {
        if (info == null) return "";
        CharSequence label = null;
        try {
            label = info.getShortLabel();
        } catch (Throwable ignored) {
        }
        if (label == null || label.length() == 0) {
            try {
                label = info.getLongLabel();
            } catch (Throwable ignored) {
            }
        }
        if (label == null || label.length() == 0) {
            try {
                label = info.getId();
            } catch (Throwable ignored) {
            }
        }
        return label == null ? "" : label.toString();
    }

    /** 关键词串 → 去重后的小写关键词表：逗号 / 顿号 / 分号 / 空白 / 换行分隔。 */
    static List<String> keywords(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.length() == 0) return out;
        for (String s : raw.split("[,，、;；\\s]+")) {
            String k = s.trim().toLowerCase(Locale.ROOT);
            if (k.length() > 0 && !out.contains(k)) out.add(k);
        }
        return out;
    }

    /**
     * 解析应用专属屏蔽：每条形如 {@code 应用名或包名 = 关键词1, 关键词2}（分号或换行分隔多条）。
     *
     * <p>应用标识同时接受包名与应用名（都忽略大小写），方便直接照抄桌面图标名；
     * 标识对不上的条目直接跳过，因此可以一次写好多个 App 的规则。</p>
     */
    static List<String> appKeywords(String raw, String pkg, String appLabel) {
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.length() == 0) return out;
        for (String line : raw.split("[\\r\\n;；]+")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            if (key.length() == 0) continue;
            boolean hit = pkg != null && key.equalsIgnoreCase(pkg);
            if (!hit && appLabel != null && appLabel.length() > 0) {
                hit = key.equalsIgnoreCase(appLabel.trim());
            }
            if (!hit) continue;
            for (String k : keywords(line.substring(eq + 1))) {
                if (!out.contains(k)) out.add(k);
            }
        }
        return out;
    }

    /** 应用显示名（按应用名写规则时用）；取不到返回 null。 */
    static String appLabelOf(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 合并出该应用生效的关键词表（全局 + 应用专属）。返回空表表示没有规则命中。
     */
    static List<String> rules(String rawAll, String rawApp, String pkg, String appLabel) {
        List<String> kws = new ArrayList<String>();
        boolean hasAll = rawAll != null && rawAll.length() > 0;
        boolean hasApp = rawApp != null && rawApp.length() > 0;
        if (!hasAll && !hasApp) return kws;
        if (hasAll) kws.addAll(keywords(rawAll));
        if (hasApp) kws.addAll(appKeywords(rawApp, pkg, appLabel));
        return kws;
    }

    /** 该条目命中的关键词；未命中返回 null。{@code skipId} 指定的 id 永不命中（模块自己注入的条目）。 */
    static String hit(ShortcutInfo info, List<String> kws, String skipId) {
        if (info == null || kws == null || kws.isEmpty()) return null;
        String id = "";
        try {
            id = info.getId();
        } catch (Throwable ignored) {
        }
        if (id == null) id = "";
        if (skipId != null && skipId.equals(id)) return null;
        String hay = (labelOf(info) + "\u0000" + id).toLowerCase(Locale.ROOT);
        for (String k : kws) {
            if (hay.contains(k)) return k;
        }
        return null;
    }

    /**
     * 按关键词过滤条目。
     *
     * <p>没有规则时原样返回同一个列表实例（零开销：一次空判断），调用方可用
     * {@code filtered == items} 判断是否发生了裁剪，避免无谓地重建 ParceledListSlice。</p>
     *
     * @param blocked 被屏蔽条目的 {@code 标题(命中词)} 描述，仅用于日志；可为 null
     */
    static List<ShortcutInfo> filter(List<ShortcutInfo> items, List<String> kws,
                                     String skipId, List<String> blocked) {
        if (items == null || items.isEmpty() || kws == null || kws.isEmpty()) return items;
        List<ShortcutInfo> out = new ArrayList<ShortcutInfo>(items.size());
        boolean changed = false;
        for (ShortcutInfo info : items) {
            if (info == null) continue;
            String k = hit(info, kws, skipId);
            if (k == null) {
                out.add(info);
                continue;
            }
            changed = true;
            if (blocked != null) blocked.add(labelOf(info) + "(" + k + ")");
        }
        return changed ? out : items;
    }
}
