package dev.terminalmcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.ui.OpenPredefinedTerminalActionProvider
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

class TerminalCreationOperations(
    private val settings: TerminalMcpSettings,
    private val registry: TerminalRegistry = TerminalRegistry(settings),
) {
    private val policy = AutomationPolicy(settings)
    private val projects = ProjectLocator()
    private val audit = AuditLog(settings)

    private companion object {
        val recoveryLocks = ConcurrentHashMap<String, ReentrantLock>()
        val pendingCreations = ConcurrentHashMap.newKeySet<String>()
    }

    internal fun ensureSavedSshTerminal(serverSelector: String, projectSelector: String?, timeoutMs: Int): Pair<String, SavedSshRecoveryOutcome> {
        require(timeoutMs in 1000..60000) { "timeout_ms 必须在 1000 到 60000 之间。" }
        require(!ApplicationManager.getApplication().isDispatchThread) { "不能在 IDE 界面线程等待 SSH 连接。" }
        val project = projects.resolve(projectSelector)
        val projectPath = project.basePath ?: error("项目没有本地路径。")
        val target = RemoteOperations(settings).savedDeploymentTargetForTerminal(projectPath, serverSelector)
        val identity = SavedSshIdentity.fromSavedServer(target.name, target.host, target.port)
        val key = project.locationHash + "|" + identity.user + "|" + identity.host.lowercase() + ":" + identity.port
        val lock = recoveryLocks.computeIfAbsent(key) { ReentrantLock() }
        if (!lock.tryLock()) return target.id to SavedSshRecoveryOutcome(status = "recovery_in_progress",
            message = "该项目的目标 SSH 终端正在创建/检查中；未重复创建。")
        try {
            val recovery = SavedSshTerminalRecovery(
                listTerminals = { registry.list().filter { it.project == project }.map { it.descriptor } },
                listPresets = { listPredefinedTerminals(projectPath).map { it.descriptor } },
                create = { preset ->
                    policy.requirePredefinedTerminalCreation()
                    pendingCreations.add(key)
                    openPredefinedTerminal(preset.id, projectPath)
                },
                shellIdle = { terminal ->
                    val handle = registry.find(terminal.id)
                    if (handle == null) false else invokeOnEdt {
                        val widget = registry.findWidget(handle.content) ?: return@invokeOnEdt false
                        widget.ttyConnectorAccessor.ttyConnector?.isConnected == true &&
                            SavedSshIdentity.shellPromptVisible(widget.getText().toString())
                    }
                },
                probe = { terminal, remaining ->
                    val marker = "MCP_SSH_READY_" + UUID.randomUUID().toString().replace("-", "")
                    val result = TerminalOperations(settings).executeAndWait(terminal.id, "printf '%s\\n' '$marker'", remaining, "posix")
                    result.completed && result.success && result.exit_code == 0 && result.output.trim() == marker
                },
            )
            val outcome = recovery.ensure(identity, timeoutMs, pendingCreations.contains(key))
            if (outcome.terminal != null) pendingCreations.remove(key)
            audit.recordAutomation("ensure-saved-ssh-terminal", target.id,
                "project=${project.name} status=${outcome.status} created=${outcome.created} reused=${outcome.reused}")
            return target.id to outcome
        } finally {
            lock.unlock()
        }
    }

    fun createLocalTerminal(projectSelector: String?, title: String?, workingDirectory: String?): TerminalDescriptor {
        policy.requireLocalTerminalCreation()
        val project = projects.resolve(projectSelector)
        val resolvedTitle = title?.trim().takeUnless { it.isNullOrEmpty() } ?: "AI Terminal"
        val resolvedDirectory = workingDirectory?.trim().takeUnless { it.isNullOrEmpty() }
            ?: project.basePath
            ?: System.getProperty("user.dir")

        val content = invokeOnEdt {
            val manager = TerminalToolWindowManager.getInstance(project)
            val widget = manager.createLocalShellWidget(resolvedTitle, resolvedDirectory).asNewWidget()
            val content = manager.getContainer(widget)?.content
                ?: error("The created terminal tab is not attached to PyCharm's terminal tool window.")
            TerminalTitleOverrides.set(content, resolvedTitle)
            content
        }

        val descriptor = registry.list()
            .firstOrNull { it.content == content }
            ?.descriptor
            ?: error("The local terminal was created but PyCharm did not expose its terminal tab yet.")
        audit.recordAutomation(
            "create-local-terminal",
            descriptor.id,
            "project=${project.name} title=$resolvedTitle workingDirectory=$resolvedDirectory",
        )
        return descriptor
    }

    fun listPredefinedTerminals(projectSelector: String?): List<TerminalPresetHandle> =
        projects.resolveMany(projectSelector).flatMap { project -> collectForProject(project) }

    fun openPredefinedTerminal(selector: String?, projectSelector: String?): List<TerminalDescriptor> {
        policy.requirePredefinedTerminalCreation()
        val matches = findPredefined(selector, projectSelector)
        if (matches.size != 1) {
            error("Configured terminal selector must match exactly one preset.")
        }
        val match = matches.single()
        val before = registry.list().map { it.descriptor.id }.toSet()

        invokeOnEdt {
            val dataContext = object : DataContext {
                override fun getData(dataId: String): Any? =
                    if (dataId == CommonDataKeys.PROJECT.name) match.project else null
            }
            val event = AnActionEvent.createFromDataContext(
                "Terminal MCP Bridge",
                match.action.templatePresentation,
                dataContext,
            )
            match.action.actionPerformed(event)
        }

        Thread.sleep(250)
        val created = registry.list()
            .map { it.descriptor }
            .filterNot { before.contains(it.id) }
        audit.recordAutomation(
            "create-predefined-terminal",
            match.descriptor.id,
            "project=${match.project.name} title=${match.descriptor.title}",
        )
        return created
    }

    private fun findPredefined(selector: String?, projectSelector: String?): List<TerminalPresetHandle> {
        val requested = selector?.trim().orEmpty()
        if (requested.isEmpty()) error("A configured terminal selector is required.")
        val handles = listPredefinedTerminals(projectSelector)
        return handles.filter { handle ->
            val haystack = listOf(
                handle.descriptor.id,
                handle.descriptor.title,
                handle.descriptor.description.orEmpty(),
                handle.descriptor.projectName,
                handle.descriptor.projectPath.orEmpty(),
            ).joinToString(" ").lowercase()
            handle.descriptor.id.equals(requested, ignoreCase = true) ||
                handle.descriptor.id.startsWith(requested, ignoreCase = true) ||
                haystack.contains(requested.lowercase())
        }
    }

    private fun collectForProject(project: Project): List<TerminalPresetHandle> = invokeOnEdt {
        OpenPredefinedTerminalActionProvider.collectAll(project)
            .mapIndexed { index, action ->
                val title = action.templatePresentation.text?.takeUnless { it.isBlank() }
                    ?: action.javaClass.simpleName
                val description = action.templatePresentation.description
                val id = stableId(project, action, index, title)
                TerminalPresetHandle(
                    descriptor = TerminalPresetDescriptor(
                        id = id,
                        title = title,
                        description = description,
                        projectName = project.name,
                        projectPath = project.basePath,
                        sshLike = "$title $description ${action.javaClass.name}".contains("ssh", ignoreCase = true) ||
                            "$title $description ${action.javaClass.name}".contains("remote", ignoreCase = true) ||
                            title.contains("@"),
                        actionClass = action.javaClass.name,
                    ),
                    project = project,
                    action = action,
                )
            }
    }

    private fun stableId(project: Project, action: AnAction, index: Int, title: String): String {
        val raw = listOf(project.locationHash, project.basePath, action.javaClass.name, index, title)
            .joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "preset-" + digest.take(10).joinToString("") { "%02x".format(it) }
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

data class TerminalPresetHandle(
    val descriptor: TerminalPresetDescriptor,
    val project: Project,
    val action: AnAction,
)

data class TerminalPresetDescriptor(
    val id: String,
    val title: String,
    val description: String?,
    val projectName: String,
    val projectPath: String?,
    val sshLike: Boolean,
    val actionClass: String,
)
