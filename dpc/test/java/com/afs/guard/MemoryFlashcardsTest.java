package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

/**
 * 抽取规则已从「随机 / 顺序 / 全部池 / 仅到期池」换成遗忘曲线排序：
 * 到期旧卡按可提取率升序，新卡按入库顺序补位并受新学上限约束。
 */
public final class MemoryFlashcardsTest {
    private static int count;
    private static final long NOW = 1792893600000L;
    private static void check(boolean value, String message) { count++; if (!value) throw new AssertionError(message); }
    private static void reject(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException e) { count++; return; }
        throw new AssertionError(message);
    }
    private static String deck(MemoryData m, int i) { return m.activeDecks().optJSONObject(i).optString("id"); }
    private static JSONArray selected(String... ids) { JSONArray a = new JSONArray(); for (String id : ids) a.put(id); return a; }
    private static String add(MemoryData m, String d, String word) {
        JSONObject c = MemoryData.newCard(d, "qa", word, "answer", "", ""); m.saveCard(c); return c.optString("id");
    }
    private static void rate(MemoryData m, int rating, long now) {
        m.rateFlashcard(MemoryFlashcards.next(m, now).optString("id"), rating, now, MemoryData.date(now));
    }
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        forgettingOrder(); newLimitGate(); migration(); validation();
        System.out.println("Memory flashcards: " + count + " assertions passed");
    }
    private static void due(MemoryData m, String id, long when, double stability, long last, int state) {
        JSONObject c = m.card(id);
        MemoryData.put(c, "reps", 1); MemoryData.put(c, "state", state);
        MemoryData.put(c, "stability", stability); MemoryData.put(c, "difficulty", 5);
        MemoryData.put(c, "last", last); MemoryData.put(c, "due", when);
    }
    /** 忘得最厉害的先来：可提取率低的排在前面，学习步直接排最前。 */
    private static void forgettingOrder() {
        MemoryData m = new MemoryData(); String d = deck(m, 0);
        String strong = add(m, d, "strong"), weak = add(m, d, "weak"), learning = add(m, d, "learning");
        String future = add(m, d, "future");
        due(m, strong, NOW - 3600000L, 10, NOW - 5 * MemoryScheduler.DAY, MemoryScheduler.REVIEW);
        due(m, weak, NOW - 3600000L, 2, NOW - 5 * MemoryScheduler.DAY, MemoryScheduler.REVIEW);
        due(m, learning, NOW - 60000L, 1, NOW - 600000L, MemoryScheduler.LEARNING);
        due(m, future, NOW + 3 * MemoryScheduler.DAY, 10, NOW - MemoryScheduler.DAY, MemoryScheduler.REVIEW);
        MemoryFlashcards.configure(m, selected(d), 20, 0, NOW);
        check(MemoryFlashcards.newLimit(m) == 0, "new limit stored");
        JSONArray ids = MemoryFlashcards.session(m, NOW).optJSONArray("ids");
        check(ids.length() == 3, "future card and new cards excluded when new limit is 0");
        check(learning.equals(ids.optString(0)), "learning step comes first");
        check(weak.equals(ids.optString(1)), "lower stability is more forgotten and comes first");
        check(strong.equals(ids.optString(2)), "higher retrievability comes last");
        check(MemoryScheduler.retrievability(2, NOW - 5 * MemoryScheduler.DAY, NOW)
                < MemoryScheduler.retrievability(10, NOW - 5 * MemoryScheduler.DAY, NOW), "retrievability drops with stability");
        rate(m, 3, NOW);
        check(weak.equals(MemoryFlashcards.next(m, NOW).optString("id")), "graded card leaves the queue");
        rate(m, 1, NOW); rate(m, 3, NOW);
        check(MemoryFlashcards.next(m, NOW) == null, "queue finishes");
        check(MemoryFlashcards.progress(m, NOW).done == 3, "forgotten card still counts as reviewed");
        check(m.card(weak).optInt("lapses") == 1, "forgotten card enters wrong book");
    }
    /** 新卡按入库顺序补位，且不超过每天新学上限；上限 0 表示只复习。 */
    private static void newLimitGate() {
        MemoryData m = new MemoryData(); String d = deck(m, 0);
        String[] order = new String[30];
        for (int i = 0; i < order.length; i++) order[i] = add(m, d, "word " + i);
        MemoryFlashcards.configure(m, selected(d), 10, 3, NOW);
        JSONArray ids = MemoryFlashcards.session(m, NOW).optJSONArray("ids");
        check(ids.length() == 3, "new limit caps the queue");
        check(order[0].equals(ids.optString(0)) && order[1].equals(ids.optString(1)) && order[2].equals(ids.optString(2)),
                "new cards follow import order");
        MemoryFlashcards.configure(m, selected(d), 10, 1000, NOW);
        check(MemoryFlashcards.session(m, NOW).optJSONArray("ids").length() == 10, "daily cap still wins over new limit");
        check(MemoryFlashcards.progress(m, NOW).requested == 10, "progress reports the daily cap");
        MemoryFlashcards.configure(m, selected(d), 4, 2, NOW);
        check(MemoryFlashcards.session(m, NOW).optJSONArray("ids").length() == 2, "new limit caps below the daily cap");
        // 记过一张之后，队列重排时它不会再出现
        rate(m, 3, NOW);
        MemoryData copy = new MemoryData(m.db.toString());
        check(copy.cards().length() == 30, "backup roundtrip keeps cards");
        check(MemoryFlashcards.next(copy, NOW).optString("id").equals(order[1]), "completed new card not repeated");
    }
    /** 老数据里的随机/顺序/池子不再影响抽取，写回时被清掉。 */
    private static void migration() {
        MemoryData m = new MemoryData(); String d = deck(m, 0);
        String first = add(m, d, "one"), second = add(m, d, "two");
        JSONObject legacy = MemoryData.copy(m.db);
        JSONObject root = new JSONObject();
        JSONObject settings = new JSONObject();
        MemoryData.put(settings, "count", 5); MemoryData.put(settings, "mode", "sequence");
        MemoryData.put(settings, "pool", "due"); MemoryData.put(settings, "subjects", selected(d));
        MemoryData.put(root, "settings", settings); MemoryData.put(legacy, "flashcards", root);
        MemoryData old = new MemoryData(legacy.toString());
        check(MemoryFlashcards.count(old) == 5, "legacy count survives");
        check(MemoryFlashcards.newLimit(old) == MemoryFlashcards.DEFAULT_NEW_LIMIT, "missing new limit falls back to default");
        check(first.equals(MemoryFlashcards.next(old, NOW).optString("id")), "legacy mode/pool no longer decides the order");
        MemoryFlashcards.configure(old, selected(d), 6, 4, NOW);
        JSONObject saved = old.db.optJSONObject("flashcards").optJSONObject("settings");
        check(!saved.has("mode") && !saved.has("pool"), "saving drops the removed rules");
        check(saved.optInt("count") == 6 && saved.optInt("newLimit") == 4, "new settings persisted");
        check(second.equals(MemoryFlashcards.session(old, NOW).optJSONArray("ids").optString(1)), "import order kept after migration");
    }
    private static void validation() {
        MemoryData m = new MemoryData(); String en = deck(m, 0);
        reject(() -> MemoryFlashcards.configure(m, selected(), 20, 10, NOW), "subject required");
        reject(() -> MemoryFlashcards.configure(m, selected(en), 0, 10, NOW), "zero count");
        reject(() -> MemoryFlashcards.configure(m, selected(en), 1001, 10, NOW), "count bound");
        reject(() -> MemoryFlashcards.configure(m, selected(en), 20, -1, NOW), "negative new limit");
        reject(() -> MemoryFlashcards.configure(m, selected(en), 20, 1001, NOW), "new limit bound");
        reject(() -> MemoryFlashcards.configure(m, selected("missing"), 20, 10, NOW), "unknown subject");
        add(m, en, "a"); MemoryFlashcards.configure(m, selected(en), 1, 1, NOW);
        check(new MemoryData(m.db.toString()).cards().length() == 1, "roundtrip");
        JSONObject duplicate = MemoryData.copy(m.db);
        JSONArray ids = duplicate.optJSONObject("flashcards").optJSONObject("session").optJSONArray("ids");
        ids.put(ids.optString(0));
        reject(() -> new MemoryData(duplicate.toString()), "duplicate queue rejected");
        JSONObject bad = MemoryData.copy(m.db);
        MemoryData.put(bad.optJSONObject("flashcards").optJSONObject("settings"), "count", -1);
        reject(() -> new MemoryData(bad.toString()), "malformed settings rejected");
        JSONObject badLimit = MemoryData.copy(m.db);
        MemoryData.put(badLimit.optJSONObject("flashcards").optJSONObject("settings"), "newLimit", 5000);
        reject(() -> new MemoryData(badLimit.toString()), "out of range new limit rejected");
        check(new MemoryData().db.optJSONObject("flashcards") == null, "old data stays compatible");
    }
}
