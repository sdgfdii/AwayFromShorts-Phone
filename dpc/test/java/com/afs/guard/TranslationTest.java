package com.afs.guard;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 翻译表解析的离线回归。
 *
 * 钉住三类真实踩过的坑：
 *  1. 译文里的换行没转义 → 一条记录劈成两行，后半句变成一条"没有 tab 的行"被丢掉。
 *  2. 以 # 开头的真实文案（#RRGGBB，如 FFD700）被当注释整条丢弃。
 *  3. 空译文与「译成空串」混淆：空串等于没翻译会退回中文，必须显式写 \e。
 *
 * 另外扫一遍真实 assets/i18n_en.txt，检查没有空键、没有重复键、没有残留的裸换行。
 */
final class TranslationTest {

    private static int checks;

    private static void ok(boolean condition, String what) {
        checks++;
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": want " + want + " got " + got);
        }
    }

    private static Map<String, String> parse(String text) {
        try {
            return T.parse(new BufferedReader(new java.io.StringReader(text)));
        } catch (java.io.IOException error) {
            throw new AssertionError("parse threw " + error);
        }
    }

    public static void main(String[] args) throws Exception {
        /* 1. 基本键值 + 注释 */
        Map<String, String> basic = parse("# 注释行\n保存\tSave\n取消\tCancel\n");
        eq(2, basic.size(), "comments are skipped");
        eq("Save", basic.get("保存"), "plain pair");

        /* 2. 值里的 \n 必须还原成换行，且不能把后面那条吞掉 */
        Map<String, String> multi = parse("提醒 \tReminder\n\\n提醒 1\t\\nReminder 1\n完成\tDone\n");
        eq(3, multi.size(), "escaped newline keeps the next entry");
        eq("\nReminder 1", multi.get("\n提醒 1"), "leading newline key");
        eq("Done", multi.get("完成"), "entry after escaped newline");

        /* 3. # 开头的真实文案不能被当注释 */
        Map<String, String> hash = parse("#RRGGBB，如 FFD700\t#RRGGBB, e.g. FFD700\n");
        eq("#RRGGBB, e.g. FFD700", hash.get("#RRGGBB，如 FFD700"), "hash key survives");

        /* 4. \e = 译成空串；空值 = 没翻译（键根本不该进表） */
        Map<String, String> sentinel = parse("个\t\\e\n 张\t\n页\tpages\n");
        eq(2, sentinel.size(), "empty value is dropped, sentinel kept");
        ok(sentinel.containsKey("个"), "sentinel key present");
        eq("", sentinel.get("个"), "sentinel maps to empty string");
        ok(!sentinel.containsKey(" 张"), "blank value dropped");
        eq("pages", sentinel.get("页"), "normal value kept");

        /* 5. 反斜杠转义 */
        Map<String, String> slash = parse("路径\\n下一行\tpath\\nnext\n反斜杠\tback\\\\slash\n");
        eq("path\nnext", slash.get("路径\n下一行"), "escaped newline in value");
        eq("back\\slash", slash.get("反斜杠"), "escaped backslash");

        /* 6. 坏行不该崩：没有 tab、只有 tab、空行 */
        Map<String, String> broken = parse("\n\tvalue\nkey\nkey2\tok\n");
        eq(1, broken.size(), "malformed lines ignored");
        eq("ok", broken.get("key2"), "well-formed line still parsed");

        /* 7. 真实翻译表：没有空键、没有重复键、没有裸换行 */
        File asset = new File(args.length > 0 ? args[0] : "assets/i18n_en.txt");
        ok(asset.isFile(), "asset exists: " + asset.getAbsolutePath());
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(asset), "UTF-8"));
        Set<String> keys = new HashSet<>();
        int pairs = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || (line.charAt(0) == '#' && line.indexOf('\t') < 0)) {
                continue;
            }
            int tab = line.indexOf('\t');
            ok(tab > 0, "every entry has a non-empty key: " + line);
            String key = line.substring(0, tab);
            ok(keys.add(key), "duplicate key: " + key);
            // 空值在运行时会被当成「没翻译」丢掉，不算正式条目。
            if (!line.substring(tab + 1).trim().isEmpty()) {
                pairs++;
            }
        }
        reader.close();
        ok(pairs > 1500, "catalog is populated, got " + pairs);

        /* 8. 真实翻译表经 parse 后条目数不缩水（值全空 = 全被丢掉，说明格式坏了） */
        BufferedReader again = new BufferedReader(
                new InputStreamReader(new FileInputStream(asset), "UTF-8"));
        Map<String, String> real = T.parse(again);
        again.close();
        ok(real.size() == pairs, "parse keeps every pair: " + real.size() + " vs " + pairs);

        /* 9. 抽查几条真实译文 */
        eq("Calendar", real.get("日历"), "calendar");
        eq("", real.get("个"), "classifier drops in English");
        ok(real.containsKey("#RRGGBB，如 FFD700"), "hash entry in real catalog");

        System.out.println("Translation table: " + checks + " checks passed");
    }
}
