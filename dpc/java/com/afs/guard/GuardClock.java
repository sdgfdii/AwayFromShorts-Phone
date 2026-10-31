package com.afs.guard;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;
import java.util.TimeZone;

/** Android 7/8 cannot prohibit timezone edits. Their schedules use a pinned zone. */
public final class GuardClock extends Application {
    @Override public void onCreate() { super.onCreate();
        /* 这一层在**第一帧之前**跑，任何几十毫秒的活都会变成白屏/卡顿。
           所以同步做的只有「必须立刻就对」的两件：语言开关（决定 T.s 的行为）和
           备份回滚（牵扯数据正确性，晚一步就可能读到半截数据）。
           翻译表、额度迁移、各类缓存全部丢到后台。 */
        try{T.refresh(this);}catch(RuntimeException ignored){}
        LocalBackup.startup(this);
        pin(this);
        // 中文用户占绝大多数，翻译表对他完全没用 —— 丢后台；
        // 选了 English 的必须先把表读进来，否则第一帧会先闪一下中文。
        boolean english = false;
        try{english = T.english();}catch(RuntimeException ignored){}
        if (english) {
            try{T.init(this);}catch(RuntimeException ignored){}
        } else {
            runAsync("afs-i18n", () -> { try{T.init(GuardClock.this);}catch(RuntimeException ignored){} });
        }
        runAsync("afs-quota-migrate", () -> {
            // 旧的全天额度并进每日限额格子，只跑一次；拿不到用量时留到下次。
            try{DailyQuotaStore.migrateLegacy(GuardClock.this);}
            catch(RuntimeException error){android.util.Log.w("AFS","legacy quota migration: "+error.getClass().getSimpleName());}
        });
        Startup.prewarm(this);
    }

    /** 后台跑一件"失败了也不该影响启动"的事。 */
    private static void runAsync(String name, Runnable work) {
        Thread thread = new Thread(work, name);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    static synchronized TimeZone pin(Context c) {
        if (Build.VERSION.SDK_INT >= 28) return TimeZone.getDefault();
        SharedPreferences p = c.getSharedPreferences("afs_guard", Context.MODE_PRIVATE);
        String id = p.getString("schedule_timezone", "");
        if (id.isEmpty()) {
            id = TimeZone.getDefault().getID();
            if (!p.edit().putString("schedule_timezone", id).commit())
                throw new IllegalStateException(T.s("无法保存冻结时区"));
        }
        TimeZone zone = TimeZone.getTimeZone(id);
        // Also keep learning-day boundaries and their UI consistent with schedules.
        TimeZone.setDefault(zone);
        return zone;
    }

    static Calendar now(Context c) { return Calendar.getInstance(pin(c)); }
}
