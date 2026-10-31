# -*- coding: utf-8 -*-
"""界面文案的抽取与翻译工具。

方案：中文原文就是 msgid（gettext 那套）。代码里只把字面量包成 T.s("中文")，
拼接语义一个字都不改；译文放 assets/i18n_en.txt，运行时 T 类读它替换。

    python tools/i18n.py scan            还剩多少未包裹的中文
    python tools/i18n.py wrap            dry-run，看要改哪些
    python tools/i18n.py wrap --write    真正落盘
    python tools/i18n.py export          把未译的导到 build/todo.txt
    python tools/i18n.py merge tools/translations/batchN.py
    python tools/i18n.py check           覆盖率 + 占位符一致性

翻译表格式：每行 `转义后的中文<TAB>转义后的英文`。`\\n` 是换行，`\\e` 是
「译成空串」（中文虚词在英文里该整个消失时用，不能写空值——空值等于没翻译）。
"""

import io
import os
import re
import sys

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.join(PROJECT, "dpc")
SRC = os.path.join(ROOT, "java", "com", "afs", "guard")
RAW = os.path.join(ROOT, "assets", "i18n_en.txt")

CJK = re.compile(r"[\u4e00-\u9fff]")
STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')

HEADER = [
    "# 英文文案表：`中文原文<TAB>英文`，中文原文就是代码里的 msgid。",
    "# 没列出的句子在英文模式下原样显示中文（原文兜底），不会留空。",
    "# 带占位符的句子（%s %d）必须保留同样的占位符，check 会校验。",
    "# 译成空串写 \\e（中文虚词在英文里该整个消失时用）。",
]

# 出现这些片段就不包裹：语义是「标识」或「逻辑」，不是给人读的文案。
SKIP_BEFORE = [
    r"\.equals\(", r"\.equalsIgnoreCase\(", r"\.contains\(", r"\.startsWith\(",
    r"\.endsWith\(", r"\.indexOf\(", r"\.lastIndexOf\(", r"\.matches\(",
    r"\.split\(", r"\.replace\(", r"\.replaceAll\(", r"\.compareTo\(",
    # Arrays.asList("正确","对",...) 这类是解析输入用的匹配词，翻成英文会让
    # 中文题库导入直接失败。这是真实事故点，不是理论风险。
    r"Arrays\.asList\(",
    r"\bcase\s", r"\bswitch\s*\(", r"Pattern\.compile", r"Log\.",
    r"\.put\(", r"\.putString\(", r"\.putInt\(", r"\.putLong\(", r"\.putBoolean\(",
    r"\.optString\(", r"\.optJSONObject\(", r"\.optJSONArray\(", r"\.opt\(",
    r"\.getString\(", r"\.has\(", r"\.remove\(", r"\.getBoolean\(", r"\.getInt\(",
    r"getSharedPreferences", r"getStringExtra", r"putExtra", r"setTag",
    r"findViewById", r"static\s+final\s+String", r"T\.s\(", r"T\.f\(", r"Lang\.t\(",
    r"@", r"import\s", r"package\s",
]


def strip_code(path):
    """把注释换成空格（保持偏移不变），这样正则不会撞到注释里的中文。"""
    text = io.open(path, encoding="utf-8").read()
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            out.append("  ")
            i += 2
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            out.append("  ")
            i += 2
            continue
        if ch == '"':
            out.append(ch)
            i += 1
            while i < n:
                if text[i] == "\\":
                    out.append(text[i:i + 2])
                    i += 2
                    continue
                if text[i] == '"':
                    out.append('"')
                    i += 1
                    break
                out.append(text[i])
                i += 1
            continue
        if ch == "'":
            out.append(ch)
            i += 1
            while i < n:
                if text[i] == "\\":
                    out.append(text[i:i + 2])
                    i += 2
                    continue
                if text[i] == "'":
                    out.append("'")
                    i += 1
                    break
                out.append(text[i])
                i += 1
            continue
        out.append(ch)
        i += 1
    return text, "".join(out)


