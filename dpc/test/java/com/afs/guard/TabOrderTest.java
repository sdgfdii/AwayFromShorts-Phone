package com.afs.guard;

/** TabOrder 纯逻辑：滑页方向与底栏顺序必须一致。 */
public final class TabOrderTest {
    private static int passed;
    private static void ok(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
    public static void main(String[] args) {
        ok(TabOrder.ORDER.length == 5, "底栏五页");
        ok(TabOrder.slotOf(3) == 0 && TabOrder.slotOf(0) == 1 && TabOrder.slotOf(4) == 2
                && TabOrder.slotOf(1) == 3 && TabOrder.slotOf(2) == 4, "槽位与底栏一致");
        ok(TabOrder.slotOf(9) == -1, "未知页面无槽位");
        ok(TabOrder.neighbor(3, 1) == 0, "打卡左滑到记忆");
        ok(TabOrder.neighbor(0, 1) == 4, "记忆左滑到守卫");
        ok(TabOrder.neighbor(4, 1) == 1, "守卫左滑到刷题");
        ok(TabOrder.neighbor(1, 1) == 2, "刷题左滑到冻结");
        ok(TabOrder.neighbor(2, 1) == -1, "冻结已是最后一页");
        ok(TabOrder.neighbor(3, -1) == -1, "打卡已是第一页");
        ok(TabOrder.neighbor(2, -1) == 1, "冻结右滑回刷题");
        ok(TabOrder.neighbor(1, -1) == 4, "刷题右滑回守卫");
        ok(TabOrder.neighbor(0, -1) == 3, "记忆右滑回打卡");
        ok(TabOrder.neighbor(4, 0) == -1 && TabOrder.neighbor(9, 1) == -1, "非法输入返回 -1");
        System.out.println("TabOrder: " + passed + " assertions passed");
    }
}
