package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * 打卡日记：一天一条纯文本，可写可不写。
 *
 * 存在独立的 SharedPreferences 里（不进 study.json），原因：
 * 日记长、写得随意，混进打卡账本会让同步冲突变成整本覆盖；
 * 而且它本来就不该跟着学习记录一起上传。
 */
final class JournalStore {

    private static final String FILE = "afs_journal";
    private static final String K_ENABLED = "enabled";
    private static final String PREFIX = "d:";
    /** 一天最多存这么多字；再多就不是"日记"了，是长文。 */
    static final int LIMIT = 2000;

    private JournalStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 默认开：这个开关只决定「日历里点开某天有没有写作入口」，不写也不影响打卡。 */
    static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ENABLED, on).apply();
    }

    static String load(Context c, String date) {
        if (date == null || date.isEmpty()) {
            return "";
        }
        return sp(c).getString(PREFIX + date, "");
    }

    static boolean has(Context c, String date) {
        return !load(c, date).isEmpty();
    }

    /** 空文本等于删除 —— 不想写就把内容清空，不留一条空记录。 */
    static void save(Context c, String date, String text) {
        if (date == null || date.isEmpty()) {
            return;
        }
        String value = text == null ? "" : text.trim();
        if (value.length() > LIMIT) {
            value = value.substring(0, LIMIT);
        }
        if (value.isEmpty()) {
            sp(c).edit().remove(PREFIX + date).apply();
        } else {
            sp(c).edit().putString(PREFIX + date, value).apply();
        }
    }

    /** 某个月写过几篇（日历页的月度小结用）。 */
    static int countInMonth(Context c, int year, int month) {
        String monthPrefix = String.format(java.util.Locale.US, "%04d-%02d-", year, month);
        int count = 0;
        for (Map.Entry<String, ?> entry : sp(c).getAll().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX)) {
                continue;
            }
            String date = entry.getKey().substring(PREFIX.length());
            Object value = entry.getValue();
            if (date.startsWith(monthPrefix) && value instanceof String && !((String) value).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    static int total(Context c) {
        int count = 0;
        for (Map.Entry<String, ?> entry : sp(c).getAll().entrySet()) {
            Object value = entry.getValue();
            if (entry.getKey().startsWith(PREFIX) && value instanceof String && !((String) value).isEmpty()) {
                count++;
            }
        }
        return count;
    }
}
