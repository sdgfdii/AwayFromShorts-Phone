package com.afs.guard;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;
public final class LocalArchiveTest {
    private static int checks;
    private static void check(boolean yes){checks++;if(!yes)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        Map<String,byte[]> data=new TreeMap<>();data.put("preferences.json","{}".getBytes("UTF-8"));data.put("files/study.json","{\"goals\":[],\"recs\":[]}".getBytes("UTF-8"));
        ByteArrayOutputStream out=new ByteArrayOutputStream();LocalArchive.write(data,out);
        Map<String,byte[]> decoded=LocalArchive.decode(new ByteArrayInputStream(out.toByteArray()));check(Arrays.equals(data.get("files/study.json"),decoded.get("files/study.json")));
        for(String bad:new String[]{"../outside","files/../study.json","files/token.local","files/afs_sync.xml","files/memory-media/../secret"})check(!LocalArchive.allowed(bad));
        ByteArrayOutputStream changed=new ByteArrayOutputStream();try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));ZipOutputStream z=new ZipOutputStream(changed)){ZipEntry e;while((e=in.getNextEntry())!=null){byte[] bytes=LocalArchive.read(in,LocalArchive.ENTRY);z.putNextEntry(new ZipEntry(e.getName()));z.write(e.getName().equals("files/study.json")?"{}".getBytes("UTF-8"):bytes);z.closeEntry();}}
        boolean rejected=false;try{LocalArchive.decode(new ByteArrayInputStream(changed.toByteArray()));}catch(Exception expected){rejected=true;}check(rejected);
        for(String p:new String[]{"webdav","github","git"}){JSONObject cfg=new JSONObject().put("target",p.equals("github")?"owner/private-repo":"https://example.com/repository").put("branch","main").put("path","backups/afs");CloudProfiles.validate(p,cfg);checks++;}
        for(String url:new String[]{"file:///data/local/secret","https://user:password@example.com/dav","http://example.com/dav"}){rejected=false;try{CloudProfiles.validate("webdav",new JSONObject().put("target",url));}catch(Exception e){rejected=true;}check(rejected);}
        check(!LocalBackup.key("afs_guard","first_seen"));check(!LocalBackup.key("afs_guard","armed"));check(!LocalBackup.key("afs_sync","token"));check(LocalBackup.key("afs_guard","memos_v1"));
        System.out.println("Local archive: "+checks+" checks passed");
    }
}
