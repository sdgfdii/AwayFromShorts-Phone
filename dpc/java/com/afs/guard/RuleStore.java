package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 每个应用各自的放行规则，按包名存在 SharedPreferences 里（一个 JSON）。
 *
 * 为什么不用 N 个 key：规则是整块读写的，拆成一堆 "rule.<pkg>.start"
 * 之后删应用容易漏字段，一个 JSON 更好维护。
 */
public final class RuleStore {

    private static final String FILE = "afs_guard";
    private static final String K_RULES = "rules";
    private static final String K_APPLIED = "applied";
    private static final String K_LAST = "last_rule";

    /** 新加的应用默认用这个：每天 21:00–23:00。 */
    public static final int FALLBACK_START = 21 * 60;
    public static final int FALLBACK_END = 23 * 60;

    /**
     * 新加应用后的可编辑窗口。
     *
     * 不加这个会很难用：默认规则是 21:00–23:00，你在上午加一个应用，
     * 它当场就被判成"在屏蔽时间里"，那张卡立刻变灰 —— 你连时段都设不了，
     * 得等到自己的 21 点。所以刚加的 10 分钟里允许改时段。
     * ★ 但**移除**不吃这个宽限（见 Guard.canRemove），否则删掉就等于绕过了。
     */
    public static final long NEW_GRACE_MS = 10L * 60L * 1000L;

    private RuleStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /* ---------- 读 ---------- */

