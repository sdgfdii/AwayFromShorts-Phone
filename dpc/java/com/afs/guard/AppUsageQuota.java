package com.afs.guard;
import android.content.Context;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import org.json.JSONObject;
import java.util.*;

/** Daily foreground allowance, independent of schedules; assigned apps use schedule intersections. */
public final class AppUsageQuota {
    static final String SP="afs_usage_quota";
    private static long cachedAt;private static List<Span> cached=new ArrayList<>();
    static final class Span {String pkg;long start,end;Span(String p,long s,long e){pkg=p;start=s;end=e;}}
    private AppUsageQuota(){}
    static JSONObject config(Context c){try{return new JSONObject(c.getSharedPreferences(SP,0).getString("limits","{}"));}catch(Exception e){throw new IllegalStateException(T.s("使用额度数据无法读取"),e);}}
    public static int minutes(Context c,String pkg){return config(c).optInt(pkg,0);}
    public static void save(Context c,String pkg,int minutes){if(minutes<0||minutes>1440)throw new IllegalArgumentException(T.s("使用额度须为1–1440分钟，0为取消"));JSONObject data=config(c);try{if(minutes==0)data.remove(pkg);else data.put(pkg,minutes);}catch(Exception e){throw new IllegalArgumentException(e);}if(!c.getSharedPreferences(SP,0).edit().putString("limits",data.toString()).commit())throw new IllegalStateException(T.s("额度未能保存"));}
    public static boolean permission(Context c){AppOpsManager manager=(AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE);return manager!=null&&manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;}
    static synchronized List<Span> spans(Context c,long now){return spans(c,now,false);}
    static synchronized List<Span> spans(Context c,long now,boolean fresh){if(!fresh&&now>=cachedAt&&now-cachedAt<5000&&DayBoundary.key(c,now).equals(DayBoundary.key(c,cachedAt)))return cached;
        long begin=DayBoundary.startMs(c,now);long queryFrom=begin-7L*86400000L;
        UsageStatsManager manager=(UsageStatsManager)c.getSystemService(Context.USAGE_STATS_SERVICE);UsageEvents events=manager==null?null:manager.queryEvents(queryFrom,now);if(events==null)throw new IllegalStateException(T.s("使用记录暂不可读"));
        List<Span> out=new ArrayList<>();String foreground=null;long start=begin;UsageEvents.Event event=new UsageEvents.Event();
        while(events.hasNextEvent()){events.getNextEvent(event);long stamp=event.getTimeStamp();int type=event.getEventType();String pkg=event.getPackageName();
            if(type==1){if(foreground!=null&&!foreground.equals(pkg)&&stamp>begin)out.add(new Span(foreground,Math.max(begin,start),stamp));if(!Objects.equals(pkg,foreground))start=stamp;foreground=pkg;}
            else if((type==2&&Objects.equals(pkg,foreground))||type==16||type==17||type==26){if(foreground!=null&&stamp>begin)out.add(new Span(foreground,Math.max(begin,start),stamp));foreground=null;}
        }if(foreground!=null)out.add(new Span(foreground,Math.max(begin,start),now));cached=out;cachedAt=now;return out;
    }
    static long overlap(long start,long end,Rule schedule){if(end<=start)return 0;if(schedule==null)return end-start;long total=0;Calendar minute=Calendar.getInstance();for(long t=start;t<end;){minute.setTimeInMillis(t);long next=Math.min(end,t+(60000-t%60000));if(!schedule.inWindow(minute))total+=next-t;t=next;}return total;}
    public static long used(Context c,String pkg,Rule rule,long now){Rule scope=FreezeSlotStore.unassigned(rule)?null:rule;long total=0;for(Span span:spans(c,now))if(pkg.equals(span.pkg))total+=overlap(span.start,span.end,scope);return total;}
    public static String status(Context c,String pkg,Rule rule,long now){if(!permission(c))return T.s("未授予使用情况权限，暂不计时");try{return String.format(java.util.Locale.CHINA,T.s("%s · 已用 %.1f / %d 分钟"),FreezeSlotStore.unassigned(rule)?T.s("全天额度"):T.s("所绑时段额度"),used(c,pkg,rule,now)/60000.0,minutes(c,pkg));}catch(RuntimeException e){return T.s("系统使用记录暂不可读，额度保护性冻结");}}
    /** 未授予使用情况权限时额度只保存不计时：判不出用量就不冻结，授权后自动恢复计时。 */
    static boolean exceeded(Context c,String pkg,Rule rule,long now){int limit=minutes(c,pkg);if(limit==0)return false;if(!FreezeSlotStore.unassigned(rule)&&rule.inWindow(calendar(now)))return false;if(!permission(c))return false;try{return used(c,pkg,rule,now)>=limit*60000L;}catch(RuntimeException e){return true;}}
    /**
     * 这个应用当前这一段连续前台是从什么时候开始的（毫秒）；不在前台返回 0。
     * 久用锁的判据：now - continuousStart >= 上限。
     */
    static synchronized long continuousStart(Context c,String pkg,long now){
        if(pkg==null)return 0;
        try{
            List<Span> spans=spans(c,now);
            if(spans.isEmpty())return 0;
            Span last=spans.get(spans.size()-1);
            if(!pkg.equals(last.pkg))return 0;
            if(last.end<now-8000L)return 0;      // 最后一段其实已经结束了
            return last.start;
        }catch(RuntimeException error){return 0;}
    }
    /** 当前前台应用：取使用记录里最后一段还开着的用量，用作「额度一用完就请出去」的判据。 */    static synchronized String foreground(Context c,long now){
        try{
            List<Span> spans=spans(c,now);
            if(spans.isEmpty())return null;
            Span last=spans.get(spans.size()-1);
            return last.end>=now-8000L?last.pkg:null;
        }catch(RuntimeException error){return null;}
    }
    private static Calendar calendar(long now){Calendar value=Calendar.getInstance();value.setTimeInMillis(now);return value;}
}
