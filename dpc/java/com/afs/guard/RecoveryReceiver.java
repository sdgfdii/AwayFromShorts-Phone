package com.afs.guard;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.LinkedHashSet;

/** Registered ONLY in the separately signed maintenance APK, protected by DUMP. */
public final class RecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (intent == null || !"com.afs.guard.RECOVER".equals(intent.getAction())
                || !"REMOVE_AFS_DEVICE_OWNER".equals(intent.getStringExtra("confirm"))) {
            setResultCode(Activity.RESULT_CANCELED); setResultData("Confirmation required"); return;
        }
        boolean restore = false;
        synchronized (Guard.POLICY_LOCK) {
        try {
            DevicePolicyManager d = (DevicePolicyManager)c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(c,AfsAdmin.class);
            if (d != null && d.isDeviceOwnerApp(c.getPackageName())) {
                c.stopService(new Intent(c,GuardService.class));
                LinkedHashSet<String> packages = new LinkedHashSet<>(RuleStore.packages(c));
                packages.addAll(RuleStore.applied(c));
                for (String pkg : packages) {
                    // Only installed packages can be suspended. Uninstalled rule
                    // entries must not prevent recovery.
                    try { c.getPackageManager().getApplicationInfo(pkg,0); }
                    catch (android.content.pm.PackageManager.NameNotFoundException e) { continue; }
                    String[] failed = d.setPackagesSuspended(admin,new String[]{pkg},false);
                    if (failed != null && failed.length != 0) throw new IllegalStateException("Cannot release " + pkg);
                    d.setUninstallBlocked(admin,pkg,false);
                }
                GrayMode.apply(c,false);
                if (Build.VERSION.SDK_INT < 28) d.setAutoTimeRequired(admin,false);
                for (String key : d.getUserRestrictions(admin).keySet()) d.clearUserRestriction(admin,key);
                d.setUninstallBlocked(admin,c.getPackageName(),false);
                d.clearDeviceOwnerApp(c.getPackageName());
                if (d.isDeviceOwnerApp(c.getPackageName())) throw new IllegalStateException("Device owner still active");
                // clearDeviceOwnerApp already removes the active admin on API 24.
                // Calling removeActiveAdmin again can leave duplicate pending-removal
                // records in that release's system service.
            }
            setResultCode(Activity.RESULT_OK); setResultData("AFS_RECOVERY_DONE");
        } catch (RuntimeException e) {
            setResultCode(Activity.RESULT_CANCELED); setResultData("Recovery failed: " + e.getClass().getSimpleName());
            restore = true;
        }
        }
        if (restore) {
            try { if (Guard.isOwner(c)) { Guard.apply(c); Guard.schedule(c); Guard.ensureService(c); } }
            catch (RuntimeException ignored) { }
        }
    }
}
