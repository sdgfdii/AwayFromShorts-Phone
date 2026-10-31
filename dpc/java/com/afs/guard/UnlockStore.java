package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 亲友解锁码的本机状态：密钥、放行期、失败次数。
 *
 * 密钥只存在本机；家长那边把它加进验证器 App。谁掌握密钥谁才能放行，
 * 所以「生成密钥」这一步本身就要在已经锁住的环境之外做（设置页里做，不在锁屏页）。
 *
 * 已知边界（界面也这么写）：密钥在本机，能打开设置页的人就看得到它。
 * 这一层防的是「手上没有别人手机时的一时冲动」，不是防技术破解。
 * 要更硬的隔离，就让家长在自己手机上生成 32 位密钥（任何 TOTP 工具都行）再手输进来。
 */
public final class UnlockStore {

    private static final String SP = "afs_unlock";
    private static final String K_ENABLED = "enabled";
    private static final String K_SECRET = "secret";
    private static final String K_PASS_UNTIL = "pass_until";
    private static final String K_FAILS = "fails";
    private static final String K_FAIL_AT = "fail_at";

    /** 验一次码放行多久。够处理急事，不够刷完一整场直播。 */
    public static final long PASS_MS = 10L * 60L * 1000L;
    /** 连续错 5 次后冷却 1 分钟，堵暴力试。 */
    public static final int MAX_FAILS = 5;
    public static final long COOLDOWN_MS = 60L * 1000L;

    private UnlockStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 有密钥才算开启；没有密钥时所有入口都不显示。 */
    public static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, false) && !secret(c).isEmpty();
    }

    public static void setEnabled(Context c, boolean value) {
        sp(c).edit().putBoolean(K_ENABLED, value).apply();
    }

    public static String secret(Context c) {
        String value = sp(c).getString(K_SECRET, "");
        return value == null ? "" : value;
    }

    public static void setSecret(Context c, String value) {
        String clean = value == null ? "" : value.replace(" ", "").replace("-", "").trim();
        sp(c).edit().putString(K_SECRET, clean).apply();
    }

    /** 生成一对新密钥（换密钥＝旧验证器里的条目立刻失效）。 */
    public static String generate(Context c) {
        String secret = UnlockCode.newSecret();
        sp(c).edit().putString(K_SECRET, secret).putBoolean(K_ENABLED, true)
                .putLong(K_PASS_UNTIL, 0L).apply();
        return secret;
    }

    public static long passUntil(Context c) {
        return sp(c).getLong(K_PASS_UNTIL, 0L);
    }

    public static boolean passActive(Context c, long now) {
        return enabled(c) && passUntil(c) > now;
    }

    public static long passRemaining(Context c, long now) {
        return Math.max(0L, passUntil(c) - now);
    }

    public static void clearPass(Context c) {
        sp(c).edit().putLong(K_PASS_UNTIL, 0L).apply();
    }

    /** 冷却还剩多久（0 = 可以继续试）。 */
    public static long cooldown(Context c, long now) {
        int fails = sp(c).getInt(K_FAILS, 0);
        if (fails < MAX_FAILS) return 0L;
        long left = sp(c).getLong(K_FAIL_AT, 0L) + COOLDOWN_MS - now;
        return Math.max(0L, left);
    }

    /**
     * 用一串码换一段放行。
     * 返回 0 = 不通过，1 = 通过，2 = 正在冷却。
     */
    public static int redeem(Context c, String input, long now) {
        if (!enabled(c)) return 0;
        if (cooldown(c, now) > 0) return 2;
        long second = now / 1000L;
        if (UnlockCode.verify(secret(c), input, second)) {
            sp(c).edit().putLong(K_PASS_UNTIL, now + PASS_MS)
                    .putInt(K_FAILS, 0).apply();
            return 1;
        }
        int fails = sp(c).getInt(K_FAILS, 0) + 1;
        if (fails > MAX_FAILS) fails = MAX_FAILS;   // 达到上限后每次失败都重新起冷却
        sp(c).edit().putInt(K_FAILS, fails).putLong(K_FAIL_AT, now).apply();
        return 0;
    }
}
