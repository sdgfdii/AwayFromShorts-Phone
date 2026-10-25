# FlClash 底栏分析与 Android 实现

2026-10-25。目标是用户手机中 `com.follow.clash` 的底栏，不涉及网络、订阅或代理配置。

本轮补充的原应用按住、拖动、松手截图，以及已实机确认的「极致」档位，见 [REA 实机逆向记录](FLCLASH-REA-20261025.md)。本文件还保留了 AwayFromShorts 历史实现的变更记录。

## 来源和版本

- 手机安装版本：FlClash 0.9.1，versionCode 2026103702。
- 从手机复制的 APK SHA-256：`7a8aeedbe3c376b724a1f76521f5322df1da4bb8d8e09833a2fa804b2913ecc2`。
- 此摘要与官方 v0.9.1 arm64 发布资产的 digest 完全一致。
- 匹配源码的 tag commit：`8d4f9744948751f1273fe871e1d05276fac09b0e`。分析没有把 main 上的 0.9.2 当成手机版本。
- [官方发布包](https://github.com/chen08209/FlClash/releases/tag/v0.9.1)
- [底栏](https://github.com/chen08209/FlClash/blob/8d4f9744948751f1273fe871e1d05276fac09b0e/lib/ui/navigation_dock.dart)
- [材质参数](https://github.com/chen08209/FlClash/blob/8d4f9744948751f1273fe871e1d05276fac09b0e/lib/ui/liquid_glass.dart)
- [光学绘制](https://github.com/chen08209/FlClash/blob/8d4f9744948751f1273fe871e1d05276fac09b0e/shaders/liquid_glass.frag)

REA 官方技能 `reverse-engineer-anything` 已安装到用户的 Codex skills 目录。本轮使用 REA 6.7.0 CLI，不需要修改 Codex 的 MCP 配置。分析文件位于 `.codex-fix/flclash-rea/`。

## 已证实的结构

源码的 pill 材质是两路背景采样：清晰折射部分占 28%，15.6 dp haze 部分占 72%。haze 不是整张视图的透明度；输出必须完整替换该区域，避免原页面又露出一份清晰文字。

| 项目 | FlClash 0.9.1 静态规格 |
| --- | --- |
| 底栏高度 / 边距 / 内边距 | 62 dp / 21 dp / 4 dp |
| 图标 / 标签 / 图文间距 | 28 dp / 10 sp / 2 dp |
| 清晰通道 blur / haze | 0 / 0.72 |
| haze blur | 15.6 dp |
| 浅色 tint | #93F4F4F4 |
| 深色 tint alpha | 0.48，RGB 来自主题 |
| 浅色 / 深色饱和度 | 2.1 / 1.925 |
| contrast | 浅色 +0.4，深色 -0.4 |
| 折射 / bevel | 60 dp / 20 dp，并按半短边限制 |
| 静止选中块 | secondary 色相的浅色或深色色盘，alpha 0.85 |
| 按住选中块 | 色盘退去，转为透明抬起镜片 |
| 镜片移动 | 400 ms spring，松手才提交页面切换 |

这些是匹配源码的静态观察。手机上生成的具体动态主题 RGB、所有 spring 的逐帧轨迹，以及 ultra 模式下图标内容的二次折射，没有由这些静态参数本身得到证明。

## REA Evidence

- `rea-artifact.json`：`ev_5f94911c755cbd36f5335a06f4e4afeb50e08a953dba0ca7c5f2977e775bc43d`。安装包及资源盘点，authority 为 shipped-artifact。APK 内包含 `assets/flutter_assets/shaders/liquid_glass.frag`，资源本身是编译后的 Flutter shader，不冒称原始 GLSL。
- `rea-flutter.json`：`ev_77db56fedb3fb7832bc059bba81cc0b94379dd96a5c37b493aadfd93214ad1ff`。确认 Flutter 载荷。其覆盖标记为 partial，并报告 AOT snapshot section 的布局限制；没有据此宣称成功反编译了全部 Dart 代码。
- `rea-screen-memory.json`：`ev_dc3e3642868b531e5248d986c2073866b2e19fa0cb4070a57c409853535c96ec`。重建版本的实机截图记录。
- GitHub 官方发布包 digest 与手机 APK digest 的核对、匹配 tag 源码的分析，属于另行保留的源码/版本证据，不伪装成 REA 原生反编译结果。

## 本项目实现与验证

以下是历史版本 1.41.0 的采样实现记录，已在 1.43.0 移除。该版本曾使用独立编写的 shader 和背景采样类实现双路采样、主题 tint、饱和度、自适应遮色及圆弧倒角折射，没有把 Flutter 源码直接复制进 Java 项目。

- 原生尺寸背景取样，线性过滤，取样区域预留外围空间；两块位图交替复用，避免每次滚动创建大位图。
- 按住镜片抬起、色盘淡去；可沿底栏拖动，松手后进入目标页面。
- 底栏本体固定；继续只让页面内容左右平移。
- 五个底栏入口均在手机上切换验证；按住拖动后能进入对应页面；未修改用户卡片、答题或冻结规则。
- Guard 207、LedgerMerge 9、Memory 78、Freeze slots 18、Quiz 69 项检查通过，冻结规则边界检查通过。

## 还原边界

本实现复现材质的主要结构和交互，不等于像素或物理 spring 的完整移植。两轮实机截图评审仍观察到边缘文字有灰色折射碎影、浅色玻璃层次较参考平；尚未实现 FlClash ultra 中对图标/文字单独采样的内容镜片和完整色散模型。不能把静止截图当作回弹手感完全一致的证据。

完整材质路径在当前 Android 36 手机验证；Android 33 以下使用兼容路径，未在其他手机上声称同等还原。

## 1.43.0：改为系统实时透明与背景模糊

用户明确要求底栏实时透出正在滚动的内容，而不是应用复制页面像素后再绘制。1.43.0 移除了上述背景位图采样类、旧 shader 资产，以及 `source.draw` 截图路径。

`LiveNavWindow.java` 将底栏放在当前 Activity 所属的非模态透明窗口中，通过 `Window.setBackgroundBlurRadius` 直接请求系统合成器处理下方实际画面。该窗口不获取键盘焦点，不使全屏变暗，不申请系统悬浮窗权限；页面暂停、销毁或键盘弹出时隐藏/释放。只让页面内容左右平移，底栏窗口保持固定。

本机 `wm disable-blur` 只读查询返回 `supported=true, enabled=true`。实机 SurfaceFlinger 显示 `AFS Live Navigation` 图层有一个 `radius=35` 的 blur region，圆角 `108.5`，区域 `[0,0,1125,217]`；窗口类型为 APPLICATION，像素格式 TRANSLUCENT。这是系统实时模糊路径的运行证据。

该版路径提供真实透明合成、系统背景模糊和边缘高光。FlClash 的复杂折射和色散没有被这项系统 API 完整替代。

参考：[Android 系统窗口模糊说明](https://source.android.com/docs/core/display/window-blurs)。

补充实机验证：五个底栏页面往返成功；中途帧 `live-slide-fixed.png` 显示内容平移、底栏位置固定。`live-keyboard.png` 中键盘正常显示，窗口检查显示全部底栏窗口为 GONE（0x8）；收起键盘、取消添加弹窗后底栏恢复，未添加或删除用户学科。退出桌面时可见底栏窗口为 0，返回应用后为 1。

## 1.44.0：背景玻璃、外层镜片、图标镜片

用户要求按手机里 FlClash「极致」档位复刻，并明确取消先前保留固定底栏和慢速气泡的限制。目标是随按压涨起的悬浮胶囊，手指拖动镜片，松手切页；保留 AwayFromShorts 的五个入口及蓝色强调色。

### 实现

- `NavGlassRenderer.java` 对当前页面的硬件 RenderNode 应用 GPU RenderEffect。清晰折射与真正的高斯模糊分成两路，再按 28% / 72% 合成；页面内容变化会直接进入这条绘制路径。没有创建页面 Bitmap、调用 `source.draw`、PixelCopy 或保存背景截图。
- `dock_backdrop.agsl` 绘制胶囊的倒角折射、主题染色、自适应遮色与圆弧高光，以及按压时的外层透明镜片。
- `dock_content.agsl` 仅处理底栏图标和标签的实时 RenderNode，提供 16% 放大、镜片边缘变形和 RGB 色散。它与页面背景采样分离，避免把页面文字误当成底栏图标。
- `DockSpring.java` 使用解析阻尼弹簧，拖动重设目标时保留速度。跟手采用 stiffness 1024 / ratio 0.9；主要位移采用 stiffness 300 / ratio 0.55，视觉节奏约 0.4 秒；按压整体涨起、回落和微光独立变化。
- 按下立即抬起，超过触摸阈值后进入拖动，越界有阻尼；跨项触感、速度外推、松手提交和取消回原项分别处理。Activity 切换携带镜片位置、速度和抬起状态。
- 原窗口增加 16 dp 绘制余量，允许镜片和底栏涨起。键盘出现、离开 Activity 或回桌面时，同时隐藏底栏与背景效果。
- Android 33 以下或 shader 编译失败时保留兼容的系统玻璃路径；完整光学路径只在本机 Android 36 验证。

平台依据：[Android AGSL 与 RenderEffect](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)。本实现独立编写原生 Java 和 AGSL，没有复制 Flutter GPL 源码。

### 两轮截图自查

本轮按 distinctive-ai-design 的相同框架进行实机自查，没有使用独立评审代理。

1. 第一轮发现折射边缘有重复文字和重复图标，高光偏厚。修正背景折射深度限制，使用 GPU 高斯模糊替代稀疏多点近似，修正图标镜片的边界取样与映射，收薄描边和阴影。
2. 第二轮重新捕获静止、按住、拖动、松手和深色状态。重复图标和多重雾化残影已明显减少，图标在镜片边缘发生彩色折射；按住时页面保持原页，松手后进入目标项。

原图和 REA Evidence 位于 `.codex-fix/glass-replica/`，`round2-*/screen.png` 对应第二轮，`app-dock-states.png` 为原图裁剪排版。原生圆弧和色散响应仍是近似，未宣称与 FlClash 像素或完整物理轨迹一致。

### 验证

- 已安装 `1.44.0`（2127），APK SHA-256：`b1214bf422ca424143e49ee5ab76f72ee1e2a9439f0ac3222023fff08342fee9`。
- 打卡、记忆、守卫、刷题、冻结共 11 次往返，前台 Activity 均与目标入口相符；按住和拖动的实机截图验证镜片状态与页面提交时机。
- 注入 DOWN、MOVE、CANCEL 验证取消手势：操作前后都保持 CheckinActivity，未提交目标入口。
- 记忆和刷题随系统切换深浅色；手机原有 `custom_schedule`、20:00–06:00 设置及日间实际状态已恢复。
- 键盘弹出时 `mInputShown=true`，全部底栏窗口为 GONE（0x8）；收起键盘并取消添加弹窗后恢复。未保存测试学科或修改用户学习数据。
- 返回桌面时五个缓存底栏窗口均为 GONE；返回应用后只显示当前页的一个窗口。
- 短时切页采样的 gfxinfo：1288 个应用窗口帧，90 分位 10 ms、95 分位 11 ms，deadline missed 1.55%。这是本机双窗口的样本，不等同于屏幕帧率或所有场景的性能保证。
- 离线检查：Guard 207、冻结规则边界、LedgerMerge 9、Memory 91、冻结时段 18、Quiz 82、弹簧 126 项通过；APK 完整性检查通过。
