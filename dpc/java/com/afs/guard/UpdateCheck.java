package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 检查更新的纯逻辑：解析更新源、比较版本。网络请求在 {@link SettingsActivity} 里。
 *
 * 更新源就是发布页附带的 manifest.json —— 每次发版时仓库脚本会把它和 APK 一起传上去，
 * 而 {@link #DEFAULT_URL} 用的 latest/download 永远指向最新那一版，所以这里不用跟着改版本号。
 *
 * {@link #DEFAULT_URL} 为空表示"还没配置更新源"，界面必须如实说这句话，
 * 而不是转两秒圈然后报"已是最新"。
 *
 * 官方源不通就按 {@link #MIRRORS} 换镜像（顺序见 {@link #sources()}）；换源的循环在
 * {@link SettingsActivity} 里，这里只管"有哪些源"和"清单怎么解析"。
 */
public final class UpdateCheck {

    /** 更新源：发布页 latest 的 manifest.json，随每次发版自动指向最新一版。 */
    public static final String DEFAULT_URL =
            "https://github.com/sdgfdii/AwayFromShorts-Phone/releases/latest/download/manifest.json";

    /**
     * 镜像源：国内直连 github.com 经常要等到超时才失败，这里再放几条走第三方加速的同名清单。
     *
     * <p>每条都是「加速前缀 + 完整 GitHub 地址」，代理把请求原样转给 origin，所以清单里的
     * 相对文件名（{@code apk.file}）拼出来同样落在镜像域下，下载也跟着走镜像，不用另配规则。
     * 官方源永远排第一：能用官方就不吃第三方。</p>
     */
    public static final String[] MIRRORS = {
            "https://gh-proxy.com/https://github.com/sdgfdii/AwayFromShorts-Phone/releases/latest/download/manifest.json",
            "https://gh.nxnow.top/https://github.com/sdgfdii/AwayFromShorts-Phone/releases/latest/download/manifest.json",
    };

    /**
     * 依次尝试的源：官方在前、镜像在后，去掉空串和重复项。
     * 全都没配（数组为空）返回空数组，调用方据此说"还没配置更新源"。
     */
    public static String[] sources() {
        List<String> out = new ArrayList<>();
        addSource(out, DEFAULT_URL);
        for (String mirror : MIRRORS) addSource(out, mirror);
        return out.toArray(new String[0]);
    }

    private static void addSource(List<String> out, String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty() || out.contains(url)) return;
        out.add(url);
    }

    /** 这条源是不是第三方镜像（非官方域）。界面据此提示"正在走镜像"。 */
    public static boolean mirrored(String source) {
        return source != null && !source.trim().startsWith("https://github.com/");
    }

    private UpdateCheck() {
    }

    /** 更新源解析出来的内容。字段缺失就是空串 / 0，不拿默认值糊弄。 */
    public static final class Release {
        public final String version;
        public final String url;
        public final String notes;
        public final String sha256;
        public final long versionCode;
        /** 清单里额外列的下载镜像（绝对地址），没有就是空数组。 */
        public final String[] mirrors;

        Release(String version, String url, String notes, String sha256, long versionCode, String[] mirrors) {
            this.version = version;
            this.url = url;
            this.notes = notes;
            this.sha256 = sha256;
            this.versionCode = versionCode;
            this.mirrors = mirrors == null ? new String[0] : mirrors;
        }

        public boolean hasDownload() {
            return !url.isEmpty() || mirrors.length > 0;
        }

        /**
         * 下载地址候选：清单指的那条排第一（它和更新源同域，源能通它就大概率能下），
         * 清单里的 mirror 依次跟上，重复的去掉。空数组表示没地方可下。
         */
        public String[] downloads() {
            List<String> out = new ArrayList<>();
            if (url != null && !url.isEmpty()) out.add(url);
            for (String mirror : mirrors) {
                if (mirror != null && !mirror.isEmpty() && !out.contains(mirror)) out.add(mirror);
            }
            return out.toArray(new String[0]);
        }
    }

    /**
     * 解析更新源返回的 JSON。三种形状都认：
     *
     * <pre>
     * 1) 本项目的 manifest.json   {"version":"1.56.0","versionCode":2177,"apk":{"file":"afs-guard.apk","sha256":"…"}}
     * 2) GitHub Releases API      {"tag_name":"v1.56.0","html_url":"…","body":"…","assets":[{"name":"…apk","browser_download_url":"…"}]}
     * 3) 自己搭的简单格式          {"version":"1.56.0","url":"…","notes":"…"}
     * </pre>
     *
     * @param source 更新源地址，用来把 manifest 里的相对文件名（apk.file）拼成下载地址
     * @return 解析不出来（没有版本号 / 不是 JSON）返回 null —— 宁可不说话，也别报"已是最新"
     */
    public static Release parse(String body, String source) {
        if (body == null || body.trim().isEmpty()) return null;
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (Exception error) {
            return null;
        }
        String version = normalize(json.optString("version"));
        if (version.isEmpty()) version = normalize(json.optString("tag_name"));   // GitHub API
        if (version.isEmpty()) return null;

        String sha256 = "";
        String url = "";

        JSONObject apk = json.optJSONObject("apk");
        if (apk != null) {
            sha256 = apk.optString("sha256");
            String file = apk.optString("file");
            if (!file.isEmpty()) url = sibling(source, file);
        }
        if (url.isEmpty()) url = apkAsset(json);                                  // GitHub API assets
        if (url.isEmpty()) url = json.optString("html_url");
        if (url.isEmpty()) url = json.optString("url");

        String notes = json.optString("notes");
        if (notes.isEmpty()) notes = json.optString("body");
        long code = json.optLong("versionCode", 0L);
        return new Release(version, url, notes, sha256, code, parseMirrors(json, source));
    }

    /**
     * 清单里的 {@code mirrors}：可以是绝对地址，也可以是相对文件名（同样按 source 同目录拼）。
     * 不认识的形状（不是数组、元素不是字符串）直接跳过，不因为一条坏数据废掉整份清单。
     */
    private static String[] parseMirrors(JSONObject json, String source) {
        JSONArray list = json.optJSONArray("mirrors");
        if (list == null) return new String[0];
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            Object raw = list.opt(i);
            if (!(raw instanceof String)) continue;   // 数字 / null 都不是地址，别硬转成字符串
            String item = ((String) raw).trim();
            if (item.isEmpty()) continue;
            if (!item.startsWith("https://") && !item.startsWith("http://")) item = sibling(source, item);
            if (!item.isEmpty() && !out.contains(item)) out.add(item);
        }
        return out.toArray(new String[0]);
    }

    /** 从 GitHub Releases 的 assets 里挑 APK 直链；没有 APK 就退回第一个附件。 */
    private static String apkAsset(JSONObject json) {
        JSONArray assets = json.optJSONArray("assets");
        if (assets == null) return "";
        String fallback = "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String link = asset.optString("browser_download_url");
            if (link.isEmpty()) continue;
            String name = asset.optString("name").toLowerCase(Locale.ROOT);
            if (name.endsWith(".apk")) return link;
            if (fallback.isEmpty()) fallback = link;
        }
        return fallback;
    }

    /**
     * 同目录下的另一个文件：…/latest/download/manifest.json + afs-guard.apk → …/latest/download/afs-guard.apk。
     * source 里没有斜杠就原样返回文件名。
     */
    public static String sibling(String source, String file) {
        if (file == null || file.isEmpty()) return "";
        if (source == null || source.isEmpty()) return file;
        int slash = source.lastIndexOf('/');
        if (slash < 0) return file;
        return source.substring(0, slash + 1) + file;
    }

    /** 去掉前缀 v/V 和空白。"v1.61.0 " → "1.61.0"。 */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        return value;
    }

    /**
     * 版本比较：a>b 返 1，相等 0，a<b 返 -1。
     * 按点切段逐段比；段数不同时缺的补 0（1.6 == 1.6.0）。
     * 每段拆成「数字 + 后缀」：数字用数值比，"6.10" 不会小于 "6.9"；
     * 数字相同的带后缀版本更早（1.6-beta < 1.6），预发布不该被当成新版。
     */
    public static int compare(String a, String b) {
        String[] left = normalize(a).split("\\.");
        String[] right = normalize(b).split("\\.");
        int size = Math.max(left.length, right.length);
        for (int i = 0; i < size; i++) {
            String l = i < left.length ? left[i] : "0";
            String r = i < right.length ? right[i] : "0";
            int result = compareSegment(l, r);
            if (result != 0) return result > 0 ? 1 : -1;
        }
        return 0;
    }

    private static int compareSegment(String left, String right) {
        long ln = prefixValue(left), rn = prefixValue(right);
        int result = Long.compare(ln, rn);
        if (result != 0) return result;
        String ls = suffix(left), rs = suffix(right);
        if (ls.isEmpty() && rs.isEmpty()) return 0;
        if (ls.isEmpty()) return 1;     // 正式版排在预发布之后
        if (rs.isEmpty()) return -1;
        return ls.compareTo(rs);
    }

    /** 前导数字部分的长度，最多 9 位（超过当 0，避免溢出）。 */
    private static int prefixLength(String value) {
        int i = 0;
        while (i < value.length() && i < 9 && Character.isDigit(value.charAt(i))) i++;
        return i;
    }

    private static long prefixValue(String value) {
        int size = prefixLength(value);
        if (size == 0) return 0L;
        try {
            return Long.parseLong(value.substring(0, size));
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private static String suffix(String value) {
        int size = prefixLength(value);
        return size == 0 ? value : value.substring(size);
    }

    /** 远端版本是否比本机新。任一为空都当"不知道"，返回 false。 */
    public static boolean newer(String remote, String local) {
        String r = normalize(remote), l = normalize(local);
        if (r.isEmpty() || l.isEmpty()) return false;
        return compare(r, l) > 0;
    }

    /**
     * 整条判定：两边都有 versionCode 就先比 code（发版号是单调的，名字可能被改乱），
     * 否则退回比版本名。远端解析失败（null）一律当"没有新版本"。
     */
    public static boolean newer(Release release, String localVersion, long localCode) {
        if (release == null) return false;
        if (release.versionCode > 0 && localCode > 0) return release.versionCode > localCode;
        return newer(release.version, localVersion);
    }
}
