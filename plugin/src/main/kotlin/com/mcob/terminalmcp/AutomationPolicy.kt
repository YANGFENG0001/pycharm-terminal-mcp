package com.mcob.terminalmcp

class AutomationPolicy(private val settings: TerminalMcpSettings) {
    fun requireDeploymentAutomation() {
        if (!settings.isAutopilotEnabled() && !settings.snapshot().allowDeploymentAutomation) {
            expectedMcpFailure("AI deployment automation is disabled in Terminal MCP Bridge settings.")
        }
    }

    fun requireRemoteFileTransferAutomation() {
        if (!settings.isAutopilotEnabled() && !settings.snapshot().allowRemoteFileTransferAutomation) {
            expectedMcpFailure("AI remote file transfer is disabled in Terminal MCP Bridge settings.")
        }
    }

    fun requireLocalTerminalCreation() {
        if (!settings.isAutopilotEnabled() && !settings.snapshot().allowLocalTerminalCreation) {
            expectedMcpFailure("AI local terminal creation is disabled in Terminal MCP Bridge settings.")
        }
    }

    fun requirePredefinedTerminalCreation() {
        if (!settings.isAutopilotEnabled() && !settings.snapshot().allowPredefinedTerminalCreation) {
            expectedMcpFailure("AI configured or SSH terminal creation is disabled in Terminal MCP Bridge settings.")
        }
    }
}
