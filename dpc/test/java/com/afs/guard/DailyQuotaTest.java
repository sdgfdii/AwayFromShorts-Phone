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
        DailyQuotaData merged=new DailyQuotaData(config(true,shared),d.usage.toString(),noon+60000);check(merged.used(shared)==60000,"merge sums retained per-app usage");// 重算口径：advance 收到的是「今天整天」的 spans（真实调用就是 spans(c,now,true) 的全量）
        merged.advance(noon+90000,spans(span(a,noon,noon+30000),span(b,noon+30000,noon+60000),span(a,noon+60000,noon+90000)),true);check(merged.used(shared)==90000,"shared budget combines both apps");
        DailyQuotaData split=new DailyQuotaData(config(true,ga,gb),merged.usage.toString(),noon+90000);check(split.used(ga)==60000&&split.used(gb)==30000,"split restores each exact app usage");check(split.exceeded(a)&&!split.exceeded(b),"one exhausted independent app does not block another");
        DailyQuotaData removed=new DailyQuotaData(config(true,gb),split.usage.toString(),noon+90000);removed.advance(noon+120000,spans(span(a,noon+90000,noon+120000)),true);DailyQuotaData added=new DailyQuotaData(config(true,ga,gb),removed.usage.toString(),noon+120000);check(added.used(ga)==60000&&added.exceeded(a),"remove and re-add preserves used time without charging removed intervals");
        // 额度全天累计：收束时段（默认 22:00–23:00）之外用掉的时间也必须记账，
        // 否则白天玩了几个小时，「今日已用」还是 0。
        JSONObject allDayGroup=group("allday",120,a);
        DailyQuotaData allDay=new DailyQuotaData(config(true,allDayGroup),"{}",at(29,0,1,0));
        allDay.advance(at(29,15,0,0),spans(span(a,at(29,14,0,0),at(29,15,0,0))),true);
        check(allDay.used(allDayGroup)==3600000L,"usage outside the focus window is still counted");
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
        List<AppUsageQuota.Span> fullDay=spans(span(a,noon,noon+30000),span(b,noon+30000,noon+60000),span(a,noon+60000,noon+90000),span(b,noon+180000,noon+210000));
        DailyQuotaData paused=new DailyQuotaData(config(false,ga,gb),added.usage.toString(),noon+120000);paused.advance(noon+180000,fullDay,true);check(!paused.exceeded(a)&&paused.used(ga)==60000,"paused quota neither blocks nor counts");
        DailyQuotaData resumed=new DailyQuotaData(config(true,ga,gb),paused.usage.toString(),noon+180000);resumed.advance(noon+210000,fullDay,true);check(resumed.used(ga)==60000&&resumed.used(gb)==60000,"resume recomputes the day and keeps per-app totals");
        List<AppUsageQuota.Span> day2=new ArrayList<>(fullDay);day2.add(span(b,noon+210000,noon+240000));
        DailyQuotaData restart=new DailyQuotaData(config(true,ga,gb),resumed.usage.toString(),noon+210000);restart.advance(noon+240000,day2,true);check(restart.used(gb)==90000,"restart recomputes the same day without losing usage");
        DailyQuotaData unreadable=new DailyQuotaData(config(true,ga,gb),"broken",noon);check(unreadable.exceeded(a)&&unreadable.exceeded(b),"corrupt ledger cannot grant another allowance");unreadable.advance(at(30,8,0,1),Collections.emptyList(),true);check(!unreadable.exceeded(a),"corrupt day's protective freeze clears on next quota day (8:00)");
        DailyQuotaData permission=new DailyQuotaData(config(true,ga),"{}",noon);permission.advance(noon+1000,Collections.emptyList(),false);check(permission.exceeded(a),"unreadable events protect enabled group");permission.advance(noon+2000,Collections.emptyList(),true);check(!permission.exceeded(a),"recovered permission clears transient protection");
        long before=at(30,7,59,59),after=at(30,8,0,1);DailyQuotaData midnight=new DailyQuotaData(config(true,ga),"{}",before);midnight.advance(after,spans(span(a,before,after)),true);check(midnight.used(ga)==1000,"quota-day roll at 8:00 counts only the new day's part");
        Rule afternoon=new Rule(Rule.ALL_DAYS,new int[][]{{12*60,13*60}},true,true);
        Map<String,Rule> scopes=new HashMap<>();scopes.put(a,afternoon);
        DailyQuotaData scoped=new DailyQuotaData(config(true,shared),"{}",at(29,11,59,0));
        List<AppUsageQuota.Span> scopedSpans=new ArrayList<>();
        scopedSpans.add(span(a,at(29,11,59,0),at(29,12,0,30)));scopedSpans.add(span(b,at(29,12,0,30),at(29,12,1,0)));
        scoped.advance(at(29,12,1,0),scopedSpans,true,scopes);
        check(scoped.used(shared)==60000,"shared: assigned app counts only slot seconds, unassigned app counts all day");
        check(!DailyQuotaData.inScope(afternoon,at(29,11,59,59))&&DailyQuotaData.inScope(afternoon,noon),"slot start inclusive; outside is allowed");
        check(!DailyQuotaData.inScope(afternoon,at(29,13,0,0)),"slot end exclusive even if budget exhausted");
        check(DailyQuotaData.inScope(null,noon)&&DailyQuotaData.inScope(FreezeSlotStore.withoutSchedule(),noon),"unassigned and quota-only apps use all-day scope");
        long retained=scoped.used(shared);scopes.put(a,new Rule(Rule.ALL_DAYS,new int[][]{{13*60,14*60}},true,true));
        scopedSpans.add(span(a,at(29,12,1,0),at(29,12,2,0)));
        scoped.advance(at(29,12,2,0),scopedSpans,true,scopes);
        check(scoped.used(shared)==retained,"moving slots retains usage and excludes time outside the new scope");
        long beforeRecompute=scoped.used(shared);scopedSpans.add(span(a,at(29,12,2,0),at(29,12,2,30)));
        scopes.remove(a);scoped.advance(at(29,12,2,30),scopedSpans,true,scopes);
        // 重算口径：改回全天后天内所有区间都按当前 scope 重算，只升不降
        check(scoped.used(shared)==210000&&scoped.used(shared)>=beforeRecompute,"moving back to all-day re-evaluates the day and never loses usage");
        Rule night=new Rule(Rule.ALL_DAYS,new int[][]{{23*60,60}},true,true);scopes.put(a,night);
        DailyQuotaData overnight=new DailyQuotaData(config(true,ga),"{}",at(29,23,59,59));overnight.advance(at(30,0,0,1),spans(span(a,at(29,23,59,59),at(30,0,0,1))),true,scopes);
        // 额度日是 8 点到 8 点：零点不再是账本边界，跨零点的整段都算同一个额度日
        check(overnight.used(ga)==2000,"overnight slot keeps counting across midnight (quota day is 8-to-8)");
        check(DailyQuotaData.inScope(night,at(30,0,59,59))&&!DailyQuotaData.inScope(night,at(30,1,0,0)),"overnight allowance ends at its exact release boundary");
        for(long stamp:new long[]{at(29,12,0,0),at(29,21,0,0),at(29,23,0,0),at(29,3,0,0)})check(!DailyQuotaData.editable(calendar(stamp)),"quota config locked outside 22-23 "+stamp);
        for(long stamp:new long[]{at(29,22,0,0),at(29,22,59,59)})check(DailyQuotaData.editable(calendar(stamp)),"quota config editable inside 22-23 "+stamp);
        // 自定义窗口走同一判断；start==end = 全天 = 随时可改
        check(!DailyQuotaData.editable(calendar(at(29,12,0,0)),1320,60),"custom overnight window locked at noon");
        check(DailyQuotaData.editable(calendar(at(29,23,0,0)),1320,60),"custom overnight window open at 23:00");
        check(DailyQuotaData.editable(calendar(at(29,12,0,0)),600,600),"all-day window always editable");
        // 「只进不出」闸门：窗口外随时能加，减要等收束时段
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
        check(DailyQuotaData.nextEdit(calendar(at(29,15,0,0)))==at(29,22,0,0),"next edit window is tonight 22:00");
        check(DailyQuotaData.nextEdit(calendar(at(29,22,30,0)))==at(29,22,30,0),"inside window edits now");
        check(DailyQuotaData.nextEdit(calendar(at(29,2,0,0)),1320,60)==at(29,22,0,0),"overnight next open tonight 22:00");
        check(DailyQuotaData.nextEdit(calendar(at(29,0,30,0)),1320,60)==at(29,0,30,0),"overnight inside edits now");
        final String duplicate=config(true,ga,group("other",2,a));reject(()->new DailyQuotaData(duplicate,"{}",noon),"same app cannot be in two groups");
        final String empty=config(true);reject(()->new DailyQuotaData(empty,"{}",noon),"enabled empty selection rejected");
        final String invalid=config(true,group("invalid",0,a));reject(()->new DailyQuotaData(invalid,"{}",noon),"zero allowance rejected");
        check(LocalBackup.key("afs_daily_quota","config")&&LocalBackup.key("afs_daily_quota","usage"),"local backup includes config and ledger");
        JSONObject legacy=new JSONObject().put(a,30).put(b,15);
        JSONObject remaining=DailyQuotaData.remainingLegacy(legacy,Collections.singleton(a));
        check(!remaining.has(a)&&remaining.optInt(b)==15,"migration deletes only successful apps and retains failures/conflicts");
        check(legacy.optInt(a)==30&&legacy.optInt(b)==15,"migration cleanup never mutates the backup source");
        check(DailyQuotaData.remainingLegacy(legacy,Collections.emptySet()).length()==2,"no successful migration leaves all legacy limits");
        long change=DayBoundary.changeAt(noon,0,0);
        check(change==at(30,0,0,0),"reset time change is deferred until current quota day ends");
        DailyQuotaData resetPreserved=new DailyQuotaData(config(true,ga),"{}",noon);
        List<AppUsageQuota.Span> resetSpans=new ArrayList<>();
        resetSpans.add(span(a,noon,noon+30000));
        resetPreserved.advance(noon+30000,resetSpans,true);
        resetSpans.add(span(a,noon+30000,noon+60000));
        resetPreserved.advance(noon+60000,resetSpans,true,Collections.emptyMap(),0);
        check(resetPreserved.used(ga)==60000&&resetPreserved.exceeded(a),"pending reset edit preserves and continues today's spent allowance");
        // ── 挂后台也计时（1.88.0）：切到后台「还在跑」＝前台服务在运行；关掉就停，切走闲置不计 ──
        JSONObject bgGroup=new JSONObject().put("id","bg").put("name","bg").put("minutes",1)
                .put("apps",new JSONArray(Arrays.asList(a))).put("background",new JSONArray(Arrays.asList(a)));
        // 前台服务区间与前台区间取并集：5 秒前台 + 50 秒挂后台播放 = 55 秒
        DailyQuotaData burn=new DailyQuotaData(config(true,bgGroup),"{}",noon);
        List<AppUsageQuota.Span> fgMerged=AppUsageQuota.effective(spans(span(a,noon,noon+5000)),spans(span(a,noon+10000,noon+60000)),burn.backgroundPackages());
        burn.advance(noon+60000,fgMerged,true);
        check(burn.used(bgGroup)==55000,"background FGS time joins the usage as a union");
        // 与前台重叠不双倍：前台 30 秒，服务 10s-40s → 一共 40 秒
        DailyQuotaData noDouble=new DailyQuotaData(config(true,bgGroup),"{}",noon);
        List<AppUsageQuota.Span> overlapped=AppUsageQuota.effective(spans(span(a,noon,noon+30000)),spans(span(a,noon+10000,noon+40000)),noDouble.backgroundPackages());
        noDouble.advance(noon+60000,overlapped,true);
        check(noDouble.used(bgGroup)==40000,"overlapping foreground and background merge into one interval");
        // 应用已经关掉：只计前台那 5 秒，不再「打开就一直烧」（1.87.0 的坑）
        DailyQuotaData closed=new DailyQuotaData(config(true,bgGroup),"{}",noon);
        closed.advance(noon+60000,spans(span(a,noon,noon+5000)),true);
        check(closed.used(bgGroup)==5000&&!closed.exceeded(a),"closed app stops counting at its last foreground second");
        // 重算自愈：中间一次读不到用量（系统事件延迟 / 进程重启 / 权限抖动），下一次重算把整段补回来
        DailyQuotaData heal=new DailyQuotaData(config(true,ga),"{}",noon);
        heal.advance(noon+60000,spans(span(a,noon,noon+60000)),true);
        long healed=heal.used(ga);
        heal.advance(noon+120000,Collections.emptyList(),true);
        check(heal.used(ga)==healed,"empty sample never erases counted usage");
        heal.advance(noon+180000,spans(span(a,noon,noon+180000)),true);
        check(heal.used(ga)==180000,"a later sample heals the minutes an earlier refresh missed");
        // 只用「今天这段」：昨天的用量不进今天的账
        DailyQuotaData yesterday=new DailyQuotaData(config(true,ga),new JSONObject().put("date","2026-10-28").put("lastAt",at(28,23,0,0)).put("targets",new JSONObject()).toString(),at(29,9,0,0));
        yesterday.advance(at(29,10,0,0),spans(span(a,at(29,7,0,0),at(29,8,0,0)),span(a,at(29,9,0,0),at(29,9,30,0))),true);
        check(yesterday.used(ga)==1800000,"only the current quota day counts");
        // 没勾的应用完全不受前台服务影响：同样的区间，不勾就只计前台
        JSONObject plainGroup=new JSONObject().put("id","p").put("name","p").put("minutes",1).put("apps",new JSONArray(Arrays.asList(a)));
        DailyQuotaData unflagged=new DailyQuotaData(config(true,plainGroup),"{}",noon);
        List<AppUsageQuota.Span> withFgs=AppUsageQuota.effective(spans(span(a,noon,noon+5000)),spans(span(a,noon+10000,noon+60000)),unflagged.backgroundPackages());
        check(withFgs.size()==1&&withFgs.get(0).end==noon+5000,"unflagged apps ignore foreground-service intervals");
        // 服务还开着（区间没闭合）：一直计到 now
        DailyQuotaData running=new DailyQuotaData(config(true,bgGroup),"{}",noon);
        running.advance(noon+60000,AppUsageQuota.effective(Collections.emptyList(),spans(span(a,noon+10000,noon+60000)),running.backgroundPackages()),true);
        check(running.used(bgGroup)==50000,"a still-running foreground service counts up to now");
        // 自身冻结时段对挂后台区间同样豁免：12:00-13:00 冻结，服务 11:59-12:01 → 只计 11:59-12:00 那一分钟
        Rule freezeNoon=new Rule(Rule.ALL_DAYS,new int[][]{{12*60,13*60}},true,true);
        Map<String,Rule> bgScopes=new HashMap<>();bgScopes.put(a,freezeNoon);
        DailyQuotaData scopedBg=new DailyQuotaData(config(true,bgGroup),"{}",at(29,11,58,0));
        List<AppUsageQuota.Span> scopedFgs=AppUsageQuota.effective(Collections.emptyList(),spans(span(a,at(29,11,59,0),at(29,12,1,0))),scopedBg.backgroundPackages());
        scopedBg.advance(at(29,12,1,0),scopedFgs,true,bgScopes);
        check(scopedBg.used(bgGroup)==60000,"background counting still respects the app's own freeze slot");
        // union 纯函数：重叠与相邻都合成一段，不同应用互不合并
        List<AppUsageQuota.Span> united=AppUsageQuota.union(spans(span(a,0,100),span(a,50,200),span(a,200,300),span(b,100,150)));
        check(united.size()==2,"union keeps packages separate");
        AppUsageQuota.Span mergedA=united.get(0),onlyB=united.get(1);
        check(mergedA.pkg.equals(a)&&mergedA.start==0&&mergedA.end==300,"overlap and adjacency collapse into one interval");
        check(onlyB.pkg.equals(b)&&onlyB.start==100&&onlyB.end==150,"different packages stay separate");
        // 可加不可减：新增挂后台随时可以，取消要等调整时段
        JSONObject plainConfig=new JSONObject(config(true,group("p",30,a)));
        JSONObject withBg=new JSONObject(plainConfig.toString());withBg.optJSONArray("groups").optJSONObject(0).put("background",new JSONArray(Arrays.asList(a)));
        check(DailyQuotaData.additive(plainConfig,withBg),"adding background counting is additive");
        check(!DailyQuotaData.additive(withBg,plainConfig),"cancelling background counting needs the edit window");
        // 校验：挂后台名单必须是本格子成员
        JSONObject stray=new JSONObject().put("id","x").put("name","x").put("minutes",30)
                .put("apps",new JSONArray(Arrays.asList(a))).put("background",new JSONArray(Arrays.asList(b)));
        JSONObject strayConfig=new JSONObject().put("enabled",true).put("groups",new JSONArray(Arrays.asList(stray)));
        reject(()->DailyQuotaData.validate(strayConfig),"background app outside the group is rejected");
        // ── 卸载应用自动清理（用户：MusicFree 都卸载了怎么还在限额格子里）──
        JSONObject pruneUsage=new JSONObject().put("date","2026-10-29").put("lastAt",noon)
                .put("targets",new JSONObject().put(a,40000).put(b,20000).put("com.example.gone",120000));
        JSONObject pruneConfig=new JSONObject(config(true,group("shared",2,a,b,"com.example.gone"),group("solo",30,"com.example.gone")));
        java.util.Set<String> installedAlive=new java.util.HashSet<>(Arrays.asList(a,b));
        JSONObject pruned=DailyQuotaData.pruneUninstalled(pruneConfig,pruneUsage,installedAlive);
        JSONObject prunedConfigOut=pruned.optJSONObject("config");
        JSONObject prunedUsageOut=pruned.optJSONObject("usage");
        JSONArray prunedGroups=prunedConfigOut.optJSONArray("groups");
        check(pruned.optInt("removed")==2,"prune counts each uninstalled membership once");
        check(prunedGroups.length()==1,"a group made only of uninstalled apps is deleted");
        JSONObject prunedShared=prunedGroups.optJSONObject(0);
        check(prunedShared.optString("id").equals("shared")&&prunedShared.optJSONArray("apps").length()==2,"installed members stay in the shared cell");
        JSONObject prunedTargets=prunedUsageOut.optJSONObject("targets");
        check(!prunedTargets.has("com.example.gone"),"uninstalled app's used time is dropped from the ledger");
        check(prunedTargets.optLong(a)==40000&&prunedTargets.optLong(b)==20000,"installed usage is untouched");
        check(pruneConfig.optJSONArray("groups").length()==2&&pruneUsage.optJSONObject("targets").optLong("com.example.gone")==120000,"prune never mutates its inputs");
        JSONObject allGone=DailyQuotaData.pruneUninstalled(new JSONObject(config(true,group("solo",30,"com.example.gone"))),new JSONObject("{}"),installedAlive);
        check(allGone.optJSONObject("config").optJSONArray("groups").length()==0&&!allGone.optJSONObject("config").optBoolean("enabled"),"pruning every app disables an otherwise-empty quota config");
        check(DailyQuotaData.pruneUninstalled(pruneConfig,pruneUsage,new java.util.HashSet<>(Arrays.asList(a,b,"com.example.gone"))).optInt("removed")==0,"nothing removed when every app is installed");
        // ── 共享格子优先于自身冻结时段（用户：nextplayer 应该因共享额度用尽冻结）──
        check(DailyQuotaData.exceededBy(true,true,false,false),"shared cell freezes even when the app is inside its own allow window");
        check(DailyQuotaData.exceededBy(true,false,false,true),"shared cell freezes when the cross-device pool is exhausted");
        check(!DailyQuotaData.exceededBy(true,false,false,false),"shared cell allows while it still has budget");
        check(DailyQuotaData.exceededBy(false,true,true,false),"independent cell still freezes inside its scope when exhausted");
        check(!DailyQuotaData.exceededBy(false,true,false,false),"independent cell keeps the in-scope gate (outside scope it lets go)");
        System.out.println("Daily independent/shared quota: "+checks+" checks passed");
    }
}
