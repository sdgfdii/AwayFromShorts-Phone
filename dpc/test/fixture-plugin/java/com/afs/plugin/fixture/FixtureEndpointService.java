package com.afs.plugin.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import com.afs.pluginapi.IPluginEndpoint;
import com.afs.pluginapi.IPrepareCallback;
import com.afs.pluginapi.PageLaunch;
import com.afs.pluginapi.PluginStatus;
import com.afs.pluginapi.PrepareResult;

/** Fixture endpoint: only proves generic discovery and prepare routing. */
public final class FixtureEndpointService extends Service {
    private final IPluginEndpoint.Stub binder = new IPluginEndpoint.Stub() {
        @Override public void preparePage(PageLaunch launch, IPrepareCallback callback) {
            if (BuildFlags.PREPARE_BEHAVIOR == 2) return; // intentionally never ready, test APK only
            try {
                callback.onResult(new PrepareResult(launch == null ? 0L : launch.requestId,
                        BuildFlags.PREPARE_BEHAVIOR == 1 ? PluginStatus.FAILED : PluginStatus.OK, "fixture"));
            } catch (RemoteException ignored) { }
        }

        @Override public void closeSession(String leaseId, int reason) { }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }
}
