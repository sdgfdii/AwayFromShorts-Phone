package com.afs.guard;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 给「代理常开」的人用的一段 Clash 规则：断网交给代理做。
 *
 * 为什么需要它：Android 只允许一个 VPN，代理占着通道时本 App 的断网隧道建不起来。
 * 但代理本身就是那台「唯一的 VPN」，它完全可以在自己的规则里把指定应用的域名
 * REJECT 掉 —— 效果和断网一样，缓存的视频照样能离线播放，两者还能共存。
 *
 * 这里只做两件事：把常见短/中视频应用映射成域名后缀列表，再拼成一段可复制的
 * Clash 规则文本。纯字符串处理，不碰 Android，离线可测。
 */
final class ProxyRules {

    /** 包名 → 域名后缀。只收确认过的常用应用，宁缺毋滥：映射错会误伤别的服务。 */
    private static final String[][] KNOWN = {
            {"tv.danmaku.bili", "bilibili.com", "bilivideo.com", "bilivideo.cn", "biliapi.com", "biliapi.net", "hdslb.com", "b23.tv"},
            {"com.ss.android.ugc.aweme", "douyin.com", "amemv.com", "snssdk.com", "bytecdntp.com", "douyinvod.com"},
            {"com.ss.android.ugc.aweme.lite", "douyin.com", "amemv.com", "snssdk.com"},
            {"com.ss.android.ugc.live", "douyin.com", "amemv.com", "snssdk.com"},
            {"com.smile.gifmaker", "kuaishou.com", "gifshow.com", "kwaicdn.com", "yximgs.com"},
            {"com.kuaishou.nebula", "kuaishou.com", "gifshow.com", "kwaicdn.com", "yximgs.com"},
            {"com.xingin.xhs", "xiaohongshu.com", "xhscdn.com"},
            {"com.sina.weibo", "weibo.com", "weibo.cn", "sinaimg.cn"},
            {"com.zhihu.android", "zhihu.com", "zhimg.com"},
            {"com.ss.android.article.news", "toutiao.com", "snssdk.com", "byteimg.com"},
    };

    private ProxyRules() {
    }

    /** 这个包有没有收录域名。 */
    static boolean known(String pkg) {
        for (String[] row : KNOWN) {
            if (row[0].equals(pkg)) return true;
        }
        return false;
    }

    /** 收录的域名后缀（去重保序）。 */
    static Set<String> domains(String pkg) {
        Set<String> out = new LinkedHashSet<String>();
        for (String[] row : KNOWN) {
            if (!row[0].equals(pkg)) continue;
            for (int i = 1; i < row.length; i++) out.add(row[i]);
            break;
        }
        return out;
    }

    /**
     * 拼一段可以直接粘进 Clash 配置 / FlClash 覆写 `rules:` 的文本。
     *
     * 只包含能映射到域名的应用；认不出的包名留一行注释提醒用户自己补，
     * 不要瞎猜域名（猜错会把别的服务一起拦掉）。
     */
    static String snippet(Collection<String> packages) {
        StringBuilder out = new StringBuilder();
        out.append("# 把下面这些行放进 Clash 配置 / FlClash 覆写的 rules: 里，")
                .append("并放在 MATCH 那一行之前。\n")
                .append("# 作用：代理照常开，只掐掉这些应用的网络；已缓存的视频仍可离线播放。\n")
                .append("rules:\n");
        Set<String> rightSides = new LinkedHashSet<String>();
        StringBuilder unknown = new StringBuilder();
        if (packages != null) {
            for (String pkg : packages) {
                if (pkg == null || pkg.trim().isEmpty()) continue;
                Set<String> suffixes = domains(pkg.trim());
                if (suffixes.isEmpty()) {
                    if (unknown.length() > 0) unknown.append('、');
                    unknown.append(pkg.trim());
                    continue;
                }
                for (String suffix : suffixes) {
                    if (rightSides.add(suffix)) out.append("  - DOMAIN-SUFFIX,").append(suffix).append(",REJECT\n");
                }
            }
        }
        if (rightSides.isEmpty()) {
            out.append("# （断网名单里的应用还没有可用的域名映射，先在上面的名单里加应用，或按注释自己补域名）\n");
        }
        if (unknown.length() > 0) {
            out.append("# 没收录的应用：").append(unknown)
                    .append(" —— 请自己补它的域名，小心别误伤同一家公司的其它服务。\n");
        }
        return out.toString();
    }
}
