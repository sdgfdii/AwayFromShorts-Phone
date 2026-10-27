package com.afs.guard;

/** 底栏从左到右的页面顺序；滑页手势与底栏共用这一份顺序，避免两处各写一套。纯逻辑，无 Android 依赖。 */
public final class TabOrder {
    /** 视觉顺序：打卡、记忆、守卫、刷题、冻结（值为 PAGES 下标）。 */
    public static final int[] ORDER = {3, 0, 4, 1, 2};

    private TabOrder() { }

    /** 视觉槽位；不在底栏里的页面返回 -1。 */
    public static int slotOf(int page) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == page) return i;
        return -1;
    }

    /**
     * 相邻页面。direction = 1 表示页面向左滑（切到视觉右侧一页），-1 表示向右滑。
     * 到头返回 -1（不循环）。
     */
    public static int neighbor(int page, int direction) {
        int slot = slotOf(page);
        if (slot < 0 || direction == 0) return -1;
        int next = slot + (direction > 0 ? 1 : -1);
        return next < 0 || next >= ORDER.length ? -1 : ORDER[next];
    }
}
