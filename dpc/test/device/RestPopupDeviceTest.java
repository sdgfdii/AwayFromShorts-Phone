package com.afs.guard;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;

/** No reminder settings or learning data are written by this test. */
public final class RestPopupDeviceTest extends Instrumentation {
    int checks;
    void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    boolean contains(View v,String s){
        if(v instanceof TextView && s.contentEquals(((TextView)v).getText()))return true;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(contains(((ViewGroup)v).getChildAt(i),s))return true;
        return false;
    }
    void shell(String command)throws Exception{
        try(java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))){byte[] b=new byte[1024];while(in.read(b)!=-1){}}
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){Bundle out=new Bundle();int status=0;RestReminderActivity popup=null;Context c=getTargetContext();
        ActivityMonitor monitor=addMonitor(RestReminderActivity.class.getName(),null,false);
        try{
            check(Build.MODEL.toLowerCase().contains("sdk"),"emulator required");
            check(RestReminderActivity.backgroundAllowed(c),"device owner can show background popup");
            long last=ReminderStore.lastFired(c);
            check(!MemoReminder.allowed(c),"notifications denied for independence test");
            shell("input keyevent KEYCODE_HOME");Thread.sleep(500);
            runOnMainSync(()->RestAlert.show(c,30));
            popup=(RestReminderActivity)waitForMonitorWithTimeout(monitor,8000);
            check(popup!=null,"background due reminder opens a page without notification permission");
            final RestReminderActivity first=popup;
            for(int i=0;i<100&&!popup.hasWindowFocus();i++)Thread.sleep(50);
            android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
            try(java.io.OutputStream image=new java.io.FileOutputStream(new java.io.File(c.getExternalFilesDir(null),"rest-popup-qa.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);}
            shot.recycle();
            check(popup.hasWindowFocus(),"reminder popup is visible above launcher");
            check(contains(popup.getWindow().getDecorView(),T.s("该歇会儿了")),"popup title visible");
            check(contains(popup.getWindow().getDecorView(),T.f("已经连续 %d 分钟没休息，站起来走走，看看远处。",30)),"popup shows configured interval");
            runOnMainSync(()->RestAlert.show(c,45));Thread.sleep(500);
            check(contains(popup.getWindow().getDecorView(),T.f("已经连续 %d 分钟没休息，站起来走走，看看远处。",45)),"repeated request updates the same page");
            check(!first.isFinishing(),"duplicate reminder does not stack another task");
            runOnMainSync(()->RestAlert.clear(c));Thread.sleep(400);
            check(first.isFinishing(),"turning reminders off dismisses popup");
            Activity host=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(400);
            removeMonitor(monitor); monitor=addMonitor(RestReminderActivity.class.getName(),null,false);
            runOnMainSync(()->RestAlert.show(c,15));
            popup=(RestReminderActivity)waitForMonitorWithTimeout(monitor,8000);
            check(popup!=null,"foreground reminder opens popup");
            final RestReminderActivity front=popup;
            runOnMainSync(front::onBackPressed);Thread.sleep(600);
            check(front.isFinishing()&&host.hasWindowFocus(),"dismiss returns to previous app page");
            check(ReminderStore.lastFired(c)==last,"presenting and dismissing do not change reminder schedule");
            out.putString("stream","Rest popup device: "+checks+" checks passed\n");status=-1;
        }catch(Throwable error){out.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{
            removeMonitor(monitor);runOnMainSync(()->RestAlert.clear(c));
            finish(status,out);
        }
    }
}
