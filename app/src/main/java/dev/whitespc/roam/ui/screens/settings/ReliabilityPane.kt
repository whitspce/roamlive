package dev.whitespc.roam.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.whitespc.roam.storage.Prefs

/** Everything about surviving failure: a local copy of the broadcast,
 *  how long to fight for a dropped connection, and keeping Android from
 *  killing the app mid-stream. */
@Composable
internal fun ReliabilityPane(onApplyRecording: (Boolean) -> Unit) {
    val context = LocalContext.current
    var recordWhileStreaming by remember {
        mutableStateOf(Prefs.recordWhileStreaming(context))
    }
    var maxReconnectMin by remember { mutableIntStateOf(Prefs.maxReconnectMinutes(context)) }

    PaneScaffold(title = "Reliability") {
        SubHeading("Local recording")
        ToggleRow(
            label = "Record to phone while streaming",
            description = "Saves a copy of the broadcast to Movies/Roam, and " +
                "keeps recording even while the stream is reconnecting, so a " +
                "dropout never loses the moment. About 1 GB of storage per " +
                "hour at 2500 kbps. Stops itself (never the stream) if " +
                "storage runs low.",
            checked = recordWhileStreaming,
            onCheckedChange = {
                recordWhileStreaming = it
                Prefs.setRecordWhileStreaming(context, it)
                // Live-safe: starts/stops the recording mid-stream.
                onApplyRecording(it)
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubHeading("Auto-reconnect")
        HelpText(
            "If your stream drops mid-broadcast, Roam will keep trying to " +
                "reconnect for this long before giving up.",
        )
        FieldLabel("Keep trying for")
        ChipRow(
            options = listOf(
                1 to "1 min",
                5 to "5 min",
                15 to "15 min",
                0 to "Forever",
            ),
            selected = maxReconnectMin,
            onSelect = {
                maxReconnectMin = it
                Prefs.setMaxReconnectMinutes(context, it)
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubHeading("Background survival")
        BatteryExemptionBlock()
    }
}

@Composable
private fun BatteryExemptionBlock() {
    val context = LocalContext.current
    val pm = remember {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    var batteryExempt by remember {
        mutableStateOf(
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true,
        )
    }
    // Re-check when we come back from the system dialog.
    LifecycleResumeEffect(Unit) {
        batteryExempt =
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        onPauseOrDispose { }
    }
    if (batteryExempt) {
        HelpText(
            "Battery optimisation is off for Roam, so Android won't kill " +
                "a long stream in the background.",
        )
    } else {
        ActionRow(
            icon = Icons.Filled.BatteryAlert,
            label = "Keep long streams alive",
            description = "Open Android's battery optimisation list, find Roam, " +
                "and allow it to keep working during a long stream.",
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                }
            },
        )
    }
}
