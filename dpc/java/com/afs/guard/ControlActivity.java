package com.afs.guard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 自律规则：定时锁机、钥匙应用、亲友解锁码、关键词拦截、页面拦截，五个都在这一页。
 *
 * 为什么合成一页而不是五个子页：这五项共用同一套前提（设备所有者 / 无障碍 / 使用情况），
 * 而用户真正在调的是「今天要多紧」这一件事。分开成五页，每页都要重复一遍权限说明，
 * 反而看不出彼此的关系。
 *
 * 屏蔽联网不在这里：它和冻结页的「哪些应用不许用」是同一件事的两个面（一个不让开、
 * 一个开了也连不上），放到冻结页底部的设置里和冻结应用设置并排，见 {@link FreezeBoardActivity}。
 *
 * 页面只写配置和真状态，所有判定都在各自的门禁类里（{@link LockEnforcer}、
 * {@link KeyGate}、{@link KeywordGuardService}），这里不做第二套逻辑。
 */
public final class ControlActivity extends ThemedActivity {

    private static final int REQ_LOCK_KEYS = 11;
    private static final int REQ_KEYGATE = 12;
    private static final int REQ_KEYWORD = 13;
    private static final int REQ_LOCK_APPS = 16;
    private static final int REQ_PAGE_TARGET = 17;

    private Ui ui;
    private LinearLayout body;

    private interface Pick {
        void on(int index);
    }

    /** 设置页那行：一眼看出现在有几条规则真在生效。 */
    public static String summary(Context c) {
        int n = 0;
        if (LockStore.anyOn(c)) n++;
        if (ReminderStore.on(c)) n++;
        if (KeyGate.enabled(c)) n++;
        if (UnlockStore.enabled(c)) n++;
        if (KeywordStore.active(c)) n++;
        if (PageRuleStore.active(c)) n++;
        return n == 0 ? T.s("都没开") : T.f("%d 项生效", n);
    }

    @Override protected void onCreate(Bundle state) {        super.onCreate(state);
        ui = new Ui(this);
        FloatingNav.fullscreen(this);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (body != null) render();
    }

    /* ==================== 页面 ==================== */

    private void render() {
        Bundle position = PageScrollState.capture(this);
        ScrollView scroll = new ScrollView(this);
        ui.page(scroll);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(36));
        scroll.addView(body);

        body.addView(header());
        body.addView(ui.title(Lang.t(this, "自律规则", "Self-control rules")));

        capabilitySection();
        lockSection();
        restSection();
        keyGateSection();
        unlockSection();
        keywordSection();
        pageSection();

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

    private LinearLayout card() {
        LinearLayout card = ui.card();
        body.addView(card, ui.gap(10));
        return card;
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
        tail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(tail);

        if (onClick != null) line.setOnClickListener(v -> onClick.run());
        parent.addView(line);
    }

