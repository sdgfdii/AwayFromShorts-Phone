package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Emulator-only learning fixtures, restored even when an assertion fails. */
public final class LearningLinkDeviceTest extends Instrumentation {
    private int checks;
    private final List<Activity> opened = new ArrayList<>();
    private void runMain(Runnable action){
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){action.run();return;}
        CountDownLatch done=new CountDownLatch(1);final Throwable[] error={null};
        new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{try{action.run();}catch(Throwable e){error[0]=e;}finally{done.countDown();}});
        try{if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("Main-thread operation timed out");}catch(InterruptedException e){throw new RuntimeException(e);}
        if(error[0]!=null)throw new RuntimeException(error[0]);
    }
    private void settle()throws Exception{runMain(()->{});Thread.sleep(80);}
    private void phase(String label){Bundle update=new Bundle();update.putString("stream","QA: "+label+"\n");sendStatus(0,update);}
    private void check(boolean yes,String label) { checks++; if(!yes)throw new AssertionError(label); }
    private Object field(Object target,String name)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private void set(Object target,String name,Object value)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private Object call(Object target,String method,Class<?>[] types,Object...args)throws Exception {
        Class<?> owner=target instanceof Class?(Class<?>)target:target.getClass();
        Method m=owner.getDeclaredMethod(method,types);m.setAccessible(true);
        return m.invoke(target instanceof Class?null:target,args);
    }
    private Object link(String method,Class<?>[] types,Object...args)throws Exception {
        return call(Class.forName("com.afs.guard.LearningLink",true,getTargetContext().getClassLoader()),method,types,args);
    }
    private void resetMemory()throws Exception {
        Field cache=MemoryStore.class.getDeclaredField("cache");cache.setAccessible(true);cache.set(null,null);
    }
    private void quizFile(File file)throws Exception {
        call(QuizStore.class,"setTestFile",new Class<?>[]{File.class},file);
    }
    private void cardFile(File file)throws Exception {call(CardStore.class,"setTestFile",new Class<?>[]{File.class},file);}
    private byte[] bytes(File file)throws Exception {
        if(!file.exists())return null;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        return out.toByteArray();
    }
    private void write(File file,byte[] data)throws Exception {
        if(data==null){if(file.exists()&&!file.delete())throw new IOException("Cannot remove QA file");return;}
        try(OutputStream out=new FileOutputStream(file)){out.write(data);}
    }
    private Activity open(String name)throws Exception {
        return openIntent(new Intent().setClassName(getTargetContext(),"com.afs.guard."+name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private Activity openIntent(Intent intent)throws Exception{
        ActivityMonitor monitor=addMonitor(intent.getComponent().getClassName(),null,false);
        runMain(()->getTargetContext().startActivity(intent));Activity a=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);
        if(a==null)throw new AssertionError("Activity did not open: "+intent.getComponent());opened.add(a);settle();return a;
    }
    private boolean text(View v,String value) {
        if(v instanceof TextView&&value.contentEquals(((TextView)v).getText()))return true;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(text(((ViewGroup)v).getChildAt(i),value))return true;
        return false;
    }
    private android.widget.Spinner findSpinner(View root){if(root instanceof android.widget.Spinner)return (android.widget.Spinner)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){android.widget.Spinner found=findSpinner(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
    private TextView label(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=label(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private void capture(String name)throws Exception{Thread.sleep(1100);android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();check(bitmap!=null,"capture "+name);try(OutputStream image=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);}finally{bitmap.recycle();}}
    private boolean selected(Activity checkin,int type)throws Exception {
        List<?> entries=(List<?>)field(checkin,"cards");int selected=(Integer)field(checkin,"selected");
        return selected>=0&&selected<entries.size()&&(Integer)field(entries.get(selected),"type")==type;
    }
    private boolean hasEntry(Activity checkin,int type)throws Exception {
        for(Object entry:(List<?>)field(checkin,"cards"))if((Integer)field(entry,"type")==type)return true;
        return false;
    }
    private boolean waitAnimation(Activity screen,String key)throws Exception {
        long deadline=android.os.SystemClock.uptimeMillis()+1500;
        while(android.os.SystemClock.uptimeMillis()<deadline){
            final boolean[] active={false};
            runMain(()->{try{active[0]=key.equals(field(screen,"completionAnimation"));}catch(Exception e){throw new RuntimeException(e);}});
            if(active[0])return true;
            Thread.sleep(10);
        }
        return false;
    }
    private boolean clickText(String value)throws Exception{
        long deadline=android.os.SystemClock.uptimeMillis()+5000;
        while(android.os.SystemClock.uptimeMillis()<deadline){
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(value)){
                if(!value.contentEquals(node.getText()))continue;
                android.view.accessibility.AccessibilityNodeInfo target=node;while(target!=null&&!target.isClickable())target=target.getParent();
                if(target!=null&&target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))return true;
            }
            Thread.sleep(100);
        }return false;
    }
    private void restorePreferences(SharedPreferences prefs,Map<String,?> values) {
        SharedPreferences.Editor e=prefs.edit().clear();
        for(Map.Entry<String,?> entry:values.entrySet()){
            Object v=entry.getValue();String k=entry.getKey();
            if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);
            else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);
            else if(v instanceof String)e.putString(k,(String)v);
        }
        e.commit();
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Context c=getTargetContext();
        File memory=new File(c.getFilesDir(),"memory-v1.json"),quiz=new File(c.getCacheDir(),"afs-link-qa-quiz.json");
        File cards=new File(c.getCacheDir(),"afs-link-qa-cards.json");
        byte[] original=null;boolean backed=false;
        SharedPreferences prefs=c.getSharedPreferences("afs_learning_link",0);
        Map<String,?> oldPrefs=new HashMap<>(prefs.getAll());
        try {
            check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk_gphone"),"isolated emulator required");
            original=bytes(memory);
            // Recover only this exact disposable fixture after an interrupted emulator QA run.
            if(original!=null){MemoryData prior=new MemoryData(new String(original,"UTF-8"));
                if(prior.decks().length()==3&&prior.cards().length()==1
                        &&"QA 联动目标".equals(prior.decks().optJSONObject(0).optString("name"))
                        &&"QA 问题".equals(prior.cards().optJSONObject(0).optString("front")))
                    original=new MemoryData().db.toString().getBytes("UTF-8");}
            backed=true;prefs.edit().clear().commit();
            cards.delete();cardFile(cards);
            MemoryData m=new MemoryData();String deck=m.decks().optJSONObject(0).optString("id");
            m.settings(deck,"QA 联动目标",10,50,1,true);
            JSONObject card=MemoryData.newCard(deck,"qa","QA 问题","QA 答案","","");m.saveCard(card);
            write(memory,m.db.toString().getBytes("UTF-8"));resetMemory();
            Object disabled=link("memory",new Class<?>[]{Context.class},c);
            check(!(Boolean)field(disabled,"selected"),"legacy linked flag never opts in automatically");
            link("setMemoryEnabled",new Class<?>[]{Context.class,String.class,boolean.class},c,deck,true);
            Object pending=link("memory",new Class<?>[]{Context.class},c);
            check((Integer)field(pending,"target")==1&&(Integer)field(pending,"done")==0,"opt in exposes real daily memory target");
            Activity checkin=open("CheckinActivity");
            check(hasEntry(checkin,2),"linked memory card appears");
            check(hasEntry(checkin,4)==(CardStore.summaryPending(c,System.currentTimeMillis())!=null),"summary follows configured period");
            runMain(()->checkin.finish());settle();

            Activity memoryActivity=open("MemoryActivity");
            check(!text(memoryActivity.getWindow().getDecorView(),"本日记忆闪卡"),"memory root header removed");capture("cards-memory");
            runMain(()->{
                try {set(memoryActivity,"deck",deck);set(memoryActivity,"flashMode",false);
                    call(memoryActivity,"review",new Class<?>[0]);set(memoryActivity,"revealed",true);
                }catch(Exception e){throw new RuntimeException(e);}
            });
            ActivityMonitor monitor=addMonitor("com.afs.guard.CheckinActivity",null,false);
            runMain(()->{
                try {check((Boolean)call(memoryActivity,"grade",new Class<?>[]{String.class,int.class},card.optString("id"),4),"memory grade persists");}
                catch(Exception e){throw new RuntimeException(e);}
            });
            Activity completed=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);
            check(completed!=null,"memory completion returns to check-in");opened.add(completed);
            check(waitAnimation(completed,"memory"),"memory card owns completion animation");
            check(selected(completed,2),"memory card selected before exit");
            Thread.sleep(1100);settle();
            check(field(completed,"completionAnimation")==null&&!(Boolean)field(completed,"busy"),"memory exit completes and unlocks gestures");
            Object earned=link("memory",new Class<?>[]{Context.class},c);
            check((Boolean)call(earned,"complete",new Class<?>[0]),"memory progress comes from saved learning");

            runMain(()->{
                try {
                    completed.getIntent().putExtra("open","manage");
                    ((View)field(completed,"rootBox")).setVisibility(View.INVISIBLE);
                }catch(Exception e){throw new RuntimeException(e);}
            });

            link("setMemoryEnabled",new Class<?>[]{Context.class,String.class,boolean.class},c,deck,false);
            quizFile(quiz);
            String bank="[{\"id\":\"qa-link\",\"subject\":\"QA\",\"type\":\"single\",\"stem\":\"QA\",\"options\":[\"A\",\"B\"],\"answer\":\"A\"}]";
            QuizData data=new QuizData().prepareImport(bank,"QA","QA").candidate;
            data.start(data.filter("QA","","all",""),false);data.draft("A","");QuizStore.commit(c,data);
            link("configureQuiz",new Class<?>[]{Context.class,boolean.class,int.class},c,true,1);
            Activity quizActivity=open("QuizActivity");
            runMain(()->{
                try {call(quizActivity,"grade",new Class<?>[]{Boolean.class},new Object[]{null});}
                catch(Exception e){throw new RuntimeException(e);}
            });
            check(waitAnimation(completed,"quiz"),"existing check-in receives quiz completion intent");
            check(((View)field(completed,"rootBox")).getVisibility()==View.VISIBLE,"completion restores check-in cards after management mode");
            check(selected(completed,3),"quiz card selected for automatic exit");
            Thread.sleep(1100);settle();
            check(field(completed,"completionAnimation")==null,"quiz exit completes");
            check(QuizStore.snapshot(c).stats(StudyStore.today())[0]==1,"completion animation never creates extra answers");
            Object quizDone=link("quiz",new Class<?>[]{Context.class},c);
            check((Boolean)call(quizDone,"complete",new Class<?>[0]),"quiz threshold uses saved daily attempts");
            Activity history=open("HistoryActivity");Thread.sleep(1200);runMain(()->history.startActivity(new Intent(history,HistoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));Thread.sleep(800);
            check(text(history.getWindow().getDecorView(),"近三天 · 卡片类型"),"recent pie charts present");check(text(history.getWindow().getDecorView(),"最近流水  ▸"),"recent flow initially collapsed");capture("cards-history");
            runMain(()->check(text(history.getWindow().getDecorView(),"周期总结"),"cycle summary lives in check-in history"));
            runMain(()->{try{call(history,"showWeekly",new Class<?>[]{int.class},0);}catch(Exception e){throw new RuntimeException(e);}});
            settle();
            boolean report=false;
            long deadline=android.os.SystemClock.uptimeMillis()+4000;
            while(android.os.SystemClock.uptimeMillis()<deadline){
                android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                if(root!=null&&!root.findAccessibilityNodeInfosByText("总结卡").isEmpty()){report=true;break;}
                Thread.sleep(100);
            }
            check(report,"cycle report dialog renders");phase("report dialog");
            Activity manager=open("CardManagementActivity");
            for(String label:new String[]{"热爱卡  ›","记忆卡  ›","刷题卡  ›","目标卡  ›","禁忌卡  ›","总结卡  ›"})
                check(text(manager.getWindow().getDecorView(),label),"central card setting: "+label);
            android.graphics.Bitmap preview=getUiAutomation().takeScreenshot();
            if(preview!=null){try(OutputStream image=new FileOutputStream(new File(c.getExternalFilesDir(null),"card-management-1530.png"))){preview.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);}preview.recycle();}
            check(text(manager.getWindow().getDecorView(),"学习联动罚时"),"linkage penalty displayed in manager");
            runMain(()->{try{set(manager,"category","summary");call(manager,"render",new Class<?>[0]);View root=manager.getWindow().getDecorView();android.widget.Spinner spinner=findSpinner(root);spinner.setSelection(3);}catch(Exception error){throw new RuntimeException(error);}});settle();
            check(text(manager.getWindow().getDecorView(),"保存总结周期"),"custom summary save available");capture("cards-cycle");
            runMain(()->{TextView save=label(manager.getWindow().getDecorView(),"保存总结周期");save.performClick();});
            CycleSummary savedCycle=CardStore.snapshot(c).summary(System.currentTimeMillis());check(savedCycle.mode().equals("custom")||savedCycle.queued()!=null,"custom cycle saves from UI");
            long baseline=CardStore.snapshot(c).balanceMillis();CardStore.saveLove(c,null,"QA 热爱",60);
            String loveId=CardStore.snapshot(c).love().optJSONObject(0).optString("id");
            runMain(()->completed.startActivity(new Intent(completed,CheckinActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));settle();
            runMain(()->{
                try{call(completed,"refreshAsync",new Class<?>[0]);List<?> entries=(List<?>)field(completed,"cards");int found=-1;
                    for(int i=0;i<entries.size();i++)if((Integer)field(entries.get(i),"type")==5){found=i;break;}
                    check(found>=0,"love card appears in check-in deck");set(completed,"selected",found);call(completed,"render",new Class<?>[]{boolean.class},false);
                    call(completed,"onUp",new Class<?>[]{int.class,float.class,float.class},1,0f,-10000f);
                }catch(Exception e){throw new RuntimeException(e);}
            });
            check(CardStore.snapshot(c).earnedToday(System.currentTimeMillis()),"up-swipe persists the love reward");
            Thread.sleep(600);settle();check(!(Boolean)field(completed,"busy"),"love completion animation releases gesture lock");
            long credited=CardStore.snapshot(c).balanceMillis()-baseline;check(credited>=60000L&&credited<=3600000L,"lottery result is 1 to 60 minutes");
            check(!CardStore.claim(c,loveId)&&CardStore.snapshot(c).balanceMillis()==baseline+credited,"daily reward cannot be claimed twice");
            try{CardStore.saveLove(c,null,"too much",61);throw new AssertionError("hard cap missing");}catch(IllegalArgumentException expected){check(true,"60-minute cap enforced in storage");}
            long stamp=System.currentTimeMillis();CardStore.reconcile(c,stamp,true,stamp+20*60000L);
            check(CardStore.rewardUntil(c,stamp)>stamp,"reward reservation persisted");
            CardStore.reconcile(c,stamp+5*60000L,false,stamp+20*60000L);
            check(CardStore.snapshot(c).balanceMillis()==baseline+Math.max(0,credited-5*60000L),"unused reward refunded on device");
            phase("reward ledger");
            Calendar begin=Calendar.getInstance();begin.add(Calendar.DAY_OF_MONTH,-((begin.get(Calendar.DAY_OF_WEEK)+5)%7)-7);
            CardData missed=new CardData(begin.getTimeInMillis());missed.summary(begin.getTimeInMillis()).configure("daily",1,CycleSummary.day(begin.getTimeInMillis()),begin.getTimeInMillis());
            write(cards,missed.db.toString().getBytes("UTF-8"));cardFile(cards);
            CycleSummary.Period outstanding=CardStore.summaryPending(c,System.currentTimeMillis());
            check(outstanding!=null&&outstanding.holds(System.currentTimeMillis()),"missed summary holds after cycle rollover");
            Activity summaryHistory=open("HistoryActivity");
            runMain(()->{try{call(summaryHistory,"showWeekly",new Class<?>[]{int.class},0);}catch(Exception e){throw new RuntimeException(e);}});
            check(clickText("完成总结"),"summary confirmation button available for overdue period");
            check(waitAnimation(completed,"summary:"+outstanding.key),"summary completion returns to matching card and animates");
            phase("summary return animation");
            Thread.sleep(1100);settle();
            check(CardStore.snapshot(c).summary(System.currentTimeMillis()).recorded(outstanding.key)!=null,"completed summary persisted");
            check(CardStore.summaryHolding(c,System.currentTimeMillis()),"remaining missed periods cannot be bypassed by one confirmation");
            for(int i=0;i<30&&CardStore.summaryHolding(c,System.currentTimeMillis());i++){
                CycleSummary.Period task=CardStore.summaryPending(c,System.currentTimeMillis());CardStore.completeSummary(c,task.key,"QA 补完");
            }
            check(!CardStore.summaryHolding(c,System.currentTimeMillis()),"finishing pending periods clears summary gate");
            phase("overdue summaries completed");
            Activity memorySettings=openIntent(new Intent(c,MemoryActivity.class).putExtra("card_management",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            check((Boolean)field(memorySettings,"managementMode")&&"tools".equals(field(memorySettings,"page")),"memory settings open through card management");
            Activity quizSettings=openIntent(new Intent(c,QuizActivity.class).putExtra("card_management",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            check((Boolean)field(quizSettings,"managementMode")&&text(quizSettings.getWindow().getDecorView(),"刷题卡设置"),"quiz settings open through card management");
            result.putString("stream","Learning link device: "+checks+" checks passed\n");
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));sendStatus(0,result);}
        finally {
            try {
                if(backed){write(memory,original);resetMemory();restorePreferences(prefs,oldPrefs);}
                quizFile(null);quiz.delete();cardFile(null);cards.delete();StudyGate.invalidate(c);
                runMain(()->{for(Activity a:opened)if(a!=null&&!a.isFinishing())a.finish();});settle();
            }catch(Throwable restore){result.putString("stream",result.getString("stream","")+"Restore failed: "+restore);}
        }
        String output=result.getString("stream","");finish(output.startsWith("Learning link device:")&&!output.contains("Restore failed")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
