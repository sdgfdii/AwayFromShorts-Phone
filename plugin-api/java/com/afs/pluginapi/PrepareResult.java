package com.afs.pluginapi;

import android.os.Parcel;
import android.os.Parcelable;

public final class PrepareResult implements Parcelable {
    private static final int[] ALLOWED = {
            PluginStatus.OK, PluginStatus.FAILED, PluginStatus.UNAVAILABLE,
            PluginStatus.INCOMPATIBLE, PluginStatus.DISABLED
    };

    public final long requestId;
    public final int status;
    public final String message;

    public PrepareResult(long requestId, int status, String message) {
        this.requestId = requestId;
        this.status = status;
        this.message = ParcelSafe.fit(message, PluginProtocol.MAX_MESSAGE);
    }

    private PrepareResult(Parcel in) {
        requestId = ParcelSafe.readLong(in);
        status = ParcelSafe.readStatus(in, ALLOWED, "prepare status");
        message = ParcelSafe.read(in, PluginProtocol.MAX_MESSAGE, "prepare message");
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(requestId);
        out.writeInt(status);
        ParcelSafe.write(out, message, PluginProtocol.MAX_MESSAGE);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<PrepareResult> CREATOR = new Creator<PrepareResult>() {
        @Override public PrepareResult createFromParcel(Parcel in) { return new PrepareResult(in); }
        @Override public PrepareResult[] newArray(int size) { return new PrepareResult[size]; }
    };
}
