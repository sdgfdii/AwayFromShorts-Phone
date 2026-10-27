package com.afs.guard;

/**
 * Review-plan presets. Pure derived values: nothing here is persisted, and the label shown in the UI
 * is always recomputed from the actual numbers, so a hand-edited deck reads as "custom" immediately.
 */
public final class MemoryPlan {
    public static final String CUSTOM = "custom";
    private MemoryPlan() { }
    public static final class Preset {
        public final String id, label, hint;
        public final int newLimit, reviewLimit, target, maxInterval;
        public final double retention;
        Preset(String id, String label, String hint, int newLimit, int reviewLimit, int target,
               double retention, int maxInterval) {
            this.id = id; this.label = label; this.hint = hint;
            this.newLimit = newLimit; this.reviewLimit = reviewLimit; this.target = target;
            this.retention = retention; this.maxInterval = maxInterval;
        }
    }
    private static final Preset[] PRESETS = {
        new Preset("light", "轻量", "每天 10 分钟以内，间隔更长", 5, 30, 10, 0.85, 365),
        new Preset("standard", "标准", "与旧版默认一致", 10, 50, 20, 0.90, 36500),
        new Preset("intense", "强化", "记忆率更高，复习更密", 15, 80, 30, 0.93, 3650),
        new Preset("exam", "考前冲刺", "只排 30 天内，短间隔高频", 20, 150, 50, 0.95, 30)
    };
    public static Preset[] presets() { return PRESETS.clone(); }
    public static Preset preset(String id) {
        if (id == null) return null;
        for (Preset p : PRESETS) if (p.id.equals(id)) return p;
        return null;
    }
    public static String match(int newLimit, int reviewLimit, int target, double retention, int maxInterval) {
        for (Preset p : PRESETS)
            if (p.newLimit == newLimit && p.reviewLimit == reviewLimit && p.target == target
                    && p.maxInterval == maxInterval && Math.abs(p.retention - retention) < 1e-9)
                return p.id;
        return CUSTOM;
    }
    public static String label(String id) {
        Preset p = preset(id);
        return p == null ? "自定义" : p.label;
    }
}
