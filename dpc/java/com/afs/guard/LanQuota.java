package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 跨设备共享额度：手机这一侧的客户端。
 *
 * 模型：一个池子、一份分钟数、两台设备。电脑是权威 —— 它每秒知道自己用了多久，
 * 也是唯一能杀进程的一端；手机每次轮询把自己的用量报上去，同时拿回「电脑已用 + 合计」。
 * 判定一律看合计，不看自己那一段。
 *
 * 为什么走局域网而不是继续用 Gist：Gist 那条链路手机端 60 秒才同步一次、
 * 加上 raw 的 CDN 缓存，最坏十几分钟不对齐 —— 共享额度里这就等于白送。
 * 同一 WiFi 下直连 PC，一次往返 20ms 左右，3 秒一轮就是真实时。
 *
 * 断网时不能瞎判：
 *   · 缓存 30 秒内 → 用合计判超（正常路径）
 *   · 缓存过期     → 交回本地判定，宁可少冻也不把用户永久锁在外面
 * 也就是说「连不上」的后果是退回各自记账，不是整组放行也不是整组冻结。
 */
final class LanQuota {

    private static final String SP = "afs_lan_quota";
    private static final String TAG = "AfsLanQuota";

    /** 3 秒一轮；两端都是这个量级，合计误差不超过一次轮询。 */
    static final long POLL_MS = 3000L;
    /** 同一时刻只允许一个在飞，避免弱网下排队堆积。 */
    private static final long MIN_GAP_MS = 2200L;
    /** 超过这个年龄的缓存不再参与判定（局域网：一次往返就够新了）。 */
    static final long STALE_MS = 30_000L;
    /**
     * Gist 兜底数据的老化窗口。
     *
     * PC 每 15 分钟推一次，所以 90 分钟等于「连着 6 次没推上来」才当作无效；
     * 再长就会出现「电脑那边已经加了额度、手机还死按旧数字锁着」的坑。
     */
    static final long GIST_STALE_MS = 90 * 60_000L;
    /** Gist 拉取间隔：比局域网宽松得多，raw 本身就有 CDN 缓存。 */
    private static final long GIST_RETRY_MS = 90_000L;

    private static final int CONNECT_MS = 2500;
    private static final int READ_MS = 3500;

