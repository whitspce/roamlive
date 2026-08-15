package dev.whitespc.roam.streaming

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import dev.whitespc.roam.diagnostics.RoamLog as Log
import android.view.MotionEvent
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import android.graphics.BitmapFactory
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.BlackFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.streamclient.GenericStreamClient
import com.pedro.library.util.streamclient.SrtStreamClient
import com.pedro.library.view.OpenGlView
import dev.whitespc.roam.NetworkMonitor
import dev.whitespc.roam.audio.AudioMeter
import dev.whitespc.roam.audio.MicDevice
import dev.whitespc.roam.audio.MicDevices
import dev.whitespc.roam.audio.MicPreviewReader
import dev.whitespc.roam.obs.ObsBrbPrivacyLatch
import dev.whitespc.roam.obs.ObsClient
import dev.whitespc.roam.obs.ObsConnectionState
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.streaming.overlay.OverlayRenderer
import dev.whitespc.roam.streaming.overlay.Scene
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.net.Network
import android.os.Environment
import android.os.StatFs
import com.pedro.library.base.recording.RecordController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.min

private const val TAG = "RoamStreamingEngine"

private const val VIDEO_GOP_SECONDS = 2
private const val AUDIO_BITRATE = 128_000
// Use the native Android capture rate to avoid timestamp drift when a device
// supplies 48 kHz frames despite a lower requested rate.
private const val AUDIO_SAMPLE_RATE = 48_000
private const val AUDIO_STEREO = true

// Local recording storage guards: refuse to start under 2 GB free, stop the
// recording (never the stream) if free space falls under 500 MB mid-stream.
private const val RECORD_MIN_START_BYTES = 2_000_000_000L
private const val RECORD_MIN_KEEP_BYTES = 500_000_000L
private const val RECORD_STORAGE_CHECK_INTERVAL_MS = 60_000L
private const val INITIAL_CONNECTION_TIMEOUT_MS = 30_000L

// SRT latency is the receiver buffer and sender retransmission window. Two
// seconds absorbs short cellular disruptions. The handshake uses the larger
// latency requested by either peer.
private const val SRT_LATENCY_MS = 2_000

// Critical heat: how long auto-stealth gets to cool the phone before we stop
// the stream as the last resort.
private const val CRITICAL_HEAT_GRACE_MS = 60_000L

class StreamingEngine(private val context: Context) {
    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isCameraOff = MutableStateFlow(false)
    val isCameraOff: StateFlow<Boolean> = _isCameraOff.asStateFlow()

    private val _isBrb = MutableStateFlow(false)
    val isBrb: StateFlow<Boolean> = _isBrb.asStateFlow()

    private val _obsBrbPrivacyActive = MutableStateFlow(false)
    val obsBrbPrivacyActive: StateFlow<Boolean> = _obsBrbPrivacyActive.asStateFlow()
    private val obsBrbPrivacyLatch = ObsBrbPrivacyLatch()
    private var obsBrbEffectsActive = false
    private var muteBeforeObsBrb = false
    private var cameraOffBeforeObsBrb = false

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    private val _thermalNotice = MutableStateFlow<String?>(null)
    val thermalNotice: StateFlow<String?> = _thermalNotice.asStateFlow()

    /** Requests stealth mode at critical heat to reduce display load. The UI
     *  consumes the request so a later manual exit remains effective. */
    private val _stealthRequested = MutableStateFlow(false)
    val stealthRequested: StateFlow<Boolean> = _stealthRequested.asStateFlow()

