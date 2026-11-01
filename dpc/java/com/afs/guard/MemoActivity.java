package com.afs.guard;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.Spanned;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * 备忘录：一页两种形态。默认是速记列表（置顶优先、搜索、长按管理），
 * 带 id 进来就是编辑页。数据全在本机 {@link MemoStore}，不联网。
 */
public final class MemoActivity extends ThemedActivity {
    private static final int REQ_IMPORT=42, REQ_EXPORT=43,REQ_ATTACH=45,REQ_VOICE=46,REQ_BACKUP=47,REQ_RESTORE=48,REQ_SKETCH=49;
    private static final int REQ_EDITOR = 41;   // 从编辑页回来要知道「是不是新建了一条」

    private Ui ui;
    private long aiGeneration;
    private android.app.ProgressDialog aiProgress;
    private android.app.AlertDialog aiPreview;
    private interface AiRequest {String run(android.content.Context c,String action,String source) throws Exception;}
    private AiRequest aiRequest=MemoAi::request;
    private void cancelAiUi(){
        aiGeneration++;
        if(aiProgress!=null){try{aiProgress.dismiss();}catch(RuntimeException ignored){}aiProgress=null;}
        if(aiPreview!=null){try{aiPreview.dismiss();}catch(RuntimeException ignored){}aiPreview=null;}
    }
    private boolean ownsAi(long token,MemoChecklistInput owner,String noteId){
        return token==aiGeneration&&bodyInput==owner&&java.util.Objects.equals(entryId,noteId)&&!isFinishing()&&!isDestroyed();
    }

    /* ---------- 编辑页 ---------- */
    private MemoStore.Note entry;       // 打开编辑页时的原条目；null = 新建
    private String entryId;
    private boolean editor;
    private boolean savedOnce;          // save() 幂等标记
    private EditText titleInput;
    private MemoChecklistInput bodyInput;
    private MemoEditor.History history;
    private boolean historyBinding, previewing;
    /** 有序列表回车续号：改文本时自己触发的 TextWatcher 回调要跳过。 */
    private boolean listContinuing;
    private TextView undoButton,redoButton,previewButton,editorStatus,previewText;
    private ScrollView previewScroll;
    private String exportText="";
    private long draftRemindAt;
    private String notebookFilter="*",draftBook="",draftPaper="default";
    private final android.os.Handler edits=new android.os.Handler();
    private View keyboardRoot;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener legacyKeyboard;
    private final Runnable remember=()->{if(history!=null&&!historyBinding){history.record(snapshot());updateHistory();}};
    @Override protected void onDestroy(){
        cancelAiUi();
        if(cardMenu!=null)cardMenu.dismiss();
        edits.removeCallbacksAndMessages(null);
        if(keyboardRoot!=null&&legacyKeyboard!=null&&keyboardRoot.getViewTreeObserver().isAlive())keyboardRoot.getViewTreeObserver().removeOnGlobalLayoutListener(legacyKeyboard);
        super.onDestroy();
    }


