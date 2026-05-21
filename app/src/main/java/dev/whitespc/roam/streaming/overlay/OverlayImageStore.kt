package dev.whitespc.roam.streaming.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.UUID

private const val TAG = "RoamOverlayImages"
private const val IMAGE_DIR = "overlay_images"

/**
 * Copies user-picked images into app-private storage. We copy rather than holding
 * the gallery content:// URI because those URIs aren't guaranteed to stay readable
 * across app restarts, and the user could delete the original from their gallery.
 * Once copied, the overlay's image is stable and self-contained.
 */
object OverlayImageStore {

    /**
     * Import [uri] into app-private storage. Returns the absolute file path of the
     * copy, or null on failure.
     */
    fun importImage(context: Context, uri: Uri): String? {
        val dir = File(context.filesDir, IMAGE_DIR).apply { mkdirs() }
        val dest = File(dir, "ovl_${UUID.randomUUID()}.img")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            dest.absolutePath
        }.getOrElse {
            Log.w(TAG, "image import failed", it)
            runCatching { dest.delete() }
            null
        }
    }

    /**
     * Width/height aspect ratio (w/h) of the image at [path], read from its bounds
     * without decoding the full bitmap. Returns 1.0 if it can't be determined, so
     * callers always get a usable value.
     */
    fun aspectRatio(path: String): Float {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        return if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 1f
    }

    /** Delete a previously-imported overlay image. Safe to call on a missing file. */
    fun deleteImage(path: String) {
        runCatching { File(path).delete() }
    }

    /**
     * Given an overlay width as a percent of frame width, the source image's
     * aspect ratio (w/h), and the broadcast frame's aspect ratio (w/h), returns
     * the overlay height as a percent of frame height that keeps the image's
     * proportions undistorted.
     *
     * Derivation: displayedW = widthPct% of frameW; displayedH = displayedW /
     * imageAspect; heightPct = displayedH / frameH → widthPct * frameAspect / imageAspect.
     */
    fun imageHeightPercent(widthPercent: Float, imageAspect: Float, frameAspect: Float): Float {
        if (imageAspect <= 0f) return widthPercent
        return widthPercent * frameAspect / imageAspect
    }
}

