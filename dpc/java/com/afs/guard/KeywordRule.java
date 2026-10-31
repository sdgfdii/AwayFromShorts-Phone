package com.afs.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 关键词匹配（纯逻辑）。
 *
 * 只在内存里比字符串，不记录屏幕内容、不落盘、不上传 —— 命中之后留给磁盘的
 * 只有「次数、时间、关键词本身」这三样（见 {@link KeywordStore}）。
 */
public final class KeywordRule {

    private static final int MAX_KEYWORDS = 200;
    private static final int MAX_LENGTH = 40;

    private KeywordRule() {
    }

    /** 一行一个，也容忍逗号（中英文）和分号；去空、去重、去超长。 */
    public static List<String> parse(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        for (String piece : raw.split("[\n\r,，;；]+")) {
            String item = piece.trim();
            if (item.isEmpty()) continue;
            if (item.length() > MAX_LENGTH) item = item.substring(0, MAX_LENGTH);
            if (!out.contains(item)) out.add(item);
            if (out.size() >= MAX_KEYWORDS) break;
        }
        return out;
    }

    public static String join(List<String> keywords) {
        StringBuilder sb = new StringBuilder();
        for (String item : keywords) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(item);
        }
        return sb.toString();
    }

    /**
     * 在整屏文字里找第一个命中的关键词。
     * 英文按小写比（大小写不敏感），中文原样比；不做模糊匹配，避免误伤。
     */
    public static String match(String screen, List<String> keywords) {
        if (screen == null || keywords == null || keywords.isEmpty()) return null;
        if (screen.isEmpty()) return null;
        String lower = screen.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword == null || keyword.isEmpty()) continue;
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) return keyword;
        }
        return null;
    }

    /** 多个文本块（TextView 文本、contentDescription）拼起来找。 */
    public static String matchIn(List<String> blocks, List<String> keywords) {
        if (blocks == null || keywords == null || keywords.isEmpty()) return null;
        for (String block : blocks) {
            String hit = match(block, keywords);
            if (hit != null) return hit;
        }
        return null;
    }

    /** 单字关键词几乎必然误拦，界面要提示（但仍然允许，用户可以自己判断）。 */
    public static boolean tooShort(String keyword) {
        return keyword != null && keyword.length() < 2;
    }

    /** 有几个过短的词。 */
    public static int shortCount(List<String> keywords) {
        int n = 0;
        for (String keyword : keywords) {
            if (tooShort(keyword)) n++;
        }
        return n;
    }

    /** 这个包该不该被检查：命中目标列表，且不是自己。 */
    public static boolean inspect(String pkg, java.util.Collection<String> targets) {
        return pkg != null && targets != null && targets.contains(pkg);
    }
}
