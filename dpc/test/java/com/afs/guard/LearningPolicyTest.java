package com.afs.guard;

import java.util.Calendar;
import java.util.GregorianCalendar;

public final class LearningPolicyTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Learning policy check " + checks);
    }
    public static void main(String[] args) {
        check(LearningPolicy.holds(true, true, 10, 9));
        check(!LearningPolicy.holds(true, true, 10, 10));
        check(!LearningPolicy.holds(true, false, 10, 0));
        check(!LearningPolicy.holds(false, true, 10, 0));
        check(!LearningPolicy.holds(true, true, 0, 0));
        check(LearningPolicy.complete(true, 0, 0));
        check(!LearningPolicy.complete(false, 0, 0));
        Calendar sun = new GregorianCalendar(2026, Calendar.NOVEMBER, 1, 0, 0);
        sun.set(Calendar.HOUR_OF_DAY, 19); sun.set(Calendar.MINUTE, 59);
        sun.add(Calendar.MINUTE, 1);
        sun.set(Calendar.HOUR_OF_DAY, 23); sun.set(Calendar.MINUTE, 59);
        check("2026-10-26".equals(LearningPolicy.weekKey(sun)));
        sun.add(Calendar.MINUTE, 1);
        Calendar week = LearningPolicy.weekStart(sun, 0);
        check(week.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY && week.get(Calendar.DAY_OF_MONTH) == 2);
        Calendar previous = LearningPolicy.weekStart(sun, 1);
        check(previous.get(Calendar.MONTH) == Calendar.OCTOBER && previous.get(Calendar.DAY_OF_MONTH) == 26);
        check(previous.get(Calendar.HOUR_OF_DAY) == 0 && previous.get(Calendar.MINUTE) == 0);
        Calendar nextSunday = (Calendar) sun.clone(); nextSunday.add(Calendar.DAY_OF_MONTH, 6);
        nextSunday.set(Calendar.HOUR_OF_DAY, 20);
        check("2026-11-02".equals(LearningPolicy.weekKey(nextSunday)));
        long twenty = nextSunday.getTimeInMillis();
        Calendar before = (Calendar) nextSunday.clone(); before.add(Calendar.MINUTE, -1);
        check(LearningPolicy.dailyOrigin(before, 0, true) == 0L);
        check(LearningPolicy.dailyOrigin(nextSunday, 0, true) == twenty);
        Calendar midnight = (Calendar) nextSunday.clone(); midnight.set(Calendar.HOUR_OF_DAY, 0);
        check(LearningPolicy.dailyOrigin(before, midnight.getTimeInMillis(), true) == 0L);
        check(LearningPolicy.dailyOrigin(nextSunday, midnight.getTimeInMillis(), true) == twenty);
        Calendar following = (Calendar) midnight.clone(); following.add(Calendar.DAY_OF_MONTH, 1); following.set(Calendar.HOUR_OF_DAY, 9);
        check(LearningPolicy.dailyOrigin(following, midnight.getTimeInMillis(), true) == twenty);
        check(LearningPolicy.dailyOrigin(following, twenty, false) == 0L);
        Rule evening = new Rule(127, new int[][]{{0,1320},{1380,0}}, true, true);
        Rule photos = new Rule(127, new int[][]{{0,360}}, true, true);
        Rule legacyPhotos = new Rule(127, new int[][]{{360,0}}, true, false);
        Rule overnightRule = new Rule(127, new int[][]{{1380,420}}, true, true);
        check(LearningPolicy.learningRelease(evening, twenty) == twenty + 2 * 60 * 60_000L);
        check(LearningPolicy.learningRelease(photos, twenty) == 0L);
        check(LearningPolicy.learningRelease(legacyPhotos, twenty) == 0L);
        check(LearningPolicy.learningRelease(overnightRule, twenty) == 0L);
        check(LearningPolicy.learningRelease(new Rule(127, new int[][]{{0,1320}},false,true), twenty) == 0L);
        check(LearningPolicy.learningRelease(null, twenty) == 0L);
        check(LearningPolicy.learningRelease(photos, 0L) == 0L);

        CardManagementActivity.LinkageSummary beforeDue =
                CardManagementActivity.linkageSummary("", 0L, twenty - 60_000L);
        check("当前无额外冻结".equals(beforeDue.reason) && beforeDue.elapsed.isEmpty());
        CardManagementActivity.LinkageSummary atDue =
                CardManagementActivity.linkageSummary("学习联动：今日打卡还有 1 个目标", twenty, twenty);
        check(atDue.reason.startsWith("学习联动") && atDue.elapsed.contains("0.0 分钟"));
        CardManagementActivity.LinkageSummary fiveMinutes =
                CardManagementActivity.linkageSummary(atDue.reason, twenty, twenty + 5 * 60_000L);
        check(fiveMinutes.elapsed.contains("5.0 分钟") && fiveMinutes.elapsed.contains("完成后解除"));
        CardManagementActivity.LinkageSummary completed =
                CardManagementActivity.linkageSummary("", 0L, twenty + 5 * 60_000L);
        check("当前无额外冻结".equals(completed.reason) && completed.elapsed.isEmpty());
        long summaryDue = twenty + 30 * 60_000L;
        CardManagementActivity.LinkageSummary summary =
                CardManagementActivity.linkageSummary("学习联动：每周总结尚未完成", summaryDue, summaryDue + 60_000L);
        check(summary.elapsed.contains("1.0 分钟"));
        CardManagementActivity.LinkageSummary overnight =
                CardManagementActivity.linkageSummary(atDue.reason, twenty, twenty + 14 * 60 * 60_000L + 10 * 60_000L);
        check(overnight.elapsed.contains("850.0 分钟"));
        String scheduledEnded = StudyGate.learningActivityText(
                "学习联动：今日打卡还有 1 个目标", true, false);
        check(scheduledEnded.contains("原冻结结束后继续冻结中"));
        String scheduledPending = StudyGate.learningActivityText(
                "学习联动：待完成目标", false, true);
        check(scheduledPending.contains("将在各应用自身冻结结束后接续"));
        System.out.println("Learning policy: " + checks + " checks passed");
    }
}
