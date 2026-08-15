package dev.whitespc.roam.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whitespc.roam.audio.MicDevices
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.ui.theme.RoamLive

/** Microphone choice, HUD level meter, and input gain. */
@Composable
internal fun AudioPane(
    onApplyMicDevice: () -> Unit,
    onApplyAudioMeter: (Boolean) -> Unit,
    onApplyMicGain: (Float) -> Unit,
) {
    val context = LocalContext.current
    var audioMeterEnabled by remember { mutableStateOf(Prefs.audioMeterEnabled(context)) }
    var micGain by remember { mutableFloatStateOf(Prefs.micGain(context)) }

    // Enumerated on each pane entry, so plugging a mic in while Settings is
    // open only needs a hop to another section and back to show up.
    val micDevices = remember { MicDevices.list(context) }
    val initialMicKey = remember(micDevices) {
        val name = Prefs.micDeviceName(context)
        val type = Prefs.micDeviceType(context)
        if (name != null && type != null) {
            micDevices.firstOrNull {
                it.productName == name && it.type == type
            }?.let { "${it.type}|${it.productName}" }
        } else {
            null
        }
    }
    var micKey by remember { mutableStateOf(initialMicKey) }

    PaneScaffold(title = "Audio") {
        SubHeading("Microphone")
        HelpText(
            "Wired and USB mics sound best. Bluetooth headsets use a " +
                "phone-call voice codec and sound noticeably worse; treat " +
                "them as a backup.",
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MicOption(
                label = "Default (system choice)",
                selected = micKey == null,
                onClick = {
                    micKey = null
                    Prefs.setMicDevice(context, null, null)
                    onApplyMicDevice()
                },
            )
            micDevices.forEach { d ->
                val key = "${d.type}|${d.productName}"
                MicOption(
                    label = d.label,
                    selected = micKey == key,
                    onClick = {
                        micKey = key
                        Prefs.setMicDevice(context, d.productName, d.type)
                        onApplyMicDevice()
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        ToggleRow(
            label = "Show audio levels HUD icon on home screen",
            checked = audioMeterEnabled,
            onCheckedChange = {
                audioMeterEnabled = it
                Prefs.setAudioMeterEnabled(context, it)
                onApplyAudioMeter(it)
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubHeading("Input gain")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${(micGain * 100).toInt()}%",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(56.dp),
            )
            Slider(
                value = micGain,
                onValueChange = {
                    micGain = it
                    Prefs.setMicGain(context, it)
                    onApplyMicGain(it)
                },
                valueRange = 0f..2f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = RoamLive,
                    activeTrackColor = RoamLive.copy(alpha = 0.6f),
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun MicOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(
                    width = if (selected) 2.dp else 1.5.dp,
                    color = if (selected) RoamLive else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(RoamLive),
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
        )
    }
}
