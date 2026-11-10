# Terminal MCP Bridge — AI 使用教程（权威版）

> 跨项目使用可整体安装 [`../skills/pycharm-terminal-mcp`](../skills/pycharm-terminal-mcp/SKILL.md)，按需加载其中的终端与传输参考；无固定项目或服务器依赖。

> **这份文档是给 AI / 代理读的。** 人类安装说明见 [../README.md](../README.md) 与 [INSTALL.md](INSTALL.md)。
> 目标：新会话的 AI 读完本文即可**不猜测、不试探**地正确使用本插件。
>
> 适用版本：**0.1.50**。若 `pluginVersion` 与本文不同，**以 `get_terminal_mcp_capabilities` 实际返回为准**，并把差异记回本文。

---

## 0. 30 秒速查：我要做什么 → 调用什么

| 目标 | 调用顺序 |
|---|---|
| 看插件能做什么 | `get_terminal_mcp_capabilities` |
| 看权限开关 | `get_terminal_mcp_settings` |
| 找目标服务器 | `list_selected_deployment_servers` (+ `list_remote_servers`) |
| 在远程跑命令 / 核对哈希 | `ensure_saved_ssh_terminal` → `execute_global_terminal_command` |
| 更新少量代码文件 | `list_selected_deployment_servers` → `select_deployment_server` → `upload_selected_files_to_deployment_server` |
| 上传目录 | `upload_to_selected_deployment_server --local_path <目录>` |
| 下载 / 同步 | `invoke_selected_deployment_action --action pull|sync` |
| 服务器之间传大文件 | `transfer_between_deployment_servers --mode server-relay` |
| 服务器之间免密前置 | `prepare_server_relay_authentication` |

---

## 1. 会话开头的固定动作（不要跳过）

```text
1) get_terminal_mcp_capabilities
   → 拿 pluginVersion、terminalTools、deploymentTools、recommendedWorkflow、currentSettings
2) get_terminal_mcp_settings
   → 拿当前权限（本本文第 8 节的开关表）
3) 然后按任务选分支（第 6 节）
```

**不要**在未调用 `get_terminal_mcp_capabilities` 前用 shell 命令替代这些 PyCharm 原生能力。
**不要**凭记忆写服务器 id / 终端 id：所有 selector 必须来自**本轮**发现结果。

---

## 2. 调用约定（宿主 `execute_tool` 形态）

工具以命令行字符串形式调用：

```text
tool_name --arg value --flag true
```

- 布尔用 `true` / `false`。
- **数组与对象必须传合法 JSON，并用单引号包住**：

  ```text
  upload_selected_files_to_deployment_server --project_selector D:/proj --local_paths '["D:/proj/a.txt","D:/proj/nested/b.txt"]'
  ```

  若不加引号，宿主解析层会直接拒绝（`expects a JSON array`）。这属于**宿主参数校验失败**，不是插件业务 JSON，不要把它当成插件缺陷。
- 必填参数缺失 / 类型错误由宿主在调用前拒绝，**插件函数无法返回 JSON**。要判定"插件 API 契约"必须传合法参数。
- 插件业务失败会返回**结构化 JSON**（`success=false` + `message` + `error`），不要用终端回显去补字段。

---

## 3. 核心不变量（违反即判定为错误用法）

1. **跨服务器传输必须显式双端 selector**：`source_server_selector` + `target_server_selector`。禁止用 `selected` / `active` / `current` 当跨服务器目标。
2. **上传 / 同步必须显式路径**（`local_path` 或 `local_paths`）。省略或空值 = 拒绝，且**不会**默认成项目根目录。
3. **全项目上传必须 `allow_full_project=true`**，且**只有用户明确要求**才能声明。它不是"审批开关"。
4. **绝不修改 `.idea/deployment.xml`**；不通过改配置文件切默认服务器。`select_deployment_server` 只改**本会话内存**。
5. **大文件禁止走交互式终端 / base64**。`terminalBackedTransferMaxBytes`（默认 2 MiB）**只限制 terminal-backed 后备通道**；PyCharm Web Deployment、SSH/Eel、`server-relay` 不受该限制。
6. **成功判定要看各工具的结构化契约**：终端执行检查 completed/success/exit_code；server-relay 检查 success=true、exit_code=0、backend=rsync|scp、mode=server-relay；原生部分上传检查 success/serverId/requestedCount/files/skippedPaths，不要求不存在的 exit_code。`Started` / `Invoked` / `pending` / 超时**都不算完成**。
7. **上传成功 ≠ 内容正确**。`success` 只表示"原生调用完成"，必须自行做远端 size + SHA-256 核对。
8. **不读取、不输出、不传输任何密码**。免密只走 `prepare_server_relay_authentication`（由插件内部用 PyCharm 保存的凭据完成）。
9. **不要静默回退**：endpoint / mapping / 服务器不匹配时要结构化失败，不许换一台服务器继续。
10. **等待真实完成**：超时不自动重试、不自动中断；不要为绕过拒绝而构造新参数。

