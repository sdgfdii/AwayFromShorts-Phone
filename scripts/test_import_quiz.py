"""Offline converter regressions; optional Java bridge checks the actual Android parser."""
import argparse
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch

import import_quiz as quiz


class ImportQuizTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.args = argparse.Namespace(format="auto", encoding="auto", subject="高数", name=None, sheet=None)

    def file(self, name, value):
        p = self.folder / name
        p.write_bytes(value.encode("utf-8") if isinstance(value, str) else value)
        return p

    def convert(self, name, content):
        return quiz.convert([self.file(name, content)], self.args)

    def cli(self, *args):
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            return quiz.main(list(map(str, args)))

    def test_json_numeric_and_custom_subject(self):
        root, encoded, _ = self.convert("bank.json", json.dumps({"subject": "电路", "questions": [
            {"题干": "电阻值？", "答案": 12, "题型": "填空"}]}))
        q = root["questions"][0]
        self.assertEqual((q["subject"], q["answer"], q["type"]), ("电路", "12", "short"))
        self.assertEqual(json.loads(encoded)["questions"][0], q)
        self.assertNotIn("id", q)

    def test_starter_round_trip(self):
        p = Path(__file__).resolve().parents[1] / "dpc/assets/quiz/starter.json"
        root, _, duplicates = quiz.convert([p, p], self.args)
        self.assertEqual((len(root["questions"]), duplicates), (24, 24))
        self.assertEqual(root["questions"][14]["subject"], "线代")

    def test_csv_quoted_multiline_formula_and_gbk(self):
        text = '科目,题干,A,B,C,D,答案,解析\r\n高数,"求 $\\frac{1}{2}$,\n含""引号""",0,1,,,B,解析\r\n'
        root, _, _ = self.convert("bank.csv", text.encode("gb18030"))
        q = root["questions"][0]
        self.assertIn('\\frac{1}{2}$,\n含"引号"', q["stem"])
        self.assertEqual((q["options"], q["answer"], q["type"]), (["0", "1"], "B", "single"))

    def test_tsv(self):
        root, _, _ = self.convert("bank.tsv", '题干\t答案\t章节\n求导\t2x\t导数\n')
        self.assertEqual(root["questions"][0]["chapter"], "导数")

    def test_text_blocks_and_math_newlines(self):
        root, _, _ = self.convert("bank.txt", quiz.template("txt"))
        self.assertEqual(len(root["questions"]), 2)
        self.assertEqual(root["questions"][1]["subject"], "线代")
        self.assertIn(r"\lim_{x\to0}", root["questions"][0]["stem"])

    def test_all_templates(self):
        for fmt in ("json", "csv", "txt"):
            with self.subTest(fmt=fmt):
                root, _, _ = self.convert("template." + fmt, quiz.template(fmt))
                self.assertGreaterEqual(len(root["questions"]), 1)

    def test_dedup_ignores_source_and_chapter(self):
        rows = [{"stem": "题", "answer": "1", "source": "a"}, {"stem": "题", "answer": "1", "source": "b", "chapter": "新章"}]
        root, _, n = self.convert("bank.json", json.dumps(rows))
        self.assertEqual((len(root["questions"]), n), (1, 1))

    def test_id_conflict(self):
        with self.assertRaisesRegex(ValueError, "id a 对应不同题目"):
            self.convert("bank.json", '[{"id":"a","stem":"q","answer":"1"},{"id":"a","stem":"q","answer":"2"}]')

    def test_invalid_file_does_not_write_or_change_output(self):
        src = self.file("bad.json", '[{"stem":"valid","answer":"1"},{"stem":"no answer"}]')
        out = self.file("out.json", "original")
        self.assertEqual(self.cli(src, "-o", out, "--force"), 2)
        self.assertEqual(out.read_text(), "original")

    def test_duplicate_headers_and_json_keys(self):
        for name, s in [("x.csv", "stem,题干,answer\na,b,c"), ("x.json", '[{"stem":"a","stem":"b","answer":"1"}]')]:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "重复"):
                self.convert(name, s)

    def test_csv_malformed_and_option_gaps(self):
        for s in ['stem,answer\n"unclosed,1', 'stem,answer\na,1,extra', 'stem,A,B,C,answer\nq,1,,3,C']:
            with self.subTest(s=s), self.assertRaises(ValueError):
                self.convert("x.csv", s)

    def test_bad_choices(self):
        for answer in ("D", "AA", "", "1"):
            with self.subTest(answer=answer), self.assertRaises(ValueError):
                quiz.normalize({"stem": "q", "type": "single", "options": ["1", "2"], "answer": answer}, "高数", "")

    def test_multi_and_judge(self):
        q = quiz.normalize({"stem": "q", "options": ["a", "b", "c"], "answer": ["C", "A"]}, "高数", "")
        self.assertEqual((q["type"], q["answer"]), ("multiple", "AC"))
        for value, answer in [(True, "A"), (False, "B"), ("错误", "B"), ("√", "A")]:
            q = quiz.normalize({"stem": "q", "type": "判断题", "answer": value}, "高数", "")
            self.assertEqual(q["answer"], answer)

    def test_invalid_type_subject_id_and_nonfinite(self):
        for q in [{"type": "bad"}, {"subject": "a\nb"}, {"id": "has space"}, {"answer": float("nan")}]:
            with self.subTest(q=q), self.assertRaises(ValueError):
                quiz.normalize(dict({"stem": "q", "answer": "1"}, **q), "高数", "")

    def test_text_requires_markers_and_block_separators(self):
        for s in ["unstructured question", "[题目] a\n[答案] b\n[题目] c\n[答案] d"]:
            with self.assertRaises(ValueError):
                self.convert("x.txt", s)

    def test_options_json_column(self):
        q = quiz.normalize({"stem": "q", "answer": "B", "options": '["a","b"]'}, "线性代数", "")
        self.assertEqual(q["subject"], "线代")
        self.assertEqual(q["options"], ["a", "b"])

    def test_file_protection_and_check_only(self):
        src = self.file("template.json", quiz.template("json"))
        out = self.folder / "out.json"
        self.assertEqual(self.cli(src, "--check"), 0)
        self.assertFalse(out.exists())
        self.assertEqual(self.cli(src, "-o", out), 0)
        self.assertEqual(self.cli(src, "-o", out), 2)
        self.assertEqual(self.cli(src, "-o", out, "--force"), 0)
        before = src.read_bytes()
        self.assertEqual(self.cli(src, "-o", src, "--force"), 2)
        self.assertEqual(src.read_bytes(), before)

    def test_limits(self):
        with patch.object(quiz, "MAX_QUESTIONS", 1), self.assertRaisesRegex(ValueError, "5000"):
            self.convert("x.json", '[{"stem":"a","answer":"1"},{"stem":"b","answer":"1"}]')
        with patch.object(quiz, "MAX_BYTES", 1), self.assertRaisesRegex(ValueError, "4MB"):
            self.convert("x.json", '[{"stem":"a","answer":"1"}]')

    def test_xlsx_read_and_formula_rejection(self):
        class Cell:
            def __init__(self, value, data_type="s"):
                self.value, self.data_type = value, data_type
        rows = [[Cell("题干"), Cell("答案")], [Cell("求值"), Cell(3)]]
        sheet = types.SimpleNamespace(max_row=2, max_column=2, iter_rows=lambda: iter(rows))
        book = types.SimpleNamespace(active=sheet, close=lambda: None)
        loader = types.SimpleNamespace(load_workbook=lambda *a, **kw: book)
        with patch.dict("sys.modules", {"openpyxl": loader}):
            root, _, _ = self.convert("bank.xlsx", b"fake book handled by reader mock")
            self.assertEqual(root["questions"][0]["answer"], "3")
            rows[1][1] = Cell("=1+2", "f")
            with self.assertRaisesRegex(ValueError, "公式单元格"):
                self.convert("bank.xlsx", b"fake book")

    def test_push_uses_explicit_adb_and_fixed_remote_name(self):
        path = self.file("has spaces;literal.json", quiz.template("json"))
        args = argparse.Namespace(adb="adb-test", serial="test-device")
        with patch.object(quiz.subprocess, "run") as run, redirect_stdout(io.StringIO()):
            run.return_value = types.SimpleNamespace(returncode=0, stdout="device\n")
            quiz.push_to_phone(path, args)
            self.assertEqual(run.call_args_list[0].args[0], ["adb-test", "-s", "test-device", "get-state"])
            command = run.call_args_list[-1].args[0]
            self.assertEqual(command[-2], str(path))
            self.assertRegex(command[-1], r"^/sdcard/Download/AFS-Quiz/bank-[a-f0-9]{12}\.json$")

    def test_output_accepted_by_actual_java_parser(self):
        jars = list((Path.home() / ".gradle/caches/modules-2/files-2.1/org.json/json").glob("*/*/json-*.jar"))
        if not (jars and shutil.which("javac") and shutil.which("java")):
            self.skipTest("Java or org.json unavailable; converter tests still run")
        source = Path(__file__).resolve().parents[1] / "dpc/java/com/afs/guard/QuizData.java"
        bridge = self.file("QuizImportBridge.java", '''package com.afs.guard;
import java.nio.file.*;import java.nio.charset.StandardCharsets;
public class QuizImportBridge { public static void main(String[] args)throws Exception {
 QuizData data=new QuizData();int total=0;
 for(String path:args){String json=new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8);
  QuizData.ImportResult r=data.prepareImport(json,"高数","test");total+=r.added;data=r.candidate;}
 System.out.println("Accepted "+total+" questions");}}
''')
        subprocess.run(["javac", "-encoding", "UTF-8", "--release", "8", "-cp", str(jars[0]), "-d", str(self.folder), str(source), str(bridge)], check=True, capture_output=True)
        paths = []
        for fmt in ("json", "csv", "txt"):
            _, encoded, _ = self.convert("source." + fmt, quiz.template(fmt))
            paths.append(str(self.file("out-" + fmt + ".json", encoded)))
        import os
        result = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", str(self.folder) + os.pathsep + str(jars[0]), "com.afs.guard.QuizImportBridge", *paths], capture_output=True, text=True, check=True)
        self.assertIn("Accepted 2 questions", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
