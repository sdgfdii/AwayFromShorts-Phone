package com.afs.guard;
import java.util.*;
public final class PenaltyTimingTest {
    static int n;static long at(int day,int hour,int minute){Calendar c=Calendar.getInstance();c.clear();c.set(2026,9,day,hour,minute);return c.getTimeInMillis();}static void eq(long expected,long actual){n++;if(expected!=actual)throw new AssertionError(expected+" != "+actual);}
    public static void main(String[] args){TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));Rule a=new Rule(127,new int[][]{{0,1260},{1380,0}},true,true),b=new Rule(127,new int[][]{{0,360}},true,true);
        eq(at(29,21,0),PenaltyTiming.release(a,at(29,10,0)));eq(at(30,21,0),PenaltyTiming.release(a,at(29,22,0)));eq(at(30,6,0),PenaltyTiming.release(b,at(29,20,0)));eq(at(29,6,0),PenaltyTiming.release(b,at(29,5,0)));eq(at(30,6,0),PenaltyTiming.release(b,at(29,6,0)));
        eq(0,PenaltyTiming.release(FreezeSlotStore.withoutSchedule(),at(29,20,0)));eq(0,PenaltyTiming.release(new Rule(127,new int[][]{{0,0}},true,true),at(29,20,0)));
        Rule legacy=new Rule(127,new int[][]{{1260,1380}},true,false);eq(at(29,21,0),PenaltyTiming.release(legacy,at(29,20,0)));eq(at(30,21,0),PenaltyTiming.release(legacy,at(29,22,0)));
        eq(0,PenaltyTiming.active(at(29,21,0),at(29,22,0),at(29,20,59))?1:0);eq(1,PenaltyTiming.active(at(29,21,0),at(29,22,0),at(29,21,0))?1:0);eq(0,PenaltyTiming.active(at(29,21,0),at(29,22,0),at(29,22,0))?1:0);
        System.out.println("Penalty schedule: "+n+" checks passed");}
}
