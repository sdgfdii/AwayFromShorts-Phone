# 给 codex 的动效优化提示词（AwayFromShorts）

用法：**一个批次一次复制**。A 做完验证通过再开 B，不要三个一起丢——diff 太大会让 codex 自己改乱。
行号是当前快照，codex 执行前应先确认是否漂移。

---

## 批次 A（P0）：补齐动效门控 —— 这是无障碍缺陷

```
你在 AwayFromShorts-Phone 项目里工作。先读技能：
C:\Users\nj\.codex\skills\ui-design-library\SKILL.md
以及 C:\Users\nj\.codex\skills\ui-design-library\references\android-view-native.md
严格按 android-view-native.md 的规范执行。这个项目是 javac + android.jar 直编、零 Gradle、
除 pdfbox 外没有任何 AndroidX/Material 依赖，禁止引入任何新依赖。

背景：项目已有 com.afs.guard.MotionPolicy，它读系统的 ANIMATOR_DURATION_SCALE /
TRANSITION_ANIMATION_SCALE，以及 App 自己的 reduce_motion 开关。但有三处动效没有走它，
导致用户开启"减弱动态效果"后动画照样播放。这是无障碍缺陷，不是风格问题。

任务：把以下三处接入 MotionPolicy，不改任何业务逻辑、不改视觉参数（时长/曲线保持原值）。

1) dpc/java/com/afs/guard/AppListActivity.java 第 880~892 行 frostAnim()
   现状：ValueAnimator INFINITE + REVERSE，850ms，直接 setDuration(850) 后 a.start()，
        完全不查 MotionPolicy。调用点在 968 行和 1030 行。
   要求：frostAnim 返回 null 或直接不启动来跳过动画时，调用方必须容错
        （现有调用方写的是 if (r.frost == null) r.frost = frostAnim(...)，
         如果返回 null 会每次绑定都重新调用，要注意不要变成空转）。
        推荐做法：在 frostAnim 内部先判 MotionPolicy.enabled(getContext())，
        不满足时把 tv.setAlpha(1f) 后返回 null；并把调用方改成只在
        MotionPolicy.enabled 为真时才 try 启动。stopFrost 保持原样。
   注意：不要用 MotionPolicy.duration(ctx, 850) 拿到 0 就继续 start——
        无限动画拿到 0 时长会变成每帧疯狂回调，必须整个跳过。

2) dpc/java/com/afs/guard/AppListActivity.java 第 689~790 行 IceBgDrawable
   现状：startDrift() 用 Handler postDelayed 每 40ms 重绘一次，无限循环，
        同样不查 MotionPolicy。
   要求：startDrift() 里判断 MotionPolicy.enabled(context) 为假时直接 return
        （drifting 保持 false），draw() 会用 anchorMs 的静态位置画一帧静态冰块，
        不要让它彻底消失。stopDrift() 不动。

3) dpc/java/com/afs/guard/SplashView.java 第 53~93 行 play()
   现状：入场 animate() 150ms + spin INFINITE（600ms/圈）+ load 640ms，
        全部不查 MotionPolicy。这是冷启动第一屏，最影响体感。
   要求：play() 里先判 MotionPolicy.navigation(getContext())：
        - 为真：保持现在的完整动画。
        - 为假：跳过缩放淡入和旋转/进度动画，直接把视图设为终态
          （scale=1、alpha=1、phase 推到最终值），然后立刻或在极短延时后
          调用 done 回调。不能让用户卡在启动页。
   stop() 里的 cancel 逻辑保持原样。

不要做的事：
- 不要重构 MotionPolicy 本身，它已经是对的。
- 不要改版本号、不要动 dpc/build.sh、不要重新签名。
- 不要顺手"优化"其他文件，本次只碰上面三处。

验收（必须逐条执行并贴出结果）：
1. grep -rn "setRepeatCount(ValueAnimator.INFINITE)" dpc/java 结果里，
   每一处所在方法的调用链都能追到 MotionPolicy 判断。
2. 编译：bash dpc/build.sh（按项目现有方式，不要自己发明构建命令）。
3. 回归：bash dpc/test/run.sh，期望 207 PASS / 0 FAIL。
4. 用 adb 把开发者选项里的"动画程序时长缩放"设为关闭，进应用列表页和冷启动，
   确认页面可用、无元素停在半透明状态、启动页正常放行。
   设备是 PLR110，adb 在 ~/.afs-android/sdk/platform-tools，路径要写 C:/ 开头并加 MSYS_NO_PATHCONV=1。

交付：只说改了什么、每处为什么这么改、验收结果。不要复述代码。
```

