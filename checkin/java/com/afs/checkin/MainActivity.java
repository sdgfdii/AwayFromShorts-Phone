package com.afs.checkin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 打卡页：选一个目标，往上滑一次，就记一笔。
 *
 * 为什么是"上滑"：点按钮和点卡片没本质区别，但上滑是个**有方向的**动作，
 * 手指一走就知道自己在提交，中途还能反悔（没滑够就松手会回弹）。
 * 这比在网页里翻到目标卡、找输入框、点打卡顺得多。
 *
 * 数据流（手机写不了学习台，只能绕一圈）：
 *   上滑 -> 本地队列 -> Gist 的 checkin-queue.json -> PC 每 15 分钟写进学习台
 * 所以打完之后手机这边立刻显示"已打"，页面上的统计要等 PC 同步。
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0E0F11;
    private static final int SURFACE = 0xFF1A1C20;
    private static final int SURFACE_SEL = 0xFF23272E;
    private static final int TEXT = 0xFFF2F3F5;
    private static final int TEXT2 = 0xFF8A9099;
    private static final int TEXT3 = 0xFF5C6169;
    private static final int ACCENT = 0xFF00AEEC;
    private static final int GREEN = 0xFF3DDC84;
    private static final int RED = 0xFFFF6B63;

    private final List<Store.Goal> goals = new ArrayList<>();
    private int selected = -1;

    private LinearLayout list;
    private TextView topTitle;
    private TextView topSub;
    private TextView hintMain;
    private TextView hintSub;
    private TextView arrow;

    /** 正在被手指拖着的那张卡（每次 render 后失效，要在拖之前现取）。 */
    private View dragCard;
    private float downY;
    private float downX;
    private boolean dragging;
    private boolean busy;
    /** 上次推送失败的原因（null = 没失败）。 */
    private String pushError;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
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

    /* ==================== UI 骨架 ==================== */

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        // ---- 顶部 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(22), dp(20), dp(10));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        topTitle = new TextView(this);
        topTitle.setText("打卡");
        topTitle.setTextColor(TEXT);
        topTitle.setTextSize(24);
        topTitle.setTypeface(Typeface.DEFAULT_BOLD);
        // 长按标题改 token：不做设置页，这个 App 只有一个配置项
        topTitle.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                askToken();
                return true;
            }
        });
        titles.addView(topTitle);

        topSub = new TextView(this);
        topSub.setTextColor(TEXT2);
        topSub.setTextSize(12);
        topSub.setPadding(0, dp(3), 0, 0);
        titles.addView(topSub);
        head.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refresh = new TextView(this);
        refresh.setText("刷新");
        refresh.setTextColor(ACCENT);
        refresh.setTextSize(13);
        refresh.setPadding(dp(14), dp(8), dp(0), dp(8));
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                topSub.setText("正在读取…");
                refreshAsync();
                pushAsync();
            }
        });
        head.addView(refresh);
        root.addView(head);

        // ---- 目标列表 ----
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), dp(4));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 底部上滑区 ----
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(16), dp(10), dp(16), dp(26));

        arrow = new TextView(this);
        arrow.setText("↑");
        arrow.setTextColor(ACCENT);
        arrow.setTextSize(30);
        arrow.setGravity(Gravity.CENTER);
        foot.addView(arrow);

        hintMain = new TextView(this);
        hintMain.setText("上滑打卡");
        hintMain.setTextColor(TEXT);
        hintMain.setTextSize(17);
        hintMain.setGravity(Gravity.CENTER);
        hintMain.setTypeface(Typeface.DEFAULT_BOLD);
        foot.addView(hintMain);

        hintSub = new TextView(this);
        hintSub.setTextColor(TEXT2);
        hintSub.setTextSize(12);
        hintSub.setGravity(Gravity.CENTER);
        hintSub.setPadding(0, dp(4), 0, 0);
        foot.addView(hintSub);

        root.addView(foot);
        setContentView(root);
    }

    /* ==================== 渲染 ==================== */

    private void render() {
        dragCard = null;
        list.removeAllViews();

        int pending = 0;
        for (Store.Goal g : goals) {
            if (!hit(g)) {
                pending++;
            }
        }

        if (goals.isEmpty()) {
            topSub.setText(Store.goalsAt(this).isEmpty()
                    ? "还没读到学习台数据"
                    : "上次同步 " + Store.goalsAt(this));
            TextView empty = new TextView(this);
            empty.setText("读不到今日目标。\n\n"
                    + "这个 App 的数据来自电脑端学习台，PC 那一刻没在同步就会空着。\n"
                    + "点右上角「刷新」重试。");
            empty.setTextColor(TEXT2);
            empty.setTextSize(13);
            empty.setLineSpacing(dp(4), 1f);
            empty.setPadding(dp(8), dp(30), dp(8), 0);
            list.addView(empty);
            hintMain.setText("暂无可打卡目标");
            hintSub.setText(Store.goalsDate(this).isEmpty() ? "" : "数据日期 " + Store.goalsDate(this));
            selected = -1;
            return;
        }

        topSub.setText(statusLine(goals));

        if (selected < 0 || selected >= goals.size()) {
            selected = firstPending();
        }
        if (selected < 0) {
            selected = 0;
        }

        for (int i = 0; i < goals.size(); i++) {
            list.addView(goalCard(goals.get(i), i));
        }

        Store.Goal g = goals.get(selected);
        if (pending == 0) {
            hintMain.setText("今天全部打完");
            hintMain.setTextColor(GREEN);
            hintSub.setText("明天继续");
        } else if (hit(g)) {
            hintMain.setText("这个已打过");
            hintMain.setTextColor(TEXT2);
            hintSub.setText("点上面的卡片换一个目标，再上滑");
        } else {
            hintMain.setText("上滑打卡");
            hintMain.setTextColor(TEXT);
            hintSub.setText(g.name + "  +" + fmt(suggestOf(g)) + " " + g.unit);
        }
        arrow.setTextColor(pending == 0 ? GREEN : ACCENT);
    }

    private View goalCard(final Store.Goal g, final int index) {
        boolean on = index == selected;
        boolean done = hit(g);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(13), dp(14), dp(13));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? SURFACE_SEL : SURFACE);
        bg.setCornerRadius(dp(14));
        if (on) {
            bg.setStroke(dp(1), 0xFF3A4048);
        }
        box.setBackground(bg);

        // 左边的竖条：选中=品牌色，打过=绿色
        View bar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(done ? GREEN : (on ? ACCENT : 0xFF2A2E34));
        barBg.setCornerRadius(dp(2));
        bar.setBackground(barBg);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(3), dp(30));
        barLp.rightMargin = dp(12);
        box.addView(bar, barLp);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(g.name);
        name.setTextColor(done ? TEXT2 : TEXT);
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name);

        TextView meta = new TextView(this);
        meta.setText("已 " + fmt(g.done) + " / " + fmt(g.total) + " " + g.unit
                + "   " + pct(g.done, g.total) + "%");
        meta.setTextColor(TEXT3);
        meta.setTextSize(11);
        meta.setPadding(0, dp(3), 0, 0);
        mid.addView(meta);

        box.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView right = new TextView(this);
        if (done) {
            right.setText("已打卡");
            right.setTextColor(GREEN);
            right.setTextSize(12);
        } else {
            right.setText("+" + fmt(suggestOf(g)) + " " + g.unit);
            right.setTextColor(on ? ACCENT : TEXT2);
            right.setTextSize(13);
        }
        right.setPadding(dp(8), 0, 0, 0);
        box.addView(right);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (selected != index) {
                    selected = index;
                    render();
                } else if (!hit(g)) {
                    askAmount(g);
                }
            }
        });
        // 长按改这一笔的数量（默认用建议量）
        box.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (!hit(g)) {
                    askAmount(g);
                }
                return true;
            }
        });
        return box;
    }

    /* ==================== 上滑手势 ==================== */

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getRawY();
                downX = ev.getRawX();
                dragging = false;
                return super.dispatchTouchEvent(ev);

            case MotionEvent.ACTION_MOVE: {
                float dy = downY - ev.getRawY();
                float dx = Math.abs(ev.getRawX() - downX);
                // 明确是"往上"才接管，否则交给子视图（否则一点击就被吞掉）
                if (!dragging && dy > dp(24) && dy > dx * 1.5f) {
                    dragging = true;
                    dragCard = cardAt(selected);
                }
                if (dragging) {
                    onDrag(dy);
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }

            case MotionEvent.ACTION_UP: {
                if (dragging) {
                    dragging = false;
                    onRelease(downY - ev.getRawY());
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }

            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    springBack();
                    return true;
                }
                return super.dispatchTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private View cardAt(int i) {
        if (i < 0 || i >= list.getChildCount()) {
            return null;
        }
        return list.getChildAt(i);
    }

    private int threshold() {
        return dp(130);
    }

    private void onDrag(float dy) {
        float max = dp(230);
        float d = Math.min(Math.max(dy, 0), max);
        View c = dragCard;
        if (c != null) {
            c.setTranslationY(-d);
            c.setAlpha(1f - 0.5f * (d / max));
        }
        float p = d / threshold();
        if (p >= 1f) {
            hintMain.setText("松手，打卡");
            hintMain.setTextColor(ACCENT);
            hintSub.setText("↑ 到底了");
            arrow.setTextColor(GREEN);
        } else {
            hintMain.setText("再往上一点…");
            hintMain.setTextColor(TEXT2);
            arrow.setTextColor(ACCENT);
        }
    }

    private void onRelease(float dy) {
        if (dy >= threshold()) {
            commit();
        } else {
            springBack();
        }
    }

    private void springBack() {
        View c = dragCard;
        if (c != null) {
            c.animate().translationY(0).alpha(1f).setDuration(170).start();
        }
        dragCard = null;
        Store.Goal g = selectedGoal();
        hintMain.setText("上滑打卡");
        hintMain.setTextColor(TEXT);
        if (g != null && !hit(g)) {
            hintSub.setText(g.name + "  +" + fmt(suggestOf(g)) + " " + g.unit);
        }
        arrow.setTextColor(ACCENT);
    }

    /* ==================== 提交 ==================== */

    private void commit() {
        final Store.Goal g = selectedGoal();
        if (g == null || busy) {
            springBack();
            return;
        }
        if (hit(g)) {
            springBack();
            toast("「" + g.name + "」今天已经打过了");
            return;
        }
        busy = true;
        final double amount = suggestOf(g);

        // 先把卡片的动画走完，再重排 —— 重排会把这张卡换掉，动画就断了
        final View c = dragCard;
        Runnable after = new Runnable() {
            public void run() {
                busy = false;
                Store.add(MainActivity.this, g, amount, today());
                g.localHit = true;
                selectNext();
                render();
                vibrate();
                toast("已打卡 " + g.name + "  +" + fmt(amount) + " " + g.unit);
                pushAsync();
            }
        };
        if (c != null) {
            c.animate().translationY(-dp(420)).alpha(0f).setDuration(230)
                    .withEndAction(after).start();
            dragCard = null;
        } else {
            after.run();
        }
    }

    private void selectNext() {
        int start = selected < 0 ? 0 : selected;
        for (int k = 1; k <= goals.size(); k++) {
            int i = (start + k) % goals.size();
            if (!hit(goals.get(i))) {
                selected = i;
                return;
            }
        }
    }

    private void askAmount(final Store.Goal g) {
        final EditText ed = new EditText(this);
        ed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ed.setText(fmt(suggestOf(g)));
        ed.setSelectAllOnFocus(true);
        ed.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this)
                .setTitle(g.name + " 打多少 " + g.unit + "？")
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
                        Store.add(MainActivity.this, g, v, today());
                        g.localHit = true;
                        selectNext();
                        render();
                        vibrate();
                        toast("已打卡 " + g.name + "  +" + fmt(v) + " " + g.unit);
                        pushAsync();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ==================== 同步 ==================== */

    private void loadLocal() {
        goals.clear();
        goals.addAll(Store.goals(this));
        selected = firstPending();
    }

    private void refreshAsync() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String raw = Gist.get(Gist.rawUrl(Gist.FILE_GATE));
                    final JSONObject o = new JSONObject(raw);
                    String date = o.optString("date", "");
                    JSONArray arr = o.optJSONArray("goals");
                    final List<Store.Goal> fresh = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject x = arr.optJSONObject(i);
                            if (x == null) {
                                continue;
                            }
                            Store.Goal g = new Store.Goal();
                            g.id = x.optString("id", "");
                            g.name = x.optString("name", "");
                            g.unit = x.optString("unit", "个");
                            g.total = x.optDouble("total", 0);
                            g.done = x.optDouble("done", 0);
                            g.suggest = x.optDouble("suggest", 0);
                            g.todayHit = x.optBoolean("todayHit", false);
                            if (!g.id.isEmpty() && !g.name.isEmpty()) {
                                fresh.add(g);
                            }
                        }
                    }
                    final String at = o.optString("checkedAt", "");
                    runOnUiThread(new Runnable() {
                        public void run() {
                            applyFresh(fresh, date, at);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (goals.isEmpty()) {
                                topSub.setText("读不到数据 · " + brief(e));
                            }
                        }
                    });
                }
            }
        }).start();
    }

    /** 用远端数据替换本地，但保留"本地刚打过、PC 还不知道"的标记。 */
    private void applyFresh(List<Store.Goal> fresh, String date, String at) {
        String prevDate = Store.goalsDate(this);
        JSONArray keep = new JSONArray();
        for (Store.Goal g : goals) {
            if (g.localHit && !g.todayHit) {
                // 刚打完、PC 还没回来：不能因为远端说"没打"就把刚打的抹掉
                for (Store.Goal f : fresh) {
                    if (f.id.equals(g.id)) {
                        f.localHit = true;
                        f.todayHit = true;
                    }
                }
            }
        }
        goals.clear();
        goals.addAll(fresh);
        try {
            for (Store.Goal g : goals) {
                JSONObject x = new JSONObject();
                x.put("id", g.id);
                x.put("name", g.name);
                x.put("unit", g.unit);
                x.put("total", g.total);
                x.put("done", g.done);
                x.put("suggest", g.suggest);
                x.put("todayHit", g.todayHit);
                keep.put(x);
            }
        } catch (Throwable ignored) {
        }
        Store.saveGoals(this, keep.toString(), date, at);
        if (!prevDate.equals(date)) {
            selected = -1;
        }
        render();
        // 顺便把"PC 是否已经记账"对齐一次
        syncFlagsAsync();
    }

    private void pushAsync() {
        final List<Store.Item> pend = Store.pending(this);
        if (pend.isEmpty()) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final String err = Gist.patchFile(Store.token(MainActivity.this),
                        Gist.FILE_QUEUE, queueJson(pend));
                runOnUiThread(new Runnable() {
                    public void run() {
                        pushError = err;
                        // 只改状态行，不重建列表 —— 重建会把用户正在拖的卡片一起换掉
                        topSub.setText(statusLine(goals));
                        if (err != null) {
                            toast("同步没成功：" + err + "（已存在手机上，会重试）");
                        }
                    }
                });
            }
        }).start();
    }

    /** 状态行：还剩几个待打卡 + 同步情况。 */
    private String statusLine(List<Store.Goal> gs) {
        int pending = 0;
        for (Store.Goal g : gs) {
            if (!hit(g)) {
                pending++;
            }
        }
        String head = gs.isEmpty() ? "还没读到学习台数据"
                : (pending == 0 ? "今天的都打完了" : "还有 " + pending + " 个待打卡");
        int q = Store.pending(this).size();
        String sync;
        if (q > 0) {
            sync = "待记账 " + q + " 条";
        } else if (pushError != null) {
            sync = "同步失败";
        } else {
            String at = Store.goalsAt(this);
            sync = at.isEmpty() ? "" : "同步 " + at;
        }
        return sync.isEmpty() ? head : head + " · " + sync;
    }

    private void syncFlagsAsync() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String raw = Gist.get(Gist.rawUrl(Gist.FILE_QUEUE));
                    JSONArray arr = new JSONObject(raw).optJSONArray("items");
                    List<Store.Item> remote = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null) {
                                continue;
                            }
                            Store.Item it = new Store.Item();
                            it.id = o.optString("id", "");
                            remote.add(it);
                        }
                    }
                    Store.markApplied(MainActivity.this, remote);
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    /**
     * 队列文件：除了打卡条目，还要带上「打完这些之后还剩几个目标」。
     *
     * ★ pendingAfter 是守卫 App 立刻解闸的唯一凭据。
     *   只报"我打了几条"是不够的 —— 守卫不知道你还有几个目标没动，
     *   于是只能退化成"有一条就放行"，那等于打一个就全豁免，闸门就废了。
     */
    private String queueJson(List<Store.Item> items) {
        int pending = 0;
        for (Store.Goal g : goals) {
            if (!hit(g)) {
                pending++;
            }
        }
        StringBuilder sb = new StringBuilder(64 + items.size() * 120);
        sb.append("{\"date\":").append(Gist.quote(today()))
                .append(",\"active\":").append(goals.size())
                .append(",\"pendingAfter\":").append(pending)
                .append(",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            Store.Item it = items.get(i);
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
        String cur = Store.token(this);
        if (!cur.isEmpty()) {
            ed.setText(cur);
        }
        new AlertDialog.Builder(this)
                .setTitle("同步凭据")
                .setMessage("打卡要写进 Gist 才能同步回电脑。\n粘一个只勾了 gist 权限的 token 即可。")
                .setView(ed)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Store.setToken(MainActivity.this, ed.getText().toString());
                        toast("已保存");
                        pushAsync();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private Store.Goal selectedGoal() {
        if (selected < 0 || selected >= goals.size()) {
            return null;
        }
        return goals.get(selected);
    }

    private int firstPending() {
        for (int i = 0; i < goals.size(); i++) {
            if (!hit(goals.get(i))) {
                return i;
            }
        }
        return goals.isEmpty() ? -1 : 0;
    }

    private static boolean hit(Store.Goal g) {
        return g.todayHit || g.localHit;
    }

    private static double suggestOf(Store.Goal g) {
        if (g.suggest > 0) {
            return g.suggest;
        }
        double remain = g.total - g.done;
        return remain > 0 ? Math.ceil(remain) : 1;
    }

    private static String today() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return f.format(new java.util.Date());
    }

    private static int pct(double done, double total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.floor(done * 100 / total);
    }

    private static String fmt(double v) {
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

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(28, 140));
            } else {
                v.vibrate(28);
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
}
