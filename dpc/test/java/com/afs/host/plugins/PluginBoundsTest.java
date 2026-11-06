package com.afs.host.plugins;

import com.afs.pluginapi.DisplayUsage;
import com.afs.pluginapi.PcPeriodSummary;
import com.afs.pluginapi.PluginProtocol;
import com.afs.pluginapi.TrendDay;
import com.afs.pluginapi.UsageSummary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Offline DTO bounds: 40/7/10 rows and 256-character labels. */
public final class PluginBoundsTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        List<DisplayUsage> ranks = new ArrayList<>();
        for (int i = 0; i < 50; i++) ranks.add(new DisplayUsage("app " + i, i * 1000L));
        List<TrendDay> days = new ArrayList<>();
        for (int i = 0; i < 12; i++) days.add(new TrendDay("day-" + i, 100L, 50L));
        List<DisplayUsage> pcRows = new ArrayList<>();
        long total = 0L;
        for (int i = 0; i < 25; i++) {
            pcRows.add(new DisplayUsage("pc " + i, i * 1000L));
            total += i * 1000L;
        }
        PcPeriodSummary pc = new PcPeriodSummary(pcRows, total, false);
        eq(PluginProtocol.MAX_PC_TOP, pc.top10.size(), "PC rows trimmed to 10");
        eq(total, pc.totalMs, "PC total comes from the full list");

        UsageSummary summary = new UsageSummary(1L, "Asia/Shanghai", "2026-11", false,
                ranks, 10L, 20L, days, pc, pc, pc, "2026-01", "2026-11-03 10:00", false);
        eq(PluginProtocol.MAX_RANK, summary.phoneTop10.size(), "ranking capped at 40");
        eq(PluginProtocol.MAX_TREND, summary.trend7.size(), "trend capped at 7");
        eq(PluginProtocol.MAX_PC_TOP, summary.pcTotal.top10.size(), "summary PC rows capped");

        String longLabel = new String(new char[400]).replace('\0', 'x');
        DisplayUsage longRow = new DisplayUsage(longLabel, 5L);
        eq(PluginProtocol.MAX_LABEL, longRow.displayName.length(), "label capped at 256");

        TrendDay odd = new TrendDay("2026-11-03", 1000L, 5000L);
        eq(1000L, odd.funMs, "fun time cannot exceed total");
        eq(100, odd.percent(), "percentage from bounded values");

        UsageSummary empty = new UsageSummary(0L, "", "", true,
                Collections.<DisplayUsage>emptyList(), 0L, 0L,
                Collections.<TrendDay>emptyList(), null, null, null, null, null, true);
        ok(empty.pcDay.empty && empty.pcMonth.empty && empty.pcTotal.empty, "null PC periods become empty");
        eq(0, empty.phoneTop10.size(), "empty ranking");
        eq(0, empty.trend7.size(), "empty trend");

        PcPeriodSummary emptyPc = PcPeriodSummary.empty();
        ok(emptyPc.empty && emptyPc.totalMs == 0L && emptyPc.top10.isEmpty(), "empty PC period");

        System.out.println("Plugin bounds: " + checks + " checks passed");
    }
}
