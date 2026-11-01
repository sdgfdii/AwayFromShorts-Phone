package com.afs.guard;

import java.util.List;

/**
 * 全局收束时段：左闭右开、跨午夜、全天、切段账本四件事都要钉死。
 *
 * 限额的计时和断网的翻转都读这一个判断，这里错一分钟，两边一起错。
 */
public final class TimeWindowTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(year, month, day, hour, minute, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static final int DAY = 24 * 60 * 60 * 1000;

    public static void main(String[] args) {
        // 默认 21:00–22:00
        eq(21 * 60, TimeWindow.DEFAULT_START, "default start");
        eq(22 * 60, TimeWindow.DEFAULT_END, "default end");
        eq("21:00", TimeWindow.clock(21 * 60), "clock start");
        eq("23:59", TimeWindow.clock(23 * 60 + 59), "clock end of day");
        eq("00:00", TimeWindow.clock(0), "clock midnight");

        // 左闭右开：21:00 生效，21:59 还在，22:00 已放开
        long day = at(2026, 10, 31, 0, 0);
        ok(TimeWindow.contains(day + 21 * 3600_000L, 1260, 1320), "21:00 in");
        ok(TimeWindow.contains(day + (21 * 60 + 59) * 60_000L, 1260, 1320), "21:59 in");
        ok(!TimeWindow.contains(day + 22 * 3600_000L, 1260, 1320), "22:00 out");
        ok(!TimeWindow.contains(day + 20 * 3600_000L, 1260, 1320), "20:00 out");

        // start == end 表示全天，不是空
        ok(TimeWindow.contains(day + 3 * 3600_000L, 600, 600), "start==end means all day");
        ok(TimeWindow.contains(day + 23 * 3600_000L, 600, 600), "start==end at midnight too");

        // 跨午夜 22:00–01:00：23 点在，00:30 在，01:00 出，21 点不在
        ok(TimeWindow.contains(day + 23 * 3600_000L, 1320, 60), "23:00 in overnight");
        ok(TimeWindow.contains(day + DAY + 30 * 60_000L, 1320, 60), "next 00:30 in overnight");
        ok(!TimeWindow.contains(day + DAY + 60 * 60_000L, 1320, 60), "01:00 out overnight");
        ok(!TimeWindow.contains(day + 21 * 3600_000L, 1320, 60), "21:00 out overnight");

        // 切段：全天窗口 = 一整段
        long from = day;
        long to = day + DAY;
        List<long[]> all = TimeWindow.segments(from, to, 600, 600);
        eq(1, all.size(), "all-day one segment");
        eq(from, all.get(0)[0], "all-day from");
        eq(to, all.get(0)[1], "all-day to");

        // 一天一段：窗口 21:00–22:00，正好切出 [21:00, 22:00)
        List<long[]> one = TimeWindow.segments(from, to, 1260, 1320);
        eq(1, one.size(), "one segment per day");
        eq(day + 21 * 3600_000L, one.get(0)[0], "segment opens 21:00");
        eq(day + 22 * 3600_000L, one.get(0)[1], "segment closes 22:00");

        // 跨午夜 22:00–01:00 扫两天：前一天的窗口尾巴落在今天 00:00–01:00，
        // 今晚的开头是 22:00–24:00，共两段 —— 尾巴在前，因为扫描从昨天起
        List<long[]> split = TimeWindow.segments(from, to, 1320, 60);
        eq(2, split.size(), "overnight splits into two");
        eq(day, split.get(0)[0], "yesterday's tail opens at midnight");
        eq(day + 60 * 60_000L, split.get(0)[1], "yesterday's tail closes 01:00");
        eq(day + 22 * 3600_000L, split.get(1)[0], "tonight opens 22:00");
        eq(day + DAY, split.get(1)[1], "tonight closes at midnight");

        // 空交集：from/to 完全在窗口外
        List<long[]> empty = TimeWindow.segments(day + 6 * 3600_000L, day + 8 * 3600_000L, 1260, 1320);
        eq(0, empty.size(), "no overlap no segments");

        // 乱序区间直接返回空
        eq(0, TimeWindow.segments(to, from, 1260, 1320).size(), "reversed range empty");

        System.out.println("TimeWindow: " + checks + " checks");
    }
}
