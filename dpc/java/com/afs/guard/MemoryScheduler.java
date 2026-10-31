package com.afs.guard;

/**
 * FSRS-6 mathematics adapted from Open Spaced Repetition/java-fsrs Scheduler.java,
 * revision c561ee6c56239b621a0a45b16a6c2e688441eae5 (MIT, copyright 2025 OSPR).
 * See res/raw/fsrs_license.txt and docs/MEMORY.md. Java 8 / Android 24 port:
 * milliseconds instead of java.time; default 90% retention (optionally tuned per deck),
 * 1m/10m learning, 10m relearning, deterministic intervals (fuzz disabled), no optimizer.
 */
public final class MemoryScheduler {
    private static final double[] W = {
        .2172, 1.1771, 3.2602, 16.1507, 7.0114, .57, 2.0966, .0069,
        1.5261, .112, 1.0178, 1.849, .1133, .3127, 2.2934, .2191,
        3.0004, .7536, .3332, .1437, .2
    };
    public static final long DAY = 86400000L;
    public static final int LEARNING = 0, REVIEW = 1, RELEARNING = 2;
    /** Upstream default target retention; the weights below were fit at 90%. */
    public static final double DEFAULT_RETENTION = 0.90;
    public static final int DEFAULT_MAX_INTERVAL_DAYS = 36500;
    /** Defensive clamp in the scheduler: bad persisted values must not block grading. */
    private static final double MIN_RETENTION = 0.70, MAX_RETENTION = 0.97;
    private static final int MAX_INTERVAL_CAP = 36500;
    public static final class State {
        public double stability, difficulty;
        public int state, step, reps, lapses;
        public long last, due;
    }
    private static double difficulty(double d) { return Math.max(1, Math.min(10, d)); }
    private static double initialDifficulty(int r) { return difficulty(W[4] - Math.exp(W[5] * (r - 1)) + 1); }

    public static State next(State old, int rating, long now) {
        return next(old, rating, now, DEFAULT_RETENTION, DEFAULT_MAX_INTERVAL_DAYS);
    }
    public static State next(State old, int rating, long now, double retention, int maxIntervalDays) {
        if (rating < 1 || rating > 4) throw new IllegalArgumentException("rating");
        State n = new State();
        n.state = old.state; n.step = old.step; n.reps = old.reps + 1;
        n.lapses = old.lapses + (rating == 1 ? 1 : 0);
        if (old.reps == 0) {
            n.stability = W[rating - 1];
            n.difficulty = initialDifficulty(rating);
        } else {
            double s = Math.max(.001, old.stability), d = old.difficulty;
            long days = Math.max(0, (now - old.last) / DAY);
            if (days < 1) {
                double increase = Math.exp(W[17] * (rating - 3 + W[18])) * Math.pow(s, -W[19]);
                if (rating >= 3) increase = Math.max(1, increase);
                n.stability = s * increase;
            } else {
                double decay = -W[20], factor = Math.pow(.9, 1 / decay) - 1;
                double r = Math.pow(1 + factor * days / s, decay);
                if (rating == 1) {
                    n.stability = Math.min(W[11] * Math.pow(d, -W[12])
                            * (Math.pow(s + 1, W[13]) - 1) * Math.exp((1 - r) * W[14]),
                            s / Math.exp(W[17] * W[18]));
                } else {
                    n.stability = s * (1 + Math.exp(W[8]) * (11 - d) * Math.pow(s, -W[9])
                            * (Math.exp((1 - r) * W[10]) - 1)
                            * (rating == 2 ? W[15] : 1) * (rating == 4 ? W[16] : 1));
                }
            }
            n.stability = Math.max(.001, n.stability);
            n.difficulty = difficulty(W[7] * initialDifficulty(4)
                    + (1 - W[7]) * (d + (10 - d) * (-W[6] * (rating - 3)) / 9));
        }
        long interval;
        if (old.state == REVIEW) {
            if (rating == 1) { n.state = RELEARNING; n.step = 0; interval = 600000; }
            else interval = intervalDays(n.stability, retention, maxIntervalDays);
        } else if (old.state == RELEARNING) {
            if (rating == 1) { n.step = 0; interval = 600000; }
            else if (rating == 2) interval = 900000;
            else { n.state = REVIEW; n.step = 0; interval = intervalDays(n.stability, retention, maxIntervalDays); }
        } else {
            if (rating == 1) { n.step = 0; interval = 60000; }
            else if (rating == 2) interval = old.step == 0 ? 330000 : 600000;
            else if (rating == 4 || old.step >= 1) {
                n.state = REVIEW; n.step = 0; interval = intervalDays(n.stability, retention, maxIntervalDays);
            } else { n.step = 1; interval = 600000; }
        }
        n.last = now; n.due = now + interval;
        return n;
    }
    /**
     * Day-level interval derived from FSRS-6 retrievability: t = S / FACTOR * (R^(1/DECAY) - 1).
     * At the default 90% retention this is exactly S, so legacy scheduling stays bit-identical.
     */
    public static long intervalDays(double stability, double retention, int maxIntervalDays) {
        double target = retention >= MIN_RETENTION && retention <= MAX_RETENTION ? retention : DEFAULT_RETENTION;
        int cap = maxIntervalDays < 1 ? DEFAULT_MAX_INTERVAL_DAYS : Math.min(maxIntervalDays, MAX_INTERVAL_CAP);
        double days;
        if (target == DEFAULT_RETENTION) days = stability;
        else {
            double decay = -W[20], factor = Math.pow(.9, 1 / decay) - 1;
            days = stability / factor * (Math.pow(target, 1 / decay) - 1);
        }
        return Math.max(1, Math.min(cap, Math.round(days))) * DAY;
    }
}
