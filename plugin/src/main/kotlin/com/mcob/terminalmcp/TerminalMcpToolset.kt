package com.mcob.terminalmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
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

    override fun isExperimental(): Boolean = true

    override fun alwaysIncluded(): Boolean = settings.isAutopilotEnabled()

    @McpTool(title = "列出 PyCharm 全部终端")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription(
        """
        列出所有已打开 PyCharm 项目中的集成终端标签页。
        遵循终端 MCP 桥接的全局权限模式和每个终端的 AI 访问开关。
        """
    )
    fun list_global_terminals(): TerminalListResult =
        TerminalListResult(operations.listTerminals().map { it.toResult() })

    @McpTool(title = "读取 PyCharm 终端输出")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("根据选择器读取 PyCharm 集成终端的最近输出。")
    fun read_global_terminal(
        @McpDescription("终端 id、logicalId、id 前缀、selected/current/active，或唯一的标题、项目名、路径片段。")
        terminal_id: String,
        @McpDescription("最多返回的行数。")
        max_lines: Int = 200,
        @McpDescription("可选，上一轮读取返回的输出 cursor。")
        output_cursor: Int? = null,
    ): TerminalReadResult = try {
        val snapshot = operations.readTerminal(terminal_id, max_lines, output_cursor)
        TerminalReadResult(
            terminal_id = snapshot.terminalId,
            logical_id = snapshot.logicalId,
            output = snapshot.output,
            redacted = settings.snapshot().redactSensitiveOutput,
            next_cursor = snapshot.nextCursor,
            cursor_reset = snapshot.cursorReset,
            has_more = snapshot.hasMore,
        )
    } catch (ex: ExpectedMcpFailureException) {
        TerminalReadResult(
            terminal_id = terminal_id,
            logical_id = "",
            output = "",
            redacted = settings.snapshot().redactSensitiveOutput,
            next_cursor = output_cursor ?: 0,
            success = false,
            error = ex.message ?: "Terminal access denied.",
        )
    }

    @McpTool(title = "向 PyCharm 终端输入文本")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        向 PyCharm 集成终端发送文本。
        press_enter 为 true 时，将文本作为命令执行。
        SSH 和危险命令是否需要确认取决于全局权限模式。
        """
    )
    fun send_global_terminal_text(
        @McpDescription("终端选择器：id、id 前缀、selected/current/active，或唯一标题、项目名、路径片段。")
        terminal_id: String,
        @McpDescription("要发送到终端的文本或命令。")
        text: String,
        @McpDescription("发送文本后是否按回车。")
        press_enter: Boolean = false,
    ): TerminalActionResult = terminalAction(terminal_id) {
        TerminalActionResult(
            terminal_id = terminal_id,
            message = operations.sendText(terminal_id, text, press_enter),
        )
    }

    @McpTool(title = "中断 PyCharm 终端任务")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("向 PyCharm 集成终端发送 Ctrl+C/ETX，不改变普通文本输入行为。")
    fun interrupt_global_terminal(
        @McpDescription("终端选择器：id、id 前缀、selected/current/active，或唯一标题、项目名、路径片段。")
        terminal_id: String,
    ): TerminalActionResult = terminalAction(terminal_id) {
        TerminalActionResult(
            terminal_id = terminal_id,
            message = operations.interrupt(terminal_id),
        )
    }

    @McpTool(title = "在 PyCharm 终端执行命令")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("向选定的 PyCharm 集成终端发送命令并按回车。")
    fun execute_global_terminal_command(
        @McpDescription("终端 id、logicalId、id 前缀、selected/current/active，或唯一的标题、项目名、路径片段。")
        terminal_id: String,
        @McpDescription("要执行的命令。")
        command: String,
        @McpDescription("等待命令完成的最长时间，单位为毫秒。")
        timeout_ms: Long = 30000,
        @McpDescription("本次调用最多返回的输出行数。")
        max_output_lines: Int = 300,
    ): TerminalExecutionResult = try {
        val snapshot = operations.executeAndWait(terminal_id, command, timeout_ms, max_output_lines)
        TerminalExecutionResult(
            terminal_id = snapshot.terminalId,
            logical_id = snapshot.logicalId,
            message = snapshot.output,
            output = snapshot.output,
            next_cursor = snapshot.nextCursor,
            completed = snapshot.completed,
            timed_out = snapshot.timedOut,
            exit_code = snapshot.exitCode,
            duration_ms = snapshot.durationMs,
            success = snapshot.completed && snapshot.exitCode == 0,
            error = if (snapshot.completed) null else "Command did not complete before the timeout.",
        )
    } catch (ex: ExpectedMcpFailureException) {
        TerminalExecutionResult(
            terminal_id = terminal_id,
            logical_id = "",
            message = ex.message ?: "Terminal command was not completed.",
            output = "",
            next_cursor = 0,
            completed = false,
            timed_out = false,
            exit_code = null,
            duration_ms = 0,
            success = false,
            error = ex.message ?: "Terminal command was not completed.",
        )
    }

    @McpTool(title = "列出终端创建预设")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("列出 PyCharm 终端预设，包括已配置终端和 SSH 终端操作。")
    fun list_predefined_terminals(
        @McpDescription("可选的项目名称或路径选择器。")
        project_selector: String? = null,
    ): TerminalPresetListResult =
        TerminalPresetListResult(
            terminalCreationOperations.listPredefinedTerminals(project_selector).map { it.descriptor.toResult() },
        )

    @McpTool(title = "创建本地 PyCharm 终端")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("通过 PyCharm 创建新的本地集成终端标签页。")
    fun create_local_terminal(
        @McpDescription("可选的项目名称或路径选择器。")
        project_selector: String? = null,
        @McpDescription("可选的终端标签页标题。")
        title: String? = null,
        @McpDescription("可选的本地工作目录。")
        working_directory: String? = null,
    ): TerminalCreationResult =
        terminalCreationOperations.createLocalTerminal(project_selector, title, working_directory).toCreationResult()

    @McpTool(title = "创建预设 PyCharm 终端")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("打开一个已配置的 PyCharm 终端操作，包括 SSH 或远程终端预设。")
    fun create_predefined_terminal(
        @McpDescription("预设 id、id 前缀、标题、描述或唯一项目片段。")
        selector: String,
        @McpDescription("可选的项目名称或路径选择器。")
        project_selector: String? = null,
    ): TerminalCreationResult {
        val created = terminalCreationOperations.openPredefinedTerminal(selector, project_selector)
        return TerminalCreationResult(
            message = "Configured terminal action invoked.",
            terminals = created.map { it.toResult() },
        )
    }

    @McpTool(title = "列出 PyCharm 远程服务器")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("列出 PyCharm 远程服务器配置和已知 SSH Eel endpoint。")
    fun list_remote_servers(): RemoteServerListResult =
        RemoteServerListResult(
            deploymentServers = remoteOperations.listRemoteServers().map { it.toResult() },
            sshEndpoints = remoteOperations.listSshEndpoints().map { it.toResult() },
            webDeploymentTargets = remoteOperations.listWebDeploymentTargets(null).map { it.toResult() },
        )

    @McpTool(title = "列出选定的 PyCharm 部署服务器")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("列出已打开项目中的 Web Deployment 服务器，包括选定/默认服务器和本地到远程的映射。")
    fun list_selected_deployment_servers(
        @McpDescription("可选的项目名称或路径选择器。")
        project_selector: String? = null,
    ): WebDeploymentTargetListResult =
        WebDeploymentTargetListResult(
            remoteOperations.listWebDeploymentTargets(project_selector).map { it.toResult() },
        )

    @McpTool(title = "选择已保存的 PyCharm 部署服务器")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("为本次终端 MCP 桥接会话选择一个已保存的 Web Deployment 服务器。不改变 PyCharm 持久化默认值，也不修改 .idea/deployment.xml。")
    fun select_deployment_server(
        @McpDescription("可选的项目名称或规范化本地路径。")
        project_selector: String? = null,
        @McpDescription("已保存服务器的 id、名称、主机或唯一选择器。")
        server_selector: String,
    ): WebDeploymentTargetResult = remoteOperations.selectDeploymentServer(project_selector, server_selector).toResult()

    @McpTool(title = "上传到已保存的 PyCharm 部署服务器")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("直接针对指定的已保存服务器启动 PyCharm Web Deployment 上传任务，不改变持久化默认服务器。")
    fun upload_to_deployment_server(
        @McpDescription("可选的项目名称或规范化本地路径。")
        project_selector: String? = null,
        @McpDescription("已保存服务器的 id、名称、主机或唯一选择器。")
        server_selector: String,
        @McpDescription("可选的本地文件或目录，默认为项目根目录。")
        local_path: String? = null,
    ): DeploymentActionResult = deploymentAction {
        DeploymentActionResult(remoteOperations.uploadToDeploymentServer(project_selector, server_selector, local_path))
    }

    @McpTool(title = "在已保存的 PyCharm 部署服务器之间传输")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("使用已保存源服务器的 PyCharm Web Deployment 下载任务；下载完成后再向已保存目标服务器启动上传。操作是异步的，不改变持久化默认服务器。")
    fun transfer_between_deployment_servers(
        @McpDescription("可选的项目名称或规范化本地路径。")
        project_selector: String? = null,
        @McpDescription("已保存源服务器选择器。")
        source_server_selector: String,
        @McpDescription("已保存目标服务器选择器。")
        target_server_selector: String,
        @McpDescription("PyCharm 在两个操作之间使用的本地暂存文件或目录。")
        local_path: String? = null,
    ): DeploymentActionResult = deploymentAction {
        DeploymentActionResult(remoteOperations.transferBetweenDeploymentServers(project_selector, source_server_selector, target_server_selector, local_path))
    }

    @McpTool(title = "列出 PyCharm 部署配置")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("列出 PyCharm 中已保存的 Deploy to Server 运行配置。")
    fun list_deployment_configurations(
        @McpDescription("可选的项目名称或路径选择器。")
        project_selector: String? = null,
    ): DeploymentConfigurationListResult =
        DeploymentConfigurationListResult(
            remoteOperations.listDeploymentConfigurations(project_selector).map { it.toResult() },
        )

    @McpTool(title = "运行 PyCharm 部署配置")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("运行已有的 PyCharm Deploy to Server 配置以上传或部署项目文件。")
    fun run_deployment_configuration(
        @McpDescription("已有部署配置的名称、id 或唯一服务器片段。")
        selector: String,
        @McpDescription("可选的项目名称或路径选择器。")
        project_selector: String? = null,
    ): DeploymentActionResult = deploymentAction {
        DeploymentActionResult(remoteOperations.runDeploymentConfiguration(project_selector, selector))
    }

    @McpTool(title = "上传到当前选中的 PyCharm 部署服务器")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("使用 PyCharm 官方“上传到默认服务器”操作，将文件上传到项目选定的部署服务器。")
    fun upload_to_selected_deployment_server(
        @McpDescription("可选的项目名称或路径选择器。")
        project_selector: String? = null,
        @McpDescription("可选的本地文件或目录路径，默认为项目根目录。")
        local_path: String? = null,
    ): DeploymentActionResult = deploymentAction {
        DeploymentActionResult(remoteOperations.uploadToSelectedDeploymentServer(project_selector, local_path))
    }

    @McpTool(title = "执行 PyCharm 部署操作")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("针对项目选定服务器调用 PyCharm 官方 Web Deployment 操作：上传、下载/拉取、同步、比较或对应的选择器操作。")
    fun invoke_selected_deployment_action(
        @McpDescription("部署操作：upload、upload_to、download、download_from、pull、pull_from、sync、sync_with、compare 或 compare_with。")
        action: String,
        @McpDescription("可选的项目名称或路径选择器。")
        project_selector: String? = null,
        @McpDescription("可选的本地文件或目录路径，默认为项目根目录。")
        local_path: String? = null,
    ): DeploymentActionResult = deploymentAction {
        DeploymentActionResult(remoteOperations.invokeSelectedDeploymentAction(project_selector, local_path, action))
    }

    @McpTool(title = "使用选中的 PyCharm 部署服务器传输")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("使用当前项目选中的 PyCharm Deployment 服务器和映射。没有 Eel endpoint 时，上传/同步可使用匹配的 SSH 终端。")
    fun transfer_selected_deployment_server(
        @McpDescription("可选的项目名称或路径选择器。")
        project_selector: String? = null,
        @McpDescription("可选的本地文件或目录路径，默认为项目根目录。")
        local_path: String? = null,
        @McpDescription("传输模式：upload、sync、download 或 pull。")
        mode: String = "upload",
        @McpDescription("同步上传前是否允许删除本地源中不存在的远程条目。")
        delete_missing: Boolean = false,
        @McpDescription("可选的终端选择器，用于终端回退路径；默认使用选定服务器的主机名/名称。")
        terminal_id: String? = null,
    ): FileTransferResult = fileTransferAction(local_path.orEmpty(), "", mode) {
        remoteOperations.transferWithSelectedDeploymentServer(
            project_selector,
            local_path,
            mode,
            delete_missing,
            terminal_id,
        ).toResult()
    }

    @McpTool(title = "传输 PyCharm SSH 目录")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("通过已知的 PyCharm SSH endpoint 上传、下载或同步本地目录。")
    fun transfer_remote_directory(
        @McpDescription("已知 SSH endpoint 的 id、id 前缀、主机、用户或名称。")
        endpoint_selector: String,
        @McpDescription("本地文件或目录路径。")
        local_path: String,
        @McpDescription("远程文件或目录路径。")
        remote_path: String,
        @McpDescription("传输模式：upload、download 或 sync。")
        mode: String,
        @McpDescription("同步时是否允许删除源端不存在的条目。")
        delete_missing: Boolean = false,
    ): FileTransferResult = fileTransferAction(local_path, remote_path, mode) {
        remoteOperations.transferDirectory(endpoint_selector, local_path, remote_path, mode, delete_missing).toResult()
    }

    @McpTool(title = "设置终端 AI 访问权限")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("在终端 MCP 桥接全局设置中启用或禁用某个终端标签页的 AI 访问。")
    fun set_terminal_ai_access(
        @McpDescription("终端选择器：id、id 前缀、selected/current/active，或唯一标题、项目名、路径片段。")
        terminal_id: String,
        @McpDescription("AI 是否可以访问此终端。")
        enabled: Boolean,
    ): TerminalInfoResult =
        operations.setAccess(terminal_id, enabled).toResult()

    @McpTool(title = "设置 Terminal MCP Bridge 全自动模式")
    @McpToolHints(readOnlyHint = McpToolHintValue.FALSE, destructiveHint = McpToolHintValue.TRUE)
    @McpDescription("启用或禁用终端 MCP 桥接的全局全自动模式。")
    fun set_terminal_autopilot_mode(
        @McpDescription("桥接是否以全自动模式运行。")
        enabled: Boolean,
    ): TerminalSettingsResult {
        settings.update { state ->
            state.autoPilotMode = enabled
        }
        return currentSettingsResult()
    }

    @McpTool(title = "读取 Terminal MCP Bridge 设置")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, destructiveHint = McpToolHintValue.FALSE)
    @McpDescription("返回当前终端 MCP 桥接的全局权限设置。")
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
    catch (ex: ExpectedMcpFailureException) {
        FileTransferResult(
            endpointId = "",
            endpointName = "",
            mode = mode,
            localPath = localPath,
            remotePath = remotePath,
            message = ex.message ?: "File transfer was not completed.",
            success = false,
            error = ex.message ?: "File transfer was not completed.",
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
            autoConfirmDeploymentActions = autopilot || state.autoConfirmDeploymentActions,
            allowRemoteFileTransferAutomation = autopilot || state.allowRemoteFileTransferAutomation,
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
data class TerminalInfoResult(
    val id: String,
    val logical_id: String = id,
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
    val logical_id: String = "",
    val output: String,
    val redacted: Boolean,
    val next_cursor: Int = 0,
    val cursor_reset: Boolean = false,
    val has_more: Boolean = false,
    val success: Boolean = true,
    val error: String? = null,
)

@Serializable
data class TerminalActionResult(
    val terminal_id: String,
    val logical_id: String = "",
    val message: String,
    val output: String = "",
    val next_cursor: Int = 0,
    val completed: Boolean = true,
    val timed_out: Boolean = false,
    val exit_code: Int? = null,
    val duration_ms: Long = 0,
    val success: Boolean = true,
    val error: String? = null,
)

@Serializable
data class TerminalExecutionResult(
    val terminal_id: String,
    val logical_id: String,
    val message: String,
    val output: String,
    val next_cursor: Int,
    val completed: Boolean,
    val timed_out: Boolean,
    val exit_code: Int?,
    val duration_ms: Long,
    val success: Boolean,
    val error: String?,
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
    val autoConfirmDeploymentActions: Boolean,
    val allowRemoteFileTransferAutomation: Boolean,
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
    val success: Boolean = true,
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
    )
