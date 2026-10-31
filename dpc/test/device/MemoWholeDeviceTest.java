package com.afs.guard;
import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import java.io.*;
import java.util.*;
public final class MemoWholeDeviceTest extends Instrumentation {
    int count;void check(boolean value,String text){if(!value)throw new AssertionError(text);count++;}
    void restore(SharedPreferences p,Map<String,?> values){SharedPreferences.Editor e=p.edit().clear();for(Map.Entry<String,?> pair:values.entrySet()){Object v=pair.getValue();if(v instanceof String)e.putString(pair.getKey(),(String)v);else if(v instanceof Boolean)e.putBoolean(pair.getKey(),(Boolean)v);else if(v instanceof Long)e.putLong(pair.getKey(),(Long)v);else if(v instanceof Integer)e.putInt(pair.getKey(),(Integer)v);}e.commit();}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){Context c=getTargetContext();Bundle result=new Bundle();SharedPreferences notes=c.getSharedPreferences("afs_guard",0),library=c.getSharedPreferences("afs_memo_library",0),ai=c.getSharedPreferences("afs_memo_ai",0);
        String original=notes.getString("memos_v1",null),trash=notes.getString("memos_trash_v1",null);Map<String,?> oldLibrary=new HashMap<>(library.getAll()),oldAi=new HashMap<>(ai.getAll());String attachment=null;File source=null;
        try{
            source=File.createTempFile("memo-whole-",".txt",c.getCacheDir());try(OutputStream out=new FileOutputStream(source)){out.write("attachment fixture".getBytes("UTF-8"));}
            String link=MemoFiles.add(c,android.net.Uri.fromFile(source));attachment=link.substring(link.indexOf("afs-file:")+9,link.length()-1);check(MemoFiles.validId(attachment),"attachment ID");
            try(InputStream in=c.getContentResolver().openInputStream(android.net.Uri.parse("content://com.afs.guard.memo.files/"+attachment))){check(in!=null&&in.read()=='a',"private provider readable");}
            boolean writeBlocked=false;try{c.getContentResolver().openOutputStream(android.net.Uri.parse("content://com.afs.guard.memo.files/"+attachment),"w");}catch(Exception e){writeBlocked=true;}check(writeBlocked,"provider refuses writes");
            MemoStore.Note note=MemoStore.create(c,"Whole QA",link);MemoLibrary.Book book=MemoLibrary.createNotebook(c,"QA "+java.util.UUID.randomUUID());MemoLibrary.setNotebook(c,note.id,book.id);MemoLibrary.setPaper(c,note.id,"blue");
            check(book.id.equals(MemoLibrary.notebookOf(c,note.id)),"notebook persisted");check("blue".equals(MemoLibrary.paperOf(c,note.id)),"paper persisted");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();MemoArchive.write(c,bytes,MemoBackup.encode(MemoStore.all(c),MemoStore.trash(c),MemoLibrary.exportJson(c)));
            File folder=new File(MemoFiles.root(c),attachment);check(new File(folder,"data").delete()&&new File(folder,"meta.json").delete()&&folder.delete(),"fixture removed before restore");
            try(MemoArchive.Data archive=MemoArchive.read(c,new ByteArrayInputStream(bytes.toByteArray()))){MemoArchive.applyAttachments(c,archive);int before=MemoStore.count(c);MemoBackup.Data merged=MemoBackup.restore(c,archive.backup);check(MemoStore.count(c)==before,"identical restore does not duplicate");check(merged.idMap.containsKey(note.id),"restore ID mapping");}
            check(MemoFiles.data(c,attachment).length()==18,"attachment bytes restored");
            MemoLibrary.deleteNotebook(c,book.id);check("".equals(MemoLibrary.notebookOf(c,note.id)),"book deletion returns default");check(MemoStore.count(c)>0,"book deletion preserves notes");
            MemoAi.save(c,"https://example.invalid/v1","qa-model","dummy-key");check("qa-model".equals(MemoAi.model(c)),"AI model persisted");check(!"dummy-key".equals(ai.getString("key","")),"API key encrypted");
            java.lang.reflect.Method decrypt=MemoAi.class.getDeclaredMethod("secret",Context.class);decrypt.setAccessible(true);check("dummy-key".equals(decrypt.invoke(null,c)),"encrypted API key decrypts");
            boolean blocked=false;try{MemoAi.save(c,"https://other.invalid/v1","qa","");}catch(IllegalArgumentException e){blocked=true;}check(blocked,"endpoint changes require explicit new credential");
            result.putString("stream","Memo whole device: "+count+" checks passed\n");
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));}
        finally{SharedPreferences.Editor e=notes.edit();if(original==null)e.remove("memos_v1");else e.putString("memos_v1",original);if(trash==null)e.remove("memos_trash_v1");else e.putString("memos_trash_v1",trash);e.commit();restore(library,oldLibrary);restore(ai,oldAi);
            if(source!=null)source.delete();if(attachment!=null){File folder=new File(MemoFiles.root(c),attachment);new File(folder,"data").delete();new File(folder,"meta.json").delete();folder.delete();}}
        finish(result.getString("stream","").startsWith("Memo whole device:")?-1:0,result);
    }
}
