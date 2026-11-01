package com.afs.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.Calendar;

/**
 * 常驻守护：每 30 秒把状态落一遍。
 *
 * 为什么必须有它：ColorOS 会把 AlarmManager 的闹钟整体往后推（实测 setAlarmClock 排的点
 * 被 adjustment 推迟到 2 天 23 小时之后，连支付宝的闹钟一样被推），于是"到点自动冻结/放行"
 * 完全失效，只有用户手动打开 App 那一刻才冻得上。
 *
 * 前台服务 + 常驻通知是这台机器上唯一稳定的节拍源；闹钟继续保留当快路径（能响就更快）。
 * 冻结本身是 Device Owner 的 setPackagesSuspended，不依赖进程活着 —— 进程被杀最多是
 * 晚几十秒才切换，重新拉起后会立刻纠正。
 */
public class GuardService extends Service {

    private static final String CHANNEL = "afs_guard";
    private static final int NOTI_ID = 1001;
    private static final long TICK_MS = 30_000L;
    private static final String WAKELOCK_TAG = "afs:guard";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private int tickCount;
    private String lastNoticeText;
    private final java.util.concurrent.ExecutorService quotaWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean quotaBusy;
    /**
     * 额度节拍：收束时段内 5 秒一问，时段外 60 秒。
     *
     * 时段外根本不计时、不判超（TimeWindow 统一关了门），查使用记录纯属白烧 CPU ——
     * 这一项从 5s 放到 60s，是本轮耗电优化里最大的一块：使用记录查询在 ColorOS
     * 上偶尔要几百毫秒，以前一天 24 小时 × 5 秒全都在查。
     */
    private final Runnable quotaTick=new Runnable(){public void run(){
        if(!quotaBusy){quotaBusy=true;quotaWorker.execute(()->{try{if(DailyQuotaStore.enforcementChanged(GuardService.this))Guard.apply(GuardService.this);}catch(Throwable error){Log.e(Guard.TAG,"daily quota tick failed",error);}finally{quotaBusy=false;}Guard.kickQuotaExceeded(GuardService.this);});}
        handler.postDelayed(this,TimeWindow.on(GuardService.this)?5000:60000);
    }};

    /**
     * 锁机节拍：5 秒问一次「现在该不该锁」。
     *
     * 单开一条，不塞进 30 秒的状态 tick —— 那一条里面有 setPackagesSuspended 和
     * 使用记录查询，偶尔要几百毫秒；锁机要的是"到点就弹"，不能被它拖住。
     */
    /**
     * 锁机节拍：亮屏 5 秒一问，灭屏 30 秒。
     *
     * 单开一条，不塞进 30 秒的状态 tick —— 那一条里面有 setPackagesSuspended 和
     * 使用记录查询，偶尔要几百毫秒；锁机要的是"到点就弹"，不能被它拖住。
     * 灭屏时锁机没有对象（用户根本不在用手机），放宽到 30 秒不吃任何体验；
     * 亮屏检测用 isInteractive，不用读电池，开销可忽略。
     */
    private final Runnable lockTick = new Runnable() {
        @Override
        public void run() {
            try {
                LockEnforcer.tick(GuardService.this);
                RestAlert.tick(GuardService.this);
            } catch (Throwable error) {
                Log.e(Guard.TAG, "lock tick failed", error);
            }
            handler.postDelayed(this, interactive() ? 5000L : 30000L);
        }
    };

