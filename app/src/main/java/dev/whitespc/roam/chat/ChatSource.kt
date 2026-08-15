package dev.whitespc.roam.chat

import kotlinx.coroutines.flow.SharedFlow

interface ChatSource {
    val platform: ChatPlatform
    val messages: SharedFlow<ChatMessage>
    suspend fun connect()
    suspend fun disconnect()
}

/** A connection must remain open this long before resetting its failure streak.
 *  Callers otherwise use exponential backoff capped at 60 seconds, with network
 *  availability allowed to wake them early. */
internal const val HEALTHY_SESSION_MS = 30_000L

internal fun nextBackoffMs(failureStreak: Int): Long {
    if (failureStreak <= 1) return 1_000L
    val shift = (failureStreak - 1).coerceAtMost(6)
    return (1_000L shl shift).coerceAtMost(60_000L)
}
