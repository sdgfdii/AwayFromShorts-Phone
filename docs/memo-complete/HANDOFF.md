# 备忘录待办交接与审查

2026-10-28，基准提交 `f5e277e`，工作区包含大量此前及其他任务的未提交改动。本轮不提交／推送，不重置它们。

## 实际执行角色

架构、实现、审查、验证均由主代理完成。尝试 `mcp__dsh.dsh_start` 返回 “DSH was not found”，配置指向不存在的 `D:\learn\DSH Desktop`；现有 DSH NEXT / Harness CLI 不提供兼容 codex-handoff。没有安装软件或改变全局配置，也没有虚报 DS 成功。

## 文件和接口

- `MemoChecklist`：选区、批量完成／恢复、缩进、去标记、移动、删除的纯函数；保留 Markdown 单一来源。
- `MemoChecklistInput`：统一操作历史入口、折行缩进、批量编辑勾选时保留选择区，防止输入法看到中途光标位置。
- `MemoActivity`：管理面板、查找、文本 chooser 分享、提醒日期时间及通知设置、草稿提醒字段恢复；保留键盘避让。
- `MemoEditor`：查找匹配和删除线预览；旧 Markor 解析器不替换。
- `MemoStore`：Note 可选提醒字段、向后兼容序列化、正文与提醒同步 commit 后再调度；提醒 claim 持久化防重复；删除／恢复同步调度。
- 新增 `MemoReminder`、`MemoReminderReceiver`，Manifest 只添加独立 receiver；沿用已有通知、开机权限，没有精确闹钟权限。
- 新增／扩充 `MemoChecklistTest` 和 `MemoChecklistDeviceTest`。版本 1.54.1 / 2171。

## 验证证据

构建源快照 `.codex-fix/note-rea/full-checklist/verify/`，主代理构建命令为 Git Bash `dpc/build.sh`，Python 显式指定 bundled runtime。构建退出 0，日志 `full-checklist/build.log`。

同一快照运行 `dpc/test/run.sh`，退出 0，完整回归通过，日志 `.codex-fix/note-rea/full-checklist/final-tests.log`。Memo checklist 69、Memo 69、Memo editor 14。

模拟器 `emulator-5580` Android 14 运行 `run-memo-checklist-device.ps1`，退出 0，最终 **92 checks passed**，日志 `full-checklist/device.log`。覆盖：原交互、管理菜单、缩进及撤销、批量完成、删除取消及撤销、查找前后导航、提醒日期取消、分享 chooser 的 ACTION_SEND 内容检查、深浅色草稿恢复、实际 AlarmManager 广播及通知、通知点击准确条目、保留其他编辑页、重复触发只发一次、删除取消、恢复不重发、时间变化不提前触发。分享通过阻断式 ActivityMonitor 拦截，没有向外发送。

仪器测试恢复备忘录两个原始偏好键及主题，取消自己的提醒、卸载 QA 包；未清除应用数据。单个模拟器键盘断言曾因提醒取消后键盘关闭而失败，测试改为明确请求 IME 后验证，产品逻辑未因此伪改。

手机 `3B66290094000000` 安装成功，`dumpsys package` 确认 1.54.1 / 2171；实机观察管理菜单、缩进、提醒入口和键盘工具栏。独立 APK `afs-guard-memo-complete.apk` SHA256 `E178E4ADC287D417091F924D6FF19F17B21D6AE794A70F9B6BD963DCABAF1291`。没有替换其他任务的通用 afs-guard.apk。

## REA 和边界

源应用新增缩进运行证据 `ev_8bd4688c2de5d8ddd159366b083b9644fd8d52f4b8856e6e5645f23530ca7382`，字体／格式弹层证据 `ev_fbb6f86749f928fefc17d7ea61c72581d3bb8633ff6d45873864be6aaae82245`。原始产物仍在忽略目录，不复制第三方脚本或素材。

ColorOS 临时笔记“111”已从活跃笔记和最近删除清理，活跃数仍 1；未设置参考应用的实际提醒或开启云服务。功能范围是待办完整本地流程，不把云、AI、私密空间、附件或完整富文本称为已完成。提醒为系统非精确调度；长期节电、真机重启与各厂商后台限制仍需后续实测。

手机 AFS 的临时草稿验证后清空再退出，活跃备忘录仍是原来的 2 条；没有为真机创建实际提醒，`dumpsys alarm` 没有本轮残留的 MEMO_REMINDER。证据 `full-checklist/afs-cleaned.xml`。
