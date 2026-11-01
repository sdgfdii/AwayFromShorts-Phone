package com.afs.guard;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 断网「调整时段」：一天里唯一能改断网设置的一段时间，也是断网唯一放开的时间。
 *
 * 和 {@link TimeWindow}（收束时段）是两件事，故意不共用：收束时段管每日限额和冻结名单，
 * 断网是「默认常开、只在这段时间放开」，方向正好相反，绑在一起必然有一边是错的。
 *
 * 语义只有三条：
 * 1. 左闭右开、跨午夜、start==end 全天，判断全走 {@link TimeWindow#contains(long, int, int)}。
 * 2. 时段内：隧道断开（放开）、开关/名单/时段本身都能改；时段外：按名单断网、三者一律锁住。
 * 3. 时段本身也上锁 —— 否则把时段改成「现在」就等于给自己开了后门。
 */
public final class NetWindow {

    static final String SP = "afs_net_window";
    private static final String K_START = "start";
    private static final String K_END = "end";

    private static final int MINUTES = 24 * 60;

    /** 默认每晚 10 点到 11 点；可在断网设置里自定义。 */
    public static final int DEFAULT_START = 22 * 60;
    public static final int DEFAULT_END = 23 * 60;

    private NetWindow() {
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
            throw new IllegalStateException(T.s("断网调整时段未能保存"));
        }
    }

    public static String label(Context c) {
        return TimeWindow.clock(start(c)) + "–" + TimeWindow.clock(end(c));
    }

    /** 现在是不是在调整时段里 —— 是的话设置可改、断网放开。 */
    public static boolean inside(Context c) {
        if (c == null) return false;
        return contains(GuardClock.now(c).getTimeInMillis(), start(c), end(c));
    }

    /**
     * 设置能不能改。不能改就顺手把「什么时候能改」说出来，别让人对着灰按钮猜。
     *
     * 首次安装的 10 分钟宽限（{@link Config#inGrace}）里也放行：刚装完正好不在
     * 调整时段的话，连断网名单都选不了，等于把这个功能锁死在装完的第一天。
     * 宽限只影响「能不能改」，不影响断网本身什么时候生效。
     */
    public static boolean canEdit(Activity host) {
        if (host == null) return false;
        if (inside(host) || Config.inGrace(host)) return true;
        TimeWindow.toast(host, T.f("断网设置只能在调整时段 %s 内修改", label(host)));
        return false;
    }

    /** 两段式选择，和收束时段共用同一套半小时一档的选择器。 */
    public static void pick(final Activity host, final Runnable after) {
        TimeWindow.pickSlots(host, start(host), end(host), new TimeWindow.Saver() {
            @Override
            public void save(int start, int end) {
                NetWindow.save(host, start, end);
                NetBlockService.reconcile(host);   // 改完立刻按新时段翻一次隧道
            }
        }, after);
    }

    /* ==================== 纯逻辑（离线可测） ==================== */

    static boolean contains(long now, int start, int end) {
        return TimeWindow.contains(now, start, end);
    }

    /**
     * 隧道该不该在跑：开了断网、选了应用，而且不在调整时段里。
     *
     * 这一行就是整个功能的方向 —— 以前是「在时段内才断」，现在是「除了时段内都断」。
     */
    static boolean shouldCut(boolean enabled, boolean inside) {
        return enabled && !inside;
    }
}
