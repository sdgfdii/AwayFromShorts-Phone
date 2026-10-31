package com.afs.guard;

/** Offline checks for the pure swipe decision used by SwipeCardView (vertical: up = remember). */
public final class MemorySwipeTest {
    private static int count;
    private static final float MIN_DY = 35f, COMMIT_DY = 283f, COMMIT_V = 2800f;
    private static void check(boolean value, String message) { count++; if (!value) throw new AssertionError(message); }
    private static int decide(float dx, float dy, float vy, boolean up, boolean down) {
        return MemorySwipe.decide(dx, dy, vy, MIN_DY, COMMIT_DY, COMMIT_V, up, down);
    }
    public static void main(String[] args) {
        check(decide(20, -300, 0, true, true) == MemorySwipe.UP, "up past distance");
        check(decide(20, 300, 0, true, true) == MemorySwipe.DOWN, "down past distance");
        check(decide(0, -200, 0, true, true) == MemorySwipe.NONE, "short drag without speed");
        check(decide(0, -100, -5000, true, true) == MemorySwipe.UP, "fling up");
        check(decide(0, 100, 5000, true, true) == MemorySwipe.DOWN, "fling down");
        check(decide(0, -100, -1000, true, true) == MemorySwipe.NONE, "slow short drag");
        check(decide(280, -300, 0, true, true) == MemorySwipe.NONE, "horizontal wins over vertical");
        check(decide(20, -300, 0, false, true) == MemorySwipe.NONE, "up direction disabled");
        check(decide(20, 300, 0, true, false) == MemorySwipe.NONE, "down direction disabled");
        check(decide(0, -10, 0, true, true) == MemorySwipe.NONE, "below minimum dy");
        check(decide(0, -COMMIT_DY, 0, true, true) == MemorySwipe.UP, "exact distance commits");
        check(decide(0, COMMIT_DY, 0, true, true) == MemorySwipe.DOWN, "exact distance commits down");
        check(decide(0, COMMIT_DY * 0.35f, COMMIT_V, true, true) == MemorySwipe.DOWN, "fling minimum distance");
        check(MemorySwipe.rating(MemorySwipe.UP) == 3, "up means remembered");
        check(MemorySwipe.rating(MemorySwipe.DOWN) == 1, "down means forgot");
        check(MemorySwipe.rating(MemorySwipe.NONE) == 0, "no direction has no rating");
        check(MemorySwipe.vertical(20, 300, MIN_DY) && !MemorySwipe.vertical(280, 300, MIN_DY),
                "axis ratio guard");
        System.out.println("Memory swipe: " + count + " assertions passed");
    }
}
