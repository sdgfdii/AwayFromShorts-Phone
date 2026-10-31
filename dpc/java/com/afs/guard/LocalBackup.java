package com.afs.guard;
import android.content.*;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import org.json.*;
final class LocalBackup {
    private static final String[] PREFS={"afs_guard","afs_study_span","afs_memo_library","afs_daily_quota"};
    static boolean key(String p,String k){return p.equals("afs_daily_quota")&&Arrays.asList("config","usage").contains(k)||p.equals("afs_memo_library")&&k.equals("state")||p.equals("afs_study_span")&&(k.equals("start")||k.equals("end")||k.equals("imported"))||p.equals("afs_guard")&&Arrays.asList("rules","freeze_slots","memos_v1","memos_trash_v1","toolbox_items").contains(k);}
    static boolean mayRestore(Context c){return !Guard.isOwner(c)||Config.inGrace(c);}
    private static File pending(Context c){return new File(c.getFilesDir(),"local-restore-pending.zip");}
    private static File journal(Context c){return new File(c.getFilesDir(),"local-restore-rollback.zip");}
    private static JSONObject preferences(Context c)throws Exception{
        JSONObject all=new JSONObject();for(String name:PREFS){JSONObject values=new JSONObject();for(Map.Entry<String,?> e:c.getSharedPreferences(name,0).getAll().entrySet())if(key(name,e.getKey())){Object v=e.getValue();String t=v instanceof Boolean?"boolean":v instanceof Integer?"int":v instanceof Long?"long":v instanceof Float?"float":v instanceof Set?"set":"string";if(v instanceof Set)v=new JSONArray((Set<?>)v);values.put(e.getKey(),new JSONObject().put("type",t).put("value",v));}all.put(name,values);}return all;
    }
    private static void collect(File root,File f,Map<String,byte[]> map)throws Exception{
        if(!new File(f.getParentFile().getCanonicalFile(),f.getName()).getPath().equals(f.getCanonicalPath()))throw new IOException(T.s("不支持符号链接"));
        if(f.isDirectory()){File[] list=f.listFiles();if(list!=null)for(File child:list)collect(root,child,map);return;}
        String path="files/"+f.getAbsolutePath().substring(root.getAbsolutePath().length()+1).replace(File.separatorChar,'/');if(!LocalArchive.allowed(path))return;
        long used=0;for(byte[] b:map.values())used+=b.length;try(InputStream in=new AtomicFile(f).openRead()){map.put(path,LocalArchive.read(in,(int)Math.min(LocalArchive.ENTRY,LocalArchive.LIMIT-used)));}
        long size=0;for(byte[] b:map.values())size+=b.length;if(size>LocalArchive.LIMIT)throw new IOException(T.s("完整备份超过当前设备的安全大小上限，请使用各模块的备份工具"));
    }
    static Map<String,byte[]> snapshot(Context c)throws Exception{Map<String,byte[]> map=new TreeMap<>();map.put("preferences.json",preferences(c).toString().getBytes("UTF-8"));File root=c.getFilesDir();for(String name:new String[]{"study.json","memory-v1.json","quiz-v1.json","cards-v1.json","backdrop.jpg"}){File f=new File(root,name);if(f.exists()||new File(f+".bak").exists())collect(root,f,map);}for(String name:new String[]{"memory-media","memo-attachments"}){File f=new File(root,name);if(f.isDirectory())collect(root,f,map);}return map;}
    static void export(Context c,OutputStream out)throws Exception{Map<String,byte[]> map=snapshot(c);validate(map);LocalArchive.write(map,out);}
    private static void validate(Map<String,byte[]> data)throws Exception{
        JSONObject p=new JSONObject(new String(data.get("preferences.json"),"UTF-8"));Iterator<String> names=p.keys();while(names.hasNext()){String n=names.next();if(!Arrays.asList(PREFS).contains(n))throw new IOException(T.s("不允许的偏好设置"));JSONObject v=p.getJSONObject(n);Iterator<String> keys=v.keys();while(keys.hasNext()){String k=keys.next();if(!key(n,k))throw new IOException(T.s("备份包含受保护设置"));JSONObject x=v.getJSONObject(k);String t=x.getString("type");if(!t.equals(n.equals("afs_study_span")&&k.equals("imported")?"boolean":"string"))throw new IOException(T.s("设置类型不符"));if(!Arrays.asList("string","boolean","int","long","float","set").contains(t))throw new IOException(T.s("未知设置类型"));Object value=x.get("value");if(t.equals("string")&&!(value instanceof String)||t.equals("boolean")&&!(value instanceof Boolean)||Arrays.asList("int","long","float").contains(t)&&!(value instanceof Number)||t.equals("set")&&!(value instanceof JSONArray))throw new IOException(T.s("设置类型不符"));}}
        for(Map.Entry<String,byte[]> e:data.entrySet()){String n=e.getKey();if(n.endsWith(".json")&&!n.equals("preferences.json")){String s=new String(e.getValue(),"UTF-8");if(n.equals("files/memory-v1.json"))new MemoryData(s);else if(n.equals("files/quiz-v1.json"))new QuizData(s);else if(n.equals("files/cards-v1.json"))new CardData(s);else if(n.equals("files/study.json")){JSONObject study=new JSONObject(s);study.getJSONArray("goals");study.getJSONArray("recs");}else new JSONObject(s);}}
    }
    static String inspect(InputStream in)throws Exception{Map<String,byte[]> data=LocalArchive.decode(in);validate(data);long bytes=0;for(byte[] b:data.values())bytes+=b.length;return T.s("文件数：")+(data.size()-1)+T.s("\n数据大小：")+(bytes/1024)+T.s(" KB\n包含本机学习数据、附件与所选设置；不包含云端凭据。");}
    private static void atomic(File file,byte[] bytes)throws Exception{AtomicFile a=new AtomicFile(file);FileOutputStream out=null;try{File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException(T.s("无法创建目录"));out=a.startWrite();out.write(bytes);a.finishWrite(out);}catch(Exception e){if(out!=null)a.failWrite(out);throw e;}}
    static void queue(Context c,InputStream in)throws Exception{if(!mayRestore(c))throw new IOException(T.s("完整恢复仅限未启用设备管理或首次配置宽限期，以防用旧备份绕过冻结"));byte[] bytes=LocalArchive.read(in,LocalArchive.LIMIT+4*1024*1024);Map<String,byte[]> data=LocalArchive.decode(new ByteArrayInputStream(bytes));validate(data);atomic(pending(c),bytes);}
    private static void paths(File root,File f,Set<String> names)throws Exception{
        if(!new File(f.getParentFile().getCanonicalFile(),f.getName()).getPath().equals(f.getCanonicalPath()))throw new IOException(T.s("不支持符号链接"));
        if(f.isDirectory()){File[] list=f.listFiles();if(list!=null)for(File child:list)paths(root,child,names);return;}
        String p="files/"+f.getAbsolutePath().substring(root.getAbsolutePath().length()+1).replace(File.separatorChar,'/');
        if(LocalArchive.allowed(p))names.add(p);else if(p.endsWith(".bak")&&LocalArchive.allowed(p.substring(0,p.length()-4)))names.add(p.substring(0,p.length()-4));
    }
    private static void apply(Context c,Map<String,byte[]> data)throws Exception{
        validate(data);Set<String> names=new HashSet<>();File root=c.getFilesDir();File[] children=root.listFiles();if(children!=null)for(File f:children)if(LocalArchive.allowed("files/"+f.getName())||f.getName().endsWith(".bak")||f.getName().equals("memory-media")||f.getName().equals("memo-attachments"))paths(root,f,names);names.addAll(data.keySet());
        for(String name:names)if(name.startsWith("files/")){File f=new File(c.getFilesDir(),name.substring(6));if(!f.getCanonicalPath().startsWith(c.getFilesDir().getCanonicalPath()+File.separator))throw new IOException(T.s("非法恢复路径"));if(data.containsKey(name))atomic(f,data.get(name));else{AtomicFile a=new AtomicFile(f);a.delete();if(f.exists())throw new IOException(T.s("无法替换旧文件"));}}
        JSONObject prefs=new JSONObject(new String(data.get("preferences.json"),"UTF-8"));for(String name:PREFS){SharedPreferences current=c.getSharedPreferences(name,0);SharedPreferences.Editor edit=current.edit();for(String k:current.getAll().keySet())if(key(name,k))edit.remove(k);JSONObject values=prefs.optJSONObject(name);if(values!=null){Iterator<String> keys=values.keys();while(keys.hasNext()){String k=keys.next();JSONObject x=values.getJSONObject(k);switch(x.getString("type")){case "string":edit.putString(k,x.getString("value"));break;case "boolean":edit.putBoolean(k,x.getBoolean("value"));break;case "int":edit.putInt(k,x.getInt("value"));break;case "long":edit.putLong(k,x.getLong("value"));break;case "float":edit.putFloat(k,(float)x.getDouble("value"));break;case "set":Set<String> set=new HashSet<>();JSONArray a=x.getJSONArray("value");for(int i=0;i<a.length();i++)set.add(a.getString(i));edit.putStringSet(k,set);break;}}}if(!edit.commit())throw new IOException(T.s("设置写入失败"));}
    }
    private static File marker(Context c){return new File(c.getFilesDir(),"local-restore-applying");}
    /** Runs before stores/services are initialized in a fresh process. */
    static void startup(Context c){try{
        if(journal(c).exists()){
            if(marker(c).exists()){
                try(InputStream in=new FileInputStream(journal(c))){apply(c,LocalArchive.decode(in));}
                if(!journal(c).delete())throw new IOException(T.s("无法结束回滚"));marker(c).delete();pending(c).delete();
                c.getSharedPreferences("afs_data_status",0).edit().putString("result","上次恢复中断，已回滚原数据").commit();return;
            }
            if(!journal(c).delete())throw new IOException(T.s("无法清理未开始的恢复"));
        }else if(marker(c).exists()){marker(c).delete();pending(c).delete();}
        if(!pending(c).exists())return;
        if(!mayRestore(c)){pending(c).delete();throw new IOException(T.s("当前冻结状态不允许完整恢复"));}
        if(!c.getSharedPreferences("afs_data_status",0).edit().putBoolean("pause_legacy_sync",true).commit())throw new IOException(T.s("无法暂停旧版同步，恢复未开始"));
        ByteArrayOutputStream before=new ByteArrayOutputStream();export(c,before);atomic(journal(c),before.toByteArray());before=null;
        atomic(marker(c),new byte[]{1});
        try(InputStream in=new FileInputStream(pending(c))){apply(c,LocalArchive.decode(in));}
        if(!c.getSharedPreferences("afs_sync",0).edit().putString("cache_epoch",java.util.UUID.randomUUID().toString()).commit())throw new IOException(T.s("无法切换同步缓存"));
        if(!pending(c).delete()||!journal(c).delete())throw new IOException(T.s("无法提交恢复"));marker(c).delete();
        c.getSharedPreferences("afs_data_status",0).edit().putBoolean("pause_legacy_sync",true).putString("result","本地备份已完整恢复；旧版 Gist 同步已暂停，请重新验证后启用").commit();
        try{MemoReminder.reschedule(c);}catch(RuntimeException reminderFailure){android.util.Log.w("AFSBackup","Restored notes; reminders need permission",reminderFailure);}
    }catch(Exception e){try{if(journal(c).exists()&&marker(c).exists()){try(InputStream in=new FileInputStream(journal(c))){apply(c,LocalArchive.decode(in));}}journal(c).delete();marker(c).delete();pending(c).delete();}catch(Exception failure){throw new IllegalStateException(T.s("恢复回滚未完成，原始回滚文件已保留"),failure);}c.getSharedPreferences("afs_data_status",0).edit().putString("result","恢复未完成，已保留原数据："+e.getMessage()).commit();}}
}
