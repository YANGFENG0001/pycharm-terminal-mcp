package com.mcob.terminalmcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.ui.OpenPredefinedTerminalActionProvider
import java.security.MessageDigest

class TerminalCreationOperations(
    private val settings: TerminalMcpSettings,
    private val registry: TerminalRegistry = TerminalRegistry(settings),
) {
    private val policy = AutomationPolicy(settings)
    private val projects = ProjectLocator()
    private val audit = AuditLog(settings)

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
