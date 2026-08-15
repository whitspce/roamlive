package dev.whitespc.roam.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatInputsTest {

    @Test
    fun `platform channel inputs are normalized`() {
        assertEquals("channel-name", normalizeKickChannel(" @Channel-Name "))
        assertEquals("channel_name", normalizeTwitchChannel("#Channel_Name"))
        assertEquals("@Channel.Name", normalizeYouTubeChannel(" @Channel.Name "))
    }

    @Test
    fun `control characters and command injection are rejected`() {
        assertNull(normalizeKickChannel("channel/path"))
        assertNull(normalizeTwitchChannel("name\r\nJOIN #other"))
        assertNull(normalizeYouTubeChannel("name\nother"))
    }

    @Test
    fun `messages are bounded and receive unique display keys`() {
        val original = ChatMessage(
            id = "id",
            platform = ChatPlatform.Twitch,
            username = " user ",
            usernameColor = 0,
            text = "x".repeat(1_100),
            timestampMs = 0,
            eventLabel = " label ",
        )

        val first = sanitizeChatMessage(original, 1)
        val second = sanitizeChatMessage(original, 2)

        assertEquals("user", first?.username)
        assertEquals(1_000, first?.text?.length)
        assertEquals("label", first?.eventLabel)
        assertEquals(false, first?.id == second?.id)
    }
}
