package com.afs.guard;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 额度用尽时的「请先离开」页。
 *
 * 冻结（setPackagesSuspended）只挡下一次启动，已经停在前台的那只应用照样能用 ——
 * 那时候图标已经灰了，人还在刷。设备所有者不受后台启动 Activity 的限制，
 * 所以额度一到点就把这一页拉到最前，当场结束这次使用，不必等用户退出去。
 */
public final class QuotaBlockedActivity extends ThemedActivity {
    static final String EXTRA_PACKAGE = "extra_package";
    private Ui ui;
    private String pkg;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        pkg = getIntent().getStringExtra(EXTRA_PACKAGE);
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String next = intent.getStringExtra(EXTRA_PACKAGE);
        if (next != null && !next.equals(pkg)) { pkg = next; render(); }
    }

    @Override protected void onResume() {
        super.onResume();
        // 额度恢复（改了额度 / 换了天）就自己退场，不挡路。
        int reason = pkg == null ? 0 : Guard.blockReasonNow(this, pkg);
        if (reason != 4 && reason != 5) finish();
    }

    private void render() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(ui.dp(26), ui.dp(26), ui.dp(26), ui.dp(26));
        ui.page(page);

        LinearLayout card = ui.card(22f, 22f);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView seal = new TextView(this);
        seal.setText(T.s("限"));
        seal.setTextSize(28);
        seal.setTextColor(0xFFFFFFFF);
        seal.setGravity(Gravity.CENTER);
        seal.setBackground(ui.round(ui.red(), 18f));
        card.addView(seal, new LinearLayout.LayoutParams(ui.dp(62), ui.dp(62)));

        TextView title = new TextView(this);
        title.setText(T.s("今天的额度用完了"));
        title.setTextSize(24);
        title.setTextColor(ui.text());
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = ui.dp(18);
        card.addView(title, titleLp);

        if (pkg != null) {
            TextView name = new TextView(this);
            name.setText(Guard.labelOf(getPackageManager(), pkg));
            name.setTextSize(15);
            name.setTextColor(ui.text2());
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameLp.topMargin = ui.dp(8);
            card.addView(name, nameLp);

            TextView status = new TextView(this);
            status.setText(quotaLine());
            status.setTextSize(13);
            status.setTextColor(ui.text3());
            status.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            statusLp.topMargin = ui.dp(10);
            card.addView(status, statusLp);
        }

        TextView note = new TextView(this);
        note.setText(DayBoundary.label(this) + T.s("重置。现在先回到桌面，下次再说。"));
        note.setTextSize(12);
        note.setTextColor(ui.text3());
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = ui.dp(10);
        card.addView(note, noteLp);

        TextView action = new TextView(this);
        action.setText(T.s("回到桌面"));
        action.setTextSize(15);
        action.setTextColor(ui.onAccent());
        action.setGravity(Gravity.CENTER);
        action.setBackground(ui.round(ui.accent(), ui.button(16f)));
        action.setOnClickListener(v -> home());
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48));
        actionLp.topMargin = ui.dp(20);
        card.addView(action, actionLp);

        page.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(page);
    }

    private String quotaLine() {
        try {
            int reason = Guard.blockReasonNow(this, pkg);
            String line = reason == 5 ? DailyQuotaStore.status(this, pkg)
                    : AppUsageQuota.status(this, pkg, RuleStore.get(this, pkg), System.currentTimeMillis());
            return line == null || line.isEmpty() ? T.s("额度已用尽") : line;
        } catch (RuntimeException error) {
            return T.s("额度已用尽");
        }
    }

    private void home() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
        finish();
    }

    @Override public void onBackPressed() {
        home();
    }
}
