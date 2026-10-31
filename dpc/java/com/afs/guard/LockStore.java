package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * 定时锁机的配置与本机状态。
 *
 * 只存数字和包名，不存任何"这一轮已经锁过"的账 —— 锁没锁完全由时间算出来，
 * 进程被杀、重启手机都不会错乱（这一点和番茄钟的 LockTask 不一样）。
 */
public final class LockStore {

    private static final String SP = "afs_lock";

    private static final String K_PERIODIC = "periodic";
    private static final String K_FROM = "from";
    private static final String K_TO = "to";
    private static final String K_PERIOD = "period";
    private static final String K_LOCK = "lock";
    private static final String K_APP_ON = "app_on";
    private static final String K_APP_LIMIT = "app_limit";
    private static final String K_APP_LOCK = "app_lock";
    private static final String K_APP_TARGETS = "app_targets";
    private static final String K_SCREEN_ACCUM = "screen_accum";
    private static final String K_KEYS = "keys";
    private static final String K_APP_UNTIL = "app_until";
    private static final String K_APP_PKG = "app_pkg";
    private static final String K_SHOWN_AT = "shown_at";

    public static final int DEFAULT_FROM = 8 * 60;
    public static final int DEFAULT_TO = 22 * 60;
    public static final int DEFAULT_PERIOD = 45;
    public static final int DEFAULT_LOCK = 5;
    public static final int DEFAULT_APP_LIMIT = 30;
    public static final int DEFAULT_APP_LOCK = 5;

    private LockStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static boolean periodicOn(Context c) {
        return sp(c).getBoolean(K_PERIODIC, false);
    }

    public static void setPeriodicOn(Context c, boolean value) {
        sp(c).edit().putBoolean(K_PERIODIC, value).apply();
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

    /**
     * 「周期锁」现在按累计亮屏计时，所以 period/lock 这两个字段的含义跟着变成
     * 「累计亮屏多少分钟」和「每次锁多少分钟」——旧用户存下的 45/5 正好还是 45/5，
     * 不用迁移，也不会因为改口径把别人的设置清掉。
     */
    public static int period(Context c) {
        return clamp(sp(c).getInt(K_PERIOD, DEFAULT_PERIOD), 5, 240);
    }

    public static int lockMinutes(Context c) {
        return clamp(sp(c).getInt(K_LOCK, DEFAULT_LOCK), 1, 60);
    }

    public static void setPeriod(Context c, int period, int lock) {
        sp(c).edit().putInt(K_PERIOD, clamp(period, 5, 240)).putInt(K_LOCK, clamp(lock, 1, 60)).apply();
    }

    public static boolean appOn(Context c) {
        return sp(c).getBoolean(K_APP_ON, false);
    }

    public static void setAppOn(Context c, boolean value) {
        sp(c).edit().putBoolean(K_APP_ON, value).apply();
    }

    public static int appLimit(Context c) {
        return clamp(sp(c).getInt(K_APP_LIMIT, DEFAULT_APP_LIMIT), 5, 240);
    }

    public static int appLockMinutes(Context c) {
        return clamp(sp(c).getInt(K_APP_LOCK, DEFAULT_APP_LOCK), 1, 60);
    }

    public static void setAppLimit(Context c, int limit, int lock) {
        sp(c).edit().putInt(K_APP_LIMIT, clamp(limit, 5, 240)).putInt(K_APP_LOCK, clamp(lock, 1, 60)).apply();
    }

    /**
     * 久用锁只管这些应用。**空集＝所有应用**（保持老行为），选了就只管选中的。
     * 用空集表达"全部"而不是"一个都不锁"，是因为多数人不会去选，默认该是全监控。
     */
    public static Set<String> appTargets(Context c) {
        return packages(c, K_APP_TARGETS);
    }

    public static void setAppTargets(Context c, Set<String> packages) {
        putPackages(c, K_APP_TARGETS, packages);
    }

    /** 某个应用要不要参与久用锁。 */
    public static boolean appMonitored(Context c, String pkg) {
        Set<String> targets = appTargets(c);
        return targets.isEmpty() || targets.contains(pkg);
    }

    /* ---------- 亮屏累计（周期锁的新口径） ---------- */

    /** 本轮已经累计的亮屏毫秒数；锁完一次清零重来。 */
    public static long screenAccum(Context c) {
        long value = sp(c).getLong(K_SCREEN_ACCUM, 0L);
        return value < 0 ? 0L : value;
    }

    public static void setScreenAccum(Context c, long millis) {
        sp(c).edit().putLong(K_SCREEN_ACCUM, Math.max(0L, millis)).apply();
    }

    /** 白名单：这些应用不参与锁机（通话、导航、学习工具）。 */
    public static Set<String> keys(Context c) {
        return packages(c, K_KEYS);
    }

    public static void setKeys(Context c, Set<String> packages) {
        putPackages(c, K_KEYS, packages);
    }

    private static Set<String> packages(Context c, String key) {
        Set<String> out = new HashSet<String>();
        String raw = sp(c).getString(key, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String item : raw.split("\n")) {
            String pkg = item.trim();
            if (!pkg.isEmpty()) out.add(pkg);
        }
        return out;
    }

    private static void putPackages(Context c, String key, Set<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : packages) {
            if (pkg == null || pkg.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(pkg.trim());
        }
        sp(c).edit().putString(key, sb.toString()).apply();
    }

    public static boolean anyOn(Context c) {
        return periodicOn(c) || appOn(c);
    }

    /* ---------- 运行时状态 ---------- */

    public static long appLockUntil(Context c) {
        return sp(c).getLong(K_APP_UNTIL, 0L);
    }

    public static void setAppLockUntil(Context c, long value) {
        sp(c).edit().putLong(K_APP_UNTIL, value).apply();
    }

    /** 触发久用锁的那个应用（只用于锁屏页上写清楚"是哪个应用用太久了"）。 */
    public static String appLockPkg(Context c) {
        String value = sp(c).getString(K_APP_PKG, "");
        return value == null ? "" : value;
    }

    public static void setAppLockPkg(Context c, String pkg) {
        sp(c).edit().putString(K_APP_PKG, pkg == null ? "" : pkg).apply();
    }

    /** 上次把锁屏页拉到前台的时间：防抖，避免 tick 每 5 秒起一次页面。 */
    public static long shownAt(Context c) {
        return sp(c).getLong(K_SHOWN_AT, 0L);
    }

    public static void setShownAt(Context c, long value) {
        sp(c).edit().putLong(K_SHOWN_AT, value).apply();
    }

    /* ---------- 时间工具 ---------- */

    /** now 所在那天的本地零点（用调用方的 Calendar，时区已按 GuardClock 钉住）。 */
    public static long dayStartMs(Calendar now) {
        Calendar c = (Calendar) now.clone();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 「08:30」这种时刻文案。 */
    public static String clock(int minuteOfDay) {
        int m = clamp(minuteOfDay, 0, 1439);
        return pad(m / 60) + ":" + pad(m % 60);
    }

    /** 「08:30–22:00」这种时段文案。 */
    public static String window(Context c) {
        return clock(from(c)) + "–" + clock(to(c));
    }

    static String pad(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
