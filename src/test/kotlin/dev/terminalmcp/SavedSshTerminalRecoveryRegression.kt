@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.terminalmcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SavedSshTerminalRecoveryRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        val identity = SavedSshIdentity.fromSavedServer("dev@192.0.2.10:2222 password", "192.0.2.10", 2222)
        val terminal = TerminalDescriptor("raw", "logical", "dev@192.0.2.10:2222", "project", "D:/project",
            true, true, true, true, true, true, "backend")
        val preset = TerminalPresetDescriptor("preset", terminal.title, null, "project", "D:/project", true, "saved-action")
        var checks = 0
        fun checkCase(condition: Boolean) { check(condition); checks++ }
        listOf("dev@192.0.2.10:22", "root@192.0.2.10:2222", "dev@192.0.2.11:2222", "selected").forEach {
            checkCase(!identity.matches(it))
        }
        checkCase(identity.matches("连接 dev@192.0.2.10:2222"))
        checkCase(!SavedSshIdentity.shellPromptVisible("Password:"))
        checkCase(!SavedSshIdentity.shellPromptVisible("Continue connecting (yes/no)?"))
        checkCase(!SavedSshIdentity.shellPromptVisible("running command output"))
        checkCase(SavedSshIdentity.shellPromptVisible("Welcome\n(base) dev@gpu:~$ "))
        checkCase(SavedSshIdentity.fromSavedServer("root@[::1]:22", "::1", 22).matches("root@[::1]:22"))
        checkCase(runCatching { SavedSshIdentity.fromSavedServer("NODE-A", "192.0.2.10", 2222) }.isFailure)
        checkCase(runCatching { SavedSshIdentity.fromSavedServer(preset.title, "192.0.2.10", 22) }.isFailure)

        fun scenario(existing: List<TerminalDescriptor>, available: List<TerminalPresetDescriptor> = listOf(preset),
                     appears: Boolean = true, idle: Boolean = true, probeOk: Boolean = true, pending: Boolean = false): Pair<SavedSshRecoveryOutcome, Int> {
            var now = 0L
            var creates = 0
            val recovery = SavedSshTerminalRecovery(
                listTerminals = { if (creates > 0 && appears && now >= 300_000_000L) listOf(terminal) else existing },
                listPresets = { available },
                create = { creates++ },
                shellIdle = { idle },
                probe = { _, _ -> probeOk },
                nanoTime = { now },
                pause = { now += it * 1_000_000L },
            )
            return recovery.ensure(identity, 1000, pending) to creates
        }
        val reuse = scenario(listOf(terminal))
        checkCase(reuse.first.ready && reuse.first.reused && !reuse.first.created && reuse.second == 0)
        val creation = scenario(emptyList())
        checkCase(creation.first.ready && creation.first.created && creation.second == 1 && creation.first.presetId == "preset")
        val noPreset = scenario(emptyList(), emptyList())
        checkCase(noPreset.first.status == "preset_not_found" && noPreset.second == 0)
        val ambiguousPreset = scenario(emptyList(), listOf(preset, preset.copy(id = "other")))
        checkCase(ambiguousPreset.first.status == "ambiguous_preset" && ambiguousPreset.second == 0)
        val ambiguousTerminal = scenario(listOf(terminal, terminal.copy(id = "other")))
        checkCase(ambiguousTerminal.first.status == "ambiguous_terminal" && ambiguousTerminal.second == 0)
        val denied = scenario(listOf(terminal.copy(canWrite = false)))
        checkCase(denied.first.status == "terminal_access_denied" && denied.second == 0)
        val wrongTerminal = scenario(listOf(terminal.copy(title = "dev@192.0.2.10:22")))
        checkCase(wrongTerminal.first.ready && wrongTerminal.first.created && wrongTerminal.second == 1)
        val wrongPreset = scenario(emptyList(), listOf(preset.copy(title = "root@192.0.2.10:2222")))
        checkCase(wrongPreset.first.status == "preset_not_found" && wrongPreset.second == 0)
        val timeout = scenario(emptyList(), appears = false)
        checkCase(timeout.first.timedOut && !timeout.first.ready && timeout.second == 1)
        val pending = scenario(emptyList(), pending = true)
        checkCase(pending.first.timedOut && !pending.first.created && pending.second == 0)
        val priorCreated = scenario(listOf(terminal), pending = true)
        checkCase(priorCreated.first.ready && priorCreated.first.reused && !priorCreated.first.created && priorCreated.second == 0)
        val busy = scenario(listOf(terminal), idle = false)
        checkCase(busy.first.timedOut && busy.second == 0)
        val failedProbe = scenario(listOf(terminal), probeOk = false)
        checkCase(failedProbe.first.status == "probe_failed" && !failedProbe.first.ready && failedProbe.second == 0)
        var probes = 0
        // An already elapsed budget must not send the probe command.
        val shortClock = generateSequence(0L) { it + 2_000_000_000L }.iterator()
        val elapsed = SavedSshTerminalRecovery({ listOf(terminal) }, { emptyList() }, {}, { true },
            { _, _ -> probes++; true }, { shortClock.next() }, {})
        checkCase(elapsed.ensure(identity, 1000, false).timedOut && probes == 0)

        for (explicit in listOf(true, false)) {
            val codec = Json { encodeDefaults = false; explicitNulls = explicit }
            val encoded = codec.encodeToString(SavedSshTerminalResult(status = "not_ready_timeout", message = "waiting"))
            val fields = Json.parseToJsonElement(encoded).jsonObject
            listOf("success", "ready", "created", "reused", "timed_out").forEach {
                checkCase(!fields.getValue(it).jsonPrimitive.boolean)
            }
        }
        println("Saved SSH terminal recovery regression: " + checks + " identity, lifecycle and JSON checks passed")
    }
}
