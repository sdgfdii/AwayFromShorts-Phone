#!/usr/bin/env python3
"""Make AwayFromShorts importable memory cards. Python 3.9+, standard library only.

Sources contain the questions and answers; this tool does not invent answers.
Output TSV files exactly match MemoryStore.importText's headerless 2–4 columns.
"""
import argparse
import csv
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile

MAX_INPUT = 16 * 1024 * 1024
MAX_FILE = 8 * 1024 * 1024
MAX_CARDS = 30000
MAX_ROWS = 10000
FIELDS = {
    "subject": "subject", "deck": "subject", "学科": "subject", "科目": "subject", "卡组": "subject",
    "type": "type", "题型": "type", "类型": "type",
    "front": "front", "question": "front", "题目": "front", "正面": "front",
    "answer": "answer", "back": "answer", "答案": "answer", "背面": "answer",
    "source": "source", "来源": "source",
    "note": "note", "notes": "note", "explanation": "note", "备注": "note", "解释": "note", "解析": "note",
    "word": "word", "单词": "word", "meaning": "meaning", "释义": "meaning",
}
TYPES = {"qa": "qa", "问答": "qa", "问答卡": "qa", "input": "input", "输入": "input", "输入答案": "input",
         "cloze": "cloze", "填空": "cloze", "填空背诵": "cloze"}
TYPE_NAMES = {"qa": "问答", "input": "输入答案", "cloze": "填空背诵"}
SCHEMA = "afs-memory-cards/v1"


class CardError(ValueError):
    pass


def require(value, message):
    if not value:
        raise CardError(message)


def text(value, field):
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        require(not isinstance(value, float) or math.isfinite(value), f"{field} 不能是无穷大或 NaN")
        return str(value)
    require(isinstance(value, str), f"{field} 必须是文字或数字")
    value = value.strip()
    try:
        size = len(value.encode("utf-16-le")) // 2
    except UnicodeError as exc:
        raise CardError(f"{field} 包含无效 Unicode 字符") from exc
    require(size <= 20000, f"{field} 超过手机版 20000 字符限制")
    require(not any(ord(c) < 32 and c not in "\t\r\n" for c in value) and "\x7f" not in value,
            f"{field} 包含不可用的控制字符")
    return value


def mapped(row):
    require(isinstance(row, dict), "每张卡片必须是对象")
    result = {}
    for key, value in row.items():
        require(isinstance(key, str), "字段名称必须是文字")
        normalized = FIELDS.get(key.strip().lstrip("\ufeff").lower())
        require(normalized is not None, f"未知字段：{key}")
        require(normalized not in result, f"字段重复：{key}")
        result[normalized] = value
    return result


def normalize(row, defaults):
    values = dict(defaults)
    # Blank spreadsheet cells inherit defaults; empty answers still fail below when required.
    for key, value in mapped(row).items():
        if value not in (None, "") or key not in ("subject", "type", "source", "note"):
            values[key] = value
    subject = text(values.get("subject"), "学科")
    require(0 < len(subject.encode("utf-16-le")) // 2 <= 40, "请填写 1–40 字的学科，或使用 --subject")
    require(not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in subject), "学科名称不能包含控制字符或换行")
    word_style = "word" in values or "meaning" in values
    raw_type = text(values.get("type"), "题型").lower() or ("input" if word_style else "qa")
    require(raw_type in TYPES, "题型须为 qa/问答、input/输入答案、cloze/填空；图片遮挡请在手机中制作")
    kind = TYPES[raw_type]
    if word_style:
        require(not any(text(values.get(k), k) for k in ("front", "answer")), "词表字段与题目/答案字段不能混用")
        word, meaning = text(values.get("word"), "单词"), text(values.get("meaning"), "释义")
        require(word and meaning, "词表必须同时填写 word/单词 和 meaning/释义")
        require(kind in ("qa", "input"), "词表只能生成问答或输入卡")
        front, answer = (word, meaning) if kind == "qa" else ("根据释义拼写单词：" + meaning, word)
    else:
        front = text(values.get("front"), "题目")
        raw_answer = values.get("answer", "")
        if isinstance(raw_answer, list):
            require(raw_answer, "答案列表不能为空")
            alternatives = [text(v, "可接受答案") for v in raw_answer]
            require(all(alternatives), "可接受答案不能包含空项")
            answer = "|".join(alternatives)
        else:
            answer = text(raw_answer, "答案")
    front, answer = text(front, "题目"), text(answer, "答案")
    require(front, "题目不能为空")
    require(answer or kind == "cloze", "问答卡和输入卡必须填写答案")
    if kind == "cloze":
        require(re.search(r"\{\{[^{}]+\}\}", front), "填空题请在题目中使用 {{答案}}")
        require(not re.search(r"\{\{c\d+::", front), "当前手机版使用 {{答案}}，不支持 Anki 的 {{c1::答案}}")
    return {"subject": subject, "type": kind, "front": front, "answer": answer,
            "source": text(values.get("source"), "来源"), "note": text(values.get("note"), "备注")}


