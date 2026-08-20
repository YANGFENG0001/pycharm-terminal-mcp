package com.mcob.terminalmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages

class SecurityPolicy(private val settings: TerminalMcpSettings) {
    fun canRead(descriptor: TerminalDescriptor): Boolean {
        val state = settings.snapshot()
        val explicit = settings.isTerminalExplicitlyEnabled(descriptor.id)
        if (explicit == false) return false
        if (settings.isAutopilotEnabled()) return true
        if (descriptor.ssh && !state.allowSshTerminals) return false
        if (settings.mode() == PermissionMode.UNGUARDED || settings.mode() == PermissionMode.FULL) return true
        if (explicit == true) return true

        return when (settings.scope()) {
            TerminalScope.ACTIVE -> descriptor.active
            TerminalScope.SELECTED -> descriptor.selected
            TerminalScope.ALL -> true
        }
    }

    fun canWrite(descriptor: TerminalDescriptor): Boolean {
        if (settings.isAutopilotEnabled()) return true
        return settings.snapshot().allowTerminalInput && canRead(descriptor)
    }

    fun confirmIfNeeded(descriptor: TerminalDescriptor, command: String): String? {
        val state = settings.snapshot()
        if (settings.isAutopilotEnabled()) return null
        val mode = settings.mode()
        if (mode == PermissionMode.FULL || mode == PermissionMode.UNGUARDED) return null

        val reasons = mutableListOf<String>()
        if (descriptor.ssh && state.requireConfirmForSshInput) {
            reasons += "this is an SSH or remote-looking terminal"
        }
        if (state.requireConfirmForDangerousCommands) {
            DangerousCommandDetector.findReason(command)?.let { reasons += it }
        }
        if (reasons.isEmpty()) return null

        val approved = askUserForConfirmation(descriptor, command, reasons.joinToString("; "))
        return if (approved) null else "User confirmation denied: ${reasons.joinToString("; ")}"
    }

    private fun askUserForConfirmation(
        descriptor: TerminalDescriptor,
        command: String,
        reason: String,
    ): Boolean {
        var approved = false
        val app = ApplicationManager.getApplication()
        val prompt = """
            AI wants to type into terminal:

            ${descriptor.projectName} / ${descriptor.title}

            Reason for confirmation:
            $reason

            Command:
            $command
        """.trimIndent()

        app.invokeAndWait {
            approved = Messages.showYesNoDialog(
                prompt,
                "Terminal MCP Bridge Confirmation",
                "Allow",
                "Deny",
                Messages.getWarningIcon(),
            ) == Messages.YES
        }
        return approved
    }
}
