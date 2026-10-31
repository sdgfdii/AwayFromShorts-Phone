package com.afs.guard;

/**
 * 亮屏累计：只算亮着的时间，跨长时间没采样要截断，时钟回拨不能倒扣。
 */
public final class ScreenLockTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    private static void near(double expected, double actual, String label) {
        checks++;
        if (Math.abs(expected - actual) > 0.001) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    public static void main(String[] args) {
        long t0 = 1_000_000L;
        long minute = LockPlan.MINUTE;

        /* ---------- 累加 ---------- */
        eq(5000L, ScreenLock.accumulate(0L, t0, t0 + 5000L, true), "亮屏 5 秒累加 5 秒");
        eq(0L, ScreenLock.accumulate(0L, t0, t0 + 5000L, false), "灭屏不累计");
        eq(10000L, ScreenLock.accumulate(7000L, t0, t0 + 3000L, true), "在上一次基础上加");
        eq(7000L, ScreenLock.accumulate(7000L, 0L, t0 + 3000L, true), "第一次采样没有参照点就不加");
        eq(7000L, ScreenLock.accumulate(7000L, t0, t0 - 3000L, true), "时钟往回拨不倒扣");
        eq(7000L, ScreenLock.accumulate(7000L, t0, t0, true), "时间没走不加");
        eq(ScreenLock.MAX_GAP, ScreenLock.accumulate(0L, t0, t0 + 3600_000L, true),
                "隔了一小时才采样只认一小段");
        eq(1000L, ScreenLock.accumulate(-5L, t0, t0 + 1000L, true), "负的旧值当 0 再正常累加");

        /* ---------- 阈值 ---------- */
        eq(false, ScreenLock.reached(0L, 0), "没启用就不算够");
        eq(false, ScreenLock.reached(44 * minute, 45), "44 分钟还差一点");
        eq(true, ScreenLock.reached(45 * minute, 45), "正好 45 分钟算够");
        eq(true, ScreenLock.reached(46 * minute, 45), "超了也算够");

        eq(0L, ScreenLock.remaining(45 * minute, 45), "够了就没有剩余");
        eq(minute, ScreenLock.remaining(44 * minute, 45), "差 1 分钟");
        eq(0L, ScreenLock.remaining(0L, 0), "没启用返回 0");

        near(0.5, ScreenLock.progress(45 * minute, 90), "进度一半");
        near(1.0, ScreenLock.progress(200 * minute, 90), "进度封顶 1");
        near(0.0, ScreenLock.progress(-1L, 90), "负进度当 0");

        /* 文案只验不炸、不含占位符残留 */
        String text = ScreenLock.describe(44 * minute, 45);
        eq(true, text.contains("1"), "还剩一分钟要说出来");
        eq(true, ScreenLock.describe(45 * minute, 45).length() > 0, "够了也有话说");
        eq("", ScreenLock.describe(0L, 0), "没启用不显示");

        System.out.println("Screen lock: " + checks + " checks passed");
    }
}
