package com.afs.guard;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * 定时锁机的执行者：每几秒问一次"现在该不该锁"，该锁就把锁屏页拉到最前。
 *
 * 为什么不用闹钟精确到秒：锁机是"到点就该休息"，晚几秒无所谓；而闹钟在 ColorOS 上
 * 会被整体推后（实测 2 天以上），反而更不准。这里跟着 GuardService 的节拍走。
 *
 * 不锁的情况（都不弹窗打扰人）：
 *   · 解锁码放行期内
 *   · 灭屏了（睡着的时候锁屏没有意义，醒来时这一段早就过去了）
 *   · 前台应用在白名单里
 *   · 不是设备所有者（拦不住 Home，弹出来只让人困惑；界面里已写明）
 */
public final class LockEnforcer {

    /** 锁屏页是否正在显示（防重复拉起）。 */
    static volatile boolean showing;

    private static long lastAttempt;

    /** 亮屏累计：内存里算，每 30 秒落一次盘（进程被杀最多丢半分钟）。 */
    private static long accum = -1L;
    private static long lastTickAt;
    private static long lastPersistAt;

    private LockEnforcer() {
    }

    /** 一次锁：到什么时候结束、是不是因为某个应用用太久。 */
    static final class Hold {
        final long end;
        final String app;

        Hold(long end, String app) {
            this.end = end;
            this.app = app == null ? "" : app;
        }

        boolean active(long now) {
            return end > now;
        }

        static final Hold NONE = new Hold(0L, "");
    }

    public static void tick(Context c) {
        try {
            long now = GuardClock.now(c).getTimeInMillis();
            PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            boolean interactive = power == null || power.isInteractive();
            // 采样必须在所有提前返回之前：不然灭屏那段会被当成亮屏补算进去。
            sampleScreen(c, now, interactive);
            if (!LockStore.anyOn(c)) return;
            if (UnlockStore.passActive(c, now)) return;
            if (!Guard.isOwner(c)) return;
            if (!interactive) return;
            if (showing) return;
            if (now - lastAttempt < 4000L) return;
            Hold hold = holdOf(c, now);
            if (!hold.active(now)) return;
            lastAttempt = now;
            show(c, now, hold);
        } catch (RuntimeException error) {
            Log.w(Guard.TAG, "lock tick failed: " + error.getClass().getSimpleName());
        }
    }

    /**
     * 累加亮屏时长。只有「屏幕亮着 + 在生效时段内 + 没在锁着」才算数。
     *
     * 顺带节流落盘：每 30 秒写一次，避免 GuardService 每几秒就动一次 SharedPreferences。
     */
    static void sampleScreen(Context c, long now, boolean interactive) {
        if (accum < 0) accum = LockStore.screenAccum(c);
        boolean counting = interactive && !showing
                && LockStore.periodicOn(c)
                && inWindow(c, now);
        if (lastTickAt > 0) accum = ScreenLock.accumulate(accum, lastTickAt, now, counting);
        lastTickAt = now;
        if (now - lastPersistAt >= 30000L) {
            LockStore.setScreenAccum(c, accum);
            lastPersistAt = now;
        }
    }

    /** 现在在不在「周期锁」的生效时段里。 */
    static boolean inWindow(Context c, long now) {
        long dayStart = LockStore.dayStartMs(GuardClock.now(c));
        return LockPlan.inWindow(LockPlan.minuteOfDay(now, dayStart), LockStore.from(c), LockStore.to(c));
    }

    /** 界面上要显示「这一轮累计亮屏多久了」。 */
    static long screenAccum(Context c) {
        if (accum < 0) accum = LockStore.screenAccum(c);
        return accum;
    }

    /**
     * 现在这一次锁的情况；不锁返回 {@link Hold#NONE}。
     *
     * 顺手把久用锁的结束时刻落盘 —— 锁屏页一起来，前台就变成我们自己，
     * 连续使用时长的判据当场消失，不落盘就会在解锁后又立刻判一次。
     */
    static Hold holdOf(Context c, long now) {
        long screenEnd = 0L;
        if (LockStore.periodicOn(c)) {
            int period = LockStore.period(c);
            int lock = LockStore.lockMinutes(c);
            long used = accum < 0 ? LockStore.screenAccum(c) : accum;
            if (ScreenLock.reached(used, period)) {
                screenEnd = now + lock * LockPlan.MINUTE;
                accum = 0L;                     // 锁完这一轮就重新累计
                lastPersistAt = now;
                LockStore.setScreenAccum(c, 0L);
            }
        }
        long appEnd = LockStore.appLockUntil(c);
        String appPkg = appEnd > now ? LockStore.appLockPkg(c) : "";
        if (appEnd <= now && LockStore.appOn(c)) {
            String candidate = appCandidate(c, now);
            if (candidate != null) {
                appEnd = now + LockStore.appLockMinutes(c) * LockPlan.MINUTE;
                appPkg = candidate;
                LockStore.setAppLockUntil(c, appEnd);
                LockStore.setAppLockPkg(c, candidate);
            }
        }
        if (appEnd > screenEnd) {
            return appEnd > now ? new Hold(appEnd, appPkg) : Hold.NONE;
        }
        return screenEnd > now ? new Hold(screenEnd, "") : Hold.NONE;
    }

    /** 有没有哪个应用连续用超了。白名单、没被选中的应用和自己在内不算。 */
    static String appCandidate(Context c, long now) {
        if (!LockStore.appOn(c)) return null;
        String foreground = AppUsageQuota.foreground(c, now);
        if (foreground == null || foreground.equals(c.getPackageName())) return null;
        if (!LockStore.appMonitored(c, foreground)) return null;
        if (LockStore.keys(c).contains(foreground)) return null;
        long since = AppUsageQuota.continuousStart(c, foreground, now);
        if (since <= 0) return null;
        return LockPlan.appOverLimit(now - since, LockStore.appLimit(c)) ? foreground : null;
    }

    private static void show(Context c, long now, Hold hold) {
        Intent intent = new Intent(c, LockScreenActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(LockScreenActivity.EXTRA_END, hold.end)
                .putExtra(LockScreenActivity.EXTRA_NOW, now)
                .putExtra(LockScreenActivity.EXTRA_APP, hold.app);
        try {
            showing = true;
            LockStore.setShownAt(c, now);
            c.startActivity(intent);
        } catch (RuntimeException error) {
            showing = false;
            Log.w(Guard.TAG, "lock show failed: " + error.getClass().getSimpleName());
        }
    }

    /** 锁屏页自己结束时调，让下个周期还能再弹。 */
    static void released() {
        showing = false;
    }
}
