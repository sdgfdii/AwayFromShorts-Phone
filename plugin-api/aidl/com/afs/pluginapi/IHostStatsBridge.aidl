package com.afs.pluginapi;

import com.afs.pluginapi.LaunchTicket;
import com.afs.pluginapi.PageLease;
import com.afs.pluginapi.IPageClient;
import com.afs.pluginapi.IOpenCallback;
import com.afs.pluginapi.IUsageCallback;

/**
 * Host -> plugin data bridge, protocol 1.
 *
 * Every method is oneway. The host captures Binder.getCallingUid() before
 * dispatching work and validates the caller's installed package, signing
 * certificate, ticket/lease, enabled generation and grant generations.
 */
oneway interface IHostStatsBridge {
    void openSession(in LaunchTicket ticket, in IPageClient client, in IOpenCallback cb);
    void loadCurrentUsageSummary(in PageLease lease, long requestId, in IUsageCallback cb);
    void cancelRequest(in PageLease lease, long requestId);
    void closeSession(in PageLease lease);
}
