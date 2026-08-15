package dev.whitespc.roam.update

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `valid manifest uses the app-owned update page`() {
        val update = validatedUpdate(18, "1.0.0-beta1")

        assertEquals(18, update?.versionCode)
        assertEquals("1.0.0-beta1", update?.versionName)
        assertEquals("https://roamlive.app/", update?.url)
    }

    @Test
    fun `invalid manifest fields are rejected`() {
        assertNull(validatedUpdate(0, "1.0.0"))
        assertNull(validatedUpdate(18, ""))
        assertNull(validatedUpdate(18, "1.0.0\nhttps://example.invalid"))
        assertNull(validatedUpdate(18, "v".repeat(33)))
    }

    @Test
    fun `manifest input is bounded by bytes`() {
        val exact = ByteArrayInputStream("1234".toByteArray())
        val oversized = ByteArrayInputStream("12345".toByteArray())

        assertEquals("1234", readUtf8WithLimit(exact, 4))
        assertNull(readUtf8WithLimit(oversized, 4))
    }
}
