package com.afs.pluginapi;

import android.os.Parcel;
import android.os.Parcelable;

public final class OpenResult implements Parcelable {
    private static final int[] ALLOWED = {
            PluginStatus.OK, PluginStatus.INVALID_TICKET, PluginStatus.BAD_CALLER,
            PluginStatus.INCOMPATIBLE, PluginStatus.DISABLED, PluginStatus.EXPIRED,
            PluginStatus.BUSY, PluginStatus.UNAVAILABLE, PluginStatus.FAILED
    };

    public final int status;
    public final PageLease lease;
    public final String message;

    public OpenResult(int status, PageLease lease, String message) {
        this.status = status;
        this.lease = lease;
        this.message = ParcelSafe.fit(message, PluginProtocol.MAX_MESSAGE);
    }

    private OpenResult(Parcel in) {
        status = ParcelSafe.readStatus(in, ALLOWED, "open status");
        lease = ParcelSafe.readCount(in,1,"parcel presence") == 1 ? PageLease.CREATOR.createFromParcel(in) : null;
        message = ParcelSafe.read(in, PluginProtocol.MAX_MESSAGE, "open message");
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(status);
        out.writeInt(lease == null ? 0 : 1);
        if (lease != null) lease.writeToParcel(out, flags);
        ParcelSafe.write(out, message, PluginProtocol.MAX_MESSAGE);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<OpenResult> CREATOR = new Creator<OpenResult>() {
        @Override public OpenResult createFromParcel(Parcel in) { return new OpenResult(in); }
        @Override public OpenResult[] newArray(int size) { return new OpenResult[size]; }
    };
}
