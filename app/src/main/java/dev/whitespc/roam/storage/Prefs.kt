package dev.whitespc.roam.storage

import android.content.Context
import dev.whitespc.roam.streaming.StreamEndpointValidation
import dev.whitespc.roam.streaming.validateStreamEndpoint
import dev.whitespc.roam.streaming.overlay.OverlayJson
import dev.whitespc.roam.streaming.overlay.Scene
import dev.whitespc.roam.streaming.overlay.defaultScene

object Prefs {
    private const val FILE = "roam_prefs"

    private const val KEY_STREAM_URL = "stream_url"
    /** Keystore-encrypted stream URL. Plaintext [KEY_STREAM_URL] migrates here
     *  on first read and is removed. */
    private const val KEY_STREAM_URL_ENC = "stream_url_enc"
    private const val SECRET_PURPOSE_STREAM_URL = "stream-url"
    private const val SECRET_PURPOSE_OBS_PASSWORD = "obs-password"
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
    private const val KEY_YOUTUBE_CHANNEL = "youtube_channel"
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
    private const val KEY_DUAL_CAM_ENABLED = "dual_cam_enabled"
    private const val KEY_AUDIO_METER_ENABLED = "audio_meter_enabled"
    private const val KEY_OBS_HOST = "obs_host"
    private const val KEY_OBS_PORT = "obs_port"
    private const val KEY_OBS_PASSWORD = "obs_password"
    private const val KEY_OBS_PASSWORD_ENC = "obs_password_enc"
    private const val KEY_OBS_BRB_SCENE = "obs_brb_scene"
    private const val KEY_OBS_SYNC_STREAMING = "obs_sync_streaming"
    private const val KEY_OBS_BRB_MUTE = "obs_brb_mute"
    private const val KEY_MIC_GAIN = "mic_gain"
    private const val KEY_CHAT_TEXT_SIZE_SP = "chat_text_size_sp"
    private const val KEY_CHAT_PANEL_MODE = "chat_panel_mode"
    private const val KEY_MIRROR_FRONT_PIP = "mirror_front_pip"
    private const val KEY_UPDATE_CHECK_ENABLED = "update_check_enabled"
    private const val KEY_LAST_UPDATE_CHECK_MS = "last_update_check_ms"
    private const val KEY_DISMISSED_UPDATE_CODE = "dismissed_update_code"
    private const val KEY_AVAIL_UPDATE_CODE = "avail_update_code"
    private const val KEY_AVAIL_UPDATE_NAME = "avail_update_name"
    private const val KEY_AVAIL_UPDATE_URL = "avail_update_url"

    /** Chat panel width presets; see StreamScreen for the dp each maps to. */
    const val CHAT_PANEL_COMPACT = "compact"
    const val CHAT_PANEL_WIDE = "wide"
    const val CHAT_PANEL_HALF = "half"

    private const val KEY_CHAT_PANEL_SIDE = "chat_panel_side"
    const val CHAT_SIDE_LEFT = "left"
    const val CHAT_SIDE_RIGHT = "right"

