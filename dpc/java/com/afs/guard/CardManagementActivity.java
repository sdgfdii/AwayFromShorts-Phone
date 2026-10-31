package com.afs.guard;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Single settings entry under Guard; learning and check-in stay on their own pages. */
public class CardManagementActivity extends ThemedActivity {
    public static final String EXTRA_MANAGE="card_management";
    private Ui ui;private LinearLayout body;private String category="",renderedCategory;
    private Bundle categoryScroll=new Bundle();private boolean resumedOnce;
    @Override protected void onCreate(Bundle saved){if(Ui.isDark(this))setTheme(getResources().getIdentifier("NavThemeDark","style",getPackageName()));super.onCreate(saved);if(saved!=null&&saved.getBundle("category_scroll")!=null)categoryScroll=saved.getBundle("category_scroll");ui=new Ui(this);category=saved==null?getIntent().getStringExtra("category"):saved.getString("category","");if(category==null)category="";render();}
    @Override protected void onSaveInstanceState(Bundle out){if(renderedCategory!=null)categoryScroll.putBundle(renderedCategory,PageScrollState.capture(this));out.putBundle("category_scroll",categoryScroll);out.putString("category",category);super.onSaveInstanceState(out);}
    @Override protected void onResume(){super.onResume();if(ui!=null&&ui.dark!=Ui.isDark(this)){recreate();return;}if(!resumedOnce){resumedOnce=true;return;}if(ui!=null)render();}
    @Override public void onBackPressed(){if(category.isEmpty())super.onBackPressed();else{category="";render();}}
    private void base(String title){
        ScrollView scroll=new ScrollView(this);ui.page(scroll);scroll.setFillViewport(true);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(32));scroll.addView(body);
        TextView back=ui.sub(category.isEmpty()?T.s("‹  守卫"):T.s("‹  卡管理"));back.setTextColor(ui.accent());back.setPadding(0,ui.dp(8),0,ui.dp(20));back.setOnClickListener(v->onBackPressed());body.addView(back);body.addView(ui.title(title));
        setContentView(FloatingNav.wrap(this,scroll,4));FloatingNav.fullscreen(this);
    }
    private void hint(LinearLayout box,String text){TextView t=ui.sub(text);t.setPadding(0,ui.dp(8),0,ui.dp(10));box.addView(t);}
    private LinearLayout card(){LinearLayout c=ui.card();body.addView(c,ui.gap(12));return c;}
    private TextView button(LinearLayout box,String label,boolean primary,Runnable action){
        TextView t=ui.sub(label);SwissTypography.role(t,SwissTypography.Role.BUTTON);t.setTextColor(primary?ui.onAccent():ui.accent());t.setGravity(Gravity.CENTER);t.setPadding(ui.dp(12),ui.dp(14),ui.dp(12),ui.dp(14));t.setBackground(ui.round(primary?ui.accent():ui.surface2(),ui.button(14)));
        box.addView(t,ui.gap(10));t.setOnClickListener(v->{try{action.run();}catch(RuntimeException e){toast(e.getMessage());}});return t;
    }
    private void toast(String value){Toast.makeText(this,value==null?T.s("请稍后重试"):value,Toast.LENGTH_SHORT).show();}
    private void render(){if(renderedCategory!=null)categoryScroll.putBundle(renderedCategory,PageScrollState.capture(this));try{if("love".equals(category))love();else if("summary".equals(category))summary();else if("checkin".equals(category))checkinSettings();else home();}catch(RuntimeException e){base(T.s("卡管理"));hint(body,e.getMessage());}renderedCategory=category;PageScrollState.restore(this,categoryScroll.getBundle(category));}
    private void home(){
        category="";base(T.s("卡管理"));hint(body,T.s("统一管理卡片、学习联动和奖励。"));
        CardData data=CardStore.snapshot(this);LinearLayout balance=card();balance.addView(ui.title(minutes(data.balanceMillis())));hint(balance,T.s("热爱奖励余额 · 跨天累计 · 自动抵扣定时冻结"));
        long until=data.rewardUntil(System.currentTimeMillis());if(until>0)hint(balance,T.s("本次减免剩余 ")+minutes(until-System.currentTimeMillis()));
        LinearLayout linkage=card();linkage.addView(ui.title(T.s("学习联动罚时")));
        hint(linkage,T.s("每日20:00检查；未完成则在各应用自身冻结结束后接续，持续至任务完成，不设固定分钟数。正在放行的应用接在下一段冻结之后，不提前打断放行。待接续跨零点保留；完成任务解除。热爱奖励不抵扣。"));
        long now=System.currentTimeMillis();String reason=StudyGate.holdReason(this,Calendar.getInstance());
        hint(linkage,reason.isEmpty()?T.s("当前无额外冻结"):reason);
        if(reason.startsWith("学习联动")){Calendar start=Calendar.getInstance();start.set(Calendar.HOUR_OF_DAY,20);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);
            CycleSummary.Period pending=data.summary(now).pending(now);long since=pending!=null&&pending.holds(now)?pending.dueAt:start.getTimeInMillis();
            hint(linkage,T.s("距本次检查已过 ")+minutes(Math.max(0,now-since))+T.s(" · 完成后解除"));}
        row(T.s("打卡设置"),T.s("外观 · 字体 · 同步 · 上传"),()->{category="checkin";render();});
        row(T.s("热爱卡"),T.s("完成热爱，积累冻结减免"),()->{category="love";render();});
        row(T.s("记忆卡"),T.s("抽取、学习计划、学科与卡片"),()->startActivity(new Intent(this,MemoryActivity.class).putExtra(EXTRA_MANAGE,true)));
        row(T.s("刷题卡"),T.s("每日目标、学习联动与题库"),()->startActivity(new Intent(this,QuizActivity.class).putExtra(EXTRA_MANAGE,true)));
        row(T.s("目标卡"),T.s("创建目标、默认完成量与卡面设置"),()->startActivity(new Intent(this,CheckinActivity.class).putExtra(CheckinActivity.EXTRA_OPEN,"manage").putExtra(EXTRA_MANAGE,true)));
        row(T.s("禁忌卡"),T.s("禁忌内容与惩罚时长"),()->startActivity(new Intent(this,CheckinActivity.class).putExtra(CheckinActivity.EXTRA_OPEN,"taboo").putExtra(EXTRA_MANAGE,true)));
        row(T.s("总结卡"),data.summary(System.currentTimeMillis()).activeLabels()+T.s(" · 周期末必须完成"),()->{category="summary";render();});
    }
    private void checkinSettings(){base(T.s("打卡设置"));
        row(T.s("打卡外观"),T.s("背景图片与亚克力"),()->startActivity(new Intent(this,CheckinActivity.class).putExtra(CheckinActivity.EXTRA_OPEN,"appearance")));
        row(T.s("打卡字体"),T.s("字体选择与导入"),()->startActivity(new Intent(this,CheckinActivity.class).putExtra(CheckinActivity.EXTRA_OPEN,"font")));
        row(T.s("同步设置"),SyncSettings.state(this),()->SyncSettings.show(this));
        row(T.s("上传打卡数据"),T.s("手动上传最新本机台账"),()->{toast(T.s("正在上传"));new Thread(()->{boolean ok=Hub.pushNowSync(this);runOnUiThread(()->toast(ok?T.s("已上传 ")+SyncSettings.at(this)+" · rev "+Hub.ledgerRev(this):(SyncConfig.configured(this)?T.s("上传失败，检查网络后重试"):T.s("云端同步未启用或已暂停，请点「同步设置」"))));},"afs-card-upload").start();});
    }
    private void row(String title,String description,Runnable action){LinearLayout box=card();TextView t=ui.title(title+"  ›");SwissTypography.role(t,SwissTypography.Role.SECTION);box.addView(t);hint(box,description);String[] types={T.s("目标卡"),T.s("禁忌卡"),T.s("记忆卡"),T.s("刷题卡"),T.s("热爱卡"),T.s("总结卡")};for(int i=0;i<types.length;i++)if(types[i].equals(title))CardPalette.apply(box,i,false);box.setOnClickListener(v->{try{action.run();}catch(RuntimeException e){toast(e.getMessage());}});}
    private void love(){
        base(T.s("热爱卡"));CardData data=CardStore.snapshot(this);long now=System.currentTimeMillis();
        LinearLayout wallet=card();wallet.addView(ui.title(minutes(data.balanceMillis())));hint(wallet,T.s("可用奖励余额"));hint(wallet,T.s("每天最多完成一张热爱卡，等概率抽取 1–60 分钟。余额自动抵扣后续定时冻结，学习联动和禁忌惩罚仍然有效。"));
        hint(wallet,data.earnedToday(now)?T.s("今天的热爱奖励已领取"):T.s("今天还可完成一张热爱卡，在打卡页面上滑完成。"));
        button(body,T.s("＋ 创建热爱卡"),true,()->editLove(null));
        int count=0;for(int i=0;i<data.love().length();i++){JSONObject c=data.love().optJSONObject(i);if(c.optBoolean("archived"))continue;count++;
            final String id=c.optString("id");row(c.optString("title"),T.s("抽奖奖励 1–60 分钟 · 点击修改"),()->editLove(id));}
        if(count==0)hint(body,T.s("添加一件自己热爱的事，例如练琴、运动或绘画。"));
        button(body,T.s("奖励明细"),false,()->rewardHistory());button(body,T.s("热爱卡回收站"),false,()->loveTrash());
    }
    private void editLove(String id){
        JSONObject c=id==null?null:CardStore.snapshot(this).find(id);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(ui.dp(24),0,ui.dp(24),0);
        EditText title=new EditText(this);title.setHint(T.s("热爱名称"));title.setText(c==null?"":c.optString("title"));box.addView(title);
        hint(box,T.s("完成后等概率抽取 1–60 分钟，每天仅一张，单次上限固定。"));
        AlertDialog.Builder builder=ThemeStore.dialog(this).setTitle(id==null?T.s("创建热爱卡"):T.s("编辑热爱卡")).setView(box).setPositiveButton(T.s("保存"),null).setNegativeButton(T.s("取消"),null);
        if(id!=null)builder.setNeutralButton(T.s("移入回收站"),(d,w)->ThemeStore.dialog(this).setTitle(T.s("移入回收站？")).setMessage(T.s("已获得的奖励与余额会保留。")).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("移入"),(a,b)->{CardStore.archive(this,id,true);render();}).show());
        AlertDialog dialog=builder.create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{CardStore.saveLove(this,id,title.getText().toString(),60);dialog.dismiss();render();}catch(RuntimeException e){toast(e.getMessage());}}));dialog.show();
    }
    private void loveTrash(){CardData data=CardStore.snapshot(this);List<String> ids=new ArrayList<>(),names=new ArrayList<>();for(int i=0;i<data.love().length();i++){JSONObject c=data.love().optJSONObject(i);if(c.optBoolean("archived")){ids.add(c.optString("id"));names.add(T.s("恢复「")+c.optString("title")+"」");}}
        if(ids.isEmpty()){toast(T.s("回收站为空"));return;}ThemeStore.dialog(this).setTitle(T.s("热爱卡回收站")).setItems(names.toArray(new String[0]),(d,w)->{CardStore.archive(this,ids.get(w),false);render();}).setNegativeButton(T.s("关闭"),null).show();}
    private void rewardHistory(){CardData data=CardStore.snapshot(this);JSONArray earned=data.earned();StringBuilder text=new StringBuilder(T.s("可用余额 ")+minutes(data.balanceMillis())+"\n");for(int i=earned.length()-1;i>=Math.max(0,earned.length()-30);i--){JSONObject e=earned.optJSONObject(i);text.append("\n").append(e.optString("day")).append(" · ").append(e.optString("title")).append(" +").append(e.optInt("minutes")).append(T.s(" 分钟"));}ThemeStore.dialog(this).setTitle(T.s("热爱奖励")).setMessage(text.toString()).setPositiveButton(T.s("关闭"),null).show();}
    private void summary(){
        base(T.s("总结卡"));CycleSummary summary=CardStore.snapshot(this).summary(System.currentTimeMillis());hint(body,T.s("周期最后一天出现总结卡，20:00 后未完成会延长学习联动冻结。跨周期继续保留待办，完成后解除这项联动。"));
        hint(body,T.s("直接填写周期天数，例如21天、40天。自定义周期可与每周、每月总结同时启用；各周期分别完成，取消周期不会清除已经到期的总结。"));
        String[] labels={T.s("每天总结"),T.s("每周总结"),T.s("每月总结"),T.s("启用自定义天数")},keys={"daily","weekly","monthly","custom"};CheckBox[] choices=new CheckBox[keys.length];
        for(int i=0;i<keys.length;i++){choices[i]=new CheckBox(this);choices[i].setText(labels[i]);choices[i].setTextColor(ui.text());choices[i].setChecked(summary.enabled(keys[i]));}
        CycleSummary custom=summary.settings("custom");JSONObject candidate=custom.queued();final JSONObject queued=candidate!=null&&"custom".equals(candidate.optString("mode"))?candidate:null;boolean hasCustom="custom".equals(custom.mode());
        LinearLayout box=card();box.addView(ui.title(T.s("自定义天数")));hint(box,T.s("每隔你填写的天数完成一次总结。填写天数会自动勾选启用。"));box.addView(choices[3]);
        LinearLayout interval=new LinearLayout(this);interval.setGravity(Gravity.CENTER_VERTICAL);interval.addView(ui.sub(T.s("每")));
        EditText days=new EditText(this);days.setTag("summary_cycle_days");days.setContentDescription(T.s("总结周期天数"));days.setInputType(2);days.setSingleLine(true);days.setGravity(Gravity.CENTER);days.setHint(T.s("例如21或40"));days.setText(queued!=null?String.valueOf(queued.optInt("days")):hasCustom?String.valueOf(custom.days()):"");interval.addView(days,new LinearLayout.LayoutParams(0,-2,1));interval.addView(ui.sub(T.s("天总结一次")));box.addView(interval);hint(box,T.s("周期天数：1–365天，可输入21、40等整数。"));
        hint(box,T.s("周期开始日期"));EditText anchor=new EditText(this);anchor.setTag("summary_cycle_anchor");anchor.setContentDescription(T.s("总结周期开始日期"));anchor.setSingleLine(true);anchor.setHint("yyyy-MM-dd");anchor.setText(queued!=null?queued.optString("anchor"):hasCustom?custom.anchor():CycleSummary.day(System.currentTimeMillis()));box.addView(anchor);
        TextView datePicker=ui.sub(T.s("选择周期首日 ›"));datePicker.setTextColor(ui.accent());datePicker.setPadding(0,ui.dp(12),0,ui.dp(12));box.addView(datePicker);
        datePicker.setOnClickListener(v->{Calendar selectedDate=Calendar.getInstance();try{String[] parts=anchor.getText().toString().split("-");selectedDate.set(Integer.parseInt(parts[0]),Integer.parseInt(parts[1])-1,Integer.parseInt(parts[2]));}catch(RuntimeException ignored){}
            new android.app.DatePickerDialog(this,(picker,y,m,d)->anchor.setText(String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d)),selectedDate.get(Calendar.YEAR),selectedDate.get(Calendar.MONTH),selectedDate.get(Calendar.DAY_OF_MONTH)).show();});
        TextView preview=ui.sub("");preview.setTag("summary_cycle_preview");preview.setPadding(0,ui.dp(8),0,ui.dp(12));box.addView(preview);
        android.text.TextWatcher watch=new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(!days.getText().toString().trim().isEmpty())choices[3].setChecked(true);summaryPreview(days,anchor,preview);}public void afterTextChanged(android.text.Editable e){}};days.addTextChangedListener(watch);anchor.addTextChangedListener(watch);summaryPreview(days,anchor,preview);
        LinearLayout presets=card();presets.addView(ui.title(T.s("同时启用其他周期")));hint(presets,T.s("可选，可与自定义天数叠加。"));for(int i=0;i<3;i++)presets.addView(choices[i]);
        button(body,T.s("保存总结周期"),true,()->{Set<String> selected=new LinkedHashSet<>();for(int i=0;i<keys.length;i++)if(choices[i].isChecked())selected.add(keys[i]);int count=7;if(selected.contains("custom"))try{count=Integer.parseInt(days.getText().toString().trim());}catch(NumberFormatException e){throw new IllegalArgumentException(T.s("周期天数须为 1–365 的整数"));}CardStore.configureSummaries(this,selected,count,anchor.getText().toString().trim());syncGuard();toast(T.s("总结周期已保存"));render();});
        for(CycleSummary.Period pending:summary.allPending(System.currentTimeMillis()))hint(body,pending.cycleLabel+T.s("待完成：")+pending.start+T.s(" 至 ")+pending.through+T.s("。需单独完成这张总结卡。"));
        if(queued!=null)hint(body,T.s("自定义周期修改将在旧总结补完后生效：每 ")+queued.optInt("days")+T.s(" 天，从 ")+queued.optString("anchor")+T.s(" 起算"));
        button(body,T.s("查看总结"),false,()->startActivity(new Intent(this,HistoryActivity.class).putExtra(HistoryActivity.EXTRA_WEEKLY,true)));
    }
    private void summaryPreview(EditText days,EditText anchor,TextView preview){
        String value=days.getText().toString().trim();if(value.isEmpty()){preview.setText(T.s("例如填写21：每21天总结一次；填写40：每40天总结一次。"));return;}
        try{int count=Integer.parseInt(value);if(count<1||count>365)throw new IllegalArgumentException();String date=anchor.getText().toString().trim();SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.US);format.setLenient(false);if(!date.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();Calendar end=Calendar.getInstance();end.setTime(format.parse(date));end.add(Calendar.DAY_OF_MONTH,count-1);preview.setText(T.s("每")+count+T.s("天总结一次\n首个周期：")+date+T.s(" 至 ")+format.format(end.getTime())+T.s("\n此后每")+count+T.s("天重复，旧总结未完成会继续保留。"));}
        catch(Exception error){preview.setText(T.s("请输入1–365的整数天数和有效的开始日期。"));}
    }
    private void syncGuard(){final android.content.Context c=getApplicationContext();new Thread(()->{try{Guard.apply(c);}catch(RuntimeException ignored){ }},"afs-card-settings").start();}
    public static String minutes(long millis){return String.format(Locale.US,T.s("%.1f 分钟"),Math.max(0,millis)/60000.0);}
}
