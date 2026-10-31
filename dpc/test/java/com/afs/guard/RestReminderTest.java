package com.afs.guard;

/**
 * 定时提醒的判定。
 *
 * 钉四件事：间隔到没到、首次不弹、四种不该打扰的情况、以及改间隔后重新起算。
 * 时间全部用毫秒直算，不依赖任何 Android 类。
 */
public final class RestReminderTest {

    private static int checks;

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static long minutes(long value) {
        return value * 60000L;
    }

    public static void main(String[] args) {
        // ---- due：间隔判断 ----
        long start = 1000L * 1000L;
        ok(RestReminder.due(start + minutes(45), start, 45), "exactly 45 min is due");
        ok(RestReminder.due(start + minutes(46), start, 45), "46 min is due");
        ok(!RestReminder.due(start + minutes(44), start, 45), "44 min is not due");
        ok(!RestReminder.due(start, start, 45), "same instant is not due");
        ok(!RestReminder.due(start + minutes(1), 0L, 45), "never fired before: no pop on first tick");
        ok(!RestReminder.due(start + minutes(999), start, 0), "zero interval never fires");
        ok(!RestReminder.due(start + minutes(999), start, -5), "negative interval never fires");

        // 跨零点：只要距上次够久就提醒，不需要「今天第几个点」
        long beforeMidnight = 23L * 60L * 60000L;
        ok(RestReminder.due(beforeMidnight + minutes(70), beforeMidnight, 45), "cross midnight is due");

        // 改小时数后
        ok(RestReminder.due(start + minutes(16), start, 15), "switched to 15 min fires at 16");
        ok(!RestReminder.due(start + minutes(9), start, 15), "switched to 15 min waits");

        // ---- shouldFire：四种不打扰 ----
        long now = start + minutes(60);
        ok(RestReminder.shouldFire(true, true, true, false, false, now, start, 45),
                "normal case fires");
        ok(!RestReminder.shouldFire(false, true, true, false, false, now, start, 45),
                "off means off");
        ok(!RestReminder.shouldFire(true, false, true, false, false, now, start, 45),
                "outside window stays quiet");
        ok(!RestReminder.shouldFire(true, true, false, false, false, now, start, 45),
                "screen off stays quiet");
        ok(!RestReminder.shouldFire(true, true, true, true, false, now, start, 45),
                "while locked stays quiet");
        ok(!RestReminder.shouldFire(true, true, true, false, true, now, start, 45),
                "during unlock pass stays quiet");
        ok(!RestReminder.shouldFire(true, true, true, false, false, now, now, 45),
                "just fired, waits again");

        // 五个条件全满足才发
        int fired = 0;
        for (int mask = 0; mask < 32; mask++) {
            boolean on = (mask & 1) != 0;
            boolean win = (mask & 2) != 0;
            boolean screen = (mask & 4) != 0;
            boolean locked = (mask & 8) != 0;
            boolean pass = (mask & 16) != 0;
            boolean got = RestReminder.shouldFire(on, win, screen, locked, pass, now, start, 45);
            boolean want = on && win && screen && !locked && !pass;
            ok(got == want, "truth table mask=" + mask);
            if (got) fired++;
        }
        ok(fired == 1, "exactly one combination fires: " + fired);

        // ---- minutes 文案 ----
        ok(RestReminder.minutes(45).contains("45"), "minutes text carries the number");

        System.out.println("Rest reminder: " + checks + " checks passed");
    }
}
