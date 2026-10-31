package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电脑端应用时长：读同步 Gist 上的 pc-activity.json。
 *
 * 数据链路：PC 面板每 60 秒采样前台窗口聚合成 activity.json（core.ps1 唯一写入方），
 * push_gist.ps1 每 15 分钟随 study-gate.json 一起把「近 14 天 · 每天前 12 个应用」
 * 的切片推成 Gist 上的 pc-activity.json；手机统计页打开时来这里拉一次。
 *
 * 不并入 StudyGate 的拉取循环是故意的：那边在本机学习台模式下（localMode）根本不跑，
 * 而统计页恰恰在这种模式下最需要电脑数据。独立小类 + 自己的节流，互不拖累。
 *
 * 不用 org.json：android.jar 里它是 stub，离线回归对拍跑不了（和 StudyGate 同理）。
 * 数据是 PC 端机器生成的紧凑 JSON，结构固定，正则够用。
 */
public final class PcStats {

    private static final String TAG = "afs.pcstats";

    /** 与 StudyGate 同一个同步 Gist（secret：raw URL 能读就行）。 */

    private static final String FILE = "pc_stats";

    /** PC 每 15 分钟推一次，gist raw CDN 还有 ~5 分钟缓存 —— 10 分钟节流刚好。 */
    private static final long REFRESH_MS = 600_000L;

    /** 单次拉取的最长容忍时间，超过就允许下一次重试（老线程自生自灭）。 */
    private static final long FETCH_LIVENESS_MS = 30_000L;

    /** 一个应用一天的时长（分钟）。 */
    public static final class Entry {
        public final String name;   // PC 进程名（msedge / VALORANT-Win64-Shipping…）
        public final int min;
        Entry(String n, int m) { name = n; min = m; }
    }

    private static final Object LOCK = new Object();

    /** 日期（yyyy-MM-dd，升序）→ 当天应用时长列表（已按分钟降序）。 */
    private static LinkedHashMap<String, List<Entry>> days;
    private static String updated;   // PC 端推送时间
    private static String rawSnapshot = "";

    private static volatile long lastTryMs;
    private static volatile long fetchStartedAt;
    private static volatile boolean fetching;

