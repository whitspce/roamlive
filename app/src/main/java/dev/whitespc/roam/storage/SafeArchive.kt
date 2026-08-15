package dev.whitespc.roam.storage

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry

/** Bounded ZIP extraction shared by settings backups and local web overlays. */
internal class SafeArchiveBudget(
    private val maxEntries: Int,
    private val maxEntryBytes: Long,
    private val maxTotalBytes: Long,
) {
    private val names = HashSet<String>()
    private var entries = 0
    private var totalBytes = 0L
    private var currentEntry: String? = null
    private var currentEntryBytes = 0L

    init {
        require(maxEntries > 0) { "entry limit must be positive" }
        require(maxEntryBytes > 0) { "entry size limit must be positive" }
        require(maxTotalBytes > 0) { "archive size limit must be positive" }
    }

    fun begin(entry: ZipEntry): String {
        require(currentEntry == null) { "previous archive entry was not finished" }
        entries++
        require(entries <= maxEntries) { "archive has too many entries" }
        val name = safeArchiveName(entry.name)
        require(names.add(name)) { "archive contains duplicate entry: $name" }
        if (entry.size >= 0) {
            require(entry.size <= maxEntryBytes) { "archive entry is too large: $name" }
            require(entry.size <= maxTotalBytes - totalBytes) { "archive is too large" }
        }
        currentEntry = name
        currentEntryBytes = 0L
        return name
    }

    fun copy(
        input: InputStream,
        output: OutputStream,
        entryLimitBytes: Long = maxEntryBytes,
    ): Long {
        requireNotNull(currentEntry) { "archive entry was not started" }
        require(entryLimitBytes in 1..maxEntryBytes) { "invalid entry size limit" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            require(read.toLong() <= entryLimitBytes - currentEntryBytes) {
                "archive entry exceeds size limit"
            }
            require(read.toLong() <= maxTotalBytes - totalBytes) {
                "archive exceeds size limit"
            }
            currentEntryBytes += read
            totalBytes += read
            output.write(buffer, 0, read)
        }
        return currentEntryBytes
    }

    fun discard(input: InputStream): Long = copy(
        input,
        object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        },
    )

    fun finish() {
        requireNotNull(currentEntry) { "archive entry was not started" }
        currentEntry = null
        currentEntryBytes = 0L
    }
}

/** A portable, relative ZIP path without traversal or ambiguous separators. */
internal fun safeArchiveName(raw: String): String {
    require(raw.isNotBlank()) { "archive entry has no name" }
    require(raw.length <= 512) { "archive entry name is too long" }
    require(raw.none { it == '\\' || it.isISOControl() }) {
        "archive entry has an invalid name"
    }
    require(!raw.startsWith('/')) { "archive entry is absolute" }
    val trimmed = raw.trimEnd('/')
    require(trimmed.isNotBlank()) { "archive entry has no name" }
    val parts = trimmed.split('/')
    require(parts.none { it.isBlank() || it == "." || it == ".." }) {
        "archive entry has an unsafe path"
    }
    return parts.joinToString("/")
}

/** Resolve an already validated archive path and verify the canonical result. */
internal fun resolveArchiveFile(root: File, relativeName: String): File {
    val canonicalRoot = root.canonicalFile
    val output = File(canonicalRoot, safeArchiveName(relativeName)).canonicalFile
    require(output.path.startsWith(canonicalRoot.path + File.separator)) {
        "archive entry escapes its destination"
    }
    return output
}
