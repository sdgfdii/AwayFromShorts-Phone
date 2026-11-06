package com.afs.host.plugins;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Base64;
import com.afs.host.usage.HostUsageStats;
import com.afs.pluginapi.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Optional-page broker. No Guard thread, lock or business store depends on it.
 * Outgoing replies and revocation barriers share a bounded serial dispatcher.
 * invalidate marks sessions unavailable immediately; the dispatcher orders the
 * final revocation against already admitted sends, without waiting on the UI.
 */
public final class HostSessions {
    private static final int MAX_SESSIONS = 8, MAX_TICKETS = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Object book = new Object();
    private final Context context;
    private final PluginRegistry registry;
    private final int processGeneration = RANDOM.nextInt() | 1;
    private final Map<String, LaunchTicket> tickets = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final ConcurrentLinkedQueue<Session> retired = new ConcurrentLinkedQueue<>();
    private final Set<Session> retiring = new HashSet<>(); // book: includes barriers not yet acknowledged
    private final Set<Operation> operations = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor control = executor(1, 64, "afs-plugin-control");
    private final ThreadPoolExecutor errors = executor(1, 8, "afs-plugin-reject");
    private final ThreadPoolExecutor workers = executor(PluginProtocol.WORKER_THREADS,
            PluginProtocol.QUEUE_SLOTS, "afs-plugin-query");
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> thread(r, "afs-plugin-deadline"));
    private ScheduledFuture<?> expiryTask;
    private long expiryAt;

    private static Thread thread(Runnable r, String name) {
        Thread t = new Thread(r, name); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
    }
    private static ThreadPoolExecutor executor(int n, int capacity, String name) {
        ThreadPoolExecutor e = new ThreadPoolExecutor(n, n, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(capacity), r -> thread(r, name), new ThreadPoolExecutor.AbortPolicy());
        e.allowCoreThreadTimeOut(true); return e;
    }
    HostSessions(Context context, PluginRegistry registry) {
        this.context = context.getApplicationContext(); this.registry = registry;
        timer.setRemoveOnCancelPolicy(true);
        timer.setKeepAliveTime(30,TimeUnit.SECONDS);
        timer.allowCoreThreadTimeOut(true);
    }
    public static final class Access {
        public final int status; public final PageLease lease;
        Access(int s, PageLease l) { status=s; lease=l; }
    }
    private boolean dispatch(Runnable r) {
        try { control.execute(() -> { try { r.run(); } finally { drainInternal(); } }); return true; }
        catch (RejectedExecutionException full) { return false; }
    }
    private void wake() { dispatch(() -> { }); }
    // Internal events have fixed per-operation mailboxes, never a fallible queue
    // item. A full external queue guarantees a wrapper will drain these slots.
    private void drainInternal() {
        Session old;
        while ((old=retired.poll()) != null) {
            for(Operation op:old.admitted.values()) abort(op);
            old.active=null; old.pending=null;
            old.revocation.complete(null);
            synchronized(book) { retiring.remove(old); }
        }
        for (Operation op:operations) {
            if (op.aborted || op.session.revoked) finish(op,PluginStatus.REVOKED,null);
            else if (op.expired || SystemClock.elapsedRealtime() >= op.deadline) finish(op,PluginStatus.TIMEOUT,null);
            else if (op.ready) finish(op,op.status,op.summary);
        }
    }
    private void reject(Runnable r) {
        try { errors.execute(r); } catch (RejectedExecutionException full) { /* bounded drop; SDK deadline resolves */ }
    }
    public LaunchTicket issueTicket(PluginEntry entry, PluginManifestParser.Page page) {
        if (entry == null || page == null) return null;
        synchronized (book) {
            expire();
            PluginRegistry.Verified v = registry.verify(entry.packageName(), entry.pluginId(), entry.fingerprint.value());
            if (v == null || tickets.size() >= MAX_TICKETS) return null;
            int count=0; for (LaunchTicket t:tickets.values()) if (t.uid == v.fingerprint.uid) count++;
            if (count >= 2) return null;
            long now=SystemClock.elapsedRealtime();
            LaunchTicket t=new LaunchTicket(randomId(), entry.pluginId(), entry.packageName(), page.id,
                    PluginProtocol.PROTOCOL, v.fingerprint.uid, processGeneration, now,
                    now+PluginProtocol.TICKET_TTL_MS, v.state.installGeneration, v.state.enabledGeneration,
                    v.state.readGeneration, v.state.requestGeneration, v.fingerprint.value());
            tickets.put(t.ticketId,t); scheduleExpiry(); return t;
        }
    }
    public void open(LaunchTicket input, int uid, IPageClient client, IOpenCallback cb) {
        if (cb == null) return;
        if (!dispatch(() -> openNow(input,uid,client,cb))) reject(() -> opened(cb,PluginStatus.BUSY,null));
    }
    private void openNow(LaunchTicket input,int uid,IPageClient client,IOpenCallback cb) {
        int status=PluginStatus.INVALID_TICKET; PageLease result=null;
        synchronized (book) {
            expire();
            LaunchTicket t=input == null ? null : tickets.get(input.ticketId);
            // A wrong UID cannot consume another caller's ticket. All other input fields are ignored.
            if (t != null && (t.uid != uid || !t.packageName.equals(packageForUid(uid)))) status=PluginStatus.BAD_CALLER;
            else if (t != null && client != null) {
                tickets.remove(t.ticketId); // atomic single consumption, before death registration
                PluginRegistry.Verified v=registry.verify(t.packageName,t.pluginId,t.installFingerprint);
                if (SystemClock.elapsedRealtime() >= t.expiresElapsedMs) status=PluginStatus.EXPIRED;
                else if (v == null || v.state.installGeneration != t.installGeneration
                        || v.state.enabledGeneration != t.enabledGeneration) status=PluginStatus.REVOKED;
                else {
                    boolean busy=sessions.size() >= MAX_SESSIONS;
                    for (Session s:sessions.values()) if (s.lease.uid == uid) busy=true;
                    if (busy) status=PluginStatus.BUSY;
                    else {
                        long now=SystemClock.elapsedRealtime();
                        PageLease l=new PageLease(randomId(),t.pluginId,t.packageName,t.pageId,PluginProtocol.PROTOCOL,
                                uid,processGeneration,now,now+PluginProtocol.LEASE_TTL_MS,
                                v.state.installGeneration,v.state.enabledGeneration,v.state.readGeneration,
                                v.state.requestGeneration,v.fingerprint.value());
                        Session s=new Session(l,client);
                        try {
                            client.asBinder().linkToDeath(s.death,0);
                            if (s.revoked || !client.asBinder().isBinderAlive()) {
                                unlink(s); status=PluginStatus.UNAVAILABLE;
                            } else {
                                sessions.put(l.leaseId,s); result=l; status=PluginStatus.OK;
                            }
                        } catch (RemoteException | RuntimeException dead) { status=PluginStatus.UNAVAILABLE; }
                    }
                }
            }
            scheduleExpiry();
        }
        opened(cb,status,result); // Never call a remote Binder under book/UI locks.
    }
    private void opened(IOpenCallback cb,int status,PageLease l) {
        try { cb.onResult(new OpenResult(status,l,"")); }
        catch (RemoteException | RuntimeException dead) { if (l != null) close(l,l.uid); }
    }
    public void load(PageLease input,int uid,long id,IUsageCallback cb) {
        if (cb == null) return;
        Session s=find(input);
        int status=s == null ? PluginStatus.REVOKED : caller(s,input,uid) ? PluginStatus.OK : PluginStatus.BAD_CALLER;
        if(id==0) status=PluginStatus.FAILED;
        if(status!=PluginStatus.OK) {final int code=status;reject(() -> respond(cb,id,code,null));return;}
        Operation op=new Operation(s,id,cb);
        synchronized(book) {
            if(sessions.get(s.lease.leaseId)!=s || s.revoked) {reject(() -> respond(cb,id,PluginStatus.REVOKED,null));return;}
            if(s.admitted.size()>=2 || s.admitted.containsKey(id)) { reject(() -> respond(cb,id,PluginStatus.BUSY,null));return; }
            s.admitted.put(id,op);
            operations.add(op); // atomically registered with the session's revocation barrier
            op.timeout=timer.schedule(() -> { op.expired=true; wake(); },PluginProtocol.QUERY_BUDGET_MS,TimeUnit.MILLISECONDS);
        }
        if(op.aborted) op.timeout.cancel(false);
        if (!dispatch(() -> loadNow(op))) {abort(op);reject(() -> respond(cb,id,PluginStatus.BUSY,null));}
    }
    private void loadNow(Operation op) {
        Session s=op.session;
        if(op.aborted || s.revoked) {abort(op);return;}
        int status=live(s,true);
        if(status!=PluginStatus.OK) {abort(op);respond(op.cb,op.id,status,null);return;}
        if (s.active == null) { s.active=op; start(op); } else s.pending=op;
    }
    private void start(Operation op) {
        if (op.aborted || op.session.revoked) { finish(op,PluginStatus.REVOKED,null); return; }
        try { workers.execute(op); } catch (RejectedExecutionException full) { finish(op,PluginStatus.BUSY,null); }
    }
    private final class Operation implements Runnable {
        final Session session; final long id; final IUsageCallback cb;
        final long deadline=SystemClock.elapsedRealtime()+PluginProtocol.QUERY_BUDGET_MS;
        volatile boolean aborted,expired,ready; volatile int status; volatile UsageSummary summary;
        volatile ScheduledFuture<?> timeout;
        Operation(Session s,long id,IUsageCallback cb) {session=s;this.id=id;this.cb=cb;}
        public void run() {
            if (aborted || session.revoked || SystemClock.elapsedRealtime() >= deadline) return;
            int status=live(session,true); UsageSummary summary=null;
            if (status == PluginStatus.OK && !aborted && !session.revoked) {
                try { summary=HostUsageStats.collect(context); }
                catch (RuntimeException | LinkageError unavailable) { status=PluginStatus.UNAVAILABLE; }
            }
            this.status=status; this.summary=summary; ready=true; wake();
        }
    }
    // Only the control dispatcher owns active/pending and sends successful replies.
    // Object identity is the operation token; stale workers/timers cannot finish a reused id.
    private void finish(Operation op,int status,UsageSummary summary) {
        Session s=op.session;
        if (s.active != op && s.pending != op) {
            boolean canceled=op.aborted; abort(op);
            if(!canceled && !s.revoked) respond(op.cb,op.id,status,null);
            return;
        }
        boolean canceled=op.aborted;
        abort(op);
        boolean active=s.active == op;
        if (active) s.active=null; else s.pending=null;
        if (!s.revoked && !canceled) {
            int current=live(s,true);
            if (current != PluginStatus.OK) { status=current; summary=null; }
            if (SystemClock.elapsedRealtime() >= op.deadline && status == PluginStatus.OK) status=PluginStatus.TIMEOUT;
            if (!s.revoked) respond(op.cb,op.id,status,status == PluginStatus.OK ? summary : null);
        }
        if (active && !s.revoked && s.pending != null) { s.active=s.pending; s.pending=null; start(s.active); }
    }
    private void abort(Operation op) {
        if (op == null) return; op.aborted=true;
        if (op.timeout != null) op.timeout.cancel(false); workers.remove(op);
        operations.remove(op);
        op.session.admitted.remove(op.id,op);
    }
    public void cancel(PageLease input,int uid,long id) {
        Session s=find(input); if (s == null || !caller(s,input,uid)) return;
        Operation op=s.admitted.get(id);
        if (op != null) op.aborted=true;
        wake();
    }
    public void close(PageLease input,int uid) {
        Session s=find(input); if (s != null && caller(s,input,uid)) revoke(s);
    }
    public Access validateAccess(String pkg,String id,int uid) {
        Session s; synchronized(book) { s=sessions.get(id); }
        if (s == null) return new Access(PluginStatus.REVOKED,null);
        if (uid != s.lease.uid || !s.lease.packageName.equals(pkg) || !pkg.equals(packageForUid(uid)))
            return new Access(PluginStatus.BAD_CALLER,null);
        int status=live(s,false);
        return new Access(status,status == PluginStatus.OK ? s.lease : null);
    }
    private int live(Session s,boolean read) {
        PageLease l=s.lease;
        if (s.revoked) return PluginStatus.REVOKED;
        if (SystemClock.elapsedRealtime() >= l.expiresElapsedMs) return PluginStatus.EXPIRED;
        PluginRegistry.Verified v=registry.verify(l.packageName,l.pluginId,l.installFingerprint);
        if (v == null || v.fingerprint.uid != l.uid || v.state.installGeneration != l.installGeneration
                || v.state.enabledGeneration != l.enabledGeneration) return PluginStatus.REVOKED;
        if (read) {
            if (!v.state.readGranted || v.state.readGeneration != l.readGrantGeneration) return PluginStatus.NO_GRANT;
            if (!HostUsageStats.hasUsageAccess(context)) return PluginStatus.PERMISSION_REQUIRED;
        } else if (!v.state.requestGranted || v.state.requestGeneration != l.requestGrantGeneration) return PluginStatus.NO_GRANT;
        return s.revoked ? PluginStatus.REVOKED : PluginStatus.OK;
    }
    private Session find(PageLease input) { synchronized(book) {return input == null ? null : sessions.get(input.leaseId);} }
    private boolean caller(Session s,PageLease input,int uid) {
        // Never authorize with fields supplied by the remote caller.
        return input != null && uid == s.lease.uid && s.lease.packageName.equals(packageForUid(uid));
    }
    public List<String> activeLeaseIds(String pkg) {
        ArrayList<String> ids=new ArrayList<>(); synchronized(book) {
            for (Session s:sessions.values()) if (s.lease.packageName.equals(pkg) && !s.revoked) ids.add(s.lease.leaseId);
        } return ids;
    }
    public CompletableFuture<Void> invalidatePackage(String pkg,int reason) {
        ArrayList<CompletableFuture<Void>> barriers=new ArrayList<>();
        synchronized(book) {
            tickets.entrySet().removeIf(e -> e.getValue().packageName.equals(pkg));
            for (Session s:new ArrayList<>(sessions.values())) if (s.lease.packageName.equals(pkg)) {
                barriers.add(s.revocation); revoke(s);
            }
            for(Session s:retiring) if(s.lease.packageName.equals(pkg) && !barriers.contains(s.revocation)) barriers.add(s.revocation);
            scheduleExpiry();
        }
        return CompletableFuture.allOf(barriers.toArray(new CompletableFuture<?>[0]));
    }
    public void invalidateAll(int reason) {
        synchronized(book) { tickets.clear(); for (Session s:new ArrayList<>(sessions.values())) revoke(s); scheduleExpiry(); }
    }
    private void revoke(Session s) {
        s.revoked=true; // cheap immediate admission barrier, including Binder-death-before-registration
        synchronized(book) {
            sessions.remove(s.lease.leaseId);
            if (s.retired) return;
            s.retired=true; retiring.add(s); retired.add(s); scheduleExpiry();
        }
        unlink(s);
        wake(); // ack completes on the same dispatcher that owns all successful sends
    }
    private void unlink(Session s) { try { s.client.asBinder().unlinkToDeath(s.death,0); } catch(RuntimeException ignored){} }
    private void expire() { // book held; one timer for the earliest ticket/lease, no polling
        long now=SystemClock.elapsedRealtime();
        tickets.entrySet().removeIf(e -> now >= e.getValue().expiresElapsedMs);
        for (Session s:new ArrayList<>(sessions.values())) if (now >= s.lease.expiresElapsedMs) revoke(s);
    }
    private void scheduleExpiry() { // book held; no periodic polling while plugins are idle
        long next=Long.MAX_VALUE;
        for(LaunchTicket t:tickets.values())next=Math.min(next,t.expiresElapsedMs);
        for(Session s:sessions.values())next=Math.min(next,s.lease.expiresElapsedMs);
        if(expiryTask!=null && next==expiryAt)return;
        if(expiryTask!=null)expiryTask.cancel(false);
        expiryTask=null;expiryAt=next;
        if(next==Long.MAX_VALUE)return;
        expiryTask=timer.schedule(() -> {synchronized(book){expiryTask=null;expire();scheduleExpiry();}},
                Math.max(1,next-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
    }
    private String packageForUid(int uid) {
        try { String[] p=context.getPackageManager().getPackagesForUid(uid); return p != null && p.length == 1 ? p[0] : null; }
        catch(RuntimeException denied) { return null; }
    }
    private void respond(IUsageCallback cb,long id,int status,UsageSummary summary) {
        if (cb == null) return;
        UsageResult value=new UsageResult(id,status,status == PluginStatus.OK ? summary : null,"");
        Parcel p=Parcel.obtain();
        try { value.writeToParcel(p,0); if(p.dataSize()>PluginProtocol.MAX_TRANSACTION) value=new UsageResult(id,PluginStatus.UNAVAILABLE,null,""); }
        catch(RuntimeException bad) { value=new UsageResult(id,PluginStatus.UNAVAILABLE,null,""); }
        finally {p.recycle();}
        try {cb.onResult(value);} catch(RemoteException | RuntimeException ignored){}
    }
    private static String randomId() {byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.encodeToString(b,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    private final class Session {
        final PageLease lease; final IPageClient client; volatile boolean revoked;
        boolean retired;
        final CompletableFuture<Void> revocation=new CompletableFuture<>();
        volatile Operation active,pending;
        final ConcurrentMap<Long,Operation> admitted=new ConcurrentHashMap<>();
        final IBinder.DeathRecipient death=() -> revoke(this);
        Session(PageLease lease,IPageClient client){this.lease=lease;this.client=client;}
    }
}
