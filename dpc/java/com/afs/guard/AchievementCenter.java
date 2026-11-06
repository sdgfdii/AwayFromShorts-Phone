package com.afs.guard;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 成就的读取与「刚解锁」提醒。
 *
 * <p>解锁与否永远从打卡历史现算（{@link Achievement}），这里只多做一件事：
 * 记住上一次看到过哪些，好在**刚刚**解锁的那一刻弹一次窗 —— 天天弹「你已解锁连三」
 * 只会让人关掉提示。
 *
 * <p>第一次运行不弹：那时候连「上一版」都没有，把当前已解锁的全部当成基线存下来，
 * 否则一装上新版就蹦出七八个弹窗。
 */
final class AchievementCenter {

    private static final String SP = "afs_achievement";
    private static final String K_SEEN = "seen";

    private AchievementCenter() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 从打卡历史算一份统计；读不出来就当空的，不抛给界面。 */
    static CalendarData.Stats stats(Context c) {
        List<CalendarData.Span> spans = new ArrayList<CalendarData.Span>();
        int goals = 0;
        try {
            for (StudyStore.G goal : StudyStore.goals(c)) {
                goals++;
            }
            for (StudyStore.Rec rec : StudyStore.recs(c)) {
                spans.add(new CalendarData.Span(rec.date, rec.goalId, rec.amount));
            }
        } catch (RuntimeException ignored) {
            return new CalendarData.Stats();
        }
        return CalendarData.stats(CalendarData.days(spans, goals), StudyStore.today());
    }

    /** 打卡之外的整体数据：每个模块单独兜底，任何一个文件坏了都不能把成就页拖垮。 */
    private static Achievement.AppStats appStats(Context c) {
        Achievement.AppStats stats = new Achievement.AppStats();
        try {
            MemoryData memory = MemoryStore.snapshot(c);
            stats.memoryCards = memory.cards() == null ? 0 : memory.cards().length();
            org.json.JSONArray logs = memory.db.optJSONArray("logs");
            stats.memoryReviews = logs == null ? 0 : logs.length();
        } catch (RuntimeException ignored) { }
        try {
            int[] quiz = QuizStore.snapshot(c).stats("");
            stats.quizAttempts = quiz[0];
            stats.quizCorrect = quiz[1];
        } catch (RuntimeException ignored) { }
        try { stats.notes = MemoStore.count(c); } catch (RuntimeException ignored) { }
        try { stats.journals = JournalStore.total(c); } catch (RuntimeException ignored) { }
        try {
            CardData cards = CardStore.snapshot(c);
            stats.loveCards = cards.earned() == null ? 0 : cards.earned().length();
            org.json.JSONArray records = cards.summary(System.currentTimeMillis()).records();
            for (int i = 0; i < records.length(); i++) {
                if (records.optJSONObject(i) != null && records.optJSONObject(i).optLong("at") > 0) stats.summaries++;
            }
        } catch (RuntimeException ignored) { }
        return stats;
    }

    static List<Achievement.Item> items(Context c) {
        List<Achievement.Item> out = new ArrayList<Achievement.Item>(Achievement.all(stats(c)));
        out.addAll(Achievement.app(appStats(c)));
        return out;
    }

    static int unlocked(Context c) {
        return Achievement.unlocked(items(c));
    }

    /** 设置页那一行：`3 / 10`。 */
    static String summary(Context c) {
        List<Achievement.Item> all = items(c);
        return Achievement.unlocked(all) + " / " + all.size();
    }

    /**
     * 取「这次新解锁」的成就并落盘。
     *
     * @return 新解锁的条目；第一次运行（没有基线）返回空表
     */
    static List<Achievement.Item> takeNew(Context c) {
        List<Achievement.Item> all = items(c);
        SharedPreferences p = sp(c);
        Set<String> seen = p.getStringSet(K_SEEN, null);
        // 旧版把翻译后的名字当身份；一旦切语言就会重复提醒。首次升级把这批旧值当基线，
        // 不把「换了 ID 方案」误报成十几条新解锁。
        boolean legacy = seen != null;
        if (seen != null) for (String value : seen) if (value != null && value.indexOf('.') >= 0) legacy = false;
        Set<String> now = new HashSet<String>();
        List<Achievement.Item> fresh = new ArrayList<Achievement.Item>();
        for (Achievement.Item item : all) {
            if (!item.unlocked()) continue;
            now.add(item.id);
            if (!legacy && seen != null && !seen.contains(item.id)) fresh.add(item);
        }
        if (seen == null || legacy || !seen.equals(now)) {
            p.edit().putStringSet(K_SEEN, now).apply();
        }
        return fresh;
    }

    /** 有新解锁就弹一次；没有就什么都不做（不闪、不打扰）。 */
    static void announce(Activity host) {
        if (host == null || host.isFinishing()) return;
        final List<Achievement.Item> fresh = takeNew(host);
        if (fresh.isEmpty()) return;
        AchievementCelebration.show(host,fresh);
    }
}
