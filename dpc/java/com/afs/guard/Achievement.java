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
        final String glyph;
        final String name;
        final String note;
        final int progress;
        final int target;

        Item(String glyph, String name, String note, int progress, int target) {
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

    static List<Item> all(CalendarData.Stats stats) {
        List<Item> out = new ArrayList<>();
        if (stats == null) {
            stats = new CalendarData.Stats();
        }
        out.add(new Item("◦", T.s("开张"), T.s("第一次打卡"), stats.activeDays, 1));
        out.add(new Item("▲", T.s("连三"), T.s("连续打卡 3 天"), stats.bestStreak, 3));
        out.add(new Item("▲", T.s("一周不断"), T.s("连续打卡 7 天"), stats.bestStreak, 7));
        out.add(new Item("◆", T.s("半月不息"), T.s("连续打卡 15 天"), stats.bestStreak, 15));
        out.add(new Item("◆", T.s("习惯成形"), T.s("连续打卡 30 天"), stats.bestStreak, 30));
        out.add(new Item("●", T.s("满勤 ×10"), T.s("单日完成全部目标，累计 10 天"), stats.fullDays, 10));
        out.add(new Item("●", T.s("满勤 ×50"), T.s("单日完成全部目标，累计 50 天"), stats.fullDays, 50));
        out.add(new Item("■", T.s("打卡 30 天"), T.s("累计打卡天数"), stats.activeDays, 30));
        out.add(new Item("■", T.s("百日记"), T.s("累计打卡 100 天"), stats.activeDays, 100));
        out.add(new Item("★", T.s("五百笔"), T.s("累计写下 500 条记录"), stats.totalHits, 500));
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
