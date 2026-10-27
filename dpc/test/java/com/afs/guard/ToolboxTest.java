package com.afs.guard;

import java.util.List;
import java.util.Random;

/** 计时格式、抽签拆行、文本统计与整理；全部纯函数，不碰设备也不碰偏好。 */
public final class ToolboxTest {

    private static int count;

    private static void check(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
        count++;
    }

    private static String repeat(char ch, int times) {
        StringBuilder builder = new StringBuilder(times);
        for (int index = 0; index < times; index++) {
            builder.append(ch);
        }
        return builder.toString();
    }

    public static void main(String[] args) {
        /* ---------- 时钟格式 ---------- */
        check(Toolbox.clock(0).equals("00:00"), "zero seconds");
        check(Toolbox.clock(-5).equals("00:00"), "negative seconds clamp to zero");
        check(Toolbox.clock(5).equals("00:05"), "pads the seconds");
        check(Toolbox.clock(59).equals("00:59"), "just under a minute");
        check(Toolbox.clock(60).equals("01:00"), "one minute");
        check(Toolbox.clock(65).equals("01:05"), "minutes and seconds");
        check(Toolbox.clock(3599).equals("59:59"), "just under an hour");
        check(Toolbox.clock(3600).equals("1:00:00"), "an hour grows the hour field");
        check(Toolbox.clock(3661).equals("1:01:01"), "hours keep minutes and seconds padded");
        check(Toolbox.clock(360000).equals("100:00:00"), "large values are not truncated");

        /* ---------- 倒计时剩余 ---------- */
        check(Toolbox.remain(300, 0) == 300, "remain starts at the total");
        check(Toolbox.remain(300, 120) == 180, "remain counts down");
        check(Toolbox.remain(300, 400) == 0, "remain never goes negative");
        check(Toolbox.remain(-5, 10) == 0, "a negative total clamps to zero");
        check(Toolbox.remain(100, -3) == 100, "a negative elapsed value is ignored");

        /* ---------- 抽签选项拆分 ---------- */
        List<String> items = Toolbox.splitItems("  苹果 \n香蕉\n\n橘子\r\n  苹果  ");
        check(items.size() == 3, "blank lines are dropped and duplicates are removed");
        check(items.get(0).equals("苹果") && items.get(2).equals("橘子"),
                "options are trimmed and keep their first-seen order");
        check(Toolbox.splitItems(null).isEmpty(), "a null list is empty");
        check(Toolbox.splitItems("\n\n  \n").isEmpty(), "a whitespace list is empty");
        check(Toolbox.splitItems(repeat('长', 100)).get(0).length() == Toolbox.MAX_ITEM_LENGTH,
                "an overlong option is clipped");
        StringBuilder many = new StringBuilder();
        for (int index = 0; index < Toolbox.MAX_ITEMS + 50; index++) {
            many.append("item").append(index).append('\n');
        }
        check(Toolbox.splitItems(many.toString()).size() == Toolbox.MAX_ITEMS,
                "the option list is capped");
        check(Toolbox.splitItems("a\r\nb").size() == 2, "CRLF input is split correctly");
        check(Toolbox.splitItems("\u3000\n苹果").size() == 1,
                "a full-width-space option is blank, not a hidden choice");
        check(Toolbox.splitItems("\u00A0").isEmpty(), "a non-breaking-space option is blank");
        String withEmoji = repeat('a', 59) + "😀";
        String clippedEmoji = Toolbox.splitItems(withEmoji).get(0);
        check(!Character.isHighSurrogate(clippedEmoji.charAt(clippedEmoji.length() - 1)),
                "clipping never splits an emoji surrogate pair");

        /* ---------- 抽签下标 ---------- */
        check(Toolbox.pickIndex(0, new Random(1)) == -1, "no options means no pick");
        check(Toolbox.pickIndex(-2, null) == -1, "a negative size means no pick");
        check(Toolbox.pickIndex(5, null) == 0, "a missing random source falls back to the first option");
        Random random = new Random(42);
        boolean inRange = true;
        for (int index = 0; index < 50; index++) {
            int picked = Toolbox.pickIndex(7, random);
            if (picked < 0 || picked >= 7) {
                inRange = false;
            }
        }
        check(inRange, "picked indexes stay inside the list");

        /* ---------- 字数 / 行数 ---------- */
        check(Toolbox.charCount(null) == 0 && Toolbox.charCount("") == 0, "no text means no characters");
        check(Toolbox.charCount("  \n\t ") == 0, "whitespace does not count");
        check(Toolbox.charCount("你好 world") == 7, "CJK and latin letters both count, the space does not");
        check(Toolbox.charCount("😀a") == 2, "an emoji counts as one character");
        check(Toolbox.lineCount(null) == 0 && Toolbox.lineCount("\n \n") == 0,
                "blank lines are not counted");
        check(Toolbox.lineCount("a\n\nb\n") == 2, "non-empty lines are counted");
        check(Toolbox.charCount("\u3000\u00A0") == 0, "full-width and non-breaking spaces are not characters");
        check(Toolbox.lineCount("\u3000\nA") == 1, "a line made of full-width spaces is blank");
        check(Toolbox.lineCount("A\n\u3000\nA") == 2, "blank-looking lines are dropped from the line count");

        /* ---------- 大小写 ---------- */
        check(Toolbox.upper(null).isEmpty() && Toolbox.lower(null).isEmpty(), "null text stays empty");
        check(Toolbox.upper("aBc 你好").equals("ABC 你好"), "uppercase keeps CJK text");
        check(Toolbox.lower("AbC").equals("abc"), "lowercase works");
        check(Toolbox.upper("a\r\nb").equals("A\nB"), "line endings are normalised before conversion");

        /* ---------- 按行去重 ---------- */
        check(Toolbox.dedupeLines(null).isEmpty(), "null text has no lines");
        check(Toolbox.dedupeLines("b\na\nb\n\n a").equals("b\na"), "duplicates keep their first position");
        check(Toolbox.dedupeLines("  x  \n x \ny").equals("x\ny"), "lines are compared after trimming");
        check(Toolbox.dedupeLines("\n \n").isEmpty(), "blank lines disappear");
        check(Toolbox.dedupeLines("A\n\u3000\nA").equals("A"), "a full-width-space line is dropped before dedupe");

        /* ---------- 按行排序 ---------- */
        check(Toolbox.sortLines("b\nc\na\n\nA").equals("A\na\nb\nc"), "sorting is lexical and drops blanks");
        check(Toolbox.sortLines("10\n2\n1").equals("1\n10\n2"), "sorting is a string comparison, not numeric");
        check(Toolbox.sortLines("x\r\ny").equals("x\ny"), "line endings are normalised before sorting");
        check(Toolbox.sortLines("B\n\u3000\nA").equals("A\nB"), "a full-width-space line is dropped before sorting");
        check(Toolbox.sortLines(null).isEmpty(), "null text sorts to nothing");

        System.out.println("Toolbox: " + count + " assertions passed");
    }
}
