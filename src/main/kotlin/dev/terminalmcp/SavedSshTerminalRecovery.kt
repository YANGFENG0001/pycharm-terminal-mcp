package dev.terminalmcp

internal data class SavedSshIdentity(val user: String, val host: String, val port: Int) {
    fun matches(text: String): Boolean = addresses.findAll(text).any { match ->
        match.groupValues[1] == user &&
            match.groupValues[2].removeSurrounding("[", "]").equals(host.removeSurrounding("[", "]"), ignoreCase = true) &&
            match.groupValues[3].toIntOrNull() == port
    }

    companion object {
        private val addresses = Regex("(?<![A-Za-z0-9_.-])([A-Za-z0-9_.-]+)@(\\[[^\\]\\s]+\\]|[A-Za-z0-9_.-]+):([0-9]+)(?![0-9])")

        fun fromSavedServer(name: String, host: String?, port: Int): SavedSshIdentity {
            val address = addresses.findAll(name).singleOrNull()
                ?: throw RemoteTransferExpectedFailureException("保存的服务器名称未暴露唯一 user@host:port；不能猜测 SSH 用户。请明确使用 list_predefined_terminals 返回的对应预设。")
            val user = address.groupValues[1]
            val identity = SavedSshIdentity(user, host?.trim().orEmpty(), port)
            if (identity.host.isBlank() || port !in 1..65535 || !identity.matches(name)) {
                throw RemoteTransferExpectedFailureException("保存服务器的名称与实际 host/port 不一致；拒绝创建其他服务器终端。")
            }
            return identity
        }

        fun shellPromptVisible(text: String): Boolean =
            Regex("(?:^|\\n)[^\\n]{0,300}[$#%] ?$").containsMatchIn(text.trimEnd())
    }
}

internal data class SavedSshRecoveryOutcome(
    val ready: Boolean = false,
    val created: Boolean = false,
    val reused: Boolean = false,
    val timedOut: Boolean = false,
    val terminal: TerminalDescriptor? = null,
    val presetId: String? = null,
    val status: String,
    val message: String,
)

// Keep the recovery state machine testable without starting an IDE or SSH connection.
internal class SavedSshTerminalRecovery(
    private val listTerminals: () -> List<TerminalDescriptor>,
    private val listPresets: () -> List<TerminalPresetDescriptor>,
    private val create: (TerminalPresetDescriptor) -> Unit,
    private val shellIdle: (TerminalDescriptor) -> Boolean,
    private val probe: (TerminalDescriptor, Int) -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    fun ensure(identity: SavedSshIdentity, timeoutMs: Int, creationPending: Boolean): SavedSshRecoveryOutcome {
        var created = false
        var reused = false
        var presetId: String? = null
        val deadline = nanoTime() + timeoutMs * 1_000_000L
        var attempted = creationPending
        while (true) {
            val candidates = listTerminals().filter { it.ssh && identity.matches(it.title) }
            if (candidates.size > 1) return SavedSshRecoveryOutcome(created = created, status = "ambiguous_terminal",
                message = "多个终端匹配相同 SSH 用户/主机/端口；拒绝选择或创建重复终端。")
            val terminal = candidates.singleOrNull()
            if (terminal != null) {
                reused = !created
                if (!terminal.canRead || !terminal.canWrite) return SavedSshRecoveryOutcome(created = created, reused = reused,
                    terminal = terminal, presetId = presetId, status = "terminal_access_denied",
                    message = "匹配终端存在，但不可同时读写；未绕过终端权限，也未另建终端规避限制。")
                val remaining = ((deadline - nanoTime()) / 1_000_000L).toInt()
                if (remaining >= 100 && shellIdle(terminal)) {
                    val ready = probe(terminal, remaining)
                    return SavedSshRecoveryOutcome(ready, created, reused, terminal = terminal, presetId = presetId,
                        status = if (ready) "ready" else "probe_failed",
                        message = if (ready) "目标 SSH 终端已完成只读命令探测；请继续远程版本核对，再决定是否上传。"
                            else "目标终端命令探测未完成；未重发、未中断、未上传覆盖。")
                }
            } else if (!attempted) {
                val presets = listPresets().filter { it.sshLike && identity.matches(it.title) }
                if (presets.size != 1) return SavedSshRecoveryOutcome(status = if (presets.isEmpty()) "preset_not_found" else "ambiguous_preset",
                    message = "未找到唯一匹配的已保存 SSH 预设；未创建终端，不回退到 selected 服务器。")
                val preset = presets.single()
                presetId = preset.id
                attempted = true
                create(preset)
                created = true
            }
            if (nanoTime() >= deadline) return SavedSshRecoveryOutcome(created = created, reused = reused, timedOut = true,
                terminal = terminal, presetId = presetId, status = "not_ready_timeout",
                message = "等待目标终端就绪超时；创建动作不等于认证成功。可能仍在连接、等待人工认证、命令忙碌或未显示可识别的 POSIX 提示符。未重复创建、未上传。")
            pause(100)
        }
    }
}
