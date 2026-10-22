# 打卡（CheckinApp）

> 选一个目标，**往上滑一次**，就记一笔。打完自动切到下一个。

独立 APK，和 `AwayFromShorts-Phone` 的守卫 App 是一对：
守卫负责"没打完就继续冻"，这个负责"打完"。

## 为什么不在网页上打卡

学习台的页面只能在 WorkBuddy 宿主的 iframe 里跑：

- `inject.js` 一检测到自己是顶层窗口（`window.self === window.top`）就
  `document.write` 一段"安全限制"再 `window.stop()`，页面直接变空白；
- 数据层 `window.__SMART_PAGE__.database` 靠 `postMessage` 跟父页面通信，
  真正发请求的身份在宿主那边。

所以任何独立 App 都**拿不到写学习台的凭据**，这条路走不通。

## 那数据怎么回去

手机能写的通道只有那个 secret Gist（守卫本来就在用它读闸门状态）：

```
打卡 App ──上滑──▶ 本地队列 ──▶ Gist 的 checkin-queue.json
                                        │
                            PC 每 15 分钟（study_sync.py）
                                        ▼
                          apply_checkins.py ──▶ 学习台「打卡记录」表
                                        │
                                  清空队列 + 重推 gate
                                        ▼
                              守卫 App 读到新状态
```

### 先解闸，台账后补

打完卡不想干等 15 分钟，所以队列里带了一个 `pendingAfter`：

```json
{"date":"2026-10-23","active":3,"pendingAfter":0,
 "items":[{"id":"ck_...","goalId":"...","amount":6,"date":"2026-10-23"}]}
```

守卫读到 `pendingAfter == 0` 就不再延长冻结。**注意是 `== 0` 而不是"队列非空"** ——
后者等于打一个目标就把剩下两个一起豁免，闸门就废了。

等 PC 记完账把队列清空，守卫的判定自然回到 `allDone` 那条线上，是收敛的。

## 交互

| 动作 | 结果 |
| --- | --- |
| 点卡片 | 切到这个目标 |
| 长按卡片 | 手动填这一笔的数量（默认用建议量） |
| **上滑** | 打卡当前目标；没滑够松手会回弹，不会误打 |
| 右上「刷新」 | 重新拉 Gist |
| 长按标题「打卡」 | 换 token（只授 gist 权限） |

建议量用的是学习台 `suggestOf()` 同一套算法：
`剩余量 / (剩余天数 + 1)`，≥1 向上取整，否则保留一位小数。
两边算法不一致的话，同一天在不同地方打卡会得到不同结果，看着像 bug。

## 离线

上滑永远能成功：先写本地队列（SharedPreferences），再异步推 Gist。
推失败只在状态行写「待记账 N 条」，不留错误弹窗 —— 下次打开或点刷新会自动重试。
`localId` 由手机生成并作为学习台的「记录ID」，PC 端靠它去重，重复推送不会重复记账。

## 构建

```bash
# token 只放在本地，不进 git
echo "<github-token>" > token.local
bash build.sh          # 产物 ../checkin.apk
adb install -r ../checkin.apk
```

没有 `token.local` 也能构建，只是首次启动要长按标题手动粘 token。

## 已知限制

- **读 Gist 的 raw 有 CDN 缓存**（约 1 分钟）。打完卡后另一台设备可能还要等一会儿
  才看得到 —— 加 `?t=` 时间戳没用，CDN 会忽略 query。
- PC 一直不开机时，打卡会一直堆在队列里；守卫因此保持放行（fail-open 的一致性）。
  队列条目超过 7 天会被手机端清掉。
