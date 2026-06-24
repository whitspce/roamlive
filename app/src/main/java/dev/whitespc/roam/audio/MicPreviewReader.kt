package dev.whitespc.roam.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.whitespc.roam.diagnostics.RoamLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Standalone AudioRecord loop that drives the HUD audio meter BEFORE the
 * stream starts, so a streamer can verify their mic is picking up sound
 * during setup. Once the stream goes live, the engine stops this reader and
 * the streaming source's CustomAudioEffect takes over (the same StateFlow
 * gets fed, so the UI doesn't notice the handoff). Mic device routing here
 * is the system default — pre-live BT-mic metering would require SCO setup
 * we deliberately keep out of the preview path.
 */
private const val TAG = "RoamMicPreview"
private const val SAMPLE_RATE = 44_100
private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

class MicPreviewReader(private val onPeak: (Float) -> Unit) {

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun start(device: AudioDeviceInfo?) {
        if (record != null) return
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(2048)
        val r = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufSize,
            )
        }.getOrElse {
            Log.w(TAG, "AudioRecord ctor failed", it)
            return
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized")
            runCatching { r.release() }
            return
        }
        // Route to the user's picked mic so the meter reflects what'll actually
        // go out on the stream. Bluetooth is excluded upstream — SCO setup
        // belongs to the streaming path, not the preview path.
        if (device != null) {
            runCatching { r.setPreferredDevice(device) }
                .onFailure { Log.w(TAG, "setPreferredDevice failed", it) }
        }
        val started = runCatching { r.startRecording() }
        if (started.isFailure) {
            Log.w(TAG, "startRecording failed", started.exceptionOrNull())
            runCatching { r.release() }
            return
        }
        record = r
        job = scope.launch {
            val buf = ShortArray(bufSize / 2)
            while (isActive) {
                val read = runCatching { r.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (read <= 0) {
                    delay(50)
                    continue
                }
                var max = 0
                for (i in 0 until read) {
                    val a = abs(buf[i].toInt())
                    if (a > max) max = a
                }
                onPeak(max / 32_768f)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        record?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        record = null
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
