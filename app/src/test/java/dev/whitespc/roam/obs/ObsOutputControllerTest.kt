package dev.whitespc.roam.obs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObsOutputControllerTest {

    @Test
    fun `newer stop intent wins before queued start runs`() = runTest {
        val calls = mutableListOf<String>()
        var remoteActive = false
        val controller = controller(
            calls = calls,
            connected = { true },
            remoteActive = { remoteActive },
            setRemoteActive = { remoteActive = it },
        )

        controller.setRoamDesired(true)
        controller.setRoamDesired(false)
        advanceUntilIdle()

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `stop arriving during start is serialized after it`() = runTest {
        val calls = mutableListOf<String>()
        var remoteActive = false
        val startResult = CompletableDeferred<Boolean>()
        val controller = ObsOutputController(
            scope = this,
            isConnected = { true },
            readRemoteActive = { remoteActive },
            startRemote = {
                calls += "start"
                startResult.await().also { if (it) remoteActive = true }
            },
            stopRemote = {
                calls += "stop"
                remoteActive = false
                true
            },
        )

        controller.setRoamDesired(true)
        runCurrent()
        controller.setRoamDesired(false)
        startResult.complete(true)
        advanceUntilIdle()

        assertEquals(listOf("start", "stop"), calls)
    }

    @Test
    fun `pre-existing OBS output is not claimed or stopped`() = runTest {
        val calls = mutableListOf<String>()
        var remoteActive = true
        val controller = controller(
            calls = calls,
            connected = { true },
            remoteActive = { remoteActive },
            setRemoteActive = { remoteActive = it },
        )

        controller.setRoamDesired(true)
        advanceUntilIdle()
        controller.setRoamDesired(false)
        advanceUntilIdle()

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `owned stop waits for reconnect`() = runTest {
        val calls = mutableListOf<String>()
        var connected = true
        var remoteActive = false
        val controller = controller(
            calls = calls,
            connected = { connected },
            remoteActive = { remoteActive },
            setRemoteActive = { remoteActive = it },
        )

        controller.setRoamDesired(true)
        advanceUntilIdle()
        connected = false
        controller.setRoamDesired(false)
        advanceUntilIdle()
        assertEquals(listOf("start"), calls)

        connected = true
        controller.onConnectionReady()
        advanceUntilIdle()

        assertEquals(listOf("start", "stop"), calls)
    }

    @Test
    fun `uncertain start remains owned and is stopped after reconnect`() = runTest {
        val calls = mutableListOf<String>()
        var connected = true
        var status: Boolean? = false
        val controller = ObsOutputController(
            scope = this,
            isConnected = { connected },
            readRemoteActive = { status },
            startRemote = {
                calls += "start"
                connected = false
                status = null
                false
            },
            stopRemote = {
                calls += "stop"
                status = false
                true
            },
        )

        controller.setRoamDesired(true)
        advanceUntilIdle()
        controller.setRoamDesired(false)
        advanceUntilIdle()

        connected = true
        status = true
        controller.onConnectionReady()
        advanceUntilIdle()

        assertEquals(listOf("start", "stop"), calls)
    }

    @Test
    fun `manual start is not lifecycle owned`() = runTest {
        val calls = mutableListOf<String>()
        var remoteActive = false
        val controller = controller(
            calls = calls,
            connected = { true },
            remoteActive = { remoteActive },
            setRemoteActive = { remoteActive = it },
        )

        assertTrue(controller.setManual(true))
        controller.setRoamDesired(false)
        advanceUntilIdle()

        assertEquals(listOf("start"), calls)
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        calls: MutableList<String>,
        connected: () -> Boolean,
        remoteActive: () -> Boolean,
        setRemoteActive: (Boolean) -> Unit,
    ) = ObsOutputController(
        scope = this,
        isConnected = connected,
        readRemoteActive = { remoteActive() },
        startRemote = {
            calls += "start"
            setRemoteActive(true)
            true
        },
        stopRemote = {
            calls += "stop"
            setRemoteActive(false)
            true
        },
    )
}
