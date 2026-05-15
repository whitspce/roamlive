package dev.whitespc.roam.chat

import kotlinx.coroutines.flow.SharedFlow

interface ChatSource {
    val platform: ChatPlatform
    val messages: SharedFlow<ChatMessage>
    suspend fun connect()
    suspend fun disconnect()
}
