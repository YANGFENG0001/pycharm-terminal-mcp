package com.mcob.terminalmcp

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

class AuditLog(private val settings: TerminalMcpSettings) {
    private val logFile: Path =
        Path.of(PathManager.getLogPath(), "terminal-mcp-bridge-audit.log")

    fun record(terminal: TerminalDescriptor, action: String, payload: String) {
        if (!settings.snapshot().auditAiInput) return
        val line = buildString {
            append(Instant.now())
            append('\t')
            append(action)
            append('\t')
            append("terminal=")
            append(terminal.logicalId)
            append('\t')
            append("instance=")
            append(terminal.id)
            append('\t')
            append("project=")
            append(terminal.projectName.replace('\t', ' '))
            append('\t')
            append("title=")
            append(terminal.title.replace('\t', ' '))
            append('\t')
            append(payload.replace('\r', ' ').replace('\n', ' '))
            append('\n')
        }
        Files.createDirectories(logFile.parent)
        Files.writeString(
            logFile,
            line,
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    fun recordAutomation(action: String, target: String, payload: String) {
        if (!settings.snapshot().auditAiInput) return
        val line = buildString {
            append(Instant.now())
            append('\t')
            append(action)
            append('\t')
            append("target=")
            append(target.replace('\t', ' '))
            append('\t')
            append(payload.replace('\r', ' ').replace('\n', ' '))
            append('\n')
        }
        Files.createDirectories(logFile.parent)
        Files.writeString(
            logFile,
            line,
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    fun path(): String = logFile.toString()
}

