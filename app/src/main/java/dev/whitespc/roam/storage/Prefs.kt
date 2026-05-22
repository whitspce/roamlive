package dev.whitespc.roam.storage

import android.content.Context
import dev.whitespc.roam.streaming.overlay.OverlayJson
import dev.whitespc.roam.streaming.overlay.Scene
import dev.whitespc.roam.streaming.overlay.defaultScene

object Prefs {
    private const val FILE = "roam_prefs"

    private const val KEY_STREAM_URL = "stream_url"
    private const val KEY_STREAM_URL_2 = "stream_url_2"
    // Legacy split-field keys, kept for one-time migration into the single URL.
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_STREAM_KEY = "stream_key"
    private const val KEY_SERVER_URL_2 = "server_url_2"
    private const val KEY_STREAM_KEY_2 = "stream_key_2"
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
    private const val KEY_MAX_RECONNECT_MIN = "max_reconnect_min"
    private const val KEY_OVERLAY_SCENE_V1 = "overlay_scene_v1"
    private const val KEY_WEB_OVERLAY_WARNING_SEEN = "web_overlay_warning_seen"

    private const val DEFAULT_BRB_TEXT = "BE RIGHT BACK"
    private const val DEFAULT_STEALTH_PULSE_SEC = 30
    /** 0 = never give up (keep trying forever). Otherwise minutes before surrender. */
    private const val DEFAULT_MAX_RECONNECT_MIN = 5

    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 720
    private const val DEFAULT_FPS = 30
    private const val DEFAULT_BITRATE_KBPS = 2500

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Full RTMP/RTMPS URL for destination 1, key included. Auto-migrates from the
     *  legacy split server/key fields on first read if the single URL is blank. */
    fun streamUrl(context: Context): String {
        val direct = sp(context).getString(KEY_STREAM_URL, "") ?: ""
        if (direct.isNotBlank()) return direct
        val server = (sp(context).getString(KEY_SERVER_URL, "") ?: "").trim().trimEnd('/')
        val key = (sp(context).getString(KEY_STREAM_KEY, "") ?: "").trim()
        if (server.isNotBlank() && key.isNotBlank()) {
            val combined = "$server/$key"
            sp(context).edit().putString(KEY_STREAM_URL, combined).apply()
            return combined
        }
        return ""
    }

    fun setStreamUrl(context: Context, url: String) {
        sp(context).edit().putString(KEY_STREAM_URL, url).apply()
    }

    fun streamUrl2(context: Context): String {
        val direct = sp(context).getString(KEY_STREAM_URL_2, "") ?: ""
        if (direct.isNotBlank()) return direct
        val server = (sp(context).getString(KEY_SERVER_URL_2, "") ?: "").trim().trimEnd('/')
        val key = (sp(context).getString(KEY_STREAM_KEY_2, "") ?: "").trim()
        if (server.isNotBlank() && key.isNotBlank()) {
            val combined = "$server/$key"
            sp(context).edit().putString(KEY_STREAM_URL_2, combined).apply()
            return combined
        }
        return ""
    }

    fun setStreamUrl2(context: Context, url: String) {
        sp(context).edit().putString(KEY_STREAM_URL_2, url).apply()
    }

    /** All configured destination URLs in slot order, blanks dropped. */
    fun streamUrls(context: Context): List<String> =
        listOf(streamUrl(context), streamUrl2(context)).filter { it.isNotBlank() }

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

    /** Minutes the engine will keep retrying reconnect after an unexpected disconnect.
     *  0 = never give up. Default 5. */
    fun maxReconnectMinutes(context: Context): Int =
        sp(context).getInt(KEY_MAX_RECONNECT_MIN, DEFAULT_MAX_RECONNECT_MIN)

    fun setMaxReconnectMinutes(context: Context, minutes: Int) {
        sp(context).edit().putInt(KEY_MAX_RECONNECT_MIN, minutes).apply()
    }

    /** The active overlay scene. Falls back to [defaultScene] (just the locked
     *  watermark) on first install or if the saved JSON is corrupted.
     *
     *  Locked items (the watermark) are force-healed to visible — they're
     *  mandatory, and this self-corrects any scene saved with the watermark
     *  hidden before locked items became un-uncheckable. */
    fun overlayScene(context: Context): Scene {
        val json = sp(context).getString(KEY_OVERLAY_SCENE_V1, null) ?: return defaultScene()
        val scene = OverlayJson.fromJson(json) ?: return defaultScene()
        return scene.copy(
            items = scene.items.map { if (it.locked) it.copy(visible = true) else it },
        )
    }

    fun setOverlayScene(context: Context, scene: Scene) {
        sp(context).edit().putString(KEY_OVERLAY_SCENE_V1, OverlayJson.toJson(scene)).apply()
    }

    /** Whether the user has seen the one-time "web overlays cost more battery"
     *  notice. Shown once before the first web overlay is added, then suppressed. */
    fun webOverlayWarningSeen(context: Context): Boolean =
        sp(context).getBoolean(KEY_WEB_OVERLAY_WARNING_SEEN, false)

    fun setWebOverlayWarningSeen(context: Context) {
        sp(context).edit().putBoolean(KEY_WEB_OVERLAY_WARNING_SEEN, true).apply()
    }
}
