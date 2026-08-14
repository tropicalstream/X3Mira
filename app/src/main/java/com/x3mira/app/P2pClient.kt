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
    private var receiver: android.content.BroadcastReceiver? = null
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
        if (c == null) { Log.w(TAG, "p2p initialize returned no channel"); return }
        manager = m; channel = c
        running = true
        Log.i(TAG, "p2p client starting — hunting for $INSTANCE")
        // The broadcast is the AUTHORITATIVE join signal. Polling
        // requestConnectionInfo after connect() was a stand-in for this, and
        // a poor one: the framework announces group changes by broadcast, and
        // a client that only polls can miss a group that forms between polls
        // or after its bounded patience runs out.
        runCatching {
            receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                    if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                    Log.i(TAG, "connection-changed broadcast")
                    if (host == null && running) askInfo(0)
                }
            }
            context.registerReceiver(
                receiver,
                android.content.IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            )
        }.onFailure { Log.w(TAG, "receiver registration failed: ${it.message}") }
        // A group can already exist — the wearer accepted the invitation on a
        // previous run and the OS keeps it. Ask before hunting, so a restart
        // reuses it instead of courting a fresh invitation dialog.
        askInfo(0)
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
                    Log.i(TAG, "saw service '$instance' on ${device.deviceName}")
                    if (!instance.startsWith(INSTANCE, ignoreCase = true)) return
                    // Log the gate. A silent return here cost a whole debugging
                    // round: a wedged `connecting` made every later sighting
                    // vanish without a trace, indistinguishable from never
                    // having seen the phone at all.
                    if (connecting || host != null) {
                        Log.i(TAG, "sighting ignored (connecting=$connecting host=$host)")
                        return
                    }
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
                            override fun onSuccess() { askInfo(0) }
                            override fun onFailure(reason: Int) {
                                Log.w(TAG, "connect failed: $reason")
                                connecting = false
                            }
                        })
                    }.onFailure { Log.w(TAG, "connect threw: ${it.message}"); connecting = false }
                    // The framework is allowed to simply never call back — and
                    // a join attempt that dies silently must not wedge the
                    // client forever. Whatever happened, after this long the
                    // attempt is over and the next sighting may try again.
                    ui.postDelayed({
                        if (host == null && connecting) {
                            Log.w(TAG, "join attempt timed out — resetting")
                            connecting = false
                        }
                    }, JOIN_TIMEOUT_MS)
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

            // UNFILTERED on purpose. Asking for a specific instance+service
            // means the framework only surfaces an exact match, and the exact
            // string it compares against is not the one written here — the
            // record ends up fully qualified (…_tcp.local.). Ask for
            // everything and match in the listener, which is where the name is
            // already being checked anyway.
            val req = WifiP2pDnsSdServiceRequest.newInstance()
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
        // Announce every pass, gated or not. A loop that only speaks on
        // success is unfalsifiable from a log.
        Log.i(TAG, "sweep (running=$running connecting=$connecting host=$host)")
        if (!running || host != null) return
        runCatching {
            // PEERS FIRST. On this Android 12 build a service sweep on its own
            // sees nothing — five minutes of clean sweeps against a phone that
            // was advertising and discoverable found no one, and the OS peer
            // table stayed EMPTY the whole time. A peer scan is what actually
            // populates that table; the service query is answered from it.
            m.discoverPeers(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i(TAG, "peer scan started") }
                override fun onFailure(reason: Int) { Log.w(TAG, "peer scan failed: $reason") }
            })
            // Log what the radio can SEE, so "no peers at all" and "peers but
            // no service answer" stop being the same silence.
            ui.postDelayed({
                runCatching {
                    m.requestPeers(c) { peers ->
                        val names = peers?.deviceList?.joinToString { it.deviceName }
                        Log.i(TAG, "peers visible: [${names.orEmpty()}]")
                    }
                }
            }, 4_000L)
            m.discoverServices(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i(TAG, "discovery sweep started") }
                override fun onFailure(reason: Int) { Log.w(TAG, "discovery failed: $reason") }
            })
        }
        ui.postDelayed({ sweep() }, SWEEP_MS)
    }

    private fun askInfo(attempt: Int) {
        val m = manager ?: return
        val c = channel ?: return
        // BOUNDED, and it logs its progress. The first version of this loop
        // polled forever with `connecting` held true and said nothing — which
        // wedged the whole client the first time a connect() "succeeded"
        // without a group ever forming, and did it invisibly.
        if (attempt >= ASK_TRIES) {
            Log.w(TAG, "group never formed after $attempt polls — giving up this attempt")
            connecting = false
            return
        }
        runCatching {
            m.requestConnectionInfo(c) { info ->
                val addr = info?.groupOwnerAddress?.hostAddress
                if (info != null && info.groupFormed && addr != null) {
                    host = addr
                    connecting = false
                    Log.i(TAG, "group formed — owner at $addr (isOwner=${info.isGroupOwner})")
                    onHost(addr)
                } else {
                    Log.i(TAG, "no group yet (poll $attempt: formed=${info?.groupFormed})")
                    ui.postDelayed({ if (host == null && running) askInfo(attempt + 1) }, 1_200L)
                }
            }
        }
    }

    fun stop() {
        running = false
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
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
        /** One join attempt's whole budget, callbacks included. */
        private const val JOIN_TIMEOUT_MS = 20_000L
        /** connectionInfo polls per attempt before conceding. */
        private const val ASK_TRIES = 10
    }
}
