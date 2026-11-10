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
v0.1.50 - Repository cleanup, docs, and bilingual README
```

## Release Notes

Terminal MCP Bridge v0.1.50 does not change the MCP tool contracts or the permission model. Tool count stays at 26, and the behaviour of `execute_global_terminal_command`, `ensure_saved_ssh_terminal`, and the upload scope guard is identical to 0.1.49. What changed is how code, tests, and docs are organised in the repository:

- Moved the 7 regression entry points into `src/test` and registered them as separate `verify*` Gradle tasks wired into `check`.
- `verifyPluginClassloader` runs against the packaged plugin zip in an isolated classloader, so it depends on `buildPlugin`.
- Added `docs/AI_USAGE_GUIDE.md`, `docs/MCP_TOOL_DISCOVERY.md`, `docs/INSTALL.md`, and 7 targeted acceptance records.
- Added the portable `skills/pycharm-terminal-mcp/` agent skill.
- `README.md` is now the English primary page, with a full Simplified Chinese version at `README.zh-CN.md`.
- Version history moved to `docs/CHANGELOG.md` so the two READMEs do not duplicate it.
- Install path and version references updated to 0.1.50.

Highlights carried over from 0.1.42–0.1.49:

- `ensure_saved_ssh_terminal` reuses or creates the terminal for a uniquely matched saved SSH Deployment server and waits for a readable POSIX prompt.
- `execute_global_terminal_command` executes and waits, returning `output`, `exit_code`, `completed`, `timed_out`, and `success`.
- `upload_selected_files_to_deployment_server` uploads only an explicit file list; omission of the path is now rejected instead of defaulting to the project root.
- `upload_entire_project_to_deployment_server` requires an explicit `allow_full_project=true`.
- Unfinished results always return `"exit_code":null`, and partial uploads always return `files` and `skippedPaths` arrays.

## Recommended First Release Asset

Attach the built plugin zip:

```text
build/distributions/pycharm-terminal-mcp-0.1.50.zip
```

## Safety Statement

This plugin does not capture credentials and does not bypass SSH, sudo, OS, remote server, or deployment authentication. It only interacts with terminals, configured deployment actions, and known remote endpoints already visible inside PyCharm.
