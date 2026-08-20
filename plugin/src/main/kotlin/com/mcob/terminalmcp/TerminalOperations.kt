package com.mcob.terminalmcp

import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.ConcurrentHashMap

class TerminalOperations(private val settings: TerminalMcpSettings) {
    private companion object {
        val cursorSnapshots = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()
        const val MAX_CURSOR_SNAPSHOTS_PER_TERMINAL = 32
    }

    private val registry = TerminalRegistry(settings)
    private val policy = SecurityPolicy(settings)
    private val audit = AuditLog(settings)

    data class TerminalReadSnapshot(val terminalId: String, val logicalId: String, val output: String, val nextCursor: Int, val cursorReset: Boolean, val hasMore: Boolean)
    data class TerminalExecutionSnapshot(val terminalId: String, val logicalId: String, val output: String, val nextCursor: Int, val completed: Boolean, val timedOut: Boolean, val exitCode: Int?, val durationMs: Long)

    fun listTerminals(): List<TerminalDescriptor> = registry.list().map { it.descriptor }

    fun readTerminal(terminalId: String, maxLines: Int, fromCursor: Int? = null): TerminalReadSnapshot {
        val handle = registry.find(terminalId) ?: terminalNotFound(terminalId)
        if (!policy.canRead(handle.descriptor)) throw TerminalAccessDeniedException("Terminal is not readable under current permission settings.")
        val text = terminalText(handle)
        val limit = maxLines.coerceIn(1, 5000)
        val requested = fromCursor?.coerceAtLeast(0)
        val storedBaseline = requested?.let { cursorSnapshots[handle.descriptor.logicalId]?.get(it) }
        val cursorReset = requested != null && storedBaseline == null && requested > text.length
        val start = when {
            requested == null -> text.lines().takeLast(limit).joinToString("\n").let { (text.length - it.length).coerceAtLeast(0) }
            storedBaseline != null -> longestCommonPrefixLength(storedBaseline, text)
            cursorReset -> 0
            else -> requested.coerceAtMost(text.length)
        }
        val raw = text.substring(start)
        val limited = raw.split('\n').take(limit).joinToString("\n")
        val nextCursor = (start + limited.length).coerceAtMost(text.length)
        rememberCursor(handle.descriptor.logicalId, nextCursor, text.substring(0, nextCursor))
        return TerminalReadSnapshot(handle.descriptor.id, handle.descriptor.logicalId, redact(limited), nextCursor, cursorReset, nextCursor < text.length)
    }

    fun executeAndWait(terminalId: String, command: String, timeoutMs: Long, maxLines: Int): TerminalExecutionSnapshot {
        val handle = registry.find(terminalId) ?: terminalNotFound(terminalId)
        if (!policy.canWrite(handle.descriptor)) throw TerminalAccessDeniedException("Terminal is not writable under current permission settings.")
        val timeout = timeoutMs.coerceIn(250L, 600_000L)
        val marker = "__TERMINAL_MCP_DONE_${System.nanoTime()}__"
        val markerLine = Regex("(?m)^${Regex.escape(marker)}:(-?\\d+)\\r?$")
        policy.confirmIfNeeded(handle.descriptor, normalizePayload(command, true))?.let { throw TerminalAccessDeniedException(it) }
        val baseline = terminalText(handle)
        val started = System.nanoTime()
        val wrapped = completionWrappedCommand(handle, command, marker)
        invokeOnEdt {
            val widget = registry.findWidget(handle.content)
                ?: throw TerminalWidgetUnavailableException("Terminal widget is no longer available. The PyCharm terminal may have been closed or recreated.")
            widget.sendCommandToExecute(wrapped)
        }
        audit.record(handle.descriptor, "execute-wait", command)
        var latest = baseline
        var captured = ""
        var completed = false
        var exitCode: Int? = null
        val deadline = System.nanoTime() + timeout * 1_000_000L
        while (System.nanoTime() < deadline) {
            Thread.sleep(100L)
            latest = terminalText(handle)
            val delta = changedSuffix(baseline, latest)
            val match = markerLine.find(delta)
            if (match != null) {
                exitCode = match.groupValues[1].toIntOrNull()
                captured = cleanExecutionOutput(delta.substring(0, match.range.first), marker)
                completed = true
                break
            }
        }
        if (!completed) captured = cleanExecutionOutput(changedSuffix(baseline, latest), marker)
        val finalText = terminalText(handle)
        val nextCursor = finalText.length
        rememberCursor(handle.descriptor.logicalId, nextCursor, finalText)
        val output = redact(captured).lines().takeLast(maxLines.coerceIn(1, 5000)).joinToString("\n")
        return TerminalExecutionSnapshot(handle.descriptor.id, handle.descriptor.logicalId, output, nextCursor, completed, !completed, exitCode, (System.nanoTime() - started) / 1_000_000L)
    }

