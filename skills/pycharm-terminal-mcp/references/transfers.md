# Deployment、SSH/Eel 与 server-relay

## 先确定对象和范围

从本轮 `list_selected_deployment_servers(project_selector)` 读取真实 server id、host/port、mapping 的 localPath/deployPath/valid；从 `list_remote_servers` 读取 SSH/Eel endpoint。目标必须与用户请求一致，不能因当前 selected 或教程示例而选择其他服务器。相对文件路径以实际项目根解释，远端路径根据实际 mapping 推导；不能盲目拼接服务器名或历史目录。

权限检查用当前有效设置，不只看保存默认值：`allowRemoteFileTransferAutomation` 控制文件传输；server-relay 还需 `allowServerRelayTransfer`；自动认证需 `allowAutomaticRelayAuthSetup`；预设创建由 `allowPredefinedTerminalCreation` 及终端访问权限控制。`requireConfirmForDeploymentActions=false` 不解除范围保护或赋予修改密钥的授权。

## 原生部分文件上传（首选少量代码更新）

在已授权服务器调用 `select_deployment_server(server_selector, project_selector)`，确认 session 选择后调用：

```json
{
  "project_selector": "<PROJECT>",
  "local_paths": ["<PROJECT_FILE_A>", "<PROJECT_FILE_B>"]
}
```

工具 `upload_selected_files_to_deployment_server`。只列用户指定或本次任务涉及文件，不递归目录，不自动上传所有 Git 改动。检查本地普通文件存在、mapping 有效、远端覆盖风险；无需 Eel endpoint。

0.1.48+ 原始成功返回检查 `success=true`、正确 `serverId`、`requestedCount` 与请求数一致、`files` 含实际尝试文件且各项成功、`skippedPaths=[]`。此返回类型没有 exit_code/backend，不要求客户端补齐。成功表示原生调用完成，受排除规则影响；独立远端校验另算。

空列表应返回 `success=false, requestedCount=0, files=[], skippedPaths=[]`；不存在文件列表应在传输前拒绝，skippedPaths 保留原始请求路径。部分完成后失败按 files/skippedPaths 如实报告，已上传文件不会自动回滚；失败或超时不自动重试。

通过目标 `ensure_saved_ssh_terminal` 的 logical_id 或实际可用原生读取能力核对远端大小、SHA-256；验收选择性上传还要检查未选文件不存在。无法读取时报告“原生调用完成，远端核验未完成”。保留或清理测试文件服从用户要求，不自动删除。

## 目录、全量、下载、同步

| 工具 | 参数与范围 |
|---|---|
| `upload_to_selected_deployment_server` | `project_selector, local_path, allow_full_project=false`；目录为递归上传 |
| `transfer_selected_deployment_server` | `project_selector, local_path, mode="upload"|"sync"|"download"|"pull", delete_missing=false`；可选 terminal_id，不用于绕过目标匹配 |
| `invoke_selected_deployment_action` | `project_selector, action, local_path, allow_full_project=false`；action 支持 upload/upload_to/download/download_from/pull/pull_from/sync/sync_with/compare/compare_with |
| `upload_entire_project_to_deployment_server` | `project_selector, allow_full_project=true`；仅用户另外明确授权全项目时使用 |

上传/同步不允许省略、空或空白路径。根目录（含路径归一化后的别名）需要显式全量声明；不能自动填根路径或把 allow_full_project 当审批开关。下载也应明确任务文件/目录，避免无意扩大覆盖。删除式同步只在明确授权删除时启用 `delete_missing=true`；不是所有 action 都有此参数，以运行 schema 为准。compare 可能打开 IDE 界面。

这些接口的返回类型可能不同；检查各自 success、完成语义和错误。不要对没有 exit_code 的原生动作套用 relay 契约；若只返回 Invoked/Started，则原生完成仍未验证。

## SSH/Eel endpoint

