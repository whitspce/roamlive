package dev.whitespc.roam.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamEndpointTest {

    @Test
    fun `blank destination is rejected before streaming starts`() {
        assertProblem("", StreamEndpointProblem.EMPTY)
    }

    @Test
    fun `secure RTMP transports require a host and meaningful path`() {
        assertValid(
            "rtmps://live.example.test/app/test-stream-key",
            SecureStreamTransport.RTMPS,
        )
        assertValid(
            "rtmpts://live.example.test:443/app/test-stream-key?session=test",
            SecureStreamTransport.RTMPS,
        )
        assertProblem("rtmps://live.example.test", StreamEndpointProblem.MISSING_PATH)
        assertProblem("rtmps://live.example.test/", StreamEndpointProblem.MISSING_PATH)
        assertProblem("rtmps:///app/key", StreamEndpointProblem.INVALID_HOST)
    }

    @Test
    fun `plaintext and unrelated transports are rejected`() {
        listOf(
            "rtmp://live.example.test/app/key",
            "rtmpt://live.example.test/app/key",
            "https://live.example.test/app/key",
            "ftp://live.example.test/app/key",
        ).forEach { endpoint ->
            assertProblem(endpoint, StreamEndpointProblem.UNSUPPORTED_TRANSPORT)
        }
    }

    @Test
    fun `encrypted SRT accepts a path or explicit streamid`() {
        assertValid(
            "srt://studio.example.test:1234/live" +
                "?passphrase=test-passphrase-123&pbkeylen=256&latency=2000",
            SecureStreamTransport.SRT,
        )
        assertValid(
            "srt://[2001:db8::1]:1234/" +
                "?streamid=publish-route&passphrase=test-passphrase-123",
            SecureStreamTransport.SRT,
        )
    }

    @Test
    fun `SRT requires an explicit valid port and stream id`() {
        assertProblem(
            "srt://studio.example.test/live?passphrase=test-passphrase-123",
            StreamEndpointProblem.MISSING_SRT_PORT,
        )
        assertProblem(
            "srt://studio.example.test:0/live?passphrase=test-passphrase-123",
            StreamEndpointProblem.INVALID_PORT,
        )
        assertProblem(
            "srt://studio.example.test:70000/live?passphrase=test-passphrase-123",
            StreamEndpointProblem.INVALID_PORT,
        )
        assertProblem(
            "srt://studio.example.test:1234/?passphrase=test-passphrase-123",
            StreamEndpointProblem.MISSING_SRT_STREAM_ID,
        )
    }

    @Test
    fun `SRT passphrase is mandatory and strictly bounded`() {
        assertProblem(
            "srt://studio.example.test:1234/live",
            StreamEndpointProblem.MISSING_SRT_PASSPHRASE,
        )
        assertProblem(
            "srt://studio.example.test:1234/live?passphrase=too-short",
            StreamEndpointProblem.INVALID_SRT_PASSPHRASE,
        )
        assertProblem(
            "srt://studio.example.test:1234/live?passphrase=${"x".repeat(80)}",
            StreamEndpointProblem.INVALID_SRT_PASSPHRASE,
        )
        assertValid(
            "srt://studio.example.test:1234/live?passphrase=${"x".repeat(10)}",
            SecureStreamTransport.SRT,
        )
        assertValid(
            "srt://studio.example.test:1234/live?passphrase=${"x".repeat(79)}",
            SecureStreamTransport.SRT,
        )
    }

    @Test
    fun `SRT query parameters reject unknown names and duplicates`() {
        assertProblem(
            "srt://studio.example.test:1234" +
                "?mode=listener&passphrase=test-passphrase-123" +
                "&pbkeylen=32&latency=2000000",
            StreamEndpointProblem.SRT_LISTENER_URL_NOT_ALLOWED,
        )
        assertProblem(
            "srt://studio.example.test:1234/live" +
                "?passphrase=test-passphrase-123&mode=LISTENER",
            StreamEndpointProblem.SRT_LISTENER_URL_NOT_ALLOWED,
        )
        assertProblem(
            "srt://studio.example.test:1234/live" +
                "?passphrase=test-passphrase-123&mode=caller",
            StreamEndpointProblem.SRT_MODE_NOT_ALLOWED,
        )
        assertProblem(
            "srt://studio.example.test:1234/live" +
                "?passphrase=test-passphrase-123&passphrase=another-passphrase",
            StreamEndpointProblem.DUPLICATE_SRT_PARAMETER,
        )
        assertProblem(
            "srt://studio.example.test:1234/live" +
                "?passphrase=test-passphrase-123&pass%70hrase=another-passphrase",
            StreamEndpointProblem.UNKNOWN_SRT_PARAMETER,
        )
        assertProblem(
            "srt://studio.example.test:1234/live" +
                "?passphrase=test-passphrase-123&password=another-passphrase",
            StreamEndpointProblem.UNKNOWN_SRT_PARAMETER,
        )
    }

    @Test
    fun `SRT encryption key length only accepts supported values`() {
        listOf("0", "64", "129", "00128", "512", "text").forEach { value ->
            assertProblem(
                "srt://studio.example.test:1234/live" +
                    "?passphrase=test-passphrase-123&pbkeylen=$value",
                StreamEndpointProblem.INVALID_SRT_KEY_LENGTH,
            )
        }
        listOf("128", "192", "256").forEach { value ->
            assertValid(
                "srt://studio.example.test:1234/live" +
                    "?passphrase=test-passphrase-123&pbkeylen=$value",
                SecureStreamTransport.SRT,
            )
        }
    }

    @Test
    fun `SRT latency is an integer within the library limit`() {
        listOf("-1", "65536", "+1", "1.5", "text", "000001").forEach { value ->
            assertProblem(
                "srt://studio.example.test:1234/live" +
                    "?passphrase=test-passphrase-123&latency=$value",
                StreamEndpointProblem.INVALID_SRT_LATENCY,
            )
        }
        listOf("0", "2000", "65535").forEach { value ->
            assertValid(
                "srt://studio.example.test:1234/live" +
                    "?passphrase=test-passphrase-123&latency=$value",
                SecureStreamTransport.SRT,
            )
        }
    }

    @Test
    fun `userinfo fragments whitespace and control characters are rejected`() {
        assertProblem(
            "rtmps://user:password@live.example.test/app/key",
            StreamEndpointProblem.USER_INFO_NOT_ALLOWED,
        )
        assertProblem(
            "rtmps://live.example.test/app/key#fragment",
            StreamEndpointProblem.FRAGMENT_NOT_ALLOWED,
        )
        assertProblem(
            "rtmps://live.example.test/app/my key",
            StreamEndpointProblem.INVALID_CHARACTERS,
        )
        assertProblem(
            "rtmps://live.example.test/app/my%20key",
            StreamEndpointProblem.INVALID_CHARACTERS,
        )
        assertProblem(
            "rtmps://live.example.test/app/key\nother",
            StreamEndpointProblem.INVALID_CHARACTERS,
        )
    }

    @Test
    fun `malformed invalid port and oversized destinations are rejected`() {
        assertProblem("rtmps://[not-ipv6]/app/key", StreamEndpointProblem.MALFORMED)
        assertProblem("rtmps://live.example.test:abc/app/key", StreamEndpointProblem.INVALID_HOST)
        assertProblem("rtmps://live.example.test:0/app/key", StreamEndpointProblem.INVALID_PORT)
        assertProblem("rtmps://live.example.test:65536/app/key", StreamEndpointProblem.INVALID_PORT)
        assertProblem(
            "rtmps://live.example.test/app/${"x".repeat(MAX_STREAM_ENDPOINT_LENGTH)}",
            StreamEndpointProblem.TOO_LONG,
        )
    }

    @Test
    fun `results and errors never retain or render endpoint credentials`() {
        val marker = "credential-marker-123"
        val valid = validateStreamEndpoint(
            "srt://studio.example.test:1234/live?passphrase=$marker",
        )
        val invalid = validateStreamEndpoint(
            "srt://studio.example.test:1234/live?passphrase=short-$marker&pbkeylen=512",
        )

        assertFalse(valid.toString().contains(marker))
        assertFalse(invalid.toString().contains(marker))
        assertTrue(valid is StreamEndpointValidation.Valid)
        assertTrue(invalid is StreamEndpointValidation.Invalid)
    }

    private fun assertValid(endpoint: String, transport: SecureStreamTransport) {
        assertEquals(
            StreamEndpointValidation.Valid(transport),
            validateStreamEndpoint(endpoint),
        )
    }

    private fun assertProblem(endpoint: String, problem: StreamEndpointProblem) {
        assertEquals(
            StreamEndpointValidation.Invalid(problem),
            validateStreamEndpoint(endpoint),
        )
    }
}
