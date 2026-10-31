package com.afs.guard;

import android.content.Context;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.Log;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Glyph refraction only. LiveNavWindow confines backdrop blur to the dock window. */
final class NavGlassRenderer {
    private final View glyphs;
    private final Api33 gpu;
    private final RectF lastLens=new RectF();
    private int lastWidth,lastHeight;
    private float lastLift=-1;
    private boolean visible,failed;
    // Cache immutable source, never mutable RuntimeShader instances across activities.
    private static String glyphSource;
    private static synchronized String source(Context context) throws java.io.IOException {
        if(glyphSource==null) {
            try(InputStream in=context.getAssets().open("dock_content.agsl");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int count;
                while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
                glyphSource=out.toString("UTF-8");
            }
        }
        return glyphSource;
    }
    static NavGlassRenderer create(Context context,View host,View nav,View glyphs,boolean dark) {
        if(Build.VERSION.SDK_INT<33||!glyphs.isHardwareAccelerated())return null;
        try{return new NavGlassRenderer(context,glyphs);}
        catch(RuntimeException|java.io.IOException e){Log.e("AFSNavGlass","Glyph lens unavailable; keeping live window glass",e);return null;}
    }
    private NavGlassRenderer(Context context,View glyphs) throws java.io.IOException {
        this.glyphs=glyphs;
        gpu=new Api33(source(context),context.getResources().getDisplayMetrics().density,
                "ultra".equals(ThemeStore.load(context).glass));
    }
    boolean active(){return !failed;}
    void setVisible(boolean next){
        if(visible==next)return;visible=next;lastLift=-1;
        if(!visible)glyphs.setRenderEffect(null);
    }
    void update(RectF bar,RectF lensInNav,RectF localLens,float lift){
        if(!visible||failed||!glyphs.isAttachedToWindow())return;
        int width=glyphs.getWidth(),height=glyphs.getHeight();if(width<=0||height<=0)return;
        if(lastLens.equals(localLens)&&lastWidth==width&&lastHeight==height&&Math.abs(lastLift-lift)<.0001f)return;
        try{gpu.update(glyphs,localLens,lift,width,height);lastLens.set(localLens);lastWidth=width;lastHeight=height;lastLift=lift;}
        catch(RuntimeException e){failed=true;glyphs.setRenderEffect(null);Log.e("AFSNavGlass","Glyph lens disabled after rendering failure",e);}
    }
    private static final class Api33 {
        private final RuntimeShader content;
        Api33(String source,float density,boolean ultra){
            content=new RuntimeShader(source);content.setFloatUniform("density",density);content.setFloatUniform("ultra",ultra?1f:0f);
        }
        void update(View glyphs,RectF lens,float lift,int width,int height){
            if(lift<=.0001f){glyphs.setRenderEffect(null);return;}
            content.setFloatUniform("lens",lens.left,lens.top,lens.right,lens.bottom);
            content.setFloatUniform("lift",lift);content.setFloatUniform("bounds",(float)width,(float)height);
            glyphs.setRenderEffect(RenderEffect.createRuntimeShaderEffect(content,"glyphs"));
        }
    }
}
