# 可用性修复交接 · 2026-10-26

## 下一阶段：DS 接手修正
工作目录：`C:\Users\nj\Desktop\空间\AwayFromShorts-Phone`。
当前状态：审查未通过，接手后直接处理以下两项，不重复进行全项目架构探索。

1. R01（P1）：修复 QuizActivity 草稿提交失败时，系统返回及页内返回抛出未捕获异常的问题。失败必须保留当前页、输入和脏标记，并可重试。
2. R02（P2）：修复 SwipeNavHost 在首个大幅 MOVE 上提前取消横向子控件的问题。子控件应有机会接管；普通按钮上的横滑仍不触发点击。

先读 `USABILITY-REVIEW.md` 获取调用链、触发条件及验收标准，再按 `USABILITY-TASKS.md` 的架构约束实现。保留工作区原有未提交改动，不更换依赖、不顺手重构；冲突先报告。

补充两项缺失回归：隔离存储提交失败，以及真实横向控件在 MOVE 内请求接管。执行 Java 回归、构建、设备测试。测试不得修改用户学习记录或冻结规则。

完成后更新本文件，逐项写明 R01/R02 的修正、变更文件、实际验证命令与结果、未验证项、APK 哈希；附本轮增量差异交回 Astra 复审。当前 17 组 Java 和 12 项设备检查只是旧结果，不能作为新修正通过的证据。

DS 调用入口已接通：`scripts/invoke-dsh.ps1`，说明见 `DSH-CALL.md`。R01/R02 已派发至 DSH 会话 `session-7074ffa4-3864-4a54-b23a-28b74e4a4cca`；实际任务正文为 `DSH-IMPLEMENTATION-TASK.md`，完成状态以本轮运行结果及 `DSH-RESULT.md` 为准。

## 本轮完成
基准提交：`f5e277ed22a3a93e08aa703779df0835fadeb419`；交付为当前工作区，未创建提交。
APK SHA256：`677D12EFF13C9D8E3DEEDFC4313202A0EAF7737C1A03A82BE5EF2D6EFC93AF86`，签名验证通过。
- `SwipeNavHost.java`：达到横滑阈值后给子控件发送 CANCEL，消费后续事件，UP 再切页，避免滑动同时触发按钮。保留子控件接管、纵向操作、多指和取消语义。
- `QuizActivity.java`：页面重建前落盘脏草稿，提交成功才清除脏标记；系统重建时恢复题库分页。
- `test/device/UsabilityDeviceTest.java`、`UsabilityManifest.xml`、`test/run-usability-device.ps1`：可重复的 Android 事件派发和主页面创建检查，不改变学习记录或冻结规则。
- 架构及验收约定：`USABILITY-TASKS.md`。无新增依赖或存储接口变化。

## 验证
- `bash dpc/test/run.sh`：17 组全部通过，包括冻结、台账、记忆、刷题、主题、备忘录和工具箱。
- `AFS_PYTHON=<有效 Python 路径> bash dpc/build.sh`：构建成功；Windows 默认 python3 是不可用占位程序，显式指定运行时后通过。
- `dpc/test/run-usability-device.ps1 -Python <有效 Python 路径>`：12 项通过，覆盖点击、滑动取消按钮、取消、多指、子控件接管，以及五个主页面创建。
- APK 已覆盖安装到连接设备 3B66290094000000，保留应用数据；测试辅助包已卸载，WAKE 广播已发送。
- 连续 16 次底栏切页：347 帧，Janky 5（1.44%），p50/p90/p95/p99 为 5/6/8/24ms。首次打开记忆 50ms、刷题 64ms、冻结 155ms。单轮样本，没有本轮修改前同机对照，不据此宣称性能提升。
- 测量输出：本机 `.codex-fix/perf/measure-usability-20261026.txt`。
- 验证后 crash 日志为空。未通过 adb 直接启动非导出页面；这些页面通过目标进程内 instrumentation 成功验证。

## 审查与限制
- 后续当前聊天代码审查发现两项待修正问题，状态改为“需修正”：草稿写入失败时返回路径未捕获异常；首次大幅 MOVE 可能先于横向子控件接管而被取消。具体证据及验收见 `USABILITY-REVIEW.md`。原有测试通过不能覆盖这两种情况。
- 保留全部既有未提交改动，没有提交或推送。根 APK 包含当前工作区原有功能。
- 全仓 diff 检查发现既有 `docs/MEMORY.md`、`MemoryActivity.java` 末尾空行；本轮未修改这两处。
- 草稿保存失败仍保留脏标记，但返回路径缺少异常处理，见 R01；本轮没有模拟磁盘写满，也未覆盖全部输入法、旋转和大题库压力场景。
- 本轮主要完成手势可用性和刷题状态修复；大文件导入、长时间学习、不同系统省电行为仍需专项检查。
