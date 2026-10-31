package com.afs.guard;
import org.json.*;
import java.util.*;
public final class DailyQuotaTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static long at(int day,int h,int m,int sec){Calendar c=Calendar.getInstance();c.clear();c.set(2026,9,day,h,m,sec);return c.getTimeInMillis();}
    private static Calendar calendar(long now){Calendar c=Calendar.getInstance();c.setTimeInMillis(now);return c;}
    private static JSONObject group(String id,int minutes,String... apps)throws Exception{return new JSONObject().put("id",id).put("name",id).put("minutes",minutes).put("apps",new JSONArray(Arrays.asList(apps)));}
    private static String config(boolean on,JSONObject... groups)throws Exception{return new JSONObject().put("enabled",on).put("groups",new JSONArray(Arrays.asList(groups))).toString();}
    private static List<AppUsageQuota.Span> spans(AppUsageQuota.Span... spans){return Arrays.asList(spans);}
    private static AppUsageQuota.Span span(String app,long start,long end){return new AppUsageQuota.Span(app,start,end);}
    private static void reject(Runnable action,String label){try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){checks++;return;}throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));String a="com.example.a",b="com.example.b";long noon=at(29,12,0,0);
        JSONObject ga=group("a",1,a),gb=group("b",2,b),shared=group("shared",2,a,b);DailyQuotaData d=new DailyQuotaData(config(true,ga,gb),"{}",noon);
        List<AppUsageQuota.Span> history=spans(span(a,noon-60000,noon+30000),span(b,noon+30000,noon+60000));d.advance(noon+60000,history,true);
        check(d.used(ga)==30000&&d.used(gb)==30000,"only use since enabling counts, independently");
        d.advance(noon+60000,history,true);check(d.used(ga)==30000,"same sample cannot double count");
        DailyQuotaData merged=new DailyQuotaData(config(true,shared),d.usage.toString(),noon+60000);check(merged.used(shared)==60000,"merge sums retained per-app usage");merged.advance(noon+90000,spans(span(a,noon+60000,noon+90000)),true);check(merged.used(shared)==90000,"shared budget combines both apps");
        DailyQuotaData split=new DailyQuotaData(config(true,ga,gb),merged.usage.toString(),noon+90000);check(split.used(ga)==60000&&split.used(gb)==30000,"split restores each exact app usage");check(split.exceeded(a)&&!split.exceeded(b),"one exhausted independent app does not block another");
        DailyQuotaData removed=new DailyQuotaData(config(true,gb),split.usage.toString(),noon+90000);removed.advance(noon+120000,spans(span(a,noon+90000,noon+120000)),true);DailyQuotaData added=new DailyQuotaData(config(true,ga,gb),removed.usage.toString(),noon+120000);check(added.used(ga)==60000&&added.exceeded(a),"remove and re-add preserves used time without charging removed intervals");
        // 额度行合并/删除：纯数据层，界面上“长按并到另一个额度”靠这两个函数
        JSONObject mergeConfig=new JSONObject(config(true,group("a",30,a),group("b",20,b)));
        check(DailyQuotaData.mergeInto(mergeConfig,"a","b",20),"mergeInto moves members into the target group");
        check(mergeConfig.optJSONArray("groups").length()==1,"merged config keeps one group");
        JSONObject kept=mergeConfig.optJSONArray("groups").optJSONObject(0);
        check(kept.optString("id").equals("a")&&kept.optInt("minutes")==20&&kept.optJSONArray("apps").length()==2,"merge keeps target id, smaller minutes and both apps");
        check(!DailyQuotaData.mergeInto(mergeConfig,"a","missing",20),"mergeInto refuses a group that no longer exists");
        check(DailyQuotaData.without(new JSONObject(config(true,group("a",30,a),group("b",20,b))),"a").length()==1,"without drops exactly one group");
        // 拖拽迁移：把一只应用从一格挪到另一格，被搬空的格子要一起消失
        JSONObject moveConfig=new JSONObject(config(true,group("a",30,a),group("b",20,b)));
        check(DailyQuotaData.moveInto(moveConfig,"b",a)==1,"moveInto moves an app into the target group");
        check(moveConfig.optJSONArray("groups").length()==1,"emptied source group is removed");
        JSONObject moved=moveConfig.optJSONArray("groups").optJSONObject(0);
        check(moved.optString("id").equals("b")&&moved.optJSONArray("apps").length()==2,"target keeps both apps");
        check(DailyQuotaData.moveInto(moveConfig,"b",a)==0,"moving an app into the same group is a no-op");
        check(DailyQuotaData.moveInto(moveConfig,"missing",a)==-1,"moving into a missing group is refused");
        DailyQuotaData paused=new DailyQuotaData(config(false,ga,gb),added.usage.toString(),noon+120000);paused.advance(noon+180000,spans(span(a,noon+120000,noon+180000)),true);check(!paused.exceeded(a)&&paused.used(ga)==60000,"paused quota neither blocks nor counts");DailyQuotaData resumed=new DailyQuotaData(config(true,ga,gb),paused.usage.toString(),noon+180000);resumed.advance(noon+210000,spans(span(b,noon+180000,noon+210000)),true);check(resumed.used(ga)==60000&&resumed.used(gb)==60000,"resume retains ledger and excludes disabled gap");
        DailyQuotaData restart=new DailyQuotaData(config(true,ga,gb),resumed.usage.toString(),noon+210000);restart.advance(noon+240000,spans(span(b,noon+210000,noon+240000)),true);check(restart.used(gb)==90000,"restart continues from persisted watermark");
        DailyQuotaData unreadable=new DailyQuotaData(config(true,ga,gb),"broken",noon);check(unreadable.exceeded(a)&&unreadable.exceeded(b),"corrupt ledger cannot grant another allowance");unreadable.advance(at(30,0,0,1),Collections.emptyList(),true);check(!unreadable.exceeded(a),"corrupt day's protective freeze clears on next day");
        DailyQuotaData permission=new DailyQuotaData(config(true,ga),"{}",noon);permission.advance(noon+1000,Collections.emptyList(),false);check(permission.exceeded(a),"unreadable events protect enabled group");permission.advance(noon+2000,Collections.emptyList(),true);check(!permission.exceeded(a),"recovered permission clears transient protection");
        long before=at(29,23,59,59),after=at(30,0,0,1);DailyQuotaData midnight=new DailyQuotaData(config(true,ga),"{}",before);midnight.advance(after,spans(span(a,before,after)),true);check(midnight.used(ga)==1000,"midnight counts only new day's part");
        Rule afternoon=new Rule(Rule.ALL_DAYS,new int[][]{{12*60,13*60}},true,true);
        Map<String,Rule> scopes=new HashMap<>();scopes.put(a,afternoon);
        DailyQuotaData scoped=new DailyQuotaData(config(true,shared),"{}",at(29,11,59,0));
        scoped.advance(at(29,12,1,0),spans(span(a,at(29,11,59,0),at(29,12,0,30)),span(b,at(29,12,0,30),at(29,12,1,0))),true,scopes);
        check(scoped.used(shared)==60000,"shared: assigned app counts only slot seconds, unassigned app counts all day");
        check(!DailyQuotaData.inScope(afternoon,at(29,11,59,59))&&DailyQuotaData.inScope(afternoon,noon),"slot start inclusive; outside is allowed");
        check(!DailyQuotaData.inScope(afternoon,at(29,13,0,0)),"slot end exclusive even if budget exhausted");
        check(DailyQuotaData.inScope(null,noon)&&DailyQuotaData.inScope(FreezeSlotStore.withoutSchedule(),noon),"unassigned and quota-only apps use all-day scope");
        long retained=scoped.used(shared);scopes.put(a,new Rule(Rule.ALL_DAYS,new int[][]{{13*60,14*60}},true,true));
        scoped.advance(at(29,12,2,0),spans(span(a,at(29,12,1,0),at(29,12,2,0))),true,scopes);
        check(scoped.used(shared)==retained,"moving slots retains usage and excludes time outside the new scope");
        scopes.remove(a);scoped.advance(at(29,12,2,30),spans(span(a,at(29,12,2,0),at(29,12,2,30))),true,scopes);
        check(scoped.used(shared)==retained+30000,"moving back to all-day resumes counting without resetting usage");
        Rule night=new Rule(Rule.ALL_DAYS,new int[][]{{23*60,60}},true,true);scopes.put(a,night);
        DailyQuotaData overnight=new DailyQuotaData(config(true,ga),"{}",at(29,23,59,59));overnight.advance(at(30,0,0,1),spans(span(a,at(29,23,59,59),at(30,0,0,1))),true,scopes);
        check(overnight.used(ga)==1000,"overnight assigned scope counts only new-day part at midnight");
        check(DailyQuotaData.inScope(night,at(30,0,59,59))&&!DailyQuotaData.inScope(night,at(30,1,0,0)),"overnight allowance ends at its exact release boundary");
        for(long stamp:new long[]{at(29,12,0,0),at(29,21,59,59),at(29,23,0,0),at(29,3,0,0)})check(!DailyQuotaData.editable(calendar(stamp)),"quota config locked outside 22-23 "+stamp);
        for(long stamp:new long[]{at(29,22,0,0),at(29,22,59,59)})check(DailyQuotaData.editable(calendar(stamp)),"quota config editable inside 22-23 "+stamp);
        // 「只进不出」闸门：窗口外随时能加，减要等 22:00–23:00
        JSONObject base=new JSONObject(config(true,group("a",30,a),group("b",20,b)));
        check(DailyQuotaData.additive(base,new JSONObject(config(true,group("a",30,a),group("b",20,b),group("c",15,"com.example.c")))),"a brand new group counts as additive");
        check(DailyQuotaData.additive(base,new JSONObject(config(true,group("a",45,a),group("b",20,b)))),"raising minutes counts as additive");
        check(DailyQuotaData.additive(base,new JSONObject(config(true,group("a",30,a,"com.example.c"),group("b",20,b)))),"adding a member to a group counts as additive");
        JSONObject renamed=group("a",30,a);renamed.put("name","renamed");
        check(DailyQuotaData.additive(base,new JSONObject(config(true,renamed,group("b",20,b)))),"renaming a group stays additive");
        check(!DailyQuotaData.additive(base,new JSONObject(config(true,group("a",30,a)))),"deleting a group is not additive");
        check(!DailyQuotaData.additive(base,new JSONObject(config(true,group("a",20,a),group("b",20,b)))),"lowering minutes is not additive");
        check(!DailyQuotaData.additive(base,new JSONObject(config(true,group("a",30,b),group("b",20,a)))),"swapping members is not additive");
        check(!DailyQuotaData.additive(base,new JSONObject(config(false,group("a",30,a),group("b",20,b)))),"switching the quota off is not additive");
        check(DailyQuotaData.additive(new JSONObject(config(false,group("a",30,a))),new JSONObject(config(true,group("a",30,a)))),"switching the quota on is additive");
        check(!DailyQuotaData.additive(null,base),"no current config means nothing is additive");
        reject(()->DailyQuotaData.requireEdit(calendar(at(29,12,0,0))),"writes rejected outside the nightly window");
        check(DailyQuotaData.nextEdit(calendar(at(29,23,0,0)))==at(30,22,0,0),"next edit window is tomorrow 22:00");
        final String duplicate=config(true,ga,group("other",2,a));reject(()->new DailyQuotaData(duplicate,"{}",noon),"same app cannot be in two groups");
        final String empty=config(true);reject(()->new DailyQuotaData(empty,"{}",noon),"enabled empty selection rejected");
        final String invalid=config(true,group("invalid",0,a));reject(()->new DailyQuotaData(invalid,"{}",noon),"zero allowance rejected");
        check(LocalBackup.key("afs_daily_quota","config")&&LocalBackup.key("afs_daily_quota","usage"),"local backup includes config and ledger");
        System.out.println("Daily independent/shared quota: "+checks+" checks passed");
    }
}
