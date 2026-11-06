# 记忆卡 M1 实施交接

日期：2026-11-03。用户已批准首批四项，M0–M5完成。实现：Codex主代理（UI、只读存储、设备验证）及模型子代理；独立静态审查：另一Codex审查子代理。本批未宣称由DS执行，也未宣称调用了专用Astra模型。根目录既有R2插件文档与隔离副本保留，没有混入本批。

## 已完成

1. 卡面认识/忘了后的时间预览；遗忘揭晓后展示实际落盘due。共用MemoryData.previewRating和真实评分计算，包含原有本地零点归整。
2. 今日队列内待复习、待新学、已处理、入队数量、总/新学上限及所选学科到期量，标明范围，不另建计数器。
3. 卡管理全部/今日队列/难记/错题筛选、全文搜索、稳定排序、50张分页；难记=lapses≥3，原错题=lapses>0。
4. 自然日/周/月统计（30日/12周/12月）、学科筛选、评分次数与周期去重数、FSRS稳定性5档。保留原7天记住率、未来7天预测、留存曲线、7天稳定性分界和各学科到期清单。

本批不新增依赖、数据库字段、后台定时任务或网络能力。原MemoryScheduler、MemorySwipe和MemoryActivity.grade方法与本轮基线逐字节一致；既有评分/有效目标/主动完成联动仍走原路径。浏览入口不调用评分或计划生成。

## 关键文件

- `dpc/java/com/afs/guard/MemoryActivity.java`：上述页面、时间提示、后台单worker/有界待处理队列、页面entry与请求generation、旋转状态恢复。
- `MemoryStore.java`：新增readOnlySnapshot及实际due标量读取；不调用plans/session/save。
- `MemoryData.java`：预演与真实评分共享下一状态推导。
- `MemoryFlashcards.java`：只读Load分解。
- `MemoryStats.java`：自然桶、稳定性分布及按学科过滤重载，旧API仍兼容。
- 新`MemoryCardQuery.java`：ID分页、难记/今日/错题和搜索。
- 新`MemoryMomoModelTest.java`、`MemoryMomoDeviceTest.java`、`MemoryMomoManifest.xml`、`run-memory-momo-device.ps1`；更新run.sh和英文翻译。
- `docs/MEMORY.md`增加本批入口，并提示其余文档含旧评分/抽取历史描述。

根工作区原有其他dirty修改已保留，不能将整个Git diff当作M1变更，也不能通过回滚整个MemoryActivity丢失原有语音、动效等工作。

## 实际验证

基线：`.codex-fix/memory-momo-impl-baseline/`保存8个原记忆文件、hash清单、原dirty状态与原全离线结果。最终日志也在同目录。

- `bash dpc/test/run.sh`：完整回归退出0。记忆189、闪卡34、原统计33、滑动17、新模型53项通过；翻译5439项通过，其他原有套件亦通过。
- `bash dpc/build.sh`：退出0，构建与签名校验成功；未覆盖根afs-guard.apk，输出到本批独立目录。
- `dpc/test/run-memory-momo-device.ps1 -Serial emulator-5592 -Python <python>`：Android24，23项通过。
- 同命令`-Serial emulator-5590`：Android34，23项通过。
- 设备检查覆盖：只读snapshot、统计/筛选前后文件与revision不变、没有生成今日队列、50张分页、Activity重建后第2页保持、难记阈值、无队列状态、预览与负荷显示、普通打卡和冻结规则不变；深浅色、1.4倍字体、减少动态效果及预览区域可见。
- 30000卡设备模型查询+周期计算：API24 156ms，API34 449ms。测试进程当时Java堆分别32/34MiB，PSS52/75MiB。这些是含fixture的测试进程读数，不是正式手机空闲占用，也不是UI帧耗时或电量测量；未做耗电定量结论。
- 模拟器截图`preview-api24.png` / `preview-api34.png`已查看，新增信息可见，API24正常底栏未遮挡预览。
- `check-boundaries.py`验证原评分/目标完成联动方法、FSRS与滑动规则未改变；`git diff --check`通过。

测试过程中修正：旧debounce修改新页面offset、旧Spinner回调干扰新查询、初始化Spinner清0分页、实例重建丢状态；另修正测试View树非主线程读取及API24重建后取到旧Activity的问题。最终设备结果来自修正后的驱动与最终APK，不沿用初次失败日志。

## 制品

本机测试包：`dist/memory-momo-preview/afs-guard-1.86.0-memory.apk`。

- 包：com.afs.guard；版本1.86.0 / 2270，沿用当前工作区版本号；minSdk24 / targetSdk34。
- SHA256：`635edc01fdb521e202b403b4f2b93ce8d63007ac07bc292dd406b5b772f18aea`。
- 使用既有开发证书，是本地测试包，未作为正式发行签名包发布，也未上传GitHub。
- 实体手机没有安装本批包或注入测试评分、卡片、奖励。所有合成fixture只在本任务自己的模拟器上运行，成功路径finally恢复测试开始时的记忆主文件/备份与外观配置。

## 回退与限制

- 数据格式未改变；移除新增UI入口即可回到旧展示，不需要还原记忆库、删除学习日志或修改冻结规则。
- 若需完整回退APK，在独立副本中以本轮基线恢复8个记忆文件及对应新增入口，再以同签名、较高versionCode构建回退包；不要卸载宿主、清数据或覆盖当前dirty工作树。
- 同日反复巩固、三档评分、暂停/熟知状态、音标/例句新字段、词库/音频来源和算法优化器仍不在本批范围。
- 参考墨墨的REA归档与真机观察见EVIDENCE.md。未配置JADX，未反编译或复制其专有排程算法。
- 测得的是两个模拟器与合成数据；真实手机长期使用、具体大图/超长卡面、端到端大日志帧表现仍需真实使用反馈，不能把模型投影耗时说成所有页面无卡顿。
