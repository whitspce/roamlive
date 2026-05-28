package dev.whitespc.roam.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * A user-pickable microphone input device.
 *
 * Stable identity is (productName, type) — Android's numeric device id can
 * change across reconnects, so we don't persist on that. The (name, type) pair
 * is what survives unplugging and replugging the same device.
 */
data class MicDevice(
    val info: AudioDeviceInfo,
    val productName: String,
    val type: Int,
) {
    /** Display label like "Built-in microphone", "USB: <name>", "Bluetooth: <name>". */
    val label: String
        get() = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB: ${productName.ifBlank { "audio device" }}"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth: ${productName.ifBlank { "headset" }}"
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired: ${productName.ifBlank { "headset" }}"
            else -> productName.ifBlank { "Unknown input" }
        }
}

object MicDevices {

    /** Device types that are meaningful as a streaming mic. Android reports a
     *  long tail of inputs that aren't useful here — telephony, FM tuner,
     *  remote submix, individual physical mic positions on a phone — which
     *  show up as confusing duplicates. We allow only the types a streamer
     *  would actually pick. */
    private val STREAMING_INPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    )

    /** Pickable input devices. Filtered to streaming-useful types and deduped
     *  by (type, productName) — a Pixel exposes several physical mics as
     *  separate TYPE_BUILTIN_MIC entries; the user just wants one row. */
    fun list(context: Context): List<MicDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val seenLabels = mutableSetOf<String>()
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .asSequence()
            .filter { it.isSource && it.type in STREAMING_INPUT_TYPES }
            .map {
                MicDevice(
                    info = it,
                    productName = it.productName?.toString().orEmpty(),
                    type = it.type,
                )
            }
            // Dedupe on the displayed label so identical-looking rows collapse
            // (a phone can report several physical mics as separate
            // TYPE_BUILTIN_MIC entries; the user just wants one "Built-in" row).
            .filter { seenLabels.add(it.label) }
            .toList()
    }

    /** Resolve a saved (productName, type) to a currently-present device. */
    fun find(context: Context, productName: String?, type: Int?): MicDevice? {
        if (productName == null || type == null) return null
        return list(context).firstOrNull {
            it.productName == productName && it.type == type
        }
    }
}
