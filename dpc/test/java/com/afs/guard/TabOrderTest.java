package com.afs.guard;

/** TabOrder 纯逻辑：底栏顺序的唯一事实来源。 */
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
        // 全局滑页手势已删：neighbor() 不再存在，切页只走底栏
        System.out.println("TabOrder: " + passed + " assertions passed");
    }
}
