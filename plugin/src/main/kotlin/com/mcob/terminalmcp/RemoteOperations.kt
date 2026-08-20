package com.mcob.terminalmcp

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
import com.jetbrains.plugins.webDeployment.AlwaysAsk
import com.jetbrains.plugins.webDeployment.ExecutionContext
import com.jetbrains.plugins.webDeployment.IgnoreOverwritingStrategy
import com.jetbrains.plugins.webDeployment.ProjectDeploymentRevisionTracker
import com.jetbrains.plugins.webDeployment.TransferTask
import com.jetbrains.plugins.webDeployment.actions.DownloadAction
import com.jetbrains.plugins.webDeployment.actions.PublishActionUtil
import com.jetbrains.plugins.webDeployment.config.Deployable
import com.jetbrains.plugins.webDeployment.config.GroupedServersConfigManager
import com.jetbrains.plugins.webDeployment.config.PublishConfig
import com.jetbrains.plugins.webDeployment.config.WebServerConfig
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

@OptIn(InternalSshEelApi::class)
class RemoteOperations(private val settings: TerminalMcpSettings) {
    private companion object {
        const val MAX_TERMINAL_BACKED_BASE64_CHARS = 1_000_000
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
                servers.map { server ->
                    webDeploymentDescriptor(project, publishConfig, server, server.getName() == defaultName)
                }
            }
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
            confirmDeploymentActionIfNeeded(project, "Run deployment configuration '${configuration.name}'", emptyList())
            applyDeploymentConfirmationPreference(project)
            ProgramRunnerUtil.executeConfiguration(project, settings, DefaultRunExecutor.getRunExecutorInstance())
        }
        audit.recordAutomation(
            "run-deployment-configuration",
            configuration.name,
            "project=${project.name} server=${configuration.serverName}",
        )
        return "Started PyCharm deployment configuration '${configuration.name}' for ${configuration.serverName}."
    }

    fun uploadToSelectedDeploymentServer(projectSelector: String?, localPath: String?): String {
        policy.requireDeploymentAutomation()
        val project = projects.resolve(projectSelector)
        val files = invokeOnEdt {
            FileDocumentManager.getInstance().saveAllDocuments()
            resolveVirtualFiles(project, localPath)
        }
        invokeOnEdt {
            confirmDeploymentActionIfNeeded(project, "Upload to Default Server", files)
            applyDeploymentConfirmationPreference(project)
            files.forEach { PublishActionUtil.uploadToDefaultServer(project, it) }
        }
        val target = defaultWebDeploymentTarget(project)
        audit.recordAutomation(
            "web-deployment-upload",
            target?.name ?: "default-server",
            "project=${project.name} local=${files.joinToString(",") { it.path }} scope=default-server",
        )
        return "Started PyCharm Upload to Default Server for ${files.size} path(s)" +
            (target?.let { " using '${it.name}'." } ?: ".")
    }

    fun invokeSelectedDeploymentAction(projectSelector: String?, localPath: String?, action: String): String {
        policy.requireDeploymentAutomation()
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

    fun selectDeploymentServer(projectSelector: String?, serverSelector: String): WebDeploymentTargetDescriptor {
        policy.requireDeploymentAutomation()
        val project = projects.resolve(projectSelector)
        return invokeOnEdt {
            val publishConfig = PublishConfig.getInstance(project)
            val selected = resolveSavedDeploymentServer(project, serverSelector)
            val selectedName = selected.getName().orEmpty()
            DeploymentSessionSelection.select(project, selectedName)
            audit.recordAutomation("select-deployment-server", selectedName, "project=${project.name} scope=session")
            webDeploymentDescriptor(project, publishConfig, selected, selected = true)
        }
    }

    fun uploadToDeploymentServer(projectSelector: String?, serverSelector: String, localPath: String?): String {
        policy.requireDeploymentAutomation()
        val project = projects.resolve(projectSelector)
        val files = invokeOnEdt {
            FileDocumentManager.getInstance().saveAllDocuments()
            resolveVirtualFiles(project, localPath)
        }
        val server = invokeOnEdt { resolveSavedDeploymentServer(project, serverSelector) }
        invokeOnEdt {
            confirmDeploymentActionIfNeeded(project, "Upload to saved server ${server.getName()}", files)
            applyDeploymentConfirmationPreference(project)
            startUploadToServer(project, server, files)
        }
        DeploymentSessionSelection.select(project, server.getName().orEmpty())
        audit.recordAutomation("web-deployment-upload", server.getName().orEmpty(), "project=${project.name} local=${files.joinToString(",") { it.path }} scope=saved-server")
        return "Started PyCharm upload for ${files.size} path(s) using saved server '${server.getName()}'."
    }

    fun transferBetweenDeploymentServers(projectSelector: String?, sourceServerSelector: String, targetServerSelector: String, localPath: String?): String {
        policy.requireDeploymentAutomation()
        val project = projects.resolve(projectSelector)
        val files = invokeOnEdt {
            FileDocumentManager.getInstance().saveAllDocuments()
            resolveVirtualFiles(project, localPath)
        }
        val source = invokeOnEdt { resolveSavedDeploymentServer(project, sourceServerSelector) }
        val target = invokeOnEdt { resolveSavedDeploymentServer(project, targetServerSelector) }
        invokeOnEdt {
            confirmDeploymentActionIfNeeded(project, "Transfer from ${source.getName()} to ${target.getName()}", files)
            applyDeploymentConfirmationPreference(project)
            DownloadAction.download(
                project,
                Deployable.create(source, project),
                null,
                null,
                files.toTypedArray(),
                Runnable {
                    invokeOnEdt { startUploadToServer(project, target, files) }
                    audit.recordAutomation("web-deployment-server-transfer-upload", target.getName().orEmpty(), "project=${project.name} source=${source.getName()} local=${files.joinToString(",") { it.path }}")
                },
                overwriteStrategy(),
            )
        }
        audit.recordAutomation("web-deployment-server-transfer-download", source.getName().orEmpty(), "project=${project.name} target=${target.getName()} local=${files.joinToString(",") { it.path }}")
        return "Started PyCharm server-to-server transfer through local staging: download from '${source.getName()}', then upload to '${target.getName()}' after download finishes."
    }

    fun transferWithSelectedDeploymentServer(
        projectSelector: String?,
        localPath: String?,
        mode: String,
        deleteMissing: Boolean,
        terminalSelector: String?,
    ): TransferOperationResult {
        policy.requireRemoteFileTransferAutomation()
        val project = projects.resolve(projectSelector)
        val target = defaultWebDeploymentTarget(project)
            ?: throw RemoteTransferExpectedFailureException("The project has no selected/default PyCharm Deployment server.")
        val local = resolveLocalPath(project, localPath)
        val remotePath = target.remotePathFor(local)
        val normalizedMode = mode.lowercase()
        if (normalizedMode !in setOf("upload", "sync", "download", "pull")) {
            throw RemoteTransferExpectedFailureException("Transfer mode must be upload, download, pull, or sync.")
        }
        invokeOnEdt {
            confirmDeploymentActionIfNeeded(project, "Terminal-backed $normalizedMode to $remotePath", emptyList())
        }

        return when (normalizedMode) {
            "upload", "sync" -> terminalBackedUpload(project, target, local, remotePath, normalizedMode, deleteMissing, terminalSelector)
            "download", "pull" -> selectedDeploymentDownload(project, target, local, remotePath, normalizedMode)
            else -> throw RemoteTransferExpectedFailureException("Transfer mode must be upload, download, pull, or sync.")
        }
    }

    fun transferDirectory(
        endpointSelector: String,
        localPath: String,
        remotePath: String,
        mode: String,
        deleteMissing: Boolean,
    ): TransferOperationResult {
        policy.requireRemoteFileTransferAutomation()
        val endpoint = findSshEndpointOrNull(endpointSelector)
            ?: throw RemoteTransferExpectedFailureException("SSH endpoint selector did not match exactly one known PyCharm SSH endpoint: $endpointSelector. No Deployment server fallback was attempted.")
        val local = Paths.get(localPath).toAbsolutePath().normalize()
        if (mode.equals("upload", ignoreCase = true) || mode.equals("sync", ignoreCase = true)) {
            if (!Files.exists(local)) error("Local path does not exist: $local")
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
            else -> error("Transfer mode must be upload, download, or sync.")
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
            ?: error("SSH endpoint selector must match exactly one known PyCharm SSH endpoint.")
    }

    private fun findSshEndpointOrNull(selector: String): SshEndpointHandle? {
        val requested = selector.trim()
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
                val info = endpoint.info
                val haystack = listOf(
                    info.id,
                    info.name,
                    info.user.orEmpty(),
                    info.host,
                    info.port?.toString().orEmpty(),
                ).joinToString(" ").lowercase()
                info.id.equals(requested, ignoreCase = true) ||
                    info.id.startsWith(requested, ignoreCase = true) ||
                    haystack.contains(requested.lowercase())
            }
        return matches.singleOrNull()
    }

    private fun resolveSavedDeploymentServer(project: Project, selector: String): WebServerConfig {
        val matches = GroupedServersConfigManager.getInstance(project).flattenedServers.filter { server ->
            matches(selector, stableWebDeploymentId(project, server), server.id, server.getName(), server.fileTransferConfig.host, server.fileTransferConfig.port.toString())
        }
        if (matches.size != 1) throw RemoteTransferExpectedFailureException("Deployment server selector must match exactly one saved PyCharm server.")
        return matches.single()
    }

    private fun startUploadToServer(project: Project, server: WebServerConfig, files: List<VirtualFile>) {
        val publishConfig = PublishConfig.getInstance(project)
        val deployable = Deployable.create(server, project)
        val title = "Upload to ${server.getName()}"
        object : TransferTask.ListBased(
            project,
            true,
            publishConfig,
            deployable,
            title,
            true,
            true,
            true,
            ProjectDeploymentRevisionTracker.getInstance(project),
        ) {
            override fun buildOperationsList(context: ExecutionContext): TransferTask.ListBased.ResultWithErrors {
                context.setIgnoreOverwritingStrategy(overwriteStrategy())
                return PublishActionUtil.scanFiles(context, files)
            }
        }.queue()
    }

    private fun resolveVirtualFiles(project: Project, localPath: String?): List<VirtualFile> {
        val path = resolveLocalPath(project, localPath)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: throw RemoteTransferExpectedFailureException("Local path is not visible to PyCharm VFS: $path")
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

    private fun defaultWebDeploymentTarget(project: Project): WebDeploymentTargetDescriptor? = invokeOnEdt {
        val publishConfig = PublishConfig.getInstance(project)
        val servers = GroupedServersConfigManager.getInstance(project).flattenedServers
        val defaultName = selectedWebDeploymentServerName(project, publishConfig)
            ?: servers.firstOrNull { hasUsableDeploymentMapping(project, publishConfig, it.getName().orEmpty()) }?.getName()
            ?: return@invokeOnEdt null
        val server = GroupedServersConfigManager.getInstance(project).findServer(defaultName, true)
            ?: GroupedServersConfigManager.getInstance(project).findServer(defaultName)
            ?: servers.firstOrNull { it.getName() == defaultName }
            ?: return@invokeOnEdt null
        webDeploymentDescriptor(project, publishConfig, server, selected = true)
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
        val localText = PathNormalization.key(local.toString())
        val mapping = mappings
            .filter { it.valid && it.deployPath.isNotBlank() && it.localPath.isNotBlank() }
            .map { it to PathNormalization.key(it.localPath) }
            .filter { (_, mappingLocal) -> localText == mappingLocal || PathNormalization.containsPath(mappingLocal, localText) }
            .maxByOrNull { (_, mappingLocal) -> mappingLocal.length }
            ?: error("No deployment mapping covers local path $local for selected server '$name'.")
        val relative = localText.removePrefix(mapping.second).trimStart('/')
        return listOf(mapping.first.deployPath.trimEnd('/'), relative)
            .filter { it.isNotBlank() }
            .joinToString("/")
    }

    private fun selectedWebDeploymentServerName(project: Project, publishConfig: PublishConfig): String? =
        DeploymentSessionSelection.selectedName(project)
            ?: publishConfig.defaultServerOrGroupName?.trim()?.takeUnless { it.isBlank() }
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

    private fun normalizeMappingLocal(localPath: String): String = PathNormalization.key(localPath)

    private fun nullableStringGetter(instance: Any, methodName: String): String? =
        runCatching { instance.javaClass.getMethod(methodName).invoke(instance) as? String }.getOrNull()

    private fun applyDeploymentConfirmationPreference(project: Project) {
        val state = settings.snapshot()
        val confirm = state.requireConfirmForDeploymentActions && !settings.isAutopilotEnabled() && !state.autoConfirmDeploymentActions
        val publishConfig = PublishConfig.getInstance(project)
        setBooleanIfPresent(publishConfig, "setConfirmBeforeUploading", confirm)
        setBooleanIfPresent(publishConfig, "setConfirmBeforeDeletion", confirm)
    }

    private fun confirmDeploymentActionIfNeeded(project: Project, operation: String, files: List<VirtualFile>) {
        val state = settings.snapshot()
        if (settings.isAutopilotEnabled() || state.autoConfirmDeploymentActions || !state.requireConfirmForDeploymentActions) return
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

    private fun overwriteStrategy(): IgnoreOverwritingStrategy =
        if (settings.isAutopilotEnabled() || settings.snapshot().autoConfirmDeploymentActions) {
            object : IgnoreOverwritingStrategy {
                override fun ignoreLocalOverwriting(target: org.apache.commons.vfs2.FileObject): Boolean = true
                override fun ignoreRemoteOverwriting(target: org.apache.commons.vfs2.FileObject): Boolean = true
            }
        } else {
            AlwaysAsk
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
        val archive = zipToBase64(local)
        if (archive.length > MAX_TERMINAL_BACKED_BASE64_CHARS) {
            throw RemoteTransferExpectedFailureException(
                "Terminal-backed transfer would write ${archive.length} base64 characters into an interactive terminal. " +
                    "The safety limit is $MAX_TERMINAL_BACKED_BASE64_CHARS characters. " +
                    "Use upload_to_selected_deployment_server or invoke_selected_deployment_action(action=upload/sync) for large transfers."
            )
        }
        val tmp = "/tmp/terminal_mcp_${System.currentTimeMillis()}_${local.fileName}.zip.b64"
        val operations = TerminalOperations(settings)
        operations.sendText(selector, "cat > ${shQuote(tmp)} <<'EOF'", pressEnter = true)
        operations.sendText(selector, archive + "\nEOF\n", pressEnter = false)
        operations.sendText(selector, extractZipCommand(tmp, remotePath, sourceIsDirectory, deleteMissing), pressEnter = true)
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
            message = "Sent terminal-backed $mode to PyCharm selected deployment server '${target.name}'. Read the terminal for TERMINAL_MCP_UPLOAD_DONE.",
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
)