---

## 4. 工具目录（0.1.50，共 26 个）

### 4.1 终端读取 / 控制

| 工具 | 参数 | 说明 |
|---|---|---|
| `list_global_terminals` | — | 列出终端：`id`、`logical_id`、`title`、`projectPath`、`ssh`、`canRead`、`canWrite`、`selected`、`active` |
| `read_global_terminal` | `terminal_id`, `max_lines=200` | 读最近输出；**不是**命令完成判定手段 |
| `send_global_terminal_text` | `terminal_id`, `text`, `press_enter=false` | 原样发文本；交互式输入用 |
| `interrupt_global_terminal` | `terminal_id` | 发送 Ctrl+C (ETX) |
| `execute_global_terminal_command` | `terminal_id`, `command`, `wait_for_completion=true`, `timeout_ms=30000`, `shell="auto"` | **POSIX/SSH 首选**；等待并直接返回 `output`/`exit_code`/`completed`/`timed_out`/`success` |
| `set_terminal_ai_access` | `terminal_id`, `enabled` | 单终端 AI 访问开关 |
| `set_terminal_autopilot_mode` | `enabled` | 全局 autopilot 开关 |

`execute_global_terminal_command` 语义要点：

- 命令在**子 shell** 执行：`exit N` **不会**关闭交互 shell；`cd` / `export` **不持久**。
- `wait_for_completion=false` 只表示"已发送"：`completed=false`、`exit_code=null`，不代表成功。
- 超时后**不要自动重发**；也不要把它当成失败原因去改设置。
- `shell=auto` 只对 SSH POSIX shell 自动包装；本地 POSIX 需显式 `shell="posix"`；未知本地 shell（PowerShell/cmd）**结构化拒绝**而不注入 POSIX 包装。

### 4.2 终端创建 / 就绪

| 工具 | 参数 | 说明 |
|---|---|---|
| `list_predefined_terminals` | `project_selector?` | 列出 PyCharm 已保存终端预设（含 SSH） |
| `create_local_terminal` | `project_selector?`, `title?`, `working_directory?` | 新建本地终端 |
| `create_predefined_terminal` | `selector`, `project_selector?` | 打开唯一匹配预设；**返回只表示动作已触发，不代表认证就绪** |
| `ensure_saved_ssh_terminal` | `server_selector`, `project_selector?`, `timeout_ms=30000` | **首选**：复用或在必要时从预设创建，等待连接 + POSIX 提示符 + 只读 `printf` 探测 |

`ensure_saved_ssh_terminal` 关键点：

- 按**明确保存的 Deployment server** 唯一匹配 SSH user/host/port；**不默认 selected**。
- 返回 `success` / `ready` / `created` / `reused` / `timed_out` / `status` / `serverId` / `presetId` / `terminal.logical_id`。
- **只有 `ready=true` 才表示探测成功**；`created=true, ready=false` 只说明终端建了但没就绪。
- 不读密码、不输密码、不配免密、不上传、不改 selected 服务器、不生成 Eel endpoint。
- 第二次调用同一服务器应返回 `reused=true, created=false`（不新增终端）。
- 空 selector / 不存在 selector 会 `status=recovery_failed` **明确拒绝**，不会连默认服务器。

### 4.3 Deployment 发现 / 选择 / 配置

| 工具 | 参数 | 说明 |
|---|---|---|
| `list_remote_servers` | — | `deploymentServers` / `sshEndpoints` / `webDeploymentTargets` |
| `list_selected_deployment_servers` | `project_selector?` | Web Deployment 目标：`id`、`name`、`host`、`port`、`mappings[{localPath,deployPath,valid}]`、`selected`、`valid` |
| `select_deployment_server` | `server_selector`, `project_selector?` | **只改本会话内存**，返回 `selected`/`previous_server_id`/`scope=session` |
| `list_deployment_configurations` | `project_selector?` | 列出 Deploy to Server 运行配置 |
| `run_deployment_configuration` | `selector`, `project_selector?` | 运行已有部署配置 |

