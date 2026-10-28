# 记忆卡片制作脚本

把已经整理好的知识点制成 AwayFromShorts 手机版可导入的卡片。支持问答、输入答案、填空；可合并多个文件，自动校验、去重，按学科与题型拆分输出。

只需要 Python 3.9+，不安装第三方库。脚本不生成知识点或答案，不调用网络/AI，也不改手机数据库、学习记录、冻结规则或打卡。

## 在手机版直接制作

入口：**记忆 → 记忆工具 → 制作 / 导入卡片**。无需在手机安装 Python。

1. 选择内容格式，粘贴内容或选择文件。支持 JSON、JSONL、带表头 CSV/TSV、结构化 Markdown，以及无表头 Tab 分列文本。
2. 内容中没有学科时填写默认学科；默认题型可选自动、问答、输入或填空，卡片内的指定值优先。
3. 点击「校验并预览」，查看各学科与题型的新增数量、重复数量和前 3 张样例。
4. 点击「确认入库」。自动归入同名在用学科，缺少的学科会创建；回收站中的同名学科需先恢复。

可直接选择桌面脚本输出的 **cards.json**（选择 JSON 格式），一次导入全部学科；不再需要逐个 TSV 导入。也可点「填入三学科示例」了解格式。导入脚本生成的无表头 TSV 时，请选择「TXT / TSV（无表头，Tab 分列）」并填写对应学科、题型。

手机文件上限 16MB，单次最多处理 30000 张，卡库总量最多 30000 张。自动先尝试 UTF-8，再尝试 GB18030；若预览乱码，请将原文件另存为 UTF-8。原生导入保留段落，去重依据为学科、题型、题目与答案，来源和备注不同仍视为重复。已有卡片与复习记录保留，无效内容整批拒绝，取消预览不会写入卡库。记忆导入不写普通打卡。

## 快速开始

在 `AwayFromShorts-Phone` 项目目录打开 PowerShell：

```powershell
# 复制一个模板，填写自己的题目和答案。
Copy-Item docs/examples/memory-source.md 我的卡片.md

# 制作导入文件。输出目录必须是新的，不覆盖已有目录。
.\scripts\make-memory-cards.ps1 我的卡片.md -o 我的记忆卡片

# 或直接试用三个学科的示例。
.\scripts\make-memory-cards.ps1 docs/examples/memory-source.json -o 示例卡片输出
```

也可直接用 Python：

```powershell
python scripts/make_memory_cards.py 我的卡片.md -o 我的记忆卡片
```

PowerShell 启动器优先使用 `AFS_PYTHON` 指定的解释器，其次查找系统 Python、Codex 本地 Python、`py -3`。

## 手机如何导入

将输出目录中的 TSV 文件传到手机，再打开：

**底栏“记忆” → “记忆工具” → 选择或添加对应学科 → “卡片” → “批量导入” → 先选题型 → 选择 TSV 文件。**

每个 TSV 文件只包含一个学科、一种题型，没有表头，四列依次是：题目、答案、来源、备注。不要把带表头的源 CSV 直接选进手机。

输出内容：

- `导入说明.txt`：逐个列出文件对应的学科、题型和张数。
- `001-英语单词-input-001.tsv` 等：手机应选择的导入文件。
- `cards.json`：保留全部字段原文的制卡源文件，可再次交给脚本转换；它不是手机备份或直接导入格式。
- `manifest.json`：卡片总量、去重数、转换提示、文件清单及 SHA-256 校验值。

已有完整示例输出位于 `docs/examples/memory-import-ready/`。这套导入走普通“批量导入”，无需“恢复备份”，不替换整个卡库。

## Markdown 写法

```markdown
# 英语单词

## 根据释义拼写单词：记忆；回忆
题型：input
答案：memory
来源：自己的词表
备注：根据中文释义回忆拼写。

# 医学

## 术语 cell 的中文名称是什么？
题型：qa
答案：细胞
来源：自己的笔记

# 工科概念

## 国际单位制中，力的单位是 {{牛顿（N）}}。
题型：cloze
来源：自己的笔记
```

一级标题 `# 学科名` 切换学科，二级标题 `## 题目` 开始一张卡。也支持 `# 学科：学科名`。字段名称用中文或英文，冒号可中可英。正文续行请缩进；题间可用单独一行 `---`。这是带标记的制卡格式，不是任意笔记的自动拆卡器。

