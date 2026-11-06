package com.afs.guard;

/**
 * 底栏显示规则（纯逻辑，不碰 Android，可以在离线回归里直接断言）。
 *
 * 规则只有一条：五个主标签（打卡、记忆、守卫、刷题、冻结）里的首页挂底栏，
 * 其余都是子页面 —— 卡管理、卡设置、卡片列表、编辑器、统计曲线、练习页，
 * 底下再亮一条主标签栏既回不去也容易误触，一律不挂。
 */
final class NavPolicy {
    private NavPolicy() { }

    /** 记忆模块：只有首页（闪卡）挂底栏。 */
    static boolean memoryShowsNav(boolean managementMode, String page) {
        return !managementMode && "home".equals(page);
    }

    /** 刷题模块：只有首页挂底栏。 */
    static boolean quizShowsNav(boolean managementMode, String page) {
        return !managementMode && "home".equals(page);
    }

    /** 打卡模块：只有打卡首页挂底栏；卡管理带进来的（目标卡、禁忌卡、外观、字体）都不挂。 */
    static boolean checkinShowsNav(boolean manage, String open) {
        return !manage && (open == null || open.isEmpty());
    }
}
