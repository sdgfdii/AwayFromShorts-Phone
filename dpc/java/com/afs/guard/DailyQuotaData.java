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
    static void validate(JSONObject config){
        if(!(config.opt("enabled") instanceof Boolean)||config.optJSONArray("groups")==null)throw new IllegalArgumentException(T.s("每日限额配置无效"));
        Set<String> ids=new HashSet<>(),apps=new HashSet<>();JSONArray groups=config.optJSONArray("groups");
        if(groups.length()>100)throw new IllegalArgumentException(T.s("最多设置100个限额格子"));
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g==null)throw new IllegalArgumentException(T.s("限额格子无效"));String id=g.optString("id"),title=g.optString("name");Object minutes=g.opt("minutes");JSONArray members=g.optJSONArray("apps");
            if(!id.matches("[A-Za-z0-9_-]{1,80}")||!ids.add(id)||title.trim().isEmpty()||title.length()>80||!(minutes instanceof Number)||((Number)minutes).doubleValue()!=g.optInt("minutes")||g.optInt("minutes")<1||g.optInt("minutes")>1440||members==null||members.length()<1||members.length()>100)throw new IllegalArgumentException(T.s("格子名称、应用或分钟数无效（1–1440整数分钟）"));
            for(int j=0;j<members.length();j++){Object pkg=members.opt(j);if(!(pkg instanceof String)||!((String)pkg).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||!apps.add((String)pkg))throw new IllegalArgumentException(T.s("同一应用只能放在一个限额格子里"));}
        }
        if(config.optBoolean("enabled")&&groups.length()==0)throw new IllegalArgumentException(T.s("请至少添加一个应用限额格子"));
    }
    /**
     * 额度的「收紧」权限只在全局收束时段内开放（{@link TimeWindow}）。
     *
     * 其余时间走 {@link #additive} 那条路 —— 能加（新格子、加应用、加分钟数、把总开关打开），
     * 不能减（移出应用、调小分钟数、删格子、关总开关）。理由很直白：
     * 白天额度不够就该补得上，但想把自己放出来这件事，只能等到收束时段。
     * 想彻底放开，把 EDIT_WINDOW 设成 false 即可。
     */
    static final boolean EDIT_WINDOW = true;
    static boolean editable(Calendar now){return editable(now,TimeWindow.DEFAULT_START,TimeWindow.DEFAULT_END);}
    static boolean editable(Calendar now,int winStart,int winEnd){return !EDIT_WINDOW||TimeWindow.contains(now.getTimeInMillis(),winStart,winEnd);}
    static void requireEdit(Calendar now){if(!editable(now))throw new IllegalStateException(T.s("这个改动要减少额度，只在收束时段内开放"));}
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
        }
        return true;
    }
    /** 下一次收束时段开门的时间；时段为全天（start==end）时随时可改，返回当前时刻。 */
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
    long used(JSONObject group){long total=0;JSONArray apps=group.optJSONArray("apps");for(int i=0;i<apps.length();i++)total+=usage.optJSONObject("targets").optLong(apps.optString(i));return total;}
    boolean exceeded(String pkg){JSONObject g=group(pkg);return enabled()&&g!=null&&(usage.optBoolean("unavailable")||!usage.optString("error").isEmpty()||used(g)>=g.optInt("minutes")*60000L);}
    static boolean inScope(Rule rule,long now){Calendar c=Calendar.getInstance();c.setTimeInMillis(now);return rule==null||FreezeSlotStore.unassigned(rule)||!rule.inWindow(c);}
    /** 再叠一层全局收束时段：时段外一律不计时、也不判超，额度原样留着。 */
    static boolean inScope(Rule rule,long now,int winStart,int winEnd){return TimeWindow.contains(now,winStart,winEnd)&&inScope(rule,now);}
    void advance(long now,List<AppUsageQuota.Span> spans,boolean reliable){advance(now,spans,reliable,Collections.emptyMap(),DayBoundary.DEFAULT_HOUR);}
    void advance(long now,List<AppUsageQuota.Span> spans,boolean reliable,Map<String,Rule> rules){advance(now,spans,reliable,rules,DayBoundary.DEFAULT_HOUR);}
    void advance(long now,List<AppUsageQuota.Span> spans,boolean reliable,Map<String,Rule> rules,int resetHour){
        advance(now,spans,reliable,rules,resetHour,0,0);   // 0/0 = 全天，等价旧行为
    }
    /**
     * 带收束时段的账本推进：只累计落在 [winStart, winEnd) 里的时间。
     *
     * 时段外的时间照旧推进 lastAt 水位线 —— 不推进的话下次进时段会把之前的空闲时间
     * 当成在用，凭空多算一笔。所以「不计数」和「不推进」必须分开做。
     */
    void advance(long now,List<AppUsageQuota.Span> spans,boolean reliable,Map<String,Rule> rules,int resetHour,int winStart,int winEnd){
        long begin=DayBoundary.startMs(now,resetHour);
        long last=usage.optLong("lastAt",now);if(!DayBoundary.key(now,resetHour).equals(usage.optString("date"))){usage=fresh(now,resetHour);last=Math.max(last,begin);}
        long from=Math.max(last,begin);
        if(enabled()&&reliable&&usage.optString("error").isEmpty()&&now>from){Set<String> apps=packages();JSONObject targets=usage.optJSONObject("targets");List<long[]> windows=TimeWindow.segments(from,now,winStart,winEnd);for(AppUsageQuota.Span s:spans)if(apps.contains(s.pkg)){Rule rule=rules.get(s.pkg);Rule scope=rule==null||FreezeSlotStore.unassigned(rule)?null:rule;long elapsed=0;for(long[] w:windows){long a=Math.max(Math.max(from,s.start),w[0]),b=Math.min(Math.min(now,s.end),w[1]);if(b>a)elapsed+=AppUsageQuota.overlap(a,b,scope);}if(elapsed>0)put(targets,s.pkg,targets.optLong(s.pkg)+elapsed);}}
        if(enabled()&&!reliable)put(usage,"unavailable",true);else usage.remove("unavailable");
        put(usage,"lastAt",now);
    }
    static JSONObject fresh(long now){return fresh(now,DayBoundary.DEFAULT_HOUR);}
    static JSONObject fresh(long now,int resetHour){JSONObject out=new JSONObject();put(out,"date",DayBoundary.key(now,resetHour));put(out,"lastAt",now);put(out,"targets",new JSONObject());return out;}
    static void validateUsage(JSONObject usage){if(!usage.optString("date").matches("\\d{4}-\\d{2}-\\d{2}")||!(usage.opt("lastAt") instanceof Number)||usage.optLong("lastAt")<0||usage.optJSONObject("targets")==null)throw new IllegalArgumentException(T.s("用量数据无效"));Iterator<String> keys=usage.optJSONObject("targets").keys();while(keys.hasNext()){Object value=usage.optJSONObject("targets").opt(keys.next());if(!(value instanceof Number)||((Number)value).doubleValue()<0||!Double.isFinite(((Number)value).doubleValue())||((Number)value).doubleValue()>86400000L)throw new IllegalArgumentException(T.s("用量数据无效"));}}
    static void put(JSONObject o,String key,Object value){MemoryData.put(o,key,value);}
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
