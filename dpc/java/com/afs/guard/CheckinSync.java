package com.afs.guard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标列表的拉取（首页和打卡页共用）。
 *
 * 抽出来的原因：两个页面都要"今天还剩几个目标"。首页只需要那个数字，
 * 打卡页要整份数据；如果各写一份解析，早晚会有一边漏字段。
 *
 * 落盘之前统一走 {@link CheckinStore#mergeFresh} —— 谁先拉到都得守住
 * "刚打完还没记账"的那几条，否则首页一次顺手刷新就能把打卡页的绿勾抹掉。
 */
final class CheckinSync {

    interface Sink {
        /** 失败时 goals 为 null，err 是原因（调用方决定显不显示）。 */
        void onData(List<CheckinStore.Goal> goals, String date, String at, Exception err);
    }

    private CheckinSync() {
    }

    static void fetch(final Context c, final Sink sink) {
        new Thread(new Runnable() {
            public void run() {
                try (SyncConfig.Session session = SyncConfig.acquire(c)) {
                    String raw = StudyGate.fetchRaw(
                            session.target.rawUrl(StudyGate.GIST_FILE) + "?t=" + System.currentTimeMillis());
                    JSONObject o = new JSONObject(raw);
                    String date = o.optString("date", "");
                    String at = o.optString("checkedAt", "");
                    List<CheckinStore.Goal> fresh = new ArrayList<>();
                    JSONArray arr = o.optJSONArray("goals");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject x = arr.optJSONObject(i);
                            if (x == null) {
                                continue;
                            }
                            CheckinStore.Goal g = new CheckinStore.Goal();
                            g.id = x.optString("id", "");
                            g.name = x.optString("name", "");
                            g.unit = x.optString("unit", "个");
                            g.total = x.optDouble("total", 0);
                            g.done = x.optDouble("done", 0);
                            g.suggest = x.optDouble("suggest", 0);
                            g.todayHit = x.optBoolean("todayHit", false);
                            if (!g.id.isEmpty() && !g.name.isEmpty()) {
                                fresh.add(g);
                            }
                        }
                    }
                    final List<CheckinStore.Goal> merged = CheckinStore.mergeFresh(c, fresh);
                    CheckinStore.saveGoals(c, merged, date, at);
                    final String fd = date;
                    final String fa = at;
                    post(new Runnable() {
                        public void run() {
                            if (session.isCurrent(c)) sink.onData(merged, fd, fa, null);
                        }
                    });
                } catch (final Exception e) {
                    post(new Runnable() {
                        public void run() {
                            sink.onData(null, null, null, e);
                        }
                    });
                }
            }
        }, "afs-goals").start();
    }

    /** 统一的 UI 线程回调入口（两处调用方都不要自己写 Handler）。 */
    static void post(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
