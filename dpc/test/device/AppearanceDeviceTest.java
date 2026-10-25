package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

/** Same-signature UI integration checks. The three appearance preference keys are restored exactly. */
public final class AppearanceDeviceTest extends Instrumentation {
    private int checks;
    private Activity current;
    private void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;}
    private Activity open(Class<? extends Activity> type){
        current=startActivitySync(new Intent(getTargetContext(),type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
        waitForIdleSync();SystemClock.sleep(100);return current;
    }
    private Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private FloatingNav dock(Activity activity)throws Exception{
        Field f=LiveNavWindow.class.getDeclaredField("windows");f.setAccessible(true);
        Map<?,?> windows=(Map<?,?>)f.get(null);WeakReference<?> ref=(WeakReference<?>)windows.get(activity);
        return ref==null?null:(FloatingNav)field(ref.get(),"nav");
    }
    private TextView text(View root,String wanted){
        if(root instanceof TextView&&wanted.contentEquals(((TextView)root).getText()))return (TextView)root;
        if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){TextView t=text(g.getChildAt(i),wanted);if(t!=null)return t;}}
        return null;
    }
    private void ui(Runnable action){
        final Throwable[] error={null};super.runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});
        if(error[0]!=null)throw new AssertionError("UI check failed",error[0]);
    }
    private float sp(TextView view){android.util.DisplayMetrics m=view.getResources().getDisplayMetrics();return Build.VERSION.SDK_INT>=34?android.util.TypedValue.deriveDimension(android.util.TypedValue.COMPLEX_UNIT_SP,view.getTextSize(),m):view.getTextSize()/m.scaledDensity;}
    private float px(Activity a,float sp){return android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,sp,a.getResources().getDisplayMetrics());}
    @Override public void onCreate(Bundle args){start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Context context=getTargetContext();SharedPreferences prefs=context.getSharedPreferences(Ui.SP,0);
        Map<String,?> original=prefs.getAll();String rules=RuleStore.exportRules(context);
        int records=StudyStore.recs(context).size();float systemScale=context.getApplicationContext().getResources().getConfiguration().fontScale;
        Throwable failure=null;
        try {
            AppearanceConfig a=ThemeStore.load(context);a.skin="classic";a.mode="dark";a.pureBlack=true;a.seed=0xFFFF9500;
            for(String style:new String[]{"vivid","tinted","soft","monochrome"}){
                a.style=style;ThemeStore.save(context,a);Activity activity=open(MainActivity.class);
                ui(()->{Ui ui=new Ui(activity);check(ui.dark&&ui.bg()==0xFF000000,"pure-black mode is applied on an actual activity");
                    TextView heading=text(activity.getWindow().getDecorView(),"设置");check(heading!=null&&heading.getCurrentTextColor()==ui.text(),"actual page heading follows generated theme");});
            }
            a.mode="light";a.style="vivid";a.seed=0xFFFF3B30;a.customScale=true;a.scale=1.35f;a.font="serif";ThemeStore.save(context,a);
            for(Class<? extends Activity> type:new Class[]{MainActivity.class,MemoryActivity.class,QuizActivity.class,FreezeBoardActivity.class,CheckinActivity.class}){
                Activity activity=open(type);
                ui(()->check(Math.abs(activity.getResources().getConfiguration().fontScale-1.35f)<.001,"custom scale reaches "+type.getSimpleName()));
                if(type==MemoryActivity.class)ui(()->{TextView add=text(activity.getWindow().getDecorView(),"记忆工具");
                    check(add!=null&&add.getCurrentTextColor()==new Ui(activity).accent(),"memory actions use the chosen seed color");
                    android.graphics.Typeface expected=ThemeStore.font(activity,"serif",0);if(Build.VERSION.SDK_INT>=28)expected=android.graphics.Typeface.create(expected,500,false);
                    check(add.getTypeface().equals(expected),"selected font reaches actual text views with the shared button weight");});
            }
            a.customScale=false;a.font="";a.floating=false;a.glass="ultra";ThemeStore.save(context,a);Activity fixed=open(MainActivity.class);
            ui(()->{try{FloatingNav nav=dock(fixed);check(nav!=null&&!nav.floating()&&!nav.hasGlass(),"fixed dock is solid rather than floating glass");
                check(nav.getWidth()==fixed.getWindow().getDecorView().getWidth(),"fixed dock spans the whole screen");}catch(Exception e){throw new RuntimeException(e);}});
            a.floating=true;
            for(String quality:new String[]{"none","standard","ultra"}){
                a.glass=quality;ThemeStore.save(context,a);Activity activity=open(MainActivity.class);
                ui(()->{try{FloatingNav nav=dock(activity);check(nav!=null&&nav.floating(),"floating dock is attached for "+quality);
                    check(nav.hasGlass()!=quality.equals("none"),"glass none switches off the optical path");
                    if(Build.VERSION.SDK_INT>=33)check(nav.gpuGlass()!=quality.equals("none"),"real GPU path selection for "+quality);
                }catch(Exception e){throw new RuntimeException(e);}});
            }
            a.animation="fade";ThemeStore.save(context,a);Activity settings=open(ThemeActivity.class);
            ui(()->{try{ThemePreviewView preview=(ThemePreviewView)field(settings,"preview");long now=SystemClock.uptimeMillis();
                MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,preview.getWidth()*.72f,preview.getHeight()*.91f,0);
                MotionEvent up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,preview.getWidth()*.72f,preview.getHeight()*.91f,0);
                preview.dispatchTouchEvent(down);preview.dispatchTouchEvent(up);down.recycle();up.recycle();
                check(preview.selected()==3&&!settings.isFinishing(),"live preview changes miniature tabs without leaving settings");
            }catch(Exception e){throw new RuntimeException(e);}});
            a.skin="amap";a.mode="light";a.pureBlack=false;a.style="vivid";a.amapSeed=AppearanceConfig.AMAP_COLOR;a.font="";a.floating=true;ThemeStore.save(context,a);
            for(Class<? extends Activity> type:new Class[]{MainActivity.class,MemoryActivity.class,QuizActivity.class,FreezeBoardActivity.class,CheckinActivity.class}){
                Activity activity=open(type);
                ui(()->{try{Ui look=new Ui(activity);check(look.amap&&look.bg()==0xFFF5F6F7&&look.accent()==AppearanceConfig.AMAP_COLOR,"Amap appearance reaches actual "+type.getSimpleName());
                    FloatingNav nav=dock(activity);check(nav!=null&&nav.floating(),"Amap dock is attached to "+type.getSimpleName());
                }catch(Exception e){throw new RuntimeException(e);}});
            }
            a.mode="dark";ThemeStore.save(context,a);Activity night=open(MainActivity.class);
            ui(()->{Ui look=new Ui(night);check(look.amap&&look.dark&&look.bg()==0xFF101418,"Amap night mode reaches actual page");check(text(night.getWindow().getDecorView(),"守卫")!=null,"Amap uses the guard page heading");});
            a.pureBlack=true;ThemeStore.save(context,a);Activity black=open(ThemeActivity.class);
            ui(()->check(new Ui(black).bg()==0xFF000000,"Amap pure black works in settings too"));
            a.skin="classic";ThemeStore.save(context,a);Activity classic=open(MainActivity.class);
            ui(()->check(!new Ui(classic).amap&&text(classic.getWindow().getDecorView(),"设置")!=null,"switching back restores classic page structure"));
            check(ThemeStore.load(context).seed==0xFFFF3B30,"Amap does not overwrite the prior classic accent");
            a.mode="light";a.pureBlack=false;ThemeStore.save(context,a);
            Class[] pageTypes={MainActivity.class,MemoryActivity.class,QuizActivity.class,FreezeBoardActivity.class};
            String[] pageTitles={"设置","本日记忆闪卡","刷题","冻结应用"};
            for(int i=0;i<pageTypes.length;i++){
                Activity page=open(pageTypes[i]);final String title=pageTitles[i];
                ui(()->{TextView heading=text(page.getWindow().getDecorView(),title);check(heading!=null,"Swiss heading exists on "+title);
                    float scale=page.getResources().getDisplayMetrics().scaledDensity;int[] xy=new int[2];heading.getLocationOnScreen(xy);
                    check(Math.abs(sp(heading)-28)<.1,"page heading uses the shared 28sp role: "+title);
                    check(Math.abs(xy[0]-new Ui(page).dp(20))<=1,"page heading aligns to the 20dp column: "+title);
                    check(heading.getLineHeight()>=px(page,35),"heading has enough leading: "+title);
                });
            }
            Activity typeCheck=open(ThemeActivity.class);
            ui(()->{android.widget.LinearLayout custom=new android.widget.LinearLayout(typeCheck);TextView sample=new TextView(typeCheck);sample.setText("自定义字体保留");sample.setTypeface(android.graphics.Typeface.SERIF);custom.addView(sample);ThemeStore.excludeFont(custom);ThemeStore.typography(custom);
                check(sample.getTypeface().equals(android.graphics.Typeface.SERIF),"Swiss spacing preserves explicit card fonts");
                TextView body=new TextView(typeCheck);body.setText("正文的行距应足够阅读。\n第二行保持同一列。");body.setTextSize(15);ThemeStore.typography(body);float scale=typeCheck.getResources().getDisplayMetrics().scaledDensity;
                check(Math.abs(sp(body)-15)<.1&&body.getLineHeight()>=px(typeCheck,23),"body role has 15sp text and 24sp leading");
            });
            check(RuleStore.exportRules(context).equals(rules)&&StudyStore.recs(context).size()==records,"appearance checks preserve freeze rules and check-in records");
            check(Math.abs(context.getApplicationContext().getResources().getConfiguration().fontScale-systemScale)<.001,"custom scaling never modifies the phone's system scale");
        }catch(Throwable t){failure=t;}
        finally {
            SharedPreferences.Editor restore=prefs.edit();
            for(String key:new String[]{ThemeStore.CONFIG,ThemeStore.REVISION,Ui.K_THEME}){
                Object value=original.get(key);if(value==null)restore.remove(key);else if(value instanceof String)restore.putString(key,(String)value);else if(value instanceof Long)restore.putLong(key,(Long)value);
            }
            restore.commit();if(current!=null)ui(()->current.finish());
        }
        if(failure!=null){java.io.StringWriter trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));result.putString("stream",trace.toString());finish(0,result);}
        else {result.putString("stream","DEVICE APPEARANCE: "+checks+" assertions passed; original appearance restored; study and freeze data unchanged\n");finish(-1,result);}
    }
}
