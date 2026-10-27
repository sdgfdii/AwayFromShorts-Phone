package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 工具箱的纯逻辑：计时格式、抽签拆行、文本统计与整理。
 *
 * 全部不碰 Android（{@link #loadItems}/{@link #saveItems} 除外），离线测试直接覆盖；
 * 三个工具页只负责画界面和调这里。零新增权限，不联网。
 */
public final class Toolbox {

    public static final int MAX_ITEMS = 200;
    public static final int MAX_ITEM_LENGTH = 60;
    public static final int MAX_TEXT = 20000;

    private static final String SP = "afs_guard";
    private static final String KEY_ITEMS = "toolbox_items";

    private Toolbox() {
    }

    /* ==================== 计时器 ==================== */

    /** 秒数转时钟：65 → "01:05"，3600 → "1:00:00"；负数和 null 语义按 0。 */
    public static String clock(long seconds) {
        long total = Math.max(0L, seconds);
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long rest = total % 60L;
        if (hours > 0) {
            return hours + ":" + two(minutes) + ":" + two(rest);
        }
        return two(minutes) + ":" + two(rest);
    }

    /** 倒计时剩余秒数，夹在 [0,total]。 */
    public static long remain(long totalSeconds, long elapsedSeconds) {
        long total = Math.max(0L, totalSeconds);
        long elapsed = Math.max(0L, elapsedSeconds);
        return Math.max(0L, total - elapsed);
    }

    /* ==================== 随机抽签 ==================== */

    /**
     * 多行文本拆成选项：按行、trim、丢空行、去重（保留首次出现），
     * 单条截到 {@link #MAX_ITEM_LENGTH}，最多 {@link #MAX_ITEMS} 条。
     */
    public static List<String> splitItems(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) {
            return out;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String line : normalize(raw).split("\n")) {
            String item = clip(trimBlank(line), MAX_ITEM_LENGTH);
            if (item.isEmpty() || !seen.add(item)) {
                continue;
            }
            out.add(item);
            if (out.size() >= MAX_ITEMS) {
                break;
            }
        }
        return out;
    }

    /** 0..size-1；没有选项返回 -1。随机源可注入，方便离线测试。 */
    public static int pickIndex(int size, Random random) {
        if (size <= 0) {
            return -1;
        }
        if (random == null) {
            return 0;
        }
        return random.nextInt(size);
    }

    public static List<String> loadItems(Context context) {
        List<String> out = new ArrayList<String>();
        try {
            SharedPreferences preferences = context.getSharedPreferences(SP, Context.MODE_PRIVATE);
            JSONArray array = new JSONArray(preferences.getString(KEY_ITEMS, "[]"));
            for (int index = 0; index < array.length(); index++) {
                String item = trimBlank(array.optString(index, ""));
                if (!item.isEmpty()) {
                    out.add(clip(item, MAX_ITEM_LENGTH));
                }
                if (out.size() >= MAX_ITEMS) {
                    break;
                }
            }
        } catch (Exception ignored) {
            // 坏偏好就当没有选项，页面还能正常输入
        }
        return out;
    }

    public static void saveItems(Context context, List<String> items) {
        JSONArray array = new JSONArray();
        if (items != null) {
            for (String item : items) {
                array.put(item == null ? "" : item);
            }
        }
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString(KEY_ITEMS, array.toString()).apply();
    }

    /* ==================== 文本工具 ==================== */

    /** 字数：按码点算、不含空白（中文一个字算一个，emoji 也算一个）。 */
    public static int charCount(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (!blank(codePoint)) {
                count++;
            }
            index += Character.charCount(codePoint);
        }
        return count;
    }

    /** 非空行数（全角空格/不换行空格/零宽字符组成的行也算空行）。 */
    public static int lineCount(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (String line : normalize(text).split("\n")) {
            if (!blank(line)) {
                count++;
            }
        }
        return count;
    }

    public static String upper(String text) {
        return text == null ? "" : normalize(text).toUpperCase(Locale.ROOT);
    }

    public static String lower(String text) {
        return text == null ? "" : normalize(text).toLowerCase(Locale.ROOT);
    }

    /** 按行去重：去掉首尾空白后比较，保留第一次出现的那一行，空行丢弃。 */
    public static String dedupeLines(String text) {
        if (text == null) {
            return "";
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String line : normalize(text).split("\n")) {
            String item = trimBlank(line);
            if (!item.isEmpty()) {
                seen.add(item);
            }
        }
        return join(seen);
    }

    /** 按行排序（String.compareTo，稳定），空行丢弃。 */
    public static String sortLines(String text) {
        if (text == null) {
            return "";
        }
        List<String> lines = new ArrayList<String>();
        for (String line : normalize(text).split("\n")) {
            String item = trimBlank(line);
            if (!item.isEmpty()) {
                lines.add(item);
            }
        }
        Collections.sort(lines);
        return join(lines);
    }

    /* ==================== 内部 ==================== */

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String join(Iterable<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        int end = max;
        // 不把 emoji/代理对劈成半个字符，否则抽签结果会出现「�」
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * 只有空白（含全角空格、不换行空格、零宽字符）就是空内容。
     * 抽签选项、文本行数、去重/排序共用这一条，避免"字数 0 却算出 1 行"。
     */
    private static boolean blank(String value) {
        if (value == null) {
            return true;
        }
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (!blank(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean blank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
                || codePoint == 0x2060 || codePoint == 0xFEFF;
    }

    private static String trimBlank(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && blank(value.charAt(start))) {
            start++;
        }
        while (end > start && blank(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static String two(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
