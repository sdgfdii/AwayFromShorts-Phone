package com.afs.guard;

import org.json.*;
import java.util.*;

/** Pure reward ledger. One earned love card per day, at most 60 minutes, never expiring. */
public final class CardData {
    public static final int MAX_REWARD_MINUTES=60;
    public final JSONObject db;
    public CardData(long now){db=new JSONObject();put(db,"version",1);put(db,"love",new JSONArray());put(db,"earned",new JSONArray());put(db,"uses",new JSONArray());put(db,"summary",new JSONObject());summary(now);}
    public CardData(String raw){try{db=new JSONObject(raw);if(db.optInt("version")!=1||db.optJSONArray("love")==null||db.optJSONArray("earned")==null||db.optJSONArray("uses")==null||db.optJSONObject("summary")==null)throw new Exception();validate();}catch(Exception e){throw new IllegalArgumentException("卡管理数据无法读取，原文件已保留",e);}}
    private void validate(){
        Set<String> ids=new HashSet<>(),days=new HashSet<>();
        for(int i=0;i<love().length();i++){JSONObject c=love().optJSONObject(i);if(c==null||c.optString("id").isEmpty()||!ids.add(c.optString("id"))||c.optInt("minutes")<1||c.optInt("minutes")>60||c.optString("title").trim().isEmpty())throw new IllegalArgumentException("热爱卡配置无效");}
        for(int i=0;i<earned().length();i++){JSONObject e=earned().optJSONObject(i);if(e==null||e.optInt("minutes")<1||e.optInt("minutes")>60||!e.optString("day").matches("\\d{4}-\\d{2}-\\d{2}")||!days.add(e.optString("day")))throw new IllegalArgumentException("热爱奖励记录违反每日上限");}
        JSONArray uses=db.optJSONArray("uses");for(int i=0;i<uses.length();i++){JSONObject use=uses.optJSONObject(i);if(use==null)throw new IllegalArgumentException("奖励抵扣记录无效");long duration=use.optLong("end")-use.optLong("start"),refund=use.optLong("refund");if(duration<=0||duration>60*60000L||refund<0||refund>duration)throw new IllegalArgumentException("奖励抵扣记录无效");}
        if(!db.optJSONObject("summary").has("mode"))throw new IllegalArgumentException("总结配置无效");summary(System.currentTimeMillis());
    }
    public CycleSummary summary(long now){return new CycleSummary(db.optJSONObject("summary"),now);}
    public JSONArray love(){return db.optJSONArray("love");}
    public JSONObject find(String id){for(int i=0;i<love().length();i++){JSONObject card=love().optJSONObject(i);if(id.equals(card.optString("id")))return card;}return null;}
    public String saveLove(String id,String title,int reward) {
        title=title==null?"":title.trim();if(title.isEmpty()||title.length()>80)throw new IllegalArgumentException("热爱名称须为 1–80 字");
        if(reward<1||reward>MAX_REWARD_MINUTES)throw new IllegalArgumentException("每张热爱卡的奖励只能为 1–60 分钟");
        JSONObject card=id==null?null:find(id);if(id!=null&&card==null)throw new IllegalArgumentException("热爱卡不存在");
        if(card==null){card=new JSONObject();id=UUID.randomUUID().toString();put(card,"id",id);love().put(card);}
        put(card,"title",title);put(card,"minutes",reward);put(card,"archived",false);return id;
    }
    public void archive(String id,boolean archived){JSONObject card=find(id);if(card==null)throw new IllegalArgumentException("热爱卡不存在");put(card,"archived",archived);}
    public boolean earnedToday(long now){String day=CycleSummary.day(now);JSONArray a=db.optJSONArray("earned");for(int i=0;i<a.length();i++)if(day.equals(a.optJSONObject(i).optString("day")))return true;return false;}
    public boolean claimed(String id,long now){String day=CycleSummary.day(now);JSONArray a=earned();for(int i=0;i<a.length();i++){JSONObject e=a.optJSONObject(i);if(day.equals(e.optString("day"))&&id.equals(e.optString("card")))return true;}return false;}
    public boolean claim(String id,long now){return claim(id,now,new java.security.SecureRandom().nextInt(60)+1);}
    boolean claim(String id,long now,int draw){JSONObject card=find(id);if(card==null||card.optBoolean("archived"))throw new IllegalArgumentException("热爱卡不存在");if(earnedToday(now))return false;
        int minutes=draw;if(minutes<1||minutes>60)throw new IllegalArgumentException("奖励分钟数无效");
        JSONObject entry=new JSONObject();put(entry,"id",UUID.randomUUID().toString());put(entry,"card",id);put(entry,"title",card.optString("title"));put(entry,"day",CycleSummary.day(now));put(entry,"at",now);put(entry,"minutes",minutes);db.optJSONArray("earned").put(entry);return true;}
    public long balanceMillis(){long balance=0;JSONArray earned=db.optJSONArray("earned"),uses=db.optJSONArray("uses");for(int i=0;i<earned.length();i++)balance+=earned.optJSONObject(i).optInt("minutes")*60000L;for(int i=0;i<uses.length();i++){JSONObject u=uses.optJSONObject(i);balance-=u.optLong("end")-u.optLong("start")-u.optLong("refund");}return Math.max(0,balance);}
    private JSONObject lastUse(){JSONArray uses=db.optJSONArray("uses");return uses.length()==0?null:uses.optJSONObject(uses.length()-1);}
    public long rewardUntil(long now){JSONObject use=lastUse();return use!=null&&!use.optBoolean("closed")&&now>=use.optLong("start")&&now<use.optLong("end")?use.optLong("end"):0;}
    /** Reserve only the next known scheduled interval; return unused time when eligibility disappears. */
    public boolean reconcile(long now,boolean eligible,long nextBoundary){
        JSONObject use=lastUse();boolean changed=false;
        if(use!=null&&!use.optBoolean("closed")&&now<use.optLong("end")){
            long start=use.optLong("start"),end=use.optLong("end");
            if(!eligible||now<start){put(use,"refund",use.optLong("refund")+end-Math.max(start,now));put(use,"closed",true);changed=true;}
            else if(nextBoundary<end&&nextBoundary>now){put(use,"end",nextBoundary);changed=true;}
            else return false;
        }
        if(!eligible||rewardUntil(now)>0)return changed;
        long amount=Math.min(balanceMillis(),Math.min(60L*60000L,Math.max(0,nextBoundary-now)));if(amount<=0)return changed;
        JSONObject next=new JSONObject();put(next,"start",now);put(next,"end",now+amount);put(next,"refund",0);db.optJSONArray("uses").put(next);return true;
    }
    public JSONArray earned(){return db.optJSONArray("earned");}
    private static void put(JSONObject o,String key,Object value){MemoryData.put(o,key,value);}
}
