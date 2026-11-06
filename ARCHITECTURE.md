# AwayFromShorts-App 手机版插件宿主：第一阶段设计

日期：2026-11-03，修订R2：用户已明确要求插件独立安装更新；原内置首版建议撤销。状态：**R2最小范围已实现并集成；两只独立APK与实际验收见 HANDOFF-R2.md 和 docs/PLUGIN-R2-VALIDATION.md。**

## A0. 范围、依据与约定

- 代码基线是 `AwayFromShorts-Phone/`，本次名称 AwayFromShorts-App 指手机产品，不是已经存在的第二套 App。HEAD 为 `e736abd`，工作区有大量既有未提交修改，必须按当前源码而非仅按 HEAD 实施。
- 本文A2保留最初调查链路；实施后：HistoryActivity → HostPluginRouter → 注册条目/prepare → 独立Activity → AIDL HostStatsBridge → HostUsageStats只读聚合。实际类与契约以新源码和验证记录为准。DS初稿中止后Codex修正，Astra独立评审；没有Git提交或发布。
- 已读根目录现有 ARCHITECTURE/TASKS/HANDOFF 约定、AndroidManifest、启动、导航、规则与统计源码；项目及检查过的父目录未找到 AGENTS.md。保留 Java 8 / 原生 View / 多 Activity，JDK17 + aapt2/d8/zipalign/apksigner，minSdk24、targetSdk34；不加 Gradle、AndroidX、Compose、外部插件框架。
- 保留既有 schema、路径、签名、包名和 Device Owner；记忆、刷题与普通打卡保持独立。没有足够证据时不得宣称隔离、无卡顿或已通过实机验证。
- 下方保留的旧动效与功能文档仍约束相关实现，但不是本插件项目的实施批准。版本默认值、根 APK、手机版本可能来自并行任务；实施前须另冻源码、APK身份、数据和测试证据。

## A1. 全部可选插件关闭后，用户看见什么

**首轮设计中的实际行为**：App 仍进入“守卫”；保留现有五项底栏、冻结应用、运行状态、部署与权限、设置、备份与恢复。打卡、记忆、刷题等本轮未迁出的页面仍是 legacy 功能，不是假装成可选插件。独立APK插件“屏幕使用统计”未安装、关闭或卸载后，打卡历史中的该入口消失；设置里的插件管理仍列出它并允许重启用。系统权限设置原有入口仍有效，但不通过隐藏统计页绕过停用。老的 StatsActivity Intent 也必须检查运行时状态，不能直达旧业务页面。

仍有效：已启用的时段冻结、使用额度、学习联动与罚时、断网等限制；宿主查看原因、在现有允许条件内编辑规则、权限修复、备份、维护恢复、原有学习和笔记操作。停用统计不会停止 GuardService，也不会删除任何学习/规则/统计缓存。

**最终精简方向，非首轮承诺**：业务 UI 逐个迁出后，只有守卫、冻结、设置与插件管理留在核心；可选功能关闭才会使相应导航消失。当前只迁一个插件，因此 APK 体积和其余业务代码不会立刻精简到这个终态。若用户要求首版立即只剩这几个页面，需另批完整业务迁移，不能塞进本轮。

## A2. 已核实的真实代码链

### A2.1 首插件的现有用户链路（源码事实）

1. `dpc/AndroidManifest.xml`：Application=`GuardClock`；MAIN/LAUNCHER=`MainActivity`。
2. `GuardClock.onCreate()`：先 `LocalBackup.startup()` 与时间区固定，再后台初始化/迁移；不是插件运行时。
3. `MainActivity.onCreate()` 创建“打卡历史”tile，点击启动 `HistoryActivity`。
4. `HistoryActivity.render()` 的“更多 / 屏幕使用统计”按钮，当前直接 `new Intent(..., StatsActivity.class)`（本次审计约439行）。
5. `StatsActivity.onCreate()/onResume() → render()`：检查 `hasUsageAccess()`；无权限显示 `permCard()`，用户点击进入 `Settings.ACTION_USAGE_ACCESS_SETTINGS`。
6. 有权限：后台线程 `collect(Context)` 读 `UsageStatsManager`、`PackageManager`，用 `RuleStore.packages()` 给娱乐分类；取本月前10名、今天/本月游戏时长、7天娱乐占比。`PcStats.ensureFresh()` 复用既有Gist同步策略、缓存；`renderData()` 再读电脑本日/本月/总计及更新时间。`onResume()` 还有 `Hub.kick()`，因此整页并非纯无副作用函数。
7. `ui.post()` 通过 `gen`、isFinishing/isDestroyed 检查再展示。最终结果是只读图表/排行；不创建打卡，不修改冻结规则；但会访问系统统计、拉取及更新统计缓存。

结论：**迁移 StatsActivity 的展示，而非把整个类原样作为插件。** 系统数据查询、规则分类、网络/凭据、缓存写入与启动/恢复订阅归宿主；插件只消费按展示需要最小化的快照（名称与时长仍属敏感数据）。

### A2.2 已生效冻结的真实链路（安全基线）

用户在冻结页把应用拖入时间格 → `FreezeBoardActivity.moveApp(pkg, slotId)` 校验应用存在/lockedForMove/时间格 → `RuleStore.put()` 保持关键应用语义、保存规则 → `refreshAssignments()` 更新 UI → `applyRules()` 后台调用 `Guard.apply()`。

- 谁保存：`RuleStore` 在 `afs_guard` SharedPreferences 的 `rules` JSON 中按包名持久化；`FreezeSlotStore` 的 `freeze_slots` 保存格子，不能用格子 UI 当作唯一有效规则。`DailyQuotaStore` 在 `afs_daily_quota` 保存配置与用量；旧 `AppUsageQuota` 迁移仍保留。
- 落盘细节：当前 `RuleStore.save()` 使用 SharedPreferences.apply()，不能将UI调用成功宣传为已完成同步刷盘的事务凭据。未来规则写API若需要持久化确认，须单独审定；首插件不引入或修改这条写链。
- 谁判定：`Guard.enforcedRules()` 合并时段与额度应用；`blockReasonNow()/effectiveReason()/effectiveBase()` 汇总规则、关键应用、额度、学习闸门、`PenaltyStore`、奖励和 `KeyGate`。现有先后顺序不是插件优先级，不在本轮重排。
- 谁执行：`Guard.apply()` 在原策略同步约束下调用 `setSuspended()`，最终由 `DevicePolicyManager.setPackagesSuspended()` 落系统限制；`AfsAdmin` 是管理组件。额度到点驱离、锁机、无障碍页面拦截、VPN断网各有既有执行器，不可只保留一个布尔“屏蔽”替代。
- 谁重算：`GuardService` tick/异步 apply、`AlarmReceiver`、`BootReceiver`、`GuardJob` 及 AfsAdmin 回调；断网另走 `NetBlockService/NetFallback.reconcile()`。
- 重要限制：BootReceiver 虽列 LOCKED_BOOT_COMPLETED，当前清单/存储没有据此证明解锁前 Direct Boot 可用。设备杀进程、无系统授权、OEM限后台、坏数据的既有降级不等于始终硬执行；文档不得把插件化说成消除了这些限制。

