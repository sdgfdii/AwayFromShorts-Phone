# AwayFromShorts Android 1.54.0

Android 7.0（API 24）及以上的离线学习与应用冻结工具。冻结使用 Android **设备所有者（Device Owner）** 的系统暂停能力；不需要 Root、Shizuku 或无障碍服务。项目以 MIT 许可证开放，第三方组件保留各自许可证。

[下载完整部署包](https://github.com/sdgfdii/AwayFromShorts-Phone/releases/tag/v1.54.0) · [本版本源码](https://github.com/sdgfdii/AwayFromShorts-Phone/tree/release/android7-1.54.0) · [验证记录](dpc/release/VERIFICATION.md)

## Windows 安装

1. 下载并完整解压 Windows 部署包。手机开启开发者选项和 USB 调试，用可传输数据的线连接电脑，在手机上允许调试。
2. 双击 **开始安装.cmd**。工具会选择设备并检查系统版本、账号、分身、多用户、工作资料和管理状态。
3. 阅读工具列出的管理限制，输入 `DEPLOY`。部署验证通过后，在手机的冻结管理中配置应用与时段，点“立即应用”。

部署包包含 ADB，不需要安装 Java、Python、Android Studio 或 Git。部分机型仍需要厂商 USB 驱动或“允许 USB 安装”。首次配置有 10 分钟编辑宽限；日常使用不需要连接电脑。

有账号或额外用户时，工具会停止并说明原因。请自行检查系统设置；涉及账号或用户数据时先备份。部分厂商系统可能需要在备份后恢复出厂，并在首次开机时跳过账号登录、分身和数据迁移。工具不会移除账号、删除用户、清空数据或自动恢复出厂。

多设备或自动化使用示例：

```powershell
.\deploy.ps1 -Serial YOUR_DEVICE_SERIAL -CheckOnly
.\deploy.ps1 -Serial YOUR_DEVICE_SERIAL -NonInteractive -AcceptDeviceOwner
```

## 冻结标准

- 按每个应用的规则调用系统 `setPackagesSuspended()`，系统负责保持暂停状态。
- 设备所有者限制系统内强停、清数据、卸载、安全模式及恢复出厂。闹钟、常驻前台服务和 Doze 白名单负责边界更新。
- Android 7/8 强制自动时间；规则及学习日界固定使用首次配置时区，改系统时区不会移动放行窗口。主页底部显示这个时区。
- Android 9 及以上使用系统禁止修改日期时间的限制。
- 不同厂商系统仍需实际验证。安装工具只在确认管理状态与加固项生效后报告部署完成。

## 每人独立同步

同步是可选功能；离线目标、打卡、记忆、题库和冻结无需 GitHub 账号。

需要与电脑同步时，在自己的 GitHub 账号创建一个 Gist，先放一个 README 文件即可。打卡页点“同步”，填写用户名、Gist ID 和具有 `gist` 权限的 token。电脑端使用同一个 Gist。应用会验证 token 所属账号与 Gist 所有者一致，再启用同步。

启用后会同步本机目标、打卡台账、冻结配置及使用统计。Gist 的 secret 链接不是端到端加密，请按个人需要决定是否启用。公开包不包含任何人的同步目标或凭据。切换目标会隔离同步缓存和合并基线，保留本机学习记录和冻结规则；同步运行中或冻结期间不允许修改同步配置。

旧版写死的同步地址已移除。已有用户升级后需在此入口重新绑定自己的空间；旧学习数据与规则不清空。

## 升级与签名

正式发行证书 SHA-256：

```text
0c047b4af90f7d980852fa089f5384dea14c39df8f4bcf816adb254e8e74be97
```

同一正式签名的后续版本可覆盖升级，通常无需重新设置设备所有者。部署工具绝不通过卸载来解决签名冲突。

早期仓库曾包含开发私钥。新正式版使用仓库外独立密钥；开发包与正式包签名不同，不能直接覆盖。旧设备请先用兼容旧签名的恢复方式解除管理、导出数据，再考虑迁移。**新维护包不能解除旧开发签名安装的管理。** 不要在仍受管理的旧手机上尝试直接卸载或清数据。

## 恢复管理与卸载

请在安装前保留完整部署包。正式 APK 没有手机端解除管理按钮，也没有 `testOnly` 标记；`dpm remove-active-admin` 不保证可用。

需要退出时，连接并授权电脑，双击 **恢复管理.cmd**。工具会先覆盖安装同签名的维护 APK，再发送仅 ADB shell / 系统级 `DUMP` 权限可用的恢复命令，释放受管应用并解除设备所有者。普通正式 APK 未注册这个恢复接收器。维护包是单独的版本，不供日常使用。

恢复工具默认保留本机数据，确认成功后可先打开应用导出内容，再从系统设置卸载。若明确需要连同数据卸载：

```powershell
.\recover.ps1 -Serial YOUR_DEVICE_SERIAL -Uninstall
```

如果 USB 调试不可用、签名不一致或厂商拒绝管理 API，工具会停止；请保留手机及部署包，不要把失败提示当作管理已经解除。无法使用 ADB 的情况下，需要按厂商支持的恢复流程处理，可能丢失数据。

## 从源码构建

需要 JDK 17、Python 3、Git Bash（Windows）或 Bash（Linux/macOS），以及 Android SDK `build-tools;34.0.0`、`platforms;android-34`。SDK 路径可设为 `AFS_SDK`、`ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。

```bash
bash dpc/build.sh          # 本地开发签名，产物 afs-guard.apk
bash dpc/test/run.sh       # 离线回归
bash dpc/test/deploy-safety.sh
```

开发签名不能覆盖正式安装。正式构建使用 `dpc/release/build-release.ps1` 与仓库外的私有签名配置；私钥和密码不属于源代码或部署包。固定公钥证书位于 `dpc/release/`。第三方分发者应使用自己的发行密钥及公钥指纹，后续升级持续使用同一密钥。

设备测试脚本默认只接受模拟器序列号，包含冻结 API、同步隔离及主要页面巡检。`dpc/libs/README.md` 记录 PDFBox 依赖来源与校验值；字体、KaTeX、FSRS、Material Color Utilities 和其他组件的许可证随源码或 APK assets 保留。

Windows 部署包的 `tools/NOTICE.txt` 保留 ADB 所含开源组件声明；ADB 上游为 Android Open Source Project / Google SDK Platform Tools。
