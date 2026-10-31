package com.afs.guard;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

public final class MemoBackupTest {
    private static int checks;
    private interface Attempt{void run()throws Exception;}
    private static void check(boolean yes,String name){checks++;if(!yes)throw new AssertionError(name);}
    private static void rejects(Attempt action,String name)throws Exception{boolean failed=false;try{action.run();}catch(IllegalArgumentException|IOException e){failed=true;}check(failed,name);}
    private static List<MemoStore.Note> one(String id,String body){return Arrays.asList(new MemoStore.Note(id,"title",body,1,false));}
    private static List<MemoStore.Note> empty(){return new ArrayList<>();}
    private static String backup(List<MemoStore.Note> notes,List<MemoStore.Note> trash){return MemoBackup.encode(notes,trash,new JSONObject());}
    private static byte[] zip(String[] names,byte[][] contents)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){for(int i=0;i<names.length;i++){z.putNextEntry(new ZipEntry(names[i]));z.write(contents[i]);z.closeEntry();}}return out.toByteArray();
    }
    private static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception{
        MemoStore.Note original=one("a","text").get(0);original.remindAt=100;original.remindedAt=100;original.pinned=true;
        String encoded=backup(Arrays.asList(original),one("b","deleted"));MemoBackup.Data decoded=MemoBackup.decode(encoded);
        check(decoded.notes.size()==1&&decoded.trash.size()==1,"both stores roundtrip");
        check(decoded.notes.get(0).remindAt==100&&decoded.notes.get(0).remindedAt==100,"reminder delivery retained");
        MemoBackup.Data same=MemoBackup.merge(Arrays.asList(original),empty(),MemoBackup.decode(backup(Arrays.asList(original),empty())));
        check(same.notes.size()==1&&same.idMap.get("a").equals("a"),"identical import is idempotent");
        MemoBackup.Data conflict=MemoBackup.merge(one("a","keep"),empty(),decoded);
        check(conflict.notes.size()==2&&!conflict.idMap.get("a").equals("a"),"changed same id copied with new UUID");
        check(conflict.notes.get(0).body.equals("keep")&&conflict.notes.get(1).body.equals("text"),"neither version overwritten");
        MemoBackup.Data cross=MemoBackup.merge(empty(),Arrays.asList(original),MemoBackup.decode(backup(Arrays.asList(original),empty())));
        check(cross.notes.isEmpty()&&cross.trash.size()==1,"existing trash state wins identical import");
        rejects(()->backup(one("a","x"),one("a","y")),"duplicate across stores rejected");
        rejects(()->MemoBackup.decode(encoded.replace("\"version\":1","\"version\":2")),"unknown version rejected");
        rejects(()->MemoBackup.decode(encoded+"garbage"),"trailing garbage rejected");
        rejects(()->MemoBackup.decode(encoded.replace("\"pinned\":true","\"pinned\":\"true\"")),"wrong boolean type rejected");
        rejects(()->MemoBackup.decode(encoded.replace("\"updated\":1","\"updated\":-1")),"negative time rejected");
        rejects(()->MemoBackup.decode(encoded.replace("\"updated\":1","\"updated\":1.5")),"fractional time rejected");
        rejects(()->backup(one("a",String.join("",Collections.nCopies(20001,"x"))),empty()),"overlong body refused");
        rejects(()->backup(Arrays.asList((MemoStore.Note)null),empty()),"null record refused instead of silently skipped");
        rejects(()->MemoBackup.read(new ByteArrayInputStream(new byte[]{(byte)0xff})),"invalid UTF8 rejected");
        check(MemoBackup.read(new ByteArrayInputStream(utf("\uFEFF"+encoded))).notes.size()==1,"UTF8 BOM accepted");
        List<MemoStore.Note> full=new ArrayList<>();for(int i=0;i<500;i++)full.add(one("n"+i,"body").get(0));
        rejects(()->MemoBackup.merge(full,empty(),MemoBackup.decode(backup(one("extra","body"),empty()))),"merged capacity checked before mutation");
        check(full.size()==500,"failed merge leaves original list intact");
        Path temp=Files.createTempDirectory("memo-backup-test");File cache=Files.createDirectory(temp.resolve("cache")).toFile(),root=Files.createDirectory(temp.resolve("attachments")).toFile();
        String id="01234567-1234-1234-1234-0123456789ab";File dir=new File(root,id);dir.mkdir();
        Files.write(new File(dir,"data").toPath(),new byte[]{1,2,3,4});Files.write(new File(dir,"meta.json").toPath(),utf("{\"name\":\"图片\",\"mime\":\"image/png\"}"));
        ByteArrayOutputStream zipped=new ByteArrayOutputStream();MemoArchive.writeFiles(root,zipped,encoded);byte[] archive=zipped.toByteArray();
        try(MemoArchive.Data read=MemoArchive.readCache(cache,new ByteArrayInputStream(archive))){
            check(read.backup.notes.size()==1,"archive note decoded");File output=new File(temp.toFile(),"restored");MemoArchive.applyTo(output,read);
            check(Arrays.equals(Files.readAllBytes(new File(new File(output,id),"data").toPath()),new byte[]{1,2,3,4}),"attachment bytes restored");
            MemoArchive.applyTo(output,read);check(output.list().length==1,"identical attachment restore idempotent");
            Files.write(new File(new File(output,id),"data").toPath(),new byte[]{9});
            rejects(()->MemoArchive.applyTo(output,read),"different same UUID rejected");
            check(Files.readAllBytes(new File(new File(output,id),"data").toPath())[0]==9,"collision does not overwrite local attachment");
        }
        check(cache.list().length==0,"staging removed on close");
        rejects(()->MemoArchive.readCache(cache,new ByteArrayInputStream(zip(new String[]{"memo.json","../escape"},new byte[][]{utf(encoded),utf("bad")}))),"path traversal rejected");
        rejects(()->MemoArchive.readCache(cache,new ByteArrayInputStream(zip(new String[]{"memo.json","attachments/"+id+"/data"},new byte[][]{utf(encoded),utf("bad")}))),"missing metadata rejected");
        rejects(()->MemoArchive.readCache(cache,new ByteArrayInputStream(zip(new String[]{"memo.json","attachments/"+id+"/data","attachments/"+id+"/meta.json"},new byte[][]{utf(encoded),utf("data"),utf("{\"name\":1,\"mime\":\"x\"}")}))),"invalid metadata rejected");
        rejects(()->MemoArchive.readCache(cache,new ByteArrayInputStream(zip(new String[]{"memo.json","unknown.txt"},new byte[][]{utf(encoded),utf("bad")}))),"unknown archive member rejected");
        rejects(()->MemoArchive.readCache(cache,new ByteArrayInputStream(zip(new String[]{"memo.json","attachments/"+id+"/data","attachments/"+id+"/meta.json"},new byte[][]{utf(encoded),new byte[MemoBackup.MAX_BYTES+1],utf("{\"name\":\"x\",\"mime\":\"x\"}")}))),"ZIP bomb per-file decompression limit");
        check(cache.list().length==0,"failed reads remove staging");
        byte[] duplicate=zip(new String[]{"memo.json","memo.jsox"},new byte[][]{utf(encoded),utf(encoded)});
        duplicate=new String(duplicate,StandardCharsets.ISO_8859_1).replace("memo.jsox","memo.json").getBytes(StandardCharsets.ISO_8859_1);final byte[] duplicateInput=duplicate;
        rejects(()->MemoArchive.readCache(cache,new ByteArrayInputStream(duplicateInput)),"duplicate ZIP entry rejected");
        rejects(()->MemoArchive.readCache(cache,new ByteArrayInputStream(zip(new String[]{"memo.json","attachments/"+id+"/data","attachments/"+id+"/meta.json"},new byte[][]{utf(encoded),utf("data"),utf("{\"name\":\"bad\\nname\",\"mime\":\"x\"}")}))),"metadata control character rejected");
        String other="11234567-1234-1234-1234-0123456789ab";File otherDir=new File(root,other);otherDir.mkdir();Files.write(new File(otherDir,"data").toPath(),new byte[]{7});Files.write(new File(otherDir,"meta.json").toPath(),utf("{\"name\":\"x\",\"mime\":\"x\"}"));
        ByteArrayOutputStream twoZip=new ByteArrayOutputStream();MemoArchive.writeFiles(root,twoZip,encoded);File collisionRoot=new File(temp.toFile(),"batch-collision");new File(collisionRoot,other).mkdirs();
        Files.write(new File(new File(collisionRoot,other),"data").toPath(),new byte[]{8});Files.write(new File(new File(collisionRoot,other),"meta.json").toPath(),utf("{\"name\":\"x\",\"mime\":\"x\"}"));
        try(MemoArchive.Data pair=MemoArchive.readCache(cache,new ByteArrayInputStream(twoZip.toByteArray()))){
            rejects(()->MemoArchive.applyTo(collisionRoot,pair),"batch collision rejected before any new attachment");
            check(!new File(collisionRoot,id).exists(),"earlier new UUID not copied when later UUID conflicts");
        }
        try(java.util.stream.Stream<Path> paths=Files.walk(temp)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});}
        System.out.println("Memo backup: "+checks+" checks passed");
    }
}
