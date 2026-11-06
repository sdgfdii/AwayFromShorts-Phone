package com.afs.guard;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.afs.host.plugins.HostPluginRouter;
import com.afs.host.plugins.HostRuntime;
import com.afs.host.plugins.PluginEntry;
import com.afs.host.plugins.PluginManagerActivity;
import com.afs.host.plugins.PluginManifestParser;
import com.afs.host.usage.HostUsageStats;
import com.afs.pluginapi.PluginProtocol;

/**
 * Compatibility router only. The old rendering lives in LegacyStatsActivity
 * and is reachable only through the whole-migration build switch; disabling a
 * single plugin never opens it.
 */
public class StatsActivity extends ThemedActivity {
    private static final int REQUEST_PLUGIN = 71;

    private Ui ui;
    private LinearLayout body;
    private boolean started;
    private boolean routing;
    private boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (BuildConfig.PLUGIN_STATS_LEGACY) {
            startActivity(new Intent(this, LegacyStatsActivity.class));
            finish();
            return;
        }
        ui = new Ui(this);
        ScrollView scroll = new ScrollView(this);
        ui.page(scroll);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32));
        scroll.addView(body);
        setContentView(scroll);
        renderLoading();
        route();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!started && !destroyed && ui != null) route();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PLUGIN) finish();
    }

    private void route() {
        if (routing || started) return;
        routing = true;
        HostRuntime.get(this).registry().refreshAsync(() -> {
            routing = false;
            if (destroyed) return;
            PluginEntry entry = HostRuntime.get(this).registry()
                    .findEnabledPage(PluginProtocol.SLOT_HISTORY_MORE, "screen");
            if (entry == null) {
                renderMissing();
                return;
            }
            started = true;
            HostPluginRouter.openPage(this, entry, "screen", REQUEST_PLUGIN, this::renderFailure);
        });
    }

    private void renderLoading() {
        body.removeAllViews();
        body.addView(ui.title(T.s("屏幕使用统计")));
        TextView note = new TextView(this);
        note.setText(T.s("正在打开统计插件…"));
        note.setTextSize(13);
        note.setTextColor(ui.text2());
        note.setPadding(0, ui.dp(8), 0, 0);
        body.addView(note);
    }

    private void renderMissing() {
        started = false;
        if (destroyed) return;
        body.removeAllViews();
        body.addView(ui.title(T.s("屏幕使用统计")));
        LinearLayout card = ui.card();
        body.addView(card, ui.gap(10));
        TextView note = new TextView(this);
        note.setText(T.s("统计插件未安装或未启用。\n\n它现在是独立 APK，需要你在「插件管理」里确认数据范围并启用；停用单个插件不会回到旧统计页。"));
        note.setTextSize(13);
        note.setTextColor(ui.text2());
        note.setLineSpacing(ui.dp(4), 1f);
        card.addView(note);
        Button manage = new Button(this);
        manage.setText(T.s("打开插件管理"));
        manage.setAllCaps(false);
        manage.setTextColor(0xFFFFFFFF);
        manage.setBackgroundColor(ui.accent());
        manage.setOnClickListener(v -> startActivity(new Intent(this, PluginManagerActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, ui.dp(44));
        lp.topMargin = ui.dp(10);
        card.addView(manage, lp);
        Button back = new Button(this);
        back.setText(T.s("返回"));
        back.setAllCaps(false);
        back.setTextColor(ui.text2());
        back.setOnClickListener(v -> finish());
        body.addView(back, new LinearLayout.LayoutParams(-1, ui.dp(42)));
    }

    private void renderFailure(String message) {
        started = false;
        if (destroyed) return;
        body.removeAllViews();
        body.addView(ui.title(T.s("屏幕使用统计")));
        LinearLayout card = ui.card();
        body.addView(card, ui.gap(10));
        TextView note = new TextView(this);
        note.setText(T.s("统计插件启动失败：") + message + "\n\n"
                + T.s("可以直接返回，或在插件管理中刷新后重试。"));
        note.setTextSize(13);
        note.setTextColor(ui.red());
        note.setLineSpacing(ui.dp(4), 1f);
        card.addView(note);
        Button manage = new Button(this);
        manage.setText(T.s("打开插件管理"));
        manage.setAllCaps(false);
        manage.setTextColor(ui.text());
        manage.setBackgroundColor(ui.line());
        manage.setOnClickListener(v -> startActivity(new Intent(this, PluginManagerActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, ui.dp(44));
        lp.topMargin = ui.dp(10);
        card.addView(manage, lp);
        Button back = new Button(this);
        back.setText(T.s("返回"));
        back.setAllCaps(false);
        back.setTextColor(ui.text2());
        back.setOnClickListener(v -> finish());
        body.addView(back, new LinearLayout.LayoutParams(-1, ui.dp(42)));
    }

    /** Existing callers (Hub) keep this read-only AppOps check. */
    static boolean hasUsageAccess(android.content.Context context) {
        return HostUsageStats.hasUsageAccess(context);
    }
}
