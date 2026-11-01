package com.afs.guard;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

/**
 * 设置：外观、语言、权限体检、检查更新、隐私说明。额度设置在冻结页的「时间限额」里。
 *
 * 这一页只做"看和跳转"，不改任何冻结/打卡业务规则。
 * 每个权限项都显示真实状态，跳不过去的（设备所有者）直说要走部署包，不给假按钮。
 */
public final class SettingsActivity extends ThemedActivity {

    private Ui ui;
    private LinearLayout body;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean destroyed;
    private TextView updateLine;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        FloatingNav.fullscreen(this);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        // 从系统设置页返回后状态会变，重画一次，不让用户看旧勾。
        if (body != null) render();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void render() {
        Bundle position = PageScrollState.capture(this);
        ScrollView scroll = new ScrollView(this);
        ui.page(scroll);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(36));
        scroll.addView(body);

        body.addView(header());
        body.addView(ui.title(Lang.t(this, "设置", "Settings")));

        /* ---------- 外观 ---------- */
        body.addView(groupTitle(Lang.t(this, "外观", "Appearance")));
        LinearLayout look = ui.card();
        body.addView(look, ui.gap(10));
        row(look, Lang.t(this, "主题", "Theme"), themeSummary(), () ->
                startActivity(new Intent(this, ThemeActivity.class)));
        row(look, Lang.t(this, "界面语言", "Language"), Lang.modeName(this), this::pickLanguage);
        addNote(look, Lang.t(this,
                "风格、深浅、主题色、字体、圆角都在这一页调，改完立刻生效，不用重启。少数系统术语（应用名、系统设置项）仍是系统语言。",
                "Style, light/dark, accent colour, fonts and corner radius are all set here and apply immediately. A few system terms (app names, system settings entries) stay in the system language."));

        /* ---------- 自律 ---------- */
        body.addView(groupTitle(Lang.t(this, "自律", "Self-control")));
        LinearLayout control = ui.card();
        body.addView(control, ui.gap(10));
        row(control, Lang.t(this, "自律规则", "Self-control rules"), ControlActivity.summary(this),
                () -> startActivity(new Intent(this, ControlActivity.class)));
        row(control, Lang.t(this, "收束时段", "Focus window"), TimeWindow.label(this),
                () -> TimeWindow.pick(this, this::render));
        addNote(control, Lang.t(this,
                "收束时段是全局的：断网和每日限额只在这段时间内真正动手，时段外照常放行。改这一个时间，两件事一起跟着走。",
                "The focus window is global: network cut and daily quotas only bite inside it. Change this one time and both follow."));

        /* ---------- 打卡 ---------- */
        body.addView(groupTitle(Lang.t(this, "打卡", "Check-in")));
        LinearLayout checkin = ui.card();
        body.addView(checkin, ui.gap(10));
        row(checkin, Lang.t(this, "打卡日记", "Journal"),
                JournalStore.enabled(this) ? T.s("开") : T.s("关"), this::toggleJournal);
        row(checkin, Lang.t(this, "成就", "Achievements"), AchievementCenter.summary(this),
                () -> startActivity(new Intent(this, AchievementActivity.class)));
        addNote(checkin, Lang.t(this,
                "日记一天一条，写不写都不影响打卡。存在本机，不跟着学习记录上传。打卡日历和打卡历史合并在一页，从打卡页底部的「历史」进去。",
                "One note per day, optional. Stored locally and never uploaded. The calendar lives inside the history page now."));

