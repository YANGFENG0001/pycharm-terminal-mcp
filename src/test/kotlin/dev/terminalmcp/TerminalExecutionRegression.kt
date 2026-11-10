@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.terminalmcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.nio.file.Files
import java.nio.file.Paths

object TerminalExecutionRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        val token = "__MCP_EXEC_test"
        check(TerminalExecutionProtocol.parse("echo '${token}_END:0'", token) == null)
        val crlf = TerminalExecutionProtocol.parse("old\n${token}_START\r\nhello\r\n\r\n${token}_END:7\r\nprompt", token)
        check(crlf == ("hello\n" to 7)) { "CRLF parse: $crlf" }
        check(TerminalExecutionProtocol.parse(token + "_START\npartial", token) == null)
        check(TerminalExecutionProtocol.parse(token + "_START\n" + token + "_END:999", token) == null)
        val json = Json.encodeToString(TerminalExecutionResult("test", message = "pending"))
        listOf("success", "completed", "timed_out", "exit_code", "output").forEach { check(json.contains("\"$it\"")) }
        val bash = if (System.getProperty("os.name").startsWith("Windows")) "D:/Git/bin/bash.exe" else "/bin/bash"
        val results = listOf(
            TerminalExecutionResult("test", message = "sent"),
            TerminalExecutionResult("test", timed_out = true, message = "timeout"),
            TerminalExecutionResult("test", message = "busy", error = "pending"),
            TerminalExecutionResult("test", completed = true, success = true, exit_code = 0, message = "done"),
            TerminalExecutionResult("test", completed = true, exit_code = 7, message = "failed"),
        )
        for (explicit in listOf(true, false)) {
            for (defaults in listOf(true, false)) {
                val codec = Json { explicitNulls = explicit; encodeDefaults = defaults }
                results.forEach { result ->
                    val encoded = codec.encodeToString(result)
                    val fields = Json.parseToJsonElement(encoded).jsonObject
                    check(fields.containsKey("exit_code")) { encoded }
                    if (result.exit_code == null) check(fields.getValue("exit_code") == JsonNull) { encoded }
                    else check(fields.getValue("exit_code").jsonPrimitive.int == result.exit_code) { encoded }
                    check(codec.decodeFromString<TerminalExecutionResult>(encoded) == result)
                }
            }
        }
        check(Files.isRegularFile(Paths.get(bash))) { "Bash required for execution regression: $bash" }
        val cases = listOf("printf 'hello\\n'" to 0, "printf 'before\\n'; exit 7" to 7,
            "printf '%s\\n' \"it's quoted\"" to 0, "false" to 1, "printf 'first\\n'\nprintf 'second\\n'" to 0)
        for ((command, expected) in cases) {
            val wrapped = TerminalExecutionProtocol.wrap(command, token)
            val process = ProcessBuilder(bash, "--noprofile", "--norc", "-s")
                .redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(wrapped + "; printf 'SHELL_ALIVE\\n'\n") }
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            val parsed = TerminalExecutionProtocol.parse(output, token) ?: error(output)
            check(parsed.second == expected) { output }
            check(output.contains("SHELL_ALIVE"))
            check(!parsed.first.contains("SHELL_ALIVE"))
        }
        println("Terminal execution regression: parser, JSON fields and 5 real Bash commands passed")
    }
}
