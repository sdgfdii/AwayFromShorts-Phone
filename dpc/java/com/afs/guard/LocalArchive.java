package com.afs.guard;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;
import org.json.*;
/** Portable, credential-free archive. Never extracts an unvalidated path. */
final class LocalArchive {
    static final int LIMIT=(int)Math.min(128L*1024*1024,Math.max(4L*1024*1024,Runtime.getRuntime().maxMemory()/10)), ENTRY=Math.min(64*1024*1024,LIMIT);
    static boolean allowed(String s){return s.equals("preferences.json")||s.matches("files/(study\\.json|memory-v1\\.json|quiz-v1\\.json|cards-v1\\.json|backdrop\\.jpg)")||s.matches("files/memory-media/[A-Za-z0-9_-]+\\.[A-Za-z0-9]+")||s.matches("files/memo-attachments/[a-fA-F0-9-]{36}/(data|meta\\.json)");}
    static String hash(byte[] bytes)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(java.util.Locale.US,"%02x",b&255));return s.toString();}
    static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] a=new byte[8192];int n;while((n=in.read(a))!=-1){if(b.size()>limit-n)throw new IOException(T.s("备份超过大小上限"));b.write(a,0,n);}return b.toByteArray();}
    static void write(Map<String,byte[]> data,OutputStream out)throws Exception{
        JSONObject entries=new JSONObject();long total=0;
        for(Map.Entry<String,byte[]> e:data.entrySet()){if(!allowed(e.getKey())||e.getValue().length>ENTRY||(total+=e.getValue().length)>LIMIT)throw new IOException(T.s("备份路径或大小无效"));entries.put(e.getKey(),new JSONObject().put("size",e.getValue().length).put("sha256",hash(e.getValue())));}
        JSONObject manifest=new JSONObject().put("format","afs-local-backup").put("version",1).put("entries",entries);
        try(ZipOutputStream z=new ZipOutputStream(out)){z.putNextEntry(new ZipEntry("manifest.json"));z.write(manifest.toString().getBytes("UTF-8"));z.closeEntry();for(Map.Entry<String,byte[]> e:data.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}
    }
    static Map<String,byte[]> decode(InputStream in)throws Exception{
        Map<String,byte[]> map=new TreeMap<>();byte[] manifest=null;long total=0;int count=0;
        try(ZipInputStream z=new ZipInputStream(in)){ZipEntry e;while((e=z.getNextEntry())!=null){String n=e.getName();if(e.isDirectory()||++count>8192||(!n.equals("manifest.json")&&!allowed(n))||map.containsKey(n))throw new IOException(T.s("备份包含不允许或重复的路径"));byte[] bytes=read(z,(int)Math.min(LIMIT-total,n.equals("manifest.json")?1024*1024:ENTRY));total+=bytes.length;if(total>LIMIT)throw new IOException(T.s("备份超过当前设备的安全大小上限"));if(n.equals("manifest.json")){if(manifest!=null)throw new IOException(T.s("重复清单"));manifest=bytes;}else map.put(n,bytes);}}
        if(manifest==null)throw new IOException(T.s("缺少备份清单"));JSONObject m=new JSONObject(new String(manifest,"UTF-8"));if(!m.optString("format").equals("afs-local-backup")||m.optInt("version")!=1)throw new IOException(T.s("不支持的备份版本"));JSONObject entries=m.getJSONObject("entries");if(entries.length()!=map.size()||!map.containsKey("preferences.json"))throw new IOException(T.s("备份文件不完整"));
        for(Map.Entry<String,byte[]> e:map.entrySet()){JSONObject x=entries.getJSONObject(e.getKey());if(x.getLong("size")!=e.getValue().length||!x.getString("sha256").equals(hash(e.getValue())))throw new IOException(T.s("备份校验失败：")+e.getKey());}
        return map;
    }
}
