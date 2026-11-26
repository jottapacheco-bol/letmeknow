package org.bolcr.letmeknow.telefono

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.bolcr.letmeknow.shared.Protocol
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The phone waits for the radio on [Protocol.PORT] and announces itself on the Wi-Fi. The radio is one client: a new
 * connection replaces the old one (the radio came back after the car was off).
 */
object Server {
    private const val TAG = "LetMeKnow"
    private val main = Handler(Looper.getMainLooper())
    private val writes = Executors.newSingleThreadExecutor()

    @Volatile private var started = false
    @Volatile private var client: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    /** Who is connected, for the main screen. Null while waiting for the radio. */
    @Volatile var radio: String? = null
        private set

    /** Everything shown now, sent when the radio connects. Set by [Listener]. */
    @Volatile var snapshot: () -> List<JSONObject> = { emptyList() }

    /** What the radio asks for, run on the main thread. Set by [Listener]. */
    @Volatile var onCommand: (JSONObject) -> Unit = {}

    @Volatile private var app: Context? = null

    /** The phone's notification tone, so the radio sounds like the phone. Null if Android doesn't let it be read. */
    private fun tone(c: Context): Pair<ByteArray, String>? = try {
        val uri = android.media.RingtoneManager.getActualDefaultRingtoneUri(c, android.media.RingtoneManager.TYPE_NOTIFICATION)
        if (uri == null) null else {
            val name = runCatching { android.media.RingtoneManager.getRingtone(c, uri)?.getTitle(c) }.getOrNull().orEmpty()
            val bytes = c.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    if (out.size() > 1024 * 1024) return null
                }
                out.toByteArray()
            }
            bytes?.takeIf { it.isNotEmpty() }?.let { it to name }
        }
    } catch (e: Exception) {
        Log.i(TAG, "Could not read the notification tone: ${e.message}")
        null
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        this.app = app
        Thread({ acceptLoop() }, "letmeknow-server").apply { isDaemon = true }.start()
        announce(app)
    }

    private fun acceptLoop() {
        while (true) {
            try {
                ServerSocket(Protocol.PORT).use { server ->
                    server.reuseAddress = true
                    while (true) {
                        val s = server.accept()
                        Thread({ serve(s) }, "letmeknow-radio").apply { isDaemon = true }.start()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server stopped, starting again", e)
                Thread.sleep(3000)
            }
        }
    }

    private fun serve(s: Socket) {
        // The newest connection is the radio; an older one is a radio that went to sleep without saying goodbye.
        client?.let { runCatching { it.close() } }
        client = s
        s.tcpNoDelay = true
        s.soTimeout = Protocol.SILENT_MS.toInt()
        val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        writer = w
        radio = s.inetAddress.hostAddress
        Log.i(TAG, "Radio connected from $radio")
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (msg.optString("t")) {
                    Protocol.HELLO -> {
                        send(Protocol.message(Protocol.HELLO) { put("v", Protocol.VERSION).put("phone", android.os.Build.MODEL) })
                        val all = org.json.JSONArray()
                        snapshot().forEach { all.put(it) }
                        send(Protocol.message(Protocol.ALL) { put("list", all) })
                        app?.let { tone(it) }?.let { (bytes, name) ->
                            send(Protocol.message(Protocol.TONE) {
                                put("sound", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).put("name", name)
                            })
                        }
                    }
                    Protocol.PING -> send(Protocol.message(Protocol.PONG))
                    else -> main.post { onCommand(msg) }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Radio connection ended: ${e.message}")
        } finally {
            runCatching { s.close() }
            if (client === s) {
                client = null
                writer = null
                radio = null
            }
        }
    }

    /** Sends one line to the radio, if it is connected. Never blocks the caller. */
    fun send(line: String) {
        writes.execute {
            val w = writer ?: return@execute
            try {
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (e: Exception) {
                Log.i(TAG, "Could not send: ${e.message}")
                runCatching { client?.close() }
            }
        }
    }

    /** The phone on the network as "LetMeKnow", so the radio finds it even when it is not the Wi-Fi's gateway. */
    private fun announce(context: Context) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = manager
        registration?.let { old -> runCatching { manager.unregisterService(old) } }
        val info = NsdServiceInfo().apply {
            serviceName = Protocol.SERVICE_NAME
            serviceType = Protocol.SERVICE_TYPE
            port = Protocol.PORT
        }
        try {
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "NSD failed: $code") }
                override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = Unit
            }
            registration = listener
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD not available", e)
        }
    }

    /** Says the phone's name on the Wi-Fi again, after the local network was allowed. */
    fun announceAgain(context: Context) {
        if (started) main.post { announce(context.applicationContext) }
    }

    /** The phone's own addresses on Wi-Fi or its hotspot, shown on the main screen to help when something fails. */
    fun addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${ni.name})" } }
    } catch (e: Exception) {
        emptyList()
    }
}
