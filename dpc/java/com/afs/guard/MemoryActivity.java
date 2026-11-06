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
import java.util.Calendar;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Native embedded memory module. All card content stays text/native Canvas, never executable HTML. */
public class MemoryActivity extends ThemedActivity {
    private static final int IMAGE = 51, TEXT = 52, EXPORT = 53, RESTORE = 54, AUTHOR = 55;
    /** 挑选弹窗每段最多渲染的候选行数；多了靠搜索，全画会把弹窗卡死。 */
    private static final int PICK_CAP = 200;
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
    private boolean revealed, submitting, graded;
    private boolean flashMode;
    private boolean motionResumed;
    private final java.util.ArrayList<Runnable> deferredUi=new java.util.ArrayList<>();
    private void imageActive(boolean active){
        if(editorImage!=null)editorImage.setPageActive(active);
        if(studyImage!=null)studyImage.setPageActive(active);
    }
    private StudyCardSurface activeStudyCard;
    private String restoredStudyId="", restoredStudyDate="", restoredTyped="";
    private boolean restoredRevealed,restoredGraded;
    private long restoredGradedAt,gradedAt;
    private long workGeneration;
    private volatile long browseGeneration;
    private long browseEntry;
    private long browseRevision=-1;
    private String browseDate="";
    private final java.util.concurrent.ThreadPoolExecutor browseWorker = new java.util.concurrent.ThreadPoolExecutor(
        1,1,20,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<Runnable>(1),
        r->{Thread t=new Thread(r,"afs-memory-browse");t.setDaemon(true);return t;});
    private int statsPeriod,statsSubject,cardOffset;
    private MemoryCardQuery.Filter cardFilter=MemoryCardQuery.Filter.ALL;
    private String cardSearch="";
    private void cancelBrowse(){browseEntry++;browseGeneration++;browseWorker.getQueue().clear();}
    private <R> void readMemory(java.util.concurrent.Callable<R> work,java.util.function.Consumer<R> ready){
        if(isFinishing()||isDestroyed()||browseWorker.isShutdown())return;
        final long generation=++browseGeneration;
        browseWorker.allowCoreThreadTimeOut(true);browseWorker.getQueue().clear();
        browseWorker.execute(()->{
            try{R result=work.call();handler.post(()->{if(generation==browseGeneration&&!isFinishing()&&!isDestroyed())ready.accept(result);});}
            catch(Exception error){handler.post(()->{if(generation==browseGeneration&&!isFinishing()&&!isDestroyed())toast(error.getMessage());});}
        });
    }
    private ProgressDialog workProgress;
    private final java.util.WeakHashMap<AlertDialog,Boolean> pageWorkDialogs=new java.util.WeakHashMap<>();
    private void clearPageWorkUi(){finishWorkUi();for(AlertDialog dialog:new java.util.ArrayList<>(pageWorkDialogs.keySet()))if(dialog!=null){try{dialog.dismiss();}catch(RuntimeException ignored){}}pageWorkDialogs.clear();}
    private int workOrientation;
    private void finishWorkUi(){
        workGeneration++;
        if(workProgress!=null){try{workProgress.dismiss();}catch(RuntimeException ignored){}workProgress=null;
            if(!isFinishing()&&!isDestroyed())setRequestedOrientation(workOrientation);}
    }
    @Override protected void onDestroy(){cancelBrowse();browseWorker.shutdownNow();Speech.release();deferredUi.clear();handler.removeCallbacksAndMessages(null);clearPageWorkUi();super.onDestroy();}
    private boolean managementMode;
    private String flashDate = "";
    private long homeRevision=-1;
    private final Handler handler = new Handler();
    private final Runnable queueTick = new Runnable() {
        public void run() {
            if (motionResumed && current == null) {
                if ("review".equals(page)) review();
                else if ("home".equals(page)) home();
            }
        }
    };
    protected void onCreate(Bundle saved) {
        if (Ui.isDark(this)) setTheme(getResources().getIdentifier("NavThemeDark", "style", getPackageName()));
        super.onCreate(saved); ui = new Ui(this);
        if(saved==null&&getIntent().getBooleanExtra("backup_only",false))getWindow().getDecorView().post(()->ThemeStore.dialog(this).setTitle(T.s("记忆备份"))
            .setItems(new String[]{T.s("导出备份（含图片与复习记录）"),T.s("恢复备份")},(dialog,which)->{if(which==0)pickExport();else confirmRestore();}).show());
        managementMode=saved==null?getIntent().getBooleanExtra(CardManagementActivity.EXTRA_MANAGE,false)
            :saved.getBoolean("managementMode",getIntent().getBooleanExtra(CardManagementActivity.EXTRA_MANAGE,false));
        if(managementMode&&saved==null)page="tools";
        deck = saved == null ? getIntent().getStringExtra("deck") : saved.getString("deck", "");
        if (deck == null) deck = "";
        if (saved == null && !deck.isEmpty()) page = "review";
        if (saved != null) {
            restoredStudyId=saved.getString("studyId","");restoredStudyDate=saved.getString("studyDate","");
            restoredRevealed=saved.getBoolean("studyRevealed",false);restoredTyped=saved.getString("studyTyped","");
            restoredGraded=saved.getBoolean("studyGraded",false);restoredGradedAt=saved.getLong("studyGradedAt",0);
            statsPeriod=Math.max(0,Math.min(2,saved.getInt("statsPeriod",0)));statsSubject=Math.max(0,saved.getInt("statsSubject",0));
            cardOffset=Math.max(0,saved.getInt("cardOffset",0));cardSearch=saved.getString("cardSearch","");cardBrowseDeck=saved.getString("cardBrowseDeck","");
            cardFilter=MemoryCardQuery.Filter.values()[Math.max(0,Math.min(3,saved.getInt("cardFilter",0)))];
            page = saved.getString("page", "home"); importType = saved.getString("importType", "qa");lastDeleted=saved.getString("lastDeleted","");
            authorFormat = saved.getInt("authorFormat", 0); authorType = saved.getInt("authorType", 0); authorSubject = saved.getString("authorSubject", "");
            try { String s = saved.getString("draft"); if (s != null) draft = new JSONObject(s); } catch (Exception ignored) { }
        }
        try {
            if ("editor".equals(page) && draft != null) editor();
            else if ("review".equals(page)) review();
            else if ("cards".equals(page)) cards(false);
            else if ("tools".equals(page)) tools();
            else if ("stats".equals(page)) stats();
            else home();
        } catch (RuntimeException e) { errorPage(e); }
    }
    protected void onSaveInstanceState(Bundle out) {
        if ("editor".equals(page)) collectDraft();
        out.putBoolean("managementMode",managementMode);
        if(current!=null&&(!graded||!submitting)&&("home".equals(page)||"review".equals(page))){
            out.putString("studyId",current.optString("id"));out.putString("studyDate",MemoryData.date(System.currentTimeMillis()));
            out.putBoolean("studyRevealed",revealed);out.putString("studyTyped",typed==null?"":typed.getText().toString());
            out.putBoolean("studyGraded",graded);out.putLong("studyGradedAt",gradedAt);
        }
        out.putInt("statsPeriod",statsPeriod);out.putInt("statsSubject",statsSubject);out.putInt("cardOffset",cardOffset);
        out.putString("cardSearch",cardSearch);out.putString("cardBrowseDeck",cardBrowseDeck);out.putInt("cardFilter",cardFilter.ordinal());
        out.putString("deck", deck); out.putString("page", page); out.putString("importType", importType);out.putString("lastDeleted",lastDeleted);
        out.putInt("authorFormat", authorFormat); out.putInt("authorType", authorType); out.putString("authorSubject", authorSubject);
        if (draft != null) out.putString("draft", draft.toString()); super.onSaveInstanceState(out);
    }
    protected void onPause() { if(activeStudyCard!=null)activeStudyCard.stopFeedback();motionResumed=false;imageActive(false);if(workProgress!=null)workProgress.hide();super.onPause(); handler.removeCallbacks(queueTick); }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);
        if("editor".equals(page)&&(intent.getBooleanExtra("from_bottom_nav",false)||intent.getBooleanExtra("open_learning",false))){ThemeStore.dialog(this).setTitle(T.s("离开卡片编辑？")).setMessage(T.s("尚未保存的修改会被放弃。")).setNegativeButton(T.s("继续编辑"),null).setPositiveButton(T.s("放弃并进入记忆"),(d,w)->{setIntent(intent);resetPageScroll();managementMode=false;home();}).show();return;}setIntent(intent);
        if(intent.getBooleanExtra("from_bottom_nav",false)||intent.getBooleanExtra("open_learning",false)){
            clearPageWorkUi();deferredUi.clear();
            boolean reuse="home".equals(page)&&!managementMode&&!revealed&&!submitting
                &&homeRevision==MemoryStore.revision()&&flashDate.equals(MemoryData.date(System.currentTimeMillis()));
            resetPageScroll();pageScrollCache.clear();managementMode=false;handler.removeCallbacksAndMessages(null);if(!reuse)home();
        }else if(intent.getBooleanExtra(CardManagementActivity.EXTRA_MANAGE,false)){managementMode=true;tools();}
    }
    protected void onResume() {
        motionResumed=true;
        super.onResume();
        if (ui != null && ui.dark != Ui.isDark(this)) { recreate(); return; }
        FloatingNav.fullscreen(this);
        if(("stats".equals(page)||"cards".equals(page))&&(browseRevision!=MemoryStore.revision()||!browseDate.equals(MemoryData.date(System.currentTimeMillis())))){
            if("stats".equals(page))stats();else cards(false);
        }
        imageActive(true);
        if(workProgress!=null&&deferredUi.isEmpty())workProgress.show();
        java.util.ArrayList<Runnable> ready=new java.util.ArrayList<>(deferredUi);deferredUi.clear();for(Runnable r:ready)r.run();
        // A committed rating is never resubmitted after returning from another page.
        if (submitting) { submitting = false; advance(); return; }
        if ("home".equals(page) && (!flashDate.equals(MemoryData.date(System.currentTimeMillis()))||(!revealed&&!graded&&homeRevision!=MemoryStore.revision()))) { home(); return; }
        if (("review".equals(page)||"home".equals(page)) && current == null) queueTick.run();
    }
    public void onBackPressed() {
        if ("home".equals(page)) { super.onBackPressed(); return; }
        if ("editor".equals(page)) {
            ThemeStore.dialog(this).setTitle(T.s("放弃本次编辑？"))
                .setNegativeButton(T.s("继续编辑"), null).setPositiveButton(T.s("放弃"), (d, w) -> home()).show();
        } else if ("tools".equals(page)&&managementMode) finish();
        else if(managementMode)tools();else home();
    }
    /**
     * 页内换页（学科列表 ↔ 卡片列表 ↔ 卡片编辑）不走 onPause/onResume，
     * 基类那套滚动保存在这里失效，所以每次换内容前自己存一份，换完再按页恢复。
     * 卡片列表按学科分开记：A 学科滚到第 50 张，不能影响 B 学科进来时的位置。
     */
    private final java.util.Map<String, Bundle> pageScrollCache = new java.util.HashMap<>();
    private String renderedScrollKey;

    private String scrollKey() { return "cards".equals(page) ? "cards|" + deck : page; }

    private void swapContent(View content) {
        if (renderedScrollKey != null) pageScrollCache.put(renderedScrollKey, PageScrollState.capture(this));
        renderedScrollKey = scrollKey();
        super.setContentView(content);
        PageScrollState.restore(this, pageScrollCache.get(renderedScrollKey));
    }

    private void base(String title, String subtitle) {
        cancelBrowse();
        clearPageWorkUi();
        handler.removeCallbacks(queueTick); current = null;activeStudyCard=null;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); ui.page(scroll);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32)); scroll.addView(body);
        swapContent(NavPolicy.memoryShowsNav(managementMode,page) ? FloatingNav.wrap(this, scroll, 0) : FloatingNav.plain(this, scroll)); getWindow().setStatusBarColor(ui.bg()); getWindow().setNavigationBarColor(ui.bg());
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
        try { m = MemoryStore.flashSnapshot(this); homeRevision=MemoryStore.revision(); } catch (RuntimeException e) { errorPage(e); return; }
        long now = System.currentTimeMillis(); flashDate = MemoryData.date(now);
        MemoryFlashcards.Progress p = MemoryFlashcards.progress(m, now);
        JSONObject next = restoreStudyCard(m,MemoryFlashcards.next(m, now),now);
        revealed = false; submitting = false; graded = false; gradedAt=0; current = next; studyImage = null; typed = null;
        if (next == null) {
            base(T.s("本日记忆闪卡"), "");
            LinearLayout empty = ui.card(); body.addView(empty, ui.gap(12));
            if(p.nextDue>now){
                text(empty,T.s("本轮回忆已结束，稍后继续巩固"),24);
                hint(empty,T.s("下次复习：")+dueLabel(p.nextDue,now));
                hint(empty,T.f("已完成 %d / %d · 待巩固 %d",p.done,p.total,p.total-p.done));
                scheduleQueue(p.nextDue);return;
            }
            text(empty, p.total > 0 ? T.s("本日闪卡已完成") : T.s("今天没有该复习的卡片"), 24);
            hint(empty, p.total > 0 ? T.s("复习记录已保存，明天按遗忘曲线重新排队。")
                    : p.available == 0 ? T.s("所选学科里没有卡片。可在守卫 → 卡管理 → 记忆卡中添加学科、制作或导入卡片。")
                    : T.f("到期的旧卡已经清完，今天的新学额度也用完了（每天新学 %d 张）。可在抽取设置里调整上限，或明天继续。", p.newLimit));
            return;
        }
        deck = next.optString("deck"); JSONObject d = m.deck(deck);
        baseStage();
        drawCard(m, d, next);
    }
    /** 卡片舞台：整页只有一张卡，页边距和卡内缩与打卡页一致（20dp 页边 + 18dp 卡缩）。 */
    private void baseStage() {
        cancelBrowse();
        handler.removeCallbacks(queueTick); current = null;activeStudyCard=null;
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(24));
        swapContent(NavPolicy.memoryShowsNav(managementMode,page) ? FloatingNav.wrap(this, body, 0) : FloatingNav.plain(this, body));
        getWindow().setStatusBarColor(ui.bg()); getWindow().setNavigationBarColor(ui.bg());
        FloatingNav.fullscreen(this);
    }
    /** Keep the tool control reachable without repeating the page title. */
    private void stageHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView tools = ui.iconButton("•••", ui.text2(), ui.surface());
        tools.setContentDescription(T.s("记忆工具"));
        tools.setOnClickListener(v -> tools());
        head.addView(tools, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.bottomMargin = ui.dp(12);
        body.addView(head, headLp);
    }
    private void scheduleQueue(long due){
        handler.removeCallbacks(queueTick);
        Calendar tomorrow=Calendar.getInstance();tomorrow.add(Calendar.DAY_OF_MONTH,1);
        tomorrow.set(Calendar.HOUR_OF_DAY,0);tomorrow.set(Calendar.MINUTE,0);tomorrow.set(Calendar.SECOND,0);tomorrow.set(Calendar.MILLISECOND,0);
        handler.postDelayed(queueTick,Math.max(1,Math.min(due,tomorrow.getTimeInMillis())-System.currentTimeMillis()));
    }
    private void flashSettings() {
        MemoryData m = MemoryStore.flashSnapshot(this); JSONArray active = m.activeDecks();
        if (active.length() == 0) { toast(T.s("请先在卡管理的记忆卡中添加学科")); return; }
        JSONArray selected = MemoryFlashcards.subjects(m);
        LinearLayout box = dialogBox(); hint(box, T.s("抽取学科（可多选）"));
        CheckBox[] checks = new CheckBox[active.length()];
        for (int i = 0; i < active.length(); i++) {
            JSONObject d = active.optJSONObject(i); CheckBox check = new CheckBox(this);
            check.setText(d.optString("name") + " · " + m.count(d.optString("id")) + T.s(" 张"));
            check.setTextColor(ui.text()); check.setChecked(MemoryData.contains(selected, d.optString("id")));
            box.addView(check); checks[i] = check;
        }
        EditText count = field(box, T.s("每天上限（1–1000 张）"), "" + MemoryFlashcards.count(m), false); count.setInputType(2);
        EditText fresh = field(box, T.s("每天新学上限（0–1000 张）"), "" + MemoryFlashcards.newLimit(m), false); fresh.setInputType(2);
        hint(box, T.s("队列按遗忘曲线排：到期的旧卡按「此刻还记得的概率」从低到高——忘得最厉害的先复习；旧卡不够时，用从没学过的新卡按入库顺序补位，每天最多补新学上限那么多张。\n没有随机、没有顺序规则，也没有「全部 / 仅到期」的池子：该复习什么由每张卡自己的 FSRS 到期时间决定。"));
        flashRules(box, active);
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        AlertDialog dialog = ThemeStore.dialog(this).setTitle(T.s("今日闪卡设置")).setView(scroll)
                .setPositiveButton(T.s("保存并重排"), null).setNegativeButton(T.s("取消"), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
            JSONArray ids = new JSONArray();
            for (int i = 0; i < checks.length; i++) if (checks[i].isChecked()) ids.put(active.optJSONObject(i).optString("id"));
            try {
                MemoryStore.configureFlashcards(this, ids, Integer.parseInt(count.getText().toString().trim()),
                        Integer.parseInt(fresh.getText().toString().trim()));
                dialog.dismiss(); if(managementMode)tools();else home();
            } catch (RuntimeException e) { toast(e instanceof NumberFormatException ? T.s("请填写 0–1000 的整数") : e.getMessage()); }
        })); dialog.show();
    }
    /** Public explanation follows MemoryScheduler, MemoryFlashcards and MemoryData, not a fixed-day mnemonic. */
    private void flashRules(LinearLayout box, JSONArray decks) {
        TextView title=text(box,T.s("记忆规律与复习规则"),18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hint(box,T.s("按你的表现调整\n使用 FSRS-6，根据每次评分、距上次复习的时间、卡片稳定性和难度计算下一次复习，不固定为第 1、3、7 天。它是排程模型，不保证每个人都能达到同样的记忆效果。"));
        hint(box,T.s("今日怎样抽卡\n到期旧卡先来，按可提取率（此刻还记得的概率）从低到高排；新卡只在旧卡之后补位，数量受每天新学上限约束。旧规则（随机 / 顺序 / 全部池 / 仅到期池）已删除，抽取范围只剩学科选择。"));
        hint(box,T.s("按钮与评分\n先回忆，再点“显示答案”，最后选择“认识 / 模糊 / 忘记”。滑动、点卡片和核对输入都不会自动评分。每个按钮显示本程序计算的下次复习时间；提交后直接进入下一张。"));
        hint(box,T.s("不认识\n揭晓前点“不认识”会记一次遗忘并显示答案。看完后点“下一张”，不会再评分；旋转屏幕仍保留这次结果。"));
        hint(box,T.s("新卡的短期学习\n“忘记”：约 1 分钟后重学；“模糊”：约 5.5–10 分钟后重学；首次“认识”：约 10 分钟后再确认，再次认识后转入按天复习。今日队列保留未学稳的卡，到期后再次出现；等待期间可以先学习其他卡。"));
        hint(box,T.s("已学卡的复习\n“忘记”：约 10 分钟后重学；“模糊”：约 15 分钟后再次回忆；认识后恢复按天复习。沿用 FSRS 状态计算，三按钮模式额外将复习卡的“模糊”安排到今日巩固。这是本程序公开的规则，不是墨墨的专有算法。"));
        hint(box,T.s("目标记忆率与间隔\n默认目标记忆率为 90%，可在各学科学习设置中调整为 70–97%。调高目标通常会缩短复习间隔、增加复习量；目标记忆率是模型参数，不是测得的个人记忆率。按天间隔至少 1 天，并受该学科的最大间隔限制。当前未使用个人历史自动训练参数。"));
        for(int i=0;i<decks.length();i++){
            JSONObject deck=decks.optJSONObject(i);if(deck==null)continue;
            hint(box,deck.optString("name")+T.s("：目标记忆率 ")+Math.round(MemoryData.retention(deck)*100)+T.s("% · 最大间隔 ")+MemoryData.maxInterval(deck)+T.s(" 天"));
        }
        hint(box,T.s("进度怎样计算\n卡面进度表示已完成本日学习步骤的卡片数，待巩固的卡不算完成；“已处理”按卡片去重，统计中的评分次数可以包含同日重学。只有选“认识”并进入按天复习才计入学科目标，每张卡在同一计划内只计一次。模糊和忘记不增加有效完成量。"));
    }
    /**
     * 记忆统计：把「复习量 / 记住率 / 到期预测 / 留存曲线」画成曲线。
     *
     * 全部从已有日志和卡片状态现算（{@link MemoryStats}），不另存计数器：
     * 同一份数据重算永远同一个数，和今日闪卡的进度不会各说各话。
     */
    private static final class StatisticsView {
        MemoryStats.PeriodSeries series;
        MemoryStats.Summary summary;
        int[] forecast,samples=new int[7],hits=new int[7],bands,strength;
        java.util.List<String> due;
        String[] bucketLabels=new String[7];
        int fresh,learning;
    }
    private void stats() {
        page="stats";
        browseRevision=MemoryStore.revision();browseDate=MemoryData.date(System.currentTimeMillis());
        base(T.s("记忆统计"),T.s("复习量、记住率与遗忘曲线。"));
        final long entry=browseEntry;
        TextView loading=hint(body,T.s("正在读取记忆统计…"));
        readMemory(()->MemoryStore.readOnlySnapshot(this),m->{
            body.removeView(loading);
            JSONArray decks=m.activeDecks();
            String[] subjects=new String[decks.length()+1];subjects[0]=T.s("全部学科");
            for(int i=0;i<decks.length();i++)subjects[i+1]=decks.optJSONObject(i).optString("name");
            if(statsSubject>=subjects.length)statsSubject=0;
            Spinner subject=spinner(body,subjects,statsSubject);
            Spinner period=spinner(body,new String[]{T.s("日 · 近30天"),T.s("周 · 近12周"),T.s("月 · 近12个月")},statsPeriod);
            LinearLayout plots=new LinearLayout(this);plots.setOrientation(LinearLayout.VERTICAL);body.addView(plots);
            Runnable render=()->{
                if(entry!=browseEntry||!"stats".equals(page))return;
                if(!browseDate.equals(MemoryData.date(System.currentTimeMillis()))){stats();return;}
                statsPeriod=period.getSelectedItemPosition();statsSubject=subject.getSelectedItemPosition();
                Set<String> selected=new LinkedHashSet<>();
                if(statsSubject>0)selected.add(decks.optJSONObject(statsSubject-1).optString("id"));
                final MemoryStats.Period unit=MemoryStats.Period.values()[statsPeriod];
                final long now=System.currentTimeMillis();
                readMemory(()->{
                    StatisticsView v=new StatisticsView();
                    v.series=MemoryStats.periods(m,now,unit,unit==MemoryStats.Period.DAY?30:12,selected);
                    v.summary=MemoryStats.summary(m,now,selected);
                    v.forecast=MemoryStats.forecast(m,now,7,selected);
                    MemoryStats.retention(m,v.samples,v.hits,v.bucketLabels,selected);
                    v.bands=MemoryStats.stabilityBands(m,selected);
                    v.strength=MemoryStats.strength(m,selected);v.due=MemoryStats.dueByDeck(m,now,selected);
                    for(int i=0;i<m.cards().length();i++){
                        JSONObject c=m.cards().optJSONObject(i);if(c==null||m.deleted(c.optString("deck"))||(!selected.isEmpty()&&!selected.contains(c.optString("deck"))))continue;
                        if(c.optInt("reps")==0)v.fresh++;
                        else if(c.optInt("state")!=MemoryScheduler.REVIEW)v.learning++;
                    }
                    return v;
                },v->drawStatistics(plots,v,now));
            };
            android.widget.AdapterView.OnItemSelectedListener listener=new android.widget.AdapterView.OnItemSelectedListener(){
                public void onItemSelected(android.widget.AdapterView<?> p,View view,int position,long id){render.run();}
                public void onNothingSelected(android.widget.AdapterView<?> p){}
            };
            subject.setOnItemSelectedListener(listener);period.setOnItemSelectedListener(listener);render.run();
        });
    }
    private void drawStatistics(LinearLayout plots,StatisticsView data,long now){
        // Chart construction is small; all scans/aggregation ran on browseWorker.
        plots.removeAllViews();
        LinearLayout original=body;body=plots;
        MemoryStats.Summary sum=data.summary;
        LinearLayout head=ui.card();body.addView(head,ui.gap(12));
        text(head,T.s("现在的位置"),18);
        hint(head,T.f("卡片 %d 张 · 已学 %d 张 · 今天到期 %d 张",sum.total,sum.learned,sum.dueToday));
        hint(head,T.f("今日评分 %d 次 · 连续 %d 天",sum.reviewedToday,sum.streak));
        hint(head,T.f("近 7 天记住率 %.0f%%（%d 次评分里记住 %d 次）",sum.accuracy7()*100,sum.reviews7,sum.correct7));
        MemoryStats.PeriodSeries series=data.series;
        double[] bars=new double[series.reviews.length],line=new double[bars.length];int reviews=0,unique=0;
        for(int i=0;i<bars.length;i++){bars[i]=series.reviews[i];line[i]=series.reviews[i]==0?-1:series.correct[i]/(double)series.reviews[i];reviews+=series.reviews[i];unique+=series.uniqueCards[i];}
        LinearLayout history=ui.card();body.addView(history,ui.gap(12));text(history,T.s("学习情况"),18);
        if(reviews==0)hint(history,T.s("所选范围还没有评分记录。"));
        hint(history,T.f("所选区间评分 %d 次 · 各周期去重卡数合计 %d 张",reviews,unique));
        hint(history,T.s("柱为评分次数，折线为认识占比；每周期的卡片数分别去重，跨周期会再次计数。"));
        addChart(history,bars,line,series.labels,T.s(" 次"));
        // 未来 7 天：到期量。柱高就是那天要还的债。
        int[] forecast = data.forecast;
        double[] fc = new double[7];
        String[] fcLabels = new String[7];
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(now);
        for (int i = 0; i < 7; i++) {
            fc[i] = forecast[i];
            Calendar day = Calendar.getInstance(); day.setTimeInMillis(now);
            day.add(Calendar.DAY_OF_MONTH, i);
            fcLabels[i] = i == 0 ? T.s("今天") : (day.get(Calendar.MONTH) + 1) + "/" + day.get(Calendar.DAY_OF_MONTH);
        }
        LinearLayout future = ui.card(); body.addView(future, ui.gap(12));
        TextView futureTitle = text(future, T.s("未来 7 天到期"), 18); futureTitle.setTypeface(Typeface.DEFAULT_BOLD);
        hint(future, T.f("今天含已逾期 %d 张；后面几天是 FSRS 排出来的到期日。柱太高就说明那天会积压。", forecast[0]));
        addChart(future, fc, null, fcLabels, T.s(" 张"));

        // 留存曲线：按「距上次复习多久」分组，看每组还答得出来的比例。
        int[] samples = new int[7], hits = new int[7];
        String[] bucketLabels = new String[7];
        samples=data.samples; hits=data.hits; bucketLabels=data.bucketLabels;
        double[] sbars = new double[7], sline = new double[7];
        for (int i = 0; i < 7; i++) {
            sbars[i] = samples[i];
            sline[i] = samples[i] >= 3 ? hits[i] / (double) samples[i] : -1;
        }
        LinearLayout curve = ui.card(); body.addView(curve, ui.gap(12));
        TextView curveTitle = text(curve, T.s("留存曲线 · 距离越久还记得吗"), 18); curveTitle.setTypeface(Typeface.DEFAULT_BOLD);
        hint(curve, T.s("柱是这一档的复习次数，线是其中记住的比例。样本少于 3 次的档位不画点——一两张卡撑不起一条曲线。越往右掉得越快，说明间隔该缩短（把目标记忆率调高）。"));
        addChart(curve, sbars, sline, bucketLabels, T.s(" 次"));


        LinearLayout stability=ui.card();body.addView(stability,ui.gap(12));
        text(stability,T.s("FSRS稳定性分布"),18);
        hint(stability,T.f("未学 %d 张 · 学习或重学中 %d 张",data.fresh,data.learning));
        double[] values=new double[5];int eligible=0;for(int i=0;i<5;i++){values[i]=data.bands[i];eligible+=data.bands[i];}
        hint(stability,T.f("复习阶段共 %d 张；以下五档互不重叠。",eligible));
        String[] bandNames={"<10", "10–29", "30–59", "60–89", "≥90"};
        addChart(stability,values,null,bandNames,T.s(" 张"));
        for(int i=0;i<5;i++)hint(stability,bandNames[i]+T.s(" 天：")+data.bands[i]+T.s(" 张"));
        hint(stability,T.s("横轴为FSRS稳定性天数，是模型估计；不代表保证记住的时长，也不是实测留存率。"));
        hint(stability,T.f("未学 %d 张 · 学习中 %d 张 · 稳定度 ≤7 天 %d 张 · 稳定度 >7 天 %d 张",data.strength[0],data.strength[1],data.strength[2],data.strength[3]));
        if(!data.due.isEmpty()){
            LinearLayout each=ui.card();body.addView(each,ui.gap(12));text(each,T.s("各学科今天到期"),18);
            for(String row:data.due){String[] parts=row.split("\t");hint(each,parts[0]+T.s(" · ")+(parts.length>1?parts[1]:"0")+T.s(" 张"));}
        }
        hint(body,T.s("统计只读：不会改动卡片状态。曲线里的每一次评分都来自日志，删掉学科会一并从曲线里消失。"));
        body=original;
    }

    private int sumReview(int[] values) { int n = 0; for (int v : values) n += v; return n; }

    private void addChart(LinearLayout host, double[] bars, double[] line, String[] labels, String unit) {
        MemoryChartView chart = new MemoryChartView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(152));
        lp.topMargin = ui.dp(4); lp.bottomMargin = ui.dp(4);
        host.addView(chart, lp);
        chart.render(bars, line, labels, unit);
    }

    private void tools() {
        page = "tools"; flashMode = false; draft = null; MemoryData m;
        try { m = MemoryStore.snapshot(this); } catch (RuntimeException e) { errorPage(e); return; }
        base(T.s("记忆卡设置"), T.s("学科、卡片、错题与学习设置。"));
        MemoryFlashcards.Load load=MemoryFlashcards.inspectLoad(m,System.currentTimeMillis());
        LinearLayout loadBox=ui.card();body.addView(loadBox,ui.gap(12));loadBox.setTag("memory-load-settings");
        hint(loadBox,T.f("待复习 %d · 待新学 %d · 已处理 %d",load.reviewPending,load.newPending,load.processed));
        hint(loadBox,T.f("入队 %d / 上限 %d · 新学上限 %d · 所选学科到期 %d",load.queued,load.count,load.newLimit,load.backlog));
        if(!load.sessionExists)hint(loadBox,T.s("今日队列尚未生成；进入记忆学习时再生成。"));
        MemoryFlashcards.Progress flash = MemoryFlashcards.progress(m, System.currentTimeMillis());
        button(body, T.s("抽取设置  ·  按遗忘曲线  ·  ") + MemoryFlashcards.count(m) + T.s(" 张/天")
                + T.s(" ·  新学 ") + MemoryFlashcards.newLimit(m), false, v -> flashSettings());
        button(body, T.s("记忆统计  ·  曲线"), false, v -> stats());
        hint(body, T.s("本日已复习 ") + flash.done + "/" + flash.total + T.s(" · 今日累计 ") + flash.reviewedToday + T.s(" 张。")
                + T.s("\n到期旧卡按遗忘风险排序，新卡按新学上限补位。")
                + T.s("\n显示答案后，点认识、模糊或忘记。未学稳的卡会在今日继续巩固，滑动与点卡片不评分。"));
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
            small(row, T.s("挑选"), v -> { deck = id; pickQueue(d); });
            small(row, T.s("设置"), v -> { deck = id; settings(d); });
        }
        button(body,T.s("学科回收站 · ")+m.deletedDecks().length(),false,v->subjectTrash());
        button(body, T.s("开源组件与许可证"), false, v -> license());
        hint(body, T.s("每天每张卡只计一次。新卡和遗忘卡须完成学习步骤后才计数。\n今日目标最多为今日队列数量。开始答题后，当日计划固定，设置次日生效。"));
    }
    /**
     * 挑选今日队列：复习候选（今天到期）+ 新学候选（从未学过）两段多选。
     * 勾选状态记在集合里而不是只在复选框上——候选超过渲染上限时，
     * 没显示出来的已选卡也不会在保存时丢掉。
     */
    private void pickQueue(JSONObject d) {
        final String id = d.optString("id");
        MemoryData m;
        try { m = MemoryStore.snapshot(this); } catch (RuntimeException e) { toast(e.getMessage()); return; }
        if (m.deleted(id)) { toast(T.s("学科已移入回收站")); return; }
        long now = System.currentTimeMillis();
        if (m.plan(id, now).optBoolean("started")) { toast(T.s("今日队列已开始答题，挑选对下一次生效")); return; }
        final Set<String> checked = new LinkedHashSet<>();
        JSONArray planIds = m.plan(id, now).optJSONArray("ids");
        for (int i = 0; i < planIds.length(); i++) checked.add(planIds.optString(i));
        final List<JSONObject> review = m.dueCandidates(id, now);
        final List<JSONObject> fresh = m.newCandidates(id);

        LinearLayout b = dialogBox();
        EditText search = field(b, T.s("搜索题目或答案"), "", false);
        final LinearLayout reviewBox = new LinearLayout(this); reviewBox.setOrientation(LinearLayout.VERTICAL); b.addView(reviewBox);
        final LinearLayout freshBox = new LinearLayout(this); freshBox.setOrientation(LinearLayout.VERTICAL); b.addView(freshBox);
        final TextView summary = hint(b, "");
        hint(b, T.s("复习候选按到期先后，新学候选按入库顺序；每段最多显示 200 张，搜不到就缩小关键词。勾选即进今日队列，不受每日上限约束；开始答题后队列固定。"));

        final Runnable paint = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            paintCandidates(reviewBox, review, q, checked, T.s("复习候选 · 今天到期"), summary);
            paintCandidates(freshBox, fresh, q, checked, T.s("新学候选 · 从未学过"), summary);
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { paint.run(); }
            public void afterTextChanged(Editable s) { }
        });
        paint.run();

        ScrollView scroll = new ScrollView(this); scroll.addView(b);
        AlertDialog dlg = ThemeStore.dialog(this).setTitle(d.optString("name") + T.s(" · 挑选今日卡片")).setView(scroll)
                .setPositiveButton(T.s("保存队列"), null).setNeutralButton(T.s("清空选择"), null).setNegativeButton(T.s("取消"), null).create();
        pageWorkDialogs.put(dlg, true);
        dlg.setOnShowListener(ignored -> {
            dlg.getButton(-1).setOnClickListener(v -> {
                // 复习在前、新学在后：和自动挑选同一顺序，复习先清积压。
                JSONArray ids = new JSONArray(); int reviewPicked = 0, freshPicked = 0;
                for (JSONObject c : review) if (checked.contains(c.optString("id"))) { ids.put(c.optString("id")); reviewPicked++; }
                for (JSONObject c : fresh) if (checked.contains(c.optString("id"))) { ids.put(c.optString("id")); freshPicked++; }
                try {
                    MemoryStore.pickPlan(this, id, ids);
                    dlg.dismiss(); tools();
                    toast(T.s("今日队列已更新 · 复习 ") + reviewPicked + T.s(" · 新学 ") + freshPicked);
                } catch (RuntimeException e) { toast(e.getMessage()); }
            });
            dlg.getButton(-3).setOnClickListener(v -> { checked.clear(); paint.run(); });
        });
        dlg.show();
    }

    /** 渲染一段候选：命中数（含搜索过滤）写入段标题，最多画 cap 行；勾选状态进 checked 集合。 */
    private void paintCandidates(LinearLayout host, List<JSONObject> candidates, String q,
                                 Set<String> checked, String title, TextView summary) {
        host.removeAllViews();
        TextView head = new TextView(this); head.setTextSize(13); head.setTextColor(ui.text2());
        head.setPadding(0, ui.dp(10), 0, ui.dp(2));
        host.addView(head);
        int matched = 0, rendered = 0;
        for (JSONObject c : candidates) {
            String hay = (c.optString("front") + " " + c.optString("answer")).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            matched++;
            if (rendered >= PICK_CAP) continue;
            rendered++;
            final String cid = c.optString("id");
            String front = c.optString("front").replaceAll("\\s+", " ");
            CheckBox check = new CheckBox(this);
            check.setMaxLines(2); check.setEllipsize(android.text.TextUtils.TruncateAt.END);
            check.setText(front.length() > 80 ? front.substring(0, 80) + "…" : front);
            check.setTextSize(13); check.setTextColor(ui.text());
            check.setChecked(checked.contains(cid));
            check.setOnCheckedChangeListener((g, on) -> {
                if (on) checked.add(cid); else checked.remove(cid);
                summary.setText(T.s("已选 ") + checked.size() + T.s(" 张"));
            });
            host.addView(check);
        }
        head.setText(title + " · " + matched + T.s(" 张") + (matched > PICK_CAP ? T.s("（显示前 ") + PICK_CAP + T.s(" 张，请用搜索缩小范围）") : ""));
        summary.setText(T.s("已选 ") + checked.size() + T.s(" 张"));
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
            final MemoryData data=MemoryStore.snapshot(this);final JSONArray trash=data.deletedDecks();
            if(trash.length()==0){toast(T.s("回收站为空"));return;}
            String[] names=new String[trash.length()];
            for(int i=0;i<names.length;i++){JSONObject d=trash.optJSONObject(i);names[i]=d.optString("name")+" · "+data.count(d.optString("id"))+T.s(" 张卡片");}
            ThemeStore.dialog(this).setTitle(T.s("学科回收站")).setItems(names,(d,which)->{
                final JSONObject deck=trash.optJSONObject(which);
                final String id=deck.optString("id"),name=deck.optString("name");
                ThemeStore.dialog(this).setTitle(name)
                        .setItems(new String[]{T.s("恢复"),T.s("彻底删除（不可恢复）")},(pick,index)->{
                            if(index==0)restoreSubject(id);else confirmPurge(id,name,data.count(id));
                        }).setNegativeButton(T.s("取消"),null).show();
            }).setNegativeButton(T.s("关闭"),null).show();
        }catch(RuntimeException e){toast(e.getMessage());}
    }
    private void confirmPurge(final String id,String name,int cards){
        ThemeStore.dialog(this).setTitle(T.f("彻底删除「%s」？",name))
                .setMessage(T.f("这个学科的 %d 张卡片、复习记录和今日计划会一起删掉，删完找不回来。",cards))
                .setNegativeButton(T.s("取消"),null)
                .setPositiveButton(T.s("彻底删除"),(d,w)->{
                    try{int removed=MemoryStore.purgeDeck(this,id);lastDeleted="";syncGuard();tools();toast(T.f("已彻底删除，共 %d 张卡片",removed));}
                    catch(RuntimeException e){toast(e.getMessage());}
                }).show();
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
        hint(b, T.s("开启后，打卡页显示记忆卡；在记忆页完成目标。目标未完成时会参与学习联动，从应用冻结结束点继续冻结。\n开始答题后修改设置，次日生效。到期复习积压超过上限时，暂停新增卡片。"));
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
    private String cardBrowseDeck="";
    private static final class CardPage {
        int count; boolean sessionExists; java.util.List<JSONObject> cards=new java.util.ArrayList<>();
    }
    private void cards(boolean wrong) {
        page="cards";
        browseRevision=MemoryStore.revision();browseDate=MemoryData.date(System.currentTimeMillis());
        if(!deck.equals(cardBrowseDeck)){cardBrowseDeck=deck;cardOffset=0;cardSearch="";cardFilter=MemoryCardQuery.Filter.ALL;}
        if(wrong){cardFilter=MemoryCardQuery.Filter.WRONG;cardOffset=0;}
        base(T.s("卡片管理"),T.s("难记：累计遗忘至少3次；筛选不会改动学习计划。"));
        final long entry=browseEntry;
        final String currentDeck=deck;
        TextView create=button(body,T.s("新建卡片"),true,v->{});create.setEnabled(false);
        button(body,T.s("批量导入文本 / TSV"),false,v->importDialog());
        Spinner filter=spinner(body,new String[]{T.s("全部"),T.s("今日队列"),T.s("难记"),T.s("错题")},cardFilter.ordinal());
        EditText search=field(body,T.s("搜索题目或答案"),cardSearch,false);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);body.addView(list);
        TextView loading=hint(list,T.s("正在读取卡片…"));
        readMemory(()->MemoryStore.readOnlySnapshot(this),m->{
            JSONObject d=m.deck(currentDeck);if(d==null){tools();return;}
            create.setEnabled(true);create.setOnClickListener(v->{draft=MemoryData.newCard(currentDeck,"english".equals(d.optString("subject"))?"input":"qa","","","","");editor();});
            Runnable render=()->{
                if(entry!=browseEntry||!"cards".equals(page)||!currentDeck.equals(deck))return;
                if(!browseDate.equals(MemoryData.date(System.currentTimeMillis()))){cards(false);return;}
                cardFilter=MemoryCardQuery.Filter.values()[filter.getSelectedItemPosition()];cardSearch=search.getText().toString();
                final MemoryCardQuery.Filter selected=cardFilter;final String query=cardSearch;final int offset=cardOffset;
                final Set<String> subjects=new LinkedHashSet<>();subjects.add(currentDeck);
                readMemory(()->{
                    CardPage result=new CardPage();long now=System.currentTimeMillis();
                    result.count=MemoryCardQuery.count(m,selected,subjects,now,query);
                    result.sessionExists=MemoryFlashcards.inspectLoad(m,now).sessionExists;
                    for(String id:MemoryCardQuery.page(m,selected,subjects,now,offset,50,query))result.cards.add(m.card(id));
                    return result;
                },result->{
                    list.removeAllViews();
                    hint(list,T.f("共 %d 张 · 第 %d 页",result.count,offset/50+1));
                    if(selected==MemoryCardQuery.Filter.TODAY&&!result.sessionExists)
                        hint(list,T.s("今日队列尚未生成；进入记忆学习时再生成。"));
                    for(JSONObject c:result.cards){
                        LinearLayout card=ui.card();list.addView(card,ui.gap(10));
                        TextView title=text(card,c.optString("front"),16);title.setMaxLines(3);title.setTextIsSelectable(false);
                        hint(card,T.f("复习 %d 次 · 遗忘 %d 次",c.optInt("reps"),c.optInt("lapses")));
                        card.setOnClickListener(v->{draft=MemoryData.copy(c);editor();});
                    }
                    if(offset>0)button(list,T.s("上一页"),false,v->{cardOffset=Math.max(0,cardOffset-50);renderCardsAgain(filter,search);});
                    if(offset+50<result.count)button(list,T.s("下一页"),false,v->{cardOffset+=50;renderCardsAgain(filter,search);});
                    if(result.count==0)hint(list,T.s("没有符合条件的卡片。"));
                });
            };
            search.setTag(render);
            filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                public void onItemSelected(android.widget.AdapterView<?> parent,View view,int pos,long id){if(entry!=browseEntry)return;if(MemoryCardQuery.Filter.values()[pos]!=cardFilter)cardOffset=0;render.run();}
                public void onNothingSelected(android.widget.AdapterView<?> parent){}
            });
            final Runnable debounce=()->{if(entry!=browseEntry||!"cards".equals(page))return;cardOffset=0;render.run();};
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence x,int a,int c,int f){}public void afterTextChanged(Editable x){}public void onTextChanged(CharSequence x,int a,int before,int count){handler.removeCallbacks(debounce);handler.postDelayed(debounce,200);}});
            render.run();
        });
    }
    private void renderCardsAgain(Spinner filter,EditText search){Object action=search.getTag();if(action instanceof Runnable)((Runnable)action).run();}
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
        JSONObject next = restoreStudyCard(m,m.next(deck, System.currentTimeMillis()),System.currentTimeMillis());
        base(d.optString("name"), T.s("今日有效复习 ") + p.done + " / " + p.target + T.s(" · 队列 ") + p.total + T.s(" 张"));
        revealed = false; submitting = false; graded = false; gradedAt=0; current = next; studyImage = null; typed = null;
        if (next == null) {
            if (p.nextDue > 0) {
                text(body, T.s("本轮回忆已结束，稍后继续巩固"), 21);
                hint(body,T.s("下次复习：")+dueLabel(p.nextDue,System.currentTimeMillis()));
                hint(body, T.s("先休息一下。到期后自动显示下一张，重复翻看不增加完成量。"));
                scheduleQueue(p.nextDue);
            } else {
                text(body, p.total == 0 ? T.s("今天没有待学卡片") : T.s("今日队列已完成"), 23);
                hint(body, p.total == 0 ? T.s("先添加卡片；开始答题后新增的卡片将进入明天的计划。") : T.s("复习进度已保存。放行仍遵守原时间表和其他学习目标。"));
                hint(body,T.s("卡片设置位于守卫 → 卡管理 → 记忆卡。"));
            }
            button(body, T.s("返回记忆首页"), true, v -> home()); return;
        }
        drawCard(m, d, next);
    }
    private TextView reviewPreview;
    private static final class CardReadout {long remember,vague,forget;}
    /** An already committed "don't know" can remain open across recreation, never be rated twice. */
    private JSONObject restoreStudyCard(MemoryData m,JSONObject next,long now){
        if(!restoredGraded)return next;
        JSONObject held=m.card(restoredStudyId);
        JSONArray ids=flashMode?MemoryFlashcards.session(m,now).optJSONArray("ids"):m.plan(deck,now).optJSONArray("ids");
        if(held!=null&&!m.deleted(held.optString("deck"))&&MemoryData.date(now).equals(restoredStudyDate)
                &&restoredGradedAt>0&&held.optLong("last")==restoredGradedAt&&MemoryData.contains(ids,restoredStudyId))return held;
        restoredGraded=false;restoredStudyId="";return next;
    }
    private String dueLabel(long due,long now){
        if(due<=0)return "—";
        if(due<=now)return T.s("现在可复习");
        if(MemoryData.date(due).equals(MemoryData.date(now)))return T.f("今天约%d分钟后",Math.max(1,(due-now+59999)/60000));
        return new java.text.SimpleDateFormat("yyyy/M/d",Locale.getDefault()).format(new java.util.Date(due));
    }
    private void drawCard(MemoryData m, JSONObject d, JSONObject next) {
        final boolean stage = flashMode;
        current=next;
        final long entry=browseEntry;
        final String id = next.optString("id"); final boolean isInput = "input".equals(next.optString("type"));
        MemoryFlashcards.Progress progress = null;
        if (stage) {
            progress = MemoryFlashcards.progress(m, System.currentTimeMillis());
            stageHeader();
        }
        final StudyCardSurface card = new StudyCardSurface(this);activeStudyCard=card;
        if (stage) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
            int inset = ui.dp(8); slp.setMargins(inset, inset, inset, inset);
            body.addView(card, slp);
        } else body.addView(card, ui.gap(16));

        if (stage) {
            // 卡面顶行与打卡页同构：左边卡片类型，右边「第几张 / 共几张」。
            LinearLayout tagRow = new LinearLayout(this);
            tagRow.setOrientation(LinearLayout.HORIZONTAL); tagRow.setGravity(Gravity.CENTER_VERTICAL);
            tagRow.setPadding(0, 0, 0, ui.dp(6));
            tagRow.addView(ui.sub(T.s("记忆卡")), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tagRow.addView(ui.sub(progress.done + " / " + progress.total));
            card.addView(tagRow);
        }
        // Content scrolls; explicit actions remain reachable above the bottom dock.
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(content);
        card.addView(scroll,new LinearLayout.LayoutParams(-1,stage?0:-2,stage?1f:0f));
        LinearLayout wordRow=new LinearLayout(this);wordRow.setGravity(Gravity.TOP);content.addView(wordRow);
        TextView question = studyText(wordRow,MemoryData.question(next),36,"memory-word");
        question.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1f));
        // The single speaker uses US pronunciation; no second accent control on the card.
        final String spoken = Speech.latinWord(MemoryData.question(next));
        if (!spoken.isEmpty()) {
            View speaker=speakerButton(spoken);LinearLayout.LayoutParams voiceLp=new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48));
            voiceLp.setMargins(ui.dp(8),ui.dp(8),0,0);wordRow.addView(speaker,voiceLp);
        }
        if (!next.optString("image").isEmpty()) {
            studyImage = new MemoryImageView(this, next.optString("image"), next.optJSONArray("masks"), false);
            content.addView(studyImage, new LinearLayout.LayoutParams(-1, ui.dp(300)));
        }
        if (isInput) typed = field(content, T.s("先输入你的答案"), "", false);
        reviewPreview=hint(content,"");reviewPreview.setTextIsSelectable(false);reviewPreview.setTextSize(11);
        final TextView preview=reviewPreview;
        final LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL); result.setVisibility(View.GONE); content.addView(result);
        CardPalette.apply(card, CardPalette.MEMORY, false);
        final LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);card.addView(controls);
        final LinearLayout ratings=new LinearLayout(this);ratings.setOrientation(LinearLayout.HORIZONTAL);ratings.setBaselineAligned(false);
        ratings.setVisibility(View.GONE);
        LinearLayout recallActions=new LinearLayout(this);recallActions.setBaselineAligned(false);controls.addView(recallActions,ui.gap(10));
        final TextView show=button(recallActions,T.s("显示答案"),true,null);show.setTag("memory-show-answer");
        final TextView unknown=button(recallActions,T.s("不认识"),false,null);unknown.setTag("memory-unknown");
        for(TextView action:new TextView[]{show,unknown}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1f);lp.setMargins(ui.dp(3),0,ui.dp(3),0);action.setLayoutParams(lp);action.setMinHeight(ui.dp(54));}
        final TextView nextCard=button(controls,T.s("下一张"),true,null);nextCard.setTag("memory-next-card");nextCard.setVisibility(View.GONE);
        controls.addView(ratings,ui.gap(10));
        final TextView remember=ratingButton(ratings,T.s("认识"),3,0xFF2D8053,entry,next);
        final TextView vague=ratingButton(ratings,T.s("模糊"),2,0xFF956A16,entry,next);
        final TextView forget=ratingButton(ratings,T.s("忘记"),1,0xFFC04747,entry,next);
        show.setOnClickListener(v->{
            if(entry!=browseEntry||current!=next||graded||submitting)return;
            revealCard(next,d,result);recallActions.setVisibility(View.GONE);ratings.setVisibility(View.VISIBLE);
        });
        Runnable committedAnswer=()->{
            recallActions.setVisibility(View.GONE);ratings.setVisibility(View.GONE);nextCard.setVisibility(View.VISIBLE);
            preview.setText(T.s("已记为遗忘 · 下次复习：")+dueLabel(MemoryStore.cardDue(this,id),System.currentTimeMillis()));
        };
        unknown.setOnClickListener(v->{
            if(entry!=browseEntry||current!=next||revealed||graded||submitting||!motionResumed)return;
            show.performClick();
            if(grade(id,1)){submitting=false;committedAnswer.run();}
        });
        nextCard.setOnClickListener(v->{if(entry==browseEntry&&current==next&&graded&&motionResumed)advance();});
        readMemory(()->{
            CardReadout out=new CardReadout();long now=System.currentTimeMillis();
            out.remember=m.previewRating(id,3,now).due;out.vague=m.previewRating(id,2,now).due;out.forget=m.previewRating(id,1,now).due;
            return out;
        },out->{if(graded)return;
            long now=System.currentTimeMillis();preview.setText(T.s("先回忆，再显示答案并选择记忆程度"));
            ratingLabel(remember,T.s("认识"),out.remember,now);ratingLabel(vague,T.s("模糊"),out.vague,now);ratingLabel(forget,T.s("忘记"),out.forget,now);
        });
        if(id.equals(restoredStudyId)&&MemoryData.date(System.currentTimeMillis()).equals(restoredStudyDate)){
            if(typed!=null)typed.setText(restoredTyped);
            if(restoredRevealed)show.performClick();
            if(restoredGraded){graded=true;gradedAt=restoredGradedAt;submitting=false;committedAnswer.run();}
        }
        restoredStudyId="";restoredStudyDate="";restoredTyped="";restoredRevealed=false;restoredGraded=false;restoredGradedAt=0;
    }
    private TextView studyText(LinearLayout parent,String value,int size,String tag){
        TextView view=text(parent,value,size);SwissTypography.role(view,SwissTypography.Role.KEEP);
        view.setTextSize(size);view.setLineSpacing(0,1.22f);view.setTag(tag);return view;
    }

    private TextView ratingButton(LinearLayout row,String label,int rating,int color,long entry,JSONObject card){
        TextView button=new TextView(this);button.setText(label);button.setTextSize(15);button.setGravity(Gravity.CENTER);
        SwissTypography.role(button,SwissTypography.Role.BUTTON);button.setMinHeight(ui.dp(64));
        button.setPadding(ui.dp(4),ui.dp(12),ui.dp(4),ui.dp(12));
        button.setTextColor(ui.dark?0xFFF5F5F5:color);
        ActionFeedback.bind(button,ui,ui.dark?ui.surface2():0xFFF5F4EE,12,ui.dark?0xFFF5F5F5:color);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1f);lp.setMargins(ui.dp(3),0,ui.dp(3),0);row.addView(button,lp);
        button.setTag("memory-rate-"+rating);
        button.setOnClickListener(v->{
            if(entry!=browseEntry||current!=card||!motionResumed)return;
            if(grade(card.optString("id"),rating))advance();
        });return button;
    }
    private void ratingLabel(TextView button,String label,long due,long now){
        String time=dueLabel(due,now);android.text.SpannableString text=new android.text.SpannableString(label+"\n"+time);
        text.setSpan(new android.text.style.RelativeSizeSpan(.72f),label.length()+1,text.length(),0);
        button.setText(text);button.setContentDescription(label+"，"+time);
    }

    private View speakerButton(String word) {
        android.widget.FrameLayout button=new android.widget.FrameLayout(this);button.setTag("memory-speaker");
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},ui.round(0x3320232A,24));
        states.addState(new int[]{android.R.attr.state_focused},ui.round(0x3320232A,24));
        states.addState(new int[]{},ui.round(0x1420232A,24));button.setBackground(states);button.setFocusable(true);
        LineIcon icon=new LineIcon(this,"🔊",CardPalette.ink(CardPalette.MEMORY));
        button.addView(icon,new android.widget.FrameLayout.LayoutParams(ui.dp(24),ui.dp(24),Gravity.CENTER));
        button.setContentDescription(T.s("美音朗读"));button.setOnClickListener(v->pronounce(word,false));
        button.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View view,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(view,info);info.setClassName(android.widget.Button.class.getName());}
        });return button;
    }

    /** 朗读：口音缺语音包或没有引擎时明说，不静默失败。 */
    private void pronounce(String word, boolean uk) {
        int r = Speech.speak(this, word, uk);
        if (r == Speech.NO_VOICE) toast(T.s("系统没有这个口音的语音包"));
        else if (r == Speech.NO_ENGINE) toast(T.s("系统没有可用的语音引擎"));
    }

    /** Revealing is read-only. Only one of the three explicit rating buttons can submit. */
    private void revealCard(JSONObject next, JSONObject d, LinearLayout result) {
        if (revealed) return;
        revealed = true;
        if (typed != null) {
            String value = typed.getText().toString().trim();
            if (!value.isEmpty())hint(result,MemoryData.matches(value,next.optString("answer"),"english".equals(d.optString("subject")))?T.s("答案匹配，请选择记忆程度"):T.s("答案不匹配，请选择记忆程度"));
            typed.setEnabled(false);
            android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if(keyboard!=null)keyboard.hideSoftInputFromWindow(typed.getWindowToken(),0);
        }
        if (studyImage != null) studyImage.reveal();
        TextView answer = studyText(result, MemoryData.answer(next), 26,"memory-meaning");
        answer.setTextIsSelectable(true);
        if (!next.optString("source").isEmpty()) hint(result, T.s("来源：") + next.optString("source"));
        if (!next.optString("note").isEmpty()) hint(result, T.s("备注：") + next.optString("note"));
        CardPalette.textColors(result, CardPalette.MEMORY);
        result.setVisibility(View.VISIBLE);
        if(activeStudyCard!=null)activeStudyCard.highlight();
    }
    /** Single commit path: must reveal first; repeated/stale button presses cannot resubmit. */
    private boolean grade(String id, int rating) {
        if (!revealed || graded || submitting || current==null || !id.equals(current.optString("id"))) return false;
        submitting = true;
        try {
            LearningLink.Progress before = LearningLink.memory(this);
            if (flashMode) { MemoryStore.rateFlashcard(this, id, rating, flashDate); syncGuard(); }
            else { boolean credited = MemoryStore.rate(this, id, rating); if (credited) toast(T.s("有效复习 +1，进度已保存")); syncGuard(); }
            graded=true;
            gradedAt=MemoryStore.cardLast(this,id);
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
        deferredUi.clear();finishWorkUi();
        final long token=workGeneration;final View owner=body;
        workOrientation=getRequestedOrientation();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        workProgress=ProgressDialog.show(this,title,T.s("请稍候…"),true,false);
        java.util.function.Consumer<Runnable> post=action->runOnUiThread(()->{
            if(token==workGeneration&&owner==body&&!isFinishing()&&!isDestroyed()){
                if(motionResumed)action.run();else deferredUi.add(()->{if(owner==body&&!isFinishing()&&!isDestroyed())action.run();});
            }
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
        button(body, T.s("数据与备份"), true, v -> startActivity(new Intent(this,DataManagementActivity.class)));
        button(body, T.s("返回首页"), false, v -> finish());
    }
    private void toast(String s) { Toast.makeText(this, s == null ? T.s("操作失败") : s, Toast.LENGTH_LONG).show(); }
}
