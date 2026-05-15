package dev.whitespc.roam.ui.stealth

import android.app.Activity
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun StealthOverlay(
    showDot: Boolean,
    hapticEnabled: Boolean,
    pulseSeconds: Int,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    val scope = rememberCoroutineScope()

    DisposableEffect(activity) {
        val window = activity?.window
        window?.let {
            it.attributes = it.attributes.apply { screenBrightness = 0f }
        }
        onDispose {
            window?.let {
                it.attributes = it.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    LaunchedEffect(hapticEnabled, pulseSeconds) {
        if (hapticEnabled) {
            while (true) {
                delay(pulseSeconds.coerceAtLeast(5) * 1000L)
                vibrator?.buzz(durationMs = 45, amplitude = 90)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val exitJob = scope.launch {
                            delay(1000)
                            vibrator?.buzz(durationMs = 30, amplitude = 140)
                            delay(600)
                            vibrator?.buzz(durationMs = 30, amplitude = 140)
                            delay(600)
                            vibrator?.buzz(durationMs = 170, amplitude = 220)
                            onExit()
                        }
                        tryAwaitRelease()
                        exitJob.cancel()
                    },
                )
            },
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(22.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF2D2D)),
            )
        }
    }
}

private fun Vibrator.buzz(durationMs: Long, amplitude: Int) {
    if (!hasVibrator()) return
    runCatching {
        vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
    }
}
