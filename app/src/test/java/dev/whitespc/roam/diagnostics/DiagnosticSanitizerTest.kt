package dev.whitespc.roam.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun `redacts supported URLs and credentials`() {
        val input =
            "connect srt://example.test/live/key?passphrase=topsecret " +
                "password=guessme Authorization: Bearer abc.def token='value'"

        val safe = DiagnosticSanitizer.sanitize(input)

        assertEquals(
            "connect [redacted srt URL] password=[redacted] " +
                "Authorization: [redacted] token=[redacted]",
            safe,
        )
        listOf("/live/key", "topsecret", "guessme", "abc.def", "value").forEach {
            assertFalse(safe.contains(it))
        }
    }

    @Test
    fun `sanitizing an exported line is idempotent`() {
        val once = DiagnosticSanitizer.sanitize("password=hunter2 Bearer abc123")

        assertEquals(once, DiagnosticSanitizer.sanitize(once))
    }

    @Test
    fun `normalizes controls to prevent forged log lines`() {
        assertEquals(
            "closed reason fake E/Other: forged",
            DiagnosticSanitizer.sanitize("closed\nreason\rfake\u0000E/Other: forged"),
        )
    }

    @Test
    fun `throwable output keeps stack location but drops all exception messages`() {
        val cause = IllegalArgumentException("rtmp://host/app/private-key")
        val error = IllegalStateException("password=visible", cause)

        val safe = DiagnosticSanitizer.format("start failed", error)

        assertTrue(safe.contains("start failed | IllegalStateException at"))
        assertTrue(safe.contains("caused by IllegalArgumentException at"))
        assertFalse(safe.contains("visible"))
        assertFalse(safe.contains("private-key"))
    }

    @Test
    fun `bounds oversized messages`() {
        val safe = DiagnosticSanitizer.sanitize("x".repeat(10_000))

        assertEquals(DiagnosticSanitizer.MAX_ENTRY_CHARS, safe.length)
        assertTrue(safe.endsWith(" [truncated]"))
    }

    @Test
    fun `sanitizes and bounds tags`() {
        val safe = DiagnosticSanitizer.sanitizeTag("tag\nforged" + "x".repeat(100))

        assertEquals(64, safe.length)
        assertFalse(safe.contains('\n'))
    }
}
