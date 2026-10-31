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
    private static final String[] AUTHOR_FORMATS = {"Markdown（按学科与题目标记）", "JSON（含脚本生成的 cards.json）", T.s("JSONL（每行一个对象）"), T.s("CSV（含表头）"), T.s("TSV（含表头）"), T.s("TXT / TSV（无表头，Tab 分列）")};
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
    private boolean motionResumed;
    private SwipeCardView activeSwipe;
    private long workGeneration;
    private ProgressDialog workProgress;
    private final java.util.WeakHashMap<AlertDialog,Boolean> pageWorkDialogs=new java.util.WeakHashMap<>();
    private void clearPageWorkUi(){finishWorkUi();for(AlertDialog dialog:new java.util.ArrayList<>(pageWorkDialogs.keySet()))if(dialog!=null){try{dialog.dismiss();}catch(RuntimeException ignored){}}pageWorkDialogs.clear();}
    private int workOrientation;
    private void finishWorkUi(){
        workGeneration++;
        if(workProgress!=null){try{workProgress.dismiss();}catch(RuntimeException ignored){}workProgress=null;
            if(!isFinishing()&&!isDestroyed())setRequestedOrientation(workOrientation);}
    }
    @Override protected void onDestroy(){clearPageWorkUi();super.onDestroy();}
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
    protected void onPause() { motionResumed=false;if(activeSwipe!=null)activeSwipe.cancelVisual();super.onPause(); handler.removeCallbacks(queueTick); handler.removeCallbacks(advanceTick); }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);
        if("editor".equals(page)&&(intent.getBooleanExtra("from_bottom_nav",false)||intent.getBooleanExtra("open_learning",false))){ThemeStore.dialog(this).setTitle(T.s("离开卡片编辑？")).setMessage(T.s("尚未保存的修改会被放弃。")).setNegativeButton(T.s("继续编辑"),null).setPositiveButton(T.s("放弃并进入记忆"),(d,w)->{setIntent(intent);resetPageScroll();managementMode=false;home();}).show();return;}setIntent(intent);
        if(intent.getBooleanExtra("from_bottom_nav",false)||intent.getBooleanExtra("open_learning",false)){
            resetPageScroll();managementMode=false;handler.removeCallbacksAndMessages(null);home();
        }else if(intent.getBooleanExtra(CardManagementActivity.EXTRA_MANAGE,false)){managementMode=true;tools();}
    }
    protected void onResume() {
        motionResumed=true;
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
            ThemeStore.dialog(this).setTitle(T.s("放弃本次编辑？"))
                .setNegativeButton(T.s("继续编辑"), null).setPositiveButton(T.s("放弃"), (d, w) -> home()).show();
        } else if ("tools".equals(page)&&managementMode) finish();
        else if(managementMode)tools();else home();
    }
    private void base(String title, String subtitle) {
        clearPageWorkUi();
        handler.removeCallbacks(queueTick); handler.removeCallbacks(advanceTick); current = null;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); ui.page(scroll);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32)); scroll.addView(body);
        setContentView(FloatingNav.wrap(this, scroll, managementMode?4:0)); getWindow().setStatusBarColor(ui.bg()); getWindow().setNavigationBarColor(ui.bg());
        FloatingNav.fullscreen(this);
        if (!"home".equals(page)) {
        TextView back = ui.sub("tools".equals(page) ? T.s("‹  卡管理") : T.s("‹  记忆卡设置"));
        back.setTextColor(ui.accent()); back.setPadding(0, ui.dp(8), 0, ui.dp(18));
        back.setOnClickListener(v -> onBackPressed()); body.addView(back);
        }
        if ("home".equals(page) && managementMode) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView heading = ui.title(title);
            row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(row);
        } else if (!"home".equals(page)) body.addView(ui.title(title));
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
        ActionFeedback.bind(t,ui,primary?ui.accent():ui.amap?ui.surface():ui.surface2(),ui.amap?99:12,primary?ui.onAccent():ui.accent());
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
        revealed = false; submitting = false; incorrect = false; current = next; studyImage = null; typed = null;
        if (next == null) {
            base(T.s("本日记忆闪卡"), "");
            boolean duePool = MemoryFlashcards.POOL_DUE.equals(MemoryFlashcards.pool(m));
            LinearLayout empty = ui.card(); body.addView(empty, ui.gap(12));
            text(empty, p.total > 0 ? T.s("本日闪卡已完成") : duePool ? T.s("今天没有到期卡片") : T.s("暂无可抽取的闪卡"), 24);
            hint(empty, p.total > 0 ? T.s("复习记录已保存，明天按抽取设置生成新的闪卡。")
                    : duePool ? T.s("当前只抽到期卡片。可在守卫 → 卡管理 → 记忆卡调整抽取设置。")
                    : p.available == 0 ? T.s("在守卫 → 卡管理 → 记忆卡中添加学科、制作或导入卡片。")
                    : T.s("所选学科的卡片今天已经复习过。可以调整学科范围，或明天继续。"));
            return;
        }
        deck = next.optString("deck"); JSONObject d = m.deck(deck);
        baseStage();
        drawCard(m, d, next);
    }
    /** 卡片舞台：整页只有一张卡，页边距和卡内缩与打卡页一致（20dp 页边 + 18dp 卡缩）。 */
    private void baseStage() {
        handler.removeCallbacks(queueTick); handler.removeCallbacks(advanceTick); current = null;
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(24));
        setContentView(FloatingNav.wrap(this, body, managementMode ? 4 : 0));
        getWindow().setStatusBarColor(ui.bg()); getWindow().setNavigationBarColor(ui.bg());
        FloatingNav.fullscreen(this);
    }
    private void flashSettings() {
        MemoryData m = MemoryStore.flashSnapshot(this); JSONArray active = m.activeDecks();
        if (active.length() == 0) { toast(T.s("请先在卡管理的记忆卡中添加学科")); return; }
        JSONObject config = MemoryFlashcards.settings(m); JSONArray selected = MemoryFlashcards.subjects(m);
        LinearLayout box = dialogBox(); hint(box, T.s("抽取学科（可多选）"));
        CheckBox[] checks = new CheckBox[active.length()];
        for (int i = 0; i < active.length(); i++) {
            JSONObject d = active.optJSONObject(i); CheckBox check = new CheckBox(this);
            check.setText(d.optString("name") + " · " + m.count(d.optString("id")) + T.s(" 张"));
            check.setTextColor(ui.text()); check.setChecked(MemoryData.contains(selected, d.optString("id")));
            box.addView(check); checks[i] = check;
        }
        EditText count = field(box, T.s("抽取数量（1–1000 张）"), "" + config.optInt("count", 20), false); count.setInputType(2);
        hint(box, T.s("抽取池")); Spinner pool = spinner(box, new String[]{T.s("全部卡片（含未到期）"), T.s("仅到期卡片（按记忆曲线）")},
                MemoryFlashcards.POOL_DUE.equals(MemoryFlashcards.pool(m)) ? 1 : 0);
        hint(box, T.s("抽取规则")); Spinner mode = spinner(box, new String[]{T.s("随机抽取"), T.s("顺序执行 · 按入库顺序跨日接续")},
                "sequence".equals(config.optString("mode")) ? 1 : 0);
        hint(box, T.s("“全部卡片”包含尚未到复习日期的卡片；“仅到期卡片”只抽今天到期的卡片，按到期时间从早到晚。每张卡在今日闪卡中只出现一次。\n保存后重新抽取今日尚未复习的卡片，已完成记录保留。"));
        flashRules(box, active);
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        AlertDialog dialog = ThemeStore.dialog(this).setTitle(T.s("今日闪卡设置")).setView(scroll)
                .setPositiveButton(T.s("保存并抽取"), null).setNegativeButton(T.s("取消"), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
            JSONArray ids = new JSONArray();
            for (int i = 0; i < checks.length; i++) if (checks[i].isChecked()) ids.put(active.optJSONObject(i).optString("id"));
            try {
                MemoryStore.configureFlashcards(this, ids, Integer.parseInt(count.getText().toString().trim()),
                        mode.getSelectedItemPosition() == 0 ? "random" : "sequence",
                        pool.getSelectedItemPosition() == 0 ? MemoryFlashcards.POOL_ALL : MemoryFlashcards.POOL_DUE);
                dialog.dismiss(); if(managementMode)tools();else home();
            } catch (RuntimeException e) { toast(e instanceof NumberFormatException ? T.s("请填写 1–1000 的整数") : e.getMessage()); }
        })); dialog.show();
    }
    /** Public explanation follows MemoryScheduler, MemoryFlashcards and MemoryData, not a fixed-day mnemonic. */
    private void flashRules(LinearLayout box, JSONArray decks) {
        TextView title=text(box,T.s("记忆规律与复习规则"),18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hint(box,T.s("按你的表现调整\n使用 FSRS-6，根据每次评分、距上次复习的时间、卡片稳定性和难度计算下一次复习，不固定为第 1、3、7 天。它是排程模型，不保证每个人都能达到同样的记忆效果。"));
        hint(box,T.s("今日怎样抽卡\n“全部卡片”可抽新卡和未到期卡；“仅到期卡片”仅抽已学习且今天结束前到期的卡，优先较早到期的卡，不包含从未学习的新卡。随机模式从全部池随机抽取；顺序模式跨天接续。仅到期池始终按到期时间排序。"));
        hint(box,T.s("上滑与下滑\n揭晓答案后，上滑按“记住了”评分，下滑按“忘了”评分。输入题答错时不能上滑，需加入重学。今日闪卡每张每天只复习一次；选“忘了”会记录到错题和重学计划，不会在今日闪卡里反复出现。"));
        hint(box,T.s("新卡的短期学习\n“忘了”：约 1 分钟后可重学；首次“记住了”：约 10 分钟后再确认，再次记住后转入按天复习。“费力想起”：第一步约 5 分 30 秒，后续学习步约 10 分钟；“很轻松”可直接转入按天复习。分钟间隔表示重学计划的到期时间，不会让今日闪卡当天重复抽到该卡。"));
        hint(box,T.s("已学卡的复习\n按天复习时选“忘了”，约 10 分钟后进入重学；重学中仍忘记，再等约 10 分钟；重学中“费力想起”约 15 分钟后再学，记住后恢复按天复习。按天间隔根据评分动态计算，并从到期日零点开放，不是一律等满 24 小时。"));
        hint(box,T.s("目标记忆率与间隔\n默认目标记忆率为 90%，可在各学科学习设置中调整为 70–97%。调高目标通常会缩短复习间隔、增加复习量；目标记忆率是模型参数，不是测得的个人记忆率。按天间隔至少 1 天，并受该学科的最大间隔限制。当前未使用个人历史自动训练参数。"));
        for(int i=0;i<decks.length();i++){
            JSONObject deck=decks.optJSONObject(i);if(deck==null)continue;
            hint(box,deck.optString("name")+T.s("：目标记忆率 ")+Math.round(MemoryData.retention(deck)*100)+T.s("% · 最大间隔 ")+MemoryData.maxInterval(deck)+T.s(" 天"));
        }
        hint(box,T.s("进度怎样计算\n今日闪卡进度表示已复习的卡片数，包含选了“忘了”的卡，不代表全部记牢。学习联动以对应学科的学习目标为准：遗忘不计为有效完成；达到复习阶段的有效评分才可能增加完成量，同一计划内不重复计数。"));
    }
    private void tools() {
        page = "tools"; flashMode = false; draft = null; MemoryData m;
        try { m = MemoryStore.snapshot(this); } catch (RuntimeException e) { errorPage(e); return; }
        base(T.s("记忆卡设置"), T.s("学科、卡片、错题、学习设置与备份。"));
        MemoryFlashcards.Progress flash = MemoryFlashcards.progress(m, System.currentTimeMillis());
        JSONObject config = MemoryFlashcards.settings(m);
        button(body, T.s("抽取设置  ·  ") + ("sequence".equals(config.optString("mode")) ? T.s("顺序") : T.s("随机"))
                + "  ·  " + config.optInt("count", 20) + T.s(" 张"), false, v -> flashSettings());
        hint(body, T.s("本日已复习 ") + flash.done + "/" + flash.total + T.s(" · 今日累计 ") + flash.reviewedToday + T.s(" 张。")
                + T.s("\n数量不足时按剩余可用卡片抽取。")
                + T.s("\n先回忆再揭晓。每张闪卡复习后进入下一张；选择“忘了”会进入错题与重学计划。"));
        button(body, T.s("＋ 添加学科"), false, v -> addDeck());
        button(body, T.s("制作 / 导入卡片"), true, v -> authorDialog());
        button(body, T.s("管理学科"), false, v -> manageSubjects());
        if(!lastDeleted.isEmpty() && m.deleted(lastDeleted))button(body,T.s("已移入回收站 · 撤回"),false,v->restoreSubject(lastDeleted));
        JSONArray active=m.activeDecks();
        if(active.length()==0)hint(body,T.s("暂无学科，点击上方添加，或从回收站恢复。"));
        for (int i = 0; i < active.length(); i++) {
            final JSONObject d = active.optJSONObject(i); final String id = d.optString("id");
            MemoryData.Progress p = m.progress(id, System.currentTimeMillis());
            LinearLayout box = ui.card(); body.addView(box, ui.gap(12));
            TextView title = text(box, d.optString("name"), 21); title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextIsSelectable(false);title.setOnLongClickListener(v->{deleteSubject(id);return true;});
            hint(box, m.count(id) + T.s(" 张卡片 · 今日有效复习 ") + p.done + " / " + p.target
                    + T.s("\n今日队列：复习 ") + p.reviewCount + T.s(" · 新学 ") + p.newCount
                    + T.s("\n学习联动：") + (LearningLink.memoryEnabled(this, id) ? T.s("已启用") : T.s("未启用"))
                    + T.s("\n记忆率 ") + Math.round(MemoryData.retention(d) * 100) + T.s("% · 上限 ") + MemoryData.maxInterval(d)
                    + T.s(" 天 · 方案 ") + MemoryPlan.label(MemoryPlan.match(d.optInt("newLimit"), d.optInt("reviewLimit"),
                        d.optInt("target"), MemoryData.retention(d), MemoryData.maxInterval(d))));
            button(box, p.ready > 0 ? T.s("开始学习 · ") + p.ready + T.s(" 张可学") : p.nextDue > 0 ? T.s("查看重学倒计时") : T.s("今日学习"), true, v -> {
                if(managementMode)startActivity(new Intent(this,MemoryActivity.class).putExtra("deck",id));else{deck=id;review();}
            });
            LinearLayout row = new LinearLayout(this); box.addView(row);
            small(row, T.s("卡片"), v -> { deck = id; cards(false); });
            small(row, T.s("错题"), v -> { deck = id; cards(true); });
            small(row, T.s("设置"), v -> { deck = id; settings(d); });
        }
        button(body,T.s("学科回收站 · ")+m.deletedDecks().length(),false,v->subjectTrash());
        button(body, T.s("导出备份（含图片与复习记录）"), false, v -> pickExport());
        button(body, T.s("恢复备份"), false, v -> confirmRestore());
        button(body, T.s("开源组件与许可证"), false, v -> license());
        hint(body, T.s("每天每张卡只计一次。新卡和遗忘卡须完成学习步骤后才计数。\n今日目标最多为今日队列数量。开始答题后，当日计划固定，设置次日生效。"));
    }
    private void manageSubjects() {
        try {
            JSONArray active=MemoryStore.snapshot(this).activeDecks();
            if(active.length()==0){subjectTrash();return;}
            String[] names=new String[active.length()];
            for(int i=0;i<names.length;i++)names[i]=T.s("删除「")+active.optJSONObject(i).optString("name")+"」";
            ThemeStore.dialog(this).setTitle(T.s("管理学科")).setItems(names,(d,which)->deleteSubject(active.optJSONObject(which).optString("id")))
                .setNeutralButton(T.s("回收站"),(d,w)->subjectTrash()).setNegativeButton(T.s("关闭"),null).show();
        }catch(RuntimeException e){toast(e.getMessage());}
    }
    private void deleteSubject(String id) {
        try {
            MemoryData data=MemoryStore.snapshot(this);JSONObject subject=data.deck(id);
            if(subject==null || data.deleted(id))return;
            SubjectDeletion.confirm(this,ui,subject.optString("name"),data.count(id)+T.s(" 张卡片"),()->{
                try{MemoryStore.deleteDeck(this,id);lastDeleted=id;syncGuard();tools();}
                catch(RuntimeException e){toast(e.getMessage());}
            });
        }catch(RuntimeException e){toast(e.getMessage());}
    }
    private void restoreSubject(String id) {
        try{MemoryStore.restoreDeck(this,id);lastDeleted="";syncGuard();tools();toast(T.s("学科与卡片已恢复"));}
        catch(RuntimeException e){toast(e.getMessage());}
    }
    private void subjectTrash() {
        try {
            MemoryData data=MemoryStore.snapshot(this);JSONArray trash=data.deletedDecks();
            if(trash.length()==0){toast(T.s("回收站为空"));return;}
            String[] names=new String[trash.length()];
            for(int i=0;i<names.length;i++){JSONObject d=trash.optJSONObject(i);names[i]=T.s("恢复「")+d.optString("name")+"」 · "+data.count(d.optString("id"))+T.s(" 张卡片");}
            ThemeStore.dialog(this).setTitle(T.s("学科回收站")).setItems(names,(d,which)->restoreSubject(trash.optJSONObject(which).optString("id"))).setNegativeButton(T.s("关闭"),null).show();
        }catch(RuntimeException e){toast(e.getMessage());}
    }
    private void small(LinearLayout row, String s, View.OnClickListener onClick) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(ui.accent()); t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12)); t.setTextSize(14);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1)); t.setOnClickListener(onClick);
    }
    private void addDeck() {
        LinearLayout box = dialogBox(); EditText name = field(box, T.s("学科名称"), "", false);
        name.setHint(T.s("例如：电路、药理学、计算机网络"));
        AlertDialog dlg = ThemeStore.dialog(this).setTitle(T.s("添加学科")).setView(box)
                .setPositiveButton(T.s("添加"), null).setNegativeButton(T.s("取消"), null).create();
        pageWorkDialogs.put(dlg,true);
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
        EditText name = field(b, T.s("名称"), d.optString("name"), false);
        EditText nl = field(b, T.s("每日新卡上限（0–200）"), "" + d.optInt("newLimit"), false);
        EditText rl = field(b, T.s("每日复习上限（1–1000）"), "" + d.optInt("reviewLimit"), false);
        EditText target = field(b, T.s("每日有效复习目标（1–1000）"), "" + d.optInt("target"), false);
        EditText retention = field(b, T.s("目标记忆率（70–97%）"), "" + Math.round(MemoryData.retention(d) * 100), false);
        EditText maxInterval = field(b, T.s("单次复习上限间隔（1–36500 天）"), "" + MemoryData.maxInterval(d), false);
        for (EditText e : new EditText[]{nl, rl, target, retention, maxInterval}) e.setInputType(2);
        hint(top, T.s("复习方案"));
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
            planLabel.setText(T.s("当前方案：") + MemoryPlan.label(matched));
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
        linked.setText(T.s("参与学习联动"));
        linked.setTextColor(ui.text());
        linked.setChecked(LearningLink.memoryEnabled(this, d.optString("id")));
        b.addView(linked);
        hint(b, T.s("开启后，打卡页显示记忆卡；在记忆页完成目标。20:00 时目标未完成会参与学习联动。\n开始答题后修改设置，次日生效。到期复习积压超过上限时，暂停新增卡片。"));
        ScrollView scroll = new ScrollView(this); scroll.addView(b);
        AlertDialog dlg = ThemeStore.dialog(this).setTitle(T.s("学习计划")).setView(scroll)
                .setPositiveButton(T.s("保存"), null).setNegativeButton(T.s("取消"), null).create();
        pageWorkDialogs.put(dlg,true);
        dlg.setOnShowListener(ignored -> dlg.getButton(-1).setOnClickListener(v -> {
            try {
                MemoryStore.settings(this, d.optString("id"), name.getText().toString(),
                        Integer.parseInt(nl.getText().toString()), Integer.parseInt(rl.getText().toString()),
                        Integer.parseInt(target.getText().toString()), linked.isChecked(),
                        Integer.parseInt(retention.getText().toString()) / 100.0,
                        Integer.parseInt(maxInterval.getText().toString()));
                LearningLink.setMemoryEnabled(this, d.optString("id"), linked.isChecked());
                dlg.dismiss(); syncGuard(); tools();
            } catch (RuntimeException e) { toast(e instanceof NumberFormatException ? T.s("请填写整数") : e.getMessage()); }
        })); dlg.show();
    }
    private void cards(boolean wrong) {
        page = "cards"; MemoryData m = MemoryStore.snapshot(this); JSONObject d = m.deck(deck);
        if (d == null) { home(); return; }
        base(d.optString("name") + (wrong ? T.s(" · 错题") : T.s(" · 卡片")), wrong ? T.s("收集曾经选择“忘了”的卡片。到期后自动进入学习队列。") : T.s("问答、输入与图片遮挡；点卡片可以编辑。"));
        button(body, T.s("新建卡片"), true, v -> { draft = MemoryData.newCard(deck, "english".equals(d.optString("subject")) ? "input" : "qa", "", "", "", ""); editor(); });
        button(body, T.s("批量导入文本 / TSV"), false, v -> importDialog());
        EditText search = field(body, T.s("搜索题目或答案"), "", false);
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
                hint(card, T.s("复习 ") + c.optInt("reps") + T.s(" 次 · 遗忘 ") + c.optInt("lapses") + T.s(" 次 · 点此编辑"));
                card.setOnClickListener(v -> { draft = MemoryData.copy(c); editor(); });
            }
            hint(list, count == 0 ? T.s("还没有卡片。可手动添加，或导入每行一张的问答文本。") : T.s("共 ") + count + T.s(" 张") + (count > 100 ? T.s("，显示前 100 张，请用搜索缩小范围") : ""));
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { render.run(); }
            public void afterTextChanged(Editable s) { }
        }); render.run();
    }
    private void editor() {
        page = "editor"; base(T.s("编辑卡片"), T.s("图片题请先选图，再拖动框选标注。"));
        int selection = 0; for (int i = 0; i < TYPE_KEYS.length; i++) if (TYPE_KEYS[i].equals(draft.optString("type"))) selection = i;
        type = spinner(body, TYPES, selection);
        front = field(body, T.s("题目"), draft.optString("front"), true);
        answer = field(body, T.s("答案（输入题可用 | 分隔可接受答案）"), draft.optString("answer"), true);
        source = field(body, T.s("来源（教材 / 页码 / 链接）"), draft.optString("source"), false);
        note = field(body, T.s("备注与解释"), draft.optString("note"), true);
        editorImage = null;
        if (!draft.optString("image").isEmpty()) {
            hint(body, T.s("拖动添加矩形遮挡。所有遮挡区域作为同一张卡复习。"));
            editorImage = new MemoryImageView(this, draft.optString("image"), draft.optJSONArray("masks"), true);
            body.addView(editorImage, new LinearLayout.LayoutParams(-1, ui.dp(320)));
            button(body, T.s("撤销最后一个遮挡"), false, v -> editorImage.undo());
        }
        button(body, T.s("选择 / 更换图片"), false, v -> {
            collectDraft(); Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT); it.setType("image/*"); it.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(it, IMAGE);
        });
        button(body, T.s("保存卡片"), true, v -> {
            try { collectDraft(); MemoryStore.saveCard(this, draft); toast(T.s("已保存")); cards(false); }
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
        base(d.optString("name"), T.s("今日有效复习 ") + p.done + " / " + p.target + T.s(" · 队列 ") + p.total + T.s(" 张"));
        revealed = false; submitting = false; incorrect = false; current = next; studyImage = null; typed = null;
        if (next == null) {
            if (p.nextDue > 0) {
                long seconds = Math.max(1, (p.nextDue - System.currentTimeMillis() + 999) / 1000);
                text(body, T.s("重学卡片将在 ") + seconds / 60 + T.s(" 分 ") + seconds % 60 + T.s(" 秒后出现"), 21);
                hint(body, T.s("先休息一下。到期后自动显示下一张，重复翻看不增加完成量。"));
                handler.postDelayed(queueTick, 1000);
            } else {
                text(body, p.total == 0 ? T.s("今天没有待学卡片") : T.s("今日队列已完成"), 23);
                hint(body, p.total == 0 ? T.s("先添加卡片；开始答题后新增的卡片将进入明天的计划。") : T.s("复习进度已保存。放行仍遵守原时间表和其他学习目标。"));
                hint(body,T.s("卡片设置位于守卫 → 卡管理 → 记忆卡。"));
            }
            button(body, T.s("返回记忆首页"), true, v -> home()); return;
        }
        drawCard(m, d, next);
    }
    private void drawCard(MemoryData m, JSONObject d, JSONObject next) {
        final boolean stage = flashMode;   // 闪卡：整页一张卡，和打卡页同尺寸；学科复习保持可滚动版式
        final String id = next.optString("id"); final boolean isInput = "input".equals(next.optString("type"));
        final StudyCardSurface card = new StudyCardSurface(this);
        final SwipeCardView swipe = new SwipeCardView(this);activeSwipe=swipe;
        swipe.setClipChildren(false); swipe.setClipToPadding(false); body.setClipChildren(false);
        swipe.addView(card, new FrameLayout.LayoutParams(-1, stage ? -1 : -2));
        if (stage) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
            int inset = ui.dp(18); slp.setMargins(inset, inset, inset, inset);
            body.addView(swipe, slp);
        } else body.addView(swipe, ui.gap(16));

        if (stage) {
            // 卡面顶行与打卡页同构：左边卡片类型，右边「第几张 / 共几张」。
            MemoryFlashcards.Progress progress = MemoryFlashcards.progress(m, System.currentTimeMillis());
            LinearLayout tagRow = new LinearLayout(this);
            tagRow.setOrientation(LinearLayout.HORIZONTAL); tagRow.setGravity(Gravity.CENTER_VERTICAL);
            tagRow.setPadding(0, 0, 0, ui.dp(6));
            tagRow.addView(ui.sub(T.s("记忆卡")), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tagRow.addView(ui.sub((progress.done + 1) + " / " + progress.total));
            card.addView(tagRow);
        }
        TextView question = text(card, MemoryData.question(next), 25);
        // Selectable text would steal long-press/drag away from the swipe gesture (see card list precedent).
        question.setTextIsSelectable(false);
        if (!next.optString("image").isEmpty()) {
            studyImage = new MemoryImageView(this, next.optString("image"), next.optJSONArray("masks"), false);
            card.addView(studyImage, new LinearLayout.LayoutParams(-1, ui.dp(300)));
        }
        if (isInput) typed = field(card, T.s("先输入你的答案"), "", false);
        final LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL); result.setVisibility(View.GONE); card.addView(result);

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout actionHost;
        if (stage) {
            // 卡面撑满：内容顶对齐，评分贴底，不额外占版面
            card.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));
            card.addView(actions);
            actionHost = actions;
        } else actionHost = body;

        CardPalette.apply(card, CardPalette.MEMORY, false);
        // 手势：下滑＝揭晓；揭晓后上滑＝记住了、再下滑＝忘了（卡片消散）。
        swipe.setDirections(false, true);
        swipe.setSwipeEnabled(true);
        swipe.setListener(direction -> {
            if (!revealed) {
                if (direction == MemorySwipe.DOWN) {
                    swipe.settle();   // 拖动位移先归位，再翻面
                    revealCard(next, d, result, actionHost, swipe, true, false);
                } else swipe.reset();
                return;
            }
            int rating = MemorySwipe.rating(direction);
            if (rating == 0) return;
            if (!grade(id, rating)) { swipe.reset(); return; }
            swipe.setSwipeEnabled(false);
            Runnable finished=()->{if(motionResumed&&!isFinishing()&&!isDestroyed()&&swipe.isAttachedToWindow()&&submitting)advance();};
            if (direction == MemorySwipe.DOWN) swipe.dissolve(finished); else swipe.flyOut(direction,finished);
        });
        showRevealActions(actionHost, next, d, result, swipe);
        if (!stage) hint(body, T.s("先回忆再揭晓。新卡需要完成学习步骤；选择“忘了”不计完成，稍后会再次出现。"));
    }

    /** 揭晓前卡面底部只有揭晓动作；下滑与按钮走同一条揭晓路径。 */
    private void showRevealActions(LinearLayout host, JSONObject next, JSONObject d, LinearLayout result, SwipeCardView swipe) {
        host.removeAllViews();
        boolean isInput = "input".equals(next.optString("type"));
        button(host, isInput ? T.s("核对答案") : T.s("显示答案"), true, v -> revealCard(next, d, result, host, swipe, false, false));
        if (isInput) button(host, T.s("想不起来 · 查看答案"), false, v -> revealCard(next, d, result, host, swipe, true, true));
    }

    /** 揭晓答案：按钮和下滑共用；fromSwipe 时空白输入按「想不起来」处理。 */
    private void revealCard(JSONObject next, JSONObject d, LinearLayout result, LinearLayout host,
                            SwipeCardView swipe, boolean fromSwipe, boolean forceIncorrect) {
        if (revealed) return;
        if ("input".equals(next.optString("type"))) {
            String value = typed == null ? "" : typed.getText().toString().trim();
            if (forceIncorrect) incorrect = true;
            else if (value.isEmpty()) {
                if (!fromSwipe) { toast(T.s("请先尝试输入答案")); return; }
                incorrect = true;
            } else {
                incorrect = !MemoryData.matches(value, next.optString("answer"), "english".equals(d.optString("subject")));
                hint(result, incorrect ? T.s("答案不匹配，本次计为遗忘，稍后重学") : T.s("答案匹配"));
            }
            if (typed != null) typed.setEnabled(false);
        }
        revealed = true;
        if (studyImage != null) studyImage.reveal();
        TextView answer = text(result, MemoryData.answer(next), 20);
        answer.setTextIsSelectable(false);
        if (!next.optString("source").isEmpty()) hint(result, T.s("来源：") + next.optString("source"));
        if (!next.optString("note").isEmpty()) hint(result, T.s("备注：") + next.optString("note"));
        CardPalette.textColors(result, CardPalette.MEMORY);
        result.setVisibility(View.VISIBLE);
        swipe.setDirections(!incorrect, true);
        showRateActions(host, next.optString("id"), incorrect);
    }

    /** 评分：手势之外保留四档按钮，卡片撑满页面时它们贴在卡面底部。 */
    private void showRateActions(LinearLayout host, String id, boolean onlyForget) {
        host.removeAllViews();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        host.addView(row, ui.gap(6));
        String[] labels = onlyForget ? new String[]{T.s("继续 · 加入重学")} : new String[]{T.s("忘了"), T.s("费力"), T.s("记住"), T.s("轻松")};
        int[] ratings = onlyForget ? new int[]{1} : new int[]{1, 2, 3, 4};
        for (int i = 0; i < labels.length; i++) {
            final int rating = ratings[i];
            TextView b = new TextView(this);
            b.setText(labels[i]); b.setTextSize(13); b.setGravity(Gravity.CENTER);
            SwissTypography.role(b, SwissTypography.Role.BUTTON);
            b.setTextColor(rating == 3 ? ui.onAccent() : ui.accent());
            b.setPadding(ui.dp(4), ui.dp(11), ui.dp(4), ui.dp(11));
            b.setBackground(ui.round(rating == 3 ? ui.accent() : ui.surface(), 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = ui.dp(6);
            row.addView(b, lp);
            b.setOnClickListener(v -> { if (grade(id, rating)) advance(); });
        }
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
            else { boolean credited = MemoryStore.rate(this, id, rating); if (credited) toast(T.s("有效复习 +1，进度已保存")); syncGuard(); }
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
    private void authorDialog() {
        LinearLayout b = dialogBox();
        hint(b, T.s("选择文件或粘贴已准备好的题目与答案，校验并预览后入库。按内容中的学科自动归类，缺少的学科会在确认后创建。"));
        hint(b, T.s("内容格式")); Spinner format = spinner(b, AUTHOR_FORMATS, authorFormat);
        EditText subject = field(b, T.s("默认学科（内容已含学科时可留空）"), authorSubject, false);
        subject.setHint(T.s("例如：英语单词、医学、工科概念"));
        hint(b, T.s("默认题型（卡片内指定的题型优先）"));
        Spinner kind = spinner(b, new String[]{T.s("自动：词表为输入卡，其余为问答"), T.s("问答"), T.s("输入答案"), T.s("填空 {{答案}}")}, authorType);
        EditText content = field(b, T.s("粘贴内容"), "", true); content.setMinLines(5);
        button(b, T.s("填入三学科示例"), false, v -> { format.setSelection(0); kind.setSelection(0); content.setText(MemoryAuthoring.TEMPLATE); });
        hint(b, T.s("JSON / CSV / TSV 字段：subject、type、front、answer、source、note，也支持中文字段名。词表可用 word、meaning。\n无表头格式：题目〔Tab〕答案〔Tab〕来源〔Tab〕备注，需填写默认学科。文件最大 16MB，支持 UTF-8 / GB18030。"));
        ScrollView scroll = new ScrollView(this); scroll.addView(b);
        AlertDialog dlg = ThemeStore.dialog(this).setTitle(T.s("制作 / 导入卡片")).setView(scroll)
            .setPositiveButton(T.s("校验并预览"), null).setNeutralButton(T.s("选择文件"), null).setNegativeButton(T.s("取消"), null).create();
        pageWorkDialogs.put(dlg,true);
        dlg.setOnShowListener(ignored -> {
            View.OnClickListener remember = v -> {
                authorFormat = format.getSelectedItemPosition(); authorType = kind.getSelectedItemPosition(); authorSubject = subject.getText().toString().trim();
            };
            dlg.getButton(-1).setOnClickListener(v -> {
                remember.onClick(v); String input = content.getText().toString();
                String fmt = AUTHOR_KEYS[authorFormat], sub = authorSubject, typ = authorTypeKey();
                dlg.getButton(-1).setEnabled(false); dlg.getButton(-3).setEnabled(false);
                work(T.s("校验卡片"), resultUi -> {
                    try {
                        MemoryAuthoring.Batch batch = MemoryAuthoring.parse(input, fmt, sub, typ);
                        MemoryAuthoring.Result preview = MemoryStore.previewCards(this, batch);
                        resultUi.accept(() -> { if (!isFinishing() && !isDestroyed()) { dlg.dismiss(); authorPreview(batch, preview); } });
                    } finally { resultUi.accept(() -> { if (dlg.isShowing()) { dlg.getButton(-1).setEnabled(true); dlg.getButton(-3).setEnabled(true); } }); }
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
        hint(b, T.s("确认后仅新增卡片，已有卡片和复习记录保留。复习独立于打卡。"));
        ScrollView scroll = new ScrollView(this); scroll.addView(b);
        AlertDialog dlg = ThemeStore.dialog(this).setTitle(T.s("制卡预览")).setView(scroll)
            .setPositiveButton(T.s("确认入库"), null).setNegativeButton(T.s("取消"), null).create();
        pageWorkDialogs.put(dlg,true);
        dlg.setOnShowListener(ignored -> {
            dlg.getButton(-1).setEnabled(preview.added > 0);
            dlg.getButton(-1).setOnClickListener(v -> {
                dlg.getButton(-1).setEnabled(false);
                work(T.s("保存卡片"), resultUi -> {
                    try {
                        MemoryAuthoring.Result result = MemoryStore.importCards(this, batch);
                        resultUi.accept(() -> { if (!isFinishing() && !isDestroyed()) { dlg.dismiss(); tools(); toast(T.s("已新增 ") + result.added + T.s(" 张，跳过重复 ") + result.skipped + T.s(" 张")); } });
                    } catch (Exception e) { resultUi.accept(() -> { if (dlg.isShowing()) dlg.getButton(-1).setEnabled(true); }); throw e; }
                });
            });
        }); dlg.show();
    }
    private void importDialog() {
        LinearLayout b = dialogBox(); Spinner s = spinner(b, IMPORT_TYPES, 0);
        hint(b, T.s("每行一张，用 Tab 分隔：题目、答案、来源（可选）、备注（可选）。\n可从表格复制两列。完全相同的卡片会跳过。\n示例：memory〔Tab〕记忆"));
        EditText content = field(b, T.s("粘贴内容"), "", true); content.setMinLines(4);
        ScrollView scroll = new ScrollView(this); scroll.addView(b);
        AlertDialog dlg = ThemeStore.dialog(this).setTitle(T.s("批量导入")).setView(scroll)
            .setPositiveButton(T.s("导入粘贴内容"), null).setNeutralButton(T.s("选择 TXT / TSV 文件"), null).setNegativeButton(T.s("取消"), null).create();
        pageWorkDialogs.put(dlg,true);
        dlg.setOnShowListener(ignored -> {
            dlg.getButton(-1).setOnClickListener(v -> {
                importType = IMPORT_KEYS[s.getSelectedItemPosition()]; String input = content.getText().toString();
                final String targetDeck=deck,targetImportType=importType;
                work(T.s("导入卡片"), resultUi -> {
                    int count = MemoryStore.importText(this,targetDeck,input,targetImportType);
                    resultUi.accept(() -> { dlg.dismiss(); toast(T.s("已导入 ") + count + T.s(" 张")); cards(false); });
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
        ThemeStore.dialog(this).setTitle(T.s("恢复记忆备份"))
            .setMessage(T.s("会替换当前记忆卡库、复习记录与记忆目标设置。建议先导出当前备份。原有普通学习目标与屏蔽时间表不受影响。"))
            .setNegativeButton(T.s("取消"), null).setPositiveButton(T.s("选择备份"), (d, w) -> {
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT); it.setType("*/*"); it.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(it, RESTORE);
            }).show();
    }
    protected void onActivityResult(int request, int result, Intent it) {
        super.onActivityResult(request, result, it);
        if (result != RESULT_OK || it == null || it.getData() == null) { if (request == AUTHOR) authorDialog(); return; } Uri uri = it.getData();
        final String targetDeck=deck,targetImportType=importType,targetSubject=authorSubject;
        final String targetFormat=AUTHOR_KEYS[authorFormat],targetType=authorTypeKey();
        work(T.s("处理文件"), resultUi -> {
            if (request == AUTHOR) {
                String input;
                try (InputStream in = getContentResolver().openInputStream(uri)) { input = MemoryAuthoring.decode(MemoryStore.read(in, MemoryAuthoring.MAX_BYTES)); }
                MemoryAuthoring.Batch batch = MemoryAuthoring.parse(input,targetFormat,targetSubject,targetType);
                MemoryAuthoring.Result preview = MemoryStore.previewCards(this, batch);
                resultUi.accept(() -> { if (!isFinishing() && !isDestroyed()) authorPreview(batch, preview); });
            } else if (request == IMAGE) {
                String name = MemoryStore.importImage(this, uri);
                resultUi.accept(() -> {MemoryData.put(draft,"image",name);MemoryData.put(draft,"masks",new JSONArray());editor();});
            } else if (request == TEXT) {
                int count;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    String input = new String(MemoryStore.read(in, 8 * 1024 * 1024), "UTF-8").replace("\uFEFF", "");
                    count = MemoryStore.importText(this,targetDeck,input,targetImportType);
                }
                final int n = count; resultUi.accept(() -> { toast(T.s("已导入 ") + n + T.s(" 张")); cards(false); });
            } else if (request == EXPORT) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) { MemoryStore.exportBackup(this, out); }
                resultUi.accept(() -> toast(T.s("备份已导出")));
            } else if (request == RESTORE) {
                try (InputStream in = getContentResolver().openInputStream(uri)) { MemoryStore.restoreBackup(this, in); }
                syncGuard(); resultUi.accept(() -> { toast(T.s("备份已恢复")); home(); });
            }
        });
    }
    private interface Job { void run(java.util.function.Consumer<Runnable> resultUi) throws Exception; }
    private void work(String title, Job job) {
        finishWorkUi();
        final long token=workGeneration;final View owner=body;
        workOrientation=getRequestedOrientation();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        workProgress=ProgressDialog.show(this,title,T.s("请稍候…"),true,false);
        java.util.function.Consumer<Runnable> post=action->runOnUiThread(()->{
            if(token==workGeneration&&owner==body&&!isFinishing()&&!isDestroyed())action.run();
        });
        new Thread(()->{
            try{job.run(post);}
            catch(Exception error){post.accept(()->toast(error.getMessage()==null?T.s("操作失败"):error.getMessage()));}
            finally{runOnUiThread(()->{if(token==workGeneration)finishWorkUi();});}
        },"afs-memory-io").start();
    }
    private void syncGuard() {
        final android.content.Context c = getApplicationContext();
        new Thread(() -> { try { Guard.apply(c); } catch (RuntimeException ignored) { } }, "afs-memory-gate").start();
    }
    private void license() {
        try (InputStream in = getResources().openRawResource(getResources().getIdentifier("fsrs_license", "raw", getPackageName()))) {
            String license = new String(MemoryStore.read(in, 20000), "UTF-8");
            ThemeStore.dialog(this).setTitle("FSRS-6 · Open Spaced Repetition")
                .setMessage(T.s("调度核心适配自 java-fsrs\n版本 c561ee6c56239b621a0a45b16a6c2e688441eae5\nhttps://github.com/open-spaced-repetition/java-fsrs\n\n") + license)
                .setPositiveButton(T.s("关闭"), null).show();
        } catch (Exception e) { toast(e.getMessage()); }
    }
    private void errorPage(RuntimeException e) {
        page = "home"; base(T.s("记忆库暂时不可用"), e.getMessage());
        button(body, T.s("恢复备份"), true, v -> confirmRestore());
        button(body, T.s("返回首页"), false, v -> finish());
    }
    private void toast(String s) { Toast.makeText(this, s == null ? T.s("操作失败") : s, Toast.LENGTH_LONG).show(); }
}
