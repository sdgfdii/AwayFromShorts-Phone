# FlClash 手机底栏：REA 逆向记录

2026-10-25。对象是手机中实际安装的 FlClash，重点核对极致玻璃材质和按压、拖动、松手的交互。

## 结论

手机当前启用 **浅色、悬浮底栏、滑动切页、液态玻璃「极致」**。实机截图已经确认：静止有色选中块 → 按住后抬起为透明镜片 → 拖动时图标发生放大、拉伸和彩色折射 → 松手后提交页面切换、恢复有色选中块。

匹配源码显示它包含三个不同的绘制部分：底栏背景玻璃、按压时的外层镜片、极致模式下图标和标签的内容镜片。仅改变半透明填色或背景模糊，不能覆盖后两个部分。

本记录保存逆向时的规格与原应用实机证据。后续 AwayFromShorts 1.44.0 的移植和验证见 [实现记录](FLCLASH-GLASS.md)。

## 样本身份与分析方法

- 包名 `com.follow.clash`，版本 `0.9.1`，versionCode `2026103702`。
- 本轮重新读取安装包 SHA-256，与本地 APK 相同：`7a8aeedbe3c376b724a1f76521f5322df1da4bb8d8e09833a2fa804b2913ecc2`。
- 该摘要与先前核对的官方 v0.9.1 arm64 发布资产一致。匹配源码 commit 为 `8d4f9744948751f1273fe871e1d05276fac09b0e`，没有使用 main 上的新版本参数替代。
- REA 6.7.0 CLI 用于 APK 结构和 Flutter 载荷识别、读取手机包信息和前台窗口、截取原应用画面。手势由普通 ADB 注入，源码核对通过本地文件完成；这些不冒充 REA 的反编译结果。
- REA 的截图证据本身不含 APK 身份；本记录结合前台窗口、包信息、重新核对的安装包摘要建立目标关联。

