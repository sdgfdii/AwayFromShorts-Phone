package com.afs.guard;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 界面文案翻译（gettext 风格：中文原文就是 msgid）。
 *
 * 为什么不用 values-en/strings.xml：
 * 全部界面里有三千多条中文，且大量是拼出来的（"已打卡 " + 名字 + " +" + 数量）。
 * 走 XML 就得把每一处拼接改写成带位置的格式串（%1$s），三千处手改，
 * 改错一处就是崩溃或错位。这里保留原句，只在字面量外面包一层 T.s()，
 * 拼接语义完全不变，翻译表是独立资源 {@code assets/i18n_en.txt}，
 * 改文案不用动代码，切换语言立刻生效。
 *
 * 调用约定：
 *   T.s("保存")            —— 整句
 *   T.f("第 %d 天", n)     —— 带占位符（翻译串必须保留同样的占位符，见 tools/i18n.py 的校验）
 *
 * 语言状态是进程级静态量，由 {@link GuardClock#onCreate} 和
 * {@link ThemedActivity#attachBaseContext} 刷新；没初始化时一律返回中文原文，
 * 不会因为顺序问题把界面变成空白。
 */
public final class T {

    private static volatile Map<String, String> table;
    private static volatile boolean english;

    private T() {
    }

    /** 进程启动或 Activity 创建时调；重复调用只重读语言开关，翻译表只读一次。 */
    static void init(Context c) {
        if (c == null) {
            return;
        }
        if (table == null) {
            synchronized (T.class) {
                if (table == null) {
                    table = load(c);
                }
            }
        }
        refresh(c);
    }

    /** 只更新语言开关，不碰翻译表；切语言后 recreate() 就会走到这里。 */
    static void refresh(Context c) {
        if (c == null) {
            return;
        }
        try {
            english = Lang.english(c);
        } catch (RuntimeException ignored) {
            english = false;
        }
    }

    /** 日期/星期的格式跟着界面语言走。写死 Locale.CHINA 会把星期印成「周六」。 */
    public static java.util.Locale locale() {
        return english ? java.util.Locale.US : java.util.Locale.CHINA;
    }

    public static boolean english() {
        return english;
    }

    static Map<String, String> table() {
        Map<String, String> local = table;
        return local == null ? new HashMap<String, String>() : local;
    }

    /**
     * 不看进程级语言开关，直接查英文表；没译文就返回中文原文。
     * 给 {@link Lang#nav} 这类「自己已经知道该用英文」的地方用，
     * 避免依赖 T 的静态开关是否已经初始化。
     */
    static String en(String zh) {
        if (zh == null || zh.isEmpty()) {
            return zh;
        }
        Map<String, String> local = table;
        if (local == null || !local.containsKey(zh)) {
            return zh;
        }
        String value = local.get(zh);
        return value == null ? zh : value;
    }

    /** 中文原文 → 当前语言；没翻译就原样返回中文，绝不显示空串。 */
    public static String s(String zh) {
        if (zh == null || zh.isEmpty() || !english) {
            return zh;
        }
        Map<String, String> local = table;
        if (local == null) {
            return zh;
        }
        // 用 containsKey 而不是判空：翻译成空串（见 load 里的 \e）是有效结果。
        if (!local.containsKey(zh)) {
            return zh;
        }
        String value = local.get(zh);
        return value == null ? zh : value;
    }

    /** 带占位符的句子。翻译串的占位符必须和原文一致，否则退回中文。 */    public static String f(String zh, Object... args) {
        String pattern = s(zh);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return String.format(pattern, args);
        } catch (RuntimeException error) {
            return zh;
        }
    }

    /**
     * 读 assets/i18n_en.txt（放 assets 不放 res：离线回归直接用 javac 编译源码，
     * 没有 aapt2 生成的 R 类，碰 R.raw 会编不过）。格式：每行 `msgid<TAB>msgstr`，
     * # 开头是注释，
     * 允许 \n \t \\ 三种转义。读不出来就返回空表（界面仍显示中文），不抛异常。
     */
    private static Map<String, String> load(Context c) {
        Map<String, String> out = new HashMap<>();
        InputStream input = null;
        try {
            input = c.getAssets().open("i18n_en.txt");
            out = parse(new BufferedReader(new InputStreamReader(input, "UTF-8")));
        } catch (Exception ignored) {
            // 翻译表缺失或损坏不该影响使用：原文兜底。
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * 解析翻译表。单独抽出来是为了离线回归能直接喂文本——
     * 这条路径最容易出的错（值里的换行没转义、# 开头被当注释、空译文被丢弃）
     * 全是纯文本问题，不需要 Context 就能钉住。
     */
    static Map<String, String> parse(BufferedReader reader) throws java.io.IOException {
        Map<String, String> out = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            // 只把没有制表符的 # 行当注释。像 "#RRGGBB，如 FFD700" 这种
            // 以 # 开头的真实文案，整行丢掉就等于这条永远显示中文。
            if (line.isEmpty() || (line.charAt(0) == '#' && line.indexOf('\t') < 0)) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String raw = line.substring(tab + 1);
            String key = unescape(line.substring(0, tab));
            if (key.isEmpty()) {
                continue;
            }
            // \e 表示"译成空串"：中文里的「中」「的」这类虚词在英文里该整个消失，
            // 但不能用空值表示——空值等于没翻译，会退回中文。
            if ("\\e".equals(raw.trim())) {
                out.put(key, "");
                continue;
            }
            String value = unescape(raw);
            if (!value.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                if (next == 'n') {
                    out.append('\n');
                } else if (next == 't') {
                    out.append('\t');
                } else if (next == '\\') {
                    out.append('\\');
                } else {
                    out.append('\\').append(next);
                }
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
