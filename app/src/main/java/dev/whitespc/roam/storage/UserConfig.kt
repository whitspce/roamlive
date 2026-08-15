package dev.whitespc.roam.storage

/** The only encoder configurations exposed to users. Keep these values stable. */
enum class VideoQualityPreset(
    val storageId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
) {
    DATA_SAVER("data_saver", 854, 480, 30, 1_200),
    RECOMMENDED("recommended", 1280, 720, 30, 2_500),
    SHARP("sharp", 1920, 1080, 30, 4_500),
    ;

    fun matches(width: Int, height: Int, fps: Int, bitrateKbps: Int): Boolean =
        this.width == width && this.height == height && this.fps == fps &&
            this.bitrateKbps == bitrateKbps

    companion object {
        val default: VideoQualityPreset = RECOMMENDED

        /**
         * Migrate an old four-field configuration to one supported preset.
         * Resolution is the strongest signal because it determines camera,
         * encoder, and texture allocations. Bitrate and frame rate are then
         * replaced by the safe values belonging to that resolution.
         */
        fun closest(
            width: Int,
            height: Int,
            fps: Int,
            bitrateKbps: Int,
        ): VideoQualityPreset {
            entries.firstOrNull { it.matches(width, height, fps, bitrateKbps) }
                ?.let { return it }
            entries.firstOrNull { it.width == width && it.height == height }
                ?.let { return it }
            if (width <= 0 || height <= 0 || fps <= 0 || bitrateKbps <= 0) return default

            val pixels = width.toLong() * height.toLong()
            return entries.minWithOrNull(
                compareBy<VideoQualityPreset>(
                    { kotlin.math.abs(pixels - it.width.toLong() * it.height.toLong()) },
                    { kotlin.math.abs(bitrateKbps.toLong() - it.bitrateKbps.toLong()) },
                ),
            ) ?: default
        }
    }
}

/** Pure validation rules shared by preference reads and backup restoration. */
internal object UserConfigRules {
    const val DEFAULT_OBS_PORT = 4455
    const val DEFAULT_MIC_GAIN = 1f
    const val DEFAULT_CHAT_TEXT_SIZE_SP = 13
    const val DEFAULT_CHAT_PANEL_MODE = "compact"
    const val DEFAULT_CHAT_PANEL_SIDE = "left"
    const val DEFAULT_STEALTH_PULSE_SECONDS = 30
    const val DEFAULT_MAX_RECONNECT_MINUTES = 5

    val chatTextSizes = setOf(11, 13, 16)
    val chatPanelModes = setOf("compact", "wide", "half")
    val chatPanelSides = setOf("left", "right")
    val allowedStealthPulseSeconds = setOf(30, 60)
    val allowedMaxReconnectMinutes = setOf(0, 1, 5, 15)

    fun isValidObsPort(value: Int): Boolean = value in 1..65_535

    fun obsPort(value: Int?): Int = value?.takeIf(::isValidObsPort) ?: DEFAULT_OBS_PORT

    fun micGain(value: Float?): Float = when {
        value == null || !value.isFinite() -> DEFAULT_MIC_GAIN
        else -> value.coerceIn(0f, 2f)
    }

    fun chatTextSizeSp(value: Int?): Int =
        value?.takeIf(chatTextSizes::contains) ?: DEFAULT_CHAT_TEXT_SIZE_SP

    fun chatPanelMode(value: String?): String =
        value?.takeIf(chatPanelModes::contains) ?: DEFAULT_CHAT_PANEL_MODE

    fun chatPanelSide(value: String?): String =
        value?.takeIf(chatPanelSides::contains) ?: DEFAULT_CHAT_PANEL_SIDE

    fun stealthPulseSeconds(value: Int?): Int =
        value?.takeIf(allowedStealthPulseSeconds::contains) ?: DEFAULT_STEALTH_PULSE_SECONDS

    fun maxReconnectMinutes(value: Int?): Int =
        value?.takeIf(allowedMaxReconnectMinutes::contains) ?: DEFAULT_MAX_RECONNECT_MINUTES
}
