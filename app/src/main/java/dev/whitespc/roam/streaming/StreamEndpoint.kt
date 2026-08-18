package dev.whitespc.roam.streaming

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

internal const val MAX_STREAM_ENDPOINT_LENGTH = 2_048

internal enum class SecureStreamTransport {
    RTMPS,
    SRT,
}

internal enum class StreamEndpointProblem(val userMessage: String) {
    EMPTY("Enter a stream destination"),
    TOO_LONG("Stream destination is too long"),
    INVALID_CHARACTERS("Stream destination contains invalid characters"),
    MALFORMED("Stream destination is not valid"),
    UNSUPPORTED_TRANSPORT("Use RTMPS or encrypted SRT"),
    USER_INFO_NOT_ALLOWED("Stream destination cannot contain user information"),
    FRAGMENT_NOT_ALLOWED("Stream destination cannot contain a fragment"),
    INVALID_HOST("Stream destination host is not valid"),
    INVALID_PORT("Stream destination port is not valid"),
    MISSING_PATH("RTMPS destination must include a path and stream key"),
    MISSING_SRT_PORT("SRT destination must include a port"),
    MISSING_SRT_STREAM_ID("SRT destination must include a path or streamid"),
    MISSING_SRT_PASSPHRASE("SRT destination must include a passphrase"),
    INVALID_SRT_PASSPHRASE("SRT passphrase must be 10 to 79 characters"),
    SRT_LISTENER_URL_NOT_ALLOWED(
        "That URL is for the OBS listener. Tap the help icon for the Roam phone URL",
    ),
    SRT_MODE_NOT_ALLOWED("Remove mode from the phone URL. Roam connects automatically"),
    UNKNOWN_SRT_PARAMETER("SRT destination contains an unsupported parameter"),
    DUPLICATE_SRT_PARAMETER("SRT destination contains a duplicate parameter"),
    INVALID_SRT_KEY_LENGTH("SRT pbkeylen must be 128, 192, or 256"),
    INVALID_SRT_LATENCY("SRT latency must be between 0 and 65535 milliseconds"),
}

internal sealed interface StreamEndpointValidation {
    data class Valid(val transport: SecureStreamTransport) : StreamEndpointValidation
    data class Invalid(val problem: StreamEndpointProblem) : StreamEndpointValidation
}

/**
 * Validates a destination without retaining, normalizing, or returning it.
 * Stream URLs contain credentials, so callers must not include [input] in logs
 * or error messages.
 */
internal fun validateStreamEndpoint(input: String): StreamEndpointValidation {
    if (input.isEmpty()) return invalid(StreamEndpointProblem.EMPTY)
    if (input.length > MAX_STREAM_ENDPOINT_LENGTH) {
        return invalid(StreamEndpointProblem.TOO_LONG)
    }
    if (input.hasForbiddenCharacters()) {
        return invalid(StreamEndpointProblem.INVALID_CHARACTERS)
    }

    val uri = runCatching { URI(input) }.getOrElse {
        return invalid(StreamEndpointProblem.MALFORMED)
    }
    if (uri.isOpaque) return invalid(StreamEndpointProblem.MALFORMED)
    if (uri.rawUserInfo != null) {
        return invalid(StreamEndpointProblem.USER_INFO_NOT_ALLOWED)
    }
    if (uri.rawFragment != null) {
        return invalid(StreamEndpointProblem.FRAGMENT_NOT_ALLOWED)
    }
    val host = uri.host
    if (host.isNullOrBlank() || host.hasForbiddenCharacters()) {
        return invalid(StreamEndpointProblem.INVALID_HOST)
    }
    if (uri.port != -1 && uri.port !in 1..65_535) {
        return invalid(StreamEndpointProblem.INVALID_PORT)
    }

    val path = decodePercentEncoded(uri.rawPath ?: "")
        ?: return invalid(StreamEndpointProblem.MALFORMED)
    if (path.hasForbiddenCharacters()) {
        return invalid(StreamEndpointProblem.INVALID_CHARACTERS)
    }

    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "rtmps", "rtmpts" -> validateSecureRtmp(uri, path)
        "srt" -> validateSrt(uri, path)
        else -> invalid(StreamEndpointProblem.UNSUPPORTED_TRANSPORT)
    }
}

private fun validateSecureRtmp(uri: URI, path: String): StreamEndpointValidation {
    val query = uri.rawQuery
    if (query != null) {
        val decodedQuery = decodePercentEncoded(query)
            ?: return invalid(StreamEndpointProblem.MALFORMED)
        if (decodedQuery.hasForbiddenCharacters()) {
            return invalid(StreamEndpointProblem.INVALID_CHARACTERS)
        }
    }
    if (!path.hasMeaningfulPath()) return invalid(StreamEndpointProblem.MISSING_PATH)
    return StreamEndpointValidation.Valid(SecureStreamTransport.RTMPS)
}

