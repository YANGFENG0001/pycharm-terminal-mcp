package com.mcob.terminalmcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

class ProjectLocator {
    fun allProjects(): List<Project> =
        ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }

    fun resolve(selector: String?): Project {
        val projects = allProjects()
        if (projects.isEmpty()) error("No open PyCharm project is available.")

        val requested = selector?.trim().orEmpty()
        if (requested.isEmpty()) {
            if (projects.size == 1) return projects.single()
            error("Project selector is required when multiple PyCharm projects are open: ${projects.joinToString { it.name }}")
        }

        val matches = projects.filter { project ->
            project.name.equals(requested, ignoreCase = true) ||
                PathNormalization.equivalent(project.basePath, requested) ||
                PathNormalization.key(project.basePath).contains(PathNormalization.key(requested))
        }
        return when (matches.size) {
            1 -> matches.single()
            0 -> error("Project not found: $requested")
            else -> error("Project selector is ambiguous: $requested")
        }
    }

    fun resolveMany(selector: String?): List<Project> =
        if (selector.isNullOrBlank()) allProjects() else listOf(resolve(selector))
}
