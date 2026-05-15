package dev.whitespc.roam.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

    LaunchedEffect(messages.size) {
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
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Top) {
        PlatformDot(color = Color(message.platform.brandColor))
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun PlatformDot(color: Color) {
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Suppress("unused")
private fun AnnotatedString.unused() = Unit
