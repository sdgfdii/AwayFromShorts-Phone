# 手机动效当前交接

M0–M7已落地，M8综合验收继续，M9已获用户批准、实现并合并。整个目标尚未验收完成。DSH执行器未找到，无job ID；Codex实际实施与修正，gpt-6-astra实际设计和只读复审，不声称DS执行。

## 当前M9

批准原话：“批准 M9，按该范围实施与复测”。方案见TASKS.md第8节。快照`.codex-fix/motion-opt/20261029-m9`，baseline commit e59a55bc5d2d12b9d986d21b452aa0856f7f39c5，417个文件，1.58.6/2189。

只改MainActivity并新增MainDisplayCount：显示用Guard.blockedCount在application-context后台串行计算，一个active和一个最新pending；弱页面owner，生命周期epoch与请求sequence分离。allBlocked→runApply、Guard、Store/schema及原tick频率不改。结果只更新状态文字，首次统计中、错误保留旧值，不假装为0。

Astra第一轮发现慢任务被1秒tick永久淘汰，已修正；第二轮静态复审通过。实施与1.58.8/2191整合均通过MainCount32项（含真实Activity.recreate、1.5秒慢任务持续刷新、单worker/有界pending、应用context、暂停/销毁/旧结果、失败与真实0），两侧离线全套通过；页面12、导航18通过。最初离线运行缺失docs/examples/quiz-source.csv，是快照fixture遗漏；补齐受控fixture后重跑全套通过，不把第一次失败写成通过。

两应用文件及3个QA文件已合并root，写入前核对sha并备份；428个冻结文件除允许的MainActivity之外无漂移。证据root-merge.json/root-source-drift.json。未改Guard、Store/schema、allBlocked→runApply或守卫刷新频率。

PLR110/API36，同1.58.8/2191、三项动画1.0、逐步核实实际90Hz、固定54步各3轮：

| 指标 | 对照 | 候选 |
| --- | --- | --- |
| p95三轮 | 200/121/121ms | 26/26/150ms |
| p99三轮 | 250/250/200ms | 300/101/350ms |
| 长帧/总帧 | 96/299（32.11%） | 53/344（15.41%） |

只证明本次样本有改善且仍有尾帧，不能说所有卡顿消除。样本帧群不同，同时给绝对数量与比例，不仅选择更好看的数。原始gfxinfo、每步页面/实际刷新率和APK身份均保留。

成对22秒诊断追踪分别1698/1773个有效protobuf包，各仅剥除181字节CLI日志；无已知error/data_loss。对照UI blockedCount17次1693.791ms、最长367.279ms；候选该方法18次1511.702ms、最长208.555ms全部在afs-home-count线程，主线程无该方法标记。主线程render对照17次最长391.795ms，候选18次最长29.250ms；最慢render Running271.253/S120.266→Running28.693/S0.557ms。两边采样调用数不同，不拿总耗时相减当整体加速比。allBlocked最长25.683→26.091ms，继续原业务路径，本次不修改。

实体手机留在未插桩整合candidate1.58.8/2191；实体手机刷新率已恢复min=null、peak=165.0。没有清数据、提交测试学习记录、变更DO、提交Git或发布GitHub。M9机制和本批回归已完成，M8剩余矩阵与尾帧仍待完成，整体目标继续进行。

## M7已交付

7个源文件已安全合入root：ActionFeedback、ThemeStore、SubjectDeletion、MemoryActivity、QuizActivity、MemoActivity、DataManagementActivity。弹窗attach主题不占OnShowListener；按钮即时pressed/focus/disabled；Memory旧IO不更新新页、进度与方向按owner收尾；Memo AI保留发送确认/结果预览，关闭UI不伪称取消网络；Data已接受恢复queue/restart不被页面销毁吞掉，调度失败不永久busy。

Astra第二轮静态审查通过，并复核真实queue→销毁→调度失败和Memory重叠任务方向恢复测试。1.58.3整合：离线全套通过，Feedback76、RuntimeAcceptance40、MemoChecklist127、API12；长列表记忆300/题库320/历史300共27项通过。后者后台位置检查收起IME并明确ScrollView取得焦点，不代表已验证搜索框保持焦点的情况。

图片4096×3072使用实际硬件窗口截图验证遮挡/揭晓，隐藏resize后验证bitmap代次与新尺寸采样；单张3145728字节，8张25165824字节。20ms内存采样仅为观察最大值。API24静态玻璃回退6项、实机API36玻璃10项通过；nativeDockGpu和系统cross-window blur均启用，保存shader及无模糊回退截图。

同1.58.2/2185、PLR110、逐步核实实际90Hz、固定54步各三轮：p95 15/16/15→15/15/16ms；长帧71/1135（6.26%）→73/1130（6.46%）。未证明导航性能提升，不能用本批反馈修正宣称全面提速。

