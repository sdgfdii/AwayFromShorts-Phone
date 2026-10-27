package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/** Real Android dispatch and draft-save regression; no learning data or blocking rules are changed. */
public final class UsabilityDeviceTest extends Instrumentation {
    private int checks, clicks;

    private void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}

    private class Host extends SwipeNavHost {
        int navigations;
        Host(Activity a,View v){super(a,v,1);}
        @Override protected void navigate(int target){navigations++;}
    }

    private long time;
    private void event(Host h,int action,float x,float y){
        MotionEvent e=MotionEvent.obtain(time,time+20,action,x,y,0);
        h.dispatchTouchEvent(e);e.recycle();time+=20;
    }
    private void gesture(Host h,int end,float dx,float dy){
        event(h,MotionEvent.ACTION_DOWN,600,100);
        event(h,MotionEvent.ACTION_MOVE,600+dx,100+dy);
        event(h,end,600+dx,100+dy);
    }
    private void layout(Host h){
        h.measure(View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY));
        h.layout(0,0,1200,1200);
    }

    private TextView findText(View v,String text){
        if(v instanceof TextView){
            CharSequence value=((TextView)v).getText();
            if(value!=null&&text.contentEquals(value))return (TextView)v;
        }
        if(v instanceof ViewGroup){
            ViewGroup group=(ViewGroup)v;
            for(int i=0;i<group.getChildCount();i++){
                TextView found=findText(group.getChildAt(i),text);
                if(found!=null)return found;
            }
        }
        return null;
    }
    private EditText findEditText(View v){
        if(v instanceof EditText)return (EditText)v;
        if(v instanceof ViewGroup){
            ViewGroup group=(ViewGroup)v;
            for(int i=0;i<group.getChildCount();i++){
                EditText found=findEditText(group.getChildAt(i));
                if(found!=null)return found;
            }
        }
        return null;
    }
    private boolean waitText(String text,boolean click)throws Exception{
        long end=System.currentTimeMillis()+10000;
        while(System.currentTimeMillis()<end){
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null){
                List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(text);
                for(AccessibilityNodeInfo node:nodes){
                    CharSequence value=node.getText();
                    if(value==null||!text.equals(value.toString()))continue;
                    if(!click)return true;
                    AccessibilityNodeInfo target=node;
                    while(target!=null&&!target.isClickable())target=target.getParent();
                    if(target!=null)return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
            Thread.sleep(150);
        }
        return false;
    }

    private Object field(Object target,String name)throws Exception{
        Field f=target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
    private void setField(Object target,String name,Object value)throws Exception{
        Field f=target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target,value);
    }
    private void call(Object target,String name)throws Exception{
        Method m=target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(target);
    }
    /** Reflection keeps the production test seam package-private while the QA dex stays separate. */
    private void setStoreFile(File file)throws Exception{
        Method m=QuizStore.class.getDeclaredMethod("setTestFile",File.class);
        m.setAccessible(true);
        m.invoke(null,file);
    }
    private void deleteTree(File file){
        if(file==null||!file.exists())return;
        File[] children=file.listFiles();
        if(children!=null)for(File child:children)deleteTree(child);
        file.delete();
    }

    /**
     * R01: an isolated, deliberately unwritable store. Both back paths must
     * stay on the practice page with the dirty draft intact, then a retry to
     * a writable isolated file must succeed. The real question bank is only read.
     */
    private void draftFailure()throws Exception{
        Context c=getTargetContext();
        String before=QuizStore.snapshot(c).db.toString();
        File root=new File(c.getCacheDir(),"qa-draft-"+System.nanoTime());
        check(root.mkdirs(),"isolated store directory");
        File blocker=new File(root,"blocked-parent");
        check(blocker.createNewFile(),"isolated blocked parent");
        File failing=new File(blocker,"quiz-v1.json");
        setStoreFile(failing);
        QuizActivity quiz=null;
        try{
            quiz=(QuizActivity)startActivitySync(new Intent(c,QuizActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            final QuizActivity screen=quiz;
            final String draft="QA 未保存草稿 "+System.nanoTime();
            final EditText[] notes={null};
            runOnMainSync(()->{
                try{
                    QuizData data=(QuizData)field(screen,"data");
                    String subject=data.subjects().isEmpty()?"":data.subjects().get(0);
                    List<JSONObject> list=data.filter(subject,"","all","");
                    JSONObject target=null;
                    for(JSONObject q:list)if(!QuizData.objective(q)){target=q;break;}
                    check(target!=null,"subjective question available");
                    data.start(Collections.singletonList(target),false);
                    setField(screen,"page","practice");
                    call(screen,"practice");
                    notes[0]=findEditText(screen.getWindow().getDecorView());
                    check(notes[0]!=null,"notes input rendered");
                    notes[0].setText(draft);
                    ((Handler)field(screen,"handler")).removeCallbacks((Runnable)field(screen,"saveDraft"));
                }catch(Exception e){throw new RuntimeException(e);}
            });
            waitForIdleSync();
            check("practice".equals(field(quiz,"page")),"draft setup stays on practice");
            check(Boolean.TRUE.equals(field(quiz,"draftDirty")),"draft setup marks dirty");

            // System back callback.
            runOnMainSync(()->screen.onBackPressed());
            waitForIdleSync();
            check("practice".equals(field(quiz,"page")),"system back failure keeps page");
            check(Boolean.TRUE.equals(field(quiz,"draftDirty")),"system back failure keeps dirty flag");
            check(notes[0].getParent()!=null&&draft.equals(notes[0].getText().toString()),
                    "system back failure keeps input");
            check(waitText("草稿尚未保存",false),"system back reports unsaved draft");
            check(waitText("知道了",true),"dismiss system back error");
            waitForIdleSync();

            // In-page back control.
            TextView back=findText(quiz.getWindow().getDecorView(),"‹  刷题");
            check(back!=null,"page back control exists");
            runOnMainSync(()->back.performClick());
            waitForIdleSync();
            check("practice".equals(field(quiz,"page")),"page back failure keeps page");
            check(Boolean.TRUE.equals(field(quiz,"draftDirty")),"page back failure keeps dirty flag");
            check(notes[0].getParent()!=null&&draft.equals(notes[0].getText().toString()),
                    "page back failure keeps input");
            check(waitText("草稿尚未保存",false),"page back reports unsaved draft");
            check(waitText("知道了",true),"dismiss page back error");
            waitForIdleSync();

            // Retry against a writable isolated file.
            File writable=new File(root,"quiz-v1.json");
            setStoreFile(writable);
            runOnMainSync(()->back.performClick());
            waitForIdleSync();
            check("home".equals(field(quiz,"page")),"successful retry reaches home");
            check(Boolean.FALSE.equals(field(quiz,"draftDirty")),"successful retry clears dirty flag");
            check(writable.isFile(),"successful retry writes isolated store");
            String saved;
            try(FileInputStream in=new FileInputStream(writable)){saved=QuizStore.read(in,QuizStore.IMPORT_LIMIT);}
            check(draft.equals(new QuizData(saved).session().optString("input")),
                    "successful retry persists draft");
        }finally{
            // Keep the unwritable store in place until the activity is gone, so a
            // failed assertion cannot make onPause save the draft to real user data.
            setStoreFile(failing);
            if(quiz!=null){
                final QuizActivity screen=quiz;
                runOnMainSync(()->{
                    try{setField(screen,"draftDirty",false);}catch(Exception ignored){}
                    screen.finish();
                });
                waitForIdleSync();Thread.sleep(200);waitForIdleSync();
            }
            setStoreFile(null);
            deleteTree(root);
        }
        check(before.equals(QuizStore.snapshot(c).db.toString()),"real quiz data untouched");
    }

    @Override public void onCreate(Bundle args){super.onCreate(args);start();}

    @Override public void onStart(){
        Bundle result=new Bundle();Activity a=null;
        try{
            a=startActivitySync(new Intent().setClassName(getTargetContext(),"com.afs.guard.ToolkitActivity")
                    .putExtra("tool","dates").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity screen=a;
            runOnMainSync(()->{
                TextView button=new TextView(screen);button.setText("QA");
                button.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_UP)clicks++;return true;});
                Host h=new Host(screen,button);screen.setContentView(h);layout(h);
                time=android.os.SystemClock.uptimeMillis();
                gesture(h,MotionEvent.ACTION_UP,0,0);check(clicks==1&&h.navigations==0,"tap clicks once");
                gesture(h,MotionEvent.ACTION_UP,-400,0);check(clicks==1&&h.navigations==1,"swipe cancels button");
                gesture(h,MotionEvent.ACTION_CANCEL,-400,0);check(h.navigations==1,"cancel never navigates");
                gesture(h,MotionEvent.ACTION_UP,0,400);check(h.navigations==1,"vertical never navigates");
                event(h,MotionEvent.ACTION_DOWN,600,100);h.requestDisallowInterceptTouchEvent(true);
                event(h,MotionEvent.ACTION_MOVE,200,100);event(h,MotionEvent.ACTION_UP,200,100);
                check(h.navigations==1,"child owns gesture");
                event(h,MotionEvent.ACTION_DOWN,600,100);event(h,MotionEvent.ACTION_POINTER_DOWN,600,100);
                event(h,MotionEvent.ACTION_UP,200,100);check(h.navigations==1,"multiple pointers cancel navigation");
                gesture(h,MotionEvent.ACTION_UP,0,0);check(h.navigations==1,"tap after cancellation");

                // R02: the first slop-crossing MOVE must reach a real HorizontalScrollView
                // before the host decides to switch pages.
                HorizontalScrollView strip=new HorizontalScrollView(screen);
                strip.setHorizontalScrollBarEnabled(false);
                LinearLayout row=new LinearLayout(screen);row.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(new View(screen),new LinearLayout.LayoutParams(3000,400));
                strip.addView(row);
                Host hStrip=new Host(screen,strip);layout(hStrip);
                event(hStrip,MotionEvent.ACTION_DOWN,600,200);
                event(hStrip,MotionEvent.ACTION_MOVE,100,200);   // first slop-crossing MOVE
                event(hStrip,MotionEvent.ACTION_MOVE,50,200);    // child still owns the gesture
                event(hStrip,MotionEvent.ACTION_UP,50,200);
                check(hStrip.navigations==0,"first move lets horizontal scroll view take over");
                check(strip.getScrollX()>0,"horizontal scroll view consumed the gesture");

                // R02: a child may only claim the gesture inside this first MOVE.
                final boolean[] tookOver={false};
                View takeover=new View(screen);
                takeover.setOnTouchListener((v,e)->{
                    if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                        tookOver[0]=true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                });
                Host hTake=new Host(screen,takeover);layout(hTake);
                gesture(hTake,MotionEvent.ACTION_UP,-400,0);
                check(tookOver[0]&&hTake.navigations==0,"child takes over within first move");

                // Ordinary buttons still navigate on a swipe, but never receive the click.
                Button real=new Button(screen);real.setText("QA 按钮");
                final int[] realClicks={0};real.setOnClickListener(v->realClicks[0]++);
                Host hReal=new Host(screen,real);layout(hReal);
                gesture(hReal,MotionEvent.ACTION_UP,-400,0);
                check(realClicks[0]==0&&hReal.navigations==1,"real button swipe navigates without click");

                screen.finish();
            });
            waitForIdleSync();
            draftFailure();
            for(String page:new String[]{"MainActivity","MemoryActivity","QuizActivity","FreezeBoardActivity","CheckinActivity"}){
                Activity opened=startActivitySync(new Intent().setClassName(getTargetContext(),"com.afs.guard."+page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync();
                runOnMainSync(()->{
                    android.view.ViewGroup content=opened.findViewById(android.R.id.content);
                    check(content!=null&&content.getChildCount()>0,"page renders: "+opened.getClass().getSimpleName());
                    opened.finish();
                });
                waitForIdleSync();
            }
            result.putString("stream","Usability device: "+checks+" checks passed\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){if(a!=null){final Activity screen=a;runOnMainSync(()->screen.finish());}result.putString("stream",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}
    }
}

