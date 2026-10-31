package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 打卡页的本地状态：token、待同步队列、上次拉到的目标列表。
 *
 * 原来是独立 App（com.afs.checkin）里的 Store，并进守卫之后名字加个前缀，
 * 免得和 {@link RuleStore} 混起来（那个存的是黑名单规则，两码事）。
 *
 * 队列的语义（这块想清楚之前别乱改）：
 *   pushed  = 已经写进 Gist（不代表 PC 已经记进学习台）
 *   applied = PC 已经记账了（从 Gist 队列里消失即为已记账）
 *
 * 推送时把所有 !applied 的条目一起推上去，PC 端用 localId 去重，
 * 所以重复推不会重复记账 —— 手机这边就不必猜"到底推没推成功"。
 */
final class CheckinStore {

    private static final String FILE = "afs_checkin";
    private static final String K_QUEUE = "queue";
    private static final String K_GOALS = "goals";
    private static final String K_DATE = "goalsDate";
    private static final String K_AT = "goalsAt";

    private CheckinStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static SharedPreferences remoteSp(Context c) {
        return c.getSharedPreferences(SyncConfig.cacheName(c, "afs_checkin_remote"), Context.MODE_PRIVATE);
    }

    /* ---------- 目标 ---------- */

    static final class Goal {
        String id = "";
        String name = "";
        String unit = T.s("个");
        double total;
        double done;
        double suggest;
        boolean todayHit;
        /** 本地刚打过卡（还没被 PC 记账），用来立刻显示"已打"。 */
        boolean localHit;

        boolean hit() {
            return todayHit || localHit;
        }
    }

    static String goalsJson(Context c) {
        return remoteSp(c).getString(K_GOALS, "");
    }

    static String goalsDate(Context c) {
        return remoteSp(c).getString(K_DATE, "");
    }

    static String goalsAt(Context c) {
        return remoteSp(c).getString(K_AT, "");
    }

