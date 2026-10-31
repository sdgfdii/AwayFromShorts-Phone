package com.afs.guard;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * 工具箱：番茄钟、正念空间、未来倒计时。
 *
 * 三个工具都是这一页的独立页面（Intent extra "tool" 区分）。
 *
 * 番茄钟的「防关闭」只有一条硬路径：设备所有者 + LockTask 白名单。
 * 有 DO 时进专注会真的锁住屏幕，返回键、Home、暂停全部无效，
 * 只有倒计时走完才解锁。没有 DO 时只能拦返回键，Home 拦不住 —— 界面上直说，
 * 不假装锁住了。
 */
public final class ToolboxActivity extends ThemedActivity {

    private static final String SP_FOCUS = "afs_focus";
    private static final String SP_FUTURE = "afs_future";
    private static final String K_EVENTS = "events";

    private Ui ui;
    private Handler handler;

    /* ---------- 番茄钟 ---------- */
    private int focusMinutes = FocusPlan.DEFAULT_FOCUS;
    private int breakMinutes = FocusPlan.DEFAULT_BREAK;
    private int phase = FocusPlan.FOCUS;
    private long endAt;                 // 系统墙钟毫秒
    private boolean running;
    private boolean locked;             // 当前是否处于 LockTask
    private long phaseTotal;            // 本阶段总毫秒，用于画环
    // 离开专注页时把剩余时间原样冻结。没有 DO 时 Home 拦不住，
    // 如果按墙钟继续跑，人不在页面上也算完成一轮 —— 那是假的专注。
    private boolean parked;
    private long parkedMillis;
    private Ring ring;
    private TextView clockView, phaseView, statusView, primaryBtn;
    private LinearLayout presetBox;

    /* ---------- 正念 ---------- */
    private int mindMinutes = 3;
    private boolean breathing;
    private long breathStart;
    private View breathCircle;
    private TextView breathTip, breathClock;
    private LinearLayout mindPresets;

    /* ---------- 未来倒计时 ---------- */
    private LinearLayout eventList;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean more = false;
            if (running) more |= tickPomodoro();
            if (breathing) more |= tickBreath();
            if (more) handler.postDelayed(this, 100L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        handler = new Handler(Looper.getMainLooper());
        String tool = getIntent().getStringExtra("tool");
        if ("pomodoro".equals(tool)) pomodoroPage();
        else if ("mindful".equals(tool)) mindfulPage();
        else if ("future".equals(tool)) futurePage();
        else homePage();
    }

