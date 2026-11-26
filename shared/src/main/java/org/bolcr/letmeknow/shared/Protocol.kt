package org.bolcr.letmeknow.shared

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * What the phone and the radio say to each other over the phone's Wi-Fi: one JSON object per line, over one TCP
 * connection that the radio opens to the phone.
 *
 * Phone to radio: `hello`, `all` (every notification shown now, after hello), `post` (a new or changed one),
 * `remove` (it was read or dismissed on the phone), `pong`.
 * Radio to phone: `hello`, `open` (do what touching the notification on the phone does), `reply` (answer the chat
 * with a text, dictated or quick), `ping`.
 */
object Protocol {
    /** The phone listens here. */
    const val PORT = 47800
    /** How the phone is announced on the network (NSD / mDNS). */
    const val SERVICE_TYPE = "_letmeknow._tcp."
    const val SERVICE_NAME = "LetMeKnow"
    const val VERSION = 1

    /** The radio pings this often, and gives up on a connection that said nothing for [SILENT_MS]. */
    const val PING_MS = 10_000L
    const val SILENT_MS = 30_000L

    const val HELLO = "hello"
    const val ALL = "all"
    const val POST = "post"
    const val REMOVE = "remove"
    const val OPEN = "open"
    const val REPLY = "reply"
    const val PING = "ping"
    const val PONG = "pong"

    fun message(type: String, build: JSONObject.() -> Unit = {}): String =
        JSONObject().put("t", type).apply(build).toString()
}

/** One line of a conversation (WhatsApp sends the last few messages of a chat in one notification). */
data class Line(val sender: String, val text: String)

/** A notification as the radio shows it. */
data class Notice(
    /** The phone's key for it: the same chat keeps the same key while new messages come. */
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val lines: List<Line>,
    val time: Long,
    /** A voice message: the notification has no audio, only says it is one. */
    val voice: Boolean,
    /** The app lets answer from the notification (WhatsApp's "Responder"). */
    val canReply: Boolean,
    val icon: Bitmap?,
    /** The contact's photo, when the app gives one. */
    val photo: Bitmap?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("pkg", pkg)
        .put("app", app)
        .put("title", title)
        .put("text", text)
        .put("lines", JSONArray().apply { lines.forEach { put(JSONObject().put("s", it.sender).put("x", it.text)) } })
        .put("time", time)
        .put("voice", voice)
        .put("reply", canReply)
        .apply {
            icon?.let { put("icon", Images.encode(it)) }
            photo?.let { put("photo", Images.encode(it)) }
        }

    companion object {
        fun fromJson(o: JSONObject): Notice {
            val lines = o.optJSONArray("lines")
            return Notice(
                key = o.getString("key"),
                pkg = o.optString("pkg"),
                app = o.optString("app"),
                title = o.optString("title"),
                text = o.optString("text"),
                lines = (0 until (lines?.length() ?: 0)).map {
                    val l = lines!!.getJSONObject(it)
                    Line(l.optString("s"), l.optString("x"))
                },
                time = o.optLong("time", System.currentTimeMillis()),
                voice = o.optBoolean("voice"),
                canReply = o.optBoolean("reply"),
                icon = Images.decode(o.optString("icon")),
                photo = Images.decode(o.optString("photo")),
            )
        }
    }
}

object Images {
    fun encode(b: Bitmap): String {
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun decode(s: String?): Bitmap? {
        if (s.isNullOrEmpty()) return null
        return try {
            val bytes = Base64.decode(s, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
