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

    private var registrationListener: NsdManager.RegistrationListener? = null
    private val isRegistered = AtomicBoolean(false)

    fun registerService(
        serviceName: String = DEFAULT_SERVICE_NAME,
        port: Int,
        deviceName: String = Build.MODEL,
    ) {
        if (isRegistered.get()) {
            Log.d(TAG, "mDNS service already registered")
            return
        }

        val serviceInfo = NsdServiceInfo().also { info ->
            info.serviceName = makeUniqueServiceName(serviceName)
            info.serviceType = SERVICE_TYPE_HTTP
            info.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                isRegistered.set(true)
                Log.d(TAG, "mDNS service registered: ${info.serviceName}:${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                isRegistered.set(false)
                Log.e(TAG, "mDNS registration failed: $errorCode for ${info.serviceName}")
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
        if (!isRegistered.get()) return
        val listener = registrationListener ?: return
        try {
            nsdManager.unregisterService(listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister mDNS service", e)
        }
    }

    fun isServiceActive(): Boolean = isRegistered.get()

    private fun makeUniqueServiceName(baseName: String): String {
        val deviceId = Build.MODEL.replace(Regex("[^a-zA-Z0-9-]"), "-").take(20)
        return "$baseName-$deviceId"
    }

    companion object {
        private const val TAG = "ServiceDiscoveryMgr"
        const val SERVICE_TYPE_HTTP = "_http._tcp."
        const val DEFAULT_SERVICE_NAME = "LensCast"
        private const val TXT_KEY_DEVICE = "device"
        private const val TXT_KEY_PATH = "path"
        private const val TXT_VALUE_PATH = "/"
    }
}
