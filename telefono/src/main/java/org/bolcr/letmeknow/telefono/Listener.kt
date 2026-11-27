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
        // Android (Samsung above all) sometimes lets go of the listener; ask for it back, or nothing reaches the radio
        // until LetMeKnow is opened.
        rebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // No radio: nothing is prepared. When it connects it gets everything shown at that moment.
        if (Server.radio == null) return
        val notice = toNotice(sbn) ?: return
        val sig = signature(notice)
        if (sent[sbn.key] == sig) return
        sent[sbn.key] = sig
        Server.send(Protocol.message(Protocol.POST) { put("n", notice.toJson()) })
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // The radio may still show it (its window stays open): answering or opening it must still work.
        synchronized(gone) { gone[sbn.key] = sbn }
        if (sent.remove(sbn.key) != null) Server.send(Protocol.message(Protocol.REMOVE) { put("key", sbn.key) })
    }

    /** Every notification of the chosen apps shown now on the phone, for a radio that just connected. */
    private fun current(): List<Notice> {
        val all = try { activeNotifications?.toList().orEmpty() } catch (e: Exception) { emptyList() }
        return all.mapNotNull { sbn -> toNotice(sbn)?.also { sent[sbn.key] = signature(it) } }
    }

    private fun signature(n: Notice) =
        n.title + "\u0000" + n.text + "\u0000" + n.lines.size + "\u0000" + n.newest + "\u0000" + (n.media != null) + (n.audio?.size ?: 0)

    private fun command(msg: JSONObject) {
        when (msg.optString("t")) {
            Protocol.OPEN -> open(msg.optString("key"))
            Protocol.REPLY -> reply(msg.optString("key"), msg.optString("text"))
            Protocol.MARK_READ -> markRead(msg.optString("key"))
        }
    }

    /** Does what touching the notification on the phone does: usually opens the chat in WhatsApp. */
    private fun open(key: String) {
        val sbn = find(key)
        val intent = sbn?.notification?.contentIntent
        if (intent == null) {
            // Already read on the phone, or a test: open the app itself.
            val pkg = sbn?.packageName ?: return
            if (sbn.user != android.os.Process.myUserHandle()) {
                val apps = getSystemService(android.content.pm.LauncherApps::class.java)
                apps.getActivityList(pkg, sbn.user).firstOrNull()?.let { apps.startMainActivity(it.componentName, sbn.user, null, null) }
                return
            }
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
        val sbn = find(key) ?: return
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

    /** WhatsApp's "Marcar como leído": marks the chat read in the app itself, as if pressed on the phone. */
    private fun markRead(key: String) {
        val sbn = find(key) ?: return
        val action = readAction(sbn.notification) ?: return
        try {
            action.actionIntent.send()
        } catch (e: PendingIntent.CanceledException) {
            Log.w("LetMeKnow", "Could not mark as read", e)
        }
    }

    private fun readAction(n: Notification): Notification.Action? {
        val actions = n.actions ?: return null
        if (Build.VERSION.SDK_INT >= 28) {
            actions.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ }?.let { return it }
        }
        return actions.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            a.remoteInputs.isNullOrEmpty() && (t.contains("leído") || t.contains("leido") || t.contains("read"))
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
        // The work profile's notifications come here too (unless its administrator forbids it).
        val work = sbn.user != android.os.Process.myUserHandle()
        if (pkg == packageName || !Choices.isOn(this, Choices.id(pkg, work))) return null
        val n = sbn.notification
        // WhatsApp also posts a summary of all its chats; ongoing ones are music, navigation, calls in progress.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (sbn.isOngoing) return null
        val ex = n.extras
        val all = rawMessages(ex)
        // Every message the notification still has (WhatsApp keeps the unread ones), so the radio can show them all.
        val lines = all.takeLast(40)
            .map { Line(it.senderPerson?.name?.toString().orEmpty(), it.text?.toString().orEmpty(), it.timestamp) }
            .filter { it.text.isNotBlank() }
        val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty() && lines.isEmpty()) return null
        val last = lines.lastOrNull()?.text ?: text
        val m = media(n, all.lastOrNull())
        return Notice(
            key = sbn.key,
            pkg = pkg,
            app = label(pkg, sbn.user),
            title = title,
            text = text,
            lines = lines,
            time = sbn.postTime,
            voice = VOICE.containsMatchIn(last),
            canReply = replyAction(n) != null,
            canRead = readAction(n) != null,
            icon = icon(pkg, sbn.user),
            photo = photo(n),
            media = m.picture,
            video = m.video,
            length = m.length,
            audio = m.audio,
            audioType = m.audioType,
            work = work,
            newest = all.lastOrNull()?.timestamp?.takeIf { it > 0 } ?: n.`when`.takeIf { it > 0 } ?: 0,
            // WhatsApp's title changes with the number of messages; its chat's shortcut (or tag) does not.
            chat = n.shortcutId?.takeIf { it.isNotBlank() } ?: sbn.tag?.takeIf { it.isNotBlank() } ?: "",
        )
    }

    /** The messages of a chat, when the app uses Android's messaging style (WhatsApp, Messages, Telegram). */
    private fun rawMessages(ex: Bundle): List<Notification.MessagingStyle.Message> {
        if (Build.VERSION.SDK_INT < 28) return emptyList()
        @Suppress("DEPRECATION")
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return try {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The last notifications taken away (opening WhatsApp on the phone clears them), by key. */
    private val gone = object : LinkedHashMap<String, StatusBarNotification>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StatusBarNotification>?) = size > 10
    }

    private fun find(key: String): StatusBarNotification? =
        (try { activeNotifications?.firstOrNull { it.key == key } } catch (e: Exception) { null })
            ?: synchronized(gone) { gone[key] }

    private class Media(
        val picture: Bitmap? = null, val video: Boolean = false, val length: String = "",
        val audio: ByteArray? = null, val audioType: String = "",
    )

    /** Remembered by address, so each new message of the chat doesn't read the same picture again. */
    private val mediaCache = object : LinkedHashMap<String, Media>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Media>?) = size > 6
    }

    /**
     * What came in the message besides words: the last message's picture, video or audio (the app gives the
     * address and Android lets this service read it), or a big picture of the notification. Nothing, if the app
     * doesn't share it (WhatsApp usually keeps voice messages to itself).
     */
    private fun media(n: Notification, last: Notification.MessagingStyle.Message?): Media {
        val uri = last?.dataUri
        val type = last?.dataMimeType.orEmpty()
        if (uri != null && type.isNotEmpty()) {
            val known = synchronized(mediaCache) { mediaCache[uri.toString()] }
            if (known != null) return known
            val got = try {
                when {
                    type.startsWith("image/") -> Media(picture = picture(uri))
                    type.startsWith("video/") -> Media(picture = frame(uri), video = true, length = length(uri))
                    type.startsWith("audio/") -> Media(audio = sound(uri), audioType = type)
                    else -> null
                }
            } catch (e: Exception) {
                Log.w("LetMeKnow", "Could not read what came in the message", e)
                null
            }
            if (got != null && (got.picture != null || got.audio != null)) {
                synchronized(mediaCache) { mediaCache[uri.toString()] = got }
                return got
            }
        }
        return Media(picture = bigPicture(n))
    }

    @Suppress("DEPRECATION")
    private fun bigPicture(n: Notification): Bitmap? = try {
        val ex = n.extras
        val b = ex.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
            ?: if (Build.VERSION.SDK_INT >= 31) ex.getParcelable<android.graphics.drawable.Icon>(Notification.EXTRA_PICTURE_ICON)
                ?.loadDrawable(this)?.let { Ui.bitmap(it, MEDIA) } else null
        b?.let { fit(it) }
    } catch (e: Exception) {
        null
    }

    private fun picture(uri: android.net.Uri): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MEDIA) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }?.let { fit(it) }
    }

    private fun frame(uri: android.net.Uri): Bitmap? = try {
        contentResolver.loadThumbnail(uri, android.util.Size(MEDIA, MEDIA), null)
    } catch (e: Exception) {
        val r = android.media.MediaMetadataRetriever()
        try { r.setDataSource(this, uri); r.frameAtTime?.let { fit(it) } } finally { r.release() }
    }

    private fun length(uri: android.net.Uri): String {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            val s = (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return "") / 1000
            "%d:%02d".format(s / 60, s % 60)
        } catch (e: Exception) {
            ""
        } finally {
            r.release()
        }
    }

    /** The audio itself, if it is not too big to send over the Wi-Fi in one go. */
    private fun sound(uri: android.net.Uri): ByteArray? = contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > MAX_AUDIO) return null
        }
        out.toByteArray()
    }

    private fun fit(b: Bitmap): Bitmap {
        val big = maxOf(b.width, b.height)
        if (big <= MEDIA) return b
        val k = MEDIA.toFloat() / big
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    /** The app as installed in that profile: in the work profile its icon carries the briefcase. */
    private fun info(pkg: String, user: android.os.UserHandle): android.content.pm.ApplicationInfo =
        if (user == android.os.Process.myUserHandle()) packageManager.getApplicationInfo(pkg, 0)
        else getSystemService(android.content.pm.LauncherApps::class.java).getApplicationInfo(pkg, 0, user)

    private fun label(pkg: String, user: android.os.UserHandle): String = labels.getOrPut("$pkg/$user") {
        try {
            packageManager.getApplicationLabel(info(pkg, user)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun icon(pkg: String, user: android.os.UserHandle): Bitmap? = icons["$pkg/$user"] ?: try {
        val plain = info(pkg, user).loadIcon(packageManager)
        val d = if (user == android.os.Process.myUserHandle()) plain else packageManager.getUserBadgedIcon(plain, user)
        Ui.bitmap(d, 144).also { icons["$pkg/$user"] = it }
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

        /** Asks Android to bind the listener again, when it is allowed but not running. */
        fun rebind(c: android.content.Context) {
            runCatching { NotificationListenerService.requestRebind(android.content.ComponentName(c, Listener::class.java)) }
        }

        /** How WhatsApp (and others) say "voice message" in a notification, in Spanish and English. */
        /** Pictures go at most this big (pixels on the long side): sharp on the radio, small over the Wi-Fi. */
        const val MEDIA = 720
        const val MAX_AUDIO = 3 * 1024 * 1024
        val VOICE = Regex("🎤|mensaje de voz|nota de voz|voice message|audio \\(", RegexOption.IGNORE_CASE)
    }
}
