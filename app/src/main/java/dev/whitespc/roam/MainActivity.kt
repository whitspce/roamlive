package dev.whitespc.roam

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.whitespc.roam.ui.screens.StreamScreen
import dev.whitespc.roam.ui.theme.RoamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Explicit dark, transparent system bars. The no-arg enableEdgeToEdge()
        // applies a default near-white scrim behind the navigation bar — that
        // was the white bar showing under the GO LIVE button.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            RoamTheme {
                StreamScreen()
            }
        }
    }
}
