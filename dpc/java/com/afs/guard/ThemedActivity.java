package com.afs.guard;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;

/** Global font scaling, typography, and refreshing cached pages after appearance changes. */
public class ThemedActivity extends Activity {
    private String appearanceSignature;
    private View decor;
    private final Runnable applyTypography=()->{if(decor!=null)ThemeStore.typography(decor);};
    /**
     * 同一轮布局里可能连续来好几次回调；合并成一次挂到下一帧再走，
     * 避免每次 layout 都把整棵 View 树按字体/网格遍历两遍（切页时最明显的白工）。
     */
    private final ViewTreeObserver.OnGlobalLayoutListener typography=()->{
        if(decor==null)return;
        decor.removeCallbacks(applyTypography);
        decor.post(applyTypography);
    };
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(ThemeStore.context(base));}
    @Override protected void onCreate(Bundle saved){
        int theme=getResources().getIdentifier(ThemeStore.dark(this)?"NavThemeDark":"NavTheme","style",getPackageName());
        if(theme!=0)setTheme(theme);appearanceSignature=ThemeStore.signature(this);super.onCreate(saved);FloatingNav.fullscreen(this);
        // 切页只做页面内容滑动：关掉系统的窗口淡入/缩放，否则过渡时能看见后面的旧页面
        getWindow().setWindowAnimations(0);
    }
    @Override protected void onPostCreate(Bundle saved){
        super.onPostCreate(saved);decor=getWindow().getDecorView();decor.getViewTreeObserver().addOnGlobalLayoutListener(typography);ThemeStore.typography(decor);
    }
    @Override protected void onResume(){GuardClock.pin(this);super.onResume();if(!isFinishing()&&!ThemeStore.signature(this).equals(appearanceSignature))recreate();}
    /** 页面自己已经按新主题重建过了：记下新签名，免得下一次 onResume 再整页 recreate 闪一下。 */
    protected final void markAppearanceApplied(){appearanceSignature=ThemeStore.signature(this);}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);LiveNavWindow.focusChanged(this);}
    @Override protected void onDestroy(){
        if(decor!=null){
            decor.removeCallbacks(applyTypography);
            if(decor.getViewTreeObserver().isAlive())decor.getViewTreeObserver().removeOnGlobalLayoutListener(typography);
        }
        super.onDestroy();
    }
}
