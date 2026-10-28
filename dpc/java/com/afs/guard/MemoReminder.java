package com.afs.guard;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

/** Note-only, local, one-shot reminders. Never changes Guard or learning data. */
public final class MemoReminder {
    private MemoReminder(){}
    static final String ACTION="com.afs.guard.MEMO_REMINDER",CHANNEL="memo_reminders";
    private static PendingIntent alarm(Context c,String id,long time){
        Intent intent=new Intent(c,MemoReminderReceiver.class).setAction(ACTION).setData(Uri.parse("afs-memo:reminder/"+Uri.encode(id)))
                .putExtra("id",id).putExtra("at",time);
        return PendingIntent.getBroadcast(c,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    public static boolean allowed(Context c){
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager==null||!manager.areNotificationsEnabled())return false;
        if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=manager.getNotificationChannel(CHANNEL);if(channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;}
        return true;
    }
    public static void schedule(Context c,MemoStore.Note note){
        cancel(c,note.id);if(note.remindAt<=System.currentTimeMillis()||note.remindedAt==note.remindAt)return;
        AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(manager!=null)try{manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,note.remindAt,alarm(c,note.id,note.remindAt));}
        catch(RuntimeException e){android.util.Log.w("afs.memo","Reminder schedule unavailable",e);}
    }
    public static void cancel(Context c,String id){
        AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);if(manager!=null)manager.cancel(alarm(c,id,0));
        NotificationManager notifications=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(notifications!=null)notifications.cancel(id,41);
    }
    public static void reschedule(Context c){for(MemoStore.Note note:MemoStore.all(c))schedule(c,note);}
    public static void fire(Context c,String id,long expected){
        if(!allowed(c))return;MemoStore.Note note=MemoStore.claimReminder(c,id,expected,System.currentTimeMillis());if(note==null)return;
        NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(manager==null)return;
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"备忘录提醒",NotificationManager.IMPORTANCE_DEFAULT));
        Intent target=new Intent(c,MemoActivity.class).setData(Uri.parse("afs-memo:open/"+Uri.encode(id))).putExtra("id",id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open=PendingIntent.getActivity(c,0,target,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("备忘录提醒").setContentText("你设置的待办提醒时间到了")
                .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE);
        try{manager.notify(id,41,builder.build());}catch(SecurityException ignored){}
    }
}
