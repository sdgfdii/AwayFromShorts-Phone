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
    private static final int IMAGE = 51, TEXT = 52, EXPORT = 53, RESTORE = 54;
    private static final String[] TYPES = {"问答", "输入答案", "填空背诵", "图片遮挡"};
    private static final String[] TYPE_KEYS = {"qa", "input", "cloze", "image"};
    private Ui ui;
    private LinearLayout body;
    private String deck = "", page = "home", importType = "qa", lastDeleted = "";
    private JSONObject draft, current;
    private EditText front, answer, source, note, typed;
    private Spinner type;
    private MemoryImageView editorImage, studyImage;
    private boolean revealed, submitting, incorrect;
    private boolean flashMode;
    private String flashDate = "";
    private final Handler handler = new Handler();
    private final Runnable queueTick = new Runnable() {
        public void run() {
            if ("review".equals(page) && current == null) review();
        }
    };
    protected void onCreate(Bundle saved) {
        if (Ui.isDark(this)) setTheme(getResources().getIdentifier("NavThemeDark", "style", getPackageName()));
        super.onCreate(saved); ui = new Ui(this);
        deck = saved == null ? getIntent().getStringExtra("deck") : saved.getString("deck", "");
        if (deck == null) deck = "";
        if (saved == null && !deck.isEmpty()) page = "review";
        if (saved != null) {
            page = saved.getString("page", "home"); importType = saved.getString("importType", "qa");lastDeleted=saved.getString("lastDeleted","");
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
        if (draft != null) out.putString("draft", draft.toString()); super.onSaveInstanceState(out);
    }
    protected void onPause() { super.onPause(); handler.removeCallbacks(queueTick); }
    protected void onResume() {
        super.onResume();
        if (ui != null && ui.dark != Ui.isDark(this)) { recreate(); return; }
        FloatingNav.fullscreen(this);
        if ("home".equals(page) && !flashDate.equals(MemoryData.date(System.currentTimeMillis()))) { home(); return; }
        if ("review".equals(page) && current == null) handler.postDelayed(queueTick, 1000);
    }
    public void onBackPressed() {
        if ("home".equals(page)) { super.onBackPressed(); return; }
        if ("editor".equals(page)) {
            ThemeStore.dialog(this).setTitle("放弃本次编辑？")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃", (d, w) -> home()).show();
        } else if ("tools".equals(page)) home();
        else tools();
    }
    private void base(String title, String subtitle) {
        handler.removeCallbacks(queueTick); current = null;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); ui.page(scroll);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32)); scroll.addView(body);
        setContentView(FloatingNav.wrap(this, scroll, 0)); getWindow().setStatusBarColor(ui.bg()); getWindow().setNavigationBarColor(ui.bg());
        FloatingNav.fullscreen(this);
        if (!"home".equals(page)) {
        TextView back = ui.sub("tools".equals(page) ? "‹  本日记忆闪卡" : "‹  记忆工具");
        back.setTextColor(ui.accent()); back.setPadding(0, ui.dp(8), 0, ui.dp(18));
        back.setOnClickListener(v -> onBackPressed()); body.addView(back);
        }
        if ("home".equals(page)) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView heading = ui.title(title);
            row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            TextView manage = ui.sub("记忆工具"); manage.setTextColor(ui.accent());
            SwissTypography.role(manage,SwissTypography.Role.BUTTON);
            manage.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            manage.setBackground(ui.round(ui.amap?ui.surface():ui.surface2(),ui.amap?99:12)); manage.setOnClickListener(v -> tools());
            row.addView(manage); body.addView(row);
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
            LinearLayout empty = ui.card(); body.addView(empty, ui.gap(12));
            text(empty, p.total > 0 ? "本日闪卡已完成" : "暂无可抽取的闪卡", 24);
            hint(empty, p.total > 0 ? "复习记录已保存，明天按抽取设置生成新的闪卡。"
                    : p.available == 0 ? "从右上角“记忆工具”添加学科、录入或导入卡片，再设置抽取范围。"
                    : "所选学科的卡片今天已经复习过。可以调整学科范围，或明天继续。");
            return;
        }
        deck = next.optString("deck"); JSONObject d = m.deck(deck);
        hint(body, (p.done + 1) + "/" + p.total);
        drawCard(m, d, next);
    }
    private void flashSettings() {
        MemoryData m = MemoryStore.flashSnapshot(this); JSONArray active = m.activeDecks();
        if (active.length() == 0) { toast("请先在记忆工具中添加学科"); return; }
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
        hint(box, "抽取规则"); Spinner mode = spinner(box, new String[]{"随机抽取", "顺序执行 · 按入库顺序跨日接续"},
                "sequence".equals(config.optString("mode")) ? 1 : 0);
        hint(box, "从所选学科的全部卡片中抽取，每张卡在今日闪卡中只出现一次。\n保存后重新抽取今日尚未复习的卡片，已完成记录保留。");
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        AlertDialog dialog = ThemeStore.dialog(this).setTitle("今日闪卡设置").setView(scroll)
                .setPositiveButton("保存并抽取", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
            JSONArray ids = new JSONArray();
            for (int i = 0; i < checks.length; i++) if (checks[i].isChecked()) ids.put(active.optJSONObject(i).optString("id"));
            try {
                MemoryStore.configureFlashcards(this, ids, Integer.parseInt(count.getText().toString().trim()), mode.getSelectedItemPosition() == 0 ? "random" : "sequence");
                dialog.dismiss(); home();
            } catch (RuntimeException e) { toast(e instanceof NumberFormatException ? "请填写 1–1000 的整数" : e.getMessage()); }
        })); dialog.show();
    }
    private void tools() {
        page = "tools"; flashMode = false; draft = null; MemoryData m;
        try { m = MemoryStore.snapshot(this); } catch (RuntimeException e) { errorPage(e); return; }
        base("记忆工具", "学科、卡片、错题、学习设置与备份。");
        MemoryFlashcards.Progress flash = MemoryFlashcards.progress(m, System.currentTimeMillis());
        JSONObject config = MemoryFlashcards.settings(m);
        button(body, "抽取设置  ·  " + ("sequence".equals(config.optString("mode")) ? "顺序" : "随机")
                + "  ·  " + config.optInt("count", 20) + " 张", false, v -> flashSettings());
        hint(body, "本日已复习 " + flash.done + "/" + flash.total + " · 今日累计 " + flash.reviewedToday + " 张。"
                + "\n数量不足时按剩余可用卡片抽取。"
                + "\n先回忆再揭晓。每张闪卡复习后进入下一张；选择“忘了”会进入错题与重学计划。");
        button(body, "＋ 添加学科", false, v -> addDeck());
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
                    + (p.linked ? "\n学习联动：" + (p.target == 0 ? "今天无任务" : p.remaining == 0 ? "已完成" : "还有 " + p.remaining + " 张") : "\n学习联动：未启用"));
            button(box, p.ready > 0 ? "开始学习 · " + p.ready + " 张可学" : p.nextDue > 0 ? "查看重学倒计时" : "今日学习", true, v -> { deck = id; review(); });
            LinearLayout row = new LinearLayout(this); box.addView(row);
            small(row, "卡片", v -> { deck = id; cards(false); });
            small(row, "错题", v -> { deck = id; cards(true); });
            small(row, "设置", v -> { deck = id; settings(d); });
        }
        button(body,"学科回收站 · "+m.deletedDecks().length(),false,v->subjectTrash());
        button(body, "导出备份（含图片与复习记录）", false, v -> pickExport());
        button(body, "恢复备份", false, v -> confirmRestore());
        button(body, "开源组件与许可证", false, v -> license());
        hint(body, "每天每张卡只计一次。新卡和遗忘卡须完成学习步骤后才计数。\n今日目标最多为今日队列数量；没有任务的卡组不会额外冻结。开始答题后，当日计划固定，设置次日生效。");
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
    private void settings(JSONObject d) {
        LinearLayout b = dialogBox(); EditText name = field(b, "名称", d.optString("name"), false);
        EditText nl = field(b, "每日新卡上限（0–200）", "" + d.optInt("newLimit"), false);
        EditText rl = field(b, "每日复习上限（1–1000）", "" + d.optInt("reviewLimit"), false);
        EditText target = field(b, "每日有效复习目标（1–1000）", "" + d.optInt("target"), false);
        for (EditText e : new EditText[]{nl, rl, target}) e.setInputType(2);
        CheckBox linked = new CheckBox(this); linked.setText("未完成目标时延长娱乐应用冻结"); linked.setTextColor(ui.text()); linked.setChecked(d.optBoolean("linked")); b.addView(linked);
        hint(b, "20:00 起检查；完成后恢复原时间表。\n开始答题后修改设置，次日生效。到期复习积压超过上限时，暂停新增卡片。");
        ScrollView scroll = new ScrollView(this); scroll.addView(b);
        AlertDialog dlg = ThemeStore.dialog(this).setTitle("学习计划").setView(scroll)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dlg.setOnShowListener(ignored -> dlg.getButton(-1).setOnClickListener(v -> {
            try {
                MemoryStore.settings(this, d.optString("id"), name.getText().toString(), Integer.parseInt(nl.getText().toString()), Integer.parseInt(rl.getText().toString()), Integer.parseInt(target.getText().toString()), linked.isChecked());
                dlg.dismiss(); syncGuard(); tools();
            } catch (RuntimeException e) { toast(e instanceof NumberFormatException ? "请填写整数" : e.getMessage()); }
        })); dlg.show();
    }
    private void cards(boolean wrong) {
        page = "cards"; MemoryData m = MemoryStore.snapshot(this); JSONObject d = m.deck(deck);
        if (d == null) { home(); return; }
        base(d.optString("name") + (wrong ? " · 错题" : " · 卡片"), wrong ? "收集曾经选择“忘了”的卡片。到期后自动进入学习队列。" : "问答、输入、填空与图片遮挡；点卡片可以编辑。");
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
        page = "editor"; base("编辑卡片", "填空写法：力的单位是 {{牛顿}}。图片题请先选图，再拖动框选标注。");
        int selection = 0; for (int i = 0; i < TYPE_KEYS.length; i++) if (TYPE_KEYS[i].equals(draft.optString("type"))) selection = i;
        type = spinner(body, TYPES, selection);
        front = field(body, "题目 / 填空原文", draft.optString("front"), true);
        answer = field(body, "答案（填空题可留空；输入题可用 | 分隔可接受答案）", draft.optString("answer"), true);
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
                button(body, "管理卡片", false, v -> cards(false));
            }
            button(body, "返回记忆首页", true, v -> home()); return;
        }
        drawCard(m, d, next);
    }
    private void drawCard(MemoryData m, JSONObject d, JSONObject next) {
        final String id = next.optString("id"); final boolean isInput = "input".equals(next.optString("type"));
        LinearLayout card = ui.card(); body.addView(card, ui.gap(16)); text(card, MemoryData.question(next), 25);
        if (!next.optString("image").isEmpty()) {
            studyImage = new MemoryImageView(this, next.optString("image"), next.optJSONArray("masks"), false);
            card.addView(studyImage, new LinearLayout.LayoutParams(-1, ui.dp(300)));
        }
        if (isInput) typed = field(card, "先输入你的答案", "", false);
        LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); result.setVisibility(View.GONE); card.addView(result);
        TextView show = button(body, isInput ? "核对答案" : "显示答案", true, v -> {
            if (revealed) return;
            if (isInput && typed.getText().toString().trim().isEmpty()) { toast("请先尝试输入答案"); return; }
            revealed = true;
            if (studyImage != null) studyImage.reveal();
            if (isInput) {
                boolean ok = MemoryData.matches(typed.getText().toString(), next.optString("answer"), "english".equals(d.optString("subject")));
                incorrect = !ok; typed.setEnabled(false); hint(result, ok ? "答案匹配" : "答案不匹配，本次计为遗忘，稍后重学");
            }
            text(result, MemoryData.answer(next), 20);
            if (!next.optString("source").isEmpty()) hint(result, "来源：" + next.optString("source"));
            if (!next.optString("note").isEmpty()) hint(result, "备注：" + next.optString("note"));
            result.setVisibility(View.VISIBLE); v.setVisibility(View.GONE);
            if (incorrect) rateButton(body, id, flashMode ? "继续 · 记录遗忘" : "继续 · 稍后重学", 1);
            else {
                rateButton(body, id, flashMode ? "忘了 · 加入重学" : "忘了 · 重新学习", 1);
                rateButton(body, id, "费力想起", 2);
                rateButton(body, id, "记住了", 3);
                rateButton(body, id, "很轻松", 4);
            }
        });
        if (isInput) button(body, "想不起来 · 查看答案", false, v -> {
            if (revealed) return; incorrect = true; revealed = true;
            typed.setEnabled(false); show.setVisibility(View.GONE); v.setVisibility(View.GONE);
            text(result, MemoryData.answer(next), 20);
            if (!next.optString("source").isEmpty()) hint(result, "来源：" + next.optString("source"));
            if (!next.optString("note").isEmpty()) hint(result, "备注：" + next.optString("note"));
            result.setVisibility(View.VISIBLE); rateButton(body, id, flashMode ? "继续 · 记录遗忘" : "继续 · 稍后重学", 1);
        });
        if (!flashMode) hint(body, "先回忆再揭晓。新卡需要完成学习步骤；选择“忘了”不计完成，稍后会再次出现。");
    }
    private void rateButton(LinearLayout parent, String id, String label, int rating) {
        button(parent, label, rating == 3, v -> {
            if (!revealed || submitting) return; submitting = true;
            try {
                if (flashMode) { MemoryStore.rateFlashcard(this, id, rating, flashDate); syncGuard(); home(); }
                else { boolean credited = MemoryStore.rate(this, id, rating); if (credited) toast("有效复习 +1，进度已保存"); syncGuard(); review(); }
            } catch (RuntimeException e) { submitting = false; toast(e.getMessage()); if (flashMode) home(); }
        });
    }
    private void importDialog() {
        LinearLayout b = dialogBox(); Spinner s = spinner(b, new String[]{"问答", "输入答案", "填空背诵"}, 0);
        hint(b, "每行一张，用 Tab 分隔：题目、答案、来源（可选）、备注（可选）。\n可从表格复制两列。完全相同的卡片会跳过。\n示例：memory〔Tab〕记忆");
        EditText content = field(b, "粘贴内容", "", true); content.setMinLines(4);
        ScrollView scroll = new ScrollView(this); scroll.addView(b);
        AlertDialog dlg = ThemeStore.dialog(this).setTitle("批量导入").setView(scroll)
            .setPositiveButton("导入粘贴内容", null).setNeutralButton("选择 TXT / TSV 文件", null).setNegativeButton("取消", null).create();
        dlg.setOnShowListener(ignored -> {
            dlg.getButton(-1).setOnClickListener(v -> {
                importType = TYPE_KEYS[s.getSelectedItemPosition()]; String input = content.getText().toString();
                work("导入卡片", () -> {
                    int count = MemoryStore.importText(this, deck, input, importType);
                    runOnUiThread(() -> { dlg.dismiss(); toast("已导入 " + count + " 张"); cards(false); });
                });
            });
            dlg.getButton(-3).setOnClickListener(v -> {
                importType = TYPE_KEYS[s.getSelectedItemPosition()]; dlg.dismiss();
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
        if (result != RESULT_OK || it == null || it.getData() == null) return; Uri uri = it.getData();
        work("处理文件", () -> {
            if (request == IMAGE) {
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
