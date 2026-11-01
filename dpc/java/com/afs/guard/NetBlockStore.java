package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 屏蔽联网的配置与运行状态。
 *
 * 只让选中的应用走黑洞隧道（{@code addAllowedApplication}），其余应用完全不受影响 ——
 * 这一条很关键：不能因为防沉迷把整台手机的网断掉，那样连电话短信之外的一切都废了。
 */
public final class NetBlockStore {

    private static final String SP = "afs_netblock";
    private static final String K_ON = "on";
    private static final String K_PKGS = "pkgs";
    private static final String K_RUNNING = "running";
    private static final String K_BEAT = "beat";
    private static final String K_TRIED = "tried";
    private static final String K_WHY = "why";
    private static final String K_WHY_AT = "why_at";
    private static final String K_FALLBACK = "fallback_mode";
    private static final String K_FALLBACK_PKGS = "fallback_pkgs";
    private static final String K_CONFLICT = "proxy_conflict";
    private static final String K_PROXY_MODE = "proxy_mode";

    /** 系统只允许一个 VPN：别的 VPN（代理）占着时，断网改用「冻结这些应用」顶上。 */
    static final int FALLBACK_FREEZE = 1;
    static final int FALLBACK_NOTIFY = 0;
    /**
     * 隧道心跳的有效期。服务被系统杀掉时 {@code onDestroy} 不一定跑，
     * {@code running} 会留在 true —— 光看这个标志会让界面说「断网中」而其实什么都没拦。
     * 服务在跑的时候每 20 秒盖一次时间戳，超过这个时间没更新就认为隧道没了。
     */
    private static final long BEAT_TTL = 90_000L;

    private NetBlockStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static boolean on(Context c) {
        return sp(c).getBoolean(K_ON, false);
    }

    public static void setOn(Context c, boolean value) {
        sp(c).edit().putBoolean(K_ON, value).apply();
    }

    public static Set<String> packages(Context c) {
        Set<String> out = new HashSet<String>();
        String raw = sp(c).getString(K_PKGS, "");
        if (raw == null) return out;
        for (String item : raw.split("\n")) {
            String pkg = item.trim();
            if (!pkg.isEmpty()) out.add(pkg);
        }
        return out;
    }

    public static void setPackages(Context c, Set<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : list) {
            if (pkg == null || pkg.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(pkg.trim());
        }
        sp(c).edit().putString(K_PKGS, sb.toString()).apply();
    }

    public static boolean enabled(Context c) {
        return on(c) && !packages(c).isEmpty();
    }

    public static boolean running(Context c) {
        if (!sp(c).getBoolean(K_RUNNING, false)) return false;
        long beat = sp(c).getLong(K_BEAT, 0L);
        return System.currentTimeMillis() - beat < BEAT_TTL;
    }

    static void setRunning(Context c, boolean value) {
        SharedPreferences.Editor edit = sp(c).edit().putBoolean(K_RUNNING, value);
        if (value) edit.putLong(K_BEAT, System.currentTimeMillis());
        edit.apply();
    }

    /** 服务还活着就隔一会儿盖个时间戳，用来判断上面的状态是不是真的。 */
    static void beat(Context c) {
        sp(c).edit().putLong(K_BEAT, System.currentTimeMillis()).apply();
    }

    /** 上一次申请拉起隧道的时间，用来给「连不上就重试」做退避。 */
    static long triedAt(Context c) {
        return sp(c).getLong(K_TRIED, 0L);
    }

    static void markTried(Context c) {
        sp(c).edit().putLong(K_TRIED, System.currentTimeMillis()).apply();
    }

    /** 清掉退避时间戳：条件变了（比如代理刚关掉）要立刻重试，不等那 60 秒。 */
    static void clearTried(Context c) {
        sp(c).edit().putLong(K_TRIED, 0L).apply();
    }

    /** 是不是「别的 VPN 正在占通道」的状态（上一次 reconcile 的判定）。 */
    static boolean proxyConflict(Context c) {
        return sp(c).getBoolean(K_CONFLICT, false);
    }

    static void setProxyConflict(Context c, boolean value) {
        sp(c).edit().putBoolean(K_CONFLICT, value).apply();
    }

