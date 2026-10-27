package com.afs.guard;

import android.content.Context;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

/**
 * 系统级灰度：今天目标没打完 → 整台手机所有应用变灰。
 *
 * 这是「健康使用手机」超时后那套灰度的实现方式：
 * Android 辅助功能里的"色彩校正"，模式 0 = 灰度。往 Settings.Secure 写两个键，
 * 全系统立刻生效（包括桌面和其它 App），重启也保持 —— 所以是"设完就不用管"：
 * 打完卡的瞬间 Guard.apply 会把它写回 0。
 *
 * 权限：WRITE_SECURE_SETTINGS 普通装机拿不到，需要 adb 授权一次：
 *   adb shell pm grant com.afs.guard android.permission.WRITE_SECURE_SETTINGS
 * 没授权时静默降级（只灰 App 自己的页面，那是 MainActivity 自己的饱和度层），不会崩。
 */
public final class GrayMode {

    private static final String TAG = Guard.TAG;
    private static final String KEY_ENABLED = "accessibility_display_daltonizer_enabled";
    private static final String KEY_MODE = "accessibility_display_daltonizer";
    /** 色彩校正模式 0 = 灰度 */
    private static final String GRAYSCALE = "0";

    /** 上一次写进系统的状态；null = 这个进程还没成功动过系统设置。 */
    private static volatile Boolean lastApplied;
    /** 失败日志只刷一条（授权前 Guard.apply 会反复跑）。 */
    private static volatile boolean warned;

    private GrayMode() {
    }

    /** 是否已拿到写系统设置的授权（决定设置页要不要给引导）。 */
    public static boolean permitted(Context c) {
        return c.checkPermission("android.permission.WRITE_SECURE_SETTINGS",
                Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 应用灰度状态。状态没变化时不写 —— 写 Settings.Secure 会惊动系统监听，
     * Guard.apply 又跑得勤，没必要的写一律省掉。
     */
    public static void apply(Context c, boolean gray) {
        Boolean want = Boolean.valueOf(gray);
        if (want.equals(lastApplied)) {
            return;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            boolean owner = dpm != null && dpm.isDeviceOwnerApp(c.getPackageName());
            if (gray) {
                if (owner) dpm.setSecureSetting(new ComponentName(c, AfsAdmin.class),
                        KEY_MODE, GRAYSCALE);
                else if (!Settings.Secure.putString(c.getContentResolver(), KEY_MODE, GRAYSCALE))
                    throw new SecurityException("cannot set grayscale mode");
            }
            if (owner) dpm.setSecureSetting(new ComponentName(c, AfsAdmin.class),
                    KEY_ENABLED, gray ? "1" : "0");
            else if (!Settings.Secure.putString(c.getContentResolver(), KEY_ENABLED,
                    gray ? "1" : "0")) throw new SecurityException("cannot toggle grayscale");
            String actual = Settings.Secure.getString(c.getContentResolver(), KEY_ENABLED);
            if (!(gray ? "1" : "0").equals(actual))
                throw new IllegalStateException("grayscale setting did not change");
            lastApplied = want;
            Log.i(TAG, "gray mode -> " + gray);
        } catch (Throwable t) {
            // 没有 WRITE_SECURE_SETTINGS：降级成只灰自己。lastApplied 保持不变，
            // 下次 Guard.apply 还会重试 —— 这样用户事后 adb 授权完不用重启 App。
            if (!warned) {
                warned = true;
                Log.w(TAG, "gray mode 需要授权: adb shell pm grant com.afs.guard "
                        + "android.permission.WRITE_SECURE_SETTINGS (" + t.getClass().getSimpleName() + ")");
            }
        }
    }
}
