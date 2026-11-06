package com.afs.pluginapi;

import android.os.Parcel;
import android.os.Parcelable;

/** One-time, 60-second launch ticket. Opaque to the plugin. */
public final class LaunchTicket implements Parcelable {
    public final String ticketId;
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

    public LaunchTicket(String ticketId, String pluginId, String packageName, String pageId,
                        int protocol, int uid, int hostGeneration, long issuedElapsedMs,
                        long expiresElapsedMs, long installGeneration, long enabledGeneration,
                        long readGrantGeneration, long requestGrantGeneration,
                        String installFingerprint) {
        this.ticketId = ParcelSafe.fit(ticketId, PluginProtocol.MAX_ID);
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

    private LaunchTicket(Parcel in) {
        ticketId = ParcelSafe.read(in, PluginProtocol.MAX_ID, "ticketId");
        pluginId = ParcelSafe.read(in, PluginProtocol.MAX_ID, "pluginId");
        packageName = ParcelSafe.read(in, PluginProtocol.MAX_ID, "packageName");
        pageId = ParcelSafe.read(in, PluginProtocol.MAX_ID, "pageId");
        protocol = ParcelSafe.readInt(in);
        uid = ParcelSafe.readInt(in);
        hostGeneration = ParcelSafe.readInt(in);
        issuedElapsedMs = ParcelSafe.readTime(in, "ticket issued");
        expiresElapsedMs = ParcelSafe.readTime(in, "ticket expires");
        installGeneration = Math.max(0L, ParcelSafe.readLong(in));
        enabledGeneration = Math.max(0L, ParcelSafe.readLong(in));
        readGrantGeneration = Math.max(0L, ParcelSafe.readLong(in));
        requestGrantGeneration = Math.max(0L, ParcelSafe.readLong(in));
        installFingerprint = ParcelSafe.read(in, PluginProtocol.MAX_ID, "installFingerprint");
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        ParcelSafe.write(out, ticketId, PluginProtocol.MAX_ID);
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

    public static final Creator<LaunchTicket> CREATOR = new Creator<LaunchTicket>() {
        @Override public LaunchTicket createFromParcel(Parcel in) { return new LaunchTicket(in); }
        @Override public LaunchTicket[] newArray(int size) { return new LaunchTicket[size]; }
    };
}