本批证据：`.codex-fix/motion-opt/20261029-m78/evidence`。phone-concurrent-latest-identity.json只读确认并行安装的1.58.6/2189与root APK SHA256相同且DEX含M7类/字段；这不等于对1.58.6所有并行功能做过全套验收。保留较新手机包，不降级追逐版本。

## M8诊断与未完成项

1.58.3诊断Perfetto原始8262408字节，剥离181字节CLI日志外壳后保留1977个完整protobuf包；无已知错误/data-loss统计。部分process.name为空，以afs方法标记关联主线程PID15518。SQL及原始结果均保留。

Main.render14次1091.747ms，其中blockedCount14次1003.914ms，最慢96.655ms（Running94.971ms）；typography最大1.239ms。迟开始帧窗口与render重合，同一帧多条重合不能当独立掉帧相加。尚未拆分blockedCount内部包查询/额度/规则读取各自成本；本次没有采到Freeze首次创建图标标记。M9只移动显示统计，不改变守卫判定。

1x/5x Motion各30、导航各14、系统0x策略2已在1.58.2整合通过；1.58.2另通过Render41、学习联动113、冻结10。尚需搜索框焦点＋IME恢复对照、锁屏等完整生命周期矩阵、Freeze首次图标细分、M9同条件性能与锁竞争检查。

实体手机force-stop后进程未消失，WARM/UNKNOWN采样不得记为冷启动。另建无DO自有AVD AFS_Motion_Cold_API24（5586），已取到一轮启动前无进程＋真实导航输入的样本，但后续轮次进程未退出，尚无有效三轮成对冷启动结论。首个onDraw和焦点/可点击条件就绪的onDraw是观测标记，不等同精确呈现时间或触摸延迟。未取得可交付前后录屏。

所有临时实体手机刷新率恢复min=null、peak=165.0，原模拟器三项动画偏好恢复null。未卸载/清除实体手机数据、未测试提交实体手机打卡/评分/奖励、未改DO授权，未发布GitHub。仅停止自有5584以启动自有5586，未操作其他任务的5580。

早期LongLists失败诊断的MemoryStore自动建计划曾将fixture写入自有模拟器记忆库，未能证明回到该批最初状态；实体手机未受影响。后续版本增加记忆文件、cache和completion偏好的备份还原，并把ScrollView.fling(0)改为DOWN/CANCEL停止惯性，排除搜索计数误含EditText。失败尝试不能算通过。

## 已完成M0–M6摘要

已统一MotionPolicy、取消整页切页截图与640ms假加载、去持续装饰帧、收敛导航/卡片取消和提交一次边界；M5–M6合并排版遍历、固定布局胶囊、冻结展开终态、有界后台图片采样和单次Memo分类读取。业务规则及schema不改。

M5–M6最终同1.58.1/2184/实际90Hz：p95 14/15/14→14/12/13ms，长帧66/1141→56/1137。最终对称API24诊断120笔记79.80→45.48ms、分类读取242→1；图片1800×1200构造6.181→0.0968ms、8.64→2.16MB。排版27.61→27.30ms未证明显著耗时收益，冻结布局14→13未称明显改善。完整旧证据保留在20261029-m56/evidence，上一版交接已归档到M9 evidence/previous-HANDOFF.md。


## M8新增验证与M10待审阅

搜索焦点＋IME对照实际完成：Memory300/Quiz320/History300，FocusLifecycle25项状态断言通过，但独立位置观测发现Quiz后台返回500→364。插桩日志证实PageScrollState恢复500后，旧搜索框caret的childRect(immediate=false)发起平滑滚动，8帧降至364；Memory保持500，viewport/内容高度不变。Astra已只读复核根因并给出局部保护设计，具体M10见TASKS第9节，已获用户批准，正在隔离快照实施。

FreshFreeze实机两次新建窗口8项通过，覆盖真实窗口focus/attach、hardware与图标绑定。此instrumentation采集trace虽为有效protobuf，但无afs方法标记，不能作为图标耗时归因。之后正常导航采集因手机已被并行升级1.58.9/2192而由版本守卫拒绝；保留新版，无降级操作。记录capture-fresh-nav.log。刷新率仍恢复null/165.0。

锁屏与窗口生命周期脚本已在自有模拟器通过33项；先前唤醒断言和平台取消消息时序断言失败均保留，不计通过。测试仅实际keyguard锁定时解锁，并等待平台ButtonHandler处理取消，不改产品。诊断APK均仅在忽略快照内，产品源码未作M10更改。


## 当前M10实施检查点

用户已批准。快照20261030-m10，baseline b82ebad55229f028749d42ec46022fe99cafc44f，434文件；仅implementation修改QuizActivity、PageScrollState并新增QuizBankScrollView，未合并root、未安装实体手机。

