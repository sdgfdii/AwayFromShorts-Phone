package com.afs.guard;

import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 锁机页：把屏幕占住，直到这一次的休息时间走完。
 *
 * 设备所有者在位时进 LockTask —— Home / 返回 / 最近任务全部无效，和番茄钟同一套机制，
 * 计时结束自动退出，不会把手机锁死。不是 DO 时只能拦返回键，界面会写明这一点，
 * 不假装锁住了。
 *
 * 唯一的口子：亲友解锁码（如果家长配了）。验过一次给 10 分钟放行期。
 */
public final class LockScreenActivity extends ThemedActivity {

    public static final String EXTRA_END = "end";
    public static final String EXTRA_NOW = "now";
    public static final String EXTRA_APP = "app";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui ui;
    private long endAt;
    private String appPkg = "";
    private boolean locked;
    private boolean released;
    private TextView clock;
    private TextView note;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            long left = endAt - System.currentTimeMillis();
            if (left <= 0) {
                release(true);
                return;
            }
            if (clock != null) clock.setText(mmss(left));
            if (note != null) note.setText(footer(left));
            handler.postDelayed(this, 500L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        Intent intent = getIntent();
        endAt = intent == null ? 0L : intent.getLongExtra(EXTRA_END, 0L);
        appPkg = intent == null ? "" : intent.getStringExtra(EXTRA_APP);
        if (appPkg == null) appPkg = "";
        long now = intent == null ? System.currentTimeMillis() : intent.getLongExtra(EXTRA_NOW, System.currentTimeMillis());
        if (endAt <= 0L) endAt = now + LockStore.lockMinutes(this) * LockPlan.MINUTE;
        if (endAt <= System.currentTimeMillis()) {      // 已经过期：直接放行，别留一页空白
            release(false);
            return;
        }
        showLocked();
        build();
        if (Guard.isOwner(this)) enterLockTask();
    }

    private void showLocked() {
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {
        }
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        ui.page(root);
        root.setPadding(ui.dp(24), ui.dp(40), ui.dp(24), ui.dp(28));

        TextView glyph = ui.circle("◷", Ui.TILE_RUST, 72f, 34);
        ThemeStore.excludeFont(glyph);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ui.dp(72), ui.dp(72));
        glp.topMargin = ui.dp(24);
        root.addView(glyph, glp);

        TextView title = new TextView(this);
        title.setText(appPkg.isEmpty() ? T.s("该休息了") : T.s("这个应用用得有点久"));
        title.setTextSize(19);
        title.setTextColor(ui.text());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = ui.dp(20);
        root.addView(title, tlp);

        TextView sub = new TextView(this);
        sub.setText(appPkg.isEmpty()
                ? T.s("到点自己开，不用手动解")
                : Guard.labelOf(getPackageManager(), appPkg) + T.s(" 连续用了 ") + LockStore.appLimit(this) + T.s(" 分钟"));
        sub.setTextSize(13);
        sub.setTextColor(ui.text2());
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, ui.dp(6), 0, 0);
        root.addView(sub);

        clock = new TextView(this);
        clock.setText(mmss(endAt - System.currentTimeMillis()));
        clock.setTextSize(52);
        clock.setTextColor(ui.text());
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        clock.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = ui.dp(26);
        root.addView(clock, clp);

        note = new TextView(this);
        note.setText(footer(endAt - System.currentTimeMillis()));
        note.setTextSize(12);
        note.setTextColor(ui.text3());
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, ui.dp(8), 0, 0);
        root.addView(note);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (UnlockStore.enabled(this)) {
            TextView code = new TextView(this);
            code.setText(T.s("用亲友解锁码提前结束"));
            code.setTextSize(14);
            code.setTextColor(ui.accent());
            code.setGravity(Gravity.CENTER);
            code.setPadding(0, ui.dp(14), 0, ui.dp(14));
            code.setOnClickListener(v -> askCode());
            root.addView(code, new LinearLayout.LayoutParams(-1, -2));
        }

        TextView hint = new TextView(this);
        hint.setText(Guard.isOwner(this)
                ? T.s("设备所有者已锁住屏幕：返回、Home、最近任务都不起作用。时间走完自动解除。")
                : T.s("当前不是设备所有者，拦不住 Home 键。要真正锁住装部署包后重新授权。"));
        hint.setTextSize(11);
        hint.setTextColor(ui.text3());
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        handler.post(tick);
    }

    private String footer(long left) {
        String until;
        try {
            until = new java.text.SimpleDateFormat("HH:mm", T.locale())
                    .format(new java.util.Date(endAt));
        } catch (RuntimeException error) {
            until = "";
        }
        return T.f("到 %s 结束，还剩 %s", until, mmss(left));
    }

    private static String mmss(long millis) {
        long total = Math.max(0L, (millis + 999L) / 1000L);
        long m = total / 60;
        long s = total % 60;
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    /* ==================== 解锁码 ==================== */

    private void askCode() {
        final EditText input = new EditText(this);
        input.setHint(T.s("6 位数字"));
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setGravity(Gravity.CENTER);
        ThemeStore.dialog(this)
                .setTitle(T.s("亲友解锁码"))
                .setMessage(T.s("向家长要此刻的 6 位码"))
                .setView(input)
                .setPositiveButton(T.s("确认"), (dialog, which) -> redeem(input.getText().toString()))
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    private void redeem(String value) {
        long now = System.currentTimeMillis();
        int result = UnlockStore.redeem(this, value, now);
        if (result == 1) {
            toast(T.s("已放行 10 分钟"));
            LockEnforcer.released();
            endAt = 0L;
            release(true);
            return;
        }
        if (result == 2) {
            long left = UnlockStore.cooldown(this, now);
            toast(T.f("错太多次，%d 秒后再试", (int) Math.ceil(left / 1000.0)));
            return;
        }
        if (!UnlockStore.enabled(this)) {
            toast(T.s("还没配亲友解锁码"));
            return;
        }
        toast(T.s("码不对，或者已经过期了"));
    }

    /* ==================== 收尾 ==================== */

    private void release(boolean apply) {
        if (released) return;
        released = true;
        handler.removeCallbacksAndMessages(null);
        leaveLockTask();
        LockEnforcer.released();
        if (apply) {
            try {
                Guard.apply(getApplicationContext());
            } catch (RuntimeException ignored) {
            }
        }
        finish();
    }

    @Override public void onBackPressed() {
        if (released) {
            super.onBackPressed();
            return;
        }
        toast(T.s("这一轮还没走完"));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        long next = intent.getLongExtra(EXTRA_END, 0L);
        if (next > endAt) {
            endAt = next;
            if (clock != null) clock.setText(mmss(endAt - System.currentTimeMillis()));
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        leaveLockTask();
        LockEnforcer.released();
        super.onDestroy();
    }

    /** 只有 DO 才能真正锁住。返回是否成功进入锁定。 */
    private void enterLockTask() {
        try {
            DevicePolicyManager dpm = Guard.dpm(this);
            if (dpm == null) return;
            dpm.setLockTaskPackages(Guard.admin(this), new String[]{getPackageName()});
            startLockTask();
            locked = true;
        } catch (Throwable error) {
            locked = false;
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

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
