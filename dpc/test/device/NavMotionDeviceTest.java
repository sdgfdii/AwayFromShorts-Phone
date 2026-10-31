package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import java.lang.reflect.*;

/** Exercises actual Activity reuse and separate dock windows; no domain writes. */
public final class NavMotionDeviceTest extends Instrumentation {
    private volatile Activity top;
    private int checks;
    private void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private void main(Runnable action){final Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private Object field(Class<?> owner,Object target,String name){try{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new RuntimeException(e);}}
    private FloatingNav nav(Activity activity){try{Method m=LiveNavWindow.class.getDeclaredMethod("find",Activity.class);m.setAccessible(true);Object window=m.invoke(null,activity);return (FloatingNav)field(LiveNavWindow.class,window,"nav");}catch(Exception e){throw new RuntimeException(e);}}
    private long token(String name){return (Long)field(FloatingNav.class,null,name);}
    private Activity waitPage(String name,Activity previous)throws Exception{
        long limit=SystemClock.uptimeMillis()+8000;
        while(SystemClock.uptimeMillis()<limit){Activity a=top;if(a!=null&&a!=previous&&a.getClass().getSimpleName().equals(name)&&a.hasWindowFocus())return a;Thread.sleep(20);}
        try{
            android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
            if(shot!=null)try(java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"nav-motion-failure.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);shot.recycle();}
            try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("dumpsys window windows"));java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"nav-motion-windows.txt"))){byte[] data=new byte[8192];int n;while((n=in.read(data))!=-1)out.write(data,0,n);}
        }catch(Exception ignored){}
        throw new AssertionError("Page did not become visible: "+name+"; actual="+(top==null?"null":top.getClass().getSimpleName()+"/focus="+top.hasWindowFocus()));
    }
    private void waitConsumed()throws Exception{for(int i=0;i<100&&token("consumedTransition")!=token("transitionToken");i++)Thread.sleep(20);check(token("consumedTransition")==token("transitionToken"),"request consumed on visible target");}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Context c=getTargetContext();android.content.SharedPreferences prefs=c.getSharedPreferences(Ui.SP,0);boolean had=prefs.contains("reduce_motion"),old=prefs.getBoolean("reduce_motion",false);
        android.content.SharedPreferences setup=c.getSharedPreferences("afs_setup",0);boolean setupHad=setup.contains("guide_v1_seen"),setupOld=setup.getBoolean("guide_v1_seen",false);setup.edit().putBoolean("guide_v1_seen",true).commit();
        Application app=(Application)c.getApplicationContext();Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){top=a;}public void onActivityPaused(Activity a){if(top==a)top=null;}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };app.registerActivityLifecycleCallbacks(observer);
        try{
            check(Build.MODEL.toLowerCase().contains("sdk"),"emulator required");prefs.edit().putBoolean("reduce_motion",false).commit();
            Activity first=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));top=first;Thread.sleep(150);
            main(()->FloatingNav.swipeNavigate(first,4,0));Activity memory=waitPage("MemoryActivity",null);waitConsumed();long consumed=token("consumedTransition");
            main(memory::recreate);Activity recreated=waitPage("MemoryActivity",memory);Thread.sleep(150);
            check(token("consumedTransition")==consumed,"recreate does not create or consume a second request");
            check((Long)field(FloatingNav.class,nav(recreated),"playedTransition")==0L,"new dock does not replay consumed request");
            main(()->FloatingNav.swipeNavigate(recreated,0,1));Activity quiz=waitPage("QuizActivity",null);waitConsumed();
            main(()->{try{FloatingNav current=nav(quiz);View panel=(View)field(FloatingNav.class,current,"panel");Method find=LiveNavWindow.class.getDeclaredMethod("find",Activity.class);find.setAccessible(true);Object live=find.invoke(null,quiz);Dialog dialog=(Dialog)field(LiveNavWindow.class,live,"dialog");View decor=dialog.getWindow().getDecorView();check(panel.getWidth()==decor.getWidth()&&panel.getHeight()==decor.getHeight(),"blur window matches capsule bounds without an outer blurred strip");}catch(Exception error){throw new RuntimeException(error);}});
            main(()->{try{FloatingNav current=nav(quiz);View content=(View)field(FloatingNav.class,current,"source");AppearanceConfig appearance=(AppearanceConfig)field(FloatingNav.class,current,"appearance");String oldAnimation=appearance.animation;Method enter=FloatingNav.class.getDeclaredMethod("enterContent",int.class);enter.setAccessible(true);for(String mode:new String[]{"slide","fade"}){appearance.animation=mode;enter.invoke(current,1);check(content.getTranslationX()==0&&content.getTranslationY()==0&&content.getAlpha()==1,"page appears immediately even with saved "+mode+" preference");}appearance.animation=oldAnimation;}catch(Exception error){throw new RuntimeException(error);}});
            main(()->{try{Method find=LiveNavWindow.class.getDeclaredMethod("find",Activity.class);find.setAccessible(true);Object live=find.invoke(null,quiz);Method background=LiveNavWindow.class.getDeclaredMethod("capsuleBackground",int.class);background.setAccessible(true);android.graphics.drawable.GradientDrawable shape=(android.graphics.drawable.GradientDrawable)background.invoke(live,0x88FFFFFF);View panel=(View)field(FloatingNav.class,nav(quiz),"panel");check(Math.abs(shape.getCornerRadius()-panel.getHeight()/2f)<=1,"window background uses capsule radius without card-theme clamping");}catch(Exception error){throw new RuntimeException(error);}});
            prefs.edit().putBoolean("reduce_motion",true).commit();Thread.sleep(100);final FloatingNav dock=nav(quiz);final View source=(View)field(FloatingNav.class,dock,"source");
            main(()->{check(source.getTranslationX()==0&&source.getAlpha()==1,"policy change ends content visual");});
            prefs.edit().putBoolean("reduce_motion",false).commit();Thread.sleep(60);
            final long beforeCancelled=token("transitionToken");
            main(()->{FloatingNav.swipeNavigate(quiz,1,0);Runnable stale=(Runnable)field(FloatingNav.class,null,"pendingOpen");FloatingNav.cancelPending(quiz);stale.run();});Thread.sleep(100);
            check(token("transitionToken")==beforeCancelled&&top==quiz,"cancelled pending request cannot publish a transition");
            main(()->{
                DockSpring position=(DockSpring)field(FloatingNav.class,dock,"position"),lift=(DockSpring)field(FloatingNav.class,dock,"lift");
                position.snap(.8);lift.snap(1);dock.glassVisible(false);
                check(lift.value==0&&position.velocity==0,"hidden dock clears old spring state");dock.glassVisible(true);
                check(lift.value==0&&position.velocity==0,"resume does not replay old spring");
                long time=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,100,100,0);dock.onTouchEvent(down);down.recycle();
                MotionEvent second=MotionEvent.obtain(time,time+10,MotionEvent.ACTION_POINTER_DOWN,100,100,0);dock.onTouchEvent(second);second.recycle();
                check(!(Boolean)field(FloatingNav.class,dock,"pressing"),"second finger cancels dock gesture");
            });
            final long beforeRapid=token("transitionToken");
            main(()->{FloatingNav.swipeNavigate(quiz,1,0);Runnable stale=(Runnable)field(FloatingNav.class,null,"pendingOpen");FloatingNav.swipeNavigate(quiz,1,4);stale.run();});Activity last=waitPage("MainActivity",null);waitConsumed();check(last instanceof MainActivity,"rapid navigation settles on final destination");
            check(token("transitionToken")==beforeRapid+1,"same-loop requests dispatch only the final destination");
            main(()->last.onBackPressed());Thread.sleep(150);check(top==null||!top.isDestroyed(),"return leaves a live resumed Activity");
            result.putString("stream","Navigation motion device: "+checks+" checks passed\n");
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));}
        finally{if(setupHad)setup.edit().putBoolean("guide_v1_seen",setupOld).commit();else setup.edit().remove("guide_v1_seen").commit();app.unregisterActivityLifecycleCallbacks(observer);if(had)prefs.edit().putBoolean("reduce_motion",old).commit();else prefs.edit().remove("reduce_motion").commit();}
        finish(result.getString("stream","").startsWith("Navigation motion device:")?-1:0,result);
    }
}
