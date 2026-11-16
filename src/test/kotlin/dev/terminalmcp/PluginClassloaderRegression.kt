@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.terminalmcp

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.kotlinFunction
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipFile

object PluginClassloaderRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        val distribution = Paths.get(args.single())
        val jars = mutableListOf<java.nio.file.Path>()
        try {
            ZipFile(distribution.toFile()).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".jar") }.forEach { entry ->
                    check(!entry.name.contains("kotlin-stdlib") && !entry.name.contains("kotlinx-serialization")) {
                        "IDE-provided runtime must not be bundled: " + entry.name
                    }
                    val file = Files.createTempFile("mcp-classloader-", ".jar")
                    jars.add(file)
                    zip.getInputStream(entry).use { input -> Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                    ZipFile(file.toFile()).use { nested ->
                        check(nested.getEntry("kotlin/jvm/internal/IntCompanionObject.class") == null)
                        nested.getEntry("META-INF/plugin.xml")?.let { descriptor ->
                            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                            val document = nested.getInputStream(descriptor).use { factory.newDocumentBuilder().parse(it) }
                            check(document.getElementsByTagName("id").item(0).textContent == "com.mcob.terminal-mcp-bridge")
                            val compatibility = document.getElementsByTagName("idea-version").item(0) as org.w3c.dom.Element
                            check(compatibility.getAttribute("since-build") == "263.7307.206")
                            check(compatibility.getAttribute("until-build") == "263.*")
                        }
                    }
                }
            }
            val parent = PluginClassloaderRegression::class.java.classLoader
            // Child-first Kotlin emulates the collision that a flat test classpath missed.
            val loader = object : URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), parent) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                    if (name.startsWith("dev.terminalmcp.") || name.startsWith("kotlin.")) {
                        val type = findLoadedClass(name) ?: try { findClass(name) } catch (_: ClassNotFoundException) { super.loadClass(name, false) }
                        if (resolve) resolveClass(type)
                        type
                    } else super.loadClass(name, resolve)
                }
            }
            loader.use {
                val connectionUi = loader.loadClass("dev.terminalmcp.McpConnectionUi")
                val controls = connectionUi.getMethod("controls").invoke(connectionUi.getField("INSTANCE").get(null)) as javax.swing.JPanel
                val labels = controls.components.map { (it as javax.swing.JButton).text }
                check(labels == listOf("复制终端 MCP 连接配置", "检查终端 MCP 连接"))
                check((controls.layout as java.awt.GridLayout).columns == 1)
                val resolverType = loader.loadClass("dev.terminalmcp.CurrentProjectResolver")
                val resolver = resolverType.getConstructor().newInstance()
                check(parent.loadClass("com.intellij.mcpserver.impl.McpSessionProjectResolver").isInstance(resolver))
                resolverType.declaredMethods.single { it.name == "resolveSessionProject" }.parameterTypes
                val toolsetType = loader.loadClass("dev.terminalmcp.TerminalMcpToolset")
                val toolset = toolsetType.getConstructor().newInstance()
                val methods = toolsetType.declaredMethods
                val tools = methods.filter { method -> method.annotations.any { it.annotationClass.java.name == "com.intellij.mcpserver.annotations.McpTool" } }
                check(tools.size == 26) { "Expected 26 bridge tools, found ${tools.size}" }
                check(toolsetType.getMethod("alwaysIncluded").invoke(toolset) == true)
                check(toolsetType.getMethod("isExperimental").invoke(toolset) == false)
                // Resolve all public signatures against this IDE SDK, not only serializers.
                tools.forEach { method ->
                    method.parameterTypes; method.returnType; method.genericReturnType
                    val function = checkNotNull(method.kotlinFunction)
                    serializer(function.returnType).descriptor
                    function.valueParameters.forEach { serializer(it.type).descriptor }
                }
                check(loader.loadClass("kotlin.jvm.internal.IntCompanionObject") === parent.loadClass("kotlin.jvm.internal.IntCompanionObject"))
                val resultType = loader.loadClass("dev.terminalmcp.TerminalExecutionResult")
                check(resultType.classLoader === loader)
                val serializerType = loader.loadClass("dev.terminalmcp.TerminalExecutionResultSerializer")
                @Suppress("UNCHECKED_CAST")
                val serializer = serializerType.getField("INSTANCE").get(null) as KSerializer<Any>
                val descriptor = serializer.descriptor
                check(descriptor.getElementIndex("exit_code") >= 0)
                val constructor = resultType.constructors.single { it.parameterCount == 8 }
                val pending = constructor.newInstance("isolated", false, false, true, null, "", "timeout", null)
                val codec = Json { explicitNulls = false; encodeDefaults = false }
                val encoded = codec.encodeToString(serializer, pending)
                check(Json.parseToJsonElement(encoded).jsonObject.getValue("exit_code") == JsonNull) { encoded }
                check(codec.decodeFromString(serializer, encoded).javaClass === resultType)
                val uploadType = loader.loadClass("dev.terminalmcp.SelectedFilesUploadResult")
                val companion = uploadType.getField("Companion").get(null)
                @Suppress("UNCHECKED_CAST")
                val uploadSerializer = companion.javaClass.getMethod("serializer").invoke(companion) as KSerializer<Any>
                val upload = uploadType.constructors.single { it.parameterCount == 6 }
                    .newInstance(true, "server", 0, emptyList<Any>(), emptyList<String>(), "done")
                val uploadJson = Json.parseToJsonElement(codec.encodeToString(uploadSerializer, upload)).jsonObject
                check(uploadJson.getValue("files") == JsonArray(emptyList()))
                check(uploadJson.getValue("skippedPaths") == JsonArray(emptyList()))
                val recoveryType = loader.loadClass("dev.terminalmcp.SavedSshTerminalResult")
                val recoveryCompanion = recoveryType.getField("Companion").get(null)
                @Suppress("UNCHECKED_CAST")
                val recoverySerializer = recoveryCompanion.javaClass.getMethod("serializer").invoke(recoveryCompanion) as KSerializer<Any>
                check(recoverySerializer.descriptor.getElementIndex("ready") >= 0)
                check(recoverySerializer.descriptor.getElementIndex("terminal") >= 0)
            }
        } finally {
            jars.forEach { Files.deleteIfExists(it) }
        }
        println("Plugin classloader regression: Chinese connection controls, 26 tool signatures/serializers, 263 descriptor, explicit null and runtime isolation passed")
    }
}
