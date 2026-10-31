# DS 修正结果 · R01 / R02

日期：2026-10-26 20:49 +08:00
基准提交：`f5e277ed22a3a93e08aa703779df0835fadeb419`
工作区状态：**未提交、未推送、未重置**；保留了本轮开始前的全部既有改动。根 `afs-guard.apk` 已按构建脚本的等价步骤重新生成（原有改动文件之一）。

## 任务状态

- **R01：已完成源码修正与隔离回归测试代码。** 系统返回和页内返回都会在切换页面前先落盘；失败时保留当前 practice 页面、输入和 `draftDirty`，只提示“草稿尚未保存”，不把 `page` 提前改成 `home`；再次返回可重试。
- **R02：已完成源码修正与设备事件回归测试代码。** 首个超过阈值的 MOVE 先派发给子控件；子控件在该 MOVE 内调用 `requestDisallowInterceptTouchEvent(true)` 后，宿主不再取消/切页。未接管时才 CANCEL 子控件并消费后续事件；UP 不会触发按钮点击；取消、多指、纵向和普通点击回归保留。
- **本地验证：** Java 回归 17 组全部通过；APK 构建、签名验证通过。设备测试代码已完成编译/dex/签名，但**未安装、未在真机执行**。
- **设备验收：** 当前 `adb devices -l` 无设备在线，且本轮约定由 Astra 执行安装与真机验收；因此 R01/R02 的最终设备通过状态待 Astra。

## 文件及改动

### 源码

1. `dpc/java/com/afs/guard/QuizActivity.java`
   - 新增 `flushDraft()`：仅当 `data != null && draftDirty` 时调用 `save()`；异常只交给 `draftError()`，返回 `false`，不清脏标记。
   - 新增 `draftError()`：标题“草稿尚未保存”，提示当前页面和输入已保留，请检查存储空间后再次返回重试；不做递归自动重试。
   - `onBackPressed()`：非 home 页面先 `flushDraft()`，失败立即 return，不再调用 `home()`。
   - `home()`：在 `page="home"` **之前**先 `flushDraft()`，失败不改变页面状态。
   - `base()`：原直接 `save()` 改为 `if(!flushDraft())return;`；失败时不再销毁当前页面/输入，也不再重建下一页。
   - `onPause()`：复用 `flushDraft()`，保留离开页面时的兜底保存与错误提示。

2. `dpc/java/com/afs/guard/QuizStore.java`
   - 新增包内可见的设备回归隔离入口：`static synchronized void setTestFile(File)`；`file(Context)` 在测试文件非空时使用该文件，生产环境该字段恒为 `null`。设置/清除时同时重置 `cache`/`path`，保证真实题库不被测试写读。
   - 仅用于设备回归的隔离存储注入；未改变正常存储路径、格式或提交语义。

3. `dpc/java/com/afs/guard/SwipeNavHost.java`
   - `dispatchTouchEvent` 的 MOVE 分支改为：先 `super.dispatchTouchEvent(event)` 给子控件本次 MOVE；若子控件已 `claimed` 或跟踪已结束，直接返回子控件结果。
   - 只有子控件未接管时，才置 `swiping`、向子控件发送 CANCEL 并消费该 MOVE；UP 仍走原有“已提交滑动则 CANCEL 子控件、切页、不派发 UP”的逻辑。
   - 抽出 `cancelChild(MotionEvent)`，行为不变。多指、CANCEL、纵向和已提前 `requestDisallow` 的语义未改。

### 测试与脚本

