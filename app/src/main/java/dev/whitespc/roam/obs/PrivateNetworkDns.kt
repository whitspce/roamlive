package dev.whitespc.roam.obs

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

internal const val OBS_PRIVATE_NETWORK_ERROR = "OBS must be on a local or private network"

internal class ObsPrivateNetworkRequiredException :
    UnknownHostException(OBS_PRIVATE_NETWORK_ERROR)

/** Restricts OBS WebSocket connections to local and private-network routes. */
internal class PrivateNetworkDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = try {
            delegate.lookup(hostname)
        } catch (_: UnknownHostException) {
            throw UnknownHostException(RESOLUTION_ERROR)
        }
        return resolved.filter(::isPrivateObsAddress).ifEmpty {
            throw ObsPrivateNetworkRequiredException()
        }
    }

    private companion object {
        const val RESOLUTION_ERROR = "OBS address could not be resolved"
    }
}

internal fun isPrivateObsAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress) return true

    val bytes = address.address
    return when (address) {
        is Inet4Address -> {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            first == 10 ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) ||
                (first == 169 && second == 254) ||
                (first == 100 && second in 64..127)
        }

        is Inet6Address -> {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            (first and 0xfe) == 0xfc ||
                (first == 0xfe && (second and 0xc0) == 0x80)
        }

        else -> false
    }
}
