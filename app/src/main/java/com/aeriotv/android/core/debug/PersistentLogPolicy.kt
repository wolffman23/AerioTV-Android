package com.aeriotv.android.core.debug

/** File-export boundary for own-process logcat lines. */
internal object PersistentLogPolicy {
    private val noisyRenderingLine = Regex(
        pattern =
            """^(?:\d{2}-\d{2}|\d{4}-\d{2}-\d{2})\s+""" +
                """\d{2}:\d{2}:\d{2}(?:\.\d+)?\s+[VDI]/(?:View|SurfaceView)\s*\(""",
    )

    fun safeSnapshot(snapshot: String): String =
        snapshot.lineSequence()
            .mapNotNull(::safeLine)
            .joinToString("\n")

    fun safeLine(line: String): String? =
        if (noisyRenderingLine.containsMatchIn(line)) {
            null
        } else {
            LogSanitizer.redact(line)
        }
}