### A2.3 数据入口与首轮可见性

| 入口 | 当前数据 | 首插件能否获取 |
| --- | --- | --- |
| StudyStore | `study.json` 普通目标/打卡；读写锁 | 否，统计插件不需要 |
| MemoryStore / LearningLink | `memory-v1.json`、memory-media、独立学习进度 | 否，不加入打卡队列，不混存 |
| QuizStore / LearningLink | `quiz-v1.json` 与答题进度 | 否 |
| CardStore / StudyGate / PenaltyStore | cards-v1.json、禁忌认领/罚时等偏好 | 否 |
| RuleStore / FreezeSlotStore / DailyQuotaStore | 有效冻结规则、时间格、额度用量 | 仅宿主用于娱乐分类；不返回原规则或包名单 |
| UsageStatsManager / PackageManager | 系统应用前台用时、名称/游戏分类 | 仅宿主查询，返回排行名称和聚合时长，不给事件流、Intent、UID或包名 |
| PcStats / Hub / SyncConfig | 既有电脑统计缓存、同步目标及凭据 | 只给电脑排行/时长/缓存更新时间，不给Gist地址、token或原JSON |
| MemoStore / MemoLibrary / MemoAttachment、LocalBackup | 笔记、分类、附件和白名单备份/恢复 | 否，备份与恢复始终宿主持有 |

## A3. 职责归属

| 类别 | 职责/类 | 理由与边界 |
| --- | --- | --- |
| 必须由宿主持有 | 包身份、签名、AfsAdmin、GuardClock启动、权限/维护恢复 | 权限和已部署数据不能依赖插件存在；本轮继续 `com.afs.guard` |
| 必须由宿主持有 | RuleStore/FreezeSlotStore/DailyQuotaStore及规则提交校验；Guard、Service/Alarm/Boot/Job | 规则提交后独立于插件生命周期，判定/放行/执行最终由宿主决定 |
| 必须由宿主持有 | 学习联动/禁忌罚时/奖励抵扣的执行与相应Store、LockEnforcer/KeywordGuardService/NetBlockService等 | 关 UI 不能解除已启用限制；学习完成与奖励不得由插件回调自报 |
| 必须由宿主持有 | 数据所有权、原子写入、备份恢复、同步凭据、使用情况授权与统计查询代理 | 不交数据库/Context服务代理等无限制数据句柄；首接口仅展示聚合 |
| 必须由宿主持有 | Settings/插件管理、入口目录、运行时、授权、取消/故障处置、公共主题语言/底部安全区 | 插件不能成为自身权限或健康状态的最终裁定者 |
| 首先迁为插件 | StatsActivity 的图表、排行、电脑统计本日/月/总计切换与空态 | 用户可见且不参与限制决策；同一个聚合查询覆盖现有展示 |
| 暂留原实现 | HistoryActivity 的撤回、总结、日记、打卡日期；CalendarActivity | 与写入/总结义务耦合；日历源码含保存和清空日记，并非只读 |
| 暂留原实现 | Checkin/Memory/Quiz、卡管理、备忘录、工具箱、成就 | 不扩大首轮接口、数据迁移和回归面；不以“关闭全部插件”为由删除它们 |
| 暂留原实现 | 五项底栏、Stats旧渲染副本、现有构建/Activity模式 | 逐步迁移；旧统计副本只供部署回退开关，不是停用时的暗门 |

### 已有规则怎样跨插件停用/故障存活

首插件**没有规则写入接口或规则评估扩展点**，没有时钟/tick/开机回调。宿主插件状态仅存 `afs_plugins_v1`，不得复用规则/学习配置表。禁用、拒绝版本或初始化失败只清入口/页面/查询，不调用 RuleStore.remove/Guard放行、清quota或恢复备份。对上述入口做依赖扫描：执行核心不能导入插件包，也不能根据 enabledPlugins 判断是否执行已有规则。

未来若批准规则配置插件，宿主只能接受现有可验证、可独立解释的具体规则数据；必须先过既有编辑窗口/只进不出/关键应用/额度校验，再原子持久化，返回提交凭据。插件ID只是来源标签，不是规则租约；关闭插件不删除规则。不得保存需要插件在线执行的闭包或脚本，不得以无响应自动批准放行。学习条件如以后迁UI，其权威评估与最后有效进度仍在宿主，不允许插件停用后自动记为完成；必须保留用户补完任务/重新启用入口。该未来写接口**不在首版API中**。

未知规则版本、原数据损坏、设备授权缺失要由现有核心报告并按已有策略处理；首轮不承诺修复所有旧解析降级。严禁用“插件失败所以返回空规则”接入 Guard。若存储行为与此冲突，暂停该迁移并提交问题，不能顺手重写限制语义。

## A4. R2：外部APK的最小契约

### A4.0 进程与交付边界

宿主继续 `com.afs.guard`，插件APK独立包 `com.afs.plugin.usagestats`；**不同UID、无sharedUserId，不加载插件DEX，不共享进程、文件或Context**。插件自己持有Activity和View；宿主不是跨进程接收原生View。对外协议使用原生Java AIDL/Binder；已确认本机SDK34有aidl.exe，无需换Gradle/AndroidX。

首版仅认可现有官方发行证书签名的插件，但不把每个插件实现或包名硬编码进宿主。通过服务action+静态元数据发现同一可信发行者、协议兼容、只使用已开放能力的新插件，由用户逐个启用。首插件包名只是交付对象，不是宿主核心switch分支。后续同协议、同信任证书、现有能力范围内新增插件不需要改宿主代码；新宿主能力或协议大版本仍需升级宿主，这是边界，不是隐蔽的每插件重装要求。

### A4.1 宿主能力：插件 → 宿主

表内IPC已在 `plugin-api/aidl` 实现，SDK源码在 `plugin-api/java`；以下设计伪码以实际公开签名为准。`oneway`为远程异步AIDL调用，返回结果经专用callback；禁止在UI线程等待Binder结果。

