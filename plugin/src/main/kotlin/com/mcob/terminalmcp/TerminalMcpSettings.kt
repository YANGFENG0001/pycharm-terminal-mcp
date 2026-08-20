package com.mcob.terminalmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "TerminalMcpBridgeSettings", storages = [Storage("terminalMcpBridge.xml")])
class TerminalMcpSettings : PersistentStateComponent<TerminalMcpSettings.State> {
    class State {
        var permissionMode: String = PermissionMode.SAFE.wireName
        var terminalScope: String = TerminalScope.ACTIVE.wireName
        var autoPilotMode: Boolean = false
        var allowSshTerminals: Boolean = true
        var requireConfirmForSshInput: Boolean = true
        var requireConfirmForDangerousCommands: Boolean = true
        var redactSensitiveOutput: Boolean = true
        var auditAiInput: Boolean = true
        var allowTerminalInput: Boolean = true
        var allowDeploymentAutomation: Boolean = false
        var requireConfirmForDeploymentActions: Boolean = true
        var autoConfirmDeploymentActions: Boolean = false
        var allowRemoteFileTransferAutomation: Boolean = false
        var allowLocalTerminalCreation: Boolean = false
        var allowPredefinedTerminalCreation: Boolean = false
        var terminalAccess: MutableMap<String, Boolean> = mutableMapOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun snapshot(): State = state

    fun update(block: (State) -> Unit) {
        block(state)
        normalizeForMode()
    }

    fun mode(): PermissionMode = PermissionMode.fromWireName(state.permissionMode)

    fun scope(): TerminalScope = TerminalScope.fromWireName(state.terminalScope)

    fun isAutopilotEnabled(): Boolean = state.autoPilotMode

    fun setTerminalAccess(terminalId: String, enabled: Boolean) {
        state.terminalAccess[terminalId] = enabled
    }

    fun isTerminalExplicitlyEnabled(terminalId: String): Boolean? = state.terminalAccess[terminalId]

    fun exportPublicSettings(): Map<String, Any?> =
        linkedMapOf(
            "permissionMode" to state.permissionMode,
            "terminalScope" to state.terminalScope,
            "autoPilotMode" to state.autoPilotMode,
            "allowSshTerminals" to state.allowSshTerminals,
            "requireConfirmForSshInput" to state.requireConfirmForSshInput,
            "requireConfirmForDangerousCommands" to state.requireConfirmForDangerousCommands,
            "redactSensitiveOutput" to state.redactSensitiveOutput,
            "auditAiInput" to state.auditAiInput,
            "allowTerminalInput" to state.allowTerminalInput,
            "allowDeploymentAutomation" to state.allowDeploymentAutomation,
            "requireConfirmForDeploymentActions" to state.requireConfirmForDeploymentActions,
            "autoConfirmDeploymentActions" to state.autoConfirmDeploymentActions,
            "allowRemoteFileTransferAutomation" to state.allowRemoteFileTransferAutomation,
            "allowLocalTerminalCreation" to state.allowLocalTerminalCreation,
            "allowPredefinedTerminalCreation" to state.allowPredefinedTerminalCreation,
        )

    fun normalizeForMode() {
        when (mode()) {
            PermissionMode.SAFE -> {
                state.terminalScope = TerminalScope.ACTIVE.wireName
                state.requireConfirmForSshInput = true
                state.requireConfirmForDangerousCommands = true
                state.redactSensitiveOutput = true
                state.auditAiInput = true
                state.allowTerminalInput = true
            }
            PermissionMode.ENHANCED -> {
                if (scope() == TerminalScope.ACTIVE) {
                    state.terminalScope = TerminalScope.SELECTED.wireName
                }
                state.requireConfirmForSshInput = true
                state.requireConfirmForDangerousCommands = true
                state.redactSensitiveOutput = true
                state.auditAiInput = true
                state.allowTerminalInput = true
            }
            PermissionMode.FULL -> {
                state.terminalScope = TerminalScope.ALL.wireName
                state.allowSshTerminals = true
                state.requireConfirmForSshInput = false
                state.requireConfirmForDangerousCommands = false
                state.redactSensitiveOutput = true
                state.auditAiInput = true
                state.allowTerminalInput = true
            }
            PermissionMode.UNGUARDED -> {
                state.terminalScope = TerminalScope.ALL.wireName
                state.allowSshTerminals = true
                state.requireConfirmForSshInput = false
                state.requireConfirmForDangerousCommands = false
                state.allowTerminalInput = true
            }
        }
    }

    companion object {
        fun getInstance(): TerminalMcpSettings =
            ApplicationManager.getApplication().getService(TerminalMcpSettings::class.java)
    }
}
