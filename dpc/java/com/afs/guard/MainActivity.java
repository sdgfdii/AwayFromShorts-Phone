package com.afs.guard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;

/**
 * 主界面：只看状态，不进列表。
 *
 * 应用一多，把每个应用都铺在首页就得一直往下滑 —— 那本来是个"黑名单"，
 * 平时不需要看。所以这里只留状态和几个动作，
 * 黑名单和每条时段都在 {@link AppListActivity} 里，一行一个。
 *
 * 打卡页 {@link CheckinActivity} 并进来了（原来是两个 App）：
 * 学习目标和"到点冻结"本来就是同一件事的两面，分开放反而要多装一个图标。
 * 并进来还有个实打实的好处 —— 打完卡能在同一个进程里立刻解闸，
 * 不用等 Gist 往返和 10 分钟的拉取节流。
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFFF1F2F3;
    private static final int SURFACE = 0xFFFFFFFF;
    private static final int TEXT = 0xFF18191C;
    private static final int TEXT2 = 0xFF61666D;
    private static final int BRAND = 0xFF00AEEC;
    private static final int RED = 0xFFF85A54;

    // 全部屏蔽时整页切深色：是"变黑"，不是把控件调成半透明灰
    private static final int BG_DARK      = 0xFF0D0D0F;
    private static final int SURFACE_DARK  = 0xFF1A1A1D;
    private static final int TEXT_DARK     = 0xFFEDEDEF;
    private static final int TEXT2_DARK    = 0xFF9B9BA2;
    private static final int OFF_DARK      = 0xFF2A2A2E;
    private static final int OFF_LIGHT     = 0xFFE3E5E7;
    private static final int RED_DARK      = 0xFFFF7B72;
    private static final int GREEN_DARK    = 0xFF3DDC84;

    private ScrollView scroll;
    private TextView title;
    private TextView sub;
    private TextView status;
    private TextView studyCard;
    private TextView lockNote;
    private TextView result;
    private TextView foot;
    private Button checkinBtn;
    private Button appsBtn;
    private Button applyBtn;

    private final java.util.List<View> surfaces = new java.util.ArrayList<>();
    private final java.util.List<TextView> labels = new java.util.ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(22), dp(16), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        title = new TextView(this);
        title.setText("AwayFromShorts");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        root.addView(title);

        sub = new TextView(this);
        sub.setText("设备所有者模式 · 每个应用各自的放行时段，以外一律冻结");
        sub.setTextSize(13);
        sub.setTextColor(TEXT2);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        status = card(root, 13);
        // 学习联动单独一张卡：埋在上面那块同色同字号的状态文本里，等于没写
        studyCard = card(root, 13);
        lockNote = card(root, 12);
        lockNote.setTextColor(RED);

        // 打卡是这个 App 里唯一每天都会用的动作，放最上面，且是唯一的实心按钮
        checkinBtn = button(root, "打卡", BRAND, 0xFFFFFFFF);
        checkinBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openCheckin();
            }
        });
        // 学习卡本来只显示"还有没有目标没打卡"，点它去打卡是最自然的下一步
        studyCard.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openCheckin();
            }
        });

        appsBtn = button(root, "黑名单", SURFACE, TEXT);
        appsBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AppListActivity.class));
            }
        });

        applyBtn = button(root, "立即应用", SURFACE, TEXT);
        applyBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Config.arm(MainActivity.this);   // 确认过了，宽限立即结束
                runApply();
            }
        });

        result = card(root, 12);
        result.setTextColor(TEXT2);

        foot = new TextView(this);
        foot.setText("移除守卫只有一条路：\n"
                + "adb shell dpm remove-active-admin com.afs.guard/.AfsAdmin");
        foot.setTextSize(12);
        foot.setTextColor(TEXT2);
        foot.setPadding(0, dp(18), 0, 0);
        root.addView(foot);

        setContentView(scroll);
        Guard.ensureService(this);   // 每次打开 App 顺手保证常驻守护在跑
        CheckinStore.seedToken(this); // 带 token 的构建第一次打开就落盘
        render();
        warmGoals();                 // 攒一份目标，打卡按钮上才能显示"还有几个"
    }

    /**
     * 首页自己也拉一次目标（只在本地还没有今天的数据时）。
     *
     * 不这么做的话，刚装完/刚跨天，按钮上永远是光秃秃的"打卡"，
     * 得先进打卡页一次才有数字 —— 而"还欠几个"恰恰是最该一眼看见的。
     * 拉失败什么也不做：按钮退化成"打卡"，打卡页里再报错。
     */
    private void warmGoals() {
        if (CheckinStore.pendingToday(this) >= 0) {
            return;   // 今天的目标已经在本地了，按钮上的数字就是现成的
        }
        CheckinSync.fetch(this, new CheckinSync.Sink() {
            public void onData(java.util.List<CheckinStore.Goal> goals, String date,
                               String at, Exception err) {
                if (goals != null) {
                    styleCheckin();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    /* ==================== 渲染 ==================== */

    private void render() {
        boolean owner = Guard.isOwner(this);
        boolean dark = Guard.allBlocked(this);
        boolean grace = Config.inGrace(this);
        int total = RuleStore.count(this);

        applyDark(dark);

        // 状态整体翻转的那一刻顺手重落地一次，否则结果卡会停在旧文本上
        if (firstRender) {
            firstRender = false;
            lastAllBlocked = dark;
            if (!applying) {
                runApply();
            }
        } else if (dark != lastAllBlocked) {
            lastAllBlocked = dark;
            if (!applying) {
                runApply();
            }
        }

        status.setText(Guard.describe(this));
        status.setTextColor(owner ? (dark ? TEXT_DARK : TEXT) : (dark ? RED_DARK : RED));
        applyStudyCard(dark);

        if (dark && !grace) {
            lockNote.setVisibility(View.VISIBLE);
            lockNote.setText("全部应用都在屏蔽中：现在改不了，到点自动解锁。没有解除按钮。");
            lockNote.setTextColor(dark ? RED_DARK : RED);
        } else if (grace) {
            lockNote.setVisibility(View.VISIBLE);
            lockNote.setText("首次使用：现在可以随便配，点「立即应用」后按锁定规则走。");
            lockNote.setTextColor(dark ? RED_DARK : RED);
        } else {
            lockNote.setVisibility(View.GONE);
        }

        appsBtn.setText("黑名单 · " + total + " 个应用");
        styleSecondary(appsBtn, false);
        styleSecondary(applyBtn, false);
        styleCheckin();

        result.setTextColor(dark ? TEXT2_DARK : TEXT2);
        if (result.getText().length() == 0) {
            result.setText("尚未执行。");
        }
    }

    private boolean darkState = false;
    private boolean themeApplied = false;

    /**
     * 打卡按钮：把"还剩几个目标"直接写在按钮上。
     * 数字来自上一次同步到本地的目标（不额外打网络），比单独一行说明有用得多 ——
     * 没打卡的时候一打开 App 就能看见还欠几个。
     */
    private void styleCheckin() {
        if (checkinBtn == null) {
            return;
        }
        int pend = CheckinStore.pendingToday(this);
        if (pend > 0) {
            checkinBtn.setText("打卡 · 还有 " + pend + " 个目标");
            checkinBtn.setAlpha(1f);
        } else if (pend == 0) {
            checkinBtn.setText("打卡 · 今天已经打完");
            checkinBtn.setAlpha(0.6f);
        } else {
            checkinBtn.setText("打卡");
            checkinBtn.setAlpha(1f);
        }
    }

    private void openCheckin() {
        // 把当前主题一起带过去，免得从浅色首页点进去先闪一下深色
        CheckinActivity.open(this, darkState);
    }

    /**
     * 学习联动卡片：生效中 / 保护已失效 用警示红，关闭 / 无数据用次要色，其余正常色。
     * 每秒跟 render 一起刷（holding 内部有 10 分钟节流，不会打网络）。
     */
    private void applyStudyCard(boolean dark) {
        if (studyCard == null) {
            return;
        }
        Calendar now = Calendar.getInstance();
        int st = StudyGate.cardState(this, now);
        studyCard.setText(StudyGate.cardText(this, now));
        int color;
        if (st == StudyGate.ST_ACTIVE || st == StudyGate.ST_STALE) {
            color = dark ? RED_DARK : RED;          // 正在拦 / 保护失效，都得让人看见
        } else if (st == StudyGate.ST_OFF || st == StudyGate.ST_NONE) {
            color = dark ? TEXT2_DARK : TEXT2;
        } else {
            color = dark ? TEXT_DARK : TEXT;
        }
        studyCard.setTextColor(color);
    }

    /** 整页配色：全部屏蔽时是深色，否则浅色。 */
    private void applyDark(boolean d) {
        if (themeApplied && d == darkState) {
            return;
        }
        themeApplied = true;
        darkState = d;
        scroll.setBackgroundColor(d ? BG_DARK : BG);
        try {
            getWindow().setStatusBarColor(d ? BG_DARK : BG);
            View v = getWindow().getDecorView();
            int flags = v.getSystemUiVisibility();
            if (d) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            v.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
        }
        for (View v : surfaces) {
            v.setBackgroundColor(d ? SURFACE_DARK : SURFACE);
        }
        for (TextView v : labels) {
            v.setTextColor(d ? TEXT2_DARK : TEXT2);
        }
        title.setTextColor(d ? TEXT_DARK : TEXT);
        sub.setTextColor(d ? TEXT2_DARK : TEXT2);
        foot.setTextColor(d ? TEXT2_DARK : TEXT2);
    }

    private void styleSecondary(Button b, boolean locked) {
        boolean dark = darkState;
        int offBg = dark ? OFF_DARK : OFF_LIGHT;
        int offFg = dark ? 0xFF6E6E76 : 0xFF9A9AA0;
        b.setEnabled(!locked);
        b.setTextColor(locked ? offFg : (dark ? TEXT_DARK : TEXT));
        b.setBackgroundColor(locked ? offBg : (dark ? SURFACE_DARK : SURFACE));
        b.setAlpha(locked ? 0.55f : 1f);
    }

    /* ==================== 行为 ==================== */

    private void runApply() {
        final MainActivity self = this;
        applying = true;
        new Thread(new Runnable() {
            public void run() {
                final String msg = Guard.apply(self);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        result.setText(msg);
                        applying = false;
                        render();
                    }
                });
            }
        }).start();
    }

    private boolean applying;
    private boolean lastAllBlocked;
    private boolean firstRender = true;

    /* ---------- 小工具 ---------- */

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView card(LinearLayout parent, int size) {
        TextView tv = new TextView(this);
        tv.setTextColor(TEXT);
        tv.setTextSize(size);
        tv.setLineSpacing(dp(5), 1f);
        tv.setBackgroundColor(SURFACE);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        parent.addView(tv, lp);
        surfaces.add(tv);
        return tv;
    }

    private Button button(LinearLayout parent, String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(fg);
        b.setBackgroundColor(bg);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.bottomMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }
}
