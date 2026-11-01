package com.afs.guard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 应用内页面拦截的规则（纯逻辑，不碰 Android）。
 *
 * 一条规则 = 「哪个应用 + 凭什么认出这个页面」。两种凭据：
 *
 * <pre>
 *   class:finder          窗口类名里含 finder
 *   text:视频号+推荐       页面文字里这几个词**都**出现（用 + 连接）
 * </pre>
 *
 * 关于两类凭据的真实可用性（2026-11-01 真机诊断）：
 * - `AccessibilityEvent#getClassName()` 在 **TYPE_WINDOW_STATE_CHANGED** 事件上给的是窗口/Activity
 *   类名（微信进视频号实测给 `com.tencent.mm.plugin.finder.ui.FinderHo…`），可以拿来拦页面。
 * - 在 TYPE_WINDOW_CONTENT_CHANGED 上给的是具体 View 类名（RecyclerView 之类），不具页面区分度。
 * - 微信这类应用把**文字**对无障碍隐藏（一屏 0 个字），文字规则对它们永远命不中，只能靠类名。
 */
public final class PageRule {

    public static final int KIND_CLASS = 0;
    public static final int KIND_TEXT = 1;

    /** 文字特征之间的连接符：全部出现才算命中。 */
    public static final char ALL = '+';

    /** 特征最长 60 字：再长就不像是"认页面的凭据"了。 */
    private static final int MAX_VALUE = 60;

    /** 内置示例盯的应用。 */
    public static final String WECHAT_PKG = "com.tencent.mm";

    /**
     * 内置：微信视频号 —— 按窗口类名拦。
     *
     * 微信把页面文字对无障碍隐藏（一屏读到 0 个字），文字规则永远命不中；
     * 但实测 `AccessibilityEvent#getClassName()` 在进视频号时会给
     * `com.tencent.mm.plugin.finder.ui.FinderHo…`（2026-11-01 真机诊断）。
     * 用类名前缀覆盖整个视频号（首页 / 详情 / 直播都算），不用逐个页面精确匹配。
     */
    public static final String WECHAT_FINDER = WECHAT_PKG + "|class:plugin.finder.ui.";

    /**
     * 内置：微信朋友圈 —— 按窗口类名拦（`plugin.sns.ui.*` 是朋友圈那一组页面）。
     * 同样是因为文字读不到，只能靠类名。
     */
    public static final String WECHAT_MOMENTS = WECHAT_PKG + "|class:plugin.sns.ui.";

    /** 旧版按文字写的两条（微信读不到文字，永远命不中）；升级时清掉，避免列表里留两条废规则。 */
    public static final String WECHAT_FINDER_TEXT = WECHAT_PKG + "|text:视频号+推荐";
    public static final String WECHAT_MOMENTS_TEXT = WECHAT_PKG + "|text:朋友圈+赞";

    private PageRule() {
    }

    public static final class Rule {
        public final String pkg;
        public final int kind;
        public final String value;
        private final String lower;

        Rule(String pkg, int kind, String value) {
            this.pkg = pkg;
            this.kind = kind;
            this.value = value;
            this.lower = value.toLowerCase(Locale.ROOT);
        }

        public boolean byClass() {
            return kind == KIND_CLASS;
        }

        /** 存盘 / 去重用的键。 */
        public String encode() {
            return pkg + "|" + (kind == KIND_CLASS ? "class:" : "text:") + value;
        }

        /** 人话：应用的包名 + 凭据，规则列表里直接显示。 */
        public String label() {
            return (kind == KIND_CLASS ? T.s("窗口类名") : T.s("页面文字")) + " · " + value;
        }
    }

    /** 解析一行；包名不像包名、类型不认识、值为空都返回 null（坏数据不参与匹配）。 */
    public static Rule parse(String encoded) {
        if (encoded == null) return null;
        int bar = encoded.indexOf('|');
        if (bar <= 0 || bar >= encoded.length() - 1) return null;
        String pkg = encoded.substring(0, bar).trim();
        if (!looksLikePackage(pkg)) return null;
        String rest = encoded.substring(bar + 1).trim();
        int kind;
        String value;
        if (rest.startsWith("class:")) {
            kind = KIND_CLASS;
            value = rest.substring(6).trim();
        } else if (rest.startsWith("text:")) {
            kind = KIND_TEXT;
            value = rest.substring(5).trim();
        } else {
            return null;
        }
        if (value.isEmpty() || value.length() > MAX_VALUE) return null;
        if (value.indexOf('|') >= 0) return null;          // 分隔符混进来会把下一行串味
        return new Rule(pkg, kind, value);
    }

