package dev.whitespc.roam.streaming

import java.security.SecureRandom
import java.util.Base64

internal const val HOME_STUDIO_INPUT_FORMAT = "mpegts"
internal const val HOME_STUDIO_SRT_PORT = 1234

private const val YOUTUBE_RTMPS_BASE = "rtmps://a.rtmps.youtube.com:443/live2"
private const val KICK_RTMPS_BASE =
    "rtmps://fa723fc1b171.global-contribute.live-video.net:443/app"
private const val HOME_STUDIO_PHONE_LATENCY_MS = 2_000
private const val HOME_STUDIO_OBS_LATENCY_MICROSECONDS = 2_000_000
private const val HOME_STUDIO_ROOT_ENCODER_KEY_LENGTH_BITS = 256
private const val HOME_STUDIO_FFMPEG_KEY_LENGTH_BYTES = 32
private const val HOME_STUDIO_RANDOM_BYTES = 24
private const val MAX_GUIDED_STREAM_KEY_CHARS = 512

private val homeStudioRandom = SecureRandom()
private val guidedStreamKeyPattern = Regex("[A-Za-z0-9._~-]+")

internal enum class DirectPlatform {
    YOUTUBE,
    KICK,
    TWITCH,
}

internal enum class GuidedDirectAvailability {
    AVAILABLE,
    HOME_STUDIO_OR_ADVANCED,
}

internal val DirectPlatform.guidedAvailability: GuidedDirectAvailability
    get() = when (this) {
        DirectPlatform.YOUTUBE,
        DirectPlatform.KICK -> GuidedDirectAvailability.AVAILABLE
        DirectPlatform.TWITCH -> GuidedDirectAvailability.HOME_STUDIO_OR_ADVANCED
    }

internal enum class SetupDestinationProblem(val userMessage: String) {
    STREAM_KEY_REQUIRED("Enter a stream key"),
    STREAM_KEY_INVALID("Stream key is not valid"),
    TWITCH_REQUIRES_HOME_STUDIO_OR_ADVANCED(
        "Twitch requires Home Studio or Advanced setup",
    ),
    TAILSCALE_IPV4_REQUIRED("Enter the Home PC Tailscale IPv4 address"),
    GENERATED_DESTINATION_INVALID("Could not create a secure stream destination"),
}

internal sealed interface SetupDestinationResult<out T> {
    class Success<out T>(val value: T) : SetupDestinationResult<T> {
        override fun toString(): String = "SetupDestinationResult.Success"
    }

    data class Failure(val problem: SetupDestinationProblem) :
        SetupDestinationResult<Nothing>
}

internal class DirectDestination internal constructor(
    val platform: DirectPlatform,
    val phoneEndpoint: String,
) {
    override fun toString(): String = "DirectDestination(platform=$platform)"
}

internal class HomeStudioDestination internal constructor(
    val phoneEndpoint: String,
    val obsMediaSourceUrl: String,
    val passphrase: String,
    val inputFormat: String = HOME_STUDIO_INPUT_FORMAT,
) {
    override fun toString(): String = "HomeStudioDestination(inputFormat=$inputFormat)"
}

/**
 * Creates a guided direct destination without accepting a caller-supplied
 * server. Twitch remains explicit but unavailable because its published
 * ingest setup does not currently provide a secure guided endpoint.
 */
internal fun buildDirectDestination(
    platform: DirectPlatform,
    streamKey: String,
): SetupDestinationResult<DirectDestination> {
    if (platform.guidedAvailability != GuidedDirectAvailability.AVAILABLE) {
        return SetupDestinationResult.Failure(
            SetupDestinationProblem.TWITCH_REQUIRES_HOME_STUDIO_OR_ADVANCED,
        )
    }

    val cleanKey = streamKey.trim()
    if (cleanKey.isEmpty()) {
        return SetupDestinationResult.Failure(SetupDestinationProblem.STREAM_KEY_REQUIRED)
    }
    if (cleanKey.length > MAX_GUIDED_STREAM_KEY_CHARS ||
        !guidedStreamKeyPattern.matches(cleanKey)
    ) {
        return SetupDestinationResult.Failure(SetupDestinationProblem.STREAM_KEY_INVALID)
    }

    val base = when (platform) {
        DirectPlatform.YOUTUBE -> YOUTUBE_RTMPS_BASE
        DirectPlatform.KICK -> KICK_RTMPS_BASE
        DirectPlatform.TWITCH -> error("unavailable platform passed availability check")
    }
    val endpoint = "$base/$cleanKey"
    if (validateStreamEndpoint(endpoint) !is StreamEndpointValidation.Valid) {
        return SetupDestinationResult.Failure(
            SetupDestinationProblem.GENERATED_DESTINATION_INVALID,
        )
    }
    return SetupDestinationResult.Success(
        DirectDestination(platform = platform, phoneEndpoint = endpoint),
    )
}

/**
 * Creates the two SRT addresses used by the guided Home Studio path. A
 * Tailscale IPv4 literal is required so this path cannot resolve to a public
 * destination or encourage router port forwarding.
 */
internal fun buildHomeStudioDestination(
    tailscaleIpv4: String,
): SetupDestinationResult<HomeStudioDestination> {
    val address = canonicalTailscaleIpv4(tailscaleIpv4)
        ?: return SetupDestinationResult.Failure(
            SetupDestinationProblem.TAILSCALE_IPV4_REQUIRED,
        )
    val passphrase = generateHomeStudioPassphrase()
    val phoneEndpoint = "srt://$address:$HOME_STUDIO_SRT_PORT/live" +
        "?passphrase=$passphrase" +
        "&pbkeylen=$HOME_STUDIO_ROOT_ENCODER_KEY_LENGTH_BITS" +
        "&latency=$HOME_STUDIO_PHONE_LATENCY_MS"
    if (validateStreamEndpoint(phoneEndpoint) !is StreamEndpointValidation.Valid) {
        return SetupDestinationResult.Failure(
            SetupDestinationProblem.GENERATED_DESTINATION_INVALID,
        )
    }
    val obsMediaSourceUrl = "srt://$address:$HOME_STUDIO_SRT_PORT" +
        "?mode=listener" +
        "&passphrase=$passphrase" +
        "&pbkeylen=$HOME_STUDIO_FFMPEG_KEY_LENGTH_BYTES" +
        "&latency=$HOME_STUDIO_OBS_LATENCY_MICROSECONDS"
    return SetupDestinationResult.Success(
        HomeStudioDestination(
            phoneEndpoint = phoneEndpoint,
            obsMediaSourceUrl = obsMediaSourceUrl,
            passphrase = passphrase,
        ),
    )
}

private fun generateHomeStudioPassphrase(): String {
    val randomBytes = ByteArray(HOME_STUDIO_RANDOM_BYTES)
    homeStudioRandom.nextBytes(randomBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
}

private fun canonicalTailscaleIpv4(input: String): String? {
    val clean = input.trim()
    val parts = clean.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { part ->
        if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
        if (part.length > 1 && part.startsWith('0')) return null
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
    if (octets[0] != 100 || octets[1] !in 64..127) return null
    return octets.joinToString(".")
}