def read_text(path, encoding):
    require(path.is_file(), f"文件不存在：{path}")
    require(path.stat().st_size <= MAX_INPUT, f"{path.name} 超过 16MB 输入限制")
    raw = path.read_bytes()
    for codec in ([encoding] if encoding != "auto" else ["utf-8-sig", "gb18030"]):
        try:
            return raw.decode(codec).lstrip("\ufeff")
        except UnicodeDecodeError:
            continue
        except LookupError as exc:
            raise CardError(f"未知编码：{codec}") from exc
    raise CardError(f"{path.name} 解码失败，请用 --encoding 指定编码")


def json_load(value):
    def invalid(name):
        raise CardError(f"JSON 不能包含 {name}")
    def pairs(items):
        result = {}
        for key, item in items:
            require(key not in result, f"JSON 字段重复：{key}")
            result[key] = item
        return result
    return json.loads(value, parse_constant=invalid, object_pairs_hook=pairs)


def json_rows(content):
    data = json_load(content)
    defaults = {}
    if isinstance(data, dict):
        require("cards" in data, "JSON 对象必须包含 cards 数组")
        require(data.get("schema", SCHEMA) == SCHEMA, "不支持的制卡 JSON schema")
        base = data.get("defaults", {})
        require(isinstance(base, dict), "defaults 必须是对象")
        defaults = mapped(base)
        for key, value in data.items():
            if key in ("cards", "schema", "defaults"):
                continue
            extra = mapped({key: value})
            require(not (set(extra) & set(defaults)), f"默认字段重复：{key}")
            defaults.update(extra)
        require(set(defaults) <= {"subject", "type", "source", "note"}, "默认值只支持学科、题型、来源和备注")
        data = data["cards"]
    require(isinstance(data, list), "JSON 须为卡片数组或包含 cards 的对象")
    for index, row in enumerate(data, 1):
        yield row, defaults, f"卡片 {index}"


def table_rows(content, delimiter, no_header=False):
    reader = csv.reader(io.StringIO(content, newline=""), delimiter=delimiter, strict=True)
    headers = None
    for row in reader:
        if not row or not any(value.strip() for value in row):
            continue
        if no_header:
            require(2 <= len(row) <= 4, f"第 {reader.line_num} 行：无表头 TSV 须为 2–4 列")
            keys = ("front", "answer", "source", "note")
            yield dict(zip(keys, row)), {}, f"行 {reader.line_num}"
        elif headers is None:
            headers = []
            for raw in row:
                require(raw.strip(), "表头不能有空列")
                key = next(iter(mapped({raw: ""})))
                require(key not in headers, f"表头重复：{raw}")
                headers.append(key)
            require("front" in headers or {"word", "meaning"} <= set(headers), "表头需要题目/front，或单词/word 与释义/meaning")
        else:
            require(len(row) == len(headers), f"第 {reader.line_num} 行列数与表头不一致")
            yield dict(zip(headers, row)), {}, f"行 {reader.line_num}"


