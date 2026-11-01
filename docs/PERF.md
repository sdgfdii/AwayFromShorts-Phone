# 切页卡顿优化

1.49.1（2136），2026-10-25。用户反馈「页面之间切换太卡顿」，这一版只做性能，不改交互语义。

## 之前卡在哪

1. **跨 Activity 双页对滑**：`FloatingNav` 切页时，把上一个页面的整棵 View 树从旧窗口摘下、塞进新窗口，再让两整页一起滑动 240ms。每帧要画两整页，还要做跨窗口挂载/归还，是切页最大的浪费。
2. **每页首帧重编玻璃着色器**：每个 Activity 的底栏第一次绘制都会重新读取两份 AGSL 并编译 `RuntimeShader`（`NavGlassRenderer`），切一页就付一次几十毫秒的编译成本。
3. **主题配置反复解析**：`ThemeStore.load` 在每次布局的排版遍历里都会把 `appearance_v1` JSON 重新解析一遍；`AppearanceConfig.copy()` 也是 JSON 往返。
4. **每次布局全树排版两遍**：`ThemedActivity` 的 `OnGlobalLayoutListener` 每次回调都按字体 + 瑞士网格遍历整棵 View 树；一次布局里常有多次回调。

## 现在怎么做

| 改动 | 文件 |
| --- | --- |
| 切页只动新页面：新页短距离滑入（190ms translation）/ 淡入（170ms alpha），不再碰旧窗口内容；底栏选中态直接落到目标位置，不再逐帧改玻璃 RenderEffect | `FloatingNav.java` |
| 玻璃着色器按「密度/深色/色调/档位」缓存，进程内所有页面复用同一份编译结果 | `NavGlassRenderer.java` |
| `ThemeStore.load` 按 `appearance_v1` 原始串缓存解析结果；`AppearanceConfig.copy()` 改成字段级复制 | `ThemeStore.java`、`AppearanceConfig.java` |
| 布局回调合并：同一轮布局只挂一次排版遍历，销毁时取消 pending 回调 | `ThemedActivity.java` |

## 怎么验证

设备连上后：

```bash
adb shell dumpsys gfxinfo com.afs.guard reset
# 来回切换底栏 5 个页面各 3 次
adb shell dumpsys gfxinfo com.afs.guard
```

看 `Janky frames` 百分比与 90th/95th/99th percentile；对比旧包（同设备、同一组操作）。也可以在开发者选项里打开「GPU 渲染模式分析」。

## 实机数据（OnePlus PLR110 / Android 16）

同一台手机、同一组操作：清空 gfxinfo 后按「记忆 → 刷题 → 冻结 → 守卫」循环 4 轮（16 次切页），等每页热起来再测。

| 指标 | 1.48.0（优化前） | 1.49.1（优化后） |
| --- | --- | --- |
| 16 次切页总帧数 | 2052 | 293 |
| 平均每次切页绘制 | ≈128 帧 | ≈18 帧 |
| p50 / p90 / p95 | 8 / 9 / 10 ms | 8 / 10 / 12 ms |
| p99 | 27 ms | 93 ms |
| Janky frames | 1.41% | 2.05% |

**怎么读这组数**：旧实现每次切页要连续画 ≈1 秒（双页对滑 + 底栏逐帧改 RenderEffect），大量普通帧把 p99 稀释了；新实现每次只画 ≈0.15 秒，因此**总绘制量降到约 1/7**，p50/p90/p95 持平或略好。新的 p99 长帧来自页面本身，不是过渡：

- 切到「记忆」「刷题」：单次 19–23 帧、0 janky、p99 12ms。
- 切到「冻结」：固定出现 1–2 帧 97–129ms（冻结页时间轴/卡片首帧绘制）。
- 切到「守卫」：1 帧 48–53ms；把 `onResume` 里的状态刷新/同步挪到首帧之后后，长帧降到 ≈24ms，且落在页面已经可见之后。

首次切到某页（Activity 冷创建）的 `Displayed +Xms` 基本没变：记忆 60→67ms、刷题 44→61ms、冻结 368→375ms。也就是说这次优化解决的是**来回切页**的绘制浪费，首切仍取决于各页 inflate + 载入数据。

设备实测脚本：`.codex-fix/perf/measure.ps1`（装包 → 冷切一遍 → 清 gfxinfo → 16 次切页 → 读 Janky/percentile），原始输出在 `.codex-fix/perf/measure-*.txt`。

## 还能再快的地方

