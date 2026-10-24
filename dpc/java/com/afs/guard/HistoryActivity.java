package com.afs.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 打卡历史：连续天数、近 30 天格子、每个目标的进度、最近的打卡流水。
 *
 * 数据全部来自本机 {@link StudyStore}——本机就是学习台，打卡记录都在 study.json 里，
 * 这一页只是把它变成能看的。PC 链路（goal-* 那套）的记录在电脑那边，不归这页管。
 */
public class HistoryActivity extends Activity {

    /* 配色/圆角全部来自 {@link Ui}：深色为主，浅色跟随全局设置。
       这些值原来是写死的静态常量，只支持深色；改成实例字段后能跟着主题走。 */
    private Ui look;
    private int BG, SURFACE, TEXT, TEXT2, TEXT3, LINE, TRACK, ACCENT, GREEN, GREEN_DIM, RED;

    @Override
    protected void onResume() {
        super.onResume();
        if (look != null && look.dark != Ui.isDark(this)) recreate();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        look = new Ui(this);
        BG = look.bg();
        SURFACE = look.surface();
        TEXT = look.text();
        TEXT2 = look.text2();
        TEXT3 = look.text3();
        LINE = look.line();
        TRACK = look.dark ? 0xFF23262D : 0xFFEDEFF2;
        ACCENT = look.accent();
        GREEN = look.green();
        GREEN_DIM = look.dark ? 0xFF1F6B47 : 0xFF2E8C5E;
        RED = look.red();
        List<StudyStore.G> goals = StudyStore.goals(this);
        List<StudyStore.Rec> recs = StudyStore.recs(this);

        // ---- 按天汇总：date -> 笔数 ----
        Map<String, Integer> byDay = new HashMap<>();
        Map<String, Double> byDayAmount = new HashMap<>();
        Map<String, java.util.Set<String>> tabooByDay = new HashMap<>();
        for (StudyStore.Rec r : recs) {
            Integer n = byDay.get(r.date);
            byDay.put(r.date, n == null ? 1 : n + 1);
            Double a = byDayAmount.get(r.date);
            byDayAmount.put(r.date, (a == null ? 0 : a) + r.amount);
        }
        List<StudyGate.TabooClaim> tabooClaims = StudyGate.tabooLog(this, 200);
        java.text.SimpleDateFormat tabooDate = new java.text.SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.US);
        for (StudyGate.TabooClaim claim : tabooClaims) {
            String day = tabooDate.format(new java.util.Date(claim.ts));
            addTabooDay(tabooByDay, day, claim.name);
        }
        for (StudyGate.TabooClaim declined : StudyGate.tabooDeclineLog(this)) {
            String day = tabooDate.format(new java.util.Date(declined.ts));
            addTabooDay(tabooByDay, day, declined.name);
        }

        String today = StudyStore.today();

        // ---- 连续打卡：从今天往前数；今天还没打不打断（从昨天起算）----
        int streak = 0;
        String cursor = byDay.containsKey(today) ? today : StudyStore.plusDays(-1);
        while (byDay.containsKey(cursor)) {
            streak++;
            cursor = shiftDay(cursor, -1);
        }

        // ---- 本月 ----
        String month = today.substring(0, 7);
        int monthRecs = 0;
        int monthDays = 0;
        java.util.Calendar monthCalendar = java.util.Calendar.getInstance();
        monthCalendar.set(Integer.parseInt(today.substring(0, 4)),
                Integer.parseInt(today.substring(5, 7)) - 1, 1);
        int daysInMonth = monthCalendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
        for (Map.Entry<String, Integer> e : byDay.entrySet()) {
            if (e.getKey().startsWith(month)) {
                monthRecs += e.getValue();
                monthDays++;
            }
        }

