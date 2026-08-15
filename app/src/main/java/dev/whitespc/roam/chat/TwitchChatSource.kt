package dev.whitespc.roam.chat

import android.graphics.Color
import dev.whitespc.roam.diagnostics.RoamLog as Log
import dev.whitespc.roam.NetworkMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
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
private const val MAX_WEBSOCKET_MESSAGE_CHARS = 256 * 1024

// Application-level liveness. OkHttp's pingInterval only proves the pipe is
// alive; a socket can keep answering transport pings while the IRC session
// silently delivers nothing (the cold-start stale-socket failure). Twitch
// sends a server PING roughly every 5 minutes even on a dead-quiet channel,
// so 6 minutes of total inbound silence means the session is gone.
private const val IDLE_TIMEOUT_MS = 6 * 60 * 1000L
private const val WATCHDOG_POLL_MS = 30_000L

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

    @Volatile
    private var lastInboundMs = 0L

    override suspend fun connect() {
        val channel = normalizeTwitchChannel(channelName) ?: return
        var failureStreak = 0
        while (currentCoroutineContext().isActive) {
            failureStreak++
            Log.d(TAG, "connect attempt $failureStreak")
            val closed = CompletableDeferred<Unit>()
            val opened = CompletableDeferred<Unit>()
            val sessionStartMs = System.currentTimeMillis()
            val ws = openSocket(channel, closed, opened)
            webSocket = ws
            try {
                // Wait for the socket to die, watching inbound activity on the
                // way. If the session goes silent past the idle window, cancel
                // the socket; that trips onFailure, completes `closed`, and the
                // normal reconnect path takes over.
                while (withTimeoutOrNull(WATCHDOG_POLL_MS) { closed.await() } == null) {
                    if (System.currentTimeMillis() - lastInboundMs > IDLE_TIMEOUT_MS) {
                        Log.w(TAG, "no inbound for ${IDLE_TIMEOUT_MS / 1000}s, recycling stale socket")
                        runCatching { ws.cancel() }
                    }
                }
                Log.d(TAG, "ws closed, will retry")
            } finally {
                runCatching { ws.close(1000, "shutdown") }
                webSocket = null
            }
            if (!currentCoroutineContext().isActive) return
            // Reset the backoff only if the session actually HELD for a while.
            // A bare onOpen isn't enough: a middlebox that accepts the upgrade
            // then instantly drops it would reset the streak every round and
            // pin this loop at 1s retries forever.
            if (opened.isCompleted &&
                System.currentTimeMillis() - sessionStartMs > HEALTHY_SESSION_MS
            ) {
                failureStreak = 0
            }
            NetworkMonitor.isAvailable.filter { it }.first()
            if (!currentCoroutineContext().isActive) return
            // Back off repeated failures, but wake early after a new network
            // arrives so handoffs reconnect promptly.
            val backoffMs = nextBackoffMs(failureStreak)
            withTimeoutOrNull(backoffMs) { NetworkMonitor.onAvailable.first() }
        }
    }

    override suspend fun disconnect() {
        runCatching { webSocket?.close(1000, "client disconnect") }
        webSocket = null
    }

    private fun openSocket(
        channel: String,
        closed: CompletableDeferred<Unit>,
        opened: CompletableDeferred<Unit>,
    ): WebSocket {
        // Baseline the idle clock before the handshake so the watchdog can't
        // judge this fresh connection by the previous connection's silence.
        lastInboundMs = System.currentTimeMillis()
        val request = Request.Builder().url(IRC_URL).build()
        return httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastInboundMs = System.currentTimeMillis()
                opened.complete(Unit)
                val nick = "justinfan${Random.nextInt(10_000, 99_999)}"
                // tags: message metadata (colors, bits, redeems, first-msg).
                // commands: USERNOTICE, which carries subs/resubs/gifts/raids.
                // Both are available to anonymous connections.
                webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                webSocket.send("PASS SCHMOOPIIE")
                webSocket.send("NICK $nick")
                webSocket.send("JOIN #$channel")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundMs = System.currentTimeMillis()
                if (text.length > MAX_WEBSOCKET_MESSAGE_CHARS) {
                    Log.w(TAG, "oversized websocket message ignored")
                    return
                }
                text.split("\r\n").forEach { line ->
                    if (line.isNotBlank()) handleLine(webSocket, line)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t)
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
                if (eq > 0) tags[kv.substring(0, eq)] = unescapeTag(kv.substring(eq + 1))
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
        if (command == "USERNOTICE") {
            handleUserNotice(rest.substring(cmdSpace + 1), tags)
            return
        }
        if (command != "PRIVMSG") return
        val afterCmd = rest.substring(cmdSpace + 1)
        val msgSep = afterCmd.indexOf(" :")
        if (msgSep < 0) return
        var messageText = afterCmd.substring(msgSep + 2)
        // /me arrives CTCP-framed as {0x01}ACTION <text>{0x01}; unwrap it or
        // the row renders control-char tofu plus a literal "ACTION" prefix.
        if (messageText.startsWith("\u0001ACTION ") && messageText.endsWith("\u0001")) {
            messageText = messageText.substring(8, messageText.length - 1)
        }
        val nick = prefix.substringBefore('!')
        if (nick.isBlank() || messageText.isBlank()) return

        val displayName = tags["display-name"]?.takeIf { it.isNotBlank() } ?: nick
        val color = parseColorOrDefault(tags["color"])

        // Money/milestone markers that ride on ordinary messages. Bits and
        // text-bearing channel point redeems only ever appear here; redeems
        // without a message never reach the anonymous feed (they need an
        // authenticated EventSub connection), same for follows.
        val bits = tags["bits"]?.toIntOrNull()
        val eventLabel = when {
            bits != null && bits > 0 -> "BITS x$bits"
            !tags["custom-reward-id"].isNullOrBlank() -> "REDEEM"
            tags["msg-id"] == "highlighted-message" -> "HIGHLIGHT"
            tags["first-msg"] == "1" -> "FIRST"
            else -> null
        }

        if (eventLabel != null) Log.d(TAG, "chat event: $eventLabel")
        val msg = ChatMessage(
            id = tags["id"]?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            platform = ChatPlatform.Twitch,
            username = displayName,
            usernameColor = color,
            text = messageText,
            timestampMs = System.currentTimeMillis(),
            eventLabel = eventLabel,
        )
        _messages.tryEmit(msg)
    }

    /** Subs, resubs, gift subs, raids, announcements. The human-readable
     *  summary arrives in the system-msg tag ("Bob subscribed at Tier 1.");
     *  an optional trailing ":message" is the user's own resub text. */
    private fun handleUserNotice(afterCmd: String, tags: Map<String, String>) {
        val systemMsg = tags["system-msg"]?.takeIf { it.isNotBlank() } ?: return
        val displayName = tags["display-name"]?.takeIf { it.isNotBlank() }
            ?: tags["login"]?.takeIf { it.isNotBlank() } ?: ""
        val userText = afterCmd.indexOf(" :")
            .takeIf { it >= 0 }?.let { afterCmd.substring(it + 2) }.orEmpty()
        val label = when (tags["msg-id"]) {
            "sub", "resub" -> "SUB"
            "subgift", "anonsubgift", "submysterygift", "giftpaidupgrade",
            "anongiftpaidupgrade" -> "GIFT"
            "raid" -> "RAID"
            "announcement" -> "NOTICE"
            "viewermilestone" -> "MILESTONE"
            else -> "EVENT"
        }
        // system-msg usually opens with the display name; strip it so the
        // row doesn't read "Bob: Bob subscribed...".
        val body = systemMsg
            .removePrefix(displayName).trim()
            .let { if (userText.isBlank()) it else "$it - $userText" }
            .ifBlank { systemMsg }
        Log.d(TAG, "chat event: $label (${tags["msg-id"]})")
        val msg = ChatMessage(
            id = tags["id"]?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            platform = ChatPlatform.Twitch,
            username = displayName.ifBlank { "Twitch" },
            usernameColor = parseColorOrDefault(tags["color"]),
            text = body,
            timestampMs = System.currentTimeMillis(),
            eventLabel = label,
        )
        _messages.tryEmit(msg)
    }

    /** IRCv3 tag value unescaping: \s space, \\ backslash, \: semicolon,
     *  \r \n line breaks. Without this, multi-word tag values like
     *  system-msg render with literal backslash-s between words. */
    private fun unescapeTag(v: String): String {
        if ('\\' !in v) return v
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (v[i + 1]) {
                    's' -> sb.append(' ')
                    '\\' -> sb.append('\\')
                    ':' -> sb.append(';')
                    'r' -> sb.append('\r')
                    'n' -> sb.append('\n')
                    else -> sb.append(v[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
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
