package com.afs.guard;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;

/** Same floating surface as check-in, with a slow light sweep for memory cards. */
final class StudyCardSurface extends LinearLayout {
    private final Ui ui;private final Paint light=new Paint(Paint.ANTI_ALIAS_FLAG);
    StudyCardSurface(Context context){super(context);ui=new Ui(context);setOrientation(VERTICAL);setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(24));
        setBackground(background(ui,ui.surface(),ui.line()));setElevation(ui.dp(12));setClipToOutline(true);setMinimumHeight(ui.dp(380));}
    static GradientDrawable background(Ui ui,int color,int stroke){GradientDrawable value=ui.round(color,22);value.setStroke(ui.dp(1),stroke);return value;}
    private boolean moving(){return !getContext().getSharedPreferences(Ui.SP,0).getBoolean("reduce_motion",false)
        &&(android.os.Build.VERSION.SDK_INT<26||android.animation.ValueAnimator.areAnimatorsEnabled());}
    @Override protected void dispatchDraw(Canvas canvas){super.dispatchDraw(canvas);if(!moving()||!isShown())return;
        float phase=(android.os.SystemClock.uptimeMillis()%4200)/4200f,x=-getWidth()+phase*getWidth()*3;
        light.setShader(new LinearGradient(x-110,0,x+110,getHeight(),new int[]{0,ui.dark?0x28FFFFFF:0x70FFFFFF,0},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,getWidth(),getHeight(),light);light.setShader(null);
        light.setColor(ui.dark?0x80FFFFFF:0x7095A6CC);float pulse=(float)(.5+.5*Math.sin(phase*Math.PI*2));
        for(int i=0;i<3;i++){float px=getWidth()*(.18f+i*.29f),py=getHeight()*(.17f+i*.23f),r=ui.dp(2+2*pulse);canvas.drawLine(px-r,py,px+r,py,light);canvas.drawLine(px,py-r,px,py+r,light);}
        postInvalidateDelayed(32);
    }
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE)invalidate();}
}
