package com.afs.guard;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 打卡页：一屏一张竖着的卡，往上平移推出去就记一笔，左右推换目标。
 *
 * 为什么是"推"而不是"按"：点按钮和点卡片没有本质区别，但推是**有方向的**动作 ——
 * 手指一走就知道自己在提交，走到一半还能反悔（没推够松手会弹回去）。
 * 一张卡占满屏，也是为了让"今天还剩几个"这件事一眼能看见，
 * 而不是在一列小横条里找。
 *
 * 三个手势各管一件事，互不抢：
 *   上推  = 做了 / 打卡（禁忌黑卡 = 认领，白卡 = 记一笔）
 *   下滑  = 没做（只有禁忌黑卡认这个动作；白卡下滑没含义）
 *   左右推 = 换卡（不打）
 *   长按  = 改这一笔的数量（黑卡 = 再认一次）
 *
 * 卡组顺序是硬口径：**禁忌黑卡永远在队首**（每天一张，先问犯忌没有），
 * 目标白卡跟在后面。两张卡一个黑一个白，一眼能分清自己在答哪一种。
 *
 * 数据流（手机就是学习台，只有一条线）：
 *   上推记一笔 -> StudyStore 落本机 study.json -> 闸门立刻重算 + Hub 打脏标记
 * 打完那一刻本机数据就变了，页面和闸门同时刷新；上传云端是后台顺带做的事
 * （Hub 推 checkin-ledger.json，PC 读它）。
 */
public class CheckinActivity extends Activity {

    /** 主题跟着首页走：全部屏蔽时是深色，否则浅色。 */
    public static final String EXTRA_DARK = "dark";
    /** 从外面直接开功能：manage=目标管理，token=同步凭据（打卡页本体只留卡片）。 */
    public static final String EXTRA_OPEN = "open";

    private static final int DIR_NONE = 0;
    private static final int DIR_VERT = 1;
    private static final int DIR_HORIZ = 2;

    /* SAF 请求码：备份导出 / 恢复导入 / 字体文件 */
    private static final int RC_EXPORT = 41;
    private static final int RC_IMPORT = 42;
    private static final int RC_BG = 43;
    private static final int RC_FONT = 45;

    /** Voyage 那套蓝：深色底上比原来的青色克制，也和首页主卡同一个色。 */
    private static final int ACCENT = 0xFF3D7BF5;
    private static final int RED_L = 0xFFF85A54;
    private static final int RED_D = 0xFFFF7B72;
    private static final int GREEN_L = 0xFF12A150;
    private static final int GREEN_D = 0xFF3DDC84;

    private boolean dark;
    /** 主题与配色基线（深色为主，浅色可切）。 */
    private Ui look;
    private int bg, cardBg, ghostBg, textC, text2C, text3C, lineC, trackC, greenC, redC;

    private final List<Goal> goals = new ArrayList<>();
    /**
     * 卡组：禁忌卡（黑）在前，目标卡（白）在后 —— 禁忌卡每天都有，
     * 所以永远排第一张，先问"今天犯忌没有"，再走今天的目标。
     * {@link #selected} 是**这个队列**的下标，不再是 goals 的下标。
     */
    private final List<Entry> cards = new ArrayList<>();
    private int selected = -1;

    /** 卡组里的一项：禁忌黑卡 或 目标白卡。 */
    private static final class Entry {
        static final int TABOO = 0;
        static final int GOAL = 1;
        final int type;
        final String taboo;              // TABOO：禁忌项名字
        final Goal goal;    // GOAL：目标
        final int gi;                    // GOAL：goals 里的下标（改数量等回查用）

        Entry(int type, String taboo, Goal goal, int gi) {
            this.type = type;
            this.taboo = taboo;
            this.goal = goal;
            this.gi = gi;
        }
    }

    /* 黑卡配色：卡面是黑的（不跟主题走），字必须是亮的 */
    private static final int TABOO_CARD = 0xFF0C0C0E;
    private static final int TABOO_CARD_DARK = 0xFF141417;
    private static final int TABOO_LINE = 0xFF2C2C31;
    private static final int TABOO_TEXT = 0xFFF2F5FA;
    private static final int TABOO_TEXT2 = 0xFF9A9AA6;

