# Terminal MCP Bridge

Terminal MCP Bridge is a global PyCharm plugin that exposes integrated terminal tabs, configured terminal presets, and selected PyCharm remote automation actions to JetBrains MCP Server. It lets an MCP-capable AI agent list terminals, read recent terminal output, send text, execute commands, create PyCharm terminal tabs, run Deploy to Server configurations, and transfer directories through PyCharm's own APIs while keeping a configurable permission model in the IDE.

The plugin is IDE-level, not project-level. After installation it scans terminal tabs across all open PyCharm projects.

## Highlights

- Global terminal discovery across open PyCharm projects.
- Read recent output from local, SSH, WSL, and remote-looking integrated terminals when PyCharm exposes them through the terminal API.
- Send text or execute commands in a selected terminal tab.
- Select terminals by id, id prefix, `selected`, `active`, `current`, or a unique title/project/path substring.
- Permission modes for safe, enhanced, full, unguarded, and autopilot workflows.
- Per-terminal AI access toggles.
- Audit log for AI terminal input.
- Redaction for passwords, tokens, private keys, sudo prompts, and nearby sensitive prompt text.
- Optional AI automation switches for PyCharm deployment, remote file transfer, local terminal creation, and configured terminal creation.
- Remote server discovery for PyCharm Web Deployment selected/default servers, path mappings, Deploy to Server configurations, and known SSH/Eel endpoints.
- Upload through PyCharm's official Upload to Default Server action, using the current project's selected Deployment server.
- Terminal-backed upload/sync fallback through the matching SSH terminal when PyCharm has a selected Deployment server but exposes no Eel endpoint.
- Kotlin data-class MCP return types so JetBrains MCP can register and serialize tool results cleanly.

## Permission Modes

Settings live under:

`PyCharm | Settings | Tools | Terminal MCP Bridge`

Modes:

- `safe`: default. Read the active terminal only, confirm SSH input, confirm dangerous commands, redact sensitive output, and audit AI input.
- `enhanced`: read selected or explicitly approved terminals, confirm SSH input, and confirm dangerous commands.
- `full`: read and write all visible integrated terminals without plugin confirmation. Redaction and audit remain enabled.
- `unguarded`: full access plus the option to disable redaction-related safeguards. This still cannot bypass SSH, sudo, OS, or server authentication.
- `Autopilot mode`: one-switch override that keeps terminal tools broadly available to a connected AI agent and removes plugin-level prompts. Saved manual settings remain in place and take effect again when autopilot is disabled.

Additional automation switches can be enabled independently:

- `Allow PyCharm deployment automation`: lets AI run existing PyCharm Deploy to Server run configurations.
- `Confirm before PyCharm deployment/upload actions`: controls whether PyCharm's own Upload/Sync confirmation prompt is kept. Disable it for automatic approval of AI-triggered deployment actions.
- `Allow remote file transfer automation`: lets AI upload, download, or sync directories through known PyCharm SSH/Eel endpoints.
- `Allow local terminal creation`: lets AI create new local integrated terminal tabs.
- `Allow configured terminal creation`: lets AI open PyCharm predefined/configured terminal actions, including SSH-like terminal presets when PyCharm exposes them.

`Autopilot mode` treats these automation switches as enabled without changing the saved manual switch values.

## MCP Tools

When JetBrains MCP Server is installed, the plugin registers these tools:

- `list_global_terminals`: list readable terminal tabs with ids and selector hints.
- `read_global_terminal`: read recent terminal output by selector.
- `send_global_terminal_text`: send raw text to a terminal, optionally pressing Enter.
- `execute_global_terminal_command`: send a command and press Enter.
- `list_predefined_terminals`: list PyCharm terminal creation presets for open projects.
- `create_local_terminal`: create a local integrated terminal tab with an optional title and working directory.
- `create_predefined_terminal`: open exactly one matching configured/predefined terminal action.
- `list_remote_servers`: list PyCharm deployment servers and known SSH/Eel endpoints.
- `list_selected_deployment_servers`: list current-project Web Deployment servers, selected/default marker, and local-to-remote mappings.
- `list_deployment_configurations`: list Deploy to Server run configurations from open projects.
- `run_deployment_configuration`: run one matching Deploy to Server configuration through PyCharm.
- `upload_to_selected_deployment_server`: use PyCharm's official Upload to Default Server action for the selected project Deployment server.
- `invoke_selected_deployment_action`: invoke PyCharm's official Web Deployment actions such as upload, pull/download, sync, and compare for the selected project server.
- `transfer_selected_deployment_server`: upload or sync through the current project's selected Deployment server mapping, falling back to a matching SSH terminal if needed.
- `transfer_remote_directory`: upload, download, or sync a directory through a known PyCharm SSH/Eel endpoint.
- `set_terminal_ai_access`: enable or disable AI access for one terminal tab.
- `set_terminal_autopilot_mode`: turn global autopilot mode on or off.
- `get_terminal_mcp_settings`: return the effective bridge settings.

Typical selectors accepted by terminal tools:

