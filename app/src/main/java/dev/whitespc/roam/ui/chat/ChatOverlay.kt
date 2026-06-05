package dev.whitespc.roam.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.whitespc.roam.chat.ChatMessage

@Composable
fun ChatOverlay(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Key on the last message, not messages.size. The buffer caps at 200, so once
    // a busy chat saturates, the size stops changing and a size-keyed effect would
    // never fire again — auto-scroll would silently die mid-stream while new
    // messages keep arriving. The last message changes on every append.
    LaunchedEffect(messages.lastOrNull()) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
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
                    ChatRow(msg)
                }
            }
        }
    }
}

@Composable
private fun ChatRow(message: ChatMessage) {
    val text = buildAnnotatedString {
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
    // Faint platform-brand tint behind the row — Kick green, Twitch purple,
    // YouTube red — so the source platform reads instantly while you're scanning
    // chat at a glance. Replaces the older left-side dot.
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(message.platform.brandColor).copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 16.sp,
        )
    }
}

@Suppress("unused")
private fun AnnotatedString.unused() = Unit
