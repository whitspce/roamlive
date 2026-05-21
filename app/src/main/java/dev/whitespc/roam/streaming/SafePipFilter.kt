package dev.whitespc.roam.streaming

import android.util.Log
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender

private const val TAG = "RoamSafePipFilter"

/**
 * A [SurfaceFilterRender] that swallows transient `updateTexImage` errors inside
 * [drawFilter] instead of letting them propagate up to the GL thread's uncaught
 * handler (which would kill the thread and freeze the whole rendering pipeline).
 *
 * Why this is needed: when the PiP filter is added to the GL chain, there's a
 * brief window before the underlying camera actually starts producing frames into
 * the surface texture. Similarly, when the camera is released, the texture is
 * "abandoned" before our filter-removal call has propagated through the GL queue.
 * In both windows, the GL render thread can call `updateTexImage` on a texture
 * with no usable producer and the native method throws a RuntimeException.
 *
 * The exception is harmless — the next render pass succeeds once state settles —
 * but it's fatal to the GL thread if uncaught. Catching it inside `drawFilter`
 * means just one frame is dropped, the GL thread survives, and the pipeline
 * keeps going.
 */
class SafePipFilter(callback: SurfaceReadyCallback) : SurfaceFilterRender(callback) {

    override fun drawFilter() {
        try {
            super.drawFilter()
        } catch (e: RuntimeException) {
            val msg = e.message.orEmpty()
            if (msg.contains("updateTexImage", ignoreCase = true)) {
                // Transient SurfaceTexture state (not yet producing, or just released).
                // Skip this frame, the next pass will be fine.
                return
            }
            // Unexpected — let it propagate.
            Log.w(TAG, "unexpected exception in PiP drawFilter", e)
            throw e
        }
    }
}
