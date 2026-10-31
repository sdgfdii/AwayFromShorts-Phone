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
    private void invoke(Activity activity,String name,Class<?>[] types,Object... args){
        try{java.lang.reflect.Method method=activity.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(activity,args);}
        catch(Exception e){throw new AssertionError(name,e);}
    }
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
    private int waitNotices(android.app.NotificationManager manager,String id,int expected)throws Exception{
        int count=0;for(int retry=0;retry<30;retry++){count=0;for(android.service.notification.StatusBarNotification notice:manager.getActiveNotifications())if(id.equals(notice.getTag()))count++;if(count==expected)return count;Thread.sleep(100);}return count;
    }
    private void tapText(String label)throws Exception{
        for(int retry=0;retry<20;retry++){
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label)){
                if(node.getText()==null||!label.contentEquals(node.getText())||!node.isVisibleToUser())continue;
                android.graphics.Rect rowBounds=new android.graphics.Rect();node.getBoundsInScreen(rowBounds);
                if(!rowBounds.isEmpty()){
                    long now=android.os.SystemClock.uptimeMillis();
                    MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,rowBounds.centerX(),rowBounds.centerY(),0);
                    MotionEvent up=MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,rowBounds.centerX(),rowBounds.centerY(),0);
                    down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                    try{check(getUiAutomation().injectInputEvent(down,true)&&getUiAutomation().injectInputEvent(up,true),"UI row tap "+label);}finally{down.recycle();up.recycle();}
                    Thread.sleep(150);return;
                }
                for(int p=0;p<5&&node!=null;p++,node=node.getParent())if(node.isClickable()&&node.isEnabled()){
                    check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"UI click "+label);Thread.sleep(150);return;
                }
            }Thread.sleep(100);
        }screenshot("tap-missing");throw new AssertionError("Dialog item not found: "+label+"; active UI: "+nodeSummary(getUiAutomation().getRootInActiveWindow()));
    }
    private String nodeSummary(android.view.accessibility.AccessibilityNodeInfo node){
        if(node==null)return "null";StringBuilder value=new StringBuilder();
        if(node.getText()!=null)value.append(node.getClassName()).append(':').append(node.getText()).append(';');
        for(int i=0;i<node.getChildCount();i++)value.append(nodeSummary(node.getChild(i)));
        return value.toString();
    }
    private android.view.accessibility.AccessibilityNodeInfo editNode(android.view.accessibility.AccessibilityNodeInfo root){
        if(root==null)return null;if("android.widget.EditText".contentEquals(root.getClassName()))return root;
        for(int i=0;i<root.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo found=editNode(root.getChild(i));if(found!=null)return found;}return null;
    }
    private int keyboardTop(Activity activity){
        android.graphics.Point display=new android.graphics.Point();activity.getWindowManager().getDefaultDisplay().getRealSize(display);
        if(android.os.Build.VERSION.SDK_INT>=30){android.view.WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();
            return insets!=null&&insets.isVisible(android.view.WindowInsets.Type.ime())?display.y-insets.getInsets(android.view.WindowInsets.Type.ime()).bottom:display.y;}
        android.graphics.Rect frame=new android.graphics.Rect();activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
        return display.y-frame.bottom>100*activity.getResources().getDisplayMetrics().density?frame.bottom:display.y;
    }
    private boolean keyboardVisible(Activity activity){android.graphics.Point display=new android.graphics.Point();activity.getWindowManager().getDefaultDisplay().getRealSize(display);return keyboardTop(activity)<display.y;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Context c=getTargetContext();SharedPreferences prefs=c.getSharedPreferences("afs_guard",0),ui=c.getSharedPreferences("afs_ui",0);
        String original=prefs.getString("memos_v1",null),trash=prefs.getString("memos_trash_v1",null),theme=ui.getString("theme",null);
        String fixtureId=null;
        try{
            ui.edit().putString("theme","light").commit();
            String body="项目说明\n- [ ] 阅读两页资料\n- [x] 整理今天的想法\n- [ ] 完成一次练习";
            MemoStore.Note note=MemoStore.create(c,"待办清单 · 验证",body);fixtureId=note.id;check(note!=null,"fixture created in existing store");
            Activity a=open(note.id);final Activity first=a;EditText input=(EditText)field(a,"bodyInput");
            main(()->{
                android.view.Menu selection=new android.widget.PopupMenu(first,input).getMenu();
                input.getCustomSelectionActionModeCallback().onCreateActionMode(null,selection);
                check(selection.findItem(0xAF01)!=null&&selection.findItem(0xAF02)!=null,"selection highlight and blocks entries");
                invoke(first,"highlight",new Class<?>[]{int.class,int.class},0,4);
                check(input.getText().toString().startsWith("==项目说明=="),"highlight source persisted");
                check(input.getText().getSpans(0,input.length(),android.text.style.BackgroundColorSpan.class).length==1,"editor visible highlight span");
                invoke(first,"togglePreview",new Class<?>[]{});
                TextView preview=(TextView)field(first,"previewText");Spanned rendered=(Spanned)preview.getText();
                check(rendered.getSpans(0,rendered.length(),android.text.style.BackgroundColorSpan.class).length==1,"preview visible highlight span");
                invoke(first,"togglePreview",new Class<?>[]{});invoke(first,"travel",new Class<?>[]{boolean.class},false);
                check(input.getText().toString().equals(body),"highlight undo");
                input.setSelection(0);invoke(first,"blockMenu",new Class<?>[]{});
            });
            tapText("1 · 项目说明");tapText("下移");tapText("关闭");
            main(()->{
                check(input.getText().toString().startsWith("- [ ] 阅读两页资料\n项目说明"),"block down through dialogs");
                invoke(first,"travel",new Class<?>[]{boolean.class},false);check(input.getText().toString().equals(body),"block undo restores tasks");
                input.setSelection(0);invoke(first,"blockMenu",new Class<?>[]{});
            });
            tapText("1 · 项目说明");tapText("删除段落");tapText("取消");
            main(()->{check(input.getText().toString().equals(body),"cancel deletion preserves block");invoke(first,"blockMenu",new Class<?>[]{});});
            tapText("1 · 项目说明");tapText("删除段落");tapText("删除");
            main(()->{
                check(!input.getText().toString().contains("项目说明"),"confirmed block deletion");
                invoke(first,"travel",new Class<?>[]{boolean.class},false);check(input.getText().toString().equals(body),"deleted block undo");
            });
            main(()->{
                check(text(first.getWindow().getDecorView(),"编辑备忘录")==null,"editor page heading removed");
                TextView save=text(first.getWindow().getDecorView(),"完成");check(save instanceof MemoToolButton,"save uses vector icon");
                check(save.getWidth()==save.getHeight(),"save touch target square");
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
            main(()->{input.setSelection(body.indexOf("- [ ]"));click(first,"管理");});tapText("增加缩进");
            main(()->{check(input.getText().toString().contains("\n  - [ ] 阅读"),"indent menu edits selected task");click(first,"撤销");check(input.getText().toString().equals(body),"indent undo");click(first,"管理");});
            tapText("全部完成");main(()->{check(!input.getText().toString().contains("- [ ]"),"complete all menu");click(first,"撤销");input.setSelection(body.indexOf("- [ ]"));click(first,"管理");});
            tapText("删除所选待办");tapText("取消");main(()->{check(input.getText().toString().equals(body),"delete cancellation preserves content");input.setSelection(body.indexOf("- [ ]"));click(first,"管理");});
            tapText("删除所选待办");tapText("删除");main(()->{check(!input.getText().toString().contains("阅读"),"confirmed deletion applies");click(first,"撤销");check(input.getText().toString().equals(body),"confirmed deletion undo");click(first,"更多");});
            tapText("文内查找");
            android.view.accessibility.AccessibilityNodeInfo query=null;
            for(int retry=0;retry<30&&query==null;retry++){query=editNode(getUiAutomation().getRootInActiveWindow());if(query==null)Thread.sleep(100);}
            check(query!=null,"find query field");
            Bundle queryArgs=new Bundle();queryArgs.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"阅读");
            check(query.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,queryArgs),"set find query");Thread.sleep(150);
            check(!getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("1 / 1 · 正文").isEmpty(),"find matching count");
            tapText("下一处");tapText("上一处");tapText("关闭");
            main(()->{check(input.getSelectionStart()==body.indexOf("阅读"),"find locates source selection");click(first,"更多");});
            tapText("设置提醒");tapText("设置时间");tapText("取消");
            main(()->{check(((Long)field(first,"draftRemindAt"))==0L,"date cancellation does not change reminder");input.setText(body);input.setSelection(input.length());});
            final Intent[] share={null};ActivityMonitor shareMonitor=android.os.Build.VERSION.SDK_INT<26
                ?new ActivityMonitor(new android.content.IntentFilter(Intent.ACTION_CHOOSER),new ActivityResult(Activity.RESULT_CANCELED,null),true)
                :new ActivityMonitor(){
                @Override public ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CHOOSER.equals(intent.getAction())){
                    share[0]=(Intent)intent.getParcelableExtra(Intent.EXTRA_INTENT);return new ActivityResult(Activity.RESULT_CANCELED,null);
                }return null;}
            };addMonitor(shareMonitor);main(()->click(first,"更多"));tapText("分享文本");
            for(int retry=0;retry<30&&shareMonitor.getHits()==0&&share[0]==null;retry++)Thread.sleep(100);
            if(android.os.Build.VERSION.SDK_INT<26)check(shareMonitor.getHits()>0,"share chooser intercepted without sending");
            else main(()->{check(share[0]!=null&&Intent.ACTION_SEND.equals(share[0].getAction()),"share uses chooser with send intent");
                check("text/plain".equals(share[0].getType()),"share uses plain text");
                check(("待办清单 · 验证\n\n"+body).equals(share[0].getStringExtra(Intent.EXTRA_TEXT)),"share contains current title and body");});removeMonitor(shareMonitor);
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
            main(()->{input.requestFocus();((android.view.inputmethod.InputMethodManager)c.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
            for(int retry=0;retry<20;retry++){
                final boolean[] visible={false};main(()->{visible[0]=keyboardVisible(first);
                    if(!visible[0]&&input.hasWindowFocus())((android.view.inputmethod.InputMethodManager)c.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_FORCED);});
                if(visible[0])break;Thread.sleep(150);
            }
            main(()->{
                check(keyboardVisible(first),"keyboard visible during editor tools");
                TextView todo=text(first.getWindow().getDecorView(),"待办");int[] location=new int[2];todo.getLocationOnScreen(location);
                check(location[1]+todo.getHeight()<=keyboardTop(first)+8,"toolbar stays above keyboard");
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
            final Activity list=open(null);
            main(()->{
                TextView defaultBook=text(list.getWindow().getDecorView(),"默认笔记本");check(defaultBook!=null,"notebook tabs visible");defaultBook.performClick();
                check("".equals(field(list,"notebookFilter")),"default tab filters notes");check(defaultBook.isSelected(),"selected tab accessibility state");
                text(list.getWindow().getDecorView(),"全部").performClick();check("*".equals(field(list,"notebookFilter")),"all tab restores list");
            });
            screenshot("notebook-tabs");
            main(()->{TextView title=text(list.getWindow().getDecorView(),"待办清单 · 验证");check(title!=null,"fixture card in list");((View)title.getParent().getParent()).performLongClick();
                android.widget.PopupWindow popup=(android.widget.PopupWindow)field(list,"cardMenu");check(popup!=null&&popup.isShowing(),"rounded card menu appears");
                check(text(popup.getContentView(),"置顶") instanceof MemoToolButton,"pin is icon button");check(text(popup.getContentView(),"删除") instanceof MemoToolButton,"delete is icon button");
                text(popup.getContentView(),"置顶").performClick();});
            main(()->{boolean pinned=false;for(MemoStore.Note item:MemoStore.all(c))if(item.id.equals(note.id))pinned=item.pinned;check(pinned,"pin icon changes pin state");
                TextView title=text(list.getWindow().getDecorView(),"待办清单 · 验证");((View)title.getParent().getParent()).performLongClick();});
            screenshot("rounded-menu");
            main(()->{android.widget.PopupWindow popup=(android.widget.PopupWindow)field(list,"cardMenu");check(text(popup.getContentView(),"取消置顶")!=null,"unpin icon available");text(popup.getContentView(),"删除").performClick();});tapText("取消");
            main(()->{boolean found=false;for(MemoStore.Note item:MemoStore.all(c))if(item.id.equals(note.id))found=true;check(found,"delete confirmation cancellation preserves note");
                TextView title=text(list.getWindow().getDecorView(),"待办清单 · 验证");((View)title.getParent().getParent()).performLongClick();android.widget.PopupWindow popup=(android.widget.PopupWindow)field(list,"cardMenu");text(popup.getContentView(),"取消置顶").performClick();list.finish();});
            long due=System.currentTimeMillis()-1000L;
            MemoStore.Note scheduled=MemoStore.saveDraft(c,note.id,"待办提醒测试",body,due);
            check(scheduled.remindAt==due,"reminder persisted with body");
            if(android.os.Build.VERSION.SDK_INT>=33){android.os.ParcelFileDescriptor permission=getUiAutomation().executeShellCommand("pm grant com.afs.guard android.permission.POST_NOTIFICATIONS");permission.close();Thread.sleep(200);}
            check(MemoReminder.allowed(c),"emulator notification permission");
            MemoReminder.fire(c,note.id,due);MemoReminder.fire(c,note.id,due);
            android.app.NotificationManager notifications=(android.app.NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);int notices=0;
            for(int retry=0;retry<20;retry++){boolean posted=false;for(android.service.notification.StatusBarNotification notice:notifications.getActiveNotifications())if(note.id.equals(notice.getTag()))posted=true;if(posted)break;Thread.sleep(100);}
            for(android.service.notification.StatusBarNotification notice:notifications.getActiveNotifications())if(note.id.equals(notice.getTag())){notices++;check(notice.getNotification().contentIntent!=null,"notification has note destination");}
            check(notices==1,"reminder delivered once despite duplicate trigger");
            MemoStore.Note delivered=null;for(MemoStore.Note n:MemoStore.all(c))if(n.id.equals(note.id))delivered=n;
            check(delivered!=null&&delivered.remindedAt==due,"delivery marked durably");
            MemoStore.remove(c,note.id);int remaining=waitNotices(notifications,note.id,0);
            check(remaining==0,"deletion cancels reminder notification");
            MemoStore.restore(c,note.id);MemoReminder.fire(c,note.id,due);remaining=0;for(android.service.notification.StatusBarNotification notice:notifications.getActiveNotifications())if(note.id.equals(notice.getTag()))remaining++;
            check(remaining==0,"restoring delivered reminder does not repeat notification");
            long future=System.currentTimeMillis()+3600000L;MemoStore.saveDraft(c,note.id,"未来提醒",body,future);
            new MemoReminderReceiver().onReceive(c,new Intent(Intent.ACTION_TIME_CHANGED));MemoReminder.fire(c,note.id,future);
            MemoStore.Note futureNote=null;for(MemoStore.Note n:MemoStore.all(c))if(n.id.equals(note.id))futureNote=n;
            check(futureNote!=null&&futureNote.remindedAt==0,"time-change reschedule cannot fire early");
            long actualAlarm=System.currentTimeMillis()+6000L;MemoStore.saveDraft(c,note.id,"系统闹钟验证",body,actualAlarm);
            boolean alarmDelivered=false;
            for(int retry=0;retry<40&&!alarmDelivered;retry++){
                for(MemoStore.Note n:MemoStore.all(c))if(n.id.equals(note.id)&&n.remindedAt==actualAlarm)alarmDelivered=true;
                if(!alarmDelivered)Thread.sleep(1000);
            }
            check(alarmDelivered,"actual AlarmManager broadcast delivers notification");
            waitNotices(notifications,note.id,1);
            android.app.Notification targetNotice=null;
            for(android.service.notification.StatusBarNotification notice:notifications.getActiveNotifications())if(note.id.equals(notice.getTag()))targetNotice=notice.getNotification();
            check(targetNotice!=null,"actual alarm notification visible");
            MemoStore.Note other=MemoStore.create(c,"另一个编辑页面","保留草稿");Activity otherPage=open(other.id);
            ActivityMonitor destination=addMonitor("com.afs.guard.MemoActivity",null,false);targetNotice.contentIntent.send();
            Activity destinationPage=waitForMonitorWithTimeout(destination,5000);removeMonitor(destination);
            check(destinationPage!=null,"notification opens memo activity");if(destinationPage!=null){opened.add(destinationPage);check(note.id.equals(field(destinationPage,"entryId")),"notification selects exact note");}
            check(!otherPage.isDestroyed(),"opening reminder preserves other editor");
            result.putString("stream","Memo checklist device: "+checks+" checks passed\n");
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));}
        finally{
            if(fixtureId!=null)MemoReminder.cancel(c,fixtureId);
            restore(prefs,"memos_v1",original);restore(prefs,"memos_trash_v1",trash);restore(ui,"theme",theme);
            for(Activity a:opened)try{main(a::finish);}catch(Throwable ignored){}
        }
        finish(result.getString("stream","").startsWith("Memo checklist device:")?-1:0,result);
    }
}
