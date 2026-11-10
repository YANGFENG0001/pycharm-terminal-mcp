package dev.terminalmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class TerminalMcpToolset : McpToolset {
    private val settings: TerminalMcpSettings
        get() = TerminalMcpSettings.getInstance()

    private val operations: TerminalOperations
        get() = TerminalOperations(settings)

    private val terminalCreationOperations: TerminalCreationOperations
        get() = TerminalCreationOperations(settings)

    private val remoteOperations: RemoteOperations
        get() = RemoteOperations(settings)

    // Keep this toolset visible to MCP clients in every session. The bridge is
    // still guarded by TerminalMcpSettings; visibility does not grant access.
    override fun isExperimental(): Boolean = false

    override fun alwaysIncluded(): Boolean = true

    @McpTool(title = "Terminal MCP Bridge capabilities and workflow")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription(
        "Returns the capabilities and required workflow for this bridge. " +
            "Call this at the beginning of a new session when terminal, SSH, Deployment, upload, download, sync, " +
            "or cross-server file transfer work is possible. Do not use a shell command as a substitute before " +
            "checking these PyCharm-native tools."
    )
    fun get_terminal_mcp_capabilities(): TerminalMcpCapabilitiesResult =
        TerminalMcpCapabilitiesResult(
            toolsetAlwaysIncluded = true,
            pluginVersion = PluginManagerCore.getPlugin(PluginId.getId("dev.terminalmcp.bridge"))?.version ?: "unknown",
            currentSettings = currentSettingsResult(),
            recommendedWorkflow = listOf(
                "1. Call list_global_terminals before reading or writing an existing terminal.",
                "1a. POSIX/SSH 命令优先使用 execute_global_terminal_command，默认等待完成并直接返回 output/exit_code/completed/timed_out，无需额外 read。超时不重发；持久 cd/export 或交互任务使用 send_global_terminal_text。",
                "2. Call list_remote_servers and list_selected_deployment_servers before any server or file-transfer action.",
                "3. 没有目标 SSH 终端时必须先调用 ensure_saved_ssh_terminal（明确 server_selector），复用或从保存预设创建并等待。BatchMode 失败不代表 PyCharm 保存凭据不可用。ready=true 后用返回的 terminal.logical_id 核对远程版本/哈希；不能直接以无终端为由放弃，也不能跳过核对上传覆盖。身份未暴露时可先 list_predefined_terminals，再唯一匹配 create_predefined_terminal；真实创建/认证失败、权限不足或歧义才报告阻塞。",
                "4. 日常更新优先使用 upload_selected_files_to_deployment_server 并明确列出本次任务文件 local_paths。上传/同步缺少 local_path 一律拒绝，不默认上传项目。目录递归范围必须显式指定；项目根目录必须 allow_full_project=true。全量使用 upload_entire_project_to_deployment_server，仅用户明确要求全量才声明。上传不删除远端额外文件，受映射和排除规则限制。",
                "5. transfer_remote_directory 只接受 list_remote_servers.sshEndpoints 中唯一匹配的真实 SSH/Eel endpoint；webdeploy ID 和可见 SSH 终端不是 Eel endpoint。sshEndpoints=[] 时跳过此接口的正向传输，不反复探测或回退。原生部分文件上传不要求 Eel endpoint。",
                "6. Use select_deployment_server to choose any saved project Deployment server for the current MCP session without editing .idea/deployment.xml.",
                "7. Use transfer_between_deployment_servers for explicit source-to-target server transfer; for large files use mode=server-relay to stream directly from the source server to the target server without a complete local staging copy.",
                "7a. If server-relay BatchMode SSH fails, call prepare_server_relay_authentication; when automatic relay auth is enabled, the plugin asks the user once, reads the saved target password internally, installs the source public key, and never sends the password to AI, terminal, response, or audit log. When disabled or denied, it returns a structured no-op and never emits a manual password command.",
                "8. Use transfer_selected_deployment_server only when the current session-selected server is the intended target. " +
                    "Terminal-backed fallback is opt-in, strictly matches host:port to one visible SSH terminal, waits for completion, and is limited to small files.",
                "9. Never silently fall back to another server when the requested endpoint or mapping does not match.",
            ),
            terminalTools = listOf(
                "list_global_terminals",
                "read_global_terminal",
                "send_global_terminal_text",
                "interrupt_global_terminal",
                "execute_global_terminal_command",
                "create_local_terminal",
                "create_predefined_terminal",
                "ensure_saved_ssh_terminal",
            ),
            deploymentTools = listOf(
                "list_remote_servers",
                "list_selected_deployment_servers",
                "list_deployment_configurations",
                "run_deployment_configuration",
                "upload_to_selected_deployment_server",
                "upload_selected_files_to_deployment_server",
                "upload_entire_project_to_deployment_server",
                "invoke_selected_deployment_action",
                "transfer_selected_deployment_server",
                "transfer_remote_directory",
                "select_deployment_server",
                "transfer_between_deployment_servers",
                "prepare_server_relay_authentication",
            ),
            limitation = "跨服务器传输支持 server-relay（源服务器 rsync/scp 直推目标服务器，不完整落地本机）、PyCharm SSH/Eel API、本地 staging 和小文件终端备用通道。server-relay 需要源端可执行 rsync/scp 且能以 BatchMode 免交互登录目标端；条件不满足时结构化拒绝，不自动回退，不修改 .idea/deployment.xml。",
        )

    @McpTool(title = "List global PyCharm terminals")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription(
        """
        Lists integrated terminal tabs across all open PyCharm projects.
        Respects the global Terminal MCP Bridge permission mode and per-terminal AI access toggles.
        Empty results do not mean SSH work is impossible: use ensure_saved_ssh_terminal for the explicitly intended saved server before reporting no terminal.
        """
    )
    fun list_global_terminals(): TerminalListResult =
        TerminalListResult(operations.listTerminals().map { it.toResult() })

    @McpTool(title = "Read global PyCharm terminal")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("Reads recent output from a PyCharm integrated terminal tab by selector.")
    fun read_global_terminal(
        @McpDescription("Terminal id returned by list_global_terminals.")
        terminal_id: String,
        @McpDescription("Maximum number of recent lines to return.")
        max_lines: Int = 200,
    ): TerminalReadResult = try {
        TerminalReadResult(
            terminal_id = terminal_id,
            output = operations.readTerminal(terminal_id, max_lines),
            redacted = settings.snapshot().redactSensitiveOutput,
        )
    }
    catch (ex: ExpectedMcpFailureException) {
        TerminalReadResult(
            terminal_id = terminal_id,
            output = "",
            redacted = settings.snapshot().redactSensitiveOutput,
            success = false,
            error = ex.message ?: "Terminal access denied.",
        )
    }

    @McpTool(title = "Send text to global PyCharm terminal")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        Sends text to a PyCharm integrated terminal tab.
        When press_enter is true, the text is executed as a command.
        SSH and dangerous-command confirmation depends on the global permission mode.
        """
    )
    fun send_global_terminal_text(
        @McpDescription("Terminal selector: id, id prefix, selected/current/active, or unique title/project/path substring.")
        terminal_id: String,
        @McpDescription("Text or command to send to the terminal.")
        text: String,
        @McpDescription("Whether to press Enter after sending the text.")
        press_enter: Boolean = false,
    ): TerminalActionResult = terminalAction(terminal_id) {
        TerminalActionResult(
            terminal_id = terminal_id,
            message = operations.sendText(terminal_id, text, press_enter),
        )
    }

    @McpTool(title = "Interrupt global PyCharm terminal")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("Sends Ctrl+C/ETX to a PyCharm integrated terminal tab without changing the raw text-input behavior.")
    fun interrupt_global_terminal(
        @McpDescription("Terminal selector: id, id prefix, selected/current/active, or unique title/project/path substring.")
        terminal_id: String,
    ): TerminalActionResult = terminalAction(terminal_id) {
        TerminalActionResult(
            terminal_id = terminal_id,
            message = operations.sendText(terminal_id, "\u0003", pressEnter = false),
        )
    }

    @McpTool(title = "Execute command in global PyCharm terminal")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("默认在 POSIX/SSH 终端执行并等待，直接返回 output、数字 exit_code、completed、timed_out 和 success，通常无需追加 read。命令在子 shell 中运行，exit N 不退出交互 shell，cd/export 不持久。超时不自动中断或重发。仅发送可指定 wait_for_completion=false；未知本地 shell 不执行 POSIX 包装。")
    fun execute_global_terminal_command(
        @McpDescription("Terminal selector: id, id prefix, selected/current/active, or unique title/project/path substring.")
        terminal_id: String,
        @McpDescription("Command to execute.")
        command: String,
        @McpDescription("是否等待完成，默认 true；false 仅发送，completed=false 不代表执行成功。")
        wait_for_completion: Boolean = true,
        @McpDescription("等待超时毫秒数，100..240000，默认 30000。超时后不要自动重试。")
        timeout_ms: Int = 30000,
        @McpDescription("auto 仅自动支持 SSH POSIX shell；本地 POSIX 需显式 posix。")
        shell: String = "auto",
    ): TerminalExecutionResult = try {
        if (wait_for_completion) operations.executeAndWait(terminal_id, command, timeout_ms, shell)
        else TerminalExecutionResult(terminal_id = terminal_id,
            message = operations.sendText(terminal_id, command, pressEnter = true))
    } catch (ex: Exception) {
        TerminalExecutionResult(terminal_id = terminal_id, message = "执行未确认完成。",
            error = ex.message ?: "终端执行失败。")
    }

    @McpTool(title = "List configured terminal creation profiles")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("Lists PyCharm terminal presets, including configured or SSH-backed terminal actions.")
    fun list_predefined_terminals(
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
    ): TerminalPresetListResult =
        TerminalPresetListResult(
            terminalCreationOperations.listPredefinedTerminals(project_selector).map { it.descriptor.toResult() },
        )

    @McpTool(title = "Create local PyCharm terminal")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("Creates a new local integrated terminal tab through PyCharm.")
    fun create_local_terminal(
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
        @McpDescription("Optional terminal tab title.")
        title: String? = null,
        @McpDescription("Optional local working directory.")
        working_directory: String? = null,
    ): TerminalCreationResult =
        terminalCreationOperations.createLocalTerminal(project_selector, title, working_directory).toCreationResult()

    @McpTool(title = "Create configured PyCharm terminal")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("打开唯一匹配的 PyCharm 已保存终端预设（含 SSH），凭据由 IDE 使用。调用返回只表示创建动作已触发，不代表认证就绪。目标终端不存在时不要直接放弃；优先 ensure_saved_ssh_terminal 合并匹配、创建与等待。")
    fun create_predefined_terminal(
        @McpDescription("Preset id, id prefix, title, description, or unique project substring.")
        selector: String,
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
    ): TerminalCreationResult {
        val created = terminalCreationOperations.openPredefinedTerminal(selector, project_selector)
        return TerminalCreationResult(
            message = "Configured terminal action invoked.",
            terminals = created.map { it.toResult() },
        )
    }

    @McpTool(title = "确保已保存服务器的 SSH 终端就绪")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("目标 SSH 终端不存在或普通 BatchMode 认证失败时优先调用。按明确保存的 Deployment server 唯一匹配 SSH 用户、主机、端口：复用现有终端，否则通过 PyCharm 已保存预设创建，等待连接、POSIX 提示符与只读 printf 探测。保存凭据由 PyCharm 自行使用，不读取密码给 AI、不输入密码、不配置免密、不上传文件、不更改 selected 服务器。ready=true 才表示探测成功；随后仍须独立核对远程版本/哈希，再决定部分上传。权限不足、歧义、无预设或超时明确失败，不创建其他服务器、不重试创建。此操作不生成 Eel endpoint，也不修复服务器之间 BatchMode 认证。")
    fun ensure_saved_ssh_terminal(
        @McpDescription("必填，来自本轮服务器发现的保存 Deployment server id、准确名称或唯一 selector；不默认 selected。")
        server_selector: String,
        @McpDescription("项目名称或路径，多项目时明确传入。")
        project_selector: String? = null,
        @McpDescription("等待毫秒数 1000..60000，默认 30000；超时不要重复创建。")
        timeout_ms: Int = 30000,
    ): SavedSshTerminalResult = try {
        val (serverId, outcome) = terminalCreationOperations.ensureSavedSshTerminal(server_selector, project_selector, timeout_ms)
        SavedSshTerminalResult(success = outcome.ready, ready = outcome.ready, created = outcome.created, reused = outcome.reused,
            timed_out = outcome.timedOut, status = outcome.status, message = outcome.message, serverId = serverId,
            terminal = outcome.terminal?.toResult(), presetId = outcome.presetId,
            error = if (outcome.ready) null else outcome.message)
    } catch (ex: Exception) {
        if (ex is java.util.concurrent.CancellationException) throw ex
        if (ex is InterruptedException) Thread.currentThread().interrupt()
        val message = SensitiveRedactor.redact(ex.message ?: "保存的 SSH 终端恢复失败；未上传。")
        SavedSshTerminalResult(status = "recovery_failed", message = message, error = message)
    }

    @McpTool(title = "List PyCharm remote servers")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("Lists PyCharm remote server configurations and known SSH Eel endpoints.")
    fun list_remote_servers(): RemoteServerListResult =
        RemoteServerListResult(
            deploymentServers = remoteOperations.listRemoteServers().map { it.toResult() },
            sshEndpoints = remoteOperations.listSshEndpoints().map { it.toResult() },
            webDeploymentTargets = remoteOperations.listWebDeploymentTargets(null).map { it.toResult() },
        )

    @McpTool(title = "List PyCharm selected deployment servers")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("Lists Web Deployment servers configured for open projects, including the selected/default server and local-to-remote mappings.")
    fun list_selected_deployment_servers(
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
    ): WebDeploymentTargetListResult =
        WebDeploymentTargetListResult(
            remoteOperations.listWebDeploymentTargets(project_selector).map { it.toResult() },
        )

    @McpTool(title = "Select PyCharm deployment server for this session")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription(
        "Selects one saved PyCharm Web Deployment server for this project in Terminal MCP Bridge session memory. " +
            "It does not change PyCharm's persisted default server or .idea/deployment.xml."
    )
    fun select_deployment_server(
        @McpDescription("Saved server id, exact name, host, host:port, or a unique server substring.")
        server_selector: String,
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
    ): DeploymentServerSelectionResult = try {
        val previous = remoteOperations.sessionSelectedDeploymentServerId(project_selector)
        val selected = remoteOperations.selectDeploymentServer(project_selector, server_selector)
        DeploymentServerSelectionResult(
            selected = selected.toResult(),
            previous_server_id = previous,
            scope = "session",
            success = true,
        )
    } catch (ex: ExpectedMcpFailureException) {
        DeploymentServerSelectionResult(
            selected = null,
            previous_server_id = null,
            scope = "session",
            success = false,
            error = ex.message ?: "Deployment server selection failed.",
        )
    }

    @McpTool(title = "List PyCharm deployment configurations")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("Lists existing Deploy to Server run configurations saved in PyCharm.")
    fun list_deployment_configurations(
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
    ): DeploymentConfigurationListResult =
        DeploymentConfigurationListResult(
            remoteOperations.listDeploymentConfigurations(project_selector).map { it.toResult() },
        )

    @McpTool(title = "Run PyCharm deployment configuration")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("Runs an existing PyCharm Deploy to Server configuration to upload or deploy project files.")
    fun run_deployment_configuration(
        @McpDescription("Existing deployment configuration name, id, or unique server substring.")
        selector: String,
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
    ): DeploymentActionResult = deploymentAction {
        DeploymentActionResult(remoteOperations.runDeploymentConfiguration(project_selector, selector))
    }

    @McpTool(title = "Upload to selected PyCharm deployment server")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("上传明确指定的文件或目录，目录递归。缺少/空 local_path 拒绝，不默认全项目。根目录需 allow_full_project=true；只有用户明确要求全量才能声明。日常更新优先 upload_selected_files_to_deployment_server。受映射和排除规则限制，不删除远端额外文件。")
    fun upload_to_selected_deployment_server(
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
        @McpDescription("上传范围必须明确指定；省略或空值拒绝。目录将递归上传。")
        local_path: String? = null,
        @McpDescription("仅用户明确要求全项目上传时设 true；默认 false，不是审批开关。")
        allow_full_project: Boolean = false,
    ): DeploymentActionResult = deploymentAction {
        DeploymentActionResult(remoteOperations.uploadToSelectedDeploymentServer(project_selector, local_path, allow_full_project))
    }

    @McpTool(title = "明确上传整个项目到选定服务器")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("递归上传整个项目根目录，可能包含大型 checkpoints 和 outputs，受 Deployment 映射及排除规则限制。不删除远端额外文件。仅用户明确要求全量上传才使用，必须 allow_full_project=true；默认拒绝。日常代码更新用 upload_selected_files_to_deployment_server。")
    fun upload_entire_project_to_deployment_server(
        @McpDescription("用户明确要求全量时才声明 true；不是人工审批开关。")
        allow_full_project: Boolean = false,
        @McpDescription("项目名称或路径。")
        project_selector: String? = null,
    ): DeploymentActionResult = deploymentAction {
        DeploymentActionResult(remoteOperations.uploadEntireProject(project_selector, allow_full_project))
    }

    @McpTool(title = "上传指定部分文件到选定服务器")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("仅上传 local_paths 明确列出的项目内文件，可覆盖远端同名文件。必填非空列表；拒绝目录和项目外文件；相对路径基于项目根目录。使用会话选定/默认 Deployment 服务器及原生传输，不依赖 SSH 终端。不自动扩大为全项目、不删除远端文件。先全部预检，执行失败后停止并返回已尝试和未尝试文件。success 表示原生上传调用完成，不代表独立 SHA256 校验。")
    fun upload_selected_files_to_deployment_server(
        @McpDescription("必填非空文件路径列表，不能传目录。")
        local_paths: List<String>,
        @McpDescription("项目名称或路径，相对文件路径以该项目根目录为基准。")
        project_selector: String? = null,
    ): SelectedFilesUploadResult = try {
        remoteOperations.uploadSelectedFiles(project_selector, local_paths)
    } catch (ex: Exception) {
        SelectedFilesUploadResult(success = false, requestedCount = local_paths.size,
            skippedPaths = local_paths, message = ex.message ?: "文件预检失败，未开始上传。")
    }

    @McpTool(title = "Invoke selected PyCharm deployment action")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("选定服务器的部署动作。上传/同步缺少路径拒绝；指定目录递归，项目根目录需 allow_full_project=true。部分文件优先 upload_selected_files_to_deployment_server。download/pull 保留原路径语义，compare 可能打开比较界面。")
    fun invoke_selected_deployment_action(
        @McpDescription("Deployment action: upload, upload_to, download, download_from, pull, pull_from, sync, sync_with, compare, or compare_with.")
        action: String,
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
        @McpDescription("上传/同步必须明确路径；下载/比较可省略。")
        local_path: String? = null,
        @McpDescription("项目根目录上传/同步需显式 true，默认 false。")
        allow_full_project: Boolean = false,
    ): DeploymentActionResult = deploymentAction {
        DeploymentActionResult(remoteOperations.invokeSelectedDeploymentAction(project_selector, local_path, action, allow_full_project))
    }

    @McpTool(title = "Transfer using selected PyCharm deployment server")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("按选定服务器映射传输。默认 mode=upload，但缺少/空 local_path 一律拒绝上传/同步，不默认项目根目录。目录递归，根目录需 allow_full_project=true。部分文件优先 upload_selected_files_to_deployment_server。终端备用受权限、唯一 endpoint 和大小限制。")
    fun transfer_selected_deployment_server(
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
        @McpDescription("上传/同步必须明确指定文件或目录；目录递归。下载可省略。")
        local_path: String? = null,
        @McpDescription("Transfer mode: upload, sync, download, or pull.")
        mode: String = "upload",
        @McpDescription("Whether sync may delete entries missing from the local source before uploading.")
        delete_missing: Boolean = false,
        @McpDescription("Optional terminal selector to use for terminal-backed fallback. Defaults to the selected server host/name.")
        terminal_id: String? = null,
        @McpDescription("仅明确全项目上传/同步才声明 true；默认 false。")
        allow_full_project: Boolean = false,
    ): FileTransferResult = fileTransferAction(local_path.orEmpty(), "", mode) {
        remoteOperations.transferWithSelectedDeploymentServer(
            project_selector,
            local_path,
            mode,
            delete_missing,
            terminal_id,
            allow_full_project,
        ).toResult()
    }

    @McpTool(title = "Transfer PyCharm SSH directory")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("显式 SSH/Eel endpoint 和双端路径传输。必须先发现真实 sshEndpoints；空列表时不可用，webdeploy ID 和 SSH 终端不是 endpoint，不回退。路径必须指定，目录递归；上传/同步覆盖已打开项目根目录需 allow_full_project=true。部分文件更新优先 upload_selected_files_to_deployment_server。业务异常返回 success=false；IDE 参数缺失/类型错误在调用前由宿主拒绝。")
    fun transfer_remote_directory(
        @McpDescription("Known SSH endpoint id, id prefix, host, user, or name.")
        endpoint_selector: String,
        @McpDescription("Local file or directory path.")
        local_path: String,
        @McpDescription("Remote file or directory path.")
        remote_path: String,
        @McpDescription("Transfer mode: upload, download, or sync.")
        mode: String,
        @McpDescription("Whether sync may delete entries missing from the source side.")
        delete_missing: Boolean = false,
        @McpDescription("上传/同步覆盖项目根目录时必须显式 true；默认 false。")
        allow_full_project: Boolean = false,
    ): FileTransferResult = fileTransferAction(local_path, remote_path, mode) {
        remoteOperations.transferDirectory(endpoint_selector, local_path, remote_path, mode, delete_missing, allow_full_project).toResult()
    }

    @McpTool(title = "Prepare server relay SSH authentication")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("检查源到目标服务器的 BatchMode SSH，与 PyCharm 使用保存凭据打开 SSH 终端是不同连接。没有源终端时先调用 ensure_saved_ssh_terminal 明确准备源服务器，不因无终端直接放弃。启用自动免密配置时先征求用户一次明确授权，插件内部读取保存的目标密码并安装公钥、验证 BatchMode，密码不经过 AI、终端、返回或审计。关闭/拒绝授权时不读取密码、不生成手动密码命令。仅为远程版本核验准备终端不需要先配置服务器间免密。")
    fun prepare_server_relay_authentication(
        @McpDescription("Source saved Deployment server id, exact name, host, host:port, or unique substring.")
        source_server_selector: String,
        @McpDescription("Target saved Deployment server id, exact name, host, host:port, or unique substring.")
        target_server_selector: String,
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
    ): RelayAuthSetupResult = try {
        remoteOperations.prepareServerRelayAuthentication(
            project_selector,
            source_server_selector,
            target_server_selector,
        ).toResult()
    } catch (ex: ExpectedMcpFailureException) {
        RelayAuthSetupResult(
            ready = false,
            sourceServer = source_server_selector,
            targetServer = target_server_selector,
            sourceHost = "",
            sourcePort = 0,
            sourceUser = "",
            targetHost = "",
            targetPort = 0,
            targetUser = "",
            sourceTerminalId = null,
            commands = emptyList(),
            message = ex.message ?: "无法准备服务器免密中转配置。",
            warnings = listOf("未读取或输出任何密码。"),
            requiresOneTimePassword = false,
        )
    }

    @McpTool(title = "Transfer files between PyCharm deployment servers")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription(
        "Transfers a file or directory from one explicitly selected saved PyCharm Deployment server to another. " +
            "The operation prefers PyCharm's SSH/Eel APIs through a temporary local staging path. If no endpoint is exposed, an opt-in strictly matched SSH-terminal fallback supports small files only. It never changes .idea/deployment.xml."
    )
    fun transfer_between_deployment_servers(
        @McpDescription("Source saved Deployment server id, exact name, host, host:port, or unique substring.")
        source_server_selector: String,
        @McpDescription("Target saved Deployment server id, exact name, host, host:port, or unique substring.")
        target_server_selector: String,
        @McpDescription("copy/sync 必须明确指定 local_path，项目根目录需 allow_full_project=true。server-relay 可省略，但须明确 source_path 和 target_path，不隐式映射整个项目。")
        local_path: String? = null,
        @McpDescription("Optional explicit source remote path. Defaults to the source server mapping for local_path.")
        source_path: String? = null,
        @McpDescription("Optional explicit target remote path. Defaults to the target server mapping for local_path.")
        target_path: String? = null,
        @McpDescription("copy, sync, or server-relay. server-relay runs rsync/scp on the source server and sends directly to the target server without a complete local staging copy; sync preserves the source directory structure and can delete missing target entries when enabled.")
        mode: String = "copy",
        @McpDescription("Whether sync may delete entries missing from the source directory.")
        delete_missing: Boolean = false,
        @McpDescription("Optional project name or path selector.")
        project_selector: String? = null,
        @McpDescription("按 local_path 映射整个项目时必须显式 true，默认 false。")
        allow_full_project: Boolean = false,
    ): FileTransferResult = fileTransferAction(local_path.orEmpty(), target_path.orEmpty(), mode) {
        remoteOperations.transferBetweenDeploymentServers(
            project_selector,
            source_server_selector,
            target_server_selector,
            local_path,
            source_path,
            target_path,
            mode,
            delete_missing,
            allow_full_project,
        ).toResult()
    }

    @McpTool(title = "Set terminal AI access")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("Enables or disables AI access for one terminal tab in Terminal MCP Bridge global settings.")
    fun set_terminal_ai_access(
        @McpDescription("Terminal selector: id, id prefix, selected/current/active, or unique title/project/path substring.")
        terminal_id: String,
        @McpDescription("Whether AI may access this terminal.")
        enabled: Boolean,
    ): TerminalInfoResult =
        operations.setAccess(terminal_id, enabled).toResult()

    @McpTool(title = "Set Terminal MCP Bridge autopilot mode")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("Enables or disables the global fully automatic mode for Terminal MCP Bridge.")
    fun set_terminal_autopilot_mode(
        @McpDescription("Whether the bridge should run in fully automatic mode.")
        enabled: Boolean,
    ): TerminalSettingsResult {
        settings.update { state ->
            state.autoPilotMode = enabled
        }
        return currentSettingsResult()
    }

    @McpTool(title = "Get Terminal MCP Bridge settings")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("Returns the current global Terminal MCP Bridge permission settings.")
    fun get_terminal_mcp_settings(): TerminalSettingsResult =
        currentSettingsResult()

    private fun terminalAction(terminalId: String, action: () -> TerminalActionResult): TerminalActionResult = try {
        action()
    }
    catch (ex: ExpectedMcpFailureException) {
        TerminalActionResult(
            terminal_id = terminalId,
            message = ex.message ?: "Terminal access denied.",
            success = false,
            error = ex.message ?: "Terminal access denied.",
        )
    }

    private fun deploymentAction(action: () -> DeploymentActionResult): DeploymentActionResult = try {
        action()
    }
    catch (ex: ExpectedMcpFailureException) {
        DeploymentActionResult(
            message = ex.message ?: "Deployment action was not completed.",
            success = false,
            error = ex.message ?: "Deployment action was not completed.",
        )
    }

    private fun fileTransferAction(localPath: String, remotePath: String, mode: String, action: () -> FileTransferResult): FileTransferResult = try {
        action()
    }
    catch (ex: Exception) {
        if (ex is java.util.concurrent.CancellationException) throw ex
        if (ex is InterruptedException) Thread.currentThread().interrupt()
        val failure = SensitiveRedactor.redact(ex.message ?: "File transfer was not completed.")
        FileTransferResult(
            endpointId = "",
            endpointName = "",
            mode = mode,
            localPath = localPath,
            remotePath = remotePath,
            message = failure,
            success = false,
            error = failure,
        )
    }

    private fun currentSettingsResult(): TerminalSettingsResult {
        val state = settings.snapshot()
        val autopilot = state.autoPilotMode
        return TerminalSettingsResult(
            permissionMode = if (autopilot) PermissionMode.FULL.wireName else state.permissionMode,
            terminalScope = if (autopilot) TerminalScope.ALL.wireName else state.terminalScope,
            autoPilotMode = autopilot,
            allowSshTerminals = if (autopilot) true else state.allowSshTerminals,
            requireConfirmForSshInput = if (autopilot) false else state.requireConfirmForSshInput,
            requireConfirmForDangerousCommands = if (autopilot) false else state.requireConfirmForDangerousCommands,
            redactSensitiveOutput = if (autopilot) true else state.redactSensitiveOutput,
            auditAiInput = if (autopilot) true else state.auditAiInput,
            allowTerminalInput = if (autopilot) true else state.allowTerminalInput,
            allowDeploymentAutomation = autopilot || state.allowDeploymentAutomation,
            requireConfirmForDeploymentActions = if (autopilot) false else state.requireConfirmForDeploymentActions,
            allowRemoteFileTransferAutomation = autopilot || state.allowRemoteFileTransferAutomation,
            allowServerRelayTransfer = autopilot || state.allowServerRelayTransfer,
            allowAutomaticRelayAuthSetup = autopilot || state.allowAutomaticRelayAuthSetup,
            allowTerminalBackedServerTransfer = autopilot || state.allowTerminalBackedServerTransfer,
            terminalBackedTransferMaxBytes = state.terminalBackedTransferMaxBytes,
            allowLocalTerminalCreation = autopilot || state.allowLocalTerminalCreation,
            allowPredefinedTerminalCreation = autopilot || state.allowPredefinedTerminalCreation,
            auditLogPath = AuditLog(settings).path(),
        )
    }

    private fun TerminalDescriptor.toResult(): TerminalInfoResult =
        TerminalInfoResult(
            id = id,
            logical_id = logicalId,
            title = title,
            projectName = projectName,
            projectPath = projectPath,
            selected = selected,
            active = active,
            ssh = ssh,
            aiAccessEnabled = aiAccessEnabled,
            canRead = canRead,
            canWrite = canWrite,
            backend = backend,
            warnings = warnings,
        )
}

@Serializable
data class RelayAuthSetupResult(
    val ready: Boolean,
    val sourceServer: String,
    val targetServer: String,
    val sourceHost: String,
    val sourcePort: Int,
    val sourceUser: String,
    val targetHost: String,
    val targetPort: Int,
    val targetUser: String,
    val sourceTerminalId: String?,
    val commands: List<String>,
    val message: String,
    val warnings: List<String> = emptyList(),
    val requiresOneTimePassword: Boolean = true,
)

@Serializable
data class TerminalMcpCapabilitiesResult(
    val toolsetAlwaysIncluded: Boolean,
    val pluginVersion: String,
    val currentSettings: TerminalSettingsResult,
    val recommendedWorkflow: List<String>,
    val terminalTools: List<String>,
    val deploymentTools: List<String>,
    val limitation: String,
)

@Serializable
data class TerminalInfoResult(
    val id: String,
    val logical_id: String,
    val title: String,
    val projectName: String,
    val projectPath: String?,
    val selected: Boolean,
    val active: Boolean,
    val ssh: Boolean,
    val aiAccessEnabled: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val backend: String,
    val warnings: List<String>,
)

@Serializable
data class TerminalListResult(
    val terminals: List<TerminalInfoResult>,
    val count: Int = terminals.size,
    val selectedTerminalId: String? = terminals.firstOrNull { it.selected }?.id,
    val activeTerminalId: String? = terminals.firstOrNull { it.active }?.id,
    val selectorHints: List<String> = listOf(
        "id",
        "id prefix",
        "selected",
        "active",
        "current",
        "unique title/project/path substring",
    ),
)

@Serializable
data class TerminalReadResult(
    val terminal_id: String,
    val output: String,
    val redacted: Boolean,
    val success: Boolean = true,
    val error: String? = null,
)

@Serializable
data class TerminalActionResult(
    val terminal_id: String,
    val message: String,
    val success: Boolean = true,
    val error: String? = null,
)

@Serializable
data class TerminalSettingsResult(
    val permissionMode: String,
    val terminalScope: String,
    val autoPilotMode: Boolean,
    val allowSshTerminals: Boolean,
    val requireConfirmForSshInput: Boolean,
    val requireConfirmForDangerousCommands: Boolean,
    val redactSensitiveOutput: Boolean,
    val auditAiInput: Boolean,
    val allowTerminalInput: Boolean,
    val allowDeploymentAutomation: Boolean,
    val requireConfirmForDeploymentActions: Boolean,
    val allowRemoteFileTransferAutomation: Boolean,
    val allowServerRelayTransfer: Boolean,
    val allowAutomaticRelayAuthSetup: Boolean,
    val allowTerminalBackedServerTransfer: Boolean,
    val terminalBackedTransferMaxBytes: Long,
    val allowLocalTerminalCreation: Boolean,
    val allowPredefinedTerminalCreation: Boolean,
    val auditLogPath: String,
)

@Serializable
data class TerminalPresetInfoResult(
    val id: String,
    val title: String,
    val description: String?,
    val projectName: String,
    val projectPath: String?,
    val sshLike: Boolean,
    val actionClass: String,
)

@Serializable
data class TerminalPresetListResult(
    val presets: List<TerminalPresetInfoResult>,
    val count: Int = presets.size,
)

@Serializable
data class TerminalCreationResult(
    val message: String,
    val terminals: List<TerminalInfoResult>,
)

@Serializable
data class RemoteServerInfoResult(
    val id: String,
    val name: String,
    val typeId: String,
    val typeName: String,
    val configurationClass: String,
    val uniqueId: String,
)

@Serializable
data class SshEndpointInfoResult(
    val id: String,
    val name: String,
    val user: String?,
    val host: String,
    val port: Int?,
    val rootPath: String,
    val osFamily: String,
)

@Serializable
data class DeploymentPathMappingResult(
    val localPath: String,
    val deployPath: String,
    val webPath: String?,
    val valid: Boolean,
    val warnings: List<String>,
)

@Serializable
data class WebDeploymentTargetResult(
    val id: String,
    val name: String,
    val selected: Boolean,
    val projectName: String,
    val projectPath: String?,
    val accessType: String,
    val host: String?,
    val port: Int,
    val rootFolder: String?,
    val mappings: List<DeploymentPathMappingResult>,
    val valid: Boolean,
    val warnings: List<String>,
)

@Serializable
data class WebDeploymentTargetListResult(
    val targets: List<WebDeploymentTargetResult>,
    val count: Int = targets.size,
    val selectedTargetId: String? = targets.firstOrNull { it.selected }?.id,
)

@Serializable
data class DeploymentServerSelectionResult(
    val selected: WebDeploymentTargetResult?,
    val previous_server_id: String?,
    val scope: String,
    val success: Boolean,
    val error: String? = null,
)

@Serializable
data class RemoteServerListResult(
    val deploymentServers: List<RemoteServerInfoResult>,
    val sshEndpoints: List<SshEndpointInfoResult>,
    val webDeploymentTargets: List<WebDeploymentTargetResult>,
)

@Serializable
data class DeploymentConfigurationInfoResult(
    val id: String,
    val name: String,
    val projectName: String,
    val projectPath: String?,
    val serverName: String,
    val serverType: String,
    val sourceName: String?,
    val sourcePath: String?,
    val valid: Boolean,
)

@Serializable
data class DeploymentConfigurationListResult(
    val configurations: List<DeploymentConfigurationInfoResult>,
    val count: Int = configurations.size,
)

@Serializable
data class DeploymentActionResult(
    val message: String,
    val success: Boolean = true,
    val error: String? = null,
)

@Serializable
data class FileTransferResult(
    val endpointId: String,
    val endpointName: String,
    val mode: String,
    val localPath: String,
    val remotePath: String,
    val message: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val success: Boolean = true,
    @SerialName("exit_code")
    val exit_code: Int? = null,
    val backend: String? = null,
    val error: String? = null,
)


private fun TerminalPresetDescriptor.toResult(): TerminalPresetInfoResult =
    TerminalPresetInfoResult(
        id = id,
        title = title,
        description = description,
        projectName = projectName,
        projectPath = projectPath,
        sshLike = sshLike,
        actionClass = actionClass,
    )

private fun TerminalDescriptor.toCreationResult(): TerminalCreationResult =
    TerminalCreationResult(
        message = "Local terminal created.",
        terminals = listOf(
            TerminalInfoResult(
                id = id,
                logical_id = logicalId,
                title = title,
                projectName = projectName,
                projectPath = projectPath,
                selected = selected,
                active = active,
                ssh = ssh,
                aiAccessEnabled = aiAccessEnabled,
                canRead = canRead,
                canWrite = canWrite,
                backend = backend,
                warnings = warnings,
            ),
        ),
    )

private fun RemoteServerDescriptor.toResult(): RemoteServerInfoResult =
    RemoteServerInfoResult(
        id = id,
        name = name,
        typeId = typeId,
        typeName = typeName,
        configurationClass = configurationClass,
        uniqueId = uniqueId,
    )

private fun SshEndpointDescriptor.toResult(): SshEndpointInfoResult =
    SshEndpointInfoResult(
        id = id,
        name = name,
        user = user,
        host = host,
        port = port,
        rootPath = rootPath,
        osFamily = osFamily,
    )

private fun DeploymentPathMappingDescriptor.toResult(): DeploymentPathMappingResult =
    DeploymentPathMappingResult(
        localPath = localPath,
        deployPath = deployPath,
        webPath = webPath,
        valid = valid,
        warnings = warnings,
    )

private fun WebDeploymentTargetDescriptor.toResult(): WebDeploymentTargetResult =
    WebDeploymentTargetResult(
        id = id,
        name = name,
        selected = selected,
        projectName = projectName,
        projectPath = projectPath,
        accessType = accessType,
        host = host,
        port = port,
        rootFolder = rootFolder,
        mappings = mappings.map { it.toResult() },
        valid = valid,
        warnings = warnings,
    )

private fun DeploymentConfigurationDescriptor.toResult(): DeploymentConfigurationInfoResult =
    DeploymentConfigurationInfoResult(
        id = id,
        name = name,
        projectName = projectName,
        projectPath = projectPath,
        serverName = serverName,
        serverType = serverType,
        sourceName = sourceName,
        sourcePath = sourcePath,
        valid = valid,
    )

private fun TransferOperationResult.toResult(): FileTransferResult =
    FileTransferResult(
        endpointId = endpointId,
        endpointName = endpointName,
        mode = mode,
        localPath = localPath,
        remotePath = remotePath,
        message = message,
        success = success,
        exit_code = exitCode,
        backend = backend,
    )

private fun RelayAuthSetupDescriptor.toResult(): RelayAuthSetupResult = RelayAuthSetupResult(
    ready = ready,
    sourceServer = sourceServer,
    targetServer = targetServer,
    sourceHost = sourceHost,
    sourcePort = sourcePort,
    sourceUser = sourceUser,
    targetHost = targetHost,
    targetPort = targetPort,
    targetUser = targetUser,
    sourceTerminalId = sourceTerminalId,
    commands = commands,
    message = message,
    warnings = warnings,
    requiresOneTimePassword = requiresOneTimePassword,
)
