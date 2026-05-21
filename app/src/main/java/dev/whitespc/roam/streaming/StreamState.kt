package dev.whitespc.roam.streaming

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data class Live(
        val bitrateBps: Long = 0,
        val connectedCount: Int = 1,
        val totalCount: Int = 1,
    ) : StreamState
    /** Lost the stream after we were Live and we're trying to get back.
     *  Differentiated from Connecting so the pill can show attempt count
     *  and seconds remaining before we give up. */
    data class Reconnecting(
        val attempt: Int,
        val secondsRemaining: Int,
    ) : StreamState
    data class Error(val reason: String) : StreamState
}
