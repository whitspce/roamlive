package dev.whitespc.roam.obs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsBrbPrivacyLatchTest {

    @Test
    fun `disconnect cannot clear confirmed BRB protection`() {
        val latch = ObsBrbPrivacyLatch()

        assertTrue(latch.update(true, "BRB", connected = true, currentScene = "BRB"))
        assertTrue(latch.update(true, "BRB", connected = false, currentScene = null))
        assertTrue(latch.update(true, "BRB", connected = true, currentScene = null))
    }

    @Test
    fun `confirmed non BRB scene clears protection`() {
        val latch = ObsBrbPrivacyLatch()

        latch.update(true, "BRB", connected = true, currentScene = "BRB")

        assertFalse(latch.update(true, "BRB", connected = true, currentScene = "Main"))
    }

    @Test
    fun `explicitly disabling protection clears the latch`() {
        val latch = ObsBrbPrivacyLatch()

        latch.update(true, "BRB", connected = true, currentScene = "BRB")

        assertFalse(latch.update(false, "BRB", connected = false, currentScene = null))
    }
}