---

## 批次 B（P1）：曲线与时长收敛

```
你在 AwayFromShorts-Phone 项目里工作。先读：
C:\Users\nj\.codex\skills\ui-design-library\references\android-view-native.md
这份规范里的曲线表和四档时长表是硬约束。项目零 AndroidX，只能用 android.view.animation.* 原生类。

背景：现在有 9 处裸写 setDuration(数字)、6 种不同的 DecelerateInterpolator factor
（1.4/1.5/1.6/1.7/1.8/2.2）、30 条 .animate() 链里有 4 条根本没设插值器（吃默认的
AccelerateDecelerateInterpolator）。这些数值之间没有层级逻辑，是逐个手调留下的残渣。

任务：

1) 新建 dpc/java/com/afs/guard/Motion.java，只放常量和工厂方法，不持有 Context：
   - 四个 Interpolator 常量（OUT / OUT_SOFT / IN / IN_OUT），用 PathInterpolator 三次贝塞尔，
     具体控制点按 android-view-native.md 第 1 节。
   - 四档时长常量 TAP=120 / ENTER=200 / EXIT=160 / PAGE=280。
   - 文件要能通过现有 javac 编译（min-api 24，PathInterpolator 是 framework 21+ API，可用）。

2) 把现有动画迁移到 Motion + MotionPolicy：
   - 所有 new DecelerateInterpolator(x) 按语义映射到 OUT 或 OUT_SOFT，删掉 factor。
     映射依据：配合入场/悬停反馈用 OUT；配合按压、选中、微位移用 OUT_SOFT。
   - 所有裸 setDuration(数字) 换成 MotionPolicy.duration(ctx, Motion.XXX)。
     已有的 MotionPolicy.duration(this, 170) 这类调用，170 归到 EXIT(160)、
     180/190/200/210 归到 ENTER(200)、220/230/260 归到 PAGE(280)。
   - 4 条缺插值器的链补上：FreezeBoardActivity.java 293 行和 339 行的悬停缩放
     （translationZ + scale 1.015）用 OUT_SOFT + TAP 档；
     检查 315 行那条也一并补。
   - 保留 OvershootInterpolator，但把它的参数收敛到 1.0f~1.2f 区间，
     超过 1.2 的降到 1.2。

3) 不要改动的部分：SplashView 的 spin/load 是匀速循环，LinearInterpolator 正确；
   SwipeCardView 的 FLY_OUT_MS/SNAP_BACK_MS 常量保留名字，但值改成引用 Motion.EXIT / Motion.TAP，
   并让调用点经过 MotionPolicy.duration。
   注意 SwipeCardView 是手势驱动的，flyOut 和 snapBack 必须保持"松手即响应"，
   如果 MotionPolicy 返回 0 时长，要有 endAction 兜底确保回调仍然触发。

不要做的事：
- 不要统一成单一曲线。OUT / OUT_SOFT / IN / IN_OUT 四种都有存在理由。
- 不要引入 tween 之外的新概念（不要写弹簧物理模拟，这套栈没有 Compose 的 spring）。
- 不要改版本号、不要动 build.sh。

验收：
1. grep -rn "setDuration([0-9]" dpc/java 应为空（SwipeCardView 的常量引用除外）。
2. grep -rn "DecelerateInterpolator\|AccelerateDecelerateInterpolator" dpc/java 应为空。
3. grep -rn "new OvershootInterpolator" 的参数全部在 1.0~1.2。
4. bash dpc/test/run.sh，207 PASS / 0 FAIL。
5. adb 装机后逐个页面走一遍：应用列表冻结态、冻结看板、打卡页、备忘、设置。
   重点看有没有动画结束但元素停在半路（时长被截断的典型症状）。

交付：给出"旧值 → 新值"的映射表，以及哪些页面的观感变化最明显。
```

