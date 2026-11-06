package com.afs.pluginapi;

import android.os.Parcel;
import android.os.Parcelable;

/** One display row: a human label and a duration. Never a package name. */
public final class DisplayUsage implements Parcelable {
    public final String displayName;
    public final long foregroundMs;

    public DisplayUsage(String displayName, long foregroundMs) {
        this.displayName = ParcelSafe.fit(displayName, PluginProtocol.MAX_LABEL);
        this.foregroundMs = Math.max(0L, foregroundMs);
    }

    private DisplayUsage(Parcel in) {
        displayName = ParcelSafe.read(in, PluginProtocol.MAX_LABEL, "displayName");
        foregroundMs = Math.max(0L, ParcelSafe.readLong(in));
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        ParcelSafe.write(out, displayName, PluginProtocol.MAX_LABEL);
        out.writeLong(foregroundMs);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<DisplayUsage> CREATOR = new Creator<DisplayUsage>() {
        @Override public DisplayUsage createFromParcel(Parcel in) { return new DisplayUsage(in); }
        @Override public DisplayUsage[] newArray(int size) { return new DisplayUsage[size]; }
    };
}