- 五个底栏页目前仍是五个独立 Activity，首次切到某页要完整 inflate + 载入本页数据。若要进一步压低首切耗时，方向是单 Activity + 五个 Fragment（或 ViewPager2），改动较大，未在本版做。
- 「冻结」页那 1–2 帧 97–129ms 是页面自身首帧绘制（时间轴 + 卡片 + 渐变），下一步可以给时间轴做分层缓存/延迟绘制，或把首帧的规则比对再往后放。
- `LiveNavWindow` 给每个 Activity 挂了一个悬浮 Dialog 承载玻璃底栏，切页时会 show/hide 一次窗口。固定底栏或液态玻璃为「无」时，其实可以不挂这个窗口；本版没有改，因为默认配置（悬浮 + 玻璃）走不到这条捷径。


## 1.49.3：冻结页首次创建优化（2026-10-25）

- 应用目录查询和名称排序移到单线程后台任务；每次重建使用代次标识，旧任务不会覆盖新页面，销毁时取消待执行任务。
- 应用库保留完整目录，只创建可视区域及左右预留范围内的应用卡片。收起时不加载卡片，展开或横向滚动后补齐；仍可直接拖入时段，实际冻结状态决定灰度。
- 时间轴用时段边界构建当天区间并集，兼容旧的放行规则（取补集），不再在首次绘制中执行 1,440 次 Calendar 规范化。没有更换 Activity 架构，也没有修改冻结执行规则。
- 新增 672 组逐分钟差异检查（967,680 个分钟状态），覆盖多时段、重叠、全天、空规则、跨午夜、周日到周一以及新旧规则语义；FreezeSlotTest 共 690 项通过。

### 同机前后对比

同一安装脚本，安装后依次首次进入记忆、刷题、冻结，再执行 16 次切页。下表是每版一次采样，不是统计学稳定结果，也不是完全相同的帧数量。

| 指标 | 1.49.2 | 1.49.3 |
| --- | --- | --- |
| 冻结页首次 Displayed | 437ms | 137ms |
| 首次记忆 / 刷题 | 56 / 66ms | 61 / 68ms |
| 总绘制帧数 | 357 | 362 |
| p50 / p90 / p95 | 9 / 10 / 13ms | 9 / 10 / 13ms |
| p99 | 93ms | 24ms |
| Janky frames | 10 (2.80%) | 16 (4.42%) |
| Legacy janky | 5 (1.40%) | 4 (1.10%) |

首次 Displayed 不包含后台目录完全加载的时间。目录异步加载有独立布局帧，因此不能根据 p99 降低声称全部掉帧指标改善。实机确认应用库横向滚动出现新卡片、冻结灰度与原因文字、收起再展开均可用。

验证：全部 11 组 Java 回归通过，导入/制卡脚本 43 项通过；APK 编译、签名、安装通过。原始测量保留本地 `.codex-fix/perf/measure-1492-before.txt` 与 `measure-1493-after.txt`，临时文件不提交。

## 1.73.1：主线程周期性卡顿（2026-11-01）

这轮不再是「切页过渡」的问题，而是**界面线程被周期性占满**：冻结页每秒一次、守卫每 30 秒一次，用户在任意页面滑动都可能撞上。

### 怎么定位的

1. `dumpsys gfxinfo com.afs.guard reset` 后只切一次页，读 90Hz 分位数：冻结页出现 350–470ms 的整帧。
2. 屏幕上只有 90 个可见节点，说明不是「视图太多画得慢」，先怀疑主线程活儿；于是在可疑路径上加临时毫秒计时（`tick`、`refreshBadges`、`RuleStore.all`、`StudyGate.holding`、`PenaltyStore.any`、`DailyQuotaStore.snapshot`、图标加载）。
3. 首次打开冻页 + 停留 4 秒，日志给出：`FreezeBoard.tick 470ms`、`refreshBadges pass 466ms`，且每个应用 `Guard.blockReasonNow` 10ms，里面 `StudyGate.holding` 8ms、`PenaltyStore.any` 8ms。
4. 28 行以上的 30 秒级卡顿用**后台线程采样主线程栈**抓（临时探针，debug 构建才开）：`GuardService tick → Guard.apply → DailyQuotaStore.refresh → AppUsageQuota.spans → UsageStatsManager.queryEvents` 阻塞 186ms（另一次 538ms）。
5. 切页长帧用 `dumpsys gfxinfo ... framestats` 拆阶段：冻结页首个绘制帧 `DrawStart→SyncQueued`（display list 录制）55–77ms，layout 6–8ms，GPU 0.2ms —— 说明卡在「画」，不是「算」或「GPU」。

