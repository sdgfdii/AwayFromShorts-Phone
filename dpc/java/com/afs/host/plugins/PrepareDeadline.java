package com.afs.host.plugins;

/**
 * Pure prepare deadline state. The router's fail/timeout/ready paths race on
 * this object so a late callback cannot revive a failed or timed-out page.
 */
public final class PrepareDeadline {
    public static final int PENDING = 0;
    public static final int READY = 1;
    public static final int FAILED = 2;
    public static final int TIMED_OUT = 3;

    private final long deadlineElapsedMs;
    private int state = PENDING;

    public PrepareDeadline(long nowElapsedMs, long budgetMs) {
        deadlineElapsedMs = nowElapsedMs + Math.max(0L, budgetMs);
    }

    public synchronized boolean ready() {
        if (state != PENDING) return false;
        state = READY;
        return true;
    }

    public synchronized boolean fail() {
        if (state != PENDING) return false;
        state = FAILED;
        return true;
    }

    public synchronized boolean timeout(long nowElapsedMs) {
        if (state != PENDING || nowElapsedMs < deadlineElapsedMs) return false;
        state = TIMED_OUT;
        return true;
    }

    public synchronized int state() { return state; }

    public synchronized boolean settled() { return state != PENDING; }
}
