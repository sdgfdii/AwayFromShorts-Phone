package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;

/** The same edit window is enforced at every write, including an already-open dialog. */
final class DailyQuotaStore {
    static final String SP="afs_daily_quota";
    private static long refreshed;
    private static String signature="";
    private static volatile boolean writeFailed;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(SP,0);}
    private static DailyQuotaData load(Context c,long now){SharedPreferences p=prefs(c);return new DailyQuotaData(p.getString("config","{}"),p.getString("usage",p.contains("config")?"{missing":"{}"),now,DayBoundary.hour(c));}
    static synchronized DailyQuotaData snapshot(Context c){return load(c,GuardClock.now(c).getTimeInMillis());}
    static synchronized void refresh(Context c){refresh(c,false);}
    private static synchronized void refresh(Context c,boolean force){long now=GuardClock.now(c).getTimeInMillis();if(!force&&now>=refreshed&&now-refreshed<4500)return;DailyQuotaData d=load(c,now);if(d.groups().length()==0||!d.enabled()&&!force)return;
        boolean reliable=AppUsageQuota.permission(c);List<AppUsageQuota.Span> spans=Collections.emptyList();if(d.enabled()&&reliable)try{spans=AppUsageQuota.spans(c,now,true);}catch(RuntimeException error){reliable=false;}
        d.advance(now,spans,reliable,RuleStore.all(c),DayBoundary.hour(c),TimeWindow.start(c),TimeWindow.end(c));if(!prefs(c).edit().putString("usage",d.usage.toString()).commit()){writeFailed=true;throw new IllegalStateException(T.s("每日限额用量未能保存"));}writeFailed=false;refreshed=now;
    }
    static synchronized void save(Context c,JSONObject candidate){Calendar now=GuardClock.now(c);DailyQuotaData.validate(candidate);if(new DailyQuotaData(candidate.toString(),"{}",now.getTimeInMillis()).packages().contains(c.getPackageName()))throw new IllegalArgumentException(T.s("不能限制守卫自身"));
        refresh(c,true);DailyQuotaData current=load(c,now.getTimeInMillis());
        // 编辑窗口外只放「只加不减」的改动：补额度随时可以，把自己放出来得等窗口
        if(!DailyQuotaData.editable(now)&&!DailyQuotaData.additive(current.config,candidate))throw new IllegalStateException(T.s("这个改动要减少额度，只在每天 22:00–23:00 开放"));
        if(!prefs(c).edit().putString("config",candidate.toString()).putString("usage",current.usage.toString()).commit())throw new IllegalStateException(T.s("每日限额未能保存"));refreshed=0;signature="";
    }
    static synchronized void beforeScheduleChange(Context c){if(active(c))refresh(c,true);}

    /**
     * 把旧「全天限时」（{@link AppUsageQuota}，一只应用一份、天生不能共享）并进每日限额格子：
     * 每只应用各成一格（minutes 照搬），今天已经用掉的时间写进账本，然后清空旧表。
     *
     * 只搬一次：旧表清空后本方法立刻返回 0。没授权使用情况时先不搬 —— 拿不到今天的用量，
     * 搬过去等于把已用时间清零，所以等下次授权后再搬。迁移前的原始数据留在
     * `afs_usage_quota/limits_before_migration` 与 `afs_daily_quota/*_before_migration`，便于回滚。
     */
    static synchronized int migrateLegacy(Context c){
        JSONObject limits;
        try{limits=AppUsageQuota.config(c);}catch(RuntimeException error){return 0;}
        if(limits.length()==0)return 0;
        if(!AppUsageQuota.permission(c))return 0;
        long now=GuardClock.now(c).getTimeInMillis();
        DailyQuotaData data=load(c,now);
        JSONObject config=MemoryData.copy(data.config),usage=data.usage;
        JSONArray groups=config.optJSONArray("groups");
        JSONObject targets=usage.optJSONObject("targets");
        if(groups==null||targets==null)return 0;
        Set<String> known=new LinkedHashSet<>(data.packages());
        int moved=0;
        for(Iterator<String> keys=limits.keys();keys.hasNext();){
            String pkg=keys.next();int minutes=limits.optInt(pkg);
            if(minutes<=0||known.contains(pkg))continue;
            try{
                long used=AppUsageQuota.used(c,pkg,RuleStore.all(c).get(pkg),now);
                groups.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("name",Guard.labelOf(c.getPackageManager(),pkg)).put("minutes",minutes).put("apps",new JSONArray().put(pkg)));
                targets.put(pkg,Math.max(0,used));
                known.add(pkg);moved++;
            }catch(RuntimeException|JSONException error){/* 这一只搬不动就留着，下次再试 */}
        }
        if(moved==0)return 0;
        DailyQuotaData.put(config,"enabled",true);
        DailyQuotaData.validate(config);
        SharedPreferences legacy=c.getSharedPreferences(AppUsageQuota.SP,0);
        if(!prefs(c).edit().putString("config_before_migration",data.config.toString()).putString("usage_before_migration",data.usage.toString()).commit())throw new IllegalStateException(T.s("额度迁移未能保存备份"));
        if(!prefs(c).edit().putString("config",config.toString()).putString("usage",usage.toString()).commit())throw new IllegalStateException(T.s("额度迁移未能保存"));
        legacy.edit().putString("limits_before_migration",limits.toString()).putString("limits","{}").commit();
        refreshed=0;signature="";
        return moved;
    }
    static boolean editable(Context c){return DailyQuotaData.editable(GuardClock.now(c));}
    static Set<String> packages(Context c){return snapshot(c).packages();}
    static boolean active(Context c){DailyQuotaData d=snapshot(c);return d.enabled()&&d.groups().length()>0;}
    static boolean hasAllowance(Context c,String pkg){DailyQuotaData d=snapshot(c);return d.enabled()&&d.group(pkg)!=null;}
    /** 先放开权限：没授权也能配额度，但判不出用量就不冻结；授权后立刻恢复计时与冻结。 */
    static boolean exceeded(Context c,String pkg){if(!AppUsageQuota.permission(c))return false;DailyQuotaData d=snapshot(c);Rule rule=RuleStore.all(c).get(pkg);return DailyQuotaData.inScope(rule,GuardClock.now(c).getTimeInMillis(),TimeWindow.start(c),TimeWindow.end(c))&&d.enabled()&&d.group(pkg)!=null&&(writeFailed||d.usage.optBoolean("unavailable")||d.exceeded(pkg));}
    static boolean enforcementChanged(Context c){try{refresh(c);}catch(IllegalStateException error){if(!writeFailed)throw error;}StringBuilder out=new StringBuilder();for(String pkg:packages(c))if(exceeded(c,pkg))out.append(pkg).append(';');synchronized(DailyQuotaStore.class){String next=out.toString();boolean changed=!next.equals(signature);signature=next;return changed;}}
    static String status(Context c,String pkg){DailyQuotaData d=snapshot(c);JSONObject g=d.group(pkg);Rule rule=RuleStore.all(c).get(pkg);if(g==null)return "";String base=(rule==null||FreezeSlotStore.unassigned(rule)?T.s("全天限额 · "):T.s("所绑时段限额 · "))+description(c,d,g);return TimeWindow.on(c)?base:T.s("时段外不计 · ")+base;}
    static boolean readable(Context c,DailyQuotaData d){return !writeFailed&&AppUsageQuota.permission(c)&&!d.usage.optBoolean("unavailable")&&d.usage.optString("error").isEmpty();}
    static String description(Context c,DailyQuotaData d,JSONObject g){if(!d.enabled())return T.s("每日限额已暂停");if(!AppUsageQuota.permission(c))return T.s("未授予使用情况权限，暂不计时");if(writeFailed||d.usage.optBoolean("unavailable")||!d.usage.optString("error").isEmpty())return T.s("用量暂不可读，保护性冻结");long used=d.used(g),limit=g.optInt("minutes")*60000L;return String.format(Locale.CHINA,T.s("已用 %.1f / %d 分钟 · 剩余 %.1f 分钟"),used/60000.0,g.optInt("minutes"),Math.max(0,limit-used)/60000.0);}
}
