package com.afs.pluginapi;

import android.os.Parcel;
import android.os.Parcelable;

/** In-memory page lease, at most 15 minutes. Opaque to the plugin. */
public final class PageLease implements Parcelable {
    public final String leaseId;
    public final String pluginId;
    public final String packageName;
    public final String pageId;
    public final int protocol;
    public final int uid;
    public final int hostGeneration;
    public final long issuedElapsedMs;
    public final long expiresElapsedMs;
    public final long installGeneration;
    public final long enabledGeneration;
    public final long readGrantGeneration;
    public final long requestGrantGeneration;
    public final String installFingerprint;

    public PageLease(String leaseId, String pluginId, String packageName, String pageId,
                     int protocol, int uid, int hostGeneration, long issuedElapsedMs,
                     long expiresElapsedMs, long installGeneration, long enabledGeneration,
                     long readGrantGeneration, long requestGrantGeneration,
                     String installFingerprint) {
        this.leaseId = ParcelSafe.fit(leaseId, PluginProtocol.MAX_ID);
        this.pluginId = ParcelSafe.fit(pluginId, PluginProtocol.MAX_ID);
        this.packageName = ParcelSafe.fit(packageName, PluginProtocol.MAX_ID);
        this.pageId = ParcelSafe.fit(pageId, PluginProtocol.MAX_ID);
        this.protocol = protocol;
        this.uid = uid;
        this.hostGeneration = hostGeneration;
        this.issuedElapsedMs = Math.max(0L, issuedElapsedMs);
        this.expiresElapsedMs = Math.max(0L, expiresElapsedMs);
        this.installGeneration = Math.max(0L, installGeneration);
        this.enabledGeneration = Math.max(0L, enabledGeneration);
        this.readGrantGeneration = Math.max(0L, readGrantGeneration);
        this.requestGrantGeneration = Math.max(0L, requestGrantGeneration);
        this.installFingerprint = ParcelSafe.fit(installFingerprint, PluginProtocol.MAX_ID);
    }

    private PageLease(Parcel in) {
        leaseId = ParcelSafe.read(in, PluginProtocol.MAX_ID, "leaseId");
        pluginId = ParcelSafe.read(in, PluginProtocol.MAX_ID, "pluginId");
        packageName = ParcelSafe.read(in, PluginProtocol.MAX_ID, "packageName");
        pageId = ParcelSafe.read(in, PluginProtocol.MAX_ID, "pageId");
        protocol = ParcelSafe.readInt(in);
        uid = ParcelSafe.readInt(in);
        hostGeneration = ParcelSafe.readInt(in);
        issuedElapsedMs = ParcelSafe.readTime(in, "lease issued");
        expiresElapsedMs = ParcelSafe.readTime(in, "lease expires");
        installGeneration = Math.max(0L, ParcelSafe.readLong(in));
        enabledGeneration = Math.max(0L, ParcelSafe.readLong(in));
        readGrantGeneration = Math.max(0L, ParcelSafe.readLong(in));
        requestGrantGeneration = Math.max(0L, ParcelSafe.readLong(in));
        installFingerprint = ParcelSafe.read(in, PluginProtocol.MAX_ID, "installFingerprint");
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        ParcelSafe.write(out, leaseId, PluginProtocol.MAX_ID);
        ParcelSafe.write(out, pluginId, PluginProtocol.MAX_ID);
        ParcelSafe.write(out, packageName, PluginProtocol.MAX_ID);
        ParcelSafe.write(out, pageId, PluginProtocol.MAX_ID);
        out.writeInt(protocol);
        out.writeInt(uid);
        out.writeInt(hostGeneration);
        out.writeLong(issuedElapsedMs);
        out.writeLong(expiresElapsedMs);
        out.writeLong(installGeneration);
        out.writeLong(enabledGeneration);
        out.writeLong(readGrantGeneration);
        out.writeLong(requestGrantGeneration);
        ParcelSafe.write(out, installFingerprint, PluginProtocol.MAX_ID);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<PageLease> CREATOR = new Creator<PageLease>() {
        @Override public PageLease createFromParcel(Parcel in) { return new PageLease(in); }
        @Override public PageLease[] newArray(int size) { return new PageLease[size]; }
    };
}
