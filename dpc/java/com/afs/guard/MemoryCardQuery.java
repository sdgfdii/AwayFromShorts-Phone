package com.afs.guard;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
/** Pure derived card browsing: no learning, plan generation or persistent flags. */
public final class MemoryCardQuery {
    private MemoryCardQuery(){}
    public enum Filter { ALL, TODAY, DIFFICULT, WRONG }
    public static List<String> page(MemoryData m,Filter filter,Set<String> decks,long now,int offset,int limit) {
        return page(m,filter,decks,now,offset,limit,"");
    }
    public static List<String> page(MemoryData m,Filter filter,Set<String> decks,long now,int offset,int limit,String query) {
        if(offset<0 || limit<1 || limit>1000)throw new IllegalArgumentException("Invalid page range");
        List<JSONObject> cards=select(m,filter,decks,now,query);
        Collections.sort(cards,(a,b)-> {
            int compared=Integer.compare(b.optInt("lapses"),a.optInt("lapses"));
            if(compared==0)compared=Long.compare(a.optLong("due"),b.optLong("due"));
            return compared==0?a.optString("id").compareTo(b.optString("id")):compared;
        });
        List<String> out=new ArrayList<>();long end=Math.min((long)cards.size(),(long)offset+limit);
        for(int i=offset;i<end;i++)out.add(cards.get(i).optString("id"));
        return out;
    }
    public static int count(MemoryData m,Filter filter,Set<String> decks,long now,String query) {
        return select(m,filter,decks,now,query).size();
    }
    public static int count(MemoryData m,Filter filter,Set<String> decks,long now) {
        return count(m,filter,decks,now,"");
    }
    private static List<JSONObject> select(MemoryData m,Filter filter,Set<String> decks,long now,String query) {
        if(filter==null)throw new IllegalArgumentException("Missing card filter");
        Set<String> active=new HashSet<>();JSONArray subjects=m.activeDecks();
        for(int i=0;i<subjects.length();i++) {String id=subjects.optJSONObject(i).optString("id");if(decks==null || decks.isEmpty() || decks.contains(id))active.add(id);}
        Set<String> today=new HashSet<>();JSONObject flash=m.db.optJSONObject("flashcards");
        JSONObject session=flash==null?null:flash.optJSONObject("session");
        if(session!=null && MemoryData.date(now).equals(session.optString("date"))) {
            JSONArray ids=session.optJSONArray("ids");if(ids!=null)for(int i=0;i<ids.length();i++)today.add(ids.optString(i));
        }
        String needle=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        List<JSONObject> result=new ArrayList<>();JSONArray cards=m.cards();
        for(int i=0;i<cards.length();i++) {
            JSONObject c=cards.optJSONObject(i);if(c==null || !active.contains(c.optString("deck")))continue;
            if(filter==Filter.TODAY && !today.contains(c.optString("id")))continue;
            if(filter==Filter.DIFFICULT && c.optInt("lapses")<3)continue;
            if(filter==Filter.WRONG && c.optInt("lapses")<1)continue;
            if(!needle.isEmpty() && !matches(c,needle))continue;
            result.add(c);
        }
        return result;
    }
    private static boolean matches(JSONObject c,String query) {
        for(String key:new String[]{"front","answer","source","note"})if(c.optString(key).toLowerCase(Locale.ROOT).contains(query))return true;
        return false;
    }
}
