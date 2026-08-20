package com.mcob.terminalmcp

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class TerminalMcpConfigurable : Configurable {
    private val settings: TerminalMcpSettings = TerminalMcpSettings.getInstance()

    private val autoPilot = JBCheckBox("全自动模式（自动执行 AI 操作）")
    private val modeBox = JComboBox(PermissionMode.entries.toTypedArray())
    private val scopeBox = JComboBox(TerminalScope.entries.toTypedArray())
    private val allowSsh = JBCheckBox("允许访问 SSH 和远程终端")
    private val confirmSsh = JBCheckBox("AI 向 SSH 终端输入前需要人工确认")
    private val confirmDanger = JBCheckBox("危险命令执行前需要人工确认")
    private val redact = JBCheckBox("屏蔽密码、Token、私钥和 sudo 密码提示附近内容")
    private val audit = JBCheckBox("将 AI 输入写入审计日志")
    private val input = JBCheckBox("允许 AI 向终端输入")
    private val deployment = JBCheckBox("允许 AI 执行 PyCharm 部署操作")
    private val confirmDeployment = JBCheckBox("PyCharm 部署、上传和下载前需要人工确认")
    private val remoteTransfer = JBCheckBox("允许 AI 进行远程文件传输")
    private val localTerminalCreation = JBCheckBox("允许 AI 创建本地终端")
    private val predefinedTerminalCreation = JBCheckBox("允许 AI 创建已配置的 SSH 或预设终端")
    private val autoConfirmDeployment = JBCheckBox("自动确认部署覆盖和传输（不再弹出人工审批）")
    private val auditPath = JBLabel(AuditLog(settings).path())
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "终端 MCP 桥接"

    override fun createComponent(): JComponent {
        reset()
        autoPilot.addActionListener { updateControlStates() }
        modeBox.addActionListener { applyModeDefaultsToUi() }
        deployment.addActionListener { updateControlStates() }

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
            .addComponent(autoConfirmDeployment)
            .addComponent(remoteTransfer)
            .addComponent(localTerminalCreation)
            .addComponent(predefinedTerminalCreation)
            .addLabeledComponent("审计日志", auditPath)
            .addComponent(
                JBLabel(
                        "<html><body width='560'>" +
                        "默认使用安全模式。开启全自动模式后，AI 可按开关执行终端、部署和文件传输操作。" +
                        "自动确认部署覆盖只影响 PyCharm 文件传输确认，不会绕过 SSH、sudo、操作系统或服务器认证。" +
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
            autoConfirmDeployment.isSelected != state.autoConfirmDeploymentActions ||
            remoteTransfer.isSelected != state.allowRemoteFileTransferAutomation ||
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
            state.autoConfirmDeploymentActions = autoConfirmDeployment.isSelected
            state.allowRemoteFileTransferAutomation = remoteTransfer.isSelected
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
        autoConfirmDeployment.isSelected = state.autoConfirmDeploymentActions
        remoteTransfer.isSelected = state.allowRemoteFileTransferAutomation
        localTerminalCreation.isSelected = state.allowLocalTerminalCreation
        predefinedTerminalCreation.isSelected = state.allowPredefinedTerminalCreation
        auditPath.text = AuditLog(settings).path()
        updateControlStates()
    }

    override fun disposeUIResources() {
        panel = null
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
        autoConfirmDeployment.isEnabled = enabled && deployment.isSelected
        remoteTransfer.isEnabled = enabled
        localTerminalCreation.isEnabled = enabled
        predefinedTerminalCreation.isEnabled = enabled
    }
}
