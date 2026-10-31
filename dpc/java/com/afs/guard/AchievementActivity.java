package com.afs.guard;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 成就页（设置 → 成就）。
 *
 * 成就全部从打卡历史现算，所以这一页没有「领取」「重置」这类按钮 ——
 * 数据一变，这里立刻跟着变，不存在领了之后数据又被删掉的矛盾。
 */
public final class AchievementActivity extends ThemedActivity {

    private Ui ui;
    private LinearLayout body;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        FloatingNav.fullscreen(this);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (body != null) render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        ui.page(scroll);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(36));
        scroll.addView(body);

        body.addView(header());
        body.addView(ui.title(Lang.t(this, "成就", "Achievements")));

        List<Achievement.Item> items = AchievementCenter.items(this);
        int open = Achievement.unlocked(items);
        body.addView(hero(open, items.size()), ui.gap(14));

        LinearLayout list = ui.card();
        body.addView(list, ui.gap(12));
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) list.addView(ui.divider());
            list.addView(row(items.get(i)));
        }

        TextView foot = ui.sub(Lang.t(this,
                "成就从打卡历史里现算：删掉记录，对应的成就也会跟着退回去。没有「领取」这一步。",
                "Achievements are derived from check-in history, so removing records rolls them back. There is nothing to claim."));
        foot.setTextSize(11);
        foot.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        body.addView(foot);

        setContentView(scroll);
    }

    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, ui.dp(14));
        TextView back = new TextView(this);
        back.setText("‹  " + Lang.t(this, "返回", "Back"));
        back.setTextSize(14);
        back.setTextColor(ui.accent());
        back.setPadding(0, ui.dp(6), ui.dp(12), ui.dp(6));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    /** 顶部总览：解锁进度 + 一条粗进度条。 */
    private View hero(int open, int total) {
        LinearLayout card = ui.card(20f, 18f);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView number = new TextView(this);
        number.setText(String.valueOf(open));
        number.setTextSize(34);
        number.setTypeface(Typeface.DEFAULT_BOLD);
        number.setTextColor(ui.accent());
        number.setIncludeFontPadding(false);
        line.addView(number);

        TextView rest = new TextView(this);
        rest.setText(" / " + total + T.s(" 已解锁"));
        rest.setTextSize(13);
        rest.setTextColor(ui.text2());
        rest.setPadding(ui.dp(6), ui.dp(10), 0, 0);
        line.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(line);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(ui.round(ui.surface2(), 99f));
        bar.setClipToOutline(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8));
        blp.topMargin = ui.dp(12);
        if (open > 0) {
            View fill = new View(this);
            fill.setBackground(ui.round(ui.accent(), 99f));
            bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, open)));
        }
        if (open < total) {
            View rest2 = new View(this);
            rest2.setBackgroundColor(0x00000000);
            bar.addView(rest2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, total - open)));
        }
        card.addView(bar, blp);
        return card;
    }

    /** 一行：图标 + 名字 + 条件 + 进度。 */
    private View row(Achievement.Item item) {
        final boolean open = item.unlocked();
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, ui.dp(10), 0, ui.dp(10));

        TextView glyph = new TextView(this);
        glyph.setText(item.glyph);
        glyph.setTextSize(15);
        glyph.setGravity(Gravity.CENTER);
        glyph.setTextColor(open ? ui.onAccent() : ui.text3());
        glyph.setBackground(ui.round(open ? ui.accent() : ui.surface2(), 99f));
        line.addView(glyph, new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setPadding(ui.dp(12), 0, ui.dp(10), 0);

        TextView name = new TextView(this);
        name.setText(item.name);
        name.setTextSize(14);
        name.setTextColor(open ? ui.text() : ui.text2());
        name.setTypeface(open ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        middle.addView(name);

        TextView note = new TextView(this);
        note.setText(item.note);
        note.setTextSize(11);
        note.setTextColor(ui.text3());
        note.setPadding(0, ui.dp(2), 0, 0);
        middle.addView(note);
        line.addView(middle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ratio = new TextView(this);
        ratio.setText(open ? T.s("已解锁") : item.ratio());
        ratio.setTextSize(12);
        ratio.setTextColor(open ? ui.green() : ui.text3());
        ratio.setTypeface(open ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        line.addView(ratio);

        line.setOnClickListener(v -> ThemeStore.dialog(this)
                .setTitle(item.name)
                .setMessage(item.note + "\n\n" + (open
                        ? T.s("已解锁。")
                        : T.s("进度 ") + item.ratio() + T.s("，还差 ") + (item.target - item.progress)))
                .setPositiveButton(T.s("知道了"), null)
                .show());
        return line;
    }
}
