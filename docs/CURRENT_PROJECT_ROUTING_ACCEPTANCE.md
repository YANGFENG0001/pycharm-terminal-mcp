# 0.1.52 全局跟随当前项目验收

## 契约

- 使用内置 MCP 服务，无额外监听端口或绕过授权的旁路。
- 仅连接头/参数明确携带 `terminal-mcp:current-project` 才启用跟随；其他连接委托 263 原解析器。
- 在每次调用开始解析当前/最近激活的项目；单项目可直接确定，多项目无明确焦点时拒绝。
- 请求上下文固定本次项目。服务器发现、终端访问及默认项目选择都使用此上下文，不在操作过程中重新查看焦点。显式不匹配项目拒绝；旧服务器/终端 ID 不重定向。
- 能力新增 `projectPath`。客户端需手动添加/启用全局连接；不自动修改 ACP 或 Codex 配置。

## 本地回归范围

`verifyMcpConnection` 覆盖配置转义、Air JSON、Codex TOML、A/B 切换、无项目、多项目歧义、已关闭焦点、显式路径冲突、Windows 路径规范化、ProjectLocator 的请求固定/多项目隔离/关闭拒绝。

`verifyPluginClassloader` 检查安装包中的项目解析器实现是否匹配 263 SDK，以及原有 26 个工具和序列化。构建通过不代表已经安装或完成真实窗口验收。

2026-11-16 使用本机 263.7307.206 SDK 执行完整 `check buildPlugin`：`BUILD SUCCESSFUL`，8 组回归通过，含 buildSearchableOptions（322 个设置项）。产物 `build/distributions/pycharm-terminal-mcp-0.1.52.zip`。PowerShell 诊断脚本已通过语法与互斥路由参数检查；未用现场仍运行的旧包冒充 0.1.52 跟随路由验收。

## 安装后交给 AI

先由用户安装 0.1.52 并重启，启用内置 MCP；在“工具 → 复制终端 MCP 连接配置”导出对应客户端格式，合并到全局 MCP、启用并新建会话。不要使用历史 URL、读取令牌或更改权限。

1. 会话目录保持一个未在 PyCharm 打开的目录。激活项目 A，调用能力、设置，必须版本 0.1.52 且 `projectPath=A`。列服务器和终端，记录本轮结果，不执行命令或写入。
2. 切换/激活项目 B，**同一 MCP 会话**再次调用能力；`projectPath` 必须变为 B。重新列服务器和终端，只能返回本次项目对象。
3. 给只读服务器列表传旧 A 的 `project_selector`，以及给能力调用传旧 A 的宿主 `projectPath`，必须拒绝，不得返回 A 的对象冒充 B。宿主拒绝可以是 MCP isError，不能要求函数未进入时返回业务 JSON。
4. 切回 A，能力返回 A。固定项目连接（不使用跟随标记）不得因本次扩展而自动改成 B。
5. 若能力 `projectPath` 缺失/错误、工具缺失或连接报错，停止并记录原始返回与当次日志；不要以其他 SSH 工具代替。

只读脚本可核对一次当前路由（不能替代同会话真实窗口切换测试）：

```powershell
.\scripts\Test-McpConnection.ps1 -ServerUrl 'http://127.0.0.1:<实际端口>/stream' `
  -FollowCurrentProject -ExpectedProjectPath 'D:/当前项目' -ExpectedVersion '0.1.52'
```

本轮未安装到用户 IDE、未改客户端配置，真实 ACP 全局接入、窗口切换、进行中长任务切换仍待验收。不执行远程命令、上传、免密或 Deployment 配置写入。
