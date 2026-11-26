package org.bolcr.letmeknow.radio

import org.bolcr.letmeknow.shared.Notice

/**
 * The phone's notifications shown on the radio, newest first: one per conversation (WhatsApp keeps one notification
 * per chat, with the same key while messages come). Used only on the main thread.
 */
object Inbox {
    private val notices = ArrayList<Notice>()
    /** How many messages came in each conversation since it was last opened on the radio: the bubble's number. */
    private val counts = HashMap<String, Int>()
    private val watchers = LinkedHashSet<(Notice?) -> Unit>()

    val all: List<Notice> get() = notices.toList()

    fun get(key: String): Notice? = notices.firstOrNull { it.key == key }

    fun count(key: String): Int = (counts[key] ?: 0).coerceAtLeast(1)

    /** A new or updated notification: it moves to the top and its number goes up. */
    fun put(n: Notice) {
        val before = get(n.key)
        notices.removeAll { it.key == n.key }
        notices.add(0, n)
        val added = if (before == null) n.lines.size.coerceAtLeast(1) else 1
        counts[n.key] = (counts[n.key] ?: 0) + added
        changed(n)
    }

    /** Everything the phone shows now, when the radio connects. */
    fun replace(list: List<Notice>) {
        notices.clear()
        notices.addAll(list.sortedByDescending { it.time })
        counts.keys.retainAll(list.map { it.key }.toSet())
        list.forEach { if (it.key !in counts) counts[it.key] = it.lines.size.coerceAtLeast(1) }
        changed(null)
    }

    fun remove(key: String) {
        counts.remove(key)
        if (notices.removeAll { it.key == key }) changed(null)
    }

    fun seen(key: String) {
        counts[key] = 0
        changed(null)
    }

    fun watch(w: (Notice?) -> Unit) { watchers.add(w) }

    fun unwatch(w: (Notice?) -> Unit) { watchers.remove(w) }

    private fun changed(arrived: Notice?) = watchers.toList().forEach { it(arrived) }
}
