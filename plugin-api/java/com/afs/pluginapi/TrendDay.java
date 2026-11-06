package com.afs.pluginapi;

import android.os.Parcel;
import android.os.Parcelable;

/** One of exactly seven entertainment-share days. */
public final class TrendDay implements Parcelable {
    public final String date;
    public final long totalMs;
    public final long funMs;

    public TrendDay(String date, long totalMs, long funMs) {
        this.date = ParcelSafe.fit(date, 32);
        this.totalMs = Math.max(0L, totalMs);
        this.funMs = Math.max(0L, Math.min(funMs, this.totalMs));
    }

    private TrendDay(Parcel in) {
        date = ParcelSafe.read(in, 32, "trend date");
        totalMs = Math.max(0L, ParcelSafe.readLong(in));
        funMs = Math.max(0L, Math.min(ParcelSafe.readLong(in), totalMs));
    }

    public int percent() {
        return totalMs <= 0L ? 0 : (int) Math.round(funMs * 100.0 / totalMs);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        ParcelSafe.write(out, date, 32);
        out.writeLong(totalMs);
        out.writeLong(funMs);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<TrendDay> CREATOR = new Creator<TrendDay>() {
        @Override public TrendDay createFromParcel(Parcel in) { return new TrendDay(in); }
        @Override public TrendDay[] newArray(int size) { return new TrendDay[size]; }
    };
}