    static void saveGoals(Context c, List<Goal> list, String date, String at) {
        JSONArray arr = new JSONArray();
        for (Goal g : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", g.id);
                o.put("name", g.name);
                o.put("unit", g.unit);
                o.put("total", g.total);
                o.put("done", g.done);
                o.put("suggest", g.suggest);
                o.put("todayHit", g.hit());
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        remoteSp(c).edit().putString(K_GOALS, arr.toString())
                .putString(K_DATE, date == null ? "" : date)
                .putString(K_AT, at == null ? "" : at).apply();
    }

    static List<Goal> goals(Context c) {
        List<Goal> out = new ArrayList<>();
        String raw = goalsJson(c);
        if (raw.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Goal g = new Goal();
                g.id = o.optString("id", "");
                g.name = o.optString("name", "");
                g.unit = o.optString("unit", "个");
                g.total = o.optDouble("total", 0);
                g.done = o.optDouble("done", 0);
                g.suggest = o.optDouble("suggest", 0);
                g.todayHit = o.optBoolean("todayHit", false);
                if (!g.id.isEmpty() && !g.name.isEmpty()) {
                    out.add(g);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 今天还剩几个目标没打。首页按钮上的"还有 N 个"用它。
     * 数据不是今天的就返回 -1（宁可不显示，也不显示昨天的数字）。
     */
    static int pendingToday(Context c) {
        if (!today().equals(goalsDate(c))) {
            return -1;
        }
        int n = 0;
        for (Goal g : goals(c)) {
            if (!g.hit()) {
                n++;
            }
        }
        return n;
    }

    /* ---------- 待同步队列 ---------- */

    static final class Item {
        String id = "";
        String goalId = "";
        String name = "";
        String unit = "";
        String date = "";
        double amount;
        long ts;
        /** 已经成功写进 Gist 过一次（推失败的都是 false）。 */
        boolean pushed;
        boolean applied;
    }

    static List<Item> queue(Context c) {
        List<Item> out = new ArrayList<>();
        String raw = sp(c).getString(K_QUEUE, "");
        if (raw.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Item it = new Item();
                it.id = o.optString("id", "");
                it.goalId = o.optString("goalId", "");
                it.name = o.optString("name", "");
                it.unit = o.optString("unit", "");
                it.date = o.optString("date", "");
                it.amount = o.optDouble("amount", 0);
                it.ts = o.optLong("ts", 0);
                it.pushed = o.optBoolean("pushed", false);
                it.applied = o.optBoolean("applied", false);
                if (!it.id.isEmpty()) {
                    out.add(it);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static void saveQueue(Context c, List<Item> items) {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("goalId", it.goalId);
                o.put("name", it.name);
                o.put("unit", it.unit);
                o.put("date", it.date);
                o.put("amount", it.amount);
                o.put("ts", it.ts);
                o.put("pushed", it.pushed);
                o.put("applied", it.applied);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        sp(c).edit().putString(K_QUEUE, arr.toString()).apply();
    }

    /** 加一条打卡，返回它。 */
    static Item add(Context c, Goal g, double amount, String date) {
        List<Item> q = queue(c);
        Item it = new Item();
        it.id = "ck_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1000);
        it.goalId = g.id;
        it.name = g.name;
        it.unit = g.unit;
        it.date = date;
        it.amount = amount;
        it.ts = System.currentTimeMillis();
        q.add(it);
        // 只留最近 7 天，防止无限增长
        long cut = it.ts - 7L * 24 * 3600 * 1000;
        List<Item> keep = new ArrayList<>();
        for (Item x : q) {
            if (x.ts >= cut) {
                keep.add(x);
            }
        }
        saveQueue(c, keep);
        return it;
    }

    static List<Item> pending(Context c) {
        List<Item> out = new ArrayList<>();
        for (Item it : queue(c)) {
            if (!it.applied) {
                out.add(it);
            }
        }
        return out;
    }

    /**
     * 标记这几条"确实写进 Gist 了"。
     *
     * 为什么非要这个标记：判断「PC 已经记账」用的是"本地有、远端没有"。
     * 刚打完还没推上去的那一瞬间，它也是"远端没有" —— 不打这个标记，
     * 一次刚好撞上的对齐就会把还没记账的记录当成已记账，直接丢掉。
     */
    static void markPushed(Context c, List<Item> sent) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Item it : sent) {
            if (it.id != null) {
                ids.add(it.id);
            }
        }
        List<Item> q = queue(c);
        boolean changed = false;
        for (Item it : q) {
            if (!it.pushed && ids.contains(it.id)) {
                it.pushed = true;
                changed = true;
            }
        }
        if (changed) {
            saveQueue(c, q);
        }
    }

    /**
     * 把本地已经不在远端队列里的条目标成 applied（= PC 已经记账）。
     *
     * ★ 只认 pushed 过的：没推成功过的记录远端当然没有，
     *   把它当成"已记账"等于静默丢数据。
     *
     * @return 有变化才返回 true —— 调用方靠它判断"要不要重新以远端为准"，
     *         不然"刷新 → 对齐 → 又触发刷新"会转起来。
     */
    static boolean markApplied(Context c, List<Item> remote) {
        java.util.Set<String> alive = new java.util.HashSet<>();
        for (Item it : remote) {
            alive.add(it.id);
        }
        List<Item> q = queue(c);
        boolean changed = false;
        for (Item it : q) {
            if (it.pushed && !it.applied && it.id != null && !alive.contains(it.id)) {
                it.applied = true;
                changed = true;
            }
        }
        if (changed) {
            saveQueue(c, q);
        }
        return changed;
    }

    /** 还在排队等 PC 记账的目标 id（用来决定"本地已打卡"标记还能不能立着）。 */
    static java.util.Set<String> unappliedGoalIds(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Item it : pending(c)) {
            if (it.goalId != null && !it.goalId.isEmpty()) {
                out.add(it.goalId);
            }
        }
        return out;
    }

    /**
     * 远端数据覆盖本地，但守住"已经打上了、PC 还没记账"的那几条。
     *
     * ★ 依据是**队列**，不是"上一轮界面显示过绿勾"。
     *   队列里还有这条 → 手机确实打过、账还没记，远端说"没打"是它落后了，不能抹；
     *   队列空了 → 账已经对齐（或被清掉），一切以远端为准。
     *   否则"本地已打卡"会一直挂着，哪怕账根本没记上 —— 自己骗自己。
     */
    static List<Goal> mergeFresh(Context c, List<Goal> fresh) {
        java.util.Set<String> waiting = unappliedGoalIds(c);
        if (waiting.isEmpty()) {
            return fresh;
        }
        for (Goal old : goals(c)) {
            if (!old.hit() || !waiting.contains(old.id)) {
                continue;
            }
            for (Goal f : fresh) {
                if (f.id.equals(old.id)) {
                    f.localHit = true;
                    f.todayHit = true;
                }
            }
        }
        return fresh;
    }

    /* ---------- 杂项 ---------- */

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
