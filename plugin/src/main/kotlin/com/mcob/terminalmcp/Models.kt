package com.mcob.terminalmcp

enum class PermissionMode(val wireName: String, private val label: String) {
    SAFE("safe", "安全"),
    ENHANCED("enhanced", "增强"),
    FULL("full", "完全访问"),
    UNGUARDED("unguarded", "不设防");

    override fun toString(): String = label;

    companion object {
        fun fromWireName(value: String?): PermissionMode =
            entries.firstOrNull { it.wireName == value } ?: SAFE
    }
}

enum class TerminalScope(val wireName: String, private val label: String) {
    ACTIVE("active", "当前终端"),
    SELECTED("selected", "选中终端"),
    ALL("all", "所有终端");

    override fun toString(): String = label;

    companion object {
        fun fromWireName(value: String?): TerminalScope =
            entries.firstOrNull { it.wireName == value } ?: ACTIVE
    }
}

data class TerminalDescriptor(
    val id: String,
    val logicalId: String = id,
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