- exact terminal id from `list_global_terminals`
- id prefix
- `selected`
- `active`
- `current`
- unique title, project, or path substring

## Quick Install

1. Build the plugin:

   ```powershell
   .\gradlew.bat check buildPlugin
   ```

2. Install the zip from:

   ```text
   build/distributions/pycharm-terminal-mcp-0.1.21.zip
   ```

3. In PyCharm, open:

   ```text
   Settings | Plugins | Install Plugin from Disk
   ```

4. Select the zip, restart PyCharm, then open:

   ```text
   Settings | Tools | Terminal MCP Bridge
   ```

5. Choose the permission mode you want. Use `full` for broad terminal access with audit and redaction, enable the additional automation switches you trust, or enable `Autopilot mode` for the one-switch automatic workflow.

For a fuller walkthrough, see [docs/INSTALL.md](docs/INSTALL.md).

## Build From Source

This project targets the PyCharm 2026.2 platform line.

```powershell
.\gradlew.bat check buildPlugin
```

If your shell has no `java`, use a JDK 17+ or PyCharm's bundled runtime before building:

```powershell
$env:JAVA_HOME = 'D:\PyCharm 2026.2.0.1\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat check buildPlugin
```

The Gradle wrapper downloads the IntelliJ Platform dependencies declared in `build.gradle.kts`.

## Repository Layout

```text
plugin/src/main/kotlin/com/mcob/terminalmcp/   Plugin source code
plugin/src/main/resources/META-INF/            IntelliJ plugin descriptors
gradle/wrapper/                                Gradle wrapper files
docs/                                          User and release documentation
build.gradle.kts                               Build configuration
settings.gradle.kts                            Gradle settings
```

Generated build output is intentionally ignored by Git. Build a fresh zip locally or download one from GitHub Releases.

## Security Notes

Terminal MCP Bridge does not capture credentials and does not bypass SSH, sudo, OS, remote server, or deployment authentication. It only interacts with terminal sessions, configured deployment actions, and known SSH/Eel endpoints already visible to PyCharm.

Autopilot, full access, deployment automation, remote file transfer automation, and terminal creation automation are intentionally powerful. Use them only when you trust the connected MCP client and the PyCharm projects, terminals, servers, and mappings exposed in the IDE.

Normal single-line terminal commands are sent through PyCharm's `sendCommandToExecute` API. Raw text input remains available for interactive use and control characters such as Ctrl+C, with audit logging and the configured permission policy. Large terminal-backed deployment fallbacks are blocked; use PyCharm's official deployment actions for large project syncs.

## GitHub Metadata

Suggested repository description and release text are in [docs/GITHUB.md](docs/GITHUB.md).

## 0.1.21 终端退出保护与启动修复

- execute_global_terminal_command now waits for a shell completion marker and returns output, completed, timed_out, exit_code, duration_ms, and next_cursor in one call. Use a longer timeout_ms for training jobs; a timeout reports partial output instead of pretending the command finished.
- read_global_terminal accepts output_cursor and returns next_cursor, has_more, and cursor_reset. Pass the returned cursor on the next read to avoid replaying the terminal tail.
- list_global_terminals returns both the current tab id and a stable logical_id. Prefer logical_id when a terminal may be recreated.
- SSH detection recognizes gateway titles such as connect.westc.seetacloud.com:33663 as remote terminals.
- execute_global_terminal_command matches only the completed marker output line, so an echoed wrapper cannot truncate output or hide the exit code. Required completion fields are always serialized.
- output cursors retain a short per-terminal snapshot history and calculate changes from the shared prefix, preventing the first character after a cursor from being lost when the prompt line is overwritten.
- select_deployment_server stores a session-only choice in Terminal MCP Bridge. It does not change PyCharm's persisted default server or edit .idea/deployment.xml.
- upload_to_deployment_server starts a PyCharm Web Deployment transfer task directly against the requested saved server.
- transfer_between_deployment_servers downloads from the requested saved source and starts upload to the requested saved target after the download task finishes. PyCharm performs these actions asynchronously, so the result says started rather than completed.
- terminal audit entries use logical_id as the primary identity, retain the instance id separately, and record Ctrl+C as interrupt.
- transfer_remote_directory requires an exact known SSH endpoint and never falls back to a different selected Deployment server.

Recommended minimal terminal workflow:

1. Call list_global_terminals once and keep logical_id.
2. Call execute_global_terminal_command with timeout_ms and read its returned output directly.
3. For a long-running job, call read_global_terminal later with the returned next_cursor.

allowDeploymentAutomation controls saved-server selection, official upload/download actions, and deployment configuration actions. allowRemoteFileTransferAutomation controls direct SSH endpoint transfer. The existing confirmation toggles still apply.

- 0.1.21 修复可能结束交互 shell 的命令导致 PyCharm terminal widget 失效的问题；同时保留通知组启动修复和“自动确认部署覆盖和传输”开关。开启后，插件自己的部署确认以及 PyCharm Web Deployment 的文件覆盖确认都会自动批准；关闭后保留人工审批。
- 设置页、权限模式、终端范围和 MCP 工具标题/参数说明已汉化。
