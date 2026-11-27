package org.bolcr.letmeknow.radio

import android.graphics.Bitmap
import org.bolcr.letmeknow.shared.Notice

/** One message kept on the radio, with what came in it (the phone only gives the newest one's picture or audio). */
data class Msg(
    val sender: String,
    val text: String,
    val time: Long,
    val media: Bitmap? = null,
    val video: Boolean = false,
    val length: String = "",
    val audio: ByteArray? = null,
    val voice: Boolean = false,
)

/**
 * The phone's notifications shown on the radio, newest first: one per conversation (WhatsApp keeps one notification
 * per chat, with the same key while messages come). Each keeps every message that came while it was not ignored or
 * marked read, even those the phone no longer shows, so the window can show the whole conversation. Used only on the
 * main thread.
 */
object Inbox {
    private val notices = ArrayList<Notice>()
    /** The messages of each conversation, oldest first: the bubble's number is how many. */
    private val history = HashMap<String, MutableList<Msg>>()
    private val watchers = LinkedHashSet<(Notice?) -> Unit>()

    val all: List<Notice> get() = notices.toList()

    fun get(key: String): Notice? = notices.firstOrNull { it.key == key }

    fun count(key: String): Int = (history[key]?.size ?: 0).coerceAtLeast(1)

    fun messages(key: String): List<Msg> = history[key]?.toList().orEmpty()

    /**
     * Adds what [n] brings to its conversation's messages; messages written up to [after] were ignored and stay
     * out.
     */
    private fun merge(n: Notice, after: Long) {
        val list = history.getOrPut(n.key) { ArrayList() }
        val lines = n.lines.ifEmpty { listOf(org.bolcr.letmeknow.shared.Line("", n.text, n.newest)) }
        val newest = lines.lastOrNull()
        for (l in lines) {
            if (l.text.isBlank()) continue
            if (l.time in 1..after) continue
            val known = list.any { it.text == l.text && it.sender == l.sender && (it.time == l.time || l.time == 0L || it.time == 0L) }
            if (known) continue
            val withMedia = l === newest
            list += Msg(
                sender = l.sender, text = l.text, time = l.time,
                media = if (withMedia) n.media else null,
                video = withMedia && n.video,
                length = if (withMedia) n.length else "",
                audio = if (withMedia) n.audio else null,
                voice = withMedia && n.voice,
            )
        }
        // The newest's picture can arrive in a later update of the same message.
        val last = list.lastOrNull()
        if (last != null && newest != null && last.text == newest.text && last.media == null && n.media != null) {
            list[list.size - 1] = last.copy(media = n.media, video = n.video, length = n.length)
        }
        if (last != null && newest != null && last.text == newest.text && last.audio == null && n.audio != null) {
            list[list.size - 1] = list.last().copy(audio = n.audio)
        }
        list.sortBy { it.time }
        while (list.size > 200) list.removeAt(0)
    }

    /**
     * A new or updated notification: it moves to the top. Not [fresh] (nothing new in it, the phone only showed it
     * again): it is updated where it is, quietly.
     */
    fun put(n: Notice, fresh: Boolean = true, after: Long = 0) {
        merge(n, after)
        if (!fresh) {
            val at = notices.indexOfFirst { it.key == n.key }
            if (at >= 0) notices[at] = n else { notices.add(n); notices.sortByDescending { it.time } }
            changed(null)
            return
        }
        notices.removeAll { it.key == n.key }
        notices.add(0, n)
        changed(n)
    }

    /** Everything the phone shows now, when the radio connects; [after] says what was ignored in each. */
    fun replace(list: List<Notice>, after: (Notice) -> Long) {
        notices.clear()
        notices.addAll(list.sortedByDescending { it.time })
        history.keys.retainAll(list.map { it.key }.toSet())
        list.forEach { merge(it, after(it)) }
        changed(null)
    }

    fun remove(key: String) {
        history.remove(key)
        if (notices.removeAll { it.key == key }) changed(null)
    }

    fun watch(w: (Notice?) -> Unit) { watchers.add(w) }

    fun unwatch(w: (Notice?) -> Unit) { watchers.remove(w) }

    private fun changed(arrived: Notice?) = watchers.toList().forEach { it(arrived) }
}
