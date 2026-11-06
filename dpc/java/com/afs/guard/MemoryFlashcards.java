package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 每日闪卡抽取：不再有「随机 / 顺序 / 全部池 / 仅到期池」这些规则，只看遗忘曲线。
 *
 * 排序依据是 FSRS 的可提取率（{@link MemoryScheduler#retrievability}）：
 * 已到期的旧卡按「此刻还记得的概率」从低到高排 —— 忘得最厉害的先复习；
 * 从未学过的新卡按入库顺序补位，并受每天新学上限约束。
 *
 * 判「到期」仍由 FSRS 的 due 决定（各学科自己的目标记忆率），这里只决定先后。
 * 每个额度日只排一次队列并存盘：中途改设置才重排，已复习记录永远保留。
 */
public final class MemoryFlashcards {
    public static final int DEFAULT_COUNT = 20, DEFAULT_NEW_LIMIT = 10, MAX_COUNT = 1000;
    private MemoryFlashcards() { }
    private static JSONObject root(MemoryData m) {
        JSONObject r = m.db.optJSONObject("flashcards");
        if (r == null) { r = new JSONObject(); MemoryData.put(m.db, "flashcards", r); }
        return r;
    }
    /** 老数据里的 mode / pool 不再读取；写回时会被丢掉，等于旧规则退场。 */
    public static JSONObject settings(MemoryData m) {
        JSONObject s = root(m).optJSONObject("settings");
        return s != null ? s : new JSONObject();
    }
    public static int count(MemoryData m) {
        return clamp(settings(m).optInt("count", DEFAULT_COUNT), 1, MAX_COUNT);
    }
    public static int newLimit(MemoryData m) {
        return clamp(settings(m).optInt("newLimit", DEFAULT_NEW_LIMIT), 0, MAX_COUNT);
    }
    public static JSONArray subjects(MemoryData m) {
        JSONArray configured = settings(m).optJSONArray("subjects");
        if (configured != null) return configured;
        JSONArray ids = new JSONArray(), active = m.activeDecks();
        for (int i = 0; i < active.length(); i++) ids.put(active.optJSONObject(i).optString("id"));
        return ids;
    }
    public static void configure(MemoryData m, JSONArray subjects, int count, int newLimit, long now) {
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException(T.s("抽取数量请填写 1–1000"));
        if (newLimit < 0 || newLimit > MAX_COUNT) throw new IllegalArgumentException(T.s("新学上限请填写 0–1000"));
        JSONArray selected = new JSONArray(); Set<String> unique = new HashSet<>();
        for (int i = 0; i < subjects.length(); i++) {
            String id = subjects.optString(i);
            if (m.deck(id) == null || m.deleted(id)) throw new IllegalArgumentException(T.s("请选择仍在使用的学科"));
            if (unique.add(id)) selected.put(id);
        }
        if (selected.length() == 0) throw new IllegalArgumentException(T.s("至少选择一个学科"));
        // 写一份干净的设置：mode / pool 这些旧字段到这里就断了。
        JSONObject s = new JSONObject(); MemoryData.put(s, "subjects", selected);
        MemoryData.put(s, "count", count); MemoryData.put(s, "newLimit", newLimit);
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
    private static Set<String> selected(MemoryData m) {
        Set<String> ids = new HashSet<>(); JSONArray a = subjects(m);
        for (int i = 0; i < a.length(); i++) ids.add(a.optString(i));
        return ids;
    }
    /**
     * 到期旧卡：已学过、今天结束前到期，按「此刻还记得的概率」升序。
     *
     * 学习步 / 重学步（state != REVIEW）没有按天的稳定度，到期是分钟级的，
     * 排序上给最小值 —— 刚忘掉的卡本来就该最先见到。
     */
    static List<JSONObject> reviews(MemoryData m, long now) {
        Set<String> decks = selected(m); long end = endOfDay(now);
        List<JSONObject> out = new ArrayList<>();
        JSONArray cards = m.cards();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject c = cards.optJSONObject(i); if (c == null) continue;
            String deck = c.optString("deck");
            if (!decks.contains(deck) || m.deleted(deck)) continue;
            if (c.optInt("reps") <= 0 || c.optLong("due") > end) continue;
            out.add(c);
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                double ra = risk(a, now), rb = risk(b, now);
                if (ra != rb) return ra < rb ? -1 : 1;
                return Long.compare(a.optLong("due"), b.optLong("due"));
            }
        });
        return out;
    }
    private static double risk(JSONObject c, long now) {
        if (c.optInt("state", MemoryScheduler.LEARNING) != MemoryScheduler.REVIEW) return 0;
        return MemoryScheduler.retrievability(c.optDouble("stability", 0), c.optLong("last", 0), now);
    }
    /** 新卡：从未学过，按入库顺序（cards 数组即导入顺序）。 */
    static List<JSONObject> fresh(MemoryData m, long now) {
        Set<String> decks = selected(m); List<JSONObject> out = new ArrayList<>();
        JSONArray cards = m.cards();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject c = cards.optJSONObject(i); if (c == null) continue;
            String deck = c.optString("deck");
            if (!decks.contains(deck) || m.deleted(deck)) continue;
            if (c.optInt("reps") > 0) continue;
            out.add(c);
        }
        return out;
    }
    private static Set<String> strings(JSONArray a) {
        Set<String> out = new HashSet<>(); if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i)); return out;
    }
    /** 队列：到期旧卡（按遗忘风险）在前，新卡（按入库顺序）按新学上限补在后。 */
    public static JSONObject session(MemoryData m, long now) {
        day(m, now); JSONObject r = root(m), s = r.optJSONObject("session");
        if (s != null && MemoryData.date(now).equals(s.optString("date"))) {
            if (s.optJSONArray("ids").length() > 0) return s;
            // 空队列不冻结一整天：加了新卡、或有卡到期，就重排一次。
            if (reviews(m, now).isEmpty() && fresh(m, now).isEmpty()) return s;
        }
        Set<String> seen = strings(day(m, now).optJSONArray("seen"));
        int limit = count(m), freshLimit = newLimit(m);
        List<String> ids = new ArrayList<>();
        for (JSONObject c : reviews(m, now)) {
            if (ids.size() >= limit) break;
            String id = c.optString("id"); if (!finished(c, seen)) ids.add(id);
        }
        int room = Math.min(freshLimit, limit - ids.size());
        if (room > 0) {
            for (JSONObject c : fresh(m, now)) {
                if (room <= 0) break;
                String id = c.optString("id");
                if (seen.contains(id)) continue;
                ids.add(id); room--;
            }
        }
        JSONArray chosen = new JSONArray(); for (String id : ids) chosen.put(id);
        s = new JSONObject(); MemoryData.put(s, "date", MemoryData.date(now)); MemoryData.put(s, "ids", chosen);
        MemoryData.put(r, "session", s); return s;
    }
    public static JSONObject next(MemoryData m, long now) {
        JSONArray ids = session(m, now).optJSONArray("ids"); Set<String> seen = strings(day(m, now).optJSONArray("seen"));
        JSONObject first = null;
        for (int i = 0; i < ids.length(); i++) {
            JSONObject c = m.card(ids.optString(i));
            if (c == null || m.deleted(c.optString("deck")) || finished(c, seen) || waiting(c, now)) continue;
            // Due relearning takes priority; a future minute-step must never block fresh cards.
            if (seen.contains(c.optString("id"))) return c;
            if (first == null) first = c;
        }
        return first;
    }
    private static boolean finished(JSONObject c, Set<String> seen) {
        return seen.contains(c.optString("id")) && c.optInt("state") == MemoryScheduler.REVIEW;
    }
    private static boolean waiting(JSONObject c, long now) {
        return c.optInt("reps") > 0 && c.optInt("state") != MemoryScheduler.REVIEW && c.optLong("due") > now;
    }
    public static final class Load {
        public boolean sessionExists;
        public int newPending, reviewPending, processed, queued, backlog, availableNew, newLimit, count;
    }
    /** Reads only existing state. In particular, never calls root/day/session/settings. */
    public static Load inspectLoad(MemoryData m, long now) {
        Load out=new Load();JSONObject r=m.db.optJSONObject("flashcards");
        JSONObject cfg=r==null?null:r.optJSONObject("settings");
        out.count=clamp(cfg==null?DEFAULT_COUNT:cfg.optInt("count",DEFAULT_COUNT),1,MAX_COUNT);
        out.newLimit=clamp(cfg==null?DEFAULT_NEW_LIMIT:cfg.optInt("newLimit",DEFAULT_NEW_LIMIT),0,MAX_COUNT);
        Set<String> selected=new HashSet<>();JSONArray configured=cfg==null?null:cfg.optJSONArray("subjects");
        JSONArray active=m.activeDecks();
        for(int i=0;i<active.length();i++) {
            String id=active.optJSONObject(i).optString("id");
            if(configured==null || MemoryData.contains(configured,id))selected.add(id);
        }
        java.util.Map<String,JSONObject> cards=new java.util.HashMap<>();JSONArray all=m.cards();long end=endOfDay(now);
        for(int i=0;i<all.length();i++) {
            JSONObject c=all.optJSONObject(i);if(c==null || !selected.contains(c.optString("deck")))continue;
            cards.put(c.optString("id"),c);
            if(c.optInt("reps")<=0)out.availableNew++;
            else if(c.optLong("due")<=end)out.backlog++;
        }
        String date=MemoryData.date(now);JSONObject day=r==null?null:r.optJSONObject("day");
        Set<String> seen=day!=null && date.equals(day.optString("date"))?strings(day.optJSONArray("seen")):new HashSet<String>();
        for(String id:seen)if(cards.containsKey(id))out.processed++;
        JSONObject session=r==null?null:r.optJSONObject("session");
        JSONArray ids=session==null?null:session.optJSONArray("ids");
        out.sessionExists=session!=null && date.equals(session.optString("date")) && ids!=null;
        if(out.sessionExists)for(int i=0;i<ids.length();i++) {
            String id=ids.optString(i);JSONObject c=cards.get(id);if(c==null)continue;
            out.queued++;
            if(finished(c,seen))continue;
            if(c.optInt("reps")<=0)out.newPending++;else out.reviewPending++;
        }
        return out;
    }
    public static final class Progress {
        public int total, done, requested, available, reviewedToday, newLimit;
        public long nextDue;
    }
    public static Progress progress(MemoryData m, long now) {
        Progress p = new Progress(); JSONArray ids = session(m, now).optJSONArray("ids");
        Set<String> seen = strings(day(m, now).optJSONArray("seen"));
        for (int i = 0; i < ids.length(); i++) {
            JSONObject c = m.card(ids.optString(i)); if (c == null || m.deleted(c.optString("deck"))) continue;
            p.total++; if (finished(c, seen)) p.done++;
            else if (waiting(c, now) && (p.nextDue == 0 || c.optLong("due") < p.nextDue)) p.nextDue = c.optLong("due");
        }
        p.requested = count(m); p.newLimit = newLimit(m);
        p.available = reviews(m, now).size() + fresh(m, now).size();
        p.reviewedToday = seen.size(); return p;
    }
    public static void requirePending(MemoryData m, String id, long now, String expectedDate) {
        if (!MemoryData.date(now).equals(expectedDate)) throw new IllegalArgumentException(T.s("日期已更新，请从今日闪卡继续"));
        JSONObject c = m.card(id);
        if (c == null || m.deleted(c.optString("deck")) || !MemoryData.contains(session(m, now).optJSONArray("ids"), id)
                || finished(c, strings(day(m, now).optJSONArray("seen"))))
            throw new IllegalArgumentException(T.s("这张闪卡已处理或不在当前抽取队列中"));
        JSONObject current = next(m, now);
        if (current == null || !id.equals(current.optString("id"))) throw new IllegalArgumentException(T.s("请按当前闪卡顺序复习"));
    }
    public static void complete(MemoryData m, String id, long now) {
        JSONArray seen = day(m, now).optJSONArray("seen");
        if (!MemoryData.contains(seen, id)) seen.put(id);
    }
    public static void validate(MemoryData m) {
        Object value = m.db.opt("flashcards"); if (value == null) return;
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException(T.s("闪卡数据格式无效"));
        JSONObject r = (JSONObject) value, cfg = r.optJSONObject("settings");
        // mode / pool 属于已经删掉的旧规则：存在也只当没看见，不再校验、不再写回。
        if (r.has("settings") && (cfg == null || cfg.optInt("count", DEFAULT_COUNT) < 1
                || cfg.optInt("count", DEFAULT_COUNT) > MAX_COUNT
                || cfg.optInt("newLimit", DEFAULT_NEW_LIMIT) < 0
                || cfg.optInt("newLimit", DEFAULT_NEW_LIMIT) > MAX_COUNT))
            throw new IllegalArgumentException(T.s("闪卡抽取设置无效"));
        if (cfg != null) checkIds(cfg.optJSONArray("subjects"), m, true);
        JSONObject d = r.optJSONObject("day"), s = r.optJSONObject("session");
        if (r.has("day") && (d == null || !d.optString("date").matches("\\d{4}-\\d{2}-\\d{2}")))
            throw new IllegalArgumentException(T.s("闪卡日期无效"));
        if (d != null) checkIds(d.optJSONArray("seen"), m, false);
        if (r.has("session") && (s == null || d == null || !s.optString("date").equals(d.optString("date"))))
            throw new IllegalArgumentException(T.s("闪卡队列无效"));
        if (s != null) {
            checkIds(s.optJSONArray("ids"), m, false);
            if (s.optJSONArray("ids").length() > MAX_COUNT) throw new IllegalArgumentException(T.s("闪卡队列超出数量限制"));
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
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
