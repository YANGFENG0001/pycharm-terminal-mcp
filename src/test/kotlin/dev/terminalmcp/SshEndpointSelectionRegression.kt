package dev.terminalmcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SshEndpointSelectionRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        val info = SshEndpointDescriptor("eel-abc123", "NODE-A", "dev", "192.0.2.10", 2222, "/", "UNIX")
        val second = info.copy(id = "eel-other", port = 22)
        listOf("", "  ", "webdeploy-abc123", "missing", "192.0.2.10:22", "dev@192.0.2.10:22").forEach {
            check(!SshEndpointSelection.matches(it, info)) { "Incorrect match: " + it }
        }
        listOf("eel-abc123", "eel-abc", "NODE-A", "node-a", "dev", "192.0.2.10", "192.0.2.10:2222", "dev@192.0.2.10:2222").forEach {
            check(SshEndpointSelection.matches(it, info)) { "Missing match: " + it }
        }
        val endpoints = listOf(info, second)
        check(endpoints.filter { SshEndpointSelection.matches("192.0.2.10", it) }.singleOrNull() == null)
        check(endpoints.filter { SshEndpointSelection.matches("192.0.2.10:22", it) }.singleOrNull() == second)
        check(listOf(info).filter { SshEndpointSelection.matches("", it) }.singleOrNull() == null)
        check(emptyList<SshEndpointDescriptor>().filter { SshEndpointSelection.matches("eel-abc", it) }.singleOrNull() == null)
        val toolset = TerminalMcpToolset()
        val boundary = TerminalMcpToolset::class.java.declaredMethods.single { it.name == "fileTransferAction" }
        boundary.isAccessible = true
        listOf(RemoteTransferExpectedFailureException("missing endpoint"), IllegalArgumentException("invalid path"), java.io.IOException("connection failed")).forEach { failure ->
            val action: () -> FileTransferResult = { throw failure }
            val result = boundary.invoke(toolset, "local", "remote", "upload", action) as FileTransferResult
            check(!result.success && result.error == failure.message)
            val json = Json.parseToJsonElement(Json.encodeToString(result)).jsonObject
            check(!json.getValue("success").jsonPrimitive.boolean && json.containsKey("error"))
        }
        val cancel: () -> FileTransferResult = { throw java.util.concurrent.CancellationException("cancelled") }
        val cancellation = runCatching { boundary.invoke(toolset, "local", "remote", "upload", cancel) }.exceptionOrNull()
        check(cancellation is java.lang.reflect.InvocationTargetException && cancellation.cause is java.util.concurrent.CancellationException)
        println("SSH endpoint selection regression: 18 selection cases and 4 real tool failure boundary cases passed")
    }
}