def markdown_rows(content):
    subject, card, last, at = "", None, "front", 0
    for index, line in enumerate(content.splitlines(), 1):
        if re.match(r"^#\s+", line):
            if card is not None:
                yield card, {}, f"行 {at}"
            subject = re.sub(r"^#\s+(?:学科\s*[:：]\s*)?", "", line).strip()
            card, last = None, "front"
        elif re.match(r"^##\s+", line):
            if card is not None:
                yield card, {}, f"行 {at}"
            card, at, last = {"front": re.sub(r"^##\s+", "", line).strip()}, index, "front"
            if subject:
                card["subject"] = subject
        elif line.strip() == "---":
            if card is not None:
                yield card, {}, f"行 {at}"
            card = None
        elif not line.strip():
            if card is not None:
                card[last] = str(card.get(last, "")) + "\n"
        else:
            require(card is not None, f"第 {index} 行：请先用 ## 题目 开始一张卡片")
            match = re.match(r"^([^\s:：]{1,20})\s*[:：]\s*(.*)$", line)
            if match:
                key = FIELDS.get(match.group(1).lower())
                require(key is not None, f"第 {index} 行未知字段：{match.group(1)}；正文续行请缩进")
                require(key not in card or (key == "subject" and card[key] == subject), f"第 {index} 行字段重复：{key}")
                card[key], last = match.group(2), key
            else:
                card[last] = str(card.get(last, "")) + "\n" + line.strip()
    if card is not None:
        yield card, {}, f"行 {at}"


def input_rows(path, args):
    content = read_text(path, args.encoding)
    fmt = args.format if args.format != "auto" else path.suffix.lower().lstrip(".")
    if fmt == "json":
        yield from json_rows(content)
    elif fmt == "jsonl":
        for index, line in enumerate(content.splitlines(), 1):
            if line.strip():
                try:
                    row = json_load(line)
                except (CardError, json.JSONDecodeError) as exc:
                    raise CardError(f"行 {index}：{exc}") from exc
                yield row, {}, f"行 {index}"
    elif fmt in ("csv", "tsv", "txt"):
        yield from table_rows(content, "," if fmt == "csv" else "\t", args.no_header or fmt == "txt")
    elif fmt in ("md", "markdown"):
        yield from markdown_rows(content)
    else:
        raise CardError("支持 JSON、JSONL、CSV、TSV、MD；TXT 按无表头 TSV 读取")


def phone_text(value, strict, label, warnings):
    if any(c in value for c in "\r\n\t"):
        require(not strict, f"{label} 含换行或 Tab；手机版 TSV 不能保存段落，请调整内容或取消 --strict-text")
        warnings.append(f"{label}：换行/Tab 已折成空格；完整原文保存在 cards.json")
        value = re.sub(r"[ \t]*[\r\n\t]+[ \t]*", " ", value)
    return value.strip()


def collect(paths, args):
    defaults = {key: value for key, value in {"subject": args.subject, "type": args.type, "source": args.source}.items() if value is not None}
    cards, rows, warnings, seen, duplicates = [], [], [], set(), 0
    for path in paths:
        try:
            for row, inherited, location in input_rows(path, args):
                try:
                    card = normalize(row, {**defaults, **inherited})
                    fields = [phone_text(card[k], args.strict_text, f"{path.name} {location} {k}", warnings)
                              for k in ("front", "answer", "source", "note")]
                    # Native import deduplicates on front/answer, ignoring source/note.
                    key = (card["subject"], card["type"], fields[0], fields[1])
                    if key in seen:
                        duplicates += 1
                        continue
                    seen.add(key); cards.append(card); rows.append(fields)
                    require(len(cards) <= MAX_CARDS, "最多生成 30000 张卡片")
                except CardError as exc:
                    raise CardError(f"{location}：{exc}") from exc
        except (CardError, csv.Error, json.JSONDecodeError) as exc:
            raise CardError(f"{path.name}：{exc}") from exc
    require(cards, "没有有效卡片")
    return cards, rows, warnings, duplicates


