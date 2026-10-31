package com.afs.guard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 应用内页面拦截的规则解析与命中。
 *
 * 重点在文字规则：单词语义太宽（「视频号」在聊天页也有），
 * 所以 `+` 连接的多词必须**全部**出现；只有部分出现就不能拦。
 */
public final class PageRuleTest {
    private static int checks;

    private static final String WX = "com.tencent.mm";

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": " + actual + " != " + expected);
        }
    }

    private static void yes(boolean actual, String label) {
        eq(Boolean.TRUE, actual, label);
    }

    private static void no(boolean actual, String label) {
        eq(Boolean.FALSE, actual, label);
    }

    /** 造几条规则用（走 parse，顺便验证解析路径）。 */
    private static List<PageRule.Rule> rules(String... encoded) {
        return PageRule.parseAll(Arrays.asList(encoded));
    }

    private static List<String> page(String... texts) {
        return Arrays.asList(texts);
    }

    public static void main(String[] args) {
        /* ---------- 解析：文字规则 ---------- */
        PageRule.Rule finder = PageRule.parse(PageRule.WECHAT_FINDER);
        yes(finder != null, "内置视频号规则能解析");
        eq(WX, finder.pkg, "包名取分隔符左边");
        eq(PageRule.KIND_TEXT, finder.kind, "内置规则是文字规则");
        no(finder.byClass(), "文字规则 byClass 为假");
        eq("视频号+推荐", finder.value, "值取 text: 之后");
        eq(PageRule.WECHAT_FINDER, finder.encode(), "编解码round-trip一致");

        /* ---------- 解析：类名规则 ---------- */
        PageRule.Rule byClass = PageRule.parse(WX + "|class:finder");
        yes(byClass != null, "类名规则能解析");
        eq(PageRule.KIND_CLASS, byClass.kind, "class: 出来是类名规则");
        yes(byClass.byClass(), "类名规则 byClass 为真");
        eq("finder", byClass.value, "值取 class: 之后");
        eq(WX + "|class:finder", byClass.encode(), "类名规则round-trip一致");

        /* 前后空格要吃掉 */
        PageRule.Rule spaced = PageRule.parse(" com.tencent.mm | text: 视频号+推荐 ");
        yes(spaced != null, "包名和值两边的空格不影响解析");
        eq("视频号+推荐", spaced.value, "值已去空格");
        eq(WX, spaced.pkg, "包名已去空格");

        /* ---------- 解析：坏数据一律 null ---------- */
        eq(null, PageRule.parse(null), "null 不解析");
        eq(null, PageRule.parse(""), "空串不解析");
        eq(null, PageRule.parse(WX), "没有分隔符不解析");
        eq(null, PageRule.parse("|text:x"), "分隔符在开头不解析");
        eq(null, PageRule.parse(WX + "|"), "分隔符在结尾不解析");
        eq(null, PageRule.parse("ab|text:x"), "不像包名（没有点）不解析");
        eq(null, PageRule.parse("a b.c|text:x"), "包名带空格不解析");
        eq(null, PageRule.parse(WX + "|find:x"), "不认识的类型不解析");
        eq(null, PageRule.parse(WX + "|text:"), "空值不解析");
        eq(null, PageRule.parse(WX + "|text:   "), "全空格值不解析");
        eq(null, PageRule.parse(WX + "|text:a|b"), "值里混进分隔符不解析");

        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 61; i++) longValue.append('x');
        eq(null, PageRule.parse(WX + "|class:" + longValue), "超过 60 字不解析");
        eq(61, longValue.length(), "边界长度确实是 61");
        StringBuilder just60 = new StringBuilder();
        for (int i = 0; i < 60; i++) just60.append('x');
        yes(PageRule.parse(WX + "|class:" + just60) != null, "正好 60 字照收");

        /* ---------- parseAll：跳坏数据 + 去重 ---------- */
        List<PageRule.Rule> all = PageRule.parseAll(Arrays.asList(
                PageRule.WECHAT_FINDER, "bad", WX + "|class:finder", PageRule.WECHAT_FINDER));
        eq(2, all.size(), "坏数据跳过、重复的只留一条");
        eq(0, PageRule.parseAll(null).size(), "null 返回空表不炸");
        eq(0, PageRule.parseAll(Collections.<String>emptyList()).size(), "空表返回空表");

        /* ---------- forPackage：先按包名筛 ---------- */
        List<PageRule.Rule> mixed = rules(PageRule.WECHAT_FINDER, "com.zhihu.android|text:推荐");
        eq(1, PageRule.forPackage(mixed, WX).size(), "只留微信自己的规则");
        eq(1, PageRule.forPackage(mixed, "com.zhihu.android").size(), "只留知乎自己的规则");
        eq(0, PageRule.forPackage(mixed, "com.other.app").size(), "别的应用一条都不碰");
        eq(0, PageRule.forPackage(mixed, "").size(), "空包名返回空");
        eq(0, PageRule.forPackage(mixed, null).size(), "null 包名返回空");
        eq(0, PageRule.forPackage(null, WX).size(), "null 规则表返回空");

        /* ---------- hitByClass ---------- */
        List<PageRule.Rule> classRules = rules(WX + "|class:finder");
        yes(PageRule.hitByClass(classRules, WX, "com.tencent.mm.plugin.finder.ui.FinderActivity") != null,
                "类名包含就命中");
        yes(PageRule.hitByClass(classRules, WX, "com.tencent.mm.plugin.FINDER.ui.X") != null,
                "类名比对不分大小写");
        eq(null, PageRule.hitByClass(classRules, WX, "com.tencent.mm.ui.LauncherUI"), "类名不沾不命中");
        eq(null, PageRule.hitByClass(classRules, "com.other.app", "x.finder.Y"), "包名不对不命中");
        eq(null, PageRule.hitByClass(classRules, WX, null), "类名为空不命中");
        eq(null, PageRule.hitByClass(classRules, WX, "   "), "类名全空格不命中");
        /* 文字规则不该被类名判命中 */
        eq(null, PageRule.hitByClass(rules(PageRule.WECHAT_FINDER), WX, "whatever.视频号+推荐"), "文字规则不参与类名判");

        /* ---------- hitByText：内置视频号 ---------- */
        List<PageRule.Rule> textRules = rules(PageRule.WECHAT_FINDER);
        yes(PageRule.hitByText(textRules, WX, page("视频号", "推荐", "朋友")) != null,
                "两个词都在同一个页面就命中");
        eq(null, PageRule.hitByText(textRules, WX, page("视频号")), "只有「视频号」不命中（发现页）");
        eq(null, PageRule.hitByText(textRules, WX, page("推荐")), "只有「推荐」不命中");
        eq(null, PageRule.hitByText(textRules, WX, page("聊天", "视频号", "第 3 条消息")),
                "聊天页出现「视频号」也不拦");
        eq(null, PageRule.hitByText(textRules, "com.other.app", page("视频号", "推荐")),
                "别的应用两个词都有也不拦");

        /* 词分落在不同节点上也要能凑齐（一个词一个 TextView 是常态） */
        yes(PageRule.hitByText(textRules, WX, page("视频号", "推荐")) != null, "词分在两个节点上也能凑齐");
        /* 拼在同一个节点里也算 */
        yes(PageRule.hitByText(textRules, WX, page("视频号 · 推荐")) != null, "同一个节点里连着出现也算");
        /* 顺序无所谓 */
        yes(PageRule.hitByText(textRules, WX, page("推荐", "视频号")) != null, "词的先后顺序不影响");
        /* 空文字表不命中 */
        eq(null, PageRule.hitByText(textRules, WX, null), "文字表为 null 不命中");
        eq(null, PageRule.hitByText(textRules, WX, Collections.<String>emptyList()), "文字表为空不命中");
        eq(null, PageRule.hitByText(textRules, WX, page("", "")), "全是空串不命中");
        /* null 元素不炸 */
        eq(null, PageRule.hitByText(textRules, WX, Arrays.asList(null, "", (String) null)), "null 节点被跳过");

        /* ---------- hitByText：单段规则退化成普通子串 ---------- */
        List<PageRule.Rule> single = rules(WX + "|text:视频号");
        yes(PageRule.hitByText(single, WX, page("发现", "视频号")) != null, "单段规则直接子串匹配");
        eq(null, PageRule.hitByText(single, WX, page("发现")), "单段规则不含就不命中");

        /* 英文单词不分大小写 */
        List<PageRule.Rule> ascii = rules("com.zhihu.android|text:reels");
        yes(PageRule.hitByText(ascii, "com.zhihu.android", page("Reels")) != null, "英文文字不分大小写");
        yes(PageRule.hitByText(ascii, "com.zhihu.android", page("REELS")) != null, "全大写也命中");
        eq(null, PageRule.hitByText(ascii, "com.zhihu.android", page("reel")), "少个字母不命中");

        /* `+` 两边有空格也要认 */
        List<PageRule.Rule> loose = rules(WX + "|text:视频号 + 推荐");
        yes(PageRule.hitByText(loose, WX, page("视频号", "推荐")) != null, "加号两边空格不影响匹配");

        /* `+` 连出来的空段（比如值就是 "+"）不能拦全世界 */
        List<PageRule.Rule> empty = rules(WX + "|text:+");
        eq(1, empty.size(), "值就一个加号能存下来");
        eq(null, PageRule.hitByText(empty, WX, page("随便什么页面")), "没有词可比的规则不命中");

        /* 多个规则时命中任何一条都算 */
        List<PageRule.Rule> two = rules(PageRule.WECHAT_FINDER, WX + "|text:看一看");
        yes(PageRule.hitByText(two, WX, page("看一看", "热点")) != null, "多规则里命中第二条也算");
        eq("看一看", PageRule.hitByText(two, WX, page("看一看")).value, "返回的是命中的那条");

        /* ---------- guessKind ---------- */
        eq(PageRule.KIND_TEXT, PageRule.guessKind("视频号"), "中文按文字");
        eq(PageRule.KIND_TEXT, PageRule.guessKind("视频号+推荐"), "中文多词按文字");
        eq(PageRule.KIND_CLASS, PageRule.guessKind("finder"), "全英文按类名");
        eq(PageRule.KIND_CLASS, PageRule.guessKind("FinderActivity"), "英文类名按类名");
        eq(PageRule.KIND_TEXT, PageRule.guessKind(""), "空串按文字");
        eq(PageRule.KIND_TEXT, PageRule.guessKind(null), "null 按文字");
        eq(PageRule.KIND_TEXT, PageRule.guessKind("Reels 推荐"), "中英混排按文字");

        /* ---------- clean ---------- */
        eq("视频号+推荐", PageRule.clean("  视频号+推荐  "), "去两边空格");
        eq("ab", PageRule.clean("a|b"), "分隔符被剔掉");
        eq("a b", PageRule.clean("a\nb"), "换行变空格");
        eq("", PageRule.clean(null), "null 清成空串");
        eq("", PageRule.clean("   "), "全空格清成空串");
        eq(60, PageRule.clean(longValue.toString()).length(), "超长截断到 60");

        /* ---------- label ---------- */
        yes(finder.label().contains("视频号+推荐"), "label 里要带上凭据");
        yes(byClass.label().contains("finder"), "类名规则 label 也带凭据");

        /* ---------- ALL 常量与切分口径一致 ---------- */
        eq('+', PageRule.ALL, "多词连接符是加号");
        List<String> split = new ArrayList<String>();
        for (String part : ("视频号" + PageRule.ALL + "推荐").split("\\+")) split.add(part);
        eq(2, split.size(), "用 ALL 连起来的词能切回两个");

        System.out.println("Page rule: " + checks + " checks passed");
    }
}
