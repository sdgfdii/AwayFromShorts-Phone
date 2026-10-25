package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
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
 *   && 本地时间已到当天 20:00 && !allDone（还有目标没打卡）
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
    public static final String GIST_ID = "455b0ef2f1e04db50dcd0b67aa9e51ec";
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
        /** PC 端配置的学习时段（分钟，支持跨午夜）。**只用于展示**，不参与判定 ——
         *  闸门是"没打完就直接延长冻结"，不是"在这个区间里额外冻一段"。 */
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
     * 纯判定：此刻是否应该延长冻结。
     * 数据不可信、开关关、已全部打卡 → false（放行）。
     *
     * 每天本地时间 20:00 才检查当天打卡；未完成时延长受联动应用的冻结，
     * 完成后立即恢复原时间表。startMin/endMin 只作 PC 配置展示。
     */
    public static boolean shouldHold(Snapshot snap, Calendar now) {
        if (snap == null || !snap.valid) {
            return false;
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.getTime());
        if (!snap.trustworthy(today, now.getTimeInMillis())) {
            return false;
        }
        if (!snap.enabled || snap.allDone) {
            return false;
        }
        return now.get(Calendar.HOUR_OF_DAY) >= 20;
    }

    /** 下一个学习联动边界：当天 20:00，或次日 00:00 重置。 */
    static long nextCheckBoundaryMillis(Calendar now) {
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (now.get(Calendar.HOUR_OF_DAY) < 20) {
            next.set(Calendar.HOUR_OF_DAY, 20);
            next.set(Calendar.MINUTE, 0);
        } else {
            next.add(Calendar.DAY_OF_MONTH, 1);
            next.set(Calendar.HOUR_OF_DAY, 0);
            next.set(Calendar.MINUTE, 0);
        }
        return next.getTimeInMillis();
    }

    /* ==================== Android 侧：拉取 + 缓存 ==================== */

    private static final String FILE = "afs_gate";
    private static final String K_RAW = "last_json";
    private static final String K_FETCHED = "fetched_at";
    /** 学习台导入格式的版本；v3 = 清库后重新拉一份最新 gate 数据再导。 */
    private static final String K_IMPORT_VER = "study_import_ver";
    private static final String K_REIMPORT_AT = "reimport_at";

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
        return "https://gist.githubusercontent.com/gptdds/" + GIST_ID
                + "/raw/" + GIST_FILE;
    }

    /** 进程起来后从盘里恢复上一次的快照（重启后不空窗）。 */
    public static void init(Context c) {
        String raw = "";
        if (cached == null) {
            try {
                raw = sp(c).getString(K_RAW, "");
                long at = sp(c).getLong(K_FETCHED, 0L);
                if (raw.length() > 0) {
                    String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .format(new Date(at));
                    cached = parse(raw, today, at);
                }
            } catch (Throwable ignored) {
            }
        }
        // 学习台导入 v4：库里有 PC 导入的目标（id 前缀 goal-）或干脆是空的，
        // 就清掉重来 —— 先拉一份**当下**的 gate 数据再导。
        // 电脑端刚记过账、本地数据被弄乱（删错目标）、上次导入半路失败，都靠这条路自愈。
        // 只库内全是用户手建的目标（g* 前缀）才跳过。没网时导入静默失败，
        // 1 小时节流后随 init 重试。
        if (sp(c).getInt(K_IMPORT_VER, 0) < 4) {
            boolean has = !StudyStore.goals(c).isEmpty();
            boolean fromPc = StudyStore.hasImportedGoals(c);
            if (has && !fromPc) {
                sp(c).edit().putInt(K_IMPORT_VER, 4).apply();   // 全是手建的，不碰
            } else {
                if (has) {
                    StudyStore.wipeForReimport(c);
                }
                long now = System.currentTimeMillis();
                if (now - sp(c).getLong(K_REIMPORT_AT, 0L) > 3600000L) {
                    sp(c).edit().putLong(K_REIMPORT_AT, now).apply();
                    final Context cc = c.getApplicationContext();
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                String fresh = fetchRaw(
                                        rawUrl() + "?t=" + System.currentTimeMillis());
                                sp(cc).edit().putString(K_RAW, fresh)
                                        .putLong(K_FETCHED, System.currentTimeMillis()).apply();
                                if (StudyStore.importFromGateJson(cc, fresh)) {
                                    sp(cc).edit().putInt(K_IMPORT_VER, 4).apply();
                                    invalidate(cc);
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "study reimport: " + t);
                            }
                        }
                    }).start();
                }
            }
        }
        // 本机有学习目标 → 立刻用本机数据判，别等那趟网络
        if (localMode(c)) {
            applyLocal(c);
        }
    }

    /**
     * 人工检测：不等 10 分钟节流，立刻重拉 / 重算一遍并落地。
     *
     * 给「我明明打完了，怎么还冻着」这种时刻用 —— 用户不该背机器的调度延迟。
     * 主线程别直接调：里面可能有一趟网络（走的是 refreshIfNeeded 自己的线程）。
     */
    public static void refreshNow(final Context c) {
        lastTryMs = 0L;
        localModeAt = 0L;
        localWholeAt = 0L;
        fetching = false;   // 上一次要是卡住了，也当它没卡
        refreshIfNeeded(c);
        if (localMode(c)) {
            try {
                Guard.apply(c);
            } catch (Throwable t) {
                Log.w(TAG, "refreshNow apply failed: " + t);
            }
        }
    }

    /**
     * 本机有没有学习台数据。
     *
     * 有 → 「手机自己就是学习台」，完全离线：不拉 Gist、没有"数据超龄"这一说。
     * 没有 → 保持老链路（电脑推 Gist，手机只读），老用户升级上来不断档。
     *
     * 缓存 5 秒：holding() 会被每个应用的判定各调一次（22 个应用 = 22 次），
     * 每次都读文件太浪费。
     */
    private static volatile boolean localMode;
    private static volatile long localModeAt;

    static boolean localMode(Context c) {
        long now = System.currentTimeMillis();
        if (now - localModeAt > 5000L) {
            // Memory-only users still retain the legacy remote study gate, if configured.
            localMode = !StudyStore.goals(c).isEmpty();
            localModeAt = now;
        }
        return localMode;
    }

    /** 本机数据变了（打卡 / 改目标 / 改时段）：下一拍立刻重算。 */
    public static void invalidate(Context c) {
        localModeAt = 0L;
        localWholeAt = 0L;
    }

    private static volatile long localWholeAt;

    /**
     * 用本机数据重算一次快照。
     *
     * 2 秒节流：holding() 在一轮 apply 里会被调用几十次，没必要每次都重建 JSON。
     * 打卡后 {@link #invalidate} 会把节流清零，所以"打完了立刻解闸"还是实时的。
     */
    private static void applyLocal(Context c) {
        long now = System.currentTimeMillis();
        if (now - localWholeAt < 2000L) {
            return;
        }
        localWholeAt = now;
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now));
            Snapshot next = parse(StudyStore.snapshotJson(c), today, now);
            Snapshot old = cached;
            cached = next;
            boolean changed = old == null
                    || old.allDone != next.allDone
                    || old.enabled != next.enabled
                    || old.startMin != next.startMin
                    || old.endMin != next.endMin;
            if (changed) {
                Log.i(TAG, "study-gate local: allDone=" + next.allDone
                        + " hold=" + shouldHold(next, Calendar.getInstance()));
                Guard.apply(c);
            }
        } catch (Throwable t) {
            Log.w(TAG, "study-gate local failed: " + t);
        }
    }

    /**
     * 需要时后台拉一次（节流 + 去重 + 卡死兜底）。tick / apply 每分钟都会调，绝不阻塞调用线程。
     * 拉完立即 apply 一遍 —— 闸门状态变了要在下一拍就落地，不等下一个 30 秒。
     */
    public static void refreshIfNeeded(final Context c) {
        // 本机学习台：没有"拉取"这回事，算完就完事
        if (localMode(c)) {
            applyLocal(c);
            return;
        }
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
                        // ★ 成功但 body 空 —— 也必须留痕, 否则线上静默没法诊断                    } else {
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
        return fetchRaw(rawUrl());
    }

    static String fetchRaw(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
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
        if (now.get(Calendar.HOUR_OF_DAY) >= 20 && MemoryStore.pending(c) > 0) return true;
        if (shouldHold(cached, now)) {
            return true;
        }
        // 禁忌卡（黑卡）：惩罚期未过照样冻 —— 与学习台数据解耦，
        // PC 没开机 / 联动关着 / 目标打完了，认了禁忌照样延长冻结
        return tabooHolding(now.getTimeInMillis(), tabooUntil(c));
    }

    /* ==================== 禁忌卡（黑卡） ====================
     * 吃辣 / 吃甜这类「禁忌行为」自己认领：在禁忌卡弹窗里点一张卡，
     * 冻结程序立刻延长一段惩罚时长（可叠加）。数据全在本机 SP，
     * 判定挂在 holding() 里 —— 守卫每次刷新都会重新算，绕不掉。
     */

    public static final String TABOO_SP = "afs_taboo";
    private static final String K_TABOO_UNTIL = "hold_until";
    private static final String K_TABOO_LOG = "log";
    /** 禁忌卡"今天没做"的标记：{名称: 时间戳}，按天判。 */
    private static final String K_TABOO_DECLINED = "declined";
    /** 「没做」历史流水，用于近 30 天分别统计每张禁忌卡。 */
    private static final String K_TABOO_DECLINED_LOG = "declined_log";
    /** 认领流水上限（最旧的丢掉）。 */
    private static final int TABOO_LOG_MAX = 200;

    /** 一条认领流水：什么禁忌、什么时候认的。 */
    public static class TabooClaim {
        public final String name;
        public final long ts;

        TabooClaim(String n, long t) {
            name = n;
            ts = t;
        }
    }

    /** 纯判定（离线回归可测）：惩罚期未过 → 继续冻结。 */
    public static boolean tabooHolding(long nowMs, long untilMs) {
        return untilMs > nowMs;
    }

    /** 当前惩罚期的截止时间（0 = 没有惩罚在身）。 */
    public static long tabooUntil(Context c) {
        return c.getSharedPreferences(TABOO_SP, Context.MODE_PRIVATE)
                .getLong(K_TABOO_UNTIL, 0L);
    }

    /**
     * 认领一张禁忌卡：冻结延长 penaltyMs。
     * 已有惩罚期没到期则在原到期时间上叠加（连吃两顿辣就是两倍），
     * 已过期则从现在起算。
     */
    public static void extendTaboo(Context c, long penaltyMs, long nowMs) {
        if (penaltyMs <= 0) {
            return;
        }
        SharedPreferences sp = c.getSharedPreferences(TABOO_SP, Context.MODE_PRIVATE);
        long base = Math.max(nowMs, sp.getLong(K_TABOO_UNTIL, 0L));
        sp.edit().putLong(K_TABOO_UNTIL, base + penaltyMs).apply();
    }

    /** 记一笔认领流水（打卡历史页的红色条目）。失败静默 —— 流水丢了不能影响惩罚判定。 */
    public static void recordTabooClaim(Context c, String name, long ts) {
        try {
            SharedPreferences sp = c.getSharedPreferences(TABOO_SP, Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(sp.getString(K_TABOO_LOG, "[]"));
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("ts", ts);
            a.put(o);
            sp.edit().putString(K_TABOO_LOG, capTabooLog(a, TABOO_LOG_MAX).toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 当前认领流水（最新在前），最多 limit 条。 */
    public static List<TabooClaim> tabooLog(Context c, int limit) {
        SharedPreferences sp = c.getSharedPreferences(TABOO_SP, Context.MODE_PRIVATE);
        return tabooLogParse(sp.getString(K_TABOO_LOG, "[]"), limit);
    }

    /** 纯解析（离线回归可测）：log JSON → 最新在前的流水。坏数据当不存在。 */
    public static List<TabooClaim> tabooLogParse(String json, int limit) {
        List<TabooClaim> out = new ArrayList<TabooClaim>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = a.length() - 1; i >= 0 && out.size() < limit; i--) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) {
                    String n = o.optString("name", "").trim();
                    if (!n.isEmpty()) {
                        out.add(new TabooClaim(n, o.optLong("ts")));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 纯裁剪（离线回归可测）：只留最近 max 条（丢数组头 = 最旧的）。 */
    static JSONArray capTabooLog(JSONArray a, int max) {
        while (a.length() > max) {
            JSONArray na = new JSONArray();
            for (int i = 1; i < a.length(); i++) {
                na.put(a.opt(i));
            }
            a = na;
        }
        return a;
    }

    /* ---------- 每天一张：认领 / 没做 的口径 ----------
     * 打卡页把禁忌卡并进卡组，排在白卡（目标）前面 —— 它「每天都有」，
     * 所以判据必须是"今天认过没有"，而不是"曾经认过"。
     */

    /**
     * 纯判定（离线回归可测）：某禁忌项在 [fromMs, toMs) 内是否认领过。
     * 认领流水是打卡历史的数据源，直接拿它当"今天做过"的判据，不另存状态。
     */
    public static boolean tabooClaimedOn(String logJson, String name, long fromMs, long toMs) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (TabooClaim c : tabooLogParse(logJson, TABOO_LOG_MAX)) {
            if (name.equals(c.name) && c.ts >= fromMs && c.ts < toMs) {
                return true;
            }
        }
        return false;
    }

    public static boolean tabooClaimedOn(Context c, String name, long fromMs, long toMs) {
        return tabooClaimedOn(c.getSharedPreferences(TABOO_SP, Context.MODE_PRIVATE)
                .getString(K_TABOO_LOG, "[]"), name, fromMs, toMs);
    }

    /**
     * 「今天没做」：禁忌卡下滑的落点。只影响卡片当天的队列位置（不再拦着往下推），
     * 不影响惩罚判定 —— 没做本来就不用延长冻结。
     */
    public static void declineTaboo(Context c, String name, long ts) {
        if (name == null || name.isEmpty()) {
            return;
        }
        SharedPreferences sp = c.getSharedPreferences(TABOO_SP, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(sp.getString(K_TABOO_DECLINED, "{}"));
            // 只留最近 7 天：表里没必要堆整年的"没做"
            JSONObject keep = new JSONObject();
            long edge = ts - 7L * 24 * 3600_000L;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                long v = o.optLong(k, 0L);
                if (v >= edge) {
                    keep.put(k, v);
                }
            }
            keep.put(name, ts);
            JSONArray history = new JSONArray(sp.getString(K_TABOO_DECLINED_LOG, "[]"));
            JSONObject event = new JSONObject();
            event.put("name", name);
            event.put("ts", ts);
            history.put(event);
            sp.edit().putString(K_TABOO_DECLINED, keep.toString())
                    .putString(K_TABOO_DECLINED_LOG,
                            capTabooLog(history, TABOO_LOG_MAX).toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 历史页读取「没做」的禁忌卡；兼容升级前仅存最近一次结果的旧数据。 */
    public static List<TabooClaim> tabooDeclineLog(Context c) {
        SharedPreferences sp = c.getSharedPreferences(TABOO_SP, Context.MODE_PRIVATE);
        List<TabooClaim> out = tabooLogParse(sp.getString(K_TABOO_DECLINED_LOG, "[]"),
                TABOO_LOG_MAX);
        try {
            JSONObject recent = new JSONObject(sp.getString(K_TABOO_DECLINED, "{}"));
            java.util.Iterator<String> names = recent.keys();
            while (names.hasNext()) {
                String name = names.next();
                long ts = recent.optLong(name, 0L);
                if (ts > 0L) out.add(new TabooClaim(name, ts));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 纯判定（离线回归可测）：某禁忌项在 [from, to) 内是否滑过「没做」。 */
    public static boolean tabooDeclinedOn(String json, String name, long fromMs, long toMs) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        try {
            long ts = new JSONObject(json).optLong(name, 0L);
            return ts > 0 && ts >= fromMs && ts < toMs;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean tabooDeclinedOn(Context c, String name, long fromMs, long toMs) {
        return tabooDeclinedOn(c.getSharedPreferences(TABOO_SP, Context.MODE_PRIVATE)
                .getString(K_TABOO_DECLINED, "{}"), name, fromMs, toMs);
    }

    /**
     * 禁忌项列表（SP 里存 JSONArray 字符串，保序）。
     * 打卡页的卡组和首页的"打卡"按钮都读这一份，避免两处口径跑偏。
     */
    public static List<String> tabooItems(Context c) {
        return tabooItems(c.getSharedPreferences(TABOO_SP, Context.MODE_PRIVATE)
                .getString("items", "[]"));
    }

    /** 纯解析（离线可测）：items 的 JSONArray 文本 → 名称列表。 */
    public static List<String> tabooItems(String itemsJson) {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray a = new JSONArray(itemsJson);
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i).trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 今天还没答的禁忌卡张数（认了 / 明确没做都算答过）。
     * 禁忌卡已经并进打卡队列，所以首页那句"还有 N 个"必须把它们算上 ——
     * 不然按钮写着"今天已经打完"，点进去却还有黑卡等着。
     */
    public static int tabooPendingToday(Context c) {
        long now = System.currentTimeMillis();
        SharedPreferences sp = c.getSharedPreferences(TABOO_SP, Context.MODE_PRIVATE);
        return tabooPendingOn(sp.getString("items", "[]"),
                sp.getString(K_TABOO_LOG, "[]"),
                sp.getString(K_TABOO_DECLINED, "{}"),
                dayStartMs(now), dayEndMs(now));
    }

    /** 纯判定（离线可测）：[from, to) 内还有几张禁忌卡没答。 */
    public static int tabooPendingOn(String itemsJson, String logJson, String declinedJson,
                                     long fromMs, long toMs) {
        int n = 0;
        for (String s : tabooItems(itemsJson)) {
            if (!tabooClaimedOn(logJson, s, fromMs, toMs)
                    && !tabooDeclinedOn(declinedJson, s, fromMs, toMs)) {
                n++;
            }
        }
        return n;
    }

    /** 本地零点（禁忌卡的"每天"从这儿翻页）。 */
    static long dayStartMs(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static long dayEndMs(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStartMs(now));
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
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
    public static final int ST_PENDING = 5;   // 还有目标没打卡

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

    /** PC 端配置的学习时段，只在"已关闭"那一态里露出来（说明它配的是什么）。 */
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
                return "学习联动\n今天目标已全部打卡 · 按时间表放行";
            case ST_ACTIVE:
                return "学习联动 · 生效中\n今天还有目标没打卡 · 相关应用直接延长冻结";
            case ST_PENDING:
                return "学习联动\n今天还有目标没打卡 · 20:00 检查是否延长冻结";
            default:
                return "学习联动\n暂无数据 · 不参与冻结";
        }
    }

    /** 首页状态行文案（describe 里展示）。 */
    public static String statusText(Context c, Calendar now) {
        init(c);
        int pending = MemoryStore.pending(c);
        return statusTextOf(cached, now) + (pending > 0 ? "\n记忆复习　还有 " + pending + " 个目标"
                + (now.get(Calendar.HOUR_OF_DAY) >= 20 ? "（延长冻结中）" : "（20:00 检查）") : "");
    }

    static String statusTextOf(Snapshot s, Calendar now) {
        int st = stateOf(s, now);
        if (st == ST_NONE) {
            return "学习联动　暂无数据（不影响冻结）";
        }
        if (st == ST_STALE) {
            return "学习联动　⚠ 数据过期，保护已失效";
        }
        if (st == ST_OFF) {
            return "学习联动　PC 端已关闭（原配置 " + spanOf(s) + "）";
        }
        if (st == ST_ACTIVE) {
            return "学习联动　今天还有目标没打卡（相关应用直接延长冻结）";
        }
        if (st == ST_PENDING) {
            return "学习联动　20:00 检查今日打卡，未完成再延长冻结";
        }
        return "学习联动　目标已打完（按时间表放行）";
    }

    /** 首页卡片状态（供 UI 选颜色）。 */
    public static int cardState(Context c, Calendar now) {
        init(c);
        return stateOf(cached, now);
    }

    /**
     * 首页「打卡」按钮的文案（纯函数，离线可测）。
     *
     * 学习联动原来有一张独立卡片，但它的 onClick 就是打开打卡页 —— 主页上
     * 两块指向同一个页面。现在整张卡并进这颗按钮：前面是"还欠几张"，
     * 后缀是"现在到底冻不冻"。
     *
     * @param state {@link #ST_*}；ST_STALE 优先（数据不更新 = 闸门其实没在拦人，
     *              这比"还剩几个"更要紧）
     * @param hold  {@link #holding} 的结果，含禁忌惩罚期
     */
    public static String checkinLabel(int pend, int tabooPending, int state, boolean hold) {
        String text;
        if (pend > 0 && tabooPending > 0) {
            text = "打卡 · 还有 " + pend + " 个目标 · 禁忌 " + tabooPending;
        } else if (pend > 0) {
            text = "打卡 · 还有 " + pend + " 个目标";
        } else if (pend == 0 && tabooPending > 0) {
            text = "打卡 · 只剩禁忌 " + tabooPending + " 张";
        } else if (pend == 0) {
            text = "打卡 · 今天已经打完";
        } else {
            text = "打卡";
        }
        if (state == ST_STALE) {
            text += " · ⚠ 联动过期";
        } else if (hold) {
            text += " · 冻结中";
        }
        return text;
    }

    /**
     * 学习联动的完整文案（两行）。
     * 首页那张卡片已经撤了，这里留着给通知和测试用 —— 它比按钮文案详细。
     */
    public static String cardText(Context c, Calendar now) {
        init(c);
        String text = textOf(cached, now);
        // 禁忌卡惩罚期在身：不管学习联动什么状态，卡上都要写清楚冻到几点
        long until = tabooUntil(c);
        if (tabooHolding(now.getTimeInMillis(), until)) {
            Calendar u = Calendar.getInstance();
            u.setTimeInMillis(until);
            text += "\n禁忌卡 · 冻结延长至 "
                    + Rule.hm(u.get(Calendar.HOUR_OF_DAY), u.get(Calendar.MINUTE));
        }
        return text;
    }

    /** 卡片是否该用"警示色"（正在拦人，或者保护已经失效）。 */
    public static boolean activeNow(Context c, Calendar now) {
        return holding(c, now);
    }

    /**
     * 常驻通知用的一行短文案（不超过十几个字，通知栏放得下）。
     * 目标没完成时把通知本身变成提醒 —— 这是"只提醒不冻结"唯一能落地的地方：
     * Android 没有"图标变灰但还能用"的 API，图标灰 = 已被 suspend = 点不开。
     */
    public static String noticeLine(Context c, Calendar now) {
        init(c);
        Snapshot s = cached;
        int st = stateOf(s, now);
        if (st == ST_ACTIVE) {
            return "⚠ 今天目标没完成 · 相关应用冻结中";
        }
        if (st == ST_PENDING) {
            return "20:00 检查今日打卡";
        }
        if (st == ST_STALE) {
            return "⚠ 学习进度已过期，保护失效";
        }
        if (st == ST_DONE) {
            return "今天目标已完成 · 按时间表放行";
        }
        return "到点自动冻结 / 放行（CPU 常驻，略耗电）";
    }

    /** 是否需要把通知做成警示样式（目标没完成 / 保护失效）。 */
    public static boolean noticeWarn(Context c, Calendar now) {
        init(c);
        int st = stateOf(cached, now);
        return st == ST_ACTIVE || st == ST_PENDING || st == ST_STALE;
    }
}
