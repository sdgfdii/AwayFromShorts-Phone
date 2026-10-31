package com.afs.guard;

/** 版本比较：v 前缀、不同段数、非数字段都要稳定，不能因为格式差异误报"有新版本"。 */
public final class UpdateCheckTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    public static void main(String[] args) {
        eq("1.61.0", UpdateCheck.normalize("v1.61.0"), "strip v");
        eq("1.61.0", UpdateCheck.normalize("V1.61.0"), "strip upper v");
        eq("1.61.0", UpdateCheck.normalize("  1.61.0 "), "trim");
        eq("", UpdateCheck.normalize(null), "null");

        eq(0, UpdateCheck.compare("1.58.12", "1.58.12"), "same");
        eq(1, UpdateCheck.compare("1.58.13", "1.58.12"), "patch higher");
        eq(-1, UpdateCheck.compare("1.58.2", "1.58.12"), "numeric not lexical");
        eq(1, UpdateCheck.compare("1.59.0", "1.58.99"), "minor higher");
        eq(-1, UpdateCheck.compare("1.7.0", "1.10.0"), "seven below ten");
        eq(0, UpdateCheck.compare("1.6", "1.6.0"), "missing segment counts as zero");
        eq(0, UpdateCheck.compare("v1.6.0", "1.6.0"), "prefix ignored");
        eq(1, UpdateCheck.compare("2.0.0", "1.99.99"), "major wins");
        eq(-1, UpdateCheck.compare("1.6-beta", "1.6"), "suffix sorts before release");

        eq(true, UpdateCheck.newer("1.61.0", "1.58.12"), "newer true");
        eq(false, UpdateCheck.newer("1.58.12", "1.58.12"), "same is not newer");
        eq(false, UpdateCheck.newer("1.58.11", "1.58.12"), "older is not newer");
        eq(false, UpdateCheck.newer("", "1.58.12"), "empty remote");
        eq(false, UpdateCheck.newer("1.61.0", ""), "empty local");

        /* ---------- 更新源解析 ---------- */
        eq(true, !UpdateCheck.DEFAULT_URL.isEmpty() && UpdateCheck.DEFAULT_URL.startsWith("https://"),
                "默认更新源已配置且是 https");

        String source = "https://github.com/sdgfdii/AwayFromShorts-Phone/releases/latest/download/manifest.json";
        String manifest = "{\"version\":\"1.56.0\",\"versionCode\":2177,"
                + "\"apk\":{\"file\":\"afs-guard.apk\",\"sha256\":\"c8ea1b\"}}";
        UpdateCheck.Release built = UpdateCheck.parse(manifest, source);
        eq("1.56.0", built.version, "manifest version");
        eq(2177L, built.versionCode, "manifest versionCode");
        eq("c8ea1b", built.sha256, "manifest sha256");
        eq("https://github.com/sdgfdii/AwayFromShorts-Phone/releases/latest/download/afs-guard.apk",
                built.url, "相对文件名拼成下载地址");
        eq(true, built.hasDownload(), "有下载地址");

        String api = "{\"tag_name\":\"v1.58.0\",\"html_url\":\"https://x/tag\",\"body\":\"修了点东西\","
                + "\"assets\":[{\"name\":\"notes.txt\",\"browser_download_url\":\"https://x/notes.txt\"},"
                + "{\"name\":\"afs-guard.apk\",\"browser_download_url\":\"https://x/afs.apk\"}]}";
        UpdateCheck.Release fromApi = UpdateCheck.parse(api, source);
        eq("1.58.0", fromApi.version, "tag_name 去掉 v");
        eq("https://x/afs.apk", fromApi.url, "assets 里优先 APK");
        eq("修了点东西", fromApi.notes, "body 当说明");
        eq(0L, fromApi.versionCode, "没有 versionCode 就是 0");

        String simple = "{\"version\":\"2.0.0\",\"url\":\"https://x/dl\",\"notes\":\"大版本\"}";
        UpdateCheck.Release plain = UpdateCheck.parse(simple, source);
        eq("2.0.0", plain.version, "简单格式版本");
        eq("https://x/dl", plain.url, "简单格式地址");
        eq("大版本", plain.notes, "简单格式说明");

