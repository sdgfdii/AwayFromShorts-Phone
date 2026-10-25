package com.afs.guard;

import android.app.*;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Native offline math practice; imports are explicitly previewed before committing. */
public class QuizActivity extends ThemedActivity {
    private static final int IMPORT=71,EXPORT=72,TEMPLATE=73;
    private Ui ui; private QuizData data;
    private LinearLayout body; private ScrollView scroll;
    private String page="home",subject="高数",chapter="",mode="all",search="",importSubject="高数",lastDeleted="";
    private EditText notes;
    private boolean busy, draftDirty;
    private final Handler handler=new Handler();
    private final ArrayList<QuizMathView> mathViews=new ArrayList<>();
    private final Runnable saveDraft=()->{try{save();}catch(Exception e){error(e);}};

    @Override protected void onCreate(Bundle saved) {
        if(Ui.isDark(this))setTheme(getResources().getIdentifier("NavThemeDark","style",getPackageName()));
        super.onCreate(saved);ui=new Ui(this);
        if(saved!=null){subject=saved.getString("subject","高数");chapter=saved.getString("chapter","");
            mode=saved.getString("mode","all");page=saved.getString("page","home");search=saved.getString("search","");importSubject=saved.getString("importSubject","高数");lastDeleted=saved.getString("lastDeleted","");}
        try{data=QuizStore.snapshot(this);if("practice".equals(page))practice();else if("bank".equals(page))bank();else home();}
        catch(Exception e){base("刷题","本地题库暂时无法读取");error(e);}
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("page",page);out.putString("subject",subject);out.putString("chapter",chapter);out.putString("mode",mode);out.putString("search",search);
        out.putString("importSubject",importSubject);out.putString("lastDeleted",lastDeleted);
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume(){
        super.onResume();
        if(ui!=null && ui.dark!=Ui.isDark(this)){recreate();return;}
        FloatingNav.fullscreen(this);
    }
    @Override protected void onPause(){super.onPause();handler.removeCallbacks(saveDraft);if(data!=null && draftDirty){try{save();}catch(Exception e){error(e);}}}
    @Override protected void onDestroy(){handler.removeCallbacks(saveDraft);destroyMath();super.onDestroy();}
    @Override public void onBackPressed(){if(busy)return;if("home".equals(page))super.onBackPressed();else home();}
    private void save(){QuizStore.commit(this,data);draftDirty=false;}
    private void error(Exception e){if(!isFinishing())ThemeStore.dialog(this).setTitle("暂未完成")
        .setMessage(e.getMessage()==null?"请稍后重试":e.getMessage()).setPositiveButton("知道了",null).show();}
    private void act(Runnable r){try{r.run();}catch(Exception e){error(e);}}
    private void destroyMath(){for(QuizMathView v:mathViews){if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);v.stopLoading();v.removeAllViews();v.destroy();}mathViews.clear();}
    private void base(String title,String subtitle){
        handler.removeCallbacks(saveDraft);destroyMath();notes=null;
        scroll=new ScrollView(this);scroll.setFillViewport(true);ui.page(scroll);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(32));scroll.addView(body);
        setContentView(FloatingNav.wrap(this,scroll,1));getWindow().setStatusBarColor(ui.bg());getWindow().setNavigationBarColor(ui.bg());
        FloatingNav.fullscreen(this);
        if(!"home".equals(page)){
        TextView back=text("‹  刷题",14,ui.accent());
        back.setPadding(0,ui.dp(10),0,ui.dp(18));back.setOnClickListener(v->onBackPressed());body.addView(back);
        }
        body.addView(ui.title(title));hint(body,subtitle);
    }
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(ui.dp(3),1);return t;}
    private void hint(LinearLayout parent,String s){TextView t=text(s,13,ui.text2());t.setPadding(0,ui.dp(8),0,ui.dp(14));parent.addView(t);}
    private TextView button(LinearLayout parent,String label,boolean primary,Runnable action){
        TextView t=text(label,15,primary?ui.onAccent():ui.accent());t.setGravity(Gravity.CENTER);
        SwissTypography.role(t,SwissTypography.Role.BUTTON);
        t.setTypeface(Typeface.DEFAULT_BOLD);t.setMinHeight(ui.dp(48));t.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));
        t.setBackground(ui.round(primary?ui.accent():ui.amap?ui.surface():ui.surface2(),ui.amap?99:13));
        LinearLayout.LayoutParams lp=ui.gap(8);parent.addView(t,lp);t.setOnClickListener(v->{if(!busy)act(action);});return t;
    }
    private LinearLayout card(){LinearLayout c=ui.card();c.setElevation(ui.dp(3));body.addView(c,ui.gap(12));return c;}
    private void math(LinearLayout parent,String value){QuizMathView v=new QuizMathView(this,ui,value);mathViews.add(v);parent.addView(v);}
    private String date(String format){return new SimpleDateFormat(format,Locale.ROOT).format(new Date());}
    private String accuracy(int[] stat){return stat[0]==0?"—":Math.round(stat[1]*100f/stat[0])+"%";}
    private void home(){
        page="home";
        List<String> available=data.subjects();
        if(!available.contains(subject)){subject=available.isEmpty()?"":available.get(0);chapter="";}
        base("刷题","自定义科目与题库，按自己的节奏练习。");
        int[] today=data.stats(date("yyyy-MM-dd")),month=data.stats(date("yyyy-MM")),all=data.stats("");
        LinearLayout stats=card();TextView n=text(today[0]+" 题",32,ui.text());n.setTypeface(Typeface.DEFAULT_BOLD);stats.addView(n);
        hint(stats,"今日作答  ·  正确率 "+accuracy(today));
        stats.addView(text("本月 "+month[0]+" 题   /   累计 "+all[0]+" 题   /   累计正确率 "+accuracy(all),12,ui.text2()));
        if(data.current()!=null){JSONObject q=data.current();button(body,"继续上次 · "+q.optString("subject")+" · 第 "+(data.session().optInt("index")+1)+" 题",true,()->practice());}
        HorizontalScrollView subjectScroll=new HorizontalScrollView(this);subjectScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout subjects=new LinearLayout(this);subjects.setGravity(Gravity.CENTER_VERTICAL);subjectScroll.addView(subjects);
        subjectScroll.setClipToPadding(false);subjectScroll.setPadding(0,ui.dp(8),0,ui.dp(8));
        for(String s:data.subjects()){
            TextView t=text(s,17,s.equals(subject)?ui.onAccent():ui.text2());t.setGravity(Gravity.CENTER);t.setPadding(ui.dp(18),ui.dp(13),ui.dp(18),ui.dp(13));
            t.setBackground(ui.round(s.equals(subject)?ui.accent():ui.surface(),14));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMargins(0,0,ui.dp(8),0);subjects.addView(t,lp);
            if(s.equals(subject))subjectScroll.post(()->subjectScroll.scrollTo(Math.max(0,t.getLeft()-ui.dp(8)),0));
            t.setOnClickListener(v->{subject=s;chapter="";home();});
            t.setOnLongClickListener(v->{deleteSubject(s);return true;});
        }
        button(subjects,"＋ 科目",false,()->newSubject(()->home()));
        button(body,"管理学科",false,()->manageSubjects());
        if(!lastDeleted.isEmpty() && data.deleted(lastDeleted))button(body,"已移入回收站 · 撤回",false,()->restoreSubject(lastDeleted));
        if(subject.isEmpty()){
            hint(body,"暂无学科，点击「＋ 科目」添加，或从回收站恢复。");
            button(body,"导入题库",true,()->importMenu());
            button(body,"题库工具",false,()->toolsMenu());
            button(body,"学科回收站 · "+data.deletedSubjects().size(),false,()->subjectTrash());
            body.addView(subjectScroll,ui.gap(12));return;
        }
        LinearLayout practice=card();int total=data.filter(subject,"","all","").size(),wrong=data.filter(subject,"","wrong","").size(),star=data.filter(subject,"","star","").size();
        TextView heading=text(subject.equals("高数")?"高等数学":subject.equals("线代")?"线性代数":subject,21,ui.text());heading.setTypeface(Typeface.DEFAULT_BOLD);practice.addView(heading);
        hint(practice,total+" 题 · 待复习错题 "+wrong+" · 收藏 "+star);
        button(practice,"顺序练习",true,()->start("all",false,""));
        button(practice,"随机练习",false,()->start("all",true,""));
        button(practice,"只练未做题",false,()->start("new",false,""));
        button(practice,"错题本 · "+wrong+" 题",false,()->{mode="wrong";chapter="";search="";bank();});
        button(practice,"收藏夹 · "+star+" 题",false,()->{mode="star";chapter="";search="";bank();});
        LinkedHashMap<String,Integer> chapters=new LinkedHashMap<>();
        for(JSONObject q:data.filter(subject,"","all","")){String c=q.optString("chapter");chapters.put(c,chapters.containsKey(c)?chapters.get(c)+1:1);}
        body.addView(text("按章节练习",17,ui.text()));
        for(Map.Entry<String,Integer> entry:chapters.entrySet()){
            String c=entry.getKey();button(body,c+"   "+entry.getValue()+" 题  ›",false,()->start("all",false,c));
        }
        button(body,"题库浏览与搜索",false,()->{mode="all";chapter="";search="";bank();});
        button(body,"导入题库",true,()->importMenu());
        button(body,"模板 / 导出题库 / 格式说明",false,()->toolsMenu());
        button(body,"学科回收站 · "+data.deletedSubjects().size(),false,()->subjectTrash());
        body.addView(subjectScroll,ui.gap(12));
        hint(body,"内置科目可以删除，也可以自行添加科目。选择题自动判分；填空、计算和证明题查看解析后自行核对。进度保存在本机。");
    }
    private void manageSubjects(){
        List<String> available=data.subjects();
        if(available.isEmpty()){subjectTrash();return;}
        String[] names=new String[available.size()];for(int i=0;i<names.length;i++)names[i]="删除「"+available.get(i)+"」";
        ThemeStore.dialog(this).setTitle("管理学科").setItems(names,(d,w)->deleteSubject(available.get(w)))
            .setNeutralButton("回收站",(d,w)->subjectTrash()).setNegativeButton("关闭",null).show();
    }
    private void deleteSubject(String name){
        SubjectDeletion.confirm(this,ui,name,data.subjectCount(name)+" 道题目",()->act(()->{
            QuizData next=new QuizData(data.db.toString());next.deleteSubject(name);QuizStore.commit(this,next);data=next;lastDeleted=name;home();
        }));
    }
    private void restoreSubject(String name){
        act(()->{QuizData next=new QuizData(data.db.toString());next.restoreSubject(name);QuizStore.commit(this,next);data=next;subject=name;lastDeleted="";home();Toast.makeText(this,"学科与题目已恢复",Toast.LENGTH_SHORT).show();});
    }
    private void subjectTrash(){
        List<String> trash=data.deletedSubjects();
        if(trash.isEmpty()){Toast.makeText(this,"回收站为空",Toast.LENGTH_SHORT).show();return;}
        String[] names=new String[trash.size()];for(int i=0;i<names.length;i++)names[i]="恢复「"+trash.get(i)+"」 · "+data.subjectCount(trash.get(i))+" 道题目";
        ThemeStore.dialog(this).setTitle("学科回收站").setItems(names,(d,w)->restoreSubject(trash.get(w))).setNegativeButton("关闭",null).show();
    }
    private void start(String selectedMode,boolean random,String selectedChapter){
        List<JSONObject> list=data.filter(subject,selectedChapter,selectedMode,"");
        if(list.isEmpty()){Toast.makeText(this,"这里还没有题目",Toast.LENGTH_SHORT).show();return;}
        Runnable go=()->act(()->{data.start(list,random);save();practice();});
        if(data.current()!=null)ThemeStore.dialog(this).setTitle("开始新的练习？")
            .setMessage("上次已提交的答题记录会保留，未完成的练习队列将被替换。")
            .setNegativeButton("继续上次",(d,w)->practice()).setPositiveButton("开始新练习",(d,w)->go.run()).show();else go.run();
    }
    private void practice(){
        page="practice";JSONObject q=data.current();
        if(q==null){finished();return;}
        JSONObject s=data.session();JSONArray ids=s.optJSONArray("ids");
        base(q.optString("subject")+" · "+q.optString("chapter"),"第 "+(s.optInt("index")+1)+" / "+ids.length()+" 题   ·   "+QuizData.typeName(q.optString("type")));
        String id=q.optString("id");
        TextView favorite=button(body,data.progress(id).optBoolean("star")?"★ 已收藏":"☆ 收藏本题",false,()->{});favorite.setTextSize(13);
        favorite.setOnClickListener(v->act(()->{data.star(id);save();favorite.setText(data.progress(id).optBoolean("star")?"★ 已收藏":"☆ 收藏本题");}));
        LinearLayout paper=card();
        StringBuilder content=new StringBuilder(q.optString("stem"));
        if(QuizData.objective(q)){
            JSONArray opts=q.optJSONArray("options");
            for(int i=0;i<opts.length();i++)content.append(i==0?"\n\n":"\n").append((char)('A'+i)).append(".  ").append(opts.optString(i));
        }
        math(paper,content.toString());
        if(!q.optString("source").isEmpty())hint(paper,"来源："+q.optString("source"));
        boolean graded=s.optBoolean("graded"),revealed=s.optBoolean("revealed");
        if(QuizData.objective(q)){
            if(!graded){
                hint(body,"multiple".equals(q.optString("type"))?"选择所有正确选项后提交":"选择一个答案后提交");
                LinearLayout row=new LinearLayout(this);body.addView(row,ui.gap(12));
                ArrayList<TextView> choices=new ArrayList<>();
                for(int i=0;i<q.optJSONArray("options").length();i++){
                    final String letter=""+(char)('A'+i);boolean selected=s.optString("selected").contains(letter);
                    TextView option=text(letter,17,selected?ui.onAccent():ui.text());option.setGravity(Gravity.CENTER);option.setMinHeight(ui.dp(50));
                    option.setBackground(ui.round(selected?ui.accent():ui.surface(),12));
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ui.dp(50),1);if(i>0)lp.leftMargin=ui.dp(6);row.addView(option,lp);
                    option.setContentDescription("选项 "+letter+(selected?" 已选择":""));
                    choices.add(option);
                    option.setOnClickListener(v->act(()->{
                        String value=s.optString("selected");
                        if("multiple".equals(q.optString("type")))value=value.contains(letter)?value.replace(letter,""):QuizData.letters(value+letter);else value=letter;
                        data.draft(value,s.optString("input"));save();
                        for(int n=0;n<choices.size();n++){
                            TextView choice=choices.get(n);String key=""+(char)('A'+n);boolean checked=value.contains(key);
                            choice.setTextColor(checked?ui.onAccent():ui.text());choice.setBackground(ui.round(checked?ui.accent():ui.surface(),12));
                            choice.setContentDescription("选项 "+key+(checked?" 已选择":""));
                        }
                    }));
                }
                button(body,"提交答案",true,()->grade(null));
            }
        } else {
            hint(body,"先在纸上演算，也可以在下方记录思路。数学表达式由你核对，不作字符串判分。");
            notes=new EditText(this);notes.setTextColor(ui.text());notes.setHintTextColor(ui.text3());notes.setTextSize(15);
            notes.setGravity(Gravity.TOP);notes.setMinLines(3);notes.setHint("我的答案 / 解题思路（选填）");
            notes.setBackground(ui.round(ui.surface(),12));notes.setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12));
            notes.setText(s.optString("input"));notes.setEnabled(!graded);body.addView(notes,ui.gap(12));
            notes.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence v,int a,int c,int n){}public void onTextChanged(CharSequence v,int a,int b,int c){
                data.draft(s.optString("selected"),v.toString());draftDirty=true;handler.removeCallbacks(saveDraft);handler.postDelayed(saveDraft,700);
            }public void afterTextChanged(Editable e){}});
            if(!revealed)button(body,"查看答案与解析",true,()->{data.reveal();save();practice();});
        }
        if(revealed||graded){
            if(graded){TextView result=text(s.optBoolean("correct")?"✓ 回答正确":"需要复习 · 已加入错题本",18,s.optBoolean("correct")?ui.green():ui.red());body.addView(result,ui.gap(12));}
            LinearLayout analysis=card();analysis.addView(text("答案与解析",17,ui.text()));
            String solution="参考答案："+q.optString("answer");
            if(QuizData.objective(q)&&graded)solution="你的答案："+s.optString("selected")+"\n"+solution;
            solution+="\n\n"+(q.optString("analysis").isEmpty()?"本题未提供解析。":q.optString("analysis"));math(analysis,solution);
            if(!graded){button(body,"核对后：做对了",true,()->grade(true));button(body,"核对后：需复习",false,()->grade(false));}
            else button(body,s.optInt("index")+1==ids.length()?"查看本轮结果":"下一题",true,()->{data.next();save();practice();});
        }
        hint(body,"本轮已完成 "+s.optInt("done")+" 题 · 做对 "+s.optInt("right")+" 题。返回后可继续。");
    }
    private void grade(Boolean self){
        QuizData before=new QuizData(data.db.toString());
        try{data.grade(self,System.currentTimeMillis());save();practice();}
        catch(Exception e){data=before;throw e;}
    }
    private void finished(){
        page="practice";base("本轮完成","这次练习的记录已经保存在本机。");JSONObject s=data.session();
        LinearLayout c=card();c.addView(text(s.optInt("done")+" 题完成",28,ui.text()));
        hint(c,"正确 "+s.optInt("right")+" 题 · 需要复习 "+(s.optInt("done")-s.optInt("right"))+" 题");
        button(body,"回到刷题",true,()->home());
        button(body,"复习错题",false,()->{mode="wrong";chapter="";search="";bank();});
    }
    private int bankPage;
    private void bank(){
        page="bank";String title="wrong".equals(mode)?"错题本":"star".equals(mode)?"收藏夹":"题库";
        base(subject+" · "+title,"错题答对后会移出待复习列表，历史答题次数仍保留。");
        EditText query=new EditText(this);query.setSingleLine(true);query.setText(search);query.setTextColor(ui.text());query.setHintTextColor(ui.text3());query.setHint("搜索题干、章节或来源");body.addView(query,ui.gap(8));
        button(body,"搜索",false,()->{search=query.getText().toString().trim();bankPage=0;bank();});
        List<JSONObject> list=data.filter(subject,chapter,mode,search);
        hint(body,"共 "+list.size()+" 题");
        if(list.isEmpty()){hint(body,"这里还没有题目，可以切换科目、搜索条件，或导入题库。");return;}
        button(body,"练习这些题",true,()->startFiltered(list));
        int pages=(list.size()+19)/20;bankPage=Math.min(bankPage,pages-1);
        for(int i=bankPage*20;i<Math.min(list.size(),(bankPage+1)*20);i++){
            JSONObject q=list.get(i);LinearLayout c=card();
            c.addView(text(q.optString("chapter")+" · "+QuizData.typeName(q.optString("type")),12,ui.accent()));
            String stem=q.optString("stem");TextView preview=text(stem.length()>150?stem.substring(0,150)+"…":stem,15,ui.text());preview.setPadding(0,ui.dp(9),0,ui.dp(9));c.addView(preview);
            JSONObject p=data.progress(q.optString("id"));hint(c,"练习 "+p.optInt("attempts")+" 次"+(p.optBoolean("star")?" · ★ 已收藏":""));
            button(c,"练习本题",false,()->startFiltered(Collections.singletonList(q)));
        }
        if(bankPage>0)button(body,"上一页",false,()->{bankPage--;bank();});
        hint(body,"第 "+(bankPage+1)+" / "+pages+" 页");
        if(bankPage+1<pages)button(body,"下一页",false,()->{bankPage++;bank();});
    }
    private void startFiltered(List<JSONObject> list){
        Runnable go=()->act(()->{data.start(list,false);save();practice();});
        if(data.current()!=null)ThemeStore.dialog(this).setTitle("替换未完成的练习队列？")
            .setMessage("已经提交的答题记录会保留。")
            .setNegativeButton("取消",null).setPositiveButton("开始",(d,w)->go.run()).show();else go.run();
    }
    private void importMenu(){
        ThemeStore.dialog(this).setTitle("导入题库")
            .setItems(new String[]{"选择 JSON / CSV 文件","粘贴 JSON / CSV","查看格式与下载模板"},(d,w)->{
                if(w==2){toolsMenu();return;}
                List<String> names=new ArrayList<>(data.subjects());names.add("＋ 新建科目");
                ThemeStore.dialog(this).setTitle("题目未填写科目时，归入")
                    .setItems(names.toArray(new String[0]),(dialog,which)->{
                        Runnable next=()->{importSubject=subject;if(w==0){Intent it=new Intent(Intent.ACTION_OPEN_DOCUMENT);it.addCategory(Intent.CATEGORY_OPENABLE);it.setType("*/*");startActivityForResult(it,IMPORT);}else paste();};
                        if(which==names.size()-1)newSubject(next);else {subject=names.get(which);next.run();}
                    }).show();
            }).show();
    }
    private void newSubject(Runnable next){
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("例如：电路、材料力学、信号与系统");
        AlertDialog dialog=ThemeStore.dialog(this).setTitle("新建科目").setView(input).setNegativeButton("取消",null).setPositiveButton("添加",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{String name=QuizData.subject(input.getText().toString());data.addSubject(name);save();subject=name;chapter="";dialog.dismiss();next.run();}
            catch(Exception e){input.setError(e.getMessage());}
        }));dialog.show();
    }
    private void paste(){
        EditText input=new EditText(this);input.setGravity(Gravity.TOP);input.setMinLines(8);input.setMaxLines(15);input.setHint("粘贴 UTF-8 JSON 数组、题库对象或带表头的 CSV");
        AlertDialog dialog=ThemeStore.dialog(this).setTitle("粘贴题库").setView(input).setNegativeButton("取消",null).setPositiveButton("检查并预览",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=input.getText().toString();if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>QuizStore.IMPORT_LIMIT){input.setError("最多 4MB");return;}
            dialog.dismiss();preview(value);
        }));dialog.show();
    }
    private void preview(String value){
        busy=true;ProgressDialog progress=ProgressDialog.show(this,"检查题库","正在校验题目与重复项…",true,false);
        QuizData snapshot=new QuizData(data.db.toString());String fallback=importSubject;
        new Thread(()->{
            try{
                QuizData.ImportResult result=snapshot.prepareImport(value,fallback,"导入题库");
                runOnUiThread(()->{progress.dismiss();busy=false;if(isFinishing()||isDestroyed())return;
                    if(result.added==0){ThemeStore.dialog(this).setTitle("没有新题目").setMessage("重复 "+result.duplicates+" 题，现有进度已保留。").setPositiveButton("知道了",null).show();return;}
                    Map<String,Integer> counts=new LinkedHashMap<>();JSONArray qs=result.candidate.questions();StringBuilder example=new StringBuilder();
                    for(int i=data.questions().length();i<qs.length();i++){
                        JSONObject q=qs.optJSONObject(i);String name=q.optString("subject");counts.put(name,counts.containsKey(name)?counts.get(name)+1:1);
                        if(i<data.questions().length()+3){String stem=q.optString("stem");example.append("\n\n").append(q.optString("subject")).append(" · ").append(q.optString("chapter")).append("\n").append(stem.length()>100?stem.substring(0,100)+"…":stem);}
                    }
                    String message="新增 "+result.added+" 题（"+counts.toString()+"）\n跳过重复 "+result.duplicates+" 题。\n已有答题记录与收藏保留。"+example;
                    ThemeStore.dialog(this).setTitle("确认导入预览").setMessage(message).setNegativeButton("取消",null)
                        .setPositiveButton("导入 "+result.added+" 题",(d,w)->act(()->{
                            // Rebuild against the latest model in case a draft was saved while the picker was open.
                            QuizData.ImportResult latest=data.prepareImport(value,fallback,"导入题库");QuizStore.commit(this,latest.candidate);data=latest.candidate;if(latest.added>0)subject=data.questions().optJSONObject(data.questions().length()-1).optString("subject");home();
                            Toast.makeText(this,"已导入 "+latest.added+" 题",Toast.LENGTH_SHORT).show();
                        })).show();
                });
            }catch(Exception e){runOnUiThread(()->{progress.dismiss();busy=false;if(!isFinishing()&&!isDestroyed())error(e);});}
        },"afs-quiz-import").start();
    }
    private void toolsMenu(){
        ThemeStore.dialog(this).setTitle("题库工具").setItems(new String[]{"导出示例模板（JSON）","导出当前全部题目（JSON）","导入格式说明"},(d,w)->{
            if(w==2){ThemeStore.dialog(this).setTitle("导入格式")
                .setMessage("JSON：数组，或 {name, subject, questions:[…]}。\n每题必填 stem（题干）、answer（答案）。\nsubject：自定义科目名称，如电路、材料力学、信号与系统；chapter：章节。\ntype：single / multiple / judge / short / solution。\n选择题 options 为文字数组，answer 为 A 或 AC；判断题答案为 A/B（正确/错误）。\nanalysis 为解析，source 为来源，id 选填。\n\nCSV：UTF-8 编码，首行 subject,chapter,type,stem,A,B,C,D,answer,analysis,source。含逗号或换行的单元格请加双引号。可用中文表头。\n\n公式用 $…$ 或 $$…$$ 包围；JSON 的反斜杠写成两个。\n最多 4MB、总共 5000 题。重复题跳过，错误行会提示且不写入。\nPDF、Word、图片需先整理为此格式。导出的题库只含题目，不含答题记录。")
                .setPositiveButton("知道了",null).show();return;}
            Intent it=new Intent(Intent.ACTION_CREATE_DOCUMENT);it.addCategory(Intent.CATEGORY_OPENABLE);it.setType("application/json");
            it.putExtra(Intent.EXTRA_TITLE,w==0?"afs-quiz-template.json":"afs-quiz-bank-"+date("yyyyMMdd")+".json");startActivityForResult(it,w==0?TEMPLATE:EXPORT);
        }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent intent){
        super.onActivityResult(request,result,intent);if(result!=RESULT_OK||intent==null||intent.getData()==null)return;
        Uri uri=intent.getData();
        if(request==IMPORT){
            busy=true;ProgressDialog progress=ProgressDialog.show(this,"读取题库","正在读取本地文件…",true,false);
            new Thread(()->{try(InputStream in=getContentResolver().openInputStream(uri)){
                String value=QuizStore.read(in,QuizStore.IMPORT_LIMIT);runOnUiThread(()->{progress.dismiss();busy=false;if(!isFinishing()&&!isDestroyed())preview(value);});
            }catch(Exception e){runOnUiThread(()->{progress.dismiss();busy=false;if(!isFinishing()&&!isDestroyed())error(e);});}},"afs-quiz-read").start();
        }else if(request==EXPORT||request==TEMPLATE){
            act(()->{try{
                String value;if(request==TEMPLATE){try(InputStream in=getAssets().open("quiz/template.json")){value=QuizStore.read(in,QuizStore.IMPORT_LIMIT);}}
                else {JSONObject root=new JSONObject();QuizData.put(root,"name","我的题库");JSONArray active=new JSONArray();for(JSONObject q:data.filter("","","all",""))active.put(q);QuizData.put(root,"questions",active);value=root.toString(2);}
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("无法写入文件");out.write(value.getBytes("UTF-8"));}
                Toast.makeText(this,"已导出",Toast.LENGTH_SHORT).show();
            }catch(Exception e){throw new IllegalArgumentException("导出失败："+e.getMessage(),e);}});
        }
    }
}
