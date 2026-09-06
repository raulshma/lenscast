package com.raulshma.lenscast.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped observer of Wi-Fi connectivity. Registers one network callback
 * and exposes the live state as a flow — callers observe instead of taking
 * one-shot snapshots that go stale.
 */
class ConnectivityMonitor(private val context: Context) {

    private val _isWifiConnected = MutableStateFlow(NetworkUtils.isWifiConnected(context))
    val isWifiConnected: StateFlow<Boolean> = _isWifiConnected.asStateFlow()

    private var registered = false

    fun start() {
        if (registered) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isWifiConnected.value = true
                }

                override fun onLost(network: Network) {
                    _isWifiConnected.value = NetworkUtils.isWifiConnected(context)
                }
            })
            registered = true
            Log.d(TAG, "Connectivity monitoring started (wifi=${_isWifiConnected.value})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register connectivity callback", e)
        }
    }

    companion object {
        private const val TAG = "ConnectivityMonitor"
    }
}
