package dev.whitespc.roam.streaming

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log

private const val TAG = "RoamTorch"

/**
 * Controls the phone's flash LED directly via [CameraManager.setTorchMode], the
 * same API the system torch tile uses. Completely independent of any camera
 * session — torch can be on whether we're streaming or not, whether front or
 * rear is the main camera, whether dual-cam is on, etc.
 *
 * Torch is always physically on the back of the phone (no front-facing flash on
 * any Android phone in common use), so we look up the rear-facing camera ID
 * once and use it as the flash target.
 */
object TorchController {

    /** Cached rear-camera-with-flash ID. Resolved lazily on first use. */
    @Volatile
    private var cachedRearCameraId: String? = null

    /**
     * Try to set torch on/off. Returns true if successful, false if the device
     * has no flash or the call failed (e.g., another app is currently using it).
     */
    fun setTorch(context: Context, on: Boolean): Boolean {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false
        val cameraId = rearCameraIdWithFlash(context, cm) ?: return false
        return runCatching {
            cm.setTorchMode(cameraId, on)
            true
        }.getOrElse {
            Log.w(TAG, "setTorchMode($on) failed", it)
            false
        }
    }

    /** Whether this device has a controllable flash unit. */
    fun isAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false
        return rearCameraIdWithFlash(context, cm) != null
    }

    private fun rearCameraIdWithFlash(context: Context, cm: CameraManager): String? {
        cachedRearCameraId?.let { return it }
        val id = runCatching {
            cm.cameraIdList.firstOrNull { id ->
                val ch = cm.getCameraCharacteristics(id)
                val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                hasFlash && facing == CameraMetadata.LENS_FACING_BACK
            }
        }.getOrNull()
        cachedRearCameraId = id
        return id
    }
}
