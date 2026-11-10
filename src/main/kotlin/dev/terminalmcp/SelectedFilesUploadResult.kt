@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.terminalmcp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
data class SelectedFileUploadItem(
    val localPath: String,
    val remotePath: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val success: Boolean,
    val message: String,
)

@Serializable
data class SelectedFilesUploadResult(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val success: Boolean,
    val serverId: String? = null,
    val requestedCount: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val files: List<SelectedFileUploadItem> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val skippedPaths: List<String> = emptyList(),
    val message: String,
)
