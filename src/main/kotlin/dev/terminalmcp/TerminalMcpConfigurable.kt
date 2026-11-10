package dev.terminalmcp

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComboBox
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JPanel

class TerminalMcpConfigurable : Configurable {
    private val settings: TerminalMcpSettings = TerminalMcpSettings.getInstance()

    private val autoPilot = JBCheckBox("启用全自动模式")
    private val modeBox = JComboBox(PermissionMode.entries.toTypedArray())
    private val scopeBox = JComboBox(TerminalScope.entries.toTypedArray())
    private val allowSsh = JBCheckBox("允许访问 SSH 和远程终端")
    private val confirmSsh = JBCheckBox("AI 向 SSH 终端输入前需要确认")
    private val confirmDanger = JBCheckBox("危险命令需要确认")
    private val redact = JBCheckBox("屏蔽密码、Token、私钥和 sudo 密码提示附近内容")
    private val audit = JBCheckBox("将 AI 终端输入写入审计日志")
    private val input = JBCheckBox("允许 AI 向终端输入")
    private val deployment = JBCheckBox("允许 AI 执行 PyCharm 部署操作")
    private val confirmDeployment = JBCheckBox("PyCharm 部署、上传、拉取和同步前需要确认")
    private val remoteTransfer = JBCheckBox("允许 AI 执行远程文件传输")
    private val serverRelayTransfer = JBCheckBox("允许服务器之间直接中转大文件（rsync/scp）")
    private val automaticRelayAuthSetup = JBCheckBox("允许 AI 使用 PyCharm 已保存凭据自动配置服务器免密（每次弹窗授权）")
    private val terminalBackedTransfer = JBCheckBox("允许通过严格匹配的 SSH 终端传输小文件")
    private val terminalBackedTransferMaxBytes = JBTextField()
    private val localTerminalCreation = JBCheckBox("允许 AI 创建本地终端")
    private val predefinedTerminalCreation = JBCheckBox("允许 AI 创建已配置的 SSH 或远程终端")
    private val auditPath = JBLabel(AuditLog(settings).path())
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "终端 MCP 桥接"

