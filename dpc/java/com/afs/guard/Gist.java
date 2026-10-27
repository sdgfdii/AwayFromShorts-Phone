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

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 12000;

    private Gist() {
    }

    /** Verify the authenticated account owns the destination before enabling sync. */
    static void verify(SyncTarget target, String token) throws Exception {
        if (token == null || !token.matches("[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("GitHub token 格式无效");
        org.json.JSONObject user = getApi("https://api.github.com/user", token);
        if (!target.owner.equalsIgnoreCase(user.getString("login")))
            throw new IllegalArgumentException("token 所属账号与 GitHub 用户名不一致");
        org.json.JSONObject gist = getApi(target.apiUrl(), token);
        if (!target.gistId.equalsIgnoreCase(gist.getString("id"))
                || !target.owner.equalsIgnoreCase(gist.getJSONObject("owner").getString("login")))
            throw new IllegalArgumentException("这个 Gist 不属于所填写的账号");
    }

    private static org.json.JSONObject getApi(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestProperty("Authorization", "token " + token);
            c.setRequestProperty("User-Agent", "AFS-Guard");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int status = c.getResponseCode();
            if (status != 200) throw new IllegalStateException("GitHub 验证失败 HTTP " + status);
            return new org.json.JSONObject(read(c.getInputStream()));
        } finally { c.disconnect(); }
    }

    /** Authenticated API read avoids stale raw CDN snapshots during a merge. */
    static org.json.JSONObject readLedger(SyncConfig.Session session) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(session.target.apiUrl()).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestProperty("Authorization", "token " + session.token);
            c.setRequestProperty("User-Agent", "AFS-Guard");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() / 100 != 2) throw new IllegalStateException("读取学习台账失败 HTTP " + c.getResponseCode());
            org.json.JSONObject gist = new org.json.JSONObject(read(c.getInputStream()));
            org.json.JSONObject entry = gist.getJSONObject("files").optJSONObject("checkin-ledger.json");
            if (entry == null) return new org.json.JSONObject("{\"goals\":[],\"recs\":[],\"rev\":0}");
            String content = entry.optBoolean("truncated") ? StudyGate.fetchRaw(entry.getString("raw_url")) : entry.getString("content");
            org.json.JSONObject ledger = new org.json.JSONObject(content);
            ledger.getJSONArray("goals");
            ledger.getJSONArray("recs");
            return ledger;
        } finally { c.disconnect(); }
    }

    static org.json.JSONObject readConfig(SyncConfig.Session session) throws Exception {
        org.json.JSONObject gist = getApi(session.target.apiUrl(), session.token);
        org.json.JSONObject entry = gist.getJSONObject("files").optJSONObject("hub-config.json");
        if (entry == null) return new org.json.JSONObject();
        String raw = entry.optBoolean("truncated") ? StudyGate.fetchRaw(entry.getString("raw_url")) : entry.getString("content");
        return new org.json.JSONObject(raw);
    }

    /**
     * PATCH 若干个 gist 文件（Gist 中心仓库的通用写入口）。
     * 调用方自己保证"读-改-写"只动自己权威的文件：study-gate.json / pc-activity.json
     * 是 PC 的，hub-config.json 只换自己的分节 —— 两边乱写会互相覆盖。
     */
    static String patchFiles(SyncConfig.Session session, java.util.Map<String, String> files) {
        String token = session.token;
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
            c = (HttpURLConnection) new URL(session.target.apiUrl()).openConnection();
            c.setInstanceFollowRedirects(false);
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
