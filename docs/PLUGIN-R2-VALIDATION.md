# R2 最终验证记录

日期：2026-11-04。本次首迁屏幕使用统计，采用独立 APK + Binder IPC + 独立 Activity/UID。DS 初稿中止后由 Codex 修正，Astra 独立评审。没有发布 GitHub，也没有安装实体手机。

## 可复核制品与源码

- 宿主 1.90.0 / 2277：`CBE4C408C40C444B54893BC8E29F9CA7A91E81199018C3A9B3E0AEFB2872A1C7`。
- 插件 1.0.0 / 1：`991726ABA15C6F507720B130F1AC9571CF0B6E9EC5E5BE79E30AC6CEB666004E`。
- 官方证书 SHA256：`0c047b4af90f7d980852fa089f5384dea14c39df8f4bcf816adb254e8e74be97`；测试信任关闭。
- 本地仅交付 `dist/plugins-1.90.0/` 中两只 APK、说明、manifest 与校验和。测试 fixture、QA APK、密钥和个人配置不在交付目录。
- 构建源：`.codex-fix/plugin-r2-final/`。清单 `final-source-delivery.json` 在 `.codex-fix/plugin-r2-review/`；旧 `final-root-source.json` 保留不改。测试和构建对应冻结副本，不声称覆盖并行任务之后对根目录 MemoryStore、MemoryActivity、LineIcon 等文件的修改。

## 最终 APK 实际运行

| 检查 | Android 7 / API24 | Android 14 / API34 | 证据位置（相对 `.codex-fix/plugin-r2-review/`） |
| --- | --- | --- | --- |
| 官方签名完整插件矩阵 | 通过，normal 197 项 | 通过，normal 196 项 | `final-official-finalqa-device-api24.log`、`final-official-summary-device-api34.log` |
| 真实统计插件 v1→v2 独立安装更新 | 31 项通过 | 31 项通过 | 两日志 real-upgrade；更新全过程设备提取宿主 APK 的 SHA256 不变 |
| 停用后杀进程重启 | 26 项通过 | 26 项通过 | disabled：历史入口消失，旧 Intent 显示未启用提示，数据和冻结保留 |
| 协议不兼容 | 27 项通过 | 27 项通过 | incompatible：不调用插件，入口消失 |
| 初始化失败 / 超时 | 30 / 31 项通过 | 30 / 31 项通过 | 实际历史入口给出反馈，原页面继续使用 |
| 保存失败、成功重试后跨进程撤销 | 42 / 29 项通过 | 42 / 29 项通过 | storage-retry、storage-retry-restart，核对不同 PID |
| 新增插件、卸载重装、旧确认、ID冲突、非信任证书 | 通过 | 通过 | upgrade30、reinstall24、stale-dialog27、collision36、untrusted27 |
| 原规则执行专项 | 最终2277官方APK42项通过 | 旧2275官方候选42项通过 | `final-official-rule-continuity-api24.log`、`official-rule-continuity-api34-observed.log` |

两套正常场景均包含真实历史按钮启动独立页面、截图、返回原历史实例及滚动位置、PC全量total先求和再截top10、协议Parcel往返、独立UID/私有目录拒绝访问、撤授权、客户端进程死亡和设置Home返回刷新。API24第一次因Home后的系统切换延迟超过测试5秒上限失败；只放宽实际焦点/摘要条件等待到15秒后重跑通过，产品APK未改。保留 `final-official-device-api24-first-attempt.log`，没有把超时当成功。Astra建议再明确断言Home后失焦，补充后重新完整初始化fixture矩阵通过normal197。期间一次StageOnly normal在matrix终态collision fixture下执行，新断言通过但后续consent不满足；`final-official-settings-assert-api24.log`保留该失败，不作为正常场景通过证据。

最终副本离线全套通过：`final-snapshot-offline.log`，翻译5597、插件清单21、身份7、边界12、prepare16及原有纯逻辑套件。静态检查未见 Guard/GuardService/Boot/Alarm 引用插件 API 或业务类。插件只依赖公开 `plugin-api`，宿主不依赖 `com.afs.plugin.usagestats` 实现类。

## 原业务数据、冻结与证据范围

- 开发签名旧1.86覆盖升级至2275，API24/34均保留Device Owner和非空原有笔记、目标、普通打卡、独立记忆、冻结对象；各阶段baseline核对8项，合法增长的使用用量单列。该升级证据不是最终官方2277在实体旧安装上的覆盖测试。
- `strict-daily-quota-core-api34-r2.log`、`strict-daily-quota-core-api24.log` 各36项：真实前台事件、全天/绑定时段计量、共享/独立额度、系统DPM暂停与释放、关键应用/奖励不能绕过额度。对应较早2275开发候选，不冒称最终所有旧UI通过。
- RuleContinuity 使用原生产引擎与实际系统DPM，覆盖当前时段放行/冻结、学习联动/关键应用、补完成后恢复、禁忌罚时接各自冻结结束及跨午夜。跨午夜使用显式Calendar边界，没有修改系统时间或等待真实午夜。
- VPN冲突通过持久状态fixture驱动原降级逻辑并验证实际DPM；没有建立真实第二条VPN隧道。无需插件在线即可继续执行宿主规则。

## 必须保留的失败与限制

1. 旧完整额度UI测试断言进度条祖先为 `FreezeTimelineView`，而该类本身是View、进度条为兄弟节点，仍失败。CoreOnly明确跳过该UI段，不能称完整额度UI已通过。
2. 旧完整学习UI在系统动画关闭时不满足完成动画断言；开启后推进至抽奖余额失败，预置冻结会自动抵扣奖励，旧测试未隔离该条件。保留失败，未放宽产品业务规则。
3. 核心旧测试创建衍生 `freeze_slots`，曾导致归档偏差；仅在确认删除这一键能让整个偏好哈希精确恢复后清理自有模拟器fixture，再baseline通过。没有删除真实用户数据。
4. 写盘持续失败时仅当前进程隔离有效；必须重试成功写盘才能保证重启后永久撤销。
5. OS强制：不同UID私有目录隔离、签名permission。协议/数据范围/票据由宿主代码强制检查。插件拿到快照后可保存/传播，宿主不能擦掉外部页面或收回已交付数据；同签名不等于相同UID，也不保证零资源竞争。
6. 仅官方签名、协议1和已声明两项能力。未知能力/协议需宿主升级；当前不是任意第三方插件市场。没有实体手机测试，也没有声称所有Android厂商版本均通过。

## 回退与操作

安装/停用/回退见 [PLUGINS.md](PLUGINS.md)。保持包名签名与数据，整个迁移恢复使用 `AFS_STATS_LEGACY=1` 的更高版本宿主覆盖包；单独停用不会偷开旧统计页。当前没有预编译恢复包，不使用降级、清数据或卸载宿主。

## Astra 最终独立评审

2026-11-04，Astra 实际只读核对最终707项源码清单零差异、交付两APK及校验和与已测哈希一致、API24最新完整矩阵normal197与Home失焦断言、API34完整矩阵和文档边界。结论：本轮范围内无剩余阻断问题，可以交付本地候选包。仅适用于冻结副本和交付APK，不包含实体安装或发布；原完整UI失败、VPN夹具边界、持续写盘及并行晚到改动未覆盖的说明继续有效。
