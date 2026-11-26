package org.bolcr.letmeknow.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import org.bolcr.letmeknow.shared.Notice
import org.bolcr.letmeknow.shared.Protocol
import org.json.JSONObject

/**
 * Always running on the radio (it starts with the radio): keeps the connection to the phone and the bubbles.
 */
class RadioService : Service() {
    private lateinit var link: Link
    private lateinit var overlay: Overlay

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notice())
        overlay = Overlay(this) { link.send(it) }
        link = Link(this, ::message) { host ->
            phone = host
            watchers.toList().forEach { it() }
        }
        overlay.start()
        link.start()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST -> Inbox.put(sample())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = null
        link.stop()
        overlay.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun message(msg: JSONObject) {
        when (msg.optString("t")) {
            Protocol.ALL -> {
                val list = msg.optJSONArray("list")
                Inbox.replace((0 until (list?.length() ?: 0)).mapNotNull { runCatching { Notice.fromJson(list!!.getJSONObject(it)) }.getOrNull() })
            }
            Protocol.POST -> msg.optJSONObject("n")?.let { runCatching { Notice.fromJson(it) }.getOrNull() }?.let { Inbox.put(it) }
            Protocol.REMOVE -> Inbox.remove(msg.optString("key"))
        }
    }

    private fun notice(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("radio", "LetMeKnow", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "radio")
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("LetMeKnow")
            .setContentText("Esperando notificaciones del teléfono")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        return n
    }

    /** A message to see the bubbles without the phone (the radio's screen has "Probar"). */
    private fun sample() = Notice(
        key = "prueba:" + System.currentTimeMillis(),
        pkg = packageName,
        app = "LetMeKnow",
        title = "Prueba",
        text = "Así se ven los mensajes en el radio, en letra grande.",
        lines = emptyList(),
        time = System.currentTimeMillis(),
        voice = false,
        canReply = false,
        icon = null,
        photo = null,
    )

    companion object {
        const val ACTION_TEST = "org.bolcr.letmeknow.radio.TEST"
        @Volatile var running: RadioService? = null
            private set
        /** The phone's address while connected, for the radio's screen. */
        @Volatile var phone: String? = null
            private set
        val watchers = LinkedHashSet<() -> Unit>()

        fun start(c: Context, action: String? = null) {
            val i = Intent(c, RadioService::class.java).setAction(action)
            c.startForegroundService(i)
        }
    }
}

/** The radio was turned on (or LetMeKnow updated): start listening for the phone. */
class Starter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RadioService.start(context)
    }
}
