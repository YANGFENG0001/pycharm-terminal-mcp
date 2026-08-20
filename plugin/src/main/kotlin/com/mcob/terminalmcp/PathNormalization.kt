package com.mcob.terminalmcp

import java.nio.file.Paths

object PathNormalization {
    fun key(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return runCatching { Paths.get(raw.replace('\\', '/')).toAbsolutePath().normalize().toString() }
            .getOrDefault(raw.replace('\\', '/'))
            .replace('\\', '/')
            .trimEnd('/')
            .lowercase()
    }

    fun equivalent(left: String?, right: String?): Boolean = key(left) == key(right)

    fun containsPath(parent: String?, child: String?): Boolean {
        val p = key(parent)
        val c = key(child)
        return p.isNotEmpty() && (p == c || c.startsWith("$p/"))
    }
}
