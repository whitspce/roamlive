package dev.whitespc.roam.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamUrlEditPolicyTest {

    @Test
    fun `empty input clears both active URL and draft`() {
        assertEquals(StreamUrlStorageTarget.CLEAR, streamUrlStorageTarget(""))
    }

    @Test
    fun `complete secure destinations become active`() {
        assertEquals(
            StreamUrlStorageTarget.ACTIVE,
            streamUrlStorageTarget("rtmps://live.example.test/app/stream-key"),
        )
        assertEquals(
            StreamUrlStorageTarget.ACTIVE,
            streamUrlStorageTarget(
                "srt://studio.example.test:1234/live" +
                    "?passphrase=test-passphrase-123&pbkeylen=256&latency=2000",
            ),
        )
    }

    @Test
    fun `partial and invalid destinations remain drafts`() {
        listOf(
            "srt://",
            "srt://studio.example.test:1234/live?passphrase=partial",
            "srt://studio.example.test:1234?mode=listener&passphrase=test-passphrase-123",
            "rtmp://live.example.test/app/stream-key",
        ).forEach { input ->
            assertEquals(StreamUrlStorageTarget.DRAFT, streamUrlStorageTarget(input))
        }
    }

    @Test
    fun `policy results never retain credentials`() {
        val marker = "private-credential-marker"
        val result = streamUrlStorageTarget("rtmps://live.example.test/app/$marker")

        assertEquals(StreamUrlStorageTarget.ACTIVE, result)
        assertFalse(result.toString().contains(marker))
    }
}
