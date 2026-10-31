package com.afs.guard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 关键词解析与匹配：不误伤、不吃整屏、非法输入不炸。 */
public final class KeywordRuleTest {

    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": " + actual + " != " + expected);
        }
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        /* ---------- 解析 ---------- */
        eq(Arrays.asList("推荐", "直播", "热舞", "广告"),
                KeywordRule.parse("推荐\n直播,热舞；广告"), "split on newline and commas");
        eq(Arrays.asList("推荐"), KeywordRule.parse("  推荐  "), "trim");
        eq(Arrays.asList("推荐"), KeywordRule.parse("推荐\n推荐\n 推荐"), "dedupe");
        eq(0, KeywordRule.parse("").size(), "empty raw");
        eq(0, KeywordRule.parse("   \n ,，;；").size(), "only separators");
        eq(0, KeywordRule.parse(null).size(), "null raw");
        eq("推荐\n直播", KeywordRule.join(Arrays.asList("推荐", "直播")), "join");

        // 超长词被截到 40 字，防止有人贴一整段文案进去
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 100; i++) huge.append('长');
        eq(40, KeywordRule.parse(huge.toString()).get(0).length(), "long keyword truncated");

        // 上限 200 条
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            if (i > 0) many.append('\n');
            many.append("词").append(i);
        }
        eq(200, KeywordRule.parse(many.toString()).size(), "keyword count capped");

        /* ---------- 匹配 ---------- */
        List<String> words = Arrays.asList("推荐", "直播");
        eq("推荐", KeywordRule.match("为你推荐：xxx", words), "cjk hit");
        eq("直播", KeywordRule.match("正在直播", words), "second keyword");
        eq(null, KeywordRule.match("今天天气不错", words), "no hit");
        eq(null, KeywordRule.match("", words), "empty screen");
        eq(null, KeywordRule.match(null, words), "null screen");
        eq(null, KeywordRule.match("推荐", new ArrayList<String>()), "no keywords");
        eq(null, KeywordRule.match("推荐", null), "null keywords");

        // 英文不分大小写
        eq("hello", KeywordRule.match("Hello World", Arrays.asList("hello")), "ascii case insensitive");
        // 不做模糊匹配：中间插字就不算
        eq(null, KeywordRule.match("推 荐", Arrays.asList("推荐")), "no fuzzy match");

        /* ---------- 多块文本 ---------- */
        eq("直播", KeywordRule.matchIn(Arrays.asList("标题", "正在直播中"), words), "hit in second block");
        eq(null, KeywordRule.matchIn(Arrays.asList("标题", "正文"), words), "no hit in blocks");
        eq(null, KeywordRule.matchIn(null, words), "null blocks");
        eq(null, KeywordRule.matchIn(Arrays.asList("推荐"), null), "null keywords in blocks");

        /* ---------- 过短提示 ---------- */
        ok(KeywordRule.tooShort("的"), "single char too short");
        ok(KeywordRule.tooShort("") , "empty is short");
        ok(KeywordRule.tooShort("推荐") == false, "two chars fine");
        eq(2, KeywordRule.shortCount(Arrays.asList("的", "了", "推荐")), "two short words");

        /* ---------- 目标应用 ---------- */
        Set<String> targets = new HashSet<String>(Arrays.asList("com.a", "com.b"));
        ok(KeywordRule.inspect("com.a", targets), "target hit");
        ok(KeywordRule.inspect("com.c", targets) == false, "not a target");
        ok(KeywordRule.inspect(null, targets) == false, "null package");
        ok(KeywordRule.inspect("com.a", null) == false, "null targets");

        System.out.println("Keyword rule: " + checks + " checks passed");
    }
}
