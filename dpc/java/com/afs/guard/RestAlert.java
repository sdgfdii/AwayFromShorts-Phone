package com.afs.guard;

import android.app.NotificationManager;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

/**
 * 定时提醒的执行者：跟 {@link GuardService} 的节拍走，到点只弹可关闭的提醒页面，不发通知。
 *
 * 不发的情况见 {@link RestReminder#shouldFire}：灭屏、锁机中、放行期里都不打扰。
 *
 * 发完立刻把「上次提醒」写到当前时刻 —— 弹页面失败也要写，
 * 否则下一次 tick 会重试，节拍一密集就变成连环弹。
 */
public final class RestAlert {

    /** 旧版本用过的提醒通知 id，只用于把历史遗留的那条清干净。 */
    private static final int LEGACY_NOTIFICATION_ID = 51;

    private RestAlert() {
    }

    public static void tick(Context c) {
        try {
            if (!ReminderStore.on(c)) return;
            long now = GuardClock.now(c).getTimeInMillis();
            long dayStart = LockStore.dayStartMs(GuardClock.now(c));
            boolean inWindow = LockPlan.inWindow(LockPlan.minuteOfDay(now, dayStart),
                    ReminderStore.from(c), ReminderStore.to(c));
            boolean screenOn = true;
            PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (power != null) screenOn = power.isInteractive();
            boolean locked = LockEnforcer.showing || LockStore.appLockUntil(c) > now;
            boolean pass = UnlockStore.passActive(c, now);
            long last = ReminderStore.lastFired(c);
            if (last <= 0L) {                       // 首次起算，不弹
                ReminderStore.setLastFired(c, now);
                return;
            }
            if (!RestReminder.shouldFire(true, inWindow, screenOn, locked, pass,
                    now, last, ReminderStore.every(c))) {
                return;
            }
            ReminderStore.setLastFired(c, now);
            show(c, ReminderStore.every(c));
        } catch (RuntimeException error) {
            Log.w(Guard.TAG, "rest reminder failed: " + error.getClass().getSimpleName());
        }
    }

    /**
     * 只弹页面。
     *
     * 不再发通知：页面本身就是提醒，通知栏那条既会被用户划掉、又会在页面关掉后
     * 还留在栏里，等于同一次提醒打扰两遍。没有了通知兜底，弹窗权限就是唯一通路，
     * 设置页里「后台提醒弹窗」必须允许，否则系统会直接拦掉这次 launch。
     */
    static void show(Context c, int every) {
        if (!RestReminderActivity.backgroundAllowed(c)) {
            // 没给弹窗权限：后台 launch 会被系统静默拦下。先挂起来，回 App 时补弹。
            ReminderStore.setPending(c, System.currentTimeMillis());
            return;
        }
        try { c.startActivity(RestReminderActivity.intent(c, every)); }
        catch (RuntimeException error) { Log.w(Guard.TAG, "rest popup launch failed", error); }
    }

    /**
     * 补弹被拦下的提醒（由 MainActivity 在首帧后调用）。
     *
     * 超过 4 小时的旧提醒直接丢掉 —— 早上开机弹一条「已经连续 45 分钟没休息」只会莫名其妙。
     */
    static void deliverPending(Context c) {
        long pending = ReminderStore.pending(c);
        if (pending <= 0L) return;
        ReminderStore.setPending(c, 0L);
        if (!ReminderStore.on(c)) return;
        if (System.currentTimeMillis() - pending > 4L * 60L * 60L * 1000L) return;
        try { c.startActivity(RestReminderActivity.intent(c, ReminderStore.every(c))); }
        catch (RuntimeException error) { Log.w(Guard.TAG, "pending rest popup failed", error); }
    }

    /** 关掉提醒时顺手撤掉旧版本还挂在通知栏的那条。 */
    public static void clear(Context c) {
        RestReminderActivity.dismissCurrent();
        ReminderStore.setPending(c, 0L);
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(LEGACY_NOTIFICATION_ID);
    }

    /**
     * 下一次该提醒的时刻（给守护闹钟用）；没开返回 0。
     *
     * 为什么要算这个：进程被「最近任务」划掉后没人跑 tick，提醒会整个错过。
     * 把到点时刻排给 AlarmManager，系统会在那一刻把进程叫醒，tick 一跑就补发。
     * 时段外则排到下一次开门，避免每分钟空转。
     */
    static long nextDueAt(Context c) {
        if (!ReminderStore.on(c)) return 0L;
        long now = System.currentTimeMillis();
        java.util.Calendar calendar = GuardClock.now(c);
        int minute = LockPlan.minuteOfDay(now, LockStore.dayStartMs(calendar));
        int from = ReminderStore.from(c);
        int to = ReminderStore.to(c);
        if (!LockPlan.inWindow(minute, from, to)) {
            long dayStart = LockStore.dayStartMs(calendar);
            long open = dayStart + from * LockPlan.MINUTE;
            while (open <= now) open += 24L * 60L * LockPlan.MINUTE;
            return open;
        }
        long last = ReminderStore.lastFired(c);
        long due = last <= 0L ? now + 60_000L : last + ReminderStore.every(c) * LockPlan.MINUTE;
        return due > now ? due : now + 60_000L;
    }
}
