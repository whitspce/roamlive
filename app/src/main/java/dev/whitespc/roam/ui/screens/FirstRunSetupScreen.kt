package dev.whitespc.roam.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.streaming.DirectPlatform
import dev.whitespc.roam.streaming.HomeStudioDestination
import dev.whitespc.roam.streaming.SetupDestinationResult
import dev.whitespc.roam.streaming.buildDirectDestination
import dev.whitespc.roam.streaming.buildHomeStudioDestination
import dev.whitespc.roam.ui.screens.settings.HelpText
import dev.whitespc.roam.ui.screens.settings.LabeledField

private enum class SetupPath { Direct, HomeStudio }

@Composable
internal fun FirstRunSetupScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var path by rememberSaveable { mutableStateOf(SetupPath.Direct) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth(),
        ) {
            Text(
                text = "SET UP ROAM",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.primary)
            when (path) {
                SetupPath.Direct -> DirectSetup(
                    onComplete = onComplete,
                    onOpenHomeStudio = { path = SetupPath.HomeStudio },
                )
                SetupPath.HomeStudio -> HomeStudioSetup(
                    onComplete = onComplete,
                    onBackToDirect = { path = SetupPath.Direct },
                )
            }
        }
    }
}

@Composable
private fun DirectSetup(
    onComplete: () -> Unit,
    onOpenHomeStudio: () -> Unit,
) {
    val context = LocalContext.current
    var platform by rememberSaveable { mutableStateOf(DirectPlatform.YOUTUBE) }
    var streamKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Text(
        text = "Go live from this phone",
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
    HelpText("Choose a platform, paste its stream key, then enable your camera and microphone.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DirectPlatform.entries.forEach { option ->
            FilterChip(
                selected = platform == option,
                onClick = {
                    platform = option
                    streamKey = ""
                    error = null
                },
                label = {
                    Text(
                        when (option) {
                            DirectPlatform.YOUTUBE -> "YouTube"
                            DirectPlatform.KICK -> "Kick"
                            DirectPlatform.TWITCH -> "Twitch"
                        },
                    )
                },
            )
        }
    }

    if (platform == DirectPlatform.TWITCH) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(14.dp),
            ) {
                Text(
                    text = "Twitch does not publish a secure Direct endpoint. " +
                        "Use Home Studio so Roam never sends your stream key over plaintext RTMP.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Button(onClick = onOpenHomeStudio) {
                    Text("Set up Home Studio")
                }
            }
        }
    } else {
        LabeledField(
            label = "Stream key",
            value = streamKey,
            onValueChange = {
                streamKey = it
                error = null
            },
            placeholder = "Paste your private stream key",
            secret = true,
            isError = error != null,
            supportingText = error,
        )
        TextButton(
            onClick = {
                openExternalUrl(
                    context,
                    when (platform) {
                        DirectPlatform.YOUTUBE -> "https://studio.youtube.com/"
                        DirectPlatform.KICK -> "https://kick.com/dashboard"
                        DirectPlatform.TWITCH -> return@TextButton
                    },
                )
            },
        ) {
            Text(
                if (platform == DirectPlatform.YOUTUBE) {
                    "Open YouTube Studio"
                } else {
                    "Open Kick dashboard"
                },
            )
        }
        Button(
            onClick = {
                when (val result = buildDirectDestination(platform, streamKey)) {
                    is SetupDestinationResult.Failure -> error = result.problem.userMessage
                    is SetupDestinationResult.Success -> {
                        if (Prefs.setStreamUrl(context, result.value.phoneEndpoint)) {
                            streamKey = ""
                            onComplete()
                        } else {
                            error = "Could not save securely. Try again."
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save Direct setup")
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    Text(
        text = "Ready for OBS at home?",
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
    OutlinedButton(
        onClick = onOpenHomeStudio,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Set up Home Studio")
    }
}

@Composable
private fun HomeStudioSetup(
    onComplete: () -> Unit,
    onBackToDirect: () -> Unit,
) {
    val context = LocalContext.current
    var tailscaleIpv4 by remember { mutableStateOf("") }
    var obsPassword by remember { mutableStateOf("") }
    var generated by remember { mutableStateOf<HomeStudioDestination?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    TextButton(onClick = onBackToDirect) {
        Text("Back to Direct")
    }
    Text(
        text = "Home Studio",
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
    HelpText(
        "Send the phone feed to OBS over Tailscale, then control OBS from Roam.",
    )
    LabeledField(
        label = "Home PC Tailscale IPv4",
        value = tailscaleIpv4,
        onValueChange = {
            tailscaleIpv4 = it
            generated = null
            error = null
        },
        placeholder = "100.100.20.30",
        isError = error != null && tailscaleIpv4.isBlank(),
    )
    LabeledField(
        label = "OBS WebSocket password",
        value = obsPassword,
        onValueChange = {
            obsPassword = it
            generated = null
            error = null
        },
        placeholder = "From OBS WebSocket Server Settings",
        secret = true,
        isError = error != null && obsPassword.isBlank(),
    )
    TextButton(
        onClick = {
            openExternalUrl(context, "https://roamlive.app/guides/stream-to-obs/")
        },
    ) {
        Text("Open the OBS setup guide")
    }

    if (generated == null) {
        Button(
            onClick = {
                if (obsPassword.isBlank()) {
                    error = "Enter the OBS WebSocket password."
                    return@Button
                }
                when (val result = buildHomeStudioDestination(tailscaleIpv4)) {
                    is SetupDestinationResult.Failure -> error = result.problem.userMessage
                    is SetupDestinationResult.Success -> {
                        generated = result.value
                        error = null
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Generate OBS setup")
        }
    } else {
        val setup = generated ?: return
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(14.dp),
            ) {
                Text(
                    text = "OBS Media Source",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Input format: ${setup.inputFormat}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                SelectionContainer {
                    Text(
                        text = setup.obsMediaSourceUrl,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                    )
                }
                OutlinedButton(
                    onClick = {
                        copySensitiveText(context, setup.obsMediaSourceUrl)
                        Toast.makeText(context, "OBS input copied", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Copy OBS input")
                }
            }
        }
        HelpText("Add this as an OBS Media Source. Set Input Format to mpegts, then confirm below.")
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        }
        Button(
            onClick = {
                if (!Prefs.setStreamUrl(context, setup.phoneEndpoint)) {
                    error = "Could not save securely. Try again."
                    return@Button
                }
                if (!Prefs.setObsPassword(context, obsPassword)) {
                    Prefs.setStreamUrl(context, "")
                    error = "Could not save securely. Try again."
                    return@Button
                }
                Prefs.setObsHost(context, tailscaleIpv4.trim())
                if (!Prefs.setObsPort(context, 4455)) {
                    Prefs.setStreamUrl(context, "")
                    Prefs.setObsPassword(context, "")
                    error = "Could not save securely. Try again."
                    return@Button
                }
                Prefs.setObsSyncStreaming(context, false)
                obsPassword = ""
                generated = null
                onComplete()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("I added this to OBS")
        }
    }

    error?.takeIf { generated == null }?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}

private fun openExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    runCatching { context.startActivity(intent) }
}

private fun copySensitiveText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("OBS Media Source input", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}
