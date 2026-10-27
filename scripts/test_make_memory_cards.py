import contextlib
import csv
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import make_memory_cards as maker


class CardMakerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="afs-card-tests-")
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, content, encoding="utf-8"):
        p = self.root / name
        p.write_text(content, encoding=encoding)
        return p

    def source(self, cards):
        return self.write("source.json", json.dumps(cards, ensure_ascii=False))

    def card(self, **changes):
        return {"subject": "工科概念", "type": "qa", "front": "力的单位？", "answer": "N", **changes}

    def run_cli(self, *args):
        capture = io.StringIO()
        with contextlib.redirect_stdout(capture), contextlib.redirect_stderr(capture):
            code = maker.main([str(a) for a in args])
        return code, capture.getvalue()

    def generate(self, source, *extra):
        out = self.root / "output"
        code, report = self.run_cli(source, "-o", out, *extra)
        self.assertEqual(code, 0, report)
        return out, json.loads((out / "manifest.json").read_text(encoding="utf-8"))

    def test_all_templates_can_be_converted(self):
        for kind in ("json", "csv", "tsv", "md"):
            with self.subTest(kind=kind):
                p = self.write("template." + kind, maker.template(kind))
                code, report = self.run_cli(p, "--check")
                self.assertEqual(code, 0, report)

    def test_mixed_subjects_and_types_are_split_for_phone(self):
        p = self.write("cards.json", maker.template("json"))
        out, manifest = self.generate(p)
        self.assertEqual(manifest["total"], 3)
        self.assertEqual({f["type"] for f in manifest["files"]}, {"qa", "input", "cloze"})
        for f in manifest["files"]:
            raw = (out / f["file"]).read_bytes()
            self.assertFalse(raw.startswith(b"\xef\xbb\xbf"))
            self.assertEqual(len(raw.decode("utf-8").splitlines()), f["cards"])
            self.assertEqual(len(raw.decode("utf-8").splitlines()[0].split("\t")), 4)
            self.assertEqual(maker.hashlib.sha256(raw).hexdigest(), f["sha256"])

    def test_vocabulary_generates_spelling_or_recognition(self):
        c = maker.normalize({"subject": "英语", "word": "memory", "meaning": "记忆"}, {})
        self.assertEqual((c["type"], c["answer"]), ("input", "memory"))
        q = maker.normalize({"subject": "英语", "word": "memory", "meaning": "记忆", "type": "qa"}, {})
        self.assertEqual((q["front"], q["answer"]), ("memory", "记忆"))

    def test_json_defaults_and_row_override(self):
        p = self.write("cards.json", json.dumps({"defaults": {"subject": "医学", "source": "教材"},
            "cards": [{"front": "术语？", "answer": "a"}, {"subject": "电路", "front": "符号？", "answer": 0}]}))
        out, manifest = self.generate(p)
        cards = json.loads((out / "cards.json").read_text(encoding="utf-8"))["cards"]
        self.assertEqual([c["subject"] for c in cards], ["医学", "电路"])
        self.assertEqual(cards[1]["answer"], "0")
        self.assertEqual(cards[0]["source"], "教材")

    def test_accepted_answers(self):
        c = maker.normalize(self.card(type="input", answer=["N", "牛顿"]), {})
        self.assertEqual(c["answer"], "N|牛顿")
        with self.assertRaises(maker.CardError):
            maker.normalize(self.card(answer=[""]), {})

    def test_deduplicate_using_phone_fields_preserving_first_note(self):
        p = self.source([self.card(note="第一条"), self.card(note="第二条"), self.card(type="input")])
        out, manifest = self.generate(p)
        self.assertEqual((manifest["total"], manifest["duplicatesSkipped"]), (2, 1))
        self.assertEqual(json.loads((out / "cards.json").read_text(encoding="utf-8"))["cards"][0]["note"], "第一条")

    def test_csv_quotes_multiline_and_original_preserved(self):
        stream = io.StringIO(newline="")
        writer = csv.writer(stream)
        writer.writerow(["学科", "题目", "答案", "备注"])
        writer.writerow(["电路", '什么是 "R"？', "电阻", "第一行\n第二行\t说明"])
        p = self.write("cards.csv", stream.getvalue(), "utf-8-sig")
        out, manifest = self.generate(p)
        full = json.loads((out / "cards.json").read_text(encoding="utf-8"))["cards"][0]
        self.assertIn("\n", full["note"])
        self.assertEqual(manifest["warningCount"], 1)
        self.assertEqual(len((out / manifest["files"][0]["file"]).read_text(encoding="utf-8").splitlines()), 1)

    def test_strict_text_leaves_no_output(self):
        p = self.source([self.card(note="a\nb")]); out = self.root / "output"
        code, _ = self.run_cli(p, "-o", out, "--strict-text")
        self.assertEqual(code, 2); self.assertFalse(out.exists())

    def test_headerless_phone_tsv(self):
        p = self.write("cards.tsv", "题目\t答案\t来源\t备注\n")
        out, manifest = self.generate(p, "--no-header", "--subject", "医学")
        self.assertEqual(manifest["total"], 1)

    def test_markdown_multiline_and_custom_subject(self):
        p = self.write("cards.md", "# 学科：自定义学科\n## 问题？\n类型：问答\n答案：一\n  二\n备注：解释\n")
        out, manifest = self.generate(p)
        c = json.loads((out / "cards.json").read_text(encoding="utf-8"))["cards"][0]
        self.assertEqual(c["subject"], "自定义学科")
        self.assertEqual(c["answer"], "一\n二")

    def test_invalid_last_row_does_not_partially_publish(self):
        p = self.source([self.card(), self.card(front="没有空的填空", type="cloze", answer="")])
        original = p.read_bytes(); out = self.root / "output"
        code, report = self.run_cli(p, "-o", out)
        self.assertEqual(code, 2); self.assertIn("卡片 2", report)
        self.assertFalse(out.exists()); self.assertEqual(p.read_bytes(), original)

    def test_anki_cloze_and_image_are_rejected(self):
        for c in (self.card(front="{{c1::答案}}", type="cloze"), self.card(type="image")):
            with self.assertRaises(maker.CardError):
                maker.normalize(c, {})

    def test_unknown_or_duplicate_fields(self):
        for payload in ([self.card(typo="x")], {"cards": [], "defaults": {"front": "bad"}}):
            p = self.source(payload); code, _ = self.run_cli(p, "--check"); self.assertEqual(code, 2)
        p = self.write("duplicate.csv", "subject,front,question,answer\nx,q,q,a\n")
        self.assertEqual(self.run_cli(p, "--check")[0], 2)

    def test_json_duplicate_and_nonfinite_numbers(self):
        for payload in ('[{"front":"a","front":"b"}]', '[{"answer": NaN}]'):
            p = self.write("bad.json", payload); self.assertEqual(self.run_cli(p, "--check")[0], 2)

    def test_jsonl_reports_actual_line(self):
        p = self.write("cards.jsonl", json.dumps(self.card()) + "\n{bad\n")
        code, report = self.run_cli(p, "--check")
        self.assertEqual(code, 2); self.assertIn("行 2", report)

    def test_gb18030_and_encoding_override(self):
        p = self.write("cards.csv", "学科,题目,答案\n工科,单位？,牛顿\n", "gb18030")
        self.assertEqual(self.run_cli(p, "--check")[0], 0)
        self.assertEqual(self.run_cli(p, "--check", "--encoding", "utf-8")[0], 2)

    def test_utf16_limits_and_controls(self):
        for c in (self.card(front="😀" * 10001), self.card(subject="x\ny"), self.card(note="\x00")):
            with self.assertRaises(maker.CardError):
                maker.normalize(c, {})

    def test_existing_output_and_template_never_overwritten(self):
        p = self.source([self.card()]); out = self.root / "output"; out.mkdir(); keep = out / "keep.txt"; keep.write_text("keep")
        self.assertEqual(self.run_cli(p, "-o", out)[0], 2); self.assertEqual(keep.read_text(), "keep")
        self.assertEqual(self.run_cli("--template", "json", "-o", p)[0], 2)
        self.assertEqual(json.loads(p.read_text(encoding="utf-8"))[0]["front"], "力的单位？")

    def test_windows_filenames_do_not_escape_output(self):
        p = self.source([self.card(subject="../../医学"), self.card(subject="CON"), self.card(subject="电路:入门")])
        out, manifest = self.generate(p)
        for f in manifest["files"]:
            self.assertEqual(Path(f["file"]).name, f["file"])
            self.assertNotIn("\\", f["file"])
            self.assertEqual((out / f["file"]).resolve().parent, out.resolve())

    def test_row_count_and_utf8_byte_splitting(self):
        c = maker.normalize(self.card(), {})
        with patch.object(maker, "MAX_ROWS", 2):
            files = maker.package_files([c] * 5, [["q", "a", "", ""]] * 5)
            self.assertEqual([f[2]["cards"] for f in files], [2, 2, 1])
        with patch.object(maker, "MAX_FILE", 24):
            files = maker.package_files([c] * 3, [["汉字", "答案", "", ""]] * 3)
            self.assertEqual(len(files), 3)
            self.assertTrue(all(len(data) <= 24 for _, data, _ in files))

    def test_check_only_and_master_json_roundtrip(self):
        p = self.write("cards.json", maker.template("json")); out, manifest = self.generate(p)
        missing = self.root / "not-created"
        self.assertEqual(self.run_cli(out / "cards.json", "--check", "-o", missing)[0], 0)
        self.assertFalse(missing.exists())

    def test_real_android_card_validator(self):
        java, javac = shutil.which("java"), shutil.which("javac")
        jars = list((Path.home() / ".gradle/caches/modules-2/files-2.1/org.json/json").glob("*/*/json-*.jar"))
        if not java or not javac or not jars:
            self.skipTest("JDK and local org.json jar needed for Java validation")
        repo = Path(__file__).resolve().parents[1]
        p = self.write("cards.json", maker.template("json")); out, _ = self.generate(p)
        harness = self.write("ValidateMemoryCards.java", '''
import com.afs.guard.MemoryData;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public class ValidateMemoryCards {
 public static void main(String[] args) throws Exception {
  Path root=Paths.get(args[0]); JSONObject manifest=new JSONObject(new String(Files.readAllBytes(root.resolve("manifest.json")),StandardCharsets.UTF_8));
  MemoryData m=new MemoryData(); int n=0;
  JSONArray files=manifest.getJSONArray("files");
  for(int i=0;i<files.length();i++){
   JSONObject f=files.getJSONObject(i); String subject=f.getString("subject"), deck="";
   for(int d=0;d<m.decks().length();d++)if(subject.equals(m.decks().getJSONObject(d).getString("name")))deck=m.decks().getJSONObject(d).getString("id");
   if(deck.isEmpty())deck=m.addDeck(subject,subject).getString("id");
   byte[] bytes=Files.readAllBytes(root.resolve(f.getString("file"))); if(bytes.length>8388608)throw new AssertionError("file too big");
   String text=new String(bytes,StandardCharsets.UTF_8).replace("\\r",""); int rows=0;
   for(String row:text.split("\\n")){if(row.trim().isEmpty())continue;String[] c=row.split("\\t",-1);if(c.length!=4)throw new AssertionError("columns");
    m.saveCard(MemoryData.newCard(deck,f.getString("type"),c[0],c[1],c[2],c[3]));n++;rows++;
   }
   if(rows!=f.getInt("cards"))throw new AssertionError("count");
  }
  new MemoryData(m.db.toString()); if(n!=manifest.getInt("total"))throw new AssertionError("total");System.out.println("VALIDATED "+n);
 }
}
''')
        sources = [repo / "dpc/java/com/afs/guard" / (name + ".java") for name in ("MemoryData", "MemoryScheduler", "MemoryFlashcards")]
        classes = self.root / "classes"; classes.mkdir(); jar = str(jars[0])
        result = subprocess.run([javac, "--release", "8", "-encoding", "UTF-8", "-cp", jar, "-d", str(classes), str(harness), *map(str, sources)], capture_output=True, text=True, errors="replace")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        run = subprocess.run([java, "-cp", str(classes) + os.pathsep + jar, "ValidateMemoryCards", str(out)], capture_output=True, text=True, errors="replace")
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr); self.assertIn("VALIDATED 3", run.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
