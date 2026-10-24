package com.afs.guard;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统计页：把"防得怎么样"用数字摆出来 —— 本月应用排行、游戏时长、近 7 天娱乐占比趋势。
 *
 * 数据全部来自系统 UsageStatsManager（用户在系统里授权"使用情况访问"后才拿得到，
 * 设备所有者也不能免掉这一步，appop 只能 shell 给或用户手动开）。
 *
 * "娱乐"的定义跟冻结规则对齐：黑名单里的包，再加上系统标记为游戏类的应用。
 * 这样趋势图回答的就是真问题 —— "我最近每天有多大比例的时间花在该冻的东西上"。
 *
 * 页面固定深色，跟黑名单页一致。
 */
public class StatsActivity extends Activity {

    private int BG_DARK, SURFACE_DARK, TEXT_DARK, TEXT2_DARK, TRACK, RED_DARK, GREEN_DARK;
    private Ui look;
    private static final int BRAND        = Ui.BLUE;

    private LinearLayout body;
    private Button permBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int gen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        look = new Ui(this);
        BG_DARK = look.bg();
        SURFACE_DARK = look.surface();
        TEXT_DARK = look.text();
        TEXT2_DARK = look.text2();
        TRACK = look.line();
        RED_DARK = look.red();
        GREEN_DARK = look.green();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG_DARK);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(22), dp(16), dp(28));
        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("统计");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT_DARK);
        body.addView(title);

        TextView sub = new TextView(this);
        sub.setText("本月应用排行 · 游戏时长 · 娱乐占比趋势");
        sub.setTextSize(13);
        sub.setTextColor(TEXT2_DARK);
        sub.setPadding(0, dp(4), 0, dp(14));
        body.addView(sub);

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (look.dark != Ui.isDark(this)) {
            recreate();
            return;
        }
        render();   // 从系统授权页回来时重查一次权限
        Hub.kick(this);   // 顺带踢一脚中心仓库（手机端使用统计/台账推送都在里面，有节流）
    }

    private void render() {
        final int my = ++gen;   // onCreate+onResume 会连开两个线程，只有最新一代能回帖
        body.removeViews(2, body.getChildCount() - 2);   // 标题和副标题保留
        if (!hasUsageAccess(this)) {
            permCard();
            return;
        }
        loadingCard();
        final Context c = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final StatsData d = collect(c);
                // 顺路拉一次电脑端数据（内部 10 分钟节流，失败保留旧快照）。
                // 必须在后台线程：阻塞式网络。
                PcStats.ensureFresh(c);
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed() || my != gen) {
                            return;
                        }
                        body.removeViews(2, body.getChildCount() - 2);
                        renderData(d);
                    }
                });
            }
        }).start();
    }

    /* ==================== 权限 ==================== */

    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void permCard() {
        LinearLayout card = card();
        TextView tv = new TextView(this);
        tv.setText("还没有「使用情况访问」权限\n\n"
                + "统计要读系统的应用使用时长（按 Android 的规矩，这一项必须用户在系统设置里亲手开）。\n"
                + "点下面去开：找到 AwayFromShorts，允许「使用情况访问」。");
        tv.setTextSize(13);
        tv.setTextColor(TEXT_DARK);
        tv.setLineSpacing(dp(4), 1f);
        card.addView(tv);

        Button go = new Button(this);
        go.setText("去开启使用情况访问");
        go.setAllCaps(false);
        go.setTextColor(0xFFFFFFFF);
        go.setBackgroundColor(BRAND);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(10);
        go.setLayoutParams(lp);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(android.provider.Settings
                            .ACTION_USAGE_ACCESS_SETTINGS));
                } catch (Throwable t) {
                    toast("打不开设置页，请在系统设置里手动搜索「使用情况访问」");
                }
            }
        });
        card.addView(go);
        addCard(card);
    }

    private void loadingCard() {
        LinearLayout card = card();
        TextView tv = new TextView(this);
        tv.setText("正在读系统使用数据…");
        tv.setTextSize(13);
        tv.setTextColor(TEXT2_DARK);
        card.addView(tv);
        addCard(card);
    }

    /* ==================== 数据 ==================== */

    private static final class DayTrend {
        final String label;      // "10-17"
        long total;
        long fun;
        DayTrend(String label) { this.label = label; }
        int pct() {
            return total <= 0 ? 0 : (int) Math.round(fun * 100.0 / total);
        }
    }

    private static final class StatsData {
        final List<Map.Entry<String, Long>> top = new ArrayList<>();  // pkg → 前台 ms
        final Map<String, String> names = new HashMap<>();
        long monthGame;
        long todayGame;
        final List<DayTrend> trend = new ArrayList<>();
        boolean tooShort;    // 系统数据不足（刚刷机/权限刚开）
    }

    /** 纯收集，可在测试里对拍逻辑；网络与 UI 都不碰。 */
    static StatsData collect(Context c) {
        StatsData d = new StatsData();
        UsageStatsManager usm = (UsageStatsManager)
                c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            d.tooShort = true;
            return d;
        }
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        long monthStart = cal.getTimeInMillis();

        PackageManager pm = c.getPackageManager();

        // ---- 本月聚合（INTERVAL_BEST 会在月内取最合适的粒度，自己再按包合并）----
        Map<String, Long> month = new HashMap<>();
        try {
            List<UsageStats> list = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST, monthStart, now);
            if (list != null) {
                for (UsageStats u : list) {
                    if (u == null) continue;
                    Long old = month.get(u.getPackageName());
                    long v = Math.max(0, u.getTotalTimeInForeground());
                    month.put(u.getPackageName(), (old == null ? 0 : old) + v);
                }
            }
        } catch (Throwable ignored) {
        }

        // ---- 近 7 天按天聚合 ----
        Map<String, DayTrend> byDay = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            Calendar t = Calendar.getInstance();
            t.add(Calendar.DAY_OF_YEAR, -i);
            byDay.put(ymd(t), new DayTrend(
                    String.format(java.util.Locale.US, "%02d-%02d",
                            t.get(Calendar.MONTH) + 1, t.get(Calendar.DAY_OF_MONTH))));
        }
        try {
            List<UsageStats> daily = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, todayStart - 6L * 86400000L, now);
            if (daily != null) {
                for (UsageStats u : daily) {
                    if (u == null) continue;
                    DayTrend day = byDay.get(ymdOf(u.getFirstTimeStamp()));
                    if (day == null) continue;
                    long v = Math.max(0, u.getTotalTimeInForeground());
                    day.total += v;
                }
            }
        } catch (Throwable ignored) {
        }

        // ---- 娱乐集合：黑名单 ∪ 游戏类 ----
        Set<String> funPkgs = RuleStore.packages(c);
        for (String pkg : month.keySet()) {
            if (isGame(pm, pkg)) {
                funPkgs.add(pkg);
            }
        }

        // ---- 排行 + 游戏时长 + 娱乐/全部 ----
        // 自己整个剔除：排行、names、时长全都不要有 —— 否则 top 里有自己而
        // names 里没有，排行第一名就会渲染成 "1. null"
        month.remove(c.getPackageName());
        for (Map.Entry<String, Long> e : month.entrySet()) {
            String pkg = e.getKey();
            d.names.put(pkg, labelOf(pm, pkg));
            if (isGame(pm, pkg)) {
                d.monthGame += e.getValue();
            }
        }
        d.top.addAll(month.entrySet());
        Collections.sort(d.top, new Comparator<Map.Entry<String, Long>>() {
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        if (d.top.size() > 10) {
            d.top.subList(10, d.top.size()).clear();
        }

        try {
            List<UsageStats> todayList = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST, todayStart, now);
            if (todayList != null) {
                for (UsageStats u : todayList) {
                    if (u != null && isGame(pm, u.getPackageName())) {
                        d.todayGame += Math.max(0, u.getTotalTimeInForeground());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // ---- 娱乐占比：黑名单/游戏 的前台时长 ÷ 全部前台时长 ----
        try {
            List<UsageStats> daily = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, todayStart - 6L * 86400000L, now);
            if (daily != null) {
                for (UsageStats u : daily) {
                    if (u == null) continue;
                    DayTrend day = byDay.get(ymdOf(u.getFirstTimeStamp()));
                    if (day == null) continue;
                    if (funPkgs.contains(u.getPackageName())) {
                        day.fun += Math.max(0, u.getTotalTimeInForeground());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        d.trend.addAll(byDay.values());
        return d;
    }

    private static boolean isGame(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return ai != null && ai.category == ApplicationInfo.CATEGORY_GAME;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String labelOf(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = ai != null ? pm.getApplicationLabel(ai) : null;
            if (cs != null && cs.length() > 0) {
                return cs.toString();
            }
        } catch (Throwable ignored) {
        }
        return pkg;   // 没 label 的系统组件退回包名，不能显示成 "null"
    }

    private static String ymd(Calendar t) {
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                t.get(Calendar.YEAR), t.get(Calendar.MONTH) + 1, t.get(Calendar.DAY_OF_MONTH));
    }

    private static String ymdOf(long ms) {
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        return ymd(t);
    }

    /* ==================== 渲染 ==================== */

    private void renderData(StatsData d) {
        // —— 本月应用排行 ——
        LinearLayout rank = card();
        head(rank, "本月应用排行");
        if (d.top.isEmpty()) {
            note(rank, "系统还没攒出本月数据（刚授权 / 刚开机）");
        } else {
            long max = Math.max(1, d.top.get(0).getValue());
            int i = 0;
            for (Map.Entry<String, Long> e : d.top) {
                rankBar(rank,
                        (i + 1) + ". " + d.names.get(e.getKey()),
                        fmt(e.getValue()),
                        e.getValue() / (float) max,
                        BRAND);
                i++;
            }
        }
        addCard(rank);

        // —— 游戏时长 ——
        LinearLayout game = card();
        head(game, "游戏时长");
        gameBar(game, "本月合计", d.monthGame, RED_DARK);
        gameBar(game, "今天", d.todayGame, RED_DARK);
        if (d.monthGame <= 0) {
            note(game, "本月没有游戏类应用的前台记录");
        }
        addCard(game);

        // —— 娱乐占比趋势 ——
        LinearLayout trend = card();
        head(trend, "近 7 天娱乐占比");
        TextView def = note(trend, "娱乐 = 黑名单应用 + 系统游戏类 · 占每天全部前台时长");
        def.setPadding(0, 0, 0, dp(6));
        int days = 0;
        long sumPct = 0;
        for (DayTrend t : d.trend) {
            if (t.total > 60_000) {   // 小于 1 分钟的一天没有统计意义
                days++;
                sumPct += t.pct();
            }
            trendBar(trend, t.label, t.pct(), t.total);
        }
        if (days > 0) {
            TextView avg = new TextView(this);
            avg.setText("7 天平均 " + (sumPct / days) + "%");
            avg.setTextSize(13);
            avg.setTypeface(Typeface.DEFAULT_BOLD);
            avg.setTextColor(sumPct / days >= 30 ? RED_DARK : GREEN_DARK);
            avg.setPadding(0, dp(8), 0, 0);
            trend.addView(avg);
        }
        addCard(trend);

        // —— 电脑端应用时长（Gist 同步的 PC 面板采样数据）——
        LinearLayout pc = card();
        head(pc, "电脑端应用时长");
        String pcDay = PcStats.latestDay(this);
        List<PcStats.Entry> pcApps = PcStats.dayApps(this, pcDay);
        if (pcApps.isEmpty()) {
            note(pc, "还没拉到电脑数据。电脑开着面板时每 15 分钟推一次，晚点再进来看。");
        } else {
            boolean isToday = pcDay.equals(ymd(Calendar.getInstance()));
            String up = PcStats.updatedAt(this);
            TextView u = note(pc, (isToday ? "今天" : pcDay)
                    + " · 共 " + pcDayTotalMin(pcApps) + " 分钟 · 更新于 "
                    + (up.length() > 0 ? up : "-"));
            u.setPadding(0, 0, 0, dp(6));
            long maxMin = Math.max(1, pcApps.get(0).min);
            int n = Math.min(10, pcApps.size());
            for (int i = 0; i < n; i++) {
                PcStats.Entry e = pcApps.get(i);
                rankBar(pc,
                        (i + 1) + ". " + PcStats.label(e.name),
                        fmt(e.min * 60000L),
                        e.min / (float) maxMin,
                        0xFF9B7BE8);   // 紫：跟手机端的品牌蓝区分开
            }
        }
        addCard(pc);
    }

    private static int pcDayTotalMin(List<PcStats.Entry> apps) {
        int sum = 0;
        for (PcStats.Entry e : apps) {
            sum += e.min;
        }
        return sum;
    }

    /** 造一张卡片，调用方自己决定什么时候 add 进 body（只 add 一次）。 */
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(SURFACE_DARK);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        return card;
    }

    private void addCard(LinearLayout card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        body.addView(card, lp);
    }

    private void head(LinearLayout card, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(TEXT_DARK);
        tv.setPadding(0, 0, 0, dp(8));
        card.addView(tv);
    }

    private TextView note(LinearLayout card, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(TEXT2_DARK);
        card.addView(tv);
        return tv;
    }

    /** 排行行：名字 + 时长，下面一条长度成比例的条。 */
    private void rankBar(LinearLayout card, String name, String time, float frac, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(13);
        n.setTextColor(TEXT_DARK);
        n.setSingleLine(true);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(n, lp1);
        TextView t = new TextView(this);
        t.setText(time);
        t.setTextSize(13);
        t.setTextColor(TEXT2_DARK);
        row.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row);
        bar(card, Math.max(0.02f, frac), color);
    }

    /** 趋势行：日期 + 占比 + 条（占比条），条右侧留灰底。 */
    private void trendBar(LinearLayout card, String label, int pct, long total) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView d = new TextView(this);
        d.setText(label);
        d.setTextSize(12);
        d.setTextColor(TEXT2_DARK);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp1.rightMargin = dp(8);
        row.addView(d, lp1);
        TextView p = new TextView(this);
        p.setText(pct + "%");
        p.setTextSize(12);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextColor(pct >= 30 ? RED_DARK : GREEN_DARK);
        row.addView(p, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row);
        // 占比条：彩 weight=pct，灰 weight=100-pct，直接用 weight 表达比例
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundColor(Color.TRANSPARENT);
        View fill = new View(this);
        fill.setBackgroundColor(pct >= 30 ? RED_DARK : GREEN_DARK);
        LinearLayout.LayoutParams lpFill = new LinearLayout.LayoutParams(0, dp(5), pct);
        track.addView(fill, lpFill);
        View rest = new View(this);
        rest.setBackgroundColor(TRACK);
        track.addView(rest, new LinearLayout.LayoutParams(0, dp(5), 100 - pct));
        LinearLayout.LayoutParams lpTrack = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        lpTrack.setMargins(0, dp(3), 0, dp(6));
        card.addView(track, lpTrack);
        if (total <= 60_000) {
            TextView tiny = new TextView(this);
            tiny.setText("　（这天数据太少，不算数）");
            tiny.setTextSize(11);
            tiny.setTextColor(TEXT2_DARK);
            tiny.setPadding(0, 0, 0, dp(4));
            card.addView(tiny);
        }
    }

    private void gameBar(LinearLayout card, String label, long ms, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView n = new TextView(this);
        n.setText(label);
        n.setTextSize(13);
        n.setTextColor(TEXT_DARK);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(n, lp1);
        TextView t = new TextView(this);
        t.setText(fmt(ms));
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ms > 0 ? color : TEXT2_DARK);
        row.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row);
    }

    /** 通用比例条。 */
    private void bar(LinearLayout card, float frac, int color) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        View fill = new View(this);
        fill.setBackgroundColor(color);
        LinearLayout.LayoutParams lpFill = new LinearLayout.LayoutParams(0, dp(5), frac);
        track.addView(fill, lpFill);
        View rest = new View(this);
        rest.setBackgroundColor(TRACK);
        track.addView(rest, new LinearLayout.LayoutParams(0, dp(5), 1f - frac));
        LinearLayout.LayoutParams lpTrack = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        lpTrack.setMargins(0, dp(3), 0, dp(8));
        card.addView(track, lpTrack);
    }

    /* ==================== 小工具 ==================== */

    static String fmt(long ms) {
        if (ms <= 0) {
            return "0 分";
        }
        long min = ms / 60000L;
        if (min < 1) {
            return "<1 分";
        }
        long h = min / 60;
        long m = min % 60;
        if (h == 0) {
            return m + " 分";
        }
        return h + " 小时 " + m + " 分";
    }

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
