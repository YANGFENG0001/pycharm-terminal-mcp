package dev.terminalmcp

enum class PermissionMode(val wireName: String) {
    SAFE("safe"),
    ENHANCED("enhanced"),
    FULL("full"),
    UNGUARDED("unguarded");

    companion object {
        fun fromWireName(value: String?): PermissionMode =
            entries.firstOrNull { it.wireName == value } ?: SAFE
    }
}

enum class TerminalScope(val wireName: String) {
    ACTIVE("active"),
    SELECTED("selected"),
    ALL("all");

    companion object {
        fun fromWireName(value: String?): TerminalScope =
            entries.firstOrNull { it.wireName == value } ?: ACTIVE
    }
}

data class TerminalDescriptor(
    val id: String,
    val logicalId: String,
    val title: String,
    val projectName: String,
    val projectPath: String?,
    val selected: Boolean,
    val active: Boolean,
    val ssh: Boolean,
    val aiAccessEnabled: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val backend: String,
    val warnings: List<String> = emptyList(),
)

data class TerminalHandle(
    val descriptor: TerminalDescriptor,
    val content: com.intellij.ui.content.Content,
    val project: com.intellij.openapi.project.Project,
)


data class RelayAuthSetupDescriptor(
    val ready: Boolean,
    val sourceServer: String,
    val targetServer: String,
    val sourceHost: String,
    val sourcePort: Int,
    val sourceUser: String,
    val targetHost: String,
    val targetPort: Int,
    val targetUser: String,
    val sourceTerminalId: String?,
    val commands: List<String>,
    val message: String,
    val warnings: List<String> = emptyList(),
    val requiresOneTimePassword: Boolean = true,
)
