package com.mcob.terminalmcp

object DangerousCommandDetector {
    private val rules = listOf(
        Rule("recursive forced delete", Regex("""(?i)\brm\s+(-[a-z]*[rf][a-z]*|-[a-z]*r[a-z]*\s+-[a-z]*f[a-z]*)\b""")),
        Rule("PowerShell recursive forced delete", Regex("""(?i)\bRemove-Item\b[\s\S]*\b-Recurse\b[\s\S]*\b-Force\b""")),
        Rule("disk format", Regex("""(?i)\b(mkfs|format|diskpart)\b""")),
        Rule("raw disk write", Regex("""(?i)\bdd\s+.*\bof\s*=\s*/dev/""")),
        Rule("shutdown or reboot", Regex("""(?i)\b(shutdown|reboot|halt|poweroff)\b""")),
        Rule("database destructive statement", Regex("""(?i)\b(drop\s+database|drop\s+schema|truncate\s+table|drop\s+table)\b""")),
        Rule("production destructive operation", Regex("""(?i)\b(prod|production)\b[\s\S]{0,80}\b(delete|drop|truncate|destroy|reset)\b""")),
        Rule("Kubernetes destructive operation", Regex("""(?i)\bkubectl\s+delete\b""")),
        Rule("Terraform destroy", Regex("""(?i)\bterraform\s+destroy\b""")),
        Rule("Git history rewrite", Regex("""(?i)\bgit\s+(reset\s+--hard|push\s+--force|clean\s+-[a-z]*f)""")),
    )

    fun findReason(command: String): String? =
        rules.firstOrNull { it.pattern.containsMatchIn(command) }?.name

    private data class Rule(val name: String, val pattern: Regex)
}

