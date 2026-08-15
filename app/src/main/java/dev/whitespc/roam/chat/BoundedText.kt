package dev.whitespc.roam.chat

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads a UTF-8 response only when it fits within the caller's memory budget. */
internal fun InputStream.readUtf8Bounded(maxBytes: Int): String? {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_INITIAL_CAPACITY))
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

private const val BUFFER_BYTES = 8 * 1024
private const val DEFAULT_INITIAL_CAPACITY = 64 * 1024