Astra首轮发现并已修正：实际搜索焦点变化应在super前解除保护；bank必须在改变page前先确认flushDraft成功，否则新类型转换可能在失败时触发。草稿写失败fixture6项通过，原页面、dirty和原root均保留。

首轮BankRestore26项通过，Memory与Quiz返回后均连续8帧500。扩展释放测试的重复原路径出现失败，尚阻断本批验收；不能以首轮通过代替最终结果。诊断首次失效点明确：捕获500，restore unchanged=true，但可见frame从(0,0-1080,1019)变为(0,63-1080,1019)，geometry判false清理保护。已排除将Memory的不同IME底部数据当Quiz原因；正核对内容origin及LAYOUT_FULLSCREEN系统栏覆盖语义，尚未改成忽略全部insets或放宽位置断言。证据bank-probe.txt，诊断日志只在隔离APK。

当前候选应用和设备扩展释放边界、真实IME变化、反复返回、配置重建及整合回归均待完成。保护实现不应误拦新输入、选择、可访问性或真正viewport变化。整个目标继续进行。


### M10最新检查点

当前implementation应用源：QuizActivity、PageScrollState、新增QuizBankScrollView；均未合并root。source已统一原文件换行，diff仅必要行。candidate APK已构建并仅装自有5586；诊断运行均用finally恢复candidate，原始失败日志保留。

验证：bank-draft-failure-device.log 6项通过，offline-candidate.log全套通过；bank-restore-device.log原路径26项通过；statusbar-no-ime-after-device.log 31项通过（明确IME先收起，只测状态栏覆盖）。此纯覆盖控制记录origin[0,0]不变、viewport1080×1794不变、可见top0→63、bottom1794不变，窄化后的保护保持，不能与IME仍打开、额外键盘布局变化的失败控制混为同一条件。

当前仍有失败：bank-release-after-overlay-device.log原路径位置断言曾失败；ordered-bank-1-device.log则是在默认返回已经保持500后，release夹具的重复旧caret断言失败。bank-ordered-trace.txt同PID6915确认实际返回几何等价、拒绝旧caret；其后GlobalLayout清理，下一次arm已见bottom956而此前1019。尚需补清理当时完整几何与交互链，不能据此宣布所有失败仅是夹具问题。Astra正在只读复审最新归一化和测试条件，没有M10最终通过。

接续入口：implementation/dpc/test/device/BankRestoreDeviceTest.java（默认IME原路径＋释放边界），StatusBarRestoreDeviceTest.java（纯顶部覆盖），BankDraftFailureDeviceTest.java（失败保存）。diagnostic目录为最新候选加AFSBankTrace的只读插桩版本；其APK为evidence/ordered-diagnostic.apk，产品candidate为evidence/candidate.apk。诊断日志含滚动/几何，不含搜索文本内容。后续测试必须finally还原产品candidate，并恢复fixture文件、cache、completion偏好。

已把通过的M8 LifecycleWindow33项QA三个文件复制到root便于复现；这不代表M10应用代码合并。整体goal继续进行，不作complete/blocked/paused变更。

## 2026-10-30：打卡百分比一位小数、额度条去字、权限先放开、额度到点就请出去

基准工作区包含此前未提交改动，本轮未创建提交。版本 1.58.12 / 2195，APK SHA256 `13e16fa8e0b855d7ae6915bca0922ae3f7376b01604d28b46b7c32318863c3cf`。

- 打卡页（`CheckinActivity`）：完成度圆环与「已 x / y 单位 · 百分比」改为一位小数，新增纯函数 `PercentText`（`of`/`percent`，除零、负数、NaN 按 0.0，超目标保留真实值 133.3），弧长按真实比例、仍封顶 100%。
- 冻结页使用限额（`FreezeBoardActivity`）：删掉进度条上方那行「已用 x / y 分钟 · 剩余 z 分钟」，只留进度条；读数写进 `contentDescription`，条本身可点（每日限额进 `DailyQuotaActivity`，旧全天额度进 `usageEditor`）。原 `dailyCounters`/`legacyCounters`/`quotaBars` 三张表合并为 `dailyBars`/`legacyBars`（键为 `UsageQuotaProgress`）。暂不计时的条改为中性灰不确定状态，不再红得像「已用爆」。
- 使用情况权限先放开：`FreezeBoardActivity.usageEditor` 不再被授权对话框拦住（未授权时给「去授权」按钮和说明）；`DailyQuotaStore.save` 不再因为启用而要求权限。未授权时 `AppUsageQuota.exceeded`、`DailyQuotaStore.exceeded` 一律返回 false —— 额度只保存、不计时、不冻结，授权后同一节拍恢复。已授权但事件不可读、用量损坏、写盘失败仍保护性冻结。`UsageQuotaProgress` 与 `DailyQuotaStore.description`、`AppUsageQuota.status` 的文案同步改为「未授予使用情况权限，暂不计时」。
- 额度用尽立即生效：`setPackagesSuspended` 只挡下一次启动，前台实例照样能刷。`GuardService` 的 5 秒额度节拍在冻结之后追加 `Guard.kickQuotaExceeded`：用 `AppUsageQuota.foreground`（使用记录里最后一段仍开着的前台区间）判当前应用，命中原因 4／5 就以设备所有者身份启动新增的 `QuotaBlockedActivity`（`FLAG_ACTIVITY_NEW_TASK|CLEAR_TOP|SINGLE_TOP`，manifest 里 `excludeFromRecents`+`singleTop`）。提示页显示应用名、当天用量、零点重置，返回键与「回到桌面」都回桌面，额度恢复后自己退场；灭屏不抢前台，同一应用 8 秒内只拉一次。