`transfer_remote_directory(endpoint_selector, local_path, remote_path, mode, delete_missing=false, allow_full_project=false)` 只使用本轮 sshEndpoints 的真实唯一 ID。`mode` 按 schema 选择 upload/download/sync。空、空白、未知 selector 或 webdeploy ID 不得连接默认服务器。

`sshEndpoints=[]` 时此接口正向传输阻塞。拒绝项通过不证明 upload/download/sync 完成；不以终端、原生部分上传或 server-relay 冒充本接口验收。用户单独授权另一接口/目标时才执行对应任务，并明确记录改变。

## server-relay：源端 rsync/scp 直推目标

1. 发现不同双端 server id 并核对用户/host/port，显式给 source_path/target_path。普通文件路径不要追加 `/`。只读核验源文件存在、大小和 SHA-256、目标覆盖风险。
2. 确保源 SSH 终端就绪；普通 SSH 就绪不等于源到目标的 BatchMode 就绪。
3. 根据用户授权调用 `prepare_server_relay_authentication(source_server_selector,target_server_selector,project_selector)`。它可能修改 SSH 密钥配置，因此只读核验任务不调用。已免密期望 `ready=true, commands=[], requiresOneTimePassword=false`；未就绪停止传输。插件可在一次性授权后内部使用 IDE 保存凭据安装公钥，密码不得到 AI、终端、返回或日志；拒绝后立即停止，不手动 ssh-copy-id、不执行返回的密码命令、不删除现有私钥。
4. 认证 ready 后调用：

```json
{
  "project_selector": "<PROJECT>",
  "source_server_selector": "<DISCOVERED_SOURCE_ID>",
  "target_server_selector": "<DISCOVERED_TARGET_ID>",
  "source_path": "<SOURCE_REMOTE_FILE>",
  "target_path": "<TARGET_REMOTE_FILE>",
  "mode": "server-relay",
  "delete_missing": false
}
```

工具 `transfer_between_deployment_servers` 默认 mode 是 copy，必须显式覆盖。relay 采用双端远程路径无需 local_path。copy/sync 则需明确 local_path，可能使用本地中转，不用于“禁止本地 staging”的直传要求。

严格 relay API 成功必须原始 JSON 同时有 `success=true, exit_code=0, backend=rsync|scp, mode=server-relay`。缺字段就判 API 未通过，不用终端可见文件补填；若版本还返回 localStaging，检查其为 false，缺少时不能自行声称该字段存在。

完成后双端核验 size/SHA-256 与源文件未变化。结合返回、审计及实际可观察本地临时文件确认无完整 staging；检查范围不足时明确证据限制。缺工具、BatchMode 255、known_hosts、权限或连接错误报告真实原因，不重试密码，不降级为 copy/sync、本地 staging 或交互式终端传输。

## 审计与完整性

验收前记录 `get_terminal_mcp_settings.auditLogPath` 的长度/时间及 `.idea/deployment.xml` SHA-256（无文件则记录不存在，不创建）。结束比较哈希，只检查本轮审计增量；日志不可读时报告未完成，不为日志修改权限。

常见事件：`ensure-saved-ssh-terminal`、`create-predefined-terminal`、`execute`、`select-deployment-server`、`web-deployment-native-upload`、`web-deployment-server-relay-auth-consent-requested` / `-completed` / `-consent-denied`、`web-deployment-server-relay`。以实际事件为准，不能假定所有发现调用都有审计。`passwordRead=true` 是插件内部操作标志，不是密码泄露证据。

审计结果只输出必要事件、时间、后端、状态和脱敏错误，不转储可能含敏感数据的日志。不得读取实际密码作为扫描样本。检查密码赋值、sshpass/SSHPASS、私钥块及授权文件正文的可见迹象；通过模式扫描只能报告未发现泄露迹象，不能证明任意密码绝无出现。

大文件禁交互终端/base64；终端 fallback 的默认 2097152 字节上限不限制原生 Deployment/SSH-Eel/relay。请求指定后端时不得静默 fallback、改服务器或 endpoint。没有审计事件不能独立证明远端完全无变化，按可用只读能力核验。
