# Android 工具箱

1.49.0（2135），2026-10-25。入口：首页「功能」四格里的「工具箱」，与目标管理、打卡历史、备忘录并列（四格排成 2×2）。

三个工具全部在本机运行，不联网、不加通知/闹钟/前台服务，不新增权限。

| 工具 | 用法 |
| --- | --- |
| 计时器 | 倒计时（1/5/10/25/45/60 分钟预设）或正计时；开始/暂停、重置；倒计时到 0 只在页面上显示「时间到」，不响铃不通知。离开页面即停表 |
| 随机抽签 | 一行一个选项，点「抽一个」；选项会去重、去空行，最多 200 个、单个最多 60 字；抽签过程有短暂闪烁动画，结果下显示共几个选项；选项会保存在本机，下次打开还在 |
| 文本工具 | 粘贴文本后实时显示字数（不含空白）和非空行数；可一键转大写、转小写、按行去重、按行排序。上限 2 万字符 |

## 实现

| 文件 | 说明 |
| --- | --- |
| `dpc/java/com/afs/guard/Toolbox.java` | 纯逻辑：`clock`/`remain` 计时格式、`splitItems`/`pickIndex` 抽签、`charCount`/`lineCount`/`upper`/`lower`/`dedupeLines`/`sortLines` 文本整理；抽签选项存 `afs_guard` / `toolbox_items` |
| `dpc/java/com/afs/guard/ToolboxActivity.java` | 工具箱首页 + 三个工具页（同一个 Activity，Intent extra `tool` 区分），全部走 `Ui` 主题色，深浅色 / 纯黑 / 高德风格自动跟随 |
| `dpc/test/java/com/afs/guard/ToolboxTest.java` | 52 项离线断言：时钟边界、剩余时间夹取、选项拆分/去重/截断/上限、抽签下标范围、字数与行数、大小写、按行去重与排序、`null` 输入 |

计时器用页面内的 `Handler`，`onPause`/`onDestroy` 会移除回调，不常驻后台；抽签和文本工具不需要任何权限。

## 验证

- 离线：`bash dpc/test/run.sh`（11 组）；本机没有 Git Bash 时用
  `powershell -NoProfile -ExecutionPolicy Bypass -File .codex-fix/memo/check.ps1`，等价于同一套 javac + 纯函数测试。
  `ToolboxTest` 52 项通过，其余 10 组（Guard 207 / FreezeRule / LedgerMerge 9 / Memory 91 / Memory flashcards 50 /
  Freeze slots 18 / Quiz 82 / Dock springs 126 / Appearance 30 / Memo 63）无回归。
- APK：与 `dpc/build.sh` 同一套手工步骤（aapt2 → javac → d8 → zipalign → apksigner）构建，版本 1.49.0 / 2135，签名校验通过。
- 设备：本功能开发时手机曾连接做整体验收，但工具箱本身是在设备断开后完成的，**未做真机验证**；
  装到手机上后建议先试：计时器开始/暂停/到点、抽签闪动与结果、文本四个按钮的即时改写。
