package com.afs.pluginapi;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Parcel;

/**
 * Minimal plugin-side SDK for protocol 1. It binds only to the explicit host
 * package, verifies the host signing certificate, and re-checks the real
 * callback UID, request id and lease on every result.
 */
public final class PluginClient {
    public interface OpenCallback {
        void onResult(int status, PageLease lease, String message);
    }

    public interface UsageCallback {
        void onResult(int status, UsageSummary summary, String message);
    }

    private interface Ready {
        void run(boolean available);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String expectedSha;
    private final boolean testMode;
    private volatile IHostStatsBridge bridge;
    private volatile int hostUid = -1;
    private boolean binding;
    private volatile boolean destroyed;
    private int bindingGeneration;
    private Connection connection;
    private final IPageClient anchor = new IPageClient.Stub() { };
    private final java.util.ArrayList<Ready> pending = new java.util.ArrayList<>();
    private static final long BIND_TIMEOUT_MS = 3_000L;
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Set<Completion> completions=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Completion,Boolean>());
    private final class Completion {
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable deadline;
        final boolean accepted;
        Completion(long timeout, Runnable failure) {
            accepted = inFlight.incrementAndGet() <= 8;
            if (!accepted) { inFlight.decrementAndGet(); done.set(true); }
            deadline = () -> { if (finish() && !destroyed) failure.run(); };
            if (accepted) {completions.add(this);main.postDelayed(deadline, timeout);}
        }
        boolean finish() {
            if (!done.compareAndSet(false, true)) return false;
            inFlight.decrementAndGet(); main.removeCallbacks(deadline); completions.remove(this);return true;
        }
    }

