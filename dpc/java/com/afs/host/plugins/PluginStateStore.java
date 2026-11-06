package com.afs.host.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * The only persisted plugin state. It lives in afs_plugins_v1 and never mixes
 * with RuleStore, quotas, learning data or backup allow-lists.
 */
public final class PluginStateStore {
    static final String PREFS = "afs_plugins_v1";
    private static final String KEY_PACKAGES = "packages";

    private final SharedPreferences prefs;
    private final Context context;
    private final Set<String> writeFailures=new HashSet<>();

    public PluginStateStore(Context context) {
        this.context=context.getApplicationContext();
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {prefs.getStringSet(KEY_PACKAGES,new HashSet<String>());}
        catch(RuntimeException badCatalog){prefs.edit().clear().commit();} // only optional-plugin metadata
    }

    public synchronized PluginState load(String packageName) {
        try {
        prefs.getStringSet(KEY_PACKAGES,java.util.Collections.<String>emptySet());
        String prefix = "p." + packageName + ".";
        if (!prefs.contains(prefix + "fingerprint")) return null;
        prefs.getLong(prefix+"first_install",0L);
        prefs.getLong(prefix+"replacing_until",0L);
        boolean blocked=writeFailures.contains(packageName);
        return new PluginState(packageName,
                !blocked&&prefs.getBoolean(prefix + "enabled", false),
                !blocked&&prefs.getBoolean(prefix + "read", false),
                !blocked&&prefs.getBoolean(prefix + "request", false),
                prefs.getString(prefix + "caps", ""),
                prefs.getString(prefix + "fingerprint", ""),
                prefs.getString(prefix + "signer", ""),
                prefs.getInt(prefix + "version", 0),
                prefs.getLong(prefix + "install_gen", 1L),
                prefs.getLong(prefix + "enabled_gen", 1L),
                prefs.getLong(prefix + "read_gen", 1L),
                prefs.getLong(prefix + "request_gen", 1L),
                blocked?"STORAGE_ERROR":prefs.getString(prefix + "failure", ""),
                blocked||prefs.getBoolean(prefix + "needs_confirm", true));
        } catch(RuntimeException damaged) { return null; } // catalog repair discovers it as disabled
    }

    public synchronized boolean save(PluginState state) {return save(state,null);}
    private boolean save(PluginState state,Long firstInstall) {
        if(!writeFailures.contains(state.packageName)) {
            PluginState previous=load(state.packageName);
            if(same(previous,state)&&knownPackages().contains(state.packageName)
                    &&(firstInstall==null||(firstInstall(state.packageName)==firstInstall&&replacingUntil(state.packageName)==0L)))return true;
        }
        String prefix = "p." + state.packageName + ".";
        Set<String> packages=knownPackages();packages.add(state.packageName);
        SharedPreferences.Editor editor=prefs.edit()
                .putBoolean(prefix + "enabled", state.enabled)
                .putBoolean(prefix + "read", state.readGranted)
                .putBoolean(prefix + "request", state.requestGranted)
                .putString(prefix + "caps", state.confirmedCapabilities)
                .putString(prefix + "fingerprint", state.fingerprint)
                .putString(prefix + "signer", state.signerSha256)
                .putInt(prefix + "version", state.versionCode)
                .putLong(prefix + "install_gen", state.installGeneration)
                .putLong(prefix + "enabled_gen", state.enabledGeneration)
                .putLong(prefix + "read_gen", state.readGeneration)
                .putLong(prefix + "request_gen", state.requestGeneration)
                .putString(prefix + "failure", state.failure)
                .putBoolean(prefix + "needs_confirm", state.needsConfirmation)
                .putStringSet(KEY_PACKAGES,packages);
        if(firstInstall!=null)editor.putLong(prefix+"first_install",firstInstall).putLong(prefix+"replacing_until",0L);
        boolean saved;
        try{saved=editor.commit();}catch(RuntimeException failure){saved=false;}
        if(saved)writeFailures.remove(state.packageName);else writeFailures.add(state.packageName);
        return saved;
    }
    private static boolean same(PluginState a,PluginState b){return a!=null&&a.enabled==b.enabled&&a.readGranted==b.readGranted&&a.requestGranted==b.requestGranted
            &&a.confirmedCapabilities.equals(b.confirmedCapabilities)&&a.fingerprint.equals(b.fingerprint)&&a.signerSha256.equals(b.signerSha256)
            &&a.versionCode==b.versionCode&&a.installGeneration==b.installGeneration&&a.enabledGeneration==b.enabledGeneration
            &&a.readGeneration==b.readGeneration&&a.requestGeneration==b.requestGeneration&&a.failure.equals(b.failure)&&a.needsConfirmation==b.needsConfirmation;}
    public synchronized boolean storageFailed(String pkg){return writeFailures.contains(pkg);}
    public synchronized boolean retryDisable(String pkg){
        PluginFingerprint live=PluginIdentity.read(context,pkg);if(live==null||!PluginIdentity.trusted(context,live.signerSha256))return false;
        PluginState old=load(pkg);long generation=old==null?1:old.enabledGeneration+1;
        return save(new PluginState(pkg,false,false,false,"",live.value(),live.signerSha256,live.versionCode,
                old==null?1:old.installGeneration,generation,old==null?1:old.readGeneration+1,
                old==null?1:old.requestGeneration+1,"",true),live.firstInstallTime);
    }

