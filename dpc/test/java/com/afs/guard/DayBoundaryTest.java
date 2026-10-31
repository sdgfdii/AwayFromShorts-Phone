package com.afs.guard;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** 额度日界：默认零点与旧行为逐毫秒一致，改成别的整点只移动边界，不丢账本。 */
public final class DayBoundaryTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(year, month, day, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String day(long at) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(at));
    }

    public static void main(String[] args) {
        // 默认零点：与旧的 Calendar.set(HOUR_OF_DAY,0) 完全一致
        long morning = at(2026, 9, 31, 9, 30);
        eq(at(2026, 9, 31, 0, 0), DayBoundary.startMs(morning, 0), "midnight boundary");
        eq("2026-10-31", DayBoundary.key(morning, 0), "midnight key");

        // 4 点日界：凌晨 2 点还属于「昨天」那一档
        long earlyTwo = at(2026, 9, 31, 2, 0);
        eq(at(2026, 9, 30, 4, 0), DayBoundary.startMs(earlyTwo, 4), "4am boundary before reset");
        eq("2026-10-30", DayBoundary.key(earlyTwo, 4), "4am key stays on previous day");

        // 5 点已过重置
        long five = at(2026, 9, 31, 5, 0);
        eq(at(2026, 9, 31, 4, 0), DayBoundary.startMs(five, 4), "4am boundary after reset");
        eq("2026-10-31", DayBoundary.key(five, 4), "4am key rolls over");

        // 正好在重置那一分钟：算新的一天
        long exactly = at(2026, 9, 31, 4, 0);
        eq(at(2026, 9, 31, 4, 0), DayBoundary.startMs(exactly, 4), "exact reset instant belongs to new day");
        eq("2026-10-31", DayBoundary.key(exactly, 4), "exact reset key");

        // 23 点日界跨月
        long firstHour = at(2026, 9, 1, 0, 30);
        eq(at(2026, 8, 30, 23, 0), DayBoundary.startMs(firstHour, 23), "23h boundary crosses month");

        // nextReset 永远是下一个整点
        eq(at(2026, 9, 31, 4, 0), DayBoundary.nextResetMs(earlyTwo, 4), "next reset after boundary");
        eq(at(2026, 10, 1, 0, 0), DayBoundary.nextResetMs(morning, 0), "next reset at midnight");

        // 非法小时按 0 兜底，不抛异常
        eq(at(2026, 9, 31, 0, 0), DayBoundary.startMs(morning, -3), "negative hour falls back");
        eq(at(2026, 9, 31, 0, 0), DayBoundary.startMs(morning, 99), "too large hour falls back");

        // 文案
        eq("每日零点", DayBoundary.label(0), "midnight label");
        eq("每日 4:00", DayBoundary.label(4), "four label");
        eq("每日零点", DayBoundary.label(42), "invalid label falls back");

        // 参数顺序一致性：startMs(now,hour) 与 key(now,hour) 用同一个边界
        eq(day(DayBoundary.startMs(earlyTwo, 4)), DayBoundary.key(earlyTwo, 4), "key derives from same boundary");

        System.out.println("Day boundary: " + checks + " checks passed");
    }
}
