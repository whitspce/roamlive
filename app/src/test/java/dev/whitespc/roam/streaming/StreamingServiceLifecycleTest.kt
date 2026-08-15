package dev.whitespc.roam.streaming

import dev.whitespc.roam.streaming.overlay.OverlayItem
import dev.whitespc.roam.streaming.overlay.OverlaySource
import dev.whitespc.roam.streaming.overlay.Scene
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingServiceLifecycleTest {

    @Test
    fun `only newest queued start can run`() {
        val gate = PendingSessionStartGate()
        val first = gate.request()
        val second = gate.request()

        assertFalse(gate.consume(first))
        assertTrue(gate.hasPending())
        assertTrue(gate.consume(second))
        assertFalse(gate.hasPending())
    }

    @Test
    fun `stop cancels a start command still in Android queue`() {
        val gate = PendingSessionStartGate()
        val queued = gate.request()

        gate.cancel()

        assertFalse(gate.consume(queued))
        assertFalse(gate.hasPending())
    }

    @Test
    fun `only active engine states require foreground service`() {
        assertFalse(StreamState.Idle.requiresForegroundService())
        assertFalse(StreamState.Error("failed").requiresForegroundService())
        assertTrue(StreamState.Connecting.requiresForegroundService())
        assertTrue(StreamState.Live().requiresForegroundService())
        assertTrue(StreamState.Reconnecting(1, 30).requiresForegroundService())
    }

    @Test
    fun `only visible GPS text requires location foreground type`() {
        val scene = Scene(
            id = "scene",
            name = "Scene",
            items = listOf(
                OverlayItem(
                    id = "hidden-gps",
                    source = OverlaySource.Text("{speed}"),
                    visible = false,
                ),
                OverlayItem(
                    id = "visible-clock",
                    source = OverlaySource.Text("{time}"),
                ),
            ),
        )
        val gpsScene = scene.copy(
            items = scene.items + OverlayItem(
                id = "visible-gps",
                source = OverlaySource.Text("Altitude: {altitude}"),
            ),
        )

        assertFalse(scene.hasVisibleGpsOverlay())
        assertTrue(gpsScene.hasVisibleGpsOverlay())
    }
}
