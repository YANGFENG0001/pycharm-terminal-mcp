package dev.terminalmcp

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JPanel

/** Settings stay loadable even when the optional bundled MCP plugin is disabled. */
internal object McpConnectionUi {
    const val COPY_ACTION = "dev.terminalmcp.CopyMcpConfigAction"
    const val DIAGNOSE_ACTION = "dev.terminalmcp.DiagnoseMcpConnectionAction"

    fun controls(): JPanel = JPanel(GridLayout(0, 1, 0, 6)).apply {
        add(button("复制终端 MCP 连接配置", COPY_ACTION))
        add(button("检查终端 MCP 连接", DIAGNOSE_ACTION))
    }

    private fun button(label: String, actionId: String): JButton = JButton(label).apply {
        addActionListener {
            val context = DataManager.getInstance().getDataContext(this)
            val action = ActionManager.getInstance().getAction(actionId)
            if (action == null) {
                Messages.showWarningDialog(CommonDataKeys.PROJECT.getData(context),
                    "内置 MCP Server 插件未加载，连接入口不可用。请在插件管理中检查 MCP Server 状态；本操作不会自动启用或更改权限。",
                    "终端 MCP 桥接")
            } else {
                action.actionPerformed(AnActionEvent.createFromDataContext(
                    "Terminal MCP Settings", action.templatePresentation.clone(), context))
            }
        }
    }
}
