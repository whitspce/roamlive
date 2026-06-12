package dev.whitespc.roam.storage

import android.content.Context
import dev.whitespc.roam.streaming.overlay.OverlayJson
import dev.whitespc.roam.streaming.overlay.Scene
import dev.whitespc.roam.streaming.overlay.defaultScene

object Prefs {
    private const val FILE = "roam_prefs"

    private const val KEY_STREAM_URL = "stream_url"
    /** Keystore-encrypted stream URL. Plaintext [KEY_STREAM_URL] migrates here
     *  on first read and is removed; see [SecretStore] for the why. */
    private const val KEY_STREAM_URL_ENC = "stream_url_enc"
    // Legacy split-field keys, kept for one-time migration into the single URL.
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
    private const val KEY_MAX_RECONNECT_MIN = "max_reconnect_min"
    private const val KEY_OVERLAY_SCENE_V1 = "overlay_scene_v1"
    private const val KEY_MIC_DEVICE_NAME = "mic_device_name"
    private const val KEY_MIC_DEVICE_TYPE = "mic_device_type"
    private const val KEY_GPS_TOKEN_WARNING_SEEN = "gps_token_warning_seen"
    private const val KEY_STABILIZATION_ENABLED = "stabilization_enabled"
    private const val KEY_BRB_IMAGE_PATH = "brb_image_path"
    private const val KEY_AUTO_BITRATE = "auto_bitrate"
    private const val KEY_RECORD_WHILE_STREAMING = "record_while_streaming"

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

    /** Full RTMP/SRT URL for the destination, stream key included, stored
     *  encrypted (the key is a credential: anyone holding it can stream as you).
     *  Reads migrate older plaintext storage on the fly: first the plaintext
     *  single-URL key, then the original split server/key fields. */
    fun streamUrl(context: Context): String {
        val encrypted = sp(context).getString(KEY_STREAM_URL_ENC, null)
        if (encrypted != null) {
            SecretStore.decrypt(encrypted)?.let { return it }
            // Undecryptable (keystore was reset): drop it so the user gets a
            // clean empty field instead of a permanently broken one.
            sp(context).edit().remove(KEY_STREAM_URL_ENC).apply()
        }
        val plaintext = sp(context).getString(KEY_STREAM_URL, "") ?: ""
        if (plaintext.isNotBlank()) {
            setStreamUrl(context, plaintext)
            return plaintext
        }
        val server = (sp(context).getString(KEY_SERVER_URL, "") ?: "").trim().trimEnd('/')
        val key = (sp(context).getString(KEY_STREAM_KEY, "") ?: "").trim()
        if (server.isNotBlank() && key.isNotBlank()) {
            val combined = "$server/$key"
            setStreamUrl(context, combined)
            return combined
        }
        return ""
    }

    fun setStreamUrl(context: Context, url: String) {
        val encrypted = SecretStore.encrypt(url)
        sp(context).edit().apply {
            if (encrypted != null) {
                putString(KEY_STREAM_URL_ENC, encrypted)
                // Remove every plaintext copy once the encrypted one exists.
                remove(KEY_STREAM_URL)
                remove(KEY_SERVER_URL)
                remove(KEY_STREAM_KEY)
            } else {
                // Keystore refused (rare, broken devices): plaintext beats
                // silently losing the user's URL.
                putString(KEY_STREAM_URL, url)
            }
        }.apply()
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

    /** Auto bitrate: steer the encoder down when the network congests and back up
     *  as it recovers, instead of pushing a fixed rate into a link that can't carry
     *  it. The configured bitrate acts as the ceiling. Default on; turning it off
     *  restores fixed-bitrate behaviour. */
    fun autoBitrateEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_BITRATE, true)

    fun setAutoBitrateEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_BITRATE, enabled).apply()
    }

    /** Local recording: save a copy of the broadcast to the phone while
     *  streaming, so dropouts never lose the moment. Off by default; costs
     *  roughly 1 GB of storage per hour at 2500 kbps. */
    fun recordWhileStreaming(context: Context): Boolean =
        sp(context).getBoolean(KEY_RECORD_WHILE_STREAMING, false)

    fun setRecordWhileStreaming(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_RECORD_WHILE_STREAMING, enabled).apply()
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

    /** The active overlay scene. Falls back to [defaultScene] (the Roam Live
     *  watermark in the bottom-right corner) on first install or if the saved
     *  JSON is corrupted.
     *
     *  The `locked` flag on an overlay now only means "can't be deleted" —
     *  it no longer pins visibility or position. The Roam Live watermark uses
     *  this so a user can hide or move it but not remove it entirely. */
    fun overlayScene(context: Context): Scene {
        val json = sp(context).getString(KEY_OVERLAY_SCENE_V1, null) ?: return defaultScene()
        return OverlayJson.fromJson(json) ?: return defaultScene()
    }

    fun setOverlayScene(context: Context, scene: Scene) {
        sp(context).edit().putString(KEY_OVERLAY_SCENE_V1, OverlayJson.toJson(scene)).apply()
    }

    /** Preferred mic device identity, persisted as (productName, type). Null
     *  for either means "use the system default mic." */
    fun micDeviceName(context: Context): String? =
        sp(context).getString(KEY_MIC_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }

    fun micDeviceType(context: Context): Int? {
        val v = sp(context).getInt(KEY_MIC_DEVICE_TYPE, -1)
        return if (v >= 0) v else null
    }

    fun setMicDevice(context: Context, productName: String?, type: Int?) {
        sp(context).edit().apply {
            if (productName.isNullOrBlank() || type == null) {
                remove(KEY_MIC_DEVICE_NAME)
                remove(KEY_MIC_DEVICE_TYPE)
            } else {
                putString(KEY_MIC_DEVICE_NAME, productName)
                putInt(KEY_MIC_DEVICE_TYPE, type)
            }
        }.apply()
    }

    /** Has the user seen the one-time "this uses GPS, costs battery + heat"
     *  dialog before saving an overlay scene with a GPS-backed live token? */
    fun gpsTokenWarningSeen(context: Context): Boolean =
        sp(context).getBoolean(KEY_GPS_TOKEN_WARNING_SEEN, false)

    fun setGpsTokenWarningSeen(context: Context, seen: Boolean) {
        sp(context).edit().putBoolean(KEY_GPS_TOKEN_WARNING_SEEN, seen).apply()
    }

    /** Image stabilization (EIS + OIS where available) toggle. Off by default —
     *  it slightly crops the frame and existing users on upgrade shouldn't get
     *  a surprise viewport change. */
    fun stabilizationEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_STABILIZATION_ENABLED, false)

    fun setStabilizationEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_STABILIZATION_ENABLED, enabled).apply()
    }

    /** Optional custom image shown full-frame on the break screen instead of the
     *  default text-on-black. Stored as an absolute path in app-private storage
     *  (`OverlayImageStore`). Null means "use the text". */
    fun brbImagePath(context: Context): String? =
        sp(context).getString(KEY_BRB_IMAGE_PATH, null)?.takeIf { it.isNotBlank() }

    fun setBrbImagePath(context: Context, path: String?) {
        sp(context).edit().apply {
            if (path.isNullOrBlank()) remove(KEY_BRB_IMAGE_PATH)
            else putString(KEY_BRB_IMAGE_PATH, path)
        }.apply()
    }
}