    private static final Object LOCK = new Object();
    private static long lastStart;
    private static long lastGistTry;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "afs-lan-quota");
        t.setDaemon(true);
        return t;
    });

    private LanQuota() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /* ---------------- 配置 ---------------- */

    static boolean active(Context c) {
        if (!sp(c).getBoolean("enabled", false)) return false;
        return !sp(c).getString("host", "").isEmpty()
                && !sp(c).getString("token", "").isEmpty()
                && !sp(c).getString("group", "").isEmpty();
    }

    static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", false);
    }

    static String host(Context c) {
        return sp(c).getString("host", "");
    }

    static int port(Context c) {
        return sp(c).getInt("port", 8738);
    }

    static String token(Context c) {
        return sp(c).getString("token", "");
    }

    static String group(Context c) {
        return sp(c).getString("group", "");
    }

    /** 这个额度组是不是那个跨设备共享的组。 */
    static boolean isShared(Context c, String groupId) {
        if (groupId == null || groupId.isEmpty()) return false;
        return active(c) && groupId.equals(sp(c).getString("group", ""));
    }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("enabled", on).apply();
    }

    /** 单独存令牌：首次配置时通常先填地址再填令牌，不能要求地址已经合法。 */
    static void setToken(Context c, String token) {
        sp(c).edit().putString("token", token == null ? "" : token.trim()).apply();
    }

    static void setGroup(Context c, String groupId) {
        sp(c).edit().putString("group", groupId == null ? "" : groupId).apply();
    }

    /** 绑定组时把名字一起存下来：列表页不必为了显示一个标题去读使用记录快照。 */
    static void setGroup(Context c, String groupId, String name) {
        sp(c).edit()
                .putString("group", groupId == null ? "" : groupId)
                .putString("groupName", name == null ? "" : name)
                .apply();
    }

    static String groupName(Context c) {
        return sp(c).getString("groupName", "");
    }

    /**
     * 保存服务器地址。允许直接粘 `192.168.1.5` / `192.168.1.5:8738` / `http://...`，
     * 少一个"格式不对"的来回。
     *
     * 只接受私有网段：明文 HTTP 已经为它放开了，不能让一个公网域名借这条路走明文。
     *
     * @return 地址合法且已保存
     */
    static boolean setServer(Context c, String raw, String token) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("http://")) text = text.substring(7);
        if (text.startsWith("https://")) text = text.substring(8);
        int slash = text.indexOf('/');
        if (slash >= 0) text = text.substring(0, slash);
        String host = text;
        int port = 8738;
        int colon = text.lastIndexOf(':');
        if (colon > 0) {
            String tail = text.substring(colon + 1);
            try {
                int parsed = Integer.parseInt(tail);
                if (parsed > 0 && parsed < 65536) {
                    port = parsed;
                    host = text.substring(0, colon);
                }
            } catch (NumberFormatException ignored) { }
        }
        if (!privateHost(host)) return false;
        sp(c).edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("token", token == null ? "" : token.trim())
                .apply();
        return true;
    }

    /** 10/8、172.16/12、192.168/16、169.254/16 与 localhost；其余一律不当局域网看。 */
    private static boolean privateHost(String host) {
        if (host == null || host.isEmpty()) return false;
        if ("localhost".equalsIgnoreCase(host)) return true;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        int a;
        int b;
        try {
            a = Integer.parseInt(parts[0]);
            b = Integer.parseInt(parts[1]);
        } catch (NumberFormatException error) {
            return false;
        }
        if (a == 10) return true;
        if (a == 172 && b >= 16 && b <= 31) return true;
        if (a == 192 && b == 168) return true;
        if (a == 169 && b == 254) return true;
        return false;
    }

    /* ---------------- 缓存视图 ---------------- */

    static int minutes(Context c) {
        return sp(c).getInt("minutes", 0);
    }

    static long pcUsed(Context c) {
        return sp(c).getLong("pcUsed", 0L);
    }

    static long phoneUsed(Context c) {
        return sp(c).getLong("phoneUsed", 0L);
    }

    static long total(Context c) {
        return sp(c).getLong("total", 0L);
    }

    static long at(Context c) {
        return sp(c).getLong("at", 0L);
    }

    static boolean fresh(Context c) {
        long stamp = at(c);
        if (stamp <= 0) return false;
        long age = System.currentTimeMillis() - stamp;
        return age <= ("gist".equals(source(c)) ? GIST_STALE_MS : STALE_MS);
    }

    /** 这份数据从哪来：lan（局域网，准）/ gist（兜底，分钟级延迟）。 */
    static String source(Context c) {
        return sp(c).getString("source", "lan");
    }

    /**
     * 共享池是否已耗尽。
     *
     * 只有缓存新鲜时才敢说"是"。缓存过期返回 false，让调用方回落到本地判定 ——
     * 用一份十分钟前的旧数据把人锁在门外，比漏判一次糟糕得多。
     */
    static boolean exhausted(Context c) {
        return fresh(c) && sp(c).getBoolean("exhausted", false);
    }

    /** 最近一次轮询失败的原因；成功后清空。设置页拿它诊断「配了但不同步」。 */
    static String lastError(Context c) {
        return sp(c).getString("lastError", "");
    }

    static void clearCache(Context c) {
        sp(c).edit().remove("minutes").remove("pcUsed").remove("phoneUsed")
                .remove("total").remove("exhausted").remove("at").remove("lastError")
                .remove("source").apply();
    }

    /* ---------------- 轮询 ---------------- */

    /**
     * 上报手机这一段的用量并取回合计。节流在调用侧做，这里只保证"同一时刻一个在飞"。
     *
     * @param phoneUsedMs 绑定组今天的已用毫秒（来自 DailyQuotaStore 的快照）
     * @param onResult    UI 线程回调；可以为 null
     */
    static void pollAsync(final Context c, final long phoneUsedMs, final Runnable onResult) {
        if (!active(c)) return;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - lastStart < MIN_GAP_MS) return;
            lastStart = now;
        }
        final Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            try {
                poll(app, phoneUsedMs);
                sp(app).edit().remove("lastError").apply();
            } catch (Throwable error) {
                Log.w(TAG, "lan quota poll failed: " + error);
                // 后台线程的异常没人看得到，落一份给设置页显示 —— 否则
                //「配置全对但就是不同步」会变成一个查不出原因的悬案。
                String detail = error.getMessage() == null ? error.toString() : error.getMessage();
                sp(app).edit().putString("lastError", detail).apply();
                // 主通道断了 → Gist 兜底。在家外也能看到电脑那边用了多少，
                // 虽然只能分钟级，总比两边各记各的强。
                try { pullGist(app, phoneUsedMs); } catch (Throwable ignored) { }
            } finally {
                if (onResult != null) {
                    try { onResult.run(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private static void poll(Context c, long phoneUsedMs) throws Exception {
        SharedPreferences p = sp(c);
        String host = p.getString("host", "");
        int port = p.getInt("port", 8738);
        String token = p.getString("token", "");
        String group = p.getString("group", "");
        if (host.isEmpty() || token.isEmpty()) return;
        JSONObject body = new JSONObject();
        body.put("usedSeconds", Math.max(0L, phoneUsedMs) / 1000.0);
        body.put("group", group);
        JSONObject o = call(host, port, token, body);
        apply(c, o);
    }

    /** 设置页「测试连接」用：不写缓存，只把结果抛出来给界面显示。 */
    static JSONObject probe(String host, int port, String token) throws Exception {
        return call(host, port, token, null);
    }

    private static JSONObject call(String host, int port, String token, JSONObject body) throws Exception {
        String url = "http://" + host + ":" + port + "/quota?token=" + token;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "AFS-Guard");
            conn.setRequestProperty("Cache-Control", "no-store");
            if (body != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            } else {
                conn.setRequestMethod("GET");
            }
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IllegalStateException(T.s("电脑端返回 HTTP ") + status);
            }
            try (InputStream in = conn.getInputStream()) {
                return new JSONObject(read(in));
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void apply(Context c, JSONObject o) {
        int minutes = o.optInt("poolMinutes", 0);
        long pcUsed = (long) (o.optDouble("pcUsed", 0) * 1000);
        long phoneUsed = (long) (o.optDouble("phoneUsed", 0) * 1000);
        long total = (long) (o.optDouble("total", 0) * 1000);
        sp(c).edit()
                .putInt("minutes", minutes)
                .putLong("pcUsed", pcUsed)
                .putLong("phoneUsed", phoneUsed)
                .putLong("total", total)
                .putBoolean("exhausted", o.optBoolean("exhausted", false))
                .putLong("at", System.currentTimeMillis())
                .putString("source", "lan")
                .apply();
    }

    /**
     * 兜底：从同步 Gist 拉 PC 推上来的 quota-pool.json。
     *
     * 这里只用它当「电脑那一半」的估计值，手机自己的用量照旧本地算，
     * 不把 Gist 上的 phoneUsed 拿回来用 —— 那是上一次连通时上报的旧值，
     * 比当前真实值小，用它会多给用户几分钟。
     */
    private static void pullGist(Context c, long phoneUsedMs) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - lastGistTry < GIST_RETRY_MS) return;
            lastGistTry = now;
        }
        SyncTarget target = SyncConfig.target(c);
        if (target == null) return;
        String raw;
        try {
            raw = StudyGate.fetchRaw(target.rawUrl("quota-pool.json") + "?t=" + now);
        } catch (Exception error) {
            Log.w(TAG, "quota-pool gist fetch failed: " + error);
            return;
        }
        if (raw == null || !raw.trim().startsWith("{")) return;
        JSONObject o;
        try {
            o = new JSONObject(raw);
        } catch (Exception error) {
            return;
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        // 隔天的账不能往今天扣：Gist 上躺着的可能是昨晚推的。
        if (!today.equals(o.optString("date", ""))) return;
        int minutes = o.optInt("minutes", 0);
        if (minutes <= 0) return;
        long pcUsed = (long) (o.optDouble("pcUsed", 0) * 1000);
        long phoneUsed = Math.max(0L, phoneUsedMs);
        long total = pcUsed + phoneUsed;
        sp(c).edit()
                .putInt("minutes", minutes)
                .putLong("pcUsed", pcUsed)
                .putLong("phoneUsed", phoneUsed)
                .putLong("total", total)
                .putBoolean("exhausted", total >= minutes * 60_000L)
                .putLong("at", System.currentTimeMillis())
                .putString("source", "gist")
                .apply();
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    /** 给额度页显示用：「与电脑共享 · 剩 12 分钟」；Gist 兜底时标「离线估算」。 */
    static String summary(Context c) {
        if (!active(c)) return "";
        if (!fresh(c)) return T.s("未连接电脑");
        int minutes = minutes(c);
        long remainSec = Math.max(0L, minutes * 60L - total(c) / 1000L);
        if ("gist".equals(source(c))) return T.f("离线估算 · 剩 %d 分钟", remainSec / 60);
        return T.f("与电脑共享 · 剩 %d 分钟", remainSec / 60);
    }
}
