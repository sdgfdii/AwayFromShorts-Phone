package com.afs.guard;

/**
 * 亮屏累计锁机的计时（纯函数，不碰 Android）。
 *
 * 口径：**只累计屏幕亮着的时间**。灭屏、锁着的那几分钟都不算，
 * 所以"累计用眼 45 分钟"不会因为午睡两小时就自动重置，也不会一觉醒来直接触发。
 *
 * 为什么单次增量要有上限：进程被杀或长时间没 tick 时，两次采样之间可能跨了很久，
 * 而中间到底亮屏多久已经无从知道。与其猜（会把灭屏时间也算进去），不如截断 ——
 * 少算一点没人受伤，多算会让人莫名其妙被锁。
 */
public final class ScreenLock {

    /** 单次最多认 90 秒亮屏：tick 间隔是几秒，正常永远碰不到这个上限。 */
    public static final long MAX_GAP = 90000L;

    private ScreenLock() {
    }

    /**
     * 新的累计亮屏毫秒数。
     *
     * @param prevMs      上一轮的累计值（今天或本轮）
     * @param lastTickMs  上次结算时刻，0 表示第一次
     * @param nowMs       现在
     * @param counting    这次间隔是否算数（屏幕亮着、在生效时段内、也没在锁着）
     */
    public static long accumulate(long prevMs, long lastTickMs, long nowMs, boolean counting) {
        long base = prevMs < 0 ? 0 : prevMs;
        if (!counting || lastTickMs <= 0) return base;
        long delta = nowMs - lastTickMs;
        if (delta <= 0) return base;                 // 时钟被往回拨过：不加也不减
        if (delta > MAX_GAP) delta = MAX_GAP;        // 跨了长时间没采样：只认一小段
        return base + delta;
    }

    /** 累计够久了没有。period 分钟，<=0 表示不启用。 */
    public static boolean reached(long accumMs, int periodMinutes) {
        return periodMinutes > 0 && accumMs >= periodMinutes * LockPlan.MINUTE;
    }

    /** 距离该锁还剩多少亮屏毫秒（已经够或没启用返回 0）。 */
    public static long remaining(long accumMs, int periodMinutes) {
        if (periodMinutes <= 0) return 0;
        long need = periodMinutes * LockPlan.MINUTE - accumMs;
        return need <= 0 ? 0 : need;
    }

    /** 进度 0–1，给界面画条进度用。 */
    public static float progress(long accumMs, int periodMinutes) {
        if (periodMinutes <= 0) return 0f;
        float value = (float) accumMs / (periodMinutes * LockPlan.MINUTE);
        if (value < 0f) return 0f;
        return value > 1f ? 1f : value;
    }

    /** 「还剩 12 分钟」这种文案；已经够了就说可以锁了。 */
    public static String describe(long accumMs, int periodMinutes) {
        if (periodMinutes <= 0) return "";
        long left = remaining(accumMs, periodMinutes);
        if (left <= 0) return T.s("已累计够一轮");
        long minutes = (left + LockPlan.MINUTE - 1) / LockPlan.MINUTE;
        return T.f("还剩 %d 分钟", (int) minutes);
    }
}
