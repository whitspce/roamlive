package dev.whitespc.roam.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores a bounded, sanitized diagnostic log in app-private storage. */
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

    fun append(level: String, tag: String, msg: String, throwable: Throwable? = null): String {
        val safeMessage = DiagnosticSanitizer.format(msg, throwable)
        val safeTag = DiagnosticSanitizer.sanitizeTag(tag)
        val d = dir ?: return safeMessage
        synchronized(lock) {
            runCatching {
                val file = File(d, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val old = File(d, FILE_NAME_OLD)
                    if (old.exists()) old.delete()
                    file.renameTo(old)
                }
                val ts = timestampFormat.format(Date())
                file.appendText("$ts $level/$safeTag: $safeMessage\n")
            }
        }
        return safeMessage
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
        val resolver = context.contentResolver
        var uri: android.net.Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val output = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open the diagnostics export")
            output.bufferedWriter().use { writer ->
                synchronized(lock) { writeCombined(writer, d) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) <= 0) {
                throw IOException("Could not publish the diagnostics export")
            }
            filename
        } catch (_: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
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
        writeSanitizedFile(writer, old)
        writeSanitizedFile(writer, current)
    }

    private fun writeSanitizedFile(writer: Writer, file: File) {
        if (!file.exists()) return
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                writer.write(DiagnosticSanitizer.sanitize(line))
                writer.write("\n")
            }
        }
    }

    /** Strip the path (which holds the stream key) from a stream URL, keep
     *  only `scheme://host[:port]/...`. Use this anywhere a URL would
     *  otherwise end up in a log line. */
    fun redactStreamUrl(url: String): String = runCatching {
        val uri = java.net.URI(url.trim())
        require(uri.scheme in setOf("rtmp", "rtmps", "rtmpt", "rtmpts", "srt"))
        val host = requireNotNull(uri.host)
        val displayHost = if (':' in host) "[$host]" else host
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "${uri.scheme}://$displayHost$port/..."
    }.getOrDefault("[redacted]")
}
