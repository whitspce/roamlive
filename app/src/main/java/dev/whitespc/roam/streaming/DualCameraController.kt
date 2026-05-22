package dev.whitespc.roam.streaming

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.generic.GenericStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "RoamDualCamera"

// PiP size and position (locked v1 design): small square in the top-left,
// opposite the chat panel on the right, leaving room for top-right burn-in chat later.
private const val PIP_SCALE_PERCENT = 25f
private const val PIP_CAPTURE_WIDTH = 640
private const val PIP_CAPTURE_HEIGHT = 480
private const val PIP_FPS = 30

// Long enough for the GL thread to drain its filter queue between an async
// removeFilter() call and tearing down the camera that was feeding the filter.
// At 30fps a frame is ~33ms; 200ms covers ~6 frames with plenty of margin.
private const val FILTER_DRAIN_DELAY_MS = 200L

/**
 * Runs the secondary (PiP) camera and composites it onto the broadcast as a small
 * overlay. The main camera is unchanged — it stays in [GenericStream.videoSource].
 *
 * All public operations are fire-and-forget; the actual work runs on an internal
 * coroutine scope, serialised by a mutex so concurrent enable/disable/swap calls
 * don't tangle. This matters because RootEncoder's `addFilter`/`removeFilter` are
 * async (they queue work for the GL thread), so we need to space out
 * filter-removal and camera-release to avoid the GL thread crashing trying to
 * draw a filter whose backing camera texture was just abandoned.
 */
class DualCameraController(
    private val context: Context,
    private val stream: GenericStream,
) {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opMutex = Mutex()

    private var pipFilter: SurfaceFilterRender? = null
    private var pipCamera: Camera2Source? = null
    /** Tracks which way the PiP camera is currently facing so swap knows what to
     *  flip it to. We track this ourselves rather than asking the Camera2Source. */
    private var pipFacingFront = true

    /** Source of truth for whether dual-cam is currently active. Updated from
     *  inside the enable/disable coroutines, so the UI reflects reality rather
     *  than the user's intent. */
    private val _isOn = MutableStateFlow(false)
    val isOn: StateFlow<Boolean> = _isOn.asStateFlow()

    val isEnabled: Boolean get() = pipFilter != null

    /**
     * Enable PiP. [facingFront] specifies which way the PiP camera should face —
     * caller must pass the opposite of the main camera's current facing, otherwise
     * we'd try to open two cameras with the same facing simultaneously which the
     * Camera2 Concurrent API doesn't support.
     */
    fun enable(facingFront: Boolean = true) {
        controllerScope.launch {
            opMutex.withLock { enableInternal(facingFront = facingFront) }
        }
    }

    fun disable() {
        controllerScope.launch {
            opMutex.withLock { disableInternal() }
        }
    }

    /**
     * Flip the facings of both cameras simultaneously. PiP fully torn down, main
     * camera flipped, then PiP rebuilt with the opposite facing. ~400ms blip in
     * the preview but no race with the GL thread.
     */
    fun swap() {
        controllerScope.launch {
            opMutex.withLock {
                if (!isEnabled) return@withLock
                val newPipFacingFront = !pipFacingFront
                disableInternal()
                runCatching { (stream.videoSource as? Camera2Source)?.switchCamera() }
                // Small settling delay for the main camera before bringing PiP back
                delay(100)
                enableInternal(facingFront = newPipFacingFront)
            }
        }
    }

    fun release() {
        controllerScope.launch {
            opMutex.withLock { disableInternal() }
            controllerScope.cancel()
        }
    }

    /**
     * Show or hide the PiP overlay without tearing down the camera. Used when BRB
     * or camera-off is engaged — we want the streamer's whole frame to look "off",
     * including the corner cam, not just the main. Setting alpha is far cheaper
     * than tearing down + rebuilding the camera.
     */
    fun setPipVisible(visible: Boolean) {
        pipFilter?.setAlpha(if (visible) 1f else 0f)
    }

    /** The PiP Camera2Source if any. Exposed so the engine can target torch at it
     *  when the PiP is the rear-facing slot. */
    val pipCameraOrNull: Camera2Source? get() = pipCamera

    private suspend fun enableInternal(facingFront: Boolean) {
        if (isEnabled) return
        if (!DualCameraSupport.isSupported(context)) {
            Log.w(TAG, "device does not support concurrent cameras; ignoring enable()")
            return
        }

        // Camera2 service can be in a transient bad state right after a recent camera
        // release (rapid toggle cycles). Wrap the whole setup in runCatching so a
        // transient failure leaves us cleanly disabled instead of crashing the app.
        val cam = Camera2Source(context)
        val initOk = runCatching {
            if (facingFront) cam.switchCamera()  // default BACK → FRONT
            cam.init(PIP_CAPTURE_WIDTH, PIP_CAPTURE_HEIGHT, PIP_FPS, 0)
        }.onFailure {
            Log.w(TAG, "pip camera init failed (likely transient; try again)", it)
            runCatching { cam.release() }
        }.isSuccess
        if (!initOk) return

        pipFacingFront = facingFront

        val filter = SafeSurfaceFilter(
            object : SurfaceFilterRender.SurfaceReadyCallback {
                override fun surfaceReady(surfaceTexture: SurfaceTexture) {
                    runCatching {
                        surfaceTexture.setDefaultBufferSize(PIP_CAPTURE_WIDTH, PIP_CAPTURE_HEIGHT)
                        cam.start(surfaceTexture)
                    }.onFailure { Log.w(TAG, "pip camera start failed", it) }
                }
            },
        )
        filter.setPosition(TranslateTo.TOP_LEFT)
        filter.setScale(PIP_SCALE_PERCENT, PIP_SCALE_PERCENT)

        runCatching {
            stream.getGlInterface().addFilter(filter)
            pipFilter = filter
            pipCamera = cam
            _isOn.value = true
        }.onFailure {
            Log.w(TAG, "failed to add pip filter", it)
            runCatching { cam.release() }
        }
    }

    private suspend fun disableInternal() {
        if (!isEnabled) return
        val filter = pipFilter
        val cam = pipCamera
        // Null these immediately so isEnabled flips and re-entrant enable() works.
        pipFilter = null
        pipCamera = null
        _isOn.value = false
        // Step 1: queue the filter removal. This is async — the GL thread will
        // process it on a subsequent render pass.
        runCatching { filter?.let { stream.getGlInterface().removeFilter(it) } }
        // Step 2: wait long enough for the GL thread to actually drain the queue
        // and stop drawing this filter. Without this, the next step (releasing
        // the camera) abandons the SurfaceTexture while the filter is still in
        // the chain, and the GL thread crashes mid-render.
        delay(FILTER_DRAIN_DELAY_MS)
        // Step 3: now safe to tear down the camera; the filter is no longer drawn.
        runCatching { cam?.stop() }
        runCatching { cam?.release() }
        runCatching { filter?.release() }
    }
}
