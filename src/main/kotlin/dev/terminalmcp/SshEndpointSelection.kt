package dev.terminalmcp

internal object SshEndpointSelection {
    fun matches(selector: String, info: SshEndpointDescriptor): Boolean {
        val requested = selector.trim()
        if (requested.isEmpty()) return false
        if (info.id.equals(requested, ignoreCase = true) || info.id.startsWith(requested, ignoreCase = true)) return true
        val address = info.host + (info.port?.let { ":" + it } ?: "")
        val userAddress = info.user?.let { it + "@" + address }.orEmpty()
        if (requested.contains(':') || requested.contains('@')) {
            return listOf(info.name, address, userAddress).any { it.equals(requested, ignoreCase = true) }
        }
        return listOf(info.name, info.user.orEmpty(), info.host, address, userAddress)
            .any { it.contains(requested, ignoreCase = true) }
    }
}
