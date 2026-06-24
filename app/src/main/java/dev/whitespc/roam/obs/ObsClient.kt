package dev.whitespc.roam.obs

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "RoamObs"

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
 * Why we wrote our own rather than pulling a library: the protocol is small
 * and well documented, we already have OkHttp + JSON from the chat sources,
 * and a focused ~250 line client beats a transitive dep on a generic Java
 * library that we'd need to wrap anyway. This handles the Hello/Identify
 * handshake (with SHA-256 challenge-response auth), event subscriptions, the
 * request/response correlation by id, and exposes the small handful of state
 * the HUD actually binds to (scene list, current scene, connection state).
 *
 * Connection is in-process and singleton: only one OBS pairing is supported,
 * which matches every other phone-side IRL app's posture and is enough for v1.
 */
object ObsClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private var configuredHost: String = ""
    private var configuredPort: Int = 4455
    private var configuredPassword: String = ""

    /** True between the user tapping Connect and tapping Disconnect (or hitting
     *  a fatal error). Used to drive auto-reconnect: if the socket drops while
     *  this is true, the reconnect loop wakes up. */
    private var userWantsConnected = false
    private var reconnectJob: Job? = null

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
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Begin a connection attempt. Cancels any in-flight session first. The
     *  state flow transitions Disconnected -> Connecting -> Connected or
     *  Error; the UI watches it. Password may be empty (OBS WebSocket
     *  servers can be configured without auth). */
    fun connect(host: String, port: Int, password: String) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) {
            _state.value = ObsConnectionState.Error("Host can't be empty")
            return
        }
        disconnectInternal(clearState = false)
        configuredHost = cleanHost
        configuredPort = port
        configuredPassword = password
        userWantsConnected = true
        openSocket()
    }

    /** Spin up a fresh ws connection using the last-configured host/port/pwd.
     *  Pulled out so the reconnect loop can call it without duplicating the
     *  build-and-set-state dance. */
    private fun openSocket() {
        _state.value = ObsConnectionState.Connecting
        val url = "ws://$configuredHost:$configuredPort/"
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t)
                _state.value = ObsConnectionState.Error(
                    t.message?.takeIf { it.isNotBlank() } ?: "Connection failed",
                )
                scheduleReconnectIfWanted()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws closed $code $reason")
                if (_state.value !is ObsConnectionState.Error) {
                    _state.value = ObsConnectionState.Disconnected
                }
                scheduleReconnectIfWanted()
            }
        })
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
                openSocket()
                // Wait for either a successful identify (state -> Connected)
                // or a fresh failure (which will reschedule us). Loop again
                // if state still says Connecting after a generous timeout.
                val outcome = withTimeoutOrNull(8_000L) {
                    state.first { it !is ObsConnectionState.Connecting }
                }
                if (outcome is ObsConnectionState.Connected) {
                    Log.d(TAG, "obs reconnected on attempt $attempt")
                    return@launch
                }
            }
        }
    }

    fun disconnect() {
        userWantsConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        disconnectInternal(clearState = true)
    }

    private fun disconnectInternal(clearState: Boolean) {
        runCatching { webSocket?.close(1000, "client disconnect") }
        webSocket = null
        pendingRequests.values.forEach { runCatching { it.complete(null) } }
        pendingRequests.clear()
        if (clearState) {
            _state.value = ObsConnectionState.Disconnected
            _scenes.value = emptyList()
            _currentScene.value = null
            _streaming.value = false
        }
    }

    /** Switch the program scene on the connected OBS. Returns true on success.
     *  No-op when disconnected. */
    suspend fun setCurrentScene(sceneName: String): Boolean {
        if (_state.value !is ObsConnectionState.Connected) return false
        val data = JSONObject().put("sceneName", sceneName)
        return request("SetCurrentProgramScene", data) != null
    }

    /** Tell OBS to start streaming to its configured destination. Returns true
     *  when OBS accepted the request. No-op when disconnected or already
     *  streaming (OBS will reject the second case; we read [streaming] first
     *  to skip the round-trip). */
    suspend fun startStream(): Boolean {
        if (_state.value !is ObsConnectionState.Connected) return false
        if (_streaming.value) return true
        return request("StartStream") != null
    }

    /** Tell OBS to stop streaming. Same shape as [startStream]. */
    suspend fun stopStream(): Boolean {
        if (_state.value !is ObsConnectionState.Connected) return false
        if (!_streaming.value) return true
        return request("StopStream") != null
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
        ws.send(payload.toString())
        return try {
            withTimeoutOrNull(5_000L) { deferred.await() }
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        runCatching {
            val msg = JSONObject(text)
            val op = msg.optInt("op", -1)
            val d = msg.optJSONObject("d") ?: JSONObject()
            when (op) {
                0 -> handleHello(ws, d)
                2 -> handleIdentified(d)
                5 -> handleEvent(d)
                7 -> handleResponse(d)
                else -> Log.d(TAG, "unhandled op=$op")
            }
        }.onFailure { Log.w(TAG, "ws parse failed: $text", it) }
    }

    private fun handleHello(ws: WebSocket, d: JSONObject) {
        val rpcVersion = d.optInt("rpcVersion", 1)
        val auth = d.optJSONObject("authentication")
        val identifyData = JSONObject().apply {
            put("rpcVersion", rpcVersion)
            // General(1) | Scenes(4) | Outputs(64) so we hear scene-change
            // events and stream-state-change events.
            put("eventSubscriptions", 1 or 4 or 64)
            if (auth != null) {
                if (configuredPassword.isEmpty()) {
                    _state.value = ObsConnectionState.Error("OBS requires a password")
                    return
                }
                val challenge = auth.getString("challenge")
                val salt = auth.getString("salt")
                put("authentication", computeAuth(configuredPassword, salt, challenge))
            }
        }
        val identifyMsg = JSONObject().apply {
            put("op", 1)
            put("d", identifyData)
        }
        ws.send(identifyMsg.toString())
    }

    private fun handleIdentified(d: JSONObject) {
        val rpcVersion = d.optInt("negotiatedRpcVersion", 1)
        _state.value = ObsConnectionState.Connected(rpcVersion)
        Log.d(TAG, "identified, rpcVersion=$rpcVersion")
        scope.launch {
            refreshSceneState()
            refreshStreamState()
        }
    }

    private suspend fun refreshStreamState() {
        val resp = request("GetStreamStatus") ?: return
        _streaming.value = resp.optBoolean("outputActive", false)
    }

    private fun handleEvent(d: JSONObject) {
        val eventType = d.optString("eventType")
        val eventData = d.optJSONObject("eventData") ?: JSONObject()
        when (eventType) {
            "CurrentProgramSceneChanged" -> {
                _currentScene.value = eventData.optString("sceneName").ifEmpty { null }
            }
            "SceneListChanged",
            "SceneCreated",
            "SceneRemoved",
            "SceneNameChanged" -> {
                scope.launch { refreshSceneState() }
            }
            "StreamStateChanged" -> {
                // outputActive=true for the started/streaming substates, false
                // for stopping/stopped. We don't differentiate the in-between
                // substates in the UI; the boolean is enough.
                val active = eventData.optBoolean("outputActive", false)
                _streaming.value = active
            }
        }
    }

    private fun handleResponse(d: JSONObject) {
        val requestId = d.optString("requestId")
        val status = d.optJSONObject("requestStatus")
        val responseData = d.optJSONObject("responseData")
        val deferred = pendingRequests.remove(requestId)
        val success = status?.optBoolean("result", false) == true
        deferred?.complete(if (success) (responseData ?: JSONObject()) else null)
    }

    private suspend fun refreshSceneState() {
        val list = request("GetSceneList") ?: return
        val arr = list.optJSONArray("scenes") ?: return
        val names = mutableListOf<String>()
        // OBS returns scenes in reverse-index order (top of OBS list is last);
        // reverse so the UI shows them in the OBS user-facing order.
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = item.optString("sceneName")
            if (name.isNotBlank()) names.add(name)
        }
        _scenes.value = names.reversed()
        _currentScene.value = list.optString("currentProgramSceneName").ifEmpty { null }
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
}
