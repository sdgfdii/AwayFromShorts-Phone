package com.afs.guard;

/**
 * Pure swipe decision logic: "how far counts, and which direction". No Android imports, so the exact
 * thresholds the UI uses can be tested offline; pixel conversion stays in {@link SwipeCardView}.
 *
 * 闪卡用上下滑：上滑 = 记住了（3），下滑 = 忘了（1）；2/4 仍只走按钮。
 */
public final class MemorySwipe {
    /** 方向值等于手指纵向位移的符号：上滑 dy&lt;0，下滑 dy&gt;0。 */
    public static final int NONE = 0, UP = -1, DOWN = 1;
    public static final int RATING_FORGET = 1, RATING_REMEMBER = 3;
    public static final float AXIS_RATIO = 1.3f;
    private MemorySwipe() { }
    public static boolean vertical(float dx, float dy, float minDy) {
        return Math.abs(dy) >= minDy && Math.abs(dy) >= Math.abs(dx) * AXIS_RATIO;
    }
    public static int rating(int direction) {
        if (direction == UP) return RATING_REMEMBER;
        if (direction == DOWN) return RATING_FORGET;
        return 0;
    }
    public static int decide(float dx, float dy, float velocityY,
                             float minDy, float commitDy, float commitVelocity,
                             boolean allowUp, boolean allowDown) {
        if (!vertical(dx, dy, minDy)) return NONE;
        int direction = dy < 0 ? UP : DOWN;
        boolean committed = Math.abs(dy) >= commitDy
                || (Math.abs(velocityY) >= commitVelocity && Math.abs(dy) >= commitDy * 0.35f);
        if (!committed) return NONE;
        if (direction == UP && !allowUp) return NONE;
        if (direction == DOWN && !allowDown) return NONE;
        return direction;
    }
}
