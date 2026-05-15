package dev.whitespc.roam.streaming

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.MotionEvent
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.gl.render.filters.BlackFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.generic.GenericStream
import com.pedro.library.view.OpenGlView
import dev.whitespc.roam.R
import dev.whitespc.roam.storage.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "RoamStreamingEngine"

private const val VIDEO_GOP_SECONDS = 2
private const val AUDIO_BITRATE = 128_000
private const val AUDIO_SAMPLE_RATE = 44_100
private const val AUDIO_STEREO = true

class StreamingEngine(private val context: Context) {
    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isCameraOff = MutableStateFlow(false)
    val isCameraOff: StateFlow<Boolean> = _isCameraOff.asStateFlow()

    private val _isBrb = MutableStateFlow(false)
    val isBrb: StateFlow<Boolean> = _isBrb.asStateFlow()

    private val _thermalNotice = MutableStateFlow<String?>(null)
    val thermalNotice: StateFlow<String?> = _thermalNotice.asStateFlow()

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val thermalListener =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager.OnThermalStatusChangedListener { status ->
                handleThermalChange(status)
            }
        } else {
            null
        }

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            Log.d(TAG, "connection started")
        }

        override fun onConnectionSuccess() {
            _state.value = StreamState.Live(0)
        }

        override fun onConnectionFailed(reason: String) {
            Log.w(TAG, "connection failed: $reason")
            _state.value = StreamState.Error(reason)
        }

        override fun onNewBitrate(bitrate: Long) {
            val current = _state.value
            if (current is StreamState.Live) {
                _state.value = StreamState.Live(bitrate)
            }
        }

        override fun onDisconnect() {
            _state.value = StreamState.Idle
        }

        override fun onAuthError() {
            _state.value = StreamState.Error("Authentication failed")
        }

        override fun onAuthSuccess() {
            Log.d(TAG, "auth success")
        }
    }

    val stream: GenericStream = GenericStream(context, connectChecker).apply {
        setVideoCodec(VideoCodec.H264)
        setAudioCodec(AudioCodec.AAC)
    }

    private var blackFilter: BlackFilterRender? = null

    private var brbBlackFilter: BlackFilterRender? = null
    private var brbTextFilter: TextObjectFilterRender? = null
    private var muteBeforeBrb = false

    private var watermarkFilter: ImageObjectFilterRender? = null

    private var isPrepared = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            powerManager?.addThermalStatusListener(thermalListener)
        }
    }

    fun attachPreview(view: OpenGlView, context: android.content.Context) {
        if (!isPrepared) {
            val width = Prefs.videoWidth(context)
            val height = Prefs.videoHeight(context)
            val fps = Prefs.videoFps(context)
            val bitrate = Prefs.videoBitrateKbps(context) * 1000
            val videoOk = stream.prepareVideo(
                width,
                height,
                bitrate,
                fps,
                VIDEO_GOP_SECONDS,
            )
            val audioOk = stream.prepareAudio(
                AUDIO_SAMPLE_RATE,
                AUDIO_STEREO,
                AUDIO_BITRATE,
            )
            if (!videoOk || !audioOk) {
                Log.e(TAG, "prepare failed video=$videoOk audio=$audioOk")
                _state.value = StreamState.Error("Encoder unavailable")
                return
            }
            isPrepared = true

            // Permanent watermark — burned into preview and broadcast.
            // No setting to disable for now; promotional / word-of-mouth.
            runCatching {
                val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.watermark)
                val wm = ImageObjectFilterRender().apply {
                    setImage(bitmap)
                    // 4:1 PNG; ~28% width / 7% height of the frame
                    setScale(28f, 7f)
                    setPosition(TranslateTo.BOTTOM_RIGHT)
                }
                stream.getGlInterface().addFilter(wm)
                watermarkFilter = wm
            }.onFailure { Log.w(TAG, "watermark add failed", it) }
        }
        if (stream.isOnPreview) stream.stopPreview()
        stream.startPreview(view)
    }

    fun detachPreview() {
        if (stream.isOnPreview) stream.stopPreview()
    }

    fun start(url: String) {
        if (stream.isStreaming) return
        if (!stream.isOnPreview) {
            _state.value = StreamState.Error("Camera not ready")
            return
        }
        if (url.isBlank()) {
            _state.value = StreamState.Error("Stream URL is empty")
            return
        }
        _state.value = StreamState.Connecting
        stream.startStream(url)
    }

    fun stop() {
        if (stream.isStreaming) stream.stopStream()
        _state.value = StreamState.Idle
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            powerManager?.removeThermalStatusListener(thermalListener)
        }
        if (stream.isStreaming) stream.stopStream()
        if (stream.isOnPreview) stream.stopPreview()
    }

    fun clearError() {
        if (_state.value is StreamState.Error) {
            _state.value = StreamState.Idle
        }
    }

    fun switchCamera() {
        (stream.videoSource as? Camera2Source)?.switchCamera()
    }

    fun toggleCameraOff() {
        val gl = stream.getGlInterface()
        if (_isCameraOff.value) {
            runCatching {
                gl.unMuteVideo()
                blackFilter?.let { gl.removeFilter(it) }
                blackFilter = null
            }
                .onSuccess { _isCameraOff.value = false }
                .onFailure { Log.w(TAG, "camera-on failed", it) }
        } else {
            runCatching {
                // muteVideo() blacks the encoder path (stream + record);
                // BlackFilterRender blacks the on-device preview.
                gl.muteVideo()
                val filter = BlackFilterRender()
                gl.addFilter(filter)
                blackFilter = filter
            }
                .onSuccess { _isCameraOff.value = true }
                .onFailure { Log.w(TAG, "camera-off failed", it) }
        }
    }

    fun toggleMute() {
        val mic = stream.audioSource as? MicrophoneSource ?: return
        if (_isMuted.value) {
            mic.unMute()
            _isMuted.value = false
        } else {
            mic.mute()
            _isMuted.value = true
        }
    }

    fun tapToFocus(event: MotionEvent) {
        runCatching {
            (stream.videoSource as? Camera2Source)?.tapToFocus(event)
        }
    }

    fun dismissThermalNotice() {
        _thermalNotice.value = null
    }

    fun toggleBrb(brbText: String) {
        val gl = stream.getGlInterface()
        val mic = stream.audioSource as? MicrophoneSource
        if (_isBrb.value) {
            runCatching {
                brbTextFilter?.let { gl.removeFilter(it) }
                brbBlackFilter?.let { gl.removeFilter(it) }
                brbTextFilter = null
                brbBlackFilter = null
                if (!muteBeforeBrb && _isMuted.value) {
                    mic?.unMute()
                    _isMuted.value = false
                }
            }
                .onSuccess { _isBrb.value = false }
                .onFailure { Log.w(TAG, "brb-off failed", it) }
        } else {
            runCatching {
                val black = BlackFilterRender()
                val text = TextObjectFilterRender().apply {
                    setText(
                        brbText.ifBlank { "BE RIGHT BACK" },
                        28f,
                        AndroidColor.WHITE,
                    )
                    setDefaultScale(
                        Prefs.videoWidth(context),
                        Prefs.videoHeight(context),
                    )
                    setPosition(TranslateTo.CENTER)
                }
                gl.addFilter(0, black)
                gl.addFilter(1, text)
                brbBlackFilter = black
                brbTextFilter = text
                muteBeforeBrb = _isMuted.value
                if (!_isMuted.value) {
                    mic?.mute()
                    _isMuted.value = true
                }
            }
                .onSuccess { _isBrb.value = true }
                .onFailure { Log.w(TAG, "brb-on failed", it) }
        }
    }

    private fun handleThermalChange(status: Int) {
        if (!stream.isStreaming) return
        val configuredBitrate = Prefs.videoBitrateKbps(context) * 1000
        when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> {
                runCatching { stream.setVideoBitrateOnFly(configuredBitrate) }
                _thermalNotice.value = null
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                val target = (configuredBitrate * 0.7).toInt()
                runCatching { stream.setVideoBitrateOnFly(target) }
                _thermalNotice.value = "Phone warming — bitrate dropped to ${target / 1000} kbps"
            }
            PowerManager.THERMAL_STATUS_SEVERE -> {
                val target = (configuredBitrate * 0.4).toInt()
                runCatching { stream.setVideoBitrateOnFly(target) }
                _thermalNotice.value = "Phone hot — bitrate dropped to ${target / 1000} kbps"
            }
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                _thermalNotice.value = "Phone too hot — stream stopped"
                stop()
            }
            else -> Unit
        }
    }
}
