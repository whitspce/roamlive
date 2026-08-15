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
 * Standalone AudioRecord loop for the pre-stream HUD meter. The streaming
 * source takes over the same meter after go-live. Bluetooth metering stays in
 * the streaming path because it requires an SCO route.
 */
private const val TAG = "RoamMicPreview"
private const val SAMPLE_RATE = 44_100
private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

class MicPreviewReader(private val onPeak: (Float) -> Unit) {

    private var record: AudioRecord? = null
    private var job: Job? = null
    private var currentDeviceId: Int? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun start(device: AudioDeviceInfo?) {
        if (record != null) {
            // AudioRecord routing is fixed at start, so changing devices needs
            // a new recorder.
            if (device?.id == currentDeviceId) return
            stop()
        }
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
        // Route to the selected non-Bluetooth input. Bluetooth SCO is handled
        // by the streaming path.
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
        currentDeviceId = device?.id
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
        currentDeviceId = null
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