    public synchronized void clear(String packageName) {
        String prefix = "p." + packageName + ".";
        prefs.edit()
                .remove(prefix + "enabled").remove(prefix + "read").remove(prefix + "request")
                .remove(prefix + "caps").remove(prefix + "fingerprint").remove(prefix + "signer")
                .remove(prefix + "version").remove(prefix + "install_gen")
                .remove(prefix + "enabled_gen").remove(prefix + "read_gen")
                .remove(prefix + "request_gen").remove(prefix + "failure")
                .remove(prefix + "needs_confirm").remove(prefix + "replacing_until").commit();
        prefs.edit().remove(prefix+"first_install").commit();
        Set<String> packages = knownPackages();
        if (packages.remove(packageName)) {
            prefs.edit().putStringSet(KEY_PACKAGES, packages).commit();
        }
    }

    public synchronized Set<String> knownPackages() {
        try {return new HashSet<>(prefs.getStringSet(KEY_PACKAGES, new HashSet<String>()));}
        catch(RuntimeException damaged){return new HashSet<>();}
    }

    private boolean sameLiveInstall(String pkg,String fingerprint) {
        PluginFingerprint live=PluginIdentity.read(context,pkg);
        return live!=null && PluginIdentity.trusted(context,live.signerSha256) && fingerprint.equals(live.value());
    }
    /** One CAS write for the exact installation/capabilities displayed in the confirmation. */
    public synchronized boolean confirm(PluginEntry displayed) {
        if(displayed==null || displayed.hasError() || !displayed.compatible || displayed.manifest==null || displayed.state==null)return false;
        String pkg=displayed.packageName();PluginState old=load(pkg);
        if(writeFailures.contains(pkg))return false;
        if(old==null || !old.fingerprint.equals(displayed.fingerprint.value())
                || old.installGeneration!=displayed.state.installGeneration
                || old.enabledGeneration!=displayed.state.enabledGeneration
                || !sameLiveInstall(pkg,old.fingerprint))return false;
        String caps=android.text.TextUtils.join(",",displayed.manifest.capabilities);
        boolean read=displayed.manifest.capabilities.contains(com.afs.pluginapi.PluginProtocol.CAP_READ);
        boolean request=displayed.manifest.capabilities.contains(com.afs.pluginapi.PluginProtocol.CAP_REQUEST);
        return save(new PluginState(pkg,true,read,request,caps,old.fingerprint,old.signerSha256,old.versionCode,
                old.installGeneration,old.enabledGeneration+1,old.readGeneration+1,old.requestGeneration+1,"",false));
    }
    /** Touch only the selected grant. Concurrent clicks must not restore its sibling. */
    public synchronized boolean setGrant(PluginEntry displayed,boolean read,boolean granted) {
        if(displayed==null || displayed.state==null)return false;
        PluginState old=load(displayed.packageName());
        String capability=read?com.afs.pluginapi.PluginProtocol.CAP_READ:com.afs.pluginapi.PluginProtocol.CAP_REQUEST;
        if(old==null || !old.enabled || old.enabledGeneration!=displayed.state.enabledGeneration
                || !old.fingerprint.equals(displayed.fingerprint.value()) || !sameLiveInstall(old.packageName,old.fingerprint)
                || !java.util.Arrays.asList(old.confirmedCapabilities.split(",")).contains(capability)
                || (read?old.readGeneration!=displayed.state.readGeneration:old.requestGeneration!=displayed.state.requestGeneration))return false;
        return save(new PluginState(old.packageName,true,read?granted:old.readGranted,read?old.requestGranted:granted,
                old.confirmedCapabilities,old.fingerprint,old.signerSha256,old.versionCode,old.installGeneration,
                old.enabledGeneration,old.readGeneration+(read?1:0),old.requestGeneration+(read?0:1),old.failure,old.needsConfirmation));
    }
    public synchronized PluginState deny(String pkg,String reason) {
        PluginState old=load(pkg);if(old==null)return null;
        if(!old.enabled&&!old.readGranted&&!old.requestGranted&&reason.equals(old.failure))return old;
        PluginState next=new PluginState(pkg,false,false,false,"",old.fingerprint,old.signerSha256,old.versionCode,
                old.installGeneration,old.enabledGeneration+1,old.readGeneration+1,old.requestGeneration+1,reason,true);
        save(next);return next;
    }

