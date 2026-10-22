package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 学习闸门：把「今天的学习目标是否全部打卡」接到冻结判定里。
 *
 * 数据链路（与 Windows 端共用一条）：
 *
 *   WorkBuddy 学习台(云端表)
 *        │ 每 15 分钟
 *        ▼
 *   PC study_sync ──▶ study.flag ──▶ PC 引擎
 *        └──▶ GitHub secret Gist 的 study-gate.json
 *                  │ 手机每 ~10 分钟 GET raw（secret gist 的 raw 免鉴权）
 *                  ▼
 *             StudyGate
 *
 * 生效条件（全部满足才延长冻结）：
 *   enabled(PC 下发) && 数据有效 && date 是今天 && checkedAt 未超龄
 *   && 当前时间在 [start, end) && !allDone（还有目标没打卡）
 *
 * fail-open 与 PC 端同一套约定：没拉到过 / JSON 损坏 / 日期不是今天 /
 * 超过 {@link #STALE_MS} 没更新 —— 一律放行。宁可不生效，也不锁死。
 *
 * 手机端没有任何开关 —— enabled 由 PC 端下发，手机上想关只能去电脑改。
 * 和「黑名单只进不出」同一设计哲学：绕路只有一条，且不在手机上。
 */
public final class StudyGate {

    private static final String TAG = Guard.TAG;

    /** 同步 Gist（PC 端「账号/同步」建的同一个，secret：raw URL 知道就能读）。 */
    public static final String GIST_ID = "9c5c1f32f5159e085b3686d7a00a8f77";
    public static final String GIST_FILE = "study-gate.json";

    /** 数据超龄线：PC 每 15 分钟推一次，45 分钟没更新视为失效（与 PC fail-open 一致）。 */
    public static final long STALE_MS = 45L * 60L * 1000L;

    /** 拉取节流：不管成败，10 分钟内最多拉一次（gist raw 本身还有 ~5 分钟 CDN 缓存）。 */
    public static final long REFRESH_MS = 10L * 60L * 1000L;

    private StudyGate() {
    }

    /* ==================== 快照（纯数据，离线回归直接跑） ==================== */

    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(
                false, false, 0, 0, true, "", -1L);

        /** JSON 能解析且关键字段都在。 */
        public final boolean valid;
        /** PC 端学习联动开关（false 时永远放行）。 */
        public final boolean enabled;
        /** 延长冻结的区间（分钟，支持跨午夜：start > end）。 */
        public final int startMin;
        public final int endMin;
        /** 今天的学习目标是否已全部打卡。 */
        public final boolean allDone;
        /** 数据里的日期（yyyy-MM-dd），用于判定是不是今天。 */
        public final String date;
        /** checkedAt 解析出的本地时间；-1 = 解析失败。 */
        public final long checkedAtMs;

        Snapshot(boolean valid, boolean enabled, int startMin, int endMin,
                 boolean allDone, String date, long checkedAtMs) {
            this.valid = valid;
            this.enabled = enabled;
            this.startMin = startMin;
            this.endMin = endMin;
            this.allDone = allDone;
            this.date = date;
            this.checkedAtMs = checkedAtMs;
        }

        /** 数据是否可信：解析成功 + 是今天的 + 没超龄。 */
        public boolean trustworthy(String todayStr, long nowMs) {
            return valid
                    && date != null && date.equals(todayStr)
                    && checkedAtMs > 0L
                    && nowMs - checkedAtMs >= 0L
                    && nowMs - checkedAtMs <= STALE_MS;
        }
    }

    /** yyyy-MM-dd HH:mm → 本地时间毫秒；解析失败返回 -1。 */
    static long parseStamp(String s) {
        if (s == null || s.length() < 10) {
            return -1L;
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            f.setLenient(false);
            Date d = f.parse(s.trim());
            return d == null ? -1L : d.getTime();
        } catch (Throwable t) {
            // 宽限：只有日期没有时间（"yyyy-MM-dd"）按当天 00:00 算，照样能参与超龄判定
            try {
                SimpleDateFormat f2 = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                f2.setLenient(false);
                Date d = f2.parse(s.trim());
                return d == null ? -1L : d.getTime();
            } catch (Throwable t2) {
                return -1L;
            }
        }
    }

    /** HH:mm → 分钟；不合法返回 -1。 */
    static int parseHm(String s) {
        if (s == null) {
            return -1;
        }
        String t = s.trim();
        int colon = t.indexOf(':');
        if (colon <= 0 || colon == t.length() - 1) {
            return -1;
        }
        try {
            int h = Integer.parseInt(t.substring(0, colon));
            int m = Integer.parseInt(t.substring(colon + 1));
            if (h < 0 || h > 24 || m < 0 || m > 59) {
                return -1;
            }
            return Rule.norm(h * 60 + m);
        } catch (Throwable e) {
            return -1;
        }
    }

    /* ---------- 极简扁平 JSON 提取 ---------- */

    /**
     * 从扁平 JSON 对象里取字符串值。study-gate.json 只有 6 个平铺字段，
     * 用不着 org.json（它在 android.jar 里是 stub，离线回归跑不了 —— Rule 的
     * 可测性就是靠零 Android/JSON 依赖换来的，这里照做）。
     * 支持 "key":"value" / "key":true / "key":false / "key":123。
     */
    static String jget(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) {
            return null;
        }
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '\t')) {
            i++;
        }
        if (i >= json.length()) {
            return null;
        }
        char c = json.charAt(i);
        if (c == '"') {
            int end = json.indexOf('"', i + 1);
            // 处理 \" 转义（数据源是 PC 端自己拼的，一般不会有；防御性扫一遍）
            int p = i + 1;
            while (p < json.length()) {
                char q = json.charAt(p);
                if (q == '\\' && p + 1 < json.length()) {
                    p += 2;
                    continue;
                }
                if (q == '"') {
                    end = p;
                    break;
                }
                p++;
            }
            if (end < 0 || end <= i + 1) {
                return null;
            }
            return json.substring(i + 1, end);
        }
        // 裸值：true / false / 数字，读到逗号或右花括号
        int end = i;
        while (end < json.length()) {
            char q = json.charAt(end);
            if (q == ',' || q == '}' || q == ' ' || q == '\n' || q == '\r' || q == '\t') {
                break;
            }
            end++;
        }
        return json.substring(i, end);
    }

    static boolean jBool(String json, String key, boolean dflt) {
        String v = jget(json, key);
        if (v == null) {
            return dflt;
        }
        if (v.equals("true")) {
            return true;
        }
        if (v.equals("false")) {
            return false;
        }
        return dflt;
    }

    /** JSON 文本 → 快照。任何结构问题都落到 valid=false（fail-open）。
     *  ★ 纯函数，不碰 android.* 和 org.json —— 离线回归直接跑；
     *    坏 JSON 在这里静默降级，不抛不响。 */
    public static Snapshot parse(String json, String todayStr, long nowMs) {
        if (json == null || json.trim().length() == 0) {
            return Snapshot.EMPTY;
        }
        try {
            if (jget(json, "error") != null) {
                return Snapshot.EMPTY;   // PC 端明确报错 → 不可信
            }
            // 最外层必须是个对象
            String t = json.trim();
            if (t.charAt(0) != '{' || t.charAt(t.length() - 1) != '}') {
                return Snapshot.EMPTY;
            }
            Snapshot snap = new Snapshot(
                    true,
                    jBool(json, "enabled", false),
                    parseHm(jget(json, "start")),
                    parseHm(jget(json, "end")),
                    jBool(json, "allDone", true),
                    jget(json, "date"),
                    parseStamp(jget(json, "checkedAt")));
            return snap;
        } catch (Throwable th) {
            return Snapshot.EMPTY;
        }
    }

    /**
     * 区间判定，跨午夜语义与 {@link Rule#inWindow} 一致：
     * s == e 全天；s < e 普通段；s > e 跨午夜（now >= s 或 now < e）。
     */
    public static boolean inSpan(int nowMin, int s, int e) {
        if (s < 0 || e < 0) {
            return false;   // 区间不合法 → 不 hold
        }
        if (s == e) {
            return true;
        }
        if (s < e) {
            return nowMin >= s && nowMin < e;
        }
        return nowMin >= s || nowMin < e;
    }

    /**
     * 纯判定：此刻是否应该延长冻结。
     * 数据不可信、开关关、已全部打卡 → false（放行）。
     */
    public static boolean shouldHold(Snapshot snap, Calendar now) {
        if (snap == null) {
            return false;
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.getTime());
        if (!snap.trustworthy(today, now.getTimeInMillis())) {
            return false;
        }
        if (!snap.enabled || snap.allDone) {
            return false;
        }
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return inSpan(nowMin, snap.startMin, snap.endMin);
    }

    /* ==================== Android 侧：拉取 + 缓存 ==================== */

    private static final String FILE = "afs_gate";
    private static final String K_RAW = "last_json";
    private static final String K_FETCHED = "fetched_at";

    private static volatile Snapshot cached;
    private static volatile boolean fetching;
    private static volatile long lastTryMs;
    private static volatile long fetchStartedAt;

    /** 单次拉取的最长容忍时间。DNS 解析不受 connect/read timeout 控制，
     *  代理把 GitHub 分流成黑洞时线程可能挂死 —— 超过这时长就允许重新拉，
     *  老线程让它自生自灭（结果写入有判重，不会出乱子）。 */
    public static final long FETCH_LIVENESS_MS = 30_000L;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String rawUrl() {
        return "https://gist.githubusercontent.com/sdgfdii/" + GIST_ID
                + "/raw/" + GIST_FILE;
    }

    /** 进程起来后从盘里恢复上一次的快照（重启后不空窗）。 */
    public static void init(Context c) {
        if (cached != null) {
            return;
        }
        try {
            String raw = sp(c).getString(K_RAW, "");
            long at = sp(c).getLong(K_FETCHED, 0L);
            if (raw.length() > 0) {
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .format(new Date(at));
                cached = parse(raw, today, at);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 需要时后台拉一次（节流 + 去重 + 卡死兜底）。tick / apply 每分钟都会调，绝不阻塞调用线程。
     * 拉完立即 apply 一遍 —— 闸门状态变了要在下一拍就落地，不等下一个 30 秒。
     */
    public static void refreshIfNeeded(final Context c) {
        long now = System.currentTimeMillis();
        boolean stuck = fetching && now - fetchStartedAt > FETCH_LIVENESS_MS;
        if ((fetching && !stuck) || now - lastTryMs < REFRESH_MS) {
            return;
        }
        lastTryMs = now;
        fetchStartedAt = now;
        fetching = true;
        new Thread(new Runnable() {
            public void run() {
                boolean changed = false;
                try {
                    String raw = fetchRaw();
                    long at = System.currentTimeMillis();
                    if (raw != null && raw.trim().length() > 0) {
                        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                .format(new Date(at));
                        Snapshot next = parse(raw, today, at);
                        Snapshot old = cached;
                        cached = next;
                        sp(c).edit().putString(K_RAW, raw).putLong(K_FETCHED, at).apply();
                        changed = old == null
                                ? next.valid
                                : (old.enabled != next.enabled
                                   || old.allDone != next.allDone
                                   || old.startMin != next.startMin
                                   || old.endMin != next.endMin);
                        Log.i(TAG, "study-gate fetched: hold=" + shouldHold(next, Calendar.getInstance())
                                + " allDone=" + next.allDone + " enabled=" + next.enabled);
                    } else {
                        // ★ 成功但 body 空 —— 也必须留痕, 否则线上静默没法诊断
                        Log.w(TAG, "study-gate fetch empty body");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "study-gate fetch failed: " + t);
                } finally {
                    fetching = false;
                    if (changed) {
                        try {
                            Guard.apply(c);   // 状态翻转，立刻落地
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "afs-gate").start();
    }

    /** 同步 GET raw；任何失败抛异常，由调用方兜住。 */
    static String fetchRaw() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(rawUrl()).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toString("UTF-8");
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 判定入口：Guard.apply / blockedNow 调这个。拉取节流也挂在这（纯读路径顺带触发）。 */
    public static boolean holding(Context c, Calendar now) {
        init(c);
        refreshIfNeeded(c);
        return shouldHold(cached, now);
    }

    /**
     * 首页卡片的两种展示：状态 + 文案。
     *
     * ★ ST_STALE 是必须单独区分的一态：数据过期时 shouldHold() 返回 false（fail-open），
     *   如果只看 shouldHold，界面会显示"到点继续冻结"这种乐观文案，
     *   而实际上保护早就失效了 —— 用户以为有人在管，其实没有。
     */
    public static final int ST_NONE    = 0;   // 从没拉到过 / 解析失败
    public static final int ST_STALE   = 1;   // 数据过期 → 保护已失效（不参与冻结）
    public static final int ST_OFF     = 2;   // PC 端开关关着
    public static final int ST_DONE    = 3;   // 今天目标全部打卡
    public static final int ST_ACTIVE  = 4;   // 闸门正在生效（正在延长冻结）
    public static final int ST_PENDING = 5;   // 还有目标没打卡，但还没到区间

    /** 纯判定（离线可测）：快照 → 卡片状态。 */
    public static int stateOf(Snapshot s, Calendar now) {
        if (s == null || !s.valid) {
            return ST_NONE;
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.getTime());
        if (!s.trustworthy(today, now.getTimeInMillis())) {
            return ST_STALE;
        }
        if (!s.enabled) {
            return ST_OFF;
        }
        if (s.allDone) {
            return ST_DONE;
        }
        return shouldHold(s, now) ? ST_ACTIVE : ST_PENDING;
    }

    private static String spanOf(Snapshot s) {
        if (s == null || s.startMin < 0 || s.endMin < 0) {
            return "?";
        }
        return Rule.hm(s.startMin / 60, s.startMin % 60) + "-"
                + Rule.hm(s.endMin / 60, s.endMin % 60);
    }

    /** 纯文案（离线可测）。 */
    public static String textOf(Snapshot s, Calendar now) {
        int st = stateOf(s, now);
        String span = spanOf(s);
        switch (st) {
            case ST_STALE: {
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .format(now.getTime());
                if (s.date != null && s.date.length() > 0 && !s.date.equals(today)) {
                    return "学习联动 · 保护已失效\n进度还停在 " + s.date + "，PC 端今天没在推";
                }
                long age = (now.getTimeInMillis() - s.checkedAtMs) / 60000L;
                if (age < 0) {
                    age = 0;
                }
                return "学习联动 · 保护已失效\n学习进度 " + age + " 分钟没更新，PC 端可能没开机";
            }
            case ST_OFF:
                return "学习联动 · 已关闭\nPC 端没开「目标没打完就继续屏蔽」";
            case ST_DONE:
                return "学习联动\n今天目标已全部打卡（" + span + "）";
            case ST_ACTIVE:
                return "学习联动 · 生效中\n今天还有目标没打卡，" + span + " 继续冻结";
            case ST_PENDING:
                return "学习联动\n今天还有目标没打卡 · " + span + " 到点继续冻结";
            default:
                return "学习联动\n暂无数据 · 不参与冻结";
        }
    }

    /** 首页状态行文案（describe 里展示）。 */
    public static String statusText(Context c, Calendar now) {
        init(c);
        return statusTextOf(cached, now);
    }

    static String statusTextOf(Snapshot s, Calendar now) {
        int st = stateOf(s, now);
        if (st == ST_NONE) {
            return "学习联动　暂无数据（不影响冻结）";
        }
        String span = spanOf(s);
        if (st == ST_STALE) {
            return "学习联动　⚠ 数据过期，保护已失效";
        }
        if (st == ST_OFF) {
            return "学习联动　PC 端已关闭（" + span + "）";
        }
        if (st == ST_ACTIVE) {
            return "学习联动　今天还有目标没打卡（" + span + " 延长冻结·生效中）";
        }
        if (st == ST_PENDING) {
            return "学习联动　今天还有目标没打卡（" + span + " 延长冻结）";
        }
        return "学习联动　目标已打完（" + span + "）";
    }

    /** 首页卡片状态（供 UI 选颜色）。 */
    public static int cardState(Context c, Calendar now) {
        init(c);
        return stateOf(cached, now);
    }

    /**
     * 首页独立卡片的文案（两行，大字显示）。
     * 单独一张卡是必须的 —— 埋在状态文本块里同色同字号，等于没写。
     */
    public static String cardText(Context c, Calendar now) {
        init(c);
        return textOf(cached, now);
    }

    /** 卡片是否该用"警示色"（正在拦人，或者保护已经失效）。 */
    public static boolean activeNow(Context c, Calendar now) {
        return holding(c, now);
    }
}
