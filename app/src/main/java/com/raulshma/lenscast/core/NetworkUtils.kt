package com.raulshma.lenscast.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    fun getLocalIpAddress(): String? {
        // Prefer IPv4 for legacy MJPEG/RTSP clients, fall back to IPv6 on v6-only networks.
        return getAllLocalIpAddresses().firstOrNull { !it.contains(':') }
            ?: getAllLocalIpAddresses().firstOrNull()
    }

    fun getAllLocalIpAddresses(): List<String> {
        val out = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (intf in interfaces) {
                try {
                    if (!intf.isUp || intf.isLoopback) continue
                } catch (_: Exception) {
                    continue
                }
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    // Strip IPv6 zone id (e.g. %wlan0) for URL building.
                    out.add(host.substringBefore('%'))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Bracket IPv6 literals for URL building; IPv4/hostnames pass through. */
    fun formatHostForUrl(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    fun getStreamingUrl(port: Int, ssl: Boolean = false): String? {
        val ip = getLocalIpAddress() ?: return null
        return "${if (ssl) "https" else "http"}://${formatHostForUrl(ip)}:$port/stream"
    }

    fun getAudioUrl(port: Int, ssl: Boolean = false): String? {
        val ip = getLocalIpAddress() ?: return null
        return "${if (ssl) "https" else "http"}://${formatHostForUrl(ip)}:$port/audio"
    }

    fun getSnapshotUrl(port: Int, ssl: Boolean = false): String? {
        val ip = getLocalIpAddress() ?: return null
        return "${if (ssl) "https" else "http"}://${formatHostForUrl(ip)}:$port/snapshot"
    }

    fun getHlsPlaylistUrl(port: Int, ssl: Boolean = false): String? {
        val ip = getLocalIpAddress() ?: return null
        return "${if (ssl) "https" else "http"}://${formatHostForUrl(ip)}:$port/hls/playlist.m3u8"
    }

    fun getRtspUrl(port: Int, path: String = "live"): String? {
        val ip = getLocalIpAddress() ?: return null
        return "rtsp://${formatHostForUrl(ip)}:$port/$path"
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Local-network readiness for the dashboard banner (Android 16 LNP-aware). */
    sealed interface LocalNetworkState {
        data class Ready(val ip: String, val allIps: List<String>) : LocalNetworkState
        data object NoNetwork : LocalNetworkState
        data object PermissionBlocked : LocalNetworkState
    }

    fun localNetworkState(): LocalNetworkState {
        val all = getAllLocalIpAddresses()
        val primary = all.firstOrNull { !it.contains(':') } ?: all.firstOrNull()
        return if (primary != null) LocalNetworkState.Ready(primary, all) else LocalNetworkState.NoNetwork
    }
}
