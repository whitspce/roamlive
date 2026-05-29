package dev.whitespc.roam.streaming

import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun StreamingView(
    engine: StreamingEngine,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val openGlView = remember {
        OpenGlView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    var focusPosition by remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(engine, openGlView) {
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                engine.attachPreview(openGlView, context)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                engine.detachPreview()
            }
        }
        openGlView.holder.addCallback(callback)
        onDispose {
            openGlView.holder.removeCallback(callback)
            // Engine lifecycle is owned by StreamScreen now (it outlives this view so
            // the stream survives navigating to Settings/Overlays). Just stop preview;
            // do NOT release the engine here.
            engine.detachPreview()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { openGlView },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val now = SystemClock.uptimeMillis()
                        val event = MotionEvent.obtain(
                            now, now, MotionEvent.ACTION_DOWN,
                            offset.x, offset.y, 0,
                        )
                        engine.tapToFocus(event)
                        event.recycle()
                        focusPosition = offset
                    }
                },
        )
        focusPosition?.let { pos ->
            FocusRing(pos) { focusPosition = null }
        }
    }
}

@Composable
private fun FocusRing(position: Offset, onComplete: () -> Unit) {
    val scale = remember { Animatable(1.6f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(position) {
        alpha.snapTo(0f)
        scale.snapTo(1.6f)
        launch { alpha.animateTo(1f, tween(120)) }
        launch { scale.animateTo(1f, tween(220)) }
        delay(900)
        alpha.animateTo(0f, tween(280))
        onComplete()
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = 32.dp.toPx() * scale.value
        val stroke = 2.dp.toPx()
        drawCircle(
            color = Color.White.copy(alpha = alpha.value),
            radius = radius,
            center = position,
            style = Stroke(width = stroke),
        )
        drawCircle(
            color = Color.White.copy(alpha = alpha.value * 0.35f),
            radius = 4.dp.toPx() * scale.value,
            center = position,
        )
    }
}
