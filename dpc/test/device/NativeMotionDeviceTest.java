package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Animation fixtures do not unlock achievements or write learning records. */
public final class NativeMotionDeviceTest extends Instrumentation {
    int checks;Activity host;AlertDialog celebration;SplashView splash;
    StudyCardSurface surface;AchievementMark mark;FrameLayout samples;
    void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
    void ui(Runnable task){final Throwable[] failure={null};runOnMainSync(()->{try{task.run();}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}
    void awaitDismiss(AlertDialog dialog)throws Exception{for(int i=0;i<40&&dialog.isShowing();i++)Thread.sleep(50);}
    void awaitCanvasStop()throws Exception{for(int i=0;i<40&&(surface.feedbackRunning()||mark.drawingRunning());i++)Thread.sleep(50);}
    String shell(String command)throws Exception{try(java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] bytes=new byte[1024];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);return out.toString("UTF-8").trim();}}
    private void captureSamples(Context c,String name){ui(()->{
        android.graphics.Bitmap frame=android.graphics.Bitmap.createBitmap(samples.getWidth(),samples.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        samples.draw(new android.graphics.Canvas(frame));
        try(java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(c.getExternalFilesDir(null),name+".png"))){frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        catch(java.io.IOException error){throw new RuntimeException(error);}finally{frame.recycle();}
    });}
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void onStart(){Context c=getTargetContext();SharedPreferences prefs=c.getSharedPreferences(Ui.SP,0);boolean had=prefs.contains("reduce_motion"),old=prefs.getBoolean("reduce_motion",false);Bundle out=new Bundle();int status=0;String systemScale=null;
        try{
            check(Build.MODEL.toLowerCase().contains("sdk"),"emulator only");prefs.edit().putBoolean("reduce_motion",false).commit();
            host=startActivitySync(new Intent(c,MainActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1400);
            java.lang.reflect.Field overlay=MainActivity.class.getDeclaredField("splash");overlay.setAccessible(true);check(overlay.get(host)==null,"launcher startup releases overlay after one action cycle");
            AtomicInteger callbacks=new AtomicInteger();
            ui(()->{splash=new SplashView(host);host.addContentView(splash,new ViewGroup.LayoutParams(-1,-1));splash.play(callbacks::incrementAndGet);});
            Thread.sleep(1200);check(callbacks.get()==1,"finite splash completion fires exactly once");
            ui(()->{splash.stop();((ViewGroup)splash.getParent()).removeView(splash);});Thread.sleep(100);check(callbacks.get()==1,"detach never fires completion again");
            ui(()->{splash=new SplashView(host);host.addContentView(splash,new ViewGroup.LayoutParams(-1,-1));splash.play(callbacks::incrementAndGet);splash.stop();((ViewGroup)splash.getParent()).removeView(splash);});Thread.sleep(1200);check(callbacks.get()==1,"interrupted splash cancels stale completion");
            prefs.edit().putBoolean("reduce_motion",true).commit();Thread.sleep(100);
            ui(()->{splash=new SplashView(host);host.addContentView(splash,new ViewGroup.LayoutParams(-1,-1));splash.play(callbacks::incrementAndGet);});check(callbacks.get()==2,"reduced motion immediately completes splash");
            ui(()->{splash.stop();((ViewGroup)splash.getParent()).removeView(splash);});
            List<Achievement.Item> fixture=Arrays.asList(new Achievement.Item("qa-animation","✓","Animation preview","No achievement data is changed",1,1));
            ui(()->celebration=AchievementCelebration.show(host,fixture));Thread.sleep(200);check(celebration.isShowing(),"reduced motion keeps full achievement dialog usable");
            ui(()->celebration.getButton(-1).performClick());Thread.sleep(100);check(!celebration.isShowing(),"reduced-motion dismissal has no delay");
            prefs.edit().putBoolean("reduce_motion",false).commit();Thread.sleep(100);
            ui(()->celebration=AchievementCelebration.show(host,fixture));Thread.sleep(400);check(celebration.isShowing(),"animated achievement remains visible after entrance");
            ui(()->{
                View window=celebration.getWindow().getDecorView();
                android.graphics.Bitmap shot=android.graphics.Bitmap.createBitmap(window.getWidth(),window.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                window.draw(new android.graphics.Canvas(shot));
                try(java.io.OutputStream image=new java.io.FileOutputStream(new java.io.File(c.getExternalFilesDir(null),"achievement-motion.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);}
                catch(java.io.IOException error){throw new RuntimeException(error);}finally{shot.recycle();}
            });
            ui(()->celebration.getButton(-1).performClick());awaitDismiss(celebration);check(!celebration.isShowing(),"finite achievement exit completes");
            ui(()->{
                samples=new FrameLayout(host);samples.setBackgroundColor(0xFFF4F5F6);
                host.addContentView(samples,new ViewGroup.LayoutParams(-1,-1));
                surface=new StudyCardSurface(host);CardPalette.apply(surface,CardPalette.MEMORY,false);
                TextView title=new TextView(host);title.setText("Canvas · answer revealed");title.setTextSize(28);surface.addView(title);
                FrameLayout.LayoutParams cardLp=new FrameLayout.LayoutParams(800,1100);cardLp.leftMargin=80;cardLp.topMargin=180;samples.addView(surface,cardLp);
                mark=new AchievementMark(host,new Ui(host));FrameLayout.LayoutParams markLp=new FrameLayout.LayoutParams(150,150);markLp.leftMargin=400;markLp.topMargin=1390;samples.addView(mark,markLp);
            });Thread.sleep(180);
            ui(()->{surface.highlight();mark.play();check(surface.feedbackRunning()&&mark.drawingRunning(),"Canvas feedback starts only on explicit action");});
            Thread.sleep(50);captureSamples(c,"canvas-motion-mid");awaitCanvasStop();
            ui(()->check(!surface.feedbackRunning()&&!mark.drawingRunning(),"Canvas animations stop after one finite pass"));captureSamples(c,"canvas-motion-settled");
            ui(()->{surface.highlight();mark.play();samples.setVisibility(View.INVISIBLE);});Thread.sleep(100);
            ui(()->check(!surface.feedbackRunning()&&!mark.drawingRunning(),"hidden Canvas never keeps drawing"));
            ui(()->{samples.setVisibility(View.VISIBLE);surface.highlight();mark.play();});
            prefs.edit().putBoolean("reduce_motion",true).commit();Thread.sleep(150);
            ui(()->check(!surface.feedbackRunning()&&!mark.drawingRunning(),"changing reduced motion cancels active Canvas feedback"));
            ui(()->{surface.highlight();mark.play();check(!surface.feedbackRunning()&&!mark.drawingRunning(),"reduced-motion feedback stays static and complete");});
            prefs.edit().putBoolean("reduce_motion",false).commit();Thread.sleep(100);
            ui(()->{surface.highlight();mark.play();((ViewGroup)samples.getParent()).removeView(samples);});Thread.sleep(100);
            ui(()->check(!surface.feedbackRunning()&&!mark.drawingRunning(),"detaching removes Canvas animation work"));
            systemScale=shell("settings get global animator_duration_scale");shell("settings put global animator_duration_scale 0");Thread.sleep(150);
            check(!MotionPolicy.enabled(c),"system animation zero disables motion");
            int before=callbacks.get();ui(()->{splash=new SplashView(host);host.addContentView(splash,new ViewGroup.LayoutParams(-1,-1));splash.play(callbacks::incrementAndGet);});check(callbacks.get()==before+1,"system animation zero never leaves loading overlay waiting");ui(()->{splash.stop();((ViewGroup)splash.getParent()).removeView(splash);});
            ui(()->celebration=AchievementCelebration.show(host,fixture));ui(host::finish);Thread.sleep(200);check(!celebration.isShowing(),"host leaving cancels and dismisses celebration");
            out.putString("stream","Native motion device: "+checks+" checks passed\n");status=-1;
        }catch(Throwable e){out.putString("stream",android.util.Log.getStackTraceString(e));}
        finally{try{if(systemScale!=null)shell("null".equals(systemScale)?"settings delete global animator_duration_scale":"settings put global animator_duration_scale "+systemScale);}catch(Exception ignored){}ui(()->{if(celebration!=null)celebration.dismiss();if(splash!=null)splash.stop();if(host!=null)host.finish();});SharedPreferences.Editor e=prefs.edit();if(had)e.putBoolean("reduce_motion",old);else e.remove("reduce_motion");e.commit();finish(status,out);}
    }
}
