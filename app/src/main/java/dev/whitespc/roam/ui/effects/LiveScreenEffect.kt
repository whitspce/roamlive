package dev.whitespc.roam.ui.effects

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
fun LiveScreenEffect(active: Boolean) {
    val activity = LocalContext.current as? Activity ?: return

    DisposableEffect(active) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val systemBars = WindowInsetsCompat.Type.systemBars()
        val statusBars = WindowInsetsCompat.Type.statusBars()
        val navBars = WindowInsetsCompat.Type.navigationBars()

        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (active) {
            // Streaming: keep the screen on and go fully immersive (status + nav).
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.hide(systemBars)
        } else {
            // Idle: status bar back (time / battery), but the nav bar stays hidden
            // — the white gesture pill should never come back.
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(statusBars)
            controller.hide(navBars)
        }

        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(statusBars)
            controller.hide(navBars)
        }
    }
}
