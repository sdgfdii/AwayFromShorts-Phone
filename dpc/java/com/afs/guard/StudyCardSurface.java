package com.afs.guard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;

/** Shared card material. Answer reveal has one finite highlight; resting cards never redraw. */
final class StudyCardSurface extends LinearLayout {
    private final Ui ui;
    private final Paint sheen=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sheenMatrix=new Matrix();
    private ValueAnimator feedback;
    private float progress=1f;
    private final Runnable policyChanged=()->{if(!MotionPolicy.enabled(getContext()))stopFeedback();};
    StudyCardSurface(Context context){
        super(context);ui=new Ui(context);setOrientation(VERTICAL);
        setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(24));
        setBackground(background(ui,ui.surface(),ui.line()));
        setClipToOutline(true);setMinimumHeight(ui.dp(380));
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);
        if(w>0)sheen.setShader(new LinearGradient(-w*.35f,0,w*.35f,0,
                new int[]{0x00FFFFFF,0x26FFFFFF,0x00FFFFFF},null,Shader.TileMode.CLAMP));
    }
    void highlight(){
        stopFeedback();
        if(!isAttachedToWindow()||!isShown()||getWidth()==0||!MotionPolicy.enabled(getContext()))return;
        progress=0f;
        feedback=ValueAnimator.ofFloat(0f,1f);
        feedback.setDuration(MotionPolicy.duration(getContext(),Motion.ENTER));
        feedback.setInterpolator(Motion.OUT_SOFT);
        feedback.addUpdateListener(a->{progress=(Float)a.getAnimatedValue();invalidate();});
        feedback.start();
    }
    void stopFeedback(){
        if(feedback!=null){feedback.cancel();feedback.removeAllUpdateListeners();feedback=null;}
        if(progress!=1f){progress=1f;invalidate();}
    }
    boolean feedbackRunning(){return feedback!=null&&feedback.isRunning();}
    @Override protected void dispatchDraw(Canvas canvas){
        super.dispatchDraw(canvas);
        if(progress>=1f||sheen.getShader()==null)return;
        sheenMatrix.setTranslate(getWidth()*(-.4f+1.8f*progress),0);
        sheen.getShader().setLocalMatrix(sheenMatrix);
        canvas.drawRect(0,0,getWidth(),getHeight(),sheen);
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();MotionPolicy.add(getContext(),policyChanged);}
    @Override protected void onDetachedFromWindow(){stopFeedback();MotionPolicy.remove(policyChanged);super.onDetachedFromWindow();}
    @Override protected void onVisibilityChanged(android.view.View changed,int visibility){super.onVisibilityChanged(changed,visibility);if(visibility!=VISIBLE)stopFeedback();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE)stopFeedback();}
    static GradientDrawable background(Ui ui,int color,int stroke){GradientDrawable value=ui.round(color,22);value.setStroke(ui.dp(1),stroke);return value;}
}
