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
    /**
     * True while the mirror is actually delivering frames. Every part of this
     * client RESTS while that holds: joining a group the wearer doesn't need
     * can put the single radio in time-slice between the router's channel and
     * the group's — this codebase measured a working mirror collapse from
     * 40 fps to 2 that way — and even the discovery sweeps steal air time.
     * P2P exists for when there is no link, so it runs when there is none.
     */
    private val linkUp: () -> Boolean = { false },
    private val onHost: (String) -> Unit
) {
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var request: WifiP2pDnsSdServiceRequest? = null
    private var receiver: android.content.BroadcastReceiver? = null
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var connecting = false
    /**
     * Consecutive credential-join attempts that produced no group. After
     * [CRED_STRIKES] the joins fall back to negotiation, where the phone's
     * approval bubble at least works. A COUNTER, not a flag, and reset by a
     * formed group: one transient BUSY must not exile credentials for the
     * life of the process. Counted from the watchdog as well as from
     * onFailure, because the realistic way a bad-credential join dies is
     * SILENTLY — the request is accepted and no group ever forms — and a
     * fallback that only fires on explicit rejection is unreachable in
     * exactly the case it exists for.
     */
    @Volatile private var credFailures = 0
    /** Monotonic id of the newest join attempt, so a stale watchdog from an
     *  earlier attempt cannot clear a newer one's state — the 20s timer
     *  outlives the 12s sweep, so overlap is the normal case, not the race. */
    private var joinSeq = 0
    /** Which method the newest attempt used, so EVERY way an attempt can die
     *  — explicit rejection, the watchdog, or askInfo running out of polls —
     *  charges the strike to the right method. The poll-exhaustion exit is
     *  the one that actually fires on this hardware, and it beats the
     *  watchdog to clearing `connecting`, so a strike counted only by the
     *  watchdog is a strike never counted. */
    @Volatile private var attemptUsedCredentials = false
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
                    if (host == null) {
                        if (running) askInfo(0)
                        return
                    }
                    // A host is HELD, so this broadcast may be the group
                    // DISSOLVING — and an address from a dead group is worse
                    // than none: the link dials it forever, the sweeps stay
                    // stopped because host looks satisfied, and the wearer
                    // watches "reconnecting" for the rest of the session.
                    // Seen live when the phone re-formed its group on a new
                    // channel and these glasses never followed.
                    val m = manager ?: return
                    val c = channel ?: return
                    runCatching {
                        m.requestConnectionInfo(c) { info ->
                            if (info == null || !info.groupFormed) {
                                Log.i(TAG, "group dissolved — clearing host, resuming hunt")
                                host = null
                                connecting = false
                                if (running) sweep()
                            }
                        }
                    }
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
                    if (linkUp()) {
                        Log.i(TAG, "sighting ignored — mirror already delivering")
                        return
                    }
                    connecting = true
                    Log.i(TAG, "found $instance on ${device.deviceName} — joining")
                    // JOIN BY PASSPHRASE, NOT BY BUTTON-PRESS. The phone
                    // creates its group with fixed credentials for exactly
                    // this: a client that presents them is admitted like an
                    // ordinary Wi-Fi client, and the wearer's phone never
                    // shows the "device wants to connect" bubble. The old
                    // negotiation join asked a human to approve every fresh
                    // group — which is every capture restart — and that tap
                    // is the one step that made outdoor reconnects manual.
                    // If a credential join ever fails on this OS pairing, the
                    // strike is remembered and the next sighting falls back
                    // to negotiation, where the bubble at least works.
                    val useCredentials = credFailures < CRED_STRIKES
                    attemptUsedCredentials = useCredentials
                    val attempt = ++joinSeq
                    val cfg = if (useCredentials) runCatching {
                        android.net.wifi.p2p.WifiP2pConfig.Builder()
                            .setNetworkName(NET_NAME)
                            .setPassphrase(PASSPHRASE)
                            .setDeviceAddress(
                                android.net.MacAddress.fromString(device.deviceAddress))
                            .build()
                    }.getOrElse { pbcConfig(device) } else pbcConfig(device)
                    // Our own sweep may still be scanning, and connect() during
                    // an active discovery is the classic source of BUSY. Stop
                    // it first; the sweep timer re-arms discovery afterwards.
                    runCatching { m.stopPeerDiscovery(c, null) }
                    runCatching {
                        m.connect(c, cfg, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { askInfo(0) }
                            override fun onFailure(reason: Int) {
                                if (useCredentials) credFailures++
                                Log.w(TAG, "connect failed: $reason" +
                                    " (credential strikes $credFailures/$CRED_STRIKES)")
                                connecting = false
                            }
                        })
                    }.onFailure { Log.w(TAG, "connect threw: ${it.message}"); connecting = false }
                    // The framework is allowed to simply never call back — and
                    // a join attempt that dies silently must not wedge the
                    // client forever. Guarded by the attempt id: this timer
                    // outlives the sweep period, so without the guard a stale
                    // watchdog from attempt A fires into attempt B — clearing
                    // a negotiation join mid-human-tap, or charging B's slow
                    // start against A's method. A silent death counts as a
                    // credential strike, because silence is HOW wrong
                    // credentials fail — the request is accepted and nothing
                    // ever forms.
                    ui.postDelayed({
                        if (host == null && connecting && attempt == joinSeq) {
                            if (useCredentials) credFailures++
                            Log.w(TAG, "join attempt timed out — resetting" +
                                " (credential strikes $credFailures/$CRED_STRIKES)")
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
        if (linkUp()) {
            // Resting, not stopped: the timer keeps beating so the hunt
            // resumes by itself the moment the mirror goes quiet.
            ui.postDelayed({ sweep() }, SWEEP_MS)
            return
        }
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
            if (attemptUsedCredentials) credFailures++
            Log.w(TAG, "group never formed after $attempt polls — giving up" +
                " (credential strikes $credFailures/$CRED_STRIKES)")
            connecting = false
            return
        }
        runCatching {
            m.requestConnectionInfo(c) { info ->
                val addr = info?.groupOwnerAddress?.hostAddress
                if (info != null && info.groupFormed && addr != null) {
                    host = addr
                    connecting = false
                    // Forgive strikes ONLY when credentials produced this
                    // group. A group formed by negotiation proves nothing
                    // about the credential path — resetting on it would
                    // re-run two doomed ~12s credential attempts at every
                    // re-form on hardware whose HAL never completes them.
                    if (attemptUsedCredentials) credFailures = 0
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

    /** The phone contested nothing: it made itself owner outright, so never
     *  bid for the role — a negotiation this side won would put the group
     *  owner on the device with no ServerSocket. */
    private fun pbcConfig(device: WifiP2pDevice) = WifiP2pConfig().apply {
        deviceAddress = device.deviceAddress
        groupOwnerIntent = 0
    }

    companion object {
        private const val TAG = "X3MiraP2p"
        private const val INSTANCE = "x3mira"
        /** MUST match P2pHost on the phone — the credentials ARE the pairing. */
        private const val NET_NAME = "DIRECT-x3mira"
        private const val PASSPHRASE = "x3mira-link-2026"
        private const val SERVICE = "_x3mira._tcp"
        private const val SWEEP_MS = 12_000L
        /** One join attempt's whole budget, callbacks included. */
        private const val JOIN_TIMEOUT_MS = 20_000L
        /** connectionInfo polls per attempt before conceding. */
        private const val ASK_TRIES = 10
        /** Credential-join failures tolerated before negotiating instead. */
        private const val CRED_STRIKES = 2
    }
}
