package com.afs.guard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.View;

/** Finite Canvas confirmation; paths and paints are retained, with no bitmap or idle loop. */
final class AchievementMark extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tick=new Path(),visibleTick=new Path();
    private final PathMeasure measure=new PathMeasure();
    private final RectF ring=new RectF();
    private final int accent,ink;
    private float progress=1f;
    private ValueAnimator drawing;
    private final Runnable policyChanged=()->{if(!MotionPolicy.enabled(getContext()))finishDrawing();};
    AchievementMark(Context c,Ui ui){
        super(c);accent=ui.accent();ink=ui.onAccent();
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription(T.s("成就已完成"));setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    void play(){
        finishDrawing();
        if(!MotionPolicy.enabled(getContext())||!isAttachedToWindow()||!isShown())return;
        progress=0;drawing=ValueAnimator.ofFloat(0,1);
        drawing.setDuration(MotionPolicy.duration(getContext(),Motion.ENTER));drawing.setInterpolator(Motion.OUT_SOFT);
        drawing.addUpdateListener(a->{progress=(Float)a.getAnimatedValue();invalidate();});drawing.start();
    }
    void finishDrawing(){
        if(drawing!=null){drawing.cancel();drawing.removeAllUpdateListeners();drawing=null;}
        progress=1;invalidate();
    }
    boolean drawingRunning(){return drawing!=null&&drawing.isRunning();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);float size=Math.min(w,h),cx=w/2f,cy=h/2f;
        ring.set(cx-size*.36f,cy-size*.36f,cx+size*.36f,cy+size*.36f);
        tick.reset();tick.moveTo(cx-size*.17f,cy);tick.lineTo(cx-size*.035f,cy+size*.13f);tick.lineTo(cx+size*.20f,cy-size*.14f);
        measure.setPath(tick,false);
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float size=Math.min(getWidth(),getHeight());
        paint.setStyle(Paint.Style.FILL);paint.setColor(accent);c.drawCircle(getWidth()/2f,getHeight()/2f,size/2f,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(size*.035f);paint.setColor(ink);paint.setAlpha(72);
        c.drawArc(ring,-90,360*progress,false,paint);
        paint.setAlpha(255);paint.setStrokeWidth(size*.055f);
        visibleTick.reset();measure.getSegment(0,measure.getLength()*Math.max(0,Math.min(1,(progress-.2f)/.8f)),visibleTick,true);
        c.drawPath(visibleTick,paint);
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();MotionPolicy.add(getContext(),policyChanged);}
    @Override protected void onDetachedFromWindow(){finishDrawing();MotionPolicy.remove(policyChanged);super.onDetachedFromWindow();}
    @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);if(visibility!=VISIBLE)finishDrawing();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE)finishDrawing();}
}
