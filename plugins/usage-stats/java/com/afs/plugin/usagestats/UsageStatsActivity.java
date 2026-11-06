package com.afs.plugin.usagestats;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.afs.pluginapi.DisplayUsage;
import com.afs.pluginapi.LaunchTicket;
import com.afs.pluginapi.PageLease;
import com.afs.pluginapi.PcPeriodSummary;
import com.afs.pluginapi.PluginClient;
import com.afs.pluginapi.PluginNavigation;
import com.afs.pluginapi.PluginProtocol;
import com.afs.pluginapi.PluginStatus;
import com.afs.pluginapi.TrendDay;
import com.afs.pluginapi.UsageSummary;

import java.util.List;
import java.util.Locale;

/**
 * The independent stats page. It owns its Views and reads only the bounded
 * UsageSummary DTO from the host; it never references a host class, file,
 * store or system usage permission.
 */
public final class UsageStatsActivity extends Activity {
    private static final int REQUEST_ACCESS = 91;

    private static Holder holder;

    private Context uiContext;
    private ScrollView scroll;
    private LinearLayout body;
    private int bg, surface, text, text2, track, accent, red, green;
    private boolean dark;

    private PluginClient client;
    private PageLease lease;
    private LaunchTicket ticket;
    private long requestCounter;
    private long currentRequest;
    private boolean loading;
    private boolean resumed;
    private boolean accessDenied;
    private boolean destroyed;
    private boolean opened;
    private UsageSummary lastSummary;
    private int viewState;
    private String currentMessage="";
    private boolean messageRetry;

    private static final class Holder {
        String ticketId;
        PageLease lease;
        PluginClient client;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        readPresentation();
        Intent intent = getIntent();
        ticket = intent == null ? null : (LaunchTicket) intent.getParcelableExtra(PluginProtocol.EXTRA_TICKET);
        build();
        if (ticket == null || !getPackageName().equals(ticket.packageName)
                || !"usage-stats".equals(ticket.pluginId)
                || !"screen".equals(ticket.pageId)
                || ticket.protocol != PluginProtocol.PROTOCOL) {
            showMessage(s(R.string.no_ticket), false);
            return;
        }
        if (holder != null && ticket.ticketId.equals(holder.ticketId)) {
            client = holder.client;
            lease = holder.lease;
            opened = true;
            if (lease != null) load();
            else showMessage(s(R.string.expired), false);
            return;
        }
        openTicket();
    }

    private void readPresentation() {
        Intent intent = getIntent();
        bg = color(intent, PluginProtocol.EXTRA_THEME_BG, 0xFF101216);
        surface = color(intent, PluginProtocol.EXTRA_THEME_SURFACE, 0xFF1A1D23);
        text = color(intent, PluginProtocol.EXTRA_THEME_TEXT, 0xFFF2F3F5);
        text2 = color(intent, PluginProtocol.EXTRA_THEME_TEXT2, 0xFFB8BCC5);
        track = color(intent, PluginProtocol.EXTRA_THEME_TRACK, 0xFF2A2E36);
        accent = color(intent, PluginProtocol.EXTRA_THEME_ACCENT, 0xFF9B7BE8);
        red = color(intent, PluginProtocol.EXTRA_THEME_RED, 0xFFE45B5B);
        green = color(intent, PluginProtocol.EXTRA_THEME_GREEN, 0xFF37A56F);
        dark = intent != null && intent.getBooleanExtra(PluginProtocol.EXTRA_DARK, true);
        if(intent!=null&&intent.getBooleanExtra(PluginProtocol.EXTRA_FOLLOW_SYSTEM,false))
            dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        int[] colors=intent==null?null:intent.getIntArrayExtra(dark?PluginProtocol.EXTRA_DARK_COLORS:PluginProtocol.EXTRA_LIGHT_COLORS);
        if(colors!=null&&colors.length==8){bg=colors[0];surface=colors[1];text=colors[2];text2=colors[3];track=colors[4];accent=colors[5];red=colors[6];green=colors[7];}
        String localeTag = intent == null ? "" : intent.getStringExtra(PluginProtocol.EXTRA_LOCALE);
        float fontScale = intent == null ? 1f : intent.getFloatExtra(PluginProtocol.EXTRA_FONT_SCALE, 1f);
        Configuration configuration = new Configuration(getResources().getConfiguration());
        if (localeTag != null && !localeTag.isEmpty()) configuration.setLocale(Locale.forLanguageTag(localeTag));
        if (fontScale > 0f && intent!=null && intent.getBooleanExtra(PluginProtocol.EXTRA_CUSTOM_FONT_SCALE,true)) configuration.fontScale = Math.min(3f, fontScale);
        uiContext = createConfigurationContext(configuration);

    }

