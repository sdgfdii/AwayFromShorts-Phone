package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Notebook, paper and layout metadata only. Never writes notes or learning data. */
public final class MemoLibrary {
    private MemoLibrary() { }
    private static final String SP="afs_memo_library",KEY="state";
    public static final int MAX_BOOKS=100,MAX_LINKS=1000,MAX_NAME=60;
    public static final class Book {
        public final String id;
        public String name;
        public Book(String id,String name){this.id=id;this.name=name;}
    }
    public static final class Model {
        /** Custom notebooks only; the default notebook always has the empty id. */
        public final List<Book> notebooks=new ArrayList<>();
        public final Map<String,String> assignments=new LinkedHashMap<>();
        public final Map<String,String> papers=new LinkedHashMap<>();
        public boolean grid;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("笔记本数据无效，未导入");}
    private static String noteId(String value){
        if(value==null||value.isEmpty()||value.length()>64||!value.equals(value.trim())||hasControl(value))throw invalid();return value;
    }
    private static boolean hasControl(String value){for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return true;return false;}
    private static String name(String value){
        if(value==null)throw new IllegalArgumentException("请输入笔记本名称");String clean=value.trim();
        if(clean.isEmpty()||clean.length()>MAX_NAME||hasControl(clean))throw new IllegalArgumentException("笔记本名称须为 1 至 60 字，且不能包含换行");return clean;
    }
    private static boolean bookId(String id){
        if(id==null||id.length()!=36)return false;try{return UUID.fromString(id).toString().equalsIgnoreCase(id);}catch(IllegalArgumentException e){return false;}
    }
    private static Book find(Model model,String id){for(Book book:model.notebooks)if(book.id.equals(id))return book;return null;}
    private static void uniqueName(Model model,String clean,String except){
        if(clean.equals("默认笔记本"))throw new IllegalArgumentException("请使用其他笔记本名称");
        for(Book book:model.notebooks)if(!book.id.equals(except)&&book.name.equalsIgnoreCase(clean))throw new IllegalArgumentException("已存在同名笔记本");
    }
    public static boolean validPaper(String paper){return "default".equals(paper)||"cream".equals(paper)||"blue".equals(paper)||"green".equals(paper);}
    public static Book createNotebook(Model model,String value){
        String clean=name(value);uniqueName(model,clean,null);
        if(model.notebooks.size()>=MAX_BOOKS)throw new IllegalArgumentException("笔记本已达到 100 个上限");
        Book book=new Book(UUID.randomUUID().toString(),clean);model.notebooks.add(book);return book;
    }
    public static void renameNotebook(Model model,String id,String value){
        Book book=find(model,id);if(book==null)throw new IllegalArgumentException("默认笔记本不可改名，或笔记本已删除");
        String clean=name(value);uniqueName(model,clean,id);book.name=clean;
    }
    public static void deleteNotebook(Model model,String id){
        Book book=find(model,id);if(book==null)throw new IllegalArgumentException("默认笔记本不可删除，或笔记本已删除");
        model.notebooks.remove(book);
        java.util.Iterator<Map.Entry<String,String>> links=model.assignments.entrySet().iterator();
        while(links.hasNext())if(id.equals(links.next().getValue()))links.remove();
    }
    public static String notebookOf(Model model,String id){if(id==null||id.isEmpty())return "";String book=model.assignments.get(noteId(id));return book==null?"":book;}
    public static String paperOf(Model model,String id){if(id==null||id.isEmpty())return "default";String paper=model.papers.get(noteId(id));return paper==null?"default":paper;}
    public static void setNotebook(Model model,String id,String book){
        noteId(id);if(book==null||(!book.isEmpty()&&find(model,book)==null))throw new IllegalArgumentException("笔记本不存在");
        if(book.isEmpty()){model.assignments.remove(id);return;}
        if(!model.assignments.containsKey(id)&&model.assignments.size()>=MAX_LINKS)throw new IllegalArgumentException("笔记分类记录已满");
        model.assignments.put(id,book);
    }
    public static void setPaper(Model model,String id,String paper){
        noteId(id);if(!validPaper(paper))throw new IllegalArgumentException("不支持的纸张样式");
        if("default".equals(paper)){model.papers.remove(id);return;}
        if(!model.papers.containsKey(id)&&model.papers.size()>=MAX_LINKS)throw new IllegalArgumentException("纸张记录已满");
        model.papers.put(id,paper);
    }
    public static JSONObject toJson(Model model){
        try{
            JSONObject out=new JSONObject();JSONArray books=new JSONArray();
            for(Book book:model.notebooks)books.put(new JSONObject().put("id",book.id).put("name",book.name));
            JSONObject links=new JSONObject(),papers=new JSONObject();
            for(Map.Entry<String,String> entry:model.assignments.entrySet())links.put(entry.getKey(),entry.getValue());
            for(Map.Entry<String,String> entry:model.papers.entrySet())papers.put(entry.getKey(),entry.getValue());
            out.put("version",1).put("notebooks",books).put("assignments",links).put("papers",papers).put("grid",model.grid);return out;
        }catch(org.json.JSONException e){throw new IllegalArgumentException("无法编码笔记本数据",e);}
    }
    /** Strict parsing for backups: do not silently drop malformed entries or unknown book ids. */
    public static Model validate(JSONObject json){
        try{
            if(json==null||!(json.get("version") instanceof Number)||json.getDouble("version")!=1d||!(json.get("grid") instanceof Boolean))throw invalid();
            JSONArray books=json.getJSONArray("notebooks");JSONObject links=json.getJSONObject("assignments"),papers=json.getJSONObject("papers");
            if(books.length()>MAX_BOOKS||links.length()>MAX_LINKS||papers.length()>MAX_LINKS)throw invalid();
            Model model=new Model();HashSet<String> ids=new HashSet<>(),names=new HashSet<>();
            for(int i=0;i<books.length();i++){
                JSONObject item=books.getJSONObject(i);Object rawId=item.get("id"),rawName=item.get("name");
                if(!(rawId instanceof String)||!(rawName instanceof String))throw invalid();
                String id=(String)rawId,clean=name((String)rawName);
                if(!bookId(id)||!ids.add(id.toLowerCase(Locale.ROOT))||!names.add(clean.toLowerCase(Locale.ROOT))||clean.equals("默认笔记本"))throw invalid();
                model.notebooks.add(new Book(id,clean));
            }
            java.util.Iterator<String> keys=links.keys();while(keys.hasNext()){
                String id=keys.next();Object value=links.get(id);if(!(value instanceof String))throw invalid();setNotebook(model,id,(String)value);
            }
            keys=papers.keys();while(keys.hasNext()){
                String id=keys.next();Object value=papers.get(id);if(!(value instanceof String))throw invalid();setPaper(model,id,(String)value);
            }
            model.grid=json.getBoolean("grid");return model;
        }catch(org.json.JSONException e){throw new IllegalArgumentException("笔记本数据无效，未导入",e);}
    }
    private static SharedPreferences prefs(Context context){return context.getApplicationContext().getSharedPreferences(SP,Context.MODE_PRIVATE);}
    public static synchronized Model load(Context context){
        String raw=prefs(context).getString(KEY,null);if(raw==null)return new Model();
        try{return validate(new JSONObject(raw));}catch(org.json.JSONException e){throw new IllegalStateException("笔记本数据损坏，请恢复本地备份",e);}
    }
    private static void save(Context context,Model model){
        String encoded=toJson(model).toString();validate(toJson(model));SharedPreferences store=prefs(context);String old=store.getString(KEY,null);
        if(!store.edit().putString(KEY,encoded).commit()){
            SharedPreferences.Editor rollback=store.edit();if(old==null)rollback.remove(KEY);else rollback.putString(KEY,old);rollback.commit();
            throw new IllegalStateException("笔记本保存失败，请重试");
        }
    }
    public static synchronized Book createNotebook(Context context,String name){Model model=load(context);Book book=createNotebook(model,name);save(context,model);return new Book(book.id,book.name);}
    public static synchronized void renameNotebook(Context context,String id,String name){Model model=load(context);renameNotebook(model,id,name);save(context,model);}
    public static synchronized void deleteNotebook(Context context,String id){Model model=load(context);deleteNotebook(model,id);save(context,model);}
    public static synchronized String notebookOf(Context context,String id){return notebookOf(load(context),id);}
    public static synchronized String paperOf(Context context,String id){return paperOf(load(context),id);}
    public static synchronized void setNotebook(Context context,String id,String book){Model model=load(context);setNotebook(model,id,book);save(context,model);}
    public static synchronized void setPaper(Context context,String id,String paper){Model model=load(context);setPaper(model,id,paper);save(context,model);}
    public static synchronized boolean grid(Context context){return load(context).grid;}
    public static synchronized void setGrid(Context context,boolean grid){Model model=load(context);model.grid=grid;save(context,model);}
    public static synchronized JSONObject exportJson(Context context){return toJson(load(context));}
    public static synchronized void importJson(Context context,JSONObject json){Model valid=validate(json);save(context,valid);}
    /** Non-destructive restore. Local mappings/layout win; conflicting books receive new ids/names. */
    public static Model merge(Model local,Model imported){
        Model result=validate(toJson(local)),incoming=validate(toJson(imported));Map<String,String> bookMap=new LinkedHashMap<>();
        for(Book source:incoming.notebooks){
            Book existing=find(result,source.id);
            if(existing!=null&&existing.name.equalsIgnoreCase(source.name)){bookMap.put(source.id,existing.id);continue;}
            if(result.notebooks.size()>=MAX_BOOKS)throw new IllegalArgumentException("合并后笔记本超过 100 个，未导入");
            String id=source.id,clean=source.name;boolean nameConflict=false;
            for(Book candidate:result.notebooks)if(candidate.name.equalsIgnoreCase(clean)){nameConflict=true;break;}
            if(existing!=null||nameConflict)id=UUID.randomUUID().toString();
            if(nameConflict){
                int suffix=1;boolean free=false;
                while(!free){String end=suffix==1?"（导入）":"（导入 "+suffix+"）";clean=source.name.substring(0,Math.min(source.name.length(),MAX_NAME-end.length()))+end;
                    free=true;for(Book candidate:result.notebooks)if(candidate.name.equalsIgnoreCase(clean)){free=false;break;}suffix++;}
            }
            result.notebooks.add(new Book(id,clean));bookMap.put(source.id,id);
        }
        for(Map.Entry<String,String> link:incoming.assignments.entrySet())if(!result.assignments.containsKey(link.getKey()))setNotebook(result,link.getKey(),bookMap.get(link.getValue()));
        for(Map.Entry<String,String> paper:incoming.papers.entrySet())if(!result.papers.containsKey(paper.getKey()))setPaper(result,paper.getKey(),paper.getValue());
        return validate(toJson(result));
    }
    public static synchronized void mergeJson(Context context,JSONObject json){Model result=merge(load(context),validate(json));save(context,result);}
}