来源：[官方 v0.9.1 发布](https://github.com/chen08209/FlClash/releases/tag/v0.9.1)。本地样本及完整 Evidence 在 `.codex-fix/flclash-rea/`，本轮原图、UI 树和证据在其中的 `runtime-20261025/`。

## 实机观察

| 状态 | 实际观察 | 原图目录 |
| --- | --- | --- |
| 静止 | 底栏悬浮，选中「工具」为主题色圆角色块；下方页面内容可通过玻璃看到 | `rest-tools/` |
| 按住「配置」 | 页面仍是工具；色块退去，配置所在位置抬起为更大的透明镜片，图标放大，镜片有高光和彩边 | `held-config/` |
| 松开 | 页面变为配置；选中块恢复为主题色 | `released-config/` |
| 从配置拖向代理，手指未松开 | 页面仍是配置；镜片移动到两项之间，对代理和配置的图标、标签同时产生拉伸和色散 | `drag-config-proxy/` |
| 松开拖动 | 页面进入代理，选中色块落在代理项 | `released-proxy/` |
| 主题设置 | 悬浮、滑动、极致均有选中标记 | `theme-glass/` |

`dock-states.png` 是原始底栏截图的裁剪排版，未补绘效果。各状态的完整屏幕原图保留为对应目录下的 `screen.png`，可核对按住和拖动时页面尚未切换。

## 匹配源码中的规格

以下是源码中的设定值，不能当作本轮实测的逐帧时间或光学测量结果。

### 布局与背景玻璃

| 项目 | 数值或规则 |
| --- | --- |
| 底栏高度、侧边距、内部留白 | 62 dp、21 dp、4 dp；底部兼顾安全区 |
| 图标、标签、图文间距 | 28 dp、10 的逻辑字号、2 dp |
| 静止镜片宽度 | 不小于 84 dp，通常比单项两侧各多 4 dp |
| 抬起增长、内容放大 | 镜片宽高各增加最多 16 dp；图标内容放大 16% |
| 背景混合 | 清晰折射 28%，haze 72%；haze blur 15.6 |
| 浅色 | tint `#93F4F4F4`，saturation 2.1，contrast +0.4 |
| 深色 | tint 使用主题的 surfaceContainerHighest、alpha 0.48，saturation 1.925，contrast -0.4 |
| 默认背景折射、倒角带 | refraction 60、bevel 20，并随可用短边限制 |
| 静止选中色块 | 来自主题 secondary 色相，浅色 tone 90、深色 tone 4，alpha 0.85 |

`haze=0.72` 是两路采样的混合比例，不是整张 View 的透明度。这里的 blur 等参数属于 Flutter 绘制路径，不能原样等同于 Android 窗口模糊 API 的半径。

### 两层按压镜片

| 部分 | 外层镜片 | 极致模式的图标内容镜片 |
| --- | --- | --- |
| tint / blur | 透明 / 0 | 透明 / 0 |
| refraction / bevel | -4.3 / 5.5 | 14 / 11.2 |
| dispersion / rimBlur | 6.5 / 1.5 | 3.6 / 2.5 |
| 深色亮度 | 1.33 | 1.33 |

内容镜片对底栏自己的图标和文字再做一次处理，是本轮拖动截图中图标彩边和变形的重要来源。背景阴影、沿圆弧变化的高光、边缘细线和随手指位置移动的微光分别绘制。

### 手势与弹簧

- 手指落下立即抬起镜片；按压时还未提交页面切换。
- 拖动超过 touch slop 后跟随手指；跨项时有选择触感；超出边界使用橡皮筋阻尼。
- 松手才提交目标项；取消手势回到原项。快速甩动按速度外推 100 ms，最多越过手指所在项一项。
- 位移弹簧设定 400 ms、bounce 0.15；抬起设定 250 ms；整体回落设定 500 ms、bounce 0.32。
- 跟手弹簧 mass 1、stiffness 1024、damping ratio 0.9；果冻形变单独使用 stiffness 100、ratio 0.4。
- 微光进入 100 ms、消退 500 ms。位置、按压、形变和微光各自变化，不能由单一缓动曲线代替。

源码位置：

- [布局、交互和镜片分层](https://github.com/chen08209/FlClash/blob/8d4f9744948751f1273fe871e1d05276fac09b0e/lib/ui/navigation_dock.dart)：20–149、1003–1125、1130–1393。
- [背景与镜片参数](https://github.com/chen08209/FlClash/blob/8d4f9744948751f1273fe871e1d05276fac09b0e/lib/ui/liquid_glass.dart)：76–99、130–150、193–223。
- [合成时的背景采样](https://github.com/chen08209/FlClash/blob/8d4f9744948751f1273fe871e1d05276fac09b0e/lib/ui/liquid_glass_capture.dart)：41–43、282–363、497–625。
- [折射、背景混合及圆弧高光](https://github.com/chen08209/FlClash/blob/8d4f9744948751f1273fe871e1d05276fac09b0e/shaders/liquid_glass.frag)：367–460。

FlClash 在合成当前画面时获取背景采样，并将其交给 shader 做折射、雾化和色散；底栏内的文字、图标还有独立内容层。这解释了它随画面更新的透视效果，也说明静态背景贴图不能替代这条路径。

## 逆向时的差距与后续移植

逆向时的 AwayFromShorts 1.43.1 通过系统合成器提供实时透明和背景模糊，并有独立的按压高光与选中块。该版尚未实现外层光学镜片、图标内容二次折射和色散。

用户随后明确取消固定底栏与慢速气泡的保留要求。1.44.0 改用接近参考的弹簧节奏，底栏会随按压涨起、越界拖动形变，并增加实时背景与独立图标折射。原生光学路径的近似和实机验证范围在实现记录中说明。

## Evidence 与限制

| 文件（相对分析目录） | REA Evidence |
| --- | --- |
| `rea-artifact.json` | `ev_5f94911c755cbd36f5335a06f4e4afeb50e08a953dba0ca7c5f2977e775bc43d` |
| `rea-flutter.json` | `ev_77db56fedb3fb7832bc059bba81cc0b94379dd96a5c37b493aadfd93214ad1ff` |
| `runtime-20261025/package-evidence.json` | `ev_6feac1a5c12219a0e7cf4a54a3a68fbd595f2359bbb689e2526bbde9987fed50` |
| `runtime-20261025/window-evidence.json` | `ev_3948b47669358831391766e02ee962485b37d1989637cd8028a6cad2fefeff05` |
| `runtime-20261025/held-config-evidence.json` | `ev_3bce2204056f1f5156d8666c6710d9925e08f664a73940a4c71725d4f7d955d8` |
| `runtime-20261025/drag-config-proxy-evidence.json` | `ev_8f3e154c119fe60f8a26bc38ebadf948ef7082b50692cb795da88283f5bae061` |
| `runtime-20261025/released-config-evidence.json` | `ev_342ba5fc112fd41d9f0dd912f6db81b806d3b55ffbd5930ca83b5b21be111227` |
| `runtime-20261025/released-proxy-evidence.json` | `ev_fbf02e572dde06fd1f0982a2cb43fdec67564b07d28d17d23e1f2aab3cd0a413` |
| `runtime-20261025/theme-glass-evidence.json` | `ev_58fb44616835fa154ed9c52510edafd62969263024a71915178a467ddc1ae583` |

- APK 中 `liquid_glass.frag` 是编译后的 Flutter shader；参数和算法由匹配版本的公开源码补证，没有宣称从该资源还原了原始 GLSL。
- REA Flutter 识别报告覆盖为 partial；本轮 `inspect_dart_aot` 返回 unsupported_target，原因是未找到其要求的 Dart snapshot 动态符号。失败结果保留在 `dart-aot-evidence.json`；没有成功反编译全部 Dart 的结论。
- 手机拒绝 screenrecord 写入，改用受控手势和独立状态截图。截图可证实状态变化，不能据此量出准确帧时间、触感、完整回弹轨迹或流畅度。
- 本轮没有更改 FlClash 的主题档位、代理配置或连接状态；查看后已返回最初的配置页。
