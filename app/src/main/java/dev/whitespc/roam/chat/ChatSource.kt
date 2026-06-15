package dev.whitespc.roam.chat

import kotlinx.coroutines.flow.SharedFlow

interface ChatSource {
    val platform: ChatPlatform
    val messages: SharedFlow<ChatMessage>
    suspend fun connect()
    suspend fun disconnect()
}

/** Exponential backoff capped at 60 s. Shared by every chat source so a flaky
 *  endpoint doesn't hammer the radio (the v0.5.0 log captured 3624 useless
 *  Twitch retries in one day on intermittent DNS). The caller pairs this with
 *  [dev.whitespc.roam.NetworkMonitor.onAvailable] so a new network wakes the
 *  delay early — instant reconnect on a wifi↔cellular flip, polite backoff
 *  while nothing is changing. */
internal fun nextBackoffMs(failureStreak: Int): Long {
    if (failureStreak <= 1) return 1_000L
    val shift = (failureStreak - 1).coerceAtMost(6)
    return (1_000L shl shift).coerceAtMost(60_000L)
}