def literals(code):
    """返回 (start, end, inner) —— 只含代码里的字符串字面量（注释已被抹掉）。"""
    return [(m.start(), m.end(), m.group(1)) for m in STRING.finditer(code)]


def should_wrap(code, start, end, inner):
    if not CJK.search(inner):
        return False
    before = code[max(0, start - 120):start]
    for pattern in SKIP_BEFORE:
        hits = list(re.finditer(pattern, before))
        if not hits:
            continue
        # 取最近一次命中，看它到字面量之间有没有括号。
        # 有括号 = 那个调用早已闭合，这个字面量不属于它：
        #   if ("auto".equals(mode)) return "跟随系统";   ← 这是真文案，要包
        # 没有括号 = 本字面量就是它的参数：
        #   map.put(KEY, "中文")  /  mode.equals("auto")  ← 这是标识，不包
        tail = before[hits[-1].end():]
        if "(" in tail or ")" in tail:
            continue
        return False
    # 赋值给常量名（UPPER_CASE = "中文"）多半是默认值/键，跳过
    if re.search(r"\b[A-Z][A-Z0-9_]{2,}\s*=\s*$", before):
        return False
    return True


def files():
    for name in sorted(os.listdir(SRC)):
        if name.endswith(".java"):
            yield os.path.join(SRC, name)


# ---------------------------------------------------------------- 转义

