package com.afs.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
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
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * 黑名单管理页：一行一个应用，点开改它的时段。
 *
 * 单独一页是必须的 —— 主界面塞满卡片之后，应用一多就得一直往下滑，
 * 而这里本来就是"黑名单"，平时不需要看，需要时才进来改。
 *
 * 配色：黑名单页固定深色（用户要求），不跟随屏蔽状态切换。
 */
public class AppListActivity extends Activity {

    private static final int REQ_PICK = 22;

    private static final int BG = 0xFFF1F2F3;
    private static final int SURFACE = 0xFFFFFFFF;
    private static final int TEXT = 0xFF18191C;
    private static final int TEXT2 = 0xFF61666D;
    private static final int BRAND = 0xFF00AEEC;
    private static final int RED = 0xFFF85A54;
    private static final int GREEN = 0xFF20A162;
    private static final int LINE = 0xFFE3E5E7;

    // 全部屏蔽时整页切深色（跟首页同一套）
    private static final int BG_DARK      = 0xFF0D0D0F;
    private static final int SURFACE_DARK  = 0xFF1A1A1D;
    private static final int TEXT_DARK     = 0xFFEDEDEF;
    private static final int TEXT2_DARK    = 0xFF9B9BA2;
    private static final int OFF_DARK      = 0xFF2A2A2E;
    private static final int OFF_LIGHT     = 0xFFE3E5E7;
    private static final int RED_DARK      = 0xFFFF7B72;
    private static final int GREEN_DARK    = 0xFF3DDC84;
    /** 组卡片里子行的底色：比组头深一档，做出"嵌在里面"的层级。 */
    private static final int CHILD_DARK    = 0xFF141417;

    /** 每一行的句柄，tick 只更新状态文字，不整页重建（保住滚动位置）。 */
    private static final class Row {
        String pkg;
        /** 是不是折进组卡片里的子行（背景比组头稍深，做出层级感）。 */
        boolean child;
        LinearLayout box;
        TextView name;
        TextView state;
    }

    /**
     * 一张组卡片：放行时段完全相同的一批应用。
     *
     * 一次性批量设时段之后，黑名单会变成十几行长得一模一样的条目，
     * 翻起来又长又分不清谁是谁。折成一张卡："10 个应用 · 每天 22:00-23:00 放行"，
     * 点开才列出具体是哪些 —— 平时看总量，需要时才看明细。
     */
    private static final class Group {
        String key;
        java.util.List<String> pkgs;
        LinearLayout card;
        LinearLayout body;
        TextView title;
        TextView sub;
        TextView names;
        TextView state;
        TextView arrow;
    }

    private ScrollView scroll;
    private LinearLayout list;
    private TextView title;
    private TextView hint;
    private TextView empty;
    private Button addBtn;
    private Button pkgBtn;
    private Button batchBtn;
    private Button keyBtn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.List<Row> rows = new ArrayList<>();
    private final java.util.List<Group> groups = new ArrayList<>();
    /** 展开过的组（key）。重建后还能保持展开，不然改完一个应用又要重新点开。 */
    private final Set<String> expandedKeys = new java.util.LinkedHashSet<>();
    private final java.util.List<View> surfaces = new ArrayList<>();
    private final java.util.List<TextView> labels = new ArrayList<>();

    private boolean darkState = false;
    private boolean themeApplied = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshStates();
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
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        title = new TextView(this);
        title.setText("黑名单");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        root.addView(title);

        hint = new TextView(this);
        hint.setText("点一行改它的放行时段，长按从黑名单移除。");
        hint.setTextSize(12);
        hint.setTextColor(TEXT2);
        hint.setPadding(0, dp(4), 0, dp(12));
        labels.add(hint);
        root.addView(hint);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        empty = new TextView(this);
        empty.setText("黑名单是空的。点下面的「添加应用」选一个。");
        empty.setTextSize(13);
        empty.setTextColor(TEXT2);
        empty.setPadding(dp(2), dp(12), 0, dp(12));
        empty.setVisibility(View.GONE);
        labels.add(empty);
        root.addView(empty);

