package dev.whitespc.roam.chat

private val KICK_CHANNEL_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
private val TWITCH_CHANNEL_PATTERN = Regex("[A-Za-z0-9_]{1,25}")

internal fun normalizeKickChannel(input: String): String? = input
    .trim()
    .removePrefix("@")
    .takeIf(KICK_CHANNEL_PATTERN::matches)
    ?.lowercase()

internal fun normalizeTwitchChannel(input: String): String? = input
    .trim()
    .removePrefix("#")
    .removePrefix("@")
    .takeIf(TWITCH_CHANNEL_PATTERN::matches)
    ?.lowercase()

internal fun normalizeYouTubeChannel(input: String): String? = input
    .trim()
    .takeIf { value ->
        value.length in 1..200 && value.none { it.isWhitespace() || it.isISOControl() }
    }
