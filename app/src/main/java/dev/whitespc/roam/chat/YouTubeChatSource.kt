package dev.whitespc.roam.chat

import android.graphics.Color
import dev.whitespc.roam.diagnostics.RoamLog as Log
import dev.whitespc.roam.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "RoamYouTubeChat"

// Desktop UA on purpose: the desktop watch page carries the ytInitialData and
// ytcfg blobs this source parses; the mobile page does not.
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

// Pre-answered consent, or some regions get an interstitial instead of the
// watch page. Harmless where consent walls don't exist.
private const val CONSENT_COOKIE = "CONSENT=YES+cb; SOCS=CAI"

// How often to re-check a channel that isn't live yet. Each check is one
// gzipped page fetch, so chat appears within this window of going live.
private const val OFFLINE_POLL_MS = 30_000L

// Clamp the server-provided poll interval to avoid excessive requests and
// long delays. The default covers responses that omit the interval.
private const val MIN_POLL_MS = 500L
private const val MAX_POLL_MS = 5_000L
private const val DEFAULT_POLL_MS = 2_000L
private const val MAX_LIVE_PAGE_BYTES = 8 * 1024 * 1024
private const val MAX_CHAT_RESPONSE_BYTES = 2 * 1024 * 1024

// Used only if ytcfg extraction fails; the page's own value takes precedence.
private const val FALLBACK_CLIENT_VERSION = "2.20260630.03.00"

private val CLIENT_VERSION_REGEX = Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"")

// The live watch page embeds the chat continuation token in ytInitialData only
// while live chat is available. The initial token selects "Top chat"; the
// first poll provides the unfiltered continuation used afterward.
private val PAGE_CONTINUATION_REGEX = Regex(
    "\"liveChatRenderer\":\\{\"continuations\":\\[\\{\"reloadContinuationData\":" +
        "\\{\"continuation\":\"([^\"]+)\"",
)

/**
 * Reads public YouTube live chat by resolving the channel's `/live` page and
 * polling the continuation endpoint used by the website. It does not require
 * a login or API key.
 *
 * This relies on an unsupported web API that can change without notice.
 * Failures affect only the chat panel, which retries independently from the
 * broadcast.
 *
 * Unlike Twitch/Kick (persistent socket) this is a polling source, so there is
 * no idle watchdog: every poll is itself the liveness probe, and a wedged
 * request is bounded by the HTTP call timeout.
 */
class YouTubeChatSource(private val channelInput: String) : ChatSource {

