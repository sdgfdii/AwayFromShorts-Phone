package com.afs.guard;

import android.content.Context;
import android.graphics.RectF;
import android.graphics.BlendMode;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.Log;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** GPU lenses over the live page and live glyph RenderNodes. Never reads pixels to a bitmap. */
final class NavGlassRenderer {
    private final View host, nav, glyphs;
    private final float density;
    private final Api33 gpu;
    private final int[] navLocation = new int[2], hostLocation = new int[2];
    private final RectF lastBar = new RectF(), lastLens = new RectF();
    private final RectF pageBar = new RectF(), pageLens = new RectF();
    private float lastLift = -1;
    private boolean visible, failed;

    /**
     * 着色器编译（RuntimeShader + 两份 AGSL）是切页时最贵的一步；
     * 同一套外观只编译一次，进程内所有页面复用，别再每个 Activity 从头来一遍。
     */
    private static final java.util.HashMap<String,Api33> compiled = new java.util.HashMap<>();
    private static synchronized Api33 shared(Context context, float density, boolean dark, int tint, boolean ultra)
            throws java.io.IOException {
        String key = density + "|" + dark + "|" + tint + "|" + ultra;
        Api33 hit = compiled.get(key);
        if (hit == null) {
            hit = new Api33(read(context, "dock_backdrop.agsl"), read(context, "dock_content.agsl"),
                    density, dark, tint, ultra);
            if (compiled.size() > 6) compiled.clear();
            compiled.put(key, hit);
        }
        return hit;
    }

    static NavGlassRenderer create(Context context, View host, View nav, View glyphs, boolean dark) {
        if (Build.VERSION.SDK_INT < 33 || !host.isHardwareAccelerated()) return null;
        try { return new NavGlassRenderer(context, host, nav, glyphs, dark); }
        catch (RuntimeException | java.io.IOException e) {
            Log.e("AFSNavGlass", "GPU glass unavailable; keeping system glass", e);
            return null;
        }
    }

    private NavGlassRenderer(Context context, View host, View nav, View glyphs, boolean dark)
            throws java.io.IOException {
        this.host = host; this.nav = nav; this.glyphs = glyphs;
        density = context.getResources().getDisplayMetrics().density;
        gpu = shared(context, density, dark, ThemeStore.palette(context,dark).glassTint,
                "ultra".equals(ThemeStore.load(context).glass));
        Log.i("AFSNavGlass", "Live page and glyph shaders ready");
    }

    private static String read(Context c, String asset) throws java.io.IOException {
        try (InputStream in = c.getAssets().open(asset); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }

    boolean active() { return !failed; }

    void setVisible(boolean visible) {
        if (this.visible == visible) return;
        this.visible = visible;
        lastLift = -1;
        if (!visible && Build.VERSION.SDK_INT >= 33) {
            host.setRenderEffect(null); glyphs.setRenderEffect(null);
        }
    }

    void update(RectF bar, RectF lensInNav, RectF lensInGlyphs, float lift) {
        if (!visible || failed || !nav.isAttachedToWindow() || host.getWidth() == 0) return;
        nav.getLocationOnScreen(navLocation); host.getLocationOnScreen(hostLocation);
        float dx = navLocation[0]-hostLocation[0], dy = navLocation[1]-hostLocation[1];
        pageBar.set(bar); pageBar.offset(dx,dy);
        pageLens.set(lensInNav); pageLens.offset(dx,dy);
        if (lastBar.equals(pageBar) && lastLens.equals(pageLens) && Math.abs(lastLift-lift)<.0001f) return;
        try {
            gpu.update(host, glyphs, pageBar, pageLens, lensInGlyphs, lift);
            lastBar.set(pageBar); lastLens.set(pageLens); lastLift=lift;
        } catch (RuntimeException e) {
            failed=true; host.setRenderEffect(null); glyphs.setRenderEffect(null);
            Log.e("AFSNavGlass", "GPU glass disabled after rendering failure", e);
        }
    }

    private static final class Api33 {
        private final RuntimeShader backdrop, haze, content;
        private final RenderEffect blur;
        Api33(String pageSource, String glyphSource, float density, boolean dark,int tint,boolean ultra) {
            backdrop = new RuntimeShader(pageSource);haze=new RuntimeShader(pageSource);content = new RuntimeShader(glyphSource);
            for(RuntimeShader shader:new RuntimeShader[]{backdrop,haze}) {
                shader.setFloatUniform("density",density);shader.setFloatUniform("dark",dark?1f:0f);
                shader.setFloatUniform("ultra",ultra?1f:0f);
                shader.setFloatUniform("glassTint",((tint>>16)&255)/255f,((tint>>8)&255)/255f,(tint&255)/255f);
            }
            backdrop.setFloatUniform("hazePass",0);haze.setFloatUniform("hazePass",1);
            content.setFloatUniform("density",density);
            content.setFloatUniform("ultra",ultra?1f:0f);
            blur=RenderEffect.createBlurEffect(15.6f*density,15.6f*density,Shader.TileMode.CLAMP);
        }
        void update(View host, View glyphs, RectF bar, RectF lens, RectF localLens, float lift) {
            for(RuntimeShader shader:new RuntimeShader[]{backdrop,haze}) {
                shader.setFloatUniform("bar",bar.left,bar.top,bar.right,bar.bottom);
                shader.setFloatUniform("lens",lens.left,lens.top,lens.right,lens.bottom);
                shader.setFloatUniform("lift",lift);
            }
            RenderEffect clear=RenderEffect.createRuntimeShaderEffect(backdrop,"scene");
            RenderEffect frosted=RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(haze,"scene"),blur);
            host.setRenderEffect(RenderEffect.createBlendModeEffect(clear,frosted,BlendMode.SRC_OVER));
            if (lift>.0001f) {
                content.setFloatUniform("lens",localLens.left,localLens.top,localLens.right,localLens.bottom);
                content.setFloatUniform("lift",lift);
                content.setFloatUniform("bounds",(float)glyphs.getWidth(),(float)glyphs.getHeight());
                glyphs.setRenderEffect(RenderEffect.createRuntimeShaderEffect(content,"glyphs"));
            } else glyphs.setRenderEffect(null);
        }
    }
}
