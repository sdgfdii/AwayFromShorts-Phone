package com.afs.guard;

import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.TimeZone;

/** Emulator-only verification against the real Android DevicePolicyManager. */
public final class FreezeDeviceTest extends Instrumentation {
    private int checks;
    private void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    public void onCreate(Bundle args) { super.onCreate(args); start(); }
    public void onStart() {
        Bundle result = new Bundle();
        Context c = getTargetContext();
        String victim = getContext().getPackageName();
        SharedPreferences p = c.getSharedPreferences("afs_guard", 0);
        String rules = p.getString("rules", null);
        DevicePolicyManager d = (DevicePolicyManager)c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(c, AfsAdmin.class);
        int code = -1;
        try {
            check(d.isDeviceOwnerApp(c.getPackageName()), "actual device owner");
            p.edit().putString("rules", "{\""+victim+"\":{\"d\":0,\"w\":[],\"g\":false}}").commit();
            Guard.apply(c);
            check(d.isPackageSuspended(admin, victim), "Guard.apply freezes an installed package");
            check(d.isUninstallBlocked(admin,victim), "target cannot be uninstalled");
            check(d.isUninstallBlocked(admin,c.getPackageName()), "guard cannot be uninstalled");
            Bundle restrictions = d.getUserRestrictions(admin);
            check(restrictions.getBoolean(UserManager.DISALLOW_FACTORY_RESET), "factory reset blocked");
            check(restrictions.getBoolean(UserManager.DISALLOW_SAFE_BOOT), "safe boot blocked");
            check(restrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL), "app controls blocked");
            if (Build.VERSION.SDK_INT < 28) {
                check(d.getAutoTimeRequired(), "automatic time enforced on Android 7/8");
                Class<?> clock = Class.forName("com.afs.guard.GuardClock",true,c.getClassLoader());
                Method now = clock.getDeclaredMethod("now",Context.class); now.setAccessible(true);
                Calendar before = (Calendar)now.invoke(null,c);
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
                Calendar after = (Calendar)now.invoke(null,c);
                check(before.getTimeZone().getID().equals(after.getTimeZone().getID()), "manual timezone change cannot shift schedule");
            }
            p.edit().putString("rules", "{\""+victim+"\":{\"d\":127,\"w\":[[0,0]],\"g\":false}}").commit();
            Guard.apply(c);
            check(!d.isPackageSuspended(admin,victim), "Guard.apply releases package in allowed window");
            result.putString("stream","Freeze device: "+checks+" checks passed\n");
        } catch (Throwable error) {
            result.putString("stream","FAILED "+android.util.Log.getStackTraceString(error)); code = 1;
        } finally {
            if (rules == null) p.edit().remove("rules").commit(); else p.edit().putString("rules",rules).commit();
            try { d.setPackagesSuspended(admin,new String[]{victim},false); d.setUninstallBlocked(admin,victim,false); Guard.apply(c); }
            catch (Exception ignored) { }
        }
        finish(code,result);
    }
}
