package com.afs.guard;

import java.util.Calendar;

/** Time and progress rules independent of Android UI and storage. */
final class LearningPolicy {
    private LearningPolicy() { }
    static boolean complete(boolean selected, int target, int done) {
        return selected && target >= 0 && done >= target;
    }
    static boolean holds(boolean available, boolean selected, int target, int done) {
        return available && selected && target > 0 && !complete(true, target, done);
    }
    /** Daily linkage starts at 20:00; normalize legacy midnight anchors without losing overdue tasks. */
    static long dailyOrigin(Calendar now, long previous, boolean unfinished) {
        if (!unfinished) return 0L;
        Calendar due = (Calendar) now.clone();
        due.set(Calendar.HOUR_OF_DAY, CheckinPenalty.DUE_HOUR);
        due.set(Calendar.MINUTE, 0); due.set(Calendar.SECOND, 0); due.set(Calendar.MILLISECOND, 0);
        if (previous > 0L && previous <= now.getTimeInMillis()) {
            Calendar old = (Calendar) due.clone();
            old.setTimeInMillis(previous);
            old.set(Calendar.HOUR_OF_DAY, CheckinPenalty.DUE_HOUR);
            old.set(Calendar.MINUTE, 0); old.set(Calendar.SECOND, 0); old.set(Calendar.MILLISECOND, 0);
            if (old.getTimeInMillis() < due.getTimeInMillis()) due = old;
        }
        return due.getTimeInMillis() <= now.getTimeInMillis() ? due.getTimeInMillis() : 0L;
    }
    /** An overnight release never receives a learning hold, including after a restart or midnight. */
    static long learningRelease(Rule rule, long origin) {
        if (rule == null || !rule.gate || origin <= 0L) return 0L;
        long release = PenaltyTiming.release(rule, origin);
        return CheckinPenalty.withinToday(rule, release, origin) ? release : 0L;
    }
    static String weekKey(Calendar time) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        format.setTimeZone(time.getTimeZone());
        return format.format(weekStart(time, 0).getTime());
    }
    static Calendar weekStart(Calendar time, int previousWeeks) {
        Calendar start = (Calendar) time.clone();
        start.add(Calendar.DAY_OF_MONTH, -((start.get(Calendar.DAY_OF_WEEK) + 5) % 7) - previousWeeks * 7);
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        return start;
    }
}
