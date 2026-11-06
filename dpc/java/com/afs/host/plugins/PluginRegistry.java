package com.afs.host.plugins;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Looper;

import com.afs.pluginapi.PluginProtocol;
import com.afs.pluginapi.PluginStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Package discovery is generic: it queries PLUGIN_V1 and validates each
 * declaration. No per-plugin package name is compiled into the host.
 */
public final class PluginRegistry {
    private static final java.util.concurrent.ThreadPoolExecutor WORKER = new java.util.concurrent.ThreadPoolExecutor(1,1,30,java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<Runnable>(4),r -> {
        Thread thread = new Thread(r, "afs-plugin-discovery");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setDaemon(true);
        return thread;
    },new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    static {WORKER.allowCoreThreadTimeOut(true);}
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context context;
    private final PluginStateStore store;
    private volatile List<PluginEntry> entries = Collections.emptyList();
    private volatile String signature = "";
    private final Object scanGate=new Object();
    private final java.util.ArrayDeque<Runnable> waiters=new java.util.ArrayDeque<>();
    private boolean scanning,scanAgain;

    public PluginRegistry(Context context) {
        this.context = context.getApplicationContext();
        this.store = new PluginStateStore(this.context);
    }

    public PluginStateStore stateStore() { return store; }

    public List<PluginEntry> entries() { return entries; }

    public String signature() { return signature; }

    public void refreshAsync(Runnable done) {
        synchronized(scanGate){
            if(done!=null){if(waiters.size()==16)waiters.removeFirst();waiters.addLast(done);}
            scanAgain=true;if(scanning)return;scanning=true;
        }
        try{WORKER.execute(this::scan);}
        catch(java.util.concurrent.RejectedExecutionException busy){finishScan();}
    }
    private void scan(){
        for(;;){
            synchronized(scanGate){scanAgain=false;}
            try{refreshNow();}
            catch(RuntimeException unavailable){entries=Collections.emptyList();signature="";HostRuntime.get(context).sessions().invalidateAll(PluginStatus.UNAVAILABLE);}
            synchronized(scanGate){if(scanAgain)continue;finishScan();return;}
        }
    }
    private void finishScan(){
        List<Runnable> callbacks;
        synchronized(scanGate){scanning=false;callbacks=new ArrayList<>(waiters);waiters.clear();}
        for(Runnable callback:callbacks)MAIN.post(()->{try{callback.run();}catch(RuntimeException failure){android.util.Log.w("afs.plugins","Plugin directory callback failed");}});
    }

    public synchronized void refreshNow() {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(PluginProtocol.PLUGIN_ACTION);
        List<ResolveInfo> resolved;
        try {
            resolved = pm.queryIntentServices(query, 0);
        } catch (RuntimeException e) {
            resolved = Collections.emptyList();
        }

        Map<String, List<ComponentName>> byPackage = new HashMap<>();
        if (resolved != null) {
            for (ResolveInfo info : resolved) {
                ServiceInfo service = info == null ? null : info.serviceInfo;
                if (service == null || service.packageName == null || service.name == null) continue;
                List<ComponentName> list = byPackage.get(service.packageName);
                if (list == null) {
                    list = new ArrayList<>();
                    byPackage.put(service.packageName, list);
                }
                list.add(new ComponentName(service.packageName, service.name));
            }
        }

        List<PluginEntry> next = new ArrayList<>();
        Map<String, Integer> idCounts = new HashMap<>();
        List<PluginEntry> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Map.Entry<String, List<ComponentName>> group : byPackage.entrySet()) {
            String packageName = group.getKey();
            if (context.getPackageName().equals(packageName)) continue;
            seen.add(packageName);
            PluginFingerprint fingerprint = PluginIdentity.read(context, packageName);
            if (fingerprint == null) continue;
            boolean trusted = PluginIdentity.trusted(context, fingerprint.signerSha256);
            if (group.getValue().size() != 1) {
                PluginState state = store.discover(packageName, fingerprint, "", false, trusted);
                next.add(new PluginEntry(fingerprint, null, group.getValue().get(0), state,
                        "DUPLICATE_SERVICE", false));
                continue;
            }
            ComponentName component = group.getValue().get(0);
            ServiceInfo serviceInfo;
            try {
                serviceInfo = pm.getServiceInfo(component, PackageManager.GET_META_DATA);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                PluginState state = store.discover(packageName, fingerprint, "", false, trusted);
                next.add(new PluginEntry(fingerprint, null, component, state,
                        "MISSING_SERVICE", false));
                continue;
            }
            PluginManifestReader.Read read = PluginManifestReader.read(context, serviceInfo);
            PluginManifestParser.Result result = read.result;
            if (result == null || result.manifest == null) {
                String error = result == null ? "BAD_METADATA" : result.error;
                boolean compatible = result == null || !result.incompatible;
                PluginState state = store.discover(packageName, fingerprint, "", false, trusted);
                next.add(new PluginEntry(fingerprint, null, component, state, error, compatible));
                continue;
            }
            String capabilities = join(result.manifest.capabilities);
            PluginState state = store.discover(packageName, fingerprint, capabilities,
                    true, trusted);
            PluginEntry entry = new PluginEntry(fingerprint, result.manifest, component,
                    state, "", true);
            Integer count=idCounts.get(result.manifest.pluginId);
            idCounts.put(result.manifest.pluginId,count==null?1:count+1);
            valid.add(entry);
        }

        // Missed uninstall broadcasts are repaired by checking every known
        // plugin package against the installed package table.
        for (String known : store.knownPackages()) {
            if (seen.contains(known) || store.isReplacing(known)) continue;
            if (PluginIdentity.read(context, known) == null) {
                store.clear(known);
            }
        }

        for(PluginEntry e:valid) {
            if(idCounts.get(e.pluginId())>1) {
                PluginState denied=store.deny(e.packageName(),"DUPLICATE_PLUGIN_ID");
                next.add(new PluginEntry(e.fingerprint,e.manifest,e.service,denied,"DUPLICATE_PLUGIN_ID",false));
            } else next.add(e);
        }
        Collections.sort(next, new Comparator<PluginEntry>() {
            @Override public int compare(PluginEntry a, PluginEntry b) {
                String ida = a.pluginId();
                String idb = b.pluginId();
                return ida.compareTo(idb);
            }
        });

        String newSignature = signatureOf(next);
        List<PluginEntry> old = entries;
        for (PluginEntry entry : next) {
            PluginEntry previous = find(old, entry.packageName());
            if (previous == null || previous.fingerprint == null
                    || !previous.fingerprint.sameInstall(entry.fingerprint)
                    || (previous.usable()&&!entry.usable())) {
                HostRuntime.get(context).sessions().invalidatePackage(
                        entry.packageName(), PluginStatus.REVOKED);
            }
        }
        for(PluginEntry previous:old) if(find(next,previous.packageName())==null)
            HostRuntime.get(context).sessions().invalidatePackage(previous.packageName(),PluginStatus.REVOKED);
        entries = Collections.unmodifiableList(next);
        signature = newSignature;
    }

