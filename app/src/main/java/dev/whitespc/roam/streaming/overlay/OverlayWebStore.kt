package dev.whitespc.roam.streaming.overlay

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

private const val TAG = "RoamOverlayWeb"
private const val WEB_DIR = "overlay_web"

/**
 * Imports local web overlays into app-private storage.
 *
 * A web overlay can be a single self-contained `.html` file, or a `.zip` bundle
 * (HTML + CSS + JS + image assets). We copy or unzip the picked file into the
 * app's own files dir — `files/overlay_web/<uuid>/` — so the overlay is stable
 * (the user can move or delete their original) and so the WebView can load it
 * with `file://` paths where relative asset references resolve.
 *
 * Each overlay gets its own `<uuid>` folder. Deleting the overlay deletes that
 * folder. The user never sees these files; they manage overlays in the editor.
 */
object OverlayWebStore {

    /**
     * Import the picked [uri] (a `.html` file or a `.zip` bundle). Returns a
     * `file://` URL to the entry HTML, or null on failure.
     */
    fun importLocal(context: Context, uri: Uri): String? {
        val name = displayName(context, uri).lowercase()
        val isZip = name.endsWith(".zip")
        val dir = File(File(context.filesDir, WEB_DIR), UUID.randomUUID().toString())
        dir.mkdirs()
        return runCatching {
            if (isZip) {
                unzip(context, uri, dir)
                val entry = findEntryHtml(dir)
                    ?: return failAndClean(dir, "no .html file inside the zip")
                "file://${entry.absolutePath}"
            } else {
                val dest = File(dir, "index.html")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return failAndClean(dir, "could not read the picked file")
                "file://${dest.absolutePath}"
            }
        }.getOrElse { failAndClean(dir, it.message ?: "import failed") }
    }

    /**
     * Delete the imported folder behind a `file://` overlay URL. Safe to call on
     * a non-local (http) URL — it does nothing. Walks up from the entry file to
     * the per-overlay `<uuid>` folder and removes the whole thing.
     */
    fun delete(fileUrl: String) {
        if (!fileUrl.startsWith("file://")) return
        runCatching {
            var node: File? = File(fileUrl.removePrefix("file://"))
            while (node != null && node.parentFile?.name != WEB_DIR) {
                node = node.parentFile
            }
            node?.deleteRecursively()
        }.onFailure { Log.w(TAG, "delete failed for $fileUrl", it) }
    }

    private fun unzip(context: Context, uri: Uri, targetDir: File) {
        val canonicalTarget = targetDir.canonicalPath
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("could not open the zip")
        input.buffered().use { buffered ->
            ZipInputStream(buffered).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    // Zip-slip guard: reject any entry that resolves outside the
                    // target folder (e.g. a name containing "../").
                    val canonicalOut = outFile.canonicalPath
                    if (canonicalOut != canonicalTarget &&
                        !canonicalOut.startsWith(canonicalTarget + File.separator)
                    ) {
                        throw SecurityException("zip entry escapes target: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output -> zis.copyTo(output) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    /** The entry HTML inside an unzipped bundle: an `index.html` if present,
     *  otherwise the shallowest `.html` file. Null if the bundle has none. */
    private fun findEntryHtml(dir: File): File? {
        val htmlFiles = dir.walkTopDown()
            .filter { it.isFile && it.extension.equals("html", ignoreCase = true) }
            .toList()
        return htmlFiles.firstOrNull { it.name.equals("index.html", ignoreCase = true) }
            ?: htmlFiles.minByOrNull { it.absolutePath.count { ch -> ch == File.separatorChar } }
    }

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun failAndClean(dir: File, reason: String): String? {
        Log.w(TAG, "web overlay import failed: $reason")
        runCatching { dir.deleteRecursively() }
        return null
    }
}
