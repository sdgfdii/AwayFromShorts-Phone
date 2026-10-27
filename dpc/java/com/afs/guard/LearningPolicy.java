package com.afs.guard;

import java.util.Calendar;

/** Time and progress rules independent of Android UI and storage. */
final class LearningPolicy {
    private LearningPolicy() { }
    static boolean complete(boolean selected, int target, int done) {
        return selected && target >= 0 && done >= target;
    }
    static boolean holds(boolean available, boolean selected, int target, int done, int hour) {
        return hour >= 20 && available && selected && target > 0 && !complete(true, target, done);
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
