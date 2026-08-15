package dev.whitespc.roam.diagnostics

/** Keeps credentials and attacker-controlled formatting out of diagnostic logs. */
internal object DiagnosticSanitizer {
    internal const val MAX_ENTRY_CHARS = 4_096
    private const val MAX_TAG_CHARS = 64
    private const val MAX_THROWABLE_DEPTH = 3
    private const val MAX_FRAMES_PER_THROWABLE = 12

    private val url = Regex(
        pattern = """(?i)\b(https?|wss?|rtmps?|rtmpts?|srt)://[^\s<>\"']+""",
    )
    private val bearerCredential = Regex(
        pattern = """(?i)\b(bearer|basic)\s+[a-z0-9._~+/=-]+""",
    )
    private val sensitiveAssignment = Regex(
        pattern =
            """(?i)([\"']?(?:passphrase|password|passwd|stream[ _-]*key|api[ _-]*key|access[ _-]*token|refresh[ _-]*token|token|secret|authorization|auth|signature|challenge|salt)[\"']?\s*[:=]\s*)(?!\[redacted])(?:(?:bearer|basic)\s+[a-z0-9._~+/=-]+|\"(?:\\.|[^\"])*\"|'(?:\\.|[^'])*'|[^\s,;&}\]]+)""",
    )
    private val controls = Regex("""[\p{Cc}\p{Cf}]+""")
    private val whitespace = Regex("""\s+""")

    fun sanitize(text: String): String {
        var safe = url.replace(text) { match ->
            "[redacted ${match.groupValues[1].lowercase()} URL]"
        }
        safe = sensitiveAssignment.replace(safe) { match ->
            "${match.groupValues[1]}[redacted]"
        }
        safe = bearerCredential.replace(safe) { match ->
            "${match.groupValues[1]} [redacted]"
        }
        safe = controls.replace(safe, " ")
        safe = whitespace.replace(safe, " ").trim()
        if (safe.length > MAX_ENTRY_CHARS) {
            safe = safe.take(MAX_ENTRY_CHARS - 12).trimEnd() + " [truncated]"
        }
        return safe
    }

    fun sanitizeTag(tag: String): String =
        controls.replace(tag, " ").replace(whitespace, " ").trim()
            .ifEmpty { "Roam" }
            .take(MAX_TAG_CHARS)

    fun format(message: String, throwable: Throwable?): String {
        if (throwable == null) return sanitize(message)

        val trace = buildString {
            if (message.isNotBlank()) {
                append(message)
                append(" | ")
            }
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < MAX_THROWABLE_DEPTH) {
                if (depth > 0) append(" caused by ")
                append(current::class.java.simpleName.ifEmpty { "Throwable" })
                current.stackTrace.take(MAX_FRAMES_PER_THROWABLE).forEach { frame ->
                    append(" at ")
                    append(frame.className)
                    append('.')
                    append(frame.methodName)
                    append('(')
                    append(frame.fileName ?: "Unknown Source")
                    if (frame.lineNumber >= 0) {
                        append(':')
                        append(frame.lineNumber)
                    }
                    append(')')
                }
                current = current.cause
                depth += 1
            }
        }
        return sanitize(trace)
    }
}
