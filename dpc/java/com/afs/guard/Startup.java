package com.afs.guard;

import android.content.Context;

/**
 * 冷启动预热。
 *
 * <p>第一条卡顿只出现在"装上之后第一次打开"：那时 ART 还没 JIT 过这些类，翻译表、调色板、
 * 字体、规则 JSON 全是第一次读。把它们放到**后台线程**先摸一遍，主线程的第一帧就不用等它们。
 *
 * <p>这里的每一件事都必须满足两个条件：幂等、且失败了不影响功能（各自带 try/catch）。
 * 预热只是提前把缓存填上，不承担任何正确性职责 —— 后台线程晚一点、或者干脆没跑，
 * 界面照样能自己算出来，只是慢一点。
 */
final class Startup {

    private Startup() {
    }

    static void prewarm(final Context c) {
        final Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        Thread worker = new Thread(new Runnable() {
            public void run() {
                long started = System.currentTimeMillis();
                // 翻译表：2300 多条，解析一次几十毫秒，第一次启动尤其明显
                try {
                    T.init(app);
                } catch (RuntimeException ignored) {
                }
                // 调色板：主题里每个颜色都要过一次 AppearancePalette，先建好缓存
                try {
                    ThemeStore.palette(app, Ui.isDark(app));
                } catch (RuntimeException ignored) {
                }
                // 字体：自定义字体是从 assets 里现读 TTF，第一次 setTypeface 会让那一帧掉帧
                try {
                    String family = ThemeStore.load(app).font;
                    if (family != null && !family.isEmpty()) {
                        ThemeStore.font(app, family, 0);
                        ThemeStore.font(app, family, 1);
                    }
                } catch (RuntimeException ignored) {
                }
                // 规则与打卡记录：首页、冻结页都要读，第一次是冷解析
                try {
                    RuleStore.all(app);
                } catch (RuntimeException ignored) {
                }
                try {
                    StudyStore.recs(app);
                } catch (RuntimeException ignored) {
                }
                android.util.Log.i(Guard.TAG, "prewarm done in "
                        + (System.currentTimeMillis() - started) + "ms");
            }
        }, "afs-prewarm");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }
}
