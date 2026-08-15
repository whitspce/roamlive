package dev.whitespc.roam.streaming.overlay

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.whitespc.roam.diagnostics.RoamLog as Log
import dev.whitespc.roam.storage.SafeArchiveBudget
import dev.whitespc.roam.storage.resolveArchiveFile
import dev.whitespc.roam.storage.safeArchiveName
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val TAG = "RoamOverlayWeb"
private const val WEB_DIR = "overlay_web"
private const val LOCAL_SCHEME = "roam-overlay"
private const val MAX_ARCHIVE_ENTRIES = 512
private const val MAX_FILE_BYTES = 8L * 1024 * 1024
private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
private const val MAX_REMOTE_URL_CHARS = 8 * 1024

internal data class LocalWebOverlay(
    val root: File,
    val entry: File,
    val relativePath: String,
)

/** Imports and resolves app-managed HTML overlays. */
object OverlayWebStore {

    /**
     * Import a self-contained HTML file or a bounded ZIP bundle. The returned
     * reference reveals no private filesystem path and can only be resolved
     * inside this app's managed web-overlay directory.
     */
    fun importLocal(context: Context, uri: Uri): String? {
        val displayName = displayName(context, uri).lowercase(Locale.ROOT)
        val mimeType = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        val isZip = displayName.endsWith(".zip") || mimeType in ZIP_MIME_TYPES
        val isHtml = displayName.endsWith(".html") || displayName.endsWith(".htm") ||
            mimeType == "text/html"
        if (!isZip && !isHtml) {
            Log.w(TAG, "web overlay import rejected: expected HTML or ZIP")
            return null
        }

        val id = UUID.randomUUID().toString()
        val webRoot = File(context.filesDir, WEB_DIR)
        if (!webRoot.mkdirs() && !webRoot.isDirectory) return null
        val staging = File(webRoot, ".import-$id")
        val target = File(webRoot, id)
        if (!staging.mkdir()) return null

        return runCatching {
            val stagedEntry = if (isZip) {
                unzip(context, uri, staging)
                findEntryHtml(staging)
                    ?: throw IllegalArgumentException("no HTML file inside the ZIP")
            } else {
                val destination = File(staging, "index.html")
                val budget = SafeArchiveBudget(1, MAX_FILE_BYTES, MAX_TOTAL_BYTES)
                budget.begin(ZipEntry("index.html"))
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> budget.copy(input, output) }
                } ?: throw IllegalStateException("could not read the selected file")
                budget.finish()
                destination
            }

