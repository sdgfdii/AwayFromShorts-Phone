package com.afs.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Native embedded memory module. All card content stays text/native Canvas, never executable HTML. */
public class MemoryActivity extends ThemedActivity {
    private static final int IMAGE = 51, TEXT = 52, EXPORT = 53, RESTORE = 54, AUTHOR = 55;
    private static final String[] AUTHOR_FORMATS = {"Markdown（按学科与题目标记）", "JSON（含脚本生成的 cards.json）", "JSONL（每行一个对象）", "CSV（含表头）", "TSV（含表头）", "TXT / TSV（无表头，Tab 分列）"};
    private static final String[] AUTHOR_KEYS = {"md", "json", "jsonl", "csv", "tsv", "txt"};
    private int authorFormat, authorType;
    private String authorSubject = "";
    private static final String[] TYPES = {"问答", "输入答案", "图片遮挡"};
    private static final String[] TYPE_KEYS = {"qa", "input", "image"};
    private static final String[] IMPORT_TYPES = {"问答", "输入答案"};
    private static final String[] IMPORT_KEYS = {"qa", "input"};
    private Ui ui;
    private LinearLayout body;
    private String deck = "", page = "home", importType = "qa", lastDeleted = "";
    private JSONObject draft, current;
    private EditText front, answer, source, note, typed;
    private Spinner type;
    private MemoryImageView editorImage, studyImage;
    private boolean revealed, submitting, incorrect;
    private boolean flashMode;
    private boolean managementMode;
    private String flashDate = "";
    private final Handler handler = new Handler();
    private final Runnable queueTick = new Runnable() {
        public void run() {
            if ("review".equals(page) && current == null) review();
        }
    };
    /** Runs after the swipe exit animation; a rated card must never come back into view. */
    private final Runnable advanceTick = new Runnable() {
        public void run() { advance(); }
    };
    protected void onCreate(Bundle saved) {
        if (Ui.isDark(this)) setTheme(getResources().getIdentifier("NavThemeDark", "style", getPackageName()));
        super.onCreate(saved); ui = new Ui(this);
        managementMode=getIntent().getBooleanExtra(CardManagementActivity.EXTRA_MANAGE,false);
        if(managementMode&&saved==null)page="tools";
        deck = saved == null ? getIntent().getStringExtra("deck") : saved.getString("deck", "");
        if (deck == null) deck = "";
        if (saved == null && !deck.isEmpty()) page = "review";
        if (saved != null) {
            page = saved.getString("page", "home"); importType = saved.getString("importType", "qa");lastDeleted=saved.getString("lastDeleted","");
            authorFormat = saved.getInt("authorFormat", 0); authorType = saved.getInt("authorType", 0); authorSubject = saved.getString("authorSubject", "");
            try { String s = saved.getString("draft"); if (s != null) draft = new JSONObject(s); } catch (Exception ignored) { }
        }
        try {
            if ("editor".equals(page) && draft != null) editor();
            else if ("review".equals(page)) review();
            else if ("cards".equals(page)) cards(false);
            else if ("tools".equals(page)) tools();
            else home();
        } catch (RuntimeException e) { errorPage(e); }
    }
    protected void onSaveInstanceState(Bundle out) {
        if ("editor".equals(page)) collectDraft();
        out.putString("deck", deck); out.putString("page", page); out.putString("importType", importType);out.putString("lastDeleted",lastDeleted);
        out.putInt("authorFormat", authorFormat); out.putInt("authorType", authorType); out.putString("authorSubject", authorSubject);
        if (draft != null) out.putString("draft", draft.toString()); super.onSaveInstanceState(out);
    }
    protected void onPause() { super.onPause(); handler.removeCallbacks(queueTick); handler.removeCallbacks(advanceTick); }
    protected void onResume() {
        super.onResume();
        if (ui != null && ui.dark != Ui.isDark(this)) { recreate(); return; }
        FloatingNav.fullscreen(this);
        // The card was already graded before the exit animation; resume must not show it again.
        if (submitting) { submitting = false; advance(); return; }
        if ("home".equals(page) && !flashDate.equals(MemoryData.date(System.currentTimeMillis()))) { home(); return; }
        if ("review".equals(page) && current == null) handler.postDelayed(queueTick, 1000);
    }
    public void onBackPressed() {
        if ("home".equals(page)) { super.onBackPressed(); return; }
        if ("editor".equals(page)) {
            ThemeStore.dialog(this).setTitle("放弃本次编辑？")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃", (d, w) -> home()).show();
        } else if ("tools".equals(page)&&managementMode) finish();
        else if(managementMode)tools();else home();
    }
    private void base(String title, String subtitle) {
        handler.removeCallbacks(queueTick); handler.removeCallbacks(advanceTick); current = null;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); ui.page(scroll);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32)); scroll.addView(body);
        setContentView(FloatingNav.wrap(this, scroll, managementMode?4:0)); getWindow().setStatusBarColor(ui.bg()); getWindow().setNavigationBarColor(ui.bg());
        FloatingNav.fullscreen(this);
        if (!"home".equals(page)) {
        TextView back = ui.sub("tools".equals(page) ? "‹  卡管理" : "‹  记忆卡设置");
        back.setTextColor(ui.accent()); back.setPadding(0, ui.dp(8), 0, ui.dp(18));
        back.setOnClickListener(v -> onBackPressed()); body.addView(back);
        }
        if ("home".equals(page)) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView heading = ui.title(title);
            row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(row);
        } else body.addView(ui.title(title));
        if (!subtitle.isEmpty()) hint(body, subtitle);
    }
    private TextView hint(LinearLayout parent, String text) {
        TextView t = ui.sub(text); t.setLineSpacing(ui.dp(4), 1); t.setPadding(0, ui.dp(8), 0, ui.dp(12));
        SwissTypography.role(t,SwissTypography.Role.META);parent.addView(t); return t;
    }
    private TextView text(LinearLayout parent, String s, int size) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(ui.text());
        SwissTypography.role(t,size>=22?SwissTypography.Role.TITLE:SwissTypography.Role.BODY);
        t.setTextIsSelectable(true); t.setLineSpacing(ui.dp(5), 1); t.setPadding(0, ui.dp(12), 0, ui.dp(12));
        parent.addView(t); return t;
    }
    private TextView button(LinearLayout parent, String label, boolean primary, View.OnClickListener action) {
        TextView t = new TextView(this); t.setText(label); t.setTextSize(15);
        SwissTypography.role(t,SwissTypography.Role.BUTTON);
        t.setTextColor(primary ? ui.onAccent() : ui.accent()); t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(14));
        t.setBackground(ui.round(primary ? ui.accent() : ui.amap?ui.surface():ui.surface2(),ui.amap?99:12));
        parent.addView(t, ui.gap(10)); t.setOnClickListener(action); return t;
    }
    private EditText field(LinearLayout parent, String label, String value, boolean multiline) {
        hint(parent, label); EditText e = new EditText(this); e.setText(value); e.setTextSize(15);
        e.setTextColor(ui.text()); e.setHintTextColor(ui.text3()); e.setSingleLine(!multiline);
        if (multiline) { e.setMinLines(2); e.setGravity(Gravity.TOP); e.setInputType(131073); }
        e.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); e.setBackground(ui.round(ui.surface2(), 10));
        parent.addView(e, ui.gap(10)); return e;
    }
    private Spinner spinner(LinearLayout parent, String[] values, int selection) {
        Spinner s = new Spinner(this);
        // Platform spinner uses the Activity's light theme; explicit text colors keep both themes readable.
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            public View getView(int p, View v, ViewGroup g) {
                TextView t = (TextView) super.getView(p, v, g); t.setTextColor(ui.text()); t.setTextSize(16); return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); s.setSelection(selection);
        parent.addView(s, ui.gap(12)); return s;
    }
    private void home() {
        page = "home"; flashMode = true; draft = null; MemoryData m;
        try { m = MemoryStore.flashSnapshot(this); } catch (RuntimeException e) { errorPage(e); return; }
        long now = System.currentTimeMillis(); flashDate = MemoryData.date(now);
        MemoryFlashcards.Progress p = MemoryFlashcards.progress(m, now);
        JSONObject next = MemoryFlashcards.next(m, now);
        base("本日记忆闪卡", "");
        revealed = false; submitting = false; incorrect = false; current = next; studyImage = null; typed = null;
        if (next == null) {
            boolean duePool = MemoryFlashcards.POOL_DUE.equals(MemoryFlashcards.pool(m));
            LinearLayout empty = ui.card(); body.addView(empty, ui.gap(12));
            text(empty, p.total > 0 ? "本日闪卡已完成" : duePool ? "今天没有到期卡片" : "暂无可抽取的闪卡", 24);
            hint(empty, p.total > 0 ? "复习记录已保存，明天按抽取设置生成新的闪卡。"
                    : duePool ? "当前只抽到期卡片。可在守卫 → 卡管理 → 记忆卡调整抽取设置。"
                    : p.available == 0 ? "在守卫 → 卡管理 → 记忆卡中添加学科、制作或导入卡片。"
                    : "所选学科的卡片今天已经复习过。可以调整学科范围，或明天继续。");
            return;
        }
        deck = next.optString("deck"); JSONObject d = m.deck(deck);
        hint(body, (p.done + 1) + "/" + p.total);
        drawCard(m, d, next);
    }
    private void flashSettings() {
        MemoryData m = MemoryStore.flashSnapshot(this); JSONArray active = m.activeDecks();
        if (active.length() == 0) { toast("请先在卡管理的记忆卡中添加学科"); return; }
        JSONObject config = MemoryFlashcards.settings(m); JSONArray selected = MemoryFlashcards.subjects(m);
        LinearLayout box = dialogBox(); hint(box, "抽取学科（可多选）");
        CheckBox[] checks = new CheckBox[active.length()];
        for (int i = 0; i < active.length(); i++) {
            JSONObject d = active.optJSONObject(i); CheckBox check = new CheckBox(this);
            check.setText(d.optString("name") + " · " + m.count(d.optString("id")) + " 张");
            check.setTextColor(ui.text()); check.setChecked(MemoryData.contains(selected, d.optString("id")));
            box.addView(check); checks[i] = check;
        }
        EditText count = field(box, "抽取数量（1–1000 张）", "" + config.optInt("count", 20), false); count.setInputType(2);
        hint(box, "抽取池"); Spinner pool = spinner(box, new String[]{"全部卡片（含未到期）", "仅到期卡片（按记忆曲线）"},
                MemoryFlashcards.POOL_DUE.equals(MemoryFlashcards.pool(m)) ? 1 : 0);
        hint(box, "抽取规则"); Spinner mode = spinner(box, new String[]{"随机抽取", "顺序执行 · 按入库顺序跨日接续"},
                "sequence".equals(config.optString("mode")) ? 1 : 0);
        hint(box, "“全部卡片”包含尚未到复习日期的卡片；“仅到期卡片”只抽今天到期的卡片，按到期时间从早到晚。每张卡在今日闪卡中只出现一次。\n保存后重新抽取今日尚未复习的卡片，已完成记录保留。");
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        AlertDialog dialog = ThemeStore.dialog(this).setTitle("今日闪卡设置").setView(scroll)
                .setPositiveButton("保存并抽取", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
            JSONArray ids = new JSONArray();
            for (int i = 0; i < checks.length; i++) if (checks[i].isChecked()) ids.put(active.optJSONObject(i).optString("id"));
            try {
                MemoryStore.configureFlashcards(this, ids, Integer.parseInt(count.getText().toString().trim()),
                        mode.getSelectedItemPosition() == 0 ? "random" : "sequence",
                        pool.getSelectedItemPosition() == 0 ? MemoryFlashcards.POOL_ALL : MemoryFlashcards.POOL_DUE);
                dialog.dismiss(); if(managementMode)tools();else home();
            } catch (RuntimeException e) { toast(e instanceof NumberFormatException ? "请填写 1–1000 的整数" : e.getMessage()); }
        })); dialog.show();
    }
    private void tools() {
        page = "tools"; flashMode = false; draft = null; MemoryData m;
        try { m = MemoryStore.snapshot(this); } catch (RuntimeException e) { errorPage(e); return; }
        base("记忆卡设置", "学科、卡片、错题、学习设置与备份。");
        MemoryFlashcards.Progress flash = MemoryFlashcards.progress(m, System.currentTimeMillis());
        JSONObject config = MemoryFlashcards.settings(m);
        button(body, "抽取设置  ·  " + ("sequence".equals(config.optString("mode")) ? "顺序" : "随机")
                + "  ·  " + config.optInt("count", 20) + " 张", false, v -> flashSettings());
        hint(body, "本日已复习 " + flash.done + "/" + flash.total + " · 今日累计 " + flash.reviewedToday + " 张。"
                + "\n数量不足时按剩余可用卡片抽取。"
                + "\n先回忆再揭晓。每张闪卡复习后进入下一张；选择“忘了”会进入错题与重学计划。");
        button(body, "＋ 添加学科", false, v -> addDeck());
        button(body, "制作 / 导入卡片", true, v -> authorDialog());
        button(body, "管理学科", false, v -> manageSubjects());
        if(!lastDeleted.isEmpty() && m.deleted(lastDeleted))button(body,"已移入回收站 · 撤回",false,v->restoreSubject(lastDeleted));
        JSONArray active=m.activeDecks();
        if(active.length()==0)hint(body,"暂无学科，点击上方添加，或从回收站恢复。");
        for (int i = 0; i < active.length(); i++) {
            final JSONObject d = active.optJSONObject(i); final String id = d.optString("id");
            MemoryData.Progress p = m.progress(id, System.currentTimeMillis());
            LinearLayout box = ui.card(); body.addView(box, ui.gap(12));
            TextView title = text(box, d.optString("name"), 21); title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextIsSelectable(false);title.setOnLongClickListener(v->{deleteSubject(id);return true;});
            hint(box, m.count(id) + " 张卡片 · 今日有效复习 " + p.done + " / " + p.target
                    + "\n今日队列：复习 " + p.reviewCount + " · 新学 " + p.newCount
                    + "\n学习联动：" + (LearningLink.memoryEnabled(this, id) ? "已启用" : "未启用")
                    + "\n记忆率 " + Math.round(MemoryData.retention(d) * 100) + "% · 上限 " + MemoryData.maxInterval(d)
                    + " 天 · 方案 " + MemoryPlan.label(MemoryPlan.match(d.optInt("newLimit"), d.optInt("reviewLimit"),
                        d.optInt("target"), MemoryData.retention(d), MemoryData.maxInterval(d))));
            button(box, p.ready > 0 ? "开始学习 · " + p.ready + " 张可学" : p.nextDue > 0 ? "查看重学倒计时" : "今日学习", true, v -> {
                if(managementMode)startActivity(new Intent(this,MemoryActivity.class).putExtra("deck",id));else{deck=id;review();}
            });
            LinearLayout row = new LinearLayout(this); box.addView(row);
            small(row, "卡片", v -> { deck = id; cards(false); });
            small(row, "错题", v -> { deck = id; cards(true); });
            small(row, "设置", v -> { deck = id; settings(d); });
        }
        button(body,"学科回收站 · "+m.deletedDecks().length(),false,v->subjectTrash());
        button(body, "导出备份（含图片与复习记录）", false, v -> pickExport());
        button(body, "恢复备份", false, v -> confirmRestore());
        button(body, "开源组件与许可证", false, v -> license());
        hint(body, "每天每张卡只计一次。新卡和遗忘卡须完成学习步骤后才计数。\n今日目标最多为今日队列数量。开始答题后，当日计划固定，设置次日生效。");
    }
    private void manageSubjects() {
        try {
            JSONArray active=MemoryStore.snapshot(this).activeDecks();
            if(active.length()==0){subjectTrash();return;}
            String[] names=new String[active.length()];
            for(int i=0;i<names.length;i++)names[i]="删除「"+active.optJSONObject(i).optString("name")+"」";
            ThemeStore.dialog(this).setTitle("管理学科").setItems(names,(d,which)->deleteSubject(active.optJSONObject(which).optString("id")))
                .setNeutralButton("回收站",(d,w)->subjectTrash()).setNegativeButton("关闭",null).show();
        }catch(RuntimeException e){toast(e.getMessage());}
    }
    private void deleteSubject(String id) {
        try {
            MemoryData data=MemoryStore.snapshot(this);JSONObject subject=data.deck(id);
            if(subject==null || data.deleted(id))return;
            SubjectDeletion.confirm(this,ui,subject.optString("name"),data.count(id)+" 张卡片",()->{
                try{MemoryStore.deleteDeck(this,id);lastDeleted=id;syncGuard();tools();}
                catch(RuntimeException e){toast(e.getMessage());}
            });
        }catch(RuntimeException e){toast(e.getMessage());}
    }
    private void restoreSubject(String id) {
        try{MemoryStore.restoreDeck(this,id);lastDeleted="";syncGuard();tools();toast("学科与卡片已恢复");}
        catch(RuntimeException e){toast(e.getMessage());}
    }
    private void subjectTrash() {
        try {
            MemoryData data=MemoryStore.snapshot(this);JSONArray trash=data.deletedDecks();
            if(trash.length()==0){toast("回收站为空");return;}
            String[] names=new String[trash.length()];
            for(int i=0;i<names.length;i++){JSONObject d=trash.optJSONObject(i);names[i]="恢复「"+d.optString("name")+"」 · "+data.count(d.optString("id"))+" 张卡片";}
            ThemeStore.dialog(this).setTitle("学科回收站").setItems(names,(d,which)->restoreSubject(trash.optJSONObject(which).optString("id"))).setNegativeButton("关闭",null).show();
        }catch(RuntimeException e){toast(e.getMessage());}
    }
    private void small(LinearLayout row, String s, View.OnClickListener onClick) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(ui.accent()); t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12)); t.setTextSize(14);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1)); t.setOnClickListener(onClick);
    }
    private void addDeck() {
        LinearLayout box = dialogBox(); EditText name = field(box, "学科名称", "", false);
        name.setHint("例如：电路、药理学、计算机网络");
        AlertDialog dlg = ThemeStore.dialog(this).setTitle("添加学科").setView(box)
                .setPositiveButton("添加", null).setNegativeButton("取消", null).create();
        dlg.setOnShowListener(ignored -> dlg.getButton(-1).setOnClickListener(v -> {
            try { String value=name.getText().toString().trim();MemoryStore.addDeck(this,value,value);dlg.dismiss();tools(); }
            catch (RuntimeException e) { name.setError(e.getMessage()); }
        })); dlg.show();
    }
    private LinearLayout dialogBox() {
        LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        b.setBackgroundColor(ui.surface()); return b;
    }
    private int number(EditText e, int fallback) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (RuntimeException x) { return fallback; }
    }
    private void settings(JSONObject d) {
        LinearLayout b = dialogBox();
        // The preset row lives in its own container so it can stay on top while the fields are declared first.
        final LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.VERTICAL);
        b.addView(top, ui.gap(2));
        EditText name = field(b, "名称", d.optString("name"), false);
        EditText nl = field(b, "每日新卡上限（0–200）", "" + d.optInt("newLimit"), false);
        EditText rl = field(b, "每日复习上限（1–1000）", "" + d.optInt("reviewLimit"), false);
        EditText target = field(b, "每日有效复习目标（1–1000）", "" + d.optInt("target"), false);
        EditText retention = field(b, "目标记忆率（70–97%）", "" + Math.round(MemoryData.retention(d) * 100), false);
        EditText maxInterval = field(b, "单次复习上限间隔（1–36500 天）", "" + MemoryData.maxInterval(d), false);
        for (EditText e : new EditText[]{nl, rl, target, retention, maxInterval}) e.setInputType(2);
        hint(top, "复习方案");
        LinearLayout presets = new LinearLayout(this); presets.setOrientation(LinearLayout.HORIZONTAL); top.addView(presets);
        final MemoryPlan.Preset[] plans = MemoryPlan.presets();
        final TextView[] chips = new TextView[plans.length];
        for (int i = 0; i < plans.length; i++) {
            final MemoryPlan.Preset plan = plans[i];
            TextView chip = new TextView(this); chip.setText(plan.label); chip.setTextSize(13); chip.setGravity(Gravity.CENTER);
            SwissTypography.role(chip, SwissTypography.Role.BUTTON);
            chip.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.rightMargin = i == plans.length - 1 ? 0 : ui.dp(6);
            presets.addView(chip, lp);
            chip.setOnClickListener(v -> {
                nl.setText("" + plan.newLimit); rl.setText("" + plan.reviewLimit); target.setText("" + plan.target);
                retention.setText("" + Math.round(plan.retention * 100)); maxInterval.setText("" + plan.maxInterval);
            });
            chips[i] = chip;
        }
        final TextView planLabel = hint(top, "");
        final Runnable render = () -> {
            int percent = number(retention, -1);
            String matched = percent < 0 ? MemoryPlan.CUSTOM
                    : MemoryPlan.match(number(nl, -1), number(rl, -1), number(target, -1),
                        percent / 100.0, number(maxInterval, -1));
            planLabel.setText("当前方案：" + MemoryPlan.label(matched));
            for (int i = 0; i < plans.length; i++) {
                boolean on = plans[i].id.equals(matched);
                chips[i].setTextColor(on ? ui.onAccent() : ui.accent());
                chips[i].setBackground(ui.round(on ? ui.accent() : ui.amap ? ui.surface() : ui.surface2(), ui.amap ? 99 : 12));
            }
        };
        for (EditText e : new EditText[]{nl, rl, target, retention, maxInterval}) {
            e.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int c, int f) { }
                public void onTextChanged(CharSequence s, int a, int b, int c) { render.run(); }
                public void afterTextChanged(Editable s) { }
            });
        }
        render.run();
        CheckBox linked = new CheckBox(this);
        linked.setText("参与学习联动");
        linked.setTextColor(ui.text());
        linked.setChecked(LearningLink.memoryEnabled(this, d.optString("id")));
        b.addView(linked);
        hint(b, "开启后，打卡页显示记忆卡；在记忆页完成目标。20:00 时目标未完成会参与学习联动。\n开始答题后修改设置，次日生效。到期复习积压超过上限时，暂停新增卡片。");
        ScrollView scroll = new ScrollView(this); scroll.addView(b);
        AlertDialog dlg = ThemeStore.dialog(this).setTitle("学习计划").setView(scroll)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dlg.setOnShowListener(ignored -> dlg.getButton(-1).setOnClickListener(v -> {
            try {
                MemoryStore.settings(this, d.optString("id"), name.getText().toString(),
                        Integer.parseInt(nl.getText().toString()), Integer.parseInt(rl.getText().toString()),
                        Integer.parseInt(target.getText().toString()), linked.isChecked(),
                        Integer.parseInt(retention.getText().toString()) / 100.0,
                        Integer.parseInt(maxInterval.getText().toString()));
                LearningLink.setMemoryEnabled(this, d.optString("id"), linked.isChecked());
                dlg.dismiss(); syncGuard(); tools();
            } catch (RuntimeException e) { toast(e instanceof NumberFormatException ? "请填写整数" : e.getMessage()); }
        })); dlg.show();
    }
    private void cards(boolean wrong) {
        page = "cards"; MemoryData m = MemoryStore.snapshot(this); JSONObject d = m.deck(deck);
        if (d == null) { home(); return; }
        base(d.optString("name") + (wrong ? " · 错题" : " · 卡片"), wrong ? "收集曾经选择“忘了”的卡片。到期后自动进入学习队列。" : "问答、输入与图片遮挡；点卡片可以编辑。");
        button(body, "新建卡片", true, v -> { draft = MemoryData.newCard(deck, "english".equals(d.optString("subject")) ? "input" : "qa", "", "", "", ""); editor(); });
        button(body, "批量导入文本 / TSV", false, v -> importDialog());
        EditText search = field(body, "搜索题目或答案", "", false);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); body.addView(list);
        Runnable render = () -> {
            list.removeAllViews(); String q = search.getText().toString().toLowerCase(Locale.ROOT); int count = 0, shown = 0;
            for (int i = 0; i < m.cards().length(); i++) {
                JSONObject c = m.cards().optJSONObject(i);
                if (!deck.equals(c.optString("deck")) || (wrong && c.optInt("lapses") == 0)) continue;
                if (!(c.optString("front") + " " + c.optString("answer")).toLowerCase(Locale.ROOT).contains(q)) continue;
                count++; if (shown++ >= 100) continue;
                LinearLayout card = ui.card(); list.addView(card, ui.gap(10));
                TextView title = text(card, c.optString("front"), 16); title.setMaxLines(3);
                title.setTextIsSelectable(false);
                hint(card, "复习 " + c.optInt("reps") + " 次 · 遗忘 " + c.optInt("lapses") + " 次 · 点此编辑");
                card.setOnClickListener(v -> { draft = MemoryData.copy(c); editor(); });
            }
            hint(list, count == 0 ? "还没有卡片。可手动添加，或导入每行一张的问答文本。" : "共 " + count + " 张" + (count > 100 ? "，显示前 100 张，请用搜索缩小范围" : ""));
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { render.run(); }
            public void afterTextChanged(Editable s) { }
        }); render.run();
    }
    private void editor() {
        page = "editor"; base("编辑卡片", "图片题请先选图，再拖动框选标注。");
        int selection = 0; for (int i = 0; i < TYPE_KEYS.length; i++) if (TYPE_KEYS[i].equals(draft.optString("type"))) selection = i;
        type = spinner(body, TYPES, selection);
        front = field(body, "题目", draft.optString("front"), true);
        answer = field(body, "答案（输入题可用 | 分隔可接受答案）", draft.optString("answer"), true);
        source = field(body, "来源（教材 / 页码 / 链接）", draft.optString("source"), false);
        note = field(body, "备注与解释", draft.optString("note"), true);
        editorImage = null;
        if (!draft.optString("image").isEmpty()) {
            hint(body, "拖动添加矩形遮挡。所有遮挡区域作为同一张卡复习。");
            editorImage = new MemoryImageView(this, draft.optString("image"), draft.optJSONArray("masks"), true);
            body.addView(editorImage, new LinearLayout.LayoutParams(-1, ui.dp(320)));
            button(body, "撤销最后一个遮挡", false, v -> editorImage.undo());
        }
        button(body, "选择 / 更换图片", false, v -> {
            collectDraft(); Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT); it.setType("image/*"); it.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(it, IMAGE);
        });
        button(body, "保存卡片", true, v -> {
            try { collectDraft(); MemoryStore.saveCard(this, draft); toast("已保存"); cards(false); }
            catch (RuntimeException e) { toast(e.getMessage()); }
        });
    }
    private void collectDraft() {
        if (draft == null || front == null) return;
        MemoryData.put(draft, "type", TYPE_KEYS[type.getSelectedItemPosition()]);
        MemoryData.put(draft, "front", front.getText().toString().trim()); MemoryData.put(draft, "answer", answer.getText().toString().trim());
        MemoryData.put(draft, "source", source.getText().toString().trim()); MemoryData.put(draft, "note", note.getText().toString().trim());
        if (editorImage != null) MemoryData.put(draft, "masks", editorImage.masks());
    }
    private void review() {
        flashMode = false;
        page = "review"; MemoryData m = MemoryStore.snapshot(this); JSONObject d = m.deck(deck);
        if (d == null) { home(); return; }
        MemoryData.Progress p = m.progress(deck, System.currentTimeMillis());
        JSONObject next = m.next(deck, System.currentTimeMillis());
        base(d.optString("name"), "今日有效复习 " + p.done + " / " + p.target + " · 队列 " + p.total + " 张");
        revealed = false; submitting = false; incorrect = false; current = next; studyImage = null; typed = null;
        if (next == null) {
            if (p.nextDue > 0) {
                long seconds = Math.max(1, (p.nextDue - System.currentTimeMillis() + 999) / 1000);
                text(body, "重学卡片将在 " + seconds / 60 + " 分 " + seconds % 60 + " 秒后出现", 21);
                hint(body, "先休息一下。到期后自动显示下一张，重复翻看不增加完成量。");
                handler.postDelayed(queueTick, 1000);
            } else {
                text(body, p.total == 0 ? "今天没有待学卡片" : "今日队列已完成", 23);
                hint(body, p.total == 0 ? "先添加卡片；开始答题后新增的卡片将进入明天的计划。" : "复习进度已保存。放行仍遵守原时间表和其他学习目标。");
                hint(body,"卡片设置位于守卫 → 卡管理 → 记忆卡。");
            }
            button(body, "返回记忆首页", true, v -> home()); return;
        }
        drawCard(m, d, next);
    }
    private void drawCard(MemoryData m, JSONObject d, JSONObject next) {
        final String id = next.optString("id"); final boolean isInput = "input".equals(next.optString("type"));
        final LinearLayout card = ui.card();
        final SwipeCardView swipe = new SwipeCardView(this);
        swipe.addView(card, new FrameLayout.LayoutParams(-1, -2));
        body.addView(swipe, ui.gap(16));
        TextView question = text(card, MemoryData.question(next), 25);
        // Selectable text would steal long-press/drag away from the swipe gesture (see card list precedent).
        question.setTextIsSelectable(false);
        if (!next.optString("image").isEmpty()) {
            studyImage = new MemoryImageView(this, next.optString("image"), next.optJSONArray("masks"), false);
            card.addView(studyImage, new LinearLayout.LayoutParams(-1, ui.dp(300)));
        }
        if (isInput) typed = field(card, "先输入你的答案", "", false);
        LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); result.setVisibility(View.GONE); card.addView(result);
        final TextView swipeTip = hint(body, swipeTipText());
        swipe.setListener(direction -> {
            int rating = MemorySwipe.rating(direction);
            if (rating == 0) return;
            if (!grade(id, rating)) { swipe.reset(); return; }
            swipe.setSwipeEnabled(false);
            swipe.flyOut(direction);
            handler.postDelayed(advanceTick, slideDuration());
        });
        TextView show = button(body, isInput ? "核对答案" : "显示答案", true, v -> {
            if (revealed) return;
            if (isInput && typed.getText().toString().trim().isEmpty()) { toast("请先尝试输入答案"); return; }
            revealed = true;
            if (studyImage != null) studyImage.reveal();
            if (isInput) {
                boolean ok = MemoryData.matches(typed.getText().toString(), next.optString("answer"), "english".equals(d.optString("subject")));
                incorrect = !ok; typed.setEnabled(false); hint(result, ok ? "答案匹配" : "答案不匹配，本次计为遗忘，稍后重学");
            }
            TextView answer = text(result, MemoryData.answer(next), 20);
            answer.setTextIsSelectable(false);
            if (!next.optString("source").isEmpty()) hint(result, "来源：" + next.optString("source"));
            if (!next.optString("note").isEmpty()) hint(result, "备注：" + next.optString("note"));
            result.setVisibility(View.VISIBLE); v.setVisibility(View.GONE);
            armSwipe(swipe, swipeTip);
            if (incorrect) rateButton(body, id, "继续 · 加入重学", 1);
            else {
                rateButton(body, id, "忘了（加入重学）", 1);
                rateButton(body, id, "费力想起", 2);
                rateButton(body, id, "记住了", 3);
                rateButton(body, id, "很轻松", 4);
            }
        });
        if (isInput) button(body, "想不起来 · 查看答案", false, v -> {
            if (revealed) return; incorrect = true; revealed = true;
            typed.setEnabled(false); show.setVisibility(View.GONE); v.setVisibility(View.GONE);
            TextView answer = text(result, MemoryData.answer(next), 20);
            answer.setTextIsSelectable(false);
            if (!next.optString("source").isEmpty()) hint(result, "来源：" + next.optString("source"));
            if (!next.optString("note").isEmpty()) hint(result, "备注：" + next.optString("note"));
            result.setVisibility(View.VISIBLE);
            armSwipe(swipe, swipeTip);
            rateButton(body, id, "继续 · 加入重学", 1);
        });
        if (!flashMode) hint(body, "先回忆再揭晓。新卡需要完成学习步骤；选择“忘了”不计完成，稍后会再次出现。");
    }
    /** Both reveal branches arm the same gesture: down always allowed, up blocked for a wrong input answer. */
    private void armSwipe(SwipeCardView swipe, TextView tip) {
        boolean allowUp = !incorrect;
        swipe.setDirections(allowUp, true);
        swipe.setSwipeEnabled(true);
        tip.setText(swipeTipText(allowUp));
    }
    private String swipeTipText() { return swipeTipText(true); }
    private String swipeTipText(boolean allowUp) {
        String base = (allowUp ? "上滑：记住了" : "上滑不可用（答案不匹配）")
                + "\n下滑：忘了（加入重学）";
        return revealed ? base : base + "\n揭晓答案后可上下滑动评分";
    }
    private int slideDuration() {
        return !getSharedPreferences(Ui.SP, 0).getBoolean("reduce_motion", false)
                && (android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()) ? 140 : 0;
    }
    /** Single grading path for buttons and swipes: guard, persist, toast. Navigation stays with the caller. */
    private boolean grade(String id, int rating) {
        if (!revealed || submitting) return false;
        submitting = true;
        try {
            LearningLink.Progress before = LearningLink.memory(this);
            if (flashMode) { MemoryStore.rateFlashcard(this, id, rating, flashDate); syncGuard(); }
            else { boolean credited = MemoryStore.rate(this, id, rating); if (credited) toast("有效复习 +1，进度已保存"); syncGuard(); }
            LearningLink.Progress after = LearningLink.memory(this);
            if (before.target > 0 && !before.complete() && after.complete()) {
                Intent completed = new Intent(this, CheckinActivity.class);
                completed.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                completed.putExtra(CheckinActivity.EXTRA_LEARNING_COMPLETE, "memory");
                startActivity(completed);
                overridePendingTransition(0, 0);
            }
            return true;
        } catch (RuntimeException e) { submitting = false; toast(e.getMessage()); if (flashMode) home(); return false; }
    }
    private void advance() {
        submitting = false;
        // A stale delayed advance must never pull the user out of a page they opened meanwhile.
        if (!"home".equals(page) && !"review".equals(page)) return;
        if (flashMode) home(); else review();
    }
    private void rateButton(LinearLayout parent, String id, String label, int rating) {
        button(parent, label, rating == 3, v -> { if (grade(id, rating)) advance(); });
    }
    private void authorDialog() {
        LinearLayout b = dialogBox();
        hint(b, "选择文件或粘贴已准备好的题目与答案，校验并预览后入库。按内容中的学科自动归类，缺少的学科会在确认后创建。");
        hint(b, "内容格式"); Spinner format = spinner(b, AUTHOR_FORMATS, authorFormat);
        EditText subject = field(b, "默认学科（内容已含学科时可留空）", authorSubject, false);
        subject.setHint("例如：英语单词、医学、工科概念");
        hint(b, "默认题型（卡片内指定的题型优先）");
        Spinner kind = spinner(b, new String[]{"自动：词表为输入卡，其余为问答", "问答", "输入答案", "填空 {{答案}}"}, authorType);
        EditText content = field(b, "粘贴内容", "", true); content.setMinLines(5);
        button(b, "填入三学科示例", false, v -> { format.setSelection(0); kind.setSelection(0); content.setText(MemoryAuthoring.TEMPLATE); });
        hint(b, "JSON / CSV / TSV 字段：subject、type、front、answer、source、note，也支持中文字段名。词表可用 word、meaning。\n无表头格式：题目〔Tab〕答案〔Tab〕来源〔Tab〕备注，需填写默认学科。文件最大 16MB，支持 UTF-8 / GB18030。");
        ScrollView scroll = new ScrollView(this); scroll.addView(b);
        AlertDialog dlg = ThemeStore.dialog(this).setTitle("制作 / 导入卡片").setView(scroll)
            .setPositiveButton("校验并预览", null).setNeutralButton("选择文件", null).setNegativeButton("取消", null).create();
        dlg.setOnShowListener(ignored -> {
            View.OnClickListener remember = v -> {
                authorFormat = format.getSelectedItemPosition(); authorType = kind.getSelectedItemPosition(); authorSubject = subject.getText().toString().trim();
            };
            dlg.getButton(-1).setOnClickListener(v -> {
                remember.onClick(v); String input = content.getText().toString();
                String fmt = AUTHOR_KEYS[authorFormat], sub = authorSubject, typ = authorTypeKey();
                dlg.getButton(-1).setEnabled(false); dlg.getButton(-3).setEnabled(false);
                work("校验卡片", () -> {
                    try {
                        MemoryAuthoring.Batch batch = MemoryAuthoring.parse(input, fmt, sub, typ);
                        MemoryAuthoring.Result preview = MemoryStore.previewCards(this, batch);
                        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { dlg.dismiss(); authorPreview(batch, preview); } });
                    } finally { runOnUiThread(() -> { if (dlg.isShowing()) { dlg.getButton(-1).setEnabled(true); dlg.getButton(-3).setEnabled(true); } }); }
                });
            });
            dlg.getButton(-3).setOnClickListener(v -> {
                remember.onClick(v); dlg.dismiss();
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT); it.setType("*/*"); it.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(it, AUTHOR);
            });
        }); dlg.show();
    }
    private String authorTypeKey() { return new String[]{"", "qa", "input", "cloze"}[authorType]; }
    private void authorPreview(MemoryAuthoring.Batch batch, MemoryAuthoring.Result preview) {
        LinearLayout b = dialogBox(); text(b, preview.summary(), 15);
        hint(b, "确认后仅新增卡片，已有卡片和复习记录保留。复习独立于打卡。");
        ScrollView scroll = new ScrollView(this); scroll.addView(b);
        AlertDialog dlg = ThemeStore.dialog(this).setTitle("制卡预览").setView(scroll)
            .setPositiveButton("确认入库", null).setNegativeButton("取消", null).create();
        dlg.setOnShowListener(ignored -> {
            dlg.getButton(-1).setEnabled(preview.added > 0);
            dlg.getButton(-1).setOnClickListener(v -> {
                dlg.getButton(-1).setEnabled(false);
                work("保存卡片", () -> {
                    try {
                        MemoryAuthoring.Result result = MemoryStore.importCards(this, batch);
                        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { dlg.dismiss(); tools(); toast("已新增 " + result.added + " 张，跳过重复 " + result.skipped + " 张"); } });
                    } catch (Exception e) { runOnUiThread(() -> { if (dlg.isShowing()) dlg.getButton(-1).setEnabled(true); }); throw e; }
                });
            });
        }); dlg.show();
    }
    private void importDialog() {
        LinearLayout b = dialogBox(); Spinner s = spinner(b, IMPORT_TYPES, 0);
        hint(b, "每行一张，用 Tab 分隔：题目、答案、来源（可选）、备注（可选）。\n可从表格复制两列。完全相同的卡片会跳过。\n示例：memory〔Tab〕记忆");
        EditText content = field(b, "粘贴内容", "", true); content.setMinLines(4);
        ScrollView scroll = new ScrollView(this); scroll.addView(b);
        AlertDialog dlg = ThemeStore.dialog(this).setTitle("批量导入").setView(scroll)
            .setPositiveButton("导入粘贴内容", null).setNeutralButton("选择 TXT / TSV 文件", null).setNegativeButton("取消", null).create();
        dlg.setOnShowListener(ignored -> {
            dlg.getButton(-1).setOnClickListener(v -> {
                importType = IMPORT_KEYS[s.getSelectedItemPosition()]; String input = content.getText().toString();
                work("导入卡片", () -> {
                    int count = MemoryStore.importText(this, deck, input, importType);
                    runOnUiThread(() -> { dlg.dismiss(); toast("已导入 " + count + " 张"); cards(false); });
                });
            });
            dlg.getButton(-3).setOnClickListener(v -> {
                importType = IMPORT_KEYS[s.getSelectedItemPosition()]; dlg.dismiss();
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT); it.setType("*/*"); it.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(it, TEXT);
            });
        }); dlg.show();
    }
    private void pickExport() {
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT); it.setType("application/zip"); it.addCategory(Intent.CATEGORY_OPENABLE);
        it.putExtra(Intent.EXTRA_TITLE, "AFS-memory-" + MemoryData.date(System.currentTimeMillis()) + ".zip"); startActivityForResult(it, EXPORT);
    }
    private void confirmRestore() {
        ThemeStore.dialog(this).setTitle("恢复记忆备份")
            .setMessage("会替换当前记忆卡库、复习记录与记忆目标设置。建议先导出当前备份。原有普通学习目标与屏蔽时间表不受影响。")
            .setNegativeButton("取消", null).setPositiveButton("选择备份", (d, w) -> {
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT); it.setType("*/*"); it.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(it, RESTORE);
            }).show();
    }
    protected void onActivityResult(int request, int result, Intent it) {
        super.onActivityResult(request, result, it);
        if (result != RESULT_OK || it == null || it.getData() == null) { if (request == AUTHOR) authorDialog(); return; } Uri uri = it.getData();
        work("处理文件", () -> {
            if (request == AUTHOR) {
                String input;
                try (InputStream in = getContentResolver().openInputStream(uri)) { input = MemoryAuthoring.decode(MemoryStore.read(in, MemoryAuthoring.MAX_BYTES)); }
                MemoryAuthoring.Batch batch = MemoryAuthoring.parse(input, AUTHOR_KEYS[authorFormat], authorSubject, authorTypeKey());
                MemoryAuthoring.Result preview = MemoryStore.previewCards(this, batch);
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) authorPreview(batch, preview); });
            } else if (request == IMAGE) {
                String name = MemoryStore.importImage(this, uri); MemoryData.put(draft, "image", name); MemoryData.put(draft, "masks", new JSONArray());
                runOnUiThread(() -> editor());
            } else if (request == TEXT) {
                int count;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    String input = new String(MemoryStore.read(in, 8 * 1024 * 1024), "UTF-8").replace("\uFEFF", "");
                    count = MemoryStore.importText(this, deck, input, importType);
                }
                final int n = count; runOnUiThread(() -> { toast("已导入 " + n + " 张"); cards(false); });
            } else if (request == EXPORT) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) { MemoryStore.exportBackup(this, out); }
                runOnUiThread(() -> toast("备份已导出"));
            } else if (request == RESTORE) {
                try (InputStream in = getContentResolver().openInputStream(uri)) { MemoryStore.restoreBackup(this, in); }
                syncGuard(); runOnUiThread(() -> { toast("备份已恢复"); home(); });
            }
        });
    }
    private interface Job { void run() throws Exception; }
    private void work(String title, Job job) {
        final int orientation = getRequestedOrientation();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        ProgressDialog progress = ProgressDialog.show(this, title, "请稍候…", true, false);
        new Thread(() -> {
            try { job.run(); }
            catch (Exception e) { runOnUiThread(() -> toast(e.getMessage() == null ? "操作失败" : e.getMessage())); }
            finally { runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) { progress.dismiss(); setRequestedOrientation(orientation); }
            }); }
        }, "afs-memory-io").start();
    }
    private void syncGuard() {
        final android.content.Context c = getApplicationContext();
        new Thread(() -> { try { Guard.apply(c); } catch (RuntimeException ignored) { } }, "afs-memory-gate").start();
    }
    private void license() {
        try (InputStream in = getResources().openRawResource(getResources().getIdentifier("fsrs_license", "raw", getPackageName()))) {
            String license = new String(MemoryStore.read(in, 20000), "UTF-8");
            ThemeStore.dialog(this).setTitle("FSRS-6 · Open Spaced Repetition")
                .setMessage("调度核心适配自 java-fsrs\n版本 c561ee6c56239b621a0a45b16a6c2e688441eae5\nhttps://github.com/open-spaced-repetition/java-fsrs\n\n" + license)
                .setPositiveButton("关闭", null).show();
        } catch (Exception e) { toast(e.getMessage()); }
    }
    private void errorPage(RuntimeException e) {
        page = "home"; base("记忆库暂时不可用", e.getMessage());
        button(body, "恢复备份", true, v -> confirmRestore());
        button(body, "返回首页", false, v -> finish());
    }
    private void toast(String s) { Toast.makeText(this, s == null ? "操作失败" : s, Toast.LENGTH_LONG).show(); }
}
