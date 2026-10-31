package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 每日额度的「一天」从几点开始。
 *
 * 以前写死本地零点（{@code Calendar.HOUR_OF_DAY=0}），对熬夜的人是错的：
 * 凌晨 1 点还在用，零点已经翻篇，额度等于白送一份。
 * 这里把它抽成一个可配置的整点，默认仍是 0 点 —— 老用户的账本键值不变。
 *
 * 语义只有一个：{@link #startMs(long, int)} 返回「此刻所属额度日的起点」。
 * 所有日界判断（账本翻页、使用记录拉取起点）都必须走它，别各自 set(HOUR_OF_DAY,0)。
 */
public final class DayBoundary {

    static final String SP = "afs_day_boundary";
    static final String KEY_HOUR = "reset_hour";

    /** 0 点。历史账本的 date 键就是按它写的，改默认值等于让所有用量清零。 */
    public static final int DEFAULT_HOUR = 0;

    private DayBoundary() {
    }

    public static int hour(Context c) {
        if (c == null) return DEFAULT_HOUR;
        SharedPreferences p = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        int value = p.getInt(KEY_HOUR, DEFAULT_HOUR);
        return value < 0 || value > 23 ? DEFAULT_HOUR : value;
    }

    public static boolean saveHour(Context c, int hour) {
        if (hour < 0 || hour > 23) throw new IllegalArgumentException(T.s("重置时刻只能是 0–23 时"));
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putInt(KEY_HOUR, hour).commit();
    }

    /** 此刻所属额度日的起点。hour=0 时与旧的「本地零点」逐毫秒一致。 */
    public static long startMs(long now, int hour) {
        int reset = hour < 0 || hour > 23 ? DEFAULT_HOUR : hour;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int current = c.get(Calendar.HOUR_OF_DAY);
        c.set(Calendar.HOUR_OF_DAY, reset);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (current < reset) c.add(Calendar.DAY_OF_MONTH, -1);
        return c.getTimeInMillis();
    }

    public static long startMs(Context c, long now) {
        return startMs(now, hour(c));
    }

    /** 账本翻页判据：用的是额度日起点的日期，不是墙上时间的日期。 */
    public static String key(long now, int hour) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(startMs(now, hour)));
    }

    public static String key(Context c, long now) {
        return key(now, hour(c));
    }

    /** 下一次翻页时刻，用来给页面写「x 小时后重置」。 */
    public static long nextResetMs(long now, int hour) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startMs(now, hour));
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** 给界面用的一句话：0 点写「每日零点」，其余写「每日 4:00」。 */
    public static String label(int hour) {
        int reset = hour < 0 || hour > 23 ? DEFAULT_HOUR : hour;
        return reset == 0 ? T.s("每日零点") : String.format(Locale.CHINA, T.s("每日 %d:00"), reset);
    }

    public static String label(Context c) {
        return label(hour(c));
    }
}
