package dev.whitespc.roam.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.storage.VideoQualityPreset

/** Three tested quality paths instead of independent encoder controls. */
@Composable
internal fun QualityPane(
    isLive: Boolean,
    onApplyLiveBitrate: (Int) -> Unit,
    onApplyAutoBitrate: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var preset by remember { mutableStateOf(Prefs.qualityPreset(context)) }
    var autoBitrate by remember { mutableStateOf(Prefs.autoBitrateEnabled(context)) }

    PaneScaffold(title = "Quality") {
        LockableFieldLabel("Quality preset", locked = isLive)
        ChipRow(
            options = VideoQualityPreset.entries.map { it to it.displayName() },
            selected = preset,
            onSelect = { selected ->
                preset = selected
                Prefs.setQualityPreset(context, selected)
                onApplyLiveBitrate(selected.bitrateKbps)
            },
            enabled = !isLive,
        )
        HelpText(
            "${preset.width} x ${preset.height}, ${preset.fps} fps, " +
                "${preset.bitrateKbps} kbps.",
        )
        Spacer(modifier = Modifier.height(2.dp))
        ToggleRow(
            label = "Auto bitrate",
            description = "Lowers bitrate when your connection weakens, then " +
                "returns toward the preset limit when the connection recovers.",
            checked = autoBitrate,
            onCheckedChange = {
                autoBitrate = it
                Prefs.setAutoBitrateEnabled(context, it)
                onApplyAutoBitrate(it)
            },
        )
        if (isLive) {
            LiveLockNote("Stop the stream to change the quality preset.")
        }
    }
}

private fun VideoQualityPreset.displayName(): String = when (this) {
    VideoQualityPreset.DATA_SAVER -> "Data saver"
    VideoQualityPreset.RECOMMENDED -> "Recommended"
    VideoQualityPreset.SHARP -> "Sharp"
}