    fun sendText(terminalId: String, text: String, pressEnter: Boolean): String {
        val handle = registry.find(terminalId) ?: terminalNotFound(terminalId)
        if (!policy.canWrite(handle.descriptor)) throw TerminalAccessDeniedException("Terminal is not writable under current permission settings.")
        val payload = normalizePayload(text, pressEnter)
        policy.confirmIfNeeded(handle.descriptor, payload)?.let { throw TerminalAccessDeniedException(it) }
        if (pressEnter && payload.indexOf('\n') < 0 && payload.indexOf('\r') == payload.length - 1) {
            invokeOnEdt {
                (registry.findWidget(handle.content)
                    ?: throw TerminalWidgetUnavailableException("Terminal widget is no longer available. The PyCharm terminal may have been closed or recreated."))
                    .sendCommandToExecute(text.trimEnd('\r', '\n'))
            }
        } else {
            val connector = invokeOnEdt {
                (registry.findWidget(handle.content)
                    ?: throw TerminalWidgetUnavailableException("Terminal widget is no longer available. The PyCharm terminal may have been closed or recreated."))
                    .ttyConnectorAccessor.ttyConnector
                    ?: error("This terminal backend does not expose direct text input.")
            }
            writeToConnector(connector, payload)
        }
        audit.record(handle.descriptor, if (pressEnter) "execute" else "input", payload)
        return "Sent to terminal ${handle.descriptor.title}"
    }

    fun interrupt(terminalId: String): String {
        val handle = registry.find(terminalId) ?: terminalNotFound(terminalId)
        if (!policy.canWrite(handle.descriptor)) throw TerminalAccessDeniedException("Terminal is not writable under current permission settings.")
        policy.confirmIfNeeded(handle.descriptor, "Ctrl+C")?.let { throw TerminalAccessDeniedException(it) }
        val connector = invokeOnEdt {
            (registry.findWidget(handle.content)
                ?: throw TerminalWidgetUnavailableException("Terminal widget is no longer available. The PyCharm terminal may have been closed or recreated."))
                .ttyConnectorAccessor.ttyConnector
                ?: error("This terminal backend does not expose direct text input.")
        }
        writeToConnector(connector, "\u0003")
        audit.record(handle.descriptor, "interrupt", "Ctrl+C")
        return "Interrupted terminal ${handle.descriptor.title}"
    }

    fun setAccess(terminalId: String, enabled: Boolean): TerminalDescriptor {
        val handle = registry.find(terminalId) ?: terminalNotFound(terminalId)
        settings.setTerminalAccess(handle.descriptor.logicalId, enabled)
        return registry.find(handle.descriptor.logicalId)?.descriptor ?: handle.descriptor.copy(aiAccessEnabled = enabled, canRead = false, canWrite = false)
    }

