package com.mcob.terminalmcp

object SensitiveRedactor {
    private val patterns = listOf(
        Regex("(?i)(password\\s*[:=]\\s*)([^\\s]+)"),
        Regex("(?i)(token\\s*[:=]\\s*)([A-Za-z0-9._\\-]{12,})"),
        Regex("(?i)(api[_-]?key\\s*[:=]\\s*)([A-Za-z0-9._\\-]{12,})"),
        Regex("(?i)(secret\\s*[:=]\\s*)([^\\s]+)"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("gh[pousr]_[A-Za-z0-9_]{20,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        Regex("(?i)(sudo password for [^:]+:).*"),
    )

    fun redact(text: String): String {
        var redacted = text
        for (pattern in patterns) {
            redacted = when (pattern.pattern.contains("BEGIN")) {
                true -> redacted.replace(pattern, "[REDACTED PRIVATE KEY]")
                false -> redacted.replace(pattern) { match ->
                    if (match.groupValues.size >= 3) {
                        match.groupValues[1] + "[REDACTED]"
                    }
                    else {
                        "[REDACTED]"
                    }
                }
            }
        }
        return redactPromptNeighborhood(redacted)
    }

    private fun redactPromptNeighborhood(text: String): String =
        text.lines().joinToString("\n") { line ->
            if (
                line.contains("password", ignoreCase = true) ||
                line.contains("passphrase", ignoreCase = true) ||
                line.contains("verification code", ignoreCase = true)
            ) {
                line.replaceAfter(":", " [REDACTED]")
            }
            else {
                line
            }
        }
}

