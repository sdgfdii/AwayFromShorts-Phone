package com.afs.guard;

import java.util.Arrays;
import java.util.Collections;

/**
 * 给「代理常开」的人的 Clash 规则生成：映射与文本格式要钉死。
 *
 * 这里错了的代价很实际：把域名映射错，用户复制进代理后会拦到别的服务；
 * 把格式写错，粘进 Clash 直接解析失败。
 */
public final class ProxyRulesTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": " + actual + " != " + expected);
        }
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        /* 映射：B 站认得，陌包不认 */
        ok(ProxyRules.known("tv.danmaku.bili"), "B 站已收录");
        ok(!ProxyRules.known("com.example.unknown"), "未知包不认");
        ok(ProxyRules.domains("tv.danmaku.bili").contains("bilibili.com"), "B 站域名含 bilibili.com");
        ok(ProxyRules.domains("tv.danmaku.bili").contains("hdslb.com"), "B 站域名含 hdslb.com");
        ok(!ProxyRules.domains("tv.danmaku.bili").contains("qq.com"), "B 站映射不夹带别的服务");
        eq(0, ProxyRules.domains("com.example.unknown").size(), "未知包没有域名");

        /* 生成的文本结构 */
        String text = ProxyRules.snippet(Arrays.asList("tv.danmaku.bili"));
        ok(text.startsWith("#"), "开头是注释说明");
        ok(text.contains("\nrules:\n"), "有 rules: 块");
        ok(text.contains("  - DOMAIN-SUFFIX,bilibili.com,REJECT\n"), "规则行格式正确");
        ok(text.contains("  - DOMAIN-SUFFIX,bilivideo.com,REJECT\n"), "视频域名也在");
        ok(!text.contains("PROCESS-NAME"), "不依赖进程名（安卓上不可靠）");
        ok(!text.contains("com.example"), "没把包名当规则用");

        /* 去重：两个应用共享域名时只出一次 */
        String shared = ProxyRules.snippet(Arrays.asList("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite"));
        int douyin = count(shared, "  - DOMAIN-SUFFIX,douyin.com,REJECT\n");
        eq(1, douyin, "共享域名只生成一条");

        /* 空名单 / 未知包：不留坏格式 */
        String empty = ProxyRules.snippet(Collections.<String>emptyList());
        ok(empty.contains("rules:\n"), "空名单仍有 rules: 块");
        ok(!empty.contains("  - DOMAIN-SUFFIX,,"), "空名单没有坏规则行");
        String unknown = ProxyRules.snippet(Collections.singletonList("com.example.unknown"));
        ok(unknown.contains("# 没收录的应用：com.example.unknown"), "未知包留下一行注释");
        ok(!unknown.contains("com.example.unknown,REJECT"), "未知包不会被瞎猜成规则");

        /* 大小写 / 空白容忍 */
        String padded = ProxyRules.snippet(Arrays.asList(" tv.danmaku.bili "));
        ok(padded.contains("bilibili.com"), "包名两边空白会被去掉");

        /* 无 null 崩溃 */
        ok(ProxyRules.snippet(null).contains("rules:\n"), "null 名单不崩");
        ok(ProxyRules.snippet(Arrays.asList((String) null, "tv.danmaku.bili")).contains("bilibili.com"),
                "名单里有 null 也照常生成");

        System.out.println("Proxy rules: " + checks + " checks passed");
    }

    private static int count(String text, String part) {
        int n = 0, at = 0;
        while ((at = text.indexOf(part, at)) >= 0) {
            n++;
            at += part.length();
        }
        return n;
    }
}
