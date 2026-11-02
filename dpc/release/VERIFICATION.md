# v1.79.2 verification · 2026-11-02

- 完整离线回归通过，包括Appearance 33项、DailyQuota 67项、DayBoundary 20项与翻译表5105项。
- API34模拟器：冻结交互15项、快照与缓存20项、底栏切换18项、提醒弹窗13项、风格移除／配置回退6项通过。提醒专项覆盖关闭通知权限后的后台弹出、重复请求复用与关闭返回。
- 旧版AppearanceDeviceTest仍依赖固定中文标题及现已隐藏的页眉，未适配当前界面而失败；删除风格验证采用新的RemovedSkinDeviceTest，不把过时整页测试计为通过。
- PLR110／Android16已保留数据安装开发签名1.79.2 /2254。正式发行APK使用固定发行证书，与手机当前开发签名不同，不能直接覆盖，不能把该真机结果声称为正式签名新装验证。
- 正式主APK版本2254、维护APK版本2255；打包工具验证身份／证书／Android最低版本24、非调试构建、维护接收器隔离及DUMP权限、凭据扫描和允许清单。Windows包包含图形向导、ADB和恢复工具。
- 最新真机热切页样本95分位53→57ms，未显示整页提速；保留五Activity架构，不保证绝对零卡顿，尚无长期耗电对照。

以下为历史验证记录，结果只代表相应版本。

# v1.56.0 verification

Validated on 2026-10-28, Windows, JDK 17, SDK build-tools 34.0.0 / platform 34, and an Android 7.0 API 24 AOSP x86_64 emulator. Reference emulator results do not certify every vendor ROM.

## Offline regression

207 core assertions and all additional suites passed, including 180 card reward / cycle checks, 20 memo block checks, and 17 local archive checks.

## Android 7 device checks

- Local data device: 11 checks passed
- API smoke device: 12 screens passed
- Freeze device: 10 checks passed
- Sync config device: 15 checks passed
- Learning link device: 50 checks passed
- Memo whole device: 15 checks passed
- Memo checklist device: 127 checks passed
- Quiz import device: 13 checks passed
- Toolkit device: 17 checks passed
- Usability device: 38 checks passed
- Freeze device: 10 checks passed

- Windows deployment bundle: same-signature upgrade, recovery with original data preserved, removal after administrator cleanup, and fresh Device Owner provisioning all passed using Windows PowerShell 5.1 and the bundle's ADB.
- Recovery before/after instrumentation confirmed that managed suspension and restrictions were cleared while study data and a marker survived. The maintenance APK continued enforcing normal freeze policy until recovery was explicitly requested.
- Both APK identities, minimum API 24, fixed release certificate, and production/maintenance receiver isolation were checked by the packaging tool. No testOnly or debuggable manifest flag is permitted. Only the maintenance receiver accepts recovery, protected by android.permission.DUMP.
- The archive allowlist excludes signing keys, credentials, generated builds and local configuration. APK and source scans reject token patterns and the former personal sync target.

## Test compatibility and limits

The device runners accept external signing through AFS_KEYSTORE / AFS_KEY_ALIAS / AFS_STORE_PASS. Android 7 tests use touchscreen input events, bounded dialog waits, legacy keyboard geometry and matching blocking ActivityMonitors. For API 24/25, share / picker interception verifies the intent action without reading the newer monitor callback; the multi-file import itself is checked using actual XLSX plus CSV preview and cancellation. On newer Android versions the callback also checks intent payload and multi-selection flags.

WebDAV, GitHub private repositories and Git currently store configuration only. No remote transmission, private-repository permission check, live AI request or personal Gist synchronization was exercised. Full local restore pauses legacy Gist synchronization. No physical phone or other emulator was modified by this release verification.
