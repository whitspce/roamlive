package dev.whitespc.roam

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes whether the device has an active internet connection. Used by stream and
 * chat reconnect loops to wake up immediately when the network comes back, instead
 * of waiting for the next scheduled retry tick.
 *
 * Initialised once in [RoamApplication.onCreate]. The registered callback runs
 * for the lifetime of the process; no cleanup needed.
 */
object NetworkMonitor {
    private const val TAG = "RoamNetMonitor"

    // Track every active network with internet so isAvailable is true iff at least
    // one is up. Avoids false "lost" signals when wifi drops but cellular is still on.
    private val activeNetworks = mutableSetOf<Network>()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    /** Edge-triggered signal: emits once each time the network transitions to available.
     *  Buffer keeps a few late-arriving signals in case a coroutine wasn't actively
     *  collecting at the exact moment of the event. */
    private val _onAvailable = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val onAvailable: SharedFlow<Unit> = _onAvailable.asSharedFlow()

    /** The network Android currently routes new connections over. Distinct from
     *  [isAvailable]: on a WiFi-to-cellular handoff Android brings cellular up
     *  BEFORE WiFi drops (make-before-break), so isAvailable never blips, but any
     *  socket bound to the old WiFi network is dead or dying. The streaming engine
     *  watches this to restart the stream on the new network immediately instead
     *  of waiting out a TCP write timeout on the old one. */
    private val _defaultNetwork = MutableStateFlow<Network?>(null)
    val defaultNetwork: StateFlow<Network?> = _defaultNetwork.asStateFlow()

    fun init(context: Context) {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasEmpty: Boolean
                synchronized(activeNetworks) {
                    wasEmpty = activeNetworks.isEmpty()
                    activeNetworks.add(network)
                }
                Log.d(TAG, "network available ($network) — total: ${activeNetworks.size}")
                if (wasEmpty) {
                    _isAvailable.value = true
                    _onAvailable.tryEmit(Unit)
                }
            }
            override fun onLost(network: Network) {
                val nowEmpty: Boolean
                synchronized(activeNetworks) {
                    activeNetworks.remove(network)
                    nowEmpty = activeNetworks.isEmpty()
                }
                Log.d(TAG, "network lost ($network) — remaining: ${activeNetworks.size}")
                if (nowEmpty) {
                    _isAvailable.value = false
                }
            }
        })

        // Separate callback for the DEFAULT network (the one new sockets use).
        // Network.equals compares the kernel netId, so a WiFi-to-cell switch is a
        // value change here even while both networks briefly coexist.
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "default network now $network")
                _defaultNetwork.value = network
            }

            override fun onLost(network: Network) {
                if (_defaultNetwork.value == network) {
                    Log.d(TAG, "default network lost ($network)")
                    _defaultNetwork.value = null
                }
            }
        })
    }
}