    override fun createComponent(): JComponent {
        reset()
        autoPilot.addActionListener { updateControlStates() }
        modeBox.addActionListener { applyModeDefaultsToUi() }
        deployment.addActionListener { updateControlStates() }
        remoteTransfer.addActionListener { updateControlStates() }
        serverRelayTransfer.addActionListener { updateControlStates() }
        terminalBackedTransfer.addActionListener { updateControlStates() }
        modeBox.renderer = localizedRenderer { modeLabel(it as PermissionMode) }
        scopeBox.renderer = localizedRenderer { scopeLabel(it as TerminalScope) }

        panel = FormBuilder.createFormBuilder()
            .addComponent(autoPilot)
            .addLabeledComponent("权限模式", modeBox)
            .addLabeledComponent("终端范围", scopeBox)
            .addComponent(allowSsh)
            .addComponent(confirmSsh)
            .addComponent(confirmDanger)
            .addComponent(redact)
            .addComponent(audit)
            .addComponent(input)
            .addComponent(deployment)
            .addComponent(confirmDeployment)
            .addComponent(remoteTransfer)
            .addComponent(JBLabel("<html>全项目上传：专用接口必须显式声明全量；旧上传接口省略路径会拒绝。<br>部分文件上传：必须明确列出文件；不接受目录，不自动上传其他文件。<br>两者均可能覆盖同名文件，但不会删除远端额外文件；沿用上方确认设置。</html>"))
            .addComponent(serverRelayTransfer)
            .addComponent(automaticRelayAuthSetup)
            .addComponent(terminalBackedTransfer)
            .addLabeledComponent("SSH 终端备用通道最大字节数（仅小文件）", terminalBackedTransferMaxBytes)
            .addComponent(localTerminalCreation)
            .addComponent(predefinedTerminalCreation)
            .addComponent(JBLabel("<html>SSH 终端不存在时，AI 可调用“确保已保存服务器的 SSH 终端就绪”。<br>使用 PyCharm 保存的预设及凭据，唯一匹配服务器并等待只读探测；不会绕过认证或访问权限。</html>"))
            .addLabeledComponent("审计日志", auditPath)
            .addComponent(
                JBLabel(
                        "<html><body width='560'>" +
                        "默认使用安全模式。完全访问模式允许 AI 读写所有可访问终端，并减少插件确认。" +
                        " 不设防模式还可以关闭敏感信息屏蔽。" +
                        " 任何模式都不会绕过 SSH、sudo、操作系统或服务器认证。" +
                        "</body></html>"
                )
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return JPanel(BorderLayout()).apply { add(panel, BorderLayout.NORTH) }
    }

    override fun isModified(): Boolean {
        val state = settings.snapshot()
        return autoPilot.isSelected != state.autoPilotMode ||
            modeBox.selectedItem != settings.mode() ||
            scopeBox.selectedItem != settings.scope() ||
            allowSsh.isSelected != state.allowSshTerminals ||
            confirmSsh.isSelected != state.requireConfirmForSshInput ||
            confirmDanger.isSelected != state.requireConfirmForDangerousCommands ||
            redact.isSelected != state.redactSensitiveOutput ||
            audit.isSelected != state.auditAiInput ||
            input.isSelected != state.allowTerminalInput ||
            deployment.isSelected != state.allowDeploymentAutomation ||
            confirmDeployment.isSelected != state.requireConfirmForDeploymentActions ||
            remoteTransfer.isSelected != state.allowRemoteFileTransferAutomation ||
            serverRelayTransfer.isSelected != state.allowServerRelayTransfer ||
            automaticRelayAuthSetup.isSelected != state.allowAutomaticRelayAuthSetup ||
            terminalBackedTransfer.isSelected != state.allowTerminalBackedServerTransfer ||
            terminalBackedTransferMaxBytes.text.toLongOrNull() != state.terminalBackedTransferMaxBytes ||
            localTerminalCreation.isSelected != state.allowLocalTerminalCreation ||
            predefinedTerminalCreation.isSelected != state.allowPredefinedTerminalCreation
    }

    override fun apply() {
        settings.update { state ->
            state.autoPilotMode = autoPilot.isSelected
            state.permissionMode = (modeBox.selectedItem as PermissionMode).wireName
            state.terminalScope = (scopeBox.selectedItem as TerminalScope).wireName
            state.allowSshTerminals = allowSsh.isSelected
            state.requireConfirmForSshInput = confirmSsh.isSelected
            state.requireConfirmForDangerousCommands = confirmDanger.isSelected
            state.redactSensitiveOutput = redact.isSelected
            state.auditAiInput = audit.isSelected
            state.allowTerminalInput = input.isSelected
            state.allowDeploymentAutomation = deployment.isSelected
            state.requireConfirmForDeploymentActions = confirmDeployment.isSelected
            state.allowRemoteFileTransferAutomation = remoteTransfer.isSelected
            state.allowServerRelayTransfer = serverRelayTransfer.isSelected
            state.allowAutomaticRelayAuthSetup = automaticRelayAuthSetup.isSelected
            state.allowTerminalBackedServerTransfer = terminalBackedTransfer.isSelected
            state.terminalBackedTransferMaxBytes = terminalBackedTransferMaxBytes.text.toLongOrNull()?.coerceAtLeast(1L)
                ?: state.terminalBackedTransferMaxBytes
            state.allowLocalTerminalCreation = localTerminalCreation.isSelected
            state.allowPredefinedTerminalCreation = predefinedTerminalCreation.isSelected
        }
        reset()
    }

    override fun reset() {
        val state = settings.snapshot()
        autoPilot.isSelected = state.autoPilotMode
        modeBox.selectedItem = settings.mode()
        scopeBox.selectedItem = settings.scope()
        allowSsh.isSelected = state.allowSshTerminals
        confirmSsh.isSelected = state.requireConfirmForSshInput
        confirmDanger.isSelected = state.requireConfirmForDangerousCommands
        redact.isSelected = state.redactSensitiveOutput
        audit.isSelected = state.auditAiInput
        input.isSelected = state.allowTerminalInput
        deployment.isSelected = state.allowDeploymentAutomation
        confirmDeployment.isSelected = state.requireConfirmForDeploymentActions
        remoteTransfer.isSelected = state.allowRemoteFileTransferAutomation
        serverRelayTransfer.isSelected = state.allowServerRelayTransfer
        automaticRelayAuthSetup.isSelected = state.allowAutomaticRelayAuthSetup
        terminalBackedTransfer.isSelected = state.allowTerminalBackedServerTransfer
        terminalBackedTransferMaxBytes.text = state.terminalBackedTransferMaxBytes.toString()
        localTerminalCreation.isSelected = state.allowLocalTerminalCreation
        predefinedTerminalCreation.isSelected = state.allowPredefinedTerminalCreation
        auditPath.text = AuditLog(settings).path()
        updateControlStates()
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun localizedRenderer(label: (Any?) -> String): javax.swing.ListCellRenderer<Any?> =
        object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(list, label(value), index, isSelected, cellHasFocus)
        }

    private fun modeLabel(mode: PermissionMode): String = when (mode) {
        PermissionMode.SAFE -> "安全模式"
        PermissionMode.ENHANCED -> "增强模式"
        PermissionMode.FULL -> "完全访问"
        PermissionMode.UNGUARDED -> "不设防模式"
    }

    private fun scopeLabel(scope: TerminalScope): String = when (scope) {
        TerminalScope.ACTIVE -> "当前活动终端"
        TerminalScope.SELECTED -> "当前选中终端"
        TerminalScope.ALL -> "所有终端"
    }

    private fun applyModeDefaultsToUi() {
        if (autoPilot.isSelected) {
            updateControlStates()
            return
        }
        val mode = modeBox.selectedItem as? PermissionMode ?: return
        when (mode) {
            PermissionMode.SAFE -> {
                scopeBox.selectedItem = TerminalScope.ACTIVE
                confirmSsh.isSelected = true
                confirmDanger.isSelected = true
                redact.isSelected = true
                audit.isSelected = true
                input.isSelected = true
            }
            PermissionMode.ENHANCED -> {
                scopeBox.selectedItem = TerminalScope.SELECTED
                confirmSsh.isSelected = true
                confirmDanger.isSelected = true
                redact.isSelected = true
                audit.isSelected = true
                input.isSelected = true
            }
            PermissionMode.FULL -> {
                scopeBox.selectedItem = TerminalScope.ALL
                allowSsh.isSelected = true
                confirmSsh.isSelected = false
                confirmDanger.isSelected = false
                redact.isSelected = true
                audit.isSelected = true
                input.isSelected = true
            }
            PermissionMode.UNGUARDED -> {
                scopeBox.selectedItem = TerminalScope.ALL
                allowSsh.isSelected = true
                confirmSsh.isSelected = false
                confirmDanger.isSelected = false
                input.isSelected = true
            }
        }
        updateControlStates()
    }

    private fun updateControlStates() {
        val enabled = !autoPilot.isSelected
        modeBox.isEnabled = enabled
        scopeBox.isEnabled = enabled
        allowSsh.isEnabled = enabled
        confirmSsh.isEnabled = enabled
        confirmDanger.isEnabled = enabled
        redact.isEnabled = enabled
        audit.isEnabled = enabled
        input.isEnabled = enabled
        deployment.isEnabled = enabled
        confirmDeployment.isEnabled = enabled && deployment.isSelected
        remoteTransfer.isEnabled = enabled
        serverRelayTransfer.isEnabled = enabled && remoteTransfer.isSelected
        automaticRelayAuthSetup.isEnabled = enabled && serverRelayTransfer.isSelected
        terminalBackedTransfer.isEnabled = enabled
        terminalBackedTransferMaxBytes.isEnabled = enabled && terminalBackedTransfer.isSelected
        localTerminalCreation.isEnabled = enabled
        predefinedTerminalCreation.isEnabled = enabled
    }
}
