package dev.whitespc.roam.obs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.whitespc.roam.diagnostics.RoamLog as Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "RoamObs"
private const val MAX_MESSAGE_CHARS = 4 * 1024 * 1024
private const val MAX_SCENES = 256
private const val MAX_SCENE_NAME_CHARS = 256
private const val MAX_SCREENSHOT_BASE64_CHARS = 2 * 1024 * 1024
private const val MAX_SCREENSHOT_PIXELS = 2_000_000L

/** OBS connection lifecycle. UI binds to [ObsClient.state] and renders the
 *  matching status pill / error message. */
sealed class ObsConnectionState {
    object Disconnected : ObsConnectionState()
    object Connecting : ObsConnectionState()
    data class Connected(val rpcVersion: Int) : ObsConnectionState()
    data class Error(val message: String) : ObsConnectionState()
}

/**
 * obs-websocket v5 client (https://github.com/obsproject/obs-websocket).
 *
 * Handles the authenticated Hello/Identify handshake, event subscriptions,
 * request correlation, scene state, and output state. One OBS pairing is
 * supported at a time.
 */
object ObsClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var webSocket: WebSocket? = null
    private var configuredUrl: String = ""
    private var configuredPassword: String = ""

    /** True between the user tapping Connect and tapping Disconnect (or hitting
     *  a fatal error). Used to drive auto-reconnect: if the socket drops while
     *  this is true, the reconnect loop wakes up. */
    @Volatile
    private var userWantsConnected = false
    private var reconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    @Volatile
    private var connectionAttemptOutcome: CompletableDeferred<Boolean>? = null

    private val _state = MutableStateFlow<ObsConnectionState>(ObsConnectionState.Disconnected)
    val state: StateFlow<ObsConnectionState> = _state.asStateFlow()

    private val _scenes = MutableStateFlow<List<String>>(emptyList())
    val scenes: StateFlow<List<String>> = _scenes.asStateFlow()

    private val _currentScene = MutableStateFlow<String?>(null)
    val currentScene: StateFlow<String?> = _currentScene.asStateFlow()

    /** True when the paired OBS is currently streaming to its configured
     *  Twitch/Kick/etc destination. Updates from the StreamStateChanged event
     *  so the UI doesn't have to poll. Reset on disconnect. */
    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject?>>()

    private val httpClient = OkHttpClient.Builder()
        .dns(PrivateNetworkDns())
        .proxy(Proxy.NO_PROXY)
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val outputController = ObsOutputController(
        scope = scope,
        isConnected = { _state.value is ObsConnectionState.Connected },
        readRemoteActive = { queryStreamActive() },
        startRemote = { request("StartStream") != null },
        stopRemote = { request("StopStream") != null },
    )

    /** Begin a connection attempt. Cancels any in-flight session first. The
     *  state flow transitions Disconnected -> Connecting -> Connected or
     *  Error; the UI watches it. Authentication is required. */
    @Synchronized
    fun connect(host: String, port: Int, password: String) {
        val endpoint = when (val result = validateObsConnection(host, port, password)) {
            is ObsEndpointResult.Valid -> result.webSocketUrl
            is ObsEndpointResult.Invalid -> {
                userWantsConnected = false
                reconnectJob?.cancel()
                reconnectJob = null
                disconnectInternal(clearState = false)
                configuredUrl = ""
                configuredPassword = ""
                clearRemoteState()
                _state.value = ObsConnectionState.Error(result.message)
                return
            }
        }
        // Kill any backoff loop from a previous session too, or its next
        // iteration would open a second socket alongside the one below.
        reconnectJob?.cancel()
        reconnectJob = null
        disconnectInternal(clearState = false)
        configuredUrl = endpoint
        configuredPassword = password
        userWantsConnected = true
        openSocket()
    }

    /** Socket generation used to ignore callbacks from replaced connections. */
    @Volatile
    private var socketGeneration = 0

    @Synchronized
    private fun openSocket(): CompletableDeferred<Boolean>? {
        if (!userWantsConnected || configuredUrl.isBlank()) return null
        val gen = ++socketGeneration
        connectionAttemptOutcome?.complete(false)
        val attemptOutcome = CompletableDeferred<Boolean>()
        connectionAttemptOutcome = attemptOutcome
        // A fresh socket replaces any existing one; cancel the old first so
        // it can't linger connected to OBS.
        runCatching { webSocket?.cancel() }
        webSocket = null
        completePendingRequests()
        clearRemoteState()
        _state.value = ObsConnectionState.Connecting
        val request = runCatching { Request.Builder().url(configuredUrl).build() }
            .getOrElse {
                markHardFailure(gen, "OBS address is not valid")
                return null
            }
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            private fun stale() = gen != socketGeneration

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stale()) return
                if (text.length > MAX_MESSAGE_CHARS) {
                    Log.w(TAG, "oversized OBS message rejected")
                    failAndCancelAttempt(gen, "OBS sent an oversized response")
                    return
                }
                handleMessage(webSocket, text, gen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stale()) return
                Log.w(TAG, "ws failure", t)
                if (t.hasPrivateNetworkRejection()) {
                    markHardFailure(gen, OBS_PRIVATE_NETWORK_ERROR)
                } else {
                    markTransientDisconnect(gen, "Connection failed")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (stale()) return
                Log.d(TAG, "ws closing $code")
                // obs-websocket closes with 4009 when the auth challenge
                // fails. Hard failure: retrying the same password would
                // hammer OBS forever with the pill stuck amber, and the
                // close surfaces later as a generic EOF that hides the
                // actual cause.
                if (code == 4009) {
                    markHardFailure(
                        gen,
                        "OBS authentication failed - check the password",
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (stale()) return
                Log.d(TAG, "ws closed $code")
                if (userWantsConnected) {
                    markTransientDisconnect(gen, "OBS connection closed")
                } else {
                    clearSocketState(gen)
                    if (_state.value !is ObsConnectionState.Error) {
                        _state.value = ObsConnectionState.Disconnected
                    }
                }
            }
        })
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(15_000L)
            if (gen == socketGeneration && _state.value is ObsConnectionState.Connecting) {
                Log.w(TAG, "OBS handshake/state sync timed out")
                failAndCancelAttempt(gen, "OBS connection timed out")
            }
        }
        return attemptOutcome
    }

    /** If the user still wants OBS connected (i.e. they tapped Connect and
     *  haven't tapped Disconnect), keep retrying with a soft backoff. The OBS
     *  pill flashes red briefly during outages and goes back to green when
     *  the server comes back. Without this, a single router hiccup or OBS
     *  restart forces the user back into Settings to tap Connect again. */
    private fun scheduleReconnectIfWanted() {
        if (!userWantsConnected) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (userWantsConnected) {
                attempt++
                val backoffMs = (1_000L * (1L shl (attempt - 1).coerceAtMost(5)))
                    .coerceAtMost(30_000L)
                delay(backoffMs)
                if (!userWantsConnected) return@launch
                Log.d(TAG, "obs reconnect attempt $attempt")
                val outcome = openSocket() ?: return@launch
                // The visible state deliberately stays Connecting throughout
                // transient outages. Wait on this socket's own result so a
                // fast refusal retries promptly instead of idling for 16 s.
                val connected = withTimeoutOrNull(16_000L) { outcome.await() } == true
                if (connected) {
                    Log.d(TAG, "obs reconnected on attempt $attempt")
                    return@launch
                }
            }
        }
    }

    @Synchronized
    fun disconnect() {
        userWantsConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        val disconnectGeneration = socketGeneration
        outputController.setRoamDesired(false)
        scope.launch {
            // If Roam started OBS, release that output before dropping control.
            outputController.reconcile()
            // A new Connect tap may have replaced this session while the OBS
            // stop request was in flight. Never let the old teardown close it.
            finishDisconnectIfCurrent(disconnectGeneration)
        }
    }

    @Synchronized
    private fun finishDisconnectIfCurrent(gen: Int) {
        if (userWantsConnected || socketGeneration != gen) return
        disconnectInternal(clearState = true)
        configuredUrl = ""
        configuredPassword = ""
    }

    @Synchronized
    private fun disconnectInternal(clearState: Boolean) {
        // Stale-out the old socket's listener before closing it, so its
        // onClosed can't overwrite state written after this point.
        socketGeneration++
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        connectionAttemptOutcome?.complete(false)
        connectionAttemptOutcome = null
        runCatching { webSocket?.close(1000, "client disconnect") }
        webSocket = null
        completePendingRequests()
        if (clearState) {
            _state.value = ObsConnectionState.Disconnected
            clearRemoteState()
        }
    }

    @Synchronized
    private fun markTransientDisconnect(gen: Int, reason: String) {
        if (gen != socketGeneration) return
        Log.d(TAG, "OBS disconnected; retrying: $reason")
        clearSocketState(gen)
        if (userWantsConnected) {
            _state.value = ObsConnectionState.Connecting
            scheduleReconnectIfWanted()
        } else {
            _state.value = ObsConnectionState.Disconnected
        }
    }

    @Synchronized
    private fun markHardFailure(gen: Int, message: String) {
        if (gen != socketGeneration) return
        userWantsConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        clearSocketState(gen)
        configuredUrl = ""
        configuredPassword = ""
        _state.value = ObsConnectionState.Error(message)
    }

    @Synchronized
    private fun clearSocketState(gen: Int) {
        if (gen != socketGeneration) return
        // Stale-out every remaining callback from the abandoned socket before
        // a replacement is opened.
        socketGeneration++
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        connectionAttemptOutcome?.complete(false)
        connectionAttemptOutcome = null
        webSocket = null
        completePendingRequests()
        clearRemoteState()
    }

    private fun completePendingRequests() {
        pendingRequests.values.forEach { runCatching { it.complete(null) } }
        pendingRequests.clear()
    }

    private fun clearRemoteState() {
        _scenes.value = emptyList()
        _currentScene.value = null
        _streaming.value = false
    }

    /** Switch the program scene on the connected OBS. Returns true on success.
     *  No-op when disconnected. */
    suspend fun setCurrentScene(sceneName: String): Boolean {
        if (_state.value !is ObsConnectionState.Connected) return false
        val safeName = safeSceneName(sceneName)?.takeIf { it == sceneName } ?: return false
        val data = JSONObject().put("sceneName", safeName)
        return request("SetCurrentProgramScene", data) != null
    }

    /** Tell OBS to start streaming to its configured destination. Returns true
     *  when OBS accepted the request. No-op when disconnected or already
     *  streaming (OBS will reject the second case; we read [streaming] first
     *  to skip the round-trip). */
    suspend fun startStream(): Boolean {
        return outputController.setManual(true)
    }

    /** Tell OBS to stop streaming. Same shape as [startStream]. */
    suspend fun stopStream(): Boolean {
        return outputController.setManual(false)
    }

    /**
     * State-derived output intent for Roam's optional OBS lifecycle sync. This
     * call is intentionally non-suspending: the newest intent is recorded
     * before any queued command can run, then reconciled serially.
     */
    fun setRoamStreamingWanted(active: Boolean) {
        outputController.setRoamDesired(active)
    }

    /** A snapshot of what OBS is outputting right now (the program scene),
     *  via GetSourceScreenshot. Small jpg on purpose: it refreshes every few
     *  seconds while the scene drawer is open, and its job is "what do
     *  viewers see", not pixel quality. Null when disconnected or on any
     *  failure; callers keep the last frame or show a placeholder. */
    suspend fun getProgramScreenshot(): Bitmap? {
        if (_state.value !is ObsConnectionState.Connected) return null
        val scene = _currentScene.value ?: return null
        val data = JSONObject()
            .put("sourceName", scene)
            .put("imageFormat", "jpg")
            .put("imageWidth", 480)
        val resp = request("GetSourceScreenshot", data) ?: return null
        // imageData arrives as a data URI: "data:image/jpg;base64,<payload>".
        val base64 = resp.optString("imageData").substringAfter("base64,", "")
        if (base64.isEmpty() || base64.length > MAX_SCREENSHOT_BASE64_CHARS) return null
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || pixels > MAX_SCREENSHOT_PIXELS) {
                return@runCatching null
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private suspend fun request(type: String, data: JSONObject? = null): JSONObject? {
        val ws = webSocket ?: return null
        val requestId = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("op", 6)
            put(
                "d",
                JSONObject().apply {
                    put("requestType", type)
                    put("requestId", requestId)
                    if (data != null) put("requestData", data)
                },
            )
        }
        val deferred = CompletableDeferred<JSONObject?>()
        pendingRequests[requestId] = deferred
        if (!ws.send(payload.toString())) {
            pendingRequests.remove(requestId)
            return null
        }
        return try {
            withTimeoutOrNull(5_000L) { deferred.await() }
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    private fun handleMessage(ws: WebSocket, text: String, gen: Int) {
        runCatching {
            val msg = JSONObject(text)
            val op = msg.optInt("op", -1)
            val d = msg.optJSONObject("d") ?: JSONObject()
            when (op) {
                0 -> handleHello(ws, d, gen)
                2 -> handleIdentified(d, gen)
                5 -> handleEvent(d, gen)
                7 -> handleResponse(d, gen)
                else -> Log.d(TAG, "unhandled op=$op")
            }
        }.onFailure { Log.w(TAG, "ws parse failed", it) }
    }

    private fun handleHello(ws: WebSocket, d: JSONObject, gen: Int) {
        val rpcVersion = d.optInt("rpcVersion", 1)
        val auth = d.optJSONObject("authentication")
        obsServerAuthenticationError(auth != null)?.let { message ->
            markHardFailure(gen, message)
            runCatching { ws.close(4000, "authentication required") }
            return
        }
        val identifyData = JSONObject().apply {
            put("rpcVersion", rpcVersion)
            // General(1) | Scenes(4) | Outputs(64) so we hear scene-change
            // events and stream-state-change events.
            put("eventSubscriptions", 1 or 4 or 64)
            if (configuredPassword.isEmpty()) {
                // Defensive fallback; blank passwords are rejected before the
                // socket opens, so this only protects against state corruption.
                markHardFailure(gen, "OBS requires a password")
                runCatching { ws.close(4000, "password required") }
                return
            }
            val challenge = auth!!.getString("challenge")
            val salt = auth.getString("salt")
            put("authentication", computeAuth(configuredPassword, salt, challenge))
        }
        val identifyMsg = JSONObject().apply {
            put("op", 1)
            put("d", identifyData)
        }
        if (!ws.send(identifyMsg.toString())) {
            failAndCancelAttempt(gen, "Could not identify with OBS")
        }
    }

    private fun handleIdentified(d: JSONObject, gen: Int) {
        val rpcVersion = d.optInt("negotiatedRpcVersion", 1)
        scope.launch {
            val scenesReady = refreshSceneState(gen)
            val streamReady = refreshStreamState(gen)
            if (gen != socketGeneration) return@launch
            if (!scenesReady || !streamReady) {
                failAndCancelAttempt(gen, "Could not read OBS state")
                return@launch
            }
            if (markConnected(gen, rpcVersion)) {
                Log.d(TAG, "identified and synced, rpcVersion=$rpcVersion")
                outputController.onConnectionReady()
            }
        }
    }

    private suspend fun queryStreamActive(): Boolean? {
        val resp = request("GetStreamStatus") ?: return null
        return resp.optBoolean("outputActive", false)
    }

    private suspend fun refreshStreamState(gen: Int): Boolean {
        val active = queryStreamActive() ?: return false
        if (gen != socketGeneration) return false
        _streaming.value = active
        return true
    }

    private fun handleEvent(d: JSONObject, gen: Int) {
        if (gen != socketGeneration) return
        val eventType = d.optString("eventType")
        val eventData = d.optJSONObject("eventData") ?: JSONObject()
        when (eventType) {
            "CurrentProgramSceneChanged" -> {
                _currentScene.value = safeSceneName(eventData.optString("sceneName"))
            }
            "SceneListChanged",
            "SceneCreated",
            "SceneRemoved",
            "SceneNameChanged" -> {
                scope.launch { refreshSceneState(gen) }
            }
            "StreamStateChanged" -> {
                // outputActive=true for the started/streaming substates, false
                // for stopping/stopped. We don't differentiate the in-between
                // substates in the UI; the boolean is enough.
                val active = eventData.optBoolean("outputActive", false)
                _streaming.value = active
                outputController.onRemoteStateChanged()
            }
        }
    }

    private fun handleResponse(d: JSONObject, gen: Int) {
        if (gen != socketGeneration) return
        val requestId = d.optString("requestId")
        val status = d.optJSONObject("requestStatus")
        val responseData = d.optJSONObject("responseData")
        val deferred = pendingRequests.remove(requestId)
        val success = status?.optBoolean("result", false) == true
        deferred?.complete(if (success) (responseData ?: JSONObject()) else null)
    }

    private suspend fun refreshSceneState(gen: Int = socketGeneration): Boolean {
        val list = request("GetSceneList") ?: return false
        val arr = list.optJSONArray("scenes") ?: return false
        val names = mutableListOf<String>()
        // OBS returns scenes in reverse-index order (top of OBS list is last);
        // reverse so the UI shows them in the OBS user-facing order.
        for (i in 0 until arr.length()) {
            if (names.size >= MAX_SCENES) break
            val item = arr.optJSONObject(i) ?: continue
            safeSceneName(item.optString("sceneName"))?.let(names::add)
        }
        if (gen != socketGeneration) return false
        _scenes.value = names.reversed()
        _currentScene.value = safeSceneName(list.optString("currentProgramSceneName"))
        return true
    }

    private fun safeSceneName(value: String): String? = value.takeIf {
        it.isNotBlank() && it.length <= MAX_SCENE_NAME_CHARS &&
            it.none(Char::isISOControl)
    }

    private fun computeAuth(password: String, salt: String, challenge: String): String {
        val secret = sha256Base64(password + salt)
        return sha256Base64(secret + challenge)
    }

    private fun sha256Base64(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(input.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    @Synchronized
    private fun markConnected(gen: Int, rpcVersion: Int): Boolean {
        if (gen != socketGeneration || !userWantsConnected) return false
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        _state.value = ObsConnectionState.Connected(rpcVersion)
        connectionAttemptOutcome?.complete(true)
        return true
    }

    @Synchronized
    private fun failAndCancelAttempt(gen: Int, reason: String) {
        if (gen != socketGeneration) return
        val failedSocket = webSocket
        markTransientDisconnect(gen, reason)
        runCatching { failedSocket?.cancel() }
    }
}

private fun Throwable.hasPrivateNetworkRejection(): Boolean {
    var current: Throwable? = this
    repeat(8) {
        if (current is ObsPrivateNetworkRequiredException) return true
        current = current?.cause
    }
    return false
}
