package com.afs.guard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.widget.FrameLayout;

/**
 * Container that turns a vertical drag over one flashcard into a direction callback.
 * Up = remembered, down = forgot. It never grades: the listener persists from MemoryActivity,
 * so swipe and buttons share one path. Math lives in {@link MemorySwipe}; this class does pixels,
 * touch and motion only.
 *
 * 卡片区域接管竖向手势（评分解锁后），页面本身仍可拖动卡片以外的区域滚动。
 */
public final class SwipeCardView extends FrameLayout {
    public interface Listener { void onSwipe(int direction); }
    private static final int FLY_OUT_MS = 140, SNAP_BACK_MS = 120;
    private static final float MIN_DY_DP = 10f, AXIS_MIN_DP = 24f, COMMIT_DP = 72f, COMMIT_VELOCITY_DP = 800f;

    private final float minDy, axisMinPx, commitMinPx, commitVelocity;
    private Listener listener;
    private long visualToken;
    private Runnable visualDone;
    private final Runnable policyChanged=()->{
        if(motion())return;Runnable done=visualDone;visualDone=null;
        visualToken++;animate().withEndAction(null).cancel();
        if(done!=null){setAlpha(0);done.run();}else reset();
    };
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();MotionPolicy.add(getContext(),policyChanged);}
    private void completeVisual(long token){if(token!=visualToken)return;Runnable done=visualDone;visualDone=null;if(done!=null)done.run();}
    private VelocityTracker velocity;
    private boolean enabled, allowUp = true, allowDown = true;
    private boolean tracking, dragging, armed, parentLocked;
    private int pointerId = -1;
    private float downX, downY, dx, dy;

    public SwipeCardView(Context c) {
        super(c);
        setClipChildren(false);
        Ui ui = new Ui(c);
        minDy = ui.dp(MIN_DY_DP);
        axisMinPx = ui.dp(AXIS_MIN_DP);
        commitMinPx = ui.dp(COMMIT_DP);
        commitVelocity = ui.dp(COMMIT_VELOCITY_DP);
    }
    public void setListener(Listener l) { listener = l; }
    /** Swipe only arms after the answer is revealed. */
    public void setSwipeEnabled(boolean value) {
        enabled = value;
        if (!value) {
            tracking = false; dragging = false; unlockParent(); recycleVelocity();
        }
    }
    /** up = remember, down = forget. Wrong input answers block "remember" but keep "forgot". */
    public void setDirections(boolean up, boolean down) { allowUp = up; allowDown = down; }

    /** Grading already happened synchronously; this is purely the visual exit. */
    public void flyOut(int direction) {
        if (direction == MemorySwipe.NONE) { reset(); return; }
        float target = direction * Math.max(getHeight(), 1) * 1.2f;
        if (!motion()) { setTranslationY(target); setAlpha(0); return; }
        animate().translationY(target).alpha(0).setDuration(FLY_OUT_MS).start();
    }
    /** 下滑之后的消散：原地淡出并轻微缩小，替代整块滑走。 */
    public void dissolve() {
        if (!motion()) { setAlpha(0); return; }
        animate().alpha(0f).scaleX(.96f).scaleY(.96f).setDuration(FLY_OUT_MS + 60).start();
    }
    public void flyOut(int direction,Runnable done) {
        visual(direction*Math.max(getHeight(),1)*1.2f,1,FLY_OUT_MS,done);
    }
    public void dissolve(Runnable done) { visual(getTranslationY(),.96f,FLY_OUT_MS+60,done); }
    private void visual(float target,float scale,long duration,Runnable done) {
        final long token=++visualToken;visualDone=done;
        animate().withEndAction(null).cancel();
        if(!motion()){setTranslationY(target);setAlpha(0);completeVisual(token);return;}
        animate().translationY(target).alpha(0).scaleX(scale).scaleY(scale)
            .setDuration(duration).withEndAction(()->completeVisual(token)).start();
    }
    /** Instant snap back; returns whether the card had actually moved. */
    public boolean reset() {
        boolean moved = getTranslationY() != 0 || getAlpha() != 1;
        visualDone=null;visualToken++;animate().withEndAction(null).cancel();
        setScaleX(1);setScaleY(1);setTranslationY(0); setAlpha(1); armed = false; dragging = false;
        return moved;
    }
    /** 下滑揭晓后平滑归位：把拖动留下的位移和透明度收回去，不评分。 */
    public void settle() {
        armed = false; dragging = false;
        if (!motion()) { setTranslationY(0); setAlpha(1); return; }
        animate().translationY(0).alpha(1f).setDuration(SNAP_BACK_MS).start();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!enabled) return false;
            downX = e.getRawX(); downY = e.getRawY(); dx = 0; dy = 0;
            pointerId = e.getPointerId(0); tracking = true;
            lockParent();
            recycleVelocity(); velocity = VelocityTracker.obtain(); velocity.addMovement(e);
            return false;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (tracking) cancelGesture();
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE && tracking) {
            if (e.findPointerIndex(pointerId) < 0) { cancelGesture(); return false; }
            dx = e.getRawX() - downX; dy = e.getRawY() - downY;
            if (MemorySwipe.vertical(dx, dy, axisMinPx)) {
                dragging = true;
                if (velocity != null) velocity.addMovement(e);
                applyDrag(dy);
                return true;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            tracking = false; lockParent(); recycleVelocity();
        }
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!tracking) return false;
        if (velocity != null) velocity.addMovement(e);
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            if (e.findPointerIndex(pointerId) < 0) { cancelGesture(); return true; }
            dx = e.getRawX() - downX; dy = e.getRawY() - downY;
            applyDrag(dy);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) { cancelGesture(); return true; }
        if (action == MotionEvent.ACTION_UP) { finish(); return true; }
        if (action == MotionEvent.ACTION_CANCEL) { cancelGesture(); return true; }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override protected void onDetachedFromWindow() {
        MotionPolicy.remove(policyChanged);visualDone=null;visualToken++;animate().withEndAction(null).cancel();
        super.onDetachedFromWindow();
        tracking = false; dragging = false; unlockParent(); recycleVelocity(); animate().cancel();
    }

    private void finish() {
        float velocityY = 0;
        if (velocity != null) { velocity.computeCurrentVelocity(1000); velocityY = velocity.getYVelocity(pointerId); }
        int direction = MemorySwipe.decide(dx, dy, velocityY, minDy, commitDy(), commitVelocity, allowUp, allowDown);
        tracking = false; dragging = false; unlockParent(); recycleVelocity();
        performClick();
        if (direction == MemorySwipe.NONE) { snapBack(); return; }
        if (listener != null) listener.onSwipe(direction);
    }
    public void cancelVisual() { cancelGesture(); }
    private void cancelGesture() {
        tracking = false; dragging = false; unlockParent(); recycleVelocity();
        reset();
    }
    private void applyDrag(float value) {
        float limit = Math.max(1f, getHeight());
        setTranslationY(Math.max(-limit, Math.min(limit, value)));
        float cd = Math.max(1f, commitDy());
        setAlpha(1f - Math.min(.5f, Math.abs(value) / (2f * cd)));
        boolean nowArmed = Math.abs(value) >= cd;
        if (nowArmed && !armed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        armed = nowArmed;
    }
    private void snapBack() {
        armed = false;
        if (!motion()) { setTranslationY(0); setAlpha(1); return; }
        animate().translationY(0).alpha(1).setDuration(SNAP_BACK_MS).start();
    }
    private float commitDy() { return Math.max(commitMinPx, getHeight() * 0.18f); }
    private void lockParent() {
        if (!parentLocked && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
            parentLocked = true;
        }
    }
    private void unlockParent() {
        if (parentLocked && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        parentLocked = false;
    }
    private void recycleVelocity() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }
    /** Same criteria as FloatingNav.motion(): the system-wide reduce-motion switch wins. */
    private boolean motion() { return MotionPolicy.enabled(getContext()); }
}
