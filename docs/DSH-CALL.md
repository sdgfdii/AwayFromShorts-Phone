# DSH 调用入口

## 2026-10-27 更新：已注册 MCP
当前正式入口为 Codex 的 `dsh` MCP，源码与说明：`C:\Users\nj\Desktop\空间\codex-dsh-mcp\README.md`。
提供 dsh_start、dsh_status、dsh_result、dsh_resume、dsh_cancel；Codex 规划和核验，DSH 实现。
当前桌面为 `D:\learn\DSH Desktop`，数据目录为 `%APPDATA%\dsh-desktop\harness`；昨天的官方桌面安装已移除，下面旧脚本的安装路径不再有效。
当前已配置路由的真实调用返回 402 / Insufficient Balance。MCP 协议与离线回归通过，但模型执行需先恢复 DeepSeek 额度；不能把注册成功当作任务成功。
R01/R02 已有 `DSH-RESULT.md`，但原运行的最终状态为 error/exit 1，不能直接标记验收通过；后续由 Codex 核对代码及构建证据、执行设备验收。

以下保留 2026-10-26 的旧命令行入口记录。

已使用本机 DeepSeek Harness Desktop 自带 CLI 接通 DeepSeek。
独立运行配置：`%USERPROFILE%\.dsh\profiles\codex-handoff\cordis.patch.yml`。
模型路由为 `deepseek-account / deepseek-flash`，推理档位 high，复用桌面版保存的登录状态。配置中不包含密钥。

## 调用
在项目根目录使用 PowerShell：

```powershell
# 只做连通性检查，不调用工具
./scripts/invoke-dsh.ps1 -Check

# 将已准备好的 R01/R02 修正任务交给 DS
./scripts/invoke-dsh.ps1 -Handoff

# 传入其他任务文件；UTF-8 文件通过 stdin 发送
./scripts/invoke-dsh.ps1 -TaskFile ./docs/my-task.md

# 在同一目录续接返回的 DSH 会话
./scripts/invoke-dsh.ps1 -SessionId 'session-返回的ID' -Prompt '按审查记录修正，并更新交接结果。'
```

一次只选择 Check、Handoff、TaskFile、Prompt 中的一项。不要同时驱动同一个 DSH 会话。
调用真实的 DS 会消耗当前 DeepSeek 账号额度。检查不是离线模拟。

## 结果与失败
每次运行输出 Session ID，并保存 `.codex-fix/dsh-handoff/` 下的 JSON 事件流、诊断和结果 JSON。
脚本同时检查进程退出码与本轮 `turn_end.reason.kind=completed`；失败会抛出错误，不能把空回答或中断当作完成。完成状态仍需结合 DS 的交接报告判断任务是否完成。
诊断行独立保存，避免 Windows 桌面 CLI 的模型目录诊断混入 JSON 解析。

CLI 入口：`%LOCALAPPDATA%\Programs\DeepSeek Harness\resources\runtime\cli\bin\dsh.cmd`。
请勿改用 `%USERPROFILE%\.dsh-runtime\dsh` 中的旧 0.1.0-rc.7 CLI：它与当前凭据文件格式不兼容。
如果出现 ACCOUNT_SIGN_IN_REQUIRED，在 DSH 桌面程序重新登录；脚本不会要求把令牌粘进聊天。

## 交接约定
Astra 整理任务和约束 → 此脚本派发 DS → DS 修改源码、验证并写 `DSH-RESULT.md` → Astra 审查和设备验收。
本轮任务正文在 `DSH-IMPLEMENTATION-TASK.md`，限制了修改范围、禁止重置既有改动和操作用户手机数据。

使用了官方 headless 的 stdin、JSON 事件和 session resume 能力，及官方文档允许的保存登录恢复配置：
- https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/bundle/headless/README.md
- https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/llm/llm-deepseek-account/README.md