| 签名与调用例子 | 权限/身份/数据 | 失败与宿主处理 |
| --- | --- | --- |
| `oneway IHostStatsBridge.openSession(in LaunchTicket ticket, IPageClient client, IOpenCallback cb)`；插件Activity启动后 `bridge.openSession(ticket, client, openResult)` | 只兑换宿主点击入口时签发的票据；由Binder真实UID、安装包/证书、协议、enabled、安装代次共同校验。不是传一个pluginId就能读取他人数据。返回opaque `PageLease`，无文件/凭据 | INVALID_TICKET、BAD_CALLER、INCOMPATIBLE、DISABLED、EXPIRED。失败不执行统计查询；未信任调用可由OS权限直接拒绝，不保证向其回调任何信息 |
| `oneway IHostStatsBridge.loadCurrentUsageSummary(in PageLease lease, long requestId, IUsageCallback cb)`；插件onResume或用户刷新时调用 | `usage.summary.read`；校验每个IPC真实UID、lease及授权代次，后台读取现有UsageStats/RuleStore分类/PcStats；只交付最小只读快照。无系统使用事件、包名名单、Store JSON、Context、凭据或修改规则能力 | OK、PERMISSION_REQUIRED、REVOKED、DISABLED、EXPIRED、BUSY、TIMEOUT、UNAVAILABLE。OS统计权限+读取grant缺一不可；空态为OK+tooShort/pcEmpty。异步完成前再校验代次、调用包安装状态；不得先回调数据再检查 |
| `oneway IHostStatsBridge.cancelRequest(in PageLease lease, long requestId)`；插件onPause/离页取消在途请求 | 仅当前调用UID及lease自己的请求；不取消Guard、全局Hub、其他插件任务 | 幂等；撤队列和回调。已开始的既有同步可按原逻辑结束但不可交付旧结果，不保证强杀任意线程 |
| `oneway IHostStatsBridge.closeSession(in PageLease lease)`；插件最终退出/销毁调用 | 释放自身lease、查询、死亡监听，不清业务数据。页面配置变化可由SDK内存controller转交同一client，不能把长期凭据写入savedInstance或文件 | 幂等；异常、Binder断开由宿主自己的超时/死亡清理兜底；不依赖插件愿意主动释放 |
| SDK导航封装 `boolean PluginNavigation.openUsageAccessForResult(Activity caller, PageLease lease, int requestCode)`；用户点权限卡按钮调用 | `usage.access.request`；显式startActivityForResult启动**宿主自己的**HostUsageAccessActivity，只传opaque lease。宿主用系统getCallingPackage（null拒绝）反查UID/签名及lease，不信任Intent自报包名。宿主页显示授权说明；onCreate先校验一次，用户再次点“进入系统设置”时重验真实来源/安装身份、lease期限及usage.access.request授权代次，失效则拒绝，不能靠首次校验永久放行，插件无需持有PACKAGE_USAGE_STATS | 宿主页面返回GRANTED/NOT_GRANTED/REVOKED/UNAVAILABLE；打开设置不代表授权成功。从宿主返回插件后，插件onResume重查数据。不得从Binder回调中后台强拉宿主Activity或把返回键误报成明确拒绝 |

回调同属版本化AIDL：`oneway IOpenCallback.onResult(in OpenResult result)`、`oneway IUsageCallback.onResult(in UsageResult result)`、`oneway IPrepareCallback.onResult(in PrepareResult result)`，分别承载launchId/lease、requestId/有界快照、requestId/READY或FAILED；接收方校验回调真实UID与当前请求/安装代次，再切UI线程。`IPageClient`只作会话死亡锚点，不含额外业务事件。`PageLaunch`只含requestId、pageId、protocol、主题值和locale；不存在Context/View/任意Intent。

两项grant独立：缺请求设置grant但已有读取grant+OS授权仍能读；只有请求grant时可以前往宿主权限页，但不能读数据。API只读统计，不能申请任意Android权限、启动任意Intent、HTTP、导出笔记、提交打卡或提供冻结判定。

`UsageSummary`：`asOfEpochMs, zoneId, monthKey, tooShort, phoneTop10, todayGameMs, monthGameMs, trend7, pcDay, pcMonth, pcTotal, pcSince, pcUpdatedAt, pcStale`。每个PC period为`PcPeriodSummary{top10,totalMs,empty}`，先按完整列表算total，再裁前10，保持旧页tabs总计口径。`DisplayUsage{displayName,foregroundMs}`；`TrendDay{date,totalMs,funMs}`。最多40条排行+7天趋势；标签限制256字符，线协议单次64KiB以内；总计不截断。固定版本Parcelable字段按界限显式读写，无任意Bundle/反序列化Java对象/文件描述符/共享内存句柄。双方拒绝未知大版本和过长列表；禁止仅依赖Binder全局大小上限。

宿主Stub入口在任何切线程或clearCallingIdentity之前捕获`Binder.getCallingUid()`；查已安装包及签名、拒绝共享UID，再创建不可变CallIdentity交给worker，不能在worker里读getCallingUid当原调用者。签名权限只做第一道门，所有官方App都可能拥有它，具体lease/授权校验不能省略。`onBind()`不是验证远程业务调用UID的位置。插件也验证显式绑定的宿主包名与签名。

代次与交付顺序：安装代次（包版本/安装指纹变化）、enabled代次和每项grant的授权代次分开保存/检查；撤销read即使仍enabled，也作废旧统计结果。宿主用每插件/会话的串行控制与派发顺序处理Revoke/Deliver，禁止“先检查后无保护地跨线程发送”。停用入口先进入停止接受新请求状态；撤销完成的确认在串行屏障之后，所有结果发送必须在同一顺序内复查代次。不得持有Guard锁、阻塞UI线程或用远程回调执行成功作停用条件。已在撤销前发送到Binder的结果可能在撤销后才被插件收到，不能保证撤销按钮点击后对端零回调；验收应证明撤销屏障生效后没有新的数据发送，已在途交付单独记录。

会话说明：60秒票据、15分钟lease、2秒prepare和5秒查询预算全部由宿主以 `SystemClock.elapsedRealtime()` 裁定，绑定宿主进程随机代次；不使用设备墙钟、插件上报时间或业务GuardClock，宿主重启旧资格一律失效。一次性启动票据用256位随机值、60秒有效，绑定插件安装代次/UID/页面/启用代次；兑换后作废，不能重放。兑换产生的PageLease最长15分钟，仅内存。plugin onPause只取消查询和UI工作，不把“前往宿主授权”的合法往返立即关闭；onDestroy/用户返回最终close。返回后lease仍有效可重查，过期显示“请从宿主重新打开”，不擅自续为永久后台访问。client Binder死亡、停用、卸载/更新、宿主重启立即作废。插件自报可见性不作为安全认证；OS并不证明所有SDK回调都来自真正用户点击。

