package dev.whitespc.roam.streaming.overlay

internal const val MAX_IMPORTED_IMAGE_DIMENSION = 16_384
internal const val MAX_IMPORTED_IMAGE_PIXELS = 16_000_000L

/** Validate decoded bounds without allocating a pixel buffer. */
internal fun hasSafeImportedImageBounds(width: Int, height: Int): Boolean =
    width in 1..MAX_IMPORTED_IMAGE_DIMENSION &&
        height in 1..MAX_IMPORTED_IMAGE_DIMENSION &&
        width.toLong() * height.toLong() <= MAX_IMPORTED_IMAGE_PIXELS
