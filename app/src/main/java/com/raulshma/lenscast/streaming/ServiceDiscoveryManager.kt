package com.raulshma.lenscast.streaming

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class ServiceDiscoveryManager(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    // @Volatile: written on the caller's thread, nulled from any stop path —
    // the unregister decision reads them without a flag gate now.
    @Volatile private var registrationListener: NsdManager.RegistrationListener? = null
    @Volatile private var rtspRegistrationListener: NsdManager.RegistrationListener? = null
    private val isRegistered = AtomicBoolean(false)
    private val isRtspRegistered = AtomicBoolean(false)
    @Volatile private var registerAttempts = 0

    /** The HTTP and RTSP adverts' last registration attempts; change-detection inputs for refreshes. */
    @Volatile private var httpAdvert: HttpAdvert? = null
    @Volatile private var rtspAdvert: RtspAdvert? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null

    data class HttpAdvert(
        val port: Int,
        val serviceName: String = DEFAULT_SERVICE_NAME,
    )

    data class RtspAdvert(
        val port: Int = com.raulshma.lenscast.core.StreamDefaults.RTSP_PORT,
        val path: String = "live",
        val authRequired: Boolean = false,
    )

    fun registerService(
        serviceName: String = DEFAULT_SERVICE_NAME,
        port: Int,
        deviceName: String = Build.MODEL,
        rtsp: RtspAdvert? = null,
    ) {
        acquireMulticastLock()
        val requestedHttp = HttpAdvert(port = port, serviceName = serviceName)
        if (DiscoveryRegistrationPolicy.shouldSkipHttpRegister(httpAdvert, requestedHttp)) {
            Log.d(TAG, "mDNS HTTP service already registered on port $port")
        } else {
            registerHttp(port, serviceName, requestedHttp)
        }
        // Always (re-)evaluate the RTSP advert: it is re-registered when
        // re-invoked with changed params (the late auth toggle), and a
        // no-op when nothing moved.
        if (rtsp != null) registerRtsp(port = rtsp.port, path = rtsp.path, authRequired = rtsp.authRequired, serviceName = serviceName)
    }

    private fun registerHttp(port: Int, serviceName: String, requested: HttpAdvert) {
        registrationListener?.let {
            if (DiscoveryRegistrationPolicy.shouldUnregister(listenerPresent = true)) {
                try {
                    nsdManager.unregisterService(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister stale HTTP mDNS advert", e)
                }
            }
            registrationListener = null
            isRegistered.set(false)
        }

        val serviceInfo = buildHttpServiceInfo(
            name = makeUniqueServiceName(serviceName),
            port = port,
        )

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                isRegistered.set(true)
                Log.d(TAG, "mDNS service registered: ${info.serviceName}:${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                isRegistered.set(false)
                Log.e(TAG, "mDNS registration failed: $errorCode for ${info.serviceName}")
                // Name collision is the common failure — retry once with a suffixed name.
                if (registerAttempts < MAX_REGISTER_ATTEMPTS) {
                    registerAttempts++
                    try {
                        val retry = buildHttpServiceInfo(
                            name = makeUniqueServiceName("$DEFAULT_SERVICE_NAME-$registerAttempts"),
                            port = info.port,
                        )
                        nsdManager.registerService(retry, NsdManager.PROTOCOL_DNS_SD, this)
                    } catch (e: Exception) {
                        Log.e(TAG, "mDNS retry failed", e)
                    }
                }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                isRegistered.set(false)
                Log.d(TAG, "mDNS service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener,
            )
            httpAdvert = requested
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service", e)
            isRegistered.set(false)
            httpAdvert = null
        }
    }

    fun unregisterService() {
        // Unregister for ANY non-null listener: a stop landing before the
        // async registration confirms used to skip the unregister (the flag
        // gates) AND null the listener — the advert ghosted until process
        // death and the next start advertised a duplicate. Pre-confirmation
        // unregister is legal; failures stay caught and logged.
        registrationListener?.let {
            if (DiscoveryRegistrationPolicy.shouldUnregister(listenerPresent = it != null)) {
                try {
                    nsdManager.unregisterService(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister mDNS service", e)
                }
            }
        }
        rtspRegistrationListener?.let {
            if (DiscoveryRegistrationPolicy.shouldUnregister(listenerPresent = it != null)) {
                try {
                    nsdManager.unregisterService(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister RTSP mDNS service", e)
                }
            }
        }
        registrationListener = null
        rtspRegistrationListener = null
        isRegistered.set(false)
        isRtspRegistered.set(false)
        httpAdvert = null
        rtspAdvert = null
        registerAttempts = 0
        releaseMulticastLock()
    }

    /**
     * Keep a Wi-Fi multicast lock while advertising so firmware mDNS filters
     * do not drop incoming LAN browse/resolve queries when idle.
     */
    private fun acquireMulticastLock() {
        runCatching {
            val lock = multicastLock ?: (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("LensCastMdnsAdvert")
                ?.apply { setReferenceCounted(false) }
                ?.also { multicastLock = it }
            if (lock != null && !lock.isHeld) {
                lock.acquire()
            }
        }.onFailure {
            Log.w(TAG, "Could not acquire multicast lock: ${it.message}")
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            val lock = multicastLock ?: return
            if (lock.isHeld) lock.release()
        }.onFailure {
            Log.w(TAG, "Could not release multicast lock: ${it.message}")
        }
    }

    private fun registerRtsp(port: Int, path: String, authRequired: Boolean, serviceName: String) {
        val requested = RtspAdvert(port = port, path = path, authRequired = authRequired)
        // Change detection, not a flag gate: a re-invocation with params
        // equal to the last attempt returns; any drift (the late auth
        // toggle) drops the stale advert and re-registers, so the TXT
        // record follows the truth.
        if (DiscoveryRegistrationPolicy.shouldSkipRtspRegister(rtspAdvert, requested)) return
        rtspRegistrationListener?.let {
            if (DiscoveryRegistrationPolicy.shouldUnregister(listenerPresent = it != null)) {
                try {
                    nsdManager.unregisterService(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister stale RTSP mDNS advert", e)
                }
            }
            rtspRegistrationListener = null
        }
        val serviceInfo = NsdServiceInfo()
        serviceInfo.setServiceName(makeUniqueServiceName("$serviceName-RTSP"))
        serviceInfo.setServiceType(SERVICE_TYPE_RTSP)
        serviceInfo.setPort(port)
        serviceInfo.setAttribute("path", "/$path")
        serviceInfo.setAttribute("auth", if (authRequired) "1" else "0")
        rtspRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                isRtspRegistered.set(true)
                Log.d(TAG, "RTSP mDNS registered: ${info.serviceName}:${info.port}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                isRtspRegistered.set(false)
                Log.e(TAG, "RTSP mDNS registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                isRtspRegistered.set(false)
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "RTSP mDNS unregistration failed: $errorCode")
            }
        }
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, rtspRegistrationListener)
            rtspAdvert = requested
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register RTSP mDNS service", e)
            isRtspRegistered.set(false)
        }
    }

    /**
     * Every HTTP advert the manager builds — the initial registration and
     * the collision retry alike — comes from here, so the published TXT
     * records ([DiscoveryRegistrationPolicy.httpTxtRecords]) can never go
     * missing on one path only.
     */
    private fun buildHttpServiceInfo(name: String, port: Int): NsdServiceInfo {
        val serviceInfo = NsdServiceInfo()
        serviceInfo.setServiceName(name)
        serviceInfo.setServiceType(SERVICE_TYPE_HTTP)
        serviceInfo.setPort(port)
        // The lenscast=1 marker is why this helper exists as a single seam:
        // the companion app's discovery filter keys on this TXT record to
        // tell a LensCast device apart from every other _http._tcp service
        // shouting on the same LAN (printers, AirPlay, other phones).
        DiscoveryRegistrationPolicy.httpTxtRecords().forEach { (key, value) ->
            serviceInfo.setAttribute(key, value)
        }
        return serviceInfo
    }

    private fun makeUniqueServiceName(baseName: String): String {
        val deviceId = Build.MODEL.replace(Regex("[^a-zA-Z0-9-]"), "-").take(20)
        return "$baseName-$deviceId"
    }

    companion object {
        private const val TAG = "ServiceDiscoveryMgr"
        const val SERVICE_TYPE_HTTP = "_http._tcp."
        const val SERVICE_TYPE_RTSP = "_rtsp._tcp."
        const val DEFAULT_SERVICE_NAME = "LensCast"
        private const val MAX_REGISTER_ATTEMPTS = 2
    }
}
