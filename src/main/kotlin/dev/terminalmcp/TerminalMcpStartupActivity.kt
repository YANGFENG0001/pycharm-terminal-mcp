package dev.terminalmcp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class TerminalMcpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = TerminalMcpSettings.getInstance()
        settings.update { }
        if (settings.isAutopilotEnabled()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Terminal MCP Bridge")
                .createNotification(
                    "Terminal MCP Bridge is in Autopilot mode",
                    "AI can read and write all integrated terminals without plugin confirmation.",
                    NotificationType.WARNING,
                )
                .notify(project)
        }
        else if (settings.mode() == PermissionMode.UNGUARDED) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Terminal MCP Bridge")
                .createNotification(
                    "Terminal MCP Bridge is in Unguarded mode",
                    "AI can read and write all integrated terminals without plugin confirmation.",
                    NotificationType.WARNING,
                )
                .notify(project)
        }
    }
}
