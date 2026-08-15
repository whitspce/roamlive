package dev.whitespc.roam.streaming

import dev.whitespc.roam.diagnostics.RoamLog as Log

private const val TAG = "RoamAutoBitrate"

/**
 * Steers the encoder's video bitrate to track what the network can actually
 * carry. Fed once a second from the stream client's bitrate callback.
 *
 * A fixed bitrate can overfill the send queue when link capacity falls. Reducing
 * encoder output preserves continuity at lower image quality.
 *
 * The algorithm cuts quickly on congestion and recovers gradually after
 * [RECOVERY_STREAK_TICKS] clean measurements. It never drops below [FLOOR_BPS],
 * which preserves room for a minimally usable picture alongside audio.
 *
 * The ceiling is owned by the engine: min(user-configured bitrate, thermal cap).
 * This controller never raises bitrate above it, so adaptive bitrate and thermal
 * protection can't fight over the encoder.
 *
 * All methods are synchronized: ticks arrive on the stream client's thread,
 * ceiling changes arrive from the thermal listener or the settings UI.
 */
class AdaptiveBitrateController(
    /** Applies a new bitrate to the encoder (engine wires this to
     *  setVideoBitrateOnFly, guarded on isStreaming). */
    private val applyBitrate: (Int) -> Unit,
) {
    private var ceilingBps = 0
    private var targetBps = 0
    private var cleanStreak = 0

    /** Start (or restart) from a fresh connection: target the full ceiling and
     *  let congestion ticks pull us down if the link disagrees. Congestion shows
     *  up within 1-2 ticks, so the worst case for over-shooting is brief. */
    @Synchronized
    fun reset(ceiling: Int) {
        ceilingBps = ceiling.coerceAtLeast(FLOOR_BPS)
        targetBps = ceilingBps
        cleanStreak = 0
        applyBitrate(targetBps)
        Log.d(TAG, "reset: target=${targetBps / 1000} kbps")
    }

    /** Ceiling moved (thermal cap engaged/cleared, or the user edited bitrate in
     *  Settings). Clamp down immediately; climbing back up stays gradual. */
    @Synchronized
    fun setCeiling(ceiling: Int) {
        ceilingBps = ceiling.coerceAtLeast(FLOOR_BPS)
        if (targetBps > ceilingBps) {
            targetBps = ceilingBps
            applyBitrate(targetBps)
            Log.d(TAG, "ceiling clamp: target=${targetBps / 1000} kbps")
        }
    }

    /**
     * One tick from the stream client (~1/s). [measuredBps] is the outbound rate
     * the client actually achieved over the last interval; [congested] is whether
     * the client's send cache is filling (frames queueing faster than the socket
     * drains them).
     */
    @Synchronized
    fun onBitrateMeasured(measuredBps: Long, congested: Boolean) {
        if (targetBps == 0) return  // reset() hasn't run; nothing to steer yet.
        if (congested) {
            cleanStreak = 0
            // Cut below the lower of (what we asked for, what actually moved):
            // the queue only drains if we produce less than the link carries.
            val basis = minOf(targetBps.toLong(), measuredBps).toInt()
            val cut = (basis * DECREASE_FACTOR).toInt().coerceAtLeast(FLOOR_BPS)
            if (cut < targetBps) {
                targetBps = cut
                applyBitrate(targetBps)
                Log.d(TAG, "congestion: cut to ${targetBps / 1000} kbps")
            }
        } else {
            if (targetBps >= ceilingBps) return
            cleanStreak++
            if (cleanStreak >= RECOVERY_STREAK_TICKS) {
                val step = (targetBps * INCREASE_STEP_FRACTION).toInt()
                    .coerceAtLeast(MIN_INCREASE_STEP_BPS)
                targetBps = (targetBps + step).coerceAtMost(ceilingBps)
                applyBitrate(targetBps)
                Log.d(TAG, "recovery: up to ${targetBps / 1000} kbps")
            }
        }
    }

    companion object {
        // Keep tuning values together so transport testing can adjust them.
        const val FLOOR_BPS = 300_000
        private const val DECREASE_FACTOR = 0.7f
        private const val RECOVERY_STREAK_TICKS = 3
        private const val INCREASE_STEP_FRACTION = 0.10f
        private const val MIN_INCREASE_STEP_BPS = 50_000
    }
}
