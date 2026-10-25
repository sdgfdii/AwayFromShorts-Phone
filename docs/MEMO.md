# Android 备忘录

1.48.0（2134），2026-10-25。入口：首页「功能」三个图标格里的「备忘录」，与「目标管理」「打卡历史」并列。

| 能做什么 | 说明 |
| --- | --- |
| 新建 | 右上角「＋」；标题可留空，列表用正文第一行顶上 |
| 编辑 | 点一条进入编辑页；点「完成」或按返回键都会保存 |
| 置顶 | 长按一条 →「置顶 / 取消置顶」；置顶的排在最前，带「置顶」胶囊 |
| 搜索 | 右上角「⌕」打开搜索框，标题和正文都参与，边打边筛 |
| 删除 | 卡片右下角的垃圾桶图标，编辑页顶栏也有一个；点了先确认。长按菜单里同样保留「删除」 |

列表顺序是**置顶优先，其余按最近修改倒序**；每条显示标题、两行摘要和相对时间（刚刚 / N分钟前 / 今天 HH:mm / 昨天 HH:mm / M月d日 / yyyy年M月d日）。一条都没写时是空状态卡片，不会出现空白页。

## 数据存在哪

- 存在本 App 私有的 SharedPreferences（`afs_guard` / `memos_v1`），卸载即清空，不写外部存储、不联网、不同步 Gist，也不进打卡或学习数据。
- 备份/迁移：本项目没有导出入口；换机前请自行备份应用数据（`adb` 或系统备份工具），本功能不承诺迁移能力。
- 上限：最多 500 条，单条正文 2 万字符、标题 80 字符；超出上限时先丢列表最后一条（优先级最低的那条）。

## 实现

| 文件 | 说明 |
| --- | --- |
| `dpc/java/com/afs/guard/MemoStore.java` | 纯函数（排序 / 过滤 / 摘要 / 时间 / JSON / 增改删置顶）+ 偏好读写包装；写操作同步，坏 JSON 降级为空列表 |
| `dpc/java/com/afs/guard/MemoActivity.java` | 列表页与编辑页（同一个 Activity：带 `id` 是编辑已有条目，带 `new=true` 是新建），全部走 `Ui` 的主题色，深浅色 / 纯黑 / 高德风格自动跟随 |
| `dpc/test/java/com/afs/guard/MemoTest.java` | 63 项离线断言：排序稳定性、检索、标题回退、摘要折叠、六档时间、JSON 容错与重复 id、Unicode 空白、空保存幂等、上限与清空边界 |

不新增权限，不注册闹钟或前台服务，不改冻结规则。全角空格、不换行空格、零宽字符这种"看起来像空白"的输入不算内容，不会留下空条目，也不会把已有条目清空。

## 验证

- 离线：`bash dpc/test/run.sh`（10 组）；本机没有 Git Bash 时用
  `powershell -NoProfile -ExecutionPolicy Bypass -File .codex-fix/memo/check.ps1`，等价于同一套 javac + 纯函数测试。
  `MemoTest` 63 项通过，其余 9 组（Guard 207 / FreezeRule / LedgerMerge 9 / Memory 91 / Memory flashcards 50 /
  Freeze slots 18 / Quiz 82 / Dock springs 126 / Appearance 30）无回归。
- 编译：`javac --release 8` 对 `android.jar`（platforms;android-34）零错误；APK 用 `dpc/build.sh` 同一套手工步骤
  （aapt2 → javac → d8 → zipalign → apksigner）构建，`apksigner verify` 通过，版本 1.48.0 / 2134。
  验收用的 Windows 机器没有 Git Bash，所以用 `.codex-fix/memo/build-apk.ps1` 复刻了同样的步骤（脚本只在本机草稿目录，不入库）。
- **未做设备验证**：验收时机器上连着 OnePlus PLR110（Android 16），但机上是 1.47.3（不含备忘录），
  本次没有覆盖安装，所以列表/编辑页在真机上的键盘弹出、转屏、深浅色实测不在本次范围内。
  装到手机上后建议先试：新建 → 返回是否自动保存 → 长按置顶 → 搜索 → 删除确认。

设备验收可用 `dpc/test/run-device.ps1` 那套工具补，但需要一台连着 adb 的机器。
