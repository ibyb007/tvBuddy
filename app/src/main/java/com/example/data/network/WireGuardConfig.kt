package com.example.data.network

/**
 * Pure text helpers for WireGuard `.conf` files (no Android / wireguard-android dependency, so they are unit-testable).
 * The full, authoritative parse is done by the wireguard-android library in [VpnManager].
 */
object WireGuardConfig {

    const val MAX_BYTES = 64 * 1024

    // wg-quick only keys: shell hooks and routing-table tweaks that mean nothing inside an Android VpnService
    private val IGNORED_INTERFACE_KEYS = setOf("preup", "postup", "predown", "postdown", "saveconfig", "table", "fwmark")

    private fun stripComment(line: String) = line.substringBefore('#').trim()

    private fun sections(text: String): List<Pair<String, MutableMap<String, String>>> {
        val out = ArrayList<Pair<String, MutableMap<String, String>>>()
        for (raw in text.lines()) {
            val line = stripComment(raw)
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                out.add(line.substring(1, line.length - 1).trim().lowercase() to HashMap())
            } else if (line.contains('=') && out.isNotEmpty()) {
                out.last().second[line.substringBefore('=').trim().lowercase()] = line.substringAfter('=').trim()
            }
        }
        return out
    }

    /** Quick sanity check with a readable message, or null when the text looks like a usable client config. */
    fun precheck(text: String): String? {
        if (text.isBlank()) return "The config is empty"
        if (text.length > MAX_BYTES) return "The config is too large"
        val secs = sections(text)
        val interfaces = secs.filter { it.first == "interface" }
        if (interfaces.size != 1) return "Not a WireGuard config (expected one [Interface] section)"
        if (interfaces[0].second["privatekey"].isNullOrBlank()) return "The config has no PrivateKey"
        val peers = secs.filter { it.first == "peer" }
        if (peers.isEmpty()) return "The config has no [Peer] section"
        if (peers.any { it.second["publickey"].isNullOrBlank() }) return "A [Peer] has no PublicKey"
        if (peers.none { !it.second["endpoint"].isNullOrBlank() }) return "The config has no peer Endpoint"
        return null
    }

    /** First peer endpoint as written ("host:port"), or null. */
    fun endpoint(text: String): String? =
        sections(text).firstOrNull { it.first == "peer" && !it.second["endpoint"].isNullOrBlank() }?.second?.get("endpoint")

    fun endpointHost(endpoint: String?): String? {
        val e = endpoint?.trim() ?: return null
        if (e.isEmpty()) return null
        return if (e.startsWith("[")) e.substringAfter('[').substringBefore(']') else e.substringBeforeLast(':', e)
    }

    /**
     * Makes the config tunnel only this app: drops any Included/ExcludedApplications the file had (the library
     * refuses to mix them), adds `IncludedApplications = <package>`, and removes wg-quick-only keys.
     */
    fun forApp(text: String, packageName: String): String {
        val out = ArrayList<String>()
        var inInterface = false
        for (raw in text.lines()) {
            val line = stripComment(raw)
            if (line.startsWith("[")) {
                inInterface = line.equals("[Interface]", ignoreCase = true)
                out.add(raw)
                if (inInterface) out.add("IncludedApplications = $packageName")
                continue
            }
            if (inInterface) {
                val key = line.substringBefore('=').trim().lowercase()
                if (key == "includedapplications" || key == "excludedapplications" || key in IGNORED_INTERFACE_KEYS) continue
            }
            out.add(raw)
        }
        return out.joinToString("\n")
    }
}
