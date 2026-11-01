package com.afs.guard;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

/**
 * 看一眼「系统里现在有没有 VPN 隧道在跑」。
 *
 * 为什么必须看：Android 同一时间只允许一个 VPN。用户的代理（FlClash 等）占着通道时，
 * 我们再去 establish() 就是在跟代理抢；抢输了只是白试，抢赢了会把代理顶掉 —— 都不该发生。
 *
 * 两种证据，都只用「毫无疑问是别人的」那一种：
 * 1. ConnectivityManager 里能看到一条 VPN 网络，而我们自己的隧道没在跑 → 一定是别人的。
 * 2. 分应用代理（split tunneling）时第 1 条可能看不见，这种情况**不能瞎猜**：
 *    猜错会把自己的隧道判成别人的、导致断网永不接管。所以看不见就返回 false，
 *    让上层用「establish 返回 null」这个更硬的证据退避（见 NetBlockService）。
 *
 * 「我们自己的隧道活没活」用同进程静态标志判断，比 prefs 心跳可靠（Doze 下心跳会过期）。
 */
final class VpnSlot {

    private VpnSlot() {
    }

    /** 有没有别人的 VPN 正在占用系统 VPN 通道（能看见的那部分）。 */
    static boolean foreignVpnActive(Context c) {
        return foreignVpn(hasVpnNetwork(c, null), NetBlockService.tunnelAlive());
    }

    /** 纯逻辑：看得见 VPN 网络、而我们的隧道没跑 → 那条就是别人的。 */
    static boolean foreignVpn(boolean hasVpnNetwork, boolean ownTunnelAlive) {
        return hasVpnNetwork && !ownTunnelAlive;
    }

    /** 系统里现在有没有 VPN 网络（不管是谁的）；诊断用的说明会写进 note。 */
    private static boolean hasVpnNetwork(Context c, StringBuilder note) {
        if (Build.VERSION.SDK_INT < 29) return false;   // 老系统拿不到这些网络信息
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            boolean found = false;
            Network active = null;
            try {
                active = manager.getActiveNetwork();
            } catch (RuntimeException ignored) {
            }
            if (active != null && isVpn(manager, active)) found = true;
            Network[] networks = null;
            try {
                networks = manager.getAllNetworks();
            } catch (RuntimeException ignored) {
            }
            if (networks != null) {
                for (Network network : networks) {
                    if (active != null && active.equals(network)) continue;
                    if (isVpn(manager, network)) found = true;
                }
            }
            return found;
        } catch (Throwable error) {
            Log.w(Guard.TAG, "netblock: vpn slot probe failed: " + error.getClass().getSimpleName());
        }
        return false;
    }

    private static boolean isVpn(ConnectivityManager manager, Network network) {
        NetworkCapabilities caps = null;
        try {
            caps = manager.getNetworkCapabilities(network);
        } catch (RuntimeException ignored) {
        }
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }
}
