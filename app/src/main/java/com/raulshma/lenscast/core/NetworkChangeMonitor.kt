package com.raulshma.lenscast.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkChangeMonitor(private val context: Context) {

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionType = MutableStateFlow(ConnectionType.UNKNOWN)
    val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var legacyReceiver: BroadcastReceiver? = null
    private var isRegistered = false

    init {
        refreshNetworkState()
    }

    fun startMonitoring() {
        if (isRegistered) return

        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshNetworkState()
                    Log.d(TAG, "Network available")
                }

                override fun onLost(network: Network) {
                    _isConnected.value = false
                    _connectionType.value = ConnectionType.DISCONNECTED
                    Log.d(TAG, "Network lost")
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    refreshNetworkState()
                }
            }

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerNetworkCallback(request, callback)
            connectivityCallback = callback
            isRegistered = true
            Log.d(TAG, "Network monitoring started (NetworkCallback)")
        } catch (e: Exception) {
            Log.w(TAG, "NetworkCallback registration failed, falling back to legacy receiver", e)
            registerLegacyReceiver()
        }
    }

    private fun registerLegacyReceiver() {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    refreshNetworkState()
                }
            }
            val filter = IntentFilter()
            @Suppress("DEPRECATION")
            filter.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
            legacyReceiver = receiver
            isRegistered = true
            Log.d(TAG, "Network monitoring started (legacy receiver)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network receiver", e)
        }
    }

    fun stopMonitoring() {
        if (!isRegistered) return

        try {
            connectivityCallback?.let {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
            legacyReceiver?.let {
                context.unregisterReceiver(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network monitor", e)
        }

        connectivityCallback = null
        legacyReceiver = null
        isRegistered = false
        Log.d(TAG, "Network monitoring stopped")
    }

    fun refreshNetworkState() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        if (network == null) {
            _isConnected.value = false
            _connectionType.value = ConnectionType.DISCONNECTED
            return
        }

        val caps = cm.getNetworkCapabilities(network)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            _isConnected.value = false
            _connectionType.value = ConnectionType.DISCONNECTED
            return
        }

        _isConnected.value = true
        _connectionType.value = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> ConnectionType.BLUETOOTH
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectionType.VPN
            else -> ConnectionType.UNKNOWN
        }
    }

    fun release() {
        stopMonitoring()
    }

    enum class ConnectionType {
        WIFI, CELLULAR, ETHERNET, BLUETOOTH, VPN, DISCONNECTED, UNKNOWN
    }

    companion object {
        private const val TAG = "NetworkChangeMonitor"
    }
}