    public static Map<String, Rule> all(Context c) {
        migrate(c);
        Map<String, Rule> m = new LinkedHashMap<>();
        String raw = sp(c).getString(K_RULES, "");
        try {
            JSONObject o = new JSONObject(raw.length() == 0 ? "{}" : raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String pkg = it.next();
                JSONObject r = o.optJSONObject(pkg);
                if (r == null) {
                    continue;
                }
                m.put(pkg, parseRule(r));
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    public static Set<String> packages(Context c) {
        return new LinkedHashSet<>(all(c).keySet());
    }

    public static int count(Context c) {
        return all(c).size();
    }

    /** Apply the two requested corrections to the installed rules once. */
    public static boolean alignRequestedRules(Context c) {
        SharedPreferences prefs = sp(c);
        Map<String, Rule> rules = all(c);
        boolean changed = false;
        if (!prefs.getBoolean("align_pdd_v1321", false)) {
            Rule market = rules.get("com.taobao.idlefish");
            if (market == null) market = rules.get("com.sankuai.meituan");
            if (market != null && rules.containsKey("com.xunmeng.pinduoduo")) {
                Rule old = rules.get("com.xunmeng.pinduoduo");
                Rule aligned = market.withGate(old.gate);
                if (!old.same(aligned)) {
                    rules.put("com.xunmeng.pinduoduo", aligned);
                    changed = true;
                }
                prefs.edit().putBoolean("align_pdd_v1321", true).apply();
            }
        }
        // 抖音 / 哔哩哔哩所在的那一格：冻结时间改成 00:00-21:00 与 23:00-00:00
        // （放行 21:00-23:00）。整格应用一起改，成员一个都不挪出去。
        // 同一格里混着两种存盘格式：新的 freeze=true（冻结段），老的 freeze=false（放行段 22:00-23:00）。
        if (!prefs.getBoolean("align_group_v1534", false)) {
            Rule target = new Rule(Rule.ALL_DAYS, new int[][] { {0, 21 * 60}, {23 * 60, 0} }, true, true);
            Rule legacy = new Rule(Rule.ALL_DAYS, new int[][] { {0, 22 * 60}, {23 * 60, 0} }, true, true);
            for (Map.Entry<String, Rule> e : rules.entrySet()) {
                Rule r = e.getValue();
                if (r.days != Rule.ALL_DAYS) continue;
                boolean inGroup;
                if (r.freeze) {
                    inGroup = r.windowCount() == 2
                            && r.startAt(0) == legacy.startAt(0) && r.endAt(0) == legacy.endAt(0)
                            && r.startAt(1) == legacy.startAt(1) && r.endAt(1) == legacy.endAt(1);
                } else {
                    // 老格式：单段放行 22:00-23:00
                    inGroup = r.windowCount() == 1 && r.startAt(0) == 22 * 60 && r.endAt(0) == 23 * 60;
                }
                if (!inGroup) continue;
                Rule aligned = target.withGate(r.gate);
                if (!r.same(aligned)) {
                    e.setValue(aligned);
                    changed = true;
                }
            }
            prefs.edit().putBoolean("align_group_v1534", true).apply();
        }
        // 整格改完时段后，旧时段会剩一个 0 个应用的空壳，一并清掉。
        if (!prefs.getBoolean("align_slot_prune_v1535", false)) {
            if (changed) save(c, rules);
            FreezeSlotStore.pruneEmpty(c, new Rule(Rule.ALL_DAYS,
                    new int[][] { {0, 22 * 60}, {23 * 60, 0} }, true, true));
            prefs.edit().putBoolean("align_slot_prune_v1535", true).apply();
        }
        if (changed) save(c, rules);
        return changed;
    }

    /* ---------- 关键应用名单 ---------- */

    /** 关键应用由用户从黑名单里选；gate=false 表示学习闸门豁免。 */
    public static Set<String> keyApps(Context c) {
        return keyAppsOf(all(c));
    }

    static Set<String> keyAppsOf(Map<String, Rule> rules) {
        Set<String> s = new LinkedHashSet<>();
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            if (!entry.getValue().gate) s.add(entry.getKey());
        }
        return s;
    }

    /** 更新整份名单，只切换闸门豁免，不碰各应用的冻结星期和时间。 */
    public static void setKeyApps(Context c, Set<String> keys) {
        Map<String, Rule> m = all(c);
        Map<String, Rule> updated = withKeyApps(m, keys);
        if (!updated.equals(m)) save(c, updated);
    }

    static Map<String, Rule> withKeyApps(Map<String, Rule> rules, Set<String> keys) {
        Map<String, Rule> updated = new LinkedHashMap<>();
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            boolean gate = !keys.contains(entry.getKey());
            Rule rule = entry.getValue();
            updated.put(entry.getKey(), rule.gate == gate ? rule : rule.withGate(gate));
        }
        return updated;
    }

    /** 取某个应用的规则；没有就退回默认（不会写盘）。 */
    public static Rule get(Context c, String pkg) {
        Rule r = all(c).get(pkg);
        return r != null ? r : defaultRule(c);
    }

    /** 新加应用时使用的规则：上次改过的那个，没有就用兜底。 */
    public static Rule defaultRule(Context c) {
        String raw = sp(c).getString(K_LAST, "");
        try {
            // 走 parseRule：setLast 存的是 w=[...] 格式，以前这里读 s/e 读不到，
            // 于是"沿用上次的规则"一直悄悄回落到 21:00-23:00。
            return parseRule(new JSONObject(raw.length() == 0 ? "{}" : raw));
        } catch (Throwable ignored) {
            return new Rule(Rule.ALL_DAYS, FALLBACK_START, FALLBACK_END);
        }
    }

    /* ---------- 写 ---------- */

    public static void put(Context c, String pkg, Rule r) {
        Map<String, Rule> m = all(c);
        boolean fresh = !m.containsKey(pkg);
        // 时段编辑不能暗中改变关键名单；新加入的应用默认参与闸门。
        r = r.withGate(fresh || m.get(pkg).gate);
        m.put(pkg, r);
        save(c, m);
        if (fresh) {
            markAdded(c, pkg);
        }
        setLast(c, r.withGate(true));
    }

    /* ---------- 新加应用的时间戳 ---------- */

    public static void markAdded(Context c, String pkg) {
        try {
            JSONObject o = raw(c);
            JSONObject r = o.optJSONObject(pkg);
            if (r == null) {
                r = new JSONObject();
                o.put(pkg, r);
            }
            r.put("at", System.currentTimeMillis());
            sp(c).edit().putString(K_RULES, o.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static long addedAt(Context c, String pkg) {
        try {
            JSONObject r = raw(c).optJSONObject(pkg);
            return r == null ? 0L : r.optLong("at", 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 刚加进来还没过宽限期。 */
    public static boolean isFresh(Context c, String pkg) {
        long at = addedAt(c, pkg);
        return at > 0L && System.currentTimeMillis() - at < NEW_GRACE_MS;
    }

    /** 解析一条规则：v2 是 w=[[s,e],...]，v1 是 s/e 单段（老用户升级自动迁移）。 */
    private static Rule parseRule(JSONObject r) {
        int days = r.optInt("d", Rule.ALL_DAYS);
        // 老数据没有 "g" 字段 → 按规则本身的放行时长自动判断（见 Rule.autoGate）
        org.json.JSONArray w = r.optJSONArray("w");
        if (w != null) {
            int[][] wins = new int[w.length()][2];
            int n = 0;
            for (int i = 0; i < w.length(); i++) {
                org.json.JSONArray seg = w.optJSONArray(i);
                if (seg != null && seg.length() >= 2) {
                    wins[n][0] = seg.optInt(0);
                    wins[n][1] = seg.optInt(1);
                    n++;
                }
            }
            if (n > 0) {
                int[][] trimmed = new int[n][2];
                System.arraycopy(wins, 0, trimmed, 0, n);
                boolean g = r.has("g") ? r.optBoolean("g", true) : Rule.autoGate(trimmed);
                return new Rule(days, trimmed, g, r.optBoolean("freeze", false));
            }
            // 显式清空 = 整天冻结（这种显然要跟着闸门走）
            return new Rule(days, new int[0][2], r.optBoolean("g", true), r.optBoolean("freeze", false));
        }
        int s = r.optInt("s", FALLBACK_START);
        int e = r.optInt("e", FALLBACK_END);
        boolean g = r.has("g") ? r.optBoolean("g", true)
                : Rule.autoGate(new int[][] { { s, e } });
        return new Rule(days, s, e, g);
    }

    private static JSONObject raw(Context c) {
        String s = sp(c).getString(K_RULES, "");
        try {
            return new JSONObject(s.length() == 0 ? "{}" : s);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static void remove(Context c, String pkg) {
        Map<String, Rule> m = all(c);
        m.remove(pkg);
        save(c, m);
    }

    private static void save(Context c, Map<String, Rule> m) {        JSONObject old = raw(c);
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Rule> e : m.entrySet()) {
                JSONObject r = new JSONObject();
                r.put("d", e.getValue().days);
                r.put("g", e.getValue().gate);
                r.put("freeze", e.getValue().freeze);
                org.json.JSONArray w = new org.json.JSONArray();
                for (int i = 0; i < e.getValue().windowCount(); i++) {
                    org.json.JSONArray seg = new org.json.JSONArray();
                    seg.put(e.getValue().startAt(i));
                    seg.put(e.getValue().endAt(i));
                    w.put(seg);
                }
                r.put("w", w);
                // 新加应用的时间戳要留着，否则每次改时段都会重新给一次宽限
                JSONObject prev = old.optJSONObject(e.getKey());
                if (prev != null && prev.has("at")) {
                    r.put("at", prev.optLong("at", 0L));
                }
                o.put(e.getKey(), r);
            }
        } catch (Throwable ignored) {
        }
        sp(c).edit().putString(K_RULES, o.toString()).apply();
        // 规则落盘 = 配置变了 → 下一拍推 hub 分节（Gist 中心仓库）
        Hub.markConfigDirty(c);
    }

    /* ---------- hub 分节导出/恢复（Gist 中心仓库） ---------- */

    /** 规则原文（afs_guard/rules 的 JSON 字符串），给 Hub 推配置分节用。 */
    public static String exportRules(Context c) {
        String s = sp(c).getString(K_RULES, "{}");
        return s.length() == 0 ? "{}" : s;
    }

    /** 从 hub 分节恢复规则原文。结构不合法直接拒绝，本地一个字节不动。 */
    public static void importRules(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            sp(c).edit().putString(K_RULES, o.toString()).apply();
            alignRequestedRules(c);
        } catch (Throwable t) {
            // 非法 JSON：静默拒绝
        }
    }

    private static void setLast(Context c, Rule r) {
        try {
            JSONObject o = new JSONObject();
            o.put("d", r.days);
            o.put("g", r.gate);
            o.put("freeze", r.freeze);
            org.json.JSONArray w = new org.json.JSONArray();
            for (int i = 0; i < r.windowCount(); i++) {
                org.json.JSONArray seg = new org.json.JSONArray();
                seg.put(r.startAt(i));
                seg.put(r.endAt(i));
                w.put(seg);
            }
            o.put("w", w);
            sp(c).edit().putString(K_LAST, o.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /* ---------- 上一次真正应用过谁（用来把移出的应用解冻） ---------- */

    public static Set<String> applied(Context c) {
        Set<String> s = sp(c).getStringSet(K_APPLIED, null);
        return s == null ? new LinkedHashSet<String>() : new LinkedHashSet<>(s);
    }

    public static void setApplied(Context c, Set<String> v) {
        sp(c).edit().putStringSet(K_APPLIED, new LinkedHashSet<>(v)).apply();
    }

    /* ---------- 老配置迁移 ---------- */

    /**
     * 旧版只有一个全局时段（days / start_min / end_min + targets 列表）。
     * 第一次读到新存储时把它铺成"每个应用一条同样的规则"，
     * 用户打开 App 看到的就是原来的行为，不需要重新配。
     */
    private static void migrate(Context c) {
        SharedPreferences p = sp(c);
        if (p.contains(K_RULES)) {
            return;
        }
        int d = p.getInt("days", Rule.ALL_DAYS);
        int s = p.getInt("start_min", FALLBACK_START);
        int e = p.getInt("end_min", FALLBACK_END);
        Rule r = new Rule(d, s, e);

        Set<String> old = p.getStringSet("targets", null);
        Map<String, Rule> m = new LinkedHashMap<>();
        if (old != null && !old.isEmpty()) {
            for (String pkg : old) {
                m.put(pkg, r);
            }
        }
        if (m.isEmpty()) {
            for (String pkg : Guard.DEFAULT_TARGETS) {
                m.put(pkg, r);
            }
        }
        save(c, m);
    }
}