4. `dpc/test/device/UsabilityDeviceTest.java`
   - 保留原有 7 项手势检查与 5 个主页面创建检查。
   - R02 新增：
     - 真实 `HorizontalScrollView` + 3000px 内容：DOWN 后首个超过阈值的 MOVE，第二次 MOVE 仍由子控件消费，`navigations == 0` 且 `scrollX > 0`。
     - 自定义子控件只在首个 MOVE 内请求接管：确认 `tookOver == true` 且 `navigations == 0`。
     - 真实 `Button` 横滑：`navigations == 1` 且点击次数为 0。
   - R01 新增隔离提交失败回归：
     - 用缓存目录中的“父路径是普通文件”的路径模拟提交失败，通过反射调用 `QuizStore.setTestFile()` 指向该隔离路径；真实题库只读。
     - 通过反射在内存中建立 practice 页并填入 notes 草稿，移除延迟自动保存，确保失败发生在返回动作。
     - 系统返回：页面仍为 `practice`、`draftDirty == true`、原 EditText 仍挂载且文本不变，出现“草稿尚未保存”，可关闭提示。
     - 页内返回：对页面上的 `‹  刷题` 控件 `performClick()`，同样保持页面/输入/脏标记并提示。
     - 可重试：把隔离路径换成可写文件后再次页内返回，成功进入 `home`、脏标记清除，且隔离文件中的 session input 等于草稿。
     - 清理：保留失败存储直到 Activity 结束，防止断言失败时 `onPause` 写入真实题库；清除测试路径后校验真实题库字符串与开始时完全一致。
   - 新增检查共 26 项；加上原 12 项，设备测试当前断言数为 **38**（未执行，待 Astra）。

5. `dpc/test/run-usability-device.ps1`
   - 仅修正失败提示文案：`Quiz import device checks failed` → `Usability device checks failed`。安装/卸载/WAKE 广播流程未改。

### 文档/产物

6. `docs/DSH-RESULT.md`（本文件）。
7. `afs-guard.apk`：本轮构建产物；SHA256 见下。`dpc/build/` 以及 `.codex-fix/dsh-r01-r02/` 为本机产物/被忽略目录，未提交。

## 验证命令与退出码

### 规范命令（受当前 DSH 沙箱限制，未能启动）

- `D:\learn\Git\bin\bash.exe -lc "cd /c/Users/nj/Desktop/空间/AwayFromShorts-Phone && bash dpc/test/run.sh"`
  - 退出码：`-1073741502`（十六进制 `0xC0000142`）
  - 原始错误：`bash: *** fatal error - couldn't create signal pipe, Win32 error 5`
  - 日志：`.codex-fix/dsh-r01-r02/canonical-java.log`
- `D:\learn\Git\bin\bash.exe -lc "cd /c/Users/nj/Desktop/空间/AwayFromShorts-Phone && AFS_PYTHON='C:/Users/nj/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' bash dpc/build.sh"`
  - 退出码：`-1073741502`（十六进制 `0xC0000142`）
  - 原始错误同上；未进入构建步骤。
  - 日志：`.codex-fix/dsh-r01-r02/canonical-bash.log`

原因：Git Bash 在当前 workspace-write 沙箱中无法创建 Cygwin signal pipe。未修改权限策略、未改用户账户、未换依赖来绕过；改用同一 JDK/SDK/aapt2/d8/apksigner 的 PowerShell 等价步骤。

### 等价 PowerShell 执行（实际结果）

- `C:\Users\nj\Desktop\空间\AwayFromShorts-Phone\.codex-fix\dsh-r01-r02\run-java.ps1`
  - 退出码：`0`
  - 结果：17 组全部通过：`GuardTest` 207 PASS / 0 FAIL，`FreezeRuleTest`、`LedgerMergeTest`、`MemoryTest` 171、`MemoryFlashcardsTest` 67、`MemorySwipeTest` 17、`MemoryAuthoringTest` 47、`TabOrderTest` 13、`FreezeSlotTest` 690、`QuizTest` 82、`QuizImportTest` 29、`DockSpringTest` 126、`AppearanceTest` 30、`MemoTest` 69、`MemoEditorTest` 14、`ToolboxTest` 52、`ToolMathTest` 80。
  - 日志：`.codex-fix/dsh-r01-r02/java-tests.log`
  - 说明：等价复刻 `dpc/test/run.sh` 的 BuildConfig 生成、`javac --release 8`、org.json 运行时和 `cd dpc` 工作目录；不是旧 17 组结果。

