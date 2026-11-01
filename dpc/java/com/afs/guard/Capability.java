package com.afs.guard;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * 能力分级：同一个 APK，能做什么取决于系统给了多少权限。
 *
 * 三级（数字越大越强）：
 *   0 基本    —— 只有使用情况访问：能看时长、能提醒，冻不住也拦不住
 *   1 无障碍  —— 能读当前窗口内容、能按返回键：关键词拦截可用，仍然冻不住
 *   2 设备所有者 —— 能真正冻结应用、能 LockTask 真锁屏
 *
 * 为什么要单独一层：同一个功能在三级里表现完全不同，界面必须**如实**说
 * "这一项现在为什么不能用"，而不是给一个点了没反应的开关。
 */
public final class Capability {

    public static final int NONE = 0;
    public static final int LIMITED = 1;
    public static final int ACCESSIBILITY = 2;
    public static final int OWNER = 3;

    /* 功能标识：界面按它查"需要什么等级" */
    public static final String F_FREEZE = "freeze";
    public static final String F_LOCK = "lock";
    public static final String F_KEYWORD = "keyword";
    public static final String F_NET = "net";
    public static final String F_QUOTA = "quota";

    private Capability() {
    }

    /** 纯函数：等级只由这两个开关决定，方便离线测试。 */
    public static int level(boolean owner, boolean accessibility) {
        if (owner) return OWNER;
        return accessibility ? ACCESSIBILITY : LIMITED;
    }

    public static int level(Context c) {
        return level(Guard.isOwner(c), accessibilityOn(c));
    }

    public static String name(int level) {
        switch (level) {
            case OWNER: return T.s("设备所有者");
            case ACCESSIBILITY: return T.s("无障碍");
            case LIMITED: return T.s("基本");
            default: return T.s("未授权");
        }
    }

    /** 这一级到底能干什么（一句话，不吹）。 */
    public static String scope(int level) {
        switch (level) {
            case OWNER:
                return T.s("能冻结应用、能真锁屏、能把你请出应用。这是完整形态。");
            case ACCESSIBILITY:
                return T.s("能读屏做关键词拦截、能按返回键，但冻不住应用、锁屏拦不住 Home。");
            case LIMITED:
                return T.s("只能计时和提醒。冻结、锁屏、拦截都需要更高权限。");
            default:
                return T.s("什么都做不了，先把权限开起来。");
        }
    }

    /** 某个功能需要的等级。 */
    public static int needFor(String feature) {
        if (F_FREEZE.equals(feature) || F_LOCK.equals(feature)) return OWNER;
        if (F_KEYWORD.equals(feature)) return ACCESSIBILITY;
        return LIMITED;
    }

    /** 没达到要求时，界面显示的那句原因。 */
    public static String blockReason(Context c, String feature) {
        int need = needFor(feature);
        int have = level(c);
        if (have >= need) return "";
        if (need == OWNER) {
            return T.s("需要设备所有者（要装部署包，系统设置里开不了）");
        }
        return T.s("需要开启无障碍服务（在系统设置 → 无障碍里打开）");
    }

    /** 无障碍服务是否在运行。用设置里的服务列表判断，不信自己的开关。 */
    public static boolean accessibilityOn(Context c) {
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(flat)) return false;
            String mine = c.getPackageName() + "/" + KeywordGuardService.class.getName();
            String mineShort = c.getPackageName() + "/.KeywordGuardService";
            for (String item : flat.split(":")) {
                if (item == null) continue;
                String value = item.trim();
                if (value.equalsIgnoreCase(mine) || value.equalsIgnoreCase(mineShort)) return true;
            }
            return false;
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** 设置页那一行：三级用一句话概括当前处境。 */
    public static String summary(Context c) {
        int level = level(c);
        return name(level) + " · " + scope(level);
    }

    /** 选择器里每一项的短说明：太长会把单选项挤成两行。 */
    static String hint(int level) {
        switch (level) {
            case OWNER: return T.s("能冻结、能锁机");
            case ACCESSIBILITY: return T.s("能读屏拦截");
            case LIMITED: return T.s("只能计时提醒");
            default: return T.s("什么都没开");
        }
    }

    /**
     * 让用户挑一个能力等级。
     *
     * <p>等级不是应用里存的一个开关，而是系统给了多少权限算出来的，
     * 所以这里的「选择」是**带路的单选**：点更高的等级就跳到对应的系统页（或者说明为什么跳不过去），
     * 点更低的等级会说清楚降级要在系统里关。不给一个点了没反应的假开关。
     */
    public static void pick(final android.app.Activity a) {
        if (a == null || a.isFinishing()) return;
        final int[] levels = {NONE, LIMITED, ACCESSIBILITY, OWNER};
        final int have = level(a);
        String[] items = new String[levels.length];
        int checked = 0;
        for (int i = 0; i < levels.length; i++) {
            items[i] = name(levels[i]) + " · " + hint(levels[i]);
            if (levels[i] == have) checked = i;
        }
        // ⚠️ 不能同时 setMessage + setSingleChoiceItems：真机（Android 14）上两者互斥，
        // message 会把单选列表整个顶掉，弹窗只剩一句「现在：设备所有者」。标题里带当前等级。
        ThemeStore.dialog(a)
                .setTitle(T.s("能力等级") + T.s(" · 现在：") + name(have))
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    dialog.dismiss();
                    choose(a, levels[which], have);
                })
                .setNegativeButton(T.s("关闭"), null)
                .show();
    }

    /** 点了某一级之后做什么：能升就带路，升不了就说清楚代价。 */
    static void choose(android.app.Activity a, int target, int have) {
        if (a == null || target == have) return;
        if (target < have) {
            android.widget.Toast.makeText(a, T.s("降级要去系统的无障碍设置里关掉服务，应用里关不了"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (target == ACCESSIBILITY) {
            try {
                a.startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (RuntimeException error) {
                android.widget.Toast.makeText(a, T.s("这个系统没有无障碍设置页"),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (target == OWNER) {
            ThemeStore.dialog(a)
                    .setTitle(T.s("设备所有者"))
                    .setMessage(T.s("设备所有者只能用部署包一次性授权，系统设置里没有这个开关。\n\n")
                            + T.s("拿到部署包后按说明执行，应用会自动变成「设备所有者」，冻结和锁机才真正生效。\n\n")
                            + T.s("现在只有「") + name(have) + T.s("」的权限：")
                            + scope(have))
                    .setPositiveButton(T.s("知道了"), null)
                    .show();
        }
    }
}
