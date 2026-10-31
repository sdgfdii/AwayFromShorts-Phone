package com.afs.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class MemoReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        if(MemoReminder.ACTION.equals(intent.getAction())){
            String id=intent.getStringExtra("id");if(id!=null)MemoReminder.fire(context,id,intent.getLongExtra("at",0));
        }else if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())||Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                ||Intent.ACTION_TIME_CHANGED.equals(intent.getAction())||Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction()))MemoReminder.reschedule(context);
    }
}
