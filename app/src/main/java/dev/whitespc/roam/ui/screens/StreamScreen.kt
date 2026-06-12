package dev.whitespc.roam.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import dev.whitespc.roam.chat.ChatManager
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.streaming.DualCameraSupport
import dev.whitespc.roam.streaming.LinkHealth
import dev.whitespc.roam.streaming.StreamState
import dev.whitespc.roam.streaming.StreamingEngine
import dev.whitespc.roam.streaming.StreamingService
import dev.whitespc.roam.streaming.StreamingView
import dev.whitespc.roam.ui.chat.ChatOverlay
import dev.whitespc.roam.ui.effects.LiveScreenEffect
import dev.whitespc.roam.ui.permissions.PermissionGate
import dev.whitespc.roam.ui.stealth.StealthOverlay
import dev.whitespc.roam.ui.system.rememberDeviceStatus
import dev.whitespc.roam.ui.theme.RoamConnecting
import dev.whitespc.roam.ui.theme.RoamLive
import dev.whitespc.roam.ui.theme.RoamMuted
import kotlinx.coroutines.delay

private enum class Screen { Main, Settings, Overlays }

@Composable
fun StreamScreen(modifier: Modifier = Modifier) {
    var screen by rememberSaveable { mutableStateOf(Screen.Main) }
    val context = LocalContext.current
    // Bumped every time a settings-layer screen closes. StreamSurface is never
    // unmounted (Settings/Overlays draw on top of it), so its remember{} prefs
    // reads would otherwise be captured once per process and go stale: chat
    // panel toggles wouldn't show until restart, and the Go Live button could
    // sit dark for minutes after the first URL save (it only re-evaluated when
    // an unrelated recomposition happened to come along). Keying the reads on
    // this revision refreshes them at the moment settings can have changed.
    var configRevision by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        PermissionGate {
            // The engine lives here, above the sub-screens, so it survives navigating
            // to Settings/Overlays. Those draw on TOP of the always-mounted streaming
            // surface rather than replacing it, so the stream and GL pipeline are never
            // torn down mid-stream. Released only when this whole screen goes away.
            val engine = remember { StreamingEngine(context) }
            val state by engine.state.collectAsState()
            val isLive = state is StreamState.Live ||
                state == StreamState.Connecting ||
                state is StreamState.Reconnecting
            DisposableEffect(engine) {
                onDispose { engine.release() }
            }

            StreamSurface(
                engine = engine,
                configRevision = configRevision,
                onOpenSettings = { screen = Screen.Settings },
            )

            when (screen) {
                Screen.Main -> Unit
                Screen.Settings -> {
                    BackHandler {
                        screen = Screen.Main
                        engine.syncConfig(context)
                        configRevision++
                    }
                    SettingsScreen(
                        isLive = isLive,
                        onApplyLiveBitrate = { engine.setBitrate(it) },
                        onApplyAutoBitrate = { engine.setAutoBitrate(it) },
                        onApplyRecording = { engine.setRecordWhileStreaming(it) },
                        onApplyStabilization = { engine.applyStabilization() },
                        onClose = {
                            screen = Screen.Main
                            engine.syncConfig(context)
                            configRevision++
                        },
                        onOpenOverlays = { screen = Screen.Overlays },
                    )
                }
                Screen.Overlays -> {
                    BackHandler {
                        screen = Screen.Settings
                        engine.applyScene(context)
                        configRevision++
                    }
                    OverlayEditorScreen(
                        onClose = {
                            screen = Screen.Settings
                            engine.applyScene(context)
                            configRevision++
                        },
                        onApplyScene = { engine.applyScene(context) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamSurface(
    engine: StreamingEngine,
    configRevision: Int,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val state by engine.state.collectAsState()
    // Keyed on configRevision so closing Settings refreshes these. See the
    // comment at the configRevision declaration for the staleness this fixes.
    val chatEnabled = remember(configRevision) { Prefs.chatEnabled(context) }
    val kickChannel = remember(configRevision) { Prefs.kickChannel(context) }
    val twitchChannel = remember(configRevision) { Prefs.twitchChannel(context) }
    val streamUrl = remember(configRevision) { Prefs.streamUrl(context) }
    val chatMessages by ChatManager.messages.collectAsState()

    val isLive = state is StreamState.Live
    var liveStartMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isLive) {
        if (isLive) {
            liveStartMs = System.currentTimeMillis()
            nowMs = liveStartMs
            while (true) {
                nowMs = System.currentTimeMillis()
                kotlinx.coroutines.delay(1000)
            }
        } else {
            liveStartMs = 0L
            nowMs = 0L
        }
    }
    val elapsedSec = if (liveStartMs > 0L) ((nowMs - liveStartMs) / 1000L).toInt() else 0

    val deviceStatus = rememberDeviceStatus()
    val isMuted by engine.isMuted.collectAsState()
    val isCameraOff by engine.isCameraOff.collectAsState()
    val isBrb by engine.isBrb.collectAsState()
    val isTorchOn by engine.isTorchOn.collectAsState()
    val isDualCamOn by engine.isDualCamOn.collectAsState()
    val dualCamSupported = remember { DualCameraSupport.isSupported(context) }
    val thermalNotice by engine.thermalNotice.collectAsState()
    val isRecording by engine.isRecording.collectAsState()
    val recordNotice by engine.recordNotice.collectAsState()
    var stealthActive by remember { mutableStateOf(false) }
    // Critical heat asks for the screen to go dark: the display is a real heat
    // source and stealth buys cooling time before the engine's last-resort stop.
    val stealthRequested by engine.stealthRequested.collectAsState()
    LaunchedEffect(stealthRequested) {
        if (stealthRequested) {
            stealthActive = true
            engine.consumeStealthRequest()
        }
    }

    LaunchedEffect(chatEnabled, kickChannel) {
        ChatManager.setKickChannel(if (chatEnabled) kickChannel.trim() else null)
    }
    LaunchedEffect(chatEnabled, twitchChannel) {
        ChatManager.setTwitchChannel(if (chatEnabled) twitchChannel.trim() else null)
    }

    val streamActive = state is StreamState.Live ||
        state == StreamState.Connecting ||
        state is StreamState.Reconnecting
    LiveScreenEffect(active = streamActive)

    // Keyed on the state's CLASS, not the instance: Live's bitrate field changes
    // every second, so instance-keying would cancel and relaunch these effects
    // once a second for the whole stream.
    val stateKind = state::class
    LaunchedEffect(stateKind) {
        if (state is StreamState.Idle || state is StreamState.Error) {
            StreamingService.stop(context)
        }
    }

    LaunchedEffect(stateKind) {
        if (state is StreamState.Error) {
            delay(8000)
            if (engine.state.value is StreamState.Error) engine.clearError()
        }
    }

    LaunchedEffect(stateKind) {
        if (state === StreamState.Connecting) {
            delay(30_000)
            if (engine.state.value === StreamState.Connecting) {
                engine.failConnectingTimeout()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        StreamingView(engine = engine, modifier = Modifier.fillMaxSize())

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusPill(state = state, elapsedSec = elapsedSec)
                    MetricPill(label = "${deviceStatus.batteryPercent}%", dotColor = batteryDotColor(deviceStatus.batteryPercent))
                    MetricPill(label = deviceStatus.thermal.label, dotColor = deviceStatus.thermal.color)
                    // Link health, live only: is the network keeping up with the
                    // encoder right now. The glanceable "it's the network" signal.
                    (state as? StreamState.Live)?.health?.let { health ->
                        MetricPill(
                            label = linkHealthLabel(health),
                            dotColor = linkHealthColor(health),
                        )
                    }
                    if (isRecording) {
                        MetricPill(label = "REC", dotColor = RoamLive)
                    }
                }
                thermalNotice?.let { notice ->
                    NoticeBanner(text = notice, onDismiss = { engine.dismissThermalNotice() })
                }
                recordNotice?.let { notice ->
                    NoticeBanner(text = notice, onDismiss = { engine.dismissRecordNotice() })
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                IconChip(
                    icon = Icons.Filled.Settings,
                    description = "Settings",
                    onClick = onOpenSettings,
                )
                IconChip(
                    icon = Icons.Filled.FlipCameraAndroid,
                    description = if (isDualCamOn) "Swap main and PiP cameras" else "Switch camera",
                    onClick = { engine.switchCamera() },
                )
                if (dualCamSupported) {
                    IconChip(
                        icon = Icons.Filled.PictureInPictureAlt,
                        description = if (isDualCamOn) "Turn dual camera off" else "Turn dual camera on",
                        onClick = { engine.toggleDualCam() },
                        accent = if (isDualCamOn) RoamLive else null,
                    )
                }
                IconChip(
                    icon = if (isTorchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                    description = if (isTorchOn) "Turn torch off" else "Turn torch on",
                    onClick = { engine.toggleTorch() },
                    accent = if (isTorchOn) RoamLive else null,
                )
                IconChip(
                    icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    description = if (isMuted) "Unmute microphone" else "Mute microphone",
                    onClick = { engine.toggleMute() },
                    accent = if (isMuted) RoamLive else null,
                )
                IconChip(
                    icon = if (isCameraOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                    description = if (isCameraOff) "Turn camera on" else "Turn camera off",
                    onClick = { engine.toggleCameraOff() },
                    accent = if (isCameraOff || isBrb) RoamLive else null,
                )
                IconChip(
                    icon = Icons.Filled.Coffee,
                    description = if (isBrb) "End break" else "Break screen",
                    onClick = { engine.toggleBrb() },
                    accent = if (isBrb) RoamLive else null,
                )
                IconChip(
                    icon = Icons.Filled.VisibilityOff,
                    description = "Stealth mode",
                    onClick = { stealthActive = true },
                    enabled = streamActive,
                )
            }

            if (chatEnabled && (kickChannel.isNotBlank() || twitchChannel.isNotBlank())) {
                ChatOverlay(
                    messages = chatMessages,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = 76.dp, bottom = 24.dp)
                        .width(270.dp)
                        .fillMaxHeight(),
                )
            }

            // streamUrl is the configRevision-keyed read from above, so this
            // re-evaluates the moment Settings closes. (This staleness was the
            // intermittent dark Go Live button: the old per-recomposition read
            // only re-ran when battery/thermal happened to tick.)
            val canGoLive = streamActive || streamUrl.isNotBlank()
            LiveButton(
                state = state,
                enabled = canGoLive,
                onGoLive = {
                    StreamingService.start(context)
                    engine.start(Prefs.streamUrl(context))
                },
                onStop = {
                    engine.stop()
                    StreamingService.stop(context)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }

        if (stealthActive) {
            StealthOverlay(
                showDot = Prefs.stealthDot(context),
                hapticEnabled = Prefs.stealthHaptic(context),
                pulseSeconds = Prefs.stealthPulseSeconds(context),
                onExit = { stealthActive = false },
            )
        }
    }
}

@Composable
private fun StatusPill(state: StreamState, elapsedSec: Int, modifier: Modifier = Modifier) {
    data class PillStyle(
        val label: String,
        val dotColor: Color,
        val backgroundColor: Color,
        val textColor: Color,
    )

    val style = when (state) {
        StreamState.Idle -> PillStyle(
            label = "OFFLINE",
            dotColor = RoamMuted,
            backgroundColor = Color.Black.copy(alpha = 0.55f),
            textColor = MaterialTheme.colorScheme.onBackground,
        )
        StreamState.Connecting -> PillStyle(
            label = "CONNECTING",
            dotColor = RoamConnecting,
            backgroundColor = Color.Black.copy(alpha = 0.55f),
            textColor = MaterialTheme.colorScheme.onBackground,
        )
        is StreamState.Live -> {
            val timeChunk = if (elapsedSec > 0) "  ${formatElapsed(elapsedSec)}" else ""
            val kbps = (state.bitrateBps / 1000L).coerceAtLeast(0L)
            val ratio = if (state.totalCount > 1) " ${state.connectedCount}/${state.totalCount}" else ""
            PillStyle(
                label = "LIVE$ratio$timeChunk  $kbps kbps",
                dotColor = Color.White,
                backgroundColor = RoamLive,
                textColor = Color.White,
            )
        }
        is StreamState.Reconnecting -> PillStyle(
            label = "RECONNECTING  attempt ${state.attempt}",
            dotColor = RoamConnecting,
            backgroundColor = Color.Black.copy(alpha = 0.55f),
            textColor = MaterialTheme.colorScheme.onBackground,
        )
        is StreamState.Error -> PillStyle(
            label = "ERROR  ${state.reason.take(60)}",
            dotColor = RoamConnecting,
            backgroundColor = Color.Black.copy(alpha = 0.55f),
            textColor = MaterialTheme.colorScheme.onBackground,
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(style.backgroundColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(style.dotColor),
        )
        Text(
            text = "  ${style.label}",
            color = style.textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun IconChip(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    enabled: Boolean = true,
) {
    val bg = accent ?: Color.Black.copy(alpha = 0.55f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = bg,
        modifier = modifier
            .size(44.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.4f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (accent != null) Color.White else MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun MetricPill(label: String, dotColor: Color, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun batteryDotColor(percent: Int): Color = when {
    percent < 0 -> Color.Gray
    percent <= 15 -> Color(0xFFFF2D2D)
    percent <= 35 -> Color(0xFFE8B43A)
    else -> Color(0xFF53FC18)
}

private fun linkHealthLabel(health: LinkHealth): String = when (health) {
    LinkHealth.Good -> "NET OK"
    LinkHealth.Weak -> "NET WEAK"
    LinkHealth.Bad -> "NET BAD"
}

private fun linkHealthColor(health: LinkHealth): Color = when (health) {
    LinkHealth.Good -> Color(0xFF53FC18)
    LinkHealth.Weak -> Color(0xFFE8B43A)
    LinkHealth.Bad -> Color(0xFFFF2D2D)
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    // No auto-dismiss: the banner stays as long as the engine reports a notice
    // (thermal or recording) — set while the condition holds, cleared when it
    // passes. A 6-second toast was trivially missable while driving. Amber, not
    // brand green, so it reads as a warning. Tap to dismiss; it returns on the
    // next change.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFE8861E).copy(alpha = 0.95f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private const val HOLD_DURATION_MS = 800

@Composable
private fun LiveButton(
    state: StreamState,
    enabled: Boolean,
    onGoLive: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val streaming = state is StreamState.Live ||
        state == StreamState.Connecting ||
        state is StreamState.Reconnecting
    val backgroundColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        streaming -> RoamLive
        else -> MaterialTheme.colorScheme.primary
    }
    val textColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        streaming -> Color.White
        else -> MaterialTheme.colorScheme.onPrimary
    }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(streaming) {
        if (streaming) progress.snapTo(0f)
    }

    Box(
        modifier = modifier
            .size(104.dp)
            .pointerInput(streaming, enabled) {
                if (!enabled) return@pointerInput
                if (streaming) {
                    detectTapGestures(onTap = { onStop() })
                } else {
                    detectTapGestures(
                        onPress = {
                            val animJob = scope.launch {
                                progress.animateTo(1f, tween(HOLD_DURATION_MS))
                                onGoLive()
                            }
                            tryAwaitRelease()
                            animJob.cancel()
                            scope.launch {
                                progress.animateTo(0f, tween(220))
                            }
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!streaming) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                val pad = stroke / 2f
                val side = size.minDimension - stroke
                drawArc(
                    color = Color.White.copy(alpha = 0.75f),
                    startAngle = -90f,
                    sweepAngle = 360f * progress.value,
                    useCenter = false,
                    topLeft = Offset(pad, pad),
                    size = Size(side, side),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(92.dp)
                .shadow(elevation = 12.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            if (streaming) {
                Text(
                    text = "STOP",
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "HOLD",
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "TO GO LIVE",
                        color = textColor.copy(alpha = 0.8f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp,
                    )
                }
            }
        }
    }
}

private fun formatElapsed(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
