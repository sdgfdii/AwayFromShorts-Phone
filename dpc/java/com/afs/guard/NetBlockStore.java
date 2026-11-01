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

    public static String status(Context c) {
        if (!on(c)) return T.s("未开启");
        int n = packages(c).size();
        if (n == 0) return T.s("还没选应用");
        if (!TimeWindow.on(c)) return T.f("%d 个应用 · %s 才生效", n, TimeWindow.label(c));
        if (running(c)) return T.f("%d 个应用断网中 · %s", n, TimeWindow.label(c));
        return T.f("%d 个应用待断网（没连上隧道）", n);
    }
}
