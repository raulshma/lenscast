package com.raulshma.lenscast.streaming

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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

    /** The RTSP advert's last registration attempt; the change-detection input for a refresh. */
    @Volatile private var rtspAdvert: RtspAdvert? = null

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
        if (isRegistered.get()) {
            Log.d(TAG, "mDNS service already registered")
        } else {
            registerHttp(port, serviceName)
        }
        // Always (re-)evaluate the RTSP advert: it is re-registered when
        // re-invoked with changed params (the late auth toggle), and a
        // no-op when nothing moved.
        if (rtsp != null) registerRtsp(port = rtsp.port, path = rtsp.path, authRequired = rtsp.authRequired, serviceName = serviceName)
    }

    private fun registerHttp(port: Int, serviceName: String) {

        val serviceInfo = NsdServiceInfo()
        serviceInfo.setServiceName(makeUniqueServiceName(serviceName))
        serviceInfo.setServiceType(SERVICE_TYPE_HTTP)
        serviceInfo.setPort(port)

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
                        val retry = NsdServiceInfo()
                        retry.setServiceName(makeUniqueServiceName("$DEFAULT_SERVICE_NAME-$registerAttempts"))
                        retry.setServiceType(SERVICE_TYPE_HTTP)
                        retry.setPort(info.port)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service", e)
            isRegistered.set(false)
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
        rtspAdvert = null
        registerAttempts = 0
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
