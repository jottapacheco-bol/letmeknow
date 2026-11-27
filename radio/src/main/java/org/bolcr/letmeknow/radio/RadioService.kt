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
        Crash.install(this)
        Emoji.start(this)
        Settings.load(this)
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
                val all = (0 until (list?.length() ?: 0)).mapNotNull { runCatching { Notice.fromJson(list!!.getJSONObject(it)) }.getOrNull() }
                // What was ignored with "Listo" doesn't come back.
                val shown = all.filter { !Settings.ignored(it) }
                Settings.hearAll(this, shown)
                Inbox.replace(shown) { Settings.ignoredTime(it) }
            }
            Protocol.POST -> msg.optJSONObject("n")?.let { runCatching { Notice.fromJson(it) }.getOrNull() }?.let {
                if (Settings.ignored(it)) Inbox.remove(it.key) else Inbox.put(it, Settings.fresh(this, it), Settings.ignoredTime(it))
            }
            Protocol.REMOVE -> Inbox.remove(msg.optString("key"))
            Protocol.TONE -> runCatching {
                val bytes = android.util.Base64.decode(msg.optString("sound"), android.util.Base64.DEFAULT)
                if (bytes.isNotEmpty()) java.io.File(filesDir, Speaker.TONE_FILE).writeBytes(bytes)
            }
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
        canReply = true,
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

/**
 * When the app falls, what went wrong is kept on the radio and shown on LetMeKnow's screen next time, so a photo of
 * it says where to fix it.
 */
object Crash {
    private fun prefs(c: Context) = c.getSharedPreferences("crash", Context.MODE_PRIVATE)

    fun install(c: Context) {
        val app = c.applicationContext
        val before = Thread.getDefaultUncaughtExceptionHandler()
        if (before is Catcher) return
        Thread.setDefaultUncaughtExceptionHandler(Catcher(app, before))
    }

    private class Catcher(val app: Context, val before: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val trace = android.util.Log.getStackTraceString(e).lines()
                    .filter { it.isNotBlank() }
                    .take(14).joinToString("\n")
                prefs(app).edit().putLong("at", System.currentTimeMillis())
                    .putString("what", "${BuildConfig.VERSION_NAME}\n$trace").commit()
            }
            before?.uncaughtException(t, e)
        }
    }

    /** The last fall, or null. */
    fun last(c: Context): String? = prefs(c).getString("what", null)
    fun at(c: Context): Long = prefs(c).getLong("at", 0L)
    fun clear(c: Context) = prefs(c).edit().clear().apply()
}

/** Today's emojis, carried in the app (the radio's Android 10 shows a box for the new ones). */
object Emoji {
    fun start(c: Context) {
        runCatching {
            androidx.emoji2.text.EmojiCompat.init(
                androidx.emoji2.bundled.BundledEmojiCompatConfig(c.applicationContext).setReplaceAll(true))
        }
    }

    /** [s] with its emojis drawn by the app, once they are loaded (before that, as it came). */
    fun of(s: CharSequence): CharSequence = runCatching {
        val e = androidx.emoji2.text.EmojiCompat.get()
        if (e.loadState == androidx.emoji2.text.EmojiCompat.LOAD_STATE_SUCCEEDED) e.process(s) ?: s else s
    }.getOrDefault(s)
}
