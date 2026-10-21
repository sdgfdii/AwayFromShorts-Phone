# 备用方案:Shizuku + Termux(不推荐)

仓库根目录的 `guard.sh` / `install.sh` / `boot.sh` 属于这条线 —— 留着但**不建议用**,
因为**每次重启都要连电脑重跑一次**,30 秒内就能破功。

真正要锁住,用 [`dpc/`](../dpc/)(Device Owner)或 [`blocker/`](../blocker/)(无障碍)。

---

## 需要什么

| 工具 | 用途 | 来源 |
|---|---|---|
| **Shizuku** | 拿到 shell 权限(不 root) | GitHub / 应用商店,开源 |
| **Termux** | 跑脚本 | **F-Droid 版**(Play 版太旧,别用) |
| **Termux:Boot** | 开机自启(可选) | F-Droid,跟 Termux 配套 |

## 一次配置

1. 设置 → 关于手机 → 连点「版本号」开开发者选项 → **无线调试**打开
2. 开 Shizuku →「通过无线调试启动」→ 配对 → 启动
3. Termux 里:`pkg update && pkg install termux-api`
4. Shizuku → 右下角「Use rish」→ 按提示把 `rish` 和 `rish_shizuku.dex` 放进 `$HOME`,然后 `chmod +x ~/rish`
5. 把 `guard.sh` / `install.sh` / `boot.sh` 传到手机,Termux 里:

```bash
cd ~/storage/downloads
bash install.sh
~/afs-phone/guard.sh status
~/afs-phone/guard.sh run
```

6. **ColorOS 会杀 Termux 后台**:设置 → 电池 → 应用耗电管理 → Termux → 允许完全后台行为

## 日常

```bash
~/afs-phone/guard.sh status   # 看状态
~/afs-phone/guard.sh allow    # 临时放开
~/afs-phone/guard.sh block    # 立刻禁用
~/afs-phone/guard.log         # 日志
```

时间窗口改 `guard.sh` 顶部的 `ALLOW_START` / `ALLOW_END`(HHMM,支持跨午夜)。

**报 `$'\r': command not found`** = 文件被 Windows 转成了 CRLF:

```bash
sed -i 's/\r$//' ~/afs-phone/*.sh ~/storage/downloads/*.sh
```

## 三个洞

1. **重启后 Shizuku 掉线 → 阻断失效。** 得重新无线调试启动一次。没有 root 就没有真正的开机自启。
2. 关掉无线调试 / 强停 Shizuku / 卸载 Termux,任一个都能在 30 秒内破功。
3. **浏览器里的抖音网页版没被拦。** 这套只管 App。
