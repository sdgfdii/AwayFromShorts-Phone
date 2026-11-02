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
 * 现在统一到早上 8 点（{@link #DEFAULT_HOUR}），并且**不再提供修改入口** ——
 * 电脑端 AFS_QUOTA_RESET_HOUR 也是 8，跨设备共享的「今天」必须两边同一天翻页。
 *
 * 语义只有一个：{@link #startMs(long, int)} 返回「此刻所属额度日的起点」。
 * 所有日界判断（账本翻页、使用记录拉取起点）都必须走它，别各自 set(HOUR_OF_DAY,0)。
 */
public final class DayBoundary {

    static final String SP = "afs_day_boundary";
    static final String KEY_HOUR = "reset_hour";
    /** 一次性迁移标记：老账本按零点写的，用户也可能选过别的整点，统一到 8 点只做一次。 */
    static final String KEY_UNIFIED_8 = "unified_8am";

    /**
     * 早上 8 点，与电脑端 core.ps1 的 {@code $script:AFS_QUOTA_RESET_HOUR} 一致。
     *
     * 改这个值等于移动日界：在 8 点之后改，今天的账本键不变、用量保留；
     * 在 8 点之前改，会把刚过去的这几个小时划到「昨天」。
     */
    public static final int DEFAULT_HOUR = 8;

    private DayBoundary() {
    }

    public static int hour(Context c) {
        if (c == null) return DEFAULT_HOUR;
        SharedPreferences p = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        // 统一到早上 8 点：老机器里可能存着 0 点或用户选过的其它整点，只迁移一次。
        // 迁移点选在 8 点之后执行时账本键不变，今天的用量不会丢。
        if (!p.getBoolean(KEY_UNIFIED_8, false)) {
            p.edit().putInt(KEY_HOUR, DEFAULT_HOUR)
                    .remove("pending_hour").remove("pending_at")
                    .putBoolean(KEY_UNIFIED_8, true).commit();
            return DEFAULT_HOUR;
        }
        int value = p.getInt(KEY_HOUR, DEFAULT_HOUR);
        int pending=p.getInt("pending_hour",-1);long at=p.getLong("pending_at",Long.MAX_VALUE);
        if(pending>=0&&pending<=23&&System.currentTimeMillis()>=at){
            if(p.edit().putInt(KEY_HOUR,pending).remove("pending_hour").remove("pending_at").commit())value=pending;
        }
        return value < 0 || value > 23 ? DEFAULT_HOUR : value;
    }

    public static boolean saveHour(Context c, int hour) {
        if (hour < 0 || hour > 23) throw new IllegalArgumentException(T.s("重置时刻只能是 0–23 时"));
        int active=hour(c);SharedPreferences p=c.getSharedPreferences(SP,Context.MODE_PRIVATE);
        SharedPreferences.Editor edit=p.edit();
        if(hour==active)return edit.remove("pending_hour").remove("pending_at").commit();
        long now=System.currentTimeMillis(),at=p.getLong("pending_at",0);
        at=changeAt(now,active,at);
        return edit.putInt("pending_hour",hour).putLong("pending_at",at).commit();
    }

    static long changeAt(long now,int active,long existing){return existing>now?existing:nextResetMs(now,active);}

    public static int selectedHour(Context c){SharedPreferences p=c.getSharedPreferences(SP,0);return p.getInt("pending_hour",hour(c));}

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

    /** 给界面用的一句话：统一后的 8 点写「每天早上 8 点」，其它整点仍按「每日 4:00」。 */
    public static String label(int hour) {
        int reset = hour < 0 || hour > 23 ? DEFAULT_HOUR : hour;
        if (reset == DEFAULT_HOUR) return T.s("每天早上 8 点");
        return reset == 0 ? T.s("每日零点") : String.format(Locale.CHINA, T.s("每日 %d:00"), reset);
    }

    public static String label(Context c) {
        return label(hour(c));
    }
}