验证（本机）：
- `bash dpc/test/run.sh`：全部通过（含新增 `PercentTextTest` 13 项；GuardTest 207 PASS / 0 FAIL）。
- `AFS_PYTHON=<bundled python> bash dpc/build.sh`：构建成功，`aapt2 dump badging` 确认 1.58.12 / 2195；dex 内含 `QuotaBlockedActivity`、`PercentText`、`kickQuotaExceeded`、`foreground`。Windows 自带 `python.exe` 是商店占位程序，必须用真实解释器。
- 虚拟设备 AFS_Test_API34（Android 14，已是 Device Owner，root adb）实机验证，截图在 `.codex-fix/quota-bars-15812/`：
  - 无使用情况权限：冻结页额度条为中性灰不确定状态、条上无文字（`freeze-quota-bar-no-permission.png`）。
  - 授权 + 12/30 分钟：蓝色进度条约 40%（`freeze-quota-bar-counting.png`）。
  - 31.7/30 分钟：整条变红、应用格显示「每日限额用尽 · 30分/日」（`freeze-quota-bar-exhausted.png`）。
  - 直接把 `QuotaBlockedActivity` 拉起来：卡片、应用名、「全天限额 · 已用 31.7 / 30 分钟 · 剩余 0.0 分钟」、回到桌面按钮（`quota-blocked-page.png`）。
  - 端到端：Chrome 前台 + 额度 32 分钟、已用 31.7 分钟，`am start-foreground-service` 起守卫后约 35 秒，`topResumedActivity` 从 `com.android.chrome/ChromeTabbedActivity` 变成 `com.afs.guard/.QuotaBlockedActivity`，页面显示「已用 32.0 / 32 分钟」（`quota-kick-from-chrome.png`）。证明设备所有者后台拉起这条路在 API 34 上成立（AOSP `ActivityStarter`/`BackgroundActivityStartController` 对 device owner 有明确豁免）。
- 设备测试脚本已按新规则同步但**未在真机上跑**：`DailyQuotaDeviceTest`（权限丢失改为「暂停计时、不冻结」，恢复权限后重新超额；`dailyCounters` 改为 `dailyBars` 并按 View 树找 `FreezeTimelineView`）、`LearningLinkDeviceTest`（撤权后 `blockReasonNow==0`，恢复后回到 4）。两者已用 `javac --release 8` 对 `build/classes.jar` 编译通过。

未验证／限制：实体手机未连接，真机未安装；真机上「额度到点请出去」还需确认 ColorOS 是否保留 AOSP 的 device-owner 后台启动豁免，以及被冻结应用正处前台时系统会不会另外弹 `ActionDisabledByAdminDialog`。虚拟设备测试写入的额度配置已恢复为 `{"enabled":false,"groups":[]}`，Chrome 已解冻，`GET_USAGE_STATS` 已恢复默认，模拟器已关闭。

### 真机复测（PLR110 / Android 16，2026-10-30 19:45–19:48）

`adb install -r` 覆盖安装 1.58.12 / 2195，`lastUpdateTime=19:45:13`，数据保留（守卫页仍显示「应用 35 个 · 冻结 18 个 · 关键应用 2 个」）。过程中发现并修正一处回归：

