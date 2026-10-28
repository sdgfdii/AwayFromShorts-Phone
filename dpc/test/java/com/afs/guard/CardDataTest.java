package com.afs.guard;

import org.json.*;
import java.util.*;

public final class CardDataTest {
    private static int checks;
    private static long at(int year,int month,int day,int hour,int minute){Calendar c=Calendar.getInstance();c.clear();c.set(year,month-1,day,hour,minute);return c.getTimeInMillis();}
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static void rejects(Runnable run,String message){try{run.run();}catch(IllegalArgumentException e){checks++;return;}throw new AssertionError(message);}
    private static void rewards(){
        long now=at(2026,10,27,10,0);CardData d=new CardData(now);
        rejects(()->d.saveLove(null,"练琴",0),"zero reward rejected");rejects(()->d.saveLove(null,"练琴",61),"hard maximum enforced");
        for(int draw=1;draw<=60;draw++){CardData sample=new CardData(now);String sampleId=sample.saveLove(null,"抽奖",30);sample.claim(sampleId,now,draw);check(sample.balanceMillis()==draw*60000L,"draw range persisted "+draw);check(!sample.claim(sampleId,now,61),"claimed day cannot redraw");}
        CardData random=new CardData(now);String randomId=random.saveLove(null,"随机",30);random.claim(randomId,now);check(random.balanceMillis()>=60000&&random.balanceMillis()<=3600000,"production random bounded");
        String a=d.saveLove(null,"练琴",30),b=d.saveLove(null,"绘画",60);
        check(d.claim(a,now,30),"first hobby earns reward");check(!d.claim(a,now+1),"repeated swipe never earns twice");
        check(!d.claim(b,now+2),"global one-card daily cap across different cards");check(d.balanceMillis()==30*60000L,"credit earned once");
        d.archive(a,true);check(d.balanceMillis()==30*60000L,"deleting card preserves credit");d.archive(a,false);
        check(!d.claim(b,now+3),"deleting and restoring cannot bypass daily cap");
        long tomorrow=at(2026,10,28,10,0);check(d.claim(b,tomorrow,60),"new day permits another card");
        check(d.balanceMillis()==90*60000L,"credits accumulate without expiring");
        CardData restored=new CardData(d.db.toString());check(restored.balanceMillis()==d.balanceMillis()&&restored.earnedToday(tomorrow),"restart preserves ledger and daily cap");
        check(!d.reconcile(tomorrow,false,tomorrow+3600000),"no spend without eligible scheduled freeze");check(d.balanceMillis()==90*60000L,"study or taboo exclusion preserves wallet");
        check(d.reconcile(tomorrow,true,tomorrow+20*60000L),"automatic scheduled remission starts");
        check(d.rewardUntil(tomorrow)==tomorrow+20*60000L,"reservation ends at next schedule boundary");
        check(d.balanceMillis()==70*60000L,"only reserved time deducted");
        check(!d.reconcile(tomorrow+5*60000L,true,tomorrow+20*60000L),"refresh cannot charge again");
        check(d.reconcile(tomorrow+10*60000L,false,tomorrow+20*60000L),"new learning restriction stops remission");
        check(d.rewardUntil(tomorrow+10*60000L)==0&&d.balanceMillis()==80*60000L,"unused reservation returned exactly once");
        d.reconcile(tomorrow+10*60000L,false,tomorrow+20*60000L);check(d.balanceMillis()==80*60000L,"refund is idempotent");
        d.reconcile(tomorrow+11*60000L,true,tomorrow+200*60000L);
        check(d.rewardUntil(tomorrow+11*60000L)==tomorrow+71*60000L,"one automatic reservation cannot exceed an hour");
        check(d.balanceMillis()==20*60000L,"remaining banked credit remains available later");
        CardData shorted=new CardData(now);String id=shorted.saveLove(null,"运动",60);shorted.claim(id,now,60);shorted.reconcile(now,true,now+3600000);
        shorted.reconcile(now+10*60000,true,now+20*60000);check(shorted.balanceMillis()==40*60000L,"shortened freeze boundary releases excess reservation");
        shorted.reconcile(now+15*60000,false,now+20*60000);check(shorted.balanceMillis()==45*60000L,"partial refund cannot create extra credit");
        check(!shorted.reconcile(now+20*60000,true,now+20*60000),"zero-length boundary never charges");
        check(shorted.claimed(id,now)&&!shorted.claimed(id,tomorrow),"card completion tracks its real day");
        JSONObject corrupt=MemoryData.copy(d.db);corrupt.optJSONArray("earned").put(corrupt.optJSONArray("earned").optJSONObject(0));
        rejects(()->new CardData(corrupt.toString()),"duplicate daily reward in storage rejected");
        Calendar c=Calendar.getInstance();c.setTimeInMillis(now);Rule rule=new Rule(Rule.ALL_DAYS,new int[][]{{0,0}},true,true);
        check(Guard.blockReason(rule,false,false,false,c,true)==0,"reward can override timed freeze");
        check(Guard.blockReason(rule,false,true,false,c,true)==2,"reward cannot override learning gate");
        check(Guard.blockReason(rule,false,true,true,c,true)==3,"reward cannot override taboo punishment");
        check(Guard.blockReason(rule,true,true,true,c,true)==0,"key-app exemption still follows existing semantics");
        check(Guard.blockReason(rule,false,false,false,c,false)==1,"expiry restores timed freeze");
    }
    private static void summaries(){
        long start=at(2026,10,27,10,0);CardData d=new CardData(start);CycleSummary s=d.summary(start);
        check(s.pending(start)==null,"weekly summary hidden before last day");
        long sun=at(2026,11,1,0,0);CycleSummary.Period p=s.pending(sun);
        check(p!=null&&"2026-10-26".equals(p.start)&&"2026-11-01".equals(p.through),"weekly period calendar boundaries");
        check(!p.holds(at(2026,11,1,19,59))&&p.holds(at(2026,11,1,20,0)),"deadline is local 20:00");
        check(p.holds(at(2026,11,1,23,59)),"23:59 never auto-completes");
        long mon=at(2026,11,2,9,0);CycleSummary.Period overdue=s.pending(mon);
        check(overdue!=null&&overdue.key.equals(p.key)&&overdue.holds(mon),"unfinished summary carries into next cycle and remains holding");
        s.configure("custom",3,"2026-11-02",mon);check(s.queued()!=null&&s.pending(mon).key.equals(p.key),"cycle edits queued without erasing debt");
        check(s.complete(p.key,"本周收获",mon),"overdue summary can be completed on Monday");
        check(!s.complete(p.key,"repeat",mon),"repeat confirmation has no effect");
        check(s.pending(mon)==null,"completion releases summary hold");
        check(s.mode().equals("custom")&&s.days()==3&&s.queued()==null,"queued custom configuration applies after debt completion");
        check(s.recorded(p.key).note.equals("本周收获"),"summary text retained");
        CardData loaded=new CardData(d.db.toString());check(loaded.summary(mon).pending(mon)==null,"restart preserves completed period and cursor");
        check(s.pending(at(2026,11,8,20,0))!=null,"next week requires another summary");
        CardData migrated=new CardData(start);migrated.summary(start).migrateWeek("2026-10-26");
        check(migrated.summary(start).pending(sun)==null,"previous weekly confirmation migrates without new mandatory task");
        CardData daily=new CardData(start);CycleSummary ds=daily.summary(start);ds.configure("daily",1,"2026-10-27",start);
        check(ds.pending(start)!=null&&!ds.pending(start).holds(start),"daily summary available with correct deadline");
        long threeDays=at(2026,10,30,8,0);int completed=0;while(ds.pending(threeDays)!=null&&ds.pending(threeDays).holds(threeDays)){ds.complete(ds.pending(threeDays).key,"补充",threeDays);completed++;}
        check(completed==3,"every missed daily period remains pending until completed");
        check(!ds.pending(threeDays).holds(threeDays),"current day's summary only holds after deadline");
        CardData monthly=new CardData(start);CycleSummary ms=monthly.summary(start);ms.configure("monthly",7,"2026-10-27",start);
        check(ms.pending(at(2026,10,30,23,59))==null,"monthly card not shown early");
        check("2026-10-31".equals(ms.pending(at(2026,10,31,0,0)).through),"monthly final day computed from calendar");
        CardData leap=new CardData(at(2024,2,10,10,0));CycleSummary ls=leap.summary(at(2024,2,10,10,0));ls.configure("monthly",7,"2024-02-10",at(2024,2,10,10,0));
        check(ls.pending(at(2024,2,28,20,0))==null&&ls.pending(at(2024,2,29,20,0))!=null,"leap-year month ends on correct day");
        CardData custom=new CardData(start);CycleSummary cs=custom.summary(start);cs.configure("custom",3,"2026-10-27",start);
        check(cs.pending(at(2026,10,28,20,0))==null&&cs.pending(at(2026,10,29,0,0))!=null,"custom period uses configured start and number of days");
        check(cs.pending(at(2026,10,30,9,0)).holds(at(2026,10,30,9,0)),"custom overdue summary remains enforced");
        rejects(()->new CardData(start).summary(start).configure("custom",0,"2026-10-27",start),"zero-day cycle rejected");
        rejects(()->new CardData(start).summary(start).configure("custom",366,"2026-10-27",start),"invalid cycle length rejected");
        rejects(()->new CardData(start).summary(start).configure("custom",3,"2026-02-30",start),"invalid calendar date rejected");
        rejects(()->s.complete("not-this-period","wrong",mon),"stale confirmation cannot complete another period");
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));long dstStart=at(2026,10,27,10,0);CardData dst=new CardData(dstStart);
        dst.summary(dstStart).configure("custom",7,"2026-10-27",dstStart);
        check("2026-11-02".equals(dst.summary(dstStart).pending(at(2026,11,2,20,0)).through),"custom calendar cycle survives daylight saving change");
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }
    public static void main(String[] args){TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));rewards();summaries();System.out.println("Card rewards and cycles: "+checks+" checks passed");}
}
