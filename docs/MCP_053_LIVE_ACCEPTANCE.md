# 0.1.53 修复与真实验收记录

日期：2026-11-16。结论：**构建与本地回归通过；真实 IDE 全功能验收未完成，不得宣称全部通过。**

## 安装重启后的连接复测更新（2026-11-16）

用户已安装新版并重启，并报告客户端连接恢复。随后只读协议复测实际通过：

```text
pluginVersion=0.1.53
projectPath=D:/Projects/example
toolCount=27
capabilities=passed
settings=passed
projectRouting=passed
```

上述结果来自 `scripts/Test-McpConnection.ps1 -FollowCurrentProject -ExpectedVersion 0.1.53`，地址经本次监听检查核对，调用包括 initialize、tools/list、能力与设置；不涉及传输、终端命令、凭据或设置修改。27 项包含 26 个桥接工具及宿主工具。安装日志也确认加载 0.1.53。用户报告的客户端恢复与本次协议检查分别作为证据；本聊天仍未直接暴露桥接工具，不以脚本冒充 AI 客户端调用。

本次对当前 `idea.log` 检索 `HTTP 502`、`Already initialized`、`workspace routing discovery timed out`、`MCP startup failed`、`handshaking with MCP server failed` 均未找到原始异常。不能证明历史错误从未发生，也不能证明代理或重复初始化就是唯一根因。连接当前健康，不再重启或修改代理/权限来追溯旧错误。

若复发，先保留同一时刻的客户端原始错误与 IDE 日志：502 排查监听、实际地址及代理/转发链路；ACP Already initialized 排查该 ACP 连接的初始化与重连状态，不向原连接循环发送 initialize。先停止连续重试，仅重建失败的客户端连接；不自动重发上传或执行命令。仍需原始异常和重现才能决定是宿主/适配器问题还是插件问题。

**本更新仅关闭“新版是否已安装、基础连接是否恢复”的阻塞；不代表模态保护、本地/SSH 终端或文件传输已经完成新版正向验收。以下为安装前记录，保留作排查历史。**

## 版本与范围

- 本轮实际运行的插件是 0.1.52，IDE 为 PY-263.7307.206。0.1.53 已构建，尚未完成安装、重启与运行版本核验。
- 本轮 IDE 打开示例项目，聊天目录为独立插件仓库。未改训练文件、Deployment 映射或权限开关，未做全项目上传。
- 所有服务器/终端选择均从本轮发现获取；服务器不可达不改用其他服务器冒充成功。

## 已复现的问题与修复

1. ACP 日志说明聊天目录项目未打开，因此没有注入项目级 MCP。现有 Codex 连接补入 `IJ_MCP_SERVER_PROJECT_PATH: terminal-mcp:current-project` 与 60 秒启动等待；TOML 解析及目标字段检查通过。未移除原有环境授权头，未读取令牌。**新客户端会话的工具可发现性仍待验收**，协议探测成功不代表客户端已经重新加载。
2. 0.1.52 全局跟随请求在设置/连接弹窗打开时超时；固定项目能力/设置查询曾通过，但随后服务器发现也超时。0.1.53 在两种路由进入工具前以允许模态期间执行的只读检查及时拒绝。该修复不取消已启动任务，也不保证消除调用开始后新弹窗造成的所有阻塞。
3. 保存 SSH 预设动作直接使用模板 presentation 触发 IDE 警告；改为克隆。待新版本运行核验警告不再新增。
4. 中文设置页顶部加入连接复制/检查按钮，使用单列布局；内置 MCP 插件未加载时提示原因，不自动启用或扩大权限。安装包隔离加载检查已验证两个按钮的文字与布局，真实界面仍待检查。
5. 本地终端创建把工作目录/标签参数传反。已从本机 263 SDK 的 `TerminalToolWindowManager.class` 调试参数表确认 `workingDirectory` 为第一参数、`tabName` 为第二参数并修正；运行时工作目录与终端暴露仍待验收。

## 实际运行观察（0.1.52）

- 本次发现的内置服务监听可完成 initialize、tools/list、能力与设置查询。工具总数为 27，包含 26 个桥接工具与宿主 execute_tool。全局跟随能力返回正确的 `projectPath`。
- 发现 Deployment 目标 12 个，其中 3 个 valid；保存的 SSH 预设可见，westc 预设的 sshLike=true。
- `deploymentServers=[]`、`sshEndpoints=[]`、Deployment 运行配置为空；不能据此验收 Eel 正向传输或运行不存在的配置。
- 当时 `list_global_terminals` 为空。对 NODE-B 调用终端准备后，返回 created=true、ready=false、not_ready_timeout；IDE 日志随后确认连接目标 192.0.2.10:2222 超时，而非已确认的密码错误。
- 独立 TCP 连接检查：192.0.2.10:2222、192.0.2.12:22 超时；192.0.2.13:22 可连接。这只是网络连接检查，不证明 SSH 认证或文件传输成功。
- 对 NODE-A 的后续准备调用未取得完整客户端证据，不推断成功、不重发。
- 安全验收脚本在能力/设置之后的 `list_selected_deployment_servers` 超时，立即停止；之后的拒绝与 SSH 执行项均未运行。

