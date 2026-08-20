package com.mcob.terminalmcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

class CopyMcpConfigAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val text = """
            This plugin registers its terminal tools directly with JetBrains MCP Server when that plugin is installed.

            Tools:
            - list_global_terminals
            - read_global_terminal
            - send_global_terminal_text
            - execute_global_terminal_command
            - list_predefined_terminals
            - create_local_terminal
            - create_predefined_terminal
            - list_remote_servers
            - list_selected_deployment_servers
            - list_deployment_configurations
            - run_deployment_configuration
            - upload_to_selected_deployment_server
            - invoke_selected_deployment_action
            - transfer_selected_deployment_server
            - transfer_remote_directory
            - set_terminal_ai_access
            - set_terminal_autopilot_mode
            - get_terminal_mcp_settings

            Settings:
            PyCharm | Settings | Tools | Terminal MCP Bridge
        """.trimIndent()
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        Messages.showInfoMessage(
            e.project,
            "Terminal MCP Bridge tool summary copied to clipboard.",
            "Terminal MCP Bridge",
        )
    }
}
