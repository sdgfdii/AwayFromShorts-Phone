package com.afs.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 月历/统计/成就的纯计算：日期换算、按日聚合、连续天数、满勤。 */
public final class CalendarDataTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static CalendarData.Span span(String date, String goal, double amount) {
        return new CalendarData.Span(date, goal, amount);
    }

    private static List<CalendarData.Span> list(CalendarData.Span... spans) {
        List<CalendarData.Span> out = new ArrayList<>();
        for (CalendarData.Span span : spans) out.add(span);
        return out;
    }

    public static void main(String[] args) {
        // 1970-01-01 是日序号的零点
        eq(0L, CalendarData.dayNumber("1970-01-01"), "epoch day number");
        eq(1L, CalendarData.dayNumber("1970-01-02"), "next day");
        eq(-1L, CalendarData.dayNumber("1969-12-31"), "previous day");
        eq(Long.MIN_VALUE, CalendarData.dayNumber("2026-13-01"), "invalid month");
        eq(Long.MIN_VALUE, CalendarData.dayNumber("2026-1-1"), "short key rejected");
        eq(Long.MIN_VALUE, CalendarData.dayNumber(""), "empty key rejected");
        eq(Long.MIN_VALUE, CalendarData.dayNumber(null), "null key rejected");
        eq(CalendarData.dayNumber("2026-03-01") - 1, CalendarData.dayNumber("2026-02-28"),
                "leap-month adjacency");

        // 2026-10-01 是周四 → 周一起排的索引 3
        eq(3, CalendarData.weekdayIndex(2026, 10, 1), "2026-10-01 is thursday");
        eq(0, CalendarData.weekdayIndex(2026, 10, 5), "monday index");
        eq(6, CalendarData.weekdayIndex(2026, 11, 1), "2026-11-01 is sunday");
        eq(31, CalendarData.daysInMonth(2026, 10), "october length");
        eq(29, CalendarData.daysInMonth(2028, 2), "leap february");
        eq(28, CalendarData.daysInMonth(2026, 2), "plain february");

        eq("2026-10-05", CalendarData.key(2026, 10, 5), "key padding");
        eq("2026-01-01", CalendarData.key(2026, 1, 1), "key padding single digit");
        eq(5, CalendarData.dayOfMonth("2026-10-05"), "day of month");
        eq(0, CalendarData.dayOfMonth("bad"), "day of month fallback");

        // 格子：前面按周一补齐，末尾补齐到整周
        List<CalendarData.Day> empty = CalendarData.grid(2026, 10, null, "2026-10-31");
        eq(3, leadingBlanks(empty), "leading blank count");
        eq(0, empty.size() % 7, "grid is whole weeks");
        eq(31, countDays(empty), "all days present");
        ok(empty.get(3).day == 1, "first day sits after 3 blanks");
        ok(empty.get(3 + 30).today, "last day marked today");
        eq(0, CalendarData.grid(2026, 13, null, "").size(), "invalid month gives no cells");

        // 聚合：同一天同一目标只算一个"已打卡目标"，金额相加
        Map<String, CalendarData.Day> byDate = CalendarData.days(list(
                span("2026-10-01", "a", 10),
                span("2026-10-01", "a", 5),
                span("2026-10-01", "b", 3),
                span("2026-10-02", "a", 1),
                span("2026-10-02", "", 9),
                span("2026-10-02", "c", 0)
        ), 2);
        eq(2, byDate.size(), "two days aggregated");
        eq(2, byDate.get("2026-10-01").hits, "distinct goals");
        eq(18.0, byDate.get("2026-10-01").amount, "amount summed");
        ok(byDate.get("2026-10-01").full, "10-01 is a full day");
        ok(!byDate.get("2026-10-02").full, "10-02 is not full");
        eq(1, byDate.get("2026-10-02").hits, "empty goal dropped");

        // 没有目标（goalCount=0）时不算满勤，避免除零式的假达标
        ok(!CalendarData.days(list(span("2026-10-03", "a", 1)), 0).get("2026-10-03").full,
                "no goals means no full day");

        // 统计与连续
        Map<String, CalendarData.Day> run = CalendarData.days(list(
                span("2026-10-27", "a", 1),
                span("2026-10-28", "a", 1),
                span("2026-10-29", "a", 1),
                span("2026-10-31", "a", 1),
                span("2026-10-20", "a", 1)
        ), 1);
        CalendarData.Stats stats = CalendarData.stats(run, "2026-10-31");
        eq(5, stats.activeDays, "active days");
        eq(5, stats.totalHits, "total hits");
        eq(5.0, stats.amount, "total amount");
        eq(3, stats.bestStreak, "best streak stops at the gap");
        eq(1, stats.currentStreak, "today counted");
        eq("2026-10-20", stats.firstKey, "first key");

        // 今天还没打卡：连续从昨天往回数，不该断；31 号那笔是未来，不算进来
        eq(3, CalendarData.stats(run, "2026-10-30").currentStreak, "yesterday-anchored streak");
        // 昨天也没打：连续归零
        eq(0, CalendarData.stats(run, "2026-11-02").currentStreak, "gap breaks current streak");
        // 未来的记录不参与"当前连续"
        Map<String, CalendarData.Day> future = CalendarData.days(list(
                span("2026-11-05", "a", 1),
                span("2026-11-06", "a", 1)
        ), 1);
        eq(0, CalendarData.stats(future, "2026-10-31").currentStreak, "future records ignored");
        eq(2, CalendarData.stats(future, "2026-10-31").bestStreak, "future still counts as best");

        // 月汇总只算这个月
        CalendarData.Stats monthStats = new CalendarData.Stats();
        CalendarData.addMonth(monthStats, CalendarData.grid(2026, 10, run, "2026-10-31"));
        eq(5, monthStats.monthActiveDays, "october active days");
        eq(5, monthStats.monthHits, "october hits");
        CalendarData.addMonth(monthStats, CalendarData.grid(2026, 11, run, "2026-10-31"));
        eq(5, monthStats.monthActiveDays, "november adds nothing");

        // 成就：全从统计派生，进度不超过目标
        CalendarData.Stats zero = new CalendarData.Stats();
        List<Achievement.Item> none = Achievement.all(zero);
        eq(10, none.size(), "achievement count");
        eq(0, Achievement.unlocked(none), "nothing unlocked at zero");
        eq("0/1", none.get(0).ratio(), "first badge ratio");

        List<Achievement.Item> some = Achievement.all(stats);
        eq(2, Achievement.unlocked(some), "first check-in + 3 day streak");
        eq(100, some.get(1).percent(), "streak badge full");
        eq(43, some.get(2).percent(), "7-day badge at 3/7");
        ok(some.get(9).percent() == 1, "500 records badge nearly empty");

        System.out.println("Calendar data: " + checks + " checks passed");
    }

    private static int leadingBlanks(List<CalendarData.Day> cells) {
        int count = 0;
        for (CalendarData.Day cell : cells) {
            if (!cell.blank()) break;
            count++;
        }
        return count;
    }

    private static int countDays(List<CalendarData.Day> cells) {
        int count = 0;
        for (CalendarData.Day cell : cells) if (!cell.blank()) count++;
        return count;
    }
}
