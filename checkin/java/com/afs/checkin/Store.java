package com.afs.checkin;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地状态：token、待同步队列、上次拉到的目标列表。
 *
 * 队列的语义（这块想清楚之前别乱改）：
 *   pushed  = 已经写进 Gist（不代表 PC 已经记进学习台）
 *   applied = PC 已经记账了（从 Gist 队列里消失即为已记账）
 *
 * 推送时把所有 !applied 的条目一起推上去，PC 端用 localId 去重，
 * 所以重复推不会重复记账 —— 手机这边就不必猜"到底推没推成功"。
 */
final class Store {

    private static final String FILE = "checkin";
    private static final String K_TOKEN = "token";
    private static final String K_QUEUE = "queue";
    private static final String K_GOALS = "goals";
    private static final String K_DATE = "goalsDate";
    private static final String K_AT = "goalsAt";

    private Store() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /* ---------- token ---------- */

    static String token(Context c) {
        String t = sp(c).getString(K_TOKEN, "");
        if (t == null || t.trim().isEmpty()) {
            return BuildConfig.PREFILL_TOKEN == null ? "" : BuildConfig.PREFILL_TOKEN.trim();
        }
        return t.trim();
    }

    static void setToken(Context c, String t) {
        sp(c).edit().putString(K_TOKEN, t == null ? "" : t.trim()).apply();
    }

    static boolean hasToken(Context c) {
        return !token(c).isEmpty();
    }

    /* ---------- 目标 ---------- */

    static final class Goal {
        String id = "";
        String name = "";
        String unit = "个";
        double total;
        double done;
        double suggest;
        boolean todayHit;
        /** 本地刚打过卡（还没被 PC 记账），用来立刻显示"已打"。 */
        boolean localHit;
    }

    static String goalsJson(Context c) {
        return sp(c).getString(K_GOALS, "");
    }

    static String goalsDate(Context c) {
        return sp(c).getString(K_DATE, "");
    }

    static String goalsAt(Context c) {
        return sp(c).getString(K_AT, "");
    }

    static void saveGoals(Context c, String json, String date, String at) {
        sp(c).edit().putString(K_GOALS, json == null ? "" : json)
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

    /* ---------- 待同步队列 ---------- */

    static final class Item {
        String id = "";
        String goalId = "";
        String name = "";
        String unit = "";
        String date = "";
        double amount;
        long ts;
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

    /** 把本地已经不在远端队列里的条目标成 applied（= PC 已经记账）。 */
    static void markApplied(Context c, List<Item> remote) {
        java.util.Set<String> alive = new java.util.HashSet<>();
        for (Item it : remote) {
            alive.add(it.id);
        }
        List<Item> q = queue(c);
        boolean changed = false;
        for (Item it : q) {
            if (!it.applied && it.id != null && !alive.contains(it.id)) {
                it.applied = true;
                changed = true;
            }
        }
        if (changed) {
            saveQueue(c, q);
        }
    }
}
