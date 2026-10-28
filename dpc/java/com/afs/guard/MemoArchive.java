package com.afs.guard;

import android.content.Context;
import java.io.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Self-contained note ZIP. Untrusted bytes are staged and validated before attachment writes. */
public final class MemoArchive {
    private MemoArchive(){}
    private static final long TOTAL=100L*1024*1024,SINGLE=20L*1024*1024;
    private static final String UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public static final class Data implements Closeable {
        public final MemoBackup.Data backup;
        private final File stage;
        private final List<String> ids;
        private boolean closed;
        Data(MemoBackup.Data backup,File stage,List<String> ids){this.backup=backup;this.stage=stage;this.ids=ids;}
        @Override public void close(){if(!closed){closed=true;remove(stage,stage);}}
    }
    private static File root(Context c)throws IOException{File root=new File(c.getFilesDir(),"memo-attachments");if(!root.isDirectory()&&!root.mkdirs())throw bad("无法创建附件目录");return root;}
    private static void regular(File file,File parent)throws IOException{
        if(!file.isFile()||!file.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile()))throw bad("附件文件路径无效");
    }
    private static void uuidFolder(File folder,File parent)throws IOException{
        if(!folder.getName().matches(UUID_PATTERN)||!folder.isDirectory()||!folder.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile()))throw bad("附件目录无效");
    }
    private static JSONObject meta(File file)throws IOException{
        if(file.length()>4096)throw bad("附件描述过大");
        byte[] data=bytes(file);String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString();}
        catch(CharacterCodingException e){throw bad("附件描述编码无效");}
        try{
            JSONObject value=new JSONObject(text);if(value.length()!=2||!value.has("name")||!value.has("mime"))throw bad("附件描述字段无效");
            Object name=value.get("name"),mime=value.get("mime");
            if(!(name instanceof String)||!(mime instanceof String)||((String)name).length()>100||((String)mime).length()>128
                    ||((String)name).matches("(?s).*\\p{Cntrl}.*")||((String)mime).matches("(?s).*\\p{Cntrl}.*"))throw bad("附件描述字段无效");
            return value;
        }catch(JSONException e){throw bad("附件描述格式无效");}
    }
    private static byte[] bytes(File file)throws IOException{try(InputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)output.write(b,0,n);return output.toByteArray();}}
    public static void write(Context context,OutputStream output,String memoJson)throws IOException{
        writeFiles(root(context),output,memoJson);
    }
    public static void writeFiles(File root,OutputStream output,String memoJson)throws IOException{
        MemoBackup.decode(memoJson);byte[] json=memoJson.getBytes(StandardCharsets.UTF_8);if(json.length>SINGLE)throw bad("备份正文超过 20 MB");
        List<File> folders=new ArrayList<>();long total=json.length;File[] files=root.listFiles();if(files==null)throw bad("无法读取附件目录");
        for(File folder:files){
            if(!folder.getName().matches(UUID_PATTERN))continue;
            uuidFolder(folder,root);File data=new File(folder,"data"),info=new File(folder,"meta.json");regular(data,folder);regular(info,folder);meta(info);
            if(data.length()>SINGLE||info.length()>SINGLE)throw bad("单个附件超过 20 MB");total+=data.length()+info.length();if(total>TOTAL)throw bad("备份超过 100 MB");folders.add(folder);if(folders.size()>1000)throw bad("附件数量超过备份限制");
        }
        Collections.sort(folders,(a,b)->a.getName().compareTo(b.getName()));
        // Finishes the ZIP without taking ownership of the caller's output stream.
        try(ZipOutputStream zip=new ZipOutputStream(new FilterOutputStream(output){@Override public void close()throws IOException{flush();}})){long[] budget={0};
        entry(zip,"memo.json",new ByteArrayInputStream(json),budget);
        for(File folder:folders)for(String name:new String[]{"data","meta.json"}){
            File file=new File(folder,name);regular(file,folder);if(name.equals("meta.json"))meta(file);
            try(InputStream in=new FileInputStream(file)){entry(zip,"attachments/"+folder.getName()+"/"+name,in,budget);}
        }zip.finish();zip.flush();}
    }
    private static void entry(ZipOutputStream zip,String name,InputStream in,long[] budget)throws IOException{
        zip.putNextEntry(new ZipEntry(name));copy(in,zip,budget);zip.closeEntry();
    }
    private static void copy(InputStream in,OutputStream out,long[] total)throws IOException{
        byte[] b=new byte[8192];int n;long single=0;while((n=in.read(b))!=-1){single+=n;total[0]+=n;if(single>SINGLE||total[0]>TOTAL)throw bad("备份超过文件大小限制");out.write(b,0,n);}
    }
    public static Data read(Context context,InputStream source)throws IOException{
        return readCache(context.getCacheDir(),source);
    }
    public static Data readCache(File cache,InputStream source)throws IOException{
        if(source==null)throw bad("无法读取备份");File stage=File.createTempFile("memo-import-","",cache);
        if(!stage.delete()||!stage.mkdir())throw bad("无法创建临时目录");
        try(ZipInputStream zip=new ZipInputStream(new FilterInputStream(source){@Override public void close(){}})){
            Set<String> paths=new HashSet<>(),ids=new TreeSet<>();long[] total={0};ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){String path=entry.getName();
                if(entry.isDirectory()||!paths.add(path)||paths.size()>2001)throw bad("备份含重复或不支持的文件");
                File target;
                if(path.equals("memo.json"))target=new File(stage,"memo.json");
                else{
                    if(!path.matches("attachments/"+UUID_PATTERN+"/(data|meta\\.json)"))throw bad("备份附件路径无效");
                    String id=path.split("/")[1];ids.add(id);File dir=new File(stage,id);if(!dir.isDirectory()&&!dir.mkdir())throw bad("无法暂存附件");
                    target=new File(dir,path.endsWith("/data")?"data":"meta.json");
                }
                try(OutputStream output=new FileOutputStream(target)){copy(zip,output,total);}zip.closeEntry();
            }
            File memo=new File(stage,"memo.json");if(!memo.isFile())throw bad("缺少备份正文");MemoBackup.Data backup;
            try(InputStream input=new FileInputStream(memo)){backup=MemoBackup.read(input);}
            for(String id:ids){File dir=new File(stage,id);regular(new File(dir,"data"),dir);regular(new File(dir,"meta.json"),dir);meta(new File(dir,"meta.json"));}
            return new Data(backup,stage,new ArrayList<>(ids));
        }catch(IOException|RuntimeException e){remove(stage,stage);throw e;}
    }
    /** All conflicts are checked first; partial new files are rolled back on I/O failure. */
    public static synchronized void applyAttachments(Context context,Data archive)throws IOException{
        applyTo(root(context),archive);
    }
    public static synchronized void applyTo(File root,Data archive)throws IOException{
        if(archive.closed)throw bad("备份暂存已关闭");if(!root.isDirectory()&&!root.mkdirs())throw bad("无法创建附件目录");
        List<File> pending=new ArrayList<>(),created=new ArrayList<>();List<String> newIds=new ArrayList<>();
        try{
            for(String id:archive.ids){File existing=new File(root,id),source=new File(archive.stage,id);
                if(existing.exists()){
                    uuidFolder(existing,root);regular(new File(existing,"data"),existing);regular(new File(existing,"meta.json"),existing);
                    JSONObject a=meta(new File(existing,"meta.json")),b=meta(new File(source,"meta.json"));
                    if(!a.optString("name").equals(b.optString("name"))||!a.optString("mime").equals(b.optString("mime"))
                            ||!Arrays.equals(digest(new File(existing,"data")),digest(new File(source,"data"))))throw bad("附件编号冲突，未覆盖任何附件");
                }else newIds.add(id);
            }
            for(String id:newIds){File temp=new File(root,".import-"+UUID.randomUUID());if(!temp.mkdir())throw bad("无法暂存恢复附件");pending.add(temp);
                for(String name:new String[]{"data","meta.json"})try(InputStream in=new FileInputStream(new File(new File(archive.stage,id),name));OutputStream out=new FileOutputStream(new File(temp,name))){copy(in,out,new long[]{0});}
            }
            for(int i=0;i<newIds.size();i++){File target=new File(root,newIds.get(i));if(target.exists()||!pending.get(i).renameTo(target))throw bad("附件恢复失败");created.add(target);}
        }catch(IOException|RuntimeException e){for(File file:created)remove(file,root);throw e;}
        finally{for(File file:pending)if(file.exists())remove(file,root);}
    }
    private static byte[] digest(File file)throws IOException{
        try{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;long count=0;while((n=in.read(b))!=-1){count+=n;if(count>SINGLE)throw bad("附件超过限制");hash.update(b,0,n);}}return hash.digest();}
        catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private static void remove(File file,File owner){
        try{String path=file.getCanonicalPath(),base=owner.getCanonicalPath();if(!path.equals(base)&&!path.startsWith(base+File.separator))return;
            File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child,owner);file.delete();
        }catch(IOException ignored){}
    }
    private static IOException bad(String message){return new IOException(message);}
}
