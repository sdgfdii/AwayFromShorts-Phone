package com.afs.plugin.usagestats;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import com.afs.pluginapi.HostTrust;
import com.afs.pluginapi.IPluginEndpoint;
import com.afs.pluginapi.IPrepareCallback;
import com.afs.pluginapi.PageLaunch;
import com.afs.pluginapi.PluginProtocol;
import com.afs.pluginapi.PluginStatus;
import com.afs.pluginapi.PrepareResult;

/** Plugin-side prepare endpoint. The host has already validated the APK. */
public final class PluginEndpointService extends Service {
    private final IPluginEndpoint.Stub binder = new IPluginEndpoint.Stub() {
        @Override public boolean onTransact(int code,android.os.Parcel data,android.os.Parcel reply,int flags)throws RemoteException{
            if(code!=IBinder.INTERFACE_TRANSACTION&&(data==null||data.dataSize()>PluginProtocol.MAX_TRANSACTION||!isHost(Binder.getCallingUid())))return false;
            try{if(!com.afs.pluginapi.WireFrame.valid("com.afs.pluginapi.IPluginEndpoint",code,data))return false;return super.onTransact(code,data,reply,flags);}catch(RuntimeException malformed){return false;}
        }
        @Override public void preparePage(final PageLaunch launch, final IPrepareCallback callback) {
            final int caller = Binder.getCallingUid();
            final long requestId = launch == null ? 0L : launch.requestId;
            if (launch == null || launch.protocol != PluginProtocol.PROTOCOL
                    || !"screen".equals(launch.pageId)) {
                finish(callback, requestId, PluginStatus.INCOMPATIBLE, "unsupported page");
                return;
            }
            if (!isHost(caller)) {
                finish(callback, requestId, PluginStatus.FAILED, "bad caller");
                return;
            }
            finish(callback, requestId, PluginStatus.OK, ""); // already asynchronous via oneway Binder
        }

        @Override public void closeSession(String leaseId, int reason) {
            // The host already revoked its lease. This page has no persistent
            // state to clear; the next host transaction will be rejected.
        }
    };

    private boolean isHost(int uid) {
        int expected = HostTrust.uidOf(this, BuildFlags.HOST_PACKAGE);
        return expected == uid
                && HostTrust.verify(this, BuildFlags.HOST_PACKAGE,
                BuildFlags.HOST_TRUST_SHA256, BuildFlags.TEST_MODE);
    }

    private void finish(IPrepareCallback callback, long requestId, int status, String message) {
        if (callback == null) return;
        try { callback.onResult(new PrepareResult(requestId, status, message)); }
        catch (RemoteException | RuntimeException ignored) { }
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

}
