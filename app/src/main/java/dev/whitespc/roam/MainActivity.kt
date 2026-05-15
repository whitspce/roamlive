package dev.whitespc.roam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.whitespc.roam.ui.screens.StreamScreen
import dev.whitespc.roam.ui.theme.RoamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoamTheme {
                StreamScreen()
            }
        }
    }
}
