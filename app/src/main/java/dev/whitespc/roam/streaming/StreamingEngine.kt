package dev.whitespc.roam.streaming

import android.content.ContentValues
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
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
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.streamclient.GenericStreamClient
import com.pedro.library.util.streamclient.SrtStreamClient
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
import android.net.Network
import android.os.Environment
import android.os.StatFs
import com.pedro.library.base.recording.RecordController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

private const val TAG = "RoamStreamingEngine"

private const val VIDEO_GOP_SECONDS = 2
private const val AUDIO_BITRATE = 128_000
private const val AUDIO_SAMPLE_RATE = 44_100
private const val AUDIO_STEREO = true

// Local recording storage guards: refuse to start under 2 GB free, stop the
// recording (never the stream) if free space falls under 500 MB mid-stream.
private const val RECORD_MIN_START_BYTES = 2_000_000_000L
private const val RECORD_MIN_KEEP_BYTES = 500_000_000L

// SRT latency budget: how long the receiver buffers (and the sender keeps
// packets for retransmission) before frames are due. The library default is
// far too small for IRL; 2 s is the community norm (Moblin's default) and is
// what lets SRT ride out a multi-hundred-ms signal dip that would stall RTMP.
// The handshake takes the max of both sides, so a receiver asking for more wins.
private const val SRT_LATENCY_MS = 2_000

// Severe-heat fallback resolution: cutting pixel count cuts encoder load far
// harder than bitrate alone. 480p16:9; applied once per stream, restored on stop.

