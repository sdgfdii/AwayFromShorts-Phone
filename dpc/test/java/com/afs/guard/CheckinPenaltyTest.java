package com.afs.guard;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 打卡罚时的纯逻辑回归：判断点、漏打卡、跨午夜不延长。
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
        Rule day = new Rule(127, new int[][]{{0, 1320}}, true, true);
        eq(true, CheckinPenalty.withinToday(day, at(29, 22, 0), at(29, 20, 0)), "当天冻结结束点可以延长");
        eq(false, CheckinPenalty.withinToday(day, at(30, 22, 0), at(29, 20, 0)), "第二天才结束的冻结不延长");
        Rule cross = new Rule(127, new int[][]{{1380, 420}}, true, true);
        eq(true, cross.crossMidnightAt(0), "这条规则确实跨午夜");
        eq(false, CheckinPenalty.withinToday(cross, at(30, 7, 0), at(29, 20, 0)), "跨午夜结束不延长");
        eq(false, CheckinPenalty.withinToday(null, at(29, 22, 0), at(29, 20, 0)), "没有规则不延长");
    }

    /**
     * 打卡罚时对所有被冻结的应用生效：它必须挡在「钥匙应用 / 非联动应用放行」之前，
     * 否则罚时条目记了也白记（1.88.0 之前的真实表现）。
     */
    private static void override() {
        Calendar after = Calendar.getInstance();
        after.setTimeInMillis(at(29, 21, 10));                 // 21:00 放行之后，罚时窗口 21:00-21:30 内
        // freeze=true 的窗口是「冻结段」：00:00-21:00 冻结 → 21:10 处在放行段
        Rule openNow = new Rule(Rule.ALL_DAYS, new int[][]{{0, 21 * 60}}, true, true);
        eq(3, Guard.blockReason(openNow, true, false, false, true, after, false), "打卡罚时越过钥匙应用豁免");
        eq(3, Guard.blockReason(openNow, false, false, false, true, after, false), "打卡罚时对非联动应用同样生效");
        eq(3, Guard.blockReason(openNow, true, false, false, true, after, true), "热爱奖励不抵扣打卡罚时");
        eq(0, Guard.blockReason(openNow, true, false, false, false, after, false), "没有罚时时钥匙应用照旧放行");
        eq(0, Guard.blockReason(openNow, false, false, false, false, after, false), "没有罚时时普通应用在放行段可自由使用");
        // 仍在冻结段里：照旧报时段冻结，不会因为罚时换口径
        Rule frozenNow = new Rule(Rule.ALL_DAYS, new int[][]{{21 * 60, 23 * 60}}, true, true);
        eq(1, Guard.blockReason(frozenNow, false, false, false, true, after, false), "冻结段内仍优先报时段冻结");
        eq(3, Guard.blockReason(openNow, false, false, true, true, after, false), "打卡与禁忌叠加时仍报延长冻结");
    }

    private static void due() {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(at(29, 21, 37));
        eq(at(29, 20, 0), CheckinPenalty.dueAt(now), "20:00 取整");
        eq(21, now.get(Calendar.HOUR_OF_DAY), "dueAt 不改调用方的 Calendar");
        override();
    }
}
