package dev.whitespc.roam.streaming

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.whitespc.roam.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal const val STREAM_NOTIFICATION_CHANNEL_ID = "roam_stream"

/**
 * Process-local owner of the one [StreamingEngine]. The UI binds for preview
 * and controls, while an active session also starts this as a camera/microphone
 * foreground service so Activity recreation or backgrounding cannot tear down
 * the broadcast.
 */
class StreamingService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val startGate = PendingSessionStartGate()

    private lateinit var streamingEngine: StreamingEngine
    private var pendingStart: PendingStart? = null
    private var foregroundActive = false
    private var foregroundServiceTypes = 0
    private var latestStartId = 0
    private var streamingWakeLock: PowerManager.WakeLock? = null

    private data class PendingStart(val token: Long, val url: String)

    inner class LocalBinder : Binder() {
        val engine: StreamingEngine
            get() = streamingEngine

        fun startSession(url: String) {
            serviceScope.launch { queueSessionStart(url) }
        }

        fun stopSession() {
            serviceScope.launch { stopSessionOnMain() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        streamingEngine = StreamingEngine(applicationContext)
        serviceScope.launch {
            combine(
                streamingEngine.state,
                streamingEngine.locationForegroundRequired,
            ) { state, _ -> state }
                .collect(::reconcileStartedState)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START_SESSION -> handleStartCommand(intent)
            ACTION_STOP_SESSION -> stopSessionOnMain()
            else -> {
                // START_NOT_STICKY means Android should not recreate us after a
                // kill. If it nevertheless delivers a null/stale command, the
                // stream URL only existed in process memory, so never resume it.
                cancelPendingStart()
                reconcileStartedState(streamingEngine.state.value)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        cancelPendingStart()
        if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
        }
        foregroundServiceTypes = 0
        releaseStreamingWakeLock()
        streamingEngine.release()
        super.onDestroy()
    }

    private fun queueSessionStart(url: String) {
        if (streamingEngine.state.value.requiresForegroundService()) return

        val token = startGate.request()
        pendingStart = PendingStart(token, url)
        val intent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_START_SESSION
            putExtra(EXTRA_START_TOKEN, token)
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure {
                if (pendingStart?.token == token) cancelPendingStart()
                runCatching {
                    streamingEngine.reportServiceFailure(
                        "Could not start the streaming service. Keep Roam open and try again.",
                    )
                }
            }
    }

    private fun handleStartCommand(intent: Intent) {
        if (!enterForeground()) {
            cancelPendingStart()
            runCatching {
                streamingEngine.reportServiceFailure(
                    "Could not access the camera and microphone in the background.",
                )
            }
            reconcileStartedState(streamingEngine.state.value)
            return
        }

        val token = intent.getLongExtra(EXTRA_START_TOKEN, NO_START_TOKEN)
        val request = pendingStart
        if (request != null && request.token == token && startGate.consume(token)) {
            pendingStart = null
            runCatching { streamingEngine.start(request.url) }
                .onFailure {
                    runCatching {
                        streamingEngine.reportServiceFailure(
                            "Could not start the stream.",
                        )
                    }
                }
        }
        // A stale command may have briefly promoted the service. Keep it only
        // if a newer start is queued or the engine is now active.
        reconcileStartedState(streamingEngine.state.value)
    }

    private fun stopSessionOnMain() {
        cancelPendingStart()
        runCatching { streamingEngine.stop() }
            .onFailure {
                runCatching {
                    streamingEngine.reportServiceFailure(
                        "Could not stop the stream cleanly.",
                    )
                }
            }
        reconcileStartedState(streamingEngine.state.value)
    }

    private fun cancelPendingStart() {
        startGate.cancel()
        pendingStart = null
    }

    private fun enterForeground(): Boolean = runCatching {
        val notification = buildNotification()
        val serviceTypes = desiredForegroundServiceTypes()
        startForeground(NOTIFICATION_ID, notification, serviceTypes)
        foregroundActive = true
        foregroundServiceTypes = serviceTypes
        acquireStreamingWakeLock()
    }.isSuccess

    private fun reconcileStartedState(state: StreamState) {
        if (state.requiresForegroundService() || startGate.hasPending()) {
            if (foregroundActive && state.requiresForegroundService()) {
                val desiredTypes = desiredForegroundServiceTypes()
                if (desiredTypes != foregroundServiceTypes && !enterForeground()) {
                    runCatching {
                        streamingEngine.reportServiceFailure(
                            "Could not keep the required background access active.",
                        )
                    }
                }
            }
            return
        }

        if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
        }
        foregroundServiceTypes = 0
        releaseStreamingWakeLock()
        if (latestStartId != 0 && stopSelfResult(latestStartId)) {
            latestStartId = 0
        }
    }

    private fun desiredForegroundServiceTypes(): Int {
        var types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            // Camera/microphone service types were introduced in Android 11.
            0
        }
        if (streamingEngine.locationForegroundRequired.value) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireStreamingWakeLock() {
        if (streamingWakeLock?.isHeld == true) return
        runCatching {
            val lock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?: return
            lock.setReferenceCounted(false)
            lock.acquire()
            streamingWakeLock = lock
        }
    }

    private fun releaseStreamingWakeLock() {
        streamingWakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        streamingWakeLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(STREAM_NOTIFICATION_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    STREAM_NOTIFICATION_CHANNEL_ID,
                    "Live stream",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Active when Roam is streaming"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, STREAM_NOTIFICATION_CHANNEL_ID)
        .setContentTitle("Roam Live")
        .setContentText("Streaming in progress")
        .setSmallIcon(android.R.drawable.presence_video_online)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(buildOpenIntent())
        .addAction(
            android.R.drawable.ic_media_pause,
            "Stop",
            buildStopIntent(),
        )
        .build()

    private fun buildOpenIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildStopIntent(): PendingIntent {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_STOP_SESSION
        }
        return PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 1
        const val OPEN_REQUEST_CODE = 0
        const val STOP_REQUEST_CODE = 1
        const val ACTION_START_SESSION = "dev.whitespc.roam.action.START_STREAM_SESSION"
        const val ACTION_STOP_SESSION = "dev.whitespc.roam.action.STOP_STREAM_SESSION"
        const val EXTRA_START_TOKEN = "start_token"
        const val NO_START_TOKEN = Long.MIN_VALUE
        const val WAKE_LOCK_TAG = "dev.whitespc.roam:streaming"
    }
}
