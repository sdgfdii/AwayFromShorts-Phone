# 题库导入脚本

脚本将电脑上的题目整理成手机版可导入的 UTF-8 JSON；支持自定义科目。不会改动手机私有数据库、冻结规则或打卡记录。

## 快速使用（Windows PowerShell）

在 `AwayFromShorts-Phone` 项目目录运行：

```powershell
# 1. 从模板开始：复制后用 Excel、WPS 或文本编辑器填写。
Copy-Item docs/examples/quiz-source.csv 我的题目.csv

# 2. 校验并转换成手机版题库。
.\scripts\import-quiz.ps1 我的题目.csv -o 我的题库.json --subject 高数 --name 高数练习

# 3. 可选：连接并授权 USB 调试后，将生成文件传到手机。
.\scripts\import-quiz.ps1 我的题目.csv -o 我的题库.json --force --push
```

传输后，在手机底栏“刷题” → “导入题库” → 选择文件 → 下载 / AFS-Quiz，选取 `bank-*.json` 并确认导入预览。脚本不会替你确认导入。

也可以通过微信、文件传输等方式把生成的 JSON 发到手机，再在刷题页导入。

## 其他用法

```powershell
# 合并多个源文件，按内容去重（保留第一次出现的题目与解析）。
.\scripts\import-quiz.ps1 高数.csv 线代.json -o 合并题库.json --name 数学题库

# 只检查，不产生输出文件。
.\scripts\import-quiz.ps1 我的题目.csv --check

# 生成新的 CSV、JSON 或带标记的文本模板。
.\scripts\import-quiz.ps1 --template csv -o 新模板.csv
.\scripts\import-quiz.ps1 --template txt -o 新模板.txt
.\scripts\import-quiz.ps1 --template json -o 新模板.json

# Excel .xlsx：先为启动脚本使用的同一 Python 环境安装读取依赖。
python -m pip install openpyxl
python scripts/import_quiz.py 题库.xlsx --sheet 高数 -o 高数题库.json

# 自定义科目；题目自身的科目优先，未填时才采用此默认值。
.\scripts\import-quiz.ps1 电路.tsv --subject 电路 -o 电路题库.json

# 有多台设备时指定设备，也可用 --adb 指定 adb.exe。
.\scripts\import-quiz.ps1 我的题目.csv -o 我的题库.json --force --push --serial 设备序列号
```

跨平台直接使用 Python 3.9+：`python scripts/import_quiz.py 源文件.csv -o 题库.json`。
Windows 启动脚本会查找 `AFS_PYTHON`、系统 Python、Codex 内置 Python 和 `py -3`。读取 XLSX 的依赖必须装在实际使用的同一个 Python 环境中；也可以在 Excel/WPS 中另存为 CSV，无需额外依赖。旧式 `.xls` 不直接支持。

## 输入约定

- JSON：标准题目数组，或 `{ "name": "题库名", "subject": "高数", "questions": [...] }`。
- CSV / TSV / XLSX：首行为表头；至少有“题干 / stem”和“答案 / answer”。可选“科目、章节、题型、A、B、C、D、E、F、G、H、解析、来源、id”。选项不能跳号，末尾未用选项可留空。
- 可用 `options` 列放 JSON 数组，如 `["$0$","$1$"]`，或直接使用 A–H 选项列。二者择一。
- 类型支持 `single` / `multiple` / `judge` / `short` / `solution` 及对应中文题型名。未填写题型时：有选项的题按答案字母数判断单选或多选；无选项的题按解答题处理。
- 选择题答案填 `B`、`AC` 或 JSON 数组 `["A","C"]`；数字不会被猜成选项字母。判断题接受 A/B、正确/错误、true/false、√/×。填空与解答题答案保留原文字。
- 带标记 TXT / MD 的写法见 `docs/examples/quiz-source.txt`。题间必须用单独一行 `---` 分隔，使用 `[题目]`、`[答案]`、`[解析]` 等标记；解析可多行。不是任意排版的试卷解析器。
- CSV / TSV / TXT 默认尝试 UTF-8 和 GB18030（中文 Windows 常见编码）；可用 `--encoding utf-8` 等显式指定。
- LaTeX 公式用 `$…$`、`$$…$$`、`\(…\)` 或 `\[…\]`。CSV / TXT 写原始反斜杠，JSON 中写双反斜杠；脚本自动正确转义输出 JSON。
- 不生成答案、不识别扫描图片、不直接解析 PDF/Word。Excel 公式单元格需要先复制并粘贴为值，脚本不计算 Excel 公式。

## 校验与文件保护

全部源文件校验成功后才写输出；遇到错误会指出文件名、题号/行号，退出码为 2。成功退出码为 0。

按科目、题型、题干、选项和答案去重，符合手机版的去重依据。相同 id 对应不同题目时拒绝导入。未提供 id 时保持省略，由手机版生成稳定 id。章节、来源或解析不同的同题仍会去重，保留第一条。

最多 5000 题、输出 JSON 最多 4MB，匹配手机版单次导入限制。手机已有题目仍计入手机总量上限，此脚本无法读取手机现有题数。每个源文本/Excel 文件最多 16MB。

默认拒绝覆盖现有输出；加 `--force` 才允许覆盖，始终禁止覆盖输入源文件。写入采用临时文件后发布；`--check` 只校验。生成的文件只含题目，不包含答题进度、收藏或历史记录。

## 检查

```powershell
python scripts/test_import_quiz.py
```

21 项回归覆盖编码、CSV 换行引号、文本标记、去重、id 冲突、选择判分格式、无效文件保护、传输参数及 XLSX 读取适配。可用 Java 和 org.json 时，还会编译当前手机版 `QuizData.java`，用真实导入器检查脚本生成的文件。
