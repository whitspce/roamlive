package dev.whitespc.roam.storage

import dev.whitespc.roam.streaming.StreamEndpointValidation
import dev.whitespc.roam.streaming.validateStreamEndpoint

/** Where an edited stream URL belongs. The result never retains the URL. */
internal enum class StreamUrlStorageTarget {
    CLEAR,
    ACTIVE,
    DRAFT,
}

internal fun streamUrlStorageTarget(input: String): StreamUrlStorageTarget = when {
    input.isEmpty() -> StreamUrlStorageTarget.CLEAR
    validateStreamEndpoint(input) is StreamEndpointValidation.Valid ->
        StreamUrlStorageTarget.ACTIVE
    else -> StreamUrlStorageTarget.DRAFT
}
