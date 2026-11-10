@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.terminalmcp

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
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
        println("Plugin classloader regression: isolated serializer/schema and explicit null passed; no bundled Kotlin runtime")
    }
}
