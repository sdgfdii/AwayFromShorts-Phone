package com.afs.host.plugins;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.afs.pluginapi.PluginStatus;

/**
 * Global package directory maintenance. These are protected system
 * broadcasts; a plugin being disabled never unregisters this receiver.
 */
public final class HostPluginReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if(!Intent.ACTION_PACKAGE_ADDED.equals(action)&&!Intent.ACTION_PACKAGE_REPLACED.equals(action)
                &&!Intent.ACTION_PACKAGE_REMOVED.equals(action)&&!Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action))return;
        String packageName = intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
        if (packageName == null || packageName.isEmpty()) {
            HostRuntime.get(context).registry().refreshAsync(null);
            return;
        }
        if (context.getPackageName().equals(packageName)) {
            HostRuntime.get(context).registry().refreshAsync(null);
            return;
        }
        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        HostRuntime runtime = HostRuntime.get(context);
        boolean known=runtime.registry().stateStore().knownPackages().contains(packageName);
        if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action) || (Intent.ACTION_PACKAGE_REMOVED.equals(action) && !replacing)) {
            runtime.sessions().invalidatePackage(packageName, PluginStatus.REVOKED);
            if(known)runtime.registry().stateStore().clear(packageName);
            runtime.registry().refreshAsync(null);
        } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            // The ADDED/REPLACED broadcast follows; invalidate live sessions
            // now, but do not clear grants as if this were an uninstall.
            runtime.sessions().invalidatePackage(packageName, PluginStatus.REVOKED);
            if(known)runtime.registry().stateStore().markReplacing(packageName,
                    System.currentTimeMillis() + 60_000L);
        } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            if(known)runtime.registry().stateStore().markReplacing(packageName, 0L);
            runtime.sessions().invalidatePackage(packageName, PluginStatus.REVOKED);
            runtime.registry().refreshAsync(null);
        } else {
            runtime.registry().refreshAsync(null);
        }
    }
}
