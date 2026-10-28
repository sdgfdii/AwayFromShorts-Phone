package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import org.json.*;

/** Validated, non-destructive offline backup. Never reads tokens or unrelated preferences. */
public final class MemoBackup {
    private MemoBackup(){}
    public static final int MAX_BYTES=20*1024*1024;
    public static final class Data {
        public final List<MemoStore.Note> notes,trash;
        public final JSONObject library;
        /** Imported note id -> retained/new local id, for notebook metadata remapping. */
        public final Map<String,String> idMap;
        Data(List<MemoStore.Note> notes,List<MemoStore.Note> trash,JSONObject library,Map<String,String> ids){
            this.notes=notes;this.trash=trash;this.library=library;this.idMap=Collections.unmodifiableMap(ids);
        }
    }
    public static String encode(List<MemoStore.Note> notes,List<MemoStore.Note> trash,JSONObject library){
        try{
            validate(notes,trash);
            JSONObject value=new JSONObject().put("version",1).put("notes",new JSONArray(MemoStore.toJson(notes)))
                    .put("trash",new JSONArray(MemoStore.toJson(trash))).put("library",library==null?new JSONObject():library);
            String text=value.toString();decode(text);if(text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw invalid("备份正文超过 20 MB");return text;
        }catch(JSONException e){throw invalid("无法编码备份");}
    }
    public static Data decode(String text){
        if(text==null||text.length()>MAX_BYTES)throw invalid("备份文件过大或为空");
        try{
            JSONTokener tokener=new JSONTokener(text);Object value=tokener.nextValue();if(!(value instanceof JSONObject)||tokener.nextClean()!=0)throw invalid("备份 JSON 格式无效");
            JSONObject root=(JSONObject)value;if(integer(root,"version",false)!=1)throw invalid("不支持的备份版本");
            Set<String> seen=new HashSet<>();List<MemoStore.Note> notes=parse(root.getJSONArray("notes"),seen),trash=parse(root.getJSONArray("trash"),seen);
            JSONObject library=root.getJSONObject("library");return new Data(notes,trash,new JSONObject(library.toString()),new LinkedHashMap<String,String>());
        }catch(JSONException e){throw invalid("备份格式无效，原有内容未更改");}
    }
    public static Data read(InputStream source)throws IOException{
        if(source==null)throw new IOException("无法读取备份");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=source.read(buffer))!=-1){if(bytes.size()+count>MAX_BYTES)throw new IOException("备份正文超过 20 MB");bytes.write(buffer,0,count);}
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
            return decode(text.startsWith("\uFEFF")?text.substring(1):text);
        }catch(CharacterCodingException e){throw new IOException("备份必须是有效的 UTF-8",e);}
    }
    private static List<MemoStore.Note> parse(JSONArray rows,Set<String> seen)throws JSONException{
        if(rows.length()>MemoStore.MAX_NOTES)throw invalid("备忘录数量超过容量");List<MemoStore.Note> result=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String id=string(row,"id",64),title=string(row,"title",MemoStore.MAX_TITLE),body=string(row,"body",MemoStore.MAX_BODY);
            if(id.isEmpty()||!id.equals(id.trim())||id.matches("(?s).*\\p{Cntrl}.*")||!seen.add(id))throw invalid("存在无效或重复的备忘录编号");
            if(MemoStore.blank(title,body))throw invalid("备忘录内容为空");Object pinned=row.get("pinned");if(!(pinned instanceof Boolean))throw invalid("置顶字段无效");
            MemoStore.Note note=new MemoStore.Note(id,title,body,integer(row,"updated",false),(Boolean)pinned);
            note.remindAt=integer(row,"remindAt",true);note.remindedAt=integer(row,"remindedAt",true);result.add(note);
        }return result;
    }
    private static String string(JSONObject row,String field,int max)throws JSONException{
        Object value=row.get(field);if(!(value instanceof String)||((String)value).length()>max)throw invalid(field+" 字段无效或超长");return (String)value;
    }
    private static long integer(JSONObject row,String field,boolean optional)throws JSONException{
        if(optional&&!row.has(field))return 0;Object value=row.get(field);
        if(!(value instanceof Integer)&&!(value instanceof Long))throw invalid(field+" 必须是整数");long number=((Number)value).longValue();
        if(number<0)throw invalid(field+" 不能为负数");return number;
    }
    private static void validate(List<MemoStore.Note> notes,List<MemoStore.Note> trash){
        if(notes==null||trash==null)throw invalid("备忘录列表无效");
        for(List<MemoStore.Note> list:Arrays.asList(notes,trash))for(MemoStore.Note note:list)
            if(note==null||note.id==null||note.title==null||note.body==null)throw invalid("备忘录字段无效");
        try{Set<String> seen=new HashSet<>();parse(new JSONArray(MemoStore.toJson(notes)),seen);parse(new JSONArray(MemoStore.toJson(trash)),seen);}
        catch(JSONException e){throw invalid("现有备忘录数据无效");}
    }
    public static Data merge(List<MemoStore.Note> active,List<MemoStore.Note> deleted,Data incoming){
        validate(active,deleted);validate(incoming.notes,incoming.trash);
        List<MemoStore.Note> notes=copy(active),trash=copy(deleted);Map<String,MemoStore.Note> existing=new HashMap<>();
        for(MemoStore.Note note:notes)existing.put(note.id,note);for(MemoStore.Note note:trash)existing.put(note.id,note);
        Map<String,String> mapping=new LinkedHashMap<>();
        mergeInto(notes,incoming.notes,existing,mapping);mergeInto(trash,incoming.trash,existing,mapping);
        if(notes.size()>MemoStore.MAX_NOTES||trash.size()>MemoStore.MAX_NOTES)throw invalid("合并后超过容量，未导入任何内容");
        try{return new Data(notes,trash,new JSONObject(incoming.library.toString()),mapping);}catch(JSONException e){throw invalid("分类信息无效");}
    }
    private static void mergeInto(List<MemoStore.Note> target,List<MemoStore.Note> source,Map<String,MemoStore.Note> existing,Map<String,String> mapping){
        for(MemoStore.Note raw:source){MemoStore.Note old=existing.get(raw.id);if(old!=null&&same(old,raw)){mapping.put(raw.id,old.id);continue;}
            MemoStore.Note note=raw.copy();if(old!=null){do{note.id=UUID.randomUUID().toString();}while(existing.containsKey(note.id));}
            mapping.put(raw.id,note.id);target.add(note);existing.put(note.id,note);
        }
    }
    private static boolean same(MemoStore.Note a,MemoStore.Note b){return a.title.equals(b.title)&&a.body.equals(b.body)&&a.updated==b.updated&&a.pinned==b.pinned&&a.remindAt==b.remindAt&&a.remindedAt==b.remindedAt;}
    private static List<MemoStore.Note> copy(List<MemoStore.Note> notes){List<MemoStore.Note> result=new ArrayList<>();for(MemoStore.Note note:notes)result.add(note.copy());return result;}
    /** Returns merged records and id mapping; caller merges library metadata separately. */
    public static Data restore(Context context,Data incoming){
        synchronized(MemoStore.class){
            Data merged=merge(MemoStore.all(context),MemoStore.trash(context),incoming);
            SharedPreferences prefs=context.getSharedPreferences("afs_guard",0);String old=prefs.getString("memos_v1","[]"),oldTrash=prefs.getString("memos_trash_v1","[]");
            if(!prefs.edit().putString("memos_v1",MemoStore.toJson(merged.notes)).putString("memos_trash_v1",MemoStore.toJson(merged.trash)).commit()){
                prefs.edit().putString("memos_v1",old).putString("memos_trash_v1",oldTrash).commit();throw invalid("恢复未保存，请重试");
            }
            MemoReminder.reschedule(context);return merged;
        }
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
