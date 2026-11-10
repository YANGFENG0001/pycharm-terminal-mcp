# 终端与调用契约

## 调用方式

优先使用宿主直接暴露工具的类型化参数。若宿主实际提供命令字符串形式的 `execute_tool`，根据它的 schema 传命令；该形式的 JSON 数组应保留为一个参数，例如：

```text
upload_selected_files_to_deployment_server --project_selector <PROJECT> --local_paths '["<FILE_A>","<FILE_B>"]'
```

尖括号表示要替换的本轮值，不可直接执行。路径含空格时按宿主解析规则引号；避免把外层命令字符串交给 shell 重新解释。缺必填字段或非法数组通常是宿主参数校验错误（可能为 `isError=true` 文本），不等于插件业务 JSON。保留原始返回，不由客户端合成成功字段。

## 终端准备

服务器先由 `list_selected_deployment_servers(project_selector)` 发现。调用：

```json
{
  "server_selector": "<DISCOVERED_SERVER_ID>",
  "project_selector": "<PROJECT>",
  "timeout_ms": 30000
}
```

工具为 `ensure_saved_ssh_terminal`。只有原始返回 `ready=true` 且 `success=true` 才表示探测成功。记录 `status/created/reused/timed_out/serverId/presetId/terminal.logical_id`。`created=true` 不证明认证成功。此操作可能创建 IDE 终端，但不上传、改 selected 或配置服务器间免密。

“设置只读”指不修改权限开关，“远端只读”指命令不修改远端文件；准备 IDE 终端可能改变本地标签页。若用户还明确禁止创建终端，则仅复用已有终端，无可用终端时报告阻塞。

无预设、多个匹配、权限不足、终端不可读写、连接未就绪或超时按真实 status 报告，不反复创建。允许创建开关关闭时仍可复用已有可读写目标终端；不能改设置绕过。需要手动预设流程时先 `list_predefined_terminals(project_selector)`，再 `create_predefined_terminal(selector=<实际 preset id>, project_selector)`；动作已触发不等于终端就绪。

对已有终端使用 `list_global_terminals` 检查 `projectPath/ssh/canRead/canWrite`，以及唯一匹配的用户/host/port，不依据 active 或相似标题选其他服务器。不向正在训练或交互输入的终端抢发命令。

## 执行

```json
{
  "terminal_id": "<RETURNED_LOGICAL_ID>",
  "command": "hostname; id; pwd",
  "wait_for_completion": true,
  "timeout_ms": 10000,
  "shell": "auto"
}
```

工具为 `execute_global_terminal_command`。SSH POSIX 使用 auto，本地 POSIX 须明确 `shell="posix"`；等待协议不支持 PowerShell/cmd，不能强行注入 POSIX 包装。以运行 schema 为准；0.1.50 超时范围 100..240000 ms。

| 情况 | 原始结构化字段 |
|---|---|
| 完成成功 | `completed=true, success=true, exit_code=0, timed_out=false`，output 属于本次命令 |
| 完成失败 | `completed=true, success=false`，数字非零 exit_code；不因此视为 MCP 调用异常 |
| 仅发送 | `completed=false, success=false, exit_code=null`；不保证同步输出 |
| 等待超时 | `timed_out=true, completed=false, success=false, exit_code=null`；原命令可能继续运行 |

等待执行在子 shell 内：`exit 7` 不应关闭交互 shell；`cd/export` 不持久，跨调用需要每次显式工作目录。原任务尚未确认结束时新等待命令应结构化拒绝，不向原任务注入输入。超时可诊断读取，待实际结束后再继续；不得自动重发 sleep、任务或 Ctrl+C。

`read_global_terminal(terminal_id, max_lines=200)` 用于已授权的只发送核验、超时和故障诊断，不能补填 execute 缺失字段，不保证 cursor 协议或严格增量隔离。`send_global_terminal_text(terminal_id,text,press_enter=false)` 是原始交互输入，不表示完成；`interrupt_global_terminal` 只在明确需要且获授权时使用。

## 只读远程核验

ensure 的 printf 只证明连通，需另跑用户要求的 hostname/id/pwd、版本或文件检查。在 POSIX 中先 `test -f <准确引用路径>`，再 `stat -c '%s' <路径>` 与 `sha256sum <路径>`，用 `&&` 保留缺文件错误。文件不存在就报告，不上传覆盖。仅请求哈希时不读取文件正文；不读取密钥和凭据。

## 验收证据

用户要求 API 验收时，记录每次工具调用和原始结果、调用总数、实际执行或未测项。插件完成字段必须来自该次 API。输出污染（包装回显、旧尾部）按实际观测报告；本地文档检查不算真实 PyCharm 认证或执行验收。
