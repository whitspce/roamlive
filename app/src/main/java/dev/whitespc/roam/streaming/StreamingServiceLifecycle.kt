package dev.whitespc.roam.streaming

import dev.whitespc.roam.streaming.overlay.OverlaySource
import dev.whitespc.roam.streaming.overlay.OverlayTokens
import dev.whitespc.roam.streaming.overlay.Scene

/**
 * Generation gate for start requests posted to Android's service queue.
 * Only the newest request may start the engine, and Stop invalidates a start
 * command that has not reached [StreamingService.onStartCommand] yet.
 */
internal class PendingSessionStartGate {
    private var nextToken = 0L
    private var pendingToken: Long? = null

    @Synchronized
    fun request(): Long {
        val token = ++nextToken
        pendingToken = token
        return token
    }

    @Synchronized
    fun consume(token: Long): Boolean {
        if (pendingToken != token) return false
        pendingToken = null
        return true
    }

    @Synchronized
    fun cancel() {
        pendingToken = null
    }

    @Synchronized
    fun hasPending(): Boolean = pendingToken != null
}

internal fun StreamState.requiresForegroundService(): Boolean =
    this === StreamState.Connecting ||
        this is StreamState.Live ||
        this is StreamState.Reconnecting

internal fun Scene.hasVisibleGpsOverlay(): Boolean = items.any { item ->
    item.visible &&
        (item.source as? OverlaySource.Text)?.text?.let(OverlayTokens::hasGpsToken) == true
}
