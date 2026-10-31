# DS 修正任务：R01、R02

你现在是实现方 DS。工作目录必须是 C:\Users\nj\Desktop\空间\AwayFromShorts-Phone。
用户已经要求接通 DSH 并交接此任务。直接完成修正和必要验证。

先读取 docs/USABILITY-HANDOFF.md、docs/USABILITY-REVIEW.md、docs/USABILITY-TASKS.md。
只处理 R01、R02，不重做架构探索，不更换依赖或顺手重构。保留当前工作区所有既有改动，不重置、清理、提交或推送。不要启动其他代理。

R01：QuizActivity 保存脏草稿失败时，系统返回和页内返回不能崩溃；必须保持原页面、原输入和脏标记并允许重试。页面状态不能提前变为 home。补充隔离的提交失败回归。
R02：SwipeNavHost 首次大幅 MOVE 必须给横向子控件接管机会；不能把快速横滑选卡条变成切页，同时普通按钮横滑仍不能点击。补真实 HorizontalScrollView 或 MOVE 内接管子控件的事件测试，保留多指、取消和纵向行为。

优先检查：dpc/java/com/afs/guard/QuizActivity.java、QuizStore.java、SwipeNavHost.java、dpc/test/device/UsabilityDeviceTest.java，以及 dpc/test/run-usability-device.ps1。必须先验证审查指出的路径，再做最小修正；如发现约定冲突，报告具体证据。

可用环境：
- Git Bash：D:\learn\Git\bin\bash.exe
- Python：C:\Users\nj\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe
- Android SDK：C:\Users\nj\.afs-android\sdk
- 设备序列号：3B66290094000000（先确认在线）
- 构建前设置 AFS_PYTHON 为上述 Python 的正斜杠路径，避免 Windows python3 占位程序。
- Java 回归：bash dpc/test/run.sh
- 构建：bash dpc/build.sh
- 设备回归：dpc/test/run-usability-device.ps1 -Python <上述路径>

本轮你负责源码、测试和本地构建；不要安装/卸载、操作手机、覆盖用户数据或修改冻结规则。设备验证由 Astra 在你交回后执行。不要通过禁用权限策略或修改用户账户配置绕过工具失败；无法运行时记录原始错误并交回。

完成后写 docs/DSH-RESULT.md：任务状态、文件及改动、验证命令和退出码、日志路径、未执行的检查、遗留问题、基准提交及当前是否未提交。不要把旧的 17 组/12 项结果当作本轮验证结果。最后给出简短交接说明，由 Astra 审查并执行设备验收。
