package dev.whitespc.roam.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.Composable

/** Entry to the overlay editor, which is its own screen. */
@Composable
internal fun OverlaysPane(onOpenOverlays: () -> Unit) {
    PaneScaffold(title = "Overlays") {
        ActionRow(
            icon = Icons.Filled.Layers,
            label = "Overlay editor",
            description = "Add text, images, and web overlays on top of " +
                "your broadcast.",
            onClick = onOpenOverlays,
        )
    }
}