每UID最多1个进行中的查询、1个待处理；全局最多2个worker+4个排队项，过量BUSY。同页快速刷新合并；查询逻辑截止5秒，未完成则返回旧缓存标记stale或TIMEOUT，旧后台IO可正常收尾但不再交付。取消和限流不改变已有同步/额度时钟，不在Guard锁下查询。

### A4.2 行为扩展：宿主 → 插件

| 签名/事件与实际例子 | 插件能观察/追加/修改/阻止什么 | 优先级、故障与最终决定 |
| --- | --- | --- |
| AndroidManifest静态声明：发现`com.afs.guard.PLUGIN_V1`服务并读protocol/id/version/capabilities/pageActivity/slot/order | **追加**已校验页面入口；无代码运行时注册核心规则的权力。例：usage-stats/screen追加到HISTORY_MORE | 只发布已安装、兼容、用户启用的项。核心项不可覆盖；同slot按order再pluginId排序；重复ID拒绝，宿主决定最终列表。先元数据校验再绑定，不执行未知代码读取声明 |
| `oneway IPluginEndpoint.preparePage(in PageLaunch launch, IPrepareCallback cb)`；宿主用户点统计入口后，显式绑定服务，发requestId/协议/主题语言，不含统计数据 | **观察**启动；仅返回READY或FAILED，不能改宿主规则或重定向任意组件 | 绑定/初始化合计2秒逻辑截止；callback校验远端真实UID、requestId、安装/启用代次，迟到忽略。超时解绑并在宿主原页面显示失败反馈，不等待远程线程、不强杀插件。宿主UI/Guard均不阻塞 |
| `HostPluginRouter.openPage(Activity host, PluginEntry entry, String pageId, int requestCode, Failure failure)` → 显式`startActivityForResult(verifiedComponent, ticket)`，插件`UsageStatsActivity.onCreate`接收 | **修改插件自身Activity的呈现**，Android在插件进程创建View；不能返回View给宿主，也不能拿到宿主Activity/Context。主题、locale、fontScale、reduceMotion仅为值参数 | 组件必须属于已验证插件包，exported、标准Activity栈；不接受prepare返回的新Intent或任意URI。插件崩溃使外部页面失败，宿主原入口仍可返回并重试。入口校验宿主最终决定 |
| `oneway IPluginEndpoint.closeSession(String leaseId,int reason)`；停用、替换、卸载、宿主结束页面后发best-effort通知 | **观察**撤销并协作关闭外部页/释放UI，不得阻止停用或清空宿主数据 | **先在宿主撤销数据授权/会话/入口，再通知插件**。超时或插件忽略都不影响撤销；不能承诺强制关闭外部Activity、删除其缓存或收回已交付数据 |

首版没有onBoot、shouldFreeze、beforeCheckin、学习完成或任意事件总线。只保留binder死亡/包变化/页面会话等实际必需的宿主订阅，停用注销相应ServiceConnection/DeathRecipient/待交付callback；系统包变化接收器属于宿主全局目录维护，不能随任一插件关闭而取消。多个插件只能追加统计类入口、各取自己的会话，不能观察/修改/阻止核心业务；规则优先级仍由Guard原实现掌握。

## A5. 首插件、装载、独立更新

### A5.1 目录与制品

```text
plugin-api/aidl/com/afs/pluginapi/    # 两端共同版本化的AIDL、只含固定字段的Parcelable
plugin-api/java/com/afs/pluginapi/    # SDK导航与会话封装、状态码；不依赖宿主Store
plugins/usage-stats/                 # 独立Manifest、Java/View、主题/翻译资源和独立build.sh
  package com.afs.plugin.usagestats  # UsageStatsActivity + PluginEndpointService
dpc/java/com/afs/host/plugins/       # Package discovery/identity/grants/session/router/IPC service
dpc/java/com/afs/host/usage/         # 原collect与PcStats聚合适配，仅宿主有系统统计权限
dpc/AndroidManifest.xml             # 宿主签名级permission、IPC服务/授权页、发现queries
```

使用当前aidl.exe生成两端Java Stub；同一API源分别编译进两只APK，不把宿主classes.jar打进插件，不共享Ui/T具体实现。插件自带小型呈现和翻译；宿主只传主题token、语言/字体倍率/减弱动态等值，不传资源对象或Context，保留原四组统计与返回体验。使用原Java工具链分别生成Host APK和`afs-plugin-usage-stats.apk`，输出目录分离。

`StatsActivity`改为宿主兼容路由壳，HistoryActivity取目录入口，GuardClock仅建立轻量目录/宿主IPC基础；Guard、GuardService、业务Store、AlarmReceiver/BootReceiver不导入插件API，也不绑定插件服务。宿主核心完全不引用UsageStatsActivity/PluginEndpointService具体Java类，只使用已验证安装清单里的ComponentName；不需要BuiltinContributions或具体插件factory表。

### A5.2 首插件全生命周期

1. **声明**：插件清单有一个发现服务action=`com.afs.guard.PLUGIN_V1`，元数据含id=usage-stats、protocolMin=1/max=1、插件versionCode、两项capability、HISTORY_MORE/pageId=screen及本包Activity。声明数据限制8KiB、每插件最多4页，仅允许已知slot/capabilities；与APK包名/版本、解析组件严格一致。
2. **安装/发现**：用户通过系统安装器独立安装官方签名插件APK。宿主在插件管理onResume、包新增/替换/删除时按action查询，校验包和证书/组件/协议；Android11+用对应`queries`声明。不为每个新插件增加宿主硬编码白名单。有效但未授权状态为DISCOVERED_DISABLED。
3. **授信与启用**：设置 → 插件管理显示发布者、版本与实际数据范围；用户首次确认启用并授予read/request两项可独立撤销的grant。宿主和插件两个APK均无sharedUserId；运行时检查UID与包一致，拒绝共享UID。首版信任根仅官方发行证书，非任意第三方。新外部App不能静默继承原内置统计的数据权限。
4. **出现入口**：启用后History刷新目录，出现一次屏幕使用统计。HostPluginRouter重验→preparePage→READY→宿主签一次ticket→用户前台启动插件Activity（无NEW_TASK、不把异步Binder回调当后台拉Activity授权）。宿主Activity若已不可见，则取消这次启动，回来后让用户重试。
5. **使用**：插件Activity校验宿主来源/显式绑定HostStatsBridge→openSession兑换→loadCurrentUsageSummary→宿主聚合→callback主线程展示四组图表。电脑tabs只切已有DTO。无系统统计权限时插件展示缺权提示；用户主动从插件去宿主授权页，再从宿主页进入系统设置，返回后重查；不把设备所有者视为使用情况权限。
6. **正常离开**：插件停止绘图、取消请求；最终退出close并解绑。宿主代理页的Android返回栈回到原历史位置。插件直接从外部启动或无ticket时只能显示“请从宿主打开”，不能查询。旋转、语言/主题变化及授权往返在会话有效期内可恢复；进程死亡、lease超时则从宿主重新开，不缓存宿主凭据。
7. **停用**：先保存disabled并增加授权代次，撤目录/lease/ticket/未交付数据，再best-effort closeSession并解绑。旧Intent或仍开着的插件页不能再从宿主取数据；诚实插件显示停用并关闭。宿主不能保证恶意/故障插件窗口真的关闭或抹去它已收到的数据。宿主重启仍disabled；所有有效规则不动。
8. **独立更新**：只升级插件APK，宿主APK与宿主versionCode/hash不变。PACKAGE_REPLACED以及每次路由/绑定/数据事务都校验安装指纹（包、UID、签名、versionCode、lastUpdateTime），旧会话全作废。签名及能力集合不变、协议仍兼容时保留用户enabled/grants，重建入口；新增capability先等用户确认新增授权，不能静默继承。兼容只改布局/图表即可立即体现，不重装宿主。故障状态按安装指纹隔离，旧版本的迟到死亡/超时不能把新版本错误标记为FAILED。
9. **不兼容/失败**：protocol不交叉则INCOMPATIBLE、不绑定不执行；init失败/prepare超时/插件崩溃进入FAILED，宿主错误页可返回。一次故障隔离当前安装版本，不循环重启；用户明确重试或换兼容版本才恢复。所有限制独立继续，不能将失败解释为空规则。
10. **卸载/重装**：替换广播REMOVED且replacing=true不当作彻底卸载；真正卸载立即撤会话与入口、删除该包grants，管理页可留无数据的历史诊断。重装即使同包同证书也重新由用户启用授权；旧UID/ticket不得恢复。HOST进程重启丢弃全部内存lease，目录根据安装包和持久元数据重建，不依赖广播一定送达。

