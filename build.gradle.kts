plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    id("org.jetbrains.intellij.platform")
}

group = "com.mcob"
version = "0.1.21"

sourceSets {
    main {
        resources.srcDirs("plugin/src/main/resources")
    }
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDirs("plugin/src/main/kotlin")
        }
    }
}

dependencies {
    intellijPlatform {
        pycharm("2026.2.0.1")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("com.jetbrains.plugins.webDeployment")
        bundledPlugin("com.intellij.mcpServer")
        bundledModule("intellij.platform.ijent.ssh")
        bundledModule("intellij.platform.remoteServers")
        bundledModule("intellij.platform.remoteServers.impl")
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.mcob.terminal-mcp-bridge"
        name = "终端 MCP 桥接"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("262")
        untilBuild.set("")
    }

    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.compilerArgs.add("-parameters")
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
