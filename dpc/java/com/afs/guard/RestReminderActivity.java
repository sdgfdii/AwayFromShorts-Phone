package com.afs.guard;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/** A dismissible reminder in its own task; never changes locking or learning state. */
public final class RestReminderActivity extends Activity {
    private static WeakReference<RestReminderActivity> current = new WeakReference<>(null);

    static Intent intent(Context c, int minutes) {
        return new Intent(c, RestReminderActivity.class).putExtra("minutes", minutes)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static boolean backgroundAllowed(Context c) {
        DevicePolicyManager policy = (DevicePolicyManager)c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return (policy != null && policy.isDeviceOwnerApp(c.getPackageName())) || Settings.canDrawOverlays(c);
    }

    static void dismissCurrent() {
        new Handler(Looper.getMainLooper()).post(() -> {
            RestReminderActivity activity = current.get();
            if (activity != null) activity.finishAndRemoveTask();
        });
    }

    @Override protected void attachBaseContext(Context base) {
        T.init(base);
        super.attachBaseContext(ThemeStore.context(base));
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        current = new WeakReference<>(this);
        setFinishOnTouchOutside(true);
        getWindow().setWindowAnimations(0);
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render();
    }

    private void render() {
        Ui ui = new Ui(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(24),ui.dp(24),ui.dp(24),ui.dp(20));
        card.setBackground(ui.round(ui.surface(),24));
        TextView title = new TextView(this);
        title.setText(T.s("该歇会儿了"));
        title.setTextSize(24);
        title.setTextColor(ui.text());
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(title);
        TextView message = new TextView(this);
        int minutes = Math.max(1,Math.min(RestReminder.MAX_EVERY,getIntent().getIntExtra("minutes",45)));
        message.setText(T.f("已经连续 %d 分钟没休息，站起来走走，看看远处。",minutes));
        message.setTextColor(ui.text2());
        message.setTextSize(16);
        message.setLineSpacing(ui.dp(4),1);
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(-1,-2);
        messageLp.topMargin = ui.dp(16);
        card.addView(message,messageLp);
        TextView close = new TextView(this);
        close.setText(T.s("知道了"));
        close.setTextSize(16);
        close.setTextColor(ui.accent());
        close.setGravity(Gravity.CENTER);
        close.setBackground(ui.round(ui.surface2(),14));
        close.setOnClickListener(v -> { RestAlert.clear(this); finishAndRemoveTask(); });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1,ui.dp(48));
        closeLp.topMargin = ui.dp(24);
        card.addView(close,closeLp);
        setContentView(card);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels-ui.dp(40),ui.dp(400));
        getWindow().setLayout(width,ViewGroup.LayoutParams.WRAP_CONTENT);
        ThemeStore.typography(card);
    }

    @Override public void onBackPressed() { RestAlert.clear(this); finishAndRemoveTask(); }

    @Override protected void onDestroy() {
        if(current.get()==this) current.clear();
        super.onDestroy();
    }
}