- 第一次真机验证暴露「条和格子分家」：删掉条上方的文字后，9 条额度条挤成一堆、9 个应用格另起一堆，看不出谁是谁。改为 `quotaRows` 里逐条「应用格 → 它自己的条」，共享格子只在第一个成员下面显示一条（`FreezeBoardActivity.quotaRows`）。重新构建安装后复测通过：每个格子下面紧跟自己的条，DroidDeck（已用尽）的条是红的，其余是浅灰空条。
- 真机截图：`.codex-fix/quota-bars-15812/phone-freeze-quota-bars.png`（条与格子一一对应）、`phone-checkin-decimal.png`（打卡页「已 6 / 1000 天 · 0.6%」圆环也是 0.6%，以前会显示 0%）、`phone-main-1.58.12.png`（1.58.12 运行中，数据未变）。
- `GET_USAGE_STATS` 真机本来就是 allow，未改权限状态；未在真机新增/修改额度、未改打卡与冻结规则，只做只读浏览。
- 未验证：真机上「额度到点当场请出去」需要真让某只应用跑到额度用尽（会打断用户使用），本轮只在虚拟设备上端到端验证过；真机 ColorOS/AOSP 的 device-owner 后台启动豁免仍待实际触发确认。
- 本轮最终 APK SHA256 换成上行的 `04aa0e89…`（含条与格子对齐的修正）；虚拟设备那三张截图是修正前布局的取值证据，布局以真机截图为准。

### 追加修正（19:53）：时段格子里只列有额度的那几行

真机上「冻结时段 / 14 个应用」这类格子会把成员再整行列一遍，而其中大多数应用没有时间限额，行是多余的。`FreezeBoardActivity.quotaRows` 现在先算这家应用有没有额度：没有就 `continue`（格子里已经画过），共享格子只在第一个成员处占一行。复测见 `.codex-fix/quota-bars-15812/phone-slot-only-quota-rows.png`：8 个应用的时段格子里只有 MusicFree（时段额度 120分/日）多一行并带条，其余 7 个只留在图标格里。仍保留的行为：有额度的应用会在格子里出现一次、下面再出现一次带条的行（行负责给条一个可读的名字和冻结原因）。

### 追加修正（19:59）：管理限额页放开修改时间

用户要求「管理限额的使用限额独立共享放开权限」。该页当时唯一还拦人的是 21:00–21:59 的修改窗口（使用情况权限那一道在上一轮已放开）。`DailyQuotaData` 新增 `EDIT_WINDOW=false`：`editable()` 恒真、`requireEdit()` 不抛，窗口逻辑与 `nextEdit` 保留，改回 true 即恢复。`DailyQuotaActivity` 顶部文案改成「随时可修改 · 当天已用时间保留」，控件不再置灰；`allowed()` 的提示改成中性一句，仅在窗口恢复时才会用到。

真机 19:59 实测（旧窗口之外）：页面不再是「设置已锁定 · 下次开放 10/30 21:00」，出现「随时可修改」，「＋ 添加限额格子」「合并为共享格子」均可点；点「添加限额格子」直接弹出名称／分钟／选择应用对话框，取消后仍是「尚未设置每日限额」，没有写入任何数据。截图 `.codex-fix/quota-bars-15812/phone-manage-editable-anytime.png`、`phone-manage-add-dialog.png`。

测试同步：`DailyQuotaTest` 改成「任意小时可编辑、write 不再被拒、nextEdit 仍可计算」；`DailyQuotaDeviceTest` 改成校验可编辑并写入成功。离线回归全过（Daily independent/shared quota 33 项）。APK SHA256 `0c11c94c…c6`。代价：额度用爆后可以直接调大——这是放开窗口换来的。

### 追加（20:14）：删掉合并／拆分，共享改成“钩多个应用”

按要求移除管理限额页的「合并为共享格子」「拆分为独立格子」（含 `merge()`／`split()`），成员只在「修改应用和时长 → 选择应用」里勾选：勾一个＝单独额度，勾多个＝共用一份。文案去掉了“独立／共享”，冻结页入口改为「管理限额 ›」，多成员格默认名「共用额度」。数据模型没动，历史共享格照常计时/冻结。验证：离线回归全过（Daily independent/shared quota 33 项），真机 1.58.12 覆盖安装后管理限额页只剩「启用每日限额 / 使用情况访问权限 / ＋ 添加限额格子」，截图 `.codex-fix/quota-bars-15812/phone-manage-no-merge-split.png`；APK SHA256 `a5682b5e…e132`。

### 方案 A 实施（20:37）：使用限额＝一行一个格子 + 旧额度迁移

