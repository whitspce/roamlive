package dev.whitespc.roam.streaming

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal sealed interface StreamingServiceBinding {
    data object Connecting : StreamingServiceBinding

    data class Connected(
        val binder: StreamingService.LocalBinder,
    ) : StreamingServiceBinding

    data class Failed(val message: String) : StreamingServiceBinding
}

/**
 * Bind only while the permission-gated streaming UI exists. An active session
 * is independently protected by the service's started foreground lifetime.
 */
@Composable
internal fun rememberStreamingServiceBinding(
    retryKey: Int,
): StreamingServiceBinding {
    val appContext = LocalContext.current.applicationContext
    var binding by remember(retryKey) {
        mutableStateOf<StreamingServiceBinding>(StreamingServiceBinding.Connecting)
    }
    val scope = rememberCoroutineScope()

    // A live session independently keeps the started foreground service alive.
    // Limiting the UI binding to STARTED prevents an idle/background Activity
    // from retaining a camera engine or restarting the pre-live mic meter.
    LifecycleStartEffect(appContext, retryKey) {
        var disposed = false
        binding = StreamingServiceBinding.Connecting
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (disposed) return
                val localBinder = service as? StreamingService.LocalBinder
                binding = if (localBinder != null) {
                    StreamingServiceBinding.Connected(localBinder)
                } else {
                    StreamingServiceBinding.Failed(
                        "Roam received an invalid streaming service connection.",
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (!disposed) {
                    binding = StreamingServiceBinding.Failed(
                        "The streaming service disconnected. Tap Retry to reconnect.",
                    )
                }
            }

            override fun onBindingDied(name: ComponentName) {
                if (!disposed) {
                    binding = StreamingServiceBinding.Failed(
                        "The streaming service stopped unexpectedly. Tap Retry to reconnect.",
                    )
                }
            }

            override fun onNullBinding(name: ComponentName) {
                if (!disposed) {
                    binding = StreamingServiceBinding.Failed(
                        "The streaming service could not provide camera controls.",
                    )
                }
            }
        }

        val bindingRequested = runCatching {
            appContext.bindService(
                Intent(appContext, StreamingService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bindingRequested) {
            binding = StreamingServiceBinding.Failed(
                "Could not connect to the streaming service. Tap Retry to try again.",
            )
        }
        val timeoutJob = scope.launch {
            delay(BIND_TIMEOUT_MS)
            if (!disposed && binding === StreamingServiceBinding.Connecting) {
                binding = StreamingServiceBinding.Failed(
                    "Connecting to the streaming service timed out. Tap Retry to try again.",
                )
            }
        }

        onStopOrDispose {
            disposed = true
            timeoutJob.cancel()
            binding = StreamingServiceBinding.Connecting
            // Android retains tracking resources for the ServiceConnection
            // even when bindService returns false or reports a dead binding.
            runCatching { appContext.unbindService(connection) }
        }
    }

    return binding
}

private const val BIND_TIMEOUT_MS = 10_000L
