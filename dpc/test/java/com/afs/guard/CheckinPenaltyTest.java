package com.afs.guard;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 打卡罚时的纯逻辑回归：判断点、漏打卡、跨天不延长。
 *
 * 和 PenaltyTimingTest 一样不连手机 —— 这几条要是有洞，罚时就会天天叠到明天，
 * 而界面上看不出来（只会觉得「怎么又不给开」）。
 */
public final class CheckinPenaltyTest {
    static int n;

    static long at(int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, 9, day, hour, minute);
        return c.getTimeInMillis();
    }

    static void eq(Object expected, Object actual, String why) {
        n++;
        if (!expected.equals(actual)) throw new AssertionError(why + ": " + expected + " != " + actual);
    }

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        point();
        miss();
        sameDay();
        due();
        System.out.println("Checkin penalty: " + n + " checks passed");
    }

    private static void point() {
        eq(false, CheckinPenalty.atDuePoint(19), "19 点还没到判断点");
        eq(true, CheckinPenalty.atDuePoint(20), "20 点整就是判断点");
        eq(true, CheckinPenalty.atDuePoint(23), "夜里更算");
        eq(30L * 60000L, CheckinPenalty.EXTEND_MS, "一次 30 分钟");
    }

    private static void miss() {
        long due = at(29, 20, 0);
        eq(true, CheckinPenalty.missed(1, 0, due), "还有目标没打卡");
        eq(false, CheckinPenalty.missed(0, at(29, 19, 59), due), "20:00 前打完");
        eq(false, CheckinPenalty.missed(0, at(29, 20, 0), due), "正好 20:00 完成算按时");
        eq(true, CheckinPenalty.missed(0, at(29, 21, 30), due), "20:00 之后才打完也算没赶上");
        eq(false, CheckinPenalty.missed(0, 0, due), "今天一笔都没打但也没有目标");
    }

    private static void sameDay() {
        // 冻结 00:00-22:00：今天 22:00 放行，可以延
        Rule day = new Rule(127, new int[][]{{0, 1320}}, true, true);
        eq(true, CheckinPenalty.withinToday(day, at(29, 22, 0), at(29, 21, 0)), "今天内的放行点");
        eq(false, CheckinPenalty.withinToday(day, at(30, 22, 0), at(29, 21, 0)), "明天才放行 → 不延");
        eq(false, CheckinPenalty.withinToday(day, 0, at(29, 21, 0)), "没有放行点 → 不延");
        // 冻结 23:00-07:00：跨午夜，按需求整个跳过
        Rule cross = new Rule(127, new int[][]{{1380, 420}}, true, true);
        eq(true, cross.crossMidnightAt(0), "这条规则确实跨午夜");
        eq(false, CheckinPenalty.withinToday(cross, at(30, 7, 0), at(29, 23, 30)), "跨午夜的不延");
        eq(false, CheckinPenalty.withinToday(null, at(29, 22, 0), at(29, 21, 0)), "没有规则 → 不延");
    }

    private static void due() {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(at(29, 21, 37));
        eq(at(29, 20, 0), CheckinPenalty.dueAt(now), "20:00 取整");
        eq(21, now.get(Calendar.HOUR_OF_DAY), "dueAt 不改调用方的 Calendar");
    }
}
