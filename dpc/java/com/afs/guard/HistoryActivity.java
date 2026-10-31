package com.afs.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
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
import android.widget.EditText;
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
public class HistoryActivity extends ThemedActivity {
    static final String EXTRA_WEEKLY = "open_weekly_report";

    /* 配色/圆角全部来自 {@link Ui}：深色为主，浅色跟随全局设置。
       这些值原来是写死的静态常量，只支持深色；改成实例字段后能跟着主题走。 */
    private Ui look;
    private int BG, SURFACE, TEXT, TEXT2, TEXT3, LINE, TRACK, ACCENT, GREEN, GREEN_DIM, RED;

    private Map<String,int[]> chartCounts=new HashMap<>();
    private int[] dayCounts(String day){int[] value=chartCounts.get(day);if(value==null){value=new int[6];chartCounts.put(day,value);}return value;}
    private void recentPies(LinearLayout parent,String today,List<StudyStore.Rec> recs,Map<String,java.util.Set<String>> taboo){
        Map<String,java.util.Set<String>> goals=new HashMap<>(),memories=new HashMap<>();
        for(StudyStore.Rec rec:recs){if(!goals.containsKey(rec.date))goals.put(rec.date,new java.util.HashSet<>());goals.get(rec.date).add(rec.goalId);}
        for(String date:goals.keySet())dayCounts(date)[0]=goals.get(date).size();for(String date:taboo.keySet())dayCounts(date)[1]=taboo.get(date).size();
        try{org.json.JSONArray logs=MemoryStore.snapshot(this).db.optJSONArray("logs");for(int i=0;i<logs.length();i++){org.json.JSONObject log=logs.optJSONObject(i);if(log==null||!(log.optBoolean("credit")||log.optBoolean("flashcard")&&log.optInt("rating")>=2))continue;
            String date=CycleSummary.day(log.optLong("ts"));if(!memories.containsKey(date))memories.put(date,new java.util.HashSet<>());memories.get(date).add(log.optString("card"));}
            for(String date:memories.keySet())dayCounts(date)[2]=memories.get(date).size();}catch(RuntimeException ignored){}
        try{org.json.JSONObject days=QuizStore.snapshot(this).db.optJSONObject("days");java.util.Iterator<String> dates=days.keys();while(dates.hasNext()){String date=dates.next();dayCounts(date)[3]=days.optJSONObject(date).optInt("attempts");}}catch(RuntimeException ignored){}
        CardData data=CardStore.snapshot(this);for(int i=0;i<data.earned().length();i++)dayCounts(data.earned().optJSONObject(i).optString("day"))[4]++;
        org.json.JSONArray reports=data.summary(System.currentTimeMillis()).records();for(int i=0;i<reports.length();i++){org.json.JSONObject report=reports.optJSONObject(i);if(report.optLong("at")>0)dayCounts(CycleSummary.day(report.optLong("at")))[5]++;}
        LinearLayout panel=card(parent);head(panel,T.s("近30天 · 卡片类型"));LinearLayout controls=new LinearLayout(this);panel.addView(controls);
        TextView previous=new TextView(this),range=new TextView(this),next=new TextView(this);previous.setText(T.s("‹ 前30天"));next.setText(T.s("后30天 ›"));
        for(TextView control:new TextView[]{previous,range,next}){control.setTextSize(12);control.setTextColor(ACCENT);control.setGravity(Gravity.CENTER);control.setPadding(0,dp(14),0,dp(14));}
        controls.addView(previous,new LinearLayout.LayoutParams(-2,-2));controls.addView(range,new LinearLayout.LayoutParams(0,-2,1));controls.addView(next,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout grid=new LinearLayout(this);grid.setOrientation(LinearLayout.VERTICAL);panel.addView(grid);String[] end={today};
        Runnable render=()->{grid.removeAllViews();String start=shiftDay(end[0],-29);range.setText(start+T.s(" 至\n")+end[0]+" ▾");next.setEnabled(end[0].compareTo(today)<0);next.setAlpha(next.isEnabled()?1f:.35f);
            LinearLayout row=null;for(int i=0;i<30;i++){if(i%5==0){row=new LinearLayout(this);grid.addView(row);}String date=shiftDay(start,i);int[] counts=chartCounts.containsKey(date)?chartCounts.get(date):new int[6];DailyCardChart chart=new DailyCardChart(this,date,counts);row.addView(chart,new LinearLayout.LayoutParams(0,dp(110),1));chart.setOnClickListener(v->showDay(date,counts));}};
        previous.setOnClickListener(v->{end[0]=shiftDay(end[0],-30);render.run();});next.setOnClickListener(v->{String candidate=shiftDay(end[0],30);end[0]=candidate.compareTo(today)>0?today:candidate;render.run();});
        range.setOnClickListener(v->{String[] parts=end[0].split("-");android.app.DatePickerDialog picker=new android.app.DatePickerDialog(this,(date,y,m,d)->{end[0]=String.format(java.util.Locale.US,"%04d-%02d-%02d",y,m+1,d);render.run();},Integer.parseInt(parts[0]),Integer.parseInt(parts[1])-1,Integer.parseInt(parts[2]));picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();});render.run();
        TextView legend=new TextView(this);legend.setText(T.s("目标白 · 禁忌黑 · 记忆黄 · 刷题蓝 · 热爱粉 · 总结红\n点击日期查看数量与完成状态；点击日期范围可选择历史区间。"));legend.setTextSize(11);legend.setTextColor(TEXT3);panel.addView(legend);
    }
    private void showDay(String date,int[] counts){StringBuilder message=new StringBuilder();for(int i=0;i<counts.length;i++)message.append(T.s(DailyCardChart.NAMES[i])).append(T.s("：")).append(counts[i]).append("\n");message.append("\n").append(DailyCardCompletion.description(this,date));ThemeStore.dialog(this).setTitle(date).setMessage(message.toString()).setPositiveButton(T.s("关闭"),null).show();}

    @Override
    protected void onResume() {
        super.onResume();
        if (look != null && look.dark != Ui.isDark(this)) { recreate(); return; }
        if (look != null && getIntent().getBooleanExtra(EXTRA_WEEKLY, false)) {
            getIntent().removeExtra(EXTRA_WEEKLY);
            showWeekly(0);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
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

        DailyCardCompletion.capture(this);
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
        look.page(sc);
        sc.setFillViewport(true);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(24), dp(20), dp(32));
        sc.addView(body);

        TextView title = new TextView(this);
        title.setText(T.s("打卡历史"));
        title.setTextColor(TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(title);

        // 打卡日历并到这一页：设置里不再单独放一个入口，找「历史」就都在这里
        LinearLayout calendar = card(body);
        head(calendar, T.s("打卡日历"));
        TextView calendarLine = new TextView(this);
        calendarLine.setText(monthDays + " / " + daysInMonth + T.s(" 天 · 连续 ") + streak + T.s(" 天"));
        calendarLine.setTextColor(TEXT);
        calendarLine.setTextSize(17);
        calendarLine.setTypeface(Typeface.DEFAULT_BOLD);
        calendarLine.setPadding(0, dp(8), 0, 0);
        calendar.addView(calendarLine);
        TextView calendarHint = new TextView(this);
        calendarHint.setText(T.s("月视图、每天明细与打卡日记 ›"));
        calendarHint.setTextColor(ACCENT);
        calendarHint.setTextSize(14);
        calendarHint.setPadding(0, dp(6), 0, dp(2));
        calendar.addView(calendarHint);
        calendar.setOnClickListener(v -> startActivity(new Intent(this, CalendarActivity.class)));

        LinearLayout weekly = card(body);
        head(weekly, T.s("周期总结"));
        TextView weeklyLink = new TextView(this);
        weeklyLink.setText(T.s("查看与完成总结  ›"));
        weeklyLink.setTextSize(15);
        weeklyLink.setTextColor(ACCENT);
        weeklyLink.setPadding(0, dp(8), 0, dp(8));
        weeklyLink.setOnClickListener(v -> showWeekly(0));
        weekly.addView(weeklyLink);
        TextView savedSummaries=new TextView(this);savedSummaries.setText(T.s("已完成的总结  ›"));savedSummaries.setTextColor(ACCENT);savedSummaries.setTextSize(15);savedSummaries.setPadding(0,dp(8),0,dp(8));savedSummaries.setOnClickListener(v->summaryArchive());weekly.addView(savedSummaries);

        // ---- 三个大数字 ----
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        body.addView(stats, sp);
        stat(stats, streak + T.s(" 天"), T.s("连续打卡"));
        stat(stats, monthDays + " / " + daysInMonth, T.s("本月打卡天数"));
        stat(stats, String.valueOf(monthRecs), T.s("本月打卡笔数"));

        LinearLayout lifetimeStats = new LinearLayout(this);
        lifetimeStats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lifetimeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lifetimeLp.topMargin = dp(8);
        body.addView(lifetimeStats, lifetimeLp);
        stat(lifetimeStats, String.valueOf(recs.size()), T.s("累计打卡笔数"));
        stat(lifetimeStats, byDay.size() + T.s(" 天"), T.s("累计打卡天数"));
        stat(lifetimeStats, DailyCardCompletion.incompleteDays(this) + T.s(" 天"), T.s("未完成卡日"));

        incompleteCards(body);

        recentPies(body,today,recs,tabooByDay);
        // 历史不限于近 30 天；按日期列出整份本机台账，点标题展开。
        java.util.Set<String> allDates=new java.util.HashSet<>(chartCounts.keySet());org.json.JSONObject statuses=DailyCardCompletion.days(this);java.util.Iterator<String> statusDates=statuses.keys();while(statusDates.hasNext())allDates.add(statusDates.next());
        final LinearLayout datesCard = card(body);
        final TextView datesTitle = new TextView(this);
        datesTitle.setText(T.s("全部打卡日期（") + allDates.size() + T.s(" 天）  ▸"));
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
                datesTitle.setText(T.s("全部打卡日期（") + allDates.size() + T.s(" 天）  ")
                        + (open ? "▾" : "▸"));
                if (!open || datesList.getChildCount() > 0) return;
                if (allDates.isEmpty()) {
                    blank(datesList, T.s("还没有打卡日期"));
                    return;
                }
                java.util.List<String> dates = new java.util.ArrayList<>(allDates);
                java.util.Collections.sort(dates, java.util.Collections.reverseOrder());
                for (String date : dates) {
                    TextView dateRow = new TextView(HistoryActivity.this);
                    org.json.JSONObject state=statuses.optJSONObject(date);String status=DailyCardCompletion.description(state);
                    dateRow.setText(date+"  ·  "+status);
                    dateRow.setTextColor(state!=null&&!state.optString("missing").isEmpty()?RED:TEXT);
                    dateRow.setOnClickListener(row->showDay(date,dayCounts(date)));
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
        head(progCard, T.s("目标进度"));
        if (goals.isEmpty()) {
            blank(progCard, T.s("还没有学习目标，去打卡页右上「管理」建一个"));
        } else {
            for (StudyStore.G g : goals) {
                double done = StudyStore.doneOf(this, g);
                double total = Math.max(g.total, 0.0001);
                float p = (float) Math.min(1.0, Math.max(0.0, done / total));
                TextView name = new TextView(this);
                String due = g.due.isEmpty() ? "" : " · " + StudyStore.daysLeft(g.due) + T.s(" 天后截止");
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
        TextView flowToggle=new TextView(this);flowToggle.setText(T.s("最近流水  ▸"));flowToggle.setTextColor(TEXT);flowToggle.setTextSize(14);flowToggle.setPadding(0,dp(8),0,dp(8));recCard.addView(flowToggle);
        LinearLayout flowBody=new LinearLayout(this);flowBody.setOrientation(LinearLayout.VERTICAL);flowBody.setVisibility(View.GONE);recCard.addView(flowBody);
        flowToggle.setOnClickListener(v->{boolean open=flowBody.getVisibility()!=View.VISIBLE;flowBody.setVisibility(open?View.VISIBLE:View.GONE);flowToggle.setText(open?T.s("最近流水  ▾"):T.s("最近流水  ▸"));});
        TextView undoTip = new TextView(this);
        undoTip.setText(T.s("当天的打卡可点记录撤回；昨天及更早的记录已锁定。红色是认领禁忌"));
        undoTip.setTextColor(TEXT3);
        undoTip.setTextSize(11);
        undoTip.setPadding(0, dp(2), 0, 0);
        flowBody.addView(undoTip);
        if (flow.isEmpty()) {
            blank(flowBody, T.s("还没有打卡记录"));
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
                    rowT.setText(md.format(new java.util.Date(tc.ts)) + T.s("  认领禁忌：") + tc.name);
                    rowT.setTextColor(RED);
                    // 禁忌认领没有「撤销」一说，认了就是认了
                } else {
                    final StudyStore.Rec r = (StudyStore.Rec) item;
                    StudyStore.G g = goalById.get(r.goalId);
                    String name = g == null ? r.goalId : g.name;
                    final String unit = g == null ? "" : g.unit;
                    rowT.setText(r.date.substring(5) + "  " + name
                            + "  +" + fmt(r.amount) + (unit.isEmpty() ? "" : " " + unit)
                            + (StudyStore.canUndoDate(r.date, StudyStore.today()) ? T.s("  ·点撤回") : T.s("  ·已锁定")));
                    rowT.setTextColor(TEXT);
                    // 撤销入口搬到历史页：打卡页的卡片上不再放可点的东西
                    if (StudyStore.canUndoDate(r.date, StudyStore.today())) rowT.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            confirmUndo(r, unit);
                        }
                    });
                }
                flowBody.addView(rowT);
                if (i < flow.size() - 1) {
                    View ln = new View(this);
                    ln.setBackgroundColor(LINE);
                    flowBody.addView(ln, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                }
            }
        }

        // ---- 屏幕使用统计入口：从主页搬过来，和打卡数据放一页（都是"看自己"的事）----
        LinearLayout moreCard = card(body);
        head(moreCard, T.s("更多"));
        Button statsBtn = new Button(this);
        statsBtn.setText(T.s("📊 屏幕使用统计"));
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
            return T.s("还没有上传过");
        }
        String hm = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date(at));
        return T.s("上次上传：") + hm + " · rev " + Hub.ledgerRev(this);
    }

    /** Reports are derived from the source records, including edits and undo. */
    private void showWeekly(int offset) {
        long now=System.currentTimeMillis();CycleSummary summary=CardStore.snapshot(this).summary(now);
        List<CycleSummary.Period> choices=summary.currentPeriods(now);
        String requested=getIntent().getStringExtra("summary_key");getIntent().removeExtra("summary_key");
        if(requested!=null)for(CycleSummary.Period p:choices)if(p.key.equals(requested)){showCycle(p.schedule,offset);return;}
        if(choices.isEmpty()){Toast.makeText(this,T.s("暂无启用的总结周期"),Toast.LENGTH_SHORT).show();return;}
        if(choices.size()==1){showCycle(choices.get(0).schedule,offset);return;}
        String[] labels=new String[choices.size()];for(int i=0;i<labels.length;i++){CycleSummary.Period p=choices.get(i);labels[i]=p.cycleLabel+T.s("总结 · ")+p.start+T.s(" 至 ")+p.through+(p.complete?T.s(" · 已完成"):now>=p.visibleAt?T.s(" · 待完成"):T.s(" · 未到期"));}
        ThemeStore.dialog(this).setTitle(T.s("选择总结周期")).setItems(labels,(d,w)->showCycle(choices.get(w).schedule,offset)).setNegativeButton(T.s("关闭"),null).show();
    }
    private void showCycle(String schedule,int offset) {
        long now=System.currentTimeMillis();CycleSummary summary=CardStore.snapshot(this).summary(now);
        CycleSummary.Period period=null;for(CycleSummary.Period choice:summary.currentPeriods(now))if(choice.schedule.equals(schedule)){period=choice;break;}
        if(period==null)return;
        CycleSummary.Period pending=null;for(CycleSummary.Period choice:summary.allPending(now))if(choice.schedule.equals(schedule)){pending=choice;break;}
        for(int i=0;i<offset;i++)period=summary.prior(period);
        final CycleSummary.Period shown=period;
        java.util.Calendar monday=reportDate(period.start),end=reportDate(period.end);
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        String from = period.start;
        String through = period.through;
        java.util.Set<String> days = new java.util.HashSet<>();
        int checkins = 0;
        for (StudyStore.Rec r : StudyStore.recs(this)) {
            if (r.date.compareTo(from) >= 0 && r.date.compareTo(through) <= 0) {
                checkins++;
                days.add(r.date);
            }
        }
        String quizText, memoryText;
        try {
            QuizData quiz = QuizStore.snapshot(this);
            int answered = 0, correct = 0;
            java.util.Calendar cursor = (java.util.Calendar) monday.clone();
            for (;cursor.before(end);cursor.add(java.util.Calendar.DAY_OF_MONTH, 1)) {
                int[] result = quiz.stats(format.format(cursor.getTime()));
                answered += result[0]; correct += result[1];
            }
            quizText = answered + T.s(" 题 · 答对 ") + correct + T.s(" 题");
        } catch (RuntimeException e) { quizText = T.s("暂时无法读取"); }
        try {
            org.json.JSONArray logs = MemoryStore.snapshot(this).db.optJSONArray("logs");
            java.util.Set<String> learned = new java.util.HashSet<>();
            for (int i = 0; logs != null && i < logs.length(); i++) {
                org.json.JSONObject log = logs.optJSONObject(i);
                if (log == null || !log.optBoolean("credit")) continue;
                long at = log.optLong("ts");
                if (at >= monday.getTimeInMillis() && at < end.getTimeInMillis())
                    learned.add(format.format(new java.util.Date(at)) + ":" + log.optString("card"));
            }
            memoryText = learned.size() + T.s(" 张次（每天每张计一次）");
        } catch (RuntimeException e) { memoryText = T.s("暂时无法读取"); }
        boolean confirmed = period.complete;
        boolean canConfirm = offset == 0 && pending!=null && pending.key.equals(period.key);
        String status = confirmed ? T.s("总结已完成") : canConfirm ? now>=period.endAt?T.s("总结已逾期，补完后解除本项学习联动"):T.s("周期末 20:00 后未完成会延长冻结") : T.s("周期最后一天开放完成总结");
        String detail = from + T.s(" 至 ") + through
                + "\n" + status
                + T.s("\n\n打卡：") + days.size() + T.s(" 天 · ") + checkins + T.s(" 笔")
                + T.s("\n记忆：") + memoryText + T.s("\n刷题：") + quizText;
        LinearLayout report=new LinearLayout(this);report.setOrientation(LinearLayout.VERTICAL);report.setPadding(dp(24),0,dp(24),dp(12));
        TextView stats=new TextView(this);stats.setText(detail);stats.setTextColor(TEXT);stats.setTextSize(15);report.addView(stats);
        EditText note=new EditText(this);note.setHint(T.s("写下这段时间的收获与下一步计划"));note.setText(period.note);note.setMinLines(3);note.setGravity(Gravity.TOP);note.setEnabled(canConfirm);report.addView(note,new LinearLayout.LayoutParams(-1,-2));
        ScrollView content=new ScrollView(this);content.addView(report);
        AlertDialog dialog = ThemeStore.dialog(this).setTitle(T.s("总结卡 · ")+shown.cycleLabel)
                .setView(content).setNegativeButton(T.s("上一期"), (d, w) -> showCycle(schedule,offset + 1))
                .setPositiveButton(canConfirm ? T.s("完成总结") : T.s("关闭"), null).setNeutralButton(offset > 0 ? T.s("下一期") : T.s("本期"), null).create();
        dialog.setOnShowListener(d -> {
            Button next = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            next.setEnabled(offset > 0);
            next.setOnClickListener(v -> { dialog.dismiss(); showCycle(schedule,offset - 1); });
            if (canConfirm) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Button button = (Button) v;
                button.setEnabled(false);
                try {
                    completeWeeklyReport(shown.key,note.getText().toString());
                    dialog.dismiss();
                } catch (RuntimeException error) {
                    button.setEnabled(true);
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });
        dialog.show();
    }

    private static java.util.Calendar reportDate(String date){String[] p=date.split("-");java.util.Calendar out=java.util.Calendar.getInstance();out.clear();out.set(Integer.parseInt(p[0]),Integer.parseInt(p[1])-1,Integer.parseInt(p[2]));return out;}

    private void summaryArchive(){
        org.json.JSONArray records=CardStore.snapshot(this).summary(System.currentTimeMillis()).records();
        if(records.length()==0){Toast.makeText(this,T.s("暂无已完成总结"),Toast.LENGTH_SHORT).show();return;}
        List<org.json.JSONObject> rows=new ArrayList<>();for(int i=0;i<records.length();i++)rows.add(records.optJSONObject(i));
        java.util.Collections.sort(rows,(a,b)->Long.compare(b.optLong("at"),a.optLong("at")));
        String[] labels=new String[rows.size()];for(int i=0;i<labels.length;i++){org.json.JSONObject row=rows.get(i);java.util.Calendar last=reportDate(row.optString("end"));last.add(java.util.Calendar.DAY_OF_MONTH,-1);labels[i]=row.optString("label","总结")+" · "+row.optString("start")+T.s(" 至 ")+CycleSummary.day(last.getTimeInMillis());}
        ThemeStore.dialog(this).setTitle(T.s("已完成的总结")).setItems(labels,(d,w)->{String note=rows.get(w).optString("note");ThemeStore.dialog(this).setTitle(labels[w]).setMessage(note.isEmpty()?T.s("该周期总结已确认完成。"):note).setPositiveButton(T.s("关闭"),null).show();}).setNegativeButton(T.s("关闭"),null).show();
    }

    private void completeWeeklyReport(String key,String note) {
        if (!CardStore.completeSummary(this,key,note)) return;
        final android.content.Context context = getApplicationContext();
        new Thread(() -> { try { Guard.apply(context); } catch (RuntimeException ignored) { } }, "afs-summary-gate").start();
        Intent completed = new Intent(this, CheckinActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        completed.putExtra(CheckinActivity.EXTRA_LEARNING_COMPLETE, "summary:"+key);
        startActivity(completed);
        overridePendingTransition(0, 0);
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
        box.setGravity(Gravity.START);
        box.setBackground(round(SURFACE, dp(16)));
        box.setPadding(dp(10), dp(14), dp(10), dp(14));
        TextView b = new TextView(this);
        b.setText(big);
        b.setTextColor(TEXT);
        b.setTextSize(18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.START);
        box.addView(b);
        TextView s = new TextView(this);
        s.setText(small);
        s.setTextColor(TEXT3);
        s.setTextSize(11);
        s.setGravity(Gravity.START);
        s.setPadding(0, dp(4), 0, 0);
        box.addView(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) {
            lp.leftMargin = dp(8);
        }
        row.addView(box, lp);
    }

    /**
     * 未完成卡片日期：把每天记下的必做快照按卡片类型摊开。
     * 只统计真正记过状态的日期，旧日子没有快照就不出现——不拿今天的设置倒推历史。
     */
    private void incompleteCards(LinearLayout parent) {
        Map<String, List<String>> missing = DailyCardCompletion.missingDays(this);
        LinearLayout box = card(parent);
        head(box, T.s("未完成卡片日期"));
        int total = 0;
        for (String type : DailyCardCompletion.TYPES) {
            List<String> dates = missing.get(type);
            total += dates == null ? 0 : dates.size();
        }
        if (total == 0) {
            blank(box, T.s("还没有未完成记录：每天打开 App 时会记一次当天的必做卡状态。"));
            return;
        }
        for (String type : DailyCardCompletion.TYPES) {
            List<String> dates = missing.get(type);
            if (dates == null) dates = new ArrayList<>();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            box.addView(row);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(top);
            TextView name = new TextView(this);
            name.setText(type);
            name.setTextColor(TEXT);
            name.setTextSize(14);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView count = new TextView(this);
            count.setText(dates.isEmpty() ? T.s("无") : dates.size() + T.s(" 天"));
            count.setTextColor(dates.isEmpty() ? TEXT3 : RED);
            count.setTextSize(12);
            top.addView(count);

            final TextView list = new TextView(this);
            list.setTextColor(TEXT3);
            list.setTextSize(11);
            list.setPadding(0, dp(4), 0, 0);
            if (dates.isEmpty()) {
                // 空类型只留「无」，不再多占一行
                row.setPadding(0, dp(6), 0, dp(6));
                continue;
            }
            list.setText(datesText(dates, 6));
            row.addView(list);
            if (dates.size() > 6) {
                final List<String> all = dates;
                final boolean[] open = {false};
                row.setClickable(true);
                row.setContentDescription(type + T.s("未完成日期，点击展开"));
                row.setOnClickListener(v -> {
                    open[0] = !open[0];
                    list.setText(datesText(all, open[0] ? Integer.MAX_VALUE : 6));
                });
            }
        }
        TextView note = new TextView(this);
        note.setText(T.s("日期来自当天记下的必做卡状态（打开 App 或守卫轮询时写入）；同一天多种卡只算一次。"));
        note.setTextColor(TEXT3);
        note.setTextSize(11);
        note.setPadding(0, dp(6), 0, 0);
        box.addView(note);
    }

    /** 取最近 limit 个日期；超出时前面用省略号，点开时 limit 传够大就全列出。 */
    private String datesText(List<String> dates, int limit) {
        int from = Math.max(0, dates.size() - limit);
        StringBuilder text = new StringBuilder();
        if (from > 0) text.append("…  ");
        for (int i = from; i < dates.size(); i++) {
            if (i > from) text.append(T.s("、"));
            String date = dates.get(i);
            text.append(date.length() > 5 ? date.substring(5) : date);
        }
        return text.toString();
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
        if (!StudyStore.canUndoDate(r.date, StudyStore.today())) {
            Toast.makeText(this, T.s("只能撤回当天的打卡记录"), Toast.LENGTH_SHORT).show();
            recreate();
            return;
        }
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
        ThemeStore.dialog(this)
                .setTitle(T.s("撤销这笔打卡？"))
                .setMessage(name + "  +" + fmt(r.amount) + unit2 + "\n" + when
                        + T.s("\n\n删掉后进度和冻结闸门立刻重算。"))
                .setPositiveButton(T.s("撤销"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (!StudyStore.deleteRec(HistoryActivity.this, r.id)) {
                            Toast.makeText(HistoryActivity.this, T.s("记录已锁定或已不存在，只能撤回当天打卡"), Toast.LENGTH_SHORT).show();
                            recreate();
                            return;
                        }
                        Toast.makeText(HistoryActivity.this,
                                T.s("已撤销 ") + name + "  -" + fmt(r.amount) + unit2,
                                Toast.LENGTH_SHORT).show();
                        recreate();   // 重新统计整页
                    }
                })
                .setNegativeButton(T.s("算了"), null)
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
