package dev.whitespc.roam.streaming

/** How well the network is carrying the live stream right now. Derived once a
 *  second from the stream client's send-cache congestion and dropped-frame
 *  counters: any strain this second is Weak, three or more continuous seconds
 *  of strain is Bad. Gives the streamer a glanceable "it's the network, slow
 *  down or find a window" signal before viewers complain. */
enum class LinkHealth { Good, Weak, Bad }

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data class Live(
        val bitrateBps: Long = 0,
        val connectedCount: Int = 1,
        val totalCount: Int = 1,
        val health: LinkHealth = LinkHealth.Good,
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
