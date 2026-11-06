package com.afs.guard;
import android.content.*;
import java.util.*;
import org.json.*;
/** Per-app penalty intervals (taboo cards + missed check-in); legacy remaining time is preserved and re-anchored once. */
final class PenaltyStore {
    private static final String KEY="schedule_penalties";
    /** 来源标记：同一格罚时可以来自禁忌卡、打卡没完成，或两者叠加。 */
    static final int SRC_TABOO=1;
    static final int SRC_CHECKIN=2;
    /** 禁忌卡认领一次的默认延长分钟数：和打卡罚时对齐（30），两边「一次」是同一个量。 */
    static final int DEFAULT_TABOO_MIN=30;
    private static JSONObject read(Context c){try{return new JSONObject(c.getSharedPreferences(StudyGate.TABOO_SP,0).getString(KEY,"{}"));}catch(JSONException e){throw new IllegalStateException(T.s("禁忌罚时无法读取"),e);}}
    /**
     * 只读快照：同一个原始串只解析一次。
     *
     * 冻结页每秒对几十个应用各查一次罚时，read() 每次都重新解析整块 JSON；
     * 记忆之后这条路径只剩一次比较。返回的对象禁止修改（写路径仍走 read()）。
     */
    private static String cachedRaw;
    private static JSONObject cachedEntries;
    private static JSONObject entries(Context c){
        String raw=c.getSharedPreferences(StudyGate.TABOO_SP,0).getString(KEY,"{}");
        synchronized(PenaltyStore.class){
            if(cachedEntries==null||!raw.equals(cachedRaw)){
                try{cachedEntries=new JSONObject(raw);}catch(JSONException e){throw new IllegalStateException(T.s("禁忌罚时无法读取"),e);}
                cachedRaw=raw;
            }
            return cachedEntries;
        }
    }
    private static void save(Context c,JSONObject entries,SharedPreferences.Editor edit){long latest=0;Iterator<String> keys=entries.keys();while(keys.hasNext())latest=Math.max(latest,entries.optJSONObject(keys.next()).optLong("until"));if(!edit.putString(KEY,entries.toString()).putLong("hold_until",latest).putBoolean("penalty_schedule_v1",true).commit())throw new IllegalStateException(T.s("禁忌罚时未能保存"));}
    private static synchronized void migrate(Context c,long now){SharedPreferences p=c.getSharedPreferences(StudyGate.TABOO_SP,0);if(p.getBoolean("penalty_schedule_v1",false))return;long old=p.getLong("hold_until",0);JSONObject entries=new JSONObject();if(old>now)append(c,entries,old-now,now,SRC_TABOO);save(c,entries,p.edit().putLong("legacy_hold_until",old));}
    private static void append(Context c,JSONObject entries,long amount,long now,int src){for(Map.Entry<String,Rule> rule:RuleStore.all(c).entrySet()){appendRule(entries,rule.getKey(),rule.getValue(),amount,now,src);}}
    /**
     * 给单个应用挂一笔延长。
     *
     * 禁忌卡只给联动(gate)应用挂；打卡罚时给「所有被冻结的应用」挂 —— 冻结时段结束后接着再冻 30 分钟。
     */
    private static void appendRule(JSONObject entries,String pkg,Rule r,long amount,long now,int src){
        boolean applies=src==SRC_CHECKIN?(r.gate||r.freeze):r.gate;
        if(!applies)return;
        JSONObject old=entries.optJSONObject(pkg);long end=old==null?0:old.optLong("until");long start=end>now?old.optLong("from"):PenaltyTiming.release(r,now);if(start==0)return;
        // 打卡罚时只延长当天还会结束的冻结；结束点跨到第二天的应用不安排。
        if(src==SRC_CHECKIN&&start>0&&!CheckinPenalty.withinToday(r,start,now))return;
        long base=end>now?end:start;int oldSrc=old==null?0:old.optInt("src",SRC_TABOO);JSONObject plan=new JSONObject();DailyQuotaData.put(plan,"from",start);DailyQuotaData.put(plan,"until",Math.addExact(base,amount));DailyQuotaData.put(plan,"src",oldSrc|src);DailyQuotaData.put(entries,pkg,plan);
    }
    /**
     * 把今天这笔打卡罚时补齐到「所有被冻结的应用」。
     *
     * 口径从「只罚联动应用」改成「所有冻结应用」之后，当天已经入账的那一笔只有联动应用有，
     * 这里补一次把其余冻结应用对齐；已经有了的（未过期且带打卡来源）跳过，幂等。
     */
    static synchronized void ensureCheckin(Context c,long amount,long now){
        migrate(c,now);JSONObject entries=read(c);boolean changed=false;
        for(Map.Entry<String,Rule> e:RuleStore.all(c).entrySet()){
            JSONObject plan=entries.optJSONObject(e.getKey());
            if(plan!=null&&plan.optLong("until")>now&&(plan.optInt("src",SRC_TABOO)&SRC_CHECKIN)!=0)continue;
            String before=plan==null?"":plan.toString();
            appendRule(entries,e.getKey(),e.getValue(),amount,now,SRC_CHECKIN);
            JSONObject after=entries.optJSONObject(e.getKey());
            if(after!=null&&!after.toString().equals(before))changed=true;
        }
        if(changed)save(c,entries,c.getSharedPreferences(StudyGate.TABOO_SP,0).edit());
    }
    static synchronized void add(Context c,long amount,long now){add(c,amount,now,SRC_TABOO);}
    static synchronized void add(Context c,long amount,long now,int src){if(amount<=0)return;migrate(c,now);JSONObject entries=read(c);append(c,entries,amount,now,src);save(c,entries,c.getSharedPreferences(StudyGate.TABOO_SP,0).edit());}
    static synchronized boolean holding(Context c,String pkg,long now){migrate(c,now);JSONObject plan=entries(c).optJSONObject(pkg);return plan!=null&&PenaltyTiming.active(plan.optLong("from"),plan.optLong("until"),now);}
    /**
     * 这一格罚时是不是「打卡没完成」来的。
     *
     * 打卡罚时对**所有被冻结的应用**生效：它必须在「钥匙应用/非联动放行」之前挡一道，
     * 否则非联动应用会被 keyApp 豁免直接放行，罚时条目就白记了。
     */
    static synchronized boolean checkinHolding(Context c,String pkg,long now){migrate(c,now);JSONObject plan=entries(c).optJSONObject(pkg);return plan!=null&&PenaltyTiming.active(plan.optLong("from"),plan.optLong("until"),now)&&(plan.optInt("src",SRC_TABOO)&SRC_CHECKIN)!=0;}
    /** 20:00 打卡罚时当前或下一段接续的汇总；count=0 表示今天没有待执行项目。 */
    static synchronized CheckinSummary checkinSummary(Context c,long now){
        migrate(c,now);JSONObject entries=entries(c);int count=0,active=0;long first=Long.MAX_VALUE,last=0;
        for(Map.Entry<String,Rule> e:RuleStore.all(c).entrySet()){
            JSONObject p=entries.optJSONObject(e.getKey());
            if(p==null||(p.optInt("src",SRC_TABOO)&SRC_CHECKIN)==0)continue;
            long from=p.optLong("from"),until=p.optLong("until");
            if(from<=0||until<=now)continue;
            count++;first=Math.min(first,from);last=Math.max(last,until);if(from<=now)active++;
        }
        return new CheckinSummary(count,active,first==Long.MAX_VALUE?0:first,last);
    }
    static final class CheckinSummary{
        final int count,active;final long first,last;
        CheckinSummary(int count,int active,long first,long last){this.count=count;this.active=active;this.first=first;this.last=last;}
    }
    static synchronized boolean any(Context c,long now){migrate(c,now);JSONObject entries=entries(c);boolean hit=false;for(Map.Entry<String,Rule> rule:RuleStore.all(c).entrySet()){if(!rule.getValue().gate)continue;JSONObject p=entries.optJSONObject(rule.getKey());if(p!=null&&PenaltyTiming.active(p.optLong("from"),p.optLong("until"),now)){hit=true;break;}}return hit;}
    static synchronized long until(Context c){migrate(c,GuardClock.now(c).getTimeInMillis());return c.getSharedPreferences(StudyGate.TABOO_SP,0).getLong("hold_until",0);}
    static synchronized long boundary(Context c,long now){migrate(c,now);long next=Long.MAX_VALUE;JSONObject entries=read(c);Iterator<String> keys=entries.keys();while(keys.hasNext()){JSONObject p=entries.optJSONObject(keys.next());long start=p.optLong("from"),end=p.optLong("until");if(start>now)next=Math.min(next,start);if(end>now)next=Math.min(next,end);}return next;}
    static String status(Context c,String pkg,long now){migrate(c,now);JSONObject p=read(c).optJSONObject(pkg);if(p==null||p.optLong("until")<=now)return "";java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA);int src=p.optInt("src",SRC_TABOO);String label=src==SRC_CHECKIN?T.s("打卡追加："):(src&SRC_CHECKIN)!=0?T.s("打卡 + 禁忌追加："):T.s("禁忌追加：");return label+f.format(new Date(p.optLong("from")))+T.s(" 至 ")+f.format(new Date(p.optLong("until")))+(now<p.optLong("from")?T.s("（待接续）"):T.s("（生效中）"));}
}
