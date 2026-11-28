package org.bolcr.letmeknow.telefono

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.bolcr.letmeknow.R

/**
 * Keeps LetMeKnow awake on the phone. Without it Android (Samsung above all) freezes the app when nothing happens on
 * the phone for a while: the radio then can't connect when the car starts, and only a new message wakes the app up.
 * It shows a quiet, small notification (Android asks for one) and does nothing else.
 */
class KeepAlive : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Esperando al radio", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("LetMeKnow")
            .setContentText("Listo para el radio del carro")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "awake"

        /** Starts it, if Android lets now (it may refuse from the background; the next try is when the app opens). */
        fun start(c: Context) {
            try {
                c.startForegroundService(Intent(c, KeepAlive::class.java))
            } catch (e: Exception) {
                Log.i("LetMeKnow", "Could not stay awake: ${e.message}")
            }
        }
    }
}

/** The phone was turned on (or LetMeKnow updated): awake again, without opening it. */
class Starter : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KeepAlive.start(context)
        Listener.rebind(context)
    }
}