原始逐次 RPC 记录保存在本地 `build/reports/mcp-live-052-20261116.json`。该文件记录的是 0.1.52 的受阻运行，不是 0.1.53 验收结果。

## 26 项工具状态

下表仅描述本轮 0.1.52 的真实调用；**所有工具的 0.1.53 运行回归均待安装后复测**。本地签名/序列化测试不能代替正向工具验收。

| 工具 | 本轮运行观察 |
|---|---|
| get_terminal_mcp_capabilities | 通过，实际版本 0.1.52 与项目路径已核验 |
| get_terminal_mcp_settings | 通过，未改设置 |
| list_global_terminals | 返回空列表；不代表可见终端已被完整暴露 |
| list_predefined_terminals | 返回真实 SSH 预设 |
| list_remote_servers | 返回真实发现；没有 Eel endpoint |
| list_selected_deployment_servers | 首次发现通过；后续模态期间超时 |
| list_deployment_configurations | 返回空列表，正向运行不可测 |
| ensure_saved_ssh_terminal | NODE-B 创建尝试后网络超时；未就绪 |
| create_local_terminal | 未取得完整运行证据；参数错误已修复待复测 |
| create_predefined_terminal | 未独立完成该工具正向验收；准备工具内部曾触发创建 |
| read_global_terminal | 未完成正向验收 |
| send_global_terminal_text | 未完成正向验收 |
| interrupt_global_terminal | 未完成正向验收 |
| execute_global_terminal_command | 未完成正向验收 |
| select_deployment_server | 未完成正向验收 |
| run_deployment_configuration | 无可用配置，阻塞 |
| upload_to_selected_deployment_server | 未执行实际上传 |
| upload_entire_project_to_deployment_server | 未执行；不对真实项目做全量上传测试 |
| upload_selected_files_to_deployment_server | 未执行实际上传 |
| invoke_selected_deployment_action | 未执行实际同步 |
| transfer_selected_deployment_server | 未执行实际传输 |
| transfer_remote_directory | 缺少真实 Eel endpoint，正向阻塞 |
| prepare_server_relay_authentication | 未调用，不读取凭据、不擅自改密钥 |
| transfer_between_deployment_servers | 未执行，小文件/大文件直传均未验收 |
| set_terminal_ai_access | 未调用，未改权限 |
| set_terminal_autopilot_mode | 未调用，未改权限 |

## 构建与本地回归

使用本机精确 263 SDK：

```powershell
$env:JAVA_HOME='D:/PyCharm 2026.2.0.1/jbr'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=%CD%/build'
.\gradlew.bat check buildPlugin '-PpycharmLocalPath=D:/PyCharm 2026.2.0.1'
```

最终构建 `BUILD SUCCESSFUL`，27 项任务。8 组回归任务通过：选择路径、选择上传结果、上传范围、保存 SSH 恢复、endpoint 选择/失败、终端执行、连接路由、安装包隔离加载。涵盖 26 个工具签名/序列化、显式 exit_code:null、中文连接按钮、模态拒绝逻辑及 5 条真实 Bash 协议命令；**不是实际 IDE 内的 26 项功能验收**。

仍有 IDE API 弃用与 Gradle 本机库访问警告，构建未失败；不声称所有警告已消除。

安装包：`build/distributions/pycharm-terminal-mcp-0.1.53.zip`。

SHA-256：`55A954FF70991B31C1C6FE90162FAFCFB8D6618B71ECEEFD4D4A0E638F267DD8`。

新增 `scripts/Test-McpSafeAcceptance.ps1`：每次 RPC 后保存原始证据；版本/项目不符、调用失败即停止。默认仅发现、无传输拒绝路径；显式指定唯一可达服务器时才尝试准备 SSH 并运行四条无文件副作用的 POSIX 命令。脚本尚未跑完整套，不能把脚本存在当成验收通过。

## 配置完整性与当前阻塞

示例项目 `.idea/deployment.xml` 测试前后 SHA-256 一致：

```text
D1E999B506F1A3EA0C59BCB5A9D5C6081B2076EB76D552CC88D56ADB1D04AB31
```

本轮未实际上传、同步、传输文件，未修改密钥。电脑控制从截图捕获失败发展为窗口枚举/选择超时；重置控制连接后仍报 service_info 超时，无法确认 IDE 当前界面或执行安装重启。安装/重启确认尚未收到答复。不能用强杀 IDE、覆盖运行中插件目录、读取令牌或绕过确认来替代。

后续顺序：恢复桌面控制并确认安装重启；核验 0.1.53；关闭弹窗验证连接，再打开模态窗口验证及时拒绝与关闭后恢复；在真实新客户端会话验证工具接入；验证本地终端目录与读取、保存 SSH 准备/复用、POSIX 等待/超时/退出码/中断；仅对一次性测试文件做原生部分上传与远端哈希；两台服务器均可达并授权后验收小文件和大文件直传。Eel/部署配置缺失项保留阻塞，不伪造对象或修改映射。
