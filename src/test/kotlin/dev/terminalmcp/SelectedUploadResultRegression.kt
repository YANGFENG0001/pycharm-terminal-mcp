@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.terminalmcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

object SelectedUploadResultRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        val uploaded = SelectedFileUploadItem("a.txt", "/tmp/a.txt", true, "completed")
        val failed = uploaded.copy(success = false, message = "failed")
        val cases = listOf(
            SelectedFilesUploadResult(true, "server", 1, files = listOf(uploaded), message = "done"),
            SelectedFilesUploadResult(false, requestedCount = 0, message = "empty selection"),
            SelectedFilesUploadResult(false, requestedCount = 2, skippedPaths = listOf("a.txt", "missing.txt"), message = "preflight"),
            SelectedFilesUploadResult(false, "server", 2, listOf(failed), listOf("b.txt"), "stopped"),
            SelectedFilesUploadResult(false, "server", 1, listOf(failed), message = "last item failed"),
        )
        for (explicit in listOf(true, false)) {
            for (defaults in listOf(true, false)) {
                val codec = Json { explicitNulls = explicit; encodeDefaults = defaults }
                for (result in cases) {
                    val encoded = codec.encodeToString(result)
                    val fields = Json.parseToJsonElement(encoded).jsonObject
                    check(fields["files"] is JsonArray) { encoded }
                    check(fields["skippedPaths"] is JsonArray) { encoded }
                    check((fields.getValue("files") as JsonArray).size == result.files.size) { encoded }
                    check((fields.getValue("skippedPaths") as JsonArray).size == result.skippedPaths.size) { encoded }
                    check(codec.decodeFromString<SelectedFilesUploadResult>(encoded) == result)
                }
            }
        }
        println("Selected upload result regression: 20 JSON configuration/scenario cases passed")
    }
}
