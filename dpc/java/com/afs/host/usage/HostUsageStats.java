package com.afs.host.usage;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.afs.guard.PcStats;
import com.afs.guard.RuleStore;
import com.afs.guard.T;
import com.afs.pluginapi.DisplayUsage;
import com.afs.pluginapi.PcPeriodSummary;
import com.afs.pluginapi.PluginProtocol;
import com.afs.pluginapi.TrendDay;
import com.afs.pluginapi.UsageSummary;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host-only aggregation. System usage, RuleStore classification and PcStats
 * stay here; the plugin receives labels and durations only.
 */
public final class HostUsageStats {

    private HostUsageStats() {}

    public static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    public static UsageSummary collect(Context context) {
        com.afs.guard.Hub.kick(context); // preserve the old statistics page's throttled sync trigger
        long asOf = System.currentTimeMillis();
        Calendar now = Calendar.getInstance();
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        long todayStart = today.getTimeInMillis();
        Calendar month = (Calendar) today.clone();
        month.set(Calendar.DAY_OF_MONTH, 1);
        long monthStart = month.getTimeInMillis();

        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        PackageManager pm = context.getPackageManager();
        if (usm == null) {
            return empty(context, asOf, now);
        }

        Map<String, Long> monthTotals = new HashMap<>();
        try {
            List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, monthStart, asOf);
            if (list != null) for (UsageStats usage : list) {
                if (usage == null) continue;
                Long old = monthTotals.get(usage.getPackageName());
                monthTotals.put(usage.getPackageName(),
                        (old == null ? 0L : old) + Math.max(0L, usage.getTotalTimeInForeground()));
            }
        } catch (Throwable ignored) { }

