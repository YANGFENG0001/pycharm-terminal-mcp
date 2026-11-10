# Terminal MCP Bridge

**English** | [简体中文](README.zh-CN.md)

[![Version](https://img.shields.io/badge/version-0.1.50-blue.svg)](docs/CHANGELOG.md)
[![PyCharm](https://img.shields.io/badge/PyCharm-2026.2%2B-green.svg)](https://www.jetbrains.com/pycharm/)

Terminal MCP Bridge is a global PyCharm plugin that exposes integrated terminal tabs, configured terminal presets, and selected PyCharm remote automation actions to JetBrains MCP Server. It lets an MCP-capable AI agent list terminals, read recent terminal output, send text, execute commands, create PyCharm terminal tabs, run Deploy to Server configurations, and transfer directories through PyCharm's own APIs while keeping a configurable permission model in the IDE.

The plugin is IDE-level, not project-level. After installation it scans terminal tabs across all open PyCharm projects.

**For AI agents:** read [docs/AI_USAGE_GUIDE.md](docs/AI_USAGE_GUIDE.md) first — it documents the session opening flow, all 26 tool contracts, permission switches, standard workflows, failure judgement, and hard safety rules — then [AGENTS.md](AGENTS.md) for mandatory agent rules and reading order. A portable skill is available at [skills/pycharm-terminal-mcp/SKILL.md](skills/pycharm-terminal-mcp/SKILL.md); the whole `skills/pycharm-terminal-mcp/` directory can be dropped into any agent that supports `SKILL.md`.

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
- `Allow remote file transfer automation`: lets AI upload, download, or sync through PyCharm Web Deployment/SSH/Eel APIs.
- `Allow small server transfers through a strictly matched SSH terminal`: opt-in fallback only when PyCharm exposes no Eel endpoint; it requires exactly one visible terminal matching the saved server host and port, waits for completion, and is limited to `terminalBackedTransferMaxBytes` (default 2 MiB).
- `Allow local terminal creation`: lets AI create new local integrated terminal tabs.
- `Allow configured terminal creation`: lets AI open PyCharm predefined/configured terminal actions, including SSH-like terminal presets when PyCharm exposes them.

`Autopilot mode` treats these automation switches as enabled without changing the saved manual switch values.

## MCP Tools

When JetBrains MCP Server is installed, the plugin registers these 26 tools:

**Capabilities and settings**

- `get_terminal_mcp_capabilities`: return the running plugin version, registered terminal tools, and the effective permission model. Call this first in a session.
- `get_terminal_mcp_settings`: return the effective bridge settings.
- `set_terminal_autopilot_mode`: turn global autopilot mode on or off.
- `set_terminal_ai_access`: enable or disable AI access for one terminal tab.

**Terminal I/O**

- `list_global_terminals`: list readable terminal tabs with ids and selector hints.
- `read_global_terminal`: read recent terminal output by selector.
- `send_global_terminal_text`: send raw text to a terminal, optionally pressing Enter.
- `execute_global_terminal_command`: execute and wait in a POSIX terminal, returning output and exit status; optional send-only mode.
- `interrupt_global_terminal`: send an interrupt (Ctrl+C) to a selected terminal.

**Terminal creation**

- `list_predefined_terminals`: list PyCharm terminal creation presets for open projects.
- `create_local_terminal`: create a local integrated terminal tab with an optional title and working directory.
- `create_predefined_terminal`: open exactly one matching configured/predefined terminal action.
- `ensure_saved_ssh_terminal`: reuse or create the terminal for a uniquely matched saved SSH Deployment server, wait for a readable POSIX prompt, and return a `ready` terminal with its `logical_id`. It never guesses credentials, never types passwords, and never falls back to another server.

**Server discovery and selection**

- `list_remote_servers`: list PyCharm deployment servers and known SSH/Eel endpoints.
- `list_selected_deployment_servers`: list current-project Web Deployment servers, selected/default marker, and local-to-remote mappings.
- `select_deployment_server`: select any saved project Deployment server for the current MCP session without editing `.idea/deployment.xml`.
- `list_deployment_configurations`: list Deploy to Server run configurations from open projects.
- `run_deployment_configuration`: run one matching Deploy to Server configuration through PyCharm.

**Upload and transfer**

- `upload_to_selected_deployment_server`: use PyCharm's official Upload to Default Server action for the selected project Deployment server.
- `upload_entire_project_to_deployment_server`: upload the whole project root only when `allow_full_project=true` is explicitly declared; rejected by default.
- `upload_selected_files_to_deployment_server`: upload only an explicit non-empty list of project files through native Deployment. Directories and project-external files are rejected; failures stop the batch and return attempted and skipped paths.
- `invoke_selected_deployment_action`: invoke PyCharm's official Web Deployment actions such as upload, pull/download, sync, and compare for the selected project server.
- `transfer_selected_deployment_server`: upload or sync through the current project's selected Deployment server mapping, using PyCharm APIs first and the opt-in strict SSH-terminal fallback only when needed.
- `transfer_remote_directory`: upload, download, or sync a directory through a known PyCharm SSH/Eel endpoint.
- `prepare_server_relay_authentication`: prepare password-less relay authentication between two explicitly selected saved Deployment servers. It never fakes success and never invents a missing Eel endpoint.
- `transfer_between_deployment_servers`: copy or sync from an explicitly selected source Deployment server to an explicitly selected target server. It prefers PyCharm SSH/Eel APIs; the opt-in terminal fallback supports small files only and never silently changes the selected server.

`select_deployment_server` affects bridge upload and transfer tools in the current IDE session, including explicit selected-file uploads. Manually invoked PyCharm actions outside the bridge retain their own default/chooser behavior.

Typical selectors accepted by terminal tools:

- exact terminal id from `list_global_terminals`
- id prefix
- `selected`
- `active`
- `current`
- unique title, project, or path substring

## Quick Install

1. Download `pycharm-terminal-mcp-0.1.50.zip` from [GitHub Releases](https://github.com/YANGFENG0001/pycharm-terminal-mcp/releases), or build it yourself:

   ```powershell
   .\gradlew.bat check buildPlugin
   ```

2. The built zip is at:

   ```text
   build/distributions/pycharm-terminal-mcp-0.1.50.zip
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
$env:JAVA_HOME = 'C:\Program Files\JetBrains\PyCharm 2026.2\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat check buildPlugin
```

`check` runs the regression entry points registered as separate `verify*` tasks, including an isolated-classloader check against the packaged plugin zip. `verifyPluginClassloader` therefore depends on `buildPlugin`.

The Gradle wrapper downloads the IntelliJ Platform dependencies declared in `build.gradle.kts`.

## Repository Layout

```text
src/main/kotlin/dev/terminalmcp/      Plugin source code
src/main/resources/META-INF/          IntelliJ plugin descriptors
src/test/kotlin/dev/terminalmcp/      Regression entry points wired into `check`
skills/pycharm-terminal-mcp/          Portable agent skill (SKILL.md + references)
docs/                                 User, release, and AI usage documentation
docs/AI_USAGE_GUIDE.md                AI-facing usage tutorial (read this first)
docs/CHANGELOG.md                     Version history
README.zh-CN.md                       Simplified Chinese README
build.gradle.kts                      Build configuration
settings.gradle.kts                   Gradle settings
```

Generated build output is intentionally ignored by Git. Build a fresh zip locally or download one from GitHub Releases.

## Security Notes

Terminal MCP Bridge does not capture credentials and does not bypass SSH, sudo, OS, remote server, or deployment authentication. It only interacts with terminal sessions, configured deployment actions, and known SSH/Eel endpoints already visible to PyCharm.

Autopilot, full access, deployment automation, remote file transfer automation, and terminal creation automation are intentionally powerful. Use them only when you trust the connected MCP client and the PyCharm projects, terminals, servers, and mappings exposed in the IDE.

Normal single-line terminal commands are sent through PyCharm's `sendCommandToExecute` API. Raw text input remains available for interactive use and control characters such as Ctrl+C, with audit logging and the configured permission policy. Terminal-backed fallbacks are opt-in, strictly host:port matched, completion-waited, and size-limited. The limit applies only to this interactive-terminal fallback; PyCharm Web Deployment and SSH/Eel transfers are not subject to it. Use those native paths for directories and GB-scale checkpoints.

## Release Notes and Metadata

- Version history: [docs/CHANGELOG.md](docs/CHANGELOG.md)
- Suggested repository description and release text: [docs/GITHUB.md](docs/GITHUB.md)
- Chinese documentation: [README.zh-CN.md](README.zh-CN.md)
