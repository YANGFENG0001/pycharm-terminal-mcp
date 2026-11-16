package dev.terminalmcp

import com.intellij.openapi.util.io.FileUtil

internal object CurrentProjectRouting {
    const val HEADER = "IJ_MCP_SERVER_PROJECT_PATH"
    const val FOLLOW_CURRENT = "terminal-mcp:current-project"

    fun checkModal(modal: Boolean) {
        if (modal) throw ExpectedMcpFailureException("PyCharm 正在显示设置、连接或确认对话框；请先关闭或完成对话框，再调用工具。当前请求未排队执行，请勿重复发送写入操作。")
    }

    fun followsCurrent(argument: String?, callHeader: String?, sessionHeader: String?): Boolean =
        listOf(argument, callHeader, sessionHeader).any { it == FOLLOW_CURRENT }

    fun <T> choose(open: List<T>, focused: T?): T {
        if (open.isEmpty()) throw ExpectedMcpFailureException("没有已打开的 PyCharm 项目；请打开要操作的项目。")
        if (focused != null && focused in open) return focused
        if (open.size == 1) return open.single()
        throw ExpectedMcpFailureException("存在多个项目但无法确定当前窗口；请先激活目标 PyCharm 项目窗口。未自动选择其他项目。")
    }

    fun checkExplicitPaths(currentPath: String?, vararg requested: String?) {
        requested.filterNotNull().filter { it.isNotBlank() && it != FOLLOW_CURRENT }.forEach {
            if (currentPath == null || !FileUtil.pathsEqual(FileUtil.toCanonicalPath(it), FileUtil.toCanonicalPath(currentPath))) {
                throw ExpectedMcpFailureException("当前项目已切换或显式项目不匹配：$it。当前项目：${currentPath ?: "无本地路径"}。请重新发现目标；未回退。")
            }
        }
    }
}