        addBtn = new Button(this);
        addBtn.setText("＋ 添加应用");
        addBtn.setAllCaps(false);
        addBtn.setTextSize(14);
        addBtn.setTextColor(BRAND);
        addBtn.setBackgroundColor(SURFACE);
        addBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!canAdd()) {
                    toast("全部应用都在屏蔽中，等某一应用进入自己的放行时段再加");
                    return;
                }
                startActivityForResult(new Intent(AppListActivity.this, AppPickerActivity.class), REQ_PICK);
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        alp.topMargin = dp(12);
        root.addView(addBtn, alp);

        // 按包名加：还没装的应用也能先进黑名单，装上之后自动被管
        pkgBtn = new Button(this);
        pkgBtn.setText("＋ 按包名添加");
        pkgBtn.setAllCaps(false);
        pkgBtn.setTextSize(14);
        pkgBtn.setTextColor(TEXT2);
        pkgBtn.setBackgroundColor(SURFACE);
        pkgBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!canAdd()) {
                    toast("全部应用都在屏蔽中，等某一应用进入自己的放行时段再加");
                    return;
                }
                promptPackage();
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        plp.topMargin = dp(6);
        root.addView(pkgBtn, plp);

        // 批量设时段：十几个应用要同一个规则时，逐个点进去改太反人类
        batchBtn = new Button(this);
        batchBtn.setText("⏱ 批量设时段");
        batchBtn.setAllCaps(false);
        batchBtn.setTextSize(14);
        batchBtn.setTextColor(BRAND);
        batchBtn.setBackgroundColor(SURFACE);
        batchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                batchSetRule();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        blp.topMargin = dp(6);
        root.addView(batchBtn, blp);

        // 关键应用名单：由用户自己列，不做任何自动猜测。
        // 名单里的应用在学习目标没完成时只按自己的放行时段走，不会被闸门延长冻结。
        keyBtn = new Button(this);
        keyBtn.setText("🔒 关键应用名单");
        keyBtn.setAllCaps(false);
        keyBtn.setTextSize(14);
        keyBtn.setTextColor(0xFF4CAF50);
        keyBtn.setBackgroundColor(SURFACE);
        keyBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openKeyApps();
            }
        });
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        klp.topMargin = dp(6);
        root.addView(keyBtn, klp);

        setContentView(scroll);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) {
            return;
        }
        Set<String> cur = RuleStore.packages(this);
        ArrayList<String> picked = data.getStringArrayListExtra("picked");
        Set<String> want = new java.util.LinkedHashSet<>();
        if (picked != null) {
            want.addAll(picked);
        }
        java.util.List<String> added = new java.util.ArrayList<>();
        for (String p : want) {
            if (!cur.contains(p)) {
                RuleStore.put(this, p, RuleStore.defaultRule(this));
                added.add(p);
            }
        }
        int refused = 0;
        for (String p : cur) {
            if (!want.contains(p)) {
                if (Guard.canRemove(this, p)) {
                    RuleStore.remove(this, p);
                } else {
                    refused++;
                }
            }
        }
        if (refused > 0) {
            toast(refused + " 个应用加入已超过 10 分钟，按「只进不出」不能移出黑名单");
        }
        rebuild();
        applyInBackground();
        // 一次加了好几个：直接问要不要统一设时段（默认规则多半不是想要的）
        if (!added.isEmpty()) {
            askBatchForNew(added);
        }
    }

    /* ==================== 列表 ==================== */

    private void rebuild() {
        applyDark(true);   // 黑名单页固定深色，不跟随屏蔽状态
        surfaces.clear();
        rows.clear();
        groups.clear();
        labels.clear();   // hint/empty 的颜色在 refreshStates 里单独刷，不会漏

        list.removeAllViews();
        Map<String, Rule> all = RuleStore.all(this);
        empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);

        // 按规则分组。LinkedHashMap 保留"第一次出现"的顺序 ——
        // 分组不能让列表每次刷新都重排，否则用户永远找不到刚才那一张。
        java.util.LinkedHashMap<String, Rule> ruleOf = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, java.util.List<String>> buckets =
                new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Rule> e : all.entrySet()) {
            String k = e.getValue().groupKey();
            if (!buckets.containsKey(k)) {
                ruleOf.put(k, e.getValue());
                buckets.put(k, new java.util.ArrayList<String>());
            }
            buckets.get(k).add(e.getKey());
        }
        // 规则改过之后旧 key 就不存在了，顺手清掉，别无限堆积
        expandedKeys.retainAll(buckets.keySet());

        for (Map.Entry<String, java.util.List<String>> e : buckets.entrySet()) {
            java.util.List<String> pkgs = e.getValue();
            if (pkgs.size() == 1) {
                // 只有它一个用这条规则：不折叠。否则每个应用都得点开才看得见
                Row r = makeRow(pkgs.get(0), false);
                rows.add(r);
                addCard(r.box);
            } else {
                Group g = makeGroup(e.getKey(), ruleOf.get(e.getKey()), pkgs);
                groups.add(g);
                addCard(g.card);
            }
        }
        refreshStates();
    }

    /** 卡片之间留一点缝，"一坨深色"看不出哪里是一行。 */
    private void addCard(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        list.addView(v, lp);
    }

    /**
     * 把用同一条规则的一批应用折成一张卡。
     * 卡上只写"几个应用 + 放行时段 + 名字预览"，点一下才展开明细。
     */
    private Group makeGroup(final String key, Rule rule, java.util.List<String> pkgs) {
        final Group g = new Group();
        g.key = key;
        g.pkgs = pkgs;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(SURFACE_DARK);   // 首帧别露白底

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        g.title = new TextView(this);
        g.title.setText(pkgs.size() + " 个应用");
        g.title.setTextSize(15);
        g.title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(g.title);

        g.sub = new TextView(this);
        // 复用 Guard.allowText：它已经带"放行"二字，也标了关键应用
        g.sub.setText(Guard.allowText(rule));
        g.sub.setTextSize(12);
        g.sub.setPadding(0, dp(2), 0, 0);
        labels.add(g.sub);
        left.addView(g.sub);

        g.names = new TextView(this);
        g.names.setText(namesLine(pkgs));
        g.names.setTextSize(11);
        g.names.setSingleLine(true);
        g.names.setEllipsize(android.text.TextUtils.TruncateAt.END);
        g.names.setPadding(0, dp(2), 0, 0);
        g.names.setTextColor(0xFF6B6B72);   // 比副标题再弱一级
        left.addView(g.names);

        head.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setPadding(dp(8), 0, 0, 0);

        g.state = new TextView(this);
        g.state.setTextSize(12);
        right.addView(g.state);

        g.arrow = new TextView(this);
        g.arrow.setTextSize(13);
        g.arrow.setTextColor(0xFF9B9BA2);
        g.arrow.setPadding(dp(6), 0, 0, 0);
        g.arrow.setText(expandedKeys.contains(key) ? "▾" : "▸");
        right.addView(g.arrow);
        head.addView(right);

        g.body = new LinearLayout(this);
        g.body.setOrientation(LinearLayout.VERTICAL);
        for (String pkg : pkgs) {
            Row r = makeRow(pkg, true);
            rows.add(r);
            g.body.addView(r.box);
        }

        // 折起来之后还给了"改这一组"：这些应用本来就是一伙的，
        // 没必要再回到顶部走「批量设时段」重选一遍。
        TextView editAll = new TextView(this);
        editAll.setText("改这一组的时段");
        editAll.setTextSize(12);
        editAll.setTextColor(BRAND);
        editAll.setPadding(dp(16), dp(10), dp(16), dp(12));
        editAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                applyRuleTo(new java.util.ArrayList<String>(g.pkgs));
            }
        });
        g.body.addView(editAll);
        g.body.setVisibility(expandedKeys.contains(key) ? View.VISIBLE : View.GONE);

        card.addView(head);
        card.addView(g.body);
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggle(g);
            }
        });

        g.card = card;
        surfaces.add(card);   // applyDark 会给它铺 surface 底
        return g;
    }

    private void toggle(Group g) {
        boolean on = g.body.getVisibility() != View.VISIBLE;
        g.body.setVisibility(on ? View.VISIBLE : View.GONE);
        g.arrow.setText(on ? "▾" : "▸");
        if (on) {
            expandedKeys.add(g.key);
        } else {
            expandedKeys.remove(g.key);
        }
    }

    /** 改完/套用完之后，把结果那张卡展开 —— 用户正想看它变成什么样。 */
    private void revealGroupOf(String pkg) {
        for (Group g : groups) {
            if (g.pkgs.size() > 1 && g.pkgs.contains(pkg)) {
                expandedKeys.add(g.key);
                g.body.setVisibility(View.VISIBLE);
                g.arrow.setText("▾");
                return;
            }
        }
    }

    /** 组卡上的名字预览："抖音、快手、小红书 等"。 */
    private String namesLine(java.util.List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        int show = Math.min(pkgs.size(), 3);
        for (int i = 0; i < show; i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(Guard.labelOf(getPackageManager(), pkgs.get(i)));
        }
        if (pkgs.size() > show) {
            sb.append(" 等");
        }
        return sb.toString();
    }

    private Row makeRow(final String pkg, boolean child) {
        Row r = new Row();
        r.pkg = pkg;
        r.child = child;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        // 子行往里缩一格，一眼能看出它是某张卡里的明细
        box.setPadding(dp(child ? 26 : 14), dp(10), dp(14), dp(10));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        String label = Guard.labelOf(getPackageManager(), pkg);

        r.name = new TextView(this);
        r.name.setText(label);
        r.name.setTextSize(child ? 14 : 15);
        r.name.setSingleLine(true);
        r.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        left.addView(r.name);

        if (!child) {
            // 组内的子行不重复写规则 —— 组头已经写了一遍，十几行都重复只显得挤
            TextView sub = new TextView(this);
            // 写明"放行"：只写 "每天 22:00-23:00" 会被读成"这段时间冻结"，语义正好相反
            sub.setText(Guard.allowText(RuleStore.get(this, pkg)));
            sub.setTextSize(12);
            sub.setTextColor(TEXT2);
            sub.setPadding(0, dp(2), 0, 0);
            labels.add(sub);
            left.addView(sub);
        }

        box.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        r.state = new TextView(this);
        r.state.setTextSize(12);
        r.state.setPadding(dp(8), 0, 0, 0);
        box.addView(r.state);

        r.box = box;
        // 固定深色：首帧就别露白底。子行用更深的底，跟组头分出层级
        box.setBackgroundColor(child ? CHILD_DARK : SURFACE_DARK);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                edit(pkg);
            }
        });
        box.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                confirmRemove(pkg);
                return true;
            }
        });
        return r;
    }

    /* ==================== 主题与状态刷新 ==================== */

    /**
     * 能不能加应用 —— 「只进不出」：**随时都可以加**，哪怕全部应用都在屏蔽中。
     * （旧版在这里设锁，结果空列表/屏蔽中想加应用都得干等；加入本身不构成绕过：
     * 新应用默认沿用上次的时段，当场就会按规则冻结。）
     */
    private boolean canAdd() {
        return true;
    }

    /** 每秒跑：刷每行状态 + 刷按钮可用性。主题固定深色。 */
    private void refreshStates() {
        boolean dark = true;   // 黑名单页固定深色
        applyDark(dark);

        int text2 = dark ? TEXT2_DARK : TEXT2;
        int red = dark ? RED_DARK : RED;
        int green = dark ? GREEN_DARK : GREEN;
        int surface = dark ? SURFACE_DARK : SURFACE;
        int childSurface = dark ? CHILD_DARK : SURFACE;

        // 这一秒的冻结/安装快照：行和组头共用，同一个应用只算一遍
        java.util.Map<String, Boolean> blockedOf = new java.util.HashMap<>();
        java.util.Map<String, Boolean> installedOf = new java.util.HashMap<>();

        for (Row r : rows) {
            String label = Guard.labelOf(getPackageManager(), r.pkg);
            boolean installed = !label.equals(r.pkg);
            boolean blocked = installed && Guard.blockedNow(this, r.pkg);
            blockedOf.put(r.pkg, blocked);
            installedOf.put(r.pkg, installed);

            r.name.setText(label);
            r.name.setTextColor(dark ? TEXT_DARK : TEXT);
            if (!installed) {
                r.state.setText("未安装");
                r.state.setTextColor(text2);
            } else if (blocked) {
                r.state.setText("冻结中");
                r.state.setTextColor(red);
            } else {
                r.state.setText("放行中");
                r.state.setTextColor(green);
            }
            r.box.setBackgroundColor(r.child ? childSurface : surface);
        }

        // 组头只报汇总："全部冻结 / 全部放行 / 5/10 冻结"
        for (Group g : groups) {
            int n = 0, b = 0;
            for (String p : g.pkgs) {
                Boolean ins = installedOf.get(p);
                if (ins == null || !ins) {
                    continue;
                }
                n++;
                Boolean bl = blockedOf.get(p);
                if (bl != null && bl) {
                    b++;
                }
            }
            g.title.setTextColor(dark ? TEXT_DARK : TEXT);
            if (n == 0) {
                g.state.setText("未安装");
                g.state.setTextColor(text2);
            } else if (b == 0) {
                g.state.setText("全部放行");
                g.state.setTextColor(green);
            } else if (b == n) {
                g.state.setText("全部冻结");
                g.state.setTextColor(red);
            } else {
                g.state.setText(b + "/" + n + " 冻结");
                g.state.setTextColor(red);
            }
        }

        boolean can = canAdd();
        addBtn.setEnabled(can);
        addBtn.setTextColor(can ? BRAND : 0xFF9A9AA0);
        addBtn.setBackgroundColor(can ? surface : (dark ? OFF_DARK : OFF_LIGHT));
        addBtn.setAlpha(can ? 1f : 0.55f);
        pkgBtn.setEnabled(can);
        pkgBtn.setTextColor(can ? text2 : 0xFF9A9AA0);
        pkgBtn.setBackgroundColor(can ? surface : (dark ? OFF_DARK : OFF_LIGHT));
        pkgBtn.setAlpha(can ? 1f : 0.55f);
        batchBtn.setTextColor(BRAND);
        batchBtn.setBackgroundColor(surface);
        batchBtn.setAlpha(1f);
        keyBtn.setText("🔒 关键应用名单 · " + RuleStore.keyApps(this).size() + " 个");
        keyBtn.setTextColor(0xFF4CAF50);
        keyBtn.setBackgroundColor(surface);
        keyBtn.setAlpha(1f);

        hint.setText("点一行改它的放行时段。时段相同的一批应用会折成一张卡，点卡片展开明细。"
                + "只进不出：加入后不能移出（刚加的 10 分钟内除外）。");
        hint.setTextColor(text2);
        empty.setTextColor(text2);
    }

    private void applyDark(boolean d) {
        // 每秒跑一次，颜色没变就别重设，免得闪烁
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
        title.setTextColor(d ? TEXT_DARK : TEXT);
        for (View v : surfaces) {
            v.setBackgroundColor(d ? SURFACE_DARK : SURFACE);
        }
        for (TextView v : labels) {
            v.setTextColor(d ? TEXT2_DARK : TEXT2);
        }
    }

    /* ==================== 编辑一个应用的时段 ==================== */

    /**
     * 规则编辑器：星期 + 多段时段。
     * 抽出来是为了「单个编辑」和「批量套用」共用同一套 UI —— 两处各写一遍迟早不一致。
     */
    private final class RuleEditor {
        final ScrollView view;
        private final int[] mask;
        private final java.util.List<int[]> wins = new java.util.ArrayList<int[]>();
        private final TextView daysText;
        private final TextView winText;
        private final LinearLayout winBox;
        private final Button[] dayBtns = new Button[7];
        private final android.widget.CheckBox gateCb;
        /** 批量套用时不显示闸门开关：改时段不该顺手改关键名单。 */
        private final boolean allowGateEdit;

        RuleEditor(Rule start) {
            this(start, true);
        }

        /**
         * @param allowGateEdit false = 不显示「参与学习闸门」开关，且 result() 的 gate
         *                      不参与写回（批量套用时保留各应用自己的关键名单状态）。
         */
        RuleEditor(Rule start, boolean allowGateEdit) {
            this.allowGateEdit = allowGateEdit;
            mask = new int[] { start.days };
            for (int i = 0; i < start.windowCount(); i++) {
                wins.add(new int[] { start.startAt(i), start.endAt(i) });
            }

            LinearLayout box = new LinearLayout(AppListActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(8), dp(20), 0);

            daysText = new TextView(AppListActivity.this);
            daysText.setTextSize(12);
            daysText.setTextColor(TEXT2);
            daysText.setText("放行星期（没选中的日子整天冻结）");
            box.addView(daysText);

            // 速选：最常用的几条规则。批量给十几个应用设时段时，
            // 逐个点 7 个星期 + 拨两次时间太费手，点一下就够。
            LinearLayout quick = new LinearLayout(AppListActivity.this);
            quick.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qlp.topMargin = dp(8);
            String[] qLabels = { "每天 22-23", "每天 21-23", "全天冻结" };
            for (int i = 0; i < qLabels.length; i++) {
                final int which = i;
                Button qb = new Button(AppListActivity.this);
                qb.setText(qLabels[i]);
                qb.setTextSize(12);
                qb.setAllCaps(false);
                qb.setPadding(0, 0, 0, 0);
                qb.setTextColor(TEXT);
                qb.setBackgroundColor(LINE);
                qb.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        applyPreset(which);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
                lp.rightMargin = dp(4);
                quick.addView(qb, lp);
            }
            box.addView(quick, qlp);

            LinearLayout dayRow = new LinearLayout(AppListActivity.this);
            dayRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(6);
            for (int i = 0; i < 7; i++) {
                final int idx = i;
                Button b = new Button(AppListActivity.this);
                b.setText(Rule.DAY_NAMES[i]);
                b.setTextSize(13);
                b.setAllCaps(false);
                b.setPadding(0, 0, 0, 0);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        mask[0] ^= (1 << idx);
                        styleDays();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
                lp.rightMargin = dp(3);
                dayRow.addView(b, lp);
                dayBtns[i] = b;
            }
            box.addView(dayRow, dlp);

            winText = new TextView(AppListActivity.this);
            winText.setTextSize(13);
            winText.setTextColor(TEXT);
            winText.setPadding(0, dp(14), 0, dp(4));
            box.addView(winText);

            winBox = new LinearLayout(AppListActivity.this);
            winBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = dp(4);
            box.addView(winBox, wlp);

            // 是否受学习闸门牵连。默认值按放行时长自动判断（≤6 小时/天算娱乐），
            // 这里让用户能改 —— 不然微信这种每天放行 18 小时的工具也会被锁。
            gateCb = new android.widget.CheckBox(AppListActivity.this);
            gateCb.setText("参与学习闸门（目标没完成时也跟着延长冻结）");
            gateCb.setTextSize(13);
            gateCb.setTextColor(TEXT);
            gateCb.setChecked(start.gate);
            gateCb.setPadding(0, dp(12), 0, 0);
            if (allowGateEdit) {
                box.addView(gateCb);
                TextView gateHint = new TextView(AppListActivity.this);
                gateHint.setText("工作/通讯类可以去黑名单页的「🔒 关键应用名单」里勾上，"
                        + "勾了就只按自己的时段冻结，学习目标不再延长它");
                gateHint.setTextSize(11);
                gateHint.setTextColor(TEXT2);
                gateHint.setPadding(0, dp(4), 0, 0);
                box.addView(gateHint);
            }

            // 内容可能超过一屏（7 个星期按钮 + 多段时段），套一层滚动
            view = new ScrollView(AppListActivity.this);
            view.addView(box);

            styleDays();
            refreshWins();
        }

        private void styleDays() {
            for (int k = 0; k < 7; k++) {
                boolean on = Rule.dayOn(mask[0], k);
                dayBtns[k].setTextColor(on ? 0xFFFFFFFF : TEXT2);
                dayBtns[k].setBackgroundColor(on ? BRAND : LINE);
            }
            daysText.setText("放行星期 " + Rule.daysText(mask[0]));
        }

        /** 0=每天 22-23 / 1=每天 21-23 / 2=全天冻结（一天都不选）。 */
        private void applyPreset(int which) {
            wins.clear();
            if (which == 0) {
                mask[0] = Rule.ALL_DAYS;
                wins.add(new int[] { 22 * 60, 23 * 60 });
            } else if (which == 1) {
                mask[0] = Rule.ALL_DAYS;
                wins.add(new int[] { 21 * 60, 23 * 60 });
            } else {
                mask[0] = 0;   // 一天都不选 = 整天冻结
            }
            // 三个预设都是"娱乐型"规则，勾上闸门才符合直觉
            gateCb.setChecked(true);
            styleDays();
            refreshWins();
        }

        private void refreshWins() {
            winBox.removeAllViews();
            if (wins.isEmpty()) {
                TextView none = new TextView(AppListActivity.this);
                none.setText("没有任何时段 = 整天冻结");
                none.setTextSize(12);
                none.setTextColor(TEXT2);
                winBox.addView(none);
            }
            for (int i = 0; i < wins.size(); i++) {
                final int idx = i;
                LinearLayout row = new LinearLayout(AppListActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView tv = new TextView(AppListActivity.this);
                tv.setText((i + 1) + ".　" + Rule.hm(wins.get(i)[0] / 60, wins.get(i)[0] % 60)
                        + " - " + Rule.hm(wins.get(i)[1] / 60, wins.get(i)[1] % 60));
                tv.setTextSize(14);
                tv.setTextColor(TEXT);
                row.addView(tv, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                Button chg = new Button(AppListActivity.this);
                chg.setText("改");
                chg.setTextSize(12);
                chg.setAllCaps(false);
                chg.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        pickWindow(wins, idx, new Runnable() {
                            public void run() {
                                refreshWins();
                            }
                        });
                    }
                });
                row.addView(chg, new LinearLayout.LayoutParams(dp(56), dp(38)));

                Button del = new Button(AppListActivity.this);
                del.setText("删");
                del.setTextSize(12);
                del.setAllCaps(false);
                del.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        wins.remove(idx);
                        refreshWins();
                    }
                });
                LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(dp(56), dp(38));
                dlp2.leftMargin = dp(4);
                row.addView(del, dlp2);
                winBox.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            Button add = new Button(AppListActivity.this);
            add.setText("＋ 加一段");
            add.setTextSize(12);
            add.setAllCaps(false);
            add.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    pickWindow(wins, -1, new Runnable() {
                        public void run() {
                            refreshWins();
                        }
                    });
                }
            });
            LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
            alp2.topMargin = dp(6);
            winBox.addView(add, alp2);
            winText.setText("放行时段　" + joinWins(wins));
        }

        Rule result() {
            int[][] w = new int[wins.size()][2];
            for (int i = 0; i < wins.size(); i++) {
                w[i][0] = wins.get(i)[0];
                w[i][1] = wins.get(i)[1];
            }
            return new Rule(mask[0], w, gateCb.isChecked());
        }
    }

    /* ==================== 单个编辑 ==================== */

    private void edit(final String pkg) {
        if (Guard.lockedNow(this, pkg)) {
            toast("它正在自己的屏蔽时间里，等它的放行时段才能改");
            return;
        }

        final RuleEditor ed = new RuleEditor(RuleStore.get(this, pkg));

        new AlertDialog.Builder(this)
                .setTitle(Guard.labelOf(getPackageManager(), pkg))
                .setView(ed.view)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Rule r = ed.result();
                        if (r.days == 0) {
                            toast("一天都没选 = 整天冻结，已保存");
                        }
                        RuleStore.put(AppListActivity.this, pkg, r);
                        rebuild();
                        revealGroupOf(pkg);
                        applyInBackground();
                    }
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("移除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (!Guard.canRemove(AppListActivity.this, pkg)) {
                            toast("只进不出：已加入的应用不能移出黑名单（刚加的 10 分钟内除外）");
                            return;
                        }
                        RuleStore.remove(AppListActivity.this, pkg);
                        rebuild();
                        applyInBackground();
                    }
                })
                .show();
    }

    /* ==================== 批量设时段 ==================== */

    /**
     * 一次给多个应用套用同一条规则。
     *
     * 正在自己屏蔽时段内的应用会被跳过（与单个编辑的锁定逻辑一致）——
     * 否则「先批量放开时段」就是一条绕过路径。
     */
    /**
     * 关键应用名单。
     *
     * 勾上的应用：学习目标没完成时**不**延长冻结，只按它自己的放行时段走。
     * 名单完全由用户维护 —— 不做"猜哪个像工作软件"的自动判定，猜错的代价
     * 是工作时间被锁死，比让用户多点两下严重得多。
     *
     * 多选同样自己画行：ColorOS 上 setMultiChoiceItems 的项 clickable=false。
     */
    private void openKeyApps() {
        final java.util.List<String> pkgs =
                new java.util.ArrayList<>(RuleStore.packages(this));
        if (pkgs.isEmpty()) {
            toast("黑名单是空的，先添加应用");
            return;
        }
        final String[] labels = new String[pkgs.size()];
        final boolean[] checked = new boolean[pkgs.size()];
        final Set<String> keys = RuleStore.keyApps(this);
        for (int i = 0; i < pkgs.size(); i++) {
            String p = pkgs.get(i);
            labels[i] = Guard.labelOf(getPackageManager(), p);
            checked[i] = keys.contains(p);
        }

        android.widget.ArrayAdapter<String> ad =
                new android.widget.ArrayAdapter<String>(this, 0, labels) {
                    @Override
                    public View getView(final int pos, View convert, ViewGroup parent) {
                        LinearLayout row = new LinearLayout(AppListActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(18), dp(12), dp(18), dp(12));

                        final android.widget.CheckBox cb =
                                new android.widget.CheckBox(AppListActivity.this);
                        cb.setChecked(checked[pos]);
                        cb.setClickable(false);
                        cb.setFocusable(false);
                        row.addView(cb);

                        TextView tv = new TextView(AppListActivity.this);
                        tv.setText(labels[pos]);
                        tv.setTextSize(15);
                        tv.setTextColor(checked[pos] ? 0xFF4CAF50 : TEXT);
                        tv.setPadding(dp(10), 0, 0, 0);
                        row.addView(tv, new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                        row.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                checked[pos] = !checked[pos];
                                cb.setChecked(checked[pos]);
                            }
                        });
                        return row;
                    }
                };

        new AlertDialog.Builder(this)
                .setTitle("关键应用（目标没完成也不延长冻结）")
                .setAdapter(ad, null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Set<String> picked = new java.util.LinkedHashSet<>();
                        for (int i = 0; i < pkgs.size(); i++) {
                            if (checked[i]) {
                                picked.add(pkgs.get(i));
                            }
                        }
                        RuleStore.setKeyApps(AppListActivity.this, picked);
                        rebuild();
                        applyInBackground();
                        toast("关键应用 " + picked.size() + " 个：目标没完成时只按自己的时段走");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void batchSetRule() {
        final java.util.List<String> pkgs =
                new java.util.ArrayList<>(RuleStore.packages(this));
        if (pkgs.isEmpty()) {
            toast("黑名单是空的，先添加应用");
            return;
        }
        final String[] labels = new String[pkgs.size()];
        final boolean[] checked = new boolean[pkgs.size()];
        final boolean[] locked = new boolean[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) {
            String p = pkgs.get(i);
            String name = Guard.labelOf(getPackageManager(), p);
            locked[i] = Guard.lockedNow(this, p);
            labels[i] = locked[i] ? (name + "（屏蔽中，跳过）") : name;
        }

        // ★ 自己画行，不用 setMultiChoiceItems：
        //   系统 simple_list_item_multiple_choice 里的 CheckedTextView 在 ColorOS 上是
        //   clickable="false"，点整行根本勾不上（实测点了没反应，只好自己接管点击）。
        android.widget.ArrayAdapter<String> ad =
                new android.widget.ArrayAdapter<String>(this, 0, labels) {
                    @Override
                    public View getView(final int pos, View convert, ViewGroup parent) {
                        LinearLayout row = new LinearLayout(AppListActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(18), dp(12), dp(18), dp(12));

                        final android.widget.CheckBox cb = new android.widget.CheckBox(AppListActivity.this);
                        cb.setChecked(checked[pos]);
                        cb.setClickable(false);
                        cb.setFocusable(false);
                        row.addView(cb);

                        TextView tv = new TextView(AppListActivity.this);
                        tv.setText(labels[pos]);
                        tv.setTextSize(15);
                        tv.setTextColor(locked[pos] ? 0xFF9A9AA0 : TEXT);
                        tv.setPadding(dp(10), 0, 0, 0);
                        row.addView(tv, new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                        if (!locked[pos]) {
                            row.setOnClickListener(new View.OnClickListener() {
                                public void onClick(View v) {
                                    checked[pos] = !checked[pos];
                                    cb.setChecked(checked[pos]);
                                }
                            });
                        }
                        return row;
                    }
                };

        new AlertDialog.Builder(this)
                .setTitle("选要一起设时段的 " + pkgs.size() + " 个应用")
                .setAdapter(ad, null)
                .setPositiveButton("下一步", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        java.util.List<String> picked = new java.util.ArrayList<>();
                        for (int i = 0; i < pkgs.size(); i++) {
                            if (checked[i] && !Guard.lockedNow(AppListActivity.this, pkgs.get(i))) {
                                picked.add(pkgs.get(i));
                            }
                        }
                        if (picked.isEmpty()) {
                            toast("没选应用（屏蔽中的会被跳过）");
                            return;
                        }
                        applyRuleTo(picked);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 弹出规则编辑器，套用到这批应用。 */
    private void applyRuleTo(final java.util.List<String> picked) {
        // false = 编辑框不显示闸门开关。批量改时段不该顺手把关键名单洗了：
        // 十个应用一次套用，gate 统一成同一个值时，微信这类会被悄悄拉回闸门。
        final RuleEditor ed = new RuleEditor(RuleStore.defaultRule(this), false);
        new AlertDialog.Builder(this)
                .setTitle("给这 " + picked.size() + " 个应用套用")
                .setView(ed.view)
                .setPositiveButton("套用", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Rule base = ed.result();
                        // 跟单个编辑同一条规矩：正在自己屏蔽时段里的不能改，
                        // 否则"批量放开时段"就是一条绕过路径
                        java.util.List<String> done = new java.util.ArrayList<>();
                        int skipped = 0;
                        for (String p : picked) {
                            if (Guard.lockedNow(AppListActivity.this, p)) {
                                skipped++;
                                continue;
                            }
                            Rule old = RuleStore.get(AppListActivity.this, p);
                            RuleStore.put(AppListActivity.this, p, base.withGate(old.gate));
                            done.add(p);
                        }
                        if (done.isEmpty()) {
                            toast("选中的应用都在自己的屏蔽时段里，改不了");
                            return;
                        }
                        rebuild();
                        revealGroupOf(done.get(0));
                        applyInBackground();
                        toast("已套用：" + Rule.daysText(base.days) + " " + base.windowText()
                                + " · " + done.size() + " 个"
                                + (skipped > 0 ? "（" + skipped + " 个屏蔽中，跳过）" : ""));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 刚添加了一批应用：直接问要不要统一设时段。
     * 不设也能用（沿用上次规则），但一次加十几个时逐个点进去改太反人类。
     */
    private void askBatchForNew(final java.util.List<String> added) {
        StringBuilder names = new StringBuilder();
        int show = Math.min(added.size(), 3);
        for (int i = 0; i < show; i++) {
            if (i > 0) {
                names.append("、");
            }
            names.append(Guard.labelOf(getPackageManager(), added.get(i)));
        }
        if (added.size() > show) {
            names.append(" 等 ").append(added.size()).append(" 个");
        }
        new AlertDialog.Builder(this)
                .setTitle("新加了 " + added.size() + " 个应用")
                .setMessage(names + "\n\n现在统一套一条放行时段？之后也可以单独改。")
                .setPositiveButton("现在设", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        applyRuleTo(added);
                    }
                })
                .setNegativeButton("用默认的", null)
                .show();
    }

    /** 时段列表拼成一行文案。 */
    private static String joinWins(java.util.List<int[]> wins) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wins.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(Rule.hm(wins.get(i)[0] / 60, wins.get(i)[0] % 60))
              .append('-')
              .append(Rule.hm(wins.get(i)[1] / 60, wins.get(i)[1] % 60));
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /**
     * 选一段起止时间：先弹起点再弹终点，确认后写回。
     * idx >= 0 是改第 idx 段；-1 是追加新段。
     */
    private void pickWindow(final java.util.List<int[]> wins, final int idx, final Runnable done) {
        int defStart = idx >= 0 ? wins.get(idx)[0] : 21 * 60;
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            public void onTimeSet(TimePicker view, int h, int m) {
                final int s = h * 60 + m;
                int defEnd = idx >= 0 ? wins.get(idx)[1] : 23 * 60;
                new TimePickerDialog(AppListActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    public void onTimeSet(TimePicker view, int h2, int m2) {
                        int e = h2 * 60 + m2;
                        if (s == e) {
                            toast("起点和终点不能相同");
                            return;
                        }
                        int[] seg = { s, e };
                        if (idx >= 0) {
                            wins.set(idx, seg);
                        } else {
                            wins.add(seg);
                        }
                        done.run();
                    }
                }, defEnd / 60, defEnd % 60, true).show();
            }
        }, defStart / 60, defStart % 60, true).show();
    }

    /** 手动输入包名 —— 主要给"还没装"的应用用，装上后自动被管。 */
    private void promptPackage() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("com.example.app");
        input.setSingleLine(true);
        input.setPadding(dp(20), dp(10), dp(20), dp(10));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(8), dp(4), dp(8), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("按包名添加到黑名单")
                .setMessage("应用还没装也可以先加：装上之后它会被自动冻结。\n包名必须完全一致，填错了这条规则不会匹配任何东西。")
                .setView(wrap)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String pkg = input.getText().toString().trim();
                        if (pkg.length() == 0) {
                            return;
                        }
                        if (RuleStore.packages(AppListActivity.this).contains(pkg)) {
                            toast("它已经在黑名单里");
                            return;
                        }
                        RuleStore.put(AppListActivity.this, pkg, RuleStore.defaultRule(AppListActivity.this));
                        rebuild();
                        applyInBackground();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemove(final String pkg) {
        if (!Guard.canRemove(this, pkg)) {
            toast("只进不出：已加入的应用不能移出黑名单（刚加的 10 分钟内除外）");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("移出黑名单")
                .setMessage(Guard.labelOf(getPackageManager(), pkg) + " 将不再被管理（立刻解冻）。")
                .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        RuleStore.remove(AppListActivity.this, pkg);
                        rebuild();
                        applyInBackground();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyInBackground() {
        new Thread(new Runnable() {
            public void run() {
                Guard.apply(AppListActivity.this);
            }
        }).start();
    }

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
