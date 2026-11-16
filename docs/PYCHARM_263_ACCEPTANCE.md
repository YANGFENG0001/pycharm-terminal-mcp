# PyCharm 263.7307.206 适配与 MCP 连接诊断

日期：2026-11-16。源码/构建版本：0.1.51。当前用户 IDE 仍运行 0.1.49；本轮未安装、覆盖或重启用户的插件。

## 现场证据

- 实际安装目录为 `D:/PyCharm 2026.2.0.1`，但 `build.txt` 是 `PY-263.7307.206`。不能用目录名称判断版本。
- `PyCharm2026.3/log/idea.log` 的 03:15:05 启动记录包含旧 ID `com.mcob.terminal-mcp-bridge`、0.1.49，以及内置 MCP Server 263.7307.206。
- 03:16:45 的 `AcpMcpServerProvider` 明确记录：`no project-scoped MCP servers: source project .../pycharm-terminal-mcp is not open`。
- 只读协议连接发现当前 IDE 已打开的是 `D:/Projects/example`。不提供路由时，能力调用返回 `Unable to determine the target project`；明确提供已打开的示例项目后，原始能力返回 `pluginVersion=0.1.49`。
- 现场实际监听地址上的 initialize 和 tools/list 成功，发现 26 个桥接工具及宿主 execute_tool，共 27 个。能力与设置读取均成功。连接使用本次系统监听/协议结果，不将历史端口写死在插件中。
- 源码原来改用了 `dev.terminalmcp.bridge`，与实际安装的旧 ID 不同。0.1.51 恢复旧 ID，防止被当成第二个同名插件；没有删除用户插件或改动设置文件。

结论：此时不能称“0.1.49 MCP 完全没有启动”。已确认的 ACP 阻塞是会话项目未在 IDE 打开，另有宿主路由要求。未取得同一时刻 ACP 的 502 请求链路证据，不把此前的 502、Already initialized 全部归为这个原因，也不声称插件代码可修复任意客户端代理或 ACP 生命周期问题。

## 0.1.51 改动

- 精确使用本机 263.7307.206 SDK 构建，声明 263.7307.206..263.*；可通过 `pycharmLocalPath` 指定 SDK。
- 原位升级 ID、版本读取保持一致；持久化设置名和文件不变。
- 中文连接检查显示构建、插件版本、MCP 开关、实际监听、26 项声明和打开项目；不冒充协议调用结果。
- 配置导出读取 IDE 实际 Streamable HTTP URL，添加已打开项目的 `IJ_MCP_SERVER_PROJECT_PATH` 请求头；没有监听时拒绝导出。不复制密码、token 或其他服务器配置，不自动启用服务。
- MCP 相关菜单只在内置 MCP 插件可用时加载；26 个工具、远程传输、授权、上传范围均未改动。

## 构建与回归

执行完整命令（最终成功运行没有跳过索引任务）：

```powershell
$env:JAVA_HOME='D:/PyCharm 2026.2.0.1/jbr'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=%CD%/build'
.\gradlew.bat check buildPlugin '-PpycharmLocalPath=D:/PyCharm 2026.2.0.1'
```

结果：`BUILD SUCCESSFUL`，包括 buildSearchableOptions（322 项设置）与 jarSearchableOptions。

8 组回归入口：连接报告/配置转义、指定文件路径、部分上传字段、SSH 终端准备、上传范围、endpoint 选择、POSIX 执行、安装包隔离类加载。隔离检查验证原始插件 ID、263 范围、全部 26 个工具签名/序列化描述符、显式空退出码及不重复打包 Kotlin 运行库。它仍不等于真实 IDE 完整工具执行验收。

只读诊断脚本使用本轮实际 URL、已打开的示例项目和 `-ExpectedVersion 0.1.49` 验证现场旧插件：27 项发现、capabilities/settings/projectRouting 均通过；默认期望版本仍为新包 0.1.51，不把旧包成功写成新版验收。

## 安装后待验收

1. 从磁盘安装 0.1.51 ZIP 后重启；如存在独立 0.1.50 同名副本，先在插件设置中移除它。
2. 在目标 PyCharm 中打开与 ACP 会话一致的项目，使用中文连接检查，再新建/重新连接 ACP 会话。
3. 能力和设置读取成功且版本为 0.1.51 后，再做只读终端发现。不得改用普通 SSH 假装 MCP 通过。
4. 脚本直连通过仍需分别验证真实 ACP/外部客户端；远程终端创建、Deployment、server-relay、大文件传输本轮没有测试。

本轮没有执行远程命令、创建远程终端、上传文件、修改密钥、读取 PasswordSafe、修改 `.idea/deployment.xml` 或启动训练。
