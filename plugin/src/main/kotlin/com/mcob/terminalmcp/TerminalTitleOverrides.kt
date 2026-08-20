package com.mcob.terminalmcp

import com.intellij.openapi.util.Key
import com.intellij.ui.content.Content

object TerminalTitleOverrides {
    private val key = Key.create<String>("com.mcob.terminalmcp.requestedTerminalTitle")

    fun set(content: Content, title: String) {
        content.putUserData(key, title)
        runCatching { content.javaClass.getMethod("setDisplayName", String::class.java).invoke(content, title) }
        runCatching { content.javaClass.getMethod("setTabName", String::class.java).invoke(content, title) }
    }

    fun get(content: Content): String? = content.getUserData(key)?.takeUnless { it.isBlank() }
}
