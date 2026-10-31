package com.afs.guard;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 打卡日历：月视图 + 当天明细 + 打卡日记。
 *
 * 成就已经搬到设置的一级菜单（{@link AchievementActivity}），这里不再重复一份。
 *
 * 数据全部是读出来的：{@link StudyStore#recs} 提供记录，{@link CalendarData} 算格子，
 * 这一页自己不写任何打卡账本 —— 日历是"照镜子"，不是第二套规则。
 * 唯一会写的只有日记（{@link JournalStore}），而且写不写都不影响打卡。
 */
public final class CalendarActivity extends ThemedActivity {

    // 必须是完整词：「日」单字既被别处的日期格式当成虚词翻成空串，
    // 又在这里当星期标签，结果整个周日格子在英文模式下消失。
    private static final String[] WEEK = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private Ui ui;
    private ScrollView scroll;
    private LinearLayout body;

    private int year;
    private int month;

    private String todayKey = "";
    private int goalCount;
    private String loadError = "";
    private List<CalendarData.Span> spans = new ArrayList<>();
    private Map<String, CalendarData.Day> byDate = new HashMap<>();
    private Map<String, String> names = new LinkedHashMap<>();
    private CalendarData.Stats stats = new CalendarData.Stats();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        FloatingNav.fullscreen(this);
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        ui.page(scroll);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(18), ui.dp(24), ui.dp(18), ui.dp(40));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        todayKey = StudyStore.today();
        Calendar now = Calendar.getInstance();
        year = now.get(Calendar.YEAR);
        month = now.get(Calendar.MONTH) + 1;

        reload();
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        // 补记完一点东西再回来，日历得跟着变；重画一次比显示旧格子诚实。
        if (body != null) {
            reload();
            render();
        }
    }

    /* ==================== 数据 ==================== */

    private void reload() {
        loadError = "";
        spans = new ArrayList<>();
        names = new LinkedHashMap<>();
        try {
            for (StudyStore.G goal : StudyStore.goals(this)) {
                names.put(goal.id, goal.name);
            }
            goalCount = names.size();
            for (StudyStore.Rec rec : StudyStore.recs(this)) {
                spans.add(new CalendarData.Span(rec.date, rec.goalId, rec.amount));
            }
        } catch (RuntimeException error) {
            goalCount = 0;
            loadError = T.s("打卡记录读不出来：") + (error.getMessage() == null ? T.s("文件损坏") : error.getMessage());
        }
        byDate = CalendarData.days(spans, goalCount);
        stats = CalendarData.stats(byDate, todayKey);
    }

    /* ==================== 绘制 ==================== */

    private void render() {
        body.removeAllViews();
        body.addView(header());
        body.addView(ui.title(T.s("打卡日历")));
        body.addView(ui.sub(T.s("点某一天看明细，也能补写日记")));

        if (!loadError.isEmpty()) {
            TextView note = new TextView(this);
            note.setText(loadError);
            note.setTextSize(12);
            note.setTextColor(ui.red());
            note.setPadding(0, ui.dp(12), 0, ui.dp(4));
            body.addView(note);
        }

        body.addView(monthBar(), ui.gap(6));
        body.addView(weekRow());
        body.addView(gridCard(), ui.gap(14));
        body.addView(summaryCard(), ui.gap(14));
    }

    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, ui.dp(10));
        TextView back = new TextView(this);
        back.setText(T.s("‹  返回"));
        back.setTextSize(14);
        back.setTextColor(ui.accent());
        back.setPadding(0, ui.dp(6), ui.dp(12), ui.dp(6));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Calendar now = Calendar.getInstance();
        boolean current = now.get(Calendar.YEAR) == year && now.get(Calendar.MONTH) + 1 == month;
        if (!current) {
            TextView jump = new TextView(this);
            jump.setText(T.s("回到本月"));
            jump.setTextSize(13);
            jump.setTextColor(ui.accent());
            jump.setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6));
            jump.setOnClickListener(v -> {
                Calendar now2 = Calendar.getInstance();
                year = now2.get(Calendar.YEAR);
                month = now2.get(Calendar.MONTH) + 1;
                render();
            });
            bar.addView(jump);
        }
        return bar;
    }

    private View monthBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView prev = arrow("‹");
        prev.setOnClickListener(v -> shift(-1));
        bar.addView(prev);

        TextView label = new TextView(this);
        label.setText(monthLabel(year, month));
        label.setTextSize(17);
        label.setTextColor(ui.text());
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView next = arrow("›");
        next.setOnClickListener(v -> shift(1));
        bar.addView(next);
        return bar;
    }

    private TextView arrow(String glyph) {
        TextView view = new TextView(this);
        view.setText(glyph);
        view.setTextSize(22);
        view.setTextColor(ui.text2());
        view.setGravity(Gravity.CENTER);
        view.setBackground(ui.round(ui.surface(), 12f));
        view.setContentDescription("›".equals(glyph) ? T.s("下个月") : T.s("上个月"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(38));
        view.setLayoutParams(lp);
        return view;
    }

    private void shift(int delta) {
        month += delta;
        if (month < 1) {
            month = 12;
            year--;
        } else if (month > 12) {
            month = 1;
            year++;
        }
        render();
        if (scroll != null) scroll.scrollTo(0, 0);
    }

    /** 月份标题交给日期格式：中文「2026年10月」，英文「October 2026」。 */
    private String monthLabel(int year, int month) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, 1);
        return new java.text.SimpleDateFormat(T.s("yyyy年M月"), T.locale()).format(c.getTime());
    }

    private View weekRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, ui.dp(4), 0, ui.dp(4));
        for (String raw : WEEK) { String name = T.s(raw);
            TextView cell = new TextView(this);
            cell.setText(name);
            cell.setTextSize(11);
            cell.setTextColor(ui.text3());
            cell.setGravity(Gravity.CENTER);
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private View gridCard() {
        LinearLayout card = ui.card(18f, 10f);
        List<CalendarData.Day> cells = CalendarData.grid(year, month, byDate, todayKey);
        LinearLayout row = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % 7 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                card.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(cell(cells.get(i)), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return card;
    }

    private View cell(final CalendarData.Day day) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, ui.dp(5), 0, ui.dp(4));
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        outer.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
        box.setLayoutParams(outer);

        // 内层才是那个"圆块"，这样点按区域能比圆块大一圈，不难点
        LinearLayout disc = new LinearLayout(this);
        disc.setOrientation(LinearLayout.VERTICAL);
        disc.setGravity(Gravity.CENTER);
        disc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(40)));
        box.addView(disc);

        TextView number = new TextView(this);
        number.setTextSize(13);
        number.setGravity(Gravity.CENTER);
        number.setIncludeFontPadding(false);

        if (day.blank()) {
            disc.setBackground(null);
            number.setText("");
        } else {
            number.setText(String.valueOf(day.day));
            if (day.full) {
                disc.setBackground(ui.round(ui.accent(), 11f));
                number.setTextColor(ui.onAccent());
                number.setTypeface(Typeface.DEFAULT_BOLD);
            } else if (day.hits > 0) {
                disc.setBackground(ui.round(soft(ui.accent(), 0.18f), 11f));
                number.setTextColor(ui.accent());
                number.setTypeface(Typeface.DEFAULT_BOLD);
            } else if (day.today) {
                disc.setBackground(ui.round(0x00000000, 11f, ui.accent(), 1.4f));
                number.setTextColor(ui.text());
            } else {
                number.setTextColor(ui.text3());
            }
            final CalendarData.Day target = day;
            box.setOnClickListener(v -> showDay(target));
            if (day.hits > 0) {
                box.setContentDescription(day.key + T.s("，完成 ") + day.hits + T.s(" 项"));
            }
        }
        disc.addView(number);

        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        dots.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(7)));
        if (!day.blank()) {
            if (day.hits > 0 && !day.full) {
                dots.addView(dot(ui.accent()));
            }
            if (JournalStore.has(this, day.key)) {
                dots.addView(dot(ui.text3()));
            }
        }
        box.addView(dots);
        return box;
    }

    private View dot(int color) {
        View view = new View(this);
        view.setBackground(ui.round(color, 99f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(4), ui.dp(4));
        lp.setMargins(ui.dp(1), 0, ui.dp(1), 0);
        view.setLayoutParams(lp);
        return view;
    }

    /** 按比例压暗/提亮颜色，做浅底用；不引入新的颜色常量。 */
    private static int soft(int color, float strength) {
        int alpha = Math.round(255 * Math.max(0f, Math.min(1f, strength)));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private View summaryCard() {
        CalendarData.Stats monthStats = new CalendarData.Stats();
        CalendarData.addMonth(monthStats, CalendarData.grid(year, month, byDate, todayKey));

        LinearLayout card = ui.card();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(T.s("本月"));
        title.setTextSize(14);
        title.setTextColor(ui.text());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hint = new TextView(this);
        hint.setText(goalCount > 0 ? T.f("达成 %d 项算满勤", goalCount) : T.s("还没有目标"));
        hint.setTextSize(11);
        hint.setTextColor(ui.text3());
        titleRow.addView(hint);
        card.addView(titleRow, ui.gap(12));

        LinearLayout first = new LinearLayout(this);
        first.setOrientation(LinearLayout.HORIZONTAL);
        first.addView(stat(String.valueOf(monthStats.monthActiveDays), T.s("打卡天数")), weight());
        first.addView(stat(String.valueOf(monthStats.monthFullDays), T.s("满勤")), weight());
        first.addView(stat(String.valueOf(monthStats.monthHits), T.s("记录笔数")), weight());
        card.addView(first, ui.gap(12));

        LinearLayout second = new LinearLayout(this);
        second.setOrientation(LinearLayout.HORIZONTAL);
        second.addView(stat(String.valueOf(stats.currentStreak), T.s("当前连续")), weight());
        second.addView(stat(String.valueOf(stats.bestStreak), T.s("最长连续")), weight());
        second.addView(stat(String.valueOf(stats.activeDays), T.s("累计天数")), weight());
        card.addView(second);

        TextView foot = new TextView(this);
        // 不汇总"完成量"：不同目标的单位（分钟/页/个）根本不能相加，加出来是个假数字。
        String footText = stats.firstKey.isEmpty()
                ? T.s("还没有记录。第一次打卡之后，这里会开始长东西。")
                : T.s("从 ") + stats.firstKey + T.s(" 起，累计打卡 ") + stats.activeDays + T.s(" 天，最长连续 ")
                  + stats.bestStreak + T.s(" 天；本月日记 ") + JournalStore.countInMonth(this, year, month) + T.s(" 篇。");
        foot.setText(footText);
        foot.setTextSize(11);
        foot.setTextColor(ui.text3());
        foot.setPadding(0, ui.dp(12), 0, 0);
        card.addView(foot);
        return card;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View stat(String value, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView number = new TextView(this);
        number.setText(value);
        number.setTextSize(24);
        number.setTextColor(ui.text());
        number.setTypeface(Typeface.DEFAULT_BOLD);
        number.setGravity(Gravity.CENTER);
        number.setIncludeFontPadding(false);
        box.addView(number);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(11);
        name.setTextColor(ui.text3());
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, ui.dp(5), 0, 0);
        box.addView(name);
        return box;
    }

    /* ==================== 某一天 ==================== */

    private void showDay(final CalendarData.Day day) {
        if (day.blank()) {
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(18), ui.dp(6), ui.dp(18), 0);

        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        box.addView(lines);

        Map<String, Double> total = new LinkedHashMap<>();
        for (CalendarData.Span span : spans) {
            if (!day.key.equals(span.date)) {
                continue;
            }
            Double old = total.get(span.goalId);
            total.put(span.goalId, (old == null ? 0 : old) + span.amount);
        }

        if (total.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(T.s("这天没有打卡记录。"));
            empty.setTextSize(13);
            empty.setTextColor(ui.text3());
            empty.setPadding(0, ui.dp(4), 0, ui.dp(8));
            lines.addView(empty);
        } else {
            for (Map.Entry<String, Double> entry : total.entrySet()) {
                String name = names.get(entry.getKey());
                if (name == null) {
                    name = entry.getKey();
                }
                lines.addView(detailRow(name, "+" + trim(entry.getValue())));
            }
            TextView foot = new TextView(this);
            foot.setText(day.full ? T.s("满勤") : T.s("完成 ") + day.hits + (goalCount > 0 ? " / " + goalCount : "") + T.s(" 项"));
            foot.setTextSize(11);
            foot.setTextColor(day.full ? ui.green() : ui.text3());
            foot.setPadding(0, ui.dp(6), 0, ui.dp(4));
            lines.addView(foot);
        }

        final EditText editor;
        if (JournalStore.enabled(this)) {
            TextView label = new TextView(this);
            label.setText(T.s("日记"));
            label.setTextSize(12);
            label.setTextColor(ui.text3());
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setPadding(0, ui.dp(14), 0, ui.dp(6));
            box.addView(label);

            editor = new EditText(this);
            editor.setText(JournalStore.load(this, day.key));
            editor.setHint(T.s("今天做了什么，哪里卡住了。可以不写。"));
            editor.setHintTextColor(ui.text3());
            editor.setTextColor(ui.text());
            editor.setTextSize(13);
            editor.setGravity(Gravity.TOP | Gravity.START);
            editor.setMinLines(3);
            editor.setMaxLines(8);
            editor.setBackground(ui.round(ui.surface2(), 12f));
            editor.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
            editor.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            box.addView(editor, ui.gap(4));

            TextView limit = new TextView(this);
            limit.setText(T.s("最多 ") + JournalStore.LIMIT + T.s(" 字，存在本机，不上传"));
            limit.setTextSize(10);
            limit.setTextColor(ui.text3());
            box.addView(limit);
        } else {
            editor = null;
            TextView off = new TextView(this);
            off.setText(T.s("打卡日记已关闭。想在设置里打开，就在「打卡」一栏里。"));
            off.setTextSize(11);
            off.setTextColor(ui.text3());
            off.setPadding(0, ui.dp(14), 0, 0);
            box.addView(off);
        }

        ScrollView holder = new ScrollView(this);
        holder.addView(box);

        AlertDialog.Builder builder = ThemeStore.dialog(this)
                .setTitle(dateLabel(day.key))
                .setView(holder)
                .setNegativeButton(T.s("关闭"), null);
        if (editor != null) {
            builder.setPositiveButton(T.s("保存"), (dialog, which) -> {
                JournalStore.save(this, day.key, editor.getText().toString());
                toast(T.s("日记已保存"));
                render();
            });
            if (JournalStore.has(this, day.key)) {
                builder.setNeutralButton(T.s("清空"), (dialog, which) -> {
                    JournalStore.save(this, day.key, "");
                    toast(T.s("已清空"));
                    render();
                });
            }
        }
        builder.show();
    }

    private View detailRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(8), 0, ui.dp(8));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(13);
        name.setTextColor(ui.text());
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView amount = new TextView(this);
        amount.setText(value);
        amount.setTextSize(13);
        amount.setTextColor(ui.accent());
        row.addView(amount);
        return row;
    }

    private String dateLabel(String key) {
        if (key.length() != 10) {
            return key;
        }
        try {
            int y = Integer.parseInt(key.substring(0, 4));
            int m = Integer.parseInt(key.substring(5, 7));
            int d = Integer.parseInt(key.substring(8, 10));
            return key + " · " + T.s(WEEK[CalendarData.weekdayIndex(y, m, d)]);
        } catch (RuntimeException error) {
            return key;
        }
    }

    /** 3.0 → "3"，3.5 → "3.5"；只在显示层用，不改数据。 */
    private static String trim(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((long) Math.rint(value));
        }
        String text = String.valueOf(Math.round(value * 10) / 10.0);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
