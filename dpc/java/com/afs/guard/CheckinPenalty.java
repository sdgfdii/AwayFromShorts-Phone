package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * 打卡没完成 → 冻结时段延长 30 分钟。
 *
 * <p>判断点是每天 20:00：到了 20:00 今天的学习打卡还没打完，就记一笔「今天欠 30 分钟」，
 * 这笔钱花在**每个受管应用本次冻结结束的那一刻** —— 原本 22:00 放行，就变成 22:30 放行。
 *
 * <p>两个刻意的取舍：
 * <ul>
 *   <li><b>入账后不撤销</b>。20:00 之后才把卡打完，这 30 分钟照样要等 —— 否则
 *       「先看会儿手机再打卡」永远没有代价，规则等于没有。</li>
 *   <li><b>一天只入账一次</b>。`armed_date == 今天` 就不再重复加，否则每次轮询都加一笔，
 *       一天下来能叠成十几个小时。</li>
 * </ul>
 *
 * <p>跨天的不加：冻结时段本身跨午夜（23:00-07:00 这类），或者下一次放行点已经不在今天，
 * 都没有「今天的这一段冻结」可以延长，硬加会把罚时甩到第二天。
 */
final class CheckinPenalty {

    /** 每次延长多久。和禁忌卡的默认值一致，界面上一眼能对上。 */
    static final long EXTEND_MS = 30 * 60_000L;

    /** 判断点：这个点之后（含）才看今天的打卡。 */
    static final int DUE_HOUR = 20;

    private static final String SP = "afs_checkin_penalty";
    private static final String K_DATE = "armed_date";
    private static final String K_AT = "armed_at";

    private CheckinPenalty() {
    }

    /* ---------- 纯判定（离线回归可测） ---------- */

    /** 判断点到了没有。 */
    static boolean atDuePoint(int hourOfDay) {
        return hourOfDay >= DUE_HOUR;
    }

    /**
     * 今天算不算「没按时打完卡」。
     *
     * @param pending   此刻还没打卡的目标数
     * @param lastHitAt 今天最后一笔打卡的时间戳（0 = 今天没打过）
     * @param dueAt     今天 20:00 的时间戳
     */
    static boolean missed(int pending, long lastHitAt, long dueAt) {
        if (pending > 0) return true;
        return lastHitAt > dueAt;
    }

    /**
     * 这次放行点还落在今天之内吗（跨午夜 / 明天才放行 → 不延长）。
     *
     * <p>只判两件事：规则本身有没有跨午夜的时段；放行点与当前是否同一天。
     * 用「同一天」而不是「距离 24 小时内」，是因为罚时说的是「今天这一段的 30 分钟」。
     */
    static boolean withinToday(Rule rule, long release, long now) {
        if (rule == null || release <= 0) return false;
        for (int i = 0; i < rule.windowCount(); i++) {
            if (rule.crossMidnightAt(i)) return false;
        }
        Calendar r = Calendar.getInstance();
        r.setTimeInMillis(release);
        Calendar n = Calendar.getInstance();
        n.setTimeInMillis(now);
        return r.get(Calendar.YEAR) == n.get(Calendar.YEAR)
                && r.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR);
    }

    /** 今天 20:00 的时间戳。 */
    static long dueAt(Calendar now) {
        Calendar c = (Calendar) now.clone();
        c.set(Calendar.HOUR_OF_DAY, DUE_HOUR);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /* ---------- 入账 ---------- */

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 今天这笔延长是否已经入账。 */
    static boolean armed(Context c) {
        return StudyStore.today().equals(sp(c).getString(K_DATE, ""));
    }

    /** 今天这笔延长入账的时刻（0 = 没入账）。 */
    static long armedAt(Context c) {
        SharedPreferences p = sp(c);
        return StudyStore.today().equals(p.getString(K_DATE, "")) ? p.getLong(K_AT, 0) : 0;
    }

    /**
     * 每个节拍问一次：该入账了吗。
     *
     * <p>由 GuardService 的常驻节拍调用，幂等 —— 同一天重复调用不会再叠第二笔。
     *
     * @return 这一次是否刚刚入账（调用方据此打日志 / 重算冻结）
     */
    static synchronized boolean tick(Context c) {
        Calendar now = GuardClock.now(c);
        if (!atDuePoint(now.get(Calendar.HOUR_OF_DAY))) return false;
        if (armed(c)) return false;
        long due = dueAt(now);
        int pending = StudyStore.pendingToday(c);
        long last = StudyStore.lastHitToday(c);
        if (!missed(pending, last, due)) return false;

        PenaltyStore.add(c, EXTEND_MS, now.getTimeInMillis(), PenaltyStore.SRC_CHECKIN);
        boolean saved = sp(c).edit()
                .putString(K_DATE, StudyStore.today())
                .putLong(K_AT, now.getTimeInMillis())
                .commit();
        if (!saved) throw new IllegalStateException(T.s("打卡罚时未能保存"));
        android.util.Log.i(Guard.TAG, "checkin penalty armed: pending=" + pending
                + " lastHit=" + last + " +" + (EXTEND_MS / 60000L) + "min");
        return true;
    }

    /* ---------- 文案 ---------- */

    /** 设置页 / 打卡页那一行的说明：今天这笔到底在不在。 */
    static String describe(Context c) {
        Calendar now = GuardClock.now(c);
        if (armed(c)) {
            return T.s("今天 20:00 打卡没打完，各应用冻结已各延长 ")
                    + (EXTEND_MS / 60000L) + T.s(" 分钟（补打也不撤销）");
        }
        if (!atDuePoint(now.get(Calendar.HOUR_OF_DAY))) {
            return T.s("20:00 前打完卡就不延长；没打完，各应用本次冻结多 ")
                    + (EXTEND_MS / 60000L) + T.s(" 分钟");
        }
        return T.s("今天按时打完了卡，不延长");
    }
}
