package dev.whitespc.roam.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupDestinationTest {

    @Test
    fun `YouTube guided setup builds the official secure destination`() {
        val result = buildDirectDestination(DirectPlatform.YOUTUBE, "test-key_123")
        val destination = result.successValue()

        assertEquals(
            "rtmps://a.rtmps.youtube.com:443/live2/test-key_123",
            destination.phoneEndpoint,
        )
        assertEquals(DirectPlatform.YOUTUBE, destination.platform)
        assertValid(destination.phoneEndpoint, SecureStreamTransport.RTMPS)
    }

    @Test
    fun `Kick guided setup builds the official secure destination`() {
        val result = buildDirectDestination(DirectPlatform.KICK, "test-key-456")
        val destination = result.successValue()

        assertEquals(
            "rtmps://fa723fc1b171.global-contribute.live-video.net:443/app/test-key-456",
            destination.phoneEndpoint,
        )
        assertEquals(DirectPlatform.KICK, destination.platform)
        assertValid(destination.phoneEndpoint, SecureStreamTransport.RTMPS)
    }

    @Test
    fun `guided stream keys are trimmed but reserved characters are rejected`() {
        assertEquals(
            "rtmps://a.rtmps.youtube.com:443/live2/test-key",
            buildDirectDestination(DirectPlatform.YOUTUBE, "  test-key\n")
                .successValue().phoneEndpoint,
        )
        listOf("", "   ", "key/path", "key?query", "key#fragment", "key%20value")
            .forEach { key ->
                assertTrue(buildDirectDestination(DirectPlatform.KICK, key) is
                    SetupDestinationResult.Failure)
            }
    }

    @Test
    fun `Twitch explicitly routes away from guided Direct`() {
        assertEquals(
            GuidedDirectAvailability.HOME_STUDIO_OR_ADVANCED,
            DirectPlatform.TWITCH.guidedAvailability,
        )
        assertEquals(
            SetupDestinationResult.Failure(
                SetupDestinationProblem.TWITCH_REQUIRES_HOME_STUDIO_OR_ADVANCED,
            ),
            buildDirectDestination(DirectPlatform.TWITCH, "credential-marker"),
        )
    }

    @Test
    fun `supported guided providers remain explicit`() {
        assertEquals(GuidedDirectAvailability.AVAILABLE, DirectPlatform.YOUTUBE.guidedAvailability)
        assertEquals(GuidedDirectAvailability.AVAILABLE, DirectPlatform.KICK.guidedAvailability)
    }

    @Test
    fun `Home Studio accepts only canonical Tailscale IPv4 literals`() {
        listOf(
            "100.64.0.0",
            "100.64.0.1",
            "100.100.20.30",
            "100.127.255.255",
        ).forEach { address ->
            assertTrue(
                address,
                buildHomeStudioDestination(address) is SetupDestinationResult.Success,
            )
        }

        listOf(
            "100.63.255.255",
            "100.128.0.0",
            "10.0.0.1",
            "192.168.1.20",
            "203.0.113.20",
            "100.064.0.1",
            "100.64.0.1.example",
            "host.tailnet.ts.net",
            "fd7a:115c:a1e0::1",
        ).forEach { address ->
            assertEquals(
                address,
                SetupDestinationProblem.TAILSCALE_IPV4_REQUIRED,
                (buildHomeStudioDestination(address) as SetupDestinationResult.Failure).problem,
            )
        }
    }

    @Test
    fun `Home Studio builds matching RootEncoder and OBS listener settings`() {
        val destination = buildHomeStudioDestination(" 100.100.20.30 ").successValue()
        val expectedPhone = "srt://100.100.20.30:1234/live" +
            "?passphrase=${destination.passphrase}" +
            "&pbkeylen=256&latency=2000"
        val expectedObs = "srt://100.100.20.30:1234?mode=listener" +
            "&passphrase=${destination.passphrase}" +
            "&pbkeylen=32&latency=2000000"

        assertEquals(expectedPhone, destination.phoneEndpoint)
        assertEquals(expectedObs, destination.obsMediaSourceUrl)
        assertEquals(HOME_STUDIO_INPUT_FORMAT, destination.inputFormat)
        assertEquals("mpegts", destination.inputFormat)
        assertValid(destination.phoneEndpoint, SecureStreamTransport.SRT)
    }

    @Test
    fun `Home Studio passphrases are random and URL safe`() {
        val first = buildHomeStudioDestination("100.100.20.30").successValue().passphrase
        val second = buildHomeStudioDestination("100.100.20.30").successValue().passphrase

        assertEquals(32, first.length)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(first, second)
    }

    @Test
    fun `result strings and errors do not expose credentials`() {
        val marker = "credential-marker-123"
        val direct = buildDirectDestination(DirectPlatform.KICK, marker)
        val home = buildHomeStudioDestination("100.100.20.30")
        val invalid = buildDirectDestination(DirectPlatform.KICK, "$marker/path")

        assertFalse(direct.toString().contains(marker))
        assertFalse(direct.successValue().toString().contains(marker))
        val homeDestination = home.successValue()
        assertFalse(home.toString().contains(homeDestination.passphrase))
        assertFalse(homeDestination.toString().contains(homeDestination.passphrase))
        assertFalse(invalid.toString().contains(marker))
    }

    private fun <T> SetupDestinationResult<T>.successValue(): T {
        assertTrue(this is SetupDestinationResult.Success)
        return (this as SetupDestinationResult.Success).value
    }

    private fun assertValid(endpoint: String, transport: SecureStreamTransport) {
        assertEquals(
            StreamEndpointValidation.Valid(transport),
            validateStreamEndpoint(endpoint),
        )
    }
}
