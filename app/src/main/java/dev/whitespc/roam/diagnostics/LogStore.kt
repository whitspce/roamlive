package dev.whitespc.roam.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only diagnostic log written to app-private storage as a rolling file.
 * The user exports it via Settings > Diagnostics > Save logs to Downloads,
 * which writes a timestamped snapshot into the phone's Downloads folder. From
 * there they can open it, share it via the Files app, attach it to an email,
 * whatever — without us needing a separate Share intent.
 *
 * Pairs with [RoamLog], which writes through to here alongside the system
 * Android logger. Files (17 of them across streaming/chat/overlay) opt in by
 * aliasing their `import android.util.Log` to RoamLog.
 *
 * Rolls into log.1.txt at MAX_BYTES (previous gets overwritten), so worst-case
 * on disk is ~2 × MAX_BYTES.
 *
 * **Privacy rule**: never log a stream URL or any other secret. The exported
 * file is meant to be shared. Use [redactStreamUrl] for URL-like values.
 */
object LogStore {
    private const val FILE_NAME = "log.txt"
    private const val FILE_NAME_OLD = "log.1.txt"
    private const val MAX_BYTES = 5L * 1024 * 1024  // 5 MB per slot, 10 MB total

    private val lock = Any()
    private var dir: File? = null
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val d = File(context.filesDir, "diagnostics")
        if (!d.exists()) d.mkdirs()
        dir = d
    }

    fun append(level: String, tag: String, msg: String, throwable: Throwable? = null) {
        val d = dir ?: return
        synchronized(lock) {
            runCatching {
                val file = File(d, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val old = File(d, FILE_NAME_OLD)
                    if (old.exists()) old.delete()
                    file.renameTo(old)
                }
                val ts = timestampFormat.format(Date())
                val tail = throwable?.let {
                    " | ${it::class.java.simpleName}: ${it.message}"
                } ?: ""
                file.appendText("$ts $level/$tag: $msg$tail\n")
            }
        }
    }

    /** Save the combined log snapshot to the user's public Downloads folder
     *  via MediaStore so they can find it in their Files app. Returns the
     *  generated filename on success (for the confirmation toast), null on
     *  failure. No permission needed: the app owns the file it creates. */
    fun saveToDownloads(context: Context): String? {
        val d = dir ?: return null
        val filename = "roam-diagnostics-${
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        }.txt"
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { writeCombined(it, d) }
            // Clear IS_PENDING so the file becomes visible to other apps
            // (Files, file managers, etc.).
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            filename
        }.getOrNull()
    }

    /** Writes the device header + both log roll files to [writer]. Shared by
     *  the share-via-FileProvider path and the save-to-Downloads path so both
     *  always produce identical content. */
    private fun writeCombined(writer: Writer, d: File) {
        val current = File(d, FILE_NAME)
        val old = File(d, FILE_NAME_OLD)
        writer.write("# Roam Live diagnostics\n")
        writer.write(
            "# Device: ${android.os.Build.MANUFACTURER} " +
                "${android.os.Build.MODEL} (Android " +
                "${android.os.Build.VERSION.RELEASE})\n",
        )
        writer.write("# Generated: ${timestampFormat.format(Date())}\n\n")
        if (old.exists()) writer.write(old.readText())
        if (current.exists()) writer.write(current.readText())
    }

    /** Strip the path (which holds the stream key) from a stream URL, keep
     *  only `scheme://host[:port]/...`. Use this anywhere a URL would
     *  otherwise end up in a log line. */
    fun redactStreamUrl(url: String): String = runCatching {
        val uri = java.net.URI(url.trim())
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "${uri.scheme}://${uri.host}$port/..."
    }.getOrDefault("[redacted]")
}