### 4.4 传输

| 工具 | 参数 | 说明 |
|---|---|---|
| `upload_to_selected_deployment_server` | `local_path?`, `project_selector?`, `allow_full_project=false` | 上传文件或**目录（递归）**；缺路径=拒绝 |
| `upload_entire_project_to_deployment_server` | `allow_full_project=false`, `project_selector?` | **仅用户明确要求全量** |
| `upload_selected_files_to_deployment_server` | `local_paths`（必填非空文件列表）, `project_selector?` | **日常更新首选**；只传列出的文件；拒绝目录与项目外文件 |
| `invoke_selected_deployment_action` | `action`, `project_selector?`, `local_path?`, `allow_full_project=false` | `action ∈ upload / upload_to / download / download_from / pull / pull_from / sync / sync_with / compare / compare_with` |
| `transfer_selected_deployment_server` | `local_path?`, `mode="upload"`, `project_selector?`, `delete_missing=false`, `terminal_id?`, `allow_full_project=false` | `mode ∈ upload / sync / download / pull` |
| `transfer_remote_directory` | `endpoint_selector`, `local_path`, `remote_path`, `mode`, `delete_missing=false`, `allow_full_project=false` | 只接受 `sshEndpoints` 里**唯一匹配的真实 endpoint**；`sshEndpoints=[]` 时不可用 |
| `prepare_server_relay_authentication` | `source_server_selector`, `target_server_selector`, `project_selector?` | 免密前置；返回 `ready`/`commands`/`requiresOneTimePassword`/`warnings` |
| `transfer_between_deployment_servers` | `source_server_selector`, `target_server_selector`, `local_path?`, `source_path?`, `target_path?`, `mode="copy"`, `delete_missing=false`, `project_selector?`, `allow_full_project=false` | `mode ∈ copy / sync / server-relay` |

`transfer_between_deployment_servers` 三种模式：

- `copy` / `sync`：按 `local_path` 映射，**必须**显式 `local_path`。
- `server-relay`：在**源服务器**上跑 `rsync`/`scp` 直推目标，**不完整落地本机**；可省略 `local_path`，但必须给 `source_path` + `target_path`；需要源端有 `rsync`/`scp` 且能以 BatchMode 免交互登录目标端。
- 返回 `endpointId` / `endpointName` / `mode` / `localPath` / `remotePath` / `message` / `success` / `exit_code` / `backend` / `error`。

### 4.5 设置

| 工具 | 参数 | 说明 |
|---|---|---|
| `get_terminal_mcp_settings` | — | 读当前有效设置 |
| `get_terminal_mcp_capabilities` | — | 版本 + 工具清单 + 推荐流程 + 当前设置 |

---

## 5. 权限与设置字段

`get_terminal_mcp_settings` 返回的字段（**代码默认值**，实际以运行时返回为准）：

| 字段 | 默认 | 作用 |
|---|---|---|
| `permissionMode` | `safe` | `safe` / `enhanced` / `full` / `unguarded` |
| `terminalScope` | `active` | `active` / `selected` / `all` |
| `autoPilotMode` | `false` | 一键全自动（等价于放开多个开关） |
| `allowSshTerminals` | `true` | 允许 SSH 终端 |
| `requireConfirmForSshInput` | `true` | SSH 输入确认 |
| `requireConfirmForDangerousCommands` | `true` | 危险命令确认 |
| `redactSensitiveOutput` | `true` | 输出脱敏 |
| `auditAiInput` | `true` | 审计 AI 输入 |
| `allowTerminalInput` | `true` | 允许终端原始输入 |
| `allowDeploymentAutomation` | `false` | 允许 Deployment 自动化 |
| `requireConfirmForDeploymentActions` | `true` | 部署动作确认弹窗 |
| `allowRemoteFileTransferAutomation` | `false` | 允许远程文件传输自动化 |
| `allowServerRelayTransfer` | `false` | 允许 **server-relay** 大文件直传 |
| `allowAutomaticRelayAuthSetup` | `false` | 允许自动准备免密（不读密码给 AI） |
| `allowTerminalBackedServerTransfer` | `false` | 允许 **terminal-backed 小文件后备** |
| `terminalBackedTransferMaxBytes` | `2097152` (2 MiB) | **仅**限制 terminal-backed 后备 |
| `allowLocalTerminalCreation` | `false` | 允许创建本地终端 |
| `allowPredefinedTerminalCreation` | `false` | 允许从预设创建终端 |
| `auditLogPath` | — | 审计日志绝对路径 |

