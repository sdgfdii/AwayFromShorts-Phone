package com.afs.guard;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 月历、连续天数、成就的纯计算部分。
 *
 * 刻意不碰 Android（不读盘、不读系统时钟、不碰 Context）：
 * 日期一律从外面传进来，这样离线回归能直接喂假数据，
 * 不用在测试里伪造 SharedPreferences。
 */
final class CalendarData {

    private CalendarData() {
    }

    /** 一笔打卡的只读视图；由 {@link StudyStore.Rec} 转过来。 */
    static final class Span {
        final String date;
        final String goalId;
        final double amount;

        Span(String date, String goalId, double amount) {
            this.date = date == null ? "" : date;
            this.goalId = goalId == null ? "" : goalId;
            this.amount = amount;
        }
    }

    /** 一格。day == 0 表示月初月末的补位空格。 */
    static final class Day {
        public int day;
        public String key = "";
        public int hits;
        public double amount;
        public boolean full;
        public boolean today;

        public boolean blank() {
            return day == 0;
        }
    }

    static final class Stats {
        public int activeDays;
        public int fullDays;
        public int totalHits;
        public double amount;
        public int currentStreak;
        public int bestStreak;
        public int monthActiveDays;
        public int monthFullDays;
        public int monthHits;
        public double monthAmount;
        public String firstKey = "";
    }

    /* ==================== 日期 ==================== */

    static String key(int year, int month, int day) {
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day);
    }

    static int daysInMonth(int year, int month) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, 1);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    /** 0 = 周一 … 6 = 周日（界面按周一起排）。 */
    static int weekdayIndex(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day);
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    /**
     * 公历日序号（1970-01-01 为 0）。用 Howard Hinnant 的算法而不是 Calendar，
     * 免得夏令时/时区把「连续天数」算断。非法日期返 {@link Long#MIN_VALUE}。
     */
    static long dayNumber(int year, int month, int day) {
        int y = month <= 2 ? year - 1 : year;
        int era = (y >= 0 ? y : y - 399) / 400;
        int yoe = y - era * 400;
        int doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return (long) era * 146097L + doe - 719468L;
    }

    static long dayNumber(String key) {
        if (key == null || key.length() != 10 || key.charAt(4) != '-' || key.charAt(7) != '-') {
            return Long.MIN_VALUE;
        }
        try {
            int y = Integer.parseInt(key.substring(0, 4));
            int m = Integer.parseInt(key.substring(5, 7));
            int d = Integer.parseInt(key.substring(8, 10));
            if (m < 1 || m > 12 || d < 1 || d > 31) {
                return Long.MIN_VALUE;
            }
            return dayNumber(y, m, d);
        } catch (NumberFormatException error) {
            return Long.MIN_VALUE;
        }
    }

    /** 从 yyyy-MM-dd 取「日」；格式不对返 0。 */
    static int dayOfMonth(String key) {
        if (key == null || key.length() != 10) {
            return 0;
        }
        try {
            return Integer.parseInt(key.substring(8, 10));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    /* ==================== 聚合 ==================== */

    /** 按日期聚合成「这天打了几个目标」。goalCount 决定哪天算满勤（<=0 时不算满勤）。 */
    static Map<String, Day> days(List<Span> spans, int goalCount) {
        Map<String, Day> out = new HashMap<>();
        if (spans == null) {
            return out;
        }
        Map<String, Set<String>> seen = new HashMap<>();
        for (Span s : spans) {
            if (s == null || s.date.isEmpty() || s.goalId.isEmpty() || s.amount <= 0) {
                continue;
            }
            Day day = out.get(s.date);
            if (day == null) {
                day = new Day();
                day.key = s.date;
                day.day = dayOfMonth(s.date);
                out.put(s.date, day);
            }
            day.amount += s.amount;
            Set<String> goals = seen.get(s.date);
            if (goals == null) {
                goals = new HashSet<>();
                seen.put(s.date, goals);
            }
            if (goals.add(s.goalId)) {
                day.hits++;
            }
            day.full = goalCount > 0 && day.hits >= goalCount;
        }
        return out;
    }

    /** 一个月的格子：前面补到周一，后面补到整周。 */
    static List<Day> grid(int year, int month, Map<String, Day> byDate, String todayKey) {
        List<Day> out = new ArrayList<>();
        if (month < 1 || month > 12) {
            return out;
        }
        int lead = weekdayIndex(year, month, 1);
        for (int i = 0; i < lead; i++) {
            out.add(new Day());
        }
        int total = daysInMonth(year, month);
        for (int d = 1; d <= total; d++) {
            String key = key(year, month, d);
            Day cell = byDate == null ? null : byDate.get(key);
            Day day = new Day();
            day.day = d;
            day.key = key;
            day.today = key.equals(todayKey);
            if (cell != null) {
                day.hits = cell.hits;
                day.amount = cell.amount;
                day.full = cell.full;
            }
            out.add(day);
        }
        while (out.size() % 7 != 0) {
            out.add(new Day());
        }
        return out;
    }

    static Stats stats(Map<String, Day> byDate, String todayKey) {
        Stats stats = new Stats();
        if (byDate == null) {
            return stats;
        }
        Set<Long> numbers = new HashSet<>();
        for (Day day : byDate.values()) {
            if (day.hits <= 0) {
                continue;
            }
            stats.activeDays++;
            stats.totalHits += day.hits;
            stats.amount += day.amount;
            if (day.full) {
                stats.fullDays++;
            }
            long number = dayNumber(day.key);
            if (number != Long.MIN_VALUE) {
                numbers.add(number);
                if (stats.firstKey.isEmpty() || day.key.compareTo(stats.firstKey) < 0) {
                    stats.firstKey = day.key;
                }
            }
        }
        List<Long> sorted = new ArrayList<>(numbers);
        Collections.sort(sorted);
        int run = 0;
        long prev = Long.MIN_VALUE;
        for (long number : sorted) {
            run = (prev != Long.MIN_VALUE && number == prev + 1) ? run + 1 : 1;
            if (run > stats.bestStreak) {
                stats.bestStreak = run;
            }
            prev = number;
        }
        // 当前连续：晚于今天的一律不算（补记未来日期不该把连续刷上去）
        long today = dayNumber(todayKey);
        if (today != Long.MIN_VALUE) {
            long cursor = numbers.contains(today) ? today : today - 1;
            while (numbers.contains(cursor)) {
                stats.currentStreak++;
                cursor--;
            }
        }
        return stats;
    }

    /** 把一个月的格子并进统计的「本月」字段。 */
    static void addMonth(Stats stats, List<Day> cells) {
        if (stats == null || cells == null) {
            return;
        }
        for (Day cell : cells) {
            if (cell.blank() || cell.hits <= 0) {
                continue;
            }
            stats.monthActiveDays++;
            stats.monthHits += cell.hits;
            stats.monthAmount += cell.amount;
            if (cell.full) {
                stats.monthFullDays++;
            }
        }
    }
}
