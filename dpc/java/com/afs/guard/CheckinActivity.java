package com.afs.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 打卡页：一屏一张竖着的卡，往上推就记一笔，左右推换目标。
 *
 * 为什么是"推"而不是"按"：点按钮和点卡片没有本质区别，但推是**有方向的**动作 ——
 * 手指一走就知道自己在提交，走到一半还能反悔（没推够松手会弹回去）。
 * 一张卡占满屏，也是为了让"今天还剩几个"这件事一眼能看见，
 * 而不是在一列小横条里找。
 *
 * 三个手势各管一件事，互不抢：
 *   上推  = 打卡（这一个目标）
 *   左右推 = 换目标（不打）
 *   长按  = 改这一笔的数量
 *
 * 数据流（手机写不了学习台，只能绕一圈）：
 *   上推 -> 本地队列 -> Gist 的 checkin-queue.json -> PC 每 15 分钟写进学习台
 * 所以打完之后手机这边立刻显示"已打"，页面上的统计要等 PC 同步。
 */
public class CheckinActivity extends Activity {

    /** 主题跟着首页走：全部屏蔽时是深色，否则浅色。 */
    public static final String EXTRA_DARK = "dark";

    private static final int DIR_NONE = 0;
    private static final int DIR_VERT = 1;
    private static final int DIR_HORIZ = 2;

    private static final int ACCENT = 0xFF00AEEC;
    private static final int RED_L = 0xFFF85A54;
    private static final int RED_D = 0xFFFF7B72;
    private static final int GREEN_L = 0xFF12A150;
    private static final int GREEN_D = 0xFF3DDC84;

    private boolean dark;
    private int bg, cardBg, ghostBg, textC, text2C, text3C, lineC, trackC, greenC, redC;

    private final List<CheckinStore.Goal> goals = new ArrayList<>();
    private int selected = -1;

    private FrameLayout stage;
    private LinearLayout dots;
    private TextView topSub;
    private TextView footHint;
    private TextView manageBtn;
    /** 打开着的「学习目标」管理对话框；增删目标后关掉重开，列表才是新的。 */
    private android.app.Dialog manageDialog;
    private View card;
    private View ghost;
    private TextView cardHint;
    /** 横推时从侧面跟手进来的那张（下一个目标）；竖推和静止时是 null。 */
    private View pre;
    private int preIndex = -1;
    private int preStep;

    /* 手势状态 */
    private int dir = DIR_NONE;
    private float downX, downY;
    private float vX, vY;
    private VelocityTracker vt;
    private boolean busy;
    private boolean thrFired;
    private boolean longFired;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable longPress = new Runnable() {
        public void run() {
            if (dir != DIR_NONE || busy) {
                return;
            }
            longFired = true;
            CheckinStore.Goal g = current();
            if (g != null) {
                askAmount(g);
            }
        }
    };

    /** 推送失败的原因（null = 没失败）。 */
    private String pushError;