---

## 批次 C（P2）：退场方向 + 绘制期去分配

```
你在 AwayFromShorts-Phone 项目里工作。先读：
C:\Users\nj\.codex\skills\ui-design-library\references\android-view-native.md 第 3、5 节。

两个独立问题，分开做、分开验证。

问题一：退场用了入场曲线
dpc/java/com/afs/guard/MainActivity.java 第 315 行 handOver()：
splash 淡出 + 放大到 1.10 倍时用的是 DecelerateInterpolator(1.4f)。
退场动作用减速曲线，观感是"黏着屏幕不肯走"。按规范第 3 节改成 IN 曲线（ease-in），
时长从 220 收到 Motion.EXIT 档。改完确认 splash 移除后主界面完全可交互，
withEndAction 里的 removeView 必须仍然执行。

问题二：无限循环里的每帧对象分配
dpc/java/com/afs/guard/AppListActivity.java 里 IceBgDrawable.draw()（约 764~900 行）：
在 40ms 一次的重绘循环里，每帧 new 了 2 个 Paint、3 个 Path、1 个 RectF，还有若干 float[]。
相当于每秒 150+ 个短命对象，冻结态的电池开销全在这里。
要求：
- 把 Paint / Path / RectF 提成成员字段，在构造或 setBounds 时复用，
  draw 里只做 reset() 和重新设置属性。
- Clip 用的 Path + RectF 只在 bounds 变化时才重建，不要每帧重建。
- 循环里遍历的 CUBES 若是每帧新建的 float[]，同样提到静态常量。
- 不要改变任何视觉输出。改完必须逐像素比对：冻结态截图前后一致
  （用同一台设备、同一冻结时长、同一组 App 抓两张图比对）。

验收：
1. bash dpc/test/run.sh，207 PASS / 0 FAIL。
2. 真机装包，进入一个冻结中的 App 列表页，静止观察 30 秒。
   adb shell dumpsys gfxinfo com.afs.guard 看掉帧，改前改后对比一次。
3. 退场：冷启动后看 splash 消失是否干脆，主界面立刻可点。

交付：改动前后 gfxinfo 的关键数字（janky frames / 90th percentile），以及静态截图比对结论。
```

---

## 附：共用红线（每次都要带上）

```
通用约束：
- 项目是 javac + android.jar 直编，零 Gradle。构建只走 dpc/build.sh 或 dpc/release/_build_release.sh，
  不要自己发明 gradle / maven 命令。
- 禁止引入 AndroidX、Material、Kotlin、Compose 的任何代码或依赖。
- 不改版本号（dpc/build.sh 里的 AFS_VERSION_CODE / AFS_VERSION_NAME 是唯一真相）。
- 不改签名、不动 dpc/keystore。
- 每次改完必须跑 bash dpc/test/run.sh 并贴 207 PASS / 0 FAIL 的实际输出。
- 中文文案改动必须走 tools/i18n.py check|merge|export，新文案放 tools/translations/batchNN.py，
  变量名必须是 TEXT。本次动效优化原则上不应产生新文案。
- 构建前若 rm -rf dpc/build 被拦，用 mv 移到 %LOCALAPPDATA%/Temp/ 而不是硬删。
```