    private FrameLayout stage;
    private FrameLayout pageHost;
    private LinearLayout dots;
    /** 亚克力底：页面最底层的模糊图 + 一层压色调的蒙版。 */
    private ImageView backdropView;
    private View backdropTint;
    private LinearLayout rootBox;
    private TextView topSub;
    private TextView footHint;
    private TextView manageBtn;
    /** 打开着的「学习目标」管理对话框；增删目标后关掉重开，列表才是新的。 */
    private android.app.Dialog manageDialog;
    private boolean reopeningManage;
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
            Entry e = entryAt(selected);
            if (e == null) {
                return;
            }
            if (e.type == Entry.TABOO) {
                // 一天犯两次忌（两顿辣）真会发生：长按黑卡再认一次（叠加）
                askClaimTabooAgain(e.taboo);
            } else if (e.goal != null) {
                askAmount(e.goal);
            }
        }
    };

    /** 推送失败的原因（null = 没失败）。 */

    /* ==================== 生命周期 ==================== */

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dark = Ui.isDark(this);   // 主题是全局设置：不再跟着首页那张"是否全部屏蔽"走
        look = new Ui(this);
        pickColors();
        Gist.seedToken(this);   // 带 token 的构建：第一次进来就落盘
        buildUi();
        applyStatusBar();
        loadLocal();
        render();
        refreshAsync();
        Hub.kick(this);   // 进页面顺手把台账推一下（有节流，脏标记优先）
        // 别的页面直接点名要某个功能（打卡页本体已经收成纯卡片）
        String open = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_OPEN);
        if ("manage".equals(open)) {
            rootBox.setVisibility(View.INVISIBLE);
            pageHost.setBackgroundColor(manageBg());
            backdropView.setVisibility(View.GONE);
            backdropTint.setVisibility(View.GONE);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(manageBg()));
            manage();
        } else if ("taboo".equals(open)) {
            tabooManage();
        } else if ("token".equals(open)) {
            askToken();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dark != Ui.isDark(this)) {
            recreate();
            return;
        }
        applyStatusBar();   // 从别的页面回来时状态栏可能又冒头，重新藏
        refreshAsync();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(longPress);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopHover();
    }

    /**
     * 沉浸式：状态栏（时间/电量）整个藏掉，页面贴到屏顶 —— 跟主页同款。
     * 坑都是主页踩过的：DRAWS_SYSTEM_BAR + 透明防 ColorOS 黑条，
     * cutout SHORT_EDGES 防刘海区黑带，window 底色跟页面背景。
     */
    private void applyStatusBar() {
        try {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(0x00000000);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(bg));
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Throwable ignored) {
        }
    }

    private void pickColors() {
        // 直接用全 App 那套基线：打卡页以前自己定义了一份，和首页差半个色阶，
        // 两页互相跳的时候底色的差别看得出来。
        bg = look.bg();
        cardBg = dark ? 0xFF303744 : look.surface();
        ghostBg = dark ? 0xFF1B2028 : look.surface2();
        // 开了亚克力底之后，卡片要半透明才透得出后面的图 —— 那才是"亚克力"。
        // 不透明的话背景只是贴在底下的一张壁纸，一点材质感都没有。
        if (Backdrop.has(this)) {
            cardBg = (cardBg & 0x00FFFFFF) | 0xB8000000;
            ghostBg = (ghostBg & 0x00FFFFFF) | 0x99000000;
        }
        textC = look.text();
        text2C = look.text2();
        text3C = look.text3();
        lineC = look.line();
        trackC = dark ? 0xFF23262D : 0xFFEDEFF2;
        greenC = look.green();
        redC = look.red();
        // 用户自定义打卡字体颜色：主文字一档全换，次级/三级用同色降透明度，
        // 保持"已完成置灰"的层次感；✓/数量/提示等状态色（ACCENT/greenC/redC）不动。
        int fc = ui().getInt("font_color", 0);
        if (fc != 0) {
            textC = fc;
            text2C = (fc & 0x00FFFFFF) | 0x99000000;
            text3C = (fc & 0x00FFFFFF) | 0x66000000;
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
        head.setPadding(dp(18), dp(14), dp(14), dp(2));

        topSub = new TextView(this);
        topSub.setTextColor(text3C);
        topSub.setTextSize(12);
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

        // 顶栏原来有一颗「禁忌」：禁忌的增删改是低频设置，已经挪到
        // 主页「目标管理 → 禁忌卡（黑卡）设置与认领」里，这里不再占位置。
        // 这颗换成了「背景」—— 亚克力底（自定义图片）本来就是这一页的事。
        TextView bgBtn = pill("背景");
        bgBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                backdropMenu();
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(6);
        head.addView(bgBtn, tlp);

        // 手动上传：平时打卡落盘就会打脏标记、Hub 立刻推一份；这颗是"我现在就要确认"的入口。
        final TextView upload = pill("上传");
        upload.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                upload.setEnabled(false);
                upload.setText("上传中");
                new Thread(new Runnable() {
                    public void run() {
                        final boolean ok = Hub.pushNowSync(CheckinActivity.this);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                upload.setEnabled(true);
                                upload.setText("上传");
                                if (ok) {
                                    topSub.setText(statusLine());
                                    toast("已上传 " + hmOf(Hub.lastPushAt(CheckinActivity.this))
                                            + " · rev " + Hub.ledgerRev(CheckinActivity.this));
                                } else {
                                    toast("上传失败，检查网络后重试");
                                }
                            }
                        });
                    }
                }, "afs-checkin-upload").start();
            }
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.leftMargin = dp(6);
        head.addView(upload, ulp);

        // 不再放「刷新」：本机模式没有远端可拉，进页面 / 回到页面都会自动重读，
        // 多一颗按钮只会把状态行挤成"还有 3 个待打卡 · 禁忌…"这种半截话。
        // 数据在盘上，页面自己会跟上。

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
        // 主页已经把「目标管理 / 打卡历史」做成常驻按钮了，这一页不再重复
        manageBtn.setVisibility(View.GONE);

        // 打卡历史：连续天数 / 30 天格子 / 目标进度 —— 本机模式才说得上有"记录"
        TextView histBtn = new TextView(this);
        histBtn.setText("历史");
        histBtn.setTextColor(ACCENT);
        histBtn.setTextSize(12);
        histBtn.setPadding(dp(12), dp(6), dp(8), dp(6));
        histBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(CheckinActivity.this, HistoryActivity.class));
            }
        });
        head.addView(histBtn);
        histBtn.setVisibility(View.GONE);
        // head（状态/禁忌/刷新）挂上去：禁忌归打卡管，入口就得在打卡页里。
        // 管理目标 / 历史仍然只在主页出现（manageBtn、histBtn 常驻 GONE），
        // 这页除了卡片只多这一行 11~12sp 的元信息，不抢卡片的视觉重心。
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(head, hlp);

        // ---- 卡片舞台：悬浮在更暗的底色上，立体感从这里来 ----
        int pageBg = dark ? 0xFF232A34 : 0xFFE2E5EA;
        // 开了亚克力底，页面自身就不能再铺死色 —— 让底下的模糊图透上来。
        root.setBackgroundColor(Backdrop.has(this) ? Color.TRANSPARENT : pageBg);
        stage = new FrameLayout(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.setMargins(dp(14), dp(12), dp(14), dp(20));   // 四周留空，投影才看得见
        root.addView(stage, slp);
        root.setClipChildren(false);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);

        // ---- 底部：手势提示 + 进度点（只构建不挂载，字段引用保持安全） ----
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(16), dp(2), dp(16), dp(10));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        foot.addView(dots);

        footHint = new TextView(this);
        footHint.setText("上滑平移打卡 · 左右推换目标 · 长按改数量");
        footHint.setTextColor(text3C);
        footHint.setTextSize(10);
        footHint.setGravity(Gravity.CENTER);
        footHint.setPadding(0, dp(5), 0, 0);
        foot.addView(footHint);

        // ---- 亚克力底：整页最底层铺一张重度模糊的自定义图 ----
        // 三层结构（图 → 压色蒙版 → 页面内容）：
        //   模糊图负责"材质"，蒙版负责压暗好让字看得清，卡片自己半透明叠在上面。
        // 整页只有这一张背景，所以图糊到什么程度都不会喧宾夺主。
        FrameLayout host = new FrameLayout(this);
        pageHost = host;
        host.setBackgroundColor(pageBg);

        backdropView = new ImageView(this);
        backdropView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdropView.setVisibility(Backdrop.has(this) ? View.VISIBLE : View.GONE);
        host.addView(backdropView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        backdropTint = new View(this);
        // 蒙版：深色主题压黑一点，浅色主题压白一点，都是为了让卡片上的字有对比
        backdropTint.setBackgroundColor(dark ? 0xB3000000 : 0x99FFFFFF);
        backdropTint.setVisibility(Backdrop.has(this) ? View.VISIBLE : View.GONE);
        host.addView(backdropTint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootBox = root;
        host.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(host);
        loadBackdrop();
    }

    /**
     * 生成并铺上亚克力底图。
     *
     * 模糊很吃 CPU（大图 + 连做三遍 box blur），一定放后台线程：
     * 在主线程做这一下会让进页面卡住半秒以上。
     */
    private void loadBackdrop() {
        if (!Backdrop.has(this) || backdropView == null) {
            return;
        }
        final int w = getResources().getDisplayMetrics().widthPixels;
        final int h = getResources().getDisplayMetrics().heightPixels;
        new Thread(new Runnable() {
            public void run() {
                final Bitmap bmp = Backdrop.build(CheckinActivity.this, w, h);
                if (bmp == null) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (backdropView != null) {
                            backdropView.setImageBitmap(bmp);
                        }
                    }
                });
            }
        }, "afs-backdrop").start();
    }

    /** 亚克力底的选择菜单：无 / 内置 / 从相册选一张。 */
    private void backdropMenu() {
        final String cur = Backdrop.src(this);
        String[] items = {
                "无背景（纯色）",
                "内置亚克力底",
                "从相册选一张…",
                "清除自定义图片"
        };
        new AlertDialog.Builder(this)
                .setTitle("打卡页背景")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            Backdrop.setSrc(CheckinActivity.this, Backdrop.NONE);
                            toast("已关掉背景");
                        } else if (w == 1) {
                            Backdrop.setSrc(CheckinActivity.this, Backdrop.DEFAULT);
                            toast("已换成内置亚克力底");
                        } else if (w == 2) {
                            pickBackdrop();
                            return;
                        } else {
                            if (!Backdrop.CUSTOM.equals(cur)) {
                                toast("现在用的不是自定义图片");
                                return;
                            }
                            Backdrop.clear(CheckinActivity.this);
                            toast("已清除自定义图片");
                        }
                        reloadBackdrop();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 打开系统选图。取到的是 Uri，得立刻复制一份进私有目录（权限随时会过期）。 */
    private void pickBackdrop() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, RC_BG);
        } catch (Throwable t) {
            try {
                Intent g = new Intent(Intent.ACTION_GET_CONTENT);
                g.setType("image/*");
                startActivityForResult(g, RC_BG);
            } catch (Throwable t2) {
                toast("这台机器没有能选图片的程序");
            }
        }
    }

    /** 背景换了：颜色（卡片要不要半透明）和底图都得重算一遍。 */
    private void reloadBackdrop() {
        pickColors();
        if (backdropView != null) {
            backdropView.setVisibility(Backdrop.has(this) ? View.VISIBLE : View.GONE);
            backdropView.setImageBitmap(null);
        }
        if (backdropTint != null) {
            backdropTint.setVisibility(Backdrop.has(this) ? View.VISIBLE : View.GONE);
        }
        if (rootBox != null) {
            rootBox.setBackgroundColor(Backdrop.has(this)
                    ? Color.TRANSPARENT : (dark ? 0xFF08090B : 0xFFE2E5EA));
        }
        loadBackdrop();
        render();
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

        // 卡组可能因为「禁忌卡设置」变了：每次重排都按最新的一份重建。
        // 重建前先记住当前这张是谁 —— 增删禁忌项会让队列整体前移，只按下标恢复会看到别的卡
        String keepKey = entryKey(entryAt(selected));
        rebuildCards();
        if (keepKey != null) {
            int k = indexOfKey(keepKey);
            if (k >= 0) {
                selected = k;
            }
        }

        if (cards.isEmpty()) {
            topSub.setText(goals.isEmpty()
                    ? "还没有学习目标"
                    : "今天的卡都处理完了");
            stage.addView(emptyCard());
            footHint.setText("回主页点「目标管理」加一个目标");
            return;
        }

        if (selected < 0 || selected >= cards.size()) {
            selected = firstPendingCard();
        }
        topSub.setText(statusLine());

        // 今天全处理完了：台上什么都不留（没有完成卡、没有照片），只有底部一行绿字
        if (pendingCards() == 0) {
            renderDots();
            footHint.setText("今天的卡都处理完了 · 已存本机，点右上「上传」推云端");
            footHint.setTextColor(greenC);
            return;
        }

        // 后面那张"下一张卡"：只做视觉纵深，不承载内容
        ghost = new View(this);
        GradientDrawable gb = round(ghostBg, dp(22));
        gb.setStroke(dp(1), lineC);
        ghost.setBackground(gb);
        floatify(ghost, dp(4));   // 空壳也在浮着，只是比真卡低一层
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        glp.setMargins(dp(24), dp(22), dp(24), dp(22));
        stage.addView(ghost, glp);
        ghost.setScaleX(0.94f);
        ghost.setScaleY(0.94f);
        ghost.setTranslationY(dp(12));
        ghost.setAlpha(0.6f);

        card = buildEntryCard(cards.get(selected), selected);
        floatify(card, dp(20));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        clp.setMargins(dp(18), dp(18), dp(18), dp(18));
        stage.addView(card, clp);
        startHover();
        if (animateIn) {
            card.setTranslationY(dp(56));
            card.setAlpha(0f);
            card.animate().translationY(0).alpha(1f)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .setDuration(210).start();
        }

        renderDots();
        footHint.setText("上滑＝做了 · 下滑＝没做（禁忌卡）· 左右推换卡");
        footHint.setTextColor(text3C);
    }

    /** 按队列项分派：黑卡走禁忌那套版式，白卡走目标那套。 */
    private View buildEntryCard(Entry e, int index) {
        return e.type == Entry.TABOO ? buildTabooCard(e, index) : buildCard(e.goal, index);
    }

    /**
     * @param index 这张卡显示"第几张"。预摆的下一张不是当前下标，
     *              所以不能直接用 {@link #selected} —— 否则角标会写错。
     */
    private View buildCard(final Goal g, int index) {
        boolean done = g.hit();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(22), dp(24), dp(22));
        box.setBackground(cardBackground(done));

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
        idx.setText((index + 1) + " / " + cards.size());
        idx.setTextColor(text3C);
        idx.setTextSize(11);
        top.addView(idx);
        box.addView(top);

        // ---- 目标名 ----
        TextView name = new TextView(this);
        name.setText(g.name);
        name.setTextColor(done ? text2C : textC);
        name.setTextSize(30);
        name.setTypeface(bold());
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
            ok.setTypeface(bold());
            // 撤销入口在主页「打卡历史」里（点流水撤销）—— 卡上不放可点的东西，误触面为零
            amountRow.addView(ok);
        } else {
            TextView num = new TextView(this);
            num.setText("+" + fmt(defaultAmount(g)));
            num.setTextColor(ACCENT);
            num.setTextSize(44);
            num.setTypeface(bold());
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
        cardHint.setText(done ? "已打卡 · 左右推换目标" : "");
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
     * 禁忌卡（黑卡）：卡面是黑的，每天一张，永远排在队首。
     * 上滑 ＝ 今天做了（认领，冻结延长）· 下滑 ＝ 今天没做（放行，不延长）。
     */
    private View buildTabooCard(Entry e, int index) {
        final String name = e.taboo;
        boolean claimed = tabooClaimedToday(name);
        boolean declined = !claimed && tabooDeclinedToday(name);
        int min = tabooPenaltyMin(this);
        long until = StudyGate.tabooUntil(this);
        boolean holding = StudyGate.tabooHolding(System.currentTimeMillis(), until);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(22), dp(24), dp(22));
        GradientDrawable bgd = round(dark ? TABOO_CARD_DARK : TABOO_CARD, dp(22));
        bgd.setStroke(dp(1), claimed ? 0xFF2E7D5B : TABOO_LINE);
        box.setBackground(bgd);

        // ---- 顶行 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView tag = new TextView(this);
        tag.setText("禁忌卡 · 每天一张");
        tag.setTextColor(TABOO_TEXT2);
        tag.setTextSize(11);
        tag.setLetterSpacing(0.14f);
        top.addView(tag, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView idx = new TextView(this);
        idx.setText((index + 1) + " / " + cards.size());
        idx.setTextColor(TABOO_TEXT2);
        idx.setTextSize(11);
        top.addView(idx);
        box.addView(top);

        // ---- 禁忌名 ----
        TextView nameTv = new TextView(this);
        nameTv.setText(name);
        nameTv.setTextColor(claimed ? 0xFF9BE7C4 : TABOO_TEXT);
        nameTv.setTextSize(30);
        nameTv.setTypeface(bold());
        nameTv.setMaxLines(2);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        nameTv.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(14);
        box.addView(nameTv, nlp);

        TextView meta = new TextView(this);
        meta.setText(holding
                ? "惩罚期至 " + hmOf(until) + " · 认领再加 " + min + " 分钟"
                : "认领一次 · 冻结延长 " + min + " 分钟");
        meta.setTextColor(TABOO_TEXT2);
        meta.setTextSize(12);
        meta.setPadding(0, dp(6), 0, 0);
        box.addView(meta);

        // ---- 中间：一个「禁」字印章，黑卡的身份 ----
        LinearLayout stampWrap = new LinearLayout(this);
        stampWrap.setGravity(Gravity.CENTER);
        stampWrap.addView(tabooStamp(claimed, declined),
                new LinearLayout.LayoutParams(dp(178), dp(178)));
        box.addView(stampWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 今天的状态 ----
        TextView state = new TextView(this);
        state.setTextSize(24);
        state.setTypeface(bold());
        state.setGravity(Gravity.CENTER);
        if (claimed) {
            state.setText("✓ 今天认了");
            state.setTextColor(greenC);
        } else if (declined) {
            state.setText("今天没做");
            state.setTextColor(0xFFB9BAC3);
        } else {
            // 还没答这一问：手势就是答案（惩罚期的截止时间在上面 meta 行）
            state.setText("↑ 做了    ↓ 没做");
            state.setTextColor(TABOO_TEXT);
        }
        box.addView(state);

        TextView sub = new TextView(this);
        sub.setTextSize(11);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, 0);
        if (claimed) {
            sub.setText("冻结延长至 " + hmOf(until) + " · 长按可再认一次");
            sub.setTextColor(TABOO_TEXT2);
        } else if (declined) {
            sub.setText("今天没做 · 明天照常出现");
            sub.setTextColor(TABOO_TEXT2);
        } else {
            sub.setText("上滑＝今天做了 · 下滑＝今天没做");
            sub.setTextColor(TABOO_TEXT2);
        }
        box.addView(sub);

        // ---- 手势提示（跟着卡一起动） ----
        cardHint = new TextView(this);
        cardHint.setText(claimed ? "已认领 · 左右推换卡" : "");
        cardHint.setTextColor(claimed ? TABOO_TEXT2 : 0xFFFFFFFF);
        cardHint.setTextSize(13);
        cardHint.setGravity(Gravity.CENTER);
        cardHint.setPadding(0, dp(22), 0, 0);
        box.addView(cardHint);

        return box;
    }

    /** 黑卡中间那个「禁」字印章：认了就转绿，没做就压暗。 */
    private View tabooStamp(final boolean claimed, final boolean declined) {
        return new View(this) {
            private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth();
                float h = getHeight();
                float cx = w / 2f;
                float cy = h / 2f;
                float stroke = dp(7);
                float r = Math.min(w, h) / 2f - stroke;

                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(stroke);
                ring.setColor(claimed ? 0x553DDC84 : 0x24FFFFFF);
                c.drawCircle(cx, cy, r, ring);

                ring.setStrokeWidth(dp(1));
                ring.setColor(0x14FFFFFF);
                c.drawCircle(cx, cy, r - dp(15), ring);

                glyph.setColor(claimed ? greenC : (declined ? 0x59FFFFFF : TABOO_TEXT));
                glyph.setTextAlign(Paint.Align.CENTER);
                glyph.setTypeface(bold());
                glyph.setTextSize(dp(66));
                c.drawText("禁", cx, cy + dp(23), glyph);
            }
        };
    }

    /**
     * 完成度圆环。
     *
     * 竖屏卡片中间那块地，用一条 6dp 的细进度条填不满 —— 会显得空荡荡的。
     * 圆环既是视觉重心，也把"我现在到哪儿了"讲清楚了。
     * 用 Canvas 画而不是嵌套 ProgressBar：自带的环形 ProgressBar 样式改不动。
     */
    private View ringView(final Goal g) {
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
                num.setTypeface(bold());
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
        t.setTypeface(bold());
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView s = new TextView(this);
        s.setText("数据来自电脑端学习台，PC 没在同步就会空着 ——\n点右上角「刷新」重试。");
        s.setTextColor(text2C);
        s.setTextSize(12);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(dp(4), 1f);
        s.setPadding(0, dp(10), 0, 0);
        box.addView(s);

        // 没有电脑的人卡在这页就死路一条了：给一条不依赖 PC 的起步路径
        TextView tplBtn = new TextView(this);
        tplBtn.setText("没有电脑？在手机上直接建目标 →");
        tplBtn.setTextColor(ACCENT);
        tplBtn.setTextSize(13);
        tplBtn.setTypeface(bold());
        tplBtn.setPadding(0, dp(18), 0, 0);
        tplBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showTemplates(false);
            }
        });
        box.addView(tplBtn);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(16), dp(6), dp(16), dp(6));
        box.setLayoutParams(lp);
        return box;
    }

    private void renderDots() {
        dots.removeAllViews();
        for (int i = 0; i < cards.size(); i++) {
            Entry e = cards.get(i);
            View d = new View(this);
            boolean on = i == selected;
            boolean hit = entryHit(e);
            int col;
            if (hit) {
                col = greenC;
            } else if (on) {
                // 禁忌卡选中时用黑点（深色主题反白），白卡用品牌色
                col = e.type == Entry.TABOO ? (dark ? 0xFFEDEDEF : 0xFF111114) : ACCENT;
            } else {
                col = lineC;
            }
            d.setBackground(round(col, dp(3)));
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
                        // 拖动期间停掉呼吸投影：每帧改 elevation 会让整张卡（含大位图
                        // 和阴影）重绘一遍，和拖动叠在一起就是"粘手"
                        stopHover();
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

    private int stageHeight() {
        int h = stage.getHeight();
        return h > 0 ? h : getResources().getDisplayMetrics().heightPixels;
    }

    private int upThreshold() {
        int h = stage.getHeight();
        if (h <= 0) {
            h = getResources().getDisplayMetrics().heightPixels;
        }
        return Math.max(dp(120), Math.min(dp(230), (int) (h * 0.26f)));
    }

    /**
     * 下滑多远算「没做」。比上滑短一截 —— 拒绝比认领轻松，
     * 而且这张卡的存在意义是"先问一句"，不是拦人。
     */
    private int downThreshold() {
        int h = stage.getHeight();
        if (h <= 0) {
            h = getResources().getDisplayMetrics().heightPixels;
        }
        return Math.max(dp(90), Math.min(dp(190), (int) (h * 0.18f)));
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
        if (card == null) {
            return;
        }
        int idx = nextRotatable(step);
        if (idx < 0 || idx == selected) {
            return;   // 这个方向全是打过的卡：不摆预摆，推过去就是死路（阻尼提示）
        }
        TextView keep = cardHint;            // build*Card 会改这个字段，别把当前卡带偏
        View v = buildEntryCard(cards.get(idx), idx);
        cardHint = keep;
        floatify(v, dp(20));

        // 边距必须和真卡一模一样（dp(6)）：预摆这张稍窄一点点的话，
        // 松手落定那一刻 render() 用真边距重建，卡会"啪"地涨宽 20dp，
        // 里面的卡面跟着等比放大 —— 看起来就是"切换时卡片胀大了"。
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(18), dp(18), dp(18), dp(18));
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

    /* ---------- 悬浮立体 ---------- */

    /** 悬浮的呼吸投影：高度 8↔16dp 来回，卡就像悬在桌面上方轻轻起伏。 */
    private ValueAnimator hoverAnim;

    /** 圆角轮廓 + 投影高度：elevation 的影子只有配了 outline 才是圆角的。 */
    private void floatify(View v, float elevPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
            }
        });
        v.setClipToOutline(false);
        v.setElevation(elevPx);
    }

    private void startHover() {
        stopHover();
        hoverAnim = ValueAnimator.ofFloat(dp(18), dp(26));
        hoverAnim.setDuration(1500);
        hoverAnim.setRepeatMode(ValueAnimator.REVERSE);
        hoverAnim.setRepeatCount(ValueAnimator.INFINITE);
        hoverAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                if (card != null) {
                    card.setElevation((Float) a.getAnimatedValue());
                }
            }
        });
        hoverAnim.start();
    }

    private void stopHover() {
        if (hoverAnim != null) {
            hoverAnim.cancel();
            hoverAnim = null;
        }
    }

    /** 撤掉预摆的那张。 */
    private void dropPre() {        if (pre == null) {
            preIndex = -1;
            return;
        }
        View v = pre;
        pre = null;
        preIndex = -1;
        v.animate().cancel();
        stage.removeView(v);
    }

    /**
     * 卡面提示文字：内容/颜色真变了才 setText。
     *
     * 拖动时 onMove 每帧都会调一次 —— 直接 setText 的话每帧都会 requestLayout，
     * 整张卡（含卡面 Drawable）重新测量布局一遍，横推就一顿一顿的。
     */
    private void hint(String text, int color) {
        TextView t = cardHint;
        if (t == null) {
            return;
        }
        if (!text.contentEquals(t.getText())) {
            t.setText(text);
        }
        if (t.getCurrentTextColor() != color) {
            t.setTextColor(color);
        }
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
        card.setRotationX(0f);
        card.setRotationY(0f);
        card.setScaleX(1f);
        card.setScaleY(1f);
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
            Entry ent = entryAt(selected);
            if (ent != null && ent.type == Entry.TABOO) {
                // 黑卡：两个方向都有意义 —— 上＝做了（认领），下＝没做（放行）。
                // 已经认过的卡两边都重阻尼（今天这一笔已经记完了）。
                boolean claimed = tabooClaimedToday(ent.taboo);
                float up = Math.min(dy, 0f);
                float down = Math.max(dy, 0f);
                float t = (up + down) * (claimed ? 0.2f : 1f);
                card.setTranslationY(t);
                float pUp = claimed ? 0f : Math.min(1f, -up / upThreshold());
                float pDown = claimed ? 0f : Math.min(1f, down / downThreshold());
                float p = Math.max(pUp, pDown);
                if (ghost != null) {
                    float s = 0.94f + 0.06f * p;
                    ghost.setScaleX(s);
                    ghost.setScaleY(s);
                    ghost.setTranslationY(dp(12) * (1f - p));
                    ghost.setAlpha(0.6f + 0.4f * p);
                }
                if (!busy && !claimed) {
                    if (p >= 1f && !thrFired) {
                        thrFired = true;
                        buzz(12);
                    } else if (p < 1f) {
                        thrFired = false;
                    }
                }
                if (claimed) {
                    hint("今天已认领 · 左右推换卡", TABOO_TEXT2);
                } else if (pUp >= 1f) {
                    hint("松手，认领「" + ent.taboo + "」", 0xFFFFFFFF);
                } else if (pDown >= 1f) {
                    hint("松手，今天没做", 0xFFC9CAD1);
                } else {
                    hint("↑ 做了   ↓ 没做", 0xB3FFFFFF);
                }
                return;
            }
            // 目标白卡：往上推 = 打卡；往下的位移做重阻尼（不打就是不打，下滑没有含义）
            boolean done = ent != null && ent.goal != null && ent.goal.hit();
            float up = Math.min(dy, 0f);
            float down = Math.max(dy, 0f) * 0.2f;
            float t = (up + down) * (done ? 0.2f : 1f);
            // 平推式：卡保持平整 —— 不后仰、不缩小、不变透明，就是笔直往上推
            // （立体感交给底下的呼吸投影，卡面本身一动不动才像"推"）
            card.setTranslationY(t);
            float p = done ? 0f : Math.min(1f, -up / upThreshold());
            // 上滑只推当前这张：不摆"下一张"跟上来（切不切目标由用户左右推决定）
            if (ghost != null) {
                float s = 0.94f + 0.06f * p;
                ghost.setScaleX(s);
                ghost.setScaleY(s);
                ghost.setTranslationY(dp(12) * (1f - p));
                ghost.setAlpha(0.6f + 0.4f * p);
            }
            if (!busy && !done) {
                if (p >= 1f && !thrFired) {
                    thrFired = true;
                    buzz(12);
                } else if (p < 1f) {
                    thrFired = false;
                }
            }
            if (done) {
                hint("已打卡 · 左右推换目标", text3C);
            } else {
                hint(p >= 1f ? "松手，打卡" : "", p >= 1f ? greenC : ACCENT);
            }
        } else {
            // 这个方向没有可换的目标（只有一个目标 / 该方向的都打完了）＝ 死路：
            // 给一个很重的阻尼。拉不动比"能拉动、但结果还是自己"更诚实
            boolean alone = nextRotatable(dx < 0 ? 1 : -1) < 0;
            float eff = alone ? dx * 0.26f : dx;
            card.setTranslationX(eff);
            float p = Math.min(1f, Math.abs(eff) / sideThreshold());
            // 立体感：横推时卡随方向侧转，像绕着竖轴转过去
            card.setRotationY(-eff * 0.012f);

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
            Entry cur = entryAt(selected);
            boolean hit = entryHit(cur);
            if (alone) {
                hint("这个方向没有可换的卡了", hit ? text3C : ACCENT);
            } else if (p >= 1f) {
                hint(dx < 0 ? "松手，看下一个 →" : "← 松手，看上一个", ACCENT);
            } else {
                hint(hit ? "已处理 · 左右推换卡" : "", hit ? text3C : ACCENT);
            }
        }
    }

    private void onUp(int d, float dx, float dy) {
        if (d == DIR_VERT) {
            Entry ent = entryAt(selected);
            if (ent != null && ent.type == Entry.TABOO) {
                if (tabooClaimedToday(ent.taboo)) {
                    dropPre();
                    spring();   // 今天认过了：竖推无效，直接弹回
                } else if (dy <= -upThreshold() || (vY < -flingX() && dy < -dp(24))) {
                    claimTabooFromCard(ent);       // 上滑 = 做了
                } else if (dy >= downThreshold() || (vY > flingX() && dy > dp(24))) {
                    declineTabooFromCard(ent);     // 下滑 = 没做
                } else {
                    dropPre();
                    spring();
                }
                return;
            }
            boolean doneNow = ent != null && ent.goal != null && ent.goal.hit();
            if (doneNow) {
                dropPre();
                spring();   // 已打卡：竖推无效，直接弹回，连提示都不弹
            } else if (dy <= -upThreshold() || (vY < -flingX() && dy < -dp(24))) {
                commit();   // 距离够或甩得够快 —— pre 留着给 commit 平移补位
            } else {
                dropPre();
                spring();
            }
            return;
        }
        if (cards.isEmpty()) {
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
        // 这个方向全是打过卡的：不放行，弹回
        if (step == 0 || nextRotatable(step) < 0) {
            spring();
        } else {
            flyTo(step);
        }
    }

    private void spring() {
        if (card != null) {
            card.animate().translationX(0).translationY(0).rotation(0).alpha(1f)
                    .scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f)
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
            // 没换成就滑回原来那格，不能直接删 —— 直接删等于"啪"地消失
            // preStep==0 是竖向带：滑回舞台下面；横推的滑回左右那一格
            final View v = pre;
            final int st = preStep;
            final float backX = st == 0 ? v.getTranslationX() : stageWidth() * st;
            final float backY = st == 0 ? stageHeight() : v.getTranslationY();
            pre = null;
            preIndex = -1;
            v.animate().translationX(backX).translationY(backY)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .setDuration(170)
                    .withEndAction(new Runnable() {
                        public void run() {
                            stage.removeView(v);
                        }
                    }).start();
        }
        Entry cur = entryAt(selected);
        if (cur != null) {
            boolean hit = entryHit(cur);
            hint(hit ? "已处理 · 左右推换卡" : "",
                    cur.type == Entry.TABOO ? TABOO_TEXT2 : (hit ? text3C : ACCENT));
        }
        startHover();   // 手势结束，呼吸投影接回来
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

    /** 只改下标，不重排 —— 重排要等动画走到位。打过卡的不参与轮换。 */
    private void stepBy(int step) {
        int idx = nextRotatable(step);
        if (idx >= 0) {
            selected = idx;
        }
    }

    /** 沿 step 方向找下一张「还没处理」的卡（跳过打过卡的）；没有返回 -1。 */
    private int nextRotatable(int step) {
        if (cards.isEmpty()) {
            return -1;
        }
        int n = cards.size();
        for (int i = 1; i <= n; i++) {
            int idx = ((selected + step * i) % n + n) % n;
            if (!entryHandled(cards.get(idx))) {
                return idx;
            }
        }
        return -1;
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
        final Goal g = current();
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
        final double amount = defaultAmount(g);
        final View c = card;
        final View in = pre;          // 竖向平移带：拖动时跟在下面的那张
        pre = null;
        preIndex = -1;

        Runnable after = new Runnable() {
            public void run() {
                busy = false;
                if (in != null) {
                    stage.removeView(in);
                }
                record(g, amount);
                // 粉碎了就别再回来：这张已打完，直接进下一张未处理的；
                // 全打完时 firstPendingCard 兜底停在完成态，底部有绿字"都打完了"
                int nx = cards.isEmpty() ? -1 : nextPendingCardFrom(selected);
                selected = nx >= 0 ? nx : firstPendingCard();
                render(false);
                enterFromUp();
            }
        };
        if (c != null) {
            shatter(c, after);
        } else {
            after.run();
        }
    }

    /* ==================== 禁忌卡：认领 / 没做 ==================== */

    /** 上滑「做了」：冻结延长 + 记流水 + 碎掉黑卡，换下一张。 */
    private void claimTabooFromCard(final Entry e) {
        if (busy || e == null) {
            spring();
            return;
        }
        busy = true;
        final View c = card;
        final View in = pre;
        pre = null;
        preIndex = -1;

        performTabooClaim(e.taboo);

        Runnable after = new Runnable() {
            public void run() {
                busy = false;
                if (in != null) {
                    stage.removeView(in);
                }
                int nx = nextPendingCardFrom(selected);
                selected = nx >= 0 ? nx : firstPendingCard();
                render(false);
                enterFromUp();
            }
        };
        if (c != null) {
            shatter(c, after);
        } else {
            after.run();
        }
    }

    /**
     * 下滑「没做」：当天放行（不延长冻结），这张卡不再拦着往下推；明天照常出现。
     * 卡是往下滑出去的 —— 和「做了」（往上碎掉）方向相反，一眼能分清。
     */
    private void declineTabooFromCard(final Entry e) {
        if (busy || e == null) {
            spring();
            return;
        }
        busy = true;
        StudyGate.declineTaboo(this, e.taboo, System.currentTimeMillis());
        buzz(6);
        toast("「" + e.taboo + "」今天没做");

        final View c = card;
        final View in = pre;
        pre = null;
        preIndex = -1;
        if (in != null) {
            stage.removeView(in);
        }
        Runnable after = new Runnable() {
            public void run() {
                busy = false;
                int nx = nextPendingCardFrom(selected);
                selected = nx >= 0 ? nx : firstPendingCard();
                render(false);
                enterFromUp();
            }
        };
        if (c == null) {
            after.run();
            return;
        }
        c.animate().translationY(stageHeight()).alpha(0.35f)
                .setInterpolator(new DecelerateInterpolator(1.7f))
                .setDuration(200)
                .withEndAction(after).start();
    }

    /** 真正落一笔认领：延长冻结 + 流水 + 立刻让守卫按新惩罚期重算。 */
    private void performTabooClaim(String name) {
        int min = tabooPenaltyMin(this);
        long now = System.currentTimeMillis();
        StudyGate.extendTaboo(this, min * 60_000L, now);
        StudyGate.recordTabooClaim(this, name, now);
        long until = StudyGate.tabooUntil(this);
        buzz(24);
        toast("认领「" + name + "」· 冻结延长至 " + hmOf(until));
        android.util.Log.i(Guard.TAG, "taboo claim: " + name + " +" + min
                + "min → until=" + until);
        syncGuard();
    }

    /** 长按黑卡：一天犯两次忌（两顿辣）时再认一次，叠加。 */
    private void askClaimTabooAgain(final String name) {
        final int min = tabooPenaltyMin(this);
        new AlertDialog.Builder(this)
                .setTitle("再认一次")
                .setMessage("「" + name + "」今天再认一次？\n\n冻结再延长 "
                        + min + " 分钟（叠加）。")
                .setPositiveButton("认了", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        performTabooClaim(name);
                        render(false);
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    /**
     * 上滑打卡的谢幕：把整张卡快照切成小碎块，四散炸开消失。
     * 比"滑出屏幕"有仪式感 —— 这一单是彻底清掉的，不是挪走的。
     */
    private void shatter(final View v, final Runnable end) {
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) {
            end.run();
            return;
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        v.draw(cv);                       // 含底图/圆环/全部文字，快照即所见
        int cols = 7;
        int rows = 10;
        int tw = w / cols;
        int th = h / rows;
        Random rnd = new Random();
        List<View> tiles = new ArrayList<>();
        float baseX = v.getX();           // 卡此刻的视觉位置（含拖拽位移）
        float baseY = v.getY();
        for (int r = 0; r < rows; r++) {
            for (int ci = 0; ci < cols; ci++) {
                int pw = (ci == cols - 1) ? (w - ci * tw) : tw;
                int ph = (r == rows - 1) ? (h - r * th) : th;
                if (pw <= 0 || ph <= 0) {
                    continue;
                }
                Bitmap piece = Bitmap.createBitmap(bmp, ci * tw, r * th, pw, ph);
                ImageView t = new ImageView(this);
                t.setImageBitmap(piece);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pw, ph);
                lp.leftMargin = (int) baseX + ci * tw;
                lp.topMargin = (int) baseY + r * th;
                stage.addView(t, lp);
                tiles.add(t);
            }
        }
        v.setVisibility(View.INVISIBLE);   // 原卡让位给碎块
        long maxDur = 0;
        float sw = Math.max(stage.getWidth(), w);
        for (View t : tiles) {
            long dur = 420 + rnd.nextInt(340);
            maxDur = Math.max(maxDur, dur + 130);
            t.animate()
                    .translationX((rnd.nextFloat() - 0.5f) * sw * 1.5f)
                    .translationY(-(dp(80) + rnd.nextFloat() * sw * 0.8f))
                    .rotation((rnd.nextFloat() - 0.5f) * 130f)
                    .scaleX(0.55f).scaleY(0.55f).alpha(0f)
                    .setStartDelay(rnd.nextInt(130))
                    .setInterpolator(new DecelerateInterpolator(1.1f))
                    .setDuration(dur).start();
        }
        stage.postDelayed(new Runnable() {
            public void run() {
                // 只摘碎块（都是 ImageView），别碰后面的卡和空壳
                for (int i = stage.getChildCount() - 1; i >= 0; i--) {
                    View ch = stage.getChildAt(i);
                    if (ch instanceof ImageView) {
                        stage.removeViewAt(i);
                    }
                }
                end.run();
            }
        }, maxDur);
    }


    private AlertDialog dlg;                     // 字体颜色弹窗
    private AlertDialog tabooDialog;
    private boolean reopeningTaboo;
    private Typeface customFont;                 // 用户选的 ttf/otf；null=系统默认
    private boolean customFontTried;             // 只试读一次，失败不反复读盘
    private static final String FONT_KEY = "font_family";
    private static final String[] FONT_IDS = {
            "default", "sans-serif-medium", "serif", "monospace", "casual"
    };
    private static final String[] FONT_NAMES = {
            "系统默认", "醒目黑体", "衬线字体", "等宽字体", "手写风格"
    };

    private static File fontFile(Context c) {
        return new File(c.getExternalFilesDir(null), "custom_font.ttf");
    }

    /** 打卡页自己的小偏好（模糊档等），不跟打卡数据混。 */
    private android.content.SharedPreferences ui() {
        return getSharedPreferences("afs_ui", 0);
    }

    private String fontChoice() {
        String saved = ui().getString(FONT_KEY, "");
        if ("custom".equals(saved) && !fontFile(this).exists()) return "default";
        return saved.isEmpty() && fontFile(this).exists() ? "custom"
                : saved.isEmpty() ? "default" : saved;
    }

    /** 打卡页字体：系统内置字体与导入字体共用一处设置。 */
    private Typeface uiFont() {
        String choice = fontChoice();
        if (!"custom".equals(choice)) {
            return "default".equals(choice) ? Typeface.DEFAULT : Typeface.create(choice, Typeface.NORMAL);
        }
        return importedFont();
    }

    private Typeface importedFont() {
        if (customFont == null && !customFontTried) {
            customFontTried = true;
            File f = fontFile(this);
            if (f.exists()) {
                try {
                    customFont = Typeface.createFromFile(f);
                } catch (Throwable t) {
                    customFont = null;   // 字体文件坏了就回落默认
                }
            }
        }
        return customFont != null ? customFont : Typeface.DEFAULT;
    }

    private void fontMenu() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(18), dp(6), dp(18), dp(12));
        scroll.addView(rows);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("打卡字体")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .create();
        String current = fontChoice();
        for (int i = 0; i < FONT_IDS.length; i++) {
            final String id = FONT_IDS[i];
            fontMenuRow(rows, FONT_NAMES[i], id, id.equals(current), dialog);
        }
        if (fontFile(this).exists()) {
            fontMenuRow(rows, "已导入的字体", "custom", "custom".equals(current), dialog);
        }
        TextView importFile = new TextView(this);
        importFile.setText("＋  从文件导入 ttf / otf");
        importFile.setTextSize(15);
        importFile.setTextColor(DLG_LINK);
        importFile.setPadding(dp(12), dp(18), dp(12), dp(18));
        rows.addView(importFile);
        importFile.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, "选字体文件（ttf/otf）"), RC_FONT);
                } catch (Throwable t) {
                    toast("没有能选文件的程序");
                }
            }
        });
        dialog.show();
    }

    private void fontMenuRow(LinearLayout rows, String name, final String id,
                             boolean selected, final AlertDialog dialog) {
        TextView row = new TextView(this);
        row.setText((selected ? "✓  " : "    ") + name + "\n    目标打卡 123 · 今天继续");
        row.setTextSize(16);
        row.setTextColor(DLG_TEXT);
        row.setLineSpacing(dp(5), 1f);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setTypeface("custom".equals(id) ? importedFont() :
                "default".equals(id) ? Typeface.DEFAULT : Typeface.create(id, Typeface.NORMAL));
        rows.addView(row);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ui().edit().putString(FONT_KEY, id).apply();
                dialog.dismiss();
                render();
                reopenManage();
            }
        });
    }

    /** 粗体变体：自定义字体也能加粗（系统合成粗体）。 */
    private Typeface bold() {
        return Typeface.create(uiFont(), Typeface.BOLD);
    }

    /** 把选中的字体文件拷进 app 目录（ttf/otf 通吃）。 */
    private void saveFontFile(Uri uri) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            File f = fontFile(this);
            f.getParentFile().mkdirs();
            in = getContentResolver().openInputStream(uri);
            out = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            customFont = null;
            customFontTried = false;
            ui().edit().putString(FONT_KEY, "custom").apply();
            toast("打卡字体已更新");
        } catch (Throwable t) {
            toast("字体没存上: " + t.getClass().getSimpleName());
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** 保存自定义字体颜色并整页重刷（0 = 恢复默认主题色）。 */
    private void saveFontColor(int c) {
        ui().edit().putInt("font_color", c).apply();
        pickColors();
        render();
    }

    /** 打卡字体颜色选择：色板直选 + 十六进制输入 + 恢复默认。 */
    private void fontColorMenu() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        box.setPadding(pad, dp(10), pad, 0);

        TextView hint = new TextView(this);
        hint.setText("点色块直接生效；或输入十六进制色值后点「用输入的颜色」。✓/数量等状态色不跟随。");
        hint.setTextColor(DLG_TEXT2);
        hint.setTextSize(12);
        box.addView(hint);

        // 色板：两行，白/黑/粉/蓝/绿 + 黄/紫/橙/青
        final int[][] rows = {{
                0xFFFFFFFF, 0xFF1B1B1F, 0xFFFF6699, 0xFF00AEEC, 0xFF6BCB77
        }, {
                0xFFFFC94D, 0xFF9D8CFF, 0xFFFF8A65, 0xFF80DEEA
        }};
        for (int[] cols : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (final int c : cols) {
                View sw = new View(this);
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.OVAL);
                gd.setColor(c);
                gd.setStroke(dp(1), 0x33000000);
                sw.setBackground(gd);
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(dp(36), dp(36));
                lp.setMargins(dp(8), dp(14), dp(8), 0);
                sw.setLayoutParams(lp);
                sw.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        saveFontColor(c);
                        dlg.dismiss();
                    }
                });
                row.addView(sw);
            }
            box.addView(row);
        }

        final EditText et = new EditText(this);
        et.setHint("#RRGGBB，如 FFD700");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextSize(14);
        et.setSingleLine(true);
        box.addView(et);

        sv.addView(box);
        dlg = new AlertDialog.Builder(this)
                .setTitle("打卡字体颜色")
                .setView(sv)
                .setPositiveButton("用输入的颜色", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            saveFontColor(Color.parseColor(et.getText().toString().trim()));
                            toast("字体颜色已更新");
                        } catch (Throwable t) {
                            toast("色值不对，形如 #FFD700");
                        }
                    }
                })
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        saveFontColor(0);
                        toast("已恢复默认颜色");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void enterFromUp() {
        if (card == null) {
            return;
        }
        card.setTranslationY(-dp(40));
        card.setAlpha(0f);
        card.animate().translationY(0).alpha(1f)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .setDuration(190).start();
    }

    /**
     * 记账：直接落本机 study.json（手机就是学习台）。
     *
     * 落盘会顺带把 Hub 的 study_dirty 打上，下一拍 kick 就把整份台账推上云；
     * 闸门由 StudyStore 的 invalidate 立刻重算，不用等网络往返。
     */
    private void record(Goal g, double amount) {
        StudyStore.check(this, g.id, amount, StudyStore.today());
        g.localHit = true;
        buzz(24);
        toast("已打卡 " + g.name + "  +" + fmt(amount) + " " + g.unit);
        android.util.Log.i(Guard.TAG, "checkin: " + g.name + " +" + fmt(amount)
                + " " + g.unit + " → pending=" + (pending() - 1));
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

    private void askAmount(final Goal g) {
        final EditText ed = new EditText(this);
        ed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ed.setText(fmt(defaultAmount(g)));
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
                        int next = nextPendingCardFrom(selected);
                        if (next >= 0) {
                            selected = next;
                        }
                        render();
                        enterFromUp();
                    }
                })
                .setNeutralButton("存为默认并打卡", new DialogInterface.OnClickListener() {
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
                        saveAmountOverride(g.id, v);
                        record(g, v);
                        int next = nextPendingCardFrom(selected);
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

    /* ==================== 本机数据 ==================== */

    /**
     * 读一遍本机目标。
     *
     * 手机自带目标，本机就是学习台：没有"拉远端"这回事。
     * 老的 CheckinStore + CheckinSync（拉 study-gate.json、打完卡排队等 PC 记账）
     * 已经整体删掉了 —— 数据源只有 {@link StudyStore}，上传走 {@link Hub} 的台账。
     */
    private void loadLocal() {
        goals.clear();
        goals.addAll(StudyStore.uiGoals(this));
        // 卡组 = 禁忌黑卡（队首）+ 目标白卡
        rebuildCards();
        selected = firstPendingCard();
    }

    private int indexOf(String id) {
        for (int i = 0; i < goals.size(); i++) {
            if (goals.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** 重新读本机文件（打完卡、改目标、跨天回来都走它）。 */
    private void refreshAsync() {
        String keepId = current() == null ? "" : current().id;
        goals.clear();
        goals.addAll(StudyStore.uiGoals(this));
        rebuildCards();
        int idx = queueIndexOfGoal(keepId);
        selected = idx >= 0 ? idx : firstPendingCard();
        render();
    }

    /** 状态行：还剩几个待打卡 + 禁忌卡待确认。 */
    private String statusLine() {
        int gp = pending();
        int tp = 0;
        for (String s : tabooItems(this)) {
            if (!tabooClaimedToday(s) && !tabooDeclinedToday(s)) {
                tp++;
            }
        }
        if (goals.isEmpty() && tp == 0) {
            return "还没有学习目标 · 回主页点「目标管理」加一个";
        }
        if (gp + tp == 0) {
            return "今天的卡都处理完了";
        }
        if (gp == 0) {
            return "禁忌卡待确认 " + tp + " 张";
        }
        if (tp == 0) {
            return "还有 " + gp + " 个待打卡";
        }
        return "还有 " + gp + " 个待打卡 · 禁忌 " + tp + " 张";
    }

    /* ==================== 杂项 ==================== */

    private void askToken() {
        final EditText ed = new EditText(this);
        ed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ed.setHint("GitHub token（只授 gist 权限）");
        ed.setSingleLine(true);
        ed.setPadding(dp(20), dp(12), dp(20), dp(12));
        String cur = Gist.token(this);
        if (!cur.isEmpty()) {
            ed.setText(cur);
        }
        new AlertDialog.Builder(this)
                .setTitle("同步凭据")
                .setMessage("打卡要写进 Gist 才能同步回电脑。\n粘一个只勾了 gist 权限的 token 即可。")
                .setView(ed)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Gist.setToken(CheckinActivity.this, ed.getText().toString());
                        toast("已保存 · 点「立即上传」就能推一份");
                        Hub.kick(CheckinActivity.this);
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
    /**
     * 「点一下能改」的项用这个蓝。
     * 之前这些项跟说明文字一样是深灰（#61666D），一眼看上去全是黑的，
     * 根本分不出哪句能点 —— 而真正的按钮（＋ 新目标）又用了浅蓝 ACCENT(#00AEEC)，
     * 那玩意儿在白底上只有 2.4:1 对比度，刺眼但不清。
     * #0A6FD8 在白底上是 4.9:1，既能点得明白，也看得清楚。
     */
    private static final int DLG_LINK = 0xFF0A6FD8;

    /** 管理入口：目标列表 + 新建。目标一多也只是在对话框里滚。 */
    /* ==================== 禁忌卡（黑卡） ==================== */

    private static android.content.SharedPreferences tabooSp(Context c) {
        return c.getSharedPreferences(StudyGate.TABOO_SP, Context.MODE_PRIVATE);
    }

    /** 禁忌项列表（SP 里存 JSONArray 字符串，保序）。
     *  实现已在 StudyGate —— 首页的"打卡"按钮要算同一份待办，口径不能有两套。 */
    private static List<String> tabooItems(Context c) {
        return StudyGate.tabooItems(c);
    }

    private static void saveTabooItems(Context c, List<String> items) {
        JSONArray a = new JSONArray();
        for (String s : items) {
            a.put(s);
        }
        tabooSp(c).edit().putString("items", a.toString()).apply();
    }

    /** 每次认领延长多少分钟（默认 60）。 */
    private static int tabooPenaltyMin(Context c) {
        return tabooSp(c).getInt("penalty_min", 60);
    }

    private static String hmOf(long ms) {
        Calendar u = Calendar.getInstance();
        u.setTimeInMillis(ms);
        return String.format("%02d:%02d",
                u.get(Calendar.HOUR_OF_DAY), u.get(Calendar.MINUTE));
    }

    /** 禁忌卡管理页：逐张显示状态，认领和设置分开。 */
    private void tabooManage() {
        if (tabooDialog != null) {
            reopeningTaboo = true;
            tabooDialog.dismiss();
            tabooDialog = null;
            reopeningTaboo = false;
        }
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(manageBg());
        sc.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(20), dp(18), dp(28));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = look.sub("禁忌卡");
        title.setTextSize(22);
        title.setTextColor(look.text());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        TextView close = manageButton("完成", look.surface(), look.accent());
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (tabooDialog != null) tabooDialog.dismiss();
            }
        });
        nav.addView(close, new LinearLayout.LayoutParams(dp(64), dp(38)));
        box.addView(nav);
        TextView subtitle = look.sub("每天核对每张卡，记录做了还是没做");
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(12);
        box.addView(subtitle, subLp);

        long until = StudyGate.tabooUntil(this);
        List<String> items = tabooItems(this);
        int pending = StudyGate.tabooPendingToday(this);
        boolean holding = StudyGate.tabooHolding(System.currentTimeMillis(), until);
        LinearLayout overview = look.card();
        LinearLayout.LayoutParams overviewLp = new LinearLayout.LayoutParams(-1, -2);
        overviewLp.topMargin = dp(20);
        box.addView(overview, overviewLp);
        overview.addView(look.sub("今日禁忌"));
        TextView count = look.title(items.isEmpty() ? "还没有禁忌卡"
                : pending == 0 ? "今天已确认" : "还有 " + pending + " 张待确认");
        count.setTextSize(22);
        count.setTextColor(pending == 0 ? look.green() : look.red());
        overview.addView(count);
        TextView summary = look.sub(holding
                ? "冻结已延长至 " + hmOf(until)
                : "当前没有禁忌卡带来的延长冻结");
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(-1, -2);
        sumLp.topMargin = dp(6);
        overview.addView(summary, sumLp);

        LinearLayout section = look.section("■", Ui.RUST, "禁忌卡列表", null, null);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.topMargin = dp(24);
        sectionLp.bottomMargin = dp(10);
        box.addView(section, sectionLp);
        if (items.isEmpty()) {
            LinearLayout empty = look.card();
            empty.addView(look.sub("添加一张禁忌卡后，每天都可以单独确认它。"));
            box.addView(empty);
        }
        for (final String name : items) {
            boolean claimed = tabooClaimedToday(name);
            boolean declined = !claimed && tabooDeclinedToday(name);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(18), dp(17), dp(18), dp(17));
            card.setBackground(look.round(TABOO_CARD, 20f));
            card.setElevation(dp(5));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.bottomMargin = dp(12);
            box.addView(card, cardLp);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView tag = new TextView(this);
            tag.setText("禁忌卡  /  每日确认");
            tag.setTextSize(11);
            tag.setTextColor(TABOO_TEXT2);
            top.addView(tag, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView remove = new TextView(this);
            remove.setText("删除");
            remove.setTextSize(12);
            remove.setTextColor(TABOO_TEXT2);
            remove.setPadding(dp(10), dp(6), 0, dp(6));
            remove.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmRemoveTaboo(name); }
            });
            top.addView(remove);
            card.addView(top);

            TextView nameText = new TextView(this);
            nameText.setText(name);
            nameText.setTextSize(22);
            nameText.setTypeface(Typeface.DEFAULT_BOLD);
            nameText.setTextColor(TABOO_TEXT);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
            nameLp.topMargin = dp(10);
            card.addView(nameText, nameLp);
            TextView state = new TextView(this);
            state.setText(claimed ? "今天已认领 · 冻结已延长" : declined ? "今天没做 · 无额外冻结" : "今天还未确认");
            state.setTextColor(claimed ? RED_D : declined ? GREEN_D : TABOO_TEXT2);
            state.setTextSize(13);
            LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(-1, -2);
            stateLp.topMargin = dp(5);
            card.addView(state, stateLp);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, dp(42));
            actionsLp.topMargin = dp(17);
            card.addView(actions, actionsLp);
            TextView claim = manageButton(claimed ? "再次认领" : "认领", 0xFF34363D, TABOO_TEXT);
            claim.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { claimTaboo(name); }
            });
            actions.addView(claim, new LinearLayout.LayoutParams(0, -1, 1f));
            if (!claimed && !declined) {
                TextView no = manageButton("今天没做", 0xFF23262D, TABOO_TEXT);
                LinearLayout.LayoutParams noLp = new LinearLayout.LayoutParams(0, -1, 1f);
                noLp.leftMargin = dp(8);
                actions.addView(no, noLp);
                no.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        StudyGate.declineTaboo(CheckinActivity.this, name, System.currentTimeMillis());
                        tabooManage();
                        render(false);
                    }
                });
            }
        }

        TextView add = manageButton("＋  添加禁忌卡", Ui.BLUE, 0xFFFFFFFF);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, dp(48));
        addLp.topMargin = dp(4);
        box.addView(add, addLp);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                askAddTaboo();
            }
        });

        LinearLayout rules = look.section("⚙", Ui.PURPLE, "冻结规则", null, null);
        LinearLayout.LayoutParams rulesLp = new LinearLayout.LayoutParams(-1, -2);
        rulesLp.topMargin = dp(26);
        rulesLp.bottomMargin = dp(10);
        box.addView(rules, rulesLp);
        manageOption(box, "认领一次延长冻结", tabooPenaltyMin(this) + " 分钟 · 点此修改",
                new View.OnClickListener() {
                    public void onClick(View v) { askTabooPenalty(); }
                });

        sc.addView(box);
        final AlertDialog shown = new AlertDialog.Builder(this).setView(sc).show();
        tabooDialog = shown;
        shown.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        shown.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(manageBg()));
        shown.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        shown.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        shown.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        shown.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface ignored) {
                if (tabooDialog != shown) return;
                tabooDialog = null;
                if ("taboo".equals(getIntent().getStringExtra(EXTRA_OPEN))
                        && !reopeningTaboo && !isFinishing()) finish();
            }
        });
    }

    /** 认领禁忌卡：确认后冻结立刻延长一段惩罚时长。 */
    private void claimTaboo(final String name) {
        final int min = tabooPenaltyMin(this);
        new AlertDialog.Builder(this)
                .setTitle("认领禁忌卡")
                .setMessage("「" + name + "」认领一次？\n\n冻结程序将延长 "
                        + min + " 分钟（可叠加）。")
                .setPositiveButton("认了", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        performTabooClaim(name);
                        tabooManage();   // 刷新弹窗状态行
                        render(false);   // 卡组里的那张黑卡也要变成"今天认了"
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private void askAddTaboo() {
        final EditText et = new EditText(this);
        et.setHint("禁忌名称，如：吃辣、吃甜");
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("添加禁忌卡")
                .setView(et)
                .setPositiveButton("添加", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) {
                            toast("名字不能为空");
                            return;
                        }
                        List<String> items = tabooItems(CheckinActivity.this);
                        if (items.contains(name)) {
                            toast("已经有这张禁忌卡了");
                            return;
                        }
                        items.add(name);
                        saveTabooItems(CheckinActivity.this, items);
                        tabooManage();
                        render(false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoveTaboo(final String name) {
        new AlertDialog.Builder(this)
                .setTitle("删除禁忌卡")
                .setMessage("删掉「" + name + "」？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        List<String> items = tabooItems(CheckinActivity.this);
                        items.remove(name);
                        saveTabooItems(CheckinActivity.this, items);
                        tabooManage();
                        render(false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askTabooPenalty() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("分钟数，如 30、60、120");
        et.setText(String.valueOf(tabooPenaltyMin(this)));
        new AlertDialog.Builder(this)
                .setTitle("每次认领延长冻结")
                .setView(et)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            int min = Integer.parseInt(et.getText().toString().trim());
                            if (min <= 0 || min > 24 * 60) {
                                toast("填 1~1440 之间的分钟数");
                                return;
                            }
                            tabooSp(CheckinActivity.this).edit()
                                    .putInt("penalty_min", min).apply();
                            tabooManage();
                            render(false);   // 卡面上的"延长 N 分钟"要跟着改
                        } catch (Throwable t) {
                            toast("没看懂，填个分钟数");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void manage() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(manageBg());
        sc.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(20), dp(18), dp(28));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = look.sub("学习目标");
        brand.setTextSize(22);
        brand.setTextColor(look.text());
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        nav.addView(brand, new LinearLayout.LayoutParams(0, dp(42), 1f));
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = look.sub("完成");
        close.setTextColor(look.accent());
        close.setTextSize(14);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        close.setBackground(look.round(look.surface(), 13f));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (manageDialog != null) manageDialog.dismiss();
            }
        });
        nav.addView(close, new LinearLayout.LayoutParams(dp(64), dp(38)));
        box.addView(nav);

        TextView subtitle = look.sub("管理进度、默认完成量和打卡设置");
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(12);
        box.addView(subtitle, subLp);

        int pendingGoals = StudyStore.pendingToday(this);
        LinearLayout overview = look.card();
        LinearLayout.LayoutParams overviewLp = new LinearLayout.LayoutParams(-1, -2);
        overviewLp.topMargin = dp(20);
        box.addView(overview, overviewLp);
        TextView overviewCaption = look.sub("今日进度");
        overview.addView(overviewCaption);
        TextView overviewValue = look.title(pendingGoals == 0 ? "今天已完成" : "还有 " + pendingGoals + " 个目标");
        overviewValue.setTextSize(22);
        overviewValue.setTextColor(pendingGoals == 0 ? look.green() : look.accent());
        overview.addView(overviewValue);
        TextView overviewHint = look.sub("共 " + goals.size() + " 个学习目标 · 点目标卡可修改");
        LinearLayout.LayoutParams overviewHintLp = new LinearLayout.LayoutParams(-1, -2);
        overviewHintLp.topMargin = dp(6);
        overview.addView(overviewHint, overviewHintLp);

        LinearLayout section = look.section("◎", Ui.TEAL, "目标列表", null, null);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.topMargin = dp(24);
        sectionLp.bottomMargin = dp(10);
        box.addView(section, sectionLp);

        if (goals.isEmpty()) {
            LinearLayout empty = look.card();
            TextView emptyText = look.sub("还没有学习目标。添加一个目标，开始记录每天的进度。");
            emptyText.setLineSpacing(dp(4), 1f);
            empty.addView(emptyText);
            box.addView(empty);
        }

        for (final Goal g : goals) {
            StudyStore.G src = StudyStore.find(this, g.id);
            String due = src == null ? "" : src.due;
            LinearLayout goalCard = look.card();
            LinearLayout.LayoutParams goalLp = new LinearLayout.LayoutParams(-1, -2);
            goalLp.bottomMargin = dp(10);
            box.addView(goalCard, goalLp);
            LinearLayout goalHead = new LinearLayout(this);
            goalHead.setOrientation(LinearLayout.HORIZONTAL);
            goalHead.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = look.sub(g.name);
            name.setTextSize(17);
            name.setTextColor(look.text());
            name.setTypeface(Typeface.DEFAULT_BOLD);
            goalHead.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView edit = look.sub("修改  ›");
            edit.setTextColor(look.accent());
            goalHead.addView(edit);
            goalCard.addView(goalHead);
            TextView dueText = look.sub(due.isEmpty() ? "不限期" : due + " 截止");
            LinearLayout.LayoutParams dueLp = new LinearLayout.LayoutParams(-1, -2);
            dueLp.topMargin = dp(4);
            goalCard.addView(dueText, dueLp);
            TextView progress = look.sub(fmt(g.done) + " / " + fmt(g.total) + " " + g.unit);
            progress.setTextSize(14);
            progress.setTextColor(look.text());
            LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, -2);
            progressLp.topMargin = dp(14);
            goalCard.addView(progress, progressLp);
            float ratio = (float) Math.max(0, Math.min(1,
                    g.total <= 0 ? 0 : g.done / g.total));
            LinearLayout track = new LinearLayout(this);
            track.setBackground(look.round(look.surface2(), 99f));
            View fill = new View(this);
            fill.setBackground(look.round(ratio >= 1f ? look.green() : look.accent(), 99f));
            track.addView(fill, new LinearLayout.LayoutParams(0, dp(7), Math.max(0.001f, ratio)));
            track.addView(new View(this), new LinearLayout.LayoutParams(
                    0, dp(7), Math.max(0.001f, 1f - ratio)));
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(-1, dp(7));
            trackLp.topMargin = dp(8);
            goalCard.addView(track, trackLp);
            TextView amount = look.sub("每次默认 +" + fmt(defaultAmount(g))
                    + (hasAmountOverride(g.id) ? " · 已自定义" : ""));
            LinearLayout.LayoutParams amountLp = new LinearLayout.LayoutParams(-1, -2);
            amountLp.topMargin = dp(10);
            goalCard.addView(amount, amountLp);
            goalCard.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    manageOne(g.id);
                }
            });
        }

        List<StudyStore.G> deleted = StudyStore.deletedGoals(this);
        if (!deleted.isEmpty()) {
            TextView deletedHint = look.sub("最近删除 · 可撤回目标和原有打卡记录");
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
            hintLp.topMargin = dp(10);
            hintLp.bottomMargin = dp(8);
            box.addView(deletedHint, hintLp);
            for (final StudyStore.G removed : deleted) {
                TextView undo = manageButton("撤回删除「" + removed.name + "」", look.surface(), look.accent());
                LinearLayout.LayoutParams undoLp = new LinearLayout.LayoutParams(-1, dp(44));
                undoLp.bottomMargin = dp(7);
                box.addView(undo, undoLp);
                undo.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (StudyStore.restore(CheckinActivity.this, removed.id)) {
                            refreshAsync();
                            reopenManage();
                            toast("已恢复目标和打卡记录");
                        }
                    }
                });
            }
        }

        TextView add = manageButton("＋  新目标", Ui.BLUE, 0xFFFFFFFF);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                askGoal(null);
            }
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, dp(48));
        addLp.topMargin = dp(4);
        box.addView(add, addLp);

        TextView tpl = manageButton("从模板新建  ›", look.surface(), look.text());
        tpl.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showTemplates(true);
            }
        });
        LinearLayout.LayoutParams tplLp = new LinearLayout.LayoutParams(-1, dp(48));
        tplLp.topMargin = dp(8);
        box.addView(tpl, tplLp);

        LinearLayout optionsHead = look.section("⚙", Ui.PURPLE, "更多设置", null, null);
        LinearLayout.LayoutParams optionsHeadLp = new LinearLayout.LayoutParams(-1, -2);
        optionsHeadLp.topMargin = dp(26);
        optionsHeadLp.bottomMargin = dp(10);
        box.addView(optionsHead, optionsHeadLp);

        manageOption(box, "禁忌卡", "设置与认领黑卡", new View.OnClickListener() {
            public void onClick(View v) { tabooManage(); }
        });

        String chosenFont = fontChoice();
        String chosenName = "已导入的字体";
        for (int i = 0; i < FONT_IDS.length; i++) {
            if (FONT_IDS[i].equals(chosenFont)) chosenName = FONT_NAMES[i];
        }
        manageOption(box, "打卡字体", chosenName + " · 点此选择或导入", new View.OnClickListener() {
            public void onClick(View v) { fontMenu(); }
        });

        // 打卡字体颜色：色板直选 / 十六进制输入
        int fcNow = ui().getInt("font_color", 0);
        manageOption(box, "字体颜色", fcNow == 0 ? "默认颜色"
                : String.format("#%06X", fcNow & 0xFFFFFF), new View.OnClickListener() {
            public void onClick(View v) {
                fontColorMenu();
            }
        });

        manageOption(box, "备份与恢复", "换手机时导出或导入数据", new View.OnClickListener() {
            public void onClick(View v) { backupMenu(); }
        });

        sc.addView(box);
        android.app.AlertDialog d = new AlertDialog.Builder(this)
                .setView(sc)
                .show();
        manageDialog = d;
        if ("manage".equals(getIntent().getStringExtra(EXTRA_OPEN))) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            d.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(manageBg()));
            // 与打卡页同样沉浸式；ColorOS 会忽略 Dialog 的状态栏颜色。
            d.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            d.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            d.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface ignored) {
                manageDialog = null;
                if (!reopeningManage) {
                    loadLocal();
                    render();
                    if ("manage".equals(getIntent().getStringExtra(EXTRA_OPEN)) && !isFinishing()) finish();
                }
            }
        });
    }

    private TextView manageButton(String title, int bgColor, int textColor) {
        TextView button = new TextView(this);
        button.setText(title);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setBackground(look.round(bgColor, 14f));
        return button;
    }

    private void manageOption(LinearLayout parent, String title, String detail,
                              View.OnClickListener click) {
        LinearLayout card = look.card(14f, 13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        parent.addView(card, lp);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView labels = new TextView(this);
        labels.setText(title + "\n" + detail);
        labels.setTextColor(look.text());
        labels.setTextSize(14);
        labels.setLineSpacing(dp(3), 1f);
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = look.sub("›");
        arrow.setTextSize(23);
        arrow.setTextColor(look.accent());
        line.addView(arrow);
        card.addView(line);
        card.setOnClickListener(click);
    }

    private int manageBg() {
        return look.bg();
    }

    /** 数据变了：把管理对话框关掉重开一份，列表才会反映最新状态。 */
    private void reopenManage() {
        if (manageDialog != null) {
            reopeningManage = true;
            manageDialog.dismiss();
            manageDialog = null;
        }
        manage();
        reopeningManage = false;
    }

    /** 单个目标的操作：编辑 / 改默认完成量 / 删除。 */
    private void manageOne(final String id) {
        String name = id;
        String unit = "";
        StudyStore.G src = StudyStore.find(this, id);
        if (src != null) {
            name = src.name;
            unit = src.unit;
        }
        final String fName = name;
        final String fUnit = unit;
        new AlertDialog.Builder(this)
                .setItems(new CharSequence[]{"编辑", "改每次默认完成量", "删除目标…"},
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            askGoal(StudyStore.find(CheckinActivity.this, id));
                        } else if (w == 1) {
                            askDefaultAmount(id, fName, fUnit);
                        } else {
                            final AlertDialog confirm = new AlertDialog.Builder(CheckinActivity.this)
                                    .setTitle("删除目标？")
                                    .setMessage("将「" + fName + "」及其打卡记录移到最近删除。可在目标列表中撤回。")
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("确认删除", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dialog, int which) {
                                            StudyStore.remove(CheckinActivity.this, id);
                                            refreshAsync();
                                            reopenManage();
                                            toast("已移到最近删除，可在下方撤回");
                                        }
                                    })
                                    .create();
                            confirm.setOnShowListener(new DialogInterface.OnShowListener() {
                                public void onShow(DialogInterface ignored) {
                                    final android.widget.Button button = confirm.getButton(AlertDialog.BUTTON_POSITIVE);
                                    button.setTextColor(look.red());
                                    button.setEnabled(false);
                                    ui.postDelayed(new Runnable() {
                                        public void run() {
                                            if (confirm.isShowing()) button.setEnabled(true);
                                        }
                                    }, 1500);
                                }
                            });
                            confirm.show();
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

    /** 内置模板：给没耐心填表单的人一键起步（总量按常见 30 天计划估的）。 */
    private static final String[][] TEMPLATES = {
            {"背单词", "词", "1500"},
            {"读书", "页", "300"},
            {"刷题", "题", "600"},
            {"运动", "分钟", "1200"},
            {"听网课", "分钟", "900"},
            {"俯卧撑", "个", "1000"},
    };

    /** 从模板新建：点一下就建好（30 天期限），想改细节再去编辑。 */
    private void showTemplates(final boolean backToManage) {
        CharSequence[] items = new CharSequence[TEMPLATES.length + 1];
        for (int i = 0; i < TEMPLATES.length; i++) {
            String[] t = TEMPLATES[i];
            double total;
            try {
                total = Double.parseDouble(t[2]);
            } catch (Throwable x) {
                total = 0;
            }
            items[i] = t[0] + " · 总量 " + fmt(total) + " " + t[1] + " · 30 天";
        }
        items[TEMPLATES.length] = "自定义…";
        new AlertDialog.Builder(this)
                .setTitle("从模板新建")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w >= TEMPLATES.length) {
                            askGoal(null);   // 自定义：沿用完整表单
                            return;
                        }
                        String[] t = TEMPLATES[w];
                        StudyStore.G g = new StudyStore.G();
                        g.id = "g" + System.currentTimeMillis();
                        g.name = t[0];
                        g.unit = t[1];
                        try {
                            g.total = Double.parseDouble(t[2]);
                        } catch (Throwable x) {
                            g.total = 100;
                        }
                        g.due = StudyStore.plusDays(30);
                        StudyStore.upsert(CheckinActivity.this, g);
                        loadLocal();
                        render();
                        syncGuard();
                        toast("已创建：" + g.name + " · 目标 " + fmt(g.total) + " " + g.unit);
                        if (backToManage) {
                            reopenManage();
                        }
                    }
                })
                .show();
    }

    /** 备份 / 恢复：走系统文件选择器（SAF），不用申请任何存储权限。 */
    private void backupMenu() {
        new AlertDialog.Builder(this)
                .setTitle("备份 / 恢复")
                .setItems(new CharSequence[]{
                        "导出到文件（目标 + 打卡记录全在里面）",
                        "从文件恢复（覆盖现有数据）"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("application/json");
                            i.putExtra(Intent.EXTRA_TITLE,
                                    "afs-study-" + StudyStore.today() + ".json");
                            startActivityForResult(i, RC_EXPORT);
                        } else {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("*/*");
                            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                                    "application/json", "text/plain",
                                    "application/octet-stream"});
                            startActivityForResult(i, RC_IMPORT);
                        }
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RC_BG) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                String err = Backdrop.importImage(this, data.getData());
                if (err == null) {
                    toast("背景已换");
                    reloadBackdrop();
                } else {
                    toast(err);
                }
            }
            return;
        }
        if (req == RC_FONT) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                saveFontFile(data.getData());
                render();
                reopenManage();
            }
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (req == RC_EXPORT) {
            java.io.OutputStream os = null;
            try {
                os = getContentResolver().openOutputStream(data.getData());
                os.write(StudyStore.exportJson(this).getBytes("UTF-8"));
                os.close();
                os = null;
                toast("已导出");
            } catch (Throwable t) {
                toast("导出失败 " + t.getClass().getSimpleName());
            } finally {
                try {
                    if (os != null) {
                        os.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        } else if (req == RC_IMPORT) {
            java.io.InputStream is = null;
            try {
                is = getContentResolver().openInputStream(data.getData());
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                is.close();
                is = null;
                if (StudyStore.importJson(this, bo.toString("UTF-8"))) {
                    toast("已恢复 · 打卡页和闸门都用新数据");
                    loadLocal();
                    render();
                    syncGuard();
                    reopenManage();
                } else {
                    toast("不是有效的备份文件，现有数据没动");
                }
            } catch (Throwable t) {
                toast("恢复失败 " + t.getClass().getSimpleName());
            } finally {
                try {
                    if (is != null) {
                        is.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
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

    private Goal current() {
        Entry e = entryAt(selected);
        return e == null ? null : e.goal;
    }

    /* ---------- 卡组（禁忌黑卡在前 + 目标白卡在后） ---------- */

    /**
     * 重建卡组。顺序是硬口径：禁忌卡**永远排在队首**（每天都有、先问犯忌没有），
     * 目标卡跟在后面。内部只读 SP / goals，随时重建都是幂等的。
     */
    private void rebuildCards() {
        cards.clear();
        for (String s : tabooItems(this)) {
            cards.add(new Entry(Entry.TABOO, s, null, -1));
        }
        for (int i = 0; i < goals.size(); i++) {
            cards.add(new Entry(Entry.GOAL, null, goals.get(i), i));
        }
    }

    private Entry entryAt(int i) {
        return (i >= 0 && i < cards.size()) ? cards.get(i) : null;
    }

    /** 卡的身份（重建队列后按它找回同一张卡）。 */
    private static String entryKey(Entry e) {
        if (e == null) {
            return null;
        }
        return e.type == Entry.TABOO ? "T:" + e.taboo : "G:" + e.goal.id;
    }

    private int indexOfKey(String key) {
        for (int i = 0; i < cards.size(); i++) {
            if (key.equals(entryKey(cards.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /** 这一张今天算不算"做完了"：目标打卡 / 禁忌今天认过。 */
    private boolean entryHit(Entry e) {
        if (e == null) {
            return false;
        }
        if (e.type == Entry.TABOO) {
            return tabooClaimedToday(e.taboo);
        }
        return e.goal != null && e.goal.hit();
    }

    /** 今天处理过了（认了 / 明确没做 / 目标已打卡）—— 轮换时跳过。 */
    private boolean entryHandled(Entry e) {
        if (e == null) {
            return true;
        }
        if (e.type == Entry.TABOO) {
            return tabooClaimedToday(e.taboo) || tabooDeclinedToday(e.taboo);
        }
        return e.goal != null && e.goal.hit();
    }

    /** 今天还没处理完的卡张数（禁忌待确认 + 目标待打卡）。 */
    private int pendingCards() {
        int n = 0;
        for (Entry e : cards) {
            if (!entryHandled(e)) {
                n++;
            }
        }
        return n;
    }

    private int firstPendingCard() {
        for (int i = 0; i < cards.size(); i++) {
            if (!entryHandled(cards.get(i))) {
                return i;
            }
        }
        return cards.isEmpty() ? -1 : 0;
    }

    private int nextPendingCardFrom(int from) {
        for (int k = 1; k <= cards.size(); k++) {
            int i = (from + k) % cards.size();
            if (!entryHandled(cards.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private int queueIndexOfGoal(String id) {
        for (int i = 0; i < cards.size(); i++) {
            Entry e = cards.get(i);
            if (e.goal != null && e.goal.id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /* ---------- 禁忌卡：按天算"认了 / 没做" ---------- */

    private boolean tabooClaimedToday(String name) {
        long now = System.currentTimeMillis();
        return StudyGate.tabooClaimedOn(this, name, dayStartMs(now), dayEndMs(now));
    }

    private boolean tabooDeclinedToday(String name) {
        long now = System.currentTimeMillis();
        return StudyGate.tabooDeclinedOn(this, name, dayStartMs(now), dayEndMs(now));
    }

    /** 本地零点：禁忌卡的"每天"就是从这儿翻页的。 */
    private static long dayStartMs(long now) {
        return StudyGate.dayStartMs(now);
    }

    private static long dayEndMs(long now) {
        return StudyGate.dayEndMs(now);
    }

    private int pending() {
        int n = 0;
        for (Goal g : goals) {
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

    static double suggestOf(Goal g) {
        if (g.suggest > 0) {
            return g.suggest;
        }
        double remain = g.total - g.done;
        return remain > 0 ? Math.ceil(remain) : 1;
    }

    /* ==================== 每目标默认完成量（用户自定义，持久化） ==================== */

    private android.content.SharedPreferences amtPrefs() {
        return getSharedPreferences("afs_amt", Context.MODE_PRIVATE);
    }

    /** 打卡默认量：用户改过的优先，没改过用自动建议。 */
    private double defaultAmount(Goal g) {
        float o = amtPrefs().getFloat("amt_" + g.id, -1f);
        return o > 0 ? o : suggestOf(g);
    }

    private boolean hasAmountOverride(String id) {
        return amtPrefs().getFloat("amt_" + id, -1f) > 0;
    }

    private void saveAmountOverride(String id, double v) {
        amtPrefs().edit().putFloat("amt_" + id, (float) v).apply();
    }

    private void clearAmountOverride(String id) {
        amtPrefs().edit().remove("amt_" + id).apply();
    }

    /** 改某目标的默认完成量：留空＝恢复自动建议。 */
    private void askDefaultAmount(final String id, final String name, final String unit) {
        Goal cur = null;
        for (Goal g : goals) {
            if (g.id.equals(id)) {
                cur = g;
                break;
            }
        }
        if (cur == null) {
            return;
        }
        final EditText ed = new EditText(this);
        ed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ed.setText(fmt(defaultAmount(cur)));
        ed.setSelectAllOnFocus(true);
        ed.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this)
                .setTitle(name + " · 每次默认完成量")
                .setMessage("现在默认记 " + fmt(defaultAmount(cur)) + " " + unit
                        + (hasAmountOverride(id) ? "（自定义）" : "（自动）")
                        + "\n填新数字保存；清空＝恢复自动建议。")
                .setView(ed)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String t = ed.getText().toString().trim();
                        if (t.isEmpty()) {
                            clearAmountOverride(id);
                            toast("已恢复自动建议量");
                        } else {
                            try {
                                double v = Double.parseDouble(t);
                                if (!(v > 0)) {
                                    toast("数量要大于 0");
                                    return;
                                }
                                saveAmountOverride(id, v);
                                toast("默认量已存：每次 " + fmt(v) + " " + unit);
                            } catch (Throwable tt) {
                                toast("数量填个数字");
                                return;
                            }
                        }
                        reopenManage();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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

    /** 顶栏小胶囊：Voyage 里那种浅一档的圆角标签，比裸文字链更像"能点"。 */
    private TextView pill(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(textC);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(13), dp(7), dp(13), dp(7));
        tv.setBackground(round(ghostBg, dp(99)));
        return tv;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /* ==================== 卡片背景 ==================== */

    /**
     * 卡片背景：一块纯色面 + 一圈描边（打完的目标描边转绿）。
     *
     * 以前这里会给每个目标铺一张背景照片，连着解码缓存、高斯模糊衬底、
     * 后台预热线程一整套；那套视觉已经清掉了，卡面就是卡面，不铺照片。
     */
    private Drawable cardBackground(boolean done) {
        GradientDrawable bgd = round(cardBg, dp(22));
        bgd.setStroke(dp(1), done ? greenC : lineC);
        return bgd;
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 供首页调用：带主题跳过来，免得从浅色页面点进去闪一下深色。 */
    static void open(Activity from, boolean dark) {
        Intent it = new Intent(from, CheckinActivity.class);
        it.putExtra(EXTRA_DARK, dark);
        from.startActivity(it);
    }
}
