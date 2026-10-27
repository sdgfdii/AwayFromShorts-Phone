package com.afs.guard;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Persistent time containers; enforcement continues to use RuleStore's per-app rules. */
public final class FreezeSlotStore {
    private static final String KEY = "freeze_slots";
    public static final class Slot {
        public String id;
        public Rule rule;
        Slot(String id, Rule rule) { this.id = id; this.rule = rule; }
    }
    private FreezeSlotStore() { }

    private static final java.util.LinkedHashMap<String,String> KEYS = new java.util.LinkedHashMap<>();
    static synchronized String scheduleKey(Rule rule) {
        String source=rule.withGate(true).groupKey();
        String cached=KEYS.get(source);if(cached!=null)return cached;
        String key=computeKey(rule);KEYS.put(source,key);
        if(KEYS.size()>96)KEYS.remove(KEYS.keySet().iterator().next());
        return key;
    }
    private static String computeKey(Rule rule) {
        Rule normalized = rule.freezeEditorRule();
        if (normalized != null && normalized.freeze && normalized.days == Rule.ALL_DAYS) {
            java.util.BitSet minutes = new java.util.BitSet(1440);
            for (int[] w : normalized.windows()) {
                if (w[0] == w[1]) minutes.set(0, 1440);
                else if (w[0] < w[1]) minutes.set(w[0], w[1]);
                else { minutes.set(w[0], 1440); minutes.set(0, w[1]); }
            }
            return "daily:" + java.util.Arrays.toString(minutes.toLongArray());
        }
        return (normalized == null ? rule : normalized).withGate(true).groupKey();
    }
    static boolean unassigned(Rule rule) {
        Rule normalized = rule.freezeEditorRule();
        return normalized != null && normalized.freeze
                && (normalized.days == 0 || normalized.windowCount() == 0);
    }
    public static List<Slot> all(Context c) {
        List<Slot> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences("afs_guard", 0).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                JSONArray w = o.optJSONArray("windows");
                if (w == null) continue;
                int[][] windows = new int[w.length()][2];
                for (int j = 0; j < w.length(); j++) {
                    JSONArray pair = w.optJSONArray(j);
                    if (pair != null) { windows[j][0] = pair.optInt(0); windows[j][1] = pair.optInt(1); }
                }
                String id = o.optString("id", "");
                if (!id.isEmpty()) out.add(new Slot(id, new Rule(o.optInt("days", 127), windows, true,
                        o.optBoolean("freeze", true))));
            }
        } catch (Exception ignored) { }
        boolean changed = false;
        for (Rule r : RuleStore.all(c).values()) {
            if (unassigned(r)) continue;
            boolean found = false;
            for (Slot slot : out) if (scheduleKey(slot.rule).equals(scheduleKey(r))) { found = true; break; }
            if (!found) {
                Rule normalized = r.freezeEditorRule();
                out.add(new Slot(java.util.UUID.randomUUID().toString(), (normalized == null ? r : normalized).withGate(true)));
                changed = true;
            }
        }
        if (changed) save(c, out);
        return out;
    }
    public static List<String> members(Slot slot, Map<String, Rule> rules) {
        List<String> out = new ArrayList<>();
        String key = scheduleKey(slot.rule);
        for (Map.Entry<String, Rule> e : rules.entrySet()) {
            if (!unassigned(e.getValue()) && key.equals(scheduleKey(e.getValue()))) out.add(e.getKey());
        }
        return out;
    }
    public static void save(Context c, List<Slot> slots) {
        JSONArray a = new JSONArray();
        try {
            for (Slot slot : slots) {
                JSONObject o = new JSONObject();
                o.put("id", slot.id); o.put("days", slot.rule.days); o.put("freeze", slot.rule.freeze);
                JSONArray windows = new JSONArray();
                for (int[] pair : slot.rule.windows()) windows.put(new JSONArray().put(pair[0]).put(pair[1]));
                o.put("windows", windows); a.put(o);
            }
        } catch (Exception e) { return; }
        c.getSharedPreferences("afs_guard", 0).edit().putString(KEY, a.toString()).apply();
    }
    public static Rule withoutSchedule() { return new Rule(0, new int[0][2], true, true); }

    /**
     * 删掉与给定时段相同、且已经没有任何应用的空格。
     *
     * 只在一次性迁移里用：整格改时段后，旧时段会留下一个"0 个应用"的壳，
     * 用户手动拖空的时间格不该被这里顺手删掉，所以不做隐含清理。
     */
    static void pruneEmpty(Context c, Rule schedule) {
        String key = scheduleKey(schedule);
        java.util.Map<String, Rule> rules = RuleStore.all(c);
        List<Slot> out = new ArrayList<>();
        boolean changed = false;
        for (Slot slot : all(c)) {
            if (key.equals(scheduleKey(slot.rule)) && members(slot, rules).isEmpty()) {
                changed = true;
                continue;
            }
            out.add(slot);
        }
        if (changed) save(c, out);
    }

    /** Frozen portions of the displayed local day; end=1440 is the right edge of the rail. */
    static int[][] frozenSegments(Rule rule, java.util.Calendar day) {
        // A schedule changes only at its window boundaries. Build their union once,
        // rather than normalizing 1,440 Calendar instances during the first draw.
        java.util.BitSet frozen = new java.util.BitSet(1440);
        int today = Rule.monIndex(day), yesterday = (today + 6) % 7;
        for (int[] window : rule.windows()) {
            int start = window[0], end = window[1];
            if (start == end) {
                if (Rule.dayOn(rule.days, today)) frozen.set(0, 1440);
            } else if (start < end) {
                if (Rule.dayOn(rule.days, today)) frozen.set(start, end);
            } else {
                if (Rule.dayOn(rule.days, today)) frozen.set(start, 1440);
                if (Rule.dayOn(rule.days, yesterday)) frozen.set(0, end);
            }
        }
        if (!rule.freeze) frozen.flip(0, 1440); // Legacy rules describe allowed windows.
        List<int[]> segments = new ArrayList<>();
        for (int start = frozen.nextSetBit(0); start >= 0 && start < 1440;) {
            int end = Math.min(1440, frozen.nextClearBit(start));
            segments.add(new int[]{start, end}); start = frozen.nextSetBit(end);
        }
        return segments.toArray(new int[0][]);
    }
}
