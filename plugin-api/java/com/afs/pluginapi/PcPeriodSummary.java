package com.afs.pluginapi;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A PC period. totalMs is computed from the complete list by the host before
 * this object is trimmed to ten rows, matching the legacy page's tab totals.
 */
public final class PcPeriodSummary implements Parcelable {
    public final List<DisplayUsage> top10;
    public final long totalMs;
    public final boolean empty;

    public PcPeriodSummary(List<DisplayUsage> top10, long totalMs, boolean empty) {
        ArrayList<DisplayUsage> copy = new ArrayList<>();
        if (top10 != null) {
            for (DisplayUsage row : top10) {
                if (row != null && copy.size() < PluginProtocol.MAX_PC_TOP) copy.add(row);
            }
        }
        this.top10 = Collections.unmodifiableList(copy);
        this.totalMs = Math.max(0L, totalMs);
        this.empty = empty;
    }

    private PcPeriodSummary(Parcel in) {
        int count = ParcelSafe.readCount(in, PluginProtocol.MAX_PC_TOP, "pc top");
        ArrayList<DisplayUsage> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) rows.add(DisplayUsage.CREATOR.createFromParcel(in));
        top10 = Collections.unmodifiableList(rows);
        totalMs = Math.max(0L, ParcelSafe.readLong(in));
        empty = ParcelSafe.readCount(in,1,"boolean") != 0;
    }

    public static PcPeriodSummary empty() {
        return new PcPeriodSummary(Collections.<DisplayUsage>emptyList(), 0L, true);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(top10.size());
        for (DisplayUsage row : top10) row.writeToParcel(out, flags);
        out.writeLong(totalMs);
        out.writeInt(empty ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<PcPeriodSummary> CREATOR = new Creator<PcPeriodSummary>() {
        @Override public PcPeriodSummary createFromParcel(Parcel in) { return new PcPeriodSummary(in); }
        @Override public PcPeriodSummary[] newArray(int size) { return new PcPeriodSummary[size]; }
    };
}
