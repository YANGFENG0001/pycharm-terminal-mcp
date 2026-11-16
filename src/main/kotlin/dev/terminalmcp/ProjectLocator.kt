package dev.terminalmcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

class ProjectLocator(
    private val requestProject: Project? = McpRequestProject.current(),
    private val projectsProvider: () -> List<Project> = { ProjectManager.getInstance().openProjects.toList() },
) {
    fun allProjects(): List<Project> =
        projectsProvider().filterNot { it.isDisposed }

    fun resolve(selector: String?): Project {
        val projects = allProjects()
        if (projects.isEmpty()) error("No open PyCharm project is available.")

        val requested = selector?.trim().orEmpty()
        requestProject?.let { pinned ->
            if (pinned !in projects) throw ExpectedMcpFailureException("本次调用的项目已关闭；未切换到其他项目。")
            if (requested.isEmpty() || requested == CurrentProjectRouting.FOLLOW_CURRENT ||
                pinned.name.equals(requested, ignoreCase = true) ||
                com.intellij.openapi.util.io.FileUtil.pathsEqual(pinned.basePath,
                    com.intellij.openapi.util.io.FileUtil.toCanonicalPath(requested))) return pinned
            throw ExpectedMcpFailureException("project_selector 与本次连接项目不匹配；请重新发现当前项目，未切换目标。")
        }
        if (requested.isEmpty()) {
            if (projects.size == 1) return projects.single()
            error("Project selector is required when multiple PyCharm projects are open: ${projects.joinToString { it.name }}")
        }

        val matches = projects.filter { project ->
            project.name.equals(requested, ignoreCase = true) ||
                project.basePath.equals(requested, ignoreCase = true) ||
                project.basePath?.contains(requested, ignoreCase = true) == true
        }
        return when (matches.size) {
            1 -> matches.single()
            0 -> error("Project not found: $requested")
            else -> error("Project selector is ambiguous: $requested")
        }
    }

    fun resolveMany(selector: String?): List<Project> =
        if (selector.isNullOrBlank() && requestProject == null) allProjects() else listOf(resolve(selector))
}