    /* ---------- 列表页 ---------- */
    private List<MemoStore.Note> notes;
    private String query = "";
    private boolean searchOpen;
    private boolean bindingSearch;
    private boolean listedOnce;         // 首次 onResume 不重刷，避免打掉恢复的滚动位置
    private boolean resultRefreshed;    // 本轮返回已由 onActivityResult 刷过，onResume 别再刷一次
    private TextView countLabel;
    private EditText search;
    private ScrollView listScroll;
    private LinearLayout listBody;
    private PopupWindow cardMenu;
    private MemoNotebookTabs notebookTabs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        if(state!=null)notebookFilter=state.getString("bookFilter","*");
        if(state!=null)exportText=state.getString("exportText","");
        String id = getIntent().getStringExtra("id");
        boolean creating = getIntent().getBooleanExtra("new", false);
        MemoStore.Note found = id == null || id.isEmpty() ? null : find(id);
        entry = found == null ? null : shown(found);
        entryId = entry == null ? null : entry.id;
        draftBook=entryId==null?getIntent().getStringExtra("book"):MemoLibrary.notebookOf(this,entryId);if(draftBook==null)draftBook="";
        draftPaper=entryId==null?"default":MemoLibrary.paperOf(this,entryId);
        if(state!=null){draftBook=state.getString("draftBook",draftBook);draftPaper=state.getString("draftPaper",draftPaper);}
        editor = entry != null || creating;   // 带 new=true 也是编辑页（新建形态）
        if (editor) editorPage(state); else listPage(state);
    }

    @Override protected void onResume() {
        super.onResume();
        if (editor) return;
        // onActivityResult 已经刷过就不要再来一次：两次滚动 post 会互相覆盖（回顶被"保留旧位置"盖掉）
        if (listedOnce && !resultRefreshed) refreshList();
        resultRefreshed = false;
        listedOnce = true;
    }

    @Override public void onBackPressed() {
        if (editor) { if(previewing)togglePreview();else saveAndFinish(); return; }   // 返回键也算一次保存
        // 搜索开着时先收起搜索，别让一次返回直接退出整个备忘录
        if (searchOpen) { closeSearch(); return; }
        super.onBackPressed();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(result==RESULT_OK&&request==REQ_SKETCH&&data!=null&&data.hasExtra("link")){insertText("\n"+data.getStringExtra("link")+"\n");return;}
        if(result==RESULT_OK&&data!=null&&data.getData()!=null&&(request==REQ_BACKUP||request==REQ_RESTORE)){
            final android.net.Uri uri=data.getData();new Thread(()->{
                try{if(request==REQ_BACKUP){String json=MemoBackup.encode(MemoStore.all(this),MemoStore.trash(this),MemoLibrary.exportJson(this));try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException(T.s("无法写入备份"));MemoArchive.write(this,out,json);}runOnUiThread(()->message(T.s("完整备份已导出")));}
                    else{final MemoArchive.Data archive;try(java.io.InputStream in=getContentResolver().openInputStream(uri)){archive=MemoArchive.read(this,in);}MemoLibrary.validate(archive.backup.library);
                        runOnUiThread(()->{if(isFinishing()||isDestroyed()){try{archive.close();}catch(Exception ignored){}return;}
                            final boolean[] restoring={false};android.app.AlertDialog dialog=ThemeStore.dialog(this).setTitle(T.s("合并恢复备忘录？")).setMessage(T.s("备份有 ")+archive.backup.notes.size()+T.s(" 条笔记和 ")+archive.backup.trash.size()+T.s(" 条回收站记录。现有内容保留，冲突记录另存副本。"))
                                .setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("合并恢复"),(d,w)->{restoring[0]=true;new Thread(()->{try{
                                    synchronized(MemoStore.class){MemoBackup.Data merged=MemoBackup.merge(MemoStore.all(this),MemoStore.trash(this),archive.backup);
                                        org.json.JSONObject library=remapLibrary(archive.backup.library,merged.idMap);MemoLibrary.merge(MemoLibrary.load(this),MemoLibrary.validate(library));
                                        MemoArchive.applyAttachments(this,archive);MemoBackup.Data restored=MemoBackup.restore(this,archive.backup);MemoLibrary.mergeJson(this,remapLibrary(archive.backup.library,restored.idMap));}
                                    runOnUiThread(()->{if(!isDestroyed()){notebookFilter="*";refreshList(true);message(T.s("备份已合并恢复"));}});
                                }catch(Exception e){runOnUiThread(()->message(T.s("恢复失败：")+e.getMessage()));}finally{try{archive.close();}catch(Exception ignored){}}},"memo-restore").start();}).create();
                            dialog.setOnDismissListener(d->{if(!restoring[0])try{archive.close();}catch(Exception ignored){}});dialog.show();});
                    }
                }catch(Exception e){runOnUiThread(()->message(T.s("备份操作失败：")+e.getMessage()));}
            },"memo-backup").start();return;
        }
        if(result==RESULT_OK&&request==REQ_VOICE&&data!=null){java.util.ArrayList<String> words=data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);if(words!=null&&!words.isEmpty())insertText(words.get(0));return;}
        if(result==RESULT_OK&&request==REQ_ATTACH&&data!=null&&data.getData()!=null){final android.net.Uri uri=data.getData();new Thread(()->{
            try{String link=MemoFiles.add(this,uri);runOnUiThread(()->{if(!isFinishing())insertText("\n"+link+"\n");});}catch(Exception e){runOnUiThread(()->message(e.getMessage()));}
        },"memo-attachment").start();return;}
        if(result==RESULT_OK&&data!=null&&data.getData()!=null&&request==REQ_IMPORT){
            final android.net.Uri uri=data.getData();
            new Thread(()->{
                try {
                    String content=MemoEditor.readText(getContentResolver().openInputStream(uri));
                    if(content.trim().isEmpty())throw new java.io.IOException(T.s("文件是空的"));
                    runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())startActivityForResult(new Intent(this,MemoActivity.class)
                            .putExtra("new",true).putExtra("importBody",content),REQ_EDITOR);});
                }catch(Exception e){runOnUiThread(()->message(T.s("导入失败：请使用不超过 2 万字的 UTF-8 文本或 Markdown 文件")));}
            },"memo-import").start();return;
        }
        if(result==RESULT_OK&&data!=null&&data.getData()!=null&&request==REQ_EXPORT){
            final android.net.Uri uri=data.getData();final String output=exportText;
            new Thread(()->{
                try(java.io.OutputStream stream=getContentResolver().openOutputStream(uri,"wt")){
                    if(stream==null)throw new java.io.IOException();stream.write(output.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    runOnUiThread(()->message(T.s("已导出")));
                }catch(Exception e){runOnUiThread(()->message(T.s("导出失败，请重试")));}
            },"memo-export").start();return;
        }
        if (request != REQ_EDITOR || result != RESULT_OK || data == null) return;
        boolean created = data.getBooleanExtra("created", false);
        boolean changed = data.getBooleanExtra("changed", false);
        if (created) {
            // 搜索词还挂着时，刚新建的条目会被过滤掉，看起来像没保存：收起搜索
            if (searchOpen) closeSearch(); else refreshList(true);
            resultRefreshed = true;
        } else if (changed) {
            // 改过的条目会按新时间排到前面，回顶才看得见
            refreshList(true);
            resultRefreshed = true;
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("bookFilter",notebookFilter);out.putString("draftBook",draftBook);out.putString("draftPaper",draftPaper);
        out.putString("exportText",exportText);
        if (editor) {
            out.putLong("draftRemindAt",draftRemindAt);
            if (titleInput != null) out.putString("draftTitle", titleInput.getText().toString());
            if (bodyInput != null) out.putString("draftBody", bodyInput.getText().toString());
        } else {
            out.putString("query", query);
            out.putBoolean("search", searchOpen);
            if (listScroll != null) out.putInt("scroll", listScroll.getScrollY());
        }
        super.onSaveInstanceState(out);
    }

    /* ==================== 列表页 ==================== */

    private void listPage(Bundle state) {
        cancelAiUi();
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        ui.page(root);   // 高德风格下带淡蓝页眉渐变；经典风格就是纯底色
        setContentView(root);

        LinearLayout bar = row();
        bar.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        root.addView(bar);
        bar.addView(glyphButton(T.s("返回"), T.s("返回"), () -> finish()), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        TextView heading = SwissTypography.role(text(T.s("备忘录"), 28, ui.text()), SwissTypography.Role.TITLE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, -2, 1);
        hlp.leftMargin = ui.dp(12);
        bar.addView(heading, hlp);
        bar.addView(plusButton(), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
        slp.leftMargin = ui.dp(4);
        bar.addView(glyphButton(T.s("搜索"), T.s("搜索备忘录"), this::toggleSearch), slp);

        LinearLayout options=row();options.setPadding(ui.dp(20),0,ui.dp(20),ui.dp(8));
        android.widget.HorizontalScrollView optionScroll=new android.widget.HorizontalScrollView(this);optionScroll.setHorizontalScrollBarEnabled(true);optionScroll.setScrollbarFadingEnabled(false);optionScroll.setScrollBarSize(ui.dp(3));
        if(android.os.Build.VERSION.SDK_INT>=29){optionScroll.setHorizontalScrollbarThumbDrawable(ui.round((ui.accent()&0xffffff)|0x66000000,3));optionScroll.setHorizontalScrollbarTrackDrawable(ui.round(ui.line(),3));}
        optionScroll.addView(options);root.addView(optionScroll);
        optionChip(options,T.s("笔记本"),()->chooseBook(false));optionChip(options,T.s("列表/网格"),()->{MemoLibrary.setGrid(this,!MemoLibrary.grid(this));refreshList();});
        optionChip(options,T.s("备份与恢复"),this::backupMenu);optionChip(options,T.s("导入文件"),this::importFile);optionChip(options,T.s("回收站"),this::showTrash);optionChip(options,T.s("开源说明"),this::sources);
        notebookTabs=new MemoNotebookTabs(this,id->{notebookFilter=id;refreshList(true);});
        LinearLayout.LayoutParams tabsLp=new LinearLayout.LayoutParams(-1,ui.dp(48));tabsLp.setMargins(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(12));root.addView(notebookTabs,tabsLp);

        countLabel = text(T.s("共 0 条"), 12, ui.text2());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        root.addView(countLabel, clp);

        search = new EditText(this);
        search.setHint(T.s("搜索备忘录"));
        search.setSingleLine();
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setTextColor(ui.text());
        search.setHintTextColor(ui.text3());
        search.setBackground(ui.round(ui.surface(), 16));
        search.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        search.setOnEditorActionListener((v, action, event) -> { hideKeyboard(search); return true; });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (bindingSearch) return;
                query = s == null ? "" : s.toString();
                refreshList(true);   // 换了搜索词，结果从头看
            }
            public void afterTextChanged(Editable e) { }
        });
        LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(-1, -2);
        sep.setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        root.addView(search, sep);
        search.setVisibility(View.GONE);

        listScroll = new ScrollView(this);
        listScroll.setClipToPadding(false);
        root.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        listBody = column();
        listBody.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(32));
        listScroll.addView(listBody);

        if (state != null) {
            String savedQuery = state.getString("query", "");
            searchOpen = state.getBoolean("search", false) || !savedQuery.isEmpty();
            if (searchOpen) {
                bindingSearch = true;
                search.setText(savedQuery);
                search.setVisibility(View.VISIBLE);
                bindingSearch = false;
            }
            query = savedQuery;
        }
        refreshList();
        if (state != null) {
            final int y = state.getInt("scroll", 0);
            listScroll.post(() -> listScroll.scrollTo(0, y));
        }
    }

    /** 重新读取并整体重建：列表量小，不做局部更新。默认保留阅读位置。 */
    private void refreshList() {
        refreshList(false);
    }

    /**
     * @param toTop 新建/改过/置顶/搜索后回顶（那些条目在列表前面）；单纯返回列表时保留位置，
     *              否则"点开看一眼再回来"会把用户读到一半的位置打掉。
     */
    private void refreshList(boolean toTop) {
        if (listBody == null || countLabel == null) return;
        int keep = listScroll == null ? 0 : listScroll.getScrollY();
        boolean searching = !query.trim().isEmpty();
        MemoLibrary.Model library=MemoLibrary.load(this);
        boolean validBook="*".equals(notebookFilter)||"".equals(notebookFilter);for(MemoLibrary.Book book:library.notebooks)if(book.id.equals(notebookFilter))validBook=true;
        if(!validBook)notebookFilter="*";
        if(notebookTabs!=null)notebookTabs.bind(library,notebookFilter);
        notes = searching ? MemoStore.search(this, query) : MemoStore.all(this);
        if(!"*".equals(notebookFilter)){java.util.List<MemoStore.Note> filtered=new java.util.ArrayList<>();for(MemoStore.Note note:notes)if(notebookFilter.equals(MemoLibrary.notebookOf(library,note.id)))filtered.add(note);notes=filtered;}
        listBody.removeAllViews();
        // 空列表时不再单写一行「还没有备忘录」：空状态卡已经说了，重复一遍只是噪音
        countLabel.setText(T.s("共 ") + notes.size() + T.s(" 条"));
        countLabel.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
        if (notes.isEmpty()) emptyCard(searching);
        else if(!library.grid)for (MemoStore.Note note : notes) listBody.addView(card(note,library), ui.gap(10));
        else for(int i=0;i<notes.size();i+=2){LinearLayout line=row();line.setGravity(Gravity.TOP);for(int j=i;j<Math.min(i+2,notes.size());j++){LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,-2,1);if(j>i)cell.leftMargin=ui.dp(10);line.addView(card(notes.get(j),library),cell);}if(i+1==notes.size())line.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));listBody.addView(line,ui.gap(10));}
        // 新加的卡片立刻套上主题字体/行距，不等下一轮全局布局（否则会先闪一帧默认字体）
        ThemeStore.typography(listBody);
        // 重建后子布局还没测量：post 一帧再滚，删除让列表变短时不会被旧的 y 卡在半空。
        // 转屏恢复的滚动位置在 listPage 里随后再 post，最终以它为准。
        if (listScroll != null) {
            final int target = toTop ? 0 : keep;
            listScroll.post(() -> listScroll.scrollTo(0, target));
        }
    }

    private void emptyCard(boolean searching) {
        LinearLayout card = ui.card();
        card.setGravity(Gravity.CENTER);
        card.setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(28));
        TextView title = text(searching ? T.s("没有找到备忘录") : T.s("还没有备忘录"), 17, ui.text());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));   // 不铺满会把居中变成左对齐
        TextView hint = text(searching ? T.s("换个词试试，再点 ⌕ 收起搜索") : T.s("点右上角 ＋ 写第一条"), 12, ui.text2());
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = ui.dp(8);
        card.addView(hint, hlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ui.dp(12);
        listBody.addView(card, lp);
    }

    private View card(MemoStore.Note raw,MemoLibrary.Model library) {
        final MemoStore.Note note = shown(raw);   // 兜一次 null：展示层不做空判断
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(ui.round(paperColor(MemoLibrary.paperOf(library,note.id)), 18));
        box.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));

        LinearLayout titleRow = row();
        TextView title = text(MemoStore.displayTitle(note), 17, ui.text());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (note.pinned) {
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
            chipLp.leftMargin = ui.dp(8);
            titleRow.addView(ui.chip(T.s("置顶"), ui.accent(), ui.surface2()), chipLp);
        }
        box.addView(titleRow);

        String summary = MemoStore.excerpt(note.body).replaceAll("(?m)[-*+] \\[ \\] ","○ ").replaceAll("(?m)[-*+] \\[[xX]\\] ","✓ ");
        if (summary != null && !summary.isEmpty()) {
            TextView excerpt = text(summary, 13, ui.text2());
            excerpt.setMaxLines(2);
            excerpt.setEllipsize(TextUtils.TruncateAt.END);
            excerpt.setLineSpacing(ui.dp(3), 1f);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
            elp.topMargin = ui.dp(6);
            box.addView(excerpt, elp);
        }

        final String id = note.id;
        final boolean pinned = note.pinned;
        final String label = MemoStore.displayTitle(note);

        LinearLayout foot = row();
        TextView time = text(MemoStore.formatTime(note.updated, System.currentTimeMillis()), 11, ui.text3());
        foot.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        List<MemoChecklist.Item> tasks=MemoChecklist.items(note.body);
        if(!tasks.isEmpty()){
            int doneCount=0;for(MemoChecklist.Item task:tasks)if(task.checked)doneCount++;
            TextView progress=text(T.s("待办 ")+doneCount+"/"+tasks.size(),11,ui.text2());
            progress.setPadding(0,0,ui.dp(8),0);foot.addView(progress);
        }
        if(note.remindAt>0){TextView reminder=text(T.s("提醒 ")+reminderTime(note.remindAt),11,ui.text3());box.addView(reminder);}
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = ui.dp(4);
        box.addView(foot, flp);

        box.setOnClickListener(v -> startActivityForResult(
                new Intent(this, MemoActivity.class).putExtra("id", id), REQ_EDITOR));
        box.setOnLongClickListener(v -> { menu(v, id, pinned, label); return true; });
        return box;
    }

    private void menu(View anchor, final String id, final boolean pinned, final String label) {
        if(cardMenu!=null)cardMenu.dismiss();
        LinearLayout panel=row();panel.setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8));panel.setBackground(ui.round(ui.surface(),26));panel.setClipToOutline(true);
        PopupWindow popup=new PopupWindow(panel,ui.dp(136),ui.dp(72),true);cardMenu=popup;
        popup.setBackgroundDrawable(ui.round(ui.surface(),26));popup.setElevation(ui.dp(8));popup.setOutsideTouchable(true);
        popup.setOnDismissListener(()->{if(cardMenu==popup)cardMenu=null;});
        MemoToolButton pin=new MemoToolButton(this,pinned?T.s("取消置顶"):T.s("置顶"));pin.setIconSize(24);pin.setIconColor(pinned?ui.accent():ui.text());
        MemoToolButton remove=new MemoToolButton(this,T.s("删除"));remove.setIconSize(24);remove.setIconColor(Ui.RED);
        for(MemoToolButton button:new MemoToolButton[]{pin,remove}){button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ui.line()),ui.round(ui.surface2(),20),ui.round(0xffffffff,20)));button.setClipToOutline(true);panel.addView(button,new LinearLayout.LayoutParams(ui.dp(56),ui.dp(56)));}
        ((LinearLayout.LayoutParams)remove.getLayoutParams()).leftMargin=ui.dp(8);
        pin.setOnClickListener(v->{try{MemoStore.setPinned(this,id,!pinned);popup.dismiss();refreshList(true);}catch(RuntimeException e){message(e.getMessage());}});
        remove.setOnClickListener(v->{popup.dismiss();confirmDelete(id,label,this::refreshList);});
        int[] location=new int[2];anchor.getLocationOnScreen(location);android.graphics.Rect visible=new android.graphics.Rect();anchor.getWindowVisibleDisplayFrame(visible);
        int margin=ui.dp(12),width=ui.dp(136),height=ui.dp(72);
        int x=Math.max(visible.left+margin,Math.min(location[0]+(anchor.getWidth()-width)/2,visible.right-margin-width));
        int y=location[1]-height-ui.dp(8);if(y<visible.top+margin)y=location[1]+anchor.getHeight()+ui.dp(8);
        y=Math.max(visible.top+margin,Math.min(y,visible.bottom-margin-height));
        popup.showAtLocation(anchor,Gravity.TOP|Gravity.LEFT,x,y);
    }

    private void confirmDelete(final String id, String label, final Runnable after) {
        ThemeStore.dialog(this)
                .setTitle(T.s("移入回收站？"))
                .setMessage(label == null || label.isEmpty() ? T.s("无标题") : label)
                .setNegativeButton(T.s("取消"), null)
                .setPositiveButton(T.s("移入回收站"), (dialog, which) -> {
                    try {MemoStore.remove(this, id);
                    if (after != null) after.run();}catch(RuntimeException e){message(e.getMessage());}
                })
                .show();
    }

    private void toggleSearch() {
        if (searchOpen) closeSearch(); else openSearch();
    }

    private void openSearch() {
        searchOpen = true;
        search.setVisibility(View.VISIBLE);
        search.requestFocus();
        search.post(() -> {
            search.setSelection(search.getText().length());
            showKeyboard(search);
        });
    }

    private void closeSearch() {
        if (!searchOpen) return;
        hideKeyboard(search);
        searchOpen = false;
        bindingSearch = true;
        search.setText("");
        bindingSearch = false;
        search.setVisibility(View.GONE);
        query = "";
        refreshList(true);   // 收起搜索回到全量列表，从顶部开始
    }

    /* ==================== 编辑页 ==================== */

    private void editorPage(Bundle state) {
        cancelAiUi();
        final boolean creating = entry == null;
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        ui.page(root);
        setContentView(root);
        avoidKeyboard(root);

        LinearLayout bar = row();
        bar.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        root.addView(bar);
        bar.addView(glyphButton(T.s("返回"), T.s("返回"), this::saveAndFinish), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        bar.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        // Top actions use equal touch targets and compact vector icons.
        if (entry != null) {
            MemoToolButton trash = new MemoToolButton(this,T.s("删除"));trash.setIconSize(22);trash.setIconColor(ui.text3());trash.setMinHeight(ui.dp(44));
            trash.setContentDescription(T.s("删除这条备忘录"));
            trash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            trash.setOnClickListener(v -> confirmDelete(entryId, currentLabel(), () -> {
                savedOnce = true;
                setResult(RESULT_OK, new Intent());
                finish();
            }));
            LinearLayout.LayoutParams trashLp = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
            trashLp.rightMargin = ui.dp(4);
            bar.addView(trash, trashLp);
        }
        MemoToolButton done = new MemoToolButton(this,T.s("完成"));done.setIconSize(22);done.setMinHeight(ui.dp(44));
        done.setContentDescription(T.s("保存"));
        done.setOnClickListener(v -> saveAndFinish());
        bar.addView(done, new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));

        titleInput = new EditText(this);
        titleInput.setSingleLine(true);
        titleInput.setHint(T.s("标题"));
        titleInput.setTextSize(20);
        titleInput.setTypeface(Typeface.DEFAULT_BOLD);
        titleInput.setTextColor(ui.text());
        titleInput.setHintTextColor(ui.text3());
        titleInput.setBackground(null);
        titleInput.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14));
        // 输入时就守住上限，别等到保存才悄悄截断（超大草稿还会撑爆转屏的 Bundle）
        titleInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MemoStore.MAX_TITLE)});
        SwissTypography.role(titleInput, SwissTypography.Role.KEEP);
        root.addView(titleInput, new LinearLayout.LayoutParams(-1, -2));

        bodyInput = new MemoChecklistInput(this);
        bodyInput.setHint(T.s("写点什么…"));
        bodyInput.setTextSize(16);
        bodyInput.setGravity(Gravity.TOP | Gravity.START);
        bodyInput.setLineSpacing(ui.dp(6), 1f);
        bodyInput.setTextColor(ui.text());
        bodyInput.setHintTextColor(ui.text3());
        bodyInput.setBackground(null);
        bodyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        bodyInput.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(20));
        bodyInput.setVerticalScrollBarEnabled(true);
        bodyInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MemoStore.MAX_BODY)});
        bodyInput.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback(){
            public boolean onCreateActionMode(android.view.ActionMode mode,android.view.Menu menu){
                menu.add(0,0xAF01,20,T.s("高亮 / 取消高亮"));menu.add(0,0xAF02,21,T.s("块编辑"));return true;
            }
            public boolean onPrepareActionMode(android.view.ActionMode mode,android.view.Menu menu){return false;}
            public boolean onActionItemClicked(android.view.ActionMode mode,android.view.MenuItem item){
                int a=bodyInput.getSelectionStart(),b=bodyInput.getSelectionEnd();
                if(item.getItemId()==0xAF01){mode.finish();highlight(a,b);return true;}
                if(item.getItemId()==0xAF02){mode.finish();bodyInput.setSelection(Math.max(0,a));blockMenu();return true;}return false;
            }
            public void onDestroyActionMode(android.view.ActionMode mode){}
        });
        SwissTypography.role(bodyInput, SwissTypography.Role.KEEP);
        root.addView(bodyInput, new LinearLayout.LayoutParams(-1, 0, 1));
        previewScroll=new ScrollView(this);previewText=text("",16,ui.text());
        previewText.setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(20));previewText.setTextIsSelectable(true);
        SwissTypography.role(previewText,SwissTypography.Role.KEEP);previewText.setLineSpacing(ui.dp(6),1);
        previewScroll.addView(previewText);previewScroll.setVisibility(View.GONE);
        root.addView(previewScroll,new LinearLayout.LayoutParams(-1,0,1));
        editorStatus=text("",12,ui.text3());editorStatus.setPadding(ui.dp(20),0,ui.dp(20),ui.dp(4));root.addView(editorStatus);
        LinearLayout actions=row();actions.setPadding(ui.dp(12),ui.dp(4),ui.dp(12),ui.dp(12));root.addView(actions);
        undoButton=iconTool(actions,T.s("撤销"),()->travel(false));redoButton=iconTool(actions,T.s("重做"),()->travel(true));
        iconTool(actions,T.s("待办"),()->{if(previewing)togglePreview();bodyInput.checklist();bodyInput.requestFocus();showKeyboard(bodyInput);});
        iconTool(actions,T.s("管理"),this::taskManager);
        previewButton=iconTool(actions,T.s("预览"),this::togglePreview);iconTool(actions,T.s("更多"),this::editorMore);
        applyPaper();

        String title = entry == null ? "" : entry.title;
        String body = entry == null ? getIntent().getStringExtra("importBody") : entry.body;
        if(body==null)body="";
        if (state != null) {
            title = state.getString("draftTitle", title);
            body = state.getString("draftBody", body);
        }
        draftRemindAt=state!=null?state.getLong("draftRemindAt",entry==null?0:entry.remindAt):(entry==null?0:entry.remindAt);
        titleInput.setText(title);
        bodyInput.setText(body);
        history=new MemoEditor.History(snapshot());
        // 有序列表回车续号：判定放在 afterTextChanged 里看「光标前面是不是刚落下的换行」。
        // 以前靠 onTextChanged 精确捕捉「插入了一个 \n」，真机（ColorOS 拼音键盘 + KEYCODE_ENTER）
        // 的事件序不保证 s 里立刻可见那个 \n，判定全落空。光标方案对事件序免疫：
        // 换行插入后光标必然紧跟在 \n 后面，删除内容贴着 \n 的情形 continueList 也会返回 null。
        bodyInput.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(Editable e){
                if(listContinuing)return;
                int cursor=bodyInput.getSelectionEnd();
                if(cursor<=0||cursor>e.length()||e.charAt(cursor-1)!='\n')return;
                MemoTextFormat.Change change=MemoTextFormat.continueList(e.toString(),cursor-1);
                if(change==null)return;
                listContinuing=true;
                try{
                    int next=Math.min(change.cursor,change.body.length());
                    e.replace(0,e.length(),change.body);
                    bodyInput.setSelection(Math.min(next,bodyInput.length()));
                }finally{listContinuing=false;}
            }
        });
        TextWatcher watch=new TextWatcher(){public void beforeTextChanged(CharSequence t,int a,int c,int n){}public void onTextChanged(CharSequence t,int a,int b,int c){
            if(historyBinding)return;edits.removeCallbacks(remember);edits.postDelayed(remember,450);editorStatus.setText(T.s("编辑中 · ")+bodyInput.length()+T.s(" 字"));
        }public void afterTextChanged(Editable e){}};
        titleInput.addTextChangedListener(watch);bodyInput.addTextChangedListener(watch);
        bodyInput.actions(()->{edits.removeCallbacks(remember);history.record(snapshot());},()->{
            edits.removeCallbacks(remember);history.record(snapshot());updateHistory();if(previewing)renderPreview();
        });updateHistory();
        final int cursor = bodyInput.getText().length();
        bodyInput.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            bodyInput.requestFocus();
            bodyInput.setSelection(Math.min(cursor, bodyInput.getText().length()));
            if (creating) showKeyboard(bodyInput);
        });
    }

    /** FLAG_FULLSCREEN suppresses adjustResize on some phones; reserve the actual IME inset. */
    private void avoidKeyboard(LinearLayout root){
        keyboardRoot=root;final int top=root.getPaddingTop();
        if(android.os.Build.VERSION.SDK_INT>=30){
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            root.setOnApplyWindowInsetsListener((view,insets)->{
                int keyboard=insets.isVisible(android.view.WindowInsets.Type.ime())?insets.getInsets(android.view.WindowInsets.Type.ime()).bottom:0;
                int system=insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                int bottom=Math.max(0,keyboard-system);
                if(view.getPaddingBottom()!=bottom)view.setPadding(0,top,0,bottom);
                return insets;
            });root.requestApplyInsets();
        }else{
            legacyKeyboard=()->{
                android.graphics.Rect visible=new android.graphics.Rect();root.getWindowVisibleDisplayFrame(visible);
                int[] location=new int[2];root.getLocationOnScreen(location);
                int overlap=Math.max(0,location[1]+root.getHeight()-visible.bottom);
                int bottom=overlap>ui.dp(100)?overlap:0;
                if(root.getPaddingBottom()!=bottom)root.setPadding(0,top,0,bottom);
            };root.getViewTreeObserver().addOnGlobalLayoutListener(legacyKeyboard);
        }
    }

    /** 当前输入（未保存）的展示标题：删除确认里给用户看是哪条。 */
    private String currentLabel() {
        String title = titleInput == null ? "" : titleInput.getText().toString();
        String body = bodyInput == null ? "" : bodyInput.getText().toString();
        long updated = entry == null ? 0 : entry.updated;
        return MemoStore.displayTitle(new MemoStore.Note(entryId, title, body, updated, false));
    }

    /** 保存一次并关闭：点「完成」、顶栏返回、系统返回键都走这里，靠 savedOnce 保证幂等。 */
    private void saveAndFinish() {
        if (savedOnce) { finish(); return; }
        String title = titleInput == null ? "" : titleInput.getText().toString();
        String body = bodyInput == null ? "" : bodyInput.getText().toString();
        boolean created = false;
        boolean changed = false;
        // 全空（含全角空格）：新建不落盘，已有条目不覆盖原数据。
        if(draftRemindAt>0&&MemoStore.blank(title,body)){message(T.s("先写下备忘录内容，再保存提醒"));return;}
        if(draftRemindAt>0&&(entry==null||entry.remindAt!=draftRemindAt)&&draftRemindAt<=System.currentTimeMillis()){
            message(T.s("提醒时间已过，请重新设置或取消提醒"));return;
        }
        try { if (entry!=null||!MemoStore.blank(title, body)) {
            MemoStore.Note saved=MemoStore.saveDraft(this,entryId,title,body,draftRemindAt);
            if(saved!=null){entryId=saved.id;MemoLibrary.setNotebook(this,saved.id,draftBook);MemoLibrary.setPaper(this,saved.id,draftPaper);}
            created=entry==null&&saved!=null;
            changed=entry!=null&&saved!=null&&(!entry.title.equals(saved.title)||!entry.body.equals(saved.body)||entry.remindAt!=saved.remindAt);
        }
        }catch(RuntimeException e){message(e.getMessage());return;}
        savedOnce=true;
        Intent result = new Intent();
        result.putExtra("created", created);
        result.putExtra("changed", changed);   // 没改内容 → false，列表别回顶、别当"刚保存"
        setResult(RESULT_OK, result);
        finish();
    }

    private TextView tool(LinearLayout row,String label,Runnable action){
        TextView t=text(label,14,ui.accent());t.setGravity(Gravity.CENTER);t.setMinHeight(ui.dp(44));
        t.setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8));SwissTypography.role(t,SwissTypography.Role.BUTTON);
        row.addView(t,new LinearLayout.LayoutParams(-2,-2));t.setOnClickListener(v->action.run());return t;
    }
    private void optionChip(LinearLayout row,String label,Runnable action){
        TextView chip=text(label,13,ui.text2());chip.setGravity(Gravity.CENTER);chip.setPadding(ui.dp(16),0,ui.dp(16),0);
        chip.setBackground(ui.round(ui.surface(),16));chip.setOnClickListener(v->action.run());chip.setFocusable(true);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,ui.dp(44));lp.rightMargin=ui.dp(8);row.addView(chip,lp);
    }
    private TextView iconTool(LinearLayout row,String label,Runnable action){MemoToolButton button=new MemoToolButton(this,label);row.addView(button,new LinearLayout.LayoutParams(0,ui.dp(48),1));button.setOnClickListener(v->action.run());return button;}
    private void message(String text){android.widget.Toast.makeText(this,text==null?T.s("操作未完成"):text,android.widget.Toast.LENGTH_LONG).show();}
    private MemoEditor.State snapshot(){return new MemoEditor.State(titleInput.getText().toString(),bodyInput.getText().toString());}
    private void updateHistory(){
        undoButton.setEnabled(history.canUndo());redoButton.setEnabled(history.canRedo());
        undoButton.setAlpha(history.canUndo()?1f:.35f);redoButton.setAlpha(history.canRedo()?1f:.35f);
        List<MemoChecklist.Item> tasks=MemoChecklist.items(bodyInput.getText().toString());int completed=0;
        for(MemoChecklist.Item item:tasks)if(item.checked)completed++;
        editorStatus.setText((tasks.isEmpty()?"":(T.s("待办 ")+completed+"/"+tasks.size()+" · "))+bodyInput.length()+T.s(" 字 · 返回或完成时保存")
                +(draftRemindAt>0?T.s("\n提醒 ")+reminderTime(draftRemindAt)+(MemoReminder.allowed(this)?"":T.s(" · 尚未允许通知")):""));
    }
    private void travel(boolean redo){
        edits.removeCallbacks(remember);history.record(snapshot());
        MemoEditor.State value=redo?history.redo():history.undo();historyBinding=true;
        titleInput.setText(value.title);bodyInput.setText(value.body);bodyInput.setSelection(bodyInput.length());historyBinding=false;
        updateHistory();if(previewing)renderPreview();
    }
    private void format(String marker,boolean wrap){
        if(previewing)togglePreview();edits.removeCallbacks(remember);history.record(snapshot());
        int start=Math.max(0,Math.min(bodyInput.getSelectionStart(),bodyInput.getSelectionEnd()));
        int end=Math.max(start,Math.max(bodyInput.getSelectionStart(),bodyInput.getSelectionEnd()));
        String value=bodyInput.getText().toString();
        String replacement;
        if(wrap)replacement=marker+value.substring(start,end)+marker;
        else {start=value.lastIndexOf('\n',Math.max(-1,start-1))+1;replacement=marker+value.substring(start,end).replace("\n","\n"+marker);}
        if(value.length()-(end-start)+replacement.length()>MemoStore.MAX_BODY){message(T.s("正文已达到 2 万字上限"));return;}
        bodyInput.getText().replace(start,end,replacement);bodyInput.setSelection(start+replacement.length()-(wrap?marker.length():0));
        edits.removeCallbacks(remember);history.record(snapshot());updateHistory();bodyInput.requestFocus();
    }
    private void renderPreview(){
        String source=bodyInput.getText().toString();
        String prefix="afs-task:"+java.util.UUID.randomUUID()+":";
        android.text.SpannableStringBuilder styled=new android.text.SpannableStringBuilder(MemoTextFormat.previewRich(
                MemoEditor.preview(source,ui.text(),prefix)));
        List<MemoChecklist.Item> tasks=MemoChecklist.items(source);
        for(android.text.style.URLSpan link:styled.getSpans(0,styled.length(),android.text.style.URLSpan.class)){
            int position=styled.getSpanStart(link),spanEnd=styled.getSpanEnd(link);styled.removeSpan(link);
            if(link.getURL().startsWith("afs-file:")){
                final String id=link.getURL().substring(9);if(MemoFiles.validId(id))styled.setSpan(new android.text.style.ClickableSpan(){@Override public void onClick(View view){MemoFiles.open(MemoActivity.this,id);}},position,spanEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);continue;
            }
            if(!link.getURL().startsWith(prefix))continue;
            int index;try{index=Integer.parseInt(link.getURL().substring(prefix.length()));}catch(NumberFormatException e){continue;}
            if(index<0||index>=tasks.size())continue;MemoChecklist.Item task=tasks.get(index);
            int end=styled.toString().indexOf('\n',position);if(end<0)end=styled.length();
            if(task.checked)styled.setSpan(new android.text.style.ForegroundColorSpan(ui.text3()),position,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(bodyInput.new Box(task.checked),position,spanEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            final int offset=task.marker;
            styled.setSpan(new android.text.style.ClickableSpan(){
                @Override public void onClick(View view){bodyInput.toggle(offset);}
                @Override public void updateDrawState(android.text.TextPaint paint){paint.setColor(ui.accent());paint.setUnderlineText(false);}
            },position,spanEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        previewText.setText(styled);previewText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    }
    private void togglePreview(){
        previewing=!previewing;if(previewing){hideKeyboard(bodyInput);renderPreview();}
        bodyInput.setVisibility(previewing?View.GONE:View.VISIBLE);previewScroll.setVisibility(previewing?View.VISIBLE:View.GONE);
        previewButton.setText(previewing?T.s("编辑"):T.s("预览"));
        previewButton.setContentDescription(previewing?T.s("编辑"):T.s("预览"));
    }
    private void importFile(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*");
        try{startActivityForResult(intent,REQ_IMPORT);}catch(android.content.ActivityNotFoundException e){message(T.s("没有可用的文件选择器"));}
    }
    private String reminderTime(long time){return new java.text.SimpleDateFormat("MM/dd HH:mm",java.util.Locale.CHINA).format(new java.util.Date(time));}
    private void editorMore(){
        String[] labels={T.s("待办管理"),T.s("文内查找"),T.s("设置提醒"),T.s("分享文本"),T.s("导出文件"),T.s("标题"),T.s("粗体"),T.s("斜体"),T.s("删除线"),T.s("通知设置"),T.s("插入图片或附件"),T.s("语音输入"),T.s("移动到笔记本"),T.s("纸张背景"),T.s("格式面板"),T.s("AI 写作"),T.s("表格"),T.s("涂鸦"),T.s("高亮 / 取消高亮"),T.s("块编辑")};
        ThemeStore.dialog(this).setTitle(T.s("备忘录工具")).setItems(labels,(d,i)->{
            switch(i){case 0:taskManager();break;case 1:findText();break;case 2:reminder();break;case 3:shareText();break;case 4:exportFile();break;
                case 5:format("# ",false);break;case 6:format("**",true);break;case 7:format("*",true);break;case 8:format("~~",true);break;
                case 9:try{
                    Intent settings=android.os.Build.VERSION.SDK_INT>=26?new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())
                            :new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()));startActivity(settings);
                }catch(android.content.ActivityNotFoundException e){message(T.s("请在系统设置中打开本应用的通知权限"));}break;
                case 10:try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),REQ_ATTACH);}catch(android.content.ActivityNotFoundException e){message(T.s("没有可用的文件选择器"));}break;
                case 11:try{startActivityForResult(new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE,"zh-CN"),REQ_VOICE);}catch(android.content.ActivityNotFoundException e){message(T.s("当前系统未提供语音识别服务"));}break;
                case 12:chooseBook(true);break;case 13:paperMenu();break;case 14:formatMenu();break;case 15:aiMenu();break;case 16:tableEditor();break;case 17:startActivityForResult(new Intent(this,MemoSketchActivity.class),REQ_SKETCH);break;
                case 18:highlight(bodyInput.getSelectionStart(),bodyInput.getSelectionEnd());break;case 19:blockMenu();break;}
        }).setNegativeButton(T.s("关闭"),null).show();
    }
    private void insertText(String value){
        if(bodyInput==null)return;if(previewing)togglePreview();int start=Math.max(0,bodyInput.getSelectionStart()),end=Math.max(start,bodyInput.getSelectionEnd());
        bodyInput.apply(new MemoChecklist.Change(bodyInput.getText().toString().substring(0,start)+value+bodyInput.getText().toString().substring(end),start+value.length()));
    }
    private void tableEditor(){
        final String original=bodyInput.getText().toString();final int start=Math.max(0,bodyInput.getSelectionStart()),end=Math.max(start,bodyInput.getSelectionEnd());
        MemoTable.Model initial;try{initial=end>start?MemoTable.parse(original.substring(start,end)):MemoTable.create(2,2);}catch(IllegalArgumentException e){message(T.s("选中完整表格可编辑；取消选区可新建表格"));return;}
        final MemoTable.Model[] model={initial};final java.util.List<EditText> fields=new java.util.ArrayList<>();LinearLayout outer=column();outer.setPadding(ui.dp(12),0,ui.dp(12),0);LinearLayout controls=row();outer.addView(controls);
        android.widget.HorizontalScrollView horizontal=new android.widget.HorizontalScrollView(this);LinearLayout grid=column();horizontal.addView(grid);ScrollView vertical=new ScrollView(this);vertical.addView(horizontal);outer.addView(vertical,new LinearLayout.LayoutParams(-1,ui.dp(260)));
        Runnable render=()->{grid.removeAllViews();fields.clear();for(java.util.List<String> cells:model[0].cells){LinearLayout line=row();grid.addView(line);for(String value:cells){EditText field=new EditText(this);field.setText(value);field.setTextColor(ui.text());field.setSingleLine();field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});line.addView(field,new LinearLayout.LayoutParams(ui.dp(120),ui.dp(48)));fields.add(field);}}};
        Runnable read=()->{MemoTable.Model candidate=MemoTable.create(model[0].cells.size(),model[0].cells.get(0).size());int n=0;for(int r=0;r<candidate.cells.size();r++)for(int c=0;c<candidate.cells.get(0).size();c++)MemoTable.setCell(candidate,r,c,fields.get(n++).getText().toString());MemoTable.encode(candidate);model[0]=candidate;};
        for(int i=0;i<4;i++){final int op=i;tool(controls,new String[]{T.s("＋行"),T.s("－行"),T.s("＋列"),T.s("－列")}[i],()->{try{read.run();if(op==0)MemoTable.addRow(model[0]);else if(op==1)MemoTable.removeRow(model[0],model[0].cells.size()-1);else if(op==2)MemoTable.addColumn(model[0]);else MemoTable.removeColumn(model[0],model[0].cells.get(0).size()-1);render.run();}catch(RuntimeException e){message(e.getMessage());}});}render.run();
        android.app.AlertDialog dialog=ThemeStore.dialog(this).setTitle(T.s("表格 · 首行为表头")).setView(outer).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("插入/更新"),null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{read.run();String table=MemoTable.encode(model[0]);if(!original.equals(bodyInput.getText().toString())){message(T.s("正文已变化，请重新打开表格"));return;}String value=original.substring(0,start)+"\n"+table+"\n"+original.substring(end);if(value.length()>MemoStore.MAX_BODY)throw new IllegalArgumentException(T.s("正文超过2万字"));bodyInput.apply(new MemoChecklist.Change(value,start+table.length()+2));dialog.dismiss();}catch(RuntimeException e){message(e.getMessage());}}));dialog.show();
    }
    private void aiMenu(){String[] actions={T.s("润色"),T.s("扩写"),T.s("精简"),T.s("总结"),T.s("翻译成中文"),T.s("翻译成英文"),T.s("API 设置")};ThemeStore.dialog(this).setTitle(T.s("AI 写作")).setItems(actions,(d,i)->{if(i==6){aiSettings();return;}
        final MemoChecklistInput requestOwner=bodyInput;final String requestNoteId=entryId;
        String original=bodyInput.getText().toString();int a=Math.max(0,bodyInput.getSelectionStart()),b=Math.max(a,bodyInput.getSelectionEnd());final int start=a==b?0:a,end=a==b?original.length():b;String source=original.substring(start,end);
        if(source.trim().isEmpty()){message(T.s("先写下或选中要处理的内容"));return;}if(MemoAi.endpoint(this).isEmpty()){aiSettings();return;}
        ThemeStore.dialog(this).setTitle(T.s("发送至你的 AI 服务？")).setMessage(actions[i]+T.s("将发送 ")+source.length()+T.s(" 字到：\n")+MemoAi.endpoint(this)+T.s("\n结果先预览，不会自动改写笔记。"))
            .setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("发送"),(confirm,w)->{if(bodyInput==requestOwner&&java.util.Objects.equals(entryId,requestNoteId))startAi(actions[i],original,start,end);}).show();}).setNegativeButton(T.s("关闭"),null).show();}
    private void startAi(String action,String original,int start,int end){
        if(isFinishing()||isDestroyed()||bodyInput==null||!editor)return;
        cancelAiUi();final long token=aiGeneration;final MemoChecklistInput owner=bodyInput;final String noteId=entryId;
        aiProgress=new android.app.ProgressDialog(this);aiProgress.setMessage(T.s("正在生成…"));aiProgress.setCancelable(true);
        aiProgress.setOnCancelListener(dialog->{if(token==aiGeneration)cancelAiUi();});aiProgress.show();
        final AiRequest request=aiRequest;
        new Thread(()->{
            try{String output=request.run(getApplicationContext(),action,original.substring(start,end));
                runOnUiThread(()->{if(!ownsAi(token,owner,noteId))return;showAiResult(token,owner,noteId,original,start,end,output);});
            }catch(Exception error){runOnUiThread(()->{if(ownsAi(token,owner,noteId)){cancelAiUi();message(error.getMessage());}});}
        },"memo-ai").start();
    }
    private void showAiResult(long token,MemoChecklistInput owner,String noteId,String original,int start,int end,String output){
        if(!ownsAi(token,owner,noteId))return;
        if(aiProgress!=null){aiProgress.dismiss();aiProgress=null;}
        TextView preview=text(output,16,ui.text());preview.setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(12));preview.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(preview);
        aiPreview=ThemeStore.dialog(this).setTitle(T.s("AI 结果")).setView(scroll).setNegativeButton(T.s("放弃"),null)
            .setNeutralButton(T.s("插入末尾"),(dialog,button)->{if(!ownsAi(token,owner,noteId))return;String current=owner.getText().toString();cancelAiUi();owner.apply(new MemoChecklist.Change(current+"\n"+output,current.length()+1+output.length()));})
            .setPositiveButton(T.s("替换原文"),(dialog,button)->{if(!ownsAi(token,owner,noteId))return;if(!original.equals(owner.getText().toString())){message(T.s("正文已变化，请重新生成后再替换"));return;}cancelAiUi();owner.apply(new MemoChecklist.Change(original.substring(0,start)+output+original.substring(end),start+output.length()));}).show();
    }
    private void aiSettings(){LinearLayout form=column();form.setPadding(ui.dp(20),ui.dp(8),ui.dp(20),0);EditText endpoint=new EditText(this),model=new EditText(this),key=new EditText(this);
        endpoint.setSingleLine();endpoint.setHint(T.s("HTTPS API 地址，例如 https://…/v1"));endpoint.setText(MemoAi.endpoint(this));model.setSingleLine();model.setHint(T.s("模型名称"));model.setText(MemoAi.model(this));
        key.setSingleLine();key.setHint(T.s("API 密钥，留空沿用已保存的密钥"));key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        for(EditText field:new EditText[]{endpoint,model,key}){field.setTextColor(ui.text());field.setHintTextColor(ui.text3());form.addView(field);}
        android.app.AlertDialog dialog=ThemeStore.dialog(this).setTitle(T.s("AI API 设置")).setView(form).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("保存"),null).setNeutralButton(T.s("清除设置"),(d,w)->ThemeStore.dialog(this).setTitle(T.s("清除 AI 设置？")).setMessage(T.s("已保存的 API 密钥也会删除。")).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("清除"),(x,y)->MemoAi.clear(this)).show()).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{MemoAi.save(this,endpoint.getText().toString(),model.getText().toString(),key.getText().toString());dialog.dismiss();message(T.s("AI 设置已保存，密钥不包含在备份中"));}catch(Exception e){message(e.getMessage());}}));dialog.show();}
    private int paperColor(String paper){if(ThemeStore.dark(this))return ui.surface();if("cream".equals(paper))return 0xfffff4da;if("blue".equals(paper))return 0xffe9f3ff;if("green".equals(paper))return 0xffeaf5ec;return ui.surface();}
    private void applyPaper(){if(bodyInput!=null)bodyInput.setBackgroundColor(paperColor(draftPaper));if(previewScroll!=null)previewScroll.setBackgroundColor(paperColor(draftPaper));}
    private void paperMenu(){String[] ids={"default","cream","blue","green"};ThemeStore.dialog(this).setTitle(T.s("纸张背景")).setItems(new String[]{T.s("跟随主题"),T.s("暖纸"),T.s("浅蓝"),T.s("浅绿")},(d,i)->{draftPaper=ids[i];applyPaper();}).setNegativeButton(T.s("关闭"),null).show();}
    private void chooseBook(boolean moving){
        MemoLibrary.Model model=MemoLibrary.load(this);java.util.List<String> ids=new java.util.ArrayList<>(),labels=new java.util.ArrayList<>();
        if(!moving){ids.add("*");labels.add(T.s("全部笔记"));}ids.add("");labels.add(T.s("默认笔记本"));
        for(MemoLibrary.Book book:model.notebooks){ids.add(book.id);labels.add(book.name);}
        android.app.AlertDialog.Builder builder=ThemeStore.dialog(this).setTitle(moving?T.s("移动到笔记本"):T.s("笔记本")).setItems(labels.toArray(new String[0]),(d,i)->{
            if(moving){draftBook=ids.get(i);message(T.s("保存后移动"));}else{notebookFilter=ids.get(i);refreshList(true);}
        }).setNegativeButton(T.s("关闭"),null);
        if(!moving){builder.setPositiveButton(T.s("新建"),(d,w)->bookName(null));builder.setNeutralButton(T.s("管理"),(d,w)->manageBooks());}builder.show();
    }
    private void bookName(MemoLibrary.Book book){EditText name=new EditText(this);name.setSingleLine();name.setTextColor(ui.text());name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});if(book!=null)name.setText(book.name);
        android.app.AlertDialog dialog=ThemeStore.dialog(this).setTitle(book==null?T.s("新建笔记本"):T.s("重命名笔记本")).setView(name).setPositiveButton(T.s("保存"),null).setNegativeButton(T.s("取消"),null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{if(book==null)MemoLibrary.createNotebook(this,name.getText().toString());else MemoLibrary.renameNotebook(this,book.id,name.getText().toString());dialog.dismiss();chooseBook(false);}catch(RuntimeException e){message(e.getMessage());}}));dialog.show();
    }
    private void manageBooks(){final MemoLibrary.Model model=MemoLibrary.load(this);String[] labels=new String[model.notebooks.size()];for(int i=0;i<labels.length;i++)labels[i]=model.notebooks.get(i).name;
        if(labels.length==0){message(T.s("暂无自建笔记本"));return;}ThemeStore.dialog(this).setTitle(T.s("管理笔记本")).setItems(labels,(d,i)->{MemoLibrary.Book book=model.notebooks.get(i);
            ThemeStore.dialog(this).setTitle(book.name).setItems(new String[]{T.s("重命名"),T.s("删除笔记本")},(x,j)->{if(j==0)bookName(book);else ThemeStore.dialog(this).setTitle(T.s("删除笔记本？")).setMessage(T.s("笔记会移回默认笔记本，不会删除笔记。")).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("删除"),(q,w)->{MemoLibrary.deleteNotebook(this,book.id);notebookFilter="*";refreshList();}).show();}).show();}).show();
    }
    private void highlight(int anchor,int focus){
        if(previewing)togglePreview();int a=Math.max(0,Math.min(anchor,focus)),b=Math.max(0,Math.max(anchor,focus));
        if(a==b){message(T.s("先选中需要高亮的文字"));return;}
        MemoTextFormat.Change change=MemoTextFormat.inline(bodyInput.getText().toString(),a,b,"highlight");
        bodyInput.apply(new MemoChecklist.Change(change.body,change.cursor));
    }
    private void blockMenu(){
        if(previewing)togglePreview();String body=bodyInput.getText().toString();List<MemoBlocks.Block> blocks=MemoBlocks.split(body);
        if(blocks.isEmpty()){message(T.s("先写下段落，再进行块编辑"));return;}
        final int selected=MemoBlocks.at(blocks,Math.max(0,bodyInput.getSelectionStart()));
        String[] labels=new String[blocks.size()];
        for(int i=0;i<blocks.size();i++){MemoBlocks.Block block=blocks.get(i);String value=body.substring(block.start,block.end).trim().replace('\n',' ');
            labels[i]=(i+1)+" · "+(value.isEmpty()?T.s("空行"):value.substring(0,Math.min(55,value.length())));}
        android.app.AlertDialog dialog=ThemeStore.dialog(this).setTitle(T.s("块编辑 · 选择段落")).setSingleChoiceItems(labels,selected,(d,i)->{
            d.dismiss();blockActions(body,i);
        }).setNegativeButton(T.s("关闭"),null).create();dialog.show();dialog.getListView().setSelection(selected);
    }
    private void blockActions(String original,int index){
        List<MemoBlocks.Block> blocks=MemoBlocks.split(original);MemoBlocks.Block block=blocks.get(index);
        String preview=original.substring(block.start,block.end);if(preview.length()>180)preview=preview.substring(0,180)+"…";
        final String summary=preview;
        ThemeStore.dialog(this).setTitle(T.s("段落 ")+(index+1)).setItems(new String[]{T.s("定位编辑"),T.s("上移"),T.s("下移"),T.s("复制段落"),T.s("删除段落")},(d,i)->{
            if(!original.equals(bodyInput.getText().toString())){message(T.s("内容已变化，请重新选择段落"));return;}
            if(i==0){bodyInput.requestFocus();bodyInput.setSelection(block.start,block.end);showKeyboard(bodyInput);return;}
            if(i==4){ThemeStore.dialog(this).setTitle(T.s("删除这段内容？")).setMessage(summary).setNegativeButton(T.s("取消"),null)
                .setPositiveButton(T.s("删除"),(confirm,which)->applyBlock(original,index,"delete")).show();return;}
            if(i==1&&index==0||i==2&&index==blocks.size()-1){message(T.s("已在段落边界"));return;}
            applyBlock(original,index,new String[]{"","up","down","copy"}[i]);blockMenu();
        }).setNegativeButton(T.s("返回"),(d,w)->blockMenu()).show();
    }
    private void applyBlock(String original,int index,String operation){
        if(!original.equals(bodyInput.getText().toString())){message(T.s("内容已变化，请重新选择段落"));return;}
        MemoTextFormat.Change change=MemoBlocks.edit(original,index,operation);
        if(change.body.equals(original)){message(T.s("内容未改变，或已达到 2 万字上限"));return;}
        bodyInput.apply(new MemoChecklist.Change(change.body,change.cursor));
        if("delete".equals(operation))message(T.s("已删除段落，可用底部撤销恢复"));
    }
    private void formatMenu(){String[] labels={T.s("标题"),T.s("副标题"),T.s("小标题"),T.s("正文"),T.s("粗体"),T.s("斜体"),T.s("删除线"),T.s("下划线"),T.s("高亮"),T.s("无序列表"),T.s("有序列表"),T.s("增加缩进"),T.s("减少缩进")};
        ThemeStore.dialog(this).setTitle(T.s("格式")).setItems(labels,(d,i)->{if(previewing)togglePreview();String body=bodyInput.getText().toString();int a=Math.max(0,bodyInput.getSelectionStart()),b=Math.max(0,bodyInput.getSelectionEnd());MemoTextFormat.Change change;
            if(i<4)change=MemoTextFormat.heading(body,a,b,i==3?0:i+1);else if(i<9)change=MemoTextFormat.inline(body,a,b,new String[]{"bold","italic","strike","underline","highlight"}[i-4]);else if(i<11)change=MemoTextFormat.list(body,a,b,i==10);else change=MemoTextFormat.indent(body,a,b,i==11);
            bodyInput.apply(new MemoChecklist.Change(change.body,change.cursor));}).setNegativeButton(T.s("关闭"),null).show();
    }
    private void backupMenu(){ThemeStore.dialog(this).setTitle(T.s("本机备份")).setMessage(T.s("备份包含笔记、回收站、分类和附件，不包含同步密钥。恢复会合并并保留现有内容。")).setItems(new String[]{T.s("导出完整备份"),T.s("恢复备份")},(d,i)->{
        Intent intent=i==0?new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"AwayFromShorts-memos.zip")
                :new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try{startActivityForResult(intent,i==0?REQ_BACKUP:REQ_RESTORE);}catch(android.content.ActivityNotFoundException e){message(T.s("没有可用的文件选择器"));}
    }).setNegativeButton(T.s("关闭"),null).show();}
    private org.json.JSONObject remapLibrary(org.json.JSONObject source,java.util.Map<String,String> ids)throws org.json.JSONException{
        org.json.JSONObject result=new org.json.JSONObject(source.toString());for(String key:new String[]{"assignments","papers"}){
            org.json.JSONObject old=result.getJSONObject(key),mapped=new org.json.JSONObject();java.util.Iterator<String> names=old.keys();while(names.hasNext()){String name=names.next();mapped.put(ids.containsKey(name)?ids.get(name):name,old.get(name));}result.put(key,mapped);
        }return result;
    }
    private void taskManager(){
        if(previewing)togglePreview();
        final String body=bodyInput.getText().toString();final int a=Math.max(0,bodyInput.getSelectionStart()),b=Math.max(0,bodyInput.getSelectionEnd());
        int count=MemoChecklist.selected(body,a,b).size();
        String[] labels={T.s("完成所选待办"),T.s("恢复所选待办"),T.s("增加缩进"),T.s("减少缩进"),T.s("上移"),T.s("下移"),T.s("移除勾选框"),T.s("删除所选待办"),T.s("全部完成"),T.s("全部恢复")};
        android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public boolean areAllItemsEnabled(){return false;}
            @Override public boolean isEnabled(int position){return position<8?count>0:!MemoChecklist.items(body).isEmpty();}
            @Override public View getView(int position,View old,android.view.ViewGroup parent){TextView view=(TextView)super.getView(position,old,parent);view.setTextColor(isEnabled(position)?ui.text():ui.text3());return view;}
        };
        android.app.AlertDialog dialog=ThemeStore.dialog(this).setTitle(T.s("待办管理 · 所选 ")+count+T.s(" 项")).setAdapter(adapter,(d,i)->{
            if(i<8&&count==0){message(T.s("先把光标放到待办上，或选中多行待办"));return;}
            MemoChecklist.Change change=null;
            switch(i){case 0:change=MemoChecklist.setChecked(body,a,b,true);break;case 1:change=MemoChecklist.setChecked(body,a,b,false);break;
                case 2:change=MemoChecklist.indent(body,a,b,true);break;case 3:change=MemoChecklist.indent(body,a,b,false);break;
                case 4:change=MemoChecklist.move(body,a,b,false);break;case 5:change=MemoChecklist.move(body,a,b,true);break;
                case 6:change=MemoChecklist.removeMarkers(body,a,b);break;
                case 7:ThemeStore.dialog(this).setTitle(T.s("删除所选的 ")+count+T.s(" 项待办？")).setMessage(T.s("删除后可通过撤销恢复。"))
                        .setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("删除"),(confirm,w)->bodyInput.apply(MemoChecklist.deleteItems(body,a,b))).show();return;
                case 8:change=MemoChecklist.setChecked(body,0,body.length(),true);break;
                case 9:change=MemoChecklist.setChecked(body,0,body.length(),false);break;}
            if(change!=null)bodyInput.apply(change);
        }).setNegativeButton(T.s("关闭"),null).create();
        dialog.show();
    }
    private void findText(){
        LinearLayout panel=column();panel.setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(12));
        EditText queryInput=new EditText(this);queryInput.setSingleLine();queryInput.setHint(T.s("查找标题和正文"));queryInput.setTextColor(ui.text());queryInput.setHintTextColor(ui.text3());panel.addView(queryInput);
        TextView result=text(T.s("输入要查找的文字"),13,ui.text2());result.setPadding(0,ui.dp(8),0,ui.dp(8));panel.addView(result);
        final java.util.List<MemoEditor.Match>[] matches=new java.util.List[]{new java.util.ArrayList<MemoEditor.Match>()};final int[] index={-1};
        Runnable locate=()->{
            if(matches[0].isEmpty()){result.setText(queryInput.length()==0?T.s("输入要查找的文字"):T.s("未找到匹配"));return;}
            MemoEditor.Match match=matches[0].get(index[0]);EditText target=match.title?titleInput:bodyInput;
            if(previewing)togglePreview();target.setSelection(Math.min(match.start,target.length()),Math.min(match.end,target.length()));
            result.setText((index[0]+1)+" / "+matches[0].size()+" · "+(match.title?T.s("标题"):T.s("正文")));
        };
        queryInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int af){}public void onTextChanged(CharSequence s,int st,int before,int count){
            matches[0]=MemoEditor.find(titleInput.getText().toString(),bodyInput.getText().toString(),s.toString());index[0]=matches[0].isEmpty()?-1:0;locate.run();
        }public void afterTextChanged(Editable e){}});
        android.app.AlertDialog dialog=ThemeStore.dialog(this).setTitle(T.s("文内查找")).setView(panel).setNegativeButton(T.s("关闭"),null)
                .setNeutralButton(T.s("上一处"),null).setPositiveButton(T.s("下一处"),null).create();
        dialog.setOnShowListener(d->{dialog.getButton(-3).setOnClickListener(v->{if(!matches[0].isEmpty()){index[0]=(index[0]+matches[0].size()-1)%matches[0].size();locate.run();}});
            dialog.getButton(-1).setOnClickListener(v->{if(!matches[0].isEmpty()){index[0]=(index[0]+1)%matches[0].size();locate.run();}});});
        dialog.setOnDismissListener(d->{if(!matches[0].isEmpty()){MemoEditor.Match match=matches[0].get(index[0]);(match.title?titleInput:bodyInput).requestFocus();}});dialog.show();
    }
    private void shareText(){
        String title=titleInput.getText().toString(),body=bodyInput.getText().toString();
        Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,title).putExtra(Intent.EXTRA_TEXT,title+(title.isEmpty()?"":"\n\n")+body);
        try{startActivity(Intent.createChooser(send,T.s("分享备忘录")));}catch(android.content.ActivityNotFoundException e){message(T.s("没有可用的分享应用"));}
    }
    private void reminder(){
        ThemeStore.dialog(this).setTitle(T.s("提醒")).setMessage((draftRemindAt>0?T.s("当前：")+reminderTime(draftRemindAt)+"\n":"")+T.s("保存备忘录后生效。系统省电可能让提醒稍有延迟。"))
                .setPositiveButton(T.s("设置时间"),(d,w)->pickReminder()).setNeutralButton(T.s("取消提醒"),(d,w)->{draftRemindAt=0;updateHistory();})
                .setNegativeButton(T.s("关闭"),null).show();
    }
    private void pickReminder(){
        java.util.Calendar date=java.util.Calendar.getInstance();date.setTimeInMillis(draftRemindAt>System.currentTimeMillis()?draftRemindAt:System.currentTimeMillis()+3600000L);
        android.app.DatePickerDialog picker=new android.app.DatePickerDialog(this,(view,y,m,day)->{
            date.set(y,m,day);android.app.TimePickerDialog timePicker=new android.app.TimePickerDialog(this,(clock,hour,minute)->{
                date.set(java.util.Calendar.HOUR_OF_DAY,hour);date.set(java.util.Calendar.MINUTE,minute);date.set(java.util.Calendar.SECOND,0);date.set(java.util.Calendar.MILLISECOND,0);
                if(date.getTimeInMillis()<=System.currentTimeMillis()){message(T.s("请选择未来的提醒时间"));return;}
                draftRemindAt=date.getTimeInMillis();updateHistory();
                if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},44);
            },date.get(java.util.Calendar.HOUR_OF_DAY),date.get(java.util.Calendar.MINUTE),true);
            timePicker.setButton(-2,T.s("取消"),timePicker);timePicker.setButton(-1,T.s("确定"),timePicker);timePicker.show();
        },date.get(java.util.Calendar.YEAR),date.get(java.util.Calendar.MONTH),date.get(java.util.Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMinDate(System.currentTimeMillis());picker.setButton(-2,T.s("取消"),picker);picker.setButton(-1,T.s("确定"),picker);picker.show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==44){updateHistory();if(!MemoReminder.allowed(this))message(T.s("尚未允许通知，提醒不会弹出；可在系统设置中开启"));}}
    private void exportFile(){
        exportText=titleInput.getText().toString()+"\n\n"+bodyInput.getText().toString();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/markdown")
                .putExtra(Intent.EXTRA_TITLE,MemoEditor.filename(currentLabel()));
        try{startActivityForResult(intent,REQ_EXPORT);}catch(android.content.ActivityNotFoundException e){message(T.s("没有可用的文件选择器"));}
    }
    private void showTrash(){
        final List<MemoStore.Note> deleted=MemoStore.trash(this);
        if(deleted.isEmpty()){message(T.s("回收站为空"));return;}
        String[] labels=new String[deleted.size()];for(int i=0;i<labels.length;i++)labels[i]=MemoStore.displayTitle(deleted.get(i));
        ThemeStore.dialog(this).setTitle(T.s("回收站 · ")+labels.length).setItems(labels,(d,which)->{
            MemoStore.Note note=deleted.get(which);
            ThemeStore.dialog(this).setTitle(MemoStore.displayTitle(note)).setMessage(MemoStore.excerpt(note.body))
                .setPositiveButton(T.s("恢复"),(dialog,w)->{try{MemoStore.restore(this,note.id);refreshList(true);message(T.s("已恢复"));}catch(RuntimeException e){message(e.getMessage());}})
                .setNeutralButton(T.s("永久删除"),(dialog,w)->ThemeStore.dialog(this).setTitle(T.s("永久删除？")).setMessage(T.s("此操作无法撤回。"))
                    .setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("永久删除"),(confirm,x)->{try{MemoStore.erase(this,note.id);message(T.s("已永久删除"));}catch(RuntimeException e){message(e.getMessage());}}).show())
                .setNegativeButton(T.s("取消"),null).show();
        }).setNegativeButton(T.s("关闭"),null).show();
    }
    private void sources(){
        ThemeStore.dialog(this).setTitle(T.s("开源说明")).setMessage(T.s("编辑体验参考 Markor（Apache-2.0）。\n基础 Markdown 预览使用其 GsSimpleMarkdownParser 组件（Unlicense / CC0-1.0）。\n作者：Gregor Santner。\nhttps://github.com/gsantner/markor\n\n支持标题、粗体、斜体和列表；不加载远程图片，不执行 HTML。文件为 UTF-8 纯文本。"))
                .setPositiveButton(T.s("关闭"),null).show();
    }

    /* ==================== 小工具 ==================== */

    private MemoStore.Note find(String id) {
        for (MemoStore.Note note : MemoStore.all(this)) if (id.equals(note.id)) return note;
        return null;
    }

    private static MemoStore.Note shown(MemoStore.Note note) {
        MemoStore.Note copy=note.copy();if(copy.title==null)copy.title="";if(copy.body==null)copy.body="";return copy;
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(ui.bg());
        return box;
    }

    private LinearLayout row() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        return box;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    /** 顶栏的圆形图标按钮（返回、搜索）：线条图标不跟随用户的字体。 */
    private View glyphButton(String glyph, String description, Runnable action) {
        View view = new ThemeGlyphView(this, glyph, ui.text());
        view.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        view.setBackground(ui.round(ui.surface(), 99));
        view.setContentDescription(description);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    /** 新建：强调色圆里的「＋」，是这一页唯一的主动作。 */
    private View plusButton() {
        TextView view = new TextView(this);
        ThemeStore.excludeFont(view);
        view.setText(T.s("＋"));
        view.setTextSize(22);
        view.setTextColor(ui.onAccent());
        view.setGravity(Gravity.CENTER);
        view.setBackground(ui.round(ui.accent(), 99));
        view.setContentDescription(T.s("新建备忘录"));
        view.setOnClickListener(v -> startActivityForResult(
                new Intent(this, MemoActivity.class).putExtra("new", true).putExtra("book","*".equals(notebookFilter)?"":notebookFilter), REQ_EDITOR));
        return view;
    }

    private void showKeyboard(View target) {
        target.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard(View target) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
    }
}