    override val platform = ChatPlatform.YouTube

    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 128)
    override val messages: SharedFlow<ChatMessage> = _messages.asSharedFlow()

    // Plain HTTP polling (no websocket), so unlike the socket sources this
    // client needs real read/call timeouts or a dead connection would hang a
    // poll until the network stack gave up on its own.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var inFlight: Call? = null

    // Server ids already emitted. The first poll after a (re)connect replays
    // recent history; without this the replay would duplicate rows and collide
    // LazyColumn keys in the overlay. Only touched from the connector
    // coroutine, so no locking. Oldest entries fall off past the cap.
    private val seenIds = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
            size > 1000
    }

    override suspend fun connect() {
        val channel = normalizeYouTubeChannel(channelInput) ?: return
        Log.d(TAG, "connect() entered")
        var failureStreak = 0
        while (currentCoroutineContext().isActive) {
            when (val session = fetchSession(channel)) {
                is SessionResult.NotLive -> {
                    // Channel exists but has no live chat right now. Not a
                    // failure: steady cadence, no backoff growth.
                    failureStreak = 0
                    if (!currentCoroutineContext().isActive) return
                    delay(OFFLINE_POLL_MS)
                }
                is SessionResult.Error -> {
                    failureStreak++
                    Log.w(TAG, "live page fetch failed (attempt $failureStreak)")
                    if (!currentCoroutineContext().isActive) return
                    NetworkMonitor.isAvailable.filter { it }.first()
                    if (!currentCoroutineContext().isActive) return
                    withTimeoutOrNull(nextBackoffMs(failureStreak)) {
                        NetworkMonitor.onAvailable.first()
                    }
                }
                is SessionResult.Live -> {
                    Log.d(TAG, "live chat found, polling (attempt $failureStreak)")
                    val outcome = pollLoop(session)
                    if (!currentCoroutineContext().isActive) return
                    if (outcome.hadSuccess) failureStreak = 0
                    if (outcome.endedCleanly) {
                        // Broadcast (or its chat) ended; re-resolving the live
                        // page lands us in the steady not-live cadence above.
                        Log.d(TAG, "chat ended, re-resolving live page")
                    } else {
                        failureStreak++
                        NetworkMonitor.isAvailable.filter { it }.first()
                        if (!currentCoroutineContext().isActive) return
                        withTimeoutOrNull(nextBackoffMs(failureStreak)) {
                            NetworkMonitor.onAvailable.first()
                        }
                    }
                }
            }
        }
    }

    override suspend fun disconnect() {
        // Polling source: nothing persistent to close. Abort any in-flight
        // request so teardown is prompt.
        runCatching { inFlight?.cancel() }
        inFlight = null
    }

    /** Resolve the /live page into a pollable chat session. */
    private suspend fun fetchSession(channel: String): SessionResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(liveUrlFor(channel))
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", CONSENT_COOKIE)
                .build()
            val call = httpClient.newCall(request)
            inFlight = call
            call.execute().use { response ->
                if (response.code == 404) {
                    // Handle typo or channel gone. Definitive, so treat like
                    // not-live (steady cadence) rather than error backoff.
                    Log.w(TAG, "channel page returned 404")
                    return@withContext SessionResult.NotLive
                }
                if (!response.isSuccessful) {
                    Log.w(TAG, "live page fetch ${response.code}")
                    return@withContext SessionResult.Error
                }
                val html = response.body.byteStream()
                    .readUtf8Bounded(MAX_LIVE_PAGE_BYTES)
                    ?: return@withContext SessionResult.Error
                val continuation = PAGE_CONTINUATION_REGEX.find(html)?.groupValues?.get(1)
                    ?: return@withContext SessionResult.NotLive
                SessionResult.Live(
                    clientVersion = CLIENT_VERSION_REGEX.find(html)?.groupValues?.get(1)
                        ?: FALLBACK_CLIENT_VERSION,
                    continuation = continuation,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            Log.w(TAG, "live page fetch error", t)
            SessionResult.Error
        } finally {
            inFlight = null
        }
    }

    /** Poll until the chat ends (clean) or a poll fails (error). */
    private suspend fun pollLoop(session: SessionResult.Live): PollOutcome {
        var continuation = session.continuation
        var hadSuccess = false
        // The page token selects "Top chat". Switch to the complete chat feed
        // using the unfiltered continuation returned by the first poll.
        var unfilteredApplied = false
        while (currentCoroutineContext().isActive) {
            val page = pollOnce(session, continuation)
                ?: return PollOutcome(hadSuccess, endedCleanly = false)
            page.messages.forEach { msg ->
                // put() returns the previous value: null means first sighting.
                if (seenIds.put(msg.id, true) == null) _messages.tryEmit(msg)
            }
            hadSuccess = true
            if (!unfilteredApplied && page.unfilteredToken != null) {
                unfilteredApplied = true
                continuation = page.unfilteredToken
                // Switch promptly rather than sitting out a full poll delay.
                delay(MIN_POLL_MS)
                continue
            }
            continuation = page.nextContinuation
                ?: return PollOutcome(hadSuccess, endedCleanly = true)
            delay(page.timeoutMs.coerceIn(MIN_POLL_MS, MAX_POLL_MS))
        }
        return PollOutcome(hadSuccess, endedCleanly = true)
    }

    private suspend fun pollOnce(
        session: SessionResult.Live,
        continuation: String,
    ): ChatPage? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject()
                            .put("clientName", "WEB")
                            .put("clientVersion", session.clientVersion)
                            .put("hl", "en")
                            .put("gl", "US"),
                    ),
                )
                .put("continuation", continuation)
            // Anonymous requests do not include an API key. Compact responses
            // reduce bandwidth and parsing work.
            val url = "https://www.youtube.com/youtubei/v1/live_chat/get_live_chat" +
                "?prettyPrint=false"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Cookie", CONSENT_COOKIE)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = httpClient.newCall(request)
            inFlight = call
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "get_live_chat ${response.code}")
                    return@withContext null
                }
                val text = response.body.byteStream()
                    .readUtf8Bounded(MAX_CHAT_RESPONSE_BYTES)
                    ?: return@withContext null
                parseChatPage(JSONObject(text))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            Log.w(TAG, "get_live_chat error", t)
            null
        } finally {
            inFlight = null
        }
    }

    private fun parseChatPage(root: JSONObject): ChatPage {
        val liveChat = root.optJSONObject("continuationContents")
            ?.optJSONObject("liveChatContinuation")
        // No continuationContents at all means the chat is over.
            ?: return ChatPage(
                emptyList(),
                nextContinuation = null,
                timeoutMs = DEFAULT_POLL_MS,
                unfilteredToken = null,
            )

        // The response's view selector carries the full unfiltered "Live
        // chat" token (index 1; index 0 is the filtered "Top chat" default).
        // hl=en in our request context keeps the titles stable, but position
        // is the primary selector.
        val unfilteredToken = liveChat.optJSONObject("header")
            ?.optJSONObject("liveChatHeaderRenderer")
            ?.optJSONObject("viewSelector")
            ?.optJSONObject("sortFilterSubMenuRenderer")
            ?.optJSONArray("subMenuItems")
            ?.optJSONObject(1)
            ?.takeIf { it.optBoolean("selected", false).not() }
            ?.optJSONObject("continuation")
            ?.optJSONObject("reloadContinuationData")
            ?.optString("continuation")
            ?.takeIf { it.isNotBlank() }

        // The wrapper key varies (invalidationContinuationData,
        // timedContinuationData, reloadContinuationData); take whichever
        // carries a token, and its timeoutMs when present.
        var next: String? = null
        var timeoutMs = DEFAULT_POLL_MS
        val wrapper = liveChat.optJSONArray("continuations")?.optJSONObject(0)
        if (wrapper != null) {
            for (key in wrapper.keys()) {
                val data = wrapper.optJSONObject(key) ?: continue
                data.optString("continuation").takeIf { it.isNotBlank() }?.let { next = it }
                if (data.has("timeoutMs")) timeoutMs = data.optLong("timeoutMs", timeoutMs)
            }
        }

        val messages = mutableListOf<ChatMessage>()
        val actions = liveChat.optJSONArray("actions")
        if (actions != null) {
            for (i in 0 until actions.length()) {
                val item = actions.optJSONObject(i)
                    ?.optJSONObject("addChatItemAction")
                    ?.optJSONObject("item")
                    ?: continue // tickers, deletions, placeholders: skipped
                parseItem(item)?.let(messages::add)
            }
        }
        return ChatPage(messages, next, timeoutMs, unfilteredToken)
    }

    private fun parseItem(item: JSONObject): ChatMessage? {
        item.optJSONObject("liveChatTextMessageRenderer")?.let { r ->
            return buildMessage(r, runsToText(r.optJSONObject("message")?.optJSONArray("runs")))
        }
        item.optJSONObject("liveChatPaidMessageRenderer")?.let { r ->
            val amount = r.optJSONObject("purchaseAmountText")?.optString("simpleText").orEmpty()
            val msg = runsToText(r.optJSONObject("message")?.optJSONArray("runs"))
            val text = if (msg.isBlank()) amount else "$amount - $msg"
            return buildMessage(r, text, eventLabel = "SUPER CHAT")
        }
        return null // membership items, mod actions, everything else: skipped
    }

    private fun buildMessage(
        renderer: JSONObject,
        text: String,
        eventLabel: String? = null,
    ): ChatMessage? {
        val username = renderer.optJSONObject("authorName")?.optString("simpleText").orEmpty()
        if (username.isBlank() || text.isBlank()) return null
        return ChatMessage(
            id = renderer.optString("id").ifBlank { UUID.randomUUID().toString() },
            platform = ChatPlatform.YouTube,
            username = username,
            usernameColor = authorColor(renderer),
            text = text,
            timestampMs = System.currentTimeMillis(),
            eventLabel = eventLabel,
        )
    }

    /** Text runs verbatim; standard emoji as the character itself; channel
     *  emotes (images) as their :shortcut: label. */
    private fun runsToText(runs: JSONArray?): String {
        if (runs == null) return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val text = run.optString("text")
            if (text.isNotEmpty()) {
                sb.append(text)
                continue
            }
            val emoji = run.optJSONObject("emoji") ?: continue
            if (emoji.optBoolean("isCustomEmoji", false)) {
                sb.append(emoji.optJSONArray("shortcuts")?.optString(0).orEmpty())
            } else {
                sb.append(emoji.optString("emojiId"))
            }
        }
        return sb.toString().trim()
    }

    // Match YouTube's own name colours so roles read at a glance: owner gold,
    // moderator blue, member green, everyone else white.
    private fun authorColor(renderer: JSONObject): Int {
        val badges = renderer.optJSONArray("authorBadges") ?: return Color.WHITE
        var color = Color.WHITE
        for (i in 0 until badges.length()) {
            val badge = badges.optJSONObject(i)
                ?.optJSONObject("liveChatAuthorBadgeRenderer") ?: continue
            val iconType = badge.optJSONObject("icon")?.optString("iconType").orEmpty()
            when {
                iconType == "OWNER" -> return 0xFFFFD600.toInt()
                iconType == "MODERATOR" -> color = 0xFF5E84F1.toInt()
                badge.has("customThumbnail") && color == Color.WHITE ->
                    color = 0xFF2BA640.toInt()
            }
        }
        return color
    }

    /** The live chat continuation from the watch page's ytInitialData blob.
     *  Prefers the unfiltered "Live chat" view over the default "Top chat". */
    private fun extractContinuation(html: String): String? {
        val json = extractJsonBlob(html) ?: return null
        return try {
            val liveChat = JSONObject(json)
                .optJSONObject("contents")
                ?.optJSONObject("twoColumnWatchNextResults")
                ?.optJSONObject("conversationBar")
                ?.optJSONObject("liveChatRenderer")
                ?: return null
            // View order is stable: [0] = Top chat (filtered), [1] = Live chat
            // (everything). Titles are localized, position isn't.
            val unfiltered = liveChat.optJSONObject("header")
                ?.optJSONObject("liveChatHeaderRenderer")
                ?.optJSONObject("viewSelector")
                ?.optJSONObject("sortFilterSubMenuRenderer")
                ?.optJSONArray("subMenuItems")
                ?.optJSONObject(1)
                ?.optJSONObject("continuation")
                ?.optJSONObject("reloadContinuationData")
                ?.optString("continuation")
                ?.takeIf { it.isNotBlank() }
            unfiltered ?: liveChat.optJSONArray("continuations")
                ?.optJSONObject(0)
                ?.optJSONObject("reloadContinuationData")
                ?.optString("continuation")
                ?.takeIf { it.isNotBlank() }
        } catch (t: Exception) {
            Log.w(TAG, "continuation parse error", t)
            null
        }
    }

    private fun extractJsonBlob(html: String): String? {
        for (marker in listOf("var ytInitialData = ", "window[\"ytInitialData\"] = ")) {
            val start = html.indexOf(marker)
            if (start < 0) continue
            val jsonStart = start + marker.length
            val end = html.indexOf(";</script>", jsonStart)
            if (end > jsonStart) return html.substring(jsonStart, end)
        }
        return null
    }

    /** Accepts @handle, bare handle, UC... channel id, or a pasted URL. */
    private fun liveUrlFor(input: String): String {
        val trimmed = input.trim().removeSuffix("/")
        val path = when {
            trimmed.contains("youtube.com/") ->
                trimmed.substringAfter("youtube.com/")
                    .substringBefore("?")
                    .removeSuffix("/live")
            trimmed.startsWith("UC") && trimmed.length == 24 -> "channel/$trimmed"
            trimmed.startsWith("@") -> trimmed
            else -> "@$trimmed"
        }
        return "https://www.youtube.com/$path/live"
    }

    private data class ChatPage(
        val messages: List<ChatMessage>,
        val nextContinuation: String?,
        val timeoutMs: Long,
        val unfilteredToken: String?,
    )

    private data class PollOutcome(val hadSuccess: Boolean, val endedCleanly: Boolean)

    private sealed interface SessionResult {
        object NotLive : SessionResult
        object Error : SessionResult
        data class Live(
            val clientVersion: String,
            val continuation: String,
        ) : SessionResult
    }
}
