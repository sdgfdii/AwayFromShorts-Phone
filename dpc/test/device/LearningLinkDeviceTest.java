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
    private android.widget.ScrollView pageScroll(Activity activity){return findScroll(activity.getWindow().getDecorView());}
    private android.widget.ScrollView findScroll(View root){if(root instanceof android.widget.ScrollView)return (android.widget.ScrollView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){android.widget.ScrollView found=findScroll(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
    private Activity child(Activity parent,Class<?> type)throws Exception{ActivityMonitor monitor=addMonitor(type.getName(),null,false);runMain(()->parent.startActivity(new Intent(parent,type)));Activity result=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);check(result!=null,"settings child starts");opened.add(result);Thread.sleep(400);return result;}
    private Activity recreatePage(Activity parent)throws Exception{ActivityMonitor monitor=addMonitor(parent.getClass().getName(),null,false);runMain(parent::recreate);Activity result=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);check(result!=null,"page recreated");opened.add(result);Thread.sleep(700);return result;}
    private void positions()throws Exception{
        Activity main=openIntent(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK).putExtra("from_bottom_nav",true));Thread.sleep(700);
        runMain(()->pageScroll(main).scrollTo(0,500));Thread.sleep(150);int mainY=pageScroll(main).getScrollY();check(mainY>0,"guard page scrolls");
        Activity theme=child(main,ThemeActivity.class);runMain(theme::finish);Thread.sleep(700);check(Math.abs(pageScroll(main).getScrollY()-mainY)<3,"return from global settings keeps guard offset");
        Activity recreated=recreatePage(main);check(Math.abs(pageScroll(recreated).getScrollY()-mainY)<3,"guard recreation keeps offset");
        Activity manager=openIntent(new Intent(getTargetContext(),CardManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));Thread.sleep(500);
        runMain(()->pageScroll(manager).scrollTo(0,500));Thread.sleep(100);int managerY=pageScroll(manager).getScrollY();check(managerY>0,"card manager scrolls");
        Activity child=child(manager,ThemeActivity.class);runMain(child::finish);Thread.sleep(700);check(Math.abs(pageScroll(manager).getScrollY()-managerY)<3,"card manager rebuild keeps offset after external child");
        runMain(()->((View)label(manager.getWindow().getDecorView(),"打卡设置  ›").getParent()).performClick());Thread.sleep(200);runMain(manager::onBackPressed);Thread.sleep(300);check(Math.abs(pageScroll(manager).getScrollY()-managerY)<3,"return from card settings keeps category offset");
        Activity restored=recreatePage(manager);check(Math.abs(pageScroll(restored).getScrollY()-managerY)<3,"card manager recreation keeps offset");
        Class<?> palette=Class.forName("com.afs.guard.CardPalette",true,getTargetContext().getClassLoader());Field colors=palette.getDeclaredField("COLORS");colors.setAccessible(true);check(((int[])colors.get(null))[5]==0xFFE85B61,"summary palette is red");
    }
    private android.view.accessibility.AccessibilityNodeInfo scrollable(android.view.accessibility.AccessibilityNodeInfo node){if(node==null)return null;if(node.isScrollable())return node;for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo found=scrollable(node.getChild(i));if(found!=null)return found;}return null;}
    private void rules(Activity activity)throws Exception{
        runMain(()->{try{call(activity,"flashSettings",new Class<?>[0]);}catch(Exception error){throw new RuntimeException(error);}});Thread.sleep(500);
        boolean heading=false,fsrs=false,progress=false,parameters=false;
        for(int i=0;i<24;i++){
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null){Thread.sleep(150);continue;}
            heading|=!root.findAccessibilityNodeInfosByText("记忆规律与复习规则").isEmpty();fsrs|=!root.findAccessibilityNodeInfosByText("FSRS-6").isEmpty();progress|=!root.findAccessibilityNodeInfosByText("进度怎样计算").isEmpty();parameters|=!root.findAccessibilityNodeInfosByText("最大间隔").isEmpty();
            android.view.accessibility.AccessibilityNodeInfo scroll=scrollable(root);if(scroll==null||!scroll.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))break;Thread.sleep(200);
        }
        check(heading&&fsrs,"flash settings expose the actual scheduling rule");check(progress&&parameters,"rules include progress semantics and current per-deck parameters");capture("memory-rules-bottom");check(clickText("取消"),"rules remain read-only and settings can be cancelled");Thread.sleep(200);
    }
    private void regressions(Context c)throws Exception{
        android.content.SharedPreferences taboo=c.getSharedPreferences("afs_taboo",0),guard=c.getSharedPreferences("afs_guard",0),quota=c.getSharedPreferences("afs_usage_quota",0);
        Map<String,?> oldTaboo=new HashMap<>(taboo.getAll()),oldGuard=new HashMap<>(guard.getAll()),oldQuota=new HashMap<>(quota.getAll());
        boolean permission=AppUsageQuota.permission(c);Class<?> allowance=Class.forName("com.afs.guard.AppUsageQuota",true,c.getClassLoader());Field cache=allowance.getDeclaredField("cached"),stamp=allowance.getDeclaredField("cachedAt");cache.setAccessible(true);stamp.setAccessible(true);Object oldCache=cache.get(null);long oldStamp=stamp.getLong(null);
        try{
            taboo.edit().clear().putString("items","[\"QA 未认领\"]").commit();StudyGate.invalidate(c);
            Activity checkin=openIntent(new Intent(c,CheckinActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK).putExtra("from_bottom_nav",true));Thread.sleep(800);
            check(StudyGate.tabooLog(c,10).isEmpty(),"opening/rendering check-in never claims taboo");
            runMain(()->{try{List<?> entries=(List<?>)field(checkin,"cards");for(int i=0;i<entries.size();i++)if((Integer)field(entries.get(i),"type")==0){set(checkin,"selected",i);break;}call(checkin,"render",new Class<?>[]{boolean.class},false);}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(500);
            runMain(()->{long now=android.os.SystemClock.uptimeMillis();android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now,1,300,0,0);checkin.dispatchTouchEvent(up);up.recycle();});
            check(StudyGate.tabooLog(c,10).isEmpty(),"orphan release cannot claim taboo");
            runMain(()->{try{List<?> before=(List<?>)field(checkin,"cards");for(int i=0;i<before.size();i++)if((Integer)field(before.get(i),"type")==2){set(checkin,"selected",i);call(checkin,"render",new Class<?>[]{boolean.class},false);break;}View card=(View)field(checkin,"card");int[] location=new int[2];card.getLocationOnScreen(location);float x=location[0]+card.getWidth()/2f,y=location[1]+card.getHeight()/2f;long now=android.os.SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,0,x,y,0);checkin.dispatchTouchEvent(down);down.recycle();android.view.MotionEvent move=android.view.MotionEvent.obtain(now,now+50,2,x,y-80,0);checkin.dispatchTouchEvent(move);move.recycle();List<?> next=(List<?>)field(checkin,"cards");for(int i=0;i<next.size();i++)if((Integer)field(next.get(i),"type")==0){set(checkin,"selected",i);break;}call(checkin,"render",new Class<?>[]{boolean.class},false);android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+150,1,x,y-1000,0);checkin.dispatchTouchEvent(up);up.recycle();}catch(Exception e){throw new RuntimeException(e);}});
            check(StudyGate.tabooLog(c,10).isEmpty(),"completed learning card release cannot claim the next taboo card");Thread.sleep(500);
            swipeTaboo(checkin,true);Thread.sleep(350);check(StudyGate.tabooLog(c,10).isEmpty()&&StudyGate.tabooUntil(c)==0,"cancelled swipe never claims taboo or adds penalty");
            swipeTaboo(checkin,false);for(int wait=0;wait<20&&StudyGate.tabooLog(c,10).isEmpty();wait++)Thread.sleep(50);check(StudyGate.tabooLog(c,10).size()==1,"valid full swipe claims directly without a second confirmation");
            check(StudyGate.tabooUntil(c)>System.currentTimeMillis(),"direct swipe applies its penalty immediately");
            runMain(()->{try{call(checkin,"onUp",new Class<?>[]{int.class,float.class,float.class},1,0f,-10000f);}catch(Exception e){throw new RuntimeException(e);}});check(StudyGate.tabooLog(c,10).size()==1,"repeated release during animation cannot duplicate a claim");
            penaltySchedules(c);
            String pkg="com.afs.quota.fixture";getUiAutomation().executeShellCommand("appops set com.afs.guard GET_USAGE_STATS allow").close();Thread.sleep(300);check(AppUsageQuota.permission(c),"usage access available on emulator");
            RuleStore.put(c,pkg,FreezeSlotStore.withoutSchedule());Set<String> keys=new HashSet<>(RuleStore.keyApps(c));keys.add(pkg);RuleStore.setKeyApps(c,keys);
            long now=GuardClock.now(c).getTimeInMillis();Class<?> span=Class.forName("com.afs.guard.AppUsageQuota$Span",true,c.getClassLoader());java.lang.reflect.Constructor<?> constructor=span.getDeclaredConstructor(String.class,long.class,long.class);constructor.setAccessible(true);List<Object> fake=new ArrayList<>();fake.add(constructor.newInstance(pkg,now-120000,now));cache.set(null,fake);stamp.setLong(null,now);
            AppUsageQuota.save(c,pkg,1);check(Guard.blockReasonNow(c,pkg)==4,"global quota actually enters enforcement");AppUsageQuota.save(c,pkg,3);check(Guard.blockReasonNow(c,pkg)==0,"unused global quota remains allowed");
            RuleStore.put(c,pkg,new Rule(Rule.ALL_DAYS,new int[][]{{0,0}},true,true));check(Guard.blockReasonNow(c,pkg)==0,"quota replaces immediate freeze inside assigned slot");AppUsageQuota.save(c,pkg,1);check(Guard.blockReasonNow(c,pkg)==4,"assigned slot freezes after its allowance");AppUsageQuota.save(c,pkg,0);check(Guard.blockReasonNow(c,pkg)==1,"removing quota restores strict scheduled freeze");
            AppUsageQuota.save(c,pkg,3);getUiAutomation().executeShellCommand("appops set com.afs.guard GET_USAGE_STATS deny").close();Thread.sleep(200);check(Guard.blockReasonNow(c,pkg)==0,"permission loss pauses the allowance instead of freezing");getUiAutomation().executeShellCommand("appops set com.afs.guard GET_USAGE_STATS allow").close();Thread.sleep(200);check(Guard.blockReasonNow(c,pkg)==4,"restored permission resumes enforcement");
            Activity freeze=open("FreezeBoardActivity");check(text(freeze.getWindow().getDecorView(),"使用限额"),"freeze page exposes unified allowance cell");runMain(()->pageScroll(freeze).fullScroll(View.FOCUS_DOWN));capture("usage-quota-ui");

        }finally{restorePreferences(taboo,oldTaboo);restorePreferences(guard,oldGuard);restorePreferences(quota,oldQuota);cache.set(null,oldCache);stamp.setLong(null,oldStamp);if(!permission)getUiAutomation().executeShellCommand("appops set com.afs.guard GET_USAGE_STATS default").close();StudyGate.invalidate(c);}
    }
    private void swipeTaboo(Activity activity,boolean cancel){runMain(()->{try{View card=(View)field(activity,"card");int[] point=new int[2];card.getLocationOnScreen(point);float x=point[0]+card.getWidth()/2f,y=point[1]+card.getHeight()*.7f;long now=android.os.SystemClock.uptimeMillis();int[] actions={0,2,cancel?3:1};for(int i=0;i<actions.length;i++){android.view.MotionEvent event=android.view.MotionEvent.obtain(now,now+i*100,actions[i],x,y-i*card.getHeight()*.3f,0);try{activity.dispatchTouchEvent(event);}finally{event.recycle();}}}catch(Exception error){throw new RuntimeException(error);}});}
    private long hour(int dayOffset,int hour){Calendar t=Calendar.getInstance();t.add(Calendar.DAY_OF_MONTH,dayOffset);t.set(Calendar.HOUR_OF_DAY,hour);t.set(Calendar.MINUTE,0);t.set(Calendar.SECOND,0);t.set(Calendar.MILLISECOND,0);return t.getTimeInMillis();}
    private Calendar cal(long at){Calendar c=Calendar.getInstance();c.setTimeInMillis(at);return c;}
    private void penaltySchedules(Context c)throws Exception{
        String a="com.afs.penalty.a",b="com.afs.penalty.b",key="com.afs.penalty.key";Rule evening=new Rule(127,new int[][]{{0,1260},{1380,0}},true,true),morning=new Rule(127,new int[][]{{0,360}},true,true);RuleStore.put(c,a,evening);RuleStore.put(c,b,morning);RuleStore.put(c,key,evening);Set<String> keys=new HashSet<>(RuleStore.keyApps(c));keys.add(key);RuleStore.setKeyApps(c,keys);
        long ten=hour(0,10),twenty=hour(0,20),release=hour(0,21);PenaltyStore.add(c,3600000,ten);
        check(!PenaltyStore.holding(c,a,twenty)&&PenaltyStore.holding(c,a,release),"taboo attaches to scheduled 21:00 release, not claim time");check(!PenaltyStore.holding(c,b,twenty)&&PenaltyStore.holding(c,b,hour(1,6)),"allowed app waits for next morning freeze release");check(!PenaltyStore.holding(c,key,release),"key apps retain exemption from taboo");
        PenaltyStore.add(c,1800000,ten+1000);check(PenaltyStore.holding(c,a,hour(0,22)+29*60000)&&!PenaltyStore.holding(c,a,hour(0,22)+30*60000),"repeat claims append precisely to existing queued interval");
        Field pending=StudyGate.class.getDeclaredField("linkedPending"),stamp=StudyGate.class.getDeclaredField("linkedAt"),cached=StudyGate.class.getDeclaredField("cached");for(Field f:new Field[]{pending,stamp,cached})f.setAccessible(true);Object oldCache=cached.get(null);boolean oldPending=pending.getBoolean(null);long oldStamp=stamp.getLong(null);android.content.SharedPreferences prefs=c.getSharedPreferences("afs_learning_link",0);boolean had=prefs.contains("extension_origin");long origin=prefs.getLong("extension_origin",0);
        Field cardPath=CardStore.class.getDeclaredField("testFile");cardPath.setAccessible(true);File fixture=(File)cardPath.get(null);byte[] oldCards=bytes(fixture);CardData empty=new CardData(System.currentTimeMillis());empty.summary(System.currentTimeMillis()).configureAll(Collections.singleton("custom"),365,CycleSummary.day(System.currentTimeMillis()),System.currentTimeMillis());write(fixture,empty.db.toString().getBytes("UTF-8"));cardFile(fixture);
        try{cached.set(null,StudyGate.Snapshot.EMPTY);pending.setBoolean(null,true);stamp.setLong(null,System.currentTimeMillis());prefs.edit().putLong("extension_origin",twenty).commit();check(!StudyGate.learningFor(c,evening,cal(twenty))&&StudyGate.learningFor(c,evening,cal(release)),"learning gate starts at app's own freeze end");check(!StudyGate.learningFor(c,morning,cal(hour(0,22)))&&StudyGate.learningFor(c,morning,cal(hour(1,6))),"learning waits through allowed hours and persists across midnight");pending.setBoolean(null,false);stamp.setLong(null,System.currentTimeMillis());check(!StudyGate.learningFor(c,evening,cal(release))&&prefs.getLong("extension_origin",0)==0,"finishing tasks cancels pending or active learning extension");}
        finally{write(fixture,oldCards);cardFile(fixture);cached.set(null,oldCache);pending.setBoolean(null,oldPending);stamp.setLong(null,oldStamp);if(had)prefs.edit().putLong("extension_origin",origin).commit();else prefs.edit().remove("extension_origin").commit();}
    }
    private void tapDock(Activity activity,String name)throws Exception{
        Thread.sleep(700);final int[] point=new int[2];final boolean[] ready={false};
        for(int retry=0;retry<30&&!ready[0];retry++){
            runMain(()->{try{Class<?> window=Class.forName("com.afs.guard.LiveNavWindow",true,getTargetContext().getClassLoader());Object live=call(window,"find",new Class<?>[]{Activity.class},activity);if(live==null)return;android.app.Dialog dialog=(android.app.Dialog)field(live,"dialog");if(!dialog.isShowing())return;View nav=(View)field(live,"nav");TextView label=label(nav,name);if(label==null)return;View target=(View)label.getParent();if(target.getWidth()==0)return;target.getLocationOnScreen(point);point[0]+=target.getWidth()/2;point[1]+=target.getHeight()/2;ready[0]=true;}catch(Exception error){throw new RuntimeException(error);}});
            if(!ready[0])Thread.sleep(100);
        }
        check(ready[0],"live dock available for "+name);
        long now=android.os.SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,0,point[0],point[1],0),up=android.view.MotionEvent.obtain(now,now+80,1,point[0],point[1],0);down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try{check(getUiAutomation().injectInputEvent(down,true)&&getUiAutomation().injectInputEvent(up,true),"inject actual dock touch "+name);}finally{down.recycle();up.recycle();}
        for(int retry=0;retry<30;retry++){settle();if(!(Boolean)field(activity,"managementMode"))return;Thread.sleep(100);}
    }
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
    private View surface(View root){if(root.getClass().getSimpleName().equals("StudyCardSurface"))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=surface(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
    private int charts(View root){int count=root.getClass().getSimpleName().equals("DailyCardChart")?1:0;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)count+=charts(((ViewGroup)root).getChildAt(i));return count;}
    private TextView prefix(View root,String value){if(root instanceof TextView&&((TextView)root).getText().toString().startsWith(value))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=prefix(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
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
        Map<String,?> oldPrefs=new HashMap<>(prefs.getAll());SharedPreferences completionPrefs=c.getSharedPreferences("afs_daily_completion",0);Map<String,?> oldCompletion=new HashMap<>(completionPrefs.getAll());
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

            Activity memoryActivity=open("MemoryActivity");rules(memoryActivity);
            check(!text(memoryActivity.getWindow().getDecorView(),"本日记忆闪卡"),"memory root header removed");View memorySurface=surface(memoryActivity.getWindow().getDecorView());check(memorySurface!=null&&((android.graphics.drawable.GradientDrawable)memorySurface.getBackground()).getColor().getDefaultColor()==0xFFF6DE7B,"memory card uses shared yellow surface");capture("cards-memory");
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
            String incompleteDay=StudyStore.plusDays(-1);JSONObject dailyFixture=new JSONObject(completionPrefs.getString("days","{}"));dailyFixture.put(incompleteDay,new JSONObject().put("required",4).put("missing","目标卡、总结卡、记忆卡、刷题卡"));completionPrefs.edit().putString("days",dailyFixture.toString()).commit();
            Activity history=open("HistoryActivity");Thread.sleep(1200);runMain(()->history.startActivity(new Intent(history,HistoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));Thread.sleep(800);
            check(text(history.getWindow().getDecorView(),"近30天 · 卡片类型"),"recent pie charts present");check(text(history.getWindow().getDecorView(),"最近流水  ▸"),"recent flow initially collapsed");check(charts(history.getWindow().getDecorView())==30,"exactly thirty daily pie charts");
            runMain(()->label(history.getWindow().getDecorView(),"‹ 前30天").performClick());check(charts(history.getWindow().getDecorView())==30,"older thirty-day page renders");
            runMain(()->label(history.getWindow().getDecorView(),"后30天 ›").performClick());
            runMain(()->prefix(history.getWindow().getDecorView(),"全部打卡日期（").performClick());
            runMain(()->{TextView missing=prefix(history.getWindow().getDecorView(),incompleteDay+"  ·");try{check(missing!=null&&missing.getCurrentTextColor()==(Integer)field(history,"RED"),"incomplete mandatory cards make the date red");}catch(Exception error){throw new RuntimeException(error);}});
            capture("cards-history");
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
            check(text(manager.getWindow().getDecorView(),"打卡设置  ›"),"combined check-in settings entry");
            check(!text(manager.getWindow().getDecorView(),"打卡历史  ›")&&!text(manager.getWindow().getDecorView(),"打卡外观  ›"),"no duplicate history or separate settings on manager home");
            runMain(()->((View)label(manager.getWindow().getDecorView(),"打卡设置  ›").getParent()).performClick());
            for(String setting:new String[]{"打卡外观  ›","打卡字体  ›","同步设置  ›","上传打卡数据  ›"})check(text(manager.getWindow().getDecorView(),setting),"setting inside combined page "+setting);
            runMain(manager::onBackPressed);

            for(String label:new String[]{"热爱卡  ›","记忆卡  ›","刷题卡  ›","目标卡  ›","禁忌卡  ›","总结卡  ›"})
                check(text(manager.getWindow().getDecorView(),label),"central card setting: "+label);
            android.graphics.Bitmap preview=getUiAutomation().takeScreenshot();
            if(preview!=null){try(OutputStream image=new FileOutputStream(new File(c.getExternalFilesDir(null),"card-management-1530.png"))){preview.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);}preview.recycle();}
            check(text(manager.getWindow().getDecorView(),"学习联动罚时"),"linkage penalty displayed in manager");
            runMain(()->{try{set(manager,"category","summary");call(manager,"render",new Class<?>[0]);View root=manager.getWindow().getDecorView();android.widget.EditText days=(android.widget.EditText)root.findViewWithTag("summary_cycle_days");check(days.getVisibility()==View.VISIBLE,"custom day input visible without enabling a checkbox");((android.widget.CheckBox)label(root,"每周总结")).setChecked(true);((android.widget.CheckBox)label(root,"每月总结")).setChecked(true);days.setText("21");check(((android.widget.CheckBox)label(root,"启用自定义天数")).isChecked(),"typing days automatically enables custom cycle");check(((TextView)root.findViewWithTag("summary_cycle_preview")).getText().toString().contains("每21天总结一次"),"21-day preview is explicit");}catch(Exception error){throw new RuntimeException(error);}});settle();
            check(text(manager.getWindow().getDecorView(),"保存总结周期"),"custom summary save available");capture("cards-cycle");
            runMain(()->{TextView save=label(manager.getWindow().getDecorView(),"保存总结周期");save.performClick();});
            CycleSummary savedCycle=CardStore.snapshot(c).summary(System.currentTimeMillis());check(savedCycle.enabled("weekly")&&savedCycle.enabled("monthly")&&savedCycle.enabled("custom"),"multiple cycle selections save together from UI");
            check(savedCycle.settings("custom").days()==21,"21 days persisted from actual custom input");
            runMain(()->{try{call(manager,"render",new Class<?>[0]);View root=manager.getWindow().getDecorView();check(((android.widget.CheckBox)label(root,"每周总结")).isChecked()&&((android.widget.CheckBox)label(root,"每月总结")).isChecked(),"weekly and monthly remain checked after page reload");}catch(Exception error){throw new RuntimeException(error);}});
            runMain(()->{View root=manager.getWindow().getDecorView();android.widget.EditText days=(android.widget.EditText)root.findViewWithTag("summary_cycle_days");check("21".contentEquals(days.getText()),"reopened input retains 21 days");days.setText("40");label(root,"保存总结周期").performClick();});
            CycleSummary forty=CardStore.snapshot(c).summary(System.currentTimeMillis());check(forty.settings("custom").days()==40&&forty.enabled("weekly")&&forty.enabled("monthly"),"40-day edit saved without replacing other selected cycles");
            runMain(()->{View root=manager.getWindow().getDecorView();check("40".contentEquals(((android.widget.EditText)root.findViewWithTag("summary_cycle_days")).getText()),"reloaded input retains 40 days");});
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
            long current=System.currentTimeMillis();CardData overlapping=new CardData(current);CycleSummary schedules=overlapping.summary(current);
            schedules.configureAll(new HashSet<>(Arrays.asList("weekly","monthly")),7,CycleSummary.day(current),current);
            Calendar lastMonth=Calendar.getInstance();lastMonth.set(Calendar.DAY_OF_MONTH,1);lastMonth.add(Calendar.MONTH,-1);
            org.json.JSONObject summaryData=overlapping.db.optJSONObject("summary");MemoryData.put(summaryData,"cursor",CycleSummary.day(lastMonth.getTimeInMillis()));MemoryData.put(summaryData.optJSONObject("extraCycles").optJSONObject("monthly"),"cursor",CycleSummary.day(lastMonth.getTimeInMillis()));
            write(cards,overlapping.db.toString().getBytes("UTF-8"));cardFile(cards);
            List<CycleSummary.Period> both=CardStore.summariesPending(c,current);check(both.size()==2,"two independent overdue summary cards on device");
            final CycleSummary.Period month=both.get(0).schedule.equals("monthly")?both.get(0):both.get(1);
            Activity monthlyReport=openIntent(new Intent(c,HistoryActivity.class).putExtra("open_weekly_report",true).putExtra("summary_key",month.key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            Thread.sleep(400);android.view.accessibility.AccessibilityNodeInfo monthlyRoot=getUiAutomation().getRootInActiveWindow();check(monthlyRoot!=null&&!monthlyRoot.findAccessibilityNodeInfosByText("总结卡 · 每月").isEmpty(),"specific monthly card opens its own report directly");
            check(clickText("完成总结"),"monthly completion available independently");for(int wait=0;wait<40&&CardStore.snapshot(c).summary(current).recorded(month.key)==null;wait++)Thread.sleep(50);
            check(CardStore.snapshot(c).summary(current).recorded(month.key)!=null,"monthly completion stored under its own key");check(CardStore.summaryHolding(c,current)&&CardStore.summariesPending(c,current).size()==1,"weekly debt continues freezing after monthly completion");
            Thread.sleep(1200);runMain(monthlyReport::finishAndRemoveTask);Thread.sleep(300);phase("independent overlapping summaries");
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
            tapDock(memorySettings,"记忆");
            check(!(Boolean)field(memorySettings,"managementMode")&&"home".equals(field(memorySettings,"page")),"real bottom tap exits cached memory settings");
            check(memorySettings.getIntent().getBooleanExtra("from_bottom_nav",false),"memory navigation sender includes entry marker");
            Activity quizSettings=openIntent(new Intent(c,QuizActivity.class).putExtra("card_management",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            check((Boolean)field(quizSettings,"managementMode")&&text(quizSettings.getWindow().getDecorView(),"刷题卡设置"),"quiz settings open through card management");
            tapDock(quizSettings,"刷题");
            check(!(Boolean)field(quizSettings,"managementMode")&&"home".equals(field(quizSettings,"page")),"real bottom tap exits cached quiz settings");
            check(quizSettings.getIntent().getBooleanExtra("from_bottom_nav",false),"quiz navigation sender includes entry marker");
            regressions(c);positions();
            result.putString("stream","Learning link device: "+checks+" checks passed\n");
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));sendStatus(0,result);}
        finally {
            try {
                if(backed){write(memory,original);resetMemory();restorePreferences(prefs,oldPrefs);}
                restorePreferences(completionPrefs,oldCompletion);quizFile(null);quiz.delete();cardFile(null);cards.delete();StudyGate.invalidate(c);
                runMain(()->{for(Activity a:opened)if(a!=null&&!a.isFinishing())a.finish();});settle();
            }catch(Throwable restore){result.putString("stream",result.getString("stream","")+"Restore failed: "+restore);}
        }
        String output=result.getString("stream","");finish(output.startsWith("Learning link device:")&&!output.contains("Restore failed")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
