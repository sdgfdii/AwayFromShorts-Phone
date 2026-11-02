package com.afs.guard;

/** 底栏从左到右的页面顺序。纯逻辑，无 Android 依赖。 */
public final class TabOrder {
    /** 视觉顺序：打卡、记忆、守卫、刷题、冻结（值为 PAGES 下标）。 */
    public static final int[] ORDER = {3, 0, 4, 1, 2};

    private TabOrder() { }

    /** 视觉槽位；不在底栏里的页面返回 -1。 */
    public static int slotOf(int page) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == page) return i;
        return -1;
    }
}