    fun consumeStealthRequest() {
        _stealthRequested.value = false
    }

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        handleThermalChange(status)
    }

    @Volatile
    private var stopRequested = false

    // Reconnect only after a successful live connection. Initial failures stay
    // visible so the user can correct the destination or credential.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var wantStreaming = false
    private var hasEverConnected = false
    private var lastStreamUrl: String? = null
    private var reconnectJob: Job? = null
    private var initialConnectionWatchdogJob: Job? = null
    @Volatile
    private var isReconnecting = false
    /** Tracks the outcome of the CURRENT reconnect attempt. The ConnectChecker
     *  completes this when either onConnectionSuccess (true) or onConnectionFailed
     *  (false) fires - so the loop wakes immediately instead of polling state. */
    private var currentAttemptOutcome: CompletableDeferred<Boolean>? = null

    // Adaptive bitrate. The controller owns the down-fast/up-slow steering; the
    // engine owns the CEILING it may climb to: min(user bitrate, thermal cap).
    // That split is what keeps auto bitrate and thermal protection from fighting
    // over the encoder. Toggleable in Settings (default on); when off, the
    // engine falls back to fixed-bitrate behaviour.
    private var autoBitrateEnabled = Prefs.autoBitrateEnabled(context)
    private var thermalCapBps: Int? = null
    private val bitrateController = AdaptiveBitrateController { bps ->
        runCatching { if (stream.isStreaming) stream.setVideoBitrateOnFly(bps) }
            .onFailure { Log.w(TAG, "auto bitrate apply failed", it) }
    }

    /** The most the encoder is allowed to push right now: the user's configured
     *  bitrate, further capped by thermal throttling when the phone is hot. */
    private fun effectiveMaxBitrateBps(): Int =
        min(Prefs.videoBitrateKbps(context) * 1000, thermalCapBps ?: Int.MAX_VALUE)

    // Link-health derivation. Shares the 1Hz onNewBitrate tick with auto
    // bitrate; counts consecutive strained seconds (congestion, fresh dropped
    // frames, or SRT packet loss) to grade Good / Weak / Bad for the NET pill.
    private var lastDroppedVideoFrames = 0L
    private var lastSrtPacketsLost = 0
    private var strainedTicks = 0

    /** SRT client used for latency and loss stats. RootEncoder does not expose
     *  protocol clients, so guarded reflection degrades to default latency and
     *  generic health metrics if the internal field changes. */
    private val srtStreamClient: SrtStreamClient? by lazy {
        runCatching {
            val field = GenericStreamClient::class.java.getDeclaredField("srtClient")
            field.isAccessible = true
            field.get(stream.getStreamClient()) as? SrtStreamClient
        }.onFailure { Log.w(TAG, "srt client access failed", it) }.getOrNull()
    }

    private fun isSrtUrl(url: String?): Boolean =
        url?.startsWith("srt://", ignoreCase = true) == true

    /** Pending stop-at-critical-heat timer; cancelled if the phone cools. */
    private var criticalStopJob: Job? = null

    /** Periodic thermal headroom logger. Headroom is a continuous signal where
     *  values near 1 indicate that throttling is imminent. */
    private var headroomLoggerJob: Job? = null

    private fun applyBitrateCeiling() {
        val effective = effectiveMaxBitrateBps()
        if (autoBitrateEnabled) {
            bitrateController.setCeiling(effective)
        } else {
            runCatching { if (stream.isStreaming) stream.setVideoBitrateOnFly(effective) }
                .onFailure { Log.w(TAG, "bitrate ceiling apply failed", it) }
        }
    }

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            // Redact: the URL contains the stream KEY, and the diagnostic log
            // file is meant to be shareable. Keep scheme + host for debugging.
            Log.d(TAG, "connection started: ${dev.whitespc.roam.diagnostics.LogStore.redactStreamUrl(url)}")
        }

        override fun onConnectionSuccess() {
            handleConnectionSuccess()
        }

        override fun onConnectionFailed(reason: String) {
            handleConnectionFailed(reason)
        }

        override fun onNewBitrate(bitrate: Long) {
            if (stopRequested) return
            val client = stream.getStreamClient()
            // Congestion = the client's send cache is filling (frames queueing
            // faster than the socket drains them; 20% full counts). Feeds both
            // the adaptive-bitrate loop and the health grade.
            val congested = runCatching { client.hasCongestion(20f) }.getOrDefault(false)
            if (autoBitrateEnabled && stream.isStreaming) {
                bitrateController.onBitrateMeasured(bitrate, congested)
            }
            val dropped = runCatching { client.getDroppedVideoFrames() }.getOrDefault(0L)
            val droppedDelta = dropped - lastDroppedVideoFrames
            lastDroppedVideoFrames = dropped
            // On SRT, lost-then-retransmitted packets show strain EARLIER than
            // dropped frames do: loss appears as soon as the network degrades,
            // drops only once the latency budget is exhausted.
            val srtLostDelta = if (isSrtUrl(lastStreamUrl)) {
                val lost = runCatching { srtStreamClient?.getPacketsLost() ?: 0 }.getOrDefault(0)
                (lost - lastSrtPacketsLost).also { lastSrtPacketsLost = lost }
            } else {
                0
            }
            strainedTicks =
                if (congested || droppedDelta > 0 || srtLostDelta > 0) strainedTicks + 1 else 0
            val health = when {
                strainedTicks == 0 -> LinkHealth.Good
                strainedTicks < 3 -> LinkHealth.Weak
                else -> LinkHealth.Bad
            }
            val current = _state.value
            if (current is StreamState.Live) {
                _state.value = current.copy(bitrateBps = bitrate, health = health)
            }
        }

        override fun onDisconnect() {
            handleDisconnect()
        }

        override fun onAuthError() {
            handleAuthError()
        }

        override fun onAuthSuccess() {
            Log.d(TAG, "auth success")
        }
    }

    val stream: GenericStream = GenericStream(context, connectChecker).apply {
        setVideoCodec(VideoCodec.H264)
        setAudioCodec(AudioCodec.AAC)
    }

    /** Connection callbacks race user Stop on protocol threads. Serialising
     *  them on the engine monitor prevents a late success from re-entering
     *  Live and starting a hidden recording after cleanup already won. */
    @Synchronized
    private fun handleConnectionSuccess() {
        Log.d(TAG, "connection success")
        if (stopRequested) {
            runCatching { if (stream.isStreaming) stream.stopStream() }
            return
        }
        initialConnectionWatchdogJob?.cancel()
        initialConnectionWatchdogJob = null
        hasEverConnected = true
        currentAttemptOutcome?.complete(true)
        _state.value = StreamState.Live(0, connectedCount = 1, totalCount = 1)
        if (autoBitrateEnabled) bitrateController.reset(effectiveMaxBitrateBps())
        lastDroppedVideoFrames = 0
        lastSrtPacketsLost = 0
        strainedTicks = 0
        runCatching { stream.getStreamClient().resetDroppedVideoFrames() }
        if (Prefs.recordWhileStreaming(context) && !stream.isRecording) {
            startRecordSafe()
        }
        engineScope.launch(Dispatchers.Main) { restoreBroadcastVisuals() }
    }

    @Synchronized
    private fun handleConnectionFailed(reason: String) {
        Log.w(TAG, "connection failed: $reason")
        if (stopRequested) return
        if (isReconnecting) {
            currentAttemptOutcome?.complete(false)
            return
        }
        if (wantStreaming && hasEverConnected) {
            startReconnect()
            return
        }
        // Transport libraries and remote servers control this string. Keep it
        // in the redacted diagnostic log, never on the user's screen where it
        // could expose a URL credential or render hostile control text.
        terminateWithError("Could not connect to the stream destination")
    }

    @Synchronized
    private fun handleDisconnect() {
        Log.d(TAG, "disconnect")
        if (stopRequested || isReconnecting) return
        if (wantStreaming && hasEverConnected) startReconnect()
    }

    @Synchronized
    private fun handleAuthError() {
        Log.w(TAG, "auth error")
        if (stopRequested) return
        currentAttemptOutcome?.complete(false)
        terminateWithError("Authentication failed")
    }

    /** Pin the GL pipeline to landscape after every prepareVideo call because
     *  RootEncoder reapplies sensor-derived orientation during preparation. */
    private fun forceLandscapePipeline() {
        runCatching {
            val gl = stream.getGlInterface()
            gl.forceOrientation(OrientationForced.LANDSCAPE)
            // Forced landscape resets rotation to zero. Restore the 270-degree
            // rotation used by RootEncoder's derived-landscape path.
            gl.setCameraOrientation(270)
        }
    }

    /** Log the GL interface's live orientation state using guarded reflection. */
    private fun logOrientationState(where: String) {
        runCatching {
            val gl = stream.getGlInterface()
            fun field(name: String): Any? = runCatching {
                gl.javaClass.getDeclaredField(name)
                    .apply { isAccessible = true }
                    .get(gl)
            }.getOrNull() ?: "?"
            val displayRotation = runCatching {
                @Suppress("DEPRECATION")
                (context.getSystemService(android.content.Context.WINDOW_SERVICE)
                    as android.view.WindowManager).defaultDisplay.rotation
            }.getOrNull() ?: "?"
            Log.d(
                TAG,
                "orientation[$where] forced=${field("orientationForced")} " +
                    "isPortrait=${field("isPortrait")} " +
                    "isPortraitPreview=${field("isPortraitPreview")} " +
                    "auto=${field("autoHandleOrientation")} " +
                    "shouldHandle=${field("shouldHandleOrientation")} " +
                    "previewOrientation=${field("previewOrientation")} " +
                    "streamOrientation=${field("streamOrientation")} " +
                    "displayRotation=$displayRotation",
            )
        }
    }

    private var blackFilter: BlackFilterRender? = null

    private var brbBlackFilter: BlackFilterRender? = null
    /** Foreground filter shown over the BRB black layer - either text or a custom
     *  full-frame image, depending on whether the user set a BRB image in Settings. */
    private var brbForegroundFilter: BaseFilterRender? = null
    private var muteBeforeBrb = false

    private val dualCamera = DualCameraController(context, stream)
    val isDualCamOn: StateFlow<Boolean> = dualCamera.isOn

    private val tokenSource = dev.whitespc.roam.streaming.overlay.TokenSource(context)

    private val _locationForegroundRequired = MutableStateFlow(
        sceneNeedsGrantedLocation(Prefs.overlayScene(context)),
    )
    internal val locationForegroundRequired: StateFlow<Boolean> =
        _locationForegroundRequired.asStateFlow()

    /** Monotonic-clock millis (elapsedRealtime) at which the current go-live
     *  attempt started. Monotonic so an NTP clock correction mid-stream can't
     *  jump the `{stream_time}` token. Set in [start]; preserved across
     *  reconnects so the token counts continuously; cleared on a real stop.
     *  Null means "not streaming." */
    private var liveStartMs: Long? = null

    private val overlayRenderer = OverlayRenderer(
        context = context,
        stream = stream,
        tokenSource = tokenSource,
        snapshotProvider = {
            tokenSource.snapshot(_state.value, currentStreamUptimeSec())
        },
    )

    private fun currentStreamUptimeSec(): Int? =
        liveStartMs?.let { ((SystemClock.elapsedRealtime() - it) / 1000L).toInt() }
    /** Tracks which way the main camera (the encoder's video source) is currently
     *  facing. Camera2Source defaults to BACK, so we start at false (back). Flipped
     *  whenever switchCamera runs. Needed so we can enable PiP with the OPPOSITE
     *  facing - two cameras with the same facing can't be opened concurrently. */
    private var mainFacingFront = false

    private var isPrepared = false

    // The OpenGlView the encoder is currently previewing into. Kept so we can
    // re-prepare the encoder (resolution/fps change) while idle without the UI
    // having to hand the view back. Set on every attachPreview.
    private var currentView: OpenGlView? = null

    // Encoder preparation state used to detect settings that require an idle
    // re-prepare.
    private var preparedWidth = 0
    private var preparedHeight = 0
    private var preparedFps = 0
    private var preparedBitrate = 0
    private var preparedMicName: String? = null
    private var preparedMicType: Int? = null

    /** True while we're routing audio through a Bluetooth SCO link (the only
     *  way to capture from a BT headset mic on Android). Tracked so a switch
     *  to a non-BT mic can release SCO cleanly, and release() can tear it
     *  down on engine shutdown. */
    private var scoActive = false

    /** Source-agnostic audio level state for the HUD meter. Fed by the
     *  streaming source's CustomAudioEffect when live, and by [micPreviewReader]
     *  when not - so the meter works during setup as well as during a stream. */
    private val audioMeter = AudioMeter()
    val audioLevel: StateFlow<Float> = audioMeter.level

    /** Pre-live mic reader: opens its own AudioRecord so the meter has data to
     *  show before the encoder is running. Started/stopped via [refreshMeter]
     *  based on (a) the meter setting and (b) whether a stream is active. */
    private val micPreviewReader = MicPreviewReader { audioMeter.feed(it) }
    private var meterDesired = false

    /** True when the user wants the meter on but the selected mic is Bluetooth
     *  and we're not yet streaming. Pre-live BT metering is intentionally
     *  skipped (SCO routing belongs to the streaming path), so the HUD shows
     *  an honest note instead of bars that would be misleadingly reading the
     *  built-in mic. Cleared once streaming starts. */
    private val _meterPreLiveUnavailable = MutableStateFlow(false)
    val meterPreLiveUnavailable: StateFlow<Boolean> = _meterPreLiveUnavailable.asStateFlow()

    init {
        runCatching {
            (stream.audioSource as? MicrophoneSource)?.setAudioEffect(audioMeter.Effect())
        }.onFailure { Log.w(TAG, "audio meter install failed", it) }
    }

    init {
        RecordingFinalizer.recoverPending(context, recordingsDir())
        powerManager?.addThermalStatusListener(thermalListener)
        // OBS BRB privacy is engine-owned rather than Compose-owned. Socket
        // callbacks still drive this collector while the display is off, and
        // the latch refuses to unmute on an unconfirmed disconnect.
        engineScope.launch {
            combine(ObsClient.state, ObsClient.currentScene) { state, scene -> state to scene }
                .collect { (state, scene) ->
                    withContext(Dispatchers.Main) {
                        refreshObsBrbPrivacy(state, scene)
                    }
                }
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
        // A make-before-break network handoff keeps availability true while the
        // current socket remains bound to the old network. Reconnect when the
        // default network identity changes.
        engineScope.launch {
            var previous: Network? = null
            NetworkMonitor.defaultNetwork.collect { current ->
                val old = previous
                previous = current
                // current == null (total loss) is the loss watcher's job, and a
                // first value after subscribe (old == null) is not a switch.
                if (old != null && current != null && current != old &&
                    wantStreaming && _state.value is StreamState.Live
                ) {
                    Log.d(TAG, "default network switched ($old -> $current), restarting stream")
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
            // prepareVideo just reverted the orientation pin (see
            // forceLandscapePipeline); re-assert before anything renders.
            forceLandscapePipeline()
            logOrientationState("post-prepare")
            isPrepared = true
            preparedWidth = width
            preparedHeight = height
            preparedFps = fps
            preparedBitrate = bitrate
            applyPreferredMic(context)
            setMicGain(Prefs.micGain(context))
        }
        if (stream.isOnPreview) stream.stopPreview()
        stream.startPreview(view)
        applyStabilization()
        // Rebuild filters after every surface attach because their GPU textures
        // do not survive surface recreation. Web overlays reload as a result.
        restoreBroadcastVisuals()
    }

    /**
     * Rebuild every GL-side visual the broadcast depends on, from prefs and the
     * engine's own state flags: either the BRB takeover (scene stays cleared
     * underneath it, same as toggleBrb), or the overlay scene plus the
     * camera-off black layer if the camera is muted.
     *
     * Called after surface recreation and stream reconnects because filters lose
     * their GPU textures while the engine state remains active.
     */
    private fun restoreBroadcastVisuals() {
        val gl = stream.getGlInterface()
        if (_isBrb.value) {
            // Same construction as toggleBrb's enter path. Remove stale refs
            // first; they may be dead textures after a GL restart.
            runCatching {
                brbForegroundFilter?.let { gl.removeFilter(it) }
                brbBlackFilter?.let { gl.removeFilter(it) }
                val black = BlackFilterRender()
                val foreground: BaseFilterRender =
                    Prefs.brbImagePath(context)
                        ?.let { createBrbImageFilter(it) }
                        ?: createBrbTextFilter(Prefs.brbText(context))
                gl.addFilter(0, black)
                gl.addFilter(1, foreground)
                brbBlackFilter = black
                brbForegroundFilter = foreground
                dualCamera.setPipVisible(false)
            }.onFailure {
                Log.w(TAG, "BRB restore failed", it)
                if (obsBrbEffectsActive && wantStreaming) {
                    terminateWithError("OBS BRB camera protection failed; stream stopped")
                }
            }
        } else {
            applyOverlayScene(Prefs.overlayScene(context))
            // The BlackFilterRender doesn't survive the GL restart, but our
            // _isCameraOff flag does. Without this, a user who backgrounds
            // with camera off resumes with the camera visibly back on. Black
            // goes at index 0 so the just-applied overlays stay on top (see
            // toggleCameraOff).
            if (_isCameraOff.value) {
                runCatching {
                    blackFilter?.let { gl.removeFilter(it) }
                    val fresh = BlackFilterRender()
                    gl.addFilter(0, fresh)
                    blackFilter = fresh
                    dualCamera.setPipVisible(false)
                }.onFailure {
                    Log.w(TAG, "camera-off restore failed", it)
                    if (obsBrbEffectsActive && wantStreaming) {
                        terminateWithError("OBS BRB camera protection failed; stream stopped")
                    }
                }
            }
        }
    }

    fun detachPreview() {
        if (stream.isOnPreview) stream.stopPreview()
    }

    /** Apply the preferred mic when present and track it for configuration sync.
     *  Bluetooth headset microphones require an active SCO route. */
    private fun applyPreferredMic(context: Context) {
        val name = Prefs.micDeviceName(context)
        val type = Prefs.micDeviceType(context)
        val micDevice = MicDevices.find(context, name, type)
        routeBluetoothSco(context, micDevice)
        // BT_SCO routing only takes effect on AudioRecord.AudioSource.VOICE_COMMUNICATION
        // (per Android's own setCommunicationDevice docs); MIC silently ignores the
        // routing. Switch the underlying source accordingly. The AudioRecord is
        // created at MicrophoneSource.start() time, so this matters most pre-live.
        val want = if (micDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        runCatching {
            val mic = stream.audioSource as? MicrophoneSource
            if (mic != null && mic.audioSource != want) {
                Log.d(TAG, "switching MicrophoneSource.audioSource ${mic.audioSource}->$want")
                mic.audioSource = want
            }
        }.onFailure { Log.w(TAG, "setAudioSource failed", it) }
        if (micDevice != null) {
            runCatching {
                (stream.audioSource as? MicrophoneSource)?.setPreferredDevice(micDevice.info)
            }.onFailure { Log.w(TAG, "setPreferredDevice failed", it) }
        }
        preparedMicName = name
        preparedMicType = type
    }

    /** Activate or release the Bluetooth SCO link based on whether [device] is a
     *  BT headset. SCO is the bidirectional audio profile (mono, voice codec) -
     *  the ONLY profile that carries the BT mic's audio back to the phone.
     *  A2DP, which is what plays music to the headset, doesn't have a mic path.
     *
     *  API 31+ uses [AudioManager.setCommunicationDevice]; pre-31 falls back to
     *  the deprecated SCO calls. Pre-31 also needs MODE_IN_COMMUNICATION for
     *  the routing to actually take effect; restoring MODE_NORMAL on release. */
    private fun routeBluetoothSco(context: Context, device: MicDevice?) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val wantSco = device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        if (wantSco == scoActive) return
        runCatching {
            if (wantSco) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val target = device.info
                    // Log available communication devices to diagnose routing
                    // failures without including user-defined device names.
                    val available = am.availableCommunicationDevices
                    Log.d(
                        TAG,
                        "SCO route: target id=${target.id} type=${target.type}; available=${
                                available.joinToString { "id=${it.id} type=${it.type}" }
                            }",
                    )
                    val matched = available.firstOrNull { it.id == target.id }
                        ?: available.firstOrNull { it.type == target.type }
                    if (matched == null) {
                        Log.w(TAG, "SCO route: target not in availableCommunicationDevices")
                    } else {
                        val ok = am.setCommunicationDevice(matched)
                        Log.d(
                            TAG,
                            "SCO route: setCommunicationDevice id=${matched.id} returned=$ok " +
                                "active-after=${am.communicationDevice?.id}",
                        )
                    }
                } else {
                    @Suppress("DEPRECATION")
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION")
                    am.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = true
                    Log.d(TAG, "SCO route: legacy startBluetoothSco invoked")
                }
                scoActive = true
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice()
                    Log.d(TAG, "SCO route: clearCommunicationDevice")
                } else {
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = false
                    @Suppress("DEPRECATION")
                    am.stopBluetoothSco()
                    @Suppress("DEPRECATION")
                    am.mode = AudioManager.MODE_NORMAL
                    Log.d(TAG, "SCO route: legacy stopBluetoothSco")
                }
                scoActive = false
            }
        }.onFailure { Log.w(TAG, "BT SCO routing failed (wantSco=$wantSco)", it) }
    }

    /** Apply the saved mic setting while idle or live, including the SCO route. */
    fun applyMicDevicePref(context: Context) {
        // Detect the mid-stream Bluetooth case before re-routing. The
        // streaming AudioRecord is locked to its initial audio source, so the
        // switch to VOICE_COMMUNICATION inside applyPreferredMic doesn't take
        // effect on the currently-running stream - we surface a banner so the
        // user knows to restart.
        val pickedIsBt = MicDevices
            .find(context, Prefs.micDeviceName(context), Prefs.micDeviceType(context))
            ?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val midStream = wantStreaming
        applyPreferredMic(context)
        if (pickedIsBt && midStream) {
            _micNotice.value =
                "Bluetooth mic takes effect on the next stream. Stop and " +
                    "restart to switch."
        } else {
            _micNotice.value = null
        }
        // When idle, the preview reader needs to re-route to the new device
        // (or hand off to the BT-unavailable state). No-op while streaming.
        refreshMeter()
    }

    /** Live-safe mic input gain. 1.0 is unity, lower is quieter, higher
     *  amplifies. RootEncoder applies it to the AudioRecord stream so it
     *  takes effect immediately whether the stream is live or idle. */
    fun setMicGain(value: Float) {
        runCatching {
            (stream.audioSource as? MicrophoneSource)?.microphoneVolume = value
        }.onFailure { Log.w(TAG, "setMicGain failed", it) }
    }

    /** Toggle the HUD audio meter. When on we run the pre-live mic reader so
     *  the bar shows activity before the stream starts; once streaming, the
     *  reader stops and the encoder-side effect tap takes over. Idempotent. */
    fun setAudioMeterDesired(desired: Boolean) {
        if (meterDesired == desired) return
        meterDesired = desired
        refreshMeter()
    }

    private fun refreshMeter() {
        // Use [wantStreaming] (set synchronously at start()) rather than
        // stream.isStreaming (set async after the encoder actually connects)
        // so refreshMeter() called from inside start() sees the right state.
        val shouldPreview = meterDesired && !wantStreaming
        if (!shouldPreview) {
            micPreviewReader.stop()
            _meterPreLiveUnavailable.value = false
            if (!meterDesired) audioMeter.reset()
            return
        }
        // Resolve the user's picked mic. Bluetooth is skipped pre-live (see
        // [meterPreLiveUnavailable]); for everything else we hand the device
        // to the reader so the meter reads from the right input.
        val name = Prefs.micDeviceName(context)
        val type = Prefs.micDeviceType(context)
        val picked = MicDevices.find(context, name, type)
        if (picked != null && picked.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            micPreviewReader.stop()
            audioMeter.reset()
            _meterPreLiveUnavailable.value = true
            return
        }
        _meterPreLiveUnavailable.value = false
        micPreviewReader.start(picked?.info)
    }

    /** Apply settings that require an idle encoder rebuild. Bitrate changes use
     *  [setBitrate], while overlays can be replaced without rebuilding. */
    fun syncConfig(context: Context) {
        ObsClient.setRoamStreamingWanted(
            Prefs.obsSyncStreaming(context) && wantStreaming,
        )
        refreshObsBrbPrivacy(ObsClient.state.value, ObsClient.currentScene.value)
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
            // prepareVideo reverts the orientation pin; re-assert (see
            // forceLandscapePipeline).
            forceLandscapePipeline()
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
            applyOverlayScene(Prefs.overlayScene(context))
        }.onFailure { Log.w(TAG, "syncConfig failed", it) }
    }

    /** Reapply the saved overlay scene while idle or live.
     *
     *  No-op while BRB is active - overlays are deliberately hidden during BRB,
     *  and toggleBrb will re-apply the scene (picking up any editor changes)
     *  when the user exits. */
    fun applyScene(context: Context) {
        if (_isBrb.value) return
        runCatching { applyOverlayScene(Prefs.overlayScene(context)) }
            .onFailure { Log.w(TAG, "applyScene failed", it) }
    }

    private fun applyOverlayScene(scene: Scene) {
        _locationForegroundRequired.value = sceneNeedsGrantedLocation(scene)
        overlayRenderer.applyScene(scene)
    }

    private fun sceneNeedsGrantedLocation(scene: Scene): Boolean =
        tokenSource.hasLocationPermission() && scene.hasVisibleGpsOverlay()

    /** Apply a new user-chosen bitrate to the running stream. With auto bitrate
     *  on, the value becomes the new ceiling: lowering it clamps immediately,
     *  raising it lets the controller climb there over the next seconds. With
     *  auto bitrate off, it applies directly (thermal cap still wins). No-op when
     *  not streaming; the value is then picked up by syncConfig / prepare. */
    fun setBitrate(kbps: Int) {
        if (!stream.isStreaming) return
        val effective = min(kbps * 1000, thermalCapBps ?: Int.MAX_VALUE)
        if (autoBitrateEnabled) {
            bitrateController.setCeiling(effective)
        } else {
            runCatching { stream.setVideoBitrateOnFly(effective) }
                .onFailure { Log.w(TAG, "setBitrate failed", it) }
        }
    }

    /** Toggle auto bitrate, live-safe. Turning it on mid-stream starts steering
     *  from the current ceiling; turning it off restores the fixed configured
     *  bitrate (still under any active thermal cap). */
    fun setAutoBitrate(enabled: Boolean) {
        autoBitrateEnabled = enabled
        if (!stream.isStreaming) return
        if (enabled) {
            bitrateController.reset(effectiveMaxBitrateBps())
        } else {
            runCatching { stream.setVideoBitrateOnFly(effectiveMaxBitrateBps()) }
                .onFailure { Log.w(TAG, "fixed bitrate restore failed", it) }
        }
    }

    // ---- Local recording -------------------------------------------------
    // A copy of the broadcast saved on the phone, riding the SAME encoders as
    // the stream (so it costs storage I/O, not a second encode). Because the
    // encoders stay alive while the protocol client reconnects, the recording
    // keeps capturing right through a dropout: the gap viewers saw is not in
    // the local file. Failure policy is one-directional: recording problems
    // stop the RECORDING, never the stream.

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** One-line notices about recording (refused start, storage stop). Shown
     *  by the UI like the thermal banner; null when there's nothing to say. */
    private val _recordNotice = MutableStateFlow<String?>(null)
    val recordNotice: StateFlow<String?> = _recordNotice.asStateFlow()

    fun dismissRecordNotice() {
        _recordNotice.value = null
    }

    /** One-line notices about mic changes that the user should know about but
     *  the engine handled silently (e.g. mid-stream Bluetooth pick that needs
     *  the next stream to take effect). Same shape as [recordNotice]. */
    private val _micNotice = MutableStateFlow<String?>(null)
    val micNotice: StateFlow<String?> = _micNotice.asStateFlow()

    fun dismissMicNotice() {
        _micNotice.value = null
    }

    private var recordingWatchdogJob: Job? = null

    /** Path of the file RootEncoder is currently writing to. Cleared after we
     *  finalize it into public Movies, so a failed/abandoned recording on the
     *  next start can be detected and not double-finalized. */
    @Volatile
    private var currentRecordPath: String? = null

    private val recordListener = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {
            Log.d(TAG, "record status: $status")
            when (status) {
                RecordController.Status.RECORDING -> _isRecording.value = true
                RecordController.Status.STOPPED -> _isRecording.value = false
                else -> Unit
            }
        }

        override fun onError(e: Exception?) {
            Log.w(TAG, "record error", e)
            recordingWatchdogJob?.cancel()
            recordingWatchdogJob = null
            runCatching { if (stream.isRecording) stream.stopRecord() }
            _isRecording.value = false
            currentRecordPath?.let { RecordingFinalizer.finishRecording(context, it) }
            currentRecordPath = null
            _recordNotice.value = "Recording failed and stopped. The stream is not affected."
        }
    }

    /** App-private Movies dir: no permissions needed on any Android version.
     *  Reachable at Android/data/dev.whitespc.roam/files/Movies via USB or the
     *  Files app. Falls back to internal storage if external is unavailable. */
    private fun recordingsDir(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir

    private fun startRecordSafe() {
        if (stream.isRecording) return
        val dir = recordingsDir()
        dir.mkdirs()
        val freeBytes = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
        if (freeBytes < RECORD_MIN_START_BYTES) {
            _recordNotice.value =
                "Not recording: needs at least 2 GB of free storage. Streaming anyway."
            return
        }
        val name = "roam-" +
            SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + "-" +
            UUID.randomUUID().toString().take(8) + ".mp4"
        val path = File(dir, name).absolutePath
        currentRecordPath = path
        RecordingFinalizer.markRecording(path)
        runCatching { stream.startRecord(path, listener = recordListener) }
            .onSuccess {
                if (currentRecordPath == path) startRecordingStorageWatchdog()
            }
            .onFailure {
                if (currentRecordPath == path) currentRecordPath = null
                RecordingFinalizer.abandonRecording(path)
                runCatching { File(path).delete() }
                Log.w(TAG, "startRecord failed", it)
                _recordNotice.value = "Couldn't start recording. The stream is not affected."
            }
    }

    private fun stopRecordSafe() {
        recordingWatchdogJob?.cancel()
        recordingWatchdogJob = null
        runCatching { if (stream.isRecording) stream.stopRecord() }
            .onFailure { Log.w(TAG, "stopRecord failed", it) }
        _isRecording.value = false
        val path = currentRecordPath
        currentRecordPath = null
        if (path != null) RecordingFinalizer.finishRecording(context, path)
    }

    /** Independent of protocol bitrate callbacks, so it keeps protecting disk
     *  space while the stream client is disconnected and recording continues. */
    private fun startRecordingStorageWatchdog() {
        recordingWatchdogJob?.cancel()
        recordingWatchdogJob = engineScope.launch {
            while (isActive && currentRecordPath != null) {
                delay(RECORD_STORAGE_CHECK_INTERVAL_MS)
                val path = currentRecordPath ?: return@launch
                val freeBytes = runCatching {
                    StatFs(File(path).parentFile?.absolutePath ?: return@runCatching 0L)
                        .availableBytes
                }.getOrDefault(0L)
                if (freeBytes < RECORD_MIN_KEEP_BYTES) {
                    stopRecordSafe()
                    _recordNotice.value =
                        "Recording stopped: storage almost full. The stream keeps going."
                    return@launch
                }
            }
        }
    }

    /** Settings toggle, live-safe: turning it on mid-stream starts recording
     *  now; off stops it. When idle this is a no-op; the pref is read at the
     *  next go-live. */
    fun setRecordWhileStreaming(enabled: Boolean) {
        if (!stream.isStreaming) return
        if (enabled) startRecordSafe() else stopRecordSafe()
    }

    @Synchronized
    fun start(url: String) {
        val current = _state.value
        if (current is StreamState.Live ||
            current is StreamState.Connecting ||
            current is StreamState.Reconnecting
        ) return

        if (!stream.isOnPreview) {
            terminateWithError("Camera not ready")
            return
        }
        // Reachable from the Error state (e.g. after "Encoder unavailable"), so
        // re-check that prepare actually succeeded before starting encoders.
        if (!isPrepared) {
            terminateWithError("Encoder not ready")
            return
        }
        val endpoint = validateStreamEndpoint(url)
        if (endpoint is StreamEndpointValidation.Invalid) {
            terminateWithError(endpoint.problem.userMessage)
            return
        }
        val cleanUrl = url

        // A confirmed OBS BRB state survives a control disconnect. Never
        // start sending a fresh feed unless both local privacy controls are
        // actually in place.
        refreshObsBrbPrivacy(ObsClient.state.value, ObsClient.currentScene.value)
        if (obsBrbEffectsActive) {
            setObsBrbEffects(true)
            if (!_isMuted.value || !_isCameraOff.value) {
                terminateWithError("OBS BRB privacy protection could not be enabled")
                return
            }
        }

        // A reconnect loop can outlive its stream in the Error state (e.g. an
        // ingest that rejects auth mid-loop). Starting fresh while it lingers
        // would let its 15s attempt timeout stop the new stream under us.
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting = false

        // Force-stop any lingering stream from a previous aborted attempt.
        val hadLingering = stream.isStreaming
        if (hadLingering) runCatching { stream.stopStream() }

        stopRequested = false
        wantStreaming = true
        hasEverConnected = false
        lastStreamUrl = cleanUrl
        // Give SRT a real retransmission window before connecting (no-op for
        // RTMP). RootEncoder 2.8 accepts milliseconds directly.
        if (isSrtUrl(cleanUrl)) {
            runCatching { srtStreamClient?.setLatency(SRT_LATENCY_MS) }
        }
        liveStartMs = SystemClock.elapsedRealtime()
        // Seed the cap from current heat because the listener reports only
        // transitions and can change while the engine is idle.
        val status = runCatching { powerManager?.currentThermalStatus }.getOrNull()
        thermalCapBps = thermalCapFor(status ?: PowerManager.THERMAL_STATUS_NONE)
        _thermalNotice.value = thermalCapBps?.let {
            "Heat warning: bitrate capped at ${it / 1000} kbps"
        }
        _micNotice.value = null
        ObsClient.setRoamStreamingWanted(Prefs.obsSyncStreaming(context))
        _state.value = StreamState.Connecting
        startInitialConnectionWatchdog()
        startHeadroomLogger()
        // Hand the mic over to the encoder; preview reader would compete for
        // the AudioRecord slot otherwise.
        refreshMeter()
        if (hadLingering) {
            // Same codec-drain hazard as the reconnect loop: restarting the
            // encoder in the same breath as stopStream segfaults native code
            // mid-buffer (see reconnectLoop). Take the same 250ms detour.
            engineScope.launch {
                delay(250)
                if (wantStreaming && !stopRequested) fireStartStream(cleanUrl)
            }
        } else {
            fireStartStream(cleanUrl)
        }
    }

    private fun fireStartStream(cleanUrl: String) {
        runCatching { stream.startStream(cleanUrl) }
            .onFailure { t ->
                Log.w(TAG, "startStream threw", t)
                terminateWithError("Stream could not start")
            }
    }

    private fun startHeadroomLogger() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val pm = powerManager ?: return
        headroomLoggerJob?.cancel()
        headroomLoggerJob = engineScope.launch {
            while (isActive) {
                val headroom = runCatching { pm.getThermalHeadroom(0) }.getOrNull()
                val status = runCatching { pm.currentThermalStatus }.getOrNull()
                if (headroom != null && !headroom.isNaN()) {
                    Log.d(TAG, "thermal headroom=${"%.2f".format(headroom)} status=$status")
                }
                delay(30_000L)
            }
        }
    }

    /** Process-owned timeout. A pocketed phone must still tear down encoders,
     *  recording, service notification, and owned OBS output if the first
     *  connection attempt never produces a terminal callback. */
    private fun startInitialConnectionWatchdog() {
        initialConnectionWatchdogJob?.cancel()
        initialConnectionWatchdogJob = engineScope.launch {
            delay(INITIAL_CONNECTION_TIMEOUT_MS)
            if (wantStreaming && _state.value === StreamState.Connecting) {
                terminateWithError("Connection timed out. Check your stream URL and network.")
            }
        }
    }

    @Synchronized
    fun stop() {
        cleanSessionResources()
        _state.value = StreamState.Idle
        // Stream's done with the mic; resume pre-live metering if the user
        // still wants it.
        refreshMeter()
    }

    /** Surface a foreground-service failure through the same terminal cleanup
     *  path as transport errors, so the UI cannot look idle while capture or a
     *  recording is still active. */
    @Synchronized
    internal fun reportServiceFailure(message: String) {
        terminateWithError(message)
    }

    @Synchronized
    private fun terminateWithError(message: String) {
        cleanSessionResources()
        _state.value = StreamState.Error(message)
        engineScope.launch(Dispatchers.Main) { refreshMeter() }
    }

    /** One terminal path for every caller, including library callbacks while
     *  the display is off. Recording is always stopped before the stream. */
    private fun cleanSessionResources() {
        stopRequested = true
        wantStreaming = false
        currentAttemptOutcome?.complete(false)
        currentAttemptOutcome = null
        reconnectJob?.cancel()
        reconnectJob = null
        initialConnectionWatchdogJob?.cancel()
        initialConnectionWatchdogJob = null
        isReconnecting = false
        criticalStopJob?.cancel()
        criticalStopJob = null
        headroomLoggerJob?.cancel()
        headroomLoggerJob = null
        liveStartMs = null
        ObsClient.setRoamStreamingWanted(false)
        stopRecordSafe()
        runCatching { if (stream.isStreaming) stream.stopStream() }
    }

    fun release() {
        powerManager?.removeThermalStatusListener(thermalListener)
        cleanSessionResources()
        dualCamera.release()
        overlayRenderer.clear()
        tokenSource.release()
        if (stream.isOnPreview) stream.stopPreview()
        // Release any held SCO link so the system isn't left in MODE_IN_COMMUNICATION.
        if (scoActive) routeBluetoothSco(context, null)
        micPreviewReader.release()
        engineScope.cancel()
    }

    fun clearError() {
        if (_state.value is StreamState.Error) {
            _state.value = StreamState.Idle
            refreshMeter()
        }
    }

    /** Launch the reconnect loop. Called from ConnectChecker when we detect an
     *  unexpected drop after we'd successfully gone live. Does nothing if already
     *  reconnecting. */
    @Synchronized
    private fun startReconnect() {
        // Synchronized + volatile flag: a mid-stream drop fires this from BOTH
        // the library's onConnectionFailed (main thread) and the network-loss
        // watcher (Default dispatcher) near-simultaneously; unsynchronized,
        // both could pass the guard and run dueling reconnect loops.
        if (isReconnecting || !wantStreaming || stopRequested) return
        val url = lastStreamUrl ?: return
        val maxSec = Prefs.maxReconnectMinutes(context) * 60
        isReconnecting = true
        reconnectJob = engineScope.launch {
            try {
                reconnectLoop(url, maxSec)
            } finally {
                isReconnecting = false
                // stop() can only stop what was streaming at the moment it
                // ran. If this loop's in-flight startStream completed after
                // that (cancellation only lands at the next suspension), the
                // client is broadcasting with nobody managing it. Close that
                // gap on the way out.
                if (!wantStreaming && stream.isStreaming) {
                    runCatching { stream.stopStream() }
                }
            }
        }
    }

    /** Retry while network availability is true and await each connection
     *  callback through a per-attempt deferred. This handles current state and
     *  terminal outcomes without polling or waiting for a new availability edge. */
    private suspend fun reconnectLoop(url: String, maxSeconds: Int) {
        val startTime = SystemClock.elapsedRealtime()
        var attempt = 0

        while (engineScope.isActive && wantStreaming) {
            val elapsedSec = ((SystemClock.elapsedRealtime() - startTime) / 1000).toInt()
            if (maxSeconds > 0 && elapsedSec >= maxSeconds) {
                terminateWithError("Reconnect failed after ${maxSeconds / 60} min")
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
            // Network might have dropped again during our backoff - go around if so.
            if (!NetworkMonitor.isAvailable.value) continue

            // Step 3: try to connect, with an outcome signal the checker fills in.
            val outcome = CompletableDeferred<Boolean>()
            currentAttemptOutcome = outcome
            Log.d(TAG, "reconnect attempt $attempt (elapsed ${elapsedSec}s)")
            runCatching {
                if (stream.isStreaming) {
                    stream.stopStream()
                    // Allow MediaCodec callbacks to drain before restarting.
                    // Reusing invalidated buffers can crash in native code.
                    delay(250)
                }
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
                    // Failed fast - loop will immediately try again. No long wait.
                    Log.d(TAG, "reconnect attempt $attempt failed, retrying")
                }
                null -> {
                    // Connect hung past 15s - give up on this attempt, retry.
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
        // The new camera session starts in its default state - re-apply
        // stabilization if the user has it enabled.
        applyStabilization()
    }

    /** Apply the user's image-stabilization preference to the current main
     *  camera. Combines optical (OIS - hardware, where the phone has it) with
     *  electronic (EIS - digital, available almost everywhere). No-op on
     *  devices that support neither. Slightly crops the frame when EIS engages,
     *  which is why this is opt-in. Safe to call live; safe to call repeatedly. */
    fun applyStabilization() {
        val enabled = Prefs.stabilizationEnabled(context)
        val cam = stream.videoSource as? Camera2Source ?: return
        runCatching {
            if (enabled) {
                cam.enableVideoStabilization()
                cam.enableOpticalVideoStabilization()
            } else {
                cam.disableVideoStabilization()
                cam.disableOpticalVideoStabilization()
            }
        }.onFailure { Log.w(TAG, "stabilization apply failed", it) }
    }

    /** Setting moved to off: kill dual cam now if it was running, so the HUD
     *  button disappearing and the camera staying alive don't disagree. No-op
     *  when the setting moves to on - the user still has to tap the HUD button
     *  to actually enable it. */
    fun setDualCamSettingEnabled(enabled: Boolean) {
        if (!enabled && isDualCamOn.value) {
            if (mainFacingFront && _isTorchOn.value) _isTorchOn.value = false
            dualCamera.disable()
        }
    }

    fun toggleDualCam() {
        // The controller publishes state only after the asynchronous operation
        // completes, keeping the UI aligned with the camera state.
        if (isDualCamOn.value) {
            // Disabling. If main is front-facing, the rear camera (which may have
            // had torch on) was on the PiP slot - it's about to be released, so
            // the torch dies with it and we have to reset the icon to match.
            // If main is rear, the main camera doesn't change, so torch persists.
            if (mainFacingFront && _isTorchOn.value) _isTorchOn.value = false
            dualCamera.disable()
        } else {
            // Dual camera is disabled at severe heat. Do not re-enable it until
            // the device cools.
            if (stream.isStreaming) {
                val status = runCatching { powerManager?.currentThermalStatus }.getOrNull()
                if ((status ?: PowerManager.THERMAL_STATUS_NONE) >=
                    PowerManager.THERMAL_STATUS_SEVERE
                ) {
                    _thermalNotice.value = "Too hot for dual cam right now"
                    return
                }
            }
            // A PiP built during BRB or camera-off must come up hidden: the
            // PiP filter sits above the BRB/black filters in the GL chain, so
            // a default-visible PiP would broadcast a live camera on top of
            // what the streamer believes is a break screen.
            dualCamera.setPipVisible(!(_isBrb.value || _isCameraOff.value))
            // Enabling. Main camera doesn't change, just adds PiP. Torch on main
            // (if it was on) keeps running, so no state reset needed.
            // Resolve the opposite facing inside the serialized operation so a
            // concurrent camera switch cannot provide a stale value.
            dualCamera.enable { !mainFacingFront }
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
        // A lost OBS connection cannot be treated as proof that BRB ended.
        // Manual controls may add protection, but cannot remove the latch.
        if (obsBrbEffectsActive && _isCameraOff.value) return
        val gl = stream.getGlInterface()
        if (_isCameraOff.value) {
            runCatching {
                blackFilter?.let { gl.removeFilter(it) }
                blackFilter = null
                dualCamera.setPipVisible(true)
            }
                .onSuccess { _isCameraOff.value = false }
                .onFailure { Log.w(TAG, "camera-on failed", it) }
        } else {
            runCatching {
                // Put black below the overlays so camera-off hides every lens
                // while preserving the watermark and information overlays.
                val filter = BlackFilterRender()
                gl.addFilter(0, filter)
                blackFilter = filter
                dualCamera.setPipVisible(false)
            }
                .onSuccess { _isCameraOff.value = true }
                .onFailure { Log.w(TAG, "camera-off failed", it) }
        }
    }

    /** OBS-mode BRB side effects: mute the mic and black the camera while the
     *  OBS BRB scene is program, restore both on return. The feed itself keeps
     *  running (instant comeback, and drop-protection scenes may want the
     *  frame), but nothing private leaks through a break: with the
     *  recommended source-in-every-scene OBS setup, audio is otherwise always
     *  live. Driven by the engine's OBS collector, including while the screen
     *  is off, so PC/drop-script switches and disconnects use the same policy. */
    private fun setObsBrbEffects(active: Boolean) {
        if (active) {
            if (!obsBrbEffectsActive) {
                muteBeforeObsBrb = _isMuted.value
                cameraOffBeforeObsBrb = _isCameraOff.value
            }
            obsBrbEffectsActive = true
            if (!_isMuted.value) toggleMute()
            if (!_isCameraOff.value) toggleCameraOff()
            if (wantStreaming && (!_isMuted.value || !_isCameraOff.value)) {
                terminateWithError("OBS BRB privacy protection failed; stream stopped")
            }
        } else {
            if (!obsBrbEffectsActive) return
            // Clear the guard first so the controlled restoration is allowed.
            obsBrbEffectsActive = false
            if (_isMuted.value != muteBeforeObsBrb) toggleMute()
            if (_isCameraOff.value != cameraOffBeforeObsBrb) toggleCameraOff()
        }
    }

    private fun refreshObsBrbPrivacy(
        state: ObsConnectionState = ObsClient.state.value,
        currentScene: String? = ObsClient.currentScene.value,
    ) {
        val active = obsBrbPrivacyLatch.update(
            protectionEnabled = Prefs.obsBrbMute(context),
            brbScene = Prefs.obsBrbScene(context),
            connected = state is ObsConnectionState.Connected,
            currentScene = currentScene,
        )
        _obsBrbPrivacyActive.value = active
        setObsBrbEffects(active)
    }

    fun toggleMute() {
        if (obsBrbEffectsActive && _isMuted.value) return
        val mic = stream.audioSource as? MicrophoneSource ?: return
        val targetMuted = !_isMuted.value
        runCatching {
            if (targetMuted) mic.mute() else mic.unMute()
        }
            .onSuccess { _isMuted.value = targetMuted }
            .onFailure { Log.w(TAG, "microphone mute toggle failed", it) }
    }

    fun tapToFocus(event: MotionEvent) {
        runCatching {
            // 2.7.x wants the view the user tapped, for the metering rectangle.
            val view = currentView ?: return
            (stream.videoSource as? Camera2Source)?.tapToFocus(view, event)
        }
    }

    /** Re-applies the front-PiP mirror preference to a running dual cam.
     *  No-op when dual cam is off; the preference is read at PiP setup. */
    fun applyMirrorFrontPip() {
        dualCamera.reapplyMirror()
    }

    /** Multiplies the current zoom ratio by [factor], clamped to what the
     *  camera supports. On logical multi-lens phones the ratio range starts
     *  below 1.0, so pinching out slides into the ultra-wide and pinching in
     *  runs through the tele without any explicit lens switching. Returns the
     *  applied ratio for the HUD readout, or null if the source has no zoom. */
    fun adjustZoomBy(factor: Float): Float? {
        val cam = stream.videoSource as? Camera2Source ?: return null
        return runCatching {
            val range = cam.getZoomRange()
            val target = (cam.getZoom() * factor).coerceIn(range.lower, range.upper)
            cam.setZoom(target)
            cam.getZoom()
        }.getOrNull()
    }

    fun dismissThermalNotice() {
        _thermalNotice.value = null
    }

    fun toggleBrb() {
        val gl = stream.getGlInterface()
        val mic = stream.audioSource as? MicrophoneSource
        if (_isBrb.value) {
            runCatching {
                brbForegroundFilter?.let { gl.removeFilter(it) }
                brbBlackFilter?.let { gl.removeFilter(it) }
                brbForegroundFilter = null
                brbBlackFilter = null
                // Restore the overlay scene now that BRB is dismissed - picks
                // up any edits the user made via the editor during BRB.
                applyOverlayScene(Prefs.overlayScene(context))
                // Restore the camera-off black layer if the user still has
                // their camera muted - we removed it on BRB enter so the BRB
                // image could show. Index 0: under the just-restored overlays
                // (camera off hides the lens, not the watermark/clock). PiP
                // visibility tracks camera-off too (hidden when the camera's
                // off, visible otherwise).
                if (_isCameraOff.value) {
                    val restored = BlackFilterRender()
                    gl.addFilter(0, restored)
                    blackFilter = restored
                }
                dualCamera.setPipVisible(!_isCameraOff.value)
                if (!obsBrbEffectsActive && !muteBeforeBrb && _isMuted.value) {
                    mic?.unMute()
                    _isMuted.value = false
                }
            }
                .onSuccess { _isBrb.value = false }
                .onFailure { Log.w(TAG, "brb-off failed", it) }
        } else {
            runCatching {
                // Take down the overlay scene so the streamer's BRB screen is
                // clean - no watermark or other overlays stamped over the
                // image / text they chose as their takeover.
                overlayRenderer.clear()
                // Camera-off keeps its own BlackFilterRender in the chain.
                // BRB inserts at indices 0/1, which would push the camera-off
                // black ABOVE the BRB foreground, hiding the BRB image. Pull
                // it out for the duration of BRB; restored (at index 0) on
                // exit if camera-off is still on. _isCameraOff itself is
                // deliberately left alone - the user's intent is preserved.
                if (_isCameraOff.value) {
                    blackFilter?.let { gl.removeFilter(it) }
                    blackFilter = null
                }
                val black = BlackFilterRender()
                // Custom image (if the user set one in Settings) takes priority
                // over the text. If image decode fails, fall back to the text.
                val foreground: BaseFilterRender =
                    Prefs.brbImagePath(context)
                        ?.let { createBrbImageFilter(it) }
                        ?: createBrbTextFilter(Prefs.brbText(context))
                gl.addFilter(0, black)
                gl.addFilter(1, foreground)
                brbBlackFilter = black
                brbForegroundFilter = foreground
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

    /** Fit-not-fill the user's custom BRB image into the frame: the whole image
     *  is visible (no cropping), centred, with the black filter behind it showing
     *  through wherever the aspect ratios don't match. Returns null if the file
     *  can't be decoded - toggleBrb falls back to the text in that case. */
    private fun createBrbImageFilter(path: String): ImageObjectFilterRender? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        val frameW = Prefs.videoWidth(context).toFloat()
        val frameH = Prefs.videoHeight(context).coerceAtLeast(1).toFloat()
        val frameAspect = frameW / frameH
        val imgAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        val scaleX: Float
        val scaleY: Float
        if (imgAspect > frameAspect) {
            // Image is wider than the frame - fit width, letterbox top/bottom.
            scaleX = 100f
            scaleY = 100f * frameAspect / imgAspect
        } else {
            // Image is taller (or same) - fit height, pillarbox left/right.
            scaleX = 100f * imgAspect / frameAspect
            scaleY = 100f
        }
        return ImageObjectFilterRender().apply {
            setImage(bitmap)
            setScale(scaleX, scaleY)
            setPosition((100f - scaleX) / 2f, (100f - scaleY) / 2f)
        }
    }

    private fun createBrbTextFilter(brbText: String): TextObjectFilterRender =
        TextObjectFilterRender().apply {
            setText(brbText.ifBlank { "BE RIGHT BACK" }, 28f, AndroidColor.WHITE)
            setDefaultScale(Prefs.videoWidth(context), Prefs.videoHeight(context))
            setPosition(TranslateTo.CENTER)
        }

    /** Thermal protection as escalating, visible degradation (never silent):
     *  MODERATE caps bitrate at 70%, SEVERE caps at 40% and steps the encode
     *  down to 480p, CRITICAL goes dark (auto-stealth) and gives the phone
     *  [CRITICAL_HEAT_GRACE_MS] to cool before stopping as the last resort.
     *  Caps are CEILINGS: with auto bitrate on, the controller keeps steering
     *  underneath them; with it off they apply directly. */
    /** Bitrate cap for a thermal status: 70% at MODERATE, 40% at SEVERE and
     *  everything above it (a direct jump to CRITICAL must not run uncapped),
     *  none below. */
    private fun thermalCapFor(status: Int): Int? {
        val configuredBitrate = Prefs.videoBitrateKbps(context) * 1000
        return when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> (configuredBitrate * 0.4).toInt()
            status == PowerManager.THERMAL_STATUS_MODERATE -> (configuredBitrate * 0.7).toInt()
            else -> null
        }
    }

    private fun handleThermalChange(status: Int) {
        // Update the cap even while idle or reconnecting. UI notices and active
        // mitigation remain limited to a running stream.
        thermalCapBps = thermalCapFor(status)
        if (!stream.isStreaming) return
        // Any reading below critical cancels a pending critical-heat stop.
        if (status < PowerManager.THERMAL_STATUS_CRITICAL) {
            criticalStopJob?.cancel()
            criticalStopJob = null
        }
        when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> {
                applyBitrateCeiling()
                _thermalNotice.value = null
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                val cap = thermalCapBps ?: return
                applyBitrateCeiling()
                _thermalNotice.value =
                    "Heat warning: bitrate capped at ${cap / 1000} kbps"
            }
            PowerManager.THERMAL_STATUS_SEVERE -> {
                val cap = thermalCapBps ?: return
                applyBitrateCeiling()
                // Dual camera adds substantial capture and rendering load, so
                // stop it at severe heat. Avoid changing resolution mid-stream
                // because some platforms split the recording at format changes.
                val wasDualCam = isDualCamOn.value
                if (wasDualCam) {
                    engineScope.launch(Dispatchers.Main) {
                        dualCamera.disableAndAwait()
                    }
                }
                _thermalNotice.value = if (wasDualCam) {
                    "Heat warning: dual cam stopped, bitrate capped at ${cap / 1000} kbps"
                } else {
                    "Heat warning: bitrate capped at ${cap / 1000} kbps"
                }
            }
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                // A direct jump past SEVERE must still shed the dominant heat
                // source; the SEVERE branch's kill never ran in that path.
                if (isDualCamOn.value) {
                    engineScope.launch(Dispatchers.Main) {
                        dualCamera.disableAndAwait()
                    }
                }
                if (criticalStopJob == null) {
                    _stealthRequested.value = true
                    _thermalNotice.value =
                        "Heat critical: stream stops in " +
                            "${CRITICAL_HEAT_GRACE_MS / 1000}s unless it cools"
                    criticalStopJob = engineScope.launch {
                        delay(CRITICAL_HEAT_GRACE_MS)
                        criticalStopJob = null
                        val still = powerManager?.currentThermalStatus
                            ?: PowerManager.THERMAL_STATUS_CRITICAL
                        // wantStreaming covers a reconnect outage: isStreaming
                        // is false mid-outage, but a critically hot phone must
                        // not keep the reconnect loop (and encoders) alive.
                        if (still >= PowerManager.THERMAL_STATUS_CRITICAL &&
                            (stream.isStreaming || wantStreaming)
                        ) {
                            _thermalNotice.value = "Heat critical: stream stopped"
                            stop()
                        }
                    }
                }
            }
            else -> Unit
        }
    }
}
