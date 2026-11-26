package org.bolcr.letmeknow.radio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.bolcr.letmeknow.shared.Protocol
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The radio looks for the phone on the Wi-Fi (the phone's hotspot) and keeps one connection open to it. The phone is
 * found three ways, in this order: announced by name on the network (NSD), the Wi-Fi's gateway (with the hotspot,
 * that is the phone itself), and an address typed in the radio's screen.
 */
class Link(context: Context, private val onMessage: (JSONObject) -> Unit, private val onState: (String?) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val writes = Executors.newSingleThreadExecutor()
    private val wake = LinkedBlockingQueue<Unit>()
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var nsdHost: String? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun start() {
        if (running) return
        running = true
        Thread({ loop() }, "letmeknow-link").apply { isDaemon = true }.start()
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { wake.offer(Unit) }
            })
        discover()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        wake.offer(Unit)
    }

    fun send(line: String) {
        writes.execute {
            val w = writer ?: return@execute
            try {
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (e: Exception) {
                runCatching { socket?.close() }
            }
        }
    }

    private fun loop() {
        while (running) {
            var connected = false
            for (host in candidates()) {
                if (!running) break
                val s = connect(host) ?: continue
                connected = true
                Settings.setLastPhone(app, host)
                session(s, host)
                break
            }
            if (!running) break
            // Nothing answered: wait a bit, or until the Wi-Fi comes back.
            wake.clear()
            wake.poll(if (connected) 1L else 4L, TimeUnit.SECONDS)
        }
    }

    private fun candidates(): List<String> =
        listOfNotNull(Settings.phoneAddress(app).ifBlank { null }, nsdHost, gateway(), Settings.lastPhone(app))
            .distinct()

    private fun wifi(): Network? {
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** With the phone's hotspot, the Wi-Fi's gateway is the phone. */
    private fun gateway(): String? {
        val net = wifi()
        if (net != null && Build.VERSION.SDK_INT >= 29) {
            cm.getLinkProperties(net)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
                ?.gateway?.hostAddress?.let { if (!it.contains(':')) return it }
        }
        return try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val g = wm.dhcpInfo?.gateway ?: 0
            if (g == 0) null else "${g and 0xff}.${g shr 8 and 0xff}.${g shr 16 and 0xff}.${g shr 24 and 0xff}"
        } catch (e: Exception) {
            null
        }
    }

    private fun connect(host: String): Socket? = try {
        val s = Socket()
        wifi()?.bindSocket(s)
        s.connect(InetSocketAddress(host, Protocol.PORT), 2500)
        s
    } catch (e: Exception) {
        null
    }

    private fun session(s: Socket, host: String) {
        socket = s
        s.tcpNoDelay = true
        s.soTimeout = Protocol.SILENT_MS.toInt()
        val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        writer = w
        main.post { onState(host) }
        val pinger = Thread {
            try {
                while (socket === s) {
                    Thread.sleep(Protocol.PING_MS)
                    send(Protocol.message(Protocol.PING))
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }
        try {
            send(Protocol.message(Protocol.HELLO) { put("v", Protocol.VERSION) })
            pinger.start()
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (running) {
                val line = reader.readLine() ?: break
                val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (msg.optString("t") == Protocol.PONG) continue
                main.post { onMessage(msg) }
            }
        } catch (e: Exception) {
            Log.i("LetMeKnow", "Phone connection ended: ${e.message}")
        } finally {
            runCatching { s.close() }
            socket = null
            writer = null
            pinger.interrupt()
            main.post { onState(null) }
        }
    }

    /** Listens for the phone's name on the network all the time; the newest address found is tried first. */
    private fun discover() {
        val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith(Protocol.SERVICE_NAME)) return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) = Unit
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val h = i.host?.hostAddress ?: return
                        if (h.contains(':')) return
                        nsdHost = h
                        wake.offer(Unit)
                    }
                })
            }
        }
        discovery = listener
        try {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w("LetMeKnow", "NSD not available", e)
        }
    }
}
