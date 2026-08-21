package dev.whitespc.roam.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import dev.whitespc.roam.chat.ChatManager
import dev.whitespc.roam.obs.ObsClient
import dev.whitespc.roam.update.UpdateChecker
import dev.whitespc.roam.obs.ObsConnectionState
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.streaming.DualCameraSupport
import dev.whitespc.roam.streaming.LinkHealth
import dev.whitespc.roam.streaming.StreamEndpointValidation
import dev.whitespc.roam.streaming.StreamState
import dev.whitespc.roam.streaming.StreamingEngine
import dev.whitespc.roam.streaming.StreamingServiceBinding
import dev.whitespc.roam.streaming.StreamingView
import dev.whitespc.roam.streaming.STREAM_NOTIFICATION_CHANNEL_ID
import dev.whitespc.roam.streaming.rememberStreamingServiceBinding
import dev.whitespc.roam.streaming.validateStreamEndpoint
import dev.whitespc.roam.ui.chat.ChatOverlay
import dev.whitespc.roam.ui.effects.LiveScreenEffect
import dev.whitespc.roam.ui.permissions.PermissionGate
import dev.whitespc.roam.ui.screens.settings.SettingsScreen
import dev.whitespc.roam.ui.stealth.StealthOverlay
import dev.whitespc.roam.ui.system.rememberDeviceStatus
import dev.whitespc.roam.ui.theme.RoamConnecting
import dev.whitespc.roam.ui.theme.RoamLive
import dev.whitespc.roam.ui.theme.RoamMuted
import kotlinx.coroutines.delay

private enum class Screen { Main, Settings, Overlays }

/** Once per process, not per composition: see the auto-connect effect. */
private var obsAutoConnectAttempted = false

