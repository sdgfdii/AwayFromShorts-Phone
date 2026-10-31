package com.afs.guard;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 关键词命中后的遮罩。
 *
 * 只做两件事：把刚才那一屏盖住、把人送回桌面。
 * 不做「继续看」按钮 —— 有那个按钮，拦截就成了一次点击确认。
 */
public final class KeywordBlockActivity extends ThemedActivity {

    public static final String EXTRA_PACKAGE = "pkg";
    public static final String EXTRA_KEYWORD = "keyword";
    /** true = 命中的是「应用内页面拦截」规则，不是关键词。 */
    public static final String EXTRA_PAGE = "page";

    /** 15 秒没人动就自己回桌面，免得手机亮着挂在这一页耗电。 */
    private static final long AUTO_HOME_MS = 15000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean left;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui ui = new Ui(this);
        Intent intent = getIntent();
        String pkg = intent == null ? "" : intent.getStringExtra(EXTRA_PACKAGE);
        String keyword = intent == null ? "" : intent.getStringExtra(EXTRA_KEYWORD);
        boolean page = intent != null && intent.getBooleanExtra(EXTRA_PAGE, false);
        if (pkg == null) pkg = "";
        if (keyword == null) keyword = "";
        String app = pkg.isEmpty() ? T.s("这个应用") : Guard.labelOf(getPackageManager(), pkg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        ui.page(root);
        root.setPadding(ui.dp(26), ui.dp(48), ui.dp(26), ui.dp(30));

        TextView glyph = ui.circle("⊘", Ui.TILE_RUST, 76f, 34);
        ThemeStore.excludeFont(glyph);
        root.addView(glyph, new LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)));

        TextView title = new TextView(this);
        title.setText(page ? T.s("这个页面不看了") : T.s("这一屏被拦下了"));
        title.setTextSize(20);
        title.setTextColor(ui.text());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = ui.dp(20);
        root.addView(title, tlp);

        TextView reason = new TextView(this);
        reason.setText(page ? T.f("在「%s」里挡住了「%s」", app, keyword) : T.f("在「%s」里看到「%s」", app, keyword));
        reason.setTextSize(14);
        reason.setTextColor(ui.text2());
        reason.setGravity(Gravity.CENTER);
        reason.setPadding(0, ui.dp(8), 0, 0);
        root.addView(reason);

        TextView count = new TextView(this);
        count.setText(page
                ? T.f("今天第 %d 次", PageRuleStore.hits(this))
                : T.f("今天第 %d 次", KeywordStore.hits(this)));
        count.setTextSize(12);
        count.setTextColor(ui.text3());
        count.setGravity(Gravity.CENTER);
        count.setPadding(0, ui.dp(6), 0, 0);
        root.addView(count);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView leave = new TextView(this);
        leave.setText(T.s("回桌面"));
        leave.setTextSize(15);
        leave.setTextColor(ui.onAccent());
        leave.setGravity(Gravity.CENTER);
        leave.setTypeface(Typeface.DEFAULT_BOLD);
        leave.setBackground(ui.round(ui.accent(), ui.button(22)));
        leave.setPadding(0, ui.dp(15), 0, ui.dp(15));
        leave.setOnClickListener(v -> goHome());
        root.addView(leave, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText(T.s("屏幕内容只在本机比对，没有上传。"));
        note.setTextSize(11);
        note.setTextColor(ui.text3());
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, ui.dp(12), 0, 0);
        root.addView(note);

        setContentView(root);
        handler.postDelayed(this::goHome, AUTO_HOME_MS);
    }

    private void goHome() {
        if (left) return;
        left = true;
        handler.removeCallbacksAndMessages(null);
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
        finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
