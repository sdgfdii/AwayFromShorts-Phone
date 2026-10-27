package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Emulator-only fixtures. Restore the two memo keys and the selected theme in finally. */
public final class MemoChecklistDeviceTest extends Instrumentation {
    private int checks;
    private final List<Activity> opened=new ArrayList<>();
    private void check(boolean result,String label){checks++;if(!result)throw new AssertionError(label);}
    private void main(Runnable action){
        CountDownLatch done=new CountDownLatch(1);Throwable[] error={null};
        new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{try{action.run();}catch(Throwable t){error[0]=t;}finally{done.countDown();}});
        try{if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("UI timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
        if(error[0]!=null)throw new RuntimeException(error[0]);
    }
    private Object field(Object owner,String name){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new RuntimeException(e);}}
    private TextView text(View root,String label){
        if(root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return (TextView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=text(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private void click(Activity a,String label){TextView button=text(a.getWindow().getDecorView(),label);check(button!=null,"button "+label);button.performClick();}
    private Activity open(String id)throws Exception{
        ActivityMonitor monitor=addMonitor("com.afs.guard.MemoActivity",null,false);
        main(()->getTargetContext().startActivity(new Intent().setClassName(getTargetContext(),"com.afs.guard.MemoActivity")
                .putExtra("id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));
        Activity a=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);check(a!=null,"memo opens");opened.add(a);Thread.sleep(300);return a;
    }
    private Activity recreate(Activity a)throws Exception{
        ActivityMonitor monitor=addMonitor("com.afs.guard.MemoActivity",null,false);main(a::recreate);
        Activity next=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);check(next!=null,"editor recreated");opened.add(next);Thread.sleep(300);return next;
    }
    private void screenshot(String name)throws Exception{
        Thread.sleep(700);Bitmap bitmap=getUiAutomation().takeScreenshot();check(bitmap!=null,"screenshot "+name);
        File dir=new File(getTargetContext().getExternalFilesDir(null),"memo-checklist-qa");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private void restore(SharedPreferences prefs,String key,String old){SharedPreferences.Editor editor=prefs.edit();if(old==null)editor.remove(key);else editor.putString(key,old);editor.commit();}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Context c=getTargetContext();SharedPreferences prefs=c.getSharedPreferences("afs_guard",0),ui=c.getSharedPreferences("afs_ui",0);
        String original=prefs.getString("memos_v1",null),trash=prefs.getString("memos_trash_v1",null),theme=ui.getString("theme",null);
        try{
            ui.edit().putString("theme","light").commit();
            String body="项目说明\n- [ ] 阅读两页资料\n- [x] 整理今天的想法\n- [ ] 完成一次练习";
            MemoStore.Note note=MemoStore.create(c,"待办清单 · 验证",body);check(note!=null,"fixture created in existing store");
            Activity a=open(note.id);final Activity first=a;EditText input=(EditText)field(a,"bodyInput");
            main(()->{
                check(input.getText().toString().equals(body),"legacy Markdown roundtrip");
                check(input.getText().getSpans(0,input.length(),android.text.style.ReplacementSpan.class).length==3,"three inline checkboxes");
                input.clearFocus();int marker=body.indexOf("- [ ]");android.text.Layout layout=input.getLayout();
                float x=input.getTotalPaddingLeft()+layout.getPrimaryHorizontal(marker)+10*input.getResources().getDisplayMetrics().density;
                float y=input.getTotalPaddingTop()+layout.getLineBaseline(layout.getLineForOffset(marker))-input.getTextSize()/3;
                long now=android.os.SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,0,x,y,0),up=MotionEvent.obtain(now,now+80,1,x,y,0);
                input.onTouchEvent(down);input.onTouchEvent(up);down.recycle();up.recycle();
                check(input.getText().toString().contains("- [x] 阅读"),"real checkbox hit toggles state");
                check(input.getText().toString().indexOf("阅读")<input.getText().toString().indexOf("整理"),"completion does not reorder");
                click(first,"撤销");check(input.getText().toString().equals(body),"toggle undo");
                click(first,"重做");check(input.getText().toString().contains("- [x] 阅读"),"toggle redo");
                click(first,"预览");TextView preview=(TextView)field(first,"previewText");
                ClickableSpan[] links=((Spanned)preview.getText()).getSpans(0,preview.length(),ClickableSpan.class);
                check(links.length==3,"preview checkboxes interactive");links[0].onClick(preview);
                check(input.getText().toString().equals(body),"preview toggle updates canonical body");click(first,"编辑");
            });
            screenshot("light");
            main(()->{
                input.setSelection(input.length());input.getText().insert(input.length(),"\n");
                check(input.getText().toString().endsWith("\n- [ ] "),"IME newline continues list");
                input.getText().insert(input.length(),"\n");check(input.getText().toString().endsWith("\n"),"empty item exits");
                input.getText().append("普通正文");input.setSelection(input.length());click(first,"待办");
                check(input.getText().toString().endsWith("- [ ] 普通正文"),"toolbar converts paragraph");
                click(first,"待办");check(input.getText().toString().endsWith("\n普通正文"),"toolbar removes marker");
                input.setText("- [ ]   ");input.setSelection(6);input.getText().insert(6,"\n");check(input.length()==0,"blank-item exit with trailing spaces");
                input.setText("☐ 普通符号\n- [ ] 真待办");click(first,"预览");
                TextView literalPreview=(TextView)field(first,"previewText");
                ClickableSpan[] taskLinks=((Spanned)literalPreview.getText()).getSpans(0,literalPreview.length(),ClickableSpan.class);
                check(taskLinks.length==1,"literal checkbox is not an action");taskLinks[0].onClick(literalPreview);
                check(input.getText().toString().equals("☐ 普通符号\n- [x] 真待办"),"preview action maps to correct source paragraph");click(first,"编辑");
                input.setSelection(input.length());check(input.performAccessibilityAction(0x01021001,null),"accessible current task action");
                check(input.getText().toString().endsWith("- [ ] 真待办"),"accessible action restores task");
                input.setText(body+"\n草稿不会丢失");input.setSelection(input.length());
            });
            Thread.sleep(600);
            main(()->{
                android.view.WindowInsets insets=first.getWindow().getDecorView().getRootWindowInsets();
                check(insets!=null&&insets.isVisible(android.view.WindowInsets.Type.ime()),"keyboard visible during editor tools");
                TextView todo=text(first.getWindow().getDecorView(),"待办");int[] location=new int[2];todo.getLocationOnScreen(location);
                android.graphics.Point display=new android.graphics.Point();first.getWindowManager().getDefaultDisplay().getRealSize(display);
                int keyboardTop=display.y-insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                check(location[1]+todo.getHeight()<=keyboardTop+8,"toolbar stays above keyboard");
            });
            screenshot("keyboard");
            ui.edit().putString("theme","dark").commit();a=recreate(a);final Activity dark=a;
            main(()->{
                EditText restored=(EditText)field(dark,"bodyInput");check(restored.getText().toString().endsWith("草稿不会丢失"),"theme recreation keeps draft");
                check(restored.getText().getSpans(0,restored.length(),android.text.style.ReplacementSpan.class).length==3,"checkbox decoration restored");
                click(dark,"预览");
            });
            screenshot("dark-preview");
            main(()->{click(dark,"编辑");click(dark,"完成");});
            a=open(note.id);final Activity reopened=a;
            main(()->{check(((EditText)field(reopened,"bodyInput")).getText().toString().equals(body+"\n草稿不会丢失"),"save and reopen preserves body");});
            result.putString("stream","Memo checklist device: "+checks+" checks passed\n");
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));}
        finally{
            restore(prefs,"memos_v1",original);restore(prefs,"memos_trash_v1",trash);restore(ui,"theme",theme);
            for(Activity a:opened)try{main(a::finish);}catch(Throwable ignored){}
        }
        finish(result.getString("stream","").startsWith("Memo checklist device:")?-1:0,result);
    }
}
