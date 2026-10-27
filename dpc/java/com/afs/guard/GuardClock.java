package com.afs.guard;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;
import java.util.TimeZone;

/** Android 7/8 cannot prohibit timezone edits. Their schedules use a pinned zone. */
public final class GuardClock extends Application {
    @Override public void onCreate() { super.onCreate(); pin(this); }

    static synchronized TimeZone pin(Context c) {
        if (Build.VERSION.SDK_INT >= 28) return TimeZone.getDefault();
        SharedPreferences p = c.getSharedPreferences("afs_guard", Context.MODE_PRIVATE);
        String id = p.getString("schedule_timezone", "");
        if (id.isEmpty()) {
            id = TimeZone.getDefault().getID();
            if (!p.edit().putString("schedule_timezone", id).commit())
                throw new IllegalStateException("无法保存冻结时区");
        }
        TimeZone zone = TimeZone.getTimeZone(id);
        // Also keep learning-day boundaries and their UI consistent with schedules.
        TimeZone.setDefault(zone);
        return zone;
    }

    static Calendar now(Context c) { return Calendar.getInstance(pin(c)); }
}
