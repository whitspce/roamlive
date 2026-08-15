package dev.whitespc.roam.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whitespc.roam.ui.theme.RoamGraphite

/** The sections of the settings surface, in rail order: what a newcomer
 *  needs first, set-and-forget and advanced further down. */
internal enum class SettingsSection(val label: String) {
    Stream("Stream"),
    Quality("Quality"),
    Audio("Audio"),
    Camera("Camera"),
    Overlays("Overlays"),
    Chat("Chat"),
    BreakStealth("Break & stealth"),
    Reliability("Reliability"),
    Obs("OBS"),
    HelpAbout("Help & about"),
}
/** One settings surface, laid out for the landscape screen: a section rail on
 *  the left (the whole menu at a glance) and the selected section's controls
 *  on the right. Drawn as an overlay over the never-unmounted streaming
 *  surface, reachable live and offline; locked items grey out while live. */
@Composable
fun SettingsScreen(
    isLive: Boolean,
    onApplyLiveBitrate: (Int) -> Unit,
    onApplyAutoBitrate: (Boolean) -> Unit,
    onApplyRecording: (Boolean) -> Unit,
    onApplyStabilization: () -> Unit,
    onApplyDualCam: (Boolean) -> Unit,
    onApplyMirrorFrontPip: () -> Unit,
    onApplyMicDevice: () -> Unit,
    onApplyAudioMeter: (Boolean) -> Unit,
    onApplyMicGain: (Float) -> Unit,
    onClose: () -> Unit,
    onOpenOverlays: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.Stream) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Cutout included: in landscape the front camera sits at one side
            // edge and would punch a hole straight through the rail or pane.
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
    ) {
        SectionRail(
            selected = section,
            isLive = isLive,
            onSelect = { section = it },
            onClose = onClose,
        )
        VerticalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            thickness = 1.dp,
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (section) {
                SettingsSection.Stream -> StreamPane(isLive = isLive)
                SettingsSection.Quality -> QualityPane(
                    isLive = isLive,
                    onApplyLiveBitrate = onApplyLiveBitrate,
                    onApplyAutoBitrate = onApplyAutoBitrate,
                )
                SettingsSection.Audio -> AudioPane(
                    onApplyMicDevice = onApplyMicDevice,
                    onApplyAudioMeter = onApplyAudioMeter,
                    onApplyMicGain = onApplyMicGain,
                )
                SettingsSection.Camera -> CameraPane(
                    onApplyStabilization = onApplyStabilization,
                    onApplyDualCam = onApplyDualCam,
                    onApplyMirrorFrontPip = onApplyMirrorFrontPip,
                )
                SettingsSection.Overlays -> OverlaysPane(onOpenOverlays = onOpenOverlays)
                SettingsSection.Chat -> ChatPane()
                SettingsSection.BreakStealth -> BreakStealthPane()
                SettingsSection.Reliability -> ReliabilityPane(
                    onApplyRecording = onApplyRecording,
                )
                SettingsSection.Obs -> ObsPane()
                SettingsSection.HelpAbout -> HelpAboutPane()
            }
        }
    }
}

@Composable
private fun SectionRail(
    selected: SettingsSection,
    isLive: Boolean,
    onSelect: (SettingsSection) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(210.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 4.dp, end = 8.dp),
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Settings",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        SettingsSection.entries.forEach { item ->
            RailItem(
                item = item,
                selected = item == selected,
                // The URL is the one fully locked pane while live; per-field
                // locks (resolution, fps) show inside their panes instead.
                showLock = isLive && item == SettingsSection.Stream,
                onClick = { onSelect(item) },
            )
        }
    }
}

@Composable
private fun RailItem(
    item: SettingsSection,
    selected: Boolean,
    showLock: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) RoamGraphite else MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = item.label,
            color = if (selected) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (showLock) {
            LockBadge()
        }
    }
}
