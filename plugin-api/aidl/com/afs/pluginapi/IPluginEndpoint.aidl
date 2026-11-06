package com.afs.pluginapi;

import com.afs.pluginapi.PageLaunch;
import com.afs.pluginapi.IPrepareCallback;

/** Host -> plugin page preparation endpoint, protocol 1. */
oneway interface IPluginEndpoint {
    void preparePage(in PageLaunch launch, in IPrepareCallback cb);
    void closeSession(in String leaseId, int reason);
}
