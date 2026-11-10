plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    id("org.jetbrains.intellij.platform")
}

group = "dev.terminalmcp"
version = "0.1.50"

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
        id = "dev.terminalmcp.bridge"
        name = "终端 MCP 桥接"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

tasks {
    val verifySelectedFilePaths by registering(JavaExec::class) {
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
        mainClass.set("dev.terminalmcp.SelectedFilePathsRegression")
    }
    check { dependsOn(verifySelectedFilePaths) }
    val verifySelectedUploadResult by registering(JavaExec::class) {
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
        mainClass.set("dev.terminalmcp.SelectedUploadResultRegression")
    }
    check { dependsOn(verifySelectedUploadResult) }
    val verifySavedSshTerminalRecovery by registering(JavaExec::class) {
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
        mainClass.set("dev.terminalmcp.SavedSshTerminalRecoveryRegression")
    }
    check { dependsOn(verifySavedSshTerminalRecovery) }
    val verifyUploadScope by registering(JavaExec::class) {
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
        mainClass.set("dev.terminalmcp.UploadScopeRegression")
    }
    check { dependsOn(verifyUploadScope) }
    val verifySshEndpointSelection by registering(JavaExec::class) {
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
        mainClass.set("dev.terminalmcp.SshEndpointSelectionRegression")
    }
    check { dependsOn(verifySshEndpointSelection) }
    val verifyTerminalExecution by registering(JavaExec::class) {
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
        mainClass.set("dev.terminalmcp.TerminalExecutionRegression")
    }
    check { dependsOn(verifyTerminalExecution) }
    val verifyPluginClassloader by registering(JavaExec::class) {
        dependsOn(testClasses, buildPlugin)
        classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
        mainClass.set("dev.terminalmcp.PluginClassloaderRegression")
        args(layout.buildDirectory.file("distributions/pycharm-terminal-mcp-${project.version}.zip").get().asFile.absolutePath)
    }
    check { dependsOn(verifyPluginClassloader) }
    // Regression checks use the explicit JavaExec runner, not JUnit discovery.
    test { failOnNoDiscoveredTests.set(false) }

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
