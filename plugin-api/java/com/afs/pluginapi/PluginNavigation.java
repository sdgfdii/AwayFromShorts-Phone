package com.afs.pluginapi;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;

/** Explicit, result-style navigation from a plugin page to the host grant page. */
public final class PluginNavigation {
    public static final String HOST_USAGE_ACCESS_ACTIVITY =
            "com.afs.host.plugins.HostUsageAccessActivity";

    private PluginNavigation() {}

    public static boolean openUsageAccessForResult(Activity caller, PageLease lease, int requestCode) {
        if (caller == null || lease == null) return false;
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(PluginProtocol.HOST_PACKAGE, HOST_USAGE_ACCESS_ACTIVITY));
        intent.setData(new android.net.Uri.Builder().scheme("afs-plugin").authority("usage-access").appendPath(lease.leaseId).build());
        try {
            caller.startActivityForResult(intent, requestCode);
            return true;
        } catch (RuntimeException denied) {
            return false;
        }
    }
}
