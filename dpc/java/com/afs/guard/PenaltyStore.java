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
    private static void save(Context c,JSONObject entries,SharedPreferences.Editor edit){long latest=0;Iterator<String> keys=entries.keys();while(keys.hasNext())latest=Math.max(latest,entries.optJSONObject(keys.next()).optLong("until"));if(!edit.putString(KEY,entries.toString()).putLong("hold_until",latest).putBoolean("penalty_schedule_v1",true).commit())throw new IllegalStateException(T.s("禁忌罚时未能保存"));}
    private static synchronized void migrate(Context c,long now){SharedPreferences p=c.getSharedPreferences(StudyGate.TABOO_SP,0);if(p.getBoolean("penalty_schedule_v1",false))return;long old=p.getLong("hold_until",0);JSONObject entries=new JSONObject();if(old>now)append(c,entries,old-now,now,SRC_TABOO);save(c,entries,p.edit().putLong("legacy_hold_until",old));}
    private static void append(Context c,JSONObject entries,long amount,long now,int src){for(Map.Entry<String,Rule> rule:RuleStore.all(c).entrySet()){Rule r=rule.getValue();if(!r.gate)continue;JSONObject old=entries.optJSONObject(rule.getKey());long end=old==null?0:old.optLong("until");long start=end>now?old.optLong("from"):PenaltyTiming.release(r,now);if(start==0)continue;
        // 打卡罚时只延长「今天这一段」：跨午夜、或下次放行已经不在今天的，都不加。
        // ★ 已有的罚时格也要过这一关：禁忌卡可能在明天 06:00 挂了一格，
        //   打卡这笔顺手叠上去，就等于把今天的账记到了明天。
        if(src==SRC_CHECKIN&&!CheckinPenalty.withinToday(r,start,now))continue;
        long base=end>now?end:start;int oldSrc=old==null?0:old.optInt("src",SRC_TABOO);JSONObject plan=new JSONObject();DailyQuotaData.put(plan,"from",start);DailyQuotaData.put(plan,"until",Math.addExact(base,amount));DailyQuotaData.put(plan,"src",oldSrc|src);DailyQuotaData.put(entries,rule.getKey(),plan);}}
    static synchronized void add(Context c,long amount,long now){add(c,amount,now,SRC_TABOO);}
    static synchronized void add(Context c,long amount,long now,int src){if(amount<=0)return;migrate(c,now);JSONObject entries=read(c);append(c,entries,amount,now,src);save(c,entries,c.getSharedPreferences(StudyGate.TABOO_SP,0).edit());}
    static synchronized boolean holding(Context c,String pkg,long now){migrate(c,now);JSONObject plan=read(c).optJSONObject(pkg);return plan!=null&&PenaltyTiming.active(plan.optLong("from"),plan.optLong("until"),now);}
    static synchronized boolean any(Context c,long now){migrate(c,now);JSONObject entries=read(c);for(String pkg:RuleStore.packages(c))if(RuleStore.get(c,pkg).gate){JSONObject p=entries.optJSONObject(pkg);if(p!=null&&PenaltyTiming.active(p.optLong("from"),p.optLong("until"),now))return true;}return false;}
    static synchronized long until(Context c){migrate(c,GuardClock.now(c).getTimeInMillis());return c.getSharedPreferences(StudyGate.TABOO_SP,0).getLong("hold_until",0);}
    static synchronized long boundary(Context c,long now){migrate(c,now);long next=Long.MAX_VALUE;JSONObject entries=read(c);Iterator<String> keys=entries.keys();while(keys.hasNext()){JSONObject p=entries.optJSONObject(keys.next());long start=p.optLong("from"),end=p.optLong("until");if(start>now)next=Math.min(next,start);if(end>now)next=Math.min(next,end);}return next;}
    static String status(Context c,String pkg,long now){migrate(c,now);JSONObject p=read(c).optJSONObject(pkg);if(p==null||p.optLong("until")<=now)return "";java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA);int src=p.optInt("src",SRC_TABOO);String label=src==SRC_CHECKIN?T.s("打卡追加："):(src&SRC_CHECKIN)!=0?T.s("打卡 + 禁忌追加："):T.s("禁忌追加：");return label+f.format(new Date(p.optLong("from")))+T.s(" 至 ")+f.format(new Date(p.optLong("until")))+(now<p.optLong("from")?T.s("（待接续）"):T.s("（生效中）"));}
}
