package com.afs.guard;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 和 PC 端共用的那个 secret Gist（写侧）。
 *
 * 读走 {@link StudyGate}：它本来就要拉 study-gate.json，同一条路子，
 * 没必要为打卡页再写一份 GET（重复的 HttpURLConnection 代码是最容易两边跑偏的地方）。
 *
 * 写谁：{@link Hub} 用 {@link #patchFiles} 推手机自己的权威文件
 * （checkin-ledger.json / phone-activity.json / hub-config.json 的 phone 分节）。
 * 各文件的权威归属写在 Hub 的类注释里，两边都不许越界写别人的文件。
 *
 * 老的单队列写入口（patchQueue → checkin-queue.json）已随 CheckinStore 一起删掉：
 * "手机写队列 → PC 代记账"那条链路 2026-10-23 起就退役了（Hub 直推台账）。
 */
final class Gist {

    /**
     * 写入凭据 = 一个 GitHub token，存在老 CheckinStore 用过的同一个
     * prefs/key 里（afs_checkin / token）—— 升级后不用重新填。
     */
    private static final String SP_TOKEN = "afs_checkin";
    private static final String K_TOKEN = "token";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 12000;

    private Gist() {
    }

    /** Authenticated API read avoids stale raw CDN snapshots during a merge. */
    static org.json.JSONObject readLedger(android.content.Context context) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL("https://api.github.com/gists/" + StudyGate.GIST_ID).openConnection();
        try {
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestProperty("Authorization", "token " + token(context));
            c.setRequestProperty("User-Agent", "AFS-Guard");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() / 100 != 2) throw new IllegalStateException("读取学习台账失败 HTTP " + c.getResponseCode());
            org.json.JSONObject gist = new org.json.JSONObject(read(c.getInputStream()));
            org.json.JSONObject entry = gist.getJSONObject("files").getJSONObject("checkin-ledger.json");
            String content = entry.optBoolean("truncated") ? StudyGate.fetchRaw(entry.getString("raw_url")) : entry.getString("content");
            org.json.JSONObject ledger = new org.json.JSONObject(content);
            ledger.getJSONArray("goals");
            ledger.getJSONArray("recs");
            return ledger;
        } finally { c.disconnect(); }
    }

    private static android.content.SharedPreferences sp(android.content.Context c) {
        return c.getSharedPreferences(SP_TOKEN, android.content.Context.MODE_PRIVATE);
    }

    /* ---------- 写入凭据 ---------- */

    static String token(android.content.Context c) {
        String t = sp(c).getString(K_TOKEN, "");
        if (t != null && !t.trim().isEmpty()) {
            return t.trim();
        }
        return BuildConfig.PREFILL_TOKEN == null ? "" : BuildConfig.PREFILL_TOKEN.trim();
    }

    /**
     * 把构建期注入的 token 落到 prefs。
     *
     * 预填构建（PREFILL 非空）的 token 是权威 —— 覆盖安装即迁移（换 GitHub 账号靠它）。
     * 公开构建（PREFILL 空）不碰 prefs，装了不影响已有 token。
     */
    static void seedToken(android.content.Context c) {
        String pre = BuildConfig.PREFILL_TOKEN == null ? "" : BuildConfig.PREFILL_TOKEN.trim();
        if (pre.isEmpty()) {
            return;
        }
        String cur = sp(c).getString(K_TOKEN, "");
        if (pre.equals(cur == null ? "" : cur.trim())) {
            return;
        }
        sp(c).edit().putString(K_TOKEN, pre).apply();
    }

    static void setToken(android.content.Context c, String t) {
        sp(c).edit().putString(K_TOKEN, t == null ? "" : t.trim()).apply();
    }

    static boolean hasToken(android.content.Context c) {
        return !token(c).isEmpty();
    }

    /**
     * PATCH 若干个 gist 文件（Gist 中心仓库的通用写入口）。
     * 调用方自己保证"读-改-写"只动自己权威的文件：study-gate.json / pc-activity.json
     * 是 PC 的，hub-config.json 只换自己的分节 —— 两边乱写会互相覆盖。
     */
    static String patchFiles(String token, java.util.Map<String, String> files) {
        if (token == null || token.trim().isEmpty()) {
            return "没有 token";
        }
        if (files == null || files.isEmpty()) {
            return "没有要写的内容";
        }
        HttpURLConnection c = null;
        try {
            StringBuilder sb = new StringBuilder("{\"files\":{");
            boolean first = true;
            for (java.util.Map.Entry<String, String> e : files.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":{\"content\":")
                  .append(quote(e.getValue())).append('}');
            }
            sb.append("}}");   // 收口 content 外层的 files 对象 + 根对象
            String body = sb.toString();
            c = (HttpURLConnection) new URL("https://api.github.com/gists/"
                    + StudyGate.GIST_ID).openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod("PATCH");
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "token " + token.trim());
            c.setRequestProperty("User-Agent", "AFS-Guard");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.getBytes(UTF8);
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            if (code / 100 == 2) {
                return null;
            }
            String detail = "";
            try {
                InputStream es = c.getErrorStream();
                if (es != null) {
                    detail = read(es);
                }
            } catch (Throwable ignored) {
            }
            if (detail.length() > 160) {
                detail = detail.substring(0, 160);
            }
            return "HTTP " + code + (detail.isEmpty() ? "" : " " + detail);
        } catch (Exception e) {
            String m = e.getMessage();
            return m == null ? e.getClass().getSimpleName() : m;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 极简 JSON 字符串转义（只处理必须的几种，避免引第三方库）。 */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String read(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }
}
