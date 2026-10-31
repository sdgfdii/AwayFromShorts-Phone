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
 * 定时提醒的执行者：跟 {@link GuardService} 的 5 秒节拍走，到点发一条通知，不拦人。
 *
 * 不发的情况见 {@link RestReminder#shouldFire}：灭屏、锁机中、放行期里都不打扰。
 *
 * 发完立刻把「上次提醒」写到当前时刻 —— 通知发失败也要写，
 * 否则下一次 tick 会重试，节拍一密集就变成连环弹。
 */
public final class RestAlert {

    private static final String CHANNEL = "afs_rest";
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

    private static void show(Context c, int every) {
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, T.s("休息提醒"), NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(T.s("该歇会儿了"))
                .setContentText(T.f("已经连续 %d 分钟没休息，站起来走走，看看远处。", every))
                .setContentIntent(pending)
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
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(ID);
    }
}
