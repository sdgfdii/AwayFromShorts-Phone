package com.afs.guard;

import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import java.io.*;
import java.util.*;
import org.json.*;

/** Dedicated-emulator rule checks. Uses production stores/engine and real DPM.
 * Future boundary cases use explicit Calendars; the device wall clock is not changed.
 * VPN conflict input is a recorded-state fixture, not a claim of a real second tunnel.
 */
public final class PluginRuleContinuityTest extends Instrumentation {
    private int checks;private final StringBuilder evidence=new StringBuilder();
    private void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);evidence.append("PASS ").append(label).append('\n');}
    private void suspended(Context c,String pkg,boolean expected,String label)throws Exception{check(Guard.dpm(c).isPackageSuspended(Guard.admin(c),pkg)==expected,label);}
    private void awaitReason(Context c,String pkg,int expected)throws Exception{long until=android.os.SystemClock.elapsedRealtime()+6000;while(Guard.blockReasonNow(c,pkg)!=expected&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(50);check(Guard.blockReasonNow(c,pkg)==expected,"original reason observed after bounded legacy cache: "+expected);}
    private void restore(SharedPreferences p,Map<String,?> values){SharedPreferences.Editor e=p.edit().clear();for(Map.Entry<String,?> row:values.entrySet()){String k=row.getKey();Object v=row.getValue();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));}if(!e.commit())throw new AssertionError("fixture restore failed");}
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void onStart(){Bundle result=new Bundle();int code=1;Context c=getTargetContext();String target=getContext().getPackageName();File cards=new File(c.getCacheDir(),"r2-rule-"+UUID.randomUUID()+".json");
        Map<String,Map<String,?>> originals=new LinkedHashMap<>();boolean started=false;
        try{
            PluginBaselineDataTest.requireEmulator();check(Guard.isOwner(c),"existing device owner retained");checks+=PluginBaselineDataTest.verify(c);
            for(String name:new String[]{"afs_guard",StudyGate.TABOO_SP,"afs_learning_link","afs_checkin_penalty","afs_netblock",NetWindow.SP})originals.put(name,new HashMap<>(c.getSharedPreferences(name,0).getAll()));started=true;
            CardStore.setTestFile(cards);
            Calendar now=GuardClock.now(c);long stamp=now.getTimeInMillis();int minute=now.get(Calendar.HOUR_OF_DAY)*60+now.get(Calendar.MINUTE);
            Rule allowed=new Rule(Rule.ALL_DAYS,new int[][]{{(minute+1380)%1440,(minute+60)%1440}},true,false);
            RuleStore.put(c,target,allowed);StudyGate.invalidate(c);Guard.apply(c);suspended(c,target,false,"normal scheduled release reaches real DPM");
            Calendar past=(Calendar)now.clone();past.add(Calendar.DAY_OF_MONTH,-3);long old=past.getTimeInMillis();CardData missing=new CardData(old);missing.summary(old).configure("daily",1,CycleSummary.day(old),old);
            try(FileOutputStream out=new FileOutputStream(cards)){out.write(missing.db.toString().getBytes("UTF-8"));}CardStore.setTestFile(cards);StudyGate.invalidate(c);
            check(CardStore.summaryHolding(c,stamp),"overdue summary crosses cycles in original store");awaitReason(c,target,2);Guard.apply(c);check(Guard.blockReasonNow(c,target)==2,"original engine identifies learning extension");suspended(c,target,true,"learning extension actually suspends application");
            Set<String> keys=RuleStore.keyApps(c),withKey=new HashSet<>(keys);withKey.add(target);RuleStore.setKeyApps(c,withKey);Guard.apply(c);suspended(c,target,false,"key application exemption survives plugin migration");RuleStore.setKeyApps(c,keys);
            for(int i=0;i<10&&CardStore.summaryHolding(c,stamp);i++){CycleSummary.Period pending=CardStore.summaryPending(c,stamp);check(pending!=null&&CardStore.completeSummary(c,pending.key,"QA"),"overdue summary completed in its own ledger");}
            StudyGate.invalidate(c);awaitReason(c,target,0);Guard.apply(c);suspended(c,target,false,"completion releases only the learning extension");
            Rule scheduled=new Rule(Rule.ALL_DAYS,new int[][]{{(minute+1430)%1440,(minute+10)%1440}},true,true);RuleStore.put(c,target,scheduled);Guard.apply(c);suspended(c,target,true,"current timed freeze still enforced");
            long release=PenaltyTiming.release(scheduled,stamp);check(release>stamp,"actual schedule has a future release");PenaltyStore.add(c,60000,stamp);JSONObject penalty=new JSONObject(c.getSharedPreferences(StudyGate.TABOO_SP,0).getString("schedule_penalties","{}")).getJSONObject(target);
            check(penalty.getLong("from")==release&&penalty.getLong("until")==release+60000,"taboo penalty starts at scheduled release rather than claim time");check(!PenaltyStore.holding(c,target,stamp),"future penalty is not applied prematurely");
            Calendar after=(Calendar)now.clone();after.setTimeInMillis(release+30000);int active=Guard.blockReason(scheduled,false,false,PenaltyStore.holding(c,target,after.getTimeInMillis()),after);check(active==3,"boundary recomputation enters appended penalty");Guard.setSuspended(Guard.dpm(c),Guard.admin(c),new String[]{target},active!=0);suspended(c,target,true,"recomputed appended penalty delivered to real DPM");
            after.setTimeInMillis(release+60001);int finished=Guard.blockReason(scheduled,false,false,PenaltyStore.holding(c,target,after.getTimeInMillis()),after);check(finished==0,"boundary recomputation releases expired penalty");Guard.setSuspended(Guard.dpm(c),Guard.admin(c),new String[]{target},finished!=0);suspended(c,target,false,"expired penalty release delivered to real DPM");
            Rule midnight=new Rule(Rule.ALL_DAYS,new int[][]{{23*60,30}},false,true);Calendar clock=(Calendar)now.clone();clock.set(Calendar.HOUR_OF_DAY,23);clock.set(Calendar.MINUTE,50);clock.set(Calendar.SECOND,0);clock.set(Calendar.MILLISECOND,0);
            for(int i=0;i<3;i++){Calendar at=(Calendar)clock.clone();at.add(Calendar.MINUTE,i==0?0:i==1?20:50);boolean expected=i<2;boolean blocked=Guard.blocked(midnight,at);check(blocked==expected,"cross-midnight engine boundary "+i);Guard.setSuspended(Guard.dpm(c),Guard.admin(c),new String[]{target},blocked);suspended(c,target,expected,"cross-midnight evaluated result reaches DPM "+i);}
            // The production fallback is exercised against its documented persisted
            // conflict input. No VPN permission or device network is changed here.
            RuleStore.put(c,target,FreezeSlotStore.withoutSchedule());Guard.apply(c);
            c.getSharedPreferences(NetWindow.SP,0).edit().putInt("start",(minute+60)%1440).putInt("end",(minute+61)%1440).commit();
            c.getSharedPreferences("afs_netblock",0).edit().putBoolean("on",true).putString("pkgs",target).putBoolean("running",false).putString("why",NetBlockService.WHY_OTHER_VPN).putLong("why_at",stamp-30000).putLong("tried",stamp-30000).putInt("fallback_mode",NetBlockStore.FALLBACK_FREEZE).commit();
            NetFallback.reconcile(c);suspended(c,target,true,"recorded VPN conflict uses original real DPM fallback");NetBlockStore.setOn(c,false);NetFallback.reconcile(c);suspended(c,target,false,"removing fallback condition releases its target");
            code=-1;
        }catch(Throwable error){evidence.append("FAILED ").append(android.util.Log.getStackTraceString(error));}
        finally{try{if(started){for(Map.Entry<String,Map<String,?>> row:originals.entrySet())restore(c.getSharedPreferences(row.getKey(),0),row.getValue());CardStore.setTestFile(null);new android.util.AtomicFile(cards).delete();StudyGate.invalidate(c);Guard.dpm(c).setPackagesSuspended(Guard.admin(c),new String[]{target},false);Guard.dpm(c).setUninstallBlocked(Guard.admin(c),target,false);Guard.apply(c);checks+=PluginBaselineDataTest.verify(c);}}catch(Throwable error){code=1;evidence.append("RESTORE FAILED ").append(android.util.Log.getStackTraceString(error));}}
        result.putString("stream",(code==-1?"Plugin rule continuity: "+checks+" checks passed\n":"")+evidence);finish(code,result);
    }
}
