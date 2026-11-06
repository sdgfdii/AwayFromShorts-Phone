package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;

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
 * <p>罚时接在应用当天冻结结束的真实放行点；如果结束点已经跨到第二天，则不安排，
 * 也不从认领时刻直接加在当前冻结中间。
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

    /** 只延长当天结束的冻结；跨午夜的接续点不参与打卡罚时。 */
    static boolean withinToday(Rule rule, long release, long now) {
        if (rule == null || release <= 0) return false;
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
        if (armed(c)) { backfill(c, now); return false; }
        long due = dueAt(now);
        int pending = pendingCards(c);
        long last = StudyStore.lastHitToday(c);
        if (!missed(pending, last, due)) return false;

        PenaltyStore.add(c, EXTEND_MS, now.getTimeInMillis(), PenaltyStore.SRC_CHECKIN);
        StudyGate.invalidate(c);
        boolean saved = sp(c).edit()
                .putString(K_DATE, StudyStore.today())
                .putLong(K_AT, now.getTimeInMillis())
                .commit();
        if (!saved) throw new IllegalStateException(T.s("打卡罚时未能保存"));
        android.util.Log.i(Guard.TAG, "checkin penalty armed: pending=" + pending
                + " lastHit=" + last + " +" + (EXTEND_MS / 60000L) + "min");
        return true;
    }

    /** 20:00 检查的是打卡页全部必做卡，不只目标卡。 */
    private static int pendingCards(Context c) {
        int pending = StudyStore.pendingToday(c);
        LearningLink.Progress memory = LearningLink.memory(c);
        if (memory.selected && memory.target > 0 && !memory.complete()) pending++;
        LearningLink.Progress quiz = LearningLink.quiz(c);
        if (quiz.selected && quiz.target > 0 && !quiz.complete()) pending++;
        pending += CardStore.summariesPending(c, GuardClock.now(c).getTimeInMillis()).size();
        return pending;
    }

    private static final String K_BACKFILL = "backfill_date";

    /**
     * 当天这笔罚时已经入账过，但口径升级成了「所有被冻结的应用」。
     *
     * 装上这一版之后的第一拍把其余冻结应用补齐一次，当天就能生效；
     * 用 backfill_date 记一笔，避免每个节拍都去扫一遍规则表。
     */
    private static void backfill(Context c, Calendar now) {
        String today = StudyStore.today();
        SharedPreferences p = sp(c);
        if (today.equals(p.getString(K_BACKFILL, ""))) return;
        try {
            PenaltyStore.ensureCheckin(c, EXTEND_MS, now.getTimeInMillis());
        } catch (RuntimeException error) {
            android.util.Log.w(Guard.TAG, "checkin penalty backfill failed: " + error.getClass().getSimpleName());
            return;   // 不写标记，下一拍再试
        }
        p.edit().putString(K_BACKFILL, today).apply();
    }

    /* ---------- 文案 ---------- */

    /** 设置页 / 打卡页那一行的说明：今天这笔到底在不在。 */
    static String describe(Context c) {
        Calendar now = GuardClock.now(c);
        String scheduled = linkageStatus(c, now);
        if (!scheduled.isEmpty()) return scheduled;
        if (armed(c)) return T.s("20:00 打卡罚时已登记，但当前没有可接续的冻结时段");
        if (!atDuePoint(now.get(Calendar.HOUR_OF_DAY))) {
            return T.s("20:00 前打完卡就不延长；没打完，所有被冻结的应用本次冻结多 ")
                    + (EXTEND_MS / 60000L) + T.s(" 分钟");
        }
        return T.s("今天按时打完了卡，不延长");
    }

    /** 给学习联动摘要一行显示的短状态；没有待执行打卡罚时时返回空串。 */
    static String linkageStatus(Context c, Calendar now) {
        PenaltyStore.CheckinSummary s = PenaltyStore.checkinSummary(c, now.getTimeInMillis());
        if (s.count == 0) return "";
        String when = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(s.first));
        String tail = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(s.last));
        if (s.active > 0) {
            return T.s("20:00 打卡罚时已生效：")
                    + s.count + T.s(" 个应用分别延长 ")
                    + (EXTEND_MS / 60000L) + T.s(" 分钟，最后一笔到 ") + tail;
        }
        return T.s("20:00 打卡罚时已安排：")
                + s.count + T.s(" 个应用从各自冻结结束点分别延长 ")
                + (EXTEND_MS / 60000L) + T.s(" 分钟，最早 ") + when + T.s(" 接续");
    }
}