**AI 行为准则**：开关为 `false` 时，**不要自己改配置文件**；应告知用户在
`设置 → 工具 → 终端 MCP 桥接` 打开对应项。`set_terminal_autopilot_mode` / `set_terminal_ai_access` 是插件提供的设置工具，仅在用户明确同意时使用。

---

## 6. 标准工作流

### 6.A 只读远程核验（改动前的推荐第一步）

```text
get_terminal_mcp_capabilities            # 版本
get_terminal_mcp_settings                # 权限
list_selected_deployment_servers --project_selector <项目>
ensure_saved_ssh_terminal --server_selector <本轮发现的 server id> --project_selector <项目> --timeout_ms 30000
   → 必须 ready=true；记下 terminal.logical_id
execute_global_terminal_command --terminal_id <logical_id> --command 'hostname; id; pwd; sha256sum <文件>' --wait_for_completion true --timeout_ms 20000
```

- **只读探测 `printf` 不等于版本核对**：必须另跑真实命令（`hostname`/`id`/`pwd`/`sha256sum`/`stat`/版本命令）。
- 文件不存在就**如实报告**，不要为了"核对通过"而上传覆盖。

### 6.B 日常代码更新（少量文件，推荐）

```text
list_selected_deployment_servers --project_selector <项目>   # 拿本轮真实 server id / mapping
select_deployment_server --server_selector <server id> --project_selector <项目>   # 只改 session
ensure_saved_ssh_terminal --server_selector <server id> --project_selector <项目>  # ready=true（若需远端核对）
upload_selected_files_to_deployment_server --project_selector <项目> --local_paths '["<文件1>","<文件2>"]'
execute_global_terminal_command ... 'sha256sum <目标路径>'      # 独立核对
```

成功返回形如：`{"success":true,"serverId":"...","requestedCount":2,"files":[{...}],"skippedPaths":[]}`。
预检失败返回：`files: []` + `skippedPaths` 列出被跳过的原始路径。

### 6.C 目录 / 整项目上传

- 目录：`upload_to_selected_deployment_server --local_path <目录>`（递归）。
- 整项目：只在用户明确要求时 `upload_entire_project_to_deployment_server --allow_full_project true`。
- 上传**不删除**远端额外文件；受 Deployment 映射与排除规则限制。

### 6.D 下载 / 同步

```text
invoke_selected_deployment_action --action pull  --project_selector <项目>   # 下载
invoke_selected_deployment_action --action sync  --project_selector <项目> --local_path <目录>
```

- `delete_missing=true` 才可能删除缺失项；**必须**在用户明确同意下使用，并记录审计。
- `compare` 可能打开 PyCharm 比较界面。

### 6.E 跨服务器传输（含 GB 级）

```text
list_selected_deployment_servers --project_selector <项目>     # 双端 server id
ensure_saved_ssh_terminal --server_selector <源 server id> --project_selector <项目>
prepare_server_relay_authentication --source_server_selector <源> --target_server_selector <目标> --project_selector <项目>
   → 期望 ready=true, commands=[], requiresOneTimePassword=false
transfer_between_deployment_servers --project_selector <项目> \
  --source_server_selector <源> --target_server_selector <目标> \
  --source_path <源远端路径> --target_path <目标远端路径> \
  --mode server-relay --delete_missing false
   → 必须 success=true 且 exit_code=0（backend=rsync|scp）
```

- `sshEndpoints=[]` 时**不要**用 `transfer_remote_directory` 正向传输，也不要把 webdeploy id 当 Eel endpoint。
- 源端缺 `rsync`/`scp`、BatchMode 认证失败、权限不足 → 结构化失败，**保持"不覆盖上传"决策**并报告阻塞原因。
- 传输后务必**双端独立 SHA-256** 核对，并确认本地无完整 staging 副本。

---

## 7. 常见失败与正确处置

