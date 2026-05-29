package dev.whitespc.roam.streaming

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.gl.render.filters.BlackFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.streamclient.GenericStreamClient
import com.pedro.library.view.OpenGlView
import dev.whitespc.roam.NetworkMonitor
import dev.whitespc.roam.audio.MicDevices
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.streaming.overlay.OverlayRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "RoamStreamingEngine"

private const val VIDEO_GOP_SECONDS = 2
private const val AUDIO_BITRATE = 128_000
private const val AUDIO_SAMPLE_RATE = 44_100
private const val AUDIO_STEREO = true

// Single-destination by design. Multi-destination is served by third-party fan-out
// services (Restream, Beamstream) — phone-side multi-stream multiplies upload over
// one cellular link, which is the bottleneck IRL streaming already fights. If Roam
// ever ships its own relay server, fan-out happens there, not on the phone.
class StreamingEngine(private val context: Context) {
    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isCameraOff = MutableStateFlow(false)
    val isCameraOff: StateFlow<Boolean> = _isCameraOff.asStateFlow()

    private val _isBrb = MutableStateFlow(false)
    val isBrb: StateFlow<Boolean> = _isBrb.asStateFlow()

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

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

    private var stopRequested = false

    // Reconnect state. `wantStreaming` is the user's intent (set by start, cleared by stop).
    // `hasEverConnected` ensures we only auto-reconnect after a stream that successfully
    // went live at least once — initial connect failures still surface as Error so users
    // can fix a bad URL/key rather than have us silently retry forever.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wantStreaming = false
    private var hasEverConnected = false
    private var lastStreamUrl: String? = null
    private var reconnectJob: Job? = null
    private var isReconnecting = false
    /** Tracks the outcome of the CURRENT reconnect attempt. The ConnectChecker
     *  completes this when either onConnectionSuccess (true) or onConnectionFailed
     *  (false) fires — so the loop wakes immediately instead of polling state. */
    private var currentAttemptOutcome: CompletableDeferred<Boolean>? = null

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            Log.d(TAG, "connection started: $url")
        }

        override fun onConnectionSuccess() {
            Log.d(TAG, "connection success")
            if (stopRequested) return
            hasEverConnected = true
            // Signal the reconnect loop (if any) that this attempt succeeded.
            currentAttemptOutcome?.complete(true)
            _state.value = StreamState.Live(0, connectedCount = 1, totalCount = 1)
        }

        override fun onConnectionFailed(reason: String) {
            Log.w(TAG, "connection failed: $reason")
            if (stopRequested) return
            if (isReconnecting) {
                // Inside a reconnect attempt — signal failure so the loop wakes up
                // immediately and retries instead of timing out a fixed wait.
                currentAttemptOutcome?.complete(false)
                return
            }
            if (wantStreaming && hasEverConnected) {
                // First failure after a successful stream: kick off reconnect.
                startReconnect()
                return
            }
            _state.value = StreamState.Error(reason)
        }

        override fun onNewBitrate(bitrate: Long) {
            if (stopRequested) return
            val current = _state.value
            if (current is StreamState.Live) {
                _state.value = current.copy(bitrateBps = bitrate)
            }
        }

        override fun onDisconnect() {
            Log.d(TAG, "disconnect")
            if (stopRequested) return
            if (isReconnecting) return
            if (wantStreaming && hasEverConnected) {
                // Surprise disconnect mid-stream: try to recover.
                startReconnect()
            }
        }

        override fun onAuthError() {
            Log.w(TAG, "auth error")
            if (!stopRequested) {
                _state.value = StreamState.Error("Authentication failed")
            }
        }

        override fun onAuthSuccess() {
            Log.d(TAG, "auth success")
        }
    }

    val stream: GenericStream = GenericStream(context, connectChecker).apply {
        setVideoCodec(VideoCodec.H264)
        setAudioCodec(AudioCodec.AAC)
        runCatching {
            (getStreamClient() as? GenericStreamClient)?.setLogs(true)
        }
    }

    private var blackFilter: BlackFilterRender? = null

    private var brbBlackFilter: BlackFilterRender? = null
    private var brbTextFilter: TextObjectFilterRender? = null
    private var muteBeforeBrb = false

    private val dualCamera = DualCameraController(context, stream)
    val isDualCamOn: StateFlow<Boolean> = dualCamera.isOn

    private val overlayRenderer = OverlayRenderer(context, stream)
    /** Tracks which way the main camera (the encoder's video source) is currently
     *  facing. Camera2Source defaults to BACK, so we start at false (back). Flipped
     *  whenever switchCamera runs. Needed so we can enable PiP with the OPPOSITE
     *  facing — two cameras with the same facing can't be opened concurrently. */
    private var mainFacingFront = false

    private var isPrepared = false

    // The OpenGlView the encoder is currently previewing into. Kept so we can
    // re-prepare the encoder (resolution/fps change) while idle without the UI
    // having to hand the view back. Set on every attachPreview.
    private var currentView: OpenGlView? = null

    // What the encoder was last prepared with. Compared against current prefs in
    // syncConfig to decide whether an idle re-prepare is needed. This used to
    // happen for free because navigating to Settings tore the engine down and
    // recreated it; now the engine survives navigation, so we re-apply explicitly.
    private var preparedWidth = 0
    private var preparedHeight = 0
    private var preparedFps = 0
    private var preparedBitrate = 0
    private var preparedMicName: String? = null
    private var preparedMicType: Int? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            powerManager?.addThermalStatusListener(thermalListener)
        }
        // Watch for the OS reporting network loss while we're live, and proactively
        // enter Reconnecting state instead of waiting ~75s for RTMP's TCP timeout.
        // drop(1) skips the initial StateFlow value so we only react to actual changes.
        engineScope.launch {
            NetworkMonitor.isAvailable.drop(1).collect { available ->
                if (!available && wantStreaming && _state.value is StreamState.Live) {
                    Log.d(TAG, "network lost while live, entering reconnect proactively")
                    runCatching { if (stream.isStreaming) stream.stopStream() }
                    startReconnect()
                }
            }
        }
    }

    fun attachPreview(view: OpenGlView, context: android.content.Context) {
        currentView = view
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
            preparedWidth = width
            preparedHeight = height
            preparedFps = fps
            preparedBitrate = bitrate
            applyPreferredMic(context)
        }
        if (stream.isOnPreview) stream.stopPreview()
        stream.startPreview(view)
        // Re-apply the overlay scene on every attach (not just the first one).
        // When the app is backgrounded without being killed, the OpenGL surface
        // gets torn down and the existing filters lose their GPU textures —
        // they'd draw blank on return. Re-applying always is the simplest way
        // to stay correct across both cold launches and background-resume.
        // It does mean a web overlay reloads when you return from background.
        // BRB / camera-off / dual-cam PiP are separate mode-based filters
        // managed elsewhere; not part of the scene.
        overlayRenderer.applyScene(Prefs.overlayScene(context))
    }

    fun detachPreview() {
        if (stream.isOnPreview) stream.stopPreview()
    }

    /** Apply the user's preferred mic if one is picked and currently present. If the
     *  saved device isn't plugged in, the resolver returns null and we leave the
     *  system default in place. Records what we applied so syncConfig can detect a
     *  later change. */
    private fun applyPreferredMic(context: Context) {
        val name = Prefs.micDeviceName(context)
        val type = Prefs.micDeviceType(context)
        val micDevice = MicDevices.find(context, name, type)
        if (micDevice != null) {
            runCatching {
                (stream.audioSource as? MicrophoneSource)?.setPreferredDevice(micDevice.info)
            }.onFailure { Log.w(TAG, "setPreferredDevice failed", it) }
        }
        preparedMicName = name
        preparedMicType = type
    }

    /** Re-apply settings that changed while idle. Settings now draws on top of the
     *  streaming screen rather than replacing it, so the engine survives navigation
     *  and no longer re-reads prefs via a recreate. We do it here, explicitly, at the
     *  safe idle moment (Settings closing). No-op while streaming: resolution / fps /
     *  mic can't change live and are greyed in the UI; bitrate is handled separately
     *  by setBitrate. Always re-applies the overlay scene so overlay edits show. */
    fun syncConfig(context: Context) {
        if (stream.isStreaming || !isPrepared) return
        val view = currentView ?: return
        val width = Prefs.videoWidth(context)
        val height = Prefs.videoHeight(context)
        val fps = Prefs.videoFps(context)
        val bitrate = Prefs.videoBitrateKbps(context) * 1000
        val micName = Prefs.micDeviceName(context)
        val micType = Prefs.micDeviceType(context)
        val videoChanged = width != preparedWidth || height != preparedHeight ||
            fps != preparedFps || bitrate != preparedBitrate
        val micChanged = micName != preparedMicName || micType != preparedMicType
        if (!videoChanged && !micChanged) {
            applyScene(context)
            return
        }
        runCatching {
            if (stream.isOnPreview) stream.stopPreview()
            val videoOk = stream.prepareVideo(width, height, bitrate, fps, VIDEO_GOP_SECONDS)
            val audioOk = stream.prepareAudio(AUDIO_SAMPLE_RATE, AUDIO_STEREO, AUDIO_BITRATE)
            if (videoOk && audioOk) {
                preparedWidth = width
                preparedHeight = height
                preparedFps = fps
                preparedBitrate = bitrate
                applyPreferredMic(context)
            } else {
                Log.e(TAG, "syncConfig re-prepare failed video=$videoOk audio=$audioOk")
            }
            // Always restore preview, even if prepare failed, so the screen isn't
            // left black. Re-apply the scene since stopPreview drops GL textures.
            stream.startPreview(view)
            overlayRenderer.applyScene(Prefs.overlayScene(context))
        }.onFailure { Log.w(TAG, "syncConfig failed", it) }
    }

    /** Re-apply the saved overlay scene to the live pipeline. Called when returning
     *  from the overlay editor, since the engine is no longer recreated on the way
     *  back. Safe whether idle or streaming (the renderer swaps filters live). */
    fun applyScene(context: Context) {
        runCatching { overlayRenderer.applyScene(Prefs.overlayScene(context)) }
            .onFailure { Log.w(TAG, "applyScene failed", it) }
    }

    /** Apply a new video bitrate to the running stream immediately. Bitrate is the
     *  one quality setting RootEncoder can change mid-stream. No-op when not
     *  streaming; the value is then picked up by syncConfig / prepare next time. */
    fun setBitrate(kbps: Int) {
        if (!stream.isStreaming) return
        runCatching { stream.setVideoBitrateOnFly(kbps * 1000) }
            .onFailure { Log.w(TAG, "setBitrate failed", it) }
    }

    fun start(url: String) {
        val current = _state.value
        if (current is StreamState.Live ||
            current is StreamState.Connecting ||
            current is StreamState.Reconnecting
        ) return

        if (!stream.isOnPreview) {
            _state.value = StreamState.Error("Camera not ready")
            return
        }
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) {
            _state.value = StreamState.Error("No stream URL configured")
            return
        }

        // Force-stop any lingering stream from a previous aborted attempt.
        runCatching { if (stream.isStreaming) stream.stopStream() }

        stopRequested = false
        wantStreaming = true
        hasEverConnected = false
        lastStreamUrl = cleanUrl
        _state.value = StreamState.Connecting
        runCatching { stream.startStream(cleanUrl) }
            .onFailure { t ->
                Log.w(TAG, "startStream threw", t)
                _state.value = StreamState.Error(t.message ?: "Stream start failed")
                wantStreaming = false
            }
    }

    fun stop() {
        stopRequested = true
        wantStreaming = false
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting = false
        runCatching { if (stream.isStreaming) stream.stopStream() }
        _state.value = StreamState.Idle
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            powerManager?.removeThermalStatusListener(thermalListener)
        }
        stopRequested = true
        wantStreaming = false
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting = false
        dualCamera.release()
        overlayRenderer.clear()
        runCatching { if (stream.isStreaming) stream.stopStream() }
        if (stream.isOnPreview) stream.stopPreview()
        engineScope.cancel()
    }

    fun clearError() {
        if (_state.value is StreamState.Error) {
            stopRequested = true
            wantStreaming = false
            runCatching { if (stream.isStreaming) stream.stopStream() }
            _state.value = StreamState.Idle
        }
    }

    fun failConnectingTimeout() {
        if (_state.value === StreamState.Connecting) {
            stopRequested = true
            wantStreaming = false
            runCatching { if (stream.isStreaming) stream.stopStream() }
            _state.value = StreamState.Error(
                "Connection timed out. Check your stream URL and network.",
            )
        }
    }

    /** Launch the reconnect loop. Called from ConnectChecker when we detect an
     *  unexpected drop after we'd successfully gone live. Does nothing if already
     *  reconnecting. */
    private fun startReconnect() {
        if (isReconnecting) return
        val url = lastStreamUrl ?: return
        val maxSec = Prefs.maxReconnectMinutes(context) * 60
        isReconnecting = true
        reconnectJob = engineScope.launch {
            try {
                reconnectLoop(url, maxSec)
            } finally {
                isReconnecting = false
            }
        }
    }

    /** The retry loop. Two key design points learned the hard way:
     *
     *  1. **Wait on isAvailable as a StateFlow**, not on the edge-triggered onAvailable
     *     SharedFlow. `isAvailable.filter { it }.first()` returns immediately if network
     *     is currently up. The previous version waited for the next UP edge, which
     *     never came after the first one — so subsequent attempts ate the full 30s
     *     timeout before retrying.
     *
     *  2. **Per-attempt outcome via CompletableDeferred**, so the loop wakes the
     *     instant the ConnectChecker fires success or failure. The previous version
     *     polled state every 500ms for 10 seconds before deciding the attempt failed.
     *
     *  Result: the loop is responsive to both real-world events (network comes back,
     *  connection succeeds, connection fails) without polling or unnecessary waits.
     */
    private suspend fun reconnectLoop(url: String, maxSeconds: Int) {
        val startTime = SystemClock.elapsedRealtime()
        var attempt = 0

        while (engineScope.isActive && wantStreaming) {
            val elapsedSec = ((SystemClock.elapsedRealtime() - startTime) / 1000).toInt()
            if (maxSeconds > 0 && elapsedSec >= maxSeconds) {
                _state.value = StreamState.Error(
                    "Reconnect failed after ${maxSeconds / 60} min",
                )
                wantStreaming = false
                return
            }

            attempt++
            val secondsRemaining = if (maxSeconds == 0) Int.MAX_VALUE else (maxSeconds - elapsedSec)
            _state.value = StreamState.Reconnecting(attempt, secondsRemaining)

            // Step 1: wait until network is up. Returns immediately if it already is.
            val timeRemainingMs = if (maxSeconds > 0) {
                (maxSeconds - elapsedSec) * 1000L
            } else {
                Long.MAX_VALUE
            }
            val gotNetwork = withTimeoutOrNull(timeRemainingMs) {
                NetworkMonitor.isAvailable.filter { it }.first()
            }
            if (gotNetwork == null) continue  // outer loop hits maxSeconds check
            if (!wantStreaming) return

            // Step 2: small backoff. Two reasons:
            //  - Lets the OS finish bringing up DNS / routes after the network signal
            //  - Throttles us against the RTMP server if it's also recovering
            // Shorter on first few attempts (be aggressive), longer if we've been at
            // this a while (don't hammer).
            val backoffMs = if (attempt <= 3) 1000L else 3000L
            delay(backoffMs)
            if (!wantStreaming) return
            // Network might have dropped again during our backoff — go around if so.
            if (!NetworkMonitor.isAvailable.value) continue

            // Step 3: try to connect, with an outcome signal the checker fills in.
            val outcome = CompletableDeferred<Boolean>()
            currentAttemptOutcome = outcome
            Log.d(TAG, "reconnect attempt $attempt (elapsed ${elapsedSec}s)")
            runCatching {
                if (stream.isStreaming) stream.stopStream()
                stream.startStream(url)
            }.onFailure { t ->
                Log.w(TAG, "reconnect attempt $attempt threw", t)
                outcome.complete(false)
            }

            // Step 4: wait for the outcome. Cap at 15s in case neither callback fires
            // (e.g. RTMP server accepts the TCP socket then hangs without replying).
            val succeeded = withTimeoutOrNull(15_000L) { outcome.await() }
            currentAttemptOutcome = null
            when (succeeded) {
                true -> return  // state was set to Live by the checker, we're done
                false -> {
                    // Failed fast — loop will immediately try again. No long wait.
                    Log.d(TAG, "reconnect attempt $attempt failed, retrying")
                }
                null -> {
                    // Connect hung past 15s — give up on this attempt, retry.
                    Log.d(TAG, "reconnect attempt $attempt timed out, retrying")
                    runCatching { if (stream.isStreaming) stream.stopStream() }
                }
            }
        }
    }

    fun switchCamera() {
        if (isDualCamOn.value) {
            dualCamera.swap()
            mainFacingFront = !mainFacingFront
        } else {
            (stream.videoSource as? Camera2Source)?.switchCamera()
            mainFacingFront = !mainFacingFront
        }
        // Any camera reconfiguration drops the torch (either the camera's lantern
        // dies with the closed session, or our setTorchMode is killed when the new
        // session opens). Reset the icon to match.
        _isTorchOn.value = false
    }

    fun toggleDualCam() {
        // Fire-and-forget; the controller's StateFlow updates when the operation
        // actually completes, and the UI binds to that — no stale icon state.
        if (isDualCamOn.value) {
            // Disabling. If main is front-facing, the rear camera (which may have
            // had torch on) was on the PiP slot — it's about to be released, so
            // the torch dies with it and we have to reset the icon to match.
            // If main is rear, the main camera doesn't change, so torch persists.
            if (mainFacingFront && _isTorchOn.value) _isTorchOn.value = false
            dualCamera.disable()
        } else {
            // Enabling. Main camera doesn't change, just adds PiP. Torch on main
            // (if it was on) keeps running, so no state reset needed.
            // PiP must face opposite of main, otherwise two cameras try to open
            // the same physical camera and the concurrent API fails.
            dualCamera.enable(facingFront = !mainFacingFront)
        }
    }

    fun toggleTorch() {
        val newState = !_isTorchOn.value
        val openRear = openRearCamera()
        val ok = if (openRear != null) {
            // Common case: we hold the rear camera open (preview / streaming /
            // dual-cam). Toggle torch via that session.
            runCatching {
                if (newState) openRear.enableLantern() else openRear.disableLantern()
                true
            }.getOrElse {
                Log.w(TAG, "enableLantern failed", it)
                false
            }
        } else {
            // Niche case: main is front-facing and dual-cam is off, so no rear
            // camera is open by us. Fall back to the system torch API.
            TorchController.setTorch(context, newState)
        }
        if (ok) _isTorchOn.value = newState
    }

    /**
     * Returns whichever Camera2Source has the rear-facing camera open, if any.
     * Used by the torch toggle to target the right session. Logic:
     *  - Single cam, main is rear → main
     *  - Single cam, main is front → null (no rear cam open by us)
     *  - Dual cam, main is rear (PiP front) → main
     *  - Dual cam, main is front (PiP rear) → PiP
     */
    private fun openRearCamera(): Camera2Source? {
        return if (isDualCamOn.value) {
            if (!mainFacingFront) stream.videoSource as? Camera2Source
            else dualCamera.pipCameraOrNull
        } else {
            if (!mainFacingFront) stream.videoSource as? Camera2Source else null
        }
    }

    fun toggleCameraOff() {
        val gl = stream.getGlInterface()
        if (_isCameraOff.value) {
            runCatching {
                gl.unMuteVideo()
                blackFilter?.let { gl.removeFilter(it) }
                blackFilter = null
                dualCamera.setPipVisible(true)
            }
                .onSuccess { _isCameraOff.value = false }
                .onFailure { Log.w(TAG, "camera-on failed", it) }
        } else {
            runCatching {
                // muteVideo blacks the encoder feed for the main camera, and the
                // BlackFilterRender blacks the on-device preview. Need to hide PiP
                // too — otherwise the corner cam still shows in the broadcast on
                // top of an otherwise-black frame.
                gl.muteVideo()
                val filter = BlackFilterRender()
                gl.addFilter(filter)
                blackFilter = filter
                dualCamera.setPipVisible(false)
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
                dualCamera.setPipVisible(true)
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
                // PiP filter has a higher index in the GL chain, so it draws on
                // top of our BRB black. Hide it explicitly so the streamer's whole
                // broadcast goes to the BRB screen, not just the main camera area.
                dualCamera.setPipVisible(false)
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
                _thermalNotice.value = "Phone warming, bitrate dropped to ${target / 1000} kbps"
            }
            PowerManager.THERMAL_STATUS_SEVERE -> {
                val target = (configuredBitrate * 0.4).toInt()
                runCatching { stream.setVideoBitrateOnFly(target) }
                _thermalNotice.value =
                    "Phone hot — bitrate dropped to ${target / 1000} kbps. " +
                        "Stealth mode (screen off) helps it cool."
            }
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                _thermalNotice.value = "Phone too hot, stream stopped"
                stop()
            }
            else -> Unit
        }
    }
}
