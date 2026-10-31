package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 钥匙应用闸门：先在「正经应用」里待够时间，娱乐应用才开门。
 *
 * 和既有学习闸门（{@link StudyGate}）的区别：学习闸门看的是打卡目标完成度（云端账本），
 * 这一层看的是**今天在指定应用里实际待了多久**（系统使用记录），完全在本机算，
 * 不依赖任何同步，也不看打卡。两者互不替代，可以同时开。
 *
 * 判定接在 {@link Guard#blockReasonNow} 之后（reason 6），所以冻结、首页状态、
 * 常驻通知、解锁后的自检都自动跟着走，没有第二套真相。
 */
public final class KeyGate {

    private static final String SP = "afs_keygate";
    private static final String K_ON = "on";
    private static final String K_MINUTES = "minutes";
    private static final String K_PKGS = "pkgs";

    public static final int DEFAULT_MINUTES = 20;
    public static final int MAX_MINUTES = 480;

    /** 达标结果缓存 10 秒：一次 apply 会问十几个应用，别每个都去查一遍使用记录。 */
    private static long checkedAt;
    private static boolean checkedValue;

    private KeyGate() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 开关本身（不看有没有选应用）：界面上的"开/关"要显示用户点的那一个。 */
    public static boolean on(Context c) {
        return sp(c).getBoolean(K_ON, false);
    }

    /** 真正生效：开着且选了钥匙应用。 */
    public static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ON, false) && !packages(c).isEmpty();
    }

    public static void setEnabled(Context c, boolean value) {
        sp(c).edit().putBoolean(K_ON, value).apply();
    }

    public static int required(Context c) {
        int value = sp(c).getInt(K_MINUTES, DEFAULT_MINUTES);
        return value < 1 ? 1 : (value > MAX_MINUTES ? MAX_MINUTES : value);
    }

    public static void setRequired(Context c, int minutes) {
        int value = minutes < 1 ? 1 : (minutes > MAX_MINUTES ? MAX_MINUTES : minutes);
        sp(c).edit().putInt(K_MINUTES, value).apply();
        invalidate();
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
        invalidate();
    }

    public static void invalidate() {
        checkedAt = 0L;
        checkedValue = false;
    }

    /** 今天在钥匙应用里累计待了多久（毫秒）。不按任何时段裁剪。 */
    public static long usedToday(Context c, long now) {
        Set<String> keys = packages(c);
        if (keys.isEmpty()) return 0L;
        long total = 0L;
        try {
            List<AppUsageQuota.Span> spans = AppUsageQuota.spans(c, now);
            for (AppUsageQuota.Span span : spans) {
                if (span == null || !keys.contains(span.pkg)) continue;
                if (span.end > span.start) total += span.end - span.start;
            }
        } catch (RuntimeException error) {
            return 0L;     // 读不到使用记录就当没达标：宁可多要求，不偷偷放宽
        }
        return total;
    }

    /** 达标了吗（10 秒缓存）。 */
    public static boolean satisfied(Context c, long now) {
        if (!enabled(c)) return true;
        if (checkedAt > 0 && now >= checkedAt && now - checkedAt < 10000L) return checkedValue;
        boolean value = usedToday(c, now) >= required(c) * LockPlan.MINUTE;
        checkedAt = now;
        checkedValue = value;
        return value;
    }

    /**
     * 这个应用现在是否该被闸门挡住。
     * 钥匙应用自己、以及解锁码放行期内都不挡。
     */
    public static boolean blocks(Context c, String pkg, long now) {
        if (pkg == null || !enabled(c)) return false;
        if (packages(c).contains(pkg)) return false;
        if (UnlockStore.passActive(c, now)) return false;
        return !satisfied(c, now);
    }

    /** 纯判定，给离线回归用。 */
    static boolean blocksFor(boolean enabled, boolean isKeyApp, boolean satisfied, boolean passActive) {
        if (!enabled || isKeyApp || passActive) return false;
        return !satisfied;
    }

    /** 还差多少分钟（0 = 已达标）。 */
    public static int missingMinutes(Context c, long now) {
        if (!enabled(c)) return 0;
        long used = usedToday(c, now);
        long need = required(c) * LockPlan.MINUTE - used;
        if (need <= 0) return 0;
        return (int) Math.ceil(need / (double) LockPlan.MINUTE);
    }

    /** 首页/设置页那一行状态。 */
    public static String status(Context c, long now) {
        if (!enabled(c)) return T.s("未开启");
        Set<String> keys = packages(c);
        if (!AppUsageQuota.permission(c)) return T.s("已开启 · 未授予使用情况权限，无法计时");
        int missing = missingMinutes(c, now);
        if (missing == 0) return T.f("已达标（%d 分钟）", required(c));
        long used = usedToday(c, now);
        return T.f("已待 %d / %d 分钟，还差 %d 分钟", (int) (used / LockPlan.MINUTE), required(c), missing);
    }
}
