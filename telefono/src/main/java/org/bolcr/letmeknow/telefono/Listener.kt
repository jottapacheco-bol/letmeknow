package org.bolcr.letmeknow.telefono

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.bolcr.letmeknow.shared.Line
import org.bolcr.letmeknow.shared.Notice
import org.bolcr.letmeknow.shared.Protocol
import org.bolcr.letmeknow.shared.Ui
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Android hands every notification of the phone to this service (once the pastor allows it in the phone's settings).
 * The ones of the chosen apps go to the radio; touching "Ver en el teléfono" on the radio comes back here and does
 * what touching the notification would.
 */
class Listener : NotificationListenerService() {
    // Also used from the connection's thread (the list for a radio that just connected).
    private val icons = ConcurrentHashMap<String, Bitmap>()
    private val labels = ConcurrentHashMap<String, String>()
    /** What was last sent for each key, so an update that changes nothing is not sent again. */
    private val sent = ConcurrentHashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
        Server.snapshot = { current().map { it.toJson() } }
        Server.onCommand = ::command
        Server.start(this)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // No radio: nothing is prepared. When it connects it gets everything shown at that moment.
        if (Server.radio == null) return
        val notice = toNotice(sbn) ?: return
        val sig = notice.title + "\u0000" + notice.text + "\u0000" + notice.lines.size
        if (sent[sbn.key] == sig) return
        sent[sbn.key] = sig
        Server.send(Protocol.message(Protocol.POST) { put("n", notice.toJson()) })
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sent.remove(sbn.key) != null) Server.send(Protocol.message(Protocol.REMOVE) { put("key", sbn.key) })
    }

    /** Every notification of the chosen apps shown now on the phone, for a radio that just connected. */
    private fun current(): List<Notice> {
        val all = try { activeNotifications?.toList().orEmpty() } catch (e: Exception) { emptyList() }
        return all.mapNotNull { sbn -> toNotice(sbn)?.also { sent[sbn.key] = it.title + "\u0000" + it.text + "\u0000" + it.lines.size } }
    }

    private fun command(msg: JSONObject) {
        when (msg.optString("t")) {
            Protocol.OPEN -> open(msg.optString("key"))
            Protocol.REPLY -> reply(msg.optString("key"), msg.optString("text"))
        }
    }

    /** Does what touching the notification on the phone does: usually opens the chat in WhatsApp. */
    private fun open(key: String) {
        val sbn = try { activeNotifications?.firstOrNull { it.key == key } } catch (e: Exception) { null }
        val intent = sbn?.notification?.contentIntent
        if (intent == null) {
            // Already read on the phone, or a test: open the app itself.
            val pkg = sbn?.packageName ?: return
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
            return
        }
        try {
            // From Android 14 an app in the background may start a screen only if it says so; "Mostrar sobre otras
            // apps" (asked on the main screen) is what lets it.
            val options = if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            intent.send(this, 0, null, null, null, null, options)
            if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) cancelNotification(key)
        } catch (e: PendingIntent.CanceledException) {
            Log.w("LetMeKnow", "The notification can no longer be opened", e)
        }
    }

    /** WhatsApp's "Responder": the dictated or quick text goes back as if typed in the notification. */
    private fun reply(key: String, text: String) {
        if (text.isBlank()) return
        val sbn = try { activeNotifications?.firstOrNull { it.key == key } } catch (e: Exception) { null } ?: return
        val action = replyAction(sbn.notification) ?: return
        val inputs = action.remoteInputs ?: return
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        val intent = android.content.Intent().addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        try {
            action.actionIntent.send(this, 0, intent)
        } catch (e: PendingIntent.CanceledException) {
            Log.w("LetMeKnow", "Could not reply", e)
        }
    }

    private fun replyAction(n: Notification): Notification.Action? {
        val actions = n.actions ?: return null
        val withInput = actions.filter { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        if (Build.VERSION.SDK_INT >= 28) {
            withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }?.let { return it }
        }
        return withInput.firstOrNull()
    }

    private fun toNotice(sbn: StatusBarNotification): Notice? {
        val pkg = sbn.packageName
        if (pkg == packageName || !Choices.isOn(this, pkg)) return null
        val n = sbn.notification
        // WhatsApp also posts a summary of all its chats; ongoing ones are music, navigation, calls in progress.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (sbn.isOngoing) return null
        val ex = n.extras
        val lines = messages(ex)
        val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty() && lines.isEmpty()) return null
        val last = lines.lastOrNull()?.text ?: text
        return Notice(
            key = sbn.key,
            pkg = pkg,
            app = label(pkg),
            title = title,
            text = text,
            lines = lines,
            time = sbn.postTime,
            voice = VOICE.containsMatchIn(last),
            canReply = replyAction(n) != null,
            icon = icon(pkg),
            photo = photo(n),
        )
    }

    /** The last messages of a chat, when the app uses Android's messaging style (WhatsApp, Messages, Telegram). */
    private fun messages(ex: Bundle): List<Line> {
        if (Build.VERSION.SDK_INT < 28) return emptyList()
        @Suppress("DEPRECATION")
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return try {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr)
                .takeLast(6)
                .map { Line(it.senderPerson?.name?.toString().orEmpty(), it.text?.toString().orEmpty()) }
                .filter { it.text.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun icon(pkg: String): Bitmap? = icons[pkg] ?: try {
        Ui.bitmap(packageManager.getApplicationIcon(pkg), 144).also { icons[pkg] = it }
    } catch (e: Exception) {
        null
    }

    private fun photo(n: Notification): Bitmap? = try {
        n.getLargeIcon()?.loadDrawable(this)?.let { Ui.bitmap(it, 192) }
    } catch (e: Exception) {
        null
    }

    companion object {
        @Volatile var instance: Listener? = null
            private set

        /** How WhatsApp (and others) say "voice message" in a notification, in Spanish and English. */
        val VOICE = Regex("🎤|mensaje de voz|nota de voz|voice message|audio \\(", RegexOption.IGNORE_CASE)
    }
}
