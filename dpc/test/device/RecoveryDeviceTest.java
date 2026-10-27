package com.afs.guard;

import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.UserManager;

/** Two-phase emulator test: freeze a probe, then verify recovery preserved data. */
public final class RecoveryDeviceTest extends Instrumentation {
    private String phase;
    public void onCreate(Bundle args) { super.onCreate(args); phase=args.getString("phase","after"); start(); }
    public void onStart() {
        Bundle result=new Bundle();
        try {
            Context c=getTargetContext(); String victim=getContext().getPackageName();
            DevicePolicyManager d=(DevicePolicyManager)c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            SharedPreferences p=c.getSharedPreferences("afs_recovery_probe",0);
            SharedPreferences rules=c.getSharedPreferences("afs_guard",0);
            if("before".equals(phase)) {
                if(!d.isDeviceOwnerApp(c.getPackageName()))throw new AssertionError("missing owner");
                p.edit().putString("study",StudyStore.exportJson(c)).putString("rules",rules.getString("rules","{}"))
                        .putString("marker","preserve-this").commit();
                rules.edit().putString("rules","{\""+victim+"\":{\"d\":0,\"w\":[],\"g\":false}}").commit();
                Guard.apply(c);
                if((c.getPackageManager().getApplicationInfo(victim,0).flags&ApplicationInfo.FLAG_SUSPENDED)==0)
                    throw new AssertionError("probe was not frozen");
            } else {
                if(d.isDeviceOwnerApp(c.getPackageName()))throw new AssertionError("owner still active");
                if(!"preserve-this".equals(p.getString("marker","")))throw new AssertionError("local data erased");
                if(!StudyStore.exportJson(c).equals(p.getString("study","")))throw new AssertionError("study data changed");
                if((c.getPackageManager().getApplicationInfo(victim,0).flags&ApplicationInfo.FLAG_SUSPENDED)!=0)
                    throw new AssertionError("managed probe still frozen");
                UserManager users=(UserManager)c.getSystemService(Context.USER_SERVICE);
                for(String key:new String[]{UserManager.DISALLOW_FACTORY_RESET,UserManager.DISALLOW_SAFE_BOOT,UserManager.DISALLOW_APPS_CONTROL})
                    if(users.hasUserRestriction(key))throw new AssertionError("restriction remains: "+key);
                rules.edit().putString("rules",p.getString("rules","{}")).commit();
                p.edit().clear().commit();
            }
            result.putString("stream","Recovery device: "+phase+" passed\n");finish(-1,result);
        } catch(Throwable e) {result.putString("stream","FAILED "+android.util.Log.getStackTraceString(e));finish(1,result);}
    }
}
