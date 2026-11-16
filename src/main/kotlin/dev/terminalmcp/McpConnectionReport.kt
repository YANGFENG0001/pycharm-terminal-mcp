package dev.terminalmcp

import kotlinx.serialization.json.JsonPrimitive

object McpConnectionReport {
    private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
    fun clientConfig(url: String, projectPath: String? = CurrentProjectRouting.FOLLOW_CURRENT): String {
        fun quoted(value: String): String = JsonPrimitive(value).toString()
        return buildString {
            appendLine("[mcp_servers.pycharm]")
            appendLine("url = ${quoted(url)}")
            appendLine("startup_timeout_sec = 60")
            if (projectPath != null) {
                appendLine("http_headers = { \"IJ_MCP_SERVER_PROJECT_PATH\" = ${quoted(projectPath)} }")
            }
        }
    }

    fun airConfig(url: String): String = kotlinx.serialization.json.buildJsonObject {
        put("mcpServers", kotlinx.serialization.json.buildJsonObject {
            put("pycharm-terminal", kotlinx.serialization.json.buildJsonObject {
                put("url", JsonPrimitive(url))
                put("headers", kotlinx.serialization.json.buildJsonObject {
                    put(CurrentProjectRouting.HEADER, JsonPrimitive(CurrentProjectRouting.FOLLOW_CURRENT))
                })
            })
        })
    }.let { prettyJson.encodeToString(it) }

    @JvmOverloads
    fun summary(build: String, version: String, enabled: Boolean, url: String?,
                registeredTools: Int, projectPaths: List<String>, routingImplementation: String? = null): String = buildString {
        appendLine("IDE 构建：$build")
        appendLine("桥接版本：$version")
        appendLine("内置 MCP 开关：${if (enabled) "已启用" else "未启用"}")
        appendLine("MCP 监听地址：${url ?: "尚未监听"}")
        appendLine("桥接工具声明：${if (registeredTools < 0) "加载异常，请检查 idea.log" else "$registeredTools / 26"}（声明不等于协议调用成功）")
        appendLine("全局跟随路由：${when (routingImplementation) {
            "dev.terminalmcp.CurrentProjectResolver" -> "已加载（需全局连接使用跟随标记）"
            null -> "未检查"
            else -> "未加载本插件解析器：$routingImplementation"
        }}")
        appendLine("IDE 已打开项目：")
        if (projectPaths.isEmpty()) appendLine("（无，请先打开项目）")
        projectPaths.forEach { appendLine(it) }
        appendLine()
        appendLine("全局跟随：复制连接配置并添加为全局 MCP；会话目录无需在 IDE 打开。")
        appendLine("每次调用使用最近激活的项目；已开始的操作不随窗口切换改变目标。")
        appendLine("多个项目且无法确定当前窗口时拒绝；显式项目不匹配时不回退。")
        appendLine("ACP 自动注入仍受宿主限制；仅安装插件不会自动给所有会话添加全局连接。")
        appendLine("固定项目连接仍可用；全局跟随连接请勿附带旧项目 projectPath。")
        appendLine("HTTP 502：检查客户端代理和实际地址，localhost/127.0.0.1 应不经过代理。")
        appendLine("工具可发现但调用失败：保留原始错误，检查项目路由、工具过滤及 idea.log。")
        append("本检查不会执行命令、读取凭据、更改权限或修改 Deployment 配置。")
    }
}
