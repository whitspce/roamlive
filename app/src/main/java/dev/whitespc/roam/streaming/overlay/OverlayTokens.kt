package dev.whitespc.roam.streaming.overlay

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live text tokens for Text overlays. A token written into the overlay text is
 * replaced with a current value each render. The renderer ticks once a second to
 * keep the broadcast current.
 *
 * Two tiers, on purpose:
 *
 * **Freebies (chips in the editor)** — `{time}`, `{date}`, `{day}`, `{battery}`,
 * `{bitrate}`, `{stream_time}`, `{compass}`. No permissions; near-zero cost.
 * Compass uses a sensor that's lazy-subscribed only when a visible overlay
 * actually uses `{compass}`.
 *
 * **GPS-backed (no chips, documented on the website FAQ only)** — `{speed}`,
 * `{speed_mph}`, `{altitude}`, `{lat}`, `{lng}`, `{heading}`, `{city}`. Need the
 * location permission and add real battery + heat cost while streaming.
 * Lazy-subscribed only when a visible overlay actually uses one of them. Hidden
 * from the chips on purpose: power users who care opt in deliberately by typing
 * the token from the FAQ rather than tapping a button without understanding the
 * cost.
 *
 * Values come from [TokenSnapshot], built by [TokenSource]. Anything without a
 * current value (GPS before first fix, bitrate while not streaming, etc.) shows
 * as a single en-dash placeholder.
 */
object OverlayTokens {

    // Freebie tokens.
    const val TOKEN_TIME = "{time}"
    const val TOKEN_DATE = "{date}"
    const val TOKEN_DAY = "{day}"
    const val TOKEN_BATTERY = "{battery}"
    const val TOKEN_BITRATE = "{bitrate}"
    const val TOKEN_STREAM_TIME = "{stream_time}"
    const val TOKEN_COMPASS = "{compass}"

    // GPS-backed, FAQ-only (no chips).
    const val TOKEN_SPEED = "{speed}"
    const val TOKEN_SPEED_MPH = "{speed_mph}"
    const val TOKEN_ALTITUDE = "{altitude}"
    const val TOKEN_LAT = "{lat}"
    const val TOKEN_LNG = "{lng}"
    const val TOKEN_HEADING = "{heading}"
    const val TOKEN_CITY = "{city}"

    /** Editor chips — freebies only. GPS tokens are documented on the website. */
    val tokens: List<Pair<String, String>> = listOf(
        TOKEN_TIME to "Time",
        TOKEN_DATE to "Date",
        TOKEN_DAY to "Day",
        TOKEN_BATTERY to "Battery",
        TOKEN_BITRATE to "Bitrate",
        TOKEN_STREAM_TIME to "Stream time",
        TOKEN_COMPASS to "Compass",
    )

    private val GPS_TOKENS = listOf(
        TOKEN_SPEED, TOKEN_SPEED_MPH, TOKEN_ALTITUDE,
        TOKEN_LAT, TOKEN_LNG, TOKEN_HEADING, TOKEN_CITY,
    )

    private val ALL_TOKENS = listOf(
        TOKEN_TIME, TOKEN_DATE, TOKEN_DAY,
        TOKEN_BATTERY, TOKEN_BITRATE, TOKEN_STREAM_TIME,
        TOKEN_COMPASS,
    ) + GPS_TOKENS

    fun hasToken(text: String): Boolean = ALL_TOKENS.any { text.contains(it) }

    /** True if [text] uses any GPS-backed token — drives lazy GPS subscription
     *  and the editor's permission/dialog flow. */
    fun hasGpsToken(text: String): Boolean = GPS_TOKENS.any { text.contains(it) }

    /** True if [text] uses the compass token — drives lazy sensor subscription. */
    fun hasCompassToken(text: String): Boolean = text.contains(TOKEN_COMPASS)

    private const val PLACEHOLDER = "—"

    /**
     * Replace every token in [text] with the current value from [snapshot].
     * Tokens with no current value (e.g. `{speed}` before a GPS fix, or
     * `{bitrate}` while not streaming) render as the en-dash placeholder so the
     * overlay stays visually stable rather than flickering empty.
     */
    fun resolve(text: String, snapshot: TokenSnapshot = TokenSnapshot()): String {
        if (!hasToken(text)) return text
        val now = Date()
        return text
            .replace(TOKEN_TIME, DateFormat.getTimeInstance(DateFormat.SHORT).format(now))
            .replace(TOKEN_DATE, DateFormat.getDateInstance(DateFormat.MEDIUM).format(now))
            .replace(TOKEN_DAY, SimpleDateFormat("EEEE", Locale.getDefault()).format(now))
            .replace(TOKEN_BATTERY, snapshot.batteryPercent?.let { "$it%" } ?: PLACEHOLDER)
            .replace(TOKEN_BITRATE, snapshot.bitrateKbps?.let { "$it kbps" } ?: PLACEHOLDER)
            .replace(TOKEN_STREAM_TIME, snapshot.streamUptimeSec?.let { formatUptime(it) } ?: PLACEHOLDER)
            .replace(TOKEN_COMPASS, snapshot.compassDegrees?.let { "${it.toInt()}°" } ?: PLACEHOLDER)
            .replace(TOKEN_SPEED, snapshot.gpsSpeedMs?.let { "${(it * 3.6f).toInt()} km/h" } ?: PLACEHOLDER)
            .replace(TOKEN_SPEED_MPH, snapshot.gpsSpeedMs?.let { "${(it * 2.237f).toInt()} mph" } ?: PLACEHOLDER)
            .replace(TOKEN_ALTITUDE, snapshot.gpsAltitudeM?.let { "${it.toInt()} m" } ?: PLACEHOLDER)
            .replace(TOKEN_LAT, snapshot.gpsLat?.let { String.format(Locale.US, "%.5f", it) } ?: PLACEHOLDER)
            .replace(TOKEN_LNG, snapshot.gpsLng?.let { String.format(Locale.US, "%.5f", it) } ?: PLACEHOLDER)
            .replace(TOKEN_HEADING, snapshot.gpsHeadingDeg?.let { "${it.toInt()}°" } ?: PLACEHOLDER)
            .replace(TOKEN_CITY, snapshot.gpsCity ?: PLACEHOLDER)
    }

    private fun formatUptime(totalSec: Int): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/**
 * The set of live values [OverlayTokens.resolve] reads from. Any field left
 * null renders as the en-dash placeholder for its token, so unavailable values
 * fail gracefully rather than blanking the overlay. Built by [TokenSource];
 * passed by the renderer each tick.
 */
data class TokenSnapshot(
    val batteryPercent: Int? = null,
    val bitrateKbps: Long? = null,
    val streamUptimeSec: Int? = null,
    val compassDegrees: Float? = null,
    val gpsLat: Double? = null,
    val gpsLng: Double? = null,
    /** Raw speed from Location, metres/second. resolve() converts to km/h or mph. */
    val gpsSpeedMs: Float? = null,
    val gpsAltitudeM: Float? = null,
    val gpsHeadingDeg: Float? = null,
    val gpsCity: String? = null,
)
