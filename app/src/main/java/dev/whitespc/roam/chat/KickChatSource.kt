package dev.whitespc.roam.chat

import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "RoamKickChat"
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 11; Pixel 2 XL) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
private const val PUSHER_URL =
    "wss://ws-us2.pusher.com/app/32cbd69e4b950bf97679?" +
        "protocol=7&client=js&version=8.4.0&flash=false"
private const val EVENT_CHAT_MESSAGE = "App\\Events\\ChatMessageEvent"

class KickChatSource(private val channelName: String) : ChatSource {

    override val platform = ChatPlatform.Kick

    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 128)
    override val messages: SharedFlow<ChatMessage> = _messages.asSharedFlow()

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var chatroomId: Long = -1

    override suspend fun connect() {
        Log.d(TAG, "connect() entered for channel '$channelName'")
        val resolved = fetchChatroomId(channelName)
        Log.d(TAG, "fetchChatroomId returned ${resolved ?: "null"}")
        if (resolved == null) {
            Log.w(TAG, "could not resolve chatroom for $channelName")
            return
        }
        chatroomId = resolved
        openWebSocket(resolved)
    }

    override suspend fun disconnect() {
        webSocket?.close(1000, "client disconnect")
        webSocket = null
    }

    private suspend fun fetchChatroomId(username: String): Long? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://kick.com/api/v2/channels/$username")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "channel fetch failed ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val root = JSONObject(body)
                root.optJSONObject("chatroom")?.optLong("id")?.takeIf { it > 0L }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "channel fetch error", t)
            null
        }
    }

    private fun openWebSocket(chatroomId: Long) {
        val request = Request.Builder()
            .url(PUSHER_URL)
            .header("User-Agent", USER_AGENT)
            .build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "ws onOpen (${response.code})")
                ws.send(
                    """{"event":"pusher:subscribe","data":{"channel":"chatrooms.$chatroomId.v2"}}""",
                )
            }

            override fun onMessage(ws: WebSocket, text: String) {
                parseAndEmit(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure (response code ${response?.code})", t)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws closed $code $reason")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws closing $code $reason")
            }
        })
    }

    private fun parseAndEmit(text: String) {
        try {
            val outer = JSONObject(text)
            if (outer.optString("event") != EVENT_CHAT_MESSAGE) return
            val data = JSONObject(outer.getString("data"))
            val sender = data.getJSONObject("sender")
            val identity = sender.optJSONObject("identity")
            val colorHex = identity?.optString("color")
            val color = parseColorOrFallback(colorHex)

            val message = ChatMessage(
                id = data.optString("id").ifBlank { UUID.randomUUID().toString() },
                platform = ChatPlatform.Kick,
                username = sender.optString("username"),
                usernameColor = color,
                text = data.optString("content"),
                timestampMs = System.currentTimeMillis(),
            )
            if (message.username.isNotBlank() && message.text.isNotBlank()) {
                _messages.tryEmit(message)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parse error", t)
        }
    }

    private fun parseColorOrFallback(hex: String?): Int {
        if (hex.isNullOrBlank()) return Color.WHITE
        return try {
            Color.parseColor(hex)
        } catch (_: IllegalArgumentException) {
            Color.WHITE
        }
    }
}