def filename_subject(subject):
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", subject).strip(" .") or "subject"
    if value.split(".")[0].upper() in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}:
        value = "_" + value
    return value


def package_files(cards, rows):
    groups = {}
    for card, row in zip(cards, rows):
        groups.setdefault((card["subject"], card["type"]), []).append("\t".join(row) + "\n")
    result = []
    for group_index, ((subject, kind), lines) in enumerate(groups.items(), 1):
        part, data, count = 1, bytearray(), 0
        def emit():
            name = f"{group_index:03d}-{filename_subject(subject)}-{kind}-{part:03d}.tsv"
            return name, bytes(data), {"file": name, "subject": subject, "type": kind, "typeLabel": TYPE_NAMES[kind], "cards": count,
                                       "sha256": hashlib.sha256(data).hexdigest()}
        for line in lines:
            encoded = line.encode("utf-8")
            require(len(encoded) <= MAX_FILE, "单张卡片超过手机版文件大小限制")
            if count and (count >= MAX_ROWS or len(data) + len(encoded) > MAX_FILE):
                result.append(emit()); part += 1; data, count = bytearray(), 0
            data.extend(encoded); count += 1
        if count:
            result.append(emit())
    return result


def publish(output, cards, files, warnings, duplicates, paths):
    output = output.expanduser().absolute()
    require(not os.path.lexists(output), f"输出目录已存在：{output}；请换一个新目录，脚本不覆盖已有文件")
    output.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".afs-memory-", dir=str(output.parent))).resolve()
    try:
        manifest = {"schema": SCHEMA, "total": len(cards), "duplicatesSkipped": duplicates, "warningCount": len(warnings),
                    "warnings": warnings[:50], "inputs": [p.name for p in paths], "files": [entry for _, _, entry in files]}
        (stage / "cards.json").write_text(json.dumps({"schema": SCHEMA, "cards": cards}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (stage / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        instructions = ["AwayFromShorts 记忆卡片导入说明", "", "手机：底栏“记忆” → “记忆工具” → 添加/选择对应学科 → “卡片” → “批量导入”。",
                        "先选清单中的题型，再选择对应 TSV 文件。每个文件只含一个学科、一个题型，无表头。", "",
                        "cards.json 是完整制卡源文件，manifest.json 是导入清单；手机当前应选择 TSV 文件。",
                        "无需恢复备份，不会替换整个卡库，也不会改变复习进度、打卡或冻结设置。", "",
                        f"共 {len(cards)} 张卡片，跳过 {duplicates} 张重复卡片。", ""]
        for name, data, entry in files:
            (stage / name).write_bytes(data)
            instructions.append(f"{name} → 学科：{entry['subject']}；题型：{entry['typeLabel']}；{entry['cards']} 张")
        if warnings:
            instructions += ["", f"提示：有 {len(warnings)} 个字段折叠了换行或 Tab，原文在 cards.json；详见 manifest.json。"]
        (stage / "导入说明.txt").write_text("\n".join(instructions) + "\n", encoding="utf-8")
        require(not os.path.lexists(output), f"输出目录已出现：{output}；未覆盖")
        os.rename(str(stage), str(output))
    finally:
        # This is only the fresh staging directory created by this invocation.
        if stage.exists() and stage.parent == output.parent.resolve() and stage.name.startswith(".afs-memory-"):
            shutil.rmtree(stage)


def template(kind):
    cards = [
        {"subject": "英语单词", "type": "input", "word": "memory", "meaning": "记忆；回忆", "source": "自编示例", "note": "根据中文释义拼写英语单词。"},
        {"subject": "医学", "type": "qa", "front": "术语 cell 的中文名称是什么？", "answer": "细胞", "source": "自编术语示例", "note": "请用自己的教材核对并扩充。"},
        {"subject": "工科概念", "type": "cloze", "front": "国际单位制中，力的单位是 {{牛顿（N）}}。", "answer": "", "source": "自编示例", "note": "每张卡聚焦一个知识点。"},
    ]
    if kind == "json":
        return json.dumps({"schema": SCHEMA, "cards": cards}, ensure_ascii=False, indent=2) + "\n"
    if kind == "md":
        return "# 英语单词\n\n## 根据释义拼写单词：记忆；回忆\n题型：input\n答案：memory\n来源：自编示例\n备注：根据中文释义回忆拼写。\n\n# 医学\n\n## 术语 cell 的中文名称是什么？\n题型：qa\n答案：细胞\n来源：自编术语示例\n\n# 工科概念\n\n## 国际单位制中，力的单位是 {{牛顿（N）}}。\n题型：cloze\n来源：自编示例\n"
    out = io.StringIO(newline=""); writer = csv.writer(out, delimiter="," if kind == "csv" else "\t", lineterminator="\n")
    writer.writerow(["学科", "题型", "题目", "答案", "来源", "备注"])
    for raw in cards:
        c = normalize(raw, {})
        writer.writerow([c[k] for k in ("subject", "type", "front", "answer", "source", "note")])
    return out.getvalue()


def main(argv=None):
    parser = argparse.ArgumentParser(description="批量制作手机版记忆卡片：JSON/CSV/TSV/Markdown → 按学科和题型拆分的导入文件")
    parser.add_argument("inputs", nargs="*", type=Path, help="一个或多个源文件")
    parser.add_argument("-o", "--output", type=Path, help="输出新目录；--template 时为新的模板文件")
    parser.add_argument("--subject", help="未填写学科时的默认学科")
    parser.add_argument("--type", help="未填写题型时的默认题型：qa/input/cloze 或中文名")
    parser.add_argument("--source", help="未填写来源时的默认来源")
    parser.add_argument("--format", choices=("auto", "json", "jsonl", "csv", "tsv", "md", "markdown", "txt"), default="auto")
    parser.add_argument("--encoding", default="auto", help="输入编码；默认自动尝试 UTF-8、GB18030")
    parser.add_argument("--no-header", action="store_true", help="CSV/TSV 按题目、答案、来源、备注四列读取，不读取表头")
    parser.add_argument("--strict-text", action="store_true", help="字段有换行或 Tab 时拒绝转换，不折叠为空格")
    parser.add_argument("--check", action="store_true", help="仅校验，不产生文件")
    parser.add_argument("--template", choices=("json", "csv", "tsv", "md"), help="生成可填写的模板")
    args = parser.parse_args(argv)
    try:
        if args.template:
            require(not args.inputs and not args.check, "--template 不能与输入文件或 --check 混用")
            require(args.output is not None, "生成模板需要 -o 文件名")
            payload = template(args.template)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8-sig" if args.template in ("csv", "tsv") else "utf-8", newline="") as out:
                out.write(payload)
            print(f"模板已生成：{args.output}")
            return 0
        require(args.inputs, "请提供源文件，或使用 --template 生成模板")
        cards, rows, warnings, duplicates = collect(args.inputs, args)
        files = package_files(cards, rows)
        if not args.check:
            publish(args.output or Path("memory-cards-output"), cards, files, warnings, duplicates, args.inputs)
        print(f"{'校验通过' if args.check else '制卡完成'}：{len(cards)} 张，{len(files)} 个导入文件，去重 {duplicates} 张。")
        for warning in warnings[:8]:
            print("提示：" + warning)
        if len(warnings) > 8:
            print(f"另有 {len(warnings) - 8} 条提示。" + ("生成文件时会写入清单。" if args.check else "详见输出清单。"))
        return 0
    except (CardError, OSError, UnicodeError, csv.Error, json.JSONDecodeError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    raise SystemExit(main())
