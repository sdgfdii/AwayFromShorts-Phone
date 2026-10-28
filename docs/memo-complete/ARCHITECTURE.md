# 备忘录待办补全架构

当前范围已由用户明确扩大为整个备忘录，最新约定与未完成项以 WHOLE-APP.md 为准。下面保留待办阶段的实现背景，不再作为排除附件、AI等功能的依据。

日期：2026-10-28。范围暂按用户本轮“全部功能”指待办清单。范围问题已提出；先补齐与之独立相关的本地交互，不把厂商账号、AI 服务当作已复刻。用户在原对话授权架构方自行审查交接后交给 DS，本文件由架构方审查后交付。

## 既有结构和约束

- Java 原生 Android，minSdk 24，现有 Ui、ThemeStore、ThemedActivity。不要换依赖、不要引入 WebView 编辑器或数据库。
- `MemoStore.Note` + `afs_guard/memos_v1`；回收站 `memos_trash_v1`。存量标题、正文、id、置顶、时间全部兼容。Markdown 是正文唯一来源；禁止另存一份待办列表。
- `MemoChecklist` 是纯函数模型，`MemoChecklistInput` 是原生输入框及圆形勾选框，`MemoEditor.History` 是有界撤销重做。新操作统一经过输入框的 before/after action，不能绕过历史。
- `MemoActivity` 负责界面、查找和分享。新增工具采用现有主题的底部面板或对话框；顶部与底部不塞满长工具条。键盘打开时工具必须可操作。
- 新增提醒是笔记级单次本地提醒。只给 Note 增加向后兼容的可选整数 `remindAt`、`remindedAt`（默认 0）；原构造函数保持可调用，copy、shown、JSON、编辑、删除恢复必须保留字段。仅修改提醒不允许把正文或其他笔记覆盖。
- 提醒通过新的独立 `MemoReminder` / `MemoReminderReceiver`，不修改 Guard、学习闸门、冻结规则。使用系统 AlarmManager 的允许空闲单次非精确调度，不申请精确闹钟权限；文案诚实说明系统省电可能延迟。Android 13 通知权限被拒绝时必须显示状态，不宣称可提醒。
- 每个提醒使用绑定 note id 的不可变 PendingIntent。通知点击打开该条 MemoActivity。删除条目取消提醒；恢复仅重排未来提醒；重复广播不重复通知；重启、时间变化、应用更新后恢复未来提醒。通知正文只含通用提示，不在锁屏泄露完整笔记。

## 模型接口

保留 `MemoChecklist.Change(body,cursor)`。增加 selection 为 anchor/focus 的纯函数：setChecked、indent、move、deleteItems（可采用更清晰的同等接口，但先在交接说明映射）。处理整段，不改变没有选中的普通正文；选区末尾恰好位于下一行开头时不误选下一段。代码围栏不是待办。缩进用每级两个空格，上限 6 级；换行后续行对齐正文。移动边界无动作，不能吞换行、改变 CRLF 或截断内容。

查找：当前笔记标题和正文可查找，支持前一个／后一个、匹配计数、循环定位，查找本身不改正文。分享：只构建 ACTION_SEND chooser，实际选收件应用由用户执行，不自动发送。

## 运行证据

ColorOS 便签 16.7.2 / 160070002，APK SHA256 与 `docs/MEMO-REFERENCE.md` 同一包。2026-10-28 新增运行观察保存在 `.codex-fix/note-rea/full-checklist/`。

- 圆形待办、完成变灰、续写和空行退出：前轮已验证。
- `indent-rea/screen.png`，REA Evidence `ev_8bd4688c2de5d8ddd159366b083b9644fd8d52f4b8856e6e5645f23530ca7382`：第一行缩进，第二行保持原位。
- `reminder.xml`：笔记菜单“设置提醒”，日期与时间选择、取消／保存；此次没有保存闹钟。
- `selection2.png`：选择文本后出现全选、复制、剪切等；`more.xml` 有查找、分享入口。
- 批量完成、删除、上下移作为本项目待办管理补齐功能，不声称逐项验证过 ColorOS 的同名入口。云、AI、私密空间、图片扫描并非待办模型的已验证能力，不能伪造为成功完成。
