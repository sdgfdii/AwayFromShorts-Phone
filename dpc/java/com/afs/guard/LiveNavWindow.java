package com.afs.guard;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Non-modal live glass window. GPU lenses on API 33+, system blur as the compatibility path. */
final class LiveNavWindow {
    private static final WeakHashMap<Activity,WeakReference<LiveNavWindow>> windows=new WeakHashMap<>();
    private static WeakReference<Activity> resumed=new WeakReference<>(null);
    private static boolean registered;
    private final Activity activity;
    private final View host;
    private final View nav;
    private final Ui ui;
    private final Dialog dialog;
    private final WindowManager manager;
    private Consumer<Boolean> blurListener;
    private boolean destroyed;
    private boolean pendingShow;
    private int lastWidth,lastBottom;

    static void attach(Activity activity,View host,View nav) {
        if(!registered) {
            registered=true;
            activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(Activity a,Bundle state){}
                public void onActivityStarted(Activity a){}
                public void onActivityResumed(Activity a){resumed=new WeakReference<>(a);LiveNavWindow w=find(a);if(w!=null)w.host.post(w::sync);}
                public void onActivityPaused(Activity a){LiveNavWindow w=find(a);if(w!=null)w.hide();if(resumed.get()==a)resumed=new WeakReference<>(null);}
                public void onActivityStopped(Activity a){LiveNavWindow w=find(a);if(w!=null)w.hide();}
                public void onActivitySaveInstanceState(Activity a,Bundle state){}
                public void onActivityDestroyed(Activity a){LiveNavWindow w=find(a);if(w!=null)w.dispose();windows.remove(a);}
            });
        }
        LiveNavWindow previous=find(activity);if(previous!=null)previous.dispose();
        LiveNavWindow window=new LiveNavWindow(activity,host,nav);
        windows.put(activity,new WeakReference<>(window));host.setTag(window);
        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){window.showImmediately();v.post(window::sync);}
            public void onViewDetachedFromWindow(View v){window.dispose();}
        });
        host.getViewTreeObserver().addOnGlobalLayoutListener(window::sync);
        host.setOnApplyWindowInsetsListener((v,insets)->{v.post(window::sync);return insets;});
        // 注意：这里不 show —— 必须在宿主的窗口真正挂上之后（onViewAttachedToWindow）再挂底栏窗口，
        // 否则窗口层级会排在页面下面，切页那几帧底栏会被新页面盖住（看起来像「后面一页露出来」）。
        host.post(window::sync);
    }
    private static LiveNavWindow find(Activity a){WeakReference<LiveNavWindow> ref=windows.get(a);return ref==null?null:ref.get();}
    static void refresh(Activity a){LiveNavWindow w=find(a);if(w!=null)w.appearance(Build.VERSION.SDK_INT>=31&&w.manager.isCrossWindowBlurEnabled());}
    static void focusChanged(Activity a){LiveNavWindow w=find(a);if(w!=null)w.host.post(w::sync);}
    private LiveNavWindow(Activity activity,View host,View nav) {
        this.activity=activity;this.host=host;this.nav=nav;ui=new Ui(activity);manager=activity.getWindowManager();
        int theme=activity.getResources().getIdentifier("LiveNavTheme","style",activity.getPackageName());
        dialog=new Dialog(activity,theme);dialog.setOwnerActivity(activity);dialog.setCancelable(false);
        dialog.setContentView(nav,new ViewGroup.LayoutParams(-1,-1));
        Window w=dialog.getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.setDimAmount(0);w.getDecorView().setPadding(0,0,0,0);
        if(w.getDecorView() instanceof ViewGroup){((ViewGroup)w.getDecorView()).setClipChildren(false);((ViewGroup)w.getDecorView()).setClipToPadding(false);}
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if(Build.VERSION.SDK_INT>=30)w.setDecorFitsSystemWindows(false);
        WindowManager.LayoutParams lp=w.getAttributes();lp.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;
        lp.windowAnimations=0;lp.setTitle("AFS Live Navigation");w.setAttributes(lp);
        if(Build.VERSION.SDK_INT>=31) {
            blurListener=this::appearance;
            manager.addCrossWindowBlurEnabledListener(activity.getMainExecutor(),blurListener);
        }
        appearance(Build.VERSION.SDK_INT>=31 && manager.isCrossWindowBlurEnabled());
    }
    private void appearance(boolean blur) {
        if(destroyed)return;
        Window w=dialog.getWindow();
        FloatingNav dock=(FloatingNav)nav;boolean glass=dock.hasGlass();
        int color=glass?(ui.palette.glassTint&0x00FFFFFF)|(blur?(ui.dark?0x78000000:0x93000000):0xD9000000):ui.surface();
        // 没有玻璃时由 FloatingNav 的 Material 自己画那一层底；窗口背景再画一层就会变成双层药丸
        w.setBackgroundDrawable(!glass?new ColorDrawable(0):capsuleBackground(color));
        // 与窗口同尺寸的圆角背景同时定义系统模糊边界，不再在胶囊外预留窗口区域。
        w.getDecorView().setPadding(0,0,0,0);
        // Blur this small window's live backdrop, never the activity's full-page RenderNode.
        if(Build.VERSION.SDK_INT>=31)w.setBackgroundBlurRadius(blur&&glass?ui.dp(15.6f):0);
    }
    private android.graphics.drawable.GradientDrawable capsuleBackground(int color){
        // Ui.round会把简洁风格的大圆角压到16dp；底栏必须保留31dp的真实胶囊轮廓。
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setColor(color);background.setCornerRadius(ui.dp(((FloatingNav)nav).floating()?31:0));return background;
    }
    private boolean keyboardVisible() {
        if(Build.VERSION.SDK_INT>=30) {
            WindowInsets insets=host.getRootWindowInsets();
            return insets!=null && insets.isVisible(WindowInsets.Type.ime());
        }
        Rect visible=new Rect();host.getWindowVisibleDisplayFrame(visible);
        return host.getRootView().getHeight()-visible.bottom>ui.dp(150);
    }
    private void sync() {
        if(destroyed || activity.isFinishing() || activity.isDestroyed())return;
        boolean focused=activity.hasWindowFocus();
        if(resumed.get()!=activity || !focused || !host.isAttachedToWindow() || host.getWindowVisibility()!=View.VISIBLE || keyboardVisible()){
            // 入场那一下还没拿到焦点：别把刚挂上去的底栏又藏了，否则切页会闪一下
            if(pendingShow && host.isAttachedToWindow() && !keyboardVisible())return;
            hide();return;
        }
        pendingShow=false;
        FloatingNav dock=(FloatingNav)nav;int room=ui.dp(dock.shadowRoomDp());
        int width=host.getWidth()-(dock.floating()?ui.dp(42)-room*2:0),bottom=dock.floating()?ui.dp(21):0; if(width<=0)return;
        int safe=0;
        if(Build.VERSION.SDK_INT>=30){WindowInsets insets=host.getRootWindowInsets();if(insets!=null)safe=insets.getInsets(WindowInsets.Type.navigationBars()).bottom;}
        if(dock.floating())bottom=Math.max(bottom,safe);
        if(!dialog.isShowing())dialog.show();
        if(lastWidth!=width || lastBottom!=bottom) {
            Window w=dialog.getWindow();WindowManager.LayoutParams lp=w.getAttributes();
            lp.width=width;lp.height=ui.dp(62)+room*2+(dock.floating()?0:safe);lp.y=Math.max(0,bottom-room);lp.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;
            w.setAttributes(lp);lastWidth=width;lastBottom=bottom;
        }
        View decor=dialog.getWindow().getDecorView();if(decor.getVisibility()!=View.VISIBLE)decor.setVisibility(View.VISIBLE);
        if(nav instanceof FloatingNav)((FloatingNav)nav).glassVisible(true);
    }
    /**
     * 在 Activity 的窗口还没画出来之前先把底栏窗口挂上：切页时旧页面的底栏会被新页面窗口盖住，
     * 如果等 resume 再 show，就会有一段「页面在动、底栏没了」的间隙。这里用屏幕宽高先摆到
     * 底部（resume 后的 sync 再按真实 inset 校正一次）。
     */
    private void showImmediately() {
        if(destroyed || dialog.isShowing())return;
        pendingShow=true;
        boolean floating=nav instanceof FloatingNav && ((FloatingNav)nav).floating();
        int room=nav instanceof FloatingNav?ui.dp(((FloatingNav)nav).shadowRoomDp()):ui.dp(16);
        int screen=activity.getResources().getDisplayMetrics().widthPixels;
        int width=Math.max(1,screen-(floating?ui.dp(42)-room*2:0));
        int safe=0;
        if(Build.VERSION.SDK_INT>=30){
            WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();
            if(insets!=null)safe=insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        }
        int bottom=floating?Math.max(ui.dp(21),safe):0;
        Window w=dialog.getWindow();WindowManager.LayoutParams lp=w.getAttributes();
        lp.width=width;lp.height=ui.dp(62)+room*2+(floating?0:safe);
        lp.y=Math.max(0,bottom-room);lp.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;
        w.setAttributes(lp);lastWidth=width;lastBottom=bottom;
        if(nav instanceof FloatingNav){
            FloatingNav dock=(FloatingNav)nav;
            // 玻璃关闭时 Material 自己会画底层，这里不能再叠一层窗口背景
            w.setBackgroundDrawable(dock.hasGlass()
                    ? capsuleBackground(ui.surface())
                    : new ColorDrawable(0));
        }
        w.getDecorView().setPadding(0,0,0,0);
        try{dialog.show();appearance(Build.VERSION.SDK_INT>=31&&manager.isCrossWindowBlurEnabled());}
        catch(RuntimeException ignored){pendingShow=false;}
    }
    private void hide(){pendingShow=false;if(nav instanceof FloatingNav)((FloatingNav)nav).glassVisible(false);if(dialog.isShowing())dialog.hide();}
    private void dispose() {
        if(destroyed)return;destroyed=true;pendingShow=false;
        if(nav instanceof FloatingNav)((FloatingNav)nav).glassVisible(false);
        if(Build.VERSION.SDK_INT>=31 && blurListener!=null)manager.removeCrossWindowBlurEnabledListener(blurListener);
        dialog.dismiss();
    }
}
