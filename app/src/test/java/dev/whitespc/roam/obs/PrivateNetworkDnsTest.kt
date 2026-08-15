package dev.whitespc.roam.obs

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class PrivateNetworkDnsTest {

    @Test
    fun `allows the supported IPv4 private ranges`() {
        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.1",
            "169.254.2.3",
            "100.64.0.1",
            "100.127.255.254",
        ).forEach { assertTrue(it, isPrivateObsAddress(address(it))) }
    }

    @Test
    fun `rejects IPv4 addresses outside the supported ranges`() {
        listOf(
            "0.0.0.0",
            "8.8.8.8",
            "100.63.255.255",
            "100.128.0.1",
            "172.15.255.255",
            "172.32.0.1",
            "192.0.2.1",
            "224.0.0.1",
        ).forEach { assertFalse(it, isPrivateObsAddress(address(it))) }
    }

    @Test
    fun `allows loopback ULA and link-local IPv6`() {
        listOf(
            "::1",
            "fc00::1",
            "fdff::1",
            "fe80::1",
            "febf::1",
        ).forEach { assertTrue(it, isPrivateObsAddress(address(it))) }
    }

    @Test
    fun `rejects global multicast and deprecated site-local IPv6`() {
        listOf(
            "2001:db8::1",
            "2001:4860:4860::8888",
            "ff02::1",
            "fec0::1",
        ).forEach { assertFalse(it, isPrivateObsAddress(address(it))) }
    }

    @Test
    fun `DNS keeps private results and removes public results`() {
        val private = address("192.168.1.20")
        val tailnet = address("100.100.10.20")
        val dns = PrivateNetworkDns(
            delegate = Dns { listOf(address("203.0.113.20"), private, tailnet) },
        )

        assertEquals(listOf(private, tailnet), dns.lookup("obs.example"))
    }

    @Test
    fun `DNS rejects a host with no private result without echoing the host`() {
        val dns = PrivateNetworkDns(
            delegate = Dns { listOf(address("203.0.113.20")) },
        )

        val error = assertThrows(UnknownHostException::class.java) {
            dns.lookup("sensitive-host.example")
        }
        assertTrue(error is ObsPrivateNetworkRequiredException)
        assertEquals(OBS_PRIVATE_NETWORK_ERROR, error.message)
        assertFalse(error.message.orEmpty().contains("sensitive-host"))
    }

    @Test
    fun `DNS replaces resolver errors with a safe message`() {
        val dns = PrivateNetworkDns(
            delegate = Dns { hostname ->
                throw UnknownHostException("failed to resolve $hostname")
            },
        )

        val error = assertThrows(UnknownHostException::class.java) {
            dns.lookup("sensitive-host.example")
        }
        assertEquals("OBS address could not be resolved", error.message)
        assertFalse(error.message.orEmpty().contains("sensitive-host"))
    }

    private fun address(value: String): InetAddress = InetAddress.getByName(value)
}
