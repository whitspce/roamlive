package dev.whitespc.roam.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.streaming.MAX_STREAM_ENDPOINT_LENGTH
import dev.whitespc.roam.streaming.StreamEndpointValidation
import dev.whitespc.roam.streaming.validateStreamEndpoint
import dev.whitespc.roam.ui.theme.RoamLive

/** Where the stream goes. The URL format documentation lives behind the "?"
 *  popup instead of filling the pane; the dashboard shortcuts sit at the
 *  bottom because title and category are a platform-side job. */
@Composable
internal fun StreamPane(isLive: Boolean) {
    val context = LocalContext.current
    val initialStreamUrl = remember { Prefs.streamUrl(context) }
    var streamUrl by remember { mutableStateOf(initialStreamUrl) }
    var helpOpen by remember { mutableStateOf(false) }
    var destsVersion by remember { mutableIntStateOf(0) }
    var saveDialogOpen by remember { mutableStateOf(false) }
    var saveDialogName by remember { mutableStateOf("") }
    var streamError by remember {
        val validation = validateStreamEndpoint(initialStreamUrl)
        mutableStateOf(
            if (initialStreamUrl.isNotEmpty() &&
                validation is StreamEndpointValidation.Invalid
            ) validation.problem.userMessage else null,
        )
    }
    var destinationSaveFailed by remember { mutableStateOf(false) }

    PaneScaffold(title = "Stream", locked = isLive) {
        UrlField(
            streamUrl = streamUrl,
            onStreamUrlChange = {
                streamUrl = it.take(MAX_STREAM_ENDPOINT_LENGTH + 1)
                val validation = validateStreamEndpoint(it)
                streamError = when {
                    it.isEmpty() -> {
                        Prefs.setStreamUrl(context, "")
                        null
                    }
                    validation is StreamEndpointValidation.Invalid -> {
                        // Clear the old active credential so a later Go Live
                        // tap cannot silently use it after an invalid edit.
                        Prefs.setStreamUrl(context, it)
                        validation.problem.userMessage
                    }
                    !Prefs.setStreamUrl(context, it) ->
                        "Could not save securely. Try again."
                    else -> null
                }
            },
            enabled = !isLive,
            onHelp = { helpOpen = true },
            saveError = streamError,
        )
        if (isLive) {
            LiveLockNote("Stop the stream to change where it goes.")
        }
        // Save lives right under the URL field, where you look after typing one.
        ActionRow(
            icon = Icons.Filled.BookmarkAdd,
            label = "Save current URL",
            description = "Keep it as a saved destination for quick switching.",
            onClick = {
                if (streamUrl.isNotBlank() && streamError == null) {
                    saveDialogName = guessDestinationName(streamUrl)
                    destinationSaveFailed = false
                    saveDialogOpen = true
                }
            },
        )
        Spacer(modifier = Modifier.height(4.dp))
        SubHeading("Saved destinations")
        val dests = remember(destsVersion) {
            List(Prefs.DEST_SLOTS) { slot ->
                Prefs.destinationName(context, slot) to
                    Prefs.destinationUrl(context, slot)
            }
        }
        if (dests.all { it.second == null }) {
            HelpText(
                "Save commonly used Stream URLs for quick switching or as " +
                    "emergency backups.",
            )
        } else {
            // Only filled slots, each a tappable row that switches to it.
            dests.forEachIndexed { slot, (name, url) ->
                if (url == null) return@forEachIndexed
                val isActive = url == streamUrl
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isLive && !isActive) {
                            if (Prefs.setStreamUrl(context, url)) {
                                streamUrl = url
                                streamError = null
                            } else {
                                streamError = "Could not switch destination securely."
                            }
                        }
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name ?: "Saved",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            text = if (isActive) "In use" else "Tap to switch",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    IconButton(onClick = {
                        Prefs.clearDestination(context, slot)
                        destsVersion++
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove ${name ?: "destination"}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        SubHeading("Stream title")
        LinkRow(
            icon = Icons.Filled.Edit,
            label = "Twitch dashboard",
            description = "Edit your Twitch title, category, and tags.",
            url = "https://dashboard.twitch.tv",
        )
        LinkRow(
            icon = Icons.Filled.Edit,
            label = "Kick dashboard",
            description = "Edit your Kick title and category.",
            url = "https://kick.com/dashboard",
        )
    }

    if (saveDialogOpen) {
        val slots = remember(destsVersion, saveDialogOpen) {
            List(Prefs.DEST_SLOTS) { Prefs.destinationName(context, it) }
        }
        AlertDialog(
            onDismissRequest = {
                destinationSaveFailed = false
                saveDialogOpen = false
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    destinationSaveFailed = false
                    saveDialogOpen = false
                }) {
                    Text(text = "Cancel")
                }
            },
            title = { Text("Save destination") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = saveDialogName,
                        onValueChange = { saveDialogName = it.take(65) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Save to which slot?",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    if (destinationSaveFailed) {
                        Text(
                            text = "Could not save. Use a short name and check the URL.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                    slots.forEachIndexed { slot, existingName ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val saved = Prefs.setDestination(
                                        context,
                                        slot,
                                        saveDialogName.trim()
                                            .ifBlank { guessDestinationName(streamUrl) },
                                        streamUrl,
                                    )
                                    destinationSaveFailed = !saved
                                    if (saved) {
                                        destsVersion++
                                        saveDialogOpen = false
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                        ) {
                            Text(
                                text = "Slot ${slot + 1}",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = existingName?.let { "replaces $it" } ?: "empty",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            },
        )
    }

    if (helpOpen) {
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            confirmButton = {
                TextButton(onClick = { helpOpen = false }) {
                    Text(text = "Got it", color = RoamLive)
                }
            },
            title = { Text("Stream URL") },
            text = {
                Text(
                    text = "Secure destinations use RTMPS or encrypted SRT.\n\n" +
                        "Direct: combine the platform's RTMPS server URL and " +
                        "stream key.\n" +
                        "rtmps://server.example/app/stream-key\n\n" +
                        "Home Studio: use the private-network SRT address from " +
                        "the OBS setup guide. Include a generated passphrase.\n" +
                        "srt://100.64.0.10:1234/live?passphrase=" +
                        "YOUR_RANDOM_PASSPHRASE&pbkeylen=256&latency=2000\n" +
                        "Replace the uppercase placeholder.\n\n" +
                        "Plain RTMP and SRT without a passphrase are blocked.",
                    fontSize = 13.sp,
                )
            },
        )
    }
}

/** A friendly default label for a destination URL: the platform if it's
 *  recognisable, otherwise the host. */
private fun guessDestinationName(url: String): String {
    val lower = url.lowercase()
    return when {
        "twitch" in lower -> "Twitch"
        "kick.com" in lower || "kick:" in lower -> "Kick"
        "youtube" in lower || "youtu.be" in lower -> "YouTube"
        lower.startsWith("srt://") -> "OBS / SRT"
        else -> url.substringAfter("://").substringBefore("/")
            .substringBefore(":").ifBlank { "Destination" }
    }
}

@Composable
private fun UrlField(
    streamUrl: String,
    onStreamUrlChange: (String) -> Unit,
    enabled: Boolean,
    onHelp: () -> Unit,
    saveError: String?,
) {
    var urlVisible by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldLabel("Stream URL")
            Spacer(modifier = Modifier.width(2.dp))
            IconButton(onClick = onHelp, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "What goes in the stream URL",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        OutlinedTextField(
            value = streamUrl,
            onValueChange = onStreamUrlChange,
            enabled = enabled,
            isError = saveError != null,
            placeholder = {
                Text(
                    text = "rtmps://server/app/stream-key",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            supportingText = saveError?.let { message ->
                {
                    Text(message)
                }
            },
            visualTransformation = if (urlVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { urlVisible = !urlVisible }) {
                    Icon(
                        imageVector = if (urlVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (urlVisible) {
                            "Hide stream URL"
                        } else {
                            "Show stream URL"
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
