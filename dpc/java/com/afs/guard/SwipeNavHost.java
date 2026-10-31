package com.afs.guard;

import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * 页面级左右滑动切页。只在子控件没有接管手势时生效：
 * 闪卡滑卡（SwipeCardView）、横向选卡条等会 requestDisallowInterceptTouchEvent(true)，
 * 这时本容器不切页，避免和卡片评分/横滑列表抢手势。
 */
public class SwipeNavHost extends FrameLayout {
    private static final float TRIGGER_DP = 56f;
    private static final float VELOCITY_DP = 900f;
    private static final float AXIS_RATIO = 1.4f;

    private final Activity activity;
    private final int page;
    private final float triggerPx, velocityPx, touchSlopPx;
    private VelocityTracker velocity;
    private float downX, downY;
    private boolean tracking, claimed, swiping;
    private int pointerId = -1;

    public SwipeNavHost(Activity activity, View content, int page) {
        super(activity);
        this.activity = activity;
        this.page = page;
        Ui ui = new Ui(activity);
        triggerPx = ui.dp(TRIGGER_DP);
        velocityPx = ui.dp(VELOCITY_DP);
        touchSlopPx = ViewConfiguration.get(activity).getScaledTouchSlop();
        addView(content, new LayoutParams(-1, -1));
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
        if (disallow) claimed = true;
        super.requestDisallowInterceptTouchEvent(disallow);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            tracking = true;
            pointerId = event.getPointerId(0);
            downX = event.getRawX();
            downY = event.getRawY();
            if (velocity == null) velocity = VelocityTracker.obtain();
            velocity.addMovement(event);
        } else if (tracking && velocity != null) {
            velocity.addMovement(event);
        }
        if (tracking && !claimed && !swiping && action == MotionEvent.ACTION_MOVE
                && horizontal(event.getRawX() - downX, event.getRawY() - downY)) {
            // 先让子控件看到这次 MOVE：HorizontalScrollView / 滑卡控件往往等到
            // 首个超过 touch slop 的 MOVE 才 requestDisallowInterceptTouchEvent(true)。
            boolean childHandled = super.dispatchTouchEvent(event);
            if (claimed || !tracking) return childHandled;   // 子控件在本次 MOVE 中接管
            swiping = true;
            cancelChild(event);
            return true;
        }
        // A committed swipe must cancel the child before UP can trigger its click.
        if (tracking && !claimed && !swiping && action == MotionEvent.ACTION_UP
                && horizontal(event.getRawX() - downX, event.getRawY() - downY)) {
            swiping = true;
            cancelChild(event);
        }
        boolean handled = swiping || super.dispatchTouchEvent(event);
        if (!tracking) return handled;
        if (action == MotionEvent.ACTION_UP) {
            float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
            float velocityX = 0;
            if (velocity != null) {
                velocity.computeCurrentVelocity(1000);
                velocityX = velocity.getXVelocity(pointerId);
            }
            if (!claimed && horizontal(dx, dy) && committed(dx, velocityX)) {
                int target = TabOrder.neighbor(page, dx < 0 ? 1 : -1);
                if (target >= 0) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    navigate(target);
                }
            }
            reset();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            reset();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            reset();
        }
        return handled;
    }

    private void cancelChild(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    private boolean horizontal(float dx, float dy) {
        return Math.abs(dx) >= Math.max(triggerPx, touchSlopPx * 3) && Math.abs(dx) >= Math.abs(dy) * AXIS_RATIO;
    }

    private boolean committed(float dx, float velocityX) {
        return Math.abs(dx) >= triggerPx || (Math.abs(velocityX) >= velocityPx && Math.abs(dx) >= triggerPx * 0.5f);
    }

    protected void navigate(int target) { FloatingNav.swipeNavigate(activity, page, target); }

    private void reset() {
        tracking = false;
        claimed = false;
        swiping = false;
        pointerId = -1;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }
}
