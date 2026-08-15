package dev.whitespc.roam.chat

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedTextTest {
    @Test
    fun `reads payload at the byte limit`() {
        val text = "hello"

        assertEquals(
            text,
            ByteArrayInputStream(text.toByteArray()).readUtf8Bounded(text.toByteArray().size),
        )
    }

    @Test
    fun `rejects payload over the byte limit`() {
        assertNull(ByteArrayInputStream(ByteArray(33)).readUtf8Bounded(32))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an invalid limit`() {
        ByteArrayInputStream(byteArrayOf()).readUtf8Bounded(0)
    }
}
