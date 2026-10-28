# 独立 Android 虚拟测试环境

最新 1.53.0 / 2167：卡管理、热爱奖励与周期总结的 Android 回归 40 项通过，本地新增模型测试 58 项通过。2026-10-27 已覆盖安装到连接的 PLR110 手机，确认六类设置入口及初始奖励余额显示；没有在手机写入测试奖励、提交总结或修改冻结时段。规则、文件与验证边界见 `CARD-MANAGEMENT.md`。此前 1.52.7 的周一重置周报规则已被“逾期总结跨周期补完”取代。

用户要求：停止通过 ADB 调试手机，改用独立虚拟环境。后续默认执行本地回归和下述虚拟设备测试；不自动运行会连接物理手机的 `run-device.ps1`、`run-toolkit-device.ps1` 或手机安装流程。

2026-10-27 后续授权：用户明确说明“连上了就可以用 abd 了”。连接手机后允许使用 ADB；命令始终显式指定设备序列号，避免把虚拟设备与手机混用。已用 `install --no-incremental -r` 将 1.52.6 / 2164 覆盖安装到手机并读取安装版本确认。学习测试数据仅在 `emulator-5580` 使用。

新增 `dpc/test/run-learning-link-device.ps1 -Serial emulator-5580`：脚本拒绝物理设备序列号，测试类再次检查模拟器型号；记忆文件及联动设置事先备份并在 finally 恢复，刷题使用隔离文件，测试包运行后卸载。17 项 Android 检查通过，包含记忆 opt-in、两种学习完成后的跳转/卡片上滑状态、已有打卡 Activity 接收刷题完成 Intent、无重复答题记账及周报入口/弹窗。日志：`.codex-fix/virtual/learning-link-device.log`。

1.52.6 虚拟设备截图确认正文清晰、底栏标签显示完整：`.codex-fix/virtual/1526-main.png`。手机确认记忆工具、刷题联动设置入口可见；没有在手机提交学习评分、普通打卡或修改学习联动开关。

1.52.7 / 2165 更新：用户改为周日手动确认周报。周报卡仅周日出现；在打卡历史的本周周报点击“完成周报”后按周持久化，返回打卡页播放上滑，立即重算守卫。周日 20:00 后未确认时参与学习联动；23:59 不再自动完成，周一零点不再携带周报冻结，下一周需要重新确认。往期周报及跨周过期弹窗不能确认当前周；查看周报本身不改变完成状态。

本地全回归通过，新增学习策略边界共 25 项；Android 联动回归共 28 项通过，覆盖非周日不生成卡片、完成持久化/重复确认/周一重置/下周重新待办/拒绝过期确认，以及目标管理模式后卡片正确恢复。测试使用传入的周日 Calendar 样本，不修改手机或虚拟设备的系统日期。日志：`.codex-fix/virtual/weekly-sunday-tests.log`、`weekly-sunday-build.log`、`weekly-sunday-device.log`。最终 APK SHA-256：`7CB26DFABA2B905B268CCEC7E22509D20632B94FCAEC943FA097D481A97A34DA`。

## 已建立环境

- AVD：`AFS_Test_API34`，Android 14 / API 34，Google APIs x86_64，Pixel 6 / 1080×2400。
- 2 个虚拟 CPU、3072 MB RAM；Windows WHPX 加速检查通过。
- SDK：`%USERPROFILE%/.afs-android/sdk`。
- AVD 数据：`%USERPROFILE%/.afs-android/avd/AFS_Test_API34.avd`。它拥有独立的应用数据，不导入手机记录、凭据或个人文档。
- 模拟器 37.2.12.0，官方压缩包 SHA-1：`488ed747e82de7e9bb5247becd1ac043c7e5e85d`。
- 系统镜像 r14，官方压缩包 SHA-1：`e0f6c9a0691aa27bd597d0deb1bcfdc943ac8ca7`。两者下载后校验通过。

运行 `dpc/test/start-virtual.ps1` 启动或复用现有设备。图形后端使用 SwiftShader，避免宿主窗口捕获问题影响 Android 的画面读取。

## 控制和传输

本轮没有执行 ADB 命令。屏幕、触控和剪贴板通过 Android Emulator 自带的 localhost gRPC 服务操作（8554，Bearer token 校验）。令牌只从模拟器运行信息读取，不输出、不提交；模拟器自身可能运行内置桥接服务，不代表使用它操作物理手机。

Windows Computer Use 能识别窗口，但截图连续报 `window crop is outside captured monitor`，因此本轮没有将其视作可用的窗口截图通道。改为模拟器原生 `getScreenshot` / `sendTouch`，已验证 Android 桌面、浏览器、触控和剪贴板。

本机辅助程序及生成的 gRPC Python 模块在忽略目录 `.codex-fix/virtual/`；依赖只安装到该目录的 `python/`。协议来源为 SDK 自带 `emulator/lib/emulator_controller.proto`。`control.py` 可读取屏幕或向这台 AVD 输入触控；不连接 Android 物理设备。

测试安装包通过仅监听 `127.0.0.1:8765` 的临时本地 HTTP 服务提供。只服务单独的测试文件目录；Android 中使用 `10.0.2.2` 访问宿主回环地址。测试结束后停止该服务，更新包时重新复制本机 APK。

## 验证边界

本轮安装了仓库现有 1.52.3 / 2161 APK（通过 aapt 读取包版本），安装路径是虚拟设备浏览器下载 → 系统文件管理器 → 系统安装器，已观察到 “App installed” 并成功打开。底栏可切到记忆页，独立环境没有手机上的学习记录。

首次启动与切页截图存在顶部模糊残留，当前不能区分应用绘制缺陷与 SwiftShader 兼容问题。证据：`.codex-fix/virtual/first-launch.png`、`memory-first.png`。因此只能确认环境和安装/启动/触控可用，不能宣称 UI 渲染验收通过，也不拿此虚拟设备的绝对耗时替代手机性能。

2026-10-27 更新：已安装 1.52.4，正文 RenderEffect 移除后，主页面与记忆页面正文恢复清晰；截图见 `.codex-fix/virtual/glass-fixed-main-1524.png`、`glass-fixed-memory-1524.png`。底栏仍有标签裁切，已补充 decor padding 修正并构建 1.52.5 / 2163，最终包安装验证待完成。详情见 `RENDERING-OPTIMIZATION.md`。

Windows 注册的 APK MIME 类型为 application/octet-stream，Chrome 下载列表不能直接打开；系统 Files 能按扩展名识别并安装。调整传输服务的组合命令曾被自动审批拦截（没有详细原因），之后使用已下载文件完成安装，没有重试被拒的组合命令。

虚拟设备用于 UI、页面切换、深浅色、键盘、文件选择器和离线工具。它不是手机厂商系统的精确替代品。当前 AVD 没有设置 Device Owner，设备管理器/冻结应用的特权行为不能据此宣称通过。不得为了验证而修改手机管理策略。
