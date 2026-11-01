package com.afs.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 到点翻转状态，然后立刻排下一次。 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.i(Guard.TAG, "alarm fired");
        Guard.apply(ctx);
        Guard.schedule(ctx);
        Guard.ensureService(ctx);
        NetBlockService.reconcile(ctx);   // 断网调整时段边界靠这里翻隧道
    }
}