        /* ---------- 权限 ---------- */
        body.addView(groupTitle(Lang.t(this, "权限体检", "Permissions")));
        LinearLayout perms = ui.card();
        body.addView(perms, ui.gap(10));
        permRow(perms, T.s("设备所有者"), Guard.isOwner(this), Guard.isOwner(this)
                ? T.s("冻结与锁机的基础能力")
                : T.s("需要部署包授权，系统设置里开不了"), null);
        permRow(perms, T.s("使用情况访问"), AppUsageQuota.permission(this),
                T.s("时间限额、用量统计要用；不授权就不计时"), () ->
                        open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        permRow(perms, T.s("通知"), notifications(), T.s("前台服务状态与提醒"), () -> {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            open(intent);
        });
        permRow(perms, T.s("精确闹钟"), exactAlarm(), T.s("边界到点才能准点执行"), () -> {
            if (Build.VERSION.SDK_INT >= 31) {
                open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            } else {
                toast(T.s("当前系统版本不需要单独授权"));
            }
        });
        permRow(perms, T.s("忽略电池优化"), batteryFree(), T.s("否则后台守卫生存时间会被压缩"), () -> {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            open(intent);
        });
        permRow(perms, T.s("无障碍服务"), Capability.accessibilityOn(this),
                T.s("应用内关键词拦截要用；开了才读得到屏幕文字"), () ->
                        open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        permRow(perms, T.s("能力等级"), Capability.level(this) >= Capability.ACCESSIBILITY,
                Capability.summary(this) + T.s(" · 点这一行切换"), () -> Capability.pick(this));

        /* ---------- 关于 ---------- */
        body.addView(groupTitle(Lang.t(this, "关于", "About")));
        LinearLayout about = ui.card();
        body.addView(about, ui.gap(10));
        row(about, Lang.t(this, "版本", "Version"), version(), null);
        updateLine = new TextView(this);
        updateLine.setText(Lang.t(this, "点下面检查更新", "Tap below to check for updates"));
        updateLine.setTextSize(12);
        updateLine.setTextColor(ui.text3());
        updateLine.setPadding(0, ui.dp(6), 0, ui.dp(10));
        about.addView(updateLine);
        row(about, Lang.t(this, "检查更新", "Check for updates"), "›", this::checkUpdate);
        row(about, Lang.t(this, "隐私说明", "Privacy"), "›", this::showPrivacy);

        setContentView(scroll);
        PageScrollState.restore(this, position);
    }

    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, ui.dp(18));
        TextView back = new TextView(this);
        back.setText("‹  " + Lang.t(this, "返回", "Back"));
        back.setTextSize(14);
        back.setTextColor(ui.accent());
        back.setPadding(0, ui.dp(6), ui.dp(12), ui.dp(6));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        return bar;
    }

    private TextView groupTitle(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(12);
        view.setTextColor(ui.text3());
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(ui.dp(4), ui.dp(16), 0, ui.dp(6));
        return view;
    }

    private void row(LinearLayout parent, String title, String value, Runnable onClick) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, ui.dp(12), 0, ui.dp(12));

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(15);
        name.setTextColor(ui.text());
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tail = new TextView(this);
        tail.setText(value == null ? "" : value);
        tail.setTextSize(13);
        tail.setTextColor(onClick == null ? ui.text3() : ui.accent());
        tail.setSingleLine(true);
        line.addView(tail);

        if (onClick != null) line.setOnClickListener(v -> onClick.run());
        parent.addView(line);
    }

    private void permRow(LinearLayout parent, String title, boolean granted, String why, Runnable onClick) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.VERTICAL);
        line.setPadding(0, ui.dp(12), 0, ui.dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(15);
        name.setTextColor(ui.text());
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView state = new TextView(this);
        state.setText(granted ? T.s("已开启") : T.s("未开启"));
        state.setTextSize(13);
        state.setTextColor(granted ? ui.green() : ui.red());
        top.addView(state);
        line.addView(top);

        TextView note = new TextView(this);
        note.setText(why);
        note.setTextSize(11);
        note.setTextColor(ui.text3());
        note.setPadding(0, ui.dp(3), 0, 0);
        line.addView(note);

        if (onClick != null) line.setOnClickListener(v -> onClick.run());
        parent.addView(line);
    }

    private void addNote(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11);
        view.setTextColor(ui.text3());
        view.setPadding(0, ui.dp(2), 0, ui.dp(10));
        parent.addView(view);
    }

    /* ==================== 交互 ==================== */

    private void pickLanguage() {
        final String[] labels = {T.s("跟随系统"), T.s("中文"), "English"};
        final String[] values = {Lang.SYSTEM, Lang.ZH, Lang.EN};
        int checked = 0;
        String current = Lang.mode(this);
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) checked = i;
        ThemeStore.dialog(this)
                .setTitle(Lang.t(this, "界面语言", "Language"))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Lang.save(this, values[which]);
                    dialog.dismiss();
                    toast(T.s("界面语言已切换"));
                    recreate();
                })
                .setNegativeButton(Lang.t(this, "取消", "Cancel"), null)
                .show();
    }

    private void toggleJournal() {
        boolean next = !JournalStore.enabled(this);
        JournalStore.setEnabled(this, next);
        toast(next ? T.s("打卡日记已打开") : T.s("打卡日记已关闭，已有的日记不会删"));
        render();
    }

    private void checkUpdate() {
        final String[] sources = UpdateCheck.sources();
        if (sources.length == 0) {
            updateLine.setText(T.s("还没有配置更新源。当前版本 ") + version() + T.s("，发布后这里会直接告诉你有没有新版。"));
            return;
        }
        updateLine.setText(T.s("正在检查…"));
        final String local = localVersion();
        final long code = versionCode();
        new Thread(() -> {
            UpdateCheck.Release release = null;
            String answerFailure = null;    // 有源回了话（404 / 看不懂），这个说法比"超时"可信
            String networkFailure = null;   // 只是连不上：DNS、超时、被墙
            for (int i = 0; i < sources.length; i++) {
                final String address = sources[i];
                if (i > 0) {
                    main.post(() -> {
                        if (!destroyed && updateLine != null) updateLine.setText(T.s("官方源没响应，正在试镜像…"));
                    });
                    sleep(200);
                }
                try {
                    release = fetchRelease(address);
                    break;
                } catch (Exception error) {
                    String reason = error.getMessage();
                    String text = T.s("检查失败：") + (reason == null || reason.trim().isEmpty()
                            ? error.getClass().getSimpleName() : reason);
                    if (error instanceof java.io.IOException) networkFailure = text;
                    else answerFailure = text;
                }
            }
            String failure = null;
            if (release == null) {
                if (answerFailure != null) failure = answerFailure;
                else if (networkFailure != null) failure = T.s("检查失败：官方源和镜像都连不上，过会儿再试");
            }
            final UpdateCheck.Release found = release;
            final String problem = failure;
            main.post(() -> {
                if (destroyed || updateLine == null) return;
                if (found == null) {
                    updateLine.setText(problem == null ? T.s("检查失败：更新源给的内容看不懂") : problem);
                    return;
                }
                if (!UpdateCheck.newer(found, local, code)) {
                    updateLine.setText(T.s("已经是最新版本（") + local + T.s("）"));
                    return;
                }
                updateLine.setText(T.s("发现新版本 ") + found.version);
                offerUpdate(found, local);
            });
        }, "afs-update-check").start();
    }

    /** 等一小会儿再试下一条源：镜像切换别把手机网络打满。 */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 拉取并解析更新源；出错抛人话异常，由调用方显示。 */
    private UpdateCheck.Release fetchRelease(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(6000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "afs-guard/" + localVersion());
        try {
            int response = connection.getResponseCode();
            if (response == 404) throw new IllegalStateException(T.s("更新源还没有发布过版本"));
            if (response == 403) throw new IllegalStateException(T.s("更新源今天被问得太多，明天再试"));
            if (response != 200) throw new IllegalStateException(T.s("服务器返回 ") + response);
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) > 0) buffer.write(chunk, 0, read);
            input.close();
            UpdateCheck.Release release = UpdateCheck.parse(buffer.toString("UTF-8"), address);
            if (release == null) throw new IllegalStateException(T.s("更新源给的内容看不懂"));
            return release;
        } finally {
            connection.disconnect();
        }
    }

    /** 有新版本才弹，说明里放更新源给的说明；没有说明就报版本号。 */
    private void offerUpdate(UpdateCheck.Release release, String local) {
        String text = release.notes == null ? "" : release.notes.trim();
        if (text.length() > 600) text = text.substring(0, 600) + "…";
        if (text.isEmpty()) {
            text = String.format(T.s("当前版本 %s，可以升到 %s。"), local, release.version);
        }
        final String[] downloads = release.downloads();
        AlertDialog.Builder dialog = ThemeStore.dialog(this)
                .setTitle(T.s("发现新版本 ") + release.version)
                .setMessage(text)
                .setNegativeButton(T.s("以后再说"), null)
                .setPositiveButton(downloads.length > 0 ? T.s("去下载") : T.s("知道了"), (d, which) -> {
                    if (downloads.length > 0) browse(downloads[0]);
                });
        // 只有清单里额外给了镜像才出现：「去下载」打不开时还能换一条。
        if (downloads.length > 1) {
            dialog.setNeutralButton(T.s("换个源"), (d, which) -> browse(downloads[1]));
        }
        dialog.show();
    }

    /** 用浏览器打开更新包的下载地址。 */
    private void browse(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException error) {
            toast(T.s("没有能打开这个链接的浏览器"));
        }
    }

    private void showPrivacy() {
        String text = T.s("存在手机里的\n")
                + T.s("· 打卡目标、笔记、记忆卡、题库、额度与冻结规则，全部在本机应用私有目录。\n")
                + T.s("· 使用时长只取自系统使用记录，在本机算，不上传。\n\n")
                + T.s("会联网的地方\n")
                + T.s("· 只有你主动开启同步时，才把打卡与目标推到你自己配置的 Gist。\n")
                + T.s("· 检查更新只在你点按钮时去发布页取一份版本清单（官方源连不上会换镜像），只读不写，也不带任何本机信息。\n\n")
                + T.s("不会做的事\n")
                + T.s("· 不收集设备标识，不上报崩溃，没有广告或统计 SDK。\n")
                + T.s("· 不把屏幕内容发到任何服务器；关键词拦截在本地匹配。");
        ThemeStore.dialog(this)
                .setTitle(Lang.t(this, "隐私说明", "Privacy"))
                .setMessage(text)
                .setPositiveButton(T.s("知道了"), null)
                .show();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            toast(T.s("这个系统没有对应的设置页"));
        }
    }

    private String themeSummary() {
        AppearanceConfig appearance = ThemeStore.load(this);
        String skin = appearance.ripple() ? T.s("涟漪风格")
                : appearance.amap() ? T.s("简约风格") : T.s("原有风格");
        String mode = Ui.themeName(this);
        if ("auto".equals(mode)) mode = T.s("跟随系统");
        else if ("dark".equals(mode)) mode = T.s("深色");
        else if ("light".equals(mode)) mode = T.s("浅色");
        return skin + " · " + mode;
    }

    private boolean notifications() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && manager.areNotificationsEnabled();
    }

    private boolean exactAlarm() {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager manager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    private boolean batteryFree() {
        PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private String version() {
        return localVersion() + T.s("（") + versionCode() + T.s("）");
    }

    private String localVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "—" : info.versionName;
        } catch (Exception error) {
            return "—";
        }
    }

    @SuppressWarnings("deprecation")
    private int versionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (Exception error) {
            return 0;
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