    private fun completionWrappedCommand(handle: TerminalHandle, command: String, marker: String): String {
        if (handle.descriptor.ssh) {
            val safeCommand = protectInteractiveShell(command)
            return "$safeCommand; __terminal_mcp_code=\$?; printf '\\n$marker:%s\\n' \"\$__terminal_mcp_code\""
        }
        val shell = invokeOnEdt {
            val widget = registry.findWidget(handle.content)
                ?: throw TerminalWidgetUnavailableException("Terminal widget is no longer available. The PyCharm terminal may have been closed or recreated.")
            widget.shellCommand?.joinToString(" ").orEmpty().lowercase()
        }
        val windowsFallback = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        return when {
            shell.contains("powershell") || shell.contains("pwsh") || (windowsFallback && !shell.contains("cmd")) -> "& { $command }; \$__terminal_mcp_code=\$LASTEXITCODE; Write-Output '$marker:'\$__terminal_mcp_code"
            shell.contains("cmd.exe") || shell.endsWith("\\cmd") -> "${protectInteractiveShell(command)} & echo $marker:%ERRORLEVEL%"
            else -> "${protectInteractiveShell(command)}; __terminal_mcp_code=\$?; printf '\\n$marker:%s\\n' \"\$__terminal_mcp_code\""
        }
    }

    /**
     * Keep commands that can terminate the interactive shell inside a child
     * shell so the PyCharm terminal widget remains available for completion.
     */
    private fun protectInteractiveShell(command: String): String {
        val terminatesShell = Regex("(?i)(^|[;&|(){}\\s])(exit|logout|exec)(?=\\s|$)").containsMatchIn(command)
        return if (terminatesShell) "( $command )" else command
    }

    private fun cleanExecutionOutput(text: String, marker: String): String =
        text.lineSequence()
            .filterNot { it.contains(marker) }
            .joinToString("\n")
            .trimEnd('\r', '\n')

    private fun changedSuffix(baseline: String, latest: String): String =
        latest.substring(longestCommonPrefixLength(baseline, latest))

    private fun longestCommonPrefixLength(left: String, right: String): Int {
        val limit = minOf(left.length, right.length)
        var index = 0
        while (index < limit && left[index] == right[index]) index++
        return index
    }

    private fun rememberCursor(logicalId: String, cursor: Int, snapshot: String) {
        val snapshots = cursorSnapshots.computeIfAbsent(logicalId) { ConcurrentHashMap() }
        snapshots[cursor] = snapshot
        if (snapshots.size > MAX_CURSOR_SNAPSHOTS_PER_TERMINAL) {
            snapshots.keys.sorted().take(snapshots.size - MAX_CURSOR_SNAPSHOTS_PER_TERMINAL).forEach(snapshots::remove)
        }
    }

    private fun terminalText(handle: TerminalHandle): String = invokeOnEdt {
        val widget = registry.findWidget(handle.content)
            ?: throw TerminalWidgetUnavailableException("Terminal widget is no longer available. The PyCharm terminal may have been closed or recreated.")
        widget.getText().toString()
    }

    private fun redact(text: String): String = if (settings.snapshot().redactSensitiveOutput) SensitiveRedactor.redact(text) else text
    private fun terminalNotFound(selector: String): Nothing {
        if (registry.list().isEmpty()) throw TerminalNotFoundException("Terminal not found: $selector. No PyCharm integrated terminals are currently visible to Terminal MCP Bridge.")
        throw TerminalNotFoundException("Terminal not found: $selector. Call list_global_terminals and use an id, logicalId, id prefix, selected, active, current, or a unique title/project/path substring.")
    }
    private fun normalizePayload(text: String, pressEnter: Boolean): String = if (pressEnter) text.trimEnd('\r', '\n') + "\r" else text
    private fun writeToConnector(connector: com.jediterm.terminal.TtyConnector, payload: String) {
        var offset = 0
        while (offset < payload.length) { val next = (offset + 8192).coerceAtMost(payload.length); connector.write(payload.substring(offset, next)); offset = next; if (offset < payload.length) Thread.sleep(2) }
    }
    private fun <T> invokeOnEdt(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) return block()
        var value: T? = null; var failure: Throwable? = null
        app.invokeAndWait { runCatching { block() }.onSuccess { value = it }.onFailure { failure = it } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
