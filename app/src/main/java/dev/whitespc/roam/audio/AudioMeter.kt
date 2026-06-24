package dev.whitespc.roam.audio

import com.pedro.encoder.input.audio.CustomAudioEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Source-agnostic audio level state for the HUD meter. Two things feed it:
 * the streaming [Effect] sitting on the MicrophoneSource (when live), and the
 * [MicPreviewReader] running its own AudioRecord (when not live, so the
 * streamer can verify their mic before going live). Both call [feed] with a
 * raw 0..1 peak; ballistics (fast attack, slow release) live here so the bar
 * decays cleanly regardless of which source is talking.
 */
class AudioMeter {

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var peak = 0f

    /** Push a raw 0..1 peak from whichever source is currently feeding us. */
    fun feed(rawPeak: Float) {
        val s = rawPeak.coerceIn(0f, 1f)
        peak = if (s > peak) s else peak * RELEASE
        _level.value = peak
    }

    fun reset() {
        peak = 0f
        _level.value = 0f
    }

    /** RootEncoder audio-effect adapter: peeks at PCM 16-bit LE samples on
     *  their way to the encoder and feeds the meter. Pass-through is zero-copy
     *  so the encoder pipeline isn't slowed down. */
    inner class Effect : CustomAudioEffect() {
        override fun process(input: ByteArray): ByteArray {
            var max = 0
            var i = 0
            while (i + 1 < input.size) {
                val sample =
                    ((input[i + 1].toInt() shl 8) or (input[i].toInt() and 0xff))
                        .toShort()
                        .toInt()
                val a = abs(sample)
                if (a > max) max = a
                i += 2
            }
            feed(max / MAX_SHORT)
            return input
        }
    }

    companion object {
        private const val MAX_SHORT = 32_768f
        private const val RELEASE = 0.7f
    }
}
