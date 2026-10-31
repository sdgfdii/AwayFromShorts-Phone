package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.FrameLayout;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Emulator-only, isolated records; never submits a real learning or check-in action. */
public final class MotionDeviceTest extends Instrumentation {
    private int checks; private String mode="normal";
    private void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private void main(Runnable action){final Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private Object invoke(Object target,String name,Class<?>[] args,Object... values)throws Exception{Method m=target.getClass()==Fake.class?CheckinActivity.class.getDeclaredMethod(name,args):target.getClass().getDeclaredMethod(name,args);m.setAccessible(true);return m.invoke(target,values);}
    private Object field(Object target,String name)throws Exception{Field f=CheckinActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void set(Object target,String name,Object value)throws Exception{Field f=CheckinActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private void write(File f,String text)throws Exception{try(OutputStream out=new FileOutputStream(f)){out.write(text.getBytes("UTF-8"));}}
    private boolean submit(Fake activity,Goal goal,double amount)throws Exception{
        main(()->{try{invoke(activity,"record",new Class<?>[]{Goal.class,double.class,Runnable.class},goal,amount,(Runnable)()->{});}catch(Exception e){throw new RuntimeException(e);}});
        for(int i=0;i<200&&(Boolean)field(activity,"confirmingReceipt");i++)Thread.sleep(20);
        check(!(Boolean)field(activity,"confirmingReceipt"),"confirmation job completed");main(()->{});return goal.localHit;
    }
    static final class Fake extends CheckinActivity {
        Context app;File files,failRead;String prefix;java.util.concurrent.CountDownLatch readStarted,releaseRead;
        void init(Context c,File dir){app=c;files=dir;prefix="motion-fixture-"+java.util.UUID.randomUUID()+"-";attachBaseContext(c);}
        @Override public File getFilesDir(){for(StackTraceElement entry:Thread.currentThread().getStackTrace())if("confirmedRecord".equals(entry.getMethodName())){if(readStarted!=null){readStarted.countDown();try{if(!releaseRead.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("read release timed out");}catch(InterruptedException e){throw new IllegalStateException(e);}}return failRead!=null?failRead:files;}return files;}
        @Override public String getPackageName(){return "com.afs.guard.fixture";}
        @Override public Context getApplicationContext(){return this;}
        @Override public android.content.SharedPreferences getSharedPreferences(String n,int mode){return app.getSharedPreferences(prefix+n,mode);}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);mode=args.getString("mode","normal");start();}
    @Override public void onStart(){Bundle result=new Bundle();Activity activity=null;Context c=getTargetContext();
        android.content.SharedPreferences prefs=c.getSharedPreferences(Ui.SP,0);boolean had=prefs.contains("reduce_motion"),old=prefs.getBoolean("reduce_motion",false);
        try{
            if(!Build.MODEL.toLowerCase().contains("sdk"))throw new AssertionError("Emulator required");
            prefs.edit().putBoolean("reduce_motion",false).commit();if("zero".equals(mode)){check(!MotionPolicy.enabled(c),"API24 reads global animator zero");check(!MotionPolicy.navigation(c),"zero disables navigation");result.putString("stream","Motion device: "+checks+" checks passed\n");finish(-1,result);return;}
            activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity screen=activity;
            final SwipeCardView[] card={null};final FrameLayout[] parent={null};AtomicInteger done=new AtomicInteger();
            main(()->{parent[0]=new FrameLayout(screen);((android.view.ViewGroup)screen.findViewById(android.R.id.content)).addView(parent[0],new android.view.ViewGroup.LayoutParams(300,400));card[0]=new SwipeCardView(screen);parent[0].addView(card[0],new FrameLayout.LayoutParams(250,350));});
            Thread.sleep(150);main(()->card[0].dissolve(done::incrementAndGet));Thread.sleep(40);prefs.edit().putBoolean("reduce_motion",true).commit();
            for(int i=0;i<30&&done.get()==0;i++)Thread.sleep(20);
            check(done.get()==1,"mid-animation reduce motion finishes once");Thread.sleep(250);check(done.get()==1,"cancelled animator cannot finish twice");
            main(()->{card[0].reset();check(card[0].getScaleX()==1&&card[0].getAlpha()==1,"reset restores scale and alpha");});
            done.set(0);main(()->card[0].dissolve(done::incrementAndGet));check(done.get()==1,"reduced motion completion is immediate");
            prefs.edit().putBoolean("reduce_motion",false).commit();if("zero".equals(mode)){check(!MotionPolicy.enabled(c),"API24 reads global animator zero");check(!MotionPolicy.navigation(c),"zero disables navigation");result.putString("stream","Motion device: "+checks+" checks passed\n");finish(-1,result);return;}Thread.sleep(60);done.set(0);
            main(()->{card[0].reset();card[0].dissolve(done::incrementAndGet);card[0].cancelVisual();});Thread.sleep(260);check(done.get()==0,"pause-style cancellation does not advance");
            main(()->parent[0].removeView(card[0]));

            File dir=new File(c.getCacheDir(),"motion-record-"+System.nanoTime());check(dir.mkdirs(),"isolated record directory");
            final Fake[] fake={null};main(()->{fake[0]=new Fake();fake[0].init(c,dir);});Goal g=new Goal();g.id="motion-goal";g.name="fixture";
            File db=new File(dir,"study.json"),tmp=new File(dir,"study.json.tmp");write(db,"{\"goals\":[],\"recs\":[]}");check(tmp.mkdir(),"create write blocker");File blocker=new File(tmp,"keep");write(blocker,"x");
            final boolean[] saved={true};saved[0]=submit(fake[0],g,1d);
            check(!saved[0]&&!g.localHit,"failed save has no completion state");check(StudyStore.recs(fake[0]).isEmpty(),"failed write creates no record");
            check(blocker.delete()&&tmp.delete(),"remove write blocker");
            File failDir=new File(dir,"unreadable");check(failDir.mkdir()&&new File(failDir,"study.json").mkdir(),"create unreadable confirmation target");fake[0].failRead=failDir;
            saved[0]=submit(fake[0],g,1d);
            check(!saved[0],"unknown readback stays unconfirmed");StudyStore.Rec pending=(StudyStore.Rec)field(fake[0],"unverifiedRec");check(pending!=null,"uncertain result retains exact accepted ID");
            fake[0].failRead=null;check(StudyStore.recs(fake[0]).size()==1,"write succeeded before confirmation read failure");
            JSONObject row=new JSONObject();row.put("id",pending.id).put("goalId",pending.goalId).put("date",pending.date).put("amount",pending.amount).put("ts",pending.ts);JSONObject data=new JSONObject();data.put("goals",new JSONArray()).put("recs",new JSONArray().put(row));check(StudyStore.recs(fake[0]).get(0).id.equals(pending.id),"retained ID matches real saved record");
            saved[0]=submit(fake[0],g,1d);
            check(saved[0]&&g.localHit,"retry recognizes original saved ID");check(StudyStore.recs(fake[0]).size()==1,"uncertain retry does not append another record");
            File strictDir=new File(c.getCacheDir(),"motion-strict-"+System.nanoTime());check(strictDir.mkdir(),"isolated strict read directory");File strictDb=new File(strictDir,"study.json");write(strictDb,"broken JSON");final Fake[] strict={null};main(()->{strict[0]=new Fake();strict[0].init(c,strictDir);});boolean failed=false;try{invoke(strict[0],"confirmedRecord",new Class<?>[]{StudyStore.Rec.class},pending);}catch(Exception expected){failed=true;}
            check(failed&&strictDb.isFile()&&!new File(strictDir,"study.json.bad").exists(),"strict confirmation never renames bad data");
            for(boolean succeed:new boolean[]{true,false}){
                File lateDir=new File(c.getCacheDir(),"motion-late-"+System.nanoTime());check(lateDir.mkdir(),"isolated delayed confirmation");final Fake[] late={null};
                main(()->{late[0]=new Fake();late[0].init(c,lateDir);late[0].readStarted=new java.util.concurrent.CountDownLatch(1);late[0].releaseRead=new java.util.concurrent.CountDownLatch(1);});
                Goal lateGoal=new Goal();lateGoal.id="late";lateGoal.name="late fixture";AtomicInteger advances=new AtomicInteger();
                main(()->{try{invoke(late[0],"record",new Class<?>[]{Goal.class,double.class,Runnable.class},lateGoal,1d,(Runnable)advances::incrementAndGet);}catch(Exception e){throw new RuntimeException(e);}});
                check(late[0].readStarted.await(3,java.util.concurrent.TimeUnit.SECONDS),"background confirmation can be delayed");
                if(!succeed)write(new File(lateDir,"study.json"),"{\"goals\":[],\"recs\":[]}");
                main(()->{try{set(late[0],"cardVisualGeneration",1L);set(late[0],"busy",true);}catch(Exception e){throw new RuntimeException(e);}});late[0].releaseRead.countDown();
                for(int i=0;i<200&&(Boolean)field(late[0],"confirmingReceipt");i++)Thread.sleep(20);main(()->{});
                check(!(Boolean)field(late[0],"confirmingReceipt"),"delayed confirmation completed");
                check((Boolean)field(late[0],"busy")&&advances.get()==0,"old confirmation preserves new visual lock and never advances");
            }
            result.putString("stream","Motion device: "+checks+" checks passed\n");
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{if(had)prefs.edit().putBoolean("reduce_motion",old).commit();else prefs.edit().remove("reduce_motion").commit();if(activity!=null){final Activity a=activity;main(a::finish);}}
        finish(result.getString("stream","").startsWith("Motion device:")?-1:0,result);
    }
}
