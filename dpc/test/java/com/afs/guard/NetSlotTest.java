package com.afs.guard;

/**
 * 「不抢代理」的判定：这层错了的代价很直接 —— 要么把用户的代理顶掉，要么断网永远不接管。
 */
public final class NetSlotTest {
    private static int checks;

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        /* ---------- foreignVpn：有 VPN 网络 + 我们的隧道没跑 → 是别人的 ---------- */
        ok(VpnSlot.foreignVpn(true, false), "有 VPN 网络、我们的隧道没跑：别人的");
        ok(!VpnSlot.foreignVpn(true, true), "有 VPN 网络、正是我们自己的隧道：不算别人的");
        ok(!VpnSlot.foreignVpn(false, false), "没有 VPN 网络：没人占");
        ok(!VpnSlot.foreignVpn(false, true), "我们的隧道在跑（但网络还没出来）：不算别人的");

        /* ---------- standDown：想断网 + 通道被占 → 让路 ---------- */
        ok(NetBlockService.standDown(true, true), "想断网且代理占着：让路");
        ok(!NetBlockService.standDown(true, false), "通道空着：不让路，去建隧道");
        ok(!NetBlockService.standDown(false, true), "调整时段内本来就放开：不算冲突");
        ok(!NetBlockService.standDown(false, false), "都不用断：不让路");

        /* ---------- shouldStartTunnel ---------- */
        ok(NetBlockService.shouldStartTunnel(true, false, false, true), "该断、没被占、没在跑、该重试 → 建");
        ok(!NetBlockService.shouldStartTunnel(true, true, false, true), "代理占着绝不建（会顶掉代理）");
        ok(!NetBlockService.shouldStartTunnel(true, false, true, true), "已经在跑不重复建");
        ok(!NetBlockService.shouldStartTunnel(true, false, false, false), "退避没到不建");
        ok(!NetBlockService.shouldStartTunnel(false, false, false, true), "不用断网时不建");

        /* ---------- retryDue：边界 ---------- */
        ok(NetBlockService.retryDue(60_001L, 60_000L), "刚过退避：可以重试");
        ok(NetBlockService.retryDue(60_000L, 60_000L), "正好到退避：可以重试");
        ok(!NetBlockService.retryDue(59_999L, 60_000L), "差 1ms：还不能");
        ok(NetBlockService.retryDue(Long.MAX_VALUE, 60_000L), "从未试过（triedAt=0）：可以重试");

        /* ---------- retryGap：确认被代理占着才退 10 分钟，否则 60 秒 ---------- */
        ok(NetBlockService.retryGap(true) == 10L * 60L * 1000L, "确认被占：退避 10 分钟");
        ok(NetBlockService.retryGap(false) == 60_000L, "其它情况：常规 60 秒（代理一关就能接管）");

        System.out.println("Net slot: " + checks + " checks passed");
    }
}
