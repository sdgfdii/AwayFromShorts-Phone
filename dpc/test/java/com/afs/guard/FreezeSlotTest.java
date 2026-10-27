package com.afs.guard;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Schedule-first grouping must preserve existing app behavior and key-app exemptions. */
public final class FreezeSlotTest {
    private static int checks;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    public static void main(String[] args) {
        Rule night = new Rule(127, new int[][]{{0,360}}, true, true);
        Rule keyNight = night.withGate(false);
        check(FreezeSlotStore.scheduleKey(night).equals(FreezeSlotStore.scheduleKey(keyNight)), "critical apps share the same schedule container");
        Rule legacy = new Rule(127, 360, 0, false);
        check(FreezeSlotStore.scheduleKey(legacy).equals(FreezeSlotStore.scheduleKey(night)), "legacy allow windows migrate without behavior changes");
        Rule wrap = new Rule(127, new int[][]{{1380,1260}}, true, true);
        Rule split = new Rule(127, new int[][]{{0,1260},{1380,0}}, true, true);
        check(FreezeSlotStore.scheduleKey(wrap).equals(FreezeSlotStore.scheduleKey(split)), "equivalent midnight segments do not create duplicate slots");
        check(!FreezeSlotStore.scheduleKey(night).equals(FreezeSlotStore.scheduleKey(night.withDays(31))), "weekdays remain part of the schedule");
        Map<String, Rule> rules = new LinkedHashMap<>();
        rules.put("normal", night); rules.put("critical", keyNight); rules.put("pending", FreezeSlotStore.withoutSchedule());
        FreezeSlotStore.Slot slot = new FreezeSlotStore.Slot("night", night);
        check(FreezeSlotStore.members(slot, rules).equals(Arrays.asList("normal","critical")), "membership depends on schedule rather than exemption");
        check(FreezeSlotStore.unassigned(FreezeSlotStore.withoutSchedule()), "new apps remain in the application tray");
        check(!FreezeSlotStore.unassigned(new Rule(127,new int[][]{{0,0}},true,true)), "all-day freeze is not unassigned");
        check(!rules.get("critical").gate && rules.get("normal").gate, "grouping does not mutate app exemptions");
        java.util.Calendar monday = java.util.Calendar.getInstance(); monday.clear(); monday.set(2026, 9, 19);
        check(Arrays.deepEquals(FreezeSlotStore.frozenSegments(night, monday), new int[][]{{0,360}}), "night interval maps onto the 24-hour rail");
        Rule crossing = new Rule(127, new int[][]{{1320,120}}, true, true);
        check(Arrays.deepEquals(FreezeSlotStore.frozenSegments(crossing, monday), new int[][]{{0,120},{1320,1440}}), "overnight interval appears at both ends of the rail");
        java.util.Calendar tuesday = (java.util.Calendar)monday.clone(); tuesday.add(java.util.Calendar.DATE,1);
        check(Arrays.deepEquals(FreezeSlotStore.frozenSegments(new Rule(1,new int[][]{{1320,120}},true,true),tuesday), new int[][]{{0,120}}), "weekly overnight interval carries into the following day");
        check(FreezeSlotStore.frozenSegments(FreezeSlotStore.withoutSchedule(),monday).length == 0, "unassigned schedule has no frozen portion");
        check(Arrays.deepEquals(FreezeSlotStore.frozenSegments(new Rule(127,new int[][]{{0,0}},true,true),monday),new int[][]{{0,1440}}), "all-day freeze fills the complete rail");
        monday.set(java.util.Calendar.HOUR_OF_DAY,20);
        check(Guard.blockReason(night,false,true,false,monday)==2,"camera outside its own night window reports study hold");
        check(Guard.blockReason(night,true,true,false,monday)==0,"key app remains exempt outside its own window");
        check(Guard.blockReason(night,false,false,false,monday)==0,"ordinary app is available when no extension is active");
        check(Guard.blockReason(night,false,true,true,monday)==3,"taboo penalty is identified independently");
        monday.set(java.util.Calendar.HOUR_OF_DAY,2);
        check(Guard.blockReason(night,false,true,false,monday)==1,"own schedule has priority in displayed reason");
        // Differential check against enforcement for every minute of each weekday.
        java.util.Random random=new java.util.Random(1493);
        for(int sample=0;sample<48;sample++) {
            int[][] windows=new int[sample%5][2];
            for(int[] w:windows){w[0]=random.nextInt(1440);w[1]=random.nextInt(1440);}
            if(windows.length>0&&sample%4==0)windows[0][1]=windows[0][0];
            for(boolean freeze:new boolean[]{false,true}) {
                Rule candidate=new Rule(random.nextInt(128),windows,true,freeze);
                for(int weekday=0;weekday<7;weekday++) {
                    java.util.Calendar date=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
                    date.clear();date.set(2026,9,19+weekday);
                    boolean[] rail=new boolean[1440];
                    for(int[] span:FreezeSlotStore.frozenSegments(candidate,date))Arrays.fill(rail,span[0],span[1],true);
                    for(int minute=0;minute<1440;minute++) {
                        date.set(java.util.Calendar.HOUR_OF_DAY,minute/60);date.set(java.util.Calendar.MINUTE,minute%60);
                        if(rail[minute]==candidate.inWindow(date))throw new AssertionError("rail differs at "+sample+":"+weekday+":"+minute);
                    }
                    checks++;
                }
            }
        }
        System.out.println("Freeze slots: " + checks + " checks passed");
    }
}