    private void toggle(LinearLayout parent, String title, boolean on, Runnable onClick) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, ui.dp(12), 0, ui.dp(12));

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(15);
        name.setTextColor(ui.text());
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView state = new TextView(this);
        state.setText(on ? T.s("开") : T.s("关"));
        state.setTextSize(13);
        state.setTextColor(on ? ui.green() : ui.text3());
        line.addView(state);

        line.setOnClickListener(v -> onClick.run());
        parent.addView(line);
    }

    private void note(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11);
        view.setTextColor(ui.text3());
        view.setPadding(0, ui.dp(2), 0, ui.dp(10));
        parent.addView(view);
    }

    private void pick(String title, String[] labels, int checked, Pick onPick) {
        ThemeStore.dialog(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    onPick.on(which);
                })
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    /* ==================== 能力 ==================== */

    private void capabilitySection() {
        int level = Capability.level(this);
        body.addView(groupTitle(Lang.t(this, "现在能做到什么", "What works right now")));
        LinearLayout card = card();
        row(card, Lang.t(this, "当前等级", "Level"), Capability.name(level) + T.s(" · 点此切换"), () -> Capability.pick(this));
        note(card, Capability.scope(level));
        if (!Guard.isOwner(this)) {
            row(card, T.s("设备所有者"), T.s("要装部署包"), null);
        }
        row(card, T.s("无障碍服务"), Capability.accessibilityOn(this) ? T.s("已开启") : T.s("未开启"), () -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (RuntimeException error) {
                toast(T.s("这个系统没有对应的设置页"));
            }
        });
        row(card, T.s("使用情况访问"), AppUsageQuota.permission(this) ? T.s("已开启") : T.s("未开启"), () -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (RuntimeException error) {
                toast(T.s("这个系统没有对应的设置页"));
            }
        });
    }

    /* ==================== 定时锁机 ==================== */

    private void lockSection() {
        body.addView(groupTitle(Lang.t(this, "定时锁机", "Scheduled lock")));
        LinearLayout card = card();

        toggle(card, T.s("亮屏累计锁机"), LockStore.periodicOn(this), () -> {
            boolean next = !LockStore.periodicOn(this);
            LockStore.setPeriodicOn(this, next);
            if (next && !Guard.isOwner(this)) {
                toast(T.s("没有设备所有者，锁机拦不住 Home 键"));
            }
            render();
        });
        row(card, T.s("生效时段"), LockStore.window(this), () -> pickWindow());
        row(card, T.s("累计亮屏多久就锁"), LockPlan.minutes(LockStore.period(this)), this::pickPeriod);
        row(card, T.s("每次锁多久"), LockPlan.minutes(LockStore.lockMinutes(this)), this::pickLockLength);
        if (LockStore.periodicOn(this)) {
            row(card, T.s("这一轮"), ScreenLock.describe(LockEnforcer.screenAccum(this), LockStore.period(this)), null);
        }
        note(card, T.s("只算屏幕亮着的时间：灭屏、正在锁着的那几分钟都不累计。累计满一轮就锁一次，锁完从零开始算。"));
        note(card, T.s("和按钟点的锁机不一样：不看几点，只看这一轮亮屏累计了多久。摸鱼少的日子不会被照常锁，盯屏久的下午会锁得更勤。"));

        toggle(card, T.s("单应用久用锁"), LockStore.appOn(this), () -> {
            LockStore.setAppOn(this, !LockStore.appOn(this));
            render();
        });
        row(card, T.s("连续用多久就锁"), LockPlan.minutes(LockStore.appLimit(this)), this::pickAppLimit);
        row(card, T.s("锁多久"), LockPlan.minutes(LockStore.appLockMinutes(this)), this::pickAppLock);
        Set<String> monitored = LockStore.appTargets(this);
        row(card, T.s("监控哪些应用"), monitored.isEmpty() ? T.s("全部应用") : T.f("%d 个", monitored.size()),
                () -> pickApps(REQ_LOCK_APPS,
                        T.s("只监控选中的应用；一个都不选就是全部"),
                        new ArrayList<String>(monitored)));
        note(card, T.s("一直待在同一个应用里才算连续；切走再回来重新计时。不选应用时对全部应用生效，短视频这类最容易被抓。"));

        row(card, T.s("关键应用白名单"), T.f("%d 个", LockStore.keys(this).size()), () -> pickApps(REQ_LOCK_KEYS,
                T.s("锁机时这些应用照常可用"),
                new ArrayList<String>(LockStore.keys(this))));
        note(card, T.s("把电话、导航、备忘录这类放进白名单，锁机不会把它们一起挡住。"));
    }

    private void pickWindow() {
        String[] labels = new String[48];
        for (int i = 0; i < 48; i++) {
            labels[i] = LockStore.clock(i * 30);
        }
        int from = LockStore.from(this) / 30;
        pick(T.s("从几点开始"), labels, Math.min(from, 47), which -> {
            int start = which * 30;
            LockStore.setWindow(this, start, LockStore.to(this));
            String[] ends = new String[48];
            for (int i = 0; i < 48; i++) ends[i] = LockStore.clock(i * 30);
            pick(T.s("到几点结束"), ends, Math.min(LockStore.to(this) / 30, 47), end -> {
                LockStore.setWindow(this, start, end * 30);
                render();
            });
        });
    }

    /* ==================== 定时提醒 ==================== */

    private void restSection() {
        body.addView(groupTitle(Lang.t(this, "定时提醒", "Rest reminder")));
        LinearLayout card = card();

        toggle(card, T.s("开启提醒"), ReminderStore.on(this), () -> {
            boolean next = !ReminderStore.on(this);
            ReminderStore.setOn(this, next);
            if (!next) RestAlert.clear(this);
            if (next && !MemoReminder.allowed(this)) {
                toast(T.s("通知没开，提醒发不出来"));
            }
            render();
        });
        row(card, T.s("每隔多久提醒"), RestReminder.minutes(ReminderStore.every(this)), this::pickEvery);
        row(card, T.s("时间段"), ReminderStore.window(this), this::pickRestWindow);
        note(card, T.s("只发一条通知，不拦人。灭屏、正在锁机、放行期里都不打扰。"));
        note(card, T.s("和上面的定时锁机是两回事：锁机是硬拦，这个是轻提醒，可以只开一个。"));
    }

    private void pickEvery() {
        final int[] values = {15, 30, 45, 60, 90};
        String[] labels = new String[values.length];
        int checked = 2;
        for (int i = 0; i < values.length; i++) {
            labels[i] = RestReminder.minutes(values[i]);
            if (values[i] == ReminderStore.every(this)) checked = i;
        }
        pick(T.s("每隔多久提醒"), labels, checked, which -> {
            ReminderStore.setEvery(this, values[which]);
            render();
        });
    }

    private void pickRestWindow() {
        String[] labels = new String[48];
        for (int i = 0; i < 48; i++) {
            labels[i] = LockStore.clock(i * 30);
        }
        int from = ReminderStore.from(this) / 30;
        pick(T.s("从几点开始"), labels, Math.min(from, 47), which -> {
            int start = which * 30;
            String[] ends = new String[48];
            for (int i = 0; i < 48; i++) ends[i] = LockStore.clock(i * 30);
            pick(T.s("到几点结束"), ends, Math.min(ReminderStore.to(this) / 30, 47), end -> {
                ReminderStore.setWindow(this, start, end * 30);
                render();
            });
        });
    }

    private void pickPeriod() {
        final int[] values = {15, 30, 45, 60, 90, 120};
        String[] labels = new String[values.length];
        int checked = 2;
        for (int i = 0; i < values.length; i++) {
            labels[i] = LockPlan.minutes(values[i]);
            if (values[i] == LockStore.period(this)) checked = i;
        }
        pick(T.s("累计亮屏多久就锁"), labels, checked, which -> {
            LockStore.setPeriod(this, values[which], LockStore.lockMinutes(this));
            render();
        });
    }

    private void pickLockLength() {
        final int[] values = {1, 3, 5, 10, 15};
        String[] labels = new String[values.length];
        int checked = 2;
        for (int i = 0; i < values.length; i++) {
            labels[i] = LockPlan.minutes(values[i]);
            if (values[i] == LockStore.lockMinutes(this)) checked = i;
        }
        pick(T.s("每次锁多久"), labels, checked, which -> {
            LockStore.setPeriod(this, LockStore.period(this), values[which]);
            render();
        });
    }

    private void pickAppLimit() {
        final int[] values = {10, 20, 30, 45, 60};
        String[] labels = new String[values.length];
        int checked = 2;
        for (int i = 0; i < values.length; i++) {
            labels[i] = LockPlan.minutes(values[i]);
            if (values[i] == LockStore.appLimit(this)) checked = i;
        }
        pick(T.s("连续用多久就锁"), labels, checked, which -> {
            LockStore.setAppLimit(this, values[which], LockStore.appLockMinutes(this));
            render();
        });
    }

    private void pickAppLock() {
        final int[] values = {1, 3, 5, 10, 15};
        String[] labels = new String[values.length];
        int checked = 2;
        for (int i = 0; i < values.length; i++) {
            labels[i] = LockPlan.minutes(values[i]);
            if (values[i] == LockStore.appLockMinutes(this)) checked = i;
        }
        pick(T.s("锁多久"), labels, checked, which -> {
            LockStore.setAppLimit(this, LockStore.appLimit(this), values[which]);
            render();
        });
    }

    /* ==================== 钥匙应用 ==================== */

    private void keyGateSection() {
        body.addView(groupTitle(Lang.t(this, "钥匙应用", "Key app")));
        LinearLayout card = card();
        toggle(card, T.s("开启钥匙闸门"), KeyGate.on(this), () -> {
            KeyGate.setEnabled(this, !KeyGate.on(this));
            KeyGate.invalidate();
            render();
        });
        row(card, T.s("钥匙应用"), T.f("%d 个", KeyGate.packages(this).size()), () -> pickApps(REQ_KEYGATE,
                T.s("在这些应用里待够时间才放行娱乐应用"),
                new ArrayList<String>(KeyGate.packages(this))));
        row(card, T.s("需要待够"), LockPlan.minutes(KeyGate.required(this)), this::pickRequired);
        row(card, T.s("今天进度"), KeyGate.status(this, System.currentTimeMillis()), null);
        note(card, T.s("看的是系统使用记录里今天在这些应用里的累计时长，不看打卡，也不上传。没开使用情况权限时不计时。"));
    }

    private void pickRequired() {
        final int[] values = {10, 15, 20, 30, 45, 60};
        String[] labels = new String[values.length];
        int checked = 2;
        for (int i = 0; i < values.length; i++) {
            labels[i] = LockPlan.minutes(values[i]);
            if (values[i] == KeyGate.required(this)) checked = i;
        }
        pick(T.s("需要待够"), labels, checked, which -> {
            KeyGate.setRequired(this, values[which]);
            render();
        });
    }

    /* ==================== 亲友解锁码 ==================== */

    private void unlockSection() {
        body.addView(groupTitle(Lang.t(this, "亲友解锁码", "Family unlock code")));
        LinearLayout card = card();
        toggle(card, T.s("启用解锁码"), UnlockStore.enabled(this), () -> {
            if (!UnlockStore.enabled(this) && UnlockStore.secret(this).isEmpty()) {
                generateSecret();
                return;
            }
            UnlockStore.setEnabled(this, !UnlockStore.enabled(this));
            render();
        });
        row(card, T.s("密钥"), UnlockStore.secret(this).isEmpty() ? T.s("还没生成") : T.s("已有"), this::showSecret);
        row(card, T.s("更换密钥"), "›", this::confirmRegenerate);
        row(card, T.s("试一次码"), "›", this::tryCode);
        long now = System.currentTimeMillis();
        if (UnlockStore.passActive(this, now)) {
            row(card, T.s("放行中"), T.f("还剩 %d 分钟", (int) Math.ceil(UnlockStore.passRemaining(this, now) / 60000.0)),
                    () -> {
                        UnlockStore.clearPass(this);
                        toast(T.s("放行已结束"));
                        render();
                    });
        } else {
            row(card, T.s("放行中"), T.s("否"), null);
        }
        note(card, T.s("这是标准的 6 位动态码，30 秒一换。把密钥加进家长手机上的验证器，Google Authenticator、1Password、微软验证器都行。")
                + T.s("孩子这边只验证、不显示码，验过一次放行 10 分钟，可以提前结束锁机。"));
        note(card, T.s("要说明的边界：密钥存在这台手机上，能打开这一页的人就看得到。")
                + T.s("它防的是「手上没有别人手机时的一时冲动」，不是防技术破解。")
                + T.s("要更硬的隔离，让家长在自己手机上生成 32 位密钥再手输进来。"));
    }

    private void generateSecret() {
        String secret = UnlockStore.generate(this);
        toast(T.s("已生成新密钥"));
        showSecretDialog(secret, true);
        render();
    }

    private void showSecret() {
        String secret = UnlockStore.secret(this);
        if (secret.isEmpty()) {
            generateSecret();
            return;
        }
        showSecretDialog(secret, false);
    }

    private void showSecretDialog(String secret, boolean fresh) {
        final String grouped = UnlockCode.grouped(secret);
        ThemeStore.dialog(this)
                .setTitle(T.s("密钥"))
                .setMessage((fresh ? T.s("这是新密钥，旧验证器里的条目立刻失效。\n\n") : "")
                        + grouped
                        + T.s("\n\n在家长手机的验证器里选「手动输入密钥」，账号名随便填，密钥就是上面这串，选「基于时间」。"))
                .setPositiveButton(T.s("复制"), (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("afs-unlock", secret));
                        toast(T.s("已复制"));
                    }
                })
                .setNegativeButton(T.s("知道了"), null)
                .show();
    }

    private void confirmRegenerate() {
        ThemeStore.dialog(this)
                .setTitle(T.s("更换密钥"))
                .setMessage(T.s("换掉之后，家长验证器里原来那条就废了，要重新加一次。确定？"))
                .setPositiveButton(T.s("换"), (dialog, which) -> generateSecret())
                .setNegativeButton(T.s("算了"), null)
                .show();
    }

    private void tryCode() {
        if (!UnlockStore.enabled(this)) {
            toast(T.s("先启用并生成密钥"));
            return;
        }
        final EditText input = new EditText(this);
        input.setHint(T.s("6 位数字"));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER);
        ThemeStore.dialog(this)
                .setTitle(T.s("试一次码"))
                .setMessage(T.s("输入家长手机验证器上此刻的码，验证配置对不对。验过会直接进入 10 分钟放行期。"))
                .setView(input)
                .setPositiveButton(T.s("验证"), (dialog, which) -> {
                    long now = System.currentTimeMillis();
                    int result = UnlockStore.redeem(this, input.getText().toString(), now);
                    if (result == 1) toast(T.s("对上了，放行 10 分钟"));
                    else if (result == 2) toast(T.f("错太多次，%d 秒后再试",
                            (int) Math.ceil(UnlockStore.cooldown(this, now) / 1000.0)));
                    else toast(T.s("码不对，或者已经过期了"));
                    render();
                })
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    /* ==================== 关键词拦截 ==================== */

    private void keywordSection() {
        body.addView(groupTitle(Lang.t(this, "应用内关键词拦截", "In-app keywords")));
        LinearLayout card = card();
        toggle(card, T.s("开启拦截"), KeywordStore.on(this), () -> {
            KeywordStore.setOn(this, !KeywordStore.on(this));
            if (KeywordStore.on(this) && !Capability.accessibilityOn(this)) {
                toast(T.s("还要去系统设置里打开无障碍服务"));
            }
            render();
        });
        List<String> words = KeywordStore.keywords(this);
        row(card, T.s("关键词"), words.isEmpty() ? T.s("还没填") : T.f("%d 个", words.size()), this::editKeywords);
        row(card, T.s("目标应用"), T.f("%d 个", KeywordStore.targets(this).size()), () -> pickApps(REQ_KEYWORD,
                T.s("只在这些应用里检查屏幕文字"),
                new ArrayList<String>(KeywordStore.targets(this))));
        row(card, T.s("无障碍服务"), Capability.accessibilityOn(this) ? T.s("已开启") : T.s("未开启"), () -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (RuntimeException error) {
                toast(T.s("这个系统没有对应的设置页"));
            }
        });
        row(card, T.s("状态"), KeywordStore.status(this), null);
        if (KeywordStore.hits(this) > 0) {
            row(card, T.s("清除拦截记录"), "›", () -> {
                KeywordStore.clearHits(this);
                toast(T.s("记录已清除"));
                render();
            });
        }
        int shortWords = KeywordRule.shortCount(words);
        note(card, T.s("命中就盖住整屏并回桌面。文字只在本机比对，不保存屏幕内容、不上传。")
                + T.s("只记录哪个应用、哪个词、第几次。"));
        if (shortWords > 0) {
            note(card, T.f("有 %d 个词只有一个字，几乎一定会误拦，建议改成两三个字的词。", shortWords));
        }
    }

    private void editKeywords() {
        final EditText input = new EditText(this);
        input.setHint(T.s("一行一个词"));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setText(KeywordRule.join(KeywordStore.keywords(this)));
        ThemeStore.dialog(this)
                .setTitle(T.s("关键词"))
                .setMessage(T.s("一行一个，英文不分大小写。视频号那类可以填「推荐」「直播」。"))
                .setView(input)
                .setPositiveButton(T.s("保存"), (dialog, which) -> {
                    KeywordStore.setKeywords(this, input.getText().toString());
                    toast(T.f("已保存 %d 个词", KeywordStore.keywords(this).size()));
                    render();
                })
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    /* ==================== 应用内页面拦截 ==================== */

    private void pageSection() {
        body.addView(groupTitle(Lang.t(this, "应用内页面拦截", "In-app pages")));
        PageRuleStore.seedBuiltins(this);
        LinearLayout card = card();
        toggle(card, T.s("开启页面拦截"), PageRuleStore.on(this), () -> {
            PageRuleStore.setOn(this, !PageRuleStore.on(this));
            if (PageRuleStore.on(this) && !Capability.accessibilityOn(this)) {
                toast(T.s("还要去系统设置里打开无障碍服务"));
            }
            if (PageRuleStore.on(this) && PageRuleStore.rules(this).isEmpty()) {
                toast(T.s("还没有规则，先在下面加一条"));
            }
            render();
        });
        List<PageRule.Rule> rules = PageRuleStore.rules(this);
        row(card, T.s("规则"), rules.isEmpty() ? T.s("还没配规则") : T.f("%d 条", rules.size()), this::openPageRules);
        row(card, T.s("微信视频号"), hasBuiltin(PageRule.WECHAT_FINDER) ? T.s("已添加") : T.s("一键添加"),
                () -> addBuiltin(PageRule.WECHAT_FINDER, T.s("已加上视频号规则")));
        row(card, T.s("微信朋友圈"), hasBuiltin(PageRule.WECHAT_MOMENTS) ? T.s("已添加") : T.s("一键添加"),
                () -> addBuiltin(PageRule.WECHAT_MOMENTS, T.s("已加上朋友圈规则")));
        row(card, T.s("状态"), PageRuleStore.status(this), null);
        if ((hasBuiltin(PageRule.WECHAT_FINDER) || hasBuiltin(PageRule.WECHAT_MOMENTS))
                && PageRuleStore.hidesText(this, PageRule.WECHAT_PKG)) {
            // 照实说：这条在微信上不会命中，别让人以为配了就管用。
            note(card, T.f("实测「%s」把页面内容对无障碍隐藏了（一屏读到 0 个字），所以「微信视频号」「微信朋友圈」这两条在它那儿不会命中。想限住它只能用整应用冻结或每日限额。",
                    appName(PageRule.WECHAT_PKG)));
        }
        note(card, T.s("进到指定页面就盖住并把人送回桌面，同一个应用里的其他页面不受影响。"));
        note(card, T.s("认页面靠页面文字或窗口类名。有些手机读不到窗口类名，所以文字才是主力：几个词用 + 连接，都出现才算，只填一个词很容易误拦。"));
        note(card, T.s("规则只在选定的应用里生效。屏幕内容只在本机比对，不保存、不上传。"));
    }

    /** 应用名；查不到就退回包名，别显示空白。 */
    private String appName(String pkg) {
        String name = Guard.labelOf(getPackageManager(), pkg);
        return name == null || name.isEmpty() ? pkg : name;
    }

    /** 某条内置规则在不在。 */
    private boolean hasBuiltin(String encoded) {
        for (PageRule.Rule rule : PageRuleStore.rules(this)) {
            if (encoded.equals(rule.encode())) return true;
        }
        return false;
    }

    /** 加一条内置规则，顺手把总开关打开（用户点「一键添加」就是想拦）。 */
    private void addBuiltin(String encoded, String done) {
        if (!PageRuleStore.add(this, encoded)) {
            toast(T.s("这条规则已经在里面了"));
            return;
        }
        if (!PageRuleStore.on(this)) PageRuleStore.setOn(this, true);
        if (!Capability.accessibilityOn(this)) toast(T.s("还要去系统设置里打开无障碍服务"));
        toast(done);
        render();
    }

    private void openPageRules() {
        final List<PageRule.Rule> rules = PageRuleStore.rules(this);
        if (rules.isEmpty()) {
            ThemeStore.dialog(this)
                    .setTitle(T.s("页面规则"))
                    .setMessage(T.s("还没有规则。可以先用下面的「微信视频号」「微信朋友圈」加一条，或者自己挑应用和页面特征。"))
                    .setPositiveButton(T.s("加一条"), (dialog, which) -> later(this::pickPageTarget))
                    .setNegativeButton(T.s("取消"), null)
                    .show();
            return;
        }
        String[] labels = new String[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            PageRule.Rule rule = rules.get(i);
            String app = Guard.labelOf(getPackageManager(), rule.pkg);
            labels[i] = app + " · " + rule.label();
        }
        ThemeStore.dialog(this)
                .setTitle(T.s("页面规则"))
                .setItems(labels, (dialog, which) -> confirmDeleteRule(rules.get(which)))
                .setNeutralButton(T.s("加一条"), (dialog, which) -> later(this::pickPageTarget))
                .setNegativeButton(T.s("关闭"), null)
                .show();
    }

    private void confirmDeleteRule(PageRule.Rule rule) {
        ThemeStore.dialog(this)
                .setTitle(T.s("删掉这条规则"))
                .setMessage(Guard.labelOf(getPackageManager(), rule.pkg) + " · " + rule.label())
                .setPositiveButton(T.s("删掉"), (dialog, which) -> {
                    PageRuleStore.remove(this, rule.encode());
                    toast(T.s("已删掉"));
                    render();
                    later(this::openPageRules);
                })
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    /** 对话框一关就 startActivity，ColorOS 上会被吞掉，隔一小会儿再跳。 */
    private void later(Runnable action) {
        if (body != null) body.postDelayed(action, 250);
    }

    private void pickPageTarget() {
        pickApps(REQ_PAGE_TARGET, T.s("在哪个应用里拦这个页面"), new ArrayList<String>());
    }

    /**
     * 填页面特征。含中文按「页面文字」比对，全英文按「窗口类名」比对 ——
     * 这条判断规则要写在提示里，别让人猜自己填的会被当成哪种。
     */
    private void addPageRule(List<String> packages) {
        final EditText input = new EditText(this);
        input.setHint(T.s("finder"));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        ThemeStore.dialog(this)
                .setTitle(T.s("拦什么页面"))
                .setMessage(T.s("填页面上会出现的字，几个词用 + 连接（都要出现才拦），例如 视频号+推荐。也可以填窗口类名的一段，但有些手机读不到。含中文按文字比对，全英文按类名比对。"))
                .setView(input)
                .setPositiveButton(T.s("保存"), (dialog, which) -> {
                    String value = PageRule.clean(input.getText().toString());
                    if (value.isEmpty()) {
                        toast(T.s("得填点东西"));
                        return;
                    }
                    int added = 0;
                    for (String pkg : packages) {
                        if (PageRuleStore.add(this, pkg, value)) added++;
                    }
                    if (added == 0) {
                        toast(T.s("这条规则已经有了"));
                        return;
                    }
                    if (!PageRuleStore.on(this)) PageRuleStore.setOn(this, true);
                    toast(T.f("加了 %d 条规则", added));
                    render();
                })
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    /* ==================== 应用选择 ==================== */

    private void pickApps(int request, String hint, ArrayList<String> preset) {
        Intent intent = new Intent(this, AppPickerActivity.class)
                .putStringArrayListExtra(AppPickerActivity.EXTRA_PRESELECT, preset)
                .putExtra(AppPickerActivity.EXTRA_HINT, hint);
        startActivityForResult(intent, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        ArrayList<String> picked = data.getStringArrayListExtra("picked");
        if (picked == null) return;
        if (request == REQ_PAGE_TARGET) {          // 选完应用还要再问"拦哪个页面"
            if (!picked.isEmpty()) addPageRule(picked);
            return;
        }
        Set<String> set = new LinkedHashSet<String>(picked);
        if (request == REQ_LOCK_APPS) {
            LockStore.setAppTargets(this, set);
        } else if (request == REQ_LOCK_KEYS) {
            LockStore.setKeys(this, set);
        } else if (request == REQ_KEYGATE) {
            KeyGate.setPackages(this, set);
            KeyGate.invalidate();
        } else if (request == REQ_KEYWORD) {
            KeywordStore.setTargets(this, set);
        }
        render();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
