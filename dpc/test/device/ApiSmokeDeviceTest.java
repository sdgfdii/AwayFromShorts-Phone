package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;

/** Opens the major screens on the installed Android runtime. */
public final class ApiSmokeDeviceTest extends Instrumentation {
    public void onCreate(Bundle args) { super.onCreate(args); start(); }
    public void onStart() {
        Bundle result = new Bundle(); int count = 0;
        try {
            for (String page : new String[]{"DataManagementActivity","MainActivity","CheckinActivity","FreezeBoardActivity",
                    "StatsActivity","HistoryActivity","MemoryActivity","QuizActivity",
                    "MemoActivity","ToolkitActivity","CardManagementActivity","ThemeActivity"}) {
                Intent intent = new Intent().setClassName(getTargetContext(),"com.afs.guard."+page)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Activity activity = startActivitySync(intent);
                waitForIdleSync();
                if (activity == null || activity.isFinishing()) throw new AssertionError(page+" did not open");
                count++;
                runOnMainSync(activity::finish);
                waitForIdleSync();
            }
            result.putString("stream","API smoke device: "+count+" screens passed\n"); finish(-1,result);
        } catch (Throwable e) {
            result.putString("stream","FAILED "+android.util.Log.getStackTraceString(e)); finish(1,result);
        }
    }
}
