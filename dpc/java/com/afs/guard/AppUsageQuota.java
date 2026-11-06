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
    /**
     * 每块查询覆盖的时长。系统的一次事件查询有传输上限，跨度太大就截断并丢最新事件；
     * 按小时切块后每块的量都很小，绕开这个上限。
     */
    private static final long QUERY_CHUNK_MS=3600000L;
    private static long cachedAt;private static List<Span> cached=new ArrayList<>();
    /** 与 spans 同一次事件流解析出的「前台服务在跑」区间（挂后台播放/画中画的判据）。 */
    private static List<Span> cachedFgs=new ArrayList<>();
    static final class Span {String pkg;long start,end;boolean active;Span(String p,long s,long e){pkg=p;start=s;end=e;}}
    private AppUsageQuota(){}
    static JSONObject config(Context c){try{return new JSONObject(c.getSharedPreferences(SP,0).getString("limits","{}"));}catch(Exception e){throw new IllegalStateException(T.s("使用额度数据无法读取"),e);}}
    /** 只读额度表：原始串没变就不重解析（冻结页每秒几十次 minutes()）。 */
    private static String cachedLimitsRaw;
    private static Map<String,Integer> cachedLimits;
    static Map<String,Integer> limits(Context c){
        String raw=c.getSharedPreferences(SP,0).getString("limits","{}");
        synchronized(AppUsageQuota.class){
            if(cachedLimits==null||!raw.equals(cachedLimitsRaw)){
                Map<String,Integer> parsed=new HashMap<>();
                try{JSONObject o=new JSONObject(raw);for(Iterator<String> keys=o.keys();keys.hasNext();){String pkg=keys.next();parsed.put(pkg,o.optInt(pkg,0));}}catch(Exception ignored){}
                cachedLimits=parsed;cachedLimitsRaw=raw;
            }
            return cachedLimits;
        }
    }
    public static int minutes(Context c,String pkg){Integer minutes=limits(c).get(pkg);return minutes==null?0:minutes;}
    public static void save(Context c,String pkg,int minutes){if(minutes<0||minutes>1440)throw new IllegalArgumentException(T.s("使用额度须为1–1440分钟，0为取消"));JSONObject data=config(c);try{if(minutes==0)data.remove(pkg);else data.put(pkg,minutes);}catch(Exception e){throw new IllegalArgumentException(e);}if(!c.getSharedPreferences(SP,0).edit().putString("limits",data.toString()).commit())throw new IllegalStateException(T.s("额度未能保存"));}
    /** 授权状态一秒内复用：同一屏几十个应用各查一次 AppOps 是纯耗时。 */
    private static long permissionAt;private static boolean permissionValue;
    public static boolean permission(Context c){
        long now=System.currentTimeMillis();
        if(permissionAt!=0&&now>=permissionAt&&now-permissionAt<1000)return permissionValue;
        AppOpsManager manager=(AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE);
        boolean allowed=manager!=null&&manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;
        permissionAt=now;permissionValue=allowed;return allowed;
    }
    static synchronized List<Span> spans(Context c,long now){return spans(c,now,false);}
    static synchronized List<Span> spans(Context c,long now,boolean fresh){if(!fresh&&now>=cachedAt&&now-cachedAt<5000&&DayBoundary.key(c,now).equals(DayBoundary.key(c,cachedAt)))return cached;
        // 只查今天这段（再往前留 6 小时兜住跨 08:00 还在前台的应用）。
        // ★ 别查 7 天：真机上事件量一大，系统返回的结果会被截断且丢掉**最新**的事件，
        //   表现就是「明明在刷，账本一动不动」（2026-11-04 实测：7 天窗口下最新 RESUMED 拿不到）。
        long begin=DayBoundary.startMs(c,now);long queryFrom=begin-6L*3600000L;
        UsageStatsManager manager=(UsageStatsManager)c.getSystemService(Context.USAGE_STATS_SERVICE);
        if(manager==null)throw new IllegalStateException(T.s("使用记录暂不可读"));
        List<Span> out=new ArrayList<>();String foreground=null;long start=begin;UsageEvents.Event event=new UsageEvents.Event();
        // 前台服务可以多个应用同时在跑（听歌 + 画中画），必须按包名各记各的起点。
        Map<String,Long> fgsOpen=new LinkedHashMap<>();List<Span> fgsOut=new ArrayList<>();String own=c.getPackageName();
        // ★ 分块查询：一次 queryEvents 覆盖太久时，返回的事件会超过单次调用的传输上限被截断，
        //   而且丢的是**最新**的那部分。额度日从 08:00 起算，所以每天 0 点到 8 点这段窗口有 22 小时，
        //   重度使用的日子照样会被截断 —— 表现就是「正在用，额度不涨，限额锁不住」。
        //   按小时切块查询，每块都很小，累计起来才是完整的一天。
        for(long cursor=queryFrom;cursor<now;){
            long piece=Math.min(cursor+QUERY_CHUNK_MS,now);
            UsageEvents events=manager.queryEvents(cursor,piece);
            if(events==null)throw new IllegalStateException(T.s("使用记录暂不可读"));
            while(events.hasNextEvent()){events.getNextEvent(event);long stamp=event.getTimeStamp();int type=event.getEventType();String pkg=event.getPackageName();
                if(type==1){if(foreground!=null&&!foreground.equals(pkg)&&stamp>begin)out.add(new Span(foreground,Math.max(begin,start),stamp));if(!Objects.equals(pkg,foreground))start=stamp;foreground=pkg;}
                else if((type==2&&Objects.equals(pkg,foreground))||type==16||type==17||type==26){if(foreground!=null&&stamp>begin)out.add(new Span(foreground,Math.max(begin,start),stamp));foreground=null;}
                // FOREGROUND_SERVICE_START(19)/STOP(20)：挂着后台还在跑的判据。自己的守护服务不算。
                else if(type==19&&!Objects.equals(pkg,own)){if(!fgsOpen.containsKey(pkg))fgsOpen.put(pkg,stamp);}
                else if(type==20&&fgsOpen.containsKey(pkg)){if(stamp>begin)fgsOut.add(new Span(pkg,Math.max(begin,fgsOpen.remove(pkg)),stamp));else fgsOpen.remove(pkg);}
                else if(type==26){for(Map.Entry<String,Long> open:fgsOpen.entrySet())if(stamp>begin&&open.getValue()<stamp)fgsOut.add(new Span(open.getKey(),Math.max(begin,open.getValue()),stamp));fgsOpen.clear();}
            }
            cursor=piece;
        }
        for(Map.Entry<String,Long> open:fgsOpen.entrySet()){Span span=new Span(open.getKey(),Math.max(begin,open.getValue()),now);span.active=true;fgsOut.add(span);}
        cached=out;cachedFgs=fgsOut;cachedAt=now;return out;
    }
    /** 前台服务区间（跟 spans 同一份缓存，先调 spans 保证新鲜）。 */
    static synchronized List<Span> fgs(Context c,long now){spans(c,now,false);return cachedFgs;}
    /**
     * 把勾了「挂后台计时」应用的前台服务区间并进用量区间，重叠部分合并成一段（不双倍计）。
     * 纯数据，离线可测；未勾选的应用完全不受影响。
     */
    static List<Span> effective(List<Span> base,List<Span> fgs,Set<String> flagged){
        if(flagged.isEmpty()||fgs==null||fgs.isEmpty())return base;
        List<Span> out=new ArrayList<>(base);for(Span s:fgs)if(flagged.contains(s.pkg))out.add(s);
        return union(out);
    }
    /** 同一应用的重叠/相邻区间合并为一段；不同应用互不合并。纯数据，离线可测。 */
    static List<Span> union(List<Span> spans){
        Map<String,List<Span>> by=new LinkedHashMap<>();for(Span s:spans){List<Span> list=by.get(s.pkg);if(list==null){list=new ArrayList<>();by.put(s.pkg,list);}list.add(s);}
        List<Span> out=new ArrayList<>();
        for(Map.Entry<String,List<Span>> entry:by.entrySet()){
            List<Span> list=entry.getValue();list.sort(Comparator.comparingLong(s->s.start));
            Span current=null;
            for(Span s:list){
                if(current!=null&&s.start<=current.end){if(s.end>current.end)current.end=s.end;}
                else{current=new Span(entry.getKey(),s.start,s.end);current.active=s.active;out.add(current);}
            }
        }
        return out;
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
    private static final java.util.concurrent.ExecutorService FOREGROUND=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(r,"afs-foreground-sample"));
    private static final java.util.concurrent.atomic.AtomicBoolean foregroundBusy=new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile ForegroundSample foregroundSample=new ForegroundSample(null,0,0);
    private static final class ForegroundSample {final String pkg;final long since,at;ForegroundSample(String p,long s,long a){pkg=p;since=s;at=a;}}
    /** UI consumers never perform UsageStats IPC or wait for the usage-cache monitor. */
    static String foregroundCached(Context c,long now){
        ForegroundSample sample=foregroundSample;
        if((now<sample.at||now-sample.at>=4000)&&foregroundBusy.compareAndSet(false,true)){
            Context app=c.getApplicationContext();
            FOREGROUND.execute(()->{try{long at=GuardClock.now(app).getTimeInMillis();synchronized(AppUsageQuota.class){List<Span> all=spans(app,at,false);Span last=all.isEmpty()?null:all.get(all.size()-1);foregroundSample=last!=null&&last.active?new ForegroundSample(last.pkg,last.start,cachedAt):new ForegroundSample(null,0,cachedAt);}}catch(RuntimeException error){foregroundSample=new ForegroundSample(null,0,System.currentTimeMillis());}finally{foregroundBusy.set(false);}});
        }
        return now>=sample.at&&now-sample.at<=6500?sample.pkg:null;
    }
    static long continuousStartCached(String pkg){ForegroundSample sample=foregroundSample;return Objects.equals(pkg,sample.pkg)?sample.since:0;}
    private static Calendar calendar(long now){Calendar value=Calendar.getInstance();value.setTimeInMillis(now);return value;}
}
