package dev.whitespc.roam.streaming

import dev.whitespc.roam.diagnostics.RoamLog as Log
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender

private const val TAG = "RoamSafeSurfaceFilter"

/**
 * A [SurfaceFilterRender] that swallows transient `updateTexImage` errors inside
 * [drawFilter] instead of letting them propagate up to the GL thread's uncaught
 * handler (which would kill the thread and freeze the whole rendering pipeline).
 *
 * A surface can briefly lack a producer while it starts or after it stops but
 * before asynchronous filter removal reaches the GL queue. During either window,
 * `updateTexImage` can throw a RuntimeException.
 *
 * This drops the affected frame while allowing unexpected runtime exceptions to
 * propagate.
 *
 * Used by both the dual-camera PiP and the web overlay.
 */
class SafeSurfaceFilter(callback: SurfaceReadyCallback) : SurfaceFilterRender(callback) {

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
            // Preserve unexpected failures.
            Log.w(TAG, "unexpected exception in drawFilter", e)
            throw e
        }
    }
}
