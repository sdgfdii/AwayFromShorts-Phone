package com.afs.guard;

/**
 * 亲友解锁码的 TOTP 实现。
 *
 * 最后一层保险是 RFC 6238 的官方测试向量：如果密钥解码或 HMAC 截断写错了，
 * 那几组数字一定对不上。只测自己和自己一致是没有意义的。
 */
public final class UnlockCodeTest {

    private static int checks;

    /** RFC 6238 附录 B 的密钥：ASCII "12345678901234567890"。 */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        /* ---------- base32 ---------- */
        eq(RFC_SECRET, UnlockCode.encode("12345678901234567890".getBytes()), "encode rfc secret");
        eq("12345678901234567890", new String(UnlockCode.decode(RFC_SECRET)), "decode rfc secret");
        eq("", UnlockCode.encode(new byte[0]), "encode empty");
        eq(0, UnlockCode.decode("").length, "decode empty");
        eq(0, UnlockCode.decode(null).length, "decode null");
        eq(0, UnlockCode.decode("ABC0").length, "illegal char rejected");
        // 大小写、空格、连字符、填充都要容忍：家长手抄会带
        eq(20, UnlockCode.decode("gezd gnbv-gy3t qojq gez dgnbvgy3tqojq").length, "tolerant decode");
        // 编解码往返
        byte[] sample = new byte[]{(byte) 0x00, (byte) 0x7F, (byte) 0x80, (byte) 0xFF, 0x12};
        eq(5, UnlockCode.decode(UnlockCode.encode(sample)).length, "roundtrip length");
        eq(sample[4], UnlockCode.decode(UnlockCode.encode(sample))[4], "roundtrip byte");

        /* ---------- RFC 6238 向量（6 位，取后 6 位） ---------- */
        eq("287082", UnlockCode.code(RFC_SECRET, 59L), "t=59");
        eq("081804", UnlockCode.code(RFC_SECRET, 1111111109L), "t=1111111109");
        eq("050471", UnlockCode.code(RFC_SECRET, 1111111111L), "t=1111111111");
        eq("005924", UnlockCode.code(RFC_SECRET, 1234567890L), "t=1234567890");
        eq("279037", UnlockCode.code(RFC_SECRET, 2000000000L), "t=2000000000");
        eq("353130", UnlockCode.code(RFC_SECRET, 20000000000L), "t=20000000000");

        /* ---------- 校验 ---------- */
        ok(UnlockCode.verify(RFC_SECRET, "287082", 59L), "verify own code");
        ok(UnlockCode.verify(RFC_SECRET, "287082", 60L), "next step still inside drift");
        ok(UnlockCode.verify(RFC_SECRET, "287082", 89L), "drift +1 step accepted");
        ok(UnlockCode.verify(RFC_SECRET, "287082", 30L), "drift -1 step accepted");
        ok(UnlockCode.verify(RFC_SECRET, "287082", 90L) == false, "two steps later rejected");
        ok(UnlockCode.verify(RFC_SECRET, "279037", 2000000000L - 60L) == false,
                "code from a later step rejected");
        ok(UnlockCode.verify(RFC_SECRET, "28708", 59L) == false, "five digits rejected");
        ok(UnlockCode.verify(RFC_SECRET, "2870821", 59L) == false, "seven digits rejected");
        ok(UnlockCode.verify(RFC_SECRET, "", 59L) == false, "empty rejected");
        ok(UnlockCode.verify("", "287082", 59L) == false, "no secret rejected");
        ok(UnlockCode.verify("!!!!", "287082", 59L) == false, "broken secret rejected");
        ok(UnlockCode.verify(RFC_SECRET, "287083", 59L) == false, "wrong code rejected");

        /* ---------- 输入清洗 ---------- */
        eq("123456", UnlockCode.digits("12 34-56"), "strip spaces and dashes");
        eq("123456", UnlockCode.digits("１２３４５６"), "full width digits");
        eq("", UnlockCode.digits(null), "null digits");
        ok(UnlockCode.verify(RFC_SECRET, "  287 082 ", 59L), "spaced input still accepted");

        /* ---------- 倒计时 ---------- */
        eq(30, UnlockCode.secondsLeft(0L), "full step left");
        eq(1, UnlockCode.secondsLeft(29L), "one second left");
        eq(30, UnlockCode.secondsLeft(30L), "new step");

        /* ---------- 显示分组 ---------- */
        eq("ABCD EFGH", UnlockCode.grouped("ABCDEFGH"), "grouped by four");
        eq("ABCD EFG", UnlockCode.grouped("ABCDEFG"), "grouped short");

        /* ---------- 新密钥 ---------- */
        String fresh = UnlockCode.newSecret();
        eq(32, fresh.length(), "new secret length");
        eq(20, UnlockCode.decode(fresh).length, "new secret decodes to 20 bytes");
        ok(UnlockCode.newSecret().equals(fresh) == false, "two secrets differ");

        System.out.println("Unlock code: " + checks + " checks passed");
    }
}
