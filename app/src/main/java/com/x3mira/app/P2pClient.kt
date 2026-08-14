package com.x3mira.app

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Joins the phone's Wi-Fi Direct group, so the mirror works with no router and
 * without asking the wearer to turn on a hotspot.
 *
 * FOUND BY SERVICE, NOT BY NAME. Wi-Fi Direct discovery returns every P2P peer
 * in earshot — a TV, a printer, somebody else's phone — and none of them are
 * running the mirror. Matching on the advertised "_x3mira._tcp" service means
 * the glasses connect to the phone that is actually offering to be mirrored,
 * and connect to nothing at all when it is not.
 *
 * THE ADDRESS COMES FROM THE GROUP, not from a setting. Once connected, the
 * owner's address is whatever the group says it is — the wearer never types an
 * IP, which is the step that would otherwise have to be explained every time
 * the network changed. [onHost] hands it to the link.
 *
 * What makes this possible at all is that the agent's model calls now go
 * through the phone: a P2P group carries no internet, so the glasses have to
 * not need any.
 */
class P2pClient(
    private val context: Context,
    private val onHost: (String) -> Unit
) {
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var request: WifiP2pDnsSdServiceRequest? = null
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var connecting = false
    /** Owner address of the group we are in, once there is one. */
    @Volatile var host: String? = null
        private set

    fun start() {
        if (running) return
        val m = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: run {
            Log.w(TAG, "no Wi-Fi P2P on this device — staying on the ordinary network")
            return
        }
        val c = runCatching { m.initialize(context, Looper.getMainLooper(), null) }.getOrNull()
            ?: return
        manager = m; channel = c
        running = true
        hunt()
    }

    private fun hunt() {
        val m = manager ?: return
        val c = channel ?: return
        runCatching {
            val onService = object : WifiP2pManager.DnsSdServiceResponseListener {
                override fun onDnsSdServiceAvailable(
                    instance: String?, registrationType: String?, device: WifiP2pDevice?
                ) {
                    if (instance == null || device == null) return
                    if (!instance.startsWith(INSTANCE, ignoreCase = true)) return
                    if (connecting || host != null) return
                    connecting = true
                    Log.i(TAG, "found $instance on ${device.deviceName} — joining")
                    val cfg = WifiP2pConfig().apply {
                        deviceAddress = device.deviceAddress
                        // The phone made itself owner outright, so never
                        // contest the role: a negotiation this side won would
                        // put the group owner on the device with no
                        // ServerSocket.
                        groupOwnerIntent = 0
                    }
                    runCatching {
                        m.connect(c, cfg, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { askInfo() }
                            override fun onFailure(reason: Int) {
                                Log.w(TAG, "connect failed: $reason")
                                connecting = false
                            }
                        })
                    }.onFailure { connecting = false }
                }
            }
            // The TXT listener is registered even though nothing here reads
            // the record: with only the service listener set, discovery
            // silently returns nothing at all.
            val onTxt = object : WifiP2pManager.DnsSdTxtRecordListener {
                override fun onDnsSdTxtRecordAvailable(
                    fullDomain: String?, record: MutableMap<String, String>?, device: WifiP2pDevice?
                ) { /* the port is fixed; nothing to read */ }
            }
            m.setDnsSdResponseListeners(c, onService, onTxt)

            val req = WifiP2pDnsSdServiceRequest.newInstance(INSTANCE, SERVICE)
            request = req
            m.addServiceRequest(c, req, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { sweep() }
                override fun onFailure(reason: Int) { Log.w(TAG, "addServiceRequest: $reason") }
            })
        }.onFailure { Log.w(TAG, "hunt failed: ${it.message}") }
    }

    /**
     * Discovery is a one-shot, not a subscription: it runs for a while and
     * stops. Re-arming on a timer is what makes the glasses find the phone
     * when the phone is switched on SECOND, which is the usual order.
     */
    private fun sweep() {
        val m = manager ?: return
        val c = channel ?: return
        if (!running || host != null) return
        runCatching { m.discoverServices(c, null) }
        ui.postDelayed({ sweep() }, SWEEP_MS)
    }

    private fun askInfo() {
        val m = manager ?: return
        val c = channel ?: return
        runCatching {
            m.requestConnectionInfo(c) { info ->
                val addr = info?.groupOwnerAddress?.hostAddress
                if (info != null && info.groupFormed && addr != null) {
                    host = addr
                    connecting = false
                    Log.i(TAG, "group formed — owner at $addr (isOwner=${info.isGroupOwner})")
                    onHost(addr)
                } else {
                    // The group can take a moment to form after connect()
                    // returns success; asking once and giving up is how this
                    // ends up looking like it never connected.
                    ui.postDelayed({ if (host == null && running) askInfo() }, 1_200L)
                }
            }
        }
    }

    fun stop() {
        running = false
        val m = manager; val c = channel; val r = request
        if (m != null && c != null) {
            runCatching { if (r != null) m.removeServiceRequest(c, r, null) }
            runCatching { m.cancelConnect(c, null) }
        }
        manager = null; channel = null; request = null
        host = null
    }

    companion object {
        private const val TAG = "X3MiraP2p"
        private const val INSTANCE = "x3mira"
        private const val SERVICE = "_x3mira._tcp"
        private const val SWEEP_MS = 12_000L
    }
}
