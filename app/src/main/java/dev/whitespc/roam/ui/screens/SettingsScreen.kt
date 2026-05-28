package dev.whitespc.roam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import dev.whitespc.roam.audio.MicDevices
import dev.whitespc.roam.chat.ChatManager
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.ui.theme.RoamLive

private data class Resolution(val width: Int, val height: Int, val label: String)

private val Resolutions = listOf(
    Resolution(854, 480, "480p"),
    Resolution(1280, 720, "720p"),
    Resolution(1920, 1080, "1080p"),
)

private val FpsOptions = listOf(30, 60)

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onOpenOverlays: () -> Unit,
) {
    val context = LocalContext.current

    var streamUrl by remember { mutableStateOf(Prefs.streamUrl(context)) }
    var resolutionIndex by remember {
        mutableIntStateOf(
            Resolutions.indexOfFirst {
                it.width == Prefs.videoWidth(context) && it.height == Prefs.videoHeight(context)
            }.takeIf { it >= 0 } ?: 1,
        )
    }
    var fps by remember { mutableIntStateOf(Prefs.videoFps(context)) }
    var bitrateText by remember {
        mutableStateOf(Prefs.videoBitrateKbps(context).toString())
    }
    var chatEnabled by remember { mutableStateOf(Prefs.chatEnabled(context)) }
    var kickChannel by remember { mutableStateOf(Prefs.kickChannel(context)) }
    var twitchChannel by remember { mutableStateOf(Prefs.twitchChannel(context)) }
    var brbText by remember { mutableStateOf(Prefs.brbText(context)) }
    var stealthDot by remember { mutableStateOf(Prefs.stealthDot(context)) }
    var stealthHaptic by remember { mutableStateOf(Prefs.stealthHaptic(context)) }
    var stealthPulseSec by remember { mutableIntStateOf(Prefs.stealthPulseSeconds(context)) }
    var maxReconnectMin by remember { mutableIntStateOf(Prefs.maxReconnectMinutes(context)) }

    LaunchedEffect(streamUrl) { Prefs.setStreamUrl(context, streamUrl) }
    LaunchedEffect(resolutionIndex) {
        val r = Resolutions[resolutionIndex]
        Prefs.setResolution(context, r.width, r.height)
    }
    LaunchedEffect(fps) { Prefs.setVideoFps(context, fps) }
    LaunchedEffect(bitrateText) {
        bitrateText.toIntOrNull()?.let { Prefs.setVideoBitrateKbps(context, it) }
    }
    LaunchedEffect(chatEnabled, kickChannel) {
        Prefs.setChatEnabled(context, chatEnabled)
        Prefs.setKickChannel(context, kickChannel)
        ChatManager.setKickChannel(if (chatEnabled) kickChannel.trim() else null)
    }
    LaunchedEffect(chatEnabled, twitchChannel) {
        Prefs.setTwitchChannel(context, twitchChannel)
        ChatManager.setTwitchChannel(if (chatEnabled) twitchChannel.trim() else null)
    }
    LaunchedEffect(brbText) {
        Prefs.setBrbText(context, brbText)
    }
    LaunchedEffect(stealthDot) { Prefs.setStealthDot(context, stealthDot) }
    LaunchedEffect(stealthHaptic) { Prefs.setStealthHaptic(context, stealthHaptic) }
    LaunchedEffect(stealthPulseSec) { Prefs.setStealthPulseSeconds(context, stealthPulseSec) }
    LaunchedEffect(maxReconnectMin) { Prefs.setMaxReconnectMinutes(context, maxReconnectMin) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        TopBar(onClose = onClose)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Section(title = "Stream destination") {
                DestinationBlock(
                    streamUrl = streamUrl,
                    onStreamUrlChange = { streamUrl = it },
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Paste your destination URL. RTMP and SRT are both " +
                        "supported.\n\n" +
                        "RTMP: your platform's server URL and stream key. " +
                        "e.g. rtmp://live.twitch.tv/app/live_xxxxxxxx\n\n" +
                        "SRT: srt://host:port/streamid (the stream id on the end " +
                        "is required).\n\n" +
                        "To stream to several platforms at once, point Roam at a " +
                        "restreaming service of your choice (such as Restream or " +
                        "Beamstream).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Section(title = "Microphone") {
                Text(
                    text = "Pick the mic Roam records from. USB and wired mics " +
                        "sound best; Bluetooth uses a lower-quality voice codec.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MicOption(
                        label = "Default (system choice)",
                        selected = micKey == null,
                        onClick = {
                            micKey = null
                            Prefs.setMicDevice(context, null, null)
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
                            },
                        )
                    }
                }
                Text(
                    text = "Selection takes effect the next time you open the " +
                        "main screen (the audio source is set up then).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Section(title = "Quality") {
                FieldLabel("Resolution")
                ChipRow(
                    options = Resolutions.mapIndexed { idx, r -> idx to r.label },
                    selected = resolutionIndex,
                    onSelect = { resolutionIndex = it },
                )
                Spacer(modifier = Modifier.height(16.dp))
                FieldLabel("Frame rate")
                ChipRow(
                    options = FpsOptions.map { it to "$it fps" },
                    selected = fps,
                    onSelect = { fps = it },
                )
                Spacer(modifier = Modifier.height(16.dp))
                LabeledField(
                    label = "Bitrate (kbps)",
                    value = bitrateText,
                    onValueChange = { bitrateText = it.filter(Char::isDigit) },
                    placeholder = "2500",
                    keyboardType = KeyboardType.Number,
                )
            }
            Section(title = "Auto-reconnect") {
                Text(
                    text = "If your stream drops mid-broadcast, Roam will keep trying " +
                        "to reconnect for this long before giving up.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FieldLabel("Keep trying for")
                ChipRow(
                    options = listOf(
                        1 to "1 min",
                        5 to "5 min",
                        15 to "15 min",
                        0 to "Forever",
                    ),
                    selected = maxReconnectMin,
                    onSelect = { maxReconnectMin = it },
                )
            }
            Section(title = "Chat panel") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        FieldLabel("Show chat panel")
                        Text(
                            text = "Visible only on this phone, never sent into your broadcast.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = chatEnabled,
                        onCheckedChange = { chatEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        ),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LabeledField(
                    label = "Kick channel name",
                    value = kickChannel,
                    onValueChange = { kickChannel = it.filter { c -> !c.isWhitespace() } },
                    placeholder = "your-kick-username",
                )
                Spacer(modifier = Modifier.height(8.dp))
                LabeledField(
                    label = "Twitch channel name",
                    value = twitchChannel,
                    onValueChange = { twitchChannel = it.filter { c -> !c.isWhitespace() } },
                    placeholder = "your-twitch-username",
                )
            }
            Section(title = "Break screen") {
                LabeledField(
                    label = "BRB message",
                    value = brbText,
                    onValueChange = { brbText = it },
                    placeholder = "BE RIGHT BACK",
                )
                Text(
                    text = "Shown full-screen with audio muted when you tap the break icon.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Section(title = "Stealth mode") {
                Text(
                    text = "Black screen while the stream keeps running, for discretion in public. Long-press anywhere to exit.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                ToggleRow(
                    label = "Recording dot",
                    description = "A red dot in the corner so you don't forget you're live.",
                    checked = stealthDot,
                    onCheckedChange = { stealthDot = it },
                )
                ToggleRow(
                    label = "Haptic pulse",
                    description = "The phone buzzes periodically so you know it's still streaming.",
                    checked = stealthHaptic,
                    onCheckedChange = { stealthHaptic = it },
                )
                if (stealthHaptic) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FieldLabel("Pulse interval")
                    ChipRow(
                        options = listOf(30 to "30s", 60 to "60s"),
                        selected = stealthPulseSec,
                        onSelect = { stealthPulseSec = it },
                    )
                }
            }
            Section(title = "Overlays") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenOverlays() }
                        .padding(vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Layers,
                        contentDescription = null,
                        tint = RoamLive,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        FieldLabel("Overlay editor")
                        Text(
                            text = "Add text and images on top of your broadcast.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Section(title = "Support") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://roamlive.app/support"),
                            )
                            runCatching { context.startActivity(intent) }
                        }
                        .padding(vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = RoamLive,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        FieldLabel("Support Roam Live")
                        Text(
                            text = "Donations help me keep building this.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            FieldLabel(label)
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            ),
        )
    }
}

@Composable
private fun TopBar(onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "Settings",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title.uppercase(),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
            )
            HorizontalDivider(
                color = RoamLive,
                thickness = 1.dp,
            )
        }
        content()
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
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

@Composable
private fun DestinationBlock(
    streamUrl: String,
    onStreamUrlChange: (String) -> Unit,
) {
    var urlVisible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("Stream URL")
        OutlinedTextField(
            value = streamUrl,
            onValueChange = onStreamUrlChange,
            placeholder = {
                Text(
                    text = "rtmp://server/app/key",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
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
            .clickable { onClick() }
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

@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}