    private PcStats() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SyncConfig.cacheName(c, FILE), Context.MODE_PRIVATE);
    }

    static void resetRemote(Context c) {
        synchronized (LOCK) {
            days = new LinkedHashMap<>();
            updated = "";
            rawSnapshot = "";
            lastTryMs = 0L;
            fetching = false;
        }
    }

    /** 首次访问时从盘里恢复上次快照（重启后不空窗）。 */
    private static void ensureLoaded(Context c) {
        synchronized (LOCK) {
            if (days != null) {
                return;
            }
            try {
                String raw = sp(c).getString("raw", "");
                if (raw.length() > 0) {
                    LinkedHashMap<String, List<Entry>> parsed = parse(raw);
                    if (parsed != null && !parsed.isEmpty()) {
                        days = parsed;
                        updated = extractUpdated(raw);
                        rawSnapshot = raw;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (days == null) {
                days = new LinkedHashMap<>();
            }
        }
    }

    /**
     * 统计页后台线程里调：10 分钟内最多真拉一次，失败保留旧数据。
     * 阻塞式（最长 8s 连接 + 8s 读），调用方必须不在主线程。
     */
    public static void ensureFresh(Context c) {
        if (!SyncConfig.configured(c)) return;
        long now = System.currentTimeMillis();
        boolean stuck = fetching && now - fetchStartedAt > FETCH_LIVENESS_MS;
        if ((fetching && !stuck) || now - lastTryMs < REFRESH_MS) {
            return;
        }
        lastTryMs = now;
        fetchStartedAt = now;
        fetching = true;
        try (SyncConfig.Session session = SyncConfig.acquire(c)) {
            String raw = StudyGate.fetchRaw(session.target.rawUrl("pc-activity.json") + "?t=" + System.currentTimeMillis());
            if (raw != null && raw.trim().startsWith("{")) {
                LinkedHashMap<String, List<Entry>> parsed = parse(raw);
                if (parsed != null && !parsed.isEmpty()) {
                    synchronized (LOCK) {
                        days = parsed;
                        updated = extractUpdated(raw);
                        rawSnapshot = raw;
                    }
                    sp(c).edit().putString("raw", raw)
                            .putLong("fetched_at", System.currentTimeMillis()).apply();
                    Log.i(TAG, "pc-activity fetched: days=" + parsed.size());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "pc-activity fetch failed: " + t);
        } finally {
            fetching = false;
        }
    }

    /** 最近一个有数据的日期（yyyy-MM-dd），没有数据返回 null。 */
    public static String latestDay(Context c) {
        ensureLoaded(c);
        synchronized (LOCK) {
            String last = null;
            for (String k : days.keySet()) {
                List<Entry> l = days.get(k);
                if (l != null && !l.isEmpty()) {
                    last = k;   // key 升序，走到最后就是最新
                }
            }
            return last;
        }
    }

    /** 指定日期的应用时长列表（分钟降序）；没数据返回空表。 */
    public static List<Entry> dayApps(Context c, String date) {
        ensureLoaded(c);
        synchronized (LOCK) {
            if (date != null && date.equals(stringField(rawSnapshot, "date"))) {
                Matcher today = Pattern.compile("\"todayApps\"\\s*:\\s*\\{([^{}]*)\\}").matcher(rawSnapshot);
                if (today.find()) return appEntries(today.group(1));
            }
            List<Entry> l = date == null ? null : days.get(date);
            return l == null ? new ArrayList<Entry>() : new ArrayList<Entry>(l);
        }
    }

    /** PC 端的推送时间（"yyyy-MM-dd HH:mm"），没有就空串。 */
    public static String updatedAt(Context c) {
        ensureLoaded(c);
        synchronized (LOCK) {
            return updated == null ? "" : updated;
        }
    }

    public static List<Entry> monthApps(Context c, String date) {
        ensureLoaded(c);
        synchronized (LOCK) { return periodApps(rawSnapshot, "month", date); }
    }

    public static List<Entry> totalApps(Context c) {
        ensureLoaded(c);
        synchronized (LOCK) { return periodApps(rawSnapshot, "total", ""); }
    }

    public static String since(Context c) {
        ensureLoaded(c);
        synchronized (LOCK) {
            String since = stringField(rawSnapshot, "since");
            if (!since.isEmpty()) return since;
            String first = "";
            for (String date : days.keySet()) if (first.isEmpty() || date.compareTo(first) < 0) first = date;
            return first;
        }
    }

    public static long totalMinutes(List<Entry> entries) {
        long sum = 0;
        for (Entry e : entries) sum += e.min;
        return sum;
    }

    /** 新版读取完整汇总；旧快照按已有日记录聚合，不能把日值冒充月值。 */
    static List<Entry> periodApps(String json, String period, String date) {
        String month = date.length() >= 7 ? date.substring(0, 7) : "";
        String key = "total".equals(period) ? "totalApps" : "monthApps";
        if (json != null && ("total".equals(period) || month.equals(stringField(json, "month")))) {
            Matcher rollup = Pattern.compile("\"" + key + "\"\\s*:\\s*\\{([^{}]*)\\}").matcher(json);
            if (rollup.find()) return appEntries(rollup.group(1));
        }
        LinkedHashMap<String, List<Entry>> parsed = parse(json);
        LinkedHashMap<String, Integer> sums = new LinkedHashMap<>();
        if (parsed != null) for (Map.Entry<String, List<Entry>> day : parsed.entrySet()) {
            if (!"total".equals(period) && !day.getKey().startsWith(month)) continue;
            for (Entry e : day.getValue()) {
                Integer old = sums.get(e.name);
                sums.put(e.name, (old == null ? 0 : old) + e.min);
            }
        }
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sums.entrySet()) out.add(new Entry(e.getKey(), e.getValue()));
        sortEntries(out);
        return out;
    }

    private static String stringField(String json, String key) {
        if (json == null) return "";
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static List<Entry> appEntries(String contents) {
        List<Entry> out = new ArrayList<>();
        Matcher m = APP_P.matcher(contents);
        while (m.find()) {
            try { out.add(new Entry(m.group(1), Integer.parseInt(m.group(2)))); }
            catch (NumberFormatException ignored) { }
        }
        sortEntries(out);
        return out;
    }

    private static void sortEntries(List<Entry> entries) {
        Collections.sort(entries, new java.util.Comparator<Entry>() {
            public int compare(Entry a, Entry b) { return Integer.compare(b.min, a.min); }
        });
    }

    /* ==================== 解析 ==================== */

    /** 一天：{"2026-10-23":{"apps":{"msedge":181}}} —— apps 值全是数字，非贪婪到第一个 }} 即日。 */
    private static final Pattern DAY_P = Pattern.compile(
            "\"([0-9]{4}-[0-9]{2}-[0-9]{2})\"\\s*:\\s*\\{\\s*\"apps\"\\s*:\\s*\\{(.*?)\\}\\s*\\}");
    private static final Pattern APP_P = Pattern.compile("\"([^\"]+)\"\\s*:\\s*([0-9]+)");
    private static final Pattern UPD_P = Pattern.compile("\"updated\"\\s*:\\s*\"([^\"]+)\"");

    /** 纯函数，回归可对拍；解析不出任何一天返回 null（调用方保留旧数据）。 */
    static LinkedHashMap<String, List<Entry>> parse(String json) {
        if (json == null || json.length() == 0) {
            return null;
        }
        try {
            LinkedHashMap<String, List<Entry>> out = new LinkedHashMap<>();
            Matcher md = DAY_P.matcher(json);
            while (md.find()) {
                String date = md.group(1);
                String apps = md.group(2);
                LinkedHashMap<String, Integer> agg = new LinkedHashMap<>();
                Matcher ma = APP_P.matcher(apps);
                while (ma.find()) {
                    try {
                        agg.put(ma.group(1), Integer.parseInt(ma.group(2)));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (agg.isEmpty()) {
                    continue;
                }
                List<Map.Entry<String, Integer>> sorted =
                        new ArrayList<Map.Entry<String, Integer>>(agg.entrySet());
                Collections.sort(sorted, new java.util.Comparator<Map.Entry<String, Integer>>() {
                    public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                        return b.getValue() - a.getValue();
                    }
                });
                List<Entry> list = new ArrayList<>(sorted.size());
                for (Map.Entry<String, Integer> e : sorted) {
                    list.add(new Entry(e.getKey(), e.getValue()));
                }
                out.put(date, list);
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            Log.w(TAG, "pc-activity parse failed: " + t);
            return null;
        }
    }

    private static String extractUpdated(String json) {
        try {
            Matcher m = UPD_P.matcher(json);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /* ==================== 展示名 ==================== */

    /** PC 进程名 → 人话。没映射的原样显示（进程名本来就是给懂的人看的）。 */
    public static String label(String proc) {
        if (proc == null) {
            return "";
        }
        String k = proc.toLowerCase(java.util.Locale.US);
        if (k.equals("msedge")) return "Edge";
        if (k.startsWith("chrome")) return "Chrome";
        if (k.startsWith("firefox")) return "Firefox";
        if (k.startsWith("qq")) return "QQ";
        if (k.startsWith("wechat") || k.startsWith("weixin")) return T.s("微信");
        if (k.startsWith("steam")) return "Steam";
        if (k.startsWith("valorant")) return "VALORANT";
        if (k.equals("explorer")) return T.s("资源管理器");
        if (k.equals("lockapp")) return T.s("锁屏");
        if (k.equals("searchhost") || k.equals("searchapp")) return T.s("搜索");
        if (k.equals("applicationframehost")) return T.s("UWP 应用");
        if (k.equals("systemsettings")) return T.s("系统设置");
        if (k.equals("cmd") || k.equals("conhost") || k.equals("windowsterminal")) return T.s("终端");
        if (k.equals("code")) return "VS Code";
        if (k.equals("node")) return "Node";
        if (k.startsWith("python")) return "Python";
        if (k.equals("powerpnt")) return "PowerPoint";
        if (k.equals("winword")) return "Word";
        if (k.equals("excel")) return "Excel";
        return proc;
    }
}
