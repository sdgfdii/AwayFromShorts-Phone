package com.afs.guard;

/**
 * 打卡目标（界面用的那份数据）。
 *
 * 以前它是 CheckinStore 的内部类 —— 那个 Store 管的是"PC 是学习台时手机拉目标、
 * 打完卡排队等 PC 记账"的老链路。这条链路已经整体退役：手机自己就是学习台，
 * 数据源是 {@link StudyStore}，上传走 {@link Hub} 的 checkin-ledger.json。
 *
 * 类型留在这里只当 DTO 用，不承载任何存储/同步语义。
 */
final class Goal {

    String id = "";
    String name = "";
    String unit = "个";
    double total;
    double done;
    double suggest;
    boolean todayHit;
    /** 本地刚打过卡（远端还没记账），用来立刻显示"已打"。 */
    boolean localHit;

    boolean hit() {
        return todayHit || localHit;
    }
}
