package com.afs.guard;

/** 钥匙闸门的纯判定：钥匙应用自己、放行期内、没开启都不该被挡。 */
public final class KeyGateTest {

    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    public static void main(String[] args) {
        // 开启、非钥匙应用、没达标 → 挡
        eq(true, KeyGate.blocksFor(true, false, false, false), "blocked when not satisfied");
        // 达标 → 放行
        eq(false, KeyGate.blocksFor(true, false, true, false), "satisfied passes");
        // 钥匙应用自己不挡
        eq(false, KeyGate.blocksFor(true, true, false, false), "key app itself passes");
        // 解锁码放行期内不挡
        eq(false, KeyGate.blocksFor(true, false, false, true), "pass window opens");
        // 没开启不挡
        eq(false, KeyGate.blocksFor(false, false, false, false), "disabled passes");
        // 没开启时即使没达标也放行
        eq(false, KeyGate.blocksFor(false, false, false, true), "disabled ignores everything");

        System.out.println("Key gate: " + checks + " checks passed");
    }
}
