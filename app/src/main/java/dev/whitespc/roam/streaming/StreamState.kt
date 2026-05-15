package dev.whitespc.roam.streaming

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data class Live(val bitrateBps: Long = 0) : StreamState
    data class Error(val reason: String) : StreamState
}