        // ---- 最近流水（按时间倒序）----
        List<StudyStore.Rec> recent = new ArrayList<>(recs);
        java.util.Collections.sort(recent, new java.util.Comparator<StudyStore.Rec>() {
            public int compare(StudyStore.Rec a, StudyStore.Rec b) {
                return a.ts > b.ts ? -1 : (a.ts < b.ts ? 1 : 0);
            }
        });
        if (recent.size() > 20) {
            recent = recent.subList(0, 20);
        }
        Map<String, StudyStore.G> goalById = new HashMap<>();
        for (StudyStore.G g : goals) {
            goalById.put(g.id, g);
        }

        // 认领禁忌的流水混进同一条时间线：StudyStore.Rec 和 TabooClaim 两种元素
        List<Object> flow = new ArrayList<>();
        for (StudyStore.Rec r : recent) {
            flow.add(r);
        }
        for (StudyGate.TabooClaim tc : tabooClaims) {
            flow.add(tc);
        }
        java.util.Collections.sort(flow, new java.util.Comparator<Object>() {
            public int compare(Object a, Object b) {
                long ta = a instanceof StudyStore.Rec ? ((StudyStore.Rec) a).ts
                        : ((StudyGate.TabooClaim) a).ts;
                long tb = b instanceof StudyStore.Rec ? ((StudyStore.Rec) b).ts
                        : ((StudyGate.TabooClaim) b).ts;
                return ta > tb ? -1 : (ta < tb ? 1 : 0);
            }
        });
        if (flow.size() > 20) {
            flow = flow.subList(0, 20);
        }

        // ==================== 画 ====================
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(BG);
        sc.setFillViewport(true);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        sc.addView(body);

        TextView title = new TextView(this);
        title.setText("打卡历史");
        title.setTextColor(TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(title);

        // ---- 三个大数字 ----
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        body.addView(stats, sp);
        stat(stats, streak + " 天", "连续打卡");
        stat(stats, monthDays + " / " + daysInMonth, "本月打卡天数");
        stat(stats, String.valueOf(monthRecs), "本月打卡笔数");

        LinearLayout lifetimeStats = new LinearLayout(this);
        lifetimeStats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lifetimeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lifetimeLp.topMargin = dp(8);
        body.addView(lifetimeStats, lifetimeLp);
        stat(lifetimeStats, String.valueOf(recs.size()), "累计打卡笔数");
        stat(lifetimeStats, byDay.size() + " 天", "累计打卡天数");

        // ---- 近 30 天：日期和当天笔数直接可见 ----
        LinearLayout gridCard = card(body);
        head(gridCard, "近 30 天");
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        gridCard.addView(grid);
        String start = shiftDay(today, -29);
        LinearLayout row = null;
        for (int i = 0; i < 30; i++) {
            if (i % 5 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(7);
                grid.addView(row, rp);
            }
            String d = shiftDay(start, i);
            Integer count = byDay.get(d);
            int tabooCount = tabooByDay.containsKey(d) ? tabooByDay.get(d).size() : 0;
            boolean taboo = tabooCount > 0;
            TextView cell = new TextView(this);
            cell.setText(d.substring(5).replace('-', '/') + "\n"
                    + (count == null ? (taboo ? tabooCount + " 禁" : "—")
                    : (taboo ? tabooCount + "禁/" + count + "目" : count + " 笔")));
            cell.setGravity(Gravity.CENTER);
            cell.setTextSize(taboo ? 10 : 11);
            cell.setTextColor(count == null && !taboo ? TEXT2 : 0xFFFFFFFF);
            cell.setBackground(taboo && count != null
                    ? new SplitDayDrawable(0xFF111318, cellColor(count), dp(9), dp(1))
                    : round(taboo ? 0xFF111318 : cellColor(count), dp(9)));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(52), 1f);
            if (i % 5 < 4) cp.rightMargin = dp(6);
            row.addView(cell, cp);
        }
        TextView legend = new TextView(this);
        boolean filled = byDay.containsKey(today) || tabooByDay.containsKey(today);
        legend.setText("黑色：禁忌卡 · 绿色：目标卡 · 黑绿斜分：两种都有；格内分别显示张数/笔数"
                + (filled ? " · 今天有记录 ✓" : " · 今天还没记录"));
        legend.setTextColor(TEXT3);
        legend.setTextSize(11);
        legend.setPadding(0, dp(2), 0, 0);
        gridCard.addView(legend);