    private const val DEFAULT_BRB_TEXT = "BE RIGHT BACK"
    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Full RTMP/SRT URL for the destination, stream key included, stored
     *  encrypted (the key is a credential: anyone holding it can stream as you).
     *  Reads migrate older plaintext storage on the fly: first the plaintext
     *  single-URL key, then the original split server/key fields. */
    fun streamUrl(context: Context): String {
        // Remove ciphertext left under a short-lived migration key.
        if (sp(context).contains("stream_url_2")) {
            sp(context).edit().remove("stream_url_2").apply()
        }
        val encrypted = sp(context).getString(KEY_STREAM_URL_ENC, null)
        if (encrypted != null) {
            SecretStore.decrypt(encrypted, SECRET_PURPOSE_STREAM_URL)?.let { plaintext ->
                if (SecretStore.needsMigration(encrypted)) {
                    SecretStore.encrypt(plaintext, SECRET_PURPOSE_STREAM_URL)?.let { migrated ->
                        sp(context).edit().putString(KEY_STREAM_URL_ENC, migrated).apply()
                    }
                }
                return plaintext
            }
            // Keep undecryptable data because Android Keystore can be
            // temporarily unavailable. A later save replaces an invalid blob.
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

    fun setStreamUrl(context: Context, url: String): Boolean {
        if (url.isEmpty()) {
            clearStreamUrl(context)
            return true
        }
        if (validateStreamEndpoint(url) is StreamEndpointValidation.Invalid) {
            // Editing an endpoint must fail closed. Keeping the previous hidden
            // credential here could send a later Go Live tap to the wrong place.
            clearStreamUrl(context)
            return false
        }
        val encrypted = SecretStore.encrypt(url, SECRET_PURPOSE_STREAM_URL)
        if (encrypted == null) {
            clearStreamUrl(context)
            return false
        }
        sp(context).edit()
            .putString(KEY_STREAM_URL_ENC, encrypted)
            .remove(KEY_STREAM_URL)
            .remove(KEY_SERVER_URL)
            .remove(KEY_STREAM_KEY)
            .apply()
        return true
    }

    private fun clearStreamUrl(context: Context) {
        sp(context).edit()
            .remove(KEY_STREAM_URL_ENC)
            .remove(KEY_STREAM_URL)
            .remove(KEY_SERVER_URL)
            .remove(KEY_STREAM_KEY)
            .apply()
    }

    /**
     * Read and atomically repair the old independent quality fields. Exact
     * legacy resolutions keep their intent; unsupported combinations are
     * migrated to the closest safe preset.
     */
    fun qualityPreset(context: Context): VideoQualityPreset {
        val preferences = sp(context)
        val values = preferences.all
        val default = VideoQualityPreset.default
        val width = values[KEY_VIDEO_WIDTH] as? Int ?: default.width
        val height = values[KEY_VIDEO_HEIGHT] as? Int ?: default.height
        val fps = values[KEY_VIDEO_FPS] as? Int ?: default.fps
        val bitrate = values[KEY_VIDEO_BITRATE_KBPS] as? Int ?: default.bitrateKbps
        val preset = VideoQualityPreset.closest(width, height, fps, bitrate)
        if (values[KEY_VIDEO_WIDTH] != preset.width ||
            values[KEY_VIDEO_HEIGHT] != preset.height ||
            values[KEY_VIDEO_FPS] != preset.fps ||
            values[KEY_VIDEO_BITRATE_KBPS] != preset.bitrateKbps
        ) {
            setQualityPreset(context, preset)
        }
        return preset
    }

    fun setQualityPreset(context: Context, preset: VideoQualityPreset) {
        sp(context).edit()
            .putInt(KEY_VIDEO_WIDTH, preset.width)
            .putInt(KEY_VIDEO_HEIGHT, preset.height)
            .putInt(KEY_VIDEO_FPS, preset.fps)
            .putInt(KEY_VIDEO_BITRATE_KBPS, preset.bitrateKbps)
            .apply()
    }

    fun videoWidth(context: Context): Int = qualityPreset(context).width

    fun videoHeight(context: Context): Int = qualityPreset(context).height

    fun videoFps(context: Context): Int = qualityPreset(context).fps

    fun videoBitrateKbps(context: Context): Int = qualityPreset(context).bitrateKbps

    /** Compatibility for older callers. Every write still resolves to a preset. */
    fun setResolution(context: Context, width: Int, height: Int) {
        val current = qualityPreset(context)
        setQualityPreset(
            context,
            VideoQualityPreset.closest(
                width,
                height,
                current.fps,
                current.bitrateKbps,
            ),
        )
    }

    /** Compatibility for older callers. The supported presets all use 30 fps. */
    fun setVideoFps(context: Context, fps: Int) {
        val current = qualityPreset(context)
        setQualityPreset(
            context,
            VideoQualityPreset.closest(
                current.width,
                current.height,
                fps,
                current.bitrateKbps,
            ),
        )
    }

    /** Compatibility for older callers. Bitrate follows the selected resolution. */
    fun setVideoBitrateKbps(context: Context, kbps: Int) {
        val current = qualityPreset(context)
        setQualityPreset(
            context,
            VideoQualityPreset.closest(
                current.width,
                current.height,
                current.fps,
                kbps,
            ),
        )
    }

    /** Auto bitrate: steer the encoder down when the network congests and back up
     *  as it recovers, instead of pushing a fixed rate into a link that can't carry
     *  it. The configured bitrate acts as the ceiling. Default on; turning it off
     *  restores fixed-bitrate behaviour. */
    fun autoBitrateEnabled(context: Context): Boolean =
        validatedBoolean(context, KEY_AUTO_BITRATE, true)

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

    /** Dual camera shows the opposite-facing camera as picture-in-picture. It
     *  defaults off because running both sensors adds substantial heat. */
    fun dualCamEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_DUAL_CAM_ENABLED, false)

    fun setDualCamEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_DUAL_CAM_ENABLED, enabled).apply()
    }

    /** HUD audio level meter: a 4-segment bar that lights up with the current
     *  mic peak so a streamer can see the mic is alive without hearing it.
     *  Off by default - it's diagnostic, not core to the broadcast - but
     *  costs effectively zero (peeks at PCM samples already in memory) so
     *  no heat or battery warning needed. */
    fun audioMeterEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUDIO_METER_ENABLED, false)

    fun setAudioMeterEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUDIO_METER_ENABLED, enabled).apply()
    }

    /** OBS WebSocket pairing. Host is a private-network address for the machine
     *  running OBS; port defaults to 4455. The password is encrypted with the
     *  same Android Keystore key used for stream destinations. */
    fun obsHost(context: Context): String =
        sp(context).getString(KEY_OBS_HOST, "") ?: ""

    fun setObsHost(context: Context, host: String) {
        sp(context).edit().putString(KEY_OBS_HOST, host).apply()
    }

    fun obsPort(context: Context): Int = validatedInt(
        context,
        KEY_OBS_PORT,
        UserConfigRules.DEFAULT_OBS_PORT,
        UserConfigRules::obsPort,
    )

    fun setObsPort(context: Context, port: Int): Boolean {
        if (!UserConfigRules.isValidObsPort(port)) return false
        sp(context).edit().putInt(KEY_OBS_PORT, port).apply()
        return true
    }

    fun obsPassword(context: Context): String {
        val encrypted = sp(context).getString(KEY_OBS_PASSWORD_ENC, null)
        if (encrypted != null) {
            SecretStore.decrypt(encrypted, SECRET_PURPOSE_OBS_PASSWORD)?.let { plaintext ->
                if (SecretStore.needsMigration(encrypted)) {
                    SecretStore.encrypt(plaintext, SECRET_PURPOSE_OBS_PASSWORD)?.let { migrated ->
                        sp(context).edit().putString(KEY_OBS_PASSWORD_ENC, migrated).apply()
                    }
                }
                return plaintext
            }
        }
        val legacyPlaintext = sp(context).getString(KEY_OBS_PASSWORD, "") ?: ""
        if (legacyPlaintext.isNotBlank()) {
            setObsPassword(context, legacyPlaintext)
        }
        return legacyPlaintext
    }

    fun setObsPassword(context: Context, password: String): Boolean {
        if (password.isBlank()) {
            sp(context).edit()
                .remove(KEY_OBS_PASSWORD_ENC)
                .remove(KEY_OBS_PASSWORD)
                .apply()
            return true
        }
        val encrypted = SecretStore.encrypt(password, SECRET_PURPOSE_OBS_PASSWORD) ?: return false
        sp(context).edit()
            .putString(KEY_OBS_PASSWORD_ENC, encrypted)
            .remove(KEY_OBS_PASSWORD)
            .apply()
        return true
    }

    /** Name of the OBS scene the HUD BRB icon switches to when OBS is paired.
     *  When blank, or when OBS isn't paired, BRB falls back to the phone-side
     *  BRB image overlay. The autocompletes in Settings come from the live
     *  scene list fetched after pairing. */
    fun obsBrbScene(context: Context): String =
        sp(context).getString(KEY_OBS_BRB_SCENE, "") ?: ""

    fun setObsBrbScene(context: Context, name: String) {
        sp(context).edit().putString(KEY_OBS_BRB_SCENE, name).apply()
    }

    /** When enabled, Roam starts and stops the paired OBS output with the phone
     *  feed. It defaults off so the feed can be positioned before broadcasting. */
    fun obsSyncStreaming(context: Context): Boolean =
        sp(context).getBoolean(KEY_OBS_SYNC_STREAMING, false)

    /** Mute mic + black camera while the OBS BRB scene is program. Default on:
     *  with the recommended source-in-every-scene OBS setup, audio is
     *  otherwise always live, including through breaks. */
    fun obsBrbMute(context: Context): Boolean =
        sp(context).getBoolean(KEY_OBS_BRB_MUTE, true)

    fun setObsBrbMute(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_OBS_BRB_MUTE, enabled).apply()
    }

    fun setObsSyncStreaming(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_OBS_SYNC_STREAMING, enabled).apply()
    }

    /** Microphone input gain. 1.0 is unity (untouched), <1 is quieter,
     *  >1 amplifies. Values are kept inside the UI's 0x..2x range. */
    fun micGain(context: Context): Float = validatedFloat(
        context,
        KEY_MIC_GAIN,
        UserConfigRules.DEFAULT_MIC_GAIN,
        UserConfigRules::micGain,
    )

    fun setMicGain(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_MIC_GAIN, UserConfigRules.micGain(value)).apply()
    }

    fun chatTextSizeSp(context: Context): Int = validatedInt(
        context,
        KEY_CHAT_TEXT_SIZE_SP,
        UserConfigRules.DEFAULT_CHAT_TEXT_SIZE_SP,
        UserConfigRules::chatTextSizeSp,
    )

    fun setChatTextSizeSp(context: Context, sizeSp: Int) {
        sp(context).edit()
            .putInt(KEY_CHAT_TEXT_SIZE_SP, UserConfigRules.chatTextSizeSp(sizeSp))
            .apply()
    }

    fun chatPanelMode(context: Context): String = validatedString(
        context,
        KEY_CHAT_PANEL_MODE,
        CHAT_PANEL_COMPACT,
        UserConfigRules::chatPanelMode,
    )

    fun setChatPanelMode(context: Context, mode: String) {
        sp(context).edit()
            .putString(KEY_CHAT_PANEL_MODE, UserConfigRules.chatPanelMode(mode))
            .apply()
    }

    /** Which side of the screen the chat panel sits on. Left is the default:
     *  eyes read left to right, and it keeps names clear of the GO LIVE
     *  button when the panel is wide. */
    fun chatPanelSide(context: Context): String = validatedString(
        context,
        KEY_CHAT_PANEL_SIDE,
        CHAT_SIDE_LEFT,
        UserConfigRules::chatPanelSide,
    )

    fun setChatPanelSide(context: Context, side: String) {
        sp(context).edit()
            .putString(KEY_CHAT_PANEL_SIDE, UserConfigRules.chatPanelSide(side))
            .apply()
    }

    /** Whether the dual-cam front PiP keeps the mirrored selfie look (default)
     *  or is flipped to what viewers would read as correct. */
    fun mirrorFrontPip(context: Context): Boolean =
        sp(context).getBoolean(KEY_MIRROR_FRONT_PIP, true)

    fun setMirrorFrontPip(context: Context, mirrored: Boolean) {
        sp(context).edit().putBoolean(KEY_MIRROR_FRONT_PIP, mirrored).apply()
    }

    fun updateCheckEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_UPDATE_CHECK_ENABLED, true)

    fun setUpdateCheckEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_UPDATE_CHECK_ENABLED, enabled).apply()
    }

    fun lastUpdateCheckMs(context: Context): Long =
        sp(context).getLong(KEY_LAST_UPDATE_CHECK_MS, 0L)

    fun setLastUpdateCheckMs(context: Context, ms: Long) {
        sp(context).edit().putLong(KEY_LAST_UPDATE_CHECK_MS, ms).apply()
    }

    fun dismissedUpdateCode(context: Context): Int =
        sp(context).getInt(KEY_DISMISSED_UPDATE_CODE, 0)

    fun setDismissedUpdateCode(context: Context, code: Int) {
        sp(context).edit().putInt(KEY_DISMISSED_UPDATE_CODE, code).apply()
    }

    /** Last update-check result, persisted so Settings can show it without a
     *  refetch. Code 0 = nothing known. */
    fun availableUpdate(context: Context): Triple<Int, String, String>? {
        val code = sp(context).getInt(KEY_AVAIL_UPDATE_CODE, 0)
        if (code <= 0) return null
        val name = sp(context).getString(KEY_AVAIL_UPDATE_NAME, "") ?: ""
        val url = sp(context).getString(KEY_AVAIL_UPDATE_URL, "") ?: ""
        return Triple(code, name, url)
    }

    fun setAvailableUpdate(context: Context, code: Int, name: String, url: String) {
        sp(context).edit()
            .putInt(KEY_AVAIL_UPDATE_CODE, code)
            .putString(KEY_AVAIL_UPDATE_NAME, name)
            .putString(KEY_AVAIL_UPDATE_URL, url)
            .apply()
    }

    fun clearAvailableUpdate(context: Context) {
        sp(context).edit()
            .remove(KEY_AVAIL_UPDATE_CODE)
            .remove(KEY_AVAIL_UPDATE_NAME)
            .remove(KEY_AVAIL_UPDATE_URL)
            .apply()
    }

    /** Saved destination slots (3 max): quick recovery when the usual path
     *  dies mid-outing, e.g. the home OBS PC crashes at a convention and the
     *  streamer flips to direct Twitch in two taps. Names are plain; URLs are
     *  credentials, so they're encrypted like the live stream URL and their
     *  key falls under the "stream_url" prefix the backup exporter excludes. */
    const val DEST_SLOTS = 3

    fun destinationName(context: Context, slot: Int): String? {
        requireDestinationSlot(slot)
        return sp(context).getString("dest_name_$slot", null)?.takeIf { it.isNotBlank() }
    }

    fun destinationUrl(context: Context, slot: Int): String? {
        requireDestinationSlot(slot)
        val enc = sp(context).getString("stream_url_dest_$slot", null) ?: return null
        val purpose = destinationSecretPurpose(slot)
        val plaintext = SecretStore.decrypt(enc, purpose)?.takeIf { it.isNotBlank() } ?: return null
        if (SecretStore.needsMigration(enc)) {
            SecretStore.encrypt(plaintext, purpose)?.let { migrated ->
                sp(context).edit().putString("stream_url_dest_$slot", migrated).apply()
            }
        }
        return plaintext
    }

    fun setDestination(context: Context, slot: Int, name: String, url: String): Boolean {
        requireDestinationSlot(slot)
        val cleanName = name.trim()
        if (cleanName.length !in 1..64 || cleanName.any { it.isISOControl() }) return false
        if (validateStreamEndpoint(url) is StreamEndpointValidation.Invalid) return false
        val encrypted = SecretStore.encrypt(url, destinationSecretPurpose(slot)) ?: return false
        sp(context).edit()
            .putString("dest_name_$slot", cleanName)
            .putString("stream_url_dest_$slot", encrypted)
            .apply()
        return true
    }

    fun clearDestination(context: Context, slot: Int) {
        requireDestinationSlot(slot)
        sp(context).edit()
            .remove("dest_name_$slot")
            .remove("stream_url_dest_$slot")
            .apply()
    }

    /** First slot with nothing in it, or null when all are full. */
    fun firstEmptyDestinationSlot(context: Context): Int? =
        (0 until DEST_SLOTS).firstOrNull { destinationUrl(context, it) == null }

    private fun requireDestinationSlot(slot: Int) {
        require(slot in 0 until DEST_SLOTS) { "invalid destination slot" }
    }

    private fun destinationSecretPurpose(slot: Int): String = "stream-destination-$slot"

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

    fun youtubeChannel(context: Context): String =
        sp(context).getString(KEY_YOUTUBE_CHANNEL, "") ?: ""

    fun setYouTubeChannel(context: Context, channel: String) {
        sp(context).edit().putString(KEY_YOUTUBE_CHANNEL, channel).apply()
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

    fun stealthPulseSeconds(context: Context): Int = validatedInt(
        context,
        KEY_STEALTH_PULSE_SEC,
        UserConfigRules.DEFAULT_STEALTH_PULSE_SECONDS,
        UserConfigRules::stealthPulseSeconds,
    )

    fun setStealthPulseSeconds(context: Context, seconds: Int) {
        sp(context).edit()
            .putInt(KEY_STEALTH_PULSE_SEC, UserConfigRules.stealthPulseSeconds(seconds))
            .apply()
    }

    /** Minutes the engine will keep retrying reconnect after an unexpected disconnect.
     *  0 = never give up. Default 5. */
    fun maxReconnectMinutes(context: Context): Int = validatedInt(
        context,
        KEY_MAX_RECONNECT_MIN,
        UserConfigRules.DEFAULT_MAX_RECONNECT_MINUTES,
        UserConfigRules::maxReconnectMinutes,
    )

    fun setMaxReconnectMinutes(context: Context, minutes: Int) {
        sp(context).edit()
            .putInt(KEY_MAX_RECONNECT_MIN, UserConfigRules.maxReconnectMinutes(minutes))
            .apply()
    }

    /** The active overlay scene. Falls back to [defaultScene] (the Roam Live
     *  watermark in the bottom-right corner) on first install or if the saved
     *  JSON is corrupted.
     *
     *  The `locked` flag on an overlay now only means "can't be deleted" -
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

    /** Image stabilization (EIS + OIS where available) toggle. Off by default -
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

    /** Repair legacy or corrupted resource-sensitive configuration at startup. */
    fun sanitizeUserConfiguration(context: Context) {
        qualityPreset(context)
        autoBitrateEnabled(context)
        obsPort(context)
        micGain(context)
        chatTextSizeSp(context)
        chatPanelMode(context)
        chatPanelSide(context)
        stealthPulseSeconds(context)
        maxReconnectMinutes(context)
    }

    private fun validatedBoolean(context: Context, key: String, default: Boolean): Boolean {
        val preferences = sp(context)
        val stored = preferences.all[key]
        val value = stored as? Boolean ?: default
        if (preferences.contains(key) && stored != value) {
            preferences.edit().putBoolean(key, value).apply()
        }
        return value
    }

    private fun validatedInt(
        context: Context,
        key: String,
        default: Int,
        sanitize: (Int?) -> Int,
    ): Int {
        val preferences = sp(context)
        val stored = preferences.all[key]
        val value = if (stored is Int) sanitize(stored) else default
        if (preferences.contains(key) && stored != value) {
            preferences.edit().putInt(key, value).apply()
        }
        return value
    }

    private fun validatedFloat(
        context: Context,
        key: String,
        default: Float,
        sanitize: (Float?) -> Float,
    ): Float {
        val preferences = sp(context)
        val stored = preferences.all[key]
        val value = if (stored is Float) sanitize(stored) else default
        if (preferences.contains(key) && stored != value) {
            preferences.edit().putFloat(key, value).apply()
        }
        return value
    }

    private fun validatedString(
        context: Context,
        key: String,
        default: String,
        sanitize: (String?) -> String,
    ): String {
        val preferences = sp(context)
        val stored = preferences.all[key]
        val value = if (stored is String) sanitize(stored) else default
        if (preferences.contains(key) && stored != value) {
            preferences.edit().putString(key, value).apply()
        }
        return value
    }
}