    public static List<Rule> parseAll(Collection<String> encoded) {
        List<Rule> out = new ArrayList<Rule>();
        if (encoded == null) return out;
        for (String item : encoded) {
            Rule rule = parse(item);
            if (rule != null && !contains(out, rule.encode())) out.add(rule);
        }
        return out;
    }

    /** 只留这个应用的规则 —— 每次事件都按包名先筛一遍，别的应用一条都不碰。 */
    public static List<Rule> forPackage(Collection<Rule> rules, String pkg) {
        List<Rule> out = new ArrayList<Rule>();
        if (rules == null || pkg == null || pkg.isEmpty()) return out;
        for (Rule rule : rules) {
            if (pkg.equals(rule.pkg)) out.add(rule);
        }
        return out;
    }

    /** 按窗口类名判。类名来自 AccessibilityEvent#getClassName，空就不算命中。 */
    public static Rule hitByClass(Collection<Rule> rules, String pkg, String className) {
        if (rules == null || pkg == null) return null;
        if (className == null || className.trim().isEmpty()) return null;
        String lower = className.toLowerCase(Locale.ROOT);
        for (Rule rule : rules) {
            if (!rule.byClass() || !pkg.equals(rule.pkg)) continue;
            if (lower.contains(rule.lower)) return rule;
        }
        return null;
    }

    /**
     * 按页面文字判。`+` 连接的多段要**全部**出现才算命中；单段就退化成普通子串匹配。
     * 中文不分词，直接比子串。
     */
    public static Rule hitByText(Collection<Rule> rules, String pkg, List<String> texts) {
        if (rules == null || pkg == null) return null;
        if (texts == null || texts.isEmpty()) return null;
        for (Rule rule : rules) {
            if (rule.byClass() || !pkg.equals(rule.pkg)) continue;
            if (allPresent(rule.value, texts)) return rule;
        }
        return null;
    }

    /** 规则里的每个词是否都能在页面文字里找到（各自可以在不同节点上）。 */
    private static boolean allPresent(String value, List<String> texts) {
        StringBuilder joined = new StringBuilder();
        for (String text : texts) {
            if (text == null || text.isEmpty()) continue;
            joined.append(text.toLowerCase(Locale.ROOT)).append('\n');
        }
        String page = joined.toString();
        int words = 0;
        for (String part : value.split("\\+")) {
            String word = part.trim().toLowerCase(Locale.ROOT);
            if (word.isEmpty()) continue;
            words++;
            if (!page.contains(word)) return false;
        }
        return words > 0;   // 一个词都没有（比如值就是 "+"）不能算命中，否则拦全世界
    }

    /**
     * 猜用户填的是类名还是文字：含非 ASCII 基本就是中文词，其他当类名。
     * 界面上把这句话写清楚，别让人猜。
     */
    public static int guessKind(String value) {
        if (value == null || value.isEmpty()) return KIND_TEXT;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) return KIND_TEXT;
        }
        return KIND_CLASS;
    }

    /** 值规整：去空白、截断、拒掉分隔符。 */
    public static String clean(String value) {
        if (value == null) return "";
        String out = value.trim().replace("|", "").replace("\n", " ");
        if (out.length() > MAX_VALUE) out = out.substring(0, MAX_VALUE);
        return out;
    }

    private static boolean looksLikePackage(String pkg) {
        if (pkg.length() < 3 || pkg.indexOf('.') < 0) return false;
        for (int i = 0; i < pkg.length(); i++) {
            char c = pkg.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean contains(List<Rule> rules, String encoded) {
        for (Rule rule : rules) {
            if (rule.encode().equals(encoded)) return true;
        }
        return false;
    }
}
