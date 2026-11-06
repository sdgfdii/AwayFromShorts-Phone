# 墨墨背单词功能融合：证据与边界

调查日期：2026-11-03。范围：手机端记忆卡。此文件只报告实际观察，不表示已实施或还原算法。

## 1. 目标身份与 REA 证据

- 设备已安装 `com.maimemo.android.momo`，5.6.10 / 912，minSdk24、targetSdk36；来源为用户已连接手机的已安装 APK。
- APK SHA256：`27f6c90a839bed8251be82b451178f8a11a0bfa5ec5c217ae2aef77014b39dc0`。
- 实际使用 REA 6.8.0 CLI，使用现有 Codex Node24.22.1，未修改 Codex/MCP 配置。
- 命令：`rea inspect-artifact <APK> --detail summary --evidence_output <new-directory> --format json`。实际成功保留完整证据；根 Evidence：`ev_e25d287ce22fbc2ce67a577bf5e16be11708c2e4dd8dfd486046840775e8dd4c`，汇总 Evidence：`ev_0b951b8087bdbc441881b0d363d766732208c0f101894c4d2f320e11ea33dbc7`。
- 归档图：3266 nodes、3396 occurrences、3395 edges；归档检查不等于解出了类、方法或学习算法。
- 实际执行 `inspect-android-package`，结果为 `capability_unavailable/unsupported_provider`：未配置 `REA_JADX_MCP_JAR`，要求 jadx-headless-mcp 0.7.1。因此没有声称完成 Java 方法反编译，也没有获取、复制墨墨的调度算法。
- 原始 APK、REA 证据 bundle、doctor 诊断、页面 XML、源码 SHA 清单仅本地保留于 `.codex-fix/momo-research/`，不作为仓库分发内容；页面里含个人学习数据，此文不复制这些数值或词条内容。

## 2. 实际页面观察

ADB 仅打开和查看页面，不提交认识/模糊/忘记评分、不确认设置、不选入/删除词汇。

| 页面/本地 XML | 可确证行为 | 仍不明确的部分 |
| --- | --- | --- |
| 复习 `home.xml` | 单词、读音入口、释义、例句、助记、词形；认识/模糊/忘记按钮附复习时间，部分显示本日和未来时间 | 未提交评分，不能据此还原间隔计算、重学次数或扣费逻辑；当前是已显示答案的界面，首次揭晓流程未验证 |
| 选词 `books.xml` | 每日学习量、待复习/待新学分开、预计完成时间、所选词汇状态、词库分类 | 未更换学习量/记忆模式或词库；预计完成时间公式未知 |
| 每日学习量 `daily-load.xml` | 数值配置弹窗；页面说明学习量含复习和新学 | 打开后退出，未保存；新旧额度实际分配算法未知 |
| 已选单词 `selected-words.xml` / `stubborn.xml` | 全部、今日、顽固、熟知、暂停、未按规划、不想背筛选；顽固列表有相应排序说明 | 顽固/熟知阈值、暂停后排程细节未知，不能冒充墨墨定义 |
| 统计 `statistics.xml` | 遗忘曲线及解释入口 | 图形不可仅凭无障碍树恢复全部坐标/公式 |
| 学习情况 `study-statistics.xml` | 认知情况/复习新学切换；日/周/月；各评分分布、待学、时长和积压提示 | 时长采集与防挂机规则未知 |
| 记忆持久度 `retention.xml` | 按10/30/60/90天分段展示覆盖量与比例；日/周/月 | 对方持久度内部计算未知；不能将 FSRS 稳定性当作等价变量 |

## 3. 当前程序的真实情况

`docs/MEMORY.md` 含旧抽取方式/旧评分说明，不能直接当实现依据。当前源码才是此次设计基准；实施前再次核对 SHA 清单，避免覆盖并行修改。

- `MemoryActivity.grade` → `MemoryStore.rateFlashcard` → `MemoryData.rateFlashcard` → `applyRating` → `MemoryScheduler.next` → `MemoryFlashcards.complete` → `MemoryStore.save`。先持久化再继续视觉流程。
- `MemoryFlashcards` 已按到期卡的遗忘风险排序，新卡按入库顺序补位并受新学上限限制；同卡每天一次，不让遗忘卡在本日闪卡反复出现。
- `MemoryActivity` 当前只有认识/忘了两档有效评分；点击揭晓也有既定评分与防重复语义。不能照搬对方三按钮而静默恢复旧评分档位。
- `MemoryScheduler` 已有 FSRS-6、目标记忆率、学习/复习/重学状态；`MemoryData.applyRating` 另将按天到期时间归到本地零点，预览必须复用这一语义。
- `MemoryStats` 已有摘要、近日日复习量、7日到期预测、按真实评分日志估计的留存曲线与记忆强度构成。
- `MemoryData` 已存 `reps/lapses/stability/difficulty/last/due`；`MemoryStore` 已用 AtomicFile 保存 `memory-v1.json`，备份/导入均已有边界校验。
- 现有学习目标完成时，`MemoryActivity.grade` 保留显式的记忆目标完成联动，并可能跳转打卡页展示记忆卡完成。此次只借用这一既有通知边界，不把普通打卡记录拿来当记忆评分或训练数据。

## 4. 调查结论

第一批可全部基于已有数据做派生计算，无需复制墨墨词库、素材或专有算法。最有价值的是复习时间预览、待复习/待新学分解、难记卡定位、统计时间范围与稳定性分布。三档评分、同日反复巩固、暂停/熟知等会改变学习或存储语义，另列后续决策，不在首批静默加入。
