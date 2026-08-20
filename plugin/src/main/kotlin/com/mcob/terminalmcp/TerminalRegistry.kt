package com.mcob.terminalmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.security.MessageDigest

class TerminalRegistry(private val settings: TerminalMcpSettings) {
    fun list(): List<TerminalHandle> {
        val app = ApplicationManager.getApplication()
        val result = mutableListOf<TerminalHandle>()
        app.invokeAndWait {
            ProjectManager.getInstance().openProjects
                .filterNot { it.isDisposed }
                .forEach { project ->
                    result += collectFromProject(project)
                }
        }
        return result
    }

    fun find(terminalId: String): TerminalHandle? {
        val requested = terminalId.trim()
        val handles = list()

        handles.firstOrNull { it.descriptor.id == requested || it.descriptor.logicalId == requested }?.let { return it }
        if (requested.isEmpty()) return null

        if (requested.equals("selected", ignoreCase = true) ||
            requested.equals("active", ignoreCase = true) ||
            requested.equals("current", ignoreCase = true)
        ) {
            handles.firstOrNull { it.descriptor.active || it.descriptor.selected }?.let { return it }
        }

        handles.firstOrNull { it.descriptor.id.startsWith(requested) }?.let { return it }

        val needle = requested.lowercase()
        val matches = handles.filter {
            buildString {
                append(it.descriptor.id)
                append(' ')
                append(it.descriptor.logicalId)
                append(' ')
                append(it.descriptor.title)
                append(' ')
                append(it.descriptor.projectName)
                append(' ')
                append(it.descriptor.projectPath.orEmpty())
                append(' ')
                append(it.descriptor.backend)
            }.lowercase().contains(needle)
        }
        return when (matches.size) {
            1 -> matches.single()
            0 -> null
            else -> throw TerminalNotFoundException("Terminal selector is ambiguous: $requested")
        }
    }

    private fun collectFromProject(project: Project): List<TerminalHandle> {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return emptyList()
        val manager = toolWindow.contentManagerIfCreated ?: return emptyList()
        val activeToolWindow = toolWindow.isActive
        val selected = manager.selectedContent

        return manager.contents.mapNotNull { content ->
            val widget = findWidget(content) ?: return@mapNotNull null
            val title = TerminalTitleOverrides.get(content)
                ?: content.displayName.ifBlank { content.tabName ?: "Terminal" }
            val backend = widget.javaClass.name
            val ssh = looksRemote(title, widget)
            val logicalId = stableLogicalId(project, title, backend, widget)
            val id = instanceId(project, content, title, backend)
            val selectedContent = content == selected || content.isSelected
            val explicitAccess = settings.isTerminalExplicitlyEnabled(logicalId) ?: settings.isTerminalExplicitlyEnabled(id)
            val descriptor = TerminalDescriptor(
                id = id,
                logicalId = logicalId,
                title = title,
                projectName = project.name,
                projectPath = project.basePath,
                selected = selectedContent,
                active = activeToolWindow && selectedContent,
                ssh = ssh,
                aiAccessEnabled = when (explicitAccess) {
                    false -> false
                    true -> true
                    null -> settings.isAutopilotEnabled() ||
                        settings.mode() == PermissionMode.FULL || settings.mode() == PermissionMode.UNGUARDED
                },
                canRead = false,
                canWrite = false,
                backend = backend,
                warnings = warningsFor(widget, ssh),
            )
            TerminalHandle(descriptor, content, project)
        }.map { handle ->
            val policy = SecurityPolicy(settings)
            val descriptor = handle.descriptor.copy(
                canRead = policy.canRead(handle.descriptor),
                canWrite = policy.canWrite(handle.descriptor),
            )
            handle.copy(descriptor = descriptor)
        }
    }

    fun findWidget(content: Content): TerminalWidget? =
        TerminalToolWindowManager.findWidgetByContent(content)
            ?: TerminalToolWindowManager.getWidgetByContent(content)?.asNewWidget()

    private fun instanceId(project: Project, content: Content, title: String, backend: String): String {
        val raw = listOfNotNull(
            stableLogicalSeed(project, title, backend, null),
            content.executionId.takeIf { it != 0L }?.toString(),
            content.hashCode().toString(),
        ).joinToString("|")
        return digestId(raw)
    }

    private fun stableLogicalId(project: Project, title: String, backend: String, widget: TerminalWidget): String =
        "terminal-" + digestId(stableLogicalSeed(project, title, backend, widget))

    private fun stableLogicalSeed(project: Project, title: String, backend: String, widget: TerminalWidget?): String =
        listOfNotNull(
            project.locationHash,
            PathNormalization.key(project.basePath),
            title.trim().lowercase(),
            backend,
            widget?.shellCommand?.joinToString(" ")?.trim()?.lowercase(),
        ).joinToString("|")

    private fun digestId(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun looksRemote(title: String, widget: TerminalWidget): Boolean {
        val parts = mutableListOf(title, widget.javaClass.name)
        runCatching { widget.shellCommand?.joinToString(" ")?.let { parts += it } }.getOrNull()
        val text = parts.joinToString(" ").lowercase()
        val hostPort = Regex("""(?<![a-z0-9.-])(?:[a-z0-9-]+\.)+[a-z]{2,}:\d{2,5}(?!\d)""", RegexOption.IGNORE_CASE)
        val gatewayTitle = Regex("""^[a-z0-9.-]+:\d{2,5}$""", RegexOption.IGNORE_CASE)
        return listOf("ssh", "@", "remote", "wsl", "ijent").any { text.contains(it) } ||
            hostPort.containsMatchIn(title) || gatewayTitle.matches(title.trim())
    }

    private fun warningsFor(widget: TerminalWidget, ssh: Boolean): List<String> {
        val warnings = mutableListOf<String>()
        if (settings.isAutopilotEnabled()) {
            warnings += "Autopilot mode is active."
        }
        if (ssh) warnings += "Remote or SSH-like terminal detected."
        if (widget.javaClass.name.contains("block", ignoreCase = true)) {
            warnings += "Uses reworked terminal backend."
        }
        return warnings
    }
}
