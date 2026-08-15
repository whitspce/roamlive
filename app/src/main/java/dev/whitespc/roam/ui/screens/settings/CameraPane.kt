package dev.whitespc.roam.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.whitespc.roam.storage.Prefs

/** Camera behaviour. Lens switching lands here when it's built. */
@Composable
internal fun CameraPane(
    onApplyStabilization: () -> Unit,
    onApplyDualCam: (Boolean) -> Unit,
    onApplyMirrorFrontPip: () -> Unit,
) {
    val context = LocalContext.current
    var stabilizationEnabled by remember { mutableStateOf(Prefs.stabilizationEnabled(context)) }
    var dualCamEnabled by remember { mutableStateOf(Prefs.dualCamEnabled(context)) }
    var mirrorFrontPip by remember { mutableStateOf(Prefs.mirrorFrontPip(context)) }

    PaneScaffold(title = "Camera") {
        ToggleRow(
            label = "Image stabilization",
            description = "Smooths out shaky handheld footage. " +
                "Slightly crops the frame; not all phones support it.",
            checked = stabilizationEnabled,
            onCheckedChange = {
                stabilizationEnabled = it
                Prefs.setStabilizationEnabled(context, it)
                onApplyStabilization()
            },
        )
        ToggleRow(
            label = "Dual camera",
            description = "Adds a dual cam icon to the home screen for " +
                "showing front and rear cameras at the same time. Warning: " +
                "very resource intensive and heats up the phone quickly.",
            checked = dualCamEnabled,
            onCheckedChange = {
                dualCamEnabled = it
                Prefs.setDualCamEnabled(context, it)
                onApplyDualCam(it)
            },
        )
        if (dualCamEnabled) {
            ToggleRow(
                label = "Mirror front camera",
                description = "Selfie-style flip for the dual cam corner " +
                    "view. On looks natural to you; off shows viewers " +
                    "text the right way round.",
                checked = mirrorFrontPip,
                onCheckedChange = {
                    mirrorFrontPip = it
                    Prefs.setMirrorFrontPip(context, it)
                    onApplyMirrorFrontPip()
                },
            )
        }
    }
}
