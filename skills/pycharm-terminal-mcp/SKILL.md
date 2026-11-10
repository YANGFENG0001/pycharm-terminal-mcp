---
name: pycharm-terminal-mcp
description: Use when operating PyCharm Terminal MCP Bridge for saved SSH terminals, remote command execution, native Deployment partial uploads, SSH/Eel transfers, server-relay, or plugin API acceptance. 适用于 PyCharm 终端、远程只读核验、指定文件上传和服务器直传；不用于通用 SSH 或训练调度。
---

# PyCharm Terminal MCP Bridge

跨项目使用 IDE 已保存的服务器、终端和 Deployment 映射。本文根据 0.1.50 接口整理；运行版本与参数以本次 MCP 能力和工具 schema 为准，不固定 IDE 安装位置、项目路径、服务器或 MCP provider 名称。

## 开场与发现

1. 在当前宿主可用工具中发现 `get_terminal_mcp_capabilities`，首先调用它，再调用 `get_terminal_mcp_settings`。记录运行版本、工具列表、当前权限。用户指定精确版本时必须匹配；未指定时不强制旧版本。
2. 只使用宿主实际暴露的桥接工具。宿主仅提供通用 `execute_tool` 时，按其 schema 调用桥接工具；不得猜 provider、参数结构、端口或私有 HTTP 地址。缺少所需桥接工具时停止该操作，报告原始发现/错误；不能以普通 SSH 或其他工具冒充插件验收。
3. 从用户任务和本次 IDE 发现结果确定 `project_selector`。服务器任务调用 `list_selected_deployment_servers(project_selector)` 和按需 `list_remote_servers`；核对目标用户、host、port、有效 mapping。使用本轮真实完整 server id；服务器不明确时先发现，写入前明确目标与范围。
4. 设置只读。开关未允许且无有效自动模式授权时报告受限，提示用户在“设置 → 工具 → 终端 MCP 桥接”启用；不要改配置文件或擅自开启 autopilot。授权不会扩大用户请求的操作范围。

## 按任务选择入口

| 任务 | 使用路径 |
|---|---|
| 已有终端执行 | `list_global_terminals` → 唯一匹配目标的可读写 SSH POSIX 终端 → `execute_global_terminal_command` |
| 保存服务器终端准备 / 远程核验 | `list_selected_deployment_servers` → `ensure_saved_ssh_terminal` → 返回的 `terminal.logical_id` → 真实核验命令 |
| 少量文件上传 | 发现 mapping → `select_deployment_server`（session）→ `upload_selected_files_to_deployment_server` |
| 目录上传 / 下载 / 同步 | 明确范围与方向，见 [传输流程](references/transfers.md) |
| SSH/Eel 传输 | 仅使用 `list_remote_servers.sshEndpoints` 中的真实 endpoint |
| 服务器直传 | 明确双端 server id → 认证检查 → `transfer_between_deployment_servers(mode="server-relay")` |

终端具体参数、超时和返回判定见 [终端与调用契约](references/terminal-and-api.md)。传输参数、权限、免密及审计见 [传输流程](references/transfers.md)。仅加载本任务需要的参考。

## 必须保留的边界

- Deployment server、终端、SSH/Eel endpoint 是三类对象，ID 不可混用；终端可见不代表 endpoint 可用。`sshEndpoints=[]` 仅阻塞 endpoint 接口，不阻塞原生部分文件上传。
- `list_global_terminals=[]` 或本机 BatchMode 失败不能直接证明 IDE 保存的 SSH 凭据失效。优先 `ensure_saved_ssh_terminal`；只复用或创建实际保存预设，保持目标唯一匹配。不得以普通本地 SSH 终端替代此流程。
- 不修改 `.idea/deployment.xml`、凭据、默认服务器或权限文件。`select_deployment_server` 只改 session；终端准备和只读检查不需要改变 selected。
- 只上传本次授权文件；显式路径，目录意味着递归。全量需要用户明确授权并显式 `allow_full_project=true`，不能由缺路径、所有 Git 改动或权限开关推导。
- AI 不读取、猜测、输出、转发密码、私钥或完整 `authorized_keys`；不调用 PasswordSafe 读取工具、不手动执行 `ssh-copy-id`。插件内部认证配置只能在任务授权范围内调用；用户拒绝一次性授权后立即停止，不重试或直传。
- 大文件使用原生 Deployment、真实 SSH/Eel 或显式 server-relay。禁止交互终端/base64 搬运大文件；直接中转不得偷偷改为本地 staging，不得换服务器、endpoint 或后端规避失败。
- 按各工具自己的结构化契约判定完成，不能从终端回显补填缺失字段。`Started`、`Invoked`、pending、超时不能当完成。普通完成命令无需追加 read；超时不重发、不自动中断。

## 交付与复用

报告实际项目/目标、运行版本、原始关键字段、完成状态、独立 size/SHA-256 核验及阻塞项。验收或配置完整性检查还应保留审计起点、增量与 deployment.xml 前后哈希；没有远端读取证据时只能说未发现副作用。

该文件夹包含全部必需引用，可整体复制到目标 agent 支持的 skills 目录。任意 AGENT/AGENTS 指令可加入：

> 涉及 PyCharm Terminal MCP Bridge 时，先读取已安装的 `pycharm-terminal-mcp/SKILL.md`，按本次能力与设置发现使用工具。无法自动发现 skill 时，在此写入它的实际路径；不要猜测服务器或用其他接口代替插件验收。

支持名称调用的宿主可使用 `$pycharm-terminal-mcp`。skill 的可移植性不代表任意宿主已经接入 PyCharm MCP；缺少连接时如实报告。
