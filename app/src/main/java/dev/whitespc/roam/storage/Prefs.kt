package dev.whitespc.roam.storage

import android.content.Context

object Prefs {
    private const val FILE = "roam_prefs"

    private const val KEY_STREAM_URL = "stream_url"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_STREAM_KEY = "stream_key"
    private const val KEY_VIDEO_WIDTH = "video_width"
    private const val KEY_VIDEO_HEIGHT = "video_height"
    private const val KEY_VIDEO_FPS = "video_fps"
    private const val KEY_VIDEO_BITRATE_KBPS = "video_bitrate_kbps"
    private const val KEY_CHAT_ENABLED = "chat_enabled"
    private const val KEY_KICK_CHANNEL = "kick_channel"
    private const val KEY_TWITCH_CHANNEL = "twitch_channel"
    private const val KEY_BRB_TEXT = "brb_text"
    private const val KEY_STEALTH_DOT = "stealth_dot"
    private const val KEY_STEALTH_HAPTIC = "stealth_haptic"
    private const val KEY_STEALTH_PULSE_SEC = "stealth_pulse_sec"

    private const val DEFAULT_BRB_TEXT = "BE RIGHT BACK"
    private const val DEFAULT_STEALTH_PULSE_SEC = 30

    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 720
    private const val DEFAULT_FPS = 30
    private const val DEFAULT_BITRATE_KBPS = 2500

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String =
        sp(context).getString(KEY_SERVER_URL, "") ?: ""

    fun setServerUrl(context: Context, url: String) {
        sp(context).edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun streamKey(context: Context): String =
        sp(context).getString(KEY_STREAM_KEY, "") ?: ""

    fun setStreamKey(context: Context, key: String) {
        sp(context).edit().putString(KEY_STREAM_KEY, key).apply()
    }

    /** Combined ingest URL the encoder consumes. Falls back to the legacy single-field
     *  value if the split fields are empty. */
    fun streamUrl(context: Context): String {
        val server = serverUrl(context).trim().trimEnd('/')
        val key = streamKey(context).trim()
        if (server.isNotBlank() && key.isNotBlank()) return "$server/$key"
        return sp(context).getString(KEY_STREAM_URL, "") ?: ""
    }

    fun videoWidth(context: Context): Int =
        sp(context).getInt(KEY_VIDEO_WIDTH, DEFAULT_WIDTH)

    fun videoHeight(context: Context): Int =
        sp(context).getInt(KEY_VIDEO_HEIGHT, DEFAULT_HEIGHT)

    fun setResolution(context: Context, width: Int, height: Int) {
        sp(context).edit()
            .putInt(KEY_VIDEO_WIDTH, width)
            .putInt(KEY_VIDEO_HEIGHT, height)
            .apply()
    }

    fun videoFps(context: Context): Int =
        sp(context).getInt(KEY_VIDEO_FPS, DEFAULT_FPS)

    fun setVideoFps(context: Context, fps: Int) {
        sp(context).edit().putInt(KEY_VIDEO_FPS, fps).apply()
    }

    fun videoBitrateKbps(context: Context): Int =
        sp(context).getInt(KEY_VIDEO_BITRATE_KBPS, DEFAULT_BITRATE_KBPS)

    fun setVideoBitrateKbps(context: Context, kbps: Int) {
        sp(context).edit().putInt(KEY_VIDEO_BITRATE_KBPS, kbps).apply()
    }

    fun chatEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_CHAT_ENABLED, false)

    fun setChatEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_CHAT_ENABLED, enabled).apply()
    }

    fun kickChannel(context: Context): String =
        sp(context).getString(KEY_KICK_CHANNEL, "") ?: ""

    fun setKickChannel(context: Context, channel: String) {
        sp(context).edit().putString(KEY_KICK_CHANNEL, channel).apply()
    }

    fun twitchChannel(context: Context): String =
        sp(context).getString(KEY_TWITCH_CHANNEL, "") ?: ""

    fun setTwitchChannel(context: Context, channel: String) {
        sp(context).edit().putString(KEY_TWITCH_CHANNEL, channel).apply()
    }

    fun brbText(context: Context): String =
        sp(context).getString(KEY_BRB_TEXT, DEFAULT_BRB_TEXT) ?: DEFAULT_BRB_TEXT

    fun setBrbText(context: Context, text: String) {
        sp(context).edit().putString(KEY_BRB_TEXT, text).apply()
    }

    fun stealthDot(context: Context): Boolean =
        sp(context).getBoolean(KEY_STEALTH_DOT, false)

    fun setStealthDot(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_STEALTH_DOT, enabled).apply()
    }

    fun stealthHaptic(context: Context): Boolean =
        sp(context).getBoolean(KEY_STEALTH_HAPTIC, true)

    fun setStealthHaptic(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_STEALTH_HAPTIC, enabled).apply()
    }

    fun stealthPulseSeconds(context: Context): Int =
        sp(context).getInt(KEY_STEALTH_PULSE_SEC, DEFAULT_STEALTH_PULSE_SEC)

    fun setStealthPulseSeconds(context: Context, seconds: Int) {
        sp(context).edit().putInt(KEY_STEALTH_PULSE_SEC, seconds).apply()
    }
}
