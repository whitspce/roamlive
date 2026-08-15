package dev.whitespc.roam.chat

data class ChatMessage(
    val id: String,
    val platform: ChatPlatform,
    val username: String,
    val usernameColor: Int,
    val text: String,
    val timestampMs: Long,
    /** Short marker for money/milestone events (SUB, GIFT, RAID, BITS x500,
     *  REDEEM, FIRST, SUPER CHAT). Non-null renders the row emphasized with
     *  a label chip, so the events streamers most hate missing stand out
     *  from the scroll. */
    val eventLabel: String? = null,
)

enum class ChatPlatform(val label: String, val brandColor: Int) {
    Kick(label = "Kick", brandColor = 0xFF53FC18.toInt()),
    Twitch(label = "Twitch", brandColor = 0xFF9146FF.toInt()),
    YouTube(label = "YouTube", brandColor = 0xFFFF0000.toInt()),
}
