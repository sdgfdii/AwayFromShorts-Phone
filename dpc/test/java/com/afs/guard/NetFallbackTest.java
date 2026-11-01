package com.afs.guard;

/**
 * VPN 被代理占着时的兜底冻结：判定逻辑必须钉死。
 *
 * 这层是「断网」的退路：系统只允许一个 VPN，代理占着时隧道建不起来，
 * 只能退到应用冻结。判错的方向有两种：不该冻的时候冻了（应用突然打不开），
 * 该冻的时候没冻（断网形同不存在）。两边都要防。
 */
public final class NetFallbackTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": " + actual + " != " + expected);
        }
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static final int FREEZE = NetBlockStore.FALLBACK_FREEZE;
    private static final int NOTIFY = NetBlockStore.FALLBACK_NOTIFY;

    public static void main(String[] args) {
        /* ---------- shouldFreeze：只有「该断网 + 隧道不在 + 确认被占 + 选了冻结」才冻 ---------- */
        ok(NetFallback.shouldFreeze(true, false, false, true, FREEZE), "全部条件满足才冻结");
        ok(!NetFallback.shouldFreeze(false, false, false, true, FREEZE), "断网没开不冻结");
        ok(!NetFallback.shouldFreeze(true, true, false, true, FREEZE), "调整时段内放开，不冻结");
        ok(!NetFallback.shouldFreeze(true, false, true, true, FREEZE), "隧道在跑就不需要兜底");
        ok(!NetFallback.shouldFreeze(true, false, false, false, FREEZE), "没确认被占用不冻结");
        ok(!NetFallback.shouldFreeze(true, false, false, true, NOTIFY), "选了只提示就不冻结");

        /* ---------- conflictConfirmed：宽限期 + 失败时间对得上 ---------- */
        long now = 1_800_000_000_000L;
        String vpn = NetBlockService.WHY_OTHER_VPN;
        ok(NetFallback.conflictConfirmed(vpn, now - 60_000L, now - 60_000L, now), "失败 1 分钟后确认");
        ok(!NetFallback.conflictConfirmed(vpn, now - 1_000L, now - 1_000L, now), "刚失败还在宽限期里不算");
        ok(!NetFallback.conflictConfirmed(vpn, now - 60_000L, now - 5_000L, now),
                "triedAt 比 whyAt 晚很多 = 进程刚重启的旧 why，不算");
        ok(!NetFallback.conflictConfirmed("", now - 60_000L, now - 60_000L, now), "没有失败原因不算");
        ok(!NetFallback.conflictConfirmed(NetBlockService.WHY_PERMISSION, now - 60_000L, now - 60_000L, now),
                "没授权走另一条路径，不当成被占用");
        ok(!NetFallback.conflictConfirmed(vpn, 0L, now - 60_000L, now), "没有失败时间不算");
        ok(!NetFallback.conflictConfirmed(vpn, now - 60_000L, 0L, now), "没有尝试时间不算");
        ok(NetFallback.conflictConfirmed(vpn, now - 25_000L - 1L, now - 25_000L - 1L, now), "刚好过宽限期就算");
        ok(!NetFallback.conflictConfirmed(vpn, now - 25_000L + 1L, now - 25_000L + 1L, now), "差 1ms 不够宽限期");

        System.out.println("Net fallback: " + checks + " checks passed");
    }
}
