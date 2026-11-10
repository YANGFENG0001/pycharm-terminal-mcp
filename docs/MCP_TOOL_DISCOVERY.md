# MCP 工具不可发现：0.1.44 到 0.1.45

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