    /** 转屏重建不打断计时：番茄钟靠 endAt 记账，界面只是显示。 */
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        saveFocusState();
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
        // 有 LockTask 时页面本来就出不去，能走到这里只是系统事件，不算离开。
        if (running && !locked && phase == FocusPlan.FOCUS) {
            parked = true;
            parkedMillis = Math.max(0L, leftMillis());
            running = false;
        }
        saveFocusState();
        // 离开这一页就暂停（进度记住），人在别的页面不算在正念
        if (breathing) pauseMind();
    }

    @Override protected void onResume() {
        super.onResume();
        if (parked) {
            parked = false;
            endAt = System.currentTimeMillis() + parkedMillis;
            running = true;
            toast(T.s("离开专注页时计时停住了，现在接着走"));
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (locked) leaveLockTask();
        saveFocusState();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (locked) {
            toast(T.s("专注进行中，时间不走完出不去"));
            return;
        }
        super.onBackPressed();
    }

    /* ==================== 首页 ==================== */

    private void homePage() {
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        setContentView(root);
        root.addView(header(T.s("工具箱"), null, ui.text2(), null));
        root.addView(text(T.s("专注 · 呼吸 · 期待"), 12, ui.text2()), subLp());

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout cards = vertical();
        cards.setPadding(0, 0, 0, ui.dp(28));
        scroll.addView(cards);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        toolCard(cards, "◒", Ui.TILE_RUST, T.s("番茄钟"), T.s("25 分钟一轮 · 专注时锁住屏幕"), "pomodoro");
        toolCard(cards, "◌", Ui.TILE_TEAL, T.s("正念空间"), T.s("4-4-4-4 呼吸，跟着圆圈慢下来"), "mindful");
        toolCard(cards, "⏳", Ui.TILE_PURPLE, T.s("未来倒计时"), T.s("距离那个日子还有几天"), "future");
    }

    private LinearLayout.LayoutParams subLp() {
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(16));
        return slp;
    }

    private void toolCard(LinearLayout parent, String glyph, int tile, String title, String hint, final String tool) {
        LinearLayout card = ui.card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setContentDescription(title);
        TextView icon = ui.circle(glyph, tile, 44f, 20);
        ThemeStore.excludeFont(icon);
        card.addView(icon);
        LinearLayout words = vertical();
        TextView name = text(title, 17, ui.text());
        name.setTypeface(Typeface.DEFAULT_BOLD);
        words.addView(name);
        words.addView(text(hint, 12, ui.text2()));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, -2, 1);
        wlp.leftMargin = ui.dp(14);
        card.addView(words, wlp);
        card.addView(text("›", 24, ui.text2()));
        card.setOnClickListener(v -> startActivity(new Intent(this, ToolboxActivity.class).putExtra("tool", tool)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        parent.addView(card, lp);
    }

    /* ==================== 番茄钟 ==================== */

    private void pomodoroPage() {
        loadFocusState();
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        setContentView(root);
        root.addView(header(T.s("番茄钟"), T.s("重置"), ui.accent(), () -> {
            if (locked) { toast(T.s("专注中，不能重置")); return; }
            resetPomodoro();
        }));

        LinearLayout content = vertical();
        content.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(24));
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        phaseView = text(FocusPlan.phaseName(phase), 13, ui.accent());
        phaseView.setTypeface(Typeface.DEFAULT_BOLD);
        phaseView.setGravity(Gravity.CENTER);
        content.addView(phaseView, new LinearLayout.LayoutParams(-1, -2));

        android.widget.FrameLayout dial = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        dlp.topMargin = ui.dp(8);
        content.addView(dial, dlp);

        ring = new Ring(this);
        dial.addView(ring, new android.widget.FrameLayout.LayoutParams(-1, -1));

        clockView = SwissTypography.role(text("25:00", 52, ui.text()), SwissTypography.Role.KEEP);
        clockView.setTypeface(Typeface.DEFAULT_BOLD);
        clockView.setGravity(Gravity.CENTER);
        clockView.setSingleLine(true);
        dial.addView(clockView, new android.widget.FrameLayout.LayoutParams(-1, -1));

        statusView = text("", 12, ui.text2());
        statusView.setGravity(Gravity.CENTER);
        content.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        presetBox = vertical();
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = ui.dp(12);
        content.addView(presetBox, plp);
        buildPresets();

        primaryBtn = SwissTypography.role(text(T.s("开始专注"), 16, ui.onAccent()), SwissTypography.Role.KEEP);
        primaryBtn.setTypeface(Typeface.DEFAULT_BOLD);
        primaryBtn.setGravity(Gravity.CENTER);
        primaryBtn.setBackground(ui.round(ui.accent(), ui.button(16)));
        primaryBtn.setOnClickListener(v -> onPrimary());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, ui.dp(52));
        blp.topMargin = ui.dp(16);
        content.addView(primaryBtn, blp);

        TextView foot = text(Guard.isOwner(this)
                ? T.s("专注期间手机会被锁在本页：返回键、Home、暂停都关掉了，时间不走完出不去。")
                : T.s("当前没有设备所有者权限，只能拦住返回键，Home 拦不住。要真正锁住请先完成设备所有者部署。"), 11, ui.text3());
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = ui.dp(10);
        content.addView(foot, flp);

        refreshPomodoro();
    }

    private void buildPresets() {
        presetBox.removeAllViews();
        presetBox.addView(caption(T.s("专注时长")));
        LinearLayout line = row();
        for (int i = 0; i < FocusPlan.FOCUS_MINUTES.length; i++) {
            final int minutes = FocusPlan.FOCUS_MINUTES[i];
            TextView chip = ui.chip(minutes + T.s(" 分"), minutes == focusMinutes ? ui.onAccent() : ui.text(),
                    minutes == focusMinutes ? ui.accent() : ui.surface2());
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ui.dp(38), 1f);
            if (i > 0) clp.leftMargin = ui.dp(8);
            chip.setOnClickListener(v -> {
                if (running) { toast(T.s("先暂停再改时长")); return; }
                focusMinutes = minutes;
                phaseTotal = FocusPlan.phaseMillis(phase, focusMinutes, breakMinutes);
                endAt = System.currentTimeMillis() + phaseTotal;
                buildPresets();
                saveFocusState();
                refreshPomodoro();
            });
            line.addView(chip, clp);
        }
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = ui.dp(8);
        presetBox.addView(line, llp);

        presetBox.addView(caption(T.s("休息时长")));
        LinearLayout rest = row();
        for (int i = 0; i < FocusPlan.BREAK_MINUTES.length; i++) {
            final int minutes = FocusPlan.BREAK_MINUTES[i];
            TextView chip = ui.chip(minutes + T.s(" 分"), minutes == breakMinutes ? ui.onAccent() : ui.text(),
                    minutes == breakMinutes ? ui.accent() : ui.surface2());
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ui.dp(38), 1f);
            if (i > 0) clp.leftMargin = ui.dp(8);
            chip.setOnClickListener(v -> {
                if (running) { toast(T.s("先暂停再改时长")); return; }
                breakMinutes = minutes;
                if (phase == FocusPlan.BREAK) {
                    phaseTotal = FocusPlan.phaseMillis(phase, focusMinutes, breakMinutes);
                    endAt = System.currentTimeMillis() + phaseTotal;
                }
                buildPresets();
                saveFocusState();
                refreshPomodoro();
            });
            rest.addView(chip, clp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = ui.dp(8);
        presetBox.addView(rest, rlp);
    }

    private TextView caption(String value) {
        TextView view = text(value, 12, ui.text3());
        view.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ui.dp(10);
        view.setLayoutParams(lp);
        return view;
    }

    private void onPrimary() {
        if (running) {
            // 专注阶段一旦锁定就没有暂停：暂停等于放人出去，那这个锁是装饰。
            // 休息阶段可以随便停，休息不需要自律。
            if (locked && phase == FocusPlan.FOCUS) {
                toast(T.s("专注进行中，不能暂停或退出"));
                return;
            }
            running = false;
            leaveLockTask();
            saveFocusState();
            refreshPomodoro();
            return;
        }
        if (endAt <= System.currentTimeMillis()) {
            phaseTotal = FocusPlan.phaseMillis(phase, focusMinutes, breakMinutes);
            endAt = System.currentTimeMillis() + phaseTotal;
        }
        running = true;
        if (phase == FocusPlan.FOCUS) enterLockTask();
        handler.removeCallbacks(tick);
        handler.post(tick);
        saveFocusState();
        refreshPomodoro();
    }

    private void resetPomodoro() {
        running = false;
        leaveLockTask();
        phase = FocusPlan.FOCUS;
        phaseTotal = FocusPlan.phaseMillis(phase, focusMinutes, breakMinutes);
        endAt = System.currentTimeMillis() + phaseTotal;
        saveFocusState();
        buildPresets();
        refreshPomodoro();
    }


    private long leftMillis() {
        return FocusPlan.remaining(endAt, System.currentTimeMillis(), phaseTotal);
    }

    /** @return 是否还需要继续刷新 */
    private boolean tickPomodoro() {
        if (!running) return false;
        if (System.currentTimeMillis() >= endAt) {
            // 阶段结束：不自动连跳，停下等用户点「开始休息 / 下一轮」。
            running = false;
            leaveLockTask();
            phase = FocusPlan.nextPhase(phase);
            phaseTotal = FocusPlan.phaseMillis(phase, focusMinutes, breakMinutes);
            endAt = System.currentTimeMillis() + phaseTotal;
            saveFocusState();
            refreshPomodoro();
            toast(phase == FocusPlan.FOCUS ? T.s("休息结束，可以开始下一轮") : T.s("这一轮专注完成，休息一下"));
            return false;
        }
        refreshPomodoro();
        return true;
    }

    private void refreshPomodoro() {
        if (clockView == null) return;
        long left = leftMillis();
        clockView.setText(FocusPlan.clock(FocusPlan.ceilSeconds(left)));
        phaseView.setText(FocusPlan.phaseName(phase) + (running ? T.s("中") : ""));
        if (ring != null) {
            ring.set(FocusPlan.progress(phaseTotal - left, phaseTotal), ui.surface2(),
                    phase == FocusPlan.BREAK ? ui.green() : ui.accent());
        }
        if (primaryBtn != null) {
            if (running) primaryBtn.setText(locked && phase == FocusPlan.FOCUS ? T.s("专注中") : T.s("暂停"));
            else primaryBtn.setText(phase == FocusPlan.BREAK ? T.s("开始休息") : T.s("开始专注"));
        }
        if (statusView != null) {
            if (running) statusView.setText(locked ? T.s("已锁定在本页 · 时间到才解锁") : T.s("计时中 · 返回键已被拦住"));
            else statusView.setText(phase == FocusPlan.BREAK ? T.s("休息 ") + breakMinutes + T.s(" 分钟") : focusMinutes + T.s(" 分钟一轮，做完再休息"));
        }
    }

    private void loadFocusState() {
        SharedPreferences p = getSharedPreferences(SP_FOCUS, Context.MODE_PRIVATE);
        focusMinutes = p.getInt("focus", FocusPlan.DEFAULT_FOCUS);
        breakMinutes = p.getInt("brk", FocusPlan.DEFAULT_BREAK);
        phase = p.getInt("phase", FocusPlan.FOCUS);
        endAt = p.getLong("endAt", 0L);
        running = p.getBoolean("running", false);
        phaseTotal = FocusPlan.phaseMillis(phase, focusMinutes, breakMinutes);
        if (endAt <= 0L) endAt = System.currentTimeMillis() + phaseTotal;
        // 上次是在后台被冻结的：剩余时间原样接回来，不按墙钟补算。
        if (p.getBoolean("parked", false)) {
            parked = true;
            parkedMillis = p.getLong("parkedMillis", phaseTotal);
            running = false;
            endAt = System.currentTimeMillis() + parkedMillis;
            return;
        }
        // 进程被杀期间阶段可能已经走完：重启后停在下一阶段，不偷偷继续跑。
        if (running && System.currentTimeMillis() >= endAt) {
            running = false;
            phase = FocusPlan.nextPhase(phase);
            phaseTotal = FocusPlan.phaseMillis(phase, focusMinutes, breakMinutes);
            endAt = System.currentTimeMillis() + phaseTotal;
        }
    }

    private void saveFocusState() {
        getSharedPreferences(SP_FOCUS, Context.MODE_PRIVATE).edit()
                .putInt("focus", focusMinutes)
                .putInt("brk", breakMinutes)
                .putInt("phase", phase)
                .putLong("endAt", endAt)
                .putBoolean("running", running)
                .putBoolean("parked", parked)
                .putLong("parkedMillis", parkedMillis)
                .apply();
    }

    /** 只有 DO 才能真正锁住。返回是否成功进入锁定。 */
    private boolean enterLockTask() {
        if (!Guard.isOwner(this)) return false;
        try {
            DevicePolicyManager d = Guard.dpm(this);
            if (d == null) return false;
            d.setLockTaskPackages(Guard.admin(this), new String[]{getPackageName()});
            startLockTask();
            locked = true;
            return true;
        } catch (Throwable error) {
            locked = false;
            return false;
        }
    }

    private void leaveLockTask() {
        if (!locked) return;
        try {
            stopLockTask();
        } catch (Throwable ignored) {
        }
        locked = false;
    }

    /* ==================== 正念空间 ==================== */

    /** 三种节律：每段毫秒（吸 / 屏息 / 呼 / 停），0 表示这一段跳过。 */
    private static final String[] MIND_NAMES = {"4-4-4-4", "4-7-8", "6-2-6"};
    private static final int[][] MIND_STEPS = {
            {4000, 4000, 4000, 4000},
            {4000, 7000, 8000, 0},
            {6000, 2000, 6000, 0},
    };
    private static final String MIND_SP = "afs_mind";
    private static final String MIND_DAY = "day";
    private static final String MIND_TOTAL = "minutes";

    private int mindRhythm;
    private boolean mindPaused;
    private long mindPausedAt;      // 暂停那一刻的已用毫秒
    private int mindRounds;         // 已经数过去的呼吸轮数
    private TextView mindPhase, mindCount, mindToday;
    private Ring mindRing;
    private LinearLayout mindRhythms, mindDurations;
    private TextView mindStop;

    private void mindfulPage() {
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        setContentView(root);
        root.addView(header(T.s("正念空间"), null, ui.text2(), null));

        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = vertical();
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(24));
        scroll.addView(content);

        breathTip = text(T.s("准备好就坐下，跟着圆圈呼吸"), 14, ui.text2());
        breathTip.setGravity(Gravity.CENTER);
        content.addView(breathTip, new LinearLayout.LayoutParams(-1, -2));

        mindRhythms = row();
        mindRhythms.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = ui.dp(12);
        content.addView(mindRhythms, rlp);
        buildRhythms();

        // 圆圈 + 剩余进度环 + 倒计时 / 阶段字，全部叠在同一个方框里
        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        int side = (int) (getResources().getDisplayMetrics().density * 268);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(side, side);
        hlp.topMargin = ui.dp(10);
        content.addView(holder, hlp);

        mindRing = new Ring(this);
        holder.addView(mindRing, new android.widget.FrameLayout.LayoutParams(-1, -1));

        breathCircle = new View(this);
        breathCircle.setBackground(ui.round(ui.soft(ui.accent(), .55f), 999));
        int size = (int) (getResources().getDisplayMetrics().density * 168);
        android.widget.FrameLayout.LayoutParams clp = new android.widget.FrameLayout.LayoutParams(size, size);
        clp.gravity = Gravity.CENTER;
        breathCircle.setScaleX(0.62f);
        breathCircle.setScaleY(0.62f);
        holder.addView(breathCircle, clp);

        LinearLayout middle = vertical();
        middle.setGravity(Gravity.CENTER);
        breathClock = SwissTypography.role(text("03:00", 30, ui.text()), SwissTypography.Role.KEEP);
        breathClock.setTypeface(Typeface.DEFAULT_BOLD);
        breathClock.setGravity(Gravity.CENTER);
        middle.addView(breathClock);
        mindPhase = text("", 13, ui.text2());
        mindPhase.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = ui.dp(4);
        middle.addView(mindPhase, plp);
        holder.addView(middle, new android.widget.FrameLayout.LayoutParams(-1, -1));

        mindCount = text("", 12, ui.text3());
        mindCount.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = ui.dp(12);
        content.addView(mindCount, nlp);

        mindDurations = row();
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = ui.dp(10);
        content.addView(mindDurations, dlp);
        buildMindPresets();

        primaryBtn = SwissTypography.role(text(T.s("开始"), 16, ui.onAccent()), SwissTypography.Role.KEEP);
        primaryBtn.setTypeface(Typeface.DEFAULT_BOLD);
        primaryBtn.setGravity(Gravity.CENTER);
        primaryBtn.setBackground(ui.round(ui.accent(), ui.button(16)));
        primaryBtn.setOnClickListener(v -> {
            if (breathing) pauseMind();
            else if (mindPaused) resumeMind();
            else startMind();
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, ui.dp(52));
        blp.topMargin = ui.dp(16);
        content.addView(primaryBtn, blp);

        mindStop = text(T.s("结束这一轮"), 14, ui.text2());
        mindStop.setGravity(Gravity.CENTER);
        mindStop.setPadding(0, ui.dp(14), 0, ui.dp(6));
        mindStop.setOnClickListener(v -> {
            if (breathing || mindPaused) {
                finishMind(false);
            }
        });
        content.addView(mindStop, new LinearLayout.LayoutParams(-1, -2));

        mindToday = text("", 11, ui.text3());
        mindToday.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = ui.dp(10);
        content.addView(mindToday, tlp);

        TextView foot = text(T.s("鼻吸鼻呼，屏息不舒服就跳过，别硬撑。离开这一页会自动暂停。"), 11, ui.text3());
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = ui.dp(8);
        content.addView(foot, flp);

        refreshBreath();
    }

    /** 节律选项。 */
    private void buildRhythms() {
        if (mindRhythms == null) return;
        mindRhythms.removeAllViews();
        for (int i = 0; i < MIND_NAMES.length; i++) {
            final int index = i;
            TextView chip = ui.chip(MIND_NAMES[i], index == mindRhythm ? ui.onAccent() : ui.text(),
                    index == mindRhythm ? ui.accent() : ui.surface2());
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ui.dp(34), 1f);
            if (i > 0) lp.leftMargin = ui.dp(8);
            chip.setOnClickListener(v -> {
                if (breathing) {
                    toast(T.s("先停下再换节律"));
                    return;
                }
                mindRhythm = index;
                buildRhythms();
                refreshBreath();
            });
            mindRhythms.addView(chip, lp);
        }
    }

    private void buildMindPresets() {
        if (mindDurations == null) return;
        mindDurations.removeAllViews();
        final int[] options = {1, 3, 5, 10};
        for (int i = 0; i < options.length; i++) {
            final int minutes = options[i];
            TextView chip = ui.chip(minutes + T.s(" 分钟"), minutes == mindMinutes ? ui.onAccent() : ui.text(),
                    minutes == mindMinutes ? ui.accent() : ui.surface2());
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ui.dp(38), 1f);
            if (i > 0) clp.leftMargin = ui.dp(8);
            chip.setOnClickListener(v -> {
                if (breathing || mindPaused) { toast(T.s("先停下再改时长")); return; }
                mindMinutes = minutes;
                buildMindPresets();
                refreshBreath();
            });
            mindDurations.addView(chip, clp);
        }
    }

    /* ---------- 开始 / 暂停 / 继续 / 结束 ---------- */

    private void startMind() {
        breathing = true;
        mindPaused = false;
        mindRounds = 0;
        breathStart = SystemClock.elapsedRealtime();
        if (primaryBtn != null) primaryBtn.setText(T.s("暂停"));
        if (mindStop != null) mindStop.setText(T.s("结束这一轮"));
        // 跟着呼吸看屏幕，别让它在半路灭掉
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void pauseMind() {
        if (!breathing) return;
        mindPaused = true;
        mindPausedAt = SystemClock.elapsedRealtime() - breathStart;
        breathing = false;
        if (primaryBtn != null) primaryBtn.setText(T.s("继续"));
        if (breathTip != null) breathTip.setText(T.s("已暂停，回来再从这一刻接上"));
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void resumeMind() {
        if (!mindPaused) return;
        mindPaused = false;
        breathing = true;
        breathStart = SystemClock.elapsedRealtime() - mindPausedAt;
        if (primaryBtn != null) primaryBtn.setText(T.s("暂停"));
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /**
     * 结束（不管是走完还是中途点结束）。
     *
     * @param completed 是否自然走完：走完才记时长，中途结束只报一声「就到这里」——
     *                  记半分钟也算「今天正念过」会让人以为完成了一轮。
     */
    private void finishMind(boolean completed) {
        long elapsed = completed ? mindMinutes * 60000L : mindElapsed();
        breathing = false;
        mindPaused = false;
        if (primaryBtn != null) primaryBtn.setText(T.s("再来一次"));
        if (mindStop != null) mindStop.setText("");
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (breathTip != null) {
            breathTip.setText(completed ? T.s("完成了。慢一点再站起来") : T.s("就到这里，随时可以重来"));
        }
        if (mindRing != null) mindRing.set(completed ? 1f : 0f, ui.surface2(), ui.accent());
        if (breathCircle != null) {
            breathCircle.setScaleX(0.62f);
            breathCircle.setScaleY(0.62f);
        }
        if (completed) {
            mindAdd(this, mindMinutes);
            buzz(40);
        }
        updateMindToday();
        refreshBreath();
    }

    /** 已用毫秒：跑着按墙上算，暂停就冻在暂停那一刻。 */
    private long mindElapsed() {
        if (breathing) return SystemClock.elapsedRealtime() - breathStart;
        return mindPaused ? mindPausedAt : 0L;
    }

    /** @return 是否还需要继续刷新 */
    private boolean tickBreath() {
        if (!breathing) return false;
        long total = mindMinutes * 60000L;
        if (mindElapsed() >= total) {
            finishMind(true);
            return false;
        }
        refreshBreath();
        return true;
    }

    private void refreshBreath() {
        if (breathClock == null) return;
        long total = mindMinutes * 60000L;
        long elapsed = Math.max(0L, Math.min(mindElapsed(), total));
        breathClock.setText(FocusPlan.clock(FocusPlan.ceilSeconds(Math.max(0L, total - elapsed))));
        if (mindRing != null) {
            mindRing.set(total <= 0 ? 0f : (float) elapsed / total, ui.surface2(), ui.accent());
        }
        if (mindCount != null) {
            mindCount.setText(mindRounds > 0
                    ? T.s("第 ") + (mindRounds + 1) + T.s(" 次呼吸 · 节律 ") + MIND_NAMES[mindRhythm]
                    : T.s("节律 ") + MIND_NAMES[mindRhythm]);
        }
        updateMindToday();
        if (!breathing) {
            // 这里不改 breathTip：结束/暂停各有各的说法，这里一覆盖就只剩一句模板话
            if (mindPhase != null) mindPhase.setText("");
            return;
        }

        int[] steps = MIND_STEPS[mindRhythm];
        int cycle = 0;
        for (int step : steps) cycle += step;
        long inCycle = elapsed % cycle;
        mindRounds = (int) (elapsed / cycle);
        int phase = 0;
        int offset = 0;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] <= 0) continue;
            if (inCycle < offset + steps[i]) {
                phase = i;
                break;
            }
            offset += steps[i];
        }
        float t = steps[phase] <= 0 ? 1f : (inCycle - offset) / (float) steps[phase];
        // smoothstep：呼吸的收放是"慢—快—慢"，线性缩放看着像机器在抽气
        float ease = t * t * (3f - 2f * t);
        float scale;
        String label;
        if (phase == 0) {
            scale = 0.62f + 0.38f * ease;
            label = T.s("吸气");
        } else if (phase == 1) {
            scale = 1f;
            label = T.s("屏息");
        } else if (phase == 2) {
            scale = 1f - 0.38f * ease;
            label = T.s("呼气");
        } else {
            scale = 0.62f;
            label = T.s("停留");
        }
        breathTip.setText(label);
        if (mindPhase != null) mindPhase.setText(label);
        breathCircle.setScaleX(scale);
        breathCircle.setScaleY(scale);
    }

    /* ---------- 今日统计 ---------- */

    private static SharedPreferences mindSp(Context c) {
        return c.getSharedPreferences(MIND_SP, Context.MODE_PRIVATE);
    }

    private static String mindDay(Context c) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
    }

    /** 今天累计正念分钟；跨天自动归零。 */
    static int mindToday(Context c) {
        SharedPreferences p = mindSp(c);
        return mindDay(c).equals(p.getString(MIND_DAY, "")) ? p.getInt(MIND_TOTAL, 0) : 0;
    }

    private static void mindAdd(Context c, int minutes) {
        if (minutes <= 0) return;
        SharedPreferences p = mindSp(c);
        int base = mindDay(c).equals(p.getString(MIND_DAY, "")) ? p.getInt(MIND_TOTAL, 0) : 0;
        p.edit().putString(MIND_DAY, mindDay(c)).putInt(MIND_TOTAL, base + minutes).apply();
    }

    private void updateMindToday() {
        if (mindToday == null) return;
        int today = mindToday(this);
        mindToday.setText(today > 0
                ? T.s("今天已正念 ") + today + T.s(" 分钟")
                : T.s("今天还没有完成一轮"));
    }

    /** 很短的震动：完成时给一下，别做成闹钟。 */
    private void buzz(int millis) {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(millis,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(millis);
            }
        } catch (RuntimeException ignored) {
        }
    }

    /* ==================== 未来倒计时 ==================== */

    private void futurePage() {
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        setContentView(root);
        root.addView(header(T.s("未来倒计时"), T.s("＋ 添加"), ui.accent(), this::addEvent));

        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        eventList = vertical();
        eventList.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(28));
        scroll.addView(eventList);
        renderEvents();
    }

    private void renderEvents() {
        if (eventList == null) return;
        eventList.removeAllViews();
        JSONArray events = events();
        if (events.length() == 0) {
            TextView empty = text(T.s("还没有放进去的日子。\n添加一个：考试、出发、见面、发工资都行。"), 13, ui.text2());
            empty.setPadding(ui.dp(4), ui.dp(20), ui.dp(4), ui.dp(20));
            eventList.addView(empty);
            return;
        }
        long now = System.currentTimeMillis();
        Calendar todayStart = startOfDay(now);
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            final String name = event.optString("name");
            final long at = event.optLong("at");
            LinearLayout card = ui.card();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = ui.dp(12);
            eventList.addView(card, lp);

            TextView title = text(name, 16, ui.text());
            title.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(title);
            card.addView(text(fullDate(at), 12, ui.text3()));

            int gap = FocusPlan.dayGap(todayStart.getTimeInMillis(), startOfDay(at).getTimeInMillis());
            TextView big = SwissTypography.role(text(FocusPlan.dayLabel(gap), 28, gap >= 0 ? ui.text() : ui.text3()),
                    SwissTypography.Role.KEEP);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = ui.dp(10);
            card.addView(big, blp);

            long remain = at - now;
            card.addView(text(remain > 0 ? T.s("折合 ") + FocusPlan.human(remain) : T.s("这个日子已经过去了"), 12, ui.text2()));

            TextView remove = text(T.s("删除"), 13, ui.red());
            remove.setPadding(0, ui.dp(10), 0, 0);
            remove.setOnClickListener(v -> {
                JSONArray all = events();
                JSONArray next = new JSONArray();
                for (int j = 0; j < all.length(); j++) {
                    JSONObject item = all.optJSONObject(j);
                    if (item == null) continue;
                    if (item.optLong("at") == at && name.equals(item.optString("name"))) continue;
                    next.put(item);
                }
                getSharedPreferences(SP_FUTURE, Context.MODE_PRIVATE).edit().putString(K_EVENTS, next.toString()).apply();
                renderEvents();
            });
            card.addView(remove);
        }
    }

    private void addEvent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(T.s("这件事叫什么"));
        box.addView(name);

        final Calendar picked = Calendar.getInstance();
        picked.add(Calendar.DAY_OF_MONTH, 30);
        picked.set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        picked.set(Calendar.MILLISECOND, 0);
        final TextView dateView = text(fullDate(picked.getTimeInMillis()) + T.s("　点击改日期"), 14, ui.text2());
        dateView.setPadding(0, ui.dp(12), 0, ui.dp(12));
        box.addView(dateView);
        dateView.setOnClickListener(v -> new DatePickerDialog(this, (DatePicker view, int year, int month, int day) -> {
            picked.set(year, month, day, 0, 0, 0);
            picked.set(Calendar.MILLISECOND, 0);
            dateView.setText(fullDate(picked.getTimeInMillis()) + T.s("　点击改日期"));
        }, picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH)).show());

        ScrollView content = new ScrollView(this);
        content.addView(box);
        final AlertDialog dialog = ThemeStore.dialog(this)
                .setTitle(T.s("放一个日子进去"))
                .setView(content)
                .setNegativeButton(T.s("取消"), null)
                .setPositiveButton(T.s("添加"), null)
                .create();
        dialog.setOnShowListener(a -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) { toast(T.s("给它起个名字")); return; }
            if (label.length() > 40) label = label.substring(0, 40);
            try {
                JSONArray all = events();
                JSONArray next = new JSONArray();
                for (int i = 0; i < all.length(); i++) next.put(all.optJSONObject(i));
                next.put(new JSONObject().put("name", label).put("at", picked.getTimeInMillis()));
                getSharedPreferences(SP_FUTURE, Context.MODE_PRIVATE).edit().putString(K_EVENTS, next.toString()).apply();
                dialog.dismiss();
                renderEvents();
            } catch (Exception error) {
                toast(T.s("没能保存"));
            }
        }));
        dialog.show();
    }

    private static Calendar startOfDay(long at) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private JSONArray events() {
        try {
            return new JSONArray(getSharedPreferences(SP_FUTURE, Context.MODE_PRIVATE).getString(K_EVENTS, "[]"));
        } catch (Exception error) {
            return new JSONArray();
        }
    }

    private static String fullDate(long at) {
        return new java.text.SimpleDateFormat(T.s("yyyy 年 M 月 d 日"), Locale.CHINA).format(new java.util.Date(at));
    }

    /* ==================== 公共小件 ==================== */

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout column() {
        LinearLayout box = vertical();
        ui.page(box);
        return box;
    }

    private LinearLayout vertical() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private LinearLayout row() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        return box;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private View header(String title, String action, int actionColor, Runnable onAction) {
        LinearLayout bar = row();
        bar.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(14));
        bar.addView(ui.title(title), new LinearLayout.LayoutParams(0, -2, 1f));
        if (action != null) {
            TextView button = text(action, 14, actionColor);
            button.setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6));
            button.setOnClickListener(v -> {
                if (onAction != null) onAction.run();
            });
            bar.addView(button);
        }
        return bar;
    }

    /** 进度环：底环 + 进度弧，线宽随屏幕密度走。 */
    private static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value;
        private int trackColor = 0xffe6e6e6;
        private int arcColor = 0xff2f6bff;

        Ring(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
        }

        void set(float value, int trackColor, int arcColor) {
            this.value = value;
            this.trackColor = trackColor;
            this.arcColor = arcColor;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float side = Math.min(getWidth(), getHeight()) - dp(18);
            if (side <= 0) return;
            float cx = getWidth() / 2f, cy = getHeight() / 2f, stroke = dp(9);
            track.setStrokeWidth(stroke);
            track.setColor(trackColor);
            arc.setStrokeWidth(stroke);
            arc.setColor(arcColor);
            RectF box = new RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
            canvas.drawArc(box, 0f, 360f, false, track);
            if (value > 0f) canvas.drawArc(box, -90f, 360f * value, false, arc);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