    /** A replacing removal must not be mistaken for a genuine uninstall. */
    public synchronized void markReplacing(String packageName, long untilEpochMs) {
        if(!prefs.edit().putLong("p." + packageName + ".replacing_until", untilEpochMs).commit())writeFailures.add(packageName);
    }

    public synchronized boolean isReplacing(String packageName) {
        return replacingUntil(packageName)>System.currentTimeMillis();
    }
    private long replacingUntil(String pkg){try{return prefs.getLong("p."+pkg+".replacing_until",0L);}catch(RuntimeException damaged){return 0L;}}

    /**
     * Reconciles persisted grants with a newly discovered install. A genuine
     * upgrade with unchanged signer, capabilities and compatibility keeps
     * enabled/grants and only bumps the install generation. Anything else
     * requires a fresh user confirmation.
     */
    public synchronized PluginState discover(String packageName, PluginFingerprint fingerprint,
                                             String capabilitiesCsv, boolean compatible,
                                             boolean signerTrusted) {
        PluginState old = load(packageName);
        PluginState next;
        if (old == null) {
            next = new PluginState(packageName, false, false, false, "", fingerprint.value(),
                    fingerprint.signerSha256, fingerprint.versionCode, 1L, 1L, 1L, 1L,
                    compatible ? "" : "INCOMPATIBLE", true);
        } else if (!old.fingerprint.equals(fingerprint.value())) {
            boolean sameSigner = old.signerSha256.equals(fingerprint.signerSha256);
            boolean keep = compatible && signerTrusted && sameSigner
                    && fingerprint.firstInstallTime > 0L
                    && firstInstall(packageName) == fingerprint.firstInstallTime
                    && old.confirmedCapabilities.equals(capabilitiesCsv)
                    && old.confirmedCapabilities.length() > 0;
            if (keep) {
                next = new PluginState(packageName, old.enabled, old.readGranted,
                        old.requestGranted, old.confirmedCapabilities, fingerprint.value(),
                        fingerprint.signerSha256, fingerprint.versionCode,
                        old.installGeneration + 1L, old.enabledGeneration,
                        old.readGeneration, old.requestGeneration, "", old.needsConfirmation);
            } else {
                next = new PluginState(packageName, false, false, false,
                        "", fingerprint.value(), fingerprint.signerSha256,
                        fingerprint.versionCode, old.installGeneration + 1L,
                        old.enabledGeneration + 1L, old.readGeneration + 1L,
                        old.requestGeneration + 1L, compatible ? "" : "INCOMPATIBLE", true);
            }
        } else {
            boolean wasCompatible = !"INCOMPATIBLE".equals(old.failure);
            if (!compatible) {
                next = new PluginState(packageName, false, false, false, old.confirmedCapabilities,
                        old.fingerprint, old.signerSha256, old.versionCode, old.installGeneration,
                        old.enabledGeneration + (wasCompatible ? 1L : 0L),
                        old.readGeneration + (wasCompatible ? 1L : 0L),
                        old.requestGeneration + (wasCompatible ? 1L : 0L),
                        "INCOMPATIBLE", old.needsConfirmation);
            } else {
                next = new PluginState(packageName, old.enabled, old.readGranted, old.requestGranted,
                        old.confirmedCapabilities, old.fingerprint, old.signerSha256,
                        old.versionCode, old.installGeneration, old.enabledGeneration,
                        old.readGeneration, old.requestGeneration, "", old.needsConfirmation);
            }
        }
        save(next,fingerprint.firstInstallTime);
        return load(packageName);
    }
    private long firstInstall(String pkg){try{return prefs.getLong("p."+pkg+".first_install",0L);}catch(RuntimeException damaged){return 0L;}}

    public synchronized boolean disable(String packageName){return retryDisable(packageName);}
}