        // 历史不限于近 30 天；按日期列出整份本机台账，点标题展开。
        final LinearLayout datesCard = card(body);
        final TextView datesTitle = new TextView(this);
        datesTitle.setText("全部打卡日期（" + byDay.size() + " 天）  ▸");
        datesTitle.setTextColor(TEXT);
        datesTitle.setTextSize(14);
        datesTitle.setTypeface(Typeface.DEFAULT_BOLD);
        datesTitle.setPadding(0, dp(2), 0, dp(2));
        datesCard.addView(datesTitle);
        final LinearLayout datesList = new LinearLayout(this);
        datesList.setOrientation(LinearLayout.VERTICAL);
        datesList.setVisibility(View.GONE);
        datesCard.addView(datesList);
        datesTitle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean open = datesList.getVisibility() != View.VISIBLE;
                datesList.setVisibility(open ? View.VISIBLE : View.GONE);
                datesTitle.setText("全部打卡日期（" + byDay.size() + " 天）  "
                        + (open ? "▾" : "▸"));
                if (!open || datesList.getChildCount() > 0) return;
                if (byDay.isEmpty()) {
                    blank(datesList, "还没有打卡日期");
                    return;
                }
                java.util.List<String> dates = new java.util.ArrayList<>(byDay.keySet());
                java.util.Collections.sort(dates, java.util.Collections.reverseOrder());
                for (String date : dates) {
                    TextView dateRow = new TextView(HistoryActivity.this);
                    dateRow.setText(date + "    " + byDay.get(date) + " 笔");
                    dateRow.setTextColor(TEXT);
                    dateRow.setTextSize(13);
                    dateRow.setPadding(dp(2), dp(10), dp(2), dp(10));
                    datesList.addView(dateRow);
                    View divider = new View(HistoryActivity.this);
                    divider.setBackgroundColor(LINE);
                    datesList.addView(divider, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                }
            }
        });

        // ---- 每个目标的进度 ----
        LinearLayout progCard = card(body);
        head(progCard, "目标进度");
        if (goals.isEmpty()) {
            blank(progCard, "还没有学习目标，去打卡页右上「管理」建一个");
        } else {
            for (StudyStore.G g : goals) {
                double done = StudyStore.doneOf(this, g);
                double total = Math.max(g.total, 0.0001);
                float p = (float) Math.min(1.0, Math.max(0.0, done / total));
                TextView name = new TextView(this);
                String due = g.due.isEmpty() ? "" : " · " + StudyStore.daysLeft(g.due) + " 天后截止";
                name.setText(g.name + "  " + fmt(done) + " / " + fmt(g.total) + " " + g.unit + due);
                name.setTextColor(TEXT);
                name.setTextSize(13);
                progCard.addView(name);

                // 轨道 + 填充叠在一起：FrameLayout 里横铺一行 weight 分配
                FrameLayout track = new FrameLayout(this);
                track.setBackground(round(TRACK, dp(4)));
                LinearLayout fillRow = new LinearLayout(this);
                fillRow.setOrientation(LinearLayout.HORIZONTAL);
                View fill = new View(this);
                fill.setBackground(round(p >= 1f ? GREEN : ACCENT, dp(4)));
                View rest = new View(this);
                fillRow.addView(fill, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, p));
                fillRow.addView(rest, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - p));
                track.addView(fillRow, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
                bp.topMargin = dp(6);
                bp.bottomMargin = dp(4);
                progCard.addView(track, bp);
                View gap = new View(this);
                progCard.addView(gap, new LinearLayout.LayoutParams(1, dp(10)));
            }
        }

        // ---- 最近流水 ----
        LinearLayout recCard = card(body);
        head(recCard, "最近流水");
        TextView undoTip = new TextView(this);
        undoTip.setText("误触了？点一笔打卡记录可以撤销（两步确认）；红色是认领禁忌");
        undoTip.setTextColor(TEXT3);
        undoTip.setTextSize(11);
        undoTip.setPadding(0, dp(2), 0, 0);
        recCard.addView(undoTip);
        if (flow.isEmpty()) {
            blank(recCard, "还没有打卡记录");
        } else {
            java.text.SimpleDateFormat md =
                    new java.text.SimpleDateFormat("MM-dd", java.util.Locale.US);
            for (int i = 0; i < flow.size(); i++) {
                final Object item = flow.get(i);
                TextView rowT = new TextView(this);
                rowT.setTextSize(13);
                rowT.setPadding(0, dp(8), 0, dp(8));
                if (item instanceof StudyGate.TabooClaim) {
                    StudyGate.TabooClaim tc = (StudyGate.TabooClaim) item;
                    rowT.setText(md.format(new java.util.Date(tc.ts)) + "  认领禁忌：" + tc.name);
                    rowT.setTextColor(RED);
                    // 禁忌认领没有「撤销」一说，认了就是认了
                } else {
                    final StudyStore.Rec r = (StudyStore.Rec) item;
                    StudyStore.G g = goalById.get(r.goalId);
                    String name = g == null ? r.goalId : g.name;
                    final String unit = g == null ? "" : g.unit;
                    rowT.setText(r.date.substring(5) + "  " + name
                            + "  +" + fmt(r.amount) + (unit.isEmpty() ? "" : " " + unit)
                            + "  ·点撤");
                    rowT.setTextColor(TEXT);
                    // 撤销入口搬到历史页：打卡页的卡片上不再放可点的东西
                    rowT.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            confirmUndo(r, unit);
                        }
                    });
                }
                recCard.addView(rowT);
                if (i < flow.size() - 1) {
                    View ln = new View(this);
                    ln.setBackgroundColor(LINE);
                    recCard.addView(ln, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                }
            }
        }

        // ---- 屏幕使用统计入口：从主页搬过来，和打卡数据放一页（都是"看自己"的事）----
        LinearLayout moreCard = card(body);
        head(moreCard, "更多");
        Button statsBtn = new Button(this);
        statsBtn.setText("📊 屏幕使用统计");
        statsBtn.setAllCaps(false);
        statsBtn.setTextSize(14);
        statsBtn.setTextColor(TEXT);
        statsBtn.setBackgroundColor(TRACK);
        statsBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new android.content.Intent(HistoryActivity.this, StatsActivity.class));
            }
        });
        moreCard.addView(statsBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(sc);
        try {
            getWindow().setStatusBarColor(BG);
            getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility()
                            & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
    }

    /** 格子颜色：没打卡 = 轨道灰；1–2 笔 = 浅绿；3 笔以上 = 深绿。 */
    private int cellColor(Integer n) {
        if (n == null || n == 0) {
            return TRACK;
        }
        return n <= 2 ? GREEN_DIM : GREEN;
    }

    private static void addTabooDay(Map<String, java.util.Set<String>> byDay,
                                    String day, String name) {
        java.util.Set<String> names = byDay.get(day);
        if (names == null) {
            names = new java.util.HashSet<>();
            byDay.put(day, names);
        }
        names.add(name);
    }

    /** 左上禁忌黑色、右下目标绿色，用 / 斜线隔开。 */
    private static final class SplitDayDrawable extends Drawable {
        private final int black, green;
        private final float radius, lineWidth;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SplitDayDrawable(int black, int green, float radius, float lineWidth) {
            this.black = black;
            this.green = green;
            this.radius = radius;
            this.lineWidth = lineWidth;
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            int save = canvas.save();
            Path clip = new Path();
            clip.addRoundRect(new RectF(b), radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(green);
            canvas.drawRect(b, paint);
            Path topLeft = new Path();
            topLeft.moveTo(b.left, b.top);
            topLeft.lineTo(b.right, b.top);
            topLeft.lineTo(b.left, b.bottom);
            topLeft.close();
            paint.setColor(black);
            canvas.drawPath(topLeft, paint);
            paint.setColor(0xCCFFFFFF);
            paint.setStrokeWidth(lineWidth);
            canvas.drawLine(b.left, b.bottom, b.right, b.top, paint);
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** yyyy-MM-dd 平移 n 天。 */
    private static String shiftDay(String date, int n) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd");
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTime(f.parse(date));
            cal.add(java.util.Calendar.DAY_OF_MONTH, n);
            return f.format(cal.getTime());
        } catch (Throwable t) {
            return date;
        }
    }

    /** 建一张卡并放进 body（MATCH_PARENT 宽 + 上边距），返回它继续往里塞内容。 */
    /** 「上次上传」那一行：没推过就说没推过，推过就给时间和版本号。 */
    private String pushAtText() {
        long at = Hub.lastPushAt(this);
        if (at <= 0) {
            return "还没有上传过";
        }
        String hm = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date(at));
        return "上次上传：" + hm + " · rev " + Hub.ledgerRev(this);
    }

    private LinearLayout card(LinearLayout body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(SURFACE, dp(18)));   // Voyage 那种大圆角卡
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        body.addView(card, lp);
        return card;
    }

    private void head(LinearLayout card, String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextColor(TEXT2);
        h.setTextSize(12);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(h);
        View ln = new View(this);
        ln.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        card.addView(ln, lp);
    }

    private void stat(LinearLayout row, String big, String small) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(round(SURFACE, dp(16)));
        box.setPadding(dp(10), dp(14), dp(10), dp(14));
        TextView b = new TextView(this);
        b.setText(big);
        b.setTextColor(TEXT);
        b.setTextSize(18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        box.addView(b);
        TextView s = new TextView(this);
        s.setText(small);
        s.setTextColor(TEXT3);
        s.setTextSize(11);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(4), 0, 0);
        box.addView(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) {
            lp.leftMargin = dp(8);
        }
        row.addView(box, lp);
    }

    private void blank(LinearLayout card, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT3);
        t.setTextSize(12);
        t.setPadding(0, dp(4), 0, dp(4));
        card.addView(t);
    }

    /** 撤销一笔打卡：两步确认防误触；删记录 → 闸门立刻重算 → hub 脏标记自动跟上。 */
    private void confirmUndo(final StudyStore.Rec r, final String unit) {
        StudyStore.G g = null;
        for (StudyStore.G x : StudyStore.goals(this)) {
            if (x.id.equals(r.goalId)) {
                g = x;
                break;
            }
        }
        final String name = g == null ? r.goalId : g.name;
        final String unit2 = (unit == null || unit.isEmpty()) ? "" : " " + unit;
        String when = r.date + (r.ts > 0
                ? " " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                        .format(new java.util.Date(r.ts)) : "");
        new AlertDialog.Builder(this)
                .setTitle("撤销这笔打卡？")
                .setMessage(name + "  +" + fmt(r.amount) + unit2 + "\n" + when
                        + "\n\n删掉后进度和冻结闸门立刻重算。")
                .setPositiveButton("撤销", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        StudyStore.deleteRec(HistoryActivity.this, r.id);
                        Toast.makeText(HistoryActivity.this,
                                "已撤销 " + name + "  -" + fmt(r.amount) + unit2,
                                Toast.LENGTH_SHORT).show();
                        recreate();   // 重新统计整页
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private static String fmt(double v) {        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
