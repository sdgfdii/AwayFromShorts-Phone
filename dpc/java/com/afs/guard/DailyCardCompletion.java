package com.afs.guard;
import android.content.Context;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.json.JSONObject;
/** Records actual daily requirements; unknown old dates must not be guessed from today's settings. */
final class DailyCardCompletion {
    private static final String SP="afs_daily_completion";
    /** 会记进 missing 的四种必做卡，顺序就是历史页展示顺序。 */
    static final String[] TYPES={"目标卡","总结卡","记忆卡","刷题卡"};
    static JSONObject days(Context context){try{return new JSONObject(context.getSharedPreferences(SP,0).getString("days","{}"));}catch(Exception e){return new JSONObject();}}
    static synchronized void capture(Context context){try{
        StringBuilder missing=new StringBuilder();int required=StudyStore.goals(context).size();
        if(StudyStore.pendingToday(context)>0)missing.append(T.s("目标卡、"));
        LearningLink.Progress memory=LearningLink.memory(context),quiz=LearningLink.quiz(context);
        if(memory.selected&&memory.target>0){required++;if(!memory.complete())missing.append(T.s("记忆卡、"));}
        if(quiz.selected&&quiz.target>0){required++;if(!quiz.complete())missing.append(T.s("刷题卡、"));}
        int summaries=CardStore.summariesPending(context,System.currentTimeMillis()).size();
        if(summaries>0){required+=summaries;missing.append(T.s("总结卡、"));}
        JSONObject days=days(context),entry=new JSONObject();entry.put("required",required);entry.put("missing",missing.length()==0?"":missing.substring(0,missing.length()-1));entry.put("at",System.currentTimeMillis());
        days.put(StudyStore.today(),entry);context.getSharedPreferences(SP,0).edit().putString("days",days.toString()).apply();
    }catch(RuntimeException|org.json.JSONException ignored){}}
    static boolean incomplete(Context context,String date){JSONObject entry=days(context).optJSONObject(date);return entry!=null&&!entry.optString("missing").isEmpty();}
    static String description(Context context,String date){return description(days(context).optJSONObject(date));}
    static String description(JSONObject entry){if(entry==null)return T.s("完成状态无法核实（缺少当日目标快照）");String missing=entry.optString("missing");return missing.isEmpty()?entry.optInt("required")==0?T.s("当天无必做卡片"):T.s("必做卡片已完成"):T.s("未完成：")+missing;}
    /** 每种卡片未完成的自然日，按日期升序。只认当天记下的快照，不回算历史。 */
    static Map<String,List<String>> missingDays(Context context){
        Map<String,List<String>> out=new LinkedHashMap<>();
        for(String type:TYPES)out.put(type,new ArrayList<String>());
        JSONObject all=days(context);TreeSet<String> dates=new TreeSet<>();Iterator<String> keys=all.keys();
        while(keys.hasNext())dates.add(keys.next());
        for(String date:dates){
            JSONObject entry=all.optJSONObject(date);
            if(entry==null)continue;
            String missing=entry.optString("missing");
            if(missing.isEmpty())continue;
            for(String part:missing.split("、")){
                List<String> list=out.get(part.trim());
                if(list!=null&&!list.contains(date))list.add(date);
            }
        }
        return out;
    }
    /** 有未完成卡片的自然日数（同一天多种卡只算一天）。 */
    static int incompleteDays(Context context){
        int count=0;JSONObject all=days(context);Iterator<String> keys=all.keys();
        while(keys.hasNext()){
            JSONObject entry=all.optJSONObject(keys.next());
            if(entry!=null&&!entry.optString("missing").isEmpty())count++;
        }
        return count;
    }
}
