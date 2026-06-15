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

object ChatManager {
    private const val MAX_BUFFERED = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sources = mutableMapOf<ChatPlatform, SourceHandle>()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun setKickChannel(channel: String?) {
        Log.d("RoamChatManager", "setKickChannel(${channel?.let { "'$it'" } ?: "null"})")
        replaceSource(ChatPlatform.Kick, channel?.takeIf { it.isNotBlank() }?.let { KickChatSource(it) })
    }

    fun setTwitchChannel(channel: String?) {
        Log.d("RoamChatManager", "setTwitchChannel(${channel?.let { "'$it'" } ?: "null"})")
        replaceSource(ChatPlatform.Twitch, channel?.takeIf { it.isNotBlank() }?.let { TwitchChatSource(it) })
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
                _messages.update { (it + msg).takeLast(MAX_BUFFERED) }
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
