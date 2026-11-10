package dev.terminalmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.terminal.ui.TerminalWidget
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

class TerminalOperations(private val settings: TerminalMcpSettings) {
    private val registry = TerminalRegistry(settings)
    private val policy = SecurityPolicy(settings)
    private val audit = AuditLog(settings)

    fun listTerminals(): List<TerminalDescriptor> =
        registry.list().map { it.descriptor }

    fun readTerminal(terminalId: String, maxLines: Int): String {
        val handle = registry.find(terminalId) ?: terminalNotFound(terminalId)
        if (!policy.canRead(handle.descriptor)) throw TerminalAccessDeniedException("Terminal is not readable under current permission settings.")

        val text = invokeOnEdt {
            val widget = registry.findWidget(handle.content) ?: error("Terminal widget is no longer available.")
            widget.getText()?.toString().orEmpty()
        }
        val tail = text.lines().takeLast(maxLines.coerceIn(1, 5000)).joinToString("\n")
        return if (settings.snapshot().redactSensitiveOutput) SensitiveRedactor.redact(tail) else tail
    }

    fun sendText(terminalId: String, text: String, pressEnter: Boolean): String =
        sendTextInternal(terminalId, text, pressEnter, skipConfirmation = false)

    fun executeAndWait(terminalId: String, command: String, timeoutMs: Int, shell: String): TerminalExecutionResult {
        require(timeoutMs in 100..240000) { "timeout_ms 必须在 100 到 240000 之间。" }
        require(command.isNotBlank()) { "命令不能为空。" }
        require(!ApplicationManager.getApplication().isDispatchThread) { "不能在 IDE 界面线程等待终端命令。" }
        val handle = registry.find(terminalId) ?: terminalNotFound(terminalId)
        require(shell == "posix" || (shell == "auto" && handle.descriptor.ssh)) {
            "等待接口仅支持 POSIX shell。SSH 默认使用 POSIX；本地 POSIX 终端需显式 shell=posix；其他 shell 请用发送接口。"
        }
        if (!policy.canRead(handle.descriptor) || !policy.canWrite(handle.descriptor)) {
            throw TerminalAccessDeniedException("等待执行需要终端同时可读和可写。")
        }
        val id = handle.descriptor.id
        val lock = executionLocks.computeIfAbsent(id) { ReentrantLock() }
        require(lock.tryLock()) { "该终端已有等待执行中的命令；拒绝交错发送。" }
        try {
            val widget = invokeOnEdt { registry.findWidget(handle.content) ?: error("终端已关闭。") }
            fun read(): String = invokeOnEdt { widget.getText().toString() }
            pendingExecutions[id]?.let { pending ->
                require(TerminalExecutionProtocol.parse(read(), pending) != null) {
                    "上次等待已超时但命令尚未确认完成；请读取终端或中断，不自动重发。"
                }
                pendingExecutions.remove(id)
            }
            policy.confirmIfNeeded(handle.descriptor, command)?.let { throw TerminalAccessDeniedException(it) }
            val token = "__MCP_EXEC_" + UUID.randomUUID().toString().replace("-", "")
            pendingExecutions[id] = token
            sendTextInternal(terminalId, TerminalExecutionProtocol.wrap(command, token), true, skipConfirmation = true)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (true) {
                TerminalExecutionProtocol.parse(read(), token)?.let { (output, code) ->
                    pendingExecutions.remove(id)
                    val safeOutput = if (settings.snapshot().redactSensitiveOutput) SensitiveRedactor.redact(output) else output
                    return TerminalExecutionResult(terminalId, success = code == 0, completed = true,
                        exit_code = code, output = safeOutput, message = "命令已完成。")
                }
                if (System.nanoTime() >= deadline) {
                    return TerminalExecutionResult(terminalId, timed_out = true,
                        message = "等待超时；命令可能仍在运行，未自动中断或重发。")
                }
                Thread.sleep(100)
            }
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private val executionLocks = ConcurrentHashMap<String, ReentrantLock>()
        private val pendingExecutions = ConcurrentHashMap<String, String>()
    }

    fun sendDeploymentAutomationText(terminalId: String, text: String, pressEnter: Boolean): String {
        val skipConfirmation = settings.isAutopilotEnabled() || !settings.snapshot().requireConfirmForDeploymentActions
        return sendTextInternal(terminalId, text, pressEnter, skipConfirmation)
    }

    private fun sendTextInternal(
        terminalId: String,
        text: String,
        pressEnter: Boolean,
        skipConfirmation: Boolean,
    ): String {
        val handle = registry.find(terminalId) ?: terminalNotFound(terminalId)
        if (!policy.canWrite(handle.descriptor)) throw TerminalAccessDeniedException("Terminal is not writable under current permission settings.")

        val payload = normalizePayload(text, pressEnter)
        if (!skipConfirmation) {
            policy.confirmIfNeeded(handle.descriptor, payload)?.let { throw TerminalAccessDeniedException(it) }
        }

        if (pressEnter && payload.indexOf('\n') < 0 && payload.indexOf('\r') == payload.length - 1) {
            invokeOnEdt {
                val widget = registry.findWidget(handle.content) ?: error("Terminal widget is no longer available.")
                widget.sendCommandToExecute(text.trimEnd('\r', '\n'))
            }
        }
        else {
            val connector = invokeOnEdt {
                val widget = registry.findWidget(handle.content) ?: error("Terminal widget is no longer available.")
                widget.ttyConnectorAccessor.ttyConnector
                    ?: error("This terminal backend does not expose direct text input.")
            }
            writeToConnector(connector, payload)
        }
        audit.record(handle.descriptor, if (pressEnter) "execute" else "input", payload)
        return "Sent to terminal ${handle.descriptor.title}"
    }

    fun setAccess(terminalId: String, enabled: Boolean): TerminalDescriptor {
        val handle = registry.find(terminalId) ?: terminalNotFound(terminalId)
        settings.setTerminalAccess(handle.descriptor.id, enabled)
        return registry.find(handle.descriptor.id)?.descriptor ?: handle.descriptor.copy(
            aiAccessEnabled = enabled,
            canRead = false,
            canWrite = false,
        )
    }

    private fun terminalNotFound(selector: String): Nothing {
        val count = registry.list().size
        if (count == 0) {
            throw TerminalNotFoundException("Terminal not found: $selector. No PyCharm integrated terminals are currently visible to Terminal MCP Bridge. Open/select a PyCharm Terminal tab and retry list_global_terminals.")
        }
        throw TerminalNotFoundException("Terminal not found: $selector. Call list_global_terminals and use an id, id prefix, selected, active, current, or a unique title/project/path substring.")
    }

    private fun normalizePayload(text: String, pressEnter: Boolean): String =
        if (pressEnter) text.trimEnd('\r', '\n') + "\r" else text

    private fun writeToConnector(connector: com.jediterm.terminal.TtyConnector, payload: String) {
        val chunkSize = 8192
        var offset = 0
        while (offset < payload.length) {
            val next = (offset + chunkSize).coerceAtMost(payload.length)
            connector.write(payload.substring(offset, next))
            offset = next
            if (offset < payload.length) Thread.sleep(2)
        }
    }

    private fun <T> invokeOnEdt(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) return block()
        var value: T? = null
        var failure: Throwable? = null
        app.invokeAndWait {
            runCatching { block() }
                .onSuccess { value = it }
                .onFailure { failure = it }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
