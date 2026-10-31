package com.afs.guard;

/**
 * 定时提醒：隔一段时间提醒一次「起来歇会儿」「喝口水」。
 *
 * 和定时锁机的区别：锁机是硬拦（LockTask 真锁屏），提醒只发一条通知，不拦人。
 * 两种可以同时开 —— 早上用提醒，晚上用锁机。
 *
 * 计时口径是「距上一次提醒满 N 分钟」，不是「从时段起点整点起算」。
 * 后者的问题是：刚过时段起点进入这一段时会立刻弹一条，人会以为出 bug 了。
 *
 * 纯函数，不碰 Android，离线可测。
 */
public final class RestReminder {

    public static final int DEFAULT_EVERY = 45;
    public static final int MAX_EVERY = 240;

    private RestReminder() {
    }

    /** 时段内且距上次提醒够久，就该提醒。 */
    public static boolean due(long nowMs, long lastFiredMs, int everyMinutes) {
        if (everyMinutes <= 0) return false;
        if (lastFiredMs <= 0L) return false;      // 还没起算，第一下不弹
        return nowMs - lastFiredMs >= everyMinutes * LockPlan.MINUTE;
    }

    /**
     * 此刻该不该真的发提醒。
     *
     * 不该发的四种情况，都是为了"别在不该打扰的时候打扰"：
     *   · 没开
     *   · 不在时段里（深夜、上班时间之外）
     *   · 灭屏（人不在看手机，弹了也是白弹，醒来还留着一条过期的）
     *   · 正被锁着 / 处于解锁码放行期（那会儿人在休息或有事，不需要再催）
     */
    public static boolean shouldFire(boolean on, boolean inWindow, boolean screenOn,
                                     boolean locked, boolean passActive,
                                     long nowMs, long lastFiredMs, int everyMinutes) {
        if (!on || !inWindow || !screenOn || locked || passActive) return false;
        return due(nowMs, lastFiredMs, everyMinutes);
    }

    /** 「45 分钟」这种时长文案（拆成数字与单位，英文好换序）。 */
    public static String minutes(int value) {
        return T.f("%d 分钟", value);
    }
}
