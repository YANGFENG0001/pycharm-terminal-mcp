package dev.terminalmcp

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.toEelApiBlocking
import com.intellij.platform.eel.provider.utils.EelPathTransfer
import com.intellij.platform.eel.provider.utils.EelPathUtils
import com.intellij.platform.ijent.ssh.IjentSshManager
import com.intellij.platform.ijent.ssh.InternalSshEelApi
import com.intellij.platform.ijent.ssh.SshEelDescriptor
import com.intellij.remoteServer.configuration.RemoteServersManager
import com.intellij.remoteServer.impl.configuration.deployment.DeployToServerRunConfiguration
import com.jetbrains.plugins.webDeployment.actions.PublishActionUtil
import com.jetbrains.plugins.webDeployment.config.GroupedServersConfigManager
import com.jetbrains.plugins.webDeployment.config.PublishConfig
import com.jetbrains.plugins.webDeployment.config.ServerPasswordSafeDeployable
import com.jetbrains.plugins.webDeployment.config.WebServerConfig
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

@OptIn(InternalSshEelApi::class)
class RemoteOperations(private val settings: TerminalMcpSettings) {
    private companion object {
        const val TERMINAL_COMMAND_WAIT_MS = 60_000L
        const val TERMINAL_POLL_MS = 250L
        const val TRANSFER_WAIT_MINUTES = 24L
        const val SERVER_RELAY_WAIT_MS = TRANSFER_WAIT_MINUTES * 60_000L
    }

    private sealed interface TransferEndpoint {
        data class Eel(val handle: SshEndpointHandle) : TransferEndpoint
        data class Terminal(
            val selector: String,
            val descriptor: TerminalDescriptor,
            val target: WebDeploymentTargetDescriptor,
        ) : TransferEndpoint
    }

    private val policy = AutomationPolicy(settings)
    private val projects = ProjectLocator()
    private val audit = AuditLog(settings)

    fun listRemoteServers(): List<RemoteServerDescriptor> = invokeOnEdt {
        RemoteServersManager.getInstance().getServers().map { server ->
            RemoteServerDescriptor(
                id = "server-${server.getUniqueId()}",
                name = server.getName(),
                typeId = server.getType().id,
                typeName = server.getType().presentableName,
                configurationClass = server.getConfiguration().javaClass.name,
                uniqueId = server.getUniqueId().toString(),
            )
        }
    }

    fun listSshEndpoints(): List<SshEndpointDescriptor> =
        runCatching<List<SshEndpointDescriptor>> {
            IjentSshManager.Default.getKnownDescriptors()
                .filterIsInstance<SshEelDescriptor>()
                .map { descriptor ->
                    val uhp = descriptor.uhp
                    SshEndpointDescriptor(
                        id = stableSshId(descriptor),
                        name = descriptor.name,
                        user = uhp.user,
                        host = uhp.host,
                        port = uhp.port,
                        rootPath = descriptor.rootPath.toString(),
                        osFamily = descriptor.osFamily.toString(),
                    )
                }
        }.getOrDefault(emptyList())

    fun listWebDeploymentTargets(projectSelector: String?): List<WebDeploymentTargetDescriptor> =
        projects.resolveMany(projectSelector).flatMap { project ->
            invokeOnEdt {
                val publishConfig = PublishConfig.getInstance(project)
                val servers = GroupedServersConfigManager.getInstance(project).flattenedServers
                val defaultName = selectedWebDeploymentServerName(project, publishConfig)
                    ?: servers.firstOrNull { hasUsableDeploymentMapping(project, publishConfig, it.getName().orEmpty()) }?.getName()
                val sessionId = DeploymentSessionSelection.get(project)
                servers.map { server ->
                    val descriptor = webDeploymentDescriptor(project, publishConfig, server, server.getName() == defaultName)
                    descriptor.copy(selected = sessionId?.let { it == descriptor.id } ?: descriptor.selected)
                }
            }
        }

    fun selectDeploymentServer(projectSelector: String?, serverSelector: String): WebDeploymentTargetDescriptor {
        policy.requireDeploymentAutomation()
        val project = projects.resolve(projectSelector)
        val target = resolveWebDeploymentTarget(project, serverSelector)
            ?: throw RemoteTransferExpectedFailureException(
                "Deployment server selector must match exactly one saved PyCharm server: $serverSelector",
            )
        DeploymentSessionSelection.set(project, target.id)
        audit.recordAutomation(
            "select-deployment-server",
            target.name,
            "project=${project.name} scope=session targetId=${target.id}",
        )
        return target.copy(selected = true)
    }

    fun sessionSelectedDeploymentServerId(projectSelector: String?): String? =
        DeploymentSessionSelection.get(projects.resolve(projectSelector))

    fun savedDeploymentTargetForTerminal(projectSelector: String?, serverSelector: String): WebDeploymentTargetDescriptor {
        if (serverSelector.isBlank()) throw RemoteTransferExpectedFailureException("必须明确指定保存的服务器 selector；不能默认使用 selected 服务器。")
        return resolveWebDeploymentTarget(projects.resolve(projectSelector), serverSelector)
            ?: throw RemoteTransferExpectedFailureException("服务器 selector 必须唯一匹配已保存的 Deployment 配置；未创建终端。")
    }

    fun listDeploymentConfigurations(projectSelector: String?): List<DeploymentConfigurationDescriptor> =
        projects.resolveMany(projectSelector).flatMap { project ->
            RunManager.getInstance(project).allSettings
                .map { it.configuration }
                .filterIsInstance<DeployToServerRunConfiguration<*, *>>()
                .map { configuration ->
                    val source = configuration.deploymentSource
                    DeploymentConfigurationDescriptor(
                        id = stableDeploymentId(project, configuration),
                        name = configuration.name,
                        projectName = project.name,
                        projectPath = project.basePath,
                        serverName = configuration.serverName,
                        serverType = configuration.serverType.presentableName,
                        sourceName = source?.presentableName,
                        sourcePath = source?.filePath,
                        valid = source?.isValid ?: false,
                    )
                }
        }

    fun runDeploymentConfiguration(projectSelector: String?, selector: String): String {
        policy.requireDeploymentAutomation()
        val project = projects.resolve(projectSelector)
        val runManager = RunManager.getInstance(project)
        val matches = runManager.allSettings
            .mapNotNull { setting ->
                val configuration = setting.configuration as? DeployToServerRunConfiguration<*, *> ?: return@mapNotNull null
                if (matches(selector, stableDeploymentId(project, configuration), configuration.name, configuration.serverName, configuration.serverType.presentableName)) {
                    setting to configuration
                }
                else null
            }
        if (matches.size != 1) error("Deployment configuration selector must match exactly one configuration.")

        val (settings, configuration) = matches.single()
        invokeOnEdt {
            applyDeploymentConfirmationPreference(project)
            confirmDeploymentActionIfNeeded(project, "Run deployment configuration '${configuration.name}'", emptyList())
            ProgramRunnerUtil.executeConfiguration(project, settings, DefaultRunExecutor.getRunExecutorInstance())
        }
        audit.recordAutomation(
            "run-deployment-configuration",
            configuration.name,
            "project=${project.name} server=${configuration.serverName}",
        )
        return "Started PyCharm deployment configuration '${configuration.name}' for ${configuration.serverName}."
    }

    fun uploadSelectedFiles(projectSelector: String?, localPaths: List<String>): SelectedFilesUploadResult {
        policy.requireRemoteFileTransferAutomation()
        require(localPaths.isNotEmpty()) { "必须明确指定至少一个文件；空列表不会上传整个项目。" }
        val project = projects.resolve(projectSelector)
        val root = Paths.get(project.basePath ?: error("项目没有本地根目录。"))
        val target = defaultWebDeploymentTarget(project)
            ?: error("项目没有选定的 Deployment 服务器。")
        // Validate the entire selection before any remote writes.
        val paths = SelectedFilePaths.resolve(root, localPaths)
        paths.forEach { path ->
            target.remotePathFor(path)
            invokeOnEdt { resolveVirtualFiles(project, path.toString()) }
        }
        val results = mutableListOf<SelectedFileUploadItem>()
        for (path in paths) {
            try {
                val result = nativeDefaultDeploymentUpload(project, target, path, "upload")
                    ?: error("选定服务器已改变或原生 Deployment 不可用；拒绝回退。")
                results += SelectedFileUploadItem(path.toString(), target.remotePathFor(path), result.success, result.message)
                if (!result.success) break
            } catch (ex: Exception) {
                results += SelectedFileUploadItem(path.toString(), target.remotePathFor(path), false,
                    ex.message ?: "上传失败；远端可能存在部分文件。")
                break
            }
        }
        return SelectedFilesUploadResult(
            success = results.size == paths.size && results.all { it.success },
            serverId = target.id,
            requestedCount = paths.size,
            files = results,
            skippedPaths = paths.drop(results.size).map { it.toString() },
            message = "仅上传明确列出的文件；不递归目录、不上传其他文件、不删除远端文件。失败后停止，已上传文件不回滚。",
        )
    }

    fun uploadToSelectedDeploymentServer(projectSelector: String?, localPath: String?, allowFullProject: Boolean = false): String {
        val result = transferWithSelectedDeploymentServer(
            projectSelector = projectSelector,
            localPath = localPath,
            mode = "upload",
            deleteMissing = false,
            terminalSelector = null,
            allowFullProject = allowFullProject,
        )
        return result.message
    }

