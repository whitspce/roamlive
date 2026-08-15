package dev.whitespc.roam.obs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsEndpointTest {

    @Test
    fun `valid host and IPv4 build canonical websocket URLs`() {
        assertEquals(
            ObsEndpointResult.Valid("ws://obs-box.local:4455/"),
            buildObsEndpoint(" obs-box.local ", 4455),
        )
        assertEquals(
            ObsEndpointResult.Valid("ws://192.168.1.42:4455/"),
            buildObsEndpoint("192.168.1.42", 4455),
        )
    }

    @Test
    fun `raw and bracketed IPv6 are canonicalised`() {
        val expected = ObsEndpointResult.Valid("ws://[2001:db8::1]:4455/")
        assertEquals(expected, buildObsEndpoint("2001:db8::1", 4455))
        assertEquals(expected, buildObsEndpoint("[2001:db8::1]", 4455))
    }

    @Test
    fun `invalid ports schemes and paths are rejected`() {
        listOf(
            buildObsEndpoint("obs.local", 0),
            buildObsEndpoint("obs.local", 65536),
            buildObsEndpoint("ws://obs.local", 4455),
            buildObsEndpoint("obs.local/control", 4455),
            buildObsEndpoint("[2001:db8::1", 4455),
        ).forEach { assertTrue(it is ObsEndpointResult.Invalid) }
    }

    @Test
    fun `blank OBS password is rejected before connecting`() {
        assertEquals(
            ObsEndpointResult.Invalid("OBS password can't be empty"),
            validateObsConnection("obs.local", 4455, "  "),
        )
        assertEquals(
            ObsEndpointResult.Valid("ws://obs.local:4455/"),
            validateObsConnection("obs.local", 4455, "strong generated password"),
        )
    }

    @Test
    fun `OBS server must offer authentication`() {
        assertEquals(
            "Enable authentication in OBS WebSocket settings",
            obsServerAuthenticationError(authenticationOffered = false),
        )
        assertEquals(null, obsServerAuthenticationError(authenticationOffered = true))
    }
}
