package com.afs.guard;

import java.util.ArrayList;
import java.util.List;

/**
 * 成就：全部从打卡历史派生，不单独存一份"已经解锁"的标记。
 *
 * 这么做是有意的 —— 一旦把解锁状态落盘，删掉几笔记录就会出现
 * "成就还在但数据不支持"的矛盾；现算逻辑永远和历史一致。
 */
final class Achievement {

    private Achievement() {
    }

    static final class Item {
        /** 稳定身份：语言切换、改名都不会让「已提醒」失效。 */
        final String id;
        final String glyph;
        final String name;
        final String note;
        final int progress;
        final int target;

        Item(String id, String glyph, String name, String note, int progress, int target) {
            this.id = id;
            this.glyph = glyph;
            this.name = name;
            this.note = note;
            this.target = target < 1 ? 1 : target;
            this.progress = Math.max(0, Math.min(progress, this.target));
        }

        boolean unlocked() {
            return progress >= target;
        }

        /** 0–100，用来画进度条；已解锁返回 100。 */
        int percent() {
            return (int) Math.round(progress * 100.0 / target);
        }

        String ratio() {
            return progress + "/" + target;
        }
    }

    /** 打卡以外的应用数据。只放计数，避免成就层再理解各业务模型。 */
    static final class AppStats {
        int memoryCards, memoryReviews, quizAttempts, quizCorrect, notes, journals, loveCards, summaries;
    }

    static List<Item> all(CalendarData.Stats stats) {
        List<Item> out = new ArrayList<>();
        if (stats == null) {
            stats = new CalendarData.Stats();
        }
        out.add(new Item("checkin.first", "◦", T.s("开张"), T.s("第一次打卡"), stats.activeDays, 1));
        out.add(new Item("checkin.streak3", "▲", T.s("连三"), T.s("连续打卡 3 天"), stats.bestStreak, 3));
        out.add(new Item("checkin.streak7", "▲", T.s("一周不断"), T.s("连续打卡 7 天"), stats.bestStreak, 7));
        out.add(new Item("checkin.streak15", "◆", T.s("半月不息"), T.s("连续打卡 15 天"), stats.bestStreak, 15));
        out.add(new Item("checkin.streak30", "◆", T.s("习惯成形"), T.s("连续打卡 30 天"), stats.bestStreak, 30));
        out.add(new Item("checkin.full10", "●", T.s("满勤 ×10"), T.s("单日完成全部目标，累计 10 天"), stats.fullDays, 10));
        out.add(new Item("checkin.full50", "●", T.s("满勤 ×50"), T.s("单日完成全部目标，累计 50 天"), stats.fullDays, 50));
        out.add(new Item("checkin.days30", "■", T.s("打卡 30 天"), T.s("累计打卡天数"), stats.activeDays, 30));
        out.add(new Item("checkin.days100", "■", T.s("百日记"), T.s("累计打卡 100 天"), stats.activeDays, 100));
        out.add(new Item("checkin.records500", "★", T.s("五百笔"), T.s("累计写下 500 条记录"), stats.totalHits, 500));
        return out;
    }

    /** 覆盖记忆、刷题、笔记、日记、热爱与周期总结的全局成就。 */
    static List<Item> app(AppStats stats) {
        if (stats == null) stats = new AppStats();
        List<Item> out = new ArrayList<>();
        out.add(new Item("memory.cards50", "◇", T.s("记忆入门"), T.s("建立 50 张记忆卡"), stats.memoryCards, 50));
        out.add(new Item("memory.reviews300", "◆", T.s("复习成习"), T.s("累计完成 300 次复习"), stats.memoryReviews, 300));
        out.add(new Item("quiz.attempts200", "△", T.s("刷题 200"), T.s("累计完成 200 道题"), stats.quizAttempts, 200));
        out.add(new Item("quiz.correct150", "▲", T.s("答对 150"), T.s("累计答对 150 道题"), stats.quizCorrect, 150));
        out.add(new Item("memo.notes30", "▤", T.s("随手有记"), T.s("写下 30 篇备忘录"), stats.notes, 30));
        out.add(new Item("journal.entries30", "✎", T.s("日记 30 篇"), T.s("记下 30 天日记"), stats.journals, 30));
        out.add(new Item("love.cards10", "♡", T.s("热爱收藏"), T.s("获得 10 次热爱奖励"), stats.loveCards, 10));
        out.add(new Item("summary.completed12", "◎", T.s("周期复盘"), T.s("完成 12 次周期总结"), stats.summaries, 12));
        return out;
    }

    static int unlocked(List<Item> items) {
        int count = 0;
        for (Item item : items) {
            if (item.unlocked()) {
                count++;
            }
        }
        return count;
    }
}
