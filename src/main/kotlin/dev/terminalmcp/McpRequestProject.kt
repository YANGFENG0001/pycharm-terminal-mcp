package dev.terminalmcp

import com.intellij.concurrency.currentThreadContext
import com.intellij.mcpserver.mcpCallInfoOrNull
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project

internal object McpRequestProject {
    // The host resolves this once per call. Never look at window focus during a transfer.
    fun current(): Project? {
        if (PluginManagerCore.getPlugin(PluginId.getId("com.intellij.mcpServer"))?.isEnabled != true) return null
        return currentThreadContext().mcpCallInfoOrNull?.project
    }
}
