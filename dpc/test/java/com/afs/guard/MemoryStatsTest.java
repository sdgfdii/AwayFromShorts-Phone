package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.TimeZone;

/** 统计口径：每日复习量、到期预测、留存曲线、记忆强度。全部从现有日志/卡片状态现算。 */
public final class MemoryStatsTest {
    private static int count;
    private static final long NOW = 1792893600000L;   // 2026-10-25 10:00 Asia/Shanghai
    private static void check(boolean value, String message) { count++; if (!value) throw new AssertionError(message); }
    private static void eq(int expected, int actual, String message) {
        count++; if (expected != actual) throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
    }
    private static String deck(MemoryData m, int i) { return m.activeDecks().optJSONObject(i).optString("id"); }
    private static String add(MemoryData m, String d) {
        JSONObject c = MemoryData.newCard(d, "qa", "front", "answer", "", ""); m.saveCard(c); return c.optString("id");
    }
    private static void log(MemoryData m, String deck, String card, long ts, int rating) {
        JSONObject log = new JSONObject();
        MemoryData.put(log, "card", card); MemoryData.put(log, "deck", deck);
        MemoryData.put(log, "ts", ts); MemoryData.put(log, "rating", rating);
        m.db.optJSONArray("logs").put(log);
    }
    private static void state(MemoryData m, String id, int reps, int state, double stability, long due) {
        JSONObject c = m.card(id);
        MemoryData.put(c, "reps", reps); MemoryData.put(c, "state", state);
        MemoryData.put(c, "stability", stability); MemoryData.put(c, "due", due);
    }
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        daily(); forecast(); retention(); strength(); summary();
        System.out.println("Memory stats: " + count + " assertions passed");
    }
    private static void daily() {
        MemoryData m = new MemoryData(); String d = deck(m, 0);
        String a = add(m, d), b = add(m, d);
        int days = 7;
        int[] reviewed = new int[days], correct = new int[days];
        String[] labels = new String[days];
        log(m, d, a, NOW - 90000000L, 3);    // 昨天
        log(m, d, b, NOW - 90000000L, 1);    // 昨天，忘了
        log(m, d, a, NOW - 1000L, 4);        // 今天
        MemoryStats.daily(m, NOW, days, reviewed, correct, labels);
        eq(2, reviewed[days - 2], "yesterday counted");
        eq(1, correct[days - 2], "yesterday remembered counts only rating>=3");
        eq(1, reviewed[days - 1], "today counted");
        eq(1, correct[days - 1], "today remembered");
        eq(0, reviewed[0], "old empty day stays zero");
        check(labels[days - 1] != null && labels[days - 1].contains("/"), "label looks like a date");
        // 学科进回收站后从曲线里消失
        m.deleteDeck(d, NOW);
        MemoryStats.daily(m, NOW, days, reviewed, correct, labels);
        eq(0, reviewed[days - 1], "trashed subject leaves the chart");
    }
    private static void forecast() {
        MemoryData m = new MemoryData(); String d = deck(m, 0);
        String overdue = add(m, d), today = add(m, d), tomorrow = add(m, d), later = add(m, d), fresh = add(m, d);
        state(m, overdue, 2, MemoryScheduler.REVIEW, 5, NOW - 2 * 3600000L);
        state(m, today, 2, MemoryScheduler.REVIEW, 5, NOW + 3600000L);
        state(m, tomorrow, 2, MemoryScheduler.REVIEW, 5, NOW + MemoryScheduler.DAY + 3600000L);
        state(m, later, 2, MemoryScheduler.REVIEW, 5, NOW + 9 * MemoryScheduler.DAY);
        int[] f = MemoryStats.forecast(m, NOW, 7);
        eq(2, f[0], "overdue and due-later-today share today's slot");
        eq(1, f[1], "tomorrow counted");
        eq(0, f[5], "beyond the window ignored");
        check(fresh != null, "new card kept");
        eq(0, MemoryStats.forecast(m, NOW, 7)[6], "last slot empty");
        List<String> rows = MemoryStats.dueByDeck(m, NOW);
        check(rows.size() == 1 && rows.get(0).endsWith("\t2"), "per-subject due rows count only due cards");
    }
    private static void retention() {
        MemoryData m = new MemoryData(); String d = deck(m, 0);
        String a = add(m, d), b = add(m, d);
        // a：隔 3 天再复习，答对 → 落在 "2–4 天" 档且算记住
        log(m, d, a, NOW - 10 * MemoryScheduler.DAY, 3);
        log(m, d, a, NOW - 7 * MemoryScheduler.DAY, 3);
        // b：隔 6 小时再复习，忘了 → 落在 "<1 天" 档且算忘记
        log(m, d, b, NOW - 1000L, 1);
        log(m, d, b, NOW - 1000L + 6 * 3600000L, 1);
        int[] samples = new int[7], hits = new int[7];
        String[] labels = new String[7];
        MemoryStats.retention(m, samples, hits, labels);
        eq(1, samples[0], "short gap bucket sampled");
        eq(0, hits[0], "forgotten short gap not counted as hit");
        eq(1, samples[2], "3-day gap lands in the 2-4 bucket");
        eq(1, hits[2], "remembered after 3 days counts as hit");
        eq(0, samples[6], "no long-gap samples");
        check(labels.length == 7 && labels[6].contains("30"), "bucket labels");
    }
    private static void strength() {
        MemoryData m = new MemoryData(); String d = deck(m, 0);
        String n = add(m, d), learn = add(m, d), shortl = add(m, d), longl = add(m, d);
        state(m, learn, 1, MemoryScheduler.LEARNING, 1, NOW);
        state(m, shortl, 2, MemoryScheduler.REVIEW, 3, NOW + 3600000L);
        state(m, longl, 2, MemoryScheduler.REVIEW, 30, NOW + 3600000L);
        int[] s = MemoryStats.strength(m);
        eq(1, s[MemoryStats.BUCKET_NEW], "new card bucket");
        eq(1, s[MemoryStats.BUCKET_LEARNING], "learning bucket");
        eq(1, s[MemoryStats.BUCKET_SHORT], "short stability bucket");
        eq(1, s[MemoryStats.BUCKET_LONG], "long stability bucket");
        check(n != null, "new card counted");
    }
    private static void summary() {
        MemoryData m = new MemoryData(); String d = deck(m, 0);
        String a = add(m, d), b = add(m, d);
        state(m, a, 2, MemoryScheduler.REVIEW, 5, NOW - 1000L);
        state(m, b, 2, MemoryScheduler.REVIEW, 5, NOW + 5 * MemoryScheduler.DAY);
        log(m, d, a, NOW - 1000L, 4);
        log(m, d, b, NOW - 1000L, 1);
        log(m, d, a, NOW - MemoryScheduler.DAY, 3);
        log(m, d, b, NOW - 2 * MemoryScheduler.DAY, 1);
        MemoryStats.Summary s = MemoryStats.summary(m, NOW);
        String untouched = add(m, d);          // 从未学过：due=0，不能算今天到期
        s = MemoryStats.summary(m, NOW);
        eq(3, s.total, "total cards");
        eq(2, s.learned, "never-studied card is not learned");
        eq(1, s.dueToday, "due today ignores never-studied cards");
        eq(4, s.reviews7, "reviews in the last 7 days");
        eq(2, s.correct7, "remembered in the last 7 days");
        check(Math.abs(s.accuracy7() - 0.5) < 1e-9, "accuracy is remembered/reviews");
        eq(3, s.streak, "streak counts today and the two days before");
        // 今天还没复习时，连续天数从昨天算起
        MemoryData quiet = new MemoryData(); JSONArray cards = quiet.cards();
        check(cards.length() == 0, "fresh db has no cards");
        log(quiet, deck(quiet, 0), add(quiet, deck(quiet, 0)), NOW - MemoryScheduler.DAY, 3);
        eq(1, MemoryStats.summary(quiet, NOW).streak, "streak falls back to yesterday");
    }
}
