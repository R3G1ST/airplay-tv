package com.airplaytv.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * DACP ("Digital Audio Control Protocol") reverse remote.
 *
 * During SETUP the sender hands us its DACP id and a session token. The sender
 * advertises the matching service as `iTunes_Ctrl_<id>` in `_dacp._tcp`, so we
 * discover it via the system mDNS daemon and then issue plain HTTP commands:
 *
 *     GET http://host:port/ctrl-int/1/playpause   (header Active-Remote: token)
 *
 * This is what lets the TV remote pause/seek the phone's music, exactly like the
 * Siri Remote does with an Apple TV.
 */
class DacpClient(private val context: Context) {

    private val nsd: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dacp").apply { isDaemon = true }
    }

    @Volatile private var dacpId: String? = null
    @Volatile private var activeRemote: String? = null
    @Volatile private var host: String? = null
    @Volatile private var port: Int = 0

    private var discovery: NsdManager.DiscoveryListener? = null

    val isAvailable: Boolean get() = activeRemote != null && host != null
    val hasSender: Boolean get() = activeRemote != null

    fun configure(newId: String?, newToken: String?) {
        if (newId.isNullOrBlank() || newToken.isNullOrBlank()) return
        if (newId == dacpId && newToken == activeRemote) return
        dacpId = newId
        activeRemote = newToken
        host = null
        port = 0
        Log.i(TAG, "configured id=$newId, discovering iTunes_Ctrl_$newId")
        startDiscovery("iTunes_Ctrl_$newId")
    }

    @Volatile private var pendingCommand: String? = null

    /** Tells whether music is currently audible, so the hint says "Пауза" or "Играть". */
    @Volatile var isPlayingProvider: (() -> Boolean)? = null

    fun send(command: String) {
        val token = activeRemote ?: return
        // Optimistic UI: confirm the remote press at once, the HTTP round trip
        // must not delay the feedback.
        AppState.update {
            it.copy(remoteHint = describe(command), remoteHintSeq = it.remoteHintSeq + 1)
        }
        val h = host
        if (h == null) {
            // Service not resolved yet (first press right after connecting).
            pendingCommand = command
            return
        }
        val p = port
        worker.execute {
            try {
                val conn = URL("http://$h:$p/ctrl-int/1/$command").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Active-Remote", token)
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val code = conn.responseCode
                conn.disconnect()
                Log.i(TAG, "$command -> HTTP $code")
            } catch (e: Throwable) {
                Log.w(TAG, "$command failed: ${e.message}")
            }
        }
    }

    private fun describe(command: String): String = when (command) {
        "playpause" -> if (isPlayingProvider?.invoke() == true) "Пауза" else "Играть"
        "nextitem" -> "Следующий"
        "previtem" -> "Предыдущий"
        "beginff" -> "Вперёд"
        "beginrew" -> "Назад"
        else -> command
    }

    private fun startDiscovery(name: String) {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == name) {
                    try {
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                host = resolved.host?.hostAddress
                                port = resolved.port
                                Log.i(TAG, "resolved $host:$port")
                                stopDiscovery()
                                pendingCommand?.let { cmd ->
                                    pendingCommand = null
                                    send(cmd)
                                }
                            }

                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "resolve failed: $errorCode")
                            }
                        })
                    } catch (e: Throwable) {
                        Log.w(TAG, "resolve exception: ${e.message}")
                    }
                }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Throwable) {
            Log.w(TAG, "discovery exception: ${e.message}")
        }
    }

    private fun stopDiscovery() {
        discovery?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: Throwable) {
            }
        }
        discovery = null
    }

    fun stop() {
        stopDiscovery()
        host = null
        port = 0
        activeRemote = null
        dacpId = null
    }

    companion object {
        private const val TAG = "Dacp"
        private const val SERVICE_TYPE = "_dacp._tcp"
    }
}
