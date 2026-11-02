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
import android.view.ViewGroup;
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
                "收束时段只管「收紧」那一头：冻结名单、移出应用、调小额度这些操作只在时段内能改，时段外只能加不能减。每日限额的计时和判定不管这个时段，全天都算。断网不归它管。",
                "The focus window only governs tightening: editing the freeze list, removing apps or lowering a quota is allowed inside it; outside you can only add. Daily quotas are counted and enforced all day regardless. Network blocking is not part of it."));
        row(control, Lang.t(this, "断网调整时段", "Cut-off adjustment window"), NetWindow.label(this),
                () -> {
                    // 时段本身也是设置：能随便改时段就等于绕过了断网的锁。
                    if (NetWindow.canEdit(this)) NetWindow.pick(this, this::render);
                });
        addNote(control, Lang.t(this,
                "断网名单里的应用默认全天断网，只有这个时段内自动放开；开关、名单和时段本身也只能在此时段内修改。默认每晚 22:00–23:00，和收束时段各自独立。",
                "Apps in the cut-off list have no network by default and are released only inside this window. The switch, the list and this window itself can be changed only inside it. Default 22:00-23:00 nightly, independent of the focus window."));

        /* ---------- 应用 ---------- */
        body.addView(groupTitle(Lang.t(this, "应用", "App")));
        LinearLayout app = ui.card();
        body.addView(app, ui.gap(10));
        row(app, Lang.t(this, "成就", "Achievements"), AchievementCenter.summary(this),
                () -> startActivity(new Intent(this, AchievementActivity.class)));
        row(app, Lang.t(this, "数据与备份", "Data & backup"),
                Lang.t(this, "本地存储 / 云端配置", "Local storage / cloud sync"),
                () -> startActivity(new Intent(this, DataManagementActivity.class)));
        addNote(app, Lang.t(this,
                "成就汇总打卡、记忆、刷题、笔记与日记。打卡日记开关已移到打卡历史的日历卡片。",
                "Achievements span check-ins, memory, quizzes, notes and journals. The journal switch lives in the calendar card on the history page."));

        /* ---------- 跨设备 ---------- */
        body.addView(groupTitle(Lang.t(this, "跨设备", "Across devices")));
        LinearLayout cross = ui.card();
        body.addView(cross, ui.gap(10));
        row(cross, Lang.t(this, "共享额度", "Shared quota"), lanSummary(), this::lanQuotaEditor);
        addNote(cross, Lang.t(this,
                "把手机上某一个额度格子接到电脑上：两边各记各的用量，合起来算一个池子，任一边用完另一边也停。优先走家里 WiFi（3 秒级）；出门连不上时退回到 Gist 上最近一次的账，分钟级延迟、数字偏乐观。",
                "Binds one phone bucket to the PC: both sides report their own usage into one pool, and either side running dry stops both. Home wifi first (3-second sync); away from home it falls back to the last snapshot on Gist, which lags by minutes and reads optimistically."));

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
        permRow(perms, T.s("通知"), notifications(), Lang.t(this,
                "守卫常驻状态与备忘录提醒（定时提醒不占通知）",
                "Guard status and memo reminders (rest reminders never post notifications)"), () -> {
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
        String skin = appearance.amap() ? T.s("简约风格") : T.s("原有风格");
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

    /* ---------------- 跨设备共享额度 ---------------- */

    private String lanSummary() {
        if (!LanQuota.enabled(this)) return Lang.t(this, "关", "Off");
        if (!LanQuota.active(this)) return Lang.t(this, "未绑定", "Not bound");
        // 这一行的右边只放得下几个字：完整描述放进弹窗，否则标题会被挤成竖排。
        if (!LanQuota.fresh(this)) return Lang.t(this, "未连上电脑", "Offline");
        long remainSec = Math.max(0L, LanQuota.minutes(this) * 60L - LanQuota.total(this) / 1000L);
        return T.f("剩 %d 分钟", remainSec / 60);
    }

    private TextView lanValue() {
        TextView view = new TextView(this);
        view.setTextSize(13);
        view.setTextColor(ui.accent());
        return view;
    }

    private LinearLayout lanRow(String title, TextView value, Runnable onClick) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, ui.dp(12), 0, ui.dp(12));
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(14);
        name.setTextColor(ui.text());
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(value);
        if (onClick != null) line.setOnClickListener(v -> onClick.run());
        return line;
    }

    private interface TextSink { void accept(String value); }

    private void lanInput(String title, String hint, String current, final TextSink onOk) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        if (current != null) input.setText(current);
        input.setSelection(input.getText().length());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ThemeStore.dialog(this).setTitle(title).setView(wrap)
                .setNegativeButton(T.s("取消"), null)
                .setPositiveButton(T.s("保存"), (d, w) -> onOk.accept(input.getText().toString().trim()))
                .show();
    }

    private void lanPickGroup() {
        DailyQuotaData data = DailyQuotaStore.snapshot(this);
        org.json.JSONArray groups = data.groups();
        if (groups.length() == 0) {
            toast(T.s("先在使用额度里建一个额度格子"));
            return;
        }
        final String[] ids = new String[groups.length()];
        String[] labels = new String[groups.length()];
        int checked = -1;
        for (int i = 0; i < groups.length(); i++) {
            org.json.JSONObject group = groups.optJSONObject(i);
            ids[i] = group.optString("id");
            labels[i] = group.optString("name") + " · " + group.optInt("minutes") + T.s(" 分钟");
            if (ids[i].equals(LanQuota.group(this))) checked = i;
        }
        ThemeStore.dialog(this).setTitle(T.s("绑定哪个额度格子"))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    LanQuota.setGroup(SettingsActivity.this, ids[which],
                            groups.optJSONObject(which).optString("name"));
                    d.dismiss();
                    lanQuotaEditor();
                })
                .setNegativeButton(T.s("取消"), null).show();
    }

    private void lanProbe(final TextView state) {
        if (LanQuota.host(this).isEmpty() || LanQuota.token(this).isEmpty()) {
            toast(T.s("先填电脑地址和令牌"));
            return;
        }
        state.setText(T.s("正在连接电脑…"));
        final String host = LanQuota.host(this);
        final int port = LanQuota.port(this);
        final String token = LanQuota.token(this);
        new Thread(() -> {
            String text;
            try {
                org.json.JSONObject o = LanQuota.probe(host, port, token);
                text = T.f("连上了 · 池子 %d 分钟，电脑已用 %.1f，手机已用 %.1f",
                        o.optInt("poolMinutes", 0),
                        o.optDouble("pcUsed", 0) / 60.0,
                        o.optDouble("phoneUsed", 0) / 60.0);
            } catch (Exception error) {
                text = T.f("连不上：%s", error.getMessage() == null ? error.toString() : error.getMessage());
            }
            final String out = text;
            main.post(() -> state.setText(out));
        }, "afs-lan-probe").start();
    }

    private static String maskToken(String token) {
        if (token == null || token.isEmpty()) return "";
        if (token.length() <= 6) return token.substring(0, 1) + "***";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 3);
    }

    /** 跨设备共享：开关、电脑地址、令牌、绑定的额度格子、测试连接。 */
    private void lanQuotaEditor() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(2), ui.dp(20), ui.dp(2));

        final AlertDialog dialog = ThemeStore.dialog(this)
                .setTitle(Lang.t(this, "跨设备共享额度", "Shared quota"))
                .setView(box)
                .setNegativeButton(T.s("关闭"), null)
                .create();

        final TextView switchValue = lanValue();
        final TextView serverValue = lanValue();
        final TextView tokenValue = lanValue();
        final TextView groupValue = lanValue();
        final TextView state = new TextView(this);
        state.setTextSize(11);
        state.setTextColor(ui.text3());
        state.setPadding(0, ui.dp(10), 0, ui.dp(2));

        final Runnable paint = () -> {
            boolean on = LanQuota.enabled(this);
            switchValue.setText(on ? T.s("开") : T.s("关"));
            switchValue.setTextColor(on ? ui.accent() : ui.text3());
            serverValue.setText(LanQuota.host(this).isEmpty()
                    ? T.s("未设置") : LanQuota.host(this) + ":" + LanQuota.port(this));
            tokenValue.setText(LanQuota.token(this).isEmpty() ? T.s("未设置") : maskToken(LanQuota.token(this)));
            String name = LanQuota.groupName(this);
            groupValue.setText(LanQuota.group(this).isEmpty()
                    ? T.s("未选择") : (name.isEmpty() ? T.s("已选择") : name));
            if (!LanQuota.active(this)) {
                state.setText(T.s("填好电脑地址和令牌，再绑定一个额度格子，这个功能才会开始工作。"));
            } else if (!LanQuota.fresh(this)) {
                String err = LanQuota.lastError(this);
                state.setText(err.isEmpty()
                        ? T.s("还没有连上过电脑。手机和电脑要在同一个 WiFi 下。")
                        : T.f("连不上电脑：%s", err));
            } else if ("gist".equals(LanQuota.source(this))) {
                state.setText(LanQuota.summary(this) + T.s(" · 局域网不通，用的是 Gist 上的旧账，回到家里 WiFi 会自动恢复准确"));
            } else {
                state.setText(LanQuota.summary(this) + T.s(" · 电脑端可查看详细用量"));
            }
        };

        LinearLayout switchRow = lanRow(T.s("启用"), switchValue, () -> {
            boolean next = !LanQuota.enabled(this);
            LanQuota.setEnabled(this, next);
            if (!next) LanQuota.clearCache(this);
            paint.run();
        });
        box.addView(switchRow);

        box.addView(lanRow(T.s("电脑地址"), serverValue, () -> {
            String hint = LanQuota.host(this).isEmpty() ? "192.168.1.5:8738" : LanQuota.host(this) + ":" + LanQuota.port(this);
            lanInput(T.s("电脑地址"), hint, hint, value -> {
                if (!LanQuota.setServer(this, value, LanQuota.token(this))) {
                    toast(T.s("只能填局域网地址，比如 192.168.1.5"));
                    return;
                }
                paint.run();
            });
        }));

        box.addView(lanRow(T.s("连接令牌"), tokenValue, () ->
                lanInput(T.s("连接令牌"), T.s("电脑端设置里那一串"), LanQuota.token(this), value -> {
                    LanQuota.setToken(this, value);
                    LanQuota.clearCache(this);
                    paint.run();
                })));

        box.addView(lanRow(T.s("共享的额度格子"), groupValue, this::lanPickGroup));

        TextView probe = new TextView(this);
        probe.setText(T.s("测试连接"));
        probe.setTextSize(13);
        probe.setTextColor(ui.accent());
        probe.setGravity(Gravity.CENTER);
        probe.setPadding(0, ui.dp(14), 0, ui.dp(14));
        probe.setOnClickListener(v -> lanProbe(state));
        box.addView(probe);
        box.addView(state);

        TextView note = new TextView(this);
        note.setTextSize(11);
        note.setTextColor(ui.text3());
        note.setPadding(0, ui.dp(8), 0, 0);
        note.setText(Lang.t(this,
                "池子的分钟数由电脑端决定，手机上这个格子只负责记手机自己的用量。电脑端要先把那条规则标成共享，两边才是同一份额度。",
                "The pool budget comes from the PC side; this phone bucket only contributes its own usage. The PC rule must be flagged as shared for both sides to charge the same budget."));
        box.addView(note);

        paint.run();
        dialog.show();
    }
}