- 冻结页 `usageCell/quotaRows` 重写：一行一个额度卡（图标/名称/已用·剩余/条，整卡可点），点卡进 `quotaEditor`（名称、分钟、勾选成员；勾多个即共用），长按 `quotaMenu`（修改 / 并到另一个额度… / 删除）。时段卡片复用同一张卡，只列该时段有成员的格子。
- 新增 `DailyQuotaData.mergeInto/without`（纯函数，`DailyQuotaTest` 加 5 项），合并分钟取小、成员去重、账本不动。
- 新增 `DailyQuotaStore.migrateLegacy`：把旧 `AppUsageQuota`（一只应用一份、不能共享）在 Application 启动时并成单成员格子，今天已用按 `AppUsageQuota.used` 结算写入账本，清空旧表并 `enabled=true`；只在有使用情况权限时迁移，迁移前数据留在 `*_before_migration` 键里。
- 真机（PLR110）覆盖安装 1.58.12 / 2195 后：9 个旧额度已迁移成 9 张额度卡（DroidDeck 已用 49.4/30 分钟、条是红的，其余 0/30），点卡弹出「修改额度」（名称/分钟/成员/选择应用/取消·保存），长按弹出「修改成员和分钟数 / 并到另一个额度… / 删除这个额度」。截图 `.codex-fix/quota-bars-15812/phone-quota-cells-migrated.png`、`phone-quota-editor.png`、`phone-quota-longpress-menu.png`。
- 未在真机执行写入型动作（没真的合并或删除用户的额度，避免破坏真实配置）：合并/删除只由离线断言（`mergeInto`、`without`）和代码路径覆盖，需要一次真机点击确认的话我可以拿两个游戏额度做一次合并再还原。
- 离线回归：Daily independent/shared quota 38 项、其余全部通过。APK SHA256 `0d75afb4…4786`。

### 追加（20:52）：成员图标全部展示 + 单独/共享 + 二级菜单

- `MemberIcons`（新文件）：自绘成员图标行，20dp、按宽度自动换行，全部成员都画出来，删掉「+N」截断；`quotaCard` 布局改为名称 → 图标行 → 已用/剩余 → 条。
- `quotaEditor` 增加「单独 / 共享」分段选择：单独＝单选应用（切换时只保留第一只），共享＝多选应用，保存前校验“单独额度只能放一只应用”。
- 新增 `quotaAddMenu`：使用限额右上角 ⋯ 打开二级菜单（新建单独额度 / 新建共享额度 / 管理额度），每行彩色图标 + 标题 + 副标题 + 箭头；页面上不再直接摆新建按钮。
- 真机 1.58.12 / 2195 覆盖安装验证：共享格子（8 只应用）把 8 个图标一行画全；⋯ 菜单三行样式正常；点「新建共享额度」直接打开带“共享”选中的额度对话框。截图 `.codex-fix/quota-bars-15812/phone-quota-icons-all.png`、`phone-quota-menu-sheet.png`、`phone-quota-shared-editor.png`。
- 离线回归全过（Daily independent/shared quota 38 项）。APK SHA256 `ffb561ce…e451`。

### 追加（20:58）：成员图标可拖拽迁移 + 共享格子标题

- `MemberIcons` 换成 `MemberStrip`（流式子 View 布局，22dp 自动换行）：每枚成员图标可单独长按拖拽，payload 复用冻结页的 `freeze-app`；额度卡本身成为拖放目标，悬停高亮，松手调用 `DailyQuotaData.moveInto` 完成迁移（原格子搬空的自动删除），同格内拖放只提示“已经在这个额度里”。
- 多成员额度卡标题固定「共享格子」，单成员仍显示应用名；编辑对话框里的名称字段保留。
- 迁移逻辑抽成 `DailyQuotaData.moveInto` 纯函数，`DailyQuotaTest` 加 5 项（迁移成功、源格清空删除、重复迁移 no-op、目标不存在拒绝、目标保留两只应用）。
- 真机 1.58.12 / 2195：长按共享格子里 Edge 图标再松手，卡片高亮后弹出「这只应用已经在这个额度里」，说明拖拽→落点→迁移这条链路在真机上通；跨格迁移未在真机执行（会改动真实额度，且 adb 无法合成“长按后拖动”的手势），由离线断言覆盖。截图 `.codex-fix/quota-bars-15812/phone-shared-title.png`、`phone-member-drag-drop.png`。
- 离线回归：Daily independent/shared quota 43 项通过，其余全套 207 PASS。APK SHA256 `96d56545…891fa`。

### 追加（21:08）：成员图标 22dp → 30dp

`MemberStrip` 图标 22dp/间距 6dp 改成 30dp/间距 6dp（圆角 8dp），八只成员仍是一行；长按拖拽的目标随之变大。真机核对：`.codex-fix/quota-bars-15812/phone-quota-icons-30dp.png`，1.58.12 / 2195 覆盖安装，SHA256 `1f9daeb8…0800e`；离线回归 43 + 207 项全过。

### 追加（21:13）：应用库拆成「冻结时段 / 时间限额」两页

`buildLibrary` 增加页签（`slotTab/quotaTab` + `libraryTab`）：冻结页沿用原状态文案，限额页显示「未限额 / N 分钟/日 / 共享格子 · N 分钟/日」。两页共用清单与拖拽载荷，拖拽悬停另一页 0.35 秒自动切页（`hoverTab`），切页只 `updateLibraryApp` 改文字、不重建列表，所以拖拽不中断。真机 1.58.12 / 2195 两页切换核对通过，截图 `.codex-fix/quota-bars-15812/phone-library-tabs-{frozen,quota}.png`；离线 43 + 207 项全过，APK SHA256 `cce2647e…16f4`。

