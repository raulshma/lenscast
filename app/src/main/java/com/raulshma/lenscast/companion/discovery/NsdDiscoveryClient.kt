package com.raulshma.lenscast.companion.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.raulshma.lenscast.companion.model.DiscoveredService
import com.raulshma.lenscast.streaming.ServiceDiscoveryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pairing sheet's LAN radar: NsdManager discovery over `_http._tcp`,
 * filtered to the records the camera app's own mDNS registration publishes
 * (the `lenscast=1` TXT contract — [NsdDiscoveryFilter] owns the verdict),
 * surfaced as [DiscoveredService] candidates.
 *
 * [results] is a [StateFlow] — the sheet renders whatever the last
 * start/stop cycle left, and a re-entry to the screen re-reads instead of
 * re-scanning. The StateFlow's conflation plus the host:port-keyed internal
 * map mean a device resolving twice (a refresh cycle re-finding it) updates
 * one entry instead of duplicating; arrival order is preserved. Everything
 * that decides — LensCast-ness, display name — lives in
 * [NsdDiscoveryFilter]; this class is the thin NsdManager shell the JVM
 * cannot test (discovery itself is exercised only on-device).
 *
 * Callbacks are marshaled onto the main looper's handler before any resolve
 * or state write: NsdManager's delivery thread is unspecified across API
 * levels, and the sheet's state must always change from one thread.
 * INACTIVE/FAILURE verdicts (a discovery start losing a race against a
 * stop, the framework's discovery-disabled errors) are logged and surface
 * as an empty snapshot — never a crash; the sheet just shows no devices.
 *
 * The client owns its lifecycle via [start]/[stop] (idempotent; [start]
 * also acts as a restart — the sheet re-invokes it on re-entry to force a
 * fresh scan).
 */
class NsdDiscoveryClient(private val context: Context) {

    private val lock = Any()

    private val _results = MutableStateFlow(emptyList<DiscoveredService>())

    /** The current discovery snapshot; empty before the first cycle. */
    val results: StateFlow<List<DiscoveredService>> = _results.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    /** Non-null while a discovery is live — the start/stop idempotence gate. */
    @Volatile
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Discovered LensCast devices keyed by `host:port` — the dedupe seam. */
    private val byHostPort = LinkedHashMap<String, DiscoveredService>()

    /** Starts (or restarts) the NSD scan. */
    fun start() {
        synchronized(lock) {
            // Restart semantics: a live cycle is torn down first so the
            // sheet's re-entry forces a fresh scan (and a double-start can
            // never register two listeners against one manager).
            stopDiscovery()
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "NSD discovery started: $serviceType")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "NSD discovery failed to start: $errorCode for $serviceType")
                    clearListener()
                    publish(emptyList())
                }

                override fun onServiceFound(info: NsdServiceInfo) {
                    val txt = txtRecords(info)
                    if (!NsdDiscoveryFilter.isLensCastService(info.serviceName, txt)) return
                    // Marshaled to the main looper: the resolve (and the
                    // state writes it triggers) always run on one thread.
                    mainHandler.post { resolve(info) }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    // A lost info carries no resolved host, so drop by name.
                    synchronized(lock) {
                        byHostPort.keys
                            .filter { byHostPort[it]?.name == info.serviceName }
                            .forEach { byHostPort.remove(it) }
                        publishByHostPort()
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "NSD discovery stopped: $serviceType")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    // The framework refuses the stop (usually INACTIVE —
                    // nothing to stop); treat the cycle as dead either way.
                    Log.w(TAG, "NSD discovery failed to stop: $errorCode for $serviceType")
                    clearListener()
                }
            }
            discoveryListener = listener
            try {
                nsdManager.discoverServices(
                    ServiceDiscoveryManager.SERVICE_TYPE_HTTP,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
            } catch (e: Exception) {
                Log.w(TAG, "NSD discovery could not start", e)
                clearListener()
                publish(emptyList())
            }
        }
    }

    /** Stops the scan and clears [results]; idempotent. */
    fun stop() {
        synchronized(lock) {
            stopDiscovery()
            byHostPort.clear()
            publish(emptyList())
        }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD discovery stop failed", e)
        }
    }

    private fun clearListener() {
        synchronized(lock) { discoveryListener = null }
    }

    /**
     * Resolves one LensCast record to its host/port/TXT. Only a resolved
     * record becomes a candidate — a record that never resolves (the common
     * churn when a device leaves the LAN mid-scan) stays absent.
     */
    private fun resolve(info: NsdServiceInfo) {
        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(resolved: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD resolve failed: $errorCode for ${resolved.serviceName}")
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    synchronized(lock) {
                        byHostPort[hostKey(host, resolved.port)] = DiscoveredService(
                            name = NsdDiscoveryFilter.friendlyName(resolved.serviceName),
                            host = host,
                            port = resolved.port,
                            txt = txtRecords(resolved),
                        )
                        publishByHostPort()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "NSD resolve could not start for ${info.serviceName}", e)
        }
    }

    /** Dedupe + publish: insertion order preserved, keyed by `host:port`. */
    private fun publishByHostPort() {
        _results.value = byHostPort.values.toList()
    }

    private fun publish(services: List<DiscoveredService>) {
        _results.value = services
    }

    /**
     * The TXT attributes as strings. NsdManager delivers keys lowercased
     * (the filter re-normalizes anyway) and values as UTF-8 bytes, null for
     * valueless flags.
     */
    private fun txtRecords(info: NsdServiceInfo): Map<String, String> =
        info.attributes.entries.associate { (key, value) ->
            key to (value?.let { String(it, Charsets.UTF_8) } ?: "")
        }

    private fun hostKey(host: String, port: Int): String = "$host:$port"

    companion object {
        private const val TAG = "NsdDiscoveryClient"
    }
}
