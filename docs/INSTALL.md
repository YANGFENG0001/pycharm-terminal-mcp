# Installation Guide

This guide installs Terminal MCP Bridge into PyCharm and verifies that JetBrains MCP can call the terminal tools.

## Requirements

- PyCharm 2026.2 or another compatible 262-based JetBrains IDE build.
- The bundled Terminal plugin enabled.
- JetBrains MCP Server installed and enabled in the IDE.
- JDK 17+ for local builds. PyCharm's bundled JBR works.

## Install From A Zip

1. Download or build the plugin zip:

   ```text
   pycharm-terminal-mcp-0.1.21.zip
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
build/distributions/pycharm-terminal-mcp-0.1.21.zip
```

If `java` is not available in the shell, point Gradle at PyCharm's bundled JBR:

```powershell
$env:JAVA_HOME = 'D:\PyCharm 2026.2.0.1\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat check buildPlugin
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
- Enable remote file transfer automation only when the AI may upload, download, or sync directories through known PyCharm SSH/Eel endpoints.
- Enable local or configured terminal creation only when the AI may open new PyCharm terminal tabs.
- Keep audit logging enabled for traceability.
- Keep redaction enabled unless you are intentionally debugging raw terminal output.

## Verify MCP Access

After restart, ask your MCP client to call:

```text
list_global_terminals
```

Then read the selected terminal:

```text
read_global_terminal --terminal_id selected --max_lines 100
```

Run a harmless smoke test:

```text
execute_global_terminal_command --terminal_id selected --command "echo terminal bridge smoke"
```

Read again and confirm the smoke-test line appears in terminal output.

To verify optional automation tools, enable the relevant switch first, then call:

```text
list_predefined_terminals
list_remote_servers
list_selected_deployment_servers
list_deployment_configurations
```

For projects configured under `Settings | Build, Execution, Deployment | Deployment`, the bridge reads the current project's selected/default server and mappings. `upload_to_selected_deployment_server` uses PyCharm's official Upload to Default Server action. `invoke_selected_deployment_action` invokes PyCharm's official upload, pull/download, sync, and compare actions. `transfer_selected_deployment_server` can upload or sync through the selected mapping and falls back to a matching SSH terminal when PyCharm does not expose a known Eel endpoint.

Creation, deployment, and file-transfer tools are powerful and should be tested first against disposable terminals, test deployment configurations, or scratch directories.

## SSH Terminals

SSH and remote-looking terminals are supported when PyCharm exposes them through the integrated terminal API. Configured terminal creation uses PyCharm's predefined terminal actions, and remote file transfer uses known PyCharm SSH/Eel endpoints when available. The plugin does not bypass authentication prompts.

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
