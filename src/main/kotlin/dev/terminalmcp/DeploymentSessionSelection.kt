package dev.terminalmcp

import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP-only Deployment server selection.
 *
 * The selection is deliberately kept in memory and keyed by project. It does
 * not change PyCharm's persisted default server or .idea/deployment.xml.
 */
object DeploymentSessionSelection {
    private val selectedByProject = ConcurrentHashMap<String, String>()

    fun get(project: Project): String? = selectedByProject[projectKey(project)]

    fun set(project: Project, serverId: String): String? =
        selectedByProject.put(projectKey(project), serverId)

    fun clear(project: Project): String? = selectedByProject.remove(projectKey(project))

    private fun projectKey(project: Project): String =
        project.locationHash.ifBlank { project.basePath ?: project.name }
}