// Critical heat: how long auto-stealth gets to cool the phone before we stop
// the stream as the last resort.
private const val CRITICAL_HEAT_GRACE_MS = 60_000L

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

    /** Set true when critical heat wants the screen dark NOW. The stream screen
     *  collects this and enters stealth mode (the display is a real heat source;
     *  Elliot's own thermal test used stealth for exactly this). The UI resets
     *  it via [consumeStealthRequest] so a later manual stealth exit sticks. */
    private val _stealthRequested = MutableStateFlow(false)
    val stealthRequested: StateFlow<Boolean> = _stealthRequested.asStateFlow()

    fun consumeStealthRequest() {
        _stealthRequested.value = false
    }

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

    /** The SRT-specific client, for latency config and loss stats. RootEncoder's
     *  generic wrapper doesn't expose the per-protocol clients, so this reaches
     *  the field reflectively; if a future library version moves it, everything
     *  degrades quietly (latency stays at the library default, health falls back
     *  to congestion + dropped frames) instead of crashing. Worth upstreaming a
     *  proper accessor to RootEncoder. */
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

    /** Periodic thermal headroom logger. Runs while streaming so field-test
     *  logs include a continuous heat curve instead of only status-change
     *  transitions (the listener fires rarely; headroom is a 0..1 continuous
     *  signal where ~1.0 means throttling imminent). */
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
            Log.d(TAG, "connection success")
            if (stopRequested) return
            // Capture BEFORE completing the deferred: the reconnect loop clears
            // isReconnecting as soon as the outcome lands.
            val wasReconnecting = isReconnecting
            hasEverConnected = true
            // Signal the reconnect loop (if any) that this attempt succeeded.
            currentAttemptOutcome?.complete(true)
            _state.value = StreamState.Live(0, connectedCount = 1, totalCount = 1)
            // Fresh connection: aim at the full ceiling and let congestion ticks
            // pull us down if the link disagrees (reacts within a second or two).
            if (autoBitrateEnabled) bitrateController.reset(effectiveMaxBitrateBps())
            // Fresh health baseline for the new connection's counters.
            lastDroppedVideoFrames = 0
            lastSrtPacketsLost = 0
            strainedTicks = 0
            runCatching { stream.getStreamClient().resetDroppedVideoFrames() }
            // Local recording rides the same encoders as the stream and keeps
            // running through reconnects, so only start it if it isn't already.
            if (Prefs.recordWhileStreaming(context) && !stream.isRecording) {
                startRecordSafe()
            }
            if (wasReconnecting) {
                // The encoder pipeline restarted under the GL chain, which can
                // drop filter textures: the broadcast side comes back with no
                // watermark/overlays even though the preview still shows them.
                // Rebuild visuals on the main thread, same as background-resume.
                engineScope.launch(Dispatchers.Main) { restoreBroadcastVisuals() }
            }
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
            maybeCheckRecordingStorage()
            val current = _state.value
            if (current is StreamState.Live) {
                _state.value = current.copy(bitrateBps = bitrate, health = health)
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
    /** Foreground filter shown over the BRB black layer — either text or a custom
     *  full-frame image, depending on whether the user set a BRB image in Settings. */
    private var brbForegroundFilter: BaseFilterRender? = null
    private var muteBeforeBrb = false

    private val dualCamera = DualCameraController(context, stream)
    val isDualCamOn: StateFlow<Boolean> = dualCamera.isOn

    private val tokenSource = dev.whitespc.roam.streaming.overlay.TokenSource(context)

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
        // The loss watcher above never fires on a network SWITCH: leaving WiFi for
        // cellular (or arriving home, the reverse) is make-before-break, so "some
        // network is up" stays true the whole time. But our socket is bound to the
        // OLD network, which is dying; left alone, detection waits on a TCP write
        // timeout while viewers stare at a frozen frame. So: when the network that
        // new connections route over changes identity while we're live, restart
        // the stream on the new one immediately via the normal reconnect path.
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
            isPrepared = true
            preparedWidth = width
            preparedHeight = height
            preparedFps = fps
            preparedBitrate = bitrate
            applyPreferredMic(context)
        }
        if (stream.isOnPreview) stream.stopPreview()
        stream.startPreview(view)
        applyStabilization()
        // The OpenGL surface was just (re)created, so filters from a previous
        // surface lost their GPU textures and would draw blank. Rebuild the
        // whole visual state on every attach: cold launch, background-resume,
        // and (new) backgrounding during BRB now restores the BRB screen
        // instead of silently dropping it on return. Costs a web overlay
        // reload when returning from background.
        restoreBroadcastVisuals()
    }

    /**
     * Rebuild every GL-side visual the broadcast depends on, from prefs and the
     * engine's own state flags: either the BRB takeover (scene stays cleared
     * underneath it, same as toggleBrb), or the overlay scene plus the
     * camera-off black layer if the camera is muted.
     *
     * Needed whenever the GL surface or encoder pipeline restarts underneath
     * us: background-resume (surface destroyed/recreated) and stream reconnect
     * (encoder restarted). In both cases the filters lose their GPU textures
     * while our state flags still say they should be visible.
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
            }.onFailure { Log.w(TAG, "BRB restore failed", it) }
        } else {
            overlayRenderer.applyScene(Prefs.overlayScene(context))
            // gl.muteVideo() and the BlackFilterRender don't survive the GL
            // restart, but our _isCameraOff flag does. Without this, a user who
            // backgrounds with camera off resumes with the camera visibly back on.
            if (_isCameraOff.value) {
                runCatching {
                    blackFilter?.let { gl.removeFilter(it) }
                    gl.muteVideo()
                    val fresh = BlackFilterRender()
                    gl.addFilter(fresh)
                    blackFilter = fresh
                    dualCamera.setPipVisible(false)
                }.onFailure { Log.w(TAG, "camera-off restore failed", it) }
            }
        }
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
     *  back. Safe whether idle or streaming (the renderer swaps filters live).
     *
     *  No-op while BRB is active — overlays are deliberately hidden during BRB,
     *  and toggleBrb will re-apply the scene (picking up any editor changes)
     *  when the user exits. */
    fun applyScene(context: Context) {
        if (_isBrb.value) return
        runCatching { overlayRenderer.applyScene(Prefs.overlayScene(context)) }
            .onFailure { Log.w(TAG, "applyScene failed", it) }
    }

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

    private var recordTickCounter = 0

    /** Path of the file RootEncoder is currently writing to. Cleared after we
     *  finalize it into public Movies, so a failed/abandoned recording on the
     *  next start can be detected and not double-finalized. */
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
            runCatching { if (stream.isRecording) stream.stopRecord() }
            _isRecording.value = false
            currentRecordPath?.let { finalizeRecording(it) }
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
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
        recordTickCounter = 0
        val path = File(dir, name).absolutePath
        runCatching { stream.startRecord(path, listener = recordListener) }
            .onSuccess { currentRecordPath = path }
            .onFailure {
                Log.w(TAG, "startRecord failed", it)
                _recordNotice.value = "Couldn't start recording. The stream is not affected."
            }
    }

    private fun stopRecordSafe() {
        runCatching { if (stream.isRecording) stream.stopRecord() }
            .onFailure { Log.w(TAG, "stopRecord failed", it) }
        _isRecording.value = false
        val path = currentRecordPath
        currentRecordPath = null
        if (path != null) finalizeRecording(path)
    }

    /** Copy the just-recorded file from app-private Movies to the public
     *  Movies/Roam folder via MediaStore so it shows up in Gallery/Files like
     *  any normal video. App-private path is invisible to the Files app on
     *  Android 11+. Runs on IO, never blocks the stream thread. */
    private fun finalizeRecording(privatePath: String) {
        engineScope.launch(Dispatchers.IO) {
            runCatching {
                val privateFile = File(privatePath)
                if (!privateFile.exists() || privateFile.length() == 0L) return@runCatching
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, privateFile.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/Roam",
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: return@runCatching
                resolver.openOutputStream(uri)?.use { os ->
                    privateFile.inputStream().use { it.copyTo(os) }
                } ?: run {
                    resolver.delete(uri, null, null)
                    return@runCatching
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                privateFile.delete()
                Log.d(TAG, "recording finalized to Movies/Roam/${privateFile.name}")
            }.onFailure { Log.w(TAG, "finalize recording failed", it) }
        }
    }

    /** Called once a second from onNewBitrate; roughly once a minute while
     *  recording, make sure storage isn't about to run out. Stops the
     *  recording with a notice, never the stream. */
    private fun maybeCheckRecordingStorage() {
        if (!stream.isRecording) return
        recordTickCounter++
        if (recordTickCounter < 60) return
        recordTickCounter = 0
        val freeBytes = runCatching { StatFs(recordingsDir().absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        if (freeBytes < RECORD_MIN_KEEP_BYTES) {
            stopRecordSafe()
            _recordNotice.value = "Recording stopped: storage almost full. The stream keeps going."
        }
    }

    /** Settings toggle, live-safe: turning it on mid-stream starts recording
     *  now; off stops it. When idle this is a no-op; the pref is read at the
     *  next go-live. */
    fun setRecordWhileStreaming(enabled: Boolean) {
        if (!stream.isStreaming) return
        if (enabled) startRecordSafe() else stopRecordSafe()
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
        // Reachable from the Error state (e.g. after "Encoder unavailable"), so
        // re-check that prepare actually succeeded before starting encoders.
        if (!isPrepared) {
            _state.value = StreamState.Error("Encoder not ready")
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
        // Give SRT a real retransmission window before connecting (no-op for RTMP).
        if (isSrtUrl(cleanUrl)) {
            runCatching { srtStreamClient?.setLatency(SRT_LATENCY_MS) }
        }
        liveStartMs = SystemClock.elapsedRealtime()
        // Clear any leftover heat banner from a previous session. The thermal
        // handler is dormant while idle, so a "phone hot" banner from an
        // earlier stream stays on screen otherwise even when the phone is fine.
        _thermalNotice.value = null
        _state.value = StreamState.Connecting
        startHeadroomLogger()
        runCatching { stream.startStream(cleanUrl) }
            .onFailure { t ->
                Log.w(TAG, "startStream threw", t)
                _state.value = StreamState.Error(t.message ?: "Stream start failed")
                wantStreaming = false
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

    fun stop() {
        stopRequested = true
        wantStreaming = false
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting = false
        criticalStopJob?.cancel()
        criticalStopJob = null
        headroomLoggerJob?.cancel()
        headroomLoggerJob = null
        liveStartMs = null
        stopRecordSafe()
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
        liveStartMs = null
        dualCamera.release()
        overlayRenderer.clear()
        tokenSource.release()
        stopRecordSafe()
        runCatching { if (stream.isStreaming) stream.stopStream() }
        if (stream.isOnPreview) stream.stopPreview()
        engineScope.cancel()
    }

    fun clearError() {
        if (_state.value is StreamState.Error) {
            stopRequested = true
            wantStreaming = false
            liveStartMs = null
            runCatching { if (stream.isStreaming) stream.stopStream() }
            _state.value = StreamState.Idle
        }
    }

    fun failConnectingTimeout() {
        if (_state.value === StreamState.Connecting) {
            stopRequested = true
            wantStreaming = false
            liveStartMs = null
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
                // The encoders kept the recording alive through the outage;
                // now that we've given up on the stream, close the file too.
                stopRecordSafe()
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
        // The new camera session starts in its default state — re-apply
        // stabilization if the user has it enabled.
        applyStabilization()
    }

    /** Apply the user's image-stabilization preference to the current main
     *  camera. Combines optical (OIS — hardware, where the phone has it) with
     *  electronic (EIS — digital, available almost everywhere). No-op on
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
     *  when the setting moves to on — the user still has to tap the HUD button
     *  to actually enable it. */
    fun setDualCamSettingEnabled(enabled: Boolean) {
        if (!enabled && isDualCamOn.value) {
            if (mainFacingFront && _isTorchOn.value) _isTorchOn.value = false
            dualCamera.disable()
        }
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
            // 2.7.x wants the view the user tapped, for the metering rectangle.
            val view = currentView ?: return
            (stream.videoSource as? Camera2Source)?.tapToFocus(view, event)
        }
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
                // Restore the overlay scene now that BRB is dismissed — picks
                // up any edits the user made via the editor during BRB.
                overlayRenderer.applyScene(Prefs.overlayScene(context))
                // Restore the camera-off black layer if the user still has
                // their camera muted — we removed it on BRB enter so the BRB
                // image could show. PiP visibility tracks camera-off too
                // (hidden when the camera's off, visible otherwise).
                if (_isCameraOff.value) {
                    val restored = BlackFilterRender()
                    gl.addFilter(restored)
                    blackFilter = restored
                }
                dualCamera.setPipVisible(!_isCameraOff.value)
                if (!muteBeforeBrb && _isMuted.value) {
                    mic?.unMute()
                    _isMuted.value = false
                }
            }
                .onSuccess { _isBrb.value = false }
                .onFailure { Log.w(TAG, "brb-off failed", it) }
        } else {
            runCatching {
                // Take down the overlay scene so the streamer's BRB screen is
                // clean — no watermark or other overlays stamped over the
                // image / text they chose as their takeover.
                overlayRenderer.clear()
                // Camera-off appends a BlackFilterRender at the END of the chain
                // so it draws on top. With BRB's filters added at indices 0/1,
                // the camera-off black would still draw last and hide the BRB
                // image. Pull it out for the duration of BRB; restored on exit
                // if camera-off is still on. We deliberately leave _isCameraOff
                // and gl.muteVideo() alone — the user's intent is preserved.
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
     *  can't be decoded — toggleBrb falls back to the text in that case. */
    private fun createBrbImageFilter(path: String): ImageObjectFilterRender? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        val frameW = Prefs.videoWidth(context).toFloat()
        val frameH = Prefs.videoHeight(context).coerceAtLeast(1).toFloat()
        val frameAspect = frameW / frameH
        val imgAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        val scaleX: Float
        val scaleY: Float
        if (imgAspect > frameAspect) {
            // Image is wider than the frame — fit width, letterbox top/bottom.
            scaleX = 100f
            scaleY = 100f * frameAspect / imgAspect
        } else {
            // Image is taller (or same) — fit height, pillarbox left/right.
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
    private fun handleThermalChange(status: Int) {
        if (!stream.isStreaming) return
        // Any reading below critical cancels a pending critical-heat stop.
        if (status < PowerManager.THERMAL_STATUS_CRITICAL) {
            criticalStopJob?.cancel()
            criticalStopJob = null
        }
        val configuredBitrate = Prefs.videoBitrateKbps(context) * 1000
        when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> {
                thermalCapBps = null
                applyBitrateCeiling()
                _thermalNotice.value = null
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                val cap = (configuredBitrate * 0.7).toInt()
                thermalCapBps = cap
                applyBitrateCeiling()
                _thermalNotice.value =
                    "Heat warning: bitrate capped at ${cap / 1000} kbps"
            }
            PowerManager.THERMAL_STATUS_SEVERE -> {
                val cap = (configuredBitrate * 0.4).toInt()
                thermalCapBps = cap
                applyBitrateCeiling()
                // Dual cam is the dominant heat driver (test 4 reached SEVERE
                // in 24 min with it as the only extra load). Killing it on
                // SEVERE gave ~0.07 headroom relief in our data. Resolution
                // step-down was tried but split the stream into separate VODs
                // on Twitch for ~0.02 of extra relief; not worth the UX hit.
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
                        if (still >= PowerManager.THERMAL_STATUS_CRITICAL && stream.isStreaming) {
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