## JSON、词表与表格源文件

JSON 可使用卡片数组，或包含 `cards` 数组的对象：

```json
{
  "defaults": {"subject": "英语单词", "source": "自己的词表"},
  "cards": [
    {"word": "memory", "meaning": "记忆；回忆"},
    {"type": "qa", "front": "review 的中文意思？", "answer": "复习；回顾"},
    {"type": "input", "front": "根据释义拼写：复习；回顾", "answer": ["review"]}
  ]
}
```

- `word`＋`meaning` 词表默认生成输入拼写卡：正面是中文释义，答案是英语单词。指定 `type: qa` 则生成“看单词回忆释义”的问答卡。词表字段不能与 `front`/`answer` 混用。
- 标准字段：`subject`、`type`、`front`、`answer`、`source`、`note`；也支持“学科、题型、题目、答案、来源、备注”等中文表头。
- `defaults` 支持学科、题型、来源、备注，单张卡可覆盖默认值。
- 输入题的多个可接受答案可写 JSON 数组，或以 `|` 分隔。
- 填空题必须在题目中使用 `{{答案}}`；答案栏可留空或放补充解释，不使用 Anki 的 `{{c1::答案}}` 写法。
- CSV / TSV 首行为表头。JSONL 每行一个卡片对象。`.txt` 按无表头 TSV 读取。
- UTF-8（含 BOM）和 GB18030 自动识别，也可以显式指定 `--encoding`。Excel/WPS 资料可另存为 UTF-8 CSV 或 TSV 后使用；脚本不直接读取 XLSX。

## 常用命令

```powershell
# 多文件合并，按内容去重。
.\scripts\make-memory-cards.ps1 英语.csv 医学.md 工科.json -o 合并卡片

# 缺少学科或题型时提供默认值。
.\scripts\make-memory-cards.ps1 词表.csv --subject 英语单词 --type input -o 英语卡片

# 读取现有无表头 TSV：题目、答案、可选来源、可选备注。
.\scripts\make-memory-cards.ps1 问答.tsv --no-header --subject 医学 -o 医学卡片

# 只检查，不写文件。
.\scripts\make-memory-cards.ps1 我的卡片.md --check

# 有换行或 Tab 时拒绝转换，不做折叠。
.\scripts\make-memory-cards.ps1 我的卡片.json --strict-text -o 严格模式输出

# 生成新的模板，目标文件不能已存在。
.\scripts\make-memory-cards.ps1 --template md -o 新卡片模板.md
.\scripts\make-memory-cards.ps1 --template csv -o 新卡片模板.csv
.\scripts\make-memory-cards.ps1 --template json -o 新卡片模板.json
```

CSV、TSV、JSONL 都按文件原顺序输出，多个文件按命令中的顺序合并。同学科同题型的相同题目与答案只保留第一张，来源或备注不同不会重复制卡；与手机版去重规则一致。

## 格式限制与校验

手机的文本导入按行分卡，无法在一个字段中直接保留换行/Tab。默认将其折成空格，同时在清单中提示，完整原文保留在 `cards.json`；需要保留排版时使用 `--strict-text` 检查，并在手机编辑器中逐卡处理。

脚本按手机版限制验证学科名称、必填答案、填空格式和 Java UTF-16 字符长度。未知字段、重复表头、损坏 JSON、错列 TSV 等会指出文件及位置；整批校验成功后才发布输出。默认不覆盖源文件、模板或已有输出目录。

最多生成 30000 张，每个导入 TSV 不超过 10000 张和 8MB，超过会自动拆成多个文件；每个源文件最多 16MB。手机已有卡片仍占用卡库上限，脚本无法获知手机当前余量。

图片遮挡卡请在手机中选图、框选区域；本脚本不处理图片、PDF/Word、扫描内容或 Anki APKG，也不会推送文件到手机或自动确认导入。

成功退出码为 `0`，校验或写入失败为 `2`。

## 验证脚本

```powershell
python scripts/test_make_memory_cards.py
```

测试覆盖模板、词表、中文字段、默认值、CSV 引号与多行字段、去重、UTF-16 限制、错误定位、已有文件保护、编码、无表头 TSV、文件拆分和原文保留。找到本机 JDK 与 `org.json` jar 时，还会编译仓库当前的 `MemoryData` 等纯 Java 类，用手机版实际卡片校验器验证生成的 TSV；缺少这些工具时该项会显示跳过。
