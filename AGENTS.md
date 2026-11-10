# AGENTS.md — PyCharm Terminal MCP Bridge

本仓库是 **PyCharm Terminal MCP Bridge** 插件源码（独立仓库，本文件仅约束本仓库）。

跨项目复用的 skill 入口：[`skills/pycharm-terminal-mcp/SKILL.md`](skills/pycharm-terminal-mcp/SKILL.md)。整个目录可复制到目标 agent 的 skills 目录；不依赖固定项目路径或固定服务器。修改插件接口时同时维护该 skill 与 references。

## 给 AI / Codex 会话的强制阅读顺序

1. **先读 [`docs/AI_USAGE_GUIDE.md`](docs/AI_USAGE_GUIDE.md)。**
   这是面向 AI 的唯一权威使用教程与工具契约：会话开场流程、全部 26 个 MCP 工具的
   参数与语义、权限/设置开关、标准工作流、失败判定、审计与安全红线。
   需要操作插件（列终端、执行命令、上传/下载/同步、跨服务器中转等）时，
   必须先读完该文档再动手。
2. 再读本文件剩余部分（硬规则）。
3. 需要发布/安装/构建说明时读 [`README.md`](README.md) 与 [`docs/INSTALL.md`](docs/INSTALL.md)。

> 唯一权威来源优先级：插件源码 `src/main/kotlin/dev/terminalmcp/TerminalMcpToolset.kt`
> 中的 `@McpDescription` 与函数签名 > `docs/AI_USAGE_GUIDE.md` > `README.md`。
> 三者若冲突，以源码为准，并同步修正文档。

## 每次会话开场（必做）

1. `get_terminal_mcp_capabilities` — 确认运行时 `pluginVersion`、暴露的工具列表、运行模式。
2. `get_terminal_mcp_settings` — 读取 `auditLogPath`、`permissionMode`、各类 `allow*` /
   `requireConfirm*` 开关的当前值。

版本不符合预期、或所需工具未暴露时**立即停止并报告**，不要用其他接口替代验收。

## 硬规则（不得违反）

- **所有 selector / server id / endpoint id / terminal id 必须来自本次实际发现结果**，
  禁止猜测或复用历史 id。相关工具：`list_global_terminals`、`list_predefined_terminals`、
  `list_remote_servers`、`list_selected_deployment_servers`。不要用 `selected`/`active`/`current`
  作为跨服务器传输的隐式目标，源与目标必须显式指定。
- **禁止修改 `.idea/deployment.xml`**。切换服务器只用 `select_deployment_server`（仅当前会话生效），
  不得改用落盘配置或改默认服务器。涉及该文件的验收必须比对测试前后 SHA-256。
- **禁止处理密码**：不读取、不输出、不猜测、不写入日志/命令行/返回值的任何服务器密码、
  私钥全文或 `authorized_keys` 全文；不读 PyCharm PasswordSafe；不使用 `sshpass`/`SSHPASS`。
  免密配置只走插件 `prepare_server_relay_authentication`。
- **大文件禁用终端备用通道**：不得用 base64 或交互式 SSH 终端搬运大文件，不得用本地 staging
  伪装直传；优先 PyCharm 原生 Deployment、SSH/Eel endpoint 或 `mode="server-relay"`。
  没有可用 endpoint 时**结构化失败**，不得偷偷改用终端通道。
- **等待真实完成**：终端执行检查 `completed/success/exit_code`；server-relay 检查 `success=true, exit_code=0, backend=rsync|scp, mode=server-relay`；原生部分上传检查 `success/serverId/requestedCount/files/skippedPaths`，不要求不存在的 exit_code；
  `Started` / `Invoked` / `pending` / `timeout` 一律不算。超时不自动重试、不重发、不擅自中断。
- **上传成功 ≠ 内容一致**：原生 Deployment 上传成功后，必须另行核对远端文件大小与 SHA-256。
- **不得回退**：endpoint/server 不匹配时必须失败，不得回退到其他服务器、其他 endpoint、
  本地中转或终端备用通道。
- 设置项为 false 时，**不要改配置文件**，而是提示用户在
  `设置 → 工具 → 终端 MCP 桥接` 中开启对应开关。
- 不做超出用户授权范围的写入：不擅自全项目上传、不擅自改密钥/免密、不改训练文件。

## 构建与测试（仅在被要求时）

```powershell
.\gradlew.bat check buildPlugin
```

构建产物：`build/distributions/*.zip`。安装后在 PyCharm 重启，再刷新/新建 MCP 会话。

## 文档维护义务

当工具集、参数、设置项或行为发生变化时，**必须同步更新**：

- `docs/AI_USAGE_GUIDE.md`（工具目录、参数表、工作流、失败判定）
- 本文件中的硬规则与开场流程
- `README.md` 的 “MCP Tools” / “Repository Layout” 等对应章节

各验收文档（`docs/*_ACCEPTANCE.md`）用于记录定向回归证据，不能替代使用教程。
