package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 定时提醒的配置与状态。
 *
 * 只存「上次提醒是几点几分」这一个时间戳：判断"该不该发"全部由它加上间隔算出来，
 * 不记「今天已经发过几次」这类账 —— 进程被杀、跨零点、改间隔都不会错乱。
 */
public final class ReminderStore {

    private static final String SP = "afs_rest";

    private static final String K_ON = "on";
    private static final String K_EVERY = "every";
    private static final String K_FROM = "from";
    private static final String K_TO = "to";
    private static final String K_LAST = "last";

    public static final int DEFAULT_FROM = 8 * 60;
    public static final int DEFAULT_TO = 22 * 60;

    private ReminderStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static boolean on(Context c) {
        return sp(c).getBoolean(K_ON, false);
    }

    public static void setOn(Context c, boolean value) {
        sp(c).edit().putBoolean(K_ON, value).apply();
        if (value) {
            // 刚打开就从"现在"起算：不然会立刻弹一条，像是坏了
            setLastFired(c, System.currentTimeMillis());
        } else {
            setLastFired(c, 0L);
        }
    }

    public static int every(Context c) {
        return clamp(sp(c).getInt(K_EVERY, RestReminder.DEFAULT_EVERY), 1, RestReminder.MAX_EVERY);
    }

    public static void setEvery(Context c, int minutes) {
        sp(c).edit().putInt(K_EVERY, clamp(minutes, 1, RestReminder.MAX_EVERY)).apply();
        setLastFired(c, System.currentTimeMillis());   // 改了间隔重新起算
    }

    public static int from(Context c) {
        return clamp(sp(c).getInt(K_FROM, DEFAULT_FROM), 0, 1439);
    }

    public static int to(Context c) {
        return clamp(sp(c).getInt(K_TO, DEFAULT_TO), 0, 1439);
    }

    public static void setWindow(Context c, int from, int to) {
        sp(c).edit().putInt(K_FROM, clamp(from, 0, 1439)).putInt(K_TO, clamp(to, 0, 1439)).apply();
    }

    public static long lastFired(Context c) {
        return sp(c).getLong(K_LAST, 0L);
    }

    public static void setLastFired(Context c, long ms) {
        sp(c).edit().putLong(K_LAST, ms).apply();
    }

    /** "08:00–22:00" 这种时段文案，和锁机那边同一格式。 */
    public static String window(Context c) {
        return LockStore.clock(from(c)) + "–" + LockStore.clock(to(c));
    }

    /** 设置页那一行状态。 */
    public static String summary(Context c) {
        if (!on(c)) return T.s("关");
        return T.f("每 %d 分钟 · %s", every(c), window(c));
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