### 追加（21:20）：应用库两页各自过滤

`renderLibrary` 按页签重铺：冻结页 `libraryVisible` 只认“没有时段绑定”的待安排应用，限额页只认 `DailyQuotaStore.group(pkg)==null` 的未限额应用；页头数量随过滤变化（真机 73 → 冻结 47 / 限额 62）。切页重铺列表，懒加载游标按新列表重算；系统级拖拽不因重铺中断。真机 1.58.12 / 2195 两页核对通过，截图 `.codex-fix/quota-bars-15812/phone-library-filtered-{frozen,quota}.png`；离线 43 + 207 项全过，APK SHA256 `bd981882…0f21`。

### 追加（21:32）：额度修改权限每晚 22:00–23:00 自动开放

按用户要求把额度配置权限改成夜间自动开放一档：`DailyQuotaData.EDIT_WINDOW=true`、`WINDOW_HOUR=22`（原 21 点档位系历史设计，本次按“10 点到 11 点”改为 22:00–23:00），`nextEdit` 与提示语同步；冻结页的 quotaEditor / moveIntoGroup / confirmRemove / confirmMerge 在窗口外直接 toast「每日限额只在 22:00–23:00 可改」，不弹窗、不写盘。删掉上一版临时加的权限横幅。

真机（21:33，窗口外）实测：点「新建单独额度」弹出该提示且没有对话框；管理限额页显示「设置已锁定 · 下次开放 10-30 22:00」。截图 `.codex-fix/quota-bars-15812/phone-quota-locked-toast.png`、`phone-quota-next-window.png`。离线断言改为 22:00/22:59 可改、21:59/23:00/12:00 锁定且 `requireEdit` 抛错；设备断言按当前小时分支为“窗口内可写 / 窗口外拒绝”。Daily independent/shared quota 45 项、其余 207 PASS。APK SHA256 `133ab3c1…958b`。

### 追加（21:37）：关键应用名单重做

`keyApps()` 重写：30dp 图标 + 名称 + 状态行（时段/全天/额度/共享格子/当前冻结原因）+ 右侧勾选框；顶部搜索框（label 或包名子串）与「已选 N / 总数」，底部「全选当前 / 清空」；保存逻辑与候选集（规则里的应用 + 额度里的应用）保持不变。真机 1.58.12 / 2195 打开核对：36 个候选、已选 2，状态行正确（例如「全天 · 共享格子 60 分钟/日 · 每日限额用尽」）。截图 `.codex-fix/quota-bars-15812/phone-key-apps-dialog.png`；未保存任何勾选改动。离线 43→45 项与 207 PASS 全过，APK SHA256 `13e16fa8…c3cf`。搜索框的过滤逻辑是标准 `TextWatcher`，真机上 adb 合成输入没打进对话框，需手指敲一下确认。

## 2026-10-31：1.59.0 工具箱重做 + 设置页 + 限额重置时刻可配

版本 1.59.0 / 2196，SHA256 `e1de99b8…0801`，包 `afs-guard-1.59.0-toolbox-settings.apk`。

- 限额日界抽成 `DayBoundary`（默认 0 点，与旧的 `set(HOUR_OF_DAY,0)` 逐毫秒一致）；`DailyQuotaData`、`DailyQuotaStore`、`AppUsageQuota.spans` 全部改走它，旧账本不清空；设置页可选 0–23 时。
- `ToolboxActivity` 重写：计时器/抽签/文本下线，番茄钟（DO 下 LockTask 真锁屏、长按放弃）、正念空间、未来倒计时上线；计算器/单位/日期/PDF/图片保留为「实用工具」。
- 新增 `SettingsActivity`（外观、语言、重置时刻、权限体检、检查更新、隐私）、`Lang`（底栏 + 设置页双语，其余页面仍中文）、`UpdateCheck`（版本比较；更新源留空时如实报未配置）。
- 离线：`dpc/test/run.sh` 全过，新增 DayBoundaryTest 17 / FocusPlanTest 43 / UpdateCheckTest 18，GuardTest 仍 207 PASS。
- 未做：真机安装与 LockTask 实测（本轮无设备连接）；余下 11 项见 TASKS.md 第 11 节。

## 2026-10-31（二）：1.60.0 打卡日历 + 日记 + 成就

版本 1.60.0 / 2197，SHA256 `73de2713…7058`，包 `afs-guard-1.60.0-calendar-journal-achievements.apk`。

- `CalendarData`（新，纯 java.util，无 Android 依赖）：日期换算用 Howard Hinnant 公历日序号，
  不碰 Calendar 时区，连续天数不会被夏令时算断；按日聚合 distinct goalId；满勤 = 当天目标数 ≥ 当前目标数。
