package com.afs.guard;

import java.util.Locale;

/**
 * 番茄钟与未来倒计时的纯计算。不碰 Android，离线测试直接覆盖。
 *
 * 规则很简单，但边界必须明确：
 *   - 剩余时间一律夹在 [0, 总时长]，负数按 0 算，不出现 "-00:03"；
 *   - 阶段只有两个：专注、休息。专注结束进休息，休息结束回专注，不自动连跳；
 *   - 跨天倒计时按自然日算，不按 24 小时算（"还有 1 天"指明天这个日期）。
 */
public final class FocusPlan {

    public static final int FOCUS = 0;
    public static final int BREAK = 1;

    /** 专注时长可选（分钟）。25 是默认，50 适合整块写作。 */
    public static final int[] FOCUS_MINUTES = {15, 25, 45, 50};
    /** 休息时长可选（分钟）。 */
    public static final int[] BREAK_MINUTES = {3, 5, 10};

    public static final int DEFAULT_FOCUS = 25;
    public static final int DEFAULT_BREAK = 5;

    private FocusPlan() {
    }

    public static String phaseName(int phase) {
        return phase == BREAK ? T.s("休息") : T.s("专注");
    }

    public static int nextPhase(int phase) {
        return phase == BREAK ? FOCUS : BREAK;
    }

    /** 该阶段应持续多久（毫秒）。参数越界时退回默认值，不让状态机炸掉。 */
    public static long phaseMillis(int phase, int focusMinutes, int breakMinutes) {
        int minutes = phase == BREAK ? breakMinutes : focusMinutes;
        if (minutes < 1 || minutes > 180) minutes = phase == BREAK ? DEFAULT_BREAK : DEFAULT_FOCUS;
        return minutes * 60000L;
    }

    /** 剩余毫秒，夹在 [0, total]。 */
    public static long remaining(long endAt, long now, long total) {
        long left = endAt - now;
        if (left < 0) left = 0;
        if (left > total) left = total;
        return left;
    }

    /** 剩余秒数向上取整：还剩 0.4 秒时显示 00:01 而不是 00:00。 */
    public static long ceilSeconds(long millis) {
        if (millis <= 0) return 0;
        return (millis + 999L) / 1000L;
    }

    public static String clock(long seconds) {
        long total = Math.max(0L, seconds);
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long rest = total % 60L;
        if (hours > 0) return hours + ":" + two(minutes) + ":" + two(rest);
        return two(minutes) + ":" + two(rest);
    }

    /** 剩余毫秒拆成 {天, 时, 分, 秒}，已过期全为 0。 */
    public static long[] split(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        return new long[]{total / 86400L, (total % 86400L) / 3600L, (total % 3600L) / 60L, total % 60L};
    }

    /**
     * 口语化剩余时间。天数是按 24 小时折算的粗粒度读数，
     * 只有 {@link #dayGap(long, long)} 才回答"隔了几个日历日"。
     */
    public static String human(long millis) {
        if (millis <= 0) return T.s("已到期");
        long[] parts = split(millis);
        if (parts[0] > 0) return parts[0] + T.s(" 天 ") + parts[1] + T.s(" 小时");
        if (parts[1] > 0) return parts[1] + T.s(" 小时 ") + parts[2] + T.s(" 分");
        if (parts[2] > 0) return parts[2] + T.s(" 分 ") + parts[3] + T.s(" 秒");
        return parts[3] + T.s(" 秒");
    }

    /**
     * 两个时刻相隔几个日历日（按本地日期算，不看钟点）。
     * 今天 → 0，明天 → 1，昨天 → -1。夏令时和跨月由调用方给的日期决定。
     */
    public static int dayGap(long fromDateStart, long targetDateStart) {
        long diff = targetDateStart - fromDateStart;
        return (int) Math.round(diff / 86400000.0);
    }

    /** "还有 37 天" / "就是今天" / "已经过去 3 天"。 */
    public static String dayLabel(int gap) {
        if (gap > 0) return T.s("还有 ") + gap + T.s(" 天");
        if (gap == 0) return T.s("就是今天");
        return T.s("已经过去 ") + (-gap) + T.s(" 天");
    }

    /** 进度 0..1：过了多少，用于画环。总长为 0 时返回 0。 */
    public static float progress(long elapsed, long total) {
        if (total <= 0) return 0f;
        float value = (float) elapsed / (float) total;
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }

    private static String two(long value) {
        return String.format(Locale.US, "%02d", value);
    }
}