`afs_plugins_v1`只保存用户enabled/grants、已确认能力集合、安装指纹、故障状态；业务源数据仍在原Store，不把插件状态并入规则表。坏插件元数据只禁该项，不从Application抛异常阻断Guard/LocalBackup启动。首版不做插件商店、自动下载、云端市场、未知证书信任UI、第三方规则脚本或热更新宿主自身。

## A6. 独立交付与真实安全限制

| 模式 | 是否满足独立安装更新 | 隔离与结论 |
| --- | --- | --- |
| **独立APK + 不同UID + Binder + 插件自有Activity** | **满足：兼容协议与现有能力范围内，安装/升级插件不用重装宿主；宿主首次增加协议服务需升级一次** | R2选定。OS隔离私有文件与进程；宿主拒绝未授予数据。插件崩溃不在Guard进程执行；不等于绝对无资源争用或能收回已给数据 |
| 同APK内置Java插件 | 不满足独立代码更新 | 原推荐撤销，不再作为第一版交付，也不以“以后再外置”替代本次要求 |
| 外部DEX/JAR加载进宿主 | 可独立换代码但无独立UID隔离 | 明确不采用，不能让高权限宿主执行插件代码 |

首版宿主声明签名级`com.afs.guard.permission.PLUGIN_STATS_V1`保护HostStatsBridge/授权页；插件请求该permission，插件Endpoint/Activity也受同一签名permission保护，宿主声明使用该permission。双方还必须在每个Binder事务内校验真实UID、安装身份、lease与能力，而不是只判断签名即可访问全部数据。API24用PackageManager签名查询，API28+按SigningInfo校验；初版只接受预置当前官方发行证书，不自动把任意签名轮换链当作新信任根，换根必须另审。用户给插件授权不能越过签名不可信。

独立插件清单不声明INTERNET、QUERY_ALL_PACKAGES、PACKAGE_USAGE_STATS、设备管理、无障碍、VPN或开机自启权限。系统可以据此限制其直接联网/读取宿主私有数据，宿主还应检查实际声明，拒绝权限超出首版许可的包。即便签名可信，也不共享UID，不给插件Device Owner。**这不是完整数据防泄漏系统**：已交付的名称/时长能被插件存入自己的目录、截图或经允许的系统交互转交；用户停用不能收回。SDK禁止缓存原始统计/主动后台工作是官方插件的代码规范，OS只强制UID/权限边界，不保证它自动遵守生命周期或删除已有数据。

Binder服务在UI主线程外执行有界工作，先验证再派发，不持Guard锁。AIDL端点在解析复杂payload之前拒绝超过64KiB事务，DTO集合/字符串二次校验；每UID限速、有限队列、unlinkToDeath/解绑/回调清理。无界字符串数组、反序列化对象或恶意Binder洪泛仍需专项压力测试，不能用一个catch(Throwable)宣称宿主绝不崩溃。

宿主能撤销查询与入口，不能强制finish外部Activity或杀第三方进程来模拟清理；prepare超时只停止等待，不承诺远程线程已停止。核心执行与插件没有调用依赖，因此停用、崩溃、卸载不应改变规则内容或判定；设备/宿主自身被杀、OS/OEM限制、权限撤销的既有风险仍在，必须用真实系统暂停与边界重算验证，不保证“永不中断”。

