package dev.whitespc.roam.streaming.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageImportLimitsTest {

    @Test
    fun acceptsNormalImagesAndBoundaryPixelCount() {
        assertTrue(hasSafeImportedImageBounds(4032, 3024))
        assertTrue(hasSafeImportedImageBounds(4_000, 4_000))
    }

    @Test
    fun rejectsInvalidHugeAndOverflowingBounds() {
        assertFalse(hasSafeImportedImageBounds(0, 1080))
        assertFalse(hasSafeImportedImageBounds(16_385, 1))
        assertFalse(hasSafeImportedImageBounds(8_000, 6_000))
        assertFalse(hasSafeImportedImageBounds(Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
