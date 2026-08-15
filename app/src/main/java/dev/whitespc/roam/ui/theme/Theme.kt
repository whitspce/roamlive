package dev.whitespc.roam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RoamDarkColors = darkColorScheme(
    background = RoamBlack,
    onBackground = RoamText,
    surface = RoamCharcoal,
    onSurface = RoamText,
    surfaceVariant = RoamGraphite,
    onSurfaceVariant = RoamMuted,
    outline = RoamLine,
    primary = RoamReady,
    onPrimary = RoamBlack,
    secondary = RoamLive,
    onSecondary = RoamText,
    tertiary = RoamConnecting,
    onTertiary = RoamBlack,
)

@Composable
fun RoamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = RoamDarkColors,
        content = content,
    )
}