@Composable
fun StreamScreen(modifier: Modifier = Modifier) {
    var screen by rememberSaveable { mutableStateOf(Screen.Main) }
    val context = LocalContext.current
    // Bumped every time a settings-layer screen closes. StreamSurface is never
    // unmounted (Settings/Overlays draw on top of it), so its remember{} prefs
    // reads would otherwise be captured once per process and go stale. Keying
    // those reads on this revision refreshes them when settings change.
    var configRevision by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        PermissionGate {
            var bindAttempt by rememberSaveable { mutableIntStateOf(0) }
            when (val serviceBinding = rememberStreamingServiceBinding(bindAttempt)) {
                StreamingServiceBinding.Connecting -> {
                    StreamingServiceConnectionStatus(
                        message = "Starting camera controls…",
                    )
                }

                is StreamingServiceBinding.Failed -> {
                    StreamingServiceConnectionStatus(
                        message = serviceBinding.message,
                        onRetry = { bindAttempt++ },
                    )
                }

                is StreamingServiceBinding.Connected -> {
                    // The service, not this composition, owns the engine. An active
                    // broadcast therefore survives Activity recreation, backgrounding,
                    // and any temporary disposal of this UI.
                    val binder = serviceBinding.binder
                    val engine = binder.engine
                    val state by engine.state.collectAsState()
                    val isLive = state is StreamState.Live ||
                        state == StreamState.Connecting ||
                        state is StreamState.Reconnecting

                    StreamSurface(
                        engine = engine,
                        configRevision = configRevision,
                        onStartSession = binder::startSession,
                        onStopSession = binder::stopSession,
                        onOpenSettings = { screen = Screen.Settings },
                    )

                    when (screen) {
                        Screen.Main -> {
                            // An accidental back gesture should background the task,
                            // not hide the only on-screen controls without warning.
                            BackHandler(enabled = isLive) {
                                (context as? android.app.Activity)?.moveTaskToBack(true)
                            }
                        }

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
                                onApplyDualCam = { engine.setDualCamSettingEnabled(it) },
                                onApplyMirrorFrontPip = { engine.applyMirrorFrontPip() },
                                onApplyMicDevice = { engine.applyMicDevicePref(context) },
                                onApplyAudioMeter = { engine.setAudioMeterDesired(it) },
                                onApplyMicGain = { engine.setMicGain(it) },
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
    }
}

@Composable
private fun StreamingServiceConnectionStatus(
    message: String,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Spacer(modifier = Modifier.weight(1f))
        if (onRetry == null) CircularProgressIndicator()
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (onRetry != null) {
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

private fun canShowStreamingStopNotification(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    ) return false
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return manager.getNotificationChannel(STREAM_NOTIFICATION_CHANNEL_ID)?.importance !=
        NotificationManager.IMPORTANCE_NONE
}

private fun openStreamingNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, STREAM_NOTIFICATION_CHANNEL_ID)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
private fun StreamSurface(
    engine: StreamingEngine,
    configRevision: Int,
    onStartSession: (String) -> Unit,
    onStopSession: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val state by engine.state.collectAsState()
    // Keyed on configRevision so closing Settings refreshes these. See the
    // comment at the configRevision declaration for the staleness this fixes.
    val chatEnabled = remember(configRevision) { Prefs.chatEnabled(context) }
    val kickChannel = remember(configRevision) { Prefs.kickChannel(context) }
    val twitchChannel = remember(configRevision) { Prefs.twitchChannel(context) }
    val youtubeChannel = remember(configRevision) { Prefs.youtubeChannel(context) }
    val chatMessages by ChatManager.messages.collectAsState()

    // Android 13+ hides foreground-service notifications, including our safe
    // Stop action, until notification permission is granted. Do not begin a
    // broadcast that could outlive a swiped task without that explicit control.
    var pendingStartUrl by remember { mutableStateOf<String?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pendingUrl = pendingStartUrl
        pendingStartUrl = null
        if (granted && pendingUrl != null) {
            if (canShowStreamingStopNotification(context)) {
                onStartSession(pendingUrl)
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Turn on Roam Live notifications, then try Go Live again.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                openStreamingNotificationSettings(context)
            }
        } else if (!granted) {
            android.widget.Toast.makeText(
                context,
                "Allow notifications so Roam can show a Stop button while streaming.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    fun startWithBackgroundStopControl(url: String) {
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (notificationGranted) {
            if (canShowStreamingStopNotification(context)) {
                onStartSession(url)
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Turn on Roam Live notifications, then try Go Live again.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                openStreamingNotificationSettings(context)
            }
        } else {
            pendingStartUrl = url
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val isLive = state is StreamState.Live
    var liveStartMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(0L) }
    // Keyed on the state's CLASS (Live's bitrate field changes every second,
    // see the stateKind comment below). Reconnecting/Connecting freeze the
    // clock instead of zeroing it: the engine preserves its own liveStartMs
    // across reconnects, and the pill snapping back to 0:00 after a blip
    // disagreed with the {stream_time} overlay on a multi-hour stream.
    LaunchedEffect(state::class) {
        when {
            isLive -> {
                if (liveStartMs == 0L) {
                    // SystemClock.elapsedRealtime (monotonic, can't drift) matches
                    // what the engine uses for `{stream_time}`. System.currentTimeMillis()
                    // can be adjusted by NTP and make the two counters disagree.
                    liveStartMs = android.os.SystemClock.elapsedRealtime()
                    nowMs = liveStartMs
                }
                while (true) {
                    nowMs = android.os.SystemClock.elapsedRealtime()
                    kotlinx.coroutines.delay(1000)
                }
            }
            state is StreamState.Reconnecting || state == StreamState.Connecting -> Unit
            else -> {
                liveStartMs = 0L
                nowMs = 0L
            }
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
    val dualCamEnabled = remember(configRevision) { Prefs.dualCamEnabled(context) }
    val audioMeterEnabled = remember(configRevision) { Prefs.audioMeterEnabled(context) }
    LaunchedEffect(audioMeterEnabled) { engine.setAudioMeterDesired(audioMeterEnabled) }
    // Auto-reconnect OBS on app launch if the user has previously paired and
    // we're not already connected. Saves the trip into Settings every cold
    // start. Disconnect button still works the same - it cancels the wanted-
    // connected flag, so this re-arm only fires once per app launch.
    LaunchedEffect(Unit) {
        // Process-level flag, not composition-level: an activity recreation
        // (config change) reruns this effect, and it must not override a
        // manual Disconnect the user made earlier in the same app run.
        if (!obsAutoConnectAttempted) {
            obsAutoConnectAttempted = true
            val host = Prefs.obsHost(context)
            if (host.isNotBlank() && ObsClient.state.value is ObsConnectionState.Disconnected) {
                ObsClient.connect(
                    host = host,
                    port = Prefs.obsPort(context),
                    password = Prefs.obsPassword(context),
                )
            }
        }
    }
    val obsBrbScene = remember(configRevision) { Prefs.obsBrbScene(context) }
    val obsCurrentScene by ObsClient.currentScene.collectAsState()
    val obsLiveState by ObsClient.state.collectAsState()
    val obsConnected = obsLiveState is ObsConnectionState.Connected
    // Track which scene to return to when the user taps BRB-toggle a second
    // time after we've sent OBS to the BRB scene. Auto-updates from the
    // current-scene flow so manual scene changes via the picker stay honest.
    var lastNonBrbScene by remember { mutableStateOf<String?>(null) }
    val obsBrbActive = obsConnected &&
        obsBrbScene.isNotBlank() &&
        obsCurrentScene == obsBrbScene
    // Engine-owned latch: remains true across an OBS disconnect until a live
    // connection confirms that program has left the BRB scene.
    val obsBrbPrivacyActive by engine.obsBrbPrivacyActive.collectAsState()
    var obsBrbBannerDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(obsBrbPrivacyActive) {
        if (!obsBrbPrivacyActive) obsBrbBannerDismissed = false
    }
    LaunchedEffect(obsCurrentScene, obsBrbScene) {
        // Whenever the OBS scene changes (via our tap OR a manual change in
        // OBS / via the picker), remember it as the return target as long as
        // it isn't the BRB scene itself. That way the BRB-toggle's "switch
        // back" always lands on whatever the user was actually on.
        val current = obsCurrentScene
        if (current != null && current != obsBrbScene) {
            lastNonBrbScene = current
        }
    }
    val thermalNotice by engine.thermalNotice.collectAsState()
    val isRecording by engine.isRecording.collectAsState()
    val recordNotice by engine.recordNotice.collectAsState()
    val micNotice by engine.micNotice.collectAsState()
    // Daily sideload update check (an anonymous GET of a static version file;
    // see UpdateChecker). Runs once per process, result shown as a banner.
    var updateOffer by remember { mutableStateOf<UpdateChecker.Update?>(null) }
    LaunchedEffect(Unit) { updateOffer = UpdateChecker.maybeCheck(context) }
    val obsStartScope = rememberCoroutineScope()
    var stealthActive by remember { mutableStateOf(false) }
    var scenePickerOpen by remember { mutableStateOf(false) }
    var micPanelOpen by remember { mutableStateOf(false) }
    var destinationNeededOpen by rememberSaveable { mutableStateOf(false) }
    var obsStartConfirmationOpen by rememberSaveable { mutableStateOf(false) }
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
    LaunchedEffect(chatEnabled, youtubeChannel) {
        ChatManager.setYouTubeChannel(if (chatEnabled) youtubeChannel.trim() else null)
    }

    val streamActive = state is StreamState.Live ||
        state == StreamState.Connecting ||
        state is StreamState.Reconnecting
    LiveScreenEffect(active = streamActive)

    // Keyed on the state's CLASS, not the instance: Live's bitrate field changes
    // every second, so instance-keying would cancel and relaunch these effects
    // once a second for the whole stream.
    // The engine also drives this at start/terminal transitions so screen-off
    // cleanup does not depend on Compose. This effect covers live setting
    // changes and keeps the desired state derived, rather than click-ordered.
    val obsSyncStreaming = remember(configRevision) { Prefs.obsSyncStreaming(context) }
    LaunchedEffect(obsSyncStreaming, streamActive) {
        ObsClient.setRoamStreamingWanted(obsSyncStreaming && streamActive)
    }
    val stateKind = state::class
    LaunchedEffect(stateKind) {
        if (state is StreamState.Error) {
            delay(8000)
            if (engine.state.value is StreamState.Error) engine.clearError()
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
                    // OBS sits right after the Roam status pill so the two-hop
                    // chain reads left to right: is the phone live, is OBS
                    // live. With sync off the two legitimately differ, which is
                    // exactly why they belong side by side.
                    val obsState by ObsClient.state.collectAsState()
                    val obsStreaming by ObsClient.streaming.collectAsState()
                    val obsConfigured = remember(configRevision) {
                        Prefs.obsHost(context).isNotBlank()
                    }
                    // Dot = CONNECTION only (gray down, amber connecting, green
                    // connected, red error), matching every other pill where a
                    // red dot means trouble. Broadcast state is the LIVE word,
                    // never the dot colour. The old red-dot-means-OBS-streaming
                    // clashed with NET BAD's red and read as an error.
                    when (val s = obsState) {
                        ObsConnectionState.Disconnected ->
                            if (obsConfigured) {
                                MetricPill(
                                    label = "OBS",
                                    dotColor = MaterialTheme.colorScheme.outline,
                                )
                            }
                        ObsConnectionState.Connecting ->
                            MetricPill(label = "OBS", dotColor = Color(0xFFE8B43A))
                        is ObsConnectionState.Connected ->
                            MetricPill(
                                label = if (obsStreaming) "OBS LIVE" else "OBS",
                                // Dot stays green (connected); the whole pill
                                // goes red when OBS is broadcasting, matching
                                // the LIVE pill so the two sit side by side as
                                // one glanceable "both live" check.
                                dotColor = Color(0xFF53FC18),
                                backgroundColor = if (obsStreaming) {
                                    RoamLive
                                } else {
                                    Color.Black.copy(alpha = 0.55f)
                                },
                                onClick = { scenePickerOpen = true },
                            )
                        is ObsConnectionState.Error -> {
                            // Only alarm with red once we've given up; transient
                            // retries shouldn't strobe the pill. The client
                            // reports Error between attempts, so treat a
                            // configured OBS as "trying" (gray) unless it's a
                            // hard auth failure.
                            val authFailed = s.message.contains("auth", ignoreCase = true) ||
                                s.message.contains("password", ignoreCase = true)
                            MetricPill(
                                label = "OBS",
                                dotColor = if (authFailed) {
                                    Color(0xFFFF2D2D)
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        }
                    }
                    MetricPill(label = "${deviceStatus.batteryPercent}%", dotColor = batteryDotColor(deviceStatus.batteryPercent))
                    MetricPill(label = deviceStatus.thermal.label, dotColor = deviceStatus.thermal.color)
                    // Link health, steady for the whole session (connecting,
                    // live, reconnecting): a pill that vanishes the moment the
                    // link is in trouble read as a glitch in the field. Gray
                    // "NET" while there's no fresh sample to report.
                    if (streamActive) {
                        val health = (state as? StreamState.Live)?.health
                        MetricPill(
                            label = health?.let { linkHealthLabel(it) } ?: "NET",
                            dotColor = health?.let { linkHealthColor(it) }
                                ?: MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (isRecording) {
                        MetricPill(label = "REC", dotColor = RoamLive)
                    }
                    if (audioMeterEnabled) {
                        val meterPreLiveUnavailable by
                            engine.meterPreLiveUnavailable.collectAsState()
                        if (meterPreLiveUnavailable) {
                            // BT mic selected and we're not yet streaming. SCO
                            // routing belongs to the streaming path, so we'd
                            // be reading the built-in mic if we ran the meter
                            // here. Honest note instead - still tappable so
                            // the panel for switching mics is reachable.
                            MicMeterNote(
                                "Mic level shows once you're live",
                                onClick = { micPanelOpen = true },
                            )
                        } else {
                            val rawLevel by engine.audioLevel.collectAsState()
                            // Muting is applied AFTER our effect tap, so a muted
                            // mic still feeds real audio through. Clamp to 0 here
                            // so the meter reads "silent" the moment the user mutes.
                            AudioLevelPill(
                                level = if (isMuted) 0f else rawLevel,
                                onClick = { micPanelOpen = true },
                            )
                        }
                    }
                }
                if (obsBrbPrivacyActive && !obsBrbBannerDismissed) {
                    // Persistent while the OBS BRB scene is program: the phone
                    // otherwise shows a live-looking HUD during a break and
                    // the streamer cannot tell the BRB actually took.
                    NoticeBanner(
                        text = if (obsConnected) {
                            "OBS BRB active - viewers see your BRB scene"
                        } else {
                            "OBS disconnected during BRB - mic and camera remain blocked"
                        },
                        onDismiss = { obsBrbBannerDismissed = true },
                    )
                }
                thermalNotice?.let { notice ->
                    NoticeBanner(text = notice, onDismiss = { engine.dismissThermalNotice() })
                }
                recordNotice?.let { notice ->
                    NoticeBanner(text = notice, onDismiss = { engine.dismissRecordNotice() })
                }
                micNotice?.let { notice ->
                    NoticeBanner(text = notice, onDismiss = { engine.dismissMicNotice() })
                }
                updateOffer?.let { update ->
                    UpdateBanner(
                        versionName = update.versionName,
                        onGet = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(update.url)),
                                )
                            }
                        },
                        onDismiss = {
                            Prefs.setDismissedUpdateCode(context, update.versionCode)
                            updateOffer = null
                        },
                    )
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
                    icon = Icons.Filled.FlipCameraAndroid,
                    description = if (isDualCamOn) "Swap main and PiP cameras" else "Switch camera",
                    onClick = { engine.switchCamera() },
                )
                val brbScope = rememberCoroutineScope()
                IconChip(
                    icon = Icons.Filled.Coffee,
                    description = when {
                        obsBrbPrivacyActive && !obsConnected ->
                            "OBS disconnected during BRB; privacy lock active"
                        obsConnected && obsBrbScene.isNotBlank() && obsBrbActive ->
                            "Return from BRB"
                        obsConnected && obsBrbScene.isNotBlank() -> "Switch OBS to BRB scene"
                        isBrb -> "End break"
                        else -> "Break screen"
                    },
                    onClick = {
                        if (obsBrbPrivacyActive && !obsConnected) {
                            android.widget.Toast.makeText(
                                context,
                                "OBS is disconnected. Mic and camera stay blocked until " +
                                    "OBS reconnects and confirms a non-BRB scene.",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        } else if (obsConnected && obsBrbScene.isNotBlank()) {
                            val target = if (obsBrbActive) {
                                lastNonBrbScene ?: ObsClient.scenes.value.firstOrNull {
                                    it != obsBrbScene
                                }
                            } else {
                                obsBrbScene
                            }
                            if (target != null) {
                                val phoneBrbActive = isBrb
                                brbScope.launch {
                                    val ok = ObsClient.setCurrentScene(target)
                                    // OBS mode. If a phone-side BRB is still
                                    // active (e.g. the OBS BRB scene was
                                    // configured while the phone BRB screen was
                                    // already up), clear it too, or the phone is
                                    // stranded on its own BRB with no dismiss
                                    // path from this button. Only AFTER the
                                    // scene switch confirmed, though: clearing
                                    // first unmutes mic + camera, and a failed
                                    // switch (scene renamed, request timeout)
                                    // would leave them hot behind a break the
                                    // streamer believes is up.
                                    if (ok && phoneBrbActive) engine.toggleBrb()
                                    if (!ok) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "OBS scene switch failed - check the scene name in Settings",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            } else {
                                // No scene to return to (fresh session while
                                // OBS sat on BRB and the scene list is still
                                // empty). Silence here read as a dead button
                                // in the field; say what to do instead.
                                android.widget.Toast.makeText(
                                    context,
                                    "No scene to return to - pick one from the OBS panel",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        } else {
                            engine.toggleBrb()
                        }
                    },
                    accent = if (isBrb || obsBrbPrivacyActive) RoamLive else null,
                )
                IconChip(
                    icon = if (isTorchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                    description = if (isTorchOn) "Turn torch off" else "Turn torch on",
                    onClick = { engine.toggleTorch() },
                    accent = if (isTorchOn) RoamLive else null,
                )
                IconChip(
                    icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    description = when {
                        obsBrbPrivacyActive && isMuted -> "Microphone locked during OBS BRB"
                        isMuted -> "Unmute microphone"
                        else -> "Mute microphone"
                    },
                    onClick = {
                        if (obsBrbPrivacyActive && isMuted) {
                            android.widget.Toast.makeText(
                                context,
                                "Microphone stays muted until OBS confirms BRB has ended.",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            engine.toggleMute()
                        }
                    },
                    accent = if (isMuted) RoamLive else null,
                )
                IconChip(
                    icon = if (isCameraOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                    description = when {
                        obsBrbPrivacyActive && isCameraOff -> "Camera locked during OBS BRB"
                        isCameraOff -> "Turn camera on"
                        else -> "Turn camera off"
                    },
                    onClick = {
                        if (obsBrbPrivacyActive && isCameraOff) {
                            android.widget.Toast.makeText(
                                context,
                                "Camera stays blocked until OBS confirms BRB has ended.",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            engine.toggleCameraOff()
                        }
                    },
                    accent = if (isCameraOff || isBrb) RoamLive else null,
                )
                if (dualCamSupported && dualCamEnabled) {
                    IconChip(
                        icon = Icons.Filled.PictureInPictureAlt,
                        description = if (isDualCamOn) "Turn dual camera off" else "Turn dual camera on",
                        onClick = { engine.toggleDualCam() },
                        accent = if (isDualCamOn) RoamLive else null,
                    )
                }
                IconChip(
                    icon = Icons.Filled.VisibilityOff,
                    description = "Stealth mode",
                    onClick = { stealthActive = true },
                    enabled = streamActive,
                )
                IconChip(
                    icon = Icons.Filled.Settings,
                    description = "Settings",
                    onClick = onOpenSettings,
                )
            }

            if (chatEnabled &&
                (kickChannel.isNotBlank() || twitchChannel.isNotBlank() || youtubeChannel.isNotBlank())
            ) {
                val chatTextSizeSp = remember(configRevision) { Prefs.chatTextSizeSp(context) }
                val chatPanelMode = remember(configRevision) { Prefs.chatPanelMode(context) }
                val chatPanelSide = remember(configRevision) { Prefs.chatPanelSide(context) }
                val widthModifier = when (chatPanelMode) {
                    Prefs.CHAT_PANEL_HALF -> Modifier.fillMaxWidth(0.5f)
                    Prefs.CHAT_PANEL_WIDE -> Modifier.width(380.dp)
                    else -> Modifier.width(270.dp)
                }
                val sideAlignment = if (chatPanelSide == Prefs.CHAT_SIDE_RIGHT) {
                    Alignment.CenterEnd
                } else {
                    Alignment.CenterStart
                }
                ChatOverlay(
                    messages = chatMessages,
                    textSizeSp = chatTextSizeSp,
                    modifier = Modifier
                        .align(sideAlignment)
                        .padding(top = 76.dp, bottom = 24.dp)
                        .then(widthModifier)
                        .fillMaxHeight(),
                )
            }

            if (micPanelOpen) {
                val micDevices = remember { dev.whitespc.roam.audio.MicDevices.list(context) }
                val currentMicName = Prefs.micDeviceName(context)
                val currentMicType = Prefs.micDeviceType(context)
                var micGain by remember { mutableFloatStateOf(Prefs.micGain(context)) }
                // If the user has a mic saved but it's not in the list right
                // now (e.g. unplugged USB, BT disconnected), the system has
                // fallen back to default - so the panel highlights Default
                // to reflect what's actually being used.
                val savedKey = if (currentMicName != null && currentMicType != null) {
                    "$currentMicType|$currentMicName"
                } else null
                val effectiveKey = if (savedKey != null &&
                    micDevices.any { "${it.type}|${it.productName}" == savedKey }
                ) savedKey else null
                MicPanel(
                    devices = micDevices,
                    selectedDeviceKey = effectiveKey,
                    gain = micGain,
                    onSelectDefault = {
                        Prefs.setMicDevice(context, null, null)
                        engine.applyMicDevicePref(context)
                    },
                    onSelectDevice = { d ->
                        Prefs.setMicDevice(context, d.productName, d.type)
                        engine.applyMicDevicePref(context)
                    },
                    onGainChange = {
                        micGain = it
                        Prefs.setMicGain(context, it)
                        engine.setMicGain(it)
                    },
                    onDismiss = { micPanelOpen = false },
                )
            }

            if (scenePickerOpen) {
                val scenes by ObsClient.scenes.collectAsState()
                val current by ObsClient.currentScene.collectAsState()
                val pickerStreaming by ObsClient.streaming.collectAsState()
                val scope = rememberCoroutineScope()
                ObsScenePicker(
                    scenes = scenes,
                    currentScene = current,
                    streaming = pickerStreaming,
                    onSelect = { name ->
                        scope.launch { ObsClient.setCurrentScene(name) }
                        scenePickerOpen = false
                    },
                    onToggleStream = {
                        if (pickerStreaming) {
                            scope.launch { ObsClient.stopStream() }
                            scenePickerOpen = false
                        } else {
                            obsStartConfirmationOpen = true
                        }
                    },
                    onDismiss = { scenePickerOpen = false },
                )
            }

            LiveButton(
                state = state,
                onGoLive = {
                    val destination = Prefs.streamUrl(context)
                    if (validateStreamEndpoint(destination) is StreamEndpointValidation.Valid) {
                        startWithBackgroundStopControl(destination)
                    } else {
                        destinationNeededOpen = true
                    }
                },
                onStop = onStopSession,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }

        if (destinationNeededOpen) {
            AlertDialog(
                onDismissRequest = { destinationNeededOpen = false },
                title = { Text("Stream destination needed") },
                text = {
                    Text("Enter a valid Stream URL in Settings > Stream before going live.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            destinationNeededOpen = false
                            onOpenSettings()
                        },
                    ) {
                        Text("Open settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { destinationNeededOpen = false }) {
                        Text("Not now")
                    }
                },
            )
        }

        if (obsStartConfirmationOpen) {
            AlertDialog(
                onDismissRequest = { obsStartConfirmationOpen = false },
                title = { Text("Start OBS streaming?") },
                text = {
                    Text(
                        "This starts OBS's configured stream and may broadcast publicly. " +
                            "Check the destination in OBS before continuing.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            obsStartConfirmationOpen = false
                            scenePickerOpen = false
                            obsStartScope.launch { ObsClient.startStream() }
                        },
                    ) {
                        Text("Start streaming")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { obsStartConfirmationOpen = false }) {
                        Text("Cancel")
                    }
                },
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

/** Live mic level on the HUD: 4 stacked bars in a pill shell that match the
 *  MetricPill look. Bars light up at progressive thresholds (10/35/65/85%).
 *  Tinting green/yellow/red at the top mirrors a standard VU meter so a
 *  glance tells you "mic is alive" vs "mic is hot" vs "mic is dead". */
@Composable
private fun AudioLevelPill(
    level: Float,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val l = level.coerceIn(0f, 1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "MIC",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AudioLevelBar(lit = l >= 0.10f, color = Color(0xFF53FC18))
            AudioLevelBar(lit = l >= 0.35f, color = Color(0xFF53FC18))
            AudioLevelBar(lit = l >= 0.65f, color = Color(0xFFE8B43A))
            AudioLevelBar(lit = l >= 0.85f, color = Color(0xFFFF2D2D))
        }
    }
}

/** Mic control panel: open from the HUD audio-meter pill. Lists the same
 *  input devices the Settings picker shows so the streamer can swap mics
 *  mid-broadcast (e.g. external mic battery dies, fall back to built-in)
 *  without diving into Settings. The gain slider applies live via
 *  [StreamingEngine.setMicGain]; the value persists as the new default. */
@Composable
private fun MicPanel(
    devices: List<dev.whitespc.roam.audio.MicDevice>,
    selectedDeviceKey: String?,
    gain: Float,
    onSelectDefault: () -> Unit,
    onSelectDevice: (dev.whitespc.roam.audio.MicDevice) -> Unit,
    onGainChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 64.dp, end = 16.dp, bottom = 16.dp)
                .widthIn(min = 260.dp, max = 360.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .padding(12.dp)
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Mic device",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            HorizontalDivider(color = RoamLive, thickness = 1.dp)
            MicPanelRow(
                label = "Default (system choice)",
                selected = selectedDeviceKey == null,
                onClick = onSelectDefault,
            )
            devices.forEach { d ->
                val key = "${d.type}|${d.productName}"
                MicPanelRow(
                    label = d.label,
                    selected = selectedDeviceKey == key,
                    onClick = { onSelectDevice(d) },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Input gain",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            HorizontalDivider(color = RoamLive, thickness = 1.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${(gain * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(56.dp),
                )
                Slider(
                    value = gain,
                    onValueChange = onGainChange,
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
}

@Composable
private fun MicPanelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) RoamLive.copy(alpha = 0.18f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (selected) RoamLive else MaterialTheme.colorScheme.outline),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Scene picker drawer overlay: dimmed scrim with a left-anchored panel of
 *  scenes from the paired OBS. Tap a scene to switch and dismiss; tap the
 *  scrim to dismiss without switching. Sits left-anchored under the OBS pill
 *  (which is in the top-left corner) so the user's tap travel from pill to
 *  list is minimal - matters for one-handed mid-stream operation. */
@Composable
private fun ObsScenePicker(
    scenes: List<String>,
    currentScene: String?,
    streaming: Boolean,
    onSelect: (String) -> Unit,
    onToggleStream: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
    ) {
        // The controls panel keeps its exact layout; the program preview docks
        // to its right as a separate pane, so the scene list's scrolling and
        // taps are untouched.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 64.dp, end = 16.dp, bottom = 16.dp),
        ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .widthIn(min = 220.dp, max = 340.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .padding(12.dp)
                // Catch taps inside the panel so the scrim's onClick doesn't dismiss.
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "OBS Controls",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            HorizontalDivider(color = RoamLive, thickness = 1.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (streaming) RoamLive.copy(alpha = 0.18f)
                        else Color.Transparent,
                    )
                    .clickable(onClick = onToggleStream)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (streaming) RoamLive else Color(0xFF53FC18)),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (streaming) "Stop OBS streaming" else "Start OBS streaming",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "OBS Scenes",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            HorizontalDivider(color = RoamLive, thickness = 1.dp)
            if (scenes.isEmpty()) {
                Text(
                    text = "No scenes received yet. Make sure OBS has at least " +
                        "one scene and the connection is live.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            } else {
                scenes.forEach { name ->
                    val isCurrent = name == currentScene
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isCurrent) RoamLive.copy(alpha = 0.18f)
                                else Color.Transparent,
                            )
                            .clickable { onSelect(name) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isCurrent) RoamLive
                                    else MaterialTheme.colorScheme.outline,
                                ),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = name,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        ObsProgramPreview()
        }
    }
}

/** Live "what viewers see" pane docked beside the OBS drawer: a real
 *  snapshot of the OBS program output, refreshed while the drawer is open.
 *  It exists only inside the drawer on purpose: zero screen space and zero
 *  polling cost when the drawer is closed, and nothing to configure. */
@Composable
private fun ObsProgramPreview() {
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            // Keep the last good frame through transient failures so the
            // pane doesn't flicker back to the placeholder.
            frame = ObsClient.getProgramScreenshot() ?: frame
            delay(3000)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .width(250.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(12.dp)
            // Catch taps so the scrim's onClick doesn't dismiss.
            .clickable(enabled = false) {},
    ) {
        Text(
            text = "Viewers see",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
        HorizontalDivider(color = RoamLive, thickness = 1.dp)
        val bmp = frame
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "OBS program output",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.4f)),
            ) {
                Text(
                    text = "Waiting for OBS...",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
            }
        }
        Text(
            text = "The OBS program output, a few seconds behind.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

/** Pill-shaped honest note shown in place of the meter when the meter setting
 *  is on but the pre-live reader can't accurately reflect the selected mic
 *  (Bluetooth, currently). Same shell as the other status pills so the row
 *  stays visually consistent. */
@Composable
private fun MicMeterNote(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "MIC",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun AudioLevelBar(lit: Boolean, color: Color) {
    Box(
        modifier = Modifier
            .size(width = 3.dp, height = 10.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(if (lit) color else color.copy(alpha = 0.18f)),
    )
}

@Composable
private fun MetricPill(
    label: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    // Full-pill fill for the "live" language: a red pill reads as broadcasting
    // the same way the status pill does, so OBS LIVE spot-checks right next to
    // LIVE. The dot keeps carrying connection state on top of the fill.
    backgroundColor: Color = Color.Black.copy(alpha = 0.55f),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
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

/** Quiet dark pill for "a new version exists": tap GET to open the download
 *  page, tap the cross to never see this version again. Deliberately not the
 *  amber warning banner; an update is information, not a problem. */
@Composable
private fun UpdateBanner(
    versionName: String,
    onGet: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Roam Live $versionName is available",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "GET",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onGet),
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Dismiss update notice",
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .size(16.dp)
                .clickable(onClick = onDismiss),
        )
    }
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    // No auto-dismiss: the banner stays as long as the engine reports a notice
    // (thermal or recording) - set while the condition holds, cleared when it
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
    onGoLive: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val streaming = state is StreamState.Live ||
        state == StreamState.Connecting ||
        state is StreamState.Reconnecting
    val backgroundColor = if (streaming) RoamLive else MaterialTheme.colorScheme.primary
    val textColor = if (streaming) Color.White else MaterialTheme.colorScheme.onPrimary
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(streaming) {
        if (streaming) progress.snapTo(0f)
    }

    Box(
        modifier = modifier
            .size(104.dp)
            .pointerInput(streaming) {
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