- `C:\Users\nj\Desktop\空间\AwayFromShorts-Phone\.codex-fix\dsh-r01-r02\build.ps1`
  - 退出码：`0`
  - 步骤：aapt2 compile/link、Python 打包 assets、javac、jar、d8、Python 追加 classes.dex、zipalign、apksigner sign、apksigner verify 全部 `EXIT=0`。
  - 日志：`.codex-fix/dsh-r01-r02/build.log`
  - 生成 APK：`dpc/build/afs-guard.apk`，已复制到根 `afs-guard.apk`。
  - APK SHA256：`1470A5407E556F2CC0BD5F7F8507BA99C9C5686926104AAAFB7CA95DE47CF884`
  - 签名验证：`apksigner verify --min-sdk-version 24` 退出码 `0`。
  - dex 内确认存在新字符串“草稿尚未保存”和 `setTestFile`；非旧包。

- 设备测试代码编译/dex/签名（不安装）
  - 命令：等价执行 `run-usability-device.ps1` 的 aapt2 link、javac、jar、d8、追加 dex、zipalign、apksigner 部分，未执行任何 `adb install`/`am instrument`。
  - 退出码：`0`
  - 产物：`dpc/build/device-usability/qa.apk`
  - 日志：`.codex-fix/dsh-r01-r02/device-compile.log`

- `adb devices -l`
  - 退出码：`0`
  - 输出：`List of devices attached`，无设备。

## 未执行的检查

- 未执行真机 instrumentation：设备列表为空；按交接约定，安装、设备回归、真机 UI/性能验收由 Astra 执行。
- 未安装、卸载、启动或操作手机；未修改真实学习记录、冻结规则或系统权限配置。
- 未运行旧的 12 项设备结果作为本轮证据；未执行 R01/R02 真机通过判定。
- 未做真实磁盘写满/超 16MB 的压力测试；R01 使用隔离的不可写路径模拟提交失败，覆盖的是同一 `QuizStore.commit` 异常路径。
- 未做性能/帧率测量；本轮不涉及性能结论。

## 遗留问题与风险

1. **R01/R02 的设备验收尚未完成。** 新设备测试断言数为 38，其中 R01 22 项、R02 4 项、原有 12 项；需 Astra 在设备 `3B66290094000000` 上安装后用 `dpc/test/run-usability-device.ps1 -Python <Python>` 执行。
2. `QuizStore.setTestFile(File)` 是包内可见的设备回归隔离入口，生产路径不会被调用（字段默认 `null`）。如果团队不希望生产类上保留测试入口，可在设备验收后移除，并改用其他隔离手段；但移除后当前 R01 设备回归无法注入提交失败。
3. `base()` 在落盘失败时提前返回，避免用下一页覆盖当前页；正常返回路径已由 `onBackPressed()`/`home()` 的前置 `flushDraft()` 保证 `page` 不会提前变为 `home`。非返回类的页面重建若在脏草稿状态下失败，会保留旧 UI，但调用方可能已先改内部 `page`；这不在本轮 R01 触发路径内。
4. Git Bash 无法在当前沙箱启动，因此规范命令的退出码不是测试/构建失败，而是 shell 启动失败；等价 PowerShell 步骤已记录。若 Astra 环境可正常启动 Git Bash，建议重跑规范命令复核。
5. 工作区仍有本轮之前的大量未提交改动；本轮没有重置、清理、提交或推送，也没有操作其他代理。

## 交接说明（给 Astra）

1. 审查 `QuizActivity.java` 的 `flushDraft`/`draftError`/`onBackPressed`/`home`/`base`，以及 `SwipeNavHost.dispatchTouchEvent` 的 MOVE 先派发顺序。
2. 审查 `UsabilityDeviceTest.java` 的 R01 隔离路径与 R02 事件时序；确认测试不会读写真实题库（测试中只读 `before`，失败期间保持不可写隔离路径直到 Activity 结束）。
3. 在设备在线后执行：
   - `dpc/test/run-usability-device.ps1 -Python C:/Users/nj/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`
   - 期望输出 `Usability device: 38 checks passed`（以实际断言计数为准）。
4. 若设备测试通过，再决定是否保留 `QuizStore.setTestFile` 测试 seam；本轮未提交，便于复审。
