# Installation Guide

This guide installs Terminal MCP Bridge into PyCharm and verifies that JetBrains MCP can call the terminal tools.

## Requirements

- PyCharm 2026.3, build 263.7307.206 or later in the 263 line. Only 263.7307.206 has been checked in this update; 262 is no longer declared compatible.
- The bundled Terminal plugin enabled.
- JetBrains MCP Server installed and enabled in the IDE.
- Use the target PyCharm's bundled JBR for local builds; plugin bytecode still targets JVM 17.

## Install From A Zip

1. Download or build the plugin zip:

   ```text
   pycharm-terminal-mcp-0.1.53.zip
   ```

2. Open PyCharm settings:

   ```text
   Settings | Plugins
   ```

3. Click the gear icon and choose:

   ```text
   Install Plugin from Disk
   ```

4. Select the zip file.

5. Restart PyCharm when prompted.

6. Open the bridge settings:

   ```text
   Settings | Tools | Terminal MCP Bridge
   ```

## Build Locally

From the project root:

```powershell
.\gradlew.bat check buildPlugin
```

The built package will be written to:

```text
build/distributions/pycharm-terminal-mcp-0.1.53.zip
```

If `java` is not available in the shell, point Gradle at PyCharm's bundled JBR:

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\PyCharm 2026.3\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat check buildPlugin '-PpycharmLocalPath=C:/Program Files/JetBrains/PyCharm 2026.3'
```

## Configure Permissions

Open:

```text
Settings | Tools | Terminal MCP Bridge
```

Recommended starting point:

- Use `safe` mode while testing.
- Switch to `full` when you want all visible terminals readable and writable without plugin confirmations.
- Enable `Autopilot mode` when a connected AI agent should be able to discover and use terminal tools without repeatedly asking you to flip plugin permissions.
- Enable deployment automation only when the AI may run your existing PyCharm Deploy to Server configurations.
- Disable deployment/upload confirmation only when AI-triggered PyCharm upload, sync, pull, or compare actions may proceed without PyCharm's extra confirmation dialog.
- Enable remote file transfer automation only when the AI may upload, download, or sync through PyCharm Deployment/SSH/Eel APIs.
- Keep the strictly matched SSH-terminal fallback disabled by default. Enable it only for small-file recovery when PyCharm does not expose an Eel endpoint; it never falls back to another server and enforces a 2 MiB default limit. This limit applies only to the interactive-terminal fallback; native PyCharm Web Deployment and SSH/Eel transfers can handle large files and directories.
- Enable local or configured terminal creation only when the AI may open new PyCharm terminal tabs.
- Keep audit logging enabled for traceability.
- Keep redaction enabled unless you are intentionally debugging raw terminal output.

## Verify MCP Access

Use **Tools → 检查终端 MCP 连接** for listener/open-project diagnostics. To use any chat directory without opening it in the IDE, choose **Tools → 复制终端 MCP 连接配置**, select Air/ACP JSON or Codex TOML, and merge the export into the client's global MCP configuration. Enable the connection and start a new session. This is not automatic ACP project injection; the plugin does not edit client settings. The current listener URL and `IJ_MCP_SERVER_PROJECT_PATH: terminal-mcp:current-project` select follow-current routing, without tokens/passwords or a fixed project.

Each call uses the current/last-activated open IDE project. Capabilities `projectPath` reports it; rediscover servers/terminals after switching. Do not send old workspace paths as `projectPath`: omit it or use `terminal-mcp:current-project`. Explicit conflicting paths fail. Multiple projects without a known current window fail; a running operation stays pinned. Fixed-project connections without the special marker retain native IDE routing. No project open means project-dependent operations cannot proceed. Test instructions: [CURRENT_PROJECT_ROUTING_ACCEPTANCE.md](CURRENT_PROJECT_ROUTING_ACCEPTANCE.md).

The distribution restores original plugin ID `com.mcob.terminal-mcp-bridge`. If ID `dev.terminalmcp.bridge` was separately installed as 0.1.50, remove that duplicate through IDE settings. Restart after installing 0.1.53, then reconnect the client. Never overwrite a live plugin directory manually.

In 0.1.53 the Chinese settings page also contains both connection buttons. Close all modal settings/connection/confirmation dialogs before calling tools. Both fixed-project and follow-current routing reject requests during modal dialogs; this does not cancel operations already running.

After restart, first call `get_terminal_mcp_capabilities` and `get_terminal_mcp_settings`. Confirm version 0.1.53, `projectPath`, and the actual permissions, then call:

```text
list_global_terminals
```

Then read the selected terminal:

```text
read_global_terminal --terminal_id <discovered-id> --max_lines 100
```

Run a harmless smoke test:

```text
execute_global_terminal_command --terminal_id <discovered-id> --command "echo terminal bridge smoke"
```

For a POSIX/SSH terminal, verify completed=true, success=true, exit_code=0 and the returned output; do not require an extra read. Do not run this POSIX acceptance against PowerShell/cmd. Merely sent, timed out, or pending is not completion.

To verify optional automation tools, enable the relevant switch first, then call:

```text
list_predefined_terminals
list_remote_servers
list_selected_deployment_servers
list_deployment_configurations
```

Saved Deployment servers and mappings are discovered from PyCharm. Select a session server using `select_deployment_server`, without editing `.idea/deployment.xml`. For daily updates prefer `upload_selected_files_to_deployment_server` with an explicit file list. Do not substitute an implicit whole-project upload. Large cross-server transfers may explicitly use `mode=server-relay`; no SSH/Eel endpoint must be invented from a Deployment ID. See AI_USAGE_GUIDE.md for the authoritative tool contracts and completion checks.

Creation, deployment, and file-transfer tools are powerful and should be tested first against disposable terminals, test deployment configurations, or scratch directories.

## SSH Terminals

SSH and remote-looking terminals are supported when PyCharm exposes them through the integrated terminal API. Configured terminal creation uses PyCharm's predefined terminal actions, and remote file transfer uses known PyCharm SSH/Eel endpoints when available. A separate opt-in small-file fallback can use an exactly matched integrated SSH terminal when no endpoint is exposed. The plugin does not bypass authentication prompts.

In safe and enhanced modes, SSH writes can require confirmation. In full and autopilot modes, plugin-level SSH confirmation is disabled.

## Troubleshooting

If MCP tools are missing:

- Restart PyCharm after installing or updating the plugin.
- Confirm JetBrains MCP Server is installed and enabled.
- Open `Settings | Tools | Terminal MCP Bridge` once to confirm the settings page loads.
- Check PyCharm logs for `TerminalMcpToolset` errors.

If tools exist but terminal selection fails:

- Call `list_global_terminals` first.
- Use the exact id, an id prefix, `selected`, or a unique title/project/path substring.
- If a selector matches multiple terminals, use a longer substring or the exact id.

If command execution succeeds but output is not immediately visible:

- Wait briefly and call `read_global_terminal` again.
- Increase `max_lines` if the terminal has a long scrollback.

If an SSH terminal stops accepting Enter or output appears in the wrong tab after heavy AI terminal use:

- Upgrade to `0.1.8` or newer.
- Close the affected terminal tabs and create fresh SSH terminals after restarting PyCharm.
- Prefer `upload_to_selected_deployment_server` or `invoke_selected_deployment_action` for project-sized syncs instead of terminal-backed transfer fallback.