    private final class Connection implements ServiceConnection {
        final int generation;
        boolean registered;
        Connection(int generation){this.generation=generation;}
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if(connection!=this || generation!=bindingGeneration){unbind(this);return;}
            binding = false;
            if (destroyed || name == null
                    || !PluginProtocol.HOST_PACKAGE.equals(name.getPackageName())
                    || !HostTrust.verify(context, name.getPackageName(), expectedSha, testMode)) {
                bridge = null;
                hostUid = -1;
                unbind(this);
                notifyPending(false);
                return;
            }
            hostUid = HostTrust.uidOf(context, name.getPackageName());
            bridge = IHostStatsBridge.Stub.asInterface(binder);
            notifyPending(bridge != null);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if(connection!=this || generation!=bindingGeneration)return;
            binding = false;
            bridge = null;
            hostUid = -1;
            notifyPending(false);
        }
        @Override public void onNullBinding(ComponentName name){if(connection==this){unbind(this);notifyPending(false);}}
        @Override public void onBindingDied(ComponentName name){if(connection==this){unbind(this);notifyPending(false);}}
    }

    public PluginClient(Context context, String expectedHostSha256, boolean testMode) {
        this.context = context.getApplicationContext();
        this.expectedSha = expectedHostSha256 == null ? "" : expectedHostSha256;
        this.testMode = testMode;
    }

    public int hostUid() { return hostUid; }

    public void open(final LaunchTicket ticket, final OpenCallback callback) {
        if (ticket == null || callback == null) return;
        if (!context.getPackageName().equals(ticket.packageName)) {
            callback.onResult(PluginStatus.BAD_CALLER, null, "ticket package mismatch");
            return;
        }
        final Completion completion = new Completion(5_000L,
                () -> callback.onResult(PluginStatus.TIMEOUT, null, "host deadline"));
        if (!completion.accepted) { callback.onResult(PluginStatus.BUSY, null, "busy"); return; }
        ensureBound(available -> {
            if (completion.done.get()) return;
            if (!available) { if (completion.finish()) callback.onResult(PluginStatus.UNAVAILABLE, null, "host unavailable"); return; }
            IOpenCallback.Stub cb = new IOpenCallback.Stub() {
                @Override public boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
                    if(code!=IBinder.INTERFACE_TRANSACTION&&!acceptCallback(data))return false;
                    try{if(!WireFrame.valid("com.afs.pluginapi.IOpenCallback",code,data))return false;return super.onTransact(code,data,reply,flags);}catch(RuntimeException malformed){return false;}
                }
                @Override public void onResult(final OpenResult result) {
                    if (destroyed || result == null) return;
                    if (android.os.Binder.getCallingUid() != hostUid) return;
                    if (result.lease != null && !context.getPackageName().equals(result.lease.packageName)) return;
                    main.post(() -> { if (completion.finish() && !destroyed) callback.onResult(result.status, result.lease, result.message); });
                }
            };
            try {
                IHostStatsBridge current=bridge;
                if(current==null)throw new RemoteException("host disconnected");
                current.openSession(ticket, anchor, cb);
            } catch (RemoteException e) {
                if (completion.finish()) callback.onResult(PluginStatus.UNAVAILABLE, null, "host died");
            }
        });
    }

    public void load(final PageLease lease, final long requestId, final UsageCallback callback) {
        if (lease == null || callback == null) return;
        final Completion completion = new Completion(7_000L,
                () -> callback.onResult(PluginStatus.TIMEOUT, null, "host deadline"));
        if (!completion.accepted) { callback.onResult(PluginStatus.BUSY, null, "busy"); return; }
        ensureBound(available -> {
            if (completion.done.get()) return;
            if (!available) { if (completion.finish()) callback.onResult(PluginStatus.UNAVAILABLE, null, "host unavailable"); return; }
            IUsageCallback.Stub cb = new IUsageCallback.Stub() {
                @Override public boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
                    if(code!=IBinder.INTERFACE_TRANSACTION&&!acceptCallback(data))return false;
                    try{if(!WireFrame.valid("com.afs.pluginapi.IUsageCallback",code,data))return false;return super.onTransact(code,data,reply,flags);}catch(RuntimeException malformed){return false;}
                }
                @Override public void onResult(final UsageResult result) {
                    if (destroyed || result == null) return;
                    if (android.os.Binder.getCallingUid() != hostUid) return;
                    if (result.requestId != requestId) return;
                    main.post(() -> { if (completion.finish() && !destroyed) callback.onResult(result.status, result.summary, result.message); });
                }
            };
            try {
                IHostStatsBridge current=bridge;
                if(current==null)throw new RemoteException("host disconnected");
                current.loadCurrentUsageSummary(lease, requestId, cb);
            } catch (RemoteException e) {
                if (completion.finish()) callback.onResult(PluginStatus.UNAVAILABLE, null, "host died");
            }
        });
    }

    public void cancel(PageLease lease, long requestId) {
        IHostStatsBridge current = bridge;
        if (current == null || lease == null) return;
        try { current.cancelRequest(lease, requestId); } catch (RemoteException ignored) { }
    }

    public void close(PageLease lease) {
        IHostStatsBridge current = bridge;
        if (current == null || lease == null) return;
        try { current.closeSession(lease); } catch (RemoteException ignored) { }
    }

    public void destroy() {
        destroyed = true;
        bridge = null;
        hostUid = -1;
        for(Completion completion:completions)completion.finish();
        if(Looper.myLooper()==Looper.getMainLooper())unbind(connection);
        else main.post(()->unbind(connection));
        synchronized (pending) { pending.clear(); }
    }
    private boolean acceptCallback(Parcel data){return !destroyed&&android.os.Binder.getCallingUid()==hostUid&&data!=null&&data.dataSize()<=PluginProtocol.MAX_TRANSACTION;}

    private void unbind(Connection target) {
        if(target==null)return;
        if (target.registered) {
            try { context.unbindService(target); } catch (RuntimeException ignored) { }
        }
        target.registered=false;
        if(connection==target){connection=null;binding=false;bridge=null;hostUid=-1;}
    }

    private void ensureBound(final Ready ready) {
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->ensureBound(ready));return;}
        if (destroyed) { ready.run(false); return; }
        if (bridge != null) { ready.run(true); return; }
        synchronized (pending) {
            if (pending.size() >= 8) { ready.run(false); return; }
            pending.add(ready);
        }
        if (binding) return;
        unbind(connection);
        Connection attempt=new Connection(++bindingGeneration);connection=attempt;binding=true;
        Intent intent = new Intent(PluginProtocol.HOST_BRIDGE_ACTION).setPackage(PluginProtocol.HOST_PACKAGE);
        boolean started;
        try {
            started = context.bindService(intent, attempt, Context.BIND_AUTO_CREATE);
            attempt.registered = started;
        } catch (RuntimeException denied) {
            started = false;
        }
        if (!started) {
            unbind(attempt);
            notifyPending(false);
            return;
        }
        main.postDelayed(() -> {
            if (connection!=attempt || attempt.generation!=bindingGeneration || bridge != null || destroyed) return;
            unbind(attempt);
            notifyPending(false);
        }, BIND_TIMEOUT_MS);
    }

    private void notifyPending(boolean available) {
        java.util.ArrayList<Ready> copy;
        synchronized (pending) {
            copy = new java.util.ArrayList<>(pending);
            pending.clear();
        }
        for (Ready ready : copy) ready.run(available);
    }
}
