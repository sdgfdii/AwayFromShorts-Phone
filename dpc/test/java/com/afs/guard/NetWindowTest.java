package com.afs.guard;

/**
 * 断网调整时段：左闭右开、跨午夜、全天、以及「方向」这一件事。
 *
 * 方向是这里最容易写错的地方：以前是「在时段内才断」，现在是「除了调整时段都断」。
 * 这一条如果反了，用户会在白天完全没事、恰好晚上十点到十一点被断网 —— 正好反着来。
 * 所以 {@link NetWindow#shouldCut} 必须单独钉死，UI 和 service 都只能走它。
 */
public final class NetWindowTest {
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
        // 默认每晚 22:00–23:00
        eq(22 * 60, NetWindow.DEFAULT_START, "default start");
        eq(23 * 60, NetWindow.DEFAULT_END, "default end");

        int open = NetWindow.DEFAULT_START;
        int close = NetWindow.DEFAULT_END;
        long day = at(2026, 10, 31, 0, 0);

        // 左闭右开：[22:00, 23:00) —— 22:00 进、22:59 还在、23:00 已经出
        ok(NetWindow.contains(day + 22 * 3600_000L, open, close), "22:00 inside");
        ok(NetWindow.contains(day + (22 * 60 + 59) * 60_000L, open, close), "22:59 inside");
        ok(!NetWindow.contains(day + 23 * 3600_000L, open, close), "23:00 outside");
        ok(!NetWindow.contains(day + (21 * 60 + 59) * 60_000L, open, close), "21:59 outside");
        ok(!NetWindow.contains(day + 12 * 3600_000L, open, close), "noon outside");

        // start == end 当成全天（唯一表达「整天都放开」的写法，不是空）
        ok(NetWindow.contains(day + 3 * 3600_000L, 600, 600), "start==end all day");
        ok(NetWindow.contains(day + 23 * 3600_000L, 600, 600), "start==end at 23:00 too");

        // 跨午夜 22:00–01:00：23 点在、次日 00:30 在、01:00 出、白天不在
        ok(NetWindow.contains(day + 23 * 3600_000L, 1320, 60), "23:00 in overnight");
        ok(NetWindow.contains(day + DAY + 30 * 60_000L, 1320, 60), "next 00:30 in overnight");
        ok(!NetWindow.contains(day + DAY + 60 * 60_000L, 1320, 60), "01:00 out overnight");
        ok(!NetWindow.contains(day + 12 * 3600_000L, 1320, 60), "noon out overnight");

        // 和收束时段共用同一套 contains，不许各写一份
        for (long stamp : new long[]{day + 12 * 3600_000L, day + 22 * 3600_000L,
                day + (22 * 60 + 30) * 60_000L, day + 23 * 3600_000L}) {
            eq(TimeWindow.contains(stamp, open, close), NetWindow.contains(stamp, open, close),
                    "delegates to TimeWindow.contains " + stamp);
        }

        // ==== 方向：除了调整时段都断 ====
        ok(NetWindow.shouldCut(true, false), "enabled outside window -> cut");
        ok(!NetWindow.shouldCut(true, true), "enabled inside window -> released");
        ok(!NetWindow.shouldCut(false, false), "disabled outside window -> nothing");
        ok(!NetWindow.shouldCut(false, true), "disabled inside window -> nothing");

        // 一天 24 小时按半小时扫一遍：只有 [22:00, 23:00) 这两格是放开的
        int released = 0;
        for (int minute = 0; minute < 24 * 60; minute += 30) {
            boolean inside = NetWindow.contains(day + minute * 60_000L, open, close);
            if (!NetWindow.shouldCut(true, inside)) released++;
        }
        eq(2, released, "exactly two half-hour slots released per day");

        System.out.println("NetWindow: " + checks + " checks");
    }
}
