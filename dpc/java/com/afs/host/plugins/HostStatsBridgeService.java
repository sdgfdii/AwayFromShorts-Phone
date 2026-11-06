package com.afs.host.plugins;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.afs.pluginapi.IHostStatsBridge;
import com.afs.pluginapi.IOpenCallback;
import com.afs.pluginapi.IPageClient;
import com.afs.pluginapi.IUsageCallback;
import com.afs.pluginapi.LaunchTicket;
import com.afs.pluginapi.PageLease;
import com.afs.pluginapi.PluginProtocol;

/**
 * Signature-protected Binder endpoint. The real caller UID is captured in
 * onTransact before any dispatch; a worker never reads getCallingUid().
 */
public final class HostStatsBridgeService extends Service {

    private final ThreadLocal<Integer> callerUid = new ThreadLocal<>();
    private HostSessions sessions;

    private final IHostStatsBridge.Stub binder = new IHostStatsBridge.Stub() {
        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (data != null && data.dataSize() > PluginProtocol.MAX_TRANSACTION) return false;
            callerUid.set(Binder.getCallingUid());
            try {
                if(!com.afs.pluginapi.WireFrame.valid("com.afs.pluginapi.IHostStatsBridge",code,data))return false;
                return super.onTransact(code, data, reply, flags);
            } catch(RuntimeException malformed) {
                return false; // fixed-size protocol rejects bad parcels without crashing the optional bridge
            } finally {
                callerUid.remove();
            }
        }

        @Override public void openSession(LaunchTicket ticket, IPageClient client, IOpenCallback cb) {
            sessions.open(ticket, uid(), client, cb);
        }

        @Override public void loadCurrentUsageSummary(PageLease lease, long requestId, IUsageCallback cb) {
            sessions.load(lease, uid(), requestId, cb);
        }

        @Override public void cancelRequest(PageLease lease, long requestId) {
            sessions.cancel(lease, uid(), requestId);
        }

        @Override public void closeSession(PageLease lease) {
            sessions.close(lease, uid());
        }

        private int uid() {
            Integer value = callerUid.get();
            return value == null ? -1 : value;
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        sessions = HostRuntime.get(this).sessions();
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }
}
