@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.terminalmcp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable(with = TerminalExecutionResultSerializer::class)
data class TerminalExecutionResult(
    val terminal_id: String,
    @EncodeDefault val success: Boolean = false,
    @EncodeDefault val completed: Boolean = false,
    @EncodeDefault val timed_out: Boolean = false,
    @EncodeDefault val exit_code: Int? = null,
    @EncodeDefault val output: String = "",
    val message: String,
    val error: String? = null,
)

// MCP may disable explicitNulls; emit the required contract as a JSON tree.
internal object TerminalExecutionResultSerializer : KSerializer<TerminalExecutionResult> {
    override val descriptor = buildClassSerialDescriptor("dev.terminalmcp.TerminalExecutionResult") {
        element<String>("terminal_id")
        element<Boolean>("success")
        element<Boolean>("completed")
        element<Boolean>("timed_out")
        element("exit_code", Int.serializer().nullable.descriptor)
        element<String>("output")
        element<String>("message")
        element("error", String.serializer().nullable.descriptor, isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: TerminalExecutionResult) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Terminal execution requires JSON.")
        json.encodeJsonElement(buildJsonObject {
            put("terminal_id", value.terminal_id)
            put("success", value.success)
            put("completed", value.completed)
            put("timed_out", value.timed_out)
            put("exit_code", value.exit_code?.let { JsonPrimitive(it) } ?: JsonNull)
            put("output", value.output)
            put("message", value.message)
            value.error?.let { put("error", it) }
        })
    }

    override fun deserialize(decoder: Decoder): TerminalExecutionResult {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Terminal execution requires JSON.")
        val fields = json.decodeJsonElement().jsonObject
        return TerminalExecutionResult(
            terminal_id = fields.getValue("terminal_id").jsonPrimitive.content,
            success = fields.getValue("success").jsonPrimitive.boolean,
            completed = fields.getValue("completed").jsonPrimitive.boolean,
            timed_out = fields.getValue("timed_out").jsonPrimitive.boolean,
            exit_code = fields.getValue("exit_code").let { if (it == JsonNull) null else it.jsonPrimitive.int },
            output = fields.getValue("output").jsonPrimitive.content,
            message = fields.getValue("message").jsonPrimitive.content,
            error = fields["error"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

internal object TerminalExecutionProtocol {
    fun wrap(command: String, token: String): String {
        val quoted = "'" + command.replace("'", "'\"'\"'") + "'"
        return "( set +e; printf '\\n%s\\n' '${token}_START'; ( eval $quoted ); " +
            "__mcp_rc=$?; printf '\\n%s:%s\\n' '${token}_END' \"$" + "__mcp_rc\" )"
    }

    fun parse(text: String, token: String): Pair<String, Int>? {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val start = Regex("(?m)^" + Regex.escape(token + "_START") + "$\n").find(normalized) ?: return null
        val end = Regex("(?m)^" + Regex.escape(token + "_END") + ":([0-9]+)$")
            .find(normalized, start.range.last + 1) ?: return null
        val code = end.groupValues[1].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        return normalized.substring(start.range.last + 1, end.range.first).removeSuffix("\n") to code
    }
}
