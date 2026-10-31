# DS 实现任务：完整本地待办流程

## 目标

用户说“继续，需要全部功能”。在现有备忘录上补齐待办管理、缩进、查找、分享和单次日期提醒，保留上一轮圆形勾选、回车和撤销。不要仅插入无操作的 Markdown 字符或仅画 UI。

## 工作边界

工作目录 `C:\Users\nj\Desktop\空间\AwayFromShorts-Phone`。先读本目录 ARCHITECTURE.md，及 docs/MEMO.md 和现有 Memo 系列代码与测试。仓库有大量其他任务的未提交改动；禁止 reset、清理、提交、推送或操作任何设备。不要触碰同步配置、冻结、学习、底栏、其他工具箱功能。不要更改版本号、构建脚本或外部服务配置。不要启动其他代理。

允许改动：MemoActivity、MemoChecklist、MemoChecklistInput、MemoEditor、MemoStore 及相应测试；新增 MemoReminder、MemoReminderReceiver；AndroidManifest.xml 只加自身 receiver/确实缺少的 POST_NOTIFICATIONS。共享 Manifest 修改只保留本功能最小段。文档仅本目录 HANDOFF.md，不覆盖其他任务交接。

## 接口与验收

1. “待办管理”面板：当前行／多行选区的完成、恢复、移除勾选框、增加缩进、减少缩进、上移、下移、删除（确认）；还可明确选择全部待办完成／恢复。没有待办时禁用不适用操作。删除和批量变更能撤销，普通文本和其他待办不丢失。
2. 缩进不仅改字符串：圆圈和正文一起偏移，折行对齐正文；已有导入的缩进也正确。保留横竖滑动、触摸勾选、文本选择及 TalkBack 当前待办动作。
3. 查找当前标题和正文：匹配计数、前后匹配、循环跳转。退出查找保持正文，空词和未找到状态清晰。
4. 分享：用户点后打开系统文本分享 chooser，内容是当前草稿的标题与正文；DS 不发送，不操作分享目标。
5. 新建／已有笔记均能设置、修改、取消未来某天某时的提醒，并与正文一起保存。取消日期选择不改任何数据；权限拒绝有明确状态；过去时间拒绝；不要为了打开提醒设置先覆盖用户草稿。
6. 提醒调度、重复触发、删除取消、未来提醒重启恢复、已过期恢复不立即骚扰、通知点击正确条目，都有可测试的模型边界。副作用由独立模块负责；无 exact-alarm 权限要求。
7. 从旧 JSON 读取缺失字段保持 0；JSON 往返、编辑不丢提醒字段、删除／恢复／copy 不丢字段；已有 69 项 Memo 和 37 项清单测试不能退化。
8. 新增有意义的纯 Java 回归；设备测试可扩充现有 `MemoChecklistDeviceTest`（仅模拟器）。不要运行设备测试；由主代理执行。

## 验证、交接

- 主代理处理最终完整构建、模拟器和手机验收。DS 可用现有 Android SDK 编译新增代码并运行 Java 回归；不要和其他任务争用 dpc/build，可用 `.codex-fix/note-rea/full-checklist/ds-build` 的隔离输出。Git Bash 在 `D:\learn\Git\bin\bash.exe`，SDK 在 `C:\Users\nj\.afs-android\sdk`，Python 在 `C:\Users\nj\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`。
- 发现接口矛盾先在 HANDOFF.md 写明具体位置、影响和选项，再继续不受影响部分；不自行换依赖或扩大成整个笔记软件重写。
- 完成写本目录 HANDOFF.md：基准提交、改动文件、实现功能、接口变化、实际执行命令及退出码、日志路径、未执行检查、遗留问题及审查重点。不能用旧测试结果冒充本轮。
- 状态：DSH 路径和交接命令不可用；主代理按同一约定实现，完整 Java 回归及模拟器 92 项验收通过。实际结果与限制见 HANDOFF.md。
