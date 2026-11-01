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
    static final String ACTION="com.afs.guard.MEMO_REMINDER";
    /** 通道 id 带版本号：旧通道一旦被降级，应用改不回来，只能换个新 id 按 HIGH 重建。 */
    static final String CHANNEL="memo_reminders_v2";
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
        if(manager==null)return;
        PendingIntent pending=alarm(c,note.id,note.remindAt);
        try{
            // 走闹钟级排点：ColorOS 会把普通 setAndAllowWhileIdle 往后推（实测能推几天），
            // 到点提醒就废了。能排精确闹钟就用 setAlarmClock，退而求其次才是 exact / idle。
            boolean exact=true;
            if(Build.VERSION.SDK_INT>=31){
                try{exact=manager.canScheduleExactAlarms();}catch(Throwable ignored){}
            }
            if(exact){
                manager.setAlarmClock(new AlarmManager.AlarmClockInfo(note.remindAt,pending),pending);
                return;
            }
        }catch(RuntimeException ignored){
        }
        try{manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,note.remindAt,pending);}
        catch(RuntimeException e){
            try{manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,note.remindAt,pending);}
            catch(RuntimeException unavailable){android.util.Log.w("afs.memo","Reminder schedule unavailable",unavailable);}
        }
    }
    public static void cancel(Context c,String id){
        AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);if(manager!=null)manager.cancel(alarm(c,id,0));
        NotificationManager notifications=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(notifications!=null)notifications.cancel(id,41);
    }
    public static void reschedule(Context c){for(MemoStore.Note note:MemoStore.all(c))schedule(c,note);}

    /**
     * 兜底重排：闹钟被系统清掉（覆盖安装、重启后没收到广播、省电策略重置）时靠它补回来。
     * 10 分钟一次就够，别让守护节拍每 30 秒解析一遍备忘录。
     */
    private static long lastRescheduleAt;
    public static void rescheduleThrottled(Context c){
        long now=System.currentTimeMillis();
        if(lastRescheduleAt!=0L&&now>=lastRescheduleAt&&now-lastRescheduleAt<10*60*1000L)return;
        lastRescheduleAt=now;
        try{reschedule(c);}catch(RuntimeException ignored){}
    }
    public static void fire(Context c,String id,long expected){
        if(!allowed(c))return;MemoStore.Note note=MemoStore.claimReminder(c,id,expected,System.currentTimeMillis());if(note==null)return;
        NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(manager==null)return;
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(CHANNEL,T.s("备忘录提醒"),NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(T.s("备忘录里设置的提醒时间到了"));
            channel.enableVibration(true);
            channel.setShowBadge(true);
            manager.createNotificationChannel(channel);
        }
        Intent target=new Intent(c,MemoActivity.class).setData(Uri.parse("afs-memo:open/"+Uri.encode(id))).putExtra("id",id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open=PendingIntent.getActivity(c,0,target,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(T.s("备忘录提醒")).setContentText(T.s("你设置的待办提醒时间到了"))
                .setContentIntent(open).setWhen(System.currentTimeMillis())
                .setCategory(Notification.CATEGORY_REMINDER).setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE);
        try{manager.notify(id,41,builder.build());}catch(SecurityException ignored){}
    }
}
