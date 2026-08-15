package dev.whitespc.roam.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.whitespc.roam.chat.ChatManager
import dev.whitespc.roam.storage.Prefs
import kotlinx.coroutines.delay

// Applying a channel tears down and reopens its chat socket, so wait for
// typing to settle instead of reconnecting on every keystroke.
private const val ChannelApplyDebounceMs = 700L

/** The on-phone chat panel. Setup-once for most people: type the channel
 *  names, leave it on. */
@Composable
internal fun ChatPane() {
    val context = LocalContext.current
    var chatEnabled by remember { mutableStateOf(Prefs.chatEnabled(context)) }
    var kickChannel by remember { mutableStateOf(Prefs.kickChannel(context)) }
    var twitchChannel by remember { mutableStateOf(Prefs.twitchChannel(context)) }
    var youtubeChannel by remember { mutableStateOf(Prefs.youtubeChannel(context)) }
    var chatTextSize by remember { mutableIntStateOf(Prefs.chatTextSizeSp(context)) }
    var chatPanelMode by remember { mutableStateOf(Prefs.chatPanelMode(context)) }
    var chatPanelSide by remember { mutableStateOf(Prefs.chatPanelSide(context)) }

    // Prefs persist per keystroke (cheap, no side effect); the ChatManager
    // apply is debounced. The close-Settings sync in StreamScreen covers a
    // pending apply if the user types and leaves within the debounce window.
    var lastAppliedKick by remember { mutableStateOf(kickChannel) }
    var lastAppliedTwitch by remember { mutableStateOf(twitchChannel) }
    var lastAppliedYouTube by remember { mutableStateOf(youtubeChannel) }
    LaunchedEffect(kickChannel) {
        if (kickChannel == lastAppliedKick) return@LaunchedEffect
        delay(ChannelApplyDebounceMs)
        lastAppliedKick = kickChannel
        ChatManager.setKickChannel(if (chatEnabled) kickChannel.trim() else null)
    }
    LaunchedEffect(twitchChannel) {
        if (twitchChannel == lastAppliedTwitch) return@LaunchedEffect
        delay(ChannelApplyDebounceMs)
        lastAppliedTwitch = twitchChannel
        ChatManager.setTwitchChannel(if (chatEnabled) twitchChannel.trim() else null)
    }
    LaunchedEffect(youtubeChannel) {
        if (youtubeChannel == lastAppliedYouTube) return@LaunchedEffect
        delay(ChannelApplyDebounceMs)
        lastAppliedYouTube = youtubeChannel
        ChatManager.setYouTubeChannel(if (chatEnabled) youtubeChannel.trim() else null)
    }

    PaneScaffold(title = "Chat") {
        ToggleRow(
            label = "Show chat panel",
            description = "Visible only on this phone, never sent into your broadcast.",
            checked = chatEnabled,
            onCheckedChange = { enabled ->
                chatEnabled = enabled
                Prefs.setChatEnabled(context, enabled)
                // The toggle gates both sources, so re-apply them together,
                // immediately, and mark the current names applied so a pending
                // debounce doesn't repeat the work.
                lastAppliedKick = kickChannel
                lastAppliedTwitch = twitchChannel
                lastAppliedYouTube = youtubeChannel
                ChatManager.setKickChannel(if (enabled) kickChannel.trim() else null)
                ChatManager.setTwitchChannel(if (enabled) twitchChannel.trim() else null)
                ChatManager.setYouTubeChannel(if (enabled) youtubeChannel.trim() else null)
            },
        )
        LabeledField(
            label = "Kick channel name",
            value = kickChannel,
            onValueChange = { text ->
                val cleaned = text.filter { c -> !c.isWhitespace() }
                kickChannel = cleaned
                Prefs.setKickChannel(context, cleaned)
            },
            placeholder = "your-kick-username",
        )
        LabeledField(
            label = "Twitch channel name",
            value = twitchChannel,
            onValueChange = { text ->
                val cleaned = text.filter { c -> !c.isWhitespace() }
                twitchChannel = cleaned
                Prefs.setTwitchChannel(context, cleaned)
            },
            placeholder = "your-twitch-username",
        )
        LabeledField(
            label = "YouTube channel handle",
            value = youtubeChannel,
            onValueChange = { text ->
                val cleaned = text.filter { c -> !c.isWhitespace() }
                youtubeChannel = cleaned
                Prefs.setYouTubeChannel(context, cleaned)
            },
            placeholder = "@your-youtube-handle",
        )
        HelpText(
            "Chat connects once this channel is live. YouTube reading uses an " +
                "unofficial interface and may break at any time.",
        )
        SubHeading("Appearance")
        FieldLabel("Text size")
        ChipRow(
            options = listOf(11 to "Small", 13 to "Medium", 16 to "Large"),
            selected = chatTextSize,
            onSelect = {
                chatTextSize = it
                Prefs.setChatTextSizeSp(context, it)
            },
        )
        FieldLabel("Panel width")
        ChipRow(
            options = listOf(
                Prefs.CHAT_PANEL_COMPACT to "Compact",
                Prefs.CHAT_PANEL_WIDE to "Wide",
                Prefs.CHAT_PANEL_HALF to "Half screen",
            ),
            selected = chatPanelMode,
            onSelect = {
                chatPanelMode = it
                Prefs.setChatPanelMode(context, it)
            },
        )
        FieldLabel("Panel side")
        ChipRow(
            options = listOf(
                Prefs.CHAT_SIDE_LEFT to "Left",
                Prefs.CHAT_SIDE_RIGHT to "Right",
            ),
            selected = chatPanelSide,
            onSelect = {
                chatPanelSide = it
                Prefs.setChatPanelSide(context, it)
            },
        )
    }
}