- `JournalStore`（新）：独立 SharedPreferences(`afs_journal`)，**不进 study.json** —— 日记是长文本，
  混进账本会让同步冲突变成整本覆盖。空文本 = 删除。
- `Achievement`（新）：10 枚成就全部由 `CalendarData.Stats` 现算，不落"已解锁"标记，删记录后不会出现矛盾状态。
- `CalendarActivity`（新）：月历格子（满勤实心/部分浅色/今天描边/日记点）、翻月、当天明细弹窗、月度小结、成就墙。
- 入口：打卡页顶栏「日历」；设置页「打卡 → 打卡日记（开关）/ 打卡日历」。
- 离线：新增 `CalendarDataTest` 51 项，`dpc/test/run.sh` 全绿，GuardTest 207 PASS 不变。
- 未做：真机安装与交互实测（无设备）；日历只读账本，不写打卡数据。

## 2026-10-31（三）：1.61.0 全量英文化 + 删五个工具 + 番茄钟去逃生口

版本 1.61.0 / 2198，SHA256 `a3b0572d…016c`，包 `afs-guard-1.61.0-full-i18n.apk`。

- **全量英文**：1875 条中文字面量全部包 `T.s()/T.f()`，`assets/i18n_en.txt` 1880 行，
  `tools/i18n.py check` 覆盖率 100%、占位符错误 0。语言开关（设置 → 界面语言）现在
  影响整个 App，切完 `recreate()` 即时生效。
- **翻译表放 assets 不放 res**：离线回归直接用 javac 编译源码，没有 aapt2 生成的 R 类。
- **四个静默失败点已修**：值里换行没转义（记录被劈两行）、`#` 开头文案被当注释、
  空串与 `\e` 混淆、占位符校验把 `90% and` 误读成 `%a`。前三个任意一个都会让英文模式
  悄悄显示中文，且不报错 —— 这是这个方案最容易踩的坑。
- **新增 TranslationTest**（3771 断言，`dpc/test/run.sh` 已挂上）：解析真实翻译表、
  转义、`\e`、`#` 键、坏行容错。全套回归 0 失败，GuardTest 207 PASS。
- **删掉五个实用工具**：ToolkitActivity / ToolMath / ToolFiles / Toolbox 及两个测试已删除，
  清单与首页磁贴同步。工具箱现在只有番茄钟、正念空间、未来倒计时。
- **番茄钟去逃生口**：长按放弃的整段代码删除，专注期唯一出口是时间走完。非 DO 环境拦不住
  Home，界面直说。
- 未做：真机安装与英文模式逐屏核对（本轮无设备）。拼接处的空格/语序是唯一没测试覆盖的风险。

## 2026-10-31（四）：1.62.1 真机实测 —— 番茄钟锁定成立，英文模式补齐

版本 1.62.1 / 2201，SHA256 `b84c720d…76a3`，包 `afs-guard-1.62.1-i18n-real-device.apk`。
设备 PLR110 / Android 14 / DO 在位，**本次为真机实测，不是仅离线回归**。

### 番茄钟 LockTask（1.61 遗留的未验证项）

- 开始专注后 `mLockTaskModeState=LOCKED`；返回、Home、最近任务三键全部无效。
- 用临时 1 分钟档测得：计时结束自动 `stopLockTask()` → `NONE`，Home 恢复。
  测试档已撤销，正式包预设仍是 15/25/45/50。
- **强停应用会重启并重新进入锁定**；唯一硬出口是**重启手机**（锁不跨重启）。
  这是「不要逃生出口」的直接代价，必须让用户知道。

### 英文模式：1.61 报的 100% 是假的

工具跳过规则太宽（往前看 80 字符），一处 `.equals(` 会连累同行的真文案。
改成「跳过的调用必须没有闭合括号」后，多扫出 320 处未包裹，现已全部补齐，
`tools/i18n.py check` = 2099/2099，占位符 0 错。

真机暴露并修掉的问题：
1. `Arrays.asList("正确","对",…)` 这类**解析用 token 被翻译** → 英文下导中文题库会失败。
2. 单字碎片「日」被译成空串 → 日历星期表头**少了周日**；「月/日/年」拼接 → 时间显示「10 23」。
3. 日期 `Locale` 写死 CHINA → 格式翻了、星期还是「周六」。新增 `T.locale()`。
4. 磁贴副标题英文超长被静默裁断（`·` 结尾）。改 `ellipsize=END` + 缩短文案。

### 工具链事故（已修）

`io.open(..., 'w', newline='\\n')` 参数写错时文件**已先被截断**才抛异常 → `tools/i18n.py` 被清零。
已按 `assets/i18n_en.txt` 现有格式重写，并加了「读入→写出逐字节一致」的往返校验后才继续用。

回归：`dpc/test/run.sh` 0 失败，`TranslationTest` 4253 断言，GuardTest 207 PASS。