    fun invokeSelectedDeploymentAction(projectSelector: String?, localPath: String?, action: String, allowFullProject: Boolean = false): String {
        policy.requireDeploymentAutomation()
        when (action.lowercase()) {
            "upload", "deploy", "upload_to", "deploy_to" -> return transferWithSelectedDeploymentServer(
                projectSelector, localPath, "upload", false, null, allowFullProject,
            ).message
            "download", "pull", "download_from", "pull_from" -> return transferWithSelectedDeploymentServer(
                projectSelector, localPath, "download", false, null,
            ).message
            "sync", "sync_with" -> return transferWithSelectedDeploymentServer(
                projectSelector, localPath, "sync", false, null, allowFullProject,
            ).message
        }
        val project = projects.resolve(projectSelector)
        val files = invokeOnEdt {
            FileDocumentManager.getInstance().saveAllDocuments()
            resolveVirtualFiles(project, localPath)
        }
        val actionId = when (action.lowercase()) {
            "upload", "deploy" -> "PublishGroup.Upload"
            "upload_to", "deploy_to" -> "PublishGroup.UploadTo"
            "download", "pull" -> "PublishGroup.Download"
            "download_from", "pull_from" -> "PublishGroup.DownloadFrom"
            "sync" -> "PublishGroup.SyncLocalVsRemote"
            "sync_with" -> "PublishGroup.SyncLocalVsRemoteWith"
            "compare" -> "PublishGroup.CompareLocalVsRemote"
            "compare_with" -> "PublishGroup.CompareLocalVsRemoteWith"
            else -> error("Unknown deployment action: $action")
        }
        invokeOnEdt {
            confirmDeploymentActionIfNeeded(project, "Deployment action $actionId", files)
            applyDeploymentConfirmationPreference(project)
            val ideAction = ActionManager.getInstance().getAction(actionId)
                ?: error("PyCharm action not found: $actionId")
            val dataContext = deploymentDataContext(project, files)
            val event = AnActionEvent.createFromDataContext(
                "Terminal MCP Bridge",
                ideAction.templatePresentation.clone(),
                dataContext,
            )
            ideAction.actionPerformed(event)
        }
        val target = defaultWebDeploymentTarget(project)
        audit.recordAutomation(
            "web-deployment-action-$action",
            target?.name ?: "default-server",
            "project=${project.name} actionId=$actionId local=${files.joinToString(",") { it.path }}",
        )
        return "Invoked PyCharm deployment action $actionId" +
            (target?.let { " for selected server '${it.name}'." } ?: ".")
    }

    fun transferWithSelectedDeploymentServer(
        projectSelector: String?,
        localPath: String?,
        mode: String,
        deleteMissing: Boolean,
        terminalSelector: String?,
        allowFullProject: Boolean = false,
    ): TransferOperationResult {
        policy.requireRemoteFileTransferAutomation()
        val project = projects.resolve(projectSelector)
        val normalizedMode = mode.lowercase()
        val local = if (normalizedMode in setOf("upload", "sync")) {
            resolveUploadScope(project, localPath, allowFullProject)
        } else resolveLocalPath(project, localPath)
        val target = defaultWebDeploymentTarget(project)
            ?: throw RemoteTransferExpectedFailureException("The project has no selected/default PyCharm Deployment server.")
        val remotePath = target.remotePathFor(local)
        if (normalizedMode !in setOf("upload", "sync", "download", "pull")) {
            throw RemoteTransferExpectedFailureException("Transfer mode must be upload, download, pull, or sync.")
        }
        if (normalizedMode == "upload" || (normalizedMode == "sync" && !deleteMissing)) {
            nativeDefaultDeploymentUpload(project, target, local, normalizedMode)?.let { return it }
        }
        val endpoint = resolveTransferEndpoint(target, requireWrite = normalizedMode == "upload" || normalizedMode == "sync")
        invokeOnEdt {
            applyDeploymentConfirmationPreference(project)
            confirmDeploymentActionIfNeeded(project, "Headless $normalizedMode to $remotePath", emptyList())
        }

        when (normalizedMode) {
            "upload" -> uploadToEndpoint(project, endpoint, local, remotePath)
            "sync" -> {
                if (endpoint is TransferEndpoint.Eel && Files.isDirectory(local)) {
                    val localEelPath = EelPath.parse(local.toString(), LocalEelDescriptor)
                    val remoteEelPath = EelPath.parse(remotePath, endpoint.handle.descriptor)
                    runBlocking {
                        EelPathTransfer.directoryOnlySync(localEelPath, remoteEelPath, deleteMissing) { _: Path -> false }
                    }
                } else {
                    uploadToEndpoint(project, endpoint, local, remotePath)
                }
            }
            "download", "pull" -> downloadFromEndpoint(endpoint, remotePath, local)
            else -> throw RemoteTransferExpectedFailureException("Transfer mode must be upload, download, pull, or sync.")
        }
        audit.recordAutomation(
            "web-deployment-headless-$normalizedMode",
            target.name,
            "project=${project.name} local=$local remote=$remotePath backend=${endpoint.javaClass.simpleName} deleteMissing=$deleteMissing",
        )
        return TransferOperationResult(
            endpointId = target.id,
            endpointName = target.name,
            mode = normalizedMode,
            localPath = local.toString(),
            remotePath = remotePath,
            message = "Completed PyCharm headless $normalizedMode for '${target.name}'.",
        )
    }

    /**
     * Uses PyCharm's own Web Deployment transfer implementation. The call is
     * uses the selected session server without changing the persisted project
     * deployment file. The server's SFTP/Rsync setting is preserved by PyCharm.
     */
    private fun nativeDefaultDeploymentUpload(
        project: Project,
        target: WebDeploymentTargetDescriptor,
        local: Path,
        mode: String,
    ): TransferOperationResult? {
        val sessionId = DeploymentSessionSelection.get(project)
        var previousDefaultName: String? = null
        var sessionOverrideApplied = false
        val files = invokeOnEdt {
            val publishConfig = PublishConfig.getInstance(project)
            val defaultName = publishConfig.defaultServerOrGroupName
            val isActualDefault = !defaultName.isNullOrBlank() && defaultName.equals(target.name, ignoreCase = true)
            val isSelectedSessionServer = sessionId != null && sessionId == target.id
            if (!isActualDefault && !isSelectedSessionServer) return@invokeOnEdt null
            if (!isActualDefault) {
                previousDefaultName = defaultName
                publishConfig.setDefaultGroupOrServerName(target.name)
                sessionOverrideApplied = true
            }
            FileDocumentManager.getInstance().saveAllDocuments()
            resolveVirtualFiles(project, local.toString())
        } ?: return null

        try {
            invokeOnEdt {
                applyDeploymentConfirmationPreference(project)
                confirmDeploymentActionIfNeeded(project, "Native PyCharm Deployment $mode", files)
            }
            files.forEach { file ->
                val promise = invokeOnEdt { PublishActionUtil.uploadToDefaultServer(project, file) }
                promise.blockingGet(TRANSFER_WAIT_MINUTES.toInt(), TimeUnit.MINUTES)
            }
        } catch (ex: Exception) {
            val cause = ex.cause ?: ex
            throw RemoteTransferExpectedFailureException(
                "PyCharm native Deployment $mode failed for '${target.name}': ${cause.message ?: cause.javaClass.simpleName}",
            )
        } finally {
            if (sessionOverrideApplied) {
                invokeOnEdt {
                    PublishConfig.getInstance(project).setDefaultGroupOrServerName(previousDefaultName.orEmpty())
                }
            }
        }
        audit.recordAutomation(
            "web-deployment-native-$mode",
            target.name,
            "project=${project.name} local=$local mode=$mode backend=pycharm-deployment-session serverId=${target.id}",
        )
        return TransferOperationResult(
            endpointId = target.id,
            endpointName = target.name,
            mode = mode,
            localPath = local.toString(),
            remotePath = target.remotePathFor(local),
            message = "Completed PyCharm native Deployment $mode for '${target.name}' using its configured SFTP/Rsync backend.",
        )
    }

    fun transferBetweenDeploymentServers(
        projectSelector: String?,
        sourceServerSelector: String,
        targetServerSelector: String,
        localPath: String?,
        sourcePath: String?,
        targetPath: String?,
        mode: String,
        deleteMissing: Boolean,
        allowFullProject: Boolean = false,
    ): TransferOperationResult {
        policy.requireRemoteFileTransferAutomation()
        val project = projects.resolve(projectSelector)
        val source = resolveWebDeploymentTarget(project, sourceServerSelector)
            ?: throw RemoteTransferExpectedFailureException(
                "Source Deployment server selector must match exactly one saved PyCharm server: $sourceServerSelector",
            )
        val target = resolveWebDeploymentTarget(project, targetServerSelector)
            ?: throw RemoteTransferExpectedFailureException(
                "Target Deployment server selector must match exactly one saved PyCharm server: $targetServerSelector",
            )
        if (source.id == target.id) {
            throw RemoteTransferExpectedFailureException("Source and target Deployment servers must be different.")
        }
        if (!source.valid) throw RemoteTransferExpectedFailureException("Source server '${source.name}' has no valid deployment mapping.")
        if (!target.valid) throw RemoteTransferExpectedFailureException("Target server '${target.name}' has no valid deployment mapping.")

        val normalizedMode = mode.lowercase()
        if (normalizedMode !in setOf("copy", "upload", "sync", "relay", "server-relay")) {
            throw RemoteTransferExpectedFailureException("Server-to-server transfer mode must be copy, sync, or server-relay.")
        }
        val isServerRelay = normalizedMode == "relay" || normalizedMode == "server-relay"
        val explicitRelayPaths = isServerRelay && !sourcePath.isNullOrBlank() && !targetPath.isNullOrBlank()
        val local = if (explicitRelayPaths) resolveLocalPath(project, localPath)
            else resolveUploadScope(project, localPath, allowFullProject)
        if (!isServerRelay && !Files.exists(local)) {
            throw RemoteTransferExpectedFailureException("Local transfer path does not exist: $local")
        }
        val sourceRemote = sourcePath?.trim().takeUnless { it.isNullOrBlank() } ?: source.remotePathFor(local)
        val targetRemote = targetPath?.trim().takeUnless { it.isNullOrBlank() } ?: target.remotePathFor(local)
        if (isServerRelay) {
            return serverRelayTransfer(
                project,
                source,
                target,
                sourceRemote,
                targetRemote,
                local,
                deleteMissing,
                sourcePathWasExplicit = !sourcePath.isNullOrBlank(),
            )
        }
        val sourceEndpoint = resolveTransferEndpoint(source, requireWrite = false)
        val targetEndpoint = resolveTransferEndpoint(target, requireWrite = true)
        val usesTerminalFallback = sourceEndpoint is TransferEndpoint.Terminal || targetEndpoint is TransferEndpoint.Terminal
        if (usesTerminalFallback && Files.isDirectory(local)) {
            throw RemoteTransferExpectedFailureException(
                "Terminal-backed server transfer supports small files only; use PyCharm SSH/Eel or Web Deployment APIs for directory transfer.",
            )
        }
        if (usesTerminalFallback) {
            val maxBytes = settings.snapshot().terminalBackedTransferMaxBytes.coerceAtLeast(1L)
            val localBytes = localTransferSize(local)
            if (localBytes > maxBytes) {
                throw RemoteTransferExpectedFailureException(
                    "Refusing terminal fallback for $localBytes-byte file; the configured interactive-terminal limit is $maxBytes bytes. Use PyCharm Deployment/SFTP or SSH/Eel for large files.",
                )
            }
        }
        val stagingRoot = Files.createTempDirectory("terminal-mcp-server-transfer-")
        val staged = stagingRoot.resolve(local.fileName?.toString().orEmpty().ifBlank { "payload" })

        invokeOnEdt {
            applyDeploymentConfirmationPreference(project)
            confirmDeploymentActionIfNeeded(
                project,
                "Transfer '${source.name}' to '${target.name}'",
                emptyList(),
            )
        }
        try {
            downloadFromEndpoint(sourceEndpoint, sourceRemote, staged)
            if (normalizedMode == "sync" && Files.isDirectory(staged)) {
                when (targetEndpoint) {
                    is TransferEndpoint.Eel -> {
                        val localEelPath = EelPath.parse(staged.toString(), LocalEelDescriptor)
                        val remoteEelPath = EelPath.parse(targetRemote, targetEndpoint.handle.descriptor)
                        runBlocking {
                            EelPathTransfer.directoryOnlySync(localEelPath, remoteEelPath, deleteMissing) { _: Path -> false }
                        }
                    }
                    is TransferEndpoint.Terminal -> {
                        throw RemoteTransferExpectedFailureException(
                            "Terminal-backed server transfer supports small files only; directory sync requires PyCharm SSH/Eel endpoints.",
                        )
                    }
                }
            } else {
                uploadToEndpoint(project, targetEndpoint, staged, targetRemote)
            }
        } finally {
            deleteRecursively(stagingRoot)
        }
        audit.recordAutomation(
            "transfer-between-deployment-servers",
            "${source.name} -> ${target.name}",
            "project=${project.name} source=$sourceRemote target=$targetRemote local=${localPath ?: project.basePath} deleteMissing=$deleteMissing",
        )
        return TransferOperationResult(
            endpointId = target.id,
            endpointName = "${source.name} -> ${target.name}",
            mode = normalizedMode,
            localPath = local.toString(),
            remotePath = targetRemote,
            message = "Transferred from PyCharm Deployment server '${source.name}' to '${target.name}' through PyCharm SSH/Eel APIs.",
        )
    }


