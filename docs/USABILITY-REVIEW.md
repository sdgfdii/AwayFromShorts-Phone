# 可用性修复审查 · 2026-10-26

状态：需修正，暂不通过。由当前聊天执行代码审查，未调用其他模型。范围为 USABILITY-TASKS.md 的 T01、T02 和对应设备测试。

## R01 / P1：保存失败可从返回操作抛出并崩溃
- 位置：QuizActivity.base 中新增的 `if(data!=null && draftDirty)save()`，以及 onBackPressed。
- 触发：主观题输入后有脏草稿，存储写入失败或数据超限，此时按系统返回或页面返回。
- 证据：onBackPressed 直接调用 home；home 调用 base；base 直接调用 save；QuizStore.commit 在写入失败时抛 IllegalStateException，超过 16MB 时抛 IllegalArgumentException。此链路没有 act/try-catch。
- 影响：新增保存入口可能导致未处理异常，违背交接中“走已有错误提示”的表述；页面状态还可能提前变为 home。
- 修正要求：在切换前统一处理保存失败，保留草稿和当前页面；提示重试，不可清除脏标记或显示已保存。不要在显示错误页面时再次递归尝试同一个失败提交。
- 验收：隔离存储中注入提交失败，分别触发系统返回和页内返回，确认不崩溃、页面状态正确、草稿可重试；成功路径仍正常。

## R02 / P2：首次大幅 MOVE 可能抢走横向子控件手势
- 位置：SwipeNavHost.dispatchTouchEvent 在 super.dispatchTouchEvent(event) 之前判断 MOVE 并取消子控件。
- 触发：HorizontalScrollView 或滑卡控件在首个超过 touch slop 的 MOVE 才调用 requestDisallowInterceptTouchEvent(true)，而该 MOVE 已达到页面横滑阈值。
- 证据：host 在子控件收到该 MOVE 之前检查 claimed，提前发 CANCEL 并吞掉原 MOVE，子控件没有接管机会。原实现先派发子控件事件，再检查切页。
- 影响：快速横滑选卡条、学科条时可能变成整页切换；现有测试手工提前设置 disallow，未覆盖这个时序。
- 修正要求：让符合条件的子控件在 MOVE 上拥有接管机会，同时确保 UP 不触发点击。保持取消、多指及纵向滚动语义。
- 验收：真实 HorizontalScrollView 和在 MOVE 中接管的子控件接收 DOWN 后，首个 MOVE 超过阈值，应该滚动/接管且导航次数为零；普通按钮上的相同滑动仍应导航且不点击。

## 验证范围
本次为代码路径审查，未重新构建和安装。此前 17 组 Java、12 项设备检查通过，但没有覆盖上述失败和事件时序，不能据此关闭这两项。
