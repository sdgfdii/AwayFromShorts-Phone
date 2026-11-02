package com.afs.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * 定时提醒的执行者：跟 {@link GuardService} 的 5 秒节拍走，到点弹出可关闭的提醒页面，并保留通知入口。
 *
 * 不发的情况见 {@link RestReminder#shouldFire}：灭屏、锁机中、放行期里都不打扰。
 *
 * 发完立刻把「上次提醒」写到当前时刻 —— 通知发失败也要写，
 * 否则下一次 tick 会重试，节拍一密集就变成连环弹。
 */
public final class RestAlert {

    /**
     * 通道 id 带版本号。<br>
     * 真机上旧的 `afs_rest` 通道被系统/用户降成了 LOW（通知栏里躺着、不弹横幅，
     * 用户完全感知不到 = 等于没提醒）。通道一旦创建，应用改不了它的重要性 ——
     * 只能换一个新的 id 重新按 HIGH 建一次，用户没动过的新通道就会正常弹横幅。
     */
    private static final String CHANNEL = "afs_rest_v2";
    private static final int ID = 51;

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

    static void show(Context c, int every) {
        // 页面和通知相互独立：拒绝通知权限也不能把页面提醒一起短路。
        try { c.startActivity(RestReminderActivity.intent(c, every)); }
        catch (RuntimeException error) { Log.w(Guard.TAG, "rest popup launch failed", error); }
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, T.s("休息提醒"), NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(T.s("定时提醒：每隔一段时间提醒你起来休息"));
            channel.enableVibration(true);
            channel.setShowBadge(true);
            manager.createNotificationChannel(channel);
        }
        Intent open = RestReminderActivity.intent(c, every);
        PendingIntent pending = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(T.s("该歇会儿了"))
                .setContentText(T.f("已经连续 %d 分钟没休息，站起来走走，看看远处。", every))
                .setContentIntent(pending)
                .setWhen(System.currentTimeMillis())
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        try {
            manager.notify(ID, builder.build());
        } catch (SecurityException ignored) {
            // 没给通知权限：静默跳过，别把服务带崩
        }
    }

    /** 关掉提醒时顺手撤掉还挂在通知栏的那条。 */
    public static void clear(Context c) {
        RestReminderActivity.dismissCurrent();
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(ID);
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
