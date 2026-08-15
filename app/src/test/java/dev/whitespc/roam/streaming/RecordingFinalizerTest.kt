package dev.whitespc.roam.streaming

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingFinalizerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `recovery finds only completed Roam recording candidates`() {
        val directory = temporaryFolder.newFolder("recordings")
        val recoverable = directory.resolve("roam-20260815-120000.mp4").apply {
            writeBytes(byteArrayOf(1))
        }
        val active = directory.resolve("roam-20260815-120001.mp4").apply {
            writeBytes(byteArrayOf(2))
        }
        directory.resolve("other-app.mp4").writeBytes(byteArrayOf(3))
        directory.resolve("roam-20260815-120002.mp4.publishing").writeText("content://pending")
        directory.resolve("roam-directory.mp4").mkdir()

        assertEquals(
            listOf(recoverable),
            recoverableRecordingFiles(directory, setOf(active.absolutePath)),
        )
    }
}
