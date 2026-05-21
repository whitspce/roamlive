package dev.whitespc.roam.chat

import android.graphics.Color
import android.util.Log
import dev.whitespc.roam.NetworkMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val TAG = "RoamTwitchChat"
private const val IRC_URL = "wss://irc-ws.chat.twitch.tv:443"

class TwitchChatSource(private val channelName: String) : ChatSource {

    override val platform = ChatPlatform.Twitch

    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 128)
    override val messages: SharedFlow<ChatMessage> = _messages.asSharedFlow()

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    override suspend fun connect() {
        val channel = channelName.lowercase().trim()
        if (channel.isBlank()) return
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt++
            Log.d(TAG, "connect attempt $attempt for #$channel")
            val closed = CompletableDeferred<Unit>()
            val ws = openSocket(channel, closed)
            webSocket = ws
            try {
                closed.await()
                Log.d(TAG, "ws closed, will retry")
            } finally {
                runCatching { ws.close(1000, "shutdown") }
                webSocket = null
            }
            if (!currentCoroutineContext().isActive) return
            // First wait until network is up — returns immediately if it already is.
            NetworkMonitor.isAvailable.filter { it }.first()
            if (!currentCoroutineContext().isActive) return
            // Then a brief backoff so we don't hammer if the failure is server-side
            // rather than network-side.
            delay(if (attempt <= 3) 1000L else 5000L)
        }
    }

    override suspend fun disconnect() {
        runCatching { webSocket?.close(1000, "client disconnect") }
        webSocket = null
    }

    private fun openSocket(channel: String, closed: CompletableDeferred<Unit>): WebSocket {
        val request = Request.Builder().url(IRC_URL).build()
        return httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val nick = "justinfan${Random.nextInt(10_000, 99_999)}"
                ws.send("CAP REQ :twitch.tv/tags")
                ws.send("PASS SCHMOOPIIE")
                ws.send("NICK $nick")
                ws.send("JOIN #$channel")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                text.split("\r\n").forEach { line ->
                    if (line.isNotBlank()) handleLine(ws, line)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t)
                closed.complete(Unit)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws closed $code $reason")
                closed.complete(Unit)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws closing $code $reason")
            }
        })
    }

    private fun handleLine(ws: WebSocket, line: String) {
        if (line.startsWith("PING")) {
            ws.send("PONG${line.substring(4)}")
            return
        }
        val tags = mutableMapOf<String, String>()
        var rest = line
        if (rest.startsWith("@")) {
            val space = rest.indexOf(' ')
            if (space < 0) return
            rest.substring(1, space).split(";").forEach { kv ->
                val eq = kv.indexOf('=')
                if (eq > 0) tags[kv.substring(0, eq)] = kv.substring(eq + 1)
            }
            rest = rest.substring(space + 1)
        }
        var prefix = ""
        if (rest.startsWith(":")) {
            val space = rest.indexOf(' ')
            if (space < 0) return
            prefix = rest.substring(1, space)
            rest = rest.substring(space + 1)
        }
        val cmdSpace = rest.indexOf(' ')
        if (cmdSpace < 0) return
        val command = rest.substring(0, cmdSpace)
        if (command != "PRIVMSG") return
        val afterCmd = rest.substring(cmdSpace + 1)
        val msgSep = afterCmd.indexOf(" :")
        if (msgSep < 0) return
        val messageText = afterCmd.substring(msgSep + 2)
        val nick = prefix.substringBefore('!')
        if (nick.isBlank() || messageText.isBlank()) return

        val displayName = tags["display-name"]?.takeIf { it.isNotBlank() } ?: nick
        val color = parseColorOrDefault(tags["color"])

        val msg = ChatMessage(
            id = tags["id"]?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            platform = ChatPlatform.Twitch,
            username = displayName,
            usernameColor = color,
            text = messageText,
            timestampMs = System.currentTimeMillis(),
        )
        _messages.tryEmit(msg)
    }

    private fun parseColorOrDefault(hex: String?): Int {
        if (hex.isNullOrBlank()) return Color.WHITE
        return try {
            Color.parseColor(hex)
        } catch (_: IllegalArgumentException) {
            Color.WHITE
        }
    }
}
