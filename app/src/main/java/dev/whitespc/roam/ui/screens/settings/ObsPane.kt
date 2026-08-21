package dev.whitespc.roam.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whitespc.roam.obs.ObsClient
import dev.whitespc.roam.obs.ObsConnectionState
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.ui.theme.RoamLive

/** Optional OBS Studio pairing: remote scene control from the HUD. Lives
 *  under the MORE divider because it's the advanced end of the app. */
@Composable
internal fun ObsPane() {
    val context = LocalContext.current
    var obsHost by remember { mutableStateOf(Prefs.obsHost(context)) }
    var obsPortText by remember { mutableStateOf(Prefs.obsPort(context).toString()) }
    var obsPassword by remember { mutableStateOf(Prefs.obsPassword(context)) }
    var obsBrbScene by remember { mutableStateOf(Prefs.obsBrbScene(context)) }
    var obsSyncStreaming by remember { mutableStateOf(Prefs.obsSyncStreaming(context)) }
    var obsBrbMute by remember { mutableStateOf(Prefs.obsBrbMute(context)) }
    var passwordSaveFailed by remember { mutableStateOf(false) }
    val obsState by ObsClient.state.collectAsState()
    val obsScenes by ObsClient.scenes.collectAsState()
    val parsedObsPort = obsPortText.toIntOrNull()
    val obsPortInvalid = parsedObsPort == null || parsedObsPort !in 1..65_535

    PaneScaffold(title = "OBS") {
        HelpText(
            "Optional. Pair Roam with an OBS Studio running on your home PC " +
                "(or wherever) to remote-control its scenes from the HUD. " +
                "Streaming itself still goes through the stream URL; OBS is " +
                "the controller, not a separate destination. Enable WebSocket " +
                "in OBS under Tools > WebSocket Server Settings, then fill in " +
                "the same host, port, and password here. Keep authentication " +
                "enabled and use OBS's generated password. For access away " +
                "from home, connect both devices through Tailscale.",
        )
        LabeledField(
            label = "Host (LAN IP or hostname)",
            value = obsHost,
            onValueChange = {
                obsHost = it.trim()
                Prefs.setObsHost(context, obsHost)
            },
            placeholder = "192.168.1.42",
        )
        LabeledField(
            label = "Port",
            value = obsPortText,
            onValueChange = { text ->
                obsPortText = text.filter(Char::isDigit)
                obsPortText.toIntOrNull()?.let { Prefs.setObsPort(context, it) }
            },
            placeholder = "4455",
            keyboardType = KeyboardType.Number,
            isError = obsPortInvalid,
            supportingText = if (obsPortInvalid) "Use a port from 1 to 65535." else null,
        )
        LabeledField(
            label = "Password (required)",
            value = obsPassword,
            onValueChange = {
                obsPassword = it
                passwordSaveFailed = !Prefs.setObsPassword(context, it)
            },
            placeholder = "from Tools > WebSocket Server Settings",
            secret = true,
            isError = passwordSaveFailed,
            supportingText = if (passwordSaveFailed) {
                "Could not save securely. The new password was not stored."
            } else {
                null
            },
        )
        ObsConnectionRow(
            state = obsState,
            canConnect = !obsPortInvalid,
            onConnect = {
                ObsClient.connect(
                    host = obsHost,
                    port = parsedObsPort ?: -1,
                    password = obsPassword,
                )
            },
            onDisconnect = { ObsClient.disconnect() },
        )
        Spacer(modifier = Modifier.height(2.dp))
        ToggleRow(
            label = "Sync OBS streaming with Roam",
            description = "When Roam goes live, it starts OBS too. If Roam " +
                "started that OBS broadcast, it stops it when Roam ends. An " +
                "OBS broadcast that was already live is left alone. Turn this " +
                "off if you control OBS's broadcast yourself.",
            checked = obsSyncStreaming,
            onCheckedChange = {
                obsSyncStreaming = it
                Prefs.setObsSyncStreaming(context, it)
            },
        )
        Spacer(modifier = Modifier.height(2.dp))
        SubHeading("BRB scene")
        LabeledField(
            label = "BRB OBS scene name",
            value = obsBrbScene,
            onValueChange = {
                obsBrbScene = it
                Prefs.setObsBrbScene(context, it)
            },
            placeholder = "BRB",
        )
        HelpText(
            "When OBS is paired, the HUD BRB button switches to the scene " +
                "you type here instead of showing the phone-side BRB image. " +
                "Tap BRB again to switch back. Leave blank to keep BRB on " +
                "the phone.",
        )
        ToggleRow(
            label = "Mute mic and camera during OBS BRB",
            description = "While your BRB scene is live in OBS, Roam mutes " +
                "the mic and blacks the camera, then restores both when you " +
                "return. The feed keeps running so your comeback is instant.",
            checked = obsBrbMute,
            onCheckedChange = {
                obsBrbMute = it
                Prefs.setObsBrbMute(context, it)
            },
        )
        if (obsScenes.isNotEmpty()) {
            Text(
                text = "Choose the scene used for BRB:",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                obsScenes.forEach { name ->
                    FilterChip(
                        selected = name == obsBrbScene,
                        onClick = {
                            obsBrbScene = name
                            Prefs.setObsBrbScene(context, name)
                        },
                        label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RoamLive.copy(alpha = 0.25f),
                            selectedLabelColor = MaterialTheme.colorScheme.onBackground,
                        ),
                    )
                }
            }
        }
    }
}

/** Status row for the OBS pairing: shows the current connection state in
 *  plain English plus a single Connect / Disconnect button. Keeps the UI
 *  honest about what's happening (connecting / error message / etc.) instead
 *  of just toggling a switch that hides state. */
@Composable
private fun ObsConnectionRow(
    state: ObsConnectionState,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val (label, dotColor) = when (state) {
        ObsConnectionState.Disconnected -> "Not connected" to MaterialTheme.colorScheme.outline
        ObsConnectionState.Connecting -> "Connecting..." to Color(0xFFE8B43A)
        is ObsConnectionState.Connected ->
            "Connected (rpc v${state.rpcVersion})" to Color(0xFF53FC18)
        is ObsConnectionState.Error -> "Error: ${state.message}" to Color(0xFFFF2D2D)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        val isConnected = state is ObsConnectionState.Connected ||
            state is ObsConnectionState.Connecting
        Button(
            onClick = if (isConnected) onDisconnect else onConnect,
            enabled = isConnected || canConnect,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(text = if (isConnected) "Disconnect" else "Connect")
        }
    }
}
