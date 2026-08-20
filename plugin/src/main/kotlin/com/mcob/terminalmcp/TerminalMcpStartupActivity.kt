package com.mcob.terminalmcp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class TerminalMcpStartupActivity : ProjectActivity {
    private companion object {
        const val NOTIFICATION_GROUP_ID = "TerminalMcpBridgeNotifications"
    }

    override suspend fun execute(project: Project) {
        val settings = TerminalMcpSettings.getInstance()
        settings.update { }
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            ?: return

        if (settings.isAutopilotEnabled()) {
            notificationGroup.createNotification(
                    "终端 MCP 桥接已启用全自动模式",
                    "AI 可以在无需插件确认的情况下读取和写入集成终端。",
                    NotificationType.WARNING,
                )
                .notify(project)
        }
        else if (settings.mode() == PermissionMode.UNGUARDED) {
            notificationGroup.createNotification(
                    "终端 MCP 桥接处于不设防模式",
                    "AI 可以读取和写入所有集成终端，且不会弹出插件确认。",
                    NotificationType.WARNING,
                )
                .notify(project)
        }
    }
}
