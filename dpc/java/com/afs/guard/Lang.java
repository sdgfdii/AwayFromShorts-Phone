package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 界面语言。权威来源只有一处：本机偏好（{@link #mode}）。
 *
 * 现状：全部界面文案已经包成 {@code T.s("...")}，翻译表是
 * assets/i18n_en.txt（1875 条，覆盖率 100%，用 tools/i18n.py check 校验）。
 * 切语言只需 {@link #save} 之后 recreate()，不重启进程。
 *
 * 例外：{@code static final} 常量、JSON/prefs 的键、日志和逻辑比较串故意没包 ——
 * 那些是标识不是文案，包了会把判断条件也翻掉。
 */
public final class Lang {

    static final String SP = "afs_lang";
    static final String KEY = "mode";

    public static final String SYSTEM = "system";
    public static final String ZH = "zh";
    public static final String EN = "en";

    /** 底栏五个标签的中文原文；英文由翻译表给出，顺序与 FloatingNav 的页序一致。 */
    private static final String[] NAV = {"记忆", "刷题", "冻结", "打卡", "守卫"};

    private Lang() {
    }

    public static String mode(Context c) {
        String value = c.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY, SYSTEM);
        return ZH.equals(value) || EN.equals(value) ? value : SYSTEM;
    }

    public static boolean save(Context c, String mode) {
        String value = ZH.equals(mode) || EN.equals(mode) ? mode : SYSTEM;
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY, value).commit();
    }

    public static boolean english(Context c) {
        String mode = mode(c);
        if (EN.equals(mode)) return true;
        if (ZH.equals(mode)) return false;
        return "en".equals(Locale.getDefault().getLanguage());
    }

    /**
     * 旧的两参写法。现在译文都进了 assets/i18n_en.txt，优先用它；
     * 表里没有时才退回调用处手写的英文，避免改了表这边不同步。
     */
    public static String t(Context c, String zh, String en) {
        if (!english(c)) {
            return zh;
        }
        String value = T.en(zh);
        return value.equals(zh) ? en : value;
    }

    /** 底栏五个标签，顺序与 FloatingNav 的页序一致。 */
    public static String[] nav(Context c) {
        boolean en = english(c);
        String[] out = new String[NAV.length];
        // 不能用 T.s：它读进程级 english 开关，静态初始化时可能还没就位。
        for (int i = 0; i < NAV.length; i++) out[i] = en ? T.en(NAV[i]) : NAV[i];
        return out;
    }

    public static String modeName(Context c) {
        String mode = mode(c);
        if (EN.equals(mode)) return "English";
        if (ZH.equals(mode)) return T.s("中文");
        return t(c, T.s("跟随系统"), "Follow system");
    }
}
