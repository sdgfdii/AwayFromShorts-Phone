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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;

/**
 * 主界面：只看状态，不进列表。
 *
 * 版式照手机里「Voyage 工具箱」那套重排的：
 * 顶栏品牌 + 主题切换 → 问候大标题 + 日期 → 状态卡 → 打卡主卡（整块蓝）
 * → 「快捷」彩色图标格 → 结果卡 → 底部说明。
 * 视觉基线在 {@link Ui} 里，四个页面共用同一组色值和圆角。
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
public class MainActivity extends ThemedActivity {

    private static volatile boolean homeVisible;

    public static boolean isHomeVisible() {
        return homeVisible;
    }

    private Ui ui;

    private ScrollView scroll;
    private TextView subLine;
    private TextView greet;
    private TextView dateLine;
    private TextView statusText;
    private TextView statusChip;
    private long nextSwitchMinute = -1L;
    private String nextSwitchText = "—";
    private TextView lockNote;
    private TextView checkinTitle;
    private TextView checkinSub;
    private TextView foot;
    private LinearLayout checkinCard;
    private FrostView frost;
    private SplashView splash;

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
        ui = new Ui(this);

        scroll = new ScrollView(this);
        ui.page(scroll);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------- 顶栏：品牌 + 主题开关 ---------- */
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        subLine = new TextView(this);
        subLine.setText("AWAYFROMSHORTS");
        subLine.setTextSize(11);
        subLine.setLetterSpacing(0.18f);
        subLine.setTextColor(ui.text3());
        subLine.setTypeface(Typeface.DEFAULT_BOLD);
        SwissTypography.role(subLine,SwissTypography.Role.LABEL);
        bar.addView(subLine, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String themeMode = Ui.themeName(this);
        View themeBtn = ui.amap?new AmapStyle.Icon(this,"主题",ui.text()):ui.iconButton("auto".equals(themeMode) ? "◐"
                : (ui.dark ? "☀" : "☾"), ui.text2(), ui.surface());
        if(ui.amap){themeBtn.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(34),ui.dp(34)));themeBtn.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);}
        themeBtn.setContentDescription("主题设置");
        themeBtn.setOnClickListener(v -> startActivity(new Intent(this, ThemeActivity.class)));
        bar.addView(themeBtn);
        root.addView(bar);

        /* ---------- 问候 + 日期 ---------- */
        greet = ui.title(ui.amap?"守卫":"设置");
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = ui.dp(24);
        root.addView(greet, glp);

        dateLine = ui.sub("");
        dateLine.setTextSize(13);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = ui.dp(8);
        root.addView(dateLine, dlp);

        /* ---------- 状态卡 ---------- */
        LinearLayout statusCard = ui.card();
        LinearLayout.LayoutParams scLp = ui.gap(16);
        scLp.topMargin = ui.dp(24);
        root.addView(statusCard, scLp);

        LinearLayout statusHead = new LinearLayout(this);
        statusHead.setOrientation(LinearLayout.HORIZONTAL);
        statusHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = new TextView(this);
        dot.setText("◉");
        dot.setTextSize(10);
        dot.setTextColor(ui.accent());
        dot.setGravity(Gravity.CENTER);
        dot.setBackground(ui.round(ui.dark ? 0xFF1B2740 : 0xFFE7EEFC, 8f));
        statusHead.addView(dot, new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)));

        TextView st = new TextView(this);
        st.setText("运行状态");
        st.setTextSize(15);
        st.setTypeface(Typeface.DEFAULT_BOLD);
        st.setTextColor(ui.text());
        SwissTypography.role(st,SwissTypography.Role.SECTION);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stLp.leftMargin = ui.dp(9);
        statusHead.addView(st, stLp);

        statusChip = ui.chip("—", ui.text(), ui.surface2());
        statusHead.addView(statusChip);
        statusCard.addView(statusHead);

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setTextColor(ui.text2());
        statusText.setLineSpacing(ui.dp(5), 1f);
        statusText.setMaxLines(3);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = ui.dp(10);
        statusCard.addView(statusText, stlp);

        lockNote = new TextView(this);
        lockNote.setTextSize(12);
        lockNote.setTextColor(ui.red());
        lockNote.setLineSpacing(ui.dp(4), 1f);
        LinearLayout.LayoutParams lnlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lnlp.topMargin = ui.dp(10);
        statusCard.addView(lockNote, lnlp);

        statusCard.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, StatusActivity.class));
            }
        });

        /* ---------- 快捷：三格彩色图标 ---------- */
        LinearLayout sec = ui.section("▦", ui.accent(), "功能", null, null);
        LinearLayout.LayoutParams seclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seclp.topMargin = ui.dp(24);
        seclp.bottomMargin = ui.dp(12);
        root.addView(sec, seclp);

        // 四格排成 2×2：三格挤一行时 hint 会被截断，两行两列每个都读得全
        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout tManage = ui.tile("◎", Ui.TILE_TEAL, "目标管理", "增删 · 改默认量",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        Intent it = new Intent(MainActivity.this, CheckinActivity.class);
                        it.putExtra(CheckinActivity.EXTRA_OPEN, "manage");
                        startActivity(it);
                    }
                });
        LinearLayout tHist = ui.tile("▤", Ui.TILE_PURPLE, "打卡历史", "连续天数 · 统计",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, HistoryActivity.class));
                    }
                });
        // 备忘录是纯本机的速记，不参与冻结/打卡，所以只给一个入口，不占底栏
        LinearLayout tMemo = ui.tile("✎", Ui.TILE_RUST, "备忘录", "速记 · 置顶",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, MemoActivity.class));
                    }
                });
        // 工具箱同样纯本机：计时、抽签、文本整理，不联网
        LinearLayout tToolbox = ui.tile("▦", Ui.TILE_BLUE, "工具箱", "计时 · 抽签 · 文本",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, ToolboxActivity.class));
                    }
                });
        // 每行只有左格留 12dp 列缝；右格不再加边距，否则两行会差 12dp、右边缘对不齐
        LinearLayout.LayoutParams halfLeftTop = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        halfLeftTop.rightMargin = ui.dp(12);
        LinearLayout.LayoutParams halfRight = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams halfLeftBottom = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        halfLeftBottom.rightMargin = ui.dp(12);
        firstRow.addView(tManage, halfLeftTop);
        firstRow.addView(tHist, halfRight);
        secondRow.addView(tMemo, halfLeftBottom);
        secondRow.addView(tToolbox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(firstRow);
        LinearLayout.LayoutParams secondLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secondLp.topMargin = ui.dp(12);
        root.addView(secondRow, secondLp);

        foot = new TextView(this);
        foot.setText("移除守卫只有一条路：\n"
                + "adb shell dpm remove-active-admin com.afs.guard/.AfsAdmin");
        foot.setTextSize(11);
        foot.setTextColor(ui.text3());
        foot.setLineSpacing(ui.dp(3), 1f);
        foot.setPadding(0, ui.dp(20), 0, 0);
        if(ui.amap)foot.setVisibility(View.GONE);
        root.addView(foot);

        // 薄霜层盖在内容上面：全屏蔽时有"被冻住"的体感，平时不画
        final android.widget.FrameLayout shell = new android.widget.FrameLayout(this);
        shell.addView(scroll);
        frost = new FrostView(this);
        frost.setVisibility(View.GONE);
        shell.addView(frost);

        // 外面再套一层：加载动画盖在最上面，首页在下面等着，两者互不干扰
        android.widget.FrameLayout host = new android.widget.FrameLayout(this);
        host.setBackgroundColor(ui.bg());
        host.addView(shell);
        root.setPadding(root.getPaddingLeft(),root.getPaddingTop(),root.getPaddingRight(),root.getPaddingBottom()+ui.dp(94));
        setContentView(FloatingNav.wrap(this, host, 4));
        immersive();
        Guard.ensureService(this);   // 每次打开 App 顺手保证常驻守护在跑
        Gist.seedToken(this); // 带 token 的构建第一次打开就落盘
        if (RuleStore.alignRequestedRules(this)) {
            new Thread(new Runnable() {
                public void run() { Guard.apply(MainActivity.this); }
            }, "afs-rule-correction").start();
        }
        Hub.kick(this);              // Gist 中心仓库同步（节流，脏标记优先）
        render();

        // 只有冷启动才播加载动画：转屏、从后台切回来都不该再挡一次
        android.util.Log.i(Guard.TAG, "main: savedInstanceState="
                + (savedInstanceState == null ? "null(冷启动)" : "非空"));
        if (savedInstanceState == null && !getIntent().getBooleanExtra("from_bottom_nav", false)) {
            shell.setAlpha(0f);
            splash = new SplashView(this);
            host.addView(splash, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            splash.play(new SplashView.Done() {
                public void onDone() {
                    handOver(shell);
                }
            });
        } else {
            contentIn(shell);
        }
    }

    /** 首页入场：从下方一点升起来 + 淡入（原来是整页从顶上滑进来，太"抽屉"了）。 */
    private void contentIn(View shell) {
        shell.setTranslationY(ui.dp(26));
        shell.setAlpha(0f);
        shell.animate().translationY(0f).alpha(1f)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .setDuration(230)
                .start();
    }

    /**
     * 加载动画收尾：一进一出同时交接 —— 它淡出并轻微放大，首页同时升起来。
     * 两段叠在一起才不会看到"先黑一下再出现"的空档。
     */
    private void handOver(final View shell) {
        final SplashView s = splash;
        splash = null;
        contentIn(shell);
        if (s == null) {
            return;
        }
        s.animate().alpha(0f).scaleX(1.10f).scaleY(1.10f)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                .setDuration(220)
                .withEndAction(new Runnable() {
                    public void run() {
                        ViewGroup p = (ViewGroup) s.getParent();
                        if (p != null) {
                            p.removeView(s);
                        }
                    }
                })
                .start();
    }

    /** 按当前时辰打招呼 —— Voyage 首页也是这么起的。 */
    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 6) {
            return "夜深了";
        }
        if (h < 11) {
            return "早上好";
        }
        if (h < 14) {
            return "中午好";
        }
        if (h < 18) {
            return "下午好";
        }
        return "晚上好";
    }

    /**
     * 藏掉状态栏（时间/电量那一条）：沉浸式，从顶边下滑才临时现形，几秒后自动再藏。
     * IMMERSE_STICKY = 现形不重排布局；LAYOUT_* 让内容顶到状态栏原来的位置。
     */
    private void immersive() {
        // 让 App 自己画系统栏底色（不然 ColorOS 在状态栏原位置画一条黑）
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(0x00000000);   // 透明：位置让给 window 背景自己铺
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(ui.bg()));
        // 经典窗口级全屏：窗口本体涨进状态栏原来占的位置，不留黑带
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 顶部黑带真凶是刘海/挖孔：默认布局不进 cutout 区，放行 SHORT_EDGES 才贴到顶
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams
                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        homeVisible = true;
        GrayMode.apply(this, false);
        if (ui.dark != Ui.isDark(this)) {
            recreate();
            return;
        }
        immersive();      // 从别的页面/别的 App 回来时状态栏可能又冒出来，重新藏
        nextSwitchMinute = -1L;
        handler.removeCallbacks(tick);
        handler.post(tick);
        // 状态刷新和同步都放到首帧之后：切页那一帧只负责把页面画出来
        handler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            render();
            Hub.kick(MainActivity.this);   // 常驻进程不会反复 onCreate，只有这里能保证周期同步
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        homeVisible = false;
        int state = StudyGate.cardState(this, Calendar.getInstance());
        GrayMode.apply(this, state == StudyGate.ST_ACTIVE || state == StudyGate.ST_PENDING);
        handler.removeCallbacks(tick);
    }

    /* ==================== 渲染 ==================== */

    private void render() {
        boolean owner = Guard.isOwner(this);
        boolean dark = Guard.allBlocked(this);
        boolean grace = Config.inGrace(this);
        int total = RuleStore.count(this);

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

        Calendar now = Calendar.getInstance();
        // 每秒都跑 render，但日期一天只变一次：不判一下就等于每秒触发一次布局
        String day = new java.text.SimpleDateFormat("M月d日 EEEE",
                java.util.Locale.CHINA).format(now.getTime());
        if (!day.equals(lastDay)) {
            lastDay = day;
            dateLine.setText(day);
        }

        if (owner) {
            statusChip.setText("运行中");
            statusChip.setTextColor(ui.dark ? 0xFF66D9A0 : 0xFF0E8A50);
            statusChip.setBackground(ui.round(ui.dark ? 0xFF16302A : 0xFFE3F7EC, 99f));
        } else {
            statusChip.setText("未启用");
            statusChip.setTextColor(ui.dark ? 0xFFFF9A94 : 0xFFC0392B);
            statusChip.setBackground(ui.round(ui.dark ? 0xFF37212A : 0xFFFCE9E8, 99f));
        }

        long minute = now.getTimeInMillis() / 60000L;
        if (minute != nextSwitchMinute) {
            nextSwitchMinute = minute;
            long flip = total == 0 ? 0L : Guard.nextFlipMillis(this);
            nextSwitchText = flip == 0L ? "—" :
                    new java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.CHINA)
                            .format(new java.util.Date(flip));
        }
        statusText.setText("应用 " + total + " 个 · 冻结 " + Guard.blockedCount(this) + " 个\n"
                + "关键应用 " + RuleStore.keyApps(this).size() + " 个 · "
                + (owner ? "加固已开启" : "未取得设备所有者权限") + "\n"
                + "下次切换 " + nextSwitchText);
        statusText.setTextColor(owner ? ui.text2() : ui.red());

        if (dark && !grace) {
            lockNote.setVisibility(View.VISIBLE);
            lockNote.setText("全部应用都在屏蔽中：现在改不了，到点自动解锁。没有解除按钮。");
        } else if (grace) {
            lockNote.setVisibility(View.VISIBLE);
            lockNote.setText("首次使用：现在可以配置，在冻结应用的设置里点「立即应用」后按锁定规则执行。");
        } else {
            lockNote.setVisibility(View.GONE);
        }

        if (frost != null) {
            frost.setVisibility(dark && !grace ? View.VISIBLE : View.GONE);
        }
        // 「今天还没学完」不用通知催 —— 整页变灰（健康使用手机那套），
        // 打完自动恢复彩色。比通知安静，但一眼就能看见。
        applyGray(false);

    }

    /**
     * 打卡卡：把"还剩几个目标"直接写在卡的副标题上。
     * 数字来自上一次同步到本地的目标（不额外打网络），比单独一行说明有用得多 ——
     * 没打卡的时候一打开 App 就能看见还欠几个。
     *
     * 学习联动也并到这里了（原来它是一张独立的卡，点进去是同一个页面）：
     * 副标题会带「冻结中」/「⚠ 联动过期」/「等记账」，因为"现在到底冻不冻"
     * 是打完卡之后唯一还想知道的事。
     */
    private String lastCheckinLabel = "";
    private Boolean lastCheckinComplete;

    private void styleCheckin() {
        if (checkinCard == null) {
            return;
        }
        // 手机自己就是学习台：数字直接从本机 study.json 算，没有远端这回事
        int pend = StudyStore.pendingToday(this);
        // 禁忌卡已经在打卡队列里了（每天第一张），它没答完就不能说"今天已经打完"
        int tp = StudyGate.tabooPendingToday(this);

        // 文案拼装在 StudyGate.checkinLabel 里（纯函数，离线有断言）
        Calendar now = Calendar.getInstance();
        int st = StudyGate.cardState(this, now);
        boolean hold = StudyGate.activeNow(this, now);
        String label = StudyGate.checkinLabel(pend, tp, st, hold);
        if (label.startsWith("打卡 · ")) {
            label = label.substring(5);
        } else if ("打卡".equals(label)) {
            label = "上滑卡片记一笔，今天还什么都没记";
        }
        if (!label.equals(lastCheckinLabel)) {
            lastCheckinLabel = label;
            checkinSub.setText(label);
        }
        boolean complete = StudyStore.hasGoals(this) && pend == 0 && tp == 0;
        if (lastCheckinComplete == null || complete != lastCheckinComplete.booleanValue()) {
            lastCheckinComplete = complete;
            checkinCard.setBackground(ui.round(complete ? 0xFF16824B : 0xFFC83542, 18f));
        }
    }

    private void openCheckin() {
        CheckinActivity.open(this, ui.dark);
    }

    private android.graphics.Paint grayPaint;

    /**
     * 灰度开关：整个窗口套一层饱和度 0 的硬件层。
     * 不弹通知、不响铃 —— 「今天还没学完」这件事让颜色自己说：
     * 页面是灰的 = 还有目标没打卡；页面恢复彩色 = 打完了。
     */
    private void applyGray(boolean gray) {
        View decor = getWindow().getDecorView();
        if (gray) {
            if (grayPaint == null) {
                grayPaint = new android.graphics.Paint();
                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                cm.setSaturation(0f);
                grayPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            }
            decor.setLayerType(View.LAYER_TYPE_HARDWARE, grayPaint);
        } else {
            decor.setLayerType(View.LAYER_TYPE_NONE, null);
        }
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
    private String lastDay = "";

    /* ---------- 小工具 ---------- */

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }
}
