# 终端 MCP 桥接 / Terminal MCP Bridge

**简体中文** | [English](README.md)

[![版本](https://img.shields.io/badge/version-0.1.50-blue.svg)](docs/CHANGELOG.md)
[![PyCharm](https://img.shields.io/badge/PyCharm-2026.2%2B-green.svg)](https://www.jetbrains.com/pycharm/)

终端 MCP 桥接是一个 PyCharm 全局插件，把集成终端标签页、已配置的终端预设，以及选定的 PyCharm 远程自动化动作暴露给 JetBrains MCP Server。有了它，支持 MCP 的 AI 代理可以列出终端、读取近期输出、发送文本、执行命令、创建 PyCharm 终端标签页、运行 Deploy to Server 配置，并通过 PyCharm 自身的 API 传输目录，同时在 IDE 内保留可配置的权限模型。

插件是 IDE 级的，不是项目级：安装后会扫描所有已打开 PyCharm 项目中的终端标签页。

**给 AI 代理：** 使用前请先读 [docs/AI_USAGE_GUIDE.md](docs/AI_USAGE_GUIDE.md) —— 面向 AI 的使用教程与工具契约（会话开场流程、26 个工具参数、权限开关、标准工作流、失败判定、安全红线），再读 [AGENTS.md](AGENTS.md) 中的强制规则与阅读顺序。另有可移植 skill 位于 [skills/pycharm-terminal-mcp/SKILL.md](skills/pycharm-terminal-mcp/SKILL.md)，整个 `skills/pycharm-terminal-mcp/` 目录可直接放入任意支持 `SKILL.md` 的 agent。

## 功能亮点

- 跨所有已打开 PyCharm 项目全局发现终端。
- 在 PyCharm 通过终端 API 暴露的前提下，读取本地、SSH、WSL 及各类远程形态集成终端的近期输出。
- 向选定终端标签页发送文本或执行命令。
- 用 id、id 前缀、`selected`、`active`、`current`，或唯一的标题/项目/路径子串选择终端。
- 提供安全、增强、完全、无保护、自动驾驶五档权限模式。
- 支持按终端单独开关 AI 访问。
- 对 AI 终端输入记录审计日志。
- 对密码、令牌、私钥、sudo 提示及邻近敏感提示文本做脱敏。
- 可独立开启的自动化开关：PyCharm 部署、远程文件传输、本地终端创建、已配置终端创建。
- 发现 PyCharm Web Deployment 的选中的/默认服务器、路径映射、Deploy to Server 配置，以及已知的 SSH/Eel endpoint。
- 通过 PyCharm 官方 Upload to Default Server 动作，按当前项目选中的 Deployment 服务器上传。
- 当 PyCharm 有选中 Deployment 服务器但未暴露 Eel endpoint 时，可回退到严格匹配的 SSH 终端完成小文件上传/同步。
- 使用 Kotlin data class 作为 MCP 返回类型，让 JetBrains MCP 能干净地注册并序列化工具结果。

## 权限模式

设置位置：

`PyCharm | 设置 | 工具 | Terminal MCP Bridge`

模式：

- `safe`（安全）：默认值。只读取当前活动终端，SSH 输入需确认，危险命令需确认，输出脱敏，并审计 AI 输入。
- `enhanced`（增强）：可读取选中或明确批准的终端，SSH 输入需确认，危险命令需确认。
- `full`（完全）：读写所有可见集成终端，插件层不再弹确认。脱敏与审计仍然开启。
- `unguarded`（无保护）：完全访问，并可选择关闭与脱敏相关的保护。仍不能绕过 SSH、sudo、操作系统或服务器认证。
- `Autopilot mode`（自动驾驶）：一键开关，让终端工具对已连接的 AI 代理大体可用，并移除插件级询问。已保存的手动设置保持原样，关闭自动驾驶后重新生效。

以下自动化开关可独立开启：

- `允许 AI 执行 PyCharm 部署自动化`：允许 AI 运行已有的 PyCharm Deploy to Server 运行配置。
- `PyCharm 部署/上传前确认`：控制是否保留 PyCharm 自身的上传/同步确认弹窗。关闭后 AI 触发的部署动作将自动批准。
- `允许远程文件传输自动化`：允许 AI 通过 PyCharm Web Deployment/SSH/Eel API 上传、下载或同步。
- `允许通过严格匹配的 SSH 终端做小文件服务器间传输`：仅在 PyCharm 未暴露 Eel endpoint 时的可选回退；要求恰好一个可见终端匹配已保存服务器的主机与端口，等待完成，并受 `terminalBackedTransferMaxBytes`（默认 2 MiB）限制。
- `允许 AI 创建本地终端`：允许 AI 新建本地集成终端标签页。
- `允许 AI 打开已配置的终端`：允许 AI 打开 PyCharm 预定义/已配置的终端动作，包括 PyCharm 暴露的类 SSH 终端预设。

`自动驾驶模式` 会把这些自动化开关视为已开启，但不改变已保存的手动开关值。

## MCP 工具

安装 JetBrains MCP Server 后，插件会注册以下 26 个工具：

**能力与设置**

- `get_terminal_mcp_capabilities`：返回运行中的插件版本、已注册的终端工具和当前生效的权限模型。建议会话开头先调用。
- `get_terminal_mcp_settings`：返回当前生效的桥接设置。
- `set_terminal_autopilot_mode`：打开或关闭全局自动驾驶模式。
- `set_terminal_ai_access`：开启或关闭某个终端标签页的 AI 访问。

**终端读写**

- `list_global_terminals`：列出可读终端标签页及其 id 与选择提示。
- `read_global_terminal`：按选择器读取终端近期输出。
- `send_global_terminal_text`：向终端发送原始文本，可选是否回车。
- `execute_global_terminal_command`：在 POSIX 终端中执行并等待，返回输出与退出状态；可选仅发送模式。
- `interrupt_global_terminal`：向选定终端发送中断（Ctrl+C）。

**终端创建**

- `list_predefined_terminals`：列出已打开项目的 PyCharm 终端创建预设。
- `create_local_terminal`：创建本地集成终端标签页，可指定标题与工作目录。
- `create_predefined_terminal`：打开唯一匹配的已配置/预定义终端动作。
- `ensure_saved_ssh_terminal`：对唯一匹配的已保存 SSH Deployment 服务器复用或创建终端，等待出现可识别的 POSIX 提示符，返回带 `logical_id` 且 `ready` 的终端。不猜测凭据、不在终端输入密码、不切换到其他服务器。

**服务器发现与选择**

- `list_remote_servers`：列出 PyCharm 部署服务器与已知 SSH/Eel endpoint。
- `list_selected_deployment_servers`：列出当前项目 Web Deployment 服务器、选中/默认标记，以及本地到远程的映射。
- `select_deployment_server`：为当前 MCP 会话选择任意已保存的项目 Deployment 服务器，不改写 `.idea/deployment.xml`。
- `list_deployment_configurations`：列出已打开项目中的 Deploy to Server 运行配置。
- `run_deployment_configuration`：通过 PyCharm 运行唯一匹配的 Deploy to Server 配置。

**上传与传输**

- `upload_to_selected_deployment_server`：按当前项目选中的 Deployment 服务器，调用 PyCharm 官方 Upload to Default Server 动作。
- `upload_entire_project_to_deployment_server`：仅在明确声明 `allow_full_project=true` 时上传整个项目根目录，默认拒绝。
- `upload_selected_files_to_deployment_server`：只通过原生 Deployment 上传一个明确的非空项目文件列表。目录与项目外文件在上传前拒绝；失败即停止，并返回已尝试与已跳过路径。
- `invoke_selected_deployment_action`：对选中项目服务器调用 PyCharm 官方 Web Deployment 动作，如上传、拉取/下载、同步、比较。
- `transfer_selected_deployment_server`：按当前项目选中 Deployment 服务器的映射上传或同步，优先用 PyCharm API，必要时才用可选的严格 SSH 终端回退。
- `transfer_remote_directory`：通过已知 PyCharm SSH/Eel endpoint 上传、下载或同步目录。
- `prepare_server_relay_authentication`：在两个明确选定的已保存 Deployment 服务器之间准备免密中继认证。不伪造成功，也不虚构缺失的 Eel endpoint。
- `transfer_between_deployment_servers`：从明确选定的源服务器复制或同步到明确选定的目标服务器。优先使用 PyCharm SSH/Eel API；可选的终端回退只支持小文件，且绝不静默更换选中服务器。

`select_deployment_server` 影响当前 IDE 会话中的桥接上传与传输工具，包括显式指定文件的上传。在桥接之外手动触发的 PyCharm 动作仍保留其自身的默认/选择器行为。

终端工具常用的选择器：

- `list_global_terminals` 返回的确切终端 id
- id 前缀
- `selected`
- `active`
- `current`
- 唯一的标题、项目或路径子串

## 快速安装

1. 从 [GitHub Releases](https://github.com/YANGFENG0001/pycharm-terminal-mcp/releases) 下载 `pycharm-terminal-mcp-0.1.50.zip`，或自行构建：

   ```powershell
   .\gradlew.bat check buildPlugin
   ```

2. 构建产物位于：

   ```text
   build/distributions/pycharm-terminal-mcp-0.1.50.zip
   ```

3. 在 PyCharm 中打开：

   ```text
   设置 | 插件 | 从磁盘安装插件
   ```

4. 选择该 zip，重启 PyCharm，然后打开：

   ```text
   设置 | 工具 | Terminal MCP Bridge
   ```

5. 选择你需要的权限模式。需要广泛终端访问又保留审计与脱敏时用 `完全`；按需开启你信任的自动化开关；或者直接开 `自动驾驶模式` 走一键自动流程。

更完整的步骤见 [docs/INSTALL.md](docs/INSTALL.md)。

## 从源码构建

本项目面向 PyCharm 2026.2 平台线。

```powershell
.\gradlew.bat check buildPlugin
```

如果当前 shell 没有 `java`，构建前先用 JDK 17+ 或 PyCharm 自带的运行时：

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\PyCharm 2026.2\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat check buildPlugin
```

`check` 会运行注册为独立 `verify*` 任务的回归入口，其中包含针对打包后插件 zip 的隔离类加载检查，因此 `verifyPluginClassloader` 依赖 `buildPlugin`。

Gradle wrapper 会自动下载 `build.gradle.kts` 中声明的 IntelliJ Platform 依赖。

## 仓库结构

```text
src/main/kotlin/dev/terminalmcp/      插件源码
src/main/resources/META-INF/          IntelliJ 插件描述符
src/test/kotlin/dev/terminalmcp/      接入 check 的回归入口
skills/pycharm-terminal-mcp/          可移植 agent skill（SKILL.md + references）
docs/                                 使用、发布与 AI 使用文档
docs/AI_USAGE_GUIDE.md                面向 AI 的使用教程（建议先读）
docs/CHANGELOG.md                     版本更新日志
README.md                             英文 README
build.gradle.kts                      构建配置
settings.gradle.kts                   Gradle 设置
```

构建产物有意被 Git 忽略。请本地构建新包，或从 GitHub Releases 下载。

## 安全说明

终端 MCP 桥接不采集凭据，也不绕过 SSH、sudo、操作系统、远程服务器或部署认证。它只与 PyCharm 已经可见的终端会话、已配置的部署动作和已知 SSH/Eel endpoint 交互。

自动驾驶、完全访问、部署自动化、远程文件传输自动化和终端创建自动化都是高权限能力。请只在你信任已连接的 MCP 客户端，以及 IDE 中暴露的项目、终端、服务器和映射时启用。

普通单行终端命令通过 PyCharm 的 `sendCommandToExecute` API 发送。原始文本输入仍可用于交互操作与控制字符（如 Ctrl+C），并受审计日志与已配置权限策略约束。终端回退是可选项，要求严格匹配主机与端口、等待完成并限制大小；该限制只作用于这个交互终端回退，PyCharm Web Deployment 与 SSH/Eel 传输不受此限制。目录和 GB 级 checkpoint 请走这些原生通道。

## 版本与元信息

- 版本更新日志：[docs/CHANGELOG.md](docs/CHANGELOG.md)
- 建议的仓库描述与 Release 文案：[docs/GITHUB.md](docs/GITHUB.md)
- 英文文档：[README.md](README.md)
