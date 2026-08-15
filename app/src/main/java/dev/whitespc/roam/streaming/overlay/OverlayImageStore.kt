package dev.whitespc.roam.streaming.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dev.whitespc.roam.diagnostics.RoamLog as Log
import dev.whitespc.roam.storage.SafeArchiveBudget
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry

private const val TAG = "RoamOverlayImages"
private const val IMAGE_DIR = "overlay_images"
private const val MAX_IMAGE_BYTES = 16L * 1024 * 1024

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
        val dir = File(context.filesDir, IMAGE_DIR)
        if (!dir.mkdirs() && !dir.isDirectory) return null
        val id = UUID.randomUUID().toString()
        val staging = File(dir, ".import-$id")
        val destination = File(dir, "ovl_$id.img")
        return runCatching {
            val budget = SafeArchiveBudget(1, MAX_IMAGE_BYTES, MAX_IMAGE_BYTES)
            budget.begin(ZipEntry("image"))
            context.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> budget.copy(input, output) }
            } ?: throw IllegalStateException("could not read the selected image")
            budget.finish()
            require(hasSafeImageBounds(staging)) { "image dimensions are unsafe" }
            require(!destination.exists() && staging.renameTo(destination))
            destination.canonicalPath
        }.getOrElse {
            Log.w(TAG, "image import failed", it)
            runCatching { staging.delete() }
            runCatching { destination.delete() }
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
    fun deleteImage(context: Context, path: String) {
        runCatching {
            val root = File(context.filesDir, IMAGE_DIR).canonicalFile
            val image = File(path).canonicalFile
            require(image.parentFile == root && isManagedImageName(image.name))
            require(image.delete() || !image.exists())
        }.onFailure { Log.w(TAG, "could not delete a local overlay image", it) }
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

    internal fun isManagedImageName(name: String): Boolean {
        if (!name.startsWith("ovl_") || !name.endsWith(".img")) return false
        val id = name.removePrefix("ovl_").removeSuffix(".img")
        return runCatching { UUID.fromString(id) }.isSuccess
    }

    /** Read only image metadata. No decoded pixel buffer is allocated. */
    internal fun hasSafeImageBounds(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return hasSafeImportedImageBounds(bounds.outWidth, bounds.outHeight)
    }
}
