package com.afs.guard;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 学习台（本机版）。
 *
 * 以前"今天的学习目标"只能由电脑算：WorkBuddy 的智能页面挂在宿主 iframe 里
 * （顶层打开会被 inject.js 拦掉），数据层身份也在宿主那边，手机 App 拿不到写权限。
 * 所以老链路是 手机 → Gist → 电脑记账 → 学习台，没有电脑的人这条路根本走不通。
 *
 * 现在反过来：**这一份就是源数据**。目标、打卡记录、截止日期全在本机文件里，
 * 打卡直接落盘，闸门直接读它 —— 全程不需要网络，也不需要电脑。
 * 电脑那边（如果还有）只是把快照推一份过去看看，不再参与判定。
 *
 * 存储：filesDir/study.json，一份 JSON，读写都在 {@link #LOCK} 里，
 * 只有几千字节，不做数据库 —— 引入 SQLite 只会多一层坑。
 */
public final class StudyStore {

    private static final String TAG = Guard.TAG;
    private static final String FILE = "study.json";
    /** 记录保留天数：一年前的打卡留着也没用，还占地方。 */
    private static final int KEEP_DAYS = 400;

    private static final Object LOCK = new Object();

    private StudyStore() {
    }

    /* ==================== 数据模型 ==================== */

    /** 一个学习目标。 */
    public static final class G {
        public String id = "";
        public String name = "";
        public String unit = "个";
        /** 总量（篇 / 小时 / 个）。 */
        public double total;
        /** 迁移时带过来的历史完成量：done = base + 本机所有记录之和。 */
        public double base;
        /** 截止日 yyyy-MM-dd；空 = 不限期（建议量按 30 天摊）。 */
        public String due = "";
        /**
         * 导入那一刻 PC 侧的「今天已打卡」快照。
         * done 的总数里含今天已打的量，但本机还没有任何今天的记录 ——
         * 不带这个标记，导入当天手机会重复要求打卡（PC 说打完了、手机说没打）。
         * 只对导入当天有效，第二天起回归正常判定。
         */
        public boolean hitAtImport;
        public String importDate = "";
        public boolean archived;
    }

    /** 一笔打卡。 */
    public static final class Rec {
        public String id = "";
        public String goalId = "";
        public String date = "";
        public double amount;
        public long ts;
    }

    /* ==================== 读写 ==================== */

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    static synchronized JSONObject load(Context c) {
        synchronized (LOCK) {
            File f = file(c);
            if (!f.exists() || f.length() == 0) {
                return empty();
            }
            FileInputStream in = null;
            try {
                in = new FileInputStream(f);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                JSONObject o = new JSONObject(out.toString("UTF-8"));
                if (o.optJSONArray("goals") == null) {
                    o.put("goals", new JSONArray());
                }
                if (o.optJSONArray("recs") == null) {
                    o.put("recs", new JSONArray());
                }
                return o;
            } catch (Throwable t) {
                // 文件坏了不能连带把闸门弄崩：留个备份，从空库起步（fail-open）
                Log.w(TAG, "study.json 读失败，改用空库: " + t);
                try {
                    f.renameTo(new File(c.getFilesDir(), FILE + ".bad"));
                } catch (Throwable ignored) {
                }
                return empty();
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static JSONObject empty() {
        JSONObject o = new JSONObject();
        try {
            o.put("goals", new JSONArray());
            o.put("recs", new JSONArray());
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static void save(Context c, JSONObject o) {
        synchronized (LOCK) {
            FileOutputStream out = null;
            try {
                // 先写临时文件再改名：写到一半被杀掉不会留下半截 JSON
                File tmp = new File(c.getFilesDir(), FILE + ".tmp");
                out = new FileOutputStream(tmp);
                out.write(o.toString().getBytes("UTF-8"));
                out.flush();
                out.close();
                out = null;
                File dst = file(c);
                if (!tmp.renameTo(dst)) {
                    dst.delete();
                    tmp.renameTo(dst);
                }
            } catch (Throwable t) {
                Log.w(TAG, "study.json 写失败: " + t);
            } finally {
                try {
                    if (out != null) {
                        out.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // 台账权威在手机（Gist 中心仓库）：库一变就标脏，Hub 下一拍整份推上去
        Hub.markStudyDirty(c);
    }

    /* ==================== 目标 ==================== */

    /** 未归档的目标（界面和闸门用的就是这一份）。 */
    public static List<G> goals(Context c) {
        List<G> out = new ArrayList<>();
        JSONArray arr = load(c).optJSONArray("goals");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            G g = new G();
            g.id = o.optString("id", "");
            g.name = o.optString("name", "");
            g.unit = o.optString("unit", "个");
            g.total = o.optDouble("total", 0);
            g.base = o.optDouble("base", 0);
            g.due = o.optString("due", "");
            g.hitAtImport = o.optBoolean("hitAtImport", false);
            g.importDate = o.optString("importDate", "");
            g.archived = o.optBoolean("archived", false);
            if (!g.id.isEmpty() && !g.name.isEmpty() && !g.archived) {
                out.add(g);
            }
        }
        return out;
    }

    public static boolean hasGoals(Context c) {
        return !goals(c).isEmpty();
    }

    /**
     * 库里有没有「从 PC 导入」的目标。
     * PC 侧的 id 形如 goal-xxx，本机新建的是 g&lt;时间戳&gt; —— 靠前缀区分，
     * 比记"导入过没有"的布尔标记可靠：那个标志会被清库动作一起带走。
     */
    public static boolean hasImportedGoals(Context c) {
        for (G g : goals(c)) {
            if (g.id.startsWith("goal-")) {
                return true;
            }
        }
        return false;
    }

    public static G find(Context c, String id) {
        for (G g : goals(c)) {
            if (g.id.equals(id)) {
                return g;
            }
        }
        return null;
    }

    /** 新增或按 id 覆盖。 */
    public static void upsert(Context c, G g) {
        if (g.id == null || g.id.isEmpty()) {
            g.id = "g" + System.currentTimeMillis();
        }
        synchronized (LOCK) {
            JSONObject db = load(c);
            JSONArray arr = db.optJSONArray("goals");
            boolean replaced = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && g.id.equals(o.optString("id", ""))) {
                    replace(arr, i, goalJson(g));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                arr.put(goalJson(g));
            }
            save(c, db);
        }
        Log.i(TAG, "study goal saved: " + g.name + " total=" + g.total + " base=" + g.base);
        invalidate(c);
    }

    /** JSONArray.put(int, Object) 会抛受检异常，包一层让调用处干净。 */
    private static void replace(JSONArray arr, int i, JSONObject o) {
        try {
            arr.put(i, o);
        } catch (Throwable ignored) {
        }
    }

    private static JSONObject goalJson(G g) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", g.id);
            o.put("name", g.name);
            o.put("unit", g.unit);
            o.put("total", g.total);
        o.put("base", g.base);
        o.put("due", g.due == null ? "" : g.due);
        o.put("hitAtImport", g.hitAtImport);
        o.put("importDate", g.importDate == null ? "" : g.importDate);
        o.put("archived", g.archived);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 删除目标时把记录移入回收区，列表与统计立即不再看到它们。 */
    public static void remove(Context c, String id) {
        synchronized (LOCK) {
            JSONObject db = load(c);
            JSONArray goals = db.optJSONArray("goals");
            boolean found = false;
            for (int i = 0; i < goals.length(); i++) {
                JSONObject o = goals.optJSONObject(i);
                if (o != null && id.equals(o.optString("id", ""))) {
                    try {
                        o.put("archived", true);
                        found = true;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (!found) return;
            JSONArray recs = db.optJSONArray("recs");
            JSONArray keep = new JSONArray();
            JSONArray removed = new JSONArray();
            for (int i = 0; i < recs.length(); i++) {
                JSONObject r = recs.optJSONObject(i);
                if (r == null) continue;
                if (id.equals(r.optString("goalId", ""))) removed.put(r);
                else keep.put(r);
            }
            try {
                db.put("recs", keep);
                JSONArray trash = db.optJSONArray("trash");
                if (trash == null) trash = new JSONArray();
                JSONObject entry = new JSONObject();
                entry.put("id", id);
                entry.put("recs", removed);
                trash.put(entry);
                db.put("trash", trash);
            } catch (Throwable ignored) {
            }
            save(c, db);
        }
        invalidate(c);
    }

    /** 可撤回的目标，最近删除的排在前面。 */
    public static List<G> deletedGoals(Context c) {
        List<G> out = new ArrayList<>();
        JSONObject db = load(c);
        JSONArray trash = db.optJSONArray("trash");
        JSONArray goals = db.optJSONArray("goals");
        for (int i = trash == null ? -1 : trash.length() - 1; i >= 0; i--) {
            JSONObject entry = trash.optJSONObject(i);
            String id = entry == null ? "" : entry.optString("id", "");
            for (int j = 0; goals != null && j < goals.length(); j++) {
                JSONObject o = goals.optJSONObject(j);
                if (o == null || !id.equals(o.optString("id", ""))) continue;
                G g = new G();
                g.id = id;
                g.name = o.optString("name", "");
                if (!g.name.isEmpty()) out.add(g);
                break;
            }
        }
        return out;
    }

    /** 撤回删除：原目标与每一笔打卡一起恢复。 */
    public static boolean restore(Context c, String id) {
        boolean restored = false;
        synchronized (LOCK) {
            JSONObject db = load(c);
            JSONArray trash = db.optJSONArray("trash");
            JSONArray goals = db.optJSONArray("goals");
            JSONArray keep = new JSONArray();
            JSONArray recs = db.optJSONArray("recs");
            if (trash == null || goals == null || recs == null) return false;
            for (int i = 0; i < trash.length(); i++) {
                JSONObject entry = trash.optJSONObject(i);
                if (entry == null || !id.equals(entry.optString("id", ""))) {
                    if (entry != null) keep.put(entry);
                    continue;
                }
                JSONArray saved = entry.optJSONArray("recs");
                for (int j = 0; saved != null && j < saved.length(); j++) {
                    JSONObject rec = saved.optJSONObject(j);
                    if (rec != null) recs.put(rec);
                }
                restored = true;
            }
            if (!restored) return false;
            for (int i = 0; i < goals.length(); i++) {
                JSONObject goal = goals.optJSONObject(i);
                if (goal != null && id.equals(goal.optString("id", ""))) {
                    try { goal.put("archived", false); } catch (Throwable ignored) { }
                    break;
                }
            }
            try { db.put("trash", keep); } catch (Throwable ignored) { }
            save(c, db);
        }
        invalidate(c);
        return true;
    }


    /* ==================== 打卡记录 ==================== */

    public static List<Rec> recs(Context c) {
        List<Rec> out = new ArrayList<>();
        JSONArray arr = load(c).optJSONArray("recs");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Rec r = new Rec();
            r.id = o.optString("id", "");
            r.goalId = o.optString("goalId", "");
            r.date = o.optString("date", "");
            r.amount = o.optDouble("amount", 0);
            r.ts = o.optLong("ts", 0);
            if (!r.goalId.isEmpty() && r.amount > 0) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * 记一笔。返回落盘的那条（id 由本机生成，PC 端拿它去重）。
     *
     * @param date 允许补记最近几天，但传什么都不改手机时钟 —— 用调用方给的日期
     */
    public static Rec check(Context c, String goalId, double amount, String date) {
        if (goalId.startsWith(MemoryData.GOAL_PREFIX)) {
            throw new IllegalArgumentException("记忆复习独立保存，不写入打卡");
        }
        Rec r = new Rec();
        r.id = "r" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1000);
        r.goalId = goalId;
        r.date = (date == null || date.isEmpty()) ? today() : date;
        r.amount = amount;
        r.ts = System.currentTimeMillis();

        synchronized (LOCK) {
            JSONObject db = load(c);
            JSONArray arr = db.optJSONArray("recs");
            JSONObject o = new JSONObject();
            try {
                o.put("id", r.id);
                o.put("goalId", r.goalId);
                o.put("date", r.date);
                o.put("amount", r.amount);
                o.put("ts", r.ts);
                arr.put(o);
                db.put("recs", arr);
            } catch (Throwable ignored) {
            }
            save(c, db);
        }
        invalidate(c);
        return r;
    }

    static boolean canUndoDate(String recordDate, String currentDate) {
        return recordDate != null && !recordDate.isEmpty() && recordDate.equals(currentDate);
    }

    /** 只允许撤回当天记录，确认框跨过午夜也必须拒绝。 */
    public static boolean deleteRec(Context c, String recId) {
        synchronized (LOCK) {
            JSONObject db = load(c);
            JSONArray arr = db.optJSONArray("recs");
            JSONArray keep = new JSONArray();
            boolean removed = false;
            String currentDate = today();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (recId.equals(o.optString("id", ""))) {
                    if (!canUndoDate(o.optString("date", ""), currentDate)) return false;
                    removed = true;
                } else keep.put(o);
            }
            if (!removed) return false;
            try {
                db.put("recs", keep);
            } catch (Throwable ignored) {
            }
            save(c, db);
        }
        invalidate(c);
        return true;
    }

    /** 某个目标到今天为止一共做了多少（历史基数 + 本机所有记录）。 */
    public static double doneOf(Context c, G g) {
        double sum = g.base;
        for (Rec r : recs(c)) {
            if (r.goalId.equals(g.id)) {
                sum += r.amount;
            }
        }
        return sum;
    }

    /** 某个目标在某一天做了多少。 */
    public static double doneOn(List<Rec> all, String goalId, String date) {
        double sum = 0;
        for (Rec r : all) {
            if (r.goalId.equals(goalId) && r.date.equals(date)) {
                sum += r.amount;
            }
        }
        return sum;
    }

    /** 某目标某天是否算「已打过」：本机有那天的记录，或（导入当天且导入时 PC 说已打）。 */
    private static boolean hitToday(List<Rec> all, G g, String d) {
        if (doneOn(all, g.id, d) > 0) {
            return true;
        }
        return g.hitAtImport && d.equals(g.importDate);
    }

    /* ==================== 派生值 ==================== */

    /**
     * 距截止日还有几天（含今天）。
     * 没有截止日 → -1（调用方按 30 天摊）。
     */
    public static int daysLeft(String due) {
        if (due == null || due.length() < 10) {
            return -1;
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setLenient(false);
            long dueMs = f.parse(due.trim()).getTime();
            long todayMs = f.parse(today()).getTime();
            long days = Math.round((dueMs - todayMs) / 86400000.0);
            return days < 0 ? 0 : (int) days;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 今天记多少合适：剩余量摊到「剩余天数 + 1」（+1 是给今天本身留一格），
     * 不到 1 就取 1 —— 和学习台页面里的算法一模一样，别让两边报出不同的数。
     */
    public static double suggest(G g, double done) {
        double remain = g.total - done;
        if (remain <= 0) {
            return 0;
        }
        int left = daysLeft(g.due);
        if (left < 0) {
            left = 29;
        }
        double per = remain / (left + 1.0);
        return Math.max(1, Math.ceil(per));
    }

    /**
     * 今天还剩几个目标没打。
     * 已完成（done >= total）的算打过 —— 不然"总量做完但今天没记"会一直催。
     */
    public static int pendingToday(Context c) {
        List<Rec> all = recs(c);
        String d = today();
        int n = 0;
        for (G g : goals(c)) {
            double done = g.base + sumOf(all, g.id);
            if (done >= g.total) {
                continue;   // 总量做完的不数
            }
            if (!hitToday(all, g, d)) {
                n++;
            }
        }
        return n;
    }

    private static double sumOf(List<Rec> all, String goalId) {
        double s = 0;
        for (Rec r : all) {
            if (r.goalId.equals(goalId)) {
                s += r.amount;
            }
        }
        return s;
    }

    /* ==================== 给打卡页用的一屏数据 ==================== */

    /**
     * 转成打卡页那套 {@link Goal}。
     *
     * 为什么不直接让打卡页读 {@link G}：卡片、排序、建议量、已打卡标记已经按那个结构
     * 写好了，多一层转换比把 UI 全部重写便宜，也不会两边算法漂移。
     */
    public static List<Goal> uiGoals(Context c) {
        List<Rec> all = recs(c);
        String d = today();
        List<Goal> out = new ArrayList<>();
        for (G g : goals(c)) {
            Goal u = new Goal();
            u.id = g.id;
            u.name = g.name;
            u.unit = g.unit;
            u.total = g.total;
            u.done = g.base + sumOf(all, g.id);
            u.todayHit = hitToday(all, g, d);
            u.localHit = u.todayHit;
            u.suggest = suggest(g, u.done);
            out.add(u);
        }
        // 没打的排前面，其次按建议量从少到多 —— 先打最容易补上的那个
        java.util.Collections.sort(out, new java.util.Comparator<Goal>() {
            public int compare(Goal a, Goal b) {
                if (a.hit() != b.hit()) {
                    return a.hit() ? 1 : -1;
                }
                return Double.compare(a.suggest, b.suggest);
            }
        });
        return out;
    }

    /* ==================== 闸门快照 ==================== */

    /**
     * 生成和 Gist 上 study-gate.json 同构的快照。
     *
     * 闸门（{@link StudyGate}）本来只认这份 JSON；本机有了数据以后，
     * 直接喂它同一份格式，判定逻辑一行都不用改，也没有"另一套规则"的隐患。
     */
    public static String snapshotJson(Context c) {
        int pending = pendingToday(c);
        String d = today();
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"enabled\":true");
        sb.append(",\"start\":\"").append(spanStart(c)).append('"');
        sb.append(",\"end\":\"").append(spanEnd(c)).append('"');
        sb.append(",\"allDone\":").append(pending == 0);
        sb.append(",\"date\":\"").append(d).append('"');
        sb.append(",\"checkedAt\":\"").append(stamp()).append('"');
        sb.append(",\"source\":\"phone\",\"pending\":").append(pending);
        sb.append('}');
        return sb.toString();
    }

    /* ==================== 学习联动的时段 ==================== */

    private static final String SP = "afs_study_span";

    public static String spanStart(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getString("start", "22:00");
    }

    public static String spanEnd(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getString("end", "23:00");
    }

    /** 学习联动时段：目标没打完时，这个区间里延长冻结。 */
    public static void setSpan(Context c, String start, String end) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString("start", start).putString("end", end).apply();
        invalidate(c);
    }

    /* ==================== 迁移：把老的 Gist 数据吃进来 ==================== */

    /**
     * 从 study-gate.json 导一次目标（只导 goal 的名字/单位/总量/已完成）。
     *
     * 只导一次，导完打标记。之后本机就是源数据 —— 再导会把用户在手机上
     * 新建、删掉的目标又变回来，那就成了"同步打架"。
     */
    public static boolean importFromGateJson(Context c, String json) {
        if (json == null || json.indexOf("\"goals\"") < 0) {
            return false;
        }
        if (c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("imported", false)) {
            return false;
        }
        try {
            JSONObject o = new JSONObject(json);
            JSONArray arr = o.optJSONArray("goals");
            if (arr == null || arr.length() == 0) {
                return false;
            }
            int n = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null) {
                    continue;
                }
                G g = new G();
                g.id = x.optString("id", "");
                g.name = x.optString("name", "");
                g.unit = x.optString("unit", "个");
                g.total = x.optDouble("total", 0);
                g.base = x.optDouble("done", 0);
                if (g.name.isEmpty() || g.total <= 0) {
                    continue;
                }
                if (g.id.isEmpty()) {
                    g.id = "g" + System.currentTimeMillis() + "_" + i;
                }
                g.due = plusDays(30);
                g.hitAtImport = x.optBoolean("todayHit", false);
                g.importDate = today();
                upsertQuiet(c, g);
                n++;
            }
            if (n > 0) {
                c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                        .putBoolean("imported", true).apply();
                Log.i(TAG, "study import: " + n + " goals");
                invalidate(c);
            }
            return n > 0;
        } catch (Throwable t) {
            Log.w(TAG, "study import failed: " + t);
            return false;
        }
    }

    /** 同 {@link #upsert}，但不触发闸门重算（批量导入时用）。 */
    private static void upsertQuiet(Context c, G g) {
        synchronized (LOCK) {
            JSONObject db = load(c);
            JSONArray arr = db.optJSONArray("goals");
            boolean replaced = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && g.id.equals(o.optString("id", ""))) {
                    replace(arr, i, goalJson(g));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                arr.put(goalJson(g));
            }
            save(c, db);
        }
    }

    /**
     * 清掉由旧版导入产生的库（v1 没有 hitAtImport 标记，导入当天会重复要求打卡）。
     * 只动带 imported 标记的库 —— 用户手动建的目标不在里面，不受影响。
     */
    public static void wipeForReimport(Context c) {
        try {
            new File(c.getFilesDir(), FILE).delete();
        } catch (Throwable ignored) {
        }
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .remove("imported").apply();
        Log.i(TAG, "study store wiped for reimport (v1 -> v2)");
    }

    /* ==================== 备份 / 恢复 ==================== */

    /**
     * 整库导出：study.json 的原文。目标、记录、联动时段全在里面，
     * 换手机时把它导出去，在新手机上导入就完成迁移。
     */
    public static String exportJson(Context c) {
        return load(c).toString();
    }

    /** Apply hub changes while retaining edits made during the network request. */
    static JSONObject mergeHub(Context c, JSONObject remote, JSONObject base, boolean dirty) throws Exception {
        synchronized (LOCK) {
            JSONObject local = load(c);
            JSONObject merged = LedgerMerge.merge(local, remote, base, dirty);
            if (!LedgerMerge.sameData(local, merged)) {
                File f = file(c);
                if (f.exists()) copy(f, new File(c.getFilesDir(), FILE + ".before-sync.bak"));
                save(c, merged);
                invalidate(c);
            }
            return merged;
        }
    }

    /**
     * 整库恢复：用备份内容整体替换现有数据。
     * 结构不对（解析失败 / 缺 goals 或 recs 数组）一律拒绝，现有数据一个字节不动；
     * 通过则先把当前库备份成 study.json.bak 再写入 —— 恢复错了还能退回来。
     */
    public static boolean importJson(Context c, String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Throwable t) {
            Log.w(TAG, "study.json 恢复：不是合法 JSON");
            return false;
        }
        if (o.optJSONArray("goals") == null || o.optJSONArray("recs") == null) {
            Log.w(TAG, "study.json 恢复：结构不对，拒绝");
            return false;
        }
        synchronized (LOCK) {
            try {
                File f = file(c);
                if (f.exists() && f.length() > 0) {
                    copy(f, new File(c.getFilesDir(), FILE + ".bak"));
                }
                save(c, o);
                invalidate(c);
                Log.i(TAG, "study.json restored: goals=" + o.optJSONArray("goals").length()
                        + " recs=" + o.optJSONArray("recs").length());
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "study.json 恢复失败: " + t);
                return false;
            }
        }
    }

    private static void copy(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /* ==================== 杂项 ==================== */

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
    }

    public static String plusDays(int days) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date(System.currentTimeMillis() + days * 86400000L));
    }

    /** 数据变了：让闸门下一拍就用新数据重判（不再等 10 分钟节流）。 */
    private static void invalidate(Context c) {
        try {
            StudyGate.invalidate(c);
        } catch (Throwable ignored) {
        }
    }

    /** 每月清一次一年前的记录，别让文件无限长。 */
    public static void prune(Context c) {
        String cutoff = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date(System.currentTimeMillis() - KEEP_DAYS * 86400000L));
        synchronized (LOCK) {
            JSONObject db = load(c);
            JSONArray arr = db.optJSONArray("recs");
            JSONArray keep = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String d = o.optString("date", "");
                if (d.compareTo(cutoff) >= 0) {
                    keep.put(o);
                }
            }
            try {
                db.put("recs", keep);
            } catch (Throwable ignored) {
            }
            save(c, db);
        }
    }
}
