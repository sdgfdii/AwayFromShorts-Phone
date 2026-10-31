package com.afs.guard;

/**
 * 定时锁机的时间逻辑。
 *
 * 时间轴用「本地零点 + 分钟」构造，dayStartMs 固定为 0，所以每个断言都能手算。
 * 重点钉四件事：窗口边界（含跨天）、周期起点、锁的起止、下一次翻转。
 */
public final class LockPlanTest {

    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    /** 第 h 小时 m 分（dayStart = 0）。 */
    private static long at(int hour, int minute) {
        return (hour * 60L + minute) * 60000L;
    }

    public static void main(String[] args) {
        final int from = 8 * 60;
        final int to = 22 * 60;
        final int period = 45;
        final int lock = 5;

        /* ---------- 窗口 ---------- */
        ok(LockPlan.inWindow(from, from, to), "window start inclusive");
        ok(LockPlan.inWindow(from - 1, from, to) == false, "before window");
        ok(LockPlan.inWindow(to - 1, from, to), "one minute before end");
        ok(LockPlan.inWindow(to, from, to) == false, "end exclusive");
        ok(LockPlan.inWindow(0, from, from), "from==to means all day");
        ok(LockPlan.inWindow(0, 22 * 60, 6 * 60), "wrap: late night inside");
        ok(LockPlan.inWindow(3 * 60, 22 * 60, 6 * 60), "wrap: early morning inside");
        ok(LockPlan.inWindow(6 * 60, 22 * 60, 6 * 60) == false, "wrap: end exclusive");
        ok(LockPlan.inWindow(12 * 60, 22 * 60, 6 * 60) == false, "wrap: noon outside");

        eq(840, LockPlan.windowSpan(from, to), "span normal");
        eq(480, LockPlan.windowSpan(22 * 60, 6 * 60), "span wrap");
        eq(1440, LockPlan.windowSpan(600, 600), "span all day");

        /* ---------- 周期起点 ---------- */
        eq(at(8, 0), LockPlan.boundary(at(8, 0), 0L, from, to, period), "boundary at start");
        eq(at(8, 0), LockPlan.boundary(at(8, 44), 0L, from, to, period), "still first slot");
        eq(at(8, 45), LockPlan.boundary(at(8, 45), 0L, from, to, period), "second slot");
        eq(at(9, 30), LockPlan.boundary(at(9, 31), 0L, from, to, period), "third slot");
        eq(at(21, 30), LockPlan.boundary(at(21, 50), 0L, from, to, period), "late slot");
        eq(-1L, LockPlan.boundary(at(7, 59), 0L, from, to, period), "before window");
        eq(-1L, LockPlan.boundary(at(22, 0), 0L, from, to, period), "window over");
        eq(-1L, LockPlan.boundary(at(23, 0), 0L, from, to, period), "after window");
        eq(-1L, LockPlan.boundary(at(9, 0), 0L, from, to, 0), "zero period is invalid");

        // 跨天窗口的凌晨段属于昨天那一段：起点可能落到前一天
        eq(-120L * 60000L + 270L * 60000L,
                LockPlan.boundary(at(3, 0), 0L, 22 * 60, 6 * 60, 45), "wrap boundary crosses midnight");

        /* ---------- 锁没锁 ---------- */
        ok(LockPlan.locked(at(8, 0), 0L, from, to, period, lock), "locked at boundary");
        ok(LockPlan.locked(at(8, 4), 0L, from, to, period, lock), "still locked 4 min in");
        ok(LockPlan.locked(at(8, 5), 0L, from, to, period, lock) == false, "released at 5 min");
        ok(LockPlan.locked(at(8, 6), 0L, from, to, period, lock) == false, "not locked mid-slot");
        ok(LockPlan.locked(at(8, 45), 0L, from, to, period, lock), "locked again next slot");
        ok(LockPlan.locked(at(21, 30), 0L, from, to, period, lock), "locked in last slot");
        ok(LockPlan.locked(at(9, 0), 0L, from, to, period, 0) == false, "zero lock length");
        // 跨天：02:32 落在 02:30 那一格里
        ok(LockPlan.locked(at(2, 32), 0L, 22 * 60, 6 * 60, 45, 5), "wrap locked after midnight");
        ok(LockPlan.locked(at(3, 0), 0L, 22 * 60, 6 * 60, 45, 5) == false, "wrap released");

        /* ---------- 剩余 ---------- */
        eq(3L * 60000L, LockPlan.remaining(at(8, 2), 0L, from, to, period, lock), "3 minutes left");
        eq(0L, LockPlan.remaining(at(8, 30), 0L, from, to, period, lock), "not locked no remaining");

        /* ---------- 下一次翻转 ---------- */
        eq(at(8, 5), LockPlan.nextChange(at(8, 2), 0L, from, to, period, lock), "release moment");
        eq(at(8, 45), LockPlan.nextChange(at(8, 10), 0L, from, to, period, lock), "next boundary");
        eq(at(8, 0), LockPlan.nextChange(at(7, 0), 0L, from, to, period, lock), "today opening");
        eq(at(8, 0) + 24L * 60L * 60000L, LockPlan.nextChange(at(23, 0), 0L, from, to, period, lock),
                "tomorrow opening");
        // 最后一格：锁着的时候翻转点是解锁时刻，解锁之后就只剩窗口末尾
        ok(LockPlan.nextChange(at(21, 46), 0L, from, to, period, lock) <= at(22, 0),
                "next change stays inside window");
        eq(at(21, 30) + 5L * 60000L, LockPlan.nextChange(at(21, 32), 0L, from, to, period, lock),
                "release of last slot");
        eq(at(22, 0), LockPlan.nextChange(at(21, 46), 0L, from, to, period, lock),
                "after last release only window end");

        /* ---------- 久用 ---------- */
        ok(LockPlan.appOverLimit(30L * 60000L, 30), "exactly at limit");
        ok(LockPlan.appOverLimit(29L * 60000L, 30) == false, "one minute short");
        ok(LockPlan.appOverLimit(60L * 60000L, 0) == false, "zero limit never fires");

        /* ---------- 文案 ---------- */
        eq("45 分钟", LockPlan.minutes(45), "minutes label");

        System.out.println("Lock plan: " + checks + " checks passed");
    }
}
