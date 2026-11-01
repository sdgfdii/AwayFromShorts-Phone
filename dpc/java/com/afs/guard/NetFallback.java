package com.afs.guard;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 断网的第二条路：VPN 通道被别的 VPN（代理、加速器）占着时，改用「冻结这些应用」顶上。
 *
 * 背景（不是 bug，是 Android 的硬限制）：系统同一时间只允许一个 VPN，断网的本质是一条
 * 只圈住目标应用的 VpnService 隧道；用户开着 FlClash 之类代理时，隧道根本建不起来，
 * 断网就形同不存在。
 *
 * 兜底语义：既然拿不到网络层，就退到应用层 —— 把断网名单里的应用直接冻结
 * （Device Owner 的 setPackagesSuspended）。对短视频这类应用，打不开和没网是同一件事；
 * 代价是它们连离线也用不了，所以名单里别放必须用的应用（界面写清楚）。
 *
 * 只认「确认被占用」这一种原因（why=vpn 且已经失败过、过了宽限期），
 * 不拿「没授权」或「正在建隧道」当理由去冻结应用。
 */
final class NetFallback {

    private static final long CONFLICT_GRACE_MS = 25_000L;

    private NetFallback() {
    }

    /**
     * 纯逻辑：现在该不该走兜底冻结。
     *
     * @param enabled       断网开关 + 名单都有效
     * @param insideWindow  在断网调整时段内（该放开）
     * @param tunnelRunning 隧道真的在跑（有它就不用兜底）
     * @param vpnConflict   确认是「别的 VPN 占着」
     * @param mode          {@link NetBlockStore#FALLBACK_FREEZE} / {@link NetBlockStore#FALLBACK_NOTIFY}
     */
    static boolean shouldFreeze(boolean enabled, boolean insideWindow, boolean tunnelRunning,
                                boolean vpnConflict, int mode) {
        return enabled && !insideWindow && !tunnelRunning && vpnConflict
                && mode == NetBlockStore.FALLBACK_FREEZE;
    }

    /**
     * 冲突是否已经确认：失败原因记录过了宽限期，且最近一次拉起尝试就是那次失败。
     *
     * 后半句很重要：进程刚重启时 why 是上一个进程留下的（旧的），而 triedAt 是刚刚 ——
     * 这时候隧道可能马上就建起来，不能抢先去冻结应用。
     */
    static boolean conflictConfirmed(String why, long whyAt, long triedAt, long now) {
        if (!NetBlockService.WHY_OTHER_VPN.equals(why)) return false;
        if (whyAt <= 0L || triedAt <= 0L) return false;
        if (now - whyAt < CONFLICT_GRACE_MS) return false;
        return triedAt <= whyAt + 2_000L;
    }

    private static boolean blockedByOtherVpn(Context c) {
        return conflictConfirmed(NetBlockStore.why(c), NetBlockStore.whyAt(c),
                NetBlockStore.triedAt(c), System.currentTimeMillis());
    }

    /** 每次隧道状态可能变化时调一次；幂等。 */
    static void reconcile(Context c) {
        try {
            boolean desired = shouldFreeze(NetBlockStore.enabled(c), NetWindow.inside(c),
                    NetBlockStore.running(c), blockedByOtherVpn(c), NetBlockStore.fallbackMode(c));
            Set<String> current = NetBlockStore.fallbackPackages(c);
            if (!desired) {
                if (!current.isEmpty()) release(c, current);
                return;
            }
            Set<String> target = desiredPackages(c);
            if (target.equals(current)) return;
            if (!Guard.isOwner(c)) {
                release(c, current);
                return;
            }
            Set<String> drop = new LinkedHashSet<String>(current);
            drop.removeAll(target);
            if (!drop.isEmpty()) suspend(c, drop, false);
            Set<String> add = new LinkedHashSet<String>(target);
            add.removeAll(current);
            Set<String> applied = new LinkedHashSet<String>(current);
            applied.retainAll(target);
            if (!add.isEmpty()) {
                Set<String> failed = suspend(c, add, true);
                for (String pkg : add) if (!failed.contains(pkg)) applied.add(pkg);
            }
            if (!applied.equals(current)) NetBlockStore.setFallbackPackages(c, applied);
        } catch (Throwable error) {
            Log.w(Guard.TAG, "net fallback failed: " + error.getClass().getSimpleName());
        }
    }

    /** 真正要冻结的名单：去掉自己、关键应用（豁免冻结）和没装的包。 */
    private static Set<String> desiredPackages(Context c) {
        Set<String> out = new LinkedHashSet<String>();
        Set<String> keys = RuleStore.keyApps(c);
        String own = c.getPackageName();
        PackageManager pm = c.getPackageManager();
        for (String pkg : NetBlockStore.packages(c)) {
            if (pkg == null || pkg.isEmpty() || pkg.equals(own) || keys.contains(pkg)) continue;
            try {
                pm.getApplicationInfo(pkg, 0);
                out.add(pkg);
            } catch (PackageManager.NameNotFoundException missing) {
                // 没装就不管
            }
        }
        return out;
    }

    private static void release(Context c, Set<String> packages) {
        NetBlockStore.setFallbackPackages(c, new HashSet<String>());
        if (!packages.isEmpty() && Guard.isOwner(c)) suspend(c, packages, false);
        // 放掉以后让正常冻结时间表重判一次：这些应用本来可能就该被冻结。
        Guard.applyAsync(c);
    }

    /** 返回冻结失败（或异常）的包名集合；空集表示成功。 */
    private static Set<String> suspend(Context c, Set<String> packages, boolean value) {
        Set<String> failed = new HashSet<String>();
        android.app.admin.DevicePolicyManager d = Guard.dpm(c);
        if (d == null) return failed;
        String[] array = packages.toArray(new String[0]);
        try {
            String[] failedArray = Guard.setSuspended(d, Guard.admin(c), array, value);
            if (failedArray != null) {
                for (String pkg : failedArray) if (pkg != null) failed.add(pkg);
            }
            Log.i(Guard.TAG, "net fallback " + (value ? "freeze " : "release ") + array.length + " apps");
        } catch (RuntimeException error) {
            Log.w(Guard.TAG, "net fallback suspend failed: " + error.getClass().getSimpleName());
            for (String pkg : array) failed.add(pkg);
        }
        return failed;
    }
}
