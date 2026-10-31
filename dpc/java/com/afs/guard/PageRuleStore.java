package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用内页面拦截的配置与命中记录。
 *
 * 和关键词拦截一样，只存「哪个应用、哪条规则、什么时候」，屏幕上的字一个都不留。
 */
public final class PageRuleStore {

    private static final String SP = "afs_page";
    private static final String K_ON = "on";
    private static final String K_RULES = "rules";
    private static final String K_HITS = "hits";
    private static final String K_LAST_AT = "last_at";
    private static final String K_LAST_PKG = "last_pkg";
    private static final String K_LAST_RULE = "last_rule";
    private static final String K_SILENT = "silent";
    private static final String K_SEEDED = "builtin_seed";

    private PageRuleStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 开着 + 至少有一条能解析的规则，才真的拦。 */
    public static boolean active(Context c) {
        return on(c) && !raw(c).isEmpty();
    }

    public static boolean on(Context c) {
        return sp(c).getBoolean(K_ON, false);
    }

    public static void setOn(Context c, boolean value) {
        sp(c).edit().putBoolean(K_ON, value).apply();
    }

    private static Set<String> raw(Context c) {
        Set<String> out = new HashSet<String>();
        Set<String> saved = sp(c).getStringSet(K_RULES, null);
        if (saved == null) return out;
        for (String item : saved) {
            if (PageRule.parse(item) != null) out.add(item);
        }
        return out;
    }

    public static List<PageRule.Rule> rules(Context c) {
        List<String> encoded = new ArrayList<String>(raw(c));
        java.util.Collections.sort(encoded);
        return PageRule.parseAll(encoded);
    }

    /** 加一条；已经有了就返回 false（界面据此说「已经加过」）。 */
    public static boolean add(Context c, String pkg, String value) {
        String cleaned = PageRule.clean(value);
        if (pkg == null || pkg.isEmpty() || cleaned.isEmpty()) return false;
        String encoded = pkg + "|" + (PageRule.guessKind(cleaned) == PageRule.KIND_CLASS ? "class:" : "text:") + cleaned;
        if (PageRule.parse(encoded) == null) return false;
        Set<String> rules = raw(c);
        if (!rules.add(encoded)) return false;
        sp(c).edit().putStringSet(K_RULES, rules).apply();
        return true;
    }

    public static boolean add(Context c, String encoded) {
        PageRule.Rule rule = PageRule.parse(encoded);
        if (rule == null) return false;
        Set<String> rules = raw(c);
        if (!rules.add(rule.encode())) return false;
        sp(c).edit().putStringSet(K_RULES, rules).apply();
        return true;
    }

    public static void remove(Context c, String encoded) {
        Set<String> rules = raw(c);
        if (rules.remove(encoded)) sp(c).edit().putStringSet(K_RULES, rules).apply();
    }

    public static void clearRules(Context c) {
        sp(c).edit().remove(K_RULES).apply();
    }

    /**
     * 补内置规则（版本升级用）：只有「页面拦截已经开着、而且已经有规则」的用户才补，
     * 从没用过这个功能的人不该被塞一条他不知情的规则。一天只跑一次。
     */
    public static void seedBuiltins(Context c) {
        SharedPreferences p = sp(c);
        if (p.getBoolean(K_SEEDED, false)) return;
        if (on(c) && !raw(c).isEmpty()) {
            add(c, PageRule.WECHAT_MOMENTS);
        }
        p.edit().putBoolean(K_SEEDED, true).apply();
    }

    /* ---------- 命中记录 ---------- */

    public static int hits(Context c) {
        return sp(c).getInt(K_HITS, 0);
    }

    public static long lastAt(Context c) {
        return sp(c).getLong(K_LAST_AT, 0L);
    }

    public static String lastLabel(Context c) {
        String pkg = sp(c).getString(K_LAST_PKG, "");
        String rule = sp(c).getString(K_LAST_RULE, "");
        if (pkg == null || pkg.isEmpty()) return "";
        PageRule.Rule parsed = PageRule.parse(rule == null ? "" : rule);
        String name = Guard.labelOf(c.getPackageManager(), pkg);
        return parsed == null ? name : name + " · " + parsed.value;
    }

    public static void recordHit(Context c, String pkg, PageRule.Rule rule, long now) {
        sp(c).edit()
                .putInt(K_HITS, hits(c) + 1)
                .putLong(K_LAST_AT, now)
                .putString(K_LAST_PKG, pkg == null ? "" : pkg)
                .putString(K_LAST_RULE, rule == null ? "" : rule.encode())
                .apply();
    }

    public static void clearHits(Context c) {
        sp(c).edit().remove(K_HITS).remove(K_LAST_AT).remove(K_LAST_PKG).remove(K_LAST_RULE).apply();
    }

    /* ---------- 读不到文字的应用 ---------- */

    /**
     * 有些应用把整棵视图树对无障碍隐藏（微信实测一屏 0 个子节点），
     * 文字规则对它们永远不会命中。记下来，界面上照实说，别让人以为配了就一定管用。
     */
    public static Set<String> silentPkgs(Context c) {
        Set<String> out = new HashSet<String>();
        Set<String> saved = sp(c).getStringSet(K_SILENT, null);
        if (saved != null) out.addAll(saved);
        return out;
    }

    public static boolean hidesText(Context c, String pkg) {
        return pkg != null && !pkg.isEmpty() && silentPkgs(c).contains(pkg);
    }

    public static void markSilent(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> next = silentPkgs(c);
        if (next.add(pkg)) sp(c).edit().putStringSet(K_SILENT, next).apply();
    }

    /** 读到字了就撤掉标记：系统升级、应用改版都可能突然又能读了。 */
    public static void clearSilent(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> next = silentPkgs(c);
        if (next.remove(pkg)) sp(c).edit().putStringSet(K_SILENT, next).apply();
    }

    /** 一行状态，给设置页显示。 */
    public static String status(Context c) {
        if (rules(c).isEmpty()) return T.s("还没配规则");
        if (!on(c)) return T.s("已关闭");
        int hits = hits(c);
        return hits > 0 ? T.f("今天拦了 %d 次", hits) : T.s("还没有拦到");
    }
}
