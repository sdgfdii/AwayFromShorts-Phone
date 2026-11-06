package com.afs.guard;
import android.animation.*;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

final class SplashView extends FrameLayout {
    interface Done {void onDone();}
    private final GuardFrames frames;
    private ValueAnimator playback;
    private Done done;
    private boolean fired;
    private final Runnable policy=()->{if(!MotionPolicy.enabled(getContext()))finishNow();};
    SplashView(Context context){
        super(context);setBackgroundColor(Color.WHITE);setClickable(true);
        frames=new GuardFrames(context);frames.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size=Math.min(new Ui(context).dp(300),(int)(getResources().getDisplayMetrics().widthPixels*.8f));
        addView(frames,new LayoutParams(size,size,Gravity.CENTER));
    }
    void play(Done callback){
        stop();done=callback;fired=false;MotionPolicy.add(getContext(),policy);
        if(!MotionPolicy.enabled(getContext())||frames.clip==null){finishNow();return;}
        int duration=Math.max(1,frames.clip.duration());
        frames.position=0;frames.invalidate();
        playback=ValueAnimator.ofInt(0,duration-1);
        playback.setDuration(MotionPolicy.duration(getContext(),duration));
        playback.setInterpolator(new LinearInterpolator());
        playback.addUpdateListener(animation->{frames.position=(Integer)animation.getAnimatedValue();frames.invalidate();});
        playback.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator animation){fire();}
        });
        playback.start();
    }
    private void fire(){if(fired)return;fired=true;Done callback=done;done=null;if(callback!=null)callback.onDone();}
    private void cancelPlayback(){
        if(playback!=null){playback.removeAllListeners();playback.removeAllUpdateListeners();playback.cancel();playback=null;}
    }
    private void finishNow(){cancelPlayback();frames.position=0;frames.invalidate();fire();}
    void stop(){done=null;fired=true;MotionPolicy.remove(policy);cancelPlayback();animate().cancel();}
    @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
    private static final class GuardFrames extends View {
        private Movie clip;
        private int position;
        GuardFrames(Context context){
            super(context);setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            try(java.io.InputStream input=context.getAssets().open("startup/nailong-guard.gif")){
                clip=Movie.decodeStream(input);
            }catch(java.io.IOException error){android.util.Log.w("AFS","Startup animation unavailable",error);}
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);if(clip==null)return;
            float scale=Math.min(getWidth()/(float)clip.width(),getHeight()/(float)clip.height());
            int saved=canvas.save();
            canvas.translate((getWidth()-clip.width()*scale)/2,(getHeight()-clip.height()*scale)/2);
            canvas.scale(scale,scale);clip.setTime(position);clip.draw(canvas,0,0);canvas.restoreToCount(saved);
        }
    }
}
