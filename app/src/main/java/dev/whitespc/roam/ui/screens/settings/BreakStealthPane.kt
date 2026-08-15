package dev.whitespc.roam.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.streaming.overlay.OverlayImageStore
import dev.whitespc.roam.ui.theme.RoamLive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The two step-away features: the BRB screen viewers see, and stealth mode
 *  for going dark in public while the stream keeps running. */
@Composable
internal fun BreakStealthPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var brbText by remember { mutableStateOf(Prefs.brbText(context)) }
    var brbImagePath by remember { mutableStateOf(Prefs.brbImagePath(context)) }
    var stealthDot by remember { mutableStateOf(Prefs.stealthDot(context)) }
    var stealthHaptic by remember { mutableStateOf(Prefs.stealthHaptic(context)) }
    var stealthPulseSec by remember { mutableIntStateOf(Prefs.stealthPulseSeconds(context)) }

    // Picker for the optional custom BRB image. Imports into app-private storage
    // via OverlayImageStore (reused from the overlay editor), and replaces any
    // previously-set BRB image so only one is on disk at a time.
    val brbImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val previous = brbImagePath
        scope.launch {
            val newPath = withContext(Dispatchers.IO) {
                OverlayImageStore.importImage(context, uri)
            } ?: return@launch
            brbImagePath = newPath
            Prefs.setBrbImagePath(context, newPath)
            if (previous != null) {
                withContext(Dispatchers.IO) { OverlayImageStore.deleteImage(context, previous) }
            }
        }
    }

    PaneScaffold(title = "Break & stealth") {
        SubHeading("Break screen")
        LabeledField(
            label = "BRB message",
            value = brbText,
            onValueChange = {
                brbText = it
                Prefs.setBrbText(context, it)
            },
            placeholder = "BE RIGHT BACK",
        )
        HelpText(
            "Shown full-screen with audio muted when you tap the break icon. " +
                "A custom image below replaces the text.",
        )
        FieldLabel("Custom break image (optional)")
        if (brbImagePath == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        brbImagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }
                    .padding(vertical = 10.dp, horizontal = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = RoamLive,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Choose image from phone",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = RoamLive,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Custom image set",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val previous = brbImagePath
                        brbImagePath = null
                        Prefs.setBrbImagePath(context, null)
                        if (previous != null) {
                            scope.launch(Dispatchers.IO) {
                                OverlayImageStore.deleteImage(context, previous)
                            }
                        }
                    },
                ) {
                    Text(text = "Clear", color = RoamLive)
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        SubHeading("Stealth mode")
        HelpText(
            "Black screen while the stream keeps running, for discretion " +
                "in public. Long-press anywhere to exit.",
        )
        ToggleRow(
            label = "Recording dot",
            description = "A red dot in the corner so you don't forget you're live.",
            checked = stealthDot,
            onCheckedChange = {
                stealthDot = it
                Prefs.setStealthDot(context, it)
            },
        )
        ToggleRow(
            label = "Haptic pulse",
            description = "The phone buzzes periodically so you know it's still streaming.",
            checked = stealthHaptic,
            onCheckedChange = {
                stealthHaptic = it
                Prefs.setStealthHaptic(context, it)
            },
        )
        if (stealthHaptic) {
            FieldLabel("Pulse interval")
            ChipRow(
                options = listOf(30 to "30s", 60 to "60s"),
                selected = stealthPulseSec,
                onSelect = {
                    stealthPulseSec = it
                    Prefs.setStealthPulseSeconds(context, it)
                },
            )
        }
    }
}
