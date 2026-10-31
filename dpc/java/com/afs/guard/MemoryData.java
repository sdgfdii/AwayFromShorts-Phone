package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Pure, offline-testable card/queue/credit model; Android IO lives in MemoryStore. */
public final class MemoryData {
    public static final String GOAL_PREFIX = "memory:";
    public static final double DEFAULT_RETENTION = MemoryScheduler.DEFAULT_RETENTION;
    public static final int DEFAULT_MAX_INTERVAL = MemoryScheduler.DEFAULT_MAX_INTERVAL_DAYS;
    public final JSONObject db;
    private final java.util.Map<String, JSONObject> cardIndex = new java.util.HashMap<>();
    private final java.util.Map<String, JSONObject> deckIndex = new java.util.HashMap<>();
    public MemoryData() {
        db = new JSONObject();
        put(db, "version", 1); put(db, "decks", new JSONArray());
        put(db, "cards", new JSONArray()); put(db, "logs", new JSONArray());
        put(db, "plans", new JSONObject());
        addDeck(T.s("英语单词"), "english"); addDeck(T.s("医学"), "medical"); addDeck(T.s("工科概念"), "engineering");
    }
    public MemoryData(String json) {
        try {
            db = new JSONObject(json);
            if (decks() != null) for (int i = 0; i < decks().length(); i++) {
                JSONObject d = decks().optJSONObject(i); if (d != null) deckIndex.put(d.optString("id"), d);
            }
            if (cards() != null) for (int i = 0; i < cards().length(); i++) {
                JSONObject c = cards().optJSONObject(i); if (c != null) cardIndex.put(c.optString("id"), c);
            }
            validate();
        }
        catch (Exception e) { throw new IllegalArgumentException(T.s("记忆数据格式不正确：") + e.getMessage(), e); }
    }
    public static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception e) { throw new IllegalArgumentException(k, e); }
    }
    public static JSONObject copy(JSONObject o) {
        try { return new JSONObject(o.toString()); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    public static String date(long now) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now));
    }
    public static String id() { return UUID.randomUUID().toString(); }
    public JSONArray decks() { return db.optJSONArray("decks"); }
    public JSONArray cards() { return db.optJSONArray("cards"); }
    public JSONObject deck(String id) { return deckIndex.get(id); }
    public JSONObject card(String id) { return cardIndex.get(id); }
    public boolean deleted(String id) { JSONObject d=deck(id);return d!=null && d.optLong("deletedAt")>0; }
    public JSONArray activeDecks() { return selectedDecks(false); }
    public JSONArray deletedDecks() { return selectedDecks(true); }
    private JSONArray selectedDecks(boolean trash) {
        JSONArray selected=new JSONArray();
        for(int i=0;i<decks().length();i++){JSONObject d=decks().optJSONObject(i);if((d.optLong("deletedAt")>0)==trash)selected.put(d);}
        return selected;
    }
    public void deleteDeck(String id,long now) {
        JSONObject d=deck(id);if(d==null || deleted(id))throw new IllegalArgumentException(T.s("学科不存在或已删除"));
        put(d,"deletedAt",Math.max(1,now));
    }
    public void restoreDeck(String id) {
        JSONObject d=deck(id);if(d==null || !deleted(id))throw new IllegalArgumentException(T.s("回收站中没有这个学科"));
        JSONArray active=activeDecks();for(int i=0;i<active.length();i++)if(d.optString("name").equals(active.optJSONObject(i).optString("name")))throw new IllegalArgumentException(T.s("已有同名学科，请先修改名称再恢复"));
        d.remove("deletedAt");
    }
    public JSONObject addDeck(String name, String subject) {
        name=name.trim();subject=subject.trim();
        if(name.isEmpty() || name.length()>40 || subject.isEmpty() || subject.length()>40 || name.matches(".*[\\p{Cntrl}].*") || subject.matches(".*[\\p{Cntrl}].*"))
            throw new IllegalArgumentException(T.s("学科名称请填写 1 至 40 个字，不能包含换行"));
        for(int i=0;i<decks().length();i++)if(name.equals(decks().optJSONObject(i).optString("name")))
            throw new IllegalArgumentException(deleted(decks().optJSONObject(i).optString("id"))?T.s("回收站中已有同名学科，请先恢复"):T.s("已有同名学科，请直接使用原学科或换一个名称"));
        if (decks().length() >= 200) throw new IllegalArgumentException(T.s("最多创建 200 个卡组"));
        JSONObject d = new JSONObject(); put(d, "id", id());
        put(d, "name", name); put(d, "subject", subject);
        put(d, "newLimit", 10); put(d, "reviewLimit", 50); put(d, "target", 20);
        put(d, "linked", false); decks().put(d); deckIndex.put(d.optString("id"), d); return d;
    }
    public void settings(String id, String name, int newLimit, int reviewLimit, int target, boolean linked) {
        // NaN / -1 mean "leave the optional FSRS keys untouched", so old callers keep the old file shape.
        settings(id, name, newLimit, reviewLimit, target, linked, Double.NaN, -1);
    }
    public void settings(String id, String name, int newLimit, int reviewLimit, int target, boolean linked,
                         double requestRetention, int maxIntervalDays) {
        if (name.trim().isEmpty() || newLimit < 0 || newLimit > 200
                || reviewLimit < 1 || reviewLimit > 1000 || target < 1 || target > 1000)
            throw new IllegalArgumentException(T.s("新卡 0–200，复习 1–1000，目标 1–1000；名称不能为空"));
        boolean writesRetention = !Double.isNaN(requestRetention);
        if (writesRetention && !(requestRetention >= 0.70 && requestRetention <= 0.97))
            throw new IllegalArgumentException(T.s("目标记忆率请填写 70–97（百分比）"));
        if (maxIntervalDays >= 0 && (maxIntervalDays < 1 || maxIntervalDays > 36500))
            throw new IllegalArgumentException(T.s("单次复习上限间隔请填写 1–36500 天"));
        JSONObject d = deck(id);
        put(d, "name", name.trim()); put(d, "newLimit", newLimit);
        put(d, "reviewLimit", reviewLimit); put(d, "target", target); put(d, "linked", linked);
        if (writesRetention) put(d, "requestRetention", requestRetention);
        if (maxIntervalDays >= 0) put(d, "maxInterval", maxIntervalDays);
        resetUnstarted(id);
        // A plan becomes fixed after the first answer, not after a dashboard preview.
    }
    /** Optional per-deck FSRS tuning; a missing or invalid value reads as the legacy default. */
    public static double retention(JSONObject deck) {
        if (deck == null) return DEFAULT_RETENTION;
        double v = deck.optDouble("requestRetention", DEFAULT_RETENTION);
        return v >= 0.70 && v <= 0.97 ? v : DEFAULT_RETENTION;
    }
    public static int maxInterval(JSONObject deck) {
        if (deck == null) return DEFAULT_MAX_INTERVAL;
        int v = deck.optInt("maxInterval", DEFAULT_MAX_INTERVAL);
        return v >= 1 && v <= 36500 ? v : DEFAULT_MAX_INTERVAL;
    }
    public static JSONObject newCard(String deck, String type, String front, String answer,
            String source, String note) {
        JSONObject c = new JSONObject(); put(c, "id", id()); put(c, "deck", deck);
        put(c, "type", type); put(c, "front", front.trim()); put(c, "answer", answer.trim());
        put(c, "source", source.trim()); put(c, "note", note.trim());
        put(c, "image", ""); put(c, "masks", new JSONArray());
        return c;
    }
    public void saveCard(JSONObject c) {
        validateCard(c);
        JSONObject existing = card(c.optString("id"));
        if (existing == null) {
            if (cards().length() >= 30000) throw new IllegalArgumentException(T.s("当前卡库最多支持 30000 张卡片"));
            JSONObject added = copy(c); cards().put(added); cardIndex.put(added.optString("id"), added);
        }
        else {
            for (String k : new String[]{"deck", "type", "front", "answer", "source", "note", "image", "masks"})
                put(existing, k, c.opt(k));
        }
        resetUnstarted(c.optString("deck"));
    }
    private void resetUnstarted(String deck) {
        JSONObject today = db.optJSONObject("plans").optJSONObject(date(System.currentTimeMillis()));
        JSONObject p = today == null ? null : today.optJSONObject(deck);
        if (p != null && !p.optBoolean("started")) today.remove(deck);
    }
    public int count(String deck) {
        int n = 0;
        for (int i = 0; i < cards().length(); i++) if (deck.equals(cards().optJSONObject(i).optString("deck"))) n++;
        return n;
    }
    public static MemoryScheduler.State state(JSONObject c) {
        MemoryScheduler.State s = new MemoryScheduler.State();
        s.stability = c.optDouble("stability", 0); s.difficulty = c.optDouble("difficulty", 0);
        s.state = c.optInt("state", 0); s.step = c.optInt("step", 0);
        s.reps = c.optInt("reps", 0); s.lapses = c.optInt("lapses", 0);
        s.last = c.optLong("last", 0); s.due = c.optLong("due", 0); return s;
    }
    public static final class Progress {
        public int total, target, done, ready, remaining, newCount, reviewCount;
        public long nextDue;
        public boolean linked;
    }
    /** A fixed daily set prevents adding new cards/limit changes from moving today's goalposts. */
    public JSONObject plan(String deck, long now) {
        JSONObject days = db.optJSONObject("plans");
        String key = date(now);
        JSONObject today = days.optJSONObject(key);
        if (today == null) { today = new JSONObject(); put(days, key, today); }
        JSONObject p = today.optJSONObject(deck);
        if (p != null) return p;
        JSONObject d = deck(deck);
        List<JSONObject> due = new ArrayList<>(), fresh = new ArrayList<>();
        Calendar end = Calendar.getInstance(); end.setTimeInMillis(now);
        end.set(Calendar.HOUR_OF_DAY, 23); end.set(Calendar.MINUTE, 59); end.set(Calendar.SECOND, 59); end.set(Calendar.MILLISECOND, 999);
        for (int i = 0; i < cards().length(); i++) {
            JSONObject c = cards().optJSONObject(i);
            if (!deck.equals(c.optString("deck"))) continue;
            if (c.optInt("reps") == 0) fresh.add(c);
            else if (c.optLong("due") <= now || (c.optInt("state") != MemoryScheduler.REVIEW && c.optLong("due") <= end.getTimeInMillis())) due.add(c);
        }
        Collections.sort(due, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) { return Long.compare(a.optLong("due"), b.optLong("due")); }
        });
        int reviewLimit = d.optInt("reviewLimit", 50), newLimit = d.optInt("newLimit", 10);
        int nr = Math.min(reviewLimit, due.size());
        // Clear the backlog before adding more new material.
        int nn = due.size() > reviewLimit ? 0 : Math.min(newLimit, fresh.size());
        JSONArray ids = new JSONArray();
        for (int i = 0; i < nr; i++) ids.put(due.get(i).optString("id"));
        for (int i = 0; i < nn; i++) ids.put(fresh.get(i).optString("id"));
        p = new JSONObject(); put(p, "ids", ids); put(p, "target", Math.min(d.optInt("target", 20), ids.length()));
        put(p, "newCount", nn); put(p, "reviewCount", nr); put(p, "linked", d.optBoolean("linked"));
        put(p, "passed", new JSONArray()); put(today, deck, p);
        return p;
    }
    public Progress progress(String deck, long now) {
        JSONObject p = plan(deck, now); Progress v = new Progress();
        JSONArray ids = p.optJSONArray("ids"), passed = p.optJSONArray("passed");
        v.newCount = p.optInt("newCount"); v.reviewCount = p.optInt("reviewCount");
        v.linked = p.optBoolean("linked");
        for (int i = 0; i < ids.length(); i++) {
            JSONObject c = card(ids.optString(i)); if (c == null) continue;
            v.total++;
            if (contains(passed, c.optString("id"))) { v.done++; continue; }
            long due = c.optLong("due");
            if (due <= now) v.ready++;
            else if (v.nextDue == 0 || due < v.nextDue) v.nextDue = due;
        }
        v.target = Math.min(p.optInt("target"), v.total);
        v.remaining = Math.max(0, v.target - v.done); return v;
    }
    public int pending(long now) {
        int n = 0;
        for (int i = 0; i < decks().length(); i++) {
            if(deleted(decks().optJSONObject(i).optString("id")))continue;
            Progress p = progress(decks().optJSONObject(i).optString("id"), now);
            if (p.linked && p.remaining > 0) n++;
        }
        return n;
    }
    public boolean hasTargets(long now) {
        for (int i = 0; i < decks().length(); i++) {
            if(deleted(decks().optJSONObject(i).optString("id")))continue;
            Progress p = progress(decks().optJSONObject(i).optString("id"), now);
            if (p.linked && p.target > 0) return true;
        }
        return false;
    }
    public JSONObject next(String deck, long now) {
        if(deleted(deck))return null;
        JSONObject p = plan(deck, now), best = null;
        JSONArray ids = p.optJSONArray("ids"), passed = p.optJSONArray("passed");
        for (int i = 0; i < ids.length(); i++) {
            JSONObject c = card(ids.optString(i));
            if (c == null || contains(passed, c.optString("id")) || c.optLong("due") > now) continue;
            if (best == null || priority(c) < priority(best)
                    || (priority(c) == priority(best) && c.optLong("due") < best.optLong("due"))) best = c;
        }
        return best;
    }
    private static int priority(JSONObject c) {
        return c.optInt("reps") == 0 ? 2 : c.optInt("state") == MemoryScheduler.REVIEW ? 1 : 0;
    }
    public static boolean contains(JSONArray a, String id) {
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optString(i))) return true;
        return false;
    }
    /** Called only after revealing an answer. Future/duplicate cards cannot earn credit. */
    public boolean rate(String id, int rating, long now) {
        JSONObject c = card(id);
        if(c!=null && deleted(c.optString("deck")))throw new IllegalArgumentException(T.s("学科已移入回收站，请先恢复"));
        if (c == null || c.optLong("due") > now) throw new IllegalArgumentException(T.s("卡片尚未到复习时间"));
        JSONObject p = plan(c.optString("deck"), now);
        JSONArray passed = p.optJSONArray("passed");
        if (!contains(p.optJSONArray("ids"), id) || contains(passed, id))
            throw new IllegalArgumentException(T.s("这张卡不在今日待学队列中"));
        return applyRating(c, rating, now, p, false);
    }
    public void rateFlashcard(String id, int rating, long now, String expectedDate) {
        MemoryFlashcards.requirePending(this, id, now, expectedDate);
        JSONObject c = card(id), today = db.optJSONObject("plans").optJSONObject(date(now));
        JSONObject p = today == null ? null : today.optJSONObject(c.optString("deck"));
        applyRating(c, rating, now, p, true);
        MemoryFlashcards.complete(this, id, now);
    }
    private boolean applyRating(JSONObject c, int rating, long now, JSONObject p, boolean flashcard) {
        String id = c.optString("id");
        JSONObject d = deck(c.optString("deck"));
        MemoryScheduler.State n = MemoryScheduler.next(state(c), rating, now, retention(d), maxInterval(d));
        boolean inPlan = p != null && contains(p.optJSONArray("ids"), id);
        if (inPlan) put(p, "started", true);
        if (n.state == MemoryScheduler.REVIEW) {
            // Day-based reviews become available at local midnight; minute-based steps keep exact times.
            Calendar day = Calendar.getInstance(); day.setTimeInMillis(now);
            int interval = (int) ((n.due - now) / MemoryScheduler.DAY);
            day.add(Calendar.DAY_OF_MONTH, interval);
            day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0); day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
            n.due = day.getTimeInMillis();
        }
        put(c, "stability", n.stability); put(c, "difficulty", n.difficulty);
        put(c, "state", n.state); put(c, "step", n.step); put(c, "reps", n.reps);
        put(c, "lapses", n.lapses); put(c, "last", n.last); put(c, "due", n.due);
        boolean credit = rating >= 2 && n.state == MemoryScheduler.REVIEW;
        if (credit && inPlan && !contains(p.optJSONArray("passed"), id)) p.optJSONArray("passed").put(id);
        JSONObject log = new JSONObject(); put(log, "card", id); put(log, "deck", c.optString("deck"));
        put(log, "ts", now); put(log, "rating", rating); put(log, "credit", credit);
        if (flashcard) put(log, "flashcard", true);
        db.optJSONArray("logs").put(log); return credit;
    }
    public static String question(JSONObject c) {
        return c.optString("front").replaceAll("(?s)\\{\\{(.*?)\\}\\}", "［ … ］");
    }
    public static String answer(JSONObject c) {
        if (!"cloze".equals(c.optString("type"))) return c.optString("answer");
        return c.optString("front").replace("{{", "").replace("}}", "")
                + (c.optString("answer").isEmpty() ? "" : "\n\n" + c.optString("answer"));
    }
    public static boolean matches(String input, String expected, boolean english) {
        String a = normalize(input, english);
        if (a.isEmpty()) return false;
        for (String variant : expected.split("\\|")) if (a.equals(normalize(variant, english))) return true;
        return false;
    }
    private static String normalize(String s, boolean english) {
        String v = Normalizer.normalize(s.trim(), Normalizer.Form.NFKC).replaceAll("\\s+", " ");
        return english ? v.toLowerCase(Locale.ROOT) : v;
    }
    private void validateCard(JSONObject c) {
        if (c == null || c.optString("id").isEmpty() || deck(c.optString("deck")) == null)
            throw new IllegalArgumentException(T.s("卡片标识或卡组无效"));
        String type = c.optString("type"), front = c.optString("front");
        if (!type.equals("qa") && !type.equals("input") && !type.equals("cloze") && !type.equals("image"))
            throw new IllegalArgumentException(T.s("未知卡片类型"));
        if (front.trim().isEmpty() || front.length() > 20000 || c.optString("answer").length() > 20000
                || c.optString("source").length() > 20000 || c.optString("note").length() > 20000)
            throw new IllegalArgumentException(T.s("题目不能为空；题目或答案不能超过 20000 字"));
        if (type.equals("cloze") && !java.util.regex.Pattern.compile("\\{\\{[^{}]+\\}\\}").matcher(front).find())
            throw new IllegalArgumentException(T.s("填空题请用 {{答案}} 标出需要遮住的部分"));
        if ((type.equals("qa") || type.equals("input")) && c.optString("answer").trim().isEmpty())
            throw new IllegalArgumentException(T.s("请填写答案"));
        JSONArray masks = c.optJSONArray("masks");
        if (masks == null || masks.length() > 100) throw new IllegalArgumentException(T.s("遮挡区域无效"));
        String image = c.optString("image");
        if (!image.isEmpty() && !image.matches("[a-zA-Z0-9_-]+\\.jpg"))
            throw new IllegalArgumentException(T.s("图片文件名无效"));
        if (type.equals("image") && (image.isEmpty() || masks.length() == 0))
            throw new IllegalArgumentException(T.s("图片题需要图片和至少一块遮挡区域"));
        for (int i = 0; i < masks.length(); i++) {
            JSONArray r = masks.optJSONArray(i);
            if (r == null || r.length() != 4) throw new IllegalArgumentException(T.s("遮挡区域格式错误"));
            double x = r.optDouble(0, -1), y = r.optDouble(1, -1), w = r.optDouble(2, -1), h = r.optDouble(3, -1);
            if (!Double.isFinite(x + y + w + h) || x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > 1.001 || y + h > 1.001)
                throw new IllegalArgumentException(T.s("遮挡区域超出图片"));
        }
        if (c.optInt("state") < 0 || c.optInt("state") > 2 || c.optInt("reps") < 0
                || c.optLong("due") < 0 || c.optInt("step") < 0 || c.optInt("step") > 1)
            throw new IllegalArgumentException(T.s("复习状态无效"));
        if (c.optInt("reps") > 0 && (!Double.isFinite(c.optDouble("stability"))
                || c.optDouble("stability") <= 0 || !Double.isFinite(c.optDouble("difficulty"))
                || c.optDouble("difficulty") < 1 || c.optDouble("difficulty") > 10))
            throw new IllegalArgumentException(T.s("复习参数无效"));
    }
    private void validate() {
        if (db.optInt("version") != 1 || decks() == null || cards() == null
                || db.optJSONArray("logs") == null || db.optJSONObject("plans") == null)
            throw new IllegalArgumentException(T.s("缺少必要字段或版本不支持"));
        if (decks().length() > 200 || cards().length() > 30000 || db.optJSONArray("logs").length() > 1000000)
            throw new IllegalArgumentException(T.s("数据量超出导入上限"));
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < decks().length(); i++) {
            JSONObject d = decks().optJSONObject(i);
            if (d == null || d.optString("id").isEmpty() || !ids.add(d.optString("id"))
                    || d.optString("name").trim().isEmpty() || d.optInt("newLimit", -1) < 0
                    || d.optInt("newLimit") > 200 || d.optInt("reviewLimit") < 1 || d.optInt("reviewLimit") > 1000
                    || d.optInt("target") < 1 || d.optInt("target") > 1000
                    || (d.has("requestRetention") && !(d.optDouble("requestRetention", -1) >= 0.70
                        && d.optDouble("requestRetention", -1) <= 0.97))
                    || (d.has("maxInterval") && (d.optInt("maxInterval", -1) < 1
                        || d.optInt("maxInterval", -1) > 36500)))
                throw new IllegalArgumentException(T.s("卡组设置无效"));
        }
        ids.clear();
        for (int i = 0; i < cards().length(); i++) {
            JSONObject c = cards().optJSONObject(i); validateCard(c);
            if (!ids.add(c.optString("id"))) throw new IllegalArgumentException(T.s("重复卡片标识"));
        }
        MemoryFlashcards.validate(this);
        // Validate persisted plans before any live data can be replaced.
        java.util.Iterator<String> dates = db.optJSONObject("plans").keys();
        while (dates.hasNext()) {
            JSONObject day = db.optJSONObject("plans").optJSONObject(dates.next());
            if (day == null) throw new IllegalArgumentException(T.s("每日计划格式无效"));
            java.util.Iterator<String> keys = day.keys();
            while (keys.hasNext()) {
                String key = keys.next(); JSONObject p = day.optJSONObject(key);
                if (deck(key) == null || p == null || p.optJSONArray("ids") == null
                        || p.optJSONArray("passed") == null || p.optInt("target", -1) < 0)
                    throw new IllegalArgumentException(T.s("每日计划字段无效"));
                Set<String> unique = new HashSet<>();
                JSONArray arr = p.optJSONArray("ids"), pass = p.optJSONArray("passed");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = card(arr.optString(i));
                    if (c == null || !key.equals(c.optString("deck")) || !unique.add(arr.optString(i)))
                        throw new IllegalArgumentException(T.s("每日计划卡片无效"));
                }
                unique.clear();
                for (int i = 0; i < pass.length(); i++) if (!contains(arr, pass.optString(i)) || !unique.add(pass.optString(i)))
                    throw new IllegalArgumentException(T.s("每日完成记录无效"));
                if (p.optInt("target") > arr.length()) throw new IllegalArgumentException(T.s("目标超过队列数量"));
            }
        }
    }
}