    /**
     * 断网方式：false = 自动（本 App 用系统 VPN），true = 我常开代理（不占 VPN，由代理规则拦）。
     *
     * 默认自动：不开代理的人开箱即用。代理常开的人切一次，之后本 App 一步都不碰 VPN 通道，
     * 不可能出现「代理被顶掉」。
     */
    static boolean proxyMode(Context c) {
        return sp(c).getBoolean(K_PROXY_MODE, false);
    }

    static void setProxyMode(Context c, boolean value) {
        sp(c).edit().putBoolean(K_PROXY_MODE, value).apply();
    }

    /** 上一次建隧道失败的原因（空 = 没失败过），见 {@link NetBlockService} 的 WHY_*。 */
    static String why(Context c) {
        String value = sp(c).getString(K_WHY, "");
        return value == null ? "" : value;
    }

    static void setWhy(Context c, String value) {
        String next = value == null ? "" : value;
        SharedPreferences.Editor edit = sp(c).edit().putString(K_WHY, next);
        if (!next.isEmpty()) edit.putLong(K_WHY_AT, System.currentTimeMillis());
        edit.apply();
    }

    /** 失败原因是什么时候记下的：给出「先等一等再判定被占用」的时间依据。 */
    static long whyAt(Context c) {
        return sp(c).getLong(K_WHY_AT, 0L);
    }

    /**
     * 代理占着 VPN 时怎么办：0 = 只提示（默认），1 = 改用冻结。
     *
     * 默认只提示：冻结会让这些应用完全打不开，对「要看缓存视频」的场景是反效果，
     * 不能默认替用户做这么重的决定；想要兜底的用户在断网设置里手动打开。
     */
    static int fallbackMode(Context c) {
        int value = sp(c).getInt(K_FALLBACK, FALLBACK_NOTIFY);
        return value == FALLBACK_FREEZE ? FALLBACK_FREEZE : FALLBACK_NOTIFY;
    }

    static void setFallbackMode(Context c, int mode) {
        sp(c).edit().putInt(K_FALLBACK, mode == FALLBACK_NOTIFY ? FALLBACK_NOTIFY : FALLBACK_FREEZE).apply();
    }

    /** 兜底冻结里由「断网」自己挂起的应用：释放时只碰这一批，别动冻结时间表管的应用。 */
    static Set<String> fallbackPackages(Context c) {
        Set<String> out = new HashSet<String>();
        String raw = sp(c).getString(K_FALLBACK_PKGS, "");
        if (raw == null) return out;
        for (String item : raw.split("\n")) {
            String pkg = item.trim();
            if (!pkg.isEmpty()) out.add(pkg);
        }
        return out;
    }

    static void setFallbackPackages(Context c, Set<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : list) {
            if (pkg == null || pkg.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(pkg.trim());
        }
        sp(c).edit().putString(K_FALLBACK_PKGS, sb.toString()).apply();
    }

    /**
     * 一句话状态：开没开、选了几个、隧道通没通、是不是正在调整时段里被放开，
     * 以及「没通」到底卡在哪 —— 没授权、被别的 VPN 占着，还是已经改用冻结顶上。
     *
     * 方向是「默认常开」：调整时段内放开是本该如此，不能说成「没连上隧道」。
     */
    public static String status(Context c) {
        if (!on(c)) return T.s("未开启");
        int n = packages(c).size();
        if (n == 0) return T.s("还没选应用");
        if (proxyMode(c)) return T.f("%d 个应用 · 由代理负责拦截（本 App 不占 VPN）", n);
        if (NetWindow.inside(c)) return T.f("%d 个应用 · 调整时段 %s 内放开", n, NetWindow.label(c));
        if (running(c)) return T.f("%d 个应用断网中", n);
        if (!fallbackPackages(c).isEmpty()) return T.f("%d 个应用 · 已改用冻结（VPN 被占用）", n);
        String why = why(c);
        if (NetBlockService.WHY_PERMISSION.equals(why)) return T.f("%d 个应用 · 断网没生效：没给 VPN 授权", n);
        if (NetBlockService.WHY_OTHER_VPN.equals(why)) return T.f("%d 个应用 · 断网让路：VPN 被代理占用（不抢）", n);
        return T.f("%d 个应用待断网（没连上隧道）", n);
    }
}
