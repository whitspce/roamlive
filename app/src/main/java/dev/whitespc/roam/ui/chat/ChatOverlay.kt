package dev.whitespc.roam.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.whitespc.roam.chat.ChatMessage
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Composable
fun ChatOverlay(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    textSizeSp: Int = 13,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Sticky follow: auto-scroll only while the user is at the bottom. Once
    // they drag up to read back, stay put (no snap-back on the next message)
    // and offer a pill back down.
    //
    // Turning follow OFF keys on the user's DRAG, not on scroll settles: our
    // own animateScrollToItem also settles (and gets cancelled mid-flight when
    // the next message restarts the effect below), and a settle-based check
    // read those as "user scrolled away", silently killing auto-follow on
    // busy chats. Settles only ever turn follow back ON (settled at bottom),
    // which is safe from any scroll source.
    var followLatest by remember { mutableStateOf(true) }
    var newWhileAway by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followLatest = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { scrolling -> !scrolling }
            .collect {
                if (!listState.canScrollForward) {
                    followLatest = true
                    newWhileAway = 0
                }
            }
    }

    // Key on the last message, not messages.size. The buffer caps at 200, so once
    // a busy chat saturates, the size stops changing and a size-keyed effect would
    // never fire again: auto-scroll would silently die mid-stream while new
    // messages keep arriving. The last message changes on every append.
    LaunchedEffect(messages.lastOrNull()) {
        if (messages.isEmpty()) {
            // List cleared (channel/platform change): reset, or a stale count
            // reappears on the pill with the next batch of messages.
            followLatest = true
            newWhileAway = 0
            return@LaunchedEffect
        }
        if (followLatest) {
            listState.animateScrollToItem(messages.lastIndex)
        } else {
            newWhileAway++
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.5f)),
    ) {
        if (messages.isEmpty()) {
            Text(
                text = "No chat yet",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 10.dp,
                    vertical = 8.dp,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(messages, key = { "${it.platform}-${it.id}" }) { msg ->
                    ChatRow(msg, textSizeSp)
                }
            }
            if (!followLatest && newWhileAway > 0) {
                Text(
                    text = if (newWhileAway == 1) {
                        "1 new message"
                    } else {
                        "$newWhileAway new messages"
                    },
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.8f))
                        .clickable {
                            followLatest = true
                            newWhileAway = 0
                            scope.launch {
                                listState.animateScrollToItem(messages.lastIndex)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// Amber marker for money/milestone events (subs, bits, redeems, raids,
// super chats): distinct from every platform brand color so events read as
// events regardless of source.
private val EventAmber = Color(0xFFFFB74D)

@Composable
private fun ChatRow(message: ChatMessage, textSizeSp: Int) {
    val text = buildAnnotatedString {
        message.eventLabel?.let { label ->
            withStyle(
                SpanStyle(
                    color = EventAmber,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append(label)
                append("  ")
            }
        }
        withStyle(
            SpanStyle(
                color = Color(message.usernameColor),
                fontWeight = FontWeight.Bold,
            ),
        ) {
            append(message.username)
        }
        withStyle(SpanStyle(color = Color.White)) {
            append(": ")
            append(message.text)
        }
    }
    // Faint platform-brand tint behind the row (Kick green, Twitch purple,
    // YouTube red) so the source platform reads instantly while you're scanning
    // chat at a glance. Event rows get a stronger amber tint instead, so the
    // things streamers hate missing stand out from the scroll.
    val rowTint = if (message.eventLabel != null) {
        EventAmber.copy(alpha = 0.22f)
    } else {
        Color(message.platform.brandColor).copy(alpha = 0.10f)
    }
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(rowTint)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            fontSize = textSizeSp.sp,
            lineHeight = (textSizeSp + 3).sp,
        )
    }
}