    fun prepareServerRelayAuthentication(
        projectSelector: String?,
        sourceServerSelector: String,
        targetServerSelector: String,
    ): RelayAuthSetupDescriptor {
        policy.requireRemoteFileTransferAutomation()
        val project = projects.resolve(projectSelector)
        val source = resolveWebDeploymentTarget(project, sourceServerSelector)
            ?: throw RemoteTransferExpectedFailureException("Source Deployment server selector must match exactly one saved PyCharm server: ${sourceServerSelector}")
        val target = resolveWebDeploymentTarget(project, targetServerSelector)
            ?: throw RemoteTransferExpectedFailureException("Target Deployment server selector must match exactly one saved PyCharm server: ${targetServerSelector}")
        if (source.id == target.id) throw RemoteTransferExpectedFailureException("Source and target Deployment servers must be different.")
        val sourceHost = source.host?.trim().orEmpty()
        val targetHost = target.host?.trim().orEmpty()
        val sourceUser = source.name.substringBefore('@', "").trim()
        val targetUser = target.name.substringBefore('@', "").trim()
        if (sourceHost.isBlank() || targetHost.isBlank() || sourceUser.isBlank() || targetUser.isBlank() || sourceUser == source.name || targetUser == target.name) {
            throw RemoteTransferExpectedFailureException("PyCharm saved server names must expose user@host so relay SSH setup can be generated without reading passwords.")
        }
        val sourceTerminal = runCatching { findTerminalForDeploymentTarget(source, requireWrite = true) }.getOrNull()
        val targetConfig = resolveWebDeploymentServer(project, targetServerSelector)
            ?: throw RemoteTransferExpectedFailureException("无法取得目标服务器的 PyCharm 内存配置；未读取任何密码。")
        val keyPath = "${'$'}HOME/.ssh/id_ed25519_mcp_relay"
        val configPath = "${'$'}HOME/.ssh/config"
        val hostLine = "Host ${targetHost}"
        val commands = listOf(
            "umask 077; mkdir -p ${'$'}HOME/.ssh; if [ ! -f ${shQuote(keyPath)} ]; then ssh-keygen -q -t ed25519 -f ${shQuote(keyPath)} -N ''; fi",
            "ssh-copy-id -i ${shQuote("${keyPath}.pub")} -p ${target.port} ${shQuote("${targetUser}@${targetHost}")}",
            "if ! grep -qF ${shQuote(hostLine)} ${shQuote(configPath)} 2>/dev/null; then printf '%s\\n' ${shQuote(hostLine)} ${shQuote("  User ${targetUser}")} ${shQuote("  Port ${target.port}")} ${shQuote("  IdentityFile ${keyPath}")} ${shQuote("  IdentitiesOnly yes")} >> ${shQuote(configPath)}; chmod 600 ${shQuote(configPath)}; fi",
            "ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -p ${target.port} ${shQuote("${targetUser}@${targetHost}")} true",
        )
        if (sourceTerminal != null) {
            val checkMarker = "__MCP_RELAY_AUTH_CHECK_${System.nanoTime()}__"
            val checkPattern = Regex("${Regex.escape(checkMarker)}:(\\d+)")
            val checkCommand = "ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -p ${target.port} ${shQuote("${targetUser}@${targetHost}")} true >/dev/null 2>&1; rc=${'$'}?; printf '\\n${checkMarker}:%s\\n' ${'$'}rc"
            val output = runCatching { executeAndWaitForPattern(sourceTerminal.id, checkCommand, checkPattern, 30_000L) }.getOrNull()
            val exitCode = output?.let { checkPattern.findAll(it).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() }
            if (exitCode == 0) {
                return RelayAuthSetupDescriptor(true, source.name, target.name, sourceHost, source.port, sourceUser, targetHost, target.port, targetUser, sourceTerminal.id, emptyList(), "源服务器已经可以免交互 SSH 登录目标服务器，方案 C 可以直接使用。", emptyList(), false)
            }
            val warning = if (exitCode != null) "当前 BatchMode SSH 检查退出码为 ${exitCode}；需要执行一次性公钥安装。" else "源终端未返回可解析的 BatchMode 检查结果。"
            if (settings.isAutopilotEnabled() || settings.snapshot().allowAutomaticRelayAuthSetup) {
                return configureRelayAuthenticationInternally(
                    project = project,
                    source = source,
                    target = target,
                    targetConfig = targetConfig,
                    sourceTerminal = sourceTerminal,
                    sourceHost = sourceHost,
                    sourceUser = sourceUser,
                    targetHost = targetHost,
                    targetUser = targetUser,
                    warning = warning,
                )
            }
            return relayAuthPlan(source, target, project, sourceHost, sourceUser, targetHost, targetUser, sourceTerminal.id, commands, listOf(warning))
        }
        return relayAuthPlan(source, target, project, sourceHost, sourceUser, targetHost, targetUser, null, commands, listOf("没有找到与源 Deployment server 唯一匹配的可写 PyCharm SSH 终端；请先调用 ensure_saved_ssh_terminal 准备源服务器 ${source.id}，ready=true 后再调用本工具。未执行免密配置。"))
    }

