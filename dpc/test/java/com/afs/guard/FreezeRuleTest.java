package com.afs.guard;

import java.util.Calendar;

public final class FreezeRuleTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static Calendar at(int day, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.OCTOBER, 19 + day, minute / 60, minute % 60, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }
    public static void main(String[] args) throws Exception {
        Rule f = new Rule(1, new int[][] {{1320, 120}}, false, true);
        check(f.inWindow(at(0, 1319)), "before start");
        check(!f.inWindow(at(0, 1320)), "start inclusive");
        check(!f.inWindow(at(1, 119)), "cross midnight from Monday");
        check(f.inWindow(at(1, 120)), "end exclusive");
        check(f.inWindow(at(1, 1320)), "unselected Tuesday");
        check(f.nextFlip(at(0, 1319)) == at(0, 1320).getTimeInMillis(), "next boundary");
        check(f.withGate(true).freeze && f.withDays(127).freeze, "copy preserves mode");
        check(f.addWindow(300, 400).withWindow(1, 600, 700).removeWindow(0).freeze, "window operations preserve mode");
        check(!f.same(new Rule(1, f.windows(), false)), "different modes differ");
        check(!f.groupKey().equals(new Rule(1, f.windows(), false).groupKey()), "group mode");
        check(new Rule(0, new int[][] {{0, 0}}, false, true).inWindow(at(0, 5)), "no days allows");
        check(new Rule(127, new int[0][2], false, true).inWindow(at(0, 5)), "empty allows");
        check(!new Rule(127, new int[][] {{0, 0}}, false, true).inWindow(at(0, 5)), "all day freezes");
        Rule multi = new Rule(127, new int[][] {{540, 720}, {780, 1080}}, false, true);
        check(multi.inWindow(at(0, 750)) && !multi.inWindow(at(0, 800)), "multiple windows");
        int[][][] cases = { {{1260, 1380}}, {{1320, 120}}, {{540, 720}, {660, 840}}, {}, {{0, 0}} };
        for (int[][] wins : cases) {
            Rule old = new Rule(127, wins, false);
            Rule migrated = old.freezeEditorRule();
            for (int d = 0; d < 7; d++) for (int m = 0; m < 1440; m++)
                check(old.inWindow(at(d, m)) == migrated.inWindow(at(d, m)), "legacy daily migration");
        }
        check(new Rule(1, new int[][] {{1320, 120}}).freezeEditorRule() == null, "legacy weekly kept intact");
        java.lang.reflect.Method parse = RuleStore.class.getDeclaredMethod("parseRule", org.json.JSONObject.class);
        parse.setAccessible(true);
        Rule stored = (Rule) parse.invoke(null, new org.json.JSONObject("{\"d\":1,\"w\":[[1320,120]],\"g\":false,\"freeze\":true}"));
        check(stored.same(f), "stored mode restores");
        Rule empty = (Rule) parse.invoke(null, new org.json.JSONObject("{\"d\":127,\"w\":[],\"freeze\":true}"));
        check(empty.freeze && empty.windowCount() == 0 && empty.inWindow(at(0, 10)), "empty stored schedule");
        Rule legacy = (Rule) parse.invoke(null, new org.json.JSONObject("{\"d\":127,\"w\":[[1260,1380]],\"g\":true}"));
        check(!legacy.freeze && legacy.inWindow(at(0, 1300)), "unversioned storage remains allow mode");
        System.out.println("Freeze rules: boundaries, weekly rollover, multiple windows, copies and 50400 migration minutes PASS");
    }
}
