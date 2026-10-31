package com.afs.guard;

/**
 * 定时锁机的时间逻辑（纯函数，不碰 Android）。
 *
 * 两种锁用同一套时间轴：
 *   周期锁 —— 从每个时段窗口的起点开始，每 period 分钟触发一次，锁 lock 分钟。
 *             例如 08:00–22:00、每 45 分钟锁 5 分钟 → 08:00、08:45、09:30 … 各锁 5 分钟。
 *   久用锁 —— 同一个应用连续在前台超过 limit 分钟就锁。判据在调用方（读使用记录）。
 *
 * 边界一律用「本地零点 + 分钟偏移」算，不构造 Calendar，避免夏令时来回跳。
 */
public final class LockPlan {

    public static final long MINUTE = 60000L;
    private static final long DAY = 86400000L;

    private LockPlan() {
    }

    /** from == to 表示全天。窗口可以跨天（22:00 → 06:00）。 */
    public static boolean inWindow(int minuteOfDay, int from, int to) {
        if (from == to) return true;
        if (from < to) return minuteOfDay >= from && minuteOfDay < to;
        return minuteOfDay >= from || minuteOfDay < to;
    }

    /** 窗口总长（分钟）。跨天按展开算，全天 1440。 */
    public static int windowSpan(int from, int to) {
        if (from == to) return 1440;
        return from < to ? to - from : 1440 - from + to;
    }

    /** 本地第几分钟（0–1439）。dayStartMs 必须是 now 所在那天的本地零点。 */
    public static int minuteOfDay(long nowMs, long dayStartMs) {
        int m = (int) ((nowMs - dayStartMs) / MINUTE);
        m %= 1440;
        return m < 0 ? m + 1440 : m;
    }

    /**
     * 现在所处的周期起点（毫秒）；不在窗口里返回 -1。
     *
     * 跨天窗口的凌晨段属于「昨天开的那一段」，所以起点要往前挪一天。
     */
    public static long boundary(long nowMs, long dayStartMs, int from, int to, int periodMinutes) {
        if (periodMinutes <= 0) return -1;
        long base = dayStartMs + from * MINUTE;
        if (from != to && from > to && minuteOfDay(nowMs, dayStartMs) < from) {
            base -= DAY;
        }
        long since = nowMs - base;
        if (since < 0) return -1;                                  // 今天还没开窗
        if (since >= windowSpan(from, to) * MINUTE) return -1;      // 窗口已经过去
        long period = periodMinutes * MINUTE;
        return base + (since / period) * period;
    }

    /** 此刻是否处在一次周期锁里。 */
    public static boolean locked(long nowMs, long dayStartMs, int from, int to,
                                 int periodMinutes, int lockMinutes) {
        if (lockMinutes <= 0) return false;
        long start = boundary(nowMs, dayStartMs, from, to, periodMinutes);
        return start >= 0 && nowMs - start < lockMinutes * MINUTE;
    }

    /**
     * 下一次状态翻转的时刻。
     * 锁着 → 解锁那一刻；没锁 → 下一个周期点，或窗口结束，或明天开窗。
     */
    public static long nextChange(long nowMs, long dayStartMs, int from, int to,
                                  int periodMinutes, int lockMinutes) {
        long base = dayStartMs + from * MINUTE;
        if (from != to && from > to && minuteOfDay(nowMs, dayStartMs) < from) {
            base -= DAY;
        }
        long since = nowMs - base;
        long span = windowSpan(from, to) * MINUTE;
        if (since < 0) return base;                 // 还没开窗
        if (since >= span) return base + DAY;       // 今天这段过了
        long start = boundary(nowMs, dayStartMs, from, to, periodMinutes);
        if (start < 0) return base + span;
        long release = start + Math.max(1, lockMinutes) * MINUTE;
        if (nowMs < release) return release;
        long next = start + Math.max(1, periodMinutes) * MINUTE;
        long windowEnd = base + span;
        return Math.min(next, windowEnd);
    }

    /** 连续使用是否已经超限。 */
    public static boolean appOverLimit(long continuousMs, int limitMinutes) {
        return limitMinutes > 0 && continuousMs >= limitMinutes * MINUTE;
    }

    /** 还有多久解锁（毫秒，已过或没锁返回 0）。 */
    public static long remaining(long nowMs, long dayStartMs, int from, int to,
                                 int periodMinutes, int lockMinutes) {
        if (!locked(nowMs, dayStartMs, from, to, periodMinutes, lockMinutes)) return 0;
        long start = boundary(nowMs, dayStartMs, from, to, periodMinutes);
        return Math.max(0, start + lockMinutes * MINUTE - nowMs);
    }

    /** 「45 分钟」这种时长文案（拆成数字与单位，英文好换序）。 */
    public static String minutes(int value) {
        return T.f("%d 分钟", value);
    }
}
