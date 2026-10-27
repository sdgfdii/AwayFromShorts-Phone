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
    private Ui ui;private LinearLayout body;private String category="";
    @Override protected void onCreate(Bundle saved){if(Ui.isDark(this))setTheme(getResources().getIdentifier("NavThemeDark","style",getPackageName()));super.onCreate(saved);ui=new Ui(this);category=saved==null?getIntent().getStringExtra("category"):saved.getString("category","");if(category==null)category="";render();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("category",category);super.onSaveInstanceState(out);}
    @Override protected void onResume(){super.onResume();if(ui!=null&&ui.dark!=Ui.isDark(this)){recreate();return;}if(ui!=null)render();}
    @Override public void onBackPressed(){if(category.isEmpty())super.onBackPressed();else{category="";render();}}
    private void base(String title){
        ScrollView scroll=new ScrollView(this);ui.page(scroll);scroll.setFillViewport(true);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(32));scroll.addView(body);
        TextView back=ui.sub(category.isEmpty()?"‹  守卫":"‹  卡管理");back.setTextColor(ui.accent());back.setPadding(0,ui.dp(8),0,ui.dp(20));back.setOnClickListener(v->onBackPressed());body.addView(back);body.addView(ui.title(title));
        setContentView(FloatingNav.wrap(this,scroll,4));FloatingNav.fullscreen(this);
    }
    private void hint(LinearLayout box,String text){TextView t=ui.sub(text);t.setPadding(0,ui.dp(8),0,ui.dp(10));box.addView(t);}
    private LinearLayout card(){LinearLayout c=ui.card();body.addView(c,ui.gap(12));return c;}
    private TextView button(LinearLayout box,String label,boolean primary,Runnable action){
        TextView t=ui.sub(label);SwissTypography.role(t,SwissTypography.Role.BUTTON);t.setTextColor(primary?ui.onAccent():ui.accent());t.setGravity(Gravity.CENTER);t.setPadding(ui.dp(12),ui.dp(14),ui.dp(12),ui.dp(14));t.setBackground(ui.round(primary?ui.accent():ui.surface2(),14));
        box.addView(t,ui.gap(10));t.setOnClickListener(v->{try{action.run();}catch(RuntimeException e){toast(e.getMessage());}});return t;
    }
    private void toast(String value){Toast.makeText(this,value==null?"请稍后重试":value,Toast.LENGTH_SHORT).show();}
    private void render(){try{if("love".equals(category))love();else if("summary".equals(category))summary();else home();}catch(RuntimeException e){base("卡管理");hint(body,e.getMessage());}}
    private void home(){
        category="";base("卡管理");hint(body,"统一管理卡片、学习联动和奖励。");
        CardData data=CardStore.snapshot(this);LinearLayout balance=card();balance.addView(ui.title(minutes(data.balanceMillis())));hint(balance,"热爱奖励余额 · 跨天累计 · 自动抵扣定时冻结");
        long until=data.rewardUntil(System.currentTimeMillis());if(until>0)hint(balance,"本次减免剩余 "+minutes(until-System.currentTimeMillis()));
        row("热爱卡","完成热爱，积累冻结减免",()->{category="love";render();});
        row("记忆卡","抽取、学习计划、学科与卡片",()->startActivity(new Intent(this,MemoryActivity.class).putExtra(EXTRA_MANAGE,true)));
        row("刷题卡","每日目标、学习联动与题库",()->startActivity(new Intent(this,QuizActivity.class).putExtra(EXTRA_MANAGE,true)));
        row("目标卡","创建目标、默认完成量与卡面设置",()->startActivity(new Intent(this,CheckinActivity.class).putExtra(CheckinActivity.EXTRA_OPEN,"manage").putExtra(EXTRA_MANAGE,true)));
        row("禁忌卡","禁忌内容与惩罚时长",()->startActivity(new Intent(this,CheckinActivity.class).putExtra(CheckinActivity.EXTRA_OPEN,"taboo").putExtra(EXTRA_MANAGE,true)));
        row("总结卡",data.summary(System.currentTimeMillis()).label()+" · 周期末必须完成",()->{category="summary";render();});
    }
    private void row(String title,String description,Runnable action){LinearLayout box=card();TextView t=ui.title(title+"  ›");SwissTypography.role(t,SwissTypography.Role.SECTION);box.addView(t);hint(box,description);box.setOnClickListener(v->{try{action.run();}catch(RuntimeException e){toast(e.getMessage());}});}
    private void love(){
        base("热爱卡");CardData data=CardStore.snapshot(this);long now=System.currentTimeMillis();
        LinearLayout wallet=card();wallet.addView(ui.title(minutes(data.balanceMillis())));hint(wallet,"可用奖励余额");hint(wallet,"每天最多完成一张热爱卡，每次最多奖励 60 分钟。余额自动抵扣后续定时冻结，学习联动和禁忌惩罚仍然有效。");
        hint(wallet,data.earnedToday(now)?"今天的热爱奖励已领取":"今天还可完成一张热爱卡，在打卡页面上滑完成。");
        button(body,"＋ 创建热爱卡",true,()->editLove(null));
        int count=0;for(int i=0;i<data.love().length();i++){JSONObject c=data.love().optJSONObject(i);if(c.optBoolean("archived"))continue;count++;
            final String id=c.optString("id");row(c.optString("title"),"完成奖励 "+c.optInt("minutes")+" 分钟 · 点击修改",()->editLove(id));}
        if(count==0)hint(body,"添加一件自己热爱的事，例如练琴、运动或绘画。");
        button(body,"奖励明细",false,()->rewardHistory());button(body,"热爱卡回收站",false,()->loveTrash());
    }
    private void editLove(String id){
        JSONObject c=id==null?null:CardStore.snapshot(this).find(id);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(ui.dp(24),0,ui.dp(24),0);
        EditText title=new EditText(this);title.setHint("热爱名称");title.setText(c==null?"":c.optString("title"));box.addView(title);
        EditText reward=new EditText(this);reward.setInputType(2);reward.setHint("奖励分钟数（1–60）");reward.setText(c==null?"30":String.valueOf(c.optInt("minutes")));box.addView(reward);hint(box,"每日只能领取一张卡的奖励，上限 60 分钟固定生效。");
        AlertDialog.Builder builder=ThemeStore.dialog(this).setTitle(id==null?"创建热爱卡":"编辑热爱卡").setView(box).setPositiveButton("保存",null).setNegativeButton("取消",null);
        if(id!=null)builder.setNeutralButton("移入回收站",(d,w)->ThemeStore.dialog(this).setTitle("移入回收站？").setMessage("已获得的奖励与余额会保留。").setNegativeButton("取消",null).setPositiveButton("移入",(a,b)->{CardStore.archive(this,id,true);render();}).show());
        AlertDialog dialog=builder.create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{CardStore.saveLove(this,id,title.getText().toString(),Integer.parseInt(reward.getText().toString()));dialog.dismiss();render();}catch(RuntimeException e){toast(e instanceof NumberFormatException?"奖励须为 1–60 的整数":e.getMessage());}}));dialog.show();
    }
    private void loveTrash(){CardData data=CardStore.snapshot(this);List<String> ids=new ArrayList<>(),names=new ArrayList<>();for(int i=0;i<data.love().length();i++){JSONObject c=data.love().optJSONObject(i);if(c.optBoolean("archived")){ids.add(c.optString("id"));names.add("恢复「"+c.optString("title")+"」");}}
        if(ids.isEmpty()){toast("回收站为空");return;}ThemeStore.dialog(this).setTitle("热爱卡回收站").setItems(names.toArray(new String[0]),(d,w)->{CardStore.archive(this,ids.get(w),false);render();}).setNegativeButton("关闭",null).show();}
    private void rewardHistory(){CardData data=CardStore.snapshot(this);JSONArray earned=data.earned();StringBuilder text=new StringBuilder("可用余额 "+minutes(data.balanceMillis())+"\n");for(int i=earned.length()-1;i>=Math.max(0,earned.length()-30);i--){JSONObject e=earned.optJSONObject(i);text.append("\n").append(e.optString("day")).append(" · ").append(e.optString("title")).append(" +").append(e.optInt("minutes")).append(" 分钟");}ThemeStore.dialog(this).setTitle("热爱奖励").setMessage(text.toString()).setPositiveButton("关闭",null).show();}
    private void summary(){
        base("总结卡");CycleSummary summary=CardStore.snapshot(this).summary(System.currentTimeMillis());hint(body,"周期最后一天出现总结卡，20:00 后未完成会延长学习联动冻结。跨周期继续保留待办，完成后解除这项联动。");
        LinearLayout box=card();String[] labels={"每天","每周","每月","自定义天数"},keys={"daily","weekly","monthly","custom"};Spinner mode=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);mode.setAdapter(adapter);int selected=Arrays.asList(keys).indexOf(summary.mode());mode.setSelection(Math.max(0,selected));box.addView(mode);
        EditText days=new EditText(this);days.setInputType(2);days.setHint("自定义周期天数（1–365）");days.setText(String.valueOf(summary.days()));box.addView(days);
        EditText anchor=new EditText(this);anchor.setSingleLine(true);anchor.setHint("自定义周期首日 yyyy-MM-dd");anchor.setText(summary.anchor());box.addView(anchor);
        Runnable fields=()->{boolean custom=mode.getSelectedItemPosition()==3;days.setVisibility(custom?View.VISIBLE:View.GONE);anchor.setVisibility(custom?View.VISIBLE:View.GONE);};mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> a,View v,int p,long id){fields.run();}public void onNothingSelected(android.widget.AdapterView<?> a){}});fields.run();
        button(box,"保存总结周期",true,()->{int count;try{count=Integer.parseInt(days.getText().toString());}catch(NumberFormatException e){throw new IllegalArgumentException("周期天数须为 1–365 的整数");}CardStore.configureSummary(this,keys[mode.getSelectedItemPosition()],count,anchor.getText().toString().trim());syncGuard();toast("总结周期已保存");render();});
        CycleSummary.Period pending=summary.pending(System.currentTimeMillis());if(pending!=null)hint(body,"待完成："+pending.start+" 至 "+pending.through);
        button(body,"查看总结",false,()->startActivity(new Intent(this,HistoryActivity.class).putExtra(HistoryActivity.EXTRA_WEEKLY,true)));
    }
    private void syncGuard(){final android.content.Context c=getApplicationContext();new Thread(()->{try{Guard.apply(c);}catch(RuntimeException ignored){ }},"afs-card-settings").start();}
    public static String minutes(long millis){return String.format(Locale.US,"%.1f 分钟",Math.max(0,millis)/60000.0);}
}