private fun validateSrt(uri: URI, path: String): StreamEndpointValidation {
    if (uri.port == -1) return invalid(StreamEndpointProblem.MISSING_SRT_PORT)

    val parameters = parseSrtParameters(uri.rawQuery)
    if (parameters is SrtParametersResult.Invalid) return invalid(parameters.problem)
    parameters as SrtParametersResult.Valid

    val passphrase = parameters.values["passphrase"]
        ?: return invalid(StreamEndpointProblem.MISSING_SRT_PASSPHRASE)
    if (passphrase.length !in 10..79) {
        return invalid(StreamEndpointProblem.INVALID_SRT_PASSPHRASE)
    }

    parameters.values["pbkeylen"]?.let { value ->
        if (value !in setOf("128", "192", "256")) {
            return invalid(StreamEndpointProblem.INVALID_SRT_KEY_LENGTH)
        }
    }
    parameters.values["latency"]?.let { value ->
        if (!value.matches(Regex("[0-9]{1,5}")) || value.toIntOrNull() !in 0..65_535) {
            return invalid(StreamEndpointProblem.INVALID_SRT_LATENCY)
        }
    }

    val streamId = parameters.values["streamid"]
    if (!path.hasMeaningfulPath() && streamId.isNullOrEmpty()) {
        return invalid(StreamEndpointProblem.MISSING_SRT_STREAM_ID)
    }
    return StreamEndpointValidation.Valid(SecureStreamTransport.SRT)
}

private sealed interface SrtParametersResult {
    data class Valid(val values: Map<String, String>) : SrtParametersResult
    data class Invalid(val problem: StreamEndpointProblem) : SrtParametersResult
}

private fun parseSrtParameters(rawQuery: String?): SrtParametersResult {
    if (rawQuery == null) return SrtParametersResult.Valid(emptyMap())

    val allowedNames = setOf("passphrase", "pbkeylen", "latency", "streamid")
    val values = mutableMapOf<String, String>()
    for (part in rawQuery.split('&')) {
        // RootEncoder 2.8 passes SRT query values through verbatim. Keep the
        // same interpretation here so an accepted passphrase or streamid is
        // exactly what the transport sends.
        val name = part.substringBefore('=')
        val value = part.substringAfter('=', missingDelimiterValue = "")
        if (name.hasForbiddenCharacters() || value.hasForbiddenCharacters()) {
            return SrtParametersResult.Invalid(StreamEndpointProblem.INVALID_CHARACTERS)
        }
        if (name == "mode" && value.equals("listener", ignoreCase = true)) {
            return SrtParametersResult.Invalid(StreamEndpointProblem.SRT_LISTENER_URL_NOT_ALLOWED)
        }
        if (name == "mode") {
            return SrtParametersResult.Invalid(StreamEndpointProblem.SRT_MODE_NOT_ALLOWED)
        }
        if (name !in allowedNames) {
            return SrtParametersResult.Invalid(StreamEndpointProblem.UNKNOWN_SRT_PARAMETER)
        }
        if (values.put(name, value) != null) {
            return SrtParametersResult.Invalid(StreamEndpointProblem.DUPLICATE_SRT_PARAMETER)
        }
    }
    return SrtParametersResult.Valid(values)
}

private fun String.hasMeaningfulPath(): Boolean = removePrefix("/").isNotEmpty()

private fun String.hasForbiddenCharacters(): Boolean = any { character ->
    character.isWhitespace() || character.isISOControl() || Character.isSpaceChar(character)
}

/** Percent-decodes an RFC 3986 component while preserving literal '+'. */
private fun decodePercentEncoded(value: String): String? {
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] != '%') {
            result.append(value[index++])
            continue
        }

        val bytes = ArrayList<Byte>()
        while (index < value.length && value[index] == '%') {
            if (index + 2 >= value.length) return null
            val high = Character.digit(value[index + 1], 16)
            val low = Character.digit(value[index + 2], 16)
            if (high < 0 || low < 0) return null
            bytes += ((high shl 4) or low).toByte()
            index += 3
        }
        val decoded = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        }.getOrNull() ?: return null
        result.append(decoded)
    }
    return result.toString()
}

private fun invalid(problem: StreamEndpointProblem): StreamEndpointValidation.Invalid =
    StreamEndpointValidation.Invalid(problem)
