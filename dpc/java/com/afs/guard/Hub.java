package com.afs.guard;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared Gist hub. Study data is bidirectional from v1.31.3 onward.
 * LedgerMerge compares each side with the last common snapshot, deduplicates IDs,
 * propagates edits/archives/deletions, and preserves offline additions.
 * Configuration and activity files retain their existing per-device ownership.
 */
public final class Hub {

    private static final String TAG = Guard.TAG;
    private static final String FILE = "afs_hub";

    /** 常规节流：10 分钟内最多完整同步一次。 */
    private static final long SYNC_MS = 60_000L;
    /** 使用统计推送节流：1 小时一次够了（PC 面板没在等它实时）。 */
    private static final long ACT_PUSH_MS = 3_600_000L;
    /** 单次网络容忍：超过就允许下一轮重试（老线程自生自灭）。 */
    private static final long LIVENESS_MS = 90_000L;

    private static final Object RUNLOCK = new Object();
    private static boolean running;

    private Hub() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SyncConfig.cacheName(c, FILE), Context.MODE_PRIVATE);
    }

    /* ---------- 触发 ---------- */

    /** 打开 App / 统计页时踢一脚：脏标记优先，否则 10 分钟节流。 */
    public static void kick(Context c) {
        final Context app = c.getApplicationContext();
        boolean dirty = sp(app).getBoolean("study_dirty", false)
                || sp(app).getBoolean("cfg_dirty", false);
        long now = System.currentTimeMillis();
        if (!dirty && now - sp(app).getLong("last_sync", 0L) < SYNC_MS) {
            return;
        }
        start(app);
    }

    /** 本机数据变了（打卡 / 改目标 / 改时段）→ 尽快推台账。 */
    public static void markStudyDirty(Context c) {
        sp(c).edit().putBoolean("study_dirty", true).apply();
        start(c);
    }

    /** 本机屏蔽规则/时段变了 → 尽快推配置分节。 */
    public static void markConfigDirty(Context c) {
        sp(c).edit().putBoolean("cfg_dirty", true).apply();
        start(c);
    }

    private static void start(final Context app) {
        if (!SyncConfig.configured(app)) return;
        synchronized (RUNLOCK) {
            if (running) {
                return;   // 在跑的这一轮结束前会重读脏标记，不会丢
            }
            running = true;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    sync(app);
                } catch (Throwable t) {
                    Log.w(TAG, "hub sync failed: " + t);
                } finally {
                    synchronized (RUNLOCK) {
                        running = false;
                    }
                }
            }
        }, "afs-hub").start();
    }

    private static boolean sync(final Context c) {
        if (!SyncConfig.configured(c)) return false;
        try (SyncConfig.Session session = SyncConfig.acquire(c)) { return sync(c, session); }
    }

    private static boolean sync(final Context c, SyncConfig.Session session) {
        sp(c).edit().putLong("last_sync", System.currentTimeMillis()).apply();
        boolean allOk = true;
        try {
            syncLedger(c, session);       // 打卡台账：hub 的核心
        } catch (Throwable t) {
            allOk = false;
            Log.w(TAG, "hub ledger: " + t);
        }
        try {
            syncConfig(c, session);       // 配置分节
        } catch (Throwable t) {
            allOk = false;
            Log.w(TAG, "hub config: " + t);
        }
        try {
            syncPhoneActivity(c, session); // 手机端使用统计
        } catch (Throwable t) {
            allOk = false;
            Log.w(TAG, "hub activity: " + t);
        }
        if (!allOk) {
            // 失败不占节流窗口：下一次 kick 立刻可以重试，不然一次网络抖动
            // 会把重试推到 10 分钟以后
            sp(c).edit().putLong("last_sync", 0L).apply();
        }
        return allOk;
    }

    /**
     * 人工推送（主页按钮）：无视 10 分钟节流，同步等一轮完整 sync 结束，返回是否全成功。
     * 调用方自己放后台线程，别在主线程等网络。已有同步在跑时不叠加线程，
     * 本次的脏改动会被那一轮带上，视为已受理。
     */
    public static boolean pushNowSync(Context c) {
        final Context app = c.getApplicationContext();
        sp(app).edit().putBoolean("study_dirty", true).apply();
        synchronized (RUNLOCK) {
            if (running) {
                return false;
            }
            running = true;
        }
        try {
            return sync(app);
        } catch (Throwable t) {
            Log.w(TAG, "hub pushNow: " + t);
            return false;
        } finally {
            synchronized (RUNLOCK) {
                running = false;
            }
        }
    }

    /* ==================== 打卡台账 ==================== */

    /**
     * 手机是台账权威：非空库整份直推（无合并）；空库且 hub 有数据 → 整库恢复。
     * 推送条件：脏标记 / hub 还没有台账 / hub rev 和上次推的不一致。
     */
    private static void syncLedger(Context c, SyncConfig.Session session) throws Exception {
        String saved = sp(c).getString("ledger_base", "");
        JSONObject base = saved.isEmpty() ? null : new JSONObject(saved);
        JSONObject hub = Gist.readLedger(session);
        boolean dirty = sp(c).getBoolean("study_dirty", false);
        JSONObject merged = StudyStore.mergeHub(c, hub, base, dirty);
        if (!LedgerMerge.sameData(merged, hub)) {
            // Re-read before publishing; never replace unseen phone/PC additions.
            JSONObject latest = Gist.readLedger(session);
            if (!LedgerMerge.sameData(latest, hub))
                merged = StudyStore.mergeHub(c, latest, hub, true);
            merged.put("rev", latest.optInt("rev", 0) + 1);
            merged.put("updated", stamp());
            String err = Gist.patchFiles(session, singleton("checkin-ledger.json", merged.toString()));
            if (err != null) throw new IllegalStateException("ledger sync: " + err);
        }
        // Remember precisely what was sent, not edits made while PATCH was in flight.
        boolean stillDirty = !LedgerMerge.sameData(new JSONObject(StudyStore.exportJson(c)), merged);
        sp(c).edit().putString("ledger_base", merged.toString())
                .putBoolean("study_dirty", stillDirty)
                .putInt("ledger_rev", merged.optInt("rev", 0))
                .putLong("last_push_at", System.currentTimeMillis()).apply();
        Log.i(TAG, "hub ledger synced rev=" + merged.optInt("rev", 0)
                + " goals=" + merged.getJSONArray("goals").length()
                + " recs=" + merged.getJSONArray("recs").length());
    }

    /** 上一次把台账成功推上云的时间（0 = 还没推过）。给界面显示"已上传"用。 */
    public static long lastPushAt(Context c) {
        return sp(c.getApplicationContext()).getLong("last_push_at", 0L);
    }

    /** 本机记录的那份台账版本号（hub 上对齐到的 rev）。 */
    public static int ledgerRev(Context c) {
        return sp(c.getApplicationContext()).getInt("ledger_rev", 0);
    }

    /* ==================== 配置分节 ==================== */

    /**
     * phone 分节双向：只有本地变 = 推；只有 hub 变 = 拉应用；都变/首次 = 本地赢。
     * phone 配置 = 黑名单规则 + 学习联动时段（这两样在手机上就是全部可配）。
     */
    private static void syncConfig(Context c, SyncConfig.Session session) throws Exception {
        JSONObject local = new JSONObject();
        local.put("rules", new JSONObject(RuleStore.exportRules(c)));
        local.put("study", new JSONObject()
                .put("start", StudyStore.spanStart(c))
                .put("end", StudyStore.spanEnd(c)));
        String localHash = sha256(local.toString());

        JSONObject hub = Gist.readConfig(session);
        JSONObject phone = hub.optJSONObject("phone");
        String hubHash = phone == null ? "" : phone.optString("hash", "");
        String stored = sp(c).getString("cfg_hash", "");
        boolean cfgDirty = sp(c).getBoolean("cfg_dirty", false);

        if (hubHash.equals(localHash)) {
            sp(c).edit().putString("cfg_hash", localHash).putBoolean("cfg_dirty", false).apply();
            return;
        }
        boolean localNewer = (hubHash.equals(stored) && !stored.isEmpty()) || cfgDirty
                || (hubHash.isEmpty() && stored.isEmpty());
        if (!localNewer && localHash.equals(stored)) {
            // 只有 hub 变了 → 拉回来应用
            JSONObject cfg = phone == null ? null : phone.optJSONObject("config");
            if (cfg != null) {
                JSONObject rules = cfg.optJSONObject("rules");
                if (rules != null) {
                    RuleStore.importRules(c, rules.toString());
                }
                JSONObject study = cfg.optJSONObject("study");
                if (study != null) {
                    StudyStore.setSpan(c, study.optString("start", "22:00"),
                            study.optString("end", "23:00"));
                }
                StudyGate.invalidate(c);
                try {
                    Guard.apply(c);
                } catch (Throwable ignored) {
                }
            }
            sp(c).edit().putString("cfg_hash", localHash).apply();
            Log.i(TAG, "hub config pulled (phone section)");
            return;
        }

        // 本地新（或首次/冲突，本地赢）：读-改-写，只换 phone 分节
        hub.put("phone", new JSONObject()
                .put("updated", stamp())
                .put("writtenBy", "phone")
                .put("hash", localHash)
                .put("config", local));
        hub.put("rev", hub.optInt("rev", 0) + 1);
        hub.put("updated", stamp());
        String err = Gist.patchFiles(session, singleton("hub-config.json", hub.toString()));
        if (err == null) {
            sp(c).edit().putString("cfg_hash", localHash).putBoolean("cfg_dirty", false).apply();
            Log.i(TAG, "hub config pushed (phone section)");
        } else {
            throw new IllegalStateException("config push: " + err);
        }
    }

    /* ==================== 手机端使用统计 ==================== */

    private static final int ACT_DAYS = 14;
    private static final int ACT_TOP = 10;

    /** 与 PC 的 pc-activity.json 同构：{updated, days:{date:{apps:{pkg:min}}}}。 */
    private static void syncPhoneActivity(Context c, SyncConfig.Session session) throws Exception {
        long last = sp(c).getLong("last_act", 0L);
        long now = System.currentTimeMillis();
        if (now - last < ACT_PUSH_MS) {
            return;
        }
        if (!StatsActivity.hasUsageAccess(c)) {
            return;   // 没授权就安静跳过（统计页里会提示去开）
        }
        UsageStatsManager usm = (UsageStatsManager)
                c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return;
        }
        Calendar day0 = Calendar.getInstance();
        day0.set(Calendar.HOUR_OF_DAY, 0);
        day0.set(Calendar.MINUTE, 0);
        day0.set(Calendar.SECOND, 0);
        day0.set(Calendar.MILLISECOND, 0);
        long start = day0.getTimeInMillis() - (long) (ACT_DAYS - 1) * 86400000L;

        LinkedHashMap<String, LinkedHashMap<String, Integer>> days = new LinkedHashMap<>();
        try {
            List<UsageStats> list = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, start, now);
            if (list != null) {
                for (UsageStats u : list) {
                    if (u == null) {
                        continue;
                    }
                    long ms = Math.max(0, u.getTotalTimeInForeground());
                    if (ms < 60_000L) {
                        continue;   // 不足 1 分钟的前台不值得上账
                    }
                    String date = ymd(u.getFirstTimeStamp());
                    LinkedHashMap<String, Integer> apps = days.get(date);
                    if (apps == null) {
                        apps = new LinkedHashMap<>();
                        days.put(date, apps);
                    }
                    String pkg = u.getPackageName();
                    if (c.getPackageName().equals(pkg)) {
                        continue;   // 自己不上榜
                    }
                    Integer old = apps.get(pkg);
                    apps.put(pkg, (old == null ? 0 : old) + (int) (ms / 60000L));
                }
            }
        } catch (Throwable ignored) {
        }
        if (days.isEmpty()) {
            return;
        }
        JSONObject out = new JSONObject();
        out.put("updated", stamp());
        JSONObject dOut = new JSONObject();
        for (Map.Entry<String, LinkedHashMap<String, Integer>> e : days.entrySet()) {
            // 每天只留前 ACT_TOP 个（PC 端同款切法，控体积）
            List<Map.Entry<String, Integer>> sorted =
                    new java.util.ArrayList<Map.Entry<String, Integer>>(e.getValue().entrySet());
            java.util.Collections.sort(sorted, new java.util.Comparator<Map.Entry<String, Integer>>() {
                public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    return b.getValue() - a.getValue();
                }
            });
            JSONObject apps = new JSONObject();
            int n = 0;
            for (Map.Entry<String, Integer> a : sorted) {
                if (n++ >= ACT_TOP) {
                    break;
                }
                apps.put(a.getKey(), a.getValue());
            }
            dOut.put(e.getKey(), new JSONObject().put("apps", apps));
        }
        out.put("days", dOut);
        String err = Gist.patchFiles(session,
                singleton("phone-activity.json", out.toString()));
        if (err == null) {
            sp(c).edit().putLong("last_act", now).apply();
            Log.i(TAG, "phone-activity pushed days=" + days.size());
        } else {
            throw new IllegalStateException("activity push: " + err);
        }
    }

    /* ==================== 小工具 ==================== */

    private static Map<String, String> singleton(String k, String v) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static String stamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static String ymd(long ms) {
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                t.get(Calendar.YEAR), t.get(Calendar.MONTH) + 1, t.get(Calendar.DAY_OF_MONTH));
    }

    private static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
