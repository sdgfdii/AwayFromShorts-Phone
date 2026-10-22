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
 * 读走 {@link StudyGate}：它本来就要拉 study-gate.json 和队列，同一条路子，
 * 没必要为打卡页再写一份 GET（重复的 HttpURLConnection 代码是最容易两边跑偏的地方）。
 *
 * 写只碰 checkin-queue.json 这一个文件 —— study-gate.json 是 PC 的，
 * 两边都写会互相覆盖，打卡数据永远写不进去。
 */
final class Gist {

    static final String FILE_QUEUE = StudyGate.QUEUE_FILE;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 12000;

    private Gist() {
    }

    /** 读队列原文（免鉴权，secret gist 的 raw 猜不到但公开可读）。 */
    static String getQueue() throws Exception {
        return StudyGate.fetchRaw(StudyGate.queueUrl());
    }

    /** PATCH 队列文件；返回 null 表示成功，否则是错误描述。 */
    static String patchQueue(String token, String content) {
        if (token == null || token.trim().isEmpty()) {
            return "没有 token";
        }
        HttpURLConnection c = null;
        try {
            String body = "{\"files\":{\"" + FILE_QUEUE + "\":{\"content\":"
                    + quote(content) + "}}}";
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
                c.disconnect();
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
