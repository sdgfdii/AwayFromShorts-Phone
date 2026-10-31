package com.afs.guard;

import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Calendar-based periods. An overdue cursor is retained until explicitly completed. */
public final class CycleSummary {
    private final JSONObject data;
    private final String id;
    private final List<CycleSummary> extras=new ArrayList<>();
    public static final class Period {
        public String key, start, end, through, note="", cycleLabel="", schedule="";
        public long visibleAt, dueAt, endAt, completedAt;
        public boolean complete;
        public boolean holds(long now){return !complete && now>=dueAt;}
    }
    CycleSummary(JSONObject data,long now) {
        this(data,now,"");
    }
    private CycleSummary(JSONObject data,long now,String id) {
        this.data=data;
        this.id=id;
        if(!data.has("mode")) {
            put(data,"mode","weekly");put(data,"days",7);put(data,"anchor",day(now));
            put(data,"cursor",currentStart(now));put(data,"records",new JSONObject());
        }
        if(!Arrays.asList("daily","weekly","monthly","custom").contains(mode())||days()<1||days()>365||data.optJSONObject("records")==null)throw new IllegalArgumentException(T.s("总结配置无效"));
        parse(anchor());parse(data.optString("cursor"));
        if(id.isEmpty()){
            JSONObject more=data.optJSONObject("extraCycles");
            if(data.has("extraCycles")&&more==null)throw new IllegalArgumentException(T.s("总结配置无效"));
            if(more!=null)for(String mode:Arrays.asList("daily","weekly","monthly","custom"))if(more.has(mode)){
                CycleSummary child=new CycleSummary(more.optJSONObject(mode),now,mode);
                if(!mode.equals(child.mode()))throw new IllegalArgumentException(T.s("总结周期类型不一致"));
                extras.add(child);
            }
        }
    }
    public JSONObject queued(){return data.optJSONObject("nextConfig");}
    public String mode(){return data.optString("mode","weekly");}
    public int days(){return data.optInt("days",7);}
    public String anchor(){return data.optString("anchor");}
    public String label(){return "daily".equals(mode())?T.s("每天"):"monthly".equals(mode())?T.s("每月"):"custom".equals(mode())?T.s("每 ")+days()+T.s(" 天"):T.s("每周");}
    public boolean enabled(){return data.optBoolean("enabled",true);}
    private List<CycleSummary> plans(){List<CycleSummary> all=new ArrayList<>();all.add(this);all.addAll(extras);return all;}
    public boolean enabled(String mode){for(CycleSummary plan:plans())if(plan.mode().equals(mode)&&plan.enabled())return true;return false;}
    public CycleSummary settings(String mode){for(CycleSummary plan:plans())if(plan.mode().equals(mode))return plan;return this;}
    public String activeLabels(){StringBuilder out=new StringBuilder();for(CycleSummary plan:plans())if(plan.enabled()){if(out.length()>0)out.append(T.s("、"));out.append(plan.label());}return out.length()==0?T.s("未启用"):out.toString();}
    public List<Period> currentPeriods(long now){List<Period> out=new ArrayList<>();for(CycleSummary plan:plans())if(plan.enabled()||plan.ownPending(now)!=null){Period p=plan.ownPending(now);out.add(p==null?plan.period(plan.currentStart(now)):p);}return out;}
    public List<Period> allPending(long now){List<Period> out=new ArrayList<>();for(CycleSummary plan:plans()){Period p=plan.ownPending(now);if(p!=null)out.add(p);}Collections.sort(out,(a,b)->Long.compare(a.dueAt,b.dueAt));return out;}
    public Period current(long now){return period(currentStart(now));}
    private Period ownPending(long now){Period p=period(data.optString("cursor"));return now>=p.visibleAt&&!p.complete&&(enabled()||p.visibleAt<=data.optLong("disabledAt",-1))?p:null;}
    public Period pending(long now){List<Period> all=allPending(now);return all.isEmpty()?null:all.get(0);}
    public Period prior(Period p){CycleSummary owner=owner(p.schedule);if(owner!=this)return owner.prior(p);Calendar c=parse(p.start);if("monthly".equals(mode()))c.add(Calendar.MONTH,-1);else c.add(Calendar.DAY_OF_MONTH,-length());return period(day(c.getTimeInMillis()));}
    private CycleSummary owner(String schedule){if(id.equals(schedule))return this;for(CycleSummary child:extras)if(child.id.equals(schedule))return child;throw new IllegalArgumentException(T.s("总结周期不存在"));}
    private CycleSummary keyOwner(String key){int split=key.indexOf('|');return split<0?this:owner(key.substring(0,split));}
    public void configureAll(Set<String> selected,int days,String anchor,long now){
        if(!id.isEmpty())throw new IllegalStateException(T.s("请从总结卡管理设置周期"));
        if(selected.isEmpty()||!Arrays.asList("daily","weekly","monthly","custom").containsAll(selected))throw new IllegalArgumentException(T.s("请至少选择一个总结周期"));
        if(selected.contains("custom")){if(days<1||days>365)throw new IllegalArgumentException(T.s("周期天数须为 1–365"));parse(anchor);}
        JSONObject more=data.optJSONObject("extraCycles");if(more==null){more=new JSONObject();put(data,"extraCycles",more);}
        for(CycleSummary plan:plans())plan.data.remove("nextConfig");
        for(String mode:Arrays.asList("daily","weekly","monthly","custom")){
            CycleSummary plan=null;for(CycleSummary candidate:plans())if(candidate.mode().equals(mode)){plan=candidate;break;}
            if(plan==null&&selected.contains(mode)){
                JSONObject fresh=new JSONObject();plan=new CycleSummary(fresh,now,mode);put(fresh,"mode",mode);put(fresh,"days","custom".equals(mode)?days:7);put(fresh,"anchor","custom".equals(mode)?anchor:day(now));put(fresh,"cursor",plan.currentStart(now));extras.add(plan);put(more,mode,fresh);
            }
            if(plan==null)continue;
            boolean enable=selected.contains(mode);
            if(enable&&!plan.enabled()){
                if(plan.ownPending(now)==null){put(plan.data,"cursor",plan.currentStart(now));plan.advanceCompleted();}
                put(plan.data,"enabled",true);plan.data.remove("disabledAt");
            }else if(!enable&&plan.enabled()){put(plan.data,"disabledAt",now);put(plan.data,"enabled",false);}
            if(enable&&"custom".equals(mode))plan.configure(mode,days,anchor,now);
        }
    }
    public Period recorded(String key) {
        CycleSummary owner=keyOwner(key);if(owner!=this)return owner.recorded(key);
        JSONObject r=data.optJSONObject("records").optJSONObject(key);if(r==null)return null;
        Period p=new Period();p.key=key;p.schedule=id;p.cycleLabel=label();p.start=r.optString("start");p.end=r.optString("end");
        fillDates(p);p.complete=true;p.note=r.optString("note");p.completedAt=r.optLong("at");return p;
    }
    public JSONArray records(){JSONArray out=new JSONArray();Iterator<String> it=data.optJSONObject("records").keys();while(it.hasNext())out.put(data.optJSONObject("records").optJSONObject(it.next()));for(CycleSummary child:extras){JSONArray rows=child.records();for(int i=0;i<rows.length();i++)out.put(rows.optJSONObject(i));}return out;}
    public void configure(String mode,int days,String anchor,long now) {
        if(!Arrays.asList("daily","weekly","monthly","custom").contains(mode)||days<1||days>365)throw new IllegalArgumentException(T.s("周期天数须为 1–365"));
        parse(anchor);
        if(mode.equals(mode())&&days==days()&&anchor.equals(anchor()))return;
        if(ownPending(now)!=null){JSONObject queued=new JSONObject();put(queued,"mode",mode);put(queued,"days",days);put(queued,"anchor",anchor);put(data,"nextConfig",queued);return;}
        data.remove("nextConfig");
        put(data,"mode",mode);put(data,"days",days);put(data,"anchor",anchor);put(data,"cursor",currentStart(now));advanceCompleted();
    }
    public boolean complete(String key,String note,long now) {
        CycleSummary owner=keyOwner(key);if(owner!=this)return owner.complete(key,note,now);
        if(data.optJSONObject("records").has(key))return false;
        Period pending=ownPending(now);
        if(pending==null||!pending.key.equals(key))throw new IllegalArgumentException(T.s("总结周期已变化，请重新打开待办总结"));
        if(note==null||note.length()>20000)throw new IllegalArgumentException(T.s("总结内容最多 20000 字"));
        JSONObject r=new JSONObject();put(r,"key",key);put(r,"start",pending.start);put(r,"end",pending.end);
        put(r,"note",note);put(r,"at",now);put(r,"label",label());put(data.optJSONObject("records"),key,r);
        put(data,"cursor",pending.end);advanceCompleted();
        JSONObject queued=data.optJSONObject("nextConfig");if(queued!=null&&ownPending(now)==null)configure(queued.optString("mode"),queued.optInt("days"),queued.optString("anchor"),now);
        return true;
    }
    void migrateWeek(String monday) {
        Period p=period(data.optString("cursor"));if(!p.start.equals(monday)||p.complete)return;
        JSONObject r=new JSONObject();put(r,"key",p.key);put(r,"start",p.start);put(r,"end",p.end);put(r,"note","");put(r,"at",0);put(r,"migrated",true);
        put(data.optJSONObject("records"),p.key,r);put(data,"cursor",p.end);
    }
    private void advanceCompleted(){for(int i=0;i<100000;i++){Period p=period(data.optString("cursor"));if(!p.complete)return;put(data,"cursor",p.end);}throw new IllegalStateException(T.s("总结周期数据异常"));}
    private int length(){return "daily".equals(mode())?1:"weekly".equals(mode())?7:days();}
    private String currentStart(long now) {
        Calendar c=parse(day(now));
        if("weekly".equals(mode()))c.add(Calendar.DAY_OF_MONTH,-((c.get(Calendar.DAY_OF_WEEK)+5)%7));
        else if("monthly".equals(mode()))c.set(Calendar.DAY_OF_MONTH,1);
        else if("custom".equals(mode())) {
            Calendar a=parse(anchor());long diff=ordinal(c)-ordinal(a);long index=Math.max(0,Math.floorDiv(diff,days()));
            a.add(Calendar.DAY_OF_MONTH,(int)(index*days()));c=a;
        }
        return day(c.getTimeInMillis());
    }
    private Period period(String start) {
        Calendar end=parse(start);if("monthly".equals(mode()))end.add(Calendar.MONTH,1);else end.add(Calendar.DAY_OF_MONTH,length());
        Period p=new Period();p.start=start;p.end=day(end.getTimeInMillis());p.schedule=id;p.cycleLabel=label();p.key=(id.isEmpty()?"":id+"|")+p.start+"/"+p.end;fillDates(p);
        JSONObject r=data.optJSONObject("records").optJSONObject(p.key);
        if(r!=null){p.complete=true;p.note=r.optString("note");p.completedAt=r.optLong("at");}return p;
    }
    private static void fillDates(Period p){Calendar end=parse(p.end);p.endAt=end.getTimeInMillis();end.add(Calendar.DAY_OF_MONTH,-1);p.through=day(end.getTimeInMillis());p.visibleAt=end.getTimeInMillis();end.set(Calendar.HOUR_OF_DAY,20);p.dueAt=end.getTimeInMillis();}
    private static long ordinal(Calendar c){Calendar u=Calendar.getInstance(TimeZone.getTimeZone("UTC"));u.clear();u.set(c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH));return u.getTimeInMillis()/86400000L;}
    public static String day(long now){return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(now));}
    private static Calendar parse(String day) {
        try {if(day==null||!day.matches("\\d{4}-\\d{2}-\\d{2}"))throw new Exception();SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setLenient(false);Calendar c=Calendar.getInstance();c.setTime(f.parse(day));return c;}
        catch(Exception e){throw new IllegalArgumentException(T.s("日期须为有效的 yyyy-MM-dd"));}
    }
    private static void put(JSONObject o,String key,Object value){MemoryData.put(o,key,value);}
}
