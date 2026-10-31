package com.afs.guard;

import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 标准 TOTP（RFC 6238）：HMAC-SHA1、6 位、30 秒一步。
 *
 * 为什么不做自研算法：标准 TOTP 意味着家长**不用装第二个 App**，
 * 把密钥加进手机自带的验证器（或 Google Authenticator / 1Password / 微软验证器）就能读数。
 * 孩子端只验不算 —— 界面上永远不显示当前码。
 *
 * 校验留 ±1 步的窗口（±30 秒），只为容忍两端时钟差；
 * 再多就等于把 6 位码的有效期拉长到 90 秒，暴力试的空间变大。
 */
public final class UnlockCode {

    public static final int DIGITS = 6;
    public static final int STEP_SECONDS = 30;
    public static final int DRIFT_STEPS = 1;

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private UnlockCode() {
    }

    /** RFC 4648 base32，不带填充；大小写、空格、连字符都容忍（家长手抄容易带）。 */
    public static byte[] decode(String text) {
        if (text == null) return new byte[0];
        String clean = text.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.US);
        if (clean.isEmpty()) return new byte[0];
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[clean.length() * 5 / 8 + 1];
        int count = 0;
        for (int i = 0; i < clean.length(); i++) {
            int value = ALPHABET.indexOf(clean.charAt(i));
            if (value < 0) return new byte[0];          // 非法字符整串作废，不猜
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out[count++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        byte[] exact = new byte[count];
        System.arraycopy(out, 0, exact, 0, count);
        return exact;
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(ALPHABET.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        return sb.toString();
    }

    /** 新密钥：20 字节 = 160 位，标准 TOTP 长度，编成 32 个 base32 字符。 */
    public static String newSecret() {
        byte[] raw = new byte[20];
        new SecureRandom().nextBytes(raw);
        return encode(raw);
    }

    /** 第 counter 步的码。counter = epochSecond / 30。 */
    public static String code(byte[] secret, long counter, int digits) {
        if (secret == null || secret.length == 0) return "";
        try {
            byte[] data = new byte[8];
            long value = counter;
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (value & 0xFF);
                value >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int mod = 1;
            for (int i = 0; i < digits; i++) mod *= 10;
            return String.format(Locale.US, "%0" + digits + "d", binary % mod);
        } catch (Exception error) {
            return "";
        }
    }

    public static String code(String secret, long epochSecond) {
        return code(decode(secret), epochSecond / STEP_SECONDS, DIGITS);
    }

    /** 只留数字；家长打进来带空格或全角字符也能读。 */
    public static String digits(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
            else if (ch >= '０' && ch <= '９') sb.append((char) ('0' + (ch - '０')));
        }
        return sb.toString();
    }

    /** 校验：位数不对直接否；±drift 步里任一命中即通过。 */
    public static boolean verify(String secret, String input, long epochSecond) {
        byte[] key = decode(secret);
        if (key.length == 0) return false;
        String value = digits(input);
        if (value.length() != DIGITS) return false;
        long step = epochSecond / STEP_SECONDS;
        for (int shift = -DRIFT_STEPS; shift <= DRIFT_STEPS; shift++) {
            String expected = code(key, step + shift, DIGITS);
            if (!expected.isEmpty() && constantTimeEquals(expected, value)) return true;
        }
        return false;
    }

    /** 当前这一步还剩几秒（给家长端倒计时用）。 */
    public static int secondsLeft(long epochSecond) {
        long used = epochSecond % STEP_SECONDS;
        return (int) (STEP_SECONDS - used);
    }

    /** 密钥分组显示：ABCD EFGH …，家长手抄不容易串行。 */
    public static String grouped(String secret) {
        if (secret == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < secret.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append(' ');
            sb.append(secret.charAt(i));
        }
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
