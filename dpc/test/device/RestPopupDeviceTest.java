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
    View described(View v,String s){
        if(s.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){
            View found=described(((ViewGroup)v).getChildAt(i),s);if(found!=null)return found;
        }
        return null;
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
            if(Build.VERSION.SDK_INT>=33)check(!MemoReminder.allowed(c),"notifications denied for independence test");
            shell("input keyevent KEYCODE_HOME");Thread.sleep(500);
            runOnMainSync(()->RestAlert.show(c,30));
            popup=(RestReminderActivity)waitForMonitorWithTimeout(monitor,8000);
            check(popup!=null,"background due reminder opens a page without notification permission");
            final RestReminderActivity first=popup;
            for(int i=0;i<100&&!popup.hasWindowFocus();i++)Thread.sleep(50);
            Thread.sleep(500); // Wait for the first rendered frame before capturing.
            android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
            try(java.io.OutputStream image=new java.io.FileOutputStream(new java.io.File(c.getExternalFilesDir(null),"rest-popup-qa.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);}
            shot.recycle();
            check(popup.hasWindowFocus(),"reminder popup is visible above launcher");
            check(described(popup.getWindow().getDecorView(),T.f("已经连续 %d 分钟没休息，站起来走走，看看远处。",30))!=null,"configured interval accessible");
            int startCard = Math.floorMod(c.getSharedPreferences("afs_rest",0).getInt("next_card",0)+6,7);
            for(int i=0;i<8;i++){
                final int expected=(startCard+i)%7;
                View root=popup.getWindow().getDecorView();
                ImageView picture=(ImageView)described(root,T.f("已经连续 %d 分钟没休息，站起来走走，看看远处。",30));
                check(picture!=null&&picture.getDrawable()!=null,"card "+expected+" decodes");
                RestReminderCard card=(RestReminderCard)picture.getParent();
                check(card.getChildCount()==(expected==2?10:2),"integrated buttons only");
                for(int j=1;j<card.getChildCount();j++){
                    View button=card.getChildAt(j);
                    check(button.getWidth()>=48*c.getResources().getDisplayMetrics().density-1 && button.getHeight()>=48*c.getResources().getDisplayMetrics().density-1,"accessible touch target");
                    check(button.getLeft()>=0&&button.getRight()<=card.getWidth()&&button.getTop()>=0&&button.getBottom()<=card.getHeight(),"button within artwork");
                }
                android.graphics.Bitmap capture=getUiAutomation().takeScreenshot();
                try(java.io.OutputStream file=new java.io.FileOutputStream(new java.io.File(c.getExternalFilesDir(null),"rest-integrated-"+expected+".png"))){capture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file);}
                capture.recycle();
                if(i==0){
                    Thread.sleep(5400);
                    check(picture.getDrawable()!=null&&picture.getParent()==card,"same reminder stays fixed");
                }
                if(i<7){runOnMainSync(()->RestAlert.show(c,30));Thread.sleep(700);}
            }
            runOnMainSync(()->RestAlert.show(c,45));Thread.sleep(500);
            check(described(popup.getWindow().getDecorView(),T.f("已经连续 %d 分钟没休息，站起来走走，看看远处。",45))!=null,"repeated request updates the same page");
            check(!first.isFinishing(),"duplicate reminder does not stack another task");
            runOnMainSync(()->RestAlert.clear(c));Thread.sleep(400);
            check(first.isFinishing(),"turning reminders off dismisses popup");
            Activity host=startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(400);
            removeMonitor(monitor); monitor=addMonitor(RestReminderActivity.class.getName(),null,false);
            runOnMainSync(()->RestAlert.show(c,15));
            popup=(RestReminderActivity)waitForMonitorWithTimeout(monitor,8000);
            check(popup!=null,"foreground reminder opens popup");
            final RestReminderActivity front=popup;
            for(int i=0;i<100&&!front.hasWindowFocus();i++)Thread.sleep(50);
            check(front.hasWindowFocus(),"foreground popup is ready for dismissal");
            View integrated=described(front.getWindow().getDecorView(),T.s("知道了"));
            int[] xy=new int[2];runOnMainSync(()->integrated.getLocationOnScreen(xy));
            shell("input tap "+(xy[0]+integrated.getWidth()/2)+" "+(xy[1]+integrated.getHeight()/2));
            for(int i=0;i<100&&(!front.isFinishing()||!host.hasWindowFocus());i++)Thread.sleep(50);
            check(front.isFinishing()&&host.hasWindowFocus(),"dismiss returns to previous app page (finishing="+front.isFinishing()+", hostFocus="+host.hasWindowFocus()+", hostFinishing="+host.isFinishing()+")");
            check(ReminderStore.lastFired(c)==last,"presenting and dismissing do not change reminder schedule");
            out.putString("stream","Rest popup device: "+checks+" checks passed\n");status=-1;
        }catch(Throwable error){out.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{
            removeMonitor(monitor);runOnMainSync(()->RestAlert.clear(c));
            finish(status,out);
        }
    }
}