            val entryRelative = stagedEntry.relativeTo(staging).invariantSeparatorsPath
            require(target.parentFile == webRoot && !target.exists())
            require(staging.renameTo(target)) { "could not finish the overlay import" }
            localUrlForRelative("$id/$entryRelative")
        }.getOrElse { error ->
            Log.w(TAG, "web overlay import failed: ${error.message ?: "unknown error"}")
            runCatching { staging.deleteRecursively() }
            runCatching { target.deleteRecursively() }
            null
        }
    }

    fun isLocalUrl(value: String): Boolean =
        value.startsWith("$LOCAL_SCHEME://") || value.startsWith("file://")

    /** True only for a well-formed HTTPS address without embedded credentials. */
    fun isSafeHttpsUrl(value: String): Boolean = runCatching {
        if (value.isBlank() || value.length > MAX_REMOTE_URL_CHARS || value != value.trim()) {
            return@runCatching false
        }
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.isNotBlank() == true &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port in 1..65_535)
    }.getOrDefault(false)

    /** Resolve current references and legacy file URLs to one managed bundle. */
    internal fun resolveLocal(context: Context, value: String): LocalWebOverlay? = runCatching {
        val webRoot = File(context.filesDir, WEB_DIR).canonicalFile
        val relative = managedRelativePath(context, value) ?: return null
        val firstSegment = relative.substringBefore('/')
        require(runCatching { UUID.fromString(firstSegment) }.isSuccess)
        val overlayRoot = File(webRoot, firstSegment).canonicalFile
        require(overlayRoot.parentFile == webRoot)
        val entry = resolveArchiveFile(webRoot, relative)
        require(entry.path.startsWith(overlayRoot.path + File.separator))
        require(entry.isFile)
        LocalWebOverlay(overlayRoot, entry, relative)
    }.getOrNull()

    /** Relative `<uuid>/path` used by backups. Accepts old file references. */
    internal fun managedRelativePath(context: Context, value: String): String? = runCatching {
        val uri = Uri.parse(value)
        when {
            uri.scheme.equals(LOCAL_SCHEME, ignoreCase = true) -> {
                val id = requireNotNull(uri.host)
                require(uri.authority.equals(id, ignoreCase = true))
                require(runCatching { UUID.fromString(id) }.isSuccess)
                val path = uri.path.orEmpty().removePrefix("/")
                require(path.isNotBlank())
                safeArchiveName("$id/$path")
            }
            uri.scheme.equals("file", ignoreCase = true) -> {
                require(uri.authority.isNullOrBlank())
                val webRoot = File(context.filesDir, WEB_DIR).canonicalFile
                val file = File(requireNotNull(uri.path)).canonicalFile
                require(file.path.startsWith(webRoot.path + File.separator))
                safeArchiveName(file.relativeTo(webRoot).invariantSeparatorsPath)
            }
            else -> null
        }
    }.getOrNull()

    internal fun localUrlForRelative(relativePath: String): String {
        val safe = safeArchiveName(relativePath)
        val parts = safe.split('/')
        require(parts.size >= 2) { "local web reference has no entry file" }
        require(runCatching { UUID.fromString(parts.first()) }.isSuccess) {
            "local web reference has an invalid id"
        }
        return Uri.Builder()
            .scheme(LOCAL_SCHEME)
            .authority(parts.first())
            .apply { parts.drop(1).forEach(::appendPath) }
            .build()
            .toString()
    }

    /** Delete only the UUID directory named by an app-managed reference. */
    fun delete(context: Context, value: String) {
        val relative = managedRelativePath(context, value) ?: return
        runCatching {
            val webRoot = File(context.filesDir, WEB_DIR).canonicalFile
            val id = relative.substringBefore('/')
            require(runCatching { UUID.fromString(id) }.isSuccess)
            val overlayDir = File(webRoot, id).canonicalFile
            require(overlayDir.parentFile == webRoot)
            require(overlayDir.deleteRecursively() || !overlayDir.exists())
        }.onFailure { Log.w(TAG, "could not delete a local web overlay", it) }
    }

    private fun unzip(context: Context, uri: Uri, targetDir: File) {
        val budget = SafeArchiveBudget(
            MAX_ARCHIVE_ENTRIES,
            MAX_FILE_BYTES,
            MAX_TOTAL_BYTES,
        )
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("could not open the ZIP")
        input.buffered().use { buffered ->
            ZipInputStream(buffered).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val safeName = budget.begin(entry)
                    val output = resolveArchiveFile(targetDir, safeName)
                    if (entry.isDirectory) {
                        require(output.mkdirs() || output.isDirectory)
                        budget.discard(zip)
                    } else {
                        require(output.parentFile?.let { it.mkdirs() || it.isDirectory } == true)
                        output.outputStream().use { budget.copy(zip, it) }
                    }
                    zip.closeEntry()
                    budget.finish()
                    entry = zip.nextEntry
                }
            }
        }
    }

    private fun findEntryHtml(dir: File): File? = dir.walkTopDown()
        .filter { file ->
            file.isFile && (file.extension.equals("html", ignoreCase = true) ||
                file.extension.equals("htm", ignoreCase = true))
        }
        .map { it to it.relativeTo(dir).invariantSeparatorsPath }
        .sortedWith(
            compareBy<Pair<File, String>>(
                { it.second.count { char -> char == '/' } },
                { if (it.first.name.equals("index.html", true) || it.first.name.equals("index.htm", true)) 0 else 1 },
                { it.second.lowercase(Locale.ROOT) },
            ),
        )
        .map { it.first }
        .firstOrNull()

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private val ZIP_MIME_TYPES = setOf(
        "application/zip",
        "application/x-zip-compressed",
    )
}
