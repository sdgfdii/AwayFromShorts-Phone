package com.afs.guard;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.Calendar;
import java.util.Map;

/** Real-device regression: never seeds goals, rules, quotas or check-in records. */
public final class NightLearningDeviceTest extends Instrumentation {
    private int checks;
    private void check(boolean value,String message){
        checks++;
        if(!value)throw new AssertionError(message);
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Context c=getTargetContext();
            Calendar now=GuardClock.now(c),due=(Calendar)now.clone();
            due.set(Calendar.HOUR_OF_DAY,20);due.set(Calendar.MINUTE,0);due.set(Calendar.SECOND,0);due.set(Calendar.MILLISECOND,0);
            Rule night=new Rule(127,new int[][]{{0,360}},true,true);
            Rule legacy=new Rule(127,new int[][]{{360,0}},true,false);
            Rule cross=new Rule(127,new int[][]{{1380,420}},true,true);
            Rule day=new Rule(127,new int[][]{{0,1320},{1380,0}},true,true);
            long origin=due.getTimeInMillis();
            check(LearningPolicy.learningRelease(night,origin)==0,"00:00-06:00 has no learning extension");
            check(LearningPolicy.learningRelease(legacy,origin)==0,"legacy overnight format has no learning extension");
            check(LearningPolicy.learningRelease(cross,origin)==0,"23:00-07:00 has no learning extension");
            check(LearningPolicy.learningRelease(day,origin)==origin+2*60*60_000L,"same-day 22:00 release keeps its learning extension");
            Calendar following=(Calendar)due.clone();following.add(Calendar.DAY_OF_MONTH,1);following.set(Calendar.HOUR_OF_DAY,9);
            check(LearningPolicy.dailyOrigin(following,origin,true)==origin,"overdue tasks retain their 20:00 origin");
            check(LearningPolicy.learningRelease(night,LearningPolicy.dailyOrigin(following,origin,true))==0,"crossing midnight cannot reactivate the overnight learning hold");
            Calendar early=(Calendar)due.clone();early.set(Calendar.HOUR_OF_DAY,19);
            check(LearningPolicy.dailyOrigin(early,0,true)==0,"new tasks do not start an early learning hold");
            String before=c.getSharedPreferences("afs_guard",0).getString("rules","");
            String quotas=c.getSharedPreferences("afs_daily_quota",0).getString("config","");
            Map<String,Rule> rules=RuleStore.all(c);StringBuilder actual=new StringBuilder();
            Guard.apply(c);
            String[] packages={"com.coloros.gallery3d","com.coloros.filemanager","com.android.documentsui","dev.anilbeesetti.nextplayer","com.heytap.market"};
            for(String pkg:packages){
                Rule rule=rules.get(pkg);if(rule==null)continue;
                check(LearningPolicy.learningRelease(rule,origin)==0,pkg+" actual rule excludes overnight linkage");
                check(!StudyGate.learningFor(c,rule,now),pkg+" actual learning hold is off");
                int reason=Guard.blockReasonNow(c,pkg);
                check(reason!=2,pkg+" is not blocked by learning linkage");
                boolean suspended=Guard.dpm(c).isPackageSuspended(Guard.admin(c),pkg);
                check(suspended==(reason!=0),pkg+" system freeze agrees with its actual rule (reason="+reason+")");
                actual.append(pkg).append(": reason=").append(reason).append(", suspended=").append(suspended).append('\n');
            }
            check(before.equals(c.getSharedPreferences("afs_guard",0).getString("rules","")),"existing freeze rules preserved");
            check(quotas.equals(c.getSharedPreferences("afs_daily_quota",0).getString("config","")),"quota limits and members preserved");
            c.startActivity(new Intent(c,FreezeBoardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            result.putString("stream","Night learning device: "+checks+" checks passed\n"+actual);
            finish(-1,result);
        }catch(Throwable error){result.putString("stream","Night learning device failed: "+android.util.Log.getStackTraceString(error));finish(0,result);}
    }
}
