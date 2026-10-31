package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 关键词拦截的配置与命中记录。
 *
 * 记录里只放「哪个应用、哪个词、什么时候、第几次」，不存屏幕内容。
 */
public final class KeywordStore {

    private static final String SP = "afs_keyword";
    private static final String K_ON = "on";
    private static final String K_WORDS = "words";
    private static final String K_TARGETS = "targets";
    private static final String K_HITS = "hits";
    private static final String K_LAST_AT = "last_at";
    private static final String K_LAST_WORD = "last_word";
    private static final String K_LAST_PKG = "last_pkg";

    private KeywordStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 开着 + 有词 + 有目标应用，三条同时满足才真的拦截。 */
    public static boolean active(Context c) {
        return sp(c).getBoolean(K_ON, false)
                && !keywords(c).isEmpty()
                && !targets(c).isEmpty();
    }

    public static boolean on(Context c) {
        return sp(c).getBoolean(K_ON, false);
    }

    public static void setOn(Context c, boolean value) {
        sp(c).edit().putBoolean(K_ON, value).apply();
    }

    public static List<String> keywords(Context c) {
        return KeywordRule.parse(sp(c).getString(K_WORDS, ""));
    }

    public static void setKeywords(Context c, String raw) {
        sp(c).edit().putString(K_WORDS, KeywordRule.join(KeywordRule.parse(raw))).apply();
    }

    public static Set<String> targets(Context c) {
        Set<String> out = new HashSet<String>();
        String raw = sp(c).getString(K_TARGETS, "");
        if (raw == null) return out;
        for (String item : raw.split("\n")) {
            String pkg = item.trim();
            if (!pkg.isEmpty()) out.add(pkg);
        }
        return out;
    }

    public static void setTargets(Context c, Set<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : list) {
            if (pkg == null || pkg.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(pkg.trim());
        }
        sp(c).edit().putString(K_TARGETS, sb.toString()).apply();
    }

    /* ---------- 记录 ---------- */

    public static void recordHit(Context c, String pkg, String keyword, long now) {
        SharedPreferences p = sp(c);
        p.edit()
                .putInt(K_HITS, p.getInt(K_HITS, 0) + 1)
                .putLong(K_LAST_AT, now)
                .putString(K_LAST_WORD, keyword == null ? "" : keyword)
                .putString(K_LAST_PKG, pkg == null ? "" : pkg)
                .apply();
    }

    public static int hits(Context c) {
        return sp(c).getInt(K_HITS, 0);
    }

    public static long lastAt(Context c) {
        return sp(c).getLong(K_LAST_AT, 0L);
    }

    public static String lastWord(Context c) {
        String value = sp(c).getString(K_LAST_WORD, "");
        return value == null ? "" : value;
    }

    public static String lastPkg(Context c) {
        String value = sp(c).getString(K_LAST_PKG, "");
        return value == null ? "" : value;
    }

    public static void clearHits(Context c) {
        sp(c).edit().putInt(K_HITS, 0).putLong(K_LAST_AT, 0L)
                .putString(K_LAST_WORD, "").putString(K_LAST_PKG, "").apply();
    }

    public static String status(Context c) {
        if (!on(c)) return T.s("未开启");
        int words = keywords(c).size();
        int targets = targets(c).size();
        if (words == 0 || targets == 0) return T.s("缺关键词或目标应用，不会生效");
        if (!Capability.accessibilityOn(c)) return T.s("无障碍服务没开，不会生效");
        return T.f("%d 个词 · %d 个应用 · 已拦 %d 次", words, targets, hits(c));
    }
}
