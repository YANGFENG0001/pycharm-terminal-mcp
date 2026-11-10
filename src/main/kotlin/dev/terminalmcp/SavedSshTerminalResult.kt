@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.terminalmcp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
data class SavedSshTerminalResult(
    @EncodeDefault val success: Boolean = false,
    @EncodeDefault val ready: Boolean = false,
    @EncodeDefault val created: Boolean = false,
    @EncodeDefault val reused: Boolean = false,
    @EncodeDefault val timed_out: Boolean = false,
    val status: String,
    val message: String,
    val serverId: String? = null,
    val terminal: TerminalInfoResult? = null,
    val presetId: String? = null,
    val error: String? = null,
)
