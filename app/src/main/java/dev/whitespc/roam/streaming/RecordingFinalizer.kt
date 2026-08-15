package dev.whitespc.roam.streaming

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.whitespc.roam.diagnostics.RoamLog as Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val RECORDING_FINALIZER_TAG = "RoamRecordingFinalizer"
private val publicRecordingDir = "${Environment.DIRECTORY_MOVIES}/Roam"

/**
 * Process-owned recording publication. Engine teardown cannot cancel a copy,
 * and every new engine scans for app-private MP4s left by process death.
 */
internal object RecordingFinalizer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeRecordings = ConcurrentHashMap.newKeySet<String>()
    private val publishing = ConcurrentHashMap.newKeySet<String>()

    fun markRecording(path: String) {
        activeRecordings += path
    }

    fun finishRecording(context: Context, path: String) {
        activeRecordings -= path
        enqueue(context.applicationContext, File(path))
    }

    fun abandonRecording(path: String) {
        activeRecordings -= path
    }

    fun recoverPending(context: Context, directory: File) {
        val appContext = context.applicationContext
        scope.launch {
            // Publication may finish immediately before process death, leaving
            // only its tiny journal. With no private source left, the public
            // row is already complete and the journal can be discarded.
            directory.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".mp4.publishing") }
                ?.filter { journal ->
                    !File(directory, journal.name.removeSuffix(".publishing")).exists()
                }
                ?.forEach { runCatching { it.delete() } }

            recoverableRecordingFiles(directory, activeRecordings)
                .forEach { enqueue(appContext, it) }
        }
    }

    private fun enqueue(context: Context, privateFile: File) {
        val path = privateFile.absolutePath
        if (path in activeRecordings || !publishing.add(path)) return
        scope.launch {
            try {
                publish(context, privateFile)
            } finally {
                publishing -= path
            }
        }
    }

    private fun publish(context: Context, privateFile: File) {
        if (!privateFile.exists()) return
        if (privateFile.length() == 0L) {
            runCatching { privateFile.delete() }
            return
        }

        val resolver = context.contentResolver
        val journal = File(privateFile.parentFile, "${privateFile.name}.publishing")
        runCatching {
            // If the process died after inserting a row, remove that exact row
            // before retrying. A finished row is also safe to recreate because
            // the private source still exists and filenames are globally unique.
            journal.takeIf { it.exists() }
                ?.readText()
                ?.takeIf { it.isNotBlank() }
                ?.let(Uri::parse)
                ?.let { resolver.delete(it, null, null) }
            journal.delete()
            deleteOrphanPendingRows(context, privateFile.name)

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, privateFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, publicRecordingDir)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: error("MediaStore insert returned null")
            try {
                journal.writeText(uri.toString())
                resolver.openOutputStream(uri)?.use { output ->
                    privateFile.inputStream().use { input -> input.copyTo(output) }
                } ?: error("MediaStore output stream unavailable")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) > 0) {
                    "MediaStore did not publish recording"
                }
                check(privateFile.delete()) { "Private recording could not be removed" }
                journal.delete()
                Log.d(
                    RECORDING_FINALIZER_TAG,
                    "recording finalized to Movies/Roam/${privateFile.name}",
                )
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }.onFailure {
            Log.w(RECORDING_FINALIZER_TAG, "finalize recording failed", it)
        }
    }

    private fun deleteOrphanPendingRows(context: Context, displayName: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection =
            "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Video.Media.IS_PENDING} = 1"
        resolver.query(collection, projection, selection, arrayOf(displayName), null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }
}

internal fun recoverableRecordingFiles(
    directory: File,
    activePaths: Set<String>,
): List<File> = directory.listFiles()
    ?.filter { file ->
        file.isFile &&
            file.name.startsWith("roam-") &&
            file.extension.equals("mp4", ignoreCase = true) &&
            file.absolutePath !in activePaths
    }
    .orEmpty()
