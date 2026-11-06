# 手机独立 APK 插件交接

2026-11-04：R2 最小范围已集成至根工作区，正式签名本地候选宿主1.90.0/2277与统计插件1.0.0/1已生成。实际结果及边界见 [验证记录](docs/PLUGIN-R2-VALIDATION.md)。尚未Git提交、上传或实体手机安装。

用户批准独立APK+IPC、仅官方签名、首次手动授权、先迁统计其余legacy。DS初稿因余额不足中止，后由Codex接手修正，Astra独立评审；不能署为DS完成。

## 改动与依赖

- 新 `plugin-api/`：协议1 AIDL、固定DTO、安全Parcel边界、客户端生命周期。
- 新 `dpc/java/com/afs/host/plugins/`：注册、证书/UID、清单与权限、持久启用、票据/会话、撤销与失败隔离。
- 新 `dpc/java/com/afs/host/usage/`：在宿主将现有统计聚合为有界只读快照。
- 新 `plugins/usage-stats/`：独立UID的Activity/Service与独立构建，不加载外部DEX、不依赖宿主实现jar。
- Manifest、构建/测试脚本、HistoryActivity、SettingsActivity、StatsActivity、LegacyStatsActivity、翻译及专项测试接入。历史页只刷新插件入口，设置返回只更新插件摘要，原Store和限制判定保留。
- Guard/GuardService/启动闹钟不调用插件；记忆和普通打卡的数据流程保持独立。插件无规则写入、事件否决、任意数据或网络代理能力。

## 源码与并行任务

选择性合并记录：`.codex-fix/plugin-r2-root-before-merge/integration.json`，被修改原文件备份保留。没有reset其他任务的dirty文件。原本根工作区1.89.2/2276合并后本候选1.90.0/2277；冻结构建源 `.codex-fix/plugin-r2-final/`。其后其他任务对根目录记忆等修改未进入本候选，不宣称被本轮验证。

最终源清单与APK校验绑定在 `.codex-fix/plugin-r2-review/final-source-delivery.json`。早期impl、integrated-c19f、strict-dev与旧official目录仅作历史证据；可交付目录仅 `dist/plugins-1.90.0/`，不能混入旧包/fixture/测试信任/QA或密钥。

## 运行与审查

最终同一宿主APK在Android7/14官方签名完整矩阵通过，独立更新时宿主哈希不变；停用后重启、不兼容、初始化失败/超时均从实际用户入口验证。离线全套及专项规则结果、旧完整UI失败、模拟边界均逐条列在验证记录。

Astra独立评审已发现并推动修正：跨UID/票据和持久撤销、AIDL尾随及截断字段、历史整页重建丢位置、系统栏对比、标签包名回退、设置异步摘要陈旧。最终审查仍以实际日志和源清单为准，不把批准当运行成功。

## 回退

停用插件立即清入口/后续会话，保留宿主所有规则数据。整体迁移回退由维护者以 `AFS_STATS_LEGACY=1` + 官方签名 + 更高versionCode生成宿主覆盖包。当前没有预编译恢复包；不要降级、卸载、清数据或撤DO。新增API改动应独立审阅，插件同协议同证书现有能力更新无需更新宿主。

Astra最终独立核对完成：本轮范围内无剩余阻断，可交付本地候选。核对结果见验证记录末节。
