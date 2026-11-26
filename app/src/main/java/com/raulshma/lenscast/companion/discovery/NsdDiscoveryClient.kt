package com.raulshma.lenscast.companion.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.raulshma.lenscast.companion.model.DiscoveredService
import com.raulshma.lenscast.streaming.ServiceDiscoveryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.Executor

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

    /** Serialized resolve queue on API < 34 where NsdManager allows one resolve at a time. */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolveInFlight = false
    private var scanEpoch = 0L

    /** Active API 34+ ServiceInfoCallbacks keyed by friendly service name so stop() tears them down. */
    private val serviceInfoCallbacks = LinkedHashMap<String, NsdManager.ServiceInfoCallback>()
    private val mainExecutor = Executor { runnable -> mainHandler.post(runnable) }

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Starts (or restarts) the NSD scan. */
    fun start() {
        synchronized(lock) {
            // Restart semantics: a live cycle is torn down first so the
            // sheet's re-entry forces a fresh scan (and a double-start can
            // never register two listeners against one manager).
            stopDiscoveryLocked()
            val epoch = ++scanEpoch
            acquireMulticastLock()
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
                    // Android NsdManager delivers empty TXT attributes in
                    // onServiceFound; resolve first and filter by the
                    // `lenscast=1` TXT contract once resolved.
                    mainHandler.post { onDiscovered(info, epoch) }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    val friendly = NsdDiscoveryFilter.friendlyName(info.serviceName)
                    mainHandler.post {
                        unregisterInfoCallback(friendly)
                        synchronized(lock) {
                            resolveQueue.removeAll {
                                NsdDiscoveryFilter.friendlyName(it.serviceName) == friendly
                            }
                            byHostPort.keys
                                .filter { byHostPort[it]?.name == friendly }
                                .forEach { byHostPort.remove(it) }
                            publishByHostPort()
                        }
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
                releaseMulticastLock()
                publish(emptyList())
            }
        }
    }

    /** Stops the scan and clears [results]; idempotent. */
    fun stop() {
        synchronized(lock) {
            scanEpoch++
            stopDiscoveryLocked()
            byHostPort.clear()
            publish(emptyList())
        }
    }

    private fun stopDiscoveryLocked() {
        resolveQueue.clear()
        resolveInFlight = false
        clearInfoCallbacksLocked()
        releaseMulticastLock()
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD discovery stop failed", e)
        }
    }

    private fun clearListener() {
        synchronized(lock) {
            discoveryListener = null
            releaseMulticastLock()
        }
    }

    private fun onDiscovered(info: NsdServiceInfo, epoch: Long) {
        synchronized(lock) {
            if (epoch != scanEpoch || discoveryListener == null) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerInfoCallbackLocked(info, epoch)
                return
            }
            val friendly = NsdDiscoveryFilter.friendlyName(info.serviceName)
            if (resolveQueue.any { NsdDiscoveryFilter.friendlyName(it.serviceName) == friendly }) return
            if (NsdDiscoveryFilter.isPrioritizedCandidate(info.serviceName)) {
                resolveQueue.addFirst(info)
            } else {
                resolveQueue.addLast(info)
            }
            drainResolveQueueLocked(epoch)
        }
    }

    private fun drainResolveQueueLocked(epoch: Long) {
        if (resolveInFlight || epoch != scanEpoch || discoveryListener == null) return
        val next = resolveQueue.pollFirst() ?: return
        resolveInFlight = true
        resolve(next, epoch)
    }

    /**
     * Resolves one record to its host/port/TXT and publishes it only when the
     * resolved TXT map satisfies the `lenscast=1` contract. Runs one resolve at
     * a time on API < 34 so concurrent `_http._tcp` responders on a busy LAN do
     * not fail with `FAILURE_ALREADY_ACTIVE`.
     */
    private fun resolve(info: NsdServiceInfo, epoch: Long) {
        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(resolved: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD resolve failed: $errorCode for ${resolved.serviceName}")
                    mainHandler.post {
                        synchronized(lock) {
                            resolveInFlight = false
                            drainResolveQueueLocked(epoch)
                        }
                    }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = primaryHostAddress(resolved)
                    val txt = txtRecords(resolved)
                    mainHandler.post {
                        synchronized(lock) {
                            resolveInFlight = false
                            if (epoch == scanEpoch &&
                                host != null &&
                                NsdDiscoveryFilter.isLensCastService(resolved.serviceName, txt)
                            ) {
                                recordResolvedLocked(resolved, host, txt)
                            }
                            drainResolveQueueLocked(epoch)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "NSD resolve could not start for ${info.serviceName}", e)
            synchronized(lock) {
                resolveInFlight = false
                drainResolveQueueLocked(epoch)
            }
        }
    }

    private fun registerInfoCallbackLocked(info: NsdServiceInfo, epoch: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val key = NsdDiscoveryFilter.friendlyName(info.serviceName)
        if (serviceInfoCallbacks.containsKey(key)) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "NSD info callback failed: $errorCode for ${info.serviceName}")
                synchronized(lock) { serviceInfoCallbacks.remove(key) }
            }

            override fun onServiceUpdated(updated: NsdServiceInfo) {
                val host = primaryHostAddress(updated) ?: return
                val txt = txtRecords(updated)
                if (!NsdDiscoveryFilter.isLensCastService(updated.serviceName, txt)) return
                synchronized(lock) {
                    if (epoch != scanEpoch) return
                    recordResolvedLocked(updated, host, txt)
                }
            }

            override fun onServiceLost() {
                synchronized(lock) {
                    serviceInfoCallbacks.remove(key)
                    byHostPort.keys
                        .filter { byHostPort[it]?.name == key }
                        .forEach { byHostPort.remove(it) }
                    publishByHostPort()
                }
            }

            override fun onServiceInfoCallbackUnregistered() {
                synchronized(lock) { serviceInfoCallbacks.remove(key) }
            }
        }
        serviceInfoCallbacks[key] = callback
        try {
            nsdManager.registerServiceInfoCallback(info, mainExecutor, callback)
        } catch (e: Exception) {
            serviceInfoCallbacks.remove(key)
            Log.w(TAG, "NSD info callback could not register for ${info.serviceName}", e)
        }
    }

    private fun unregisterInfoCallback(friendlyName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val callback = synchronized(lock) { serviceInfoCallbacks.remove(friendlyName) } ?: return
        runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
    }

    private fun clearInfoCallbacksLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val callbacks = serviceInfoCallbacks.values.toList()
        serviceInfoCallbacks.clear()
        callbacks.forEach { cb ->
            runCatching { nsdManager.unregisterServiceInfoCallback(cb) }
        }
    }

    private fun recordResolvedLocked(
        resolved: NsdServiceInfo,
        host: String,
        txt: Map<String, String>,
    ) {
        byHostPort[hostKey(host, resolved.port)] = DiscoveredService(
            name = NsdDiscoveryFilter.friendlyName(resolved.serviceName),
            host = host,
            port = resolved.port,
            txt = txt,
        )
        publishByHostPort()
    }

    private fun primaryHostAddress(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val addrs: List<InetAddress> = info.hostAddresses
            val preferred = addrs.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?: addrs.firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
                ?: addrs.firstOrNull()
            if (preferred?.hostAddress != null) return preferred.hostAddress
        }
        @Suppress("DEPRECATION")
        return info.host?.hostAddress
    }

    private fun acquireMulticastLock() {
        runCatching {
            val lock = multicastLock ?: (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("LensCastNsdScan")
                ?.apply { setReferenceCounted(false) }
                ?.also { multicastLock = it }
            if (lock != null && !lock.isHeld) lock.acquire()
        }.onFailure {
            Log.w(TAG, "Could not acquire NSD multicast lock: ${it.message}")
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            val lock = multicastLock ?: return
            if (lock.isHeld) lock.release()
        }.onFailure {
            Log.w(TAG, "Could not release NSD multicast lock: ${it.message}")
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
