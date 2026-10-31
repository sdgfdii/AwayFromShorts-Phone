# 页面绘制优化 · 1.52.5

后续验收（2026-10-27）：用户授权手机连接后可以使用 ADB，包含上述修正的 1.52.6 / 2164 已安装到虚拟设备和手机。虚拟设备截图 `.codex-fix/virtual/1526-main.png` 确认正文清晰且五个底栏标签显示完整；先前传输阻塞已解除。下文保留最初 1.52.5 构建时的验证记录。

本轮范围：修正虚拟设备上已复现的顶部模糊残留，减少底栏对正文绘制的影响。沿用原生 Java、多 Activity 和现有存储，不新增运行时依赖；不调用 ADB、不安装到手机。保留工作区其他任务的未提交改动。

## 代码问题与修正

原 `NavGlassRenderer` 把包含高斯模糊和两路 RuntimeShader 的 RenderEffect 挂到整张 host 页面上。进程缓存还向多个 Activity 返回同一套可变 RuntimeShader，页面会共享 lens/bar 等参数。这是源码确认的风险；原截图的完整成因需以虚拟设备对照判断。

- `NavGlassRenderer` 仅给底栏图标区域做按压折射，缓存不可变 AGSL 字符串；每页创建独立 RuntimeShader。正文不再挂底栏 RenderEffect。
- `LiveNavWindow` 在底栏独立窗口中使用系统实时背景模糊。GPU 图标效果不再关闭窗口模糊；首次 show 后立即应用外观，避免临时不透明底。
- 1.52.4 虚拟检查发现系统窗口背景会重新带入 decor padding，裁切底栏标签。1.52.5 在设置背景和显示窗口时重置 padding；此项已构建，尚待安装后的视觉确认。
- 切页继续沿用现有从用户点击时刻计算的 240ms 滑动/180ms 淡入预算，剩余不足 80ms 则直接就位。本轮未进一步改变动画节奏。
- 系统不支持跨窗口模糊时，仍使用既有着色背景保证可读性；不承诺所有设备的视觉一致。

## 验证状态

- 手工构建与签名通过；最终包版本 1.52.5 / 2163。SHA-256：`27D524C38B0EA4707CE87FA51192CACA6430CDB7649DD2B9AF7A525D7820C7AD`。
- 当前完整本地回归通过（含 Memo 69、MemoEditor 14、Toolbox 52、ToolMath 80 等）。
- 已在 API 34 虚拟设备安装 1.52.4：主页面与记忆页面正文清晰，原顶部模糊残留在这两页未再出现。截图：`.codex-fix/virtual/glass-fixed-main-1524.png`、`glass-fixed-memory-1524.png`。
- 1.52.5 包未成功传入虚拟设备：Chrome 本地下载/Fetch 失败。启动另一传输服务的组合命令被自动审批拒绝，未返回详细原因，未继续该操作。因此不能宣称最终底栏标签修正已经视觉验收，也没有新的切页耗时数据。
- 未使用 ADB、未操作手机；AVD 没有 Device Owner，冻结特权行为不在本轮验收范围。
- 本机日志：`.codex-fix/virtual/glass-fix-build.log`、`glass-fix-tests.log`。