def escape(value):
    return (value.replace("\\", "\\\\")
                 .replace("\t", "\\t")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r"))


def unescape(value):
    out = []
    i = 0
    n = len(value)
    while i < n:
        ch = value[i]
        if ch == "\\" and i + 1 < n:
            nxt = value[i + 1]
            if nxt == "n":
                out.append("\n")
            elif nxt == "t":
                out.append("\t")
            elif nxt == "r":
                out.append("\r")
            elif nxt == "\\":
                out.append("\\")
            else:
                out.append("\\")
                out.append(nxt)
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def format_value(value):
    """写盘时值也要转义：译文里带换行会把一条记录劈成两行，键值全错位。

    空串必须写成 \\e：内存里的 "" 意思是「译成空串」，而文件里的空值
    意思是「这条还没翻译」。两者混了就会读回来变回中文。
    """
    if value == "" or value == "\\e":
        return "\\e"
    return escape(value)


def java_unescape(raw):
    """源码里的 \\n 在运行时是换行；msgid 必须是运行时的样子，否则永远匹配不上。"""
    return unescape(raw)


# ---------------------------------------------------------------- 翻译表

def load_catalog():
    known = {}
    if not os.path.exists(RAW):
        return known
    for line in io.open(RAW, encoding="utf-8").read().split("\n"):
        # 只把「没有制表符」的 # 行当注释：像 "#RRGGBB，如 FFD700" 这种
        # 以 # 开头的真实文案，会被当成注释整条丢掉。
        if not line or (line.startswith("#") and "\t" not in line):
            continue
        parts = line.split("\t", 1)
        if len(parts) < 2:
            continue
        key = unescape(parts[0])
        if not key:
            continue
        if parts[1].strip() == "\\e":
            known[key] = ""
            continue
        value = unescape(parts[1])
        if value.strip():
            known[key] = value
    return known


def save_catalog(known):
    lines = list(HEADER)
    for key in sorted(known):
        value = known[key]
        lines.append("%s\t%s" % (escape(key), format_value(value)))
    io.open(RAW, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")


def collect_msgids():
    ids = {}
    for path in files():
        text, code = strip_code(path)
        for m in re.finditer(r'T\.(?:s|f)\("((?:[^"\\]|\\.)*)"', code):
            key = java_unescape(m.group(1))
            ids[key] = ids.get(key, 0) + 1
    return ids


def specs(value):
    # %% 是字面百分号，不是占位符；先剥掉，否则 "%% used" 会被当成 "% u"。
    # 不把空格当 printf 标志：否则 "90% and" 会被误读成 %a（十六进制浮点）。
    cleaned = value.replace("%%", "")
    return sorted(re.findall(r"%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[a-zA-Z]", cleaned))


# ---------------------------------------------------------------- 子命令

def cmd_scan():
    total = 0
    rows = []
    for path in files():
        text, code = strip_code(path)
        pending = 0
        for start, end, inner in literals(code):
            if should_wrap(code, start, end, inner):
                pending += 1
        wrapped = code.count("T.s(") + code.count("T.f(")
        if pending or wrapped:
            rows.append((pending, wrapped, os.path.basename(path)))
        total += pending
    rows.sort(reverse=True)
    for pending, wrapped, name in rows[:25]:
        print("%5d 待包  %4d 已包  %s" % (pending, wrapped, name))
    print("---- 合计待包裹 %d 条" % total)
    return total


def cmd_wrap(write=False):
    changed = 0
    for path in files():
        text, code = strip_code(path)
        spans = [(s, e) for s, e, inner in literals(code) if should_wrap(code, s, e, inner)]
        if not spans:
            continue
        out = []
        cur = 0
        for s, e in spans:
            out.append(text[cur:s])
            out.append("T.s(" + text[s:e] + ")")
            cur = e
        out.append(text[cur:])
        changed += len(spans)
        print("%4d 处  %s" % (len(spans), os.path.basename(path)))
        if write:
            io.open(path, "w", encoding="utf-8", newline="\n").write("".join(out))
    print("---- 共 %d 处%s" % (changed, "" if write else "（dry-run，加 --write 才落盘）"))


def cmd_export():
    known = load_catalog()
    total = 0
    out_path = os.path.join(ROOT, "build", "todo.txt")
    if not os.path.isdir(os.path.dirname(out_path)):
        os.makedirs(os.path.dirname(out_path))
    with io.open(out_path, "w", encoding="utf-8") as fh:
        for path in files():
            text, code = strip_code(path)
            seen = []
            for m in re.finditer(r'T\.(?:s|f)\("((?:[^"\\]|\\.)*)"', code):
                key = java_unescape(m.group(1))
                if key in seen:
                    continue
                seen.append(key)
            todo = [k for k in seen if k not in known]
            if not todo:
                continue
            total += len(todo)
            fh.write("## %s (%d)\n" % (os.path.basename(path), len(todo)))
            for key in todo:
                fh.write(escape(key) + "\n")
    print("未译 %d 条 → build/todo.txt" % total)
    return total


def cmd_merge(path):
    ns = {}
    code = io.open(path, encoding="utf-8").read()
    exec(compile(code, path, "exec"), ns)
    text = ns.get("TEXT") or {}
    known = load_catalog()
    added = 0
    for key, value in text.items():
        # 空串等于「没翻译」，会退回中文；想让它整个消失必须写 \e。
        value = value if value.strip() else "\\e"
        if known.get(key) != value:
            added += 1
        known[key] = value
    save_catalog(known)
    print("并入 %d 条（其中变化 %d），表内共 %d 条" % (len(text), added, len(known)))


def cmd_check():
    known = load_catalog()
    ids = collect_msgids()
    missing = [k for k in ids if k not in known]
    bad = []
    for key, value in known.items():
        if not value:
            continue
        if specs(key) != specs(value):
            bad.append((key, value, specs(key), specs(value)))
    total = len(ids)
    covered = total - len(missing)
    pct = 100.0 * covered / total if total else 100.0
    print("msgid %d 条，已译 %d 条，覆盖率 %.1f%%" % (total, covered, pct))
    if missing:
        print("未译 %d 条：" % len(missing))
        for key in missing[:20]:
            print("   ", repr(key))
    if bad:
        print("占位符不一致 %d 条：" % len(bad))
        for key, value, a, b in bad[:20]:
            print("   ", repr(key), a, "->", b)
    else:
        print("占位符：全部一致")
    return len(missing) + len(bad)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    command = argv[1]
    if command == "scan":
        cmd_scan()
    elif command == "wrap":
        cmd_wrap("--write" in argv)
    elif command == "export":
        cmd_export()
    elif command == "merge":
        if len(argv) < 3:
            print("用法：merge <翻译批次 .py>")
            return 2
        cmd_merge(argv[2])
    elif command == "check":
        return 1 if cmd_check() else 0
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
