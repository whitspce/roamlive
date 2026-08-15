package dev.whitespc.roam.obs

import okhttp3.HttpUrl

internal sealed interface ObsEndpointResult {
    data class Valid(val webSocketUrl: String) : ObsEndpointResult
    data class Invalid(val message: String) : ObsEndpointResult
}

/** Validates every setting needed before a socket is created. OBS control is
 *  intentionally authenticated even on a private LAN or tailnet. */
internal fun validateObsConnection(
    host: String,
    port: Int,
    password: String,
): ObsEndpointResult {
    val endpoint = buildObsEndpoint(host, port)
    if (endpoint is ObsEndpointResult.Invalid) return endpoint
    if (password.isBlank()) {
        return ObsEndpointResult.Invalid("OBS password can't be empty")
    }
    return endpoint
}

internal fun obsServerAuthenticationError(authenticationOffered: Boolean): String? =
    if (authenticationOffered) null else "Enable authentication in OBS WebSocket settings"

/**
 * Validates the host-only OBS setting and builds a WebSocket URL without
 * hand-concatenating an untrusted authority. The temporary HTTP URL lets
 * OkHttp canonicalise hostnames and IPv6 literals; Request.Builder accepts
 * the equivalent ws:// form.
 */
internal fun buildObsEndpoint(host: String, port: Int): ObsEndpointResult {
    val cleanHost = host.trim()
    if (cleanHost.isEmpty()) return ObsEndpointResult.Invalid("Host can't be empty")
    if (port !in 1..65535) {
        return ObsEndpointResult.Invalid("Port must be between 1 and 65535")
    }
    if (cleanHost.contains("://")) {
        return ObsEndpointResult.Invalid("Enter a host only, without ws://")
    }
    if (cleanHost.startsWith("[") != cleanHost.endsWith("]")) {
        return ObsEndpointResult.Invalid("Host is not valid")
    }
    if (cleanHost.any(Char::isWhitespace) ||
        cleanHost.any { it == '/' || it == '?' || it == '#' || it == '@' }
    ) {
        return ObsEndpointResult.Invalid("Host is not valid")
    }

    val hostForBuilder = cleanHost
        .removePrefix("[")
        .removeSuffix("]")
        .takeIf { it.isNotEmpty() }
        ?: return ObsEndpointResult.Invalid("Host is not valid")

    val httpUrl = runCatching {
        HttpUrl.Builder()
            .scheme("http")
            .host(hostForBuilder)
            .port(port)
            .build()
    }.getOrElse {
        return ObsEndpointResult.Invalid("Host is not valid")
    }
    return ObsEndpointResult.Valid("ws://${httpUrl.toString().removePrefix("http://")}")
}
