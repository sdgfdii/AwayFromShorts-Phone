package com.afs.host.plugins;

/** Offline prepare failure/timeout race checks. */
public final class PluginPrepareTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        PrepareDeadline before = new PrepareDeadline(1_000L, 2_000L);
        ok(!before.timeout(2_999L), "not timed out before 2s");
        ok(before.ready(), "ready before deadline");
        ok(!before.fail(), "late failure ignored after ready");
        ok(!before.timeout(10_000L), "late timeout ignored after ready");

        PrepareDeadline timeout = new PrepareDeadline(1_000L, 2_000L);
        ok(timeout.timeout(3_000L), "timeout at exactly 2s");
        eq(PrepareDeadline.TIMED_OUT, timeout.state(), "timed-out state");
        ok(!timeout.ready(), "late prepare cannot revive timeout");
        ok(!timeout.fail(), "timeout is final");

        PrepareDeadline failed = new PrepareDeadline(10L, 2_000L);
        ok(failed.fail(), "explicit initialization failure");
        eq(PrepareDeadline.FAILED, failed.state(), "failed state");
        ok(!failed.ready(), "late ready cannot revive failure");
        ok(!failed.timeout(99_999L), "timeout cannot overwrite failure");

        PrepareDeadline ready = new PrepareDeadline(5L, 2_000L);
        ok(ready.ready(), "first ready wins");
        ok(!ready.ready(), "second ready is ignored");
        ok(ready.settled(), "settled after ready");
        eq(PrepareDeadline.READY, ready.state(), "ready state");

        System.out.println("Plugin prepare: " + checks + " checks passed");
    }
}
