package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Restores the emulator's ledger in finally. Never allowed on a physical device. */
public final class ReceiptUiDeviceTest extends Instrumentation {
    private int checks;
    private void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private void main(Runnable r){final Throwable[] fail={null};runOnMainSync(()->{try{r.run();}catch(Throwable t){fail[0]=t;}});if(fail[0]!=null)throw new AssertionError(fail[0]);}
    private Field receipt()throws Exception{Field f=CheckinActivity.class.getDeclaredField("unverifiedRec");f.setAccessible(true);return f;}
    private byte[] read(File f)throws Exception{try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private void publish(File dst,byte[] data)throws Exception{File temp=new File(dst.getParentFile(),"motion-ledger-fixture.tmp");try(OutputStream out=new FileOutputStream(temp)){out.write(data);}if(!temp.renameTo(dst))throw new IOException("Atomic fixture publish failed");}
    private Object call(CheckinActivity a,String name,Class<?>[] types,Object...values)throws Exception{Method m=CheckinActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(a,values);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Context c=getTargetContext();File ledger=new File(c.getFilesDir(),"study.json");byte[] original=null;boolean existed=ledger.exists();Application app=(Application)c.getApplicationContext();AtomicReference<CheckinActivity> resumed=new AtomicReference<>();AtomicBoolean restored=new AtomicBoolean();final CheckinActivity[] first={null};
        String id="motion-receipt-"+System.nanoTime();Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){if(a instanceof CheckinActivity&&b!=null)try{StudyStore.Rec rec=(StudyStore.Rec)receipt().get(a);if(rec!=null&&id.equals(rec.id))restored.set(true);}catch(Exception ignored){}}
            public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a instanceof CheckinActivity&&a!=first[0])resumed.set((CheckinActivity)a);}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };
        try{
            check(Build.MODEL.toLowerCase().contains("sdk"),"emulator required");if(existed)original=read(ledger);
            StudyStore.Rec rec=new StudyStore.Rec();rec.id=id;rec.goalId="motion-fixture-goal";rec.date=StudyStore.today();rec.amount=1;rec.ts=System.currentTimeMillis();
            JSONObject row=new JSONObject().put("id",id).put("goalId",rec.goalId).put("date",rec.date).put("amount",1).put("ts",rec.ts);
            JSONObject legacy=new JSONObject().put("id","old-null-fields").put("goalId",JSONObject.NULL).put("amount",JSONObject.NULL).put("unknown",new JSONObject().put("x",1));
            JSONObject db=new JSONObject().put("goals",new JSONArray()).put("recs",new JSONArray().put(legacy).put(row));publish(ledger,db.toString().getBytes("UTF-8"));StudyGate.invalidate(c);
            first[0]=(CheckinActivity)startActivitySync(new Intent(c,CheckinActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            check((Boolean)call(first[0],"confirmedRecord",new Class<?>[]{StudyStore.Rec.class},rec),"unrelated legacy null fields do not block receipt");
            row.put("amount",2);publish(ledger,db.toString().getBytes("UTF-8"));check(!(Boolean)call(first[0],"confirmedRecord",new Class<?>[]{StudyStore.Rec.class},rec),"mismatched target is not confirmed");row.put("amount",1);publish(ledger,db.toString().getBytes("UTF-8"));
            app.registerActivityLifecycleCallbacks(observer);
            main(()->{try{receipt().set(first[0],rec);first[0].recreate();}catch(Exception e){throw new RuntimeException(e);}});
            for(int i=0;i<100&&resumed.get()==null;i++)Thread.sleep(50);CheckinActivity current=resumed.get();check(current!=null,"receipt page recreated");check(restored.get(),"accepted ID restored from real Activity Bundle");
            for(int i=0;i<100&&receipt().get(current)!=null;i++)Thread.sleep(20);main(()->{});check(receipt().get(current)==null,"readable original saved receipt resolved on resume");check(StudyStore.recs(c).size()==1,"recreation did not append another valid record");
            java.util.Calendar day=java.util.Calendar.getInstance();day.add(java.util.Calendar.DATE,-1);String previous=new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(day.getTime());rec.date=previous;row.put("date",previous);publish(ledger,db.toString().getBytes("UTF-8"));
            Goal goal=new Goal();goal.id=rec.goalId;goal.name="fixture";final boolean[] accepted={true};
            main(()->{try{receipt().set(current,rec);call(current,"record",new Class<?>[]{Goal.class,double.class,Runnable.class},goal,1d,(Runnable)()->{});}catch(Exception e){throw new RuntimeException(e);}});
            for(int i=0;i<100&&receipt().get(current)!=null;i++)Thread.sleep(20);main(()->{});check(!goal.localHit,"old-day receipt cannot mark current day complete");check(StudyStore.recs(c).size()==1,"date rollover confirmation did not append");
            // This legal file exceeds the old proposed 32 MiB cap, without allocating its content in memory.
            File large=new File(c.getFilesDir(),"motion-large.tmp");try(OutputStream out=new FileOutputStream(large)){
                out.write("{\"padding\":\"".getBytes("UTF-8"));byte[] pad=new byte[8192];java.util.Arrays.fill(pad,(byte)'x');for(int i=0;i<4352;i++)out.write(pad);
                out.write(("\",\"goals\":[],\"recs\":["+row.toString()+"]}").getBytes("UTF-8"));
            }
            check(large.renameTo(ledger),"publish large legal ledger");long started=SystemClock.uptimeMillis();final boolean[] found={false};Thread background=new Thread(()->{try{found[0]=(Boolean)call(current,"confirmedRecord",new Class<?>[]{StudyStore.Rec.class},rec);}catch(Exception e){throw new RuntimeException(e);}});background.start();long heartbeat=SystemClock.uptimeMillis();main(()->{});check(SystemClock.uptimeMillis()-heartbeat<500,"large receipt verification does not block main thread");background.join(10000);check(!background.isAlive(),"background large confirmation completed");long elapsed=SystemClock.uptimeMillis()-started;
            check(found[0],"streaming confirmation supports ledger over 32 MiB");Bundle timing=new Bundle();timing.putString("stream","Large receipt fixture: bytes="+ledger.length()+", background confirmation wall="+elapsed+"ms\n");sendStatus(0,timing);
            main(current::finish);result.putString("stream","Receipt UI device: "+checks+" checks passed\n");
        }catch(Throwable t){result.putString("stream",android.util.Log.getStackTraceString(t));}
        finally{app.unregisterActivityLifecycleCallbacks(observer);try{if(original!=null)publish(ledger,original);else if(!existed)ledger.delete();StudyGate.invalidate(c);}catch(Exception restore){result.putString("stream",result.getString("stream","")+"RESTORE FAILED "+restore);}}
        String text=result.getString("stream","");finish(text.startsWith("Receipt UI device:")&&!text.contains("RESTORE FAILED")?-1:0,result);
    }
}
