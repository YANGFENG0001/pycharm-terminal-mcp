package dev.terminalmcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy

object McpConnectionRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        val url = "http://127.0.0.1:64462/stream"
        listOf("D:/Projects/example", "D:\\项目\\a\"b", "D:\\newline\nproject").forEach { path ->
            val config = McpConnectionReport.clientConfig(url, path)
            check(config.startsWith("[mcp_servers.pycharm]\n"))
            val urlValue = config.lineSequence().single { it.startsWith("url = ") }.removePrefix("url = ")
            check(Json.parseToJsonElement(urlValue).jsonPrimitive.content == url)
            val header = config.lineSequence().single { it.startsWith("http_headers") }
                .substringAfter("\"IJ_MCP_SERVER_PROJECT_PATH\" = ").removeSuffix(" }")
            check(Json.parseToJsonElement(header).jsonPrimitive.content == path)
            check(!config.contains("AUTH_TOKEN"))
        }
        check(!McpConnectionReport.clientConfig(url, null).contains("http_headers"))
        val global = McpConnectionReport.clientConfig(url)
        check(global.contains(CurrentProjectRouting.FOLLOW_CURRENT))
        check(!global.contains("Projects/example"))
        CurrentProjectRouting.checkModal(false)
        val modal = runCatching { CurrentProjectRouting.checkModal(true) }.exceptionOrNull()
        check(modal is ExpectedMcpFailureException && modal.message.orEmpty().contains("未排队执行"))
        val air = Json.parseToJsonElement(McpConnectionReport.airConfig(url)).jsonObject
            .getValue("mcpServers").jsonObject.getValue("pycharm-terminal").jsonObject
        check(air.getValue("url").jsonPrimitive.content == url)
        check(air.getValue("headers").jsonObject.getValue(CurrentProjectRouting.HEADER).jsonPrimitive.content == CurrentProjectRouting.FOLLOW_CURRENT)
        check(CurrentProjectRouting.choose(listOf("A", "B"), "B") == "B")
        check(CurrentProjectRouting.choose(listOf("A", "B"), "A") == "A")
        check(CurrentProjectRouting.choose(listOf("A"), null) == "A")
        check(runCatching { CurrentProjectRouting.choose(emptyList<String>(), null) }.exceptionOrNull() is ExpectedMcpFailureException)
        check(runCatching { CurrentProjectRouting.choose(listOf("A", "B"), "closed") }.exceptionOrNull() is ExpectedMcpFailureException)
        check(runCatching { CurrentProjectRouting.choose(listOf("A", "B"), null) }.isFailure)
        check(CurrentProjectRouting.followsCurrent(null, CurrentProjectRouting.FOLLOW_CURRENT, null))
        check(CurrentProjectRouting.followsCurrent(null, null, CurrentProjectRouting.FOLLOW_CURRENT))
        check(CurrentProjectRouting.followsCurrent(CurrentProjectRouting.FOLLOW_CURRENT, null, null))
        check(!CurrentProjectRouting.followsCurrent(null, "D:/old", null))
        CurrentProjectRouting.checkExplicitPaths("D:/new", null, "", CurrentProjectRouting.FOLLOW_CURRENT)
        CurrentProjectRouting.checkExplicitPaths("D:/new", "D:/new/")
        if (System.getProperty("os.name").startsWith("Windows")) {
            CurrentProjectRouting.checkExplicitPaths("D:/new", "d:\\NEW\\.")
        }
        check(runCatching { CurrentProjectRouting.checkExplicitPaths("D:/new", "D:/old") }.isFailure)
        check(runCatching { CurrentProjectRouting.checkExplicitPaths(null, "D:/old") }.isFailure)
        var open = true
        fun project(name: String): Project = Proxy.newProxyInstance(Project::class.java.classLoader,
            arrayOf(Project::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "getName" -> name
                "getBasePath" -> "D:/$name"
                "isDisposed" -> !open
                "equals" -> proxy === arguments?.singleOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> name
                else -> error("Unexpected project API in routing regression: ${method.name}")
            }
        } as Project
        val a = project("A")
        val b = project("B")
        val pinned = ProjectLocator(a) { listOf(a, b) }
        check(pinned.resolve(null) === a)
        check(pinned.resolve("D:/A/.") === a)
        check(pinned.resolveMany(null) == listOf(a))
        check(runCatching { pinned.resolve("D:/B") }.exceptionOrNull() is ExpectedMcpFailureException)
        val nextCall = ProjectLocator(b) { listOf(a, b) }
        check(nextCall.resolve(null) === b)
        check(pinned.resolve(null) === a) // Already-running A call is not retargeted by B's new call.
        check(runCatching { ProjectLocator(a) { listOf(b) }.resolve(null) }.isFailure)
        open = false
        check(runCatching { pinned.resolve(null) }.isFailure)
        val missing = McpConnectionReport.summary("PY-263.7307.206", "0.1.51", false, null, 0, emptyList())
        check(missing.contains("尚未监听"))
        check(missing.contains("无，请先打开项目"))
        check(missing.contains("声明不等于协议调用成功"))
        check(McpConnectionReport.summary("263", "0.1.51", true, url, -1, listOf("D:/open"))
            .contains("加载异常，请检查 idea.log"))
        check(McpConnectionReport.summary("263", "0.1.52", true, url, 26, listOf("D:/open"),
            "dev.terminalmcp.CurrentProjectResolver").contains("已加载（需全局连接使用跟随标记）"))
        println("MCP connection routing: modal rejection, focus switching, closed/ambiguous rejection, explicit mismatch, JSON/TOML and config escaping passed")
    }
}