依据：[Android应用沙箱](https://source.android.google.cn/docs/security/app-sandbox?hl=en)、[AIDL跨进程线程/oneway语义](https://developer.android.com/develop/background-work/services/aidl)、[Binder调用UID](https://developer.android.com/reference/android/os/Binder)、[Activity.getCallingPackage与结果式启动](https://developer.android.com/reference/android/app/Activity)。这些平台机制不等于SDK数据使用约定也能被系统强制执行。

## A7. 审批状态与待决定项（R2）

| 编号 | 当前状态/推荐 | 选择改变什么 |
| --- | --- | --- |
| D1 独立装载 | **用户已决定必须独立安装更新；R2采用外部APK+IPC，自有Activity，不采用内置过渡版。R2及推荐范围已获用户批准** | 不再要求每次插件更新重装宿主；增加双APK构建、身份/会话/卸载/独立更新验证 |
| D2 精简速度 | 已批准：首轮迁统计，其余legacy保留，全部插件关闭后其余五页仍存在 | 若首版必须只剩守卫/冻结/设置，需要追加全部业务迁移；本轮不能用删除页面冒充插件化 |
| D3 安装身份 | 已批准：宿主保留com.afs.guard及发行签名，插件单独包com.afs.plugin.usagestats、不同UID | 新宿主包并存不能继承原DO及私有数据，需要另做部署迁移；本轮不做 |
| D4 信任与初次启用 | **已批准：仅官方证书插件；外部APK安装后默认未启用，首次由用户确认数据范围** | 此项不同于原“内置默认启用”；不能因D1已选就宣称用户同意静默给另一App统计数据。首次迁移未安装插件时给设置中的安装/启用提示，旧数据保留 |

只读统计首插件、上述两个数据/授权能力及会话传输为本轮范围。未来新能力/第三方签名/规则提供器均需另批。用户已明确答复“批准 R2，按推荐范围实施”，第二阶段获准，P6仍需实际审查与复验。

---

# 原有动效与功能约定（保留原文，不属于本插件阶段的实施批准）

# 手机端动效优化：当前架构与设计约定

## 当前增量设计：学习页面休眠与底栏性能（2026-11-02，用户授权直接修改）

本节为设计目标；本次已实现休眠、无变化首页复用、轻量进度查询与重复计算消除。首次/数据失效时的后台快照准备尚未实现，不能把本节所有条目视为完成。旧性能记录不代表当前安装版本。用户要求：记忆页、刷题页未激活时休眠，解决底栏切换卡顿。维持现有 Java/View、多 Activity 架构。

- 页面采用前台激活、后台休眠、销毁三态；只有前台允许刷新视图、启动页面动画、公式渲染和图片解码。首次进入才加载学习页面，不从其他底栏页面预热整页。
- 休眠保留必要页面状态；取消页面定时回调，暂停当前页面 WebView（不得用影响全进程的 pauseTimers），取消或作废图片加载结果。恢复时检查数据版本、日期、主题与路由，只在失效时重建。底栏进入首页、编辑离开确认、草稿保存失败等现有语义保持。
- 已接受的保存、评分、导入、恢复等持久化事务必须完整结束；休眠期间不弹结果窗口、不重建后台页面。用生命周期代次与请求身份隔离迟到结果，返回时只呈现当前有效结果。休眠不是禁用守卫或学习完成判断。
- UI 数据准备采用有界后台任务与不可变展示快照；不得把缓存中的可变 JSON 直接交给 UI 共享。数据变更、恢复导入及跨日必须使快照失效；恢复时不重复提交评分或完成记录。
- 打卡学习联动应读取轻量且最新的进度摘要，避免为一个计数复制整个记忆库或题库。保持学习队列、阈值、奖励及守卫语义；任何无法证明等价的数据改动须缩小实现范围。
- 消除打卡刷新与渲染链中的重复卡组计算。底栏重复创建、独立玻璃窗口的成本以采样确认；本批不迁移单 Activity，也不直接删除玻璃外观来声称完成优化。
- 性能验收以相同设备、数据、刷新率、动画设置下的切页延迟及长帧为准；后台无页面主动工作必须单独验证。构建通过不等于性能通过。


日期：2026-10-29。状态：**M0–M6 已实现、审查、回归并合并；同条件实机及局部诊断结果见HANDOFF.md；M7–M8 尚待批准实施，整体目标未验收**。本文件与根目录 `TASKS.md` 是本次动画任务的有效交接；笔记模块已有文档继续约束笔记业务，不被本文件替代。

## 1. 目标、基线与协作

目标是核心交互流畅、自然、美观且一致，操作不等待装饰动画；不改变目标、打卡、历史、记忆、题库、奖励、总结、冻结、同步及本地备份的业务规则或数据结构。只修改手机端必要代码，不涉及 PC。

设计审计后，用户已明确批准第一批实施。当前结果、包身份、修改范围、验证证据和限制见根目录 `HANDOFF.md`。实体手机仅进行页面操作、保留数据的覆盖安装及临时采样设置；未提交测试打卡、评分、奖励或冻结规则。

- 审计工作树 HEAD：`f5e277ed22a3a93e08aa703779df0835fadeb419`；存在大量未提交及未跟踪文件，HEAD 不等于当前源码。初读默认 `1.56.1 / 2178`，准备文档期间并行修改已更新为 `1.56.2 / 2179`，不能套用旧版发布树。
- Java/XML/AGSL 文件的 SHA-256 及工作区状态保存在 `.codex-fix/motion-audit/review-20261028/source-manifest.json` 和 `working-tree-status.txt`；最初 129 文件清单保留为 `source-manifest.initial.json`。这些是静态审计时点，实施前须重新确认差异并冻结独立快照。
- 文档准备期间发现并行修改：记忆卡光带已移除；新增下滑揭晓/消散和 `PageScrollState` 滚动恢复。已按新源码复核相关条目，不回退这些行为，也不将其算作本任务已完成的优化。
- Astra 设计审计实际使用 `gpt-6-astra` 子代理，重点检查导航、手势、底栏和生命周期；主代理审计启动、卡片、列表、图片、排版、弹窗和加载并整理文档。
- DSH 已实际尝试派发，返回 “DSH was not found. Set DSH_DESKTOP_ROOT”，未生成 job ID。旧 CLI 路径不存在；已向用户说明由 Codex 承担本批实现与修正，Astra 实际独立审查。不能声称 DS 已执行。
- 流程：用户审阅两份文档 → 基线采集 → DS 实现获准范围 → Astra 独立审查 → DS 修正 → 主代理验收。模型或执行器不可用时明确报告，不冒称对应阶段已执行。

## 2. 已确认的技术栈

| 层 | 当前实现 | 本轮约束 |
| --- | --- | --- |
| 构建 | `dpc/build.sh`，JDK 17，Java 8 编译目标，aapt2/d8/zipalign/apksigner；minSdk 24、targetSdk 34 | 不换构建体系或引入重型依赖 |
| 页面 | 原生 Java、多 Activity；大量程序创建的 ScrollView/LinearLayout/FrameLayout 和自绘 View | 保留 Activity、路由、返回栈和页面顺序；不迁移 Compose/Fragment/ViewPager |
| 页面及手势 | `FloatingNav.wrap`、`SwipeNavHost`；通过 `FLAG_ACTIVITY_REORDER_TO_FRONT` 复用 Activity | 冷创建和热复用都必须有明确的视觉开始/完成/取消状态 |
| 底栏 | `FloatingNav` + `LiveNavWindow` 独立无焦点 Dialog；`DockSpring` + Choreographer | 保留跟手与子控件手势优先权，不修改导航阈值和现有弹簧积分数学 |
| 玻璃 | API 33+ RuntimeShader/RenderEffect；支持设备上独立底栏窗口模糊，低版本回退着色背景 | 保留每 Activity 独立可变 shader；不得把效果重新挂到正文 |
| 通用动效 | ViewPropertyAnimator、ValueAnimator、部分 XML 动画、自绘定时重绘 | 优先复用，统一策略及生命周期管理 |
| 弹窗及反馈 | `ThemeStore.dialog` 原生 AlertDialog，部分 PopupWindow/ProgressDialog；按钮反馈实现不统一 | 不添加新的业务确认步骤，不用延迟点击或夸张缩放代替即时反馈 |
| 数据 | 现有 AtomicFile/JSON、SharedPreferences 和各模块 Store | 不改 schema、字段、文件名、存储与同步语义；不把 UI 动画改动扩散到 Guard/Store |

`Hub.java` 是 Gist 同步与合并模块，不是导航组件。本轮不改它。`res/anim` 的 170/240ms XML 动画目前未发现 Java 导航引用；只修改这些 XML 不能证明改到了实际切页路径。

## 3. 当前动效分布

| 场景 | 当前行为 | 审计结论 |
| --- | --- | --- |
| 底栏切页 | 点击先绘制半分辨率整页快照；新内容短移，快照在上层移动；slide/fade 总预算 240/180ms | 关键路径与截图所有权需要优先处理 |
| 底栏按压 | 多个解析弹簧、选中态和玻璃折射 | 保留现有按需帧调度；检查多指、打断和生命周期 |
| 首页启动 | 640ms 模拟进度、无限旋转，再交接 230ms 内容入场及 220ms 遮罩退场 | 动画计时不代表真实就绪，未统一减少动态效果策略 |
| 打卡卡片 | 跟手位移、3D 倾斜、170–240ms 换卡/完成；2800ms 无限阴影呼吸 | 纯视觉与业务回调耦合，部分路径缺少统一 motion 判断 |
| 记忆卡片 | 评分先保存；下滑先揭晓，揭晓后下滑 200ms 消散/上滑 140ms 飞出、回位 120ms；推进仍定时 140ms | 保留新手势语义，检查消散与推进不同步；StudyCardSurface 光带已在并行修改中移除 |
| 冻结页 | 拖拽抬升、落位 overshoot、应用库 210ms 高度展开 | 高度动画逐帧布局；目录已有后台查询/可视范围创建，不能当成未优化状态重做 |
| 笔记本分类 | 220ms 胶囊移动、逐帧改变宽度并 smoothScroll | 缺少统一 motion 策略；宽度变化会触发布局 |
| 列表/历史 | 笔记筛选 removeAllViews 后全量建卡；历史创建日期图表；全局布局后遍历排版 | 构建、测量与动画开销必须分开归因，不能仅改插值器 |
| 冻结状态 | FrostView 在可绘制时持续刷新薄霜/粒子；旧 AppList 仍有呼吸动画 | 需要统一静态降级与可见生命周期，不移除冻结原因和文字状态 |
| 弹窗、按钮、加载 | 系统弹窗动画、原生 ripple 与普通圆角背景并存；后台 I/O 有模态进度/仅 busy 状态等不同反馈 | 后续批次统一即时反馈，不改确认、取消或任务本身 |

## 4. 第一批推荐方案

### 4.1 统一动效策略，不新增持久化字段

新增轻量 `MotionPolicy`（名称可在交接中等价调整），读取既有 `afs_ui/reduce_motion`、主题 slide/fade 选择及系统动画设置。调用方不再各自复制 `Build.VERSION.SDK_INT < 26 || areAnimatorsEnabled()`。

- API 24/25 也检查系统动画关闭；API 26+ 使用平台能力并按类别处理属性动画、切页和窗口动画设置。
- 既有 ValueAnimator/ViewPropertyAnimator 已受系统倍率影响，不能再次乘倍率；手动 Choreographer/自绘循环须正确处理关闭和非 1x 倍率。
- 减少动态效果或对应系统倍率为 0：直接最终状态；无位移、弹性、装饰循环、假进度、延迟 reveal 或截图。选中、完成、错误文字和必要触觉反馈仍即时可用。
- 通过前台生命周期/设置变化刷新策略；不在每个绘制帧解析 JSON 或反复读取全部偏好。
- 有效窗口/页面不可见时停止拥有的动画和回调；恢复可见时以当前状态恢复，不能重放已经完成的动作。

### 4.2 导航只保留目标内容短距入场

推荐移除整页旧截图路径及 overlay，避免点击前 `Bitmap.createBitmap + root.draw`。统一点击与页面滑动的导航请求，记录单调递增 token、from/to、动作时刻；视觉不能决定是否执行路由。

- 保留既有 slide/fade 配置和 **1x 下从动作时刻计算的 240/180ms 总预算及 80ms 剩余门槛**；冷创建耗尽预算时直接到位，不补播。非 1x 遵循系统倍率，不承诺仍以 240/180ms 墙钟时间封顶；不得重复缩放或自行猜测另一套封顶规则。
- slide 只让目标内容做约 16–24dp 的短移；fade 做轻量透明度恢复。最终位移为 0、alpha 为 1，触控不等待动画完成。
- 新合法导航请求先结束旧视觉，再开始新目标；不通过加长 160/180/250ms 锁来掩盖重叠。重复点击同页不新建过渡。
- 热复用、返回、旋转/重建、后台恢复须显式消费或结束对应 token，不能依赖 `wrap()` 恰好重建。
- 第二指加入底栏手势时推荐取消该次手势并回到 selected；不引入新的多指导航。保留页面滑动既有阈值，短距离高速 fling 的规则修订不在本批内。
- 继续保留底栏独立窗口与玻璃档位；只有 trace 证明它们仍是瓶颈时再做局部优化，不更换视觉架构。

### 4.3 启动、卡片与取消语义

首页内容就绪即展示，取消固定 640ms 假加载等待及额外缩放交接；真实异步操作仍用既有结果驱动状态。底栏 reveal 与内容共用当前可见状态，不独立排一个过期计时器。

记忆光带/闪点已由并行修改移除，保持当前静态卡面，不再安排重复修复。第一批取消打卡悬浮阴影等常驻装饰循环，冻结薄霜在正常模式和减少动态效果模式均使用静态弱提示；保留清晰层次、手势反馈和业务状态文字。

打卡、热爱、禁忌、总结和记忆卡的完成必须分清三件事：手势尚未接受、业务已接受/保存、纯视觉已结束。

- 取消未达到现有提交条件的手势：只回位，零业务提交。
- 已接受的普通打卡：既有 `record` 成功路径只能执行一次；不能因取消 animator 而吞掉它，也不能从多个 end/cancel 回调重复执行。持久化失败不得表现为成功。
- 已保存的记忆评分/热爱奖励/禁忌记录：视觉打断不再次记账、不撤销或改写结果；保留现有联动和跳转条件。
- 保留最新记忆“下滑揭晓；揭晓后下滑忘记并消散、上滑记住”的行为及现有可选评分。200ms 消散与 140ms 定时推进不同步，应改成受 token 保护的视觉完成/取消协议，不能通过改变评分或揭晓条件解决。
- `ThemedActivity` 新增的 `PageScrollState` 捕获/恢复必须保留；导航取消与恢复不能覆盖其滚动位置或引入重复预绘制回调。
- 为每轮视觉保存 token/视图身份；销毁或旧结果到达时只清理自己的 View，不操作新一轮卡片。
- 不修改每日数量、评分、奖励、冻结限制、队列顺序及 schema。若实现需要移动业务提交边界，DS 必须单列差异交 Astra 审查，不能当成动画小改隐含带入。

## 5. 统一动效规格草案

以下是设计时的 **1x 基线参数**；M0–M4已获用户批准并按范围落地，其余项仍待审阅实施，参数本身不是性能实测结果。保持页面切换当前预算；系统倍率非 1x 时按平台行为验收，不能二次乘倍率。其他值以基线验证后的小范围调整为准。

| 类别 | 时长/缓动 | 位移、透明度与触发 |
| --- | --- | --- |
| 页面 slide | 默认约 180–220ms，1x 动作预算 240ms；保留现有 PathInterpolator(.2,0,0,1) | X 16–24dp → 0；必要时 alpha .9 → 1；点击/滑动同路径 |
| 页面 fade | 140–180ms，最大预算 180ms；减速 | alpha .85 → 1；不做整页缩放 |
| 底栏按压 | 按下即反馈；离手约 100–160ms 收敛 | 保留受控的现有弹簧，不增加大幅弹跳；选中逻辑即时 |
| 卡片拖动/取消 | 拖动无延迟；回位 120–160ms 减速 | 沿现有手势位移，取消恢复所有变换；不变提交阈值 |
| 卡片完成 | 140–200ms 减速；不以装饰延迟业务响应 | 保留既有飞出/消散语义；收敛 3D 翻转和二次入场叠加，不启用碎片；不清理无调用的旧方法 |
| 分类胶囊 | 160–180ms 减速 | 平移/缩放绘制，避免逐帧 LayoutParams；滚动与选中同步 |
| 展开/折叠 | 160–200ms 减速，先测量后展示 | 仅必要布局变化；若高度逐帧布局显著则改裁剪/位移呈现，最终尺寸语义不变 |
| 弹窗 | 优先平台原生反馈；若统一窗口动画仅一层 120–180ms | 轻淡入/短移，无二次 content 动画；关闭立即可取消 |
| 按钮 | 原生 ripple/pressed 状态即时开始 | 不延迟 OnClick、不统一强加 scale；禁用/错误/焦点状态可辨 |
| 加载/状态 | 工作立即开始，结果就绪立即更新 | 极短工作不闪遮罩；约 120ms 后仍未完成可显示真实忙碌提示，无最短展示等待 |

## 6. 后续批次仍属于完整目标

第一批先修影响所有页面的路径及最显著的持续动效。其后继续检查并修复：笔记胶囊/冻结应用库的逐帧布局、全局与弹窗重复排版、图片解码与图层、笔记长列表/历史图表构建，以及按钮、弹窗、加载和状态更新的一致性。后续项目不能因为第一批通过而从验收范围删除。

资源加载和全量列表构建先用 trace 定位；可以使用已有后台加载、代次标识、局部更新和可视范围策略，不默认上 RecyclerView 或迁移整体页面。

## 7. 性能证据与完成标准

- `docs/PERF.md` 的 1.49.x 数字、`RENDERING-OPTIMIZATION.md` 的 1.52.x 截图是历史背景，不是当前工作树的性能证明。
- 设计阶段只读设备审计当时为 PLR110/API36、1.56.1、约60Hz；这不是实施后的对比证据。实施期间版本和实际刷新率发生变化，最终使用同版本1.57.1成对包、同路径及逐步核实的实际90Hz；证据见 `HANDOFF.md`。系统普通60Hz偏好被OEM覆盖，不能把偏好值当实际刷新率。
- 版本名相同不证明安装包与 dirty 源码一致。获准后先冻结 baseline 源码/包/测试数据/签名及设备环境，再实现。不得拿模拟器 1.56.0 与手机 1.56.1 比较为优化前后。
- 同设备、同刷新率、同路径分别记录冷创建、热切换、长列表、弹窗、快速连点、取消/返回、后台恢复；每组至少 3 轮，报告样本数、耗时和长帧绝对数量及比例。减少动画帧数时不能仅以总体 jank 百分比论胜负。
- 帧预算按采样时实际刷新率计算：60Hz 16.67ms、90Hz 11.11ms、120Hz 8.33ms。自适应刷新率若未锁定或无法证明相同，必须分组或明确不可比，不能用屏幕最高刷新率代替当前刷新率。
- 记录 gfxinfo framestats；可用时用 Perfetto/FrameTimeline 分辨主线程、RenderThread、GPU 和布局/解码。API 24 使用可用的 gfxinfo/atrace，不伪造新系统专属指标。测不出时明确缺口。
- UI 录像与性能采样分开或采用两侧完全相同的录像配置，说明录像开销和帧率限制；录屏本身不证明真实刷新帧率。
- 只有完成获准实现、Astra 审查、DS 修正、业务回归和同条件性能复测后，才给最终优化结论。当前整个目标保持进行中。


## 8. 已落实的反馈生命周期与后续显示统计边界

M7：平台弹窗只在decor attach时套主题，保留调用方OnShowListener；按钮pressed/focused/disabled即时状态不引入动画等待或业务锁。Memory已接受IO继续执行，页面owner/代次只限制UI；正常结束仅清进度，换页/销毁清所属源弹窗。Memo AI关闭提示只使UI结果失效，不能声称取消网络，仍需发送确认及结果预览。Data已确认恢复的queue/restart不受旧页面销毁影响，调度失败保留排队档案并释放当前busy。

M9已获用户明确批准：后台计算首页显示用冻结数量，进程内单worker和有界最新请求，应用context＋弱页面owner＋UI代次；不移动参与runApply的allBlocked，不改变Guard频率、业务Store或数据结构。已有统计调用可能触发既有迁移，实施前须复核同步与锁顺序；不宣称它是纯读函数。细项及验收见TASKS第8节，已获用户批准，按该范围实施与验收。

M9生命周期规则：页面暂停/销毁递增epoch并取消该owner的pending；普通刷新仅增加请求sequence。同一epoch内，完成结果序号大于已发布序号即可显示，再执行最新pending，避免慢任务被1秒tick永久淘汰。计数与错误只影响显示，不进入冻结决策。


M10已获用户批准，当前在隔离快照实施：只在Quiz bank真正恢复旧位置时保护旧搜索框的自动caret滚动；新的用户输入、选择、可访问性、布局和IME变化必须正常接管。PageScrollState若增加通知接口，只通知支持者，普通滚动容器不变；不得反复scrollTo压住用户，不改业务或Store。具体门槛见TASKS第9节，已获用户批准，按限定范围实施与验证。
