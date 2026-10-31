package com.afs.guard;
import java.util.*;
/** Attach an extension to an actual scheduled release, never to the claim timestamp. */
final class PenaltyTiming {
    private static final Map<String,Long> cache=new LinkedHashMap<>();
    static synchronized long release(Rule rule,long at){String key=rule.groupKey()+":"+(at/60000);Long prior=cache.get(key);if(prior!=null)return prior;Calendar cursor=Calendar.getInstance();cursor.setTimeInMillis(at);
        if(rule.inWindow(cursor)){cursor.setTimeInMillis(rule.nextFlip(cursor));if(rule.inWindow(cursor))return remember(key,0);}
        cursor.setTimeInMillis(rule.nextFlip(cursor));return remember(key,rule.inWindow(cursor)?cursor.getTimeInMillis():0);
    }
    private static long remember(String key,long value){if(cache.size()>256)cache.clear();cache.put(key,value);return value;}
    static boolean active(long start,long end,long now){return start>0&&now>=start&&now<end;}
}
