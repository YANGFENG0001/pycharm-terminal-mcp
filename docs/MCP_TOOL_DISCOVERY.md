# MCP 工具发现与连接排查

## PyCharm 2026.3 / 263：先区分四个层次

1. **安装**：插件是否启用，版本与 ID 是否正确？0.1.51 沿用原 ID `com.mcob.terminal-mcp-bridge`。如同时存在 0.1.50 的 `dev.terminalmcp.bridge`，在插件设置中移除该同名副本。
2. **服务监听**：内置 MCP 开关开启不等于监听已经成功。使用“工具 → 检查终端 MCP 连接”查看实际监听地址，不猜端口；界面中的 26 项为工具声明数量，不是协议成功证明。
3. **会话接入**：ACP 项目级自动注入依赖 IDE 已打开的会话项目。0.1.52 另外支持由用户添加到客户端的全局跟随连接，不要求打开聊天目录。添加并启用全局连接后新建会话；仅安装插件不会自动配置任意客户端。不能靠重复启动远程 SSH 修复工具缺失。
4. **调用路由**：263 宿主在工具函数之前解析项目。全局跟随连接通过 `IJ_MCP_SERVER_PROJECT_PATH: terminal-mcp:current-project` 显式选择跟随模式；每次调用使用当前/最近激活的 IDE 项目，没有焦点但只有一个项目时使用它。多个项目无法确定当前窗口、没有项目、显式项目与当前项目不匹配均拒绝，不回退。其他连接委托原生解析器，保留固定项目路由。工具实现使用宿主本次调用上下文固定项目；切换只影响下一次调用。

“工具 → 复制终端 MCP 连接配置”提供 Air/ACP JSON 与 Codex TOML，使用实际 Streamable HTTP URL 和跟随模式头，不写死项目或服务器。用户合并到全局 MCP 配置、启用并重新连接，不覆盖已有服务。该菜单不启用服务、不修改授权、不复制令牌。Air 配置只是连接导出，实际客户端接入须单独验收。

0.1.53 起，两个入口同时出现在“设置 → 工具 → 终端 MCP 桥接”顶部；内置 MCP Server 未加载时按钮显示中文说明，不绕过开关。调用工具前先关闭设置、SSH 连接、确认等模态弹窗。全局跟随及固定项目路由会及时拒绝新的请求，不等待弹窗关闭后自动补发；这不取消既有操作。此类宿主项目解析错误不属于插件业务 JSON。真实安装/客户端/传输验收分别记录在 [0.1.53 实测](MCP_053_LIVE_ACCEPTANCE.md)。

跟随模式下，AI 不应传旧项目或聊天目录作为 `projectPath`；省略或传 `terminal-mcp:current-project`。显式 `project_selector` 必须匹配本次项目，能力返回 `projectPath`；切换项目后重新发现服务器/终端。旧 ID 不会被映射成新项目 ID。已开始的操作不会被改派到另一项目。

HTTP 502 是连接链路错误，不能据此断言插件类加载失败。检查实际地址、端口、监听和客户端代理，回环地址应绕过代理；不要自动改全局代理或猜认证令牌。若 `tools/list` 已返回桥接工具而调用提示无法确定项目，应先修复路由。

只读协议诊断脚本（PowerShell 7，地址必须来自本次 IDE 诊断，不要照抄历史端口）：

```powershell
.\scripts\Test-McpConnection.ps1 `
  -ServerUrl 'http://127.0.0.1:<actual-port>/stream' `
  -FollowCurrentProject `
  -ExpectedProjectPath 'D:/path/to/current-project' `
  -ExpectedVersion '0.1.53'
```

脚本依次执行 initialize、tools/list、能力与设置查询；不执行终端命令、不创建终端、不传输文件、不读取凭据、不改设置。版本不符或调用失败即停止，不自动重试。它绕过本机代理，因而成功不能证明原 ACP 客户端代理配置正确，也不替代 ACP 新会话验收。已发现的 263 问题与构建证据见 [PYCHARM_263_ACCEPTANCE.md](PYCHARM_263_ACCEPTANCE.md)。

## 历史：0.1.44 到 0.1.45

## 已确认原因

2026-10-08 的本地 PyCharm 日志记录了：

```text
Failed to create tools for toolset dev.terminalmcp.TerminalMcpToolset
Could not initialize class dev.terminalmcp.TerminalExecutionResultSerializer
LinkageError: loader constraint violation
IntCompanionObject
```

已安装的 0.1.44 包中包含 kotlin-stdlib-2.4.10.jar。序列化库由 IDE 类加载器提供，而 Kotlin 标准库由插件类加载器提供，序列化器初始化时发生类型身份冲突。工具集注册失败后，内置 IDE 工具仍可用，但 Terminal MCP Bridge 工具不在可用列表中。此问题不是服务器连通性、Deployment 权限或终端选择错误。

## 0.1.45 修复

- 禁用 Kotlin 标准库默认依赖，不在安装包内重复打包 IDE 运行库。
- 保留专用结果序列化器，未完成分支仍输出明确的 JSON null。
- 本地回归检查最终安装包，并以隔离类加载器初始化序列化器、读取描述符及验证空值输出。

该回归模拟运行库类加载隔离，不等于真实 IDE MCP 注册验收。未自动替换运行中的插件，也未更改 MCP 客户端配置。

## 安装后的最小复测

将以下指令交给 AI：

请只验收工具发现和终端执行返回。先调用 get_terminal_mcp_capabilities，确认 pluginVersion=0.1.45。若工具不存在，立即停止，不用其他终端工具冒充结果，保留原始错误及可用工具列表，并检查本次 PyCharm 启动后的 idea.log 中是否仍有 TerminalMcpToolset、TerminalExecutionResultSerializer 或 LinkageError。

发现通过后，按 TERMINAL_EXECUTION_ACCEPTANCE.md 执行定向回归。仅发送、超时、忙碌拒绝和参数错误的原始返回必须明确包含 exit_code:null；完成分支返回数字退出码。只使用本轮发现的终端 ID，不上传项目、不改 Deployment、不操作密钥、不启动训练。