    private static PluginEntry find(List<PluginEntry> list, String packageName) {
        for (PluginEntry entry : list) if (entry.packageName().equals(packageName)) return entry;
        return null;
    }

    private static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (text.length() > 0) text.append(',');
            text.append(value);
        }
        return text.toString();
    }

    private static String signatureOf(List<PluginEntry> list) {
        StringBuilder text = new StringBuilder();
        for (PluginEntry entry : list) {
            text.append(entry.packageName()).append(':')
                    .append(entry.pluginId()).append(':')
                    .append(entry.fingerprint == null ? "" : entry.fingerprint.value()).append(':')
                    .append(entry.state == null ? "" : entry.state.enabled).append(':')
                    .append(entry.state == null ? "" : entry.state.readGranted).append(':')
                    .append(entry.state == null ? "" : entry.state.requestGranted).append(':')
                    .append(entry.hasError() ? entry.error : "").append(';');
        }
        return text.toString();
    }

    public PluginEntry findByPackage(String packageName) {
        for (PluginEntry entry : entries) if (entry.packageName().equals(packageName)) return entry;
        return null;
    }

    public PluginEntry findByPluginId(String pluginId) {
        for (PluginEntry entry : entries) if (entry.pluginId().equals(pluginId)) return entry;
        return null;
    }

    public PluginEntry findEnabledPage(String slot, String pageId) {
        for (PluginEntry entry : entries) {
            if (!entry.usable()) continue;
            for (PluginManifestParser.Page page : entry.manifest.pages) {
                if ((pageId == null || pageId.equals(page.id)) && slot.equals(page.slot)) return entry;
            }
        }
        return null;
    }

    public List<PluginEntry> enabledPages(String slot) {
        List<PluginEntry> out = new ArrayList<>();
        for (PluginEntry entry : entries) {
            if (!entry.usable()) continue;
            for (PluginManifestParser.Page page : entry.manifest.pages) {
                if (slot.equals(page.slot)) { out.add(entry); break; }
            }
        }
        return out;
    }

    /** Re-reads identity and state before routing or servicing a transaction. */
    public Verified verify(String packageName, String pluginId, String expectedFingerprint) {
        PluginEntry entry = findByPackage(packageName);
        if (entry == null || entry.fingerprint == null || entry.state == null) return null;
        if(entry.hasError() || !entry.compatible || entry.manifest==null)return null;
        if (!entry.pluginId().equals(pluginId)) return null;
        if (expectedFingerprint != null && !expectedFingerprint.equals(entry.fingerprint.value())) return null;
        PluginFingerprint live = PluginIdentity.read(context, packageName);
        if (live == null || !live.sameInstall(entry.fingerprint)) return null;
        if (!PluginIdentity.trusted(context, live.signerSha256)) return null;
        if (live.sharedUserId != null && !live.sharedUserId.isEmpty()) return null;
        PluginState state = store.load(packageName);
        if (state == null || !state.enabled) return null;
        if (!state.fingerprint.equals(live.value())) return null;
        return new Verified(entry, live, state);
    }
    public boolean confirm(PluginEntry displayed) {
        if(displayed==null||displayed.manifest==null||displayed.fingerprint==null||displayed.state==null)return false;
        PluginEntry current=displayed==null?null:findByPackage(displayed.packageName());
        if(current==null || current.hasError() || !current.compatible || current.manifest==null
                || !current.fingerprint.sameInstall(displayed.fingerprint)
                || !current.manifest.capabilities.equals(displayed.manifest.capabilities))return false;
        return store.confirm(displayed);
    }

    public static final class Verified {
        public final PluginEntry entry;
        public final PluginFingerprint fingerprint;
        public final PluginState state;

        Verified(PluginEntry entry, PluginFingerprint fingerprint, PluginState state) {
            this.entry = entry;
            this.fingerprint = fingerprint;
            this.state = state;
        }
    }
}