    private static int color(Intent intent, String key, int fallback) {
        return intent == null ? fallback : intent.getIntExtra(key, fallback);
    }

    private void build() {
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);
        try {
            getWindow().setStatusBarColor(bg);
            getWindow().setNavigationBarColor(bg);
            int systemUi=getWindow().getDecorView().getSystemUiVisibility();
            systemUi=dark?systemUi&~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR:systemUi|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if(android.os.Build.VERSION.SDK_INT>=26)systemUi=dark?systemUi&~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:systemUi|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            // API 24/25 have no dark navigation icons; retain an opaque dark bar.
            else if(!dark)getWindow().setNavigationBarColor(0xFF101216);
            getWindow().getDecorView().setSystemUiVisibility(systemUi);
        } catch (RuntimeException ignored) { }

        TextView back = new TextView(uiContext);
        back.setText(s(R.string.back));
        back.setTextSize(14);
        back.setTextColor(accent);
        back.setPadding(0, dp(6), 0, dp(12));
        back.setOnClickListener(v -> finish());
        body.addView(back);

        TextView title = new TextView(uiContext);
        title.setText(s(R.string.title));
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(text);
        body.addView(title);

        TextView subtitle = new TextView(uiContext);
        subtitle.setText(s(R.string.subtitle));
        subtitle.setTextSize(13);
        subtitle.setTextColor(text2);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        body.addView(subtitle);
    }

    private void openTicket() {
        client = new PluginClient(this, BuildFlags.HOST_TRUST_SHA256, BuildFlags.TEST_MODE);
        showMessage(s(R.string.loading), false);
        client.open(ticket, (status, pageLease, message) -> {
            if (destroyed) return;
            if (status == PluginStatus.OK && pageLease != null) {
                lease = pageLease;
                opened = true;
                holder = new Holder();
                holder.ticketId = ticket.ticketId;
                holder.lease = lease;
                holder.client = client;
                if(resumed)load();
            } else {
                showMessage(messageFor(status, message), status == PluginStatus.DISABLED
                        || status == PluginStatus.EXPIRED || status == PluginStatus.REVOKED);
            }
        });
    }

    private void load() {
        if (lease == null || client == null || destroyed || !resumed || accessDenied) return;
        if (loading) client.cancel(lease, currentRequest);
        final long request = ++requestCounter;
        currentRequest = request;
        loading = true;
        showMessage(s(R.string.loading), false);
        client.load(lease, request, (status, summary, message) -> {
            if (destroyed || request != currentRequest) return;
            loading = false;
            if (status == PluginStatus.OK && summary != null) {
                renderSummary(summary);
            } else if (status == PluginStatus.PERMISSION_REQUIRED) {
                showPermission();
            } else {
                showMessage(messageFor(status, message), status==PluginStatus.BUSY||status==PluginStatus.TIMEOUT||status==PluginStatus.UNAVAILABLE||status==PluginStatus.FAILED);
            }
        });
    }

    private String messageFor(int status, String hostMessage) {
        if (hostMessage != null && !hostMessage.isEmpty()) return hostMessage;
        switch (status) {
            case PluginStatus.PERMISSION_REQUIRED: return s(R.string.perm_title);
            case PluginStatus.NO_GRANT: return s(R.string.no_grant);
            case PluginStatus.DISABLED: return s(R.string.disabled);
            case PluginStatus.EXPIRED: return s(R.string.expired);
            case PluginStatus.BUSY: return s(R.string.busy);
            case PluginStatus.TIMEOUT: return s(R.string.timeout);
            case PluginStatus.UNAVAILABLE: return s(R.string.unavailable);
            case PluginStatus.REVOKED: return s(R.string.disabled);
            default: return s(R.string.failed);
        }
    }

    private void showMessage(String message, boolean retry) {
        viewState=3;currentMessage=message;messageRetry=retry;
        body.removeViews(3, Math.max(0, body.getChildCount() - 3));
        LinearLayout card = card();
        TextView view = new TextView(uiContext);
        view.setText(message);
        view.setTextSize(13);
        view.setTextColor(text2);
        view.setLineSpacing(dp(4), 1f);
        card.addView(view);
        if (retry && lease != null) {
            TextView action = new TextView(uiContext);
            action.setText(s(R.string.retry));
            action.setTextSize(14);
            action.setTextColor(accent);
            action.setGravity(Gravity.CENTER);
            action.setPadding(0, dp(12), 0, dp(6));
            action.setOnClickListener(v -> load());
            card.addView(action);
        }
        body.addView(card, gap());
    }

    private void showPermission() {
        viewState=2;
        body.removeViews(3, Math.max(0, body.getChildCount() - 3));
        LinearLayout card = card();
        head(card, s(R.string.perm_title));
        TextView note = new TextView(uiContext);
        note.setText(s(R.string.perm_message));
        note.setTextSize(13);
        note.setTextColor(text2);
        note.setLineSpacing(dp(4), 1f);
        card.addView(note);
        TextView action = new TextView(uiContext);
        action.setText(s(R.string.perm_button));
        action.setTextSize(14);
        action.setTextColor(0xFFFFFFFF);
        action.setGravity(Gravity.CENTER);
        action.setBackgroundColor(accent);
        action.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        action.setLayoutParams(lp);
        action.setOnClickListener(v -> {
            if (lease == null || !PluginNavigation.openUsageAccessForResult(this, lease, REQUEST_ACCESS)) {
                showMessage(s(R.string.perm_unavailable), true);
            }
        });
        card.addView(action);
        body.addView(card, gap());
    }

    private void renderSummary(UsageSummary summary) {
        viewState=1;lastSummary=summary;
        body.removeViews(3, Math.max(0, body.getChildCount() - 3));
        if (summary.tooShort) {
            LinearLayout card = card();
            TextView note = new TextView(uiContext);
            note.setText(s(R.string.too_short));
            note.setTextSize(13);
            note.setTextColor(text2);
            card.addView(note);
            body.addView(card, gap());
        }
        body.addView(rankCard(summary.phoneTop10), gap());
        body.addView(gameCard(summary), gap());
        body.addView(trendCard(summary), gap());
        body.addView(pcCard(summary), gap());
    }

    private View rankCard(List<DisplayUsage> rows) {
        LinearLayout card = card();
        head(card, s(R.string.month_rank));
        if (rows == null || rows.isEmpty()) {
            note(card, s(R.string.rank_empty));
            return card;
        }
        long max = 1L;
        for (DisplayUsage row : rows) max = Math.max(max, row.foregroundMs);
        int count = Math.min(10, rows.size());
        for (int i = 0; i < count; i++) {
            DisplayUsage row = rows.get(i);
            rankBar(card, (i + 1) + ". " + row.displayName, fmt(row.foregroundMs),
                    row.foregroundMs / (float) max, accent);
        }
        return card;
    }

    private View gameCard(UsageSummary summary) {
        LinearLayout card = card();
        head(card, s(R.string.games));
        gameBar(card, s(R.string.month_total), summary.monthGameMs);
        gameBar(card, s(R.string.today), summary.todayGameMs);
        if (summary.monthGameMs <= 0) note(card, s(R.string.no_game));
        return card;
    }

    private View trendCard(UsageSummary summary) {
        LinearLayout card = card();
        head(card, s(R.string.trend));
        TextView definition = note(card, s(R.string.trend_note));
        definition.setPadding(0, 0, 0, dp(6));
        int days = 0;
        long sum = 0;
        List<TrendDay> trend = summary.trend7;
        for (TrendDay day : trend) {
            if (day.totalMs > 60_000L) {
                days++;
                sum += day.percent();
            }
            trendBar(card, day);
        }
        if (days > 0) {
            TextView average = new TextView(uiContext);
            average.setText(s(R.string.avg, (int) (sum / days)));
            average.setTextSize(13);
            average.setTypeface(Typeface.DEFAULT_BOLD);
            average.setTextColor((sum / days) >= 30 ? red : green);
            average.setPadding(0, dp(8), 0, 0);
            card.addView(average);
        }
        return card;
    }

    private View pcCard(UsageSummary summary) {
        LinearLayout card = card();
        head(card, s(R.string.pc_title));
        if (summary.pcTotal.empty) {
            note(card, s(R.string.pc_empty));
            return card;
        }
        note(card, s(R.string.pc_updated,
                summary.pcSince.isEmpty() ? "-" : summary.pcSince,
                summary.pcUpdatedAt.isEmpty() ? "-" : summary.pcUpdatedAt));

        final LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(-1, -2);
        tabLp.topMargin = dp(10);
        tabLp.bottomMargin = dp(10);
        card.addView(tabs, tabLp);
        card.addView(detail);

        final PcPeriodSummary[] periods = {summary.pcDay, summary.pcMonth, summary.pcTotal};
        final String[] labels = {s(R.string.pc_day), s(R.string.pc_month), s(R.string.pc_total)};
        final TextView[] buttons = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int selected = i;
            TextView tab = new TextView(uiContext);
            tab.setText(labels[i] + "\n" + fmt(periods[i].totalMs));
            tab.setTextSize(13);
            tab.setGravity(Gravity.CENTER);
            tab.setTypeface(Typeface.DEFAULT_BOLD);
            tab.setPadding(dp(4), dp(10), dp(4), dp(10));
            tab.setTextColor(i == 0 ? accent : text2);
            buttons[i] = tab;
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));
            tab.setOnClickListener(v -> {
                for (int j = 0; j < buttons.length; j++) {
                    buttons[j].setTextColor(j == selected ? accent : text2);
                }
                detail.removeAllViews();
                pcRanking(detail, periods[selected], labels[selected]);
            });
        }
        pcRanking(detail, periods[0], labels[0]);
        return card;
    }

    private void pcRanking(LinearLayout parent, PcPeriodSummary period, String label) {
        TextView heading = new TextView(uiContext);
        heading.setText(s(R.string.pc_period_rank, label));
        heading.setTextSize(15);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(text);
        heading.setPadding(0, 0, 0, dp(8));
        parent.addView(heading);
        if (period.top10.isEmpty()) {
            note(parent, s(R.string.pc_empty));
            return;
        }
        long max = 1L;
        for (DisplayUsage row : period.top10) max = Math.max(max, row.foregroundMs);
        int count = Math.min(10, period.top10.size());
        for (int i = 0; i < count; i++) {
            DisplayUsage row = period.top10.get(i);
            rankBar(parent, (i + 1) + ". " + row.displayName, fmt(row.foregroundMs),
                    row.foregroundMs / (float) max, 0xFF9B7BE8);
        }
    }

    private void rankBar(LinearLayout card, String name, String duration, float fraction, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = new TextView(uiContext);
        label.setText(name);
        label.setTextSize(13);
        label.setTextColor(text);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView time = new TextView(uiContext);
        time.setText(duration);
        time.setTextSize(13);
        time.setTextColor(text2);
        row.addView(time);
        card.addView(row);
        bar(card, Math.max(0.02f, Math.min(1f, fraction)), color);
    }

    private void trendBar(LinearLayout card, TrendDay day) {
        int percent = day.percent();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(uiContext);
        label.setText(day.date);
        label.setTextSize(12);
        label.setTextColor(text2);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-2, -2);
        labelLp.rightMargin = dp(8);
        row.addView(label, labelLp);
        TextView value = new TextView(uiContext);
        value.setText(percent + "%");
        value.setTextSize(12);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        int tone = percent >= 30 ? red : green;
        value.setTextColor(tone);
        row.addView(value);
        card.addView(row);

        LinearLayout trackView = new LinearLayout(this);
        trackView.setOrientation(LinearLayout.HORIZONTAL);
        View fill = new View(this);
        fill.setBackgroundColor(tone);
        trackView.addView(fill, new LinearLayout.LayoutParams(0, dp(5), percent));
        View rest = new View(this);
        rest.setBackgroundColor(track);
        trackView.addView(rest, new LinearLayout.LayoutParams(0, dp(5), Math.max(0, 100 - percent)));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(-1, dp(5));
        trackLp.setMargins(0, dp(3), 0, dp(6));
        card.addView(trackView, trackLp);
        if (day.totalMs <= 60_000L) note(card, s(R.string.trend_tiny));
    }

    private void gameBar(LinearLayout card, String label, long ms) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = new TextView(uiContext);
        name.setText(label);
        name.setTextSize(13);
        name.setTextColor(text);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView value = new TextView(uiContext);
        value.setText(fmt(ms));
        value.setTextSize(13);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setTextColor(ms > 0 ? red : text2);
        row.addView(value);
        card.addView(row);
    }

    private void bar(LinearLayout card, float fraction, int color) {
        LinearLayout trackView = new LinearLayout(this);
        trackView.setOrientation(LinearLayout.HORIZONTAL);
        View fill = new View(this);
        fill.setBackgroundColor(color);
        trackView.addView(fill, new LinearLayout.LayoutParams(0, dp(5), fraction));
        View rest = new View(this);
        rest.setBackgroundColor(track);
        trackView.addView(rest, new LinearLayout.LayoutParams(0, dp(5), Math.max(0f, 1f - fraction)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(5));
        lp.setMargins(0, dp(3), 0, dp(8));
        card.addView(trackView, lp);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(surface);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        return card;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private void head(LinearLayout card, String label) {
        TextView view = new TextView(uiContext);
        view.setText(label);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(text);
        view.setPadding(0, 0, 0, dp(8));
        card.addView(view);
    }

    private TextView note(LinearLayout card, String label) {
        TextView view = new TextView(uiContext);
        view.setText(label);
        view.setTextSize(12);
        view.setTextColor(text2);
        card.addView(view);
        return view;
    }

    private String fmt(long ms) {
        if (ms <= 0L) return s(R.string.dur_zero);
        long minutes = ms / 60000L;
        if (minutes < 1L) return s(R.string.dur_less);
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        if (hours == 0L) return s(R.string.dur_min, rest);
        return s(R.string.dur_hm, hours, rest);
    }

    private String s(int resource) {
        return uiContext.getString(resource);
    }

    private String s(int resource, Object... args) {
        return uiContext.getString(resource, args);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed=true;
        if (opened && lease != null && !loading) load();
    }
    @Override public void onConfigurationChanged(Configuration next){
        super.onConfigurationChanged(next);
        int previous=viewState,position=scroll==null?0:scroll.getScrollY();
        String message=currentMessage;boolean retry=messageRetry;
        readPresentation();build();
        if(previous==1&&lastSummary!=null)renderSummary(lastSummary);
        else if(previous==2)showPermission();
        else showMessage(loading?s(R.string.loading):message,retry);
        scroll.post(()->scroll.scrollTo(0,position));
    }

    @Override protected void onPause() {
        super.onPause();
        resumed=false;
        if (loading && lease != null && client != null) {
            client.cancel(lease, currentRequest);
            currentRequest=++requestCounter;
            loading = false;
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ACCESS && data != null) {
            int status=data.getIntExtra(PluginProtocol.EXTRA_ACCESS_STATUS,PluginStatus.ACCESS_NOT_GRANTED);
            if(status==PluginStatus.ACCESS_REVOKED || status==PluginStatus.ACCESS_UNAVAILABLE) {
                accessDenied=true;
                showMessage(status==PluginStatus.ACCESS_REVOKED?s(R.string.request_denied):s(R.string.perm_unavailable),false);
            }
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (!isChangingConfigurations()) {
            if (lease != null && client != null) client.close(lease);
            if (client != null) client.destroy();
            if (holder != null && ticket != null && ticket.ticketId.equals(holder.ticketId)) holder = null;
        }
        super.onDestroy();
    }
}


