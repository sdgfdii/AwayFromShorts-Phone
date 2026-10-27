package com.afs.guard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Vector miniature of this app. Its dock previews the selected page transition without changing data. */
final class ThemePreviewView extends View {
    private AppearanceConfig config;
    private Boolean forceDark;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final boolean interactive;
    private boolean split;
    private boolean thumbnail;
    private int selected,previous;
    private float progress=1;
    private ValueAnimator animator;
    ThemePreviewView(Context c,AppearanceConfig config,Boolean dark,boolean interactive){
        super(c);this.config=config.copy();forceDark=dark;this.interactive=interactive;
        setContentDescription(interactive?"主题实时预览，点击底栏体验切换":"主题选项预览");
        setImportantForAccessibility(interactive?IMPORTANT_FOR_ACCESSIBILITY_YES:IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(interactive);setClickable(interactive);
    }
    void split(boolean value){split=value;invalidate();}
    void thumbnail(){thumbnail=true;invalidate();}
    void color(int value){config.selectColor(value);invalidate();}
    void update(AppearanceConfig c){config=c.copy();invalidate();}
    int selected(){return selected;}
    void selected(int value){selected=Math.max(0,Math.min(4,value));invalidate();}
    @Override protected void onDraw(Canvas canvas){
        canvas.save();canvas.scale(getWidth()/96f,getHeight()/(thumbnail?66f:160f));
        if(thumbnail)canvas.translate(0,-94);
        boolean dark=forceDark!=null?forceDark:ThemeStore.dark(getContext());
        drawScreen(canvas,dark);
        if(split){canvas.save();Path path=new Path();path.moveTo(67,thumbnail?94:0);path.lineTo(96,thumbnail?94:0);path.lineTo(96,160);path.lineTo(29,160);path.close();canvas.clipPath(path);drawScreen(canvas,true);canvas.restore();}
        canvas.restore();
    }
    private void box(Canvas c,float x,float y,float w,float h,float radius,int color){p.setColor(color);p.setStyle(Paint.Style.FILL);rect.set(x,y,x+w,y+h);c.drawRoundRect(rect,radius,radius,p);}
    private void drawScreen(Canvas c,boolean dark){
        AppearancePalette a=ThemeStore.palette(config,dark,ThemeStore.systemSeed(getContext()));
        box(c,0,0,96,160,14,a.bg);
        if(config.amap()){p.setShader(new android.graphics.LinearGradient(0,0,0,55,dark?0xFF173047:0xFFDFF3FF,a.bg,android.graphics.Shader.TileMode.CLAMP));rect.set(0,0,96,160);c.drawRoundRect(rect,14,14,p);p.setShader(null);}
        box(c,7,10,36,5,2.5f,a.text2);box(c,80,9,9,9,4.5f,a.surface);
        c.save();c.clipRect(0,23,96,141);
        if(progress<1){
            if("fade".equals(config.animation)){c.saveLayerAlpha(0,23,96,141,(int)(255*(1-progress)));page(c,previous,a);c.restore();}
            else {c.save();c.translate((previous<selected?-1:1)*96*progress,0);page(c,previous,a);c.restore();}
        }
        if("fade".equals(config.animation)&&progress<1){c.saveLayerAlpha(0,23,96,141,(int)(255*progress));page(c,selected,a);c.restore();}
        else {c.save();if(progress<1)c.translate((previous<selected?1:-1)*96*(1-progress),0);page(c,selected,a);c.restore();}
        c.restore();
        boolean glass=config.floating&&!"none".equals(config.glass);
        int fill=glass?AppearancePaletteBlend.mix(a.surface,a.bg,.3f):a.surface;
        float x=config.floating?5:0,y=config.floating?139:136,w=config.floating?86:96,h=config.floating?16:24;
        box(c,x,y,w,h,config.floating?8:0,fill);
        if(glass){p.setColor(dark?0x55FFFFFF:0xAFFFFFFF);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(.5f);rect.set(x,y,x+w,y+h);c.drawRoundRect(rect,8,8,p);}
        float slot=w/5;box(c,x+selected*slot+1,y+1,slot-2,h-2,7,a.selection);
        for(int i=0;i<5;i++){int color=i==selected?a.accent:a.text2;if(config.amap()){c.save();c.translate(x+i*slot+slot/2-3,y+2);c.scale(6f/28,6f/28);AmapStyle.glyph(c,p,new String[]{"打卡","记忆","守卫","刷题","冻结"}[i],color,i==selected);c.restore();}else box(c,x+i*slot+slot/2-2.5f,y+3,5,5,2,color);box(c,x+i*slot+slot/2-4,y+h-5,8,1.5f,1,color);}
    }
    private void page(Canvas c,int page,AppearancePalette a){
        if(page==0){
            box(c,7,25,82,94,10,a.surface);box(c,14,33,33,4,2,a.text);
            p.setColor(a.surface2);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6);c.drawCircle(48,74,20,p);
            p.setColor(a.accent);rect.set(28,54,68,94);c.drawArc(rect,-90,230,false,p);
            box(c,35,70,26,7,3,a.text2);box(c,28,105,40,4,2,a.accent);
            box(c,7,124,82,15,7,a.surface);
        }else if(page==1){
            box(c,7,25,82,13,6,a.surface2);
            for(int i=0;i<3;i++){float y=43+i*33;box(c,7,y,82,28,8,a.surface);box(c,13,y+6,34,4,2,a.text);box(c,13,y+15,62,7,3,a.accent);}
        }else if(page==2){
            box(c,7,25,82,37,10,a.surface);box(c,13,32,28,4,2,a.text);box(c,66,31,15,6,3,a.selection);
            box(c,13,43,53,3,1.5f,a.text2);box(c,13,51,44,3,1.5f,a.text3);
            for(int i=0;i<4;i++){float x=7+(i%2)*43,y=69+(i/2)*31;box(c,x,y,39,27,8,a.surface);if(config.amap()){c.save();c.translate(x+14,y+5);c.scale(11f/28,11f/28);AmapStyle.glyph(c,p,new String[]{"目标管理","打卡历史","屏幕统计","守卫"}[i],a.text,false);c.restore();}else box(c,x+14,y+5,11,11,5.5f,a.accent);box(c,x+8,y+20,23,2,1,a.text2);}
        }else if(page==3){
            box(c,7,25,82,27,8,a.surface);box(c,14,32,22,8,2,a.text);box(c,14,44,52,2,1,a.text3);
            box(c,7,57,82,14,7,a.accent);
            for(int i=0;i<3;i++){float y=77+i*20;box(c,7,y,82,16,6,a.surface);box(c,13,y+5,37,3,1.5f,a.text2);box(c,65,y+5,15,5,2,a.selection);}
        }else{
            for(int i=0;i<3;i++){float y=25+i*36;box(c,7,y,82,31,8,a.surface);box(c,13,y+6,28,3,1.5f,a.text2);box(c,13,y+15,69,3,1.5f,a.surface2);box(c,24+i*5,y+15,28,3,1.5f,a.accent);box(c,13,y+23,13,3,1.5f,a.text3);}
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!interactive)return false;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN)return e.getY()>getHeight()*.82f;
        if(e.getActionMasked()==MotionEvent.ACTION_UP){performClick();int next=Math.max(0,Math.min(4,(int)(e.getX()/getWidth()*5)));select(next);return true;}
        return true;
    }
    private void select(int next){
        if(next==selected)return;if(animator!=null)animator.cancel();previous=selected;selected=next;
        animator=ValueAnimator.ofFloat(0,1);animator.setDuration(android.os.Build.VERSION.SDK_INT<26||ValueAnimator.areAnimatorsEnabled()?260:0);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        animator.addUpdateListener(v->{progress=(Float)v.getAnimatedValue();invalidate();});animator.start();
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){if(animator!=null)animator.cancel();super.onDetachedFromWindow();}
}

final class AppearancePaletteBlend {
    private AppearancePaletteBlend(){}
    static int mix(int a,int b,float amount){
        float keep=1-amount;int r=Math.round(((a>>16)&255)*keep+((b>>16)&255)*amount);
        int g=Math.round(((a>>8)&255)*keep+((b>>8)&255)*amount),bl=Math.round((a&255)*keep+(b&255)*amount);
        return 0xFF000000|(r<<16)|(g<<8)|bl;
    }
}
