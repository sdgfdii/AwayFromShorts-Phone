package com.afs.pluginapi;

import android.os.Parcel;
import android.os.Parcelable;

public final class UsageResult implements Parcelable {
    private static final int[] ALLOWED = {
            PluginStatus.OK, PluginStatus.PERMISSION_REQUIRED, PluginStatus.REVOKED,
            PluginStatus.DISABLED, PluginStatus.EXPIRED, PluginStatus.BUSY,
            PluginStatus.TIMEOUT, PluginStatus.UNAVAILABLE, PluginStatus.NO_GRANT,
            PluginStatus.FAILED, PluginStatus.BAD_CALLER, PluginStatus.INCOMPATIBLE
    };

    public final long requestId;
    public final int status;
    public final UsageSummary summary;
    public final String message;

    public UsageResult(long requestId, int status, UsageSummary summary, String message) {
        this.requestId = requestId;
        this.status = status;
        this.summary = summary;
        this.message = ParcelSafe.fit(message, PluginProtocol.MAX_MESSAGE);
    }

    private UsageResult(Parcel in) {
        requestId = ParcelSafe.readLong(in);
        status = ParcelSafe.readStatus(in, ALLOWED, "usage status");
        summary = ParcelSafe.readCount(in,1,"parcel presence") == 1 ? UsageSummary.CREATOR.createFromParcel(in) : null;
        message = ParcelSafe.read(in, PluginProtocol.MAX_MESSAGE, "usage message");
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(requestId);
        out.writeInt(status);
        out.writeInt(summary == null ? 0 : 1);
        if (summary != null) summary.writeToParcel(out, flags);
        ParcelSafe.write(out, message, PluginProtocol.MAX_MESSAGE);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<UsageResult> CREATOR = new Creator<UsageResult>() {
        @Override public UsageResult createFromParcel(Parcel in) { return new UsageResult(in); }
        @Override public UsageResult[] newArray(int size) { return new UsageResult[size]; }
    };
}