        Map<String, TrendDayBuilder> byDay = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            Calendar day = Calendar.getInstance();
            day.add(Calendar.DAY_OF_YEAR, -i);
            byDay.put(ymd(day), new TrendDayBuilder(shortDate(day)));
        }
        try {
            List<UsageStats> daily = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                    todayStart - 6L * 86400000L, asOf);
            if (daily != null) for (UsageStats usage : daily) {
                if (usage == null) continue;
                TrendDayBuilder day = byDay.get(ymdOf(usage.getFirstTimeStamp()));
                if (day != null) day.total += Math.max(0L, usage.getTotalTimeInForeground());
            }
        } catch (Throwable ignored) { }

        Set<String> funPackages = RuleStore.packages(context);
        for (String pkg : monthTotals.keySet()) if (isGame(pm, pkg)) funPackages.add(pkg);

        String self = context.getPackageName();
        monthTotals.remove(self);
        long monthGame = 0L;
        List<DisplayUsage> ranking = new ArrayList<>();
        for (Map.Entry<String, Long> row : monthTotals.entrySet()) {
            if (isGame(pm, row.getKey())) monthGame += row.getValue();
            ranking.add(new DisplayUsage(labelOf(pm, row.getKey()), row.getValue()));
        }
        Collections.sort(ranking, new Comparator<DisplayUsage>() {
            @Override public int compare(DisplayUsage a, DisplayUsage b) {
                return Long.compare(b.foregroundMs, a.foregroundMs);
            }
        });
        if (ranking.size() > PluginProtocol.MAX_RANK) {
            ranking = new ArrayList<>(ranking.subList(0, PluginProtocol.MAX_RANK));
        }

        long todayGame = 0L;
        try {
            List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, todayStart, asOf);
            if (list != null) for (UsageStats usage : list) {
                if (usage != null && isGame(pm, usage.getPackageName())) {
                    todayGame += Math.max(0L, usage.getTotalTimeInForeground());
                }
            }
        } catch (Throwable ignored) { }

        try {
            List<UsageStats> daily = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                    todayStart - 6L * 86400000L, asOf);
            if (daily != null) for (UsageStats usage : daily) {
                if (usage == null) continue;
                TrendDayBuilder day = byDay.get(ymdOf(usage.getFirstTimeStamp()));
                if (day != null && funPackages.contains(usage.getPackageName())) {
                    day.fun += Math.max(0L, usage.getTotalTimeInForeground());
                }
            }
        } catch (Throwable ignored) { }

        ArrayList<TrendDay> trend = new ArrayList<>();
        long allTotal = 0L;
        for (TrendDayBuilder day : byDay.values()) {
            trend.add(new TrendDay(day.label, day.total, day.fun));
            allTotal += day.total;
        }
        boolean tooShort = ranking.isEmpty() && allTotal <= 0L;

        // Blocking network is allowed here: this method only runs on the host
        // worker, never on the UI thread or under a Guard lock.
        PcStats.ensureFresh(context);
        String todayKey = ymd(Calendar.getInstance());
        PcPeriodSummary dayPc = pcPeriod(PcStats.dayApps(context, todayKey));
        PcPeriodSummary monthPc = pcPeriod(PcStats.monthApps(context, todayKey));
        PcPeriodSummary totalPc = pcPeriod(PcStats.totalApps(context));
        String updated = PcStats.updatedAt(context);

        return new UsageSummary(asOf, java.util.TimeZone.getDefault().getID(),
                String.format(java.util.Locale.US, "%04d-%02d", now.get(Calendar.YEAR),
                        now.get(Calendar.MONTH) + 1), tooShort, ranking, todayGame, monthGame,
                trend, dayPc, monthPc, totalPc, PcStats.since(context), updated,
                totalPc.empty && updated.isEmpty());
    }

    /**
     * Computes totalMs from the complete list first, then trims to ten rows.
     * This preserves the legacy PC tabs' total meaning.
     */
    public static PcPeriodSummary pcPeriod(List<PcStats.Entry> apps) {
        if (apps == null) apps = Collections.emptyList();
        long total = 0L;
        for (PcStats.Entry entry : apps) total += Math.max(0, entry.min) * 60000L;
        ArrayList<DisplayUsage> rows = new ArrayList<>();
        for (int i = 0; i < apps.size() && i < PluginProtocol.MAX_PC_TOP; i++) {
            PcStats.Entry entry = apps.get(i);
            rows.add(new DisplayUsage(PcStats.label(entry.name), Math.max(0, entry.min) * 60000L));
        }
        return new PcPeriodSummary(rows, total, apps.isEmpty());
    }

    private static UsageSummary empty(Context context, long asOf, Calendar now) {
        ArrayList<TrendDay> trend = new ArrayList<>();
        Calendar day = Calendar.getInstance();
        for (int i = 6; i >= 0; i--) {
            Calendar value = (Calendar) day.clone();
            value.add(Calendar.DAY_OF_YEAR, -i);
            trend.add(new TrendDay(shortDate(value), 0L, 0L));
        }
        return new UsageSummary(asOf, java.util.TimeZone.getDefault().getID(),
                String.format(java.util.Locale.US, "%04d-%02d", now.get(Calendar.YEAR),
                        now.get(Calendar.MONTH) + 1), true,
                Collections.<DisplayUsage>emptyList(), 0L, 0L, trend,
                PcPeriodSummary.empty(), PcPeriodSummary.empty(), PcPeriodSummary.empty(),
                "", "", true);
    }

    private static boolean isGame(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return info != null && info.category == ApplicationInfo.CATEGORY_GAME;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String labelOf(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = info == null ? null : pm.getApplicationLabel(info);
            return displayLabel(label,pkg);
        } catch (Throwable ignored) { }
        // Never leak an Android package name through the display DTO.
        return T.s("未知应用");
    }
    // PackageManager itself falls back to packageName when no label is defined.
    private static String displayLabel(CharSequence label,String pkg) {
        String value=label==null?"":label.toString().trim();
        return value.isEmpty()||value.equals(pkg)?T.s("未知应用"):value;
    }

    private static String ymd(Calendar value) {
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                value.get(Calendar.YEAR), value.get(Calendar.MONTH) + 1,
                value.get(Calendar.DAY_OF_MONTH));
    }

    private static String shortDate(Calendar value) {
        return String.format(java.util.Locale.US, "%02d-%02d",
                value.get(Calendar.MONTH) + 1, value.get(Calendar.DAY_OF_MONTH));
    }

    private static String ymdOf(long millis) {
        Calendar value = Calendar.getInstance();
        value.setTimeInMillis(millis);
        return ymd(value);
    }

    private static final class TrendDayBuilder {
        final String label;
        long total;
        long fun;

        TrendDayBuilder(String label) { this.label = label; }
    }
}
