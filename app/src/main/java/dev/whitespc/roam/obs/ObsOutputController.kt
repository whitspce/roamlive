package dev.whitespc.roam.obs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises OBS StartStream/StopStream and tracks whether Roam started the
 * remote output. State-derived intent is recorded before a coroutine runs, so
 * a quick stop always wins over an older queued start.
 */
internal class ObsOutputController(
    private val scope: CoroutineScope,
    private val isConnected: () -> Boolean,
    private val readRemoteActive: suspend () -> Boolean?,
    private val startRemote: suspend () -> Boolean,
    private val stopRemote: suspend () -> Boolean,
) {
    private val commandMutex = Mutex()

    @Volatile
    private var desiredByRoam = false

    /** Accessed only under [commandMutex]. Kept across transient disconnects. */
    private var startedByRoam = false

    fun setRoamDesired(active: Boolean) {
        desiredByRoam = active
        scheduleReconcile()
    }

    fun onConnectionReady() {
        scheduleReconcile()
    }

    fun onRemoteStateChanged() {
        scheduleReconcile()
    }

    private fun scheduleReconcile() {
        scope.launch { reconcile() }
    }

    internal suspend fun reconcile(): Boolean = commandMutex.withLock {
        // Usually one pass. A second pass is required when Stop arrives while
        // StartStream is in flight; cap protects against a broken callback loop.
        repeat(4) {
            val wantedAtStart = desiredByRoam
            if (!wantedAtStart && !startedByRoam) return@withLock true
            if (!isConnected()) return@withLock false

            if (wantedAtStart) {
                val remoteActive = readRemoteActive() ?: return@withLock false
                if (!remoteActive) {
                    // Claim ownership before sending. A lost response is
                    // uncertain, so later Stop must still be allowed to clean up.
                    startedByRoam = true
                    val accepted = startRemote()
                    if (!accepted) {
                        val activeAfter = readRemoteActive()
                        if (activeAfter == false) {
                            startedByRoam = false
                            if (wantedAtStart == desiredByRoam) return@withLock false
                        } else if (activeAfter == null) {
                            // The command may have reached OBS before the
                            // connection failed. Keep ownership so a later
                            // terminal intent can stop it after reconnect.
                            return@withLock false
                        }
                    }
                }
            } else {
                // Do not trust the cached/event state here. StartStream may have
                // been accepted while its StreamStateChanged event is still queued.
                val accepted = stopRemote()
                if (accepted) {
                    startedByRoam = false
                } else {
                    val activeAfter = readRemoteActive()
                    if (activeAfter == false) {
                        startedByRoam = false
                    } else {
                        return@withLock false
                    }
                }
            }

            if (wantedAtStart == desiredByRoam) return@withLock true
        }
        false
    }

    /** Explicit output controls are independent of lifecycle sync. */
    suspend fun setManual(active: Boolean): Boolean {
        desiredByRoam = false
        return commandMutex.withLock {
            if (!isConnected()) return@withLock false
            val remoteActive = readRemoteActive() ?: return@withLock false
            if (remoteActive == active) {
                startedByRoam = false
                return@withLock true
            }
            val accepted = if (active) startRemote() else stopRemote()
            if (accepted) startedByRoam = false
            accepted
        }
    }
}
