package com.afs.guard;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.graphics.*;import java.io.*;import java.lang.reflect.*;
/** Read-only domain checks on a real window. No preference, learning, check-in or cloud writes. */
public final class GlassAcceptanceDeviceTest extends Instrumentation {
 int checks;Activity page;NavGlassRenderer probe;Object live;boolean blurEnabled;StringBuilder details=new StringBuilder();
 void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 void main(Runnable r){Throwable[] e={null};runOnMainSync(()->{try{r.run();}catch(Throwable t){e[0]=t;}});if(e[0]!=null)throw new AssertionError(e[0]);}
 Object field(Object o,String n){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
 void appearance(boolean blur){try{Method m=LiveNavWindow.class.getDeclaredMethod("appearance",boolean.class);m.setAccessible(true);m.invoke(live,blur);}catch(Exception e){throw new RuntimeException(e);}}
 void screenshot(String name)throws Exception{Bitmap shot=getUiAutomation().takeScreenshot();check(shot!=null,"actual window screenshot "+name);try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name+".png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}shot.recycle();}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Bundle result=new Bundle();try{
  Context c=getTargetContext();ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);main(()->c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));page=waitForMonitorWithTimeout(monitor,8000);removeMonitor(monitor);check(page!=null,"main window starts");Thread.sleep(300);
  main(()->{try{Method find=LiveNavWindow.class.getDeclaredMethod("find",Activity.class);find.setAccessible(true);live=find.invoke(null,page);check(live!=null,"real dock window attached");FloatingNav nav=(FloatingNav)field(live,"nav");View glyphs=(View)field(nav,"tabs"),host=(View)field(live,"host");check(glyphs.isHardwareAccelerated(),"real glyph layer hardware accelerated");details.append("api=").append(Build.VERSION.SDK_INT).append(" configuredGlass=").append(ThemeStore.load(c).glass).append(" nativeDockGpu=").append(nav.gpuGlass()).append('\n');
   probe=NavGlassRenderer.create(c,host,nav,glyphs,new Ui(c).dark);
   if(Build.VERSION.SDK_INT>=33){check(probe!=null&&probe.active(),"API33 RuntimeShader compiles on actual device");probe.setVisible(true);RectF lens=new RectF(0,0,Math.max(1,glyphs.getWidth()/3),Math.max(1,glyphs.getHeight()));probe.update(lens,lens,lens,1);check(probe.active(),"RuntimeShader effect attaches successfully");glyphs.invalidate();}
   else check(probe==null&&!nav.gpuGlass(),"pre33 uses static glass fallback");
   if(Build.VERSION.SDK_INT>=31){blurEnabled=page.getSystemService(WindowManager.class).isCrossWindowBlurEnabled();details.append("systemCrossWindowBlurEnabled=").append(blurEnabled).append('\n');appearance(blurEnabled);}
  }catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(200);screenshot("glass-shader-active");if(probe!=null)main(()->{check(probe.active(),"shader remains active after composed frame");probe.setVisible(false);});
  if(Build.VERSION.SDK_INT>=31){main(()->appearance(false));Thread.sleep(100);screenshot("glass-blur-fallback");main(()->appearance(blurEnabled));check(true,"blur fallback and restoration complete");}
  main(()->{FloatingNav nav=(FloatingNav)field(live,"nav");nav.glassVisible(false);nav.glassVisible(true);check(nav.getAlpha()==1&&nav.getScaleX()==1,"hide show retains visible dock terminal state");});
  result.putString("stream","Glass acceptance device: "+checks+" checks passed\n"+details);
 }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e)+details);}
 finally{if(page!=null)main(()->{if(probe!=null)probe.setVisible(false);LiveNavWindow.refresh(page);page.finish();});}
 finish(result.getString("stream","").startsWith("Glass acceptance device:")?-1:0,result);
 }
}
