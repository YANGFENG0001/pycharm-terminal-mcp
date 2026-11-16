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
                    "终端 MCP 桥接已启用全自动模式",
                    "AI 可读写本次连接项目的可访问终端，无需插件再次确认。全局 MCP 连接可跟随当前项目，不要求打开聊天目录。",
                    NotificationType.WARNING,
                )
                .notify(project)
        }
        else if (settings.mode() == PermissionMode.UNGUARDED) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Terminal MCP Bridge")
                .createNotification(
                    "终端 MCP 桥接已启用不设防模式",
                    "AI 可读写所有可访问的集成终端，无需插件再次确认。",
                    NotificationType.WARNING,
                )
                .notify(project)
        }
    }
}
