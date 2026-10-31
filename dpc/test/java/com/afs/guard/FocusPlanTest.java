package com.afs.guard;

/** 番茄钟/倒计时的纯计算：剩余时间不出现负数，阶段只走一步，日期差按日历日。 */
public final class FocusPlanTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    private static void near(double expected, double actual, String label) {
        checks++;
        if (Math.abs(expected - actual) > 0.0001) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    public static void main(String[] args) {
        // 时钟格式
        eq("00:00", FocusPlan.clock(0), "zero");
        eq("00:59", FocusPlan.clock(59), "seconds");
        eq("01:00", FocusPlan.clock(60), "one minute");
        eq("25:00", FocusPlan.clock(1500), "twenty five minutes");
        eq("1:00:00", FocusPlan.clock(3600), "one hour");
        eq("00:00", FocusPlan.clock(-40), "negative clamps");

        // 秒数向上取整：还剩 0.4 秒仍显示 1 秒
        eq(0L, FocusPlan.ceilSeconds(0), "zero millis");
        eq(1L, FocusPlan.ceilSeconds(1), "one millis");
        eq(1L, FocusPlan.ceilSeconds(400), "partial second rounds up");
        eq(1L, FocusPlan.ceilSeconds(1000), "exact second stays one");
        eq(0L, FocusPlan.ceilSeconds(-5), "negative millis");

        // 剩余时间夹在 [0, total]
        eq(5000L, FocusPlan.remaining(105000, 100000, 300000), "normal remaining");
        eq(0L, FocusPlan.remaining(90000, 100000, 300000), "overdue clamps to zero");
        eq(300000L, FocusPlan.remaining(999999, 0, 300000), "future clamps to total");

        // 阶段：只走一步，不连跳
        eq(FocusPlan.BREAK, FocusPlan.nextPhase(FocusPlan.FOCUS), "focus to break");
        eq(FocusPlan.FOCUS, FocusPlan.nextPhase(FocusPlan.BREAK), "break to focus");
        eq("专注", FocusPlan.phaseName(FocusPlan.FOCUS), "focus name");
        eq("休息", FocusPlan.phaseName(FocusPlan.BREAK), "break name");

        // 阶段时长，越界退回默认
        eq(25 * 60000L, FocusPlan.phaseMillis(FocusPlan.FOCUS, 25, 5), "focus millis");
        eq(5 * 60000L, FocusPlan.phaseMillis(FocusPlan.BREAK, 25, 5), "break millis");
        eq(25 * 60000L, FocusPlan.phaseMillis(FocusPlan.FOCUS, 0, 5), "zero focus falls back");
        eq(5 * 60000L, FocusPlan.phaseMillis(FocusPlan.BREAK, 25, 999), "huge break falls back");

        // 拆分
        long[] parts = FocusPlan.split((((2 * 24L + 3) * 60 + 4) * 60 + 5) * 1000L);
        eq(2L, parts[0], "days");
        eq(3L, parts[1], "hours");
        eq(4L, parts[2], "minutes");
        eq(5L, parts[3], "seconds");
        long[] zero = FocusPlan.split(-1);
        eq(0L, zero[0], "negative split zero");

        // 口语
        eq("已到期", FocusPlan.human(0), "expired");
        eq("已到期", FocusPlan.human(-10), "negative expired");
        eq("3 秒", FocusPlan.human(3000), "seconds only");
        eq("12 分 5 秒", FocusPlan.human(725000), "minutes and seconds");
        eq("3 小时 12 分", FocusPlan.human(3 * 3600000L + 12 * 60000L), "hours and minutes");
        eq("1 天 3 小时", FocusPlan.human(27 * 3600000L), "days and hours");

        // 日历日差（输入是当天 00:00 的两个毫秒值）
        long today = 0L, tomorrow = 86400000L;
        eq(1, FocusPlan.dayGap(today, tomorrow), "tomorrow");
        eq(0, FocusPlan.dayGap(today, today), "today");
        eq(-3, FocusPlan.dayGap(today, -3 * 86400000L), "past");
        eq("还有 37 天", FocusPlan.dayLabel(37), "future label");
        eq("就是今天", FocusPlan.dayLabel(0), "today label");
        eq("已经过去 3 天", FocusPlan.dayLabel(-3), "past label");

        // 进度夹紧
        near(0.5, FocusPlan.progress(150, 300), "half progress");
        near(0.0, FocusPlan.progress(-5, 300), "negative progress");
        near(1.0, FocusPlan.progress(900, 300), "over progress");
        near(0.0, FocusPlan.progress(10, 0), "zero total");

        System.out.println("Focus plan: " + checks + " checks passed");
    }
}
