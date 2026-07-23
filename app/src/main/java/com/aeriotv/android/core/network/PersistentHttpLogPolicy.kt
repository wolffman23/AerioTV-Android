package com.aeriotv.android.core.network

/** Fail-closed allowlist for Ktor diagnostics that may reach shareable logs. */
internal object PersistentHttpLogPolicy {
    private val methodLine = Regex("""^METHOD:\s+(GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS)$""")
    private val responseLine = Regex("""^RESPONSE:\s+([1-5][0-9]{2})(?:\s+.*)?$""")

    fun safeMessage(message: String): String? {
        val safeLines = message.lineSequence().mapNotNull { rawLine ->
            val line = rawLine.trim()
            methodLine.matchEntire(line)?.let { match ->
                return@mapNotNull "HTTP method=${match.groupValues[1]}"
            }
            responseLine.matchEntire(line)?.let { match ->
                return@mapNotNull "HTTP status=${match.groupValues[1]}"
            }
            null
        }.toList()
        return safeLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
