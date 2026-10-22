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
    /**
     * 手机打卡队列。手机写、PC 记账后清空 —— 两边谁也不覆盖谁的文件。
     */
    public static final String QUEUE_FILE = "checkin-queue.json";

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
        return shouldHold(snap, now, effectivePending());
    }

    /**
     * @param queuedPending 合并手机打卡后还剩几个没打（-1 = 队列里没有今天的记录）
     *
     * ★ 这一层是「先解闸」的落点：手机把最后几个目标打完，队列里就带着
     *   pendingAfter=0，守卫这边立刻不再 hold，不用干等 PC 那 15 分钟的同步。
     *   等 PC 记完账把队列清空，判定自然回到 allDone 那条线上 —— 是收敛的。
     */
    public static boolean shouldHold(Snapshot snap, Calendar now, int queuedPending) {
        if (queuedPending == 0) {
            return false;
        }
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
    /** 学习台导入格式的版本；v3 = 清库后重新拉一份最新 gate 数据再导。 */
    private static final String K_IMPORT_VER = "study_import_ver";
    private static final String K_REIMPORT_AT = "reimport_at";

    private static volatile Snapshot cached;
    /**
     * 把手机队列里的打卡也算进去之后，今天还剩几个目标没打卡。
     *
     *   -1 = 队列里没有今天的记录（手机没打过卡，按 PC 的数据判）
     *    0 = 全部打完了（手机补齐了最后几个）→ 放行
     *   >0 = 还剩这么多个     → 继续判
     *
     * ★ 不能"队列非空就放行"：那等于打一个目标就全解锁，
     *   22:00 打 0.5 篇就能把剩下两个目标一起豁免 —— 闸门就废了。
     */
    private static volatile int queuePending = -1;

    /**
     * 打卡页在本机刚算出来的同一个值。
     *
     * 有了它就不用等 Gist 往返 + 10 分钟拉取节流：打卡页和闸门现在在同一个进程里
     * （合并成一个 App 之后才成立），打完最后一个目标那一刻就能立刻解闸。
     * 队列推成功后远端也是同一个数，两边收敛；推失败时本地这份还留着，等重试。
     */
    private static volatile int localPending = -1;

    /** 打卡页打完一笔后直接告诉闸门还剩几个；-1 表示"退回按远端判"。 */
    public static void noteLocalPending(int pending) {
        localPending = pending;
        // 这一行是"先解闸"的唯一现场证据：出问题时先看它到底传了什么
        Log.i(TAG, "study-gate localPending=" + pending
                + " (remote=" + queuePending + " effective=" + effectivePending() + ")");
    }

    /**
     * 远端和本地两份取"更小的那个"（-1 当没有）。
     *
     * 取小 = 更早就解闸。宁可早放行也不锁死：这是整个项目一贯的 fail-open。
     * 反过来取大就成了"本地说打完了、远端还没收到 → 继续冻"，
     * 用户会以为打卡没生效，然后反复打 —— 体验上直接崩。
     */
    static int mergePending(int remote, int local) {
        if (remote < 0) {
            return local;
        }
        if (local < 0) {
            return remote;
        }
        return Math.min(remote, local);
    }

    static int effectivePending() {
        return mergePending(queuePending, localPending);
    }
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

    static String queueUrl() {
        // 带上时间戳绕开 CDN：gist raw 会缓存几十秒，刚打完卡读到的还是旧的空队列
        return "https://gist.githubusercontent.com/sdgfdii/" + GIST_ID
                + "/raw/" + QUEUE_FILE + "?t=" + System.currentTimeMillis();
    }

    /**
     * 从队列文件里读「合上手机这些打卡之后，还剩几个目标没打」。
     *
     * 返回 -1 表示队列里没有今天的东西（或字段缺失 / 读不懂），调用方按 PC 的数据判。
     * 0 表示手机把目标补齐了 —— 这才是「立刻解闸」的唯一凭据。
     *
     * 纯函数、不引 org.json（android.jar 里它是 stub，离线回归跑不了），
     * 所以用"压掉空白 + 找字段"的笨办法。格式由打卡 App 和 PC 两边固定，够用。
     */
    static int parsePendingAfter(String json, String today) {
        if (json == null || today == null || json.length() == 0) {
            return -1;
        }
        String flat = json.replaceAll("\\s+", "");
        if (flat.indexOf("\"date\":\"" + today + "\"") < 0) {
            return -1;
        }
        int i = flat.indexOf("\"pendingAfter\":");
        if (i < 0) {
            return -1;
        }
        i += "\"pendingAfter\":".length();
        int j = i;
        while (j < flat.length()) {
            char ch = flat.charAt(j);
            if ((ch >= '0' && ch <= '9') || ch == '-') {
                j++;
            } else {
                break;
            }
        }
        try {
            int v = Integer.parseInt(flat.substring(i, j));
            return v < 0 ? -1 : v;
        } catch (Throwable t) {
            return -1;
        }
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
            boolean has = StudyStore.hasGoals(c);
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
            localMode = StudyStore.hasGoals(c);
            localModeAt = now;
        }
        return localMode;
    }

    /** 给外面用的只读版本：本机是不是学习台（决定首页按钮文案等）。 */
    public static boolean isLocalMode(Context c) {
        return localMode(c);
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
            // 本机是源数据，队列那套"先解闸"的中间态就没意义了 —— 清掉，
            // 否则旧的 queuePending=0 会让卡片一直显示"等电脑记账"。
            queuePending = -1;
            localPending = -1;
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
                        // 顺带拉一次打卡队列。读失败就保持上一次的值——
                        // 网络抖一下就把"已打卡"抹成"未打卡"，比不更新难受得多。
                        try {
                            int q = parsePendingAfter(fetchRaw(queueUrl()), today);
                            if (q != queuePending) {
                                queuePending = q;
                                changed = true;
                            }
                            Log.i(TAG, "study-gate queue: pendingAfter=" + q);
                        } catch (Throwable tq) {
                            Log.w(TAG, "study-gate queue failed: " + tq);
                        }
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
    public static final int ST_QUEUED  = 6;   // 手机上已打卡，等 PC 记账

    /** 纯判定（离线可测）：快照 → 卡片状态。 */
    public static int stateOf(Snapshot s, Calendar now) {
        return stateOf(s, now, effectivePending());
    }

    public static int stateOf(Snapshot s, Calendar now, int queuedPending) {
        // 手机把最后一个目标也打完了：先显示"已打卡"，别让人以为没生效。
        // 注意只有 ==0 才算 —— 还有剩的目标就仍是"没打完"，该冻还是冻。
        if (queuedPending == 0) {
            return ST_QUEUED;
        }
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
        return textOf(s, now, effectivePending());
    }

    public static String textOf(Snapshot s, Calendar now, int queuedPending) {
        int st = stateOf(s, now, queuedPending);
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
            case ST_QUEUED:
                return "学习联动 · 已打卡\n手机上已打卡，等电脑记账后同步到学习台";
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
        if (st == ST_QUEUED) {
            return "学习联动　手机上已打卡（等电脑记账）";
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
            return "⚠ 今天目标没完成 · 正在延长冻结";
        }
        if (st == ST_PENDING) {
            return "今天目标没完成 · " + spanOf(s) + " 起继续冻结";
        }
        if (st == ST_QUEUED) {
            return "今天已打卡 · 等电脑记账";
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
