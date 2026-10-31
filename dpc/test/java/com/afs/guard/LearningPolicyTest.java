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
        check(!LearningPolicy.holds(true, true, 10, 9, 19));
        check(LearningPolicy.holds(true, true, 10, 9, 20));
        check(!LearningPolicy.holds(true, true, 10, 10, 20));
        check(!LearningPolicy.holds(true, false, 10, 0, 20));
        check(!LearningPolicy.holds(false, true, 10, 0, 20));
        check(!LearningPolicy.holds(true, true, 0, 0, 20));
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
        System.out.println("Learning policy: " + checks + " checks passed");
    }
}
