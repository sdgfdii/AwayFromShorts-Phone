package com.afs.guard;
import java.util.Calendar;
public final class AppUsageQuotaTest {
    private static int checks;
    private static long time(int day,int hour,int minute,int second){Calendar c=Calendar.getInstance();c.clear();c.set(2026,9,day,hour,minute,second);return c.getTimeInMillis();}
    private static void eq(long expected,long actual,String label){checks++;if(expected!=actual)throw new AssertionError(label+": "+actual+" != "+expected);}
    public static void main(String[] args){
        Rule day=new Rule(Rule.ALL_DAYS,new int[][]{{600,660}},true,true);
        eq(60000,AppUsageQuota.overlap(time(28,9,59,30),time(28,10,0,30),null),"global exact seconds");
        eq(30000,AppUsageQuota.overlap(time(28,9,59,30),time(28,10,0,30),day),"only in-slot seconds");
        eq(0,AppUsageQuota.overlap(time(28,9,0,0),time(28,9,59,59),day),"outside slot ignored");
        eq(3600000,AppUsageQuota.overlap(time(28,9,0,0),time(28,12,0,0),day),"clipped interval");
        eq(1000,AppUsageQuota.overlap(time(28,10,59,59),time(28,11,0,1),day),"end exclusive");
        Rule night=new Rule(Rule.ALL_DAYS,new int[][]{{1380,60}},true,true);
        eq(7200000,AppUsageQuota.overlap(time(28,22,0,0),time(29,2,0,0),night),"overnight window");
        eq(0,AppUsageQuota.overlap(time(28,10,0,0),time(28,10,0,0),day),"empty usage");
        eq(0,AppUsageQuota.overlap(time(28,10,0,1),time(28,10,0,0),day),"reversed timestamps");
        Rule wednesday=new Rule(4,new int[][]{{1380,60}},true,true);
        eq(3600000,AppUsageQuota.overlap(time(29,0,0,0),time(29,2,0,0),wednesday),"carry weekday across midnight");
        eq(0,AppUsageQuota.overlap(time(30,0,0,0),time(30,2,0,0),wednesday),"wrong weekday");
        System.out.println("App usage quota: "+checks+" checks passed");
    }
}
