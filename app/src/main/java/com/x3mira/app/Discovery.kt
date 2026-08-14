package com.x3mira.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Find the phone by NAME, not by IP.
 *
 * The whole reason this exists: a phone's DHCP address changes, and a
 * hardcoded IP in the glasses dies the moment it does. The phone advertises
 * a "_x3mira._tcp" service; this discovers it and keeps the current
 * address fresh, so the link reconnects wherever the phone lands. The
 * manually-set address in settings stays as a fallback for networks that
 * block mDNS.
 */
class Discovery(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as? NsdManager

    /** Most recently resolved phone address, or null until one is found. */
    @Volatile var host: String? = null
        private set
    @Volatile var port: Int = 7391
        private set

    private var discovery: NsdManager.DiscoveryListener? = null

    fun start() {
        val mgr = nsd ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { Log.i(TAG, "mDNS discovery started") }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                Log.i(TAG, "found ${info.serviceName}; resolving")
                resolve(mgr, info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                Log.i(TAG, "service lost: ${info.serviceName}")
                // Keep the last address; a lost event is often transient and
                // the link's own retry will re-resolve on the next found.
            }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { Log.w(TAG, "discovery start failed: $e") }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        runCatching { mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { discovery = listener }
            .onFailure { Log.w(TAG, "discovery threw: ${it.message}") }
    }

    private fun resolve(mgr: NsdManager, info: NsdServiceInfo) {
        val rl = object : NsdManager.ResolveListener {
            override fun onServiceResolved(s: NsdServiceInfo) {
                val addr = s.host?.hostAddress ?: return
                host = addr; port = s.port
                Log.i(TAG, "resolved -> $addr:${s.port}")
            }
            override fun onResolveFailed(s: NsdServiceInfo, err: Int) {
                Log.w(TAG, "resolve failed: $err")
            }
        }
        runCatching { mgr.resolveService(info, rl) }
    }

    fun stop() {
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        discovery = null
    }

    companion object {
        private const val TAG = "X3Dex"
        const val SERVICE_TYPE = "_x3mira._tcp."
    }
}
