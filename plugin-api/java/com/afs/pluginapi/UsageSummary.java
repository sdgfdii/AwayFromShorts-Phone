package com.afs.pluginapi;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded protocol-1 snapshot. It contains display labels and durations only:
 * no package names, raw events, JSON, tokens, files or arbitrary Bundles.
 */
public final class UsageSummary implements Parcelable {
    public final long asOfEpochMs;
    public final String zoneId;
    public final String monthKey;
    public final boolean tooShort;
    /** At most 40 rows; the plugin renders the first ten. */
    public final List<DisplayUsage> phoneTop10;
    public final long todayGameMs;
    public final long monthGameMs;
    public final List<TrendDay> trend7;
    public final PcPeriodSummary pcDay;
    public final PcPeriodSummary pcMonth;
    public final PcPeriodSummary pcTotal;
    public final String pcSince;
    public final String pcUpdatedAt;
    public final boolean pcStale;

    public UsageSummary(long asOfEpochMs, String zoneId, String monthKey, boolean tooShort,
                        List<DisplayUsage> phoneTop10, long todayGameMs, long monthGameMs,
                        List<TrendDay> trend7, PcPeriodSummary pcDay, PcPeriodSummary pcMonth,
                        PcPeriodSummary pcTotal, String pcSince, String pcUpdatedAt, boolean pcStale) {
        this.asOfEpochMs = Math.max(0L, asOfEpochMs);
        this.zoneId = ParcelSafe.fit(zoneId, 64);
        this.monthKey = ParcelSafe.fit(monthKey, 16);
        this.tooShort = tooShort;
        this.phoneTop10 = bounded(phoneTop10, PluginProtocol.MAX_RANK);
        this.todayGameMs = Math.max(0L, todayGameMs);
        this.monthGameMs = Math.max(0L, monthGameMs);
        this.trend7 = bounded(trend7, PluginProtocol.MAX_TREND);
        this.pcDay = pcDay == null ? PcPeriodSummary.empty() : pcDay;
        this.pcMonth = pcMonth == null ? PcPeriodSummary.empty() : pcMonth;
        this.pcTotal = pcTotal == null ? PcPeriodSummary.empty() : pcTotal;
        this.pcSince = ParcelSafe.fit(pcSince, 64);
        this.pcUpdatedAt = ParcelSafe.fit(pcUpdatedAt, 64);
        this.pcStale = pcStale;
    }

    private static <T> List<T> bounded(List<T> source, int max) {
        ArrayList<T> copy = new ArrayList<>();
        if (source != null) {
            for (T item : source) {
                if (item != null && copy.size() < max) copy.add(item);
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private UsageSummary(Parcel in) {
        asOfEpochMs = Math.max(0L, ParcelSafe.readLong(in));
        zoneId = ParcelSafe.read(in, 64, "zoneId");
        monthKey = ParcelSafe.read(in, 16, "monthKey");
        tooShort = ParcelSafe.readCount(in,1,"boolean") != 0;
        int ranks = ParcelSafe.readCount(in, PluginProtocol.MAX_RANK, "phone ranking");
        ArrayList<DisplayUsage> ranking = new ArrayList<>(ranks);
        for (int i = 0; i < ranks; i++) ranking.add(DisplayUsage.CREATOR.createFromParcel(in));
        phoneTop10 = Collections.unmodifiableList(ranking);
        todayGameMs = Math.max(0L, ParcelSafe.readLong(in));
        monthGameMs = Math.max(0L, ParcelSafe.readLong(in));
        int trends = ParcelSafe.readCount(in, PluginProtocol.MAX_TREND, "trend7");
        ArrayList<TrendDay> days = new ArrayList<>(trends);
        for (int i = 0; i < trends; i++) days.add(TrendDay.CREATOR.createFromParcel(in));
        trend7 = Collections.unmodifiableList(days);
        pcDay = PcPeriodSummary.CREATOR.createFromParcel(in);
        pcMonth = PcPeriodSummary.CREATOR.createFromParcel(in);
        pcTotal = PcPeriodSummary.CREATOR.createFromParcel(in);
        pcSince = ParcelSafe.read(in, 64, "pcSince");
        pcUpdatedAt = ParcelSafe.read(in, 64, "pcUpdatedAt");
        pcStale = ParcelSafe.readCount(in,1,"boolean") != 0;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(asOfEpochMs);
        ParcelSafe.write(out, zoneId, 64);
        ParcelSafe.write(out, monthKey, 16);
        out.writeInt(tooShort ? 1 : 0);
        out.writeInt(phoneTop10.size());
        for (DisplayUsage row : phoneTop10) row.writeToParcel(out, flags);
        out.writeLong(todayGameMs);
        out.writeLong(monthGameMs);
        out.writeInt(trend7.size());
        for (TrendDay day : trend7) day.writeToParcel(out, flags);
        pcDay.writeToParcel(out, flags);
        pcMonth.writeToParcel(out, flags);
        pcTotal.writeToParcel(out, flags);
        ParcelSafe.write(out, pcSince, 64);
        ParcelSafe.write(out, pcUpdatedAt, 64);
        out.writeInt(pcStale ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<UsageSummary> CREATOR = new Creator<UsageSummary>() {
        @Override public UsageSummary createFromParcel(Parcel in) { return new UsageSummary(in); }
        @Override public UsageSummary[] newArray(int size) { return new UsageSummary[size]; }
    };
}
