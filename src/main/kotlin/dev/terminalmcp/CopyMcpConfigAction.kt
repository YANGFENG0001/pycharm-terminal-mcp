package dev.terminalmcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.application.ApplicationManager
import java.awt.datatransfer.StringSelection

class CopyMcpConfigAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val server = ApplicationManager.getApplication().getService(McpServerService::class.java)
        val url = server.serverStreamUrl.takeIf { server.serverPortFlow.value != null }
        if (url == null) {
            Messages.showWarningDialog(e.project,
                "MCP 服务尚未启用或尚未监听。请在“设置 → AI → MCP 服务器”中启用服务，等待启动后再复制。不会自动修改设置或授权。",
                "终端 MCP 桥接")
            return
        }
        val format = Messages.showDialog(e.project,
            "请选择客户端配置格式。全局连接随当前 PyCharm 项目切换，不绑定会话目录。",
            "复制全局 MCP 连接配置", arrayOf("Air / ACP（JSON）", "Codex（TOML）", "取消"), 0, Messages.getQuestionIcon())
        val text = when (format) {
            0 -> McpConnectionReport.airConfig(url)
            1 -> McpConnectionReport.clientConfig(url)
            else -> return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        Messages.showInfoMessage(
            e.project,
            "已复制跟随当前项目的全局 MCP 连接配置，请合并而非覆盖已有配置。\n" +
                "Air/ACP 请添加为全局 MCP 并启用；新建会话后检查能力返回中的 projectPath。\n" +
                "切换项目后重新发现服务器和终端；显式旧项目参数会被拒绝。\n" +
                "使用的是当前 IDE 监听地址，不含密码或认证令牌。",
            "终端 MCP 桥接",
        )
    }
}
