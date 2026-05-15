package dev.whitespc.roam.chat

data class ChatMessage(
    val id: String,
    val platform: ChatPlatform,
    val username: String,
    val usernameColor: Int,
    val text: String,
    val timestampMs: Long,
)

enum class ChatPlatform(val label: String, val brandColor: Int) {
    Kick(label = "Kick", brandColor = 0xFF53FC18.toInt()),
    Twitch(label = "Twitch", brandColor = 0xFF9146FF.toInt()),
    YouTube(label = "YouTube", brandColor = 0xFFFF0000.toInt()),
}