    private fun configureRelayAuthenticationInternally(
        project: Project,
        source: WebDeploymentTargetDescriptor,
        target: WebDeploymentTargetDescriptor,
        targetConfig: WebServerConfig,
        sourceTerminal: TerminalDescriptor,
        sourceHost: String,
        sourceUser: String,
        targetHost: String,
        targetUser: String,
        warning: String,
    ): RelayAuthSetupDescriptor {
        val approved = invokeOnEdt {
            audit.recordAutomation(
                "web-deployment-server-relay-auth-consent-requested",
                "${source.name} -> ${target.name}",
                "project=${project.name} passwordRead=false",
            )
            Messages.showYesNoDialog(
                "插件需要读取 PyCharm 已保存的目标服务器密码，仅用于本次配置服务器之间的免密 SSH。\n\n" +
                    "密码只在插件内存中短暂使用，不会传递给 AI、终端、返回值或审计日志。\n\n" +
                    "目标服务器：${target.name}\n" +
                    "源服务器：${source.name}\n\n" +
                    "是否允许本次读取并配置？",
                "终端 MCP 桥接：授权免密配置",
                "允许一次",
                "拒绝",
                Messages.getWarningIcon(),
            ) == Messages.YES
        }
        if (!approved) {
            audit.recordAutomation(
                "web-deployment-server-relay-auth-consent-denied",
                "${source.name} -> ${target.name}",
                "project=${project.name} passwordRead=false",
            )
            return RelayAuthSetupDescriptor(
                ready = false,
                sourceServer = source.name,
                targetServer = target.name,
                sourceHost = sourceHost,
                sourcePort = source.port,
                sourceUser = sourceUser,
                targetHost = targetHost,
                targetPort = target.port,
                targetUser = targetUser,
                sourceTerminalId = sourceTerminal.id,
                commands = emptyList(),
                message = "用户拒绝读取目标服务器密码；未执行免密配置。",
                warnings = listOf(warning, "密码未读取。"),
                requiresOneTimePassword = false,
            )
        }

        val savedPassword = runCatching { readSavedServerPassword(project, targetConfig) }.getOrNull()
        if (savedPassword == null || savedPassword.isEmpty()) {
            audit.recordAutomation(
                "web-deployment-server-relay-auth-failed",
                "${source.name} -> ${target.name}",
                "project=${project.name} reason=saved-password-unavailable passwordRead=true",
            )
            return relayAuthFailure(
                source,
                target,
                sourceTerminal,
                warning,
                "PyCharm 未找到目标服务器的已保存密码；未向 AI 或终端索取密码，也未执行免密配置。",
            )
        }
        val password = savedPassword

        try {
            val keyPath = "\$HOME/.ssh/id_ed25519_mcp_relay"
            val publicKeyStartMarker = "__MCP_RELAY_PUBLIC_KEY_START_${System.nanoTime()}__"
            val publicKeyEndMarker = "__MCP_RELAY_PUBLIC_KEY_END_${System.nanoTime()}__"
            val publicKeyCommand = """
                umask 077
                mkdir -p ${'$'}HOME/.ssh
                relay_key="$keyPath"
                if [ ! -s "${'$'}relay_key" ]; then
                  ssh-keygen -q -t ed25519 -f "${'$'}relay_key" -N '' || exit 21
                else
                  key_type=${'$'}(ssh-keygen -y -f "${'$'}relay_key" 2>/dev/null | awk '{print ${'$'}1}')
                  if [ "${'$'}key_type" != "ssh-ed25519" ]; then
                    mv "${'$'}relay_key" "${'$'}relay_key.legacy-${'$'}(date +%s)" || exit 22
                    rm -f "${'$'}{relay_key}.pub"
                    ssh-keygen -q -t ed25519 -f "${'$'}relay_key" -N '' || exit 23
                  fi
                fi
                if [ ! -s "${'$'}{relay_key}.pub" ] || ! grep -q '^ssh-ed25519 ' "${'$'}{relay_key}.pub" 2>/dev/null; then
                  ssh-keygen -y -f "${'$'}relay_key" > "${'$'}{relay_key}.pub" || exit 24
                fi
                if ! grep -q '^ssh-ed25519 ' "${'$'}{relay_key}.pub" 2>/dev/null; then
                  exit 25
                fi
                printf '%s\\n' ${shQuote(publicKeyStartMarker)}
                cat "${'$'}{relay_key}.pub"
                printf '\\n%s\\n' ${shQuote(publicKeyEndMarker)}
            """.trimIndent()
            val publicKeyOutput = runCatching {
                executeAndWaitForMarkers(sourceTerminal.id, publicKeyCommand, publicKeyStartMarker, publicKeyEndMarker)
            }.getOrElse { ex ->
                audit.recordAutomation(
                    "web-deployment-server-relay-auth-failed",
                    "${source.name} -> ${target.name}",
                    "project=${project.name} reason=source-key-generation-failed passwordRead=true",
                )
                return relayAuthFailure(source, target, sourceTerminal, warning, "源服务器公钥生成失败：${ex.message ?: "未知错误"}。密码未输出。")
            }
            val publicKey = publicKeyOutput.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("ssh-ed25519 ") && it.split(Regex("\\s+")).size >= 2 }
            if (publicKey.isNullOrBlank()) {
                audit.recordAutomation(
                    "web-deployment-server-relay-auth-failed",
                    "${source.name} -> ${target.name}",
                    "project=${project.name} reason=source-public-key-missing passwordRead=true",
                )
                return relayAuthFailure(source, target, sourceTerminal, warning, "源服务器没有返回有效的 ed25519 公钥；未执行目标端修改。")
            }

            try {
                installPublicKeyWithSavedPassword(targetHost, target.port, targetUser, publicKey, password)
            } catch (ex: Exception) {
                audit.recordAutomation(
                    "web-deployment-server-relay-auth-failed",
                    "${source.name} -> ${target.name}",
                    "project=${project.name} reason=target-key-installation-failed passwordRead=true",
                )
                return relayAuthFailure(source, target, sourceTerminal, warning, "目标服务器公钥安装失败：${ex.message ?: "未知错误"}。未输出密码。")
            }

            val verifyMarker = "__MCP_RELAY_AUTH_RESULT_${System.nanoTime()}__"
            val verifyPattern = Regex("${Regex.escape(verifyMarker)}:(\\d+)")
            val verifyCommand = "ssh -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -i \"\$HOME/.ssh/id_ed25519_mcp_relay\" -p ${target.port} ${shQuote("${targetUser}@${targetHost}")} true >/dev/null 2>&1; rc=\$?; printf '\\n${verifyMarker}:%s\\n' \$rc"
            val verifyOutput = runCatching {
                executeAndWaitForPattern(sourceTerminal.id, verifyCommand, verifyPattern, 30_000L)
            }.getOrNull()
            val exitCode = verifyOutput?.let { verifyPattern.findAll(it).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() }
            if (exitCode != 0) {
                audit.recordAutomation(
                    "web-deployment-server-relay-auth-failed",
                    "${source.name} -> ${target.name}",
                    "project=${project.name} reason=batchmode-verification-failed exit_code=${exitCode ?: "unknown"} passwordRead=true",
                )
                return relayAuthFailure(source, target, sourceTerminal, warning, "公钥已尝试安装，但源服务器的 BatchMode SSH 验证未通过；未自动回退或伪造成功。")
            }
            audit.recordAutomation(
                "web-deployment-server-relay-auth-completed",
                "${source.name} -> ${target.name}",
                "project=${project.name} passwordRead=true",
            )
            return RelayAuthSetupDescriptor(
                ready = true,
                sourceServer = source.name,
                targetServer = target.name,
                sourceHost = sourceHost,
                sourcePort = source.port,
                sourceUser = sourceUser,
                targetHost = targetHost,
                targetPort = target.port,
                targetUser = targetUser,
                sourceTerminalId = sourceTerminal.id,
                commands = emptyList(),
                message = "已在插件内部使用 PyCharm 保存的凭据完成一次性免密配置；密码未传递给 AI、终端或日志。方案 C 现在可以使用。",
                warnings = emptyList(),
                requiresOneTimePassword = false,
            )
        } finally {
            password.fill('\u0000')
        }
    }

    private fun relayAuthFailure(
        source: WebDeploymentTargetDescriptor,
        target: WebDeploymentTargetDescriptor,
        sourceTerminal: TerminalDescriptor,
        warning: String,
        message: String,
    ) = RelayAuthSetupDescriptor(
        ready = false,
        sourceServer = source.name,
        targetServer = target.name,
        sourceHost = source.host.orEmpty(),
        sourcePort = source.port,
        sourceUser = source.name.substringBefore('@', "").trim(),
        targetHost = target.host.orEmpty(),
        targetPort = target.port,
        targetUser = target.name.substringBefore('@', "").trim(),
        sourceTerminalId = sourceTerminal.id,
        commands = emptyList(),
        message = message,
        warnings = listOf(warning),
        requiresOneTimePassword = false,
    )

    private fun readSavedServerPassword(project: Project, server: WebServerConfig): CharArray? = invokeOnEdt {
        val sshUiData = server.getOrCreateSshUiData(project)
        val deployable = ServerPasswordSafeDeployable(server, sshUiData)
        deployable.refreshCredentials()
        deployable.credentials?.password?.toCharArray()
    }

    private fun installPublicKeyWithSavedPassword(
        host: String,
        port: Int,
        user: String,
        publicKey: String,
        password: CharArray,
    ) {
        val client = SSHClient()
        try {
            client.loadKnownHosts()
            client.connect(host, port)
            client.authPassword(user, password.concatToString())
            val remoteKey = shQuote(publicKey)
            val commandText = "umask 077; mkdir -p \$HOME/.ssh; touch \$HOME/.ssh/authorized_keys; chmod 700 \$HOME/.ssh; chmod 600 \$HOME/.ssh/authorized_keys; grep -Fqx -- $remoteKey \$HOME/.ssh/authorized_keys 2>/dev/null || printf '%s\\n' $remoteKey >> \$HOME/.ssh/authorized_keys"
            client.startSession().use { session ->
                session.exec(commandText).use { command ->
                    command.join(30, TimeUnit.SECONDS)
                    val exitCode = command.exitStatus ?: -1
                    if (exitCode != 0) throw IllegalStateException("目标服务器返回远程命令退出码 $exitCode")
                }
            }
        } finally {
            runCatching { client.disconnect() }
            runCatching { client.close() }
        }
    }

    private fun relayAuthPlan(
        source: WebDeploymentTargetDescriptor,
        target: WebDeploymentTargetDescriptor,
        project: Project,
        sourceHost: String,
        sourceUser: String,
        targetHost: String,
        targetUser: String,
        sourceTerminalId: String?,
        commands: List<String>,
        initialWarnings: List<String>,
    ): RelayAuthSetupDescriptor {
        val warnings = initialWarnings.toMutableList()
        if (!settings.isAutopilotEnabled() && !settings.snapshot().allowAutomaticRelayAuthSetup) warnings += "自动免密配置开关当前关闭；请在设置中开启后重新调用。插件不会读取密码，也不会要求 AI 或用户在终端中手动输入密码。"
        audit.recordAutomation("web-deployment-server-relay-auth-plan", "${source.name} -> ${target.name}", "project=${project.name} source=${sourceHost}:${source.port} target=${targetHost}:${target.port} passwordRead=false")
        return RelayAuthSetupDescriptor(false, source.name, target.name, sourceHost, source.port, sourceUser, targetHost, target.port, targetUser, sourceTerminalId, emptyList(), "未执行免密配置。启用“允许 AI 使用 PyCharm 已保存凭据自动配置服务器免密”后，插件会先弹窗请求一次授权，再在插件内部读取已保存密码并完成配置；密码不会经过 AI 或终端。", warnings, false)
    }

    private fun serverRelayTransfer(
        project: Project,
        source: WebDeploymentTargetDescriptor,
        target: WebDeploymentTargetDescriptor,
        sourceRemote: String,
        targetRemote: String,
        local: Path,
        deleteMissing: Boolean,
        sourcePathWasExplicit: Boolean,
    ): TransferOperationResult {
        if (!settings.isAutopilotEnabled() && !settings.snapshot().allowServerRelayTransfer) {
            throw RemoteTransferExpectedFailureException(
                "服务器直连中转已关闭。请在“终端 MCP 桥接”设置中开启“允许服务器之间直接中转大文件”，或启用全自动模式。",
            )
        }
        val sourceTerminal = findTerminalForDeploymentTarget(source, requireWrite = true)
        val sourceHost = source.host?.trim().orEmpty()
        val targetHost = target.host?.trim().orEmpty()
        if (sourceHost.isBlank() || targetHost.isBlank()) {
            throw RemoteTransferExpectedFailureException("方案 C 需要源服务器和目标服务器都暴露 host/port。")
        }
        val targetUser = target.name.substringBefore('@', "").trim()
        if (targetUser.isBlank() || targetUser == target.name) {
            throw RemoteTransferExpectedFailureException(
                "无法从目标 Deployment server '${target.name}' 推断 SSH 用户名；请在服务器名称中提供 user@host，或使用 PyCharm SSH/Eel endpoint。",
            )
        }
        // An explicit remote source path is authoritative. A local placeholder
        // can be absent or have a different type and must not turn a remote
        // regular file into a directory transfer.
        val normalizedSourceRemote = sourceRemote.trim()
        val sourceIsDirectory = if (sourcePathWasExplicit) {
            normalizedSourceRemote.endsWith("/")
        } else {
            normalizedSourceRemote.endsWith("/") || Files.isDirectory(local)
        }
        val rsyncSource = if (sourceIsDirectory) {
            normalizedSourceRemote.trimEnd('/') + "/"
        } else {
            normalizedSourceRemote.trimEnd('/')
        }
        val sshOptions = "-o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -i \\\"${'$'}relay_key\\\" -p ${target.port}"
        val scpOptions = "-o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -i \"${'$'}relay_key\" -P ${target.port}"
        val targetSpec = "$targetUser@$targetHost:$targetRemote"
        val rsyncDelete = if (deleteMissing && sourceIsDirectory) "--delete" else ""
        val rsyncCommand = "rsync -a --partial --info=progress2 $rsyncDelete -e \"ssh $sshOptions\" " +
            "${shQuote(rsyncSource)} ${shQuote(targetSpec)}"
        val scpRecursive = if (sourceIsDirectory) "-r" else ""
        val scpCommand = "scp -p $scpRecursive $scpOptions ${shQuote(sourceRemote)} ${shQuote(targetSpec)}"
        val resultMarker = "__MCP_SERVER_RELAY_RESULT_${UUID.randomUUID().toString().replace("-", "")}__"
        val command = """
            relay_key="${'$'}HOME/.ssh/id_ed25519_mcp_relay"
            if [ ! -r "${'$'}relay_key" ]; then
              rc=254
              backend=missing-relay-key
            elif command -v rsync >/dev/null 2>&1; then
              $rsyncCommand
              rc=${'$'}?
              backend=rsync
            else
              $scpCommand
              rc=${'$'}?
              backend=scp
            fi
            printf '\\n${resultMarker}:%s:%s\\n' "${'$'}rc" "${'$'}backend"
        """.trimIndent()
        invokeOnEdt {
            applyDeploymentConfirmationPreference(project)
            confirmDeploymentActionIfNeeded(project, "服务器直连中转 ${source.name} -> ${target.name}", emptyList())
        }
        val output = executeAndWaitForRelayResult(sourceTerminal.id, command, resultMarker)
        val result = Regex("${Regex.escape(resultMarker)}:(\\d+):([A-Za-z0-9_-]+)").find(output)
        if (result == null) {
            audit.recordAutomation(
                "web-deployment-server-relay-failed",
                "${source.name} -> ${target.name}",
                "project=${project.name} source=$sourceRemote target=$targetRemote reason=missing-exit-marker localStaging=false",
            )
            throw RemoteTransferExpectedFailureException("方案 C 未返回可验证的 rsync/scp 退出状态。")
        }
        val exitCode = result.groupValues[1].toInt()
        val backend = result.groupValues[2]
        if (exitCode != 0) {
            audit.recordAutomation(
                "web-deployment-server-relay-failed",
                "${source.name} -> ${target.name}",
                "project=${project.name} source=$sourceRemote target=$targetRemote backend=$backend exit_code=$exitCode localStaging=false",
            )
            throw RemoteTransferExpectedFailureException(
                "方案 C 服务器直连中转失败（backend=$backend，exit_code=$exitCode）。源服务器必须能以 BatchMode 免交互 SSH 登录目标服务器；未自动回退到本地 staging。",
            )
        }
        audit.recordAutomation(
            "web-deployment-server-relay",
            "${source.name} -> ${target.name}",
            "project=${project.name} source=$sourceRemote target=$targetRemote backend=$backend localStaging=false deleteMissing=$deleteMissing",
        )
        return TransferOperationResult(
            endpointId = target.id,
            endpointName = "${source.name} -> ${target.name}",
            mode = "server-relay",
            localPath = local.toString(),
            remotePath = targetRemote,
            message = "已通过方案 C 从源服务器直接传输到目标服务器（backend=$backend），未在本机保存完整副本。",
            success = true,
            exitCode = exitCode,
            backend = backend,
        )
    }

    private fun executeAndWaitForPattern(selector: String, command: String, pattern: Regex, timeoutMs: Long): String {
        val operations = TerminalOperations(settings)
        operations.sendDeploymentAutomationText(selector, command, pressEnter = true)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val output = runCatching { operations.readTerminal(selector, 5000) }.getOrNull().orEmpty()
            if (pattern.containsMatchIn(output)) return output
            Thread.sleep(TERMINAL_POLL_MS)
        }
        throw RemoteTransferExpectedFailureException("等待源服务器 SSH 连通性检查超时。")
    }

    private fun executeAndWaitForRelayResult(selector: String, command: String, resultMarker: String): String {
        val resultPattern = Regex("${Regex.escape(resultMarker)}:(\\d+):([A-Za-z0-9_-]+)")
        val operations = TerminalOperations(settings)
        operations.sendDeploymentAutomationText(selector, command, pressEnter = true)
        val deadline = System.nanoTime() + SERVER_RELAY_WAIT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val output = runCatching { operations.readTerminal(selector, 5000) }.getOrNull().orEmpty()
            // The terminal echoes commands before executing them. Require the full
            // result tuple so an echoed marker cannot finish the wait prematurely.
            if (resultPattern.containsMatchIn(output)) return output
            Thread.sleep(TERMINAL_POLL_MS)
        }
        throw RemoteTransferExpectedFailureException("方案 C 等待源服务器 rsync/scp 完成超过 ${TRANSFER_WAIT_MINUTES} 分钟；未将超时当作成功。")
    }
    fun transferDirectory(

        endpointSelector: String,
        localPath: String,
        remotePath: String,
        mode: String,
        deleteMissing: Boolean,
        allowFullProject: Boolean = false,
    ): TransferOperationResult {
        policy.requireRemoteFileTransferAutomation()
        if (endpointSelector.isBlank()) throw RemoteTransferExpectedFailureException("endpoint_selector 不能为空；必须使用 list_remote_servers 返回的真实 sshEndpoints，不能使用 webdeploy ID。")
        if (mode.lowercase() !in setOf("upload", "download", "sync")) throw RemoteTransferExpectedFailureException("mode 必须为 upload、download 或 sync；未开始传输。")
        if (localPath.isBlank() || remotePath.isBlank()) throw RemoteTransferExpectedFailureException("local_path 和 remote_path 必须明确指定，不能传空值。")
        if (mode.lowercase() in setOf("upload", "sync")) {
            val explicitPath = Paths.get(localPath).toAbsolutePath().normalize().toString()
            projects.allProjects().forEach { project -> resolveUploadScope(project, explicitPath, allowFullProject) }
        }
        val endpoint = findSshEndpoint(endpointSelector)
        val local = Paths.get(localPath).toAbsolutePath().normalize()
        if (mode.equals("upload", ignoreCase = true) || mode.equals("sync", ignoreCase = true)) {
            if (!Files.exists(local)) throw RemoteTransferExpectedFailureException("Local path does not exist: $local")
        }
        val remoteNioPath = EelPathUtils.getNioPath(remotePath, endpoint.descriptor)

        when (mode.lowercase()) {
            "upload" -> {
                EelPathUtils.transferLocalContentToRemote(
                    local,
                    EelPathUtils.TransferTarget.Explicit(remoteNioPath),
                )
            }
            "download" -> {
                Files.createDirectories(local.parent ?: local)
                val api = endpoint.descriptor.toEelApiBlocking()
                EelPathUtils.transferContentsIfNonLocal(api, remoteNioPath, local)
            }
            "sync" -> {
                val localEelPath = EelPath.parse(local.toString(), LocalEelDescriptor)
                val remoteEelPath = EelPath.parse(remotePath, endpoint.descriptor)
                runBlocking {
                    EelPathTransfer.directoryOnlySync(localEelPath, remoteEelPath, deleteMissing) { _: Path -> false }
                }
            }
            else -> throw RemoteTransferExpectedFailureException("Transfer mode must be upload, download, or sync.")
        }

        audit.recordAutomation(
            "remote-file-$mode",
            endpoint.info.id,
            "local=$local remote=$remotePath deleteMissing=$deleteMissing",
        )
        return TransferOperationResult(
            endpointId = endpoint.info.id,
            endpointName = endpoint.info.name,
            mode = mode.lowercase(),
            localPath = local.toString(),
            remotePath = remotePath,
            message = "PyCharm remote file operation completed.",
        )
    }

    private fun findSshEndpoint(selector: String): SshEndpointHandle {
        return findSshEndpointOrNull(selector)
            ?: throw RemoteTransferExpectedFailureException("SSH endpoint selector must match exactly one known PyCharm SSH/Eel endpoint. Call list_remote_servers and use sshEndpoints; webDeploymentTargets and visible SSH terminals are not Eel endpoints. No fallback or transfer was performed.")
    }

    private fun findSshEndpointOrNull(selector: String): SshEndpointHandle? {
        val requested = selector.trim()
        if (requested.isEmpty()) return null
        val matches = runCatching<List<SshEndpointHandle>> {
            IjentSshManager.Default.getKnownDescriptors()
                .filterIsInstance<SshEelDescriptor>()
                .map { descriptor ->
                    SshEndpointHandle(
                        descriptor = descriptor,
                        info = SshEndpointDescriptor(
                            id = stableSshId(descriptor),
                            name = descriptor.name,
                            user = descriptor.uhp.user,
                            host = descriptor.uhp.host,
                            port = descriptor.uhp.port,
                            rootPath = descriptor.rootPath.toString(),
                            osFamily = descriptor.osFamily.toString(),
                        ),
                    )
                }
        }.getOrDefault(emptyList())
            .filter { endpoint ->
                SshEndpointSelection.matches(requested, endpoint.info)
            }
        return matches.singleOrNull()
    }

    private fun resolveVirtualFiles(project: Project, localPath: String?): List<VirtualFile> {
        val path = resolveLocalPath(project, localPath)
        if (!Files.exists(path)) {
            throw RemoteTransferExpectedFailureException("Local path does not exist: $path")
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: throw RemoteTransferExpectedFailureException("Local path exists but is not visible to PyCharm VFS: $path")
        return listOf(file)
    }

    private fun deploymentDataContext(project: Project, files: List<VirtualFile>): DataContext = DataContext { dataId ->
        when (dataId) {
            CommonDataKeys.PROJECT.name -> project
            CommonDataKeys.VIRTUAL_FILE.name -> files.firstOrNull()
            CommonDataKeys.VIRTUAL_FILE_ARRAY.name -> files.toTypedArray()
            else -> null
        }
    }

    private fun resolveLocalPath(project: Project, localPath: String?): Path {
        val raw = localPath?.trim().takeUnless { it.isNullOrEmpty() }
            ?: project.basePath
            ?: error("Project has no base path and no local path was provided.")
        return Paths.get(raw).toAbsolutePath().normalize()
    }

    private fun resolveUploadScope(project: Project, localPath: String?, allowFullProject: Boolean): Path = try {
        UploadScope.resolve(Paths.get(project.basePath ?: error("项目没有本地根目录。")), localPath, allowFullProject)
    } catch (ex: RemoteTransferExpectedFailureException) {
        throw ex
    } catch (ex: Exception) {
        throw RemoteTransferExpectedFailureException("上传路径预检失败，未开始传输：" + ex.message)
    }

    fun uploadEntireProject(projectSelector: String?, allowFullProject: Boolean): String {
        if (!allowFullProject) throw RemoteTransferExpectedFailureException("全项目上传必须显式 allow_full_project=true；默认拒绝。")
        val project = projects.resolve(projectSelector)
        return uploadToSelectedDeploymentServer(projectSelector, project.basePath, allowFullProject = true)
    }

    private fun defaultWebDeploymentTarget(project: Project): WebDeploymentTargetDescriptor? = invokeOnEdt {
        val publishConfig = PublishConfig.getInstance(project)
        val servers = GroupedServersConfigManager.getInstance(project).flattenedServers
        val sessionId = DeploymentSessionSelection.get(project)
        val defaultName = selectedWebDeploymentServerName(project, publishConfig)
            ?: servers.firstOrNull { hasUsableDeploymentMapping(project, publishConfig, it.getName().orEmpty()) }?.getName()
            ?: return@invokeOnEdt null
        val server = sessionId?.let { selectedId ->
            servers.firstOrNull { stableWebDeploymentId(project, it) == selectedId }
        } ?: GroupedServersConfigManager.getInstance(project).findServer(defaultName, true)
            ?: GroupedServersConfigManager.getInstance(project).findServer(defaultName)
            ?: servers.firstOrNull { it.getName() == defaultName }
            ?: return@invokeOnEdt null
        webDeploymentDescriptor(project, publishConfig, server, selected = true)
    }

    private fun resolveWebDeploymentTarget(project: Project, selector: String): WebDeploymentTargetDescriptor? = invokeOnEdt {
        val publishConfig = PublishConfig.getInstance(project)
        val servers = GroupedServersConfigManager.getInstance(project).flattenedServers
        val descriptors = servers.map { webDeploymentDescriptor(project, publishConfig, it, selected = false) }
        val requested = selector.trim()
        val matches = descriptors.filter { target ->
            matches(
                requested,
                target.id,
                target.name,
                target.host,
                target.port.toString(),
                target.rootFolder,
                target.projectName,
                target.projectPath,
            )
        }
        when (matches.size) {
            1 -> matches.single()
            else -> null
        }
    }

    private fun resolveWebDeploymentServer(project: Project, selector: String): WebServerConfig? = invokeOnEdt {
        val publishConfig = PublishConfig.getInstance(project)
        val servers = GroupedServersConfigManager.getInstance(project).flattenedServers
        val matches = servers.filter { server ->
            val target = webDeploymentDescriptor(project, publishConfig, server, selected = false)
            matches(
                selector.trim(),
                target.id,
                target.name,
                target.host,
                target.port.toString(),
                target.rootFolder,
                target.projectName,
                target.projectPath,
            )
        }
        matches.singleOrNull()
    }

    private fun resolveTransferEndpoint(
        target: WebDeploymentTargetDescriptor,
        requireWrite: Boolean,
    ): TransferEndpoint {
        val eel = runCatching { findSshEndpointForTarget(target) }.getOrNull()
        if (eel != null) return TransferEndpoint.Eel(eel)
        policy.requireTerminalBackedServerTransfer()
        val terminal = findTerminalForDeploymentTarget(target, requireWrite)
        return TransferEndpoint.Terminal(terminal.id, terminal, target)
    }

    private fun findTerminalForDeploymentTarget(
        target: WebDeploymentTargetDescriptor,
        requireWrite: Boolean,
    ): TerminalDescriptor {
        val host = target.host?.trim().orEmpty()
        if (host.isBlank()) {
            throw RemoteTransferExpectedFailureException("Deployment server '${target.name}' has no host exposed by PyCharm.")
        }
        val endpointToken = "${host.lowercase()}:${target.port}"
        val matches = TerminalOperations(settings).listTerminals().filter { terminal ->
            val text = "${terminal.title} ${terminal.backend}".lowercase()
            endpointToken in text && terminal.ssh && (if (requireWrite) terminal.canWrite else terminal.canRead)
        }
        return when (matches.size) {
            1 -> matches.single()
            0 -> throw RemoteTransferExpectedFailureException(
                "Deployment server '${target.name}' matches ${host}:${target.port}, but no uniquely matching readable/writable PyCharm SSH terminal is visible; refusing fallback. Call ensure_saved_ssh_terminal with server_selector='${target.id}' to reuse/create its saved PyCharm SSH preset before reporting no terminal.",
            )
            else -> throw RemoteTransferExpectedFailureException(
                "Deployment server '${target.name}' matches ${host}:${target.port}, but ${matches.size} matching PyCharm SSH terminals are visible; refusing ambiguous fallback.",
            )
        }
    }

    private fun findSshEndpointForTarget(target: WebDeploymentTargetDescriptor): SshEndpointHandle {
        val host = target.host?.trim().orEmpty()
        val port = target.port
        if (host.isBlank()) {
            throw RemoteTransferExpectedFailureException("Deployment server '${target.name}' has no host exposed by PyCharm.")
        }
        val matches = runCatching<List<SshEndpointHandle>> {
            IjentSshManager.Default.getKnownDescriptors()
                .filterIsInstance<SshEelDescriptor>()
                .map { descriptor ->
                    SshEndpointHandle(
                        descriptor = descriptor,
                        info = SshEndpointDescriptor(
                            id = stableSshId(descriptor),
                            name = descriptor.name,
                            user = descriptor.uhp.user,
                            host = descriptor.uhp.host,
                            port = descriptor.uhp.port,
                            rootPath = descriptor.rootPath.toString(),
                            osFamily = descriptor.osFamily.toString(),
                        ),
                    )
                }
        }.getOrDefault(emptyList()).filter {
            it.info.host.equals(host, ignoreCase = true) && (it.info.port ?: 22) == port
        }
        return matches.singleOrNull()
            ?: throw RemoteTransferExpectedFailureException(
                "Deployment server '${target.name}' matches $host:$port, but PyCharm exposes no unique SSH/Eel endpoint for it; refusing fallback.",
            )
    }

    private fun downloadFromEndpoint(endpoint: TransferEndpoint, remotePath: String, localPath: Path) {
        when (endpoint) {
            is TransferEndpoint.Eel -> {
                Files.createDirectories(localPath.parent ?: localPath)
                val api = endpoint.handle.descriptor.toEelApiBlocking()
                EelPathUtils.transferContentsIfNonLocal(api, EelPathUtils.getNioPath(remotePath, endpoint.handle.descriptor), localPath)
            }
            is TransferEndpoint.Terminal -> downloadFromTerminal(endpoint, remotePath, localPath)
        }
    }

    private fun uploadToEndpoint(project: Project, endpoint: TransferEndpoint, localPath: Path, remotePath: String) {
        when (endpoint) {
            is TransferEndpoint.Eel -> {
                if (!Files.exists(localPath)) error("Staged transfer path does not exist: $localPath")
                EelPathUtils.transferLocalContentToRemote(
                    localPath,
                    EelPathUtils.TransferTarget.Explicit(EelPathUtils.getNioPath(remotePath, endpoint.handle.descriptor)),
                )
            }
            is TransferEndpoint.Terminal -> {
                terminalBackedUpload(
                    project,
                    endpoint.target,
                    localPath,
                    remotePath,
                    "upload",
                    false,
                    endpoint.selector,
                )
            }
        }
    }

    private fun downloadFromTerminal(endpoint: TransferEndpoint.Terminal, remotePath: String, localPath: Path) {
        if (Files.isDirectory(localPath)) {
            throw RemoteTransferExpectedFailureException(
                "Terminal-backed server transfer supports small files only; directory download requires PyCharm SSH/Eel endpoints.",
            )
        }
        Files.createDirectories(localPath.parent ?: localPath)
        val startMarker = "__MCP_TRANSFER_START_${System.nanoTime()}__"
        val endMarker = "__MCP_TRANSFER_END_${System.nanoTime()}__"
        val command = "printf '%s\n' ${shQuote(startMarker)}; if [ -d ${shQuote(remotePath)} ]; then printf '%s' 'MCP_REMOTE_DIRECTORY_UNSUPPORTED'; elif [ -f ${shQuote(remotePath)} ]; then base64 ${shQuote(remotePath)} | tr -d '\n'; else printf '%s' 'MCP_REMOTE_FILE_NOT_FOUND'; fi; printf '\n%s\n' ${shQuote(endMarker)}"
        val output = executeAndWaitForMarkers(endpoint.selector, command, startMarker, endMarker)
        if (output == "MCP_REMOTE_FILE_NOT_FOUND") {
            throw RemoteTransferExpectedFailureException("Remote file does not exist on '${endpoint.target.name}': $remotePath")
        }
        if (output == "MCP_REMOTE_DIRECTORY_UNSUPPORTED") {
            throw RemoteTransferExpectedFailureException("Remote path is a directory on '${endpoint.target.name}'; directory transfer requires PyCharm SSH/Eel APIs: $remotePath")
        }
        val bytes = runCatching { Base64.getDecoder().decode(output.filterNot { it.isWhitespace() }) }
            .getOrElse { throw RemoteTransferExpectedFailureException("Remote terminal returned invalid base64 for $remotePath.") }
        val maxBytes = settings.snapshot().terminalBackedTransferMaxBytes.coerceAtLeast(1L)
        if (bytes.size.toLong() > maxBytes) {
            throw RemoteTransferExpectedFailureException("Terminal-backed transfer received ${bytes.size} bytes, above the configured limit of $maxBytes bytes.")
        }
        Files.write(localPath, bytes)
    }

    private fun executeAndWaitForMarkers(selector: String, command: String, start: String, end: String): String {
        val operations = TerminalOperations(settings)
        operations.sendDeploymentAutomationText(selector, command, pressEnter = true)
        val deadline = System.nanoTime() + TERMINAL_COMMAND_WAIT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val output = runCatching { operations.readTerminal(selector, 5000) }.getOrNull().orEmpty()
            val startIndex = output.indexOf(start)
            val endIndex = output.indexOf(end, startIndex + start.length)
            if (startIndex >= 0 && endIndex >= 0) return output.substring(startIndex + start.length, endIndex).trim()
            Thread.sleep(TERMINAL_POLL_MS)
        }
        throw RemoteTransferExpectedFailureException("Timed out waiting for the strictly matched PyCharm SSH terminal to finish the transfer command.")
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun webDeploymentDescriptor(
        project: Project,
        publishConfig: PublishConfig,
        server: WebServerConfig,
        selected: Boolean,
    ): WebDeploymentTargetDescriptor {
        val serverName = server.getName().orEmpty()
        val transfer = server.fileTransferConfig
        val apiMappings = publishConfig.getPathMappings(serverName).map { mapping ->
            val localPath = expandProjectPath(project, nullableStringGetter(mapping, "getLocalPath"))
            val deployPath = nullableStringGetter(mapping, "getDeployPath").orEmpty()
            val webPath = nullableStringGetter(mapping, "getWebPath")?.takeUnless { it.isBlank() }
            deploymentMappingDescriptor(localPath, deployPath, webPath, fallbackSource = null)
        }
        val xmlMappings = webDeploymentMappingsFromXml(project, serverName)
        val mappings = mergeDeploymentMappings(apiMappings, xmlMappings)
        val warnings = buildList {
            if (mappings.isEmpty()) add("No deployment path mappings are configured for this server.")
            if (mappings.none { it.valid }) add("No usable deployment mapping with both local and deploy paths was found.")
        }
        return WebDeploymentTargetDescriptor(
            id = stableWebDeploymentId(project, server),
            name = serverName,
            selected = selected,
            projectName = project.name,
            projectPath = project.basePath,
            accessType = server.accessType.toString(),
            host = transfer.host,
            port = transfer.port,
            rootFolder = transfer.rootFolder,
            mappings = mappings,
            valid = mappings.any { it.valid },
            warnings = warnings,
        )
    }

    private fun deploymentMappingDescriptor(
        localPath: String,
        deployPath: String,
        webPath: String?,
        fallbackSource: String?,
    ): DeploymentPathMappingDescriptor {
        val warnings = buildList {
            if (localPath.isBlank()) add("Mapping local path is empty.")
            if (deployPath.isBlank()) add("Mapping deploy path is empty; this mapping cannot be used for upload/sync.")
            if (fallbackSource != null) add("Mapping was read from $fallbackSource because PyCharm's path mapping API did not expose a deploy path.")
        }
        return DeploymentPathMappingDescriptor(
            localPath = localPath,
            deployPath = deployPath,
            webPath = webPath,
            valid = localPath.isNotBlank() && deployPath.isNotBlank(),
            warnings = warnings,
        )
    }

    private fun mergeDeploymentMappings(
        apiMappings: List<DeploymentPathMappingDescriptor>,
        xmlMappings: List<DeploymentPathMappingDescriptor>,
    ): List<DeploymentPathMappingDescriptor> {
        if (apiMappings.isEmpty()) return xmlMappings
        if (xmlMappings.isEmpty()) return apiMappings

        val xmlByLocal = xmlMappings.associateBy { normalizeMappingLocal(it.localPath) }
        val merged = apiMappings.map { apiMapping ->
            if (apiMapping.deployPath.isNotBlank()) return@map apiMapping
            val xmlMapping = xmlByLocal[normalizeMappingLocal(apiMapping.localPath)] ?: return@map apiMapping
            deploymentMappingDescriptor(
                localPath = apiMapping.localPath.ifBlank { xmlMapping.localPath },
                deployPath = xmlMapping.deployPath,
                webPath = apiMapping.webPath ?: xmlMapping.webPath,
                fallbackSource = ".idea/deployment.xml",
            )
        }.toMutableList()

        val knownLocals = merged.map { normalizeMappingLocal(it.localPath) }.toMutableSet()
        xmlMappings.forEach { xmlMapping ->
            if (knownLocals.add(normalizeMappingLocal(xmlMapping.localPath))) {
                merged += xmlMapping
            }
        }
        return merged
    }

    private fun webDeploymentMappingsFromXml(project: Project, serverName: String): List<DeploymentPathMappingDescriptor> {
        val document = deploymentXmlDocument(project) ?: return emptyList()
        val paths = document.getElementsByTagName("paths")
        for (index in 0 until paths.length) {
            val pathsElement = paths.item(index) as? Element ?: continue
            if (pathsElement.getAttribute("name") != serverName) continue
            val mappings = pathsElement.getElementsByTagName("mapping")
            return (0 until mappings.length).mapNotNull { mappingIndex ->
                val mappingElement = mappings.item(mappingIndex) as? Element ?: return@mapNotNull null
                val localPath = expandProjectPath(project, mappingElement.getAttribute("local"))
                val deployPath = mappingElement.getAttribute("deploy").orEmpty()
                val webPath = mappingElement.getAttribute("web").takeUnless { it.isBlank() }
                deploymentMappingDescriptor(localPath, deployPath, webPath, fallbackSource = ".idea/deployment.xml")
            }
        }
        return emptyList()
    }

    private fun WebDeploymentTargetDescriptor.remotePathFor(local: Path): String {
        val localText = local.toString().replace('\\', '/')
        val mapping = mappings
            .filter { it.valid && it.deployPath.isNotBlank() && it.localPath.isNotBlank() }
            .map { it to Paths.get(it.localPath).toAbsolutePath().normalize().toString().replace('\\', '/') }
            .filter { (_, mappingLocal) -> localText == mappingLocal || localText.startsWith(mappingLocal.trimEnd('/') + "/") }
            .maxByOrNull { (_, mappingLocal) -> mappingLocal.length }
            ?: error("No deployment mapping covers local path $local for selected server '$name'.")
        val relative = localText.removePrefix(mapping.second).trimStart('/')
        return listOf(mapping.first.deployPath.trimEnd('/'), relative)
            .filter { it.isNotBlank() }
            .joinToString("/")
    }

    private fun selectedWebDeploymentServerName(project: Project, publishConfig: PublishConfig): String? =
        publishConfig.defaultServerOrGroupName?.trim()?.takeUnless { it.isBlank() }
            ?: selectedWebDeploymentServerNameFromXml(project)

    private fun selectedWebDeploymentServerNameFromXml(project: Project): String? {
        val document = deploymentXmlDocument(project) ?: return null
        return runCatching {
            val components = document.getElementsByTagName("component")
            for (index in 0 until components.length) {
                val component = components.item(index) ?: continue
                val attributes = component.attributes ?: continue
                val name = attributes.getNamedItem("name")?.nodeValue
                if (name == "PublishConfigData") {
                    return@runCatching attributes.getNamedItem("serverName")?.nodeValue
                        ?.trim()
                        ?.takeUnless { it.isBlank() }
                }
            }
            null
        }.getOrNull()
    }

    private fun hasUsableDeploymentMapping(project: Project, publishConfig: PublishConfig, serverName: String): Boolean =
        publishConfig.getPathMappings(serverName).any { mapping ->
            expandProjectPath(project, nullableStringGetter(mapping, "getLocalPath")).isNotBlank() &&
                !nullableStringGetter(mapping, "getDeployPath").isNullOrBlank()
        } || webDeploymentMappingsFromXml(project, serverName).any { it.valid }

    private fun deploymentXmlDocument(project: Project): org.w3c.dom.Document? {
        val basePath = project.basePath ?: return null
        val deploymentXml = Paths.get(basePath, ".idea", "deployment.xml")
        if (!Files.isRegularFile(deploymentXml)) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            factory.isNamespaceAware = false
            factory.newDocumentBuilder().parse(deploymentXml.toFile())
        }.getOrNull()
    }

    private fun normalizeMappingLocal(localPath: String): String =
        runCatching { Paths.get(localPath).toAbsolutePath().normalize().toString() }
            .getOrDefault(localPath)
            .replace('\\', '/')
            .trimEnd('/')
            .lowercase()

    private fun nullableStringGetter(instance: Any, methodName: String): String? =
        runCatching { instance.javaClass.getMethod(methodName).invoke(instance) as? String }.getOrNull()

    private fun applyDeploymentConfirmationPreference(project: Project) {
        val confirm = settings.snapshot().requireConfirmForDeploymentActions && !settings.isAutopilotEnabled()
        val publishConfig = PublishConfig.getInstance(project)
        setBooleanIfPresent(publishConfig, "setConfirmBeforeUploading", confirm)
        setBooleanIfPresent(publishConfig, "setConfirmBeforeDeletion", confirm)
    }

    private fun confirmDeploymentActionIfNeeded(project: Project, operation: String, files: List<VirtualFile>) {
        val state = settings.snapshot()
        if (settings.isAutopilotEnabled() || !state.requireConfirmForDeploymentActions) return
        val target = defaultWebDeploymentTarget(project)
        val pathText = files.takeIf { it.isNotEmpty() }
            ?.joinToString("\n", prefix = "\nPaths:\n") { it.path }
            .orEmpty()
        val approved = Messages.showYesNoDialog(
            "AI wants to run PyCharm deployment action:\n\n" +
                "Project: ${project.name}\n" +
                "Server: ${target?.name ?: "selected/default server"}\n" +
                "Action: $operation" + pathText,
            "Terminal MCP Bridge Deployment Confirmation",
            "Allow",
            "Deny",
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!approved) throw RemoteTransferExpectedFailureException("User confirmation denied: PyCharm deployment action $operation")
    }

    private fun setBooleanIfPresent(instance: Any, methodName: String, value: Boolean) {
        runCatching {
            instance.javaClass.getMethod(methodName, java.lang.Boolean.TYPE).invoke(instance, value)
        }
    }

    private fun terminalBackedUpload(
        project: Project,
        target: WebDeploymentTargetDescriptor,
        local: Path,
        remotePath: String,
        mode: String,
        deleteMissing: Boolean,
        terminalSelector: String?,
    ): TransferOperationResult {
        if (!Files.exists(local)) throw RemoteTransferExpectedFailureException("Local path does not exist: $local")
        val selector = terminalSelector?.trim().takeUnless { it.isNullOrEmpty() }
            ?: target.host?.takeUnless { it.isBlank() }
            ?: target.name
        val sourceIsDirectory = Files.isDirectory(local)
        val maxBytes = settings.snapshot().terminalBackedTransferMaxBytes.coerceAtLeast(1L)
        val sourceBytes = localTransferSize(local)
        if (sourceBytes > maxBytes) {
            throw RemoteTransferExpectedFailureException(
                "Terminal-backed transfer would send $sourceBytes bytes, above the configured limit of $maxBytes bytes. Use PyCharm Web Deployment for larger transfers."
            )
        }
        val archive = zipToBase64(local)
        val maxBase64Chars = ((maxBytes + 2L) / 3L * 4L + 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (archive.length > maxBase64Chars) {
            throw RemoteTransferExpectedFailureException(
                "Terminal-backed transfer archive is too large for an interactive terminal. Use PyCharm Web Deployment for larger transfers."
            )
        }
        val tmp = "/tmp/terminal_mcp_${System.currentTimeMillis()}_${local.fileName}.zip.b64"
        val operations = TerminalOperations(settings)
        operations.sendDeploymentAutomationText(selector, "cat > ${shQuote(tmp)} <<'EOF'", pressEnter = true)
        operations.sendDeploymentAutomationText(selector, archive + "\nEOF\n", pressEnter = false)
        operations.sendDeploymentAutomationText(selector, extractZipCommand(tmp, remotePath, sourceIsDirectory, deleteMissing), pressEnter = true)
        waitForTerminalMarker(selector, "TERMINAL_MCP_UPLOAD_DONE")
        audit.recordAutomation(
            "web-deployment-terminal-$mode",
            target.name,
            "project=${project.name} local=$local remote=$remotePath terminalSelector=$selector deleteMissing=$deleteMissing",
        )
        return TransferOperationResult(
            endpointId = target.id,
            endpointName = target.name,
            mode = mode,
            localPath = local.toString(),
            remotePath = remotePath,
            message = "Completed terminal-backed $mode to PyCharm deployment server '${target.name}'.",
        )
    }

    private fun selectedDeploymentDownload(
        project: Project,
        target: WebDeploymentTargetDescriptor,
        local: Path,
        remotePath: String,
        mode: String,
    ): TransferOperationResult {
        val files = invokeOnEdt {
            FileDocumentManager.getInstance().saveAllDocuments()
            resolveVirtualFiles(project, local.toString())
        }
        invokeOnEdt {
            confirmDeploymentActionIfNeeded(project, "Download from Default Server to $local", files)
            applyDeploymentConfirmationPreference(project)
            val ideAction = ActionManager.getInstance().getAction("PublishGroup.Download")
                ?: throw RemoteTransferExpectedFailureException("PyCharm action not found: PublishGroup.Download")
            val event = AnActionEvent.createFromDataContext(
                "Terminal MCP Bridge",
                ideAction.templatePresentation.clone(),
                deploymentDataContext(project, files),
            )
            ideAction.actionPerformed(event)
        }
        audit.recordAutomation(
            "web-deployment-download-default",
            target.name,
            "project=${project.name} local=$local remote=$remotePath",
        )
        return TransferOperationResult(
            endpointId = target.id,
            endpointName = target.name,
            mode = mode,
            localPath = local.toString(),
            remotePath = remotePath,
            message = "Started PyCharm Download from Default Server for ${files.size} path(s) using '${target.name}'.",
        )
    }

    private fun waitForTerminalMarker(selector: String, marker: String) {
        val operations = TerminalOperations(settings)
        val deadline = System.nanoTime() + TERMINAL_COMMAND_WAIT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val output = runCatching { operations.readTerminal(selector, 5000) }.getOrNull().orEmpty()
            if (marker in output) return
            Thread.sleep(TERMINAL_POLL_MS)
        }
        throw RemoteTransferExpectedFailureException("Timed out waiting for the PyCharm SSH terminal to finish the upload command.")
    }

    private fun localTransferSize(local: Path): Long {
        if (Files.isRegularFile(local)) return Files.size(local)
        return Files.walk(local).use { stream ->
            stream.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(Long.MAX_VALUE) }.sum()
        }
    }

    private fun zipToBase64(local: Path): String {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            if (Files.isDirectory(local)) {
                Files.walk(local).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        val entryName = local.relativize(file).toString().replace('\\', '/')
                        zip.putNextEntry(ZipEntry(entryName))
                        Files.copy(file, zip)
                        zip.closeEntry()
                    }
                }
            }
            else {
                zip.putNextEntry(ZipEntry(local.fileName.toString()))
                Files.copy(local, zip)
                zip.closeEntry()
            }
        }
        return Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(bytes.toByteArray())
    }

    private fun extractZipCommand(tmp: String, remotePath: String, sourceIsDirectory: Boolean, deleteMissing: Boolean): String =
        """
        python3 - <<'PY'
        import base64, pathlib, shutil, zipfile
        archive = pathlib.Path(${pythonQuote(tmp)})
        target = pathlib.Path(${pythonQuote(remotePath)})
        source_is_directory = ${if (sourceIsDirectory) "True" else "False"}
        data = base64.b64decode(archive.read_text().encode('ascii'))
        zip_path = archive.with_suffix('.zip')
        zip_path.write_bytes(data)
        if source_is_directory:
            extract_root = target
            if ${if (deleteMissing) "True" else "False"} and target.exists():
                if target.is_dir():
                    shutil.rmtree(target)
                else:
                    target.unlink()
            target.mkdir(parents=True, exist_ok=True)
        else:
            extract_root = target.parent
            extract_root.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(zip_path) as zf:
            names = [name for name in zf.namelist() if name and not name.endswith('/')]
            if source_is_directory:
                zf.extractall(extract_root)
            else:
                if len(names) != 1:
                    raise RuntimeError(f'Expected one file in upload archive, got {len(names)}')
                if ${if (deleteMissing) "True" else "False"} and target.exists():
                    if target.is_dir():
                        shutil.rmtree(target)
                    else:
                        target.unlink()
                extracted = pathlib.Path(zf.extract(names[0], extract_root))
                if extracted != target:
                    if target.exists():
                        if target.is_dir():
                            shutil.rmtree(target)
                        else:
                            target.unlink()
                    shutil.move(str(extracted), str(target))
        archive.unlink(missing_ok=True)
        zip_path.unlink(missing_ok=True)
        print('TERMINAL_MCP_UPLOAD_DONE', target)
        PY
        """.trimIndent()

    private fun expandProjectPath(project: Project, path: String?): String {
        val value = path.orEmpty()
        return value.replace("$" + "PROJECT_DIR$", project.basePath.orEmpty())
    }

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun pythonQuote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun stableSshId(descriptor: SshEelDescriptor): String {
        val raw = listOf(descriptor.name, descriptor.uhp.user, descriptor.uhp.host, descriptor.uhp.port)
            .joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "ssh-" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun stableDeploymentId(project: Project, configuration: RunConfiguration): String {
        val raw = listOf(project.locationHash, project.basePath, configuration.name, configuration.type.id)
            .joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "deploy-" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun stableWebDeploymentId(project: Project, server: WebServerConfig): String {
        val raw = listOf(project.locationHash, project.basePath, server.id, server.getName())
            .joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "webdeploy-" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun matches(selector: String, vararg values: String?): Boolean {
        val requested = selector.trim()
        val haystack = values.filterNotNull().joinToString(" ").lowercase()
        return values.any { it?.equals(requested, ignoreCase = true) == true } ||
            haystack.contains(requested.lowercase())
    }

    private fun <T> invokeOnEdt(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) return block()
        var value: T? = null
        var failure: Throwable? = null
        app.invokeAndWait {
            runCatching { block() }
                .onSuccess { value = it }
                .onFailure { failure = it }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}

data class RemoteServerDescriptor(
    val id: String,
    val name: String,
    val typeId: String,
    val typeName: String,
    val configurationClass: String,
    val uniqueId: String,
)

data class SshEndpointHandle(
    val descriptor: SshEelDescriptor,
    val info: SshEndpointDescriptor,
)

data class SshEndpointDescriptor(
    val id: String,
    val name: String,
    val user: String?,
    val host: String,
    val port: Int?,
    val rootPath: String,
    val osFamily: String,
)

data class DeploymentConfigurationDescriptor(
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

data class DeploymentPathMappingDescriptor(
    val localPath: String,
    val deployPath: String,
    val webPath: String?,
    val valid: Boolean,
    val warnings: List<String>,
)

data class WebDeploymentTargetDescriptor(
    val id: String,
    val name: String,
    val selected: Boolean,
    val projectName: String,
    val projectPath: String?,
    val accessType: String,
    val host: String?,
    val port: Int,
    val rootFolder: String?,
    val mappings: List<DeploymentPathMappingDescriptor>,
    val valid: Boolean,
    val warnings: List<String>,
)

data class TransferOperationResult(
    val endpointId: String,
    val endpointName: String,
    val mode: String,
    val localPath: String,
    val remotePath: String,
    val message: String,
    val success: Boolean = true,
    val exitCode: Int? = null,
    val backend: String? = null,
)