        eq(true, UpdateCheck.parse("", source) == null, "空内容解析失败");
        eq(true, UpdateCheck.parse("not json", source) == null, "不是 JSON 算失败");
        eq(true, UpdateCheck.parse("{\"name\":\"x\"}", source) == null, "没有版本号算失败");

        eq("https://a/b/c.apk", UpdateCheck.sibling("https://a/b/manifest.json", "c.apk"), "同目录拼文件");
        eq("c.apk", UpdateCheck.sibling("", "c.apk"), "没有源就返回文件名");
        eq("", UpdateCheck.sibling(source, ""), "没有文件名返回空");

        /* 两边都有 versionCode 时以 code 为准，版本名被改乱也不误报 */
        UpdateCheck.Release codeOnly = UpdateCheck.parse(
                "{\"version\":\"9.9.9\",\"versionCode\":2100,\"apk\":{\"file\":\"a.apk\"}}", source);
        eq(false, UpdateCheck.newer(codeOnly, "1.67.0", 2206), "code 更旧就不算新");
        eq(true, UpdateCheck.newer(codeOnly, "1.0.0", 2000), "code 更新就算新");
        eq(false, UpdateCheck.newer(null, "1.67.0", 2206), "解析失败当没有新版本");

        /* ---------- 镜像 ---------- */
        eq(true, UpdateCheck.sources().length >= 2, "官方源 + 至少一个镜像");
        eq(UpdateCheck.DEFAULT_URL, UpdateCheck.sources()[0], "官方源永远排第一");
        eq(true, UpdateCheck.sources()[1].startsWith("https://gh-proxy.com/"), "镜像紧随其后");
        eq(true, UpdateCheck.mirrored(UpdateCheck.sources()[1]), "镜像认得出来");
        eq(false, UpdateCheck.mirrored(UpdateCheck.DEFAULT_URL), "官方源不算镜像");
        eq(false, UpdateCheck.mirrored(null), "空源不算镜像");

        String withMirrors = "{\"version\":\"1.70.0\",\"versionCode\":2210,"
                + "\"apk\":{\"file\":\"afs-guard.apk\"},"
                + "\"mirrors\":[\"https://m1/afs.apk\",\"\",\"https://m1/afs.apk\",\"extra.apk\"]}";
        UpdateCheck.Release multi = UpdateCheck.parse(withMirrors, source);
        eq(2, multi.mirrors.length, "mirrors 去空去重");
        eq("https://m1/afs.apk", multi.mirrors[0], "绝对地址原样保留");
        eq("https://github.com/sdgfdii/AwayFromShorts-Phone/releases/latest/download/extra.apk",
                multi.mirrors[1], "相对项按同目录拼");
        eq(3, multi.downloads().length, "下载候选 = 主地址 + 镜像");
        eq("https://github.com/sdgfdii/AwayFromShorts-Phone/releases/latest/download/afs-guard.apk",
                multi.downloads()[0], "主地址排第一");
        eq(0, built.mirrors.length, "清单没有 mirrors 就是空数组");
        eq(1, built.downloads().length, "没有镜像就只有一个下载候选");

        UpdateCheck.Release onlyMirror = UpdateCheck.parse(
                "{\"version\":\"1.0.0\",\"mirrors\":[\"https://m/a.apk\"]}", source);
        eq(true, onlyMirror.hasDownload(), "只有镜像也算能下载");
        eq("", onlyMirror.url, "没有 url 就是空串");
        eq(1, onlyMirror.downloads().length, "候选里只剩镜像");

        eq(0, UpdateCheck.parse("{\"version\":\"1.0.0\",\"mirrors\":\"https://m/a.apk\"}", source).mirrors.length,
                "mirrors 不是数组就忽略");
        eq(0, UpdateCheck.parse("{\"version\":\"1.0.0\",\"mirrors\":[1,null]}", source).mirrors.length,
                "mirrors 里不是字符串就忽略");

        System.out.println("Update check: " + checks + " checks passed");
    }
}
