package com.mcob.terminalmcp

import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

object DeploymentSessionSelection {
    private val serverNames = ConcurrentHashMap<String, String>()

    fun select(project: Project, serverName: String) {
        serverNames[project.locationHash] = serverName
    }

    fun selectedName(project: Project): String? = serverNames[project.locationHash]
}
