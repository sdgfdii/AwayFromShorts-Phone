package com.afs.guard;

/** 能力分级：等级只由两个开关决定，功能要求不随界面变化。 */
public final class CapabilityTest {

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
        // 等级
        eq(Capability.LIMITED, Capability.level(false, false), "nothing on");
        eq(Capability.ACCESSIBILITY, Capability.level(false, true), "a11y only");
        eq(Capability.OWNER, Capability.level(true, false), "owner without a11y");
        eq(Capability.OWNER, Capability.level(true, true), "owner with a11y");

        // 功能要求
        eq(Capability.OWNER, Capability.needFor(Capability.F_FREEZE), "freeze needs owner");
        eq(Capability.OWNER, Capability.needFor(Capability.F_LOCK), "lock needs owner");
        eq(Capability.ACCESSIBILITY, Capability.needFor(Capability.F_KEYWORD), "keyword needs a11y");
        eq(Capability.LIMITED, Capability.needFor(Capability.F_QUOTA), "quota needs usage stats only");
        eq(Capability.LIMITED, Capability.needFor(Capability.F_NET), "net needs nothing extra");
        eq(Capability.LIMITED, Capability.needFor("unknown"), "unknown feature degrades to limited");

        // 文案不空、等级名可读
        ok(!Capability.name(Capability.OWNER).isEmpty(), "owner name");
        ok(!Capability.name(Capability.ACCESSIBILITY).isEmpty(), "a11y name");
        ok(!Capability.name(Capability.LIMITED).isEmpty(), "limited name");
        ok(!Capability.name(Capability.NONE).isEmpty(), "none name");
        ok(!Capability.scope(Capability.OWNER).isEmpty(), "owner scope");
        ok(!Capability.scope(Capability.ACCESSIBILITY).isEmpty(), "a11y scope");
        ok(!Capability.scope(Capability.LIMITED).isEmpty(), "limited scope");

        System.out.println("Capability: " + checks + " checks passed");
    }
}
