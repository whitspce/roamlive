package dev.whitespc.roam.chat

import dev.whitespc.roam.diagnostics.RoamLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

object ChatManager {
    private const val MAX_BUFFERED = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sources = mutableMapOf<ChatPlatform, SourceHandle>()
    private val messageSequence = AtomicLong()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun setKickChannel(channel: String?) {
        val normalized = channel?.let(::normalizeKickChannel)
        Log.d("RoamChatManager", "Kick chat ${if (normalized == null) "disabled" else "enabled"}")
        replaceSource(ChatPlatform.Kick, normalized?.let(::KickChatSource))
    }

    fun setTwitchChannel(channel: String?) {
        val normalized = channel?.let(::normalizeTwitchChannel)
        Log.d("RoamChatManager", "Twitch chat ${if (normalized == null) "disabled" else "enabled"}")
        replaceSource(ChatPlatform.Twitch, normalized?.let(::TwitchChatSource))
    }

    fun setYouTubeChannel(channel: String?) {
        val normalized = channel?.let(::normalizeYouTubeChannel)
        Log.d("RoamChatManager", "YouTube chat ${if (normalized == null) "disabled" else "enabled"}")
        replaceSource(ChatPlatform.YouTube, normalized?.let(::YouTubeChatSource))
    }

    private fun replaceSource(platform: ChatPlatform, source: ChatSource?) {
        sources[platform]?.let { handle ->
            scope.launch { handle.source.disconnect() }
            handle.collectorJob.cancel()
            handle.connectorJob.cancel()
        }
        sources.remove(platform)
        clearMessagesFromPlatform(platform)

        if (source == null) return

        val collectorJob = scope.launch {
            source.messages.collect { msg ->
                val safe = sanitizeChatMessage(msg, messageSequence.incrementAndGet()) ?: return@collect
                _messages.update { (it + safe).takeLast(MAX_BUFFERED) }
            }
        }
        val connectorJob = scope.launch {
            source.connect()
        }
        sources[platform] = SourceHandle(source, collectorJob, connectorJob)
    }

    private fun clearMessagesFromPlatform(platform: ChatPlatform) {
        _messages.update { list -> list.filter { it.platform != platform } }
    }

    fun clear() {
        _messages.value = emptyList()
    }

    private data class SourceHandle(
        val source: ChatSource,
        val collectorJob: Job,
        val connectorJob: Job,
    )
}

internal fun sanitizeChatMessage(message: ChatMessage, sequence: Long): ChatMessage? {
    val username = message.username.trim().take(80)
    val text = message.text.trim().take(1_000)
    if (username.isEmpty() || text.isEmpty()) return null
    return message.copy(
        id = "${message.id.take(96)}:$sequence",
        username = username,
        text = text,
        eventLabel = message.eventLabel?.trim()?.take(32)?.takeIf(String::isNotEmpty),
    )
}
