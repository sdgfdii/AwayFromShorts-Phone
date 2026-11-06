package com.afs.guard;

import org.json.*;
import java.util.*;

/** Independent/shared allowances; each app follows its assigned schedule. Ledger stays per app. */
final class DailyQuotaData {
    final JSONObject config;
    JSONObject usage;
    DailyQuotaData(String raw,String ledger,long now){this(raw,ledger,now,DayBoundary.DEFAULT_HOUR);}
    /** resetHour 决定账本 date 键按哪个额度日写；只在账本为空/损坏时用到。 */
    DailyQuotaData(String raw,String ledger,long now,int resetHour){
        try{config=new JSONObject(raw);if(!config.has("groups")){if(config.length()!=0)throw new IllegalArgumentException(T.s("限额配置缺少分组"));put(config,"enabled",false);put(config,"groups",new JSONArray());}validate(config);}
        catch(JSONException e){throw new IllegalArgumentException(T.s("每日限额配置无法读取"),e);}
        try{usage=new JSONObject(ledger);if(usage.length()==0)usage=fresh(now,resetHour);validateUsage(usage);}
        catch(RuntimeException|JSONException e){usage=fresh(now,resetHour);put(usage,"error",T.s("用量记录损坏，限额保护性冻结至次日"));}
    }
    static JSONObject remainingLegacy(JSONObject original,Set<String> migrated){JSONObject remaining=MemoryData.copy(original);for(String pkg:migrated)remaining.remove(pkg);return remaining;}
    static void validate(JSONObject config){
        if(!(config.opt("enabled") instanceof Boolean)||config.optJSONArray("groups")==null)throw new IllegalArgumentException(T.s("每日限额配置无效"));
        Set<String> ids=new HashSet<>(),apps=new HashSet<>();JSONArray groups=config.optJSONArray("groups");
        if(groups.length()>100)throw new IllegalArgumentException(T.s("最多设置100个限额格子"));
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g==null)throw new IllegalArgumentException(T.s("限额格子无效"));String id=g.optString("id"),title=g.optString("name");Object minutes=g.opt("minutes");JSONArray members=g.optJSONArray("apps");
            if(!id.matches("[A-Za-z0-9_-]{1,80}")||!ids.add(id)||title.trim().isEmpty()||title.length()>80||!(minutes instanceof Number)||((Number)minutes).doubleValue()!=g.optInt("minutes")||g.optInt("minutes")<1||g.optInt("minutes")>1440||members==null||members.length()<1||members.length()>100)throw new IllegalArgumentException(T.s("格子名称、应用或分钟数无效（1–1440整数分钟）"));
            for(int j=0;j<members.length();j++){Object pkg=members.opt(j);if(!(pkg instanceof String)||!((String)pkg).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||!apps.add((String)pkg))throw new IllegalArgumentException(T.s("同一应用只能放在一个限额格子里"));}
            JSONArray background=g.optJSONArray("background");
            if(background!=null)for(int j=0;j<background.length();j++){Object pkg=background.opt(j);if(!(pkg instanceof String)||!apps.contains((String)pkg))throw new IllegalArgumentException(T.s("挂后台计时的应用必须先在这个格子里"));}
        }
        if(config.optBoolean("enabled")&&groups.length()==0)throw new IllegalArgumentException(T.s("请至少添加一个应用限额格子"));
    }
    /**
     * 额度的「收紧」权限只在全局调整时段内开放（{@link TimeWindow}）。
     *
     * 其余时间走 {@link #additive} 那条路 —— 能加（新格子、加应用、加分钟数、把总开关打开），
     * 不能减（移出应用、调小分钟数、删格子、关总开关）。理由很直白：
     * 白天额度不够就该补得上，但想把自己放出来这件事，只能等到调整时段。
     * 想彻底放开，把 EDIT_WINDOW 设成 false 即可。
     */
    static final boolean EDIT_WINDOW = true;
    static boolean editable(Calendar now){return editable(now,TimeWindow.DEFAULT_START,TimeWindow.DEFAULT_END);}
    static boolean editable(Calendar now,int winStart,int winEnd){return !EDIT_WINDOW||TimeWindow.contains(now.getTimeInMillis(),winStart,winEnd);}
    static void requireEdit(Calendar now){if(!editable(now))throw new IllegalStateException(T.s("这个改动要减少额度，只在调整时段内开放"));}
    /**
     * 候选配置相对当前是不是「只加不减」：新开格子、给格子加成员、加分钟数、把总开关打开。
     * 只要出现移除成员、调小分钟数、删格子、关总开关，就不算 —— 那些得等编辑窗口。
     * 纯数据，离线可测。
     */
    static boolean additive(JSONObject current,JSONObject candidate){
        if(current==null||candidate==null)return false;
        if(current.optBoolean("enabled")&&!candidate.optBoolean("enabled"))return false;
        JSONArray before=current.optJSONArray("groups"),after=candidate.optJSONArray("groups");
        if(before==null||after==null)return false;
        Map<String,JSONObject> was=new HashMap<>(),is=new HashMap<>();
        for(int i=0;i<before.length();i++){JSONObject g=before.optJSONObject(i);if(g!=null)was.put(g.optString("id"),g);}
        for(int i=0;i<after.length();i++){JSONObject g=after.optJSONObject(i);if(g!=null)is.put(g.optString("id"),g);}
        for(String id:was.keySet())if(!is.containsKey(id))return false;
        for(Map.Entry<String,JSONObject> row:is.entrySet()){
            JSONObject old=was.get(row.getKey());
            if(old==null)continue;
            JSONObject next=row.getValue();
            if(next.optInt("minutes")<old.optInt("minutes"))return false;
            Set<String> kept=new HashSet<>(),now=new HashSet<>();
            JSONArray a=old.optJSONArray("apps"),b=next.optJSONArray("apps");
            if(a!=null)for(int i=0;i<a.length();i++)kept.add(a.optString(i));
            if(b!=null)for(int i=0;i<b.length();i++)now.add(b.optString(i));
            if(!now.containsAll(kept))return false;
            // 挂后台计时一旦取消就是放宽：跟移出应用同一条规矩，只能等调整时段。
            if(!DailyQuotaData.backgroundApps(next).containsAll(DailyQuotaData.backgroundApps(old)))return false;
        }
        return true;
    }
    /** 这一格里勾了「挂后台也计时」的应用集合；没这个字段的旧配置返回空集。 */
    static Set<String> backgroundApps(JSONObject group){Set<String> out=new HashSet<>();JSONArray background=group.optJSONArray("background");if(background!=null)for(int i=0;i<background.length();i++)out.add(background.optString(i));return out;}
    /** 下一次调整时段开门的时间；时段为全天（start==end）时随时可改，返回当前时刻。 */
    static long nextEdit(Calendar now){return nextEdit(now,TimeWindow.DEFAULT_START,TimeWindow.DEFAULT_END);}
    static long nextEdit(Calendar now,int winStart,int winEnd){
        if(winStart==winEnd)return now.getTimeInMillis();
        if(TimeWindow.contains(now.getTimeInMillis(),winStart,winEnd))return now.getTimeInMillis();   // 已在窗口内（含跨午夜的后半夜）
        Calendar c=(Calendar)now.clone();
        c.set(Calendar.HOUR_OF_DAY,winStart/60);c.set(Calendar.MINUTE,winStart%60);
        c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        if(!c.after(now))c.add(Calendar.DAY_OF_MONTH,1);                                              // 今天的开点已过 → 明天
        return c.getTimeInMillis();
    }
    boolean enabled(){return config.optBoolean("enabled");}
    JSONArray groups(){return config.optJSONArray("groups");}
    Set<String> packages(){Set<String> out=new LinkedHashSet<>();for(int i=0;i<groups().length();i++){JSONArray apps=groups().optJSONObject(i).optJSONArray("apps");for(int j=0;j<apps.length();j++)out.add(apps.optString(j));}return out;}
    JSONObject group(String pkg){for(int i=0;i<groups().length();i++){JSONObject group=groups().optJSONObject(i);JSONArray apps=group.optJSONArray("apps");for(int j=0;j<apps.length();j++)if(pkg.equals(apps.optString(j)))return group;}return null;}
    /** 按 id 取额度组；跨设备共享那边只存着组 id。 */
    JSONObject groupById(String id){if(id==null||id.isEmpty())return null;for(int i=0;i<groups().length();i++){JSONObject group=groups().optJSONObject(i);if(id.equals(group.optString("id")))return group;}return null;}
    long used(JSONObject group){long total=0;JSONArray apps=group.optJSONArray("apps");for(int i=0;i<apps.length();i++)total+=usage.optJSONObject("targets").optLong(apps.optString(i));return total;}
    boolean exceeded(String pkg){JSONObject g=group(pkg);return enabled()&&g!=null&&(usage.optBoolean("unavailable")||!usage.optString("error").isEmpty()||used(g)>=g.optInt("minutes")*60000L);}
    static boolean inScope(Rule rule,long now){Calendar c=Calendar.getInstance();c.setTimeInMillis(now);return rule==null||FreezeSlotStore.unassigned(rule)||!rule.inWindow(c);}
    /**
     * 额度是否触发的纯判定（把 {@link DailyQuotaStore#exceeded} 里那段优先级规则抽出来，离线可测）。
     *
     * 共享格子优先于应用自身的冻结时段：共享组一律不受 {@code inScope} 约束 ——
     * 只要本机这一份满了（{@code localExceeded}）或跨设备合计用尽（{@code lanExhausted}）就冻，
     * 不管它此刻是不是在自己的放行时段里；反过来没满就放行，也不因时段单独冻结它。
     * 独立格子沿用旧口径：必须在本应用承担的时段范围内（{@code inScope}）才判超限。
     */
    static boolean exceededBy(boolean shared,boolean localExceeded,boolean inScope,boolean lanExhausted){
        return shared?(localExceeded||lanExhausted):(inScope&&localExceeded);
    }
    void advance(long now,List<AppUsageQuota.Span> spans,boolean reliable){advance(now,spans,reliable,Collections.emptyMap(),DayBoundary.DEFAULT_HOUR);}
    void advance(long now,List<AppUsageQuota.Span> spans,boolean reliable,Map<String,Rule> rules){advance(now,spans,reliable,rules,DayBoundary.DEFAULT_HOUR);}
    /**
     * 账本推进：按「应用自身冻结时段」裁剪，不看全局调整时段。
     *
     * 调整时段（默认 22:00–23:00）只决定「什么时候能改设置」，跟统计和判超无关。
     * 之前拿它过滤账本，结果白天用了几小时全都不记，额度永远停在 0 —— 那是个错误的口径。
     */
    void advance(long now,List<AppUsageQuota.Span> spans,boolean reliable,Map<String,Rule> rules,int resetHour){
        long begin=DayBoundary.startMs(now,resetHour);
        // 跨额度日重置前先读旧账本的起点：reset 会把 since 写成 now，白白丢掉今天零点到现在的用量。
        long since=usage.optLong("since",begin);   // 老账本没有 since：按「从额度日起点算」自愈重算
        if(!DayBoundary.key(now,resetHour).equals(usage.optString("date")))usage=fresh(now,resetHour);
        // since = 这份账本开始统计的时刻（新建当天账本 / 启用额度那一刻）。早于它的用量不计，
        // 否则「下午才开额度」会把上午的用量一次性算进来。
        long limit=Math.min(Math.max(since,begin),now);
        if(enabled()&&reliable&&usage.optString("error").isEmpty()){
            Set<String> apps=packages();JSONObject targets=usage.optJSONObject("targets");
            // 按整段用量「重算」，不再按窗口增量累加。
            // 增量法只要有一次刷新失败 / 进程被杀 / 系统事件延迟，那一小段就永久丢失（真机上一天能丢九成）；
            // 重算每次都从今天的 spans 重新求和，漏掉的自动补回来，且天然幂等。
            Map<String,Long> computed=new HashMap<>();
            for(AppUsageQuota.Span s:spans)if(apps.contains(s.pkg)){
                Rule rule=rules.get(s.pkg);Rule scope=rule==null||FreezeSlotStore.unassigned(rule)?null:rule;
                long a=Math.max(limit,s.start),b=Math.min(now,s.end);
                if(b>a){long elapsed=AppUsageQuota.overlap(a,b,scope);if(elapsed>0)computed.put(s.pkg,computed.getOrDefault(s.pkg,0L)+elapsed);}
            }
            // 只升不降：系统把历史事件裁掉时算出来的数会变小，不能把已记上的用量抹回去。
            for(Map.Entry<String,Long> e:computed.entrySet()){String pkg=e.getKey();if(e.getValue()>targets.optLong(pkg))put(targets,pkg,e.getValue());}
        }
        if(enabled()&&!reliable)put(usage,"unavailable",true);else usage.remove("unavailable");
        put(usage,"lastAt",now);
    }
    /** 所有格子里勾了「挂后台也计时」的应用集合；接线处拿它决定哪些应用要并上前台服务区间。 */
    Set<String> backgroundPackages(){Set<String> out=new HashSet<>();for(int i=0;i<groups().length();i++)out.addAll(backgroundApps(groups().optJSONObject(i)));return out;}
    static JSONObject fresh(long now){return fresh(now,DayBoundary.DEFAULT_HOUR);}
    static JSONObject fresh(long now,int resetHour){JSONObject out=new JSONObject();put(out,"date",DayBoundary.key(now,resetHour));put(out,"lastAt",now);put(out,"since",now);put(out,"targets",new JSONObject());return out;}
    static void validateUsage(JSONObject usage){if(!usage.optString("date").matches("\\d{4}-\\d{2}-\\d{2}")||!(usage.opt("lastAt") instanceof Number)||usage.optLong("lastAt")<0||usage.optJSONObject("targets")==null)throw new IllegalArgumentException(T.s("用量数据无效"));Iterator<String> keys=usage.optJSONObject("targets").keys();while(keys.hasNext()){Object value=usage.optJSONObject("targets").opt(keys.next());if(!(value instanceof Number)||((Number)value).doubleValue()<0||!Double.isFinite(((Number)value).doubleValue())||((Number)value).doubleValue()>86400000L)throw new IllegalArgumentException(T.s("用量数据无效"));}}
    static void put(JSONObject o,String key,Object value){MemoryData.put(o,key,value);}
    /**
     * 已卸载应用的自动清理：从每个格子的成员（和「挂后台」名单）里摘掉不在 {@code installed}
     * 集合里的包，被清空的格子一并删除；账本里这些包的已用时间也一起抹掉，共享合计随之变小。
     *
     * 用户反馈「应用都卸载了，怎么还在限额格子里」：额度只按包名存，卸载既不触发移除，也不会
     * 让它退出共享合计，于是格子里永远挂着一只已卸载的应用、还占着当天那份时间。
     *
     * 纯数据，离线可测。返回 {@code {config, usage, removed}} 三个键：config/usage 是处理后的
     * 副本（没有变化时也返回等价副本），removed 是本次摘掉的应用数（0 表示无需写盘）。
     * 若清空后 groups 变空且原来 enabled，则一并把 enabled 置 false，保证配置仍然合法。
     */
    static JSONObject pruneUninstalled(JSONObject config,JSONObject usage,Set<String> installed){
        JSONObject nextConfig=MemoryData.copy(config),nextUsage=MemoryData.copy(usage);
        JSONArray groups=nextConfig.optJSONArray("groups");
        int removed=0;
        if(groups!=null&&installed!=null){
            JSONArray kept=new JSONArray();
            for(int i=0;i<groups.length();i++){
                JSONObject g=groups.optJSONObject(i);if(g==null)continue;
                JSONArray apps=g.optJSONArray("apps"),keepApps=new JSONArray();
                if(apps!=null)for(int j=0;j<apps.length();j++){String p=apps.optString(j);if(installed.contains(p))keepApps.put(p);else removed++;}
                if(keepApps.length()==0)continue; // 整格都是已卸载的 → 删掉这格
                put(g,"apps",keepApps);
                JSONArray bg=g.optJSONArray("background");
                if(bg!=null){JSONArray keepBg=new JSONArray();for(int j=0;j<bg.length();j++){String p=bg.optString(j);if(installed.contains(p))keepBg.put(p);}put(g,"background",keepBg);}
                kept.put(g);
            }
            put(nextConfig,"groups",kept);
            if(kept.length()==0&&nextConfig.optBoolean("enabled"))put(nextConfig,"enabled",false);
        }
        JSONObject targets=nextUsage.optJSONObject("targets");
        if(targets!=null&&installed!=null){
            List<String> drop=new ArrayList<>();
            for(java.util.Iterator<String> keys=targets.keys();keys.hasNext();){String p=keys.next();if(!installed.contains(p))drop.add(p);}
            for(String p:drop)targets.remove(p);
        }
        JSONObject out=new JSONObject();
        put(out,"config",nextConfig);put(out,"usage",nextUsage);put(out,"removed",removed);
        return out;
    }
    /** 把 id 对应的格子摘掉，其余原样返回（新建/改名/合并都用它铺底）。 */
    static JSONArray without(JSONObject config,String id){
        JSONArray out=new JSONArray();JSONArray groups=config.optJSONArray("groups");if(groups==null)return out;
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g!=null&&!id.equals(g.optString("id")))out.put(g);}
        return out;
    }
    /** 把 fromId 的成员并进 intoId（分钟取调用方给的较小值），返回是否合并成功。纯数据，离线可测。 */
    static boolean mergeInto(JSONObject config,String intoId,String fromId,int minutes){
        if(intoId==null||fromId==null||intoId.equals(fromId))return false;
        JSONArray groups=config.optJSONArray("groups");if(groups==null)return false;
        JSONObject into=null,from=null;
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g==null)continue;if(intoId.equals(g.optString("id")))into=g;else if(fromId.equals(g.optString("id")))from=g;}
        if(into==null||from==null)return false;
        Set<String> apps=new LinkedHashSet<>();JSONArray a=into.optJSONArray("apps"),b=from.optJSONArray("apps");
        if(a==null||b==null)return false;
        for(int i=0;i<a.length();i++)apps.add(a.optString(i));
        for(int i=0;i<b.length();i++)apps.add(b.optString(i));
        put(into,"apps",new JSONArray(apps));put(into,"minutes",minutes);
        put(config,"groups",without(config,fromId));
        return true;
    }
    /**
     * 把一只应用迁到 targetId 那一格：先从别的格子摘掉，再加进目标格子；
     * 被搬空的格子一起删掉。返回 1＝迁成功，0＝已经在这一格里，-1＝目标不存在。纯数据，离线可测。
     */
    static int moveInto(JSONObject config,String targetId,String pkg){
        JSONArray groups=config.optJSONArray("groups");
        if(groups==null||pkg==null||pkg.isEmpty())return -1;
        JSONObject target=null;
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g!=null&&targetId.equals(g.optString("id")))target=g;}
        if(target==null)return -1;
        JSONArray members=target.optJSONArray("apps");
        if(members==null)return -1;
        for(int i=0;i<members.length();i++)if(pkg.equals(members.optString(i)))return 0;
        for(int i=0;i<groups.length();i++){
            JSONObject g=groups.optJSONObject(i);if(g==null||g==target)continue;
            JSONArray apps=g.optJSONArray("apps");if(apps==null)continue;
            JSONArray keep=new JSONArray();boolean removed=false;
            for(int j=0;j<apps.length();j++){String p=apps.optString(j);if(p.equals(pkg)){removed=true;continue;}keep.put(p);}
            if(removed)put(g,"apps",keep);
        }
        members.put(pkg);
        for(int i=groups.length()-1;i>=0;i--){JSONObject g=groups.optJSONObject(i);JSONArray apps=g==null?null:g.optJSONArray("apps");if(apps==null||apps.length()==0)groups.remove(i);}
        return 1;
    }
}
