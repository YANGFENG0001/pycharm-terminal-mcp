# GitHub Repository Notes

Use this page when creating the GitHub repository, release page, or project description.

## Suggested Repository Name

```text
pycharm-terminal-mcp
```

## Suggested Repository Description

```text
Global PyCharm automation bridge for JetBrains MCP with terminal discovery, terminal creation, remote deployment, remote file transfer, audit logging, redaction, and optional autopilot mode.
```

## Suggested Topics

```text
pycharm
intellij-platform
jetbrains
mcp
model-context-protocol
terminal
ssh
deployment
remote-file-transfer
kotlin
ai-agent
```

## Release Title

```text
v0.1.21 - Terminal exit protection, startup notification fix, reliable execution and saved-server transfers
```

## Release Notes

Terminal MCP Bridge v0.1.14 keeps normal single-line command execution on PyCharm's `TerminalWidget.sendCommandToExecute` path while restoring raw text/control-character input for interactive terminal use, including Ctrl+C. It keeps the v0.1.10 Deployment mapping XML fallback, per-terminal AI access denial, and deployment confirmation controls.

Highlights:

- Replaced `sendCommandToExecute` for existing terminals with connector-level writes, improving SSH Enter handling and multiline command behavior.
- Avoided remote cwd probing while listing terminals, reducing SSH terminal backend warnings.
- Blocked oversized terminal-backed deployment fallback payloads; use official PyCharm deployment actions for large syncs.
- Added `list_selected_deployment_servers` for PyCharm Web Deployment targets and mappings.
- Added `upload_to_selected_deployment_server`, backed by PyCharm's official Upload to Default Server action.
- Added `invoke_selected_deployment_action` to call PyCharm's official upload, pull/download, sync, and compare deployment actions.
- Added `transfer_selected_deployment_server` for selected-server upload/sync with SSH-terminal fallback when no Eel endpoint is exposed.
- `list_remote_servers` now includes Web Deployment targets alongside RemoteServers and known SSH/Eel endpoints.
- Added independent settings switches for deployment automation, remote file transfer automation, local terminal creation, and configured terminal creation.
- Autopilot mode treats the new automation switches as enabled without changing saved manual preferences.
- Existing terminal discovery, reading, input, command execution, per-terminal access, audit logging, redaction, and Kotlin data-class MCP result types remain intact.

## Recommended First Release Asset

Attach the built plugin zip:

```text
build/distributions/pycharm-terminal-mcp-0.1.21.zip
```

## Safety Statement

This plugin does not capture credentials and does not bypass SSH, sudo, OS, remote server, or deployment authentication. It only interacts with terminals, configured deployment actions, and known remote endpoints already visible inside PyCharm.

