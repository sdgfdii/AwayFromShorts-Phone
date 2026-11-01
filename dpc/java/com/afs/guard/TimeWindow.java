package com.afs.guard;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 全局「收束时段」：一天里只有这段时间内，断网和每日限额才真正动手。
 *
 * 以前两件事各管各的 —— 断网是个 24 小时常开开关，限额全天累计 —— 想调「今天几点到几点
 * 要收着点」得改两个地方，还容易只改一半。现在只有这一个时段，谁要按时段生效就来读它。
 *
 * 语义只有三条，所有判断都必须走 {@link #contains(long, int, int)}：
 * 1. 左闭右开：[21:00, 22:00) 表示 21:00:00 生效、21:59:59 还在、22:00:00 已经放开。
 * 2. start &gt; end 表示跨午夜（22:00–01:00）。
 * 3. start == end 当成全天生效 —— 唯一能表达「整天都管」的写法，不允许当成空。
 */
public final class TimeWindow {

    static final String SP = "afs_time_window";
    private static final String K_START = "start";
    private static final String K_END = "end";

    /** 晚上 9 点到 10 点。 */
    public static final int DEFAULT_START = 21 * 60;
    public static final int DEFAULT_END = 22 * 60;

    private static final int MINUTES = 24 * 60;

    private TimeWindow() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static int start(Context c) {
        if (c == null) return DEFAULT_START;
        return sane(sp(c).getInt(K_START, DEFAULT_START), DEFAULT_START);
    }

    public static int end(Context c) {
        if (c == null) return DEFAULT_END;
        return sane(sp(c).getInt(K_END, DEFAULT_END), DEFAULT_END);
    }

    private static int sane(int value, int fallback) {
        return value < 0 || value >= MINUTES ? fallback : value;
    }

    public static void save(Context c, int start, int end) {
        if (start < 0 || start >= MINUTES || end < 0 || end >= MINUTES) {
            throw new IllegalArgumentException(T.s("时刻只能是 0:00–23:59"));
        }
        if (!sp(c).edit().putInt(K_START, start).putInt(K_END, end).commit()) {
            throw new IllegalStateException(T.s("收束时段未能保存"));
        }
    }

    public static String label(Context c) {
        return clock(start(c)) + "–" + clock(end(c));
    }

    /** 现在在不在时段里。 */
    public static boolean on(Context c) {
        if (c == null) return true;
        return contains(GuardClock.now(c).getTimeInMillis(), start(c), end(c));
    }

    public static String clock(int minutes) {
        int value = minutes % MINUTES;
        return String.format(Locale.CHINA, "%02d:%02d", value / 60, value % 60);
    }

    /* ==================== 纯逻辑（离线可测） ==================== */

    static int minuteOf(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    static boolean contains(long now, int start, int end) {
        if (start == end) return true;
        int minute = minuteOf(now);
        return start < end ? minute >= start && minute < end : minute >= start || minute < end;
    }

    /**
     * 把 [from, to] 和窗口的交集切成若干段，给额度账本按段累计用。
     *
     * 跨午夜的窗口一天最多出现一段，所以从前一天开始扫、最多扫 5 天就够；
     * 中国没有夏令时，{@code 零点 + 分钟数} 就是墙上时间，不用管偏移。
     */
    static List<long[]> segments(long from, long to, int start, int end) {
        List<long[]> out = new ArrayList<long[]>();
        if (to <= from) return out;
        if (start == end) {
            out.add(new long[]{from, to});
            return out;
        }
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(from);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        day.add(Calendar.DAY_OF_MONTH, -1);          // 跨午夜的窗口可能从前一天开始
        for (int i = 0; i < 5 && day.getTimeInMillis() <= to; i++) {
            long base = day.getTimeInMillis();
            long open = base + start * 60000L;
            long close = start < end ? base + end * 60000L : base + (MINUTES + end) * 60000L;
            long a = Math.max(from, open);
            long b = Math.min(to, close);
            if (b > a) out.add(new long[]{a, b});
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    /* ==================== 选择时段 ==================== */

    /**
     * 两段式选择：先选开始，再选结束。半小时一档 —— 这个功能调的是「危险的一小时」，
     * 精确到分没有意义，反而让人在 47 个选项里找。
     */
    public static void pick(final Activity host, final Runnable after) {
        if (host == null || host.isFinishing()) return;
        final String[] slots = new String[48];
        for (int i = 0; i < slots.length; i++) slots[i] = clock(i * 30);
        final int currentStart = start(host) / 30;
        ThemeStore.dialog(host)
                .setTitle(T.s("从几点开始"))
                .setSingleChoiceItems(slots, Math.min(currentStart, 47), (dialog, which) -> {
                    dialog.dismiss();
                    final int chosenStart = which * 30;
                    ThemeStore.dialog(host)
                            .setTitle(T.s("到几点结束"))
                            .setSingleChoiceItems(slots, Math.min(end(host) / 30, 47), (endDialog, item) -> {
                                endDialog.dismiss();
                                int chosenEnd = item * 30;
                                try {
                                    save(host, chosenStart, chosenEnd);
                                } catch (RuntimeException error) {
                                    toast(host, error.getMessage());
                                    return;
                                }
                                NetBlockService.reconcile(host);
                                if (after != null) after.run();
                            })
                            .setNegativeButton(T.s("取消"), null)
                            .show();
                })
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    private static void toast(Activity host, String value) {
        try {
            android.widget.Toast.makeText(host, value == null ? T.s("操作未完成") : value,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) {
        }
    }
}
