package com.afs.guard;
import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;
public final class LocalDataDeviceTest extends Instrumentation {
    private int checks;private Class<?> backup,cloud;
    private void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
    private Object call(Class<?> type,String method,Class<?>[] types,Object...args)throws Exception{Method m=type.getDeclaredMethod(method,types);m.setAccessible(true);try{return m.invoke(null,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}
    private void write(File f,byte[] b)throws Exception{try(OutputStream out=new FileOutputStream(f)){out.write(b);}}
    private byte[] read(File f)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}return out.toByteArray();}
    private byte[] export(Context c)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();call(backup,"export",new Class<?>[]{Context.class,OutputStream.class},c,out);return out.toByteArray();}
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void onStart(){Bundle result=new Bundle();try{
        Context base=getTargetContext();String prefix="data_qa_"+System.nanoTime()+"_";Context c=new ContextWrapper(base){public Context getApplicationContext(){return this;}public String getPackageName(){return "com.afs.guard.fixture";}public File getFilesDir(){File f=new File(base.getCacheDir(),prefix);f.mkdirs();return f;}public SharedPreferences getSharedPreferences(String n,int mode){return base.getSharedPreferences(prefix+n,mode);}};
        backup=Class.forName("com.afs.guard.LocalBackup",true,base.getClassLoader());cloud=Class.forName("com.afs.guard.CloudProfiles",true,base.getClassLoader());
        File study=new File(c.getFilesDir(),"study.json");byte[] original="{\"goals\":[],\"recs\":[],\"marker\":\"original\"}".getBytes("UTF-8");write(study,original);
        File media=new File(c.getFilesDir(),"memory-media");media.mkdir();File image=new File(media,"fixture.png");byte[] picture=new byte[]{1,2,3,4};write(image,picture);
        File attachments=new File(c.getFilesDir(),"memo-attachments/12345678-1234-1234-1234-123456789abc");attachments.mkdirs();File attachment=new File(attachments,"data");write(attachment,new byte[]{8,7,6});write(new File(attachments,"meta.json"),"{}".getBytes("UTF-8"));
        SharedPreferences guard=c.getSharedPreferences("afs_guard",0);guard.edit().putString("memos_v1","[]").putString("rules","{}").putLong("first_seen",123).commit();
        JSONObject profile=new JSONObject().put("target","https://example.com/dav").put("path","backups");call(cloud,"save",new Class<?>[]{Context.class,String.class,JSONObject.class,String.class},c,"webdav",profile,"cloud-fixture-secret");
        String stored=c.getSharedPreferences("afs_cloud_profiles",0).getString("webdav","");check(!stored.contains("cloud-fixture-secret")&&stored.contains("credential"),"credentials encrypted at rest");
        byte[] archive=export(c);String summary=(String)call(backup,"inspect",new Class<?>[]{InputStream.class},new ByteArrayInputStream(archive));check(summary.contains("文件数"),"backup preview");
        write(image,new byte[]{9});write(attachment,new byte[]{0});
        write(study,"{\"goals\":[],\"recs\":[]}".getBytes("UTF-8"));guard.edit().putLong("first_seen",999).commit();
        call(backup,"queue",new Class<?>[]{Context.class,InputStream.class},c,new ByteArrayInputStream(archive));call(backup,"startup",new Class<?>[]{Context.class},c);
        check(Arrays.equals(original,read(study)),"study data round trip");check(Arrays.equals(picture,read(image)),"memory image round trip");check(Arrays.equals(new byte[]{8,7,6},read(attachment)),"memo attachment round trip");check(guard.getLong("first_seen",0)==999,"initial grace timestamp not restored");check(stored.equals(c.getSharedPreferences("afs_cloud_profiles",0).getString("webdav","")),"cloud credential not replaced");check(c.getSharedPreferences("afs_data_status",0).getBoolean("pause_legacy_sync",false),"restore pauses legacy network sync");
        byte[] rollback=export(c);write(new File(c.getFilesDir(),"local-restore-rollback.zip"),rollback);write(new File(c.getFilesDir(),"local-restore-applying"),new byte[]{1});write(study,"{\"goals\":[],\"recs\":[]}".getBytes("UTF-8"));call(backup,"startup",new Class<?>[]{Context.class},c);check(Arrays.equals(original,read(study)),"interrupted restore rolled back");
        write(new File(c.getFilesDir(),"local-restore-rollback.zip"),new byte[]{1,2,3});call(backup,"startup",new Class<?>[]{Context.class},c);check(Arrays.equals(original,read(study)),"incomplete journal before mutation safely discarded");
        check(!new File(c.getFilesDir(),"local-restore-rollback.zip").exists(),"journal cleaned after recovery");
        result.putString("stream","Local data device: "+checks+" checks passed\n");finish(-1,result);
    }catch(Throwable e){result.putString("stream","FAILED "+android.util.Log.getStackTraceString(e));finish(1,result);}}
}
