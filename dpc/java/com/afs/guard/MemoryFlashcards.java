package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Daily, persisted sampling across subjects. Independent of ordinary check-ins. */
public final class MemoryFlashcards {
    public static final String POOL_ALL = "all", POOL_DUE = "due";
    private MemoryFlashcards() { }
    private static JSONObject root(MemoryData m) {
        JSONObject r = m.db.optJSONObject("flashcards");
        if (r == null) { r = new JSONObject(); MemoryData.put(m.db, "flashcards", r); }
        return r;
    }
    public static JSONObject settings(MemoryData m) {
        JSONObject s = root(m).optJSONObject("settings");
        if (s != null) return s;
        s = new JSONObject(); MemoryData.put(s, "count", 20); MemoryData.put(s, "mode", "random");
        // Missing subjects means all active subjects, including ones added later.
        return s;
    }
    /** Missing or malformed pool reads as "all", which is the pre-existing behaviour. */
    public static String pool(MemoryData m) {
        return POOL_DUE.equals(settings(m).optString("pool", POOL_ALL)) ? POOL_DUE : POOL_ALL;
    }
    public static JSONArray subjects(MemoryData m) {
        JSONArray configured = settings(m).optJSONArray("subjects");
        if (configured != null) return configured;
        JSONArray ids = new JSONArray(), active = m.activeDecks();
        for (int i = 0; i < active.length(); i++) ids.put(active.optJSONObject(i).optString("id"));
        return ids;
    }
    public static void configure(MemoryData m, JSONArray subjects, int count, String mode, long now) {
        configure(m, subjects, count, mode, POOL_ALL, now);
    }
    public static void configure(MemoryData m, JSONArray subjects, int count, String mode, String pool, long now) {
        if (count < 1 || count > 1000) throw new IllegalArgumentException(T.s("抽取数量请填写 1–1000"));
        if (!"random".equals(mode) && !"sequence".equals(mode)) throw new IllegalArgumentException(T.s("抽取规则无效"));
        if (!POOL_ALL.equals(pool) && !POOL_DUE.equals(pool)) throw new IllegalArgumentException(T.s("抽取范围无效"));
        JSONArray selected = new JSONArray(); Set<String> unique = new HashSet<>();
        for (int i = 0; i < subjects.length(); i++) {
            String id = subjects.optString(i);
            if (m.deck(id) == null || m.deleted(id)) throw new IllegalArgumentException(T.s("请选择仍在使用的学科"));
            if (unique.add(id)) selected.put(id);
        }
        if (selected.length() == 0) throw new IllegalArgumentException(T.s("至少选择一个学科"));
        JSONObject s = new JSONObject(); MemoryData.put(s, "subjects", selected);
        MemoryData.put(s, "count", count); MemoryData.put(s, "mode", mode); MemoryData.put(s, "pool", pool);
        JSONObject r = root(m); MemoryData.put(r, "settings", s); r.remove("session");
        session(m, now);
    }
    private static JSONObject day(MemoryData m, long now) {
        JSONObject r = root(m), d = r.optJSONObject("day"); String date = MemoryData.date(now);
        if (d == null || !date.equals(d.optString("date"))) {
            d = new JSONObject(); MemoryData.put(d, "date", date); MemoryData.put(d, "seen", new JSONArray());
            MemoryData.put(r, "day", d); r.remove("session");
        }
        return d;
    }
    private static long endOfDay(long now) {
        Calendar end = Calendar.getInstance(); end.setTimeInMillis(now);
        end.set(Calendar.HOUR_OF_DAY, 23); end.set(Calendar.MINUTE, 59);
        end.set(Calendar.SECOND, 59); end.set(Calendar.MILLISECOND, 999);
        return end.getTimeInMillis();
    }
    private static List<String> bank(MemoryData m, long now) {
        Set<String> selected = new HashSet<>(); JSONArray ids = subjects(m);
        for (int i = 0; i < ids.length(); i++) selected.add(ids.optString(i));
        boolean dueOnly = POOL_DUE.equals(pool(m));
        long end = endOfDay(now);
        List<JSONObject> picked = new ArrayList<>();
        for (int i = 0; i < m.cards().length(); i++) {
            JSONObject c = m.cards().optJSONObject(i); String deck = c.optString("deck");
            if (!selected.contains(deck) || m.deleted(deck)) continue;
            if (dueOnly && (c.optInt("reps") <= 0 || c.optLong("due") > end)) continue;
            picked.add(c);
        }
        if (dueOnly) Collections.sort(picked, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) { return Long.compare(a.optLong("due"), b.optLong("due")); }
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) out.add(picked.get(i).optString("id"));
        return out;
    }
    private static List<String> unseen(MemoryData m, long now) {
        Set<String> seen = strings(day(m, now).optJSONArray("seen")); List<String> all = bank(m, now), out = new ArrayList<>();
        for (String id : all) if (!seen.contains(id)) out.add(id);
        return out;
    }
    public static JSONObject session(MemoryData m, long now) {
        day(m, now); JSONObject r = root(m), s = r.optJSONObject("session");
        if (s != null && MemoryData.date(now).equals(s.optString("date"))
                && (s.optJSONArray("ids").length() > 0 || unseen(m, now).isEmpty())) return s;
        JSONObject cfg = settings(m); List<String> pool = unseen(m, now);
        String mode = cfg.optString("mode", "random");
        if (POOL_DUE.equals(pool(m))) {
            // Due pool keeps ascending due order: oldest due first, never shuffled.
        } else if ("random".equals(mode)) Collections.shuffle(pool, new Random());
        else {
            // Continue after the last actually reviewed card, rather than repeating the first N every day.
            List<String> all = bank(m, now); String after = r.optString("sequenceAfter", ""); int start = all.indexOf(after);
            if (start >= 0 && !all.isEmpty()) {
                Set<String> eligible = new HashSet<>(pool); pool.clear();
                for (int i = 1; i <= all.size(); i++) {
                    String id = all.get((start + i) % all.size()); if (eligible.contains(id)) pool.add(id);
                }
            }
        }
        JSONArray chosen = new JSONArray(); int count = Math.min(cfg.optInt("count", 20), pool.size());
        for (int i = 0; i < count; i++) chosen.put(pool.get(i));
        s = new JSONObject(); MemoryData.put(s, "date", MemoryData.date(now)); MemoryData.put(s, "ids", chosen);
        MemoryData.put(s, "mode", mode); MemoryData.put(r, "session", s); return s;
    }
    public static JSONObject next(MemoryData m, long now) {
        JSONArray ids = session(m, now).optJSONArray("ids"); Set<String> seen = strings(day(m, now).optJSONArray("seen"));
        for (int i = 0; i < ids.length(); i++) {
            JSONObject c = m.card(ids.optString(i));
            if (c != null && !m.deleted(c.optString("deck")) && !seen.contains(c.optString("id"))) return c;
        }
        return null;
    }
    public static final class Progress {
        public int total, done, requested, available, reviewedToday;
    }
    public static Progress progress(MemoryData m, long now) {
        Progress p = new Progress(); JSONArray ids = session(m, now).optJSONArray("ids");
        Set<String> seen = strings(day(m, now).optJSONArray("seen"));
        for (int i = 0; i < ids.length(); i++) {
            JSONObject c = m.card(ids.optString(i)); if (c == null || m.deleted(c.optString("deck"))) continue;
            p.total++; if (seen.contains(c.optString("id"))) p.done++;
        }
        p.requested = settings(m).optInt("count", 20); p.available = bank(m, now).size(); p.reviewedToday = seen.size(); return p;
    }
    public static void requirePending(MemoryData m, String id, long now, String expectedDate) {
        if (!MemoryData.date(now).equals(expectedDate)) throw new IllegalArgumentException(T.s("日期已更新，请从今日闪卡继续"));
        JSONObject c = m.card(id);
        if (c == null || m.deleted(c.optString("deck")) || !MemoryData.contains(session(m, now).optJSONArray("ids"), id)
                || MemoryData.contains(day(m, now).optJSONArray("seen"), id))
            throw new IllegalArgumentException(T.s("这张闪卡已处理或不在当前抽取队列中"));
        JSONObject current = next(m, now);
        if (current == null || !id.equals(current.optString("id"))) throw new IllegalArgumentException(T.s("请按当前闪卡顺序复习"));
    }
    public static void complete(MemoryData m, String id, long now) {
        day(m, now).optJSONArray("seen").put(id);
        if ("sequence".equals(session(m, now).optString("mode"))) MemoryData.put(root(m), "sequenceAfter", id);
    }
    private static Set<String> strings(JSONArray a) {
        Set<String> out = new HashSet<>(); if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i)); return out;
    }
    public static void validate(MemoryData m) {
        Object value = m.db.opt("flashcards"); if (value == null) return;
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException(T.s("闪卡数据格式无效"));
        JSONObject r = (JSONObject) value, cfg = r.optJSONObject("settings");
        if (r.has("settings") && (cfg == null || cfg.optInt("count") < 1 || cfg.optInt("count") > 1000
                || (!"random".equals(cfg.optString("mode")) && !"sequence".equals(cfg.optString("mode")))
                || (cfg.has("pool") && !POOL_ALL.equals(cfg.opt("pool")) && !POOL_DUE.equals(cfg.opt("pool")))))
            throw new IllegalArgumentException(T.s("闪卡抽取设置无效"));
        if (cfg != null) checkIds(cfg.optJSONArray("subjects"), m, true);
        JSONObject d = r.optJSONObject("day"), s = r.optJSONObject("session");
        if (r.has("day") && (d == null || !d.optString("date").matches("\\d{4}-\\d{2}-\\d{2}")))
            throw new IllegalArgumentException(T.s("闪卡日期无效"));
        if (d != null) checkIds(d.optJSONArray("seen"), m, false);
        if (r.has("session") && (s == null || d == null || !s.optString("date").equals(d.optString("date"))
                || (!"random".equals(s.optString("mode")) && !"sequence".equals(s.optString("mode")))))
            throw new IllegalArgumentException(T.s("闪卡队列无效"));
        if (s != null) {
            checkIds(s.optJSONArray("ids"), m, false);
            if (s.optJSONArray("ids").length() > 1000) throw new IllegalArgumentException(T.s("闪卡队列超出数量限制"));
        }
    }
    private static void checkIds(JSONArray a, MemoryData m, boolean decks) {
        if (a == null || a.length() > (decks ? 200 : 30000) || (decks && a.length() == 0))
            throw new IllegalArgumentException(T.s("闪卡选择列表无效"));
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            String id = a.optString(i);
            if (!ids.add(id) || (decks ? m.deck(id) : m.card(id)) == null)
                throw new IllegalArgumentException(T.s("闪卡列表存在重复或未知项目"));
        }
    }
}