    /* ==================== 生命周期 ==================== */

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dark = getIntent() != null && getIntent().getBooleanExtra(EXTRA_DARK, false);
        pickColors();
        CheckinStore.seedToken(this);   // 带 token 的构建：第一次进来就落盘
        buildUi();
        applyStatusBar();
        loadLocal();
        render();
        refreshAsync();
        pushAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAsync();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(longPress);
    }

    /** 状态栏跟着页面走：不设的话深色页顶上挂一条浅灰，很跳。 */
    private void applyStatusBar() {
        try {
            getWindow().setStatusBarColor(bg);
            View v = getWindow().getDecorView();
            int flags = v.getSystemUiVisibility();
            if (dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            v.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
        }
    }

    private void pickColors() {        if (dark) {
            bg = 0xFF0D0D0F;
            cardBg = 0xFF1A1A1D;
            ghostBg = 0xFF141417;
            textC = 0xFFEDEDEF;
            text2C = 0xFF9B9BA2;
            text3C = 0xFF6E6E76;
            lineC = 0xFF2A2A2E;
            trackC = 0xFF25252A;
            greenC = GREEN_D;
            redC = RED_D;
        } else {
            bg = 0xFFF1F2F3;
            cardBg = 0xFFFFFFFF;
            ghostBg = 0xFFE9EBEE;
            textC = 0xFF18191C;
            text2C = 0xFF61666D;
            text3C = 0xFF9AA0A6;
            lineC = 0xFFE3E5E7;
            trackC = 0xFFEDEFF1;
            greenC = GREEN_L;
            redC = RED_L;
        }
    }

    /* ==================== UI 骨架 ==================== */

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);

        // ---- 顶部：只留一行状态 + 刷新 ----
        // 原来这里有个 24sp 的"打卡"大标题，占掉一整条；
        // 这一页除了打卡没别的意思，标题纯属浪费高度 —— 卡片才是主角。
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(22), dp(4), dp(12), dp(0));

        topSub = new TextView(this);
        topSub.setTextColor(text2C);
        topSub.setTextSize(11);
        topSub.setMaxLines(1);
        topSub.setEllipsize(TextUtils.TruncateAt.END);
        // 长按这一行改 token：这个页面只有一个配置项，不值得开设置页
        topSub.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                askToken();
                return true;
            }
        });
        head.addView(topSub, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refresh = new TextView(this);
        refresh.setText("刷新");
        refresh.setTextColor(ACCENT);
        refresh.setTextSize(12);
        refresh.setPadding(dp(12), dp(6), dp(4), dp(6));
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                topSub.setText("正在读取…");
                refreshAsync();
                pushAsync();
            }
        });
        head.addView(refresh);

        // 管理目标只在"手机自己就是学习台"时出现 ——
        // 目标来自电脑的那条老链路里，手机上改目标等于跟电脑打架。
        manageBtn = new TextView(this);
        manageBtn.setText("管理");
        manageBtn.setTextColor(ACCENT);
        manageBtn.setTextSize(12);
        manageBtn.setPadding(dp(12), dp(6), dp(8), dp(6));
        manageBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                manage();
            }
        });
        head.addView(manageBtn);
        manageBtn.setVisibility(local() ? View.VISIBLE : View.GONE);
        root.addView(head);

        // ---- 卡片舞台 ----
        stage = new FrameLayout(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(stage, slp);

        // ---- 底部：手势提示 + 进度点 ----
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(16), dp(2), dp(16), dp(10));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        foot.addView(dots);

        footHint = new TextView(this);
        footHint.setText("上推打卡 · 左右推换目标 · 长按改数量");
        footHint.setTextColor(text3C);
        footHint.setTextSize(10);
        footHint.setGravity(Gravity.CENTER);
        footHint.setPadding(0, dp(5), 0, 0);
        foot.addView(footHint);

        root.addView(foot);
        setContentView(root);
    }

    /* ==================== 渲染 ==================== */

    private void render() {
        render(true);
    }

    /**
     * @param animateIn 要不要自带一段"从下方淡入"。换目标时必须传 false ——
     *                  入场由 {@link #enterFrom(int)} 或预摆卡负责，
     *                  两段动画同时改 alpha/translation 会互相打架，看着一顿一顿的。
     */
    private void render(boolean animateIn) {
        stage.removeAllViews();
        dots.removeAllViews();
        card = null;
        ghost = null;
        cardHint = null;
        pre = null;
        preIndex = -1;

        if (goals.isEmpty()) {
            topSub.setText(CheckinStore.goalsAt(this).isEmpty()
                    ? "还没读到学习台数据"
                    : "上次同步 " + CheckinStore.goalsAt(this));
            stage.addView(emptyCard());
            footHint.setText("点右上角「刷新」重试 · 长按标题可改同步凭据");
            return;
        }

        if (selected < 0 || selected >= goals.size()) {
            selected = firstPending();
        }
        topSub.setText(statusLine());

        // 后面那张"下一张卡"：只做视觉纵深，不承载内容
        ghost = new View(this);
        GradientDrawable gb = round(ghostBg, dp(22));
        gb.setStroke(dp(1), lineC);
        ghost.setBackground(gb);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        glp.setMargins(dp(10), dp(8), dp(10), dp(8));
        stage.addView(ghost, glp);
        ghost.setScaleX(0.94f);
        ghost.setScaleY(0.94f);
        ghost.setTranslationY(dp(12));
        ghost.setAlpha(0.6f);

        card = buildCard(goals.get(selected), selected);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        clp.setMargins(dp(16), dp(6), dp(16), dp(6));
        stage.addView(card, clp);
        if (animateIn) {
            card.setTranslationY(dp(56));
            card.setAlpha(0f);
            card.animate().translationY(0).alpha(1f)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .setDuration(210).start();
        }

        renderDots();
        footHint.setText(pending() == 0
                ? "今天的目标都打完了 · 等电脑记账后同步到学习台"
                : "上推打卡 · 左右推换目标 · 长按改数量");
        footHint.setTextColor(pending() == 0 ? greenC : text3C);
    }

    /**
     * @param index 这张卡显示"第几张"。预摆的下一张不是当前下标，
     *              所以不能直接用 {@link #selected} —— 否则角标会写错。
     */
    private View buildCard(final CheckinStore.Goal g, int index) {
        boolean done = g.hit();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(22), dp(24), dp(22));
        GradientDrawable bgd = round(cardBg, dp(22));
        bgd.setStroke(dp(1), done ? greenC : lineC);
        box.setBackground(bgd);

        // ---- 顶行：序号 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView tag = new TextView(this);
        tag.setText("今日目标");
        tag.setTextColor(text3C);
        tag.setTextSize(11);
        tag.setLetterSpacing(0.14f);
        top.addView(tag, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView idx = new TextView(this);
        idx.setText((index + 1) + " / " + goals.size());
        idx.setTextColor(text3C);
        idx.setTextSize(11);
        top.addView(idx);
        box.addView(top);

        // ---- 目标名 ----
        TextView name = new TextView(this);
        name.setText(g.name);
        name.setTextColor(done ? text2C : textC);
        name.setTextSize(30);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(14);
        box.addView(name, nlp);

        TextView meta = new TextView(this);
        meta.setText("已 " + fmt(g.done) + " / " + fmt(g.total) + " " + g.unit
                + "  ·  " + pct(g.done, g.total) + "%");
        meta.setTextColor(text3C);
        meta.setTextSize(12);
        meta.setPadding(0, dp(6), 0, 0);
        box.addView(meta);

        // ---- 中间：完成度圆环（竖卡中间那一大片空地，光留白太空） ----
        LinearLayout ringWrap = new LinearLayout(this);
        ringWrap.setGravity(Gravity.CENTER);
        ringWrap.addView(ringView(g), new LinearLayout.LayoutParams(dp(178), dp(178)));
        box.addView(ringWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 要记多少 ----
        LinearLayout amountRow = new LinearLayout(this);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        amountRow.setGravity(Gravity.CENTER);
        if (done) {
            TextView ok = new TextView(this);
            ok.setText("✓ 已打卡");
            ok.setTextColor(greenC);
            ok.setTextSize(30);
            ok.setTypeface(Typeface.DEFAULT_BOLD);
            amountRow.addView(ok);
        } else {
            TextView num = new TextView(this);
            num.setText("+" + fmt(suggestOf(g)));
            num.setTextColor(ACCENT);
            num.setTextSize(44);
            num.setTypeface(Typeface.DEFAULT_BOLD);
            amountRow.addView(num);

            TextView unit = new TextView(this);
            unit.setText(" " + g.unit);
            unit.setTextColor(text2C);
            unit.setTextSize(15);
            unit.setPadding(0, 0, 0, dp(7));
            amountRow.addView(unit);
        }
        box.addView(amountRow);

        TextView left = new TextView(this);
        left.setText(done ? "这个目标今天不用再打了"
                : "还差 " + fmt(g.total - g.done) + " " + g.unit + " 完成");
        left.setTextColor(text3C);
        left.setTextSize(11);
        left.setGravity(Gravity.CENTER);
        left.setPadding(0, dp(6), 0, 0);
        box.addView(left);

        // ---- 卡片里的手势提示（跟着卡一起飞走，比底部那行更容易看见）----
        cardHint = new TextView(this);
        cardHint.setText(done ? "左右推换一个目标" : "↑ 上推打卡");
        cardHint.setTextColor(done ? text3C : ACCENT);
        cardHint.setTextSize(13);
        cardHint.setGravity(Gravity.CENTER);
        cardHint.setPadding(0, dp(22), 0, 0);
        box.addView(cardHint);

        // 长按改数量由 Activity 的 dispatchTouchEvent 处理（自己计时），
        // 这里不能再挂 OnLongClickListener —— 两个计时会打架。
        return box;
    }

    /**
     * 完成度圆环。
     *
     * 竖屏卡片中间那块地，用一条 6dp 的细进度条填不满 —— 会显得空荡荡的。
     * 圆环既是视觉重心，也把"我现在到哪儿了"讲清楚了。
     * 用 Canvas 画而不是嵌套 ProgressBar：自带的环形 ProgressBar 样式改不动。
     */
    private View ringView(final CheckinStore.Goal g) {
        final int trackColor = trackC;
        final int fillColor = g.hit() ? greenC : ACCENT;
        final int numColor = g.hit() ? text2C : textC;
        final int capColor = text3C;
        final int percent = pct(g.done, g.total);

        return new View(this) {
            private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth();
                float h = getHeight();
                float cx = w / 2f;
                float cy = h / 2f;
                float stroke = dp(9);
                float r = Math.min(w, h) / 2f - stroke;

                arc.setStyle(Paint.Style.STROKE);
                arc.setStrokeWidth(stroke);
                arc.setStrokeCap(Paint.Cap.ROUND);
                arc.setColor(trackColor);
                c.drawCircle(cx, cy, r, arc);

                if (percent > 0) {
                    arc.setColor(fillColor);
                    c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r),
                            -90f, 360f * Math.min(100, percent) / 100f, false, arc);
                }

                num.setColor(numColor);
                num.setTextAlign(Paint.Align.CENTER);
                num.setTypeface(Typeface.DEFAULT_BOLD);
                num.setTextSize(dp(34));
                c.drawText(percent + "%", cx, cy + dp(7), num);

                cap.setColor(capColor);
                cap.setTextAlign(Paint.Align.CENTER);
                cap.setTextSize(dp(11));
                c.drawText("已完成", cx, cy + dp(28), cap);
            }
        };
    }

    private View emptyCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(26), dp(26), dp(26), dp(26));
        GradientDrawable bgd = round(cardBg, dp(22));
        bgd.setStroke(dp(1), lineC);
        box.setBackground(bgd);

        TextView t = new TextView(this);
        t.setText("读不到今日目标");
        t.setTextColor(textC);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView s = new TextView(this);
        s.setText("这个页面的数据来自电脑端学习台。\nPC 那一刻没在同步就会空着 —— 点右上角「刷新」重试。");
        s.setTextColor(text2C);
        s.setTextSize(12);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(dp(4), 1f);
        s.setPadding(0, dp(10), 0, 0);
        box.addView(s);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(16), dp(6), dp(16), dp(6));
        box.setLayoutParams(lp);
        return box;
    }

    private void renderDots() {
        dots.removeAllViews();
        for (int i = 0; i < goals.size(); i++) {
            View d = new View(this);
            boolean on = i == selected;
            boolean done = goals.get(i).hit();
            d.setBackground(round(done ? greenC : (on ? ACCENT : lineC), dp(3)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(on ? 16 : 6), dp(6));
            lp.rightMargin = dp(5);
            lp.leftMargin = dp(5);
            dots.addView(d, lp);
        }
    }

    /* ==================== 手势 ==================== */

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (vt == null) {
            vt = VelocityTracker.obtain();
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                vX = 0f;
                vY = 0f;
                dir = DIR_NONE;
                thrFired = false;
                longFired = false;
                vt.clear();
                vt.addMovement(ev);
                ui.removeCallbacks(longPress);
                if (card != null && !busy) {
                    // 上一段的入场动画还没走完就又按住了：先收到终点。
                    // 不然手指在改 translationX、动画也在改，两边抢 —— 摸起来像打滑
                    settle();
                    ui.postDelayed(longPress, 480);
                }
                return super.dispatchTouchEvent(ev);

            case MotionEvent.ACTION_MOVE: {
                vt.addMovement(ev);
                float dx = ev.getRawX() - downX;
                float dy = ev.getRawY() - downY;
                if (dir == DIR_NONE) {
                    // 先看往哪个方向走，再决定这个手势归谁 ——
                    // 一上来就抢会把"点一下"也吃掉
                    if (Math.abs(dx) > dp(12) || Math.abs(dy) > dp(12)) {
                        ui.removeCallbacks(longPress);
                        dir = Math.abs(dy) >= Math.abs(dx) ? DIR_VERT : DIR_HORIZ;
                        if (dir == DIR_HORIZ) {
                            // 一判出是横推就立刻把下一张摆进场，
                            // 而不是等松手才让新卡凭空出现
                            mountPre(dx < 0 ? 1 : -1);
                        }
                    }
                }
                if (dir != DIR_NONE && card != null && !busy) {
                    onMove(dx, dy);
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }

            case MotionEvent.ACTION_UP: {
                ui.removeCallbacks(longPress);
                vt.addMovement(ev);
                vt.computeCurrentVelocity(1000);
                vX = vt.getXVelocity();
                vY = vt.getYVelocity();
                vt.recycle();
                vt = null;
                float dx = ev.getRawX() - downX;
                float dy = ev.getRawY() - downY;
                int d = dir;
                dir = DIR_NONE;
                if (longFired) {
                    dropPre();     // 长按已经处理过，这次抬手不算数
                    return true;
                }
                if (d != DIR_NONE && !busy) {
                    onUp(d, dx, dy);
                    return true;
                }
                dropPre();
                return super.dispatchTouchEvent(ev);
            }

            case MotionEvent.ACTION_CANCEL: {
                ui.removeCallbacks(longPress);
                int d = dir;
                dir = DIR_NONE;
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                if (d != DIR_NONE && !busy) {
                    spring();
                    return true;
                }
                dropPre();
                return super.dispatchTouchEvent(ev);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private int stageWidth() {
        int w = stage.getWidth();
        return w > 0 ? w : getResources().getDisplayMetrics().widthPixels;
    }

    private int upThreshold() {
        int h = stage.getHeight();
        if (h <= 0) {
            h = getResources().getDisplayMetrics().heightPixels;
        }
        return Math.max(dp(120), Math.min(dp(230), (int) (h * 0.26f)));
    }

    /**
     * 横推多远算换一张。
     *
     * 原来是屏宽的 28%（1260px 的机器上要拖 353px ≈ 三分之一屏），
     * 手指根本走不到那么远 —— 每次都是"拖半天 → 弹回去"，这是僵硬的头号原因。
     * 现在 18%，而且快甩不看距离（见 {@link #flingX()}）。
     */
    private int sideThreshold() {
        return Math.max(dp(64), (int) (stageWidth() * 0.18f));
    }

    /** 甩到多快就算数。慢拖一般 <800px/s，快甩 >2500px/s。 */
    private float flingX() {
        return dp(420);
    }

    /**
     * 把下一个目标那张卡摆进场，压在当前卡下面。
     *
     * 原来横推时只有后面那个空壳（ghost）在动，前面一张在飞、后面空空荡荡，
     * 手感像在拖一张贴纸 —— 你不知道推过去会看见什么，所以越推越小心。
     */
    private void mountPre(int step) {
        dropPre();
        if (goals.size() < 2 || card == null) {
            return;   // 只有一个目标：推了也没得换，让它带阻力晃一下就好
        }
        int n = goals.size();
        int idx = ((selected + step) % n + n) % n;
        if (idx == selected) {
            return;
        }
        TextView keep = cardHint;            // buildCard 会改这个字段，别把当前卡带偏
        View v = buildCard(goals.get(idx), idx);
        cardHint = keep;

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(16), dp(6), dp(16), dp(6));
        stage.addView(v, stage.indexOfChild(card), lp);
        // 轮换式：下一张就是一整页宽地停在旁边，两者**不重叠**。
        // 早先那版让它从后面 42% 处缩着浮上来，那是"抽卡/覆盖"的手感 ——
        // 两张叠着一起动，看着糊，也看不出到底翻没翻页。
        v.setTranslationX(stageWidth() * step);
        v.setAlpha(1f);

        // 后面那个空壳在横推时没有任何意义了（旁边就有一张真卡），先隐掉
        if (ghost != null) {
            ghost.setAlpha(0f);
        }

        pre = v;
        preIndex = idx;
        preStep = step;
    }

    /** 撤掉预摆的那张。 */
    private void dropPre() {
        if (pre == null) {
            preIndex = -1;
            return;
        }
        View v = pre;
        pre = null;
        preIndex = -1;
        v.animate().cancel();
        stage.removeView(v);
    }

    /** 把卡片收到静止状态（打断还没跑完的入场动画）。 */
    private void settle() {
        if (card == null) {
            return;
        }
        card.animate().cancel();
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setRotation(0f);
        card.setAlpha(1f);
        if (ghost != null) {
            ghost.animate().cancel();
            ghost.setScaleX(0.94f);
            ghost.setScaleY(0.94f);
            ghost.setTranslationX(0f);
            ghost.setTranslationY(dp(12));
            ghost.setAlpha(0.6f);
        }
        dropPre();
    }

    private void onMove(float dx, float dy) {
        if (dir == DIR_VERT) {
            // 往上是提交，往下的位移做阻尼 —— 明确告诉手指"这个方向没用"
            float up = Math.min(dy, 0f);
            float down = Math.max(dy, 0f) * 0.2f;
            float t = up + down;
            card.setTranslationY(t);
            float p = Math.min(1f, -up / upThreshold());
            card.setAlpha(1f - 0.35f * p);
            if (ghost != null) {
                float s = 0.94f + 0.06f * p;
                ghost.setScaleX(s);
                ghost.setScaleY(s);
                ghost.setTranslationY(dp(12) * (1f - p));
                ghost.setAlpha(0.6f + 0.4f * p);
            }
            if (!busy) {
                if (p >= 1f && !thrFired) {
                    thrFired = true;
                    buzz(12);
                } else if (p < 1f) {
                    thrFired = false;
                }
            }
            if (cardHint != null) {
                cardHint.setText(p >= 1f ? "松手，打卡" : "↑ 上推打卡");
                cardHint.setTextColor(p >= 1f ? greenC : ACCENT);
            }
        } else {
            // 只有一个目标时左右推是死路：给一个很重的阻尼。
            // 拉不动比"能拉动、但结果还是自己"更诚实
            boolean alone = goals.size() < 2;
            float eff = alone ? dx * 0.26f : dx;
            card.setTranslationX(eff);
            float p = Math.min(1f, Math.abs(eff) / sideThreshold());

            if (pre != null) {
                // 两张卡同步平移：当前这张走多少，旁边那张就补多少 ——
                // 这就是"轮换"，手指下面是一条卡片带，不是两张叠着的卡
                pre.setTranslationX(stageWidth() * preStep + eff);
            }

            if (!busy && !alone && p >= 1f && !thrFired) {
                thrFired = true;
                buzz(8);
            } else if (p < 1f) {
                thrFired = false;
            }
            if (cardHint != null) {
                if (alone) {
                    cardHint.setText("只有一个目标");
                    cardHint.setTextColor(text3C);
                } else if (p >= 1f) {
                    cardHint.setText(dx < 0 ? "松手，看下一个 →" : "← 松手，看上一个");
                    cardHint.setTextColor(ACCENT);
                } else {
                    cardHint.setText(goals.get(selected).hit()
                            ? "左右推换一个目标" : "↑ 上推打卡");
                    cardHint.setTextColor(goals.get(selected).hit() ? text3C : ACCENT);
                }
            }
        }
    }

    private void onUp(int d, float dx, float dy) {
        if (d == DIR_VERT) {
            dropPre();
            if (-dy >= upThreshold()) {
                commit();
            } else {
                spring();
            }
            return;
        }
        if (goals.size() < 2) {
            spring();
            return;
        }
        // 距离够 —— 或者甩得够快。原来只认距离：快甩一下没拖满 28% 就弹回去，
        // 手指明明在动、卡却不认，这是"僵硬"最直接的来源。
        int step = 0;
        if (dx <= -sideThreshold() || (vX < -flingX() && dx < -dp(24))) {
            step = 1;     // 往左推 = 下一个（新卡从右边进）
        } else if (dx >= sideThreshold() || (vX > flingX() && dx > dp(24))) {
            step = -1;    // 往右推 = 上一个
        }
        if (step == 0) {
            spring();
        } else {
            flyTo(step);
        }
    }

    private void spring() {
        if (card != null) {
            card.animate().translationX(0).translationY(0).rotation(0).alpha(1f)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .setDuration(190).start();
        }
        if (ghost != null) {
            ghost.animate().scaleX(0.94f).scaleY(0.94f).translationX(0)
                    .translationY(dp(12)).alpha(0.6f)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .setDuration(190).start();
        }
        if (pre != null) {
            // 没换成就滑回旁边那一格，不能直接删 —— 直接删等于"啪"地消失
            final View v = pre;
            final int st = preStep;
            pre = null;
            preIndex = -1;
            v.animate().translationX(stageWidth() * st)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .setDuration(170)
                    .withEndAction(new Runnable() {
                        public void run() {
                            stage.removeView(v);
                        }
                    }).start();
        }
        if (cardHint != null && selected >= 0 && selected < goals.size()) {
            CheckinStore.Goal g = goals.get(selected);
            cardHint.setText(g.hit() ? "左右推换一个目标" : "↑ 上推打卡");
            cardHint.setTextColor(g.hit() ? text3C : ACCENT);
        }
    }

    /**
     * 换目标：当前这张飞出去，预摆的那张接手。
     *
     * ★ 两张一起动。原来只有当前卡飞走，新的要等 190ms 后才从 64dp 外淡进来，
     *   中间那段空场就是"顿一下"的来源；而且 render() 自己还有一段从 y+56 的入场，
     *   两段动画叠在一起，看着是糊的。
     */
    private void flyTo(final int step) {
        if (card == null) {
            stepBy(step);
            render();
            return;
        }
        busy = true;
        final View out = card;
        final View in = pre;
        pre = null;
        preIndex = -1;

        float w = stageWidth();
        // 出去 = 整整一页宽，进来那张从整整一页宽处补位：轮换，不是盖上去
        out.animate().translationX(-step * w)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .setDuration(170)
                .withEndAction(new Runnable() {
                    public void run() {
                        if (in != null) {
                            stage.removeView(in);
                        }
                        stepBy(step);
                        render(false);      // 只重排，不再自带一段入场
                        busy = false;
                        if (in == null) {
                            enterFrom(step);   // 没有预摆卡时补一段入场
                        }
                    }
                }).start();

        if (in != null) {
            in.animate().translationX(0)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .setDuration(170).start();
        }
    }

    /** 只改下标，不重排 —— 重排要等动画走到位。 */
    private void stepBy(int step) {
        if (goals.isEmpty()) {
            return;
        }
        int n = goals.size();
        selected = ((selected + step) % n + n) % n;
    }

    /** 新卡从哪一侧补进来：和刚出去的那张相反，正好一格。 */
    private void enterFrom(int step) {
        if (card == null) {
            return;
        }
        card.setTranslationX(stageWidth() * step);
        card.animate().translationX(0)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .setDuration(200).start();
    }

    /* ==================== 提交 ==================== */

    private void commit() {
        final CheckinStore.Goal g = current();
        if (g == null || busy) {
            spring();
            return;
        }
        if (g.hit()) {
            spring();
            toast("「" + g.name + "」今天已经打过了");
            return;
        }
        busy = true;
        final double amount = suggestOf(g);
        final View c = card;

        Runnable after = new Runnable() {
            public void run() {
                busy = false;
                record(g, amount);
                int next = nextPendingFrom(selected);
                if (next >= 0) {
                    selected = next;
                }
                render(false);     // 入场交给 enterFromUp，别再叠一段从下往上
                enterFromUp();
            }
        };
        if (c != null) {
            // 让卡片先飞出去再重排 —— 重排会把这张卡换掉，动画就断了
            c.animate().translationY(-stage.getHeight() * 1.1f).alpha(0f)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .setDuration(210).withEndAction(after).start();
        } else {
            after.run();
        }
    }

    private void enterFromUp() {
        if (card == null) {
            return;
        }
        card.setTranslationY(dp(40));
        card.setAlpha(0f);
        card.animate().translationY(0).alpha(1f)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .setDuration(190).start();
    }

    /** 记账 + 立刻告诉闸门还剩几个（不用等 Gist 往返和 10 分钟节流）。 */
    private void record(CheckinStore.Goal g, double amount) {
        if (local()) {
            // 本机就是学习台：直接落盘，闸门由 StudyStore 触发的 invalidate 重算
            StudyStore.check(this, g.id, amount, StudyStore.today());
            g.localHit = true;
            buzz(24);
            toast("已打卡 " + g.name + "  +" + fmt(amount) + " " + g.unit);
            android.util.Log.i(Guard.TAG, "checkin(local): " + g.name + " +" + fmt(amount)
                    + " " + g.unit + " → pending=" + (pending() - 1));
            syncGuard();
            return;
        }
        CheckinStore.add(this, g, amount, CheckinStore.today());
        g.localHit = true;
        // 先落盘：首页那边可能正好在拉数据，mergeFresh 要靠盘上的状态守住这个绿勾
        CheckinStore.saveGoals(this, goals, CheckinStore.goalsDate(this),
                CheckinStore.goalsAt(this));
        buzz(24);
        toast("已打卡 " + g.name + "  +" + fmt(amount) + " " + g.unit);
        android.util.Log.i(Guard.TAG, "checkin done: " + g.name + " +" + fmt(amount)
                + " " + g.unit + " → pending=" + pending());
        StudyGate.noteLocalPending(pending());
        pushAsync();
        syncGuard();
    }

    private void syncGuard() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Guard.apply(CheckinActivity.this);
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    private void askAmount(final CheckinStore.Goal g) {
        final EditText ed = new EditText(this);
        ed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ed.setText(fmt(suggestOf(g)));
        ed.setSelectAllOnFocus(true);
        ed.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this)
                .setTitle(g.name + " 记多少 " + g.unit + "？")
                .setView(ed)
                .setPositiveButton("打卡", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        double v;
                        try {
                            v = Double.parseDouble(ed.getText().toString().trim());
                        } catch (Throwable t) {
                            toast("数量填个数字");
                            return;
                        }
                        if (!(v > 0)) {
                            toast("数量要大于 0");
                            return;
                        }
                        if (g.hit()) {
                            toast("「" + g.name + "」今天已经打过了");
                            return;
                        }
                        record(g, v);
                        int next = nextPendingFrom(selected);
                        if (next >= 0) {
                            selected = next;
                        }
                        render();
                        enterFromUp();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ==================== 同步 ==================== */

    private void loadLocal() {
        goals.clear();
        if (local()) {
            goals.addAll(StudyStore.uiGoals(this));
        } else {
            goals.addAll(CheckinStore.goals(this));
            // 上次没推成功的记录还在本地：闸门那边也要知道，不然重启一次就又冻上了
            if (!CheckinStore.pending(this).isEmpty() && !goals.isEmpty()) {
                StudyGate.noteLocalPending(pending());
            }
        }
        selected = firstPending();
    }

    /** 本机有学习目标 → 手机就是学习台，整条链路不用联网。 */
    private boolean local() {
        return StudyStore.hasGoals(this);
    }

    private int indexOf(String id) {
        for (int i = 0; i < goals.size(); i++) {
            if (goals.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void refreshAsync() {
        if (local()) {
            // 没有"拉"这回事：重新读一遍本机文件就够了
            String keepId = current() == null ? "" : current().id;
            goals.clear();
            goals.addAll(StudyStore.uiGoals(this));
            int idx = indexOf(keepId);
            selected = idx >= 0 ? idx : firstPending();
            render();
            return;
        }
        final String prevDate = CheckinStore.goalsDate(this);
        CheckinSync.fetch(this, new CheckinSync.Sink() {
            public void onData(List<CheckinStore.Goal> fresh, String date, String at, Exception err) {
                if (fresh == null) {
                    if (goals.isEmpty()) {
                        topSub.setText("读不到数据 · " + brief(err));
                    }
                    return;
                }
                applyFresh(fresh, date, prevDate);
            }
        });
    }

    /** 换上新的目标列表（数据已经在 CheckinSync 里落过盘了）。 */
    private void applyFresh(List<CheckinStore.Goal> fresh, String date, String prevDate) {
        goals.clear();
        goals.addAll(fresh);
        if (!prevDate.equals(date) || selected < 0 || selected >= goals.size()) {
            selected = firstPending();
        }
        render();
        // 顺便把"PC 是否已经记账"对齐一次
        syncFlagsAsync();
    }

    private void pushAsync() {
        if (local()) {
            return;   // 本机就是源数据，没有什么要"推"的
        }
        final List<CheckinStore.Item> pend = CheckinStore.pending(this);
        if (pend.isEmpty()) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final String err = Gist.patchQueue(CheckinStore.token(CheckinActivity.this),
                        queueJson(pend));
                if (err == null) {
                    CheckinStore.markPushed(CheckinActivity.this, pend);
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        pushError = err;
                        // 只改状态行，不重建卡片 —— 重建会把正在拖的那张换掉
                        topSub.setText(statusLine());
                        if (err != null) {
                            toast("同步没成功：" + err + "（已存在手机上，会重试）");
                        }
                    }
                });
            }
        }).start();
    }

    /** 状态行：还剩几个待打卡 + 同步情况。 */
    private String statusLine() {
        int pending = pending();
        String head = goals.isEmpty() ? "还没有学习目标 · 点右上「管理」加一个"
                : (pending == 0 ? "今天的都打完了" : "还有 " + pending + " 个待打卡");
        if (local()) {
            return head + " · 本机";
        }
        int q = CheckinStore.pending(this).size();
        String sync;
        if (q > 0) {
            sync = "待记账 " + q + " 条";
        } else if (pushError != null) {
            sync = "同步失败";
        } else {
            String at = CheckinStore.goalsAt(this);
            sync = at.isEmpty() ? "" : "同步 " + at;
        }
        return sync.isEmpty() ? head : head + " · " + sync;
    }

    private void syncFlagsAsync() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONArray arr = new JSONObject(Gist.getQueue()).optJSONArray("items");
                    List<CheckinStore.Item> remote = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null) {
                                continue;
                            }
                            CheckinStore.Item it = new CheckinStore.Item();
                            it.id = o.optString("id", "");
                            remote.add(it);
                        }
                    }
                    final boolean changed = CheckinStore.markApplied(CheckinActivity.this, remote);
                    final boolean allApplied =
                            CheckinStore.pending(CheckinActivity.this).isEmpty();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (allApplied) {
                                // 队列空了 = PC 已经记完账，闸门可以回到"看学习台数据"那条线
                                StudyGate.noteLocalPending(-1);
                            }
                            if (changed && allApplied && !isFinishing()) {
                                // 账已经对齐了，本地的"已打卡"就没依据了：重新以远端为准
                                refreshAsync();
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    /**
     * 队列文件：除了打卡条目，还要带上「打完这些之后还剩几个目标」。
     *
     * ★ pendingAfter 是守卫立刻解闸的唯一凭据。
     *   只报"我打了几条"是不够的 —— 守卫不知道你还有几个目标没动，
     *   于是只能退化成"有一条就放行"，那等于打一个就全豁免，闸门就废了。
     */
    private String queueJson(List<CheckinStore.Item> items) {
        int pending = pending();
        StringBuilder sb = new StringBuilder(64 + items.size() * 120);
        sb.append("{\"date\":").append(Gist.quote(CheckinStore.today()))
                .append(",\"active\":").append(goals.size())
                .append(",\"pendingAfter\":").append(pending)
                .append(",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            CheckinStore.Item it = items.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"id\":").append(Gist.quote(it.id)).append(',')
                    .append("\"goalId\":").append(Gist.quote(it.goalId)).append(',')
                    .append("\"goal\":").append(Gist.quote(it.name)).append(',')
                    .append("\"unit\":").append(Gist.quote(it.unit)).append(',')
                    .append("\"amount\":").append(it.amount).append(',')
                    .append("\"date\":").append(Gist.quote(it.date)).append(',')
                    .append("\"ts\":").append(it.ts)
                    .append('}');
        }
        return sb.append("]}").toString();
    }

    /* ==================== 杂项 ==================== */

    private void askToken() {
        final EditText ed = new EditText(this);
        ed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ed.setHint("GitHub token（只授 gist 权限）");
        ed.setSingleLine(true);
        ed.setPadding(dp(20), dp(12), dp(20), dp(12));
        String cur = CheckinStore.token(this);
        if (!cur.isEmpty()) {
            ed.setText(cur);
        }
        new AlertDialog.Builder(this)
                .setTitle("同步凭据")
                .setMessage("打卡要写进 Gist 才能同步回电脑。\n粘一个只勾了 gist 权限的 token 即可。")
                .setView(ed)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        CheckinStore.setToken(CheckinActivity.this, ed.getText().toString());
                        toast("已保存");
                        pushAsync();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ==================== 目标管理（手机自己就是学习台时才有） ==================== */

    // 对话框是系统样式（白底），不跟随页面的深色主题 ——
    // 文字用页面变量的话，深色模式下近白的字打在白底上直接隐形。这里固定用深色。
    private static final int DLG_TEXT = 0xFF18191C;
    private static final int DLG_TEXT2 = 0xFF61666D;
    private static final int DLG_LINE = 0xFFE3E5E7;

    /** 管理入口：目标列表 + 新建 + 联动时段。目标一多也只是在对话框里滚。 */
    private void manage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(12));

        for (final CheckinStore.Goal g : goals) {
            TextView row = new TextView(this);
            StudyStore.G src = StudyStore.find(this, g.id);
            String due = src == null ? "" : src.due;
            row.setText(g.name + "（" + g.unit + "）\n"
                    + fmt(g.done) + " / " + fmt(g.total)
                    + (due.isEmpty() ? " · 不限期" : " · " + due + " 截止"));
            row.setTextColor(DLG_TEXT);
            row.setTextSize(14);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    manageOne(g.id);
                }
            });
            box.addView(row);
            box.addView(line());
        }

        TextView add = new TextView(this);
        add.setText("＋ 新目标");
        add.setTextColor(ACCENT);
        add.setTextSize(14);
        add.setPadding(0, dp(14), 0, dp(4));
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                askGoal(null);
            }
        });
        box.addView(add);

        TextView span = new TextView(this);
        String s = StudyStore.spanStart(this), e = StudyStore.spanEnd(this);
        span.setText("联动冻结时段：" + s + " – " + e);
        span.setTextColor(DLG_TEXT2);
        span.setTextSize(12);
        span.setPadding(0, dp(16), 0, dp(2));
        span.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                askSpan();
            }
        });
        box.addView(span);

        sc.addView(box);
        android.app.AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("学习目标")
                .setView(sc)
                .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int w) {
                        manageDialog = null;
                        loadLocal();
                        render();
                    }
                })
                .show();
        manageDialog = d;
    }

    /** 数据变了：把管理对话框关掉重开一份，列表才会反映最新状态。 */
    private void reopenManage() {
        if (manageDialog != null) {
            manageDialog.dismiss();
            manageDialog = null;
        }
        manage();
    }

    /** 单个目标的操作：编辑 / 删除。 */
    private void manageOne(final String id) {
        new AlertDialog.Builder(this)
                .setItems(new CharSequence[]{"编辑", "删除"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            askGoal(StudyStore.find(CheckinActivity.this, id));
                        } else {
                            StudyStore.remove(CheckinActivity.this, id);
                            refreshAsync();
                            reopenManage();
                            toast("已删除");
                        }
                    }
                })
                .show();
    }

    /** 新建 / 编辑一个目标。old 为 null 表示新建。 */
    private void askGoal(final StudyStore.G old) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), dp(4));

        final EditText name = field("名字，如：读论文", false);
        final EditText unit = field("单位（篇 / 小时 / 个）", false);
        final EditText total = field("总量", true);
        final EditText due = field("截止日 yyyy-MM-dd（空 = 不限期）", false);
        box.addView(name);
        box.addView(unit);
        box.addView(total);
        box.addView(due);

        if (old != null) {
            name.setText(old.name);
            unit.setText(old.unit);
            total.setText(old.total % 1 == 0
                    ? String.valueOf((long) old.total) : String.valueOf(old.total));
            due.setText(old.due);
        } else {
            due.setHint("截止日（空 = 30 天后自动）");
        }

        new AlertDialog.Builder(this)
                .setTitle(old == null ? "新目标" : "编辑目标")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        double t;
                        try {
                            t = Double.parseDouble(total.getText().toString().trim());
                        } catch (Throwable x) {
                            t = 0;
                        }
                        if (n.isEmpty() || !(t > 0)) {
                            toast("名字和总量都得填");
                            return;
                        }
                        StudyStore.G g = old == null ? new StudyStore.G() : old;
                        g.name = n;
                        g.unit = unit.getText().toString().trim();
                        if (g.unit.isEmpty()) {
                            g.unit = "个";
                        }
                        g.total = t;
                        String dd = due.getText().toString().trim();
                        g.due = dd.isEmpty() ? StudyStore.plusDays(30) : dd;
                        StudyStore.upsert(CheckinActivity.this, g);
                        refreshAsync();
                        reopenManage();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 联动冻结时段：目标没打完时，这个区间里延长冻结。 */
    private void askSpan() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(20), dp(6), dp(20), dp(4));
        final EditText start = field("22:00", false);
        final EditText end = field("23:00", false);
        start.setText(StudyStore.spanStart(this));
        end.setText(StudyStore.spanEnd(this));
        box.addView(start, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dash = new TextView(this);
        dash.setText(" – ");
        box.addView(dash);
        box.addView(end, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        new AlertDialog.Builder(this)
                .setTitle("联动冻结时段")
                .setMessage("学习目标没打完时，这个时间段里娱乐应用继续冻结。")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String s = start.getText().toString().trim();
                        String e = end.getText().toString().trim();
                        if (!s.matches("\\d{1,2}:\\d{2}") || !e.matches("\\d{1,2}:\\d{2}")) {
                            toast("时间写成 HH:mm");
                            return;
                        }
                        StudyStore.setSpan(CheckinActivity.this, s, e);
                        reopenManage();
                        toast("已保存");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(String hint, boolean number) {
        EditText ed = new EditText(this);
        ed.setHint(hint);
        ed.setSingleLine(true);
        if (number) {
            ed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }
        ed.setPadding(0, dp(10), 0, dp(10));
        return ed;
    }

    private View line() {
        View v = new View(this);
        v.setBackgroundColor(DLG_LINE);
        return v;
    }

    private CheckinStore.Goal current() {
        if (selected < 0 || selected >= goals.size()) {
            return null;
        }
        return goals.get(selected);
    }

    private int pending() {
        int n = 0;
        for (CheckinStore.Goal g : goals) {
            if (!g.hit()) {
                n++;
            }
        }
        return n;
    }

    private int firstPending() {
        for (int i = 0; i < goals.size(); i++) {
            if (!goals.get(i).hit()) {
                return i;
            }
        }
        return goals.isEmpty() ? -1 : 0;
    }

    /** 从 from 之后找下一个没打的；找不到返回 -1（都打完了，停在原地）。 */
    private int nextPendingFrom(int from) {
        for (int k = 1; k <= goals.size(); k++) {
            int i = (from + k) % goals.size();
            if (!goals.get(i).hit()) {
                return i;
            }
        }
        return -1;
    }

    static double suggestOf(CheckinStore.Goal g) {
        if (g.suggest > 0) {
            return g.suggest;
        }
        double remain = g.total - g.done;
        return remain > 0 ? Math.ceil(remain) : 1;
    }

    private static int pct(double done, double total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.floor(done * 100 / total);
    }

    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) {
            return String.valueOf((long) Math.rint(v));
        }
        String s = String.format(java.util.Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        if (m == null) {
            return e.getClass().getSimpleName();
        }
        return m.length() > 40 ? m.substring(0, 40) : m;
    }

    private void buzz(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, 140));
            } else {
                v.vibrate(ms);
            }
        } catch (Throwable ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** 供首页调用：带主题跳过来，免得从浅色页面点进去闪一下深色。 */
    static void open(Activity from, boolean dark) {
        Intent it = new Intent(from, CheckinActivity.class);
        it.putExtra(EXTRA_DARK, dark);
        from.startActivity(it);
    }
}
