package com.airplaytv.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Last-resort advertiser built on Android's system mDNS (NsdManager).
 *
 * The bundled [com.airplaytv.core.mdns.MdnsResponder] is used first because it
 * gives full control over TXT records and announcements; this fallback exists
 * for devices where binding port 5353 directly is refused.
 */
class NsdFallback(context: Context) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()

    fun register(deviceName: String, deviceId: String, port: Int, airplayTxt: Map<String, String>, raopTxt: Map<String, String>) {
        registerService("_airplay._tcp", deviceName, port, airplayTxt)
        registerService("_raop._tcp", "$deviceId@$deviceName", port, raopTxt)
    }

    private fun registerService(type: String, name: String, port: Int, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceType = type
            serviceName = name
            setPort(port)
            for ((k, v) in txt) {
                try {
                    setAttribute(k, v)
                } catch (e: Throwable) {
                    Log.w(TAG, "cannot set TXT $k: ${e.message}")
                }
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered ${info.serviceType} as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed for ${info.serviceType}: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        listeners.add(listener)
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Throwable) {
            Log.w(TAG, "NsdManager refused $type: ${e.message}")
        }
    }

    fun unregister() {
        for (listener in listeners) {
            try {
                manager.unregisterService(listener)
            } catch (_: Throwable) {
            }
        }
        listeners.clear()
    }

    companion object {
        private const val TAG = "NsdFallback"
    }
}