| 现象 | 含义 | 正确处置 |
|---|---|---|
| `status=recovery_failed`，"必须唯一匹配已保存的 Deployment 配置" | selector 不存在/不唯一 | 回到 `list_selected_deployment_servers` 取真实 id；**不要**猜 |
| `status=recovery_failed`，"必须明确指定保存的服务器 selector" | 空 selector | 显式传 selector；不会用默认服务器 |
| `skippedPaths` 非空、`files=[]` | 预检失败，未传输 | 修路径；**不要**重试同一失败调用 |
| `success=false` + `error` | 插件业务失败 | 读 `error`/`message` 判因 |
| 宿主报 `expects a JSON array` | 参数形态错误（宿主层） | 用单引号包 JSON；这不是插件缺陷 |
| `timed_out=true` | 未在超时内完成 | 明确报告未完成；**不要**自动重发 |
| `terminal-backed` 大小超限 | 后备通道 2 MiB 限制 | 走 Deployment / SSH-Eel / `server-relay` |
| `sshEndpoints=[]` | 无 Eel endpoint | 跳过 `transfer_remote_directory` 正向；标记未测/阻塞 |

---

## 8. 每次任务收尾的自检清单

- [ ] 版本与工具清单来自 `get_terminal_mcp_capabilities`
- [ ] 所有 selector 都来自**本轮**发现结果
- [ ] 上传/同步有显式路径；未擅自声明 `allow_full_project=true`
- [ ] 传输成功有 `success=true`（+ 适用时数字 `exit_code`）
- [ ] 已做**远端 size + SHA-256** 独立核对
- [ ] 未使用 base64 / 交互式终端 / 本地 staging 传大文件
- [ ] 无服务器回退、未切换 selected 服务器
- [ ] `.idea/deployment.xml` SHA-256 **前后一致**
- [ ] 审计日志无密码 / 私钥 / `authorized_keys` 全文 / `sshpass`
- [ ] 结构化区分：**API 返回** vs **原生操作完成** vs **远端独立校验** vs **未测**

---

## 9. 审计与安全

- 审计日志路径由 `get_terminal_mcp_settings.auditLogPath` 给出；所有 AI 调用都会落审计。
- 常见审计事件类型：`execute`、`select-deployment-server`、`web-deployment-native-upload`、
  `ensure-saved-ssh-terminal`、`create-predefined-terminal`、`web-deployment-server-relay`、
  `web-deployment-server-relay-auth-*`。
- 审计中**不应**出现：真实密码、`password=<值>`、`sshpass`/`SSHPASS`、私钥内容、`authorized_keys` 全文。
- 插件**不绕过** SSH / sudo / 系统 / 远端 / 部署认证，也不抓取凭据。

---

## 10. 文档地图

| 文档 | 用途 |
|---|---|
| [../README.md](../README.md) | 安装、构建、总体说明（人类） |
| [INSTALL.md](INSTALL.md) | 安装细节 |
| [SAVED_SSH_TERMINAL_ACCEPTANCE.md](SAVED_SSH_TERMINAL_ACCEPTANCE.md) | `ensure_saved_ssh_terminal` 验收 |
| [TERMINAL_EXECUTION_ACCEPTANCE.md](TERMINAL_EXECUTION_ACCEPTANCE.md) | 终端执行接口验收 |
| [SELECTED_FILES_ACCEPTANCE.md](SELECTED_FILES_ACCEPTANCE.md) | 部分文件上传验收 |
| [SELECTED_UPLOAD_FIELDS_ACCEPTANCE.md](SELECTED_UPLOAD_FIELDS_ACCEPTANCE.md) | 部分上传字段契约 |
| [UPLOAD_SCOPE_ACCEPTANCE.md](UPLOAD_SCOPE_ACCEPTANCE.md) | 上传范围保护验收 |
| [ENDPOINT_FAILURE_ACCEPTANCE.md](ENDPOINT_FAILURE_ACCEPTANCE.md) | SSH/Eel endpoint 失败契约 |
| [MCP_TOOL_DISCOVERY.md](MCP_TOOL_DISCOVERY.md) | 工具发现要求 |
| [GITHUB.md](GITHUB.md) | 仓库元信息 |

## 11. 文档维护义务

新增 / 改名 / 改参数的 MCP 工具，或新增设置开关时，**必须同步更新本文**（第 4、5 节）以及 `../README.md` 的工具清单。
`src/main/kotlin/dev/terminalmcp/TerminalMcpToolset.kt` 的 `@McpTool` / `@McpDescription` 注释是**工具契约的权威来源**，本文与它冲突时以源码为准并修正本文。
