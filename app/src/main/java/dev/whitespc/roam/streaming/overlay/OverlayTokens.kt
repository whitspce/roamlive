package dev.whitespc.roam.streaming.overlay

import java.text.DateFormat
import java.util.Date

/**
 * Live text tokens for Text overlays. A token written into the overlay text is
 * replaced with a current value when rendered.
 *
 * v1 ships {time} and {date}, both read from the phone clock — no permissions,
 * no network, nearly free. The renderer re-resolves tokens on a timer so the
 * broadcast stays current. Later tokens (GPS, heading, battery) follow the same
 * pattern: data that physically lives on the phone, which a web overlay can't
 * reach.
 */
object OverlayTokens {

    private const val TOKEN_TIME = "{time}"
    private const val TOKEN_DATE = "{date}"

    /** Supported tokens as (token, human label) — drives the editor's chips. */
    val tokens: List<Pair<String, String>> = listOf(
        TOKEN_TIME to "Time",
        TOKEN_DATE to "Date",
    )

    fun hasToken(text: String): Boolean =
        text.contains(TOKEN_TIME) || text.contains(TOKEN_DATE)

    /**
     * Replace every token in [text] with its current value, using the device's
     * locale formats. Text with no tokens is returned unchanged.
     */
    fun resolve(text: String): String {
        if (!hasToken(text)) return text
        val now = Date()
        return text
            .replace(TOKEN_TIME, DateFormat.getTimeInstance(DateFormat.SHORT).format(now))
            .replace(TOKEN_DATE, DateFormat.getDateInstance(DateFormat.MEDIUM).format(now))
    }
}
