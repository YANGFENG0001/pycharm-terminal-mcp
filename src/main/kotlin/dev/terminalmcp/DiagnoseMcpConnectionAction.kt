package dev.terminalmcp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.McpSessionProjectResolver
import com.intellij.mcpserver.settings.McpServerSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages

class DiagnoseMcpConnectionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val server = ApplicationManager.getApplication().getService(McpServerService::class.java)
        val registered = try {
            val toolset = ExtensionPointName.create<McpToolset>("com.intellij.mcpServer.mcpToolset")
                .findExtension(TerminalMcpToolset::class.java)
            toolset?.javaClass?.methods?.count { it.isAnnotationPresent(McpTool::class.java) } ?: 0
        } catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: LinkageError) {
            -1
        } catch (_: RuntimeException) {
            -1
        }
        val report = McpConnectionReport.summary(
            ApplicationInfo.getInstance().build.asString(),
            PluginManagerCore.getPlugin(PluginId.getId("com.mcob.terminal-mcp-bridge"))?.version ?: "unknown",
            McpServerSettings.getInstance().enableMcpServer,
            server.serverStreamUrl.takeIf { server.serverPortFlow.value != null }, registered,
            ProjectManager.getInstance().openProjects.mapNotNull { it.basePath },
            ApplicationManager.getApplication().getService(McpSessionProjectResolver::class.java).javaClass.name)
        Messages.showInfoMessage(e.project, report, "终端 MCP 连接检查")
    }
}