### 改了什么

- **去重复解析**：规则、罚时、额度表、每日额度快照都按「原始串」记忆解析结果，写盘后原始串变了自动失效。`PenaltyStore.any()` 从「每个应用解析一遍规则」改成一次遍历。
- **去重复判定**：冻结页一屏共用一份学习闸门/禁忌/关键应用/生效规则/奖励；`StudyGate.holding` 结果缓存 3 秒（粒度是分钟级）。
- **去重复重画**：徽标文案、颜色、进度值没变就不 setText/setProgress（每处 `setText` 都会让整页重新录制 display list）；整屏刷新从每秒一次改成每 2 秒一次。
- **别在主线程查系统**：`Guard.apply`（含 `UsageStatsManager.queryEvents`）移到后台单线程，合并重复请求；守护节拍、闹钟、开机、JobScheduler 四条入口都改走后台。
- **图标别再每次取**：`ConstantState` 缓存 + 启动后台预热；恢复滚动位置不再 `root.invalidate()` 整窗；窗口背景/系统栏只在值变化时设置。

### 前后数据（PLR110，90Hz，动画倍率 1.0，各一次采样）

| 指标 | 前 | 后 |
| --- | --- | --- |
| 冻结页每秒刷新占用主线程 | 470ms | 8–22ms，且改为 2 秒一次 |
| 冻结页整页重建 `rebuild()` | 538ms | 57ms |
| 冻结页冷开 `Displayed` | 736ms | 217ms |
| 每 30 秒主线程阻塞（使用记录查询） | 186–538ms | 0（后台） |
| 冻结页空闲 12s | 每拍必掉帧 | 6 帧中 3 帧 >11ms，p95 34ms |
| 20 次热切页 | 136 帧 / 26 janky（19.1%）/ p95 34 / p99 89ms | 138 帧 / 26 janky（18.8%）/ p95 34 / p99 97ms |

### 没解决的（别误读成「全面流畅」）

切页仍然每切一次约 1 帧长帧：冻结页首帧 display list 录制 55–77ms，外加系统拉起页面时主线程等 78ms。要彻底消掉得把五个 Activity 合成一页（或给冻结页做懒加载/虚拟化），是结构性改动。本轮只解决「周期性卡顿」和「冷开/重建」，切页指标持平。

测量脚本与原始输出：`.codex-fix/motion-opt/20261101-jank/measure.ps1`、`measure-*.txt`、`frames-*.txt`、`framestats-*.txt`（本地证据目录，不入库）。


## 1.75.1 冻结页首帧只建首屏（中间方案）

1.73.1 之后剩下的唯一一处已知长帧是「切到冻结页」：每次切页约 1 帧 80–110ms。它的成分是
①页面首帧 display list 录制（此前整页一次性建，含首屏以下的使用限额 / 设置区）
②系统拉起新窗口时主线程等待。

这一版只动 ①，做的是「中间方案」里最保守的一条：**首帧只建首屏**，把首屏以下的使用限额和设置区
延后到首帧之后（`screen.post`）再建。不改 Activity 结构、不动拖拽/刷新/滚动恢复逻辑，
并加了代次守卫：期间若又 `rebuild()`，旧的一批视图直接丢弃，不会串到新页面上。

### 前后对比（同机同条件，`dumpsys gfxinfo framestats`，切页 6 次/轮 × 2 轮）

| 指标（切到冻结页那一帧） | 改前 | 改后 |
|---|---|---|
| total 中位数 | 88.7ms | 84.0ms |
| total 均值 | 90.2ms | 84.9ms |
| drawCmd 均值（第 2 轮 6 次） | 56.1ms | 54.3ms |
| 可见变化 | — | 无（下半屏仍在下一帧出现，滚动到底截图核对过使用限额/设置区都在） |

**诚实结论**：只降了约 5%（~5ms），远没到「消除」。剩下的大头是首屏可见内容的录制
（时段卡 + 13 个应用磁贴 + 时间轴）和系统拉起窗口的等待——这两块分别要「精简可见视图」和
「合并五个 Activity」才能动，属于结构性改动，没有在这一版做。想再进一步，下一步该做的是
把应用磁贴的背景/阴影换成更省的绘制方式（先量，再改），而不是继续挪构建时机。

证据：`.codex-fix/motion-opt/20261101-jank/frames-defer-before*.txt`、`frames-defer-after*.txt`。
