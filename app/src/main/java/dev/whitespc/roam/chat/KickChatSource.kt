package dev.whitespc.roam.chat

import android.graphics.Color
import dev.whitespc.roam.diagnostics.RoamLog as Log
import dev.whitespc.roam.NetworkMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
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
private val KICK_CHANNEL_API = "https://kick.com/api/v2/channels".toHttpUrl()
private const val MAX_CHANNEL_RESPONSE_BYTES = 256 * 1024
private const val MAX_WEBSOCKET_MESSAGE_CHARS = 512 * 1024

// OkHttp pings prove transport liveness, not subscription liveness. After an
// idle period, send a Pusher ping and recycle the session if no pong arrives.
private const val PING_AFTER_IDLE_MS = 120_000L
private const val IDLE_TIMEOUT_MS = 180_000L
private const val WATCHDOG_POLL_MS = 30_000L

class KickChatSource(private val channelName: String) : ChatSource {

    override val platform = ChatPlatform.Kick

    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 128)
    override val messages: SharedFlow<ChatMessage> = _messages.asSharedFlow()

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // The ws client's infinite readTimeout is right for the socket but wrong
    // for the one-shot chatroom lookup: a blackholed GET (silent cellular
    // handoff mid-request) would wedge connect() forever, since the idle
    // watchdog only guards the ws phase. Shares the pool, adds real limits.
    private val lookupClient = httpClient.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    @Volatile
    private var lastInboundMs = 0L

    override suspend fun connect() {
        val channel = normalizeKickChannel(channelName) ?: return
        Log.d(TAG, "connect() entered")
        var failureStreak = 0
        while (currentCoroutineContext().isActive) {
            failureStreak++
            val chatroomId = fetchChatroomId(channel)
            if (chatroomId == null) {
                Log.w(TAG, "chatroom fetch failed (attempt $failureStreak)")
                if (!currentCoroutineContext().isActive) return
                NetworkMonitor.isAvailable.filter { it }.first()
                if (!currentCoroutineContext().isActive) return
                val backoffMs = nextBackoffMs(failureStreak)
                withTimeoutOrNull(backoffMs) { NetworkMonitor.onAvailable.first() }
                continue
            }
            Log.d(TAG, "connecting ws for chatroom $chatroomId (attempt $failureStreak)")
            val closed = CompletableDeferred<Unit>()
            val opened = CompletableDeferred<Unit>()
            val sessionStartMs = System.currentTimeMillis()
            val ws = openWebSocket(chatroomId, closed, opened)
            webSocket = ws
            try {
                // Wait for the socket to die, watching inbound activity on the
                // way. Past the activity window we heartbeat (client-initiated
                // per the Pusher protocol); if not even the pong arrives by the
                // idle timeout, cancel the socket. That trips onFailure,
                // completes `closed`, and the normal reconnect path takes over.
                while (withTimeoutOrNull(WATCHDOG_POLL_MS) { closed.await() } == null) {
                    val idleMs = System.currentTimeMillis() - lastInboundMs
                    if (idleMs > IDLE_TIMEOUT_MS) {
                        Log.w(TAG, "no inbound for ${idleMs / 1000}s despite ping, recycling stale socket")
                        runCatching { ws.cancel() }
                    } else if (idleMs > PING_AFTER_IDLE_MS) {
                        Log.d(TAG, "idle ${idleMs / 1000}s, sending pusher:ping")
                        runCatching { ws.send("""{"event":"pusher:ping","data":"{}"}""") }
                    }
                }
                Log.d(TAG, "ws closed, will retry")
            } finally {
                runCatching { ws.close(1000, "shutdown") }
                webSocket = null
            }
            if (!currentCoroutineContext().isActive) return
            // Reset backoff only if the session actually HELD for a while. A
            // bare onOpen isn't enough: a server that accepts the socket then
            // instantly drops it (middlebox, cluster rejecting the app key)
            // would reset the streak every round and pin this loop at 1s.
            if (opened.isCompleted &&
                System.currentTimeMillis() - sessionStartMs > HEALTHY_SESSION_MS
            ) {
                failureStreak = 0
            }
            NetworkMonitor.isAvailable.filter { it }.first()
            if (!currentCoroutineContext().isActive) return
            val backoffMs = nextBackoffMs(failureStreak)
            withTimeoutOrNull(backoffMs) { NetworkMonitor.onAvailable.first() }
        }
    }

    override suspend fun disconnect() {
        runCatching { webSocket?.close(1000, "client disconnect") }
        webSocket = null
    }

    private suspend fun fetchChatroomId(username: String): Long? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(KICK_CHANNEL_API.newBuilder().addPathSegment(username).build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            lookupClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "channel fetch failed ${response.code}")
                    return@withContext null
                }
                val body = response.body.byteStream()
                    .readUtf8Bounded(MAX_CHANNEL_RESPONSE_BYTES)
                    ?: return@withContext null
                val root = JSONObject(body)
                root.optJSONObject("chatroom")?.optLong("id")?.takeIf { it > 0L }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            Log.w(TAG, "channel fetch error", t)
            null
        }
    }

    private fun openWebSocket(
        chatroomId: Long,
        closed: CompletableDeferred<Unit>,
        opened: CompletableDeferred<Unit>,
    ): WebSocket {
        // Baseline the idle clock before the handshake so the watchdog can't
        // judge this fresh connection by the previous connection's silence.
        lastInboundMs = System.currentTimeMillis()
        val request = Request.Builder()
            .url(PUSHER_URL)
            .header("User-Agent", USER_AGENT)
            .build()
        return httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "ws onOpen (${response.code})")
                lastInboundMs = System.currentTimeMillis()
                opened.complete(Unit)
                webSocket.send(
                    """{"event":"pusher:subscribe","data":{"channel":"chatrooms.$chatroomId.v2"}}""",
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundMs = System.currentTimeMillis()
                if (text.length > MAX_WEBSOCKET_MESSAGE_CHARS) {
                    Log.w(TAG, "oversized websocket message ignored")
                    return
                }
                // Answer server pings and consume pongs from the client heartbeat.
                if (text.contains("\"pusher:ping\"")) {
                    Log.d(TAG, "pusher:ping -> pong")
                    webSocket.send("""{"event":"pusher:pong","data":"{}"}""")
                    return
                }
                if (text.contains("\"pusher:pong\"")) {
                    Log.d(TAG, "pusher:pong received")
                    return
                }
                parseAndEmit(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure (response code ${response?.code})", t)
                closed.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws closed $code $reason")
                closed.complete(Unit)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws closing $code $reason")
            }
        })
    }

    private fun parseAndEmit(text: String) {
        try {
            val outer = JSONObject(text)
            when (outer.optString("event")) {
                // Subscription events share the chatroom channel. Skip malformed
                // payloads without interrupting ordinary chat.
                "App\\Events\\SubscriptionEvent" -> {
                    val data = JSONObject(outer.getString("data"))
                    val who = data.optString("username")
                    if (who.isNotBlank()) {
                        val months = data.optInt("months", 0)
                        emitEvent(
                            username = who,
                            text = if (months > 1) "subscribed ($months months)" else "subscribed",
                            label = "SUB",
                        )
                    }
                    return
                }
                "App\\Events\\GiftedSubscriptionsEvent" -> {
                    val data = JSONObject(outer.getString("data"))
                    val gifter = data.optString("gifter_username")
                    val count = data.optJSONArray("gifted_usernames")?.length() ?: 0
                    if (gifter.isNotBlank() && count > 0) {
                        val subs = if (count == 1) "a sub" else "$count subs"
                        emitEvent(gifter, "gifted $subs", "GIFT")
                    }
                    return
                }
                EVENT_CHAT_MESSAGE -> Unit
                else -> return
            }
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
        } catch (t: Exception) {
            Log.w(TAG, "parse error", t)
        }
    }

    private fun emitEvent(username: String, text: String, label: String) {
        _messages.tryEmit(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                platform = ChatPlatform.Kick,
                username = username,
                usernameColor = Color.WHITE,
                text = text,
                timestampMs = System.currentTimeMillis(),
                eventLabel = label,
            ),
        )
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
