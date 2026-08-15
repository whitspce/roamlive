package dev.whitespc.roam.streaming

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import dev.whitespc.roam.diagnostics.RoamLog as Log

private const val TAG = "RoamDualCamSupport"

/**
 * Whether the device supports running two cameras at the same time (Camera2
 * Concurrent API, Android 11+). Hardware-gated: the ISP has to be able to drive
 * two camera pipelines in parallel, so older or budget phones return false even
 * on Android 11+. Newer Pixels, recent Galaxies, OnePlus etc. generally support it.
 *
 * Used to decide whether to surface the dual-camera toggle in Settings at all.
 */
object DualCameraSupport {

    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        return runCatching {
            val sets = cm.concurrentCameraIds
            Log.d(TAG, "concurrent camera id sets: $sets")
            sets.isNotEmpty()
        }.getOrElse {
            Log.w(TAG, "concurrentCameraIds query failed", it)
            false
        }
    }
}