    private boolean interactive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                // 低频心跳：诊断"进程活着但 tick 死了"这种事 —— 没有它永远查不出来
                if (tickCount % 10 == 0) {
                    Log.i(Guard.TAG, "tick alive #" + tickCount);
                }
                tickCount++;
                Hub.kick(GuardService.this);
                // 20:00 判断点：今天打卡没打完 → 各应用本次冻结再延 30 分钟（一天只入账一次）
                CheckinPenalty.tick(GuardService.this);
                Guard.apply(GuardService.this);   // 内部只有状态变化时才调 setPackagesSuspended
                NetBlockService.reconcile(GuardService.this);   // 收束时段边界在这里翻隧道
                updateNotice();
            } catch (Throwable t) {
                Log.e(Guard.TAG, "tick failed", t);
            }
            // 灭屏放宽到 60 秒：冻结状态切换没人看着，晚一分钟无感；亮屏保持 30 秒。
            handler.postDelayed(this, interactive() ? TICK_MS : TICK_MS * 2);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTI_ID, buildNotification());
        acquireWakeLock();
        handler.post(tick);
        handler.post(quotaTick);
        handler.post(lockTick);
        Log.i(Guard.TAG, "service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被系统杀掉后自动拉起；onCreate 里已经挂了 tick，这里只需保证不重复挂
        handler.removeCallbacks(tick);
        handler.post(tick);
        handler.removeCallbacks(quotaTick);handler.post(quotaTick);
        handler.removeCallbacks(lockTick);handler.post(lockTick);
        // 上一条 START_STICKY 的实例可能没走 onDestroy，锁要补上
        acquireWakeLock();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(lockTick);
        handler.removeCallbacks(quotaTick);quotaWorker.shutdownNow();
        releaseWakeLock();
        Log.w(Guard.TAG, "service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 常驻 Partial Wakelock：灭屏后 CPU 不睡，30 秒的 Handler 节拍才跑得准。
     *
     * 只有前台服务不够 —— ColorOS 灭屏进 Doze 后 Handler 的 postDelayed 直接被冻住
     * （实测灭屏 70 秒才 tick 一次），到点切不切换全看运气。持有 PARTIAL_WAKE_LOCK
     * 期间 CPU 保持唤醒，tick 能按时跑。
     *
     * 代价是耗电：锁不设超时，服务活着就一直持着。这是"准时"换"电量"，
     * 用户明确选了这条路。要退回省电就只能靠闹钟（会被 ROM 推后）。
     *
     * 另外 deploy.sh 会把本包塞进 Doze 白名单（dumpsys deviceidle whitelist），
     * 那一步是前提 —— 光有 wakelock 挡不住系统级 Doze。
     */
    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm == null) {
                    Log.e(Guard.TAG, "wakelock: no PowerManager");
                    return;
                }
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
                Log.i(Guard.TAG, "wakelock acquired");
            }
        } catch (Throwable t) {
            Log.e(Guard.TAG, "wakelock acquire failed", t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.i(Guard.TAG, "wakelock released");
            }
        } catch (Throwable t) {
            Log.e(Guard.TAG, "wakelock release failed", t);
        }
    }

    /**
     * 常驻通知随学习闸门状态变化。
     *
     * 为什么要这么干：Android 没有"只让图标变灰、但应用还能用"的能力 ——
     * 图标灰 = 已被 suspend = 点不开。所以"提醒但不惩罚"只能落在通知上：
     * 目标没完成时，常驻通知本身就是那句提醒，每 30 秒跟着状态刷新。
     * 只在文案真的变了才 notify，避免每 30 秒刷一次通知栏。
     */
    private void updateNotice() {
        try {
            String text = StudyGate.noticeLine(this, Calendar.getInstance());
            if (text.equals(lastNoticeText)) {
                return;
            }
            lastNoticeText = text;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTI_ID, buildNotification());
            }
        } catch (Throwable t) {
            Log.w(Guard.TAG, "updateNotice failed: " + t);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, T.s("时间表守护"), NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Calendar now = Calendar.getInstance();
        boolean warn = StudyGate.noticeWarn(this, now);
        String text = StudyGate.noticeLine(this, now);
        lastNoticeText = text;
        b.setContentTitle(warn ? T.s("AwayFromShorts · 今天还没学完") : T.s("按时间表工作"))
         .setContentText(text)
         .setSmallIcon(android.R.drawable.ic_lock_lock)
         .setOngoing(true);
        return b.build();
    }
}
